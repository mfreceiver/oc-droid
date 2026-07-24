package cn.vectory.ocdroid.service

import android.content.Context
import androidx.core.content.ContextCompat
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class StreamingServiceLauncherTest {
    private val identity = ConnectionIdentity(3L, "group", "/work", "endpoint")
    private lateinit var context: Context
    private lateinit var foreground: MutableStateFlow<Boolean>
    private lateinit var monitor: AppLifecycleMonitor
    private lateinit var gate: StreamingOwnershipGate
    private lateinit var settingsManager: SettingsManager
    private lateinit var launcher: AndroidStreamingServiceLauncher

    @Before
    fun setUp() {
        mockkStatic(ContextCompat::class)
        context = mockk(relaxed = true)
        foreground = MutableStateFlow(true)
        monitor = mockk(relaxed = true)
        every { monitor.isInForeground } returns foreground
        gate = StreamingOwnershipGate()
        settingsManager = mockk(relaxed = true)
        launcher = AndroidStreamingServiceLauncher(
            context,
            monitor,
            mockk<StreamingLifecycleCoordinator>(relaxed = true),
            gate,
            OwnershipAckPolicy(),
            settingsManager,
        )
    }

    @After
    fun tearDown() = unmockkStatic(ContextCompat::class)

    @Test
    fun `background refusal never asks Android to start`() = runTest {
        foreground.value = false

        assertEquals(
            OwnershipStartResult.Refused(OwnershipRefusal.Background),
            launcher.ensureStarted(identity),
        )
        verify(exactly = 0) { ContextCompat.startForegroundService(any(), any()) }
    }

    @Test
    fun `platform rejection is an explicit refusal`() = runTest {
        val failure = SecurityException("denied")
        every { ContextCompat.startForegroundService(any(), any()) } throws failure

        assertEquals(
            OwnershipStartResult.Refused(OwnershipRefusal.PlatformRejected(failure)),
            launcher.ensureStarted(identity),
        )
    }

    @Test
    fun `Android start without Service acknowledgement times out`() = runTest {
        every { ContextCompat.startForegroundService(any(), any()) } returns Unit

        assertEquals(
            OwnershipStartResult.Refused(OwnershipRefusal.AckTimeout),
            launcher.ensureStarted(identity),
        )
    }

    @Test
    fun `sseDisabled true refuses SSE start with no FGS and no gate mutation`() = runTest {
        // §sse-disabled-debug-toggle: flag ON → REST-only. ensureStarted must
        // short-circuit BEFORE any eligibility check / prepareAttempt /
        // startForegroundService, returning a distinct SseDisabled refusal.
        every { settingsManager.sseDisabled } returns true

        val result = launcher.ensureStarted(identity)

        assertEquals(
            OwnershipStartResult.Refused(OwnershipRefusal.SseDisabled),
            result,
        )
        // NO foreground-service start was issued (no FGS bootstrap).
        verify(exactly = 0) { ContextCompat.startForegroundService(any(), any()) }
        // NO gate attempt was prepared (nothing to expire/roll back — the
        // launcher never touched the ownership gate).
        assertFalse("no pending attempt prepared", gate.isAttemptLive(1L))
    }

    @Test
    fun `sseDisabled false (default) keeps normal SSE start path`() = runTest {
        // §sse-disabled-debug-toggle: flag OFF (production default) → the gate
        // is transparent; the normal AckTimeout path is unchanged (REST path /
        // fix-1 bootstrap behavior untouched).
        every { settingsManager.sseDisabled } returns false
        every { ContextCompat.startForegroundService(any(), any()) } returns Unit

        assertEquals(
            OwnershipStartResult.Refused(OwnershipRefusal.AckTimeout),
            launcher.ensureStarted(identity),
        )
        verify(exactly = 1) { ContextCompat.startForegroundService(any(), any()) }
    }

    @Test
    fun `only exact matching registered identity is accepted`() = runTest {
        // D4-B B1: two-stage ownership. A Ready owner short-circuits the launcher.
        gate.registerStarting(identity, disconnectAndJoin = { }, abortStartup = { })
        gate.markReady(identity)
        assertEquals(OwnershipStartResult.Ready(identity), launcher.ensureStarted(identity))

        // A different-identity request is refused (AlreadyOwned).
        val other = identity.copy(normalizedWorkdir = "/other")
        assertEquals(
            OwnershipStartResult.Refused(OwnershipRefusal.AlreadyOwned(identity)),
            launcher.ensureStarted(other),
        )
        verify(exactly = 0) { ContextCompat.startForegroundService(any(), any()) }
    }
}
