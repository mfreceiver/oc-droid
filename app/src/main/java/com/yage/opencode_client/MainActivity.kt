package com.yage.opencode_client

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.yage.opencode_client.ui.MainViewModel
import com.yage.opencode_client.ui.chat.ChatScreen
import com.yage.opencode_client.ui.files.FilesScreen
import com.yage.opencode_client.ui.files.FilesViewModel
import com.yage.opencode_client.ui.sessions.SessionsScreen
import com.yage.opencode_client.ui.settings.SettingsScreen
import com.yage.opencode_client.ui.theme.OpenCodeTheme
import com.yage.opencode_client.ui.theme.compactTypography
import com.yage.opencode_client.util.AppLocaleController
import com.yage.opencode_client.util.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

sealed class Screen(
    val route: String,
    val titleRes: Int,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector
) {
    object Chat : Screen(
        "chat",
        R.string.nav_chat,
        Icons.AutoMirrored.Filled.Chat,
        Icons.Outlined.ChatBubbleOutline
    )

    object Sessions : Screen(
        "sessions",
        R.string.nav_sessions,
        Icons.Default.History,
        Icons.Outlined.History
    )

    object Files : Screen(
        "files",
        R.string.nav_files,
        Icons.Default.Folder,
        Icons.Outlined.Folder
    )

    object Settings : Screen(
        "settings",
        R.string.nav_settings,
        Icons.Default.Settings,
        Icons.Outlined.Settings
    )
}

val screens = listOf(Screen.Chat, Screen.Sessions, Screen.Files, Screen.Settings)

