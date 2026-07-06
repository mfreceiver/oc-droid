package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.SessionViewModel
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
 * [SessionViewModel]. Coverage gap before this file: 7/18 methods, 62/87
 * lines — openSubAgent (the fetch + child-resolution branches), closeSession
 * (the no-current-session + close-non-current branches), toggleSessionExpanded,
 * loadChildSessions, loadPendingQuestions, loadPendingPermissions, loadSessions
 * (delegator), loadInitialData (delegator), refreshDirectorySessions (blank
 * workdir), clearDraftIfActive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelPassThroughTest : MainViewModelTestBase() {

    @Test
    fun `openSubAgent emits error when child cannot be resolved`() = runTest {
        coEvery { repository.getSession("missing") } returns Result.failure(java.io.IOException("404"))

        val core = createCore()
        val vm = SessionViewModel(core)
        advanceUntilIdle()  // pump so the test UiEvent collector subscribes

        vm.openSubAgent("missing")
        advanceUntilIdle()

        assertNotNull(core.recentTestErrors.lastOrNull())
    }

    @Test
    fun `openSubAgent resolves child from local sessions list and selects it`() = runTest {
        val child = Session(id = "child-1", directory = "/x")
        coEvery { repository.getSession(any()) } returns Result.success(child)

        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeSessionList { it.copy(sessions = listOf(child)) }

        vm.openSubAgent("child-1")
        advanceUntilIdle()

        assertEquals("child-1", core.chatFlow.value.currentSessionId)
    }

    @Test
    fun `openSubAgent resolves child via repository when missing locally`() = runTest {
        val child = Session(id = "child-fetch", directory = "/x")
        coEvery { repository.getSession("child-fetch") } returns Result.success(child)

        val core = createCore()
        val vm = SessionViewModel(core)
        // Local list empty → falls through to repository.getSession.
        core.writeSessionList { it.copy(sessions = emptyList()) }

        vm.openSubAgent("child-fetch")
        advanceUntilIdle()

        assertEquals("child-fetch", core.chatFlow.value.currentSessionId)
        coVerify { repository.getSession("child-fetch") }
    }

    @Test
    fun `closeSession non-current session just removes it from openSessionIds`() = runTest {
        every { settingsManager.openSessionIds } returns listOf("s1", "s2")
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1") }
        core.writeSessionList { it.copy(openSessionIds = listOf("s1", "s2")) }

        vm.closeSession("s2")
        advanceUntilIdle()

        // s2 was not current → no chat clear, just removed from openSessionIds.
        assertEquals(listOf("s1"), core.sessionListFlow.value.openSessionIds)
        assertEquals("s1", core.chatFlow.value.currentSessionId)
    }

    @Test
    fun `closeSession current session with another open selects the next`() = runTest {
        every { settingsManager.openSessionIds } returns listOf("s1", "s2")
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1") }
        core.writeSessionList { it.copy(openSessionIds = listOf("s1", "s2")) }

        vm.closeSession("s1")
        advanceUntilIdle()

        // s1 was current and s2 remains → switchTo(s2) fires.
        assertEquals(listOf("s2"), core.sessionListFlow.value.openSessionIds)
    }

    @Test
    fun `closeSession current session with no other open clears chat`() = runTest {
        every { settingsManager.openSessionIds } returns listOf("s1")
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1", messages = listOf(cn.vectory.ocdroid.data.model.Message(id = "m", role = "user"))) }
        core.writeSessionList { it.copy(openSessionIds = listOf("s1")) }

        vm.closeSession("s1")
        advanceUntilIdle()

        // No remaining open session → chat cleared + composer inputText cleared.
        assertNull(core.chatFlow.value.currentSessionId)
        assertTrue(core.chatFlow.value.messages.isEmpty())
        assertEquals("", core.composerFlow.value.inputText)
    }

    @Test
    fun `closeSession current session saves current draft text first`() = runTest {
        every { settingsManager.openSessionIds } returns listOf("s1")
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1") }
        core.writeComposer { it.copy(inputText = "draft text") }
        core.writeSessionList { it.copy(openSessionIds = listOf("s1")) }

        vm.closeSession("s1")
        advanceUntilIdle()

        verify { settingsManager.setDraftText("s1", "draft text") }
    }

    @Test
    fun `toggleSessionExpanded adds id when absent`() = runTest {
        val core = createCore()
        val vm = SessionViewModel(core)

        vm.toggleSessionExpanded("s1")
        assertTrue(core.sessionListFlow.value.expandedSessionIds.contains("s1"))
    }

    @Test
    fun `toggleSessionExpanded removes id when present`() = runTest {
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeSessionList { it.copy(expandedSessionIds = setOf("s1")) }

        vm.toggleSessionExpanded("s1")
        assertFalse(core.sessionListFlow.value.expandedSessionIds.contains("s1"))
    }

    @Test
    fun `refreshDirectorySessions with blank workdir is a no-op`() = runTest {
        val core = createCore()
        val vm = SessionViewModel(core)

        vm.refreshDirectorySessions("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getSessionsForDirectory(any()) }
    }

    @Test
    fun `refreshDirectorySessions fetches into directorySessions map`() = runTest {
        val sessions = listOf(Session(id = "s1", directory = "/proj"))
        coEvery { repository.getSessionsForDirectory("/proj") } returns Result.success(sessions)
        val core = createCore()
        val vm = SessionViewModel(core)

        vm.refreshDirectorySessions("/proj")
        advanceUntilIdle()

        assertEquals(sessions, core.sessionListFlow.value.directorySessions["/proj"])
    }

    @Test
    fun `refreshDirectorySessions trims the workdir before lookup`() = runTest {
        coEvery { repository.getSessionsForDirectory("/proj") } returns Result.success(emptyList())
        val core = createCore()
        val vm = SessionViewModel(core)

        vm.refreshDirectorySessions("  /proj  ")
        advanceUntilIdle()

        coVerify { repository.getSessionsForDirectory("/proj") }
    }

    @Test
    fun `loadChildSessions delegates to launchLoadChildSessions`() = runTest {
        coEvery { repository.getChildren(any()) } returns Result.success(emptyList())
        val core = createCore()
        val vm = SessionViewModel(core)

        vm.loadChildSessions("parent-1")
        advanceUntilIdle()

        coVerify { repository.getChildren("parent-1") }
    }

    @Test
    fun `loadPendingQuestions delegates with currentWorkdir`() = runTest {
        every { settingsManager.currentWorkdir } returns "/w"
        coEvery { repository.getPendingQuestions("/w") } returns Result.success(emptyList())
        val core = createCore()
        val vm = SessionViewModel(core)

        vm.loadPendingQuestions()
        advanceUntilIdle()

        coVerify { repository.getPendingQuestions("/w") }
    }

    @Test
    fun `loadPendingPermissions delegates to repository`() = runTest {
        coEvery { repository.getPendingPermissions() } returns Result.success(emptyList())
        val core = createCore()
        val vm = SessionViewModel(core)

        vm.loadPendingPermissions()
        advanceUntilIdle()

        coVerify { repository.getPendingPermissions() }
    }

    @Test
    fun `loadSessions delegates through AppCore loadSessionsForEffect`() = runTest {
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        val core = createCore()
        val vm = SessionViewModel(core)

        vm.loadSessions()
        advanceUntilIdle()

        coVerify(atLeast = 1) { repository.getSessions(any()) }
    }

    @Test
    fun `loadInitialData delegates through connection coordinator`() = runTest {
        every { repository.connectSSE(any()) } returns kotlinx.coroutines.flow.emptyFlow()
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        every { settingsManager.currentWorkdir } returns null
        val core = createCore()
        val vm = SessionViewModel(core)

        vm.loadInitialData()
        advanceUntilIdle()

        coVerify { repository.getCommands() }
    }

    @Test
    fun `clearDraftIfActive delegates to composerController`() = runTest {
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeComposer { it.copy(draftWorkdir = "/draft") }

        vm.clearDraftIfActive()

        assertNull(core.composerFlow.value.draftWorkdir)
    }

    @Test
    fun `createSession delegates to launchCreateSession`() = runTest {
        val created = Session(id = "new", directory = "/x")
        coEvery { repository.createSession(any(), any()) } returns Result.success(created)
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(created))
        val core = createCore()
        val vm = SessionViewModel(core)

        vm.createSession(title = "t")
        advanceUntilIdle()

        coVerify { repository.createSession(any(), any()) }
    }

    @Test
    fun `forkSession delegates to launchForkSession`() = runTest {
        val forked = Session(id = "fork-1", directory = "/x")
        coEvery { repository.forkSession(any(), any()) } returns Result.success(forked)
        val core = createCore()
        val vm = SessionViewModel(core)

        vm.forkSession("s1", messageId = null)
        advanceUntilIdle()

        coVerify { repository.forkSession("s1", null) }
    }

    @Test
    fun `archiveSession delegates to launchSetSessionArchived true`() = runTest {
        coEvery { repository.updateSessionArchived(any(), any()) } returns Result.success(
            Session(id = "s1", directory = "/x"),
        )
        val core = createCore()
        val vm = SessionViewModel(core)

        vm.archiveSession("s1")
        advanceUntilIdle()

        coVerify { repository.updateSessionArchived("s1", any()) }
    }

    @Test
    fun `restoreSession delegates to launchSetSessionArchived false`() = runTest {
        coEvery { repository.updateSessionArchived(any(), any()) } returns Result.success(
            Session(id = "s1", directory = "/x"),
        )
        val core = createCore()
        val vm = SessionViewModel(core)

        vm.restoreSession("s1")
        advanceUntilIdle()

        coVerify { repository.updateSessionArchived("s1", any()) }
    }

    @Test
    fun `deleteSession delegates to launchDeleteSession`() = runTest {
        coEvery { repository.deleteSession(any()) } returns Result.success(Unit)
        val core = createCore()
        val vm = SessionViewModel(core)

        vm.deleteSession("s1")
        advanceUntilIdle()

        coVerify { repository.deleteSession("s1") }
    }
}
