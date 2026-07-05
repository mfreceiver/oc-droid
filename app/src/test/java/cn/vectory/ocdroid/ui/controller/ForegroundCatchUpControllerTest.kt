package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-16 M1 → R-17 batch3b: independent unit test for [ForegroundCatchUpController].
 *
 * Zero reflection — the controller's state machine is driven entirely through
 * its public API ([onForegroundChanged] / [onServerConnected] /
 * [onHostReconfigured]) and asserted via:
 *  - the emitted [ControllerEffect]s on a real [SharedEffectBus] (a coroutine
 *    on the test scope drains every effect into [collectedEffects]), and
 *  - direct reads of the [chatFlow] / [composerFlow] slices (the controller
 *    now writes the staleNotice banner and reads currentSessionId inline).
 *
 * The controller is constructed on a SEPARATE [controllerScope] (not the test
 * scope) because its init's `launchIn(appLifecycleMonitor.isInForeground)`
 * never completes — sharing the test scope would trigger
 * `UncompletedCoroutinesError`. The unconfined dispatcher on both scopes means
 * `tryEmit` on the controller's call stack synchronously delivers to the
 * collector, so the assertions can run immediately after the controller call.
 *
 * An injected `clock` makes the three tier thresholds (<15s / 15s–5min / >5min)
 * deterministic without depending on wall-clock latency.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ForegroundCatchUpControllerTest {

    private lateinit var foregroundFlow: MutableStateFlow<Boolean>
    private lateinit var chatFlow: MutableStateFlow<ChatState>
    private lateinit var composerFlow: MutableStateFlow<ComposerState>
    private lateinit var settingsManager: SettingsManager
    private lateinit var effects: SharedEffectBus
    private var nowMs: Long = 0L

    @Before
    fun setUp() {
        foregroundFlow = MutableStateFlow(true)
        chatFlow = MutableStateFlow(ChatState())
        composerFlow = MutableStateFlow(ComposerState())
        settingsManager = mockk(relaxed = true)
        effects = SharedEffectBus()
        nowMs = 0L
    }

    /**
     * Runs [block] with [collectedEffects] wired up. The collector runs on an
     * [UnconfinedTestDispatcher] so the controller's `tryEmit` synchronously
     * delivers to it (no scheduler advance needed in the test body). The
     * controller is given a SEPARATE [controllerScope] using a
     * [StandardTestDispatcher] — that way its init's
     * `launchIn(appLifecycleMonitor.isInForeground)` is queued but does not
     * eagerly fire its first emission (mirroring the original test pattern
     * where the first explicit `onForegroundChanged(true)` call was the
     * baseline consumer). Both scopes are cancelled in [finally] — no
     * [kotlinx.coroutines.test.runTest] so there's no
     * UncompletedCoroutinesError check to trip on the never-ending launchIn.
     */
    private fun withCollectedEffects(block: (controllerScope: CoroutineScope, collectedEffects: List<ControllerEffect>) -> Unit) {
        val collectorDispatcher = UnconfinedTestDispatcher()
        val collectorScope = CoroutineScope(SupervisorJob() + collectorDispatcher)
        val controllerDispatcher = StandardTestDispatcher()
        val controllerScope = CoroutineScope(SupervisorJob() + controllerDispatcher)
        val collected = mutableListOf<ControllerEffect>()
        val collectorJob = collectorScope.launch { effects.effectsConsumed.toList(collected) }
        try {
            block(controllerScope, collected)
        } finally {
            collectorJob.cancel()
            collectorScope.cancel()
            controllerScope.cancel()
        }
    }

    /** Builds a controller wired to [foregroundFlow] + [effects] + [nowMs]. */
    private fun makeController(scope: CoroutineScope): ForegroundCatchUpController =
        ForegroundCatchUpController(
            appLifecycleMonitor = stubMonitor(),
            scope = scope,
            chatFlow = chatFlow,
            composerFlow = composerFlow,
            settingsManager = settingsManager,
            effects = effects,
            clock = { nowMs }
        )

    // ── first-emission guard ───────────────────────────────────────────────

    @Test
    fun `first foreground emission is treated as baseline - no effects fire`() = withCollectedEffects { controllerScope, collected ->
        val controller = makeController(controllerScope)
        // The init subscription delivers the current value (true); that is the
        // baseline, not a transition — nothing should happen.
        controller.onForegroundChanged(true)
        assertTrue("no ForceReconnect on first emission", collected.none { it is ControllerEffect.ForceReconnect })
        assertTrue("no CancelSse on first emission", collected.none { it is ControllerEffect.CancelSse })
    }

    // ── background tier ────────────────────────────────────────────────────

    @Test
    fun `background transition clears draft, cancels SSE, stamps backgroundedAtMs`() = withCollectedEffects { controllerScope, collected ->
        val controller = makeController(controllerScope)
        controller.onForegroundChanged(true) // consume the baseline emission
        nowMs = 1_000
        // Set up an in-progress draft so clearDraft actually does work.
        composerFlow.value = composerFlow.value.copy(draftWorkdir = "/tmp/proj")
        chatFlow.value = chatFlow.value.copy(currentSessionId = null)

        controller.onForegroundChanged(false)

        // clearDraft inline path ran: workdir cleared, input text emptied.
        assertEquals(null, composerFlow.value.draftWorkdir)
        assertEquals("", composerFlow.value.inputText)
        // CancelSse effect fired.
        assertTrue("CancelSse emitted", collected.filterIsInstance<ControllerEffect.CancelSse>().isNotEmpty())
    }

    // ── <15s tier (throttle) ───────────────────────────────────────────────

    @Test
    fun `foreground return under 15s forces reconnect and suppresses SSE catch-up`() = withCollectedEffects { controllerScope, collected ->
        val controller = makeController(controllerScope)
        controller.onForegroundChanged(true) // baseline
        nowMs = 1_000
        controller.onForegroundChanged(false) // background @1000
        nowMs = 2_000 // 1s absence — well under 15s

        controller.onForegroundChanged(true)

        assertEquals("ForceReconnect always fires on foreground", 1, collected.filterIsInstance<ControllerEffect.ForceReconnect>().size)
        assertTrue("no GlobalColdStartRefresh for <15s", collected.filterIsInstance<ControllerEffect.GlobalColdStartRefresh>().isEmpty())
        // staleNotice is now an inline chatFlow write — assert no banner set.
        assertEquals(false, chatFlow.value.staleNotice)
        // Suppress flag should block the SSE reconnect's catch-up probe.
        controller.onServerConnected()
        assertTrue(
            "<15s tier suppresses the server.connected catch-up",
            collected.filterIsInstance<ControllerEffect.CatchUpAfterDisconnect>().isEmpty()
        )
    }

    // ── 15s–5min tier (catch-up) ───────────────────────────────────────────

    @Test
    fun `foreground return 15s-5min forces reconnect but lets SSE drive catch-up`() = withCollectedEffects { controllerScope, collected ->
        val controller = makeController(controllerScope)
        controller.onForegroundChanged(true) // baseline
        nowMs = 1_000
        controller.onForegroundChanged(false) // background @1000
        nowMs = 1_000 + 20_000 // 20s absence — medium tier

        controller.onForegroundChanged(true)

        assertEquals(1, collected.filterIsInstance<ControllerEffect.ForceReconnect>().size)
        assertTrue("no GlobalColdStartRefresh for medium tier", collected.filterIsInstance<ControllerEffect.GlobalColdStartRefresh>().isEmpty())
        assertEquals("no stale banner for medium tier", false, chatFlow.value.staleNotice)
        // Medium tier does NOT suppress → the SSE reconnect's server.connected
        // drives the catch-up (single entry point). Need a prior connect so
        // sseHasConnectedOnce is true, and a current session for the catch-up
        // to target.
        chatFlow.value = chatFlow.value.copy(currentSessionId = "session-M")
        controller.onServerConnected() // first connect: sets sseHasConnectedOnce
        controller.onServerConnected() // reconnect: should catch up
        assertEquals(1, collected.filterIsInstance<ControllerEffect.CatchUpAfterDisconnect>().size)
    }

    // ── >5min tier (cold-start + stale) ────────────────────────────────────

    @Test
    fun `foreground return over 5min triggers global cold-start and stale banner`() = withCollectedEffects { controllerScope, collected ->
        val controller = makeController(controllerScope)
        controller.onForegroundChanged(true) // baseline
        nowMs = 1_000
        controller.onForegroundChanged(false) // background @1000
        nowMs = 1_000 + (6 * 60 * 1000L) // 6min absence — long tier
        chatFlow.value = chatFlow.value.copy(currentSessionId = "session-X")

        controller.onForegroundChanged(true)

        assertEquals(1, collected.filterIsInstance<ControllerEffect.ForceReconnect>().size)
        val coldStarts = collected.filterIsInstance<ControllerEffect.GlobalColdStartRefresh>()
        assertEquals("global cold-start fires for >5min", 1, coldStarts.size)
        assertEquals("session-X", coldStarts.single().sessionId)
        // staleNotice is now an inline chatFlow write.
        assertEquals("stale banner fires for >5min", true, chatFlow.value.staleNotice)
        // Long tier suppresses the SSE reconnect catch-up (cold-start already loaded).
        controller.onServerConnected()
        assertTrue(
            ">5min tier suppresses the server.connected catch-up",
            collected.filterIsInstance<ControllerEffect.CatchUpAfterDisconnect>().isEmpty()
        )
    }

    // ── onServerConnected ──────────────────────────────────────────────────

    @Test
    fun `first server connected does not catch up (cold start has no local history)`() = withCollectedEffects { controllerScope, collected ->
        val controller = makeController(controllerScope)
        controller.onServerConnected()
        assertTrue(
            "no catch-up on the very first connect",
            collected.filterIsInstance<ControllerEffect.CatchUpAfterDisconnect>().isEmpty()
        )
    }

    @Test
    fun `second server connected with current session catches up`() = withCollectedEffects { controllerScope, collected ->
        val controller = makeController(controllerScope)
        chatFlow.value = chatFlow.value.copy(currentSessionId = "session-Y")
        controller.onServerConnected() // first → sets sseHasConnectedOnce
        controller.onServerConnected() // reconnect → catch up
        val catchUps = collected.filterIsInstance<ControllerEffect.CatchUpAfterDisconnect>()
        assertEquals(1, catchUps.size)
        assertEquals("session-Y", catchUps.single().sessionId)
    }

    @Test
    fun `server connected with no current session is a no-op catch-up`() = withCollectedEffects { controllerScope, collected ->
        val controller = makeController(controllerScope)
        // currentSessionId defaults to null.
        controller.onServerConnected() // first
        controller.onServerConnected() // reconnect, but no session
        assertTrue(collected.filterIsInstance<ControllerEffect.CatchUpAfterDisconnect>().isEmpty())
    }

    // ── onHostReconfigured ─────────────────────────────────────────────────

    @Test
    fun `onHostReconfigured resets so next connect is treated as cold start`() = withCollectedEffects { controllerScope, collected ->
        val controller = makeController(controllerScope)
        chatFlow.value = chatFlow.value.copy(currentSessionId = "session-Z")
        controller.onServerConnected() // first → sseHasConnectedOnce=true
        controller.onServerConnected() // reconnect → would catch up
        assertEquals(1, collected.filterIsInstance<ControllerEffect.CatchUpAfterDisconnect>().size)

        controller.onHostReconfigured() // host switch → reset
        controller.onServerConnected() // next connect treated as cold start
        assertEquals(
            "after host reconfigure the next connect skips catch-up",
            1,
            collected.filterIsInstance<ControllerEffect.CatchUpAfterDisconnect>().size
        )
    }

    // ── suppress flag carry-over (glm-1 🟠-1) ──────────────────────────────

    @Test
    fun `residual suppress flag is cleared at start of next foreground cycle`() = withCollectedEffects { controllerScope, collected ->
        val controller = makeController(controllerScope)
        controller.onForegroundChanged(true) // baseline
        nowMs = 1_000
        controller.onForegroundChanged(false) // background
        nowMs = 2_000
        controller.onForegroundChanged(true) // <15s → sets suppress=true
        // Without a server.connected to consume it, the flag lingers. The next
        // foreground cycle must clear it FIRST so it can't suppress a later tier.
        nowMs = 3_000
        controller.onForegroundChanged(false) // background
        nowMs = 3_000 + 20_000 // 20s — medium tier (would NOT suppress)
        chatFlow.value = chatFlow.value.copy(currentSessionId = "session-W")

        controller.onForegroundChanged(true)
        controller.onServerConnected() // first connect after this cycle
        controller.onServerConnected() // reconnect
        assertEquals(
            "lingering <15s suppress did not block the medium-tier catch-up",
            1,
            collected.filterIsInstance<ControllerEffect.CatchUpAfterDisconnect>().size
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Returns a no-op AppLifecycleMonitor stub backed by [foregroundFlow]. */
    private fun stubMonitor(): AppLifecycleMonitor = mockk(relaxed = true) {
        io.mockk.every { isInForeground } returns foregroundFlow
    }
}
