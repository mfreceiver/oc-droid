package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.FileDiff
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
import cn.vectory.ocdroid.ui.isStreamablePartType
import cn.vectory.ocdroid.ui.reasoningPartOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

        val (next, _) = state.applySessionCreated(created)

        assertEquals(listOf("new", "old"), next.sessions.map { it.id })
    }

    @Test
    fun `applySessionCreated on empty list yields the sole session`() {
        val state = SessionListState()
        val created = Session(id = "solo", directory = "/x")

        val (next, _) = state.applySessionCreated(created)

        assertEquals(listOf("solo"), next.sessions.map { it.id })
    }

    @Test
    fun `session created confirmation removes pending id and registration timestamp`() {
        val state = SessionListState(
            pendingCreateIds = setOf("new", "other"),
            pendingCreatedAt = mapOf("new" to 10L, "other" to 20L),
        )

        val (next, _) = state.applySessionCreated(Session(id = "new", directory = "/tmp"))

        assertEquals(setOf("other"), next.pendingCreateIds)
        assertEquals(mapOf("other" to 20L), next.pendingCreatedAt)
    }

    @Test
    fun `session created with unresolved parent invalidates every complete root`() {
        val state = SessionListState(
            sessions = listOf(Session(id = "A", directory = "/x")),
            completeRootIds = setOf("A", "B"),
        )
        val unknownGrandchild = Session(id = "G", directory = "/x", parentId = "missing-parent")

        val (next, _) = state.applySessionCreated(unknownGrandchild)

        assertTrue(next.completeRootIds.isEmpty())
    }

    @Test
    fun `applySessionUpsert replaces an existing session id`() {
        val state = SessionListState(sessions = listOf(
            Session(id = "s1", directory = "/tmp", title = "old title")
        ))
        val updated = Session(id = "s1", directory = "/tmp", title = "new title")

        val (next, _) = state.applySessionUpsert(updated)

        assertEquals(1, next.sessions.size)
        assertEquals("new title", next.sessions[0].title)
    }

    @Test
    fun `applySessionUpsert propagates the title into directorySessions buckets via conditional replace`() {
        // §title-sync regression: SSE session.updated (parsed into applySessionUpsert)
        // must propagate the refreshed Session into BOTH stores, not just the
        // top-level `sessions` list. SessionsScreen + the chat header read the
        // UNION of `sessions` and `directorySessions`; before the fix, a session
        // that surfaces from a directory bucket kept a stale title (e.g. the
        // server's auto-generated rename) until a REST refresh replaced the
        // bucket wholesale. Mirrors applyMessageTimestampBump's two-store pattern
        // via a CONDITIONAL replace (not upsert/append) so unrelated buckets
        // don't get polluted.
        val seed = SessionListState(
            sessions = listOf(
                Session(id = "s1", directory = "/project", title = "old")
            ),
            directorySessions = mapOf(
                "/project" to listOf(Session(id = "s1", directory = "/project", title = "old")),
                // Unrelated bucket that must NOT gain s1 (proves conditional-replace,
                // not append).
                "/other" to listOf(Session(id = "s2", directory = "/other", title = "untouched"))
            )
        )
        val updated = Session(id = "s1", directory = "/project", title = "new title")

        val (next, _) = seed.applySessionUpsert(updated)

        // Top-level `sessions` keeps the replace-or-append upsert semantics.
        val topS1 = next.sessions.first { it.id == "s1" }
        assertEquals("new title", topS1.title)

        // The /project bucket reflects the refreshed title.
        val projS1 = next.directorySessions["/project"]?.firstOrNull { it.id == "s1" }
        assertNotNull("seeded directory bucket entry must still be present", projS1)
        assertEquals(
            "title-sync must propagate into directorySessions buckets",
            "new title",
            projS1!!.title
        )

        // The unrelated /other bucket is unchanged AND does NOT gain s1.
        val otherIds = next.directorySessions["/other"]?.map { it.id }
        assertEquals(
            "unrelated bucket must not gain the upserted id (conditional-replace, not append)",
            listOf("s2"),
            otherIds
        )
        val otherS2 = next.directorySessions["/other"]?.first { it.id == "s2" }
        assertEquals("untouched", otherS2?.title)
    }

    @Test
    fun `session updated confirmation removes pending id and registration timestamp`() {
        val state = SessionListState(
            pendingCreateIds = setOf("s1", "other"),
            pendingCreatedAt = mapOf("s1" to 10L, "other" to 20L),
        )

        val (next, _) = state.applySessionUpsert(Session(id = "s1", directory = "/tmp"))

        assertEquals(setOf("other"), next.pendingCreateIds)
        assertEquals(mapOf("other" to 20L), next.pendingCreatedAt)
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

        val (next, _) = state.applyArchiveEviction(archived, listOf("s1", "s3"))

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

        val (next, _) = state.applyArchivedChatClear()

        assertNull(next.currentSessionId)
        assertTrue(next.messages.isEmpty())
        assertTrue(next.partsByMessage.isEmpty())
        // streamingPartTexts is intentionally NOT cleared by the archive path
        // (matches the original dispatcher behaviour — only the chat-list /
        // parts / currentSessionId are wiped).
        assertEquals("leftover", next.streamingPartTexts["p1"])
    }

    // ── session.status: applySessionStatus ─────────────────────────────────

    @Test
    fun `applySessionStatus upserts the badge entry`() {
        val state = SessionListState(
            sessionStatuses = mapOf("s1" to SessionStatus(type = "idle"))
        )

        val (next, _) = state.applySessionStatus("s1", SessionStatus(type = "busy"))

        assertEquals(SessionStatus(type = "busy"), next.sessionStatuses["s1"])
        assertEquals(1, next.sessionStatuses.size)
    }

    @Test
    fun `applySessionStatus adds a new entry without disturbing others`() {
        val state = SessionListState(
            sessionStatuses = mapOf("s1" to SessionStatus(type = "idle"))
        )

        val (next, _) = state.applySessionStatus("s2", SessionStatus(type = "busy"))

        assertEquals(2, next.sessionStatuses.size)
        assertEquals(SessionStatus(type = "idle"), next.sessionStatuses["s1"])
        assertEquals(SessionStatus(type = "busy"), next.sessionStatuses["s2"])
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

        val (next, _) = state.applyQuestionAsked(question)

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

        val (next, _) = state.applyQuestionAsked(dup)

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

        val (next, _) = state.applyQuestionResolved("q1")

        assertEquals(listOf("q2"), next.pendingQuestions.map { it.id })
    }

    @Test
    fun `applyQuestionResolved is a no-op when the id is absent`() {
        val state = SessionListState(pendingQuestions = listOf(
            QuestionRequest(id = "q1", sessionId = "s1", questions = emptyList())
        ))

        val (next, _) = state.applyQuestionResolved("missing")

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

        val (next, _) = state.applyTodoUpdated("s1", todos)

        assertEquals(2, next.sessionTodos["s1"]?.size)
        assertEquals("new", next.sessionTodos["s1"]!![0].content)
    }

    @Test
    fun `applyTodoUpdated adds a new session entry without disturbing others`() {
        val state = SessionListState(
            sessionTodos = mapOf("s1" to listOf(TodoItem("a", "pending", "low", "t0")))
        )

        val (next, _) = state.applyTodoUpdated("s2", listOf(TodoItem("b", "pending", "low", "t1")))

        assertNotNull(next.sessionTodos["s1"])
        assertNotNull(next.sessionTodos["s2"])
    }

    // ── §issue-1(1) applySessionDiff: session file diffs ==================

    @Test
    fun `applySessionDiff overwrites the entry for the same session`() {
        val state = SessionListState(
            sessionDiffs = mapOf("s1" to listOf(FileDiff(filePath = "old.kt", additions = 1)))
        )
        val diffs = listOf(
            FileDiff(filePath = "a.kt", additions = 3, deletions = 1, status = "modified"),
            FileDiff(filePath = "b.kt", additions = 0, deletions = 2, status = "deleted")
        )

        val (next, _) = state.applySessionDiff("s1", diffs)

        assertEquals(2, next.sessionDiffs["s1"]?.size)
        assertEquals("a.kt", next.sessionDiffs["s1"]!![0].file)
    }

    @Test
    fun `applySessionDiff adds a new session entry without disturbing others`() {
        val state = SessionListState(
            sessionDiffs = mapOf("s1" to listOf(FileDiff(filePath = "x.kt", additions = 1)))
        )

        val (next, _) = state.applySessionDiff("s2", listOf(FileDiff(filePath = "y.kt", additions = 2)))

        assertNotNull(next.sessionDiffs["s1"])
        assertNotNull(next.sessionDiffs["s2"])
    }

    @Test
    fun `applySessionDiff with an empty diff list stores an empty list`() {
        val (next, _) = SessionListState().applySessionDiff("s1", emptyList())

        assertNotNull(next.sessionDiffs["s1"])
        assertTrue(next.sessionDiffs["s1"]!!.isEmpty())
    }

    // ── §issue-1(1) applySessionDiffIfAbsent: REST stale-overwrite 守卫 ────

    @Test
    fun `applySessionDiffIfAbsent writes when no prior entry exists`() {
        val (next, _) = SessionListState()
            .applySessionDiffIfAbsent("s1", listOf(FileDiff(filePath = "a.kt", additions = 1)))

        assertEquals(1, next.sessionDiffs["s1"]?.size)
    }

    @Test
    fun `applySessionDiffIfAbsent skips when an entry already exists`() {
        // 模拟 REST 在途期间 SSE 已推送：SSE 写入的新快照不得被旧 REST 结果覆盖。
        val state = SessionListState(
            sessionDiffs = mapOf("s1" to listOf(FileDiff(filePath = "fresh-from-sse.kt", additions = 9)))
        )

        val (next, _) = state.applySessionDiffIfAbsent("s1", listOf(FileDiff(filePath = "stale-from-rest.kt", additions = 1)))

        // 仍是 SSE 的值——REST 的旧结果被守卫丢弃。
        assertEquals(1, next.sessionDiffs["s1"]?.size)
        assertEquals("fresh-from-sse.kt", next.sessionDiffs["s1"]!![0].file)
    }

    // ── part.updated placeholder / leading-edge writes ─────────────────────

    @Test
    fun `applyPartCreatedPlaceholder injects a reasoning part and sets streamingReasoningPart`() {
        val state = ChatState()

        val (next, _) = state.applyPartCreatedPlaceholder(
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

        val (next, _) = state.applyPartCreatedPlaceholder(
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

        val (first, _) = state.applyPartCreatedPlaceholder("reasoning", "p1", "m1", "s1")
        val (second, _) = first.applyPartCreatedPlaceholder("reasoning", "p1", "m1", "s1")

        assertEquals(1, second.partsByMessage["m1"]?.count { it.id == "p1" })
    }

    @Test
    fun `applyPartDeltaLeadingEdge appends onto prior streamingPartTexts value`() {
        // §part.delta before placeholder: a delta may arrive before any
        // part.updated creation event — the leading edge both seeds the
        // placeholder AND writes the streaming text in one pass.
        val state = ChatState(streamingPartTexts = mapOf("p1" to "Hello"))

        val (next, _) = state.applyPartDeltaLeadingEdge(
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

        val (next, _) = state.applyPartDeltaLeadingEdge(
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

        val (next, _) = state.applyPartFullTextLeadingEdge(
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

        val (next, _) = state.applyPartFullTextLeadingEdge(
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
            .appendDeltaBuffer("p1", "Hello").first
            .appendDeltaBuffer("p1", ", ").first
            .appendDeltaBuffer("p1", "world!").first

        assertEquals("Hello, world!", next.deltaBuffer["p1"])
    }

    @Test
    fun `appendDeltaBuffer keeps per-partId buffers independent`() {
        val state = ChatState()

        val next = state
            .appendDeltaBuffer("p1", "A").first
            .appendDeltaBuffer("p2", "B").first
            .appendDeltaBuffer("p1", "A2").first

        assertEquals("AA2", next.deltaBuffer["p1"])
        assertEquals("B", next.deltaBuffer["p2"])
    }

    @Test
    fun `replaceFullTextBuffer keeps only the latest value per partId`() {
        val state = ChatState()

        val next = state
            .replaceFullTextBuffer("p1", "first").first
            .replaceFullTextBuffer("p1", "second").first
            .replaceFullTextBuffer("p1", "third").first

        assertEquals("third", next.fullTextBuffer["p1"])
    }

    @Test
    fun `markFlushPending adds partId to the pending set`() {
        val state = ChatState()

        val next = state
            .markFlushPending("p1").first
            .markFlushPending("p2").first

        assertEquals(setOf("p1", "p2"), next.pendingFlushPartIds)
    }

    @Test
    fun `markFlushPending is idempotent`() {
        val state = ChatState()

        val (next, _) = state.markFlushPending("p1").first.markFlushPending("p1")

        assertEquals(setOf("p1"), next.pendingFlushPartIds)
    }

    @Test
    fun `isFlushPending reflects pendingFlushPartIds membership`() {
        val state = ChatState().markFlushPending("p1").first

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

        val (next, _) = state.flushCoalesceBufferForPart("p1")

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

        val (next, _) = state.flushCoalesceBufferForPart("p1")

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

        val (next, _) = state.flushCoalesceBufferForPart("p1")

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

        val (next, _) = state.flushCoalesceBufferForPart("p1")

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

        val (next, _) = state.flushCoalesceBufferForPart("p1")

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

        val (next, _) = state.flushCoalesceBufferForPart("p1")

        assertEquals("short", next.streamingPartTexts["p1"])
    }

    @Test
    fun `flushCoalesceBufferForPart with empty delta string clears state without append`() {
        val state = ChatState(
            streamingPartTexts = mapOf("p1" to "preserved"),
            deltaBuffer = mapOf("p1" to ""),  // empty string
            pendingFlushPartIds = setOf("p1")
        )

        val (next, _) = state.flushCoalesceBufferForPart("p1")

        assertEquals("preserved", next.streamingPartTexts["p1"])
        assertNull(next.deltaBuffer["p1"])
        assertFalse(next.pendingFlushPartIds.contains("p1"))
    }

    @Test
    fun `flushCoalesceBufferForPart drops stale fullText when overlay wiped and no delta buffered`() {
        // overlay absent + fullText buffered + delta absent → fullText is stale
        // (overlay was wiped) and is dropped; returns early without append.
        val state = ChatState(
            streamingPartTexts = emptyMap(),
            fullTextBuffer = mapOf("p1" to "stale-full"),
            deltaBuffer = emptyMap(),
            pendingFlushPartIds = setOf("p1")
        )

        val (next, _) = state.flushCoalesceBufferForPart("p1")

        assertNull(next.streamingPartTexts["p1"])
        assertNull(next.fullTextBuffer["p1"])
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

        val (next, _) = state.clearCoalesceBufferForPart("p1")

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

        val (next, _) = state.clearAllCoalesceBuffers()

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

        val (next, _) = state.applyMarkSessionUnread("s1", currentSessionId = "s2")

        assertEquals(setOf("s1"), next.unreadSessions)
    }

    @Test
    fun `applyMarkSessionUnread is a no-op when the session is current`() {
        val state = UnreadState(unreadSessions = setOf("other"))

        val (next, _) = state.applyMarkSessionUnread("s1", currentSessionId = "s1")

        assertFalse(next.unreadSessions.contains("s1"))
        assertEquals(setOf("other"), next.unreadSessions)
    }

    @Test
    fun `applyMarkSessionUnread with null currentSessionId always marks unread`() {
        val state = UnreadState()

        val (next, _) = state.applyMarkSessionUnread("s1", currentSessionId = null)

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
        val (sessionListBusy, _) = SessionListState()
            .applySessionStatus("s1", SessionStatus(type = "busy"))
        assertEquals(SessionStatus(type = "busy"), sessionListBusy.sessionStatuses["s1"])

        // Streaming delta leading edge: overlay non-empty
        chat = chat.applyPartDeltaLeadingEdge(
            partId = "p1", delta = "streaming", knownType = "text",
            msgId = "m1", sessionId = "s1"
        ).first.markFlushPending("p1").first
        assertTrue(chat.streamingPartTexts.isNotEmpty())
        assertTrue(chat.streamingReasoningPart == null)

        // idle status: badge updated; reload decision is based on
        // streamingPartTexts being non-empty (still true here).
        val (sessionListIdle, _) = sessionListBusy.applySessionStatus("s1", SessionStatus(type = "idle"))
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
            ).first

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

        val (flushed, _) = chatAfterSwitch.flushCoalesceBufferForPart("p1")

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

        val (next, _) = chat.flushCoalesceBufferForPart("p1")

        assertNull(next.streamingPartTexts["p1"])
        assertNull(next.deltaBuffer["p1"])
        assertNull(next.fullTextBuffer["p1"])
    }

    // ── R18 Phase 5: expanded edge-case / branch coverage ─────────────────
    // The 49 table tests above nail the happy paths + the four reviewed
    // composite scenarios. This block exhausts the remaining `when` branches
    // and boundary inputs (null/empty, every partType, every status type,
    // idempotency, type-guarded injection, …) so the pure transforms reach
    // near-full branch coverage without dragging in Android framework or
    // coroutine state.

    // === applyMessageUpdated: position / replacement edge cases ============

    @Test
    fun `applyMessageUpdated patching the first element preserves tail order`() {
        val state = ChatState(messages = listOf(
            Message(id = "a", role = "user"),
            Message(id = "b", role = "assistant"),
            Message(id = "c", role = "user")
        ))
        val updated = Message(id = "a", role = "user", cost = 1.0)

        val (next, found) = state.applyMessageUpdated(updated)

        assertTrue(found)
        assertEquals(listOf("a", "b", "c"), next.messages.map { it.id })
        assertEquals(1.0, next.messages.first { it.id == "a" }.cost!!, 1e-9)
    }

    @Test
    fun `applyMessageUpdated patching the last element preserves head order`() {
        val state = ChatState(messages = listOf(
            Message(id = "a", role = "user"),
            Message(id = "b", role = "assistant"),
            Message(id = "c", role = "user")
        ))
        val updated = Message(id = "c", role = "user", cost = 2.0)

        val (next, found) = state.applyMessageUpdated(updated)

        assertTrue(found)
        assertEquals(listOf("a", "b", "c"), next.messages.map { it.id })
    }

    @Test
    fun `applyMessageUpdated on single-element list replaces in place`() {
        val state = ChatState(messages = listOf(Message(id = "only", role = "user")))
        val updated = Message(id = "only", role = "user", cost = 9.9)

        val (next, found) = state.applyMessageUpdated(updated)

        assertTrue(found)
        assertEquals(1, next.messages.size)
        assertEquals(9.9, next.messages[0].cost!!, 1e-9)
    }

    @Test
    fun `applyMessageUpdated swaps the entire message instance no field merge`() {
        // The transform is patch-by-replacement, NOT field merge: any field
        // absent on `updated` is absent on the result. Verified by cost:
        val state = ChatState(messages = listOf(
            Message(id = "m1", role = "user", cost = 5.0)
        ))
        val updated = Message(id = "m1", role = "user") // no cost

        val (next, found) = state.applyMessageUpdated(updated)

        assertTrue(found)
        assertNull(next.messages[0].cost)
    }

    @Test
    fun `applyMessageUpdated can change the role of an existing id`() {
        val state = ChatState(messages = listOf(
            Message(id = "m1", role = "user")
        ))
        val updated = Message(id = "m1", role = "assistant")

        val (next, found) = state.applyMessageUpdated(updated)

        assertTrue(found)
        assertEquals("assistant", next.messages[0].role)
        assertTrue(next.messages[0].isAssistant)
    }

    @Test
    fun `applyMessageUpdated preserves sibling messages untouched on insert`() {
        val state = ChatState(messages = listOf(
            Message(id = "a", role = "user", cost = 1.0),
            Message(id = "b", role = "assistant", cost = 2.0)
        ))
        val inserted = Message(id = "c", role = "user", cost = 3.0)

        val (next, found) = state.applyMessageUpdated(inserted)

        assertFalse(found)
        assertEquals(3, next.messages.size)
        assertEquals(1.0, next.messages[0].cost!!, 1e-9)
        assertEquals(2.0, next.messages[1].cost!!, 1e-9)
        assertEquals(3.0, next.messages[2].cost!!, 1e-9)
    }

    @Test
    fun `applyMessageUpdated preserves message order across a larger list`() {
        val state = ChatState(messages = (1..6).map {
            Message(id = "m$it", role = if (it % 2 == 0) "assistant" else "user")
        })
        val updated = Message(id = "m4", role = "assistant", cost = 0.1)

        val (next, found) = state.applyMessageUpdated(updated)

        assertTrue(found)
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5", "m6"), next.messages.map { it.id })
    }

    // === applySessionCreated: upsert semantics ============================

    @Test
    fun `applySessionCreated on duplicate id demotes the prior occurrence`() {
        // upsertSession prepends the new occurrence and filters out any prior
        // entry with the same id — so a re-created session lifts to the head.
        val state = SessionListState(sessions = listOf(
            Session(id = "old", directory = "/old"),
            Session(id = "s1", directory = "/first"),
            Session(id = "other", directory = "/other")
        ))
        val reCreated = Session(id = "s1", directory = "/first", title = "refreshed")

        val (next, _) = state.applySessionCreated(reCreated)

        assertEquals(listOf("s1", "old", "other"), next.sessions.map { it.id })
        assertEquals("refreshed", next.sessions[0].title)
    }

    @Test
    fun `applySessionCreated preserves title and directory of unrelated sessions`() {
        val state = SessionListState(sessions = listOf(
            Session(id = "a", directory = "/dir-a", title = "Title A")
        ))
        val created = Session(id = "b", directory = "/dir-b", title = "Title B")

        val (next, _) = state.applySessionCreated(created)

        assertEquals(2, next.sessions.size)
        val original = next.sessions.first { it.id == "a" }
        assertEquals("/dir-a", original.directory)
        assertEquals("Title A", original.title)
    }

    @Test
    fun `applySessionCreated with archived-time session still prepends`() {
        val state = SessionListState(sessions = listOf(
            Session(id = "a", directory = "/a")
        ))
        val created = Session(
            id = "arch",
            directory = "/arch",
            time = Session.TimeInfo(archived = 1_700_000_000_000)
        )

        val (next, _) = state.applySessionCreated(created)

        assertEquals(listOf("arch", "a"), next.sessions.map { it.id })
        assertTrue(next.sessions[0].isArchived)
    }

    @Test
    fun `applySessionCreated is idempotent under repeated creates`() {
        val created = Session(id = "solo", directory = "/x", title = "T")
        val state = SessionListState()

        val (next, _) = state.applySessionCreated(created).first.applySessionCreated(created)

        assertEquals(1, next.sessions.size)
        assertEquals("solo", next.sessions[0].id)
    }

    @Test
    fun `applySessionCreated prepends to a multi-session list preserving order`() {
        val state = SessionListState(sessions = listOf(
            Session(id = "a", directory = "/a"),
            Session(id = "b", directory = "/b"),
            Session(id = "c", directory = "/c")
        ))
        val created = Session(id = "new", directory = "/new")

        val (next, _) = state.applySessionCreated(created)

        assertEquals(listOf("new", "a", "b", "c"), next.sessions.map { it.id })
    }

    // === applySessionUpsert: prepend / replace ===========================

    @Test
    fun `applySessionUpsert prepends a brand-new session id`() {
        val state = SessionListState(sessions = listOf(
            Session(id = "old", directory = "/old")
        ))
        val updated = Session(id = "new", directory = "/new")

        val (next, _) = state.applySessionUpsert(updated)

        assertEquals(listOf("new", "old"), next.sessions.map { it.id })
    }

    @Test
    fun `applySessionUpsert on empty list yields the sole session`() {
        val state = SessionListState()
        val updated = Session(id = "solo", directory = "/x")

        val (next, _) = state.applySessionUpsert(updated)

        assertEquals(listOf("solo"), next.sessions.map { it.id })
    }

    @Test
    fun `applySessionUpsert demotes a matching id from middle of the list`() {
        // Upsert always prepends; a matching id in the middle is filtered out
        // and lifted to the head. This is intentional (MRU-style ordering).
        val state = SessionListState(sessions = listOf(
            Session(id = "a", directory = "/a"),
            Session(id = "b", directory = "/b"),
            Session(id = "c", directory = "/c")
        ))
        val updated = Session(id = "b", directory = "/b", title = "updated")

        val (next, _) = state.applySessionUpsert(updated)

        assertEquals(listOf("b", "a", "c"), next.sessions.map { it.id })
        assertEquals("updated", next.sessions[0].title)
    }

    @Test
    fun `applySessionUpsert preserves non-id fields on the replacement`() {
        val state = SessionListState(sessions = listOf(
            Session(id = "s1", directory = "/old-dir", title = "old")
        ))
        val updated = Session(
            id = "s1", directory = "/new-dir", title = "new",
            version = "2.0"
        )

        val (next, _) = state.applySessionUpsert(updated)

        assertEquals(1, next.sessions.size)
        assertEquals("/new-dir", next.sessions[0].directory)
        assertEquals("new", next.sessions[0].title)
        assertEquals("2.0", next.sessions[0].version)
    }

    @Test
    fun `applySessionUpsert is idempotent under repeated upserts`() {
        val updated = Session(id = "x", directory = "/x", title = "T")
        val state = SessionListState(sessions = listOf(
            Session(id = "y", directory = "/y")
        ))

        val (next, _) = state.applySessionUpsert(updated).first.applySessionUpsert(updated)

        assertEquals(listOf("x", "y"), next.sessions.map { it.id })
    }

    // === applyArchiveEviction: openSessionIds rewrite ====================

    @Test
    fun `applyArchiveEviction when archived id is absent from openSessionIds`() {
        val state = SessionListState(
            sessions = listOf(Session(id = "s1", directory = "/tmp")),
            openSessionIds = listOf("s1", "s3")
        )
        val archived = Session(
            id = "sX", directory = "/tmp",
            time = Session.TimeInfo(archived = 1L)
        )

        val (next, _) = state.applyArchiveEviction(archived, listOf("s1", "s3"))

        // openSessionIds unchanged (sX was never open); session upserted.
        assertEquals(listOf("s1", "s3"), next.openSessionIds)
        assertTrue(next.sessions.any { it.id == "sX" })
    }

    @Test
    fun `applyArchiveEviction with empty newOpenIds yields empty list`() {
        val state = SessionListState(
            sessions = emptyList(),
            openSessionIds = listOf("s1")
        )
        val archived = Session(
            id = "s1", directory = "/tmp",
            time = Session.TimeInfo(archived = 1L)
        )

        val (next, _) = state.applyArchiveEviction(archived, emptyList())

        assertTrue(next.openSessionIds.isEmpty())
    }

    @Test
    fun `applyArchiveEviction preserves the archived session metadata`() {
        val state = SessionListState()
        val archived = Session(
            id = "s2", directory = "/tmp", title = "Got archived",
            time = Session.TimeInfo(archived = 1_700_000_000_000L)
        )

        val (next, _) = state.applyArchiveEviction(archived, emptyList())

        val upserted = next.sessions.first { it.id == "s2" }
        assertEquals("Got archived", upserted.title)
        assertTrue(upserted.isArchived)
    }

    @Test
    fun `applyArchiveEviction prepends the archived session to the list`() {
        val state = SessionListState(
            sessions = listOf(Session(id = "keep", directory = "/k"))
        )
        val archived = Session(
            id = "arc", directory = "/a",
            time = Session.TimeInfo(archived = 1L)
        )

        val (next, _) = state.applyArchiveEviction(archived, listOf("keep"))

        assertEquals(listOf("arc", "keep"), next.sessions.map { it.id })
    }

    // === applyArchivedChatClear: selective wipe ==========================

    @Test
    fun `applyArchivedChatClear with null currentSessionId leaves it null`() {
        val state = ChatState(
            currentSessionId = null,
            messages = listOf(Message(id = "m1", role = "user"))
        )

        val (next, _) = state.applyArchivedChatClear()

        assertNull(next.currentSessionId)
        assertTrue(next.messages.isEmpty())
    }

    @Test
    fun `applyArchivedChatClear on already-empty state is a no-op for the wiped fields`() {
        val state = ChatState()

        val (next, _) = state.applyArchivedChatClear()

        assertNull(next.currentSessionId)
        assertTrue(next.messages.isEmpty())
        assertTrue(next.partsByMessage.isEmpty())
    }

    @Test
    fun `applyArchivedChatClear preserves streamingReasoningPart and buffers`() {
        val state = ChatState(
            currentSessionId = "s1",
            messages = listOf(Message(id = "m1", role = "user")),
            streamingReasoningPart = Part(id = "p1", messageId = "m1", sessionId = "s1", type = "reasoning"),
            deltaBuffer = mapOf("p1" to "buf"),
            pendingFlushPartIds = setOf("p1")
        )

        val (next, _) = state.applyArchivedChatClear()

        // Only currentSessionId / messages / partsByMessage are wiped.
        assertNotNull(next.streamingReasoningPart)
        assertEquals("buf", next.deltaBuffer["p1"])
        assertTrue(next.pendingFlushPartIds.contains("p1"))
    }

    // FIX-B (review-blocker, groker B3) + §Wave5b-Q13: applyArchivedChatClear
    // must ALSO wipe the unified scroll slot + parent-return backstack so a
    // pending scroll / checkpoint for an archived session does not survive
    // (the session id it references is being cleared). clearSessionData
    // already clears them, but the archive-clear path was missed under the
    // pre-Wave5b design.

    @Test
    fun `FIX-B applyArchivedChatClear wipes pendingScrollRequest + parentReturnCheckpoints`() {
        val state = ChatState(
            currentSessionId = "s1",
            messages = listOf(Message(id = "m1", role = "user")),
            pendingScrollRequest = cn.vectory.ocdroid.ui.PendingScrollRequest(
                requestId = 7L,
                targetSessionId = "s1",
                behavior = cn.vectory.ocdroid.ui.ScrollBehavior.Latest,
            ),
            parentReturnCheckpoints = mapOf(
                "s1" to cn.vectory.ocdroid.ui.ScrollCheckpoint(anchorKey = null, fallbackIndex = 0, offset = 0),
            ),
        )

        val (next, _) = state.applyArchivedChatClear()

        assertNull(
            "FIX-B / §Wave5b-Q13: pendingScrollRequest must be wiped so the intent does not survive archive",
            next.pendingScrollRequest,
        )
        assertTrue(
            "FIX-B / §Wave5b-Q13: parentReturnCheckpoints must be wiped",
            next.parentReturnCheckpoints.isEmpty(),
        )
        assertNull(next.currentSessionId)
        assertTrue(next.messages.isEmpty())
    }

    @Test
    fun `FIX-B applyArchivedChatClear wipes pendingScrollRequest + parentReturnCheckpoints even when already empty`() {
        val state = ChatState(
            currentSessionId = "s1",
            pendingScrollRequest = null,
            parentReturnCheckpoints = emptyMap(),
        )

        val (next, _) = state.applyArchivedChatClear()

        assertNull(next.pendingScrollRequest)
        assertTrue(next.parentReturnCheckpoints.isEmpty())
    }

    // === applySessionStatus: every status type branch ====================

    @Test
    fun `applySessionStatus with idle type`() {
        val (next, _) = SessionListState().applySessionStatus("s1", SessionStatus(type = "idle"))
        assertEquals("idle", next.sessionStatuses["s1"]?.type)
        assertTrue(next.sessionStatuses["s1"]?.isIdle == true)
    }

    @Test
    fun `applySessionStatus with busy type`() {
        val (next, _) = SessionListState().applySessionStatus("s1", SessionStatus(type = "busy"))
        assertTrue(next.sessionStatuses["s1"]?.isBusy == true)
    }

    @Test
    fun `applySessionStatus with retry type`() {
        val (next, _) = SessionListState().applySessionStatus("s1", SessionStatus(type = "retry"))
        assertTrue(next.sessionStatuses["s1"]?.isRetry == true)
    }

    @Test
    fun `applySessionStatus with custom type is stored verbatim`() {
        // Types beyond idle/busy/retry are stored as-is (no isXxx helper but
        // the entry is preserved for any future UI consumer).
        val (next, _) = SessionListState().applySessionStatus("s1", SessionStatus(type = "error"))
        assertEquals("error", next.sessionStatuses["s1"]?.type)
    }

    @Test
    fun `applySessionStatus preserves attempt message and next fields`() {
        val status = SessionStatus(type = "retry", attempt = 3, message = "backoff", next = 1_700L)
        val (next, _) = SessionListState().applySessionStatus("s1", status)

        assertEquals(status, next.sessionStatuses["s1"])
        assertEquals(3, next.sessionStatuses["s1"]?.attempt)
        assertEquals("backoff", next.sessionStatuses["s1"]?.message)
        assertEquals(1_700L, next.sessionStatuses["s1"]?.next)
    }

    @Test
    fun `applySessionStatus overwriting the same id multiple times keeps only the latest`() {
        val state = SessionListState()

        val next = state
            .applySessionStatus("s1", SessionStatus(type = "idle")).first
            .applySessionStatus("s1", SessionStatus(type = "busy")).first
            .applySessionStatus("s1", SessionStatus(type = "idle")).first

        assertEquals(1, next.sessionStatuses.size)
        assertEquals("idle", next.sessionStatuses["s1"]?.type)
    }

    // === applyPartCreatedPlaceholder: every partType branch ==============

    @Test
    fun `applyPartCreatedPlaceholder for tool type injects a tool part and skips streamingReasoningPart`() {
        val state = ChatState()

        val (next, _) = state.applyPartCreatedPlaceholder(
            partType = "tool", partId = "p1", msgId = "m1", sessionId = "s1"
        )

        assertEquals("tool", next.partsByMessage["m1"]?.firstOrNull { it.id == "p1" }?.type)
        assertNull(next.streamingReasoningPart)
    }

    @Test
    fun `applyPartCreatedPlaceholder for patch type injects a patch part`() {
        val (next, _) = ChatState().applyPartCreatedPlaceholder(
            partType = "patch", partId = "p1", msgId = "m1", sessionId = "s1"
        )
        assertEquals("patch", next.partsByMessage["m1"]?.firstOrNull { it.id == "p1" }?.type)
        assertNull(next.streamingReasoningPart)
    }

    @Test
    fun `applyPartCreatedPlaceholder for file type injects a file part`() {
        val (next, _) = ChatState().applyPartCreatedPlaceholder(
            partType = "file", partId = "p1", msgId = "m1", sessionId = "s1"
        )
        assertEquals("file", next.partsByMessage["m1"]?.firstOrNull { it.id == "p1" }?.type)
    }

    @Test
    fun `applyPartCreatedPlaceholder preserves an unrelated existing part under the same msgId`() {
        val state = ChatState(partsByMessage = mapOf(
            "m1" to listOf(Part(id = "other", messageId = "m1", sessionId = "s1", type = "text"))
        ))

        val (next, _) = state.applyPartCreatedPlaceholder(
            partType = "reasoning", partId = "p1", msgId = "m1", sessionId = "s1"
        )

        val parts = next.partsByMessage["m1"]!!
        assertEquals(2, parts.size)
        assertTrue(parts.any { it.id == "other" && it.type == "text" })
        assertTrue(parts.any { it.id == "p1" && it.type == "reasoning" })
    }

    @Test
    fun `applyPartCreatedPlaceholder filters out a stale same-id placeholder before re-injecting`() {
        // Defensive: if a wrong-typed placeholder for partId already exists,
        // it is removed and re-added with the correct type.
        val state = ChatState(partsByMessage = mapOf(
            "m1" to listOf(Part(id = "p1", messageId = "m1", sessionId = "s1", type = "text"))
        ))

        val (next, _) = state.applyPartCreatedPlaceholder(
            partType = "reasoning", partId = "p1", msgId = "m1", sessionId = "s1"
        )

        val parts = next.partsByMessage["m1"]!!
        assertEquals(1, parts.size)
        assertEquals("reasoning", parts[0].type)
        assertEquals("p1", next.streamingReasoningPart?.id)
    }

    @Test
    fun `applyPartCreatedPlaceholder for reasoning does not overwrite a pre-existing streamingReasoningPart for a different partId`() {
        // reasoningPartOrNull(...) returns a fresh Part for "reasoning", so
        // the `?: streamingReasoningPart` fallback is NOT taken — the new
        // value wins. Verified explicitly:
        val state = ChatState(
            streamingReasoningPart = Part(id = "prior", messageId = "m0", sessionId = "s1", type = "reasoning")
        )

        val (next, _) = state.applyPartCreatedPlaceholder(
            partType = "reasoning", partId = "p1", msgId = "m1", sessionId = "s1"
        )

        assertEquals("p1", next.streamingReasoningPart?.id)
    }

    @Test
    fun `applyPartCreatedPlaceholder for text preserves a pre-existing streamingReasoningPart`() {
        // For "text", reasoningPartOrNull returns null → fallback keeps the
        // prior streamingReasoningPart untouched.
        val state = ChatState(
            streamingReasoningPart = Part(id = "prior", messageId = "m0", sessionId = "s1", type = "reasoning")
        )

        val (next, _) = state.applyPartCreatedPlaceholder(
            partType = "text", partId = "p1", msgId = "m1", sessionId = "s1"
        )

        assertEquals("prior", next.streamingReasoningPart?.id)
    }

    // === applyPartFullTextLeadingEdge: type / pId branches ===============

    @Test
    fun `applyPartFullTextLeadingEdge for tool type updates overlay but skips placeholder injection`() {
        val state = ChatState()

        val (next, _) = state.applyPartFullTextLeadingEdge(
            partId = "p1", fullText = "hello", partType = "tool",
            pId = "p1", msgId = "m1", sessionId = "s1"
        )

        // streamingPartTexts IS still updated (fullText is authoritative
        // regardless of type); ensurePlaceholderPart returns partsByMessage
        // unchanged because tool is not streamable.
        assertEquals("hello", next.streamingPartTexts["p1"])
        assertNull(next.partsByMessage["m1"])
        assertNull(next.streamingReasoningPart)
    }

    @Test
    fun `applyPartFullTextLeadingEdge for patch type does not inject placeholder`() {
        val (next, _) = ChatState().applyPartFullTextLeadingEdge(
            partId = "p1", fullText = "x", partType = "patch",
            pId = "p1", msgId = "m1", sessionId = "s1"
        )
        assertEquals("x", next.streamingPartTexts["p1"])
        assertNull(next.partsByMessage["m1"])
    }

    @Test
    fun `applyPartFullTextLeadingEdge with empty fullText stores empty string`() {
        val (next, _) = ChatState().applyPartFullTextLeadingEdge(
            partId = "p1", fullText = "", partType = "text",
            pId = "p1", msgId = "m1", sessionId = "s1"
        )
        assertEquals("", next.streamingPartTexts["p1"])
    }

    @Test
    fun `applyPartFullTextLeadingEdge with distinct pId and partId keeps both keys consistent`() {
        // partId is the streamingPartTexts key; pId is the Part id. They
        // usually match but the API permits divergence — verify both are
        // wired to the correct slots.
        val (next, _) = ChatState().applyPartFullTextLeadingEdge(
            partId = "overlay-key", fullText = "v", partType = "reasoning",
            pId = "part-uuid", msgId = "m1", sessionId = "s1"
        )
        assertEquals("v", next.streamingPartTexts["overlay-key"])
        assertEquals("part-uuid", next.streamingReasoningPart?.id)
        assertEquals("part-uuid", next.partsByMessage["m1"]?.firstOrNull { it.id == "part-uuid" }?.id)
    }

    @Test
    fun `applyPartFullTextLeadingEdge preserves existing parts under the same msgId`() {
        val state = ChatState(partsByMessage = mapOf(
            "m1" to listOf(Part(id = "other", messageId = "m1", sessionId = "s1", type = "text"))
        ))

        val (next, _) = state.applyPartFullTextLeadingEdge(
            partId = "p1", fullText = "v", partType = "text",
            pId = "p1", msgId = "m1", sessionId = "s1"
        )

        val parts = next.partsByMessage["m1"]!!
        assertEquals(2, parts.size)
        assertTrue(parts.any { it.id == "other" })
        assertTrue(parts.any { it.id == "p1" })
    }

    @Test
    fun `applyPartFullTextLeadingEdge for text does not overwrite a prior streamingReasoningPart`() {
        val state = ChatState(
            streamingReasoningPart = Part(id = "prior", messageId = "m0", sessionId = "s1", type = "reasoning")
        )

        val (next, _) = state.applyPartFullTextLeadingEdge(
            partId = "p1", fullText = "v", partType = "text",
            pId = "p1", msgId = "m1", sessionId = "s1"
        )

        assertEquals("prior", next.streamingReasoningPart?.id)
    }

    @Test
    fun `applyPartFullTextLeadingEdge overwrites a prior overlay value with the new fullText`() {
        val state = ChatState(streamingPartTexts = mapOf("p1" to "first"))

        val (next, _) = state.applyPartFullTextLeadingEdge(
            partId = "p1", fullText = "second", partType = "text",
            pId = "p1", msgId = "m1", sessionId = "s1"
        )

        assertEquals("second", next.streamingPartTexts["p1"])
    }

    @Test
    fun `applyPartFullTextLeadingEdge preserves an existing same-id part without double-inject`() {
        val state = ChatState(partsByMessage = mapOf(
            "m1" to listOf(Part(id = "p1", messageId = "m1", sessionId = "s1", type = "text"))
        ))

        val (next, _) = state.applyPartFullTextLeadingEdge(
            partId = "p1", fullText = "v", partType = "text",
            pId = "p1", msgId = "m1", sessionId = "s1"
        )

        // ensurePlaceholderPart is a no-op when pId already exists.
        assertEquals(1, next.partsByMessage["m1"]?.count { it.id == "p1" })
    }

    // === applyPartDeltaLeadingEdge (knownType variant): type branches =====

    @Test
    fun `applyPartDeltaLeadingEdge with reasoning knownType sets streamingReasoningPart`() {
        val (next, _) = ChatState().applyPartDeltaLeadingEdge(
            partId = "p1", delta = "thought", knownType = "reasoning",
            msgId = "m1", sessionId = "s1"
        )
        assertEquals("thought", next.streamingPartTexts["p1"])
        assertEquals("p1", next.streamingReasoningPart?.id)
    }

    @Test
    fun `applyPartDeltaLeadingEdge with tool knownType skips streamingReasoningPart and placeholder`() {
        val (next, _) = ChatState().applyPartDeltaLeadingEdge(
            partId = "p1", delta = "x", knownType = "tool",
            msgId = "m1", sessionId = "s1"
        )
        // Delta still accumulates into the overlay; placeholder injection
        // is gated (tool isn't streamable) and streamingReasoningPart stays
        // null.
        assertEquals("x", next.streamingPartTexts["p1"])
        assertNull(next.partsByMessage["m1"])
        assertNull(next.streamingReasoningPart)
    }

    @Test
    fun `applyPartDeltaLeadingEdge accumulates onto an existing overlay`() {
        val state = ChatState(streamingPartTexts = mapOf("p1" to "abc"))

        val (next, _) = state.applyPartDeltaLeadingEdge(
            partId = "p1", delta = "def", knownType = "text",
            msgId = "m1", sessionId = "s1"
        )

        assertEquals("abcdef", next.streamingPartTexts["p1"])
    }

    @Test
    fun `applyPartDeltaLeadingEdge chained calls accumulate in order`() {
        val next = ChatState()
            .applyPartDeltaLeadingEdge("p1", "Hello", "text", "m1", "s1").first
            .applyPartDeltaLeadingEdge("p1", ", ", "text", "m1", "s1").first
            .applyPartDeltaLeadingEdge("p1", "world", "text", "m1", "s1").first

        assertEquals("Hello, world", next.streamingPartTexts["p1"])
    }

    @Test
    fun `applyPartDeltaLeadingEdge with empty delta still creates an overlay entry`() {
        val (next, _) = ChatState().applyPartDeltaLeadingEdge(
            partId = "p1", delta = "", knownType = "text",
            msgId = "m1", sessionId = "s1"
        )
        // orEmpty() yields "" so the entry is created as an empty string.
        assertEquals("", next.streamingPartTexts["p1"])
    }

    @Test
    fun `applyPartDeltaLeadingEdge for text preserves a prior streamingReasoningPart`() {
        val state = ChatState(
            streamingReasoningPart = Part(id = "prior", messageId = "m0", sessionId = "s1", type = "reasoning")
        )

        val (next, _) = state.applyPartDeltaLeadingEdge(
            partId = "p1", delta = "x", knownType = "text",
            msgId = "m1", sessionId = "s1"
        )

        assertEquals("prior", next.streamingReasoningPart?.id)
    }

    // === applyPartDeltaLeadingEdge (partType+pId variant): branches =======

    @Test
    fun `applyPartDeltaLeadingEdge partType variant with text appends and injects placeholder`() {
        val (next, _) = ChatState().applyPartDeltaLeadingEdge(
            partId = "p1", delta = "hi", partType = "text",
            pId = "p1", msgId = "m1", sessionId = "s1"
        )
        assertEquals("hi", next.streamingPartTexts["p1"])
        assertEquals("text", next.partsByMessage["m1"]?.firstOrNull { it.id == "p1" }?.type)
        assertNull(next.streamingReasoningPart)
    }

    @Test
    fun `applyPartDeltaLeadingEdge partType variant with reasoning sets streamingReasoningPart using pId`() {
        val (next, _) = ChatState().applyPartDeltaLeadingEdge(
            partId = "overlay-key", delta = "thought", partType = "reasoning",
            pId = "uuid-1", msgId = "m1", sessionId = "s1"
        )
        assertEquals("thought", next.streamingPartTexts["overlay-key"])
        assertEquals("uuid-1", next.streamingReasoningPart?.id)
    }

    @Test
    fun `applyPartDeltaLeadingEdge partType variant with distinct pId wires placeholder to pId`() {
        val (next, _) = ChatState().applyPartDeltaLeadingEdge(
            partId = "overlay-key", delta = "v", partType = "text",
            pId = "uuid-2", msgId = "m1", sessionId = "s1"
        )
        assertEquals("v", next.streamingPartTexts["overlay-key"])
        assertEquals("uuid-2", next.partsByMessage["m1"]?.firstOrNull { it.id == "uuid-2" }?.id)
    }

    @Test
    fun `applyPartDeltaLeadingEdge partType variant accumulates onto prior overlay`() {
        val state = ChatState(streamingPartTexts = mapOf("p1" to "A"))

        val (next, _) = state.applyPartDeltaLeadingEdge(
            partId = "p1", delta = "B", partType = "text",
            pId = "p1", msgId = "m1", sessionId = "s1"
        )

        assertEquals("AB", next.streamingPartTexts["p1"])
    }

    @Test
    fun `applyPartDeltaLeadingEdge partType variant with tool skips placeholder and streamingReasoningPart`() {
        val (next, _) = ChatState().applyPartDeltaLeadingEdge(
            partId = "p1", delta = "x", partType = "tool",
            pId = "p1", msgId = "m1", sessionId = "s1"
        )
        assertEquals("x", next.streamingPartTexts["p1"])
        assertNull(next.partsByMessage["m1"])
        assertNull(next.streamingReasoningPart)
    }

    // === appendDeltaBuffer: boundary =====================================

    @Test
    fun `appendDeltaBuffer with empty delta string creates an empty-string entry`() {
        val (next, _) = ChatState().appendDeltaBuffer("p1", "")
        // The entry is created (current.orEmpty() + "" = ""); it is NOT a
        // no-op. Verified explicitly because flushCoalesceBufferForPart then
        // treats empty string as "nothing buffered".
        assertEquals("", next.deltaBuffer["p1"])
    }

    @Test
    fun `appendDeltaBuffer on a brand-new partId creates the entry`() {
        val (next, _) = ChatState().appendDeltaBuffer("p1", "first")
        assertEquals("first", next.deltaBuffer["p1"])
    }

    @Test
    fun `appendDeltaBuffer preserves unrelated partId entries`() {
        val state = ChatState(deltaBuffer = mapOf("p2" to "kept"))

        val (next, _) = state.appendDeltaBuffer("p1", "x")

        assertEquals("kept", next.deltaBuffer["p2"])
        assertEquals("x", next.deltaBuffer["p1"])
    }

    // === replaceFullTextBuffer: boundary =================================

    @Test
    fun `replaceFullTextBuffer on a brand-new partId creates the entry`() {
        val (next, _) = ChatState().replaceFullTextBuffer("p1", "v")
        assertEquals("v", next.fullTextBuffer["p1"])
    }

    @Test
    fun `replaceFullTextBuffer overwrites a prior value for the same partId`() {
        val state = ChatState(fullTextBuffer = mapOf("p1" to "old"))

        val (next, _) = state.replaceFullTextBuffer("p1", "new")

        assertEquals("new", next.fullTextBuffer["p1"])
        assertEquals(1, next.fullTextBuffer.size)
    }

    @Test
    fun `replaceFullTextBuffer does not touch deltaBuffer`() {
        val state = ChatState(deltaBuffer = mapOf("p1" to "delta"))

        val (next, _) = state.replaceFullTextBuffer("p1", "full")

        assertEquals("delta", next.deltaBuffer["p1"])
        assertEquals("full", next.fullTextBuffer["p1"])
    }

    // === markFlushPending / isFlushPending: boundary =====================

    @Test
    fun `markFlushPending preserves existing entries when adding a new partId`() {
        val state = ChatState(pendingFlushPartIds = setOf("p1"))

        val (next, _) = state.markFlushPending("p2")

        assertEquals(setOf("p1", "p2"), next.pendingFlushPartIds)
    }

    @Test
    fun `markFlushPending does not touch unrelated slice state`() {
        val state = ChatState(
            streamingPartTexts = mapOf("p1" to "v"),
            streamingReasoningPart = Part(id = "p1", messageId = "m1", sessionId = "s1", type = "reasoning")
        )

        val (next, _) = state.markFlushPending("p2")

        assertEquals("v", next.streamingPartTexts["p1"])
        assertNotNull(next.streamingReasoningPart)
    }

    @Test
    fun `markFlushPending on an empty set adds the sole entry`() {
        val (next, _) = ChatState().markFlushPending("solo")
        assertEquals(setOf("solo"), next.pendingFlushPartIds)
    }

    @Test
    fun `isFlushPending returns false for every partId when the set is empty`() {
        val state = ChatState()
        assertFalse(state.isFlushPending("p1"))
        assertFalse(state.isFlushPending("anything"))
    }

    @Test
    fun `isFlushPending reports membership independently for multiple partIds`() {
        val state = ChatState(pendingFlushPartIds = setOf("a", "b"))
        assertTrue(state.isFlushPending("a"))
        assertTrue(state.isFlushPending("b"))
        assertFalse(state.isFlushPending("c"))
    }

    // === flushCoalesceBufferForPart: extended decision matrix ============

    @Test
    fun `flushCoalesceBufferForPart REPLACE path with both buffers prefers fullText and drops delta`() {
        val state = ChatState(
            streamingPartTexts = mapOf("p1" to "partial"),
            fullTextBuffer = mapOf("p1" to "AUTHORITATIVE"),
            deltaBuffer = mapOf("p1" to " stale delta"),
            pendingFlushPartIds = setOf("p1")
        )

        val (next, _) = state.flushCoalesceBufferForPart("p1")

        assertEquals("AUTHORITATIVE", next.streamingPartTexts["p1"])
        assertNull(next.deltaBuffer["p1"])
        assertNull(next.fullTextBuffer["p1"])
    }

    @Test
    fun `flushCoalesceBufferForPart with fullText only and no delta applies REPLACE`() {
        val state = ChatState(
            streamingPartTexts = mapOf("p1" to "partial"),
            fullTextBuffer = mapOf("p1" to "FULL"),
            pendingFlushPartIds = setOf("p1")
        )

        val (next, _) = state.flushCoalesceBufferForPart("p1")

        assertEquals("FULL", next.streamingPartTexts["p1"])
        assertNull(next.fullTextBuffer["p1"])
        assertFalse(next.pendingFlushPartIds.contains("p1"))
    }

    @Test
    fun `flushCoalesceBufferForPart with delta only and no fullText applies APPEND`() {
        val state = ChatState(
            streamingPartTexts = mapOf("p1" to "head"),
            deltaBuffer = mapOf("p1" to " tail"),
            pendingFlushPartIds = setOf("p1")
        )

        val (next, _) = state.flushCoalesceBufferForPart("p1")

        assertEquals("head tail", next.streamingPartTexts["p1"])
        assertNull(next.deltaBuffer["p1"])
    }

    @Test
    fun `flushCoalesceBufferForPart with overlay wiped and both buffers drops both`() {
        val state = ChatState(
            streamingPartTexts = emptyMap(),
            fullTextBuffer = mapOf("p1" to "ghost-full"),
            deltaBuffer = mapOf("p1" to "ghost-delta"),
            pendingFlushPartIds = setOf("p1")
        )

        val (next, _) = state.flushCoalesceBufferForPart("p1")

        assertNull(next.streamingPartTexts["p1"])
        assertNull(next.fullTextBuffer["p1"])
        assertNull(next.deltaBuffer["p1"])
        assertFalse(next.pendingFlushPartIds.contains("p1"))
    }

    @Test
    fun `flushCoalesceBufferForPart with overlay wiped and fullText only drops the stale fullText`() {
        val state = ChatState(
            streamingPartTexts = emptyMap(),
            fullTextBuffer = mapOf("p1" to "stale"),
            pendingFlushPartIds = setOf("p1")
        )

        val (next, _) = state.flushCoalesceBufferForPart("p1")

        assertNull(next.streamingPartTexts["p1"])
        assertNull(next.fullTextBuffer["p1"])
    }

    @Test
    fun `flushCoalesceBufferForPart with overlay wiped and delta only drops the stale delta`() {
        val state = ChatState(
            streamingPartTexts = emptyMap(),
            deltaBuffer = mapOf("p1" to "ghost"),
            pendingFlushPartIds = setOf("p1")
        )

        val (next, _) = state.flushCoalesceBufferForPart("p1")

        assertNull(next.streamingPartTexts["p1"])
        assertNull(next.deltaBuffer["p1"])
    }

    @Test
    fun `flushCoalesceBufferForPart with overlay present and no buffers clears only the pending bookkeeping`() {
        val state = ChatState(
            streamingPartTexts = mapOf("p1" to "preserved"),
            pendingFlushPartIds = setOf("p1")
        )

        val (next, _) = state.flushCoalesceBufferForPart("p1")

        assertEquals("preserved", next.streamingPartTexts["p1"])
        assertFalse(next.pendingFlushPartIds.contains("p1"))
    }

    @Test
    fun `flushCoalesceBufferForPart flushes even when partId is not in pendingFlushPartIds`() {
        // pendingFlushPartIds is the observable mirror; the flush itself is
        // not gated by membership (the dispatcher may have already removed
        // the entry). Verified explicitly:
        val state = ChatState(
            streamingPartTexts = mapOf("p1" to "x"),
            deltaBuffer = mapOf("p1" to " y"),
            pendingFlushPartIds = emptySet()
        )

        val (next, _) = state.flushCoalesceBufferForPart("p1")

        assertEquals("x y", next.streamingPartTexts["p1"])
        assertNull(next.deltaBuffer["p1"])
    }

    @Test
    fun `flushCoalesceBufferForPart for an unknown partId with empty overlay clears bookkeeping`() {
        val state = ChatState(
            streamingPartTexts = emptyMap(),
            pendingFlushPartIds = setOf("p1")
        )

        val (next, _) = state.flushCoalesceBufferForPart("p1")

        // Overlay absent + no buffers → no overlay entry created; pending cleared.
        assertNull(next.streamingPartTexts["p1"])
        assertFalse(next.pendingFlushPartIds.contains("p1"))
    }

    @Test
    fun `flushCoalesceBufferForPart preserves every other partId entirely`() {
        val state = ChatState(
            streamingPartTexts = mapOf("p1" to "a", "p2" to "b", "p3" to "c"),
            fullTextBuffer = mapOf("p2" to "B"),
            deltaBuffer = mapOf("p3" to "DELTA"),
            pendingFlushPartIds = setOf("p1", "p2", "p3")
        )

        val (next, _) = state.flushCoalesceBufferForPart("p2")

        // p1 untouched, p2 flushed (REPLACE), p3 untouched.
        assertEquals("a", next.streamingPartTexts["p1"])
        assertEquals("B", next.streamingPartTexts["p2"])
        assertEquals("c", next.streamingPartTexts["p3"])
        assertNull(next.fullTextBuffer["p2"])
        assertEquals("DELTA", next.deltaBuffer["p3"])
        assertTrue(next.pendingFlushPartIds.contains("p1"))
        assertTrue(next.pendingFlushPartIds.contains("p3"))
        assertFalse(next.pendingFlushPartIds.contains("p2"))
    }

    // === clearCoalesceBufferForPart: boundary ============================

    @Test
    fun `clearCoalesceBufferForPart for an absent partId is a no-op on the buffers`() {
        val state = ChatState(
            deltaBuffer = mapOf("p1" to "a"),
            fullTextBuffer = mapOf("p1" to "x"),
            pendingFlushPartIds = setOf("p1")
        )

        val (next, _) = state.clearCoalesceBufferForPart("pX")

        assertEquals("a", next.deltaBuffer["p1"])
        assertEquals("x", next.fullTextBuffer["p1"])
        assertEquals(setOf("p1"), next.pendingFlushPartIds)
    }

    @Test
    fun `clearCoalesceBufferForPart preserves streamingPartTexts`() {
        val state = ChatState(streamingPartTexts = mapOf("p1" to "kept"))

        val (next, _) = state.clearCoalesceBufferForPart("p1")

        assertEquals("kept", next.streamingPartTexts["p1"])
    }

    @Test
    fun `clearCoalesceBufferForPart of the last entry yields empty maps`() {
        val state = ChatState(
            deltaBuffer = mapOf("p1" to "a"),
            fullTextBuffer = mapOf("p1" to "x"),
            pendingFlushPartIds = setOf("p1")
        )

        val (next, _) = state.clearCoalesceBufferForPart("p1")

        assertTrue(next.deltaBuffer.isEmpty())
        assertTrue(next.fullTextBuffer.isEmpty())
        assertTrue(next.pendingFlushPartIds.isEmpty())
    }

    // === clearAllCoalesceBuffers: boundary ===============================

    @Test
    fun `clearAllCoalesceBuffers on already-empty state is a no-op`() {
        val state = ChatState()

        val (next, _) = state.clearAllCoalesceBuffers()

        assertTrue(next.deltaBuffer.isEmpty())
        assertTrue(next.fullTextBuffer.isEmpty())
        assertTrue(next.pendingFlushPartIds.isEmpty())
    }

    @Test
    fun `clearAllCoalesceBuffers wipes multiple partIds at once`() {
        val state = ChatState(
            deltaBuffer = mapOf("p1" to "a", "p2" to "b", "p3" to "c"),
            fullTextBuffer = mapOf("p1" to "x", "p2" to "y"),
            pendingFlushPartIds = setOf("p1", "p2", "p3")
        )

        val (next, _) = state.clearAllCoalesceBuffers()

        assertTrue(next.deltaBuffer.isEmpty())
        assertTrue(next.fullTextBuffer.isEmpty())
        assertTrue(next.pendingFlushPartIds.isEmpty())
    }

    @Test
    fun `clearAllCoalesceBuffers preserves streamingPartTexts entries`() {
        val state = ChatState(
            streamingPartTexts = mapOf("p1" to "v1", "p2" to "v2"),
            deltaBuffer = mapOf("p1" to "buf")
        )

        val (next, _) = state.clearAllCoalesceBuffers()

        assertEquals("v1", next.streamingPartTexts["p1"])
        assertEquals("v2", next.streamingPartTexts["p2"])
    }

    // === applyQuestionAsked / applyQuestionResolved: boundary ============

    @Test
    fun `applyQuestionAsked appends multiple distinct questions in order`() {
        val state = SessionListState()

        val next = state
            .applyQuestionAsked(QuestionRequest(id = "q1", sessionId = "s1", questions = emptyList())).first
            .applyQuestionAsked(QuestionRequest(id = "q2", sessionId = "s1", questions = emptyList())).first

        assertEquals(listOf("q1", "q2"), next.pendingQuestions.map { it.id })
    }

    @Test
    fun `applyQuestionAsked with the same id but a different sessionId is still idempotent`() {
        // Idempotency is keyed on QuestionRequest.id alone — a different
        // sessionId does NOT turn it into a new question.
        val state = SessionListState(pendingQuestions = listOf(
            QuestionRequest(id = "q1", sessionId = "s1", questions = emptyList())
        ))
        val dup = QuestionRequest(id = "q1", sessionId = "s2", questions = emptyList())

        val (next, _) = state.applyQuestionAsked(dup)

        assertEquals(1, next.pendingQuestions.size)
        assertEquals("s1", next.pendingQuestions[0].sessionId)
    }

    @Test
    fun `applyQuestionAsked preserves the questions list payload verbatim`() {
        val state = SessionListState()
        val q = QuestionRequest(
            id = "q1", sessionId = "s1",
            questions = listOf(
                QuestionInfo("q", "h", listOf(QuestionOption("a", "b")))
            )
        )

        val (next, _) = state.applyQuestionAsked(q)

        assertEquals(1, next.pendingQuestions[0].questions.size)
        assertEquals("q", next.pendingQuestions[0].questions[0].question)
    }

    @Test
    fun `applyQuestionResolved on an empty pending list is a no-op`() {
        val state = SessionListState()

        val (next, _) = state.applyQuestionResolved("anything")

        assertTrue(next.pendingQuestions.isEmpty())
    }

    @Test
    fun `applyQuestionResolved resolving the only pending question yields empty list`() {
        val state = SessionListState(pendingQuestions = listOf(
            QuestionRequest(id = "q1", sessionId = "s1", questions = emptyList())
        ))

        val (next, _) = state.applyQuestionResolved("q1")

        assertTrue(next.pendingQuestions.isEmpty())
    }

    @Test
    fun `applyQuestionResolved filter removes every entry sharing the id`() {
        // The implementation uses `filter { it.id != requestId }`; if the
        // list ever contains duplicates (it should not, but defensively)
        // all are removed.
        val state = SessionListState(pendingQuestions = listOf(
            QuestionRequest(id = "q1", sessionId = "s1", questions = emptyList()),
            QuestionRequest(id = "q1", sessionId = "s2", questions = emptyList()),
            QuestionRequest(id = "q2", sessionId = "s1", questions = emptyList())
        ))

        val (next, _) = state.applyQuestionResolved("q1")

        assertEquals(listOf("q2"), next.pendingQuestions.map { it.id })
    }

    // === applyTodoUpdated: boundary ======================================

    @Test
    fun `applyTodoUpdated with an empty todos list stores an empty list for the session`() {
        val state = SessionListState()

        val (next, _) = state.applyTodoUpdated("s1", emptyList())

        assertEquals(0, next.sessionTodos["s1"]?.size)
    }

    @Test
    fun `applyTodoUpdated replacing a prior list with an empty list yields empty`() {
        val state = SessionListState(
            sessionTodos = mapOf("s1" to listOf(TodoItem("a", "pending", "low", "t0")))
        )

        val (next, _) = state.applyTodoUpdated("s1", emptyList())

        assertEquals(0, next.sessionTodos["s1"]?.size)
    }

    @Test
    fun `applyTodoUpdated preserves other sessions' todo lists`() {
        val state = SessionListState(
            sessionTodos = mapOf(
                "s1" to listOf(TodoItem("a", "pending", "low", "t0")),
                "s2" to listOf(TodoItem("b", "completed", "high", "t1"))
            )
        )

        val (next, _) = state.applyTodoUpdated("s1", listOf(TodoItem("c", "pending", "medium", "t2")))

        assertEquals(1, next.sessionTodos["s1"]?.size)
        assertEquals("c", next.sessionTodos["s1"]!![0].content)
        assertEquals(1, next.sessionTodos["s2"]?.size)
        assertEquals("b", next.sessionTodos["s2"]!![0].content)
    }

    // === applyMarkSessionUnread: boundary ================================

    @Test
    fun `applyMarkSessionUnread is idempotent when the id is already unread`() {
        val state = UnreadState(unreadSessions = setOf("s1"))

        val (next, _) = state.applyMarkSessionUnread("s1", currentSessionId = "other")

        assertEquals(setOf("s1"), next.unreadSessions)
    }

    @Test
    fun `applyMarkSessionUnread is case-sensitive for s1 versus S1`() {
        val state = UnreadState()

        val (next, _) = state.applyMarkSessionUnread("s1", currentSessionId = null).first
            .applyMarkSessionUnread("S1", currentSessionId = null)

        assertEquals(setOf("s1", "S1"), next.unreadSessions)
    }

    @Test
    fun `applyMarkSessionUnread preserves unrelated unread entries`() {
        val state = UnreadState(unreadSessions = setOf("a", "b"))

        val (next, _) = state.applyMarkSessionUnread("c", currentSessionId = null)

        assertEquals(setOf("a", "b", "c"), next.unreadSessions)
    }

    // === ensurePlaceholderPart: direct unit tests (type-guarded inject) ==

    @Test
    fun `ensurePlaceholderPart for tool type returns the map unchanged`() {
        val input = mapOf("m1" to listOf(Part(id = "p1", messageId = "m1", sessionId = "s1", type = "text")))
        val result = ensurePlaceholderPart(input, "m1", "p2", "s1", "tool")
        // Non-streamable type → no injection; the existing single part is
        // preserved and no p2 placeholder is added.
        assertEquals(1, result["m1"]?.size)
        assertEquals("p1", result["m1"]!![0].id)
        assertFalse(result["m1"]!!.any { it.id == "p2" })
    }

    @Test
    fun `ensurePlaceholderPart for patch type returns the map unchanged`() {
        val input = emptyMap<String, List<Part>>()
        val result = ensurePlaceholderPart(input, "m1", "p1", "s1", "patch")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `ensurePlaceholderPart for file type returns the map unchanged`() {
        val input = emptyMap<String, List<Part>>()
        val result = ensurePlaceholderPart(input, "m1", "p1", "s1", "file")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `ensurePlaceholderPart for step-start type returns the map unchanged`() {
        val input = emptyMap<String, List<Part>>()
        val result = ensurePlaceholderPart(input, "m1", "p1", "s1", "step-start")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `ensurePlaceholderPart for text type on missing msgId inserts a single-element list`() {
        val result = ensurePlaceholderPart(emptyMap(), "m1", "p1", "s1", "text")
        assertEquals(1, result["m1"]?.size)
        assertEquals("p1", result["m1"]!![0].id)
        assertEquals("text", result["m1"]!![0].type)
    }

    @Test
    fun `ensurePlaceholderPart for reasoning type on missing msgId inserts a reasoning part`() {
        val result = ensurePlaceholderPart(emptyMap(), "m1", "p1", "s1", "reasoning")
        assertEquals("reasoning", result["m1"]!![0].type)
    }

    @Test
    fun `ensurePlaceholderPart appends when msgId exists but lacks pId`() {
        val input = mapOf(
            "m1" to listOf(Part(id = "other", messageId = "m1", sessionId = "s1", type = "text"))
        )

        val result = ensurePlaceholderPart(input, "m1", "p1", "s1", "text")

        assertEquals(2, result["m1"]?.size)
        assertTrue(result["m1"]!!.any { it.id == "other" })
        assertTrue(result["m1"]!!.any { it.id == "p1" })
    }

    @Test
    fun `ensurePlaceholderPart is a no-op when msgId already has pId`() {
        val input = mapOf(
            "m1" to listOf(Part(id = "p1", messageId = "m1", sessionId = "s1", type = "text"))
        )

        val result = ensurePlaceholderPart(input, "m1", "p1", "s1", "text")

        assertEquals(1, result["m1"]?.size)
    }

    @Test
    fun `ensurePlaceholderPart does not touch unrelated msgId entries`() {
        val input = mapOf(
            "m-other" to listOf(Part(id = "x", messageId = "m-other", sessionId = "s1", type = "text"))
        )

        val result = ensurePlaceholderPart(input, "m1", "p1", "s1", "text")

        assertEquals(1, result["m-other"]?.size)
        assertEquals("x", result["m-other"]!![0].id)
        assertEquals(1, result["m1"]?.size)
    }

    // === reasoningPartOrNull: direct unit tests =========================

    @Test
    fun `reasoningPartOrNull for reasoning returns a Part typed reasoning`() {
        val part = reasoningPartOrNull("reasoning", "p1", "m1", "s1")
        assertNotNull(part)
        assertEquals("reasoning", part!!.type)
        assertEquals("p1", part.id)
        assertEquals("m1", part.messageId)
        assertEquals("s1", part.sessionId)
    }

    @Test
    fun `reasoningPartOrNull for text returns null`() {
        assertNull(reasoningPartOrNull("text", "p1", "m1", "s1"))
    }

    @Test
    fun `reasoningPartOrNull for tool returns null`() {
        assertNull(reasoningPartOrNull("tool", "p1", "m1", "s1"))
    }

    @Test
    fun `reasoningPartOrNull for empty string returns null`() {
        assertNull(reasoningPartOrNull("", "p1", "m1", "s1"))
    }

    // === isStreamablePartType: direct unit tests ========================

    @Test
    fun `isStreamablePartType returns true for text`() {
        assertTrue(isStreamablePartType("text"))
    }

    @Test
    fun `isStreamablePartType returns true for reasoning`() {
        assertTrue(isStreamablePartType("reasoning"))
    }

    @Test
    fun `isStreamablePartType returns false for tool`() {
        assertFalse(isStreamablePartType("tool"))
    }

    @Test
    fun `isStreamablePartType returns false for patch`() {
        assertFalse(isStreamablePartType("patch"))
    }

    @Test
    fun `isStreamablePartType returns false for an empty string`() {
        assertFalse(isStreamablePartType(""))
    }

    @Test
    fun `isStreamablePartType returns false for an unknown custom type`() {
        assertFalse(isStreamablePartType("custom-future-type"))
    }

    // P2-4: SseSideEffect return verification
    //
    // Every applyXxx now returns Pair of State and effects List. Most
    // return emptyList for effects (the transform is pure state-only;
    // cross-slice effects are computed by the dispatcher). These tests pin
    // that contract: the effects list is structurally empty for pure-slice
    // transforms, and the state is unchanged from the pre-P2-4 behavior.

    @Test
    fun `p24 applySessionCreated returns empty effects list`() {
        val state = SessionListState()
        val created = Session(id = "s1", directory = "/x")

        val (next, effects) = state.applySessionCreated(created)

        assertEquals(1, next.sessions.size)
        assertTrue("effects must be empty for pure-slice transform", effects.isEmpty())
    }

    @Test
    fun `p24 applySessionStatus returns empty effects list`() {
        val state = SessionListState()

        val (next, effects) = state.applySessionStatus("s1", SessionStatus(type = "busy"))

        assertEquals(SessionStatus(type = "busy"), next.sessionStatuses["s1"])
        assertTrue(
            "applySessionStatus effects must be empty — busy/idle reload effects " +
                "depend on chat.currentSessionId (cross-slice) and are computed by the dispatcher",
            effects.isEmpty()
        )
    }

    @Test
    fun `p24 applyMessageUpdated preserves found flag`() {
        // applyMessageUpdated is the ONE function that kept its pre-P2-4
        // Pair<ChatState, Boolean> signature (the Boolean is a diagnostic
        // found/not-found flag for logging, NOT a bus-level side effect).
        val state = ChatState(messages = listOf(Message(id = "m1", role = "user")))
        val updated = Message(id = "m1", role = "user", cost = 1.0)

        val (next, found) = state.applyMessageUpdated(updated)

        assertTrue(found)
        assertEquals(1.0, next.messages[0].cost!!, 1e-9)
    }

    @Test
    fun `p24 applyPartDeltaLeadingEdge returns empty effects list`() {
        val state = ChatState()

        val (next, effects) = state.applyPartDeltaLeadingEdge(
            partId = "p1", delta = "hello", knownType = "text",
            msgId = "m1", sessionId = "s1"
        )

        assertEquals("hello", next.streamingPartTexts["p1"])
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `p24 applyPartFullTextLeadingEdge returns empty effects list`() {
        val state = ChatState()

        val (next, effects) = state.applyPartFullTextLeadingEdge(
            partId = "p1", fullText = "authoritative", partType = "text",
            pId = "p1", msgId = "m1", sessionId = "s1"
        )

        assertEquals("authoritative", next.streamingPartTexts["p1"])
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `p24 flushCoalesceBufferForPart returns empty effects list`() {
        val state = ChatState(
            streamingPartTexts = mapOf("p1" to "base"),
            deltaBuffer = mapOf("p1" to " MORE"),
            pendingFlushPartIds = setOf("p1")
        )

        val (next, effects) = state.flushCoalesceBufferForPart("p1")

        assertEquals("base MORE", next.streamingPartTexts["p1"])
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `p24 clearAllCoalesceBuffers returns empty effects list`() {
        val state = ChatState(
            deltaBuffer = mapOf("p1" to "x"),
            pendingFlushPartIds = setOf("p1")
        )

        val (next, effects) = state.clearAllCoalesceBuffers()

        assertTrue(next.deltaBuffer.isEmpty())
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `p24 applyQuestionAsked returns empty effects list`() {
        val state = SessionListState()
        val question = QuestionRequest(id = "q1", sessionId = "s1", questions = emptyList())

        val (next, effects) = state.applyQuestionAsked(question)

        assertEquals(1, next.pendingQuestions.size)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `p24 applyMarkSessionUnread returns empty effects list`() {
        val state = UnreadState()

        val (next, effects) = state.applyMarkSessionUnread("s1", currentSessionId = null)

        assertEquals(setOf("s1"), next.unreadSessions)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `p24 markFlushPending returns empty effects list`() {
        val state = ChatState()

        val (next, effects) = state.markFlushPending("p1")

        assertTrue(next.pendingFlushPartIds.contains("p1"))
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `allSessionsById_merges_three_sources_dedup`() {
        val root = Session(id = "A", directory = "/d", parentId = null)
        val dirChild = Session(id = "C", directory = "/d", parentId = "A")
        val childStoreChild = Session(id = "E", directory = "/d", parentId = "A")
        val byId = allSessionsById(
            sessions = listOf(root),
            directorySessions = mapOf("w" to listOf(dirChild)),
            childSessions = mapOf("A" to listOf(childStoreChild)),
        )
        assertEquals(root, byId["A"])
        assertEquals(dirChild, byId["C"])
        assertEquals(childStoreChild, byId["E"])
    }

    @Test
    fun `allSessionsById_dedups_when_same_id_appears_in_multiple_sources`() {
        val primary = Session(id = "A", directory = "/d", parentId = null, title = "primary")
        val dirDup = Session(id = "A", directory = "/d", parentId = null, title = "dirDup")
        val childDup = Session(id = "A", directory = "/d", parentId = null, title = "childDup")
        val byId = allSessionsById(
            sessions = listOf(primary),
            directorySessions = mapOf("w" to listOf(dirDup)),
            childSessions = mapOf("A" to listOf(childDup)),
        )
        assertEquals(1, byId.size)
        assertEquals("primary", byId["A"]?.title)
    }

    @Test
    fun `rootIdOf_walks_parent_chain`() {
        val byId = mapOf(
            "A" to Session(id = "A", directory = "/d", parentId = null),
            "B" to Session(id = "B", directory = "/d", parentId = "A"),
            "C" to Session(id = "C", directory = "/d", parentId = "B"),
        )
        assertEquals("A", rootIdOf("C", byId))
        assertEquals("A", rootIdOf("A", byId))
        assertNull(rootIdOf("X", byId))
    }

    @Test
    fun `rootIdOf_cycle_returns_null`() {
        val byId = mapOf(
            "A" to Session(id = "A", directory = "/d", parentId = "B"),
            "B" to Session(id = "B", directory = "/d", parentId = "A"),
        )
        assertNull(rootIdOf("A", byId))
    }

    @Test
    fun `treeIds_includes_root_and_all_descendants`() {
        val byId = mapOf(
            "A" to Session(id = "A", directory = "/d", parentId = null),
            "B" to Session(id = "B", directory = "/d", parentId = "A"),
            "C" to Session(id = "C", directory = "/d", parentId = "B"),
            "Z" to Session(id = "Z", directory = "/d", parentId = null),
        )
        assertEquals(setOf("A", "B", "C"), treeIds("A", byId))
    }

    @Test
    fun `subtreeIds_covers_three_sources`() {
        val root = Session(id = "A", directory = "/d", parentId = null)
        val dirChild = Session(id = "C", directory = "/d", parentId = "A")
        val childStoreChild = Session(id = "E", directory = "/d", parentId = "A")
        val ids = subtreeIds(
            "A",
            listOf(root),
            mapOf("w" to listOf(dirChild)),
            mapOf("A" to listOf(childStoreChild)),
        )
        assertEquals(setOf("A", "C", "E"), ids)
    }

    // ── §task6 question tree aggregation: questionRootIds / questionsInTree ─

    @Test
    fun `questionRootIds_aggregates_child_question_to_root`() {
        val byId = mapOf(
            "A" to Session(id = "A", directory = "/d", parentId = null),
            "C" to Session(id = "C", directory = "/d", parentId = "A"),
        )
        val qs = listOf(QuestionRequest(id = "q1", sessionId = "C", questions = emptyList()))
        assertEquals(setOf("A"), questionRootIds(qs, byId))
    }

    @Test
    fun `questionRootIds_returns_one_root_per_tree_even_with_multiple_descendants`() {
        val byId = mapOf(
            "A" to Session(id = "A", directory = "/d", parentId = null),
            "B" to Session(id = "B", directory = "/d", parentId = "A"),
            "C" to Session(id = "C", directory = "/d", parentId = "A"),
            "Z" to Session(id = "Z", directory = "/d", parentId = null),
        )
        val qs = listOf(
            QuestionRequest(id = "q1", sessionId = "B", questions = emptyList()),
            QuestionRequest(id = "q2", sessionId = "C", questions = emptyList()),
            QuestionRequest(id = "q3", sessionId = "Z", questions = emptyList()),
        )
        assertEquals(setOf("A", "Z"), questionRootIds(qs, byId))
    }

    @Test
    fun `questionRootIds_skips_questions_whose_session_is_unknown`() {
        // rootIdOf returns null for unknown sessions → those questions drop
        // out (no root to surface them on). The Sessions tab cannot render a
        // marker for an unknown id, so dropping is the only sane behaviour.
        val byId = mapOf(
            "A" to Session(id = "A", directory = "/d", parentId = null),
        )
        val qs = listOf(
            QuestionRequest(id = "q1", sessionId = "A", questions = emptyList()),
            QuestionRequest(id = "q2", sessionId = "Ghost", questions = emptyList()),
        )
        assertEquals(setOf("A"), questionRootIds(qs, byId))
    }

    @Test
    fun `questionRootIds_empty_input_returns_empty_set`() {
        assertEquals(emptySet<String>(), questionRootIds(emptyList(), emptyMap()))
    }

    @Test
    fun `questionsInTree_returns_child_questions_preserving_id`() {
        val byId = mapOf(
            "A" to Session(id = "A", directory = "/d", parentId = null),
            "C" to Session(id = "C", directory = "/d", parentId = "A"),
        )
        val qs = listOf(
            QuestionRequest(id = "q1", sessionId = "C", questions = emptyList()),
            QuestionRequest(id = "q2", sessionId = "Z", questions = emptyList()),
        )
        val inTree = questionsInTree("A", qs, byId)
        assertEquals(1, inTree.size)
        assertEquals("q1", inTree[0].id)
        assertEquals("C", inTree[0].sessionId)
    }

    @Test
    fun `questionsInTree_includes_root_session_own_question`() {
        val byId = mapOf(
            "A" to Session(id = "A", directory = "/d", parentId = null),
        )
        val qs = listOf(
            QuestionRequest(id = "q1", sessionId = "A", questions = emptyList()),
        )
        val inTree = questionsInTree("A", qs, byId)
        assertEquals(1, inTree.size)
        assertEquals("A", inTree[0].sessionId)
    }

    @Test
    fun `questionsInTree_excludes_sibling_tree_questions`() {
        val byId = mapOf(
            "A" to Session(id = "A", directory = "/d", parentId = null),
            "B" to Session(id = "B", directory = "/d", parentId = "A"),
            "Z" to Session(id = "Z", directory = "/d", parentId = null),
            "Y" to Session(id = "Y", directory = "/d", parentId = "Z"),
        )
        val qs = listOf(
            QuestionRequest(id = "q1", sessionId = "B", questions = emptyList()),
            QuestionRequest(id = "q2", sessionId = "Y", questions = emptyList()),
        )
        val inTreeA = questionsInTree("A", qs, byId)
        assertEquals(listOf("q1"), inTreeA.map { it.id })
        val inTreeZ = questionsInTree("Z", qs, byId)
        assertEquals(listOf("q2"), inTreeZ.map { it.id })
    }

    @Test
    fun `questionsInTree_empty_input_returns_empty_list`() {
        assertEquals(
            emptyList<QuestionRequest>(),
            questionsInTree("A", emptyList(), emptyMap()),
        )
    }

    // ── §task7-coverage: applyMessageTimestampBump directorySessions branch ─

    @Test
    fun `applyMessageTimestampBump bumps a session that lives only in directorySessions`() {
        val state = SessionListState(
            sessions = listOf(Session(id = "other", directory = "/tmp")),
            directorySessions = mapOf("/d" to listOf(
                Session(id = "target", directory = "/d", time = Session.TimeInfo(updated = 10L)),
            )),
        )

        val (next, _) = state.applyMessageTimestampBump("target", 99L)

        val target = next.directorySessions["/d"]!!.first { it.id == "target" }
        assertEquals(99L, target.time?.updated)
    }

    // ── §task7-coverage: applyArchiveEviction directorySessions branch ──────

    @Test
    fun `applyArchiveEviction replaces the archived session inside directorySessions`() {
        val original = Session(id = "s1", directory = "/d", title = "old")
        val state = SessionListState(
            sessions = listOf(original),
            directorySessions = mapOf("/d" to listOf(original)),
        )
        val archived = original.copy(
            time = Session.TimeInfo(archived = 1L),
            title = "archived-title",
        )

        val (next, _) = state.applyArchiveEviction(archived, listOf("s1"))

        val dirEntry = next.directorySessions["/d"]!!.first { it.id == "s1" }
        assertTrue(dirEntry.isArchived)
        assertEquals("archived-title", dirEntry.title)
    }

    @Test
    fun `applyArchiveEviction leaves non-matching sessions inside directorySessions untouched`() {
        val state = SessionListState(
            directorySessions = mapOf("/d" to listOf(
                Session(id = "other", directory = "/d", title = "keep-me"),
            )),
        )
        val archived = Session(
            id = "arc", directory = "/d",
            time = Session.TimeInfo(archived = 1L),
        )

        val (next, _) = state.applyArchiveEviction(archived, emptyList())

        val other = next.directorySessions["/d"]!!.first { it.id == "other" }
        assertEquals("keep-me", other.title)
        assertFalse(other.isArchived)
    }

    @Test
    fun `applyMessageTimestampBump does not touch non-matching sessions in directorySessions`() {
        val state = SessionListState(
            directorySessions = mapOf("/d" to listOf(
                Session(id = "unrelated", directory = "/d", time = Session.TimeInfo(updated = 50L)),
            )),
        )

        val (next, _) = state.applyMessageTimestampBump("target", 99L)

        val unrelated = next.directorySessions["/d"]!!.first { it.id == "unrelated" }
        assertEquals(50L, unrelated.time?.updated)
    }

    // === §Wave5b-Q13 blocker-2: cleanScrollStateForSubtree ==================
    //
    // The UNCONDITIONAL scroll-state cleanup applied to an archived subtree
    // (used by SessionArchived / BulkSessionsRefreshed reducers AND by
    // launchSetSessionArchived onSuccess). Pure function — JVM-testable
    // without the reducer harness.

    @Test
    fun `cleanScrollStateForSubtree wipes pendingScrollRequest when target is in subtree`() {
        val state = ChatState(
            currentSessionId = "cur",
            messages = listOf(Message(id = "m1", role = "user")),
            partsByMessage = mapOf("m1" to emptyList()),
            pendingScrollRequest = cn.vectory.ocdroid.ui.PendingScrollRequest(
                requestId = 1L,
                targetSessionId = "stale-target",
                behavior = cn.vectory.ocdroid.ui.ScrollBehavior.Latest,
            ),
        )

        val next = state.cleanScrollStateForSubtree(setOf("stale-target", "other"))

        assertNull(
            "pendingScrollRequest MUST be wiped when targetSessionId is in subtree",
            next.pendingScrollRequest,
        )
        // Content untouched (the helper is scroll-state-only).
        assertEquals("cur", next.currentSessionId)
        assertEquals(1, next.messages.size)
        assertEquals(1, next.partsByMessage.size)
    }

    @Test
    fun `cleanScrollStateForSubtree PRESERVES pendingScrollRequest when target is NOT in subtree`() {
        val liveReq = cn.vectory.ocdroid.ui.PendingScrollRequest(
            requestId = 9L,
            targetSessionId = "live",
            behavior = cn.vectory.ocdroid.ui.ScrollBehavior.Latest,
        )
        val state = ChatState(pendingScrollRequest = liveReq)

        val next = state.cleanScrollStateForSubtree(setOf("archived-1", "archived-2"))

        assertEquals(liveReq, next.pendingScrollRequest)
    }

    @Test
    fun `cleanScrollStateForSubtree wipes only parentReturnCheckpoints entries keyed by the subtree`() {
        val keep = cn.vectory.ocdroid.ui.ScrollCheckpoint(anchorKey = "k-live", fallbackIndex = 1, offset = 1)
        val drop = cn.vectory.ocdroid.ui.ScrollCheckpoint(anchorKey = "k-stale", fallbackIndex = 2, offset = 2)
        val state = ChatState(
            parentReturnCheckpoints = mapOf(
                "live-child" to keep,
                "stale-child" to drop,
                "stale-grandchild" to drop,
            ),
        )

        val next = state.cleanScrollStateForSubtree(setOf("stale-child", "stale-grandchild"))

        assertEquals(
            "live entry preserved",
            mapOf("live-child" to keep),
            next.parentReturnCheckpoints,
        )
    }

    @Test
    fun `cleanScrollStateForSubtree empty subtree is a no-op (preserves reference)`() {
        // Fast path: empty subtree returns the SAME ChatState instance (no
        // .copy() allocation). The hot path is a bulk refresh archiving an
        // unrelated subtree on a chat with no scroll state — the helper MUST
        // be cheap.
        val state = ChatState(
            currentSessionId = "cur",
            pendingScrollRequest = null,
            parentReturnCheckpoints = emptyMap(),
        )

        val next = state.cleanScrollStateForSubtree(emptySet())

        assertSame("empty subtree MUST return the same instance (no allocation)", state, next)
    }

    @Test
    fun `cleanScrollStateForSubtree already-clean chat also returns the same instance`() {
        // Subtree non-empty but no fields would change (slot already null +
        // checkpoints already empty) → no allocation either.
        val state = ChatState(
            currentSessionId = "cur",
            pendingScrollRequest = null,
            parentReturnCheckpoints = emptyMap(),
        )

        val next = state.cleanScrollStateForSubtree(setOf("archived"))

        assertSame(
            "no-op cleanup MUST return the same instance (no allocation)",
            state,
            next,
        )
    }

    @Test
    fun `cleanScrollStateForSubtree is idempotent`() {
        // Calling twice yields the same result. Important because BOTH the
        // reducer's applyArchivedChatClear AND cleanScrollStateForSubtree can
        // touch the same fields for the current-archived case.
        val state = ChatState(
            pendingScrollRequest = cn.vectory.ocdroid.ui.PendingScrollRequest(
                requestId = 1L,
                targetSessionId = "drop",
                behavior = cn.vectory.ocdroid.ui.ScrollBehavior.Latest,
            ),
            parentReturnCheckpoints = mapOf(
                "drop" to cn.vectory.ocdroid.ui.ScrollCheckpoint(null, 0, 0),
            ),
        )
        val subtree = setOf("drop")

        val once = state.cleanScrollStateForSubtree(subtree)
        val twice = once.cleanScrollStateForSubtree(subtree)

        assertEquals(once, twice)
        assertNull(twice.pendingScrollRequest)
        assertTrue(twice.parentReturnCheckpoints.isEmpty())
    }

    @Test
    fun `cleanScrollStateForSubtree preserves all chat content fields`() {
        // The helper is SCROLL-STATE-ONLY. Chat content (messages / parts /
        // streaming / cursor / currentModel / pendingAgent / pendingModel /
        // currentSessionId) MUST survive intact.
        val state = ChatState(
            currentSessionId = "cur",
            messages = listOf(Message(id = "m1", role = "user")),
            partsByMessage = mapOf("m1" to emptyList()),
            streamingPartTexts = mapOf("p1" to "delta"),
            streamingReasoningPart = Part(id = "p1", type = "reasoning", text = "r"),
            olderMessagesCursor = "cursor",
            hasMoreMessages = true,
            currentModel = Message.ModelInfo("openai", "gpt-5"),
            pendingAgent = "agent-x",
            pendingModel = Message.ModelInfo("anthropic", "claude"),
            pendingScrollRequest = cn.vectory.ocdroid.ui.PendingScrollRequest(
                requestId = 1L,
                targetSessionId = "stale",
                behavior = cn.vectory.ocdroid.ui.ScrollBehavior.Latest,
            ),
        )

        val next = state.cleanScrollStateForSubtree(setOf("stale"))

        // Only the slot wiped.
        assertNull(next.pendingScrollRequest)
        // Everything else preserved.
        assertEquals("cur", next.currentSessionId)
        assertEquals(1, next.messages.size)
        assertEquals(1, next.partsByMessage.size)
        assertEquals("delta", next.streamingPartTexts["p1"])
        assertEquals("r", next.streamingReasoningPart?.text)
        assertEquals("cursor", next.olderMessagesCursor)
        assertTrue(next.hasMoreMessages)
        assertEquals(Message.ModelInfo("openai", "gpt-5"), next.currentModel)
        assertEquals("agent-x", next.pendingAgent)
        assertEquals(Message.ModelInfo("anthropic", "claude"), next.pendingModel)
    }
}
