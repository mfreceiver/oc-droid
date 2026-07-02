package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.ui.AppState
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.FileState
import cn.vectory.ocdroid.ui.HostState
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SettingsState
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.TrafficState
import cn.vectory.ocdroid.ui.UnreadState
import cn.vectory.ocdroid.ui.syncSlicesFromAppState
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-16 M4: independent unit test for [SessionSyncCoordinator].
 *
 * Zero reflection — the coordinator is driven entirely through its public
 * [SessionSyncCoordinator.handleEvent] API and asserted via direct
 * `MutableStateFlow<AppState>` reads + the [RecordingSessionSyncCoordinatorCallbacks]
 * spy. The dispatch body is a verbatim move of the pre-extraction
 * `handleIncomingSseEvent`, so these cases are the behaviour-equivalence proof
 * that the SSE fold (message patch/insert, session upsert, status badge, part
 * streaming overlay, permission/question/todo, server.connected catch-up
 * trigger) is byte-for-byte preserved. AppState + SliceFlows are real so
 * `updateAndSync` runs and the coordinator's state writes are observable.
 * Follows the [HostProfileControllerTest] / [SessionSwitcherTest] pattern.
 */
@Suppress("DEPRECATION")
class SessionSyncCoordinatorTest {

    private lateinit var state: MutableStateFlow<AppState>
    private lateinit var slices: SliceFlows
    private lateinit var callbacks: RecordingSessionSyncCoordinatorCallbacks
    private lateinit var settingsManager: SettingsManager
    private lateinit var scope: TestScope
    private lateinit var coordinator: SessionSyncCoordinator

