package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.isSseDown
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * rev-ogpt B (Disconnected 周期重探): a resident supervisor that self-heals the
 * connection banner's REST_OUTAGE dead-lock.
 *
 * **Problem**: the health probe is purely event-driven (cold start / manual
 * refresh / foreground return). After a transient network blip writes
 * [ConnectionPhase.Disconnected], NOTHING re-probes in the background — so once
 * the network recovers, the banner stays stuck REST_OUTAGE until the user
 * manually refreshes AND that probe happens to succeed. This controller adds the
 * missing periodic self-heal.
 *
 * **Design**:
 *  - [start] launches ONE supervisor coroutine on the injected [scope] that
 *    observes `combine(connectionFlow, isInForeground)`. When the
 *    [needsReprobe] predicate holds (SSE-down + foreground + no permanent
 *    failure signal + no probe in flight), it launches a single [runEpisode]
 *    backoff loop.
 *  - [runEpisode] re-probes via the injected [probe]
 *    (healthProbe.testConnection force=true retries=0) on an escalating backoff
 *    (5s/15s/30s/60s/120s, capped at 120s). On success the probe's OWN success
 *    path writes Connected + loadInitialData + connectSseAndAwait — **this
 *    controller NEVER writes connectionFlow directly** (invariant #2).
 *  - **Single-flight** is handled by the production [ConnectionBootstrapEngine]
 *    (mutex + same-key joiner await): a reprobe probe racing a manual refresh /
 *    foreground ForceReconnect collapses to ONE real network probe. No extra
 *    de-dup is needed here.
 *
 * **Exit conditions** (episode terminates): phase leaves `isSseDown` (recovered
 * by any path) / `authFailureReason` set (user must fix creds — invariant #1) /
 * `mtlsDegradedError` set (user must fix config) / backgrounded (zero network in
 * background — invariant #3) / epoch changed (host switch — the new generation's
 * coldStart takes over) / probe succeeds / scope cancelled (structured
 * concurrency auto-cleanup — invariant #4). The 120s cap is the steady state
 * (invariant #5): battery-acceptable, and持续探活 is the desired behaviour under
 * a real outage.
 *
 * **Zero-regression gate**: [ConnectionCoordinator] only constructs this when
 * BOTH `appLifecycleMonitor` AND `identityStore` are non-null (production
 * wiring). The legacy [ConnectionCoordinatorTest] fixture leaves
 * `appLifecycleMonitor` null → this controller is never started → existing
 * tests are untouched (invariant verified by the full test suite).
 *
 * **Known limitation (rev-3 MAJOR 1, accepted)**: the probe runs via
 * [ConnectionHealthProbe.testConnection] which launches on the shared app
 * scope — cancelling this controller's episode does NOT cancel an in-flight
 * probe. If the app backgrounds while a probe is running, the probe may
 * complete and write Connected / connect SSE. This is the same design as all
 * other testConnection callers (manual refresh, cold start, foreground
 * reconnect). The proper fix (testConnection as suspend / returning a
 * cancellable Job) is deferred to a future API refactor.
 */
internal class ConnectionReprobeController(
    private val scope: CoroutineScope,
    private val connectionFlow: StateFlow<ConnectionState>,
    private val isInForeground: StateFlow<Boolean>,
    private val currentEpoch: () -> Long,
    private val probe: (onSettled: (Boolean) -> Unit) -> Unit,
) {
    @Volatile
    private var episodeJob: Job? = null

    /**
     * Launches the resident supervisor. Idempotent: launching twice starts two
     * supervisors (the second would just observe the same flows and no-op while
     * [episodeJob] is active); [ConnectionCoordinator] constructs + starts
     * exactly once.
     */
    fun start() {
        scope.launch {
            // Re-evaluate the start/cancel decision ONLY when the reprobe
            // predicate OR the foreground axis flips. Connection-state churn
            // within the same (needed, foreground) tuple — notably the probe's
            // OWN transient isConnecting blip — must NOT spuriously re-trigger
            // (it would either double-launch episodes or cancel mid-probe).
            combine(connectionFlow, isInForeground) { c, fg -> Triple(c, fg, needsReprobe(c, fg)) }
                .distinctUntilChanged { old, new -> old.third == new.third && old.second == new.second }
                .collect { (conn, fg, needed) ->
                    if (needed && episodeJob?.isActive != true) {
                        episodeJob = launch { runEpisode(epochAtStart = currentEpoch()) }
                    } else if (!needed && episodeJob != null &&
                               // ★MOST-ERROR-PRONE POINT: isConnecting here can
                               // be the reprobe's OWN probe transient (the probe
                               // coroutine writes Connecting as its first step).
                               // Cancelling on it would kill the episode
                               // mid-probe; the subsequent failure write
                               // (Disconnected) would then relaunch at attempt=0
                               // → backoff never advances past the first tier.
                               // Cancel ONLY when not connecting OR a permanent
                               // signal appeared OR we backgrounded.
                               (!conn.isConnecting || conn.authFailureReason != null ||
                                conn.mtlsDegradedError != null || !fg)) {
                        episodeJob?.cancel()
                        episodeJob = null
                    }
                }
        }
    }

    /**
     * One backoff episode. Runs while the connection stays SSE-down + foreground
     * + same identity generation. Every loop iteration re-validates the decision
     * point (the collect predicate and the fire-moment can diverge if state
     * moved in between) before firing a probe.
     */
    private suspend fun runEpisode(epochAtStart: Long) {
        var attempt = 0
        DebugLog.i(TAG, "reprobe episode started (epoch=$epochAtStart)")
        while (coroutineContext.isActive) {
            delay(DELAYS[attempt.coerceAtMost(DELAYS.lastIndex)])
            val conn = connectionFlow.value
            // Decision-point re-validation (state may have moved since collect).
            if (!conn.connectionPhase.isSseDown) {
                DebugLog.i(TAG, "reprobe episode exiting: phase recovered (${conn.connectionPhase})")
                return
            }
            if (conn.authFailureReason != null || conn.mtlsDegradedError != null ||
                conn.slimapiVersionIncompatible != null) {
                DebugLog.i(TAG, "reprobe episode exiting: permanent failure signal (auth=${conn.authFailureReason != null}, mtls=${conn.mtlsDegradedError != null}, version=${conn.slimapiVersionIncompatible != null})")
                return
            }
            if (!isInForeground.value) {
                DebugLog.i(TAG, "reprobe episode exiting: backgrounded")
                return
            }
            if (currentEpoch() != epochAtStart) {
                DebugLog.i(TAG, "reprobe episode exiting: epoch advanced ($epochAtStart -> ${currentEpoch()}), new generation takes over")
                return
            }
            if (conn.isConnecting) continue // another probe in flight: defer, hold this backoff tier
            if (!doProbe()) return // probe succeeded or episode should exit
            attempt++
            DebugLog.i(TAG, "reprobe probe failed; advancing to backoff tier $attempt/${DELAYS.lastIndex}")
        }
    }

    /**
     * Performs one probe call and awaits settlement. Returns true if the probe
     * failed (episode should continue), false if the probe succeeded (episode
     * should exit). Extracted for testability — tests can subclass or mock.
     */
    private suspend fun doProbe(): Boolean {
        val settled = CompletableDeferred<Boolean>()
        probe { ok -> settled.complete(ok) } // testConnection(force=true, retries=0)
        val ok = withTimeoutOrNull(SETTLE_TIMEOUT_MS) { settled.await() } ?: false
        if (ok) {
            DebugLog.i(TAG, "reprobe episode exiting: probe succeeded (Connected written by probe)")
            return false
        }
        return true
    }

    /**
     * True iff the connection is SSE-down, in the foreground, no probe in
     * flight, and no permanent-failure signal the user must resolve (bad creds /
     * mTLS config / incompatible server version). Permanent signals deliberately
     * STOP re-probing (探无意义) until the user acts — re-probing a 401 only
     * burns battery.
     */
    private fun needsReprobe(conn: ConnectionState, foreground: Boolean): Boolean =
        foreground &&
            conn.connectionPhase.isSseDown &&
            !conn.isConnecting &&
            conn.authFailureReason == null &&
            conn.mtlsDegradedError == null &&
            conn.slimapiVersionIncompatible == null

    companion object {
        const val TAG = "ReprobeCtrl"

        /**
         * Escalating reprobe backoff (ms). Capped at 120s (the last tier) —
         * battery-acceptable for a real outage where持续探活 is the desired
         * behaviour (the user is staring at a red banner waiting for recovery).
         */
        val DELAYS = listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L)

        /** Safety net: if the probe's onSettled callback is never invoked, treat it as a failure after this. */
        const val SETTLE_TIMEOUT_MS = 60_000L
    }
}
