package cn.vectory.ocdroid.ui

import android.content.Context
import cn.vectory.ocdroid.data.model.*
import cn.vectory.ocdroid.data.cache.CacheRepository
import cn.vectory.ocdroid.data.cache.HydrateResult
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.TrafficTracker
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
    @ApplicationContext private val appContext: Context,
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
     * R-20 Phase 2: the gap-fill state machine (probe→detect→50-step fill).
     * Owned here so [catchUpAfterDisconnectOrForeground] / ChatViewModel can
     * delegate a detected gap to it; it is a @Singleton (plan §3 N3) so its
     * session-level Mutex map persists across the calls.
     */
    internal val gapFillCoordinator: cn.vectory.ocdroid.ui.chat.GapFillCoordinator,
    /**
     * R-20 Phase 1: persistent encrypted chat cache. Injected here so the
     * VerifyAndHydrate / EvictSession / EvictGroup effect handlers in
     * [dispatchSessionEffect] / [dispatchHostEffect] can call into it
     * directly (the controllers cannot inject it without churn).
     */
    internal val cacheRepository: CacheRepository,
    /**
     * R-20 Phase 1 (review-fix #1): provider for the current host's
     * serverGroupFp. Injected via the SAME `@Named("currentServerGroupFp")`
     * @Provides that every controller uses (ControllerModule), so AppCore's
     * fp derivation never drifts from the controllers'. Used by the
     * VerifyAndHydrate handler's post-verify 二次重检 (the suspend
     * verifyAndLoad call can straddle a user-initiated host switch; without
     * re-checking the fp after the suspend, the handler would inject the old
     * group's window into the new group's chat slice).
     */
    @Named("currentServerGroupFp") internal val currentServerGroupFp: () -> String,
    /** R-19 P2-5: the app-lifetime scope is now Hilt-provided
     *  ([cn.vectory.ocdroid.di.UiApplicationScopeModule], Main.immediate) so
     *  the migrated ViewModels inject the SAME singleton scope AppCore and
     *  the controllers use. AppCore keeps the field `internal` because the
     *  R-19-P2-2 dispatch helpers (in this file) and the AppCoreOrchestration
     *  extensions reach `appScope` directly. */
    @UiApplicationScope internal val appScope: CoroutineScope,
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
     * launchLoadMoreMessages; CatchUpActions launchCatchUp / launchCloseGap;
     * SessionViewModel.launchLoadMessagesForEffect; AppCoreOrchestration.
     * loadMessagesForEffect). Per plan §3 (v4 glmer I-3 + freegpt) the
     * closure captures [fp] AT FACTORY TIME so a profile switch mid-flight
     * cannot re-key a write to the wrong group. The hook:
     *  1. Synchronously writes the in-memory LRU ([SessionSwitcher.writeSessionWindow]).
     *  2. Asynchronously persists via [cacheRepository.putSessionWindow],
     *     fire-and-forget — IO failures only log via DebugLog.e; the user
     *     is never blocked (plan §5 不崩).
     *
     * `createdAt` and `workdir` are looked up from the sessionList slice
     * (main-thread-safe — the slice is Main.immediate-confined) so callers
     * don't have to thread them through every fetch callsite.
     *
     * §review-fix #2 (gpter #2): both the memory LRU write AND the persistent
     * write use the CAPTURED [fp] (not a re-read of currentServerGroupFp).
     * A host switch mid-flight would otherwise route the old fetch's data into
     * the new group's LRU slot.
     *
     * §review-fix #3 (gpter #3): session metadata (createdAt/workdir) lookup
     * includes `directorySessions` (not just `sessions`) — directory-only
     * sessions (surfaced via a connected-project fetch but not yet in the
     * main sessions list) would otherwise get createdAt=null → verifyAndLoad
     * always UnknownColdStart, defeating the persistent cache for the
     * "connected project sharing" feature.
     */
    internal fun makeCacheHook(fp: String): (String, CachedSessionWindow) -> Unit = { sid, window ->
        // 1. Synchronous in-memory write — uses CAPTURED fp (review #2).
        sessionSwitcher.writeSessionWindow(fp, sid, window)
        // 2. Async persistent write — fire-and-forget; createdAt/workdir are
        //    derived here (closure captured fp + sid) so the callsite signature
        //    stays (sessionId, window) and 6 callsites don't need plumbing.
        //    §review-fix #3: include directorySessions so directory-only
        //    sessions get their real createdAt/workdir cached.
        val sessionList = store.sessionListFlow.value
        val session = (sessionList.sessions + sessionList.directorySessions.values.flatten())
            .firstOrNull { it.id == sid }
        val createdAt = session?.time?.created
        val workdir = session?.directory ?: settingsManager.currentWorkdir ?: ""
        appScope.launch {
            runCatching { cacheRepository.putSessionWindow(fp, sid, createdAt, workdir, window) }
                .onFailure { DebugLog.e(TAG, "cache write failed for fp=$fp sid=$sid", it) }
        }
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

        // R-20 Phase 1 (C8, plan §3 矩阵 "applySavedSettings verify" 行):
        // cache self-consistency check on cold start. applySavedSettings is
        // non-suspend (called from this init block synchronously), so the
        // verifyFingerprint (suspend) hoists HERE — inside appScope.launch —
        // to avoid runBlocking the constructor. If the persisted currentSessionId
        // has a fingerprint mismatch in the cache (DB corruption / fp drift /
        // session recreated with a different createdAt), drop currentSessionId
        // so the user lands on the empty state instead of seeing a stale cached
        // window. This is NOT a cross-connection merge trigger (Phase 5 owns
        // that) — it is purely a defensive cache self-check. The persisted
        // SettingsManager value is left intact (the AppCore collector below
        // persists non-null changes; a null clear here does not overwrite the
        // stored id, so the next cold start re-tries the verify).
        val seededSid = store.chatFlow.value.currentSessionId
        if (seededSid != null) {
            appScope.launch {
                // §review-fix #1: use the injected currentServerGroupFp provider
                // (same source as every controller) instead of inline derivation.
                val fp = currentServerGroupFp()
                val seededSession = store.sessionListFlow.value.sessions.firstOrNull { it.id == seededSid }
                val createdAt = seededSession?.time?.created
                val result = runCatching {
                    cacheRepository.verifyFingerprint(fp, seededSid, createdAt)
                }.getOrNull()
                if (result is cn.vectory.ocdroid.data.cache.FingerprintResult.MismatchEvicted) {
                    DebugLog.i(
                        TAG,
                        "applySavedSettings verify: seeded currentSessionId=$seededSid fingerprint mismatch → clearing (cold-start fallback)"
                    )
                    store.mutateChat {
                        it.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap())
                    }
                }
            }
        }

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
            connectionCoordinator.cancelSse()
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
            // R-20 Phase 1 (plan §3 v4 round-3 handoff lines 122-140):
            // verify-before-hydrate. Emitted by SessionSwitcher.switchTo in
            // place of the old synchronous LRU seed + synchronous LoadMessages.
            // The handler runs cacheRepository.verifyAndLoad in a single Room
            // @Transaction (glmer I-2 TOCTOU) and dispatches the follow-up
            // LoadMessages inside the same launch.
            //
            // The handler guards on `currentSessionId` because the user may
            // have switched away between the synchronous effect emission and
            // this async dispatch (FIFO effects preserve order but not
            // session-identity-still-current) — without the guard we'd hydrate
            // a stale session's view.
            //
            // §review-fix #1 (gpter+glm-3 共识，头号): verifyAndLoad is suspend
            // (tens of ms Room IO). During the suspend the user may switch
            // sessions (same-group A→B → currentSessionId changes) OR switch
            // host (cross-group → serverGroupFp changes). Without a SECOND
            // guard after the suspend returns, the handler would:
            //  - glm-3 scenario: inject A's verified window into B's chat slice.
            //  - gpter scenario: cross-group same-sessionId collision (plan §0
            //    N1: ses_xxxx is not UUID) → inject old group's stale content.
            // The second guard re-checks BOTH the fp AND sessionId after
            // verifyAndLoad returns, BEFORE any slice mutation or LoadMessages.
            appScope.launch {
                // Entry guard: user may have switched away before this launch
                // even starts.
                if (effect.sessionId != store.chatFlow.value.currentSessionId) {
                    DebugLog.d(TAG, "VerifyAndHydrate dropped: session switched away (entry)")
                    return@launch
                }
                val r = cacheRepository.verifyAndLoad(effect.serverGroupFp, effect.sessionId, effect.createdAt)
                // 🔴 二次重检 (review-fix #1): verifyAndLoad suspend 期间用户
                // 可能切会话/切组。重检复合键 (fp + sessionId) 都未变才注入。
                // fp 用注入的 currentServerGroupFp provider（与 ControllerModule
                // 同源），不重读 hostProfileStore（已在 DI 层统一 ifBlank 兜底）。
                if (effect.serverGroupFp != currentServerGroupFp() ||
                    effect.sessionId != store.chatFlow.value.currentSessionId
                ) {
                    DebugLog.d(
                        TAG,
                        "VerifyAndHydrate dropped: fp or session changed during verifyAndLoad " +
                            "(effect.fp=${effect.serverGroupFp} current.fp=${currentServerGroupFp()} " +
                            "effect.sid=${effect.sessionId} current.sid=${store.chatFlow.value.currentSessionId})"
                    )
                    return@launch
                }
                when (r) {
                    is HydrateResult.Verified -> {
                        store.mutateChat {
                            it.copy(
                                messages = r.window.messages,
                                partsByMessage = r.window.partsByMessage,
                                olderMessagesCursor = r.window.olderMessagesCursor,
                                hasMoreMessages = r.window.hasMoreMessages
                            )
                        }
                        // resetLimit=false: keep the verified-cached older
                        // history; loadMessages merges the latest tail non-
                        // destructively (§preserveUnfetched in MessageActions).
                        loadMessagesForEffect(effect.sessionId, resetLimit = false)
                    }
                    HydrateResult.UnknownColdStart, HydrateResult.MismatchEvicted -> {
                        // No verified cache → cold-start fetch (latest window).
                        // resetLimit=true wipes any partial state and seeds a
                        // fresh olderMessagesCursor.
                        loadMessagesForEffect(effect.sessionId, resetLimit = true)
                    }
                }
            }
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
            // synchronous memory clear + async persistent evict. Memory first
            // so an immediate switchTo does not re-hydrate the just-evicted
            // window from the LRU; persistent evict is fire-and-forget (the
            // cache DB write cannot block the UI thread).
            sessionSwitcher.evictSession(effect.serverGroupFp, effect.sessionId)
            appScope.launch {
                runCatching { cacheRepository.evictSession(effect.serverGroupFp, effect.sessionId) }
                    .onFailure { DebugLog.e(TAG, "evictSession failed fp=${effect.serverGroupFp} sid=${effect.sessionId}", it) }
            }
            true
        }
        is ControllerEffect.EvictGroup -> {
            // R-20 Phase 1 (plan §3 矩阵 "异组切换" 行): synchronous group-
            // scoped memory clear + async group-scoped persistent evict.
            // NOT clearAll — only the previous group is wiped; the new group
            // (current after selectHostProfile) keeps its cache. Naming
            // explicitly EvictGroup (plan §3 N6 forbids ClearGroup).
            sessionSwitcher.clearMemoryForGroup(effect.serverGroupFp)
            appScope.launch {
                runCatching { cacheRepository.evictGroup(effect.serverGroupFp) }
                    .onFailure { DebugLog.e(TAG, "evictGroup failed fp=${effect.serverGroupFp}", it) }
            }
            true
        }
        else -> false
    }

    /** ConnectionCoordinator-owned effects. */
    internal fun dispatchConnectionEffect(effect: ControllerEffect): Boolean = when (effect) {
        is ControllerEffect.HostReconfigured -> {
            foregroundCatchUpController.onHostReconfigured()
            true
        }
        is ControllerEffect.LoadSessions -> {
            loadSessionsForEffect()
            true
        }
        is ControllerEffect.LoadAgents -> {
            launchLoadAgents(appScope, repository, store.slices, settingsManager, TAG)
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
     */
    internal fun handleSSEEvent(event: SSEEvent) {
        sessionSyncCoordinator.handleEvent(event)
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