// Debug-only Intent extra keys for injecting connection credentials at launch,
// so automated UI tests can connect to a server without driving the Settings UI.
// Read only when BuildConfig.DEBUG is true (see onCreate).
private const val EXTRA_TEST_SERVER_URL = "test_server_url"
private const val EXTRA_TEST_USERNAME = "test_username"
private const val EXTRA_TEST_PASSWORD = "test_password"

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val lifecycleOwner = LocalLifecycleOwner.current
            LaunchedEffect(lifecycleOwner) {
                // Debug-only credential injection: if the launch Intent carries
                // test credentials (passed via `am start --es test_server_url ...`),
                // configure the server before testing the connection so automated
                // tests skip the Settings UI entirely. Gated hard on BuildConfig.DEBUG
                // so this path is dead code in release builds.
                if (BuildConfig.DEBUG) {
                    val testUrl = intent?.getStringExtra(EXTRA_TEST_SERVER_URL)
                    if (!testUrl.isNullOrEmpty()) {
                        viewModel.configureServer(
                            url = testUrl,
                            username = intent?.getStringExtra(EXTRA_TEST_USERNAME),
                            password = intent?.getStringExtra(EXTRA_TEST_PASSWORD)
                        )
                    }
                }
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.testConnection()
                }
            }
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.languageMode) {
                AppLocaleController.apply(state.languageMode)
            }
            val darkTheme = when (state.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val windowSizeClass = calculateWindowSizeClass(this)
            val isTablet = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

            OpenCodeTheme(
                darkTheme = darkTheme,
                markdownFontSizes = state.markdownFontSizes
            ) {
                if (isTablet) {
                    TabletLayout(viewModel = viewModel)
                } else {
                    PhoneLayout(viewModel = viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneLayout(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val context = LocalContext.current

    fun navigateToTopLevel(route: String) {
        if (currentRoute == route) return
        // Official Material 3 bottom-navigation pattern: popUpTo the start
        // destination with saveState = true (preserving each tab's state) and
        // restoreState = true on the new destination. This avoids the brittle
        // `popUpTo(start) { inclusive = true }` form which can throw when the
        // start destination has already been popped from the back stack. The
        // user-facing back behavior (pressing back exits to the home screen
        // instead of walking back to Chat) is handled by the BackHandler below.
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Pressing back from any top-level tab exits to the home screen (launcher).
    // BackHandler only fires when no descendant handler (dialogs, sheets, text
    // fields with IME) consumed the event first, so in-composition overlays
    // still dismiss normally.
    BackHandler(enabled = currentRoute != null) {
        (context as? Activity)?.finish()
    }

    // Hide the bottom NavigationBar while the IME (soft keyboard) is open.
    // Otherwise Scaffold's bottomBar padding and ChatInputBar.imePadding() both
    // apply, leaving a gap roughly the height of the NavigationBar between the
    // input field and the keyboard.
    val imeVisible = WindowInsets.isImeVisible

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!imeVisible) {
                NavigationBar {
                    screens.forEach { screen ->
                        val selected = currentRoute == screen.route
                        val title = stringResource(screen.titleRes)
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateToTopLevel(screen.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                    contentDescription = title,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Chat.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Chat.route) {
                ChatScreen(
                    viewModel = viewModel,
                    onNavigateToFiles = { path ->
                        viewModel.showFileInFiles(path, originRoute = Screen.Chat.route)
                        navigateToTopLevel(Screen.Files.route)
                    },
                    onNavigateToSettings = {
                        navigateToTopLevel(Screen.Settings.route)
                    },
                    showSettingsButton = false
                )
            }
            composable(Screen.Files.route) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val filesViewModel: FilesViewModel = hiltViewModel()
                FilesScreen(
                    viewModel = filesViewModel,
                    pathToShow = state.filePathToShowInFiles,
                    sessionDirectory = state.currentSession?.directory,
                    onCloseFile = {
                        val origin = state.filePreviewOriginRoute
                        viewModel.clearFileToShow()
                        if (origin == Screen.Chat.route) {
                            navigateToTopLevel(Screen.Chat.route)
                        }
                    },
                    onFileClick = { }
                )
            }
            composable(Screen.Sessions.route) {
                SessionsScreen(
                    viewModel = viewModel,
                    navController = navController
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabletLayout(viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var sessionsPaneCollapsed by rememberSaveable { mutableStateOf(false) }
    val onOpenSettings: () -> Unit = { selectedTab = 1 }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filesWeight = if (sessionsPaneCollapsed) 0.5f else 0.375f
    val chatWeight = if (sessionsPaneCollapsed) 0.5f else 0.375f

        Row(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
        // Left panel: Session list or Settings — 25% when expanded.
        if (!sessionsPaneCollapsed) {
            Column(
                modifier = Modifier
                    .weight(0.25f)
                    .fillMaxHeight()
            ) {
                if (selectedTab == 1) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onBack = { selectedTab = 0 }
                    )
                } else {
                    SessionsScreen(
                        viewModel = viewModel,
                        navController = null // no phone-nav in TabletLayout
                    )
                }
            }

            VerticalDivider()
        }

        // Middle panel: FilesScreen (file preview) — 37.5%, or 50% when Sessions is collapsed.
        Column(
            modifier = Modifier
                .weight(filesWeight)
                .fillMaxHeight()
        ) {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme,
                typography = compactTypography(MaterialTheme.typography)
            ) {
                val filesViewModel: FilesViewModel = hiltViewModel()
                Box(modifier = Modifier.fillMaxSize()) {
                    FilesScreen(
                        viewModel = filesViewModel,
                        pathToShow = state.filePathToShowInFiles,
                        sessionDirectory = state.currentSession?.directory,
                        onCloseFile = { viewModel.clearFileToShow() },
                        onFileClick = { }
                    )
                    if (sessionsPaneCollapsed) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            tonalElevation = 3.dp
                        ) {
                            IconButton(onClick = { sessionsPaneCollapsed = false }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = stringResource(R.string.sessions_show)
                                )
                            }
                        }
                    }
                }
            }
        }

        VerticalDivider()

        // Right panel: Chat — 37.5%, or 50% when Sessions is collapsed.
        Column(
            modifier = Modifier
                .weight(chatWeight)
                .fillMaxHeight()
        ) {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme,
                typography = compactTypography(MaterialTheme.typography)
            ) {
                ChatScreen(
                    viewModel = viewModel,
                    onNavigateToFiles = { path ->
                        viewModel.showFileInFiles(path)
                    },
                    onNavigateToSettings = onOpenSettings,
                    showSettingsButton = false
                )
            }
        }
    }
}
