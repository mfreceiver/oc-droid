package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.MainViewModelTestBase
import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.RevertCutoffState
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.MessagesPage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Executable destructive-operation gate for [RevertConversation]. */
@OptIn(ExperimentalCoroutinesApi::class)
class RevertConversationTest : MainViewModelTestBase() {

    @Test
    fun `busy session blocks revert before destructive server call`() = runTest {
        val core = createCore()
        seedRevertableMessage(core)
        core.writeSessionList { it.copy(sessionStatuses = mapOf("s1" to SessionStatus(type = "busy"))) }

        val outcome = RevertConversation(core).execute("s1", "m1") { error("reload must not run") }

        assertGeneratingFailure(outcome)
        coVerify(exactly = 0) { repository.revertSession(any(), any(), any()) }
    }

    @Test
    fun `retry session blocks revert before destructive server call`() = runTest {
        val core = createCore()
        seedRevertableMessage(core)
        core.writeSessionList { it.copy(sessionStatuses = mapOf("s1" to SessionStatus(type = "retry"))) }

        val outcome = RevertConversation(core).execute("s1", "m1") { error("reload must not run") }

        assertGeneratingFailure(outcome)
        coVerify(exactly = 0) { repository.revertSession(any(), any(), any()) }
    }

    @Test
    fun `active text stream blocks revert before destructive server call`() = runTest {
        val core = createCore()
        seedRevertableMessage(core, streamingPartTexts = mapOf("p-stream" to "partial response"))

        val outcome = RevertConversation(core).execute("s1", "m1") { error("reload must not run") }

        assertGeneratingFailure(outcome)
        coVerify(exactly = 0) { repository.revertSession(any(), any(), any()) }
    }

    @Test
    fun `active reasoning stream blocks revert before destructive server call`() = runTest {
        val core = createCore()
        seedRevertableMessage(
            core,
            streamingReasoningPart = Part(id = "p-reasoning", messageId = "a1", sessionId = "s1", type = "reasoning"),
        )

        val outcome = RevertConversation(core).execute("s1", "m1") { error("reload must not run") }

        assertGeneratingFailure(outcome)
        coVerify(exactly = 0) { repository.revertSession(any(), any(), any()) }
    }

    @Test
    fun `sending session blocks revert before destructive server call`() = runTest {
        val core = createCore()
        seedRevertableMessage(core)
        core.writeComposer { it.copy(sendingSessionIds = setOf("s1")) }

        val outcome = RevertConversation(core).execute("s1", "m1") { error("reload must not run") }

        assertGeneratingFailure(outcome)
        coVerify(exactly = 0) { repository.revertSession(any(), any(), any()) }
    }

    @Test
    fun `cancellation from repository propagates and releases the dedup guard`() = runTest {
        val started = CompletableDeferred<Unit>()
        coEvery { repository.revertSession("s1", "m1", null) } coAnswers {
            started.complete(Unit)
            CompletableDeferred<Result<Session>>().await()
        }
        val core = createCore()
        seedRevertableMessage(core)
        val useCase = RevertConversation(core)

        val running = async(start = CoroutineStart.UNDISPATCHED) { useCase.execute("s1", "m1") {} }
        started.await()
        running.cancel()
        var propagated = false
        try {
            running.await()
        } catch (_: kotlinx.coroutines.CancellationException) {
            propagated = true
        }

        assertTrue(propagated)
        assertTrue(running.isCancelled)
        coEvery { repository.revertSession("s1", "m1", null) } returns Result.failure(IOException("second call proves guard released"))
        assertTrue(useCase.execute("s1", "m1") {} is RevertOutcome.Failure)
    }

    @Test
    fun `overlapping revert returns cancelled and releases guard after terminal outcome`() = runTest {
        val firstResult = CompletableDeferred<Result<Session>>()
        val reverted = Session(id = "s1", directory = "/reverted", revert = Session.RevertInfo("m1"))
        coEvery { repository.revertSession("s1", "m1", null) } coAnswers { firstResult.await() }
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(reverted))
        val core = createCore()
        seedRevertableMessage(core)
        val useCase = RevertConversation(core)

        val first = async(start = CoroutineStart.UNDISPATCHED) { useCase.execute("s1", "m1") {} }
        // The first call reaches the deferred repository response before the second starts.
        assertFalse(first.isCompleted)

        val collided = useCase.execute("s1", "m1") { error("collided revert must not reload") }

        assertEquals(RevertOutcome.Cancelled, collided)
        coVerify(exactly = 1) { repository.revertSession("s1", "m1", null) }

        firstResult.complete(Result.success(reverted))
        assertTrue(first.await() is RevertOutcome.Success)
        advanceUntilIdle()

        val subsequent = useCase.execute("s1", "m1") {}

