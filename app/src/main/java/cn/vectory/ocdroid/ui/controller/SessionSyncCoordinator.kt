package cn.vectory.ocdroid.ui.controller

import androidx.annotation.VisibleForTesting
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.util.NOISY_SSE_LOG_EVENTS

import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.events.IdentifiedSseEvent
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.service.status.StatusFanOutSummary
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.BundleStamp
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SkeletonReloadCoordinator
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.controller.sse.DeltaFlushScheduler
import cn.vectory.ocdroid.ui.controller.sse.QuestionReconcileWorker
import cn.vectory.ocdroid.ui.controller.sse.SseDiagLogger
import cn.vectory.ocdroid.ui.controller.sse.SseDispatchHost
import cn.vectory.ocdroid.ui.controller.sse.SseEventRouter
import cn.vectory.ocdroid.ui.controller.sse.SseGapReconcileController
import cn.vectory.ocdroid.ui.controller.sse.StatusFanOutApplier
import cn.vectory.ocdroid.ui.controller.sse.SharedConversationSseHandler
import cn.vectory.ocdroid.ui.controller.sse.LegacySseHandler
import cn.vectory.ocdroid.ui.controller.sse.SlimSseHandler
import cn.vectory.ocdroid.ui.controller.sse.applyStatusViaAuthority
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * R-16 M4 → R-17 batch3b → R-17 batch5: owns the SSE event → slice fold (the
 * SSE-trust dispatch model).
 *
 * **Wave 2.1 split-l3**: extracted 5 internal state-machine modules to separate
 * files — this class is now a ~300 LOC thin host + wiring. The extracted
 * modules are:
 *  - [SseGapReconcileController] — owns sseSyncState + reconcileStripes
 *  - [DeltaFlushScheduler] — owns flushJobs (Main.immediate imprisoned)
 *  - [QuestionReconcileWorker] — latest-wins question reconcile worker
 *  - [StatusFanOutApplier] — applySlimStatusFanOutSummary + retry-queue wire
 *  - [SseDiagLogger] — verbose SSE diag coalesce + noise filter
 *
 * The Chain-of-Responsibility dispatch ([SseEventRouter.route]) is UNCHANGED.
 *
 * Full prior kdoc retained below (abridged from pre-split version).
 *
 * @see SseGapReconcileController
 * @see DeltaFlushScheduler
 * @see QuestionReconcileWorker
 * @see StatusFanOutApplier
 * @see SseDiagLogger
 *
 * ## Migration (batch 3b)
 * The [SessionSyncCoordinatorCallbacks] interface was eliminated. The
 * cross-domain signals (onServerConnected / onRefreshMessages /
 * onLoadPendingPermissions) emit [ControllerEffect]s on [effects] (rule B).
 *
 * ## Moved from the orchestrator
 * Every server-pushed message / session / status / part / permission / question /
 * todo event is folded in-place into the slice flows via `slices.mutateChat { ... }`.
 * Side effects flow through [effects] — the coordinator never touches the
 * orchestrator, the Repository, or any other controller directly.
 *
 * The coordinator holds NO streaming state of its own other than the
 * per-partId flush [DeltaFlushScheduler.flushJobs]: SSE events are stateless
 * folds over the shared slices.
 */
/** G-F1 clock override for tests. */
@Volatile
private var clockOverride: (() -> Long)? = null

