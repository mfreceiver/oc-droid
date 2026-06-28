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
            // Single full-screen pane in ALL orientations/sizes. The former
            // tablet/landscape split-pane (sidebar) layouts were removed: in
            // landscape the screen simply rotates. A single pane keeps the
            // global currentDirectory unambiguous (the Sessions file-browse
            // overlay is full-screen, so Chat is never interactable during a
            // browse — no desync), and matches the product decision to drop the
            // sidebar feature.
            OpenCodeTheme(
                darkTheme = darkTheme,
                markdownFontSizes = state.markdownFontSizes
            ) {
                PhoneLayout(viewModel = viewModel, initialPage = state.lastNavPage)
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
 * the three top-level screens (Chat → Sessions → Settings).
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
    val state by viewModel.state.collectAsStateWithLifecycle()

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

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                // Disable swipe while the project file browser is open so the
                // user can't swipe to Chat with the overlay (and a re-scoped
                // global currentDirectory) still active — prevents desync.
                userScrollEnabled = !state.fileBrowserOpen,
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

        // Project file browser overlay — opened from the Sessions screen's
        // per-workdir folder button. Rendered ABOVE the pager so it covers
        // every page; the pager is swipe-disabled while open, so Chat can't be
        // reached/interacted with during a browse (the global currentDirectory
        // is temporarily re-scoped to fileBrowserWorkdir). Closing restores it.
        if (state.fileBrowserOpen) {
            val filesViewModel: FilesViewModel = hiltViewModel()
            BackHandler { viewModel.closeFileBrowser() }
            Box(modifier = Modifier.fillMaxSize()) {
                FilesScreen(
                    viewModel = filesViewModel,
                    pathToShow = state.filePathToShowInFiles,
                    sessionDirectory = state.fileBrowserWorkdir,
                    onCloseFile = { viewModel.closeFileBrowser() },
                    onFileClick = { path -> viewModel.showFileInFiles(path, "sessions") }
                )
            }
        }
    }
}
