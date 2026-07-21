package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.MainViewModelTestBase
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.ExpandOutcome
import cn.vectory.ocdroid.data.repository.isThinPlaceholder
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
     * P9a: Commit-failure visibility contract — when the owner/live slice is
     * unavailable at commit time (here: removed entirely so
     * `currentParts == null`), the reducer CANNOT reconcile the fetched
     * content into the live partsByMessage. A successful network fetch
     * (owner item present) does NOT by itself justify Loaded — that would
     * hide the affordance while the content is invisible. The key must
     * keep retry visible (`Failed(null)`), NOT Loaded.
     */
    @Test
    fun `C1 - commit failure when owner slice unavailable keeps retry visible`() = runTest {
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

        // Remove the owner slice ENTIRELY before the old response completes,
        // so current.partsByMessage["m1"] == null at commit time.
        core.writeChat { current ->
            current.copy(
                partsByMessage = current.partsByMessage - "m1",
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
        // Not Loaded merely because the network returned an owner item:
        // the content could not be reconciled into the (now-gone) live
        // slice, so retry must stay visible.
        assertEquals(
            PartExpandState.Failed(code = null),
            finalState.partExpandStates[key],
        )
        // Nothing was committed for the removed owner.
        assertTrue(finalState.partsByMessage["m1"]?.none { it.id == "p1" } ?: true)
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

    // ── Visibility contract: end-to-end partsByMessage content ────────────
    //
    // These tests assert the fetched content is actually VISIBLE in the live
    // partsByMessage (not just that the state is Loaded). They guard the
    // content-loss bug where Loaded hid the affordance while no content was
    // committed.

    /**
     * thin_placeholder: a synthetic `thin_placeholder_*` skeleton part is
     * resolved by a fetched message whose real parts have DIFFERENT ids.
     * The reducer must insert the real parts, remove the placeholder, and
     * mark Loaded. (The synthetic placeholder id never appears in the
     * fetched message, so a per-part-id patch could never represent it.)
     */
    @Test
    fun `visibility - thin_placeholder resolved, real parts inserted, Loaded`() = runTest {
        val sessionId = "session-1"
        val placeholder = Part(
            id = "thin_placeholder_m1",
            messageId = "m1",
            type = "text",
            text = "skeleton",
            hasFull = true,
            omitted = listOf("tool"),
        )
        val realPart = Part(id = "prt_real", messageId = "m1", type = "text", text = "real content")
        seedSession(
            sessionId,
            Message(id = "m1", role = "assistant"),
            listOf(placeholder),
        )
        val deferred = CompletableDeferred<ExpandOutcome>()
        coEvery { repository.expandMessagesFullBatch(sessionId, any()) } coAnswers {
            deferred.await()
        }

        chatVM.expandParts(sessionId, listOf(placeholder))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertEquals(
            PartExpandState.Loading,
            core.store.chatFlow.value.partExpandStates[PartKey("m1", "thin_placeholder_m1")],
        )

        deferred.complete(ExpandOutcome.Ok(
            items = listOf(msg("m1", listOf(realPart))),
            failures = emptyList(),
            usedBatch = true,
        ))
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        val finalState = core.store.chatFlow.value
        val key = PartKey("m1", "thin_placeholder_m1")
        // Real part is visible.
        assertEquals(
            "real content",
            finalState.partsByMessage["m1"]?.single { it.id == "prt_real" }?.text,
        )
        // Placeholder is gone.
        assertTrue(
            "placeholder must be removed",
            finalState.partsByMessage["m1"].orEmpty().none { it.isThinPlaceholder() },
        )
        assertEquals(PartExpandState.Loaded, finalState.partExpandStates[key])
    }

    /**
     * part-id drift: skeleton id `p1`; before completion an unrelated current
     * part is added; the fetched message returns a real id `real-1` (≠ p1).
     * The fetched content must be visible, the unrelated current part must
     * survive (lost-update protection), the stale skeleton p1 is cleaned up,
     * and state is Loaded.
     */
    @Test
    fun `visibility - part-id drift commits fetched content, keeps unrelated parts, Loaded`() = runTest {
        val sessionId = "session-1"
        val key = PartKey("m1", "p1")
        val skeletonP1 = skeleton("p1", "m1")
        val unrelated = Part(id = "other", messageId = "m1", type = "text", text = "other content")
        seedSession(
            sessionId,
            Message(id = "m1", role = "assistant"),
            listOf(skeletonP1, unrelated),
        )
        val deferred = CompletableDeferred<ExpandOutcome>()
        coEvery { repository.expandMessagesFullBatch(sessionId, any()) } coAnswers {
            deferred.await()
        }

        chatVM.expandParts(sessionId, listOf(skeletonP1))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        deferred.complete(ExpandOutcome.Ok(
            items = listOf(msg("m1", listOf(
                Part(id = "real-1", messageId = "m1", type = "text", text = "drifted full"),
            ))),
            failures = emptyList(),
            usedBatch = true,
        ))
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        val finalState = core.store.chatFlow.value
        // Fetched content visible.
        assertEquals(
            "drifted full",
            finalState.partsByMessage["m1"]?.single { it.id == "real-1" }?.text,
        )
        // Unrelated current part survived (lost-update protection).
        assertEquals(
            "other content",
            finalState.partsByMessage["m1"]?.first { it.id == "other" }?.text,
        )
        // Stale skeleton marker cleaned up (its id did not come back).
        assertTrue(
            "stale skeleton p1 must be removed",
            finalState.partsByMessage["m1"].orEmpty().none { it.id == "p1" },
        )
        assertEquals(PartExpandState.Loaded, finalState.partExpandStates[key])
    }

    /**
     * fetched-part messageId mismatch: fetched owner stays `m1` but the
     * fetched part's `messageId` is a different value. Commit identity is
     * owner+partId, not the fetched part's wire messageId, so the full part
     * is committed by id and state is Loaded.
     */
    @Test
    fun `visibility - fetched part with mismatched messageId is committed by id`() = runTest {
        val sessionId = "session-1"
        val key = PartKey("m1", "p1")
        val skeletonP1 = skeleton("p1", "m1")
        seedSession(
            sessionId,
            Message(id = "m1", role = "assistant"),
            listOf(skeletonP1),
        )
        val deferred = CompletableDeferred<ExpandOutcome>()
        coEvery { repository.expandMessagesFullBatch(sessionId, any()) } coAnswers {
            deferred.await()
        }

        chatVM.expandParts(sessionId, listOf(skeletonP1))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        // Fetched owner info.id == "m1"; part id matches "p1" BUT its wire
        // messageId is "m-X" (a different value).
        deferred.complete(ExpandOutcome.Ok(
            items = listOf(msg("m1", listOf(
                Part(id = "p1", messageId = "m-X", type = "text", text = "full by id"),
            ))),
            failures = emptyList(),
            usedBatch = true,
        ))
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        val finalState = core.store.chatFlow.value
        // Committed by (owner, partId) regardless of the fetched part's
        // wire messageId.
        assertEquals(
            "full by id",
            finalState.partsByMessage["m1"]?.single { it.id == "p1" }?.text,
        )
        assertEquals(PartExpandState.Loaded, finalState.partExpandStates[key])
    }

    /**
     * Refresh lost-update protection: an UNRELATED current part is added to
     * the live slice before completion. The expand commit must NOT clobber
     * it — it survives alongside the fetched content.
     */
    @Test
    fun `visibility - unrelated concurrent refresh part survives expand commit`() = runTest {
        val sessionId = "session-1"
        val key = PartKey("m1", "p1")
        val skeletonP1 = skeleton("p1", "m1")
        seedSession(
            sessionId,
            Message(id = "m1", role = "assistant"),
            listOf(skeletonP1),
        )
        val deferred = CompletableDeferred<ExpandOutcome>()
        coEvery { repository.expandMessagesFullBatch(sessionId, any()) } coAnswers {
            deferred.await()
        }

        chatVM.expandParts(sessionId, listOf(skeletonP1))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        // Concurrent refresh adds an unrelated part to the live slice.
        core.writeChat { current ->
            val currentParts = current.partsByMessage["m1"] ?: emptyList()
            current.copy(
                partsByMessage = current.partsByMessage +
                    ("m1" to currentParts + Part(id = "refresh-new", messageId = "m1", type = "text", text = "concurrent refresh")),
            )
        }
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        deferred.complete(ExpandOutcome.Ok(
            items = listOf(msg("m1", listOf(
                Part(id = "p1", messageId = "m1", type = "text", text = "FULL"),
            ))),
            failures = emptyList(),
            usedBatch = true,
        ))
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        val finalState = core.store.chatFlow.value
        // Expand target replaced.
        assertEquals(
            "FULL",
            finalState.partsByMessage["m1"]?.first { it.id == "p1" }?.text,
        )
        // Unrelated refresh part survived the expand commit.
        assertEquals(
            "concurrent refresh",
            finalState.partsByMessage["m1"]?.first { it.id == "refresh-new" }?.text,
        )
        assertEquals(PartExpandState.Loaded, finalState.partExpandStates[key])
    }

    /**
     * content-loss guard: the fetched owner message returns with NO parts
     * (server returned the owner but with an empty parts list — no usable
     * content). The reducer must NOT strip the skeleton or mark Loaded; it
     * retains the skeleton and keeps retry visible. Guards the v2 content-
     * loss edge where Loaded hid the affordance with nothing committed.
     */
    @Test
    fun `visibility - empty fetched owner keeps skeleton and retry, not Loaded`() = runTest {
        val sessionId = "session-1"
        val key = PartKey("m1", "p1")
        val skeletonP1 = skeleton("p1", "m1")
        seedSession(
            sessionId,
            Message(id = "m1", role = "assistant"),
            listOf(skeletonP1),
        )
        val deferred = CompletableDeferred<ExpandOutcome>()
        coEvery { repository.expandMessagesFullBatch(sessionId, any()) } coAnswers {
            deferred.await()
        }

        chatVM.expandParts(sessionId, listOf(skeletonP1))
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

        // Owner m1 fetched but with NO parts — nothing to commit.
        deferred.complete(ExpandOutcome.Ok(
            items = listOf(msg("m1", emptyList())),
            failures = emptyList(),
            usedBatch = true,
        ))
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        val finalState = core.store.chatFlow.value
        // Skeleton retained (not stripped) — retry can still target it.
        assertTrue(
            "skeleton p1 must be retained when no content was fetched",
            finalState.partsByMessage["m1"].orEmpty().any { it.id == "p1" },
        )
        // Retry stays visible — NOT Loaded (no content committed).
        val state = finalState.partExpandStates[key]
        assertTrue("expected Failed (retry visible), got $state", state is PartExpandState.Failed)
    }
}
