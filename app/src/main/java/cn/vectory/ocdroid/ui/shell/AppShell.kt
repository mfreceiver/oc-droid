package cn.vectory.ocdroid.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
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
import cn.vectory.ocdroid.ui.crossSessionPendingCount
import cn.vectory.ocdroid.ui.files.FilesViewModel
import cn.vectory.ocdroid.ui.sessions.SessionsScreen
import cn.vectory.ocdroid.ui.settings.SettingsAboutRoute
import cn.vectory.ocdroid.ui.settings.SettingsAppearanceRoute
import cn.vectory.ocdroid.ui.settings.SettingsHostsRoute
import cn.vectory.ocdroid.ui.settings.SettingsModelsRoute
import cn.vectory.ocdroid.ui.settings.SettingsNotificationsRoute
import cn.vectory.ocdroid.ui.settings.SettingsScreen
import cn.vectory.ocdroid.ui.settings.SettingsStorageRoute
import cn.vectory.ocdroid.ui.workspace.WorkspaceScaffold
import cn.vectory.ocdroid.ui.workspace.WorkspaceTab

/**
 * Default and only app shell (plan §5 task 6 step c — the legacy PhoneLayout
 * shell + USE_NEW_SHELL flag were physically deleted after the four-judge gate
 * + emulator regression passed). The existing screen composables stay
 * unchanged; this file supplies their top-level navigation.
 */
