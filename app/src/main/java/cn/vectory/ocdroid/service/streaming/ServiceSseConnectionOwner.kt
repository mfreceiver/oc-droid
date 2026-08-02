package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.SlimapiResyncReason
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.events.IdentifiedSseEvent
import cn.vectory.ocdroid.service.events.SseEventStream
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Callback for unexpected transport drops. Implemented by
 * [ForegroundTransportDropHandler] (only surviving impl; the Service's
 * [SseShutdownSeal] died in Commit 2).
 */
interface UnexpectedTransportDropHandler {
    fun onUnexpectedDrop(attempt: TransportAttemptToken, reason: TransportDropReason)
}

/** Shared monitor/fence for owner supersession and unexpected-drop routing. */
internal interface FencedUnexpectedTransportDropHandler : UnexpectedTransportDropHandler {
    fun onUnexpectedDropIfCurrent(
        attempt: TransportAttemptToken,
        reason: TransportDropReason,
    ): Boolean
}

/**
 * CP9 (notify Phase-0 switchover): the live SSE collector that USED to live
 * inside [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator] (`sseJob`
 * + `launchSseCollection`) is now owned by the streaming Service.
 *
 * **Architecture (FGS spec §1 / §15.1)**: this class is the SOLE producer of
 * [IdentifiedSseEvent]s into the process-wide [SseEventStream]. The
 * [cn.vectory.ocdroid.service.bridge.SseEventBridge] subscribes eagerly from
 * AppCore init and routes each frame through the §2 epoch guard + the §11
 * dual-channel; AppCore re-emits each validated frame as
 * [ControllerEffect.OnSseEvent] for `SessionSyncCoordinator.fold`. The
 * downstream path is byte-for-byte preserved from CP3-8.
 *
 * **Identity contract (FGS spec §2)**: the [ConnectionIdentity] passed to
 * [connect] is the atomic capture (no re-read of `SettingsManager.currentWorkdir`).
 * A queued `StartSse` whose identity is no longer current
 * ([ConnectionIdentityStore.isCurrent] == false) is dropped before any
 * side effect — its host/workdir belongs to a reconfigure epoch that has
 * since been invalidated. Returns [SourceActivation.Rejected.StaleIdentity].
 *
 * **D2 gate #4 — acknowledgeable activation** → **D4-B M3 (transport-only
 * readiness)**: [connect] is a `suspend fun` returning [SourceActivation]:
 *  - [SourceActivation.Ready] — ONLY AFTER the first successful current-
 *    identity SSE frame proves the transport works. The readiness completes
 *    IMMEDIATELY on that frame (liveness + event publish + gap reset); it
 *    does NOT await or gate on a REST status baseline. The status authority
 *    (Busy / AllIdleFresh / Unknown) is consulted separately by the
 *    [StreamingOwnershipGate] at handoff commit — a host whose
 *    snapshot is Unknown no longer hangs the SSE activation.
 *  - [SourceActivation.Rejected.TransportTimeout] — NO valid current-identity
 *    frame arrived within [TRANSPORT_READY_TIMEOUT_MS] (30s). The attempted
 *    collector is cancelled; the handoff commit routes through
 *    [StreamingOwnershipGate.failStarting] → full B1 rollback.
 *  - [SourceActivation.Rejected.StaleIdentity] — no network retry consumed.
 *  - [SourceActivation.Rejected.Exhausted] — §5 step 6 service-level retry
 *    budget spent; [onTerminalExhaustion] invoked exactly once.
 *
 * **D2 gate #7 — terminal SSE exhaustion + gap-dirty + service-level retry**:
 * the SSEClient's internal retry budget (10 attempts) is followed by 3
 * SERVICE-LEVEL retries with delays `30s / 2m / 5m` + ±20% jitter
 * ([SseRecoveryPolicy]). The gap-dirty signal ([ControllerEffect.CancelSse])
 * is emitted IDEMPOTENTLY per outage (independent of `Job.isActive`) via
 * [emitGapOnce] — repeated failures in the same outage do NOT emit duplicate
 * gap signals. Intentional cancellation (clean shutdown, reconfigure) +
 * stale-identity termination do NOT emit gap + do NOT start recovery.
 *
 * @param scope the Service-lifetime [CoroutineScope] (Main.immediate in
 *   production; matches [ConnectionCoordinator]'s scope so command
 *   ordering + identity-check reads stay single-threaded).
 * @param repository SSE producer (FGS spec §15.1: `connectSSE(workdir)`).
 * @param identityStore the single process-level identity store (CP1).
 * @param sseEventStream the process-wide stream the collector publishes to
 *   (CP3+). The bridge + downstream fold stay unchanged.
 * @param sharedStateStore SSE liveness writes (green-icon) land on
 *   `connectionFlow` here so the icon flips green even when the connection
 *   came in via the SSE auto-reconnect (the Service has no CC reference to
 *   delegate the write to).
 * @param sharedEffectBus event-level + collection-level failures emit
 *   `UiEvent.Error(R.string.error_sse_failed, ...)` here. Liveness + errors
 *   belong at the point that proves transport delivery (the collector), NOT
 *   in the bridge — see CP9 plan §D19.
 * @param recoveryPolicy D2 gate #7: the service-level retry schedule
 *   (30s / 2m / 5m + ±20% jitter).
 * @param transportTimeoutMs D4-B M3: the bounded window for the first valid
 *   current-identity frame. Default [TRANSPORT_READY_TIMEOUT_MS] (30s). If
 *   no frame arrives within this window the activation completes with
 *   [SourceActivation.Rejected.TransportTimeout].
 * @param onTerminalExhaustion invoked once after the collector exhausts the
 *   service-level retry budget (3 attempts past the SSEClient's internal 10);
 *   triggers [ConnectionCoordinator] cold-start reconnect. Skipped on normal
 *   cancellation (clean shutdown) and on stale-identity drops.
 */
