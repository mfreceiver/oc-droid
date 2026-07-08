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

    /**
     * R-20 Phase 5: per-(serverGroupFp, sessionId) agent override. Composite
     * map key `"<fp>\u0000<sessionId>"` — see [getDraftText] for the
     * collision-defense rationale.
     */
    fun getAgentForSession(serverGroupFp: String, sessionId: String): String? {
        val json = encryptedPrefs.getString(KEY_SESSION_AGENTS, null) ?: return null
        return try {
            Json.decodeFromString<Map<String, String>>(json)[compositeSessionKey(serverGroupFp, sessionId)]
        } catch (e: Exception) {
            null
        }
    }

    fun setAgentForSession(serverGroupFp: String, sessionId: String, agentName: String) {
        val json = encryptedPrefs.getString(KEY_SESSION_AGENTS, null)
        val map: MutableMap<String, String> = try {
            json?.let { Json.decodeFromString<Map<String, String>>(it).toMutableMap() } ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
        map[compositeSessionKey(serverGroupFp, sessionId)] = agentName
        encryptedPrefs.edit().putString(KEY_SESSION_AGENTS, Json.encodeToString(map)).apply()
    }

    /**
     * §agent-default: 清除某会话的 per-session agent 覆盖，使其回退到服务端默认 agent。
     * 用于 agent 选择器的"默认"项。
     */
    fun clearAgentForSession(serverGroupFp: String, sessionId: String) {
        val json = encryptedPrefs.getString(KEY_SESSION_AGENTS, null) ?: return
        val map: MutableMap<String, String> = try {
            Json.decodeFromString<Map<String, String>>(json).toMutableMap()
        } catch (e: Exception) {
            return
        }
        map.remove(compositeSessionKey(serverGroupFp, sessionId))
        encryptedPrefs.edit().putString(KEY_SESSION_AGENTS, Json.encodeToString(map)).apply()
    }

    /**
     * §model-selection: per-(serverGroupFp, sessionId) intended-next-model
     * override, persisted across cold starts. Stored as a JSON map keyed by
     * the composite `"<fp>\u0000<sessionId>"` (R-20 Phase 5) mirroring the
     * per-session agent map. Returns null when no model has been chosen for
     * the (fp, session) pair (caller falls back to inferring from the latest
     * assistant message).
     *
     * Model: V1-per-prompt semantics — the stored value is the model that will be
     * attached to the NEXT outgoing prompt's [PromptRequest.model]; it is NOT a
     * server-side session binding.
     */
    fun getModelForSession(serverGroupFp: String, sessionId: String): Message.ModelInfo? {
        val json = encryptedPrefs.getString(KEY_SESSION_MODELS, null) ?: return null
        return try {
            val raw = Json.decodeFromString<Map<String, String>>(json)[compositeSessionKey(serverGroupFp, sessionId)] ?: return null
            val parts = raw.split("/", limit = 2)
            if (parts.size != 2) null else Message.ModelInfo(providerId = parts[0], modelId = parts[1])
        } catch (e: Exception) {
            null
        }
    }

    fun setModelForSession(serverGroupFp: String, sessionId: String, providerId: String, modelId: String) {
        val json = encryptedPrefs.getString(KEY_SESSION_MODELS, null)
        val map: MutableMap<String, String> = try {
            json?.let { Json.decodeFromString<Map<String, String>>(it).toMutableMap() } ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
        map[compositeSessionKey(serverGroupFp, sessionId)] = "$providerId/$modelId"
        encryptedPrefs.edit().putString(KEY_SESSION_MODELS, Json.encodeToString(map)).apply()
    }

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

    // ───────────── R-20 Phase 3: per-serverGroup daily-sweep dedup ─────────

    /**
     * R-20 Phase 3 (plan §3): the epoch-day (UTC) on which the daily sweep
     * last ran for [serverGroupFp]. Used by
     * [cn.vectory.ocdroid.data.cache.CacheMaintenanceCoordinator.dailySweepIfNeeded]
     * to skip a sweep that already ran today (idempotent within a calendar
     * day — a reconnect within 24h must not re-enumerate + re-evict).
     *
     * Returns null if no sweep has ever been recorded for this fp (first
     * connect, or the row was wiped by [clearAllLocalData]).
     *
     * Key format: `last_sweep_epoch_<serverGroupFp>`. The fp is a stable host
     * identifier (profile id or merged group id) so the key survives host
     * list edits as long as the fp value itself does.
     */
    fun getLastSweepEpochDay(serverGroupFp: String): Long? {
        if (serverGroupFp.isBlank()) return null
        val key = lastSweepEpochKey(serverGroupFp)
        return if (encryptedPrefs.contains(key)) encryptedPrefs.getLong(key, -1L) else null
    }

    fun setLastSweepEpochDay(serverGroupFp: String, epochDay: Long) {
        if (serverGroupFp.isBlank()) return
        encryptedPrefs.edit().putLong(lastSweepEpochKey(serverGroupFp), epochDay).apply()
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
     *  3. `session_drafts` / `session_agents` / `session_models` JSON maps
     *     (bare-sessionId keys) → composite keys `"<fp>\u0000<sessionId>"`
     *     inside the same JSON maps.
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

        // ── 3) session_drafts / agents / models (bare sessionId) → composite ─
        // Rewrite the JSON maps in place: each entry's key is prefixed with
        // `<fp>\u0000`. Entries that already carry the composite prefix (a
        // prior partial migration wrote some entries before the flag landed)
        // are left alone.
        rewriteSessionMapLegacyToFp(KEY_SESSION_DRAFTS, serverGroupFp, e)
        rewriteSessionMapLegacyToFp(KEY_SESSION_AGENTS, serverGroupFp, e)
        rewriteSessionMapLegacyToFp(KEY_SESSION_MODELS, serverGroupFp, e)

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
        // R-20 Phase 0: cache DB key MUST survive a "reset local data" — if it
        // is wiped, the SQLCipher DB becomes permanently unreadable (the key
        // never rotates; losing it = losing the cache, which then has to be
        // destructive-reset in CacheModule on the next open). Same category as
        // the per-host password secrets — preserved across reset.
        val preservedKeys = connectionKeys + CACHE_DB_KEY
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
        /**
         * R-20 Phase 0: EncryptedSharedPreferences key holding the SQLCipher DB
         * passphrase (32 random bytes, Base64-encoded). Public so
         * [cn.vectory.ocdroid.data.cache.CacheKeyStore] and the
         * `clearAllLocalData` preserved-keys whitelist reference the SAME
         * constant — drift between the two would either silently wipe the key
         * on reset (cache unreadable) or leave it after a full wipe (cache
         * leaks across identities).
         */
        const val CACHE_DB_KEY = "cache_db_key"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_HOST_PROFILES = "host_profiles_json"
        private const val KEY_CURRENT_HOST_PROFILE_ID = "current_host_profile_id"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_LAST_NAV_PAGE = "last_nav_page"
        private const val KEY_CURRENT_WORKDIR = "current_workdir"
        private const val KEY_RECENT_WORKDIRS = "recent_workdirs"
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
         * R-20 Phase 3: storage key for [getLastSweepEpochDay] /
         * [setLastSweepEpochDay]. Prefix `last_sweep_epoch_` + the
         * serverGroupFp (a stable host identifier, never blank by the time
         * it reaches here — the public methods blank-guard upstream).
         */
        private fun lastSweepEpochKey(serverGroupFp: String): String = "last_sweep_epoch_$serverGroupFp"

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
