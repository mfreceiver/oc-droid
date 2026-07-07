package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.ui.ChatViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R18 Phase 5++ coverage: thin delegators + uncovered branches on
 * [ChatViewModel]. Coverage gap before this file: 11/30 methods, 31/71 lines
 * — compactSession (no-session + no-model + isCompacting guards + failure
 * branch), clearCompacting, editFromMessage (multiple guards + success +
 * failure), abortSession (no-session + failure), refreshCurrentSession
 * (no-session + is-loading guards), loadMoreMessages, closeGap, sendMessage
 * delegator, clearExpandedParts, loadMessages(single-arg) overload,
 * sessionWindowCacheSize / peekSessionWindow test hooks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelPassThroughTest : MainViewModelTestBase() {

    // ── compactSession ──────────────────────────────────────────────────────

    @Test
    fun `compactSession with no current session emits error_compact_no_session`() = runTest {
        val core = createCore()
        val vm = ChatViewModel(core)
        advanceUntilIdle()  // pump UiEvent collector

        vm.compactSession()
        advanceUntilIdle()

        assertNotNull(core.recentTestErrors.lastOrNull())
        coVerify(exactly = 0) { repository.summarizeSession(any(), any()) }
    }

    @Test
    fun `compactSession with no current model emits error_compact_no_model`() = runTest {
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1") }  // no currentModel
        advanceUntilIdle()

        vm.compactSession()
        advanceUntilIdle()

        assertNotNull(core.recentTestErrors.lastOrNull())
        coVerify(exactly = 0) { repository.summarizeSession(any(), any()) }
    }

    @Test
    fun `compactSession with isCompacting already true is a no-op`() = runTest {
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat {
            it.copy(
                currentSessionId = "s1",
                currentModel = Message.ModelInfo("p", "m"),
                isCompacting = true,
            )
        }

        vm.compactSession()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.summarizeSession(any(), any()) }
    }

    @Test
    fun `compactSession happy path sets isCompacting and calls summarize`() = runTest {
        coEvery { repository.summarizeSession(any(), any()) } returns Result.success(true)
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat {
            it.copy(
                currentSessionId = "s1",
                currentModel = Message.ModelInfo("p", "m"),
            )
        }

        vm.compactSession()
        advanceUntilIdle()

        assertTrue(core.chatFlow.value.isCompacting)
        coVerify { repository.summarizeSession("s1", Message.ModelInfo("p", "m")) }
    }

    @Test
    fun `compactSession failure clears isCompacting and emits error`() = runTest {
        coEvery { repository.summarizeSession(any(), any()) } returns Result.failure(
            java.io.IOException("server denied"),
        )
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat {
            it.copy(
                currentSessionId = "s1",
                currentModel = Message.ModelInfo("p", "m"),
            )
        }
        advanceUntilIdle()

        vm.compactSession()
        advanceUntilIdle()

        assertFalse(core.chatFlow.value.isCompacting)
        assertEquals(0L, core.chatFlow.value.compactStartedAt)
        assertNotNull(core.recentTestErrors.lastOrNull())
    }

    @Test
    fun `clearCompacting clears the flag when it is set`() = runTest {
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat { it.copy(isCompacting = true, compactStartedAt = 99L) }

        vm.clearCompacting()

        assertFalse(core.chatFlow.value.isCompacting)
        assertEquals(0L, core.chatFlow.value.compactStartedAt)
    }

    @Test
    fun `clearCompacting is a no-op when isCompacting is already false`() = runTest {
        val core = createCore()
        val vm = ChatViewModel(core)

        vm.clearCompacting()

        assertFalse(core.chatFlow.value.isCompacting)
    }

    // ── editFromMessage ─────────────────────────────────────────────────────

    @Test
    fun `editFromMessage with no current session is a no-op`() = runTest {
        val core = createCore()
        val vm = ChatViewModel(core)

        vm.editFromMessage("m1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.revertSession(any(), any(), any()) }
    }

    @Test
    fun `editFromMessage with unknown message id is a no-op`() = runTest {
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1", messages = listOf(Message(id = "other", role = "user"))) }

        vm.editFromMessage("m1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.revertSession(any(), any(), any()) }
    }

    @Test
    fun `editFromMessage with non-user message is a no-op`() = runTest {
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat {
            it.copy(currentSessionId = "s1", messages = listOf(Message(id = "m1", role = "assistant")))
        }

        vm.editFromMessage("m1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.revertSession(any(), any(), any()) }
    }

    @Test
    fun `editFromMessage with blank draft text is a no-op`() = runTest {
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(Message(id = "m1", role = "user")),
                partsByMessage = mapOf("m1" to listOf(Part(id = "p1", type = "text", text = "  "))),
            )
        }

        vm.editFromMessage("m1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.revertSession(any(), any(), any()) }
    }

    @Test
    fun `editFromMessage happy path reverts and seeds draft`() = runTest {
        val reverted = Session(id = "s1", directory = "/x")
        coEvery { repository.revertSession(any(), any(), any()) } returns Result.success(reverted)
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(Message(id = "m1", role = "user")),
                partsByMessage = mapOf("m1" to listOf(Part(id = "p1", type = "text", text = "original prompt"))),
            )
        }

        vm.editFromMessage("m1")
        advanceUntilIdle()

        coVerify { repository.revertSession("s1", "m1", null) }
        verify { settingsManager.setDraftText("s1", "original prompt") }
    }

    @Test
    fun `editFromMessage failure emits error_edit_message_failed`() = runTest {
        coEvery { repository.revertSession(any(), any(), any()) } returns Result.failure(
            java.io.IOException("denied"),
        )
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(Message(id = "m1", role = "user")),
                partsByMessage = mapOf("m1" to listOf(Part(id = "p1", type = "text", text = "draft"))),
            )
        }
        advanceUntilIdle()

        vm.editFromMessage("m1")
        advanceUntilIdle()

        assertNotNull(core.recentTestErrors.lastOrNull())
    }

    // ── abortSession ────────────────────────────────────────────────────────

    @Test
    fun `abortSession with no current session is a no-op`() = runTest {
        val core = createCore()
        val vm = ChatViewModel(core)

        vm.abortSession()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.abortSession(any()) }
    }

    @Test
    fun `abortSession happy path calls repository abortSession`() = runTest {
        coEvery { repository.abortSession(any()) } returns Result.success(Unit)
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1") }

        vm.abortSession()
        advanceUntilIdle()

        coVerify { repository.abortSession("s1") }
    }

    @Test
    fun `abortSession failure emits error_abort_session_failed`() = runTest {
        coEvery { repository.abortSession(any()) } returns Result.failure(
            java.io.IOException("already finished"),
        )
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1") }
        advanceUntilIdle()

        vm.abortSession()
        advanceUntilIdle()

        assertNotNull(core.recentTestErrors.lastOrNull())
    }

    // ── refreshCurrentSession ───────────────────────────────────────────────

    @Test
    fun `refreshCurrentSession with no current session is a no-op`() = runTest {
        coEvery { repository.checkHealth() } returns Result.success(
            cn.vectory.ocdroid.data.model.HealthResponse(healthy = true, version = "1.0"),
        )
        val core = createCore()
        val vm = ChatViewModel(core)

        vm.refreshCurrentSession()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.checkHealth() }
    }

    @Test
    fun `refreshCurrentSession with is-loading-messages is a no-op`() = runTest {
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1", isLoadingMessages = true) }

        vm.refreshCurrentSession()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.checkHealth() }
    }

    @Test
    fun `refreshCurrentSession happy path triggers cold-start refresh and forced health check`() = runTest {
        coEvery { repository.checkHealth() } returns Result.success(
            cn.vectory.ocdroid.data.model.HealthResponse(healthy = true, version = "1.0"),
        )
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.success(
            MessagesPage(emptyList(), null),
        )
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        every { repository.connectSSE(any()) } returns kotlinx.coroutines.flow.emptyFlow()
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1") }

        vm.refreshCurrentSession()
        advanceUntilIdle()

        coVerify(atLeast = 1) { repository.checkHealth() }
    }

    // ── togglePartExpand / clearExpandedParts ───────────────────────────────

    @Test
    fun `togglePartExpand flips value via composerController`() = runTest {
        val core = createCore()
        val vm = ChatViewModel(core)

        vm.togglePartExpand("p1", currentValue = false)
        assertEquals(true, core.expandedParts.value["p1"])

        vm.togglePartExpand("p1", currentValue = true)
        assertEquals(false, core.expandedParts.value["p1"])
    }

    @Test
    fun `clearExpandedParts empties the map`() = runTest {
        val core = createCore()
        val vm = ChatViewModel(core)
        vm.togglePartExpand("p1", currentValue = false)
        vm.togglePartExpand("p2", currentValue = false)
        assertTrue(core.expandedParts.value.isNotEmpty())

        vm.clearExpandedParts()

        assertTrue(core.expandedParts.value.isEmpty())
    }

    // ── loadMoreMessages / closeGap ─────────────────────────────────────────

    @Test
    fun `loadMoreMessages with no current session is a no-op`() = runTest {
        val core = createCore()
        val vm = ChatViewModel(core)

        vm.loadMoreMessages()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `loadMoreMessages happy path pages older messages`() = runTest {
        val msgs = listOf(MessageWithParts(info = Message(id = "old1", role = "user")))
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.success(MessagesPage(msgs, "next"))
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1", olderMessagesCursor = "current", hasMoreMessages = true) }

        vm.loadMoreMessages()
        advanceUntilIdle()

        coVerify { repository.getMessagesPaged("s1", any(), any()) }
    }

    @Test
    fun `fillGap with no current session is a no-op`() = runTest {
        val core = createCore()
        val vm = ChatViewModel(core)

        vm.fillGap("g1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `fillGap with a current session but no cached gap is a no-op`() = runTest {
        // R-20 Phase 2: fillGap routes to GapFillCoordinator.fillSingleGap,
        // which reads the gap from the cache. With no cached gap (the relaxed
        // mock returns an empty list) the coordinator returns early — no fetch.
        // (The full fill integration lives in GapFillCoordinatorTest.)
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1") }

        vm.fillGap("nonexistent-gap")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    // ── sendMessage / loadMessages / accessors ──────────────────────────────

    @Test
    fun `sendMessage delegates to AppCore sendMessage`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.getSession(any()) } returns Result.success(Session(id = "s1", directory = "/x"))
        every { settingsManager.openSessionIds } returns emptyList()
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1") }
        core.writeSessionList { it.copy(sessions = listOf(Session(id = "s1", directory = "/x"))) }
        core.writeComposer { it.copy(inputText = "hi") }

        vm.sendMessage()
        advanceUntilIdle()

        coVerify { repository.sendMessage("s1", "hi", any(), any(), any()) }
    }

    @Test
    fun `loadMessages single-arg overload defaults resetLimit to true`() = runTest {
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.success(
            MessagesPage(emptyList(), null),
        )
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        val core = createCore()
        val vm = ChatViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1") }

        vm.loadMessages("s1")
        advanceUntilIdle()

        coVerify { repository.getMessagesPaged("s1", any(), null) }
    }

    @Test
    fun `accessors delegate to core flows`() = runTest {
        val core = createCore()
        val vm = ChatViewModel(core)
        assertEquals(core.chatFlow, vm.chatFlow)
        assertEquals(core.sessionListFlow, vm.sessionListFlow)
        assertEquals(core.unreadFlow, vm.unreadFlow)
        assertEquals(core.expandedParts, vm.expandedParts)
        assertEquals(core.connectionFlow, vm.connectionFlow)
        assertEquals(core.composerFlow, vm.composerFlow)
        assertEquals(core.settingsFlow, vm.settingsFlow)
        assertEquals(core.fileFlow, vm.fileFlow)
        assertEquals(core.trafficFlow, vm.trafficFlow)
        assertEquals(core.hostFlow, vm.hostFlow)
        assertEquals(core.repository, vm.repository)
    }

    @Test
    fun `sessionWindowCacheSize and peekSessionWindow delegate to sessionSwitcher`() = runTest {
        val core = createCore()
        val vm = ChatViewModel(core)
        assertEquals(0, vm.sessionWindowCacheSize())
        assertNull(vm.peekSessionWindow("missing"))
    }
}
