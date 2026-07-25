package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.ui.LoadedContent

/** B2 render authority for a parameterized chat route. */
internal fun isRouteContentRenderable(
    routeId: String?,
    content: LoadedContent?,
    routeInstance: Long,
): Boolean = routeId != null && content != null &&
    content.sessionId == routeId && content.routeInstance == routeInstance

/**
 * §B2 rev-gpt MAJOR 1: resolve the authoritative session id for transcript-
 * adjacent chrome (agent / model / status / revert-cutoff identity). For the
 * parameterized `chat/{sessionId}` route the route id governs: flat
 * `currentSessionId` can lag the route flip — `navigateToChat` commits
 * `nav.lastRoute` + the freshness token in ONE dispatch, and
 * `SessionSelected(currentSessionId=…)` follows in a SEPARATE dispatch — so
 * reading `currentSessionId` during that loading window would surface the
 * PRIOR session's chrome identity. The legacy bare-chat branch
 * (`routeSessionId == null`) keeps reading flat `currentSessionId`.
 *
 * Pure + AST-auditable so the chrome authority is a single testable seam.
 */
internal fun chromeSessionIdFor(
    routeSessionId: String?,
    currentSessionId: String?,
): String? = if (routeSessionId != null) routeSessionId else currentSessionId
