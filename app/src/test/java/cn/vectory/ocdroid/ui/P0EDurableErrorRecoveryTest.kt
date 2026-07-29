package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §P0-E: Durable Message.error recovery — session-level pending queue + drain
 * mechanism (R9/R10 fix, B2/M5 guard).
 *
 * Covers:
 *  (a) Archive clears sessionErrorsById (R9)
 *  (b) Queue + drain (R10 silent-drop fix, B2/M5 stale-attach guard)
 *  (c) Two-phase timing marker (busy/retry→idle → pendingErrorCheck)
 *  (d) Cleanup (delete/archive clear pending maps)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class P0EDurableErrorRecoveryTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private val sid = "test-session-1"
    private val sid2 = "test-session-2"

    private val sampleError = Message.MessageError(name = "ToolError")
    private val sampleBanner = SlimSessionLastError(name = "ToolError", message = null, at = null)
    private val idleStatus = SessionStatus(type = "idle")
    private val busyStatus = SessionStatus(type = "busy")
    private val retryStatus = SessionStatus(type = "retry")

    /** Seed ChatState with a currentSessionId and optional messages. */
    private fun chatSeed(
        sessionId: String = sid,
        messages: List<Message> = emptyList(),
    ): ChatState = ChatState(
        currentSessionId = sessionId,
        messages = messages,
    )

    /** Create a minimal store with seeded ChatState. */
    private fun storeWithChat(chat: ChatState): SharedStateStore =
        SharedStateStore().apply { mutateState { it.copy(chat = chat) } }

    /** Create a minimal store with seeded ChatState + SessionListState. */
    private fun storeWithChatAndSessionList(
        chat: ChatState,
        sessionList: SessionListState = SessionListState(),
    ): SharedStateStore =
        SharedStateStore().apply { mutateState { it.copy(chat = chat, sessionList = sessionList) } }

    /** Create a test session object. */
    private fun testSession(id: String = sid, directory: String = "/test"): Session =
        Session(id = id, directory = directory)

    // ═══════════════════════════════════════════════════════════════════════
    // (a) Archive clears sessionErrorsById (R9)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `archive clears sessionErrorsById for the archived session (R9)`() {
        val ses = testSession()
        val prior = SessionListState(
            sessionErrorsById = mapOf(
                sid to sampleBanner,
                sid2 to SlimSessionLastError(name = "Other", message = null, at = null),
            ),
            sessions = listOf(ses),
        )
        val store = SharedStateStore().apply { mutateState { it.copy(sessionList = prior) } }

        store.dispatch(AppAction.SessionArchivedLocal(
            session = ses,
            pendingQuestions = emptyList(),
            activeSessionIdsToRemove = emptySet(),
        ))

        val out = store.stateFlow.value.sessionList.sessionErrorsById
        assertFalse("archived session error cleared", out.containsKey(sid))
        assertEquals("other session's error preserved", "Other", out[sid2]?.name)
        assertEquals("preserved session count", 1, out.size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // (b) Queue + drain
    // ═══════════════════════════════════════════════════════════════════════

    // ── b1: route-mismatch → queue ──────────────────────────────────────

    @Test
    fun `route mismatch queues error in pendingErrorReattach and sets pendingErrorCheck`() {
        // Set up a state where chatRouteInstance != expectedRouteInstance,
        // so acceptsRouteUpdate returns false for a non-zero token.
        val chat = chatSeed(messages = listOf(Message(id = "m1", role = "assistant")))
        val store = SharedStateStore().apply {
            mutateState {
                it.copy(
                    chat = chat,
                    chatRouteInstance = 1L,
                )
            }
        }

        // Dispatch with mismatching routeInstance (2L != 1L)
        store.dispatch(AppAction.LastAssistantErrorAttached(
            error = sampleError,
            expectedRouteInstance = 2L,
            sessionId = sid,
        ))

        val out = store.stateFlow.value.chat
        assertTrue("pendingErrorReattach contains sid", out.pendingErrorReattach.containsKey(sid))
        assertTrue("pendingErrorCheck contains sid", out.pendingErrorCheck.contains(sid))
        val queued = out.pendingErrorReattach[sid]
        assertNotNull("queued error exists", queued)
        assertEquals("queued error matches", sampleError.name, queued!!.error.name)
        assertEquals("route instance captured", 2L, queued.routeInstance)
        // Messages unchanged (no attach).
        assertNull("last assistant error NOT set (no attach on mismatch)",
            out.messages.last().error)
    }

    // ── b2: route return → drain → attach ───────────────────────────────

    @Test
    fun `drain attaches queued error on route return via MessagesMerged`() = runTest {
        val lastAssistant = Message(id = "m2", role = "assistant")
        val chat = ChatState(
            currentSessionId = sid,
            messages = listOf(Message(id = "m1", role = "user"), lastAssistant),
            pendingErrorReattach = mapOf(sid to PendingChatError(
                error = sampleError,
                routeInstance = 1L,
                messageAssistantId = "m2",
            )),
            pendingErrorCheck = setOf(sid),
        )
        val store = SharedStateStore().apply {
            mutateState { it.copy(chat = chat, chatRouteInstance = 1L) }
        }

        // Dispatch MessagesMerged — this triggers drain.
        store.dispatch(AppAction.MessagesMerged(
            messages = listOf(Message(id = "m1", role = "user"), lastAssistant),
            partsByMessage = emptyMap(),
            streamingPartTexts = emptyMap(),
            streamingReasoningPart = null,
            olderMessagesCursor = null,
            hasMoreMessages = false,
            currentModel = null,
        ))

        val out = store.stateFlow.value.chat
        assertFalse("pendingErrorReattach cleared after drain", out.pendingErrorReattach.containsKey(sid))
        assertFalse("pendingErrorCheck cleared after drain", out.pendingErrorCheck.contains(sid))
        assertEquals("error attached to last assistant", sampleError, out.messages.last { it.isAssistant }.error)
    }

    // ── b3: last==null → refresh → drain → attach ───────────────────────

    @Test
    fun `drain attaches error when assistant appears after queue`() = runTest {
        // Queue with route matching but NO assistant yet.
        val chat = ChatState(
            currentSessionId = sid,
            messages = listOf(Message(id = "m1", role = "user")),
            pendingErrorReattach = mapOf(sid to PendingChatError(
                error = sampleError,
                routeInstance = 1L,
                messageAssistantId = null,  // no assistant when queued
            )),
            pendingErrorCheck = setOf(sid),
        )
        val store = SharedStateStore().apply {
            mutateState { it.copy(chat = chat, chatRouteInstance = 1L) }
        }

        val newAssistant = Message(id = "m2", role = "assistant")
        store.dispatch(AppAction.MessagesMerged(
            messages = listOf(Message(id = "m1", role = "user"), newAssistant),
            partsByMessage = emptyMap(),
            streamingPartTexts = emptyMap(),
            streamingReasoningPart = null,
            olderMessagesCursor = null,
            hasMoreMessages = false,
            currentModel = null,
        ))

        val out = store.stateFlow.value.chat
        assertFalse("pendingErrorReattach cleared", out.pendingErrorReattach.containsKey(sid))
        assertEquals("error attached to new assistant", sampleError,
            out.messages.last { it.isAssistant }.error)
    }

    // ── b4: demote-to-banner (last assistant already has an error) ───────

    @Test
    fun `drain demotes queued error to session banner when last assistant already has an error`() = runTest {
        val existingError = Message.MessageError(name = "ExistingError")
        val lastAssistant = Message(id = "m2", role = "assistant", error = existingError)
        val chat = ChatState(
            currentSessionId = sid,
            messages = listOf(Message(id = "m1", role = "user"), lastAssistant),
            pendingErrorReattach = mapOf(sid to PendingChatError(
                error = Message.MessageError(name = "NewQueuedError"),
                routeInstance = 1L,
                messageAssistantId = "m2",
            )),
            pendingErrorCheck = setOf(sid),
        )
        val store = SharedStateStore().apply {
            mutateState { it.copy(chat = chat, chatRouteInstance = 1L) }
        }

        store.dispatch(AppAction.MessagesMerged(
            messages = listOf(Message(id = "m1", role = "user"), lastAssistant),
            partsByMessage = emptyMap(),
            streamingPartTexts = emptyMap(),
            streamingReasoningPart = null,
            olderMessagesCursor = null,
            hasMoreMessages = false,
            currentModel = null,
        ))

        val outChat = store.stateFlow.value.chat
        val outSessionErrors = store.stateFlow.value.sessionList.sessionErrorsById

        // Existing error preserved (not overwritten).
        assertEquals("existing assistant error preserved", existingError,
            outChat.messages.last { it.isAssistant }.error)
        // Banner set for the queued error.
        assertTrue("sessionErrorsById contains sid for banner", outSessionErrors.containsKey(sid))
        assertEquals("banner name reflects queued error", "NewQueuedError",
            outSessionErrors[sid]?.name)
        // Pending maps cleared.
        assertFalse("pendingErrorReattach cleared", outChat.pendingErrorReattach.containsKey(sid))
        assertFalse("pendingErrorCheck cleared", outChat.pendingErrorCheck.contains(sid))
    }

    // ── b5: bounded LRU ─────────────────────────────────────────────────

    @Test
    fun `pendingErrorReattach is bounded to PENDING_ERROR_REATTACH_MAX (LRU eviction)`() {
        val chat = ChatState(currentSessionId = sid)
        val store = SharedStateStore().apply {
            mutateState { it.copy(chat = chat, chatRouteInstance = 1L) }
        }

        // Insert PENDING_ERROR_REATTACH_MAX + 5 distinct sids.
        val overflow = PENDING_ERROR_REATTACH_MAX + 5
        for (i in 1..overflow) {
            val id = "session-$i"
            store.dispatch(AppAction.LastAssistantErrorAttached(
                error = sampleError,
                expectedRouteInstance = 2L,
                sessionId = id,
            ))
        }

        val size = store.stateFlow.value.chat.pendingErrorReattach.size
        assertTrue("pendingErrorReattach size <= $PENDING_ERROR_REATTACH_MAX (was $size)",
            size <= PENDING_ERROR_REATTACH_MAX)
        // The oldest entries should have been evicted; the newest should remain.
        // First inserted "session-1" should be evicted.
        assertFalse("oldest entry evicted", store.stateFlow.value.chat.pendingErrorReattach.containsKey("session-1"))
    }

    // ── b6: messageAssistantId mismatch → demote to banner ──────────────

    @Test
    fun `drain demotes to banner when messageAssistantId does not match current last assistant (B2 guard)`() = runTest {
        val oldAssistant = Message(id = "m2-old", role = "assistant")
        val newAssistant = Message(id = "m3-new", role = "assistant")
        val chat = ChatState(
            currentSessionId = sid,
            messages = listOf(Message(id = "m1", role = "user"), newAssistant),
            pendingErrorReattach = mapOf(sid to PendingChatError(
                error = sampleError,
                routeInstance = 1L,
                messageAssistantId = "m2-old",  // doesn't match current last "m3-new"
            )),
            pendingErrorCheck = setOf(sid),
        )
        val store = SharedStateStore().apply {
            mutateState { it.copy(chat = chat, chatRouteInstance = 1L) }
        }

        store.dispatch(AppAction.MessagesMerged(
            messages = listOf(Message(id = "m1", role = "user"), newAssistant),
            partsByMessage = emptyMap(),
            streamingPartTexts = emptyMap(),
            streamingReasoningPart = null,
            olderMessagesCursor = null,
            hasMoreMessages = false,
            currentModel = null,
        ))

        val outChat = store.stateFlow.value.chat
        val outSessionErrors = store.stateFlow.value.sessionList.sessionErrorsById

        // New assistant NOT touched.
        assertNull("new assistant NOT given stale error", outChat.messages.last { it.isAssistant }.error)
        // Banner set.
        assertTrue("banner set for mismatched assistant id", outSessionErrors.containsKey(sid))
        assertFalse("pendingErrorReattach cleared", outChat.pendingErrorReattach.containsKey(sid))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // (c) Two-phase timing marker
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `busy to idle transition marks pendingErrorCheck for current session`() {
        val store = SharedStateStore().apply {
            mutateState {
                it.copy(
                    chat = ChatState(currentSessionId = sid),
                    sessionList = SessionListState(
                        sessionStatuses = mapOf(sid to busyStatus),
                    ),
                )
            }
        }

        store.dispatch(AppAction.SessionStatusPatched(
            sessionId = sid,
            updatedTimestamp = 1000L,
            status = idleStatus,
        ))

        assertTrue("pendingErrorCheck contains sid after busy→idle",
            store.stateFlow.value.chat.pendingErrorCheck.contains(sid))
    }

    @Test
    fun `retry to idle transition marks pendingErrorCheck for current session`() {
        val store = SharedStateStore().apply {
            mutateState {
                it.copy(
                    chat = ChatState(currentSessionId = sid),
                    sessionList = SessionListState(
                        sessionStatuses = mapOf(sid to retryStatus),
                    ),
                )
            }
        }

        store.dispatch(AppAction.SessionStatusPatched(
            sessionId = sid,
            updatedTimestamp = 1000L,
            status = idleStatus,
        ))

        assertTrue("pendingErrorCheck contains sid after retry→idle",
            store.stateFlow.value.chat.pendingErrorCheck.contains(sid))
    }

    @Test
    fun `idle to busy does NOT mark pendingErrorCheck`() {
        val store = SharedStateStore().apply {
            mutateState {
                it.copy(
                    chat = ChatState(currentSessionId = sid),
                    sessionList = SessionListState(
                        sessionStatuses = mapOf(sid to idleStatus),
                    ),
                )
            }
        }

        store.dispatch(AppAction.SessionStatusPatched(
            sessionId = sid,
            updatedTimestamp = 1000L,
            status = busyStatus,
        ))

        assertFalse("pendingErrorCheck NOT set for idle→busy",
            store.stateFlow.value.chat.pendingErrorCheck.contains(sid))
    }

    @Test
    fun `busy to idle for non-current session does NOT mark pendingErrorCheck`() {
        val store = SharedStateStore().apply {
            mutateState {
                it.copy(
                    chat = ChatState(currentSessionId = "other-session"),
                    sessionList = SessionListState(
                        sessionStatuses = mapOf(sid to busyStatus),
                    ),
                )
            }
        }

        store.dispatch(AppAction.SessionStatusPatched(
            sessionId = sid,
            updatedTimestamp = 1000L,
            status = idleStatus,
        ))

        assertFalse("pendingErrorCheck NOT set for non-current session",
            store.stateFlow.value.chat.pendingErrorCheck.contains(sid))
    }

    @Test
    fun `drain clears pendingErrorCheck when last assistant gains error`() = runTest {
        val lastAssistant = Message(id = "m2", role = "assistant")
        val chat = ChatState(
            currentSessionId = sid,
            messages = listOf(Message(id = "m1", role = "user"), lastAssistant),
            pendingErrorReattach = mapOf(sid to PendingChatError(
                error = sampleError,
                routeInstance = 1L,
                messageAssistantId = "m2",
            )),
            pendingErrorCheck = setOf(sid),
        )
        val store = SharedStateStore().apply {
            mutateState { it.copy(chat = chat, chatRouteInstance = 1L) }
        }

        // MessageUpdatedApplied triggers drain + clear pendingErrorCheck.
        store.dispatch(AppAction.MessageUpdatedApplied(
            message = lastAssistant,
            expectedRouteInstance = 1L,
            sessionId = sid,
        ))

        assertFalse("pendingErrorCheck cleared after drain via MessageUpdatedApplied",
            store.stateFlow.value.chat.pendingErrorCheck.contains(sid))
        assertEquals("error attached to assistant via drained attach",
            sampleError, store.stateFlow.value.chat.messages.last { it.isAssistant }.error)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // (d) Cleanup
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `delete clears pendingErrorReattach and pendingErrorCheck for deleted ids`() {
        val chat = ChatState(
            currentSessionId = sid,
            pendingErrorReattach = mapOf(
                sid to PendingChatError(sampleError, 0L, null),
                sid2 to PendingChatError(sampleError, 0L, null),
            ),
            pendingErrorCheck = setOf(sid, sid2),
        )
        val sessionList = SessionListState(
            sessions = listOf(testSession(sid), testSession(sid2)),
        )
        val store = storeWithChatAndSessionList(chat, sessionList)

        // Delete sid2 only.
        store.dispatch(AppAction.SessionDeletedLocal(removedIds = setOf(sid2)))

        val outChat = store.stateFlow.value.chat
        assertTrue("sid's errorReattach preserved (not deleted)", outChat.pendingErrorReattach.containsKey(sid))
        assertFalse("sid2's errorReattach cleared", outChat.pendingErrorReattach.containsKey(sid2))
        assertTrue("sid's pendingErrorCheck preserved", outChat.pendingErrorCheck.contains(sid))
        assertFalse("sid2's pendingErrorCheck cleared", outChat.pendingErrorCheck.contains(sid2))
    }

    @Test
    fun `archive clears pendingErrorReattach and pendingErrorCheck for archived id`() {
        val chat = ChatState(
            currentSessionId = sid,
            pendingErrorReattach = mapOf(
                sid to PendingChatError(sampleError, 0L, null),
                sid2 to PendingChatError(sampleError, 0L, null),
            ),
            pendingErrorCheck = setOf(sid, sid2),
        )
        val ses = testSession()
        val sessionList = SessionListState(sessions = listOf(ses))
        val store = storeWithChatAndSessionList(chat, sessionList)

        store.dispatch(AppAction.SessionArchivedLocal(
            session = ses,
            pendingQuestions = emptyList(),
            activeSessionIdsToRemove = emptySet(),
        ))

        val outChat = store.stateFlow.value.chat
        assertFalse("archived sid's errorReattach cleared", outChat.pendingErrorReattach.containsKey(sid))
        assertTrue("non-archived sid2 errorReattach preserved", outChat.pendingErrorReattach.containsKey(sid2))
        assertFalse("archived sid's pendingErrorCheck cleared", outChat.pendingErrorCheck.contains(sid))
        assertTrue("non-archived sid2 pendingErrorCheck preserved", outChat.pendingErrorCheck.contains(sid2))
    }

    @Test
    fun `HostStatePurged clears pendingErrorReattach and pendingErrorCheck via clearSessionData`() {
        val chat = ChatState(
            currentSessionId = sid,
            pendingErrorReattach = mapOf(sid to PendingChatError(sampleError, 0L, null)),
            pendingErrorCheck = setOf(sid),
        )
        val store = storeWithChat(chat)

        store.dispatch(AppAction.HostStatePurged(preserveServerGroupData = false))

        val outChat = store.stateFlow.value.chat
        assertTrue("pendingErrorReattach cleared by HostStatePurged",
            outChat.pendingErrorReattach.isEmpty())
        assertTrue("pendingErrorCheck cleared by HostStatePurged",
            outChat.pendingErrorCheck.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Legacy happy-path preservation
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `happy path LastAssistantErrorAttached byte-for-byte compatible with legacy behaviour`() = runTest {
        val lastAssistant = Message(id = "m2", role = "assistant")
        val seed = listOf(Message(id = "m1", role = "user"), lastAssistant)
        val error = Message.MessageError(name = "ToolError", data = null)
        val oldStore = SharedStateStore().apply {
            mutateState {
                it.copy(chat = ChatState(currentSessionId = sid, messages = seed))
            }
        }
        val newStore = SharedStateStore().apply {
            mutateState {
                it.copy(chat = ChatState(currentSessionId = sid, messages = seed))
            }
        }

        // Old path: replicate SSC:1706-1712 mutateChat verbatim.
        oldStore.mutateChat { c ->
            val la = c.messages.lastOrNull { it.isAssistant }
            if (la == null || la.error != null) c
            else c.copy(messages = c.messages.map { m ->
                if (m.id == la.id) m.copy(error = error) else m
            })
        }
        // New path.
        newStore.dispatch(AppAction.LastAssistantErrorAttached(
            error = error,
            expectedRouteInstance = 0L,
            sessionId = sid,
        ))

        assertEquals(
            "LastAssistantErrorAttached (happy path, routeInstance=0) MUST equal legacy",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
    }

    @Test
    fun `LastAssistantErrorAttached is a no-op when sessionId is null`() {
        val prior = StoreState.initial().copy(chat = ChatState(currentSessionId = sid))
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(AppAction.LastAssistantErrorAttached(
            error = sampleError,
            expectedRouteInstance = 0L,
            sessionId = null,
        ))

        assertEquals("null sessionId → no-op (state unchanged)", prior, store.stateFlow.value)
    }
}
