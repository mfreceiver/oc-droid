@file:Suppress("DEPRECATION")
// Holding pattern: androidx.security.crypto 1.1.0 stable API is @Deprecated by Google
// with no official 1:1 replacement (project spec forbids switching to DataStore).
// Suppress project-wide here until a replacement ships; revisit on security-crypto update.
package cn.vectory.ocdroid.data.repository.http

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §B2 (slimapi-v2-adapt-traffic-plan §B): device-id storage abstraction for
 * the `X-Client-Id` header.
 *
 * **Atomic get-or-create**: [getDeviceId] returns the effective id —
 *  1. the user override (see [setOverride]) when set and non-blank;
 *  2. else the persisted random UUIDv4, generating + persisting it on the
 *     first call (atomic against concurrent first-access — two threads
 *     hitting a never-seeded store at once MUST observe a single shared id).
 *
 * Persistence uses [EncryptedSharedPreferences] — the SAME crypto infra as
 * [cn.vectory.ocdroid.util.SessionPrefs] (AES256-SIV keys / AES256-GCM
 * values), per spec §B2 ("reuse SessionPrefs infra; do NOT introduce
 * DataStore"). Mirrors [EspTofuPinStore]: its own ESP file so device-id
 * state is isolated from [cn.vectory.ocdroid.util.SettingsManager]'s prefs.
 *
 * **Override**: [setOverride] lets a future settings screen supply a
 * user-chosen id. null / blank clears the override → [getDeviceId] falls
 * back to the random UUID. The override is NOT validated for OkHttp header
 * legality here; the [sanitizeClientIdentityHeaderValue] gate at the
 * injection site omits the header if the override produces an illegal value.
 */
interface ClientIdStore {
    /**
     * Effective device id (override if set+non-blank, else persisted/created
     * UUID). Never null for the random-UUID path (always creatable); the
     * nullable return covers pathological ESP-write failures so the injection
     * site omits just `X-Client-Id` rather than crashing the request.
     */
    fun getDeviceId(): String?

    /**
     * Sets / clears the user override. A null or blank [value] clears the
     * override so [getDeviceId] falls back to the random UUID.
     */
    fun setOverride(value: String?)
}

/**
 * §B2: production backing — its own ESP file ("ocdroid_client_id") so the
 * device id is isolated from [cn.vectory.ocdroid.util.SettingsManager]'s
 * prefs (and from its `clearAllLocalData` key whitelist). Mirrors
 * [EspTofuPinStore]'s ESP setup verbatim.
 *
 * **Atomicity** ([getDeviceId] get-or-create): the entire read→create→write
 * is serialized under [lock]. The first caller to enter generates + persists
 * the UUID via `commit()` (synchronous disk write); every concurrent / later
 * caller observes the now-persisted value. So two concurrent first-access
 * calls produce ONE id, not two.
 */
@Singleton
class EspClientIdStore @Inject constructor(
    @ApplicationContext context: Context
) : ClientIdStore {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ocdroid_client_id",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** Serializes the get-or-create RMW so concurrent first-access yields one id. */
    private val lock = Any()

    override fun getDeviceId(): String? = synchronized(lock) {
        // Override wins iff present AND non-blank.
        prefs.getString(KEY_OVERRIDE, null)?.takeIf { it.isNotBlank() }
            ?.let { return@synchronized it }
        // Existing random UUID (created on a prior first-access).
        prefs.getString(KEY_DEVICE_ID, null)?.let { return@synchronized it }
        // First-access: generate UUIDv4 + persist synchronously (commit, not
        // apply) so a concurrent caller entering after we release [lock]
        // observes this id rather than generating a second one. commit()
        // blocks until the write is durable in ESP's backing file.
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, newId).commit()
        newId
    }

    override fun setOverride(value: String?) {
        synchronized(lock) {
            if (value.isNullOrBlank()) {
                prefs.edit().remove(KEY_OVERRIDE).apply()
            } else {
                prefs.edit().putString(KEY_OVERRIDE, value).apply()
            }
        }
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        /** §B2: storage key for the user-overridable id (future settings screen). */
        private const val KEY_OVERRIDE = "client_id_override"
    }
}

/**
 * §B2: pure-JVM in-memory fake for unit tests (no Android / ESP). Same
 * get-or-create + override semantics as [EspClientIdStore] so the algorithm
 * (atomicity, override resolution, stability) is testable without Robolectric
 * — mirrors the [InMemoryTofuPinStore] / [EspTofuPinStore] split.
 *
 * [seedDeviceId] lets a test pre-seed the persisted id; when null (default),
 * the first [getDeviceId] call generates a fresh UUIDv4 under [lock].
 */
class InMemoryClientIdStore(
    seedDeviceId: String? = null,
) : ClientIdStore {
    @Volatile private var deviceId: String? = seedDeviceId
    @Volatile private var override: String? = null

    /** Serializes the get-or-create RMW (mirrors [EspClientIdStore.lock]). */
    private val lock = Any()

    override fun getDeviceId(): String? = synchronized(lock) {
        override?.takeIf { it.isNotBlank() }?.let { return@synchronized it }
        deviceId?.let { return@synchronized it }
        val newId = UUID.randomUUID().toString()
        deviceId = newId
        newId
    }

    override fun setOverride(value: String?) {
        synchronized(lock) { override = value }
    }
}
