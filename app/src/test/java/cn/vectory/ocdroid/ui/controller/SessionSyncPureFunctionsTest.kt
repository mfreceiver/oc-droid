package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.QuestionInfo
import cn.vectory.ocdroid.data.model.QuestionOption
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.UnreadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §R-17 batch5: table-driven unit tests for the pure SSE state transforms
 * extracted out of [SessionSyncCoordinator.dispatchSseEvent].
 *
 * Each test calls an `applyXxx` extension directly on a hand-constructed
 * slice value — no SliceFlows, no SharedEffectBus, no coroutines, no Android
 * framework. This is the semi-formalization boundary: the state math is now
 * unit-testable in isolation, while the dispatcher's side-effect sequencing
 * (effect emits, settings writes, scope.launch) is still proven by the
 * integration-level [SessionSyncCoordinatorTest].
 *
 * Coverage targets (dser / gpter review):
 *  - message.updated before/after part.updated
 *  - part.delta before placeholder
 *  - fullText shorter / divergent
 *  - busy reload then idle finalization
 *  - session switch while flush job pending
 *  - reconnect gap closure (not strictly a pure-transform concern — covered
 *    via the catch-up path; here we cover the buffer-drop semantics that
 *    underpin it: a wiped overlay drops both buffers).
 */
@Suppress("DEPRECATION")
class SessionSyncPureFunctionsTest {

    // ── message.updated: applyMessageUpdated ───────────────────────────────

    @Test
    fun `applyMessageUpdated replaces an existing message in place`() {
        val state = ChatState(messages = listOf(
            Message(id = "m1", role = "user"),
            Message(id = "m2", role = "assistant")
        ))
        val updated = Message(id = "m1", role = "user", cost = 0.42)

        val (next, found) = state.applyMessageUpdated(updated)

        assertTrue("found flag must be true when id was already present", found)
        assertEquals(listOf("m1", "m2"), next.messages.map { it.id })
        assertEquals(0.42, next.messages.first { it.id == "m1" }.cost!!, 1e-9)
    }

    @Test
    fun `applyMessageUpdated inserts a new message at the tail when absent`() {
        val state = ChatState(messages = listOf(
            Message(id = "m1", role = "user")
        ))
        val updated = Message(id = "m2", role = "assistant")

        val (next, found) = state.applyMessageUpdated(updated)

        assertFalse("found flag must be false when id was absent", found)
        assertEquals(listOf("m1", "m2"), next.messages.map { it.id })
    }

    @Test
    fun `applyMessageUpdated preserves unrelated message order on patch`() {
        val state = ChatState(messages = listOf(
            Message(id = "a", role = "user"),
            Message(id = "b", role = "assistant"),
            Message(id = "c", role = "user"),
            Message(id = "d", role = "assistant")
        ))
        val updated = Message(id = "c", role = "user", cost = 1.0)

        val (next, found) = state.applyMessageUpdated(updated)

        assertTrue(found)
        assertEquals(listOf("a", "b", "c", "d"), next.messages.map { it.id })
    }

    @Test
    fun `applyMessageUpdated on empty list inserts as the sole message`() {
        val state = ChatState(messages = emptyList())
        val updated = Message(id = "solo", role = "assistant")

        val (next, found) = state.applyMessageUpdated(updated)

        assertFalse(found)
        assertEquals(listOf("solo"), next.messages.map { it.id })
    }

    // ── session.created / session.updated / archived ───────────────────────

    @Test
    fun `applySessionCreated prepends via upsert`() {
        val state = SessionListState(sessions = listOf(
            Session(id = "old", directory = "/tmp/old")
        ))
        val created = Session(id = "new", directory = "/tmp/new")

        val next = state.applySessionCreated(created)

        assertEquals(listOf("new", "old"), next.sessions.map { it.id })
    }

    @Test
    fun `applySessionCreated on empty list yields the sole session`() {
        val state = SessionListState()
        val created = Session(id = "solo", directory = "/x")

        val next = state.applySessionCreated(created)

        assertEquals(listOf("solo"), next.sessions.map { it.id })
    }

