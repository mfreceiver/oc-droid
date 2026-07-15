package cn.vectory.ocdroid.ui

import android.content.Context
import cn.vectory.ocdroid.data.model.*
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.service.bridge.SseEventBridge
import cn.vectory.ocdroid.service.events.SseEventStream
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.TrafficTracker
import cn.vectory.ocdroid.ui.controller.CachedSessionWindow
import cn.vectory.ocdroid.ui.controller.ComposerController
import cn.vectory.ocdroid.ui.controller.ConnectionCoordinator
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.ui.controller.ForegroundCatchUpController
import cn.vectory.ocdroid.ui.controller.HostProfileController
import cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator
import cn.vectory.ocdroid.ui.controller.SessionSwitcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * §R18 Phase 2-G: this constant was the hardcoded success-toast text for
 * tunnel activation. The toast now rides `UiEvent.Success(R.string
 * .success_tunnel_activated)` (i18n'd). The constant is retained only as
 * a documentation anchor — production code MUST NOT reference it (it has
 * no runtime value post-i18n). Tests assert on the resId directly.
 */
@Deprecated("Use R.string.success_tunnel_activated via UiEvent.Success(resId).")
const val TUNNEL_SUCCESS_TOAST: String = "隧道激活成功"

/**
 * R-17 batch3 → batch3d: application-scoped engine that owns the 6 controllers
 * + the cross-domain orchestration logic (only ~6 methods that span 3+ domains).
 *
 * **batch3d redesign**: the 6 domain ViewModels ([ChatViewModel],
 * [SessionViewModel], [ConnectionViewModel], [HostViewModel],
 * [ComposerViewModel], [OrchestratorViewModel]) now PHYSICALLY OWN their
 * domain method bodies (moved here). Each VM reaches its domain controller +
 * the shared [store] + the [effectBus] directly — no `core.<method>()`
 * self-bypass. AppCore retains only:
 *
 *  - constructor (builds the 6 controllers + the app-lifetime [appScope])
 *  - [init] (loads saved settings + subscribes to the [effectBus] and
 *    dispatches each [ControllerEffect] to the matching helper below)
 *  - [dispatchEffect] (the effect-bus → controller/method router)
 *  - the ~6 genuinely cross-domain orchestration methods:
 *    [sendMessage] (composer→chat→session creation),
 *    [openSessionFromDeepLink] (nav→session→chat),
 *    [executeCommand] (/clear → composer+session),
 *    [resetLocalDataAndResync] (full-stack reset).
 *  - private/internal dispatch helpers (one per [ControllerEffect] branch —
 *    each calls the same controller / free function the matching VM method
 *    uses; AppCore cannot reference the VMs because Hilt ViewModels are not
 *    @Inject-able dependencies).
 *  - [cleanup] (ProcessLifecycleOwner teardown).
 *
 * The slice public read accessors (`chatFlow`, `sessionListFlow`, ...) stay
 * so legacy subscribers (composables mid-migration, tests, [uiEvents]) keep
 * resolving; writes flow through the [store] / [writeXxx] helpers which are
 * `internal` so the VMs share the same authoritative slice.
 *
 * The 6 HiltViewModels inject this class and expose ONLY their domain surface
 * to composables. Composables inject those VMs (NOT this class).
 */
@Singleton
@OptIn(FlowPreview::class)
class AppCore @Inject constructor(
    internal val store: SharedStateStore,
    internal val repository: OpenCodeRepository,
    internal val settingsManager: SettingsManager,
    internal val hostProfileStore: HostProfileStore,
    internal val trafficTracker: TrafficTracker,
    private val appLifecycleMonitor: AppLifecycleMonitor,
    private val serverCompatProfile: cn.vectory.ocdroid.data.repository.ServerCompatProfile,
    /** R-17 batch3: shared bus for cross-VM effect dispatch. Controllers emit
     *  [ControllerEffect]s on [SharedEffectBus.effects]; this class collects
     *  them in its [init] block and dispatches. UiEvents ride
     *  [SharedEffectBus.uiEvents]. */
    internal val effectBus: SharedEffectBus,
    /** §R18 Phase 2-G (P0-6): application Context used to resolve
     *  [UiEvent.Error]'s `@StringRes resId` + args to a localized String
     *  before forwarding to [AppLifecycleMonitor.onAppError] (whose
     *  notification body needs a real String). Composable collectors
     *  resolve via [LocalContext] instead; this Context serves only the
     *  app-lifetime notification path that has no Composition available. */
    @ApplicationContext internal val appContext: Context,
    /** R-19 Sprint 3 P2-5: the 5 application-scoped controllers are now
     *  Hilt-injected (@Singleton via [cn.vectory.ocdroid.di.ControllerModule])
     *  instead of being constructed inline here. This lets the per-domain
     *  ViewModels inject the SAME singleton instances directly (precise
     *  injection — see R-19 P2-5) without `core.<controller>` reach-through
     *  and without duplicating controller instances. AppCore retains the
     *  effect-bus collector → [dispatchEffect] routing + the cross-domain
     *  orchestration methods; it does not construct the controllers. */
    internal val foregroundCatchUpController: ForegroundCatchUpController,
    internal val composerController: ComposerController,
    internal val sessionSwitcher: SessionSwitcher,
    internal val hostProfileController: HostProfileController,
    internal val sessionSyncCoordinator: SessionSyncCoordinator,
    internal val connectionCoordinator: ConnectionCoordinator,
    /**
     * §unread-soak: the foreground sweep that owns the new "unread"
     * population logic (replaces the old instant busy→idle marker). Injected
     * here purely so Hilt constructs the @Singleton early — its init block
     * subscribes to [cn.vectory.ocdroid.di.AppLifecycleMonitor.isInForeground]
     * and self-starts/stops the sweep. AppCore never calls into it directly.
     */
    internal val unreadSoakController: cn.vectory.ocdroid.ui.controller.UnreadSoakController,
    /**
     * R-20 Phase 1 (review-fix #1): provider for the current host's
     * serverGroupFp. Injected via the SAME `@Named("currentServerGroupFp")`
     * @Provides that every controller uses (ControllerModule), so AppCore's
     * fp derivation never drifts from the controllers'. Used by the
     * VerifyAndHydrate handler's post-peek 二次重检 (the in-memory
     * peekSessionWindow read is synchronous and main-confined, so it cannot
     * straddle a host switch; the re-check is retained as defence-in-depth
     * to lock the composite-key (fp + sessionId) invariant at the handler
     * boundary and stays robust if a dispatcher hop is ever reintroduced).
     */
    @Named("currentServerGroupFp") internal val currentServerGroupFp: () -> String,
    /** R-19 P2-5: the app-lifetime scope is now Hilt-provided
     *  ([cn.vectory.ocdroid.di.UiApplicationScopeModule], Main.immediate) so
     *  the migrated ViewModels inject the SAME singleton scope AppCore and
     *  the controllers use. AppCore keeps the field `internal` because the
     *  R-19-P2-2 dispatch helpers (in this file) and the AppCoreOrchestration
     *  extensions reach `appScope` directly. */
    @UiApplicationScope internal val appScope: CoroutineScope,
    /**
     * CP1 (notify Phase-0): the single connection-identity store. Injected
     * here so the test hook [handleSSEEvent] can auto-wrap raw SSEEvent with
     * the current identity (production goes through CC.launchSseCollection
     * which captures the identity at collection start). Hilt auto-provides
     * the @Singleton instance (same one CC / SSC / HPC inject).
     */
    internal val identityStore: ConnectionIdentityStore,
    /**
     * CP3 (notify Phase-0): the process-wide SSE event stream. CC publishes
     * each [cn.vectory.ocdroid.service.events.IdentifiedSseEvent] here (CP3
     * replaced the direct ControllerEffect.OnSseEvent emission). Injected
     * here so AppCore can pass it to the bridge.
     */
    internal val sseEventStream: SseEventStream,
    /**
     * CP3 (notify Phase-0): the identity-checked SSE event bridge. Subscribes
     * to [sseEventStream.events], validates epoch (§2), routes to the §11
     * control/delta dual-channel. AppCore collects both channels and re-emits
     * them as [ControllerEffect.OnSseEvent] for SSC's identity-checked fold.
     */
    internal val sseEventBridge: SseEventBridge,
) {

    // ── Slice accessors (delegate to SharedStateStore) ──────────────────────
    // §R18 Phase 4 (P0-9): SharedStateStore now owns private MutableStateFlows
    // + public read-only StateFlow views + public mutateXxx write funnels.
    // These accessors re-expose the read views (StateFlow) and the write
    // helpers (delegating to store.mutateXxx) so the 6 VMs / orchestration
    // extensions keep resolving unchanged.
    val connectionFlow: StateFlow<ConnectionState> get() = store.connectionFlow
    val trafficFlow: StateFlow<TrafficState> get() = store.trafficFlow
    val composerFlow: StateFlow<ComposerState> get() = store.composerFlow
    val fileFlow: StateFlow<FileState> get() = store.fileFlow
    val settingsFlow: StateFlow<SettingsState> get() = store.settingsFlow
    val chatFlow: StateFlow<ChatState> get() = store.chatFlow
    val sessionListFlow: StateFlow<SessionListState> get() = store.sessionListFlow
    val unreadFlow: StateFlow<UnreadState> get() = store.unreadFlow
    val hostFlow: StateFlow<HostState> get() = store.hostFlow
    val expandedParts: StateFlow<Map<String, Boolean>> get() = store.expandedParts
    val navFlow: StateFlow<NavState> get() = store.navFlow

    val uiEvents: SharedFlow<UiEvent> get() = effectBus.uiEventsConsumed

    // ── Slice write helpers (delegate to SharedStateStore.mutateXxx). ───────
    // Kept `internal` so the 6 VMs / orchestration extensions keep resolving
    // unchanged. The single authoritative writer per slice is now
    // SharedStateStore.mutateXxx; these are thin pass-throughs.
    internal fun writeConnection(transform: (ConnectionState) -> ConnectionState) = store.mutateConnection(transform)
    internal fun writeTraffic(transform: (TrafficState) -> TrafficState) = store.mutateTraffic(transform)
    internal fun writeComposer(transform: (ComposerState) -> ComposerState) = store.mutateComposer(transform)
    internal fun writeFile(transform: (FileState) -> FileState) = store.mutateFile(transform)
    internal fun writeSettings(transform: (SettingsState) -> SettingsState) = store.mutateSettings(transform)
    internal fun writeChat(transform: (ChatState) -> ChatState) = store.mutateChat(transform)
    internal fun writeSessionList(transform: (SessionListState) -> SessionListState) = store.mutateSessionList(transform)
    internal fun writeUnread(transform: (UnreadState) -> UnreadState) = store.mutateUnread(transform)
    internal fun writeHost(transform: (HostState) -> HostState) = store.mutateHost(transform)
    internal fun writeSessionWindow(serverGroupFp: String, sessionId: String, window: CachedSessionWindow) {
        sessionSwitcher.writeSessionWindow(serverGroupFp, sessionId, window)
    }

    /**
     * R-20 Phase 1: factory for the `onCacheWindow` hook threaded through the
     * 6 message-fetch callsites (MessageActions launchLoadMessages /
     * launchLoadMoreMessages; CatchUpActions launchCatchUp;
     * SessionViewModel.launchLoadMessagesForEffect; AppCoreOrchestration.
     * loadMessagesForEffect). Per plan §3 (v4 glmer I-3 + freegpt) the
     * closure captures [fp] AT FACTORY TIME so a profile switch mid-flight
     * cannot re-key a write to the wrong group.
     *
     * remove-message-persistence Task 3: the hook now performs ONLY the
     * synchronous in-memory LRU write ([SessionSwitcher.writeSessionWindow]).
     * The previous async `cacheRepository.putSessionWindow` fire-and-forget
     * write was deleted — the process-in LRU is the sole cache layer now.
     * `createdAt` / `workdir` lookup was deleted alongside the persistent
     * write (it existed only to feed putSessionWindow's metadata columns).
     *
     * §review-fix #2 (gpter #2): the memory LRU write uses the CAPTURED
     * [fp] (not a re-read of currentServerGroupFp). A host switch
     * mid-flight would otherwise route the old fetch's data into the new
     * group's LRU slot.
     */
    internal fun makeCacheHook(fp: String): (String, CachedSessionWindow) -> Unit = { sid, window ->
        sessionSwitcher.writeSessionWindow(fp, sid, window)
        // Room 持久化已移除（remove-message-persistence Task 3）：进程内 LRU
        // 是唯一缓存层，无 IO。
    }

    init {
        applySavedSettings(repository, settingsManager, hostProfileStore, store.slices)
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // §R18 Phase 2-G (P0-6): UiEvent.Error now carries a `@StringRes`
            // resId + format args instead of a hardcoded String. Resolve to
            // a localized String here (the only app-lifetime UiEvent consumer
            // without a Composition); the in-app snackbar is rendered by
            // ChatScreen, which resolves via its own LocalContext.
            uiEvents.collect { event ->
                if (event is UiEvent.Error) {
                    val message = appContext.getString(event.resId, *event.args.toTypedArray())
                    appLifecycleMonitor.onAppError(message)
                }
            }
        }
        // R-17 batch3b: subscribe to controller effects BEFORE any external
        // caller can drive a controller. UNDISPATCHED so the collector is
        // registered synchronously here, before the constructor returns.
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            effectBus.effects.collect { effect -> dispatchEffect(effect) }
        }

        // CP3 (notify Phase-0): eagerly start the SSE event bridge so it is
        // subscribed to [sseEventStream.events] BEFORE any producer emits.
        // The bridge performs the §2 epoch guard (drops stale-identity frames)
        // and routes fresh frames to the §11 control/delta dual-channel.
        // currentEpoch is read fresh per frame from the single identity store.
        sseEventBridge.start(sseEventStream.events) { identityStore.currentEpoch() }

        // CP3: collect the bridge's control + delta channels and re-emit each
        // validated [IdentifiedSseEvent] as [ControllerEffect.OnSseEvent] so
        // SSC's identity-checked [handleEvent(IdentifiedSseEvent)] fold runs
        // exactly as it did pre-CP3 (when CC emitted OnSseEvent directly).
        // The OnSseEvent type + the fold path are unchanged; only the producer
        // moved (CC → stream → bridge → AppCore → OnSseEvent → SSC).
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sseEventBridge.controlEvents.collect { identified ->
                dispatchEffect(ControllerEffect.OnSseEvent(identified))
            }
        }
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sseEventBridge.deltaEvents.collect { identified ->
                dispatchEffect(ControllerEffect.OnSseEvent(identified))
            }
        }

        // CP3 §11 overflow recovery: when delta overflow marks sessions dirty,
        // drain the set and drive a REST reconcile for each dirty session via
        // the existing LoadMessages effect (resetLimit=true forces a full
        // window reload from the server). The dirtySessions StateFlow starts
        // empty; we only react when it transitions to non-empty.
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sseEventBridge.dirtySessions.filter { it.isNotEmpty() }.collect { _ ->
                    val sessions = sseEventBridge.consumeDirty()
                    for (sid in sessions) {
                        // Skip the bridge's "__unknown__" placeholder + blanks —
                        // those have no session-scoped reload target.
                        if (sid.isBlank() || sid.startsWith("__")) continue
                        DebugLog.i(
                            TAG,
                            "§11 delta overflow reconcile: reloading session $sid"
                        )
                        effectBus.tryEmitEffect(
                            ControllerEffect.LoadMessages(sid, resetLimit = true)
                        )
                    }
                }
        }

        // §R18 Phase 2-F: currentSessionId convergence. ChatState
        // (chatFlow.currentSessionId) is the sole runtime source; the
        // SettingsManager is a cold-start seed + a persistence side-effect.
        //
        // Cold-start seed: applySavedSettings already seeded chatFlow above;
        // this is a fallback for when applySavedSettings did not run or left
        // currentSessionId null while a persisted id exists. Runs synchronously
        // (inline) BEFORE the collector below is registered, so the collector
        // sees the seeded value as the starting point (not the pre-seed null).
        val persistedSid = settingsManager.currentSessionId
        if (persistedSid != null && store.chatFlow.value.currentSessionId == null) {
            store.mutateChat { it.copy(currentSessionId = persistedSid) }
        }

        // remove-message-persistence Task 6: the prior cold-start
        // `cacheRepository.verifyFingerprint(seededSid)` defensive self-check
        // (R-20 Phase 1 C8) was deleted together with the CacheRepository
        // surface. The seeded currentSessionId is now consumed directly;
        // VerifyAndHydrate's in-memory peek handles the empty-cache cold
        // start (memory miss → cold-start REST, server is the source of
        // truth). MismatchEvicted's stale-window eviction is no longer
        // performed — see the task brief's "MismatchEvicted 清理副作用" note
        // for the accepted self-heal behaviour.

        // Persistence side-effect: every non-null change of chatFlow's
        // currentSessionId is written back to SettingsManager so the next cold
        // start can re-seed. `filterNotNull()` prevents the collector from
        // reading the initial null (before the seed lands) and overwriting the
        // seed; it also means null-clearing transitions (host switch / close /
        // delete / archive of the current session) are NOT persisted here —
        // those are handled by the explicit chatFlow.clear + applySavedSettings
        // archived-id filter on the next cold start. `distinctUntilChanged`
        // avoids redundant writes. UNDISPATCHED so the collector is registered
        // before the constructor returns (same rationale as the two collectors
        // above).
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.chatFlow.map { it.currentSessionId }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { id -> settingsManager.currentSessionId = id }
        }
    }

    /**
     * R-17 batch3b → batch3d → R-19 Sprint 2 P2-2: routes a single
     * [ControllerEffect] emitted by any of the 6 controllers to the matching
     * helper here.
     *
     * **R-19 P2-2 split**: the former 23-branch monolithic `when` (which had
     * reached its cognitive ceiling — sealed `ControllerEffect` guarantees
     * compile-time exhaustiveness but a single function with 23 branches
     * makes adding a new effect silently no-op if a branch is missed) is now
     * split into 5 per-domain dispatchers that each return `Boolean` (handled).
     * The top-level router cascades them via short-circuit `||` so the first
     * domain that handles the effect wins; if none does,
     * [assertExactlyOneHandled] logs a warning (visible in the in-app debug
     * log viewer) so a missed branch is never silently swallowed.
     *
     * Each branch is a thin call to the same controller / free function the
     * corresponding VM method uses (AppCore cannot call the VMs directly
     * because Hilt ViewModels are not @Inject-able).
     */
    private fun dispatchEffect(effect: ControllerEffect) {
        val handled = dispatchForegroundCatchUpEffect(effect)
            || dispatchSessionEffect(effect)
            || dispatchHostEffect(effect)
            || dispatchConnectionEffect(effect)
            || dispatchSessionSyncEffect(effect)
        assertExactlyOneHandled(effect, handled)
    }

    /**
     * R-19 P2-2: invariant guard. Each [ControllerEffect] subtype belongs to
     * exactly ONE domain dispatcher; the `||` cascade in [dispatchEffect]
     * stops at the first `handled = true`. If none of the 5 dispatchers
     * claims the effect (e.g. a new branch was added to the sealed hierarchy
     * but no dispatcher was updated), this logs a warning so the miss is
     * observable in the in-app debug log viewer instead of silently
     * no-op'ing. We do not throw: production must not crash on an
     * unrecognized effect (a missed branch is a correctness bug, but the
     * effect itself is otherwise harmless to drop).
     *
     * Visibility is `internal` (not `private`) so unit tests can drive the
     * warning path directly without constructing a fake `ControllerEffect`
     * subtype (sealed classes cannot be subclassed from the test source set
     * — different Kotlin module). The cascade itself is exercised by
     * `dispatchEffect`, which is the only production caller.
     */
    internal fun assertExactlyOneHandled(effect: ControllerEffect, handled: Boolean) {
        if (!handled) {
            DebugLog.w(TAG, "unhandled effect=$effect")
        }
    }

    // ── Per-domain effect dispatchers (R-19 P2-2 split). ─────────────────────
    // Each dispatcher owns the branches for ONE controller family; returns
    // `true` iff it matched + dispatched the effect, `false` otherwise so the
    // next domain dispatcher in [dispatchEffect]'s `||` cascade gets a chance.
    // Branch bodies are preserved verbatim from the former monolithic `when`
    // (pure relocation — no behavior change).

    /** ForegroundCatchUpController-owned effects. */
    internal fun dispatchForegroundCatchUpEffect(effect: ControllerEffect): Boolean = when (effect) {
        is ControllerEffect.ForceReconnect -> {
            connectionCoordinator.testConnection(force = true)
            true
        }
        is ControllerEffect.GlobalColdStartRefresh -> {
            performGlobalColdStartRefresh(currentId = effect.sessionId)
            true
        }
        is ControllerEffect.CancelSse -> {
            // CP9 §D21: REMOVE `connectionCoordinator.cancelSse()` — the
            // Service's `disconnect()` is now the producer of this effect
            // (it is an OBSERVED transport-disconnect signal, NOT a request
            // for CC to cancel a job; CC no longer owns a job). Calling CC
            // here would route through coordinator.onDisconnect() →
            // redundant teardown loop. RETAIN the delta-buffer clear (the
            // gap-dirty contract is still relevant — SSC stamps the current
            // session dirty + records the disconnect time so the next
            // server.connected reconciles).
            sessionSyncCoordinator.clearDeltaBuffers()
            true
        }
        is ControllerEffect.CatchUpAfterDisconnect -> {
            catchUpAfterDisconnectOrForeground(effect.sessionId)
            true
        }
        else -> false
    }

    /** SessionSwitcher-owned effects. */
    internal fun dispatchSessionEffect(effect: ControllerEffect): Boolean = when (effect) {
        is ControllerEffect.LoadMessages -> {
            loadMessagesForEffect(effect.sessionId, effect.resetLimit)
            true
        }
        is ControllerEffect.LoadChildSessions -> {
            launchLoadChildSessions(appScope, repository, store.slices, effect.sessionId, TAG)
            true
        }
        is ControllerEffect.LoadSessionStatus -> {
            launchLoadSessionStatus(appScope, repository, store.slices)
            true
        }
        is ControllerEffect.LoadSessionStatusWithCompletion -> {
            launchLoadSessionStatus(
                appScope,
                repository,
                store.slices,
                onComplete = effect.onComplete,
            )
            true
        }
        is ControllerEffect.LoadPendingQuestions -> {
            // §R18 Phase 3 Wave 3 (P1-9 wire-up): production now uses the
            // multi-workdir fan-out added in Wave 1. The single-workdir
            // `launchLoadPendingQuestions(..., settingsManager.currentWorkdir, ...)`
            // path was dropping pending questions for any background workdir
            // (a `question.asked` SSE event for a non-current workdir was
            // fetched-then-overwritten by the next currentWorkdir poll).
            // The coordinator owns `slices` (directorySessions keys) +
            // `settingsManager` (currentWorkdir), so it computes the
            // workdir set internally — only the repository is passed in.
            sessionSyncCoordinator.loadPendingQuestionsAllWorkdirs(repository)
            true
        }
        is ControllerEffect.ClearDeltaBuffers -> {
            sessionSyncCoordinator.clearDeltaBuffers()
            true
        }
        is ControllerEffect.VerifyAndHydrate -> {
            // remove-message-persistence Task 2: the handler no longer touches
            // SQLite. It probes the in-memory sessionWindowCache via
            // [SessionSwitcher.peekSessionWindow] (a TRUE read-only, non-LRU-
            // promoting synchronous read) and hydrates the chat slice directly
            // when the window is present, else cold-starts via loadMessages.
            //
            // Why synchronous peek is safe here (no suspend, no dispatcher hop):
            // appScope = Dispatchers.Main.immediate (UiApplicationScope), and
            // sessionWindowCache is main-thread confined (SessionSwitcher.kt
            // §sessionWindowCache docblock). Both ends run on the same thread,
            // so the peek cannot race a concurrent write. Because there is no
            // suspend between the entry guard and the post-peek re-check, the
            // classic suspend TOCTOU window (review-fix #1 / gpter 复审
            // #1: switch session or host DURING a suspend) collapses — a
            // single entry + re-check pair suffices.
            //
            // The entry guard on `currentSessionId` is retained: the user may
            // have switched away between the synchronous effect emission and
            // this async dispatch (FIFO effects preserve order but not
            // session-identity-still-current). The post-peek re-check of the
            // composite key (fp + sessionId) is also retained as defence-in-
            // depth: although peek itself does not suspend, keeping the
            // re-check documents the invariant and stays robust if a hop is
            // ever reintroduced upstream of the hydrate.
            //
            // remove-message-persistence Task 4: the gap_marker mechanism was
            // deleted (non-contiguous layout + 50-step backfill removed). The
            // hydrate path only restores messages + parts.
            appScope.launch {
                // Entry guard: user may have switched away before this launch
                // even starts.
                if (effect.sessionId != store.chatFlow.value.currentSessionId) {
                    DebugLog.d(TAG, "VerifyAndHydrate dropped: session switched away (entry)")
                    return@launch
                }
                val cached = sessionSwitcher.peekSessionWindow(effect.sessionId)
                // 二次重检 (review-fix #1, retained): peek 后复合键未变才注入。
                // peek 本身同步不 suspend，但保留重检作为 defence-in-depth 并
                // 固化不变量；fp 用注入的 currentServerGroupFp provider（与
                // ControllerModule 同源），不重读 hostProfileStore（已在 DI 层
                // 统一 ifBlank 兜底）。
                if (effect.serverGroupFp != currentServerGroupFp() ||
                    effect.sessionId != store.chatFlow.value.currentSessionId
                ) {
                    DebugLog.d(
                        TAG,
                        "VerifyAndHydrate dropped: fp or session changed during peek " +
                            "(effect.fp=${effect.serverGroupFp} current.fp=${currentServerGroupFp()} " +
                            "effect.sid=${effect.sessionId} current.sid=${store.chatFlow.value.currentSessionId})"
                    )
                    return@launch
                }
                if (cached != null) {
                    store.mutateChat {
                        it.copy(
                            messages = cached.messages,
                            partsByMessage = cached.partsByMessage,
                            olderMessagesCursor = cached.olderMessagesCursor,
                            hasMoreMessages = cached.hasMoreMessages,
                        )
                    }
                    // resetLimit=false: keep the cached older history;
                    // loadMessages merges the latest tail non-destructively
                    // (§preserveUnfetched in MessageActions).
                    loadMessagesForEffect(effect.sessionId, resetLimit = false)
                } else {
                    // No memory hit → cold-start fetch (latest window).
                    // resetLimit=true wipes any partial state and seeds a
                    // fresh olderMessagesCursor.
                    loadMessagesForEffect(effect.sessionId, resetLimit = true)
                }
            }
            true
        }
        is ControllerEffect.AppendMessageToCache -> {
            // remove-message-persistence Task 3: SSE `message.updated`
            // new-insert branch (SessionSyncCoordinator) emits this in place
            // of the old `cacheRepository.appendMessageIfSessionCached`
            // suspend call. Delegates to the in-memory LRU append — no-op
            // when the window is not resident (cold-start sessions do not
            // proactively build a cache). Synchronous (no appScope.launch):
            // appendMessageIfCached is a pure memory op and the SSE fold
            // already runs on appScope = Dispatchers.Main.immediate, which
            // is the same dispatcher sessionWindowCache is confined to.
            sessionSwitcher.appendMessageIfCached(
                effect.serverGroupFp, effect.sessionId, effect.message, effect.parts
            )
            true
        }
        else -> false
    }

    /** HostProfileController-owned effects. */
    internal fun dispatchHostEffect(effect: ControllerEffect): Boolean = when (effect) {
        is ControllerEffect.CancelSseForReconfigure -> {
            connectionCoordinator.cancelSseForReconfigure()
            true
        }
        is ControllerEffect.StartSse -> {
            connectionCoordinator.startSSE()
            true
        }
        is ControllerEffect.HostProfileSwitched -> {
            applyReloadDisabledModelsForCurrentHost(settingsManager, hostProfileStore, store.slices)
            true
        }
        is ControllerEffect.ColdStartReconnect -> {
            connectionCoordinator.coldStartReconnect()
            true
        }
        is ControllerEffect.ResetLocalDataAndResync -> {
            resetLocalDataAndResync()
            true
        }
        is ControllerEffect.ClearSessionWindowCache -> {
            sessionSwitcher.clearSessionWindowCache()
            true
        }
        is ControllerEffect.EvictSession -> {
            // R-20 Phase 1 (plan §3 矩阵 "用户归档 / 删除 / SSE 归档" 行):
            // synchronous memory clear. Memory first so an immediate switchTo
            // does not re-hydrate the just-evicted window from the LRU.
            // remove-message-persistence Task 6: the prior async
            // `cacheRepository.evictSession` fire-and-forget persistent evict
            // was deleted together with the CacheRepository surface — the
            // process-in LRU is the sole cache layer now.
            sessionSwitcher.evictSession(effect.serverGroupFp, effect.sessionId)
            true
        }
        is ControllerEffect.EvictGroup -> {
            // R-20 Phase 1 (plan §3 矩阵 "异组切换" 行): synchronous group-
            // scoped memory clear. NOT clearAll — only the previous group is
            // wiped; the new group (current after selectHostProfile) keeps
            // its cache. Naming explicitly EvictGroup (plan §3 N6 forbids
            // ClearGroup).
            // remove-message-persistence Task 6: the prior async
            // `cacheRepository.evictGroup` fire-and-forget persistent evict
            // was deleted together with the CacheRepository surface.
            sessionSwitcher.clearMemoryForGroup(effect.serverGroupFp)
            true
        }
        else -> false
    }

    /** ConnectionCoordinator-owned effects. */
    internal fun dispatchConnectionEffect(effect: ControllerEffect): Boolean = when (effect) {
        is ControllerEffect.HostReconfigured -> {
            // CP1: HostReconfigured carries the new epoch. Reset the
            // foreground catch-up state machine (idempotent) — SSC's init
            // collector independently reads effect.epoch to reset its overlay
            // to this exact generation.
            foregroundCatchUpController.onHostReconfigured()
            true
        }
        is ControllerEffect.LoadSessions -> {
            loadSessionsForEffect()
            true
        }
        is ControllerEffect.LoadAgents -> {
            // §chat-ux-batch T8 (B3): launchLoadAgents shed its settingsManager
            // param (the legacy selectedAgentName reconciliation was deleted).
            launchLoadAgents(appScope, repository, store.slices, TAG)
            true
        }
        is ControllerEffect.LoadProviders -> {
            launchLoadProviders(appScope, repository, store.slices, settingsManager, hostProfileStore) { message, error ->
                reportNonFatalIssue(TAG, message, error)
            }
            true
        }
        is ControllerEffect.LoadPendingPermissions -> {
            launchLoadPendingPermissions(appScope, repository, store.slices, TAG)
            true
        }
        is ControllerEffect.OnSseEvent -> {
            // CP1: route through the identity-checked entry point.
            // SSC.handleEvent(IdentifiedSseEvent) validates
            // identityStore.isCurrent BEFORE any fold/state mutation — a
            // stale-identity frame (captured under a pre-reconfigure epoch)
            // is dropped silently.
            sessionSyncCoordinator.handleEvent(effect.event)
            true
        }
        else -> false
    }

    /** SessionSyncCoordinator-owned effects. */
    internal fun dispatchSessionSyncEffect(effect: ControllerEffect): Boolean = when (effect) {
        is ControllerEffect.ServerConnected -> {
            foregroundCatchUpController.onServerConnected()
            true
        }
        is ControllerEffect.RefreshSessions -> {
            loadSessionsForEffect()
            true
        }
        else -> false
    }


    // ── Test hooks (kept `internal` so MainViewModelTest's reflection-free
    //  call sites keep resolving; production never calls these directly). ────

    /**
     * Test hook: routes a single SSE event to [sessionSyncCoordinator.handleEvent].
     * Production code goes through the SSE collection coroutine inside
     * [ConnectionCoordinator] (which emits [ControllerEffect.OnSseEvent] for
     * each event, dispatched back to [sessionSyncCoordinator] by
     * [dispatchEffect]).
     *
     * CP1: auto-wraps with the current identity (if bound) so the identity-
     * checked path is exercised. Tests that don't bind an identity fall
     * through to the raw [SSEEvent] dispatch (no identity gate).
     */
    internal fun handleSSEEvent(event: SSEEvent) {
        val identity = identityStore.currentIdentity.value
        if (identity != null) {
            sessionSyncCoordinator.handleEvent(
                cn.vectory.ocdroid.service.events.IdentifiedSseEvent(identity, event)
            )
        } else {
            sessionSyncCoordinator.handleEvent(event)
        }
    }

    internal fun sessionWindowCacheSize(): Int = sessionSwitcher.sessionWindowCacheSize()
    internal fun peekSessionWindow(sessionId: String): CachedSessionWindow? = sessionSwitcher.peekSessionWindow(sessionId)


    /**
     * Teardown — cancels SSE, drops delta buffers, and cancels [appScope].
     * Invoked from [OpenCodeApp.onTerminate] (best-effort; the framework does
     * not guarantee onTerminate is called, but OS process death reclaims all
     * resources regardless). This hook exists for future multi-Activity or
     * explicit-reset scenarios where AppCore state must be manually cleared.
     */
    fun cleanup() {
        sessionSyncCoordinator.clearDeltaBuffers()
        connectionCoordinator.cancelSse()
        appScope.cancel()
    }

    private companion object {
        private const val TAG = "AppCore"
    }
}
