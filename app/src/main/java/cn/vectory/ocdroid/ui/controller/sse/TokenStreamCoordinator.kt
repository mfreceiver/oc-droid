package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.model.EpochFrame
import cn.vectory.ocdroid.data.model.TokenStreamFrame
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.data.repository.TokenPartStreamState
import cn.vectory.ocdroid.data.repository.TokenStreamCoordinatorEffect
import cn.vectory.ocdroid.data.repository.TokenStreamReducer
import cn.vectory.ocdroid.data.repository.TokenStreamReducerState
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.BundleStamp
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.StreamOwnedState
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * §Stage-D1 §3.7 / §3.8 / §5.8 / §3.10 — the lifecycle coordinator ENGINE for
 * the per-session token stream (`GET /slimapi/sessions/{sid}/stream`).
 *
 * # Scope (D1 — engine only)
 *
 * This class is the unit-testable engine. It owns:
 *  - **max-1 foreground stream** (opening B closes A) + a short debounce on
 *    rapid `open(sid)` to avoid storming the sidecar's cap-8 admission.
 *  - **epoch capture at open** (M6/gpt): per-sid monotonic epoch; inbound
 *    frames are wrapped as [EpochFrame] and frames whose epoch no longer
 *    matches `currentEpoch[sid]` are dropped (stale — incl. post-cancel
 *    queued OkHttp callbacks that leak past the transport's own closed guard).
 *  - **watchdog pre-first-frame** (bgpt MF-2): an INDEPENDENT watchdog active
 *    FROM open() / onOpen that resets on ANY frame (incl. heartbeat) and
 *    fires on `TOKEN_WATCHDOG_MS` timeout. It does NOT reuse SSEClient's
 *    `eventCount==0` early-skip — that pattern would hang forever if the
 *    server never emits a first frame (half-open TCP, silent sidecar boot).
 *  - **reconnect backoff** (exponential, bounded) on failure / Reconnect effect.
 *  - **503 `sse_token_subscriber_limit`** (bounded Retry-After backoff; after
 *    N consecutive failures → capability-degrade: stop attempting the token
 *    stream for that sid until [resetDegraded] is called by the next health
 *    re-check).
 *  - **generation guard** (bgpt MF-3): per-sid generation + per-partId owner
 *    tag. Stale clears (from an old session/generation) do NOT wipe a newer
 *    session's same-partId overlay — extends epoch protection to clear-effects.
 *  - **consume reducer effects → dispatch**: runs [TokenStreamReducer.reduce]
 *    on each (post-epoch) frame; emits [AppAction.ClearTokenStreamState],
 *    invokes the [triggerSinceFetch] hook, schedules reconnect.
 *  - **bridge reducer state → ChatState**: dispatches [AppAction.TokenStreamPartUpdated]
 *    so `streamOwned` / `streamingPartTexts` reflect the live token buffer
 *    (the write-side Stage-B single-owner guard + Stage-A clear contract).
 *
 * # What D1 does NOT do (D2 territory)
 *
 *  - NO ChatViewModel.loadMessages / busy-open UX wiring.
 *  - NO MessageCard display key-lifecycle.
 *  - NO session.deleted digest → close hook (D2 wires digest→coordinator.close).
 *  - NO `/since` 404 terminal handling (D2).
 *  - NO ConnectionCoordinator foreground/background integration (D2 calls
 *    [open] / [close] from the foreground lifecycle).
 *  - NO DI binding (D2 wires the production constructor params: real
 *    `streamProvider` from `tokenStreamClient(hostPort)` + TokenStreamClient.connect,
 *    real [triggerSinceFetch] → ControllerEffect.LoadMessages or
 *    SessionSyncCoordinator's reconcile path).
 *
 * # Coroutine scope / scheduler
 *
 * The coordinator is constructed with the caller's [scope] (production: the
 * app main CoroutineScope, matching every other UI controller in this package;
 * tests: a `TestScope(UnconfinedTestDispatcher())`). All launches — debounce,
 * collector, watchdog, reconnect-backoff — run on that scope. The
 * UnconfinedTestDispatcher in tests makes timing deterministic without
 * sleeping; the watchdog's `delay(watchdogPollMs)` + the test clock advance
 * together via `testScheduler.advanceUntilIdle()`.
 *
 * # TriggerSinceFetch wiring
 *
 * There is no clean single-action dispatch for the slimapi `/since` fetch —
 * the existing path is [cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator.reconcileSession]
 * wrapped behind a striped lock + cadence state machine, reached via
 * [cn.vectory.ocdroid.ui.controller.ControllerEffect.LoadMessages] (collected
 * by AppCore) OR the resync sweep. Rather than couple D1 to either (which
 * would pull SessionSyncCoordinator + AppCore into the engine's tests), D1
 * takes a [triggerSinceFetch] callback and invokes it verbatim. **D2 wires
 * the callback** to the chosen /since trigger (likely
 * `ControllerEffect.LoadMessages(sid, resetLimit = false)` emit on the shared
 * effect bus, OR a direct SessionSyncCoordinator.reconcileSession call).
 *
 * RFC reference: dev-plan §3.7 / §3.8 / §5.8 / §3.10 Stage-D.
 */
/**
 * A stream flow together with the exact immutable client bundle that created
 * it. The strongly typed [bundle] is retained by reference for
 * generation/endpoint validation.
 */
data class TokenStreamConnection(
    val flow: kotlinx.coroutines.flow.Flow<TokenStreamFrame>,
    val bundle: ClientBundle,
)

/**
 * §Stage-B C3 (CRITICAL): the route + bundle context captured at the moment
 * a token frame's commit hook fires. The hooks ([TokenStreamCoordinator.dedupPartRevision],
 * [TokenStreamCoordinator.onMessagePartRemoved], [TokenStreamCoordinator.onMessageRemoved])
 * MUST receive the context that was live at the frame's dispatch entry — NOT
 * a fresh "latest route token" read at callback time. Threading the captured
 * pair verbatim preserves the epoch+bundle critical-section invariant: a
 * bundle rotation between the epoch check and the hook is impossible, and a
 * late straggler frame cannot adopt a newer route incarnation.
 *
 * - [expectedRouteInstance]: the [SliceFlows.routeInstanceFor] snapshot
 *   captured at THIS lifecycle's open()/runStream() entry (the same value
 *   threaded as `capturedRouteInstance` through [TokenStreamCoordinator.dispatchEpochFrame]).
 * - [bundleStamp]: the [BundleStamp] derived from the bound [ClientBundle]
 *   (generation + endpointFp) at dispatch time. Production wiring (Lane I)
 *   forwards these verbatim into [AppAction.SlimFullMessageReconciled] /
 *   [AppAction.MessageRemovedConfirmed] so the §7.2 route + bundle CAS in the
 *   reducer stays the single source of truth for transcript mutations.
 */
data class TokenFrameCommitContext(
    val expectedRouteInstance: Long,
    val bundleStamp: BundleStamp,
)

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
    /**
     * Consecutive 503 `sse_token_subscriber_limit` failures before
     * capability-degrade kicks in (stop attempting the token stream for the
     * sid until [resetDegraded]).
     */
    private val maxConsecutive503: Int = MAX_CONSECUTIVE_503,
    /** Retry-After honour for 503 (sidecar advertises 5s). */
    private val retryAfter503Ms: Long = RETRY_AFTER_503_MS,
    /** Injectable clock for deterministic watchdog tests. */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * Classifies a flow-completion throwable into the recovery class the
     * coordinator should follow. Default inspects the message for 503 +
     * subscriber-limit markers (TokenStreamClient closes with
     * `Exception("token stream failed (code=503)")` when OkHttp's onFailure
     * had no throwable but a 503 response). Tests override to classify
     * crafted exceptions deterministically.
     */
    private val classifyFailure: (Throwable?) -> TokenStreamFailure = ::defaultClassifyFailure,
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
    // ── State ───────────────────────────────────────────────────────────────
    //
    // All shared mutable state uses concurrent + atomic primitives. The
    // coordinator is effectively single-threaded in production (main scope)
    // and tests (UnconfinedTestDispatcher), but the cross-thread visibility
    // discipline future-proofs against dispatcher changes and makes the
    // generation-guard CAS semantics explicit.

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
     * Per-sid monotonic epoch counter. Bumped at every open(sid) that reaches
     * runStream.
     */
    private val epochBySid = ConcurrentHashMap<String, AtomicLong>()
    /**
     * Per-sid generation counter (bgpt MF-3). Bumped by [beginSession]; used
     * to reject stale clears that target a partId a NEWER session/generation
     * now owns. Distinct from [epochBySid] (epoch tags inbound frames;
     * generation tags outbound clears) — they bump together at every open/
     * reconnect but serve different guards.
     */
    private val genBySid = ConcurrentHashMap<String, AtomicLong>()
    /** partId → the (sid, generation) that owns it. */
    private val ownerByPartId = ConcurrentHashMap<String, OwnerTag>()
    /** Per-sid reducer working state (single active stream, but per-sid for safety). */
    private val reducerStateBySid = ConcurrentHashMap<String, TokenStreamReducerState>()
    /** Per-sid consecutive reconnect attempts (drives backoff growth). */
    private val attemptBySid = ConcurrentHashMap<String, AtomicInteger>()
    /** Per-sid consecutive 503 subscriber-limit failures (drives capability-degrade). */
    private val consecutive503BySid = ConcurrentHashMap<String, AtomicInteger>()
    /**
     * Capability-degraded sids — the sidecar's per-instance token-stream
     * admission cap has been hit repeatedly; the coordinator stops attempting
     * the stream until [resetDegraded] (called by D2's next health re-check).
     */
    private val degradedSids: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun dispatchBound(boundBundle: ClientBundle, action: AppAction) {
        synchronized(bundleCommitLock) {
            if (currentBundleProvider() !== boundBundle) {
                DebugLog.d(TAG, "bundle-bound dispatch rejected: superseded bundle")
                return
            }
            val liveStamp = BundleStamp(boundBundle.generation, boundBundle.endpointFp)
            val stampMatches = when (action) {
                is AppAction.PartPlaceholderEnsured -> action.bundleStamp == liveStamp
                is AppAction.PartFullTextReceived -> action.bundleStamp == liveStamp
                is AppAction.PartDeltaReceived -> action.bundleStamp == liveStamp
                is AppAction.CoalesceFlushedForPart -> action.bundleStamp == liveStamp
                is AppAction.ClearTokenStreamState -> action.bundleStamp == liveStamp
                is AppAction.TokenStreamPartUpdated -> action.bundleStamp == liveStamp
                else -> true
            }
            if (!stampMatches) {
                DebugLog.d(TAG, "bundle-bound dispatch rejected: stamp mismatch")
                return
            }
            slices.store.dispatch(action)
        }
    }

    /** Dispatches an ownership clear with a stamp captured under the bundle lock. */
    internal fun dispatchTokenStreamClear(
        partIds: Set<String>,
        expectedRouteInstance: Long,
        sessionId: String?,
    ): Boolean {
        synchronized(bundleCommitLock) {
            val bundle = currentBundleProvider() ?: return false
            dispatchBound(
                bundle,
                AppAction.ClearTokenStreamState(
                    partIds = partIds,
                    expectedRouteInstance = expectedRouteInstance,
                    sessionId = sessionId,
                    bundleStamp = BundleStamp(bundle.generation, bundle.endpointFp),
                ),
            )
            return true
        }
    }

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
     * approach had a recovery hole:
     *
     *  1. sid A processes `resync(reconnect)` → `handleEffect` sets sentinel `A`.
     *  2. Before the post-dispatch check, `open(B)` cancels A's lifecycle.
     *  3. A's collect throws `CancellationException` (NOT `TokenStreamReconnectRequested`)
     *     → catch path that clears the sentinel does NOT run → sentinel stays `A`.
     *  4. B's runStream does `compareAndSet(B, null)` → **FAILS** (value is `A`).
     *  5. B gets a Reconnect → `compareAndSet(null, B)` → **FAILS** (value is `A`).
     *  6. Post-check `reconnectRequested.get() == B` is false → **B never reconnects**.
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

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Foreground opt-in connect for [sid]. Supersedes any currently-open stream
     * (max-1: opening B closes A). Applies a short debounce to coalesce rapid
     * opens (UI taps / state-driven bursts) before actually issuing the
     * transport connect.
     *
     * `directory` is threaded verbatim into the [streamProvider]; production
     * D2 wiring resolves it from the live workdir just before calling open().
     *
     * §MF-1 (gate r1): the open's lifecycle job goes through [launchStreamLifecycle]
     * which supersedes the prior [currentStreamJob] (whether it was an active
     * collector OR a pending reconnect-in-backoff). This is the max-1 invariant:
     * there is exactly ONE lifecycle job at any time.
     *
     * [source] is a diagnostic tag logged at entry so repro logcat can
     * distinguish the driving caller.
     *
     * @param source diagnostic tag identifying the caller (e.g. "effect-load",
     *   "chatvm-load"); logged at entry for root-cause attribution. Defaults to
     *   "unknown" for backward compatibility.
     */
    fun open(sid: String, directory: String? = null, source: String = "unknown") {
        if (sid.isBlank()) return
        DebugLog.i(TAG, "open($sid) source=$source")
        // §sse-disabled-debug-toggle: REST-only mode — do NOT connect the
        // per-session token stream. Gate at the coordinator ENTRY (before
        // currentSid/state mutation and before launchStreamLifecycle) so no
        // lifecycle job / debounce / streamProvider call is ever made. The chat
        // falls back to REST (triggerSinceFetch / resync / manual refresh);
        // ProcessStatusPoller keeps refreshing status (it never owned a stream).
        if (sseDisabled()) {
            DebugLog.i(TAG, "open($sid): sse_disabled=true → REST-only (no token stream)")
            return
        }
        // §T1.1 Fix① + T3.2-C1 IDEMPOTENT GUARD: same (sid, directory,
        // routeToken, published bundle reference) + active lifecycle → skip
        // (no supersede, no provider call). This is the
        // single-flight guard for open(): a burst of identical opens (UI taps,
        // state-driven bursts, reconnect-backoff re-entry) absorbs into the
        // existing lifecycle. A different sid or directory, an inactive job
        // (cancelled / completing), a changed published bundle, OR a same-session NEW route incarnation
        // (navigateToChat same-session re-entry → chatRouteInstance advanced)
        // falls through to supersede. The guard and lifecycle installation are
        // serialized by [bundleCommitLock], so configure/open cannot split this
        // decision from the supersede operation.
        //
        // §B4 rev-gpt round3 MAJOR: the route token comparison is REQUIRED.
        // navigateToChat same-session re-entry deliberately bypasses the
        // SessionSwitcher's same-session guard (openForRoute → performSwitch →
        // SessionSelected ALWAYS dispatches). Without the token check, this
        // guard would skip on (sid, directory) match alone → the prior
        // lifecycle's stale captured token would persist → token-stream frames
        // would carry the OLD token → reducer's acceptsRouteUpdate would reject
        // → new incarnation would receive NO real-time updates.
        synchronized(bundleCommitLock) {
        val currentBundle = currentBundleProvider()
        val existingLifecycle = currentLifecycle.get()
        val existingJob = existingLifecycle?.job
        val currentRouteInstance = slices.routeInstanceFor(sid)
        if (currentSid.get() == sid &&
            currentDirectory.get() == directory &&
            existingJob?.isActive == true &&
            lifecycleRouteInstance.get() == currentRouteInstance && // §B4 round3 MAJOR
            existingLifecycle?.bundle === currentBundle
        ) {
            // The first bundle read above is only a candidate for the guard.
            // Re-read immediately before returning: a publication can happen
            // between the candidate snapshot and this skip decision. If it
            // did, fall through and bind the new lifecycle to the new bundle.
            val revalidatedBundle = currentBundleProvider()
            if (existingLifecycle.bundle === revalidatedBundle) {
                DebugLog.i(TAG, "open($sid) idempotent skip — same (sid,dir,routeToken,bundle) + active lifecycle (source=$source)")
                return
            }
            DebugLog.i(TAG, "open($sid) bundle changed during guard revalidation — superseding (source=$source)")
        }
        // B-4 HIGH-2: when switching to a DIFFERENT sid (not same-sid
        // reopen like reconnect), reclaim ALL revision entries for the
        // previous session so stale per-part revisions from the dead
        // connection cannot block fresh frames on the new session's
        // connection. Checked BEFORE currentSid.set(sid) so the previous
        // value is still available. Same-sid (currentSid == new sid) is
        // the idempotent-guard fall-through case (bundle-rotated reopen
        // or navigateToChat same-session re-entry) — must NOT clear
        // its own revisions.
        val prevSid = currentSid.get()
        if (prevSid != null && prevSid != sid) {
            clearSessionRevisions(prevSid)
        }
        currentSid.set(sid)
        currentDirectory.set(directory)
        // §B4 rev-gpt round3 MAJOR: capture this lifecycle's route token for
        // the guard. Read ONCE here (the same snapshot threaded into runStream
        // below as capturedRouteInstance). Reconnect / 503-retry re-set this
        // field at their entry so a same-session open() during their backoff
        // correctly absorbs into the in-flight lifecycle.
        lifecycleRouteInstance.set(currentRouteInstance)
        // §MF-1 (gate r2): UNCONDITIONALLY clear the sentinel. A prior sid's
        // resync(Reconnect) may have set the sentinel to a DIFFERENT sid, and
        // that sid's collect was cancelled before the post-dispatch check
        // could consume it → the sentinel is stale under that foreign sid.
        // CAS-on-sid would fail to clear it, permanently blocking the new
        // sid's Reconnect. set(null) always wins.
        reconnectRequested.set(null)
        val capturedSid = sid
        val capturedDir = directory
        // §B4 lifecycle capture: snapshot the route token BEFORE runStream so
        // late events from this lifecycle carry the token captured at entry
        // (rev-bgpt CRITICAL 2 — prevents a stale event from adopting a newer
        // token via a dynamic routeInstanceFor read). Token=0 is the legacy
        // flat-path scope (the route did not point at this sid when opened).
        // §B4 round-2 (rev-gpt C2): the token is threaded VERBATIM through
        // runStream → dispatchEpochFrame → bridgePartToChatState / handleEffect
        // as a function parameter (NOT a shared AtomicLong field — a shared
        // field would let a new lifecycle's open()/reconnect overwrite the
        // value mid-flight, so a prior lifecycle's late frame could pick up
        // the NEW token and bypass the §7.2 freshness CAS).
        // §B4 rev-gpt round3 MAJOR: reuse the single routeInstanceFor read
        // from the guard above (no suspension between → identical value under
        // the single-threaded premise; avoids a second dynamic read that could
        // race a route advance on a future off-main call-site).
        val capturedRouteInstance = currentRouteInstance
        launchStreamLifecycle(capturedSid, "open") {
            if (openDebounceMs > 0L) {
                delay(openDebounceMs)
            }
            // A newer open() may have superseded us during the debounce window.
            if (currentSid.get() != capturedSid) {
                DebugLog.d(TAG, "open($capturedSid) superseded during debounce — skipping")
                return@launchStreamLifecycle
            }
            if (capturedSid in degradedSids) {
                DebugLog.i(TAG, "open($capturedSid) skipped — degraded (503 cap reached)")
                return@launchStreamLifecycle
            }
            val (epoch, gen) = beginStreamIncarnation(capturedSid)
            try {
                runStream(capturedSid, capturedDir, epoch, gen, isReconnect = false, capturedRouteInstance)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                // runStream's inner catch already classified + handled; this
                // outer net is a defence-in-depth for anything that escaped
                // (e.g. streamProvider itself throwing synchronously).
                DebugLog.w(TAG, "open($capturedSid) escaped: ${e.message}")
                onStreamFailure(capturedSid, capturedDir, e)
            }
        }
        }
    }

    /**
     * Explicit close for [sid]. Cancels the in-flight stream if it matches
     * [sid]; clears per-sid coordinator state (ownership + reducer working
     * state). Does NOT dispatch [AppAction.ClearTokenStreamState] — the
     * caller (D2 / ChatViewModel) owns the UX decision of when to wipe the
     * streaming overlay (e.g. on session switch the existing reducer paths
     * already clear it via [AppAction.SessionSelected]).
     *
     * Does NOT reset capability-degrade state (use [resetDegraded] for that).
     */
    fun close(sid: String) {
        synchronized(bundleCommitLock) {
        if (currentSid.get() == sid) {
            // §MF-1 (gate r1 S1): close cancels the current/pending lifecycle
            // job — whether it's an active collector OR a reconnect-in-backoff
            // (both are tracked in currentStreamJob via launchStreamLifecycle).
            cancelCurrentStream("close($sid)")
            currentSid.set(null)
            currentDirectory.set(null)
            // §MF-1 (gate r2): UNCONDITIONALLY clear the sentinel (was
            // compareAndSet(sid, null)). A cancelled resync(Reconnect) may
            // have left a stale sentinel under a FOREIGN sid that CAS-on-sid
            // would fail to clear.
            reconnectRequested.set(null)
        }
        // Clear coordinator-internal state for this sid regardless of whether
        // it was the current stream (defensive: covers a stale sid whose job
        // was already cancelled by a newer open()).
        ownerByPartId.entries.removeIf { it.value.sid == sid }
        reducerStateBySid.remove(sid)
        // B-4 HIGH-2: reclaim ALL revision entries for this session on close
        // (stream teardown, session switch, background — prevents unbounded
        // growth + provides epoch isolation).
        clearSessionRevisions(sid)
        attemptBySid.remove(sid)
        consecutive503BySid.remove(sid)
        }
    }

    /**
     * D2 hook: clears the capability-degrade flag for [sid] (the next health
     * re-check that re-confirms `SlimapiFeatures.tokenStream == true` should
     * invoke this so a transient sidecap-full state does not permanently
     * disable the token stream for the session).
     */
    fun resetDegraded(sid: String) {
        degradedSids.remove(sid)
        consecutive503BySid.remove(sid)
    }

    /** Test/diagnostic read. */
    internal fun isDegraded(sid: String): Boolean = sid in degradedSids

    /** Test/diagnostic read: the current stream Job (or null when idle). */
    internal fun currentStreamJobSnapshot(): Job? = currentLifecycle.get()?.job

    /** Test/diagnostic read: the current epoch for [sid] (or 0 if none). */
    internal fun epochOf(sid: String): Long = epochBySid[sid]?.get() ?: 0L

    /** Test/diagnostic read: the current generation for [sid] (or 0 if none). */
    internal fun genOf(sid: String): Long = genBySid[sid]?.get() ?: 0L

    /**
     * Test-only: bumps the epoch for [sid] WITHOUT going through open() (so
     * tests can simulate a re-open's epoch bump in isolation while driving
     * frames via [dispatchEpochFrame]).
     */
    internal fun bumpEpochForTest(sid: String): Long =
        synchronized(bundleCommitLock) {
            epochBySid.computeIfAbsent(sid) { AtomicLong(0L) }.incrementAndGet()
        }

    /**
     * §MF-1 (gate r2) test seam: directly sets the reconnect sentinel to
     * simulate a stale value left by a cancelled sid's unconsumed
     * resync(Reconnect). Used by the stale-sentinel regression test to verify
     * that a new sid's open() + Reconnect works regardless of the stale value.
     */
    internal fun setReconnectRequestedForTest(sid: String?) {
        reconnectRequested.set(sid)
    }

    /** §MF-1 (gate r2) test seam: reads the current sentinel value. */
    internal fun reconnectRequestedSnapshot(): String? = reconnectRequested.get()

    /**
     * §MF-2 test seam: the current consecutive-503 streak for [sid] (0 when
     * none recorded). Used by the streak-reset test to assert a successful
     * frame zeroes the counter.
     */
    internal fun consecutive503Of(sid: String): Int =
        consecutive503BySid[sid]?.get() ?: 0

    /**
     * §MF-2 test seam: directly increments the consecutive-503 streak (tests
     * build a partial streak WITHOUT having to wire a 503-throwing provider
     * + then switch to a succeeding one).
     */
    internal fun increment503ForTest(sid: String): Int =
        consecutive503BySid.computeIfAbsent(sid) { AtomicInteger(0) }.incrementAndGet()

    /** Test/diagnostic read: the partIds currently owned by the active stream for [sid]. */
    internal fun ownedPartsForSid(sid: String): Set<String> =
        ownerByPartId.entries
            .asSequence()
            .filter { it.value.sid == sid }
            .map { it.key }
            .toSet()

    // ── Generation guard (bgpt MF-3) ─────────────────────────────────────────

    /**
     * Bumps the generation for [sid] and returns the new value. Called at
     * every open/reconnect so ownership claims + clears emitted by the prior
     * generation become stale.
     */
    internal fun beginSession(sid: String): Long =
        synchronized(bundleCommitLock) {
            genBySid.computeIfAbsent(sid) { AtomicLong(0L) }.incrementAndGet()
        }

    private fun beginStreamIncarnation(sid: String): Pair<Long, Long> =
        synchronized(bundleCommitLock) {
            val epoch = epochBySid.computeIfAbsent(sid) { AtomicLong(0L) }.incrementAndGet()
            val generation = genBySid.computeIfAbsent(sid) { AtomicLong(0L) }.incrementAndGet()
            epoch to generation
        }

    /**
     * Records that [partId] is owned by stream ([sid], [gen]). Only records
     * when [gen] is the current generation for [sid] — a stale claim (from
     * a cancelled collector whose gen lags behind beginSession) is dropped.
     */
    internal fun onPartOwned(sid: String, gen: Long, partId: String) {
        synchronized(bundleCommitLock) {
            val currentGen = genBySid[sid]?.get() ?: return
            if (currentGen != gen) return
            ownerByPartId[partId] = OwnerTag(sid, gen)
        }
    }

    /**
     * Filters [partIds] through the generation guard. Returns the subset that
     * the ([sid], [gen]) stream is allowed to clear:
     *  - partIds whose owner tag matches ([sid], [gen]) → ALLOW (+ remove tag);
     *  - partIds with NO owner tag → ALLOW (no current owner; the clear is a
     *    safe no-op — e.g. a truncated first-snapshot before any onPartOwned);
     *  - partIds whose owner tag is a DIFFERENT (sid', gen') → DROP (a newer
     *    stream owns them; the stale clear MUST NOT wipe the new overlay).
     *
     * Side-effect: removes allowed-and-owned entries from [ownerByPartId].
     */
    internal fun filterClearByGeneration(sid: String, gen: Long, partIds: Set<String>): Set<String> {
        synchronized(bundleCommitLock) {
            if (partIds.isEmpty()) return emptySet()
            val allowed = mutableSetOf<String>()
            for (partId in partIds) {
                val tag = ownerByPartId[partId]
                if (tag == null) {
                    allowed += partId
                } else if (tag.sid == sid && tag.gen == gen) {
                    allowed += partId
                    ownerByPartId.remove(partId)
                }
                // else: stale — a newer stream owns this partId. Drop.
            }
            return allowed
        }
    }

    // ── Epoch-tagged frame dispatch (unit-testable surface) ──────────────────

    /**
     * Epoch-guarded entry: validates [sid]/[epoch] against the current
     * `epochBySid[sid]` BEFORE any reduce / state mutation. Drops stale
     * frames (the connection that delivered this frame has been torn down
     * and re-opened under a newer epoch — late OkHttp callbacks that leaked
     * past the transport's own `closed` guard). Then resets the watchdog,
     * runs the pure reducer, bridges any part-text change into ChatState,
     * and processes emitted effects.
     *
     * Exposed internal so unit tests can drive frames with crafted epochs
     * without going through the asynchronous [streamProvider].
     */
    /**
     * Epoch-guarded entry: validates [sid]/[epoch] against the current
     * `epochBySid[sid]` BEFORE any reduce / state mutation. Drops stale
     * frames (the connection that delivered this frame has been torn down
     * and re-opened under a newer epoch — late OkHttp callbacks that leaked
     * past the transport's own `closed` guard). Then resets the watchdog,
     * runs the pure reducer, bridges any part-text change into ChatState,
     * and processes emitted effects.
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
        if (!isBundleCurrentForCommit(boundBundle)) return
        val currentEpoch = epochBySid[sid]?.get() ?: return
        if (currentEpoch != epoch) {
            DebugLog.d(
                TAG,
                "drop stale-epoch frame sid=$sid epoch=$epoch current=$currentEpoch type=${frame::class.simpleName}",
            )
            return
        }
        // Reset watchdog on ANY frame (incl. heartbeat + server.connected).
        lastFrameAt.set(clock())
        // Reset reconnect-attempt counter on any successful frame — the link
        // is alive, the next failure should start a fresh backoff ladder.
        attemptBySid[sid]?.set(0)
        // §MF-2 (gate r1): reset the consecutive-503 streak on any successful
        // frame. The "consecutive failures" contract for capability-degrade
        // means N 503s IN A ROW with no successful traffic between them —
        // intermittent 503s over a long session must NOT accumulate to a
        // permanent degrade. A frame proves the stream is alive (the 503
        // admission gate let us in), so the streak is broken.
        consecutive503BySid[sid]?.set(0)

        // §Stage-B C3 (CRITICAL): capture the route + bundle context ONCE
        // inside the epoch+bundle critical section (after both guards have
        // passed) and thread it VERBATIM through every hook call below. The
        // hooks MUST receive the context that was live at THIS frame's
        // dispatch entry — NOT a fresh read at callback time (a bundle
        // rotation or route advance between the guards and the hook is
        // impossible inside the synchronized block, and threading the
        // captured pair prevents a late straggler frame from adopting a
        // newer route incarnation). Lane I forwards these unchanged into
        // [AppAction.SlimFullMessageReconciled] / [AppAction.MessageRemovedConfirmed]
        // so the §7.2 route + bundle CAS in the reducer is the single source
        // of truth for transcript mutations.
        val commitContext = TokenFrameCommitContext(
            expectedRouteInstance = capturedRouteInstance,
            bundleStamp = BundleStamp(boundBundle.generation, boundBundle.endpointFp),
        )

        val priorState = reducerStateBySid[sid] ?: TokenStreamReducerState()
        // S2 (Stage-C should-fix): a resync frame may arrive with
        // sessionId == null (backpressure overflow omits it per the sidecar's
        // handoff contract). The reducer cannot act on a null sid (it would
        // return no effects), so the coordinator rewrites the frame to carry
        // the ACTIVE connection's sid BEFORE reduction. This is the engine-
        // level fix; handleEffect's TriggerSinceFetch branch carries a second
        // defensive fallback for any other path that surfaces a null sid.
        val effectiveFrame: TokenStreamFrame =
            if (frame is TokenStreamFrame.Resync && frame.sessionId == null) {
                frame.copy(sessionId = sid)
            } else {
                frame
            }
        // The reducer's resync branch unions reducer-known parts with the
        // EXTERNALLY-owned parts for the sid (the ChatState.streamOwned view).
        // We feed our own ownerByPartId projection as that union source — it
        // IS the authoritative ownership map (Stage A's clear reads the same
        // concept via ChatState.streamOwned; D1 keeps its own working set so
        // the engine stays decoupled from the UI slice in unit tests).
        val ownedBySession: Map<String, Set<String>> = mapOf(sid to ownedPartsForSid(sid))
        // B-P0-1 (R1+R2 dedup wiring): apply the per-part revision
        // dedup BEFORE the streaming reducer. A `false` return means
        // the frame is a re-delivery within the 250ms debounce window
        // — drop it (no reducer state mutation, no bridge, no effects).
        // The hook is the injected [dedupPartRevision] callback; it is
        // null-safe (a null partEventRevision always returns true
        // — accept every frame, matching the pre-B-P0-1 behavior).
        //
        // Per the B-P0-3 frozen contract: the dedup MUST run BEFORE
        // TokenStreamReducer.reduce to prevent the streaming-overlay
        // from being polluted by a re-delivered snapshot/delta.
        when (effectiveFrame) {
            is TokenStreamFrame.PartSnapshot -> {
                val fresh = dedupPartRevision(
                    effectiveFrame.sessionId,
                    effectiveFrame.messageId,
                    effectiveFrame.partId,
                    effectiveFrame.partEventRevision,
                    commitContext,
                )
                if (!fresh) {
                    DebugLog.d(
                        TAG,
                        "drop dup part-snapshot sid=${effectiveFrame.sessionId} " +
                            "mid=${effectiveFrame.messageId} pid=${effectiveFrame.partId} " +
                            "rev=${effectiveFrame.partEventRevision}",
                    )
                    return
                }
            }
            is TokenStreamFrame.PartDelta -> {
                val fresh = dedupPartRevision(
                    effectiveFrame.sessionId,
                    effectiveFrame.messageId,
                    effectiveFrame.partId,
                    effectiveFrame.partEventRevision,
                    commitContext,
                )
                if (!fresh) {
                    DebugLog.d(
                        TAG,
                        "drop dup part-delta sid=${effectiveFrame.sessionId} " +
                            "mid=${effectiveFrame.messageId} pid=${effectiveFrame.partId} " +
                            "rev=${effectiveFrame.partEventRevision}",
                    )
                    return
                }
            }
            else -> { /* no dedup for non-part frames */ }
        }
        val (newState, effects) = TokenStreamReducer.reduce(priorState, effectiveFrame, ownedBySession)
        if (!isBundleCurrentForCommit(boundBundle)) return
        reducerStateBySid[sid] = newState

        // B-P0-2 (MAJOR 4 + replacement edge): fire the message.part.removed
        // / message.removed hooks AFTER the reducer has cleared its in-memory
        // overlay (the [TokenStreamReducer.reduce] call above already removed
        // the part(s) from [reducerStateBySid]). The hooks run INSIDE the
        // bundleCommitLock critical section (no bundle rotation between epoch
        // check and hook), carrying the [commitContext] captured at this
        // frame's dispatch entry. The ClearPartState effect (chat-slice
        // streamOwned/streamingPartTexts clear) is processed AFTER the hooks
        // via [handleEffect] below — the §Stage-B C3 frozen contract is:
        // reducer-overlay clear (synchronous, in-memory) → hook (watermark +
        // R2 debounce schedule) → chat-slice overlay clear (effect dispatch).
        // The hook implementations are no-ops by default; B-P0-2's DI wiring
        // (Lane I, ControllerModule) injects the production callbacks
        // (applyMessagePartRemoved / applyMessageRemoved + debounced
        // reconcileActiveSession / MessageRemovedConfirmed dispatch).
        when (effectiveFrame) {
            is TokenStreamFrame.PartSnapshot -> {
                // B-4 HIGH-2 + HIGH-1: reclaim revision entry when part reaches
                // done:true OR truncated:true. Both are terminal: the reducer
                // removes the part from the overlay (ClearPartState) and the
                // dedup revision map must not retain an entry that could block
                // a future frame for the same partId.
                if (effectiveFrame.done || effectiveFrame.truncated) {
                    onPartDone(effectiveFrame.sessionId, effectiveFrame.messageId, effectiveFrame.partId)
                }
            }
            is TokenStreamFrame.MessagePartRemoved -> {
                val msgSeq = effectiveFrame.messageEventSeq ?: 0L
                if (!isBundleCurrentForCommit(boundBundle)) return
                onMessagePartRemoved(
                    effectiveFrame.sessionId,
                    effectiveFrame.messageId,
                    effectiveFrame.partId,
                    msgSeq,
                    commitContext,
                )
            }
            is TokenStreamFrame.MessageRemoved -> {
                if (!isBundleCurrentForCommit(boundBundle)) return
                onMessageRemoved(
                    effectiveFrame.sessionId,
                    effectiveFrame.messageId,
                    commitContext,
                )
            }
            is TokenStreamFrame.Resync -> {
                // B-4 HIGH-2 + MEDIUM: resync clears ALL parts for the session
                // (epoch boundary — stale revisions from the old connection
                // cannot block fresh frames on the new connection).
                effectiveFrame.sessionId?.let { clearSessionRevisions(it) }
            }
            else -> { /* no hook for other frame types */ }
        }

        // Bridge reducer state → ChatState for any frame that touches a part
        // (snapshot / delta). ServerConnected / Heartbeat / Resync carry no
        // single partId; resync's part-clearing is handled via the effect
        // path below (ClearPartState).
        val partId = when (effectiveFrame) {
            is TokenStreamFrame.PartSnapshot -> effectiveFrame.partId
            is TokenStreamFrame.PartDelta -> effectiveFrame.partId
            else -> null
        }
        // §B4 lifecycle token: use the value captured at open()/runStream()
        // entry and threaded verbatim through the call chain (rev-gpt C2) —
        // NOT a shared-field read (a new lifecycle's open()/reconnect could
        // overwrite a shared field mid-flight, letting a prior lifecycle's
        // late frame pick up the NEW token).
        if (partId != null) {
            bridgePartToChatState(sid, gen, partId, newState, capturedRouteInstance, boundBundle)
        }

        val directory = currentDirectory.get()
        for (effect in effects) {
            handleEffect(
                sid,
                epoch,
                gen,
                directory,
                effect,
                capturedRouteInstance,
                boundBundle,
                deferredEffects,
            )
        }
        }
        deferredEffects.forEach { it() }
    }

    /**
     * Bridges a single partId's post-reduce state into ChatState via
     * [AppAction.TokenStreamPartUpdated]. Records ownership first (generation-
     * guarded) so the matching clear (later) passes the filter.
     */
    private fun bridgePartToChatState(
        sid: String,
        gen: Long,
        partId: String,
        state: TokenStreamReducerState,
        capturedRouteInstance: Long,
        boundBundle: ClientBundle,
    ) {
        if (!isBundleCurrentForCommit(boundBundle)) return
        val acc = state.parts[partId] ?: return
        if (!isBundleCurrentForCommit(boundBundle)) return
        onPartOwned(sid, gen, partId)
        val stamp = BundleStamp(boundBundle.generation, boundBundle.endpointFp)
        // §E2 PartPlaceholderEnsured from bridge: when a NEW partId arrives that is
        // NOT yet in partsByMessage[messageId], dispatch PartPlaceholderEnsured BEFORE
        // the TokenStreamPartUpdated, so MessageCard has a stable list key.
        val msgId = acc.messageId
        if (slices.chat.value.partsByMessage[msgId]?.none { it.id == partId } != false) {
            if (!isBundleCurrentForCommit(boundBundle)) return
            dispatchBound(
                boundBundle,
                AppAction.PartPlaceholderEnsured(
                    partType = "text",
                    partId = partId,
                    messageId = msgId,
                    sessionId = sid,
                    expectedRouteInstance = capturedRouteInstance,
                    bundleStamp = stamp,
                ),
            )
        }
        val owned = when (acc.state) {
            TokenPartStreamState.STREAMING -> StreamOwnedState.STREAMING
            TokenPartStreamState.DONE -> StreamOwnedState.DONE
        }
        if (!isBundleCurrentForCommit(boundBundle)) return
        dispatchBound(
            boundBundle,
            AppAction.TokenStreamPartUpdated(
                partId = partId,
                text = acc.text,
                state = owned,
                sessionId = sid,
                expectedRouteInstance = capturedRouteInstance,
                bundleStamp = stamp,
            ),
        )
    }

    /**
     * Translates one reducer effect into concrete side effects:
     *  - [TokenStreamCoordinatorEffect.ClearPartState] → generation-guard filter,
     *    then [AppAction.ClearTokenStreamState] (only the allowed subset).
     *  - [TokenStreamCoordinatorEffect.TriggerSinceFetch] → invoke the
     *    [triggerSinceFetch] callback verbatim (D2 wires it to the /since path).
     *  - [TokenStreamCoordinatorEffect.Reconnect] → schedule reconnect backoff.
     *
     * [directory] is the currently-captured stream directory (for reconnect).
     */
    private fun handleEffect(
        sid: String,
        @Suppress("UNUSED_PARAMETER") epoch: Long,
        gen: Long,
        directory: String?,
        effect: TokenStreamCoordinatorEffect,
        capturedRouteInstance: Long,
        boundBundle: ClientBundle,
        deferredEffects: MutableList<() -> Unit>,
    ) {
        if (!isBundleCurrentForCommit(boundBundle)) return
        when (effect) {
            is TokenStreamCoordinatorEffect.ClearPartState -> {
                val allowed = filterClearByGeneration(sid, gen, effect.partIds)
                if (!isBundleCurrentForCommit(boundBundle)) return
                if (allowed.isNotEmpty()) {
                    if (!isBundleCurrentForCommit(boundBundle)) return
                    val stamp = BundleStamp(boundBundle.generation, boundBundle.endpointFp)
                    dispatchBound(
                        boundBundle,
                        AppAction.ClearTokenStreamState(
                            allowed,
                            expectedRouteInstance = capturedRouteInstance,
                            sessionId = sid,
                            bundleStamp = stamp,
                        ),
                    )
                }
            }
            is TokenStreamCoordinatorEffect.TriggerSinceFetch -> {
                if (!isBundleCurrentForCommit(boundBundle)) return
                // S2 (Stage-C should-fix): a resync frame may arrive with
                // sessionId == null (backpressure overflow omits it per the
                // handoff contract). Infer from the active connection's sid.
                val resolvedSid = effect.sessionId.takeIf { it.isNotBlank() } ?: sid
                deferredEffects += {
                    triggerSinceFetch(resolvedSid, effect.authoritative)
                }
            }
            is TokenStreamCoordinatorEffect.Reconnect -> {
                if (!isBundleCurrentForCommit(boundBundle)) return
                // §MF-1 (gate r1): do NOT call scheduleReconnect from here —
                // handleEffect runs synchronously INSIDE `flow.collect { }`
                // (called from dispatchEpochFrame). Calling scheduleReconnect
                // directly would supersede the currently-running job via
                // launchStreamLifecycle, causing a self-cancellation mid-frame.
                // Instead, set the sentinel; the collect lambda checks it
                // right after dispatchEpochFrame returns and throws
                // TokenStreamReconnectRequested to unwind the collector. The
                // run-loop's catch path (the SINGLE re-entry point) then
                // calls scheduleReconnect AFTER the old flow's EventSource is
                // torn down — guaranteeing no overlapping collectors.
                // §MF-1 (gate r2): UNCONDITIONALLY set the sentinel (was
                // compareAndSet(null, sid)). If a prior sid's stale sentinel
                // persists (cancelled-before-consumed), CAS-on-null would
                // fail to overwrite it, silently dropping THIS sid's
                // Reconnect. set(sid) always wins. The post-dispatch check
                // reads .get()==sid so the last Reconnect in the batch wins
                // (only one post-dispatch check runs per frame).
                reconnectRequested.set(sid)
            }
        }
    }

    // ── Stream run loop (collector + watchdog) ───────────────────────────────

    /**
     * The per-open collector body. Launches the watchdog as a child of an
     * inner [coroutineScope] (NON-supervisor) so a [TokenStreamWatchdogTimeout]
     * thrown by the watchdog cancels the scope, which in turn cancels the
     * suspended `flow.collect { ... }`. The outer try-catch then runs the
     * matching recovery sequence.
     *
     * Using [coroutineScope] (NOT [scope].launch) for the watchdog+collector
     * pair is the structured-concurrency fix: an inner non-supervisor scope
     * guarantees the watchdog's throw propagates to the collector regardless
     * of whether the outer [scope] is a SupervisorJob (production app main
     * scope often is). Without this, the throw would be swallowed inside the
     * watchdog's launch and runStream would hang forever in `flow.collect`.
     *
     * Catches:
     *  - [TokenStreamWatchdogTimeout] → watchdog tripped; clear sid's parts +
     *    TriggerSinceFetch(auth) + schedule reconnect.
     *  - [CancellationException] → re-thrown (structured concurrency).
     *  - any other [Throwable] → classified via [classifyFailure]; transient
     *    → reconnect backoff, 503 subscriber-limit → bounded Retry-After then
     *    degrade after N, normal (server clean close) → stop.
     */
    private suspend fun runStream(
        sid: String,
        directory: String?,
        epoch: Long,
        gen: Long,
        isReconnect: Boolean,
        capturedRouteInstance: Long,
    ) {
        if (isReconnect && sid in degradedSids) return
        val connection = try {
            streamConnectionProvider?.invoke(sid, directory) ?: run {
                val bundle = currentBundleProvider() ?: return
                TokenStreamConnection(
                    flow = streamProvider(sid, directory),
                    bundle = bundle,
                )
            }
        } catch (e: Throwable) {
            // Provider itself threw synchronously (test fake misconfig or a
            // production TokenStreamClient constructor blow-up). Treat as a
            // failure of the stream.
            onStreamFailure(sid, directory, e)
            return
        }
        val boundBundle = connection.bundle
        if (!bindCurrentLifecycleBundle(boundBundle)) return
        if (!isCurrentLifecycleBundle(boundBundle)) return
        // §MF-1 (gate r2): clear the sentinel only after this lifecycle has
        // proved ownership of its resolve-time bundle. A stale lifecycle must
        // not mutate the replacement stream's reconnect state.
        reconnectRequested.set(null)
        // Seed watchdog clock at open so it can fire BEFORE the first frame.
        lastFrameAt.set(clock())
        val flow = connection.flow

        try {
            coroutineScope {
                val watchdogJob = launch {
                    // bgpt MF-2 fix: NO eventCount==0 skip — the watchdog is
                    // active FROM open and resets on ANY frame (incl.
                    // heartbeat) via lastFrameAt.
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
                        dispatchEpochFrame(sid, epoch, gen, frame, capturedRouteInstance, boundBundle)
                        // §MF-1 (gate r1): mid-collect Reconnect sentinel. If
                        // handleEffect set the sentinel during this frame's
                        // dispatch (reducer emitted a Reconnect effect), unwind
                        // the collector so the run-loop's catch path can call
                        // scheduleReconnect AFTER the flow's EventSource is
                        // torn down (no overlapping collectors). Checked AFTER
                        // dispatchEpochFrame so ALL effects in the batch
                        // (ClearPartState, TriggerSinceFetch) are processed first.
                        if (reconnectRequested.get() == sid) {
                            throw TokenStreamReconnectRequested(sid)
                        }
                    }
                    // Flow completed normally → server closed cleanly.
                    DebugLog.i(TAG, "stream completed (server closed) sid=$sid")
                    if (isBundleCurrentForCommit(boundBundle)) {
                        attemptBySid[sid]?.set(0)
                    }
                } finally {
                    // Always cancel the watchdog when collect returns (normally
                    // OR via the scope cancellation the watchdog itself
                    // triggered). Idempotent.
                    watchdogJob.cancel()
                }
            }
        } catch (e: TokenStreamWatchdogTimeout) {
            onWatchdogTimeout(sid, epoch, gen, directory, capturedRouteInstance, boundBundle)
        } catch (e: TokenStreamReconnectRequested) {
            // §MF-1 (gate r1/r2): the SINGLE re-entry point for runStream after
            // a mid-collect Reconnect effect. The old flow's EventSource was
            // torn down by the throw (awaitClose fired) BEFORE this catch
            // runs, so calling scheduleReconnect here opens the new
            // EventSource only after the old one is gone — no overlap.
            // §gate r2: UNCONDITIONALLY clear (was compareAndSet(sid, null)).
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

    /**
     * Watchdog tripped: cancel the stream (the throw already unwinds the
     * collector), clear the sid's parts (generation-guarded), trigger an
     * authoritative /since fetch (the in-flight overlay may be stale), and
     * schedule a reconnect (the link is dead).
     *
     * §B4 round-2 (rev-gpt C2): [capturedRouteInstance] is threaded from
     * runStream (NOT read from a shared field) so the clear dispatch carries
     * the token captured at THIS lifecycle's entry.
     */
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
            val parts = ownedPartsForSid(sid)
            val allowed = filterClearByGeneration(sid, gen, parts)
            if (allowed.isNotEmpty()) {
                // Keep the ownership removal and its ChatState clear in this
                // same monitor acquisition; a superseding publication cannot
                // slip between them.
                dispatchBound(
                    boundBundle,
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

    /**
     * Flow failed (or provider threw). Classify and route to the matching
     * recovery class.
     *
     * §MF-1 (gate r1): the 503-retry path goes through [launchStreamLifecycle]
     * (same as open + scheduleReconnect) so the retry is tracked in
     * [currentStreamJob] and supersedes any prior lifecycle job. This closes
     * the "orphan 503-retry in delay + user open(A) → second concurrent
     * collector" race.
     */
    private fun onStreamFailure(sid: String, directory: String?, t: Throwable) {
        when (val cls = classifyFailure(t)) {
            TokenStreamFailure.Normal -> {
                // Server clean close — no reconnect, reset attempts.
                attemptBySid[sid]?.set(0)
            }
            TokenStreamFailure.Transient -> {
                scheduleReconnect(sid, directory)
            }
            TokenStreamFailure.SubscriberLimit503 -> {
                val n = consecutive503BySid.computeIfAbsent(sid) { AtomicInteger(0) }.incrementAndGet()
                DebugLog.w(TAG, "503 sse_token_subscriber_limit sid=$sid consecutive=$n/$maxConsecutive503")
                if (n >= maxConsecutive503) {
                    DebugLog.w(TAG, "503 cap reached sid=$sid — degrading (next health re-check re-arms via resetDegraded)")
                    degradedSids.add(sid)
                    // Stop attempting — do NOT schedule reconnect.
                } else {
                    // §MF-1 (gate r1): bounded Retry-After backoff via the
                    // lifecycle-tracked launch helper (NOT a bare scope.launch)
                    // so the retry is supervised under currentStreamJob.
                    // §B4 lifecycle capture at 503-retry: snapshot the route
                    // token NOW and thread it verbatim through runStream →
                    // dispatchEpochFrame (rev-gpt C2 — no shared field).
                    // §B4 rev-gpt round3 MAJOR: also publish into
                    // lifecycleRouteInstance so the open() guard recognizes
                    // this retry as the in-flight lifecycle (a same-session
                    // open during the Retry-After delay absorbs instead of
                    // superseding a perfectly-good retry).
                    val retryRouteInstance = slices.routeInstanceFor(sid)
                    lifecycleRouteInstance.set(retryRouteInstance)
                    launchStreamLifecycle(sid, "503-retry") {
                        delay(retryAfter503Ms)
                        if (currentSid.get() != sid) return@launchStreamLifecycle
                        if (sid in degradedSids) return@launchStreamLifecycle
                        val (epoch, gen) = beginStreamIncarnation(sid)
                        try {
                            runStream(sid, directory, epoch, gen, isReconnect = true, retryRouteInstance)
                        } catch (ce: CancellationException) {
                            throw ce
                        }
                    }
                }
            }
        }
    }

    // ── Reconnect backoff ────────────────────────────────────────────────────

    /**
     * Schedules a reconnect for [sid] under the captured [directory] with
     * exponential backoff (seed [initialBackoffMs] × [backoffMultiplier]^attempt,
     * capped at [maxBackoffMs]). Skipped if [sid] is capability-degraded.
     *
     * §MF-1 (gate r1): the reconnect job goes through [launchStreamLifecycle]
     * which superseded the prior [currentStreamJob]. This is the ONLY re-entry
     * point for runStream after a collector unwinds (watchdog timeout,
     * mid-collect Reconnect sentinel, transient failure) — guaranteeing no
     * overlapping collectors. The prior job (the unwound collector) is already
     * completing when this runs, so the supersede cancel is effectively a
     * no-op on it; the critical guarantee is that the NEW backoff-job is the
     * sole tracked lifecycle job.
     */
    private fun scheduleReconnect(sid: String, directory: String?) {
        if (sid in degradedSids) {
            DebugLog.i(TAG, "scheduleReconnect sid=$sid skipped — degraded")
            return
        }
        val attempt = attemptBySid.computeIfAbsent(sid) { AtomicInteger(0) }.getAndIncrement()
        val backoff = nextBackoffMs(attempt)
        DebugLog.i(TAG, "scheduleReconnect sid=$sid attempt=$attempt backoff=${backoff}ms")
        // §B4 lifecycle capture at reconnect: snapshot the route token NOW and
        // thread it verbatim through runStream → dispatchEpochFrame (rev-gpt C2
        // — no shared field). The reconnected stream carries the token valid
        // at reconnect time (not the stale token from the original open, nor a
        // dynamic read that could race with a newer lifecycle's open).
        // §B4 rev-gpt round3 MAJOR: also publish into lifecycleRouteInstance
        // so the open() guard recognizes this reconnect's token as the in-
        // flight lifecycle's. If the route advanced during the backoff delay
        // (navigateToChat same-session re-entry), the next same-session open()
        // would otherwise supersede this reconnect — even though the reconnect
        // is already carrying the new token verbatim. Publishing here keeps the
        // guard in sync with the tracked lifecycle's actual capture.
        val reconnectRouteInstance = slices.routeInstanceFor(sid)
        lifecycleRouteInstance.set(reconnectRouteInstance)
        launchStreamLifecycle(sid, "reconnect") {
            delay(backoff)
            // Re-check after the delay: a newer open() / close() may have
            // moved on OR superseded this job (in which case the delay threw
            // CancellationException and we never reach here).
            if (currentSid.get() != sid) return@launchStreamLifecycle
            if (sid in degradedSids) return@launchStreamLifecycle
            val (epoch, gen) = beginStreamIncarnation(sid)
            try {
                runStream(sid, directory, epoch, gen, isReconnect = true, reconnectRouteInstance)
            } catch (ce: CancellationException) {
                throw ce
            }
        }
    }

    private fun nextBackoffMs(attempt: Int): Long {
        val raw = (initialBackoffMs * Math.pow(backoffMultiplier, attempt.toDouble())).toLong()
        return raw.coerceAtMost(maxBackoffMs)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Binds the resolve-time bundle to the currently executing lifecycle via
     * one CAS. A lifecycle superseded while its provider is resolving cannot
     * publish its bundle into the replacement lifecycle.
     */
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

    /**
     * Validates both lifecycle ownership and exact published-bundle identity.
     * Generation/endpoint values are derived from [StreamLifecycle.bundle]
     * (see its computed properties), never from a second mutable counter.
     */
    private suspend fun isCurrentLifecycleBundle(bundle: ClientBundle): Boolean {
        val currentJob = currentCoroutineContext()[Job] ?: return false
        val lifecycle = currentLifecycle.get() ?: return false
        return lifecycle.job === currentJob &&
            lifecycle.bundle === bundle &&
            bundle === currentBundleProvider()
    }

    private fun isBundleCurrentForCommit(bundle: ClientBundle): Boolean =
        bundle === currentBundleProvider()

    /**
     * §MF-1 (gate r1): the SINGLE launch point for every stream lifecycle job
     * (open + reconnect + 503-retry). Atomically supersedes the prior
     * [currentStreamJob] (getAndSet + cancel) so there is at most ONE tracked
     * lifecycle job at any time — closing the "orphan reconnect in delay +
     * user open → second concurrent collector" race that a bare `scope.launch`
     * left open.
     *
     * # LAZY start (critical fix for synchronous dispatchers)
     *
     * Uses [CoroutineStart.LAZY]: the Job is CREATED but NOT STARTED by
     * `scope.launch`. [currentStreamJob] is set BEFORE `job.start()` runs the
     * body. This is essential because with an unconfined dispatcher
     * (production main scope in test mode / [kotlinx.coroutines.test.UnconfinedTestDispatcher]),
     * a DEFAULT-start `scope.launch { block() }` runs `block()` **synchronously
     * inline** — and `block` may itself call `launchStreamLifecycle` (the
     * 503-retry → reconnect chain). Without LAZY, the INNER call's
     * `getAndSet(newJob)` runs FIRST (setting currentStreamJob to the retry
     * job), then control unwinds to the OUTER call whose `getAndSet` sees the
     * retry job as the "prior" and CANCELS it — silently killing the retry
     * before it ever fires. LAZY start makes the outer's getAndSet+cancel
     * happen BEFORE the body runs, so the inner call correctly sees the outer
     * as the prior and the outer self-cancels (a clean no-op: its body is
     * already returning from the catch path that triggered the retry).
     *
     * Self-cancellation note: when the block calls launchStreamLifecycle
     * (the 503 / reconnect / watchdog-timeout paths), the OUTER job
     * self-cancels. The outer's body continues executing until it returns
     * (no further suspension points matter); the outer completes as
     * Cancelled. The INNER job is the sole tracked lifecycle job.
     */
    private fun launchStreamLifecycle(sid: String, reason: String, block: suspend () -> Unit): Job? {
        synchronized(bundleCommitLock) {
        val publishedBundle = currentBundleProvider() ?: run {
            DebugLog.d(TAG, "skip lifecycle sid=$sid reason=$reason: no published bundle")
            return null
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        // Set currentStreamJob + cancel the prior BEFORE starting the body,
        // so a nested launchStreamLifecycle inside the body sees the correct
        // prior (this job) and supersedes IT, not the stale pre-outer value.
        val prior = currentLifecycle.getAndSet(StreamLifecycle(job, publishedBundle))
        // §sse-self-cancel T1.3: 冒烟枪 (smoking gun) — priorActive==true means a
        // supersede just cancelled an ACTIVE collector (the self-cancel-loop
        // fingerprint). Combined with open()'s entry `source=` tag this pinpoints
        // the driving caller (digest storm / dual-entry). On repro logcat a run of
        // `priorActive=true reason=open source=effect-load` confirms the root cause.
        DebugLog.i(TAG, "supersede prior sid=$sid priorActive=${prior?.job?.isActive} reason=$reason")
        prior?.job?.cancel(CancellationException("superseded by $reason sid=$sid"))
        // Now start the body. With an unconfined dispatcher this runs inline
        // until the first suspension (delay / collect). The lifecycle slot was
        // installed and the prior job cancelled under bundleCommitLock.
        job.start()
        return job
        }
    }

    private fun cancelCurrentStream(reason: String) {
        // Cancel the in-flight stream job but KEEP the reference: a subsequent
        // isActive read (e.g. a test or D2 lifecycle probe) observes the
        // cancelled state. The next open() overwrites this slot atomically.
        currentLifecycle.get()?.job?.cancel(CancellationException(reason))
    }

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

        // §3.10 Stage-D: 503 sse_token_subscriber_limit handling.
        internal const val MAX_CONSECUTIVE_503 = 3
        internal const val RETRY_AFTER_503_MS = 5_000L
    }
}

/**
 * partId → owning (sid, generation) tag for the bgpt MF-3 generation guard.
 * `[OwnerTag] == OwnerTag(sid, gen)` is the equality check [clearPart] uses.
 */
internal data class OwnerTag(val sid: String, val gen: Long)

/**
 * Recovery class for a token-stream flow completion. Routed by
 * [TokenStreamCoordinator.onStreamFailure] via the injected [classifyFailure].
 *
 * Public (not internal) so the public coordinator constructor parameter
 * `classifyFailure: (Throwable?) -> TokenStreamFailure` does not expose an
 * internal type (Kotlin: "public function exposes internal parameter type").
 */
sealed interface TokenStreamFailure {
    /** Server closed cleanly (no throwable); do NOT reconnect. */
    object Normal : TokenStreamFailure
    /** Transient network blip / unknown failure; reconnect with backoff. */
    object Transient : TokenStreamFailure
    /** 503 `sse_token_subscriber_limit` — bounded Retry-After, then degrade. */
    object SubscriberLimit503 : TokenStreamFailure
}

/**
 * Default failure classifier. TokenStreamClient.onFailure closes the flow with
 * `t ?: Exception("token stream failed (code=${response?.code})")`. The
 * subscriber-limit failure surfaces as either:
 *  - a throwable whose message carries `503` + a `subscriber`/`token_subscriber`
 *    marker (when the sidecar's JSON error body was threaded through), OR
 *  - the bare `"token stream failed (code=503)"` exception (when OkHttp had
 *    no throwable, only the HTTP 503 response).
 *
 * The second case is ambiguous with any other 503; the coordinator treats it
 * as SubscriberLimit503 anyway because the token-stream endpoint's only
 * advertised 503 reason IS the subscriber cap (the sidecar's
 * `sse_token_subscriber_limit` admission gate). A genuine upstream 503 would
 * not reach this SSE endpoint (the sidecar returns 502/504 for upstream
 * failures).
 */
internal fun defaultClassifyFailure(t: Throwable?): TokenStreamFailure {
    if (t == null) return TokenStreamFailure.Normal
    val msg = t.message.orEmpty()
    return when {
        msg.contains("503") &&
            (msg.contains("subscriber", ignoreCase = true) ||
                msg.contains("token_subscriber", ignoreCase = true)) ->
            TokenStreamFailure.SubscriberLimit503
        // Bare "code=503" from TokenStreamClient's fallback exception — treat
        // as subscriber-limit per the comment above.
        msg.contains("code=503") -> TokenStreamFailure.SubscriberLimit503
        else -> TokenStreamFailure.Transient
    }
}

/**
 * Watchdog timeout sentinel — thrown by the watchdog coroutine to break the
 * collector out of `flow.collect { ... }` so the run loop's catch can run the
 * [onWatchdogTimeout] recovery sequence (clear + TriggerSinceFetch + reconnect).
 */
internal class TokenStreamWatchdogTimeout(val sid: String) : Exception(
    "token stream watchdog timeout sid=$sid"
)

/**
 * §MF-1 (gate r1): mid-collect Reconnect sentinel exception. Thrown by the
 * `flow.collect { }` lambda (right after [TokenStreamCoordinator.dispatchEpochFrame])
 * when the [TokenStreamCoordinator.reconnectRequested] sentinel was set by
 * [TokenStreamCoordinator.handleEffect] processing a
 * [TokenStreamCoordinatorEffect.Reconnect] effect. Unwinds the collector so
 * the run-loop's catch path — the SINGLE re-entry point — can call
 * [TokenStreamCoordinator.scheduleReconnect] AFTER the old flow's EventSource
 * is torn down (no overlapping collectors / no double cap-8 admission).
 */
internal class TokenStreamReconnectRequested(val sid: String) : Exception(
    "token stream reconnect requested sid=$sid"
)
