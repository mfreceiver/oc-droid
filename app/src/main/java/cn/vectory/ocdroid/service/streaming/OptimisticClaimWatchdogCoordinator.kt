package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.state.AuthorityState
import cn.vectory.ocdroid.di.ApplicationScope
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.OPTIMISTIC_CLAIM_WATCHDOG_TICK_MS
import cn.vectory.ocdroid.ui.OPTIMISTIC_CONFIRM_TIMEOUT_MS
import cn.vectory.ocdroid.ui.StaleClaim
import cn.vectory.ocdroid.ui.selectStaleClaimsForReconcile
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Singleton

/**
 * §U-P2 (Batch 2): the **independent** optimistic-claim watchdog coordinator.
 *
 * # Why this exists (decoupling from [ProcessStatusPoller])
 *
 * Pre-U-P2 the watchdog logic lived INSIDE [ProcessStatusPoller.runWatchdog]
 * and ran on the poller's 30s tick. A POST that stamped an optimistic claim
 * thus waited UP TO 30s for the next tick before the stale-claim reconcile
 * was even detected — actual self-heal SLA ≈ 30s + GET RTT. The target SLA
 * is ~7.5s (5s `OPTIMISTIC_CONFIRM_TIMEOUT_MS` + ~2.5s GET RTT). Running the
 * watchdog on its OWN 5s timer (independent of the 30s bulk poller) cuts the
 * worst-case detection latency from ~30s to ~5s.
 *
 * The watchdog loop runs on the SAME `@ApplicationScope` as the poller
 * (process-lifetime — survives [cn.vectory.ocdroid.service.SessionStreamingService.onDestroy]),
 * but is launched / cancelled INDEPENDENTLY by the connection lifecycle (the
 * ServiceShell's [startPoller] / [ensurePoller] / [stopPoller] /
 * [enterNoSourceTerminal] now also call [start] / [stop] on this coordinator).
 *
 * # Purity / re-use
 *
 * The actual stale-claim selection is the PRE-EXISTING pure function
 * [selectStaleClaimsForReconcile] (`(authority, now, timeoutMs) -> List<StaleClaim>`).
 * This coordinator is only the timer + identity-guard + sink-dispatch shell
 * around that pure function — it owns NO detection logic.
 *
 * # Identity discipline (mirrors [ProcessStatusPoller.runSlimFanOut])
 *
 * Each tick re-reads [ConnectionIdentityStore.currentIdentity] and re-checks
 * [ConnectionIdentityStore.isCurrent] BEFORE selecting stale claims AND BEFORE
 * sinking — a host switch during the tick invalidates every reconcile outcome.
 *
 * # Generation fence
 *
 * [start] / [stop] bump a [generation] counter; the launched loop captures
 * the generation at start and breaks when it advances (a [stop] or a
 * superseding [start] invalidates an in-flight tick). This mirrors
 * [ProcessStatusPoller]'s generation discipline so a stale tick that woke up
 * after a [stop] does NOT sink.
 *
 * Construction: `@Singleton` + `internal constructor` (the clock / sink /
 * authorityState function-type defaults cannot be Hilt-provided directly —
 * mirrors [ProcessStatusPoller]'s pattern); the `@Provides` in
 * [StreamingModule]'s [ProcessStatusPollerModule] fills the function-typed
 * deps. The Hilt container still treats this as a singleton.
 *
 * @param scope the process-lifetime [CoroutineScope] (D2: `@ApplicationScope` =
 *   SupervisorJob + Dispatchers.Default — survives Service.onDestroy, same as
 *   the poller).
 * @param authorityState thunk reading the LIVE authority snapshot at each tick
 *   (production: `sessionSyncCoordinator.currentAuthority()`). The thunk is
 *   re-read every tick so a dispatch that lands between ticks is observed.
 * @param identityStore the single process-level identity guard (§2 epoch).
 * @param clock the watchdog clock (production: wall-clock millis — the SAME
 *   clock domain as the claim's `claimedAtMs` so the age comparison in
 *   [selectStaleClaimsForReconcile] is consistent).
 * @param staleClaimReconcileSink the sink that turns detected stale claims
 *   into per-sid reconcile GETs (production routes through
 *   [cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator.reconcileStaleOptimisticClaims]).
 *   Invoked ONLY when the identity is still current at sink time.
 * @param tickIntervalMs the watchdog tick interval. Production =
 *   [OPTIMISTIC_CLAIM_WATCHDOG_TICK_MS] (1s — STRICTLY LESS THAN the 5s
 *   [OPTIMISTIC_CONFIRM_TIMEOUT_MS] so worst-case detection ≤ timeout+tick
 *   ≈ 6s, honoring the ~7.5s self-heal SLA; rev-gpt gate r1 #2). Test
 *   override (tests pass a short interval or drive the virtual clock).
 */
