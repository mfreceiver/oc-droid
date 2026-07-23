package cn.vectory.ocdroid.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cn.vectory.ocdroid.data.model.SessionCacheEntry
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ocdroid's persisted-settings facade.
 *
 * Historically a ~900-line god-object that owned every EncryptedSharedPreferences
 * key directly. L4b (refactor-optimization-plan.md §3 Wave 4) split it into
 * per-domain Prefs helpers + a [MigrationHelper]; this class is now a thin
 * composition facade that:
 *  1. Creates the single [EncryptedSharedPreferences] instance (the
 *     `ocdroid_settings` backing store) exactly once — same file name, same
 *     MasterKey scheme, same key/value encryption schemes as pre-L4b. NO
 *     backing-store rename.
 *  2. Hands that one shared [SharedPreferences] instance to every domain
 *     Prefs, so all reads/writes hit the same encrypted file. A user
 *     upgrading across L4b sees byte-identical values.
 *  3. Re-exposes the pre-L4b public API verbatim (every property + function
 *     signature preserved) by 1:1 delegation, so all ~90 call sites keep
 *     resolving unchanged.
 *  4. Re-exports the pre-L4b public companion constants ([DEFAULT_SERVER],
 *     [LEGACY_BASIC_AUTH_PASSWORD_ID], [UI_SCALE_MIN], [UI_SCALE_MAX],
 *     [COMPOSITE_KEY_SEPARATOR]) from their owning domain Prefs.
 *
 * Domain ownership (see each Prefs file for the key→domain inventory):
 *  - [ConnectionPrefs] — connection scalars + ALL ESP per-host secrets
 *    (basic-auth / tunnel / mTLS client-cert triplets).
 *  - [NavigationPrefs] — session id + top-level nav persistence.
 *  - [WorkdirPrefs] — current workdir + reactive mirror + per-fp recent
 *    workdirs (+ cluster 17 `isValidFp` guard).
 *  - [AppearancePrefs] — theme / locale / UI scale / fonts / markdown fonts
 *    / persistent-notification visibility.
 *  - [SessionPrefs] — open tabs + session cache + per-(fp,sessionId) drafts.
 *  - [DebugPrefs] — verbose-diag + debug-card-identity toggles.
 *  - [TrafficPrefs] — cumulative HTTP byte counters.
 *  - [ModelPrefs] — per-fp disabled-model + model-availability sets.
 *  - [MigrationHelper] — one-shot legacy → per-fp migration (R-20 Phase 5).
 *
 * [clearAllLocalData] stays here because it is cross-domain: it iterates the
 * live ESP key set (which it sees via the shared instance) and preserves
 * exactly the connection + per-host-secret keys, then re-syncs the workdir
 * flow mirror.
 */
