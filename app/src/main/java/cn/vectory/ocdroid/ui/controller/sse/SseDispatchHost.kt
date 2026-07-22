package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.controller.SseSideEffect
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex

/**
 * T2 §3.1 + §host: the single point through which [SseEventHandler]s access
 * the [SessionSyncCoordinator]'s (SSC) capabilities. SSC implements this
 * interface; handlers receive the host at construction and call it for all
 * SSC-owned dependencies: slices, effects, scope, settings, repository,
 * and a few imperative side-effect / scheduling helpers.
 *
 * **Design rule**: the host MUST NOT expose any mutable state that would let
 * a handler commit a cross-slice write that bypasses [AppAction] dispatch.
 * Only [SliceFlows] (which is the reactive gateway) and the imperative
 * helpers below are exposed.
 */
interface SseDispatchHost {
    // ── Core SSC dependencies ─────────────────────────────────────────────────
    val slices: SliceFlows
    val effects: SharedEffectBus
    val settingsManager: SettingsManager
    val scope: CoroutineScope
    val repository: OpenCodeRepository?

    /**
     * CP4 (notify Phase-0): the authoritative status aggregator's INPUT
     * surface. The `session.status` SSE branch feeds it via
     * [StatusAggregatorInput.applySseStatus] BEFORE the unread/badge fold.
     * Null in test/legacy constructions that wire [handleEvent] directly.
     */
    val statusAggregatorInput: StatusAggregatorInput?

    // ── Helpers exposed from SSC ────────────────────────────────────────────

    /** Returns the current host's serverGroupFp (for keying eviction effects). */
    fun serverGroupFp(): String

    /**
     * Returns the per-sid stripe [Mutex] so handlers can serialize
     * competing writes for the same session id (e.g. session.error
     * + digest + reconcile). Mirrors [SessionSyncCoordinator.stripeFor].
     */
    fun stripeFor(sid: String): Mutex

    /**
     * Opens (or reopens) the [DELTA_COALESCE_MS] trailing-coalesce window
     * for [partId]. Mirrors [SessionSyncCoordinator.scheduleDeltaFlush].
     */
    fun scheduleDeltaFlush(partId: String)

    /**
     * Cancels [partId]'s pending flush and drops its buffers. Mirrors
     * [SessionSyncCoordinator.clearDeltaBuffers].
     */
    fun clearDeltaBuffers()

    /**
     * Translates a list of [SseSideEffect] into the matching bus/UI/log
     * calls. Mirrors [SessionSyncCoordinator.applySseSideEffects].
     */
    fun applySseSideEffects(sideEffects: List<SseSideEffect>)

    /**
     * Increments the per-type unknown-event counter. Called from the Router's
     * fallback (else) branch. Mirrors the internal
     * `unknownEventCounters.computeIfAbsent(type) { ... }.incrementAndGet()`.
     */
    fun bumpUnknownEventCounter(type: String)

    /**
     * The coordinator's clock (wall-clock millis; test-overridable). Used by
     * the `session.status` aggregator feed branch (for [StatusAggregatorInput]).
     */
    fun sseClock(): Long

    /**
     * Whether the current runtime is in slim mode. Used by the
     * `session.error` branch for the slim-only durable banner write.
     */
    fun slimMode(): Boolean

    /**
     * Checks whether a delta-flush job is currently active for [partId].
     * Mirrors the `flushJobs[partId]?.isActive == true` check in the
     * message.part.* branches.
     */
    fun isFlushActiveForPart(partId: String): Boolean

    /**
     * Processes a `session.digest` SSE event. Mirrors the private
     * [SessionSyncCoordinator.handleSessionDigest] method; the handler
     * delegates to this rather than inlining the ~135-line digest workflow.
     * Keeps the host as the single owner of the reconcile state machine.
     */
    fun handleSessionDigest(event: SSEEvent)
}
