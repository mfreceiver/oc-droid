package cn.vectory.ocdroid.util

import android.content.SharedPreferences
import android.util.Log
import cn.vectory.ocdroid.data.model.SessionCacheEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * L4b domain split of [SettingsManager] — SESSION domain.
 *
 * Owns the cold-start session-list seeding surface: the browser-tab style
 * open-session id list, the persisted session-metadata cache, and the
 * per-(serverGroupFp, sessionId) draft text map.
 *
 * §L4b ESP-key ownership: this class owns the [COMPOSITE_KEY_SEPARATOR]
 * constant and the [compositeSessionKey] builder used by the drafts map
 * AND by [MigrationHelper.rewriteSessionMapLegacyToFp]. The public API
 * `SettingsManager.COMPOSITE_KEY_SEPARATOR` is re-exported from here so
 * the test fixture `SettingsManagerMigrationTest` keeps resolving.
 *
 * Behavior byte-identical to pre-split [SettingsManager]: same ESP
 * instance, same key strings, same NUL-separated composite encoding, same
 * JSON parse-fallback defaults. NO key renames.
 */
internal class SessionPrefs(
    private val encryptedPrefs: SharedPreferences,
) {
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
     * bare sessionId. [MigrationHelper.migrateLegacyKeysToFp] rewrites every
     * legacy entry to `"<currentFp>\u0000<sessionId>"` once per fp
     * (idempotent).
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

    companion object {
        private const val TAG = "SettingsManager"

        internal const val KEY_OPEN_SESSION_IDS = "open_session_ids"
        internal const val KEY_SESSION_CACHE = "session_cache"
        internal const val KEY_SESSION_DRAFTS = "session_drafts"

        /**
         * R-20 Phase 5: separator used in the composite `(serverGroupFp,
         * sessionId)` map key. NUL (\u0000) is chosen because serverGroupFp
         * is a UUID / branded string (Phase 0 guarantees nonblank + the
         * HostProfile decode normalize step never produces one containing
         * NUL), so `"$fp\u0000$sessionId"` is an unambiguous reversible
         * encoding — no fp value can collide with a sessionId prefix.
         *
         * Public so tests + [MigrationHelper.rewriteSessionMapLegacyToFp]
         * share the constant.
         */
        const val COMPOSITE_KEY_SEPARATOR = '\u0000'

        /**
         * R-20 Phase 5: builds the composite map key for per-(fp, sessionId)
         * storage (drafts / agents / models). See [COMPOSITE_KEY_SEPARATOR].
         */
        internal fun compositeSessionKey(serverGroupFp: String, sessionId: String): String =
            "$serverGroupFp$COMPOSITE_KEY_SEPARATOR$sessionId"
    }
}