    @Before
    fun setUp() {
        state = MutableStateFlow(AppState())
        slices = SliceFlows(
            connection = MutableStateFlow(ConnectionState()),
            traffic = MutableStateFlow(TrafficState()),
            composer = MutableStateFlow(ComposerState()),
            file = MutableStateFlow(FileState()),
            settings = MutableStateFlow(SettingsState()),
            chat = MutableStateFlow(ChatState()),
            sessionList = MutableStateFlow(SessionListState()),
            unread = MutableStateFlow(UnreadState()),
            host = MutableStateFlow(HostState())
        )
        settingsManager = mockk(relaxed = true)
        callbacks = RecordingSessionSyncCoordinatorCallbacks()
        scope = TestScope()
        coordinator = SessionSyncCoordinator(scope, state, slices, settingsManager, callbacks)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Seeds AppState then propagates to the slices (the coordinator reads
     * slices). R-17 M5: controllers no longer read the AppState mirror.
     */
    private fun seed(transform: (AppState) -> AppState) {
        state.value = transform(state.value)
        syncSlicesFromAppState(state.value, slices)
    }

    /** Current-session guard: many folds only touch the current session's view. */
    private fun setCurrentSession(sessionId: String?) {
        seed { it.copy(currentSessionId = sessionId) }
    }

    private fun event(type: String, block: JsonObjectBuilder.() -> Unit): SSEEvent =
        SSEEvent(payload = SSEPayload(type = type, properties = buildJsonObject(block)))

    // ── server.connected ───────────────────────────────────────────────────

    @Test
    fun `server connected routes to onServerConnected and is a no-op in the dispatch table`() {
        coordinator.handleEvent(event("server.connected") {})

        assertEquals(1, callbacks.onServerConnectedCalls)
        // No state mutation, no other callback.
        assertTrue(callbacks.onRefreshMessagesCalls.isEmpty())
        assertEquals(0, callbacks.onNonFatalIssueCalls)
    }

    // ── session.created / session.updated (upsert) ─────────────────────────

    @Test
    fun `session created prepends the parsed session via upsert`() {
        seed {
            it.copy(
            sessions = listOf(Session(id = "session-1", directory = "/tmp/old"))
            )
        }

        coordinator.handleEvent(event("session.created") {
            put("session", buildJsonObject {
                put("id", JsonPrimitive("session-2"))
                put("directory", JsonPrimitive("/tmp/project"))
                put("title", JsonPrimitive("New Session"))
            })
        })

        assertEquals(listOf("session-2", "session-1"), slices.sessionList.value.sessions.map { it.id })
    }

    @Test
    fun `session created with unparseable payload fires onNonFatalIssue without mutating state`() {
        coordinator.handleEvent(event("session.created") {
            put("session", JsonPrimitive("not-an-object"))
        })

        assertEquals(1, callbacks.onNonFatalIssueCalls)
        assertTrue(slices.sessionList.value.sessions.isEmpty())
    }

    @Test
    fun `session updated replaces the existing session title from info`() {
        seed {
            it.copy(
            sessions = listOf(Session(id = "session-1", directory = "/tmp/project", title = null))
            )
        }

        coordinator.handleEvent(event("session.updated") {
            put("info", buildJsonObject {
                put("id", JsonPrimitive("session-1"))
                put("directory", JsonPrimitive("/tmp/project"))
                put("title", JsonPrimitive("Refactor auth module"))
            })
        })

        val sessions = slices.sessionList.value.sessions
        assertEquals(1, sessions.size)
        assertEquals("Refactor auth module", sessions[0].title)
    }

    @Test
    fun `session updated inserts an unknown session from the session key`() {
        seed {
            it.copy(
            sessions = listOf(Session(id = "session-1", directory = "/tmp/old"))
            )
        }

        coordinator.handleEvent(event("session.updated") {
            put("session", buildJsonObject {
                put("id", JsonPrimitive("session-new"))
                put("directory", JsonPrimitive("/tmp/new"))
                put("title", JsonPrimitive("New Feature"))
            })
        })

        val ids = slices.sessionList.value.sessions.map { it.id }
        assertEquals(listOf("session-new", "session-1"), ids)
    }

    @Test
    fun `session updated archived evicts the id from openSessionIds and persists`() {
        // Archived-by-another-client path: the SSE frame flips the session to
        // archived. The handler must drop it from openSessionIds (so the tab
        // strip drops it) and persist the cleaned list.
        seed {
            it.copy(
                sessions = listOf(Session(id = "session-1", directory = "/tmp/project")),
                openSessionIds = listOf("session-1", "session-2")
            )
        }

        coordinator.handleEvent(event("session.updated") {
            put("info", buildJsonObject {
                put("id", JsonPrimitive("session-1"))
                put("directory", JsonPrimitive("/tmp/project"))
                put("time", buildJsonObject { put("archived", JsonPrimitive(1_700_000_000_000)) })
            })
        })

        // Upsert still happened (authoritative record kept), but the tab list
        // dropped the now-archived id.
        assertTrue(slices.sessionList.value.sessions.any { it.id == "session-1" && it.isArchived })
        assertEquals(listOf("session-2"), slices.sessionList.value.openSessionIds)
        verify { settingsManager.openSessionIds = listOf("session-2") }
    }

    @Test
    fun `session updated archived clears currentSessionId and messages when it is the open session`() {
        // Cross-client archive of the currently-open session: the SSE handler
        // must not only drop the tab (openSessionIds) but also clear
        // currentSessionId + messages so the chat view falls back to empty,
        // aligning with the user-triggered archive path.
        seed {
            it.copy(
                sessions = listOf(Session(id = "session-1", directory = "/tmp/project")),
                openSessionIds = listOf("session-1"),
                currentSessionId = "session-1",
                messages = listOf(Message(id = "msg-1", role = "assistant")),
                partsByMessage = mapOf("msg-1" to emptyList())
            )
        }

        coordinator.handleEvent(event("session.updated") {
            put("info", buildJsonObject {
                put("id", JsonPrimitive("session-1"))
                put("directory", JsonPrimitive("/tmp/project"))
                put("time", buildJsonObject { put("archived", JsonPrimitive(1_700_000_000_000)) })
            })
        })

        assertNull(slices.chat.value.currentSessionId)
        assertTrue(slices.chat.value.messages.isEmpty())
        assertTrue(slices.chat.value.partsByMessage.isEmpty())
        assertEquals(emptyList<String>(), slices.sessionList.value.openSessionIds)
        verify { settingsManager.currentSessionId = null }
    }

    @Test
    fun `session updated non-archived keeps openSessionIds untouched and does not persist`() {
        seed {
            it.copy(
                sessions = listOf(Session(id = "session-1", directory = "/tmp/project")),
                openSessionIds = listOf("session-1", "session-2")
            )
        }

        coordinator.handleEvent(event("session.updated") {
            put("info", buildJsonObject {
                put("id", JsonPrimitive("session-1"))
                put("directory", JsonPrimitive("/tmp/project"))
                put("title", JsonPrimitive("Edited title"))
            })
        })

        assertEquals(listOf("session-1", "session-2"), slices.sessionList.value.openSessionIds)
        verify(exactly = 0) { settingsManager.openSessionIds = any() }
    }

    // ── session.status (busy / idle / temp-cleared finalization) ────────────

    @Test
    fun `session status busy updates the badge and triggers a resetLimit reload for the current session`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("session-1"))
            put("status", buildJsonObject { put("type", JsonPrimitive("busy")) })
        })

        assertNotNull(slices.sessionList.value.sessionStatuses["session-1"])
        assertTrue(slices.sessionList.value.sessionStatuses["session-1"]!!.isBusy)
        assertEquals(listOf("session-1" to true), callbacks.onRefreshMessagesCalls)
    }

    @Test
    fun `session status busy for a non-current session only updates the badge`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("session-2"))
            put("status", buildJsonObject { put("type", JsonPrimitive("busy")) })
        })

        assertTrue(slices.sessionList.value.sessionStatuses["session-2"]!!.isBusy)
        assertTrue(callbacks.onRefreshMessagesCalls.isEmpty())
    }

    @Test
    fun `session status idle on current with a non-empty streaming overlay triggers a resetLimit reload`() {
        setCurrentSession("session-1")
        seed {
            it.copy(
            streamingPartTexts = mapOf("part-1" to "partial"),
            streamingReasoningPart = Part(id = "part-1", messageId = "m1", sessionId = "session-1", type = "reasoning")
            )
        }

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("session-1"))
            put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
        })

        assertFalse(slices.sessionList.value.sessionStatuses["session-1"]!!.isBusy)
        assertEquals(listOf("session-1" to true), callbacks.onRefreshMessagesCalls)
    }

    @Test
    fun `session status idle on current with an empty overlay skips the reload`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("session-1"))
            put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
        })

        assertFalse(slices.sessionList.value.sessionStatuses["session-1"]!!.isBusy)
        assertTrue(callbacks.onRefreshMessagesCalls.isEmpty())
    }

    @Test
    fun `session status idle on a temp-cleared non-current session drops it from tempClearedUnread`() {
        setCurrentSession("session-1")
        seed { it.copy(tempClearedUnread = setOf("session-2")) }

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("session-2"))
            put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
        })

        assertFalse(slices.unread.value.tempClearedUnread.contains("session-2"))
    }

    @Test
    fun `session status with an unparseable payload fires onNonFatalIssue`() {
        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("session-1"))
            put("status", JsonPrimitive("not-an-object"))
        })

        assertEquals(1, callbacks.onNonFatalIssueCalls)
    }

    // ── message.created (forward-compat branch) ────────────────────────────

    @Test
    fun `message created for the current session triggers a resetLimit reload`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("message.created") {
            put("sessionID", JsonPrimitive("session-1"))
        })

        assertEquals(listOf("session-1" to true), callbacks.onRefreshMessagesCalls)
    }

    @Test
    fun `message created for a non-current session marks it unread`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("message.created") {
            put("sessionID", JsonPrimitive("session-2"))
        })

        assertTrue(slices.unread.value.unreadSessions.contains("session-2"))
        assertTrue(callbacks.onRefreshMessagesCalls.isEmpty())
    }

    @Test
    fun `message created for the current session does not mark it unread`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("message.created") {
            put("sessionID", JsonPrimitive("session-1"))
        })

        assertFalse(slices.unread.value.unreadSessions.contains("session-1"))
    }

    // ── message.updated (patch-if-found + insert-if-absent) ─────────────────

    @Test
    fun `message updated patches an existing message in place without reloading`() {
        setCurrentSession("session-1")
        seed {
            it.copy(
            messages = listOf(Message(id = "m1", role = "assistant"), Message(id = "m2", role = "user"))
            )
        }

        coordinator.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m1"))
                put("role", JsonPrimitive("assistant"))
                put("cost", JsonPrimitive(0.5))
            })
        })

        assertEquals(listOf("m1", "m2"), slices.chat.value.messages.map { it.id })
        // No reload issued — patch is in-place.
        assertTrue(callbacks.onRefreshMessagesCalls.isEmpty())
    }

    @Test
    fun `message updated inserts a new message when its id is absent from the local list`() {
        setCurrentSession("session-1")
        seed { it.copy(messages = listOf(Message(id = "m1", role = "user"))) }

        coordinator.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m2"))
                put("role", JsonPrimitive("assistant"))
            })
        })

        // Inserted at the tail (oldest-first list).
        assertEquals(listOf("m1", "m2"), slices.chat.value.messages.map { it.id })
    }

    @Test
    fun `message updated is ignored when the event targets a non-current session`() {
        setCurrentSession("session-1")
        seed { it.copy(messages = listOf(Message(id = "m1", role = "user"))) }

        coordinator.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-other"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m2"))
                put("role", JsonPrimitive("assistant"))
            })
        })

        // Defensive session guard: list untouched.
        assertEquals(listOf("m1"), slices.chat.value.messages.map { it.id })
    }

    // ── message.part.updated (full text / delta / part.created) ─────────────

    @Test
    fun `message part updated full text replaces the streaming value as a sync point`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("message-1"))
                put("id", JsonPrimitive("part-1"))
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive("Hello world"))
            })
        })

        assertEquals("Hello world", slices.chat.value.streamingPartTexts["part-1"])
    }

    @Test
    fun `message part updated delta accumulates into streamingPartTexts and sets a reasoning overlay`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("message-1"))
                put("id", JsonPrimitive("part-1"))
                put("type", JsonPrimitive("reasoning"))
            })
            put("delta", JsonPrimitive("thinking"))
        })

        assertEquals("thinking", slices.chat.value.streamingPartTexts["part-1"])
        assertEquals("part-1", slices.chat.value.streamingReasoningPart?.id)
    }

    @Test
    fun `message part updated with null ids preserves the overlay and triggers a resetLimit=false reload`() {
        setCurrentSession("session-1")
        val seeded = mapOf("part-1" to "stale")
        val seededReasoning = Part(id = "part-1", messageId = "m1", sessionId = "session-1", type = "text")
        seed {
            it.copy(
            streamingPartTexts = seeded,
            streamingReasoningPart = seededReasoning
            )
        }

        // part.created: part object present but no messageID / id yet.
        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("part", buildJsonObject { put("type", JsonPrimitive("text")) })
        })

        // 闪屏修复：part.created（无 id）不再清空 overlay —— 同回合内每个新 part
        // 都发此信号，清空会导致所有流式气泡反复坍缩/充填闪烁。overlay 保留，
        // 仅触发 reload(resetLimit=false) 刷新权威快照。
        assertEquals(seeded, slices.chat.value.streamingPartTexts)
        assertEquals(seededReasoning, slices.chat.value.streamingReasoningPart)
        assertEquals(listOf("session-1" to false), callbacks.onRefreshMessagesCalls)
    }

    @Test
    fun `message part updated is ignored for a non-current session`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("session-other"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("message-1"))
                put("id", JsonPrimitive("part-1"))
                put("type", JsonPrimitive("text"))
            })
            put("delta", JsonPrimitive("ignored"))
        })

        assertTrue(slices.chat.value.streamingPartTexts.isEmpty())
        assertTrue(callbacks.onRefreshMessagesCalls.isEmpty())
    }

    // ── message.part.delta (web independent event) ──────────────────────────

    @Test
    fun `message part delta leading edge writes immediately and trailing deltas coalesce into a 100ms batch`() {
        setCurrentSession("session-1")

        fun delta(d: String) = coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-1"))
            put("messageID", JsonPrimitive("message-1"))
            put("partID", JsonPrimitive("part-1"))
            put("field", JsonPrimitive("text"))
            put("delta", JsonPrimitive(d))
        })

        delta("Hello")
        // §M5 leading edge: first delta renders immediately (zero-latency first token).
        assertEquals("Hello", slices.chat.value.streamingPartTexts["part-1"])

        delta(", world")
        delta("!")
        // §M5 trailing coalesce: subsequent deltas buffer within the 100ms window
        // — they do NOT each trigger a state write / Compose recomposition.
        assertEquals("Hello", slices.chat.value.streamingPartTexts["part-1"])

        // Advance virtual time past the DELTA_COALESCE_MS window → one batched flush.
        scope.testScheduler.advanceUntilIdle()

        assertEquals("Hello, world!", slices.chat.value.streamingPartTexts["part-1"])
        assertTrue(callbacks.onRefreshMessagesCalls.isEmpty())
    }

    @Test
    fun `message part delta opens a fresh leading edge after the coalesce window flushes`() {
        setCurrentSession("session-1")

        fun delta(d: String) = coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-1"))
            put("messageID", JsonPrimitive("message-1"))
            put("partID", JsonPrimitive("part-1"))
            put("delta", JsonPrimitive(d))
        })

        delta("A")
        delta("B")
        scope.testScheduler.advanceUntilIdle()
        assertEquals("AB", slices.chat.value.streamingPartTexts["part-1"])

        // After the window closed, the next delta starts a new leading edge
        // (writes immediately), then a new 100ms window opens.
        delta("C")
        assertEquals("ABC", slices.chat.value.streamingPartTexts["part-1"])
        delta("D")
        assertEquals("ABC", slices.chat.value.streamingPartTexts["part-1"])

        scope.testScheduler.advanceUntilIdle()
        assertEquals("ABCD", slices.chat.value.streamingPartTexts["part-1"])
    }

    @Test
    fun `message part updated full text coalesces and replaces pending deltas so the snapshot stays authoritative`() {
        setCurrentSession("session-1")

        fun delta(d: String) = coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-1"))
            put("messageID", JsonPrimitive("message-1"))
            put("partID", JsonPrimitive("part-1"))
            put("delta", JsonPrimitive(d))
        })

        delta("partial")
        // Buffered delta that would corrupt the authoritative snapshot if flushed.
        delta(" STALE")
        assertEquals("partial", slices.chat.value.streamingPartTexts["part-1"])

        // Authoritative full text arrives while the delta coalesce window is
        // still open. §Site1 coalescing: it is buffered into the REPLACE
        // fullTextBuffer (not written synchronously), so the overlay still
        // shows the leading-edge value until the window flushes.
        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("message-1"))
                put("id", JsonPrimitive("part-1"))
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive("AUTHORITATIVE"))
            })
        })

        // Flushing the coalesce window must apply the authoritative fullText as
        // a REPLACE (fullTextBuffer wins over the deltaBuffer's STALE append),
        // so the stale delta never corrupts the snapshot.
        scope.testScheduler.advanceUntilIdle()
        assertEquals("AUTHORITATIVE", slices.chat.value.streamingPartTexts["part-1"])
    }

    @Test
    fun `message part created with null ids drops pending delta buffers but preserves overlay`() {
        setCurrentSession("session-1")
        seed { it.copy(streamingPartTexts = mapOf("part-1" to "partial")) }

        // Leading edge: first delta writes immediately + opens the 100ms window.
        coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-1"))
            put("messageID", JsonPrimitive("message-1"))
            put("partID", JsonPrimitive("part-1"))
            put("delta", JsonPrimitive(" MORE"))
        })
        assertEquals("partial MORE", slices.chat.value.streamingPartTexts["part-1"])

        // Trailing delta buffers (window still open) — not yet written.
        coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-1"))
            put("messageID", JsonPrimitive("message-1"))
            put("partID", JsonPrimitive("part-1"))
            put("delta", JsonPrimitive(" BUFFERED"))
        })
        // Still "partial MORE" — trailing delta is buffered, not written.
        assertEquals("partial MORE", slices.chat.value.streamingPartTexts["part-1"])

        // part.created (null ids): clearDeltaBuffers() drops the pending
        // " BUFFERED" buffer, but the overlay is PRESERVED (闪屏修复).
        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("part", buildJsonObject { put("type", JsonPrimitive("text")) })
        })

        // Overlay preserved — not cleared.
        assertEquals("partial MORE", slices.chat.value.streamingPartTexts["part-1"])
        // Advancing time must not resurrect the buffered delta (clearDeltaBuffers
        // cancelled the flush job + dropped the buffer).
        scope.testScheduler.advanceUntilIdle()
        assertEquals("partial MORE", slices.chat.value.streamingPartTexts["part-1"])
    }

    @Test
    fun `part-created preserves overlay then idle finalization clears it via resetLimit reload`() {
        setCurrentSession("session-1")
        seed {
            it.copy(
            streamingPartTexts = mapOf("part-1" to "streaming"),
            streamingReasoningPart = Part(id = "part-1", messageId = "m1", sessionId = "session-1", type = "reasoning")
            )
        }

        // part.created (null ids): overlay preserved (闪屏修复), reload(resetLimit=false).
        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("part", buildJsonObject { put("type", JsonPrimitive("text")) })
        })
        assertEquals("streaming", slices.chat.value.streamingPartTexts["part-1"])
        assertEquals(listOf("session-1" to false), callbacks.onRefreshMessagesCalls)

        // session.status idle: overlay still non-empty → idle finalization fires
        // reload(resetLimit=true), which is the safety net that ultimately clears
        // the overlay (MainViewModelMessageActions streamingFinalized branch).
        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("session-1"))
            put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
        })
        assertFalse(slices.sessionList.value.sessionStatuses["session-1"]!!.isBusy)
        assertEquals(listOf("session-1" to false, "session-1" to true), callbacks.onRefreshMessagesCalls)
    }

    @Test
    fun `message part delta is ignored for a non-current session`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-other"))
            put("messageID", JsonPrimitive("message-1"))
            put("partID", JsonPrimitive("part-1"))
            put("delta", JsonPrimitive("ignored"))
        })

        assertTrue(slices.chat.value.streamingPartTexts.isEmpty())
    }

    // ── permission.asked / question.* / todo.updated ────────────────────────

    @Test
    fun `permission asked refreshes pending permissions via the callback`() {
        coordinator.handleEvent(event("permission.asked") {})
        assertEquals(1, callbacks.onLoadPendingPermissionsCalls)
    }

    @Test
    fun `question asked appends a pending question`() {
        coordinator.handleEvent(event("question.asked") {
            put("id", JsonPrimitive("question-1"))
            put("sessionID", JsonPrimitive("session-1"))
            put("questions", buildJsonArray {
                add(buildJsonObject {
                    put("question", JsonPrimitive("Which framework?"))
                    put("header", JsonPrimitive("Framework Choice"))
                    put("options", buildJsonArray {
                        add(buildJsonObject {
                            put("label", JsonPrimitive("React"))
                            put("description", JsonPrimitive("Popular UI library"))
                        })
                    })
                    put("multiple", JsonPrimitive(false))
                    put("custom", JsonPrimitive(true))
                })
            })
        })

        assertEquals(listOf("question-1"), slices.sessionList.value.pendingQuestions.map { it.id })
        assertEquals("session-1", slices.sessionList.value.pendingQuestions.single().sessionId)
    }

    @Test
    fun `question asked is idempotent when the id already exists`() {
        seed {
            it.copy(
            pendingQuestions = listOf(QuestionRequest(id = "question-1", sessionId = "session-1", questions = emptyList()))
            )
        }

        coordinator.handleEvent(event("question.asked") {
            put("id", JsonPrimitive("question-1"))
            put("sessionID", JsonPrimitive("session-1"))
            put("questions", buildJsonArray {})
        })

        assertEquals(1, slices.sessionList.value.pendingQuestions.size)
    }

    @Test
    fun `question rejected removes the pending question by requestID`() {
        seed {
            it.copy(
            pendingQuestions = listOf(
                QuestionRequest(id = "question-1", sessionId = "session-1", questions = emptyList()),
                QuestionRequest(id = "question-2", sessionId = "session-2", questions = emptyList())
            )
            )
        }

        coordinator.handleEvent(event("question.rejected") {
            put("requestID", JsonPrimitive("question-1"))
        })

        assertEquals(listOf("question-2"), slices.sessionList.value.pendingQuestions.map { it.id })
    }

    @Test
    fun `question replied removes the pending question by id fallback`() {
        seed {
            it.copy(
            pendingQuestions = listOf(QuestionRequest(id = "question-1", sessionId = "session-1", questions = emptyList()))
            )
        }

        coordinator.handleEvent(event("question.replied") {
            put("id", JsonPrimitive("question-1"))
        })

        assertTrue(slices.sessionList.value.pendingQuestions.isEmpty())
    }

    @Test
    fun `todo updated writes the parsed todos keyed by sessionID`() {
        coordinator.handleEvent(event("todo.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("todos", buildJsonArray {
                add(buildJsonObject {
                    put("id", JsonPrimitive("todo-1"))
                    put("content", JsonPrimitive("Write tests"))
                    put("status", JsonPrimitive("pending"))
                    put("activeForm", buildJsonObject { put("pastTense", JsonPrimitive("wrote")) })
                })
            })
        })

        val todos = slices.sessionList.value.sessionTodos["session-1"]
        assertNotNull(todos)
        assertEquals(1, todos!!.size)
        assertEquals("todo-1", todos[0].id)
    }

    @Test
    fun `todo updated with a malformed todos array is a no-op`() {
        coordinator.handleEvent(event("todo.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("todos", JsonPrimitive("not-an-array"))
        })

        assertNull(slices.sessionList.value.sessionTodos["session-1"])
    }

    @Test
    fun `an unknown event type is silently ignored`() {
        coordinator.handleEvent(event("plugin.added") { put("sessionID", JsonPrimitive("session-1")) })
        assertEquals(0, callbacks.onNonFatalIssueCalls)
        assertTrue(callbacks.onRefreshMessagesCalls.isEmpty())
    }

    // ── RecordingSessionSyncCoordinatorCallbacks ────────────────────────────

    /**
     * Handwritten spy (per the codebase's zero-reflection test convention) that
     * records every [SessionSyncCoordinatorCallbacks] invocation so tests can
     * assert on side effects (reload requests, permission refresh, catch-up
     * trigger, non-fatal logging).
     */
    private class RecordingSessionSyncCoordinatorCallbacks : SessionSyncCoordinatorCallbacks {
        var onServerConnectedCalls = 0
        var onLoadPendingPermissionsCalls = 0
        var onNonFatalIssueCalls = 0
        val onRefreshMessagesCalls = mutableListOf<Pair<String, Boolean>>()
        val onNonFatalIssues = mutableListOf<String>()

        override fun onServerConnected() { onServerConnectedCalls++ }
        override fun onRefreshMessages(sessionId: String, resetLimit: Boolean) {
            onRefreshMessagesCalls += sessionId to resetLimit
        }
        override fun onRefreshSessions() {}
        override fun onLoadPendingPermissions() { onLoadPendingPermissionsCalls++ }
        override fun onNonFatalIssue(message: String) {
            onNonFatalIssueCalls++
            onNonFatalIssues += message
        }
    }
}
