package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.SlimapiPermissionEntry
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.SlimAggregationOutcome
import cn.vectory.ocdroid.data.repository.toPermissionRequest
import cn.vectory.ocdroid.ui.controller.applyAggregationOutcome
import cn.vectory.ocdroid.ui.controller.aggregationSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import cn.vectory.ocdroid.util.DebugLog

/**
 * §R-17 batch3d: extracted orchestrator for permission refresh operations.
 *
 * Owns the two permission-loading free-functions moved from [SessionListActions]:
 * - [launchLoadPendingPermissions] (standard/legacy path)
 * - [launchLoadPendingPermissionsSlim] (slim path with token guard)
 *
 * Injected dependencies are passed per-call (no constructor state).
 */
internal object PermissionRefreshOrchestrator {

    /**
     * §R-17 batch3d: free-function extraction of the former AppCore.loadPendingPermissions
     * body. Called by [SessionViewModel.loadPendingPermissions] and by AppCore's
     * effect-dispatch handler.
     *
     * §rev-grok fix1 (permissions routeToken): the previous full-replace
     * (`it.copy(pendingPermissions = permissions)`) silently wiped the
     * `routeToken` that slim SSE `permission.asked` had folded into the
     * existing entry — because [SlimapiPermissionEntry.toPermissionRequest]
     * historically dropped it. The slim respond path then saw `routeToken=null`
     * and fell back to the legacy endpoint (contract §2/B2 violation). The
     * merge now preserves a known-good routeToken across a null-token REST
     * refresh (intersection only — see rule 3 below).
     *
     * §rev-grok 9.5 🟠1 (ghost cleanup): the Fix1 union-with-existing rule
     * ("existing fills gaps") created ghost permissions — a permission the
     * server resolved / expired without the client receiving the resolve
     * event stayed in pendingPermissions forever. This function now mirrors
     * the AUTHORITATIVE reconcile pattern of
     * [SessionSyncCoordinator.loadPendingQuestionsAllWorkdirs] (the full-sweep
     * analog, NOT the single-workdir-optimistic `launchLoadPendingQuestions`
     * which intentionally keeps existing to avoid flicker on a narrow scope).
     *
     * Three merge rules:
     *
     *  1. **Membership is REST-authoritative** (de-ghost): every fetched entry
     *     is added (dedup by id); an id present at poll-start that REST did
     *     NOT return is dropped (server no longer lists it → resolved/expired).
     *  2. **Race-window preservation**: a `permission.asked` that lands DURING
     *     the poll — i.e. present in the post-fetch slice but NOT in [startIds]
     *     (the pre-poll snapshot) and NOT in the REST response — is preserved.
     *     Without this, a fresh arrival the REST sweep hadn't yet observed
     *     would be silently dropped (race loss).
     *  3. **Token never downgrades** (Fix1 protection): on the intersection
     *     (REST entry AND existing entry), take REST as the baseline, BUT if
     *     REST's routeToken is null/blank and the existing entry has a
     *     non-null token, preserve the existing token (slim SSE → REST race).
     *
     * Defensive: in slim mode, a final entry with a null routeToken logs WARN
     * (the slim respond path cannot route it correctly — surfaces the issue
     * instead of silently degrading to the legacy endpoint).
     *
     * §B3-retirement: in slim mode, delegates to [launchLoadPendingPermissionsSlim]
     * which captures ONE [cn.vectory.ocdroid.data.repository.OpenCodeRepository.ConnectionCapture]
     * before the network suspend + guards the slice / signal / UiEvent commits
     * inside a single `commitIfConnectionCaptureCurrent` block. The legacy
     * non-slim path is unchanged.
     */
    fun launchLoadPendingPermissions(
        scope: CoroutineScope,
        repository: OpenCodeRepository,
        slices: SliceFlows,
        effects: SharedEffectBus,
        tag: String,
    ) {
        if (repository.supportsWatermarkResync) {
            launchLoadPendingPermissionsSlim(
                scope = scope,
                repository = repository,
                slices = slices,
                effects = effects,
            )
            return
        }
        scope.launch {
            // §rev-grok 9.5 🟠1: snapshot pending ids BEFORE the poll so the
            // post-fetch merge can distinguish "existed at start" (REST-
            // authoritative — drop if absent from REST → ghost cleanup) from
            // "arrived during the poll" (race-window — preserve to avoid
            // dropping a fresh permission.asked the REST sweep hasn't seen).
            // Mirrors [SessionSyncCoordinator.loadPendingQuestionsAllWorkdirs]'s
            // startIds snapshot (NOT the single-workdir-optimistic
            // [launchLoadPendingQuestions], which keeps existing to avoid
            // flicker — there is no single-workdir variant for permissions;
            // this sweep IS authoritative).
            val startIds = slices.sessionList.value.pendingPermissions
                .mapTo(mutableSetOf()) { it.id }
            repository.getPendingPermissions()
                .onSuccess { permissions ->
                    slices.mutateSessionList { currentState ->
                        val existing = currentState.pendingPermissions.associateBy { it.id }
                        val fetchedIds = mutableSetOf<String>()
                        val merged = buildList {
                            // Rule 1: REST authoritative for membership (de-ghost).
                            permissions.forEach { rest ->
                                if (fetchedIds.add(rest.id)) {
                                    val prev = existing[rest.id]
                                    // Rule 3 (Fix1): never downgrade a known-good
                                    // routeToken to null (slim SSE → REST race).
                                    val resolved = when {
                                        prev == null -> rest
                                        rest.routeToken.isNullOrBlank() &&
                                            !prev.routeToken.isNullOrBlank() ->
                                            rest.copy(routeToken = prev.routeToken)
                                        else -> rest
                                    }
                                    add(resolved)
                                }
                            }
                            // Rule 2: race-window — a permission.asked that
                            // landed DURING the poll (NOT in startIds, NOT in
                            // fetched) is preserved (REST sweep hasn't seen it
                            // yet). Entries that WERE in startIds and are absent
                            // from fetched are intentionally dropped here (rule 1).
                            currentState.pendingPermissions.forEach { p ->
                                if (p.id !in fetchedIds && p.id !in startIds) add(p)
                            }
                        }
                        // §rev-grok fix1 defensive: in slim mode warn on any
                        // final entry lacking a routeToken (no silent fallback
                        // — the slim respond path will be unable to route it).
                        if (repository.supportsWatermarkResync) {
                            merged.filter { it.routeToken.isNullOrBlank() }.forEach { p ->
                                Log.w(
                                    tag,
                                    "slim pending permission has null routeToken (slim respond will misroute): id=${p.id}",
                                )
                            }
                        }
                        currentState.copy(pendingPermissions = merged)
                    }
                }
                .onFailure { error -> Log.w(tag, "Failed to load permissions: ${error.message}") }
        }
    }

