package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.repository.http.AuthFailureReason
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.SharedStateStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * rev-ogpt B (Disconnected 周期重探): unit test for [ConnectionReprobeController].
 *
 * The controller runs on its own [TestScope] with [StandardTestDispatcher].
 * Virtual time is driven via [TestScope.testScheduler]. The probe callback
 * immediately settles from [settleResults] — the [kotlinx.coroutines.CompletableDeferred]
 * is resolved synchronously inside the virtual-time dispatch.
 *
 * **Zero-regression**: the [ConnectionCoordinator] zero-regression gate ensures
 * existing [ConnectionCoordinatorTest] stays green.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionReprobeControllerTest {

    private lateinit var store: SharedStateStore
    private lateinit var foreground: MutableStateFlow<Boolean>
    private var epoch: Long = 1L
    /** Pre-determined settle results consumed FIFO by probe. */
    private lateinit var settleResults: MutableList<Boolean>
    /** Number of probe invocations. */
    private var probeCount: Int = 0
    private lateinit var scope: TestScope

    @Before
    fun setUp() {
        store = SharedStateStore()
        foreground = MutableStateFlow(true)
        epoch = 1L
        settleResults = mutableListOf()
        probeCount = 0
        scope = TestScope(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun advance(ms: Long = 0) {
        if (ms > 0) scope.testScheduler.advanceTimeBy(ms)
        scope.testScheduler.runCurrent()
    }

    private fun ctrl() {
        val results = settleResults
        ConnectionReprobeController(
            scope = scope,
            connectionFlow = store.slices.connection,
            isInForeground = foreground,
            currentEpoch = { epoch },
            probe = { cb ->
                probeCount++
                // Immediately settle from results. If empty, the deferred
                // hangs — caller must provide enough entries.
                if (results.isNotEmpty()) cb(results.removeAt(0))
            },
        ).start()
        advance()
    }

    // ── Backoff timing (probe immediately settles inline) ────────────────────

    @Test
    fun `backoff progression`() {
        settleResults.addAll(listOf(false, false, false, false, true))
        store.slices.mutateConnection { it.copy(connectionPhase = ConnectionPhase.Disconnected) }
        ctrl()

        advance(5_000); assertEquals(1, probeCount)
        advance(15_000); assertEquals(2, probeCount)
        advance(30_000); assertEquals(3, probeCount)
        advance(60_000); assertEquals(4, probeCount)
        advance(120_000); assertEquals(5, probeCount) // success → episode exits

        // Write Connected so supervisor doesn't launch new episode
        store.slices.mutateConnection { it.copy(connectionPhase = ConnectionPhase.Connected) }
        advance()
        val after = probeCount
        advance(120_000)
        assertEquals("no more probes", after, probeCount)
    }

    @Test
    fun `backoff ends at 120s cap`() {
        settleResults.addAll(listOf(false, false, false, false, false, false, false))
        store.slices.mutateConnection { it.copy(connectionPhase = ConnectionPhase.Disconnected) }
        ctrl()

        advance(5_000); assertEquals(1, probeCount)
        advance(15_000); assertEquals(2, probeCount)
        advance(30_000); assertEquals(3, probeCount)
        advance(60_000); assertEquals(4, probeCount)
        advance(120_000); assertEquals(5, probeCount) // capped at 120s
        advance(120_000); assertEquals(6, probeCount) // stays at 120s
        advance(120_000); assertEquals(7, probeCount) // stays at 120s
    }

    @Test
    fun `auth cancels episode`() {
        settleResults.add(false)
        store.slices.mutateConnection { it.copy(connectionPhase = ConnectionPhase.Disconnected) }
        ctrl()
        advance(5_000); assertEquals(1, probeCount)

        store.slices.mutateConnection {
            it.copy(authFailureReason = AuthFailureReason.HttpAuth(code = 401, message = null))
        }
        advance()
        val after = probeCount
        advance(600_000)
        assertEquals(after, probeCount)
    }

    @Test
    fun `auth before start`() {
        store.slices.mutateConnection {
            it.copy(connectionPhase = ConnectionPhase.Disconnected,
                authFailureReason = AuthFailureReason.HttpAuth(code = 401, message = null))
        }
        ctrl(); advance(600_000)
        assertEquals(0, probeCount)
    }

    @Test
    fun `background cancels`() {
        settleResults.add(false)
        store.slices.mutateConnection { it.copy(connectionPhase = ConnectionPhase.Disconnected) }
        ctrl()
        advance(5_000); assertEquals(1, probeCount)

        foreground.value = false; advance()
        val bg = probeCount
        advance(30_000)
        assertEquals(bg, probeCount)

        settleResults.add(true)
        foreground.value = true; advance()
        advance(5_000); assertEquals(bg + 1, probeCount)

        store.slices.mutateConnection { it.copy(connectionPhase = ConnectionPhase.Connected) }
        advance()
    }

    @Test
    fun `isConnecting skip`() {
        settleResults.add(false)
        store.slices.mutateConnection { it.copy(connectionPhase = ConnectionPhase.Disconnected) }
        ctrl()
        advance(5_000); assertEquals(1, probeCount)

        store.slices.mutateConnection { it.copy(isConnecting = true) }
        advance(15_000); assertEquals(1, probeCount)

        settleResults.add(true)
        store.slices.mutateConnection { it.copy(isConnecting = false) }
        advance(15_000); assertEquals(2, probeCount)

        store.slices.mutateConnection { it.copy(connectionPhase = ConnectionPhase.Connected) }
        advance()
    }

    @Test
    fun `epoch change terminates`() {
        settleResults.add(false)
        store.slices.mutateConnection { it.copy(connectionPhase = ConnectionPhase.Disconnected) }
        ctrl()
        advance(5_000); assertEquals(1, probeCount)

        epoch = 2L
        advance(15_000); assertEquals(1, probeCount)
    }

    @Test
    fun `epoch change with fg toggle restarts`() {
        settleResults.add(false)
        store.slices.mutateConnection { it.copy(connectionPhase = ConnectionPhase.Disconnected) }
        ctrl()
        advance(5_000); assertEquals(1, probeCount)

        epoch = 2L; foreground.value = false; advance()
        settleResults.add(true)
        foreground.value = true; advance()

        advance(5_000); assertEquals(2, probeCount)

        store.slices.mutateConnection { it.copy(connectionPhase = ConnectionPhase.Connected) }
        advance()
    }

    @Test
    fun `mtls error prevents`() {
        store.slices.mutateConnection {
            it.copy(connectionPhase = ConnectionPhase.Disconnected, mtlsDegradedError = "err")
        }
        ctrl(); advance(600_000)
        assertEquals(0, probeCount)
    }

    @Test
    fun `slimapi version prevents`() {
        store.slices.mutateConnection {
            it.copy(connectionPhase = ConnectionPhase.Disconnected, slimapiVersionIncompatible = Triple(5, 3, 7))
        }
        ctrl(); advance(600_000)
        assertEquals(0, probeCount)
    }

    @Test
    fun `slimapi version cancels episode mid-flight`() {
        settleResults.add(false)
        store.slices.mutateConnection { it.copy(connectionPhase = ConnectionPhase.Disconnected) }
        ctrl()
        advance(5_000); assertEquals(1, probeCount)

        store.slices.mutateConnection {
            it.copy(slimapiVersionIncompatible = Triple(5, 3, 7))
        }
        advance()
        val after = probeCount
        advance(600_000)
        assertEquals("version-incompatible signal should stop episode", after, probeCount)
    }

    @Test
    fun `no episode when connected`() {
        ctrl(); advance(600_000)
        assertEquals(0, probeCount)
    }

    @Test
    fun `SseBootstrapFailed triggers reprobe`() {
        store.slices.mutateConnection { it.copy(connectionPhase = ConnectionPhase.SseBootstrapFailed) }
        settleResults.add(false)
        ctrl()
        advance(5_000); assertEquals(1, probeCount)
    }

    @Test
    fun `SseDisabled does not trigger`() {
        store.slices.mutateConnection { it.copy(connectionPhase = ConnectionPhase.SseDisabled) }
        ctrl(); advance(600_000)
        assertEquals(0, probeCount)
    }

    @Test
    fun `SseBootstrapFailed recovery`() {
        store.slices.mutateConnection { it.copy(connectionPhase = ConnectionPhase.SseBootstrapFailed) }
        settleResults.add(true)
        ctrl()
        advance(5_000); assertEquals(1, probeCount)

        store.slices.mutateConnection { it.copy(connectionPhase = ConnectionPhase.Connected) }
        advance()
        val after = probeCount
        advance(120_000)
        assertEquals(after, probeCount)
    }

    @Test
    fun `DELAYS array`() {
        assertEquals(5, ConnectionReprobeController.DELAYS.size)
        assertEquals(5_000L, ConnectionReprobeController.DELAYS[0])
        assertEquals(15_000L, ConnectionReprobeController.DELAYS[1])
        assertEquals(30_000L, ConnectionReprobeController.DELAYS[2])
        assertEquals(60_000L, ConnectionReprobeController.DELAYS[3])
        assertEquals(120_000L, ConnectionReprobeController.DELAYS[4])
    }
}
