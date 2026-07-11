package cn.vectory.ocdroid.ui

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Stable top-level route identities. Persist [route], never enum ordinals.
 *
 * §phase2 workspace-family classification: [fromRouteKey] recognises the
 * Workspace family (`workspace`, `workspace/files?...`, `workspace/changes?...`)
 * via PREFIX match — the nested routes (`workspace/files`,
 * `workspace/changes?session=x`) MUST classify as [Workspace] for nav-bar
 * selection and back handling, NOT fall back to [Chat] (the §phase2 fix-6
 * regression). The nested-vs-top-level distinction (for back behaviour) is
 * exposed via [isNestedWorkspaceRoute].
 */
enum class NavRoute(val route: String, val legacyPage: Int) {
    Chat("chat", 0),
    Sessions("sessions", 1),
    Workspace("workspace", 0),
    Settings("settings", 2);

    companion object {
        const val workspaceFilesRoute: String = "workspace/files?workdir={workdir}&path={path}"
        const val workspaceChangesRoute: String = "workspace/changes?session={session}"

        // §phase3 (G.3 / D.8): nested Settings sub-routes. Constants only —
        // they are NOT new NavRoute entries. Classification-wise they all
        // belong to the [Settings] parent (for nav-bar selection + back
        // handling), mirroring the workspace-family pattern. Listed here so
        // AppShell's NavHost + PhoneLayout's local nav share one source of
        // truth for the route keys.
        const val settingsHostsRoute: String = "settings/hosts"
        const val settingsAppearanceRoute: String = "settings/appearance"
        const val settingsModelsRoute: String = "settings/models"
        const val settingsNotificationsRoute: String = "settings/notifications"
        const val settingsStorageRoute: String = "settings/storage"
        const val settingsAboutRoute: String = "settings/about"

        val topLevel: List<NavRoute> = entries

        /**
         * Builds the Files route with both the workdir (for FilesScreen's
         * `sessionDirectory` binding) and the specific path (for
         * `pathToShow` location). Both optional — Navigation Compose treats
         * query params after `?` as optional, so navigating to the bare
         * `workspace/files` matches the registered pattern with null args.
         * Callers MUST use this builder — never concatenate route strings
         * by hand.
         */
        fun workspaceFiles(workdir: String? = null, path: String? = null): String {
            val params = mutableListOf<String>()
            if (!workdir.isNullOrBlank()) {
                params += "workdir=" + encodeParam(workdir)
            }
            if (!path.isNullOrBlank()) {
                params += "path=" + encodeParam(path)
            }
            return if (params.isEmpty()) "workspace/files"
            else "workspace/files?" + params.joinToString("&")
        }

        /** Builds the only parameterized Changes route; callers must not concatenate routes. */
        fun workspaceChanges(sessionId: String): String =
            "workspace/changes?session=${encodeParam(sessionId)}"

        /**
         * §phase2 workspace-family classification. Recognises the Workspace
         * family by PREFIX match so `workspace/files?...` and
         * `workspace/changes?session=...` classify as [Workspace] (not
         * [Chat]). Exact match on the 4 top-level routes; the
         * `workspace/...` prefix arms the nested case. Null / unknown →
         * [Chat] (start destination).
         *
         * §phase3 (G.3 / D.8): also recognises settings-prefixed nested
         * routes (settings/hosts, settings/appearance, ...) as [Settings]
         * for nav-bar selection + back handling.
         */
        fun fromRouteKey(route: String?): NavRoute {
            if (route == null) return Chat
            entries.firstOrNull { it.route == route }?.let { return it }
            // §phase2: nested workspace routes (workspace/files,
            // workspace/changes?session=...) classify as Workspace for
            // nav-bar selection. The prefix is the base `workspace` route
            // followed by `/` — `workspace` exact-match is handled above.
            if (route.startsWith("workspace/")) return Workspace
            // §phase3: nested settings sub-routes (settings/hosts,
            // settings/appearance, settings/models, settings/notifications,
            // settings/storage, settings/about) classify as Settings so the
            // Settings nav item stays selected while the user drills in.
            if (route.startsWith("settings/")) return Settings
            return Chat
        }

        /**
         * §phase2: distinguishes a NESTED workspace route
         * (`workspace/files?...`, `workspace/changes?session=...`) from the
         * top-level `workspace` destination. AppShell uses this to decide
         * back behaviour: nested routes POP the NavHost back stack (back to
         * the Workspace base / previous destination); the top-level route
         * routes back to Chat (Phase 1A contract).
         */
        fun isNestedWorkspaceRoute(route: String?): Boolean {
            if (route == null) return false
            return route != Workspace.route && route.startsWith("workspace/")
        }

        /**
         * §phase3 (G.3 / D.8): distinguishes a NESTED Settings sub-route
         * (`settings/hosts`, `settings/appearance`, ...) from the top-level
         * `settings` destination. Used by AppShell's BackHandler so a
         * nested settings route POPS the NavHost back to the Settings root
         * (rather than routing back to Chat per the Phase 1A contract).
         * Mirrors [isNestedWorkspaceRoute].
         */
        fun isNestedSettingsRoute(route: String?): Boolean {
            if (route == null) return false
            return route != Settings.route && route.startsWith("settings/")
        }

        /** Legacy phone-layout order: 2 was Settings, never Workspace. */
        fun fromLegacyPage(page: Int): NavRoute = when (page) {
            1 -> Sessions
            2 -> Settings
            else -> Chat
        }

        private fun encodeParam(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
