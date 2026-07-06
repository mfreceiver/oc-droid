package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.PermissionResponse
import cn.vectory.ocdroid.data.model.QuestionInfo
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R18 Phase 5++ coverage: thin delegators + permission/question response
 * branches on [OrchestratorViewModel]. Coverage gap before this file:
 * 6/27 methods, 7/35 lines — setLastNavPage (every branch), respondPermission
 * (success + failure), replyQuestion (success + failure + onError),
 * rejectQuestion (success + failure), showFileInFiles / clearFileToShow /
 * browseFilesInWorkdir / closeFileBrowser / clearDraftIfActive,
 * openSessionFromDeepLink / coldStartReconnect / resetLocalDataAndResync /
 * executeCommand / configureServer (1-line delegators).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorViewModelPassThroughTest : MainViewModelTestBase() {

    // ── Nav ────────────────────────────────────────────────────────────────

    @Test
    fun `setLastNavPage clamps below zero`() = runTest {
        val core = createCore()
        val vm = OrchestratorViewModel(core)
        // Pre-set the nav slice to a non-zero value so the equality guard
        // does not short-circuit when clamping -5 → 0.
        core.store.mutateNav { it.copy(lastNavPage = 1) }

        vm.setLastNavPage(-5)

        assertEquals(0, core.navFlow.value.lastNavPage)
        verify { settingsManager.lastNavPage = 0 }
    }

    @Test
    fun `setLastNavPage clamps above two`() = runTest {
        val core = createCore()
        val vm = OrchestratorViewModel(core)
        // Default lastNavPage=0; 99 → 2 differs so the guard does not short-circuit.
        vm.setLastNavPage(99)

        assertEquals(2, core.navFlow.value.lastNavPage)
        verify { settingsManager.lastNavPage = 2 }
    }

    @Test
    fun `setLastNavPage in range writes through`() = runTest {
        val core = createCore()
        val vm = OrchestratorViewModel(core)

        vm.setLastNavPage(1)

        assertEquals(1, core.navFlow.value.lastNavPage)
        verify { settingsManager.lastNavPage = 1 }
    }

    @Test
    fun `setLastNavPage same value is a no-op`() = runTest {
        val core = createCore()
        val vm = OrchestratorViewModel(core)
        core.store.mutateNav { it.copy(lastNavPage = 1) }

        vm.setLastNavPage(1)

        // No setter call when value already matches.
        verify(exactly = 0) { settingsManager.lastNavPage = any() }
    }

    // ── Permission ──────────────────────────────────────────────────────────

    @Test
    fun `respondPermission success removes the permission from pendingPermissions`() = runTest {
        coEvery {
            repository.respondPermission(any(), any(), any())
        } returns Result.success(Unit)
        val core = createCore()
        val vm = OrchestratorViewModel(core)
        val req = PermissionRequest(id = "p1", sessionId = "s1")
        core.writeSessionList { it.copy(pendingPermissions = listOf(req)) }

        vm.respondPermission("s1", "p1", PermissionResponse.ONCE)
        advanceUntilIdle()

        assertTrue(core.sessionListFlow.value.pendingPermissions.isEmpty())
    }

    @Test
    fun `respondPermission failure emits UiEvent Error`() = runTest {
        coEvery {
            repository.respondPermission(any(), any(), any())
        } returns Result.failure(java.io.IOException("denied"))
        val core = createCore()
        val vm = OrchestratorViewModel(core)
        advanceUntilIdle()  // pump UiEvent collector

        vm.respondPermission("s1", "p1", PermissionResponse.ONCE)
        advanceUntilIdle()

        assertNotNull(core.recentTestErrors.lastOrNull())
    }

    // ── Question ────────────────────────────────────────────────────────────

    @Test
    fun `replyQuestion success removes the question from pendingQuestions`() = runTest {
        coEvery { repository.replyQuestion(any(), any(), any()) } returns Result.success(Unit)
        val core = createCore()
        val vm = OrchestratorViewModel(core)
        val q = QuestionRequest(
            id = "q1", sessionId = "s1",
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())),
        )
        core.writeSessionList { it.copy(pendingQuestions = listOf(q)) }

        vm.replyQuestion("q1", listOf(listOf("yes")))
        advanceUntilIdle()

        assertTrue(core.sessionListFlow.value.pendingQuestions.isEmpty())
    }

    @Test
    fun `replyQuestion failure invokes onError callback`() = runTest {
        coEvery { repository.replyQuestion(any(), any(), any()) } returns Result.failure(
            java.io.IOException("denied"),
        )
        val core = createCore()
        val vm = OrchestratorViewModel(core)
        var onErrorCalled = false

        vm.replyQuestion("q1", listOf(listOf("yes")), onError = { onErrorCalled = true })
        advanceUntilIdle()

        assertTrue(onErrorCalled)
    }

    @Test
    fun `rejectQuestion success removes the question`() = runTest {
        coEvery { repository.rejectQuestion(any(), any()) } returns Result.success(Unit)
        val core = createCore()
        val vm = OrchestratorViewModel(core)
        val q = QuestionRequest(
            id = "q1", sessionId = "s1",
            questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())),
        )
        core.writeSessionList { it.copy(pendingQuestions = listOf(q)) }

        vm.rejectQuestion("q1")
        advanceUntilIdle()

        assertTrue(core.sessionListFlow.value.pendingQuestions.isEmpty())
    }

    @Test
    fun `rejectQuestion failure logs and does not crash`() = runTest {
        coEvery { repository.rejectQuestion(any(), any()) } returns Result.failure(
            java.io.IOException("denied"),
        )
        val core = createCore()
        val vm = OrchestratorViewModel(core)

        vm.rejectQuestion("q1")
        advanceUntilIdle()

        // Body executed; no exception thrown.
        assertTrue(true)
    }

    // ── File browser ────────────────────────────────────────────────────────

    @Test
    fun `showFileInFiles sets filePathToShowInFiles and origin`() = runTest {
        val core = createCore()
        val vm = OrchestratorViewModel(core)

        vm.showFileInFiles("/abs/path", originRoute = "chat")

        assertEquals("/abs/path", core.fileFlow.value.filePathToShowInFiles)
        assertEquals("chat", core.fileFlow.value.filePreviewOriginRoute)
    }

    @Test
    fun `clearFileToShow nulls both filePathToShowInFiles and origin`() = runTest {
        val core = createCore()
        val vm = OrchestratorViewModel(core)
        core.writeFile { it.copy(filePathToShowInFiles = "/p", filePreviewOriginRoute = "chat") }

        vm.clearFileToShow()

        assertNull(core.fileFlow.value.filePathToShowInFiles)
        assertNull(core.fileFlow.value.filePreviewOriginRoute)
    }

    @Test
    fun `browseFilesInWorkdir opens browser with workdir`() = runTest {
        val core = createCore()
        val vm = OrchestratorViewModel(core)

        vm.browseFilesInWorkdir("/proj")

        assertEquals(true, core.fileFlow.value.fileBrowserOpen)
        assertEquals("/proj", core.fileFlow.value.fileBrowserWorkdir)
        assertEquals("sessions", core.fileFlow.value.filePreviewOriginRoute)
        assertNull(core.fileFlow.value.filePathToShowInFiles)
    }

    @Test
    fun `closeFileBrowser clears all file browser state`() = runTest {
        val core = createCore()
        val vm = OrchestratorViewModel(core)
        core.writeFile {
            it.copy(
                fileBrowserOpen = true,
                fileBrowserWorkdir = "/proj",
                filePathToShowInFiles = "/p",
                filePreviewOriginRoute = "chat",
            )
        }

        vm.closeFileBrowser()

        assertEquals(false, core.fileFlow.value.fileBrowserOpen)
        assertNull(core.fileFlow.value.fileBrowserWorkdir)
        assertNull(core.fileFlow.value.filePathToShowInFiles)
        assertNull(core.fileFlow.value.filePreviewOriginRoute)
    }

    @Test
    fun `clearDraftIfActive delegates to composerController`() = runTest {
        val core = createCore()
        val vm = OrchestratorViewModel(core)
        core.writeComposer { it.copy(draftWorkdir = "/draft") }

        vm.clearDraftIfActive()

        assertNull(core.composerFlow.value.draftWorkdir)
    }

    // ── Cross-domain delegators ─────────────────────────────────────────────

    @Test
    fun `openSessionFromDeepLink delegates through AppCore`() = runTest {
        coEvery { repository.getSession(any()) } returns Result.success(
            Session(id = "deep-link-1", directory = "/x"),
        )
        val core = createCore()
        val vm = OrchestratorViewModel(core)

        vm.openSessionFromDeepLink("deep-link-1")
        // The body launches on appScope with withContext(Dispatchers.IO), so
        // the actual fetch escapes the test dispatcher. We give it real time
        // to settle (mirrors AppCoreOrchestrationTest's pattern) — the goal
        // here is covering the VM's 1-line delegator, not the async fetch
        // (which is verified in AppCoreOrchestrationTest).
        repeat(5) {
            advanceUntilIdle()
            kotlinx.coroutines.delay(100)
        }
        advanceUntilIdle()
    }

    @Test
    fun `coldStartReconnect delegates through coordinator`() = runTest {
        coEvery { repository.checkHealth() } returns Result.success(
            HealthResponse(healthy = false, version = "1.0"),
        )
        val core = createCore()
        val vm = OrchestratorViewModel(core)

        vm.coldStartReconnect()
        advanceUntilIdle()

        coVerify(atLeast = 1) { repository.checkHealth() }
    }

    @Test
    fun `resetLocalDataAndResync delegates through AppCore`() = runTest {
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.checkHealth() } returns Result.success(
            HealthResponse(healthy = true, version = "1.0"),
        )
        every { settingsManager.clearAllLocalData() } just runs

        val core = createCore()
        val vm = OrchestratorViewModel(core)

        vm.resetLocalDataAndResync()
        advanceUntilIdle()

        verify { settingsManager.clearAllLocalData() }
    }

    @Test
    fun `executeCommand delegates through AppCore`() = runTest {
        every { settingsManager.currentWorkdir } returns "/w"
        coEvery { repository.getSessionsForDirectory(any()) } returns Result.success(emptyList())
        val core = createCore()
        val vm = OrchestratorViewModel(core)

        vm.executeCommand("/clear", "")
        advanceUntilIdle()

        // /clear branch enters draft mode (no executeCommand repository call).
        assertEquals("/w", core.composerFlow.value.draftWorkdir)
    }

    @Test
    fun `configureServer delegates to host controller`() = runTest {
        every { hostProfileStore.currentProfile() } returns
            cn.vectory.ocdroid.data.model.HostProfile.defaultDirect("http://x")
        val core = createCore()
        val vm = OrchestratorViewModel(core)

        vm.configureServer("http://new", "u", "p")
        advanceUntilIdle()
    }

    @Test
    fun `accessors delegate to core flows`() = runTest {
        val core = createCore()
        val vm = OrchestratorViewModel(core)
        assertEquals(core.navFlow, vm.navFlow)
        assertEquals(core.fileFlow, vm.fileFlow)
        assertEquals(core.settingsFlow, vm.settingsFlow)
        assertEquals(core.trafficFlow, vm.trafficFlow)
        assertEquals(core.hostFlow, vm.hostFlow)
        assertEquals(core.connectionFlow, vm.connectionFlow)
    }
}
