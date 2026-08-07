package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.model.TokenStreamFrame
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Public facade for the per-session token stream engine.
 *
 * # Architecture (strangler-fig composition split)
 *
 * This class is a **facade** over four `internal` collaborators in the same
 * package. The constructor is byte-identical to the pre-split version (19
 * parameters, defaults intact) — zero caller/test migration. Internal
 * delegation:
 *
 * | Component | Responsibility |
 * |---|---|
 * | [ReconnectPolicy] | Exponential backoff ladder + per-sid attempt counters |
 * | [TokenFrameGuard] | Epoch + generation + ownership bookkeeping (stale-frame / stale-clear authority) |
 * | [TokenStateDispatcher] | Reducer → ChatState bridging + effect translation + revision-hook invocation |
 * | [StreamLifecycleSupervisor] | Max-1 lifecycle ownership: open/close/debounce, run loop, watchdog, reconnect scheduling, §MF-1 sentinel, lifecycle-bundle binding |
 *
 * # One monitor, reentrant
 *
 * All four components share the [bundleCommitLock] (`Any`). The facade's
 * [close] wraps all four cleanups in ONE outer `synchronized(bundleCommitLock)`;
 * JVM `synchronized` reentrancy preserves today's single-acquisition atomicity.
 * Atomics ([AtomicReference], [AtomicLong]) are retained for reads outside the
 * lock (e.g. [StreamLifecycleSupervisor.reconnectRequestedSnapshot]).
 *
 * # Supervisor ↔ dispatcher wiring (lateinit)
 *
 * The supervisor↔dispatcher cycle is broken by construction order:
 *  1. Build [TokenStateDispatcher] FIRST with `requestReconnect` and `onAnyFrame`
 *     callbacks closing over a `lateinit var streamLifecycleSupervisor`.
 *  2. Build [StreamLifecycleSupervisor] SECOND in an `init` block with
 *     `dispatchFrame = this::dispatchEpochFrame`.
 * First use is at the first [open] call, strictly after construction completes
 * → `lateinit` is safe on the main-confined scope.
 *
 * # Companion constants
 *
 * [TOKEN_HEARTBEAT_MS], [TOKEN_WATCHDOG_MS], etc. stay on this facade —
 * tests reference them by name.
 */
