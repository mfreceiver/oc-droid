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
     */
    private data class Handoff(
        val token: Long,
        val kind: HandoffKind,
        /** The layer the coordinator was in when the handoff was allocated. */
        val fromLayer: Layer,
        /** The identity captured at handoff allocation (for reconfigure detect). */
        val expectedIdentity: ConnectionIdentity,
        /** The deferred the commit coroutine awaits; completed by [onActivationAck]. */
        val deferred: CompletableDeferred<SourceActivation>,
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
     * ([cn.vectory.ocdroid.service.streaming.SessionStreamingController.handleUserClose])
     * BEFORE this entry — by the time [requestUserClose] is called the action
     * has been revalidated against the current identity and the final status
     * snapshot has been refreshed. Busy/Unknown does NOT veto an explicit
     * user close (§16-U1 implementation note).
     */
    fun requestUserClose() {
        DebugLog.i(TAG, "requestUserClose (§16-U1)")
        scope.launch { teardown() }
    }

    /**
     * §4.1 dataSync `onTimeout()` entry (targetSdk 35+ 6h/24h limit). L3
     * teardown; the machine cannot restart the FGS from L3.
     */
    fun onTimeout() {
        DebugLog.i(TAG, "onTimeout (§4.1 dataSync timeout)")
        scope.launch { teardown() }
    }

    /**
     * §4.1 disconnect entry (SSEConnectionExhausted past the service-level
     * retry budget). L3 teardown.
     */
    fun onDisconnect() {
        DebugLog.i(TAG, "onDisconnect (§4.1)")
        scope.launch { teardown() }
    }

    override suspend fun teardownAndAwait(reason: TeardownReason) {
        DebugLog.i(TAG, "teardownAndAwait(reason=$reason)")
        teardown()
        layer.first { it == Layer.L3 }
        // L3 is committed only after D2's replacement poller has acknowledged
        // readiness. Join the old transport after that handoff, but before a
        // reconfigure caller is allowed to rebuild the repository client.
        ownershipGate?.disconnectAndRelease(markGap = true)
    }
    /**
     * D2 (gate #4): the controller's completion signal for a source activation.
     * Completes the [Handoff.deferred] keyed by [token]; the corresponding
     * commit coroutine (launched in [beginHandoff]) reacquires the mutex +
     * runs the transition's commit logic.
     *
     * A stale ack (for a superseded handoff) is dropped silently — the
     * controller may still be running an activation that has been superseded
     * by a newer handoff; the ack arrives, finds the token no longer matches
     * [pendingHandoff], and is ignored. No commands emitted.
     *
     * Safe to call from any thread — completes the deferred under [mutex].
     */
    suspend fun onActivationAck(token: Long, activation: SourceActivation) {
        val deferred = mutex.withLock {
            val ph = pendingHandoff
            if (ph?.token == token) ph.deferred else null
        }
        deferred?.complete(activation)
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
     * activation is cleaned up) + cancels the deferred (the commit coroutine
     * awaiting it bails).
     *
     * MUST be called under [mutex].
     */
    private suspend fun cancelPendingHandoff() {
        val ph = pendingHandoff ?: return
        emitStopFor(ph.kind)
        ph.deferred.cancel()
        pendingHandoff = null
    }

    /**
     * D2: allocates a handoff token + emits the [LifecycleCommand.StartX]
     * carrying it + records [pendingHandoff] + launches the commit coroutine.
     * MUST be called under [mutex] (the caller — a transition handler — already
     * holds it).
     *
     * If a prior handoff is pending, it is superseded: its StopX is emitted
     * (so the dangling activation is cleaned up) + its deferred is cancelled
     * (its commit coroutine bails). The new handoff's StartX is emitted
     * AFTER the prior's StopX (FIFO channel — the controller observes the
     * cleanup before the new activation).
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
        val deferred = CompletableDeferred<SourceActivation>()
        pendingHandoff = Handoff(
            token = token,
            kind = kind,
            fromLayer = fromLayer,
            expectedIdentity = identity,
            deferred = deferred,
            decision = decision,
            fg = fg,
            state = state,
        )
        when (kind) {
            HandoffKind.StartPoller -> emit(StartPoller(identity, token))
            HandoffKind.StartSse -> emit(StartSse(identity, token))
        }
        scope.launchCommit(token, deferred, decision, fromLayer, identity, kind, fg, state)
    }

    /**
     * D2: the handoff commit coroutine. Awaits the activation ack WITHOUT
     * holding the mutex (a long SSE connect attempt must NOT block
     * foreground/timeout/user-close inputs), then REACQUIRES the mutex to
     * verify the handoff is still valid + commit the transition.
     *
     * Verification (under mutex):
     *  - [pendingHandoff]'s token MUST still match — a newer handoff
     *    superseding us is the canonical invalidation.
     *  - `_layer.value` MUST still equal [fromLayer] — a concurrent
     *    foreground/timeout/user-close transition changed the layer.
     *  - `currentIdentity` MUST still equal [expectedIdentity] — a
     *    reconfigure (via a new [onBootstrapResult]) replaced the identity.
     *
     * If any check fails: cancel the activation we started (emit the matching
     * StopX) + clear pendingHandoff. The concurrent transition (or teardown)
     * is responsible for the rest of the cleanup.
     *
     * If all checks pass: run the [HandoffDecision]-specific commit logic,
     * which emits the closing StopX + flips the layer.
     */
    private fun CoroutineScope.launchCommit(
        token: Long,
        deferred: CompletableDeferred<SourceActivation>,
        decision: HandoffDecision,
        fromLayer: Layer,
        expectedIdentity: ConnectionIdentity,
        kind: HandoffKind,
        fg: Boolean,
        state: GlobalBusyState,
    ): Job = launch {
        val activation: SourceActivation = try {
            deferred.await()
        } catch (e: CancellationException) {
            // Superseded (beginHandoff's cancelPendingHandoff cancelled us)
            // OR the coordinator scope was cancelled (process death). Bail
            // silently — the superseder / teardown handles cleanup.
            return@launch
        }
        mutex.withLock {
            val ph = pendingHandoff
            if (ph?.token != token) {
                // Superseded by a newer handoff — the newer one already
                // cleaned up our source. Bail.
                return@withLock
            }
            val layerValid = _layer.value == fromLayer
            val identityValid = currentIdentity == expectedIdentity
            if (!layerValid || !identityValid) {
                // Concurrent input changed the layer OR a reconfigure changed
                // the identity mid-handoff. Cancel our activation; the
                // concurrent transition handles the rest.
                emitStopFor(kind)
                pendingHandoff = null
                return@withLock
            }
            // Commit — decision-specific.
            runDecisionCommit(decision, activation, fg, state)
            pendingHandoff = null
        }
    }

    /**
     * D2: the decision-specific commit logic. Runs under [mutex] inside
     * [launchCommit] AFTER the activation ack verified the handoff is still
     * current. Emits the closing StopX + flips the layer.
     */
    private suspend fun runDecisionCommit(
        decision: HandoffDecision,
        activation: SourceActivation,
        fg: Boolean,
        state: GlobalBusyState,
    ) {
        when (decision) {
            HandoffDecision.DebounceFire -> {
                // L2Active → L2Idle: poller's final poll decides.
                when (activation) {
                    is SourceActivation.Ready -> {
                        if (activation.state == GlobalBusyState.AllIdleFresh) {
                            // Commit: StopSse (close old) + flip to L2Idle.
                            emit(StopSse)
                            _layer.value = Layer.L2Idle
                        } else {
                            // Busy/Unknown — stop the new poller, stay L2Active.
                            // SSE was never closed.
                            emit(StopPoller)
                        }
                    }
                    is SourceActivation.Rejected -> {
                        // Don't commit; stop the new poller, stay L2Active.
                        emit(StopPoller)
                    }
                }
            }
            HandoffDecision.L2IdleExit -> {
                // L2Idle → L1/L2Active: SSE readiness decides.
                when (activation) {
                    is SourceActivation.Ready -> {
                        // Commit: StopPoller (close old) + flip layer.
                        emit(StopPoller)
                        if (fg) {
                            if (state.isKeepAlive) {
                                _layer.value = Layer.L1(busy = true)
                            } else {
                                emit(StopForeground)
                                _layer.value = Layer.L1(busy = false)
                            }
                        } else {
                            _layer.value = Layer.L2Active
                        }
                    }
                    is SourceActivation.Rejected -> {
                        // Don't commit. Poller stays running. Stay L2Idle.
                    }
                }
            }
            HandoffDecision.Teardown -> {
                // → L3: regardless of activation (poller's state only affects
                // the aggregator / notification, not the layer decision — L3
                // is terminal for this run).
                emit(StopSse)
                emit(StopForeground)
                emit(StopSelf)
                _layer.value = Layer.L3
            }
            HandoffDecision.Bootstrap -> {
                // L3 → L1/L2Active: SSE readiness decides.
                when (activation) {
                    is SourceActivation.Ready -> {
                        emit(StopPoller)
                        val keepAlive = state.isKeepAlive
                        if (fg) {
                            if (keepAlive) {
                                _layer.value = Layer.L1(busy = true)
                            } else {
                                emit(StopForeground)
                                _layer.value = Layer.L1(busy = false)
                            }
                        } else {
                            if (keepAlive) {
                                _layer.value = Layer.L2Active
                            } else {
                                // Background idle (rare at bootstrap — the
                                // status snapshot was AllIdleFresh; the SSE
                                // activation succeeded anyway). Don't burn an
                                // FGS slot on idle background.
                                emit(StopForeground)
                                emit(StopSelf)
                                // Stay L3.
                            }
                        }
                    }
                    is SourceActivation.Rejected -> {
                        // SSE couldn't establish baseline. Stay L3. The
                        // bootstrap retry path (or the user re-opening the
                        // app) handles recovery. Poller stays running (the
                        // L3 source).
                    }
                }
            }
        }
    }

    /**
     * D2: emits the StopX command that cancels a [kind] activation. Used by
     * [cancelPendingHandoff] + the supersede path in [launchCommit].
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

/**
 * The layered lifecycle state (FGS spec §4.1). See
 * [StreamingLifecycleCoordinator] for the transition rules.
 */
sealed interface Layer {
    /**
     * Foreground. [busy] distinguishes:
     *  - `busy=false` — L1-idle: normal started Service (no FGS slot), SSE
     *    alive (§4.1 decision 4);
     *  - `busy=true` — L1-busy: dataSync FGS pre-warmed while still
     *    foreground (§4.2), so the subsequent background transition keeps it
     *    without tripping a background FGS-start denial.
     */
    data class L1(val busy: Boolean) : Layer

    /** Background + global busy. dataSync FGS, SSE alive, ongoing "N tasks". */
    data object L2Active : Layer

    /**
     * Background, authoritative all-idle AND the 45s debounce expired. FGS
     * shell KEPT (no stopSelf), SSE off, poller on. Poller finding busy
     * returns IN-PLACE to [L2Active] (groker-R1).
     */
    data object L2Idle : Layer

    /**
     * Torn down. No FGS, no SSE, poller only. Entered via
     * [StreamingLifecycleCoordinator.requestUserClose] /
     * [StreamingLifecycleCoordinator.onTimeout] /
     * [StreamingLifecycleCoordinator.onDisconnect] / §5 step 5 idle
     * background. No automatic recovery (§4.1).
     */
    data object L3 : Layer
}

/**
 * Commands the service observes (§4.4). Emitted in the order each transition
 * requires; the service executes them serially.
 *
 * **D2 (gate #4)**: [StartSse] and [StartPoller] are ACKNOWLEDGEABLE source
 * activations — each carries a [handoffToken] that the controller uses when
 * calling [StreamingLifecycleCoordinator.onActivationAck] after the source
 * establishes (or fails to establish) a verified baseline. The token
 * uniquely identifies the handoff for which this activation was started; a
 * stale activation (superseded mid-flight) is dropped by the ack handler.
 */
sealed interface LifecycleCommand {
    /**
     * Promote the service to dataSync FGS (§4.2 L1-idle→L1-busy elevation).
     * Idempotent if already in foreground.
     */
    data object StartForeground : LifecycleCommand

    /**
     * Downgrade from FGS to a normal started Service (§4.1 L1-idle, or
     * foreground return while idle). Does NOT stopSelf.
     */
    data object StopForeground : LifecycleCommand

    /** §4.4 teardown: stopSelf after stopForeground. Terminal for this run. */
    data object StopSelf : LifecycleCommand

    /**
     * Start the SSE collector bound to [identity] (FGS spec §2 — every
     * published event carries this identity; the bridge and SSC.fold
     * validate it before any side effect).
     *
     * D2: [handoffToken] identifies the coordinator handoff this activation
     * belongs to — the controller acks with the same token via
     * [StreamingLifecycleCoordinator.onActivationAck] once the collector
     * establishes (or fails to establish) a verified baseline.
     */
    data class StartSse(val identity: ConnectionIdentity, val handoffToken: Long) : LifecycleCommand

    /** Stop the SSE collector (leaves the gap-dirty signal, §1.1). */
    data object StopSse : LifecycleCommand

    /**
     * Arm / start the background poller (the L2Idle / L3 source). §6 —
     * single notifier, the poller is the fallback data source.
     *
     * D2: [handoffToken] identifies the coordinator handoff this activation
     * belongs to — the controller acks after the immediate-first-poll
     * completes with [SourceActivation.Ready] carrying the time-correct
     * [cn.vectory.ocdroid.service.status.GlobalBusyState].
     */
    data class StartPoller(
        val identity: ConnectionIdentity,
        val handoffToken: Long,
    ) : LifecycleCommand

    /** Stop the poller (SSE has become the active source again). */
    data object StopPoller : LifecycleCommand
}