@Composable
fun AppShell(orchestratorVM: OrchestratorViewModel) {
    // These calls happen before NavHost creates a NavBackStackEntry, therefore
    // they retain the Activity owner/scope used by PhoneLayout. Do not move them
    // into composable(route) until Phase 1B defines intentional screen scopes.
    val chatVM: ChatViewModel = hiltViewModel()
    val composerVM: ComposerViewModel = hiltViewModel()
    val connectionVM: ConnectionViewModel = hiltViewModel()
    val sessionVM: SessionViewModel = hiltViewModel()
    val hostVM: HostViewModel = hiltViewModel()
    val settingsVM: SettingsViewModel = hiltViewModel()
    val filesVM: FilesViewModel = hiltViewModel()

    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    // §phase2: capture the raw destination route for nested-workspace
    // classification. fromRouteKey now classifies `workspace/*` as Workspace
    // (for nav-bar selection); isNestedWorkspaceRoute distinguishes the
    // nested routes (workspace/files, workspace/changes) so AppShell's
    // BackHandler can let the NavHost pop instead of routing to Chat.
    // §phase3 (G.3): same pattern for `settings/*` nested sub-routes.
    val destRoute = entry?.destination?.route
    val currentRoute = NavRoute.fromRouteKey(destRoute)
    val isNestedWorkspace = NavRoute.isNestedWorkspaceRoute(destRoute)
    val isNestedSettings = NavRoute.isNestedSettingsRoute(destRoute)
    val navState by orchestratorVM.navFlow.collectAsStateWithLifecycle()
    val requestedRoute = NavRoute.fromRouteKey(navState.lastRoute)
    // §1C-FIX-⑦: subscribe to sessionListFlow so the Sessions nav
    // item can show a badge when OTHER sessions have pending
    // permission / question requests (plan §3.3 G.1 / scheme E.4 /
    // P5-7). The Chat destination's StatusSlot only shows this
    // session's pending items (P5-7), so the only way a user can
    // see that another session needs their attention is the badge
    // on the Sessions tab. The subscription is read-only; no writes
    // to the slice happen here. Phase 1A's AppShell only adds this
    // read + the badge parameter to the nav item; navigation /
    // route structure is untouched.
    val sessionListState by sessionVM.sessionListFlow.collectAsStateWithLifecycle()
    val chatState by chatVM.chatFlow.collectAsStateWithLifecycle()
    // §1C-FIX-⑦ / scheme D.1: the Sessions badge counts pending
    // permission / question requests from sessions OTHER than the
    // current session. The Chat StatusSlot renders the CURRENT
    // session's pending items (P5-7 session-scope filter), so
    // counting ALL pending here would double-count the current
    // session (its items would appear BOTH in the StatusSlot AND
    // inflate the badge). The filter is a pure function
    // ([crossSessionPendingCount]); null currentSessionId counts
    // everything (no current session → StatusSlot is empty → all
    // pending are genuinely cross-session).
    val crossSessionPendingCount =
        crossSessionPendingCount(sessionListState, chatState.currentSessionId)

    fun navigateTopLevel(route: NavRoute) {
        navController.navigate(route.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // First read migrates legacy 0/1/2 persistence to a stable key. This runs
    // after composition so the Activity-scoped VM remains construction-safe.
    LaunchedEffect(Unit) { orchestratorVM.restoreLastRoute() }
    // Covers external adapters too: notification deep-link sets Chat before it
    // selects the session, so a notification never leaves its user in Settings.
    LaunchedEffect(requestedRoute) {
        if (currentRoute != requestedRoute) navigateTopLevel(requestedRoute)
    }
    LaunchedEffect(currentRoute) {
        if (currentRoute != NavRoute.Chat) orchestratorVM.clearDraftIfActive()
    }
    NavigationSuiteScaffold(
            navigationSuiteItems = {
                NavRoute.topLevel.forEach { route ->
                    item(
                        selected = currentRoute == route,
                        onClick = { orchestratorVM.setLastRoute(route) },
                        icon = { NavIcon(route) },
                        label = { Text(routeLabel(route)) },
                        // §1C-FIX-⑦: badge on the Sessions nav item
                        // when ANOTHER session has a pending
                        // permission / question. The Chat
                        // destination's StatusSlot renders the
                        // CURRENT session's pending items (P5-7
                        // session-scope filter), so the badge
                        // counts only OTHER sessions (scheme D.1)
                        // to avoid double-counting the current
                        // session.
                        // Capped at 99 to keep the nav item
                        // readable. The 0-case is a no-op
                        // (NavigationSuiteScaffold hides the
                        // badge composable when its slot is Unit).
                        badge = {
                            if (route == NavRoute.Sessions && crossSessionPendingCount > 0) {
                                Badge {
                                    Text(crossSessionPendingCount.coerceAtMost(99).toString())
                                }
                            }
                        },
                    )
                }
            },
        ) {
            NavHost(
                navController = navController,
                startDestination = NavRoute.Chat.route,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(NavRoute.Chat.route) {
                    // §round-B ② (D.5): feed the active host's recent
                    // workdirs (recent_workdirs is the single source of
                    // truth for "connected", same gate as SessionsScreen's
                    // buildWorkdirGroups) into ChatScreen so the
                    // ContextSelectorSheet's workdir list is no longer
                    // derived from `sessionList.sessions.map { directory }`
                    // (which excluded connected-but-not-yet-opened workdirs).
                    val recentWorkdirs by settingsVM.recentWorkdirs.collectAsStateWithLifecycle()
                    ChatScreen(
                        chatVM = chatVM,
                        composerVM = composerVM,
                        connectionVM = connectionVM,
                        sessionVM = sessionVM,
                        hostVM = hostVM,
                        orchestratorVM = orchestratorVM,
                        recentWorkdirs = recentWorkdirs,
                        onManageHosts = {
                            // ServerManagement parity (Settings → Hosts).
                            orchestratorVM.setLastRoute(NavRoute.Settings)
                        },
                        onNavigateToSettings = { orchestratorVM.setLastRoute(NavRoute.Settings) },
                        onNavigateToSessions = { orchestratorVM.setLastRoute(NavRoute.Sessions) },
                        // §phase2: pass BOTH workdir + path to the typed route
                        // builder. ChatScaffold derives workdir from the current
                        // session and forwards the tapped file path verbatim.
                        onOpenWorkspaceFiles = { workdir, path ->
                            navController.navigate(NavRoute.workspaceFiles(workdir, path))
                        },
                        onOpenWorkspaceChanges = { sessionId ->
                            navController.navigate(NavRoute.workspaceChanges(sessionId))
                        },
                    )
                }
                composable(NavRoute.Sessions.route) {
                    SessionsScreen(
                        viewModel = sessionVM,
                        composerVM = composerVM,
                        orchestratorVM = orchestratorVM,
                        settingsVM = settingsVM,
                        repository = filesVM.repository,
                        onSwitchToChat = { orchestratorVM.setLastRoute(NavRoute.Chat) },
                        // §phase2: Sessions only carries a workdir (no specific
                        // file path) — opens the Workspace Files tab at the
                        // tapped workdir B (NOT the current session A directory).
                        onOpenWorkspaceFiles = { workdir, _ ->
                            navController.navigate(NavRoute.workspaceFiles(workdir = workdir, path = null))
                        },
                    )
                }
                composable(NavRoute.Workspace.route) { entry ->
                    WorkspaceScaffold(
                        filesVM = filesVM, sessionVM = sessionVM, hostVM = hostVM,
                        savedStateHandle = entry.savedStateHandle,
                        onOpenFiles = { navController.navigate(NavRoute.workspaceFiles()) },
                        onOpenChanges = { id -> navController.navigate(NavRoute.workspaceChanges(id ?: "")) },
                    )
                }
                composable(
                    route = NavRoute.workspaceFilesRoute,
                    arguments = listOf(
                        navArgument("workdir") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("path") { type = NavType.StringType; nullable = true; defaultValue = null },
                    )
                ) { entry ->
                    // §phase2: read the typed route args — workdir binds
                    // FilesScreen's sessionDirectory; path is the specific
                    // file to locate (FilesPane.pathToShow, was always-null
                    // before this fix).
                    val argWorkdir = entry.arguments?.getString("workdir")?.takeIf { it.isNotBlank() }
                    val argPath = entry.arguments?.getString("path")?.takeIf { it.isNotBlank() }
                    WorkspaceScaffold(
                        filesVM = filesVM, sessionVM = sessionVM, hostVM = hostVM,
                        savedStateHandle = entry.savedStateHandle,
                        initialWorkdir = argWorkdir,
                        initialPath = argPath,
                        initialTab = WorkspaceTab.Files,
                        onOpenFiles = {},
                        onOpenChanges = { id -> navController.navigate(NavRoute.workspaceChanges(id ?: "")) },
                    )
                }
                composable(
                    route = NavRoute.workspaceChangesRoute,
                    // §B1-fix③ (groker 🟠): declare the session query param as a
                    // navArgument so Navigation Compose parses it into the entry's
                    // arguments bundle. Without this declaration (present for
                    // workspace/files but missing here), entry.arguments?
                    // .getString("session") was always null — the `?: currentSessionId`
                    // fallback masked it for the CURRENT session but a deep link /
                    // restoration to a NON-current session silently mis-bound.
                    // Nullable + default null matches the workspaceChanges builder
                    // (session is a query param, so the bare `workspace/changes`
                    // pattern is also a valid match).
                    arguments = listOf(
                        navArgument("session") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) { entry ->
                    WorkspaceScaffold(
                        filesVM = filesVM, sessionVM = sessionVM, hostVM = hostVM,
                        savedStateHandle = entry.savedStateHandle,
                        initialSessionId = entry.arguments?.getString("session"), initialTab = WorkspaceTab.Changes,
                        onOpenFiles = { navController.navigate(NavRoute.workspaceFiles()) }, onOpenChanges = {},
                    )
                }
                composable(NavRoute.Settings.route) {
                    // §phase3 (G.3 / D.8): Settings is now a slim LazyColumn
                    // root. Each row pushes a sub-route via NavController.
                    SettingsScreen(
                        viewModel = hostVM,
                        composerVM = composerVM,
                        connectionVM = connectionVM,
                        settingsVM = settingsVM,
                        onBack = { orchestratorVM.setLastRoute(NavRoute.Chat) },
                        onNavigateSection = { route -> navController.navigate(route) },
                    )
                }
                // §phase3 (G.3 / D.8): Settings sub-routes. Each owns its own
                // Scaffold + TopAppBar + back arrow. BackHandler below lets
                // these POP the NavHost (back to settings) instead of routing
                // to Chat per the Phase 1A top-level contract.
                composable(NavRoute.settingsHostsRoute) {
                    SettingsHostsRoute(
                        viewModel = hostVM,
                        connectionVM = connectionVM,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(NavRoute.settingsAppearanceRoute) {
                    // §phase3 red line (plan §5 task 5): reuses the existing
                    // M3 SegmentedButton + Slider verbatim — no replacement.
                    SettingsAppearanceRoute(
                        settingsVM = settingsVM,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(NavRoute.settingsModelsRoute) {
                    SettingsModelsRoute(
                        composerVM = composerVM,
                        settingsVM = settingsVM,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(NavRoute.settingsNotificationsRoute) {
                    SettingsNotificationsRoute(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(NavRoute.settingsStorageRoute) {
                    SettingsStorageRoute(
                        viewModel = hostVM,
                        connectionVM = connectionVM,
                        settingsVM = settingsVM,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(NavRoute.settingsAboutRoute) {
                    SettingsAboutRoute(
                        viewModel = hostVM,
                        settingsVM = settingsVM,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

    // §phase2 nested-workspace back behaviour. The Phase 1A BackHandler
    // routed EVERY non-Chat destination back to Chat — which (after the
    // workspace family fix) would also fire from `workspace/files` and
    // `workspace/changes`, bouncing the user out of a nested view instead
    // of popping it. The fix: nested workspace routes (workspace/* except
    // the base) DISABLE this BackHandler so the NavHost's own back stack
    // pops (Changes/Files → Workspace base → Chat). The top-level non-Chat
    // destinations (Sessions / Workspace base / Settings) still honour the
    // Phase 1A contract (back → Chat). This mirrors the §10 route/back table
    // (workspace/files back → files; workspace/changes back → changes → base).
    //
    // §phase3 (G.3): same exception for nested `settings/*` sub-routes —
    // they POP to the Settings root rather than jumping to Chat. The
    // top-level Settings destination still routes back to Chat.
    BackHandler(
        enabled = !isNestedWorkspace && !isNestedSettings && currentRoute != NavRoute.Chat
    ) {
        orchestratorVM.setLastRoute(NavRoute.Chat)
    }
}

@Composable
private fun NavIcon(route: NavRoute) = when (route) {
    NavRoute.Chat -> androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.Chat, null)
    NavRoute.Sessions -> androidx.compose.material3.Icon(Icons.Default.History, null)
    NavRoute.Workspace -> androidx.compose.material3.Icon(Icons.Default.Folder, null)
    NavRoute.Settings -> androidx.compose.material3.Icon(Icons.Default.Settings, null)
}

private fun routeLabel(route: NavRoute): String = when (route) {
    NavRoute.Chat -> "Chat"
    NavRoute.Sessions -> "Sessions"
    NavRoute.Workspace -> "Workspace"
    NavRoute.Settings -> "Settings"
}
