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
    val requestedRoute = NavRoute.fromRouteKey(navState.lastRoute)
    val sessionListState by sessionVM.sessionListFlow.collectAsStateWithLifecycle()
    val chatState by chatVM.chatFlow.collectAsStateWithLifecycle()

    fun navigateTopLevel(route: NavRoute) {
        navController.navigate(route.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // §CRITICAL-1 (hub-back-trap fix): centralized "return to home hub"
    // operation. Files / Git are entered via direct `navController.navigate(
    // filesRoute(workdir, …))` (necessary because the workdir param can't be
    // carried by `setLastRoute` — it only takes a NavRoute identity). So on
    // entry the navState.lastRoute STAYS Sessions; on exit, calling
    // setLastRoute(Sessions) would SHORT-CIRCUIT (state already Sessions) →
    // no navFlow emission → the `LaunchedEffect(requestedRoute)` synchronizer
    // never fires → user trapped on Files/Git. The fix decouples the return
    // NAVIGATION (popBackStack — always works regardless of navState) from
    // the navState UPDATE (setLastRoute — for consistency so the
    // synchronizer doesn't fight afterward).
    //
    // Why the synchronizer does NOT fight after this call:
    //   - Files/Git case: navState was already Sessions (entry didn't touch
    //     it) → setLastRoute is a no-op → requestedRoute stays Sessions.
    //     popBackStack moves currentRoute to Sessions → currentRoute ==
    //     requestedRoute → no hop. ✓
    //   - Chat/Settings case: navState was Chat/Settings (entry updated it)
    //     → setLastRoute(Sessions) EMITS → requestedRoute becomes Sessions.
    //     popBackStack already moved currentRoute to Sessions →
    //     LaunchedEffect(requestedRoute) fires but `currentRoute ==
    //     requestedRoute == Sessions` → no hop. ✓
    // popBackStack(route, inclusive=false) pops everything above the Sessions
    // destination, leaving it at the top. Sessions is startDestination so it
    // is always on the back stack; the Boolean return covers the (impossible
    // in practice) case where it isn't, falling back to navigateTopLevel.
    fun backToHome() {
        orchestratorVM.setLastRoute(NavRoute.Sessions)
        if (!navController.popBackStack(NavRoute.Sessions.route, inclusive = false)) {
            navigateTopLevel(NavRoute.Sessions)
        }
    }

    // §home-hub T7-C5 (cold-start-stays-home): no cold-start route-restore —
    // the old `LaunchedEffect(Unit) { restoreLastRoute() }` was deleted (and
    // `restoreLastRoute()` itself removed as dead code). The initial
    // NavState.lastRoute = Sessions.route (see NavState.kt) matches
    // startDestination, so requestedRoute == currentRoute == Sessions on cold
    // start → the hop below is a no-op. Deeplink / notification still call
    // setLastRoute(Chat) via MainActivity, which mutates navFlow →
    // requestedRoute changes → hop fires.
    LaunchedEffect(requestedRoute) {
        if (currentRoute != requestedRoute) navigateTopLevel(requestedRoute)
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
                // §home-hub T7-C1: bottom bar removed; finish IME handling
                // with a plain imePadding on the content (the composer inside
                // ChatScaffold already imePads its input row; this top-level
                // padding is the safety net for any other IME-consuming surface).
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
                    onOpenFiles = { workdir, _ ->
                        navController.navigate(NavRoute.filesRoute(workdir, null))
                    },
                    onOpenGit = { workdir ->
                        navController.navigate(NavRoute.gitRoute(workdir = workdir))
                    },
                    onNavigateToSettings = { orchestratorVM.setLastRoute(NavRoute.Settings) },
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
