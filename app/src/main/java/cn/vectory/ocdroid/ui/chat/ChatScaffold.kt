// ChatScaffold.kt — Phase 1B chat shell (facade). The ~930-line god-composable
// was split into 4 internal components following the existing
// `rememberChatTopBarState` precedent (ChatTopBar.kt:195):
//
// Component map (all in `ui/chat/`):
//
//   ChatDerivedState.kt        — `rememberChatDerivedState(...)`
//     ~20 cross-slice derived values as per-field `State<T>` properties.
//     Route identity, session identity, context-usage, agent/model,
//     activity/matching, host profile, drawer session list.
//
//   ChatChromeState.kt         — `rememberChatChromeState(...)`
//     Chrome/overlay state: 4 rememberSaveable flags (slot-positionality
//     preserved), 4 remember dialog flags, drawer state + actions,
//     snackbar host, image picker.
//
//   ChatNavigationEffects.kt   — `ChatNavigationEffects(...)`
//     Pure-effect host: checkpoint consume, reconcile state machine,
//     parent/drawer BackHandlers (LIFO order), UiEvent snackbar,
//     stale-notice snackbar, compacting auto-clear.
//
// Stays in ChatScaffold (~690 lines after split):
//   - 11 collectAsStateWithLifecycle subscriptions
//   - isWide/showSessionSidebar (tablet-responsive layout)
//   - rememberChatTopBarState + topBarActions wiring
//   - chatBodyContent lambda (composable tree)
//   - SaveableStateHolder for sidebar / drawer branch
//   - ChatDrawerHost / RecentSessionsPane / ChatOverlayHost wiring
//   - Force-abort AppConfirmDialog
//
// §Item15b (archdebt-batch2): extraction completed 2026-08-08.

package cn.vectory.ocdroid.ui.chat

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.SettingsViewModel
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.ui.controller.questionRootIds
import cn.vectory.ocdroid.ui.theme.AppBottomSheet
import cn.vectory.ocdroid.ui.theme.AppConfirmDialog
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.ui.theme.StatusBanner
import kotlinx.coroutines.launch

