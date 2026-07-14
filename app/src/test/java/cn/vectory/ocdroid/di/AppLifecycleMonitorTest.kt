package cn.vectory.ocdroid.di

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.controller.IdleUnreadAlert
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.mockk
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 *  3. **D1 (gate #2) 700ms background-confirmation**: the synchronous 1→0
 *     flip was replaced with a delayed confirmation (matching AndroidX
 *     `ProcessLifecycleOwner`). Tests use virtual time via [TestScope] to
 *     advance the confirmation window.
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CapturingApp::class)
class AppLifecycleMonitorTest {

    private lateinit var app: CapturingApp
    private lateinit var scope: CoroutineScope
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // M5: the confirmation delay runs exclusively on the injected
        // UiApplicationScope; this TestScope virtualizes its Main dispatcher.
        testScope = TestScope(StandardTestDispatcher())
        scope = testScope
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
    fun `section 4_3 - onActivityStopped 1 to 0 flips foreground back to false after 700ms`() = testScope.runTest {
        // D1 (gate #2): the synchronous flip was replaced with a 700ms
        // background-confirmation delay (matches AndroidX
        // ProcessLifecycleOwner). The 1→0 transition alone no longer
        // immediately flips foreground; we must advance virtual time past
        // BACKGROUND_CONFIRMATION_MS to observe the flip.
        val monitor = newMonitor()
        val cb = app.registeredCallbacks!!
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        assertTrue(monitor.isInForeground.value)

        cb.onActivityStopped(mockk<android.app.Activity>(relaxed = true))
        // Pre-confirmation: still foreground.
        assertTrue(
            "1→0 inside the 700ms window stays foreground (no premature flip)",
            monitor.isInForeground.value,
        )

        advanceTimeBy(AppLifecycleMonitor.BACKGROUND_CONFIRMATION_MS)
        runCurrent()

        assertFalse(
            "1→0 + 700ms confirmation → isInForeground false",
            monitor.isInForeground.value,
        )
        // Cleanup: go foreground to cancel the legacy background poller
        // (else runTest sees an uncompleted child coroutine).
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        runCurrent()
    }

    @Test
    fun `section 4_3 - partial Activity overlap keeps foreground true until count drops to zero`() = testScope.runTest {
        val monitor = newMonitor()
        val cb = app.registeredCallbacks!!
        // Two Activities start — count goes 0→1→2.
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        assertTrue(monitor.isInForeground.value)

        // First stops — count 2→1 (still foreground; no confirmation job armed).
        cb.onActivityStopped(mockk<android.app.Activity>(relaxed = true))
        assertTrue(monitor.isInForeground.value)

        // Second stops — count 1→0 (background confirmation armed, but
        // hasn't fired yet — still foreground).
        cb.onActivityStopped(mockk<android.app.Activity>(relaxed = true))
        assertTrue(
            "1→0 inside the 700ms window stays foreground",
            monitor.isInForeground.value,
        )

        advanceTimeBy(AppLifecycleMonitor.BACKGROUND_CONFIRMATION_MS)
        runCurrent()
        assertFalse(monitor.isInForeground.value)
        // Cleanup: foreground return cancels the legacy poller.
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        runCurrent()
    }

    // ── D1 (gate #2): 700ms background-confirmation corner cases ─────────

    @Test
    fun `D1 gate #2 - 1 to 0 then advance 699ms stays foreground`() = testScope.runTest {
        // Just under the 700ms confirmation window → still foreground.
        val monitor = newMonitor()
        val cb = app.registeredCallbacks!!
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        cb.onActivityStopped(mockk<android.app.Activity>(relaxed = true))
        assertTrue(monitor.isInForeground.value)

        advanceTimeBy(AppLifecycleMonitor.BACKGROUND_CONFIRMATION_MS - 1)
        runCurrent()
        assertTrue(
            "699ms < 700ms confirmation window — still foreground",
            monitor.isInForeground.value,
        )
        // Cleanup: never actually went background, but cancel any pending
        // confirmation job to avoid runTest leak reports.
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        runCurrent()
    }

