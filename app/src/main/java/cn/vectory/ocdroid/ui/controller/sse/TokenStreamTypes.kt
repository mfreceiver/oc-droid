package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.model.TokenStreamFrame
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.ui.BundleStamp

/**
 * A stream flow together with the exact immutable client bundle that created
 * it. The strongly typed [bundle] is retained by reference for
 * generation/endpoint validation.
 */
data class TokenStreamConnection(
    val flow: kotlinx.coroutines.flow.Flow<TokenStreamFrame>,
    val bundle: ClientBundle,
)

/**
 * §Stage-B C3 (CRITICAL): the route + bundle context captured at the moment
 * a token frame's commit hook fires. The hooks ([TokenStreamCoordinator.dedupPartRevision],
 * [TokenStreamCoordinator.onMessagePartRemoved], [TokenStreamCoordinator.onMessageRemoved])
 * MUST receive the context that was live at the frame's dispatch entry — NOT
 * a fresh "latest route token" read at callback time. Threading the captured
 * pair verbatim preserves the epoch+bundle critical-section invariant: a
 * bundle rotation between the epoch check and the hook is impossible, and a
 * late straggler frame cannot adopt a newer route incarnation.
 *
 * - [expectedRouteInstance]: the [SliceFlows.routeInstanceFor] snapshot
 *   captured at THIS lifecycle's open()/runStream() entry (the same value
 *   threaded as `capturedRouteInstance` through [TokenStreamCoordinator.dispatchEpochFrame]).
 * - [bundleStamp]: the [BundleStamp] derived from the bound [ClientBundle]
 *   (generation + endpointFp) at dispatch time. Production wiring (Lane I)
 *   forwards these verbatim into [AppAction.SlimFullMessageReconciled] /
 *   [AppAction.MessageRemovedConfirmed] so the §7.2 route + bundle CAS in the
 *   reducer stays the single source of truth for transcript mutations.
 */
data class TokenFrameCommitContext(
    val expectedRouteInstance: Long,
    val bundleStamp: BundleStamp,
)

/**
 * partId → owning (sid, generation) tag for the bgpt MF-3 generation guard.
 * `[OwnerTag] == OwnerTag(sid, gen)` is the equality check [clearPart] uses.
 */
internal data class OwnerTag(val sid: String, val gen: Long)

/**
 * Watchdog timeout sentinel — thrown by the watchdog coroutine to break the
 * collector out of `flow.collect { ... }` so the run loop's catch can run the
 * [TokenStreamCoordinator.onWatchdogTimeout] recovery sequence (clear + TriggerSinceFetch + reconnect).
 */
internal class TokenStreamWatchdogTimeout(val sid: String) : Exception(
    "token stream watchdog timeout sid=$sid"
)

/**
 * §MF-1 (gate r1): mid-collect Reconnect sentinel exception. Thrown by the
 * `flow.collect { }` lambda (right after [TokenStreamCoordinator.dispatchEpochFrame])
 * when the [TokenStreamCoordinator.reconnectRequested] sentinel was set by
 * [TokenStreamCoordinator.handleEffect] processing a
 * [TokenStreamCoordinatorEffect.Reconnect] effect. Unwinds the collector so
 * the run-loop's catch path — the SINGLE re-entry point — can call
 * [TokenStreamCoordinator.scheduleReconnect] AFTER the old flow's EventSource
 * is torn down (no overlapping collectors / no double cap-8 admission).
 */
internal class TokenStreamReconnectRequested(val sid: String) : Exception(
    "token stream reconnect requested sid=$sid"
)
