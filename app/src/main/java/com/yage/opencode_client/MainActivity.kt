package com.yage.opencode_client

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.yage.opencode_client.ui.MainViewModel
import com.yage.opencode_client.ui.chat.ChatScreen
import com.yage.opencode_client.ui.chat.LocalWindowSizeClass
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
 * Top-level screens shown in the phone navigation.
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

// Page order for the phone navigation: Chat → Sessions → Settings.
// File browsing is reached from the Sessions screen (per-project folder button)
// and via in-chat file-path taps — not a top-level destination.
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
        // Re-apply the locale policy (en→follow system, non-en→zh) before the
        // first frame is composed AND on every Activity recreate, so a system
        // language change while the app is backgrounded is reflected on the
        // next onCreate. AppCompatDelegate is idempotent (no-op/recreate only
        // when the locale actually changes), so this is safe to call every
        // time. OpenCodeApp.onCreate also calls it for process start.
        AppLocaleController.applySystemLocale()
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
            // §R-17 M5.1 (kimo 🟠#1): subscribe ONLY to lastNavPage instead of the
            // whole AppState. The mirror double-write makes `_state` emit on every
            // keystroke / SSE delta; a full collect here would recompose this root
            // composable each time, cancelling the slice-migration gains. The
            // distinctUntilChanged + map keeps this composable inert unless the
            // nav page actually changes.
            val lastNavPage by viewModel.state
                .map { it.lastNavPage }
                .distinctUntilChanged()
                .collectAsStateWithLifecycle(initialValue = 0)
            val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()
            val darkTheme = when (settings.themeMode) {
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
            // §B3: compute the M3 WindowSizeClass once per configuration from
            // the Activity (the canonical entry point — `calculateWindowSizeClass`
            // is the stable 1.2.0+ API for deriving Compact / Medium / Expanded
            // breakpoints from the real window size). Provided via a
            // CompositionLocal so any descendant screen (ChatScreen etc.) can
            // read it without each one re-deriving from
            // LocalConfiguration.screenWidthDp. `@OptIn(ExperimentalMaterial3WindowSizeClassApi)`
            // is on the MainActivity class.
            val windowSizeClass = calculateWindowSizeClass(this)
            OpenCodeTheme(
                darkTheme = darkTheme,
                markdownFontSizes = settings.markdownFontSizes
            ) {
                CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
                    PhoneLayout(viewModel = viewModel, initialPage = lastNavPage)
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
 * Phone layout: button-driven top-level navigation between Chat / Sessions /
 * Settings, with a crossfade ([AnimatedContent]) between destinations.
 *
 * The previous swipeable [HorizontalPager] has been replaced by explicit
 * navigation callbacks (top-bar buttons + system back). Each page renders its
 * own TopAppBar showing the current screen name (and a back arrow where
 * applicable), so no shared app bar is needed. The project file-browser
 * overlay is rendered ABOVE all destinations and is opaque so destination
 * content does not bleed through.
 */
@Composable
private fun PhoneLayout(viewModel: MainViewModel, initialPage: Int = 0) {
    val file by viewModel.fileFlow.collectAsStateWithLifecycle()
    var navPage by rememberSaveable {
        mutableStateOf(initialPage.coerceIn(0, screens.lastIndex))
    }

    fun switchToPage(page: Int) {
        val clamped = page.coerceIn(0, screens.lastIndex)
        navPage = clamped
        viewModel.setLastNavPage(clamped)
    }

    // Discard any in-progress draft session whenever the user navigates AWAY
    // from the Chat destination. The effect fires on every navPage change
    // (including transitions INTO Chat), but the guard inside
    // clearDraftIfActive only acts on draftWorkdir!=null && currentSessionId==null,
    // and reaching Chat is the desired outcome for a fresh draft — so the
    // `if (navPage != Chat)` guard here makes transitioning into Chat a no-op.
    LaunchedEffect(navPage) {
        if (navPage != screens.indexOf(Screen.Chat)) {
            viewModel.clearDraftIfActive()
        }
    }

    // #12: System back returns to Chat from any non-Chat destination. On the
    // Chat destination itself the handler is disabled, so back exits the app
    // (expected).
    BackHandler(enabled = navPage != screens.indexOf(Screen.Chat)) {
        switchToPage(screens.indexOf(Screen.Chat))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { padding ->
            AnimatedContent(
                targetState = navPage,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "phoneNav",
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
                        onNavigateToSessions = {
                            switchToPage(screens.indexOf(Screen.Sessions))
                        }
                    )
                    Screen.Sessions -> SessionsScreen(
                        viewModel = viewModel,
                        onSwitchToChat = { switchToPage(screens.indexOf(Screen.Chat)) }
                    )
                    Screen.Settings -> SettingsScreen(
                        viewModel = viewModel,
                        onBack = { switchToPage(screens.indexOf(Screen.Chat)) }
                    )
                }
            }
        }

        // Project file browser overlay — opened from the Sessions screen's
        // per-workdir folder button. Rendered ABOVE all destinations so it
        // covers every page; while open the user can only interact with the
        // overlay (system back closes it via the BackHandler below). The Box
        // is given an opaque surface background so the destination
        // underneath does not bleed through (FilesScreen's own root is
        // transparent by design — opaqueness is the host's responsibility).
        if (file.fileBrowserOpen) {
            val filesViewModel: FilesViewModel = hiltViewModel()
            BackHandler { viewModel.closeFileBrowser() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                FilesScreen(
                    viewModel = filesViewModel,
                    pathToShow = file.filePathToShowInFiles,
                    sessionDirectory = file.fileBrowserWorkdir,
                    onCloseFile = { viewModel.closeFileBrowser() },
                    onFileClick = { path -> viewModel.showFileInFiles(path, "sessions") }
                )
            }
        }
    }
}

