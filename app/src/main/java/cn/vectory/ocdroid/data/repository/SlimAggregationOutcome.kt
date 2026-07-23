package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.SlimapiAggregationError
import cn.vectory.ocdroid.data.model.SlimapiPermissionEntry
import cn.vectory.ocdroid.data.model.SlimapiScope

/**
 * Typed aggregation outcome for cross-directory q/p fetches.
 *
 * - [Failure] — transport/HTTP/decode failure; preserve all prior state.
 * - [Success] — replace authoritative scope. [authoritativeDirectories] null
 *   means globally authoritative; non-null scopes replacement.
 * - [Partial] — replace only directories proven successful; preserve
 *   failed/unknown-directory prior values.
 */
sealed interface SlimAggregationOutcome<out T> {
    /**
     * C-D3 v2 §3.2 / I-2: transport/HTTP/decode failure; preserve all
     * prior state. Carries an optional [message] so the UI can surface
     * a failure toast/banner via [aggregationSignal].
     */
    data class Failure(
        val message: String? = null,
    ) : SlimAggregationOutcome<Nothing>

    data class Success<T>(
        val items: List<T>,
        val authoritativeDirectories: Set<String>?,
        /**
         * T2 (slimapi v0.2.2): the sidecar readiness scope carried by the
         * envelope (`scope:{directories:N}`). Null = old sidecar / 503 →
         * original behavior. `directories==0` = not ready → caller retains
         * prior. `directories>0` = authoritative.
         */
        val serverScope: SlimapiScope? = null,
    ) : SlimAggregationOutcome<T>

    data class Partial<T>(
        val items: List<T>,
        val errors: List<SlimapiAggregationError>,
        val authoritativeDirectories: Set<String>,
        /**
         * T2 (slimapi v0.2.2): same readiness contract as
         * [Success.serverScope]. A Partial can also carry the scope from a
         * 200-with-errors response (sidecar shipped `errors[]` AND a
         * `scope`); the gating logic in `applyAggregationOutcome` applies
         * the same `directories==0` retain-prior rule.
         */
        val serverScope: SlimapiScope? = null,
    ) : SlimAggregationOutcome<T>
}

/**
 * I-2: fold per-source aggregation envelope into a typed outcome.
 * [requestedDirectories] is the directory list passed to the API call.
 *
 * T2 (slimapi v0.2.2): [serverScope] is the envelope's `scope:{directories:N}`
 * carried through into [SlimAggregationOutcome.Success] /
 * [SlimAggregationOutcome.Partial] so the caller can gate on
 * `directories==0` (sidecar allowlist not ready). Default null
 * preserves the original behavior for non-slim / pre-0.2.2 callers.
 */
internal fun <T> aggregationOutcome(
    items: List<T>,
    errors: List<SlimapiAggregationError>,
    requestedDirectories: List<String>?,
    directoryOf: (T) -> String?,
    serverScope: SlimapiScope? = null,
): SlimAggregationOutcome<T> {
    val requested = requestedDirectories?.toSet()

    if (errors.isEmpty()) {
        return SlimAggregationOutcome.Success(
            items = items,
            authoritativeDirectories = requested,
            serverScope = serverScope,
        )
    }

    val failedDirectories = errors.mapNotNull { it.directory }.toSet()
    val hasUnknownFailedDirectory = errors.any { it.directory == null }

    val itemDirectories = items.mapNotNull(directoryOf).toSet()

    val successfulDirectories = when {
        // Cannot safely infer which requested directory failed. Only directories
        // represented by returned items are proven successful.
        hasUnknownFailedDirectory -> itemDirectories

        // Requested scope is known: every requested directory not in errors
        // completed, including successful-empty directories.
        requested != null -> requested - failedDirectories

        // Scope unknown: only item-bearing directories are provably successful.
        else -> itemDirectories - failedDirectories
    }

    return SlimAggregationOutcome.Partial(
        items = items,
        errors = errors,
        authoritativeDirectories = successfulDirectories,
        serverScope = serverScope,
    )
}

/**
 * §slim-reconcile-lane-repo (B2 T4) / §rev-grok fix1: adapt a
 * [SlimapiPermissionEntry] (the slimapi aggregate shape = legacy fields +
 * slimapi-only `directory` + `routeToken`) to the legacy [PermissionRequest]
 * model that the UI / VM consumers expect.
 *
 * **Preserves [SlimapiPermissionEntry.routeToken]** (Phase 3b): the slimapi
 * respond path ([OpenCodeRepository.respondSlimapiPermission]) requires the
 * sidecar HMAC to validate the response POST (§2/B2). The upper-layer
 * [ChatScaffold] reads [PermissionRequest.routeToken] off the
 * `pendingPermissions` entry and forwards it to
 * [OrchestratorViewModel.respondPermission]; if this mapping drops the
 * token, the slim respond falls back to the legacy endpoint and the
 * sidecar rejects / misroutes the response. (`directory` stays dropped —
 * the sidecar re-injects it from the token, and the upper layer's
 * session-set already implies it.)
 *
 * Hoisted top-level so the mapping is unit-testable in isolation (see
 * [OpenCodeRepositorySlimapiEndpointsTest]`getPendingPermissions slim mode
 * maps entries to PermissionRequest`).
 */
internal fun SlimapiPermissionEntry.toPermissionRequest(): PermissionRequest =
    PermissionRequest(
        id = id,
        sessionId = sessionId,
        permission = permission,
        patterns = patterns,
        metadata = metadata,
        always = always,
        tool = tool,
        directory = directory,
        routeToken = routeToken,
    )
