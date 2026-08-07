package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.model.TokenStreamFrame
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.BundleStamp
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Max-1 lifecycle ownership for the per-session token stream.
 *
 * Owns: open/close/debounce, run loop (collector + watchdog), reconnect
 * scheduling, the §MF-1 sentinel, lifecycle-bundle binding.
 *
 * All mutating methods are [synchronized] on [bundleCommitLock] or use atomic
 * primitives for lock-outside reads. The supervisor receives a [dispatchFrame]
 * callback (pointing to [TokenStateDispatcher.dispatchEpochFrame]) so the
 * run-loop's collect lambda dispatches frames through the reducer pipeline.
 *
 * @param scope The coroutine scope for all lifecycle jobs.
 * @param slices UI slice flows (for route token resolution).
 * @param guard The epoch/generation/ownership guard.
 * @param policy The exponential backoff policy.
 * @param streamProvider Creates a token-stream flow for (sid, directory).
 * @param streamConnectionProvider Creates a resolved connection (incl. bundle).
 * @param sseDisabled Debug gate to skip stream connection.
 * @param clearSessionRevisions B-4 HIGH-2 hook to reclaim all revisions for a sid.
 * @param triggerSinceFetch Callback for `/since` fetch (D2 wiring).
 * @param clock Injectable clock for deterministic watchdog tests.
 * @param bundleCommitLock The shared monitor.
 * @param currentBundleProvider Returns the currently-published [ClientBundle].
 * @param watchdogMs Watchdog timeout duration.
 * @param watchdogPollMs Watchdog poll cadence.
 * @param openDebounceMs Short debounce on rapid open(sid).
 * @param dispatchFrame Callback to [TokenStateDispatcher.dispatchEpochFrame] for
 *   frame processing inside the run-loop's collect lambda.
 */
