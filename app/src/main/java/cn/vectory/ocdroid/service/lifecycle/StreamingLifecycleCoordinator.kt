package cn.vectory.ocdroid.service.lifecycle

import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand.StartForeground
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand.StopForeground
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand.StopPoller
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand.StopSelf
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand.StartPoller
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand.StartSse
import cn.vectory.ocdroid.service.lifecycle.LifecycleCommand.StopSse
import cn.vectory.ocdroid.service.status.StatusAggregator
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
 * **§4.4 unified handoff ordering**: every source switch (SSE↔poller) is
 * emitted as an ordered command sequence — **new source active + notifier
 * switched BEFORE closing the old source**. There is never a "no data source"
 * middle state. The ordering is encoded in the command emission order of each
 * transition handler; [commands] is a single-consumer FIFO [Channel] so the
 * service observes the exact order.
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
 *  - [StatusAggregator.globalBusy] (authoritative busy — Lane A impl);
 *  - explicit entries [requestUserClose] (§16-U1), [onTimeout] (dataSync
 *    timeout), [onDisconnect];
 *  - [onBootstrapResult] — the §5 START_STICKY bootstrap completion signal
 *    carrying the fresh [ConnectionIdentity]; the ONLY legal entry out of L3.
 *
 * **Outputs** — [commands]: a [Flow] of [LifecycleCommand] the service
 * observes (start/stop foreground, start/stop SSE, start/stop poller,
 * stopSelf). Notifier data-source switching (§6 unified IslandNotifier) is a
 * Phase-1 seam and not represented here.
 *
 * **Thread safety**: all state reads/writes happen under [mutex]. The
 * coordinator runs on the injected [UiApplicationScope] (Main.immediate) so
 * commands are emitted on the main thread — the service MUST call
 * `startForeground`/`stopForeground` on the main thread.
 */
