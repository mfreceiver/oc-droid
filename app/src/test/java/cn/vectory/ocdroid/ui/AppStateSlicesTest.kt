package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.FileDiff
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import cn.vectory.ocdroid.data.model.TodoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R18 Phase 5++ coverage: data-class state slices + small sealed hierarchies
 * on [AppStateSlices.kt]. These are pure value types whose synthetic
 * accessors (equals / hashCode / copy / componentN / defaults) only land in
 * coverage when a test constructs them. Coverage gap before this file:
 *  - ContextUsage: 0/1 class, 0/11 lines, 0/71 instructions
 *  - TrafficState: 1/2 branches, 14/19 lines (totalTrafficBytes getter)
 *  - CachedSessionWindow: 0% (never constructed directly)
 *
 * Construction-only tests are sufficient for kover line coverage of synthetic
 * accessors + the explicit derived getters (totalTrafficBytes, etc.).
 */
class AppStateSlicesTest {

    @Test
    fun `ContextUsage default constructor fills every field`() {
        val c = ContextUsage(
            percentage = 0f,
            totalTokens = 0,
            contextLimit = 0,
        )
        assertEquals(0f, c.percentage, 0.0001f)
        assertEquals(0, c.totalTokens)
        assertEquals(0, c.contextLimit)
        assertNull(c.providerId)
        assertNull(c.modelId)
        assertNull(c.inputTokens)
        assertNull(c.outputTokens)
        assertNull(c.reasoningTokens)
        assertNull(c.cachedReadTokens)
        assertNull(c.cachedWriteTokens)
        assertNull(c.cost)
    }

    @Test
    fun `ContextUsage full constructor round-trips`() {
        val c = ContextUsage(
            percentage = 0.5f,
            totalTokens = 100,
            contextLimit = 200,
            providerId = "p",
            modelId = "m",
            inputTokens = 70,
            outputTokens = 20,
            reasoningTokens = 10,
            cachedReadTokens = 5,
            cachedWriteTokens = 2,
            cost = 0.001,
        )
        assertEquals(0.5f, c.percentage, 0.0001f)
        assertEquals("p", c.providerId)
        assertEquals("m", c.modelId)
        assertEquals(70, c.inputTokens)
        assertEquals(20, c.outputTokens)
        assertEquals(10, c.reasoningTokens)
        assertEquals(5, c.cachedReadTokens)
        assertEquals(2, c.cachedWriteTokens)
        assertEquals(0.001, c.cost!!, 0.0000001)
    }

    @Test
    fun `ContextUsage equals hashCode copy componentN are synthetic-covered`() {
        val c1 = ContextUsage(percentage = 0.1f, totalTokens = 1, contextLimit = 10)
        val c2 = c1.copy()
        assertEquals(c1, c2)
        assertEquals(c1.hashCode(), c2.hashCode())
        assertEquals(0.1f, c1.component1(), 0.0001f)
        assertEquals(1, c1.component2())
        assertEquals(10, c1.component3())
        assertTrue(c1.toString().contains("ContextUsage"))
    }

    @Test
    fun `TrafficState defaults are zero`() {
        val t = TrafficState()
        assertEquals(0L, t.trafficSent)
        assertEquals(0L, t.trafficReceived)
        assertEquals(0L, t.totalTrafficBytes)
    }

    @Test
    fun `TrafficState totalTrafficBytes sums sent and received`() {
        val t = TrafficState(trafficSent = 100L, trafficReceived = 250L)
        assertEquals(350L, t.totalTrafficBytes)
    }

    @Test
    fun `TrafficState equals and copy`() {
        val t1 = TrafficState(trafficSent = 1L, trafficReceived = 2L)
        val t2 = t1.copy()
        assertEquals(t1, t2)
        val t3 = t1.copy(trafficSent = 99L)
        assertEquals(99L, t3.trafficSent)
        assertEquals(2L, t3.trafficReceived)
    }

    @Test
    fun `CachedSessionWindow default constructor round-trips`() {
        val w = cn.vectory.ocdroid.ui.controller.CachedSessionWindow(
            messages = emptyList(),
            partsByMessage = emptyMap(),
            olderMessagesCursor = null,
            hasMoreMessages = true,
        )
        assertTrue(w.messages.isEmpty())
        assertTrue(w.partsByMessage.isEmpty())
        assertNull(w.olderMessagesCursor)
        assertTrue(w.hasMoreMessages)
    }

