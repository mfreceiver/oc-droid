package cn.vectory.ocdroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.chat.LocalWindowSizeClass
import cn.vectory.ocdroid.ui.shell.AppShell
import cn.vectory.ocdroid.ui.theme.OpenCodeTheme
import cn.vectory.ocdroid.util.AppLocaleController
import cn.vectory.ocdroid.util.ThemeMode
import dagger.hilt.android.AndroidEntryPoint

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
     * Reference to the Activity-scoped [OrchestratorViewModel], populated inside
     * [onCreate]'s `setContent` block once Hilt constructs it. Held so that
     * [onNewIntent] (which fires on warm-start deep links from §18
     * notifications, with `launchMode="singleTop"`) can dispatch the session
     * extra without re-entering `setContent`.
     */
    private var mainViewModel: OrchestratorViewModel? = null

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
            val viewModel: OrchestratorViewModel = hiltViewModel()
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
            val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
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
                markdownFontSizes = settings.markdownFontSizes,
                // §ui-scale: pass the persisted UI scale factors so the
                // LocalDensity override in OpenCodeTheme reacts to slider
                // changes (settingsFlow is collected above → recomposes this
                // root on every change → OpenCodeTheme re-derives scaledDensity).
                uiFontScale = settings.uiFontScale,
                uiContentScale = settings.uiContentScale
            ) {
                CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
                    // §phase3 (G.5 / plan §5 task 6 step c): AppShell is the
                    // single shell. The legacy PhoneLayout + Screen enum +
                    // USE_NEW_SHELL flag have been physically deleted after the
                    // four-judge gate + emulator regression (USE_NEW_SHELL=true,
                    // 36/38) passed. Chat / Sessions / Workspace (Files |
                    // Changes) / Settings (with sub-routes) / ContextSelector /
                    // Search / Revert are all reachable via AppShell's NavHost
                    // (see ui/shell/AppShell.kt).
                    AppShell(orchestratorVM = viewModel)
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
        // The shell observes this stable route key. Set it before selecting the
        // session so notification taps always land on Chat, including warm starts.
        mainViewModel?.setLastRoute(NavRoute.Chat)
        mainViewModel?.openSessionFromDeepLink(sessionId)
    }

    companion object {
        /**
         * Intent extra carrying a session ID to deep-link into when the
         * Activity is launched from a §18 notification tap. Defined here in
         * the write-domain of Module A so [cn.vectory.ocdroid.di.AppLifecycleMonitor]
         * (which builds the tap PendingIntent) and any deep-link entry point
         * share a single source of truth.
         */
        const val EXTRA_SESSION_ID = "opencode_session_id"
    }
}
