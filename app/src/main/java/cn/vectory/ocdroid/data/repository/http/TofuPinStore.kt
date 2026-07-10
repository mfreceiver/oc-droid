package cn.vectory.ocdroid.data.repository.http

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §tofu R2: SSH-style trust-on-first-use pin store, keyed by `host:port`
 * (known_hosts model — shared across profiles that hit the same endpoint;
 * connecting via a different name/IP re-prompts; grill Q4=a).
 *
 * Two tiers:
 *  - **persistent** (EncryptedSharedPreferences, file "ocdroid_tofu",
 *    key `tofu_pin_<hostPort>` → SPKI SHA-256 hex): the "Trust" decision.
 *    Survives cold start.
 *  - **session** (in-process map): the "Accept once" decision. Lost on
 *    process death → next cold start re-prompts. (grill Q5 accept-once)
 *
 * [pinnedSpki] returns persistent || session. Persistent wins on conflict
 * (a later session-only accept cannot shadow a persisted trust).
 *
 * The pinned value is the **leaf certificate's SPKI SHA-256** (SubjectPublicKeyInfo
 * hash — grill Q3=b): stable across cosmetic leaf renewals that reuse the same
 * keypair; only a true key rotation re-prompts.
 */
interface TofuPinStore {
    /** Persistent || session SPKI hex for [hostPort], or null when neither tier has it. */
    fun pinnedSpki(hostPort: String): String?

    /** "Trust": persist (cold-start-surviving) + mirror into session. */
    fun trustPersistent(hostPort: String, spki: String)

    /** "Accept once": session-only (cleared on process death). */
    fun acceptSession(hostPort: String, spki: String)

    /** Drop both tiers for [hostPort] (forget this endpoint). */
    fun clear(hostPort: String)
}

/**
 * §tofu R2: production backing — its own ESP file ("ocdroid_tofu") so TOFU
 * state is isolated from [cn.vectory.ocdroid.util.SettingsManager]'s prefs
 * (and from [cn.vectory.ocdroid.util.SettingsManager.clearAllLocalData]'s
 * key whitelist, which intentionally does NOT touch TOFU pins).
 */
@Singleton
class EspTofuPinStore @Inject constructor(
    @param:ApplicationContext context: Context
) : TofuPinStore {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ocdroid_tofu",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val session = ConcurrentHashMap<String, String>()

    private fun key(hostPort: String) = "tofu_pin_$hostPort"

    override fun pinnedSpki(hostPort: String): String? =
        prefs.getString(key(hostPort), null) ?: session[hostPort]

    override fun trustPersistent(hostPort: String, spki: String) {
        prefs.edit().putString(key(hostPort), spki).apply()
        session[hostPort] = spki
    }

    override fun acceptSession(hostPort: String, spki: String) {
        session[hostPort] = spki
    }

    override fun clear(hostPort: String) {
        prefs.edit().remove(key(hostPort)).apply()
        session.remove(hostPort)
    }
}

/**
 * §tofu R2: pure-JVM in-memory fake for unit tests (no Android/ESP). Same
 * two-tier semantics so tests can exercise Trust vs Accept-once isolation.
 */
class InMemoryTofuPinStore : TofuPinStore {
    private val persistent = ConcurrentHashMap<String, String>()
    private val session = ConcurrentHashMap<String, String>()
    override fun pinnedSpki(hostPort: String): String? = persistent[hostPort] ?: session[hostPort]
    override fun trustPersistent(hostPort: String, spki: String) {
        persistent[hostPort] = spki; session[hostPort] = spki
    }
    override fun acceptSession(hostPort: String, spki: String) { session[hostPort] = spki }
    override fun clear(hostPort: String) { persistent.remove(hostPort); session.remove(hostPort) }
}
