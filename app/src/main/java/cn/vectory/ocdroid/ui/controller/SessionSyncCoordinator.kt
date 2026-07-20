package cn.vectory.ocdroid.ui.controller

import android.util.Log
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
import cn.vectory.ocdroid.ui.SessionListState
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
import cn.vectory.ocdroid.util.STREAMING_FLICKER_DEBUG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
@Suppress("DEPRECATION")
class SessionSyncCoordinator(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
    private val settingsManager: SettingsManager,
    private val effects: SharedEffectBus,
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
    internal val statusAggregatorInput: StatusAggregatorInput? = null,
    /**
     * CP4 (notify Phase-0): the single clock used for SSE arrival timestamps
     * passed to [StatusAggregatorInput.applySseStatus]. Defaults to wall-clock
     * millis — the SAME clock domain as `StatusAggregatorImpl`'s injected
     * `clock`, so merge-timing comparisons inside the aggregator are
     * consistent. Test-only override (the existing tests do not assert on
     * arrival times; the default is fine).
     */
    internal val clock: () -> Long = { System.currentTimeMillis() },
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
    private val repository: OpenCodeRepository? = null,
) {
    /** Tag for [reportNonFatalIssue]; mirrors the original MainViewModel TAG. */
    private val tag: String = "SessionSyncCoordinator"
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

    private fun stripeFor(sid: String): Mutex {
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
            val (nextState, decisions) = reconcileGap(sseSyncState, trigger)
            sseSyncState = nextState
            applySseSyncDecisions(decisions)
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
    private fun applySseSideEffects(sideEffects: List<SseSideEffect>) {
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
                // Per-token streaming events for the current session: 1Hz coalesce.
                // Counts ONLY deltas for the current session (non-current are not
                // rendered and carry no signal). No per-event string allocation —
                // just an int increment + timestamp check.
                (t == "message.part.delta" || t == "message.part.updated") &&
                    evtSid != null && evtSid == currentSid -> {
                    val now = System.currentTimeMillis()
                    if (verboseSseDeltaCount == 0 || now - verboseSseDeltaFirstAt >= 1000L) {
                        // New window: flush any pending prior-window summary first
                        // (defensive — should be empty here since non-delta events
                        // flush on their way through), then open a fresh window.
                        flushVerboseSseDeltaWindow()
                        verboseSseDeltaFirstAt = now
                        verboseSseDeltaCount = 1
                        verboseSseDeltaSid = currentSid
                    } else {
                        verboseSseDeltaCount++
                    }
                }
                // All other event types — flush any pending delta window first so
                // the viewer's chronological order is preserved, then log this
                // event if it's sid-less OR for the current session.
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
                // else: non-current session's non-delta event — skip (no signal).
            }
        }
        // Throttle dispatch logging to preserve the 1000-entry ring buffer's signal.
        // Skipped (noise): server.heartbeat (periodic), message.part.delta (per-token
        // during streaming — its render IS the proof), server.connected (fires on
        // every reconnect), and plugin.added / catalog.updated / integration.updated
        // (server-internal bursts that fire in large flurries when a run starts,
        // e.g. when another client sends a message). Logging-only — dispatch is
        // unchanged. All other types (message.created/updated, session.*,
        // permission/question, todo, connection) are logged — they are the actual
        // sync signal.
        val type = event.payload.type
        val evtSession = event.payload.getString("sessionID") ?: "-"
        val noisy = type in NOISY_SSE_LOG_EVENTS
        if (!noisy) {
            DebugLog.d("Sync", "dispatch $type session=$evtSession current=${slices.chat.value.currentSessionId}")
        }
        when (event.payload.type) {
            "session.created" -> {
                val created = parseSessionCreatedEvent(event)
                if (created != null) {
                    slices.mutateSessionList { s -> s.applySessionCreated(created.session).first }
                } else {
                    applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid session.created payload")))
                }
            }
            "session.updated" -> {
                val updated = parseSessionUpdatedEvent(event)
                if (updated != null) {
                    if (updated.isArchived) {
                        // Archived (typically by another client): evict the
                        // id from the open-tabs list and persist, mirroring
                        // the user-triggered archive path. If the archived
                        // session is the currently-open one, also clear
                        // currentSessionId + messages so the chat view falls
                        // back to the empty state instead of lingering on an
                        // archived session whose tab has disappeared.
                        val newOpenIds = slices.sessionList.value.openSessionIds.filter { id -> id != updated.id }
                        if (newOpenIds != slices.sessionList.value.openSessionIds) {
                            settingsManager.openSessionIds = newOpenIds
                        }
                        // §A5-3 Phase B2: the pre-B2 dual
                        // mutateSessionList(applyArchiveEviction) +
                        // mutateChat(applyArchivedChatClear) (the latter only
                        // when the archived session WAS the current one) is
                        // collapsed into ONE atomic dispatch. The reducer
                        // derives the "clear chat" decision from the snapshot
                        // (chat.currentSessionId == updated.id), so the
                        // action carries pure data only — no clearChat
                        // boolean. The applyArchiveEviction + (conditional)
                        // applyArchivedChatClear helpers are reused unchanged
                        // inside [reduce]. ONE committed aggregate state →
                        // no torn "sessionList archived but
                        // chat.currentSessionId still references it"
                        // intermediate for stateFlow collectors.
                        slices.store.dispatch(
                            cn.vectory.ocdroid.ui.AppAction.SessionArchived(
                                session = updated,
                                openSessionIds = newOpenIds,
                            )
                        )
                        // R-20 Phase 1 (plan §3 矩阵 "SSE 归档 session" 行):
                        // evict the archived session from both the memory LRU
                        // and the persistent cache. The fp comes from the
                        // currentServerGroupFp provider (the SSE feed is
                        // generation-guarded by ConnectionCoordinator so a
                        // stale-host event cannot reach here — see
                        // §P1-10 hostGeneration guard). Routed through the
                        // effect bus so AppCore.dispatchHostEffect runs the
                        // memory + persistent halves.
                        effects.tryEmitEffect(
                            ControllerEffect.EvictSession(currentServerGroupFp(), updated.id)
                        )
                    } else {
                        slices.mutateSessionList { s -> s.applySessionUpsert(updated).first }
                    }
                } else {
                    applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid session.updated payload")))
                }
            }
            "session.status" -> {
                // §Phase1a instrumentation (Issue 4): capture the FULL raw properties
                // JSON so we can confirm which fields actually arrive for retry
                // (type/attempt/message/next + any `action` sub-object). Parser/model
                // is Phase 4 — for now, raw toString() of the whole JsonObject.
                DebugLog.d("Retry", "session.status raw properties=${event.payload.properties?.toString() ?: "null"}")
                val statusEvent = parseSessionStatusEvent(event)
                if (statusEvent != null) {
                    // CP4 (notify Phase-0): feed the authoritative status aggregator
                    // BEFORE the existing fold. The aggregator's input surface takes
                    // the composite key `(serverGroupFp, workdir, sessionId)` and the
                    // SSE arrival time `clock()` (same clock domain as the impl's
                    // injected clock — merge timing inside the aggregator is consistent).
                    // The identity gate already ran in [handleEvent(IdentifiedSseEvent)]
                    // (CP1 isCurrent check) before this dispatch, so the current identity
                    // is the live one — using [currentServerGroupFp] here matches the
                    // session.updated archived branch's pattern.
                    //
                    // Unknown sessionId (not in sessionsById) → silently skipped
                    // (matches the existing §unread-lifecycle "unknown id" exclusion;
                    // we cannot build a composite key without the workdir).
                    val aggregatorInput = statusAggregatorInput
                    if (aggregatorInput != null) {
                        val sessionsByIdNow = allSessionsById(
                            slices.sessionList.value.sessions,
                            slices.sessionList.value.directorySessions,
                            slices.sessionList.value.childSessions,
                        )
                        val target = sessionsByIdNow[statusEvent.sessionId]
                        if (target != null) {
                            val key = SessionStatusKey(
                                serverGroupFp = currentServerGroupFp(),
                                workdir = target.directory,
                                sessionId = statusEvent.sessionId,
                            )
                            aggregatorInput.applySseStatus(
                                key,
                                statusEvent.status.toSessionBusyStatus(),
                                sourceTimeMs = clock(),
                            )
                        }
                    }
                    // §unread-soak (main): the prior busy→idle edge capture
                    // (oldStatus / wasBusy / nowIdle) is GONE — the instant marking
                    // it fed is now owned by the [UnreadSoakController] sweep + the
                    // pure [evaluateUnread] evaluator. This branch only folds the new
                    // status into sessionStatuses so the evaluator sees it. (The CP4
                    // aggregator feeding above is independent of that edge capture.)
                    // §streaming-state-sync-diag (runtime-gated, scoped+dedup):
                    // record the OLD type immediately before the sessionStatuses
                    // write so we can confirm whether the optimistic busy gets
                    // overwritten by a stale idle. Scope to the current session
                    // AND log only on actual transition (idle→idle is the
                    // dominant flood source — skip it entirely).
                    if (cn.vectory.ocdroid.util.DebugLog.verboseDiagEnabled) {
                        val diagSid = statusEvent.sessionId
                        val diagNewType = statusEvent.status.type
                        val diagOldType = slices.sessionList.value.sessionStatuses[diagSid]?.type
                        if (diagSid == slices.chat.value.currentSessionId && diagOldType != diagNewType) {
                            cn.vectory.ocdroid.util.DebugLog.d(
                                "StatusDiag",
                                "session.status write sid=$diagSid oldType=$diagOldType newType=$diagNewType",
                            )
                        }
                    }
                    slices.mutateSessionList {
                        it.applySessionStatus(statusEvent.sessionId, statusEvent.status).first
                    }
                    // §cross-client-sync: when the CURRENT session goes busy, a run
                    // just started — i.e. a message was sent, possibly from ANOTHER
                    // client (e.g. the web UI). This is a belt-and-suspenders reload
                    // that catches messages emitted before the message.updated
                    // insert-if-absent path (below) has run / before the local view
                    // is subscribed — the primary surfacing path is now message.updated
                    // insert-if-absent (mirrors the web client). Debounced via
                    // loadMessagesWithRetry's 400ms delay + isLoadingMessages
                    // coalescing; the user message is persisted server-side before
                    // the run starts. The overlay-clear in launchLoadMessages is
                    // gated on !busy, so this does NOT disrupt the streaming overlay.
                    //
                    // §R-19 Sprint 3 P2-4: cross-slice effects (depend on chat /
                    // unread state, not just SessionListState) are computed here
                    // and routed through applySseSideEffects — the single side-
                    // effect routing point. applySessionStatus itself returns
                    // emptyList() (its state transform is pure same-slice).
                    val statusEffects = mutableListOf<SseSideEffect>()
                    val chatSnap = slices.chat.value
                    val isCurrent = statusEvent.sessionId == chatSnap.currentSessionId
                    // §unread-soak: unread marking is NO LONGER done here on
                    // the instant busy→idle edge. The foreground
                    // [UnreadSoakController] sweep + the pure [evaluateUnread]
                    // evaluator now own the population logic — a root becomes
                    // unread ONLY when (a) it AND all descendants are idle,
                    // (b) that all-idle state persisted ≥10s, (c) it is not the
                    // current session, AND (d) it was not viewed since idle.
                    // This branch still updates sessionStatuses (via
                    // applySessionStatus above) so the evaluator sees fresh
                    // statuses; [applyMarkSessionUnread] remains the
                    // low-level marker (now called only by the sweep).
                    if (statusEvent.status.isBusy && isCurrent) {
                        DebugLog.i("Sync", "session.status busy (current) → reload (cross-client message sync)")
                        statusEffects.add(SseSideEffect.ReloadMessages(statusEvent.sessionId, resetLimit = true))
                    }
                    // SSE-trust model: session.status (busy/idle) only updates the
                    // status badge. It does NOT reload or clear streaming buffers
                    // (except the busy-current and idle-current finalization paths).
                    // The finalized turn text is carried by streamingPartTexts
                    // until a foreground catch-up reconciles the persisted message
                    // list — mirroring opencode-web.
                    //
                    // §append-safe finalization (gpter MAJOR): the overlay-clear in
                    // launchLoadMessages is gated on !busy, so if the last reload
                    // happened while busy (overlay preserved), the overlay could
                    // linger after the run settles. When the CURRENT session goes
                    // idle with a non-empty overlay, reconcile against the now-
                    // persisted authoritative window (loadMessagesWithRetry delays
                    // internally so the server has time to persist the finalized
                    // part text; status is now idle so the gated clear will run).
                    //
                    // §R-19 fix Blocker 2 v2: no sessionsDirty dedup — idle-driven
                    // reloads ALWAYS fire based on overlay state; overlapping
                    // reloads coalesce at the scheduling layer (isLoadingMessages
                    // check-and-set + 400ms debounce).
                    if (statusEvent.status.isIdle && isCurrent) {
                        val overlayNonEmpty = chatSnap.streamingPartTexts.isNotEmpty() || chatSnap.streamingReasoningPart != null
                        DebugLog.d("Sync", "session.status idle → ${if (overlayNonEmpty) "reload" else "no-op"}")
                        if (overlayNonEmpty) {
                            statusEffects.add(SseSideEffect.ReloadMessages(statusEvent.sessionId, resetLimit = true))
                        }
                    }
                    applySseSideEffects(statusEffects)
                } else {
                    applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid session.status payload")))
                }
            }
            "message.created" -> {
                // NOTE: server 1.17.11+ does NOT emit message.created (only
                // message.updated / message.part.*). This branch is retained for
                // FORWARD COMPATIBILITY if a future server version adds it; today
                // it is effectively dead code. New messages are surfaced by the
                // message.updated insert-if-absent path above and the
                // session.status busy reload.
                //
                // §unread-lifecycle Task 3: unread is produced SOLELY by the
                // session.status root busy→idle path (lifecycle completion).
                // This branch keeps only its time.updated bump + current-session
                // reload for forward-compat; it no longer marks unread.
                val sessionId = event.payload.getString("sessionID")
                val isCurrent = sessionId != null && sessionId == slices.chat.value.currentSessionId
                DebugLog.i("Sync", "message.created: ${if (isCurrent) "reload current" else "no-op (unread is lifecycle-driven)"}")

                // §recent-sort-by-message: forward-compat parity with the
                // message.updated branch — bump the session's time.updated to
                // max(current, info.time.created) so the recent-sessions list
                // reorders on a new message even if a future server emits
                // message.created instead of message.updated.
                if (sessionId != null) {
                    val createdInfo = event.payload.getJsonObject("info")?.let {
                        runCatching {
                            lenientJson.decodeFromJsonElement<Message>(it)
                        }.getOrNull()
                    }
                    val msgCreated = createdInfo?.time?.created ?: 0L
                    if (msgCreated > 0L) {
                        slices.mutateSessionList { s ->
                            s.applyMessageTimestampBump(sessionId, msgCreated).first
                        }
                    }
                }

                // §R-19 Sprint 3 P2-4: side effects formalized as SseSideEffect list.
                // Only the current session reloads; non-current sessions do NOT
                // mark unread here (see the session.status busy→idle path).
                val msgEffects = mutableListOf<SseSideEffect>()
                if (sessionId != null && sessionId == slices.chat.value.currentSessionId) {
                    msgEffects.add(SseSideEffect.ReloadMessages(sessionId, resetLimit = true))
                }
                applySseSideEffects(msgEffects)
            }
            "message.updated" -> {
                // SSE-trust: patch the message metadata in-place from the server's
                // authoritative `info` (mirrors opencode-web server-session.ts:706).
                // Server payload confirmed to carry a full { info: Message } object.
                // We replace the matching Message in the split `messages` store
                // (List<Message>); its parts live separately in partsByMessage and
                // are NOT touched.
                //
                // §cross-client-sync (server 1.17.11+): the server emits
                // `message.updated` (NOT `message.created`) for NEW messages. So
                // when the message id is ABSENT from the local list we INSERT it
                // (append — the list is oldest-first and the new message is the
                // newest). This mirrors the oc-ref web client's
                // patch-if-found + insert-if-absent handler. Subsequent updates for
                // the same id find it in the list and patch in place, so this is a
                // once-per-new-id op (no storm — pure state update, no I/O, no
                // reload). The session.status busy → reload path is the parallel
                // belt-and-suspenders for messages emitted before the local view
                // is even subscribed.
                // Defensive session guard: only touch the current session's chat
                // view (the slice mutation below).
                val eventSessionId = event.payload.getString("sessionID")
                val infoJson = event.payload.getJsonObject("info")
                val updated = infoJson?.let {
                    runCatching {
                        lenientJson.decodeFromJsonElement<Message>(it)
                    }.getOrNull()
                }

                // §recent-sort-by-message: bump the owning session's time.updated
                // to max(current, message.time.created) on every message event so
                // the "recent sessions" surfaces (SessionsScreen recent +
                // SessionPickerSheet recent, both sortedByDescending { time.updated })
                // reflect actual message activity, not just server-side session
                // metadata timestamps. The bump applies to BOTH current and non-
                // current sessions (cross-client messages from another client
                // surface in the recent list). withUpdatedAtLeast is idempotent /
                // monotonic, so repeated patches for the same message are a no-op
                // (no thrash) and out-of-order / replay events never go backwards.
                if (eventSessionId != null && updated != null) {
                    val msgCreated = updated.time?.created ?: 0L
                    if (msgCreated > 0L) {
                        slices.mutateSessionList { s ->
                            s.applyMessageTimestampBump(eventSessionId, msgCreated).first
                        }
                    }
                }

                // Defensive session guard: only touch the current session's chat view.
                if (eventSessionId != null && eventSessionId != slices.chat.value.currentSessionId) return
                if (updated != null && updated.id.isNotEmpty()) {
                    // §R-17 batch5: pure transform returns (newState, found).
                    // Single O(n) scan inside the atomic update — `found` is
                    // set during the same `map` pass that builds the
                    // replacement list, so the patch-vs-insert decision and
                    // the found flag come from one atomic pass (no TOCTOU, no
                    // second `.none{}` scan). When found, patch in place;
                    // when NOT found, append (insert) the new message at the
                    // tail (oldest-first list).
                    var found = false
                    slices.mutateChat { c ->
                        val (next, wasFound) = c.applyMessageUpdated(updated)
                        found = wasFound
                        next
                    }
                    if (found) {
                        DebugLog.d("Sync", "message.updated: patched")
                    } else {
                        DebugLog.i("Sync", "message.updated: inserted (new message, absent from local list)")
                        // remove-message-persistence Task 3 (grilling 假设1
                        // 修正): the new message was appended to the slice.
                        // Mirror it into the IN-MEMORY sessionWindowCache so
                        // a subsequent switch-back (VerifyAndHydrate → peek
                        // hit) sees it without a re-fetch. The OLD path did
                        // a fire-and-forget `cacheRepository
                        // .appendMessageIfSessionCached` suspend write to
                        // SQLite; that target is replaced by an effect bus
                        // hop to AppCore → SessionSwitcher
                        // .appendMessageIfCached (in-memory LRU only — no IO).
                        // Why keep the append at all (vs deleting the SSE
                        // cache write outright): if the user is away from
                        // this session and >initialMessagePageSize (40) new
                        // messages arrive, the切回 latest-40 fetch +
                        // olderKept merge would drop the middle (olderKept
                        // excludes messages newer than oldestFetched but
                        // absent from the fetch). The pure-memory append
                        // closes that loss window without re-introducing IO.
                        // Append is scoped to the CURRENT session
                        // (eventSessionId was guarded above to equal
                        // currentSessionId); appendMessageIfCached is a
                        // no-op when no cached window is resident.
                        effects.tryEmitEffect(
                            ControllerEffect.AppendMessageToCache(
                                serverGroupFp = currentServerGroupFp(),
                                sessionId = eventSessionId!!, // guarded non-null above
                                message = updated,
                                // §parts-by-message: the new message has no
                                // parts yet (its first part.created /
                                // part.updated will arrive separately).
                                // Persist an empty list; the next part event
                                // + the post-turn idle reload fills them in.
                                parts = emptyList(),
                            )
                        )
                    }
                }
            }
            "message.part.updated" -> {
                val deltaEvent = parseMessagePartDeltaEvent(event) ?: return
                if (deltaEvent.sessionId == slices.chat.value.currentSessionId) {
                    val msgId = deltaEvent.messageId
                    val pId = deltaEvent.partId
                    if (msgId != null && pId != null) {
                        val key = pId
                        // §user-part-guard (flicker root cause, secondary): the
                        // server emits message.part.updated for the USER message
                        // too — the user input text is reflected as a part event
                        // (type=text). Treating it as streaming assistant output
                        // pollutes streamingPartTexts with a partId that belongs
                        // to the user message and injects a placeholder into the
                        // user bubble, which (a) misleads the assistant's
                        // isStreaming guard and (b) can render echoed text in the
                        // user's own bubble. Only ASSISTANT output streams — skip
                        // parts whose owning message is a user message. The user
                        // message is always inserted before its part event, so
                        // the lookup is reliable; an unknown msgId falls through
                        // (it is the assistant message being born).
                        val ownerIsUser = slices.chat.value.messages.any { it.id == msgId && it.isUser }
                        if (ownerIsUser) return
                        val fullText = deltaEvent.text
                        val delta = deltaEvent.delta
                        // §reasoning-routing-fix (symptom: reasoning rendered as
                        // 正文 / main body text). The server creates a reasoning
                        // part via part.updated with type=reasoning but BLANK text
                        // (full=0) BEFORE streaming its content via part.delta.
                        // The blank creation event used to fall through the
                        // fullText/delta gates below (both require non-blank
                        // content), so the type=reasoning info was LOST. The
                        // subsequent part.delta events then injected a placeholder
                        // using their `field` — which the server sets to "text"
                        // even for reasoning content — so the reasoning part became
                        // type=text and rendered as 正文, and streamingReasoningPart
                        // was never set (no thinking card). Record the part's TRUE
                        // type at creation here (inject correctly-typed placeholder
                        // + set streamingReasoningPart) so content routes to the
                        // standalone thinking card. Idempotent: once the part
                        // exists with the correct type this is a no-op.
                        val pType = deltaEvent.partType
                        if (isStreamablePartType(pType)) {
                            val existingParts = slices.chat.value.partsByMessage[msgId]
                            val hasCorrectType = existingParts?.any { it.id == pId && it.type == pType } == true
                            if (!hasCorrectType) {
                                slices.mutateChat { c ->
                                    c.applyPartCreatedPlaceholder(pType, pId, msgId, deltaEvent.sessionId).first
                                }
                                // §streaming-flicker-diagnosis (Top1): the placeholder
                                // Part (text=null) is now in partsByMessage but the first
                                // delta/fullText has NOT yet been staged into
                                // streamingPartTexts — this is the two-phase-mutation
                                // intermediate state. inStreamingTexts is expected to be
                                // false here; if Compose snapshots between this mutation
                                // and the leading-edge write below, the ChatMessageContent
                                // filter (Top1) drops the whole message → blank frame.
                                if (STREAMING_FLICKER_DEBUG) {
                                    val inStreamingTexts = key in slices.chat.value.streamingPartTexts
                                    Log.w(
                                        FLICKER_TAG,
                                        "placeholder created partId=$key msgId=$msgId inStreamingTexts=$inStreamingTexts"
                                    )
                                }
                            }
                        }
                        if (!fullText.isNullOrBlank()) {
                            // Server sent full accumulated text — use it as the
                            // authoritative streaming value (REPLACE, not append;
                            // acts as a sync point). §Site1-coalesce: mirror the
                            // Site2 (delta) leading-edge + trailing
                            // DELTA_COALESCE_MS coalesce pattern. Some Site1
                            // servers emit fullText per-token; without coalescing
                            // each event recomposed. Leading edge writes
                            // immediately (zero-latency first-token feel) + sets
                            // streamingReasoningPart once + ensures the placeholder
                            // part; subsequent fullText events within the window
                            // buffer into fullTextBuffer (REPLACE — fullText is
                            // authoritative accumulated text, only the latest value
                            // matters) and flush in one state write.
                            if (flushJobs[key]?.isActive != true) {
                                slices.mutateChat { c ->
                                    c.applyPartFullTextLeadingEdge(
                                        partId = key,
                                        fullText = fullText,
                                        partType = deltaEvent.partType,
                                        pId = pId,
                                        msgId = msgId,
                                        sessionId = deltaEvent.sessionId
                                    ).first.markFlushPending(key).first
                                }
                                scheduleDeltaFlush(key)
                                // §streaming-flicker-diagnosis (Top1): first frame
                                // (fullText) staged into streamingPartTexts — closes
                                // the intermediate-state window opened by the
                                // placeholder mutation above.
                                if (STREAMING_FLICKER_DEBUG) {
                                    Log.w(FLICKER_TAG, "first fullText staged partId=$key msgId=$msgId")
                                }
                            } else {
                                // Trailing coalesce: buffer the latest fullText
                                // (REPLACE). The pending DELTA_COALESCE_MS flush
                                // writes the buffered fullText in one state write.
                                // streamingReasoningPart was already set on this
                                // part's leading edge.
                                slices.mutateChat { c -> c.replaceFullTextBuffer(key, fullText).first }
                            }
                        } else if (!delta.isNullOrBlank()) {
                            // §flicker-fix: apply the same leading-edge +
                            // trailing DELTA_COALESCE_MS coalesce used by the
                            // `message.part.delta` handler. The server emits
                            // `message.part.updated` as the per-token streaming
                            // event (dozens–100s/sec); without coalescing each
                            // one wrote AppState and triggered a Compose
                            // re-parse/recompose, causing streaming jitter.
                            // Leading edge writes immediately (zero-latency
                            // first-token feel) + sets streamingReasoningPart
                            // once; subsequent deltas within the window buffer
                            // into deltaBuffer and flush in one batch.
                            if (flushJobs[key]?.isActive != true) {
                                slices.mutateChat { c ->
                                    c.applyPartDeltaLeadingEdge(
                                        partId = key,
                                        delta = delta,
                                        partType = deltaEvent.partType,
                                        pId = pId,
                                        msgId = msgId,
                                        sessionId = deltaEvent.sessionId
                                    ).first.markFlushPending(key).first
                                }
                                scheduleDeltaFlush(key)
                                // §streaming-flicker-diagnosis (Top1): first frame
                                // (delta) staged into streamingPartTexts — closes
                                // the intermediate-state window opened by the
                                // placeholder mutation above.
                                if (STREAMING_FLICKER_DEBUG) {
                                    Log.w(FLICKER_TAG, "first delta staged partId=$key msgId=$msgId")
                                }
                            } else {
                                // Trailing coalesce: buffer; the pending
                                // DELTA_COALESCE_MS flush appends the batch in
                                // one state write. streamingReasoningPart was
                                // already set on this part's leading edge.
                                slices.mutateChat { c -> c.appendDeltaBuffer(key, delta).first }
                            }
                        }
                        // Else: ids present + both text & delta null/blank =
                        // part status flip. Do NOT clear streaming buffers and
                        // do NOT reload — a foreground catch-up or the next
                        // message.updated / part.created reconciles if needed.
                    } else {
                        // 闪屏修复：part.created（part 对象只有 type 无 messageID/id）
                        // 在同一回合内每个新 part 都会发出（reasoning→text→tool…），
                        // 并非原先注释假设的"全新回合"。原先这里无条件清空
                        // streamingPartTexts + streamingReasoningPart，导致所有流式
                        // 气泡（reasoning 卡片、正文 text）瞬间坍缩为零高度，下一个
                        // 带 ID 的 part.updated 又重新填充 → 反复闪烁（用户观察：思考
                        // 卡片与正文气泡持续闪屏）。
                        //
                        // 修复：不再手动清空 overlay。reload(resetLimit=false) 本身保留
                        // streamingPartTexts/streamingReasoningPart（见 launchLoadMessages
                        // §append-safe MessageActions.kt:175），当前流式
                        // 气泡继续显示不坍缩。仅 clearDeltaBuffers() 丢弃旧 part 的 pending
                        // delta 缓冲（新 part 随后用权威 fullText 恢复，不丢数据）。下一
                        // 个带 ID 的 message.part.updated 正确更新流式状态；回合结束 idle
                        // finalization（resetLimit=true + streamingFinalized）正常清空 overlay。
                        //
                        // §R-19 Sprint 3 P2-4: side effects routed through applySseSideEffects.
                        clearDeltaBuffers()
                        applySseSideEffects(listOf(
                            SseSideEffect.ReloadMessages(deltaEvent.sessionId, resetLimit = false)
                        ))
                    }
                }
            }
            "message.part.delta" -> {
                // Web has an independent `message.part.delta` event (distinct from
                // `message.part.updated`): top-level { sessionID, messageID, partID,
                // field, delta }, field-level incremental append. Without this
                // handler, when the server emits this event the client loses all
                // streaming text. Payload shape per opencode-web server-session.ts.
                //
                // Phase 2 scope (docs/architecture-v3-sse-trust.md §246): only
                // accumulate into streamingPartTexts. Field-level in-place updates
                // on the Part object are deferred (needs field→property mapping).
                // Keyed by bare partId (S4: streamingPartTexts is rekeyed from
                // "msgId:partId" to partId, matching the UI consumers).
                val sessionId = event.payload.getString("sessionID") ?: return
                if (sessionId != slices.chat.value.currentSessionId) return
                // messageID required for a well-formed delta event (validation guard).
                val msgId = event.payload.getString("messageID") ?: return
                val partId = event.payload.getString("partID") ?: return
                // §user-part-guard (see message.part.updated): only assistant
                // output streams — skip deltas whose owning message is a user
                // message (the server reflects the user input as a part event).
                if (slices.chat.value.messages.any { it.id == msgId && it.isUser }) return
                // `field` defaults to "text"; it is the type hint for this
                // delta (e.g. "text", "reasoning"). Used as the placeholder
                // partType so a reasoning field-delta routes to ReasoningCard,
                // not a generic text bubble.
                val field = event.payload.getString("field") ?: "text"
                val delta = event.payload.getString("delta")
                if (!delta.isNullOrEmpty()) {
                    val key = partId
                    // §reasoning-routing-fix: prefer the part's KNOWN type (set by
                    // the part.updated creation event) over the delta's `field` —
                    // the server sends field="text" even for reasoning content.
                    val knownType = slices.chat.value.partsByMessage[msgId]
                        ?.firstOrNull { it.id == partId }?.type ?: field
                    // Only streamable types (text/reasoning) are rendered via
                    // streamingPartTexts; a non-streamable type's delta would
                    // accumulate dead overlay text never consumed by PartView.
                    if (!isStreamablePartType(knownType)) return
                    // §M5 delta coalescing: leading-edge
                    // immediate + trailing 100ms coalesce per partId. The FIRST
                    // delta for a partId writes straight to streamingPartTexts
                    // (zero-latency first-token feel); subsequent deltas within
                    // DELTA_COALESCE_MS are buffered and flushed in one batch,
                    // collapsing per-token Compose recompositions into one-per-
                    // window. Keyed by partId (S4: streamingPartTexts is rekeyed
                    // from "msgId:partId" to partId, matching UI consumers).
                    if (flushJobs[key]?.isActive != true) {
                        // Leading edge: write now, then open the coalesce window.
                        slices.mutateChat { c ->
                            c.applyPartDeltaLeadingEdge(
                                partId = key,
                                delta = delta,
                                knownType = knownType,
                                msgId = msgId,
                                sessionId = sessionId
                            ).first.markFlushPending(key).first
                        }
                        scheduleDeltaFlush(key)
                    } else {
                        // Trailing coalesce: buffer; the pending DELTA_COALESCE_MS
                        // flush will append the batch in one state write.
                        slices.mutateChat { c -> c.appendDeltaBuffer(key, delta).first }
                    }
                }
            }
            "permission.asked" -> {
                // §R-19 Sprint 3 P2-4: routed through applySseSideEffects.
                // Cluster A / Phase 2: when the slim SSE frame carries a
                // routeToken, fold it into pendingPermissions immediately so
                // Phase 3 reply can return it; still also refresh via the
                // aggregate REST path (authoritative reconcile).
                val slimPermission = parsePermissionAskedEvent(event)
                if (slimPermission != null && !slimPermission.routeToken.isNullOrBlank()) {
                    slices.mutateSessionList { s -> s.applyPermissionAsked(slimPermission).first }
                }
                applySseSideEffects(listOf(SseSideEffect.LoadPendingPermissions))
            }
            "question.asked" -> {
                // §Phase1c instrumentation (Issue 1): raw JSON for live-adherence
                // insurance (schema already confirmed: {id, sessionID, questions, tool},
                // no directory) — cheap parity check vs the confirmed contract.
                DebugLog.d("Question", "question.asked raw properties=${event.payload.properties?.toString() ?: "null"}")
                val question = parseQuestionAskedEvent(event)
                if (question != null) {
                    // §Phase1a→1c instrumentation: DebugLog moved here from inside
                    // applyQuestionAsked to keep that transform pure (see L1187 note).
                    val duplicate = slices.sessionList.value.pendingQuestions.any { it.id == question.id }
                    DebugLog.d(
                        "Question",
                        "applyQuestionAsked id=${question.id} sid=${question.sessionId} " +
                            "routeToken=${!question.routeToken.isNullOrBlank()} duplicate=$duplicate",
                    )
                    slices.mutateSessionList { currentState -> currentState.applyQuestionAsked(question).first }
                } else {
                    applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid question.asked payload")))
                }
            }
            // Cluster A / Phase 2: slim-only curated frame. Debounced 250ms /
            // session; properties ARE the SlimSessionDigest body (B1 unwrap).
            // applySlimDigest advances the per-session bookmark; a non-null
            // SlimFetchMessages decision triggers /since merge into the open
            // chat slice (messageID-dedup, mirrors message.updated).
            "session.digest" -> {
                handleSessionDigest(event)
            }
            "question.replied", "question.rejected" -> {
                val requestId = event.payload.getString("requestID")
                    ?: event.payload.getString("id")
                if (requestId != null) {
                    // §Phase1a→1c instrumentation: DebugLog moved here from inside
                    // applyQuestionResolved to keep that transform pure (see L1187 note).
                    DebugLog.d("Question", "applyQuestionResolved id=$requestId")
                    slices.mutateSessionList { currentState -> currentState.applyQuestionResolved(requestId).first }
                }
            }
            "todo.updated" -> {
                val sessionId = event.payload.getString("sessionID") ?: return
                val todosArray = event.payload.properties?.get("todos") as? kotlinx.serialization.json.JsonArray ?: return
                val todos = try {
                    Json.decodeFromJsonElement<List<TodoItem>>(todosArray)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid todo.updated payload: ${e.message}")))
                    return
                }
                slices.mutateSessionList { s -> s.applyTodoUpdated(sessionId, todos).first }
            }
            "session.error" -> {
                // §Phase1a instrumentation (Issue 4): capture the FULL raw properties
                // JSON (name/data/message/statusCode + anything else) so the retry/
                // error hydration parser in Phase 4 can be finalized against reality.
                DebugLog.w("Retry", "session.error raw properties=${event.payload.properties?.toString() ?: "null"}")
                // §error-feedback (Issue 4): the server emits session.error for
                // rate-limit / quota / provider failures. Two surfaces:
                //   (1) a UiEvent.Error toast (always — the user must know)
                //   (2) attach the error to the current session's last assistant
                //       message (if any non-error one exists) so ErrorCard can
                //       render inline. Error-only turns (error set, no renderable
                //       parts) are otherwise filtered out before render.
                //
                // §R-19 Sprint 3 P2-4: the UiEvent.Error emission is formalized
                // as SseSideEffect.SessionError. The chat-slice mutation (attach
                // error to last assistant message) stays inline — it's a same-
                // domain state transform, not a bus signal.
                //
                // Task 12 round-2 (slimapi v1 §G2 / T12-C1 + I2 + I3 fixes):
                //  - I3 (defensive dual-shape parsing): the ocdroid slim contract
                //    (docs/slimapi-client-impl-v1.md:43) declares slim session.error
                //    fields as TOP-LEVEL { sessionID?, directory?, name, message, at }.
                //    Legacy opencode emits NESTED { sessionID, error: { name, data:
                //    { message, statusCode } } }. The deployed slimapi sidecar's
                //    curated SSE set does NOT include session.error (problem-report-
                //    wip.md C-D8), so the actual wire shape is unverified. Try
                //    top-level first (contract truth), fall back to nested (legacy).
                //  - I2 (slim-mode gate): the canonical sessionErrorsById write is
                //    a NEW slim-only side effect. Legacy/non-slim session.error
                //    MUST stay byte-for-byte unchanged (toast + chat attachment
                //    only — no map write).
                //  - I1 (stripe routing): the map write goes through T11's
                //    per-sid stripe (stripeFor(sid).withLock) so it serializes
                //    against digest-driven lastError writes + the reconcile
                //    workflow (T12-C3 per-sid workflow serialization).
                //
                // Toast-suppression intent (round-1 Minor): toast is emitted in
                // BOTH sid and no-sid cases (preserves existing UX; existing
                // legacy tests depend on it). Brief C1's "no-sid → toast"
                // describes the FALLBACK (no map write when no sid), not an
                // exclusive route. The durable banner (slim-only, sid-required)
                // is the addition; the toast stays as the immediate UX in both.
                val props = event.payload.properties
                val errObj = props?.get("error") as? JsonObject
                // name: top-level first, fall back to nested error.name.
                val name = (props?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: (errObj?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content
                // data: nested-only (legacy MessageError shape — kept for the
                // chat-slice attachment's byte-for-byte preservation).
                val data = errObj?.get("data") as? JsonObject
                // message: top-level first (slim contract), then nested
                // error.data.message (legacy primary), then error.data.error
                // (legacy fallback), then error.message / error.error
                // (defensive — covers sidecars that omit the data wrapper).
                val rawMsg = (props?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: (data?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: (data?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: (errObj?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: (errObj?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: "Server session error"
                // at: top-level first, fall back to nested error.at.
                val at = (props?.get("at") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                    ?: (errObj?.get("at") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                applySseSideEffects(listOf(SseSideEffect.SessionError(name = name, rawMsg = rawMsg)))
                val sid = event.payload.getString("sessionID")
                    ?: (props?.get("sessionID") as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (sid != null && sid == slices.chat.value.currentSessionId) {
                    slices.mutateChat { c ->
                        val lastAssistant = c.messages.lastOrNull { it.isAssistant }
                        if (lastAssistant == null || lastAssistant.error != null) c
                        else c.copy(messages = c.messages.map { m ->
                            if (m.id == lastAssistant.id) m.copy(error = Message.MessageError(name = name, data = data)) else m
                        })
                    }
                }
                // T12-C1 (slim-only, sid-required): durable banner. The map
                // write is gated behind isSlimMode() (I2 — legacy non-slim
                // paths stay byte-for-byte unchanged) and routed through T11's
                // per-sid stripe (I1 — concurrent session.error + digest +
                // reconcile for the same sid serialize end-to-end, satisfying
                // T12-C3). No-sid frames fall through without touching the
                // map (toast is the only surface for session-less errors).
                if (sid != null && isSlimMode()) {
                    val banner = SlimSessionLastError(
                        name = name ?: "Unknown",
                        message = rawMsg,
                        at = at,
                    )
                    scope.launch {
                        stripeFor(sid).withLock {
                            slices.mutateSessionList { s ->
                                s.copy(sessionErrorsById = s.sessionErrorsById + (sid to banner))
                            }
                        }
                    }
                }
            }
            "sync" -> {
                // §issue-1(3): 全局事件流的同步标记。服务端发布每个 durable 事件时
                // 附带一个 type=sync 的回放封装（event-v2-bridge），上游 web 客户端
                // 直接跳过（server-sdk.tsx:195）。ocdroid 不做多端回放，同样不消费；
                // 显式 case 替代落入 else 的告警——已知且刻意忽略。
            }
            "models-dev.refreshed" -> {
                // §F3: 服务端 models.dev 模型目录刷新通知（上游 schema/models-dev.ts:5，
                // payload 为空对象）。ocdroid 不动态响应目录刷新，显式 no-op 以消除
                // unrecognized event 告警。与 load-more 失效无关。
            }
            "session.idle" -> {
                // §issue-1(2): 已废弃事件（schema/session-status-event.ts:43 标注
                // deprecated）。服务端在 session.status{type:idle} 之后总是再发一次
                // session.idle，二者时序等价。ocdroid 的会话状态（busy/idle、流式
                // 收尾、未读清理）已由上方 "session.status" 分支完整驱动，此处不再
                // 派发。若将来要做"回复就绪"主动通知，应挂在 session.status{idle}
                // （非废弃源）而非本事件——此处仅显式识别以消除 else 告警。
            }
            "session.diff" -> {
                // §issue-1(1): 会话文件变更快照。payload { sessionID, diff[] }，
                // 每条 = SnapshotFileDiff（file/patch/additions/deletions/status）。
                // 解析后写入 SessionListState.sessionDiffs，驱动聊天内 SessionDiffCard。
                // 解析模式镜像 todo.updated（JsonArray → List<@Serializable>）。
                val sessionId = event.payload.getString("sessionID") ?: return
                val diffArray = event.payload.properties?.get("diff") as? kotlinx.serialization.json.JsonArray ?: return
                // §gpter-B：用 lenientJson（ignoreUnknownKeys=true）与 REST getSessionDiff
                // 路径（OpenCodeRepository.json）对齐——默认 Json 严格模式会在上游
                // SnapshotFileDiff 多带字段时整条丢弃，导致 SSE/REST 行为不一致。
                val diffs = try {
                    lenientJson.decodeFromJsonElement<List<cn.vectory.ocdroid.data.model.FileDiff>>(diffArray)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid session.diff payload: ${e.message}")))
                    return
                }
                slices.mutateSessionList { it.applySessionDiff(sessionId, diffs).first }
            }
            // §files-events-noop: location-scoped filesystem events. Per
            // opencode-src/v1.17.18/packages/schema/src/filesystem-watcher.ts /
            // filesystem.ts, `file.watcher.updated` and `file.edited` carry NO
            // sessionID — they fire for ANY path change under a watched
            // directory, independent of which session touched the file. The
            // dispatch log will show `session=-` (the SSE frame lacks
            // sessionID) — this is NORMAL, not a missing-field bug. ocdroid
            // does NOT react to these (the Files pane is driven by the file
            // browser's own REST listing, not by these push events); an
            // explicit empty case stops the unrecognized-event warning. A real
            // Files-pane refresh on these events is a tracked follow-up. NOT
            // added to NOISY_SSE_LOG_EVENTS (that set is for high-frequency
            // noise; explicit no-op cases are semantically correct).
            "file.watcher.updated", "file.edited" -> {
            }
            "command.executed" -> {
                // §command-executed-noop: slash command 执行完成的元事件
                // (opencode-src packages/schema/src/v1/legacy-event.ts)。
                // payload {name, sessionID, arguments, messageID}。命令的实际输出（文本/工具/推理）
                // 由该 messageID 的 message.updated / message.part.* 通路独立投递，此处无需再派发。
                // 服务端唯一消费者是 project.ts(name==="init" 时标记 project 已初始化，写服务端 DB，
                // 与 app 无关)；web/TUI 亦不消费。显式识别以消除 unrecognized-event 告警——与
                // sync / session.idle / file.* 同策略。
            }
            else -> {
                // §R18 Phase 3 Wave 1 (P0-7): surface unrecognized SSE event
                // types instead of silently dropping them. NOISY_SSE_LOG_EVENTS
                // (server.connected / plugin.added / catalog.updated /
                // integration.updated / server.heartbeat / message.part.*)
                // are known-intentional non-dispatched types — skip the warning
                // to avoid log noise on every reconnect / per-token streaming
                // event, but still bump the counter for diagnostics.
                //
                // §test-guard: the existing `plugin.added` test asserts
                // `verify(exactly = 0) { Log.w(...) }`; the `!noisy` gate
                // preserves that contract (DebugLog.w forwards to Log.w).
                if (!noisy) {
                    val keys = event.payload.properties?.keys?.take(5) ?: emptyList()
                    DebugLog.w("SSE", "unrecognized event type=$type session=$evtSession payload-keys=$keys")
                    if (BuildConfig.DEBUG) {
                        effects.tryEmitUiEvent(UiEvent.Debug("unknown SSE: $type"))
                    }
                }
                unknownEventCounters
                    .computeIfAbsent(type) { java.util.concurrent.atomic.AtomicInteger(0) }
                    .incrementAndGet()
            }
        }
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
    private fun scheduleDeltaFlush(partId: String) {
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
        slices.mutateChat { c -> c.flushCoalesceBufferForPart(partId).first }
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
        slices.mutateChat { c -> c.clearCoalesceBufferForPart(partId).first }
    }

    /**
     * Drops ALL pending delta/fullText buffers, cancels their flush jobs, and
     * clears [ChatState.pendingFlushPartIds]. Called when the whole streaming
     * overlay is wiped (part.created now; session switch / SSE stop /
     * ViewModel clear may be wired by the caller — see §4.2). Safe to call
     * repeatedly.
     */
    fun clearDeltaBuffers() {
        flushJobs.values.forEach { it.cancel() }
        flushJobs.clear()
        slices.mutateChat { c -> c.clearAllCoalesceBuffers().first }
    }

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
    private fun handleSessionDigest(event: SSEEvent) {
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
        stripeFor(sid).withLock {
            if (!repo.isSlimCommitTokenCurrent(token)) return@withLock

            // C-D3 v2 §1.13: gate the banner commit so a stale token
            // can NOT touch the sessionErrorsById slice.
            val bannerCommitted = repo.commitIfSlimTokenCurrent(token) {
                applyDigestLastErrorToBanner(sid, digest.lastError)
            }

            if (!bannerCommitted) return@withLock

            repo.applySlimDigest(digest, token)

            if (!repo.isSlimCommitTokenCurrent(token)) return@withLock

            val result = reconcileSessionLocked(sid, mode, repo, token)
            applyReconcileResult(result, token)
        }
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

        // C-D3 v2 §1.8: public workflow entry captures once before its
        // first suspend point. Every nested suspend call and every commit
        // surface receives this exact token — NO recapture inside.
        val token = repo.captureSlimCommitToken()

        return reconcileSessionWithToken(
            sid = sid,
            mode = mode,
            repo = repo,
            token = token,
            isStillCurrent = { true },
        )
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
    ): ReconcileResult = stripeFor(sid).withLock {
        // C-D3 v2 §1.8: re-check both predicates under the stripe. A host
        // switch between the entry capture and the stripe acquisition
        // surfaces as Stale (NOT NoRepository — the repo IS wired, but
        // the token predates the rotation).
        if (!isStillCurrent() || !repo.isSlimCommitTokenCurrent(token)) {
            return@withLock ReconcileResult.Stale(sid)
        }

        reconcileSessionLocked(sid, mode, repo, token)
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
                sid == slices.chat.value.currentSessionId
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
                sid == slices.chat.value.currentSessionId
            ) {
                cn.vectory.ocdroid.util.DebugLog.d(
                    "DigestDiag",
                    "digest sid=$sid remoteUpdatedAt=${state.remoteUpdatedAt} " +
                        "priorWatermark=${state.localAppliedUpdatedAt} decision=skip reason=BACKGROUND mode=$mode",
                )
            }
            DebugLog.d("Sync", "reconcileSession sid=$sid mode=$mode BACKGROUND needsCatchUp → refresh row, keep dirty")
            return ReconcileResult.RefreshRow(sid)
        }
        // §streaming-state-sync-diag (runtime-gated, scoped): FETCH decision —
        // about to call /slimapi/messages/since.
        if (cn.vectory.ocdroid.util.DebugLog.verboseDiagEnabled &&
            sid == slices.chat.value.currentSessionId
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
                // C-D3 v2 §1.9: guard EVERY slice/effect commit (not just
                // the repo watermark bump, which already landed inside
                // bumpSlimBookmarkFromItems under the same token). The
                // banner / chat merge MUST land under the same atomic
                // gate so a configure rotation between repo commit and
                // slice commit cannot write a stale chat.
                val committed = repo.commitIfSlimTokenCurrent(token) {
                    if (slices.chat.value.currentSessionId == sid) {
                        mergeSlimMessagesIntoChat(items)
                    }
                }

                if (!committed) {
                    DebugLog.i(
                        "Sync",
                        "drop stale REST result sid=$sid mode=$mode",
                    )
                    ReconcileResult.Stale(sid)
                } else {
                    DebugLog.i(
                        "Sync",
                        "reconcileSession sid=$sid mode=$mode REST success items=${items.size} → dirty cleared (if truly aligned)",
                    )
                    ReconcileResult.Reconciled(sid, items)
                }
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
    private fun applyReconcileResult(
        result: ReconcileResult,
        token: OpenCodeRepository.SlimCommitToken,
    ) {
        val repo = repository ?: return

        // C-D3 v2 §1.10: Stale is a clean no-op (no slice / cache / effect
        // commit, no Failure pollution).
        if (result is ReconcileResult.Stale) return

        // C-D3 v2 §1.10: ONE mandatory gate. The check + every slice /
        // effect / cache mutation run atomically under slimStateLock so a
        // configure() rotation cannot straddle the gate-commit boundary
        // (TOCTOU mitigation — rev-gpt round-2 concern).
        repo.commitIfSlimTokenCurrent(token) {
            applyCurrentReconcileResult(result, token)
        }
    }

    /**
     * C-D3 v2 §1.10: the current-reincarnation body. Runs inside the
     * [commitIfSlimTokenCurrent] atomic region — every branch must stay
     * synchronous (no network, no delay, no blocking IO).
     */
    private fun applyCurrentReconcileResult(
        result: ReconcileResult,
        token: OpenCodeRepository.SlimCommitToken,
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

                if (slices.chat.value.currentSessionId == sid) {
                    slices.mutateChat {
                        it.copy(
                            messages = emptyList(),
                            partsByMessage = emptyMap(),
                        )
                    }
                }

                effects.tryEmitEffect(
                    ControllerEffect.EvictSession(currentServerGroupFp(), sid),
                )
            }

            is ReconcileResult.Reconciled -> {
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
                val isCurrent = slices.chat.value.currentSessionId == sid

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

                                applyReconcileResult(reconciled, entryToken)
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
                        outcomes[sid] = result
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
    ): Map<String, ReconcileResult> {
        val repo = repository ?: return emptyMap()
        if (!isSlimMode()) return emptyMap()

        // C-D3 v2 §1.12: ONE entry token; NO recapture after this point.
        val token = repo.captureSlimCommitToken()

        fun current(): Boolean =
            isStillCurrent() && repo.isSlimCommitTokenCurrent(token)

        if (!current()) return emptyMap()

        // ── Step 1: capture pre-refresh state ───────────────────────────
        val focus = slices.chat.value.currentSessionId
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
        val snapshotResult = repo.coldStartSlimSync(
            openSessionId = null,
            directories = directories,
            token = token,
        )
        val snapshot = snapshotResult.getOrNull()

        // C-D3 v2 §1.5/§1.12: a stale incarnation makes coldStartSlimSync
        // fail with StaleSlimCommitException (NOT collapse to null). The
        // outer Result.failure surface is identical to other transport
        // failures from the caller's perspective, but the token recheck
        // below catches it either way.
        if (!current()) return emptyMap()

        if (snapshot == null) {
            DebugLog.w(
                tag,
                "performSlimResync metadata refresh failed: ${snapshotResult.exceptionOrNull()?.message} — " +
                    "falling back to pre-refresh known set",
            )
        } else {
            // ── Step 3: fold snapshot under the same entry token ───────
            if (!current()) return emptyMap()
            // C-D3 v2 §3.6: token-gated snapshot fold. If the gate
            // rejects, the snapshot is dropped and we abort the sweep
            // (token superseded between cold-start commit and fold).
            if (!applySlimColdStartSnapshot(snapshot, token)) {
                return emptyMap()
            }
        }

        if (!current()) return emptyMap()

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
        if (catchUp.isEmpty()) return emptyMap()
        DebugLog.i(
            "Sync",
            "performSlimResync focus=$focus preRefreshLocal=${preRefreshLocalAll.size} " +
                "preRefreshSessions=${preRefreshSessions.size} refreshed=${refreshedSessions.size} " +
                "postRefreshLocal=${postRefreshLocalAll.size} overlayDirty=${overlayDirty.size} " +
                "callerDirty=${sessionsDirty.size} union=${catchUp.size} deadlineMs=$perSidDeadlineMs",
        )

        // ── Step 5: per-sid reconcile sweep using the SAME entry token ─
        if (!current()) return emptyMap()
        return performResyncCatchUp(
            catchUpSet = catchUp,
            perSidDeadlineMs = perSidDeadlineMs,
            token = token,
            isStillCurrent = isStillCurrent,
        )
    }

    /**
     * Cluster A / Phase 2: merge [MessageWithParts] skeletons into the open
     * chat slice by messageID (patch-if-found + insert-if-absent for messages;
     * parts map overwritten per fetched id). Mirrors message.updated fold.
     */
    private fun mergeSlimMessagesIntoChat(items: List<MessageWithParts>) {
        if (items.isEmpty()) return
        slices.mutateChat { chat ->
            var messages = chat.messages
            var partsByMessage = chat.partsByMessage
            for (item in items) {
                val updated = item.info
                if (updated.id.isEmpty()) continue
                var found = false
                messages = messages.map {
                    if (it.id == updated.id) {
                        found = true
                        updated
                    } else it
                }
                if (!found) messages = messages + updated
                if (item.parts.isNotEmpty()) {
                    partsByMessage = partsByMessage + (updated.id to item.parts)
                }
            }
            chat.copy(messages = messages, partsByMessage = partsByMessage)
        }
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
     */
    fun applySlimColdStartSnapshot(
        snapshot: SlimColdStartSnapshot,
        token: OpenCodeRepository.SlimCommitToken,
    ): Boolean {
        val repo = repository ?: return false

        return repo.commitIfSlimTokenCurrent(token) {
            DebugLog.i(
                "Sync",
                "applySlimColdStartSnapshot sessions=${snapshot.sessions?.size ?: "null"} " +
                    "questions=${snapshot.questions::class.simpleName} " +
                    "permissions=${snapshot.permissions::class.simpleName} " +
                    "messages=${snapshot.messages?.size ?: "null"}",
            )

            val sessions = snapshot.sessions
            if (sessions != null) {
                val byDirectory = sessions.groupBy { it.directory }
                slices.mutateSessionList { s ->
                    s.copy(
                        sessions = sessions,
                        directorySessions = byDirectory,
                    )
                }
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
                mergeSlimMessagesIntoChat(msgs)
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
 * I-2 v2 §3.5: directory-scoped apply for aggregation outcomes.
 *
 * Returns a [SlimAggregationFold] carrying BOTH the merged items AND
 * the derived [SlimAggregationSignal] (completeness + failedSources
 * + failureMessage) so the caller can update the slice's observable
 * signal alongside the list. The signal is computed via
 * [aggregationSignal] (top-level helper).
 *
 * - [SlimAggregationOutcome.Failure] → keep prior unchanged.
 * - [SlimAggregationOutcome.Success] with `authoritativeDirectories=null`
 *   → complete replacement (including empty).
 * - [SlimAggregationOutcome.Success] / [SlimAggregationOutcome.Partial]
 *   with non-null `authoritativeDirectories` → delete prior only from
 *   directories proven successful; failed/unknown dirs survive.
 *
 * C-D3 v2 §3.5 (defensive filter): fetched entries attributed to
 * directories OUTSIDE the proven-authoritative set are REJECTED.
 * Pre-fix, the directory filter only decided which PRIOR entries to
 * keep — a misbehaving sidecar could ship out-of-scope items and
 * pollute the local cache.
 *
 * Fetched entries win on duplicate IDs.
 *
 * T2 (slimapi v0.2.2 client-adapt): scope-directories gate. When
 * [SlimAggregationOutcome.Success.serverScope] (or
 * [SlimAggregationOutcome.Partial.serverScope]) reports
 * `directories == 0`, the sidecar's allowlist is not yet ready and the
 * (possibly empty) `items` are NOT authoritative — return prior
 * unchanged (same as `Failure`). `directories > 0` means authoritative;
 * `serverScope == null` (pre-0.2.2 sidecar / 503) preserves the original
 * behavior. See task-2-brief.md.
 */
internal fun <Wire, Ui> applyAggregationOutcome(
    prior: List<Ui>,
    outcome: SlimAggregationOutcome<Wire>,
    wireToUi: (Wire) -> Ui,
    uiId: (Ui) -> String,
    uiDirectory: (Ui) -> String?,
): SlimAggregationFold<Ui> {
    val signal = aggregationSignal(outcome)

    when (outcome) {
        is SlimAggregationOutcome.Failure -> {
            return SlimAggregationFold(
                items = prior,
                signal = signal,
            )
        }

        is SlimAggregationOutcome.Success -> {
            // T2: scope not ready → retain prior (do NOT clear stale).
            if (outcome.serverScope?.directories == 0) {
                return SlimAggregationFold(
                    items = prior,
                    signal = signal,
                )
            }

            val scope = outcome.authoritativeDirectories

            val fetched = outcome.items
                .map(wireToUi)
                .let { mapped ->
                    if (scope == null) {
                        mapped
                    } else {
                        // C-D3 v2 §3.5 defensive: reject out-of-scope
                        // envelope items.
                        mapped.filter { item -> uiDirectory(item) in scope }
                    }
                }

            if (scope == null) {
                return SlimAggregationFold(
                    items = fetched.distinctBy(uiId),
                    signal = signal,
                )
            }

            val result = LinkedHashMap<String, Ui>()

            prior.asSequence()
                .filter { item -> uiDirectory(item) !in scope }
                .forEach { item -> result[uiId(item)] = item }

            fetched.forEach { item -> result[uiId(item)] = item }

            return SlimAggregationFold(
                items = result.values.toList(),
                signal = signal,
            )
        }

        is SlimAggregationOutcome.Partial -> {
            // T2: scope not ready → retain prior (do NOT clear stale).
            // Mirrors the Success-branch gate.
            if (outcome.serverScope?.directories == 0) {
                return SlimAggregationFold(
                    items = prior,
                    signal = signal,
                )
            }

            val scope = outcome.authoritativeDirectories

            // A Partial item is accepted only from a proven-success
            // source. Items attributed to failed, unknown, or out-of-
            // request directories cannot replace local state.
            val fetched = outcome.items
                .map(wireToUi)
                .filter { item -> uiDirectory(item) in scope }

            val result = LinkedHashMap<String, Ui>()

            prior.asSequence()
                .filter { item -> uiDirectory(item) !in scope }
                .forEach { item -> result[uiId(item)] = item }

            fetched.forEach { item -> result[uiId(item)] = item }

            return SlimAggregationFold(
                items = result.values.toList(),
                signal = signal,
            )
        }
    }
}

// ── §R-17 batch5 → §R-19 Sprint 3 P2-4: pure state transforms for each SSE event branch ──
//
// Each function takes the prior slice value + the event payload and returns
// `Pair<State, List<SseSideEffect>>` — the new state AND a list of side
// effects the dispatcher should commit via `applySseSideEffects`. Pure: no
// effect emits, no coroutine launches, no settings writes. All DebugLog /
// diagnostic calls live in the dispatcher (the `when` branches above), NOT
// inside these functions, so the transforms stay pure + testable. Most return
// `emptyList()` (the transform is pure state-only); the dispatcher adds
// cross-slice effects (which need context the single-slice function doesn't
// have) and routes the combined list.
//
// §R-19 Sprint 3 P2-4: the signature unification (all return Pair) is the
// formalization step — the dispatcher's CAS pattern is now uniform across all
// 11 branches, and the effect sequencing is auditable in one place.

/**
 * session.created → upsert the parsed [Session] into [SessionListState.sessions].
 * Pure; effects empty (the dispatcher handles parse-failure via ReportNonFatal).
 *
 * §Q4-strict-sync: also removes [session].id from [SessionListState.pendingCreateIds]
 * — the server just confirmed the session exists, so it is no longer "pending".
 * This is the SSE-side confirmation path; the REST refresh (launchLoadSessions)
 * does the same removal idempotently. Idempotent with the 30 s sweep (which is
 * a fallback for ids that never get confirmed).
 */
internal fun SessionListState.applySessionCreated(session: Session): Pair<SessionListState, List<SseSideEffect>> =
    upsertAndInvalidateTree(session).copy(
        pendingCreateIds = pendingCreateIds - session.id,
        pendingCreatedAt = pendingCreatedAt - session.id,
    ) to emptyList()

/**
 * session.updated (non-archived) → upsert the parsed [Session] into
 * [SessionListState.sessions]. Pure; effects empty.
 *
 * §Q4-strict-sync: also removes [updated].id from [SessionListState.pendingCreateIds]
 * — a session.updated event proves the server knows about this session, so it
 * is no longer "pending" (even if session.created was missed). Idempotent with
 * applySessionCreated and the REST sweep.
 */
internal fun SessionListState.applySessionUpsert(updated: Session): Pair<SessionListState, List<SseSideEffect>> =
    upsertAndInvalidateTree(updated).copy(
        pendingCreateIds = pendingCreateIds - updated.id,
        pendingCreatedAt = pendingCreatedAt - updated.id,
    ) to emptyList()

private fun SessionListState.upsertAndInvalidateTree(session: Session): SessionListState {
    val updatedSessions = upsertSession(sessions, session)
    val byId = allSessionsById(updatedSessions, directorySessions, childSessions)
    val rootId = rootIdOf(session.id, byId)
    return copy(
        sessions = updatedSessions,
        // An unresolved parent chain cannot be attributed safely. Invalidate
        // every completeness proof rather than risk a false "all descendants
        // idle" result until hydration reconnects the new node to its root.
        completeRootIds = if (rootId == null) emptySet() else completeRootIds - rootId,
        // §gpter-blocker: bump the invalidation epoch so any in-flight
        // hydration (started before this SSE event) drops its result at
        // commit instead of re-certifying the now-stale root.
        completenessEpoch = completenessEpoch + 1L,
    )
}

/**
 * §recent-sort-by-message: when a session receives a message, bump its
 * [Session.time.updated] to `max(current, [updated])` so the
 * "recent sessions" surfaces (sortedByDescending { time.updated }) reflect
 * actual message activity rather than just the server-side session metadata
 * timestamp. Mirrors [applyArchiveEviction]'s two-store pattern: BOTH the
 * top-level `sessions` list AND every `directorySessions` bucket are bumped
 * (a session can be present in either / both stores, and both feed the
 * SessionsScreen / SessionPickerSheet recent derivations).
 *
 * Idempotent + monotonic (see [Session.TimeInfo.withUpdatedAtLeast]) — repeated
 * bumps for the same message are a no-op, and out-of-order / replayed events
 * never go backwards. Pure.
 */
internal fun SessionListState.applyMessageTimestampBump(
    sessionId: String,
    updated: Long
): Pair<SessionListState, List<SseSideEffect>> {
    if (updated <= 0L) return this to emptyList()
    val newSessions = sessions.map { s ->
        if (s.id == sessionId) s.copy(time = s.time.withUpdatedAtLeast(updated)) else s
    }
    val newDirectorySessions = directorySessions.mapValues { (_, list) ->
        list.map { s ->
            if (s.id == sessionId) s.copy(time = s.time.withUpdatedAtLeast(updated)) else s
        }
    }
    return copy(sessions = newSessions, directorySessions = newDirectorySessions) to emptyList()
}

/**
 * session.updated (archived) → upsert the session AND rewrite [openSessionIds]
 * to the caller-supplied [newOpenIds] (with the archived id evicted). The
 * caller computes [newOpenIds] + persists it via SettingsManager (a side
 * effect that stays inline). Pure; effects empty.
 */
internal fun SessionListState.applyArchiveEviction(
    updated: Session,
    newOpenIds: List<String>
): Pair<SessionListState, List<SseSideEffect>> = copy(
    sessions = upsertSession(sessions, updated),
    directorySessions = directorySessions.mapValues { (_, list) ->
        list.map { session -> if (session.id == updated.id) updated else session }
    },
    openSessionIds = newOpenIds
) to emptyList()

/**
 * The chat-side archive clear when the archived session IS the currently-open
 * one: drop currentSessionId + messages + partsByMessage so the chat view
 * falls back to the empty state. Pure; effects empty.
 *
 * FIX-B (review-blocker, groker B3): also clears the unified scroll slot +
 * parent-return backstack (added by WT2 / §Wave5b-Q13). The clearSessionData
 * path already clears them, but this archive-clear path was missed → a
 * pending scroll intent / checkpoint could stick if the session was archived
 * between the intent being set and consumed. Now the slot + backstack are
 * wiped atomically with the rest of the chat clear (one committed state, no
 * torn "session archived but scroll intent still references it").
 */
internal fun ChatState.applyArchivedChatClear(): Pair<ChatState, List<SseSideEffect>> = copy(
    currentSessionId = null,
    messages = emptyList(),
    partsByMessage = emptyMap(),
    // §slimapi-client-v1 §G6 (Task 16 round-2): clear per-part expand states
    // on archived-chat clear. Matches the partsByMessage clear above.
    partExpandStates = emptyMap(),
    // FIX-B / §Wave5b-Q13: clear the unified scroll slot + parent-return
    // backstack — a pending scroll / checkpoint for an archived session is
    // meaningless.
    pendingScrollRequest = null,
    parentReturnCheckpoints = emptyMap(),
) to emptyList()

/**
 * §Wave5b-Q13 blocker-2 fix (gpter 8.7 FAIL / groker 9.6 PASS): UNCONDITIONAL
 * scroll-state cleanup for an archived subtree. Used by the THREE archive
 * paths ([AppAction.SessionArchived] reducer, [AppAction.BulkSessionsRefreshed]
 * reducer, [launchSetSessionArchived] onSuccess) so a non-current archived
 * session/child can no longer leave stale scroll state behind.
 *
 * Cleans (preserving all chat CONTENT — messages/parts/streaming/etc. — which
 * remains current-only-cleared by [applyArchivedChatClear]):
 *  - [ChatState.pendingScrollRequest] → null IFF its targetSessionId is in
 *    [subtree] (a pending Latest/Restore for an archived session will never
 *    fire correctly; the consumer would skip it on the next switch anyway,
 *    but leaving it risks a stale fire if the user re-opens the same id
 *    later via a fresh create).
 *  - [ChatState.parentReturnCheckpoints] → filter out every entry whose key
 *    (childId) is in [subtree]. A returnToParent from an archived child is
 *    unreachable (the user cannot navigate to an archived session), but the
 *    stale entry would otherwise leak indefinitely in the map.
 *
 * Pure; effects empty. Callers MUST already have computed [subtree] via
 * [subtreeIds] (the SAME three-source union used for unread/questions
 * cleanup) — no second subtree walk here.
 *
 * Idempotent: safe to call when the slot is already null / the map already
 * has no entries in [subtree] (the operations are no-ops in that case).
 * Safe to compose with [applyArchivedChatClear] for the current-archived
 * case: applyArchivedChatClear wipes BOTH fields unconditionally, so a
 * subsequent call to this helper is a no-op.
 */
internal fun ChatState.cleanScrollStateForSubtree(
    subtree: Set<String>,
): ChatState {
    if (subtree.isEmpty()) return this
    val cleanSlot = pendingScrollRequest
        ?.takeUnless { it.targetSessionId in subtree }
    val cleanCheckpoints = if (parentReturnCheckpoints.isEmpty()) {
        parentReturnCheckpoints
    } else {
        parentReturnCheckpoints.filterKeys { it !in subtree }
    }
    // Skip the .copy() allocation entirely if neither field would change
    // (hot path: archive of an unrelated subtree on a chat with no scroll
    // state — common during bulk refreshes).
    if (cleanSlot === pendingScrollRequest && cleanCheckpoints === parentReturnCheckpoints) {
        return this
    }
    return copy(
        pendingScrollRequest = cleanSlot,
        parentReturnCheckpoints = cleanCheckpoints,
    )
}

/**
 * session.status → upsert the [sessionId] → [status] pair into
 * [SessionListState.sessionStatuses]. Pure; effects empty — the busy-current /
 * idle-current finalization side effects are computed by the dispatcher (they
 * depend on cross-slice state: chat.currentSessionId,
 * chat.streamingPartTexts).
 */
internal fun SessionListState.applySessionStatus(
    sessionId: String,
    status: SessionStatus
): Pair<SessionListState, List<SseSideEffect>> =
    copy(sessionStatuses = sessionStatuses + (sessionId to status)) to emptyList()

/**
 * message.updated → patch-if-found / insert-if-absent [updated] into
 * [ChatState.messages]. Returns the new state AND a `found` flag (so the
 * caller can log patch vs. insert without a second O(n) scan). Single O(n)
 * atomic pass — no TOCTOU. Pure.
 *
 * When [found] is false, the new message is appended at the tail (the list
 * is oldest-first and the new message is the newest — mirrors opencode-web).
 */
internal fun ChatState.applyMessageUpdated(updated: Message): Pair<ChatState, Boolean> {
    var found = false
    val newMessages = messages.map { if (it.id == updated.id) { found = true; updated } else it }
    val finalMessages = if (found) newMessages else newMessages + updated
    return copy(messages = finalMessages) to found
}

/**
 * message.part.updated (blank reasoning creation / type-correct placeholder) →
 * inject a [Part] of [partType] into [ChatState.partsByMessage][msgId] AND
 * set [ChatState.streamingReasoningPart] when [partType] == "reasoning".
 * Idempotent — once a Part of the correct type exists this is a no-op (caller
 * guards `hasCorrectType`, but this is defensive: it filters out any stale
 * wrong-typed placeholder first). Pure.
 */
internal fun ChatState.applyPartCreatedPlaceholder(
    partType: String,
    partId: String,
    msgId: String,
    sessionId: String
): Pair<ChatState, List<SseSideEffect>> {
    val base = partsByMessage[msgId]?.filterNot { it.id == partId } ?: emptyList()
    return copy(
        streamingReasoningPart = reasoningPartOrNull(partType, partId, msgId, sessionId)
            ?: streamingReasoningPart,
        partsByMessage = partsByMessage + (msgId to base + Part(
            id = partId, messageId = msgId, sessionId = sessionId, type = partType
        ))
    ) to emptyList()
}

/**
 * Leading-edge fullText write: REPLACE [ChatState.streamingPartTexts][partId]
 * with [fullText] (authoritative accumulated text), set
 * [ChatState.streamingReasoningPart] if [partType] == "reasoning", and ensure
 * the placeholder [Part] exists. Pure.
 *
 * Caller follows up with [markFlushPending] + [scheduleDeltaFlush] (the Job
 * scheduling is a side effect that stays on the coordinator).
 */
internal fun ChatState.applyPartFullTextLeadingEdge(
    partId: String,
    fullText: String,
    partType: String,
    pId: String,
    msgId: String,
    sessionId: String
): Pair<ChatState, List<SseSideEffect>> = copy(
    streamingPartTexts = streamingPartTexts + (partId to fullText),
    streamingReasoningPart = reasoningPartOrNull(
        partType = partType,
        partId = pId,
        messageId = msgId,
        sessionId = sessionId
    ) ?: streamingReasoningPart,
    partsByMessage = ensurePlaceholderPart(partsByMessage, msgId, pId, sessionId, partType)
) to emptyList()

/**
 * Leading-edge delta write: APPEND [delta] to the prior
 * [ChatState.streamingPartTexts][partId] value, set
 * [ChatState.streamingReasoningPart] if the resolved type is "reasoning", and
 * ensure the placeholder [Part] exists. Pure.
 *
 * Caller follows up with [markFlushPending] + [scheduleDeltaFlush] (the Job
 * scheduling is a side effect that stays on the coordinator).
 */
internal fun ChatState.applyPartDeltaLeadingEdge(
    partId: String,
    delta: String,
    knownType: String,
    msgId: String,
    sessionId: String
): Pair<ChatState, List<SseSideEffect>> {
    val previous = streamingPartTexts[partId].orEmpty()
    return copy(
        streamingPartTexts = streamingPartTexts + (partId to (previous + delta)),
        streamingReasoningPart = reasoningPartOrNull(knownType, partId, msgId, sessionId)
            ?: streamingReasoningPart,
        partsByMessage = ensurePlaceholderPart(partsByMessage, msgId, partId, sessionId, knownType)
    ) to emptyList()
}

/** Variant of [applyPartDeltaLeadingEdge] taking the part.updated partType +
 *  explicit pId (used by the message.part.updated delta branch where the
 *  known-type lookup hasn't run yet). Pure. */
internal fun ChatState.applyPartDeltaLeadingEdge(
    partId: String,
    delta: String,
    partType: String,
    pId: String,
    msgId: String,
    sessionId: String
): Pair<ChatState, List<SseSideEffect>> {
    val previous = streamingPartTexts[partId].orEmpty()
    return copy(
        streamingPartTexts = streamingPartTexts + (partId to (previous + delta)),
        streamingReasoningPart = reasoningPartOrNull(
            partType = partType,
            partId = pId,
            messageId = msgId,
            sessionId = sessionId
        ) ?: streamingReasoningPart,
        partsByMessage = ensurePlaceholderPart(partsByMessage, msgId, pId, sessionId, partType)
    ) to emptyList()
}

/**
 * Trailing-coalesce delta APPEND: append [delta] to the slice's
 * [ChatState.deltaBuffer][partId] (the buffer is now a `Map<String, String>`,
 * so the append is a read-modify-write under the slice's CAS `update`).
 * Pure. */
internal fun ChatState.appendDeltaBuffer(partId: String, delta: String): Pair<ChatState, List<SseSideEffect>> {
    val current = deltaBuffer[partId].orEmpty()
    return copy(deltaBuffer = deltaBuffer + (partId to (current + delta))) to emptyList()
}

/**
 * Trailing-coalesce fullText REPLACE: overwrite [ChatState.fullTextBuffer][partId]
 * with [text] (REPLACE — fullText is the server-authoritative accumulated
 * text; only the latest value per partId matters). Pure.
 */
internal fun ChatState.replaceFullTextBuffer(partId: String, text: String): Pair<ChatState, List<SseSideEffect>> =
    copy(fullTextBuffer = fullTextBuffer + (partId to text)) to emptyList()

/**
 * Marks [partId] as having a pending flush (the slice-side mirror of the
 * coordinator's [SessionSyncCoordinator.flushJobs] entry). Set on the
 * leading-edge write so an observer of the slice can detect "deltas still
 * buffered" without needing access to the Job. Pure.
 */
internal fun ChatState.markFlushPending(partId: String): Pair<ChatState, List<SseSideEffect>> =
    if (partId in pendingFlushPartIds) this to emptyList()
    else copy(pendingFlushPartIds = pendingFlushPartIds + partId) to emptyList()

/**
 * Flushes [partId]'s buffered delta/fullText into [ChatState.streamingPartTexts]
 * in a single atomic pure transform, then clears that partId's three coalesce
 * entries ([deltaBuffer] / [fullTextBuffer] / [pendingFlushPartIds]).
 *
 * §Site1-coalesce: a buffered fullText wins (REPLACE) over a buffered delta
 * (APPEND), and is checked FIRST. If the overlay was wiped mid-window
 * (`streamingPartTexts[partId] == null`), both buffers are dropped (stale;
 * re-injecting would render ghost text from the previous session).
 *
 * Mirrors the verbatim semantics of the pre-batch5 imperative
 * `flushDeltaBuffer` (the only change is storage location). Pure.
 */
internal fun ChatState.flushCoalesceBufferForPart(partId: String): Pair<ChatState, List<SseSideEffect>> {
    val overlayPresent = streamingPartTexts[partId] != null
    // REPLACE path: fullText wins outright when the overlay still exists.
    val bufferedFullText = fullTextBuffer[partId]
    if (overlayPresent && bufferedFullText != null) {
        // The authoritative fullText supersedes any concurrent delta
        // accumulation for this partId; drop the stale buffered delta so
        // it can't be appended in a later coalesce window.
        return copy(
            streamingPartTexts = streamingPartTexts + (partId to bufferedFullText),
            deltaBuffer = deltaBuffer - partId,
            fullTextBuffer = fullTextBuffer - partId,
            pendingFlushPartIds = pendingFlushPartIds - partId
        ) to emptyList()
    }
    // If the overlay was wiped mid-window, drop the buffered fullText (stale).
    // Otherwise keep fullTextBuffer intact (no fullText buffered for this
    // partId; the entry was already absent).
    val fullTextBufferAfter = if (overlayPresent) fullTextBuffer else fullTextBuffer - partId
    val bufferedDelta = deltaBuffer[partId]
    if (bufferedDelta.isNullOrEmpty()) {
        return copy(
            fullTextBuffer = fullTextBufferAfter - partId,
            deltaBuffer = deltaBuffer - partId,
            pendingFlushPartIds = pendingFlushPartIds - partId
        ) to emptyList()
    }
    // Overlay wiped mid-window → drop stale buffered delta (re-injecting
    // would render ghost text from the previous session).
    if (!overlayPresent) {
        return copy(
            fullTextBuffer = fullTextBufferAfter - partId,
            deltaBuffer = deltaBuffer - partId,
            pendingFlushPartIds = pendingFlushPartIds - partId
        ) to emptyList()
    }
    // APPEND path: append the buffered delta to the overlay.
    val previous = streamingPartTexts[partId].orEmpty()
    return copy(
        streamingPartTexts = streamingPartTexts + (partId to (previous + bufferedDelta)),
        fullTextBuffer = fullTextBufferAfter - partId,
        deltaBuffer = deltaBuffer - partId,
        pendingFlushPartIds = pendingFlushPartIds - partId
    ) to emptyList()
}

/**
 * Drops [partId]'s three coalesce entries without flushing (the buffers are
 * discarded, NOT applied to streamingPartTexts). The slice-side companion of
 * [SessionSyncCoordinator.cancelDeltaFlush]. Pure.
 */
internal fun ChatState.clearCoalesceBufferForPart(partId: String): Pair<ChatState, List<SseSideEffect>> = copy(
    deltaBuffer = deltaBuffer - partId,
    fullTextBuffer = fullTextBuffer - partId,
    pendingFlushPartIds = pendingFlushPartIds - partId
) to emptyList()

/**
 * Drops ALL coalesce entries (deltaBuffer / fullTextBuffer /
 * pendingFlushPartIds). Does NOT touch streamingPartTexts /
 * streamingReasoningPart — the overlay clear is a separate concern (the
 * §闪屏修复 made part.created preserve the overlay; only the buffers are
 * dropped). The slice-side companion of
 * [SessionSyncCoordinator.clearDeltaBuffers]. Pure.
 */
internal fun ChatState.clearAllCoalesceBuffers(): Pair<ChatState, List<SseSideEffect>> = copy(
    deltaBuffer = emptyMap(),
    fullTextBuffer = emptyMap(),
    pendingFlushPartIds = emptySet()
) to emptyList()

/**
 * question.asked → append [question] to [SessionListState.pendingQuestions]
 * iff its id is not already present (idempotent). Pure.
 *
 * Cluster A / Phase 2: when a duplicate id arrives WITH a non-null
 * [QuestionRequest.routeToken] and the existing entry has null, upgrade the
 * stored entry (slim SSE may re-deliver with the token after a REST-only
 * insert).
 */
internal fun SessionListState.applyQuestionAsked(question: QuestionRequest): Pair<SessionListState, List<SseSideEffect>> {
    val existingIdx = pendingQuestions.indexOfFirst { it.id == question.id }
    if (existingIdx < 0) {
        return copy(pendingQuestions = pendingQuestions + question) to emptyList()
    }
    val existing = pendingQuestions[existingIdx]
    if (existing.routeToken.isNullOrBlank() && !question.routeToken.isNullOrBlank()) {
        val upgraded = pendingQuestions.toMutableList().also { it[existingIdx] = question }
        return copy(pendingQuestions = upgraded) to emptyList()
    }
    return this to emptyList()
}

/**
 * permission.asked (slim SSE with routeToken) → append/upgrade
 * [SessionListState.pendingPermissions] by id. Pure. Legacy path still
 * relies on [SseSideEffect.LoadPendingPermissions] REST refresh.
 */
internal fun SessionListState.applyPermissionAsked(permission: PermissionRequest): Pair<SessionListState, List<SseSideEffect>> {
    val existingIdx = pendingPermissions.indexOfFirst { it.id == permission.id }
    if (existingIdx < 0) {
        return copy(pendingPermissions = pendingPermissions + permission) to emptyList()
    }
    val existing = pendingPermissions[existingIdx]
    if (existing.routeToken.isNullOrBlank() && !permission.routeToken.isNullOrBlank()) {
        val upgraded = pendingPermissions.toMutableList().also { it[existingIdx] = permission }
        return copy(pendingPermissions = upgraded) to emptyList()
    }
    return this to emptyList()
}

/**
 * Cluster A / Phase 2: map a slimapi aggregate question entry onto the legacy
 * [QuestionRequest] UI model, **preserving [SlimapiQuestionEntry.routeToken]**.
 */
internal fun SlimapiQuestionEntry.toQuestionRequest(): QuestionRequest =
    QuestionRequest(
        id = id,
        sessionId = sessionId,
        questions = questions,
        tool = tool,
        directory = directory,
        routeToken = routeToken,
    )

/**
 * I-2 v2 §3.4: derive an observable [SlimAggregationSignal] from a
 * [SlimAggregationOutcome]. Top-level so both
 * [SessionSyncCoordinator] and [cn.vectory.ocdroid.ui.launchLoadPendingPermissionsSlim]
 * use the SAME implementation.
 */
internal fun <T> aggregationSignal(
    outcome: SlimAggregationOutcome<T>,
): cn.vectory.ocdroid.ui.SlimAggregationSignal = when (outcome) {
    is SlimAggregationOutcome.Success -> cn.vectory.ocdroid.ui.SlimAggregationSignal(
        completeness = cn.vectory.ocdroid.ui.SlimAggregationCompleteness.COMPLETE,
    )

    is SlimAggregationOutcome.Partial -> cn.vectory.ocdroid.ui.SlimAggregationSignal(
        completeness = cn.vectory.ocdroid.ui.SlimAggregationCompleteness.INCOMPLETE,
        failedSources = outcome.errors.map { error ->
            cn.vectory.ocdroid.ui.SlimAggregationFailedSource(
                directory = error.directory,
                code = error.code,
            )
        },
    )

    is SlimAggregationOutcome.Failure -> cn.vectory.ocdroid.ui.SlimAggregationSignal(
        completeness = cn.vectory.ocdroid.ui.SlimAggregationCompleteness.FAILED,
        failureMessage = outcome.message,
    )
}

/**
 * I-2 v2 §3.5: shared model returned by [applyAggregationOutcome].
 * Carries BOTH the merged items AND the derived
 * [cn.vectory.ocdroid.ui.SlimAggregationSignal] so the caller updates
 * the slice's signal atomically with the list.
 */
internal data class SlimAggregationFold<Ui>(
    val items: List<Ui>,
    val signal: cn.vectory.ocdroid.ui.SlimAggregationSignal,
)

/**
 * Cluster A / Phase 2: parse a `permission.asked` SSE frame into a
 * [PermissionRequest], including optional [PermissionRequest.routeToken]
 * from slim properties. Returns null on malformed payload.
 */
internal fun parsePermissionAskedEvent(event: SSEEvent): PermissionRequest? {
    val properties = event.payload.properties ?: return null
    return runCatching {
        lenientJson.decodeFromJsonElement<PermissionRequest>(properties)
    }.getOrNull()
}

/**
 * question.replied / question.rejected → drop the pending question whose id
 * matches [requestId]. Pure.
 */
internal fun SessionListState.applyQuestionResolved(requestId: String): Pair<SessionListState, List<SseSideEffect>> =
    copy(pendingQuestions = pendingQuestions.filter { it.id != requestId }) to emptyList()

/**
 * todo.updated → upsert [todos] under [sessionId] in
 * [SessionListState.sessionTodos]. Pure.
 */
internal fun SessionListState.applyTodoUpdated(
    sessionId: String,
    todos: List<TodoItem>
): Pair<SessionListState, List<SseSideEffect>> =
    copy(sessionTodos = sessionTodos + (sessionId to todos)) to emptyList()

/**
 * §issue-1(1): session.diff → 替换 [sessionId] 的文件变更快照。服务端每次发布的是
 * 该会话当前的完整 diff 列表（非增量），故整体替换。Pure。
 */
internal fun SessionListState.applySessionDiff(
    sessionId: String,
    diffs: List<cn.vectory.ocdroid.data.model.FileDiff>
): Pair<SessionListState, List<SseSideEffect>> =
    copy(sessionDiffs = sessionDiffs + (sessionId to diffs)) to emptyList()

/**
 * §issue-1(1) REST 预取专用：仅当 [sessionId] 尚无 diff 条目时写入（stale-overwrite
 * 守卫，glmer-S1）。SSE [applySessionDiff] 是权威源、无条件覆盖；REST 仅乐观预取，若
 * SSE 已推过更新则跳过，避免在途 REST 旧结果覆盖更新的 SSE 快照。抽成纯函数以便单测
 * （maxer 复审：覆盖维度）。镜像上游 web client `diff()` 的 `if defined && !force return`。
 */
internal fun SessionListState.applySessionDiffIfAbsent(
    sessionId: String,
    diffs: List<cn.vectory.ocdroid.data.model.FileDiff>
): Pair<SessionListState, List<SseSideEffect>> =
    (if (sessionDiffs[sessionId] != null) this
    else copy(sessionDiffs = sessionDiffs + (sessionId to diffs))) to emptyList()

/**
 * session lifecycle completion (root busy→idle) → mark [sessionId] unread IF
 * it is not the currently-open session. Also used as the REST reconnect
 * backstop. Caller passes [currentSessionId] so this stays pure (no slice
 * read). Pure.
 */
internal fun UnreadState.applyMarkSessionUnread(
    sessionId: String,
    currentSessionId: String?
): Pair<UnreadState, List<SseSideEffect>> =
    (if (sessionId == currentSessionId) this
    else copy(unreadSessions = unreadSessions + sessionId)) to emptyList()

// ── §streaming-render placeholder injection ────────────────

/**
 * Ensures a minimal placeholder [Part] exists in [partsByMessage] for the
 * given [msgId]/[pId] so a `ChatMessageRow.PartView` is composed immediately
 * and consumes the streaming override (`streamingTextOverride ?: part.text`)
 * while SSE deltas accumulate. The placeholder has `text = null`, so the
 * streaming override wins; when the REST reload later fires it replaces
 * `partsByMessage[msgId]` wholesale, overwriting the placeholder with the
 * real Part — no conflict.
 *
 * No-op when [partsByMessage] already has a Part with `id == pId` for [msgId]
 * (the common case once the first placeholder has been inserted, or once the
 * REST reload has brought the real parts in).
 *
 * Type guard: only `text` and `reasoning` parts are streamed through
 * `streamingPartTexts` and rendered by `PartView`'s `TextPart` /
 * `ReasoningCard`. Other part types (`tool`, `patch`, `file`, `step-start`,
 * `step-finish`, …) are NOT streamed this way — injecting a placeholder for
 * them would misroute in `PartView` (e.g. a `type="tool"` placeholder with
 * `tool=null` renders an empty tool card; `step-*` has no render branch and
 * the streaming text would be orphaned). For those types this is a no-op.
 * They get their real `Part` from the REST reload as before.
 */
internal fun ensurePlaceholderPart(
    partsByMessage: Map<String, List<Part>>,
    msgId: String,
    pId: String,
    sessionId: String,
    partType: String
): Map<String, List<Part>> {
    // Only text/reasoning parts stream via streamingPartTexts. Skip
    // placeholder injection for any other type — they would misroute in
    // PartView and the streaming text would never be consumed.
    if (partType != "text" && partType != "reasoning") return partsByMessage
    val existing = partsByMessage[msgId]
    return when {
        existing == null -> partsByMessage + (msgId to listOf(Part(id = pId, messageId = msgId, sessionId = sessionId, type = partType)))
        existing.none { it.id == pId } -> partsByMessage + (msgId to (existing + Part(id = pId, messageId = msgId, sessionId = sessionId, type = partType)))
        else -> partsByMessage
    }
}

/**
 * §R-17 batch5: helper for tests / future state inspectors — is the SSE
 * coalesce window currently open for [partId]? Pure read on [ChatState].
 */
internal fun ChatState.isFlushPending(partId: String): Boolean =
    partId in pendingFlushPartIds