    @Test
    fun `applySessionUpsert replaces an existing session id`() {
        val state = SessionListState(sessions = listOf(
            Session(id = "s1", directory = "/tmp", title = "old title")
        ))
        val updated = Session(id = "s1", directory = "/tmp", title = "new title")

        val next = state.applySessionUpsert(updated)

        assertEquals(1, next.sessions.size)
        assertEquals("new title", next.sessions[0].title)
    }

    @Test
    fun `applyArchiveEviction drops the archived id from openSessionIds and upserts`() {
        val state = SessionListState(
            sessions = listOf(Session(id = "s1", directory = "/tmp")),
            openSessionIds = listOf("s1", "s2", "s3")
        )
        val archived = Session(
            id = "s2",
            directory = "/tmp",
            time = Session.TimeInfo(archived = 1_700_000_000_000)
        )

        val next = state.applyArchiveEviction(archived, listOf("s1", "s3"))

        assertTrue(next.sessions.any { it.id == "s2" && it.isArchived })
        assertEquals(listOf("s1", "s3"), next.openSessionIds)
    }

    @Test
    fun `applyArchivedChatClear wipes currentSessionId messages and partsByMessage`() {
        val state = ChatState(
            currentSessionId = "s1",
            messages = listOf(Message(id = "m1", role = "user")),
            partsByMessage = mapOf("m1" to emptyList()),
            streamingPartTexts = mapOf("p1" to "leftover")
        )

        val next = state.applyArchivedChatClear()

        assertNull(next.currentSessionId)
        assertTrue(next.messages.isEmpty())
        assertTrue(next.partsByMessage.isEmpty())
        // streamingPartTexts is intentionally NOT cleared by the archive path
        // (matches the original dispatcher behaviour — only the chat-list /
        // parts / currentSessionId are wiped).
        assertEquals("leftover", next.streamingPartTexts["p1"])
    }

    // ── session.status: applySessionStatus / dropTempCleared ───────────────

    @Test
    fun `applySessionStatus upserts the badge entry`() {
        val state = SessionListState(
            sessionStatuses = mapOf("s1" to SessionStatus(type = "idle"))
        )

        val next = state.applySessionStatus("s1", SessionStatus(type = "busy"))

        assertEquals(SessionStatus(type = "busy"), next.sessionStatuses["s1"])
        assertEquals(1, next.sessionStatuses.size)
    }

    @Test
    fun `applySessionStatus adds a new entry without disturbing others`() {
        val state = SessionListState(
            sessionStatuses = mapOf("s1" to SessionStatus(type = "idle"))
        )

        val next = state.applySessionStatus("s2", SessionStatus(type = "busy"))

        assertEquals(2, next.sessionStatuses.size)
        assertEquals(SessionStatus(type = "idle"), next.sessionStatuses["s1"])
        assertEquals(SessionStatus(type = "busy"), next.sessionStatuses["s2"])
    }

    @Test
    fun `dropTempCleared removes the id`() {
        val state = UnreadState(tempClearedUnread = setOf("s1", "s2", "s3"))

        val next = state.dropTempCleared("s2")

        assertEquals(setOf("s1", "s3"), next.tempClearedUnread)
    }

    @Test
    fun `dropTempCleared is a no-op when the id is absent`() {
        val state = UnreadState(tempClearedUnread = setOf("s1"))

        val next = state.dropTempCleared("missing")

        assertEquals(setOf("s1"), next.tempClearedUnread)
    }

    // ── question.* / todo.updated ──────────────────────────────────────────

    @Test
    fun `applyQuestionAsked appends a new question`() {
        val state = SessionListState()
        val question = QuestionRequest(
            id = "q1",
            sessionId = "s1",
            questions = listOf(
                QuestionInfo(
                    question = "Which?",
                    header = "Choice",
                    options = listOf(QuestionOption("A", "desc"))
                )
            )
        )

        val next = state.applyQuestionAsked(question)

        assertEquals(listOf("q1"), next.pendingQuestions.map { it.id })
    }

