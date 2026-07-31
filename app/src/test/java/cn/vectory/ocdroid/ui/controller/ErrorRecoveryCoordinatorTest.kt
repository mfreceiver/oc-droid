package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.PendingChatError
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SharedStateStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §P0-E(b)(c): tests for [ErrorRecoveryCoordinator] — the GET drain/consumer
 * for durable error localization.
 *
 * Uses a real [SharedStateStore] + mock [OpenCodeRepository] so the coordinator's
 * stateFlow collector fires on real state transitions. The coordinator runs on a
 * [StandardTestDispatcher] so [advanceUntilIdle] controls its emission scheduling.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ErrorRecoveryCoordinatorTest {

    private lateinit var store: SharedStateStore
    private lateinit var repository: OpenCodeRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        store = SharedStateStore()
        repository = mockk(relaxed = true)
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun makeCoordinator(): ErrorRecoveryCoordinator = ErrorRecoveryCoordinator(
        scope = scope,
        store = store,
        repository = repository,
    )

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun err(name: String) = Message.MessageError(name = name)

    private fun assistantMsg(id: String, error: Message.MessageError? = null) =
        Message(id = id, role = "assistant", error = error)

    private fun userMsg(id: String) = Message(id = id, role = "user")

    private fun msgWithParts(
        mid: String,
        role: String = "assistant",
        error: Message.MessageError? = null,
        createdAt: Long = 0L,
    ) = MessageWithParts(
        info = Message(id = mid, role = role, error = error,
            time = Message.TimeInfo(created = createdAt)),
    )

    // ── (b) reattach drain: pendingErrorReattach ─────────────────────────────

    @Test
    fun `reattach drain fires getMessages when pendingErrorReattach matches currentSessionId`() = runTest {
        val errorMsg = assistantMsg("m42", error = err("rate_limit"))
        coEvery { repository.getMessages("s1", limit = 50) } returns Result.success(
            listOf(msgWithParts("m1", role = "user"), msgWithParts("m42", error = err("rate_limit")))
        )

        store.mutateChat { it.copy(
            currentSessionId = "s1",
            messages = listOf(userMsg("m1"), errorMsg),
            pendingErrorReattach = mapOf("s1" to PendingChatError(err("rate_limit"), 0L, null)),
        )}

        val coordinator = makeCoordinator()
        advanceUntilIdle()

        val state = store.stateFlow.value
        // The coordinator should have dispatched ErrorLocalizationSettled → markers cleared.
        assertFalse("pendingErrorReattach cleared for s1",
            "s1" in state.chat.pendingErrorReattach)
        // The error should be attached to m42.
        assertEquals("error attached to m42",
            err("rate_limit"), state.chat.messages.find { it.id == "m42" }?.error)
    }

    @Test
    fun `reattach drain does NOT fire when pendingErrorReattach sid differs from currentSessionId`() = runTest {
        coEvery { repository.getMessages(any(), limit = any()) } returns Result.success(emptyList())

        store.mutateChat { it.copy(
            currentSessionId = "s2",  // NOT s1
            pendingErrorReattach = mapOf("s1" to PendingChatError(err("e"), 0L, null)),
        )}

        val coordinator = makeCoordinator()
        advanceUntilIdle()

        // getMessages should NOT have been called for s1 (never in reattachToDrain).
        // Since no dispatch happened, pendingErrorReattach still has s1.
        assertTrue("s1 still in pendingErrorReattach (no drain fired)",
            "s1" in store.stateFlow.value.chat.pendingErrorReattach)
    }

    // ── (c) fallback drain: pendingErrorCheck + sessionErrorsById ─────────────

    @Test
    fun `fallback drain fires getMessages when pendingErrorCheck + sessionErrorsById present and lastAssistant has no error`() = runTest {
        coEvery { repository.getMessages("s1", limit = 50) } returns Result.success(
            listOf(msgWithParts("m1", role = "user"), msgWithParts("m99", error = err("timeout")))
        )

        store.mutateChat { it.copy(
            currentSessionId = "s1",
            messages = listOf(assistantMsg("existing")),  // no error yet
            pendingErrorCheck = setOf("s1"),
        )}
        store.mutateSessionList { it.copy(
            sessionErrorsById = mapOf("s1" to SlimSessionLastError(name = "timeout")),
        )}

        val coordinator = makeCoordinator()
        advanceUntilIdle()

        val state = store.stateFlow.value
        assertFalse("pendingErrorCheck cleared for s1",
            "s1" in state.chat.pendingErrorCheck)
    }

    @Test
    fun `U-CQ9 fallback drain fires even when sessionErrorsById is missing for the sid`() = runTest {
        coEvery { repository.getMessages("s1", limit = 50) } returns Result.success(
            listOf(msgWithParts("m1", role = "assistant", error = err("timeout")))
        )

        store.mutateChat { it.copy(
            currentSessionId = "s1",
            pendingErrorCheck = setOf("s1"),
            // No sessionErrorsById set for s1 — U-CQ9 drain fires regardless.
        )}

        val coordinator = makeCoordinator()
        advanceUntilIdle()

        // U-CQ9: drain fires even without banner → pendingErrorCheck cleared.
        assertFalse("U-CQ9 drain cleared pendingErrorCheck despite missing banner",
            "s1" in store.stateFlow.value.chat.pendingErrorCheck)
        coVerify(exactly = 1) { repository.getMessages("s1", limit = 50) }
    }

    @Test
    fun `U-CQ9 fallback drain fires even when lastAssistant already has an error`() = runTest {
        coEvery { repository.getMessages("s1", limit = 50) } returns Result.success(
            listOf(msgWithParts("m1", role = "assistant", error = err("timeout")))
        )

        store.mutateChat { it.copy(
            currentSessionId = "s1",
            messages = listOf(assistantMsg("m1", error = err("existing"))),
            pendingErrorCheck = setOf("s1"),
        )}
        store.mutateSessionList { it.copy(
            sessionErrorsById = mapOf("s1" to SlimSessionLastError(name = "timeout")),
        )}

        val coordinator = makeCoordinator()
        advanceUntilIdle()

        // U-CQ9: drain fires regardless of lastAssistant error state → cleared.
        assertFalse("U-CQ9 drain cleared pendingErrorCheck despite assistant having error",
            "s1" in store.stateFlow.value.chat.pendingErrorCheck)
        coVerify(exactly = 1) { repository.getMessages("s1", limit = 50) }
    }

    // ── Network failure ──────────────────────────────────────────────────────

    @Test
    fun `getMessages failure dispatches ErrorLocalizationSettled with null markers`() = runTest {
        coEvery { repository.getMessages("s1", limit = 50) } returns Result.failure(IOException("network down"))

        store.mutateChat { it.copy(
            currentSessionId = "s1",
            pendingErrorReattach = mapOf("s1" to PendingChatError(err("e"), 0L, null)),
        )}

        val coordinator = makeCoordinator()
        advanceUntilIdle()

        val state = store.stateFlow.value
        // On failure, ErrorLocalizationSettled(sid, null, null) clears markers.
        assertFalse("pendingErrorReattach cleared despite network failure",
            "s1" in state.chat.pendingErrorReattach)
    }

    // ── inFlight dedup ───────────────────────────────────────────────────────

    @Test
    fun `inFlight dedup prevents re-launching drain for same sid while in-flight`() = runTest {
        // Make getMessages block until we explicitly release it.
        val latch = kotlinx.coroutines.CompletableDeferred<Result<List<MessageWithParts>>>()
        coEvery { repository.getMessages("s1", limit = 50) } coAnswers {
            latch.await()
        }

        store.mutateChat { it.copy(
            currentSessionId = "s1",
            pendingErrorReattach = mapOf("s1" to PendingChatError(err("e"), 0L, null)),
        )}

        val coordinator = makeCoordinator()
        advanceUntilIdle()

        // First emission should have launched the drain (now stuck on latch).
        // Mutate again to trigger another emission while the first is in-flight.
        store.mutateChat { it.copy(
            pendingErrorCheck = setOf("s1"),
        )}
        store.mutateSessionList { it.copy(
            sessionErrorsById = mapOf("s1" to SlimSessionLastError(name = "e")),
        )}
        advanceUntilIdle()

        // Complete the latch so the first drain finishes.
        latch.complete(Result.success(listOf(msgWithParts("m1", role = "assistant"))))
        advanceUntilIdle()

        // The error should be attached (from the first drain) and markers cleared.
        val state = store.stateFlow.value
        assertFalse("pendingErrorReattach cleared", "s1" in state.chat.pendingErrorReattach)
        assertFalse("pendingErrorCheck cleared", "s1" in state.chat.pendingErrorCheck)
        // getMessages should only have been called ONCE.
        coVerify(exactly = 1) { repository.getMessages("s1", limit = 50) }
    }
}

/** Local throwable to avoid importing java.io.IOException across the entire file. */
private class IOException(message: String) : java.io.IOException(message)