@Singleton
class StreamingLifecycleCoordinator @Inject constructor(
    private val statusAggregator: StatusAggregator,
    @UiApplicationScope private val scope: CoroutineScope,
) {
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
     * Binds the foreground signal and (re)starts observation. Idempotent.
     * Must be called before [onBootstrapResult] so the initial transition
     * out of L3 knows whether §5 is happening in a foreground or background
     * context (§4.3).
     */
    fun start(inForeground: StateFlow<Boolean>) {
        if (started) return
        started = true
        inForegroundRef = inForeground
        scope.launchObserving(inForeground, statusAggregator.globalBusy)
    }

    /**
     * §5 START_STICKY bootstrap completion — the ONLY legal L3 → running
     * transition. Carries the fresh [ConnectionIdentity] (so subsequent
     * [StartSse] commands tag events correctly, FGS spec §2) and the
     * authoritative busy verdict from the §3 global status snapshot.
     *
     * Decision matrix (§5 steps 4–5 / §4.2):
     *  - busy + foreground → [Layer.L1](busy=true): FGS held (service §5
     *    step 2), SSE on, layer records busy.
     *  - busy + background → [Layer.L2Active]: same, background flavour.
     *  - idle + foreground → [Layer.L1](busy=false): SSE on, FGS downgraded
     *    to normal Service (§4.1 L1-idle "普通 Service").
     *  - idle + background → [Layer.L3]: §5 step 5 `stopForeground` +
     *    `stopSelf` (no FGS slot burned on an idle background service).
     */
    fun onBootstrapResult(identity: ConnectionIdentity, busy: Boolean) {
        scope.launch {
            mutex.withLock {
                currentIdentity = identity
                val fg = inForegroundRef?.value ?: false
                transitionFromL3(fg, busy, identity)
            }
        }
    }

    /**
     * §16-U1 user-explicit-close entry (ongoing notification Action
     * "关闭后台"). Triggers L3 teardown regardless of current layer. The
     * notification layer re-queries identity/status before invoking this
     * (§16-U1 implementation note).
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

    // ── Transition handlers (all under [mutex]) ─────────────────────────────

    /**
     * L3 → running state. Emitted only by [onBootstrapResult]. Per §4.4 the
     * SSE source is started (and the L3 poller stopped) BEFORE the layer
     * flips — new source active before retiring the old.
     */
    private suspend fun transitionFromL3(fg: Boolean, busy: Boolean, identity: ConnectionIdentity) {
        when {
            busy -> {
                // §5 step 4: keep FGS (held by service step 2) + SSE on.
                // L3 had poller running → stop it AFTER starting SSE (§4.4).
                emit(StartSse(identity))
                emit(StopPoller)
                _layer.value = if (fg) Layer.L1(busy = true) else Layer.L2Active
            }
            fg -> {
                // §4.1 L1-idle: SSE on, normal Service (downgrade FGS).
                emit(StartSse(identity))
                emit(StopPoller)
                emit(StopForeground)
                _layer.value = Layer.L1(busy = false)
            }
            else -> {
                // §5 step 5: background all-idle → L3 (teardown, no FGS slot).
                emit(StopForeground)
                emit(StopSelf)
                _layer.value = Layer.L3
            }
        }
    }

    /**
     * §4.4 L1 internal conversions + L1→L2/L3 transitions.
     */
    private suspend fun handleL1(current: Layer.L1, fg: Boolean, busy: Boolean) {
        if (fg) {
            if (current.busy == busy) return // no sub-state change
            if (busy) {
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
            if (busy) {
                // §4.2 L1-busy → L2Active: FGS already held, just layer change. SSE stays on.
                _layer.value = Layer.L2Active
            } else {
                // §4.2 L1-idle → background: NO FGS promotion attempted → L3.
                // §4.4 teardown: poller (new source) before StopSse (old).
                emit(StartPoller)
                emit(StopSse)
                emit(StopForeground)
                emit(StopSelf)
                _layer.value = Layer.L3
            }
        }
    }

    /**
     * §4.4 L2Active transitions: → L1 (foreground return), → L2Idle (idle
     * debounce), or stay.
     */
    private suspend fun handleL2Active(fg: Boolean, busy: Boolean) {
        cancelDebounce()
        if (fg) {
            // L2Active → L1: SSE was on, stays on (no source switch).
            if (busy) {
                _layer.value = Layer.L1(busy = true)
            } else {
                // §4.4: idle on foreground return → stopForeground → L1-idle.
                emit(StopForeground)
                _layer.value = Layer.L1(busy = false)
            }
        } else {
            if (busy) {
                // still busy; stay L2Active (debounce cancelled above, no-op).
            } else {
                // §4.4: all-idle → arm the 45s debounce.
                startIdleDebounce()
            }
        }
    }

    /**
     * §4.4 L2Idle transitions: → L2Active (poller finds busy, IN-PLACE per
     * groker-R1), → L1 (foreground return), or stay.
     */
    private suspend fun handleL2Idle(fg: Boolean, busy: Boolean) {
        val identity = currentIdentity ?: run {
            DebugLog.w(TAG, "handleL2Idle: no identity bound — skipping transition")
            return
        }
        if (fg) {
            // L2Idle → L1: StartSse (new source) before StopPoller (old). §4.4.
            emit(StartSse(identity))
            emit(StopPoller)
            if (busy) {
                _layer.value = Layer.L1(busy = true)
            } else {
                emit(StopForeground)
                _layer.value = Layer.L1(busy = false)
            }
        } else {
            if (busy) {
                // groker-R1: IN-PLACE L2Idle → L2Active (no new startForegroundService).
                // §4.4: StartSse (new source) before StopPoller (old).
                emit(StartSse(identity))
                emit(StopPoller)
                _layer.value = Layer.L2Active
            } else {
                // still idle; stay L2Idle.
            }
        }
    }

    /**
     * §4.4 → L3 teardown: notifier→polling, cancelSse, stopForeground /
     * stopSelf. The poller is started (if not already the active source)
     * BEFORE the SSE is closed — no "no data source" middle state.
     */
    private suspend fun teardown() {
        mutex.withLock {
            cancelDebounce()
            when (_layer.value) {
                is Layer.L1, Layer.L2Active -> {
                    // SSE was source → start poller (new) before StopSse (old).
                    emit(StartPoller)
                    emit(StopSse)
                }
                Layer.L2Idle -> {
                    // poller already running, SSE already off — nothing to switch.
                }
                Layer.L3 -> return@withLock // already torn down
            }
            emit(StopForeground)
            emit(StopSelf)
            _layer.value = Layer.L3
        }
    }

    /**
     * §4.4 L2Active → L2Idle: arms the 45s idle debounce. Fires only if still
     * L2Active + authoritative all-idle at expiry. On fire: StartPoller (new
     * source) BEFORE StopSse (old), then flip to L2Idle (FGS shell kept).
     */
    private fun startIdleDebounce() {
        debounceJob?.cancel()
        debounceJob = scope.launchDebounce {
            delay(IDLE_DEBOUNCE_MS)
            mutex.withLock {
                if (_layer.value == Layer.L2Active && !statusAggregator.globalBusy.value) {
                    emit(StartPoller)
                    emit(StopSse)
                    _layer.value = Layer.L2Idle
                }
            }
        }
    }

    private fun cancelDebounce() {
        debounceJob?.cancel()
        debounceJob = null
    }

    private suspend fun emit(command: LifecycleCommand) {
        commandChannel.send(command)
    }

    /**
     * launches the (fg, busy) observer that drives [handleL1]/[handleL2Active]/
     * [handleL2Idle] on every input change. L3 inputs are ignored (no
     * auto-recovery — §4.1).
     */
    private fun CoroutineScope.launchObserving(
        inForeground: StateFlow<Boolean>,
        globalBusy: StateFlow<Boolean>,
    ) {
        launch {
            combine(inForeground, globalBusy) { fg, busy -> fg to busy }
                .collect { (fg, busy) ->
                    mutex.withLock {
                        when (val current = _layer.value) {
                            is Layer.L1 -> handleL1(current, fg, busy)
                            Layer.L2Active -> handleL2Active(fg, busy)
                            Layer.L2Idle -> handleL2Idle(fg, busy)
                            Layer.L3 -> {
                                // §4.1: no automatic recovery from L3.
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
     */
    data class StartSse(val identity: ConnectionIdentity) : LifecycleCommand

    /** Stop the SSE collector (leaves the gap-dirty signal, §1.1). */
    data object StopSse : LifecycleCommand

    /**
     * Arm / start the background poller (the L2Idle / L3 source). §6 —
     * single notifier, the poller is the fallback data source.
     */
    data object StartPoller : LifecycleCommand

    /** Stop the poller (SSE has become the active source again). */
    data object StopPoller : LifecycleCommand
}