    @Test
    fun `applyQuestionAsked is idempotent on duplicate id`() {
        val existing = QuestionRequest(
            id = "q1", sessionId = "s1", questions = emptyList()
        )
        val state = SessionListState(pendingQuestions = listOf(existing))
        val dup = QuestionRequest(
            id = "q1", sessionId = "s1",
            questions = listOf(QuestionInfo("other", "h", emptyList()))
        )

        val next = state.applyQuestionAsked(dup)

        assertEquals(1, next.pendingQuestions.size)
        // Original is preserved (insert-if-absent semantics).
        assertEquals(0, next.pendingQuestions[0].questions.size)
    }

    @Test
    fun `applyQuestionResolved removes by requestID`() {
        val state = SessionListState(pendingQuestions = listOf(
            QuestionRequest(id = "q1", sessionId = "s1", questions = emptyList()),
            QuestionRequest(id = "q2", sessionId = "s2", questions = emptyList())
        ))

        val next = state.applyQuestionResolved("q1")

        assertEquals(listOf("q2"), next.pendingQuestions.map { it.id })
    }

    @Test
    fun `applyQuestionResolved is a no-op when the id is absent`() {
        val state = SessionListState(pendingQuestions = listOf(
            QuestionRequest(id = "q1", sessionId = "s1", questions = emptyList())
        ))

        val next = state.applyQuestionResolved("missing")

        assertEquals(listOf("q1"), next.pendingQuestions.map { it.id })
    }

    @Test
    fun `applyTodoUpdated overwrites the entry for the same session`() {
        val state = SessionListState(
            sessionTodos = mapOf("s1" to listOf(TodoItem("old", "pending", "low", "t0")))
        )
        val todos = listOf(
            TodoItem("new", "completed", "high", "t1"),
            TodoItem("second", "pending", "medium", "t2")
        )

        val next = state.applyTodoUpdated("s1", todos)

        assertEquals(2, next.sessionTodos["s1"]?.size)
        assertEquals("new", next.sessionTodos["s1"]!![0].content)
    }

    @Test
    fun `applyTodoUpdated adds a new session entry without disturbing others`() {
        val state = SessionListState(
            sessionTodos = mapOf("s1" to listOf(TodoItem("a", "pending", "low", "t0")))
        )

        val next = state.applyTodoUpdated("s2", listOf(TodoItem("b", "pending", "low", "t1")))

        assertNotNull(next.sessionTodos["s1"])
        assertNotNull(next.sessionTodos["s2"])
    }

    // ── part.updated placeholder / leading-edge writes ─────────────────────

    @Test
    fun `applyPartCreatedPlaceholder injects a reasoning part and sets streamingReasoningPart`() {
        val state = ChatState()

        val next = state.applyPartCreatedPlaceholder(
            partType = "reasoning",
            partId = "p1",
            msgId = "m1",
            sessionId = "s1"
        )

        assertEquals("reasoning", next.partsByMessage["m1"]?.firstOrNull()?.type)
        assertEquals("p1", next.partsByMessage["m1"]!!.first().id)
        assertEquals("p1", next.streamingReasoningPart?.id)
        assertEquals("m1", next.streamingReasoningPart?.messageId)
    }

    @Test
    fun `applyPartCreatedPlaceholder for text type does not set streamingReasoningPart`() {
        val state = ChatState()

        val next = state.applyPartCreatedPlaceholder(
            partType = "text",
            partId = "p1",
            msgId = "m1",
            sessionId = "s1"
        )

        assertEquals("text", next.partsByMessage["m1"]?.firstOrNull()?.type)
        assertNull(next.streamingReasoningPart)
    }

    @Test
    fun `applyPartCreatedPlaceholder is idempotent`() {
        val state = ChatState()

        val first = state.applyPartCreatedPlaceholder("reasoning", "p1", "m1", "s1")
        val second = first.applyPartCreatedPlaceholder("reasoning", "p1", "m1", "s1")

        assertEquals(1, second.partsByMessage["m1"]?.count { it.id == "p1" })
    }

