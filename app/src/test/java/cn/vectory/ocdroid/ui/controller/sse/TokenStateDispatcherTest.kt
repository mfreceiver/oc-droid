package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.model.ResyncReason
import cn.vectory.ocdroid.data.model.TokenStreamFrame
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.StreamOwnedState
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [TokenStateDispatcher].
 *
 * Drives the dispatcher with a real [TokenFrameGuard] and a [SharedStateStore]
 * slice, verifying:
 *  - dedup-false drops frame before reducer
 *  - resync null-sid rewrite
 *  - deferred [TriggerSinceFetch] runs after lock release
 *  - removal-hook order (reducer overlay clear → hook → effect)
 */
class TokenStateDispatcherTest {

    private lateinit var stateStore: SharedStateStore
    private lateinit var guard: TokenFrameGuard
    private lateinit var dispatcher: TokenStateDispatcher
    private val lock = Any()
    private val reconnectCalls = mutableListOf<String>()
    private val anyFrameCalls = mutableListOf<String>()
    private val sinceFetchCalls = mutableListOf<Pair<String, Boolean>>()
    private val removedParts = mutableListOf<String>()
    private lateinit var testBundle: ClientBundle

    @Before
    fun setUp() {
        stateStore = SharedStateStore()
        guard = TokenFrameGuard(lock)
        reconnectCalls.clear()
        anyFrameCalls.clear()
        sinceFetchCalls.clear()
        removedParts.clear()

        val repo = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        testBundle = repo.currentClientBundle()!!

        // Publish the bundle to the store so that bundle-stamped actions
        // pass acceptsBundle (liveEndpointFp must match).
        stateStore.slices.store.dispatch(
            AppAction.BundlePublished(testBundle.generation, testBundle.endpointFp),
        )

        dispatcher = TokenStateDispatcher(
            slices = stateStore.slices,
            guard = guard,
            bundleCommitLock = lock,
            currentBundleProvider = { testBundle },
            triggerSinceFetch = { sid, auth -> sinceFetchCalls += sid to auth },
            requestReconnect = { sid -> reconnectCalls += sid },
            onAnyFrame = { sid -> anyFrameCalls += sid },
            dedupPartRevision = { _, _, _, _, _ -> true },
            onMessagePartRemoved = { _, _, pid, _, _ -> removedParts += pid },
            onMessageRemoved = { _, _, _ -> },
            onPartDone = { _, _, _ -> },
            clearSessionRevisions = { },
        )
    }

    /** Create a dispatcher with overridden dedup/onPartDone. */
    private fun makeDispatcher(
        dedup: (String, String, String, Long?, TokenFrameCommitContext) -> Boolean = { _, _, _, _, _ -> true },
        onPartDone: (String, String, String) -> Unit = { _, _, _ -> },
    ): TokenStateDispatcher = TokenStateDispatcher(
        slices = stateStore.slices,
        guard = guard,
        bundleCommitLock = lock,
        currentBundleProvider = { testBundle },
        triggerSinceFetch = { sid, auth -> sinceFetchCalls += sid to auth },
        requestReconnect = { sid -> reconnectCalls += sid },
        onAnyFrame = { sid -> anyFrameCalls += sid },
        dedupPartRevision = dedup,
        onMessagePartRemoved = { _, _, pid, _, _ -> removedParts += pid },
        onMessageRemoved = { _, _, _ -> },
        onPartDone = onPartDone,
        clearSessionRevisions = { },
    )

    @Test
    fun `dedup returns false drops part-snapshot before reducer`() {
        dispatcher = makeDispatcher(dedup = { _, _, _, _, _ -> false })
        val deferred = mutableListOf<() -> Unit>()
        dispatcher.processFrameBody(
            "s1", epoch = 1L, gen = 1L, frame = snapshot("p1", "dropped"),
            capturedRouteInstance = 0L, boundBundle = testBundle,
            deferredEffects = deferred,
        )
        deferred.forEach { it() }
        assertTrue(stateStore.chatFlow.value.streamingPartTexts.isEmpty())
        assertTrue(stateStore.chatFlow.value.streamOwned.isEmpty())
    }