    @Test
    fun `CachedSessionWindow full constructor round-trips`() {
        val msg = Message(id = "m1", role = "user")
        val part = Part(id = "p1", type = "text")
        val w = cn.vectory.ocdroid.ui.controller.CachedSessionWindow(
            messages = listOf(msg),
            partsByMessage = mapOf("m1" to listOf(part)),
            olderMessagesCursor = "cursor-1",
            hasMoreMessages = false,
        )
        assertEquals(listOf(msg), w.messages)
        assertEquals(mapOf("m1" to listOf(part)), w.partsByMessage)
        assertEquals("cursor-1", w.olderMessagesCursor)
        assertFalse(w.hasMoreMessages)
    }

    @Test
    fun `CachedSessionWindow equals hashCode copy`() {
        val w1 = cn.vectory.ocdroid.ui.controller.CachedSessionWindow(
            messages = emptyList(),
            partsByMessage = emptyMap(),
            olderMessagesCursor = null,
            hasMoreMessages = true,
        )
        val w2 = w1.copy()
        assertEquals(w1, w2)
        assertEquals(w1.hashCode(), w2.hashCode())
    }

    @Test
    fun `ConnectionPhase has the expected variants`() {
        // Seal completeness: every variant is a distinct subtype.
        val idle: ConnectionPhase = ConnectionPhase.Idle
        val connecting: ConnectionPhase = ConnectionPhase.Connecting
        val connected: ConnectionPhase = ConnectionPhase.Connected
        val disconnected: ConnectionPhase = ConnectionPhase.Disconnected
        val retrying: ConnectionPhase = ConnectionPhase.ReconnectingAttempt(1, 3)

        assertTrue(idle is ConnectionPhase.Idle)
        assertTrue(connecting is ConnectionPhase.Connecting)
        assertTrue(connected is ConnectionPhase.Connected)
        assertTrue(disconnected is ConnectionPhase.Disconnected)
        assertTrue(retrying is ConnectionPhase.ReconnectingAttempt)
        assertEquals(1, (retrying as ConnectionPhase.ReconnectingAttempt).attempt)
        assertEquals(3, retrying.maxAttempts)
    }

    // R-20 Phase 2 / remove-message-persistence Task 4: the legacy single-gap
    // `GapInfo` class + its constructor round-trip test were removed, then the
    // entire multi-gap replacement (the contract GapMarker + the GapFill
    // coordinator + the gap-aware render pipeline) was deleted in Task 4 —
    // catch-up now always merges the fetched window.

    // ── §U-MN3: SessionListState.withProjection + copy propagation ─────────

