package cn.vectory.ocdroid.ui

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Stable top-level route identities. Persist [route], never enum ordinals. */
enum class NavRoute(val route: String, val legacyPage: Int) {
    Chat("chat", 0),
    Sessions("sessions", 1),
    Files("files", 0),
    Git("git", 0),
    Settings("settings", 2);

    companion object {
        const val chatPreviewRoutePattern: String = "chat/preview?workdir={workdir}&path={path}"
        const val filesRoutePattern: String = "files?workdir={workdir}&path={path}"
        // §home-hub T7-C3: single pattern, two nullable args (mirrors
        // [filesRoutePattern] precedent). Either/both may be absent; the
        // composable block decodes them and forwards to GitScreen.
        const val gitRoutePattern: String = "git?session={session}&workdir={workdir}"

        // Nested Settings routes belong to the Settings top-level destination.
        const val settingsHostsRoute: String = "settings/hosts"
        const val settingsAppearanceRoute: String = "settings/appearance"
        const val settingsModelsRoute: String = "settings/models"
        const val settingsNotificationsRoute: String = "settings/notifications"
        const val settingsAboutRoute: String = "settings/about"

        /** Builds a fullscreen file preview pushed onto the Chat back stack. */
        fun chatPreviewRoute(workdir: String? = null, path: String? = null): String =
            parameterizedRoute(
                base = "chat/preview",
                "workdir" to workdir,
                "path" to path,
            )

        /** Builds the Files destination with optional workdir and selected path. */
        fun filesRoute(workdir: String? = null, path: String? = null): String =
            parameterizedRoute(
                base = Files.route,
                "workdir" to workdir,
                "path" to path,
            )

        /**
         * Builds the Git destination with an optional session scope and/or an
         * explicit workdir.
         *
         * §home-hub T7-C3: the home hub's per-project Git IconButton navigates
         * with `workdir` only (no session); Chat's "open git changes" still
         * passes `sessionId`. Both flow through [parameterizedRoute], which
         * drops blank/null params — so the bare base `"git"` is produced when
         * neither is supplied.
         */
        fun gitRoute(sessionId: String? = null, workdir: String? = null): String =
            parameterizedRoute(
                base = Git.route,
                "session" to sessionId,
                "workdir" to workdir,
            )

        /** Classifies top-level destinations and the nested Settings family. */
        fun fromRouteKey(route: String?): NavRoute {
            val base = route?.substringBefore('?') ?: return Chat
            entries.firstOrNull { it.route == base }?.let { return it }
            if (base.startsWith("settings/")) return Settings
            return Chat
        }

        fun isNestedSettingsRoute(route: String?): Boolean =
            route != null && route != Settings.route && route.startsWith("settings/")

        /** Legacy phone-layout order: 2 was Settings; Files and Git have no legacy page. */
        fun fromLegacyPage(page: Int): NavRoute = when (page) {
            1 -> Sessions
            2 -> Settings
            else -> Chat
        }

        private fun parameterizedRoute(base: String, vararg parameters: Pair<String, String?>): String {
            val query = parameters.mapNotNull { (name, value) ->
                value?.takeIf { it.isNotBlank() }?.let { "$name=${encodeParam(it)}" }
            }
            return if (query.isEmpty()) base else "$base?${query.joinToString("&")}"
        }

        private fun encodeParam(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
