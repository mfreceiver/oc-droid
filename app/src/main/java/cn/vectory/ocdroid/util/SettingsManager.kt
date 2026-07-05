package cn.vectory.ocdroid.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.SessionCacheEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
     * The workdir (project directory) the user last connected to. Restored on
     * cold start so repository queries are re-scoped to the same project and
     * its directory-scoped sessions are re-fetched — without this, restart
     * resets currentDirectory to null and the connected project "vanishes".
     */
    var currentWorkdir: String?
        get() = encryptedPrefs.getString(KEY_CURRENT_WORKDIR, null)
        set(value) = encryptedPrefs.edit().putString(KEY_CURRENT_WORKDIR, value).apply()

    /**
     * The set of workdirs the user has recently connected to (MRU order), so
     * cold-start [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator.loadInitialData]
     * can re-fetch directory-scoped sessions for EVERY connected project —
     * not just the single [currentWorkdir]. Without this, a non-current
     * workdir whose sessions fall outside the global `getSessions(limit=10)`
     * first page vanishes from the Sessions screen after restart (the "one of
     * my frequent projects randomly disappeared" bug). Capped at
     * [MAX_RECENT_WORKDIRS].
     */
    var recentWorkdirs: List<String>
        get() {
            val json = encryptedPrefs.getString(KEY_RECENT_WORKDIRS, null) ?: return emptyList()
            return try {
                Json.decodeFromString<List<String>>(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse recent workdirs, using empty", e)
                emptyList()
            }
        }
        set(value) {
            val json = Json.encodeToString(value)
            encryptedPrefs.edit().putString(KEY_RECENT_WORKDIRS, json).apply()
        }

    /**
     * Prepends [workdir] to [recentWorkdirs] (MRU), deduplicating and capping
     * at [MAX_RECENT_WORKDIRS]. Called when the user connects a project via
     * `createSessionInWorkdir` so the workdir survives restart even after it
     * is later superseded as [currentWorkdir]. Blank/whitespace-only entries
     * are ignored.
     */
    fun addRecentWorkdir(workdir: String) {
        val normalized = workdir.trim()
        if (normalized.isEmpty()) return
        val updated = (listOf(normalized) + recentWorkdirs.filter { it != normalized })
            .take(MAX_RECENT_WORKDIRS)
        recentWorkdirs = updated
    }

    var selectedAgentName: String?
        get() = encryptedPrefs.getString(KEY_AGENT_NAME, null)
        set(value) = encryptedPrefs.edit().putString(KEY_AGENT_NAME, value).apply()

    var themeMode: ThemeMode
        get() = ThemeMode.valueOf(encryptedPrefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        set(value) = encryptedPrefs.edit().putString(KEY_THEME, value.name).apply()

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

    fun getDraftText(sessionId: String): String {
        val json = encryptedPrefs.getString(KEY_SESSION_DRAFTS, null) ?: return ""
        return try {
            Json.decodeFromString<Map<String, String>>(json)[sessionId] ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun setDraftText(sessionId: String, text: String) {
        val json = encryptedPrefs.getString(KEY_SESSION_DRAFTS, null)
        val map: MutableMap<String, String> = try {
            json?.let { Json.decodeFromString<Map<String, String>>(it).toMutableMap() } ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
        if (text.isBlank()) {
            map.remove(sessionId)
        } else {
            map[sessionId] = text
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

    fun getAgentForSession(sessionId: String): String? {
        val json = encryptedPrefs.getString(KEY_SESSION_AGENTS, null) ?: return null
        return try {
            Json.decodeFromString<Map<String, String>>(json)[sessionId]
        } catch (e: Exception) {
            null
        }
    }

    fun setAgentForSession(sessionId: String, agentName: String) {
        val json = encryptedPrefs.getString(KEY_SESSION_AGENTS, null)
        val map: MutableMap<String, String> = try {
            json?.let { Json.decodeFromString<Map<String, String>>(it).toMutableMap() } ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
        map[sessionId] = agentName
        encryptedPrefs.edit().putString(KEY_SESSION_AGENTS, Json.encodeToString(map)).apply()
    }

    /**
     * §model-selection: per-session intended-next-model override, persisted across
     * cold starts. Stored as a JSON map `sessionId -> "providerId/modelId"` mirroring
     * the per-session agent map. Returns null when no model has been chosen for the
     * session (caller falls back to inferring from the latest assistant message).
     *
     * Model: V1-per-prompt semantics — the stored value is the model that will be
     * attached to the NEXT outgoing prompt's [PromptRequest.model]; it is NOT a
     * server-side session binding.
     */
    fun getModelForSession(sessionId: String): Message.ModelInfo? {
        val json = encryptedPrefs.getString(KEY_SESSION_MODELS, null) ?: return null
        return try {
            val raw = Json.decodeFromString<Map<String, String>>(json)[sessionId] ?: return null
            val parts = raw.split("/", limit = 2)
            if (parts.size != 2) null else Message.ModelInfo(providerId = parts[0], modelId = parts[1])
        } catch (e: Exception) {
            null
        }
    }

    fun setModelForSession(sessionId: String, providerId: String, modelId: String) {
        val json = encryptedPrefs.getString(KEY_SESSION_MODELS, null)
        val map: MutableMap<String, String> = try {
            json?.let { Json.decodeFromString<Map<String, String>>(it).toMutableMap() } ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
        map[sessionId] = "$providerId/$modelId"
        encryptedPrefs.edit().putString(KEY_SESSION_MODELS, Json.encodeToString(map)).apply()
    }

    /**
     * §model-selection: per-baseUrl disabled-model set. Models the user has
     * unchecked in Settings → Model management; those entries are hidden from
     * the chat quick-switch picker. Storage key format:
     * `disabled_models_<normalizedBaseUrl>` where normalize strips scheme +
     * trailing slash (e.g. `http://localhost:4096/` → `localhost:4096`).
     * Stored as a StringSet whose entries are `"$providerId/$modelId"`.
     */
    fun getDisabledModels(baseUrl: String): Set<String> {
        return encryptedPrefs.getStringSet(disabledModelsKey(baseUrl), emptySet()) ?: emptySet()
    }

    /**
     * §model-selection: toggle a single model's disabled flag for [baseUrl].
     * [providerId]/[modelId] form the entry key `"$providerId/$modelId"`.
     */
    fun setModelDisabled(baseUrl: String, providerId: String, modelId: String, disabled: Boolean) {
        val key = disabledModelsKey(baseUrl)
        val current = (encryptedPrefs.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
        val entry = "$providerId/$modelId"
        if (disabled) current.add(entry) else current.remove(entry)
        encryptedPrefs.edit().putStringSet(key, current).apply()
    }

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
     *
     * WIPED: open tabs, session metadata cache, drafts, per-session agents,
     * per-session models, current session/workdir, nav page, theme + font
     * preferences, traffic counters — everything not listed above.
     *
     * Implementation iterates the live key set and `.remove()`s each non-
     * preserved key in a single batched edit. This deliberately avoids
     * `.clear()` (which would also nuke the connection keys) and never touches
     * the `basic_auth_password_*` / `tunnel_password_*` prefixes.
     */
    fun clearAllLocalData() {
        val connectionKeys = setOf(
            KEY_SERVER_URL,
            KEY_USERNAME,
            KEY_PASSWORD,
            KEY_HOST_PROFILES,
            KEY_CURRENT_HOST_PROFILE_ID
        )
        val preservedKeys = connectionKeys
        val e = encryptedPrefs.edit()
        for (k in encryptedPrefs.all.keys) {
            val preserved = k in preservedKeys ||
                k.startsWith("basic_auth_password_") ||
                k.startsWith("tunnel_password_")
            if (!preserved) e.remove(k)
        }
        e.apply()
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
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_LAST_NAV_PAGE = "last_nav_page"
        private const val KEY_CURRENT_WORKDIR = "current_workdir"
        private const val KEY_RECENT_WORKDIRS = "recent_workdirs"
        /** Cap for [recentWorkdirs] — bounds cold-start directory-fetch fan-out. */
        private const val MAX_RECENT_WORKDIRS = 8
        private const val KEY_AGENT_NAME = "agent_name"
        private const val KEY_THEME = "theme"
        private const val KEY_UI_FONT_SCALE = "ui_font_scale"
        private const val KEY_UI_CONTENT_SCALE = "ui_content_scale"
        /** §ui-scale: clamp range for both font + content scale sliders. */
        const val UI_SCALE_MIN = 0.85f
        const val UI_SCALE_MAX = 1.3f
        private const val KEY_SESSION_DRAFTS = "session_drafts"
        private const val KEY_SESSION_AGENTS = "session_agents"
        private const val KEY_SESSION_MODELS = "session_models"
        private const val KEY_MARKDOWN_FONT_SIZES = "markdown_font_sizes_json"
        private const val KEY_FONT_LATIN = "font_latin"
        private const val KEY_FONT_CJK = "font_cjk"
        private const val KEY_MARKDOWN_FONT_LATIN = "markdown_font_latin"
        private const val KEY_MARKDOWN_FONT_CJK = "markdown_font_cjk"
        private const val KEY_OPEN_SESSION_IDS = "open_session_ids"
        private const val KEY_SESSION_CACHE = "session_cache"
        private const val KEY_TRAFFIC_SENT = "traffic_sent"
        private const val KEY_TRAFFIC_RECEIVED = "traffic_received"

        private fun basicAuthPasswordKey(passwordId: String): String = "basic_auth_password_$passwordId"
        private fun tunnelPasswordKey(id: String): String = "tunnel_password_$id"

        /**
         * §model-selection: storage key for the per-baseUrl disabled-model
         * set. Strips scheme + trailing slash so `http://h:1/` and `https://h:1`
         * share storage (same server, alternate transport).
         */
        private fun disabledModelsKey(baseUrl: String): String {
            val normalized = baseUrl
                .substringAfter("://")
                .trimEnd('/')
            return "disabled_models_$normalized"
        }
    }
}

enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}
