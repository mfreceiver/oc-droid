package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.BuildConfig
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.api.NOISY_SSE_LOG_EVENTS
import cn.vectory.ocdroid.data.cache.CacheRepository
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.events.IdentifiedSseEvent
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.status.SessionBusyStatus
import cn.vectory.ocdroid.service.status.SessionStatusKey
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.service.status.toSessionBusyStatus
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.UnreadState
import cn.vectory.ocdroid.ui.computeQuestionFanOutWorkdirs
import cn.vectory.ocdroid.ui.lenientJson
import cn.vectory.ocdroid.ui.parseMessagePartDeltaEvent
import cn.vectory.ocdroid.ui.parseQuestionAskedEvent
import cn.vectory.ocdroid.ui.parseSessionCreatedEvent
import cn.vectory.ocdroid.ui.parseSessionStatusEvent
import cn.vectory.ocdroid.ui.parseSessionUpdatedEvent
import cn.vectory.ocdroid.ui.reasoningPartOrNull
import cn.vectory.ocdroid.ui.reportNonFatalIssue
import cn.vectory.ocdroid.ui.isStreamablePartType
import cn.vectory.ocdroid.ui.upsertSession
import cn.vectory.ocdroid.ui.withUpdatedAtLeast
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.FLICKER_TAG
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.STREAMING_FLICKER_DEBUG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * R-16 M4 → R-17 batch3b → R-17 batch5: owns the SSE event → slice fold (the
 * SSE-trust dispatch model).
 *
 * **Migration (batch 3b)**: the [SessionSyncCoordinatorCallbacks] interface
 * was eliminated. The cross-domain signals (onServerConnected /
 * onRefreshMessages / onLoadPendingPermissions) emit [ControllerEffect]s on
 * [effects] (rule B). The non-fatal-issue logger was same-domain
 * ([cn.vectory.ocdroid.ui.reportNonFatalIssue] top-level helper) — inlined.
 *
 * **Moved from the orchestrator** (`handleSSEEvent` + the
 * `handleIncomingSseEvent` / `markSessionUnread` free functions): every
 * server-pushed message / session / status / part / permission / question /
 * todo event is folded in-place into the slice flows via
 * `slices.mutateChat { ... }` (patch-if-found + insert-if-absent for messages;
 * upsert for sessions; in-place map updates for statuses/todos/questions;
 * streaming overlay for parts). The side effects a fold can trigger
 * (authoritative reload, permission refresh, catch-up) flow through
 * [effects] — so the coordinator never touches the orchestrator, the
 * Repository, or any other controller directly (R-16 §7.3
 * circular-dependency avoidance).
 *
 * §R-17 batch5 (SSE semi-formalization): the per-partId delta coalescing
 * hidden state machine (`deltaBuffer` / `fullTextBuffer` / `pendingFlushPartIds`)
 * has been migrated INTO [ChatState] (immutable Map/Set, CAS updates). Only
 * the coroutine `Job` references ([flushJobs]) remain on the coordinator
 * (a Job is neither serializable nor a value type — it is bound to the
 * coordinator's [scope]). Each of the 11 event branches now calls a pure
 * `applyXxx(...)` extension function that takes the prior slice + event
 * payload and returns the new slice value; side effects (effect emits,
 * settingsManager writes, scope.launch) stay inline in the `when` branches
 * (effect-channel migration is a tracked followup — not in this batch).
 *
 * The coordinator holds NO streaming state of its own other than the
 * per-partId flush [flushJobs]: SSE events are stateless folds over the
 * shared slices, so a single instance follows the orchestrator lifetime and
 * is driven entirely through [handleEvent]. The `server.connected` catch-up
 * trigger is folded in here (one entry point for every event) and routed to
 * the foreground catch-up controller via [ControllerEffect.ServerConnected].
 *
 * §R-17 batch2 step e final: all state writes go through the per-slice
 * `MutableStateFlow.update` helpers (slices are the sole authoritative store).
 *
 * RFC reference: R-16 §B / §M4. Zero behaviour change — the dispatch body is a
 * verbatim move of the pre-extraction `handleIncomingSseEvent`, with the
 * buffer storage migrated to the slice and the per-branch state transforms
 * extracted as pure functions (R-17 batch5).
 */
@Suppress("DEPRECATION")
class SessionSyncCoordinator(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
    private val settingsManager: SettingsManager,
    private val effects: SharedEffectBus,
    /** R-20 Phase 1: provider for the current host's serverGroupFp. Used to
     *  key the [ControllerEffect.EvictSession] emission on the session.updated
     *  archived branch (plan §3 矩阵 "SSE 归档 session" 行). */
    internal val currentServerGroupFp: () -> String,
    /**
     * R-20 Phase 1 (C4, maxer I11): persistent cache for the message.updated
     * new-insert append path. When a `message.updated` SSE event for the
     * current session inserts a NEW message (found=false in
     * [ChatState.applyMessageUpdated]), the cached window for that session
     * (if any) must stay in sync without a full putSessionWindow round-trip.
     * Fire-and-forget: failures only log via DebugLog.e; the user is never
     * blocked (plan §5 不崩).
     */
    internal val cacheRepository: CacheRepository? = null,
    /**
     * CP1 (notify Phase-0): the single source of truth for the connection
     * epoch. Replaces the private [hostGeneration] AtomicLong — the epoch
     * now comes from [ConnectionIdentityStore.currentEpoch] (guarded by
     * [ConnectionIdentityStore.beginReconfigure] at the reconfigure barrier
     * origin in HostProfileController). FGS spec §2 «关键约束»: no second
     * private generation.
     *
     * The [handleEvent]/[handleEvent] identified overload validates
     * [ConnectionIdentityStore.isCurrent] BEFORE any fold/state mutation.
     */
    internal val identityStore: ConnectionIdentityStore? = null,
    /**
     * CP4 (notify Phase-0): the authoritative status aggregator's INPUT
     * surface. The `session.status` SSE branch feeds it via
     * [StatusAggregatorInput.applySseStatus] (keyed by `(serverGroupFp,
     * workdir, sessionId)`, sourced from the SSE arrival time) BEFORE the
     * existing unread/badge fold runs. Optional so legacy/test construction
     * (which drives [handleEvent] directly with raw SSEEvent) keeps working
     * — when null, the SSE branch simply skips the aggregator feed (no
     * behaviour change for tests that did not wire the aggregator).
     */
    internal val statusAggregatorInput: StatusAggregatorInput? = null,
    /**
     * CP4 (notify Phase-0): the single clock used for SSE arrival timestamps
     * passed to [StatusAggregatorInput.applySseStatus]. Defaults to wall-clock
     * millis — the SAME clock domain as `StatusAggregatorImpl`'s injected
     * `clock`, so merge-timing comparisons inside the aggregator are
     * consistent. Test-only override (the existing tests do not assert on
     * arrival times; the default is fine).
     */
    internal val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** Tag for [reportNonFatalIssue]; mirrors the original MainViewModel TAG. */
    private val tag: String = "SessionSyncCoordinator"
    /**
     * §R-17 batch5: the ONLY coalesce state retained on the coordinator. The
     * Job references are bound to [scope] (a Job is neither serializable nor
     * a value type, so it cannot live in [ChatState]). The observable mirror
     * — which partIds have a pending flush — is [ChatState.pendingFlushPartIds]
     * in the slice; this map is the imperative side that drives
     * `delay(DELTA_COALESCE_MS) → flushDeltaBuffer(partId)`.
     *
     * The two views are kept in lock-step: a leading-edge write adds the
     * partId to [ChatState.pendingFlushPartIds] AND schedules a job here;
     * [flushDeltaBuffer] removes the partId from the slice AND removes the
     * job here; [clearDeltaBuffers] cancels every job here AND wipes the
     * slice's three coalesce fields.
     *
     * **Thread confinement**: Main-thread confined — all access runs on
     * appScope (Dispatchers.Main.immediate). If appScope ever changes to a
     * non-single-threaded dispatcher, this MUST become a ConcurrentHashMap
     * or use @Synchronized.
     */
    private val flushJobs = mutableMapOf<String, Job>()

    /**
     * §R18 Phase 3 Wave 1 (P0-7): per-event-type counters for SSE events that
     * fell through the dispatch `when`'s else branch. Lets diagnostics (and
     * future tests) see which unknown types are recurring vs one-off. Only
     * incremented for NON-noisy types (noisy ones in [NOISY_SSE_LOG_EVENTS]
     * are known-intentional skips).
     */
    private val unknownEventCounters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /** Test/diagnostic read: snapshot of unknown-event counts by type. */
    internal fun unknownEventCountsSnapshot(): Map<String, Int> =
        unknownEventCounters.mapValues { it.value.get() }

    // ── §R-19 Sprint 1 Lane A (P1-10): SSE gap reconciliation overlay ────────

    /**
     * §P1-10: the SSE gap reconciliation overlay state. Drives the explicit
     * invariant ([SseSyncState] + [reconcileGap]) on top of
     * [ForegroundCatchUpController]'s 3-tier. See [SseSyncState] for the
     * overlay-vs-replacement rationale.
     *
     * `@Volatile` for forward-safety only — all reads/writes are confined to
     * the coordinator's single-threaded [scope] (Dispatchers.Main.immediate).
     */
    @Volatile
    private var sseSyncState: SseSyncState = SseSyncState()

    /**
     * CP1 (notify Phase-0): the generation counter has been REMOVED. The
     * private `hostGeneration: AtomicLong` that used to live here is gone —
     * it was a second generation that could drift apart from CC's
     * `directoryFetchGeneration` (which itself is now also gone, replaced by
     * [ConnectionIdentityStore]). The epoch is sourced from
     * [ConnectionIdentityStore.currentEpoch] via [currentEpoch].
     */

    /**
     * §P1-10: diagnostic + test hook — snapshot of the current overlay state.
     * Production callers should NOT branch on this (the decision logic lives
     * in [reconcileGap]); exposed for [SessionSyncGapReconcileTest] integration
     * cases and future debug surfaces.
     */
    internal fun sseSyncStateSnapshot(): SseSyncState = sseSyncState

    /**
     * CP1 (notify Phase-0): the current connection epoch, sourced from the
     * single [ConnectionIdentityStore]. Replaces the private [hostGeneration]
     * AtomicLong that was kept in lock-step with CC's generation via the
     * HostReconfigured effect. FGS spec §2 «关键约束»: no second private
     * generation — the epoch comes from the store.
     *
     * Returns 0 when no [identityStore] is wired (legacy/test construction)
     * so the pure [reconcileGap] function's generation guard still has a
     * stable value to compare against.
     */
    private fun currentEpoch(): Long = identityStore?.currentEpoch() ?: 0L

    /**
     * R-20 Phase 2 (G6): mark [sessionId] as having an established cold-snapshot
     * baseline. Called by [cn.vectory.ocdroid.ui.launchCatchUp]'s onColdSnapshot
     * callback on every successful catch-up. Future probe gating
     * ([cn.vectory.ocdroid.ui.chat.BackfillAlgorithm.shouldProbe]) treats a
     * session in this set + a live SSE feed for its workdir as covered (skip
     * the REST probe). Idempotent (set-add). Reset to empty by HostReconfigured.
     *
     * Confined to the coordinator's main-thread scope (matching sseSyncState's
     * write discipline); safe because launchCatchUp's onColdSnapshot fires from
     * a main-immediate coroutine.
     */
    internal fun markSessionColdSnapshotted(sessionId: String) {
        if (sessionId.isBlank()) return
        if (sessionId !in sseSyncState.sessionsEverColdSnapshotted) {
            sseSyncState = sseSyncState.copy(
                sessionsEverColdSnapshotted = sseSyncState.sessionsEverColdSnapshotted + sessionId
            )
        }
    }

    init {
        // §P1-10: observe the disconnect / host-reconfigure signals that the
        // SSE collector (ConnectionCoordinator) emits on the effects bus, so
        // the overlay state stays in lock-step without coupling the two
        // controllers. ServerConnected is NOT consumed here — it arrives via
        // [handleEvent] (the OnSseEvent path), which keeps the trigger's
        // `currentSessionId` snapshot co-temporal with the SSE frame.
        //
        // §start-UNDISPATCHED: the collector must be subscribed BEFORE the
        // first effect emission lands, otherwise the overlay misses the
        // initial CancelSse / HostReconfigured signals. UNDISPATCHED runs the
        // coroutine body inline until the first suspension (the SharedFlow
        // collect), guaranteeing the subscription is open by the time the init
        // block returns. Mirrors the test-harness pattern in
        // [SessionSyncCoordinatorTest.setUp].
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            effects.effects.collect { effect ->
                when (effect) {
                    is ControllerEffect.CancelSse -> {
                        // §P1-10 / CP9 §D22: an OBSERVED transport-disconnect
                        // signal. Producer (CP9): the Service's
                        // ServiceSseConnectionOwner emits this once when a
                        // live collector was actually stopped (Service going
                        // away, user explicit close, reconfigure teardown,
                        // §4.1 timeout) — NOT FCC/CC cancelling a job (CC
                        // no longer owns a job). The current session's slice
                        // is now potentially stale (the user was watching
                        // it). Mark dirty + stamp the disconnect time so the
                        // next `server.connected` reconciles.
                        val gen = currentEpoch()
                        val now = System.currentTimeMillis()
                        val dirty = listOfNotNull(slices.chat.value.currentSessionId).toSet()
                        val trigger = SseReconnectTrigger.Disconnected(now, dirty, gen)
                        sseSyncState = reconcileGap(sseSyncState, trigger).first
                    }
                    is ControllerEffect.HostReconfigured -> {
                        // §P1-10 scenario 4 + CP1: the epoch carried on the
                        // effect IS the new generation (bumped synchronously
                        // by ConnectionIdentityStore.beginReconfigure at the
                        // HostProfileController barrier origin). Reset the
                        // overlay to a fresh cold-start under this epoch so
                        // any in-flight ServerConnected from the PREVIOUS
                        // host's cancelled SSE job becomes a stale-trigger
                        // no-op.
                        val trigger = SseReconnectTrigger.HostReconfigured(effect.epoch)
                        sseSyncState = reconcileGap(sseSyncState, trigger).first
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * CP1 (notify Phase-0) identity-checked entry point. Validates
     * [identified.identity] against [ConnectionIdentityStore.isCurrent]
     * BEFORE any fold/state mutation — a stale-identity frame (captured under
     * a pre-reconfigure epoch) is dropped silently here so it cannot pollute
     * the new host's state. This is the production path:
     * ConnectionCoordinator.launchSseCollection captures the identity at
     * collection start, wraps each event as [IdentifiedSseEvent], and emits
     * [ControllerEffect.OnSseEvent]; AppCore.dispatchConnectionEffect routes
     * it here.
     *
     * FGS spec §1 «identity 不得在 fold 前被剥掉»: the identity is carried on
     * the event container (NOT stripped at the bridge) so this second-stage
     * validation is possible without trusting the bridge alone.
     *
     * When no [identityStore] is wired (legacy/test construction that bypasses
     * the store), the frame is dispatched WITHOUT the identity gate (backward
     * compat for tests that drive [handleEvent] directly with raw SSEEvent).
     */
    fun handleEvent(identified: IdentifiedSseEvent) {
        val store = identityStore
        if (store != null && !store.isCurrent(identified.identity)) {
            // Drop stale-identity frame BEFORE any side effect. Keep the
            // existing stale-host logging pattern (DebugLog.i, not Log.w —
            // this is an expected race window during reconfigure, not a bug).
            DebugLog.i(
                "Sync",
                "drop stale-identity SSE event " +
                    "(epoch=${identified.identity.epoch} current=${store.currentEpoch()} " +
                    "type=${identified.event.payload.type})"
            )
            return
        }
        handleEvent(identified.event)
    }

    /**
     * §P1-10 entry point for every SSE event. Mirrors the pre-extraction
     * `MainViewModel.handleSSEEvent`: first the `server.connected` catch-up
     * trigger, then the [dispatchSseEvent] fold.
     *
     * §Phase1E: every (re)connect's first frame is `server.connected`. Catch-up
     * runs on every connect EXCEPT the very first process-time connect (cold
     * start has no local history). The three-tier suppress /
     * sseHasConnectedOnce state machine lives in [ForegroundCatchUpController]
     * (R-16 M1); it calls back into the catch-up probe via
     * [ForegroundCatchUpCallbacks] when a probe is actually warranted.
     *
     * CP1: the generation value for the reconcile trigger now comes from
     * [ConnectionIdentityStore.currentEpoch] (via [currentEpoch]) instead of
     * the removed private AtomicLong — single epoch source, FGS spec §2.
     */
    fun handleEvent(event: SSEEvent) {
        if (event.payload.type == "server.connected") {
            // §R18 Phase 3 Wave 1 (P1-3 B 类): 单发非 suspend → tryEmitEffect。
            effects.tryEmitEffect(ControllerEffect.ServerConnected)
            // §R-19 Sprint 1 Lane A (P1-10): consult the SSE gap reconciliation
            // overlay BEFORE ForegroundCatchUpController's catch-up runs (the
            // ServerConnected effect above is collected asynchronously by
            // AppCore's dispatch loop). The reconciler is a pure decision
            // function: it returns the new overlay state + a list of decisions
            // that we translate into effects here.
            //
            // currentSessionId is captured at event-arrival time so scenario 3
            // (user switched sessions mid-disconnect) targets the NEW session.
            val currentSessionId = slices.chat.value.currentSessionId
            val gen = currentEpoch()
            val trigger = SseReconnectTrigger.ServerConnected(currentSessionId, gen)
            val (nextState, decisions) = reconcileGap(sseSyncState, trigger)
            sseSyncState = nextState
            applySseSyncDecisions(decisions)
        }
        dispatchSseEvent(event)
    }

    /**
     * §R-19 Sprint 1 Lane A (P1-10): translates [SseSyncDecision]s returned by
     * [reconcileGap] into concrete side effects. Kept tiny + inline so the
     * pure decision function stays the single unit-testable surface.
     *
     *  - [SseSyncDecision.ReloadSession]    → `LoadMessages` effect
     *    (single-shot, non-suspend → tryEmitEffect to preserve FIFO order
     *    relative to the ServerConnected emit above).
     *  - [SseSyncDecision.LoadSessionStatus] → `LoadSessionStatus` effect
     *    (the AppCore handler performs the repository-backed status reload).
     *  - [SseSyncDecision.RefreshSessions]  → `LoadSessions` effect (the
     *    non-current dirty session's list-level state is refreshed).
     *  - [SseSyncDecision.ClearDeltaBuffers] → local [clearDeltaBuffers] call.
     */
    private fun applySseSyncDecisions(decisions: List<SseSyncDecision>) {
        if (decisions.isEmpty()) return
        for (decision in decisions) {
            when (decision) {
                is SseSyncDecision.ReloadSession -> {
                    // §R18 Phase 3 Wave 1 (P1-3 B 类): 单发非 suspend → tryEmitEffect。
                    effects.tryEmitEffect(
                        ControllerEffect.LoadMessages(decision.sessionId, decision.resetLimit)
                    )
                }
                SseSyncDecision.LoadSessionStatus -> {
                    effects.tryEmitEffect(ControllerEffect.LoadSessionStatus)
                }
                SseSyncDecision.RefreshSessions -> {
                    // §R-19 P1-10: RefreshSessions decision maps to the
                    // existing LoadSessions effect (refreshes the session
                    // list — used when a non-current session is dirty and we
                    // don't want a per-session windowed reload).
                    effects.tryEmitEffect(ControllerEffect.LoadSessions)
                }
                SseSyncDecision.ClearDeltaBuffers -> {
                    clearDeltaBuffers()
                }
            }
        }
    }

    /**
     * §R-19 Sprint 3 P2-4: the single side-effect routing point. Translates
     * each [SseSideEffect] into its matching [ControllerEffect] emit /
     * [UiEvent] emit / log call. Called by every `dispatchSseEvent` branch
     * with the combined effects list (applyXxx-returned + dispatcher-computed
     * cross-slice effects).
     *
     * **Why centralized**: P2-4's goal is to kill the scattered
     * `effects.tryEmitEffect(...)` / `effects.tryEmitUiEvent(...)` calls that
     * were sprinkled inline in the 11 `when` branches. Every bus-level side
     * effect now flows through this single helper, making the dispatcher's
     * effect sequencing auditable and the pure applyXxx functions testable
     * in isolation (they return the effects list; the dispatcher commits it).
     */
    private fun applySseSideEffects(sideEffects: List<SseSideEffect>) {
        if (sideEffects.isEmpty()) return
        for (effect in sideEffects) {
            when (effect) {
                is SseSideEffect.ReloadMessages -> {
                    // §R18 Phase 3 Wave 1 (P1-3 B 类): 单发非 suspend → tryEmitEffect。
                    effects.tryEmitEffect(
                        ControllerEffect.LoadMessages(effect.sessionId, effect.resetLimit)
                    )
                }
                SseSideEffect.LoadPendingPermissions -> {
                    // §R18 Phase 3 Wave 1 (P1-3 B 类): 单发非 suspend → tryEmitEffect。
                    effects.tryEmitEffect(ControllerEffect.LoadPendingPermissions)
                }
                is SseSideEffect.SessionError -> {
                    // §R18 Phase 2-G: pick the format by whether `name` is present
                    // so the rendered text matches the prior "$name: $rawMsg" /
                    // "$rawMsg" shape exactly (only the prefix label is i18n'd;
                    // name + rawMsg come from the server payload).
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
                    reportNonFatalIssue(tag, effect.message)
                }
            }
        }
    }

    /**
     * Dispatches a single SSE event. Per the SSE-trust model (mirrors opencode-web):
     *
     * - `message.updated` for the current session does NOT reload — live text comes
     *   via `streamingPartTexts` (populated by `message.part.updated` delta/full
     *   text). Structural sync is handled in-place: an existing message is patched,
     *   and a NEW message (absent from the local list) is INSERTED (server 1.17.11+
     *   emits `message.updated`, not `message.created`, for new messages; the oc-ref
     *   web client does the same patch-if-found + insert-if-absent).
     * - `message.part.updated` with empty delta but non-null ids (a part status
     *   flip) does NOT clear streaming buffers or reload. Only a true `part.created`
     *   (ids null) wipes the streaming state and reloads.
     * - `session.status` transitions only update the `sessionStatuses` map (busy/idle
     *   badge). They do NOT reload or clear streaming buffers — the finalized turn
     *   text is carried by `streamingPartTexts` until a foreground catch-up
     *   reconciles the persisted message list. (A busy transition on the CURRENT
     *   session also triggers a debounced reload as the cross-client-sync fallback.)
     * - There is no watchdog/idle-reload: a silently-stalled SSE feed recovers via
     *   connection-level retry, a foreground transition (SSE restart), or the next
     *   user action — matching opencode-web.
     *
     * §R-17 batch5: each `when` branch now calls a pure `applyXxx` extension
     * function for the state transform. Side effects (effect emits, settings
     * writes, scheduling) stay inline.
     */
    private fun dispatchSseEvent(event: SSEEvent) {
        // Throttle dispatch logging to preserve the 1000-entry ring buffer's signal.
        // Skipped (noise): server.heartbeat (periodic), message.part.delta (per-token
        // during streaming — its render IS the proof), server.connected (fires on
        // every reconnect), and plugin.added / catalog.updated / integration.updated
        // (server-internal bursts that fire in large flurries when a run starts,
        // e.g. when another client sends a message). Logging-only — dispatch is
        // unchanged. All other types (message.created/updated, session.*,
        // permission/question, todo, connection) are logged — they are the actual
        // sync signal.
        val type = event.payload.type
        val evtSession = event.payload.getString("sessionID") ?: "-"
        val noisy = type in NOISY_SSE_LOG_EVENTS
        if (!noisy) {
            DebugLog.d("Sync", "dispatch $type session=$evtSession current=${slices.chat.value.currentSessionId}")
        }
        when (event.payload.type) {
            "session.created" -> {
                val created = parseSessionCreatedEvent(event)
                if (created != null) {
                    slices.mutateSessionList { s -> s.applySessionCreated(created.session).first }
                } else {
                    applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid session.created payload")))
                }
            }
            "session.updated" -> {
                val updated = parseSessionUpdatedEvent(event)
                if (updated != null) {
                    if (updated.isArchived) {
                        // Archived (typically by another client): evict the
                        // id from the open-tabs list and persist, mirroring
                        // the user-triggered archive path. If the archived
                        // session is the currently-open one, also clear
                        // currentSessionId + messages so the chat view falls
                        // back to the empty state instead of lingering on an
                        // archived session whose tab has disappeared.
                        val newOpenIds = slices.sessionList.value.openSessionIds.filter { id -> id != updated.id }
                        if (newOpenIds != slices.sessionList.value.openSessionIds) {
                            settingsManager.openSessionIds = newOpenIds
                        }
                        // §A5-3 Phase B2: the pre-B2 dual
                        // mutateSessionList(applyArchiveEviction) +
                        // mutateChat(applyArchivedChatClear) (the latter only
                        // when the archived session WAS the current one) is
                        // collapsed into ONE atomic dispatch. The reducer
                        // derives the "clear chat" decision from the snapshot
                        // (chat.currentSessionId == updated.id), so the
                        // action carries pure data only — no clearChat
                        // boolean. The applyArchiveEviction + (conditional)
                        // applyArchivedChatClear helpers are reused unchanged
                        // inside [reduce]. ONE committed aggregate state →
                        // no torn "sessionList archived but
                        // chat.currentSessionId still references it"
                        // intermediate for stateFlow collectors.
                        slices.store.dispatch(
                            cn.vectory.ocdroid.ui.AppAction.SessionArchived(
                                session = updated,
                                openSessionIds = newOpenIds,
                            )
                        )
                        // R-20 Phase 1 (plan §3 矩阵 "SSE 归档 session" 行):
                        // evict the archived session from both the memory LRU
                        // and the persistent cache. The fp comes from the
                        // currentServerGroupFp provider (the SSE feed is
                        // generation-guarded by ConnectionCoordinator so a
                        // stale-host event cannot reach here — see
                        // §P1-10 hostGeneration guard). Routed through the
                        // effect bus so AppCore.dispatchHostEffect runs the
                        // memory + persistent halves.
                        effects.tryEmitEffect(
                            ControllerEffect.EvictSession(currentServerGroupFp(), updated.id)
                        )
                    } else {
                        slices.mutateSessionList { s -> s.applySessionUpsert(updated).first }
                    }
                } else {
                    applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid session.updated payload")))
                }
            }
            "session.status" -> {
                // §Phase1a instrumentation (Issue 4): capture the FULL raw properties
                // JSON so we can confirm which fields actually arrive for retry
                // (type/attempt/message/next + any `action` sub-object). Parser/model
                // is Phase 4 — for now, raw toString() of the whole JsonObject.
                DebugLog.d("Retry", "session.status raw properties=${event.payload.properties?.toString() ?: "null"}")
                val statusEvent = parseSessionStatusEvent(event)
                if (statusEvent != null) {
                    // CP4 (notify Phase-0): feed the authoritative status aggregator
                    // BEFORE the existing fold. The aggregator's input surface takes
                    // the composite key `(serverGroupFp, workdir, sessionId)` and the
                    // SSE arrival time `clock()` (same clock domain as the impl's
                    // injected clock — merge timing inside the aggregator is consistent).
                    // The identity gate already ran in [handleEvent(IdentifiedSseEvent)]
                    // (CP1 isCurrent check) before this dispatch, so the current identity
                    // is the live one — using [currentServerGroupFp] here matches the
                    // session.updated archived branch's pattern.
                    //
                    // Unknown sessionId (not in sessionsById) → silently skipped
                    // (matches the existing §unread-lifecycle "unknown id" exclusion;
                    // we cannot build a composite key without the workdir).
                    val aggregatorInput = statusAggregatorInput
                    if (aggregatorInput != null) {
                        val sessionsByIdNow = allSessionsById(
                            slices.sessionList.value.sessions,
                            slices.sessionList.value.directorySessions,
                            slices.sessionList.value.childSessions,
                        )
                        val target = sessionsByIdNow[statusEvent.sessionId]
                        if (target != null) {
                            val key = SessionStatusKey(
                                serverGroupFp = currentServerGroupFp(),
                                workdir = target.directory,
                                sessionId = statusEvent.sessionId,
                            )
                            aggregatorInput.applySseStatus(
                                key,
                                statusEvent.status.toSessionBusyStatus(),
                                sourceTimeMs = clock(),
                            )
                        }
                    }
                    // §unread-soak (main): the prior busy→idle edge capture
                    // (oldStatus / wasBusy / nowIdle) is GONE — the instant marking
                    // it fed is now owned by the [UnreadSoakController] sweep + the
                    // pure [evaluateUnread] evaluator. This branch only folds the new
                    // status into sessionStatuses so the evaluator sees it. (The CP4
                    // aggregator feeding above is independent of that edge capture.)
                    slices.mutateSessionList {
                        it.applySessionStatus(statusEvent.sessionId, statusEvent.status).first
                    }
                    // §cross-client-sync: when the CURRENT session goes busy, a run
                    // just started — i.e. a message was sent, possibly from ANOTHER
                    // client (e.g. the web UI). This is a belt-and-suspenders reload
                    // that catches messages emitted before the message.updated
                    // insert-if-absent path (below) has run / before the local view
                    // is subscribed — the primary surfacing path is now message.updated
                    // insert-if-absent (mirrors the web client). Debounced via
                    // loadMessagesWithRetry's 400ms delay + isLoadingMessages
                    // coalescing; the user message is persisted server-side before
                    // the run starts. The overlay-clear in launchLoadMessages is
                    // gated on !busy, so this does NOT disrupt the streaming overlay.
                    //
                    // §R-19 Sprint 3 P2-4: cross-slice effects (depend on chat /
                    // unread state, not just SessionListState) are computed here
                    // and routed through applySseSideEffects — the single side-
                    // effect routing point. applySessionStatus itself returns
                    // emptyList() (its state transform is pure same-slice).
                    val statusEffects = mutableListOf<SseSideEffect>()
                    val chatSnap = slices.chat.value
                    val isCurrent = statusEvent.sessionId == chatSnap.currentSessionId
                    // §unread-soak: unread marking is NO LONGER done here on
                    // the instant busy→idle edge. The foreground
                    // [UnreadSoakController] sweep + the pure [evaluateUnread]
                    // evaluator now own the population logic — a root becomes
                    // unread ONLY when (a) it AND all descendants are idle,
                    // (b) that all-idle state persisted ≥10s, (c) it is not the
                    // current session, AND (d) it was not viewed since idle.
                    // This branch still updates sessionStatuses (via
                    // applySessionStatus above) so the evaluator sees fresh
                    // statuses; [applyMarkSessionUnread] remains the
                    // low-level marker (now called only by the sweep).
                    if (statusEvent.status.isBusy && isCurrent) {
                        DebugLog.i("Sync", "session.status busy (current) → reload (cross-client message sync)")
                        statusEffects.add(SseSideEffect.ReloadMessages(statusEvent.sessionId, resetLimit = true))
                    }
                    // SSE-trust model: session.status (busy/idle) only updates the
                    // status badge. It does NOT reload or clear streaming buffers
                    // (except the busy-current and idle-current finalization paths).
                    // The finalized turn text is carried by streamingPartTexts
                    // until a foreground catch-up reconciles the persisted message
                    // list — mirroring opencode-web.
                    //
                    // §append-safe finalization (gpter MAJOR): the overlay-clear in
                    // launchLoadMessages is gated on !busy, so if the last reload
                    // happened while busy (overlay preserved), the overlay could
                    // linger after the run settles. When the CURRENT session goes
                    // idle with a non-empty overlay, reconcile against the now-
                    // persisted authoritative window (loadMessagesWithRetry delays
                    // internally so the server has time to persist the finalized
                    // part text; status is now idle so the gated clear will run).
                    //
                    // §R-19 fix Blocker 2 v2: no sessionsDirty dedup — idle-driven
                    // reloads ALWAYS fire based on overlay state; overlapping
                    // reloads coalesce at the scheduling layer (isLoadingMessages
                    // check-and-set + 400ms debounce).
                    if (statusEvent.status.isIdle && isCurrent) {
                        val overlayNonEmpty = chatSnap.streamingPartTexts.isNotEmpty() || chatSnap.streamingReasoningPart != null
                        DebugLog.d("Sync", "session.status idle → ${if (overlayNonEmpty) "reload" else "no-op"}")
                        if (overlayNonEmpty) {
                            statusEffects.add(SseSideEffect.ReloadMessages(statusEvent.sessionId, resetLimit = true))
                        }
                    }
                    applySseSideEffects(statusEffects)
                } else {
                    applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid session.status payload")))
                }
            }
            "message.created" -> {
                // NOTE: server 1.17.11+ does NOT emit message.created (only
                // message.updated / message.part.*). This branch is retained for
                // FORWARD COMPATIBILITY if a future server version adds it; today
                // it is effectively dead code. New messages are surfaced by the
                // message.updated insert-if-absent path above and the
                // session.status busy reload.
                //
                // §unread-lifecycle Task 3: unread is produced SOLELY by the
                // session.status root busy→idle path (lifecycle completion).
                // This branch keeps only its time.updated bump + current-session
                // reload for forward-compat; it no longer marks unread.
                val sessionId = event.payload.getString("sessionID")
                val isCurrent = sessionId != null && sessionId == slices.chat.value.currentSessionId
                DebugLog.i("Sync", "message.created: ${if (isCurrent) "reload current" else "no-op (unread is lifecycle-driven)"}")

                // §recent-sort-by-message: forward-compat parity with the
                // message.updated branch — bump the session's time.updated to
                // max(current, info.time.created) so the recent-sessions list
                // reorders on a new message even if a future server emits
                // message.created instead of message.updated.
                if (sessionId != null) {
                    val createdInfo = event.payload.getJsonObject("info")?.let {
                        runCatching {
                            lenientJson.decodeFromJsonElement<Message>(it)
                        }.getOrNull()
                    }
                    val msgCreated = createdInfo?.time?.created ?: 0L
                    if (msgCreated > 0L) {
                        slices.mutateSessionList { s ->
                            s.applyMessageTimestampBump(sessionId, msgCreated).first
                        }
                    }
                }

                // §R-19 Sprint 3 P2-4: side effects formalized as SseSideEffect list.
                // Only the current session reloads; non-current sessions do NOT
                // mark unread here (see the session.status busy→idle path).
                val msgEffects = mutableListOf<SseSideEffect>()
                if (sessionId != null && sessionId == slices.chat.value.currentSessionId) {
                    msgEffects.add(SseSideEffect.ReloadMessages(sessionId, resetLimit = true))
                }
                applySseSideEffects(msgEffects)
            }
            "message.updated" -> {
                // SSE-trust: patch the message metadata in-place from the server's
                // authoritative `info` (mirrors opencode-web server-session.ts:706).
                // Server payload confirmed to carry a full { info: Message } object.
                // We replace the matching Message in the split `messages` store
                // (List<Message>); its parts live separately in partsByMessage and
                // are NOT touched.
                //
                // §cross-client-sync (server 1.17.11+): the server emits
                // `message.updated` (NOT `message.created`) for NEW messages. So
                // when the message id is ABSENT from the local list we INSERT it
                // (append — the list is oldest-first and the new message is the
                // newest). This mirrors the oc-ref web client's
                // patch-if-found + insert-if-absent handler. Subsequent updates for
                // the same id find it in the list and patch in place, so this is a
                // once-per-new-id op (no storm — pure state update, no I/O, no
                // reload). The session.status busy → reload path is the parallel
                // belt-and-suspenders for messages emitted before the local view
                // is even subscribed.
                // Defensive session guard: only touch the current session's chat
                // view (the slice mutation below).
                val eventSessionId = event.payload.getString("sessionID")
                val infoJson = event.payload.getJsonObject("info")
                val updated = infoJson?.let {
                    runCatching {
                        lenientJson.decodeFromJsonElement<Message>(it)
                    }.getOrNull()
                }

                // §recent-sort-by-message: bump the owning session's time.updated
                // to max(current, message.time.created) on every message event so
                // the "recent sessions" surfaces (SessionsScreen recent +
                // SessionPickerSheet recent, both sortedByDescending { time.updated })
                // reflect actual message activity, not just server-side session
                // metadata timestamps. The bump applies to BOTH current and non-
                // current sessions (cross-client messages from another client
                // surface in the recent list). withUpdatedAtLeast is idempotent /
                // monotonic, so repeated patches for the same message are a no-op
                // (no thrash) and out-of-order / replay events never go backwards.
                if (eventSessionId != null && updated != null) {
                    val msgCreated = updated.time?.created ?: 0L
                    if (msgCreated > 0L) {
                        slices.mutateSessionList { s ->
                            s.applyMessageTimestampBump(eventSessionId, msgCreated).first
                        }
                    }
                }

                // Defensive session guard: only touch the current session's chat view.
                if (eventSessionId != null && eventSessionId != slices.chat.value.currentSessionId) return
                if (updated != null && updated.id.isNotEmpty()) {
                    // §R-17 batch5: pure transform returns (newState, found).
                    // Single O(n) scan inside the atomic update — `found` is
                    // set during the same `map` pass that builds the
                    // replacement list, so the patch-vs-insert decision and
                    // the found flag come from one atomic pass (no TOCTOU, no
                    // second `.none{}` scan). When found, patch in place;
                    // when NOT found, append (insert) the new message at the
                    // tail (oldest-first list).
                    var found = false
                    slices.mutateChat { c ->
                        val (next, wasFound) = c.applyMessageUpdated(updated)
                        found = wasFound
                        next
                    }
                    if (found) {
                        DebugLog.d("Sync", "message.updated: patched")
                    } else {
                        DebugLog.i("Sync", "message.updated: inserted (new message, absent from local list)")
                        // R-20 Phase 1 (C4, maxer I11): the new message
                        // was appended to the slice. Mirror it into the
                        // persistent cache so a subsequent switch-back
                        // (VerifyAndHydrate → Verified) sees it without a
                        // full putSessionWindow round-trip. Append is
                        // scoped to the CURRENT session (eventSessionId
                        // was already guarded above to equal
                        // currentSessionId), and appendMessageIfSessionCached
                        // is a no-op when no cached session row exists
                        // (cold-start sessions don't proactively build a
                        // cache — only already-cached sessions stay fresh).
                        // Fire-and-forget on scope; failures only log.
                        if (cacheRepository != null) {
                            val fp = currentServerGroupFp()
                            val sid = eventSessionId!!
                            scope.launch {
                                runCatching {
                                    cacheRepository.appendMessageIfSessionCached(
                                        fp,
                                        sid,
                                        updated,
                                        // §parts-by-message: the new message
                                        // has no parts yet (its first
                                        // part.created / part.updated will
                                        // arrive separately). Persist an
                                        // empty list; the next part event +
                                        // the post-turn idle reload fills
                                        // them in.
                                        emptyList(),
                                    )
                                }.onFailure {
                                    DebugLog.e(tag, "appendMessageIfSessionCached failed fp=$fp sid=$sid", it)
                                }
                            }
                        }
                    }
                }
            }
            "message.part.updated" -> {
                val deltaEvent = parseMessagePartDeltaEvent(event) ?: return
                if (deltaEvent.sessionId == slices.chat.value.currentSessionId) {
                    val msgId = deltaEvent.messageId
                    val pId = deltaEvent.partId
                    if (msgId != null && pId != null) {
                        val key = pId
                        // §user-part-guard (flicker root cause, secondary): the
                        // server emits message.part.updated for the USER message
                        // too — the user input text is reflected as a part event
                        // (type=text). Treating it as streaming assistant output
                        // pollutes streamingPartTexts with a partId that belongs
                        // to the user message and injects a placeholder into the
                        // user bubble, which (a) misleads the assistant's
                        // isStreaming guard and (b) can render echoed text in the
                        // user's own bubble. Only ASSISTANT output streams — skip
                        // parts whose owning message is a user message. The user
                        // message is always inserted before its part event, so
                        // the lookup is reliable; an unknown msgId falls through
                        // (it is the assistant message being born).
                        val ownerIsUser = slices.chat.value.messages.any { it.id == msgId && it.isUser }
                        if (ownerIsUser) return
                        val fullText = deltaEvent.text
                        val delta = deltaEvent.delta
                        // §reasoning-routing-fix (symptom: reasoning rendered as
                        // 正文 / main body text). The server creates a reasoning
                        // part via part.updated with type=reasoning but BLANK text
                        // (full=0) BEFORE streaming its content via part.delta.
                        // The blank creation event used to fall through the
                        // fullText/delta gates below (both require non-blank
                        // content), so the type=reasoning info was LOST. The
                        // subsequent part.delta events then injected a placeholder
                        // using their `field` — which the server sets to "text"
                        // even for reasoning content — so the reasoning part became
                        // type=text and rendered as 正文, and streamingReasoningPart
                        // was never set (no thinking card). Record the part's TRUE
                        // type at creation here (inject correctly-typed placeholder
                        // + set streamingReasoningPart) so content routes to the
                        // standalone thinking card. Idempotent: once the part
                        // exists with the correct type this is a no-op.
                        val pType = deltaEvent.partType
                        if (isStreamablePartType(pType)) {
                            val existingParts = slices.chat.value.partsByMessage[msgId]
                            val hasCorrectType = existingParts?.any { it.id == pId && it.type == pType } == true
                            if (!hasCorrectType) {
                                slices.mutateChat { c ->
                                    c.applyPartCreatedPlaceholder(pType, pId, msgId, deltaEvent.sessionId).first
                                }
                                // §streaming-flicker-diagnosis (Top1): the placeholder
                                // Part (text=null) is now in partsByMessage but the first
                                // delta/fullText has NOT yet been staged into
                                // streamingPartTexts — this is the two-phase-mutation
                                // intermediate state. inStreamingTexts is expected to be
                                // false here; if Compose snapshots between this mutation
                                // and the leading-edge write below, the ChatMessageContent
                                // filter (Top1) drops the whole message → blank frame.
                                if (STREAMING_FLICKER_DEBUG) {
                                    val inStreamingTexts = key in slices.chat.value.streamingPartTexts
                                    Log.w(
                                        FLICKER_TAG,
                                        "placeholder created partId=$key msgId=$msgId inStreamingTexts=$inStreamingTexts"
                                    )
                                }
                            }
                        }
                        if (!fullText.isNullOrBlank()) {
                            // Server sent full accumulated text — use it as the
                            // authoritative streaming value (REPLACE, not append;
                            // acts as a sync point). §Site1-coalesce: mirror the
                            // Site2 (delta) leading-edge + trailing
                            // DELTA_COALESCE_MS coalesce pattern. Some Site1
                            // servers emit fullText per-token; without coalescing
                            // each event recomposed. Leading edge writes
                            // immediately (zero-latency first-token feel) + sets
                            // streamingReasoningPart once + ensures the placeholder
                            // part; subsequent fullText events within the window
                            // buffer into fullTextBuffer (REPLACE — fullText is
                            // authoritative accumulated text, only the latest value
                            // matters) and flush in one state write.
                            if (flushJobs[key]?.isActive != true) {
                                slices.mutateChat { c ->
                                    c.applyPartFullTextLeadingEdge(
                                        partId = key,
                                        fullText = fullText,
                                        partType = deltaEvent.partType,
                                        pId = pId,
                                        msgId = msgId,
                                        sessionId = deltaEvent.sessionId
                                    ).first.markFlushPending(key).first
                                }
                                scheduleDeltaFlush(key)
                                // §streaming-flicker-diagnosis (Top1): first frame
                                // (fullText) staged into streamingPartTexts — closes
                                // the intermediate-state window opened by the
                                // placeholder mutation above.
                                if (STREAMING_FLICKER_DEBUG) {
                                    Log.w(FLICKER_TAG, "first fullText staged partId=$key msgId=$msgId")
                                }
                            } else {
                                // Trailing coalesce: buffer the latest fullText
                                // (REPLACE). The pending DELTA_COALESCE_MS flush
                                // writes the buffered fullText in one state write.
                                // streamingReasoningPart was already set on this
                                // part's leading edge.
                                slices.mutateChat { c -> c.replaceFullTextBuffer(key, fullText).first }
                            }
                        } else if (!delta.isNullOrBlank()) {
                            // §flicker-fix: apply the same leading-edge +
                            // trailing DELTA_COALESCE_MS coalesce used by the
                            // `message.part.delta` handler. The server emits
                            // `message.part.updated` as the per-token streaming
                            // event (dozens–100s/sec); without coalescing each
                            // one wrote AppState and triggered a Compose
                            // re-parse/recompose, causing streaming jitter.
                            // Leading edge writes immediately (zero-latency
                            // first-token feel) + sets streamingReasoningPart
                            // once; subsequent deltas within the window buffer
                            // into deltaBuffer and flush in one batch.
                            if (flushJobs[key]?.isActive != true) {
                                slices.mutateChat { c ->
                                    c.applyPartDeltaLeadingEdge(
                                        partId = key,
                                        delta = delta,
                                        partType = deltaEvent.partType,
                                        pId = pId,
                                        msgId = msgId,
                                        sessionId = deltaEvent.sessionId
                                    ).first.markFlushPending(key).first
                                }
                                scheduleDeltaFlush(key)
                                // §streaming-flicker-diagnosis (Top1): first frame
                                // (delta) staged into streamingPartTexts — closes
                                // the intermediate-state window opened by the
                                // placeholder mutation above.
                                if (STREAMING_FLICKER_DEBUG) {
                                    Log.w(FLICKER_TAG, "first delta staged partId=$key msgId=$msgId")
                                }
                            } else {
                                // Trailing coalesce: buffer; the pending
                                // DELTA_COALESCE_MS flush appends the batch in
                                // one state write. streamingReasoningPart was
                                // already set on this part's leading edge.
                                slices.mutateChat { c -> c.appendDeltaBuffer(key, delta).first }
                            }
                        }
                        // Else: ids present + both text & delta null/blank =
                        // part status flip. Do NOT clear streaming buffers and
                        // do NOT reload — a foreground catch-up or the next
                        // message.updated / part.created reconciles if needed.
                    } else {
                        // 闪屏修复：part.created（part 对象只有 type 无 messageID/id）
                        // 在同一回合内每个新 part 都会发出（reasoning→text→tool…），
                        // 并非原先注释假设的"全新回合"。原先这里无条件清空
                        // streamingPartTexts + streamingReasoningPart，导致所有流式
                        // 气泡（reasoning 卡片、正文 text）瞬间坍缩为零高度，下一个
                        // 带 ID 的 part.updated 又重新填充 → 反复闪烁（用户观察：思考
                        // 卡片与正文气泡持续闪屏）。
                        //
                        // 修复：不再手动清空 overlay。reload(resetLimit=false) 本身保留
                        // streamingPartTexts/streamingReasoningPart（见 launchLoadMessages
                        // §append-safe MessageActions.kt:175），当前流式
                        // 气泡继续显示不坍缩。仅 clearDeltaBuffers() 丢弃旧 part 的 pending
                        // delta 缓冲（新 part 随后用权威 fullText 恢复，不丢数据）。下一
                        // 个带 ID 的 message.part.updated 正确更新流式状态；回合结束 idle
                        // finalization（resetLimit=true + streamingFinalized）正常清空 overlay。
                        //
                        // §R-19 Sprint 3 P2-4: side effects routed through applySseSideEffects.
                        clearDeltaBuffers()
                        applySseSideEffects(listOf(
                            SseSideEffect.ReloadMessages(deltaEvent.sessionId, resetLimit = false)
                        ))
                    }
                }
            }
            "message.part.delta" -> {
                // Web has an independent `message.part.delta` event (distinct from
                // `message.part.updated`): top-level { sessionID, messageID, partID,
                // field, delta }, field-level incremental append. Without this
                // handler, when the server emits this event the client loses all
                // streaming text. Payload shape per opencode-web server-session.ts.
                //
                // Phase 2 scope (docs/architecture-v3-sse-trust.md §246): only
                // accumulate into streamingPartTexts. Field-level in-place updates
                // on the Part object are deferred (needs field→property mapping).
                // Keyed by bare partId (S4: streamingPartTexts is rekeyed from
                // "msgId:partId" to partId, matching the UI consumers).
                val sessionId = event.payload.getString("sessionID") ?: return
                if (sessionId != slices.chat.value.currentSessionId) return
                // messageID required for a well-formed delta event (validation guard).
                val msgId = event.payload.getString("messageID") ?: return
                val partId = event.payload.getString("partID") ?: return
                // §user-part-guard (see message.part.updated): only assistant
                // output streams — skip deltas whose owning message is a user
                // message (the server reflects the user input as a part event).
                if (slices.chat.value.messages.any { it.id == msgId && it.isUser }) return
                // `field` defaults to "text"; it is the type hint for this
                // delta (e.g. "text", "reasoning"). Used as the placeholder
                // partType so a reasoning field-delta routes to ReasoningCard,
                // not a generic text bubble.
                val field = event.payload.getString("field") ?: "text"
                val delta = event.payload.getString("delta")
                if (!delta.isNullOrEmpty()) {
                    val key = partId
                    // §reasoning-routing-fix: prefer the part's KNOWN type (set by
                    // the part.updated creation event) over the delta's `field` —
                    // the server sends field="text" even for reasoning content.
                    val knownType = slices.chat.value.partsByMessage[msgId]
                        ?.firstOrNull { it.id == partId }?.type ?: field
                    // Only streamable types (text/reasoning) are rendered via
                    // streamingPartTexts; a non-streamable type's delta would
                    // accumulate dead overlay text never consumed by PartView.
                    if (!isStreamablePartType(knownType)) return
                    // §M5 delta coalescing: leading-edge
                    // immediate + trailing 100ms coalesce per partId. The FIRST
                    // delta for a partId writes straight to streamingPartTexts
                    // (zero-latency first-token feel); subsequent deltas within
                    // DELTA_COALESCE_MS are buffered and flushed in one batch,
                    // collapsing per-token Compose recompositions into one-per-
                    // window. Keyed by partId (S4: streamingPartTexts is rekeyed
                    // from "msgId:partId" to partId, matching UI consumers).
                    if (flushJobs[key]?.isActive != true) {
                        // Leading edge: write now, then open the coalesce window.
                        slices.mutateChat { c ->
                            c.applyPartDeltaLeadingEdge(
                                partId = key,
                                delta = delta,
                                knownType = knownType,
                                msgId = msgId,
                                sessionId = sessionId
                            ).first.markFlushPending(key).first
                        }
                        scheduleDeltaFlush(key)
                    } else {
                        // Trailing coalesce: buffer; the pending DELTA_COALESCE_MS
                        // flush will append the batch in one state write.
                        slices.mutateChat { c -> c.appendDeltaBuffer(key, delta).first }
                    }
                }
            }
            "permission.asked" -> {
                // §R-19 Sprint 3 P2-4: routed through applySseSideEffects.
                applySseSideEffects(listOf(SseSideEffect.LoadPendingPermissions))
            }
            "question.asked" -> {
                // §Phase1c instrumentation (Issue 1): raw JSON for live-adherence
                // insurance (schema already confirmed: {id, sessionID, questions, tool},
                // no directory) — cheap parity check vs the confirmed contract.
                DebugLog.d("Question", "question.asked raw properties=${event.payload.properties?.toString() ?: "null"}")
                val question = parseQuestionAskedEvent(event)
                if (question != null) {
                    // §Phase1a→1c instrumentation: DebugLog moved here from inside
                    // applyQuestionAsked to keep that transform pure (see L1187 note).
                    val duplicate = slices.sessionList.value.pendingQuestions.any { it.id == question.id }
                    DebugLog.d("Question", "applyQuestionAsked id=${question.id} sid=${question.sessionId} duplicate=$duplicate")
                    slices.mutateSessionList { currentState -> currentState.applyQuestionAsked(question).first }
                } else {
                    applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid question.asked payload")))
                }
            }
            "question.replied", "question.rejected" -> {
                val requestId = event.payload.getString("requestID")
                    ?: event.payload.getString("id")
                if (requestId != null) {
                    // §Phase1a→1c instrumentation: DebugLog moved here from inside
                    // applyQuestionResolved to keep that transform pure (see L1187 note).
                    DebugLog.d("Question", "applyQuestionResolved id=$requestId")
                    slices.mutateSessionList { currentState -> currentState.applyQuestionResolved(requestId).first }
                }
            }
            "todo.updated" -> {
                val sessionId = event.payload.getString("sessionID") ?: return
                val todosArray = event.payload.properties?.get("todos") as? kotlinx.serialization.json.JsonArray ?: return
                val todos = try {
                    Json.decodeFromJsonElement<List<TodoItem>>(todosArray)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid todo.updated payload: ${e.message}")))
                    return
                }
                slices.mutateSessionList { s -> s.applyTodoUpdated(sessionId, todos).first }
            }
            "session.error" -> {
                // §Phase1a instrumentation (Issue 4): capture the FULL raw properties
                // JSON (name/data/message/statusCode + anything else) so the retry/
                // error hydration parser in Phase 4 can be finalized against reality.
                DebugLog.w("Retry", "session.error raw properties=${event.payload.properties?.toString() ?: "null"}")
                // §error-feedback (Issue 4): the server emits session.error with
                // payload { sessionID, error: { name, data: { message, statusCode } } }
                // for rate-limit / quota / provider failures. Two surfaces:
                //   (1) a UiEvent.Error toast (always — the user must know)
                //   (2) attach the error to the current session's last assistant
                //       message (if any non-error one exists) so ErrorCard can
                //       render inline. Error-only turns (error set, no renderable
                //       parts) are otherwise filtered out before render.
                //
                // §R-19 Sprint 3 P2-4: the UiEvent.Error emission is formalized
                // as SseSideEffect.SessionError. The chat-slice mutation (attach
                // error to last assistant message) stays inline — it's a same-
                // domain state transform, not a bus signal.
                val errObj = event.payload.getJsonObject("error")
                val name = (errObj?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content
                val data = errObj?.get("data") as? JsonObject
                val rawMsg = (data?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: (data?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: "Server session error"
                applySseSideEffects(listOf(SseSideEffect.SessionError(name = name, rawMsg = rawMsg)))
                val sid = event.payload.getString("sessionID")
                if (sid != null && sid == slices.chat.value.currentSessionId) {
                    slices.mutateChat { c ->
                        val lastAssistant = c.messages.lastOrNull { it.isAssistant }
                        if (lastAssistant == null || lastAssistant.error != null) c
                        else c.copy(messages = c.messages.map { m ->
                            if (m.id == lastAssistant.id) m.copy(error = Message.MessageError(name = name, data = data)) else m
                        })
                    }
                }
            }
            "sync" -> {
                // §issue-1(3): 全局事件流的同步标记。服务端发布每个 durable 事件时
                // 附带一个 type=sync 的回放封装（event-v2-bridge），上游 web 客户端
                // 直接跳过（server-sdk.tsx:195）。ocdroid 不做多端回放，同样不消费；
                // 显式 case 替代落入 else 的告警——已知且刻意忽略。
            }
            "models-dev.refreshed" -> {
                // §F3: 服务端 models.dev 模型目录刷新通知（上游 schema/models-dev.ts:5，
                // payload 为空对象）。ocdroid 不动态响应目录刷新，显式 no-op 以消除
                // unrecognized event 告警。与 load-more 失效无关。
            }
            "session.idle" -> {
                // §issue-1(2): 已废弃事件（schema/session-status-event.ts:43 标注
                // deprecated）。服务端在 session.status{type:idle} 之后总是再发一次
                // session.idle，二者时序等价。ocdroid 的会话状态（busy/idle、流式
                // 收尾、未读清理）已由上方 "session.status" 分支完整驱动，此处不再
                // 派发。若将来要做"回复就绪"主动通知，应挂在 session.status{idle}
                // （非废弃源）而非本事件——此处仅显式识别以消除 else 告警。
            }
            "session.diff" -> {
                // §issue-1(1): 会话文件变更快照。payload { sessionID, diff[] }，
                // 每条 = SnapshotFileDiff（file/patch/additions/deletions/status）。
                // 解析后写入 SessionListState.sessionDiffs，驱动聊天内 SessionDiffCard。
                // 解析模式镜像 todo.updated（JsonArray → List<@Serializable>）。
                val sessionId = event.payload.getString("sessionID") ?: return
                val diffArray = event.payload.properties?.get("diff") as? kotlinx.serialization.json.JsonArray ?: return
                // §gpter-B：用 lenientJson（ignoreUnknownKeys=true）与 REST getSessionDiff
                // 路径（OpenCodeRepository.json）对齐——默认 Json 严格模式会在上游
                // SnapshotFileDiff 多带字段时整条丢弃，导致 SSE/REST 行为不一致。
                val diffs = try {
                    lenientJson.decodeFromJsonElement<List<cn.vectory.ocdroid.data.model.FileDiff>>(diffArray)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    applySseSideEffects(listOf(SseSideEffect.ReportNonFatal("Ignoring invalid session.diff payload: ${e.message}")))
                    return
                }
                slices.mutateSessionList { it.applySessionDiff(sessionId, diffs).first }
            }
            // §files-events-noop: location-scoped filesystem events. Per
            // opencode-src/v1.17.18/packages/schema/src/filesystem-watcher.ts /
            // filesystem.ts, `file.watcher.updated` and `file.edited` carry NO
            // sessionID — they fire for ANY path change under a watched
            // directory, independent of which session touched the file. The
            // dispatch log will show `session=-` (the SSE frame lacks
            // sessionID) — this is NORMAL, not a missing-field bug. ocdroid
            // does NOT react to these (the Files pane is driven by the file
            // browser's own REST listing, not by these push events); an
            // explicit empty case stops the unrecognized-event warning. A real
            // Files-pane refresh on these events is a tracked follow-up. NOT
            // added to NOISY_SSE_LOG_EVENTS (that set is for high-frequency
            // noise; explicit no-op cases are semantically correct).
            "file.watcher.updated", "file.edited" -> {
            }
            else -> {
                // §R18 Phase 3 Wave 1 (P0-7): surface unrecognized SSE event
                // types instead of silently dropping them. NOISY_SSE_LOG_EVENTS
                // (server.connected / plugin.added / catalog.updated /
                // integration.updated / server.heartbeat / message.part.*)
                // are known-intentional non-dispatched types — skip the warning
                // to avoid log noise on every reconnect / per-token streaming
                // event, but still bump the counter for diagnostics.
                //
                // §test-guard: the existing `plugin.added` test asserts
                // `verify(exactly = 0) { Log.w(...) }`; the `!noisy` gate
                // preserves that contract (DebugLog.w forwards to Log.w).
                if (!noisy) {
                    val keys = event.payload.properties?.keys?.take(5) ?: emptyList()
                    DebugLog.w("SSE", "unrecognized event type=$type session=$evtSession payload-keys=$keys")
                    if (BuildConfig.DEBUG) {
                        effects.tryEmitUiEvent(UiEvent.Debug("unknown SSE: $type"))
                    }
                }
                unknownEventCounters
                    .computeIfAbsent(type) { java.util.concurrent.atomic.AtomicInteger(0) }
                    .incrementAndGet()
            }
        }
    }

    // ── §M5 delta coalescing helpers ────────────────

    /**
     * Opens (or reopens) the [DELTA_COALESCE_MS] trailing-coalesce window for
     * [partId]. Scheduled on the leading-edge delta; while the launched job is
     * alive, subsequent deltas append to [ChatState.deltaBuffer] instead of
     * writing streamingPartTexts. The Job reference is held in [flushJobs]
     * (NOT in the slice — a Job is not a value type); the observable mirror is
     * [ChatState.pendingFlushPartIds], set by [ChatState.markFlushPending] on
     * the leading edge and cleared by [flushDeltaBuffer] / [clearDeltaBuffers].
     */
    private fun scheduleDeltaFlush(partId: String) {
        // Defensive: a stale/completed entry should never coexist with a leading
        // edge (the window self-clears on flush), but cancel anyway to avoid
        // ever having two flush jobs racing for one partId.
        flushJobs[partId]?.cancel()
        flushJobs[partId] = scope.launch {
            delay(DELTA_COALESCE_MS)
            flushDeltaBuffer(partId)
        }
    }

    /**
     * Flushes [partId]'s buffered deltas/fullText into the chat slice's
     * streamingPartTexts in a single atomic write (TOCTOU-safe). Self-removes
     * the partId from [ChatState.pendingFlushPartIds] and from [flushJobs].
     * If the overlay was cleared mid-window (session switch / part.created /
     * ViewModel reset wiped streamingPartTexts), the buffer is dropped —
     * re-injecting stale tokens into the new view would render ghost text from
     * the previous session.
     *
     * §Site1-coalesce: a buffered fullText wins (REPLACE) — it is the server's
     * authoritative accumulated text and supersedes any concurrent delta
     * accumulation for this partId. It is checked BEFORE the delta; if
     * present, streamingPartTexts[partId] is overwritten with the buffered
     * value (REPLACE, not append) and the entry cleared. Only when no fullText
     * is buffered does the delta APPEND path run.
     *
     * §R-17 batch5: the state transform is the pure
     * [ChatState.flushCoalesceBufferForPart] extension; this wrapper only
     * owns the [flushJobs] entry (Job lifecycle, scope-bound — not in the
     * slice).
     */
    private fun flushDeltaBuffer(partId: String) {
        flushJobs.remove(partId)
        slices.mutateChat { c -> c.flushCoalesceBufferForPart(partId).first }
    }

    /**
     * Cancels [partId]'s pending flush and drops its buffers (both delta APPEND
     * and fullText REPLACE) in the slice. Kept for callers that need to
     * supersede the streaming accumulation for a partId with an authoritative
     * snapshot outside the coalesce path; the §Site1-coalesce fullText branch
     * no longer calls this (it lets the leading-edge + trailing pattern handle
     * it), but the helper is retained for correctness/future use.
     */
    @Suppress("unused")
    private fun cancelDeltaFlush(partId: String) {
        flushJobs.remove(partId)?.cancel()
        slices.mutateChat { c -> c.clearCoalesceBufferForPart(partId).first }
    }

    /**
     * Drops ALL pending delta/fullText buffers, cancels their flush jobs, and
     * clears [ChatState.pendingFlushPartIds]. Called when the whole streaming
     * overlay is wiped (part.created now; session switch / SSE stop /
     * ViewModel clear may be wired by the caller — see §4.2). Safe to call
     * repeatedly.
     */
    fun clearDeltaBuffers() {
        flushJobs.values.forEach { it.cancel() }
        flushJobs.clear()
        slices.mutateChat { c -> c.clearAllCoalesceBuffers().first }
    }

    // ── §R18 Phase 3 Wave 1 (P1-9): multi-workdir pending questions fan-out ──

    /**
     * §P1-9: refreshes pending questions across EVERY known workdir (the in-
     * memory `directorySessions` keys + `settingsManager.currentWorkdir`),
     * not just `currentWorkdir`. The single-workdir AppCore dispatch handler
     * for `LoadPendingQuestions` reads only `currentWorkdir`, so a
     * `question.asked` SSE event for any OTHER workdir is fetched-then-
     * immediately-overwritten by the next currentWorkdir poll — background
     * workdirs' questions silently vanish.
     *
     * The coordinator already owns [slices] (so it can read `directorySessions`)
     * + [settingsManager] (currentWorkdir) + [scope]. The repository is passed
     * in because the batch 3b migration left this controller without it
     * (callers fan out via [ControllerEffect]s); AppCore (out of this wave's
     * write scope) will need a one-line wiring update to call this method.
     *
     * Merge semantics: byGet wins, pre-existing fills gaps (mirrors
     * `launchLoadPendingQuestions` so the per-workdir fan-out composes without
     * dropping prior SSE-delivered questions).
     *
     * §scope-note: AppCore needs to call this from its catch-up / switch paths
     * to wire production. The method is exercised directly by
     * [SessionSyncCoordinatorTest].
     */
    fun loadPendingQuestionsAllWorkdirs(repository: OpenCodeRepository) {
        // §issue-1 Phase 2a Fix B: shared workdir-set computation (with per-fp
        // recent_workdirs) — identical to AppCore's catchUpWorkdirs site, via
        // the [computeQuestionFanOutWorkdirs] helper, so the two sites cannot drift.
        val workdirs = computeQuestionFanOutWorkdirs(
            directorySessionKeys = slices.sessionList.value.directorySessions.keys,
            currentWorkdir = settingsManager.currentWorkdir,
            recentWorkdirs = settingsManager.getRecentWorkdirs(currentServerGroupFp()),
        )
        // §Phase1a instrumentation (Issue 1): the full workdir SET being fanned out.
        DebugLog.d("Question", "loadPendingQuestionsAllWorkdirs fanOut=${workdirs.size} workdirs=$workdirs")
        if (workdirs.isEmpty()) return
        // §badge-stale-fix: fan out to EVERY known workdir in parallel, then
        // reconcile AUTHORITATIVELY (server is source of truth). Unlike the
        // single-workdir optimistic path [launchLoadPendingQuestions] — which
        // keeps locally-held questions to avoid flicker — this sweep covers the
        // full known-workdir set at once, so its union is authoritative. A
        // question the server no longer returns (resolved without the client
        // receiving the resolve event, e.g. a missed SSE gap while backgrounded)
        // is dropped here instead of lingering as a ghost that keeps the
        // Sessions nav badge lit forever. Matches launchLoadPendingPermissions
        // (full replace) semantics.
        //
        // Race-safety: a question.asked SSE event that lands DURING the fan-out
        // (after the start snapshot, not yet in any in-flight GET response) is
        // preserved — only questions present at start AND absent from the server
        // response are treated as resolved-and-dropped.
        scope.launch {
            val startIds = slices.sessionList.value.pendingQuestions
                .mapTo(mutableSetOf()) { it.id }
            val fetched = workdirs.map { dir ->
                async {
                    repository.getPendingQuestions(dir)
                        .onSuccess { questions ->
                            DebugLog.d("Question", "loadPendingQuestionsAllWorkdirs dir=$dir count=${questions.size}")
                        }
                        .onFailure { error ->
                            DebugLog.w(tag, "fan-out getPendingQuestions failed for $dir: ${error.message}")
                        }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
            val fetchedIds = mutableSetOf<String>()
            // §task5-ghost (final-review fix 2): snapshot the three-source
            // sessions map BEFORE the merge so the filter can identify
            // archived-session questions. A question whose session is marked
            // archived in the local snapshot is dropped here even if the server
            // still returns it — the archive reducer already cleared it from
            // the presentation domain, and letting it back in would relight
            // the Sessions nav badge for a session the user cannot open.
            val slSnap = slices.sessionList.value
            val sessionsById = allSessionsById(
                slSnap.sessions,
                slSnap.directorySessions,
                slSnap.childSessions,
            )
            val authoritative = buildList {
                fetched.flatten().forEach { if (fetchedIds.add(it.id)) add(it) }
                slSnap.pendingQuestions.forEach { q ->
                    if (q.id !in fetchedIds && q.id !in startIds) add(q)
                }
            }.let { filterArchivedSessionQuestions(it, sessionsById) }
            slices.mutateSessionList { it.copy(pendingQuestions = authoritative) }
            DebugLog.d("Question", "loadPendingQuestionsAllWorkdirs authoritative reconcile total=${authoritative.size} (had ${startIds.size} before)")
        }
    }

    companion object {
        /**
         * §M5 trailing-coalesce window (§7). Leading-edge
         * delta writes immediately; subsequent deltas within this window are
         * batched into one flush → one Compose recomposition per window instead
         * of one per token.
         */
        private const val DELTA_COALESCE_MS = 100L
    }
}

// ── §R-17 batch5 → §R-19 Sprint 3 P2-4: pure state transforms for each SSE event branch ──
//
// Each function takes the prior slice value + the event payload and returns
// `Pair<State, List<SseSideEffect>>` — the new state AND a list of side
// effects the dispatcher should commit via `applySseSideEffects`. Pure: no
// effect emits, no coroutine launches, no settings writes. All DebugLog /
// diagnostic calls live in the dispatcher (the `when` branches above), NOT
// inside these functions, so the transforms stay pure + testable. Most return
// `emptyList()` (the transform is pure state-only); the dispatcher adds
// cross-slice effects (which need context the single-slice function doesn't
// have) and routes the combined list.
//
// §R-19 Sprint 3 P2-4: the signature unification (all return Pair) is the
// formalization step — the dispatcher's CAS pattern is now uniform across all
// 11 branches, and the effect sequencing is auditable in one place.

/**
 * session.created → upsert the parsed [Session] into [SessionListState.sessions].
 * Pure; effects empty (the dispatcher handles parse-failure via ReportNonFatal).
 */
internal fun SessionListState.applySessionCreated(session: Session): Pair<SessionListState, List<SseSideEffect>> =
    upsertAndInvalidateTree(session) to emptyList()

/**
 * session.updated (non-archived) → upsert the parsed [Session] into
 * [SessionListState.sessions]. Pure; effects empty.
 */
internal fun SessionListState.applySessionUpsert(updated: Session): Pair<SessionListState, List<SseSideEffect>> =
    upsertAndInvalidateTree(updated) to emptyList()

private fun SessionListState.upsertAndInvalidateTree(session: Session): SessionListState {
    val updatedSessions = upsertSession(sessions, session)
    val byId = allSessionsById(updatedSessions, directorySessions, childSessions)
    val rootId = rootIdOf(session.id, byId)
    return copy(
        sessions = updatedSessions,
        // An unresolved parent chain cannot be attributed safely. Invalidate
        // every completeness proof rather than risk a false "all descendants
        // idle" result until hydration reconnects the new node to its root.
        completeRootIds = if (rootId == null) emptySet() else completeRootIds - rootId,
        // §gpter-blocker: bump the invalidation epoch so any in-flight
        // hydration (started before this SSE event) drops its result at
        // commit instead of re-certifying the now-stale root.
        completenessEpoch = completenessEpoch + 1L,
    )
}

/**
 * §recent-sort-by-message: when a session receives a message, bump its
 * [Session.time.updated] to `max(current, [updated])` so the
 * "recent sessions" surfaces (sortedByDescending { time.updated }) reflect
 * actual message activity rather than just the server-side session metadata
 * timestamp. Mirrors [applyArchiveEviction]'s two-store pattern: BOTH the
 * top-level `sessions` list AND every `directorySessions` bucket are bumped
 * (a session can be present in either / both stores, and both feed the
 * SessionsScreen / SessionPickerSheet recent derivations).
 *
 * Idempotent + monotonic (see [Session.TimeInfo.withUpdatedAtLeast]) — repeated
 * bumps for the same message are a no-op, and out-of-order / replayed events
 * never go backwards. Pure.
 */
internal fun SessionListState.applyMessageTimestampBump(
    sessionId: String,
    updated: Long
): Pair<SessionListState, List<SseSideEffect>> {
    if (updated <= 0L) return this to emptyList()
    val newSessions = sessions.map { s ->
        if (s.id == sessionId) s.copy(time = s.time.withUpdatedAtLeast(updated)) else s
    }
    val newDirectorySessions = directorySessions.mapValues { (_, list) ->
        list.map { s ->
            if (s.id == sessionId) s.copy(time = s.time.withUpdatedAtLeast(updated)) else s
        }
    }
    return copy(sessions = newSessions, directorySessions = newDirectorySessions) to emptyList()
}

/**
 * session.updated (archived) → upsert the session AND rewrite [openSessionIds]
 * to the caller-supplied [newOpenIds] (with the archived id evicted). The
 * caller computes [newOpenIds] + persists it via SettingsManager (a side
 * effect that stays inline). Pure; effects empty.
 */
internal fun SessionListState.applyArchiveEviction(
    updated: Session,
    newOpenIds: List<String>
): Pair<SessionListState, List<SseSideEffect>> = copy(
    sessions = upsertSession(sessions, updated),
    directorySessions = directorySessions.mapValues { (_, list) ->
        list.map { session -> if (session.id == updated.id) updated else session }
    },
    openSessionIds = newOpenIds
) to emptyList()

/**
 * The chat-side archive clear when the archived session IS the currently-open
 * one: drop currentSessionId + messages + partsByMessage so the chat view
 * falls back to the empty state. Pure; effects empty.
 *
 * FIX-B (review-blocker, groker B3): also clears [pendingJumpToLatest] (added
 * by WT2). The clearSessionData path already clears it, but this archive-clear
 * path was missed → a one-shot "jump to latest" intent could stick if the
 * session was archived between the intent being set and consumed. Now the
 * intent is wiped atomically with the rest of the chat clear (one committed
 * state, no torn "session archived but jump intent still references it").
 */
internal fun ChatState.applyArchivedChatClear(): Pair<ChatState, List<SseSideEffect>> = copy(
    currentSessionId = null,
    messages = emptyList(),
    partsByMessage = emptyMap(),
    // FIX-B: clear the jump intent so it does not survive the archive.
    pendingJumpToLatest = null,
) to emptyList()

/**
 * session.status → upsert the [sessionId] → [status] pair into
 * [SessionListState.sessionStatuses]. Pure; effects empty — the busy-current /
 * idle-current finalization side effects are computed by the dispatcher (they
 * depend on cross-slice state: chat.currentSessionId,
 * chat.streamingPartTexts).
 */
internal fun SessionListState.applySessionStatus(
    sessionId: String,
    status: SessionStatus
): Pair<SessionListState, List<SseSideEffect>> =
    copy(sessionStatuses = sessionStatuses + (sessionId to status)) to emptyList()

/**
 * message.updated → patch-if-found / insert-if-absent [updated] into
 * [ChatState.messages]. Returns the new state AND a `found` flag (so the
 * caller can log patch vs. insert without a second O(n) scan). Single O(n)
 * atomic pass — no TOCTOU. Pure.
 *
 * When [found] is false, the new message is appended at the tail (the list
 * is oldest-first and the new message is the newest — mirrors opencode-web).
 */
internal fun ChatState.applyMessageUpdated(updated: Message): Pair<ChatState, Boolean> {
    var found = false
    val newMessages = messages.map { if (it.id == updated.id) { found = true; updated } else it }
    val finalMessages = if (found) newMessages else newMessages + updated
    return copy(messages = finalMessages) to found
}

/**
 * message.part.updated (blank reasoning creation / type-correct placeholder) →
 * inject a [Part] of [partType] into [ChatState.partsByMessage][msgId] AND
 * set [ChatState.streamingReasoningPart] when [partType] == "reasoning".
 * Idempotent — once a Part of the correct type exists this is a no-op (caller
 * guards `hasCorrectType`, but this is defensive: it filters out any stale
 * wrong-typed placeholder first). Pure.
 */
internal fun ChatState.applyPartCreatedPlaceholder(
    partType: String,
    partId: String,
    msgId: String,
    sessionId: String
): Pair<ChatState, List<SseSideEffect>> {
    val base = partsByMessage[msgId]?.filterNot { it.id == partId } ?: emptyList()
    return copy(
        streamingReasoningPart = reasoningPartOrNull(partType, partId, msgId, sessionId)
            ?: streamingReasoningPart,
        partsByMessage = partsByMessage + (msgId to base + Part(
            id = partId, messageId = msgId, sessionId = sessionId, type = partType
        ))
    ) to emptyList()
}

/**
 * Leading-edge fullText write: REPLACE [ChatState.streamingPartTexts][partId]
 * with [fullText] (authoritative accumulated text), set
 * [ChatState.streamingReasoningPart] if [partType] == "reasoning", and ensure
 * the placeholder [Part] exists. Pure.
 *
 * Caller follows up with [markFlushPending] + [scheduleDeltaFlush] (the Job
 * scheduling is a side effect that stays on the coordinator).
 */
internal fun ChatState.applyPartFullTextLeadingEdge(
    partId: String,
    fullText: String,
    partType: String,
    pId: String,
    msgId: String,
    sessionId: String
): Pair<ChatState, List<SseSideEffect>> = copy(
    streamingPartTexts = streamingPartTexts + (partId to fullText),
    streamingReasoningPart = reasoningPartOrNull(
        partType = partType,
        partId = pId,
        messageId = msgId,
        sessionId = sessionId
    ) ?: streamingReasoningPart,
    partsByMessage = ensurePlaceholderPart(partsByMessage, msgId, pId, sessionId, partType)
) to emptyList()

/**
 * Leading-edge delta write: APPEND [delta] to the prior
 * [ChatState.streamingPartTexts][partId] value, set
 * [ChatState.streamingReasoningPart] if the resolved type is "reasoning", and
 * ensure the placeholder [Part] exists. Pure.
 *
 * Caller follows up with [markFlushPending] + [scheduleDeltaFlush] (the Job
 * scheduling is a side effect that stays on the coordinator).
 */
internal fun ChatState.applyPartDeltaLeadingEdge(
    partId: String,
    delta: String,
    knownType: String,
    msgId: String,
    sessionId: String
): Pair<ChatState, List<SseSideEffect>> {
    val previous = streamingPartTexts[partId].orEmpty()
    return copy(
        streamingPartTexts = streamingPartTexts + (partId to (previous + delta)),
        streamingReasoningPart = reasoningPartOrNull(knownType, partId, msgId, sessionId)
            ?: streamingReasoningPart,
        partsByMessage = ensurePlaceholderPart(partsByMessage, msgId, partId, sessionId, knownType)
    ) to emptyList()
}

/** Variant of [applyPartDeltaLeadingEdge] taking the part.updated partType +
 *  explicit pId (used by the message.part.updated delta branch where the
 *  known-type lookup hasn't run yet). Pure. */
internal fun ChatState.applyPartDeltaLeadingEdge(
    partId: String,
    delta: String,
    partType: String,
    pId: String,
    msgId: String,
    sessionId: String
): Pair<ChatState, List<SseSideEffect>> {
    val previous = streamingPartTexts[partId].orEmpty()
    return copy(
        streamingPartTexts = streamingPartTexts + (partId to (previous + delta)),
        streamingReasoningPart = reasoningPartOrNull(
            partType = partType,
            partId = pId,
            messageId = msgId,
            sessionId = sessionId
        ) ?: streamingReasoningPart,
        partsByMessage = ensurePlaceholderPart(partsByMessage, msgId, pId, sessionId, partType)
    ) to emptyList()
}

/**
 * Trailing-coalesce delta APPEND: append [delta] to the slice's
 * [ChatState.deltaBuffer][partId] (the buffer is now a `Map<String, String>`,
 * so the append is a read-modify-write under the slice's CAS `update`).
 * Pure. */
internal fun ChatState.appendDeltaBuffer(partId: String, delta: String): Pair<ChatState, List<SseSideEffect>> {
    val current = deltaBuffer[partId].orEmpty()
    return copy(deltaBuffer = deltaBuffer + (partId to (current + delta))) to emptyList()
}

/**
 * Trailing-coalesce fullText REPLACE: overwrite [ChatState.fullTextBuffer][partId]
 * with [text] (REPLACE — fullText is the server-authoritative accumulated
 * text; only the latest value per partId matters). Pure.
 */
internal fun ChatState.replaceFullTextBuffer(partId: String, text: String): Pair<ChatState, List<SseSideEffect>> =
    copy(fullTextBuffer = fullTextBuffer + (partId to text)) to emptyList()

/**
 * Marks [partId] as having a pending flush (the slice-side mirror of the
 * coordinator's [SessionSyncCoordinator.flushJobs] entry). Set on the
 * leading-edge write so an observer of the slice can detect "deltas still
 * buffered" without needing access to the Job. Pure.
 */
internal fun ChatState.markFlushPending(partId: String): Pair<ChatState, List<SseSideEffect>> =
    if (partId in pendingFlushPartIds) this to emptyList()
    else copy(pendingFlushPartIds = pendingFlushPartIds + partId) to emptyList()

/**
 * Flushes [partId]'s buffered delta/fullText into [ChatState.streamingPartTexts]
 * in a single atomic pure transform, then clears that partId's three coalesce
 * entries ([deltaBuffer] / [fullTextBuffer] / [pendingFlushPartIds]).
 *
 * §Site1-coalesce: a buffered fullText wins (REPLACE) over a buffered delta
 * (APPEND), and is checked FIRST. If the overlay was wiped mid-window
 * (`streamingPartTexts[partId] == null`), both buffers are dropped (stale;
 * re-injecting would render ghost text from the previous session).
 *
 * Mirrors the verbatim semantics of the pre-batch5 imperative
 * `flushDeltaBuffer` (the only change is storage location). Pure.
 */
internal fun ChatState.flushCoalesceBufferForPart(partId: String): Pair<ChatState, List<SseSideEffect>> {
    val overlayPresent = streamingPartTexts[partId] != null
    // REPLACE path: fullText wins outright when the overlay still exists.
    val bufferedFullText = fullTextBuffer[partId]
    if (overlayPresent && bufferedFullText != null) {
        // The authoritative fullText supersedes any concurrent delta
        // accumulation for this partId; drop the stale buffered delta so
        // it can't be appended in a later coalesce window.
        return copy(
            streamingPartTexts = streamingPartTexts + (partId to bufferedFullText),
            deltaBuffer = deltaBuffer - partId,
            fullTextBuffer = fullTextBuffer - partId,
            pendingFlushPartIds = pendingFlushPartIds - partId
        ) to emptyList()
    }
    // If the overlay was wiped mid-window, drop the buffered fullText (stale).
    // Otherwise keep fullTextBuffer intact (no fullText buffered for this
    // partId; the entry was already absent).
    val fullTextBufferAfter = if (overlayPresent) fullTextBuffer else fullTextBuffer - partId
    val bufferedDelta = deltaBuffer[partId]
    if (bufferedDelta.isNullOrEmpty()) {
        return copy(
            fullTextBuffer = fullTextBufferAfter - partId,
            deltaBuffer = deltaBuffer - partId,
            pendingFlushPartIds = pendingFlushPartIds - partId
        ) to emptyList()
    }
    // Overlay wiped mid-window → drop stale buffered delta (re-injecting
    // would render ghost text from the previous session).
    if (!overlayPresent) {
        return copy(
            fullTextBuffer = fullTextBufferAfter - partId,
            deltaBuffer = deltaBuffer - partId,
            pendingFlushPartIds = pendingFlushPartIds - partId
        ) to emptyList()
    }
    // APPEND path: append the buffered delta to the overlay.
    val previous = streamingPartTexts[partId].orEmpty()
    return copy(
        streamingPartTexts = streamingPartTexts + (partId to (previous + bufferedDelta)),
        fullTextBuffer = fullTextBufferAfter - partId,
        deltaBuffer = deltaBuffer - partId,
        pendingFlushPartIds = pendingFlushPartIds - partId
    ) to emptyList()
}

/**
 * Drops [partId]'s three coalesce entries without flushing (the buffers are
 * discarded, NOT applied to streamingPartTexts). The slice-side companion of
 * [SessionSyncCoordinator.cancelDeltaFlush]. Pure.
 */
internal fun ChatState.clearCoalesceBufferForPart(partId: String): Pair<ChatState, List<SseSideEffect>> = copy(
    deltaBuffer = deltaBuffer - partId,
    fullTextBuffer = fullTextBuffer - partId,
    pendingFlushPartIds = pendingFlushPartIds - partId
) to emptyList()

/**
 * Drops ALL coalesce entries (deltaBuffer / fullTextBuffer /
 * pendingFlushPartIds). Does NOT touch streamingPartTexts /
 * streamingReasoningPart — the overlay clear is a separate concern (the
 * §闪屏修复 made part.created preserve the overlay; only the buffers are
 * dropped). The slice-side companion of
 * [SessionSyncCoordinator.clearDeltaBuffers]. Pure.
 */
internal fun ChatState.clearAllCoalesceBuffers(): Pair<ChatState, List<SseSideEffect>> = copy(
    deltaBuffer = emptyMap(),
    fullTextBuffer = emptyMap(),
    pendingFlushPartIds = emptySet()
) to emptyList()

/**
 * question.asked → append [question] to [SessionListState.pendingQuestions]
 * iff its id is not already present (idempotent). Pure.
 */
internal fun SessionListState.applyQuestionAsked(question: QuestionRequest): Pair<SessionListState, List<SseSideEffect>> {
    val existing = pendingQuestions.any { it.id == question.id }
    return (if (existing) this else copy(pendingQuestions = pendingQuestions + question)) to emptyList()
}

/**
 * question.replied / question.rejected → drop the pending question whose id
 * matches [requestId]. Pure.
 */
internal fun SessionListState.applyQuestionResolved(requestId: String): Pair<SessionListState, List<SseSideEffect>> =
    copy(pendingQuestions = pendingQuestions.filter { it.id != requestId }) to emptyList()

/**
 * todo.updated → upsert [todos] under [sessionId] in
 * [SessionListState.sessionTodos]. Pure.
 */
internal fun SessionListState.applyTodoUpdated(
    sessionId: String,
    todos: List<TodoItem>
): Pair<SessionListState, List<SseSideEffect>> =
    copy(sessionTodos = sessionTodos + (sessionId to todos)) to emptyList()

/**
 * §issue-1(1): session.diff → 替换 [sessionId] 的文件变更快照。服务端每次发布的是
 * 该会话当前的完整 diff 列表（非增量），故整体替换。Pure。
 */
internal fun SessionListState.applySessionDiff(
    sessionId: String,
    diffs: List<cn.vectory.ocdroid.data.model.FileDiff>
): Pair<SessionListState, List<SseSideEffect>> =
    copy(sessionDiffs = sessionDiffs + (sessionId to diffs)) to emptyList()

/**
 * §issue-1(1) REST 预取专用：仅当 [sessionId] 尚无 diff 条目时写入（stale-overwrite
 * 守卫，glmer-S1）。SSE [applySessionDiff] 是权威源、无条件覆盖；REST 仅乐观预取，若
 * SSE 已推过更新则跳过，避免在途 REST 旧结果覆盖更新的 SSE 快照。抽成纯函数以便单测
 * （maxer 复审：覆盖维度）。镜像上游 web client `diff()` 的 `if defined && !force return`。
 */
internal fun SessionListState.applySessionDiffIfAbsent(
    sessionId: String,
    diffs: List<cn.vectory.ocdroid.data.model.FileDiff>
): Pair<SessionListState, List<SseSideEffect>> =
    (if (sessionDiffs[sessionId] != null) this
    else copy(sessionDiffs = sessionDiffs + (sessionId to diffs))) to emptyList()

/**
 * session lifecycle completion (root busy→idle) → mark [sessionId] unread IF
 * it is not the currently-open session. Also used as the REST reconnect
 * backstop. Caller passes [currentSessionId] so this stays pure (no slice
 * read). Pure.
 */
internal fun UnreadState.applyMarkSessionUnread(
    sessionId: String,
    currentSessionId: String?
): Pair<UnreadState, List<SseSideEffect>> =
    (if (sessionId == currentSessionId) this
    else copy(unreadSessions = unreadSessions + sessionId)) to emptyList()

// ── §streaming-render placeholder injection ────────────────

/**
 * Ensures a minimal placeholder [Part] exists in [partsByMessage] for the
 * given [msgId]/[pId] so a `ChatMessageRow.PartView` is composed immediately
 * and consumes the streaming override (`streamingTextOverride ?: part.text`)
 * while SSE deltas accumulate. The placeholder has `text = null`, so the
 * streaming override wins; when the REST reload later fires it replaces
 * `partsByMessage[msgId]` wholesale, overwriting the placeholder with the
 * real Part — no conflict.
 *
 * No-op when [partsByMessage] already has a Part with `id == pId` for [msgId]
 * (the common case once the first placeholder has been inserted, or once the
 * REST reload has brought the real parts in).
 *
 * Type guard: only `text` and `reasoning` parts are streamed through
 * `streamingPartTexts` and rendered by `PartView`'s `TextPart` /
 * `ReasoningCard`. Other part types (`tool`, `patch`, `file`, `step-start`,
 * `step-finish`, …) are NOT streamed this way — injecting a placeholder for
 * them would misroute in `PartView` (e.g. a `type="tool"` placeholder with
 * `tool=null` renders an empty tool card; `step-*` has no render branch and
 * the streaming text would be orphaned). For those types this is a no-op.
 * They get their real `Part` from the REST reload as before.
 */
internal fun ensurePlaceholderPart(
    partsByMessage: Map<String, List<Part>>,
    msgId: String,
    pId: String,
    sessionId: String,
    partType: String
): Map<String, List<Part>> {
    // Only text/reasoning parts stream via streamingPartTexts. Skip
    // placeholder injection for any other type — they would misroute in
    // PartView and the streaming text would never be consumed.
    if (partType != "text" && partType != "reasoning") return partsByMessage
    val existing = partsByMessage[msgId]
    return when {
        existing == null -> partsByMessage + (msgId to listOf(Part(id = pId, messageId = msgId, sessionId = sessionId, type = partType)))
        existing.none { it.id == pId } -> partsByMessage + (msgId to (existing + Part(id = pId, messageId = msgId, sessionId = sessionId, type = partType)))
        else -> partsByMessage
    }
}

/**
 * §R-17 batch5: helper for tests / future state inspectors — is the SSE
 * coalesce window currently open for [partId]? Pure read on [ChatState].
 */
internal fun ChatState.isFlushPending(partId: String): Boolean =
    partId in pendingFlushPartIds
