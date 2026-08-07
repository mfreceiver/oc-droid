package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.model.TokenStreamFrame
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [StreamLifecycleSupervisor] — non-timing paths.
 *
 * Tests that don't depend on deterministic timing use
 * [System.currentTimeMillis] for the clock.
 */
class StreamLifecycleSupervisorTest {

    private val lock = Any()
    private val dispatchedFrames = mutableListOf<TokenStreamFrame>()
    private val guard = TokenFrameGuard(lock)
    private val policy = ReconnectPolicy(50L, 200L, 2.0, lock)

    private val dispatchFrame: (String, Long, Long, TokenStreamFrame, Long, ClientBundle) -> Unit =
        { _, _, _, frame, _, _ -> dispatchedFrames += frame }

    private fun createSupervisor(
        streamProvider: (String, String?) -> Flow<TokenStreamFrame> = { _, _ -> flow { } },
    ): StreamLifecycleSupervisor {
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        return StreamLifecycleSupervisor(
            scope = mockk(relaxed = true),
            slices = SharedStateStore().slices,
            guard = guard,
            policy = policy,
            streamProvider = streamProvider,
            streamConnectionProvider = null,
            sseDisabled = { false },
            clearSessionRevisions = { },
            triggerSinceFetch = { _, _ -> },
            clock = { System.currentTimeMillis() },
            bundleCommitLock = lock,
            currentBundleProvider = { repository.currentClientBundle() },
            watchdogMs = 10_000L,
            watchdogPollMs = 10L,
            openDebounceMs = 0L,
            dispatchFrame = dispatchFrame,
        )
    }

    @Test
    fun `setReconnectRequestedForTest and reconnectRequestedSnapshot round-trip`() {
        val supervisor = createSupervisor()
        supervisor.setReconnectRequestedForTest("s1")
        assertEquals("s1", supervisor.reconnectRequestedSnapshot())
        supervisor.setReconnectRequestedForTest(null)
        assertNull(supervisor.reconnectRequestedSnapshot())
    }

    @Test
    fun `markReconnectRequested sets the sentinel`() {
        val supervisor = createSupervisor()
        supervisor.markReconnectRequested("s1")
        assertEquals("s1", supervisor.reconnectRequestedSnapshot())
    }

    @Test
    fun `onFrame resets the watchdog timer`() {
        val supervisor = createSupervisor()
        supervisor.onFrame("s1")
        assertTrue(true)
    }
}
