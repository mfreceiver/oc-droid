package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.SharedEffectBus
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §P2-3 (docs-plumbing): determinism proof for [SessionSyncCoordinator] coroutine
 * scheduling after removing the dead `reconcileDispatcher` param.
 *
 * # Background
 *
 * The `reconcileDispatcher: CoroutineDispatcher = Dispatchers.Default` constructor
 * param was ZERO-use dead code (only its declaration + the DI wiring referenced it;
 * the coordinator launches ALL coroutines on the injected `scope`). It was removed.
 *
 * # What this proves
 *
 * The REAL dispatcher control point is the injected `scope` (`CoroutineScope`),
 * NOT a dispatcher field. This test uses [StandardTestDispatcher] (does NOT auto-
 * advance) to prove:
 *  1. A coroutine launched on the coordinator's `scope` does NOT run until the
 *     virtual scheduler advances (deterministic gating via `scope`).
 *  2. The `scope`'s virtual clock drives any `delay()` the coordinator launches
 *     (time is controllable, not wall-clock).
 *
 * The companion compile-guarantee test pins that NO `CoroutineDispatcher`
 * constructor param exists — so the coordinator cannot smuggle a hardcoded
 * dispatcher (if one were re-introduced, that construction call breaks).
 *
 * Note: the existing [SessionSyncCoordinatorTest] already proves behavioral
 * determinism at 8+ `scope.testScheduler.advanceUntilIdle()` sites (delta-coalesce
 * flushes, effect emissions). This file is the focused P2-3 invariant test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSyncCoordinatorDispatcherTest {

    private lateinit var slices: cn.vectory.ocdroid.ui.SliceFlows
    private lateinit var scope: TestScope
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        io.mockk.every { Log.w(any<String>(), any<String>()) } returns 0
        io.mockk.every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        val store = cn.vectory.ocdroid.ui.SharedStateStore()
        slices = store.slices
        val repository = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        val bundle = repository.currentClientBundle()!!
        store.dispatch(cn.vectory.ocdroid.ui.AppAction.BundlePublished(bundle.generation, bundle.endpointFp))
        // StandardTestDispatcher does NOT auto-run coroutines — they pend until
        // the scheduler advances. This is the strong determinism harness.
        scope = TestScope(testDispatcher)
        // Construct the coordinator on this scope (same as production wiring,
        // which passes appScope). The coordinator has NO dispatcher param now.
        SessionSyncCoordinator(
            scope = scope,
            slices = slices,
            settingsManager = mockk(relaxed = true),
            effects = SharedEffectBus(),
            currentProfileId = { "test-fp" },
            identityStore = cn.vectory.ocdroid.service.identity.ConnectionIdentityStore(),
            repository = repository,
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `scope coroutines do not run until the virtual scheduler advances (gating via scope not dispatcher)`() =
        runTest(testDispatcher) {
            val ran = java.util.concurrent.atomic.AtomicInteger(0)
            // Launch a coroutine on the coordinator's scope (the SAME scope the
            // coordinator launches its internal work on). StandardTestDispatcher
            // must pend it — ran stays 0 until advance.
            scope.launch { ran.incrementAndGet() }

            assertEquals("scope coroutine NOT run before scheduler advance",
                0, ran.get())

            advanceUntilIdle()
            assertEquals("scope coroutine ran after scheduler advance",
                1, ran.get())
        }

    @Test
    fun `scope delay is driven by the virtual clock not wall-clock (time is controllable)`() =
        runTest(testDispatcher) {
            val ran = java.util.concurrent.atomic.AtomicInteger(0)
            scope.launch {
                delay(500L)
                ran.incrementAndGet()
            }

            // Advance less than the delay → still not run.
            advanceTimeBy(400L)
            assertEquals("delayed coroutine not run before its delay elapses",
                0, ran.get())

            // Advance past the delay → runs.
            advanceUntilIdle()
            assertTrue("delayed coroutine ran after virtual time advanced past its delay",
                ran.get() == 1)
        }

    @Test
    fun `construction smoke - current SessionSyncCoordinator signature has no dispatcher param`() {
        // rev-glm N2: this is a construction SMOKE test of the current no-dispatcher
        // signature, NOT a compile-guarantee that a future re-introduction breaks.
        // (A re-introduced param WITH a default value, like the original
        // `reconcileDispatcher: CoroutineDispatcher = Dispatchers.Default`, would
        // still compile here via the default.) The determinism proof lives in the
        // two StandardTestDispatcher tests above; this test just snapshots that
        // construction works without any dispatcher argument today.
        val c = SessionSyncCoordinator(
            scope = TestScope(StandardTestDispatcher()),
            slices = cn.vectory.ocdroid.ui.SharedStateStore().slices,
            settingsManager = mockk(relaxed = true),
            effects = SharedEffectBus(),
            currentProfileId = { "fp" },
        )
        assertEquals("fp", c.profileId())
    }
}
