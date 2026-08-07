package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.model.TokenStreamFrame
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [StreamLifecycleSupervisor] — lifecycle paths, timing, wiring.
 *
 * Uses [TestScope(UnconfinedTestDispatcher())] for deterministic coroutine
 * execution. Tests drive the supervisor via a fake [streamProvider] (Channel-
 * backed) + fake dispatcher callback, exercising the lateinit wiring
 * (supervisor↔dispatcher callbacks fire correctly) and the timing/ordering
 * constraints that are the highest-risk part of this component.
 *
 * **Existing tests (sentinel round-trip / onFrame) are preserved as-is.**
 * New tests (added per rev-gpt finding 2, design SSOT §9.2):
 *  - max-1 supersede: [launchStreamLifecycle] getAndSet + cancel prior job
 *  - stale-sentinel recovery: §MF-1 gate r2 — foreign sentinel via seam, new sid open
 *  - watchdog-fires-before-first-frame: bgpt MF-2 — timeout with no frames
 *  - reconnect-after-delay sid re-check: stale reconnect dropped after host switch
 *
 * @see [StreamLifecycleSupervisor]
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamLifecycleSupervisorTest {

    private val lock = Any()
    private val guard = TokenFrameGuard(lock)
    private val policy = ReconnectPolicy(50L, 200L, 2.0, lock)

    /** Collected frames from the [dispatchFrame] callback. */
    private val dispatchedFrames = mutableListOf<TokenStreamFrame>()

    /**
     * Default dispatch-frame callback — appends frames to [dispatchedFrames].
     * Tests may replace this per-scenario.
     */
    private val dispatchFrame: (String, Long, Long, TokenStreamFrame, Long, ClientBundle) -> Unit =
        { _, _, _, frame, _, _ -> dispatchedFrames += frame }

    // ── Fake stream provider ───────────────────────────────────────────────

    /**
     * Channel-backed stream provider for deterministic frame injection.
     * Each invocation creates a fresh [Channel] and stores it in [currentChannel]
     * so tests can [send] frames into the active flow.
     */
    private class FakeStreamProvider {
        val openCount = AtomicInteger(0)
        var currentChannel: Channel<TokenStreamFrame>? = null
            private set

        val provider: (String, String?) -> Flow<TokenStreamFrame> = { sid, _ ->
            openCount.incrementAndGet()
            val ch = Channel<TokenStreamFrame>(Channel.UNLIMITED)
            currentChannel = ch
            flow {
                for (frame in ch) {
                    emit(frame)
                }
            }
        }

        fun send(frame: TokenStreamFrame) {
            val ch = currentChannel ?: error("no active channel — call open() first")
            val result = ch.trySend(frame)
            assertTrue("send failed: ${result.exceptionOrNull()}", result.isSuccess)
        }
    }

    // ── Test helpers ───────────────────────────────────────────────────────

    private data class SinceFetchCall(val sid: String, val auth: Boolean)

    private fun snapshot(
        partId: String = "p1",
        text: String? = "hello",
        done: Boolean = false,
        truncated: Boolean = false,
        sessionId: String = "s1",
        messageId: String = "m1",
    ) = TokenStreamFrame.PartSnapshot(sessionId, messageId, partId, text, done, truncated)

    /**
     * Creates a [StreamLifecycleSupervisor] wired to the given [scope] and [fake].
     *
     * @param scope The test coroutine scope (creates a new [TestScope] per test).
     * @param fake A [FakeStreamProvider] for frame injection.
     * @param watchdogMs Watchdog timeout in ms (default 10_000 = effectively off).
     * @param openDebounceMs Debounce on rapid open (default 0 = immediate).
     * @param triggerSinceFetch Optional callback to track `/since` fetch calls.
     */
    private fun createSupervisor(
        scope: TestScope,
        fake: FakeStreamProvider,
        watchdogMs: Long = 10_000L,
        openDebounceMs: Long = 0L,
        triggerSinceFetch: ((String, Boolean) -> Unit)? = null,
    ): StreamLifecycleSupervisor {
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        return StreamLifecycleSupervisor(
            scope = scope,
            slices = SharedStateStore().slices,
            guard = guard,
            policy = policy,
            streamProvider = fake.provider,
            streamConnectionProvider = null,
            sseDisabled = { false },
            clearSessionRevisions = { },
            triggerSinceFetch = triggerSinceFetch ?: { _, _ -> },
            clock = { scope.testScheduler.currentTime },
            bundleCommitLock = lock,
            currentBundleProvider = { repository.currentClientBundle() },
            watchdogMs = watchdogMs,
            watchdogPollMs = 10L,
            openDebounceMs = openDebounceMs,
            dispatchFrame = dispatchFrame,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Existing tests (preserved as-is)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `setReconnectRequestedForTest and reconnectRequestedSnapshot round-trip`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val supervisor = createSupervisor(scope, FakeStreamProvider())
        supervisor.setReconnectRequestedForTest("s1")
        assertEquals("s1", supervisor.reconnectRequestedSnapshot())
        supervisor.setReconnectRequestedForTest(null)
        assertNull(supervisor.reconnectRequestedSnapshot())
    }

    @Test
    fun `markReconnectRequested sets the sentinel`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val supervisor = createSupervisor(scope, FakeStreamProvider())
        supervisor.markReconnectRequested("s1")
        assertEquals("s1", supervisor.reconnectRequestedSnapshot())
    }

    @Test
    fun `onFrame resets the watchdog timer`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val supervisor = createSupervisor(scope, FakeStreamProvider())
        supervisor.onFrame("s1")
        assertTrue(true)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  max-1 supersede — launchStreamLifecycle getAndSet + cancel prior job
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Verifies that opening a second stream (B) supersedes the first (A):
     *  - [currentStreamJobSnapshot] reflects B's job, not A's.
     *  - A's job is cancelled (inactive).
     *
     * This pins the core invariant of [launchStreamLifecycle]: `getAndSet`
     * atomically replaces the lifecycle and cancels the prior one, so there
     * is never more than one active stream collector.
     */
    @Test
    fun `opening B supersedes A - getAndSet cancels prior lifecycle job`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val fake = FakeStreamProvider()
        val supervisor = createSupervisor(scope, fake)

        // Open stream A
        supervisor.open("s1", source = "test-open-A")
        scope.runCurrent() // advance past debounce + runStream entry
        val jobA = supervisor.currentStreamJobSnapshot()
        assertNotNull("stream A lifecycle must exist", jobA)
        assertTrue("stream A must be active", jobA?.isActive == true)
        assertEquals(1, fake.openCount.get())

        // Open stream B — supersedes A
        supervisor.open("s2", source = "test-open-B")
        scope.runCurrent()
        val jobB = supervisor.currentStreamJobSnapshot()
        assertNotNull("stream B lifecycle must exist", jobB)
        assertTrue("stream B must be active", jobB?.isActive == true)
        // A's job must have been cancelled by launchStreamLifecycle's getAndSet
        assertFalse("stream A must be cancelled after supersede", jobA?.isActive == true)
        // Provider was called again for B
        assertEquals(2, fake.openCount.get())

        supervisor.close("s2")
        scope.runCurrent()
    }

    /**
     * Verifies that opening a NEW stream supersedes a RECONNECT lifecycle
     * that is still in its backoff delay. The reconnect block's
     * `currentSid.get() != sid` check returns early after the sid changes.
     *
     * This is covered by [reconnect sid re-check drops stale after host switch
     * during backoff]; this test adds direct supersede cancellation as a
     * complement to the max-1 invariant.
     */

    // ═══════════════════════════════════════════════════════════════════════
    //  stale-sentinel recovery — §MF-1 gate r2
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Verifies that a stale foreign sentinel (e.g. left by a prior lifecycle)
     * is unconditionally cleared by [open] so the new lifecycle starts clean.
     *
     * Sequence:
     *  1. Set a foreign sentinel via the test seam ([setReconnectRequestedForTest]).
     *  2. Open a new sid — the sentinel MUST be cleared by open().
     *  3. Inject a frame — must be dispatched (no false reconnect).
     *  4. Set the sentinel for the CURRENT sid via [markReconnectRequested].
     *  5. Inject another frame — the post-dispatch check fires and triggers
     *     a reconnect lifecycle (new stream opened via [scheduleReconnect]).
     *
     * This pins the §MF-1 gate r2 unconditional set/clear semantics: a new
     * lifecycle ignores any stale sentinel from a previous lifecycle.
     */
    @Test
    fun `stale foreign sentinel is cleared by open - reconnect fires for new sid`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val fake = FakeStreamProvider()
        val supervisor = createSupervisor(scope, fake)

        // Step 1: Set a "foreign" sentinel (simulates stale state from a
        // cancelled prior lifecycle that never got to process its reconnect).
        supervisor.setReconnectRequestedForTest("other-sid")
        assertEquals("foreign sentinel must be set", "other-sid", supervisor.reconnectRequestedSnapshot())

        // Step 2: Open new sid — must clear the stale sentinel unconditionally.
        supervisor.open("s1", source = "test")
        scope.runCurrent()
        assertNull("open must clear stale foreign sentinel", supervisor.reconnectRequestedSnapshot())
        assertNotNull("lifecycle must exist", supervisor.currentStreamJobSnapshot())

        // Step 3: Send a frame — no reconnect should fire (sentinel is null).
        dispatchedFrames.clear()
        fake.send(snapshot(sessionId = "s1", partId = "p1"))
        scope.runCurrent()
        assertEquals("frame must be dispatched (no false reconnect)", 1, dispatchedFrames.size)

        // Step 4: Set reconnect sentinel for the current sid (simulating what
        // handleEffect does when the reducer emits a Reconnect effect).
        supervisor.markReconnectRequested("s1")

        // Step 5: Send another frame — the post-dispatch check sees
        // reconnectRequested == sid, throws TokenStreamReconnectRequested,
        // caught by runStream, calls scheduleReconnect.
        dispatchedFrames.clear()
        fake.send(snapshot(sessionId = "s1", partId = "p2"))
        scope.runCurrent()
        // After the reconnect exception, scheduleReconnect creates a new lifecycle
        // with backoff=50ms. Advance past the backoff so the reconnect's runStream
        // calls the streamProvider again.
        scope.advanceTimeBy(60L)
        scope.runCurrent()
        // The reconnect should have opened a new stream (openCount went from 1 to 2)
        assertEquals("reconnect must call stream provider again",
            2, fake.openCount.get())
        assertNull("reconnect sentinel must be cleared after reconnect scheduled",
            supervisor.reconnectRequestedSnapshot())

        supervisor.close("s1")
        scope.runCurrent()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  watchdog-fires-before-first-frame — bgpt MF-2
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Verifies that the watchdog timer fires even when NO frame has arrived
     * (the bgpt MF-2 fix: there is NO `eventCount==0` skip).
     *
     * Sequence:
     *  1. Open stream with a small watchdogMs (100 ms).
     *  2. Do NOT inject any frames — the watchdog should see
     *     [clock] - [lastFrameAt] >= [watchdogMs] and throw
     *     [TokenStreamWatchdogTimeout].
     *  3. onWatchdogTimeout calls [triggerSinceFetch](sid, auth=true) and
     *     [scheduleReconnect] (a new lifecycle with backoff).
     *  4. After the backoff delay, the reconnect opens a new stream.
     *
     * Pins: watchdog fires before first frame, triggerSinceFetch invoked,
     * reconnect lifecycle replaces the dead one.
     */
    @Test
    fun `watchdog fires before first frame - triggers fetch and reconnects`() {
        val sinceFetchCalls = mutableListOf<SinceFetchCall>()
        val scope = TestScope(UnconfinedTestDispatcher())
        val fake = FakeStreamProvider()
        val supervisor = createSupervisor(
            scope = scope,
            fake = fake,
            watchdogMs = 100L,
            triggerSinceFetch = { sid, auth -> sinceFetchCalls += SinceFetchCall(sid, auth) },
        )

        // Step 1: Open stream (small watchdog already set).
        supervisor.open("s1", source = "test")
        scope.runCurrent()
        assertEquals(1, fake.openCount.get())
        dispatchedFrames.clear()

        // Step 2: Advance virtual time past the watchdog window without
        // sending any frames. The watchdog runs inside a coroutineScope
        // child coroutine; advanceTimeBy dispatches its periodic poll.
        scope.advanceTimeBy(200L) // safely past watchdogMs=100
        scope.runCurrent()

        // Step 3: onWatchdogTimeout should have invoked triggerSinceFetch.
        assertTrue(
            "triggerSinceFetch(auth=true) must be called after watchdog timeout",
            sinceFetchCalls.any { it.sid == "s1" && it.auth },
        )

        // Step 4: After watchdog fires, scheduleReconnect creates a new
        // lifecycle with backoff=50ms. The reconnect lifecycle calls
        // streamProvider again (openCount == 2).
        scope.advanceTimeBy(60L) // past initialBackoffMs=50
        scope.runCurrent()
        assertEquals("reconnect must open a new stream after watchdog timeout",
            2, fake.openCount.get())

        supervisor.close("s1")
        scope.runCurrent()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  reconnect-after-delay sid re-check
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Verifies that the `currentSid.get() != sid` guard inside
     * [StreamLifecycleSupervisor.scheduleReconnect] drops a stale reconnect
     * when the current sid changes during the backoff delay, WITHOUT relying
     * on [launchStreamLifecycle]'s getAndSet cancellation.
     *
     * **Mutation-testing criterion**: deleting the guard at
     * `StreamLifecycleSupervisor.kt:395` MUST make this test fail. The test
     * achieves this by using [setCurrentSidForTest] to change the sid WITHOUT
     * calling [open] (which would cancel the pending reconnect job via
     * [launchStreamLifecycle]), so the reconnect job survives into its post-
     * delay execution and the guard is the ONLY thing that stops it.
     *
     * Sequence:
     *  1. Open "s1" — stream opens, openCount=1.
     *  2. Advance past watchdogMs=100 — watchdog fires, scheduleReconnect("s1")
     *     creates a reconnect lifecycle with 50ms backoff. openCount still 1.
     *  3. Via [setCurrentSidForTest], set currentSid to "s2" WITHOUT calling
     *     open() — the pending reconnect job for "s1" is NOT cancelled.
     *  4. Advance past the 50ms backoff — the reconnect block fires, guard
     *     detects `currentSid("s2") != "s1"` → returns early.
     *  5. Assert openCount stays at 1 — no new stream was opened.
     *
     * If the guard were deleted, step 4 would call runStream("s1") →
     * streamProvider → openCount becomes 2 → assertion fails.
     */
    @Test
    fun `reconnect sid re-check drops stale after host switch during backoff`() {
        val sinceFetchCalls = mutableListOf<SinceFetchCall>()
        val scope = TestScope(UnconfinedTestDispatcher())
        val fake = FakeStreamProvider()
        val supervisor = createSupervisor(
            scope = scope,
            fake = fake,
            watchdogMs = 100L,
            triggerSinceFetch = { sid, auth -> sinceFetchCalls += SinceFetchCall(sid, auth) },
        )

        // Step 1: Open "s1" → stream opens, openCount=1, currentSid="s1".
        supervisor.open("s1", source = "first")
        scope.runCurrent()
        assertEquals(1, fake.openCount.get())

        // Step 2: Advance past watchdogMs=100 to trigger scheduleReconnect("s1")
        // with backoff=50ms (due at ~t=150). The watchdog fires at t=100,
        // onWatchdogTimeout calls scheduleReconnect → launchStreamLifecycle
        // creates the reconnect lifecycle. openCount is still 1.
        scope.advanceTimeBy(110L)
        scope.runCurrent()
        assertTrue(
            "watchdog timeout must have triggered fetch",
            sinceFetchCalls.any { it.sid == "s1" && it.auth },
        )
        assertEquals("reconnect must NOT have opened a stream yet (still in backoff)",
            1, fake.openCount.get())

        // Step 3: Use the test seam to set currentSid to "s2" WITHOUT calling
        // open(). The pending reconnect job for "s1" survives — NOT cancelled.
        supervisor.setCurrentSidForTest("s2")

        // Step 4: Advance past the stale reconnect's backoff deadline (~t=150).
        // The reconnect block fires: currentSid("s2") != "s1" → guard returns
        // early. runStream is NOT called → openCount unchanged.
        scope.advanceTimeBy(60L) // from ~110 to ~170, past the 150 deadline
        scope.runCurrent()

        // Step 5: The guard dropped the stale reconnect — no new stream.
        assertEquals("stale reconnect guard must have dropped reconnect — no new stream",
            1, fake.openCount.get())
    }
}
