package cn.vectory.ocdroid.service.lifecycle

import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.service.ReconfigureTeardown
import cn.vectory.ocdroid.service.StreamingOwnershipGate
import cn.vectory.ocdroid.service.TeardownReason
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand.StartForeground
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand.StopForeground
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand.StopPoller
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand.StopSelf
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand.StartPoller
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand.StartSse
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand.StopSse
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand.EnsurePoller
import cn.vectory.ocdroid.service.status.GlobalBusyState
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.service.status.isKeepAlive
import cn.vectory.ocdroid.service.streaming.SourceActivation
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 0 / dev-design P0.3 — the FGS §4 L1/L2/L3 state machine.
 *
 * Single serial (Mutex-guarded) state machine that drives the
 * [cn.vectory.ocdroid.service.SessionStreamingService] through the layered
 * lifecycle defined in FGS spec §4.1:
 *
 *  - **[Layer.L1]** — foreground. [Layer.L1.busy] distinguishes the idle
 *    sub-state (普通 Service, no FGS slot, SSE alive) from the busy sub-state
 *    (dataSync FGS pre-warmed while still foreground, §4.2 — so the subsequent
 *    Activity→background transition never trips a
 *    `ForegroundServiceStartNotAllowedException`).
 *  - **[Layer.L2Active]** — background + global busy. dataSync FGS, SSE alive.
 *  - **[Layer.L2Idle]** — background, authoritative all-idle AND the 45s
 *    debounce expired. FGS shell KEPT (no `stopSelf`), SSE off, poller on.
 *  - **[Layer.L3]** — torn down (`onTimeout()` / user-explicit-close /
 *    disconnect / process death). No FGS, no SSE, poller only. No automatic
 *    recovery — legal recovery entries only (user reopens app / notification
 *    action / system restart).
 *
 * **§4.4 unified handoff ordering (D2 gate #4 — acknowledgeable)**: every
 * source activation (SSE collector start, poller start) is acknowledgeable —
 * the coordinator allocates a handoff token under mutex, emits the StartX
 * command carrying the token, **releases** the mutex while the source
 * establishes readiness, and **reacquires** the mutex to verify the token +
 * layer + identity are still valid before committing the transition (emitting
 * the closing StopX command + flipping the layer). This closes the
 * "launch == readiness" gap: a long SSE connection attempt can no longer
 * block a concurrent foreground/timeout/user-close input, and the §4.4
 * invariant "new source active + verified BEFORE closing old" is now
 * enforced by [SourceActivation.Ready] rather than by coroutine launch order.
 *
 * **§4.1 groker-R1 (L2 internal recovery)**: when the L2-idle poller/REST
 * finds busy, the transition to [Layer.L2Active] happens **in-place** —
 * [StartSse] + [StopPoller] — without a new `startForegroundService` (the FGS
 * shell was kept in L2Idle). This is only valid inside the L2 window; after
 * `onTimeout()` the machine is in L3 and cannot restart the FGS.
 *
 * **Inputs**:
 *  - a foreground signal [StateFlow] (bound at [start]; §4.3 — default false,
 *    Activity started-count is the truth source);
 *  - [StatusAggregator.globalState] — the authoritative, lifecycle-safe tri-state
 *    (Lane A impl, FGS spec §3 + §3.1, CP4). Replaces the unsafe `globalBusy:
 *    Boolean`: [GlobalBusyState.Unknown] (request failure / no fresh data /
 *    stale entry) is treated like [GlobalBusyState.Busy] for keep-alive (the
 *    source MUST stay alive, no idle debounce) so a failure can NEVER silently
 *    drop keep-alive on real busy sessions;
 *  - explicit entries [requestUserClose] (§16-U1), [onTimeout] (dataSync
 *    timeout), [onDisconnect];
 *  - [onBootstrapResult] — the §5 START_STICKY bootstrap completion signal
 *    carrying the fresh [ConnectionIdentity] and the bootstrap's
 *    [GlobalBusyState] verdict; the ONLY legal entry out of L3.
 *  - [onActivationAck] — D2: the controller's completion signal for a
 *    [StartSse] / [StartPoller] activation, carrying the handoff token +
 *    [SourceActivation] result. Drives the handoff commit phase.
 *
 * **Outputs** — [commands]: a [Flow] of [LifecycleCommand] the service
 * observes (start/stop foreground, start/stop SSE, start/stop poller,
 * stopSelf). Notifier data-source switching (§6 unified IslandNotifier) is a
 * Phase-1 seam and not represented here.
 *
 * **Thread safety**: all state reads/writes happen under [mutex]. The
 * coordinator runs on the injected [UiApplicationScope] (Main.immediate) so
 * commands are emitted on the main thread — the service MUST call
 * `startForeground`/`stopForeground` on the main thread. The handoff commit
 * coroutines release + reacquire the mutex around their await — the await
 * itself runs WITHOUT the mutex held (so a long SSE connect attempt does not
 * block foreground/timeout/user-close inputs).
 */
@Singleton
class StreamingLifecycleCoordinator @Inject constructor(
    private val statusAggregator: StatusAggregator,
    @UiApplicationScope private val scope: CoroutineScope,
    private val ownershipGate: StreamingOwnershipGate? = null,
) : ReconfigureTeardown {
    /**
     * The layered lifecycle state (FGS spec §4.1). See [Layer] for the
     * invariant each value carries. Consumers (notification display layer,
     * debug surfaces) read this; only this coordinator writes it.
     */
    private val _layer = MutableStateFlow<Layer>(Layer.L3)
    val layer: StateFlow<Layer> = _layer.asStateFlow()

    /**
     * Command channel — single-consumer, FIFO, bounded. The service collects
     * [commands] and executes each in order; the bounded capacity applies
     * backpressure if the service falls behind (the serial state machine
     * naturally waits — §4.4 "持锁、串行").
     */
    private val commandChannel: Channel<LifecycleCommand> =
        Channel(capacity = COMMAND_CHANNEL_CAPACITY)
    val commands: Flow<LifecycleCommand> = commandChannel.receiveAsFlow()

    private val mutex = Mutex()
    private var debounceJob: Job? = null
    private var started = false
    private var inForegroundRef: StateFlow<Boolean>? = null
    private var currentIdentity: ConnectionIdentity? = null

    /**
     * D5 (#2) — the real supplemental / primary process-poller runtime
     * state machine (replaces the prior `supplementalPollerActive: Boolean`
     * fiction). Under [mutex]; identity-bound + acknowledged + idempotent.
     *
     *  - [PollerRuntime.Stopped] — no poller running.
     *  - [PollerRuntime.Starting] — a [LifecycleCommand.StartPoller] (Primary)
     *    or [LifecycleCommand.EnsurePoller] (Supplemental) has been emitted;
     *    awaiting the matching ack. Carries the identity + request id so a
     *    stale ack (superseded mid-flight) is ignored.
     *  - [PollerRuntime.Running] — the poller acked Ready; the identity is
     *    bound. A later definitive status verdict (Busy / AllIdleFresh)
     *    retires it via [stopPollerIfActiveLocked].
     *
     * [GlobalBusyState.Unknown] NEVER authorizes idle teardown while a
     * Supplemental poller is Running (the status authority is not yet
     * definitive).
     *
     * **Dual-plane note (docs only)**: this field is the **control-plane**
     * half of the poller lifecycle — it tracks the acknowledgeable
     * handshake (requestId-bound [PollerRuntime.Starting] →
     * [PollerRuntime.Running]) the coordinator drives under [mutex] so a
     * stale / superseded ack is rejected. The matching **data-plane** half
     * lives in [cn.vectory.ocdroid.service.streaming.ProcessStatusPoller]
     * — specifically its `loopJob` field, the actual polling coroutine that
     * fires the §3 status refreshes every
     * [cn.vectory.ocdroid.service.streaming.ProcessStatusPoller.DEFAULT_INTERVAL_MS].
     * The two planes are decoupled by design: the control-plane decides
     * WHETHER a poller should be running + whether its activation committed
     * (this field); the data-plane performs the polling (`loopJob`). A
     * lifecycle teardown retires the control-plane via
     * [stopPollerIfActiveLocked] (emits [LifecycleCommand.StopPoller] +
     * flips to [PollerRuntime.Stopped]); the controller's command collector
     * then reaches the data-plane by routing that StopPoller command to the
     * poller.
     */
    private var pollerRuntime: PollerRuntime = PollerRuntime.Stopped

    /**
     * D5 (#2) — monotonic request-id counter for [LifecycleCommand.EnsurePoller]
     * (supplemental poller activations). Distinct from [handoffTokenCounter]
     * (which covers primary StartPoller / StartSse) so a supplemental
     * ensure-ack cannot be confused with a primary handoff ack.
     */
    private var ensurePollerRequestIdCounter: Long = 0L

    // ── D2 gate #4: handoff token / pending-handoff state ───────────────────

    /**
     * D2: the current pending source-activation handoff, or null when no
     * activation is awaiting an ack. Read/written under [mutex]; the handoff
     * commit coroutine reads `pendingHandoff?.token` after reacquiring the
     * mutex to detect supersession (a concurrent transition allocated a new
     * handoff while the old one was awaiting ack).
     */
    private var pendingHandoff: Handoff? = null

    /**
     * D2: monotonic handoff-token counter. Every [beginHandoff] allocation
     * bumps this; the token travels on the [LifecycleCommand.StartSse] /
     * [LifecycleCommand.StartPoller] command and comes back via
     * [onActivationAck]. A stale ack (for a superseded handoff) is dropped
     * silently.
     */
    private var handoffTokenCounter: Long = 0L

    /**
     * D2: in-flight handoff descriptor. Carries everything the commit phase
     * (after the ack) needs to verify freshness + decide what to emit.
     *
     * D5 (#3): [deferred] is REMOVED — the network op already completed
     * when [onActivationAck] is called, so the commit runs synchronously
     * under the mutex (no separate commit coroutine). The handoff is now
     * just a descriptor of what was started + what to validate when the
     * ack arrives.
     */
    private data class Handoff(
        val token: Long,
        val kind: HandoffKind,
        /** The layer the coordinator was in when the handoff was allocated. */
        val fromLayer: Layer,
        /** The identity captured at handoff allocation (for reconfigure detect). */
        val expectedIdentity: ConnectionIdentity,
        /** Which transition this handoff is for (drives the commit's `when`). */
        val decision: HandoffDecision,
        /** Foreground signal captured at allocation (used by L2Idle-exit + bootstrap). */
        val fg: Boolean,
        /** Status state captured at allocation (used to decide layer sub-state). */
        val state: GlobalBusyState,
    )

    private enum class HandoffKind { StartPoller, StartSse }

    private enum class HandoffDecision {
        /** L2Active → L2Idle: poller's final poll decides StopSse vs Stay-L2Active. */
        DebounceFire,

        /** L2Idle → L1/L2Active: SSE readiness decides StopPoller + commit vs stay. */
        L2IdleExit,

        /** → L3 teardown: poller first attempt → StopSse → StopForeground → StopSelf. */
        Teardown,

        /** L3 → L1/L2Active: bootstrap → SSE → StopPoller + commit. */
        Bootstrap,
    }

    /**
     * Binds the foreground signal and (re)starts observation. Idempotent.
     * Must be called before [onBootstrapResult] so the initial transition
     * out of L3 knows whether §5 is happening in a foreground or background
     * context (§4.3).
     */
    fun start(inForeground: StateFlow<Boolean>) {
        if (started) return
        started = true
        inForegroundRef = inForeground
        scope.launchObserving(inForeground, statusAggregator.globalState)
    }

    /**
     * §5 START_STICKY bootstrap completion — the ONLY legal L3 → running
     * transition. Carries the fresh [ConnectionIdentity] (so subsequent
     * [StartSse] commands tag events correctly, FGS spec §2) and the
     * authoritative [GlobalBusyState] verdict from the §3 global status
     * snapshot.
     *
     * **CP4**: takes the lifecycle-safe [GlobalBusyState] (not the unsafe
     * `Boolean`). [GlobalBusyState.Unknown] (bootstrap status fetch failed)
     * is treated like [GlobalBusyState.Busy] for keep-alive: the source is
     * kept alive (SSE on, FGS held) and the idle-grace window is NOT entered
     * — the bootstrap refuses to authoritatively label the host idle until a
     * fresh successful snapshot arrives (FGS spec §3 «请求失败 → 全局 Unknown,
     * 不得进 idle 宽限期»).
     *
     * D2: the SSE activation is acknowledgeable — [transitionFromL3]
     * allocates a handoff token, emits [LifecycleCommand.StartSse], and the
     * layer commit (StopPoller + layer flip) runs ONLY after
     * [onActivationAck] delivers [SourceActivation.Ready]. A [SourceActivation.Rejected]
     * leaves the host in L3 (poller still running) — the bootstrap retry path
     * handles recovery.
     */
    fun onBootstrapResult(identity: ConnectionIdentity, state: GlobalBusyState) {
        scope.launch {
            mutex.withLock {
                currentIdentity = identity
                val fg = inForegroundRef?.value ?: false
                transitionFromL3(fg, state, identity)
            }
        }
    }

    /**
     * §16-U1 user-explicit-close entry (ongoing notification Action
     * 「关闭后台」). Triggers L3 teardown regardless of current layer. D2 (gate #8):
     * the §16-U1 identity/status recheck happens at the controller
     * ([SessionStreamingController.handleUserClose])
     * BEFORE this entry — by the time [requestUserClose] is called the action
     * has been revalidated against the current identity and the final status
     * snapshot has been refreshed. Busy/Unknown does NOT veto an explicit
     * user close (§16-U1 implementation note).
     *
     * D4-B (awaitable unification): non-suspend Android entrypoint →
     * `scope.launch { teardownAndAwait(UserClose) }`. The canonical path is
     * [teardownAndAwait].
     */
    fun requestUserClose() {
        DebugLog.i(TAG, "requestUserClose (§16-U1)")
        scope.launch { teardownAndAwait(TeardownReason.UserClose) }
    }

    /**
     * §4.1 dataSync `onTimeout()` entry (targetSdk 35+ 6h/24h limit). L3
     * teardown; the machine cannot restart the FGS from L3.
     *
     * D4-B (awaitable unification): non-suspend entrypoint →
     * `scope.launch { teardownAndAwait(Timeout) }`.
     */
    fun onTimeout() {
        DebugLog.i(TAG, "onTimeout (§4.1 dataSync timeout)")
        scope.launch { teardownAndAwait(TeardownReason.Timeout) }
    }

    /**
     * §4.1 disconnect entry (SSEConnectionExhausted past the service-level
     * retry budget). L3 teardown.
     *
     * D4-B (awaitable unification): non-suspend entrypoint →
     * `scope.launch { teardownAndAwait(Disconnect) }`.
     */
    fun onDisconnect() {
        DebugLog.i(TAG, "onDisconnect (§4.1)")
        scope.launch { teardownAndAwait(TeardownReason.Disconnect) }
    }

    /**
     * D4-B — the CANONICAL awaitable teardown path. Every teardown entry
     * (onDisconnect / onTimeout / requestUserClose / terminal SSE callback /
     * bootstrap failure / reconfigure) routes through here.
     *
     * Reason-specific behavior:
     *  - [TeardownReason.Reconfigure] (M4): intentional no-source barrier —
     *    cancels pending handoff, emits StopPoller + StopSse + StopForeground
     *    + StopSelf, sets L3, releases ownership; does NOT emit a replacement
     *    StartPoller (old identity is intentionally invalid post-
     *    `beginReconfigure()`). The no-source window lasts only through the
     *    serialized repository rebuild.
     *  - [TeardownReason.BootstrapFailure] (B1): forces terminal shell cleanup
     *    EVEN WHEN the lifecycle layer is already L3 (a live placeholder
     *    Service holding Starting ownership must be fully torn down). Does
     *    NOT call [StreamingOwnershipGate.disconnectAndRelease] — the caller
     *    ([SessionStreamingService.failStarting]) owns the Starting rollback
     *    + SSE join via [StreamingOwnershipGate.failStarting].
     *  - [TeardownReason.Timeout] / [UserClose] / [Disconnect]: generic L3
     *    teardown (the acknowledgeable StartPoller → StopSse handoff may run
     *    if leaving L1/L2); then releases ownership via
     *    [StreamingOwnershipGate.disconnectAndRelease].
     */
    override suspend fun teardownAndAwait(reason: TeardownReason) {
        DebugLog.i(TAG, "teardownAndAwait(reason=$reason)")
        when (reason) {
            TeardownReason.Reconfigure -> teardownForReconfigure()
            TeardownReason.BootstrapFailure -> teardownForBootstrapFailure()
            TeardownReason.Timeout, TeardownReason.UserClose, TeardownReason.Disconnect -> {
                teardown()
                layer.first { it == Layer.L3 }
                ownershipGate?.disconnectAndRelease(markGap = true)
            }
        }
    }
    /**
     * D2 (gate #4) + D5 (#3): the controller's completion signal for a
     * source activation. The network op already completed when called —
     * this method runs the commit SYNCHRONOUSLY under [mutex] (no separate
     * commit coroutine, no `Handoff.deferred`). Command emission is the
     * only suspend work; NO network/REST under the mutex.
     *
     * Under the mutex: find matching handoff → validate token → validate
     * current layer → validate captured identity → commit or reject →
     * clear pending handoff → return the exact [ActivationCommitResult].
     *
     * Ownership promotion (D5 #3): for a current Bootstrap SSE Ready that
     * commits into a running L1/L2 layer — (1) update layer/source state,
     * (2) clear pending handoff, (3) EXIT the mutex, (4)
     * `ownershipGate.markReady(identity)`, (5) return `SseCommitted`. Do
     * NOT mark Ready when token superseded / identity changed / layer
     * changed / Bootstrap resolves to background-idle stopSelf / activation
     * rejected.
     *
     * A stale ack (for a superseded handoff) is dropped silently — returns
     * [ActivationCommitResult.Superseded]. No commands emitted.
     */
    suspend fun onActivationAck(token: Long, activation: SourceActivation): ActivationCommitResult {
        // Phase 1: under the mutex, validate + commit (or refuse). Collect
        // any post-mutex work (ownership promotion) to run AFTER releasing
        // the lock.
        data class PostCommit(
            val markReadyIdentity: ConnectionIdentity?,
            val result: ActivationCommitResult,
        )
        val post: PostCommit = mutex.withLock {
            val ph = pendingHandoff
            if (ph?.token != token) {
                // Superseded by a newer handoff — the newer one already
                // cleaned up our source.
                return@withLock PostCommit(null, ActivationCommitResult.Superseded)
            }
            val layerValid = _layer.value == ph.fromLayer
            val identityValid = currentIdentity == ph.expectedIdentity
            if (!layerValid || !identityValid) {
                // Concurrent input changed the layer OR a reconfigure changed
                // the identity mid-handoff. Cancel our activation; the
                // concurrent transition handles the rest.
                emitStopFor(ph.kind)
                pendingHandoff = null
                return@withLock PostCommit(null, ActivationCommitResult.Superseded)
            }
            // Commit — decision-specific. Returns the identity to markReady
            // (or null) + the result.
            val commitOutcome = runDecisionCommit(ph, activation)
            pendingHandoff = null
            PostCommit(commitOutcome.markReadyIdentity, commitOutcome.result)
        }
        // Phase 2: ownership promotion OUTSIDE the mutex (markReady is a
        // synchronized block on the gate's own lock — no coroutine
        // suspension, but we keep it out of the coordinator mutex for
        // lock-ordering hygiene).
        if (post.markReadyIdentity != null) {
            ownershipGate?.markReady(post.markReadyIdentity)
        }
        return post.result
    }

    /**
     * D5 (#2) — the controller's completion signal for a supplemental
     * [LifecycleCommand.EnsurePoller] activation. Only a matching
     * [PollerRuntime.Starting]`(`identity`, `requestId`, Supplemental)` may
     * change state: Ready → [PollerRuntime.Running]`(`identity`, Supplemental)`;
     * Rejected → [PollerRuntime.Stopped]; stale ack → ignored.
     */
    suspend fun onEnsurePollerAck(
        identity: ConnectionIdentity,
        requestId: Long,
        activation: SourceActivation,
    ): ActivationCommitResult {
        mutex.withLock {
            val runtime = pollerRuntime
            if (runtime !is PollerRuntime.Starting) return@withLock ActivationCommitResult.Superseded
            if (runtime.identity != identity || runtime.requestId != requestId) {
                return@withLock ActivationCommitResult.Superseded
            }
            when (activation) {
                is SourceActivation.Ready -> {
                    pollerRuntime = PollerRuntime.Running(identity, PollerRuntime.Purpose.Supplemental)
                }
                is SourceActivation.Rejected -> {
                    pollerRuntime = PollerRuntime.Stopped
                }
            }
        }
        return if (activation is SourceActivation.Ready) ActivationCommitResult.PollerCommitted
        else ActivationCommitResult.RetainedOldSource
    }

    // ── Transition handlers (all under [mutex]) ─────────────────────────────

    /**
     * L3 → running state. Emitted only by [onBootstrapResult]. Per §4.4 +
     * D2 gate #4: the SSE source is started (with a handoff token) and the
     * L3 poller is stopped ONLY after the SSE activation acks Ready — the
     * layer commit (StopPoller + layer flip) runs in the handoff's commit phase.
     *
     * CP4: takes the lifecycle-safe [GlobalBusyState]. [GlobalBusyState.Unknown]
     * is treated like [GlobalBusyState.Busy] (keep source alive, no idle teardown)
     * — see [onBootstrapResult].
     */
    private suspend fun transitionFromL3(fg: Boolean, state: GlobalBusyState, identity: ConnectionIdentity) {
        val keepAlive = state.isKeepAlive // true for Busy || Unknown; false for AllIdleFresh
        when {
            keepAlive -> {
                // §5 step 4: keep FGS + start SSE (acknowledgeable). The L3
                // poller stays running until SSE verifies; the commit emits
                // StopPoller + flips to L1-busy/L2Active.
                beginHandoff(
                    HandoffKind.StartSse,
                    fromLayer = Layer.L3,
                    identity = identity,
                    decision = HandoffDecision.Bootstrap,
                    fg = fg,
                    state = state,
                )
            }
            fg -> {
                // §4.1 L1-idle: SSE on (acknowledgeable), FGS downgraded AFTER
                // SSE verifies. The commit emits StopPoller + StopForeground +
                // flips to L1-idle.
                beginHandoff(
                    HandoffKind.StartSse,
                    fromLayer = Layer.L3,
                    identity = identity,
                    decision = HandoffDecision.Bootstrap,
                    fg = fg,
                    state = state,
                )
            }
            else -> {
                // §5 step 5: background all-idle → L3 (teardown, no FGS slot).
                // No source switch (no SSE to start; poller stays running).
                emit(StopForeground)
                emit(StopSelf)
                _layer.value = Layer.L3
            }
        }
    }

    /**
     * §4.4 L1 internal conversions + L1→L2/L3 transitions.
     *
     * CP4: `state.isKeepAlive` (Busy || Unknown) maps to the prior `busy=true`
     * branch; only [GlobalBusyState.AllIdleFresh] takes the idle path. So a
     * failure / stale status (Unknown) keeps the FGS slot hot in L1 just like
     * Busy — the idle-grace window is never entered on uncertainty.
     *
     * D2: L1-idle → background routes through [teardown] (acknowledgeable
     * StartPoller handoff before StopSse). L1 internal conversions
     * (busy↔idle while foreground) require no source switch — no handoff.
     * L1-busy → L2Active (background transition while busy) is also
     * source-stable (SSE stays on).
     */
    private suspend fun handleL1(current: Layer.L1, fg: Boolean, state: GlobalBusyState) {
        // D5 (#2): clear a supplemental poller once the status authority is
        // definitive (Busy/AllIdleFresh). Unknown keeps it running.
        // stopPollerIfActiveLocked emits exactly one StopPoller + sets
        // Stopped (no duplicate emissions on repeated definitive verdicts).
        if (state == GlobalBusyState.Busy || state == GlobalBusyState.AllIdleFresh) {
            stopPollerIfActiveLocked()
        }
        val keepAlive = state.isKeepAlive
        if (fg) {
            if (current.busy == keepAlive) return // no sub-state change
            if (keepAlive) {
                // §4.2 L1-idle → L1-busy: foreground promotion is legal → StartForeground.
                emit(StartForeground)
                _layer.value = Layer.L1(busy = true)
            } else {
                // L1-busy → L1-idle: downgrade to normal Service (SSE kept).
                emit(StopForeground)
                _layer.value = Layer.L1(busy = false)
            }
        } else {
            cancelDebounce()
            if (keepAlive) {
                // §4.2 L1-busy → L2Active: FGS already held, just layer change. SSE stays on.
                _layer.value = Layer.L2Active
            } else {
                // §4.2 L1-idle → background: NO FGS promotion attempted → L3.
                // Routes through teardownLocked() (we already hold the mutex
                // via the observer's withLock) which performs the acknowledgeable
                // StartPoller handoff before StopSse (§4.4 + D2 gate #4).
                teardownLocked()
            }
        }
    }

    /**
     * §4.4 L2Active transitions: → L1 (foreground return), → L2Idle (idle
     * debounce), or stay.
     *
     * CP4: only [GlobalBusyState.AllIdleFresh] arms the 45s debounce.
     * [GlobalBusyState.Busy] AND [GlobalBusyState.Unknown] both stay L2Active
     * with the debounce cancelled (Unknown ≠ idle — a failed / stale snapshot
     * must NOT enter the idle grace window, FGS spec §3).
     */
    private suspend fun handleL2Active(fg: Boolean, state: GlobalBusyState) {
        // D5 (#2): clear a supplemental poller once the status authority is
        // definitive (Busy/AllIdleFresh). Unknown keeps it running.
        if (state == GlobalBusyState.Busy || state == GlobalBusyState.AllIdleFresh) {
            stopPollerIfActiveLocked()
        }
        val keepAlive = state.isKeepAlive
        cancelDebounce()
        if (pendingHandoff?.decision == HandoffDecision.DebounceFire && (fg || keepAlive)) {
            cancelPendingHandoff()
        }
        if (fg) {
            // L2Active → L1: SSE was on, stays on (no source switch).
            if (keepAlive) {
                _layer.value = Layer.L1(busy = true)
            } else {
                // §4.4: AllIdleFresh on foreground return → stopForeground → L1-idle.
                emit(StopForeground)
                _layer.value = Layer.L1(busy = false)
            }
        } else {
            if (keepAlive) {
                // still busy OR unknown; stay L2Active (debounce cancelled above, no-op).
            } else {
                // §4.4: AllIdleFresh → arm the 45s debounce.
                startIdleDebounce()
            }
        }
    }

    /**
     * §4.4 L2Idle transitions: → L2Active (poller finds busy, IN-PLACE per
     * groker-R1), → L1 (foreground return), or stay.
     *
     * CP4: only [GlobalBusyState.AllIdleFresh] stays L2Idle. Both
     * [GlobalBusyState.Busy] and [GlobalBusyState.Unknown] return to L2Active
     * (in-place, per groker-R1): Busy is the normal recovery; Unknown also
     * re-establishes SSE because L2Idle had SSE OFF and a stale/failed poller
     * verdict must NOT be trusted to keep the host in idle (restore the source).
     *
     * D2 gate #4: the SSE activation is acknowledgeable. The commit (after
     * [onActivationAck]) emits StopPoller + flips the layer. A
     * [SourceActivation.Rejected] leaves the host in L2Idle with the poller
     * still running — the source switch is NOT committed.
     */
    private suspend fun handleL2Idle(fg: Boolean, state: GlobalBusyState) {
        val identity = currentIdentity ?: run {
            DebugLog.w(TAG, "handleL2Idle: no identity bound — skipping transition")
            return
        }
        val keepAlive = state.isKeepAlive
        if (fg || keepAlive) {
            // Foreground return OR (background + busy/unknown) → start SSE
            // (acknowledgeable) before retiring the poller.
            beginHandoff(
                HandoffKind.StartSse,
                fromLayer = Layer.L2Idle,
                identity = identity,
                decision = HandoffDecision.L2IdleExit,
                fg = fg,
                state = state,
            )
        } else {
            // still AllIdleFresh; stay L2Idle.
        }
    }

    /**
     * §4.4 → L3 teardown entry. Acquires [mutex] + delegates to
     * [teardownLocked]. Public entries ([requestUserClose] / [onTimeout] /
     * [onDisconnect]) route here; the L1-idle→background path inside
     * [handleL1] (already under mutex) calls [teardownLocked] directly to
     * avoid a re-entrant lock (Kotlin [Mutex] is NOT re-entrant).
     */
    private suspend fun teardown() {
        mutex.withLock { teardownLocked() }
    }

    /**
     * D4-B M4 — the Reconfigure no-source teardown. Modelled as an
     * INTENTIONAL bounded no-source barrier: the old identity is invalid
     * post-`beginReconfigure()`, so NO replacement StartPoller is emitted
     * (do NOT poll with a transition identity; do NOT delay epoch
     * invalidation for a final old-host poll). The no-source window lasts
     * only through the serialized repository rebuild / new bootstrap that the
     * [ConnectionReconfigureBarrier] runs after this returns (spec §4.4
     * exception).
     *
     * Sequence: cancel pending handoff → StopPoller (terminate old-identity
     * process poller) → StopSse + await cancellation (via the ownership
     * disconnect callback) → StopForeground → StopSelf → enter L3 → release
     * ownership. Does NOT emit StartPoller.
     */
    private suspend fun teardownForReconfigure() {
        mutex.withLock {
            cancelDebounce()
            cancelPendingHandoff()
            // Terminate the old-identity process poller (L3 source contract
            // is void during reconfigure — the new bootstrap will start a
            // fresh poller bound to the new identity). D5 (#2): emit
            // StopPoller directly (not via stopPollerIfActiveLocked) because
            // the old-identity poller may be running as an external source
            // not tracked by pollerRuntime.
            emit(StopPoller)
            pollerRuntime = PollerRuntime.Stopped
            emit(StopSse)
            emit(StopForeground)
            emit(StopSelf)
            _layer.value = Layer.L3
        }
        layer.first { it == Layer.L3 }
        // disconnectAndRelease joins the SSE collector cancellation BEFORE
        // the barrier's repository rebuild runs.
        ownershipGate?.disconnectAndRelease(markGap = false)
    }

    /**
     * D4-B B1 — the BootstrapFailure teardown. Forces terminal shell cleanup
     * EVEN WHEN the lifecycle layer is already L3 (a live placeholder
     * Service holding Starting ownership must be fully torn down: the
     * ordinary `L3 → return` short-circuit in [teardownLocked] is INVALID
     * for this reason). Emits StopSse + StopPoller + StopForeground +
     * StopSelf + sets L3 unconditionally.
     *
     * Does NOT call [StreamingOwnershipGate.disconnectAndRelease] — the
     * caller ([SessionStreamingService.failStarting]) owns the Starting
     * rollback + SSE join via [StreamingOwnershipGate.failStarting].
     */
    private suspend fun teardownForBootstrapFailure() {
        mutex.withLock {
            cancelDebounce()
            cancelPendingHandoff()
            emitBootstrapFailureTeardownCommands()
            _layer.value = Layer.L3
        }
        layer.first { it == Layer.L3 }
    }

    /**
     * Wave-3 L3c — the BootstrapFailure teardown command emit sequence.
     *
     * Order is SPECIFIC to BootstrapFailure and DIFFERS from the reconfigure
     * teardown (which emits `StopPoller → StopSse → StopForeground → StopSelf`
     * — see [teardownForReconfigure]). DO NOT unify the two sequences:
     *
     *   StopSse → stopPollerIfActiveLocked (StopPoller once if active)
     *         → StopForeground → StopSelf
     *
     * The BootstrapFailure invariant is that the SSE source is closed BEFORE
     * the poller retirement (a placeholder Service holding Starting ownership
     * has its SSE attempt cancelled first; the poller is best-effort cleanup
     * gated on [pollerRuntime] via [stopPollerIfActiveLocked]).
     *
     * MUST be called under [mutex] (uses [stopPollerIfActiveLocked] which
     * reads/writes [pollerRuntime]).
     */
    private suspend fun emitBootstrapFailureTeardownCommands() {
        emit(StopSse)
        stopPollerIfActiveLocked()
        emit(StopForeground)
        emit(StopSelf)
    }

    /**
     * §4.4 → L3 teardown body (MUST be called under [mutex]). notifier→polling,
     * cancelSse, stopForeground / stopSelf. D2 gate #4: the poller is the new
     * source — started via an acknowledgeable [LifecycleCommand.StartPoller]
     * handoff. The commit (after the poller's immediate-first-poll acks Ready)
     * emits StopSse (gap-dirty via §1.1) + StopForeground + StopSelf + flips
     * to L3.
     *
     * The poller runs on [cn.vectory.ocdroid.di.ApplicationScope] — survives
     * Service.onDestroy (§6 L3 source contract). The teardown commit lands
     * the layer at L3 regardless of the poller's reported state (L3 is
     * terminal for this run; the poller's state only matters for the
     * aggregator / notification display, not for the layer decision).
     */
    private suspend fun teardownLocked() {
        cancelDebounce()
        // Cancel any pending handoff (e.g., L2Idle→L2Active in flight) —
        // its activation is no longer relevant; teardown will start its
        // own poller handoff if needed.
        val hadPendingHandoff = pendingHandoff != null
        cancelPendingHandoff()
        when (_layer.value) {
            is Layer.L1, Layer.L2Active -> {
                // SSE was source → start poller (new) before StopSse (old).
                // The handoff commit emits StopSse + StopForeground + StopSelf.
                val identity = currentIdentity
                if (identity != null) {
                    beginHandoff(
                        HandoffKind.StartPoller,
                        fromLayer = _layer.value,
                        identity = identity,
                        decision = HandoffDecision.Teardown,
                        fg = inForegroundRef?.value ?: false,
                        state = statusAggregator.stateAtNow(),
                    )
                } else {
                    // No identity — just teardown synchronously.
                    emit(StopSse)
                    emit(StopForeground)
                    emit(StopSelf)
                    _layer.value = Layer.L3
                }
            }
            Layer.L2Idle -> {
                // poller already running, SSE already off — nothing to switch.
                // (Any pending SSE handoff was cancelled above.) Teardown synchronously.
                emit(StopForeground)
                emit(StopSelf)
                _layer.value = Layer.L3
            }
            Layer.L3 -> {
                if (hadPendingHandoff) {
                    emit(StopForeground)
                    emit(StopSelf)
                }
                return
            }
        }
    }

    /**
     * §4.4 L2Active → L2Idle: arms the 45s idle debounce. Fires only if still
     * L2Active + authoritative [GlobalBusyState.AllIdleFresh] at expiry (CP4:
     * replaces the unsafe `!globalBusy.value` check — Unknown / Busy keep the
     * source alive and prevent the L2Idle transition even at debounce expiry).
     *
     * D2 gate #4: on fire, allocates an acknowledgeable [LifecycleCommand.StartPoller]
     * handoff. The poller's immediate-first-poll returns the time-correct
     * [GlobalBusyState]; the commit emits [LifecycleCommand.StopSse] ONLY if
     * the poller reports [GlobalBusyState.AllIdleFresh] (the §4.4 "final
     * snapshot poll"). If the poller reports [GlobalBusyState.Busy] /
     * [GlobalBusyState.Unknown] the commit emits
     * [LifecycleCommand.StopPoller] + stays L2Active (the new poller is
     * cancelled; SSE was never closed). A [SourceActivation.Rejected] from
     * the poller also stops the new poller + stays L2Active.
     *
     * **D1 (gate #1)**: the expiry reads [StatusAggregator.stateAtNow] (a
     * time-correct projection), NOT the cached [StatusAggregator.globalState]
     * `.value`. The cached value lags the wall clock by the dispatcher
     * latency of the aggregator's passive-TTL wake-up — a debounce firing at
     * exactly `sourceTime + TTL + ε` would otherwise read a stale
     * `AllIdleFresh` and emit `StopSse` on stale-idle data (§3 violation).
     */
    private fun startIdleDebounce() {
        debounceJob?.cancel()
        debounceJob = scope.launchDebounce {
            delay(IDLE_DEBOUNCE_MS)
            mutex.withLock {
                if (_layer.value == Layer.L2Active &&
                    statusAggregator.stateAtNow() == GlobalBusyState.AllIdleFresh
                ) {
                    val identity = currentIdentity ?: return@withLock
                    // D2: acknowledgeable StartPoller handoff. The commit
                    // (after the immediate first poll acks) emits StopSse
                    // (AllIdleFresh) or StopPoller (Busy/Unknown — stay L2Active).
                    beginHandoff(
                        HandoffKind.StartPoller,
                        fromLayer = Layer.L2Active,
                        identity = identity,
                        decision = HandoffDecision.DebounceFire,
                        fg = false,
                        state = GlobalBusyState.AllIdleFresh,
                    )
                }
            }
        }
    }

    private fun cancelDebounce() {
        debounceJob?.cancel()
        debounceJob = null
    }

    /**
     * D2: cancels any pending handoff — used by transitions that need to
     * allocate a new handoff (e.g., teardown superseding an in-flight
     * L2Idle→L2Active SSE activation) OR cancel one without allocating a new
     * one (e.g., L2Idle teardown — poller already running, SSE handoff in
     * flight is cancelled). Emits the appropriate StopX (so the dangling
     * activation is cleaned up) + clears [pendingHandoff].
     *
     * D5 (#3): the commit is now synchronous inside [onActivationAck] —
     * there is no `deferred` to cancel. Clearing [pendingHandoff] is
     * sufficient: a late ack for the cleared token returns [Superseded].
     *
     * MUST be called under [mutex].
     */
    private suspend fun cancelPendingHandoff() {
        val ph = pendingHandoff ?: return
        emitStopFor(ph.kind)
        pendingHandoff = null
    }

    /**
     * D2: allocates a handoff token + emits the [LifecycleCommand.StartX]
     * carrying it + records [pendingHandoff]. MUST be called under [mutex]
     * (the caller — a transition handler — already holds it).
     *
     * D5 (#3): NO commit coroutine is launched — the commit runs
     * synchronously inside [onActivationAck] when the controller delivers
     * the activation result. The handoff is just a descriptor of what was
     * started.
     *
     * If a prior handoff is pending, it is superseded: its StopX is emitted
     * (so the dangling activation is cleaned up). The new handoff's StartX
     * is emitted AFTER the prior's StopX (FIFO channel — the controller
     * observes the cleanup before the new activation).
     */
    private suspend fun beginHandoff(
        kind: HandoffKind,
        fromLayer: Layer,
        identity: ConnectionIdentity,
        decision: HandoffDecision,
        fg: Boolean,
        state: GlobalBusyState,
    ) {
        // Supersede any prior pending handoff.
        cancelPendingHandoff()

        val token = ++handoffTokenCounter
        pendingHandoff = Handoff(
            token = token,
            kind = kind,
            fromLayer = fromLayer,
            expectedIdentity = identity,
            decision = decision,
            fg = fg,
            state = state,
        )
        when (kind) {
            HandoffKind.StartPoller -> {
                emit(StartPoller(identity, token))
                // D5 (#2): track the primary poller runtime state.
                markPrimaryPollerStartingLocked(identity, token)
            }
            HandoffKind.StartSse -> emit(StartSse(identity, token))
        }
    }

    /**
     * D5 (#2) — tracks the primary poller runtime state when a
     * [LifecycleCommand.StartPoller] handoff is emitted. MUST be under
     * [mutex].
     */
    private fun markPrimaryPollerStartingLocked(identity: ConnectionIdentity, token: Long) {
        pollerRuntime = PollerRuntime.Starting(identity, token, PollerRuntime.Purpose.Primary)
    }

    /**
     * D5 (#2) — applies a primary StartPoller ack to the runtime. Called
     * from [runDecisionCommit] when a Primary poller handoff commits.
     * MUST be under [mutex].
     */
    private fun applyPrimaryPollerAckLocked(identity: ConnectionIdentity, ready: Boolean) {
        val runtime = pollerRuntime
        if (runtime !is PollerRuntime.Starting || runtime.identity != identity ||
            runtime.purpose != PollerRuntime.Purpose.Primary
        ) {
            return // stale or wrong-purpose — ignore
        }
        pollerRuntime = if (ready) {
            PollerRuntime.Running(identity, PollerRuntime.Purpose.Primary)
        } else {
            PollerRuntime.Stopped
        }
    }

    /**
     * D5 (#2) — ensures a supplemental process poller is running for
     * [identity]. Called from [commitSseTransport] when the status verdict
     * is Unknown (the SSE transport proved itself but the REST status
     * snapshot is not yet definitive). MUST be under [mutex].
     *
     *  - [PollerRuntime.Running] same identity → no-op.
     *  - [PollerRuntime.Starting] same identity → no-op.
     *  - [PollerRuntime.Stopped] → allocate a new requestId, set Starting,
     *    emit [LifecycleCommand.EnsurePoller].
     *  - different identity → stop old then start new.
     */
    private suspend fun ensureSupplementalPollerLocked(identity: ConnectionIdentity) {
        val runtime = pollerRuntime
        when {
            runtime is PollerRuntime.Running && runtime.identity == identity -> return
            runtime is PollerRuntime.Starting && runtime.identity == identity -> return
            else -> {
                // Different identity or Stopped — stop old if active.
                if (runtime !is PollerRuntime.Stopped) {
                    emit(StopPoller)
                }
                val requestId = ++ensurePollerRequestIdCounter
                pollerRuntime = PollerRuntime.Starting(identity, requestId, PollerRuntime.Purpose.Supplemental)
                emit(EnsurePoller(identity, requestId))
            }
        }
    }

    /**
     * D5 (#2) — stops the poller if it is active (Starting or Running).
     * Emits exactly one [LifecycleCommand.StopPoller] then sets
     * [PollerRuntime.Stopped]. Stopped → no command. MUST be under [mutex].
     * Repeated definitive emissions MUST NOT emit repeated StopPoller.
     */
    private suspend fun stopPollerIfActiveLocked() {
        val runtime = pollerRuntime
        if (runtime is PollerRuntime.Stopped) return
        emit(StopPoller)
        pollerRuntime = PollerRuntime.Stopped
    }

    /**
     * D5 (#3) — the decision-specific commit logic. Runs under [mutex]
     * inside [onActivationAck] AFTER the activation ack verified the
     * handoff is still current. Emits the closing StopX + flips the layer.
     * Returns the identity to markReady (for Bootstrap Ready that commits
     * into L1/L2) + the [ActivationCommitResult].
     *
     * **D4-B M3 + D5 (#2)**: the status authority is read via
     * [StatusAggregator.stateAtNow] AT COMMIT (not carried on the
     * activation — [SourceActivation.Ready] is transport-only). For the SSE
     * handoffs (Bootstrap / L2IdleExit): if the verdict is Busy /
     * AllIdleFresh → [stopPollerIfActiveLocked]; if Unknown →
     * [ensureSupplementalPollerLocked] until a definitive verdict arrives.
     */
    private suspend fun runDecisionCommit(
        ph: Handoff,
        activation: SourceActivation,
    ): CommitOutcome {
        val decision = ph.decision
        val fg = ph.fg
        return when (decision) {
            HandoffDecision.DebounceFire -> {
                when (activation) {
                    is SourceActivation.Ready -> {
                        applyPrimaryPollerAckLocked(ph.expectedIdentity, ready = true)
                        val verdict = statusAggregator.stateAtNow()
                        if (verdict == GlobalBusyState.AllIdleFresh) {
                            // Commit: StopSse (close old SSE) + flip to L2Idle.
                            // The poller is the NEW source — it STAYS running
                            // (do NOT stop it). The runtime is already Running
                            // (set by applyPrimaryPollerAckLocked above).
                            emit(StopSse)
                            _layer.value = Layer.L2Idle
                        } else {
                            // Busy/Unknown — stop the NEW poller, stay L2Active.
                            // SSE was never closed.
                            stopPollerIfActiveLocked()
                        }
                        CommitOutcome(null, ActivationCommitResult.PollerCommitted)
                    }
                    is SourceActivation.Rejected -> {
                        // Don't commit; stop the new poller, stay L2Active.
                        applyPrimaryPollerAckLocked(ph.expectedIdentity, ready = false)
                        stopPollerIfActiveLocked()
                        CommitOutcome(null, ActivationCommitResult.RetainedOldSource)
                    }
                }
            }
            HandoffDecision.L2IdleExit -> {
                when (activation) {
                    is SourceActivation.Ready -> commitSseTransport(ph.expectedIdentity, fg)
                    is SourceActivation.Rejected -> {
                        CommitOutcome(null, ActivationCommitResult.RetainedOldSource)
                    }
                }
            }
            HandoffDecision.Teardown -> {
                // → L3: regardless of activation (poller's state only affects
                // the aggregator / notification, not the layer decision — L3
                // is terminal for this run). The poller is the NEW source —
                // it stays running (do NOT stop it). D5 (#2): the primary
                // poller ack updates the runtime to Running (if Ready).
                applyPrimaryPollerAckLocked(
                    ph.expectedIdentity,
                    ready = activation is SourceActivation.Ready,
                )
                emit(StopSse)
                emit(StopForeground)
                emit(StopSelf)
                _layer.value = Layer.L3
                CommitOutcome(null, ActivationCommitResult.PollerCommitted)
            }
            HandoffDecision.Bootstrap -> {
                when (activation) {
                    is SourceActivation.Ready -> commitSseTransport(ph.expectedIdentity, fg)
                    is SourceActivation.Rejected -> {
                        CommitOutcome(
                            null,
                            ActivationCommitResult.BootstrapRejected(ph.expectedIdentity, activation),
                        )
                    }
                }
            }
        }
    }

    private data class CommitOutcome(
        val markReadyIdentity: ConnectionIdentity?,
        val result: ActivationCommitResult,
    )

    /**
     * D4-B M3 + D5 (#2/#3) — the SSE transport commit (Bootstrap +
     * L2IdleExit share this body). Runs under [mutex]. Reads
     * [StatusAggregator.stateAtNow] to decide the layer sub-state + whether
     * to retire the supplemental poller. Returns the identity to markReady
     * (for a current Bootstrap that commits into L1/L2) + the
     * [ActivationCommitResult]. MUST be called under [mutex].
     */
    private suspend fun commitSseTransport(
        identity: ConnectionIdentity,
        fg: Boolean,
    ): CommitOutcome {
        val verdict = statusAggregator.stateAtNow()
        if (verdict == GlobalBusyState.Busy || verdict == GlobalBusyState.AllIdleFresh) {
            // Definitive status — retire the process poller (SSE is the source).
            // D5 (#2): emit StopPoller directly (NOT via stopPollerIfActiveLocked)
            // because the L3 primary poller may be running as an external source
            // not tracked by pollerRuntime (it was started before the coordinator
            // got involved). The runtime is reset to Stopped so subsequent
            // definitive verdicts in handleL1/handleL2Active do NOT emit
            // duplicate StopPoller.
            emit(StopPoller)
            pollerRuntime = PollerRuntime.Stopped
        } else {
            // Unknown — commit the SSE transport + layer, KEEP the process
            // poller running as a supplemental status authority. Cleared
            // once a definitive verdict arrives (handleL1/handleL2Active).
            ensureSupplementalPollerLocked(identity)
        }
        var markReady: ConnectionIdentity? = null
        var result: ActivationCommitResult = ActivationCommitResult.RetainedOldSource
        if (fg) {
            if (verdict.isKeepAlive) {
                _layer.value = Layer.L1(busy = true)
                markReady = identity
                result = ActivationCommitResult.SseCommitted(identity)
            } else {
                emit(StopForeground)
                _layer.value = Layer.L1(busy = false)
                markReady = identity
                result = ActivationCommitResult.SseCommitted(identity)
            }
        } else {
            if (verdict.isKeepAlive) {
                _layer.value = Layer.L2Active
                markReady = identity
                result = ActivationCommitResult.SseCommitted(identity)
            } else {
                emit(StopForeground)
                emit(StopSelf)
                // D5 (#2): retire any poller (L3 primary or supplemental).
                emit(StopPoller)
                pollerRuntime = PollerRuntime.Stopped
                // Stay L3 — do NOT markReady (background-idle stopSelf).
            }
        }
        return CommitOutcome(markReady, result)
    }

    /**
     * D2: emits the StopX command that cancels a [kind] activation. Used by
     * [cancelPendingHandoff] + the supersede path in [onActivationAck].
     */
    private suspend fun emitStopFor(kind: HandoffKind) {
        when (kind) {
            HandoffKind.StartPoller -> emit(StopPoller)
            HandoffKind.StartSse -> emit(StopSse)
        }
    }

    private suspend fun emit(command: LifecycleCommand) {
        commandChannel.send(command)
    }

    /**
     * launches the (fg, state) observer that drives [handleL1]/[handleL2Active]/
     * [handleL2Idle] on every input change. L3 inputs are ignored (no
     * auto-recovery — §4.1).
     *
     * CP4: consumes the lifecycle-safe [GlobalBusyState] (not the legacy
     * `globalBusy: Boolean`). [GlobalBusyState.Unknown] routes through the
     * `isKeepAlive=true` branches alongside [GlobalBusyState.Busy] — the
     * source stays alive, no idle debounce is armed.
     */
    private fun CoroutineScope.launchObserving(
        inForeground: StateFlow<Boolean>,
        globalState: StateFlow<GlobalBusyState>,
    ) {
        launch {
            combine(inForeground, globalState) { fg, state -> fg to state }
                .collect { (fg, state) ->
                    mutex.withLock {
                        when (val current = _layer.value) {
                            is Layer.L1 -> handleL1(current, fg, state)
                            Layer.L2Active -> handleL2Active(fg, state)
                            Layer.L2Idle -> handleL2Idle(fg, state)
                            Layer.L3 -> {
                                val handoff = pendingHandoff
                                if (handoff?.decision == HandoffDecision.Bootstrap &&
                                    (handoff.fg != fg || handoff.state != state)
                                ) {
                                    val identity = currentIdentity ?: return@withLock
                                    beginHandoff(
                                        kind = HandoffKind.StartSse,
                                        fromLayer = Layer.L3,
                                        identity = identity,
                                        decision = HandoffDecision.Bootstrap,
                                        fg = fg,
                                        state = state,
                                    )
                                }
                                // Otherwise §4.1: no automatic recovery from L3.
                            }
                        }
                    }
                }
        }
    }

    /**
     * Indirection so the debounce coroutine is launched on the coordinator's
     * scope (Main.immediate). Extracted as a private extension so test code
     * path is identical to production.
     */
    private fun CoroutineScope.launchDebounce(block: suspend CoroutineScope.() -> Unit): Job =
        this.launch(block = block)

    companion object {
        private const val TAG = "StreamingLifecycleCoordinator"

        /**
         * FGS spec §4.1: L2-active all-idle → wait 45s → L2-idle. The
         * debounce prevents a sub-second idle blip (e.g. between two tasks)
         * from tearing down the SSE feed.
         */
        const val IDLE_DEBOUNCE_MS = 45_000L

        /**
         * Command-channel capacity. Generous vs. the few commands a single
         * transition emits (≤ 4) so the serial state machine rarely
         * suspends; bounded so a stuck consumer cannot grow it without limit.
         */
        private const val COMMAND_CHANNEL_CAPACITY = 64
    }
}