internal class StreamLifecycleSupervisor(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
    private val guard: TokenFrameGuard,
    private val policy: ReconnectPolicy,
    private val streamProvider: (sid: String, directory: String?) -> Flow<TokenStreamFrame>,
    private val streamConnectionProvider: ((String, String?) -> TokenStreamConnection)?,
    private val sseDisabled: () -> Boolean,
    private val clearSessionRevisions: (sessionId: String) -> Unit,
    private val triggerSinceFetch: (sessionId: String, authoritative: Boolean) -> Unit,
    private val clock: () -> Long,
    private val bundleCommitLock: Any,
    private val currentBundleProvider: () -> ClientBundle?,
    private val watchdogMs: Long,
    private val watchdogPollMs: Long,
    private val openDebounceMs: Long,
    private val dispatchFrame: (
        sid: String, epoch: Long, gen: Long, frame: TokenStreamFrame,
        capturedRouteInstance: Long, boundBundle: ClientBundle,
    ) -> Unit,
) {
    private val TAG = "TokenStreamCoordinator"

    // ── State ────────────────────────────────────────────────────────────────

    /** The sid of the currently-open (or pending-debounce) stream. Null = idle. */
    private val currentSid = AtomicReference<String?>(null)
    /** The directory captured for the current stream (for reconnect). */
    private val currentDirectory = AtomicReference<String?>(null)
    /**
     * The single active lifecycle reference. Its [StreamLifecycle.bundle] is
     * filled at resolve-time, after debounce, and is never sourced from an
     * independent generation/endpoint variable.
     */
    private data class StreamLifecycle(
        val job: Job,
        val bundle: ClientBundle,
    ) {
        val boundBundleGeneration: Long
            get() = bundle.generation
        val boundEndpointFp: String
            get() = bundle.endpointFp
    }

    private val currentLifecycle = AtomicReference<StreamLifecycle?>(null)
    /**
     * §B4 rev-gpt round3 MAJOR: the route token captured at the most recent
     * entry of the currently-tracked lifecycle (open / 503-retry / reconnect).
     * Used ONLY by the [open] idempotent guard to detect same-session NEW route
     * incarnations (navigateToChat same-session re-entry advances
     * chatRouteInstance; the old guard's (sid, directory)-only check would skip
     * → the prior lifecycle's stale captured token would be reused →
     * dispatchEpochFrame would carry an outdated token → acceptsRouteUpdate
     * would reject the new incarnation's frames).
     *
     * NOT used by the dispatch chain — the per-lifecycle captured token is
     * threaded VERBATIM as a function parameter through runStream →
     * dispatchEpochFrame (rev-gpt C2 fix, unchanged). This field is the guard's
     * sole reader; reconnect / 503-retry re-set it so the guard recognizes the
     * in-flight lifecycle's latest captured token.
     */
    private val lifecycleRouteInstance = AtomicLong(0L)

    /**
     * Watchdog clock — last frame (any kind, incl. heartbeat) arrival time.
     * Seeded at open() so the very first check window is not already expired
     * AND so the watchdog can fire BEFORE the first frame (the bgpt MF-2 fix:
     * SSEClient's `eventCount==0` skip is deliberately NOT replicated here).
     */
    private val lastFrameAt = AtomicLong(0L)

    /**
     * §MF-1 (gate r1/r2): mid-collect Reconnect sentinel. Set by
     * [handleEffect] when the reducer emits a [TokenStreamCoordinatorEffect.Reconnect]
     * (checked INSIDE `flow.collect { }` right after [dispatchEpochFrame]).
     * The collect lambda throws [TokenStreamReconnectRequested] to unwind the
     * collector cleanly, and the run-loop's catch path is the SINGLE re-entry
     * point that calls [scheduleReconnect] — guaranteeing no overlapping
     * collectors (the old flow's EventSource is torn down via its awaitClose
     * BEFORE the reconnect's backoff opens a new one).
     *
     * # §gate r2: UNCONDITIONAL set/clear (NOT CAS-on-sid)
     *
     * The sentinel uses **unconditional `set`** for both taking and releasing
     * ownership — NOT `compareAndSet` against a specific sid value. The CAS
     * approach had a recovery hole ...
     *
     * Unconditional `set(null)` in open/close/runStream-start/catch, and
     * unconditional `set(sid)` in handleEffect, closes the hole: a new sid's
     * lifecycle always starts with a clean sentinel regardless of what a
     * cancelled prior sid left behind. The sentinel is effectively a
     * "single-global pending-reconnect for the current lifecycle" — last
     * writer wins, only one post-dispatch check runs per frame.
     *
     * Why a sentinel (not a direct [scheduleReconnect] call from handleEffect):
     * handleEffect runs synchronously INSIDE `flow.collect { dispatchEpochFrame(...) }`.
     * Calling scheduleReconnect directly would supersede the currently-running
     * job via [launchStreamLifecycle], causing a self-cancellation race mid-frame
     * and leaving the other effects in the same batch (ClearPartState /
     * TriggerSinceFetch) in an ambiguous state. The sentinel defers the
     * reconnect decision to the end of the frame's dispatch, after ALL effects
     * have been processed.
     */
    private val reconnectRequested = AtomicReference<String?>(null)

    // ── Sentinel test seams ──────────────────────────────────────────────────

    /** §MF-1 (gate r2) test seam: directly sets the reconnect sentinel. */
    internal fun setReconnectRequestedForTest(sid: String?) {
        reconnectRequested.set(sid)
    }

    /**
     * Test seam: directly sets [currentSid] without going through [open].
     * Used by mutation-testing the `currentSid.get() != sid` guard inside
     * [scheduleReconnect] — the test must change the sid WITHOUT cancelling
     * the pending reconnect job (which [open] would do via
     * [launchStreamLifecycle]).
     */
    internal fun setCurrentSidForTest(sid: String?) {
        currentSid.set(sid)
    }

    /** §MF-1 (gate r2) test seam: reads the current sentinel value. */
    internal fun reconnectRequestedSnapshot(): String? = reconnectRequested.get()

    // ── Frame callback ───────────────────────────────────────────────────────

    /**
     * Called by [TokenStateDispatcher] on every successfully-dispatched frame.
     * Resets the watchdog clock and the reconnect-attempt counter.
     */
    fun onFrame(sid: String) {
        lastFrameAt.set(clock())
        policy.resetAttempts(sid)
    }

    /**
     * Called by [TokenStateDispatcher] to set the §MF-1 reconnect sentinel.
     * The run-loop's post-dispatch check reads this and throws
     * [TokenStreamReconnectRequested] to unwind the collector.
     */
    fun markReconnectRequested(sid: String) {
        reconnectRequested.set(sid)
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Foreground opt-in connect for [sid]. Supersedes any currently-open stream
     * (max-1: opening B closes A). Applies a short debounce.
     *
     * [source] is a diagnostic tag logged at entry so repro logcat can
     * distinguish the driving caller.
     */
    fun open(sid: String, directory: String? = null, source: String = "unknown") {
        if (sid.isBlank()) return
        DebugLog.i(TAG, "open($sid) source=$source")
        if (sseDisabled()) {
            DebugLog.i(TAG, "open($sid): sse_disabled=true → REST-only (no token stream)")
            return
        }
        synchronized(bundleCommitLock) {
        val currentBundle = currentBundleProvider()
        val existingLifecycle = currentLifecycle.get()
        val existingJob = existingLifecycle?.job
        val currentRouteInstance = slices.routeInstanceFor(sid)
        if (currentSid.get() == sid &&
            currentDirectory.get() == directory &&
            existingJob?.isActive == true &&
            lifecycleRouteInstance.get() == currentRouteInstance &&
            existingLifecycle?.bundle === currentBundle
        ) {
            val revalidatedBundle = currentBundleProvider()
            if (existingLifecycle.bundle === revalidatedBundle) {
                DebugLog.i(TAG, "open($sid) idempotent skip — same (sid,dir,routeToken,bundle) + active lifecycle (source=$source)")
                return
            }
            DebugLog.i(TAG, "open($sid) bundle changed during guard revalidation — superseding (source=$source)")
        }
        val prevSid = currentSid.get()
        if (prevSid != null && prevSid != sid) {
            clearSessionRevisions(prevSid)
        }
        currentSid.set(sid)
        currentDirectory.set(directory)
        lifecycleRouteInstance.set(currentRouteInstance)
        reconnectRequested.set(null)
        val capturedSid = sid
        val capturedDir = directory
        val capturedRouteInstance = currentRouteInstance
        launchStreamLifecycle(capturedSid, "open") {
            if (openDebounceMs > 0L) {
                delay(openDebounceMs)
            }
            if (currentSid.get() != capturedSid) {
                DebugLog.d(TAG, "open($capturedSid) superseded during debounce — skipping")
                return@launchStreamLifecycle
            }
            val (epoch, gen) = guard.beginStreamIncarnation(capturedSid)
            try {
                runStream(capturedSid, capturedDir, epoch, gen, isReconnect = false, capturedRouteInstance)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                DebugLog.w(TAG, "open($capturedSid) escaped: ${e.message}")
                onStreamFailure(capturedSid, capturedDir, e)
            }
        }
        }
    }

    /**
     * Job/sid portion of close for [sid]. Cancels the in-flight stream if it
     * matches [sid]; clears lifecycle state (currentSid, currentDirectory,
     * reconnectRequested). Does NOT clear guard/dispatcher/policy state —
     * the caller (the facade) is responsible for that.
     */
    fun close(sid: String) {
        synchronized(bundleCommitLock) {
        if (currentSid.get() == sid) {
            cancelCurrentStream("close($sid)")
            currentSid.set(null)
            currentDirectory.set(null)
            reconnectRequested.set(null)
        }
        }
    }

    /** Test/diagnostic read: the current stream Job (or null when idle). */
    internal fun currentStreamJobSnapshot(): Job? = currentLifecycle.get()?.job

    // ── Stream run loop (collector + watchdog) ───────────────────────────────

    private suspend fun runStream(
        sid: String,
        directory: String?,
        epoch: Long,
        gen: Long,
        isReconnect: Boolean,
        capturedRouteInstance: Long,
    ) {
        val connection = try {
            streamConnectionProvider?.invoke(sid, directory) ?: run {
                val bundle = currentBundleProvider() ?: return
                TokenStreamConnection(
                    flow = streamProvider(sid, directory),
                    bundle = bundle,
                )
            }
        } catch (e: Throwable) {
            onStreamFailure(sid, directory, e)
            return
        }
        val boundBundle = connection.bundle
        if (!bindCurrentLifecycleBundle(boundBundle)) return
        if (!isCurrentLifecycleBundle(boundBundle)) return
        reconnectRequested.set(null)
        lastFrameAt.set(clock())
        val flow = connection.flow

        try {
            coroutineScope {
                val watchdogJob = launch {
                    while (isActive) {
                        delay(watchdogPollMs)
                        val elapsed = clock() - lastFrameAt.get()
                        if (elapsed >= watchdogMs) {
                            DebugLog.w(TAG, "watchdog timeout sid=$sid (elapsed=${elapsed}ms ≥ ${watchdogMs}ms)")
                            throw TokenStreamWatchdogTimeout(sid)
                        }
                    }
                }
                try {
                    flow.collect { frame ->
                        dispatchFrame(sid, epoch, gen, frame, capturedRouteInstance, boundBundle)
                        if (reconnectRequested.get() == sid) {
                            throw TokenStreamReconnectRequested(sid)
                        }
                    }
                    DebugLog.i(TAG, "stream completed (server closed) sid=$sid")
                    if (isBundleCurrentForCommit(boundBundle)) {
                        policy.resetAttempts(sid)
                    }
                } finally {
                    watchdogJob.cancel()
                }
            }
        } catch (e: TokenStreamWatchdogTimeout) {
            onWatchdogTimeout(sid, epoch, gen, directory, capturedRouteInstance, boundBundle)
        } catch (e: TokenStreamReconnectRequested) {
            reconnectRequested.set(null)
            if (isCurrentLifecycleBundle(boundBundle)) {
                scheduleReconnect(sid, directory)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            if (isCurrentLifecycleBundle(boundBundle)) {
                onStreamFailure(sid, directory, e)
            }
        }
    }

    private fun onWatchdogTimeout(
        sid: String,
        epoch: Long,
        gen: Long,
        directory: String?,
        capturedRouteInstance: Long,
        boundBundle: ClientBundle,
    ) {
        var shouldFetch = false
        var shouldReconnect = false
        synchronized(bundleCommitLock) {
            if (!isBundleCurrentForCommit(boundBundle)) return
            val stamp = BundleStamp(boundBundle.generation, boundBundle.endpointFp)
            val parts = guard.ownedPartsForSid(sid)
            val allowed = guard.filterClearByGeneration(sid, gen, parts)
            if (allowed.isNotEmpty()) {
                slices.store.dispatch(
                    AppAction.ClearTokenStreamState(
                        allowed,
                        expectedRouteInstance = capturedRouteInstance,
                        sessionId = sid,
                        bundleStamp = stamp,
                    ),
                )
            }
            if (!isBundleCurrentForCommit(boundBundle)) return
            shouldFetch = true
            shouldReconnect = true
        }
        if (shouldFetch) {
            triggerSinceFetch(sid, true)
        }
        if (shouldReconnect) {
            scheduleReconnect(sid, directory)
        }
    }

    private fun onStreamFailure(sid: String, directory: String?, t: Throwable) {
        DebugLog.w(TAG, "stream failure sid=$sid — scheduling reconnect: ${t.message}")
        scheduleReconnect(sid, directory)
    }

    // ── Reconnect backoff ────────────────────────────────────────────────────

    private fun scheduleReconnect(sid: String, directory: String?) {
        val backoff = policy.nextDelayMs(sid)
        DebugLog.i(TAG, "scheduleReconnect sid=$sid backoff=${backoff}ms")
        val reconnectRouteInstance = slices.routeInstanceFor(sid)
        lifecycleRouteInstance.set(reconnectRouteInstance)
        launchStreamLifecycle(sid, "reconnect") {
            delay(backoff)
            if (currentSid.get() != sid) return@launchStreamLifecycle
            val (epoch, gen) = guard.beginStreamIncarnation(sid)
            try {
                runStream(sid, directory, epoch, gen, isReconnect = true, reconnectRouteInstance)
            } catch (ce: CancellationException) {
                throw ce
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun bindCurrentLifecycleBundle(bundle: ClientBundle): Boolean {
        val currentJob = currentCoroutineContext()[Job] ?: return false
        while (true) {
            val current = currentLifecycle.get() ?: return false
            if (current.job !== currentJob) return false
            if (current.bundle === bundle) return isCurrentLifecycleBundle(bundle)
            val bound = current.copy(bundle = bundle)
            if (currentLifecycle.compareAndSet(current, bound)) {
                return isCurrentLifecycleBundle(bundle)
            }
        }
    }

    private suspend fun isCurrentLifecycleBundle(bundle: ClientBundle): Boolean {
        val currentJob = currentCoroutineContext()[Job] ?: return false
        val lifecycle = currentLifecycle.get() ?: return false
        return lifecycle.job === currentJob &&
            lifecycle.bundle === bundle &&
            bundle === currentBundleProvider()
    }

    private fun isBundleCurrentForCommit(bundle: ClientBundle): Boolean =
        bundle === currentBundleProvider()

    private fun launchStreamLifecycle(sid: String, reason: String, block: suspend () -> Unit): Job? {
        synchronized(bundleCommitLock) {
        val publishedBundle = currentBundleProvider() ?: run {
            DebugLog.d(TAG, "skip lifecycle sid=$sid reason=$reason: no published bundle")
            return null
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        val prior = currentLifecycle.getAndSet(StreamLifecycle(job, publishedBundle))
        DebugLog.i(TAG, "supersede prior sid=$sid priorActive=${prior?.job?.isActive} reason=$reason")
        prior?.job?.cancel(CancellationException("superseded by $reason sid=$sid"))
        job.start()
        return job
        }
    }

    private fun cancelCurrentStream(reason: String) {
        currentLifecycle.get()?.job?.cancel(CancellationException(reason))
    }
}
