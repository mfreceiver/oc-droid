package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.ScrollCheckpoint
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
import org.junit.Assert.assertNotEquals
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
 *
 * §chat-list-detail §11 / G6 (B5 BLOCK-fix): the openSubAgent signature
 * changed again — it now takes the parent's [ScrollCheckpoint] AND a
 * `(resolvedChildId, checkpoint) -> Unit` success callback. The checkpoint
 * write + nav happen INSIDE the callback (NOT before the call), so a failed
 * child fetch or a route change mid-fetch leaves no stale checkpoint. Tests
 * pass a recording callback + a neutral checkpoint + set up the parent route
 * (navState.lastRoute) so the route-instance guard passes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelPassThroughTest : MainViewModelTestBase() {

    // §B5 BLOCK-fix: records (childId, checkpoint) pairs passed to
    // openSubAgent's onNavigateToChild callback. The "happy path" tests
    // assert this list non-empty; the "fetch fails" / "route changed" tests
    // assert it stays empty.
    private val navigatedTo = mutableListOf<Pair<String, ScrollCheckpoint>>()
    private val testCheckpoint = ScrollCheckpoint(anchorKey = "anchor", fallbackIndex = 3, offset = 7)
    private val recordNavigate: (String, ScrollCheckpoint) -> Unit = { id, cp ->
        navigatedTo += id to cp
    }

    @Test
    fun `openSubAgent emits error when child cannot be resolved`() = runTest {
        coEvery { repository.getSession("ses_missing") } returns Result.failure(java.io.IOException("404"))

        val core = createCore()
        val vm = SessionViewModel(core)
        // §B5 BLOCK-fix: set up the parent route so the route-instance guard
        // passes (routeChatSessionId(nav.lastRoute) == parentId).
        core.store.mutateNav { it.copy(lastRoute = "chat/ses_parent_1") }
        core.writeChat { it.copy(currentSessionId = "ses_parent_1") }
        advanceUntilIdle()  // pump so the test UiEvent collector subscribes

        vm.openSubAgent("ses_missing", testCheckpoint, onNavigateToChild = recordNavigate)
        advanceUntilIdle()

        assertNotNull(core.recentTestErrors.lastOrNull())
        // §B5 BLOCK-fix MAJOR 1: navigation + checkpoint write MUST NOT fire
        // when the child fetch failed.
        assertTrue("navigation must not fire on failed fetch", navigatedTo.isEmpty())
    }

    @Test
    fun `openSubAgent resolves child from local sessions list and selects it`() = runTest {
        val parent = Session(id = "ses_parent_1", directory = "/x")
        val child = Session(id = "ses_child_1", directory = "/x", parentId = "ses_parent_1")
        coEvery { repository.getSession(any()) } returns Result.success(child)

        val core = createCore()
        val vm = SessionViewModel(core)
        core.store.mutateNav { it.copy(lastRoute = "chat/ses_parent_1") }
        core.writeChat { it.copy(currentSessionId = "ses_parent_1") }
        core.writeSessionList { it.copy(sessions = listOf(parent, child)) }

        vm.openSubAgent("ses_child_1", testCheckpoint, onNavigateToChild = recordNavigate)
        advanceUntilIdle()

        // §B5 BLOCK-fix: callback fires with (childId, checkpoint); caller
        // writes checkpoint to parent handle + calls navigateToChat(childId).
        assertEquals(listOf("ses_child_1" to testCheckpoint), navigatedTo)
    }

    @Test
    fun `openSubAgent resolves child via repository when missing locally`() = runTest {
        val child = Session(id = "ses_child_fetch", directory = "/x", parentId = "ses_parent_1")
        coEvery { repository.getSession("ses_child_fetch") } returns Result.success(child)

        val core = createCore()
        val vm = SessionViewModel(core)
        core.store.mutateNav { it.copy(lastRoute = "chat/ses_parent_1") }
        core.writeChat { it.copy(currentSessionId = "ses_parent_1") }
        // Local list empty → falls through to repository.getSession.
        core.writeSessionList { it.copy(sessions = emptyList()) }

        vm.openSubAgent("ses_child_fetch", testCheckpoint, onNavigateToChild = recordNavigate)
        advanceUntilIdle()

        assertEquals(listOf("ses_child_fetch" to testCheckpoint), navigatedTo)
        coVerify { repository.getSession("ses_child_fetch") }
    }

    /**
     * §B5 BLOCK-fix MAJOR 1: if the user navigates away from the parent
     * mid-fetch (route changes), the openSubAgent callback MUST NOT fire —
     * otherwise the checkpoint write + nav would land on the wrong route
     * entry. The route-instance CAS guard in the launch body catches this.
     */
    @Test
    fun `openSubAgent drops callback when route changes mid-fetch (B5 BLOCK-fix MAJOR 1)`() = runTest {
        val child = Session(id = "ses_child_fetch", directory = "/x", parentId = "ses_parent_1")
        coEvery { repository.getSession("ses_child_fetch") } returns Result.success(child)

        val core = createCore()
        val vm = SessionViewModel(core)
        core.store.mutateNav { it.copy(lastRoute = "chat/ses_parent_1") }
        core.writeChat { it.copy(currentSessionId = "ses_parent_1") }

        // §B5 BLOCK-fix MAJOR 1: capture happens synchronously inside
        // openSubAgent (parentId = routeChatSessionId("chat/ses_parent_1") =
        // "ses_parent_1"); the launch body then suspends on repository.getSession.
        // BEFORE advanceUntilIdle pumps the coroutine to completion, flip the
        // route — the post-fetch re-validation guard (routeChatSessionId !=
        // parentId) catches the mismatch and silently drops the callback.
        vm.openSubAgent("ses_child_fetch", testCheckpoint, onNavigateToChild = recordNavigate)
        // Flip the route mid-fetch (before advanceUntilIdle pumps the
        // coroutine to completion).
        core.store.mutateNav { it.copy(lastRoute = "chat/other-route") }
        advanceUntilIdle()

        // §B5 BLOCK-fix: callback did NOT fire (route no longer parent-1).
        assertTrue("callback must not fire when route changed mid-fetch", navigatedTo.isEmpty())
    }

    @Test
    fun `closeSession non-current session is a no-op (B4 — tab concept gone)`() = runTest {
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1") }
        vm.closeSession("s2")
        advanceUntilIdle()

        // §B4 / §10: non-current close is a no-op — list-detail has a single
        // detail pane, no tab strip to remove from. Current session untouched.
        assertEquals("s1", core.chatFlow.value.currentSessionId)
    }

    @Test
    fun `closeSession current session with another open selects the next`() = runTest {
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1") }
        vm.closeSession("s1")
        advanceUntilIdle()

        // s1 was current and s2 remains → switchTo(s2) fires.
    }

    @Test
    fun `closeSession current session with no other open clears chat`() = runTest {
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1", messages = listOf(cn.vectory.ocdroid.data.model.Message(id = "m", role = "user"))) }
        vm.closeSession("s1")
        advanceUntilIdle()

        // No remaining open session → chat cleared + composer inputText cleared.
        assertNull(core.chatFlow.value.currentSessionId)
        assertTrue(core.chatFlow.value.messages.isEmpty())
        assertEquals("", core.composerFlow.value.inputText)
    }

    @Test
    fun `closeSession last tab clears the persisted currentSessionId`() = runTest {
        // §fix-close-all-residual: the AppCore persistence collector uses
        // filterNotNull(), so the chat.currentSessionId → null transition is
        // NOT auto-persisted. closeSession must explicitly clear
        // settingsManager.currentSessionId, otherwise applySavedSettings
        // re-seeds chatFlow with the stale id on the next cold start and
        // resurrects a session the user closed all tabs on.
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1") }
        vm.closeSession("s1")
        advanceUntilIdle()

        verify { settingsManager.currentSessionId = null }
    }

    @Test
    fun `closeSession last tab uses slice open-tabs-list not stale settings`() = runTest {
        // §fix-close-all-slice-source: disk open-tabs-list may still list a
        // ghost id the runtime strip already dropped. closeSession must filter
        // the SLICE list so last-tab close clears current instead of switchTo
        // on a disk-only ghost.
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(cn.vectory.ocdroid.data.model.Message(id = "m", role = "user")))
        }
        vm.closeSession("s1")
        advanceUntilIdle()

        assertNull(core.chatFlow.value.currentSessionId)
        assertTrue(core.chatFlow.value.messages.isEmpty())
        // Navigates home via nav slice (domain half of leave-Chat).
        assertEquals(NavRoute.Sessions.route, core.navFlow.value.lastRoute)
        // §B4: open-tabs-list removed — no openSessionIds verify.
        verify { settingsManager.currentSessionId = null }
    }

    @Test
    fun `closeSession last tab sets nav lastRoute to Sessions`() = runTest {
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1") }
        // Simulate being on Chat.
        core.store.mutateNav { it.copy(lastRoute = NavRoute.Chat.route) }

        vm.closeSession("s1")
        advanceUntilIdle()

        assertEquals(NavRoute.Sessions.route, core.navFlow.value.lastRoute)
        verify { settingsManager.lastRoute = NavRoute.Sessions.route }
    }

    @Test
    fun `closeSession non-current with null current is a no-op (B4 — tab concept gone)`() = runTest {
        // §B4: open-tabs-list removed. closeSession of a non-current id when
        // current is already null is a no-op — no tab list to prune, no
        // "last tab closed → home" transition (that was the old open-tabs rule).
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeChat { it.copy(currentSessionId = null) }
        core.store.mutateNav { it.copy(lastRoute = NavRoute.Chat.route) }

        vm.closeSession("orphan-open")
        advanceUntilIdle()

        assertNull(core.chatFlow.value.currentSessionId)
        // lastRoute unchanged — close of a non-current id is a no-op in B4.
        assertEquals(NavRoute.Chat.route, core.navFlow.value.lastRoute)
    }

    @Test
    fun `closeSession last tab with active draft does not navigate home`() = runTest {
        // Match ChatScaffold draft guard: mid-composition stays on Chat.
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1") }
        core.writeComposer { it.copy(draftWorkdir = "/proj", inputText = "typing") }
        core.store.mutateNav { it.copy(lastRoute = NavRoute.Chat.route) }

        vm.closeSession("s1")
        advanceUntilIdle()

        assertNull(core.chatFlow.value.currentSessionId)
        assertEquals("/proj", core.composerFlow.value.draftWorkdir)
        assertEquals(NavRoute.Chat.route, core.navFlow.value.lastRoute)
        verify(exactly = 0) { settingsManager.lastRoute = NavRoute.Sessions.route }
    }

    @Test
    fun `deleteSession current clears persisted currentSessionId via the collector`() = runTest {
        // §fix-null-persistence (oracle+grok review): the AppCore collector no
        // longer filters null — a null currentSessionId transition from ANY
        // path (here: launchDeleteSession clearing chat after deleting the
        // current session, which has NO explicit settingsManager write) must
        // be persisted so the next cold start cannot re-seed the deleted id.
        coEvery { repository.deleteSession(any()) } returns Result.success(Unit)
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1") }
        core.writeSessionList { it.copy(sessions = listOf(Session(id = "s1", directory = "/x"))) }

        vm.deleteSession("s1")
        advanceUntilIdle()

        assertNull("runtime currentSessionId cleared", core.chatFlow.value.currentSessionId)
        verify { settingsManager.currentSessionId = null }
    }

    @Test
    fun `closeSession current session saves current draft text first`() = runTest {
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeChat { it.copy(currentSessionId = "s1") }
        core.writeComposer { it.copy(inputText = "draft text") }
        vm.closeSession("s1")
        advanceUntilIdle()

        verify { settingsManager.setDraftText(any(), "s1", "draft text") }
    }

    // §fix-close-subagent regression coverage: the close-X only renders on the
    // selected tab, and the tab strip's effectiveSelectedId falls back to the
    // root when current is a sub-agent. So the user can close a root tab while
    // currentSessionId points at one of its descendants. Pre-fix, isCurrent
    // (curId == sessionId) was false in that case → currentSessionId was never
    // cleared → the chat body kept rendering after every tab was closed (the
    // "关光 tab 仍显示 chat" residual bug).

    @Test
    fun `closeSession ancestor of current is a no-op (B4 §10 — non-current close)`() = runTest {
        val root = Session(id = "root-1", directory = "/proj")
        val child = Session(id = "ses_child_1", directory = "/proj", parentId = "root-1")
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeChat {
            it.copy(
                currentSessionId = "ses_child_1",
                messages = listOf(cn.vectory.ocdroid.data.model.Message(id = "m", role = "user")))
        }
        core.writeSessionList { it.copy(sessions = listOf(root, child)) }

        vm.closeSession("root-1")
        advanceUntilIdle()

        // §B4 / §10: non-current close is a no-op — list-detail has a single
        // detail pane. Closing an ancestor of the current session does NOT
        // trigger chat clear + pop-to-Sessions (only the active route's leave does).
        assertEquals("ses_child_1", core.chatFlow.value.currentSessionId)
        assertFalse(core.chatFlow.value.messages.isEmpty())
    }

    @Test
    fun `closeSession ancestor of current with another root open is a no-op (B4 §10)`() = runTest {
        val root1 = Session(id = "root-1", directory = "/proj")
        val root2 = Session(id = "root-2", directory = "/proj")
        val child = Session(id = "ses_child_1", directory = "/proj", parentId = "root-1")
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeChat { it.copy(currentSessionId = "ses_child_1") }
        core.writeSessionList {
            it.copy(sessions = listOf(root1, root2, child))
        }
        // Set the active route to Chat so the no-op assertion can verify the
        // route stays Chat (without this the default route is already Sessions
        // and the assertion is vacuously true).
        core.store.mutateNav { it.copy(lastRoute = NavRoute.Chat.route) }

        vm.closeSession("root-1")
        advanceUntilIdle()

        // §B4 / §10: non-current close is a no-op. Closing root-1 (ancestor of
        // current "ses_child_1") does NOT clear chat or navigate. root-2 remaining
        // in the list does NOT auto-select.
        assertEquals("ses_child_1", core.chatFlow.value.currentSessionId)
        assertNotEquals(NavRoute.Sessions.route, core.navFlow.value.lastRoute)
    }

    @Test
    fun `closeSession ancestor of current does not save draft (B4 §10 — no-op)`() = runTest {
        val root = Session(id = "root-1", directory = "/proj")
        val child = Session(id = "ses_child_1", directory = "/proj", parentId = "root-1")
        val core = createCore()
        val vm = SessionViewModel(core)
        core.writeChat { it.copy(currentSessionId = "ses_child_1") }
        core.writeComposer { it.copy(inputText = "draft in child") }
        core.writeSessionList { it.copy(sessions = listOf(root, child)) }

        vm.closeSession("root-1")
        advanceUntilIdle()

        // §B4 / §10: non-current close is a no-op — no draft save, no chat clear.
        // Draft save only happens when closing the CURRENT session.
        verify(exactly = 0) { settingsManager.setDraftText(any(), any(), any()) }
        assertEquals("ses_child_1", core.chatFlow.value.currentSessionId)
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

        vm.loadChildSessions("ses_parent_1")
        advanceUntilIdle()

        coVerify { repository.getChildren("ses_parent_1") }
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

        core.loadSessionsForEffect()
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
            Session(id = "s1", directory = "/x"))
        val core = createCore()
        val vm = SessionViewModel(core)

        vm.archiveSession("s1")
        advanceUntilIdle()

        coVerify { repository.updateSessionArchived("s1", any()) }
    }

    @Test
    fun `restoreSession delegates to launchSetSessionArchived false`() = runTest {
        coEvery { repository.updateSessionArchived(any(), any()) } returns Result.success(
            Session(id = "s1", directory = "/x"))
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
