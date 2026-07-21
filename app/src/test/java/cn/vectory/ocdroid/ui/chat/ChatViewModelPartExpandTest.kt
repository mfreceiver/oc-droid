package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.MainViewModelTestBase
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.ExpandOutcome
import cn.vectory.ocdroid.ui.ChatViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T16 round-2: real-ViewModel harness tests for the expand wiring.
 *
 * Uses [MainViewModelTestBase.createCore] → real [ChatViewModel] → real
 * [SharedStateStore] → controlled [CompletableDeferred<ExpandOutcome>]
 * responses. Unlike [PartExpandWiringTest] (which uses local maps), these
 * tests drive the production reducer and can detect regressions like
 * automatic terminal→Idle cleanup in SSE reducers.
 *
 * # Transitions pinned (6)
 *  - A: Idle → Loading on first dispatch
 *  - B: Loading → Loaded on completion
 *  - C: Loading → Failed(code) on completion
 *  - D: Failed → Loading only on explicit retry
 *  - E: Loaded survives unrelated SSE mutation
 *  - F: Failed survives unrelated SSE mutation
 *
 * # C1 race tests (5)
 *  - Two rows concurrent
 *  - SSE interleave
 *  - Session switch drops result
 *  - Host change drops result
 *  - Duplicate tap suppressed
 */
class ChatViewModelPartExpandTest : MainViewModelTestBase() {

    private lateinit var chatVM: ChatViewModel

    private val expandDeferred = CompletableDeferred<ExpandOutcome>()

    private fun skeleton(partId: String, msgId: String, text: String? = "skeleton") =
        Part(
            id = partId,
            messageId = msgId,
            type = "text",
            text = text,
            hasFull = true,
            omitted = listOf("tool"),
        )

    private fun msg(id: String, parts: List<Part>) =
        MessageWithParts(Message(id = id, role = "assistant"), parts)

    private fun seedSession(sessionId: String, message: Message, parts: List<Part>) {
        core.writeChat { state ->
            state.copy(
                currentSessionId = sessionId,
                messages = listOf(message),
                partsByMessage = mapOf(message.id to parts),
            )
        }
    }

    @Before
    override fun setUp() {
        super.setUp()
        newCore()
        chatVM = ChatViewModel(core)

        // Wire the expand call to our controlled deferred.
        coEvery { repository.expandMessagesFullBatch(any(), any()) } coAnswers {
            expandDeferred.await()
        }
    }

    // ── Transition pins ───────────────────────────────────────────────────

    /**
     * A: Idle → Loading on first dispatch.
     * Seed session, call expandParts, runCurrent, assert Loading landed.
     */
    @Test
    fun `A - Idle to Loading on first dispatch`() = runTest {
        val s1 = "session-1"
        val p1 = skeleton("p1", "m1")
        seedSession(s1, Message(id = "m1", role = "assistant"), listOf(p1))

        chatVM.expandParts(s1, listOf(p1))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        val states = core.store.chatFlow.value.partExpandStates
        assertEquals(PartExpandState.Loading, states[PartKey("m1", "p1")])
        coVerify(exactly = 1) { repository.expandMessagesFullBatch(s1, setOf("m1")) }
    }

    /**
     * B: Loading → Loaded on completion.
     * Continue from A by completing the deferred with Ok.
     */
    @Test
    fun `B - Loading to Loaded on completion`() = runTest {
        val s1 = "session-1"
        val p1 = skeleton("p1", "m1")
        seedSession(s1, Message(id = "m1", role = "assistant"), listOf(p1))

        chatVM.expandParts(s1, listOf(p1))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        // Complete the deferred.
        val fullPart = Part(id = "p1", messageId = "m1", type = "text", text = "FULL")
        expandDeferred.complete(ExpandOutcome.Ok(
            items = listOf(msg("m1", listOf(fullPart))),
            failures = emptyList(),
            usedBatch = true,
        ))
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        val state = core.store.chatFlow.value
        assertEquals(PartExpandState.Loaded, state.partExpandStates[PartKey("m1", "p1")])
        // Part content was replaced.
        assertEquals("FULL", state.partsByMessage["m1"]?.first { it.id == "p1" }?.text)
    }