    @Test
    fun `applyPartDeltaLeadingEdge appends onto prior streamingPartTexts value`() {
        // §part.delta before placeholder: a delta may arrive before any
        // part.updated creation event — the leading edge both seeds the
        // placeholder AND writes the streaming text in one pass.
        val state = ChatState(streamingPartTexts = mapOf("p1" to "Hello"))

        val next = state.applyPartDeltaLeadingEdge(
            partId = "p1",
            delta = ", world",
            knownType = "text",
            msgId = "m1",
            sessionId = "s1"
        )

        assertEquals("Hello, world", next.streamingPartTexts["p1"])
        // Placeholder part is ensured (defensive — first delta for this part).
        assertEquals("text", next.partsByMessage["m1"]?.firstOrNull { it.id == "p1" }?.type)
    }

    @Test
    fun `applyPartDeltaLeadingEdge on missing overlay seeds an empty-string base`() {
        val state = ChatState()

        val next = state.applyPartDeltaLeadingEdge(
            partId = "p1",
            delta = "first token",
            knownType = "text",
            msgId = "m1",
            sessionId = "s1"
        )

        assertEquals("first token", next.streamingPartTexts["p1"])
    }

    @Test
    fun `applyPartFullTextLeadingEdge replaces overlay with authoritative snapshot`() {
        // §fullText shorter / divergent: REPLACE semantics mean a shorter
        // fullText still wins outright (the server is the source of truth).
        val state = ChatState(streamingPartTexts = mapOf("p1" to "longer trailing delta accumulation"))

        val next = state.applyPartFullTextLeadingEdge(
            partId = "p1",
            fullText = "short",  // shorter than the current overlay
            partType = "text",
            pId = "p1",
            msgId = "m1",
            sessionId = "s1"
        )

        assertEquals("short", next.streamingPartTexts["p1"])
    }

    @Test
    fun `applyPartFullTextLeadingEdge sets streamingReasoningPart for reasoning`() {
        val state = ChatState()

        val next = state.applyPartFullTextLeadingEdge(
            partId = "p1",
            fullText = "thinking",
            partType = "reasoning",
            pId = "p1",
            msgId = "m1",
            sessionId = "s1"
        )

        assertEquals("thinking", next.streamingPartTexts["p1"])
        assertEquals("p1", next.streamingReasoningPart?.id)
    }

    // ── coalesce buffer appends / replaces ─────────────────────────────────

    @Test
    fun `appendDeltaBuffer accumulates across multiple appends`() {
        val state = ChatState()

        val next = state
            .appendDeltaBuffer("p1", "Hello")
            .appendDeltaBuffer("p1", ", ")
            .appendDeltaBuffer("p1", "world!")

        assertEquals("Hello, world!", next.deltaBuffer["p1"])
    }

    @Test
    fun `appendDeltaBuffer keeps per-partId buffers independent`() {
        val state = ChatState()

        val next = state
            .appendDeltaBuffer("p1", "A")
            .appendDeltaBuffer("p2", "B")
            .appendDeltaBuffer("p1", "A2")

        assertEquals("AA2", next.deltaBuffer["p1"])
        assertEquals("B", next.deltaBuffer["p2"])
    }

    @Test
    fun `replaceFullTextBuffer keeps only the latest value per partId`() {
        val state = ChatState()

        val next = state
            .replaceFullTextBuffer("p1", "first")
            .replaceFullTextBuffer("p1", "second")
            .replaceFullTextBuffer("p1", "third")

        assertEquals("third", next.fullTextBuffer["p1"])
    }

    @Test
    fun `markFlushPending adds partId to the pending set`() {
        val state = ChatState()

        val next = state
            .markFlushPending("p1")
            .markFlushPending("p2")

        assertEquals(setOf("p1", "p2"), next.pendingFlushPartIds)
    }

    @Test
    fun `markFlushPending is idempotent`() {
        val state = ChatState()

        val next = state.markFlushPending("p1").markFlushPending("p1")

        assertEquals(setOf("p1"), next.pendingFlushPartIds)
    }

    @Test
    fun `isFlushPending reflects pendingFlushPartIds membership`() {
        val state = ChatState().markFlushPending("p1")

        assertTrue(state.isFlushPending("p1"))
        assertFalse(state.isFlushPending("p2"))
    }

    // ── flushCoalesceBufferForPart: REPLACE / APPEND / drop paths ──────────