    @Test
    fun `D1 gate #2 - 1 to 0 to 1 within 700ms never emits background`() = testScope.runTest {
        // Rotation-style 1→0→1 cycle inside 700ms: the 0→1 cancels the
        // pending confirmation job, so background is NEVER emitted.
        val monitor = newMonitor()
        val cb = app.registeredCallbacks!!
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        cb.onActivityStopped(mockk<android.app.Activity>(relaxed = true))
        assertTrue(monitor.isInForeground.value)

        // Re-start inside the window — cancels the confirmation job.
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        assertTrue(monitor.isInForeground.value)

        // Advance well past 700ms — no confirmation fires (job was cancelled).
        advanceTimeBy(AppLifecycleMonitor.BACKGROUND_CONFIRMATION_MS * 3)
        runCurrent()
        assertTrue(
            "1→0→1 inside 700ms — never emits background",
            monitor.isInForeground.value,
        )
    }

    @Test
    fun `D1 gate #2 - 1 to 0 advance 700ms emits background exactly once`() = testScope.runTest {
        val monitor = newMonitor()
        val cb = app.registeredCallbacks!!
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        cb.onActivityStopped(mockk<android.app.Activity>(relaxed = true))

        advanceTimeBy(AppLifecycleMonitor.BACKGROUND_CONFIRMATION_MS)
        runCurrent()
        assertFalse(monitor.isInForeground.value)

        // Advancing further does not flip again — the confirmation job is
        // a single-shot guard, and there's no re-emission of background.
        advanceTimeBy(AppLifecycleMonitor.BACKGROUND_CONFIRMATION_MS * 2)
        runCurrent()
        assertFalse(monitor.isInForeground.value)
        // Cleanup: foreground return cancels the legacy poller.
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        runCurrent()
    }

    @Test
    fun `M5 Default dispatcher advancement cannot race Main confirmation after restart`() {
        val defaultScheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val mainScheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val defaultScope = TestScope(StandardTestDispatcher(defaultScheduler))
        val mainScope = TestScope(StandardTestDispatcher(mainScheduler))
        val monitor = newMonitor(appScope = defaultScope, uiScope = mainScope)
        val cb = app.registeredCallbacks!!
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        cb.onActivityStopped(mockk<android.app.Activity>(relaxed = true))

        defaultScheduler.advanceTimeBy(AppLifecycleMonitor.BACKGROUND_CONFIRMATION_MS * 2)
        defaultScheduler.runCurrent()
        assertTrue("Default scope does not own confirmation", monitor.isInForeground.value)

        mainScheduler.advanceTimeBy(AppLifecycleMonitor.BACKGROUND_CONFIRMATION_MS - 1)
        mainScheduler.runCurrent()
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        mainScheduler.advanceTimeBy(2)
        mainScheduler.runCurrent()
        assertTrue("Main restart cancels before delayed recheck", monitor.isInForeground.value)
        defaultScope.cancel()
        mainScope.cancel()
    }

    @Test
    fun `D1 gate #2 - later onActivityStarted after background cancels pending poller`() = testScope.runTest {
        // Going to background arms the legacy poller (via onEnterBackground).
        // A subsequent 0→1 must cancel the poller (foreground uses in-app
        // cards) — the foreground return must not leave the legacy poller
        // running alongside the in-app surfaces.
        val monitor = newMonitor()
        val cb = app.registeredCallbacks!!
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        cb.onActivityStopped(mockk<android.app.Activity>(relaxed = true))
        advanceTimeBy(AppLifecycleMonitor.BACKGROUND_CONFIRMATION_MS)
        runCurrent()
        assertFalse(monitor.isInForeground.value)

        // Foreground return: cancels the backgroundConfirmationJob (already
        // completed) AND fires onEnterForeground which cancels the legacy
        // poller job. We assert the foreground signal flips back; the
        // poller-cancellation is exercised by the production
        // onEnterForeground code path.
        cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
        assertTrue(monitor.isInForeground.value)
    }

    // ── T5-re-review I2-R — Main.immediate publish gate (TOCTOU closure) ──

