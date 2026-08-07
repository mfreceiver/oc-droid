package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.model.TokenStreamFrame
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.data.repository.TokenPartStreamState
import cn.vectory.ocdroid.data.repository.TokenStreamCoordinatorEffect
import cn.vectory.ocdroid.data.repository.TokenStreamReducer
import cn.vectory.ocdroid.data.repository.TokenStreamReducerState
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.BundleStamp
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.StreamOwnedState
import cn.vectory.ocdroid.util.DebugLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Reducer → [ChatState] bridging + effect translation + revision-hook invocation.
 *
 * Owns [reducerStateBySid] (per-sid working state). Receives the [TokenFrameGuard]
 * for generation-guarded ownership operations and the revision hooks for
 * B-P0-1/B-P0-2 dedup / part-removal wiring. All frame-dispatch methods assume
 * the caller holds [bundleCommitLock] (the single monitor shared with the facade
 * and [StreamLifecycleSupervisor]).
 *
 * @param slices UI slice flows (for [dispatchBound]).
 * @param guard The epoch/generation/ownership guard.
 * @param bundleCommitLock The shared monitor (all synchronized blocks use it).
 * @param currentBundleProvider Returns the currently-published [ClientBundle].
 * @param triggerSinceFetch Callback for `/since` fetch (D2 wiring).
 * @param requestReconnect Callback to set the §MF-1 reconnect sentinel on the
 *   supervisor. Called with the sid when the reducer emits a Reconnect effect.
 * @param onAnyFrame Callback fired on every successfully-dispatched frame.
 *   The supervisor uses it to reset the watchdog clock and the attempt counter.
 * @param dedupPartRevision B-P0-1 per-part revision dedup hook.
 * @param onMessagePartRemoved B-P0-2 hook for `message.part.removed`.
 * @param onMessageRemoved B-P0-2 hook for `message.removed`.
 * @param onPartDone B-4 HIGH-2 hook for done:true parts.
 * @param clearSessionRevisions B-4 HIGH-2 hook to reclaim all revisions for a sid.
 */
