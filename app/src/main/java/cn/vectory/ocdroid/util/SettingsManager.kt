package cn.vectory.ocdroid.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.SessionCacheEntry
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "ocdroid_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // §reactive-workdir: hot, observable mirror of [currentWorkdir]. Seeded from
    // ESP at construction so cold-start collectors see the persisted value
    // immediately (not null). Every setter write updates it; the only bypass is
    // [clearAllLocalData] (batched direct removes), which re-syncs at its tail.
    private val _currentWorkdirFlow = MutableStateFlow<String?>(
        encryptedPrefs.getString(KEY_CURRENT_WORKDIR, null)
    )
    val currentWorkdirFlow: StateFlow<String?> = _currentWorkdirFlow.asStateFlow()

    var serverUrl: String
        get() = encryptedPrefs.getString(KEY_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = encryptedPrefs.edit().putString(KEY_SERVER_URL, value).apply()

    var username: String?
        get() = encryptedPrefs.getString(KEY_USERNAME, null)
        set(value) = encryptedPrefs.edit().putString(KEY_USERNAME, value).apply()

    var password: String?
        get() = encryptedPrefs.getString(KEY_PASSWORD, null)
        set(value) = encryptedPrefs.edit().putString(KEY_PASSWORD, value).apply()

    var hostProfilesJson: String?
        get() = encryptedPrefs.getString(KEY_HOST_PROFILES, null)
        set(value) = encryptedPrefs.edit().putString(KEY_HOST_PROFILES, value).apply()

    var currentHostProfileId: String?
        get() = encryptedPrefs.getString(KEY_CURRENT_HOST_PROFILE_ID, null)
        set(value) = encryptedPrefs.edit().putString(KEY_CURRENT_HOST_PROFILE_ID, value).apply()

    /** Explicit Manual/Profile bootstrap source; null means legacy migration is pending. */
    var effectiveConnectionSourceMarker: String?
        get() = encryptedPrefs.getString(KEY_EFFECTIVE_CONNECTION_SOURCE, null)
        set(value) = encryptedPrefs.edit().apply {
            if (value == null) remove(KEY_EFFECTIVE_CONNECTION_SOURCE)
            else putString(KEY_EFFECTIVE_CONNECTION_SOURCE, value)
        }.apply()

    fun basicAuthPassword(passwordId: String): String? {
        if (passwordId == LEGACY_BASIC_AUTH_PASSWORD_ID) return password
        return encryptedPrefs.getString(basicAuthPasswordKey(passwordId), null)
    }

    fun setBasicAuthPassword(passwordId: String, value: String?) {
        encryptedPrefs.edit().apply {
            if (value.isNullOrBlank()) remove(basicAuthPasswordKey(passwordId)) else putString(basicAuthPasswordKey(passwordId), value)
        }.apply()
    }

    fun getTunnelPassword(id: String): String? {
        return encryptedPrefs.getString(tunnelPasswordKey(id), null)
    }

    fun setTunnelPassword(id: String, password: String?) {
        encryptedPrefs.edit().apply {
            if (password.isNullOrBlank()) remove(tunnelPasswordKey(id)) else putString(tunnelPasswordKey(id), password)
        }.apply()
    }

    fun clearTunnelPassword(id: String) {
        encryptedPrefs.edit().remove(tunnelPasswordKey(id)).apply()
    }

    // ── §2.3: mTLS 客户端证书（PKCS12 / 密码 / CA）存取 ─────────────────────
    //
    // 三个分项 key（`client_cert_p12_<id>` / `client_cert_pw_<id>` /
    // `client_cert_ca_<id>`）+ 一个原子批量写 [saveClientCert]。p12 / CA 走
    // ByteArray + java.util.Base64（与 OpenCodeRepository 一致；JVM 可单测）。
    // 密码用 `== null` 判清除（非 isNullOrBlank）——允许空串密码
    // （openssl `-password pass:` 合法）。CA 用 ByteArray（PEM / DER 都二进制
    // 安全；CertificateFactory 都吃）——readText() 对 DER 会坏。

    fun getClientCertP12(id: String): ByteArray? =
        encryptedPrefs.getString(clientCertP12Key(id), null)?.let { Base64.getDecoder().decode(it) }

    fun setClientCertP12(id: String, bytes: ByteArray?) {
        encryptedPrefs.edit().apply {
            if (bytes == null) remove(clientCertP12Key(id))
            else putString(clientCertP12Key(id), Base64.getEncoder().encodeToString(bytes))
        }.apply()
    }

    /** 用 `== null` 判清除，允许空字符串密码（openssl `-password pass:` 合法）。 */
    fun getClientCertPassword(id: String): String? =
        encryptedPrefs.getString(clientCertPasswordKey(id), null)

    fun setClientCertPassword(id: String, value: String?) {
        encryptedPrefs.edit().apply {
            if (value == null) remove(clientCertPasswordKey(id))
            else putString(clientCertPasswordKey(id), value)
        }.apply()
    }

    /** CA 用 ByteArray（PEM 或 DER 都二进制安全；CertificateFactory 都吃）。 */
    fun getClientCertCa(id: String): ByteArray? =
        encryptedPrefs.getString(clientCertCaKey(id), null)?.let { Base64.getDecoder().decode(it) }

    fun setClientCertCa(id: String, bytes: ByteArray?) {
        encryptedPrefs.edit().apply {
            if (bytes == null) remove(clientCertCaKey(id))
            else putString(clientCertCaKey(id), Base64.getEncoder().encodeToString(bytes))
        }.apply()
    }

    fun clearClientCert(id: String) {
        encryptedPrefs.edit()
            .remove(clientCertP12Key(id))
            .remove(clientCertPasswordKey(id))
            .remove(clientCertCaKey(id))
            .apply()
    }

    /**
     * §2.3 / v3-gpter R2#2: 单次原子提交 p12 + 密码 + CA（三个独立 apply() 非原子，
     * 崩溃 / 并发可半写 → 悬空引用）。null 表清除该项。
     */
    fun saveClientCert(id: String, p12: ByteArray?, password: String?, ca: ByteArray?) {
        encryptedPrefs.edit().apply {
            if (p12 == null) remove(clientCertP12Key(id))
            else putString(clientCertP12Key(id), Base64.getEncoder().encodeToString(p12))
            if (password == null) remove(clientCertPasswordKey(id))
            else putString(clientCertPasswordKey(id), password)
            if (ca == null) remove(clientCertCaKey(id))
            else putString(clientCertCaKey(id), Base64.getEncoder().encodeToString(ca))
        }.apply()
    }

    /**
     * §2.3: 载入 [clientCertId] 对应的完整 [ClientCertMaterial]。p12 或 密码
     * 缺失（null）→ 返回 null（空字符串密码 != null，能通过）。CA 缺失 →
     * [ClientCertMaterial.caBytes] = null（平台 CA 模式）。
     */
    fun loadClientCertMaterial(clientCertId: String): ClientCertMaterial? {
        val p12 = getClientCertP12(clientCertId) ?: return null
        val pw = getClientCertPassword(clientCertId) ?: return null
        return ClientCertMaterial(p12, pw.toCharArray(), getClientCertCa(clientCertId))
    }

    var currentSessionId: String?
        get() = encryptedPrefs.getString(KEY_SESSION_ID, null)
        set(value) = encryptedPrefs.edit().putString(KEY_SESSION_ID, value).apply()

    /**
     * Index of the last-opened top-level page in the phone HorizontalPager
     * (0=Chat, 1=Sessions, 2=Settings). Restored on cold start so the user
     * lands back on the screen they last used instead of always Chat.
     */
    var lastNavPage: Int
        get() = encryptedPrefs.getInt(KEY_LAST_NAV_PAGE, 0).coerceIn(0, 2)
        set(value) = encryptedPrefs.edit().putInt(KEY_LAST_NAV_PAGE, value.coerceIn(0, 2)).apply()

    /**
     * Stable top-level route persistence for AppShell (the sole shell; the
     * legacy PhoneLayout + USE_NEW_SHELL flag were removed in the redesign).
     *
     * Existing installations have only [KEY_LAST_NAV_PAGE]. The first read
     * migrates 0/1/2 to chat/sessions/settings and writes the route key.
     * The removed `workspace` route is explicitly migrated to Files.
     */
    var lastRoute: String
        get() {
            val stored = encryptedPrefs.getString(KEY_LAST_ROUTE, null)
            if (stored == "workspace") {
                encryptedPrefs.edit().putString(KEY_LAST_ROUTE, ROUTE_FILES).apply()
                return ROUTE_FILES
            }
            if (stored != null) {
                if (stored in TOP_LEVEL_ROUTE_KEYS) return stored
                encryptedPrefs.edit().putString(KEY_LAST_ROUTE, ROUTE_CHAT).apply()
                return ROUTE_CHAT
            }
            val migrated = when (encryptedPrefs.getInt(KEY_LAST_NAV_PAGE, 0)) {
                1 -> ROUTE_SESSIONS
                2 -> ROUTE_SETTINGS
                else -> ROUTE_CHAT
            }
            encryptedPrefs.edit().putString(KEY_LAST_ROUTE, migrated).apply()
            return migrated
        }
        set(value) {
            val route = value.takeIf { it in TOP_LEVEL_ROUTE_KEYS } ?: ROUTE_CHAT
            encryptedPrefs.edit().putString(KEY_LAST_ROUTE, route).apply()
        }

    /**
     * The workdir (project directory) the user last connected to. Restored on
     * cold start so repository queries are re-scoped to the same project and
     * its directory-scoped sessions are re-fetched — without this, restart
     * resets currentDirectory to null and the connected project "vanishes".
     */
    var currentWorkdir: String?
        get() = encryptedPrefs.getString(KEY_CURRENT_WORKDIR, null)
        set(value) {
            encryptedPrefs.edit().putString(KEY_CURRENT_WORKDIR, value).apply()
            // §reactive-workdir: keep the flow mirror in sync so the
            // Git → Changes pane (and any other collector) reacts to
            // workdir changes without manual refresh.
            _currentWorkdirFlow.value = value
        }

    /**
     * R-20 Phase 5: the set of workdirs the user has recently connected to
     * (MRU order), keyed per [serverGroupFp] so the right set survives a cold
     * start for the active host AND so two profiles reaching the same server
     * (same fp) share the workdir-discovery memory. Without this, a non-
     * current workdir whose sessions fall outside the global
     * `getSessions(limit=10)` first page vanishes from the Sessions screen
     * after restart (the "one of my frequent projects randomly disappeared"
     * bug). Capped at [MAX_RECENT_WORKDIRS].
     *
     * Plan §3 Phase 5 (v4): the legacy global `recent_workdirs` single key is
     * migrated to `recent_workdirs_<fp>` once per fp by
     * [migrateLegacyKeysToFp] (idempotent via the `cache_migration_v1_done_<fp>`
     * flag) — see [ConnectionActions.applySavedSettings].
     *
     * §files-git-readonly-workdir: the legacy `files_last_workdir` field (the
     * last workdir explicitly browsed in Files/Git) was removed when the
     * Files/Git WorkdirControl became a read-only indicator — workdir now
     * follows the active session's directory exclusively, so there is no
     * longer a "browsed-but-not-current" workdir to persist. The storage key
     * `files_last_workdir` is intentionally NOT reclaimed here (a leftover
     * ESP entry is harmless); it is reclaimed by [clearAllLocalData].
     */
    fun getRecentWorkdirs(serverGroupFp: String): List<String> {
        if (serverGroupFp.isBlank()) return emptyList()
        return synchronized(this) {
            // §R18 Phase 4 (P2-9) Gate-4 fix (maxer): lock the read too so it
            // cannot observe a value mid-flight from a concurrent
            // addRecentWorkdir write (the getter is otherwise a separate
            // critical section from the write-side RMW). SharedPreferences
            // getString is itself atomic, but without this lock a reader
            // (e.g. loadInitialData computing the fan-out workdir set) could
            // base its decision on a list that addRecentWorkdir is about to
            // change. Synchronized(this) pairs with addRecentWorkdir's lock.
            val json = encryptedPrefs.getString(recentWorkdirsKey(serverGroupFp), null)
                ?: return@synchronized emptyList()
            try {
                Json.decodeFromString<List<String>>(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse recent workdirs for fp=$serverGroupFp, using empty", e)
                emptyList()
            }
        }
    }

    fun setRecentWorkdirs(serverGroupFp: String, workdirs: List<String>) {
        if (serverGroupFp.isBlank()) return
        val json = Json.encodeToString(workdirs)
        encryptedPrefs.edit().putString(recentWorkdirsKey(serverGroupFp), json).apply()
    }

    /**
     * Prepends [workdir] to the [serverGroupFp]'s recent-workdirs list (MRU),
     * deduplicating and capping at [MAX_RECENT_WORKDIRS]. Called when the user
     * connects a project via `createSessionInWorkdir` so the workdir survives
     * restart even after it is later superseded as [currentWorkdir]. Blank/
     * whitespace-only entries are ignored.
     *
     * §R18 Phase 4 (P2-9): the read-filter-write across [getRecentWorkdirs] is
     * wrapped in `synchronized(this)` to make the sequence atomic. Without
     * this, two concurrent callers can both read the old list, both prepend,
     * and the second write clobbers the first — losing the first caller's
     * workdir (read-modify-write race). SharedPreferences itself is process-
     * thread-safe but the read→filter→write compound is not.
     *
     * §grouping-rewrite Round-6 F4: dedup is now by NORMALIZED EQUIVALENCE
     * via [WorkdirPaths.normalize] (symmetric with [removeRecentWorkdir]'s
     * normalized match per C4). Pre-F4 the dedup was exact-trimmed, so
     * adding "/proj-a" then "proj-a/" would store BOTH — they normalize
     * together but compared unequal under raw `==`. The asymmetry vs
     * removeRecentWorkdir (which normalized) meant the disconnect would
     * remove both via normalized match but a re-add could leave a stale
     * twin. Both sides now agree: add removes any existing entry whose
     * normalized form matches the new entry's, then prepends the new
     * entry's stored form.
     *
     * **Storage stays ORIGINAL-form** (the trimmed string the server
     * returned) — NOT the normalized key. The server needs the real path
     * for `getSessionsForDirectory`; normalizing on store would break the
     * cold-start fan-out. Only the comparison is normalized.
     */
    fun addRecentWorkdir(serverGroupFp: String, workdir: String) {
        if (serverGroupFp.isBlank()) return
        val storedForm = workdir.trim()
        if (storedForm.isEmpty()) return
        val normalizedKey = WorkdirPaths.normalize(storedForm)
        synchronized(this) {
            val updated = (listOf(storedForm) + getRecentWorkdirs(serverGroupFp).filter {
                WorkdirPaths.normalize(it) != normalizedKey
            }).take(MAX_RECENT_WORKDIRS)
            setRecentWorkdirs(serverGroupFp, updated)
        }
    }

    /**
     * Remove [workdir] from the [serverGroupFp]'s recent-workdirs list.
     *
     * §grouping-rewrite Round-4 C4: matching is by NORMALIZED EQUIVALENCE, not
     * exact trimmed string. The display dir passed in here comes from
     * `buildWorkdirGroups`'s output, which is often the absolute leading-slash
     * form taken from a live session (e.g. `/proj-a`), while recent_workdirs
     * may persist a slash variant the server originally returned (e.g.
     * `proj-a/`). Under exact-string matching the disconnect would silently
     * fail to match → the variant would persist → the normalized visible key
     * would still exist → the workdir would reappear after disconnect (the
     * disconnect dialog's "将从列表移除" promise broken for variant cases).
     *
     * Both the incoming [workdir] and each stored entry are funnelled through
     * [WorkdirPaths.normalize] — the SAME normalization
     * `buildWorkdirGroups`/`SessionsScreen` uses for visibility gating — so
     * the disconnect pipeline is closed end-to-end:
     *
     *   display dir → disconnectWorkdir → removeRecentWorkdir (normalized)
     *   → removed → recentWorkdirsTick bumped → buildWorkdirGroups re-derives
     *   → workdir no longer in visible set → hidden.
     *
     * Storage behaviour is unchanged: `getRecentWorkdirs` still returns the
     * ORIGINAL stored forms (server-facing paths); only the comparison is
     * normalized. `addRecentWorkdir` also still stores the trimmed original
     * (not the normalized form) — see its KDoc for why.
     */
    fun removeRecentWorkdir(serverGroupFp: String, workdir: String) {
        if (serverGroupFp.isBlank()) return
        val targetNormalized = WorkdirPaths.normalize(workdir)
        if (targetNormalized.isEmpty()) return
        synchronized(this) {
            val updated = getRecentWorkdirs(serverGroupFp).filter { stored ->
                WorkdirPaths.normalize(stored) != targetNormalized
            }
            setRecentWorkdirs(serverGroupFp, updated)
        }
    }

    /**
     * R-20 Phase 5: clears the [serverGroupFp]'s recent-workdirs list. Used
     * by [cn.vectory.ocdroid.ui.controller.HostProfileController.purgePerHostState]
     * on a DIFFERENT-group switch (the old fp's workdirs are meaningless on
     * the new server). Same-group switches preserve the list (the new
     * profile reaches the same server).
     */
    fun clearRecentWorkdirs(serverGroupFp: String) {
        if (serverGroupFp.isBlank()) return
        encryptedPrefs.edit().remove(recentWorkdirsKey(serverGroupFp)).apply()
    }

    // §chat-ux-batch T8 (B3): the global `selectedAgentName` property was
    // deleted here. T7 rewired agent selection to the TRANSIENT `pendingAgent`
    // chat-slice field (resolved `pending ?: infer ?: null` at send); the
    // legacy persistent property has no live reader. See the KEY_AGENT_NAME
    // tombstone in the companion object below.

    var themeMode: ThemeMode
        get() = ThemeMode.valueOf(encryptedPrefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        set(value) = encryptedPrefs.edit().putString(KEY_THEME, value.name).apply()

    /**
     * T5-C1/C2: when `false` (default), the persistent/ongoing FGS
     * session-status notification is built with `PRIORITY_MIN` +
     * `setSilent(true)` so it does not surface in the shade nor make
     * sound/vibrate, while still `setOngoing` so the FGS slot survives and
     * SSE keepalive continues unchanged. When `true`, the prior LOW +
     * non-silent surface is restored (the user explicitly opts in to a
     * visible ongoing notification).
     *
     * ESP-persisted Boolean mirroring the existing key/get/set pattern
     * (e.g. [themeMode]); default `false` so a fresh install keeps the
     * notification drawer quiet.
     */
    var persistentNotificationEnabled: Boolean
        get() = encryptedPrefs.getBoolean(KEY_PERSISTENT_NOTIFICATION_ENABLED, false)
        set(value) = encryptedPrefs.edit().putBoolean(KEY_PERSISTENT_NOTIFICATION_ENABLED, value).apply()

    /**
     * §streaming-state-sync-diag (release-enabling): runtime toggle for the
     * 5 verbose diagnostic tags (`SendDiag` / `SseDiag` / `StatusDiag` /
     * `DigestDiag` / `LayerDiag`). Default OFF — release users get zero log
     * noise / perf cost unless they flip the toggle in Settings → Debug.
     *
     * ESP-persisted Boolean mirroring [persistentNotificationEnabled]'s pattern.
     * On set, the caller (the Settings UI) ALSO writes
     * [DebugLog.verboseDiagEnabled] so the change takes effect immediately
     * without a restart; on app start [AppCore]'s init seeds
     * [DebugLog.verboseDiagEnabled] from this value.
     */
    var debugLogVerboseEnabled: Boolean
        get() = encryptedPrefs.getBoolean(KEY_DEBUG_LOG_VERBOSE, false)
        set(value) = encryptedPrefs.edit().putBoolean(KEY_DEBUG_LOG_VERBOSE, value).apply()

    /**
     * §P5a (Q5): user-facing language preference. SYSTEM = follow the real
     * system locale (zh→zh, en→en, anything else→zh via AppLocaleController);
     * ZH/EN = force that language. First-launch default = SYSTEM (null in ESP
     * → SYSTEM). Applied at process startup in OpenCodeApp.onCreate before
     * the first Activity frame, and re-applied on every setLocaleMode via
     * [cn.vectory.ocdroid.util.AppLocaleController.apply].
     *
     * NOT in the [clearAllLocalData] preserved-keys whitelist — a full data
     * reset returns the language to SYSTEM (the comment in that method notes
     * KEY_LOCALE is intentionally wiped alongside theme/font prefs).
     */
    var localeMode: LocaleMode
        get() = encryptedPrefs.getString(KEY_LOCALE, null)?.let {
            runCatching { LocaleMode.valueOf(it) }.getOrNull()
        } ?: LocaleMode.SYSTEM
        set(value) = encryptedPrefs.edit().putString(KEY_LOCALE, value.name).apply()

    /**
     * §ui-scale: user-adjustable UI scale factors (M3 canonical pattern —
     * applied via CompositionLocalProvider(LocalDensity provides Density(...))
     * in OpenCodeTheme). Two independent axes per the official Android
     * scalable-content guidance:
     *
     *  - [uiFontScale]: multiplies LocalDensity.fontScale ONLY → text resizes,
     *    layout/padding/icon sizes stay fixed. Good for "make text bigger
     *    without reflowing the layout".
     *  - [uiContentScale]: multiplies LocalDensity.density → everything (dp-
     *    based dimensions AND sp text) resizes together. True "zoom" — good
     *    for tablet users who want the whole UI larger.
     *
     * Both layer ON TOP OF the system accessibility font size (the base
     * LocalDensity.fontScale already carries the OS setting). Range clamped to
     * [UI_SCALE_MIN]–[UI_SCALE_MAX]; default 1.0 (no change).
     */
    var uiFontScale: Float
        get() = encryptedPrefs.getFloat(KEY_UI_FONT_SCALE, 1f).coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)
        set(value) = encryptedPrefs.edit().putFloat(KEY_UI_FONT_SCALE, value.coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)).apply()

    var uiContentScale: Float
        get() = encryptedPrefs.getFloat(KEY_UI_CONTENT_SCALE, 1f).coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)
        set(value) = encryptedPrefs.edit().putFloat(KEY_UI_CONTENT_SCALE, value.coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)).apply()

    /**
     * 字体脚手架（v2 §20 / D5；Phase 1 Expressive 字体路线 B 更新）。
     *
     * 4 键默认空字符串（= 使用 bundled 可变字体）。Phase 1 已 bundle Noto Sans VF
     * （常规）+ JetBrains Mono VF（等宽）到 res/font/。空 →
     * [cn.vectory.ocdroid.ui.theme.OpenCodeTheme] 内的 `resolveFontFamilyOrNull`
     * 回退到 `BundledSansFamily`（Noto Sans VF）；非空 → 经 `systemFontFamily()`
     * 作为 4 档 weight 系统字体族名加载（Android 对任意族名 weight 支持有限，
     * 自定义名可能回落 Normal，平台限制）。
     *
     * - [fontLatin] / [fontCJK]：作用于全应用 Typography（含顶栏、卡片等）。
     * - [markdownFontLatin] / [markdownFontCJK]：作用于聊天 markdown 渲染。
     */
    var fontLatin: String
        get() = encryptedPrefs.getString(KEY_FONT_LATIN, "") ?: ""
        set(value) = encryptedPrefs.edit().putString(KEY_FONT_LATIN, value).apply()

    var fontCJK: String
        get() = encryptedPrefs.getString(KEY_FONT_CJK, "") ?: ""
        set(value) = encryptedPrefs.edit().putString(KEY_FONT_CJK, value).apply()

    var markdownFontLatin: String
        get() = encryptedPrefs.getString(KEY_MARKDOWN_FONT_LATIN, "") ?: ""
        set(value) = encryptedPrefs.edit().putString(KEY_MARKDOWN_FONT_LATIN, value).apply()

    var markdownFontCJK: String
        get() = encryptedPrefs.getString(KEY_MARKDOWN_FONT_CJK, "") ?: ""
        set(value) = encryptedPrefs.edit().putString(KEY_MARKDOWN_FONT_CJK, value).apply()

    var markdownFontSizes: MarkdownFontSizes
        get() {
            val json = encryptedPrefs.getString(KEY_MARKDOWN_FONT_SIZES, null) ?: return MarkdownFontSizes()
            return try {
                Json.decodeFromString<MarkdownFontSizes>(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse markdown font sizes, using defaults", e)
                MarkdownFontSizes()
            }
        }
        set(value) {
            val json = Json.encodeToString(value)
            encryptedPrefs.edit().putString(KEY_MARKDOWN_FONT_SIZES, json).apply()
        }

    /**
     * "Open" (not closed) session IDs in open-order (most recently opened first).
     * Replaces the previous MRU [recentSessionIds] model with a browser-tab style
     * list: opening/switching a session prepends it; closing (x) removes it.
     * Capped at 8 entries.
     */
    var openSessionIds: List<String>
        get() {
            val json = encryptedPrefs.getString(KEY_OPEN_SESSION_IDS, null) ?: return emptyList()
            return try {
                Json.decodeFromString<List<String>>(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse open session IDs, using empty", e)
                emptyList()
            }
        }
        set(value) {
            val json = Json.encodeToString(value)
            encryptedPrefs.edit().putString(KEY_OPEN_SESSION_IDS, json).apply()
        }

    /**
     * Persisted projection of [cn.vectory.ocdroid.data.model.Session]
     * metadata, used to seed the session-list slice
     * ([cn.vectory.ocdroid.ui.SessionListState.sessions])
     * on cold start so tabs/title/workdir groups render instantly before the
     * server list is fetched. Written only from `launchLoadSessions`
     * onSuccess (bounded to open/current/workdir-relevant entries). A server
     * refresh later replaces these with authoritative data.
     */
    var sessionCache: List<SessionCacheEntry>
        get() {
            val json = encryptedPrefs.getString(KEY_SESSION_CACHE, null) ?: return emptyList()
            return try {
                Json.decodeFromString<List<SessionCacheEntry>>(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse session cache, using empty", e)
                emptyList()
            }
        }
        set(value) {
            val json = Json.encodeToString(value)
            encryptedPrefs.edit().putString(KEY_SESSION_CACHE, json).apply()
        }

    /**
     * R-20 Phase 5: per-(serverGroupFp, sessionId) draft text. The composite
     * map key is `"<fp>\u0000<sessionId>"` (NUL separator — fp is a UUID /
     * branded string that never contains NUL, so the split is unambiguous).
     *
     * Plan §3 Phase 5 (v4 freegpt #4): sessionId is a branded `ses_xxxx`
     * string, NOT a UUID — clone/reset servers can collide. A bare sessionId
     * key would let a draft typed on server A's `ses_xyz` leak into server B
     * when B happens to issue the same id. The composite key eliminates the
     * cross-server collision. Drafts contain unsent text (potentially
     * sensitive) so the isolation is privacy-critical.
     *
     * Legacy storage: a single global `session_drafts` JSON map keyed by
     * bare sessionId. [migrateLegacyKeysToFp] rewrites every legacy entry to
     * `"<currentFp>\u0000<sessionId>"` once per fp (idempotent).
     */
    fun getDraftText(serverGroupFp: String, sessionId: String): String {
        val json = encryptedPrefs.getString(KEY_SESSION_DRAFTS, null) ?: return ""
        return try {
            Json.decodeFromString<Map<String, String>>(json)[compositeSessionKey(serverGroupFp, sessionId)] ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun setDraftText(serverGroupFp: String, sessionId: String, text: String) {
        val json = encryptedPrefs.getString(KEY_SESSION_DRAFTS, null)
        val map: MutableMap<String, String> = try {
            json?.let { Json.decodeFromString<Map<String, String>>(it).toMutableMap() } ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
        val key = compositeSessionKey(serverGroupFp, sessionId)
        if (text.isBlank()) {
            map.remove(key)
        } else {
            map[key] = text
        }
        encryptedPrefs.edit().putString(KEY_SESSION_DRAFTS, Json.encodeToString(map)).apply()
    }

    /**
     * Cumulative bytes sent over HTTP, persisted across restarts. Seeded into
     * [TrafficTracker] on app start; updated by [TrafficTracker] on every
     * counted request. See [trafficBytesReceived] for the inbound counterpart.
     */
    var trafficBytesSent: Long
        get() = encryptedPrefs.getLong(KEY_TRAFFIC_SENT, 0L)
        set(value) = encryptedPrefs.edit().putLong(KEY_TRAFFIC_SENT, value).apply()

    var trafficBytesReceived: Long
        get() = encryptedPrefs.getLong(KEY_TRAFFIC_RECEIVED, 0L)
        set(value) = encryptedPrefs.edit().putLong(KEY_TRAFFIC_RECEIVED, value).apply()

    /** Epoch millis of the last traffic reset (0 = never reset). Mirrors
     *  [trafficBytesSent]/[trafficBytesReceived]; written by [TrafficTracker]. */
    var trafficResetAt: Long
        get() = encryptedPrefs.getLong(KEY_TRAFFIC_RESET_AT, 0L)
        set(value) = encryptedPrefs.edit().putLong(KEY_TRAFFIC_RESET_AT, value).apply()

    // §chat-ux-batch T8 (B3): getAgentForSession / setAgentForSession /
    // clearAgentForSession / getModelForSession / setModelForSession were
    // deleted here (the per-session agent/model override maps). T7 rewired
    // both picks to transient pendingAgent / pendingModel on the chat slice;
    // these persistent helpers have no live reader.

    /**
     * §model-selection / R-20 Phase 5: per-serverGroupFp disabled-model set.
     * Models the user has unchecked in Settings → Model management; those
     * entries are hidden from the chat quick-switch picker. Storage key
     * format: `disabled_models_<serverGroupFp>` (was `disabled_models_<normalizedBaseUrl>`
     * before Phase 5 — the URL dimension could not distinguish two profiles
     * reaching the same URL but treated as separate caches, and leaked
     * across identities sharing a URL). Stored as a StringSet whose entries
     * are `"$providerId/$modelId"`.
     *
     * Plan §3 Phase 5: legacy `disabled_models_<normalizedBaseUrl>` is migrated
     * to `disabled_models_<fp>` once per fp by [migrateLegacyKeysToFp]
     * (idempotent).
     */
    fun getDisabledModels(serverGroupFp: String): Set<String> {
        return encryptedPrefs.getStringSet(disabledModelsKey(serverGroupFp), emptySet()) ?: emptySet()
    }

    /**
     * §model-selection: toggle a single model's disabled flag for
     * [serverGroupFp]. [providerId]/[modelId] form the entry key
     * `"$providerId/$modelId"`.
     */
    fun setModelDisabled(serverGroupFp: String, providerId: String, modelId: String, disabled: Boolean) {
        val key = disabledModelsKey(serverGroupFp)
        val current = (encryptedPrefs.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
        val entry = "$providerId/$modelId"
        if (disabled) current.add(entry) else current.remove(entry)
        encryptedPrefs.edit().putStringSet(key, current).apply()
    }

    /**
     * §bug5: bulk replace the disabled set for a serverGroupFp (used by manual
     * refresh inherit so we don't issue N incremental writes). Entries are
     * `"$providerId/$modelId"`.
     */
    fun setDisabledModels(serverGroupFp: String, disabledKeys: Set<String>) {
        encryptedPrefs.edit().putStringSet(disabledModelsKey(serverGroupFp), disabledKeys).apply()
    }

    // §bug5: per-serverGroupFp model availability catalog (server-fetched full
    // set) so that manual refresh can inherit disable status only for models
    // still present.
    fun getModelAvailability(serverGroupFp: String): Set<String> {
        return encryptedPrefs.getStringSet(modelAvailabilityKey(serverGroupFp), emptySet()) ?: emptySet()
    }

    fun setModelAvailability(serverGroupFp: String, availableKeys: Set<String>) {
        encryptedPrefs.edit().putStringSet(modelAvailabilityKey(serverGroupFp), availableKeys).apply()
    }

    /**
     * R-20 Phase 5: clear ALL per-serverGroupFp model data (availability +
     * disabled) — used on异组 host switch / server-profile deletion so stale
     * data does not leak across identities. Replaces the legacy
     * `clearModelDataForUrl(baseUrl)` (URL was the wrong dimension: two
     * profiles with same URL but different group would clobber each other).
     */
    fun clearModelDataForGroup(serverGroupFp: String) {
        encryptedPrefs.edit()
            .remove(modelAvailabilityKey(serverGroupFp))
            .remove(disabledModelsKey(serverGroupFp))
            .apply()
    }

    // ───────────── R-20 Phase 5: legacy → fp-keyed migration (once per fp) ─────

    /**
     * R-20 Phase 5: one-shot migration of the three legacy global / baseUrl-
     * keyed / sessionId-keyed categories to per-serverGroupFp storage.
     *
     * Plan §3 Phase 5 (dser/maxer): [cn.vectory.ocdroid.ui.ConnectionActions.applySavedSettings]
     * is the cold-start trigger — it runs early (AppCore.init) and is
     * idempotent per fp via the `cache_migration_v1_done_<fp>` flag. Once an
     * fp has been migrated, subsequent cold starts skip the rewrite.
     *
     * Categories migrated:
     *  1. `recent_workdirs` (global single key) → `recent_workdirs_<fp>`.
     *  2. `disabled_models_<normalizedBaseUrl>` + `model_availability_<normalizedBaseUrl>`
     *     (where baseUrl normalizes to the current profile's URL) →
     *     `disabled_models_<fp>` + `model_availability_<fp>`.
     *  3. `session_drafts` JSON map (bare-sessionId keys) → composite keys
     *     `"<fp>\u0000<sessionId>"` inside the same JSON map.
     *
     * §chat-ux-batch T8 (B3): the legacy `session_agents` / `session_models`
     * JSON maps were deleted alongside their getters/setters; the migration
     * rewrites for those two categories were dropped here (no live reader).
     *
     * The migration is non-destructive: legacy keys are NOT removed (they'd
     * be reclaimed by [clearAllLocalData] eventually). This keeps the
     * migration reversible in case of a rollback — the new code reads only
     * the fp-keyed slot; old code reading the legacy slot sees its original
     * value. Idempotency comes from the per-fp flag.
     *
     * @param serverGroupFp the current host's fp (never blank — caller
     *   normalizes via `serverGroupFp.ifBlank { id }`).
     * @param legacyBaseUrl the current host's normalized baseUrl (used to
     *   locate the legacy `disabled_models_*` / `model_availability_*` slot
     *   for THIS server only — other URLs' data is left in place as orphan).
     */
    fun migrateLegacyKeysToFp(serverGroupFp: String, legacyBaseUrl: String) {
        if (serverGroupFp.isBlank()) return
        val flagKey = migrationFlagKey(serverGroupFp)
        if (encryptedPrefs.getBoolean(flagKey, false)) return

        val e = encryptedPrefs.edit()

        // ── 1) recent_workdirs (global) → recent_workdirs_<fp> ───────────────
        // Only copy if the fp slot is empty (defensive: never overwrite a
        // value that a prior partial migration wrote).
        val legacyWorkdirsJson = encryptedPrefs.getString(KEY_RECENT_WORKDIRS, null)
        if (legacyWorkdirsJson != null &&
            !encryptedPrefs.contains(recentWorkdirsKey(serverGroupFp))
        ) {
            e.putString(recentWorkdirsKey(serverGroupFp), legacyWorkdirsJson)
        }

        // ── 2) disabled_models / model_availability (per-baseUrl) → per-fp ──
        // Locate the legacy slots for THIS server's baseUrl. Other URLs' data
        // stays orphaned (multi-host users would need to migrate each host's
        // data on first cold-start of that host).
        if (!legacyBaseUrl.isBlank()) {
            val legacyDisabledKey = disabledModelsLegacyKey(legacyBaseUrl)
            val legacyAvailabilityKey = modelAvailabilityLegacyKey(legacyBaseUrl)
            if (!encryptedPrefs.contains(disabledModelsKey(serverGroupFp))) {
                encryptedPrefs.getStringSet(legacyDisabledKey, null)?.let {
                    e.putStringSet(disabledModelsKey(serverGroupFp), it)
                }
            }
            if (!encryptedPrefs.contains(modelAvailabilityKey(serverGroupFp))) {
                encryptedPrefs.getStringSet(legacyAvailabilityKey, null)?.let {
                    e.putStringSet(modelAvailabilityKey(serverGroupFp), it)
                }
            }
        }

        // ── 3) session_drafts (bare sessionId) → composite ───────────────
        // Rewrite the JSON map in place: each entry's key is prefixed with
        // `<fp>\u0000`. Entries that already carry the composite prefix (a
        // prior partial migration wrote some entries before the flag landed)
        // are left alone.
        //
        // §chat-ux-batch T8 (B3): the session_agents / session_models rewrites
        // were removed (the maps + their getters/setters were deleted; no live
        // reader remains). session_drafts keeps its migration (drafts still
        // active).
        rewriteSessionMapLegacyToFp(KEY_SESSION_DRAFTS, serverGroupFp, e)

        e.putBoolean(flagKey, true)
        e.apply()
    }

    /**
     * Helper: rewrites a JSON Map<String, String> ESP entry from bare-sessionId
     * keys to composite `"<fp>\u0000<sessionId>"` keys, in place. Entries
     * already carrying the NUL prefix are preserved as-is (idempotent across
     * partial migrations). The edit is staged into [editor] so the whole
     * migration is a single batched apply().
     */
    private fun rewriteSessionMapLegacyToFp(
        key: String,
        serverGroupFp: String,
        editor: SharedPreferences.Editor
    ) {
        val json = encryptedPrefs.getString(key, null) ?: return
        val map: MutableMap<String, String> = try {
            Json.decodeFromString<Map<String, String>>(json).toMutableMap()
        } catch (e: Exception) {
            return
        }
        val prefix = serverGroupFp + COMPOSITE_KEY_SEPARATOR
        var changed = false
        val updated = map.mapKeys { (k, _) ->
            if (k.contains(COMPOSITE_KEY_SEPARATOR)) {
                // Already composite (prior partial migration) — leave alone.
                k
            } else {
                changed = true
                prefix + k
            }
        }
        if (changed) {
            editor.putString(key, Json.encodeToString(updated))
        }
    }

    // §grouping-rewrite Round-2 #5: `copyCompositeSessionMapPrefix(...)` was
    // here — sole caller was `copyPerFpConfig(...)`, which item 1 of this
    // rewrite deleted. Removed as dead code.

    /**
     * Hard reset: wipes EVERY persisted key EXCEPT the connection-credential
     * keys and the per-host password secrets (basic-auth + tunnel), then leaves
     * the encrypted prefs otherwise intact for those preserved entries.
     *
     * PRESERVED (the "server connection info + tunnel passwords" invariant):
     *  - [KEY_SERVER_URL], [KEY_USERNAME], [KEY_PASSWORD] (legacy direct form)
     *  - [KEY_HOST_PROFILES], [KEY_CURRENT_HOST_PROFILE_ID] (the host list +
     *    the active profile id)
     *  - per-host basic-auth passwords (`basic_auth_password_*`) — these back
     *    [HostProfile.basicAuth.passwordId]; wiping them would silently break
     *    every saved host's authentication on reconnect.
     *  - per-host tunnel passwords (`tunnel_password_*`)
     *  - §2.3: per-host mTLS client certificates (`client_cert_p12_*` /
     *    `client_cert_pw_*` / `client_cert_ca_*`) — same semantics as the
     *    connection credentials: wiping them while `KEY_HOST_PROFILES` keeps
     *    `mtlsEnabled=true/clientCertId=<id>` would leave a dangling reference
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
     * §P5a (Q5): [KEY_LOCALE] (language preference) is INTENTIONALLY NOT in
     * [preservedKeys] — a full data reset returns the language to SYSTEM
     * (first-launch default), alongside the theme/font prefs. This keeps
     * "Clear all local data" a true return-to-defaults for appearance.
     *
     * Implementation iterates the live key set and `.remove()`s each non-
     * preserved key in a single batched edit. This deliberately avoids
     * `.clear()` (which would also nuke the connection keys) and never touches
     * the `basic_auth_password_*` / `tunnel_password_*` / `client_cert_*`
     * prefixes.
     */
    fun clearAllLocalData() {
        val connectionKeys = setOf(
            KEY_SERVER_URL,
            KEY_USERNAME,
            KEY_PASSWORD,
            KEY_HOST_PROFILES,
            KEY_CURRENT_HOST_PROFILE_ID,
            KEY_EFFECTIVE_CONNECTION_SOURCE,
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
        _currentWorkdirFlow.value = null
    }

    companion object {
        private const val TAG = "SettingsManager"
        const val DEFAULT_SERVER = "http://localhost:4096"
        const val LEGACY_BASIC_AUTH_PASSWORD_ID = "legacy_basic_auth_password"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_HOST_PROFILES = "host_profiles_json"
        private const val KEY_CURRENT_HOST_PROFILE_ID = "current_host_profile_id"
        private const val KEY_EFFECTIVE_CONNECTION_SOURCE = "effective_connection_source"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_LAST_NAV_PAGE = "last_nav_page"
        private const val KEY_LAST_ROUTE = "last_route"
        private const val ROUTE_CHAT = "chat"
        private const val ROUTE_SESSIONS = "sessions"
        private const val ROUTE_FILES = "files"
        private const val ROUTE_GIT = "git"
        private const val ROUTE_SETTINGS = "settings"
        private val TOP_LEVEL_ROUTE_KEYS = setOf(
            ROUTE_CHAT,
            ROUTE_SESSIONS,
            ROUTE_FILES,
            ROUTE_GIT,
            ROUTE_SETTINGS,
        )
        private const val KEY_CURRENT_WORKDIR = "current_workdir"
        private const val KEY_RECENT_WORKDIRS = "recent_workdirs"
        /**
         * §files-git-readonly-workdir: REMOVED. The `files_last_workdir` field
         * (last workdir explicitly browsed in Files/Git) was dead code after
         * the Files/Git WorkdirControl became a read-only indicator. The
         * constant is intentionally kept commented-out as a tombstone so a
         * future "let me re-add this" pass sees the rationale instead of
         * re-deriving the same dead concept.
         */
        // private const val KEY_FILES_LAST_WORKDIR = "files_last_workdir"
        /**
         * Cap for [recentWorkdirs] — bounds cold-start directory-fetch fan-out.
         *
         * §grouping-rewrite 项 5 (spec decision): 30 — the recent-workdir list
         * doubles as the persistent "Connected projects" surface (0-live wds
         * are retained as placeholders so the user can re-enter / disconnect
         * them), so the cap is sized for navigation memory rather than fetch
         * fan-out alone.
         */
        private const val MAX_RECENT_WORKDIRS = 30
        // §chat-ux-batch T8 (B3) tombstone: KEY_AGENT_NAME (global
        // selectedAgentName) + KEY_SESSION_AGENTS / KEY_SESSION_MODELS (the
        // per-session agent/model JSON maps) were deleted here. Their
        // getters/setters were removed too — T7 rewired agent/model selection
        // to the TRANSIENT pendingAgent / pendingModel chat-slice fields
        // (resolved `pending ?: infer ?: null` at send). The constants are
        // intentionally NOT declared so a future "let me re-add this" pass
        // must consciously re-derive them; any orphan ESP values from a prior
        // install are reclaimed by clearAllLocalData on the next reset.
        // private const val KEY_AGENT_NAME = "agent_name"
        // private const val KEY_SESSION_AGENTS = "session_agents"
        // private const val KEY_SESSION_MODELS = "session_models"
        private const val KEY_THEME = "theme"
        /** T5-C1: ESP key for [persistentNotificationEnabled]. Default false. */
        private const val KEY_PERSISTENT_NOTIFICATION_ENABLED = "persistent_notification_enabled"
        /** §streaming-state-sync-diag: ESP key for [debugLogVerboseEnabled]. Default false. */
        private const val KEY_DEBUG_LOG_VERBOSE = "debug_log_verbose"
        /** §P5a (Q5): persisted [LocaleMode] (language preference). Default null → SYSTEM. */
        private const val KEY_LOCALE = "locale"
        private const val KEY_UI_FONT_SCALE = "ui_font_scale"
        private const val KEY_UI_CONTENT_SCALE = "ui_content_scale"
        /** §ui-scale: clamp range for both font + content scale sliders. */
        const val UI_SCALE_MIN = 0.85f
        const val UI_SCALE_MAX = 1.3f
        private const val KEY_SESSION_DRAFTS = "session_drafts"
        private const val KEY_MARKDOWN_FONT_SIZES = "markdown_font_sizes_json"
        private const val KEY_FONT_LATIN = "font_latin"
        private const val KEY_FONT_CJK = "font_cjk"
        private const val KEY_MARKDOWN_FONT_LATIN = "markdown_font_latin"
        private const val KEY_MARKDOWN_FONT_CJK = "markdown_font_cjk"
        private const val KEY_OPEN_SESSION_IDS = "open_session_ids"
        private const val KEY_SESSION_CACHE = "session_cache"
        private const val KEY_TRAFFIC_SENT = "traffic_sent"
        private const val KEY_TRAFFIC_RECEIVED = "traffic_received"
        private const val KEY_TRAFFIC_RESET_AT = "traffic_reset_at"

        private fun basicAuthPasswordKey(passwordId: String): String = "basic_auth_password_$passwordId"
        private fun tunnelPasswordKey(id: String): String = "tunnel_password_$id"

        // §2.3: mTLS 客户端证书 key 后缀（与 basic_auth_password_ / tunnel_password_ 同构）。
        private fun clientCertP12Key(id: String): String = "client_cert_p12_$id"
        private fun clientCertPasswordKey(id: String): String = "client_cert_pw_$id"
        private fun clientCertCaKey(id: String): String = "client_cert_ca_$id"

        /**
         * R-20 Phase 5: separator used in the composite `(serverGroupFp,
         * sessionId)` map key. NUL (\u0000) is chosen because serverGroupFp
         * is a UUID / branded string (Phase 0 guarantees nonblank + the
         * HostProfile decode normalize step never produces one containing
         * NUL), so `"$fp\u0000$sessionId"` is an unambiguous reversible
         * encoding — no fp value can collide with a sessionId prefix.
         *
         * Public so tests + [rewriteSessionMapLegacyToFp] share the constant.
         */
        const val COMPOSITE_KEY_SEPARATOR = '\u0000'

        /**
         * R-20 Phase 5: builds the composite map key for per-(fp, sessionId)
         * storage (drafts / agents / models). See [COMPOSITE_KEY_SEPARATOR].
         */
        private fun compositeSessionKey(serverGroupFp: String, sessionId: String): String =
            "$serverGroupFp$COMPOSITE_KEY_SEPARATOR$sessionId"

        /** R-20 Phase 5: per-fp recent-workdirs key. */
        private fun recentWorkdirsKey(serverGroupFp: String): String =
            "recent_workdirs_$serverGroupFp"

        /** R-20 Phase 5: per-fp disabled-models key (replaces the legacy
         *  baseUrl-keyed slot). */
        private fun disabledModelsKey(serverGroupFp: String): String =
            "disabled_models_$serverGroupFp"

        /** R-20 Phase 5: per-fp model-availability key. */
        private fun modelAvailabilityKey(serverGroupFp: String): String =
            "model_availability_$serverGroupFp"

        /** R-20 Phase 5: per-fp migration flag (idempotency). */
        private fun migrationFlagKey(serverGroupFp: String): String =
            "cache_migration_v1_done_$serverGroupFp"

        // ── Legacy (pre-Phase-5) key helpers — kept ONLY for
        // [migrateLegacyKeysToFp] to read the old slots. New code MUST use
        // the per-fp versions above. ────────────────────────────────────────

        /**
         * §bug5 / pre-Phase-5: shared URL normalizer for the legacy per-URL
         * model keys. Strips scheme + trailing slash, lowercases the host
         * (collision defense — `http://Host:4096` vs `http://host:4096`),
         * and keeps any path so the identity matches the URL the user
         * actually configured.
         */
        private fun normalizeBaseUrl(baseUrl: String): String {
            val withoutScheme = baseUrl.substringAfter("://").trimEnd('/')
            val host = withoutScheme.substringBefore('/').lowercase()
            val path = withoutScheme.substringAfter('/', "")
            return if (path.isEmpty()) host else "$host/$path"
        }

        /** Pre-Phase-5 legacy key — see [migrateLegacyKeysToFp]. */
        private fun modelAvailabilityLegacyKey(baseUrl: String): String {
            return "model_availability_${normalizeBaseUrl(baseUrl)}"
        }

        /** Pre-Phase-5 legacy key — see [migrateLegacyKeysToFp]. */
        private fun disabledModelsLegacyKey(baseUrl: String): String {
            return "disabled_models_${normalizeBaseUrl(baseUrl)}"
        }
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
