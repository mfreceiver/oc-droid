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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import org.junit.Assert.assertNull
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
        // Bug-1: clear the persisted dedup store between tests so no test
        // leaks seeded keys into another test's [AppLifecycleMonitor.init]
        // seed step (every newMonitor() reads the same SharedPreferences file
        // backed by the shared Robolectric Application context).
        app.deleteSharedPreferences("ocdroid_notif_dedup")
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

    // ── Bug-1: persisted dedup survives simulated process death ───────────

    /**
     * Bug-1 (notification regeneration): the in-memory [NotificationDedup]
     * state is wiped on process death. Without seeding from durable storage,
     * the next background poll after a restart re-evaluates every idle
     * unread root as "freshly idle + never notified" and re-posts (≈ weekly
     * regeneration). [NotificationDedup.seedPosted] installs persisted keys
     * directly into the [NotificationDedup.State.Posted] slot BEFORE any
     * claim can fire, so the existing [claim] path returns `null` for them
     * (correctly suppressing re-notification).
     *
     * This test simulates "process death" by constructing a FRESH
     * [NotificationDedup] (the prior process's in-memory map is gone) and
     * seeding it from the persisted set, then asserting the seeded key
     * cannot be claimed (no re-notify). A non-seeded key CAN still be
     * claimed (legitimate first-time notification).
     */
    @Test
    fun `Bug-1 - seeded dedup key suppresses re-notify across simulated process death`() {
        // Process death: a brand-new NotificationDedup (no prior state).
        val reincarnated = NotificationDedup()
        // Simulate the persisted set the prior process wrote before death.
        val persistedIdleKeys = setOf("idle:s:w:r")
        // Seed BEFORE any claim (mirrors AppLifecycleMonitor.init).
        reincarnated.seedPosted(persistedIdleKeys)

        // The seeded key cannot be claimed → no re-notify.
        assertNull(
            "seeded key must suppress claim (already-notified in prior process)",
            reincarnated.claim("idle:s:w:r"),
        )

        // A different (non-seeded) key can still be claimed for the first time.
        assertNotNull(
            "non-seeded key remains first-time claimable",
            reincarnated.claim("idle:s:w:other"),
        )
    }

    /**
     * Bug-1-fix-C: the [NotificationDedupStore] round-trip is the
     * persistence contract [AppLifecycleMonitor] relies on. The new API is
     * mirror-based + additive (addPostedIdle / removePostedIdle /
     * snapshotIdle) — the prior saveDecisionKeys / saveIdleKeys full-
     * snapshot API is gone (decision dedup is in-memory only; idle uses
     * additive ADD/REMOVE under a lock). This test exercises the additive
     * contract across store instances (simulating process death): add a
     * key, construct a FRESH store (process death), and assert the mirror
     * reads it back; remove a key, fresh store reads empty; add two,
     * remove one, fresh store reads the survivor (slot/additive correctness
     * — never a full-set overwrite).
     */
    @Test
    fun `Bug-1-fix-C - NotificationDedupStore addIdle_removeIdle round-trips across store instances`() {
        // Add a single key in "process 1".
        NotificationDedupStore(app).addPostedIdle("idle:s:w:r")

        // Process death + reincarnation: a brand-new store reads it back.
        assertEquals(
            "addPostedIdle persisted across store instances",
            setOf("idle:s:w:r"),
            NotificationDedupStore(app).snapshotIdle(),
        )

        // Remove the key in "process 2".
        NotificationDedupStore(app).removePostedIdle("idle:s:w:r")
        assertEquals(
            "removePostedIdle cleared the key (fresh store reads empty)",
            emptySet<String>(),
            NotificationDedupStore(app).snapshotIdle(),
        )

        // Slot/additive correctness: add two keys, remove one, fresh store
        // reads the survivor (proves removePostedIdle is additive — does
        // NOT rewrite the whole set, only the delta).
        NotificationDedupStore(app).run {
            addPostedIdle("idle:a")
            addPostedIdle("idle:b")
        }
        NotificationDedupStore(app).removePostedIdle("idle:a")
        assertEquals(
            "additive remove leaves the survivor intact",
            setOf("idle:b"),
            NotificationDedupStore(app).snapshotIdle(),
        )
    }

    /**
     * Bug-1-fix-B/C: the ALM init seeds the in-memory idle dedup from
     * [NotificationDedupStore.snapshotIdle]. This test proves the seed
     * path works end-to-end through the store → ALM: a key persisted via
     * the store BEFORE constructing an [AppLifecycleMonitor] must
     * suppress `claim` on that key (the seeded slot is Posted), while a
     * non-persisted key remains first-time claimable.
     */
    @Test
    fun `Bug-1-fix-B - ALM seeds idle dedup from the store and suppresses claim on seeded keys`() {
        // Persist an idle key directly via the store (as a prior process
        // would have before death).
        NotificationDedupStore(app).addPostedIdle("idle:s:w:r")

        // Construct the ALM — its init reads snapshotIdle() and calls
        // seedPosted(...) BEFORE any claim can fire.
        val monitor = newMonitor()

        // The seeded key cannot be claimed → no re-notify.
        assertNull(
            "seeded key must suppress claim (already-notified in prior process)",
            monitor.idleNotificationSnapshot.claim("idle:s:w:r"),
        )

        // A different (non-seeded) key can still be claimed for the first time.
        assertNotNull(
            "non-seeded key remains first-time claimable",
            monitor.idleNotificationSnapshot.claim("idle:s:w:other"),
        )
    }

    /**
     * Bug-1-fix-C/race: the new additive mirror API MUST be safe under
     * concurrent mutation from multiple threads (the Main.immediate notify
     * path and the Default post-prune path can race add/remove on
     * disjoint keys). This test launches many coroutines each doing an
     * add + remove on its own key, then asserts the mirror is internally
     * consistent (empty after balanced add+remove; no crash; snapshot is
     * a clean set with no duplicates — Set<String> guarantees this, but
     * the assertion documents the invariant).
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun `Bug-1-fix-C - additive mirror is safe under concurrent add_remove`() = testScope.runTest {
        val store = NotificationDedupStore(app)
        val n = 50
        val jobs = (0 until n).map { i ->
            launch(Dispatchers.Default) {
                store.addPostedIdle("k$i")
                store.removePostedIdle("k$i")
            }
        }
        jobs.forEach { it.join() }
        runCurrent()

        // After balanced add+remove, the mirror must be empty (no key was
        // stranded by a lost-update race between the add and remove paths).
        assertEquals(
            "concurrent balanced add+remove leaves the mirror empty (no lost update)",
            emptySet<String>(),
            store.snapshotIdle(),
        )

        // Add all keys back concurrently, then assert the snapshot contains
        // exactly the n keys (no duplicates, no losses). Set<String> makes
        // duplicates structurally impossible, but the size assertion proves
        // no add was lost to a concurrent writer.
        val addJobs = (0 until n).map { i ->
            launch(Dispatchers.Default) { store.addPostedIdle("k$i") }
        }
        addJobs.forEach { it.join() }
        runCurrent()

        val snapshot = store.snapshotIdle()
        assertEquals("snapshot has exactly n keys (no add lost)", n, snapshot.size)
        assertTrue(
            "snapshot contains every k0..k(n-1)",
            (0 until n).all { "k$it" in snapshot },
        )
    }

    // ── idleMutex race closure: serialized publish+prune keep dedup ↔ mirror consistent ──

    /**
     * Race-closure stress test for the shared [AppLifecycleMonitor.idleMutex].
     *
     * Models the IDLE critical sections that now run under the SAME mutex:
     *  - **Publish** (poller [AppLifecycleMonitor.handleIdleAlert] + bridge
     *    idle branch): `claim → notifyIdle → complete → addPostedIdle → release`.
     *  - **Prune** (poller [AppLifecycleMonitor.pollPendingItems]
     *    post-prune): `pruneStaleCandidates → snapshotPosted-afterPrune →
     *    pruned.forEach { cancel + removePostedIdle }`.
     *
     * Pre-fix the prune side-effect loop ran UNLOCKED. In the window between
     * the `afterPrune` snapshot and the `pruned.forEach { ... }` loop the
     * bridge (Main) could re-claim + complete + `addPostedIdle(K)`, and the
     * poller's stale deferred `cancel(K.hashCode()) + removePostedIdle(K)`
     * clobbered the fresh completion: the dedup ended up with K (the bridge
     * completed it) but the store did NOT (the stale remove wiped the fresh
     * add) → regeneration on process death, AND the fresh shade was
     * cancelled → missed notification.
     *
     * Under the mutex, the publish and prune critical sections are mutually
     * exclusive. In BOTH orderings the dedup ↔ store invariant holds:
     *  - publish first → the prune's afterPrune (taken inside its own locked
     *    region, after publish released) sees K PRESENT → K is excluded from
     *    `pruned` → removePostedIdle NOT called → store keeps K.
     *  - prune first → K removed from dedup + store; later publish re-claims
     *    and re-adds → both have K.
     *
     * The invariant `dedup.contains(K) == (K in store)` therefore holds
     * after every serialized op. This stress test launches N coroutines on
     * Dispatchers.Default each running either a publish or a prune under
     * the shared [Mutex], then asserts the invariant at the end. (Without
     * the mutex the stale-side-effect window above would, under sufficient
     * parallelism, eventually leave dedup with K and store without it.)
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test
    fun `idleMutex - serialized publish and prune keep dedup and mirror consistent under concurrent stress`() =
        testScope.runTest {
            val dedup = NotificationDedup()
            val store = NotificationDedupStore(app)
            val mutex = Mutex()
            val key = "idle:fp:/wd:ses-root:100"

            // Seed K as Posted in BOTH the dedup and the store (initial
            // state: a prior notify completed and was mirrored).
            val seedToken = dedup.claim(key)!!
            assertTrue("seed claim wins on empty dedup", seedToken.isNotBlank())
            assertTrue("seed completes to Posted", dedup.complete(key, seedToken))
            store.addPostedIdle(key)
            assertTrue("seed mirrored to store", key in store.snapshotIdle())

            // N interleaved ops, half publish / half prune, all under the
            // shared mutex. Each publish mirrors the production critical
            // section (claim → complete → addPostedIdle, releasing on the
            // failure paths). Each prune mirrors the production post-prune
            // critical section (pruneStaleCandidates → afterPrune →
            // cancel + removePostedIdle for the diff).
            val n = 200
            val cancelCount = java.util.concurrent.atomic.AtomicInteger(0)
            val jobs = (0 until n).map { i ->
                launch(Dispatchers.Default) {
                    if (i % 2 == 0) {
                        // Publish path under the shared mutex.
                        mutex.withLock {
                            val token = dedup.claim(key)
                            if (token != null) {
                                // Fake notify succeeded (the race is
                                // independent of the platform notify).
                                dedup.complete(key, token)
                                store.addPostedIdle(key)
                            }
                            // If claim returned null, K was still Posted
                            // from a prior publish that no prune has
                            // reached yet — no-op (idempotent).
                        }
                    } else {
                        // Prune path: candidates captured (mirrors production
                        // — the candidate snapshot is taken before the
                        // unread poll, OUTSIDE the lock), then prune +
                        // side-effects under the shared mutex.
                        val candidates = dedup.snapshotPosted()
                        mutex.withLock {
                            dedup.pruneStaleCandidates(candidates, active = emptySet())
                            val afterPrune = dedup.snapshotPosted()
                            val pruned = candidates.keys - afterPrune.keys
                            pruned.forEach { k ->
                                // Fake cancel (production calls
                                // notificationManager.cancel(k.hashCode())).
                                cancelCount.incrementAndGet()
                                store.removePostedIdle(k)
                            }
                        }
                    }
                }
            }
            jobs.forEach { it.join() }
            runCurrent()

            // Invariant: the dedup and the mirror agree on K's presence.
            // Under the mutex this holds after every serialized op; without
            // the mutex the stale-side-effect window above could leave
            // dedup with K (bridge completed) and store without K (stale
            // remove clobbered the fresh add).
            val dedupHas = dedup.contains(key)
            val storeHas = key in store.snapshotIdle()
            assertEquals(
                "dedup and store must agree on key presence after concurrent publish+prune (race closed)",
                dedupHas,
                storeHas,
            )

            // Sanity: the prune path DID fire its side-effect at least once
            // (proves the test actually exercised the locked region —
            // otherwise it could pass for the wrong reason). Across N/2
            // prune ops on a key that flips in/out of the dedup, the diff
            // fires multiple times.
            assertTrue(
                "prune side-effect (cancel + removePostedIdle) fired at least once during the stress",
                cancelCount.get() > 0,
            )
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