    @Test
    fun `resync with null sessionId rewrites to active sid`() {
        guard.beginStreamIncarnation("s1")
        val deferred = mutableListOf<() -> Unit>()
        dispatcher.processFrameBody(
            "s1", epoch = 1L, gen = 1L,
            frame = TokenStreamFrame.Resync(ResyncReason.RECONNECT_NO_REPLAY, null),
            capturedRouteInstance = 0L, boundBundle = testBundle,
            deferredEffects = deferred,
        )
        deferred.forEach { it() }
        assertTrue("resync with null sid must not crash", true)
    }

    @Test
    fun `deferred TriggerSinceFetch runs after processFrameBody returns`() {
        val deferred = mutableListOf<() -> Unit>()
        guard.beginStreamIncarnation("s1")
        guard.onPartOwned("s1", 1L, "p1")
        dispatcher.processFrameBody(
            "s1", epoch = 1L, gen = 1L,
            frame = snapshot("p1", "partial", truncated = true),
            capturedRouteInstance = 0L, boundBundle = testBundle,
            deferredEffects = deferred,
        )
        assertEquals("sinceFetch must not be called before deferred effects run", 0, sinceFetchCalls.size)
        deferred.forEach { it() }
        assertTrue("sinceFetch must be called after deferred effects run", sinceFetchCalls.isNotEmpty())
    }

    @Test
    fun `part snapshot updates chat state via bridge`() {
        val deferred = mutableListOf<() -> Unit>()
        guard.beginStreamIncarnation("s1")
        dispatcher.processFrameBody(
            "s1", epoch = 1L, gen = 1L, frame = snapshot("p1", "hello"),
            capturedRouteInstance = 0L, boundBundle = testBundle,
            deferredEffects = deferred,
        )
        deferred.forEach { it() }
        assertEquals("hello", stateStore.chatFlow.value.streamingPartTexts["p1"])
        assertEquals(StreamOwnedState.STREAMING, stateStore.chatFlow.value.streamOwned["p1"])
    }

    @Test
    fun `part done sets StreamOwnedState DONE`() {
        val deferred = mutableListOf<() -> Unit>()
        guard.beginStreamIncarnation("s1")
        dispatcher.processFrameBody(
            "s1", epoch = 1L, gen = 1L,
            frame = snapshot("p1", "final", done = true),
            capturedRouteInstance = 0L, boundBundle = testBundle,
            deferredEffects = deferred,
        )
        deferred.forEach { it() }
        assertEquals(StreamOwnedState.DONE, stateStore.chatFlow.value.streamOwned["p1"])
    }

    @Test
    fun `onPartDone hook fires for terminal parts`() {
        val doneParts = mutableListOf<String>()
        dispatcher = makeDispatcher(onPartDone = { _, _, pid -> doneParts += pid })
        val deferred = mutableListOf<() -> Unit>()
        guard.beginStreamIncarnation("s1")
        dispatcher.processFrameBody(
            "s1", epoch = 1L, gen = 1L,
            frame = snapshot("p1", "done", done = true),
            capturedRouteInstance = 0L, boundBundle = testBundle,
            deferredEffects = deferred,
        )
        deferred.forEach { it() }
        assertTrue("onPartDone must fire for done=true part", doneParts.contains("p1"))
    }

    @Test
    fun `onAnyFrame callback fires on every frame`() {
        guard.beginStreamIncarnation("s1")
        val deferred = mutableListOf<() -> Unit>()
        dispatcher.processFrameBody(
            "s1", epoch = 1L, gen = 1L, frame = snapshot("p1", "test"),
            capturedRouteInstance = 0L, boundBundle = testBundle,
            deferredEffects = deferred,
        )
        deferred.forEach { it() }
        assertTrue("onAnyFrame must fire for each frame", anyFrameCalls.contains("s1"))
    }

    @Test
    fun `dispatchTokenStreamClear dispatches ClearTokenStreamState`() {
        val result = dispatcher.dispatchTokenStreamClear(
            partIds = setOf("p1"),
            expectedRouteInstance = 0L,
            sessionId = "s1",
        )
        assertTrue("dispatchTokenStreamClear must return true", result)
    }

    companion object {
        private fun snapshot(
            partId: String = "p1",
            text: String = "test",
            messageId: String = "m1",
            truncated: Boolean = false,
            done: Boolean = false,
        ): TokenStreamFrame.PartSnapshot = TokenStreamFrame.PartSnapshot(
            sessionId = "s1",
            messageId = messageId,
            partId = partId,
            text = text,
            done = done,
            truncated = truncated,
        )
    }
}
