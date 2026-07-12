package cn.vectory.ocdroid

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.app.Activity
import android.view.ViewTreeObserver
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.NavRoute
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
                    // 36/38) passed. Chat / Files / Git / Sessions / Settings
                    // (with sub-routes) /
                    // Search / Revert are all reachable via AppShell's NavHost
                    // (see ui/shell/AppShell.kt).
                    AppShell(
                        orchestratorVM = viewModel,
                        navBarBottomDp = rememberNavBarBottomDp(this@MainActivity),
                    )
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

/**
 * §bug-6.4: capture the navigation-bar bottom inset from the Android View
 * inset system (WindowInsetsCompat on the decorView root) — Compose's
 * [androidx.compose.foundation.layout.WindowInsets.navigationBars] resolves to
 * 0 under this AppCompat theme (the DecorView consumes the inset before Compose
 * receives it), so every Compose-inset-based fill attempt left a gap below the
 * bottom bar. The decorView root gets the unconsumed system inset; threading it
 * in as a plain [Dp] lets [cn.vectory.ocdroid.ui.shell.AppShell] size the bar's
 * bottom spacer to the real gesture-pill height. Reactive — updates on config
 * changes (e.g. the inset height changing).
 *
 * §review-final (Blocker-2 residual): this previously used
 * `ViewCompat.setOnApplyWindowInsetsListener(decorView) { ... }`. That call
 * *replaces* the DecorView's own insets dispatch (it sets a fresh
 * `OnApplyWindowInsetsListener` that overrides AppCompat's DecorView behavior —
 * the status-guard view, color-view frame offsets, IME handling, OEM
 * gesture-inset consumption, etc.). The override returned the insets unchanged
 * so visually 6.4 still filled the gesture area, but the side effect was that
 * AppCompat's per-DecorView inset handling was bypassed for the lifetime of
 * the listener — a latent regression surface for TopAppBar padding, IME
 * animation, and OEM-specific behavior. The new implementation uses an
 * observation-only path:
 *
 *  - [ViewTreeObserver.OnGlobalLayoutListener] fires on layout / inset
 *    changes (it is *additive* — never replaces existing dispatch).
 *  - [ViewCompat.getRootWindowInsets] is a *read-only* query of the latest
 *    root insets that have already been dispatched through the normal pipeline.
 *    It does not intercept, consume, or replace any inset; AppCompat's
 *    DecorView handler runs first as usual, and we merely observe the result.
 *
 * Together they give us the same `navigationBars().bottom` value as the
 * intercepting listener did, without any of the side effects. The
 * `requestApplyInsets()` call triggers one extra layout/inset pass so the
 * listener observes a real value on first composition (otherwise the first
 * callback would fire only on the next configuration change).
 */
@Composable
private fun rememberNavBarBottomDp(activity: Activity): Dp {
    val density = LocalDensity.current
    var bottomPx by remember { mutableIntStateOf(0) }
    DisposableEffect(activity) {
        val decorView = activity.window.decorView
        // §review-final: observation-only — additive listener + read-only
        // query. No setOnApplyWindowInsetsListener anywhere.
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val root = ViewCompat.getRootWindowInsets(decorView)
            bottomPx = root?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        }
        decorView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        // Trigger one inset/layout pass so the listener observes a real value
        // immediately instead of waiting for the next configuration change.
        decorView.requestApplyInsets()
        onDispose {
            decorView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }
    return with(density) { bottomPx.toDp() }
}
