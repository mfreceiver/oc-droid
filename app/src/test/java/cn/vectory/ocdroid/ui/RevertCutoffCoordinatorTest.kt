package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.MainViewModelTestBase
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.RevertCutoff
import cn.vectory.ocdroid.data.model.RevertCutoffState
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.MessagesPage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RevertCutoffCoordinatorTest : MainViewModelTestBase() {
    @Test
    fun `cross host fetch completion drops stale live cutoff`() = runTest {
        val gate = CompletableDeferred<Result<MessagesPage>>()
        var profile = HostProfile.defaultDirect("http://a").copy(serverGroupFp = "A")
        every { hostProfileStore.currentProfile() } answers { profile }
        coEvery { repository.getMessagesPaged("s1", any(), any()) } coAnswers { gate.await() }
        val core = createCore()
        seedPending(core, "m1")

        val job = async(start = CoroutineStart.UNDISPATCHED) { RevertCutoffCoordinator(core).ensure("s1", "m1") }
        profile = HostProfile.defaultDirect("http://b").copy(serverGroupFp = "B")
        core.writeChat { it.copy(revertCutoffs = emptyMap()) }
        gate.complete(Result.success(page("m1", 10L)))
        job.await()

        assertFalse(core.chatFlow.value.revertCutoffs.containsKey("s1"))
    }

    @Test
    fun `pending fetch resolves instead of remaining pending`() = runTest {
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(page("m1", 10L))
        val core = createCore()
        seedPending(core, "m1")

        RevertCutoffCoordinator(core).ensure("s1", "m1")

        assertEquals(RevertCutoffState.Resolved(10L), core.chatFlow.value.revertCutoffs["s1"]?.state)
    }

    @Test
    fun `failed cutoff retries only when forced`() = runTest {
        val core = createCore()
        seedPending(core, "m1")
        core.writeChat { it.copy(revertCutoffs = mapOf("s1" to RevertCutoff("s1", "m1", RevertCutoffState.Failed))) }
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(page("m1", 10L))
        val coordinator = RevertCutoffCoordinator(core)

        coordinator.ensure("s1", "m1")
        coVerify(exactly = 0) { repository.getMessagesPaged("s1", any(), any()) }
        coordinator.ensure("s1", "m1", retryFailed = true)

        coVerify(exactly = 1) { repository.getMessagesPaged("s1", any(), any()) }
        assertEquals(RevertCutoffState.Resolved(10L), core.chatFlow.value.revertCutoffs["s1"]?.state)
    }

    @Test
    fun `changed revert target drops stale fetch completion`() = runTest {
        val gate = CompletableDeferred<Result<MessagesPage>>()
        coEvery { repository.getMessagesPaged("s1", any(), any()) } coAnswers { gate.await() }
        val core = createCore()
        seedPending(core, "m1")

        val job = async(start = CoroutineStart.UNDISPATCHED) { RevertCutoffCoordinator(core).ensure("s1", "m1") }
        core.writeSessionList { it.copy(sessions = listOf(Session("s1", directory = "/x", revert = Session.RevertInfo("m2")))) }
        gate.complete(Result.success(page("m1", 10L)))
        job.await()

        assertEquals(RevertCutoffState.PendingFetch, core.chatFlow.value.revertCutoffs["s1"]?.state)
    }

    private fun seedPending(core: AppCore, messageId: String) {
        core.writeSessionList { it.copy(sessions = listOf(Session("s1", directory = "/x", revert = Session.RevertInfo(messageId)))) }
        core.writeChat { it.copy(currentSessionId = "s1", olderMessagesCursor = "cursor", revertCutoffs = mapOf("s1" to RevertCutoff("s1", messageId, RevertCutoffState.PendingFetch))) }
    }

    private fun page(id: String, created: Long) = MessagesPage(
        listOf(MessageWithParts(Message(id, role = "user", time = Message.TimeInfo(created = created)))), null
    )
}