    /**
     * T5-re-review I2-R: the poller runs on Dispatchers.Default; the three
     * ALM handlers read `_isInForeground.value` and call `notifier.notify*`
     * on Default. But `onActivityStarted` sets `_isInForeground = true` on
     * Main. The Default read-then-notify was a TOCTOU — a Main ON_START
     * between the Default read and the Default notify posted sound /
     * vibration while foregrounded.
     *
     * Fix: the final foreground gate + notify + complete run SYNCHRONOUSLY
     * on `Dispatchers.Main.immediate`, serialized with lifecycle callbacks.
     * This test proves the closure deterministically: a custom scheduling
     * Main dispatcher (whose `.immediate` variant IS scheduling, unlike the
     * default test wrapper which is always inline) makes
     * `withContext(Dispatchers.Main.immediate)` queue and suspend. The
     * handler is launched on `UnconfinedTestDispatcher` (mimicking Default
     * — runs eagerly up to the withContext suspension); the continuation
     * queues on the scheduler; we flip foreground to true (onActivityStarted)
     * BEFORE resuming; the resumed withContext body reads fg=true and
     * suppresses — NO notification is posted AND the claim is released
     * (finally ran) so a later background attempt can fire.
     */
    @Test
    fun `I2-R - foreground flip on Main during publish suppresses notification and releases claim`() {
        // Custom scheduling Main dispatcher: the default `setMain` wrapper
        // makes `.immediate.isDispatchNeeded` always false (inline). This
        // custom dispatcher's `.immediate` returns `this` (which has
        // `isDispatchNeeded = true`), so `withContext(Dispatchers.Main.
        // immediate)` QUEUES and SUSPENDS — enabling deterministic
        // interleaving between the Default pre-check/claim and the Main gate.
        val scheduler = TestCoroutineScheduler()
        val testDispatcher = StandardTestDispatcher(scheduler)
        val schedulingMain = object : MainCoroutineDispatcher() {
            override val immediate: MainCoroutineDispatcher = this
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) =
                testDispatcher.dispatch(context, block)
        }
        Dispatchers.setMain(schedulingMain)
        try {
            val appScope = CoroutineScope(UnconfinedTestDispatcher(scheduler))
            val monitor = newMonitor(appScope = appScope, uiScope = CoroutineScope(testDispatcher))
            val cb = app.registeredCallbacks!!

            // Start in background (CP8 default-false).
            assertFalse("background at start", monitor.isInForeground.value)

            val key = "idle:fp:/wd:ses-root:100"
            val alert = IdleUnreadAlert("ses-root", "Project A", 100L, key)

            // Launch the handler on appScope (Default in production). It runs
            // eagerly (UnconfinedTestDispatcher): pre-check (bg, continue)
            // → claim (succeeds) → withContext(Main.immediate) → dispatches
            // (isDispatchNeeded=true on schedulingMain) → suspends, queues
            // the continuation on the scheduler.
            appScope.launch { monitor.handleIdleAlert(alert) }

            // Flip foreground to true, simulating onActivityStarted firing
            // AFTER the claim but BEFORE the withContext body runs. This is
            // the exact TOCTOU the I2-R fix closes: the pre-check on Default
            // saw background; the gate on Main.immediate must see the flip.
            cb.onActivityStarted(mockk<android.app.Activity>(relaxed = true))
            assertTrue("foreground flipped before publish", monitor.isInForeground.value)

            // Resume the queued withContext body — reads fg=true, suppresses,
            // returns; finally releases the claim.
            scheduler.runCurrent()

            // Assert: NO notification was posted (the gate suppressed the
            // publish before notifier.notifyIdle was ever called).
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            assertEquals(
                "foreground flip during publish must suppress the notification (TOCTOU closed)",
                0,
                nm.activeNotifications.size,
            )

            // Assert: the claim was released (finally ran on suppression) so
            // a later background attempt can re-post.
            assertFalse(
                "suppressed publish must release the claim so a later background attempt can fire",
                monitor.idleNotificationSnapshot.contains(key),
            )

            // A later background attempt on the same key can re-claim (proves
            // the release actually cleared the slot — otherwise the second
            // claim would lose to the stranded first claim).
            val tokenRetry = monitor.idleNotificationSnapshot.claim(key)
            assertNotNull(
                "released claim lets a later background attempt re-claim",
                tokenRetry,
            )
        } finally {
            Dispatchers.resetMain()
        }
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
    private fun newMonitor(
        appScope: CoroutineScope = scope,
        uiScope: CoroutineScope = scope,
    ): AppLifecycleMonitor = AppLifecycleMonitor(
        application = app,
        appScope = appScope,
        uiScope = uiScope,
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
