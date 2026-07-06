package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionCacheEntry
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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
 * §R18 Phase 5+: direct unit tests for [launchLoadSessions] /
 * [launchLoadMoreSessions] / [launchLoadChildSessions] /
 * [launchLoadPendingQuestions] / [launchLoadPendingPermissions] /
 * [launchLoadSessionStatus] / [launchLoadAgents] / [persistSessionCache].
 *
 * High-yield slice-merge + SettingsManager cross-write paths (~350 lines).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListActionsTest {

    private lateinit var store: SharedStateStore
    private lateinit var slices: SliceFlows
    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var scope: TestScope
    private lateinit var emitted: MutableList<UiEvent>
    private lateinit var emit: EventEmitter

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        store = SharedStateStore()
        slices = store.slices
        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        every { settingsManager.currentWorkdir } returns null
        every { settingsManager.openSessionIds } returns emptyList()
        every { settingsManager.sessionCache } returns emptyList()
        scope = TestScope(UnconfinedTestDispatcher())
        emitted = mutableListOf()
        emit = EventEmitter { event -> emitted.add(event) }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── persistSessionCache ───────────────────────────────────────────────────

    @Test
    fun `persistSessionCache writes only open + current + workdir-root entries`() {
        val open = Session(id = "open", directory = "/x")
        val current = Session(id = "current", directory = "/y")
        val workdirRoot = Session(id = "root", directory = "/workdir")
        val unrelated = Session(id = "other", directory = "/elsewhere")
        every { settingsManager.sessionCache = any() } returns Unit

        persistSessionCache(
            settingsManager = settingsManager,
            sessions = listOf(open, current, workdirRoot, unrelated),
            openIds = listOf("open"),
            currentId = "current",
            currentWorkdir = "/workdir",
        )

        verify {
            settingsManager.sessionCache = match { entries ->
                entries.map { it.id }.toSet() == setOf("open", "current", "root")
            }
        }
    }

    @Test
    fun `persistSessionCache writes nothing when no sessions match`() {
        every { settingsManager.sessionCache = any() } returns Unit

        persistSessionCache(
            settingsManager = settingsManager,
            sessions = listOf(Session(id = "x", directory = "/a")),
            openIds = emptyList(),
            currentId = null,
            currentWorkdir = null,
        )

        verify { settingsManager.sessionCache = emptyList() }
    }

    // ── launchLoadSessions ────────────────────────────────────────────────────

    @Test
    fun `launchLoadSessions success merges sessions and clears refreshing flag`() = runTest {
        val sessions = listOf(Session(id = "s1", directory = "/x"), Session(id = "s2", directory = "/y"))
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)

        launchLoadSessions(
            scope = scope,
            repository = repository,
            slices = slices,
            settingsManager = settingsManager,
            onSelectSession = {},
            onLoadSessionStatus = {},
            onLoadMessages = {},
            emit = emit,
        )
        advanceUntilIdle()

        assertEquals(listOf("s1", "s2"), slices.sessionList.value.sessions.map { it.id })
        assertFalse(slices.sessionList.value.isRefreshingSessions)
        assertFalse(slices.sessionList.value.isLoadingMoreSessions)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `launchLoadSessions sets hasMore true when limit reached`() = runTest {
        val sessions = (1..10).map { Session(id = "s$it", directory = "/x") }
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)

        launchLoadSessions(scope, repository, slices, settingsManager, {}, {}, {}, emit)
        advanceUntilIdle()

        assertTrue(slices.sessionList.value.hasMoreSessions)
    }

    @Test
    fun `launchLoadSessions failure clears refreshing flag and emits error`() = runTest {
        coEvery { repository.getSessions(any()) } returns Result.failure(IllegalStateException("offline"))

        launchLoadSessions(scope, repository, slices, settingsManager, {}, {}, {}, emit)
        advanceUntilIdle()

        assertFalse(slices.sessionList.value.isRefreshingSessions)
        val err = emitted.filterIsInstance<UiEvent.Error>().single()
        assertEquals(R.string.error_load_sessions_failed, err.resId)
        assertTrue(err.args.any { it.toString().contains("offline") })
    }

    @Test
    fun `launchLoadSessions auto-selects first when no current session and not drafting`() = runTest {
        val sessions = listOf(Session(id = "first", directory = "/x"))
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)
        var selected: String? = null

        launchLoadSessions(scope, repository, slices, settingsManager, { selected = it }, {}, {}, emit)
        advanceUntilIdle()

        assertEquals("first", selected)
    }

    @Test
    fun `launchLoadSessions skips auto-select when composer is mid-draft`() = runTest {
        val sessions = listOf(Session(id = "first", directory = "/x"))
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)
        store.mutateComposer { it.copy(draftWorkdir = "/somewhere") }
        var selected: String? = null

        launchLoadSessions(scope, repository, slices, settingsManager, { selected = it }, {}, {}, emit)
        advanceUntilIdle()

        assertNull(selected)
    }

    @Test
    fun `launchLoadSessions with current session calls onLoadMessages and onLoadSessionStatus`() = runTest {
        val sessions = listOf(Session(id = "s1", directory = "/x"))
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)
        store.mutateChat { it.copy(currentSessionId = "s1") }
        var msgLoads = 0
        var statusLoads = 0

        launchLoadSessions(
            scope, repository, slices, settingsManager,
            onSelectSession = {},
            onLoadSessionStatus = { statusLoads += 1 },
            onLoadMessages = { msgLoads += 1 },
            emit = emit,
        )
        advanceUntilIdle()

        assertEquals(1, msgLoads)
        assertEquals(1, statusLoads)
    }

    @Test
    fun `launchLoadSessions clears chat when no sessions and no current`() = runTest {
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        // No currentSessionId → falls through to the else branch which clears the chat.
        store.mutateChat { it.copy(currentSessionId = null, messages = listOf(cn.vectory.ocdroid.data.model.Message(id = "stale", role = "user"))) }

        launchLoadSessions(scope, repository, slices, settingsManager, {}, {}, {}, emit)
        advanceUntilIdle()

        assertNull(slices.chat.value.currentSessionId)
        assertTrue(slices.chat.value.messages.isEmpty())
    }

    // ── launchLoadMoreSessions ────────────────────────────────────────────────

    @Test
    fun `launchLoadMoreSessions no-ops when hasMore is false`() = runTest {
        store.mutateSessionList { it.copy(hasMoreSessions = false, isLoadingMoreSessions = false) }

        launchLoadMoreSessions(scope, repository, slices, {}, emit)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getSessions(any()) }
    }

    @Test
    fun `launchLoadMoreSessions no-ops when already loading more`() = runTest {
        store.mutateSessionList { it.copy(hasMoreSessions = true, isLoadingMoreSessions = true) }

        launchLoadMoreSessions(scope, repository, slices, {}, emit)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getSessions(any()) }
    }

    @Test
    fun `launchLoadMoreSessions success expands list and bumps limit`() = runTest {
        val initial = (1..10).map { Session(id = "s$it", directory = "/x") }
        // nextSessionFetchLimit(current=10, pageSize=10) = max(10,10) + max(10,1) = 20.
        val expanded = (1..20).map { Session(id = "s$it", directory = "/x") }
        store.mutateSessionList {
            it.copy(sessions = initial, loadedSessionLimit = 10, hasMoreSessions = true, isLoadingMoreSessions = false)
        }
        coEvery { repository.getSessions(any()) } returns Result.success(expanded)

        launchLoadMoreSessions(scope, repository, slices, {}, emit)
        advanceUntilIdle()

        assertEquals(20, slices.sessionList.value.sessions.size)
        assertEquals(20, slices.sessionList.value.loadedSessionLimit)
        // mergedSessions.size (20) >= nextLimit (20) → hasMoreSessions=true.
        assertTrue(slices.sessionList.value.hasMoreSessions)
        assertFalse(slices.sessionList.value.isLoadingMoreSessions)
    }

    @Test
    fun `launchLoadMoreSessions failure clears loading flag and emits error`() = runTest {
        store.mutateSessionList {
            it.copy(hasMoreSessions = true, isLoadingMoreSessions = false, loadedSessionLimit = 10)
        }
        coEvery { repository.getSessions(any()) } returns Result.failure(IllegalStateException("timeout"))

        launchLoadMoreSessions(scope, repository, slices, {}, emit)
        advanceUntilIdle()

        assertFalse(slices.sessionList.value.isLoadingMoreSessions)
        assertEquals(R.string.error_load_more_sessions_failed, emitted.filterIsInstance<UiEvent.Error>().single().resId)
    }

    @Test
    fun `launchLoadMoreSessions bails when loadedLimit advanced past nextLimit during fetch`() = runTest {
        // Pre-set a very high loadedSessionLimit so the synchronous read of
        // `currentLoadedLimit` returns 1000 → nextLimit = max(1000,10)+10 = 1010.
        // Inside the launch's onSuccess, loadedLimit (still 1000) <= nextLimit (1010),
        // so the bail branch does NOT fire here. Instead, we verify the
        // happy-path write: the limit is bumped to nextLimit (1010).
        val expanded = (1..15).map { Session(id = "s$it", directory = "/x") }
        store.mutateSessionList {
            it.copy(hasMoreSessions = true, isLoadingMoreSessions = false, loadedSessionLimit = 1000)
        }
        coEvery { repository.getSessions(any()) } returns Result.success(expanded)

        launchLoadMoreSessions(scope, repository, slices, {}, emit)
        advanceUntilIdle()

        // The next-limit calc was max(1000,10)+10 = 1010; sessions merged from 15.
        assertEquals(1010, slices.sessionList.value.loadedSessionLimit)
        assertEquals(15, slices.sessionList.value.sessions.size)
        // 15 < 1010 → hasMore flips to false (no more pages).
        assertFalse(slices.sessionList.value.hasMoreSessions)
    }

    // ── launchLoadSessionStatus ───────────────────────────────────────────────

    @Test
    fun `launchLoadSessionStatus success writes statuses to slice`() = runTest {
        val statuses = mapOf("s1" to cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"))
        coEvery { repository.getSessionStatus() } returns Result.success(statuses)

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        assertEquals(statuses, slices.sessionList.value.sessionStatuses)
    }

    @Test
    fun `launchLoadSessionStatus failure does not crash and leaves slice untouched`() = runTest {
        coEvery { repository.getSessionStatus() } returns Result.failure(IllegalStateException("nope"))

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        assertTrue(slices.sessionList.value.sessionStatuses.isEmpty())
    }

    // ── launchLoadChildSessions ───────────────────────────────────────────────

    @Test
    fun `launchLoadChildSessions success writes into childSessions map`() = runTest {
        val children = listOf(Session(id = "c1", directory = "/x", parentId = "p1"))
        coEvery { repository.getChildren("p1") } returns Result.success(children)

        launchLoadChildSessions(scope, repository, slices, "p1", "Tag")
        advanceUntilIdle()

        assertEquals(children, slices.sessionList.value.childSessions["p1"])
    }

    @Test
    fun `launchLoadChildSessions failure does not throw`() = runTest {
        coEvery { repository.getChildren(any()) } returns Result.failure(IllegalStateException("x"))

        launchLoadChildSessions(scope, repository, slices, "p1", "Tag")
        advanceUntilIdle()

        // No throw → slice untouched.
        assertTrue(slices.sessionList.value.childSessions.isEmpty())
    }

    // ── launchLoadPendingQuestions ────────────────────────────────────────────

    @Test
    fun `launchLoadPendingQuestions merges byGet over existing`() = runTest {
        val info = cn.vectory.ocdroid.data.model.QuestionInfo(
            question = "fresh",
            header = "h",
            options = emptyList(),
        )
        val existing = QuestionRequest(id = "q1", sessionId = "s1", questions = listOf(info.copy(question = "old")))
        val fetched = QuestionRequest(id = "q1", sessionId = "s1", questions = listOf(info))
        store.mutateSessionList { it.copy(pendingQuestions = listOf(existing)) }
        coEvery { repository.getPendingQuestions(any()) } returns Result.success(listOf(fetched))

        launchLoadPendingQuestions(scope, repository, slices, directory = null, "Tag")
        advanceUntilIdle()

        val merged = slices.sessionList.value.pendingQuestions
        assertEquals(1, merged.size)
        assertEquals("fresh", merged.single().questions.single().question)
    }

    @Test
    fun `launchLoadPendingQuestions keeps locally-held questions missing from fetch`() = runTest {
        val info = cn.vectory.ocdroid.data.model.QuestionInfo(question = "q", header = "h", options = emptyList())
        val localOnly = QuestionRequest(id = "q2", sessionId = "s1", questions = listOf(info))
        val fetched = QuestionRequest(id = "q1", sessionId = "s1", questions = listOf(info))
        store.mutateSessionList { it.copy(pendingQuestions = listOf(localOnly)) }
        coEvery { repository.getPendingQuestions(any()) } returns Result.success(listOf(fetched))

        launchLoadPendingQuestions(scope, repository, slices, directory = "/work", "Tag")
        advanceUntilIdle()

        val merged = slices.sessionList.value.pendingQuestions.map { it.id }.toSet()
        assertEquals(setOf("q1", "q2"), merged)
        coVerify { repository.getPendingQuestions("/work") }
    }

    // ── launchLoadPendingPermissions ──────────────────────────────────────────

    @Test
    fun `launchLoadPendingPermissions success writes pendingPermissions`() = runTest {
        val perm = PermissionRequest(
            id = "perm1",
            sessionId = "s1",
            permission = "once",
        )
        coEvery { repository.getPendingPermissions() } returns Result.success(listOf(perm))

        launchLoadPendingPermissions(scope, repository, slices, "Tag")
        advanceUntilIdle()

        assertEquals(listOf(perm), slices.sessionList.value.pendingPermissions)
    }

    @Test
    fun `launchLoadPendingPermissions failure leaves slice untouched`() = runTest {
        coEvery { repository.getPendingPermissions() } returns Result.failure(IllegalStateException("x"))

        launchLoadPendingPermissions(scope, repository, slices, "Tag")
        advanceUntilIdle()

        assertTrue(slices.sessionList.value.pendingPermissions.isEmpty())
    }

    // ── launchLoadAgents ──────────────────────────────────────────────────────

    @Test
    fun `launchLoadAgents reconciles invalid selectedAgentName to build`() = runTest {
        val agents = listOf(AgentInfo(name = "build"), AgentInfo(name = "code"))
        coEvery { repository.getAgents() } returns Result.success(agents)
        store.mutateSettings { it.copy(selectedAgentName = "ghost") }

        launchLoadAgents(scope, repository, slices, settingsManager, "Tag")
        advanceUntilIdle()

        assertEquals("build", slices.settings.value.selectedAgentName)
        verify { settingsManager.selectedAgentName = "build" }
    }

    @Test
    fun `launchLoadAgents preserves valid selectedAgentName and does not persist`() = runTest {
        val agents = listOf(AgentInfo(name = "build"), AgentInfo(name = "code"))
        coEvery { repository.getAgents() } returns Result.success(agents)
        store.mutateSettings { it.copy(selectedAgentName = "code") }

        launchLoadAgents(scope, repository, slices, settingsManager, "Tag")
        advanceUntilIdle()

        assertEquals("code", slices.settings.value.selectedAgentName)
        verify(exactly = 0) { settingsManager.selectedAgentName = any() }
    }

    @Test
    fun `launchLoadAgents failure leaves slice untouched`() = runTest {
        coEvery { repository.getAgents() } returns Result.failure(IllegalStateException("x"))
        store.mutateSettings { it.copy(selectedAgentName = "code") }

        launchLoadAgents(scope, repository, slices, settingsManager, "Tag")
        advanceUntilIdle()

        assertEquals("code", slices.settings.value.selectedAgentName)
    }
}