    @Test
    fun `withProjection delegates to copy - only sessionStatuses differs`() {
        val session = Session(id = "s1", directory = "/work")
        val oldStatuses = mapOf("s1" to SessionStatus("busy"))
        val newStatuses = mapOf("s1" to SessionStatus("idle"))

        val original = SessionListState(
            sessions = listOf(session),
            activeSessionIds = setOf("s1"),
            expandedSessionIds = setOf("s1"),
            loadedSessionLimit = 42,
            hasMoreSessions = false,
            isLoadingMoreSessions = true,
            isRefreshingSessions = true,
            pendingPermissions = listOf(PermissionRequest(id = "p1", sessionId = "s1")),
            pendingQuestions = listOf(QuestionRequest(id = "q1", sessionId = "s1", questions = emptyList())),
            childSessions = mapOf("root" to listOf(session)),
            completeRootIds = setOf("root"),
            completenessEpoch = 99L,
            directorySessions = mapOf("/work" to listOf(session)),
            sessionTodos = mapOf("s1" to listOf(TodoItem(content = "task", status = "pending", priority = "high", id = "t1"))),
            sessionDiffs = mapOf("s1" to listOf(FileDiff(filePath = "f1"))),
            sessionErrorsById = mapOf("s1" to SlimSessionLastError(name = "err")),
            questionAggregationSignal = SlimAggregationSignal(completeness = SlimAggregationCompleteness.INCOMPLETE),
            permissionAggregationSignal = SlimAggregationSignal(completeness = SlimAggregationCompleteness.INCOMPLETE),
            pendingCreateIds = setOf("s1"),
            pendingCreatedAt = mapOf("s1" to 1000L),
            hasCompletedInitialLoad = true,
            abortPendingSessionIds = mapOf("s1" to 1L),
        ).withProjection(oldStatuses)

        val result = original.withProjection(newStatuses)

        assertEquals(newStatuses, result.sessionStatuses)
        assertEquals(original.sessions, result.sessions)
        assertEquals(original.activeSessionIds, result.activeSessionIds)
        assertEquals(original.expandedSessionIds, result.expandedSessionIds)
        assertEquals(original.loadedSessionLimit, result.loadedSessionLimit)
        assertEquals(original.hasMoreSessions, result.hasMoreSessions)
        assertEquals(original.isLoadingMoreSessions, result.isLoadingMoreSessions)
        assertEquals(original.isRefreshingSessions, result.isRefreshingSessions)
        assertEquals(original.pendingPermissions, result.pendingPermissions)
        assertEquals(original.pendingQuestions, result.pendingQuestions)
        assertEquals(original.childSessions, result.childSessions)
        assertEquals(original.completeRootIds, result.completeRootIds)
        assertEquals(original.completenessEpoch, result.completenessEpoch)
        assertEquals(original.directorySessions, result.directorySessions)
        assertEquals(original.sessionTodos, result.sessionTodos)
        assertEquals(original.sessionDiffs, result.sessionDiffs)
        assertEquals(original.sessionErrorsById, result.sessionErrorsById)
        assertEquals(original.questionAggregationSignal, result.questionAggregationSignal)
        assertEquals(original.permissionAggregationSignal, result.permissionAggregationSignal)
        assertEquals(original.pendingCreateIds, result.pendingCreateIds)
        assertEquals(original.pendingCreatedAt, result.pendingCreatedAt)
        assertEquals(original.hasCompletedInitialLoad, result.hasCompletedInitialLoad)
        assertEquals(original.abortPendingSessionIds, result.abortPendingSessionIds)
        // manual equals includes sessionStatuses, so two instances with different
        // sessionStatuses must NOT be equal
        assertNotEquals(original, result)
    }

    @Test
    fun `copy propagates sessionStatuses from receiver`() {
        val statuses = mapOf("s1" to SessionStatus("idle"))
        val src = SessionListState().withProjection(statuses)
        val copied = src.copy()
        assertEquals(statuses, copied.sessionStatuses)
    }

    @Test
    fun `copy propagates all declared constructor fields - JDK reflection guard`() {
        val session = Session(id = "s1", directory = "/work")
        val original = SessionListState(
            sessions = listOf(session),
            activeSessionIds = setOf("s1"),
            expandedSessionIds = setOf("s1"),
            loadedSessionLimit = 42,
            hasMoreSessions = false,
            isLoadingMoreSessions = true,
            isRefreshingSessions = true,
            pendingPermissions = listOf(PermissionRequest(id = "p1", sessionId = "s1")),
            pendingQuestions = listOf(QuestionRequest(id = "q1", sessionId = "s1", questions = emptyList())),
            childSessions = mapOf("root" to listOf(session)),
            completeRootIds = setOf("root"),
            completenessEpoch = 99L,
            directorySessions = mapOf("/work" to listOf(session)),
            sessionTodos = mapOf("s1" to listOf(TodoItem(content = "task", status = "pending", priority = "high", id = "t1"))),
            sessionDiffs = mapOf("s1" to listOf(FileDiff(filePath = "f1"))),
            sessionErrorsById = mapOf("s1" to SlimSessionLastError(name = "err")),
            questionAggregationSignal = SlimAggregationSignal(completeness = SlimAggregationCompleteness.INCOMPLETE, failureMessage = "err"),
            permissionAggregationSignal = SlimAggregationSignal(completeness = SlimAggregationCompleteness.FAILED, failureMessage = "err"),
            pendingCreateIds = setOf("s1"),
            pendingCreatedAt = mapOf("s1" to 1000L),
            hasCompletedInitialLoad = true,
            abortPendingSessionIds = mapOf("s1" to 1L),
        )

        val copied = original.copy()

        for (field in SessionListState::class.java.declaredFields) {
            if (field.isSynthetic) continue
            // sessionStatuses is a class-body var (not a constructor param) whose
            // propagation relies on copy().also — tested separately in
            // `copy propagates sessionStatuses from receiver`.
            if (field.name == "sessionStatuses") continue
            field.isAccessible = true
            assertEquals(
                "copy() did not propagate field '${field.name}'",
                field.get(original),
                field.get(copied),
            )
        }
    }
}
