package com.yage.opencode_client

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.launch

/**
 * Top-level screens shown in the phone HorizontalPager.
 *
 * `route` is retained as a stable identity string (also used to tag the origin
 * of a file-preview request via MainViewModel.showFileInFiles(originRoute=...)).
 * `titleRes` provides the localized screen name shown in each page's own
 * TopAppBar (each page renders its own title bar — there is no shared app bar).
 */
sealed class Screen(
    val route: String,
    val titleRes: Int
) {
    object Chat : Screen("chat", R.string.nav_chat)
    object Sessions : Screen("sessions", R.string.nav_sessions)
    object Settings : Screen("settings", R.string.nav_settings)
}

// Page order for the phone HorizontalPager: Chat → Sessions → Settings.
// File browsing is reached from the Sessions screen (per-project folder button)
// and via in-chat file-path taps — no longer a swipeable pager page.
val screens = listOf(Screen.Chat, Screen.Sessions, Screen.Settings)

// Debug-only Intent extra keys for injecting connection credentials at launch,
// so automated UI tests can connect to a server without driving the Settings UI.
// Read only when BuildConfig.DEBUG is true (see onCreate).
private const val EXTRA_TEST_SERVER_URL = "test_server_url"
private const val EXTRA_TEST_USERNAME = "test_username"
private const val EXTRA_TEST_PASSWORD = "test_password"

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    /**
     * Reference to the Activity-scoped [MainViewModel], populated inside
     * [onCreate]'s `setContent` block once Hilt constructs it. Held so that
     * [onNewIntent] (which fires on warm-start deep links from §18
     * notifications, with `launchMode="singleTop"`) can dispatch the session
     * extra without re-entering `setContent`.
     */
    private var mainViewModel: MainViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            mainViewModel = viewModel
            val lifecycleOwner = LocalLifecycleOwner.current
            // §18.1 cold-start deep link: if the launch Intent carries
            // EXTRA_SESSION_ID (notification tap), route to the session once
            // the VM is initialised. The extra is consumed (removed) so screen
            // rotations do not re-trigger the navigation.
            LaunchedEffect(Unit) {
                handleSessionExtra(intent)
            }
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
                // §评审 Stage C #8: one-shot initial health check. The
                // ON_START-driven catch-up is now owned exclusively by
                // [AppLifecycleMonitor] (which routes through
                // [MainViewModel.onForegroundChanged]). coldStartReconnect()
                // satisfies the cold-start path with a small retry loop so a
                // slow-to-wake server still comes up instead of stranding the
                // user on the disconnected empty state. The call's own
                // 30s throttle makes any overlap with the foreground hook a
                // no-op. Previously [repeatOnLifecycle(STARTED)] re-fired this
                // on every ON_START, doubling the AppLifecycleMonitor path.
                viewModel.coldStartReconnect()
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
            // Landscape split: trigger on phone landscape orientation (≥400dp wide
            // so a ~100dp session pane is still usable). Below 400dp fall back to
            // the swipable PhoneLayout. Rotation state survives via the Hilt VM.
            val config = androidx.compose.ui.platform.LocalConfiguration.current
            val isLandscape =
                config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val tooNarrow = config.screenWidthDp < 400

            OpenCodeTheme(
                darkTheme = darkTheme,
                markdownFontSizes = state.markdownFontSizes
            ) {
                when {
                    isTablet -> TabletLayout(viewModel = viewModel)
                    isLandscape && !tooNarrow -> LandscapeSplitLayout(viewModel = viewModel)
                    else -> PhoneLayout(viewModel = viewModel, initialPage = state.lastNavPage)
                }
            }
        }
    }

    /**
     * §18.1 / N3: warm-start deep link. With `launchMode="singleTop"`, a
     * notification tap while the Activity is already alive routes here instead
     * of [onCreate]. We update the cached Intent (so subsequent reads see the
     * new extras) and dispatch the session id to the VM. Idempotent — a null
     * extra short-circuits.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSessionExtra(intent)
    }

    private fun handleSessionExtra(intent: Intent?) {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: return
        // Consume the extra so configuration changes (rotation) do not
        // re-trigger the deep-link navigation.
        intent.removeExtra(EXTRA_SESSION_ID)
        mainViewModel?.openSessionFromDeepLink(sessionId)
    }

    companion object {
        /**
         * Intent extra carrying a session ID to deep-link into when the
         * Activity is launched from a §18 notification tap. Defined here in
         * the write-domain of Module A so [com.yage.opencode_client.di.AppLifecycleMonitor]
         * (which builds the tap PendingIntent) and any deep-link entry point
         * share a single source of truth.
         */
        const val EXTRA_SESSION_ID = "opencode_session_id"
    }
}

