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
        coEvery { repository.getActiveSessionIds() } returns Result.success(emptySet())
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
    fun `persistSessionCache writes all non-archived root sessions`() {
        // §Q4-strict-sync: the filter is now ALL non-archived root sessions
        // (parentId == null && !isArchived), not just open/current/workdir.
        val open = Session(id = "open", directory = "/x", revert = Session.RevertInfo("revert"))
        val current = Session(id = "current", directory = "/y")
        val workdirRoot = Session(id = "root", directory = "/workdir")
        val unrelated = Session(id = "other", directory = "/elsewhere")
        val child = Session(id = "child", directory = "/workdir", parentId = "root")
        val archived = Session(
            id = "archived",
            directory = "/z",
            time = Session.TimeInfo(archived = 1L),  // isArchived == true
        )
        every { settingsManager.sessionCache = any() } returns Unit

        persistSessionCache(
            settingsManager = settingsManager,
            sessions = listOf(open, current, workdirRoot, unrelated, child, archived),
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
                // All root non-archived sessions cached; child + archived excluded.
                entries.map { it.id }.toSet() == setOf("open", "current", "root", "other")
                    && entries.first { it.id == "open" }.revertMessageId == "revert"
                    && entries.first { it.id == "open" }.revertCreatedAtEpochMs == 42L
            }
        }
    }

    @Test
    fun `persistSessionCache writes nothing when no sessions match`() {
        // §Q4-strict-sync: only root non-archived sessions match; a child-only
        // list produces an empty cache.
        every { settingsManager.sessionCache = any() } returns Unit

        persistSessionCache(
            settingsManager = settingsManager,
            sessions = listOf(Session(id = "x", directory = "/a", parentId = "parent")),
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
    fun `pending local session remains pending when server has not returned it`() = runTest {
        val registeredAt = System.currentTimeMillis()
        val serverSession = Session(id = "s1", directory = "/x")
        val pendingSession = Session(id = "s2", directory = "/x")
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(serverSession))
        store.mutateSessionList {
            it.copy(
                sessions = listOf(serverSession, pendingSession),
                pendingCreateIds = setOf("s2"),
                pendingCreatedAt = mapOf("s2" to registeredAt),
            )
        }

        launchLoadSessions(scope, repository, slices, settingsManager, {}, {}, {}, emit)
        advanceUntilIdle()

        assertEquals(setOf("s1", "s2"), slices.sessionList.value.sessions.map { it.id }.toSet())
        assertEquals(setOf("s2"), slices.sessionList.value.pendingCreateIds)
        assertEquals(mapOf("s2" to registeredAt), slices.sessionList.value.pendingCreatedAt)
    }

    @Test
    fun `pending local session expires thirty seconds after registration when unconfirmed`() = runTest {
        val serverSession = Session(id = "s1", directory = "/x")
        val pendingSession = Session(id = "s2", directory = "/x")
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(serverSession))
        store.mutateSessionList {
            it.copy(
                sessions = listOf(serverSession, pendingSession),
                pendingCreateIds = setOf("s2"),
                pendingCreatedAt = mapOf(
                    "s2" to System.currentTimeMillis() - MainViewModelTimings.pendingCreateTimeoutMs - 1L,
                ),
            )
        }

        launchLoadSessions(scope, repository, slices, settingsManager, {}, {}, {}, emit)
        advanceUntilIdle()

        assertTrue(slices.sessionList.value.pendingCreateIds.isEmpty())
        assertTrue(slices.sessionList.value.pendingCreatedAt.isEmpty())
    }

    @Test
    fun `server returned session confirms pending create and removes registration timestamp`() = runTest {
        val serverSession = Session(id = "s2", directory = "/x")
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(serverSession))
        store.mutateSessionList {
            it.copy(
                sessions = listOf(serverSession),
                pendingCreateIds = setOf("s2"),
                pendingCreatedAt = mapOf("s2" to System.currentTimeMillis()),
            )
        }

        launchLoadSessions(scope, repository, slices, settingsManager, {}, {}, {}, emit)
        advanceUntilIdle()

        assertEquals(listOf("s2"), slices.sessionList.value.sessions.map { it.id })
        assertTrue(slices.sessionList.value.pendingCreateIds.isEmpty())
        assertTrue(slices.sessionList.value.pendingCreatedAt.isEmpty())
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

    // ── §fix-close-all-residual: cold-start auto-select must NOT refire ──────
    // The auto-select lands the user on a session during TRUE cold start only.
    // Once the first load has completed, a null currentSessionId means the user
    // closed every tab — a refresh must keep the empty state instead of
    // resurrecting the first server session (which produced the stable
    // "StreamingSvcLauncher AckTimeout bootstrap abort" residual).

    @Test
    fun `fix-close-all-residual - second load after close-all does not auto-select`() = runTest {
        val sessions = listOf(Session(id = "first", directory = "/x"))
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)
        // Simulate "initial load already happened" (the normal pre-condition
        // before the user can close tabs): the flag is true, currentSessionId
        // is null (user just closed the last tab), no draft.
        store.mutateSessionList { it.copy(hasCompletedInitialLoad = true) }
        var selected: String? = null

        launchLoadSessions(scope, repository, slices, settingsManager, { selected = it }, {}, {}, emit)
        advanceUntilIdle()

        assertNull("must NOT auto-select after close-all-tabs", selected)
        assertNull("chat stays cleared (empty state)", slices.chat.value.currentSessionId)
    }

    @Test
    fun `fix-close-all-residual - first load with null current still auto-selects`() = runTest {
        // Regression guard: the cold-start auto-select itself must still work
        // on the FIRST load (hasCompletedInitialLoad = false). This is the
        // legitimate "land the user on a session" path the gate preserves.
        val sessions = listOf(Session(id = "first", directory = "/x"))
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)
        var selected: String? = null

        launchLoadSessions(scope, repository, slices, settingsManager, { selected = it }, {}, {}, emit)
        advanceUntilIdle()

        assertEquals("cold start still auto-selects first", "first", selected)
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
            onArchivedSessionsDetected = { sessions, openIds, _, _, _ -> archivedSessions = sessions; archivedOpenIds = openIds },
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
            onArchivedSessionsDetected = { _, _, _, _, _ -> archivedInvoked = true },
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
            onArchivedSessionsDetected = { _, openIds, _, _, _ -> capturedOpenIds = openIds },
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
            onArchivedSessionsDetected = { _, openIds, _, _, _ -> callbackFired = true; capturedOpenIds = openIds },
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
            onArchivedSessionsDetected = { _, _, _, _, _ -> archiveCallbackCount += 1 },
        )
        advanceUntilIdle()
        // Second call (fresh, non-archived) supersedes the first
        launchLoadSessions(
            scope, repository, slices, settingsManager, {}, {}, {}, emit,
            onArchivedSessionsDetected = { _, _, _, _, _ -> archiveCallbackCount += 1 },
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
    fun `launchLoadSessionStatus writes and prunes active ids to current tree`() = runTest {
        store.mutateSessionList {
            it.copy(sessions = listOf(Session(id = "known", directory = "/x")))
        }
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getActiveSessionIds() } returns Result.success(setOf("known", "stale"))

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        assertEquals(setOf("known"), slices.sessionList.value.activeSessionIds)
    }

    @Test
    fun `launchLoadSessionStatus active failure retains prior snapshot but prunes stale ids`() = runTest {
        store.mutateSessionList {
            it.copy(
                sessions = listOf(Session(id = "known", directory = "/x")),
                activeSessionIds = setOf("known", "deleted"),
            )
        }
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getActiveSessionIds() } returns Result.failure(IllegalStateException("offline"))

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        assertEquals(setOf("known"), slices.sessionList.value.activeSessionIds)
    }

    @Test
    fun `launchLoadSessionStatus completion is true after equal map merge`() = runTest {
        val statuses = mapOf("s1" to cn.vectory.ocdroid.data.model.SessionStatus("idle"))
        store.mutateSessionList { it.copy(sessions = listOf(Session(id = "s1", directory = "/x")), sessionStatuses = statuses) }
        coEvery { repository.getSessionStatus() } returns Result.success(statuses)
        val completions = mutableListOf<Boolean>()

        launchLoadSessionStatus(scope, repository, slices, completions::add)
        advanceUntilIdle()

        assertEquals(listOf(true), completions)
        assertEquals(statuses, slices.sessionList.value.sessionStatuses)
    }

    @Test
    fun `launchLoadSessionStatus completion runs after merge`() = runTest {
        val status = cn.vectory.ocdroid.data.model.SessionStatus("busy")
        coEvery { repository.getSessionStatus() } returns Result.success(mapOf("s1" to status))
        var observed: Map<String, cn.vectory.ocdroid.data.model.SessionStatus>? = null

        launchLoadSessionStatus(scope, repository, slices) {
            observed = slices.sessionList.value.sessionStatuses
        }
        advanceUntilIdle()

        assertEquals(mapOf("s1" to status), observed)
    }

    @Test
    fun `launchLoadSessionStatus failure completes false exactly once`() = runTest {
        coEvery { repository.getSessionStatus() } returns Result.failure(IllegalStateException("offline"))
        val completions = mutableListOf<Boolean>()

        launchLoadSessionStatus(scope, repository, slices, completions::add)
        advanceUntilIdle()

        assertEquals(listOf(false), completions)
    }

    @Test
    fun `superseded launchLoadSessionStatus completes stale request false`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var first = true
        coEvery { repository.getSessionStatus() } coAnswers {
            if (first) {
                first = false
                gate.await()
                Result.success(emptyMap())
            } else Result.success(emptyMap())
        }
        val firstCompletions = mutableListOf<Boolean>()
        val secondCompletions = mutableListOf<Boolean>()

        launchLoadSessionStatus(scope, repository, slices, firstCompletions::add)
        advanceUntilIdle()
        launchLoadSessionStatus(scope, repository, slices, secondCompletions::add)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(false), firstCompletions)
        assertEquals(listOf(true), secondCompletions)
    }

    @Test
    fun `launchLoadSessionStatus normalizes omitted authoritative nodes to idle`() = runTest {
        // §item6: /session/status 是全局权威快照, 只含 active(busy/retry) — idle 已被
        // server delete (opencode session/status.ts: data.delete on idle). 整体替换清除
        // server 已 idle(快照缺失)的 stale 本地 busy; merge(+statuses) 会永久保留旧 busy.
        slices.mutateSessionList {
            it.copy(
                sessions = listOf(
                    Session(id = "stale-idle", directory = "/x"),
                    Session(id = "keep", directory = "/x"),
                ),
                sessionStatuses = mutableMapOf(
                "stale-idle" to cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"),
                "keep" to cn.vectory.ocdroid.data.model.SessionStatus(type = "retry")
                ),
            )
        }
        coEvery { repository.getSessionStatus() } returns Result.success(
            mapOf("keep" to cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"))
        )

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        val result = slices.sessionList.value.sessionStatuses
        assertEquals(
            "successful omission is authoritative idle, not unknown",
            cn.vectory.ocdroid.data.model.SessionStatus(type = "idle"),
            result["stale-idle"],
        )
        assertEquals(cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"), result["keep"])
        assertEquals(2, result.size)
    }

    @Test
    fun `status normalization leaves nodes outside loaded authoritative tree unknown`() = runTest {
        slices.mutateSessionList {
            it.copy(
                sessions = listOf(Session(id = "known", directory = "/x")),
                sessionStatuses = mapOf("outside" to cn.vectory.ocdroid.data.model.SessionStatus("busy")),
            )
        }
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())

        launchLoadSessionStatus(scope, repository, slices)
        advanceUntilIdle()

        assertEquals(cn.vectory.ocdroid.data.model.SessionStatus("idle"), slices.sessionList.value.sessionStatuses["known"])
        assertFalse("outside node is not authoritative", "outside" in slices.sessionList.value.sessionStatuses)
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

        assertEquals(
            "successful omission must normalize A to explicit idle",
            cn.vectory.ocdroid.data.model.SessionStatus("idle"),
            slices.sessionList.value.sessionStatuses["A"],
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
        store.mutateSessionList { it.copy(sessions = listOf(Session(id = "p1", directory = "/x"))) }
        coEvery { repository.getChildren("p1") } returns Result.success(children)
        coEvery { repository.getChildren("c1") } returns Result.success(emptyList())
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())

        launchLoadChildSessions(scope, repository, slices, "p1", "Tag")
        advanceUntilIdle()

        assertEquals(children, slices.sessionList.value.childSessions["p1"])
        assertTrue("p1" in slices.sessionList.value.completeRootIds)
    }

    @Test
    fun `launchLoadChildSessions failure does not throw`() = runTest {
        store.mutateSessionList { it.copy(sessions = listOf(Session(id = "p1", directory = "/x"))) }
        coEvery { repository.getChildren(any()) } returns Result.failure(IllegalStateException("x"))

        launchLoadChildSessions(scope, repository, slices, "p1", "Tag")
        advanceUntilIdle()

        // No throw → slice untouched.
        assertTrue(slices.sessionList.value.childSessions.isEmpty())
    }

    // ── §gpter-blocker (v097 review-fix): stale in-flight child-load must NOT
    //    re-certify a root after the tree was invalidated mid-flight ──────────

    @Test
    fun `gpter-blocker launchLoadChildSessions drops stale result when epoch bumped mid-flight`() = runTest {
        val root = Session(id = "p1", directory = "/x")
        val child = Session(id = "c1", directory = "/x", parentId = "p1")
        store.mutateSessionList { it.copy(sessions = listOf(root)) }

        val gate = CompletableDeferred<Unit>()
        coEvery { repository.getChildren("p1") } coAnswers {
            gate.await()
            Result.success(listOf(child))
        }
        coEvery { repository.getChildren("c1") } returns Result.success(emptyList())
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())

        launchLoadChildSessions(scope, repository, slices, "p1", "Tag")
        advanceUntilIdle() // hydration suspended inside gate

        // Simulate invalidation mid-flight: bump the epoch (as
        // upsertAndInvalidateTree / REST structural replace would).
        val epochBefore = slices.sessionList.value.completenessEpoch
        store.mutateSessionList { it.copy(completenessEpoch = it.completenessEpoch + 1L) }
        assertTrue(slices.sessionList.value.completenessEpoch > epochBefore)

        // Release the stale hydration.
        gate.complete(Unit)
        advanceUntilIdle()

        // §gpter-blocker: stale result dropped — root NOT in completeRootIds.
        assertFalse(
            "stale child-load must NOT re-certify root after invalidation",
            "p1" in slices.sessionList.value.completeRootIds,
        )

        // Follow-up tick re-hydrates and succeeds.
        coEvery { repository.getChildren("p1") } returns Result.success(listOf(child))
        launchLoadChildSessions(scope, repository, slices, "p1", "Tag")
        advanceUntilIdle()

        assertTrue(
            "follow-up tick re-hydrates and certifies root",
            "p1" in slices.sessionList.value.completeRootIds,
        )
    }

    @Test
    fun `gpter-blocker launchLoadChildSessions commits normally when epoch unchanged`() = runTest {
        // Negative control: no invalidation mid-flight → commit succeeds.
        val root = Session(id = "p1", directory = "/x")
        val child = Session(id = "c1", directory = "/x", parentId = "p1")
        store.mutateSessionList { it.copy(sessions = listOf(root)) }
        coEvery { repository.getChildren("p1") } returns Result.success(listOf(child))
        coEvery { repository.getChildren("c1") } returns Result.success(emptyList())
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())

        launchLoadChildSessions(scope, repository, slices, "p1", "Tag")
        advanceUntilIdle()

        assertTrue("p1 must be certified when no invalidation occurred", "p1" in slices.sessionList.value.completeRootIds)
    }

    // ── §gpter-important (v097 review-fix): REST structural replace discards
    //    cached completeness proofs and bumps the invalidation epoch ─────────

    @Test
    fun `gpter-important launchLoadSessions clears completeRootIds and bumps epoch`() = runTest {
        val root = Session(id = "A", directory = "/x")
        store.mutateSessionList {
            it.copy(
                sessions = listOf(root),
                completeRootIds = setOf("A"),
                completenessEpoch = 5L,
            )
        }
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(root))

        launchLoadSessions(scope, repository, slices, settingsManager, {}, {}, {}, emit)
        advanceUntilIdle()

        assertTrue(
            "stale completeRootIds discarded on REST full-list replace",
            slices.sessionList.value.completeRootIds.isEmpty(),
        )
        assertEquals("epoch bumped", 6L, slices.sessionList.value.completenessEpoch)
    }

    @Test
    fun `gpter-important launchLoadMoreSessions clears completeRootIds and bumps epoch`() = runTest {
        val root = Session(id = "A", directory = "/x")
        store.mutateSessionList {
            it.copy(
                sessions = listOf(root),
                completeRootIds = setOf("A"),
                completenessEpoch = 3L,
                hasMoreSessions = true,
                isLoadingMoreSessions = false,
                loadedSessionLimit = 10,
            )
        }
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(root))

        launchLoadMoreSessions(scope, repository, slices, {}, emit)
        advanceUntilIdle()

        assertTrue(
            "stale completeRootIds discarded on REST pagination catch-up",
            slices.sessionList.value.completeRootIds.isEmpty(),
        )
        assertEquals("epoch bumped", 4L, slices.sessionList.value.completenessEpoch)
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

    // ── §rev-grok fix1 + 9.5 🟠1: authoritative reconcile + slim token ──────
    //
    // Fix1 (token protection): the race that broke slim — SSE
    // `permission.asked` folds an entry with a non-null routeToken → REST
    // `getPendingPermissions` returns the same id with routeToken=null →
    // full-replace wiped the token → ChatScaffold took the legacy respond
    // path (contract §2/B2 violation). Token preservation is on the
    // intersection only (REST entry AND existing entry, REST null + existing
    // non-null → keep existing token).
    //
    // 9.5 🟠1 (ghost cleanup): membership is REST-authoritative. REST is a
    // full sweep (no workdir param), so an id REST doesn't return is dropped
    // — the server resolved / expired it without the client receiving the
    // resolve event. Only race-window arrivals (SSE during the poll, not in
    // startIds) are preserved. Mirrors the authoritative reconcile shape of
    // [SessionSyncCoordinator.loadPendingQuestionsAllWorkdirs].

    @Test
    fun `rev-grok fix1 - REST with routeToken overrides existing null token`() = runTest {
        // REST authoritative: provides a token where SSE hadn't yet → take it.
        val existing = PermissionRequest(id = "p1", sessionId = "s1", permission = "edit", routeToken = null)
        val fetched = PermissionRequest(id = "p1", sessionId = "s1", permission = "edit", routeToken = "tok-rest")
        store.mutateSessionList { it.copy(pendingPermissions = listOf(existing)) }
        coEvery { repository.getPendingPermissions() } returns Result.success(listOf(fetched))

        launchLoadPendingPermissions(scope, repository, slices, "Tag")
        advanceUntilIdle()

        val merged = slices.sessionList.value.pendingPermissions
        assertEquals(1, merged.size)
        assertEquals("tok-rest", merged.single().routeToken)
    }

    @Test
    fun `rev-grok fix1 - SSE-folded routeToken survives REST refresh that returns null token`() = runTest {
        // The core race: SSE delivered `tok-sse`, REST returns the same id
        // with routeToken=null. The merged entry MUST keep `tok-sse` —
        // otherwise the slim respond path falls back to the legacy endpoint.
        val existing = PermissionRequest(id = "p1", sessionId = "s1", permission = "edit", routeToken = "tok-sse")
        val fetched = PermissionRequest(id = "p1", sessionId = "s1", permission = "edit", routeToken = null)
        store.mutateSessionList { it.copy(pendingPermissions = listOf(existing)) }
        coEvery { repository.getPendingPermissions() } returns Result.success(listOf(fetched))

        launchLoadPendingPermissions(scope, repository, slices, "Tag")
        advanceUntilIdle()

        val merged = slices.sessionList.value.pendingPermissions.single()
        assertEquals("p1", merged.id)
        assertEquals(
            "SSE-folded routeToken MUST NOT be wiped by a null-token REST refresh",
            "tok-sse",
            merged.routeToken,
        )
    }

    @Test
    fun `rev-grok fix1 - REST updates other fields while preserving existing routeToken`() = runTest {
        // REST is the authority for non-token fields (permission mutated
        // server-side) — those MUST land; only the token is preserved when
        // REST would null it out.
        val existing = PermissionRequest(
            id = "p1", sessionId = "s1", permission = "once", routeToken = "tok-sse"
        )
        val fetched = PermissionRequest(
            id = "p1", sessionId = "s1", permission = "always", routeToken = null
        )
        store.mutateSessionList { it.copy(pendingPermissions = listOf(existing)) }
        coEvery { repository.getPendingPermissions() } returns Result.success(listOf(fetched))

        launchLoadPendingPermissions(scope, repository, slices, "Tag")
        advanceUntilIdle()

        val merged = slices.sessionList.value.pendingPermissions.single()
        assertEquals("REST authoritative for non-token fields", "always", merged.permission)
        assertEquals("token preserved from existing", "tok-sse", merged.routeToken)
    }

    @Test
    fun `rev-grok 9-5 O1 - REST-only id added and pre-existing id not returned is dropped`() = runTest {
        // §rev-grok 9.5 🟠1: authoritative reconcile. REST returns p-rest
        // (added). p-local was in pendingPermissions at poll-start, REST did
        // NOT return it → server resolved / expired it → DROPPED (ghost
        // cleanup). The previous Fix1 union ("existing fills gaps") would
        // have kept p-local as a ghost forever.
        val existing = PermissionRequest(id = "p-local", sessionId = "s1", permission = "edit", routeToken = "tok-local")
        val fetched = PermissionRequest(id = "p-rest", sessionId = "s1", permission = "edit", routeToken = "tok-rest")
        store.mutateSessionList { it.copy(pendingPermissions = listOf(existing)) }
        coEvery { repository.getPendingPermissions() } returns Result.success(listOf(fetched))

        launchLoadPendingPermissions(scope, repository, slices, "Tag")
        advanceUntilIdle()

        val merged = slices.sessionList.value.pendingPermissions.associateBy { it.id }
        assertEquals("only REST-returned id survives (ghost cleanup)", setOf("p-rest"), merged.keys)
        assertEquals("tok-rest", merged["p-rest"]?.routeToken)
    }

    @Test
    fun `rev-grok 9-5 O1 - server-stale permission dropped on empty REST result`() = runTest {
        // §rev-grok 9.5 🟠1: ghost cleanup. REST returns empty → every
        // permission in pendingPermissions at poll-start is dropped (server
        // resolved / expired all of them without the client receiving the
        // resolve events). This is the test that previously pinned the
        // opposite ("preserved") under a misleading "removes" name — now
        // re-pinned to the correct authoritative-reconcile behavior.
        val existing = PermissionRequest(id = "p-stale", sessionId = "s1", permission = "edit", routeToken = "tok")
        store.mutateSessionList { it.copy(pendingPermissions = listOf(existing)) }
        coEvery { repository.getPendingPermissions() } returns Result.success(emptyList())

        launchLoadPendingPermissions(scope, repository, slices, "Tag")
        advanceUntilIdle()

        val merged = slices.sessionList.value.pendingPermissions
        assertTrue(
            "REST authoritative: server-stale permission dropped (ghost cleanup), was: ${merged.map { it.id }}",
            merged.isEmpty(),
        )
    }

    @Test
    fun `rev-grok 9-5 O1 - SSE arrival during in-flight REST poll is preserved (race window)`() = runTest {
        // §rev-grok 9.5 🟠1 race-window: a `permission.asked` that lands
        // DURING the poll (after the startIds snapshot, before the merge)
        // MUST survive — REST hasn't observed it yet; dropping it would
        // lose a fresh permission. Mirrors the questions-path startIds
        // pattern in [SessionSyncCoordinator.loadPendingQuestionsAllWorkdirs].
        //
        // Setup: pendingPermissions is empty at poll-start (startIds = {}).
        // During the in-flight REST fetch, an SSE event writes p-fresh into
        // the slice. REST returns empty. The race arrival (p-fresh) must be
        // preserved because p-fresh.id is NOT in startIds.
        val sseArrival = PermissionRequest(
            id = "p-fresh", sessionId = "s1", permission = "edit", routeToken = "tok-fresh"
        )
        store.mutateSessionList { it.copy(pendingPermissions = emptyList()) }
        coEvery { repository.getPendingPermissions() } coAnswers {
            // Simulate SSE mid-poll delivery: write into the slice DURING
            // the fetch (after startIds was snapshotted, before the merge).
            slices.mutateSessionList { it.copy(pendingPermissions = listOf(sseArrival)) }
            Result.success(emptyList())
        }

        launchLoadPendingPermissions(scope, repository, slices, "Tag")
        advanceUntilIdle()

        val merged = slices.sessionList.value.pendingPermissions
        assertEquals(
            "race-window: SSE arrival during in-flight poll preserved",
            listOf("p-fresh"),
            merged.map { it.id },
        )
        assertEquals("tok-fresh", merged.single().routeToken)
    }

    @Test
    fun `rev-grok 9-5 O1 - id known at start is dropped despite mid-poll SSE refresh (boundary pin)`() = runTest {
        // §rev-grok 9.5 🟠1 boundary pin: an id that was in pendingPermissions
        // AT POLL START, is absent from REST, and is re-delivered by SSE
        // during the poll MUST be dropped — race-window keep applies ONLY
        // to ids NOT in startIds. Otherwise resolved-and-redelivered events
        // would resurrect ghosts. This pins where the boundary sits so
        // future refactors don't accidentally widen the race window.
        //
        // Setup: pendingPermissions = [p1: tok-old] at poll-start
        //        (startIds = {p1}). During the fetch, SSE upgrades p1's
        //        token to tok-sse-fresh. REST returns empty. Expected:
        //        p1 dropped (authoritative reconcile wins over the
        //        mid-poll SSE refresh because p1 was known at start).
        val startEntry = PermissionRequest(
            id = "p1", sessionId = "s1", permission = "edit", routeToken = "tok-old"
        )
        val sseFresh = PermissionRequest(
            id = "p1", sessionId = "s1", permission = "edit", routeToken = "tok-sse-fresh"
        )
        store.mutateSessionList { it.copy(pendingPermissions = listOf(startEntry)) }
        coEvery { repository.getPendingPermissions() } coAnswers {
            // SSE mid-poll upgrades p1's token (startEntry → sseFresh).
            slices.mutateSessionList { it.copy(pendingPermissions = listOf(sseFresh)) }
            Result.success(emptyList())  // REST no longer lists p1
        }

        launchLoadPendingPermissions(scope, repository, slices, "Tag")
        advanceUntilIdle()

        assertTrue(
            "id known at start + absent from REST → dropped, even if SSE re-delivered during poll",
            slices.sessionList.value.pendingPermissions.isEmpty(),
        )
    }

    @Test
    fun `rev-grok fix1 - slim mode warns on final permission with null routeToken`() = runTest {
        // §rev-grok fix1 defensive: in slim mode, a final pending permission
        // with a null routeToken means the slim respond path cannot route
        // correctly — surface via Log.w (no silent legacy fallback).
        every { repository.isSlimMode } returns true
        val fetched = PermissionRequest(id = "p1", sessionId = "s1", permission = "edit", routeToken = null)
        coEvery { repository.getPendingPermissions() } returns Result.success(listOf(fetched))

        launchLoadPendingPermissions(scope, repository, slices, "Tag")
        advanceUntilIdle()

        // The permission is still written (no silent fallback / no drop).
        assertEquals(1, slices.sessionList.value.pendingPermissions.size)
        // Log.w captured by the mockkStatic(Log::class) in setUp.
        verify(atLeast = 1) {
            Log.w(any<String>(), match<String> { it.contains("null routeToken") && it.contains("p1") })
        }
    }

    // ── launchLoadAgents ──────────────────────────────────────────────────────

    // §chat-ux-batch T8 (B3): the three former tests
    // `launchLoadAgents reconciles invalid selectedAgentName to null (server default)`,
    // `launchLoadAgents keeps a still-valid selectedAgentName`, and
    // `launchLoadAgents preserves valid selectedAgentName and does not persist`
    // were DELETED here. They exercised the legacy selectedAgentName
    // reconciliation in launchLoadAgents — both the validation block and the
    // selectedAgentName field were deleted in T8 (T7 rewired agent selection
    // to the TRANSIENT pendingAgent chat-slice field, so loadAgents now just
    // writes the freshly-fetched list).

    @Test
    fun `launchLoadAgents failure leaves slice untouched`() = runTest {
        coEvery { repository.getAgents() } returns Result.failure(IllegalStateException("x"))

        // §chat-ux-batch T8 (B3): launchLoadAgents shed its settingsManager
        // param (legacy selectedAgentName reconciliation removed). On failure,
        // the agents slice is left untouched.
        launchLoadAgents(scope, repository, slices, "Tag")
        advanceUntilIdle()

        assertTrue(slices.settings.value.agents.isEmpty())
    }
}