    @Test
    fun `flushCoalesceBufferForPart applies buffered fullText as REPLACE`() {
        val state = ChatState(
            streamingPartTexts = mapOf("p1" to "partial"),
            fullTextBuffer = mapOf("p1" to "AUTHORITATIVE"),
            deltaBuffer = mapOf("p1" to " STALE"),
            pendingFlushPartIds = setOf("p1")
        )

        val next = state.flushCoalesceBufferForPart("p1")

        // fullText wins (REPLACE), stale delta is dropped.
        assertEquals("AUTHORITATIVE", next.streamingPartTexts["p1"])
        assertNull(next.deltaBuffer["p1"])
        assertNull(next.fullTextBuffer["p1"])
        assertFalse(next.pendingFlushPartIds.contains("p1"))
    }

    @Test
    fun `flushCoalesceBufferForPart appends buffered delta when no fullText buffered`() {
        val state = ChatState(
            streamingPartTexts = mapOf("p1" to "partial"),
            deltaBuffer = mapOf("p1" to " MORE"),
            pendingFlushPartIds = setOf("p1")
        )

        val next = state.flushCoalesceBufferForPart("p1")

        assertEquals("partial MORE", next.streamingPartTexts["p1"])
        assertNull(next.deltaBuffer["p1"])
        assertFalse(next.pendingFlushPartIds.contains("p1"))
    }

    @Test
    fun `flushCoalesceBufferForPart drops buffers when overlay was wiped mid-window`() {
        // §session switch while flush job pending: streamingPartTexts was
        // wiped (the entry is absent); the buffered text is stale and must
        // NOT be re-injected (would render ghost text from the previous
        // session).
        val state = ChatState(
            streamingPartTexts = emptyMap(),  // overlay wiped
            deltaBuffer = mapOf("p1" to "ghost"),
            fullTextBuffer = mapOf("p1" to "ghostFull"),
            pendingFlushPartIds = setOf("p1")
        )

        val next = state.flushCoalesceBufferForPart("p1")

        assertNull(next.streamingPartTexts["p1"])
        assertNull(next.deltaBuffer["p1"])
        assertNull(next.fullTextBuffer["p1"])
        assertFalse(next.pendingFlushPartIds.contains("p1"))
    }

    @Test
    fun `flushCoalesceBufferForPart clears state when nothing buffered`() {
        val state = ChatState(
            streamingPartTexts = mapOf("p1" to "hello"),
            pendingFlushPartIds = setOf("p1")
        )

        val next = state.flushCoalesceBufferForPart("p1")

        // Overlay is untouched; only the pending bookkeeping is cleared.
        assertEquals("hello", next.streamingPartTexts["p1"])
        assertFalse(next.pendingFlushPartIds.contains("p1"))
    }

    @Test
    fun `flushCoalesceBufferForPart does not touch other partIds`() {
        val state = ChatState(
            streamingPartTexts = mapOf("p1" to "a", "p2" to "b"),
            deltaBuffer = mapOf("p1" to "1", "p2" to "2"),
            pendingFlushPartIds = setOf("p1", "p2")
        )

        val next = state.flushCoalesceBufferForPart("p1")

        // p1 flushed (delta appended), p2 untouched.
        assertEquals("a1", next.streamingPartTexts["p1"])
        assertEquals("b", next.streamingPartTexts["p2"])
        assertEquals("2", next.deltaBuffer["p2"])
        assertTrue(next.pendingFlushPartIds.contains("p2"))
        assertFalse(next.pendingFlushPartIds.contains("p1"))
    }

    @Test
    fun `flushCoalesceBufferForPart prefers shorter fullText over longer delta`() {
        // §fullText shorter / divergent: fullText is server-authoritative,
        // so a SHORTER fullText still wins over a longer delta accumulation.
        val state = ChatState(
            streamingPartTexts = mapOf("p1" to "x"),
            fullTextBuffer = mapOf("p1" to "short"),
            deltaBuffer = mapOf("p1" to " this is a much longer stale delta"),
            pendingFlushPartIds = setOf("p1")
        )

        val next = state.flushCoalesceBufferForPart("p1")

        assertEquals("short", next.streamingPartTexts["p1"])
    }