        assertTrue(subsequent is RevertOutcome.Success)
        coVerify(exactly = 2) { repository.revertSession("s1", "m1", null) }
    }

    @Test
    fun `successful revert preserves complete success sequence`() = runTest {
        val original = Session(id = "s1", directory = "/original")
        val reverted = Session(id = "s1", directory = "/reverted", revert = Session.RevertInfo("m1"))
        coEvery { repository.revertSession("s1", "m1", null) } returns Result.success(reverted)
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(reverted))
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        val core = createCore()
        seedRevertableMessage(core, session = original, createdAt = 42L)
        core.writeComposer {
            it.copy(
                inputText = "old draft",
                imageAttachments = listOf(ComposerImageAttachment("img", "x.png", "image/png", "data:x", byteArrayOf(1), 1)),
            )
        }
        val reloads = mutableListOf<String>()

        val outcome = RevertConversation(core).execute("s1", "m1") { reloads += it }
        advanceUntilIdle()

        assertEquals(RevertOutcome.Success(reverted), outcome)
        assertEquals(reverted, core.sessionListFlow.value.sessions.single())
        assertEquals(RevertCutoffState.Resolved(42L), core.chatFlow.value.revertCutoffs["s1"]?.state)
        assertEquals("original prompt", core.composerFlow.value.inputText)
        assertTrue(core.composerFlow.value.imageAttachments.isEmpty())
        assertEquals(listOf("s1"), reloads) // ChatViewModel supplies loadMessages(id, resetLimit = true).
        verify { settingsManager.setDraftText(any(), "s1", "original prompt") }
        // loadSessionsForEffect is observable through its session-list request.
        coVerify(atLeast = 1) { repository.getSessions(any()) }
    }

    @Test
    fun `cutoff cache persistence failure does not overturn server success`() = runTest {
        val reverted = Session(id = "s1", directory = "/reverted", revert = Session.RevertInfo("m1"))
        coEvery { repository.revertSession("s1", "m1", null) } returns Result.success(reverted)
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(reverted))
        val core = createCore()
        seedRevertableMessage(core, createdAt = 42L)
        every { settingsManager.sessionCache = any() } throws IOException("disk unavailable") andThen Unit
        var reloadCount = 0

        val outcome = RevertConversation(core).execute("s1", "m1") { reloadCount++ }
        advanceUntilIdle()

        assertEquals(RevertOutcome.Success(reverted), outcome)
        assertEquals(1, reloadCount)
        assertEquals("original prompt", core.composerFlow.value.inputText)
        verify { settingsManager.setDraftText(any(), "s1", "original prompt") }
        coVerify(atLeast = 1) { repository.getSessions(any()) }
    }

    @Test
    fun `draft persistence failure does not overturn server success`() = runTest {
        val reverted = Session(id = "s1", directory = "/reverted", revert = Session.RevertInfo("m1"))
        coEvery { repository.revertSession("s1", "m1", null) } returns Result.success(reverted)
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(reverted))
        every { settingsManager.setDraftText(any(), any(), any()) } throws IOException("draft disk unavailable")
        val core = createCore()
        seedRevertableMessage(core, createdAt = 42L)
        var reloadCount = 0

        val outcome = RevertConversation(core).execute("s1", "m1") { reloadCount++ }
        advanceUntilIdle()

        assertEquals(RevertOutcome.Success(reverted), outcome)
        assertEquals(1, reloadCount)
        coVerify(atLeast = 1) { repository.getSessions(any()) }
    }

    @Test
    fun `failed revert leaves state composer cutoff and reload untouched`() = runTest {
        val original = Session(id = "s1", directory = "/original")
        coEvery { repository.revertSession("s1", "m1", null) } returns Result.failure(IOException("denied"))
        val core = createCore()
        seedRevertableMessage(core, session = original, createdAt = 42L)
        core.writeComposer { it.copy(inputText = "existing draft") }
        var reloadCount = 0

        val outcome = RevertConversation(core).execute("s1", "m1") { reloadCount++ }

        assertTrue(outcome is RevertOutcome.Failure)
        assertEquals(original, core.sessionListFlow.value.sessions.single())
        assertEquals("existing draft", core.composerFlow.value.inputText)
        assertTrue(core.chatFlow.value.revertCutoffs.isEmpty())
        assertEquals(0, reloadCount)
        verify(exactly = 0) { settingsManager.setDraftText(any(), any(), any()) }
        coVerify(exactly = 0) { repository.getSessions(any()) }
    }

    private fun seedRevertableMessage(
        core: AppCore,
        session: Session = Session(id = "s1", directory = "/original"),
        createdAt: Long? = null,
        streamingPartTexts: Map<String, String> = emptyMap(),
        streamingReasoningPart: Part? = null,
    ) {
        val message = Message(id = "m1", role = "user", time = createdAt?.let { Message.TimeInfo(created = it) })
        core.writeSessionList { it.copy(sessions = listOf(session)) }
        core.writeChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(message),
                partsByMessage = mapOf("m1" to listOf(Part(id = "p1", messageId = "m1", sessionId = "s1", type = "text", text = "original prompt"))),
                streamingPartTexts = streamingPartTexts,
                streamingReasoningPart = streamingReasoningPart,
            )
        }
    }

    private fun assertGeneratingFailure(outcome: RevertOutcome) {
        assertTrue(outcome is RevertOutcome.Failure)
        val message = (outcome as RevertOutcome.Failure).error.message.orEmpty()
        assertTrue("expected generating/cannot-revert error, was: $message", message.contains("生成") && message.contains("回退"))
    }
}
