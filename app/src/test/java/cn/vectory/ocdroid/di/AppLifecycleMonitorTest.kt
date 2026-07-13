package cn.vectory.ocdroid.di

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FGS spec §4.3 / §7 — Robolectric tests for [AppLifecycleMonitor].
 *
 * Two CP8 invariants verified here:
 *  1. **§4.3 foreground truth-source default flip**: `_isInForeground` now
 *     defaults to `false` (was `true` pre-CP8). A sticky-rebuilt process with
 *     no started Activity must be treated as background — the prior `true`
 *     default caused the §4.3 bug. The 0→1 `onActivityStarted` transition
 *     still flips it to `true` on the first real Activity start; only the
 *     "no Activity yet" window is now correctly background.
 *  2. **§7 channel matrix**: `createChannels` now registers
 *     `ocdroid.session_status` (IMPORTANCE_LOW) alongside the two existing
 *     channels (`ocdroid.decisions` HIGH / `ocdroid.errors` DEFAULT).
 *
 * Uses Robolectric because `Application.registerActivityLifecycleCallbacks`
 * (ALM init) and `NotificationManager.createNotificationChannel` are Android
 * framework surfaces.
 *
 * The custom [CapturingApp] overrides `registerActivityLifecycleCallbacks`
 * purely so the test can drive `onActivityStarted` / `onActivityStopped`
 * directly without spinning up a real Activity (Robolectric does not replay
 * Activity transitions otherwise, and reflection over framework internals is
 * fragile across Android versions).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CapturingApp::class)
class AppLifecycleMonitorTest {

    private lateinit var app: CapturingApp
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    // ── §4.3 foreground truth-source ────────────────────────────────────────

    @Test
    fun `section 4_3 - isInForeground default is false (sticky-rebuild safe)`() {
        val monitor = newMonitor()
        // CP8 flip: the prior `true` default caused the §4.3 bug. A
        // sticky-rebuilt process with no started Activity is background.
        assertFalse(
            "CP8 §4.3: isInForeground must default to false (no Activity yet = background)",
            monitor.isInForeground.value,
        )
    }

    @Test
    fun `section 4_3 - first onActivityStarted flips foreground to true`() {
        val monitor = newMonitor()
        val cb = app.registeredCallbacks

        assertNotNull("ALM init must register one ActivityLifecycleCallbacks", cb)
        cb!!.onActivityStarted(mockk<android.app.Activity>(relaxed = true))

        assertTrue(
            "0→1 onActivityStarted → isInForeground true (foreground UX unchanged)",
            monitor.isInForeground.value,
        )
    }

    @Test
    fun `section 4_3 - onActivityStopped 1 to 0 flips foreground back to false`() {
        val monitor = newMonitor()
        val cb = app.registeredCallbacks!!
        // Drive the started-count to 1 first (foreground).
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        assertTrue(monitor.isInForeground.value)

        cb.onActivityStopped(mockk<android.app.Activity>(relaxed = true))

        assertFalse(
            "1→0 onActivityStopped → isInForeground false",
            monitor.isInForeground.value,
        )
    }

    @Test
    fun `section 4_3 - partial Activity overlap keeps foreground true until count drops to zero`() {
        val monitor = newMonitor()
        val cb = app.registeredCallbacks!!
        // Two Activities start — count goes 0→1→2.
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        assertTrue(monitor.isInForeground.value)

        // First stops — count 2→1 (still foreground).
        cb.onActivityStopped(mockk<android.app.Activity>(relaxed = true))
        assertTrue(monitor.isInForeground.value)

        // Second stops — count 1→0 (background).
        cb.onActivityStopped(mockk<android.app.Activity>(relaxed = true))
        assertFalse(monitor.isInForeground.value)
    }

    // ── §7 channel matrix ─────────────────────────────────────────────────

    @Test
    fun `createChannels registers all three channels - decisions HIGH, errors DEFAULT, session_status LOW`() {
        AppLifecycleMonitor.createChannels(app)

        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channels = manager.notificationChannels.associateBy { it.id }

        assertNotNull("ocdroid.decisions registered", channels[AppLifecycleMonitor.CHANNEL_DECISIONS])
        assertNotNull("ocdroid.errors registered", channels[AppLifecycleMonitor.CHANNEL_ERRORS])
        val session = channels[AppLifecycleMonitor.CHANNEL_SESSION_STATUS]
        assertNotNull("ocdroid.session_status registered (CP8)", session)
        assertEquals(
            "§7 channel matrix: session_status is IMPORTANCE_LOW",
            android.app.NotificationManager.IMPORTANCE_LOW,
            session!!.importance,
        )
        // Descriptions are non-null (the production code always sets one).
        assertNotNull("session_status has a description", session.description)
    }

    @Test
    fun `createChannels is idempotent - re-invocation does not duplicate session_status`() {
        AppLifecycleMonitor.createChannels(app)
        AppLifecycleMonitor.createChannels(app) // idempotent per platform contract.

        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sessionCount = manager.notificationChannels.count {
            it.id == AppLifecycleMonitor.CHANNEL_SESSION_STATUS
        }
        assertEquals("re-create is a no-op (single channel)", 1, sessionCount)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Builds an [AppLifecycleMonitor] with the real [app] so its
     * `init { registerActivityLifecycleCallbacks(...) }` runs against the
     * Robolectric Application. Other constructor deps are relaxed mocks —
     * we never reach them in the lifecycle-callback paths exercised here.
     */
    private fun newMonitor(): AppLifecycleMonitor = AppLifecycleMonitor(
        application = app,
        appScope = scope,
        repository = mockk<OpenCodeRepository>(relaxed = true),
        settingsManager = mockk<SettingsManager>(relaxed = true),
    )
}

/**
 * Test-only [Application] subclass that captures the
 * [Application.ActivityLifecycleCallbacks] registered by [AppLifecycleMonitor]'s
 * init. Avoids framework-internals reflection.
 *
 * Declared top-level + public because [Config.application] instantiates it by
 * name from Robolectric's class loader.
 */
class CapturingApp : Application() {
    var registeredCallbacks: ActivityLifecycleCallbacks? = null
        private set

    override fun registerActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks?) {
        registeredCallbacks = callback
        super.registerActivityLifecycleCallbacks(callback)
    }
}
