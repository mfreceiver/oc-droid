package com.yage.opencode_client.ui.controller

import com.yage.opencode_client.di.AppLifecycleMonitor
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-16 M1: independent unit test for [ForegroundCatchUpController].
 *
 * Zero reflection — the controller's state machine is driven entirely through
 * its public API ([onForegroundChanged] / [onServerConnected] /
 * [onHostReconfigured]) and asserted via the [RecordingCallbacks] spy. A
 * injected `clock` makes the three tier thresholds (<15s / 15s–5min / >5min)
 * deterministic without depending on wall-clock latency.
 */
class ForegroundCatchUpControllerTest {

    private lateinit var foregroundFlow: MutableStateFlow<Boolean>
    private lateinit var callbacks: RecordingCallbacks
    private var nowMs: Long = 0L

    /** Builds a controller wired to [foregroundFlow] + [callbacks] + [nowMs]. */
    private fun makeController(scope: CoroutineScope): ForegroundCatchUpController =
        ForegroundCatchUpController(
            appLifecycleMonitor = stubMonitor(),
            scope = scope,
            callbacks = callbacks,
            clock = { nowMs }
        )

    @Before
    fun setUp() {
        foregroundFlow = MutableStateFlow(true)
        callbacks = RecordingCallbacks()
        nowMs = 0L
    }

    // ── first-emission guard ───────────────────────────────────────────────

    @Test
    fun `first foreground emission is treated as baseline - no callbacks fire`() {
        val scope = TestScope()
        val controller = makeController(scope)
        // The init subscription delivers the current value (true); that is the
        // baseline, not a transition — nothing should happen.
        controller.onForegroundChanged(true)
        assertTrue("no forceReconnect on first emission", callbacks.forceReconnectCalls == 0)
        assertTrue("no clearDraft on first emission", callbacks.clearDraftCalls == 0)
    }

    // ── background tier ────────────────────────────────────────────────────

    @Test
    fun `background transition clears draft, cancels SSE, stamps backgroundedAtMs`() {
        val scope = TestScope()
        val controller = makeController(scope)
        controller.onForegroundChanged(true) // consume the baseline emission
        nowMs = 1_000
        callbacks.reset()

        controller.onForegroundChanged(false)

        assertEquals(1, callbacks.clearDraftCalls)
        assertEquals(1, callbacks.cancelSseCalls)
    }

    // ── <15s tier (throttle) ───────────────────────────────────────────────

    @Test
    fun `foreground return under 15s forces reconnect and suppresses SSE catch-up`() {
        val scope = TestScope()
        val controller = makeController(scope)
        controller.onForegroundChanged(true) // baseline
        nowMs = 1_000
        controller.onForegroundChanged(false) // background @1000
        nowMs = 2_000 // 1s absence — well under 15s
        callbacks.reset()

        controller.onForegroundChanged(true)

        assertEquals("forceReconnect always fires on foreground", 1, callbacks.forceReconnectCalls)
        assertEquals("no cold-start for <15s", 0, callbacks.globalColdStartRefreshCalls)
        assertEquals("no stale banner for <15s", 0, callbacks.setStaleNoticeCalls)
        // Suppress flag should block the SSE reconnect's catch-up probe.
        controller.onServerConnected()
        assertEquals(
            "<15s tier suppresses the server.connected catch-up",
            0,
            callbacks.catchUpAfterDisconnectCalls
        )
    }

    // ── 15s–5min tier (catch-up) ───────────────────────────────────────────

    @Test
    fun `foreground return 15s-5min forces reconnect but lets SSE drive catch-up`() {
        val scope = TestScope()
        val controller = makeController(scope)
        controller.onForegroundChanged(true) // baseline
        nowMs = 1_000
        controller.onForegroundChanged(false) // background @1000
        nowMs = 1_000 + 20_000 // 20s absence — medium tier
        callbacks.reset()

        controller.onForegroundChanged(true)

        assertEquals(1, callbacks.forceReconnectCalls)
        assertEquals("no cold-start for medium tier", 0, callbacks.globalColdStartRefreshCalls)
        assertEquals("no stale banner for medium tier", 0, callbacks.setStaleNoticeCalls)
        // Medium tier does NOT suppress → the SSE reconnect's server.connected
        // drives the catch-up (single entry point). Need a prior connect so
        // sseHasConnectedOnce is true, and a current session for the catch-up
        // to target.
        callbacks.currentSessionId = "session-M"
        controller.onServerConnected() // first connect: sets sseHasConnectedOnce
        callbacks.reset()
        controller.onServerConnected() // reconnect: should catch up
        assertEquals(1, callbacks.catchUpAfterDisconnectCalls)
    }

    // ── >5min tier (cold-start + stale) ────────────────────────────────────

