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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 * `setLastRoute(...)` (deeplink / notification / in-session nav) still fires
 * the hop.
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

    fun navigateTopLevel(route: NavRoute) {
        navController.navigate(route.route) {
            popUpTo(navController.graph.findStartDestination().id)
            launchSingleTop = true
        }
    }

    // §CRITICAL-1 (hub-back-trap fix): centralized "return to home hub"
    // operation. Files / Git are entered via direct `navController.navigate(
    // filesRoute(workdir, …))` (necessary because the workdir param can't be
    // carried by `setLastRoute` — it only takes a NavRoute identity). So on
    // entry the navState.lastRoute STAYS Sessions; on exit, calling
    // setLastRoute(Sessions) would SHORT-CIRCUIT (state already Sessions) →
    // no navFlow emission → the nav synchronizer never fires → user trapped
    // on Files/Git. The fix decouples the return NAVIGATION (popBackStack —
    // always works regardless of navState) from the navState UPDATE
    // (setLastRoute — for consistency so the synchronizer doesn't fight
    // afterward).
    //
    // §12 B1: the synchronizer is now `LaunchedEffect(navState.lastRoute)`
    // (the unified synchronizer, NOT the old `LaunchedEffect(requestedRoute)`
    // mirror). The guard skips navigation when NavController is already at
    // the target — after popBackStack moves to Sessions, the synchronizer
    // fires on the lastRoute change but the guard finds NavController
    // already at Sessions → no-op. ✓
    // popBackStack(route, inclusive=false) pops everything above the Sessions
    // destination, leaving it at the top. Sessions is startDestination so it
    // is always on the back stack; the Boolean return covers the (impossible
    // in practice) case where it isn't, falling back to navigateTopLevel.
    fun backToHome() {
        // §B2 rev-gpt #2: when leaving the parameterized chat/{sessionId}
        // detail route, advance the §7.2 route-instance token + clear the
        // route-owned LoadedContent so a stale route-aware completion (delta /
        // load / load-more carrying the prior token) is rejected by the
        // existing freshness CAS. Gated on the parameterized detail route so
        // the legacy bare-chat path (NavRoute.Chat) keeps its flat messages
        // across a leave-and-return — reduceCloseDetail clears the flat
        // payload too, which would otherwise regress the legacy surface.
        if (destRoute == "chat/{sessionId}") {
            orchestratorVM.closeDetail()
        }
        orchestratorVM.setLastRoute(NavRoute.Sessions)
        if (!navController.popBackStack(NavRoute.Sessions.route, inclusive = false)) {
            navigateTopLevel(NavRoute.Sessions)
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
                ChatScreen(
                    chatVM = chatVM,
                    composerVM = composerVM,
                    connectionVM = connectionVM,
                    sessionVM = sessionVM,
                    hostVM = hostVM,
                    orchestratorVM = orchestratorVM,
                    settingsVM = settingsVM,
                    onNavigateToSettings = { orchestratorVM.setLastRoute(NavRoute.Settings) },
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
                        onNavigateToSettings = { orchestratorVM.setLastRoute(NavRoute.Settings) },
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
                    onSwitchToChat = { orchestratorVM.setLastRoute(NavRoute.Chat) },
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
                    onNavigateToSettings = { orchestratorVM.setLastRoute(NavRoute.Settings) },
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
                    onSwitchToChat = { orchestratorVM.setLastRoute(NavRoute.Chat) },
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
                    // §CRITICAL-1: Settings is entered via setLastRoute(
                    // Settings) from the server popup, so setLastRoute(
                    // Sessions) WOULD emit — but use backToHome() for a
                    // single canonical path that also works for the
                    // (defensive) case where the entry path changes.
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
    // §CRITICAL-1: uses backToHome() (explicit popBackStack + setLastRoute)
    // so Git back works even though Git entry doesn't update navState (direct
    // navigate for the workdir param).
    //
    // §IMPORTANT-1 (Chat back precedence): Chat is EXCLUDED from this handler
    // so ChatScaffold (T4) owns system Back on the Chat destination in its
    // own LIFO priority — drawer-open → close drawer; parent-session →
    // returnToParent; root-session → onBackToHome → backToHome(). This shell
    // handler is composed AFTER the NavHost (so it would otherwise OUTRANK
    // ChatScaffold's BackHandlers in the LIFO dispatch and steal every press
    // → home, regressing T4's drawer/parent precedence). Excluding Chat
    // restores the in-screen priority AND keeps root Chat back → home working
    // via ChatScaffold's own root BackHandler → onBackToHome → backToHome().
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
    // Chat → ChatScaffold's own chain (root path → backToHome()). Sessions
    // (root) → system exits.
    BackHandler(
        enabled = !isNestedSettings &&
            currentRoute != NavRoute.Sessions &&
            currentRoute != NavRoute.Files &&
            currentRoute != NavRoute.Chat,
    ) {
        backToHome()
    }
}
