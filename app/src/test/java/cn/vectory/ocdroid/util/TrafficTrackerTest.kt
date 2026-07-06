package cn.vectory.ocdroid.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R18 Phase 5++ coverage: [TrafficTracker] — best-effort byte counters +
 * batched persistence. Coverage gap before this file: 0/1 class, 0/7 methods,
 * 0/23 lines, 0/90 instructions.
 *
 * The tracker reads its initial totals from [SettingsManager] and writes them
 * back at most once per [PERSIST_INTERVAL_MS] (2s). The tests inject a mock
 * SettingsManager so persistence calls are observable without dragging
 * EncryptedSharedPreferences in.
 *
 * NOTE: [tryPersist] reads `System.currentTimeMillis()` directly; we cannot
 * deterministically drive the 2s throttle without Robolectric's time machinery
 * (the field is private). The tests below cover:
 *  - initial load from SettingsManager (both directions)
 *  - add() with positive, zero, and negative values (the OkHttp -1 path)
 *  - reset() zeroes and persists immediately
 *  - the public surface (no-op adds do not flip the dirty flag, so they skip
 *    tryPersist via the early `if (changed)` guard)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrafficTrackerTest {

    private lateinit var settings: SettingsManager
    private lateinit var tracker: TrafficTracker

    @Before
    fun setUp() {
        // §note: see TrafficLoggerTest — Robolectric boots the real OpenCodeApp
        // which needs the AndroidKeyStore stub.
        FakeAndroidKeyStoreProvider.install()
        settings = mockk(relaxed = true)
        every { settings.trafficBytesSent } returns 0L
        every { settings.trafficBytesReceived } returns 0L
        tracker = TrafficTracker(settings)
    }

    @Test
    fun `initial totals come from SettingsManager`() {
        every { settings.trafficBytesSent } returns 1234L
        every { settings.trafficBytesReceived } returns 5678L
        val t = TrafficTracker(settings)

        assertEquals(1234L, t.totalBytesSent)
        assertEquals(5678L, t.totalBytesReceived)
    }

    @Test
    fun `add with positive sent and received accumulates and flips dirty`() {
        tracker.add(sent = 100L, received = 200L)

        assertEquals(100L, tracker.totalBytesSent)
        assertEquals(200L, tracker.totalBytesReceived)
    }

    @Test
    fun `add accumulates across multiple calls`() {
        tracker.add(sent = 100L, received = 200L)
        tracker.add(sent = 50L, received = 70L)

        assertEquals(150L, tracker.totalBytesSent)
        assertEquals(270L, tracker.totalBytesReceived)
    }

    @Test
    fun `add skips negative sent (OkHttp unknown content length)`() {
        tracker.add(sent = -1L, received = 200L)

        assertEquals(0L, tracker.totalBytesSent)
        assertEquals(200L, tracker.totalBytesReceived)
    }

    @Test
    fun `add skips negative received`() {
        tracker.add(sent = 100L, received = -1L)

        assertEquals(100L, tracker.totalBytesSent)
        assertEquals(0L, tracker.totalBytesReceived)
    }

    @Test
    fun `add with both negative is a complete no-op`() {
        tracker.add(sent = -1L, received = -1L)

        assertEquals(0L, tracker.totalBytesSent)
        assertEquals(0L, tracker.totalBytesReceived)
    }

    @Test
    fun `add with zero values is a no-op (does not flip dirty)`() {
        tracker.add(sent = 0L, received = 0L)

        assertEquals(0L, tracker.totalBytesSent)
        assertEquals(0L, tracker.totalBytesReceived)
        // No persist attempted (changed=false → tryPersist never reached).
        verify(exactly = 0) { settings.trafficBytesSent = any() }
        verify(exactly = 0) { settings.trafficBytesReceived = any() }
    }

    @Test
    fun `reset zeroes both counters and persists immediately`() {
        tracker.add(sent = 100L, received = 200L)
        assertEquals(100L, tracker.totalBytesSent)

        tracker.reset()

        assertEquals(0L, tracker.totalBytesSent)
        assertEquals(0L, tracker.totalBytesReceived)
        // doPersist writes both back to settings.
        verify { settings.trafficBytesSent = 0L }
        verify { settings.trafficBytesReceived = 0L }
    }

    @Test
    fun `reset persists even when no add has run`() {
        tracker.reset()
        assertEquals(0L, tracker.totalBytesSent)
        verify { settings.trafficBytesSent = 0L }
        verify { settings.trafficBytesReceived = 0L }
    }
}
