package cn.vectory.ocdroid.util

import android.content.SharedPreferences

/**
 * L4b domain split of [SettingsManager] — NAVIGATION domain.
 *
 * Owns the current session id and the top-level navigation persistence
 * ([KEY_LAST_ROUTE] route string). The legacy [KEY_LAST_NAV_PAGE] integer
 * key is now a read-only migration source — the [lastRoute] getter reads it
 * on first access (no [KEY_LAST_ROUTE] present yet) as a one-shot migration
 * to the route string. No public accessor exposes [KEY_LAST_NAV_PAGE].
 *
 * Behavior byte-identical to pre-split [SettingsManager]: same ESP instance,
 * same key strings, same `workspace → files` and unknown-route → chat
 * rewrites, same first-read-and-write migration. NO key renames.
 */
internal class NavigationPrefs(
    private val encryptedPrefs: SharedPreferences,
) {
    var currentSessionId: String?
        get() = encryptedPrefs.getString(KEY_SESSION_ID, null)
        set(value) = encryptedPrefs.edit().putString(KEY_SESSION_ID, value).apply()

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

    companion object {
        internal const val KEY_SESSION_ID = "session_id"
        internal const val KEY_LAST_NAV_PAGE = "last_nav_page"
        internal const val KEY_LAST_ROUTE = "last_route"
        internal const val ROUTE_CHAT = "chat"
        internal const val ROUTE_SESSIONS = "sessions"
        internal const val ROUTE_FILES = "files"
        internal const val ROUTE_GIT = "git"
        internal const val ROUTE_SETTINGS = "settings"
        internal val TOP_LEVEL_ROUTE_KEYS = setOf(
            ROUTE_CHAT,
            ROUTE_SESSIONS,
            ROUTE_FILES,
            ROUTE_GIT,
            ROUTE_SETTINGS,
        )
    }
}