internal class TokenStateDispatcher(
    private val slices: SliceFlows,
    private val guard: TokenFrameGuard,
    private val bundleCommitLock: Any,
    private val currentBundleProvider: () -> ClientBundle?,
    private val triggerSinceFetch: (sessionId: String, authoritative: Boolean) -> Unit,
    private val requestReconnect: (sid: String) -> Unit,
    private val onAnyFrame: (sid: String) -> Unit,
    private val dedupPartRevision: (
        sessionId: String, messageId: String, partId: String, partEventRevision: Long?,
        context: TokenFrameCommitContext,
    ) -> Boolean,
    private val onMessagePartRemoved: (
        sessionId: String, messageId: String, partId: String, messageEventSeq: Long,
        context: TokenFrameCommitContext,
    ) -> Unit,
    private val onMessageRemoved: (
        sessionId: String, messageId: String,
        context: TokenFrameCommitContext,
    ) -> Unit,
    private val onPartDone: (
        sessionId: String, messageId: String, partId: String,
    ) -> Unit,
    private val clearSessionRevisions: (
        sessionId: String,
    ) -> Unit,
) {
    /** Per-sid reducer working state (single active stream, but per-sid for safety). */
    private val reducerStateBySid = ConcurrentHashMap<String, TokenStreamReducerState>()

    private val TAG = "TokenStreamCoordinator"

    // ── Bundle validation ────────────────────────────────────────────────────

    private fun isBundleCurrentForCommit(bundle: ClientBundle): Boolean =
        bundle === currentBundleProvider()

    // ── Bound dispatch ───────────────────────────────────────────────────────

    /**
     * Dispatches [action] iff [boundBundle] is still the currently-published
     * bundle. Must be called inside [bundleCommitLock]; see the entry-fence
     * comment below.
     */
    private fun dispatchBound(boundBundle: ClientBundle, action: AppAction) {
        synchronized(bundleCommitLock) {
            // Entry fence: boundBundle must be the currently-published bundle.
            // currentBundleProvider() cannot change inside this lock
            // (bundleCommitLock === repository's @Synchronized monitor), so no
            // mid-block re-check is needed.
            if (currentBundleProvider() !== boundBundle) {
                DebugLog.d(TAG, "bundle-bound dispatch rejected: superseded bundle")
                return
            }
            slices.store.dispatch(action)
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Dispatches an ownership clear with a stamp captured under the bundle lock. */
    fun dispatchTokenStreamClear(
        partIds: Set<String>,
        expectedRouteInstance: Long,
        sessionId: String?,
    ): Boolean {
        synchronized(bundleCommitLock) {
            val bundle = currentBundleProvider() ?: return false
            dispatchBound(
                bundle,
                AppAction.ClearTokenStreamState(
                    partIds = partIds,
                    expectedRouteInstance = expectedRouteInstance,
                    sessionId = sessionId,
                    bundleStamp = BundleStamp(bundle.generation, bundle.endpointFp),
                ),
            )
            return true
        }
    }

    /**
     * Post-guard dispatch body. Called by the facade inside [bundleCommitLock]
     * after the epoch guard passes. Runs the reducer, bridges part-text changes
     * into ChatState, processes effects (deferred via [deferredEffects]).
     */
    fun processFrameBody(
        sid: String,
        epoch: Long,
        gen: Long,
        frame: TokenStreamFrame,
        capturedRouteInstance: Long,
        boundBundle: ClientBundle,
        deferredEffects: MutableList<() -> Unit>,
    ) {
        // Reset watchdog on ANY frame (incl. heartbeat + server.connected).
        onAnyFrame(sid)

        // §Stage-B C3 (CRITICAL): capture the route + bundle context ONCE
        // inside the epoch+bundle critical section (after both guards have
        // passed) and thread it VERBATIM through every hook call below. The
        // hooks MUST receive the context that was live at THIS frame's
        // dispatch entry — NOT a fresh read at callback time (a bundle
        // rotation or route advance between the guards and the hook is
        // impossible inside the synchronized block, and threading the
        // captured pair prevents a late straggler frame from adopting a
        // newer route incarnation). Lane I forwards these unchanged into
        // [AppAction.SlimFullMessageReconciled] / [AppAction.MessageRemovedConfirmed]
        // so the §7.2 route + bundle CAS in the reducer is the single source
        // of truth for transcript mutations.
        val commitContext = TokenFrameCommitContext(
            expectedRouteInstance = capturedRouteInstance,
            bundleStamp = BundleStamp(boundBundle.generation, boundBundle.endpointFp),
        )

        val priorState = reducerStateBySid[sid] ?: TokenStreamReducerState()
        // S2 (Stage-C should-fix): a resync frame may arrive with
        // sessionId == null (backpressure overflow omits it per the sidecar's
        // handoff contract). The reducer cannot act on a null sid (it would
        // return no effects), so the coordinator rewrites the frame to carry
        // the ACTIVE connection's sid BEFORE reduction. This is the engine-
        // level fix; handleEffect's TriggerSinceFetch branch carries a second
        // defensive fallback for any other path that surfaces a null sid.
        val effectiveFrame: TokenStreamFrame =
            if (frame is TokenStreamFrame.Resync && frame.sessionId == null) {
                frame.copy(sessionId = sid)
            } else {
                frame
            }
        // The reducer's resync branch unions reducer-known parts with the
        // EXTERNALLY-owned parts for the sid (the ChatState.streamOwned view).
        // We feed the guard's owned-parts projection as that union source — it
        // IS the authoritative ownership map (Stage A's clear reads the same
        // concept via ChatState.streamOwned; D1 keeps its own working set so
        // the engine stays decoupled from the UI slice in unit tests).
        val ownedBySession: Map<String, Set<String>> = mapOf(sid to guard.ownedPartsForSid(sid))
        // B-P0-1 (R1+R2 dedup wiring): apply the per-part revision
        // dedup BEFORE the streaming reducer. A `false` return means
        // the frame is a re-delivery within the 250ms debounce window
        // — drop it (no reducer state mutation, no bridge, no effects).
        // The hook is the injected [dedupPartRevision] callback; it is
        // null-safe (a null partEventRevision always returns true
        // — accept every frame, matching the pre-B-P0-1 behavior).
        //
        // Per the B-P0-3 frozen contract: the dedup MUST run BEFORE
        // TokenStreamReducer.reduce to prevent the streaming-overlay
        // from being polluted by a re-delivered snapshot/delta.
        when (effectiveFrame) {
            is TokenStreamFrame.PartSnapshot -> {
                val fresh = dedupPartRevision(
                    effectiveFrame.sessionId,
                    effectiveFrame.messageId,
                    effectiveFrame.partId,
                    effectiveFrame.partEventRevision,
                    commitContext,
                )
                if (!fresh) {
                    DebugLog.d(
                        TAG,
                        "drop dup part-snapshot sid=${effectiveFrame.sessionId} " +
                            "mid=${effectiveFrame.messageId} pid=${effectiveFrame.partId} " +
                            "rev=${effectiveFrame.partEventRevision}",
                    )
                    return
                }
            }
            is TokenStreamFrame.PartDelta -> {
                val fresh = dedupPartRevision(
                    effectiveFrame.sessionId,
                    effectiveFrame.messageId,
                    effectiveFrame.partId,
                    effectiveFrame.partEventRevision,
                    commitContext,
                )
                if (!fresh) {
                    DebugLog.d(
                        TAG,
                        "drop dup part-delta sid=${effectiveFrame.sessionId} " +
                            "mid=${effectiveFrame.messageId} pid=${effectiveFrame.partId} " +
                            "rev=${effectiveFrame.partEventRevision}",
                    )
                    return
                }
            }
            else -> { /* no dedup for non-part frames */ }
        }
        val (newState, effects) = TokenStreamReducer.reduce(priorState, effectiveFrame, ownedBySession)
        reducerStateBySid[sid] = newState

        // B-P0-2 (MAJOR 4 + replacement edge): fire the message.part.removed
        // / message.removed hooks AFTER the reducer has cleared its in-memory
        // overlay (the [TokenStreamReducer.reduce] call above already removed
        // the part(s) from [reducerStateBySid]). The hooks run INSIDE the
        // bundleCommitLock critical section (no bundle rotation between epoch
        // check and hook), carrying the [commitContext] captured at this
        // frame's dispatch entry. The ClearPartState effect (chat-slice
        // streamOwned/streamingPartTexts clear) is processed AFTER the hooks
        // via [handleEffect] below — the §Stage-B C3 frozen contract is:
        // reducer-overlay clear (synchronous, in-memory) → hook (watermark +
        // R2 debounce schedule) → chat-slice overlay clear (effect dispatch).
        // The hook implementations are no-ops by default; B-P0-2's DI wiring
        // (Lane I, ControllerModule) injects the production callbacks
        // (applyMessagePartRemoved / applyMessageRemoved + debounced
        // reconcileActiveSession / MessageRemovedConfirmed dispatch).
        when (effectiveFrame) {
            is TokenStreamFrame.PartSnapshot -> {
                // B-4 HIGH-2 + HIGH-1: reclaim revision entry when part reaches
                // done:true OR truncated:true. Both are terminal: the reducer
                // removes the part from the overlay (ClearPartState) and the
                // dedup revision map must not retain an entry that could block
                // a future frame for the same partId.
                if (effectiveFrame.done || effectiveFrame.truncated) {
                    onPartDone(effectiveFrame.sessionId, effectiveFrame.messageId, effectiveFrame.partId)
                }
            }
            is TokenStreamFrame.MessagePartRemoved -> {
                val msgSeq = effectiveFrame.messageEventSeq ?: 0L
                onMessagePartRemoved(
                    effectiveFrame.sessionId,
                    effectiveFrame.messageId,
                    effectiveFrame.partId,
                    msgSeq,
                    commitContext,
                )
            }
            is TokenStreamFrame.MessageRemoved -> {
                onMessageRemoved(
                    effectiveFrame.sessionId,
                    effectiveFrame.messageId,
                    commitContext,
                )
            }
            is TokenStreamFrame.Resync -> {
                // B-4 HIGH-2 + MEDIUM: resync clears ALL parts for the session
                // (epoch boundary — stale revisions from the old connection
                // cannot block fresh frames on the new connection).
                effectiveFrame.sessionId?.let { clearSessionRevisions(it) }
            }
            else -> { /* no hook for other frame types */ }
        }

        // Bridge reducer state → ChatState for any frame that touches a part
        // (snapshot / delta). ServerConnected / Heartbeat / Resync carry no
        // single partId; resync's part-clearing is handled via the effect
        // path below (ClearPartState).
        val partId = when (effectiveFrame) {
            is TokenStreamFrame.PartSnapshot -> effectiveFrame.partId
            is TokenStreamFrame.PartDelta -> effectiveFrame.partId
            else -> null
        }
        // §B4 lifecycle token: use the value captured at open()/runStream()
        // entry and threaded verbatim through the call chain (rev-gpt C2) —
        // NOT a shared-field read (a new lifecycle's open()/reconnect could
        // overwrite a shared field mid-flight, letting a prior lifecycle's
        // late frame pick up the NEW token).
        if (partId != null) {
            bridgePartToChatState(sid, gen, partId, newState, capturedRouteInstance, boundBundle)
        }

        for (effect in effects) {
            handleEffect(
                sid,
                epoch,
                gen,
                effect,
                capturedRouteInstance,
                boundBundle,
                deferredEffects,
            )
        }
    }

    /**
     * Bridges a single partId's post-reduce state into ChatState via
     * [AppAction.TokenStreamPartUpdated]. Records ownership first (generation-
     * guarded) so the matching clear (later) passes the filter.
     */
    private fun bridgePartToChatState(
        sid: String,
        gen: Long,
        partId: String,
        state: TokenStreamReducerState,
        capturedRouteInstance: Long,
        boundBundle: ClientBundle,
    ) {
        // Entry fence (the dispatchEpochFrame lock already verified boundBundle
        // is current; currentBundleProvider() cannot change inside that lock).
        if (!isBundleCurrentForCommit(boundBundle)) return
        val acc = state.parts[partId] ?: return
        guard.onPartOwned(sid, gen, partId)
        val stamp = BundleStamp(boundBundle.generation, boundBundle.endpointFp)
        // §E2 PartPlaceholderEnsured from bridge: when a NEW partId arrives that is
        // NOT yet in partsByMessage[messageId], dispatch PartPlaceholderEnsured BEFORE
        // the TokenStreamPartUpdated, so MessageCard has a stable list key.
        val msgId = acc.messageId
        if (slices.chat.value.partsByMessage[msgId]?.none { it.id == partId } != false) {
            dispatchBound(
                boundBundle,
                AppAction.PartPlaceholderEnsured(
                    partType = "text",
                    partId = partId,
                    messageId = msgId,
                    sessionId = sid,
                    expectedRouteInstance = capturedRouteInstance,
                    bundleStamp = stamp,
                ),
            )
        }
        val owned = when (acc.state) {
            TokenPartStreamState.STREAMING -> StreamOwnedState.STREAMING
            TokenPartStreamState.DONE -> StreamOwnedState.DONE
        }
        dispatchBound(
            boundBundle,
            AppAction.TokenStreamPartUpdated(
                partId = partId,
                text = acc.text,
                state = owned,
                sessionId = sid,
                expectedRouteInstance = capturedRouteInstance,
                bundleStamp = stamp,
            ),
        )
    }

    /**
     * Translates one reducer effect into concrete side effects:
     *  - [TokenStreamCoordinatorEffect.ClearPartState] → generation-guard filter,
     *    then [AppAction.ClearTokenStreamState] (only the allowed subset).
     *  - [TokenStreamCoordinatorEffect.TriggerSinceFetch] → invoke the
     *    [triggerSinceFetch] callback verbatim (D2 wires it to the /since path).
     *  - [TokenStreamCoordinatorEffect.Reconnect] → schedule reconnect backoff.
     */
    private fun handleEffect(
        sid: String,
        @Suppress("UNUSED_PARAMETER") epoch: Long,
        gen: Long,
        effect: TokenStreamCoordinatorEffect,
        capturedRouteInstance: Long,
        boundBundle: ClientBundle,
        deferredEffects: MutableList<() -> Unit>,
    ) {
        // Entry fence (the dispatchEpochFrame lock already verified boundBundle).
        if (!isBundleCurrentForCommit(boundBundle)) return
        when (effect) {
            is TokenStreamCoordinatorEffect.ClearPartState -> {
                val allowed = guard.filterClearByGeneration(sid, gen, effect.partIds)
                if (allowed.isNotEmpty()) {
                    val stamp = BundleStamp(boundBundle.generation, boundBundle.endpointFp)
                    dispatchBound(
                        boundBundle,
                        AppAction.ClearTokenStreamState(
                            allowed,
                            expectedRouteInstance = capturedRouteInstance,
                            sessionId = sid,
                            bundleStamp = stamp,
                        ),
                    )
                }
            }
            is TokenStreamCoordinatorEffect.TriggerSinceFetch -> {
                // S2 (Stage-C should-fix): a resync frame may arrive with
                // sessionId == null (backpressure overflow omits it per the
                // handoff contract). Infer from the active connection's sid.
                val resolvedSid = effect.sessionId.takeIf { it.isNotBlank() } ?: sid
                deferredEffects += {
                    triggerSinceFetch(resolvedSid, effect.authoritative)
                }
            }
            is TokenStreamCoordinatorEffect.Reconnect -> {
                // §MF-1 (gate r1): do NOT call scheduleReconnect from here —
                // handleEffect runs synchronously INSIDE `flow.collect { }`
                // (called from dispatchEpochFrame). Calling scheduleReconnect
                // directly would supersede the currently-running job via
                // launchStreamLifecycle, causing a self-cancellation mid-frame.
                // Instead, set the sentinel; the collect lambda checks it
                // right after dispatchEpochFrame returns and throws
                // TokenStreamReconnectRequested to unwind the collector. The
                // run-loop's catch path (the SINGLE re-entry point) then
                // calls scheduleReconnect AFTER the old flow's EventSource is
                // torn down — guaranteeing no overlapping collectors.
                // §MF-1 (gate r2): UNCONDITIONALLY set the sentinel (was
                // compareAndSet(null, sid)). If a prior sid's stale sentinel
                // persists (cancelled-before-consumed), CAS-on-null would
                // fail to overwrite it, silently dropping THIS sid's
                // Reconnect. set(sid) always wins. The post-dispatch check
                // reads .get()==sid so the last Reconnect in the batch wins
                // (only one post-dispatch check runs per frame).
                requestReconnect(sid)
            }
        }
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    /** Removes reducer state for [sid] (stream teardown). */
    fun removeSid(sid: String) {
        reducerStateBySid.remove(sid)
    }
}
