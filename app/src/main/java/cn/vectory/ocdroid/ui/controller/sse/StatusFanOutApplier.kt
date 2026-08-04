package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.state.AuthorityState
import cn.vectory.ocdroid.data.state.AuthorityOp
import cn.vectory.ocdroid.data.repository.StatusOutcome
import cn.vectory.ocdroid.service.status.StatusFanOutSummary
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SkeletonReloadCoordinator
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.exponentialBackoffMs
import cn.vectory.ocdroid.service.streaming.ProcessStatusPoller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * **INVARIANT: all access must occur on Dispatchers.Main.immediate — this is a
 * thread-imprisonment contract, do not introduce concurrent primitives.**
 *
 * Applies a [StatusFanOutSummary] (produced by
 * [cn.vectory.ocdroid.service.status.SlimStatusFanOut]) to BOTH effect emissions
 * AND authority dispatches.
 *
 * Owns no long-lived state (the summary input is transient).
 */
internal class StatusFanOutApplier(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
    private val effects: SharedEffectBus,
    private val currentProfileId: () -> String,
    private val clock: () -> Long,
    private val skeletonReloadCoordinator: SkeletonReloadCoordinator?,
) {
    companion object {
        private const val TAG = "SessionSync"
    }

    /**
     * T13 — fold a slim on-demand fan-out summary into coordinator side effects.
     *
     * Two side-effect arms:
     *  - **[StatusFanOutSummary.missingSids]** → emit a delete-session effect
     *    per sid (mirrors session.updated archived + digest deleted branches).
     *  - **[StatusFanOutSummary.retryableCount]** → request the poller's bounded
     *    backoff when > 0; reset to base when == 0.
     *
     * See [SessionSyncCoordinator.applySlimStatusFanOutSummary] for full kdoc.
     */
    fun applySlimStatusFanOutSummary(summary: StatusFanOutSummary) {
        // §U-CQ5 sweep-start identity causal fence: DROP the entire summary
        // if the identity advanced between sweep-start and now.
        val currentEpoch = slices.store.stateFlow.value.identityEpoch
        if (summary.sweepStartEpoch != currentEpoch) {
            DebugLog.w(TAG, "applySlimStatusFanOutSummary: dropped stale summary " +
                "(sweep epoch=${summary.sweepStartEpoch}, current=$currentEpoch)")
            return
        }
        val fp = currentProfileId()
        // T13-C3: missingSids → delete-session effect per sid.
        // §critical-eviction-delivery: 关键驱逐不可丢弃 — 走可靠发送路径。
        for (sid in summary.missingSids) {
            closeSkeletonSession(sid)
            emitCriticalEffect(ControllerEffect.EvictSession(fp, sid))
        }
        // §P1-B/E retry-queue wire: fire (dequeue) previously-queued sids FIRST.
        val auth = currentAuthority()
        val scopeKey = slices.store.authorityScope()
        val now = clock()
        val capturedEpoch = slices.store.stateFlow.value.identityEpoch
        for (sid in summary.perSid.keys) {
            if (sid in auth.retryQueue) {
                slices.store.dispatch(
                    AppAction.AuthorityEvent(
                        AuthorityOp.RetryFired(
                            sid = sid,
                            scopeKey = scopeKey,
                            monotonic = now,
                            identityEpochAtCapture = capturedEpoch,
                        ),
                    ),
                )
            }
        }
        // T13-C4: retryableCount > 0 → request poller backoff; == 0 → reset.
        if (summary.retryableCount > 0) {
            for ((sid, outcome) in summary.perSid) {
                if (outcome !is StatusOutcome.Retry) continue
                val prevAttempt = auth.retryQueue[sid]?.attempt ?: 0
                val nominalBackoffMs = exponentialBackoffMs(
                    attempt = prevAttempt,
                    baseMs = ProcessStatusPoller.BACKOFF_BASE_MS,
                    maxShift = ProcessStatusPoller.BACKOFF_MAX_SHIFT,
                ).coerceAtMost(ProcessStatusPoller.BACKOFF_MAX_MS)
                slices.store.dispatch(
                    AppAction.AuthorityEvent(
                        AuthorityOp.RetryQueued(
                            sid = sid,
                            scopeKey = scopeKey,
                            attempt = prevAttempt + 1,
                            backoffMs = nominalBackoffMs,
                            queuedAtMs = now,
                            identityEpochAtCapture = capturedEpoch,
                        ),
                    ),
                )
            }
            effects.tryEmitEffect(ControllerEffect.RequestPollerBackoff)
        } else {
            effects.tryEmitEffect(ControllerEffect.ResetPollerBackoff)
        }
    }

    /** §P0-B ITEM 4: exposes the current authority state. */
    fun currentAuthority(): AuthorityState =
        slices.store.stateFlow.value.authority

    /** §U-CQ5 sweep-start epoch reader. */
    fun captureStoreIdentityEpoch(): Long = slices.store.captureIdentityEpoch()

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * §critical-eviction-delivery: best-effort reliable delivery for CRITICAL
     * effects whose loss would corrupt state.
     */
    private fun emitCriticalEffect(effect: ControllerEffect) {
        if (!effects.tryEmitEffect(effect)) {
            scope.launch { effects.emitEffect(effect) }
        }
    }

    /** L3: close skeleton session (detach state + cancel jobs). */
    private fun closeSkeletonSession(sessionId: String) {
        skeletonReloadCoordinator?.let { skel ->
            scope.launch { skel.onSessionClosed(sessionId) }
        }
    }
}
