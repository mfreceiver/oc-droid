package cn.vectory.ocdroid.data.cache

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cn.vectory.ocdroid.util.SettingsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R-20 Phase 0: SQLCipher DB passphrase provider.
 *
 * Generates a 32-byte random key on first call, stores it Base64-encoded under
 * [SettingsManager.CACHE_DB_KEY] in EncryptedSharedPreferences, and returns the
 * raw bytes for [net.zetetic.database.sqlcipher.SupportOpenHelperFactory].
 *
 * Key invariants (plan §1 CacheKeyStore):
 *  - **32 random bytes**, generated once via [SecureRandom].
 *  - **Never rotates** — rotation = data loss (the DB encrypted with the old
 *    key becomes unreadable). If the DB is ever observed corrupt / unreadable,
 *    [cn.vectory.ocdroid.di.CacheModule] catches the open failure and runs a
 *    destructive reset (delete DB file + [reset] the key) rather than trying
 *    to recover.
 *  - **Survives `clearAllLocalData`**: [SettingsManager.clearAllLocalData]'s
 *    preserved-keys whitelist references the same constant
 *    ([SettingsManager.CACHE_DB_KEY]) this store writes under — wiping the key
 *    on a reset would brick the cache.
 *
 * The EncryptedSharedPreferences file name `"ocdroid_settings"` matches
 * [SettingsManager]'s file so both classes observe the same encrypted store
 * (Android dedupes SharedPreferences by file name within a package). The
 * MasterKey uses the default androidx alias
 * (`_androidx_security_crypto_encryption_prefs_key_`), also identical to
 * SettingsManager's, so both readers decrypt with the same AndroidKeyStore-
 * backed key. The masterKey + ESP file are recreated here rather than
 * injected from SettingsManager to keep the cache layer self-contained.
 */
@Singleton
class CacheKeyStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "ocdroid_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Returns the 32-byte SQLCipher passphrase, generating + persisting it on
     * first call. The returned array is freshly allocated each call (callers
     * should NOT cache it across process restarts — read it each time the DB
     * is opened so [reset] takes effect immediately).
     */
    fun getOrCreateKey(): ByteArray {
        val existing = encryptedPrefs.getString(SettingsManager.CACHE_DB_KEY, null)
        if (existing != null) {
            val decoded = runCatching { Base64.decode(existing, Base64.NO_WRAP) }
                .getOrElse { return generateAndStore() }
            // Defensive: a corrupt or truncated value (e.g. old/shorter key)
            // must not silently feed SQLCipher a wrong-length passphrase —
            // SupportOpenHelperFactory will hang the DB open otherwise.
            if (decoded.size == KEY_SIZE_BYTES) return decoded
        }
        return generateAndStore()
    }

    /**
     * Wipes the persisted key. Used by [cn.vectory.ocdroid.di.CacheModule]'s
     * destructive-reset fallback when the DB refuses to open — the next
     * [getOrCreateKey] generates a fresh key (the old cache being unreadable
     * anyway since the file was deleted).
     */
    fun reset() {
        encryptedPrefs.edit().remove(SettingsManager.CACHE_DB_KEY).apply()
    }

    private fun generateAndStore(): ByteArray {
        val key = ByteArray(KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        val encoded = Base64.encodeToString(key, Base64.NO_WRAP)
        encryptedPrefs.edit().putString(SettingsManager.CACHE_DB_KEY, encoded).apply()
        return key
    }

    private companion object {
        /** SQLCipher 4.x recommended passphrase size for full AES-256 spread. */
        private const val KEY_SIZE_BYTES = 32
    }
}