class ServiceSseConnectionOwner(
    private val scope: CoroutineScope,
    private val repository: OpenCodeRepository,
    private val identityStore: ConnectionIdentityStore,
    private val sseEventStream: SseEventStream,
    private val sharedStateStore: SharedStateStore,
    private val sharedEffectBus: SharedEffectBus,
    private val recoveryPolicy: SseRecoveryPolicy,
    private val transportTimeoutMs: Long = TRANSPORT_READY_TIMEOUT_MS,
    private val jitterSource: () -> Float = {
        kotlin.random.Random.nextFloat() * 0.4f - 0.2f
    },
    private val onTerminalExhaustion: () -> Unit,
    /**
     * L4 §3.1: gate for SSE reconnect. Called before each service-level retry
     * AND passed through to [SSEClient.connect.reconnectAllowed] for the
     * SSEClient's internal retryWhen. When false, the retry is suppressed
     * (the socket was in background grace and reconnect must not be attempted).
     * Default `{ true }` preserves existing behavior.
     */
    private val reconnectAllowed: () -> Boolean = { true },
    /**
     * Cluster A (slim SSE): cold-start / resync path. Two SEPARATE triggers
     * (B2 fix — rev-grok 🔴2):
     *
     *  1. **First-frame cold-start (P2.5)**: invoked AT MOST ONCE per
     *     transport generation on the FIRST successful current-identity
     *     frame. Gated by [resyncHandledForGen] (once-per-gen latch).
     *
     *  2. **Explicit `type=="resync"` frame (v1 §3/§4)**: invoked EVERY
     *     TIME a `resync` frame arrives mid-stream — NOT gated by the
     *     once-per-gen latch. The sidecar can emit `resync` on upstream
     *     reconnect / `resync_all` WITHOUT dropping the client SSE
     *     (hub.py ~:367-370 puts a frame onto the existing subscriber);
     *     each such frame MUST trigger a fresh cold-start pull (resync =
     *     reuse cold-start). The old Phase-2 wiring fed both triggers
     *     through ONE once-per-gen gate, which silently dropped mid-stream
     *     resyncs after the first-frame cold-start had armed the latch.
     *
     * The upper layer wires this to
     * [cn.vectory.ocdroid.data.repository.OpenCodeRepository.coldStartSlimSync]
     * — which is NOT reentrant-safe (network I/O + bookmark bump on shared
     * [slimSseState]). Concurrent calls are therefore SERIALIZED via
     * [resyncMutex] (see [scheduleResync]); multiple rapid resyncs run
     * back-to-back, never in parallel. Legacy mode (slim=false) no-ops
     * inside the callback; default `{}` keeps existing constructions
     * (including the locked test setup) byte-identical.
     *
     * Failures inside the callback are logged + swallowed — a cold-start
     * fetch failure must NOT tear down the SSE transport. The next digest /
     * q-p frame will re-drive incremental state.
     */
    private val onResync: suspend (isStillCurrent: () -> Boolean) -> Unit = { _ -> },
    /**
     * L4 §2 / §3 (M1A): the process-level transport-truth authority. The
     * owner is the ONLY producer of transport state transitions: an accepted
     * connect [SseTransportRuntimeStore.beginAttempt]s a generation-scoped
     * attempt; the first valid frame [SseTransportRuntimeStore.markLive]s it;
     * a post-Live collection failure [SseTransportRuntimeStore.markRetrying]s
     * it; unexpected terminal paths route through [dropHandler]; intentional
     * disconnect/shutdown [SseTransportRuntimeStore.markStopped]s it; a
     * recovery-attempt timeout/refusal/supersession
     * [SseTransportRuntimeStore.rollbackAttempt]s it (preserving the original
     * Dropped ticket when the attempt was a recovery, I4).
     *
     * Stale callbacks are rejected by BOTH the per-generation guard
     * ([isCurrentTransport]) AND the runtime's canonical-attempt validation
     * (a stale/foreign token returns false/null from every mutation).
     *
     * **REQUIRED** — production passes the shared `@Singleton` instance (I1:
     * a single transport-truth authority; no second runtime authority is
     * permitted). There is NO default: a construction site that omits this
     * argument does not compile, by design (the M1A/M4 compile-coupling rule).
     */
    private val runtimeStore: SseTransportRuntimeStore,
    /**
     * L4 §3 (M1A): routes every UNEXPECTED transport drop EXACTLY ONCE. The
     * implementer releases ownership BEFORE publishing the drop (I3 ordering:
     * ownership released → Dropped published). The owner NEVER calls
     * [SseTransportRuntimeStore.publishDropped] directly — it routes through
     * this handler so the observable ordering is guaranteed.
     *
     * **REQUIRED** — production
     * ([cn.vectory.ocdroid.service.SessionStreamingService]) injects a real
     * handler that releases ownership + publishes the drop. There is NO
     * default no-op handler: a construction site that omits this argument does
     * not compile, by design. Tests must inject a real (recording) handler
     * that mirrors the production publish ordering.
     */
    private val dropHandler: UnexpectedTransportDropHandler,
    private val beforeMarkRetrying: () -> Unit = {},
) {
    /**
     * The live SSE collector job, or null when no collector is running.
     * Read/written under [connectMutex].
     */
    private var sseJob: Job? = null
    private var transportTimeoutJob: Job? = null
    private var activeIdentity: ConnectionIdentity? = null

    /**
     * L4 §3 (M1A): the runtime attempt token for the CURRENT transport
     * generation, or null when no collector is active / the attempt was
     * rejected. Set in [setupConnectLocked] under [connectMutex] alongside
     * the generation bump; read by the intentional teardown paths
     * ([disconnect] / [cancelForShutdown]). The collector captures its OWN
     * token at launch (passed into [launchSseCollector]) so a stale prior-
     * generation collector never reads a newer generation's token — the
     * runtime's canonical-attempt validation is the authoritative backstop.
     */
    @Volatile
    private var activeAttempt: TransportAttemptToken? = null

    /**
     * D2 gate #4: the readiness deferred for the in-flight [connect]. The
     * `suspend fun connect` awaits this; the collector completes it on the
     * first current-identity frame (Ready), the transport timeout
     * (TransportTimeout), or [onTerminalExhaustion]'s exhausted signal.
     *
     * A SUPERSEDED connect (a newer [connect] arrived, or [disconnect] was
     * called) cancels this deferred — the older `connect` await throws
     * CancellationException + the controller's launch (which is just a
     * pass-through) propagates without acking.
     */
    private var pendingReadiness: CompletableDeferred<SourceActivation>? = null

    /**
     * D2 gate #7: monotonic transport-generation counter. Bumped on every
     * accepted [connect]; the gap-dirty emission uses it to keep
     * [gapEmittedForGen] scoped to ONE outage per generation (a successful
     * frame resets the gap flag; the next failure starts a NEW outage).
     *
     * §breathing-indicator (TOCTOU fix): SEEDED from the @Singleton store's
     * [SharedStateStore.sseConnectedGeneration] at construction. The counter is
     * per-owner-INSTANCE (it resets to 0 for a recreated Service), but the
     * monotonic CAS on [SharedStateStore.mutateSseConnected] is GLOBAL (the
     * store is process-lifetime). Without seeding, a recreated owner's
     * generations (1, 2, …) would all be LOWER than the prior owner's teardown
     * stamp, so the CAS would reject the new owner's EVERY write — including
     * its own legitimate first-frame `true` — breaking service-recreation
     * survival. Seeding continues the counter monotonically from the persisted
     * stamp, so the new owner's generations always win the CAS. Harmless to the
     * gap/exhaustion/closing/resync protocols (they only need WITHIN-owner
     * monotonicity, which the seeded counter preserves).
     */
    @Volatile
    private var transportGenerationCounter: Long = sharedStateStore.sseConnectedGeneration

    /**
     * D5 (#1) — CRITICAL post-Ready outage recovery marker. The D4-B
     * `if (readiness.isCompleted) return@launch` guard at the collector's
     * post-flow-break exit was wrong: the first valid frame completes
     * `readiness` with Ready, so ANY later flow failure / abnormal
     * completion exited SILENTLY — skipping gap-dirty / retry / 30s-2m-5m
     * recovery / terminal-exhaustion / L3 teardown / ownership release
     * (R1 violation: a post-Ready outage is a REAL outage, not a clean
     * teardown).
     *
     * The fix is an EXPLICIT per-generation closing marker, set ONLY on
     * intentional closing paths (transport timeout / disconnect /
     * supersession in setupConnectLocked / cancelForShutdown) BEFORE the
     * cancellation/invalidation. A successful frame / Ready MUST NEVER set
     * it; a new accepted generation does NOT inherit the prior marker
     * (generations are monotonic, so matching by generation suffices). The
     * collector's post-flow-break exit now checks `isClosing(generation)`
     * (NOT `readiness.isCompleted`), so a non-closing failure routes through
     * `onCollectionException` → gap → retry → exhaustion → L3 teardown as
     * R1 requires.
     */
    @Volatile
    private var closingGeneration: Long = NO_GENERATION

    private fun isClosing(generation: Long): Boolean = closingGeneration == generation

    private fun markClosing(generation: Long) {
        // Compare-and-set keeps the marker scoped to the intended generation
        // — a newer generation that already bumped past it is unaffected.
        if (transportGenerationCounter == generation) closingGeneration = generation
    }

    /**
     * §breathing-indicator (item ①): the OBSERVABLE SSE-transport-up signal.
     * `true` iff the live collector has proven transport delivery with at least
     * one valid current-identity frame AND has not since torn down.
     *
     * Exposed as the SAME [StateFlow] the [SharedStateStore] holds (not a
     * private mirror) so the flag SURVIVES service recreation: the owner is
     * service-instance-scoped + nullable, but the store is `@Singleton`, so a
     * torn-down + rebuilt [SessionStreamingService] reads/writes the SAME
     * field. The UI observes [sharedStateStore].[SharedStateStore.sseConnectedFlow]
     * process-lifetime; this property exists so callers that already hold the
     * owner (the Service shell) can read transport state off it directly.
     *
     * INDEPENDENT of [cn.vectory.ocdroid.ui.ConnectionState.isConnected]: that
     * field is HEALTH-settle (REST baseline); this field is TRANSPORT delivery
     * (a frame reached the owner). The two flip independently — a transient
     * SSE outage flips this false while health may still read connected.
     *
     * Updated ONLY through the generation-checked [setSseConnected] helper.
     */
    val isSseConnected: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = sharedStateStore.sseConnectedFlow

    /**
     * §breathing-indicator (item ①): the SINGLE write path for [isSseConnected].
     * Delegates to [SharedStateStore.mutateSseConnected], whose MONOTONIC
     * generation-stamped CAS is the AUTHORITATIVE guard: a write commits ONLY
     * IF its [generation] is `>=` the stored `sseConnectedGeneration`.
     *
     * TOCTOU fix: the pre-CAS version did `if (isCurrentTransport(generation))
     * mutateSseConnected { ... }` — the check (reads `transportGenerationCounter`)
     * and the write (commits to the store) were NON-atomic, so a stale collector
     * could pass the check and then commit `true` AFTER a concurrent
     * disconnect/reconfigure bumped the generation in between. The CAS collapses
     * check + write into ONE atomic transition, so a stale LOWER-generation
     * write loses the CAS regardless of when it races. No extra lock is needed
     * (cannot deadlock with [connectMutex] — the CAS is lock-free).
     *
     * The upstream `isCurrentTransport(identity, generation)` guards in
     * [onSuccessfulFrame] / [onCollectionException] stay as cheap early-returns
     * for the common stale-collector case; the CAS is the atomic backstop that
     * wins the races those guards cannot (the collector is single-threaded on
     * its scope dispatcher, but a teardown on another path can interleave
     * between the upstream guard and this call).
     *
     * @param value the candidate `isSseConnected` value.
     * @param generation the transport generation this write belongs to:
     *   - frame / outage / exhaustion → the collector's own generation (so a
     *     same-gen recovered frame can re-assert true after an outage);
     *   - reconfigure supersession / disconnect / shutdown → the BUMPED (new)
     *     generation (so a stale prior-gen collector loses the CAS).
     */
    private fun setSseConnected(value: Boolean, generation: Long) {
        sharedStateStore.mutateSseConnected(value, generation)
    }

    /**
     * D2 gate #7: the transport generation for which the gap-dirty signal
     * has already been emitted. `-1L` = "no gap emitted for the current
     * generation". Reset to `-1L` on every successful current-identity frame
     * (a new outage can begin) + bumped to the current generation on the
     * first failure of each outage. Repeated failures in the same outage
     * (same generation, no successful frame between them) do NOT emit a
     * duplicate gap.
     */
    private var gapEmittedForGen: Long = -1L

    /**
     * D2 gate #7: the transport generation that has been reported via
     * [onTerminalExhaustion]. Ensures the L3 callback fires EXACTLY ONCE per
     * outage (a generation is one outage; a new [connect] bumps the
     * generation + re-arms the budget).
     */
    private var exhaustedReportedForGen: Long = -1L

    /**
     * Cluster A (slim SSE): the transport generation for which the
     * FIRST-FRAME cold-start ([onResync] via P2.5) has already been
     * invoked. B2 fix (rev-grok 🔴2): this latch now gates ONLY the
     * first-frame path — it is no longer shared with the explicit
     * `resync` path (which fires every time, see [scheduleResync]).
     * A new [connect] (new generation) re-arms the flag.
     */
    private var resyncHandledForGen: Long = -1L

    /**
     * Cluster A (slim SSE, B2 fix): serializes [onResync] invocations
     * across generations + frames. [coldStartSlimSync] is NOT reentrant-
     * safe (shared bookmark state + network I/O), so concurrent triggers
     * — e.g. a stale collector's in-flight cold-start racing a newer
     * generation's first-frame cold-start, OR multiple mid-stream resync
     * frames queued while a cold-start is in flight — MUST be serialized
     * rather than dropped (rev-grok: "可加 in-flight 合并/串行，但不得
     * 静默丢"). [scheduleResync] launches each trigger on [scope] and
     * funnels them through this Mutex.
     */
    private val resyncMutex = Mutex()

    /**
     * D2 gate #7: guards [connect] (serializes the readiness deferred +
     * generation tracking + collector replacement). Without this, two
     * concurrent `scope.launch { connect(...) }` would race on `sseJob` +
     * `pendingReadiness`.
     */
    private val connectMutex = Mutex()

    /**
     * D2 gate #4 — launches one SSE collector bound to [identity], awaits
     * transport readiness OUTSIDE the [connectMutex] (so a newer [connect]
     * OR [disconnect] can supersede an in-flight await by cancelling
     * [pendingReadiness]), and returns the resulting [SourceActivation].
     *
     * **D4-B M3**: readiness completes on the FIRST valid current-identity
     * frame (transport-ready), NOT on a REST status baseline. A 30s transport
     * timeout produces [SourceActivation.Rejected.TransportTimeout].
     *
     * The setup (TOFU check, stale check, cancel prior collector, bump
     * generation, launch collector) runs under [connectMutex] so concurrent
     * connect calls are serialized on `sseJob` + `pendingReadiness`. The
     * await itself is OUTSIDE the lock.
     */
    suspend fun connect(identity: ConnectionIdentity): SourceActivation {
        val setup = connectMutex.withLock { setupConnectLocked(identity) }
        return when (setup) {
            is ConnectSetup.Rejected -> setup.activation
            is ConnectSetup.Started -> try {
                setup.readiness.await()
            } catch (e: CancellationException) {
                // The connect itself was superseded (a newer connect / a
                // disconnect cancelled this deferred). Propagate — the
                // controller's launch dies without acking.
                throw e
            }
        }
    }

    private sealed interface ConnectSetup {
        data class Started(val readiness: CompletableDeferred<SourceActivation>) : ConnectSetup
        data class Rejected(val activation: SourceActivation) : ConnectSetup
    }

    /**
     * The connect setup body — MUST be called under [connectMutex]. Performs
     * stale-identity rejection synchronously (no collector started) +
     * otherwise launches the collector + returns the [ConnectSetup.Started]
     * carrying the in-flight readiness deferred.
     */
    private fun setupConnectLocked(identity: ConnectionIdentity): ConnectSetup {
        // §A3.2 — stale-identity drop.
        if (!identityStore.isCurrent(identity)) {
            DebugLog.i(
                TAG,
                "connect: dropping stale queued StartSse " +
                    "(identity epoch=${identity.epoch} current=${identityStore.currentEpoch()})"
            )
            return ConnectSetup.Rejected(SourceActivation.Rejected.StaleIdentity)
        }
        // L4 §3 (M1A): terminalize the PRIOR runtime attempt so the new
        // generation's beginAttempt is not blocked by a non-Stopped state.
        // Supersession is an INTENTIONAL closing path (reconfigure teardown →
        // Stopped, never Dropped; I6) for a DIFFERENT identity, but a SAME-
        // identity supersession of a recovery attempt MUST preserve the
        // original Dropped ticket (I4): rollbackAttempt restores Dropped(ticket)
        // when the prior attempt carried a recoveryTicket, then beginAttempt
        // below re-captures it. A fresh same-identity attempt rolls back to
        // Stopped (no spurious Dropped). For a different identity, markStopped
        // gives a clean slate (the old ticket belongs to a stale identity and
        // must NOT block the new identity's beginAttempt).
        val attempt = synchronized(dropHandler) {
            activeAttempt?.let { prev ->
                if (prev.identity == identity) {
                    runtimeStore.rollbackAttempt(prev)
                } else {
                    runtimeStore.markStopped(prev)
                }
            }
            runtimeStore.beginAttempt(identity)
        }
        // L4 §3 (M1A): begin a runtime attempt for this generation. Captures
        // a matching Dropped ticket as recoveryTicket (recovery; §4.3); null
        // when another identity owns a non-Stopped state (cross-identity edge
        // — ownership normalization is the coordinator/supervisor's job). On
        // rejection the owner does NOT create an active collector for this
        // generation (M1A-2).
        if (attempt == null) {
            DebugLog.w(
                TAG,
                "connect: runtime beginAttempt rejected " +
                    "(identity epoch=${identity.epoch}) — no collector started",
            )
            return ConnectSetup.Rejected(SourceActivation.Rejected.TransportTimeout)
        }
        // §A3.3 — cancel + null previous collector. Cancel prior readiness
        // (the prior connect's await throws CancellationException → its
        // controller launch dies without acking).
        // D5 (#1): mark the PRIOR generation as closing BEFORE the cancel so
        // a collector whose flow breaks at this exact moment exits silently
        // (supersession is an intentional closing path, NOT a transport
        // outage). `transportGenerationCounter` is still the prior
        // generation here — the bump below allocates a fresh, non-closing
        // generation. Matching-by-generation means the new generation does
        // NOT inherit the prior marker.
        // §breathing-indicator (purge-clear defensive): a host purge (or any
        // external stamp advancement on the @Singleton store) may have pushed
        // [SharedStateStore.sseConnectedGeneration] past this owner's per-
        // instance counter (the counter is seeded once at construction; a
        // purge that did NOT go through owner-disconnect advances the store
        // stamp without bumping the counter). Re-seed to the max so the
        // supersession stamp below (counter + 1) wins the monotonic CAS even
        // after multiple purges without a reconnect in between. Runs under
        // connectMutex — no race with another of THIS owner's connects.
        if (transportGenerationCounter < sharedStateStore.sseConnectedGeneration) {
            transportGenerationCounter = sharedStateStore.sseConnectedGeneration
        }
        markClosing(transportGenerationCounter)
        // §breathing-indicator (TOCTOU fix): stamp the NEW generation
        // (`transportGenerationCounter + 1` == the post-bump value the `++`
        // below produces) so a stale PRIOR-gen collector's
        // setSseConnected(true, priorGen) loses the monotonic CAS
        // (priorGen < priorGen+1) even if it races this teardown. The CAS
        // commits this stamp atomically — no window between the check and the
        // write. (Pre-CAS this stamped the prior gen, so a racing prior-gen
        // frame could resurrect `true` after teardown — the bgpt TOCTOU.)
        setSseConnected(false, transportGenerationCounter + 1)
        sseJob?.cancel()
        sseJob = null
        transportTimeoutJob?.cancel()
        transportTimeoutJob = null
        activeIdentity = identity
        pendingReadiness?.cancel()
        val readiness = CompletableDeferred<SourceActivation>()
        pendingReadiness = readiness
        // §A3.4 — bump generation + reset per-generation gap/exhaustion flags.
        val generation = ++transportGenerationCounter
        gapEmittedForGen = -1L
        exhaustedReportedForGen = -1L
        // Cluster A: re-arm the per-generation resync flag.
        resyncHandledForGen = -1L
        // L4 §3 (M1A): associate the runtime attempt token with this
        // generation (set under connectMutex alongside the bump).
        activeAttempt = attempt
        // §A3.5 — launch one new job + the M3 transport-readiness timeout.
        sseJob = scope.launchSseCollector(identity, generation, attempt, readiness)
        transportTimeoutJob = scope.launchTransportTimeout(generation, attempt, readiness)
        return ConnectSetup.Started(readiness)
    }

    /**
     * D4-B M3 — the transport-readiness timeout. If no valid current-identity
     * frame completes [readiness] within [transportTimeoutMs], the activation
     * fails with [SourceActivation.Rejected.TransportTimeout] + the collector
     * is cancelled (the handoff commit routes through B1 rollback). Cancelled
     * by [onSuccessfulFrame] the moment a frame verifies.
     */
    private fun CoroutineScope.launchTransportTimeout(
        generation: Long,
        attempt: TransportAttemptToken,
        readiness: CompletableDeferred<SourceActivation>,
    ): Job = launch {
        try {
            delay(transportTimeoutMs)
        } catch (e: CancellationException) {
            return@launch
        }
        if (isCurrentTransport(generation) && !readiness.isCompleted) {
            DebugLog.w(TAG, "transport timeout (gen=$generation, ${transportTimeoutMs}ms, no frame)")
            // D5 (#1): mark this generation as closing BEFORE the cancel so
            // the collector's post-flow-break exit is silent (transport
            // timeout is an intentional closing path, NOT a transport
            // outage — gap/retry MUST NOT fire).
            markClosing(generation)
            // Cancel the collector so it does not keep churning after the
            // activation has been rejected.
            transportTimeoutJob = null
            sseJob?.cancel()
            sseJob = null
            // L4 §3/§4.3 (M1A): transport timeout = the attempt failed without
            // proving Live. A RECOVERY attempt (carried a recoveryTicket)
            // MUST preserve the original Dropped ticket — rollbackAttempt
            // restores Dropped(ticket) so demand survives (I4); a fresh
            // attempt rolls back to Stopped (I6, never a spurious Dropped).
            synchronized(dropHandler) {
                runtimeStore.rollbackAttempt(attempt)
            }
            readiness.complete(SourceActivation.Rejected.TransportTimeout)
        }
    }

    /**
     * D2 gate #7 — the SSE collector body, launched as [sseJob]. Runs the
     * §5 step 6 service-level retry loop: each iteration starts a fresh
     * `repository.connectSSE(workdir)` flow (which itself restarts the
     * SSEClient's internal 10-attempt budget); the first valid current-
     * identity frame completes [readiness] (Ready) and resets the retry
     * counter; after [SseRecoveryPolicy.attempts] failed iterations with no
     * frame, completes [readiness] with [SourceActivation.Rejected.Exhausted]
     * + invokes [onTerminalExhaustion] exactly once.
     *
     * **D4-B M3**: readiness completes on the first frame regardless of the
     * post-refresh status verdict — the status authority is consulted by the
     * coordinator at commit, not by the collector.
     *
     * Gap-dirty: idempotent per generation — [emitGapOnce] emits
     * [ControllerEffect.CancelSse] once per outage (reset by a successful
     * frame). Repeated failures in the same outage do not emit duplicates.
     *
     * Intentional cancellation (the job is cancelled by a newer connect /
     * disconnect / scope shutdown / transport-timeout) + stale-identity
     * termination do NOT emit gap + do NOT start recovery + do NOT invoke
     * [onTerminalExhaustion].
     */
    private fun CoroutineScope.launchSseCollector(
        identity: ConnectionIdentity,
        generation: Long,
        attempt: TransportAttemptToken,
        readiness: CompletableDeferred<SourceActivation>,
    ): Job = launch {
        val workdirArg: String? = identity.normalizedWorkdir.ifBlank { null }
        // Number of additional service-level attempts consumed in the current
        // outage. A valid frame resets the budget for a later outage.
        var retriesUsed = 0
        while (true) {
            if (!isCurrentTransport(identity, generation)) return@launch
            // L4 §3.1: check reconnect gate immediately before establishing a
            // new SSE connection (TOCTOU fix). Catches the window between the
            // pre-delay check and the actual connection attempt, including the
            // very first iteration. A denial here means the app went background.
            if (!reconnectAllowed()) {
                DebugLog.i(TAG, "SSE reconnect gate refused before connect (gen=$generation)")
                if (!readiness.isCompleted) {
                    // Pre-readiness (first iteration): the transport never
                    // proved Live — a connect-time refusal, not a drop.
                    // Complete as rejected so the caller's `connect().await()`
                    // does not hang. A RECOVERY attempt (carried a
                    // recoveryTicket) MUST preserve the original Dropped
                    // ticket — rollbackAttempt restores Dropped(ticket) so
                    // demand survives (I4); a fresh attempt rolls back to
                    // Stopped (I6, never a spurious Dropped). This writes
                    // ONLY the SSE transport projection (sseConnected=false),
                    // NOT REST/server Disconnected state (I8: SSE-only loss
                    // cannot alone write server-unreachable REST state).
                    setSseConnected(false, generation)
                    markClosing(generation)
                    synchronized(dropHandler) {
                        runtimeStore.rollbackAttempt(attempt)
                    }
                    readiness.complete(SourceActivation.Rejected.TransportTimeout)
                } else {
                    // Post-readiness (retry iteration): the transport WAS Live
                    // — a genuine post-Live background drop. Route EXACTLY
                    // ONCE through the drop handler (M1A-5).
                    handleBackgroundReconnectRefusal(identity, generation, attempt)
                }
                return@launch
            }
            // 1. Collect one flow instance. Try/catch routes BOTH thrown
            //    exceptions AND unexpected normal completion (an SSE flow
            //    should be infinite — a normal completion is a failure).
            var failure: Throwable? = null
            try {
                // L4 §3.1: set reconnect gate on the SSEClient BEFORE starting
                // the collection cycle. Not passed through [connectSSE] (which is
                // widely mocked) to avoid cascading test breakage.
                repository.setSseReconnectAllowed(reconnectAllowed)
                repository.connectSSE(workdirArg).collect { result ->
                    // §A3.7 — re-check identity BEFORE every side effect.
                    if (!isCurrentTransport(identity, generation)) {
                        DebugLog.i(
                            TAG,
                            "drop stale-identity SSE event " +
                                "(epoch=${identity.epoch} → current=${identityStore.currentEpoch()})"
                        )
                        throw CancellationException("stale SSE transport generation")
                    }
                    result.onSuccess { event ->
                        // §A3.8 — liveness + identified emit + first-frame
                        // transport readiness + gap reset (new outage can
                        // begin). D4-B M3: NO status refresh — readiness
                        // completes on transport proof alone.
                        onSuccessfulFrame(identity, generation, attempt, event, readiness)
                        retriesUsed = 0
                    }.onFailure { error ->
                        // §A3.9 — event-level failure.
                        Log_e(TAG, "SSE event failed", error)
                        sharedEffectBus.tryEmitUiEvent(
                            UiEvent.Error(
                                R.string.error_sse_failed,
                                listOf(error.message ?: "unknown error"),
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                // Cooperative cancellation (newer connect / disconnect /
                // transport-timeout / scope shutdown). Do NOT emit gap, do NOT
                // start recovery, do NOT invoke onTerminalExhaustion. Propagate.
                throw e
            } catch (e: Throwable) {
                failure = e
            }
            // 2. If we get here, the flow either threw OR completed normally.
            //    Either way, it's a collection-level failure (no infinite
            //    stream should complete). Stale-identity termination exits
            //    silently (no gap, no recovery, no exhaustion callback).
            if (!isCurrentTransport(identity, generation)) {
                return@launch
            }
            // D5 (#1) CRITICAL: do NOT infer teardown from
            // `readiness.isCompleted`. The first valid frame completes
            // `readiness` with Ready (transport proved), so a post-Ready
            // flow break would otherwise exit SILENTLY — skipping gap /
            // retry / 30s-2m-5m recovery / terminal-exhaustion / L3
            // teardown / ownership release (R1 violation). Only an EXPLICIT
            // per-generation closing marker (set by transport-timeout /
            // disconnect / supersession / shutdown) may suppress recovery.
            if (isClosing(generation)) {
                return@launch
            }
            val failureThrowable = failure
                ?: java.io.IOException("SSE flow completed without an explicit error")
            onCollectionException(identity, generation, attempt, failureThrowable)
            // 3. After handling the failure: if budget exhausted, exit.
            //    D5 (#1): a post-Ready terminal exhaustion MUST run the
            //    Disconnected write + onTerminalExhaustion() + lifecycle
            //    teardown + ownership release EVEN IF
            //    `readiness.complete(Rejected.Exhausted)` returns false
            //    (the deferred is already completed with Ready — that is
            //    harmless). The exhaustion callback is the gate, NOT the
            //    readiness completion result.
            if (retriesUsed >= recoveryPolicy.attempts) {
                if (isCurrentTransport(identity, generation) &&
                    exhaustedReportedForGen != generation
                ) {
                    // L4 §3 (M1A / fix #4): FINAL canonical-token validation
                    // BEFORE the drop. If this attempt is no longer canonical
                    // (a concurrent path terminalized it within the same
                    // generation), this is a STALE exhaustion callback — it
                    // MUST produce NO side effects and NO duplicate drop: no
                    // sseConnected write, no dropHandler invocation, no
                    // readiness completion, no terminal callback. The
                    // [exhaustedReportedForGen] guard already ensures
                    // exactly-once per generation for a LEGITIMATE exhaustion;
                    // this canonical check closes the same-generation
                    // stale-token window (a stale token is not reported, so the
                    // flag is intentionally left unset).
                    val exhaustionCanonical = runtimeStore.currentAttempt(identity)
                    if (exhaustionCanonical == null ||
                        exhaustionCanonical.attemptId != attempt.attemptId
                    ) {
                        DebugLog.i(
                            TAG,
                            "exhaustion: stale token (gen=$generation) — drop/terminal callback suppressed (no duplicate drop)",
                        )
                        return@launch
                    }
                    exhaustedReportedForGen = generation
                    // §breathing-indicator: terminal exhaustion = the transport
                    // is permanently down for this generation. Drop the SSE-up
                    // flag (a new connect / reconfigure re-arms it).
                    setSseConnected(false, generation)
                    // L4 §3/§8 (M1A / I8): SSE-only retry exhaustion MUST NOT
                    // write REST/server Disconnected state — the REST
                    // connection is a SEPARATE axis (a dropped SSE does not
                    // prove the server is unreachable). Only the SSE transport
                    // projection (sseConnected=false above) + the gap/drop
                    // demand below may update. The REST Connected/Disconnected
                    // verdict belongs to the coordinator/ownership commit, not
                    // to the SSE collector.
                    // §red-dot-trace: escalate the silent retry-budget
                    // exhaustion to WARN/ERROR so the red indicator is
                    // traceable. The per-retry attempts above only log at INFO
                    // (line ~615), which is why the red dot appeared with "no
                    // exception" in the debug log. .e when a real exception is
                    // present, else .w.
                    if (failure != null) {
                        DebugLog.e(
                            TAG,
                            "SSE retry budget exhausted retriesUsed=$retriesUsed attempts=${recoveryPolicy.attempts} (gen=$generation, epoch=${identity.epoch}) -> SSE transport down (REST unchanged)",
                            failure,
                        )
                    } else {
                        DebugLog.w(
                            TAG,
                            "SSE retry budget exhausted retriesUsed=$retriesUsed attempts=${recoveryPolicy.attempts} (gen=$generation, epoch=${identity.epoch}) -> SSE transport down (flow completed without error, REST unchanged)",
                        )
                    }
                    // L4 §3/§5 (M1A): retry budget exhausted = unexpected
                    // terminal drop. Route EXACTLY ONCE through [dropHandler]
                    // with RETRY_EXHAUSTED. (markRetrying was already applied
                    // by [onCollectionException] for the post-Live case; for a
                    // never-Live Connecting attempt publishDropped still
                    // applies — the runtime accepts it from any canonical
                    // attempt.) Normal completion and exception share this one
                    // path — no double-publish (M1A-C3).
                    if (!routeUnexpectedDrop(attempt, TransportDropReason.RETRY_EXHAUSTED)) {
                        // The outer canonical check can race a generation
                        // supersession before the fenced handler acquires its
                        // monitor. A rejected route means this callback no
                        // longer owns terminal side effects: do not rewrite
                        // readiness or invoke the disconnect callback.
                        return@launch
                    }
                    // May harmlessly return false because readiness was
                    // already completed with Ready at the first frame; the
                    // fenced drop route, not this completion, owns terminal
                    // exactly-once semantics.
                    readiness.complete(SourceActivation.Rejected.Exhausted)
                    onTerminalExhaustion()
                }
                return@launch
            }
            // 4. L4 §3.1: check the reconnect gate BEFORE each service-level retry.
            //    If gate is closed (background grace, socket dropped), skip retry
            //    and exit to trigger recovery-needed accounting.
            if (!reconnectAllowed()) {
                DebugLog.i(TAG, "SSE reconnect gate refused at service-level retry (gen=$generation)")
                handleBackgroundReconnectRefusal(identity, generation, attempt)
                return@launch
            }
            // Retry: delay per policy (with jitter) + loop.
            retriesUsed++
            val jitter = jitterSource()
            val delayMs = recoveryPolicy.delayMs(retriesUsed, jitter)
            DebugLog.i(TAG, "SSE retry attempt=$retriesUsed delay=${delayMs}ms (gen=$generation)")
            try {
                delay(delayMs)
            } catch (e: CancellationException) {
                throw e
            }
            // L4 §3.1: TOCTOU fix — re-check reconnect gate AFTER the retry
            // delay completed. The delay started while in foreground; the app
            // may have gone background during the wait. If denied, abort the
            // reconnect cleanly (no exception, no tight loop).
            if (!reconnectAllowed()) {
                DebugLog.i(
                    TAG,
                    "SSE reconnect gate refused after retry delay (gen=$generation)",
                )
                handleBackgroundReconnectRefusal(identity, generation, attempt)
                return@launch
            }
        }
    }

    /**
     * D4-B M3 — first-frame transport readiness + gap-reset side-effect
     * handler. Called for every successful current-identity frame. On the
     * FIRST such frame: publishes the event, completes [readiness] with
     * [SourceActivation.Ready], cancels the transport timeout, and resets
     * the gap flag. Does NOT perform a REST status refresh — the status
     * authority is the coordinator's concern at commit.
     *
     * D5 (#1): a recovered frame (post-Ready, after a retry cycle) resets
     * `retriesUsed=0` (the caller's local) + [gapEmittedForGen]=-1L so a
     * new outage can begin. It does NOT recreate the transport timeout
     * (the one-time transport timeout exists ONLY before the first valid
     * frame; post-Ready outages use ONLY [SseRecoveryPolicy]) and does NOT
     * emit a second activation ack (the readiness deferred is already
     * complete with Ready — `!readiness.isCompleted` guards the ack path).
     *
     * D5 (#3): the `sharedStateStore.mutateConnection { ... Connected }`
     * write is REMOVED — a frame proves liveness via event publication +
     * activation readiness + gap reset, but ONLY a committed ownership /
     * ConnectionCoordinator may publish terminal Connected. Transient
     * post-Ready outages need not immediately mark Disconnected either
     * (final exhaustion already does).
     *
     * Resetting [gapEmittedForGen] = -1 means the next failure starts a NEW
     * outage (a new gap emission). This is the §1.1 "observed transport-
     * disconnect signal" semantics: a gap is per-outage, not per-failure.
     */
    private suspend fun onSuccessfulFrame(
        identity: ConnectionIdentity,
        generation: Long,
        attempt: TransportAttemptToken,
        event: cn.vectory.ocdroid.data.model.SSEEvent,
        readiness: CompletableDeferred<SourceActivation>,
    ) {
        if (!isCurrentTransport(identity, generation)) return
        // L4 §3 (M1A): the runtime is the AUTHORITATIVE transport-truth. A
        // stale/foreign token is rejected by markLive (canonical-attempt
        // validation). When markLive returns false the attempt is no longer
        // canonical — EVERY frame side effect MUST be suppressed: no event
        // publish, no sseConnected, no resync, no Ready completion, no gap
        // reset (fix #4). The per-generation guard above is the cheap early-
        // return; this is the authoritative runtime-token backstop.
        if (!runtimeStore.markLive(attempt)) {
            DebugLog.i(
                TAG,
                "markLive rejected stale token (gen=$generation) — frame side effects suppressed",
            )
            return
        }
        // §breathing-indicator: a valid current-identity frame proves transport
        // delivery → SSE is connected. Set on EVERY such frame (first-frame
        // readiness AND a post-Ready recovered frame after a retry gap) so the
        // breathing resumes the instant the stream recovers. Idempotent +
        // generation-checked inside [setSseConnected].
        setSseConnected(true, generation)
        // Publish into the process-wide stream — the bridge (subscribed
        // eagerly from AppCore) routes through the §2 epoch guard + §11
        // dual-channel + re-emits each validated frame as OnSseEvent for
        // SSC's fold. D5 (#3): a frame proves liveness via event
        // publication + activation readiness + gap reset; it does NOT
        // publish terminal Connected (only committed ownership / CC may).
        sseEventStream.emit(Result.success(IdentifiedSseEvent(identity, event)))
        // Cluster A (slim SSE) P2.4 + P2.5 + B2 fix (rev-grok 🔴2): two
        // SEPARATE triggers, NOT one shared once-per-gen gate.
        //  - First successful frame of the generation → cold-start ONCE
        //    per generation (gated by resyncHandledForGen — gives the UI
        //    an initial snapshot before any digest lands).
        //  - Explicit `type=="resync"` frame → cold-start EVERY TIME,
        //    independent of the once-per-gen latch. The sidecar emits
        //    `resync` mid-stream on upstream reconnect / `resync_all`
        //    WITHOUT dropping the client SSE (v1 §3/§4: resync = reuse
        //    cold-start); the old Phase-2 code fed both triggers through
        //    ONE gate, so the first-frame cold-start armed the latch and
        //    every subsequent resync was silently dropped (incremental /
        //    snapshot recovery path broken). Branches are mutually
        //    exclusive so a first-frame that IS `type=="resync"` fires
        //    exactly once (via the first-frame gate).
        // The callback is launched off-frame via [scheduleResync] so SSE
        // delivery is NOT blocked on the cold-start fetch, and concurrent
        // triggers are serialized via [resyncMutex].
        val type = event.payload.type
        val isFirstFrameOfGen = !readiness.isCompleted
        val isResync = type == "resync"
        // lite-v2: server.reconfigured handler removed (no runtime reconfigure).
        val serverReasonRaw: String? =
            if (isResync) event.payload.getString("reason") else null
        val serverReasonTyped: SlimapiResyncReason? =
            if (isResync) SlimapiResyncReason.fromRaw(serverReasonRaw) else null
        if (isResync) {
            DebugLog.i(
                TAG,
                "slim resync reason: raw=$serverReasonRaw typed=$serverReasonTyped gen=$generation",
            )
        }
        if (isFirstFrameOfGen && resyncHandledForGen != generation) {
            resyncHandledForGen = generation
            // First-frame cold-start. If this first frame IS itself a
            // resync, include the parsed server reason in the label (T10).
            // The scheduleResync signature is UNCHANGED — only the STRING
            // label is enriched for log attribution.
            val firstFrameReason = if (isResync) {
                "first-frame type=$type server-reason=$serverReasonRaw"
            } else {
                "first-frame type=$type"
            }
            scheduleResync(firstFrameReason, generation)
        } else if (isResync) {
            // Mid-stream (or skipped-first-frame) explicit resync: NOT
            // gated. rev-grok 🔴2 — this is the core regression fix.
            // T10: enrich the owner's internal label with the server's
            // reason (raw + typed) for log attribution. Signature UNCHANGED.
            scheduleResync(
                "explicit-resync server-reason=$serverReasonRaw typed=$serverReasonTyped",
                generation,
            )
        }
        // D4-B M3: transport readiness completes on the first frame — NO
        // status refresh, NO baseline gating. Cancel the transport timeout
        // (the transport proved itself). D5 (#1): post-Ready recovered
        // frames take the `!readiness.isCompleted` false branch — no second
        // ack, no transport-timeout recreation.
        if (!readiness.isCompleted) {
            transportTimeoutJob?.cancel()
            transportTimeoutJob = null
            readiness.complete(SourceActivation.Ready)
        }
        // Reset the per-generation gap flag — a new outage can begin.
        // D5 (#1): this also applies to a post-Ready recovered frame, so
        // a subsequent outage emits a fresh gap (NOT a duplicate of the
        // pre-recovery one).
        gapEmittedForGen = -1L
    }

    /**
     * D2 gate #7 — collection-level exception handler. Emits the SSE error
     * UI event + the IDEMPOTENT gap-dirty signal (once per outage per
     * generation). The retry / exhaustion decision is made by the caller
     * ([launchSseCollector]'s loop).
     *
     * L4 §3 (M1A / fix #4): a CANONICAL-TOKEN validation gates ALL subsequent
     * error/gap/drop side effects. The runtime is the AUTHORITATIVE
     * transport-truth: if THIS attempt is no longer the runtime's canonical
     * attempt (a concurrent path terminalized it within the same generation —
     * e.g. [SseTransportRuntimeStore.publishDropped] / [markStopped] / a
     * superseding [beginAttempt]), this is a STALE callback and MUST produce
     * NO side effects (no UI error, no gap, no drop). This distinguishes a
     * legitimate current Connecting/Live/Retrying failure (canonical matches
     * → emit gap/UI) from a stale token (canonical null or differs → suppress)
     * — a distinction [markRetrying]'s boolean alone CANNOT make, since it
     * returns false for both "stale token" and "never-Live Connecting".
     */
    private suspend fun onCollectionException(
        identity: ConnectionIdentity,
        generation: Long,
        attempt: TransportAttemptToken,
        error: Throwable,
    ) {
        Log_e(TAG, "SSE collection failed (gen=$generation)", error)
        // Generation guard: a stale collector (reconfigure/disconnect/supersession
        // bumped the generation) emits NEITHER errors NOR a disconnect transition.
        if (!isCurrentTransport(identity, generation)) {
            return
        }
        // Canonical-token validation (fix #4): if THIS attempt is no longer the
        // runtime's canonical attempt for this identity, the token was
        // terminalized by a concurrent path within the same generation. Suppress
        // ALL error/gap/drop side effects — no UI error, no gap, no drop, no
        // duplicate. (currentAttempt returns null for Stopped/Dropped and a
        // foreign/different attemptId for a superseded runtime.)
        val canonical = runtimeStore.currentAttempt(identity)
        if (canonical == null || canonical.attemptId != attempt.attemptId) {
            DebugLog.i(
                TAG,
                "onCollectionException: stale token (gen=$generation) — error/gap/drop side effects suppressed",
            )
            return
        }
        beforeMarkRetrying()
        // markRetrying: Live → Retrying (idempotent no-op for an already-Retrying
        // or a never-Live Connecting attempt — both are legitimate current
        // failures that passed the canonical check above). Uses the canonical
        // attempt, so a token that went stale between the check and this call
        // cannot resurrect state.
        val transitionedToRetrying = synchronized(dropHandler) {
            runtimeStore.markRetrying(attempt)
        }
        if (!transitionedToRetrying) {
            val stillCanonical = runtimeStore.currentAttempt(identity)
            val stillConnecting = runtimeStore.state.value is SseTransportState.Connecting
            if (stillCanonical?.attemptId != attempt.attemptId || !stillConnecting) {
                DebugLog.i(TAG, "onCollectionException: markRetrying fence rejected stale token")
                return
            }
        }
        // §breathing-indicator: a collection-level failure is a transport gap
        // (the stream broke). Drop the SSE-up flag so the breathing stops
        // during the inter-retry gap — the UI must not lie that the stream is
        // alive while a retry is pending. A recovered frame re-asserts true.
        setSseConnected(false, generation)
        sharedEffectBus.tryEmitUiEvent(
            UiEvent.Error(
                R.string.error_sse_failed,
                listOf(error.message ?: "unknown error"),
            )
        )
        // D2 gate #7: IDEMPOTENT gap-dirty emission. Independent of
        // Job.isActive. Uses the suspend effect-emission path (not silently
        // dropped).
        emitGapOnce(identity, generation)
    }

    /**
     * D2 gate #7 — emits the §1.1 gap-dirty signal ([ControllerEffect.CancelSse])
     * IDEMPOTENTLY per outage (per generation). Repeated failures in the same
     * outage (same generation, no successful frame between them) do NOT emit
     * duplicates.
     *
     * Per-generation idempotence: [gapEmittedForGen] tracks which generation
     * has already been gap-emitted; a successful frame resets it (`-1L`) so
     * a NEW outage (later failure in the same generation) emits a fresh gap.
     */
    private suspend fun emitGapOnce(identity: ConnectionIdentity, generation: Long) {
        // Stale-identity: do NOT emit gap (the §4.4 teardown of a stale
        // identity is not a transport disconnect).
        if (!isCurrentTransport(identity, generation)) return
        if (gapEmittedForGen == generation) return
        gapEmittedForGen = generation
        sharedEffectBus.emitEffect(ControllerEffect.CancelSse)
    }

    /**
     * L4 §3/§7 (M1A): post-Live background reconnect refusal. The collector
     * broke while the app was in background (the reconnect gate is closed — a
     * reconnect is NOT attempted in background per the receive-only decision).
     *
     * M1A-5: the owner:
     *  1. writes the SSE transport projection (sseConnected=false — the SSE
     *     stream is down); it MUST NOT write REST/server Disconnected state
     *     (I8: SSE-only loss cannot alone write server-unreachable state);
     *  2. emits the idempotent gap-dirty signal (delta-buffer clear — unchanged);
     *  3. transitions the runtime Live → Retrying (authoritative — a stale
     *     token rejects, suppressing the drop);
     *  4. routes the drop EXACTLY ONCE through [dropHandler] with
     *     [TransportDropReason.BACKGROUND_RECONNECT_REFUSED].
     *
     * The drop handler releases ownership BEFORE publishing Dropped (I3). The
     * owner never calls [SseTransportRuntimeStore.publishDropped] directly.
     */
    private suspend fun handleBackgroundReconnectRefusal(
        identity: ConnectionIdentity,
        generation: Long,
        attempt: TransportAttemptToken,
    ) {
        if (!isCurrentTransport(identity, generation)) return
        // L4 §3/§8 (M1A / I8): write ONLY the SSE transport projection. The
        // REST/server connection is a SEPARATE axis — an SSE-only background
        // refusal MUST NOT write Disconnected (only committed ownership / CC
        // may publish terminal REST state).
        setSseConnected(false, generation)
        // The gap-dirty signal (downstream delta-buffer clear) is preserved.
        emitGapOnce(identity, generation)
        // L4 §3 (M1A / fix #4): the runtime is AUTHORITATIVE transport-truth.
        // By the time this refusal path runs, [onCollectionException] has
        // ALREADY transitioned the attempt Live → Retrying (the flow broke
        // before the gate was checked). A redundant markRetrying would return
        // false (Retrying is not Live) and incorrectly look like a stale-token
        // rejection. Instead, query the canonical attempt: if THIS attempt is
        // still canonical (Connecting/Live/Retrying for its identity), the drop
        // is routed; if a newer attempt superseded it (stale token), the drop is
        // suppressed (no double-drop, no drop after supersession). The handler's
        // own publishDropped is the linearizable authoritative backstop.
        val canonical = runtimeStore.currentAttempt(attempt.identity)
        if (canonical?.attemptId != attempt.attemptId) {
            DebugLog.i(
                TAG,
                "background refusal: attempt no longer canonical (gen=$generation) — drop suppressed",
            )
            return
        }
        routeUnexpectedDrop(attempt, TransportDropReason.BACKGROUND_RECONNECT_REFUSED)
        DebugLog.i(TAG, "SSE reconnect gate closed — routed background drop (gen=$generation)")
    }

    private fun routeUnexpectedDrop(
        attempt: TransportAttemptToken,
        reason: TransportDropReason,
    ): Boolean = when (val handler = dropHandler) {
        is FencedUnexpectedTransportDropHandler ->
            handler.onUnexpectedDropIfCurrent(attempt, reason)
        else -> {
            handler.onUnexpectedDrop(attempt, reason)
            true
        }
    }

    /**
     * Cluster A / Phase 2 (slim SSE, rev-G 🔴2 / rev-F 🔴1): launches ONE
     * [onResync] cold-start invocation on [scope], SERIALIZED via
     * [resyncMutex]. Supports coalescing of cold-start triggers that land
     * while a cold-start is already in-flight for the same generation.
     *
     * Off-frame execution: SSE delivery does NOT block on the cold-start
     * fetch (the old inline `onResync()` call inside the collector's frame
     * handler stalled SSE while the snapshot was being pulled). The
     * callback now runs in a child of [scope] (Service-lifetime), so the
     * collector returns to consuming frames immediately.
     *
     * Serialization: [coldStartSlimSync] is not reentrant-safe (shared
     * bookmark state + network I/O), so concurrent triggers — a stale
     * collector's in-flight cold-start racing a newer generation's first-
     * frame cold-start, OR multiple mid-stream `resync` frames queued
     * behind an in-flight one — are FUNNELED through [resyncMutex]. The
     * second waits for the first to complete, then runs. This satisfies
     * rev-grok's "可加 in-flight 合并/串行，但不得静默丢": no trigger is
     * ever dropped (every resync fires its own cold-start), and no two
     * cold-starts ever overlap.
     *
     * Stale-generation guard (🟠2 fix — rev-grok 9.5): the Mutex prevents
     * CONCURRENT execution but NOT stale execution. On a fast reconnect /
     * host switch, a queued resync cold-start (gen N) can win the Mutex
     * AFTER [setupConnectLocked] has bumped [transportGenerationCounter]
     * to N+1 + established a new live slice. Running gen N's cold-start
     * in that window would fold gen N's snapshot into gen N+1's live
     * slice (stale apply). The [isCurrentTransport] guard INSIDE the
     * Mutex (after acquiring — TOCTOU-safe) drops the stale trigger
     * silently: the new generation has already armed its own first-frame
     * cold-start, so dropping gen N's queued trigger loses nothing.
     *
     * Failures inside the callback are logged + swallowed (a cold-start
     * fetch failure must NOT tear down the SSE transport — the next
     * digest / q-p frame re-drives incremental state). Cancellation is
     * propagated so a scope shutdown cleans up in-flight cold-starts.
     *
     * **rev-F 🔴1 — connect-establish coalescing**: if a cold-start
     * trigger for this generation is already in-flight (the mutex is
     * held, or queued behind the mutex), we set a dirty flag and return
     * instead of spawning a parallel unbounded launch. After the
     * in-flight `onResync` completes (still under the mutex), we check
     * the dirty flag: if set for this generation, clear it and run one
     * more `onResync` pass. This prevents double cold-start from a
     * rapid `server.connected` + `resync` pair on connect
     * establishment.
     *
     * Dirty flags are only set for **cold-start triggers** (first-frame
     * of generation, and establish-window `resync`). Mid-stream
     * `resync` and `server.reconfigured` AFTER the first cold-start
     * has completed for a generation always fire normally (no dirty gating).
     */
    @Volatile
    private var resyncDirtyForGen: Long = -1L

    /** rev-F 🔴1: marks that a cold-start is in flight FOR this generation.
     *  Set BEFORE the launch, cleared inside the mutex after the first
     *  `onResync` completes. A second trigger sees this and sets
     *  [resyncDirtyForGen] instead of launching.
     */
    @Volatile
    private var resyncInFlightForGen: Long = -1L

    private fun isColdStartTrigger(reason: String): Boolean =
        reason.startsWith("first-frame") || reason.startsWith("explicit-resync")

    private fun scheduleResync(reason: String, generation: Long) {
        val isColdStart = isColdStartTrigger(reason)
        if (isColdStart) {
            // rev-F 🔴1: if a cold-start for this gen is already in-flight
            // or queued, set dirty and return — do not spawn a new one.
            if (resyncInFlightForGen == generation) {
                // A cold-start is already in-flight; mark dirty for coalesce.
                resyncDirtyForGen = generation
                DebugLog.d(
                    TAG,
                    "slim cold-start/resync coalesce: already in-flight " +
                        "$reason gen=$generation — set dirty, skip duplicate",
                )
                return
            }
            if (resyncDirtyForGen == generation) {
                // There is a coalesce-scheduled extra run already pending;
                // No need for another dirty or another launch.
                DebugLog.d(
                    TAG,
                    "slim cold-start/resync coalesce: already dirty/pending " +
                        "$reason gen=$generation — skip duplicate",
                )
                return
            }
            // Mark as in-flight BEFORE launch so a second trigger can
            // detect concurrency and set dirty instead of spawning another.
            resyncInFlightForGen = generation
        }
        scope.launch {
            resyncMutex.withLock {
                // 🟠2 fix (rev-grok 9.5): re-check generation AFTER acquiring
                // the Mutex, not before scheduling. Without this guard, a
                // queued gen-N trigger that wins the Mutex after a fast
                // reconnect to gen-N+1 would apply gen N's stale snapshot
                // to the live slice. The guard must live INSIDE the locked
                // region to be TOCTOU-safe (transportGenerationCounter is
                // bumped under connectMutex in setupConnectLocked, so a
                // pre-lock check could race with the bump).
                if (!isCurrentTransport(generation)) {
                    DebugLog.i(
                        TAG,
                        "skip stale cold-start/resync $reason " +
                            "gen=$generation (current=$transportGenerationCounter) — " +
                            "superseded by newer generation",
                    )
                    // Clear in-flight/dirty state for the stale gen.
                    if (isColdStart) {
                        if (resyncInFlightForGen == generation) {
                            resyncInFlightForGen = -1L
                        }
                        if (resyncDirtyForGen == generation) {
                            resyncDirtyForGen = -1L
                        }
                    }
                    return@withLock
                }
                // Clear the in-flight flag (the dirty flag may be set by a
                // concurrent second trigger that saw inFlight==generation).
                if (isColdStart && resyncInFlightForGen == generation) {
                    resyncInFlightForGen = -1L
                }
                DebugLog.i(
                    TAG,
                    "slim cold-start/resync fire $reason gen=$generation",
                )
                try {
                    onResync { isCurrentTransport(generation) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    DebugLog.w(
                        TAG,
                        "slim cold-start/resync refetch failed: ${e.message}",
                    )
                }
                // rev-F 🔴1: after in-flight onResync completes, check dirty
                // for this generation. If dirty, clear and run ONE MORE pull
                // (without setting dirty again — the extra run is a successor
                // to the current one, not a duplicate).
                if (isColdStart && resyncDirtyForGen == generation) {
                    resyncDirtyForGen = -1L
                    DebugLog.d(
                        TAG,
                        "slim cold-start/resync coalesce: extra run for dirty " +
                            "gen=$generation",
                    )
                    // Re-check generation (may have been bumped by setupConnectLocked
                    // while we were inside onResync — though that would set a new
                    // dirty for the new gen, and this stale-gen extra run is safe
                    // to skip via the existing stale-gen guard below).
                    if (isCurrentTransport(generation)) {
                        onResync { isCurrentTransport(generation) }
                    }
                }
            }
        }
    }
    /**
     * D2 gate #7 — cancels the in-flight collector + invokes the SAME
     * idempotent [emitGapOnce] path the terminal-collection-exception branch
     * uses (when [markGap] is true). This closes the "job.isActive as the gap
     * predicate" bug: the gap signal is now independent of whether the job
     * was live at disconnect time.
     *
     * Idempotent: a second call with no live collector emits nothing (the
     * generation tracking prevents duplicate gap emissions across repeated
     * disconnect calls in the same outage).
     *
     * Cancels [pendingReadiness] so a suspended [connect] await throws
     * CancellationException (the controller's launch dies without acking).
     */
    suspend fun disconnect(markGap: Boolean = true) = disconnectWithGuard(markGap) { true }

    /**
     * §sse-zombie-fix (v3): guarded disconnect. [guard] is evaluated UNDER
     * [connectMutex], so guard-check + collector cancellation are ATOMIC w.r.t.
     * any concurrent [connect] setup (which runs under the same mutex, :420).
     * A stale teardown whose guard observes a newer attempt is a strict no-op;
     * a teardown that wins the mutex before the newer attempt's setup completes
     * runs fully, and the newer [setupConnectLocked] then starts fresh on clean
     * state. No window exists in which the disconnect kills the new generation.
     *
     * Returns true iff the disconnect actually ran.
     */
    suspend fun disconnectWithGuard(markGap: Boolean, guard: () -> Boolean): Boolean {
        var ran = false
        val job = connectMutex.withLock {
            if (!guard()) return@withLock null
            ran = true
            disconnectLocked(markGap)
        }
        job?.cancelAndJoin()
        return ran
    }

    /**
     * §sse-zombie-fix (v3): the mutex-held body of [disconnect] / [disconnectWithGuard].
     * Cancels collector + readiness, emits gap, marks attempt stopped, bumps
     * generation. Returns the cancelled SSE job so the caller can join it
     * OUTSIDE the mutex (cancelAndJoin suspends — never under connectMutex).
     *
     * §fix: marked `suspend` because [emitGapOnce] (invoked when markGap=true)
     * is itself a suspend function (it writes to a MutableSharedFlow). The
     * enclosing [disconnectWithGuard] already runs this under [connectMutex]
     * via `withLock` (a suspend block), so the suspension propagates correctly.
     */
    private suspend fun disconnectLocked(markGap: Boolean): Job? {
        // D5 (#1): mark the current generation as closing BEFORE the
        // cancel so the collector's post-flow-break exit is silent
        // (disconnect is an intentional closing path — gap is emitted
        // explicitly below via emitGapOnce when markGap=true, NOT via
        // the collector's outage path).
        val closingGen = transportGenerationCounter
        markClosing(closingGen)
        // §breathing-indicator (TOCTOU fix): stamp the NEW generation
        // (`closingGen + 1` == the post-bump value `transportGenerationCounter
        // += 1` produces below) so a stale gen-closingGen collector's
        // setSseConnected(true, closingGen) loses the monotonic CAS and
        // cannot resurrect `true` after an intentional disconnect. The CAS
        // commits atomically — no check-then-write window.
        setSseConnected(false, closingGen + 1)
        val job = sseJob
        sseJob = null
        transportTimeoutJob?.cancel()
        transportTimeoutJob = null
        val readiness = pendingReadiness
        pendingReadiness = null
        val generation = transportGenerationCounter
        job?.cancel()
        readiness?.cancel()
        val identity = activeIdentity
        if (markGap && identity != null) {
            emitGapOnce(identity = identity, generation = generation)
        }
        transportGenerationCounter += 1
        // L4 §3 (M1A): intentional disconnect → markStopped (never Dropped).
        // Idempotent: a no-op if the attempt already dropped (no revive; I6).
        synchronized(dropHandler) {
            activeAttempt?.let { runtimeStore.markStopped(it) }
        }
        activeAttempt = null
        activeIdentity = null
        return job
    }

    suspend fun disconnectAndJoin(markGap: Boolean = true) = disconnect(markGap)

    /** Synchronous Service-destruction fallback; normal L3 teardown already joined. */
    fun cancelForShutdown() {
        // D5 (#1): mark closing BEFORE the cancel so a collector whose flow
        // is breaking at this exact moment exits silently (shutdown is an
        // intentional closing path — no false transport-outage signal).
        val shuttingDownGen = transportGenerationCounter
        markClosing(shuttingDownGen)
        // §breathing-indicator (TOCTOU fix): stamp the NEW generation
        // (`shuttingDownGen + 1` == the post-bump value below) so a stale
        // gen-shuttingDownGen collector loses the monotonic CAS and cannot
        // resurrect `true` after service destruction. The store survives (it is
        // @Singleton) and the UI observes the drop process-lifetime.
        setSseConnected(false, shuttingDownGen + 1)
        transportGenerationCounter = shuttingDownGen + 1
        // L4 §3 (M1A): intentional shutdown → markStopped (never Dropped).
        // Idempotent: a no-op if the attempt already dropped (no revive; I6).
        synchronized(dropHandler) {
            activeAttempt?.let { runtimeStore.markStopped(it) }
        }
        activeAttempt = null
        sseJob?.cancel()
        sseJob = null
        transportTimeoutJob?.cancel()
        transportTimeoutJob = null
        pendingReadiness?.cancel()
        pendingReadiness = null
        activeIdentity = null
    }

    private fun isCurrentTransport(identity: ConnectionIdentity, generation: Long): Boolean =
        generation == transportGenerationCounter && identityStore.isCurrent(identity)

    private fun isCurrentTransport(generation: Long): Boolean =
        generation == transportGenerationCounter

    private companion object {
        private const val TAG = "ServiceSseOwner"

        /**
         * D5 (#1): sentinel for "no generation is closing". Generations are
         * monotonic starting from 0 (the first accepted connect bumps
         * `transportGenerationCounter` to 1), so `-1L` cannot collide with
         * any real generation.
         */
        private const val NO_GENERATION: Long = -1L

        /**
         * D4-B M3: the bounded window (30s) for the first valid current-
         * identity SSE frame. If no frame arrives within this window the
         * activation fails with [SourceActivation.Rejected.TransportTimeout]
         * and the handoff routes through B1 rollback. Generous vs. a LAN
         * server's first-frame latency; tight enough that a dead endpoint
         * does not stall the bootstrap.
         */
        const val TRANSPORT_READY_TIMEOUT_MS = 30_000L

        // Indirection so tests do not need to mockkStatic(Log). Production
        // routes through android.util.Log; tests see a no-op.
        private fun Log_e(tag: String, msg: String, throwable: Throwable) {
            try {
                android.util.Log.e(tag, msg, throwable)
            } catch (_: Throwable) {
                // unit tests run without android.util.Log; the message is
                // still useful via DebugLog if a spy is attached.
            }
        }
    }
}
