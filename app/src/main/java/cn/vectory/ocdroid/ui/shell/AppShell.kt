package cn.vectory.ocdroid.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.SettingsViewModel
import cn.vectory.ocdroid.ui.chat.ChatScreen
import cn.vectory.ocdroid.ui.chat.ChatFilePreviewScreen
import cn.vectory.ocdroid.ui.files.FilesScreen
import cn.vectory.ocdroid.ui.files.FilesViewModel
import cn.vectory.ocdroid.ui.sessions.SessionsScreen
import cn.vectory.ocdroid.ui.settings.SettingsAboutRoute
import cn.vectory.ocdroid.ui.settings.SettingsAppearanceRoute
import cn.vectory.ocdroid.ui.settings.SettingsDebugRoute
import cn.vectory.ocdroid.ui.settings.SettingsHostsRoute
import cn.vectory.ocdroid.ui.settings.SettingsModelsRoute
import cn.vectory.ocdroid.ui.settings.SettingsNotificationsRoute
import cn.vectory.ocdroid.ui.settings.SettingsScreen
import cn.vectory.ocdroid.ui.workspace.GitScreen

/**
 * Sole application shell and owner of top-level navigation chrome.
 *
 * §home-hub T7: this is now a hub-and-spoke graph — Sessions is the root
 * (startDestination + initial NavState.lastRoute), and the bottom nav bar has
 * been removed. Top-level destinations (Chat / Files / Git / Settings) are
 * spokes reached from the home hub's per-row actions or the server-status
 * dialog; system-back on any spoke pops back to Sessions (root-back exits the
 * app via the system handler). Cold start stays on home: no
 * `restoreLastRoute()` runs (the method was removed — dead code after the
 * bottom-bar removal); `requestedRoute == Sessions == startDestination`
 * so the `LaunchedEffect(requestedRoute)` hop short-circuits. Explicit
 * `requestNavigate(...)` / `navigateToChat(...)` (deeplink / notification /
 * in-session nav / new-draft → Chat / server popup → Settings) still fire
 * the hop — requestNavigate always bumps navEpoch so the synchronizer re-fires
 * even on a same-target re-entry (items 6 + 8).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun AppShell(orchestratorVM: OrchestratorViewModel) {
    // Activity-scoped until the later feature graph isolation phase.
    val chatVM: ChatViewModel = hiltViewModel()
    val composerVM: ComposerViewModel = hiltViewModel()
    val connectionVM: ConnectionViewModel = hiltViewModel()
    val sessionVM: SessionViewModel = hiltViewModel()
    val hostVM: HostViewModel = hiltViewModel()
    val settingsVM: SettingsViewModel = hiltViewModel()
    // FIXME(P4-features): scope FilesViewModel to the files graph; chat preview must not share it.
    val filesVM: FilesViewModel = hiltViewModel()

    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val destRoute = entry?.destination?.route
    val currentRoute = NavRoute.fromRouteKey(destRoute)
    val isNestedSettings = NavRoute.isNestedSettingsRoute(destRoute)
    val navState by orchestratorVM.navFlow.collectAsStateWithLifecycle()
    val sessionListState by sessionVM.sessionListFlow.collectAsStateWithLifecycle()
    val chatState by chatVM.chatFlow.collectAsStateWithLifecycle()
    // §unified-nav A5.5: the chat-route-instance token for the staged-route
    // bridge (the 4-way CAS that decides whether the bare "chat" composable
    // renders the materialized session's detail during the one-frame gap
    // before the chat/{sessionId} NavBackStackEntry manifests).
    val chatRouteInstance by orchestratorVM.chatRouteInstanceFlow.collectAsStateWithLifecycle()

    fun navigateTopLevel(route: NavRoute) {
        navController.navigate(route.route) {
            popUpTo(navController.graph.findStartDestination().id)
            launchSingleTop = true
        }
    }

    // §unified-nav A3: backToHome. The explicit closeDetail() that used to live
    // here was MIGRATED to the destination-transition listener below (the SOLE
    // place closeDetail runs for chat→Sessions), so backToHome no longer calls
    // it — its popBackStack(Sessions) triggers the listener which does
    // closeDetail exactly once. setLastRoute(Sessions) → requestNavigate(
    // Sessions) (the explicit nav-command setter that ALWAYS bumps navEpoch so
    // the synchronizer re-fires even when lastRoute already reads "sessions").
    // The syncer's alreadyThere guard prevents a double-push (after popBackStack
    // moves to Sessions, the synchronizer fires but finds NavController already
    // at Sessions → no-op).
    fun backToHome() {
        orchestratorVM.requestNavigate(NavRoute.Sessions)
        if (!navController.popBackStack(NavRoute.Sessions.route, inclusive = false)) {
            navigateTopLevel(NavRoute.Sessions)
        }
    }

    // §unified-nav A3: the REAL destination listener (NOT a loose LaunchedEffect).
    // Registered via DisposableEffect so it is unregistered on dispose. This is
    // the SOLE place that:
    //  (1) reconciles the PASSIVE mirror (setLastRoute — no epoch bump) when the
    //      committed destination diverges from navState.lastRoute (item 6:
    //      system BACK pops chat→sessions while the mirror still reads
    //      "chat/…"); and
    //  (2) runs closeDetail() on the EXACT committed transition
    //      chat/{sessionId} → sessions (restoring the leave-detail transaction
    //      that native popBackStack bypasses — route-instance/CAS invalidation
    //      + clear route-owned content). CRITICAL GATING: closeDetail fires ONLY
    //      on this exact transition; it does NOT fire on predictive-back CANCEL
    //      (destination unchanged → no listener fire), chat/{parent}→chat/{child},
    //      chat/{child}→chat/{parent}, chat→preview, preview→chat, config change,
    //      or initial app entry to Sessions.
    //  (3) writes NavState.activeDestination + epoch (A5.2 passive — never fires
    //      the syncer) so the materialize CAS can detect mid-create navigation.
    //
    // No feedback loop: the observer key is the COMMITTED destination (not
    // navState), and setLastRoute does NOT bump navEpoch, so the nav
    // synchronizer's alreadyThere guard no-ops (NavController already moved to
    // the destination that triggered this listener).
    val navStateRef = rememberUpdatedState(navState)
    var prevCommittedPattern by remember { mutableStateOf<String?>(null) }
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, arguments ->
            val newPattern = destination.route
            // §unified-nav A5.2: resolve the ACTUAL destination literal + write
            // it to NavState.activeDestination (passive setter — never touches
            // lastRoute/navEpoch, so the syncer never re-fires from this).
            val actualDestination = resolveActualDestination(destination.route, arguments)
            orchestratorVM.setActiveDestination(actualDestination)
            // §unified-nav A3.1: passive mirror reconciliation. When the
            // committed destination is Sessions but the mirror still reads a
            // chat route, reconcile the mirror so item 6 (new session → BACK →
            // "+" again) does not strand the mirror on a stale "chat/…" value.
            // setLastRoute is passive (no epoch bump) → no loop.
            if (newPattern == NavRoute.Sessions.route &&
                navStateRef.value.lastRoute != NavRoute.Sessions.route
            ) {
                orchestratorVM.setLastRoute(NavRoute.Sessions)
            }
            // §unified-nav A3.2: closeDetail on the EXACT committed transition
            // chat/{sessionId} → sessions. This is the SOLE closeDetail site
            // for chat→Sessions (the explicit call was migrated OUT of
            // backToHome so it runs exactly once). prevCommittedPattern is null
            // on the initial fire (app entry to Sessions) → no closeDetail.
            if (prevCommittedPattern == "chat/{sessionId}" &&
                newPattern == NavRoute.Sessions.route
            ) {
                orchestratorVM.closeDetail()
            }
            prevCommittedPattern = newPattern
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose {
            navController.removeOnDestinationChangedListener(listener)
        }
    }

    // §chat-list-detail §12 B1: the UNIFIED nav synchronizer. Observes
    // navState.lastRoute (the raw route STRING, not a NavRoute identity) and
    // navigates the NavController to match. Replaces BOTH the old
    // `LaunchedEffect(requestedRoute)` mirror (which mis-routed "chat/ses_X"
    // → NavRoute.Chat → bare "chat" via fromRouteKey) AND the B0.5
    // `chatNavEvents` SharedFlow workaround. navigateToChat now writes
    // navState.lastRoute directly (the B0.5 deviation is resolved).
    //
    // §B3-C2: [navState.navEpoch] is added as a second key so the synchronizer
    // re-fires even when [lastRoute] is structurally identical to the current
    // value — which happens when [forceNavigateToSessions] bumps [navEpoch]
    // while the user is on Files/Git (those routes do NOT update [NavState],
    // so [lastRoute] stays `"sessions"`). Without [navEpoch], the
    // [DerivedStateFlow] would not emit (equals unchanged) and the
    // synchronizer would never fire, trapping the user on Files/Git.
    // [setLastRoute] does NOT touch [navEpoch]; navigateToChat and
    // forceNavigateToSessions both bump it, so normal route transitions
    // are unaffected.
    //
    // Guard: skip navigation when the NavController is already at the target.
    // For parameterized chat/{sessionId}, compares the sessionId arg; for
    // non-parameterized routes, compares the route string directly. This
    // prevents redundant pushes (e.g. backToHome → popBackStack + setLastRoute
    // → synchronizer fires but guard skips because NavController is already
    // at Sessions).
    //
    // Cold-start (§5 P3): NavState defaults to Sessions.route (see NavState.kt),
    // which matches NavHost.startDestination. The synchronizer fires on first
    // composition but the guard finds NavController already at Sessions →
    // no-op. The persisted settingsManager.lastRoute is NOT loaded into
    // NavState on cold start (the old restoreLastRoute() was deleted in T7-C5),
    // so a persisted "chat/ses_X" never auto-restores. Cold start ALWAYS lands
    // on Sessions.
    //
    // Back handling: the synchronizer uses popUpTo(startDestination) which
    // keeps the back stack flat (Sessions → target). System Back from chat/{id}
    // pops to Sessions naturally.
    //
    // §chat-list-detail §11 / G6 (B5): chat-to-chat navigation preserves the
    // parent's NavBackStackEntry (and its SavedStateHandle, which carries the
    // openSubAgent checkpoint). Two cases:
    //  (1) PUSH (openSubAgent child push): target=child, previousBackStackEntry
    //      is the parent (sessionId != target) → plain navigate() without
    //      popUpTo. Stack: [Sessions, parent, child].
    //  (2) POP-RESTORE (navigateToChat parent): target=parent,
    //      previousBackStackEntry.sessionId == target → popBackStack().
    //      Stack: [Sessions, parent]. The OLD parent entry (with its
    //      SavedStateHandle checkpoint) is re-activated — Restore fires via
    //      the parent's ChatScaffold LaunchedEffect.
    // The non-chat-to-chat paths (Sessions → chat, chat → Sessions, Files /
    // Git / Settings transitions) keep the original popUpTo behavior (flat
    // backstack, system-back pops to Sessions).
    //
    // §B5 BLOCK-fix (rev-gpt CRITICAL): the pop-restore branch is REQUIRED so
    // returnToParent does NOT push a new parent entry on top of the child. The
    // prior B5 implementation always plain-navigated on chat→chat, which
    // produced [Sessions, parent(old), child, parent(NEW)] — the NEW parent's
    // fresh SavedStateHandle had no checkpoint, so Restore never fired.
    LaunchedEffect(navState.lastRoute, navState.navEpoch) {
        val target = navState.lastRoute
        val currentDest = navController.currentDestination
        val alreadyThere = when {
            // Parameterized chat route: compare the sessionId arg.
            currentDest?.route == "chat/{sessionId}" && target.startsWith("chat/") -> {
                val targetSid = target.removePrefix("chat/")
                navController.currentBackStackEntry?.arguments?.getString("sessionId") == targetSid
            }
            // Non-parameterized: compare route string directly.
            else -> currentDest?.route == target
        }
        if (!alreadyThere) {
            val chatToChat = currentDest?.route == "chat/{sessionId}" && target.startsWith("chat/")
            // §B5 BLOCK-fix: pop-restore when target matches the immediately-
            // previous back-stack entry. This is the子→父 return path —
            // the parent entry is one pop away.
            val targetSid = target.removePrefix("chat/").takeIf { chatToChat }
            val previousSid = navController.previousBackStackEntry
                ?.arguments?.getString("sessionId")
            val popRestore = chatToChat &&
                targetSid != null &&
                previousSid != null &&
                targetSid == previousSid
            if (popRestore) {
                // Restore the existing parent entry — preserves its
                // SavedStateHandle (and the checkpoint). The current child
                // entry is popped. Defensive fallback: if popBackStack
                // returns false (framework state inconsistency — the
                // previousBackStackEntry check above GUARANTEES a previous
                // entry, so this branch should be unreachable), fall through
                // to a navigate+popUpTo so the user is never stranded.
                if (!navController.popBackStack()) {
                    navController.navigate(target) {
                        popUpTo(navController.graph.findStartDestination().id)
                        launchSingleTop = true
                    }
                }
            } else {
                navController.navigate(target) {
                    if (!chatToChat) {
                        popUpTo(navController.graph.findStartDestination().id)
                    }
                    launchSingleTop = true
                }
            }
        }
    }
    LaunchedEffect(currentRoute) {
        if (currentRoute != NavRoute.Chat) orchestratorVM.clearDraftIfActive()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoute.Sessions.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                // §IME-OWNER (sole): AppShell's NavHost is the UNIQUE IME padding
                // owner in the tree. Composer has been deliberately stripped of
                // its own .imePadding() to prevent double displacement (rev-2
                // finding). The NavHost sits above every route (Chat, Sessions,
                // Files, Git, Settings), so ALL content is uniformly shrunk by
                // the IME height — the composer (at the bottom of ChatScaffold's
                // Column) sits flush against the IME top, and the message area
                // (weight(1f) above the composer) resizes correctly. DO NOT add
                // another .imePadding() or .consumWindowInsets(ime) below this
                // level without explicit rev-2 sign-off: any child imePadding
                // re-creates the two-owner ambiguity that was intentionally
                // collapsed here. Non-Chat routes whose text fields live in
                // AppFormDialog / AlertDialog (Tier-C) get IME from the dialog
                // host, NOT from this padding; this top-level safety net handles
                // any remaining non-dialog input surface uniformly.
                .imePadding(),
        ) {
            composable(NavRoute.Chat.route) {
                // §unified-nav A5.5: staged-route bridge. Because NavHost
                // manifests the chat/{sessionId} entry ONE FRAME after the
                // materialize CAS committed the route, the bare "chat"
                // composable computes a STRICT staged id (4-way CAS: requested
                // route id == currentSessionId == content.sessionId AND
                // content.routeInstance == live token) and passes it as
                // routeSessionId. This is NOT a relaxed flat-currentSessionId
                // render — it enforces the structural+temporal CAS using the
                // already-committed store route until the NavController entry
                // appears. Once chat/{sessionId} exists it takes over.
                val stagedSid = navState.lastRoute
                    .takeIf { it.startsWith("chat/") }
                    ?.removePrefix("chat/")
                    ?.takeIf { sid ->
                        chatState.currentSessionId == sid &&
                            chatState.content?.sessionId == sid &&
                            chatState.content?.routeInstance == chatRouteInstance
                    }
                ChatScreen(
                    chatVM = chatVM,
                    composerVM = composerVM,
                    connectionVM = connectionVM,
                    sessionVM = sessionVM,
                    hostVM = hostVM,
                    orchestratorVM = orchestratorVM,
                    settingsVM = settingsVM,
                    routeSessionId = stagedSid,
                    onNavigateToSettings = { orchestratorVM.requestNavigate(NavRoute.Settings) },
                    // §CRITICAL-1: explicit pop-to-Sessions (not just
                    // setLastRoute) — see backToHome() doc above.
                    onNavigateToSessions = { backToHome() },
                    // §home-hub T4/T7: phone ArrowBack + tablet drawer header
                    // Home + root-session system-Back all route to the hub.
                    onBackToHome = { backToHome() },
                    // The drawer is opened/closed internally by ChatScaffold
                    // (drawerState is owned there); this is the external
                    // extension hook, no-op from the shell.
                    onOpenDrawer = {},
                    onOpenChatFilePreview = { workdir, path ->
                        navController.navigate(NavRoute.chatPreviewRoute(workdir, path))
                    },
                    onOpenGitChanges = { sessionId ->
                        navController.navigate(NavRoute.gitRoute(sessionId))
                    },
                )
            }
            // §chat-list-detail §12 B0.5: chat/preview MUST be registered
            // BEFORE chat/{sessionId} — NavController matches in declaration
            // order, so the literal "chat/preview" must win over the wildcard
            // "chat/{sessionId}" (otherwise preview is intercepted with
            // sessionId="preview").
            composable(
                route = NavRoute.chatPreviewRoutePattern,
                arguments = listOf(
                    navArgument("workdir") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("path") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { routeEntry ->
                ChatFilePreviewScreen(
                    repository = filesVM.repository,
                    workdir = routeEntry.arguments?.getString("workdir")?.takeIf { it.isNotBlank() },
                    path = routeEntry.arguments?.getString("path")?.takeIf { it.isNotBlank() },
                    onClose = { navController.popBackStack() },
                )
            }
            // §chat-list-detail §12 B0.5: the THIN chat/{sessionId} route —
            // the route-driven render path proving P1 (content.sessionId==
            // routeId) + P6 (freshness CAS). Registered AFTER chat/preview so
            // the literal preview pattern wins. Coexists with the bare "chat"
            // composable above (old path for non-migrated entries). B1 removes
            // the bare route + unifies.
            composable(
                route = "chat/{sessionId}",
                arguments = listOf(
                    navArgument("sessionId") { type = NavType.StringType },
                ),
            ) { routeEntry ->
                val sessionId = routeEntry.arguments?.getString("sessionId")
                if (sessionId != null) {
                    ChatScreen(
                        chatVM = chatVM,
                        composerVM = composerVM,
                        connectionVM = connectionVM,
                        sessionVM = sessionVM,
                        hostVM = hostVM,
                        orchestratorVM = orchestratorVM,
                        settingsVM = settingsVM,
                        routeSessionId = sessionId,
                        // §chat-list-detail §11 / G6 (B5): pass the route
                        // entry's SavedStateHandle so ChatScaffold can use it
                        // as the per-entry backing store for sub-agent
                        // checkpoints (protocol 2 — parent-keyed-by-child).
                        // The handle's lifecycle is bound to this
                        // NavBackStackEntry; pop auto-cleans the checkpoint.
                        routeSavedStateHandle = routeEntry.savedStateHandle,
                        onNavigateToSettings = { orchestratorVM.requestNavigate(NavRoute.Settings) },
                        onNavigateToSessions = { backToHome() },
                        onBackToHome = { backToHome() },
                        onOpenChatFilePreview = { workdir, path ->
                            navController.navigate(NavRoute.chatPreviewRoute(workdir, path))
                        },
                        onOpenGitChanges = { id -> navController.navigate(NavRoute.gitRoute(id)) },
                    )
                }
            }
            composable(NavRoute.Sessions.route) {
                // §home-hub T3/T7-C4: Sessions is the HOME HUB. All T3
                // callbacks are wired: the server-status IconButton reads
                // connection/host VMs; the per-project row IconButtons route
                // to Files/Git; the server-management dialog routes to
                // Settings; session-row tap switches to Chat. Home is root,
                // so showBackNavigation = false.
                SessionsScreen(
                    viewModel = sessionVM,
                    composerVM = composerVM,
                    orchestratorVM = orchestratorVM,
                    settingsVM = settingsVM,
                    repository = filesVM.repository,
                    connectionVM = connectionVM,
                    hostVM = hostVM,
                    // §unified-nav A2: new-draft → Chat is an explicit nav intent
                    // → requestNavigate (always bumps navEpoch so re-entry works
                    // even when the mirror already reads "chat" — item 6).
                    onSwitchToChat = { orchestratorVM.requestNavigate(NavRoute.Chat) },
                    // §chat-list-detail §12 B0.5: the ONE entry swap — the
                    // Sessions→tap→chat path uses navigateToChat (route-driven
                    // chat/{id} + LoadedContent + freshness token). Other entries
                    // (Files / picker / drawer / new-session) STAY on the old
                    // onSwitchToChat path above (B3 migrates them).
                    onNavigateToChat = { sessionId -> orchestratorVM.navigateToChat(sessionId) },
                    onOpenFiles = { workdir, _ ->
                        navController.navigate(NavRoute.filesRoute(workdir, null))
                    },
                    onOpenGit = { workdir ->
                        navController.navigate(NavRoute.gitRoute(workdir = workdir))
                    },
                    // §unified-nav A2 (item 8): server popup → Settings is an
                    // explicit nav intent → requestNavigate (always bumps
                    // navEpoch so it fires even when the mirror already reads
                    // "settings" from a stale state).
                    onNavigateToSettings = { orchestratorVM.requestNavigate(NavRoute.Settings) },
                    onLongClickServer = { navController.navigate(NavRoute.settingsDebugRoute) },
                    showBackNavigation = false,
                )
            }
            composable(
                route = NavRoute.filesRoutePattern,
                arguments = listOf(
                    navArgument("workdir") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("path") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { routeEntry ->
                val explicitWorkdir = routeEntry.arguments?.getString("workdir")?.takeIf { it.isNotBlank() }
                // §home-hub T5: FilesScreen is browser-only now (workdir from
                // initialWorkdir). Back exits to home (Sessions).
                FilesScreen(
                    viewModel = filesVM,
                    orchestratorVM = orchestratorVM,
                    sessionVM = sessionVM,
                    settingsVM = settingsVM,
                    composerVM = composerVM,
                    pathToShow = routeEntry.arguments?.getString("path")?.takeIf { it.isNotBlank() },
                    sessions = sessionListState.sessions,
                    activeSessionId = chatState.currentSessionId,
                    initialWorkdir = explicitWorkdir,
                    // §unified-nav A2: Files legacy switch → Chat is an explicit
                    // nav intent → requestNavigate.
                    onSwitchToChat = { orchestratorVM.requestNavigate(NavRoute.Chat) },
                    // §CRITICAL-1: FilesScreen's root-tier onExit must reach
                    // Sessions via the explicit pop (not just setLastRoute,
                    // which no-ops because Files entry doesn't update
                    // navState). See backToHome() doc above.
                    onExit = { backToHome() },
                )
            }
            composable(
                route = NavRoute.gitRoutePattern,
                arguments = listOf(
                    navArgument("session") { type = NavType.StringType; nullable = true; defaultValue = null },
                    // §home-hub T7-C4: second nullable arg — the home hub's
                    // per-project Git IconButton navigates with workdir only.
                    navArgument("workdir") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { routeEntry ->
                val sessionId = routeEntry.arguments?.getString("session")?.takeIf { it.isNotBlank() }
                val workdir = routeEntry.arguments?.getString("workdir")?.takeIf { it.isNotBlank() }
                // §files-git-readonly-workdir: GitScreen's WorkdirControl is
                // now read-only — recentWorkdirs / defaultWorkdir / onSelect
                // are no longer consumed. Kept flowing for signature
                // stability.
                GitScreen(
                    filesVM = filesVM,
                    sessionVM = sessionVM,
                    hostVM = hostVM,
                    orchestratorVM = orchestratorVM,
                    savedStateHandle = routeEntry.savedStateHandle,
                    initialSessionId = sessionId,
                    initialWorkdir = workdir,
                    onBack = { backToHome() },
                )
            }
            composable(NavRoute.Settings.route) {
                SettingsScreen(
                    viewModel = hostVM,
                    composerVM = composerVM,
                    connectionVM = connectionVM,
                    settingsVM = settingsVM,
                    // §unified-nav A2: Settings is entered via requestNavigate(
                    // Settings) from the server popup / Chat overflow (always
                    // bumps navEpoch). backToHome() (requestNavigate(Sessions)
                    // + popBackStack) is the single canonical back path.
                    onBack = { backToHome() },
                    onNavigateSection = navController::navigate,
                )
            }
            composable(
                NavRoute.settingsHostsRoute,
                exitTransition = { ExitTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                SettingsHostsRoute(hostVM, connectionVM) { navController.popBackStack() }
            }
            composable(
                NavRoute.settingsAppearanceRoute,
                exitTransition = { ExitTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                SettingsAppearanceRoute(settingsVM) { navController.popBackStack() }
            }
            composable(
                NavRoute.settingsModelsRoute,
                exitTransition = { ExitTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                SettingsModelsRoute(composerVM, settingsVM) { navController.popBackStack() }
            }
            composable(
                NavRoute.settingsNotificationsRoute,
                exitTransition = { ExitTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                SettingsNotificationsRoute { navController.popBackStack() }
            }
            composable(
                NavRoute.settingsDebugRoute,
                exitTransition = { ExitTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                SettingsDebugRoute(hostVM, settingsVM) { navController.popBackStack() }
            }
            composable(
                NavRoute.settingsAboutRoute,
                exitTransition = { ExitTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                SettingsAboutRoute(hostVM, settingsVM) { navController.popBackStack() }
            }
        }
    }

    // §home-hub T7-C6 (hub semantics): non-root spokes (Git / Settings) → pop
    // back to Sessions (home). Sessions (root) → disabled (system-back exits).
    // Nested Settings → disabled (sub-route scaffolds pop naturally).
    //
    // §CRITICAL-1: uses backToHome() (explicit popBackStack + requestNavigate)
    // so Git back works even though Git entry doesn't update navState (direct
    // navigate for the workdir param).
    //
    // §unified-nav A7 (rewritten — was stale): Chat is EXCLUDED from this
    // handler. On the Chat destination system Back is handled by:
    //  - ChatScaffold's parent-session BackHandler (enabled when parentId !=
    //    null) → returnToParent (子→父 pop-restore via navigateToChat);
    //  - ChatScaffold's drawer-open BackHandler (enabled when drawer open) →
    //    close drawer.
    //  - When NEITHER is active (root session, drawer closed), NO custom
    //    BackHandler is enabled → the NavHost's NATIVE predictive-back handler
    //    fires → popBackStack to Sessions with the system "shrink + reveal"
    //    animation. The committed pop triggers the destination listener (A3)
    //    which reconciles the mirror to Sessions + calls closeDetail() exactly
    //    once (restoring the leave-detail transaction). There is NO
    //    "ChatScaffold's own root BackHandler" — it was REMOVED for predictive-
    //    back support (see ChatScaffold §predictive-back-fix). Excluding Chat
    //    from this shell handler keeps the in-screen LIFO priority so the
    //    parent/drawer handlers preempt the NavHost native handler.
    //
    // §files-back-fix (Blocker-2, PRESERVED): FilesScreen must own its
    // system-back entirely (preview open → close preview; preview closed →
    // onExit → backToHome → Sessions). Compose's BackHandler stack is LIFO
    // and this handler is composed AFTER the screen, so without this disable
    // it would steal the press and jump straight to Sessions even with a
    // preview open. The functional outcome is identical (Sessions), but the
    // intermediate preview-close / browseWorkdir-reset steps would be skipped
    // — so the exclusion stays.
    //
    // Net coverage after both exclusions: Git (no internal BackHandler) and
    // top-level Settings → backToHome(). Files → onExit → backToHome().
    // Chat → ChatScaffold's parent/drawer chain OR NavHost native predictive
    // back (root path → Sessions). Sessions (root) → system exits.
    BackHandler(
        enabled = !isNestedSettings &&
            currentRoute != NavRoute.Sessions &&
            currentRoute != NavRoute.Files &&
            currentRoute != NavRoute.Chat,
    ) {
        backToHome()
    }
}

/**
 * §unified-nav A5.2: resolve the ACTUAL destination literal from a committed
 * [NavDestination.route] pattern + the entry's arguments. Reconstructs the
 * literal `chat/$sid` from the `sessionId` arg so the materialize CAS's
 * [DraftRouteOrigin] comparison is exact (the bare pattern `"chat/{sessionId}"`
 * is not distinctive enough). Falls back to the route base (query stripped) for
 * non-parameterized destinations. Pure (no VM / state reads).
 */
private fun resolveActualDestination(routePattern: String?, arguments: android.os.Bundle?): String {
    if (routePattern == null) return "unknown"
    return when {
        routePattern == "chat/{sessionId}" -> {
            val sid = arguments?.getString("sessionId")
            if (sid != null) "chat/$sid" else "chat"
        }
        else -> routePattern.substringBefore('?')
    }
}
