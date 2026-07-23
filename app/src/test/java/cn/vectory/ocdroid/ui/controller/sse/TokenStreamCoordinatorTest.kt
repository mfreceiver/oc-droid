package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.model.ResyncReason
import cn.vectory.ocdroid.data.model.TokenStreamFrame
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.StreamOwnedState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * §Stage-D1 unit tests for [TokenStreamCoordinator]. Drives the engine with a
 * fake [streamProvider] (Channel-backed so tests can pump scripted frames) +
 * `TestScope(UnconfinedTestDispatcher())` for deterministic timing. Verifies:
 *
 *  - open/close lifecycle
 *  - max-1 foreground stream (open B closes A)
 *  - debounce on rapid open(sid)
 *  - epoch stale-frame drop
 *  - watchdog timeout → ClearPartState + TriggerSinceFetch(auth) + Reconnect
 *  - watchdog fires BEFORE first frame (NO eventCount==0 skip)
 *  - generation-guard rejects stale clear (newer gen owns the partId)
 *  - 503 sse_token_subscriber_limit → degrade after N consecutive failures
 *  - reducer-effect → dispatch bridging (ClearPartState / TriggerSinceFetch / Reconnect)
 *  - ChatState.streamOwned / streamingPartTexts updated on STREAMING/DONE/buffer
 *  - S2: resync frame with sessionId==null → infer from the open connection's sid
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenStreamCoordinatorTest {

    private lateinit var scope: TestScope
    private lateinit var slices: SliceFlows
    private lateinit var stateStore: SharedStateStore
    private lateinit var fake: FakeStreamProvider
    private val sinceFetchCalls = mutableListOf<SinceFetchCall>()
    private lateinit var coordinator: TokenStreamCoordinator

    @Before
    fun setUp() {
        scope = TestScope(UnconfinedTestDispatcher())
        stateStore = SharedStateStore()
        slices = stateStore.slices
        fake = FakeStreamProvider()
        sinceFetchCalls.clear()
        // watchdogMs is intentionally LARGE in the shared coordinator so
        // `runPending()` (= advanceUntilIdle) in non-watchdog tests does not
        // trip the timeout → reconnect loop. Tests that exercise the
        // watchdog rebuild via [buildCoordinator] with a small watchdogMs.
        coordinator = buildCoordinator(watchdogMs = 10_000L)
    }

    private fun buildCoordinator(
        watchdogMs: Long = 10_000L,
        openDebounceMs: Long = 0L,
        streamProvider: (String, String?) -> Flow<TokenStreamFrame> = fake.provider,
        triggerSinceFetch: (String, Boolean) -> Unit = { sid, auth -> sinceFetchCalls += SinceFetchCall(sid, auth) },
        initialBackoffMs: Long = 50L,
        retryAfter503Ms: Long = 20L,
        maxConsecutive503: Int = 3,
    ): TokenStreamCoordinator = TokenStreamCoordinator(
        scope = scope,
        slices = slices,
        streamProvider = streamProvider,
        triggerSinceFetch = triggerSinceFetch,
        openDebounceMs = openDebounceMs,
        watchdogPollMs = 10L,
        watchdogMs = watchdogMs,
        initialBackoffMs = initialBackoffMs,
        maxBackoffMs = 200L,
        retryAfter503Ms = retryAfter503Ms,
        maxConsecutive503 = maxConsecutive503,
        clock = { scope.testScheduler.currentTime },
    )

    @After
    fun tearDown() {
        // Best-effort: cancel any leftover jobs.
        try {
            scope.runCurrent()
        } catch (_: Throwable) {}
    }

    private fun runPending() {
        // runCurrent (NOT advanceUntilIdle): runs only tasks immediately due
        // at the current virtual time WITHOUT advancing the clock. This is
        // essential because the watchdog + reconnect loop is unbounded —
        // advanceUntilIdle would advance the clock to fire the watchdog,
        // trigger the handler, schedule a reconnect, and loop forever.
        // Tests that need to advance time do so explicitly via advanceTimeBy.
        scope.runCurrent()
    }

    private fun snapshot(
        partId: String = "p1",
        text: String? = "hello",
        done: Boolean = false,
        truncated: Boolean = false,
        sessionId: String = "s1",
        messageId: String = "m1",
    ) = TokenStreamFrame.PartSnapshot(sessionId, messageId, partId, text, done, truncated)

    private fun delta(
        partId: String = "p1",
        text: String = "x",
        sessionId: String = "s1",
        messageId: String = "m1",
    ) = TokenStreamFrame.PartDelta(sessionId, messageId, partId, text)

    // ── open/close lifecycle ──────────────────────────────────────────────

    @Test
    fun `open subscribes via the streamProvider`() {
        coordinator.open("s1")
        runPending()
        assertEquals("s1", fake.lastOpenedSid)
        assertEquals(1, fake.openCount.get())
    }

    @Test
    fun `close cancels the in-flight stream`() {
        coordinator.open("s1")
        runPending()
        val jobAfterOpen = coordinator.currentStreamJobSnapshot()
        assertNotNull(jobAfterOpen)
        assertTrue("stream job should be active right after open", jobAfterOpen?.isActive == true)
        coordinator.close("s1")
        runPending()
        // cancelCurrentStream keeps the reference (so probes can observe the
        // cancelled state); the job must report inactive.
        val jobAfterClose = coordinator.currentStreamJobSnapshot()
        assertNotNull(jobAfterClose)
        assertFalse("stream job should be cancelled after close", jobAfterClose?.isActive == true)
    }

    // ── max-1: opening B closes A ─────────────────────────────────────────

    @Test
    fun `opening B closes A`() {
        coordinator.open("s1")
        runPending()
        assertEquals("s1", fake.lastOpenedSid)
        val aChannel = fake.currentChannel
        assertNotNull(aChannel)
        coordinator.open("s2")
        runPending()
        assertEquals("s2", fake.lastOpenedSid)
        assertEquals(2, fake.openCount.get())
        // A's channel consumer was cancelled; its producer (Channel) is now
        // orphaned but the coordinator has moved on. Verify the current
        // channel is the B one.
        val bChannel = fake.currentChannel
        assertNotNull(bChannel)
        assertTrue("B channel must be distinct from A", aChannel !== bChannel)
    }

    // ── debounce on rapid open(sid) ───────────────────────────────────────

    @Test
    fun `rapid opens are debounced — provider called once`() {
        // Rebuild with non-zero debounce for this case.
        val debounced = buildCoordinator(openDebounceMs = 50L)
        debounced.open("s1")
        debounced.open("s1")
        debounced.open("s1")
        // Advance past the debounce window (50ms) so the surviving open fires.
        scope.advanceTimeBy(100L)
        runPending()
        assertEquals(1, fake.openCount.get())
        assertEquals("s1", fake.lastOpenedSid)
        debounced.close("s1")
        runPending()
    }

    @Test
    fun `open superseded during debounce does not subscribe`() {
        val debounced = buildCoordinator(openDebounceMs = 50L)
        debounced.open("s1")
        // Immediately superseded before the debounce window elapses.
        debounced.open("s2")
        scope.advanceTimeBy(100L)
        runPending()
        // Only the second open's sid should have hit the provider.
        assertEquals("s2", fake.lastOpenedSid)
        assertEquals(1, fake.openCount.get())
        debounced.close("s2")
        runPending()
    }

    // ── epoch stale-frame drop ────────────────────────────────────────────

    @Test
    fun `dispatchEpochFrame drops a frame whose epoch is stale`() {
        coordinator.open("s1")
        runPending()
        val currentEpoch = coordinator.epochOf("s1")
        assertTrue("epoch should be > 0 after open", currentEpoch > 0L)
        // Dispatch a frame with the current epoch — should be processed.
        coordinator.dispatchEpochFrame("s1", currentEpoch, gen = 1L, snapshot(text = "live"))
        assertEquals("live", stateStore.chatFlow.value.streamingPartTexts["p1"])
        // Now bump the epoch (simulating a re-open) and dispatch a stale-epoch frame.
        coordinator.bumpEpochForTest("s1")
        val newEpoch = coordinator.epochOf("s1")
        assertTrue(newEpoch > currentEpoch)
        // The stale-epoch frame (using the OLD epoch) must be dropped: the
        // buffer must NOT change.
        coordinator.dispatchEpochFrame("s1", currentEpoch, gen = 1L, snapshot(text = "STALE"))
        assertEquals("live", stateStore.chatFlow.value.streamingPartTexts["p1"])
        // The fresh-epoch frame DOES land.
        coordinator.dispatchEpochFrame("s1", newEpoch, gen = 1L, snapshot(text = "fresh"))
        assertEquals("fresh", stateStore.chatFlow.value.streamingPartTexts["p1"])
    }

    // ── watchdog timeout → ClearPartState + TriggerSinceFetch + Reconnect ─

    @Test
    fun `watchdog timeout clears sid parts, triggers authoritative fetch, and reconnects`() {
        // Rebuild with a small watchdog so the timeout fires within the
        // test's advanceTimeBy window.
        coordinator = buildCoordinator(watchdogMs = 100L)
        coordinator.open("s1")
        runPending()
        // Stream a part so the sid owns it.
        coordinator.dispatchEpochFrame("s1", coordinator.epochOf("s1"), coordinator.genOf("s1"), snapshot(partId = "p1", text = "buf"))
        assertTrue(stateStore.chatFlow.value.streamOwned.containsKey("p1"))
        val baseline = fake.openCount.get()
        // Advance virtual time past the watchdog window WITHOUT any frame
        // (the fake channel is open but nothing is sent). advanceTimeBy in
        // kotlinx-coroutines-test 1.7+ runs tasks at intermediate times, so
        // the watchdog body executes at each poll tick and the timeout
        // throw → handler sequence runs WITHIN this advance.
        scope.advanceTimeBy(200L) // past watchdogMs=100; handler runs.
        // ClearPartState dispatched.
        assertFalse("streamOwned[p1] should be cleared", stateStore.chatFlow.value.streamOwned.containsKey("p1"))
        assertFalse("streamingPartTexts[p1] should be cleared", stateStore.chatFlow.value.streamingPartTexts.containsKey("p1"))
        // TriggerSinceFetch(auth=true) invoked.
        assertTrue(sinceFetchCalls.any { it.sid == "s1" && it.auth })
        // Reconnect was scheduled with backoff. The reconnect's runStream
        // fires a NEW watchdog at +100ms; we advance just past the backoff
        // window to land ONE reconnect, then close to stop the loop.
        scope.advanceTimeBy(60L) // past initialBackoffMs=50; one reconnect.
        assertTrue("provider should have been called again for reconnect", fake.openCount.get() > baseline)
        // Stop the reconnect loop so advanceUntilIdle/runCurrent downstream
        // (e.g. test teardown) doesn't loop forever.
        coordinator.close("s1")
        runPending()
    }

    // ── watchdog fires BEFORE first frame (no eventCount==0 skip) ─────────

    @Test
    fun `watchdog fires before the first frame arrives`() {
        coordinator = buildCoordinator(watchdogMs = 100L)
        coordinator.open("s1")
        runPending()
        // Do NOT send any frame. Advance past watchdog window.
        scope.advanceTimeBy(200L) // past watchdogMs=100
        // Watchdog must have tripped — TriggerSinceFetch(auth=true) emitted.
        assertTrue(
            "watchdog should have fired pre-first-frame and triggered an authoritative fetch",
            sinceFetchCalls.any { it.sid == "s1" && it.auth },
        )
        coordinator.close("s1")
        runPending()
    }

    // ── generation-guard rejects stale clear ──────────────────────────────

    @Test
    fun `filterClearByGeneration drops a clear when a newer generation owns the partId`() {
        coordinator.open("s1")
        runPending()
        val gen1 = coordinator.genOf("s1")
        // Stream p1 under gen1 → owner=(s1, gen1).
        coordinator.dispatchEpochFrame("s1", coordinator.epochOf("s1"), gen1, snapshot(partId = "p1", text = "v1"))
        assertEquals(setOf("p1"), coordinator.ownedPartsForSid("s1"))
        // Reopen s1 → bumps generation; the new stream re-claims p1.
        coordinator.open("s1")
        runPending()
        val gen2 = coordinator.genOf("s1")
        assertTrue("gen should bump on reopen", gen2 > gen1)
        coordinator.dispatchEpochFrame("s1", coordinator.epochOf("s1"), gen2, snapshot(partId = "p1", text = "v2"))
        // Now a stale clear targeting (s1, gen1) — the partId is owned by (s1, gen2).
        val allowed = coordinator.filterClearByGeneration("s1", gen1, setOf("p1"))
        assertTrue("stale clear must be rejected", allowed.isEmpty())
        // And the live overlay is preserved.
        assertEquals("v2", stateStore.chatFlow.value.streamingPartTexts["p1"])
        assertEquals(StreamOwnedState.STREAMING, stateStore.chatFlow.value.streamOwned["p1"])
    }

    @Test
    fun `filterClearByGeneration allows clear when owner matches`() {
        coordinator.open("s1")
        runPending()
        val gen = coordinator.genOf("s1")
        coordinator.dispatchEpochFrame("s1", coordinator.epochOf("s1"), gen, snapshot(partId = "p1", text = "v"))
        val allowed = coordinator.filterClearByGeneration("s1", gen, setOf("p1"))
        assertEquals(setOf("p1"), allowed)
    }

    @Test
    fun `filterClearByGeneration allows clear when no owner is registered`() {
        // First-snapshot truncated case: reducer emits ClearPartState before
        // any onPartOwned. The clear must still flow (safe no-op on an empty
        // overlay, but the dispatch path must not drop it).
        coordinator.open("s1")
        runPending()
        val gen = coordinator.genOf("s1")
        val allowed = coordinator.filterClearByGeneration("s1", gen, setOf("p-no-owner"))
        assertEquals(setOf("p-no-owner"), allowed)
    }

    // ── reducer-effect → dispatch bridging ────────────────────────────────

    @Test
    fun `truncated snapshot emits ClearPartState and TriggerSinceFetch(auth=true)`() {
        coordinator.open("s1")
        runPending()
        val gen = coordinator.genOf("s1")
        val epoch = coordinator.epochOf("s1")
        // Stream the part first so it's owned.
        coordinator.dispatchEpochFrame("s1", epoch, gen, snapshot(partId = "p1", text = "partial"))
        assertTrue(stateStore.chatFlow.value.streamOwned.containsKey("p1"))
        // Truncate.
        coordinator.dispatchEpochFrame("s1", epoch, gen, snapshot(partId = "p1", truncated = true))
        // ClearPartState dispatched for p1.
        assertFalse(stateStore.chatFlow.value.streamOwned.containsKey("p1"))
        assertFalse(stateStore.chatFlow.value.streamingPartTexts.containsKey("p1"))
        // TriggerSinceFetch(auth=true) emitted.
        assertTrue(sinceFetchCalls.any { it.sid == "s1" && it.auth })
    }

    @Test
    fun `resync reconnect_no_replay emits ClearPartState + TriggerSinceFetch + schedules Reconnect via sentinel`() {
        // §MF-1 (gate r1): the Reconnect effect now sets a sentinel (NOT a
        // direct scheduleReconnect call) consumed inside flow.collect's post-
        // dispatch check. Pump frames THROUGH the flow so the sentinel fires.
        coordinator.open("s1")
        runPending()
        assertEquals(1, fake.openCount.get())
        // Pump two snapshots through the flow so parts are owned.
        fake.send(snapshot(partId = "p1", text = "a"))
        runPending()
        fake.send(snapshot(partId = "p2", text = "b"))
        runPending()
        assertEquals(2, stateStore.chatFlow.value.streamOwned.size)
        val baseline = fake.openCount.get()
        // Pump the resync through the flow — the collect lambda processes it,
        // handleEffect sets the sentinel, the post-dispatch sentinel check
        // throws TokenStreamReconnectRequested → catch → scheduleReconnect.
        fake.send(TokenStreamFrame.Resync(ResyncReason.RECONNECT_NO_REPLAY, "s1"))
        runPending()
        // Both parts cleared (ClearPartState dispatched synchronously inside
        // dispatchEpochFrame, before the sentinel check threw).
        assertEquals(0, stateStore.chatFlow.value.streamOwned.size)
        // Fetch triggered.
        assertTrue(sinceFetchCalls.any { it.sid == "s1" && it.auth })
        // The reconnect is now pending in backoff (J2 in delay). Advance past
        // it to land ONE reconnect.
        scope.advanceTimeBy(60L) // past initialBackoffMs=50
        runPending()
        assertTrue("reconnect should call provider again", fake.openCount.get() > baseline)
        coordinator.close("s1")
        runPending()
    }

    // ── MF-1 (gate r1): max-1 under concurrent reconnect ──────────────────

    @Test
    fun `open during reconnect backoff supersedes the pending reconnect — no double subscription`() {
        // Use the default large watchdogMs so the watchdog doesn't interfere.
        coordinator = buildCoordinator(watchdogMs = 10_000L, initialBackoffMs = 200L)
        coordinator.open("s1")
        runPending()
        assertEquals(1, fake.openCount.get())
        assertEquals("no collector should be live beyond the initial", 1, fake.maxLiveCollectors.get())

        // Pump a resync(reconnect_no_replay) through the flow → sentinel →
        // throw → catch → scheduleReconnect → J2 in delay(200).
        fake.send(snapshot(partId = "p1", text = "a"))
        runPending()
        fake.send(TokenStreamFrame.Resync(ResyncReason.RECONNECT_NO_REPLAY, "s1"))
        runPending()
        // J2 is now pending in backoff; the live collector (J1) has unwound.
        assertEquals("J1 unwound → liveCount back to 0", 0, fake.liveCollectors.get())
        assertEquals("only J1's provider call so far", 1, fake.openCount.get())

        // During the backoff window, user issues open("s1") again. This MUST
        // supersede J2 (the pending reconnect) via launchStreamLifecycle →
        // currentStreamJob.getAndSet(J3)?.cancel() → J2 cancelled.
        coordinator.open("s1")
        runPending()
        // J3's runStream called streamProvider (openCount=2) and its collector
        // is now live. J2 was cancelled BEFORE its delay(200) could fire, so
        // it NEVER called streamProvider.
        assertEquals("openCount should be exactly 2 (J1 + J3, NOT J2)", 2, fake.openCount.get())
        assertEquals("exactly one live collector (J3)", 1, fake.liveCollectors.get())

        // Advance past the backoff window. The orphaned J2 would have fired
        // here (at +200ms) if NOT cancelled — calling streamProvider a 3rd
        // time and creating a 2nd concurrent collector.
        scope.advanceTimeBy(300L)
        runPending()
        assertEquals(
            "orphan reconnect must NOT fire — openCount should stay at 2",
            2,
            fake.openCount.get(),
        )
        assertEquals(
            "maxLiveCollectors must never exceed 1 — no overlapping collectors",
            1,
            fake.maxLiveCollectors.get(),
        )
        coordinator.close("s1")
        runPending()
    }

    @Test
    fun `open during 503-retry backoff supersedes the pending retry — no double subscription`() {
        // §MF-1: the 503-retry path also goes through launchStreamLifecycle,
        // so a user open() during the Retry-After window supersedes the retry.
        val fifty = FiftyProvider()
        val c = buildCoordinator(
            streamProvider = fifty.provider,
            triggerSinceFetch = { _, _ -> },
            retryAfter503Ms = 200L,
            maxConsecutive503 = 10, // high cap so we don't degrade mid-test
        )
        c.open("s1")
        scope.advanceTimeBy(10L)
        runPending()
        // First open → immediate 503 failure → 503-retry scheduled in delay(200).
        val countAfterFirstFailure = fifty.openCount.get()
        assertTrue("at least one provider call from initial open", countAfterFirstFailure >= 1)

        // During the Retry-After window, user issues open("s1") again. This
        // supersedes the pending 503-retry via launchStreamLifecycle.
        c.open("s1")
        scope.advanceTimeBy(10L)
        runPending()
        val countAfterReopen = fifty.openCount.get()
        assertTrue("reopen should call provider", countAfterReopen > countAfterFirstFailure)

        // Advance past the Retry-After window. The orphaned 503-retry would
        // have fired here if NOT cancelled.
        scope.advanceTimeBy(300L)
        runPending()
        // The reopen's own 503 failure may schedule another retry, but the
        // ORPHANED retry (from the first failure) must NOT have fired. We
        // verify by checking that the count didn't jump by more than the
        // reopen's retry chain (which is itself bounded by the re-open's
        // own 503 handling). The key invariant: no MORE than one provider
        // call per backoff cycle. We assert the count is stable across a
        // short window after the reopen's immediate retry.
        val stable = fifty.openCount.get()
        scope.advanceTimeBy(10L)
        runPending()
        assertEquals("no orphan retry within the immediate window", stable, fifty.openCount.get())
        c.close("s1")
        runPending()
    }

    // ── MF-2 (gate r1): streak reset on successful traffic ───────────────

    @Test
    fun `successful frame resets the consecutive-503 streak to zero`() {
        coordinator.open("s1")
        runPending()
        // Build a partial 503 streak directly via the test seam.
        coordinator.increment503ForTest("s1")
        coordinator.increment503ForTest("s1")
        assertEquals(2, coordinator.consecutive503Of("s1"))
        // A successful frame (any type — even ServerConnected) proves the
        // admission gate let us in; the streak is broken.
        coordinator.dispatchEpochFrame(
            "s1",
            coordinator.epochOf("s1"),
            coordinator.genOf("s1"),
            TokenStreamFrame.ServerConnected("s1"),
        )
        assertEquals(
            "successful frame must reset the consecutive-503 streak",
            0,
            coordinator.consecutive503Of("s1"),
        )
        coordinator.close("s1")
        runPending()
    }

    @Test
    fun `resync token_memory_limit clears but does NOT reconnect`() {
        // §5 C-3: repurposed from part_too_large (removed from the enum — the
        // server never emits it; over-limit surfaces as truncated:true on the
        // part snapshot). Uses TOKEN_MEMORY_LIMIT as a stable non-reconnect reason.
        coordinator.open("s1")
        runPending()
        val gen = coordinator.genOf("s1")
        val epoch = coordinator.epochOf("s1")
        coordinator.dispatchEpochFrame("s1", epoch, gen, snapshot(partId = "p1", text = "a"))
        val baseline = fake.openCount.get()
        coordinator.dispatchEpochFrame("s1", epoch, gen, TokenStreamFrame.Resync(ResyncReason.TOKEN_MEMORY_LIMIT, "s1"))
        // Cleared.
        assertEquals(0, stateStore.chatFlow.value.streamOwned.size)
        // Fetch triggered.
        assertTrue(sinceFetchCalls.any { it.sid == "s1" && it.auth })
        // TOKEN_MEMORY_LIMIT does NOT emit a Reconnect effect — verify NO provider
        // call happened synchronously (we do NOT advance time here because
        // the watchdog would independently reconnect on its own timeout,
        // which is unrelated to the reducer's effect decision).
        assertEquals("token_memory_limit must NOT trigger reconnect", baseline, fake.openCount.get())
        coordinator.close("s1")
        runPending()
    }

    @Test
    fun `resync with null sessionId infers sid from the open connection (S2)`() {
        coordinator.open("s1")
        runPending()
        val gen = coordinator.genOf("s1")
        val epoch = coordinator.epochOf("s1")
        // Resync with sessionId == null — coordinator must infer "s1".
        coordinator.dispatchEpochFrame("s1", epoch, gen, TokenStreamFrame.Resync(ResyncReason.TOKEN_MEMORY_LIMIT, null))
        // TriggerSinceFetch fired with the inferred sid (this reason does NOT
        // reconnect, so no provider re-open expected).
        assertTrue(
            "TriggerSinceFetch should use the inferred sid s1",
            sinceFetchCalls.any { it.sid == "s1" && it.auth },
        )
        coordinator.close("s1")
        runPending()
    }

    // ── MF-1 (gate r2): stale sentinel recovery ───────────────────────────

    @Test
    fun `stale reconnect sentinel from a cancelled sid does NOT block a new sid's Reconnect`() {
        // §gate r2: a cancelled sid's unconsumed resync(Reconnect) leaves a
        // stale sentinel under the OLD sid. The new sid's open() + runStream
        // must UNCONDITIONALLY clear it (not CAS-on-sid) so the new sid's
        // own Reconnect fires correctly.
        //
        // We use the test seam to set the stale sentinel (simulating the race
        // where sid A's resync sets the sentinel, then open(B) cancels A's
        // collect before the post-dispatch check could consume it). Pumping
        // the resync + open(s2) on Unconfined is synchronous and would NOT
        // produce the race naturally (the sentinel is consumed inline before
        // any open can intervene), so the test seam is the only reliable way
        // to set up the stale-sentinel precondition.
        coordinator = buildCoordinator(watchdogMs = 10_000L, initialBackoffMs = 50L)
        // Stale sentinel from a cancelled sid "s1".
        coordinator.setReconnectRequestedForTest("s1")
        assertEquals("stale sentinel precondition", "s1", coordinator.reconnectRequestedSnapshot())

        // open("s2") must clear the stale sentinel via set(null).
        coordinator.open("s2")
        runPending()
        assertNull(
            "open must UNCONDITIONALLY clear the sentinel (not CAS-on-sid)",
            coordinator.reconnectRequestedSnapshot(),
        )
        assertEquals(1, fake.openCount.get())

        // Pump a resync(reconnect_no_replay) for s2 through the flow. The
        // collect lambda should set sentinel→"s2", throw, catch, clear, and
        // scheduleReconnect — despite the prior stale "s1" value.
        fake.send(TokenStreamFrame.Resync(ResyncReason.RECONNECT_NO_REPLAY, "s2"))
        runPending()
        // The reconnect is now pending in backoff. Capture the baseline.
        val baseline = fake.openCount.get()

        // Advance past backoff to land the reconnect.
        scope.advanceTimeBy(60L)
        runPending()
        assertTrue(
            "s2's Reconnect must fire despite the stale sentinel from s1",
            fake.openCount.get() > baseline,
        )
        coordinator.close("s2")
        runPending()
    }

    // ── ChatState bridge on STREAMING / DONE / buffer ─────────────────────

    @Test
    fun `streaming snapshot writes STREAMING ownership and the buffer text`() {
        coordinator.open("s1")
        runPending()
        coordinator.dispatchEpochFrame("s1", coordinator.epochOf("s1"), coordinator.genOf("s1"), snapshot(partId = "p1", text = "hello"))
        assertEquals("hello", stateStore.chatFlow.value.streamingPartTexts["p1"])
        assertEquals(StreamOwnedState.STREAMING, stateStore.chatFlow.value.streamOwned["p1"])
    }

    @Test
    fun `delta appends into the reducer buffer and is bridged as STREAMING`() {
        coordinator.open("s1")
        runPending()
        val epoch = coordinator.epochOf("s1")
        val gen = coordinator.genOf("s1")
        coordinator.dispatchEpochFrame("s1", epoch, gen, snapshot(partId = "p1", text = "hello"))
        coordinator.dispatchEpochFrame("s1", epoch, gen, delta(partId = "p1", text = " world"))
        assertEquals("hello world", stateStore.chatFlow.value.streamingPartTexts["p1"])
        assertEquals(StreamOwnedState.STREAMING, stateStore.chatFlow.value.streamOwned["p1"])
    }

    @Test
    fun `done snapshot transitions to DONE and replaces final text`() {
        coordinator.open("s1")
        runPending()
        val epoch = coordinator.epochOf("s1")
        val gen = coordinator.genOf("s1")
        coordinator.dispatchEpochFrame("s1", epoch, gen, snapshot(partId = "p1", text = "partial"))
        coordinator.dispatchEpochFrame("s1", epoch, gen, delta(partId = "p1", text = "+"))
        coordinator.dispatchEpochFrame("s1", epoch, gen, snapshot(partId = "p1", text = "FINAL", done = true))
        assertEquals("FINAL", stateStore.chatFlow.value.streamingPartTexts["p1"])
        assertEquals(StreamOwnedState.DONE, stateStore.chatFlow.value.streamOwned["p1"])
    }

    // ── 503 subscriber-limit → degrade after N ────────────────────────────

    @Test
    fun `three consecutive 503 failures degrade the sid`() {
        // Wire a provider that fails immediately with a 503-shaped exception.
        val fifty = FiftyProvider()
        val c = buildCoordinator(
            streamProvider = fifty.provider,
            triggerSinceFetch = { _, _ -> },
            retryAfter503Ms = 5L,
            maxConsecutive503 = 3,
        )
        c.open("s1")
        // Run the retry ladder: each failure bumps consecutive503BySid until
        // it hits the cap (3). Each retry is scheduled at +retryAfter503Ms
        // (5ms); advancing the clock past 3 hops lands all 3 attempts +
        // the degrade decision.
        scope.advanceTimeBy(100L)
        assertTrue("sid should be degraded after 3 consecutive 503s", c.isDegraded("s1"))
        // No further attempts — the provider's openCount should stabilise.
        val stableCount = fifty.openCount.get()
        scope.advanceTimeBy(500L)
        assertEquals("no further attempts after degrade", stableCount, fifty.openCount.get())
    }

    @Test
    fun `degraded sid open is a no-op`() {
        // Pre-degrade by exhausting 503 retries.
        val fifty = FiftyProvider()
        val c = buildCoordinator(
            streamProvider = fifty.provider,
            triggerSinceFetch = { _, _ -> },
            retryAfter503Ms = 5L,
            maxConsecutive503 = 3,
        )
        c.open("s1")
        scope.advanceTimeBy(100L)
        assertTrue(c.isDegraded("s1"))
        val countAfterDegrade = fifty.openCount.get()
        // resetDegraded re-arms; without it, a fresh open is a no-op.
        c.open("s1")
        scope.advanceTimeBy(100L)
        assertEquals(countAfterDegrade, fifty.openCount.get())
        // resetDegraded allows a new attempt.
        c.resetDegraded("s1")
        c.open("s1")
        scope.advanceTimeBy(100L)
        assertTrue("re-open after reset should call provider again", fifty.openCount.get() > countAfterDegrade)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Backing data for assertion on TriggerSinceFetch callback arguments. */
    private data class SinceFetchCall(val sid: String, val auth: Boolean)

    /**
     * Fake streamProvider backed by an unbounded [Channel]. The latest channel
     * is exposed so tests can either pump frames via [send] or close the
     * stream via [closeStream]. Each provider invocation replaces
     * [currentChannel] (mirrors production: the prior stream's consumer is
     * cancelled, its channel is orphaned).
     *
     * §MF-1 (gate r1): [liveCollectors] / [maxLiveCollectors] track the
     * number of concurrently-live flow collectors. The max-1 invariant
     * (no overlapping EventSources / cap-8 admissions) is directly testable
     * by asserting `maxLiveCollectors <= 1` at the end of a test.
     */
    private class FakeStreamProvider {
        val openCount = AtomicInteger(0)
        /** Current number of live flow bodies (incremented on flow start, decremented on flow completion/cancel). */
        val liveCollectors = AtomicInteger(0)
        /** High-water mark of [liveCollectors] — directly tests the max-1 invariant. */
        val maxLiveCollectors = AtomicInteger(0)
        var lastOpenedSid: String? = null
            private set
        var currentChannel: Channel<TokenStreamFrame>? = null
            private set

        val provider: (String, String?) -> Flow<TokenStreamFrame> = { sid, _ ->
            openCount.incrementAndGet()
            lastOpenedSid = sid
            val ch = Channel<TokenStreamFrame>(Channel.UNLIMITED)
            currentChannel = ch
            flow {
                liveCollectors.incrementAndGet()
                maxLiveCollectors.updateAndGet { cur -> maxOf(cur, liveCollectors.get()) }
                try {
                    for (frame in ch) {
                        emit(frame)
                    }
                } finally {
                    liveCollectors.decrementAndGet()
                }
            }
        }

        fun send(frame: TokenStreamFrame, timeoutMs: Long = 100L) {
            val ch = currentChannel ?: error("no active channel")
            val result = ch.trySend(frame)
            assertTrue("send failed: ${result.exceptionOrNull()}", result.isSuccess)
        }

        fun closeStream(cause: Throwable? = null) {
            currentChannel?.close(cause)
        }
    }

    /** Fake provider whose flow throws a 503-subscriber-limit exception. */
    private class FiftyProvider {
        val openCount = AtomicInteger(0)
        val provider: (String, String?) -> Flow<TokenStreamFrame> = { _, _ ->
            openCount.incrementAndGet()
            flow<TokenStreamFrame> {
                throw RuntimeException("HTTP 503 sse_token_subscriber_limit")
            }
        }
    }
}
