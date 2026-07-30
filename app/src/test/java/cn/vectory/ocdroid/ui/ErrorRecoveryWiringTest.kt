package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.MainViewModelTestBase
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §P0-E(b)(c) integration test: verifies the [ErrorRecoveryCoordinator] is
 * WIRED by the production [AppCore] holder (not direct-"new"). Extends
 * [MainViewModelTestBase] so [createCore] constructs the coordinator via the
 * real AppCore constructor — proving Hilt would resolve the @Provides binding.
 *
 * The coordinator's `init` collector subscribes on `appScope` (Main.immediate).
 * `runTest` + `mainDispatcherRule` set Dispatchers.Main to the test scheduler,
 * so [advanceUntilIdle] drives the collector.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ErrorRecoveryWiringTest : MainViewModelTestBase() {

    @Test
    fun `wired coordinator fires getMessages when pendingErrorReattach matches currentSessionId`() = runTest {
        val sid = "test-sid"
        val err = Message.MessageError(name = "rate_limit")
        coEvery { repository.getMessages(sid, limit = 50) } returns Result.success(
            listOf(MessageWithParts(
                info = Message(id = "m42", role = "assistant", error = err),
            ))
        )

        // createCore() constructs AppCore → which holds errorRecoveryCoordinator
        // in its constructor → the coordinator's init block starts collecting.
        val core = createCore()

        // Seed a drainable marker via the real producer reducer: dispatch a
        // LastAssistantErrorAttached with a non-matching route so it lands in
        // pendingErrorReattach.
        core.store.dispatch(AppAction.LastAssistantErrorAttached(
            error = err,
            expectedRouteInstance = 999L,  // non-matching → goes to queue
            sessionId = sid,
        ))
        assertTrue("pendingErrorReattach has sid before drain",
            sid in core.store.chatFlow.value.pendingErrorReattach)

        // Switch to the sid so the coordinator's (b) reattach drain matches it.
        core.store.dispatch(AppAction.SessionSelected(
            sessionId = sid,
            pendingScrollRequest = PendingScrollRequest(
                requestId = 1L,
                targetSessionId = sid,
                behavior = ScrollBehavior.Latest,
            ),
        ))
        advanceUntilIdle()

        // The coordinator's collector should have fired getMessages.
        coVerify { repository.getMessages(sid, limit = 50) }
        // Markers should be cleared.
        assertFalse("pendingErrorReattach cleared",
            sid in core.store.chatFlow.value.pendingErrorReattach)
    }

    @Test
    fun `wired coordinator does NOT fire getMessages when currentSessionId differs`() = runTest {
        val sid = "test-sid"
        coEvery { repository.getMessages(sid, limit = 50) } returns Result.success(emptyList())

        val core = createCore()

        // Seed pendingErrorReattach with sid, but keep currentSessionId = null.
        core.store.mutateChat { it.copy(
            currentSessionId = "other-sid",
            pendingErrorReattach = it.pendingErrorReattach + (sid to PendingChatError(
                error = Message.MessageError(name = "e"),
                routeInstance = 0L,
                messageAssistantId = null,
            )),
        )}
        advanceUntilIdle()

        // Coordinator should NOT have called getMessages (sid != currentSessionId).
        coVerify(exactly = 0) { repository.getMessages(sid, limit = any()) }
        // Marker must survive.
        assertTrue("pendingErrorReattach preserved when not viewing the sid",
            sid in core.store.chatFlow.value.pendingErrorReattach)
    }
}