@Singleton
class SettingsManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "ocdroid_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val connectionPrefs = ConnectionPrefs(encryptedPrefs)
    private val navigationPrefs = NavigationPrefs(encryptedPrefs)
    private val workdirPrefs = WorkdirPrefs(encryptedPrefs)
    private val appearancePrefs = AppearancePrefs(encryptedPrefs)
    private val sessionPrefs = SessionPrefs(encryptedPrefs)
    private val debugPrefs = DebugPrefs(encryptedPrefs)
    private val trafficPrefs = TrafficPrefs(encryptedPrefs)
    private val modelPrefs = ModelPrefs(encryptedPrefs)
    private val migrationHelper = MigrationHelper(encryptedPrefs)

    // ── Connection / ESP-secret domain (ConnectionPrefs) ────────────────────

    var serverUrl: String
        get() = connectionPrefs.serverUrl
        set(value) { connectionPrefs.serverUrl = value }

    var username: String?
        get() = connectionPrefs.username
        set(value) { connectionPrefs.username = value }

    var password: String?
        get() = connectionPrefs.password
        set(value) { connectionPrefs.password = value }

    var hostProfilesJson: String?
        get() = connectionPrefs.hostProfilesJson
        set(value) { connectionPrefs.hostProfilesJson = value }

    var currentHostProfileId: String?
        get() = connectionPrefs.currentHostProfileId
        set(value) { connectionPrefs.currentHostProfileId = value }

    var effectiveConnectionSourceMarker: String?
        get() = connectionPrefs.effectiveConnectionSourceMarker
        set(value) { connectionPrefs.effectiveConnectionSourceMarker = value }

    fun basicAuthPassword(passwordId: String): String? =
        connectionPrefs.basicAuthPassword(passwordId)

    fun setBasicAuthPassword(passwordId: String, value: String?) =
        connectionPrefs.setBasicAuthPassword(passwordId, value)

    fun getTunnelPassword(id: String): String? = connectionPrefs.getTunnelPassword(id)

    fun setTunnelPassword(id: String, password: String?) =
        connectionPrefs.setTunnelPassword(id, password)

    fun clearTunnelPassword(id: String) = connectionPrefs.clearTunnelPassword(id)

    fun getClientCertP12(id: String): ByteArray? = connectionPrefs.getClientCertP12(id)

    fun setClientCertP12(id: String, bytes: ByteArray?) =
        connectionPrefs.setClientCertP12(id, bytes)

    fun getClientCertPassword(id: String): String? = connectionPrefs.getClientCertPassword(id)

    fun setClientCertPassword(id: String, value: String?) =
        connectionPrefs.setClientCertPassword(id, value)

    fun getClientCertCa(id: String): ByteArray? = connectionPrefs.getClientCertCa(id)

    fun setClientCertCa(id: String, bytes: ByteArray?) =
        connectionPrefs.setClientCertCa(id, bytes)

    fun clearClientCert(id: String) = connectionPrefs.clearClientCert(id)

    fun saveClientCert(id: String, p12: ByteArray?, password: String?, ca: ByteArray?) =
        connectionPrefs.saveClientCert(id, p12, password, ca)

    fun loadClientCertMaterial(clientCertId: String): ClientCertMaterial? =
        connectionPrefs.loadClientCertMaterial(clientCertId)

    // ── Navigation domain (NavigationPrefs) ────────────────────────────────

    var currentSessionId: String?
        get() = navigationPrefs.currentSessionId
        set(value) { navigationPrefs.currentSessionId = value }

    var lastNavPage: Int
        get() = navigationPrefs.lastNavPage
        set(value) { navigationPrefs.lastNavPage = value }

    var lastRoute: String
        get() = navigationPrefs.lastRoute
        set(value) { navigationPrefs.lastRoute = value }

    // ── Workdir domain (WorkdirPrefs) ───────────────────────────────────────

    val currentWorkdirFlow: StateFlow<String?>
        get() = workdirPrefs.currentWorkdirFlow

    var currentWorkdir: String?
        get() = workdirPrefs.currentWorkdir
        set(value) { workdirPrefs.currentWorkdir = value }

    fun getRecentWorkdirs(serverGroupFp: String): List<String> =
        workdirPrefs.getRecentWorkdirs(serverGroupFp)

    fun setRecentWorkdirs(serverGroupFp: String, workdirs: List<String>) =
        workdirPrefs.setRecentWorkdirs(serverGroupFp, workdirs)

    fun addRecentWorkdir(serverGroupFp: String, workdir: String) =
        workdirPrefs.addRecentWorkdir(serverGroupFp, workdir)

    fun removeRecentWorkdir(serverGroupFp: String, workdir: String) =
        workdirPrefs.removeRecentWorkdir(serverGroupFp, workdir)

    fun clearRecentWorkdirs(serverGroupFp: String) =
        workdirPrefs.clearRecentWorkdirs(serverGroupFp)

    // ── Appearance domain (AppearancePrefs) ────────────────────────────────

    var themeMode: ThemeMode
        get() = appearancePrefs.themeMode
        set(value) { appearancePrefs.themeMode = value }

    var persistentNotificationEnabled: Boolean
        get() = appearancePrefs.persistentNotificationEnabled
        set(value) { appearancePrefs.persistentNotificationEnabled = value }

    var localeMode: LocaleMode
        get() = appearancePrefs.localeMode
        set(value) { appearancePrefs.localeMode = value }

    var uiFontScale: Float
        get() = appearancePrefs.uiFontScale
        set(value) { appearancePrefs.uiFontScale = value }

    var uiContentScale: Float
        get() = appearancePrefs.uiContentScale
        set(value) { appearancePrefs.uiContentScale = value }

    var fontLatin: String
        get() = appearancePrefs.fontLatin
        set(value) { appearancePrefs.fontLatin = value }

    var fontCJK: String
        get() = appearancePrefs.fontCJK
        set(value) { appearancePrefs.fontCJK = value }

    var markdownFontLatin: String
        get() = appearancePrefs.markdownFontLatin
        set(value) { appearancePrefs.markdownFontLatin = value }

    var markdownFontCJK: String
        get() = appearancePrefs.markdownFontCJK
        set(value) { appearancePrefs.markdownFontCJK = value }

    var markdownFontSizes: MarkdownFontSizes
        get() = appearancePrefs.markdownFontSizes
        set(value) { appearancePrefs.markdownFontSizes = value }

    // ── Debug domain (DebugPrefs) ───────────────────────────────────────────

    var debugLogVerboseEnabled: Boolean
        get() = debugPrefs.debugLogVerboseEnabled
        set(value) { debugPrefs.debugLogVerboseEnabled = value }

    var debugCardIdentityEnabled: Boolean
        get() = debugPrefs.debugCardIdentityEnabled
        set(value) { debugPrefs.debugCardIdentityEnabled = value }

    // ── Session domain (SessionPrefs) ───────────────────────────────────────

    var openSessionIds: List<String>
        get() = sessionPrefs.openSessionIds
        set(value) { sessionPrefs.openSessionIds = value }

    var sessionCache: List<SessionCacheEntry>
        get() = sessionPrefs.sessionCache
        set(value) { sessionPrefs.sessionCache = value }

    fun getDraftText(serverGroupFp: String, sessionId: String): String =
        sessionPrefs.getDraftText(serverGroupFp, sessionId)

    fun setDraftText(serverGroupFp: String, sessionId: String, text: String) =
        sessionPrefs.setDraftText(serverGroupFp, sessionId, text)

    // ── Traffic domain (TrafficPrefs) ───────────────────────────────────────

    var trafficBytesSent: Long
        get() = trafficPrefs.trafficBytesSent
        set(value) { trafficPrefs.trafficBytesSent = value }

    var trafficBytesReceived: Long
        get() = trafficPrefs.trafficBytesReceived
        set(value) { trafficPrefs.trafficBytesReceived = value }

    var trafficResetAt: Long
        get() = trafficPrefs.trafficResetAt
        set(value) { trafficPrefs.trafficResetAt = value }

    // ── Model-management domain (ModelPrefs) ────────────────────────────────

    fun getDisabledModels(serverGroupFp: String): Set<String> =
        modelPrefs.getDisabledModels(serverGroupFp)

    fun setModelDisabled(serverGroupFp: String, providerId: String, modelId: String, disabled: Boolean) =
        modelPrefs.setModelDisabled(serverGroupFp, providerId, modelId, disabled)

    fun setDisabledModels(serverGroupFp: String, disabledKeys: Set<String>) =
        modelPrefs.setDisabledModels(serverGroupFp, disabledKeys)

    fun getModelAvailability(serverGroupFp: String): Set<String> =
        modelPrefs.getModelAvailability(serverGroupFp)

    fun setModelAvailability(serverGroupFp: String, availableKeys: Set<String>) =
        modelPrefs.setModelAvailability(serverGroupFp, availableKeys)

    fun clearModelDataForGroup(serverGroupFp: String) =
        modelPrefs.clearModelDataForGroup(serverGroupFp)

    // ── Migration domain (MigrationHelper) ──────────────────────────────────

    fun migrateLegacyKeysToFp(serverGroupFp: String, legacyBaseUrl: String) =
        migrationHelper.migrateLegacyKeysToFp(serverGroupFp, legacyBaseUrl)

    // ── Cross-domain hard reset ─────────────────────────────────────────────

    /**
     * Hard reset: wipes EVERY persisted key EXCEPT the connection-credential
     * keys and the per-host password secrets (basic-auth + tunnel), then leaves
     * the encrypted prefs otherwise intact for those preserved entries.
     *
     * PRESERVED (the "server connection info + tunnel passwords" invariant):
     *  - [ConnectionPrefs.KEY_SERVER_URL], [ConnectionPrefs.KEY_USERNAME],
     *    [ConnectionPrefs.KEY_PASSWORD] (legacy direct form)
     *  - [ConnectionPrefs.KEY_HOST_PROFILES], [ConnectionPrefs.KEY_CURRENT_HOST_PROFILE_ID]
     *    (the host list + the active profile id)
     *  - per-host basic-auth passwords (`basic_auth_password_*`) — these back
     *    [HostProfile.basicAuth.passwordId]; wiping them would silently break
     *    every saved host's authentication on reconnect.
     *  - per-host tunnel passwords (`tunnel_password_*`)
     *  - §2.3: per-host mTLS client certificates (`client_cert_p12_*` /
     *    `client_cert_pw_*` / `client_cert_ca_*`) — same semantics as the
     *    connection credentials: wiping them while [ConnectionPrefs.KEY_HOST_PROFILES]
     *    keeps `mtlsEnabled=true/clientCertId=<id>` would leave a dangling reference
     *    and mTLS would silently fail to present the cert (glmer I5 /
     *    gpter 重要#6).
     *
     * WIPED: open tabs, session metadata cache, drafts, current session/workdir,
     * nav page, theme + font preferences, traffic counters — everything not
     * listed above.
     *
     * §chat-ux-batch T8 (B3): the legacy per-session agent/model override
     * keys are no longer written by any code path (their getters/setters were
     * deleted); any stale orphan entries from a prior install are still wiped
     * by the iteration below (the keys are not in the preserved whitelist).
     *
     * §P5a (Q5): [AppearancePrefs.KEY_LOCALE] (language preference) is
     * INTENTIONALLY NOT in [preservedKeys] — a full data reset returns the
     * language to SYSTEM (first-launch default), alongside the theme/font
     * prefs. This keeps "Clear all local data" a true return-to-defaults for
     * appearance.
     *
     * Implementation iterates the live key set and `.remove()`s each non-
     * preserved key in a single batched edit. This deliberately avoids
     * `.clear()` (which would also nuke the connection keys) and never touches
     * the `basic_auth_password_*` / `tunnel_password_*` / `client_cert_*`
     * prefixes.
     */
    fun clearAllLocalData() {
        val connectionKeys = setOf(
            ConnectionPrefs.KEY_SERVER_URL,
            ConnectionPrefs.KEY_USERNAME,
            ConnectionPrefs.KEY_PASSWORD,
            ConnectionPrefs.KEY_HOST_PROFILES,
            ConnectionPrefs.KEY_CURRENT_HOST_PROFILE_ID,
            ConnectionPrefs.KEY_EFFECTIVE_CONNECTION_SOURCE,
        )
        // remove-message-persistence Task 6: the prior `+ CACHE_DB_KEY`
        // (R-20 Phase 0 SQLCipher DB passphrase) was deleted together with
        // the CacheRepository / CacheKeyStore / encrypted chat-cache surface.
        val preservedKeys = connectionKeys
        val e = encryptedPrefs.edit()
        for (k in encryptedPrefs.all.keys) {
            val preserved = k in preservedKeys ||
                k.startsWith("basic_auth_password_") ||
                k.startsWith("tunnel_password_") ||
                k.startsWith("client_cert_p12_") ||
                k.startsWith("client_cert_pw_") ||
                k.startsWith("client_cert_ca_")
            if (!preserved) e.remove(k)
        }
        e.apply()
        // §reactive-workdir: clearAllLocalData bypasses the currentWorkdir setter
        // (batched direct .remove()s), so re-sync the flow mirror. Assign null
        // DIRECTLY (opuser🟠-3 / kimo 0.6.1 round-1): clearAllLocalData just
        // .remove()d KEY_CURRENT_WORKDIR above (it is not in preservedKeys), so
        // the post-wipe ESP value is GUARANTEED null by construction. Reading
        // it back via ESP would re-introduce a theoretical race (a concurrent
        // setter between the remove() above and the read here) for no benefit;
        // the direct null assignment eliminates that window.
        workdirPrefs.resetWorkdirMirror()
    }

    companion object {
        const val DEFAULT_SERVER = ConnectionPrefs.DEFAULT_SERVER
        const val LEGACY_BASIC_AUTH_PASSWORD_ID = ConnectionPrefs.LEGACY_BASIC_AUTH_PASSWORD_ID
        const val UI_SCALE_MIN = AppearancePrefs.UI_SCALE_MIN
        const val UI_SCALE_MAX = AppearancePrefs.UI_SCALE_MAX
        const val COMPOSITE_KEY_SEPARATOR = SessionPrefs.COMPOSITE_KEY_SEPARATOR
    }
}

enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}

/**
 * §P5a (Q5): user-facing language preference. Mirrors [ThemeMode]'s shape
 * (top-level enum persisted by name in EncryptedSharedPreferences).
 *
 *  - [SYSTEM]: follow the real system locale. zh→zh, en→en, any other (or
 *    undetermined) → zh ("跟不上系统时选中文"). The real system locale is read
 *    via [androidx.core.os.LocaleManagerCompat.getSystemLocales] — NEVER
 *    `Locale.getDefault()` (that reports the app override after the first apply).
 *  - [ZH]: force Chinese.
 *  - [EN]: force English.
 */
enum class LocaleMode {
    SYSTEM, ZH, EN
}