    @Test
    fun `flushCoalesceBufferForPart with empty delta string clears state without append`() {
        val state = ChatState(
            streamingPartTexts = mapOf("p1" to "preserved"),
            deltaBuffer = mapOf("p1" to ""),  // empty string
            pendingFlushPartIds = setOf("p1")
        )

        val next = state.flushCoalesceBufferForPart("p1")

        assertEquals("preserved", next.streamingPartTexts["p1"])
        assertNull(next.deltaBuffer["p1"])
        assertFalse(next.pendingFlushPartIds.contains("p1"))
    }

    // ── clearCoalesceBufferForPart / clearAllCoalesceBuffers ───────────────

    @Test
    fun `clearCoalesceBufferForPart drops only the targeted partId`() {
        val state = ChatState(
            deltaBuffer = mapOf("p1" to "a", "p2" to "b"),
            fullTextBuffer = mapOf("p1" to "x", "p2" to "y"),
            pendingFlushPartIds = setOf("p1", "p2")
        )

        val next = state.clearCoalesceBufferForPart("p1")

        assertNull(next.deltaBuffer["p1"])
        assertEquals("b", next.deltaBuffer["p2"])
        assertNull(next.fullTextBuffer["p1"])
        assertEquals("y", next.fullTextBuffer["p2"])
        assertEquals(setOf("p2"), next.pendingFlushPartIds)
    }

    @Test
    fun `clearAllCoalesceBuffers wipes every buffer but preserves overlay`() {
        // 闪屏修复 contract: clearDeltaBuffers drops pending buffers but
        // does NOT wipe streamingPartTexts / streamingReasoningPart — those
        // are reconciled by the subsequent authoritative reload.
        val state = ChatState(
            streamingPartTexts = mapOf("p1" to "preserved"),
            streamingReasoningPart = Part(id = "p1", messageId = "m1", sessionId = "s1", type = "reasoning"),
            deltaBuffer = mapOf("p1" to "buffered"),
            fullTextBuffer = mapOf("p1" to "fullText"),
            pendingFlushPartIds = setOf("p1")
        )

        val next = state.clearAllCoalesceBuffers()

        assertEquals("preserved", next.streamingPartTexts["p1"])
        assertNotNull(next.streamingReasoningPart)
        assertTrue(next.deltaBuffer.isEmpty())
        assertTrue(next.fullTextBuffer.isEmpty())
        assertTrue(next.pendingFlushPartIds.isEmpty())
    }

    // ── UnreadState.applyMarkSessionUnread ─────────────────────────────────

    @Test
    fun `applyMarkSessionUnread adds the id when not current`() {
        val state = UnreadState(unreadSessions = emptySet())

        val next = state.applyMarkSessionUnread("s1", currentSessionId = "s2")

        assertEquals(setOf("s1"), next.unreadSessions)
    }

    @Test
    fun `applyMarkSessionUnread is a no-op when the session is current`() {
        val state = UnreadState(unreadSessions = setOf("other"))

        val next = state.applyMarkSessionUnread("s1", currentSessionId = "s1")

        assertFalse(next.unreadSessions.contains("s1"))
        assertEquals(setOf("other"), next.unreadSessions)
    }

    @Test
    fun `applyMarkSessionUnread with null currentSessionId always marks unread`() {
        val state = UnreadState()

        val next = state.applyMarkSessionUnread("s1", currentSessionId = null)

        assertEquals(setOf("s1"), next.unreadSessions)
    }

    // ── composite / scenario coverage ──────────────────────────────────────

