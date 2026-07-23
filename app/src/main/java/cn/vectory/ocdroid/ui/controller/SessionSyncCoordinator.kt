package cn.vectory.ocdroid.ui.controller

import android.util.Log
import androidx.annotation.VisibleForTesting
import cn.vectory.ocdroid.BuildConfig
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.api.NOISY_SSE_LOG_EVENTS
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.LastErrorField
import cn.vectory.ocdroid.data.model.SlimSessionDigest
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import cn.vectory.ocdroid.data.model.SlimapiQuestionEntry
import cn.vectory.ocdroid.data.model.SlimapiPermissionEntry
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ProbeResult
import cn.vectory.ocdroid.data.repository.SlimColdStartSnapshot
import cn.vectory.ocdroid.data.repository.SlimAggregationOutcome
import cn.vectory.ocdroid.data.repository.SlimSessionState
import cn.vectory.ocdroid.data.repository.catchUpSet
import cn.vectory.ocdroid.data.repository.needsCatchUp
import cn.vectory.ocdroid.data.repository.toPermissionRequest
import cn.vectory.ocdroid.service.events.IdentifiedSseEvent
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.status.SessionBusyStatus
import cn.vectory.ocdroid.service.status.SessionStatusKey
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.service.status.toSessionBusyStatus
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.MESSAGE_CHRONO
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.StreamOwnedState
import cn.vectory.ocdroid.ui.chronological
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.UnreadState
import cn.vectory.ocdroid.ui.computeQuestionFanOutWorkdirs
import cn.vectory.ocdroid.ui.lenientJson
import cn.vectory.ocdroid.ui.parseMessagePartDeltaEvent
import cn.vectory.ocdroid.ui.parseQuestionAskedEvent
import cn.vectory.ocdroid.ui.parseSessionCreatedEvent
import cn.vectory.ocdroid.ui.parseSessionStatusEvent
import cn.vectory.ocdroid.ui.parseSessionUpdatedEvent
import cn.vectory.ocdroid.ui.reasoningPartOrNull
import cn.vectory.ocdroid.ui.reportNonFatalIssue
import cn.vectory.ocdroid.ui.isStreamablePartType
import cn.vectory.ocdroid.ui.upsertSession
import cn.vectory.ocdroid.ui.withUpdatedAtLeast
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.FLICKER_TAG
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.ui.controller.sse.SseDispatchHost
import cn.vectory.ocdroid.ui.controller.sse.SseEventRouter
import cn.vectory.ocdroid.ui.controller.sse.SharedConversationSseHandler
import cn.vectory.ocdroid.ui.controller.sse.LegacySseHandler
import cn.vectory.ocdroid.ui.controller.sse.SlimSseHandler
import cn.vectory.ocdroid.util.STREAMING_FLICKER_DEBUG
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * R-16 M4 → R-17 batch3b → R-17 batch5: owns the SSE event → slice fold (the
 * SSE-trust dispatch model).
 *
 * **Migration (batch 3b)**: the [SessionSyncCoordinatorCallbacks] interface
 * was eliminated. The cross-domain signals (onServerConnected /
 * onRefreshMessages / onLoadPendingPermissions) emit [ControllerEffect]s on
 * [effects] (rule B). The non-fatal-issue logger was same-domain
 * ([cn.vectory.ocdroid.ui.reportNonFatalIssue] top-level helper) — inlined.
 *
 * **Moved from the orchestrator** (`handleSSEEvent` + the
 * `handleIncomingSseEvent` / `markSessionUnread` free functions): every
 * server-pushed message / session / status / part / permission / question /
 * todo event is folded in-place into the slice flows via
 * `slices.mutateChat { ... }` (patch-if-found + insert-if-absent for messages;
 * upsert for sessions; in-place map updates for statuses/todos/questions;
 * streaming overlay for parts). The side effects a fold can trigger
 * (authoritative reload, permission refresh, catch-up) flow through
 * [effects] — so the coordinator never touches the orchestrator, the
 * Repository, or any other controller directly (R-16 §7.3
 * circular-dependency avoidance).
 *
 * §R-17 batch5 (SSE semi-formalization): the per-partId delta coalescing
 * hidden state machine (`deltaBuffer` / `fullTextBuffer` / `pendingFlushPartIds`)
 * has been migrated INTO [ChatState] (immutable Map/Set, CAS updates). Only
 * the coroutine `Job` references ([flushJobs]) remain on the coordinator
 * (a Job is neither serializable nor a value type — it is bound to the
 * coordinator's [scope]). Each of the 11 event branches now calls a pure
 * `applyXxx(...)` extension function that takes the prior slice + event
 * payload and returns the new slice value; side effects (effect emits,
 * settingsManager writes, scope.launch) stay inline in the `when` branches
 * (effect-channel migration is a tracked followup — not in this batch).
 *
 * The coordinator holds NO streaming state of its own other than the
 * per-partId flush [flushJobs]: SSE events are stateless folds over the
 * shared slices, so a single instance follows the orchestrator lifetime and
 * is driven entirely through [handleEvent]. The `server.connected` catch-up
 * trigger is folded in here (one entry point for every event) and routed to
 * the foreground catch-up controller via [ControllerEffect.ServerConnected].
 *
 * §R-17 batch2 step e final: all state writes go through the per-slice
 * `MutableStateFlow.update` helpers (slices are the sole authoritative store).
 *
 * RFC reference: R-16 §B / §M4. Zero behaviour change — the dispatch body is a
 * verbatim move of the pre-extraction `handleIncomingSseEvent`, with the
 * buffer storage migrated to the slice and the per-branch state transforms
 * extracted as pure functions (R-17 batch5).
 */
/** G-F1 clock override for tests. */
@Volatile
private var clockOverride: (() -> Long)? = null

@Suppress("DEPRECATION")
class SessionSyncCoordinator(
    override val scope: CoroutineScope,
    override val slices: SliceFlows,
    override val settingsManager: SettingsManager,
    override val effects: SharedEffectBus,
    /** R-20 Phase 1: provider for the current host's serverGroupFp. Used to
     *  key the [ControllerEffect.EvictSession] emission on the session.updated
     *  archived branch (plan §3 矩阵 "SSE 归档 session" 行). */
    internal val currentServerGroupFp: () -> String,
    /**
     * CP1 (notify Phase-0): the single source of truth for the connection
     * epoch. Replaces the private [hostGeneration] AtomicLong — the epoch
     * now comes from [ConnectionIdentityStore.currentEpoch] (guarded by
     * [ConnectionIdentityStore.beginReconfigure] at the reconfigure barrier
     * origin in HostProfileController). FGS spec §2 «关键约束»: no second
     * private generation.
     *
     * The [handleEvent]/[handleEvent] identified overload validates
     * [ConnectionIdentityStore.isCurrent] BEFORE any fold/state mutation.
     */
    internal val identityStore: ConnectionIdentityStore? = null,
    /**
     * CP4 (notify Phase-0): the authoritative status aggregator's INPUT
     * surface. The `session.status` SSE branch feeds it via
     * [StatusAggregatorInput.applySseStatus] (keyed by `(serverGroupFp,
     * workdir, sessionId)`, sourced from the SSE arrival time) BEFORE the
     * existing unread/badge fold runs. Optional so legacy/test construction
     * (which drives [handleEvent] directly with raw SSEEvent) keeps working
     * — when null, the SSE branch simply skips the aggregator feed (no
     * behaviour change for tests that did not wire the aggregator).
     */
    override val statusAggregatorInput: StatusAggregatorInput? = null,
    /**
     * CP4 (notify Phase-0): the single clock used for SSE arrival timestamps
     * passed to [StatusAggregatorInput.applySseStatus]. Defaults to wall-clock
     * millis — the SAME clock domain as `StatusAggregatorImpl`'s injected
     * `clock`, so merge-timing comparisons inside the aggregator are
     * consistent. Test-only override (the existing tests do not assert on
     * arrival times; the default is fine).
     */
    internal val clock: () -> Long = { clockOverride?.invoke() ?: System.currentTimeMillis() },
    /**
     * Cluster A / Phase 2 (slim SSE): runtime slim-mode provider. Read on
     * every use (do NOT cache) so a host-profile switch that flips
     * [OpenCodeRepository.isSlimMode] is observed without reconstructing
     * this coordinator. Default `false` keeps legacy/test constructions
     * byte-identical.
     */
    private val isSlimMode: () -> Boolean = { false },
    /**
     * Cluster A / Phase 2 (slim SSE): repository used by the `session.digest`
     * branch ([applySlimDigest] + [OpenCodeRepository.getSlimapiMessagesSince])
     * and by [applySlimColdStartSnapshot] when the Service wires cold-start
     * folding through this coordinator. Optional so legacy/test constructions
     * that only drive pure folds keep working — when null, the digest branch
     * is a no-op (malformed / unparsed frames still count as handled so they
     * do not fall through to the unknown-event counter).
     *
     * **M1 choice**: inject the repository (rather than a ControllerEffect
     * hop) because [applySlimDigest] is a pure in-memory reducer on the
     * repository's [cn.vectory.ocdroid.data.repository.SlimSseState] and the
     * subsequent `/since` fetch must follow immediately. An effect hop would
     * race the next digest frame against an unadvanced bookmark.
     */
    override val repository: OpenCodeRepository? = null,
    /** Worker lane for network/reconcile computation. UI commits switch to Main. */
    internal val reconcileDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SseDispatchHost {
    /** Tag for [reportNonFatalIssue]; mirrors the original MainViewModel TAG. */
    private val tag: String = "SessionSyncCoordinator"

    /** T2: the SSE event router + domain handlers. Created at construction. */
    private val sseRouter: SseEventRouter = run {
        val shared = SharedConversationSseHandler(this)
        val legacy = LegacySseHandler(this)
        val slim = SlimSseHandler(this)
        SseEventRouter(shared, legacy, slim)
    }
    /**
     * §R-17 batch5: the ONLY coalesce state retained on the coordinator. The
     * Job references are bound to [scope] (a Job is neither serializable nor
     * a value type, so it cannot live in [ChatState]). The observable mirror
     * — which partIds have a pending flush — is [ChatState.pendingFlushPartIds]
     * in the slice; this map is the imperative side that drives
     * `delay(DELTA_COALESCE_MS) → flushDeltaBuffer(partId)`.
     *
     * The two views are kept in lock-step: a leading-edge write adds the
     * partId to [ChatState.pendingFlushPartIds] AND schedules a job here;
     * [flushDeltaBuffer] removes the partId from the slice AND removes the
     * job here; [clearDeltaBuffers] cancels every job here AND wipes the
     * slice's three coalesce fields.
     *
     * **Thread confinement**: Main-thread confined — all access runs on
     * appScope (Dispatchers.Main.immediate). If appScope ever changes to a
     * non-single-threaded dispatcher, this MUST become a ConcurrentHashMap
     * or use @Synchronized.
     */
    private val flushJobs = mutableMapOf<String, Job>()

    /**
     * Task 11 round-2 (oracle I5 — fixed striped locks): a fixed array of
     * 64 [Mutex] used to serialize competing per-sid reconcile triggers.
     * Replaces the round-1 keyed-map approach (which grew unbounded over
     * a long-lived client's session-id churn).
     *
     * # Why striped (oracle I5)
     *
     * A per-sid keyed map (`mutableMapOf<String, Mutex>`) grows without
     * bound: every session id the client ever observed (including
     * long-deleted ones from prior hosts) gets an entry that's never
     * removed. A fixed stripe array caps the memory at `STRIPES`
     * regardless of session-id churn.
     *
     * # Stripe selection
     *
     * `stripeFor(sid) = stripes[floorMod(sid.hashCode(), STRIPES)]`.
     * Different sids USUALLY land on different stripes → fully parallel;
     * two sids with the same `hashCode() mod 64` collide and serialize
     * (rare, benign — they proceed serially, both complete).
     *
     * # T11-C6 (per oracle D7 clarification)
     *
     * T11-C6 means **serialization of competing per-SID reconcile
     * triggers** (digest-driven + resync-driven + explicit session.error
     * reconcile if a future task adds it). It does NOT mean session.error
     * + digest atomic UI (per problem-report-wip.md C-D7). The stripe
     * lock guarantees no two concurrent reconcile bodies for the same sid
     * race the read-modify-write inside the repository's atomic boundary.
     *
     * # Thread confinement
     *
     * The array is initialized once at construction; reads are
     * plain (`stripes[i]`) — no synchronization needed for array element
     * reads after construction. The [Mutex] values are awaited under
     * [scope] (structured concurrency → cancellation propagates cleanly
     * on scope cancel).
     */
    private val reconcileStripes: Array<Mutex> = Array(STRIPES) { Mutex() }

    private fun stripeForImpl(sid: String): Mutex {
        // floorMod keeps the result non-negative for negative hashCode().
        val idx = ((sid.hashCode() % STRIPES) + STRIPES) % STRIPES
        return reconcileStripes[idx]
    }

    /**
     * Task 11: resync catch-up concurrency cap (§3 performance hint:
     * "可加客户端并发上限（如 4）"). Bounds the number of concurrent
     * [OpenCodeRepository.probeLatestSlim] + [getSlimapiMessagesSince]
     * fetches during a resync catch-up sweep so a 50-session catch-up set
     * does not stampede the sidecar. Pinned to 4 (matches the contract
     * hint + the expand-batch fallback cap in OpenCodeRepository).
     */
    private val resyncConcurrencySemaphore = Semaphore(4)

    /**
     * Task 11: default per-sid deadline for a single session's reconcile
     * during a resync catch-up sweep. Prevents one slow / hung session
     * from blocking the batch — [withTimeout] cancels the per-sid job and
     * the sweep moves on. The session's `dirty` is preserved (cancellation
     * throws CE out of the per-sid job before any state mutation lands).
     *
     * 8 seconds is the upper bound for a single probe + (focus) since-fetch
     * under normal sidecar load. Overridable per-call for tests.
     */
    private val defaultResyncPerSidDeadlineMs: Long = 8_000L

    /**
     * Task 11 (§3 / §4 reconcile lane): the outcome of a single
     * [reconcileSession] invocation. The coordinator's
     * [applyReconcileResult] branches on these to fold side effects
     * (chat-slice mutation, session-list eviction) that can't live inside
     * the repository's pure state-derive layer.
     *
     * Sealed so the [applyReconcileResult] `when` is exhaustive.
     */
    sealed class ReconcileResult {
        /** The session is aligned — local view matches the probe's view. */
        data class Aligned(val sid: String) : ReconcileResult()
        /** Focus/RESYNC REST fetch succeeded + items merged into chat. */
        data class Reconciled(val sid: String, val items: List<MessageWithParts>) : ReconcileResult()
        /** BACKGROUND catch-up needed; row refreshed, dirty PRESERVED. */
        data class RefreshRow(val sid: String) : ReconcileResult()
        /** Probe 404 → session gone upstream; drop from list. */
        data class MarkDeleted(val sid: String) : ReconcileResult()
        /** Probe empty + local had messages; local cache cleared. */
        data class ClearLocal(val sid: String) : ReconcileResult()
        /** Probe transport failure OR REST failure; dirty preserved. */
        data class Failure(val sid: String) : ReconcileResult()
        /** Per-sid deadline exceeded; dirty preserved. */
        data class TimedOut(val sid: String) : ReconcileResult()
        /** No repository wired; reconcile is a no-op. */
        data class NoRepository(val sid: String) : ReconcileResult()

        /**
         * C-D3 v2 §1.7: entry token became stale; no repo, slice, cache,
         * or effect commit landed. Stale ≠ Failure — it is a clean no-op
         * (no [markSlimReconcileFailure], no banner, no toast).
         */
        data class Stale(val sid: String) : ReconcileResult()
    }

    /**
     * Task 11 round-2 (oracle I4 — ReconcileMode enum): replaces the
     * round-1 `isFocus: Boolean` parameter on [reconcileSession]. Three
     * modes encode the three calling contexts, each with a different
     * branch matrix per the contract §3 + §4 + oracle's design.
     *
     * # Branch matrix (oracle I4)
     *
     * | Probe outcome                       | DIGEST_FOCUS         | DIGEST_BACKGROUND     | RESYNC               |
     * | ---                                 | ---                  | ---                   | ---                  |
     * | 404                                 | MarkDeleted          | MarkDeleted           | MarkDeleted          |
     * | Other failure                       | Failure (keep dirty) | Failure (keep dirty)  | Failure (keep dirty) |
     * | empty + local-has messages          | ClearLocal + clear   | no-op (keep dirty)    | ClearLocal + clear   |
     * | empty + local empty                 | Aligned (clear)      | no-op (keep dirty)    | Aligned (clear)      |
     * | aligned (probe says caught up)      | Aligned (clear)      | no-op (keep dirty)    | Aligned (clear)      |
     * | needs catch-up                      | REST fetch + clear   | RefreshRow (no clear) | REST fetch + clear   |
     * | REST success                        | clear-if-truly-aligned| n/a                  | clear-if-truly-aligned|
     * | REST failure                        | Failure (keep dirty) | n/a                   | Failure (keep dirty) |
     *
     * The matrix is the canonical spec — every branch in [reconcileSessionLocked]
     * MUST match this table.
     *
     * # Why three modes (oracle I4)
     *
     * Round-1 had only `isFocus: Boolean`. Two problems:
     *  1. **C3 fix:** BACKGROUND (non-focus digest) must NEVER clear dirty
     *     on aligned/empty (only focus + RESYNC may clear). The boolean
     *     couldn't express "RESYNC clears on aligned but BACKGROUND doesn't".
     *  2. **RESYNC fetch policy:** RESYNC always fetches on needsCatchUp
     *     (regardless of focus), but BACKGROUND never fetches. The boolean
     *     conflated "should fetch" with "is current tab".
     *
     * The enum separates the concerns: FOCUS/BACKGROUND select from
     * [handleSessionDigest] based on `sid == currentSessionId`; RESYNC is
     * passed by [performResyncCatchUp] / [performSlimResync] for every sid
     * in the catch-up set.
     */
    enum class ReconcileMode {
        /** Digest frame for the currently-open chat tab. May fetch + clear dirty. */
        DIGEST_FOCUS,
        /** Digest frame for a non-focus session. NEVER clears dirty; never fetches. */
        DIGEST_BACKGROUND,
        /** Resync sweep (every sid in the catch-up set). May fetch + clear dirty. */
        RESYNC,
    }

    private fun ReconcileMode.mayFetch() = this == ReconcileMode.DIGEST_FOCUS || this == ReconcileMode.RESYNC
    private fun ReconcileMode.mayClearDirty() = this == ReconcileMode.DIGEST_FOCUS || this == ReconcileMode.RESYNC

    /**
     * §R18 Phase 3 Wave 1 (P0-7): per-event-type counters for SSE events that
     * fell through the dispatch `when`'s else branch. Lets diagnostics (and
     * future tests) see which unknown types are recurring vs one-off. Only
     * incremented for NON-noisy types (noisy ones in [NOISY_SSE_LOG_EVENTS]
     * are known-intentional skips).
     */
    private val unknownEventCounters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /** Test/diagnostic read: snapshot of unknown-event counts by type. */
    internal fun unknownEventCountsSnapshot(): Map<String, Int> =
        unknownEventCounters.mapValues { it.value.get() }

    // ── §R-19 Sprint 1 Lane A (P1-10): SSE gap reconciliation overlay ────────

    /**
     * §P1-10: the SSE gap reconciliation overlay state. Drives the explicit
     * invariant ([SseSyncState] + [reconcileGap]) on top of
     * [ForegroundCatchUpController]'s 3-tier. See [SseSyncState] for the
     * overlay-vs-replacement rationale.
     *
     * `@Volatile` for forward-safety only — all reads/writes are confined to
     * the coordinator's single-threaded [scope] (Dispatchers.Main.immediate).
     */
    @Volatile
    private var sseSyncState: SseSyncState = SseSyncState()

    /**
     * §verbose-diag-flood: 1Hz coalesce window for the per-token SSE streaming
     * events (`message.part.delta` + `message.part.updated`) inside
     * [dispatchSseEvent]'s verbose SseDiag log. Both fire dozens–100s/sec
     * during AI output (per [NOISY_SSE_LOG_EVENTS] comment); logging each one
     * drowns the ring buffer. The coalesce emits ONE summary line per 1s
     * window per current session: `part.delta/updated ×N in window sid=…`.
     *
     * Thread discipline: same as [sseSyncState] — `@Volatile` for forward-
     * safety only; all reads/writes confined to the coordinator's single-
     * threaded [scope] (Dispatchers.Main.immediate). Mutated ONLY inside
     * [dispatchSseEvent]'s verbose diag block.
     */
    @Volatile
    private var verboseSseDeltaFirstAt: Long = 0L
    @Volatile
    private var verboseSseDeltaCount: Int = 0
    @Volatile
    private var verboseSseDeltaSid: String? = null

    /**
     * Flush + reset the verbose delta coalesce window (emit the summary line
     * if any deltas were buffered). Called on every non-delta event type so
     * the per-event log line ordering in the viewer stays chronological
     * relative to the coalesced delta summary; also called from inside the
     * 1Hz tick when a new window opens. No-op when nothing is buffered.
     */
    private fun flushVerboseSseDeltaWindow() {
        if (verboseSseDeltaCount > 0) {
            cn.vectory.ocdroid.util.DebugLog.d(
                "SseDiag",
                "part.delta/updated ×$verboseSseDeltaCount in window sid=$verboseSseDeltaSid",
            )
            verboseSseDeltaCount = 0
            verboseSseDeltaFirstAt = 0L
            verboseSseDeltaSid = null
        }
    }

    /**
     * CP1 (notify Phase-0): the generation counter has been REMOVED. The
     * private `hostGeneration: AtomicLong` that used to live here is gone —
     * it was a second generation that could drift apart from CC's
     * `directoryFetchGeneration` (which itself is now also gone, replaced by
     * [ConnectionIdentityStore]). The epoch is sourced from
     * [ConnectionIdentityStore.currentEpoch] via [currentEpoch].
     */

    /**
     * §P1-10: diagnostic + test hook — snapshot of the current overlay state.
     * Production callers should NOT branch on this (the decision logic lives
     * in [reconcileGap]); exposed for [SessionSyncGapReconcileTest] integration
     * cases and future debug surfaces.
     */
    internal fun sseSyncStateSnapshot(): SseSyncState = sseSyncState

    /**
     * CP1 (notify Phase-0): the current connection epoch, sourced from the
     * single [ConnectionIdentityStore]. Replaces the private [hostGeneration]
     * AtomicLong that was kept in lock-step with CC's generation via the
     * HostReconfigured effect. FGS spec §2 «关键约束»: no second private
     * generation — the epoch comes from the store.
     *
     * Returns 0 when no [identityStore] is wired (legacy/test construction)
     * so the pure [reconcileGap] function's generation guard still has a
     * stable value to compare against.
     */
    private fun currentEpoch(): Long = identityStore?.currentEpoch() ?: 0L

    /**
     * R-20 Phase 2 (G6): mark [sessionId] as having an established cold-snapshot
     * baseline. Called by [cn.vectory.ocdroid.ui.launchCatchUp]'s onColdSnapshot
     * callback on every successful catch-up. Future probe gating
     * (the inlined `shouldProbeCatchUp` helper in CatchUpActions.kt) treats a
     * session in this set + a live SSE feed for its workdir as covered (skip
     * the REST probe). Idempotent (set-add). Reset to empty by HostReconfigured.
     *
     * Confined to the coordinator's main-thread scope (matching sseSyncState's
     * write discipline); safe because launchCatchUp's onColdSnapshot fires from
     * a main-immediate coroutine.
     */
    internal fun markSessionColdSnapshotted(sessionId: String) {
        if (sessionId.isBlank()) return
        if (sessionId !in sseSyncState.sessionsEverColdSnapshotted) {
            sseSyncState = sseSyncState.copy(
                sessionsEverColdSnapshotted = sseSyncState.sessionsEverColdSnapshotted + sessionId
            )
        }
    }

    /**
     * G-F1 clock seam: set a non-null lambda to override the cadence's
     * wall-clock. Intended for tests that need to control "now" without
     * sleeping for 15 minutes. Default null keeps the real wall clock.
     */
    @VisibleForTesting
    internal var resyncClockMsForTest: (() -> Long)? = null
        set(value) { field = value; clockOverride = value }

    /**
     * G-F1: cadence state. [lastSuccessfulResyncAt] = epoch-ms of the most
     * recent successful resync sweep (used for 15-min interval). [resyncInFlight]
     * = true while a sweep is in-progress. [trailingQueued] = true if a
     * second coalesced trigger is waiting. [resyncHostGeneration] = the
     * generation this state machine is tracking (triggers with mismatched gen are
     * stale). [resyncDirty] = true when a trigger was suppressed due to interval;
     * the next eligible trigger will immediately launch.
     */
    private data class ResyncCadence(
        val lastSuccessfulResyncAt: Long = 0L,
        val resyncInFlight: Boolean = false,
        val trailingQueued: Boolean = false,
        val resyncHostGeneration: Long = 0L,
        val resyncDirty: Boolean = false,
    )

    /** G-F1: bounded re-sync cadence state machine. Atomic reference for thread-safe updates. */
    private val resyncCadence = AtomicReference(ResyncCadence())

    /**
     * G-F1: reset the cadence state for a new host generation. Clears any
     * in-flight / trailing / dirty state and sets the generation.
     */
    private fun resetCadenceForGeneration(gen: Long) {
        resyncCadence.set(ResyncCadence(resyncHostGeneration = gen))
    }

    /**
     * G-F1: bounded re-sync cadence guard. Call this (on the coordinator's
     * Main-immediate scope) before launching a resync sweep. Returns `true`
     * if the sweep should proceed (respecting interval, single-flight, trailing).
     *
     * When [isManual] is true, the 15-min interval is bypassed.
     * On stale generation (triggerGeneration != resyncCadence.resyncHostGeneration),
     * returns false (caller should drop).
     *
     * Side effects: updates [resyncCadence] (in-flight, dirty, trailing).
     * Does NOT actually launch the sweep — the caller must call
     * [performSlimResync] after receiving true, and call
     * [finishResyncCadence] afterward.
     */
    private fun maybeScheduleResync(
        triggerGeneration: Long,
        isManual: Boolean = false,
        bypassIntervalCheck: Boolean = false,
    ): Boolean {
        // CAS loop for atomic update of resyncCadence.
        while (true) {
            val cur = resyncCadence.get()
            if (triggerGeneration != cur.resyncHostGeneration) {
                DebugLog.d(tag, "maybeScheduleResync: stale generation $triggerGeneration != ${cur.resyncHostGeneration}")
                return false
            }
            val now = clock()
            val tooSoon = !isManual && !bypassIntervalCheck &&
                cur.lastSuccessfulResyncAt > 0L &&
                now - cur.lastSuccessfulResyncAt < 15 * 60 * 1000L
            if (tooSoon) {
                DebugLog.d(tag, "maybeScheduleResync: too soon (<15min), mark dirty")
                val next = cur.copy(resyncDirty = true)
                if (resyncCadence.compareAndSet(cur, next)) return false
                continue
            }
            if (cur.resyncInFlight) {
                if (!cur.trailingQueued) {
                    DebugLog.d(tag, "maybeScheduleResync: in-flight, queue trailing")
                    val next = cur.copy(trailingQueued = true)
                    if (resyncCadence.compareAndSet(cur, next)) return false
                    continue
                } else {
                    DebugLog.d(tag, "maybeScheduleResync: in-flight + already queued, skip")
                }
                return false
            }
            // Launch eligible; clear dirty and trailing, set in-flight.
            val next = cur.copy(
                resyncInFlight = true,
                resyncDirty = false,
                trailingQueued = false,
            )
            if (resyncCadence.compareAndSet(cur, next)) return true
            // CAS failed -> retry
            continue
        }
    }

    /**
     * G-F1: call AFTER [performSlimResync] completes (success or failure).
     * Updates [resyncCadence] based on outcome.
     */
    private fun finishResyncCadence(hadFailure: Boolean) {
        val now = clock()
        // CAS loop for atomic update.
        while (true) {
            val cur = resyncCadence.get()
            val next = cur.copy(
                resyncInFlight = false,
                lastSuccessfulResyncAt = if (hadFailure) cur.lastSuccessfulResyncAt else now,
            )
            if (resyncCadence.compareAndSet(cur, next)) break
        }
        // Check trailing: if a trailing was queued while in-flight, launch it now.
        val curAfter = resyncCadence.get()
        if (curAfter.trailingQueued) {
            val gen = curAfter.resyncHostGeneration
            // CAS clear trailingQueued.
            while (true) {
                val cur2 = resyncCadence.get()
                if (!cur2.trailingQueued) break
                val next2 = cur2.copy(trailingQueued = false)
                if (resyncCadence.compareAndSet(cur2, next2)) break
            }
            // Trailing runs with bypassIntervalCheck=true (was pre-approved when
            // queued). The internal guard inside performSlimResync is the SOLE
            // cadence authority — launching UNCONDITIONALLY here (NOT wrapping in
            // maybeScheduleResync) avoids the double-guard that re-set inFlight and
            // made the internal guard decline -> emptyMap -> finishResyncCadence
            // re-launch -> livelock (B1.5). inFlight was cleared above; trailingQueued
            // was cleared above; bypassIntervalCheck=true skips the 15-min interval.
            scope.launch {
                val outcomes = performSlimResync(bypassIntervalCheck = true)
                finishResyncCadence(
                    hadFailure = outcomes.any { (_, r) ->
                        r is ReconcileResult.Failure ||
                            r is ReconcileResult.TimedOut ||
                            r is ReconcileResult.Stale
                    }
                )
            }
        }
    }

    init {
        // §P1-10: observe the disconnect / host-reconfigure signals that the
        // SSE collector (ConnectionCoordinator) emits on the effects bus, so
        // the overlay state stays in lock-step without coupling the two
        // controllers. ServerConnected is NOT consumed here — it arrives via
        // [handleEvent] (the OnSseEvent path), which keeps the trigger's
        // `currentSessionId` snapshot co-temporal with the SSE frame.
        //
        // §start-UNDISPATCHED: the collector must be subscribed BEFORE the
        // first effect emission lands, otherwise the overlay misses the
        // initial CancelSse / HostReconfigured signals. UNDISPATCHED runs the
        // coroutine body inline until the first suspension (the SharedFlow
        // collect), guaranteeing the subscription is open by the time the init
        // block returns. Mirrors the test-harness pattern in
        // [SessionSyncCoordinatorTest.setUp].
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            effects.effects.collect { effect ->
                when (effect) {
                    is ControllerEffect.CancelSse -> {
                        // §P1-10 / CP9 §D22: an OBSERVED transport-disconnect
                        // signal. Producer (CP9): the Service's
                        // ServiceSseConnectionOwner emits this once when a
                        // live collector was actually stopped (Service going
                        // away, user explicit close, reconfigure teardown,
                        // §4.1 timeout) — NOT FCC/CC cancelling a job (CC
                        // no longer owns a job). The current session's slice
                        // is now potentially stale (the user was watching
                        // it). Mark dirty + stamp the disconnect time so the
                        // next `server.connected` reconciles.
                        val gen = currentEpoch()
                        val now = System.currentTimeMillis()
                        val dirty = listOfNotNull(slices.chat.value.currentSessionId).toSet()
                        val trigger = SseReconnectTrigger.Disconnected(now, dirty, gen)
                        sseSyncState = reconcileGap(sseSyncState, trigger).first
                    }
                    is ControllerEffect.HostReconfigured -> {
                        // §P1-10 scenario 4 + CP1: the epoch carried on the
                        // effect IS the new generation (bumped synchronously
                        // by ConnectionIdentityStore.beginReconfigure at the
                        // HostProfileController barrier origin). Reset the
                        // overlay to a fresh cold-start under this epoch so
                        // any in-flight ServerConnected from the PREVIOUS
                        // host's cancelled SSE job becomes a stale-trigger
                        // no-op.
                        val trigger = SseReconnectTrigger.HostReconfigured(effect.epoch)
                        sseSyncState = reconcileGap(sseSyncState, trigger).first

                        // G-F1 cadence reset + schedule on host reconfigured.
                        val gen = effect.epoch
                        resetCadenceForGeneration(gen)
                        // Internal guard in performSlimResync handles cadence.
                        scope.launch { performSlimResync(isManual = false) }
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * CP1 (notify Phase-0) identity-checked entry point. Validates
     * [identified.identity] against [ConnectionIdentityStore.isCurrent]
     * BEFORE any fold/state mutation — a stale-identity frame (captured under
     * a pre-reconfigure epoch) is dropped silently here so it cannot pollute
     * the new host's state. This is the production path:
     * ConnectionCoordinator.launchSseCollection captures the identity at
     * collection start, wraps each event as [IdentifiedSseEvent], and emits
     * [ControllerEffect.OnSseEvent]; AppCore.dispatchConnectionEffect routes
     * it here.
     *
     * FGS spec §1 «identity 不得在 fold 前被剥掉»: the identity is carried on
     * the event container (NOT stripped at the bridge) so this second-stage
     * validation is possible without trusting the bridge alone.
     *
     * When no [identityStore] is wired (legacy/test construction that bypasses
     * the store), the frame is dispatched WITHOUT the identity gate (backward
     * compat for tests that drive [handleEvent] directly with raw SSEEvent).
     */
    fun handleEvent(identified: IdentifiedSseEvent) {
        val store = identityStore
        if (store != null && !store.isCurrent(identified.identity)) {
            // Drop stale-identity frame BEFORE any side effect. Keep the
            // existing stale-host logging pattern (DebugLog.i, not Log.w —
            // this is an expected race window during reconfigure, not a bug).
            DebugLog.i(
                "Sync",
                "drop stale-identity SSE event " +
                    "(epoch=${identified.identity.epoch} current=${store.currentEpoch()} " +
                    "type=${identified.event.payload.type})"
            )
            return
        }
        handleEvent(identified.event)
    }

    /**
     * §P1-10 entry point for every SSE event. Mirrors the pre-extraction
     * `MainViewModel.handleSSEEvent`: first the `server.connected` catch-up
     * trigger, then the [dispatchSseEvent] fold.
     *
     * §Phase1E: every (re)connect's first frame is `server.connected`. Catch-up
     * runs on every connect EXCEPT the very first process-time connect (cold
     * start has no local history). The three-tier suppress /
     * sseHasConnectedOnce state machine lives in [ForegroundCatchUpController]
     * (R-16 M1); it calls back into the catch-up probe via
     * [ForegroundCatchUpCallbacks] when a probe is actually warranted.
     *
     * CP1: the generation value for the reconcile trigger now comes from
     * [ConnectionIdentityStore.currentEpoch] (via [currentEpoch]) instead of
     * the removed private AtomicLong — single epoch source, FGS spec §2.
     */
    fun handleEvent(event: SSEEvent) {
        if (event.payload.type == "server.connected") {
            // §R18 Phase 3 Wave 1 (P1-3 B 类): 单发非 suspend → tryEmitEffect。
            effects.tryEmitEffect(ControllerEffect.ServerConnected)
            // §R-19 Sprint 1 Lane A (P1-10): consult the SSE gap reconciliation
            // overlay BEFORE ForegroundCatchUpController's catch-up runs (the
            // ServerConnected effect above is collected asynchronously by
            // AppCore's dispatch loop). The reconciler is a pure decision
            // function: it returns the new overlay state + a list of decisions
            // that we translate into effects here.
            //
            // currentSessionId is captured at event-arrival time so scenario 3
            // (user switched sessions mid-disconnect) targets the NEW session.
            val currentSessionId = slices.chat.value.currentSessionId
            val gen = currentEpoch()
            val trigger = SseReconnectTrigger.ServerConnected(currentSessionId, gen)
            val connectedOnceBefore = sseSyncState.connectedOnce
            val (nextState, decisions) = reconcileGap(sseSyncState, trigger)
            sseSyncState = nextState
            applySseSyncDecisions(decisions)

            // G-F1 cadence reset + schedule on server connected.
            resetCadenceForGeneration(gen)
            // Only launch performSlimResync on RE-connect (not cold-start first connect).
            // The initial cold-start snapshot is already handled by the normal
            // reconcileSession path; triggering a superfluous resync here would
            // cause extra HTTP requests that break the golden-path test expectations.
            if (connectedOnceBefore) {
                scope.launch { performSlimResync(isManual = false) }
            }
        }
        dispatchSseEvent(event)
    }

    /**
     * §R-19 Sprint 1 Lane A (P1-10): translates [SseSyncDecision]s returned by
     * [reconcileGap] into concrete side effects. Kept tiny + inline so the
     * pure decision function stays the single unit-testable surface.
     *
     *  - [SseSyncDecision.ReloadSession]    → `LoadMessages` effect
     *    (single-shot, non-suspend → tryEmitEffect to preserve FIFO order
     *    relative to the ServerConnected emit above).
     *  - [SseSyncDecision.LoadSessionStatus] → `LoadSessionStatus` effect
     *    (the AppCore handler performs the repository-backed status reload).
     *  - [SseSyncDecision.RefreshSessions]  → `LoadSessions` effect (the
     *    non-current dirty session's list-level state is refreshed).
     *  - [SseSyncDecision.ClearDeltaBuffers] → local [clearDeltaBuffers] call.
     */
    private fun applySseSyncDecisions(decisions: List<SseSyncDecision>) {
        if (decisions.isEmpty()) return
        for (decision in decisions) {
            when (decision) {
                is SseSyncDecision.ReloadSession -> {
                    // §R18 Phase 3 Wave 1 (P1-3 B 类): 单发非 suspend → tryEmitEffect。
                    effects.tryEmitEffect(
                        ControllerEffect.LoadMessages(decision.sessionId, decision.resetLimit)
                    )
                }
                SseSyncDecision.LoadSessionStatus -> {
                    effects.tryEmitEffect(ControllerEffect.LoadSessionStatus)
                }
                SseSyncDecision.RefreshSessions -> {
                    // §R-19 P1-10: RefreshSessions decision maps to the
                    // existing LoadSessions effect (refreshes the session
                    // list — used when a non-current session is dirty and we
                    // don't want a per-session windowed reload).
                    effects.tryEmitEffect(ControllerEffect.LoadSessions)
                }
                SseSyncDecision.ClearDeltaBuffers -> {
                    clearDeltaBuffers()
                }
            }
        }
    }

    /**
     * §R-19 Sprint 3 P2-4: the single side-effect routing point. Translates
     * each [SseSideEffect] into its matching [ControllerEffect] emit /
     * [UiEvent] emit / log call. Called by every `dispatchSseEvent` branch
     * with the combined effects list (applyXxx-returned + dispatcher-computed
     * cross-slice effects).
     *
     * **Why centralized**: P2-4's goal is to kill the scattered
     * `effects.tryEmitEffect(...)` / `effects.tryEmitUiEvent(...)` calls that
     * were sprinkled inline in the 11 `when` branches. Every bus-level side
     * effect now flows through this single helper, making the dispatcher's
     * effect sequencing auditable and the pure applyXxx functions testable
     * in isolation (they return the effects list; the dispatcher commits it).
     */
    private fun applySseSideEffectsImpl(sideEffects: List<SseSideEffect>) {
        if (sideEffects.isEmpty()) return
        for (effect in sideEffects) {
            when (effect) {
                is SseSideEffect.ReloadMessages -> {
                    // §R18 Phase 3 Wave 1 (P1-3 B 类): 单发非 suspend → tryEmitEffect。
                    effects.tryEmitEffect(
                        ControllerEffect.LoadMessages(effect.sessionId, effect.resetLimit)
                    )
                }
                SseSideEffect.LoadPendingPermissions -> {
                    // §R18 Phase 3 Wave 1 (P1-3 B 类): 单发非 suspend → tryEmitEffect。
                    effects.tryEmitEffect(ControllerEffect.LoadPendingPermissions)
                }
                is SseSideEffect.SessionError -> {
                    // §R18 Phase 2-G: pick the format by whether `name` is present
                    // so the rendered text matches the prior "$name: $rawMsg" /
                    // "$rawMsg" shape exactly (only the prefix label is i18n'd;
                    // name + rawMsg come from the server payload).
                    if (!effect.name.isNullOrBlank()) {
                        effects.tryEmitUiEvent(
                            UiEvent.Error(R.string.error_session_sse_named, listOf(effect.name, effect.rawMsg))
                        )
                    } else {
                        effects.tryEmitUiEvent(
                            UiEvent.Error(R.string.error_session_sse_unnamed, listOf(effect.rawMsg))
                        )
                    }
                }
                is SseSideEffect.ReportNonFatal -> {
                    reportNonFatalIssue(tag, effect.message)
                }
            }
        }
    }

    /**
     * Dispatches a single SSE event. Per the SSE-trust model (mirrors opencode-web):
     *
     * - `message.updated` for the current session does NOT reload — live text comes
     *   via `streamingPartTexts` (populated by `message.part.updated` delta/full
     *   text). Structural sync is handled in-place: an existing message is patched,
     *   and a NEW message (absent from the local list) is INSERTED (server 1.17.11+
     *   emits `message.updated`, not `message.created`, for new messages; the oc-ref
     *   web client does the same patch-if-found + insert-if-absent).
     * - `message.part.updated` with empty delta but non-null ids (a part status
     *   flip) does NOT clear streaming buffers or reload. Only a true `part.created`
     *   (ids null) wipes the streaming state and reloads.
     * - `session.status` transitions only update the `sessionStatuses` map (busy/idle
     *   badge). They do NOT reload or clear streaming buffers — the finalized turn
     *   text is carried by `streamingPartTexts` until a foreground catch-up
     *   reconciles the persisted message list. (A busy transition on the CURRENT
     *   session also triggers a debounced reload as the cross-client-sync fallback.)
     * - There is no watchdog/idle-reload: a silently-stalled SSE feed recovers via
     *   connection-level retry, a foreground transition (SSE restart), or the next
     *   user action — matching opencode-web.
     *
     * §R-17 batch5: each `when` branch now calls a pure `applyXxx` extension
     * function for the state transform. Side effects (effect emits, settings
     * writes, scheduling) stay inline.
     */
    private fun dispatchSseEvent(event: SSEEvent) {
        // §streaming-state-sync-diag (runtime-gated, scope+coalesce): cut the
        // verbose SSE flood to a signal-rich ~5–20/sec during a send:
        //  - SCOPE to the current (open) session — non-current events are not
        //    rendered and carry no streaming-send signal. Rare sid-less frames
        //    (server.connected, plugin.added, etc.) pass through.
        //  - COALESCE `message.part.delta` + `message.part.updated` (the per-
        //    token streaming events per NOISY_SSE_LOG_EVENTS) into ONE summary
        //    line per 1s window per current session. The render path is the
        //    proof of delivery; the verbose log only needs to confirm
        //    "streaming IS happening for sid X" at low frequency.
        // All other event types (session.status, session.digest,
        // message.completed/updated, permission/question, unknown): log each
        // normally — low volume, high signal. The 1Hz delta window is flushed
        // before any such log so the viewer's chronological order is preserved.
        if (cn.vectory.ocdroid.util.DebugLog.verboseDiagEnabled) {
            val t = event.payload.type
            val evtSid = event.payload.getString("sessionID")
            val currentSid = slices.chat.value.currentSessionId
            val sidMatches = evtSid == null || evtSid == currentSid
            when {
                (t == "message.part.delta" || t == "message.part.updated") &&
                    evtSid != null && evtSid == currentSid -> {
                    val now = System.currentTimeMillis()
                    if (verboseSseDeltaCount == 0 || now - verboseSseDeltaFirstAt >= 1000L) {
                        flushVerboseSseDeltaWindow()
                        verboseSseDeltaFirstAt = now
                        verboseSseDeltaCount = 1
                        verboseSseDeltaSid = currentSid
                    } else {
                        verboseSseDeltaCount++
                    }
                }
                sidMatches -> {
                    flushVerboseSseDeltaWindow()
                    val props = event.payload.properties
                    val extra = if (t == "session.digest" && props != null) {
                        val obj = props as? kotlinx.serialization.json.JsonObject
                        val sid = obj?.get("sessionID")?.toString()?.trim('"')
                        val st = obj?.get("status")?.toString()?.trim('"')
                        val ua = obj?.get("updatedAt")
                        val mid = obj?.get("messageID")
                        " sid=$sid status=$st updatedAt=$ua messageId=$mid"
                    } else ""
                    cn.vectory.ocdroid.util.DebugLog.d("SseDiag", "frame type=$t$extra")
                }
            }
        }
        // Throttle dispatch logging to preserve the 1000-entry ring buffer's signal.
        val type = event.payload.type
        val evtSession = event.payload.getString("sessionID") ?: "-"
        val noisy = type in NOISY_SSE_LOG_EVENTS
        if (!noisy) {
            DebugLog.d("Sync", "dispatch $type session=$evtSession current=${slices.chat.value.currentSessionId}")
        }
        // Route through the T2 router
        sseRouter.route(event, this)
    }

    // ── §M5 delta coalescing helpers ────────────────

    /**
     * Opens (or reopens) the [DELTA_COALESCE_MS] trailing-coalesce window for
     * [partId]. Scheduled on the leading-edge delta; while the launched job is
     * alive, subsequent deltas append to [ChatState.deltaBuffer] instead of
     * writing streamingPartTexts. The Job reference is held in [flushJobs]
     * (NOT in the slice — a Job is not a value type); the observable mirror is
     * [ChatState.pendingFlushPartIds], set by [ChatState.markFlushPending] on
     * the leading edge and cleared by [flushDeltaBuffer] / [clearDeltaBuffers].
     */
    private fun scheduleDeltaFlushImpl(partId: String) {
        // Defensive: a stale/completed entry should never coexist with a leading
        // edge (the window self-clears on flush), but cancel anyway to avoid
        // ever having two flush jobs racing for one partId.
        flushJobs[partId]?.cancel()
        flushJobs[partId] = scope.launch {
            delay(DELTA_COALESCE_MS)
            flushDeltaBuffer(partId)
        }
    }

    /**
     * Flushes [partId]'s buffered deltas/fullText into the chat slice's
     * streamingPartTexts in a single atomic write (TOCTOU-safe). Self-removes
     * the partId from [ChatState.pendingFlushPartIds] and from [flushJobs].
     * If the overlay was cleared mid-window (session switch / part.created /
     * ViewModel reset wiped streamingPartTexts), the buffer is dropped —
     * re-injecting stale tokens into the new view would render ghost text from
     * the previous session.
     *
     * §Site1-coalesce: a buffered fullText wins (REPLACE) — it is the server's
     * authoritative accumulated text and supersedes any concurrent delta
     * accumulation for this partId. It is checked BEFORE the delta; if
     * present, streamingPartTexts[partId] is overwritten with the buffered
     * value (REPLACE, not append) and the entry cleared. Only when no fullText
     * is buffered does the delta APPEND path run.
     *
     * §R-17 batch5: the state transform is the pure
     * [ChatState.flushCoalesceBufferForPart] extension; this wrapper only
     * owns the [flushJobs] entry (Job lifecycle, scope-bound — not in the
     * slice).
     */
    private fun flushDeltaBuffer(partId: String) {
        flushJobs.remove(partId)
        // T1b: flushJobs lifecycle stays here; state transform via dispatch.
        slices.store.dispatch(cn.vectory.ocdroid.ui.AppAction.CoalesceFlushedForPart(partId))
    }

    /**
     * Cancels [partId]'s pending flush and drops its buffers (both delta APPEND
     * and fullText REPLACE) in the slice. Kept for callers that need to
     * supersede the streaming accumulation for a partId with an authoritative
     * snapshot outside the coalesce path; the §Site1-coalesce fullText branch
     * no longer calls this (it lets the leading-edge + trailing pattern handle
     * it), but the helper is retained for correctness/future use.
     */
    @Suppress("unused")
    private fun cancelDeltaFlush(partId: String) {
        flushJobs.remove(partId)?.cancel()
        // T1b: flushJobs cancel stays here; buffer drop via dispatch.
        slices.store.dispatch(cn.vectory.ocdroid.ui.AppAction.CoalesceClearedForPart(partId))
    }

    /**
     * Drops ALL pending delta/fullText buffers, cancels their flush jobs, and
     * clears [ChatState.pendingFlushPartIds]. Called when the whole streaming
     * overlay is wiped (part.created now; session switch / SSE stop /
     * ViewModel clear may be wired by the caller — see §4.2). Safe to call
     * repeatedly.
     */
    override fun clearDeltaBuffers() {
        flushJobs.values.forEach { it.cancel() }
        flushJobs.clear()
        // T1b: Job cancel stays here; coalesce buffer clear via dispatch.
        slices.store.dispatch(cn.vectory.ocdroid.ui.AppAction.CoalesceBuffersCleared)
    }

    // ── SseDispatchHost implementation ──────────────────────────────────────
    override fun serverGroupFp(): String = currentServerGroupFp()
    override fun stripeFor(sid: String): Mutex = stripeForImpl(sid)
    override fun scheduleDeltaFlush(partId: String) { scheduleDeltaFlushImpl(partId) }
    override fun applySseSideEffects(sideEffects: List<SseSideEffect>) { applySseSideEffectsImpl(sideEffects) }
    override fun bumpUnknownEventCounter(type: String) {
        unknownEventCounters
            .computeIfAbsent(type) { java.util.concurrent.atomic.AtomicInteger(0) }
            .incrementAndGet()
    }
    override fun sseClock(): Long = clock()
    override fun slimMode(): Boolean = isSlimMode()
    override fun isFlushActiveForPart(partId: String): Boolean = flushJobs[partId]?.isActive == true
    override fun handleSessionDigest(event: SSEEvent) { handleSessionDigestImpl(event) }

    // ── §R18 Phase 3 Wave 1 (P1-9): multi-workdir pending questions fan-out ──

    /**
     * §P1-9: refreshes pending questions across EVERY known workdir (the in-
     * memory `directorySessions` keys + `settingsManager.currentWorkdir`),
     * not just `currentWorkdir`. The single-workdir AppCore dispatch handler
     * for `LoadPendingQuestions` reads only `currentWorkdir`, so a
     * `question.asked` SSE event for any OTHER workdir is fetched-then-
     * immediately-overwritten by the next currentWorkdir poll — background
     * workdirs' questions silently vanish.
     *
     * The coordinator already owns [slices] (so it can read `directorySessions`)
     * + [settingsManager] (currentWorkdir) + [scope]. The repository is passed
     * in because the batch 3b migration left this controller without it
     * (callers fan out via [ControllerEffect]s); AppCore (out of this wave's
     * write scope) will need a one-line wiring update to call this method.
     *
     * Merge semantics: byGet wins, pre-existing fills gaps (mirrors
     * `launchLoadPendingQuestions` so the per-workdir fan-out composes without
     * dropping prior SSE-delivered questions).
     *
     * §scope-note: AppCore needs to call this from its catch-up / switch paths
     * to wire production. The method is exercised directly by
     * [SessionSyncCoordinatorTest].
     */
    fun loadPendingQuestionsAllWorkdirs(repository: OpenCodeRepository) {
        // Cluster A / Phase 2 (P2.3): slim mode aggregates cross-directory
        // pending questions in ONE `/slimapi/questions` call (routeToken
        // preserved). Legacy keeps the multi-workdir fan-out below.
        if (isSlimMode()) {
            loadPendingQuestionsSlim(repository)
            return
        }
        // §issue-1 Phase 2a Fix B: shared workdir-set computation (with per-fp
        // recent_workdirs) — identical to AppCore's catchUpWorkdirs site, via
        // the [computeQuestionFanOutWorkdirs] helper, so the two sites cannot drift.
        val workdirs = computeQuestionFanOutWorkdirs(
            directorySessionKeys = slices.sessionList.value.directorySessions.keys,
            currentWorkdir = settingsManager.currentWorkdir,
            recentWorkdirs = settingsManager.getRecentWorkdirs(currentServerGroupFp()),
        )
        // §Phase1a instrumentation (Issue 1): the full workdir SET being fanned out.
        DebugLog.d("Question", "loadPendingQuestionsAllWorkdirs fanOut=${workdirs.size} workdirs=$workdirs")
        if (workdirs.isEmpty()) return
        // §badge-stale-fix: fan out to EVERY known workdir in parallel, then
        // reconcile AUTHORITATIVELY (server is source of truth). Unlike the
        // single-workdir optimistic path [launchLoadPendingQuestions] — which
        // keeps locally-held questions to avoid flicker — this sweep covers the
        // full known-workdir set at once, so its union is authoritative. A
        // question the server no longer returns (resolved without the client
        // receiving the resolve event, e.g. a missed SSE gap while backgrounded)
        // is dropped here instead of lingering as a ghost that keeps the
        // Sessions nav badge lit forever. Matches launchLoadPendingPermissions
        // (full replace) semantics.
        //
        // Race-safety: a question.asked SSE event that lands DURING the fan-out
        // (after the start snapshot, not yet in any in-flight GET response) is
        // preserved — only questions present at start AND absent from the server
        // response are treated as resolved-and-dropped.
        scope.launch {
            val startIds = slices.sessionList.value.pendingQuestions
                .mapTo(mutableSetOf()) { it.id }
            val fetched = workdirs.map { dir ->
                async {
                    repository.getPendingQuestions(dir)
                        .onSuccess { questions ->
                            DebugLog.d("Question", "loadPendingQuestionsAllWorkdirs dir=$dir count=${questions.size}")
                        }
                        .onFailure { error ->
                            DebugLog.w(tag, "fan-out getPendingQuestions failed for $dir: ${error.message}")
                        }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
            val fetchedIds = mutableSetOf<String>()
            // §task5-ghost (final-review fix 2): snapshot the three-source
            // sessions map BEFORE the merge so the filter can identify
            // archived-session questions. A question whose session is marked
            // archived in the local snapshot is dropped here even if the server
            // still returns it — the archive reducer already cleared it from
            // the presentation domain, and letting it back in would relight
            // the Sessions nav badge for a session the user cannot open.
            val slSnap = slices.sessionList.value
            val sessionsById = allSessionsById(
                slSnap.sessions,
                slSnap.directorySessions,
                slSnap.childSessions,
            )
            val authoritative = buildList {
                fetched.flatten().forEach { if (fetchedIds.add(it.id)) add(it) }
                slSnap.pendingQuestions.forEach { q ->
                    if (q.id !in fetchedIds && q.id !in startIds) add(q)
                }
            }.let { filterArchivedSessionQuestions(it, sessionsById) }
            slices.mutateSessionList { it.copy(pendingQuestions = authoritative) }
            DebugLog.d("Question", "loadPendingQuestionsAllWorkdirs authoritative reconcile total=${authoritative.size} (had ${startIds.size} before)")
        }
    }

    /**
     * Cluster A / Phase 2 (P2.3): slim single-shot pending-questions load via
     * [OpenCodeRepository.getSlimapiQuestions]. Maps each entry to legacy
     * [QuestionRequest] **preserving [QuestionRequest.routeToken]**. Same
     * authoritative reconcile + archived-session filter as the legacy fan-out.
     *
     * C-D3 v2 §2.2: standalone workflow entry — captures ONE token before
     * the network suspend, then guards every slice / signal / effect
     * commit inside a single `commitIfSlimTokenCurrent` block. A stale
     * result is a clean no-op (no slice mutation, no UiEvent).
     */
    private fun loadPendingQuestionsSlim(repository: OpenCodeRepository) {
        DebugLog.d("Question", "loadPendingQuestionsAllWorkdirs slim single-shot")
        scope.launch {
            // Standalone workflow entry: ONE capture before first suspend.
            val token = repository.captureSlimCommitToken()

            val startIds = slices.sessionList.value.pendingQuestions
                .mapTo(mutableSetOf()) { it.id }

            val workdirs = computeQuestionFanOutWorkdirs(
                directorySessionKeys = slices.sessionList.value.directorySessions.keys,
                currentWorkdir = settingsManager.currentWorkdir,
                recentWorkdirs = settingsManager.getRecentWorkdirs(currentServerGroupFp()),
            )

            val directories = workdirs.takeIf { it.isNotEmpty() }?.toList()

            val outcome = repository.getSlimapiQuestions(
                directories = directories,
                token = token,
            ).getOrElse { error ->
                if (error is OpenCodeRepository.StaleSlimCommitException) {
                    return@launch
                }
                SlimAggregationOutcome.Failure(error.message)
            }

            // Fast rejection after suspension but before building work.
            if (!repository.isSlimCommitTokenCurrent(token)) return@launch

            // C-D3 v2 §2.2: ALL slice + effect commits land inside ONE
            // commitIfSlimTokenCurrent atomic gate so a host rotation
            // between the network return and the slice commit can NOT
            // write a stale question list / signal under a new host.
            repository.commitIfSlimTokenCurrent(token) {
                val signal = aggregationSignal(outcome)

                slices.mutateSessionList { current ->
                    val sessionsById = allSessionsById(
                        current.sessions,
                        current.directorySessions,
                        current.childSessions,
                    )

                    val folded = applyAggregationOutcome(
                        prior = current.pendingQuestions,
                        outcome = outcome,
                        wireToUi = SlimapiQuestionEntry::toQuestionRequest,
                        uiId = QuestionRequest::id,
                        uiDirectory = QuestionRequest::directory,
                    )

                    // Preserve an SSE arrival that occurred during this poll.
                    val foldedIds = folded.items.mapTo(mutableSetOf()) { it.id }
                    val raceArrivals = current.pendingQuestions.filter { question ->
                        question.id !in startIds &&
                            question.id !in foldedIds
                    }

                    val merged = (folded.items + raceArrivals)
                        .distinctBy { it.id }
                        .let {
                            filterArchivedSessionQuestions(it, sessionsById)
                        }

                    current.copy(
                        pendingQuestions = merged,
                        questionAggregationSignal = signal,
                    )
                }

                if (outcome is SlimAggregationOutcome.Failure) {
                    effects.tryEmitUiEvent(
                        UiEvent.Error(R.string.error_slim_questions_fetch_failed)
                    )
                }
            }
        }
    }

    /**
     * Task 12 (slimapi v1 §2 / §6.1 + §G2 — T12-C2): folds a decoded
     * `session.digest.lastError` three-state value into the canonical
     * [SessionListState.sessionErrorsById] map. Called from
     * [reconcileDigest] INSIDE T11's per-sid stripe (round-2 I1 fix) so
     * the fold serializes against session.error map writes + the
     * reconcile body for the same sid.
     *
     * # Three-state semantics (per [LastErrorField])
     *
     *  - [LastErrorField.Set] → write/replace `sessionErrorsById[sid]`
     *    (sidecar surfaces / replaces the upstream-error banner).
     *  - [LastErrorField.Cleared] → remove `sessionErrorsById[sid]`
     *    (sidecar signals upstream recovery — strand the active banner).
     *  - [LastErrorField.Omitted] → no-op (a debounce tick that doesn't
     *    restate `lastError` must NOT clear an active banner).
     *
     * # Idempotency / per-sid serialization (T12-C3, round-2 I1)
     *
     * Round-1 ran this fold INLINE in [handleSessionDigest] (outside the
     * stripe), relying only on `MutableStateFlow.update` CAS for atomicity.
     * That prevented structural lost-update but did NOT give per-sid
     * workflow serialization — concurrent Set/Cleared/session.error for the
     * same sid produced scheduling-dependent last-write-wins. Round-2 routes
     * the fold THROUGH [reconcileDigest]'s `stripeFor(sid).withLock { ... }`,
     * so digest lastError + session.error + reconcile all serialize
     * end-to-end per sid. Duplicate Set / duplicate Cleared frames still
     * converge to the same map state as a single application (idempotent).
     *
     * # NOT a banner abstraction (T12-C4)
     *
     * The map is written directly. There is no
     * `repository.applySessionErrorBanner` / `sessionBanners` indirection.
     */
    private fun applyDigestLastErrorToBanner(sid: String, field: LastErrorField) {
        when (field) {
            LastErrorField.Omitted -> { /* no-op — preserve prior banner */ }
            LastErrorField.Cleared -> {
                slices.mutateSessionList { s ->
                    if (sid !in s.sessionErrorsById) s
                    else s.copy(sessionErrorsById = s.sessionErrorsById - sid)
                }
            }
            is LastErrorField.Set -> {
                val banner = field.error
                slices.mutateSessionList { s ->
                    s.copy(sessionErrorsById = s.sessionErrorsById + (sid to banner))
                }
            }
        }
    }

    /**
     * Cluster A / Phase 2 → Task 11 (slimapi v1 §3 / §4 reconcile lane):
     * `session.digest` frame handler.
     *
     * # What stays inline (UI side effects, same-domain slice mutations)
     *
     *  - Decode the digest via [lenientJson] (NON-COERCING — T6 invariant;
     *    the [SlimSessionDigest.lastError] three-state Omitted/Cleared/Set
     *    is faithful ONLY under non-coercing Json. Do NOT migrate to
     *    `ssePayloadJson` / `coerceInputValues=true`).
     *  - Status badge fold ([SlimSessionDigest.status] → sessionStatuses).
     *  - Archived / deleted eviction (open-tabs + session list + cache
     *    eviction effect).
     *  - Lightweight `applyMessageTimestampBump` so recent-sort reflects
     *    activity.
     *  - Run the reducer ([OpenCodeRepository.applySlimDigest]) — mutates
     *    `remote*` watermarks + ratchets `dirty`. The returned
     *    [cn.vectory.ocdroid.data.repository.SlimFetchMessages] is now
     *    IGNORED: T11's [reconcileSession] always probes, making the
     *    reducer's optimistic fetch-decision redundant. (The reducer's
     *    STATE mutation is still required — that's what advances remote
     *    + sets dirty.)
     *
      * # What's delegated to [reconcileSession] (the message-fetch + dirty-clearing)
      *
      * The reducer having mutated state, we hand off to [reconcileSession]
      * on [scope]. Per-sid serialization (T11-C6) is inside
      * [reconcileSession].
      *
      * §CE-discipline (R-14): the [lenientJson] decode is synchronous (no
      * CE possible) so the [runCatching] here is safe. The suspend path
      * lives in [reconcileSession] / [OpenCodeRepository.probeLatestSlim] /
      * [OpenCodeRepository.getSlimapiMessagesSince] which all use
      * [cn.vectory.ocdroid.util.runSuspendCatching].
      */
    private fun handleSessionDigestImpl(event: SSEEvent) {
        val props = event.payload.properties
        if (props == null) {
            applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring session.digest with null properties")))
            return
        }
        val digest = runCatching {
            lenientJson.decodeFromJsonElement<SlimSessionDigest>(props)
        }.getOrNull()
        if (digest == null || digest.sessionId.isBlank()) {
            applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid session.digest payload")))
            return
        }
        DebugLog.d(
            "Sync",
            "session.digest sid=${digest.sessionId} status=${digest.status} " +
                "updatedAt=${digest.updatedAt} messageId=${digest.messageId} " +
                "archived=${digest.archived} deleted=${digest.deleted}",
        )
        // Status badge (slim stand-in for session.status).
        digest.status?.takeIf { it.isNotBlank() }?.let { statusType ->
            // §streaming-state-sync-diag (runtime-gated, scoped): record each
            // slim-digest status fold so we can attribute optimistic-busy
            // overwrites. Scope to the current (open) session — non-current
            // sessions' status folds carry no streaming-send signal.
            if (cn.vectory.ocdroid.util.DebugLog.verboseDiagEnabled &&
                digest.sessionId == slices.chat.value.currentSessionId
            ) {
                cn.vectory.ocdroid.util.DebugLog.d(
                    "StatusDiag",
                    "slim digest status write sid=${digest.sessionId} status=$statusType",
                )
            }
            slices.mutateSessionList {
                it.applySessionStatus(digest.sessionId, SessionStatus(type = statusType)).first
            }
        }
        // Task 12 round-2 (I1 fix): the digest's three-state `lastError`
        // fold into [SessionListState.sessionErrorsById] is routed THROUGH
        // T11's per-sid stripe — see [reconcileDigest] (the fold runs
        // inside the stripe, before the reconcile body). Round-1 ran the
        // fold inline here, which left it OUTSIDE the stripe and made
        // concurrent session.error / digest / reconcile for the same sid
        // scheduling-dependent (CAS-only, not per-sid workflow
        // serialization). Routing through the stripe gives T12-C3's
        // required per-sid serialization: digest lastError + session.error
        // + reconcile serialize end-to-end via stripeFor(sid).
        //
        // Archived / deleted eviction (slim stand-in for session.updated archived).
        if (digest.deleted == true || (digest.archived != null && digest.archived > 0L)) {
            val newOpenIds = slices.sessionList.value.openSessionIds.filter { id -> id != digest.sessionId }
            if (newOpenIds != slices.sessionList.value.openSessionIds) {
                settingsManager.openSessionIds = newOpenIds
            }
            val archivedSession = Session(
                id = digest.sessionId,
                directory = digest.directory
                    ?: slices.sessionList.value.sessions.firstOrNull { it.id == digest.sessionId }?.directory
                    ?: event.directory
                    ?: "",
                time = Session.TimeInfo(
                    archived = digest.archived?.takeIf { it > 0L } ?: if (digest.deleted == true) 1L else null,
                ),
            )
            slices.store.dispatch(
                cn.vectory.ocdroid.ui.AppAction.SessionArchived(
                    session = archivedSession,
                    openSessionIds = newOpenIds,
                )
            )
            effects.tryEmitEffect(
                ControllerEffect.EvictSession(currentServerGroupFp(), digest.sessionId)
            )
        } else if (digest.directory != null || digest.updatedAt != null) {
            // Lightweight session list touch so recent-sort reflects activity.
            val bumpAt = digest.updatedAt ?: 0L
            if (bumpAt > 0L) {
                slices.mutateSessionList { s ->
                    s.applyMessageTimestampBump(digest.sessionId, bumpAt).first
                }
            }
        }
        val repo = repository
        if (repo == null) {
            DebugLog.d("Sync", "session.digest: no repository wired — skip reconcile")
            return
        }
        val sid = digest.sessionId
        // T11 round-2 (oracle I4): select ReconcileMode based on whether
        // this is the currently-open chat tab. FOCUS may fetch + clear
        // dirty; BACKGROUND never clears dirty and never fetches (only
        // refreshes the row).
        val mode = if (sid == slices.chat.value.currentSessionId) {
            ReconcileMode.DIGEST_FOCUS
        } else {
            ReconcileMode.DIGEST_BACKGROUND
        }
        // §streaming-state-sync-diag (runtime-gated, scoped): entry context
        // for the reconcile decision. Logs the digest's updatedAt + the prior
        // localApplied watermark so we can confirm the
        // "updatedAt-not-advancing → fetch skipped" hypothesis. Scope to the
        // current (open) session — non-current digests go through BACKGROUND
        // mode (no FETCH decision) and carry no streaming-send signal.
        // (Repo may be null in legacy/test construction — guard the read.)
        if (cn.vectory.ocdroid.util.DebugLog.verboseDiagEnabled &&
            sid == slices.chat.value.currentSessionId
        ) {
            val priorLocalApplied = repo.getSlimSessionState(sid)?.localAppliedUpdatedAt
            val priorRemote = repo.getSlimSessionState(sid)?.remoteUpdatedAt
            cn.vectory.ocdroid.util.DebugLog.d(
                "DigestDiag",
                "digest entry sid=$sid updatedAt=${digest.updatedAt} " +
                    "priorLocalApplied=$priorLocalApplied priorRemote=$priorRemote mode=$mode " +
                    "messageId=${digest.messageId}",
            )
        }
        // T11 round-3 (oracle workflow-serialization): the reducer apply
        // + reconcile body run inside the SAME per-sid stripe lock so
        // competing digest / reconcile triggers for this sid serialize
        // end-to-end. Round-2 applied the reducer OUTSIDE the stripe,
        // which left a window where two digests for the same sid could
        // interleave with duplicate / out-of-order reconcile scheduling
        // (the repo atomic boundary prevented lost state, but not
        // duplicate probe/fetch work). Round-3 closes that gap.
        //
        // Network I/O remains OUTSIDE the repo's [slimStateLock]
        // (that's a separate invariant — the stripe lock CAN be held
        // across network IO because it's per-sid and bounded by the
        // Semaphore(4) for resync sweeps; the repo lock is the one that
        // must stay in-memory-only).
        val commitToken = repo.captureSlimCommitToken()
        scope.launch {
            reconcileDigest(sid, mode, digest, repo, commitToken)
        }
    }

    /**
     * T11 round-3 (oracle workflow-serialization): the digest-driven
     * per-sid workflow entry. Acquires [stripeFor] ONCE for the WHOLE
     * workflow (reducer apply + reconcile body) so competing digest /
     * reconcile triggers for the same sid serialize end-to-end.
     *
     * # Why this exists (oracle review round-2 fix)
     *
     * Round-2 had `handleSessionDigest` apply `applySlimDigest` OUTSIDE
     * the stripe, then `scope.launch { reconcileSession(sid, mode) }`
     * which acquires the stripe only for the reconcile body. The repo's
     * [slimStateLock] prevented state lost-update, but TWO concurrent
     * digests for the same sid could both apply + both schedule
     * reconciles (resulting in 2 probes / 2 fetches for one logical
     * digest burst). Round-3 fixes that by acquiring the stripe ONCE
     * for the entire workflow.
     *
     * # Network IO discipline
     *
     * The stripe lock CAN be held across network IO (it's per-sid,
     * bounded by [resyncConcurrencySemaphore] for sweep concurrency).
     * The REPO [slimStateLock] is the one that must stay in-memory-only
     * (unchanged from round-2).
     *
     * # CE discipline
     *
     * [Mutex.withLock] propagates CE; [reconcileSessionLocked] uses
     * [runSuspendCatching] internally. A scope cancel mid-workflow
     * terminates cleanly.
     */
    private suspend fun reconcileDigest(
        sid: String,
        mode: ReconcileMode,
        digest: SlimSessionDigest,
        repo: OpenCodeRepository,
        token: OpenCodeRepository.SlimCommitToken,
    ) {
        val uiSnapshot = ResyncUiSnapshot(slices.chat.value.currentSessionId)
        // UI banner mutation happens before the worker lock.
        val bannerCommitted = withContext(Dispatchers.Main.immediate) {
            repo.commitIfSlimTokenCurrent(token) {
                applyDigestLastErrorToBanner(sid, digest.lastError)
            }
        }
        if (!bannerCommitted) return
        val result: ReconcileResult = withContext(reconcileDispatcher) {
            stripeFor(sid).withLock {
            if (!repo.isSlimCommitTokenCurrent(token)) return@withLock ReconcileResult.Stale(sid)

            repo.applySlimDigest(digest, token)

            if (!repo.isSlimCommitTokenCurrent(token)) return@withLock ReconcileResult.Stale(sid)

                reconcileSessionLocked(sid, mode, repo, token, uiSnapshot.currentSessionId)
            }
        }
        applyReconcileResult(result, token, uiSnapshot, mode)
    }

    /**
     * Task 11 round-2 (slimapi v1 §3 / §4 — per-session reconciler): the
     * SINGLE entry point that serves BOTH digest-driven updates AND resync
     * catch-up. Branch logic per [ReconcileMode]'s matrix (oracle I4).
     *
     * # Per-sid serialization (T11-C6)
     *
     * Concurrent dispatch for the same sid (digest + resync, OR multiple
     * digests, OR explicit reconcile triggers) is serialized by the
     * [stripeFor] lock. Different sids usually land on different stripes
     * → fully parallel; the resync catch-up path bounds the global
     * in-flight count via [resyncConcurrencySemaphore].
     *
     * # Watermark-branched fetch (oracle I2)
     *
     * On needsCatchUp with [ReconcileMode.mayFetch]:
     *
     *  - `localAppliedUpdatedAt != null` → `getSlimapiMessagesSince(sid, ts)`
     *    (anchored on what we've actually applied).
     *  - `localAppliedUpdatedAt == null` → `fetchSlimInitialWindowBounded(sid)`
     *    (cursor-drain façade that reuses T5's drainSlimapiMessagesBounded).
     *
     * Both paths internally call `bumpSlimBookmarkFromItems → onReconcileSuccess`
     * to advance localApplied + clear dirty (with T11 round-2 dirty
     * re-evaluation inside the repo atomic boundary).
     *
     * # §CE-discipline (R-14)
     *
     * [OpenCodeRepository.probeLatestSlim] /
     * [OpenCodeRepository.getSlimapiMessagesSince] /
     * [OpenCodeRepository.fetchSlimInitialWindowBounded] all use
     * [cn.vectory.ocdroid.util.runSuspendCatching] internally so CE
     * propagates correctly. [Mutex.withLock] propagates CE. A scope cancel
     * mid-reconcile terminates cleanly WITHOUT landing a partial state
     * mutation (the repo atomic boundary is held only during pure
     * read/derive/write, never across network IO).
     *
     * # Idle/non-slim guard
     *
     * When [repository] is null OR [isSlimMode] is false, this is a no-op
     * ([ReconcileResult.NoRepository]). The legacy non-slim path is
     * byte-for-byte unchanged (digest is recognized but not reconciled).
     *
     * @param sid the session id to reconcile.
     * @param mode the calling context — see [ReconcileMode] for the
     *   branch matrix.
     */
    internal suspend fun reconcileSession(sid: String, mode: ReconcileMode): ReconcileResult {
        val repo = repository ?: return ReconcileResult.NoRepository(sid)
        if (!isSlimMode()) return ReconcileResult.NoRepository(sid)
        val uiSnapshot = ResyncUiSnapshot(slices.chat.value.currentSessionId)

        // C-D3 v2 §1.8: public workflow entry captures once before its
        // first suspend point. Every nested suspend call and every commit
        // surface receives this exact token — NO recapture inside.
        val token = repo.captureSlimCommitToken()

        val result = reconcileSessionWithToken(
            sid = sid,
            mode = mode,
            repo = repo,
            token = token,
            isStillCurrent = { true },
        )
        return applyReconcileResult(result, token, uiSnapshot, mode)
    }

    /**
     * C-D3 v2 §1.8: shared reconcile body that threads an externally-
     * supplied token through the stripe + reconcile body. Used by:
     *  - [reconcileSession] (public entry, captures fresh).
     *  - [performResyncCatchUp] (uses the orchestrator's entry token,
     *    no recapture).
     *
     * The resync path MUST call this (NOT public [reconcileSession]) so
     * the orchestrator's single-entry-token invariant holds.
     */
    private suspend fun reconcileSessionWithToken(
        sid: String,
        mode: ReconcileMode,
        repo: OpenCodeRepository,
        token: OpenCodeRepository.SlimCommitToken,
        isStillCurrent: () -> Boolean,
    ): ReconcileResult = withContext(reconcileDispatcher) {
        stripeFor(sid).withLock {
        // C-D3 v2 §1.8: re-check both predicates under the stripe. A host
        // switch between the entry capture and the stripe acquisition
        // surfaces as Stale (NOT NoRepository — the repo IS wired, but
        // the token predates the rotation).
        if (!isStillCurrent() || !repo.isSlimCommitTokenCurrent(token)) {
            return@withLock ReconcileResult.Stale(sid)
        }

        reconcileSessionLocked(sid, mode, repo, token, null)
        }
    }

    /**
     * Test-visible alias for [reconcileSession] — same semantics, named to
     * make it obvious in stack traces + tests that this is the entry under
     * test. Production callers ([handleSessionDigest],
     * [performResyncCatchUp], [performSlimResync]) go through
     * [reconcileSession] directly.
     */
    internal suspend fun reconcileSessionExposed(sid: String, mode: ReconcileMode): ReconcileResult =
        reconcileSession(sid, mode)

    /**
     * Task 11: the reconciler body, called under the per-sid stripe lock.
     * Reads the latest [SlimSessionState] (the reducer / a prior reconcile
     * may have mutated it while this dispatch waited for the lock),
     * probes, and dispatches to the appropriate T6 primitive via the
     * repository's public mutators (which hold the repo-level atomic
     * boundary).
     *
     * Branch matrix per [ReconcileMode] (oracle I4).
     */
    private suspend fun reconcileSessionLocked(
        sid: String,
        mode: ReconcileMode,
        repo: OpenCodeRepository,
        token: OpenCodeRepository.SlimCommitToken,
        snapshotCurrentSessionId: String?,
    ): ReconcileResult {
        // T11 round-3: the slim-mode guard lives HERE (not just at the
        // [reconcileSession] public entry) so the [reconcileDigest]
        // workflow path (which calls this body directly under the
        // stripe) ALSO short-circuits for legacy non-slim mode. The
        // legacy non-slim path is byte-for-byte unchanged: digest is
        // recognized but no probe / fetch / state mutation lands.
        if (!isSlimMode()) return ReconcileResult.NoRepository(sid)
        val state = repo.getSlimSessionState(sid) ?: SlimSessionState(sessionId = sid)
        val probe = repo.probeLatestSlim(sid)
        // ── probe failure branches (uniform across all modes) ────────────
        if (!probe.ok) {
            if (probe.httpStatus == 404) {
                // Session gone upstream → mark deleted (all modes).
                if (!repo.markSlimSessionDeleted(sid, token)) {
                    return ReconcileResult.Stale(sid)
                }
                DebugLog.i("Sync", "reconcileSession sid=$sid mode=$mode 404 → markDeleted")
                return ReconcileResult.MarkDeleted(sid)
            }
            // Transport / network failure → keep dirty (all modes).
            if (!repo.markSlimReconcileFailure(sid, token)) {
                return ReconcileResult.Stale(sid)
            }
            DebugLog.i(
                "Sync",
                "reconcileSession sid=$sid mode=$mode probe failed httpStatus=${probe.httpStatus} → keep dirty",
            )
            return ReconcileResult.Failure(sid)
        }
        // ── probe ok + empty branch ──────────────────────────────────────
        if (probe.empty) {
            val localHas = state.localAppliedMessageId != null ||
                state.localAppliedUpdatedAt != null
            if (localHas) {
                // FOCUS/RESYNC: clear local + clear dirty.
                // BACKGROUND: no-op (keep dirty; do NOT clear local — the
                // local cache is the user's open tab's history).
                if (mode.mayClearDirty()) {
                    if (!repo.clearSlimLocalMessages(sid, token)) {
                        return ReconcileResult.Stale(sid)
                    }
                    DebugLog.i("Sync", "reconcileSession sid=$sid mode=$mode probe empty + local-has → clearLocal")
                    return ReconcileResult.ClearLocal(sid)
                }
                DebugLog.d("Sync", "reconcileSession sid=$sid mode=$mode probe empty + local-has → BACKGROUND no-op (keep dirty)")
                return ReconcileResult.RefreshRow(sid)
            }
            // Local-empty + probe-empty: aligned.
            // FOCUS/RESYNC: clear dirty.
            // BACKGROUND: no-op (keep dirty).
            if (mode.mayClearDirty()) {
                if (!repo.markSlimReconcileAligned(sid, token)) {
                    return ReconcileResult.Stale(sid)
                }
                DebugLog.d("Sync", "reconcileSession sid=$sid mode=$mode probe empty + local-empty → aligned")
                return ReconcileResult.Aligned(sid)
            }
            DebugLog.d("Sync", "reconcileSession sid=$sid mode=$mode probe empty + local-empty → BACKGROUND no-op")
            return ReconcileResult.RefreshRow(sid)
        }
        // ── probe ok + non-empty: needsCatchUp decision ─────────────────
        val catchUp = needsCatchUp(
            probe = probe,
            localAppliedId = state.localAppliedMessageId,
            localAppliedTs = state.localAppliedUpdatedAt,
        )
        if (!catchUp) {
            // §streaming-state-sync-diag (runtime-gated, scoped): probe says
            // caught up → no FETCH. Scope to current session (resync catch-up
            // runs this body for every dirty sid — non-current sids carry no
            // streaming-send signal).
            if (cn.vectory.ocdroid.util.DebugLog.verboseDiagEnabled &&
                sid == snapshotCurrentSessionId
            ) {
                cn.vectory.ocdroid.util.DebugLog.d(
                    "DigestDiag",
                    "digest sid=$sid remoteUpdatedAt=${state.remoteUpdatedAt} " +
                        "priorWatermark=${state.localAppliedUpdatedAt} decision=skip reason=aligned mode=$mode",
                )
            }
            if (mode.mayClearDirty()) {
                if (!repo.markSlimReconcileAligned(sid, token)) {
                    return ReconcileResult.Stale(sid)
                }
                DebugLog.d("Sync", "reconcileSession sid=$sid mode=$mode probe aligned → clear dirty")
                return ReconcileResult.Aligned(sid)
            }
            DebugLog.d("Sync", "reconcileSession sid=$sid mode=$mode probe aligned → BACKGROUND no clear")
            return ReconcileResult.RefreshRow(sid)
        }
        // ── needsCatchUp: FOCUS/RESYNC fetch; BACKGROUND refresh only ────
        if (!mode.mayFetch()) {
            // §streaming-state-sync-diag (runtime-gated, scoped): BACKGROUND
            // mode → no FETCH (refresh row only).
            if (cn.vectory.ocdroid.util.DebugLog.verboseDiagEnabled &&
                sid == snapshotCurrentSessionId
            ) {
                cn.vectory.ocdroid.util.DebugLog.d(
                    "DigestDiag",
                    "digest sid=$sid remoteUpdatedAt=${state.remoteUpdatedAt} " +
                        "priorWatermark=${state.localAppliedUpdatedAt} decision=skip reason=BACKGROUND mode=$mode",
                )
            }
            DebugLog.d("Sync", "reconcileSession sid=$sid mode=$mode BACKGROUND needsCatchUp → schedule resync")
            val gen = currentEpoch()
            // Internal guard in performSlimResync handles cadence — launch unconditionally.
            scope.launch { performSlimResync(sessionsDirty = setOf(sid), isManual = false) }
            return ReconcileResult.RefreshRow(sid)
        }
        // §streaming-state-sync-diag (runtime-gated, scoped): FETCH decision —
        // about to call /slimapi/messages/since.
        if (cn.vectory.ocdroid.util.DebugLog.verboseDiagEnabled &&
            sid == snapshotCurrentSessionId
        ) {
            cn.vectory.ocdroid.util.DebugLog.d(
                "DigestDiag",
                "digest sid=$sid remoteUpdatedAt=${state.remoteUpdatedAt} " +
                    "priorWatermark=${state.localAppliedUpdatedAt} decision=FETCH mode=$mode",
            )
        }
        // Watermark-branched fetch (oracle I2):
        //   - localAppliedUpdatedAt != null → /since/{ts}
        //   - localAppliedUpdatedAt == null → bounded cursor drain façade
        return if (state.localAppliedUpdatedAt != null) {
            val since = state.localAppliedUpdatedAt!!
            val result = repo.getSlimapiMessagesSince(sid, since, token = token)
            foldRestFetch(sid, mode, result, token)
        } else {
            // Cold path: no local watermark yet. Use the bounded cursor
            // drain façade (reuses T5's drainSlimapiMessagesBounded).
            val result = repo.fetchSlimInitialWindowBounded(sid, token = token)
            foldRestFetch(sid, mode, result, token)
        }
    }

    /**
     * Task 11 round-2: shared fold for both fetch paths (/since and
     * cursor-drain). On success: merge into chat slice (if current
     * session) + return Reconciled. On failure: keep dirty + return
     * Failure. The watermark advancement + dirty clear happens INSIDE
     * `bumpSlimBookmarkFromItems` (the repo atomic boundary with dirty
     * re-evaluation) — the coordinator doesn't double-clear here.
     */
    private suspend fun foldRestFetch(
        sid: String,
        mode: ReconcileMode,
        result: Result<List<MessageWithParts>>,
        token: OpenCodeRepository.SlimCommitToken,
    ): ReconcileResult {
        val repo = repository ?: return ReconcileResult.NoRepository(sid)

        return result.fold(
            onSuccess = { items ->
                if (!repo.isSlimCommitTokenCurrent(token)) ReconcileResult.Stale(sid)
                else ReconcileResult.Reconciled(sid, items)
            },
            onFailure = { error ->
                // C-D3 v2 §1.9: Stale ≠ Failure. A stale cursor result
                // must NOT call markSlimReconcileFailure (that pollutes
                // error state for the new incarnation). Only real
                // transport failures map to Failure / markSlimReconcileFailure.
                if (error is OpenCodeRepository.StaleSlimCommitException ||
                    !repo.isSlimCommitTokenCurrent(token)
                ) {
                    return@fold ReconcileResult.Stale(sid)
                }

                val marked = repo.markSlimReconcileFailure(sid, token)
                if (!marked) {
                    ReconcileResult.Stale(sid)
                } else {
                    DebugLog.w(
                        tag,
                        "reconcileSession sid=$sid mode=$mode REST failed: ${error.message}",
                    )
                    ReconcileResult.Failure(sid)
                }
            },
        )
    }

    /**
     * Task 11: fold a [ReconcileResult] into UI side effects that can't
     * live inside the repository's pure state-derive layer. Called by
     * [handleSessionDigest] after [reconcileSession] returns.
     *
     *  - [ReconcileResult.MarkDeleted] → drop the session from the open-tabs
     *    list + dispatch [AppAction.SessionArchived] so the row vanishes.
     *    (The repository's `markSlimSessionDeleted` only flips the
     *    `deleted` flag on the slim SSE state; the UI list mutation lives
     *    here.)
     *  - [ReconcileResult.ClearLocal] → wipe the chat slice's message
     *    cache for [ReconcileResult.ClearLocal.sid] if it's the current
     *    session (the reducer / a prior merge may have left stale rows).
     *  - Other variants → state already updated inside [reconcileSession];
     *    no additional side effects.
     */
    data class ResyncUiSnapshot(val currentSessionId: String?)

    private suspend fun applyReconcileResult(
        result: ReconcileResult,
        token: OpenCodeRepository.SlimCommitToken,
        // T2 (Phase 3): the focus-snapshot gate that used this parameter was
        // removed (see below). The parameter is retained because callers
        // ([reconcileSession], [performResyncCatchUp]) still pass it and the
        // [ResyncUiSnapshot] capture at resync entry stays for future use;
        // the ABI is unchanged.
        snapshot: ResyncUiSnapshot? = null,
        // §Stage-B §3.4: the reconcile mode threads through to the Reconciled
        // branch's isAuthoritativeSlimMerge decision (RESYNC is always
        // authoritative; DIGEST_FOCUS/BACKGROUND consult session status).
        mode: ReconcileMode = ReconcileMode.DIGEST_BACKGROUND,
    ): ReconcileResult {
        val repo = repository ?: return ReconcileResult.NoRepository(resultSid(result))

        // C-D3 v2 §1.10: Stale is a clean no-op (no slice / cache / effect
        // commit, no Failure pollution).
        if (result is ReconcileResult.Stale) return result

        val stillCurrent = withContext(reconcileDispatcher) {
            repo.isSlimCommitTokenCurrent(token)
        }
        // rev-grok rule #1: the token gate is all-or-nothing. A stale /
        // superseded token (between fetch and commit) → full no-op + Stale.
        // This is the ONLY branch that returns Stale from a non-Stale input.
        if (!stillCurrent) return ReconcileResult.Stale(resultSid(result))

        // T2 (Phase 3): the OUTER focus-snapshot gate is gone. Pre-T2 a
        // mid-reconcile session switch caused
        //   `if (snapshot.currentSessionId != liveSessionId) return@commit`
        // which dropped the ENTIRE result — including work that is NOT
        // current-session-sensitive:
        //   - MarkDeleted → openSessionIds mutation + SessionArchived +
        //     EvictSession (session-list-GLOBAL; dropping it left a zombie
        //     open tab).
        //   - ClearLocal → EvictSession (global) was dropped even though
        //     the chat clear itself is self-gated inside
        //     applyCurrentReconcileResult by `liveSessionId == sid`.
        //   - Reconciled for a NON-current session → WriteSessionWindow
        //     cache write + dirty re-ratchet were skipped, leaving stale
        //     dirty with no retained window for the next switchTo.
        // The chat-merge (the ONE current-session-sensitive op) was ALREADY
        // protected inside applyCurrentReconcileResult's Reconciled branch
        // by `if (liveSessionId == result.sid) mergeSlimMessagesIntoChat`
        // — rev-grok rule #3. The coarse outer gate was therefore redundant
        // for chat safety and only wrongly suppressed global/retention work.
        //
        // Catch-up accounting note (Phase 3 review): the only caller-side
        // consumer of this return value is the per-sid outcomes map logged
        // by SessionStreamingService.onResync (`outcomes.size`). No control
        // flow branches on Stale vs the real result, so returning the real
        // result here cannot strand a re-enqueue/skip loop. The per-sid
        // dirty clear/ratchet still happens inside the atomic
        // bumpSlimBookmarkFromItems (during the fetch) and the retention
        // branch below re-ratchets via markSlimDirty if the window wasn't
        // retained — both paths terminate correctly under T2.
        val committed = withContext(Dispatchers.Main.immediate) {
            repo.commitIfSlimTokenCurrent(token) {
                val liveSessionId = slices.chat.value.currentSessionId
                applyCurrentReconcileResult(result, token, liveSessionId, mode)
            }
        }
        return if (committed) result else ReconcileResult.Stale(resultSid(result))
    }

    private fun resultSid(result: ReconcileResult): String = when (result) {
        is ReconcileResult.Aligned -> result.sid
        is ReconcileResult.Reconciled -> result.sid
        is ReconcileResult.RefreshRow -> result.sid
        is ReconcileResult.MarkDeleted -> result.sid
        is ReconcileResult.ClearLocal -> result.sid
        is ReconcileResult.Failure -> result.sid
        is ReconcileResult.TimedOut -> result.sid
        is ReconcileResult.NoRepository -> result.sid
        is ReconcileResult.Stale -> result.sid
    }

    /**
     * C-D3 v2 §1.10: the current-reincarnation body. Runs inside the
     * [commitIfSlimTokenCurrent] atomic region — every branch must stay
     * synchronous (no network, no delay, no blocking IO).
     */
    private fun applyCurrentReconcileResult(
        result: ReconcileResult,
        token: OpenCodeRepository.SlimCommitToken,
        liveSessionId: String? = slices.chat.value.currentSessionId,
        // §Stage-B §3.4: mode threads to the Reconciled branch's
        // isAuthoritativeSlimMerge decision.
        mode: ReconcileMode = ReconcileMode.DIGEST_BACKGROUND,
    ) {
        when (result) {
            is ReconcileResult.MarkDeleted -> {
                val sid = result.sid
                val currentList = slices.sessionList.value
                val newOpenIds = currentList.openSessionIds.filter { it != sid }

                if (newOpenIds != currentList.openSessionIds) {
                    settingsManager.openSessionIds = newOpenIds
                }

                val directory = currentList.sessions
                    .firstOrNull { it.id == sid }
                    ?.directory
                    .orEmpty()

                slices.store.dispatch(
                    cn.vectory.ocdroid.ui.AppAction.SessionArchived(
                        session = Session(
                            id = sid,
                            directory = directory,
                            time = Session.TimeInfo(archived = 1L),
                        ),
                        openSessionIds = newOpenIds,
                    )
                )

                effects.tryEmitEffect(
                    ControllerEffect.EvictSession(currentServerGroupFp(), sid),
                )
            }

            is ReconcileResult.ClearLocal -> {
                val sid = result.sid

                // T1d P1-3: slim-only ClearLocal path — fail-fast in legacy
                // mode before any chat wipe (structural; normal legacy never
                // reaches ClearLocal because reconcile early-returns).
                requireSlimOnlyStateWrite(isSlimMode(), "clear-local")

                if (liveSessionId == sid) {
                    // T1b: content-only wipe (messages + partsByMessage);
                    // streaming overlay / cursor / model preserved.
                    slices.store.dispatch(cn.vectory.ocdroid.ui.AppAction.SlimChatContentCleared)
                }

                effects.tryEmitEffect(
                    ControllerEffect.EvictSession(currentServerGroupFp(), sid),
                )
            }

            is ReconcileResult.Reconciled -> {
                if (liveSessionId == result.sid) {
                    // §Stage-B §3.4 (grok MF-1): thread the authoritative
                    // decision through mergeSlimMessagesIntoChat so an
                    // actively-streaming token stream's owned parts are
                    // preserved on a skeleton merge, and cleared on an
                    // authoritative (resync / idle) merge.
                    val authoritative = isAuthoritativeSlimMerge(
                        mode = mode,
                        sid = result.sid,
                        sessionStatuses = slices.sessionList.value.sessionStatuses,
                    )
                    mergeSlimMessagesIntoChat(result.items, authoritative)
                }
                // T11 round-2 (oracle D1 — cache-coupled non-focus resync):
                // if this Reconciled was for a NON-current session, write
                // the fetched items to sessionWindowCache so a later
                // switchTo finds them without a re-fetch. For the CURRENT
                // session, items were already merged into the chat slice
                // inside foldRestFetch — no extra work.
                //
                // T11 round-3 (oracle D1 part a — retention-bound dirty):
                // the dirty clear already happened inside
                // `bumpSlimBookmarkFromItems` (via `onReconcileSuccess`)
                // during the fetch. If the cache-retention step below
                // fails (empty result / filtered-to-nothing / bus drop),
                // we RE-RATCHET dirty via [markSlimDirty] so the next
                // pass retries the fetch. Without this binding, dirty
                // could clear without a retained window — leaving the
                // user with no cached messages and no scheduled retry.
                val sid = result.sid
                val isCurrent = liveSessionId == sid

                if (!isCurrent && result.items.isNotEmpty()) {
                    val messages = result.items
                        .map { it.info }
                        .filter { it.id.isNotEmpty() }

                    val partsByMessage = result.items
                        .filter {
                            it.info.id.isNotEmpty() && it.parts.isNotEmpty()
                        }
                        .associate { it.info.id to it.parts }

                    if (messages.isNotEmpty()) {
                        val retained = effects.tryEmitEffect(
                            ControllerEffect.WriteSessionWindow(
                                serverGroupFp = currentServerGroupFp(),
                                sessionId = sid,
                                messages = messages,
                                partsByMessage = partsByMessage,
                            )
                        )

                        if (!retained) {
                            DebugLog.w(tag, "applyReconcileResult sid=$sid WriteSessionWindow dropped → re-ratchet dirty")
                            repository?.markSlimDirty(sid, token)
                        }
                    } else {
                        DebugLog.w(tag, "applyReconcileResult sid=$sid filtered-to-nothing → re-ratchet dirty")
                        repository?.markSlimDirty(sid, token)
                    }
                } else if (!isCurrent && result.items.isEmpty()) {
                    DebugLog.d(tag, "applyReconcileResult sid=$sid empty non-focus result → re-ratchet dirty")
                    repository?.markSlimDirty(sid, token)
                }
                // For the CURRENT session, items are already merged into
                // the chat slice (retention = chat slice itself); no
                // extra dirty work needed.
            }

            is ReconcileResult.RefreshRow,
            is ReconcileResult.Aligned,
            is ReconcileResult.Failure,
            is ReconcileResult.TimedOut,
            is ReconcileResult.NoRepository,
            is ReconcileResult.Stale -> {
                // C-D3 v2 §1.10: Stale is also caught by the entry guard;
                // reproduced here to keep the `when` exhaustive without
                // an `else`.
                // State already updated inside reconcileSession; no extra UI work.
            }
        }
    }

    /**
     * T13 — fold a slim on-demand fan-out summary into coordinator side
     * effects. Slim on-demand ONLY; legacy non-slim callers never reach
     * this (T13-C6 — the bulk L3 path [StatusAggregatorImpl.refresh] is
     * byte-for-byte unchanged).
     *
     * Two side-effect arms (per the brief's "coordinator action hook"):
     *
     *  - **[StatusFanOutSummary.missingSids]** → emit a delete-session
     *    effect per sid. The session is gone upstream (direct 404 OR
     *    fake-idle per T13-C5 — both folded by [foldStatusOutcomes]).
     *    Mirrors the session.updated archived + digest deleted branches'
     *    [ControllerEffect.EvictSession] emission so the cache + open-tabs
     *    list are cleaned uniformly (T13-C3).
     *  - **[StatusFanOutSummary.retryableCount]** → request the poller's
     *    bounded backoff when > 0 (T13-C4: transient sidecar/upstream/
     *    transport fault → next sweep slows down); reset the backoff to
     *    base when == 0 (the success path).
     *
     * **Minimally scoped** (T11/T12 just heavily modified this file):
     * this hook touches ONLY the effect bus — it does NOT read or mutate
     * the slice flows, the repo's slim SSE state, or the per-sid stripe
     * locks. The hook is a pure routing step from a [StatusFanOutSummary]
     * (produced by [cn.vectory.ocdroid.service.status.SlimStatusFanOut])
     * to effect emissions.
     *
     * @param summary the fan-out result. Caller (the slim integration
     *   layer / future fan-out scheduler) constructs this via
     *   [cn.vectory.ocdroid.service.status.SlimStatusFanOut.checkSlimSessionsStatuses].
     */
    fun applySlimStatusFanOutSummary(summary: cn.vectory.ocdroid.service.status.StatusFanOutSummary) {
        val fp = currentServerGroupFp()
        // T13-C3: missingSids → delete-session effect per sid. 404 and
        // fake-idle (T13-C5) both land here (folded by [foldStatusOutcomes]).
        for (sid in summary.missingSids) {
            effects.tryEmitEffect(ControllerEffect.EvictSession(fp, sid))
        }
        // T13-C4: retryableCount > 0 → ask the poller to schedule backoff;
        // retryableCount == 0 → ask the poller to reset backoff to base
        // (the success path; symmetric to keep the poller's backoff state
        // machine coherent across sweeps).
        if (summary.retryableCount > 0) {
            effects.tryEmitEffect(ControllerEffect.RequestPollerBackoff)
        } else {
            effects.tryEmitEffect(ControllerEffect.ResetPollerBackoff)
        }
    }

    /**
     * Task 11 (slimapi v1 §3 / §4 resync catch-up): orchestrate a
     * catch-up sweep across the union of (catchUpSet) per the contract §3
     * catch-up-set definition. Each sid is reconciled via
     * [reconcileSession] (probe + dispatch) with [ReconcileMode.RESYNC].
     *
     * # Concurrency model (T11-C5)
     *
     *  - [supervisorScope]: a single slow / failing sid does NOT propagate
     *    its exception to the sweep (other sids still complete). A sid
     *    throwing inside its [withTimeout] is caught here as a
     *    [TimeoutCancellationException] (a CE subclass) → the per-sid job
     *    completes-with-exception silently + the per-sid outcome is
     *    recorded as [ReconcileResult.TimedOut] for diagnostics.
     *  - [resyncConcurrencySemaphore] (Semaphore(4)): bounds the global
     *    in-flight REST fetch count so a 50-session sweep doesn't
     *    stampede the sidecar. Per the contract's performance hint
     *    ("可加客户端并发上限（如 4）").
     *  - **per-sid deadline** ([withTimeout]`[perSidDeadlineMs]`):
     *    prevents ONE slow / hung sid from blocking the entire batch.
     *
     * # T11 round-2 (oracle D4 — timeout ordering)
     *
     * The ordering is `semaphore.withPermit { withTimeout { reconcile } }`
     * (NOT `withTimeout { semaphore.withPermit { ... } }`). The deadline
     * starts when the WORK starts (after the permit is acquired), not
     * when the sid is QUEUED on the semaphore. Otherwise a sid that
     * waited 5s for a permit would have only 3s of work budget left —
     * premature timeouts under sustained load.
     *
     * # Idempotency (T11-C6)
     *
     * Each per-sid reconcile goes through [reconcileSession] → [stripeFor],
     * so a digest arriving mid-sweep for a sid already being reconciled
     * serializes (no double-apply).
     *
     * # CE discipline
     *
     * [supervisorScope] + [withTimeout] propagate CE correctly. A
     * [scope] cancel mid-sweep cancels every per-sid job; partial state
     * mutations are absent (each sid's state mutation is atomic inside
     * the repository's [slimStateLock] boundary).
     *
     * @param catchUpSet the pre-computed catch-up set (focus ∪ localAll ∪
     *   dirty). Caller (typically [performSlimResync]) is responsible for
     *   building it so the snapshot timing is well-defined.
     * @param perSidDeadlineMs per-sid timeout. Defaults to
     *   [defaultResyncPerSidDeadlineMs]; tests pass a shorter value.
     * @return per-sid outcomes (oracle D4 — observable timeout/failure
     *   for diagnostics + dirty-preservation verification).
     */
    suspend fun performResyncCatchUp(
        catchUpSet: Set<String>,
        perSidDeadlineMs: Long = defaultResyncPerSidDeadlineMs,
        token: OpenCodeRepository.SlimCommitToken? = null,
        isStillCurrent: () -> Boolean = { true },
        snapshot: ResyncUiSnapshot? = null,
    ): Map<String, ReconcileResult> {
        // UI state is Main-confined: capture it before entering the worker lane.
        val resyncUiSnapshot = snapshot ?: ResyncUiSnapshot(slices.chat.value.currentSessionId)
        return withContext(reconcileDispatcher) {
            performResyncCatchUpOnWorker(
                catchUpSet,
                perSidDeadlineMs,
                token,
                isStillCurrent,
                resyncUiSnapshot,
            )
        }
    }

    private suspend fun performResyncCatchUpOnWorker(
        catchUpSet: Set<String>,
        perSidDeadlineMs: Long = defaultResyncPerSidDeadlineMs,
        /**
         * C-D3 v2 §1.11: orchestrator-supplied entry token. The whole
         * sweep uses THIS token; NO recapture inside per-sid children.
         * Defaults to a fresh capture for backwards-compat with the
         * public surface (callers that don't have one handy still get
         * per-call current-token semantics).
         */
        token: OpenCodeRepository.SlimCommitToken? = null,
        /**
         * C-D3 v2 §1.11: orchestrator-supplied "still current" predicate
         * (e.g. the SSE identity is still the live transport). Combined
         * with the token check inside.
         */
        isStillCurrent: () -> Boolean = { true },
        resyncUiSnapshot: ResyncUiSnapshot,
    ): Map<String, ReconcileResult> {
        val repo = repository ?: return emptyMap()
        if (!isSlimMode() || catchUpSet.isEmpty()) return emptyMap()

        // C-D3 v2 §1.11: prefer the orchestrator-supplied token; fall
        // back to a fresh capture for the legacy single-call surface.
        val entryToken = token ?: repo.captureSlimCommitToken()

        fun current(): Boolean =
            isStillCurrent() && repo.isSlimCommitTokenCurrent(entryToken)

        if (!current()) {
            // Stale at the gate: every sid is Stale (NO probe / fetch
            // attempt — the orchestrator's token is already superseded).
            return catchUpSet.associateWith { ReconcileResult.Stale(it) }
        }

        DebugLog.i(
            "Sync",
            "performResyncCatchUp union=${catchUpSet.size} deadlineMs=$perSidDeadlineMs",
        )
        val outcomes = java.util.concurrent.ConcurrentHashMap<String, ReconcileResult>()
        // supervisorScope: a per-sid failure / timeout does NOT propagate
        // to the sweep. Each per-sid launch is independent.
        supervisorScope {
            for (sid in catchUpSet) {
                launch {
                    // D4 timeout ordering: acquire the permit FIRST, then
                    // start the deadline. A sid queued behind others does
                    // not "use up" its deadline while waiting.
                    resyncConcurrencySemaphore.withPermit {
                        val result = try {
                            withTimeout(perSidDeadlineMs) {
                                // C-D3 v2 §1.11: per-sid reconcile uses
                                // the SAME entry token (NO recapture).
                                val reconciled = reconcileSessionWithToken(
                                    sid = sid,
                                    mode = ReconcileMode.RESYNC,
                                    repo = repo,
                                    token = entryToken,
                                    isStillCurrent = isStillCurrent,
                                )

                                reconciled
                            }
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            // Per-sid deadline exceeded.
                            if (!current()) {
                                ReconcileResult.Stale(sid)
                            } else {
                                DebugLog.w(tag, "reconcileSession sid=$sid RESYNC timed out after ${perSidDeadlineMs}ms")
                                ReconcileResult.TimedOut(sid)
                            }
                        }
                        val finalResult = if (
                            result is ReconcileResult.TimedOut || result is ReconcileResult.Stale
                        ) {
                            result
                        } else {
                            // Main switch/commit is deliberately outside the
                            // network timeout and outside any worker lock.
                            applyReconcileResult(result, entryToken, resyncUiSnapshot, ReconcileMode.RESYNC)
                        }
                        outcomes[sid] = finalResult
                    }
                }
            }
        }
        return outcomes.toMap()
    }

    /**
     * Task 11 round-2 (oracle I1 — Coordinator-owned `performSlimResync`
     * orchestrator): the SINGLE method [SessionStreamingService.onResync]
     * calls. Replaces the round-1 direct `repository.coldStartSlimSync`
     * invocation (which produced a snapshot but did NOT run the per-sid
     * reconcile sweep, leaving [performResyncCatchUp] as dead code).
     *
     * # Ordering (oracle I1)
     *
     *  1. **Capture** the current focus + pre-refresh known SIDs + dirty
     *     SIDs. The pre-refresh snapshot is the fallback catch-up set if
     *     the metadata refresh fails.
     *  2. **Metadata refresh**: `repository.coldStartSlimSync(openSessionId
     *     = null, directories = ...)` — metadata-only (NO open-session
     *     message fetch; the per-sid reconcile handles that uniformly).
     *  3. **Fold** the snapshot via [applySlimColdStartSnapshot] (D2
     *     nullable semantics — null pieces keep prior; empty replaces).
     *  4. **Build catch-up set** = pre-refresh SIDs ∪ refreshed session
     *     list ∪ SlimSseState keys ∪ dirty.
     *  5. **[performResyncCatchUp]** with [ReconcileMode.RESYNC] for EVERY
     *     sid in the union.
     *
     * If the metadata refresh fails, the catch-up still runs against the
     * pre-refresh known set (oracle I1: "If metadata refresh fails, still
     * catch up the pre-refresh known set").
     *
     * # Open-session message fetch policy
     *
     * `openSessionId = null` is passed to `coldStartSlimSync` so the
     * metadata refresh does NOT fetch the open session's messages. The
     * per-sid reconcile handles that uniformly (watermark-branched:
     * `/since/{ts}` if localAppliedUpdatedAt set, else bounded cursor
     * drain). This avoids a double-fetch race between cold-start snapshot
     * apply and the reconcile sweep.
     *
     * # CE discipline
     *
     * `coldStartSlimSync` uses [runSuspendCatching]; CE propagates. The
     * supervisorScope inside [performResyncCatchUp] isolates per-sid
     * failures. A scope cancel mid-orchestration terminates cleanly
     * (state mutations are atomic at the repo boundary).
     *
     * @param directories the workdirs to refresh (current + recent); null
     *   = let the sidecar decide scope.
     * @param sessionsDirty sessions explicitly marked dirty by the SSE
     *   gap overlay (e.g. the prior current session at disconnect time).
     * @return per-sid outcomes from the catch-up sweep (empty if the
     *   sweep was skipped — no repo / non-slim / empty catch-up set).
     */
    suspend fun performSlimResync(
        directories: List<String>? = null,
        sessionsDirty: Set<String> = emptySet(),
        perSidDeadlineMs: Long = defaultResyncPerSidDeadlineMs,
        isStillCurrent: () -> Boolean = { true },
        isManual: Boolean = false,
        bypassIntervalCheck: Boolean = false,
    ): Map<String, ReconcileResult> {
        val repo = repository ?: return emptyMap()
        if (!isSlimMode()) return emptyMap()

        // Cadence guard: if the cadence declines (stale gen / too soon / in-flight),
        // skip the sweep entirely (caller may still have the partial/single-flight
        // fallback from maybeScheduleResync returning false).
        if (!maybeScheduleResync(currentEpoch(), isManual, bypassIntervalCheck)) return emptyMap()

        var hadFailure = true
        try {
            // C-D3 v2 §1.12: ONE entry token; NO recapture after this point.
            val token = repo.captureSlimCommitToken()

            fun current(): Boolean =
                isStillCurrent() && repo.isSlimCommitTokenCurrent(token)

            if (!current()) {
                hadFailure = false
                return emptyMap()
            }

            // ── Step 1: capture pre-refresh state ───────────────────────────
            val resyncUiSnapshot = ResyncUiSnapshot(slices.chat.value.currentSessionId)
            val focus = resyncUiSnapshot.currentSessionId
            val preRefreshLocalAll = repo.snapshotSlimSseState().keys
            val preRefreshSessions = slices.sessionList.value.sessions.map { it.id }.toSet()

            // T11 round-3 (oracle I1 — dirty overlay wiring fix): the
            // coordinator reads its OWN dirty overlay (`sseSyncState.sessionsDirty`)
            // and unions it into the catch-up set. The Service-passed
            // [sessionsDirty] param is ALSO unioned (caller-supplied extra
            // dirty) but is no longer relied on as the sole dirty source —
            // round-2 had Service pass `emptySet()` here which silently
            // dropped every disconnected dirty sid. The coordinator owns the
            // overlay; it must read it directly. (Service passing emptySet
            // is now harmless.)
            val overlayDirty = sseSyncState.sessionsDirty

            // ── Step 2: metadata-only refresh (no open-session fetch) ───────
            // §session-scope-narrow: union currentWorkdir into the snapshot dirs so the
            // currently-viewed project is always in scope, even if addRecentWorkdir hasn't
            // persisted it yet (race / migration gap).
            val currentWd = settingsManager.currentWorkdir
            val recentWorkdirs = settingsManager.getRecentWorkdirs(currentServerGroupFp())
            val effectiveDirs = (recentWorkdirs + listOfNotNull(currentWd))
                .filter { it.isNotBlank() }
                .distinct()
            val snapshotDirectories = if (effectiveDirs.isNotEmpty()) effectiveDirs else directories

            val snapshotResult = repo.coldStartSlimSync(
                openSessionId = null,
                directories = snapshotDirectories,
                token = token,
            )
            val snapshot = snapshotResult.getOrNull()

            // C-D3 v2 §1.5/§1.12: a stale incarnation makes coldStartSlimSync
            // fail with StaleSlimCommitException (NOT collapse to null). The
            // outer Result.failure surface is identical to other transport
            // failures from the caller's perspective, but the token recheck
            // below catches it either way.
            if (!current()) {
                hadFailure = false
                return emptyMap()
            }

            if (snapshot == null) {
                DebugLog.w(
                    tag,
                    "performSlimResync metadata refresh failed: ${snapshotResult.exceptionOrNull()?.message} — " +
                        "falling back to pre-refresh known set",
                )
                // O-C weak-network §4: mark connection state as stale since we are
                // serving cached (pre-refresh) data.
                slices.mutateConnection { it.copy(stale = true) }
            } else {
                // ── Step 3: fold snapshot under the same entry token ───────
                if (!current()) {
                    hadFailure = false
                    return emptyMap()
                }
                // C-D3 v2 §3.6: token-gated snapshot fold. If the gate
                // rejects, the snapshot is dropped and we abort the sweep
                // (token superseded between cold-start commit and fold).
                // §Stage-B §3.4: resync snapshot is authoritative — fetched
                // messages are the final view, owned streaming parts cleared.
                if (!applySlimColdStartSnapshot(snapshot, token, authoritative = true)) {
                    hadFailure = false
                    return emptyMap()
                }
                // O-C weak-network §4: refresh succeeded → clear stale flag.
                slices.mutateConnection { it.copy(stale = false) }
            }

            if (!current()) {
                hadFailure = false
                return emptyMap()
            }

            // ── Step 4: build catch-up set (oracle I1 union, slimmed) ────────
            // Slimmed catch-up: focus + dirty (overlay + caller-supplied).
            //
            // Pre-fix this ALSO unioned preRefreshLocalAll + preRefreshSessions
            // + refreshedSessions + postRefreshLocalAll — i.e. ≈ ALL ~150
            // sessions × ≤250 skeletons — which ran on Main.immediate and
            // blocked the user's session switch (loadMessagesForEffect waits
            // on this sweep). Those un-reconciled sessions are now left to
            // the on-demand path: when the user actually switches to them,
            // loadMessagesForEffect + the slim digest reconciliation corrects
            // their state lazily. focus + dirty must stay here because:
            //  - focus is the session the user is currently looking at (any
            //    gap there is immediately visible);
            //  - dirty marks sessions with a known SSE gap that the user
            //    might switch to next.
            //
            // (refreshedSessions / postRefreshLocalAll are still computed
            // below for the diagnostic DebugLog only — the size telemetry
            // stays useful and is NOT what made the sweep slow; the per-sid
            // reconcile in performResyncCatchUp was.)
            val refreshedSessions = snapshot?.sessions?.map { it.id }?.toSet() ?: emptySet()
            val postRefreshLocalAll = repo.snapshotSlimSseState().keys
            val catchUp = buildSet {
                focus?.let { add(it) }
                addAll(overlayDirty)
                addAll(sessionsDirty)
            }
            if (catchUp.isEmpty()) {
                hadFailure = false
                return emptyMap()
            }
            DebugLog.i(
                "Sync",
                "performSlimResync focus=$focus preRefreshLocal=${preRefreshLocalAll.size} " +
                    "preRefreshSessions=${preRefreshSessions.size} refreshed=${refreshedSessions.size} " +
                    "postRefreshLocal=${postRefreshLocalAll.size} overlayDirty=${overlayDirty.size} " +
                    "callerDirty=${sessionsDirty.size} union=${catchUp.size} deadlineMs=$perSidDeadlineMs",
            )

            // ── Step 5: per-sid reconcile sweep using the SAME entry token ─
            if (!current()) {
                hadFailure = false
                return emptyMap()
            }
            val result = performResyncCatchUp(
                catchUpSet = catchUp,
                perSidDeadlineMs = perSidDeadlineMs,
                token = token,
                isStillCurrent = isStillCurrent,
                snapshot = resyncUiSnapshot,
            )
            hadFailure = false
            return result
        } finally {
            finishResyncCadence(hadFailure)
        }
    }

    /**
     * Cluster A / Phase 2: merge [MessageWithParts] skeletons into the open
     * chat slice by messageID (patch-if-found + insert-if-absent for messages;
     * parts map overwritten per fetched id). Mirrors message.updated fold.
     *
     * T1b: routes through [AppAction.SlimMessagesMerged] → [mergeSlimMessages]
     * (1:1 with the pre-T1b private mutateChat loop).
     *
     * §Stage-B §3.4: [authoritative] threads the splice/merge contract — see
     * [mergeSlimMessages] + [isAuthoritativeSlimMerge].
     */
    private fun mergeSlimMessagesIntoChat(items: List<MessageWithParts>, authoritative: Boolean = false) {
        if (items.isEmpty()) return
        // T1d P1-2: slim-only state write — fail-fast in legacy mode.
        requireSlimOnlyStateWrite(isSlimMode(), "merge-slim-messages")
        slices.store.dispatch(cn.vectory.ocdroid.ui.AppAction.SlimMessagesMerged(items, authoritative))
    }

    /**
     * §Stage-B §3.4 (grok MF-1 caller decision rule): decide whether a slim
     * reconcile merge should be authoritative (fetched content wins, owned
     * streaming parts cleared) or skeleton (owned streaming parts preserved).
     *
     * Returns `true` (authoritative) when ANY of:
     *  - [forceAuthoritative] is set (explicit override).
     *  - [mode] is [ReconcileMode.RESYNC] (the resync sweep is a forced
     *    catch-up — fetched content is authoritative even if the session
     *    happens to be busy at merge time).
     *  - The session's status is unknown (`null`) — fail-safe: treat an
     *    unknown session as idle/authoritative.
     *  - The session is idle (`!isBusy && !isRetry`) — no active token
     *    stream to preserve.
     *
     * Returns `false` (skeleton / preserve) ONLY when the session is
     * actively busy or retrying (an in-flight token stream may own parts
     * whose streamed text must be preserved).
     */
    private fun isAuthoritativeSlimMerge(
        mode: ReconcileMode,
        sid: String,
        sessionStatuses: Map<String, SessionStatus>,
        forceAuthoritative: Boolean = false,
    ): Boolean = forceAuthoritative || mode == ReconcileMode.RESYNC || run {
        val st = sessionStatuses[sid]
        st == null || (!st.isBusy && !st.isRetry)
    }

    /**
     * Cluster A / Phase 2 (P2.4 / P2.5): fold a [SlimColdStartSnapshot] (from
     * [OpenCodeRepository.coldStartSlimSync] — also the resync path) into the
     * UI slices. Called by [cn.vectory.ocdroid.service.SessionStreamingService]
     * after a successful cold-start / resync fetch. Failures are partial
     * (null pieces — see T11 round-2 / oracle D2 below); null messages means
     * no open-session fetch was requested.
     *
     * # T11 round-2 (oracle D2 — typed null/empty outcomes)
     *
     * The metadata pieces (`sessions` / `questions` / `permissions`) are
     * NULLABLE on the snapshot:
     *
     *  - `null` → fetch FAILED. Keep the prior list (cannot tell "server
     *    authoritative empty" from "server unreachable").
     *  - `emptyList()` → fetch succeeded with no entries. REPLACE the prior
     *    list with empty (the server's authoritative view is "nothing").
     *  - non-empty → fetch succeeded; replace prior list.
     *
     * Pre-T11-round-2 the snapshot folded null→emptyList, which made it
     * impossible to clear stale rows when the server genuinely returned an
     * empty list. The nullable shape fixes this.
     */
    fun applySlimColdStartSnapshot(snapshot: SlimColdStartSnapshot): Boolean {
        // T1d P1-1: fail-fast before any repo / token / fold work when not slim.
        requireSlimOnlyStateWrite(isSlimMode(), "cold-start-snapshot")
        val repo = repository ?: return false

        // C-D3 v2 §3.6: token-gated snapshot fold. The token is captured
        // at this entry; if the caller already has a workflow token
        // (performSlimResync), it routes through the token-taking overload
        // below.
        return applySlimColdStartSnapshot(snapshot, repo.captureSlimCommitToken())
    }

    /**
     * C-D3 v2 §3.6: token-aware cold-start snapshot fold. Returns `false`
     * when the gate rejects (caller MUST abort the surrounding workflow
     * — e.g. [performSlimResync] returns emptyMap). Returns `true` when
     * the snapshot landed (or was a no-op for null pieces).
     *
     * All slice + effect commits run inside ONE
     * [commitIfSlimTokenCurrent] atomic region so a configure() rotation
     * between cold-start fetch return and slice commit cannot write a
     * stale snapshot under a new host.
     *
     * §Stage-B §3.4: [authoritative] controls the messages-merge contract
     * — default `false` (cold-start skeleton: preserve any in-flight token-
     * stream owned parts). Resync / watchdog callers pass `true` (the
     * snapshot's messages are the authoritative final view).
     */
    fun applySlimColdStartSnapshot(
        snapshot: SlimColdStartSnapshot,
        token: OpenCodeRepository.SlimCommitToken,
        authoritative: Boolean = false,
    ): Boolean {
        // T1d P1-1: same entry guard on the token-taking overload (direct
        // callers / tests must not bypass via token path).
        requireSlimOnlyStateWrite(isSlimMode(), "cold-start-snapshot")
        val repo = repository ?: return false

        return repo.commitIfSlimTokenCurrent(token) {
            DebugLog.i(
                "Sync",
                "applySlimColdStartSnapshot sessions=${snapshot.sessions?.size ?: "null"} " +
                    "questions=${snapshot.questions::class.simpleName} " +
                    "permissions=${snapshot.permissions::class.simpleName} " +
                    "messages=${snapshot.messages?.size ?: "null"} " +
                    "complete=${snapshot.complete} discoveryDirectories=${snapshot.discoveryDirectories} " +
                    "discoveryReady=${snapshot.discoveryReady}",
            )

            // O-C weak-network §1: when the server marks the snapshot as
            // incomplete (X-Complete: false), the sidecar couldn't assemble the
            // full snapshot on a flaky/lossy network. In that case, DO NOT
            // full-replace (or even merge) — keep the existing page entirely
            // to avoid wiping the user's current view with a partial snapshot.
            if (snapshot.complete == false) {
                DebugLog.w(
                    "Sync",
                    "applySlimColdStartSnapshot snapshot.complete=false — keeping prior page intact",
                )
                return@commitIfSlimTokenCurrent
            }

            val sessions = snapshot.sessions
            if (sessions != null) {
                // rev-F: if discoveryReady == false, treat empty/null sessions as
                // "not ready" — do NOT authority-empty wipe the session list.
                val isDiscoveryReady = snapshot.discoveryReady ?: true
                if (isDiscoveryReady || sessions.isNotEmpty()) {
                    // C-D3 v2 §3.6 (sessions-merge fix): fold the fetched
                    // sessions with MERGE semantics, mirroring the retain-prior
                    // pattern used by `applyAggregationOutcome`'s Success branch.
                    //
                    // Root cause this guards against: the slim cold-start fetch
                    // (coldStartSlimSync / SSE first-frame) does NOT pass a
                    // session limit, and the sidecar defaults to returning only
                    // the most recent 100 sessions. A FULL REPLACE here would
                    // therefore substitute the prior list (e.g. 374 sessions
                    // accumulated across directories) with just those 100 —
                    // making the entire session list "vanish" on cold start /
                    // SSE reconnect. The merge below restricts the fetched
                    // payload to overwriting ONLY the directories it actually
                    // covers; sessions in every other directory survive.
                    //
                    // `directory` is non-null on Session (data class), so the
                    // `mapNotNull` / null-directory defensive filters are a no-op
                    // today; left in place to stay correct if the field ever
                    // becomes nullable.
                    val byDirectory = sessions.groupBy { it.directory }
                    val fetchedDirs = sessions.mapNotNull { it.directory }.toSet()
                    slices.mutateSessionList { s ->
                        if (fetchedDirs.isEmpty()) {
                            // Defensive: fetched sessions carry no directory
                            // (legacy / malformed payload) → fall back to the
                            // historical FULL REPLACE so we don't silently pin
                            // the entire list to stale entries.
                            s.copy(
                                sessions = sessions,
                                directorySessions = byDirectory,
                            )
                        } else {
                            val priorKept = s.sessions.filter { it.directory == null || it.directory !in fetchedDirs }
                            val mergedSessions = (priorKept + sessions).distinctBy { it.id }
                            val mergedByDir = s.directorySessions.filterKeys { it !in fetchedDirs } + byDirectory
                            s.copy(
                                sessions = mergedSessions,
                                directorySessions = mergedByDir,
                            )
                        }
                    }
                }
                // If isDiscoveryReady=false && sessions.isNotEmpty(), the merge
                // above still applies (non-empty sessions are merged normally).
                // The !isDiscoveryReady guard only prevents emptiness-triggered wipe.
            }

            // I-2: questions + permissions use typed aggregation outcome.
            slices.mutateSessionList { s ->
                val sessionsById = allSessionsById(s.sessions, s.directorySessions, s.childSessions)

                val questionFold = applyAggregationOutcome(
                    prior = s.pendingQuestions,
                    outcome = snapshot.questions,
                    wireToUi = SlimapiQuestionEntry::toQuestionRequest,
                    uiId = QuestionRequest::id,
                    uiDirectory = QuestionRequest::directory,
                )

                val permissionFold = applyAggregationOutcome(
                    prior = s.pendingPermissions,
                    outcome = snapshot.permissions,
                    wireToUi = SlimapiPermissionEntry::toPermissionRequest,
                    uiId = PermissionRequest::id,
                    uiDirectory = PermissionRequest::directory,
                )

                s.copy(
                    pendingQuestions = filterArchivedSessionQuestions(
                        questionFold.items,
                        sessionsById,
                    ),
                    pendingPermissions = permissionFold.items,
                    questionAggregationSignal = questionFold.signal,
                    permissionAggregationSignal = permissionFold.signal,
                )
            }

            val msgs = snapshot.messages
            if (msgs != null) {
                mergeSlimMessagesIntoChat(msgs, authoritative)
            }

            // I-2: a whole-call Failure surfaces a toast. Partial
            // incompleteness is observable via the signal — no toast on
            // every resync.
            if (snapshot.questions is SlimAggregationOutcome.Failure) {
                effects.tryEmitUiEvent(
                    UiEvent.Error(R.string.error_slim_questions_fetch_failed)
                )
            }
            if (snapshot.permissions is SlimAggregationOutcome.Failure) {
                effects.tryEmitUiEvent(
                    UiEvent.Error(R.string.error_slim_permissions_fetch_failed)
                )
            }
        }
    }

    companion object {
        /**
         * §M5 trailing-coalesce window (§7). Leading-edge
         * delta writes immediately; subsequent deltas within this window are
         * batched into one flush → one Compose recomposition per window instead
         * of one per token.
         */
        private const val DELTA_COALESCE_MS = 100L

        /**
         * T11 round-2 (oracle I5): stripe count for [reconcileStripes].
         * 64 stripes balances collision rate (~1.5% for 1k distinct sids
         * under uniform hashing) against fixed memory (64 Mutex objects
         * = ~2.3KB). The oracle design pins this value.
         */
        internal const val STRIPES = 64
    }
}

/**
 * Clears token-stream ownership state for the given [partIds].
 * If [partIds] is empty, returns [this] unchanged.
 * Removes matching entries from both [streamingPartTexts] and [streamOwned].
 */
internal fun ChatState.clearTokenStreamState(partIds: Set<String>): ChatState {
    if (partIds.isEmpty()) return this
    return copy(
        streamingPartTexts = streamingPartTexts - partIds,
        streamOwned = streamOwned - partIds,
    )
}