/**
 * §1B: Phase 1B chat shell. Wraps the existing message-list / streaming /
 * gap-paging / draft / question / permission surfaces behind the new
 * M3-native chrome. Reads the same six slice flows as the old [ChatScreen]
 * (chatFlow / sessionListFlow / composerFlow / connectionFlow / settingsFlow
 * / hostFlow + unreadFlow + trafficFlow) so behaviour stays equivalent.
 *
 * @param onNavigateToSettings forwards to the new AppShell — the overflow
 *        menu's "System settings" entry.
 * @param onNavigateToSessions called when the user taps the session list
 *        entry point in [ChatEmptyState] to navigate to the session list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScaffold(
    chatVM: ChatViewModel,
    composerVM: ComposerViewModel,
    connectionVM: ConnectionViewModel,
    sessionVM: SessionViewModel,
    hostVM: HostViewModel,
    orchestratorVM: OrchestratorViewModel,
    settingsVM: SettingsViewModel,
    onNavigateToSettings: () -> Unit = {},
    /**
     * Called when the user taps the session-list entry point in [ChatEmptyState]
     * (all-tabs-closed empty state) to return to the Sessions home hub. AppShell
     * wires this to `backToHome()` (setLastRoute Sessions + popBackStack).
     * Session switching itself is NOT this callback — SessionPicker goes through
     * [ChatOverlayHost] → navigateToChat.
     */
    onNavigateToSessions: () -> Unit = {},
    /**
     * Opens the Chat-stack file preview at the current session's workdir,
     * locating the specific tapped file path when present. ChatScaffold
     * derives workdir from the current session and passes BOTH down — the
     * previous fix-6 shape dropped the path and passed only the session
     * directory, which broke file-path navigation (FilesPane.pathToShow was
     * always null).
     */
    onOpenChatFilePreview: (workdir: String?, path: String?) -> Unit = { _, _ -> },
    onOpenGitChanges: (String) -> Unit = {},
    /**
     * §home-hub T4: pop the Chat stack back to the Home hub. Invoked by:
     *   - phone (<600dp) top-left ArrowBack (ChatTopBar navigationIcon);
     *   - tablet drawer header ArrowBack (RecentSessionsDrawer);
     *   - the root-session system-Back handler (replaces the legacy
     *     "press again to exit" double-tap-confirm snackbar — root-session
     *     Back now goes Home directly);
     *   - tablet drawer header Home affordance.
     * Defaults to `{}` so the existing AppShell call site compiles; T7
     * supplies the real `popBackStack` / route-to-Home navigation.
     */
    onBackToHome: () -> Unit = {},
    /**
     * §home-hub T4: external hook fired when the tablet hamburger (Menu)
     * button opens the [RecentSessionsDrawer]. ChatScaffold ALSO opens its
     * owned `drawerState` (the drawer is an internal chrome concern), so
     * this param is purely an extension point for callers (default `{}`).
     */
    onOpenDrawer: () -> Unit = {},
    /** Active parameterized route id; null is the explicitly staged legacy
     * bare-chat compatibility surface. */
    routeSessionId: String? = null,
    /**
     * §chat-list-detail §11 / G6 (B5) + §scroll-guard-fix: the chat slot's
     * [SavedStateHandle]. Used as the shared backing store for sub-agent
     * checkpoints. Under chat→chat `launchSingleTop`, parent and child SHARE
     * one slot/handle (Navigation in-place replaces it — see ScrollCheckpoint.kt),
     * so checkpoints are NOT cleaned by entry pop; they are consumed-once on
     * return-to-capturing-parent (see [consumeAnySubAgentCheckpoint]).
     *
     * Three roles:
     *  1. WRITE — at openSubAgent time (user is on parent): the
     *     [onOpenSubAgentNavigate] callback writes the captured checkpoint to
     *     this handle under `subAgentCheckpoint:{childId}` BEFORE triggering
     *     navigation.
     *  2. READ + CONSUME — at re-composition (the user popped back from a
     *     child): the LaunchedEffect keyed on routeSessionId fires, calls
     *     [consumeAnySubAgentCheckpoint], and dispatches Restore via
     *     [ChatViewModel.requestScrollRestore] if a checkpoint is present.
     *  3. PROCESS-DEATH SURVIVAL — SavedStateHandle is Bundle-backed, so the
     *     checkpoint survives process death + config change; the
     *     LaunchedEffect re-fires on the rebuilt entry and replays the
     *     Restore.
     *
     * Null on the legacy bare-chat route (no per-entry handle); the
     * checkpoint paths are inert in that case.
     */
    routeSavedStateHandle: SavedStateHandle? = null,
) {
    // §PARITY (verbatim from ChatScreen): all six slice reads survive the
    // chrome swap. The streaming / settings / host / unread / traffic reads
    // feed the top-bar / status overlay / model picker / agent picker /
    // session-picker sheet / snackbar host — every consumer is downstream of
    // these flows exactly as before.
    //
    // §L5a (UI god-file split): each slice now ALSO keeps its State handle
    // (alongside the `by` delegate the rest of this composable reads). The
    // handle is passed to `rememberChatTopBarState` (ChatTopBar.kt) so the
    // derivedStateOf body reads `.value` on the same snapshot — that is what
    // carries the snapshot-tracking inside the derived lambda (see the
    // CORRECTNESS note on `rememberChatTopBarState`). One subscription per
    // slice; the `by` delegate and the handle read the SAME State instance.
    val connectionState = chatVM.connectionFlow.collectAsStateWithLifecycle()
    val connection by connectionState
    // §sse-feedback-ux (§1.3): derived SSE-disconnect status for the in-chat
    // banner + hysteresis-governed visibility. Pure projections of the
    // connection slice (see [cn.vectory.ocdroid.ui.deriveSseConnectionFeedback]
    // / [bannerHysteresisReducer]); collected here so the banner recomposes
    // only on a status change / ticker tick while a disconnect is visible
    // (WhileSubscribed upstream; healthy path = no churn).
    // §C2: banner visibility driven by process-scoped [BannerHysteresisOwner].
    val bannerVisibility by chatVM.bannerVisibility.collectAsStateWithLifecycle()
    val trafficState = connectionVM.trafficFlow.collectAsStateWithLifecycle()
    val traffic by trafficState
    val composerState = composerVM.composerFlow.collectAsStateWithLifecycle()
    val composer by composerState
    val settingsState = orchestratorVM.settingsFlow.collectAsStateWithLifecycle()
    val settings by settingsState
    val chatState = chatVM.chatFlow.collectAsStateWithLifecycle()
    val chat by chatState
    val sessionListState = chatVM.sessionListFlow.collectAsStateWithLifecycle()
    val sessionList by sessionListState
    val unreadState = chatVM.unreadFlow.collectAsStateWithLifecycle()
    val unread by unreadState
    val hostState = orchestratorVM.hostFlow.collectAsStateWithLifecycle()
    val host by hostState
    val recentWorkdirs by settingsVM.recentWorkdirs.collectAsStateWithLifecycle()
    val routeInstance by orchestratorVM.chatRouteInstanceFlow.collectAsStateWithLifecycle()
    // §Item15b: cross-slice derived state extracted into a dedicated
    // remember-factory. Every field is an individual State<T> (not one bundled
    // derivedStateOf) preserving the pre-extraction per-field recompose
    // granularity. All remember/derivedStateOf key lists are verbatim from the
    // pre-extraction code.
    val derivedState = rememberChatDerivedState(
        routeSessionId = routeSessionId,
        routeInstance = routeInstance,
        chatState = chatState,
        sessionListState = sessionListState,
        settingsState = settingsState,
        composerState = composerState,
        hostState = hostState,
        onOpenChatFilePreview = onOpenChatFilePreview,
    )
    // §Item15b: unwrap derived state fields for direct use in the scaffold
    // body — each `by` delegate reads `.value` on the individual State<T>,
    // preserving snapshot-tracking.
    val chromeSessionId by derivedState.chromeSessionId
    val onParameterizedRoute by derivedState.onParameterizedRoute
    val routeOwnedContent by derivedState.routeOwnedContent
    val renderedMessages by derivedState.renderedMessages
    val renderedPartsByMessage by derivedState.renderedPartsByMessage
    val renderedStreamingTexts by derivedState.renderedStreamingTexts
    val renderedStreamingReasoning by derivedState.renderedStreamingReasoning
    val sessionsById by derivedState.sessionsById
    val curSession by derivedState.curSession
    val effectiveBusy by derivedState.effectiveBusy
    val curCutoff by derivedState.curCutoff
    val curRevertMessageId by derivedState.curRevertMessageId
    val curSessionStatus by derivedState.curSessionStatus
    val cachedContextUsage by derivedState.cachedContextUsageState
    val visibleAgents by derivedState.visibleAgents
    val effectiveAgent by derivedState.effectiveAgent
    val effectiveModel by derivedState.effectiveModel
    val currentSessionIsRunning by derivedState.currentSessionIsRunning
    val isCurrentSessionSending by derivedState.isCurrentSessionSending
    val currentActivity by derivedState.currentActivity
    val matchingQuestions by derivedState.matchingQuestions
    val pendingQuestion by derivedState.pendingQuestion
    val pendingPermission by derivedState.pendingPermission
    val curHostProfile by derivedState.curHostProfile
    val recentSessionsForDrawer by derivedState.recentSessionsForDrawer
    val onChatFileClick = derivedState.onChatFileClick

    // §chat-list-detail §11 / G6 (B5) + §scroll-guard-fix: checkpoint consume
    // with a DIRECTION GUARD. Because chat→chat navigation uses `launchSingleTop`
    // (Navigation 2.8.x in-place slot replacement — see ScrollCheckpoint.kt),
    // parent and child SHARE one SavedStateHandle, and nested sub-agents
    // accumulate multiple `subAgentCheckpoint:*` keys on it. This LaunchedEffect
    // re-fires on every chat→chat transition (enter-child / return-parent /
    // nested / unrelated-jump) and sees the whole key set.
    //
    // Direction is decided inside [consumeAnySubAgentCheckpoint] by comparing
    // each checkpoint's `capturedFromSessionId` (the parent that captured it)
    // against `sid`:
    //  - capturedFrom == sid (return-to-parent): this session IS the capturing
    //    parent → consume + Restore.
    //  - capturedFrom != sid (enter-child / nested / unrelated-jump): NOT ours
    //    → skip WITHOUT consuming → openForRoute's Latest stands; the key
    //    stays for its own return path.
    //
    // Single-scroll-intent contract (§11): Restore vs Latest is decided in
    // exactly this one place. The dispatch goes through the unified
    // [AppAction.ScrollRequested] slot — the consumer in ChatMessageList sees
    // `behavior=Restore` and applies it.
    // §Item15b: checkpoint consume + onOpenSubAgentNavigate extracted to
    // ChatNavigationEffects.kt.
    val onOpenSubAgentNavigate = rememberOnOpenSubAgentNavigate(
        chromeSessionId = chromeSessionId,
        routeSavedStateHandle = routeSavedStateHandle,
        sessionVM = sessionVM,
        orchestratorVM = orchestratorVM,
    )

    // §Item15b: chrome/overlay state (picker flags, drawer, snackbar, image
    // picker) extracted to `rememberChatChromeState` (ChatChromeState.kt).
    val chromeState = rememberChatChromeState(
        composerVM = composerVM,
        onOpenDrawer = onOpenDrawer,
    )
    val context = LocalContext.current

    // §home-hub T4: responsive top-left affordance + tablet drawer gating.
    // Hoisted here (formerly computed deep inside the chat Surface at §B3
    // card-wrap) so it gates BOTH the ModalNavigationDrawer wrapper below
    // AND the wide-screen card wrap. Reads the M3 WindowSizeClass provided
    // by [LocalWindowSizeClass] (ChatScreen.kt:26 — calculated once in
    // MainActivity via calculateWindowSizeClass); falls back to a
    // screenWidthDp ≥ 600 check when no provider is present (previews /
    // unit tests). Phone (Compact) → ArrowBack top-left + system-Back to
    // Home; tablet (Medium/Expanded) → hamburger drawer.
    val isWide = LocalWindowSizeClass.current
        ?.let { it.widthSizeClass != WindowWidthSizeClass.Compact }
        ?: (LocalConfiguration.current.screenWidthDp >= 600)

    // §P2-item2: persistent left session sidebar on tablet landscape.
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val showSessionSidebar = isWide && isLandscape
    // §polish ④: responsive sidebar width by window bucket (Medium 280 /
    // Expanded 320) so a narrower tablet doesn't waste horizontal space.
    val sidebarWidth = if (LocalWindowSizeClass.current?.widthSizeClass == WindowWidthSizeClass.Expanded) {
        Dimens.sessionSidebarWidthExpanded
    } else {
        Dimens.sessionSidebarWidthMedium
    }

    // §Item15b: all side-effect blocks extracted to ChatNavigationEffects.kt.
    ChatNavigationEffects(
        chromeSessionId = chromeSessionId,
        curSession = curSession,
        chatState = chatState,
        routeSavedStateHandle = routeSavedStateHandle,
        chatVM = chatVM,
        sessionVM = sessionVM,
        orchestratorVM = orchestratorVM,
        drawerState = chromeState.drawerState,
        closeDrawerAction = chromeState.closeDrawerAction,
        snackbarHostState = chromeState.snackbarHostState,
        currentSessionIsRunning = currentSessionIsRunning,
        onSnackbarErrorShowDetail = { chromeState.errorDetail = it },
    )

    // §1B / §nav-redesign: derive ChatTopBarState inside a remembered
    // derivedStateOf so the TopAppBar recomposes only when its slice inputs
    // change. Slice reads match ChatTopBarState (see ChatTopBar.kt). The
    // second-row SessionTabStrip was RESTORED under ChatTopBar by the nav
    // redesign (quick switch between open root sessions); session switching is
    // via the strip AND the SessionPickerSheet (title tap = all/search/archive).
    // §Item15b: curHostProfile extracted to ChatDerivedState.kt.
    // §Nit: hoist the localised "No host" fallback outside the
    // derivedStateOf lambda (Compose forbids @Composable invocations
    // inside non-composable lambdas like derivedStateOf).
    val noHostFallback = stringResource(R.string.chat_no_host_fallback)
    // §L5a (UI god-file split): the derivedStateOf body now lives in
    // `rememberChatTopBarState` (ChatTopBar.kt). Slice State handles (not
    // values) are passed so the snapshot-tracking contract holds — see the
    // CORRECTNESS note there. The remember keys stay EXACTLY
    // `(noHostFallback, effectiveAgent, effectiveModel)` (effective* stay
    // value-params AND keys; slices are NOT keys — derivedStateOf tracks
    // them via the `.value` reads inside the lambda).
    val topBarState by rememberChatTopBarState(
        sessionListState = sessionListState,
        chatState = chatState,
        settingsState = settingsState,
        connectionState = connectionState,
        hostState = hostState,
        unreadState = unreadState,
        trafficState = trafficState,
        composerState = composerState,
        cachedContextUsageState = derivedState.cachedContextUsageState,
        effectiveAgent = effectiveAgent,
        effectiveModel = effectiveModel,
        noHostFallback = noHostFallback,
        chromeSessionId = chromeSessionId,
    )
    // §Q11 (2026-07-16): chromeSessionId is the remember key because the
    // onForceRefresh lambda below captures it to emit the refresh for the
    // ACTIVE route — without it the lambda would hold a stale id across a
    // route switch. §B2 rev-gpt MAJOR 1: route-owned identity, not flat
    // currentSessionId (which can lag the route flip).
    val topBarActions = remember(
        sessionVM,
        chatVM,
        chromeSessionId,
    ) {
        // §0.8.2 P2.3: the new overflow menu (Context / Todo / Agent / Model)
        // lives inside ChatTopBar; its open-callbacks fire the local
        // sheet/dialog state hoisted in this composable. The pickers' slice
        // reads (agents / providers / currentModel / disabled models) are
        // sourced below from the already-subscribed settings + chat slices,
        // so opening a sheet does not trigger a fresh subscription. The
        // AgentPickerSheet / ModelPickerSheet composables are defined in
        // PickerSheets.kt (now `internal`).
        //
        // §dead-onCompact-cleanup: the standalone "Compress" overflow item
        // was removed; compaction is triggered via the ContextUsageDialog's
        // own "Compress context" button (see showContextDialog below).
        //
        ChatTopBarActions(
            // §chat-list-detail §11 / G6: dedicated子→父 callback for the
            // breadcrumb — routes through SessionViewModel.returnToParent →
            // navigateToChat; AppShell's popRestore detects the parent on the
            // previous back-stack entry and popBackStack()s to it, preserving
            // its SavedStateHandle. The parent's LaunchedEffect replays the
            // Restore checkpoint.
            onNavigateParent = { sessionVM.returnToParent { pid -> orchestratorVM.navigateToChat(pid) } },
            onOpenContextDialog = { chromeState.showContextDialog = true },
            onOpenTodoDialog = { chromeState.showTodoDialog = true },
            onOpenAgentPicker = { chromeState.showAgentPicker = true },
            onOpenModelPicker = { chromeState.showModelPicker = true },
            // §sse-rest-fallback (强制刷新 = SSE-disconnect REST 兜底): the
            // user's explicit "Force refresh" — clear the current session
            // window, wipe messages/parts, full UNANCHORED re-fetch (bypass a
            // stale slim watermark so an SSE outage cannot leave the cleared
            // window empty), re-probe the connection, and resync the session
            // list. Routed through [AppCore.performForceRefresh] (5-step: clear
            // cache + ColdStartChatReset + forceInitialWindow fetch +
            // testConnection + LoadSessions) so the logic is shared + unit-
            // tested at the orchestration layer. When no session is open, only
            // the session-list resync applies.
            onForceAbort = { chromeState.showForceAbortConfirm = true },
            onForceRefresh = {
                val sid = chromeSessionId
                if (sid != null) {
                    chatVM.core.performForceRefresh(sid)
                } else {
                    chatVM.core.effectBus.tryEmitEffect(ControllerEffect.LoadSessions)
                }
            },
        )
    }

    // §B6: ChatSessionPager and ChatSessionTabStrip deleted. The
    // route-driven single detail pane (routeSessionId != null below) is
    // the sole chat surface. When routeSessionId is null and no draft
    // is active, ChatEmptyState renders instead.

    // §home-hub T4 (C2): wrap the chat body in ModalNavigationDrawer (now via
    // ChatDrawerHost — §L5a). The drawer is tablet-only by construction: the
    // hamburger (Menu) button that opens it renders ONLY on `isWide` form
    // factors (ChatTopBar navigationIcon branch). Phone has no Menu and no
    // open gesture (see gesturesEnabled inside ChatDrawerHost), so the drawer
    // stays unreachable there (phone uses ArrowBack → onBackToHome). Always
    // wrapping (rather than conditionally composing the Column twice) keeps
    // the body a single tree — the drawer content is cheap (a LazyColumn of
    // recent root sessions) and stays invisible/closed on phone.
    //
    // §opuser IMPORTANT-2: the drawer's recent-session list MUST mirror the
    // home page §2a "Recently Session" projection (req 5: tablet drawer =
    // "近期session（session页面的第一个section）"). The previous derivation
    // used `sessionList.sessions` only (matching SessionPickerSheet), which
    // OMITTED directorySessions — a per-workdir session present only in
    // directorySessions showed on home but NOT in the drawer. Now merged
    // identically to SessionsScreen.recentSessions: sessions +
    // directorySessions.values.flatten(), distinctBy id, filtered
    // parentId==null && !isArchived, sorted by time.updated desc, no cap
    // (home §2a applies none; the LazyColumn handles scrolling).
    //
    // §Item15b: recentSessionsForDrawer extracted to ChatDerivedState.kt.
    // §L5a (UI god-file split): the ModalNavigationDrawer wrapper + its
    // drawer-local state (drawerInteractionLocked + onStartNewSessionInDrawer)
    // MOVED into ChatDrawerHost (ChatDrawerHost.kt). The chat body Column
    // below is the trailing `content` lambda — verbatim, every local
    // ChatScaffold read inside it is still in scope. `closeDrawerAction`
    // + `drawerState` are now on `chromeState` (ChatChromeState.kt).
    // `onShowWorkdirPicker = { chromeState.pendingWorkdirPick = true }`
    // keeps pendingWorkdirPick owned+consumed in ChatScaffold.
    // §P2-item2: new-session handler for the persistent sidebar
    // (mirrors ChatDrawerHost's onStartNewSessionInDrawer but without
    // drawer close animation — the sidebar is always visible).
    val onStartNewSessionInSidebar: () -> Unit = remember(recentWorkdirs, sessionVM) {
        {
            when {
                recentWorkdirs.isEmpty() -> Unit
                recentWorkdirs.size == 1 -> {
                    sessionVM.createSessionInWorkdir(recentWorkdirs.single())
                }
                else -> {
                    chromeState.pendingWorkdirPick = true
                }
            }
        }
    }

    // §P2-item2: extract the chat body as a reusable composable lambda
    // so it can be shared between the drawer and the persistent sidebar.
    val chatBodyContent: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxSize()) {
            ChatTopBar(
                state = topBarState,
                actions = topBarActions,
                onTitleClick = { chromeState.showSessionPicker = true },
                // §home-hub T4 (C1/C3): responsive top-left affordance. ChatTopBar
                // branches on width internally (phone ArrowBack / tablet Menu).
                onBackToHome = onBackToHome,
                onOpenDrawer = chromeState.openDrawerAction,
            )

            // §persistent-restart-required (Medium-1): show a persistent error
            // banner while restartRequired is true (connection params changed,
            // restart needed). Tied to the state flag — NOT auto-dismiss.
            if (connection.restartRequired) {
                StatusBanner(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    border = null,
                ) {
                    Text(
                        text = stringResource(R.string.connection_restart_required_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.connection_restart_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            // §B6: SessionTabStrip removed. The SessionPickerSheet (opened by
            // tapping the ChatTopBar title) is the sole session-switching surface.

            // §PARITY: wide-screen card wrap mirrors ChatScreen 10/§B3. Phase 1B
            // keeps the wrapping Surface so the chat area looks identical on
            // Medium/Expanded. (§home-hub T4: `isWide` is now hoisted to the
            // top of ChatScaffold so it also gates the ModalNavigationDrawer
            // wrapper below — this branch just reuses the hoisted value.)
            val cardShape = if (isWide) MaterialTheme.shapes.large else RectangleShape
            androidx.compose.material3.Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(if (isWide) Modifier.padding(Dimens.spacing2) else Modifier),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = cardShape,
                shadowElevation = if (isWide) 2.dp else 0.dp,
                tonalElevation = if (isWide) Dimens.hairline else 0.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // §sse-feedback-ux (P2-1): persistent banner at the top of
                    // the chat area surfacing a sustained SSE disconnect / debug
                    // REST-only mode. Renders nothing on the healthy path
                    // (showBanner == false), so it is a zero-height no-op then.
                    // Refresh reuses the REST-fallback recovery path.
                    //
                    // rev-glm nit (方案 A): gate on chromeSessionId != null so the
                    // banner renders ONLY inside a real chat session. In the empty-
                    // session state (all tabs closed / no draft), chromeSessionId is
                    // null → refreshCurrentSession would silently no-op (no sid, no
                    // feedback), leaving the user tapping a dead button. The empty
                    // state already has its own connection-status UI (ChatEmptyState),
                    // so this banner's "chat-session recovery" semantics belong here
                    // only when a session is actually open.
                    if (chromeSessionId != null) {
                        SseDisconnectBanner(
                            visibility = bannerVisibility.visibility,
                            onRefresh = { chatVM.refreshCurrentSession(chromeSessionId) },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        // §B6: ChatSessionPager deleted. The route-aware detail
                        // (routeSessionId != null) is the sole chat surface.
                        if (routeSessionId != null) {
                            // Route-aware B2 detail: only LoadedContent owned by
                            // this route instance can become transcript UI.
                            val routeContent = routeOwnedContent
                            if (routeContent != null) {
                                ChatMessageList(
                                    chatVM = chatVM,
                                    composerVM = composerVM,
                                    sessionVM = sessionVM,
                                    orchestratorVM = orchestratorVM,
                                    onFileClick = onChatFileClick,
                                    onOpenChanges = onOpenGitChanges,
                                    onCopyMessage = { _, text -> copyToSystemClipboard(context, text) },
                                    onEditAndRerun = { messageId -> chatVM.editFromMessage(messageId) },
                                    onFork = { messageId -> sessionVM.forkSession(routeSessionId, messageId) },
                                    isCurrentSessionSending = isCurrentSessionSending,
                                    routeSessionId = routeSessionId,
                                    routeContent = routeContent,
                                    onOpenSubAgentNavigate = onOpenSubAgentNavigate,
                                )
                            } else {
                                ChatDetailSlice(
                                    routeId = routeSessionId,
                                    chatVM = chatVM,
                                    orchestratorVM = orchestratorVM,
                                )
                            }
                        } else if (composer.draftWorkdir == null) {
                            ChatEmptyState(
                                isConnected = connection.isConnected,
                                isConnecting = connection.isConnecting,
                                connectionPhase = connection.connectionPhase,
                                hostName = curHostProfile?.name
                                    ?: curHostProfile?.serverUrl
                                        ?.substringAfter("://")
                                        ?.substringBefore("/")
                                        ?: stringResource(R.string.chat_server_fallback),
                                onConnect = { connectionVM.testConnection() },
                                // §new2: when the user closed every tab
                                // (currentSessionId == null, no draft), the empty
                                // state's "connected + idle" branch offers a one-
                                // tap deep-link to the Sessions screen.
                                onNavigateToSessions = onNavigateToSessions,
                            )
                        }

                        // §1C: the single status slot (C.3 / D.2.1). Replaces the
                        // five competing overlays (thinking / retry / connecting /
                        // question / permission) that used to stack on top of
                        // the chat area. Only ONE of (Permission, Question,
                        // Retry, Compacting, Running, Connecting) renders at any
                        // time — see [StatusSlotPriority.pick] for the binding
                        // rule and the priority enum. The pending permission is
                        // pre-filtered to chat.currentSessionId (P5-7) at the
                        // call site; the slot does not re-apply the filter.
                        //
                        // §1C-FIX-①: the caller MUST NOT pre-filter the inputs
                        // beyond the P5-7 session-scope rule. The previous
                        // `curSessionStatus?.takeIf { !chat.isCompacting }` and
                        // the `curSessionStatus?.isRetry != true` filter on
                        // currentActivityText were over-eager: they hid a Retry
                        // status from `pick()` whenever Compacting was also
                        // true, breaking the C.3 priority (Retry > Compacting).
                        // pick() is now the SOLE decision point — we hand it
                        // the canonical inputs and let it return the winning
                        // class. sessionStatus flows in whole (including
                        // isRetry even during compaction) and the activity
                        // text is the raw value (Retry > Running means the
                        // text is ignored when Retry wins, exactly what the
                        // scheme specifies).
                        SnackbarHost(
                            hostState = chromeState.snackbarHostState,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                        StatusSlot(
                            permission = pendingPermission,
                            question = pendingQuestion,
                            sessionStatus = curSessionStatus,
                            isCompacting = chat.isCompacting,
                            currentActivityText = if (currentSessionIsRunning && currentActivity != null) {
                                @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                                currentActivity!!.text
                            } else {
                                null
                            },
                            // §1C-FIX-⑤: pass the startedAt values that the
                            // Compacting + Running branches need for the
                            // elapsed timer. The values are non-null when
                            // the corresponding state is active (see
                            // ChatActivityHelpers for how currentActivity
                            // sources startedAtMillis from the latest user
                            // message's time.created).
                            currentActivityStartedAtMillis = currentActivity?.startedAtMillis,
                            compactStartedAt = chat.compactStartedAt,
                            isConnecting = connection.isConnecting && !connection.isConnected,
                            // §T17 slimapi v1 §6.1: source the current
                            // session's lastError from the canonical T12
                            // store (SessionListState.sessionErrorsById).
                            // The lookup uses the SAME sid derivation as
                            // curSessionStatus above (chat.currentSessionId
                            // → sessionList.sessionStatuses), so the banner
                            // is in lockstep with the other session-scoped
                            // slot inputs. Null when the sid is absent from
                            // the map (no error / recovered) — the slot's
                            // LastError branch NEVER fires for an absent sid.
                            // Caller is the sole filter site (file doc rule).
                            lastError = chromeSessionId?.let { sessionList.sessionErrorsById[it] },
                            // §1C-FIX-⑧: scheme E.4 metadata. Sourced from
                            // the canonical slices: host (host.hostProfiles
                            // + currentHostProfileId), workdir (current
                            // session's directory or composer draft), session
                            // (current session's displayName), tool
                            // (permission.tool), target (permission.metadata
                            // .filepath — the most common target shape;
                            // could be extended in future for command-line
                            // targets).
                            permissionMetadata = ChatPermissionMetadata(
                                hostName = curHostProfile?.name,
                                workdirBasename = (curSession?.directory
                                    ?: composer.draftWorkdir)
                                    ?.split('/')
                                    ?.filter { it.isNotEmpty() }
                                    ?.lastOrNull(),
                                sessionName = curSession?.displayName,
                                // The "tool name" the user cares about is
                                // the permission string itself (e.g.
                                // "bash" / "edit" / "webfetch"). The
                                // [PermissionRequest.tool] field is a
                                // ToolRef (messageId / callId reference)
                                // and is NOT a human-readable tool name.
                                toolName = pendingPermission?.permission,
                                target = pendingPermission?.metadata?.filepath,
                            ),
                            onRespondPermission = { response ->
                                pendingPermission?.let { p ->
                                    // §Phase3b slim: thread the slimapi HMAC the
                                    // sidecar re-injects directory from. Null on
                                    // legacy single-dir path.
                                    orchestratorVM.respondPermission(p.sessionId, p.id, response, p.routeToken)
                                }
                            },
                            onReplyQuestion = { questionId, answers, onError ->
                                // §Phase3b slim: matchingQuestions holds the same
                                // QuestionRequest models StatusSlot renders
                                // (pendingQuestion = matchingQuestions.firstOrNull());
                                // lookup by id is exact, no implicit slice read.
                                val routeToken = matchingQuestions.firstOrNull { it.id == questionId }?.routeToken
                                orchestratorVM.replyQuestion(questionId, answers, routeToken, onError)
                            },
                            onRejectQuestion = { questionId, onError ->
                                val routeToken = matchingQuestions.firstOrNull { it.id == questionId }?.routeToken
                                orchestratorVM.rejectQuestion(questionId, routeToken, onError)
                            },
                            questionQueuePosition = pendingQuestion?.let { q ->
                                matchingQuestions.indexOfFirst { it.id == q.id } + 1
                            } ?: 1,
                            questionQueueTotal = matchingQuestions.size,
                        )
                    }
                }
            }

            // §PARITY: ChatInputBar moved to the outer Column (below the chat
            // Surface) so it is not wrapped by the chat card's background /
            // rounding / elevation. Composer.kt replaces ChatInputBar.kt and
            // subscribes to the same slices (composerFlow + settingsFlow) and
            // routes through the same domain methods.
            if (routeSessionId != null || chat.currentSessionId != null || composer.draftWorkdir != null) {
                // §1B-FIX (I5): Composer no longer takes connectionVM / hostVM —
                // it does not render any chrome that needs them (the surface
                // stays on composerFlow + settingsFlow + a narrow
                // currentModelFlow projection). Removing the dead injections
                // enforces "Composer must NOT subscribe to unrelated slices".
                val isSubagentSession = curSession?.parentId != null
                Composer(
                    chatVM = chatVM,
                    composerVM = composerVM,
                    orchestratorVM = orchestratorVM,
                    isBusy = currentSessionIsRunning || chat.isCompacting,
                    isAborting = chromeSessionId != null && chromeSessionId in sessionList.abortPendingSessionIds,
                    questionPending = pendingQuestion != null,
                    isSubagent = isSubagentSession,
                    onAddImages = chromeState.onAddImages,
                    onAbort = { chatVM.abortSession(chromeSessionId) },
                )
            }

            // §1C: the bottom-anchored ChatPermissionCard that used to live
            // here is REMOVED — the single StatusSlot (above the chat Surface)
            // now renders the permission card for the current session. Cross-
            // session pending items surface as a Sessions nav-bar badge (Phase
            // 1A / scheme D.1) and never appear in the chat area. The bottom
            // space that this Column would have occupied is now empty (the
            // message Surface above uses weight(1f), so it claims the full
            // vertical space and the Composer sits flush below).
        }
    }

    // §P2-item2: SaveableStateHolder that survives the branch swap
    // (when showSessionSidebar flips on rotation, chatBodyContent moves
    // between ChatDrawerHost and Row/Box parents — without this holder,
    // rememberSaveable keys are positional and get re-initialized,
    // losing scroll position, followBottom, and Composer sheet state).
    val chatBodySaveableHolder = rememberSaveableStateHolder()

    // §P2-item2: branch — persistent sidebar on tablet landscape,
    // drawer on phone / portrait-tablet.
    if (showSessionSidebar) {
        Row(modifier = Modifier.fillMaxSize()) {
            RecentSessionsPane(
                sessions = recentSessionsForDrawer,
                onSelect = { sid ->
                    if (sid != chromeSessionId) orchestratorVM.navigateToChat(sid)
                },
                onBackToHome = onBackToHome,
                onRefreshSessions = {
                    chatVM.core.effectBus.tryEmitEffect(ControllerEffect.LoadSessions)
                },
                onStartNewSession = onStartNewSessionInSidebar,
                isStartNewSessionEnabled = recentWorkdirs.isNotEmpty(),
                sessionErrorsByID = sessionList.sessionErrorsById,
                selectedSessionId = chromeSessionId,
                unreadSessions = unread.unreadSessions,
                pendingInputSessionIds = questionRootIds(sessionList.pendingQuestions, sessionsById)
                    .union(sessionList.pendingPermissions.map { it.sessionId }.toSet()),
                sessionStatuses = sessionList.sessionStatuses,
                // §polish ②: statusBarsPadding on the pane so its header starts
                // below the status bar (aligned with ChatTopBar's top baseline)
                // — the bare Column had no inset handling (intruded under the
                // status bar). Applied at the sidebar call site ONLY (the drawer
                // wraps RecentSessionsPane in ModalDrawerSheet which already
                // insets). §polish ④: sidebarWidth (Medium/Expanded) not the
                // old fixed 320dp.
                modifier = Modifier
                    .width(sidebarWidth)
                    .fillMaxHeight()
                    .statusBarsPadding(),
            )
            VerticalDivider(Modifier.fillMaxHeight())
            Box(modifier = Modifier.weight(1f)) {
                chatBodySaveableHolder.SaveableStateProvider(key = "chatBody") {
                    chatBodyContent()
                }
            }
        }
    } else {
        ChatDrawerHost(
            drawerState = chromeState.drawerState,
            sessions = recentSessionsForDrawer,
            recentWorkdirs = recentWorkdirs,
            sessionErrorsById = sessionList.sessionErrorsById,
            sessionVM = sessionVM,
            closeDrawerAction = chromeState.closeDrawerAction,
            onBackToHome = onBackToHome,
            onRefreshSessions = {
                chatVM.core.effectBus.tryEmitEffect(ControllerEffect.LoadSessions)
            },
            onShowWorkdirPicker = { chromeState.pendingWorkdirPick = true },
                // §P2-3 (rev-glm): guard against re-navigating to the current
                // session (idempotent freshness-token renewal, but wasteful);
                // mirrors the sidebar path's `if (sid != chromeSessionId)`.
                // closeDrawerAction() still runs in ChatDrawerHost.onSelect so
                // tapping the current row dismisses the drawer without a
                // redundant nav dispatch.
                onNavigateToChat = { sid ->
                    if (sid != chromeSessionId) orchestratorVM.navigateToChat(sid)
                },
        ) {
            chatBodySaveableHolder.SaveableStateProvider(key = "chatBody") {
                chatBodyContent()
            }
        }
    }

    // ── §B: 强制中止确认弹窗 ────────────────────────────────────────────────
    if (chromeState.showForceAbortConfirm) {
        AppConfirmDialog(
            title = stringResource(R.string.chat_force_abort_confirm_title),
            bodyContent = {
                Text(stringResource(R.string.chat_force_abort_confirm_message))
            },
            confirmText = stringResource(R.string.chat_force_abort),
            onConfirm = {
                chatVM.abortSessionRecursive(chromeSessionId)
                chromeState.showForceAbortConfirm = false
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { chromeState.showForceAbortConfirm = false },
        )
    }

    // ── Phase 1B sheets / overflows / dialogs (new) ──────────────────────
    ChatOverlayHost(
        showAgentPicker = chromeState.showAgentPicker,
        showModelPicker = chromeState.showModelPicker,
        showSessionPicker = chromeState.showSessionPicker,
        showTodoDialog = chromeState.showTodoDialog,
        showContextDialog = chromeState.showContextDialog,
        pendingWorkdirPick = chromeState.pendingWorkdirPick,
        errorDetail = chromeState.errorDetail,
        onDismissAgentPicker = { chromeState.showAgentPicker = false },
        onPickAgent = { name -> composerVM.selectAgent(name); chromeState.showAgentPicker = false },
        onDismissModelPicker = { chromeState.showModelPicker = false },
        onSwitchModel = { providerId, modelId ->
            composerVM.switchSessionModel(providerId, modelId)
            chromeState.showModelPicker = false
        },
        onClearModel = {
            composerVM.clearSessionModel()
            chromeState.showModelPicker = false
        },
        onDismissSessionPicker = { chromeState.showSessionPicker = false },
        // §B3: the SessionPickerSheet selection is a session-OPENING entry
        // point — route it through navigateToChat (route-aware pipeline) so it
        // mints the freshness token + dispatches openForRoute, matching the
        // SessionsScreen / drawer / deep-link entries. The legacy
        // sessionVM.selectSession (flat switchTo) is retired for entries that
        // open a chat.
        onSelectSession = { sessionId ->
            orchestratorVM.navigateToChat(sessionId)
            chromeState.showSessionPicker = false
        },
        onNewSession = {
            sessionVM.createSession()
            chromeState.showSessionPicker = false
        },
        onDismissTodo = { chromeState.showTodoDialog = false },
        onDismissContext = { chromeState.showContextDialog = false },
        onCompactContext = {
            chromeState.showContextDialog = false
            chatVM.compactSession()
        },
        onDismissWorkdirPick = { chromeState.pendingWorkdirPick = false },
        onPickWorkdir = { workdir ->
            chromeState.pendingWorkdirPick = false
            sessionVM.createSessionInWorkdir(workdir)
        },
        onDismissError = { chromeState.errorDetail = null },
        // Derived slice values
        agents = settings.agents.filter { it.isVisible },
        currentAgentName = effectiveAgent,
        providers = settings.providers,
        disabledModels = settings.disabledModels,
        currentModel = effectiveModel,
        // §session-picker-all-dirs (2026-07-26): merge sessions from ALL
        // attached directories (sessions + directorySessions), not just the
        // current workdir's sessions. Previously only `sessionList.sessions`
        // was passed, which meant the quick-switch picker showed only
        // sessions the user had opened in the current directory — while the
        // Home SessionsScreen shows sessions from every attached project.
        // Now both surfaces share the same merged source. The SessionPickerSheet's
        // internal filter (parentId==null, !isArchived, sort, take 10) handles
        // the presentation narrowing. Mirrors the recentSessionsForDrawer
        // derivation at ~L842 and the SessionsScreen merge at ~L215.
        sessions = remember(sessionList.sessions, sessionList.directorySessions) {
            (sessionList.sessions + sessionList.directorySessions.values.flatten())
                .distinctBy { it.id }
        },
        sessionStatuses = sessionList.sessionStatuses,
        activeSessionIds = sessionList.activeSessionIds,
        currentSessionId = chromeSessionId,
        unreadSessions = unread.unreadSessions,
        todos = sessionList.sessionTodos[chromeSessionId ?: ""] ?: emptyList(),
        cachedContextUsage = cachedContextUsage,
        recentWorkdirs = recentWorkdirs,
        questionSessionIds = questionRootIds(sessionList.pendingQuestions, sessionsById),
        permissionSessionIds = sessionList.pendingPermissions.map { it.sessionId }.toSet(),
        sessionErrorsById = sessionList.sessionErrorsById,
        composerVM = composerVM,
        sessionVM = sessionVM,
        chatVM = chatVM,
        connectionVM = connectionVM,
    )
}

// §0.8.2 P2.3: the standalone `ConversationOverflowMenu` composable that
// used to live here is REMOVED. The overflow DropdownMenu is now co-located
// with its ContextUsageRing trigger inside ChatTopBar (the fix for the
// top-left popup bug — the prior remote menu had no tight Box anchor).
// §dead-onCompact-cleanup: the 4 items (Context / Todo / Agent / Model) are
// composed inline in `ContextMenuCluster` (ChatTopBar.kt). Compaction is
// triggered via the ContextUsageDialog's own "Compress context" button, NOT
// a standalone overflow item. Archive was dropped (the destructive
// affordance moves to the Sessions screen long-press).