@Singleton
class OptimisticClaimWatchdogCoordinator internal constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val authorityState: () -> AuthorityState,
    private val identityStore: ConnectionIdentityStore,
    private val clock: () -> Long,
    private val staleClaimReconcileSink:
        suspend (ConnectionIdentity, List<StaleClaim>) -> Unit,
    /** §U-P2: watchdog tick interval. Production =
     *  [OPTIMISTIC_CLAIM_WATCHDOG_TICK_MS] (1s — STRICTLY LESS THAN
     *  [OPTIMISTIC_CONFIRM_TIMEOUT_MS] so worst-case detection ≤ timeout+tick
     *  ≈ 6s, honoring the ~7.5s self-heal SLA; rev-gpt gate r1 #2). Test
     *  override (tests pass a short interval or drive the virtual clock). */
    private val tickIntervalMs: Long = OPTIMISTIC_CLAIM_WATCHDOG_TICK_MS,
) {

    /** The current loop job, or null when no loop is running. Read/written
     *  under [stateLock] (serial — start/stop are driven by the connection
     *  lifecycle on the Service Main scope, mirroring the poller). */
    private var loopJob: Job? = null
    private val stateLock = Any()

    /** §U-P2 generation fence: bumped by [start] / [stop]; the launched loop
     *  captures the generation at start and breaks when it advances so an
     *  in-flight tick that woke after a [stop] does NOT sink. Mirrors
     *  [ProcessStatusPoller]'s generation discipline. */
    private var generation: Long = 0L

    /**
     * §U-P2 — start the watchdog loop. Idempotent: calling [start] when a
     * loop is already active is a pure no-op (the existing loop continues
     * uninterrupted). This preserves the watchdog's SLA timer — a repeated
     * [start] from [SessionStreamingService.ensurePoller] mid-window does NOT
     * reset the 5s delay.
     *
     * The loop [delay]s [tickIntervalMs] BEFORE the first tick (no immediate
     * tick at t=0 — a just-stamped claim is by definition NOT yet stale; the
     * first detection opportunity is one tickIntervalMs out, matching the
     * [OPTIMISTIC_CONFIRM_TIMEOUT_MS] window).
     *
     * Cancelled by [stop] (host switch / connection teardown / no-source
     * terminal).
     */
    fun start() {
        // Single-flight: cancel any prior loop before launching the new one.
        val prior = synchronized(stateLock) {
            if (loopJob?.isActive == true) {
                // §U-P2 S1 fix (batch2-review): make start() truly idempotent.
                // Previously the if-block was empty, causing every call to
                // cancel+relaunch and reset the 5s watchdog timer — which could
                // delay detection from ~5s to ~10s when ensurePoller() fires
                // mid-window. Now it's a pure no-op when a loop is already
                // running, preserving the ~7.5s SLA guarantee.
                return
            }
            generation += 1
            loopJob.also { loopJob = null }
        }
        prior?.cancel()
        val expected = synchronized(stateLock) { generation }
        loopJob = scope.launch {
            while (isActive) {
                delay(tickIntervalMs)
                // §U-P2 generation fence: a [stop] or superseding [start]
                // advanced generation — break WITHOUT ticking (an in-flight
                // delay that woke after stop must not sink).
                if (synchronized(stateLock) { generation != expected }) break
                runWatchdogTick()
            }
        }
    }

    /**
     * §U-P2 — stop the watchdog loop (no further ticks). Idempotent: a second
     * call without an intervening [start] is a no-op. The connection
     * lifecycle (ServiceShell stopPoller / enterNoSourceTerminal) calls this
     * so the watchdog does NOT keep firing reconcile GETs after the
     * connection tears down.
     *
     * Bumps [generation] so an in-flight tick that is past the [delay] but
     * has not yet sunk is invalidated (the tick re-checks generation — though
     * the primary fence is the identity re-check inside [runWatchdogTick], the
     * generation bump is belt-and-suspenders for the post-delay window).
     */
    fun stop() {
        val job = synchronized(stateLock) {
            generation += 1
            loopJob.also { loopJob = null }
        }
        job?.cancel()
    }

    /**
     * §U-P2 — ONE watchdog tick. Reads the live identity + authority, selects
     * stale claims via the pure [selectStaleClaimsForReconcile], and sinks
     * them for reconcile. Identity is re-checked BEFORE selecting (cheap
     * fast-path — skip the scan entirely when already stale) AND BEFORE
     * sinking (a host switch during the scan invalidates every outcome).
     *
     * Uses the same runSuspendCatching-style belt-and-suspenders pattern as
     * [ProcessStatusPoller.runRefresh] / [ProcessStatusPoller.runSlimFanOut]:
     * a non-CE throwable is swallowed + logged so it cannot kill the loop.
     */
    private suspend fun runWatchdogTick() {
        try {
            val identity = identityStore.currentIdentity.value ?: return
            // §U-P2: identity re-check before + after (mirrors runSlimFanOut
            // discipline) — a host switch invalidates every reconcile outcome.
            if (!identityStore.isCurrent(identity)) return
            val stale = selectStaleClaimsForReconcile(authorityState(), clock())
            if (stale.isEmpty()) return
            if (!identityStore.isCurrent(identity)) return  // re-check before sink
            staleClaimReconcileSink(identity, stale)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DebugLog.w(TAG, "watchdog tick failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "WatchdogCoordinator"
    }
}