/**
 * Phone layout: a full-screen HorizontalPager that swipes left/right between
 * the four top-level screens (Chat → Sessions → Files → Settings).
 *
 * The previous bottom NavigationBar + NavHost stack has been replaced by the
 * pager. Each page renders its own TopAppBar showing the current screen name,
 * so no shared app bar or title dropdown is needed. Cross-page navigation
 * (e.g. tapping a file in Chat to open Files) is wired through callbacks that
 * animate the pager to the target page.
 */
@Composable
private fun PhoneLayout(viewModel: MainViewModel, initialPage: Int = 0) {
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, screens.lastIndex),
        pageCount = { screens.size }
    )
    val scope = rememberCoroutineScope()

    // Persist the user's last-opened top-level page so the next cold start
    // lands them back on it (SettingsManager.lastNavPage). setLastNavPage has
    // a same-value no-op guard so this only writes on actual transitions.
    LaunchedEffect(pagerState.currentPage) {
        viewModel.setLastNavPage(pagerState.currentPage)
    }

    fun switchToPage(page: Int) {
        scope.launch { pagerState.animateScrollToPage(page) }
    }

    // #12: System back returns to Chat from any non-Chat page. On the Chat page
    // itself the handler is disabled, so back exits the app (expected). Landscape
    // and tablet layouts have no pager, so back exits the app directly there.
    BackHandler(enabled = pagerState.currentPage != screens.indexOf(Screen.Chat)) {
        switchToPage(screens.indexOf(Screen.Chat))
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) { page ->
            when (screens[page]) {
                Screen.Chat -> ChatScreen(
                    viewModel = viewModel,
                    onNavigateToSettings = {
                        switchToPage(screens.indexOf(Screen.Settings))
                    },
                    showSettingsButton = false
                )
                Screen.Sessions -> SessionsScreen(
                    viewModel = viewModel,
                    onSwitchToChat = { switchToPage(screens.indexOf(Screen.Chat)) }
                )
                Screen.Settings -> SettingsScreen(viewModel = viewModel)
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
                // Status-bar inset is consumed by each pane's own M3 TopAppBar
                // (see RFC 0.1.3 §3 — single inset application rule).
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
                        // No swipe on tablet (Chat is always visible in the right
                        // pane); session selection updates the shared ViewModel
                        // which the Chat pane observes.
                        onSwitchToChat = {}
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
                    onNavigateToSettings = onOpenSettings,
                    showSettingsButton = false
                )
            }
        }
    }
}

/**
 * Landscape split layout (#1, RFC 0.1.3 §1): a two-pane Row used on phones in
 * landscape orientation (width ≥400dp). Sessions on the left (25%), Chat on the
 * right (75%). Settings has no entry in landscape (no pager); the settings
 * button is hidden. Selecting a session drives the shared Hilt ViewModel, which
 * the Chat pane observes — `onSwitchToChat` is intentionally a no-op.
 *
 * The Row intentionally applies NO status-bar inset padding; per §3 the inset
 * is consumed by each pane's own M3 TopAppBar. Rotation state survives because
 * the ViewModel is Hilt-scoped to the Activity.
 *
 * Files navigation is routed to `showFileInFiles` which caches the path so it
 * can be displayed when the user rotates back to portrait (no Files pane in
 * landscape to show it immediately).
 */
@Composable
private fun LandscapeSplitLayout(viewModel: MainViewModel) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Left pane: Sessions list — 25%.
        Column(modifier = Modifier.weight(0.25f).fillMaxHeight()) {
            SessionsScreen(
                viewModel = viewModel,
                onSwitchToChat = {} // Selection updates the shared VM, which the
                                   // right-hand Chat pane observes automatically.
            )
        }

        VerticalDivider()

        // Right pane: Chat — 75%.
        // §评审 Stage C #5: wrap in compactTypography so landscape matches
        // [TabletLayout]'s compact density for the chat pane.
        Column(modifier = Modifier.weight(0.75f).fillMaxHeight()) {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme,
                typography = compactTypography(MaterialTheme.typography)
            ) {
                ChatScreen(
                    viewModel = viewModel,
                    onNavigateToSettings = {},
                    showSettingsButton = false
                )
            }
        }
    }
}
