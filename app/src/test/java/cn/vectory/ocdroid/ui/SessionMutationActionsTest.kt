package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.QuestionInfo
import cn.vectory.ocdroid.data.model.QuestionOption
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §R18 Phase 5+: direct unit tests for [launchCreateSession] /
 * [launchForkSession] / [launchSetSessionArchived] / [launchDeleteSession] /
 * [launchSendMessage].
 *
 * High-yield mutation paths (~250 lines): session lifecycle (create / fork /
 * archive subtree / delete + reselect), and the send-message success/failure
 * callback chain that drives ChatViewModel.sendMessage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionMutationActionsTest {

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
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        store = SharedStateStore()
        slices = store.slices
        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        // §chat-ux-batch T8 (B3): mock setup for getAgentForSession /
        // getModelForSession removed (deleted APIs).
        every { settingsManager.openSessionIds } returns emptyList()
        scope = TestScope(UnconfinedTestDispatcher())
        emitted = mutableListOf()
        emit = EventEmitter { event -> emitted.add(event) }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── launchCreateSession ───────────────────────────────────────────────────

    @Test
    fun `launchCreateSession success upserts and selects new session`() = runTest {
        val created = Session(id = "new1", directory = "/x")
        coEvery { repository.createSession(any(), any()) } returns Result.success(created)
        var selected: String? = null

        launchCreateSession(scope, repository, slices, title = null, onSelectSession = { selected = it }, emit = emit)
        advanceUntilIdle()

        assertTrue(slices.sessionList.value.sessions.any { it.id == "new1" })
        assertEquals(setOf("new1"), slices.sessionList.value.pendingCreateIds)
        assertNotNull(slices.sessionList.value.pendingCreatedAt["new1"])
        assertEquals("new1", selected)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `launchCreateSession passes title through to repository`() = runTest {
        coEvery { repository.createSession(any(), any()) } returns Result.success(Session(id = "x", directory = "/y"))

        launchCreateSession(scope, repository, slices, title = "My Title", onSelectSession = {}, emit = emit)
        advanceUntilIdle()

        coVerify { repository.createSession("My Title", any()) }
    }

    @Test
    fun `launchCreateSession failure emits UiEvent Error`() = runTest {
        coEvery { repository.createSession(any(), any()) } returns Result.failure(IllegalStateException("nope"))

        launchCreateSession(scope, repository, slices, title = null, onSelectSession = {}, emit = emit)
        advanceUntilIdle()

        val err = emitted.filterIsInstance<UiEvent.Error>().single()
        assertEquals(R.string.error_create_session_failed, err.resId)
    }

    // ── launchForkSession ─────────────────────────────────────────────────────

    @Test
    fun `launchForkSession success upserts and selects fork`() = runTest {
        val forked = Session(id = "fork1", directory = "/x", parentId = "parent1")
        coEvery { repository.forkSession(any(), any()) } returns Result.success(forked)
        var selected: String? = null

        launchForkSession(scope, repository, slices, sessionId = "parent1", messageId = "m1", onSelectSession = { selected = it }, emit = emit)
        advanceUntilIdle()

        assertTrue(slices.sessionList.value.sessions.any { it.id == "fork1" })
        assertEquals("fork1", selected)
        coVerify { repository.forkSession("parent1", "m1") }
    }

    @Test
    fun `launchForkSession passes null messageId through`() = runTest {
        coEvery { repository.forkSession(any(), any()) } returns Result.success(Session(id = "x", directory = "/y"))

        launchForkSession(scope, repository, slices, sessionId = "p1", messageId = null, onSelectSession = {}, emit = emit)
        advanceUntilIdle()

        coVerify { repository.forkSession("p1", null) }
    }

    @Test
    fun `launchForkSession failure emits UiEvent Error`() = runTest {
        coEvery { repository.forkSession(any(), any()) } returns Result.failure(IllegalStateException("x"))

        launchForkSession(scope, repository, slices, sessionId = "p1", messageId = null, onSelectSession = {}, emit = emit)
        advanceUntilIdle()

        assertEquals(R.string.error_fork_session_failed, emitted.filterIsInstance<UiEvent.Error>().single().resId)
    }

    // ── launchSetSessionArchived ──────────────────────────────────────────────

    @Test
    fun `launchSetSessionArchived archives a single session and evicts from openIds`() = runTest {
        val session = Session(id = "s1", directory = "/x")
        store.mutateSessionList {
            it.copy(sessions = listOf(session), openSessionIds = listOf("s1", "s2"))
        }
        every { settingsManager.openSessionIds } returns listOf("s1", "s2")
        val archived = session.copy(time = Session.TimeInfo(archived = 1000L))
        coEvery { repository.updateSessionArchived("s1", any()) } returns Result.success(archived)

        launchSetSessionArchived(scope, repository, slices, settingsManager, sessionId = "s1", archived = true, emit = emit)
        advanceUntilIdle()

        assertTrue(slices.sessionList.value.sessions.single().isArchived)
        // s1 evicted from openSessionIds.
        assertEquals(listOf("s2"), slices.sessionList.value.openSessionIds)
        verify { settingsManager.openSessionIds = listOf("s2") }
    }

    @Test
    fun `launchSetSessionArchived clears currentSessionId when archiving the current session`() = runTest {
        val session = Session(id = "s1", directory = "/x")
        store.mutateSessionList { it.copy(sessions = listOf(session)) }
        store.mutateChat {
            it.copy(currentSessionId = "s1", messages = listOf(Message(id = "m1", role = "user")))
        }
        coEvery { repository.updateSessionArchived("s1", any()) } returns Result.success(session.copy(time = Session.TimeInfo(archived = 1000L)))

        launchSetSessionArchived(scope, repository, slices, settingsManager, sessionId = "s1", archived = true, emit = emit)
        advanceUntilIdle()

        assertNull(slices.chat.value.currentSessionId)
        assertTrue(slices.chat.value.messages.isEmpty())
    }

    @Test
    fun `launchSetSessionArchived restores an archived session`() = runTest {
        val session = Session(id = "s1", directory = "/x", time = Session.TimeInfo(archived = 1000L))
        store.mutateSessionList { it.copy(sessions = listOf(session)) }
        coEvery { repository.updateSessionArchived("s1", any()) } returns Result.success(session.copy(time = Session.TimeInfo(archived = null)))

        launchSetSessionArchived(scope, repository, slices, settingsManager, sessionId = "s1", archived = false, emit = emit)
        advanceUntilIdle()

        assertFalse(slices.sessionList.value.sessions.single().isArchived)
        // openSessionIds setter is NOT invoked when restoring.
        verify(exactly = 0) { settingsManager.openSessionIds = any() }
    }

    @Test
    fun `launchSetSessionArchived failure emits restore error and stops`() = runTest {
        val session = Session(id = "s1", directory = "/x")
        store.mutateSessionList { it.copy(sessions = listOf(session)) }
        coEvery { repository.updateSessionArchived(any(), any()) } returns Result.failure(IllegalStateException("denied"))

        launchSetSessionArchived(scope, repository, slices, settingsManager, sessionId = "s1", archived = false, emit = emit)
        advanceUntilIdle()

        assertEquals(R.string.error_restore_session_failed, emitted.filterIsInstance<UiEvent.Error>().single().resId)
        // Session not updated.
        assertFalse(slices.sessionList.value.sessions.single().isArchived)
    }

    @Test
    fun `launchSetSessionArchived archives whole subtree`() = runTest {
        // §task5-lifecycle: archive walks the whole subtree; iteration order
        // is not significant (each id is an independent REST call).
        val parent = Session(id = "p", directory = "/x")
        val child = Session(id = "c", directory = "/x", parentId = "p")
        store.mutateSessionList { it.copy(sessions = listOf(parent, child)) }
        coEvery { repository.updateSessionArchived("p", any()) } returns Result.success(parent.copy(time = Session.TimeInfo(archived = 1L)))
        coEvery { repository.updateSessionArchived("c", any()) } returns Result.success(child.copy(time = Session.TimeInfo(archived = 1L)))

        launchSetSessionArchived(scope, repository, slices, settingsManager, sessionId = "p", archived = true, emit = emit)
        advanceUntilIdle()

        assertTrue(slices.sessionList.value.sessions.all { it.isArchived })
        coVerify {
            repository.updateSessionArchived("c", any())
            repository.updateSessionArchived("p", any())
        }
    }

    // ── §Wave5b-Q13 blocker-2: launchSetSessionArchived cleans scroll state ──

    @Test
    fun `Wave5b-Q13 blocker-2 - launchSetSessionArchived wipes pendingScrollRequest targeting a non-current archived id`() = runTest {
        // §Wave5b-Q13 blocker-2: archiving a NON-current session MUST still
        // wipe a stale pendingScrollRequest that targets it. Chat CONTENT
        // (currentSessionId / messages) MUST be preserved (current not
        // archived). Pre-fix: only the clearCurrent branch (line 206) wiped
        // scroll state, so non-current archived ids leaked.
        val session = Session(id = "stale-target", directory = "/x")
        val currentSession = Session(id = "cur", directory = "/x")
        store.mutateSessionList {
            it.copy(sessions = listOf(session, currentSession), openSessionIds = listOf("cur", "stale-target"))
        }
        every { settingsManager.openSessionIds } returns listOf("cur", "stale-target")
        store.mutateChat {
            it.copy(
                currentSessionId = "cur",  // NOT the archived id
                messages = listOf(Message(id = "m1", role = "user")),
                pendingScrollRequest = cn.vectory.ocdroid.ui.PendingScrollRequest(
                    requestId = 7L,
                    targetSessionId = "stale-target",
                    behavior = cn.vectory.ocdroid.ui.ScrollBehavior.Latest,
                ),
            )
        }
        val archived = session.copy(time = Session.TimeInfo(archived = 1000L))
        coEvery { repository.updateSessionArchived("stale-target", any()) } returns Result.success(archived)

        launchSetSessionArchived(scope, repository, slices, settingsManager, sessionId = "stale-target", archived = true, emit = emit)
        advanceUntilIdle()

        // Chat content preserved.
        assertEquals("cur", slices.chat.value.currentSessionId)
        assertEquals(1, slices.chat.value.messages.size)
        // Stale scroll intent wiped.
        assertNull(
            "non-current archived target's pendingScrollRequest MUST be wiped",
            slices.chat.value.pendingScrollRequest,
        )
    }

    @Test
    fun `Wave5b-Q13 blocker-2 - launchSetSessionArchived wipes parentReturnCheckpoints entries keyed by the archived subtree`() = runTest {
        // Archiving a subtree (parent + child) MUST wipe any checkpoint
        // entries keyed by the archived ids. The current session's own
        // checkpoint entry MUST survive.
        val parent = Session(id = "stale-parent", directory = "/x")
        val child = Session(id = "stale-child", directory = "/x", parentId = "stale-parent")
        val currentSession = Session(id = "cur", directory = "/x")
        store.mutateSessionList {
            it.copy(sessions = listOf(parent, child, currentSession), openSessionIds = listOf("cur", "stale-parent"))
        }
        every { settingsManager.openSessionIds } returns listOf("cur", "stale-parent")
        store.mutateChat {
            it.copy(
                currentSessionId = "cur",
                parentReturnCheckpoints = mapOf(
                    "stale-child" to cn.vectory.ocdroid.ui.ScrollCheckpoint(anchorKey = "k1", fallbackIndex = 1, offset = 1),
                    "cur" to cn.vectory.ocdroid.ui.ScrollCheckpoint(anchorKey = "k2", fallbackIndex = 2, offset = 2),
                ),
            )
        }
        coEvery { repository.updateSessionArchived("stale-parent", any()) } returns Result.success(parent.copy(time = Session.TimeInfo(archived = 1L)))
        coEvery { repository.updateSessionArchived("stale-child", any()) } returns Result.success(child.copy(time = Session.TimeInfo(archived = 1L)))

        launchSetSessionArchived(scope, repository, slices, settingsManager, sessionId = "stale-parent", archived = true, emit = emit)
        advanceUntilIdle()

        // Subtree entries wiped; live entry preserved.
        assertFalse(
            "stale-child entry MUST be wiped",
            slices.chat.value.parentReturnCheckpoints.containsKey("stale-child"),
        )
        assertEquals(
            "cur entry MUST survive",
            cn.vectory.ocdroid.ui.ScrollCheckpoint(anchorKey = "k2", fallbackIndex = 2, offset = 2),
            slices.chat.value.parentReturnCheckpoints["cur"],
        )
    }

    @Test
    fun `Wave5b-Q13 blocker-2 - launchSetSessionArchived current-archive case still wipes scroll state (no regression)`() = runTest {
        // Regression guard: the current-archive case (clearCurrent branch)
        // still wipes scroll state. The new cleanScrollStateForSubtree call
        // runs FIRST (unconditional), then the clearCurrent branch wipes
        // currentSessionId/messages. Both must fire; the result is the same
        // as pre-fix for this case.
        val session = Session(id = "cur", directory = "/x")
        store.mutateSessionList { it.copy(sessions = listOf(session)) }
        store.mutateChat {
            it.copy(
                currentSessionId = "cur",
                messages = listOf(Message(id = "m1", role = "user")),
                pendingScrollRequest = cn.vectory.ocdroid.ui.PendingScrollRequest(
                    requestId = 1L,
                    targetSessionId = "cur",
                    behavior = cn.vectory.ocdroid.ui.ScrollBehavior.Latest,
                ),
                parentReturnCheckpoints = mapOf("cur" to cn.vectory.ocdroid.ui.ScrollCheckpoint(null, 0, 0)),
            )
        }
        coEvery { repository.updateSessionArchived("cur", any()) } returns Result.success(session.copy(time = Session.TimeInfo(archived = 1000L)))

        launchSetSessionArchived(scope, repository, slices, settingsManager, sessionId = "cur", archived = true, emit = emit)
        advanceUntilIdle()

        assertNull(slices.chat.value.currentSessionId)
        assertTrue(slices.chat.value.messages.isEmpty())
        assertNull(slices.chat.value.pendingScrollRequest)
        assertTrue(slices.chat.value.parentReturnCheckpoints.isEmpty())
    }

    @Test
    fun `launchSetSessionArchived archive failure emits archive error`() = runTest {
        val session = Session(id = "s1", directory = "/x")
        store.mutateSessionList { it.copy(sessions = listOf(session)) }
        coEvery { repository.updateSessionArchived(any(), any()) } returns Result.failure(IllegalStateException("nope"))

        launchSetSessionArchived(scope, repository, slices, settingsManager, sessionId = "s1", archived = true, emit = emit)
        advanceUntilIdle()

        assertEquals(R.string.error_archive_session_failed, emitted.filterIsInstance<UiEvent.Error>().single().resId)
    }

    // ── §task5-lifecycle: archive / delete clear unread + pendingQuestions ──

    @Test
    fun `launchSetSessionArchived archive clears subtree unread and pendingQuestions`() = runTest {
        // §task5-lifecycle: root A has unread; child C carries a pending
        // question. Archiving A walks the subtree {A, C}; after the loop both
        // the unread badge for A and the question for C must be gone.
        val parent = Session(id = "A", directory = "/x")
        val child = Session(id = "C", directory = "/x", parentId = "A")
        store.mutateSessionList { it.copy(sessions = listOf(parent, child)) }
        store.mutateUnread { it.copy(unreadSessions = setOf("A")) }
        store.mutateSessionList {
            it.copy(
                pendingQuestions = listOf(
                    QuestionRequest(
                        id = "q1", sessionId = "C",
                        questions = listOf(QuestionInfo("q?", "h", listOf(QuestionOption("a", "b")))),
                    ),
                ),
            )
        }
        every { settingsManager.openSessionIds } returns emptyList()
        coEvery { repository.updateSessionArchived("A", any()) } returns Result.success(parent.copy(time = Session.TimeInfo(archived = 1L)))
        coEvery { repository.updateSessionArchived("C", any()) } returns Result.success(child.copy(time = Session.TimeInfo(archived = 1L)))

        launchSetSessionArchived(scope, repository, slices, settingsManager, sessionId = "A", archived = true, emit = emit)
        advanceUntilIdle()

        assertFalse("archived root A removed from unread", slices.unread.value.unreadSessions.contains("A"))
        assertTrue(
            "child C pending question removed",
            slices.sessionList.value.pendingQuestions.none { it.sessionId == "C" },
        )
    }

    @Test
    fun `archive parent success clears child active unread and question even if child REST fails`() = runTest {
        val parent = Session(id = "A", directory = "/x")
        val child = Session(id = "C", directory = "/x", parentId = "A")
        store.mutateSessionList {
            it.copy(
                sessions = listOf(parent, child),
                activeSessionIds = setOf("A", "C", "Z"),
                pendingQuestions = listOf(
                    QuestionRequest(
                        id = "q-child",
                        sessionId = "C",
                        questions = listOf(QuestionInfo("q?", "h", listOf(QuestionOption("a", "b")))),
                    ),
                    QuestionRequest(
                        id = "q-other",
                        sessionId = "Z",
                        questions = listOf(QuestionInfo("q?", "h", listOf(QuestionOption("a", "b")))),
                    ),
                ),
            )
        }
        store.mutateUnread { it.copy(unreadSessions = setOf("A", "C", "Z")) }
        every { settingsManager.openSessionIds } returns emptyList()
        coEvery { repository.updateSessionArchived("A", any()) } returns
            Result.success(parent.copy(time = Session.TimeInfo(archived = 1L)))
        coEvery { repository.updateSessionArchived("C", any()) } returns
            Result.failure(IllegalStateException("child archive failed"))

        launchSetSessionArchived(
            scope,
            repository,
            slices,
            settingsManager,
            sessionId = "A",
            archived = true,
            emit = emit,
        )
        advanceUntilIdle()

        assertEquals(setOf("Z"), slices.sessionList.value.activeSessionIds)
        assertEquals(setOf("Z"), slices.unread.value.unreadSessions)
        assertEquals(listOf("Z"), slices.sessionList.value.pendingQuestions.map { it.sessionId })
    }

    @Test
    fun `launchSetSessionArchived restore leaves unread and pendingQuestions untouched`() = runTest {
        // §task5-lifecycle: restore (archived=false) MUST NOT clear unread or
        // questions — the user wants to see the session again. Guards the
        // isArchive branch inside onSuccess.
        val session = Session(id = "s1", directory = "/x", time = Session.TimeInfo(archived = 1L))
        store.mutateSessionList { it.copy(sessions = listOf(session)) }
        store.mutateUnread { it.copy(unreadSessions = setOf("s1")) }
        store.mutateSessionList {
            it.copy(
                pendingQuestions = listOf(
                    QuestionRequest(
                        id = "q1", sessionId = "s1",
                        questions = listOf(QuestionInfo("q?", "h", listOf(QuestionOption("a", "b")))),
                    ),
                ),
            )
        }
        coEvery { repository.updateSessionArchived("s1", any()) } returns Result.success(session.copy(time = Session.TimeInfo(archived = null)))

        launchSetSessionArchived(scope, repository, slices, settingsManager, sessionId = "s1", archived = false, emit = emit)
        advanceUntilIdle()

        // Unread + question survive the restore.
        assertTrue("unread preserved on restore", slices.unread.value.unreadSessions.contains("s1"))
        assertTrue(
            "pending question preserved on restore",
            slices.sessionList.value.pendingQuestions.any { it.sessionId == "s1" },
        )
    }

    @Test
    fun `launchDeleteSession clears subtree unread and pendingQuestions`() = runTest {
        // §task5-lifecycle §delete-subtree: deleting a root MUST purge the
        // whole subtree's unread + pending questions. The mock deleteSession
        // simulates a server cascade-delete (shrinks the slice's session
        // metadata for both root and descendant) — the snapshot taken BEFORE
        // the REST call must still cover the whole subtree.
        val parent = Session(id = "A", directory = "/x")
        val child = Session(id = "C", directory = "/x", parentId = "A")
        store.mutateSessionList { it.copy(sessions = listOf(parent, child)) }
        store.mutateUnread { it.copy(unreadSessions = setOf("A", "C")) }
        store.mutateSessionList {
            it.copy(
                pendingQuestions = listOf(
                    QuestionRequest(
                        id = "q1", sessionId = "C",
                        questions = listOf(QuestionInfo("q?", "h", listOf(QuestionOption("a", "b")))),
                    ),
                    // Unrelated session's question must survive.
                    QuestionRequest(
                        id = "q2", sessionId = "Z",
                        questions = listOf(QuestionInfo("q?", "h", listOf(QuestionOption("a", "b")))),
                    ),
                ),
            )
        }
        // Mock simulates server cascade: deleteSession shrinks the slice's
        // session metadata so a naive post-delete read would miss the child.
        coEvery { repository.deleteSession(any()) } answers {
            val id = firstArg<String>()
            store.mutateSessionList { sl ->
                sl.copy(sessions = sl.sessions.filter { it.id != id && it.id != "C" })
            }
            Result.success(Unit)
        }

        launchDeleteSession(scope, repository, slices, settingsManager, sessionId = "A", onSelectSession = {}, emit = emit)
        advanceUntilIdle()

        assertFalse("deleted root A removed from unread", slices.unread.value.unreadSessions.contains("A"))
        assertFalse("deleted child C removed from unread", slices.unread.value.unreadSessions.contains("C"))
        assertTrue(
            "child C pending question removed",
            slices.sessionList.value.pendingQuestions.none { it.sessionId == "C" },
        )
        assertTrue(
            "unrelated session Z question preserved",
            slices.sessionList.value.pendingQuestions.any { it.sessionId == "Z" },
        )
        // Subtree also purged from sessions / directorySessions (whole subtree).
        assertTrue(
            "subtree purged from sessions",
            slices.sessionList.value.sessions.none { it.id == "A" || it.id == "C" },
        )
    }

    @Test
    fun `launchSetSessionArchived archive clears subtree when descendant lives only in childSessions`() = runTest {
        // §task5-lifecycle: descendant C lives ONLY in childSessions (not in
        // the global sessions list). The three-source subtree must still
        // visit C so its unread + pendingQuestion are cleared on archive.
        val parent = Session(id = "A", directory = "/x")
        val child = Session(id = "C", directory = "/x", parentId = "A")
        store.mutateSessionList {
            it.copy(
                sessions = listOf(parent),
                childSessions = mapOf("A" to listOf(child)),
            )
        }
        store.mutateUnread { it.copy(unreadSessions = setOf("A", "C")) }
        store.mutateSessionList {
            it.copy(
                pendingQuestions = listOf(
                    QuestionRequest(
                        id = "q1", sessionId = "C",
                        questions = listOf(QuestionInfo("q?", "h", listOf(QuestionOption("a", "b")))),
                    ),
                ),
            )
        }
        every { settingsManager.openSessionIds } returns emptyList()
        coEvery { repository.updateSessionArchived("A", any()) } returns Result.success(parent.copy(time = Session.TimeInfo(archived = 1L)))
        coEvery { repository.updateSessionArchived("C", any()) } returns Result.success(child.copy(time = Session.TimeInfo(archived = 1L)))

        launchSetSessionArchived(scope, repository, slices, settingsManager, sessionId = "A", archived = true, emit = emit)
        advanceUntilIdle()

        assertFalse("root A removed from unread", slices.unread.value.unreadSessions.contains("A"))
        assertFalse(
            "childSessions-only descendant C removed from unread (three-source coverage)",
            slices.unread.value.unreadSessions.contains("C"),
        )
        assertTrue(
            "childSessions-only descendant C pending question removed",
            slices.sessionList.value.pendingQuestions.none { it.sessionId == "C" },
        )
        // Both archive REST calls fired (subtree covered C even though it is
        // absent from the global sessions list).
        coVerify {
            repository.updateSessionArchived("A", any())
            repository.updateSessionArchived("C", any())
        }
    }

    @Test
    fun `launchSetSessionArchived archive replaces archived session in childSessions with archived copy`() = runTest {
        // §task5-ghost-r2 (final-fix round 2): pre-fix onSuccess only updated
        // `sessions` + `directorySessions`, leaving a stale unarchived copy in
        // `childSessions`. The next reconcile's allSessionsById snapshot would
        // then see the descendant as unarchived, defeating
        // filterArchivedSessionQuestions and letting its question ghost back.
        // The fix syncs childSessions the same way as directorySessions.
        val child = Session(id = "C", directory = "/x")
        val archivedChild = child.copy(time = Session.TimeInfo(archived = 1L))
        store.mutateSessionList {
            it.copy(
                // C lives ONLY in childSessions — not in the global sessions
                // list and not in directorySessions.
                sessions = emptyList(),
                childSessions = mapOf("parent-key" to listOf(child)),
            )
        }
        every { settingsManager.openSessionIds } returns emptyList()
        coEvery { repository.updateSessionArchived("C", any()) } returns Result.success(archivedChild)

        launchSetSessionArchived(scope, repository, slices, settingsManager, sessionId = "C", archived = true, emit = emit)
        advanceUntilIdle()

        val childEntry = slices.sessionList.value.childSessions["parent-key"]!!.single()
        assertEquals("C", childEntry.id)
        assertTrue(
            "childSessions entry replaced with the archived copy (time.archived > 0)",
            childEntry.isArchived,
        )
    }

    @Test
    fun `launchDeleteSession clears currentSessionId when a subtree descendant is current`() = runTest {
        // §task5-lifecycle §delete-subtree: the current-session guard expands
        // from `== sessionId` to `in removedIds` so deleting a parent whose
        // child is currently open also clears chat (defensive against the
        // server cascade-deleting the open child).
        val parent = Session(id = "A", directory = "/x")
        val child = Session(id = "C", directory = "/x", parentId = "A")
        store.mutateSessionList { it.copy(sessions = listOf(parent, child)) }
        store.mutateChat {
            it.copy(currentSessionId = "C", messages = listOf(Message(id = "m1", role = "user")))
        }
        coEvery { repository.deleteSession(any()) } returns Result.success(Unit)

        launchDeleteSession(scope, repository, slices, settingsManager, sessionId = "A", onSelectSession = {}, emit = emit)
        advanceUntilIdle()

        // Child was current; after deleting parent the chat MUST NOT still
        // point at C (no remaining session → currentSessionId cleared).
        assertNull(
            "currentSessionId cleared when cascade-deleted child was current",
            slices.chat.value.currentSessionId,
        )
        assertTrue(
            "deleted subtree purged from sessions",
            slices.sessionList.value.sessions.none { it.id == "A" || it.id == "C" },
        )
    }

    // ── launchDeleteSession ───────────────────────────────────────────────────

    @Test
    fun `launchDeleteSession success purges from sessions and directorySessions`() = runTest {
        val session = Session(id = "s1", directory = "/x")
        store.mutateSessionList {
            it.copy(
                sessions = listOf(session),
                directorySessions = mapOf("/x" to listOf(session)),
            )
        }
        coEvery { repository.deleteSession(any()) } returns Result.success(Unit)

        launchDeleteSession(scope, repository, slices, settingsManager, sessionId = "s1", onSelectSession = {}, emit = emit)
        advanceUntilIdle()

        assertTrue(slices.sessionList.value.sessions.isEmpty())
        assertTrue(slices.sessionList.value.directorySessions.isEmpty())
    }

    @Test
    fun `launchDeleteSession selects fallback when current session is deleted`() = runTest {
        val s1 = Session(id = "s1", directory = "/x")
        val s2 = Session(id = "s2", directory = "/x")
        store.mutateSessionList { it.copy(sessions = listOf(s1, s2)) }
        store.mutateChat { it.copy(currentSessionId = "s1") }
        coEvery { repository.deleteSession(any()) } returns Result.success(Unit)
        var selected: String? = null

        launchDeleteSession(scope, repository, slices, settingsManager, sessionId = "s1", onSelectSession = { selected = it }, emit = emit)
        advanceUntilIdle()

        // Remaining first session selected.
        assertEquals("s2", selected)
    }

    @Test
    fun `launchDeleteSession clears currentSessionId when no remaining sessions`() = runTest {
        val s1 = Session(id = "s1", directory = "/x")
        store.mutateSessionList { it.copy(sessions = listOf(s1)) }
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(Message(id = "m", role = "user"))) }
        coEvery { repository.deleteSession(any()) } returns Result.success(Unit)

        launchDeleteSession(scope, repository, slices, settingsManager, sessionId = "s1", onSelectSession = {}, emit = emit)
        advanceUntilIdle()

        assertNull(slices.chat.value.currentSessionId)
        assertTrue(slices.chat.value.messages.isEmpty())
    }

    @Test
    fun `launchDeleteSession failure emits UiEvent Error`() = runTest {
        coEvery { repository.deleteSession(any()) } returns Result.failure(IllegalStateException("denied"))

        launchDeleteSession(scope, repository, slices, settingsManager, sessionId = "s1", onSelectSession = {}, emit = emit)
        advanceUntilIdle()

        assertEquals(R.string.error_delete_session_failed, emitted.filterIsInstance<UiEvent.Error>().single().resId)
    }

    // ── R-20 Phase 1 (C3): EvictSession emission on archive / delete ──────────

    @Test
    fun `C3 launchSetSessionArchived emits EvictSession per subtree id on archive`() = runTest {
        // archive=true → parentFirst=false → children first, then parent.
        // Subtree = {c1, c2, p} (3 ids) → 3 EvictSession emissions, one per id.
        val parent = Session(id = "p", directory = "/x")
        val c1 = Session(id = "c1", directory = "/x", parentId = "p")
        val c2 = Session(id = "c2", directory = "/x", parentId = "p")
        store.mutateSessionList { it.copy(sessions = listOf(parent, c1, c2)) }
        coEvery { repository.updateSessionArchived("c1", any()) } returns Result.success(c1.copy(time = Session.TimeInfo(archived = 1L)))
        coEvery { repository.updateSessionArchived("c2", any()) } returns Result.success(c2.copy(time = Session.TimeInfo(archived = 1L)))
        coEvery { repository.updateSessionArchived("p", any()) } returns Result.success(parent.copy(time = Session.TimeInfo(archived = 1L)))
        val emittedEffects = mutableListOf<cn.vectory.ocdroid.ui.controller.ControllerEffect>()

        launchSetSessionArchived(
            scope, repository, slices, settingsManager,
            sessionId = "p", archived = true,
            emit = emit,
            currentServerGroupFp = { "g-test" },
            emitEffect = { emittedEffects.add(it) },
        )
        advanceUntilIdle()

        val evictions = emittedEffects.filterIsInstance<cn.vectory.ocdroid.ui.controller.ControllerEffect.EvictSession>()
        assertEquals(
            "archive subtree of 3 ids must emit 3 EvictSession effects (one per archived id)",
            3,
            evictions.size,
        )
        assertEquals(
            "evicted ids must cover the full subtree",
            setOf("p", "c1", "c2"),
            evictions.map { it.sessionId }.toSet(),
        )
        assertEquals(
            "every EvictSession must carry the currentServerGroupFp",
            setOf("g-test"),
            evictions.map { it.serverGroupFp }.toSet(),
        )
    }

    @Test
    fun `C3 launchSetSessionArchived does NOT emit EvictSession on restore`() = runTest {
        // archived=false (restore): the user wants to see the session again;
        // evicting its cache would defeat the purpose. EvictSession MUST NOT
        // fire on the restore path (gated on isArchive inside onSuccess).
        val session = Session(id = "s1", directory = "/x", time = Session.TimeInfo(archived = 1L))
        store.mutateSessionList { it.copy(sessions = listOf(session)) }
        coEvery { repository.updateSessionArchived("s1", any()) } returns Result.success(session.copy(time = Session.TimeInfo(archived = null)))
        val emittedEffects = mutableListOf<cn.vectory.ocdroid.ui.controller.ControllerEffect>()

        launchSetSessionArchived(
            scope, repository, slices, settingsManager,
            sessionId = "s1", archived = false,
            emit = emit,
            currentServerGroupFp = { "g-test" },
            emitEffect = { emittedEffects.add(it) },
        )
        advanceUntilIdle()

        assertTrue(
            "restore must not emit any EvictSession",
            emittedEffects.filterIsInstance<cn.vectory.ocdroid.ui.controller.ControllerEffect.EvictSession>().isEmpty(),
        )
    }

    @Test
    fun `C3 launchSetSessionArchived skips EvictSession when providers are null - legacy caller`() = runTest {
        // Backward-compat: callers that haven't been migrated (no
        // currentServerGroupFp / emitEffect) MUST behave as before (no
        // eviction). This protects any test/caller that hasn't been updated.
        val session = Session(id = "s1", directory = "/x")
        store.mutateSessionList { it.copy(sessions = listOf(session)) }
        coEvery { repository.updateSessionArchived("s1", any()) } returns Result.success(session.copy(time = Session.TimeInfo(archived = 1L)))
        val emittedEffects = mutableListOf<cn.vectory.ocdroid.ui.controller.ControllerEffect>()

        launchSetSessionArchived(
            scope, repository, slices, settingsManager,
            sessionId = "s1", archived = true,
            emit = emit,
            // currentServerGroupFp + emitEffect intentionally omitted (legacy caller).
        )
        advanceUntilIdle()

        assertTrue(
            "legacy caller path must not emit any EvictSession",
            emittedEffects.filterIsInstance<cn.vectory.ocdroid.ui.controller.ControllerEffect.EvictSession>().isEmpty(),
        )
    }

    @Test
    fun `C3 launchSetSessionArchived does NOT emit EvictSession on failed archive`() = runTest {
        // The eviction must fire AFTER the REST call confirms success (plan
        // §3 N6: "避免乐观清缓存"). A failed archive MUST NOT evict.
        val session = Session(id = "s1", directory = "/x")
        store.mutateSessionList { it.copy(sessions = listOf(session)) }
        coEvery { repository.updateSessionArchived(any(), any()) } returns Result.failure(IllegalStateException("denied"))
        val emittedEffects = mutableListOf<cn.vectory.ocdroid.ui.controller.ControllerEffect>()

        launchSetSessionArchived(
            scope, repository, slices, settingsManager,
            sessionId = "s1", archived = true,
            emit = emit,
            currentServerGroupFp = { "g-test" },
            emitEffect = { emittedEffects.add(it) },
        )
        advanceUntilIdle()

        assertTrue(
            "failed archive must not emit any EvictSession (no optimistic eviction)",
            emittedEffects.filterIsInstance<cn.vectory.ocdroid.ui.controller.ControllerEffect.EvictSession>().isEmpty(),
        )
    }

    @Test
    fun `C3 launchDeleteSession emits exactly one EvictSession on success`() = runTest {
        val session = Session(id = "s1", directory = "/x")
        store.mutateSessionList { it.copy(sessions = listOf(session)) }
        coEvery { repository.deleteSession(any()) } returns Result.success(Unit)
        val emittedEffects = mutableListOf<cn.vectory.ocdroid.ui.controller.ControllerEffect>()

        launchDeleteSession(
            scope, repository, slices, settingsManager,
            sessionId = "s1", onSelectSession = {},
            emit = emit,
            currentServerGroupFp = { "g-test" },
            emitEffect = { emittedEffects.add(it) },
        )
        advanceUntilIdle()

        val evictions = emittedEffects.filterIsInstance<cn.vectory.ocdroid.ui.controller.ControllerEffect.EvictSession>()
        assertEquals("delete must emit exactly one EvictSession", 1, evictions.size)
        assertEquals("s1", evictions.single().sessionId)
        assertEquals("g-test", evictions.single().serverGroupFp)
    }

    @Test
    fun `C3 launchDeleteSession does NOT emit EvictSession on failure`() = runTest {
        // Same optimistic-eviction guard as archive: a failed delete MUST NOT
        // evict (the session still exists server-side).
        val session = Session(id = "s1", directory = "/x")
        store.mutateSessionList { it.copy(sessions = listOf(session)) }
        coEvery { repository.deleteSession(any()) } returns Result.failure(IllegalStateException("denied"))
        val emittedEffects = mutableListOf<cn.vectory.ocdroid.ui.controller.ControllerEffect>()

        launchDeleteSession(
            scope, repository, slices, settingsManager,
            sessionId = "s1", onSelectSession = {},
            emit = emit,
            currentServerGroupFp = { "g-test" },
            emitEffect = { emittedEffects.add(it) },
        )
        advanceUntilIdle()

        assertTrue(
            "failed delete must not emit any EvictSession",
            emittedEffects.filterIsInstance<cn.vectory.ocdroid.ui.controller.ControllerEffect.EvictSession>().isEmpty(),
        )
    }

    // ── launchSendMessage ─────────────────────────────────────────────────────

    @Test
    fun `launchSendMessage success bumps session status to busy and triggers callbacks`() = runTest {
        val session = Session(id = "s1", directory = "/x")
        store.mutateSessionList { it.copy(sessions = listOf(session)) }
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        var refreshMsg = 0
        var refreshSessions = 0
        var successCalled = 0
        var completeCalled = 0

        launchSendMessage(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            text = "hello",
            agent = "build",
            model = null,
            onRefreshMessages = { _, _ -> refreshMsg += 1 },
            onRefreshSessions = { refreshSessions += 1 },
            onSuccess = { successCalled += 1 },
            onComplete = { completeCalled += 1 },
            emit = emit,
        )
        advanceUntilIdle()

        coVerify { repository.sendMessage("s1", "hello", "build", null, any()) }
        assertEquals("busy", slices.sessionList.value.sessionStatuses["s1"]?.type)
        assertEquals(1, refreshMsg)
        assertEquals(1, refreshSessions)
        assertEquals(1, successCalled)
        assertEquals(1, completeCalled)
    }

    // ── gro-2 Blocker 2a: send-success resurrects archived session guard ─────

    @Test
    fun `gro-2 Blocker 2a - launchSendMessage skips bump and refresh when session archived mid-send`() = runTest {
        // If the session was archived/evicted mid-send (cross-device archive
        // during prompt_async), onSuccess must NOT bump time.updated, write
        // sessionStatuses[busy], or fire onRefreshSessions/onRefreshMessages —
        // that would resurrect a dead session as ghost-busy.
        val archived = Session(id = "s1", directory = "/x", time = Session.TimeInfo(archived = 1L))
        store.mutateSessionList { it.copy(sessions = listOf(archived)) }
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        var refreshMsg = 0
        var refreshSessions = 0
        var successCalled = 0
        var completeCalled = 0

        launchSendMessage(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            text = "hello",
            agent = "build",
            model = null,
            onRefreshMessages = { _, _ -> refreshMsg += 1 },
            onRefreshSessions = { refreshSessions += 1 },
            onSuccess = { successCalled += 1 },
            onComplete = { completeCalled += 1 },
            emit = emit,
        )
        advanceUntilIdle()

        // No ghost-busy: sessionStatuses NOT written.
        assertNull("no busy status written for archived session", slices.sessionList.value.sessionStatuses["s1"])
        // No refresh triggered.
        assertEquals("onRefreshMessages NOT called for archived session", 0, refreshMsg)
        assertEquals("onRefreshSessions NOT called for archived session", 0, refreshSessions)
        // onSuccess NOT called (the session is dead — no success side-effects).
        assertEquals("onSuccess NOT called for archived session", 0, successCalled)
        // onComplete IS called (the outer onComplete?.invoke() always fires
        // after the Result block — it is the "finally" equivalent).
        assertEquals("onComplete called (cleanup)", 1, completeCalled)
    }

    @Test
    fun `gro-2 Blocker 2a - launchSendMessage proceeds normally when session absent from list (not yet loaded)`() = runTest {
        // A session that is absent from sessionList.sessions (not yet loaded /
        // cold-start window) is NOT the same as archived. The guard bails ONLY
        // when the session is explicitly present AND archived — absence is
        // treated leniently (the session was valid at dispatch time).
        store.mutateSessionList { it.copy(sessions = emptyList()) }
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        var refreshMsg = 0
        var refreshSessions = 0
        var successCalled = 0

        launchSendMessage(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "not-loaded-yet",
            text = "hello",
            agent = "build",
            model = null,
            onRefreshMessages = { _, _ -> refreshMsg += 1 },
            onRefreshSessions = { refreshSessions += 1 },
            onSuccess = { successCalled += 1 },
            emit = emit,
        )
        advanceUntilIdle()

        // Absent ≠ archived → normal path proceeds (lenient).
        assertEquals("busy status written (absent is not archived)", "busy", slices.sessionList.value.sessionStatuses["not-loaded-yet"]?.type)
        assertEquals("onRefreshMessages called", 1, refreshMsg)
        assertEquals("onRefreshSessions called", 1, refreshSessions)
        assertEquals("onSuccess called", 1, successCalled)
    }

    @Test
    fun `gro-2 Blocker 2a - launchSendMessage proceeds normally when user switched away to non-archived session`() = runTest {
        // Critical regression guard: the guard must NOT break the legitimate
        // "user switched away mid-send" case. The sent-to session is still
        // alive (present + not archived) — just not current anymore. The bump
        // + busy status + refresh MUST fire normally (the sent-to session
        // deserves its activity bump regardless of which session is open).
        val sentTo = Session(id = "s1", directory = "/x")
        val switchedTo = Session(id = "s2", directory = "/y")
        store.mutateSessionList { it.copy(sessions = listOf(sentTo, switchedTo)) }
        store.mutateChat { it.copy(currentSessionId = "s2") }  // user switched to s2
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        var refreshMsg = 0
        var refreshSessions = 0
        var successCalled = 0

        launchSendMessage(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",  // sent to s1 (not current, but alive)
            text = "hello",
            agent = "build",
            model = null,
            onRefreshMessages = { _, _ -> refreshMsg += 1 },
            onRefreshSessions = { refreshSessions += 1 },
            onSuccess = { successCalled += 1 },
            emit = emit,
        )
        advanceUntilIdle()

        // Normal path: bump + busy + refresh all fire for s1 (stillAlive).
        assertEquals("busy status written for sent-to session", "busy", slices.sessionList.value.sessionStatuses["s1"]?.type)
        assertEquals("onRefreshMessages called", 1, refreshMsg)
        assertEquals("onRefreshSessions called", 1, refreshSessions)
        assertEquals("onSuccess called", 1, successCalled)
    }

    @Test
    fun `launchSendMessage failure restores input and emits error`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.failure(IllegalStateException("boom"))
        var completeCalled = 0

        launchSendMessage(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            text = "hi",
            agent = "build",
            model = null,
            onRefreshMessages = { _, _ -> },
            onRefreshSessions = {},
            onComplete = { completeCalled += 1 },
            emit = emit,
        )
        advanceUntilIdle()

        // inputText restored to the failed prompt.
        assertEquals("hi", slices.composer.value.inputText)
        val err = emitted.filterIsInstance<UiEvent.Error>().single()
        assertEquals(R.string.error_send_message_failed, err.resId)
        // onComplete ALWAYS invoked (even on failure).
        assertEquals(1, completeCalled)
    }

    @Test
    fun `launchSendMessage failure preserves user-typed-newer input over restore`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.failure(IllegalStateException("boom"))

        launchSendMessage(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            text = "old",
            agent = "build",
            model = null,
            onRefreshMessages = { _, _ -> },
            onRefreshSessions = {},
            emit = emit,
        )
        // User typed something newer while send was in flight.
        store.mutateComposer { it.copy(inputText = "newer draft") }
        advanceUntilIdle()

        // Preserve the newer draft over the restore.
        assertEquals("newer draft", slices.composer.value.inputText)
    }

    @Test
    fun `launchSendMessage passes attachments and agent through`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        val attachment = ComposerImageAttachment(
            id = "att1",
            dataUrl = "data:image/png;base64,AAAA",
            mime = "image/png",
            filename = "p.png",
            thumbnailData = ByteArray(0),
            byteSize = 4,
        )

        launchSendMessage(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            text = "look",
            attachments = listOf(attachment),
            agent = "review",
            model = Message.ModelInfo("openai", "gpt-5"),
            onRefreshMessages = { _, _ -> },
            onRefreshSessions = {},
            emit = emit,
        )
        advanceUntilIdle()

        coVerify {
            repository.sendMessage(
                "s1",
                "look",
                "review",
                Message.ModelInfo("openai", "gpt-5"),
                listOf(attachment),
            )
        }
    }
}
