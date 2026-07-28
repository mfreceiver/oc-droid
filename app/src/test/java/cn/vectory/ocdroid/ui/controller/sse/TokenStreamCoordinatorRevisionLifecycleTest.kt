package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.model.ResyncReason
import cn.vectory.ocdroid.data.model.TokenStreamFrame
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.tokenStreamProductionHooks
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.BundleStamp
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SkeletonReloadCoordinator
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §D-wire (rev-gpt HIGH-1 / CRITICAL-1): coordinator-level integration test for
 * revision-entry lifecycle hooks ([onPartDone], [clearSessionRevisions]).
 *
 * Verifies that every terminal frame — both `done:true` AND `truncated=true` —
 * triggers [onPartDone] so the dedup revision map does not grow unbounded
 * across a long session. Verifies that close, resync, and session switch
 * all reclaim per-session revision entries via [clearSessionRevisions].
 *
 * All tests drive frames through [TokenStreamCoordinator.dispatchEpochFrame]
 * (the real dispatch path), NOT by calling hooks directly — ensuring the
 * production wiring is exercised.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenStreamCoordinatorRevisionLifecycleTest {

    private data class PartDoneCall(
        val sessionId: String,
        val messageId: String,
        val partId: String,
    )

    private data class ClearSessionRevisionsCall(val sessionId: String)

    private fun snapshot(
        partId: String = "p1",
        text: String? = "hello",
        done: Boolean = false,
        truncated: Boolean = false,
        sessionId: String = "s1",
        messageId: String = "m1",
        partEventRevision: Long? = null,
    ) = TokenStreamFrame.PartSnapshot(sessionId, messageId, partId, text, done, truncated, partEventRevision)

    /** Build a coordinator with captured [onPartDone] and [clearSessionRevisions] hooks. */
    private fun makeCoordinator(
        scope: TestScope,
        store: SharedStateStore,
        repository: OpenCodeRepository,
        onPartDone: (String, String, String) -> Unit = { _, _, _ -> },
        clearSessionRevisions: (String) -> Unit = { _ -> },
    ): TokenStreamCoordinator {
        val bundle = repository.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))
        return TokenStreamCoordinator(
            scope = scope,
            slices = store.slices,
            streamProvider = { _, _ -> emptyFlow() },
            triggerSinceFetch = { _, _ -> },
            bundleCommitLock = repository,
            currentBundleProvider = { repository.currentClientBundle() },
            onPartDone = onPartDone,
            clearSessionRevisions = clearSessionRevisions,
        )
    }

    // ── onPartDone: done:true (truncated=false) — regression ──────────────

    @Test
    fun `done true truncated false triggers onPartDone`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val partDoneCalls = mutableListOf<PartDoneCall>()
        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            onPartDone = { sid, mid, pid ->
                partDoneCalls += PartDoneCall(sid, mid, pid)
            },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")
        val bundle = repository.currentClientBundle()!!

        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = snapshot(done = true, truncated = false),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )

        assertEquals(1, partDoneCalls.size)
        assertEquals(PartDoneCall("s1", "m1", "p1"), partDoneCalls.single())
    }

    // ── onPartDone: truncated=true (done=false) — THE BUG FIX ────────────

    @Test
    fun `truncated true done false triggers onPartDone`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val partDoneCalls = mutableListOf<PartDoneCall>()
        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            onPartDone = { sid, mid, pid ->
                partDoneCalls += PartDoneCall(sid, mid, pid)
            },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")
        val bundle = repository.currentClientBundle()!!

        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = snapshot(done = false, truncated = true),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )

        // BUG: senza la fix, truncated=true non chiama onPartDone.
        // Dopo la fix: deve chiamarlo esattamente una volta.
        assertEquals("truncated=true must trigger onPartDone", 1, partDoneCalls.size)
        assertEquals(PartDoneCall("s1", "m1", "p1"), partDoneCalls.single())
    }

    @Test
    fun `truncated true done true triggers onPartDone`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val partDoneCalls = mutableListOf<PartDoneCall>()
        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            onPartDone = { sid, mid, pid ->
                partDoneCalls += PartDoneCall(sid, mid, pid)
            },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")
        val bundle = repository.currentClientBundle()!!

        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = snapshot(done = true, truncated = true),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )

        // BUG: prima della fix, `!effectiveFrame.truncated` bloccava anche
        // (done=true, truncated=true). Dopo la fix: chiama onPartDone.
        assertEquals("(done=true, truncated=true) must trigger onPartDone", 1, partDoneCalls.size)
        assertEquals(PartDoneCall("s1", "m1", "p1"), partDoneCalls.single())
    }

    // ── onPartDone: NOT called for non-terminal frames ───────────────────

    @Test
    fun `non-terminal snapshot does NOT trigger onPartDone`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val partDoneCalls = mutableListOf<PartDoneCall>()
        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            onPartDone = { sid, mid, pid ->
                partDoneCalls += PartDoneCall(sid, mid, pid)
            },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")
        val bundle = repository.currentClientBundle()!!

        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = snapshot(done = false, truncated = false),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )

        // Non-terminal snapshot: onPartDone must NOT fire.
        assertTrue("non-terminal must NOT trigger onPartDone", partDoneCalls.isEmpty())
    }

    // ── clearSessionRevisions: close ─────────────────────────────────────

    @Test
    fun `close triggers clearSessionRevisions`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val clearCalls = mutableListOf<ClearSessionRevisionsCall>()
        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            clearSessionRevisions = { sid ->
                clearCalls += ClearSessionRevisionsCall(sid)
            },
        )

        // Open then close — close must clean up revision entries.
        coordinator.open("s1")
        scope.runCurrent()
        coordinator.close("s1")
        scope.runCurrent()

        assertTrue("close must trigger clearSessionRevisions", clearCalls.isNotEmpty())
        assertTrue(
            "clearSessionRevisions must be called for the closed sid",
            clearCalls.any { it.sessionId == "s1" },
        )
    }

    // ── clearSessionRevisions: resync ────────────────────────────────────

    @Test
    fun `resync triggers clearSessionRevisions`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val clearCalls = mutableListOf<ClearSessionRevisionsCall>()
        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            clearSessionRevisions = { sid ->
                clearCalls += ClearSessionRevisionsCall(sid)
            },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")
        val bundle = repository.currentClientBundle()!!

        // dispatchEpochFrame: Resync frame must call clearSessionRevisions.
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = TokenStreamFrame.Resync(ResyncReason.RECONNECT_NO_REPLAY, "s1"),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )

        assertTrue("resync must trigger clearSessionRevisions", clearCalls.isNotEmpty())
        assertTrue(
            "clearSessionRevisions must be called for the resynced sid",
            clearCalls.any { it.sessionId == "s1" },
        )
    }

    // ── clearSessionRevisions: session switch (open different sid) ──────

    @Test
    fun `session switch triggers clearSessionRevisions for old session via open`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val clearCalls = mutableListOf<ClearSessionRevisionsCall>()
        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            clearSessionRevisions = { sid ->
                clearCalls += ClearSessionRevisionsCall(sid)
            },
        )

        // Open session A, then directly open session B WITHOUT explicit close.
        // The coordinator's different-sid open path MUST call
        // clearSessionRevisions("s-a") before setting currentSid to "s-b".
        coordinator.open("s-a")
        scope.runCurrent()
        coordinator.open("s-b")
        scope.runCurrent()

        // open("s-b") for a different sid must reclaim s-a's revision entries.
        assertTrue(
            "session switch via different-sid open must trigger clearSessionRevisions for old sid",
            clearCalls.any { it.sessionId == "s-a" },
        )
        // open("s-b") must NOT clear its own (s-b) revisions — nothing to clear yet.
        assertTrue(
            "open(\"s-b\") must NOT call clearSessionRevisions for its own sid",
            clearCalls.none { it.sessionId == "s-b" },
        )
    }

    // ── bundle guard: stale bundle prevents hook firing ─────────────────

    @Test
    fun `hooks do not fire when bound bundle is retired`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        repository.configure(baseUrl = "http://host-a.test", slim = true)
        val bundleA = repository.currentClientBundle()!!
        repository.configure(baseUrl = "http://host-b.test", slim = true)
        store.dispatch(AppAction.BundlePublished(
            repository.currentClientBundle()!!.generation,
            repository.currentClientBundle()!!.endpointFp,
        ))

        var partDoneFired = false
        var clearFired = false
        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            onPartDone = { _, _, _ -> partDoneFired = true },
            clearSessionRevisions = { _ -> clearFired = true },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")

        // Dispatch with RETIRED bundle A — must be rejected before hooks.
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = snapshot(done = true),
            capturedRouteInstance = 0L,
            boundBundle = bundleA,
        )

        // close with bundle A guard could also be tested (close uses
        // currentBundleProvider which now returns bundleB).
        assertFalse("onPartDone must NOT fire for retired bundle", partDoneFired)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // D: coordinator + PRODUCTION hooks — terminal blocks stale snapshots
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Helper: builds a [TokenStreamCoordinator] with REAL [tokenStreamProductionHooks]
     * wired in, so the full dedup + terminal tombstone chain is exercised.
     */
    private fun makeCoordinatorWithProductionHooks(
        scope: TestScope,
        store: SharedStateStore,
        repository: OpenCodeRepository,
    ): TokenStreamCoordinator {
        val bundle = repository.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))
        val skeleton = SkeletonReloadCoordinator(
            scope = scope,
            repository = repository,
            slices = store.slices,
            currentServerGroupFp = { "" },
        )
        val productionHooks = tokenStreamProductionHooks(
            store = store,
            skeletonReloadCoordinator = skeleton,
            appScope = CoroutineScope(Dispatchers.Unconfined),
            debounceMs = 0L,
        )
        return TokenStreamCoordinator(
            scope = scope,
            slices = store.slices,
            streamProvider = { _, _ -> emptyFlow() },
            triggerSinceFetch = { _, _ -> },
            bundleCommitLock = repository,
            currentBundleProvider = { repository.currentClientBundle() },
            dedupPartRevision = productionHooks.dedupPartRevision,
            onMessagePartRemoved = productionHooks.onMessagePartRemoved,
            onMessageRemoved = productionHooks.onMessageRemoved,
            onPartDone = productionHooks.onPartDone,
            clearSessionRevisions = productionHooks.clearSessionRevisions,
        )
    }

    @Test
    fun `D done frame blocks stale snapshot from updating chat overlay`() {
        // rev=10 done:true → terminal tombstone set.
        // Then stale rev=5 snapshot → must be rejected by dedup → NO state change.
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val coordinator = makeCoordinatorWithProductionHooks(scope, store, repository)
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")
        val bundle = repository.currentClientBundle()!!

        // First, send a snapshot that establishes rev=10 and is terminal.
        coordinator.dispatchEpochFrame(
            sid = "s1", epoch = epoch, gen = gen,
            frame = TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "final text", done = true, truncated = false, partEventRevision = 10),
            capturedRouteInstance = 0L, boundBundle = bundle,
        )

        // After done:true, the part should be in streamOwned as DONE.
        val streamOwned = store.chatFlow.value.streamOwned
        assertEquals("part must be DONE after done:true", cn.vectory.ocdroid.ui.StreamOwnedState.DONE, streamOwned["p1"])

        // Now send a stale snapshot with rev=5 (lower than the terminal 10).
        // This must be rejected by the terminal tombstone → no update.
        coordinator.dispatchEpochFrame(
            sid = "s1", epoch = epoch, gen = gen,
            frame = TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "stale text", done = false, truncated = false, partEventRevision = 5),
            capturedRouteInstance = 0L, boundBundle = bundle,
        )

        // Chat overlay must be unchanged (stale snapshot rejected by dedup).
        // streamOwned should still have the DONE entry (unchanged).
        assertEquals("stale snapshot must NOT change streamOwned", streamOwned, store.chatFlow.value.streamOwned)
    }

    @Test
    fun `D truncated frame blocks stale snapshot from updating chat overlay`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val coordinator = makeCoordinatorWithProductionHooks(scope, store, repository)
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")
        val bundle = repository.currentClientBundle()!!

        // Establish rev=10 truncated:true.
        coordinator.dispatchEpochFrame(
            sid = "s1", epoch = epoch, gen = gen,
            frame = TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "truncated text", done = false, truncated = true, partEventRevision = 10),
            capturedRouteInstance = 0L, boundBundle = bundle,
        )

        val streamOwned = store.chatFlow.value.streamOwned.toMap()

        // Stale rev=5 must be rejected.
        coordinator.dispatchEpochFrame(
            sid = "s1", epoch = epoch, gen = gen,
            frame = TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "stale text", done = false, truncated = false, partEventRevision = 5),
            capturedRouteInstance = 0L, boundBundle = bundle,
        )

        assertEquals("stale snapshot after truncated must NOT change streamOwned", streamOwned, store.chatFlow.value.streamOwned)
    }

    @Test
    fun `D done frame rejects same revision`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val coordinator = makeCoordinatorWithProductionHooks(scope, store, repository)
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")
        val bundle = repository.currentClientBundle()!!

        // rev=10 done:true.
        coordinator.dispatchEpochFrame(
            sid = "s1", epoch = epoch, gen = gen,
            frame = TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "final", done = true, truncated = false, partEventRevision = 10),
            capturedRouteInstance = 0L, boundBundle = bundle,
        )

        val streamOwned = store.chatFlow.value.streamOwned.toMap()

        // Same rev=10 again (without terminal=false — can happen if sidecar
        // re-sends the terminal snapshot). Must be rejected.
        coordinator.dispatchEpochFrame(
            sid = "s1", epoch = epoch, gen = gen,
            frame = TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "re-send", done = true, truncated = false, partEventRevision = 10),
            capturedRouteInstance = 0L, boundBundle = bundle,
        )

        assertEquals("same revision after terminal must NOT re-bridge", streamOwned, store.chatFlow.value.streamOwned)
    }

    @Test
    fun `D done frame blocks HIGHER revision snapshot from updating chat overlay`() {
        // rev=10 done:true → terminal tombstone.
        // Then rev=11 non-terminal → must be rejected by terminal tombstone
        // (not just stale < = check — this is the blocking defect fix).
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val coordinator = makeCoordinatorWithProductionHooks(scope, store, repository)
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")
        val bundle = repository.currentClientBundle()!!

        // rev=10 done:true.
        coordinator.dispatchEpochFrame(
            sid = "s1", epoch = epoch, gen = gen,
            frame = TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "final", done = true, truncated = false, partEventRevision = 10),
            capturedRouteInstance = 0L, boundBundle = bundle,
        )
        val streamOwned = store.chatFlow.value.streamOwned.toMap()

        // Higher revision (rev=11) non-terminal after done — must be rejected.
        coordinator.dispatchEpochFrame(
            sid = "s1", epoch = epoch, gen = gen,
            frame = TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "higher revision text", done = false, truncated = false, partEventRevision = 11),
            capturedRouteInstance = 0L, boundBundle = bundle,
        )

        assertEquals(
            "higher revision after done must NOT update streamOwned",
            streamOwned,
            store.chatFlow.value.streamOwned,
        )
    }

    @Test
    fun `D clearSession via close allows fresh part in new lifecycle`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val coordinator = makeCoordinatorWithProductionHooks(scope, store, repository)
        val bundle = repository.currentClientBundle()!!

        // Establish a done:true frame via direct dispatch (no open(required).
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")
        coordinator.dispatchEpochFrame(
            sid = "s1", epoch = epoch, gen = gen,
            frame = TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "terminal text", done = true, truncated = false, partEventRevision = 10),
            capturedRouteInstance = 0L, boundBundle = bundle,
        )
        assertTrue("part must be in streamOwned after done", store.chatFlow.value.streamOwned.containsKey("p1"))

        // Now close s1 (triggers clearSessionRevisions in the ledger).
        coordinator.close("s1")
        scope.runCurrent()

        // New epoch: the ledger's session was cleared by close.
        val epoch2 = coordinator.bumpEpochForTest("s1")
        val gen2 = coordinator.beginSession("s1")

        // After clearSession, a new part with a fresh revision should be accepted.
        coordinator.dispatchEpochFrame(
            sid = "s1", epoch = epoch2, gen = gen2,
            frame = TokenStreamFrame.PartSnapshot("s1", "m1", "p2", "new part", done = false, truncated = false, partEventRevision = 1),
            capturedRouteInstance = 0L, boundBundle = bundle,
        )
        assertTrue("new part after close must be accepted", store.chatFlow.value.streamOwned.containsKey("p2"))
    }
}