@Suppress("DEPRECATION")
class SessionSyncCoordinator(
    override val scope: CoroutineScope,
    override val slices: SliceFlows,
    override val settingsManager: SettingsManager,
    override val effects: SharedEffectBus,
    internal val currentProfileId: () -> String,
    internal val identityStore: ConnectionIdentityStore? = null,
    override val statusAggregatorInput: StatusAggregatorInput? = null,
    internal val clock: () -> Long = { clockOverride?.invoke() ?: System.currentTimeMillis() },
    private val supportsWatermarkResync: () -> Boolean = { false },
    override val repository: OpenCodeRepository? = null,
    internal val skeletonReloadCoordinator: SkeletonReloadCoordinator? = null,
) : SseDispatchHost {
    // ── Extracted sub-modules (initialized as private properties) ────────────
    private val gapReconcileCtrl = SseGapReconcileController(
        scope, effects, slices, identityStore, skeletonReloadCoordinator,
    )
    private val flushScheduler = DeltaFlushScheduler(scope, slices, repository)
    private val questionWorker = QuestionReconcileWorker(
        scope, slices, settingsManager, currentProfileId,
    )
    private val statusApplier = StatusFanOutApplier(
        scope, slices, effects, currentProfileId, clock, skeletonReloadCoordinator,
    )
    private val diagLogger = SseDiagLogger(slices, cn.vectory.ocdroid.util.NOISY_SSE_LOG_EVENTS)
    /** Tag for [cn.vectory.ocdroid.ui.reportNonFatalIssue]; mirrors the original MainViewModel TAG. */
    private val tag: String = "SessionSyncCoordinator"

    /** T2: the SSE event router + domain handlers. Created at construction. */
    private val sseRouter: SseEventRouter = run {
        val shared = SharedConversationSseHandler(this)
        val legacy = LegacySseHandler(this)
        val slim = SlimSseHandler(this)
        SseEventRouter(shared, legacy, slim)
    }

    /**
     * §P0-C (B11): the [ConnectionIdentity] of the SSE event currently being
     * processed (set in [handleEvent] before dispatching to the raw event handler,
     * cleared after). null when not processing an identified event.
     *
     * @Volatile for forward-safety only (all reads/writes are confined to the
     * coordinator's single-threaded Main.immediate scope today; the annotation
     * guards visibility if a future change introduces a dispatcher hop).
     */
    @Volatile
    private var currentProcessingIdentity: ConnectionIdentity? = null

    override fun currentEventIdentity(): ConnectionIdentity? = currentProcessingIdentity

    /**
     * §R18 Phase 3 Wave 1 (P0-7): per-event-type counters for unknown SSE events.
     */
    private val unknownEventCounters = ConcurrentHashMap<String, AtomicInteger>()

    /** Test/diagnostic read: snapshot of unknown-event counts by type. */
    internal fun unknownEventCountsSnapshot(): Map<String, Int> =
        unknownEventCounters.mapValues { it.value.get() }

    // ── Identity-gated entry point ──────────────────────────────────────────

    /**
     * CP1 (notify Phase-0) identity-checked entry point. Validates
     * [IdentifiedSseEvent.identity] against [ConnectionIdentityStore.isCurrent]
     * BEFORE any fold/state mutation.
     */
    fun handleEvent(identified: IdentifiedSseEvent) {
        val store = identityStore
        if (store != null && !store.isCurrent(identified.identity)) {
            DebugLog.i(
                "Sync",
                "drop stale-identity SSE event " +
                    "(epoch=${identified.identity.epoch} current=${store.currentEpoch()} " +
                    "type=${identified.event.payload.type})"
            )
            return
        }
        currentProcessingIdentity = if (store != null) identified.identity else null
        try {
            handleEvent(identified.event)
        } finally {
            currentProcessingIdentity = null
        }
    }

    // ── SSE event dispatch ──────────────────────────────────────────────────

    /**
     * §P1-10 entry point for every SSE event. First the `server.connected`
     * catch-up trigger, then [dispatchSseEvent].
     */
    fun handleEvent(event: SSEEvent) {
        if (event.payload.type == "server.connected") {
            effects.tryEmitEffect(ControllerEffect.ServerConnected)
            val currentSessionId = slices.chat.value.currentSessionId
            val gen = gapReconcileCtrl.currentEpoch()
            val connectedOnceBefore = gapReconcileCtrl.sseSyncStateSnapshot().connectedOnce
            val decisions = gapReconcileCtrl.onServerConnected(currentSessionId, gen)
            applySseSyncDecisions(decisions)

            if (connectedOnceBefore) {
                skeletonReloadCoordinator?.let { skeleton ->
                    scope.launch {
                        val currentSid = slices.chat.value.currentSessionId
                        if (currentSid != null) skeleton.requestReload(currentSid, 200)
                    }
                }
            }
        }
        dispatchSseEvent(event)
    }

    /**
     * Dispatches a single SSE event through the Chain-of-Responsibility router.
     */
    private fun dispatchSseEvent(event: SSEEvent) {
        // §streaming-state-sync-diag (delegated to SseDiagLogger)
        diagLogger.logVerbose(event, slices.chat.value.currentSessionId)

        // Throttle dispatch logging
        val type = event.payload.type
        val evtSession = event.payload.getString("sessionID") ?: "-"
        val noisy = type in NOISY_SSE_LOG_EVENTS
        if (!noisy) {
            DebugLog.d("Sync", "dispatch $type session=$evtSession current=${slices.chat.value.currentSessionId}")
        }
        // Route through the T2 router (Chain-of-Responsibility — UNCHANGED)
        sseRouter.route(event, this)
    }

    // ── SseDispatchHost implementation ──────────────────────────────────────

    override fun profileId(): String = currentProfileId()
    override fun stripeFor(sid: String): Mutex = gapReconcileCtrl.stripeFor(sid)
    override fun scheduleDeltaFlush(partId: String) = flushScheduler.scheduleDeltaFlush(partId)
    override fun clearDeltaBuffers() = flushScheduler.clearDeltaBuffers()
    override fun applySseSideEffects(sideEffects: List<SseSideEffect>) {
        applySseSideEffectsImpl(sideEffects)
    }
    override fun bumpUnknownEventCounter(type: String) {
        unknownEventCounters
            .computeIfAbsent(type) { AtomicInteger(0) }
            .incrementAndGet()
    }
    override fun sseClock(): Long = clock()
    override fun supportsDurableSessionErrorBanner(): Boolean = supportsWatermarkResync()
    override fun isFlushActiveForPart(partId: String): Boolean =
        flushScheduler.isFlushActiveForPart(partId)
    override fun markLocallyInjected(sessionId: String, messageId: String) {
        skeletonReloadCoordinator?.markLocallyInjected(sessionId, messageId)
    }
    override fun closeSkeletonSession(sessionId: String) {
        skeletonReloadCoordinator?.let { skel ->
            scope.launch { skel.onSessionClosed(sessionId) }
        }
    }

    override fun handleSessionDigest(event: SSEEvent) {
        // lite-v2-dev (plan §4.2/§4.5): digest → skeleton reload 直连。
        val sid = event.payload.getString("sessionID") ?: return

        val props = event.payload.properties
        val status = event.payload.getString("status")
        val archived = (props?.get("archived") as? JsonPrimitive)?.longOrNull
        val deleted = (props?.get("deleted") as? JsonPrimitive)?.booleanOrNull
        val lastErrorEl = props?.get("lastError")

        // status 投影 → authority
        if (status != null) {
            val inc = (props?.get("turnIncarnation") as? JsonPrimitive)?.longOrNull
            val turn = (props?.get("turn") as? JsonPrimitive)?.longOrNull
            val round = if (inc != null && turn != null) {
                cn.vectory.ocdroid.data.state.ServerRound(inc, turn)
            } else null
            applyStatusViaAuthority(
                sid = sid,
                status = SessionStatus(type = status),
                origin = cn.vectory.ocdroid.data.state.EntryOrigin.SSE_SLIM,
                serverRound = round,
            )
        }

        // lastError 三态投影
        when (lastErrorEl) {
            is JsonObject -> {
                val name = (lastErrorEl["name"] as? JsonPrimitive)?.content
                if (name == "MessageAbortedError") { /* silently discard */ } else {
                    val message = (lastErrorEl["message"] as? JsonPrimitive)?.content
                    val at = (lastErrorEl["at"] as? JsonPrimitive)?.longOrNull
                    val banner = SlimSessionLastError(
                        name = name ?: "Unknown", message = message, at = at,
                    )
                    slices.mutateSessionList { s ->
                        s.copy(sessionErrorsById = s.sessionErrorsById + (sid to banner))
                    }
                }
            }
            is JsonNull -> {
                slices.mutateSessionList { s -> s.copy(sessionErrorsById = s.sessionErrorsById - sid) }
            }
            else -> { /* absent: 保留现有 banner */ }
        }

        // deleted / archived → 驱逐 session
        if (deleted == true || (archived != null && archived > 0L)) {
            skeletonReloadCoordinator?.let { skel ->
                scope.launch { skel.onSessionClosed(sid) }
            }
            emitCriticalEffect(ControllerEffect.EvictSession(currentProfileId(), sid))
            return
        }

        if (archived == 0L) {
            emitCriticalEffect(ControllerEffect.RefreshSessions)
        }

        // content-bearing digest → submit to unified scheduler
        val contentBearing = props?.containsKey("updatedAt") == true ||
            props?.containsKey("messageID") == true
        if (contentBearing) {
            val tuple = cn.vectory.ocdroid.ui.Tuple(
                updatedAt = (props?.get("updatedAt") as? JsonPrimitive)?.longOrNull,
                messageId = (props?.get("messageID") as? JsonPrimitive)?.content
                    ?.takeIf(String::isNotBlank),
            )
            skeletonReloadCoordinator?.submit(
                sid, tuple, cn.vectory.ocdroid.ui.Priority.DIGEST,
                if (tuple.isComplete) cn.vectory.ocdroid.ui.ReloadReason.DIGEST
                else cn.vectory.ocdroid.ui.ReloadReason.DIGEST_MALFORMED,
            )
        }
    }

    override fun dispatchBundleBound(actionFactory: (BundleStamp) -> AppAction): Boolean {
        val repo = repository ?: return false
        synchronized(repo) {
            val bundle = repo.currentClientBundle() ?: return false
            slices.store.dispatch(actionFactory(BundleStamp(bundle.generation, bundle.endpointFp)))
            return true
        }
    }

    // ── Public API (delegates to sub-modules) ───────────────────────────────

    /** §P1-10: diagnostic + test hook — snapshot of the current overlay state. */
    internal fun sseSyncStateSnapshot(): SseSyncState = gapReconcileCtrl.sseSyncStateSnapshot()

    /**
     * R-20 Phase 2 (G6): mark [sessionId] as having an established cold-snapshot
     * baseline. Called by [cn.vectory.ocdroid.ui.launchCatchUp]'s onColdSnapshot
     * callback.
     */
    internal fun markSessionColdSnapshotted(sessionId: String) {
        gapReconcileCtrl.markSessionColdSnapshotted(sessionId)
    }

    /**
     * §rev-ds: refreshes pending questions — branches on the connection's
     * capability flag: slim = single global fetch (all workdirs aggregated
     * server-side), legacy = per-dir fan-out identical to pre-P3 behavior.
     */
    fun loadPendingQuestionsAllWorkdirs(repository: OpenCodeRepository) {
        questionWorker.loadPendingQuestionsAllWorkdirs(repository)
    }

    /**
     * T13 — fold a slim on-demand fan-out summary into coordinator side effects.
     */
    fun applySlimStatusFanOutSummary(summary: StatusFanOutSummary) {
        statusApplier.applySlimStatusFanOutSummary(summary)
    }

    /** §P0-B ITEM 4: exposes the current authority state. */
    internal fun currentAuthority(): cn.vectory.ocdroid.data.state.AuthorityState =
        statusApplier.currentAuthority()

    /** §U-CQ5 sweep-start epoch reader. */
    internal fun captureStoreIdentityEpoch(): Long =
        statusApplier.captureStoreIdentityEpoch()

    // ── C2 CRITICAL (digest → skeleton reload) ──────────────────────────────

    internal fun requestDigestFullSweep(
        sessionId: String,
        @Suppress("UNUSED_PARAMETER") token: OpenCodeRepository.SlimCommitToken,
        @Suppress("UNUSED_PARAMETER") expectedRouteInstance: Long,
    ) {
        skeletonReloadCoordinator?.submit(
            sessionId, tuple = null,
            priority = cn.vectory.ocdroid.ui.Priority.DIGEST,
            reason = cn.vectory.ocdroid.ui.ReloadReason.REQUEST_RELOAD,
        )
    }

    internal fun reconcileFullAfterTransportReset(isStillCurrent: () -> Boolean) {
        val skeleton = skeletonReloadCoordinator
        if (skeleton != null) {
            scope.launch {
                if (!isStillCurrent()) return@launch
                val currentSid = slices.chat.value.currentSessionId ?: return@launch
                skeleton.requestReload(currentSid, 200)
            }
            return
        }
    }

    // ── Effect helpers ──────────────────────────────────────────────────────

    fun tryEmitEffect(effect: ControllerEffect): Boolean = effects.tryEmitEffect(effect)
    suspend fun emitEffect(effect: ControllerEffect) = effects.emitEffect(effect)
    fun tryEmitUiEvent(event: UiEvent): Boolean = effects.tryEmitUiEvent(event)
    suspend fun emitUiEvent(event: UiEvent) = effects.emitUiEvent(event)

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * §R-19 Sprint 1 Lane A (P1-10): translates [SseSyncDecision]s into
     * concrete side effects.
     */
    private fun applySseSyncDecisions(decisions: List<SseSyncDecision>) {
        if (decisions.isEmpty()) return
        for (decision in decisions) {
            when (decision) {
                is SseSyncDecision.ReloadSession -> {
                    effects.tryEmitEffect(
                        ControllerEffect.LoadMessages(
                            sessionId = decision.sessionId,
                            resetLimit = decision.resetLimit,
                            expectedRouteInstance = slices.routeInstanceFor(decision.sessionId),
                        )
                    )
                }
                SseSyncDecision.LoadSessionStatus -> {
                    effects.tryEmitEffect(ControllerEffect.LoadSessionStatus)
                }
                SseSyncDecision.RefreshSessions -> {
                    effects.tryEmitEffect(ControllerEffect.LoadSessions)
                }
                SseSyncDecision.ClearDeltaBuffers -> {
                    flushScheduler.clearDeltaBuffers()
                }
            }
        }
    }

    /**
     * §R-19 Sprint 3 P2-4: the single side-effect routing point. Translates
     * each [SseSideEffect] into its matching [ControllerEffect] emit /
     * [UiEvent] emit / log call.
     */
    private fun applySseSideEffectsImpl(sideEffects: List<SseSideEffect>) {
        if (sideEffects.isEmpty()) return
        for (effect in sideEffects) {
            when (effect) {
                is SseSideEffect.ReloadMessages -> {
                    effects.tryEmitEffect(
                        ControllerEffect.LoadMessages(
                            sessionId = effect.sessionId,
                            resetLimit = effect.resetLimit,
                            expectedRouteInstance = slices.routeInstanceFor(effect.sessionId),
                        )
                    )
                }
                SseSideEffect.LoadPendingPermissions -> {
                    effects.tryEmitEffect(ControllerEffect.LoadPendingPermissions)
                }
                is SseSideEffect.SessionError -> {
                    if (!effect.name.isNullOrBlank()) {
                        effects.tryEmitUiEvent(
                            UiEvent.Error(R.string.error_session_sse_named, listOf(effect.name, effect.rawMsg))
                        )
                    } else {
                        effects.tryEmitUiEvent(
                            UiEvent.Error(R.string.error_session_sse_unnamed, listOf(effect.rawMsg))
                        )
                    }
                }
                is SseSideEffect.ReportNonFatal -> {
                    cn.vectory.ocdroid.ui.reportNonFatalIssue(tag, effect.message)
                }
            }
        }
    }

    /**
     * §critical-eviction-delivery: best-effort reliable delivery for CRITICAL
     * effects whose loss would corrupt state.
     */
    private fun emitCriticalEffect(effect: ControllerEffect) {
        if (!effects.tryEmitEffect(effect)) {
            scope.launch { effects.emitEffect(effect) }
        }
    }

    // ── G-F1 clock seam ────────────────────────────────────────────────────

    @VisibleForTesting
    internal var resyncClockMsForTest: (() -> Long)? = null
        set(value) { field = value; clockOverride = value }

    companion object {
        /** §P0-B watchdog observability tag. */
        private const val TAG = "SessionSync"

        /**
         * C2 CRITICAL: per-session debounce window for the digest → R1
         * active sweep.
         */
        internal const val DIGEST_FULL_SWEEP_DEBOUNCE_MS = 100L
    }
}

/**
 * Clears token-stream ownership state for the given [partIds].
 * If [partIds] is empty, returns [this] unchanged.
 */
internal fun ChatState.clearTokenStreamState(partIds: Set<String>): ChatState {
    if (partIds.isEmpty()) return this
    return copy(
        streamingPartTexts = streamingPartTexts - partIds,
        streamOwned = streamOwned - partIds,
    )
}
