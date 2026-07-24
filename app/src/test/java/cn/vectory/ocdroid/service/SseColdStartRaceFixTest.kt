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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SSE cold-start race fix — regression matrix for the two complementary root
 * causes that killed cold-start SSE bootstrapping:
 *  - **A. main-thread starvation** of the Stage-1 ownership registration
 *    (fixed in `SessionStreamingService.onStartCommand` — Dispatchers.Default);
 *  - **B. double-fire race** in `StreamingOwnershipGate.prepareAttempt` Case 3
 *    (fixed — same-identity JOIN + different-identity REFUSE) and the
 *    attemptId-scoped teardown guard
 *    (`StreamingOwnershipGate.hasLiveAttemptOtherThan` +
 *    `SessionStreamingService.rollbackBootstrap` Timeout branch).
 *
 * Tests 1-4 are gate/launcher-level (pure JVM). Test 5 (coordinator-level:
 * BootstrapFailure teardown coexists with the L3 poller + allows re-arm) lives
 * in [OwnershipAndReconfigureIntegrationTest] which already owns the
 * coordinator fixture.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SseColdStartRaceFixTest {

    private val identity = ConnectionIdentity(3L, "group", "/work", "endpoint")
    private val otherIdentity = ConnectionIdentity(4L, "group", "/other", "endpoint")

    // ── launcher fixture (used by test 1) ──────────────────────────────────
    private lateinit var context: Context
    private lateinit var foreground: MutableStateFlow<Boolean>
    private lateinit var monitor: AppLifecycleMonitor
    private lateinit var gate: StreamingOwnershipGate
    private lateinit var launcher: AndroidStreamingServiceLauncher

    @Before
    fun setUp() {
        mockkStatic(ContextCompat::class)
        context = mockk(relaxed = true)
        foreground = MutableStateFlow(true)
        monitor = mockk(relaxed = true)
        every { monitor.isInForeground } returns foreground
        gate = StreamingOwnershipGate()
        launcher = AndroidStreamingServiceLauncher(
            context,
            monitor,
            mockk<StreamingLifecycleCoordinator>(relaxed = true),
            gate,
            OwnershipAckPolicy(),
            mockk<SettingsManager>(relaxed = true),
        )
    }

    @After
    fun tearDown() = unmockkStatic(ContextCompat::class)

    // ── 1. same-identity double ensureStarted → single attempt/FGS ─────────

    @Test
    fun `1 - same-identity double ensureStarted collapses to single attemptId, single FGS, shared Ready`() = runTest {
        every { ContextCompat.startForegroundService(any(), any()) } returns Unit

        // Two concurrent ensureStarted for the SAME identity (e.g. foreground-
        // return + health-probe overlap — the exact cold-start double-fire).
        val r1 = async { launcher.ensureStarted(identity) }
        val r2 = async { launcher.ensureStarted(identity) }
        runCurrent()

        // JOIN ⇒ only the FIRST issued startForegroundService (the second got
        // launchRequired=false). This is the regression for the dual-fire that
        // overwrote the single pending slot and Expire'd attempt 1.
        verify(exactly = 1) { ContextCompat.startForegroundService(any(), any()) }

        // Fresh gate ⇒ attemptIdCounter starts at 0, first ++prefix ⇒ 1.
        // Self-verify so the derivation is not a magic number.
        assertTrue("fresh gate's first attempt is id=1", gate.isAttemptLive(1L))

        // Simulate the Service: a SINGLE registerStarting (attemptId=1) accepts
        // for BOTH callers (shared starting deferred), then markReady completes
        // the shared terminal deferred for both.
        assertEquals(
            RegisterStartingOutcome.Accepted,
            gate.registerStarting(identity, attemptId = 1L, disconnectAndJoin = { }, abortStartup = { }),
        )
        runCurrent()
        gate.markReady(identity)
        runCurrent()

        assertEquals(OwnershipStartResult.Ready(identity), r1.await())
        assertEquals(OwnershipStartResult.Ready(identity), r2.await())
    }

    // ── gate-level JOIN mechanics (1b) ─────────────────────────────────────

    @Test
    fun `1b - same-identity double prepareAttempt shares attemptId and deferreds`() {
        val g = StreamingOwnershipGate()
        val a1 = g.prepareAttempt(identity)
        val a2 = g.prepareAttempt(identity)

        assertEquals("JOIN ⇒ same attemptId", a1.attemptId, a2.attemptId)
        assertTrue("first requires launch", a1.launchRequired)
        assertFalse("second must NOT launch (JOIN)", a2.launchRequired)
        assertSame("shared starting deferred", a1.starting, a2.starting)
        assertSame("shared terminal deferred", a1.terminal, a2.terminal)
    }

    // ── 2. expired attempt must not teardown when a newer attempt is live ───

    @Test
    fun `2 - expired attempt1 with attempt2 Starting ⇒ guard skips service teardown`() = runTest {
        val g = StreamingOwnershipGate()
        // attempt 1 prepared, then the launcher gave up (expireAttempt clears
        // the pending). Simulates a late/expired cold-start attempt.
        val attempt1 = g.prepareAttempt(identity)
        g.expireAttempt(attempt1.attemptId, OwnershipRefusal.AckTimeout)

        // A subsequent recovery (foreground-return / probe) prepared + the
        // Service registered a NEWER attempt (attempt 2) that now owns the gate.
        val attempt2 = g.prepareAttempt(identity)
        assertEquals(
            RegisterStartingOutcome.Accepted,
            g.registerStarting(identity, attempt2.attemptId, disconnectAndJoin = { }, abortStartup = { }),
        )
        assertNotEquals("distinct attemptIds", attempt1.attemptId, attempt2.attemptId)

        // The teardown-scope guard: attempt1's abort must detect that a NEWER
        // attempt (2) holds the gate ⇒ in the Service this means "cancel this
        // bootstrap job ONLY, do NOT teardown the shared Service / StopSelf".
        assertTrue(
            "newer attempt 2 holds the gate ⇒ attempt1 abort must skip teardown",
            g.hasLiveAttemptOtherThan(attempt1.attemptId),
        )
    }

    // ── 3. unique attempt AckTimeout still tears down + can re-pull ─────────

    @Test
    fun `3 - unique attempt AckTimeout ⇒ guard allows teardown and a later probe re-pulls`() = runTest {
        val g = StreamingOwnershipGate()
        val attempt1 = g.prepareAttempt(identity)
        g.expireAttempt(attempt1.attemptId, OwnershipRefusal.AckTimeout)

        // No successor ⇒ guard allows the BootstrapFailure teardown.
        assertFalse(
            "no newer attempt ⇒ teardown is allowed",
            g.hasLiveAttemptOtherThan(attempt1.attemptId),
        )

        // Recovery: a subsequent probe/foreground-return re-pulls a fresh
        // attempt (the gate is clear, not stranded).
        val attempt2 = g.prepareAttempt(identity)
        assertNotEquals("fresh attemptId allocated on re-pull", attempt1.attemptId, attempt2.attemptId)
        assertTrue("re-pull requires a fresh launch", attempt2.launchRequired)
    }

    // ── 4. different-identity concurrent prepare ⇒ no hanging deferred ──────

    @Test
    fun `4 - different-identity pending refuses the newcomer without stranding the in-flight waiter`() = runTest {
        val g = StreamingOwnershipGate()
        // attempt A prepared (pending, pre-Stage-1).
        val attemptA = g.prepareAttempt(identity)
        assertTrue("A requires launch", attemptA.launchRequired)

        // B (different identity) arrives while A's attempt is still pending.
        val attemptB = g.prepareAttempt(otherIdentity)
        assertFalse("B must NOT launch (refused, not overwrite)", attemptB.launchRequired)

        // B's deferreds resolve IMMEDIATELY with AlreadyOwned(A) — no hang.
        assertEquals(
            StartingAck.Refused(OwnershipRefusal.AlreadyOwned(identity)),
            attemptB.starting.await(),
        )
        assertEquals(
            OwnershipStartResult.Refused(OwnershipRefusal.AlreadyOwned(identity)),
            attemptB.terminal.await(),
        )

        // A's pending attempt is intact (NOT overwritten/stranded).
        assertTrue("A's attempt still live (not clobbered)", g.isAttemptLive(attemptA.attemptId))
    }
}
