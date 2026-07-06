package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        store = SharedStateStore()
        slices = store.slices
        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        every { settingsManager.getAgentForSession(any()) } returns null
        every { settingsManager.getModelForSession(any()) } returns null
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
    fun `launchSetSessionArchived archives subtree children-first`() = runTest {
        // archive=true → parentFirst=!archived=false → children first.
        val parent = Session(id = "p", directory = "/x")
        val child = Session(id = "c", directory = "/x", parentId = "p")
        store.mutateSessionList { it.copy(sessions = listOf(parent, child)) }
        coEvery { repository.updateSessionArchived("p", any()) } returns Result.success(parent.copy(time = Session.TimeInfo(archived = 1L)))
        coEvery { repository.updateSessionArchived("c", any()) } returns Result.success(child.copy(time = Session.TimeInfo(archived = 1L)))

        launchSetSessionArchived(scope, repository, slices, settingsManager, sessionId = "p", archived = true, emit = emit)
        advanceUntilIdle()

        // Both archived.
        assertTrue(slices.sessionList.value.sessions.all { it.isArchived })
        // Child first then parent (archive walks children before parent).
        coVerifyOrder {
            repository.updateSessionArchived("c", any())
            repository.updateSessionArchived("p", any())
        }
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