class TokenStreamCoordinator(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
    private val streamProvider: (sid: String, directory: String?) -> kotlinx.coroutines.flow.Flow<TokenStreamFrame>,
    private val triggerSinceFetch: (sessionId: String, authoritative: Boolean) -> Unit,
    /**
     * Heartbeat interval the server emits (informational; the watchdog uses
     * [watchdogMs]). Default 15s per the oc-slimapi sidecar contract.
     */
    private val heartbeatMs: Long = TOKEN_HEARTBEAT_MS,
    /**
     * Watchdog timeout = 3× heartbeat (tolerates missing 2 frames before
     * declaring the link dead). Mirrors SSEClient's 30s/3×-10s heartbeat
     * policy but applied to the per-session token stream's 15s heartbeat.
     */
    private val watchdogMs: Long = TOKEN_WATCHDOG_MS,
    /** Watchdog poll cadence. */
    private val watchdogPollMs: Long = TOKEN_WATCHDOG_POLL_MS,
    /**
     * Short debounce on rapid open(sid) to coalesce UI taps / state-driven
     * bursts (avoid storming the sidecar's cap-8 admission). 0 = open
     * immediately (test default for synchronous cases).
     */
    private val openDebounceMs: Long = OPEN_DEBOUNCE_MS,
    /** Exponential-backoff seed for reconnect. */
    private val initialBackoffMs: Long = INITIAL_BACKOFF_MS,
    /** Backoff cap. */
    private val maxBackoffMs: Long = MAX_BACKOFF_MS,
    /** Backoff growth factor. */
    private val backoffMultiplier: Double = BACKOFF_MULTIPLIER,
    /** Injectable clock for deterministic watchdog tests. */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * §sse-disabled-debug-toggle: when true, [open] short-circuits WITHOUT
     * touching [streamProvider] — NO per-session `/slimapi/sessions/{sid}/stream`
     * connection (REST-only degraded mode). Default `{ false }` so production
     * wiring (ControllerModule) and tests opt in explicitly. The gate is at the
     * coordinator ENTRY (before debounce/job/state mutation) so no stream
     * lifecycle is ever created while the flag is on.
     */
     private val sseDisabled: () -> Boolean = { false },
      /** Resolve-time stream connection, including its immutable bundle. */
      private val streamConnectionProvider: ((String, String?) -> TokenStreamConnection)? = null,
      /** Shared with [OpenCodeRepository.configure]'s @Synchronized monitor. */
      private val bundleCommitLock: Any = Any(),
      /** Published bundle identity used by all lifecycle/result guards. */
      private val currentBundleProvider: () -> ClientBundle? = { null },
      /**
      * B-P0-1 (R1+R2 dedup wiring): per-part revision dedup hook. Called
      * BEFORE the streaming reducer for EVERY `message.part.snapshot` /
      * `message.part.delta` frame. Returns `true` iff the frame is FRESH
      * (the revision differs from the previously-applied one — the
      * caller proceeds with [TokenStreamReducer.reduce]); `false`
      * signals a re-delivery within the 250ms debounce window, and the
      * caller DROPS the frame (returns from [dispatchEpochFrame]
      * without bridging / state mutation).
      *
       * Default `{ _, _, _, _, _ -> true }` (accept all) preserves the
       * pre-B-P0-1 behavior — no dedup is performed when the hook is
       * unset. Production wiring (B-P0-1) injects
       * a token-part-revision dedup checker captured against the
       * lifecycle's slim commit token; the coordinator stays free of
       * the data/repository layer dependency.
       *
       * `partEventRevision == null` (older sidecar / status-only frame)
       * MUST be accepted (`true`) — without a revision counter the
       * dedup-layer cannot operate, and the caller falls back to its
       * pre-B-P0-1 behavior.
      *
      * §Stage-B C3: the [context] carries the route + bundle snapshot
      * captured at THIS frame's dispatch entry; production wiring MAY
      * forward it verbatim into downstream route-guarded actions.
      */
     private val dedupPartRevision: (
         sessionId: String, messageId: String, partId: String, partEventRevision: Long?,
         context: TokenFrameCommitContext,
     ) -> Boolean = { _, _, _, _, _ -> true },
     /**
      * B-P0-2 (MAJOR 4 + replacement edge): hook invoked when a
      * `message.part.removed` token frame clears the epoch + bundle
      * guards. The callback is responsible for:
      *
       *  1. Applying the part-removal to the per-message watermark map
       *     (advances `messageEventSeq` monotonically, drops the
       *     removed partID, flags `needsFullRecheck = true`) via the
       *     slim commit token-guarded in-memory state update.
      *  2. Scheduling a 100ms-debounced R2 /full reconcile for the
      *     message so the chat slice's part cache reflects the
      *     upstream removal.
      *
      * Default `{ _, _, _, _, _ -> }` (no-op) preserves the pre-B-P0-2
      * behaviour — the frame is still consumed by the reducer (which
      * returns no effects for it) but no watermark mutation / R2
      * reconcile is scheduled.
      *
      * The hook is called AFTER the dedup check, INSIDE
      * [dispatchEpochFrame]'s `bundleCommitLock` critical section, so
      * a bundle rotation between the epoch check and the hook is
      * impossible. §Stage-B C3: the [context] carries the route +
      * bundle snapshot captured at THIS frame's dispatch entry;
      * production wiring forwards it verbatim into the R2 reconcile's
      * route-guarded dispatch.
      */
     private val onMessagePartRemoved: (
         sessionId: String, messageId: String, partId: String, messageEventSeq: Long,
         context: TokenFrameCommitContext,
     ) -> Unit = { _, _, _, _, _ -> },
     /**
      * B-P0-2 (MAJOR 4): hook invoked when a `message.removed` token
      * frame clears the epoch + bundle guards. The callback is
      * responsible for:
      *
       *  1. Removing the per-message watermark entry via the slim
       *     commit token-guarded in-memory state update.
      *  2. Evicting the message from the chat slice (`messages` list
      *     + `partsByMessage` map) and dropping its tuple from any
      *     `maxMessageTuple` cache (MAJOR 4 cleanup).
      *
      * Default `{ _, _, _ -> }` (no-op) preserves the pre-B-P0-2
      * behaviour. Production wiring (B-P0-2) injects the cleanup
      * path; tests override to verify the hook fires.
      *
      * §Stage-B C3: the [context] carries the route + bundle snapshot
      * captured at THIS frame's dispatch entry; production wiring
      * forwards it verbatim into [AppAction.MessageRemovedConfirmed].
      */
     private val onMessageRemoved: (
         sessionId: String, messageId: String,
         context: TokenFrameCommitContext,
     ) -> Unit = { _, _, _ -> },
     /**
      * B-4 HIGH-2: called when a part snapshot reaches done:true (terminal).
      * The production implementation clears the per-part revision entry from
      * the dedup map so it does not grow unbounded across completed parts.
      */
     private val onPartDone: (
         sessionId: String, messageId: String, partId: String,
     ) -> Unit = { _, _, _ -> },
     /**
      * B-4 HIGH-2: called when ALL revision entries for a session should be
      * reclaimed (stream close, resync, session switch). Prevents unbounded
      * map growth across the singleton coordinator's lifetime and provides
      * connection-epoch isolation (stale revisions from a dead connection
      * cannot block fresh frames on a new connection).
      */
     private val clearSessionRevisions: (
         sessionId: String,
     ) -> Unit = { _ -> },
 ) {
    // ── Split components ───────────────────────────────────────────────────

    /** Exponential backoff ladder + per-sid attempt counters. */
    private val reconnectPolicy = ReconnectPolicy(
        initialBackoffMs, maxBackoffMs, backoffMultiplier, bundleCommitLock,
    )

    /** Epoch + generation + ownership bookkeeping (stale-frame / stale-clear authority). */
    private val tokenFrameGuard = TokenFrameGuard(bundleCommitLock)

    /**
     * Stream lifecycle supervisor.
     *
     * # lateinit wiring
     *
     * The supervisor ↔ dispatcher cycle (dispatcher sets the reconnect sentinel;
     * supervisor calls dispatcher per frame) is broken by construction order:
     * the dispatcher is built FIRST with [TokenStateDispatcher.requestReconnect]
     * and [TokenStateDispatcher.onAnyFrame] callbacks closing over a
     * [lateinit] `streamLifecycleSupervisor`; the supervisor is built SECOND
     * with [StreamLifecycleSupervisor.dispatchFrame = tokenStateDispatcher::dispatchEpochFrame].
     * First use is at the first `open()` call, strictly after construction
     * completes → lateinit is safe on the main-confined scope.
     */
    private lateinit var streamLifecycleSupervisor: StreamLifecycleSupervisor

    /** Reducer → ChatState bridging + effect translation + revision-hook invocation. */
    private val tokenStateDispatcher = TokenStateDispatcher(
        slices = slices,
        guard = tokenFrameGuard,
        bundleCommitLock = bundleCommitLock,
        currentBundleProvider = currentBundleProvider,
        triggerSinceFetch = triggerSinceFetch,
        requestReconnect = { sid -> streamLifecycleSupervisor.markReconnectRequested(sid) },
        onAnyFrame = { sid -> streamLifecycleSupervisor.onFrame(sid) },
        dedupPartRevision = dedupPartRevision,
        onMessagePartRemoved = onMessagePartRemoved,
        onMessageRemoved = onMessageRemoved,
        onPartDone = onPartDone,
        clearSessionRevisions = clearSessionRevisions,
    )
    // Wire the supervisor after the dispatcher (see lateinit note above).
    // This runs after all property initializers, so tokenStateDispatcher is
    // fully constructed and its ::dispatchEpochFrame reference is valid.
    init {
        streamLifecycleSupervisor = StreamLifecycleSupervisor(
            scope = scope,
            slices = slices,
            guard = tokenFrameGuard,
            policy = reconnectPolicy,
            streamProvider = streamProvider,
            streamConnectionProvider = streamConnectionProvider,
            sseDisabled = sseDisabled,
            clearSessionRevisions = clearSessionRevisions,
            triggerSinceFetch = triggerSinceFetch,
            clock = clock,
            bundleCommitLock = bundleCommitLock,
            currentBundleProvider = currentBundleProvider,
            watchdogMs = watchdogMs,
            watchdogPollMs = watchdogPollMs,
            openDebounceMs = openDebounceMs,
            dispatchFrame = this::dispatchEpochFrame,
        )
    }

    /** Dispatches an ownership clear with a stamp captured under the bundle lock. */
    internal fun dispatchTokenStreamClear(
        partIds: Set<String>,
        expectedRouteInstance: Long,
        sessionId: String?,
    ): Boolean = tokenStateDispatcher.dispatchTokenStreamClear(partIds, expectedRouteInstance, sessionId)

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Foreground opt-in connect for [sid]. Delegates to [StreamLifecycleSupervisor.open]
     * which handles the idempotent guard, debounce, and max-1 lifecycle supersede.
     *
     * [source] is a diagnostic tag logged at entry so repro logcat can
     * distinguish the driving caller.
     */
    fun open(sid: String, directory: String? = null, source: String = "unknown") =
        streamLifecycleSupervisor.open(sid, directory, source)

    /**
     * Explicit close for [sid]. Cancels the in-flight stream if it matches
     * [sid]; clears per-sid coordinator state (ownership + reducer working
     * state). Does NOT dispatch [AppAction.ClearTokenStreamState] — the
     * caller (D2 / ChatViewModel) owns the UX decision of when to wipe the
     * streaming overlay (e.g. on session switch the existing reducer paths
     * already clear it via [AppAction.SessionSelected]).
     */
    fun close(sid: String) {
        synchronized(bundleCommitLock) {
        streamLifecycleSupervisor.close(sid)
        // Clear coordinator-internal state for this sid regardless of whether
        // it was the current stream (defensive: covers a stale sid whose job
        // was already cancelled by a newer open()).
        tokenFrameGuard.removeSid(sid)
        tokenStateDispatcher.removeSid(sid)
        // B-4 HIGH-2: reclaim ALL revision entries for this session on close
        // (stream teardown, session switch, background — prevents unbounded
        // growth + provides epoch isolation).
        clearSessionRevisions(sid)
        reconnectPolicy.clearSid(sid)
        }
    }

    /** Test/diagnostic read: the current stream Job (or null when idle). */
    internal fun currentStreamJobSnapshot(): Job? = streamLifecycleSupervisor.currentStreamJobSnapshot()

    /** Test/diagnostic read: the current epoch for [sid] (or 0 if none). */
    internal fun epochOf(sid: String): Long = tokenFrameGuard.epochOf(sid)

    /** Test/diagnostic read: the current generation for [sid] (or 0 if none). */
    internal fun genOf(sid: String): Long = tokenFrameGuard.genOf(sid)

    /**
     * Test-only: bumps the epoch for [sid] WITHOUT going through open() (so
     * tests can simulate a re-open's epoch bump in isolation while driving
     * frames via [dispatchEpochFrame]).
     */
    internal fun bumpEpochForTest(sid: String): Long =
        tokenFrameGuard.bumpEpochForTest(sid)

    /**
     * §MF-1 (gate r2) test seam: directly sets the reconnect sentinel to
     * simulate a stale value left by a cancelled sid's unconsumed
     * resync(Reconnect). Used by the stale-sentinel regression test to verify
     * that a new sid's open() + Reconnect works regardless of the stale value.
     */
    internal fun setReconnectRequestedForTest(sid: String?) =
        streamLifecycleSupervisor.setReconnectRequestedForTest(sid)

    /** §MF-1 (gate r2) test seam: reads the current sentinel value. */
    internal fun reconnectRequestedSnapshot(): String? =
        streamLifecycleSupervisor.reconnectRequestedSnapshot()

    /** Test/diagnostic read: the partIds currently owned by the active stream for [sid]. */
    internal fun ownedPartsForSid(sid: String): Set<String> =
        tokenFrameGuard.ownedPartsForSid(sid)

    /**
     * Bumps the generation for [sid] and returns the new value. Called at
     * every open/reconnect so ownership claims + clears emitted by the prior
     * generation become stale.
     */
    internal fun beginSession(sid: String): Long =
        tokenFrameGuard.beginSession(sid)

    /**
     * Filters [partIds] through the generation guard. Returns the subset that
     * the ([sid], [gen]) stream is allowed to clear.
     */
    internal fun filterClearByGeneration(sid: String, gen: Long, partIds: Set<String>): Set<String> =
        tokenFrameGuard.filterClearByGeneration(sid, gen, partIds)

    // ── Epoch-tagged frame dispatch (unit-testable surface) ──────────────────

    /**
     * Epoch-guarded entry: validates [sid]/[epoch] against the current
     * epoch via [TokenFrameGuard.isEpochCurrent] BEFORE any reduce / state
     * mutation. Drops stale frames (the connection that delivered this frame has
     * been torn down and re-opened under a newer epoch — late OkHttp callbacks
     * that leaked past the transport's own `closed` guard). Then resets the
     * watchdog, runs the pure reducer, bridges any part-text change into
     * ChatState, and processes emitted effects.
     *
     * Exposed internal so unit tests can drive frames with crafted epochs
     * without going through the asynchronous [streamProvider].
     */
    /**
     * Epoch-guarded entry: validates [sid]/[epoch] against the current
     * epoch via [TokenFrameGuard.isEpochCurrent] BEFORE any reduce / state
     * mutation. Drops stale frames (the connection that delivered this frame has
     * been torn down and re-opened under a newer epoch — late OkHttp callbacks
     * that leaked past the transport's own `closed` guard). Then resets the
     * watchdog, runs the pure reducer, bridges any part-text change into
     * ChatState, and processes emitted effects.
     *
     * §B4 round-2 (rev-gpt C2): [capturedRouteInstance] is the route token
     * captured at THIS lifecycle's open()/runStream() entry and threaded
     * verbatim through the call chain. It is NOT read from a shared field —
     * a new lifecycle's open()/reconnect could overwrite such a field mid-
     * flight, letting a prior lifecycle's late frame adopt the new token
     * and bypass the §7.2 freshness CAS.
     *
     * Exposed internal so unit tests can drive frames with crafted epochs
     * without going through the asynchronous [streamProvider].
     */
    internal fun dispatchEpochFrame(
        sid: String,
        epoch: Long,
        gen: Long,
        frame: TokenStreamFrame,
        capturedRouteInstance: Long,
        boundBundle: ClientBundle,
    ) {
        val deferredEffects = mutableListOf<() -> Unit>()
        synchronized(bundleCommitLock) {
        if (boundBundle !== currentBundleProvider()) return
        if (!tokenFrameGuard.isEpochCurrent(sid, epoch)) {
            DebugLog.d(
                TAG,
                "drop stale-epoch frame sid=$sid epoch=$epoch current=${tokenFrameGuard.epochOf(sid)} type=${frame::class.simpleName}",
            )
            return
        }
        tokenStateDispatcher.processFrameBody(
            sid, epoch, gen, frame, capturedRouteInstance, boundBundle, deferredEffects,
        )
        }
        deferredEffects.forEach { it() }
    }

    // ── Stream lifecycle is owned by [StreamLifecycleSupervisor] ──────────────

    companion object {
        private const val TAG = "TokenStreamCoordinator"

        // §bgpt MF-2 / dev-plan §3.10: the oc-slimapi sidecar emits a token-
        // stream heartbeat every 15s. Watchdog = 3× = 45s (tolerates missing
        // 2 frames before declaring the link dead).
        internal const val TOKEN_HEARTBEAT_MS = 15_000L
        internal const val TOKEN_WATCHDOG_MS = TOKEN_HEARTBEAT_MS * 3L // 45_000L
        internal const val TOKEN_WATCHDOG_POLL_MS = 5_000L

        // Short debounce on rapid open(sid). 100ms absorbs UI double-taps +
        // state-driven bursts without perceptible latency.
        internal const val OPEN_DEBOUNCE_MS = 100L

        // Exponential backoff ladder for reconnect (matches SSEClient).
        internal const val INITIAL_BACKOFF_MS = 1_000L
        internal const val MAX_BACKOFF_MS = 30_000L
        internal const val BACKOFF_MULTIPLIER = 2.0
    }
}