    /**
     * §B3-retirement: slim standalone permissions load via
     * [OpenCodeRepository.getSlimapiPermissions]. Uses [ConnectionCapture] guard
     * to detect stale responses after a host reconfigure. A stale result is a
     * clean no-op (no slice mutation, no signal update, no toast).
     */
    private fun launchLoadPendingPermissionsSlim(
        scope: CoroutineScope,
        repository: OpenCodeRepository,
        slices: SliceFlows,
        effects: SharedEffectBus,
    ) {
        scope.launch {
            // §B3-retirement: ONE capture before first suspend.
            val capture = repository.captureConnection()

            val startIds = slices.sessionList.value.pendingPermissions
                .mapTo(mutableSetOf()) { it.id }

            val outcome = repository.getSlimapiPermissions(
                directories = null,
            ).getOrElse { error ->
                SlimAggregationOutcome.Failure(error.message)
            }

            if (!repository.isConnectionCaptureCurrent(capture)) return@launch

            // §B3-retirement: ALL slice + effect commits land inside ONE atomic
            // gate so a host rotation between the network return and the slice
            // commit cannot write a stale permission list / signal under a
            // new host.
            repository.commitIfConnectionCaptureCurrent(capture) {
                val signal = aggregationSignal(outcome)

                slices.mutateSessionList { current ->
                    val folded = applyAggregationOutcome(
                        prior = current.pendingPermissions,
                        outcome = outcome,
                        wireToUi = SlimapiPermissionEntry::toPermissionRequest,
                        uiId = PermissionRequest::id,
                        uiDirectory = PermissionRequest::directory,
                    )

                    val foldedIds = folded.items
                        .mapTo(mutableSetOf()) { it.id }

                    val raceArrivals =
                        current.pendingPermissions.filter { permission ->
                            permission.id !in startIds &&
                                permission.id !in foldedIds
                        }

                    current.copy(
                        pendingPermissions = (folded.items + raceArrivals)
                            .distinctBy { it.id },
                        permissionAggregationSignal = signal,
                    )
                }

                if (outcome is SlimAggregationOutcome.Failure) {
                    effects.tryEmitUiEvent(
                        UiEvent.Error(R.string.error_slim_permissions_fetch_failed)
                    )
                }
            }
        }
    }
}