    @Test
    fun `scenario busy reload then idle finalization carries overlay through`() {
        // §busy reload then idle finalization: a busy reload preserves the
        // overlay (reload gating is in MainViewModelMessageActions, NOT in
        // the pure transform — here we only verify the SSE-side state math:
        // session.status writes the badge; idle finalization reads
        // streamingPartTexts to decide reload — which still must contain
        // the leading-edge value).
        var chat = ChatState(currentSessionId = "s1")
        // busy status: badge updated
        val sessionListBusy = SessionListState()
            .applySessionStatus("s1", SessionStatus(type = "busy"))
        assertEquals(SessionStatus(type = "busy"), sessionListBusy.sessionStatuses["s1"])

        // Streaming delta leading edge: overlay non-empty
        chat = chat.applyPartDeltaLeadingEdge(
            partId = "p1", delta = "streaming", knownType = "text",
            msgId = "m1", sessionId = "s1"
        ).markFlushPending("p1")
        assertTrue(chat.streamingPartTexts.isNotEmpty())
        assertTrue(chat.streamingReasoningPart == null)

        // idle status: badge updated; reload decision is based on
        // streamingPartTexts being non-empty (still true here).
        val sessionListIdle = sessionListBusy.applySessionStatus("s1", SessionStatus(type = "idle"))
        assertEquals(SessionStatus(type = "idle"), sessionListIdle.sessionStatuses["s1"])
        assertTrue(chat.streamingPartTexts.isNotEmpty())
    }

    @Test
    fun `scenario message updated before part updated carries the new message id`() {
        // §message.updated before/after part.updated: the message.updated
        // insert-if-absent path runs FIRST, so the subsequent part.updated
        // user-part-guard correctly classifies the owning message.
        var chat = ChatState(currentSessionId = "s1")
        // message.updated for a new assistant message → inserted at tail
        chat = chat.applyMessageUpdated(Message(id = "m1", role = "assistant")).first
        assertEquals(listOf("m1"), chat.messages.map { it.id })

        // Now a part.updated for m1 — owner is NOT a user message, so the
        // guard would let it through (the guard itself is in the dispatcher,
        // but we can verify the classification here).
        val ownerIsUser = chat.messages.any { it.id == "m1" && it.isUser }
        assertFalse(ownerIsUser)
    }

    @Test
    fun `scenario part delta before placeholder seeds both atomically`() {
        // §part.delta before placeholder: leading-edge delta writes the
        // overlay AND ensures the placeholder Part in one atomic pass.
        val chat = ChatState()
            .applyPartDeltaLeadingEdge(
                partId = "p1", delta = "first", knownType = "text",
                msgId = "m1", sessionId = "s1"
            )

        assertEquals("first", chat.streamingPartTexts["p1"])
        assertEquals("text", chat.partsByMessage["m1"]?.firstOrNull { it.id == "p1" }?.type)
    }

    @Test
    fun `scenario session switch with flush pending drops buffers on next flush`() {
        // §session switch while flush job pending: the dispatcher resets
        // streamingPartTexts to empty on session switch (SessionSwitcher,
        // not in scope here). When the now-stale flush job eventually
        // fires, the wiped-overlay branch of flushCoalesceBufferForPart
        // drops the buffers (no ghost text injected into the new session).
        val chatBeforeSwitch = ChatState(
            streamingPartTexts = mapOf("p1" to "streaming"),
            deltaBuffer = mapOf("p1" to " MORE"),
            pendingFlushPartIds = setOf("p1")
        )
        // SessionSwitcher wipes streamingPartTexts (simulated here):
        val chatAfterSwitch = chatBeforeSwitch.copy(streamingPartTexts = emptyMap())

        val flushed = chatAfterSwitch.flushCoalesceBufferForPart("p1")

        assertNull(flushed.streamingPartTexts["p1"])
        assertNull(flushed.deltaBuffer["p1"])
        assertFalse(flushed.pendingFlushPartIds.contains("p1"))
    }

    @Test
    fun `scenario reconnect gap closure drops stale buffers when overlay was reset`() {
        // §reconnect gap closure: a reconnect's catch-up reload may replace
        // streamingPartTexts; any pending flush whose overlay is now absent
        // must drop its buffers (the authoritative reload's snapshot wins).
        val chat = ChatState(
            // Overlay was reset (gap closure reload reconciled the message).
            streamingPartTexts = emptyMap(),
            fullTextBuffer = mapOf("p1" to "stale authoritative"),
            deltaBuffer = mapOf("p1" to " stale delta"),
            pendingFlushPartIds = setOf("p1")
        )

        val next = chat.flushCoalesceBufferForPart("p1")

        assertNull(next.streamingPartTexts["p1"])
        assertNull(next.deltaBuffer["p1"])
        assertNull(next.fullTextBuffer["p1"])
    }
}
