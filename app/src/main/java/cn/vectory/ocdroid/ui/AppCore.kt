package cn.vectory.ocdroid.ui

import android.content.Context
import cn.vectory.ocdroid.data.model.*
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.R
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
import cn.vectory.ocdroid.ui.controller.ErrorRecoveryCoordinator
import cn.vectory.ocdroid.ui.controller.ForegroundCatchUpController
import cn.vectory.ocdroid.ui.controller.HostProfileController
import cn.vectory.ocdroid.ui.controller.subtreeIds
import cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator
import cn.vectory.ocdroid.ui.controller.SessionSwitcher
// §Wave2.1-split-l2: orchestrator dependencies (Pattern B constructor injection).
// §Wave2.1-split-l2: orchestrator dependencies (Pattern B — constructor
// injection, NO interfaces). These are internal to the ui package.
import cn.vectory.ocdroid.ui.CommandOrchestrator
import cn.vectory.ocdroid.ui.DraftSessionOrchestrator
import cn.vectory.ocdroid.ui.RefreshOrchestrator
import cn.vectory.ocdroid.ui.SendOrchestrator
import cn.vectory.ocdroid.ui.SessionOpener
import androidx.annotation.MainThread
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
    /**
     * §slim-reconcile-lane-repo (Phase 3a / Lane-B3-Dialog): exposed `internal`
     * so [ConnectionViewModel]'s test-connection path can read
     * [ServerCompatProfile.isSlimapiClientAccepted] / [ServerCompatProfile.slimapiAcceptedMin]
     * / [ServerCompatProfile.slimapiAcceptedMax] AFTER [OpenCodeRepository.checkHealthFor]
     * wrote them (slim branch mirrors `probeSlimapiHealth`'s T5 updateSlimapi call) —
     * this closes the M2 version-incompatibility UX loop (fail-closed transport
     * worked but the dialog never fired because the flag was never written from
     * the test-connection path). Visibility bumped from `private` to `internal`
     * for the secondary constructor forwarding; production never reads this
     * directly outside ConnectionViewModel.
     */
    internal val serverCompatProfile: cn.vectory.ocdroid.data.repository.ServerCompatProfile,
    /** R-17 batch3: shared bus for cross-VM effect dispatch. Controllers emit
     *  [ControllerEffect]s on [SharedEffectBus.effects]; this class collects
     *  them in its [init] block and dispatches. UiEvents ride
     *  [SharedEffectBus.uiEvents]. */
    internal val effectBus: SharedEffectBus,
    /**
     * Application Context. Retained as a constructor param so the test-only
     * secondary constructor of [SettingsViewModel] can forward it without a
     * separate Hilt binding in unit tests. The Phase 1 (后台驻留移除) cleanup
     * removed the only production consumer (the [AppLifecycleMonitor.onAppError]
     * notification path); production code no longer reads this directly, but
     * Hilt still injects it (cheap, and keeps the test path simple).
     */
    @ApplicationContext internal val appContext: Context,
    /** R-19 Sprint 3 P2-5: the 5 application-scoped controllers are now
     *  Hilt-injected (@Singleton via [cn.vectory.ocdroid.di.ControllerModule])
     *  instead of being constructed inline here. This lets the per-domain
     *  ViewModels inject the SAME singleton instances directly (precise
     *  injection — see R-19 P2-5) without `core.<controller>` reach-through
     *  and without duplicating controller instances. AppCore retains the
     *  effect-bus collector → [dispatchEffect] routing + the cross-domain
     *  orchestration methods; it does not construct the controllers. */
    private val foregroundCatchUpController: ForegroundCatchUpController,
    internal val composerController: ComposerController,
    internal val sessionSwitcher: SessionSwitcher,
    internal val hostProfileController: HostProfileController,
    internal val sessionSyncCoordinator: SessionSyncCoordinator,
    internal val connectionCoordinator: ConnectionCoordinator,
    /**
     * §Stage-D2: the token-stream coordinator singleton. Injected here so
     * [ChatViewModel] can reach it for the busy-open hook (B-1), and so
     * AppCore's [dispatchEffect] can close the token stream on
     * [ControllerEffect.EvictSession] (covers session.deleted digest +
     * /since 404 MarkDeleted — both emit EvictSession via
     * [SessionSyncCoordinator]).
     */
    internal val tokenStreamCoordinator: cn.vectory.ocdroid.ui.controller.sse.TokenStreamCoordinator,
    /**
     * §unread-soak: the foreground sweep that owns the new "unread"
     * population logic (replaces the old instant busy→idle marker). Injected
     * here purely so Hilt constructs the @Singleton early — its init block
     * subscribes to [cn.vectory.ocdroid.di.AppLifecycleMonitor.isInForeground]
     * and self-starts/stops the sweep. AppCore never calls into it directly.
     */
    private val unreadSoakController: cn.vectory.ocdroid.ui.controller.UnreadSoakController,
    /**
     * §P0-E(b)(c): the GET drain/consumer for durable error localization.
     * Injected here purely so Hilt constructs the @Singleton early — its init
     * block subscribes to [cn.vectory.ocdroid.ui.SharedStateStore.stateFlow]
     * and drains pendingErrorReattach / pendingErrorCheck markers via
     * [cn.vectory.ocdroid.data.repository.OpenCodeRepository.getMessages].
     * AppCore never calls into it directly.
     */
    private val errorRecoveryCoordinator: ErrorRecoveryCoordinator,
    /**
     * R-20 Phase 1 (review-fix #1): provider for the current host's
     * profileId. Injected via the SAME `@Named("currentProfileId")`
     * @Provides that every controller uses (ControllerModule), so AppCore's
     * fp derivation never drifts from the controllers'. Used by the
     * VerifyAndHydrate handler's post-peek 二次重检 (the in-memory
     * peekSessionWindow read is synchronous and main-confined, so it cannot
     * straddle a host switch; the re-check is retained as defence-in-depth
     * to lock the composite-key (fp + sessionId) invariant at the handler
     * boundary and stays robust if a dispatcher hop is ever reintroduced).
     */
    @Named("currentProfileId") internal val currentProfileId: () -> String,
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
    private val identityStore: ConnectionIdentityStore,
    /**
     * CP3 (notify Phase-0): the process-wide SSE event stream. CC publishes
     * each [cn.vectory.ocdroid.service.events.IdentifiedSseEvent] here (CP3
     * replaced the direct ControllerEffect.OnSseEvent emission). Injected
     * here so AppCore can pass it to the bridge.
     */
    private val sseEventStream: SseEventStream,
    /**
     * CP3 (notify Phase-0): the identity-checked SSE event bridge. Subscribes
     * to [sseEventStream.events], validates epoch (§2), routes to the §11
     * control/delta dual-channel. AppCore collects both channels and re-emits
     * them as [ControllerEffect.OnSseEvent] for SSC's identity-checked fold.
     */
    private val sseEventBridge: SseEventBridge,
    /**
     * T13 (round-2 review fix — AppCore dispatch): the process-level
     * [ProcessStatusPoller], injected so [dispatchSessionSyncEffect] can
     * route [ControllerEffect.RequestPollerBackoff] /
     * [ControllerEffect.ResetPollerBackoff] (emitted by
     * [SessionSyncCoordinator.applySlimStatusFanOutSummary]) to
     * [ProcessStatusPoller.scheduleBackoff] /
     * [ProcessStatusPoller.resetBackoff]. Without this wiring the emitted
     * effects disappeared through the unhandled-effect warning path
     * (rev-gpt round-1 review #6).
     *
     * `@Singleton` (provided by
     * [cn.vectory.ocdroid.service.streaming.ProcessStatusPollerModule]);
     * AppCore receives the SAME instance [SessionStreamingService] injects
     * (the L3 background loop owner) so the backoff state machine is
     * process-coherent.
     *
     * **Phase 1 (后台驻留移除) inert-by-design note**: the poller's only
     * production start path (`ConnectionCoordinator`'s background-transition
     * `ensureRunning` call) was removed with the rest of the background
     * polling subsystem — background is now fully silent. The class + this
     * DI binding + the backoff effect handlers are RETAINED so a future
     * foreground-degraded-polling path can re-wire `ensureRunning` without
     * rebuilding the backoff/slim-fan-out machinery. Until then the poller
     * loop never starts; `scheduleBackoff`/`resetBackoff`/`requestSlimFanOutRetry`
     * mutate an inert instance's backoff state (no runtime effect, no
     * regression — the foreground SSE-disconnect case was never covered by
     * this poller, which was always background-started-only).
     */
    private val processStatusPoller: cn.vectory.ocdroid.service.streaming.ProcessStatusPoller,
) {
    /**
     * §Wave2.1-split-l2 (rev-gpt APPROVED EXCEPTION): _lazy composition_ of the 5
     * orchestrator dependencies, NOT Hilt constructor injection.
     *
     * ## Why lazy, not Hilt ctor injection (§2.2 Pattern B)
     *
     * The Wave2.1 architecture report §2.2 specifies Pattern B: orchestrator
     * classes declare constructor deps via `@Inject constructor` and AppCore
     * receives them as constructor-injected parameters. This is the target state.
     *
     * However, migrating AppCore's constructor signature would break existing
     * test factories (MainViewModelTestBase.createCore, ForkSessionTest) that
     * construct AppCore manually with positional args and do not go through Hilt.
     * Those factories are outside the Wave2.1 write domain, so constructor
     * injection of orchestrators is deferred.
     *
     * ## What this means for Hilt ownership
     *
     * The orchestrator classes retain `@Singleton @Inject constructor`
     * annotations (they are Hilt-provisionable types, future-proof for
     * Wave2.2). But in Wave2.1, AppCore does NOT obtain them via Hilt — it owns
     * the instances via `lazy`, which calls the orchestrator constructors
     * directly. This is runtime-safe because:
     *   - The dependency graph is acyclic (Refresh → Send → Draft → Command).
     *   - All deps required by orchestrator constructors are already available
     *     as AppCore constructor-injected fields (same Singleton instances Hilt
     *     would provide).
     *   - Production access is single-threaded (Dispatchers.Main), so lazy
     *     synchronization overhead is negligible.
     *
     * ## TODO(Wave2.2)
     *
     * Migrate test factories to allow full Hilt constructor injection of the 5
     * orchestrators into AppCore. Then remove this `lazy` composition block and
     * add the 5 params to AppCore's `@Inject constructor`.
     */
    private val sessionOpener by lazy { SessionOpener(store, repository, appScope, sessionSwitcher) }
    internal val refreshOrchestrator by lazy {
        RefreshOrchestrator(
            store, repository, settingsManager, effectBus, appScope,
            currentProfileId, sessionSwitcher, connectionCoordinator,
            sessionSyncCoordinator, foregroundCatchUpController, hostProfileStore,
            serverCompatProfile, tokenStreamCoordinator,
        )
    }
    private val sendOrchestrator by lazy {
        SendOrchestrator(
            store, repository, settingsManager, effectBus, appScope,
            currentProfileId, sessionSwitcher, connectionCoordinator,
        )
    }
    internal val draftSessionOrchestrator by lazy {
        DraftSessionOrchestrator(
            store, repository, settingsManager, effectBus, appScope,
            currentProfileId, composerController, sessionSwitcher,
            sendOrchestrator, refreshOrchestrator,
        )
    }
    private val commandOrchestrator by lazy {
        // `repository` (the OCR @Singleton) is passed twice: it implements BOTH
        // SessionRepository and InteractionRepository — the same singleton sits
        // behind both narrow seams (see RepositoryInterfaceModule @Binds). This
        // is intentional, not a typo.
        CommandOrchestrator(
            store, repository, repository, settingsManager, effectBus, appScope,
            currentProfileId, composerController,
            draftSessionOrchestrator, sessionOpener,
        )
    }

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
    private fun writeTraffic(transform: (TrafficState) -> TrafficState) = store.mutateTraffic(transform)
    internal fun writeComposer(transform: (ComposerState) -> ComposerState) = store.mutateComposer(transform)
    internal fun writeFile(transform: (FileState) -> FileState) = store.mutateFile(transform)
    internal fun writeSettings(transform: (SettingsState) -> SettingsState) = store.mutateSettings(transform)
    internal fun writeChat(transform: (ChatState) -> ChatState) = store.mutateChat(transform)
    internal fun writeSessionList(transform: (SessionListState) -> SessionListState) = store.mutateSessionList(transform)
    internal fun writeUnread(transform: (UnreadState) -> UnreadState) = store.mutateUnread(transform)
    internal fun writeHost(transform: (HostState) -> HostState) = store.mutateHost(transform)
    internal fun writeSessionWindow(profileId: String, sessionId: String, window: CachedSessionWindow) {
        sessionSwitcher.writeSessionWindow(profileId, sessionId, window)
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
     * [fp] (not a re-read of currentProfileId). A host switch
     * mid-flight would otherwise route the old fetch's data into the new
     * group's LRU slot.
     */
    internal fun makeCacheHook(fp: String): (String, CachedSessionWindow) -> Unit = { sid, window ->
        sessionSwitcher.writeSessionWindow(fp, sid, window)
        // Room 持久化已移除（remove-message-persistence Task 3）：进程内 LRU
        // 是唯一缓存层，无 IO。
    }

    // ── §Wave2.1-split-l2: 5 cross-domain entry points (thin delegation) ─────
    // The orchestrators own the implementation; AppCore is the thin router.
    // Signatures MUST NOT change — callers (ChatViewModel, OrchestratorViewModel)
    // invoke these as `core.<method>()`.

    /** Cross-domain: composer→chat→session creation. Routes to draft or existing. */
    @MainThread
    internal fun sendMessage() {
        val draftWorkdir = store.composerFlow.value.draftWorkdir
        val existingSessionId = store.chatFlow.value.currentSessionId
        val text = store.composerFlow.value.inputText.trim()
        val attachments = store.composerFlow.value.imageAttachments
        if (text.isEmpty() && attachments.isEmpty()) return
        if (draftWorkdir != null && existingSessionId == null) {
            draftSessionOrchestrator.sendMessageViaDraft()
        } else {
            val sessionId = existingSessionId ?: return
            if (store.composerFlow.value.sendingSessionIds.contains(sessionId)) return
            sendOrchestrator.dispatchSendMessage(sessionId)
        }
    }

    /** `/clear` and other slash commands. */
    internal fun executeCommand(command: String, arguments: String) = commandOrchestrator.executeCommand(command, arguments)

    /** nav → session-list → chat (deep-link path). */
    internal fun openSessionFromDeepLink(sessionId: String) = sessionOpener.openSessionFromDeepLink(sessionId)

    /**
     * Full-stack local reset. Retained as a thin 1-line delegate — HostViewModel
     * already bypasses this (calls controller directly), but OrchestratorViewModel
     * still calls `core.resetLocalDataAndResync()` and we must not break that.
     */
    internal fun resetLocalDataAndResync() { hostProfileController.resetLocalDataAndResync() }

    // ── §Wave2.1-split-l2: additional delegation methods for callers outside
    //  the write domain (ChatViewModel, ChatScaffold, RevertConversation).
    //  These preserve the original extension-function signatures.

    /** @see [RefreshOrchestrator.performGlobalColdStartRefresh] */
    internal fun performGlobalColdStartRefresh(currentId: String, forceInitialWindow: Boolean = false, explicit: Boolean = false): Boolean =
        refreshOrchestrator.performGlobalColdStartRefresh(currentId, forceInitialWindow, explicit)

    /** @see [RefreshOrchestrator.performForceRefresh] */
    internal fun performForceRefresh(sessionId: String) = refreshOrchestrator.performForceRefresh(sessionId)

    /** @see [RefreshOrchestrator.loadSessionsForEffect] */
    internal fun loadSessionsForEffect() = refreshOrchestrator.loadSessionsForEffect()

    init {
        // §G-ACL: one-time migration of legacy slimapi profiles (http:4097 → https:14097 mTLS).
        hostProfileStore.migrateAllForGacl()
        applySavedSettings(repository, settingsManager, hostProfileStore, store.slices)
        // §需求12阶段4: one-shot purge of per-group orphan keys (legacy named-
        // group A/B/C/D + legacy baseUrl-keyed slots) whose suffix is not a
        // canonical UUID. Runs AFTER applySavedSettings so the current host's
        // legacy data is migrated to its per-fp slot first; then this pass
        // deletes every remaining non-UUID-suffixed per-fp key. Idempotent via
        // the orphan_group_cleanup_v1_done flag — a second cold start is a no-op.
        settingsManager.cleanupOrphanGroupKeys()
        // §streaming-state-sync-diag (release-enabling): seed the runtime
        // verbose-diag flag from its ESP-persisted value so the 5 *Diag tags
        // (SendDiag/SseDiag/StatusDiag/DigestDiag/LayerDiag) start emitting
        // immediately if the user enabled them in Settings → Debug. Default
        // OFF → zero log noise / perf cost.
        cn.vectory.ocdroid.util.DebugLog.verboseDiagEnabled = settingsManager.debugLogVerboseEnabled
        // §streaming-state-sync-diag (DEBUG-only backdoor): pre-seed a host
        // profile at the dev host's opencode (10.0.2.2 = the emulator's host-
        // loopback alias; legacy non-slim; user/pass from .env) and select it
        // current, so diagnostic emulator runs skip the multi-field host-config
        // UI. Idempotent. No-op in release (BuildConfig.DEBUG gate).
        if (cn.vectory.ocdroid.BuildConfig.DEBUG) {
            appScope.launch {
                val devId = "dev-debug-4096"
                val present = runCatching { hostProfileStore.profiles() }.getOrDefault(emptyList())
                if (present.none { it.id == devId || it.serverUrl.contains("10.0.2.2:4096") }) {
                    runCatching {
                        hostProfileController.saveHostProfile(
                            profile = cn.vectory.ocdroid.data.model.HostProfile(
                                id = devId,
                                name = "dev 10.0.2.2:4096",
                                serverUrl = "http://10.0.2.2:4096",
                                basicAuth = cn.vectory.ocdroid.data.model.BasicAuthConfig(
                                    username = "user",
                                    passwordId = devId,
                                ),
                                slim = false,
                                lastUsedAt = System.currentTimeMillis(),
                            ),
                            basicAuthPassword = "pass",
                            basicAuthEdited = true,
                        )
                    }
                }
                runCatching { hostProfileStore.select(devId) }
                val cur = runCatching { hostProfileStore.currentProfile() }.getOrNull()
                cn.vectory.ocdroid.util.DebugLog.i(
                    "SendDiag",
                    "dev host seed done: current=${cur?.id} url=${cur?.serverUrl} slim=${cur?.slim}",
                )
            }
        }
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // R-17 batch3b: subscribe to controller effects BEFORE any external
            // caller can drive a controller. UNDISPATCHED so the collector is
            // registered synchronously here, before the constructor returns.
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
                            ControllerEffect.LoadMessages(
                                sessionId = sid,
                                resetLimit = true,
                                expectedRouteInstance = store.slices.routeInstanceFor(sid),
                            )
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
        // §B4 / §10 cold start: never seed currentSessionId from disk into the
        // detail pane (route is Sessions; content stays null). Self-heal any
        // stale persisted id so the next launch cannot reintroduce a ghost.
        val persistedSid = settingsManager.currentSessionId
        if (persistedSid != null) {
            settingsManager.currentSessionId = null
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

        // Persistence side-effect: every DISTINCT change of chatFlow's
        // currentSessionId (including null) is written back to
        // SettingsManager so the next cold start can re-seed — AND so a
        // null-clearing transition (close-all-tabs / archive / delete /
        // SSE-archive / host-purge) is persisted, preventing applySavedSettings
        // from resurrecting a stale id on the next cold start.
        //
        // §fix-null-persistence (oracle+grok review): the prior
        // `filterNotNull()` here meant null transitions were NOT persisted,
        // so every "clear current" path left the stale id in SettingsManager
        // and the next cold start re-seeded it — the recurring "residual
        // session after close-all" theme. Centralizing null persistence here
        // closes close/archive/delete/SSE-archive in ONE place; the seed
        // block above runs synchronously BEFORE this collector subscribes
        // (UNDISPATCHED launch), so the collector's first emission is always
        // the already-seeded value (never a pre-seed null that would wrongly
        // wipe a valid persisted id). `distinctUntilChanged` avoids
        // redundant writes.
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.chatFlow.map { it.currentSessionId }
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
        // §Wave2.1-split-l2: orchestrators are created via `lazy`; by the time
        // an effect arrives (after init block completes), they are available.
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
            refreshOrchestrator.performGlobalColdStartRefresh(currentId = effect.sessionId)
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
            refreshOrchestrator.catchUpAfterDisconnectOrForeground(effect.sessionId)
            true
        }
        else -> false
    }

    /** SessionSwitcher-owned effects. */
    internal fun dispatchSessionEffect(effect: ControllerEffect): Boolean = when (effect) {
        is ControllerEffect.LoadMessages -> {
            refreshOrchestrator.loadMessagesForEffect(
                sessionId = effect.sessionId,
                resetLimit = effect.resetLimit,
                expectedRouteInstance = effect.expectedRouteInstance,
            )
            true
        }
        is ControllerEffect.LoadChildSessions -> {
            launchLoadChildSessions(appScope, repository, store.slices, effect.sessionId, TAG)
            true
        }
        is ControllerEffect.LoadSessionStatus -> {
            launchLoadSessionStatus(appScope, repository, store.slices, trigger = SessionStatusLoadTrigger.COLD_START)
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
                // even starts. §chat-list-detail §7.2 B0.5-rework: ALSO check
                // the route-instance token — a route-aware effect (T > 0) whose
                // token has already been superseded (the user navigated again)
                // must not even peek the cache or launch a load.
                val currentRouteInstance = store.stateFlow.value.chatRouteInstance
                if (effect.expectedRouteInstance > 0L && effect.expectedRouteInstance != currentRouteInstance) {
                    DebugLog.d(TAG, "VerifyAndHydrate dropped: route-instance token superseded (effect.T=${effect.expectedRouteInstance} current.T=$currentRouteInstance)")
                    return@launch
                }
                if (effect.sessionId != store.chatFlow.value.currentSessionId) {
                    DebugLog.d(TAG, "VerifyAndHydrate dropped: session switched away (entry)")
                    return@launch
                }
                val cached = sessionSwitcher.peekSessionWindow(effect.sessionId)
                // 二次重检 (review-fix #1, retained): peek 后复合键未变才注入。
                // peek 本身同步不 suspend，但保留重检作为 defence-in-depth 并
                // 固化不变量；fp 用注入的 currentProfileId provider（与
                // ControllerModule 同源），不重读 hostProfileStore（已在 DI 层
                // 统一 ifBlank 兜底）。
                if (effect.profileId != currentProfileId() ||
                    effect.sessionId != store.chatFlow.value.currentSessionId
                ) {
                    DebugLog.d(
                        TAG,
                        "VerifyAndHydrate dropped: fp or session changed during peek " +
                            "(effect.fp=${effect.profileId} current.fp=${currentProfileId()} " +
                            "effect.sid=${effect.sessionId} current.sid=${store.chatFlow.value.currentSessionId})"
                    )
                    return@launch
                }
                // §empty-window-fix: treat a resident-but-EMPTY CachedSessionWindow
                // as a cache MISS. An empty window gets written when
                // SessionSwitcher.captureCurrentSessionWindow snapshots an outgoing
                // session that was still loading or already cleared. Hydrating it
                // and running the normal resetLimit=false refresh would hit the slim
                // branch of getMessagesPaged — anchored on the EXISTING slim
                // watermark + /since. If that watermark already covers the server's
                // latest message, the response is EMPTY → the selective merge
                // preserves the empty UI window → "暂无消息" on open (root cause of
                // the "session opens empty but send populates it" bug). Falling
                // through to the cold-load branch runs an UNANCHORED fetch
                // (forceInitialWindow=true → getMessagesPagedUnanchored → since=0L)
                // which bypasses the stale watermark.
                //
                // Classification uses ONLY messages.isEmpty() (NOT "no recent tail")
                // so a window with older history but missing the tail still hydrates
                // normally — the resetLimit=false refresh re-fetches the missing tail
                // non-destructively (§preserveUnfetched).
                if (cached != null && cached.messages.isNotEmpty()) {
                    // T1b: cached-window inject (4 fields) via ChatWindowHydrated.
                    store.dispatch(
                        AppAction.ChatWindowHydrated(
                            messages = cached.messages,
                            partsByMessage = cached.partsByMessage,
                            olderMessagesCursor = cached.olderMessagesCursor,
                            hasMoreMessages = cached.hasMoreMessages,
                            expectedRouteInstance = effect.expectedRouteInstance,
                            sessionId = effect.sessionId,
                        )
                    )
                    // resetLimit=false: keep the cached older history;
                    // loadMessages merges the latest tail non-destructively
                    // (§preserveUnfetched in MessageActions).
                    refreshOrchestrator.loadMessagesForEffect(effect.sessionId, resetLimit = false, expectedRouteInstance = effect.expectedRouteInstance)
                } else {
                    // §empty-window-fix: cold-load path. This branch now covers
                    // BOTH the genuine cache miss (cached == null) AND the
                    // resident-but-empty window (cached.messages.isEmpty()).
                    // forceInitialWindow=true makes the slim fetch UNANCHORED
                    // (since=0L), bypassing any stale watermark that would
                    // return an empty /since response.
                    // resetLimit=true wipes any partial state and seeds a
                    // fresh olderMessagesCursor.
                    refreshOrchestrator.loadMessagesForEffect(effect.sessionId, resetLimit = true, forceInitialWindow = true, expectedRouteInstance = effect.expectedRouteInstance)
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
                effect.profileId, effect.sessionId, effect.message, effect.parts
            )
            true
        }
        is ControllerEffect.WriteSessionWindow -> {
            // T11 round-2 (oracle D1): non-focus RESYNC result written to
            // the in-memory sessionWindowCache so a later switchTo finds
            // the refreshed window without a re-fetch. Synchronous (same
            // dispatcher-discipline rationale as AppendMessageToCache).
            sessionSwitcher.writeSessionWindow(
                effect.profileId,
                effect.sessionId,
                CachedSessionWindow(
                    messages = effect.messages,
                    partsByMessage = effect.partsByMessage,
                    // Cursor + hasMore are unknown for a slim-skeleton
                    // RESYNC batch (the cursor drain façade aggregates
                    // skeletons without surfacing the sidecar's
                    // X-Next-Cursor through to here). Conservative defaults:
                    // null cursor + hasMore=false keeps the LRU entry
                    // usable for switchTo without advertising more
                    // history. A later loadMore on this session re-queries
                    // the sidecar for the actual older-page cursor.
                    olderMessagesCursor = null,
                    hasMoreMessages = false,
                ),
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
            // §Stage-D2 §5.9: close the token stream for the evicted session.
            // Covers BOTH paths that emit EvictSession:
            //  - session.deleted digest (SessionSyncCoordinator ~:1409)
            //  - /since 404 MarkDeleted (SessionSyncCoordinator ~:1974)
            // The EvictSession effect is the single funnel for session-gone-
            // upstream eviction; hooking here avoids coupling SSC to the TSC.
            //
            // §P0-F 阻断1 (round 4): ALL side-effects that mutate the CURRENT
            // host's bare-sid state MUST be fp-guarded — a late old-group
            // effect must NOT close the new group's tokenStream, clear its
            // overlay, or release its abort-pending lock for the same sid
            // (cross-host pollution via same-sid UUID collision across server
            // groups). The cache eviction below stays keyed by effect.profileId
            // (old group's LRU is cleared by its own fp — safe).
            // §known-gap: full identity (BundleStamp/connection epoch) deferred to P0-A.
            if (effect.profileId == currentProfileId()) {
                tokenStreamCoordinator.close(effect.sessionId)
                // §Stage-D2 (gate r1 S1): if the evicted session was the CURRENT
                // chat, clear the token-stream overlay from ChatState too. close()
                // clears coordinator-internal maps but NOT ChatState's
                // streamingPartTexts/streamOwned — without this, a deleted/
                // archived current session can leave a sticky overlay until
                // SessionSelected wipes it on the next switch.
                if (store.chatFlow.value.currentSessionId == effect.sessionId) {
                    val ownedKeys = store.chatFlow.value.streamOwned.keys
                    if (ownedKeys.isNotEmpty()) {
                        tokenStreamCoordinator.dispatchTokenStreamClear(
                            partIds = ownedKeys,
                            expectedRouteInstance = store.stateFlow.value.chatRouteInstance,
                            sessionId = effect.sessionId,
                        )
                    }
                }
                // §P0-F 阻断4 (round 3): release any in-flight abort-pending flag.
                store.mutateSessionList { s ->
                    if (effect.sessionId in s.abortPendingSessionIds)
                        s.copy(abortPendingSessionIds = s.abortPendingSessionIds - effect.sessionId)
                    else s
                }
            }
            // R-20 Phase 1: synchronous memory clear keyed by effect fp (old
            // group's LRU cleared by its own fp — safe regardless of current host).
            sessionSwitcher.evictSession(effect.profileId, effect.sessionId)
            // §slim-storm P2 (Bug B self-heal): the cache/token cleanup above does NOT
            // remove the session from sessionList, so the snapshot the status poller
            // iterates never shrank → infinite re-eviction loop. Dispatch a subtree-
            // scoped SessionDeletedLocal so the snapshot (sessions + childSessions +
            // sessionStatuses, etc.) actually contracts. The reducer now also purges
            // sessionStatuses — the field the poller fans out over — so a mis-emitted
            // EvictSession self-heals instead of churning forever.
            //
            // fp gate: sessionList is the CURRENT host's view (reloaded on host switch);
            // a cross-group EvictSession (stale fp) must NOT mutate it. Cache/token
            // cleanup above is fp-scoped via the CacheWindowKey and stays unconditional.
            // Mirrors the VerifyAndHydrate fp gate (AppCore :611).
            if (effect.profileId == currentProfileId()) {
                val sl = store.stateFlow.value.sessionList
                val removedIds = subtreeIds(
                    effect.sessionId,
                    sl.sessions,
                    sl.directorySessions,
                    sl.childSessions,
                )
                store.dispatch(AppAction.SessionDeletedLocal(removedIds))
            }
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
            sessionSwitcher.clearMemoryForGroup(effect.profileId)
            true
        }
        is ControllerEffect.RestartRequired -> {
            // §persistent-restart-required: dual notification —
            //  1) UiEvent.Error snackbar: immediate feedback on ANY screen
            //     (host-profile select/edit/delete fires from Settings, not
            //     Chat, so the snackbar ensures the user sees the prompt even
            //     before navigating to Chat).
            //  2) restartRequired flag → persistent StatusBanner in ChatScaffold
            //     (tied to the flag, NOT auto-dismiss) — stays visible until
            //     the app is restarted so the user cannot forget.
            store.slices.mutateConnection { it.copy(restartRequired = true) }
            effectBus.tryEmitUiEvent(UiEvent.Error(R.string.connection_restart_required))
            true
        }
        else -> false
    }

    /** ConnectionCoordinator-owned effects. */
    internal fun dispatchConnectionEffect(effect: ControllerEffect): Boolean = when (effect) {
        is ControllerEffect.LoadSessions -> {
            refreshOrchestrator.loadSessionsForEffect()
            true
        }
        is ControllerEffect.LoadAgents -> {
            // §chat-ux-batch T8 (B3): launchLoadAgents shed its settingsManager
            // param (the legacy selectedAgentName reconciliation was deleted).
            launchLoadAgents(appScope, repository, store.slices, TAG)
            true
        }
        is ControllerEffect.LoadProviders -> {
            // §需求13 rev-8 #2c (rev-gpt finding B close): SINK-LEVEL single-flight.
            // A prior fetch in flight has set isLoadingProviders=true synchronously
            // (launchLoadProviders:58, before scope.launch). Effects are collected
            // sequentially on Dispatchers.Main.immediate (appScope is @UiApplicationScope),
            // so by the time THIS effect is collected, any in-flight fetch has already
            // set the flag → skip to avoid a duplicate parallel /config/providers fetch.
            // Sources of concurrent LoadProviders: auto-emit (loadInitialData CAS path),
            // manual refresh (ComposerViewModel/HostViewModel.refreshProviders DIRECT emit),
            // and double-tap on the refresh IconButton. The guard covers ALL sources at
            // the single sink — the loadInitialData-side guard (rev-8 #2b) remains as
            // defense-in-depth (avoids arming the latch + emitting a redundant effect).
            if (store.slices.settings.value.isLoadingProviders) {
                // Fetch already in flight — drop this effect. The in-flight fetch will
                // resolve providers (success → gate stays armed; failure → gate disarms
                // → next loadInitialData retries after the flag clears in finally).
                true
            } else {
                // §需求4 host/fp guard: capture fp at call time + pass the LIVE fp
                // provider so the onSuccess guard can detect a mid-REST host switch
                // and drop the stale response. Mirrors launchLoadMessages callers
                // (AppCoreOrchestration:1815-1816). currentProfileId is the
                // @Named("currentProfileId") provider — single source of truth
                // for fp derivation (ControllerModule.provideCurrentProfileId),
                // equivalent to hostProfileStore.currentProfile().serverGroupFp.ifBlank { .id }.
                //
                // §ABA-triple-guard (F1): also capture `(endpointFp, generation)`
                // from the CURRENT [ClientBundle] (the sole volatile publication
                // point, `repository.currentClientBundle()`) alongside the
                // profileId, and pass live suppliers that re-read the same
                // bundle at onSuccess. This closes the ABA window the profileId-
                // only guard left open: a stale in-flight `/config/providers`
                // response from the SAME profile but a DIFFERENT URL (or published
                // under an older generation — e.g. resetLocalDataAndResync bumped
                // it +1) is now correctly discarded. Supplier pattern mirrors
                // currentProfileId — read on demand at onSuccess, NOT cached at
                // call site (so a mid-REST bundle publication is observable).
                // Null-safe: if the bundle is momentarily absent (test seam /
                // shutdown race), the suppliers return "" / 0L → guard degenerates
                // to a profileId-only check (no false drop).
                //
                // §需求13: previously the failure path was SILENT —
                // onNonFatalError → reportNonFatalIssue → Log.w only. The user
                // tapping the new manual refresh IconButton saw the spinner clear
                // with no explanation. Now ALSO emit a UiEvent.Error so the
                // SnackbarHost shows "Failed to refresh model list". reportNonFatalIssue
                // is kept for the structured log trail; the UiEvent is the
                // user-facing channel. Mirrors the ConnectionHealthProbe:622 +
                // SessionListRefreshOrchestrator:256 pattern.
                val bundleAtCall = repository.currentClientBundle()
                launchLoadProviders(
                    scope = appScope,
                    repository = repository,
                    slices = store.slices,
                    settingsManager = settingsManager,
                    hostProfileStore = hostProfileStore,
                    expectedProfileId = currentProfileId(),
                    currentProfileId = currentProfileId,
                    expectedEndpointFp = bundleAtCall?.endpointFp ?: "",
                    currentEndpointFp = { repository.currentClientBundle()?.endpointFp ?: "" },
                    expectedGeneration = bundleAtCall?.generation ?: 0L,
                    currentGeneration = { repository.currentClientBundle()?.generation ?: 0L },
                    onNonFatalError = { message, error ->
                        reportNonFatalIssue(TAG, message, error)
                        effectBus.tryEmitUiEvent(UiEvent.Error(R.string.model_management_refresh_failed))
                    },
                    // §需求13 rev-8 #2 (council #2 fix): disarm the single-flight latch on
                    // real fetch failure so the next loadInitialData / ON_RESUME auto-retries
                    // (weak-network cold-start recovery). Success path leaves the latch armed
                    // (no duplicate fetch).
                    onProvidersFirstFetchFailed = connectionCoordinator::resetProvidersFirstFetchGate,
                )
                true
            }
        }
        is ControllerEffect.LoadPendingPermissions -> {
            launchLoadPendingPermissions(appScope, repository, store.slices, effectBus, TAG)
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
            refreshOrchestrator.loadSessionsForEffect()
            true
        }
        /**
         * T13 (round-2 review fix — AppCore dispatch #6) + §final-gate I-1
         * (oracle §3.7): route the slim fan-out backoff/reset effects to
         * the process-level poller. Emitted by
         * [SessionSyncCoordinator.applySlimStatusFanOutSummary] for every
         * slim fan-out sweep:
         *
         *  - [ControllerEffect.RequestPollerBackoff] (retryableCount > 0):
         *    the poller schedules a bounded exponential + jitter backoff
         *    for the next sweep AND launches a single-flight retry job
         *    that fires one sweep after the delay. The default jitter
         *    sampler inside [ProcessStatusPoller.scheduleBackoff] kicks
         *    in (we do NOT pass jitter explicitly — see M2 fix).
         *  - [ControllerEffect.ResetPollerBackoff] (retryableCount == 0):
         *    the poller resets its backoff state to base (the success
         *    path; symmetric so the state machine stays coherent across
         *    sweeps) AND cancels any pending retry (no stale retry on
         *    top of fresh data).
         *
         * These effects carry no payload by design (the poller owns the
         * backoff state + the retry job; the coordinator just reports
         * the sweep outcome).
         */
        is ControllerEffect.RequestPollerBackoff -> {
            // §final-gate I-1 (oracle §3.7): schedule the slim fan-out
            // retry with the bounded delay returned by scheduleBackoff
            // (200ms → 400ms → … → 30s cap, ±20% jitter). The retry is
            // single-flight (requestSlimFanOutRetry cancels any prior
            // pending retry before launching the new one).
            val delayMs = processStatusPoller.scheduleBackoff()
            processStatusPoller.requestSlimFanOutRetry(delayMs)
            true
        }
        is ControllerEffect.ResetPollerBackoff -> {
            // §final-gate I-1 (oracle §3.7): a successful sweep cancels
            // any pending retry (no stale retry stacking on top of fresh
            // data) and resets the backoff state to base.
            processStatusPoller.resetBackoff()
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
