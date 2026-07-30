package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.BundleStamp
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
     * Whether the session-error handler may persist its durable banner.
     * This is a semantic capability, not a transport-mode read.
     */
    fun supportsDurableSessionErrorBanner(): Boolean

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

    /** Dispatches a bundle-bound action with its stamp captured under the lock. */
    fun dispatchBundleBound(actionFactory: (BundleStamp) -> AppAction): Boolean

    /**
     * lite-v2-dev: marks a message as locally injected (eliminates timing window
     * between SSE shell injection and skeleton reload marking). The coordinator
     * forwards to [SkeletonReloadCoordinator.markLocallyInjected].
     * Order contract: MUST be called BEFORE dispatching the corresponding slice
     * update.
     */
    fun markLocallyInjected(sessionId: String, messageId: String)

    /**
     * L3 (blocker #1): closes a session in the skeleton reload scheduler —
     * detaches state, cancels + joins in-flight, and cleans up per-generation
     * resources. Fire-and-forget (launched on [scope]). Called from SSE event
     * handlers when a session is confirmed deleted or archived (not route switch).
     */
    fun closeSkeletonSession(sessionId: String)
}

/**
 * §P0-A (B1): the shared funnel for SSE-driven single-status writes
 * (slim `session.digest` status relay + legacy `session.status`). Replaces the
 * former `mutateSessionList { it.applySessionStatus(sid, status).first }`:
 *
 *  - the STATUS write funnels through the authority reducer
 *    (`dispatch(AuthorityEvent(ApplyEvent(origin)))`) so authority.bySid is the
 *    single source of truth and `sessionStatuses` is its projection;
 *  - §P0-A rev-gpt rework (abort-pending single-CAS): the `abortPendingSessionIds`
 *    release for a TERMINAL status (NOT busy AND NOT retry) now happens INSIDE
 *    `reduceAuthority`'s ApplyEvent branch (same state.copy that writes the
 *    status projection) — atomically. The old separate `mutateSessionList` CAS
 *    (② block) is DELETED: one SSE status frame = ONE CAS that atomically
 *    updates status + projection + abort-pending release (no torn window).
 *
 * [origin] is [cn.vectory.ocdroid.data.state.EntryOrigin.SSE_SLIM] for the
 * digest relay and [cn.vectory.ocdroid.data.state.EntryOrigin.SSE_LEGACY] for
 * the legacy session.status event. The reducer's ApplyEvent fence is lenient
 * for P0-A (no B11 identity guard yet); connectionMonotonicMs = the host's
 * SSE clock (TTL/tie-break, non-causal).
 */
internal fun SseDispatchHost.applyStatusViaAuthority(
    sid: String,
    status: cn.vectory.ocdroid.data.model.SessionStatus,
    origin: cn.vectory.ocdroid.data.state.EntryOrigin,
) {
    // §P0-A rev-gpt #3: resolve the session's workdir from the merged session
    // tree (sessions + directorySessions + childSessions) so the authority
    // entry carries the correct workdir for the composite key + coverage. The
    // old LSH code did this lookup before calling applySseStatus; now it's
    // consolidated here (single dispatch point).
    val workdir = cn.vectory.ocdroid.ui.controller.allSessionsById(
        slices.sessionList.value.sessions,
        slices.sessionList.value.directorySessions,
        slices.sessionList.value.childSessions,
    )[sid]?.directory
    // §P0-A rev-gpt rework: SINGLE dispatch — the authority reducer's ApplyEvent
    // branch releases abortPendingSessionIds for terminal statuses in the SAME
    // state.copy (atomic). No separate mutateSessionList CAS needed.
    slices.store.dispatch(
        cn.vectory.ocdroid.ui.AppAction.AuthorityEvent(
            cn.vectory.ocdroid.data.state.AuthorityOp.ApplyEvent(
                sid = sid,
                status = status,
                origin = origin,
                scopeKey = cn.vectory.ocdroid.data.state.ScopeKey(
                    serverGroupFp = serverGroupFp(),
                    endpointFp = "", // P0-C placeholder (lenient ApplyEvent guard).
                ),
                connectionMonotonicMs = sseClock(),
                workdir = workdir,
            ),
        ),
    )
}
