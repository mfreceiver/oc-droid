package cn.vectory.ocdroid.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cn.vectory.ocdroid.R
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
import cn.vectory.ocdroid.ui.crossSessionPendingCount
import cn.vectory.ocdroid.ui.files.FilesScreen
import cn.vectory.ocdroid.ui.files.FilesViewModel
import cn.vectory.ocdroid.ui.sessions.SessionsScreen
import cn.vectory.ocdroid.ui.settings.SettingsAboutRoute
import cn.vectory.ocdroid.ui.settings.SettingsAppearanceRoute
import cn.vectory.ocdroid.ui.settings.SettingsHostsRoute
import cn.vectory.ocdroid.ui.settings.SettingsModelsRoute
import cn.vectory.ocdroid.ui.settings.SettingsNotificationsRoute
import cn.vectory.ocdroid.ui.settings.SettingsScreen
import cn.vectory.ocdroid.ui.settings.SettingsStorageRoute
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.ui.workspace.GitScreen

/** Sole application shell and owner of top-level navigation chrome. */
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
    val crossSessionPendingCount =
        crossSessionPendingCount(sessionListState, chatState.currentSessionId)

    fun navigateTopLevel(route: NavRoute) {
        navController.navigate(route.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(Unit) { orchestratorVM.restoreLastRoute() }
    LaunchedEffect(requestedRoute) {
        if (currentRoute != requestedRoute) navigateTopLevel(requestedRoute)
    }
    LaunchedEffect(currentRoute) {
        if (currentRoute != NavRoute.Chat) orchestratorVM.clearDraftIfActive()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!WindowInsets.isImeVisible) {
                CompactBottomBar(
                    currentRoute = currentRoute,
                    crossSessionPendingCount = crossSessionPendingCount,
                    onSelect = { route ->
                        if (route == currentRoute) {
                            navController.popBackStack(NavRoute.homeRoute(route), inclusive = false)
                            orchestratorVM.emitReselect(route)
                        } else {
                            orchestratorVM.setLastRoute(route)
                        }
                    },
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoute.Chat.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
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
                    onNavigateToSessions = { orchestratorVM.setLastRoute(NavRoute.Sessions) },
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
                SessionsScreen(
                    viewModel = sessionVM,
                    composerVM = composerVM,
                    orchestratorVM = orchestratorVM,
                    settingsVM = settingsVM,
                    repository = filesVM.repository,
                    onSwitchToChat = { orchestratorVM.setLastRoute(NavRoute.Chat) },
                    onOpenFiles = { workdir, _ ->
                        navController.navigate(NavRoute.filesRoute(workdir, null))
                    },
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
                val chatSessionWorkdir = sessionListState.sessions
                    .firstOrNull { it.id == chatState.currentSessionId }
                    ?.directory
                // §Q2: default-fallback chain — explicit route workdir ?:
                // Chat session workdir ?: persisted filesLastWorkdir.
                val filesDefaultWorkdir = explicitWorkdir
                    ?: chatSessionWorkdir
                    ?: settingsVM.filesLastWorkdir
                val recentWorkdirs by settingsVM.recentWorkdirs.collectAsStateWithLifecycle()
                FilesScreen(
                    viewModel = filesVM,
                    orchestratorVM = orchestratorVM,
                    pathToShow = routeEntry.arguments?.getString("path")?.takeIf { it.isNotBlank() },
                    sessionDirectory = filesDefaultWorkdir,
                    recentWorkdirs = recentWorkdirs,
                    onWorkdirSelected = { settingsVM.setFilesLastWorkdir(it) },
                )
            }
            composable(
                route = NavRoute.gitRoutePattern,
                arguments = listOf(
                    navArgument("session") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { routeEntry ->
                val gitRecentWorkdirs by settingsVM.recentWorkdirs.collectAsStateWithLifecycle()
                GitScreen(
                    filesVM = filesVM,
                    sessionVM = sessionVM,
                    hostVM = hostVM,
                    orchestratorVM = orchestratorVM,
                    savedStateHandle = routeEntry.savedStateHandle,
                    initialSessionId = routeEntry.arguments?.getString("session"),
                    recentWorkdirs = gitRecentWorkdirs,
                    defaultWorkdir = settingsVM.filesLastWorkdir,
                    onWorkdirSelected = { settingsVM.setFilesLastWorkdir(it) },
                )
            }
            composable(NavRoute.Settings.route) {
                SettingsScreen(
                    viewModel = hostVM,
                    composerVM = composerVM,
                    connectionVM = connectionVM,
                    settingsVM = settingsVM,
                    onBack = { orchestratorVM.setLastRoute(NavRoute.Chat) },
                    onNavigateSection = navController::navigate,
                )
            }
            composable(NavRoute.settingsHostsRoute) {
                SettingsHostsRoute(hostVM, connectionVM) { navController.popBackStack() }
            }
            composable(NavRoute.settingsAppearanceRoute) {
                SettingsAppearanceRoute(settingsVM) { navController.popBackStack() }
            }
            composable(NavRoute.settingsModelsRoute) {
                SettingsModelsRoute(composerVM, settingsVM) { navController.popBackStack() }
            }
            composable(NavRoute.settingsNotificationsRoute) {
                SettingsNotificationsRoute { navController.popBackStack() }
            }
            composable(NavRoute.settingsStorageRoute) {
                SettingsStorageRoute(hostVM, connectionVM, settingsVM) { navController.popBackStack() }
            }
            composable(NavRoute.settingsAboutRoute) {
                SettingsAboutRoute(hostVM, settingsVM) { navController.popBackStack() }
            }
        }
    }

    // Nested Settings routes pop naturally; top-level non-Chat routes return to Chat.
    BackHandler(enabled = !isNestedSettings && currentRoute != NavRoute.Chat) {
        orchestratorVM.setLastRoute(NavRoute.Chat)
    }
}

@Composable
private fun CompactBottomBar(
    currentRoute: NavRoute,
    crossSessionPendingCount: Int,
    onSelect: (NavRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, tonalElevation = 3.dp) {
        Row {
            NavRoute.topLevel.forEach { route ->
                val selected = route == currentRoute
                val label = routeLabel(route)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(Dimens.touchTargetMin + Dimens.spacing2)
                        .semantics { contentDescription = label }
                        .clip(RoundedCornerShape(Dimens.spacing2))
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onSelect(route) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (selected) {
                            Box(
                                Modifier
                                    .size(width = 40.dp, height = 32.dp)
                                    .background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        RoundedCornerShape(16.dp),
                                    ),
                            )
                        }
                        NavIcon(route, selected)
                        if (route == NavRoute.Sessions && crossSessionPendingCount > 0) {
                            Badge(modifier = Modifier.align(Alignment.TopEnd)) {
                                Text(crossSessionPendingCount.coerceAtMost(99).toString())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavIcon(route: NavRoute, selected: Boolean) {
    val tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val icon = when (route) {
        NavRoute.Chat -> Icons.AutoMirrored.Filled.Chat
        NavRoute.Sessions -> Icons.Default.History
        NavRoute.Files -> Icons.Default.Folder
        NavRoute.Git -> Icons.Default.AccountTree
        NavRoute.Settings -> Icons.Default.Settings
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Dimens.iconStd))
}

@Composable
private fun routeLabel(route: NavRoute): String = stringResource(
    when (route) {
        NavRoute.Chat -> R.string.nav_chat
        NavRoute.Sessions -> R.string.nav_sessions
        NavRoute.Files -> R.string.nav_files
        NavRoute.Git -> R.string.nav_git
        NavRoute.Settings -> R.string.nav_settings
    },
)
