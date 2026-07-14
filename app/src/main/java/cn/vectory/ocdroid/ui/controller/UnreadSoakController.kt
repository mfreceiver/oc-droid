package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §unread-soak (LOCKED design): foreground sweep that owns the NEW "unread"
 * population logic. Replaces the old instant busy→idle marker inside the
 * `session.status` SSE handler + the REST backstop (Path B). The sweep
 * periodically snapshots the current state, calls the PURE
 * [evaluateUnread] evaluator, and:
 *
 *  (1) writes the evaluator's [UnreadSoakResult.newIdleSince] to the unread
 *      slice (wholesale replace — the evaluator prunes reset/orphaned entries
 *      and retains completed-cycle edge memory until busy);
 *  (2) for each root in [UnreadSoakResult.rootsToMarkUnread], dispatches the
 *      existing [applyMarkSessionUnread] low-level marker (same path the old
 *      instant marker used, so every UI reader of `unreadSessions` sees the
 *      badge identically);
 *  (3) stamps `lastViewedTime[root] = now` when the currentSessionId's root
 *      is all-idle — the "watching-at-completion counts as viewed" rule, so
 *      switching away later does not re-badge a root the user saw settle.
 *
 * Lifecycle: a simple always-on-while-foreground sweep. The loop starts on
 * ON_START and cancels on ON_STOP (matching [ForegroundCatchUpController]'s
 * foreground gating). The background (killed-SSE) case is a SEPARATE lane
 * (T3b poller) — it is NOT implemented here, but [evaluateUnread] is pure and
 * reusable by it. While backgrounded, [idleSince] entries persist in the
 * unread slice (no sweep resets them); T3b will pick them up on its own tick.
 *
 * Concurrency: all state writes go through [SharedStateStore.mutateUnread]
 * (CAS, Main.immediate). The sweep loop runs on the injected [scope]
 * (UiApplicationScope — Main.immediate), so each tick's snapshot + writes are
 * serial w.r.t. the SSE fold and the SessionSwitcher. The injected [clock]
 * makes the soak threshold deterministic in tests.
 */
@Singleton
class UnreadSoakController @Inject constructor(
    private val appLifecycleMonitor: AppLifecycleMonitor,
    private val scope: CoroutineScope,
    private val store: SharedStateStore,
    // Injectable clock so the soak threshold + lastViewedTime stamping are
    // deterministically testable without wall-clock latency in the unit test
    // harness. Defaults to System::currentTimeMillis in production.
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val autoStart: Boolean = true,
) {
    /** Current sweep loop job; null while backgrounded (or not yet started). */
    @Volatile
    private var sweepJob: Job? = null

    init {
        // §unread-soak: subscribe to foreground transitions in init so the
        // subscription lifecycle follows the controller (which follows the
        // orchestrator / app process). onEach+launchIn (NOT a suspend collect)
        // so init is synchronous.
        if (autoStart) {
            appLifecycleMonitor.isInForeground
                .onEach { inForeground -> onForegroundChanged(inForeground) }
                .launchIn(scope)
        }
    }

    /**
     * Starts the sweep on enter-foreground, cancels it on enter-background.
     * Idempotent — re-foregrounding while the loop is alive is a no-op.
     */
    private fun onForegroundChanged(inForeground: Boolean) {
        if (inForeground) startSweep() else stopSweep()
    }

    /**
     * Launches the periodic sweep loop (if not already running). The loop
     * ticks [SWEEP_INTERVAL_MS] while foregrounded; each tick runs [tick].
     */
    private fun startSweep() {
        if (sweepJob?.isActive == true) return
        sweepJob = scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { t ->
                    // Defensive: a single tick must never tear down the loop.
                    DebugLog.w("UnreadSoak", "sweep tick failed: ${t.message}")
                }
                delay(SWEEP_INTERVAL_MS)
            }
        }
    }

    /** Cancels the sweep loop (enter-background). Safe to call repeatedly. */
    private fun stopSweep() {
        sweepJob?.cancel()
        sweepJob = null
    }

    /**
     * Manual tick entry point — exposed internal so [UnreadSoakControllerTest]
     * can drive a single evaluation synchronously (without waiting for the
     * [SWEEP_INTERVAL_MS] delay). Production callers SHOULD NOT invoke this;
     * the loop in [startSweep] is the only production driver.
     */
    internal fun tick() {
        evaluateAndApply(clock())
    }

    /** Shared atomic evaluator path for foreground and background drivers. */
    internal fun evaluateAndApply(now: Long): UnreadSoakResult {
        lateinit var committedResult: UnreadSoakResult
        store.mutateUnreadFromState { snapshot ->
            val sl = snapshot.sessionList
            val chat = snapshot.chat
            val u = snapshot.unread
            val result = evaluateUnread(
                sessions = sl.sessions,
                sessionStatuses = sl.sessionStatuses,
                childSessions = sl.childSessions,
                directorySessions = sl.directorySessions,
                currentSessionId = chat.currentSessionId,
                lastViewedTime = u.lastViewedTime,
                idleSince = u.idleSince,
                now = now,
            )
            committedResult = result
            var next = u.copy(idleSince = result.newIdleSince)
            // (2) mark each root that crossed the soak threshold. The current
            // session guard inside applyMarkSessionUnread is belt-and-suspenders
            // (the evaluator never marks the current root) — kept for parity
            // with the original marker contract.
            if (result.rootsToMarkUnread.isNotEmpty()) {
                for (root in result.rootsToMarkUnread) {
                    next = next.applyMarkSessionUnread(root, chat.currentSessionId).first
                }
            }
            // (3) Consume completed/current cycles. Keeping their idleSince
            // stamp until busy provides rising-edge memory; this viewed stamp
            // prevents repeated marks while all-idle remains continuous.
            if (result.rootsToStampViewed.isNotEmpty()) {
                next = next.copy(
                    lastViewedTime = next.lastViewedTime +
                        result.rootsToStampViewed.associateWith { now }
                )
            }
            next
        }
        return committedResult
    }

    companion object {
        /**
         * §unread-soak: foreground sweep cadence. ~2s keeps the soak
         * resolution tight (the 10s threshold lands within one tick of
         * nominal) without hammering the slice on every frame. The injected
         * [clock] (not this interval) is what makes tests deterministic.
         */
        const val SWEEP_INTERVAL_MS: Long = 2_000L
    }
}
