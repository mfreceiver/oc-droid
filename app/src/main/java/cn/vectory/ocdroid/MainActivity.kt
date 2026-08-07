package cn.vectory.ocdroid

import android.content.pm.ActivityInfo
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.chat.LocalWindowSizeClass
import cn.vectory.ocdroid.ui.shell.AppShell
import cn.vectory.ocdroid.ui.theme.OpenCodeTheme
import cn.vectory.ocdroid.util.AppLocaleController
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

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
     * §P5a (Q5): SettingsManager for the warm-recreate locale re-apply in
     * [onCreate]. Injected by Hilt (@AndroidEntryPoint field injection,
     * populated before onCreate returns from super).
     */
    @Inject
    lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (resources.configuration.smallestScreenWidthDp < 600) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        // §P5a (Q5): re-apply the persisted locale on every Activity create.
        // The authoritative cold-start point is OpenCodeApp.onCreate (runs
        // once per process, before the first frame); this warm-recreate call
        // catches a SYSTEM-locale change that happened while the process was
        // alive (system locale change triggers an Activity recreate WITHOUT
        // re-running Application.onCreate, so the SYSTEM-mode re-resolution
        // here is what keeps "Follow System" honest across a backgrounded
        // system-language change). AppCompatDelegate is idempotent (no-op
        // when the resolved locale list is unchanged), so calling this every
        // time is safe.
        AppLocaleController.applyPersisted(this, settingsManager)
        enableEdgeToEdge()
        setContent {
            val viewModel: OrchestratorViewModel = hiltViewModel()
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
                    // 36/38) passed. Chat / Files / Git / Sessions / Settings
                    // (with sub-routes) /
                    // Search / Revert are all reachable via AppShell's NavHost
                    // (see ui/shell/AppShell.kt).
                    AppShell(
                        orchestratorVM = viewModel,
                    )
                }
            }
        }
    }
}
