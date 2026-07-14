package cn.vectory.ocdroid.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
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
fun AppShell(orchestratorVM: OrchestratorViewModel, navBarBottomDp: Dp = 0.dp) {
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
    val unreadState by sessionVM.unreadFlow.collectAsStateWithLifecycle()
    val crossSessionPendingCount =
        crossSessionPendingCount(sessionListState, chatState.currentSessionId)
    // §sessux-badge: the Sessions nav dot signals "attention needed elsewhere".
    // It lights when ANY session other than the current one has a pending
    // permission/question OR unread out-of-band activity. Pending and unread in
    // the CURRENT session are already surfaced in-chat (StatusSlot / message
    // list), so they must not re-light the nav dot — that double-signal was the
    // source of user confusion ("I cleared every unread dot, why is the badge
    // still on?"). The dot is binary (no count); count is visible on the cards.
    val sessionsBadgeVisible =
        crossSessionPendingCount > 0 ||
            unreadState.unreadSessions.any { it != chatState.currentSessionId }

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

    // §bug-6.4: navBarBottomDp is captured from the Android View inset system
    // in MainActivity and threaded in as a plain Dp. Compose's
    // WindowInsets.navigationBars resolves to 0 under this AppCompat theme
    // (the DecorView consumes the inset before Compose sees it), so the value
    // is sourced from ViewCompat/WindowInsetsCompat where it is reliable.

    // §kbd-ime: drive the bottom bar's position/alpha from the LIVE ime inset
    // height instead of the binary `WindowInsets.isImeVisible` flag. The flag
    // flips in a single composition-frame, so the old `if (!isImeVisible)`
    // guard made the bar POP in one frame late on dismiss — a two-phase jank
    // (composer slides smoothly via imePadding, then the bar snaps). The ime
    // bottom inset is read here in composition (WindowInsets.ime's getter is
    // @Composable, so it cannot be read inside the layout modifier);
    // recomposition fires per-frame during the IME spring, so the bar slides
    // + fades in lockstep with the composer. barHeightPx is the bar's full
    // painted height (tab Row + navBarBottomDp gesture-pill spacer), used to
    // clamp the slide so the bar travels exactly its own height and is fully
    // faded by the time the IME is open. The custom layout also shrinks the
    // height reported to Scaffold, so innerPadding.bottom reaches zero and the
    // zero-height clipped bar cannot intercept composer touches.
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density).toFloat()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            CompactBottomBar(
                currentRoute = currentRoute,
                sessionsBadgeVisible = sessionsBadgeVisible,
                navBarBottomDp = navBarBottomDp,
                onSelect = { route ->
                    if (route == currentRoute) {
                        navController.popBackStack(NavRoute.homeRoute(route), inclusive = false)
                        orchestratorVM.emitReselect(route)
                    } else {
                        orchestratorVM.setLastRoute(route)
                    }
                },
                modifier = Modifier
                    .clipToBounds()
                    .collapseForIme(imeBottomPx),
            )
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
                // §files-git-readonly-workdir: recentWorkdirs / filesLastWorkdir
                // are no longer consumed by FilesScreen (the WorkdirControl is
                // read-only). Kept flowing for signature stability; switching
                // workdir happens by opening a session in the target dir.
                //
                // Nav redesign: FilesScreen now hosts the project-list first
                // screen (ported from SessionsScreen "Connected projects"),
                // so the session / settings / composer VMs + onSwitchToChat
                // are threaded in for the per-row actions.
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
                    // §files-back-fix (Blocker-2): hand the back-to-Chat
                    // affordance to FilesScreen so its three mutually-exclusive
                    // BackHandlers can fully own system back. AppShell's
                    // top-level BackHandler is disabled on the Files route
                    // (see the BackHandler at the bottom of this composable),
                    // so this onExit is the single exit path when both the
                    // preview is closed AND browseWorkdir == null.
                    onExit = { orchestratorVM.setLastRoute(NavRoute.Chat) },
                )
            }
            composable(
                route = NavRoute.gitRoutePattern,
                arguments = listOf(
                    navArgument("session") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { routeEntry ->
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
                    initialSessionId = routeEntry.arguments?.getString("session"),
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
    // §files-back-fix (Blocker-2): let FilesScreen own its system-back entirely
    // (preview open → close preview; preview closed → onExit → Chat). Compose's
    // BackHandler stack is LIFO and this handler is composed AFTER the screen,
    // so without this disable it would steal the press and jump straight to
    // Chat even with a preview open. Sessions / Git / Settings still fall
    // through to the Chat-return behaviour.
    BackHandler(enabled = !isNestedSettings && currentRoute != NavRoute.Chat && currentRoute != NavRoute.Files) {
        orchestratorVM.setLastRoute(NavRoute.Chat)
    }
}

@Composable
private fun CompactBottomBar(
    currentRoute: NavRoute,
    sessionsBadgeVisible: Boolean,
    navBarBottomDp: androidx.compose.ui.unit.Dp,
    onSelect: (NavRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    // §bug-6.4: consumption-immune background fill. The navigation-bar bottom
    // inset is captured as a concrete Dp at the AppShell top level (outside
    // the Scaffold and its contentWindowInsets=0 consumption link) and passed
    // in as navBarBottomDp. The Surface paints its tonalElevation color across
    // BOTH the fixed-height tab Row AND the explicit navBarBottomDp Spacer
    // below it, so the bar's color extends all the way to the screen bottom —
    // covering the gesture-pill area with no dark gap. The previous attempts
    // used windowInsetsBottomHeight / windowInsetsPadding INSIDE this
    // CompactBottomBar, but both resolved to 0 because the outer Scaffold's
    // contentWindowInsets=0 had already consumed the inset upstream.
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.touchTargetMin + Dimens.spacing2),
            ) {
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
                                            MaterialTheme.shapes.small,
                                        ),
                                )
                            }
                            // §sessux #4: wrap NavIcon in its own Box so the
                            // pending-count Badge anchors to the ICON's bounds
                            // (icon-sized, fixed) rather than the outer Box
                            // (which grows to the 40×32 selected-pill when
                            // active but shrinks to just the icon when not).
                            // Anchoring on the icon keeps the badge at the
                            // same screen position in both states — previously
                            // it shifted inward when the pill rendered.
                            Box {
                                NavIcon(route, selected)
                                if (route == NavRoute.Sessions && sessionsBadgeVisible) {
                                    // §sessux-badge: small attention dot anchored to
                                    // the icon's top-end corner, nudged past it so it
                                    // reads as a separate marker (not a number — just
                                    // "something needs you elsewhere"). The surface-
                                    // colored ring separates it from the icon glyph.
                                    Box(
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = 3.dp, y = (-3).dp)
                                            .size(8.dp)
                                            .background(
                                                MaterialTheme.colorScheme.error,
                                                CircleShape,
                                            )
                                            .border(
                                                width = 1.5.dp,
                                                color = MaterialTheme.colorScheme.surface,
                                                shape = CircleShape,
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // §bug-6.4: reserve EXACTLY the captured gesture-pill inset height
            // so the Surface background fills it; the tab Row content stays
            // above. Using a plain fixed Dp height (captured upstream) instead
            // of the insets API makes this immune to upstream inset consumption.
            if (navBarBottomDp > 0.dp) {
                Spacer(Modifier.fillMaxWidth().height(navBarBottomDp))
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
