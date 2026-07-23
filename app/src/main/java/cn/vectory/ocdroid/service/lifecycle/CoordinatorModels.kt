package cn.vectory.ocdroid.service.lifecycle

import cn.vectory.ocdroid.service.identity.ConnectionIdentity

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

    /**
     * Stop the poller (SSE has become the active source again). D5 (#2):
     * emitted by [StreamingLifecycleCoordinator.stopPollerIfActiveLocked]
     * exactly once per active→Stopped transition (no duplicate emissions on
     * repeated definitive verdicts).
     */
    data object StopPoller : LifecycleCommand

    /**
     * D5 (#2) — ensure a SUPPLEMENTAL process poller is running for
     * [identity]. Emitted by [StreamingLifecycleCoordinator.
     * ensureSupplementalPollerLocked] when an SSE Bootstrap/L2Idle-exit
     * commit finds the status verdict Unknown (the SSE transport proved
     * itself but the REST status snapshot is not yet definitive). The
     * controller acks via [StreamingLifecycleCoordinator.onEnsurePollerAck]
     * with the matching [requestId].
     *
     * NOT a source-switch handoff — no SSE handoff token. The supplemental
     * poller runs ALONGSIDE the SSE transport (both are active sources).
     * Retired by [StopPoller] once a definitive verdict arrives.
     */
    data class EnsurePoller(
        val identity: ConnectionIdentity,
        val requestId: Long,
    ) : LifecycleCommand
}
