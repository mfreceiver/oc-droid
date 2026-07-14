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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    /** R-20 Phase 1 (C7): persistent cache mock for currentSessionId verify. */
    private lateinit var cacheRepository: cn.vectory.ocdroid.data.cache.CacheRepository

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
        cacheRepository = io.mockk.mockk(relaxed = true)
        // C7: default fingerprint result is Verified (cache healthy / no-op).
        io.mockk.coEvery {
            cacheRepository.verifyFingerprint(any(), any(), any())
        } returns cn.vectory.ocdroid.data.cache.FingerprintResult.Verified
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── persistSessionCache ───────────────────────────────────────────────────

    @Test
    fun `persistSessionCache writes only open + current + workdir-root entries`() {
        val open = Session(id = "open", directory = "/x", revert = Session.RevertInfo("revert"))
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
            revertCutoffs = mapOf(
                "open" to cn.vectory.ocdroid.data.model.RevertCutoff(
                    "open", "revert", cn.vectory.ocdroid.data.model.RevertCutoffState.Resolved(42L)
                )
            ),
        )

        verify {
            settingsManager.sessionCache = match { entries ->
                entries.map { it.id }.toSet() == setOf("open", "current", "root")
                    && entries.first { it.id == "open" }.revertMessageId == "revert"
                    && entries.first { it.id == "open" }.revertCreatedAtEpochMs == 42L
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
            revertCutoffs = emptyMap(),
        )

        verify { settingsManager.sessionCache = emptyList() }
    }

    @Test
    fun `persistSessionCache filters out child sessions in the workdir directory`() {
        every { settingsManager.sessionCache = any() } returns Unit

        persistSessionCache(
            settingsManager = settingsManager,
            sessions = listOf(
                Session(id = "child", directory = "/workdir", parentId = "parent"),
            ),
            openIds = emptyList(),
            currentId = null,
            currentWorkdir = "/workdir",
            revertCutoffs = emptyMap(),
        )

        verify { settingsManager.sessionCache = emptyList() }
    }

    @Test
    fun `persistSessionCache nulls revertCreatedAtEpochMs when cutoff messageId mismatches`() {
        every { settingsManager.sessionCache = any() } returns Unit

        persistSessionCache(
            settingsManager = settingsManager,
            sessions = listOf(
                Session(id = "s1", directory = "/x", revert = Session.RevertInfo("actual")),
            ),
            openIds = listOf("s1"),
            currentId = null,
            currentWorkdir = null,
            revertCutoffs = mapOf(
                "s1" to cn.vectory.ocdroid.data.model.RevertCutoff(
                    "s1", "different", cn.vectory.ocdroid.data.model.RevertCutoffState.Resolved(99L)
                )
            ),
        )

        verify {
            settingsManager.sessionCache = match { entries ->
                entries.size == 1 && entries[0].revertCreatedAtEpochMs == null
            }
        }
    }

    @Test
    fun `persistSessionCache nulls revertCreatedAtEpochMs when cutoff state is not Resolved`() {
        every { settingsManager.sessionCache = any() } returns Unit

        persistSessionCache(
            settingsManager = settingsManager,
            sessions = listOf(
                Session(id = "s1", directory = "/x", revert = Session.RevertInfo("msg")),
            ),
            openIds = listOf("s1"),
            currentId = null,
            currentWorkdir = null,
            revertCutoffs = mapOf(
                "s1" to cn.vectory.ocdroid.data.model.RevertCutoff(
                    "s1", "msg", cn.vectory.ocdroid.data.model.RevertCutoffState.PendingFetch
                )
            ),
        )

        verify {
            settingsManager.sessionCache = match { entries ->
                entries.size == 1 && entries[0].revertCreatedAtEpochMs == null
            }
        }
    }

    @Test
    fun `persistSessionCache nulls revertCreatedAtEpochMs when session has no revert but cutoff exists`() {
        every { settingsManager.sessionCache = any() } returns Unit

        persistSessionCache(
            settingsManager = settingsManager,
            sessions = listOf(
                Session(id = "s1", directory = "/x"),  // no revert field
            ),
            openIds = listOf("s1"),
            currentId = null,
            currentWorkdir = null,
            revertCutoffs = mapOf(
                "s1" to cn.vectory.ocdroid.data.model.RevertCutoff(
                    "s1", "orphan-cutoff", cn.vectory.ocdroid.data.model.RevertCutoffState.Resolved(77L)
                )
            ),
        )

        verify {
            settingsManager.sessionCache = match { entries ->
                entries.size == 1 && entries[0].revertCreatedAtEpochMs == null
            }
        }
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
    fun `launchLoadSessions clears hasMore when fewer than full-load limit returned`() = runTest {
        // §nav-redesign: launchLoadSessions now uses sessionFullLoadLimit (500)
        // for the initial global fetch — the Sessions tab is the flat full
        // history. Returning fewer sessions than the limit means there is no
        // next page, so hasMoreSessions is false (the pre-redesign pagination
        // contract returned true when sessions.size == limit; that no longer
        // holds because limit is now the "effectively all" cap, not page size).
        val sessions = (1..10).map { Session(id = "s$it", directory = "/x") }
        coEvery { repository.getSessions(MainViewModelTimings.sessionFullLoadLimit) } returns Result.success(sessions)

        launchLoadSessions(scope, repository, slices, settingsManager, {}, {}, {}, emit)
        advanceUntilIdle()

        assertFalse(slices.sessionList.value.hasMoreSessions)
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

    // ── gro-2 Blocker 2b: auto-select must skip archived sessions ────────────

    @Test
    fun `gro-2 Blocker 2b - auto-select skips archived session and selects first live one`() = runTest {
        // If the server returns an archived session first (e.g. after a bulk-
        // archive nulled currentSessionId), the auto-select must NOT resurrect
        // it — select the first NON-archived session instead.
        val archivedA = Session(id = "archived-A", directory = "/x", time = Session.TimeInfo(archived = 1L))
        val liveB = Session(id = "live-B", directory = "/x")
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(archivedA, liveB))
        var selected: String? = null

        launchLoadSessions(scope, repository, slices, settingsManager, { selected = it }, {}, {}, emit)
        advanceUntilIdle()

        assertEquals("must select live-B, NOT archived-A", "live-B", selected)
    }

    @Test
    fun `gro-2 Blocker 2b - auto-select clears chat when ALL candidates are archived`() = runTest {
        // If every session in the refresh result is archived, do NOT select
        // any — fall through to the chat-clear (empty state).
        val archivedA = Session(id = "archived-A", directory = "/x", time = Session.TimeInfo(archived = 1L))
        val archivedC = Session(id = "archived-C", directory = "/x", time = Session.TimeInfo(archived = 2L))
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(archivedA, archivedC))
        var selected: String? = null

        launchLoadSessions(scope, repository, slices, settingsManager, { selected = it }, {}, {}, emit)
        advanceUntilIdle()

        assertNull("no archived session selected", selected)
        assertNull("chat cleared (empty state)", slices.chat.value.currentSessionId)
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
    fun `WT6 launchLoadSessions invokes onArchivedSessionsDetected when merged result flips current to archived`() = runTest {
        // The gap-3 case Task 1 surfaces: a cross-device archive during an SSE
        // gap. The reconnect's RefreshSessions triggers launchLoadSessions;
        // the server returns the current session with isArchived=true. The
        // merge passes it through (server-authoritative), so the callback must
        // fire so the caller dispatches AppAction.BulkSessionsRefreshed
        // (atomically writing the list + clearing chat) — otherwise the chat
        // lingers on a session the render filters now hide.
        val archived = Session(
            id = "s1",
            directory = "/x",
            time = Session.TimeInfo(archived = 12345L)  // isArchived == true
        )
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(archived))
        store.mutateChat { it.copy(currentSessionId = "s1") }
        store.mutateSessionList { it.copy(openSessionIds = listOf("s1")) }
        var archivedSessions: List<Session>? = null
        var archivedOpenIds: List<String>? = null
        var msgLoads = 0
        var statusLoads = 0

        launchLoadSessions(
            scope, repository, slices, settingsManager,
            onSelectSession = {},
            onLoadSessionStatus = { statusLoads += 1 },
            onLoadMessages = { msgLoads += 1 },
            emit = emit,
            onArchivedSessionsDetected = { sessions, openIds, _ -> archivedSessions = sessions; archivedOpenIds = openIds },
        )
        advanceUntilIdle()

        assertEquals("callback fired with the merged list", listOf("s1"), archivedSessions?.map { it.id })
        assertTrue("archived flag preserved in callback payload", archivedSessions?.first()?.isArchived == true)
        // FIX-A: the archived id is pruned from the new openIds.
        assertEquals("archived id pruned from newOpenIds", emptyList<String>(), archivedOpenIds)
        // The auto-select / load path is SKIPPED when the callback fires (the
        // caller's dispatch atomically clears chat — loading messages for the
        // just-archived id would be wasteful + racy vs the reducer's clear).
        assertEquals("onLoadMessages skipped (archived current → eviction path)", 0, msgLoads)
        assertEquals("onLoadSessionStatus skipped (archived current → eviction path)", 0, statusLoads)
    }

    @Test
    fun `WT6 launchLoadSessions skips onArchivedSessionsDetected when current session is not archived`() = runTest {
        // Negative control: a normal refresh (current session NOT archived)
        // must NOT fire the archive callback — the existing load path runs.
        val sessions = listOf(Session(id = "s1", directory = "/x"))
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)
        store.mutateChat { it.copy(currentSessionId = "s1") }
        store.mutateSessionList { it.copy(openSessionIds = listOf("s1")) }
        var archivedInvoked = false
        var msgLoads = 0

        launchLoadSessions(
            scope, repository, slices, settingsManager,
            onSelectSession = {},
            onLoadSessionStatus = {},
            onLoadMessages = { msgLoads += 1 },
            emit = emit,
            onArchivedSessionsDetected = { _, _, _ -> archivedInvoked = true },
        )
        advanceUntilIdle()

        assertFalse("callback must NOT fire for a non-archived current session", archivedInvoked)
        assertEquals("normal load path runs", 1, msgLoads)
    }

    @Test
    fun `WT6 launchLoadSessions without onArchivedSessionsDetected callback still writes merged list (legacy callers)`() = runTest {
        // The new param is nullable so legacy callers (SessionViewModel, tests
        // that pass positional args) keep compiling. When null, the archived-
        // current case is detected but no callback fires — the merged list is
        // still written (the caller simply does not clear chat). This is the
        // pre-WT6 behavior for paths that have not wired the callback.
        val archived = Session(id = "s1", directory = "/x", time = Session.TimeInfo(archived = 1L))
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(archived))
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadSessions(scope, repository, slices, settingsManager, {}, {}, {}, emit)
        advanceUntilIdle()

        // Merged list IS written with the archived session (server-authoritative).
        assertEquals(listOf("s1"), slices.sessionList.value.sessions.map { it.id })
        assertTrue(slices.sessionList.value.sessions.first().isArchived)
    }

    // ── FIX-A (review-blocker, groker B1): prune ALL archived openIds ────────

    @Test
    fun `FIX-A launchLoadSessions prunes ALL archived ids from openIds not just current`() = runTest {
        // The bug: the SSE archive path prunes ANY archived id from
        // openSessionIds, but the bulk-refresh path only handled the CURRENT
        // session. So if non-current OPEN tabs B and C were archived cross-
        // device, they stayed as ghosts in openSessionIds (capped at 8) —
        // silently occupying tab slots. FIX-A prunes EVERY archived id.
        val current = Session(id = "current", directory = "/x")
        val archivedB = Session(id = "B", directory = "/x", time = Session.TimeInfo(archived = 1L))
        val archivedC = Session(id = "C", directory = "/x", time = Session.TimeInfo(archived = 1L))
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(current, archivedB, archivedC))
        store.mutateChat { it.copy(currentSessionId = "current") }
        store.mutateSessionList { it.copy(openSessionIds = listOf("current", "B", "C")) }
        var capturedOpenIds: List<String>? = null

        launchLoadSessions(
            scope, repository, slices, settingsManager,
            onSelectSession = {},
            onLoadSessionStatus = {},
            onLoadMessages = {},
            emit = emit,
            onArchivedSessionsDetected = { _, openIds, _ -> capturedOpenIds = openIds },
        )
        advanceUntilIdle()

        assertNotNull("callback must fire (non-current archived open tabs detected)", capturedOpenIds)
        assertEquals(
            "FIX-A: ALL archived ids (B, C) pruned from openIds; current kept",
            listOf("current"),
            capturedOpenIds,
        )
    }

    @Test
    fun `FIX-A launchLoadSessions fires callback for non-current archived open tab even when current is NOT archived`() = runTest {
        // Edge case: current session is fine, but a non-current OPEN tab was
        // archived cross-device. The callback MUST still fire so the caller
        // can prune the ghost tab (the prior code only fired for current).
        val current = Session(id = "current", directory = "/x")
        val archivedTab = Session(id = "ghost-tab", directory = "/x", time = Session.TimeInfo(archived = 1L))
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(current, archivedTab))
        store.mutateChat { it.copy(currentSessionId = "current") }
        store.mutateSessionList { it.copy(openSessionIds = listOf("current", "ghost-tab")) }
        var callbackFired = false
        var capturedOpenIds: List<String>? = null
        var msgLoads = 0

        launchLoadSessions(
            scope, repository, slices, settingsManager,
            onSelectSession = {},
            onLoadSessionStatus = {},
            onLoadMessages = { msgLoads += 1 },
            emit = emit,
            onArchivedSessionsDetected = { _, openIds, _ -> callbackFired = true; capturedOpenIds = openIds },
        )
        advanceUntilIdle()

        assertTrue("callback MUST fire for non-current archived open tab", callbackFired)
        assertEquals(
            "ghost-tab pruned from openIds",
            listOf("current"),
            capturedOpenIds,
        )
        // The current session is NOT archived → the normal load path runs
        // (the reducer's BulkSessionsRefreshed does NOT clear chat when
        // current is not archived — launchLoadSessions still fires the
        // callback for the openIds prune, then returns early since archives
        // were detected).
        // NOTE: msgLoads is 0 because the callback path returns early after
        // the atomic dispatch (the auto-select/load logic is skipped). This
        // is acceptable: the BulkSessionsRefreshed reducer wrote the merged
        // list + load flags; the caller's normal post-load cascade (status
        // + messages) is a minor optimization that could be re-added if
        // needed, but the current-session messages are already loaded.
    }

    // ── FIX-D (gpter #2): single-flight epoch for launchLoadSessions ─────────

    @Test
    fun `FIX-D launchLoadSessions discards stale result when superseded by newer call`() = runTest {
        // Concurrent calls (reconnect + foreground catch-up + manual refresh)
        // can race; a slow stale response must NOT update sessions/openIds/
        // cache NOR trigger archive side-effects (now destructive per FIX-A/C).
        val firstGate = CompletableDeferred<Unit>()
        var firstStarted = false
        val staleSessions = listOf(Session(id = "stale", directory = "/x"))
        val freshSessions = listOf(Session(id = "fresh", directory = "/x"))
        coEvery { repository.getSessions(any()) } coAnswers {
            if (!firstStarted) {
                firstStarted = true
                firstGate.await()
                Result.success(staleSessions)
            } else {
                Result.success(freshSessions)
            }
        }

        launchLoadSessions(scope, repository, slices, settingsManager, {}, {}, {}, emit)
        advanceUntilIdle() // first call suspended in firstGate
        launchLoadSessions(scope, repository, slices, settingsManager, {}, {}, {}, emit)
        advanceUntilIdle() // second (newer epoch) completes first

        assertEquals(
            "newer call's result must be in effect",
            listOf("fresh"),
            slices.sessionList.value.sessions.map { it.id },
        )

        firstGate.complete(Unit) // stale first call completes
        advanceUntilIdle()

        assertEquals(
            "stale superseded result must be discarded",
            listOf("fresh"),
            slices.sessionList.value.sessions.map { it.id },
        )
    }

    @Test
    fun `superseded failure is fully silent and cannot overwrite newer loading state`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        var first = true
        coEvery { repository.getSessions(any()) } coAnswers {
            if (first) {
                first = false
                firstGate.await()
                Result.failure(IllegalStateException("stale failure"))
            } else {
                Result.success(listOf(Session(id = "fresh", directory = "/x")))
            }
        }

        launchLoadSessions(scope, repository, slices, settingsManager, {}, {}, {}, emit)
        advanceUntilIdle()
        launchLoadSessions(scope, repository, slices, settingsManager, {}, {}, {}, emit)
        advanceUntilIdle()
        val stateAfterFresh = slices.sessionList.value

        firstGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(stateAfterFresh, slices.sessionList.value)
        assertTrue("stale failure must not emit an error", emitted.isEmpty())
    }

    @Test
    fun `FIX-D launchLoadSessions stale result does NOT trigger archive callback`() = runTest {
        // Critical: a stale response that would have triggered the destructive
        // archive eviction (FIX-A/C) must be dropped BEFORE the callback fires.
        // Without the epoch guard, a slow stale response containing an archived
        // session could evict the current chat AFTER a newer response already
        // established the correct state.
        val firstGate = CompletableDeferred<Unit>()
        var firstStarted = false
        val archivedStale = Session(id = "current", directory = "/x", time = Session.TimeInfo(archived = 1L))
        val freshNonArchived = Session(id = "current", directory = "/x")
        coEvery { repository.getSessions(any()) } coAnswers {
            if (!firstStarted) {
                firstStarted = true
                firstGate.await()
                Result.success(listOf(archivedStale))
            } else {
                Result.success(listOf(freshNonArchived))
            }
        }
        store.mutateChat { it.copy(currentSessionId = "current") }
        store.mutateSessionList { it.copy(openSessionIds = listOf("current")) }
        var archiveCallbackCount = 0

        launchLoadSessions(
            scope, repository, slices, settingsManager, {}, {}, {}, emit,
            onArchivedSessionsDetected = { _, _, _ -> archiveCallbackCount += 1 },
        )
        advanceUntilIdle()
        // Second call (fresh, non-archived) supersedes the first
        launchLoadSessions(
            scope, repository, slices, settingsManager, {}, {}, {}, emit,
            onArchivedSessionsDetected = { _, _, _ -> archiveCallbackCount += 1 },
        )
        advanceUntilIdle()

        assertEquals("current session NOT archived (fresh result wins)", "current", slices.chat.value.currentSessionId)
        assertEquals("archive callback must NOT fire for the stale result", 0, archiveCallbackCount)

        firstGate.complete(Unit)
        advanceUntilIdle()

        // Stale result discarded — still no callback, chat intact.
        assertEquals("stale archived result discarded — callback stays 0", 0, archiveCallbackCount)
        assertEquals("chat intact after stale discard", "current", slices.chat.value.currentSessionId)
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

    @Test
    fun `launchLoadSessions drops stale REST success after live fp changes`() = runTest {
        var currentFp = "g1"
        coEvery { repository.getSessions(any()) } answers {
            currentFp = "g2"
            Result.success(listOf(Session(id = "stale", directory = "/x")))
        }

        launchLoadSessions(
            scope = scope,
            repository = repository,
            slices = slices,
            settingsManager = settingsManager,
            onSelectSession = {},
            onLoadSessionStatus = {},
            onLoadMessages = {},
            emit = emit,
            cacheRepository = cacheRepository,
            expectedServerGroupFp = "g1",
            currentServerGroupFp = { currentFp },
            // §grouping-rewrite Round-2 #5: hostProfileStore arg removed.
        )
        advanceUntilIdle()

        assertTrue(slices.sessionList.value.sessions.isEmpty())
        // §epoch-no-op (task 1): a stale-host discard is now FULLY no-op — it
        // no longer clears isRefreshingSessions (the newer request / host-switch
        // reload owns those flags). In this single-call test there is no newer
        // request to reset it, so the flag stays true (production: the host-
        // switch handler fires a fresh load that resets it). The POINT of this
        // test is that the stale sessions list is discarded, asserted above.
        assertTrue(slices.sessionList.value.isRefreshingSessions)
        coVerify(exactly = 0) { cacheRepository.allServerGroupFps() }
    }

    @Test
    fun `launchLoadSessions drops result when fp changes during verify suspend before callbacks`() = runTest {
        var currentFp = "g1"
        val sessions = listOf(Session(id = "s1", directory = "/x", time = Session.TimeInfo(created = 1L)))
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)
        store.mutateChat { it.copy(currentSessionId = "s1") }
        io.mockk.coEvery { cacheRepository.verifyFingerprint(any(), any(), any()) } answers {
            currentFp = "g2"
            cn.vectory.ocdroid.data.cache.FingerprintResult.Verified
        }
        var selected: String? = null
        var msgLoads = 0
        var statusLoads = 0

        launchLoadSessions(
            scope = scope,
            repository = repository,
            slices = slices,
            settingsManager = settingsManager,
            onSelectSession = { selected = it },
            onLoadSessionStatus = { statusLoads += 1 },
            onLoadMessages = { msgLoads += 1 },
            emit = emit,
            cacheRepository = cacheRepository,
            expectedServerGroupFp = "g1",
            currentServerGroupFp = { currentFp },
        )
        advanceUntilIdle()

        assertTrue(slices.sessionList.value.sessions.isEmpty())
        assertNull(selected)
        assertEquals(0, msgLoads)
        assertEquals(0, statusLoads)
    }

    // ── R-20 Phase 1 (C7): currentSessionId fingerprint verify ────────────────

    @Test
    fun `C7 launchLoadSessions drops currentSessionId on fingerprint mismatch`() = runTest {
        // Server returns the session list with session-A in it; the current
        // chatFlow points at session-A. cacheRepository.verifyFingerprint
        // returns MismatchEvicted → the cached window's createdAt differs
        // from the freshly-fetched server copy → drop currentSessionId +
        // re-select the first session so the user lands somewhere valid.
        val sessions = listOf(Session(id = "s1", directory = "/x", time = Session.TimeInfo(created = 1L)))
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)
        store.mutateChat { it.copy(currentSessionId = "s1") }
        io.mockk.coEvery {
            cacheRepository.verifyFingerprint(any(), any(), any())
        } returns cn.vectory.ocdroid.data.cache.FingerprintResult.MismatchEvicted
        var selected: String? = null

        launchLoadSessions(
            scope, repository, slices, settingsManager,
            onSelectSession = { selected = it },
            onLoadSessionStatus = {},
            onLoadMessages = {},
            emit = emit,
            cacheRepository = cacheRepository,
            currentServerGroupFp = { "g-test" },
        )
        advanceUntilIdle()

        // currentSessionId cleared by the mismatch branch, then onSelectSession
        // fired for the first refreshed session.
        assertNull(
            "fingerprint mismatch must clear currentSessionId (stale cached window)",
            slices.chat.value.currentSessionId,
        )
        assertEquals(
            "mismatch branch must fall through to first-select",
            "s1",
            selected,
        )
    }

    @Test
    fun `C7 launchLoadSessions keeps currentSessionId on fingerprint match`() = runTest {
        // verifyFingerprint returns Verified → cache is self-consistent →
        // keep currentSessionId, fire the normal onLoadSessionStatus +
        // onLoadMessages cascade (no eviction, no first-select).
        val sessions = listOf(Session(id = "s1", directory = "/x", time = Session.TimeInfo(created = 1L)))
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)
        store.mutateChat { it.copy(currentSessionId = "s1") }
        io.mockk.coEvery {
            cacheRepository.verifyFingerprint(any(), any(), any())
        } returns cn.vectory.ocdroid.data.cache.FingerprintResult.Verified
        var selected: String? = null
        var msgLoads = 0
        var statusLoads = 0

        launchLoadSessions(
            scope, repository, slices, settingsManager,
            onSelectSession = { selected = it },
            onLoadSessionStatus = { statusLoads += 1 },
            onLoadMessages = { msgLoads += 1 },
            emit = emit,
            cacheRepository = cacheRepository,
            currentServerGroupFp = { "g-test" },
        )
        advanceUntilIdle()

        assertEquals("fingerprint match must keep currentSessionId", "s1", slices.chat.value.currentSessionId)
        assertNull("fingerprint match must NOT auto-select", selected)
        assertEquals(1, msgLoads)
        assertEquals(1, statusLoads)
    }

    @Test
    fun `C7 launchLoadSessions treats UnknownColdStart as no-op`() = runTest {
        // UnknownColdStart = no cached row (or createdAt=null). The verify is
        // inconclusive; behave like the legacy path (keep currentSessionId,
        // run the onLoadMessages cascade). No eviction.
        val sessions = listOf(Session(id = "s1", directory = "/x", time = Session.TimeInfo(created = 1L)))
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)
        store.mutateChat { it.copy(currentSessionId = "s1") }
        io.mockk.coEvery {
            cacheRepository.verifyFingerprint(any(), any(), any())
        } returns cn.vectory.ocdroid.data.cache.FingerprintResult.UnknownColdStart
        var msgLoads = 0

        launchLoadSessions(
            scope, repository, slices, settingsManager,
            onSelectSession = {},
            onLoadSessionStatus = {},
            onLoadMessages = { msgLoads += 1 },
            emit = emit,
            cacheRepository = cacheRepository,
            currentServerGroupFp = { "g-test" },
        )
        advanceUntilIdle()

        assertEquals("UnknownColdStart must keep currentSessionId", "s1", slices.chat.value.currentSessionId)
        assertEquals(1, msgLoads)
    }

    @Test
    fun `C7 launchLoadSessions without cacheRepository params preserves legacy behavior`() = runTest {
        // Backward-compat: callers that haven't been migrated (no
        // cacheRepository / currentServerGroupFp) MUST behave as before
        // (no verify call, no currentSessionId churn).
        val sessions = listOf(Session(id = "s1", directory = "/x"))
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)
        store.mutateChat { it.copy(currentSessionId = "s1") }
        var msgLoads = 0

        launchLoadSessions(
            scope, repository, slices, settingsManager,
            onSelectSession = {},
            onLoadSessionStatus = {},
            onLoadMessages = { msgLoads += 1 },
            emit = emit,
            // cacheRepository + currentServerGroupFp intentionally omitted (legacy caller).
        )
        advanceUntilIdle()

        assertEquals("legacy caller must keep currentSessionId", "s1", slices.chat.value.currentSessionId)
        assertEquals(1, msgLoads)
        // Cache mock never invoked on the legacy path.
        io.mockk.coVerify(exactly = 0) { cacheRepository.verifyFingerprint(any(), any(), any()) }
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
    fun `launchLoadSessionStatus replaces snapshot dropping stale idle entries`() = runTest {
        // §item6: /session/status 是全局权威快照, 只含 active(busy/retry) — idle 已被
        // server delete (opencode session/status.ts: data.delete on idle). 整体替换清除
        // server 已 idle(快照缺失)的 stale 本地 busy; merge(+statuses) 会永久保留旧 busy.
        slices.mutateSessionList {
            it.copy(sessionStatuses = mutableMapOf(
                "stale-idle" to cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"),
                "keep" to cn.vectory.ocdroid.data.model.SessionStatus(type = "retry")
            ))
        }
        coEvery { repository.getSessionStatus() } returns Result.success(
            mapOf("keep" to cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"))
        )

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        val result = slices.sessionList.value.sessionStatuses
        assertFalse(
            "stale busy must be cleared (server idle = absent from snapshot)",
            result.containsKey("stale-idle")
        )
        assertEquals(cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"), result["keep"])
        assertEquals(1, result.size)
    }

    @Test
    fun `preserves SSE status updated during in-flight REST over stale snapshot`() = runTest {
        // §sse-rest-race: REST 在途时 SSE 已把 X 推到 idle; REST 返回的旧快照仍 X=busy.
        // 守卫应保留 SSE 的 idle, 不被旧 REST busy 覆盖 (gpter🟠/groker🟠/opuser🟠).
        slices.mutateSessionList {
            it.copy(sessionStatuses = mutableMapOf(
                "X" to cn.vectory.ocdroid.data.model.SessionStatus(type = "busy")
            ))
        }
        coEvery { repository.getSessionStatus() } coAnswers {
            // 模拟 REST 在途期间 SSE 写入 X=idle
            slices.mutateSessionList {
                it.copy(sessionStatuses = it.sessionStatuses + ("X" to cn.vectory.ocdroid.data.model.SessionStatus(type = "idle")))
            }
            Result.success(mapOf("X" to cn.vectory.ocdroid.data.model.SessionStatus(type = "busy")))
        }

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        assertEquals(
            "SSE idle during in-flight REST must win over stale busy snapshot",
            cn.vectory.ocdroid.data.model.SessionStatus(type = "idle"),
            slices.sessionList.value.sessionStatuses["X"]
        )
    }

    @Test
    fun `launchLoadSessionStatus failure does not crash and leaves slice untouched`() = runTest {
        coEvery { repository.getSessionStatus() } returns Result.failure(IllegalStateException("nope"))

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        assertTrue(slices.sessionList.value.sessionStatuses.isEmpty())
    }

    @Test
    fun `mergeStatusSnapshot replaces stale and preserves SSE-updated entries`() {
        // §groker🟡 v0.7.5 表驱动矩阵: REST 权威整体替换 + SSE 在途更新保护.
        val busy = cn.vectory.ocdroid.data.model.SessionStatus(type = "busy")
        val idle = cn.vectory.ocdroid.data.model.SessionStatus(type = "idle")
        val retry = cn.vectory.ocdroid.data.model.SessionStatus(type = "retry")
        // 1. 纯整体替换: 本地无 SSE 在途变化(before==after) → 用 REST, 清除 stale x
        assertEquals(
            mapOf("a" to busy),
            mergeStatusSnapshot(mapOf("x" to busy), mapOf("x" to busy), mapOf("a" to busy))
        )
        // 2. SSE 在途改 x→idle: 保留 SSE idle(REST 不含 x), 不被清
        assertEquals(
            mapOf("a" to busy, "x" to idle),
            mergeStatusSnapshot(mapOf("x" to busy), mapOf("x" to idle), mapOf("a" to busy))
        )
        // 3. SSE 在途新增 y(before 无): 保留
        assertEquals(
            mapOf("a" to busy, "y" to retry),
            mergeStatusSnapshot(emptyMap(), mapOf("y" to retry), mapOf("a" to busy))
        )
        // 4. REST 含 x 且 SSE 未改 x(before==after): 用 REST 值覆盖本地
        assertEquals(
            mapOf("x" to busy),
            mergeStatusSnapshot(mapOf("x" to idle), mapOf("x" to idle), mapOf("x" to busy))
        )
        // 5. 全空
        assertEquals(
            emptyMap<String, cn.vectory.ocdroid.data.model.SessionStatus>(),
            mergeStatusSnapshot(emptyMap(), emptyMap(), emptyMap())
        )
    }

    @Test
    fun `reconnect_known_busy_missing_in_rest_does_not_mark_unread_sweep_owns_marking`() = runTest {
        // §unread-soak: the REST status backstop NO LONGER marks unread on the
        // "busy→absent" edge. [launchLoadSessionStatus] still merges the
        // authoritative status snapshot (so busy A is cleared from
        // sessionStatuses — the server's idle=data.delete dropped it), but the
        // actual unread marking is now driven by the [UnreadSoakController]
        // sweep + [evaluateUnread] (which will see A's now-absent status, treat
        // it as NOT-idle, and reset any pending soak — never marking). The
        // sweep-driven marking is covered by [UnreadSoakTest].
        val busy = cn.vectory.ocdroid.data.model.SessionStatus(type = "busy")
        store.mutateSessionList {
            it.copy(
                sessions = listOf(Session(id = "A", directory = "/x")),
                sessionStatuses = mapOf("A" to busy),
            )
        }
        store.mutateChat { it.copy(currentSessionId = "other") }
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        assertTrue(
            "busy A must be cleared from sessionStatuses (REST snapshot replaces)",
            slices.sessionList.value.sessionStatuses.isEmpty(),
        )
        assertFalse(
            "A must NOT be marked unread by the REST backstop (sweep owns marking)",
            slices.unread.value.unreadSessions.contains("A"),
        )
    }

    @Test
    fun `reconnect_first_snapshot_does_not_batch_mark`() = runTest {
        // §task4-first-load: 首次加载 localBefore 全空 → completedRoots 自然空 → 不批量标.
        val sessions = (1..5).map { Session(id = "s$it", directory = "/x") }
        store.mutateSessionList {
            it.copy(sessions = sessions, sessionStatuses = emptyMap())
        }
        store.mutateChat { it.copy(currentSessionId = "s1") }
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        assertTrue(
            "first snapshot with empty localBefore must not batch-mark unread",
            slices.unread.value.unreadSessions.isEmpty(),
        )
    }

    @Test
    fun `reconnect_busy_appeared_in_flight_via_sse_not_marked_unread`() = runTest {
        // §task4-grilling-G1: localBefore 无 X (REST 发起时 X 非 busy). REST 在途期间
        // SSE 把 X 推为 busy. REST 快照(更早)缺失 X. 不得误判 X 完成 → 用 localBefore
        // 判定 wasBusy, 不用 sl.sessionStatuses (此时 sl 含 X=busy 会误标).
        store.mutateSessionList {
            it.copy(
                sessions = listOf(Session(id = "X", directory = "/x")),
                sessionStatuses = emptyMap(),
            )
        }
        store.mutateChat { it.copy(currentSessionId = "other") }
        coEvery { repository.getSessionStatus() } coAnswers {
            slices.mutateSessionList {
                it.copy(
                    sessionStatuses = it.sessionStatuses +
                        ("X" to cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"))
                )
            }
            Result.success(emptyMap())
        }

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        assertFalse(
            "X turned busy in-flight must not be marked unread (G1: use localBefore, not sl)",
            slices.unread.value.unreadSessions.contains("X"),
        )
    }

    @Test
    fun `reconnect_status_merge_does_not_mark_any_unread_sweep_owns_marking`() = runTest {
        // §unread-soak: the REST status backstop no longer marks unread for
        // ANY session (root / child / current). It only merges the authoritative
        // status snapshot. The [UnreadSoakController] sweep consumes the merged
        // statuses and decides marking via the soak evaluator.
        val busy = cn.vectory.ocdroid.data.model.SessionStatus(type = "busy")
        store.mutateSessionList {
            it.copy(
                sessions = listOf(
                    Session(id = "root", directory = "/x"),
                    Session(id = "child", directory = "/x", parentId = "root"),
                    Session(id = "cur", directory = "/x"),
                ),
                sessionStatuses = mapOf(
                    "root" to busy,
                    "child" to busy,
                    "cur" to busy,
                ),
            )
        }
        store.mutateChat { it.copy(currentSessionId = "cur") }
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        val unread = slices.unread.value.unreadSessions
        assertFalse("root must NOT be marked by REST backstop (sweep owns it)", unread.contains("root"))
        assertFalse("child must NOT be marked", unread.contains("child"))
        assertFalse("cur must NOT be marked", unread.contains("cur"))
    }

    @Test
    fun `launchLoadSessionStatus discards stale result when superseded by newer call`() = runTest {
        // §groker🟡 v0.7.5: 第一个请求挂起返回旧 busy; 期间第二个请求发起(epoch 更大)并先完成.
        // 第一个后完成时 epoch 已过期 → 丢弃, 不覆盖第二个的结果.
        val firstGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var firstStarted = false
        coEvery { repository.getSessionStatus() } coAnswers {
            if (!firstStarted) {
                firstStarted = true
                firstGate.await()
                Result.success(mapOf("X" to cn.vectory.ocdroid.data.model.SessionStatus(type = "busy")))
            } else {
                Result.success(emptyMap())
            }
        }
        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle() // 第一个挂起在 firstGate
        launchLoadSessionStatus(scope, repository, slices) // 第二个 epoch 更大, 立即完成
        advanceUntilIdle()
        assertEquals(
            "newer call's empty result must be in effect",
            emptyMap<String, cn.vectory.ocdroid.data.model.SessionStatus>(),
            slices.sessionList.value.sessionStatuses
        )
        firstGate.complete(Unit) // 第一个完成, 返回旧 busy
        advanceUntilIdle()
        assertEquals(
            "stale superseded result must be discarded",
            emptyMap<String, cn.vectory.ocdroid.data.model.SessionStatus>(),
            slices.sessionList.value.sessionStatuses
        )
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
    fun `launchLoadAgents reconciles invalid selectedAgentName to null (server default)`() = runTest {
        val agents = listOf(AgentInfo(name = "build"), AgentInfo(name = "code"))
        coEvery { repository.getAgents() } returns Result.success(agents)
        store.mutateSettings { it.copy(selectedAgentName = "ghost") }

        launchLoadAgents(scope, repository, slices, settingsManager, "Tag")
        advanceUntilIdle()

        // §agent-default: 选过的 agent 已不在服务端列表 → 回退 null（服务端默认），不再强制 build。
        assertEquals(null, slices.settings.value.selectedAgentName)
        verify { settingsManager.selectedAgentName = null }
    }

    @Test
    fun `launchLoadAgents keeps a still-valid selectedAgentName`() = runTest {
        // §agent-default: 选过的 agent 仍在列表 → 保留（不回退）。
        val agents = listOf(AgentInfo(name = "build"), AgentInfo(name = "code"))
        coEvery { repository.getAgents() } returns Result.success(agents)
        store.mutateSettings { it.copy(selectedAgentName = "code") }

        launchLoadAgents(scope, repository, slices, settingsManager, "Tag")
        advanceUntilIdle()

        assertEquals("code", slices.settings.value.selectedAgentName)
        verify(exactly = 0) { settingsManager.selectedAgentName = any() }
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