    /**
     * C: Loading → Failed(code) on completion.
     */
    @Test
    fun `C - Loading to Failed with code`() = runTest {
        val s1 = "session-1"
        val p1 = skeleton("p1", "m1")
        seedSession(s1, Message(id = "m1", role = "assistant"), listOf(p1))

        chatVM.expandParts(s1, listOf(p1))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        expandDeferred.complete(ExpandOutcome.Failed(sessionId = s1, code = "upstream_unavailable"))
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            PartExpandState.Failed(code = "upstream_unavailable"),
            core.store.chatFlow.value.partExpandStates[PartKey("m1", "p1")],
        )
    }

    /**
     * D: Failed → Loading only on explicit retry.
     * Establish Failed through real expandParts, leave state, assert still Failed.
     * Invoke again, assert Loading.
     */
    @Test
    fun `D - Failed to Loading only on explicit retry`() = runTest {
        val s1 = "session-1"
        val p1 = skeleton("p1", "m1")
        seedSession(s1, Message(id = "m1", role = "assistant"), listOf(p1))

        // First call → Failed.
        chatVM.expandParts(s1, listOf(p1))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        expandDeferred.complete(ExpandOutcome.Failed(sessionId = s1, code = "network"))
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            PartExpandState.Failed(code = "network"),
            core.store.chatFlow.value.partExpandStates[PartKey("m1", "p1")],
        )

        // Second call (retry) → Loading.
        val expandDeferred2 = CompletableDeferred<ExpandOutcome>()
        coEvery { repository.expandMessagesFullBatch(any(), any()) } coAnswers {
            expandDeferred2.await()
        }
        chatVM.expandParts(s1, listOf(p1))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertEquals(
            PartExpandState.Loading,
            core.store.chatFlow.value.partExpandStates[PartKey("m1", "p1")],
        )
        expandDeferred2.cancel()
    }

    /**
     * E: Loaded survives real message.updated SSE reducer.
     * P8: drives production SessionSyncCoordinator via handleSse.
     */
    @Test
    fun `E - Loaded survives real message updated SSE reducer`() = runTest {
        val sessionId = "session-1"
        val key = PartKey("m1", "p1")
        val skeleton = skeleton("p1", "m1")

        seedSession(
            sessionId,
            Message(id = "m1", role = "assistant", agent = "before"),
            listOf(skeleton),
        )

        chatVM.expandParts(sessionId, listOf(skeleton))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        val fullPart = Part(
            id = "p1",
            messageId = "m1",
            type = "text",
            text = "FULL",
        )
        expandDeferred.complete(
            ExpandOutcome.Ok(
                items = listOf(msg("m1", listOf(fullPart))),
                failures = emptyList(),
                usedBatch = true,
            )
        )
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            PartExpandState.Loaded,
            core.store.chatFlow.value.partExpandStates[key],
        )

        // Production route: MainViewModelTestBase.handleSse →
        // AppCore.handleSSEEvent → SessionSyncCoordinator message.updated.
        handleSse(
            core,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive(sessionId))
                        put(
                            "info",
                            buildJsonObject {
                                put("id", JsonPrimitive("m1"))
                                put("sessionID", JsonPrimitive(sessionId))
                                put("role", JsonPrimitive("assistant"))
                                put("agent", JsonPrimitive("after-sse"))
                            }
                        )
                    },
                )
            )
        )
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        // Prove the production SSE reducer actually ran.
        assertEquals(
            "after-sse",
            core.store.chatFlow.value.messages.single { it.id == "m1" }.agent,
        )
        assertEquals(
            "Loaded must survive the production SSE reducer",
            PartExpandState.Loaded,
            core.store.chatFlow.value.partExpandStates[key],
        )
    }

    /**
     * F: Failed survives real message.updated SSE reducer.
     * P8: drives production SessionSyncCoordinator via handleSse.
     */
    @Test
    fun `F - Failed survives real message updated SSE reducer`() = runTest {
        val sessionId = "session-1"
        val key = PartKey("m1", "p1")
        val skeleton = skeleton("p1", "m1")

        seedSession(
            sessionId,
            Message(id = "m1", role = "assistant", agent = "before"),
            listOf(skeleton),
        )

        chatVM.expandParts(sessionId, listOf(skeleton))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        expandDeferred.complete(
            ExpandOutcome.Failed(
                sessionId = sessionId,
                code = "timeout",
            )
        )
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            PartExpandState.Failed(code = "timeout"),
            core.store.chatFlow.value.partExpandStates[key],
        )

        handleSse(
            core,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive(sessionId))
                        put(
                            "info",
                            buildJsonObject {
                                put("id", JsonPrimitive("m1"))
                                put("sessionID", JsonPrimitive(sessionId))
                                put("role", JsonPrimitive("assistant"))
                                put("agent", JsonPrimitive("after-sse"))
                            }
                        )
                    },
                )
            )
        )
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        // Prove this was not a handwritten no-op mutation.
        assertEquals(
            "after-sse",
            core.store.chatFlow.value.messages.single { it.id == "m1" }.agent,
        )
        assertEquals(
            "Failed must survive the production SSE reducer",
            PartExpandState.Failed(code = "timeout"),
            core.store.chatFlow.value.partExpandStates[key],
        )
    }

    // ── C1 race tests ─────────────────────────────────────────────────────

    /**
     * C1 race 1: Two rows concurrently — m1 and m2 expand from same initial
     * state. Complete m1, then m2. Both full replacements survive.
     */
    @Test
    fun `C1 - two rows concurrent - both full replacements survive`() = runTest {
        val s1 = "session-1"
        val p1 = skeleton("p1", "m1")
        val p2 = skeleton("p2", "m2")
        seedSession(s1, Message(id = "m1", role = "assistant"), listOf(p1))
        // Add m2 to the cache.
        core.writeChat { state ->
            state.copy(
                messages = state.messages + Message(id = "m2", role = "assistant"),
                partsByMessage = state.partsByMessage + ("m2" to listOf(p2)),
            )
        }

        val deferred1 = CompletableDeferred<ExpandOutcome>()
        val deferred2 = CompletableDeferred<ExpandOutcome>()
        var callCount = 0
        coEvery { repository.expandMessagesFullBatch(any(), any()) } coAnswers {
            callCount++
            if (callCount == 1) deferred1.await() else deferred2.await()
        }

        // Dispatch both.
        chatVM.expandParts(s1, listOf(p1))
        chatVM.expandParts(s1, listOf(p2))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        // Both should be Loading.
        val states1 = core.store.chatFlow.value.partExpandStates
        assertEquals(PartExpandState.Loading, states1[PartKey("m1", "p1")])
        assertEquals(PartExpandState.Loading, states1[PartKey("m2", "p2")])

        // Complete m1.
        deferred1.complete(ExpandOutcome.Ok(
            items = listOf(msg("m1", listOf(Part(id = "p1", messageId = "m1", type = "text", text = "FULL1")))),
            failures = emptyList(),
            usedBatch = true,
        ))
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        // Complete m2.
        deferred2.complete(ExpandOutcome.Ok(
            items = listOf(msg("m2", listOf(Part(id = "p2", messageId = "m2", type = "text", text = "FULL2")))),
            failures = emptyList(),
            usedBatch = true,
        ))
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        val finalState = core.store.chatFlow.value
        assertEquals(PartExpandState.Loaded, finalState.partExpandStates[PartKey("m1", "p1")])
        assertEquals(PartExpandState.Loaded, finalState.partExpandStates[PartKey("m2", "p2")])
        assertEquals("FULL1", finalState.partsByMessage["m1"]?.first { it.id == "p1" }?.text)
        assertEquals("FULL2", finalState.partsByMessage["m2"]?.first { it.id == "p2" }?.text)
    }

    /**
     * C1 race 2: SSE interleave — start expand of p1, apply SSE update to
     * p2 in same message, complete expand. p1 is full, p2 retains SSE value.
     */
    @Test
    fun `C1 - SSE interleave - p1 full, p2 retains SSE value`() = runTest {
        val s1 = "session-1"
        val p1 = skeleton("p1", "m1")
        val p2 = Part(id = "p2", messageId = "m1", type = "text", text = "original p2")
        seedSession(s1, Message(id = "m1", role = "assistant"), listOf(p1, p2))

        val deferred = CompletableDeferred<ExpandOutcome>()
        coEvery { repository.expandMessagesFullBatch(any(), any()) } coAnswers {
            deferred.await()
        }

        chatVM.expandParts(s1, listOf(p1))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        // Simulate SSE update to p2 while request is suspended.
        core.writeChat { state ->
            val currentParts = state.partsByMessage["m1"] ?: emptyList()
            val updated = currentParts.map { part ->
                if (part.id == "p2") part.copy(text = "SSE updated p2") else part
            }
            state.copy(partsByMessage = state.partsByMessage + ("m1" to updated))
        }
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        // Complete the expand.
        deferred.complete(ExpandOutcome.Ok(
            items = listOf(msg("m1", listOf(
                Part(id = "p1", messageId = "m1", type = "text", text = "FULL p1"),
            ))),
            failures = emptyList(),
            usedBatch = true,
        ))
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        val finalState = core.store.chatFlow.value
        assertEquals(PartExpandState.Loaded, finalState.partExpandStates[PartKey("m1", "p1")])
        assertEquals("FULL p1", finalState.partsByMessage["m1"]?.first { it.id == "p1" }?.text)
        assertEquals("SSE updated p2", finalState.partsByMessage["m1"]?.first { it.id == "p2" }?.text)
    }

    /**
     * C1 race 3: Session switch drops result.
     * Start expand in s1, switch to s2, complete s1 response.
     * Assert s2 is unchanged and no s1 terminal state leaks.
     */
    @Test
    fun `C1 - session switch drops result`() = runTest {
        val s1 = "session-1"
        val s2 = "session-2"
        val p1 = skeleton("p1", "m1")
        seedSession(s1, Message(id = "m1", role = "assistant"), listOf(p1))

        val deferred = CompletableDeferred<ExpandOutcome>()
        coEvery { repository.expandMessagesFullBatch(any(), any()) } coAnswers {
            deferred.await()
        }

        chatVM.expandParts(s1, listOf(p1))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertEquals(PartExpandState.Loading, core.store.chatFlow.value.partExpandStates[PartKey("m1", "p1")])

        // Switch to s2 — this clears partExpandStates atomically.
        core.sessionSwitcher.switchTo(s2)
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        // Complete the s1 response.
        deferred.complete(ExpandOutcome.Ok(
            items = listOf(msg("m1", listOf(Part(id = "p1", messageId = "m1", type = "text", text = "FULL")))),
            failures = emptyList(),
            usedBatch = true,
        ))
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        // s2 must not contain any s1 state.
        val state = core.store.chatFlow.value
        assertEquals(s2, state.currentSessionId)
        assertTrue("No s1 terminal state leaks into s2", state.partExpandStates.isEmpty())
    }

    /**
     * C1 race 4: Host change drops result.
     * Capture under fingerprint A, switch to fingerprint B, complete.
     * No cache or terminal state committed.
     */
    @Test
    fun `C1 - host change drops result`() = runTest {
        val s1 = "session-1"
        val p1 = skeleton("p1", "m1")
        seedSession(s1, Message(id = "m1", role = "assistant"), listOf(p1))

        val deferred = CompletableDeferred<ExpandOutcome>()
        coEvery { repository.expandMessagesFullBatch(any(), any()) } coAnswers {
            deferred.await()
        }

        chatVM.expandParts(s1, listOf(p1))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        // Simulate host change by updating the fp provider.
        val newProfile = cn.vectory.ocdroid.data.model.HostProfile.defaultDirect("http://server2.test")
        every { hostProfileStore.currentProfile() } returns newProfile
        every { hostProfileStore.profiles() } returns listOf(newProfile)

        // Complete with old fingerprint.
        deferred.complete(ExpandOutcome.Ok(
            items = listOf(msg("m1", listOf(Part(id = "p1", messageId = "m1", type = "text", text = "FULL")))),
            failures = emptyList(),
            usedBatch = true,
        ))
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        // Fingerprint mismatch → result dropped.
        // The partExpandStates should not contain Loaded for the old key
        // (it may or may not contain Loading, depending on whether the
        // session check dropped it first).
        val state = core.store.chatFlow.value
        val keyState = state.partExpandStates[PartKey("m1", "p1")]
        assertTrue(
            "Host change must not commit Loaded",
            keyState !is PartExpandState.Loaded,
        )
    }

    /**
     * C1 race 5: Duplicate tap suppressed.
     * Dispatch the same key twice before recomposition. One repository call,
     * one Loading→terminal sequence.
     */
    @Test
    fun `C1 - duplicate tap suppressed`() = runTest {
        val s1 = "session-1"
        val p1 = skeleton("p1", "m1")
        seedSession(s1, Message(id = "m1", role = "assistant"), listOf(p1))

        val deferred = CompletableDeferred<ExpandOutcome>()
        coEvery { repository.expandMessagesFullBatch(any(), any()) } coAnswers {
            deferred.await()
        }

        // Dispatch twice before completing.
        chatVM.expandParts(s1, listOf(p1))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        chatVM.expandParts(s1, listOf(p1))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        // Only one repository call.
        coVerify(exactly = 1) { repository.expandMessagesFullBatch(s1, setOf("m1")) }

        // Complete.
        deferred.complete(ExpandOutcome.Ok(
            items = listOf(msg("m1", listOf(Part(id = "p1", messageId = "m1", type = "text", text = "FULL")))),
            failures = emptyList(),
            usedBatch = true,
        ))
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(PartExpandState.Loaded, core.store.chatFlow.value.partExpandStates[PartKey("m1", "p1")])
    }

    // ── P9: delayed-completion discriminators ──────────────────────────────

    /**
     * P9a: Part disappears before completion → commits Failed(null), not Loaded.
     */
    @Test
    fun `C1 - part disappears before completion commits Failed null`() = runTest {
        val sessionId = "session-1"
        val key = PartKey("m1", "p1")
        val skeleton = skeleton("p1", "m1")
        val deferred = CompletableDeferred<ExpandOutcome>()

        coEvery {
            repository.expandMessagesFullBatch(sessionId, setOf("m1"))
        } coAnswers {
            deferred.await()
        }

        seedSession(
            sessionId,
            Message(id = "m1", role = "assistant"),
            listOf(skeleton),
        )

        chatVM.expandParts(sessionId, listOf(skeleton))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertEquals(
            PartExpandState.Loading,
            core.store.chatFlow.value.partExpandStates[key],
        )

        // A fresh reducer removed the target before the old response completed.
        core.writeChat { current ->
            current.copy(
                partsByMessage = current.partsByMessage + ("m1" to emptyList()),
            )
        }

        deferred.complete(
            ExpandOutcome.Ok(
                items = listOf(
                    msg(
                        "m1",
                        listOf(
                            Part(
                                id = "p1",
                                messageId = "m1",
                                type = "text",
                                text = "STALE FULL",
                            )
                        ),
                    )
                ),
                failures = emptyList(),
                usedBatch = true,
            )
        )
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        val finalState = core.store.chatFlow.value
        assertEquals(
            PartExpandState.Failed(code = null),
            finalState.partExpandStates[key],
        )
        assertTrue(finalState.partsByMessage["m1"].orEmpty().none { it.id == "p1" })
    }

    /**
     * P9b: Newer terminal blocks old successful completion (cache + state).
     */
    @Test
    fun `C1 - newer terminal blocks old success cache and state`() = runTest {
        val sessionId = "session-1"
        val key = PartKey("m1", "p1")
        val skeleton = skeleton("p1", "m1")
        val deferred = CompletableDeferred<ExpandOutcome>()

        coEvery {
            repository.expandMessagesFullBatch(sessionId, setOf("m1"))
        } coAnswers {
            deferred.await()
        }

        seedSession(
            sessionId,
            Message(id = "m1", role = "assistant"),
            listOf(skeleton),
        )

        chatVM.expandParts(sessionId, listOf(skeleton))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        val newerPart = skeleton.copy(text = "NEWER CACHE")
        val newerTerminal = PartExpandState.Failed(code = "newer-result")

        core.writeChat { current ->
            current.copy(
                partsByMessage =
                    current.partsByMessage + ("m1" to listOf(newerPart)),
                partExpandStates =
                    current.partExpandStates + (key to newerTerminal),
            )
        }

        deferred.complete(
            ExpandOutcome.Ok(
                items = listOf(
                    msg(
                        "m1",
                        listOf(
                            Part(
                                id = "p1",
                                messageId = "m1",
                                type = "text",
                                text = "OLD FULL",
                            )
                        ),
                    )
                ),
                failures = emptyList(),
                usedBatch = true,
            )
        )
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        val finalState = core.store.chatFlow.value
        assertEquals(newerTerminal, finalState.partExpandStates[key])
        assertEquals(
            "NEWER CACHE",
            finalState.partsByMessage["m1"]?.single { it.id == "p1" }?.text,
        )
    }

    /**
     * P9c: Delayed Result.failure does not overwrite newer terminal.
     */
    @Test
    fun `C1 - delayed Result failure does not overwrite newer terminal`() = runTest {
        val sessionId = "session-1"
        val key = PartKey("m1", "p1")
        val skeleton = skeleton("p1", "m1")
        val releaseFailure = CompletableDeferred<Unit>()

        coEvery {
            repository.expandMessagesFullBatch(sessionId, setOf("m1"))
        } coAnswers {
            releaseFailure.await()
            throw IOException("delayed failure")
        }

        seedSession(
            sessionId,
            Message(id = "m1", role = "assistant"),
            listOf(skeleton),
        )

        chatVM.expandParts(sessionId, listOf(skeleton))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertEquals(
            PartExpandState.Loading,
            core.store.chatFlow.value.partExpandStates[key],
        )

        val newerFull = Part(
            id = "p1",
            messageId = "m1",
            type = "text",
            text = "NEWER FULL",
        )
        core.writeChat { current ->
            current.copy(
                partsByMessage =
                    current.partsByMessage + ("m1" to listOf(newerFull)),
                partExpandStates =
                    current.partExpandStates + (key to PartExpandState.Loaded),
            )
        }

        releaseFailure.complete(Unit)
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        val finalState = core.store.chatFlow.value
        assertEquals(PartExpandState.Loaded, finalState.partExpandStates[key])
        assertEquals(
            "NEWER FULL",
            finalState.partsByMessage["m1"]?.single { it.id == "p1" }?.text,
        )
    }
}