    @Test
    fun `foreground return over 5min triggers global cold-start and stale banner`() {
        val scope = TestScope()
        val controller = makeController(scope)
        controller.onForegroundChanged(true) // baseline
        nowMs = 1_000
        controller.onForegroundChanged(false) // background @1000
        nowMs = 1_000 + (6 * 60 * 1000L) // 6min absence — long tier
        callbacks.currentSessionId = "session-X"
        callbacks.reset()

        controller.onForegroundChanged(true)

        assertEquals(1, callbacks.forceReconnectCalls)
        assertEquals("global cold-start fires for >5min", 1, callbacks.globalColdStartRefreshCalls)
        assertEquals("stale banner fires for >5min", 1, callbacks.setStaleNoticeCalls)
        assertEquals("session-X", callbacks.globalColdStartRefreshIds.single())
        // Long tier suppresses the SSE reconnect catch-up (cold-start already loaded).
        controller.onServerConnected()
        assertEquals(
            ">5min tier suppresses the server.connected catch-up",
            0,
            callbacks.catchUpAfterDisconnectCalls
        )
    }

    // ── onServerConnected ──────────────────────────────────────────────────

    @Test
    fun `first server connected does not catch up (cold start has no local history)`() {
        val scope = TestScope()
        val controller = makeController(scope)
        controller.onServerConnected()
        assertEquals(
            "no catch-up on the very first connect",
            0,
            callbacks.catchUpAfterDisconnectCalls
        )
    }

    @Test
    fun `second server connected with current session catches up`() {
        val scope = TestScope()
        val controller = makeController(scope)
        callbacks.currentSessionId = "session-Y"
        controller.onServerConnected() // first → sets sseHasConnectedOnce
        callbacks.reset()
        controller.onServerConnected() // reconnect → catch up
        assertEquals(1, callbacks.catchUpAfterDisconnectCalls)
        assertEquals("session-Y", callbacks.catchUpAfterDisconnectIds.single())
    }

    @Test
    fun `server connected with no current session is a no-op catch-up`() {
        val scope = TestScope()
        val controller = makeController(scope)
        callbacks.currentSessionId = null
        controller.onServerConnected() // first
        controller.onServerConnected() // reconnect, but no session
        assertEquals(0, callbacks.catchUpAfterDisconnectCalls)
    }

    // ── onHostReconfigured ─────────────────────────────────────────────────

    @Test
    fun `onHostReconfigured resets so next connect is treated as cold start`() {
        val scope = TestScope()
        val controller = makeController(scope)
        callbacks.currentSessionId = "session-Z"
        controller.onServerConnected() // first → sseHasConnectedOnce=true
        callbacks.reset()
        controller.onServerConnected() // reconnect → would catch up
        assertEquals(1, callbacks.catchUpAfterDisconnectCalls)

        callbacks.reset()
        controller.onHostReconfigured() // host switch → reset
        controller.onServerConnected() // next connect treated as cold start
        assertEquals(
            "after host reconfigure the next connect skips catch-up",
            0,
            callbacks.catchUpAfterDisconnectCalls
        )
    }

    // ── suppress flag carry-over (glm-1 🟠-1) ──────────────────────────────

    @Test
    fun `residual suppress flag is cleared at start of next foreground cycle`() {
        val scope = TestScope()
        val controller = makeController(scope)
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
        callbacks.currentSessionId = "session-W"
        callbacks.reset()

        controller.onForegroundChanged(true)
        controller.onServerConnected() // first connect after this cycle
        callbacks.reset()
        controller.onServerConnected() // reconnect
        assertEquals(
            "lingering <15s suppress did not block the medium-tier catch-up",
            1,
            callbacks.catchUpAfterDisconnectCalls
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Returns a no-op AppLifecycleMonitor stub backed by [foregroundFlow]. */
    private fun stubMonitor(): AppLifecycleMonitor = mockk(relaxed = true) {
        io.mockk.every { isInForeground } returns foregroundFlow
    }

    /** Records every callback invocation so tests can assert on side effects. */
    private class RecordingCallbacks : ForegroundCatchUpCallbacks {
        var forceReconnectCalls = 0
        var globalColdStartRefreshCalls = 0
        var setStaleNoticeCalls = 0
        var clearDraftCalls = 0
        var cancelSseCalls = 0
        var catchUpAfterDisconnectCalls = 0
        var currentSessionId: String? = null
        val globalColdStartRefreshIds = mutableListOf<String>()
        val catchUpAfterDisconnectIds = mutableListOf<String>()

        fun reset() {
            forceReconnectCalls = 0
            globalColdStartRefreshCalls = 0
            setStaleNoticeCalls = 0
            clearDraftCalls = 0
            cancelSseCalls = 0
            catchUpAfterDisconnectCalls = 0
            globalColdStartRefreshIds.clear()
            catchUpAfterDisconnectIds.clear()
        }

        override fun forceReconnect() { forceReconnectCalls++ }
        override fun globalColdStartRefresh(currentId: String) {
            globalColdStartRefreshCalls++
            globalColdStartRefreshIds.add(currentId)
        }
        override fun setStaleNotice() { setStaleNoticeCalls++ }
        override fun clearDraft() { clearDraftCalls++ }
        override fun cancelSse() { cancelSseCalls++ }
        override fun catchUpAfterDisconnect(sessionId: String) {
            catchUpAfterDisconnectCalls++
            catchUpAfterDisconnectIds.add(sessionId)
        }
        override fun currentSessionId(): String? = currentSessionId
    }
}
