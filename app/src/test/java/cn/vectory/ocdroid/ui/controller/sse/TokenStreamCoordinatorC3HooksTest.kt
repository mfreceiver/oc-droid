package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.model.TokenStreamFrame
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.BundleStamp
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §Stage-B C3 (CRITICAL): verifies the three token-frame commit hooks
 * ([TokenStreamCoordinator.dedupPartRevision],
 * [TokenStreamCoordinator.onMessagePartRemoved],
 * [TokenStreamCoordinator.onMessageRemoved]) receive the correct
 * [TokenFrameCommitContext] captured inside the epoch+bundle critical
 * section, and that the dispatch ordering matches the frozen contract:
 *
 *  - dedup returning `false` drops the frame (no reducer mutation,
 *    no bridge, no hook for removal frames).
 *  - MessagePartRemoved: reducer overlay clear → callback →
 *    ClearPartState effect (chat-slice clear).
 *  - MessageRemoved: reducer overlay clear → callback →
 *    ClearPartState effect (chat-slice clear).
 *
 * The hook signatures (for Lane I production wiring in ControllerModule)
 * are the ones asserted here:
 *
 * ```
 * dedupPartRevision: (sessionId, messageId, partId, partEventRevision, context) -> Boolean
 * onMessagePartRemoved: (sessionId, messageId, partId, messageEventSeq, context) -> Unit
 * onMessageRemoved: (sessionId, messageId, context) -> Unit
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenStreamCoordinatorC3HooksTest {

    private data class PartRemovedCall(
        val sessionId: String,
        val messageId: String,
        val partId: String,
        val messageEventSeq: Long,
        val context: TokenFrameCommitContext,
        /** Snapshot of chat-slice streamOwned keys at callback time. */
        val streamOwnedKeysAtCall: Set<String>,
        /** Snapshot of chat-slice streamingPartTexts keys at callback time. */
        val streamingTextsKeysAtCall: Set<String>,
    )

    private data class MessageRemovedCall(
        val sessionId: String,
        val messageId: String,
        val context: TokenFrameCommitContext,
        val streamOwnedKeysAtCall: Set<String>,
        val streamingTextsKeysAtCall: Set<String>,
    )

    private data class DedupCall(
        val sessionId: String,
        val messageId: String,
        val partId: String,
        val partEventRevision: Long?,
        val context: TokenFrameCommitContext,
    )

    private fun makeCoordinator(
        scope: TestScope,
        store: SharedStateStore,
        repository: OpenCodeRepository,
        dedupPartRevision: (String, String, String, Long?, TokenFrameCommitContext) -> Boolean = { _, _, _, _, _ -> true },
        onMessagePartRemoved: (String, String, String, Long, TokenFrameCommitContext) -> Unit = { _, _, _, _, _ -> },
        onMessageRemoved: (String, String, TokenFrameCommitContext) -> Unit = { _, _, _ -> },
    ): TokenStreamCoordinator = TokenStreamCoordinator(
        scope = scope,
        slices = store.slices,
        streamProvider = { _, _ -> emptyFlow() },
        triggerSinceFetch = { _, _ -> },
        bundleCommitLock = repository,
        currentBundleProvider = { repository.currentClientBundle() },
        dedupPartRevision = dedupPartRevision,
        onMessagePartRemoved = onMessagePartRemoved,
        onMessageRemoved = onMessageRemoved,
    )

    private fun snapshot(
        partId: String = "p1",
        text: String? = "hello",
        done: Boolean = false,
        truncated: Boolean = false,
        sessionId: String = "s1",
        messageId: String = "m1",
        partEventRevision: Long? = null,
    ) = TokenStreamFrame.PartSnapshot(sessionId, messageId, partId, text, done, truncated, partEventRevision)

    // ── TokenFrameCommitContext threaded verbatim ────────────────────────

    @Test
    fun `dedupPartRevision receives the captured route + bundle context`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val bundle = repository.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))

        val dedupCalls = mutableListOf<DedupCall>()
        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            dedupPartRevision = { sid, mid, pid, rev, ctx ->
                dedupCalls += DedupCall(sid, mid, pid, rev, ctx)
                true
            },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")
        val capturedRoute = 7L
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = snapshot(partEventRevision = 12L),
            capturedRouteInstance = capturedRoute,
            boundBundle = bundle,
        )

        assertEquals(1, dedupCalls.size)
        val call = dedupCalls.single()
        assertEquals("s1", call.sessionId)
        assertEquals("m1", call.messageId)
        assertEquals("p1", call.partId)
        assertEquals(12L, call.partEventRevision)
        assertEquals(capturedRoute, call.context.expectedRouteInstance)
        assertEquals(
            BundleStamp(bundle.generation, bundle.endpointFp),
            call.context.bundleStamp,
        )
    }

    @Test
    fun `onMessagePartRemoved receives the captured route + bundle context`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val bundle = repository.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))

        val partRemovedCalls = mutableListOf<PartRemovedCall>()
        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            onMessagePartRemoved = { sid, mid, pid, seq, ctx ->
                partRemovedCalls += PartRemovedCall(
                    sessionId = sid,
                    messageId = mid,
                    partId = pid,
                    messageEventSeq = seq,
                    context = ctx,
                    streamOwnedKeysAtCall = store.chatFlow.value.streamOwned.keys,
                    streamingTextsKeysAtCall = store.chatFlow.value.streamingPartTexts.keys,
                )
            },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")

        // Stream a part so it exists in the chat-slice overlay before removal.
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = snapshot(partId = "p1", text = "buffered"),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )
        assertTrue(store.chatFlow.value.streamOwned.containsKey("p1"))

        val capturedRoute = 42L
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = TokenStreamFrame.MessagePartRemoved("s1", "m1", "p1", 99L),
            capturedRouteInstance = capturedRoute,
            boundBundle = bundle,
        )

        assertEquals(1, partRemovedCalls.size)
        val call = partRemovedCalls.single()
        assertEquals("s1", call.sessionId)
        assertEquals("m1", call.messageId)
        assertEquals("p1", call.partId)
        assertEquals(99L, call.messageEventSeq)
        assertEquals(capturedRoute, call.context.expectedRouteInstance)
        assertEquals(
            BundleStamp(bundle.generation, bundle.endpointFp),
            call.context.bundleStamp,
        )
    }

    @Test
    fun `onMessageRemoved receives the captured route + bundle context`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val bundle = repository.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))

        val messageRemovedCalls = mutableListOf<MessageRemovedCall>()
        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            onMessageRemoved = { sid, mid, ctx ->
                messageRemovedCalls += MessageRemovedCall(
                    sessionId = sid,
                    messageId = mid,
                    context = ctx,
                    streamOwnedKeysAtCall = store.chatFlow.value.streamOwned.keys,
                    streamingTextsKeysAtCall = store.chatFlow.value.streamingPartTexts.keys,
                )
            },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")

        // Stream a part so the message has owned parts before removal.
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = snapshot(partId = "p1", text = "buffered"),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )
        assertTrue(store.chatFlow.value.streamOwned.containsKey("p1"))

        val capturedRoute = 77L
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = TokenStreamFrame.MessageRemoved("s1", "m1"),
            capturedRouteInstance = capturedRoute,
            boundBundle = bundle,
        )

        assertEquals(1, messageRemovedCalls.size)
        val call = messageRemovedCalls.single()
        assertEquals("s1", call.sessionId)
        assertEquals("m1", call.messageId)
        assertEquals(capturedRoute, call.context.expectedRouteInstance)
        assertEquals(
            BundleStamp(bundle.generation, bundle.endpointFp),
            call.context.bundleStamp,
        )
    }

    // ── dedupPartRevision false → frame dropped ─────────────────────────

    @Test
    fun `dedupPartRevision returning false drops a PartSnapshot frame`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val bundle = repository.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))

        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            dedupPartRevision = { _, _, _, _, _ -> false },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")

        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = snapshot(partId = "p1", text = "dropped"),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )

        // Frame dropped: no chat-slice mutation, no ownership recorded.
        assertFalse(store.chatFlow.value.streamOwned.containsKey("p1"))
        assertFalse(store.chatFlow.value.streamingPartTexts.containsKey("p1"))
        assertTrue(coordinator.ownedPartsForSid("s1").isEmpty())
    }

    @Test
    fun `dedupPartRevision returning false drops a PartDelta frame`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val bundle = repository.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))

        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            dedupPartRevision = { _, _, _, _, _ -> false },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")

        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = TokenStreamFrame.PartDelta("s1", "m1", "p1", "delta-text"),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )

        assertFalse(store.chatFlow.value.streamOwned.containsKey("p1"))
        assertFalse(store.chatFlow.value.streamingPartTexts.containsKey("p1"))
    }

    @Test
    fun `dedupPartRevision returning true proceeds with reducer + bridge`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val bundle = repository.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))

        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            dedupPartRevision = { _, _, _, _, _ -> true },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")

        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = snapshot(partId = "p1", text = "kept"),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )

        // Frame accepted: chat-slice overlay updated.
        assertEquals("kept", store.chatFlow.value.streamingPartTexts["p1"])
        assertTrue(store.chatFlow.value.streamOwned.containsKey("p1"))
    }

    // ── MessagePartRemoved: reducer overlay clear → callback ────────────

    @Test
    fun `MessagePartRemoved fires callback AFTER reducer overlay clear and BEFORE chat-slice clear`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val bundle = repository.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))

        val partRemovedCalls = mutableListOf<PartRemovedCall>()
        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            onMessagePartRemoved = { sid, mid, pid, seq, ctx ->
                partRemovedCalls += PartRemovedCall(
                    sessionId = sid,
                    messageId = mid,
                    partId = pid,
                    messageEventSeq = seq,
                    context = ctx,
                    streamOwnedKeysAtCall = store.chatFlow.value.streamOwned.keys.toSet(),
                    streamingTextsKeysAtCall = store.chatFlow.value.streamingPartTexts.keys.toSet(),
                )
            },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")

        // Stream a part so it exists in BOTH the reducer overlay (private
        // reducerStateBySid) AND the chat-slice overlay (streamOwned /
        // streamingPartTexts). Use capturedRouteInstance=0L (legacy flat
        // path) so the chat reducer's acceptsRouteUpdate accepts the
        // subsequent ClearTokenStreamState dispatch unconditionally.
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = snapshot(partId = "p1", text = "buffered"),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )
        assertTrue(store.chatFlow.value.streamOwned.containsKey("p1"))
        assertTrue(coordinator.ownedPartsForSid("s1").contains("p1"))

        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = TokenStreamFrame.MessagePartRemoved("s1", "m1", "p1", 5L),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )

        // Hook fired exactly once with the right identity.
        assertEquals(1, partRemovedCalls.size)
        val call = partRemovedCalls.single()
        assertEquals("p1", call.partId)
        assertEquals(5L, call.messageEventSeq)
        assertEquals(0L, call.context.expectedRouteInstance)

        // §Stage-B C3 frozen contract: the hook fires INSIDE the epoch+bundle
        // critical section, AFTER the reducer overlay has been cleared
        // (reducerStateBySid[s1].parts no longer contains p1 — proven by the
        // reducer emitting a ClearPartState effect that has NOT yet been
        // processed when the hook runs), and BEFORE the chat-slice clear
        // dispatch lands. We assert the chat-slice still shows the part at
        // callback time (the ClearPartState effect is processed AFTER the
        // hook via handleEffect).
        //
        // The reducer-overlay clear itself is not directly observable
        // (reducerStateBySid is private), but the hook firing proves the
        // reducer ran first (reduce() precedes the hook in dispatchEpochFrame);
        // and the chat-slice clear landing AFTER the hook proves the effect
        // dispatch ordering.
        assertTrue(
            "chat-slice streamOwned must still contain p1 at callback time " +
                "(ClearPartState effect runs AFTER the hook)",
            "p1" in call.streamOwnedKeysAtCall,
        )
        assertTrue(
            "chat-slice streamingPartTexts must still contain p1 at callback time",
            "p1" in call.streamingTextsKeysAtCall,
        )

        // AFTER dispatch returns, the ClearPartState effect has been processed
        // → chat-slice overlay cleared.
        assertFalse(store.chatFlow.value.streamOwned.containsKey("p1"))
        assertFalse(store.chatFlow.value.streamingPartTexts.containsKey("p1"))
    }

    @Test
    fun `MessagePartRemoved for unknown part fires callback but no chat-slice clear`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val bundle = repository.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))

        var hookFired = false
        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            onMessagePartRemoved = { _, _, _, _, _ -> hookFired = true },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")

        // Remove a part that was never streamed.
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = TokenStreamFrame.MessagePartRemoved("s1", "m1", "p-unknown", 1L),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )

        // Hook still fires (watermark mutation in production is independent
        // of whether the reducer had an overlay entry).
        assertTrue(hookFired)
        // No chat-slice mutation (no prior overlay to clear).
        assertTrue(store.chatFlow.value.streamOwned.isEmpty())
    }

    // ── MessageRemoved: reducer overlay clear → callback ────────────────

    @Test
    fun `MessageRemoved fires callback AFTER reducer overlay clear and BEFORE chat-slice clear`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val bundle = repository.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))

        val messageRemovedCalls = mutableListOf<MessageRemovedCall>()
        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            onMessageRemoved = { sid, mid, ctx ->
                messageRemovedCalls += MessageRemovedCall(
                    sessionId = sid,
                    messageId = mid,
                    context = ctx,
                    streamOwnedKeysAtCall = store.chatFlow.value.streamOwned.keys.toSet(),
                    streamingTextsKeysAtCall = store.chatFlow.value.streamingPartTexts.keys.toSet(),
                )
            },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")

        // Stream two parts for the same message so removal clears both.
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = snapshot(partId = "p1", text = "a"),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = snapshot(partId = "p2", text = "b"),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )
        assertEquals(2, store.chatFlow.value.streamOwned.size)

        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = TokenStreamFrame.MessageRemoved("s1", "m1"),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )

        assertEquals(1, messageRemovedCalls.size)
        val call = messageRemovedCalls.single()
        assertEquals("m1", call.messageId)
        assertEquals(0L, call.context.expectedRouteInstance)
        assertEquals(
            BundleStamp(bundle.generation, bundle.endpointFp),
            call.context.bundleStamp,
        )

        // Same ordering contract as MessagePartRemoved: chat-slice overlay
        // still present at callback time, cleared AFTER dispatch returns.
        assertTrue(
            "chat-slice streamOwned must still contain parts at callback time",
            "p1" in call.streamOwnedKeysAtCall && "p2" in call.streamOwnedKeysAtCall,
        )
        assertTrue(
            "chat-slice streamingPartTexts must still contain parts at callback time",
            "p1" in call.streamingTextsKeysAtCall && "p2" in call.streamingTextsKeysAtCall,
        )

        // AFTER dispatch returns: chat-slice overlay cleared.
        assertFalse(store.chatFlow.value.streamOwned.containsKey("p1"))
        assertFalse(store.chatFlow.value.streamOwned.containsKey("p2"))
        assertFalse(store.chatFlow.value.streamingPartTexts.containsKey("p1"))
        assertFalse(store.chatFlow.value.streamingPartTexts.containsKey("p2"))
    }

    @Test
    fun `MessageRemoved for unknown message fires callback but no chat-slice clear`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val bundle = repository.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))

        var hookFired = false
        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            onMessageRemoved = { _, _, _ -> hookFired = true },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")

        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = TokenStreamFrame.MessageRemoved("s1", "m-unknown"),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )

        assertTrue(hookFired)
        assertTrue(store.chatFlow.value.streamOwned.isEmpty())
    }

    // ── Default hooks (no-op) preserve prior behavior ───────────────────

    @Test
    fun `default hooks are no-ops and do not interfere with frame processing`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val bundle = repository.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))

        // No hook overrides — all three default to no-op / accept-all.
        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")

        // PartSnapshot accepted (default dedup = true).
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = snapshot(partId = "p1", text = "buffered"),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )
        assertEquals("buffered", store.chatFlow.value.streamingPartTexts["p1"])

        // MessagePartRemoved: no-op hook, but reducer still clears overlay.
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = TokenStreamFrame.MessagePartRemoved("s1", "m1", "p1", 1L),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )
        assertFalse(store.chatFlow.value.streamOwned.containsKey("p1"))

        // Stream again, then MessageRemoved: no-op hook, reducer clears.
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = snapshot(partId = "p2", text = "again"),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )
        assertTrue(store.chatFlow.value.streamOwned.containsKey("p2"))
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = TokenStreamFrame.MessageRemoved("s1", "m1"),
            capturedRouteInstance = 0L,
            boundBundle = bundle,
        )
        assertFalse(store.chatFlow.value.streamOwned.containsKey("p2"))
    }

    // ── Bundle guard inside the critical section ────────────────────────

    @Test
    fun `hooks do not fire when bound bundle is no longer current`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        repository.configure(baseUrl = "http://host-a.test", slim = true)
        val bundleA = repository.currentClientBundle()!!
        repository.configure(baseUrl = "http://host-b.test", slim = true)
        val bundleB = repository.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundleB.generation, bundleB.endpointFp))

        var dedupCalled = false
        var partRemovedCalled = false
        var messageRemovedCalled = false
        val coordinator = makeCoordinator(
            scope = scope,
            store = store,
            repository = repository,
            dedupPartRevision = { _, _, _, _, _ -> dedupCalled = true; true },
            onMessagePartRemoved = { _, _, _, _, _ -> partRemovedCalled = true },
            onMessageRemoved = { _, _, _ -> messageRemovedCalled = true },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")

        // Dispatch with the RETIRED bundle A — bundle guard rejects before
        // any hook fires.
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = snapshot(partId = "p1", text = "stale"),
            capturedRouteInstance = 0L,
            boundBundle = bundleA,
        )
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = TokenStreamFrame.MessagePartRemoved("s1", "m1", "p1", 1L),
            capturedRouteInstance = 0L,
            boundBundle = bundleA,
        )
        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = gen,
            frame = TokenStreamFrame.MessageRemoved("s1", "m1"),
            capturedRouteInstance = 0L,
            boundBundle = bundleA,
        )

        assertFalse("dedup hook must not fire for retired bundle", dedupCalled)
        assertFalse("part-removed hook must not fire for retired bundle", partRemovedCalled)
        assertFalse("message-removed hook must not fire for retired bundle", messageRemovedCalled)
        // State is untouched.
        assertTrue(store.chatFlow.value.streamOwned.isEmpty())
        assertNotNull(repository.currentClientBundle())
    }

    @Test
    fun `TokenFrameCommitContext is a public data class with expected fields`() {
        val ctx = TokenFrameCommitContext(
            expectedRouteInstance = 99L,
            bundleStamp = BundleStamp(7L, "http://host.test"),
        )
        assertEquals(99L, ctx.expectedRouteInstance)
        assertEquals(BundleStamp(7L, "http://host.test"), ctx.bundleStamp)
        // copy() / equality are data-class guarantees Lane I relies on for
        // destructuring when forwarding into AppAction.MessageRemovedConfirmed.
        val copy = ctx.copy()
        assertEquals(ctx, copy)
        assertEquals(ctx.hashCode(), copy.hashCode())
        assertNull(ctx.toString().takeIf { it.isBlank() })
    }
}
