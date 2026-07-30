package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.Session
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
 * §P0-E NARROWED: covers R9 (archive clears sessionErrorsById) + pending
 * producer scaffolding + cleanup hygiene. The (b) drain/consumer and (c)
 * two-phase marker are DEFERRED to a post-P0-A task (need GET/controller +
 * authority status writer); their tests are removed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class P0EDurableErrorRecoveryTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private val sid = "test-session-1"
    private val sid2 = "test-session-2"
    private val sid3 = "test-session-3"

    private val sampleError = Message.MessageError(name = "ToolError")
    private val sampleBanner = SlimSessionLastError(name = "ToolError", message = null, at = null)

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
    // (b) Producer scaffolding — pendingErrorReattach recording only
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `route mismatch records error in pendingErrorReattach (producer scaffolding)`() {
        // Set up a state where chatRouteInstance != expectedRouteInstance,
        // so acceptsRouteUpdate returns false for a non-zero token.
        val chat = ChatState(
            currentSessionId = sid,
            messages = listOf(Message(id = "m1", role = "assistant")),
        )
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
        // Producer scaffolding: payload recorded but never attached.
        assertTrue("pendingErrorReattach contains sid", out.pendingErrorReattach.containsKey(sid))
        val queued = out.pendingErrorReattach[sid]
        assertNotNull("queued error exists", queued)
        assertEquals("queued error matches", sampleError.name, queued!!.error.name)
        assertEquals("route instance captured", 2L, queued.routeInstance)
        // Messages unchanged (no attach — drain/consumer deferred to post-P0-A).
        assertNull("last assistant error NOT set (no attach on mismatch)",
            out.messages.last().error)
    }

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
        assertFalse("oldest entry evicted", store.stateFlow.value.chat.pendingErrorReattach.containsKey("session-1"))
    }

    @Test
    fun `happy path route match attaches error directly (byte-for-byte legacy)`() = runTest {
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
        // New path (routeInstance=0, sid non-null → acceptsRouteUpdate returns true).
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

    // ═══════════════════════════════════════════════════════════════════════
    // Cleanup hygiene: delete/archive/host-purge / SSE archive
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
    fun `SSE archive path clears pendingErrorReattach and pendingErrorCheck for archived subtree`() {
        // The SSE archive reducer (reduceSessionArchived, CrossSliceFieldsReducer)
        // cleans the full subtree. Seed two sessions that form a parent-child tree.
        val parentSid = "parent"
        val childSid = "child"
        val parentSes = Session(id = parentSid, directory = "/parent")
        val childSes = Session(id = childSid, directory = "/child", parentId = parentSid)
        val chat = ChatState(
            currentSessionId = sid,  // unrelated current session
            pendingErrorReattach = mapOf(
                parentSid to PendingChatError(sampleError, 1L, null),
                childSid to PendingChatError(sampleError, 1L, null),
                sid2 to PendingChatError(sampleError, 1L, null), // not in subtree
            ),
            pendingErrorCheck = setOf(parentSid, childSid, sid2),
        )
        val prior = StoreState.initial().copy(
            chat = chat,
            sessionList = SessionListState(
                sessions = listOf(parentSes, childSes),
                childSessions = mapOf(parentSid to listOf(childSes)),
            ),
        )
        // Use the direct reduce() call to test the SSE archive path.
        val out = reduce(prior, AppAction.SessionArchived(parentSes))

        val outChat = out.chat
        assertFalse("parent sid cleared from pendingErrorReattach",
            outChat.pendingErrorReattach.containsKey(parentSid))
        assertFalse("child sid cleared from pendingErrorReattach",
            outChat.pendingErrorReattach.containsKey(childSid))
        assertTrue("non-archived sid2 preserved in pendingErrorReattach",
            outChat.pendingErrorReattach.containsKey(sid2))
        assertFalse("parent sid cleared from pendingErrorCheck",
            outChat.pendingErrorCheck.contains(parentSid))
        assertFalse("child sid cleared from pendingErrorCheck",
            outChat.pendingErrorCheck.contains(childSid))
        assertTrue("non-archived sid2 preserved in pendingErrorCheck",
            outChat.pendingErrorCheck.contains(sid2))
    }

    @Test
    fun `bulk archive clears pendingErrorReattach and pendingErrorCheck for the archived subtree`() {
        // reduceBulkSessionsRefreshed reuses allArchivedSubtree to clear the
        // pending maps. Seed a parent(archived)+child subtree + an unrelated
        // session, mirroring the SSE-subtree test above.
        val parentSid = "parent"
        val childSid = "child"
        val parentSes = Session(id = parentSid, directory = "/parent",
            time = Session.TimeInfo(archived = 1_000))  // archived
        val childSes = Session(id = childSid, directory = "/child", parentId = parentSid)
        val otherSes = Session(id = sid2, directory = "/other")
        val chat = ChatState(
            currentSessionId = sid,  // unrelated current session
            pendingErrorReattach = mapOf(
                parentSid to PendingChatError(sampleError, 1L, null),
                childSid to PendingChatError(sampleError, 1L, null),
                sid2 to PendingChatError(sampleError, 1L, null), // not in subtree
            ),
            pendingErrorCheck = setOf(parentSid, childSid, sid2),
        )
        val prior = StoreState.initial().copy(
            chat = chat,
            sessionList = SessionListState(
                sessions = listOf(parentSes, childSes, otherSes),
                childSessions = mapOf(parentSid to listOf(childSes)),
            ),
        )
        // Direct reduce() call: BulkSessionsRefreshed with parent archived.
        val out = reduce(prior, AppAction.BulkSessionsRefreshed(
            sessions = listOf(parentSes, childSes, otherSes),
            hasMoreSessions = false,
            confirmedServerIds = setOf(parentSid, childSid, sid2),
            sweepNow = 0L,
        ))

        val outChat = out.chat
        assertFalse("parent sid cleared from pendingErrorReattach",
            outChat.pendingErrorReattach.containsKey(parentSid))
        assertFalse("child sid cleared from pendingErrorReattach (subtree)",
            outChat.pendingErrorReattach.containsKey(childSid))
        assertTrue("non-archived sid2 preserved in pendingErrorReattach",
            outChat.pendingErrorReattach.containsKey(sid2))
        assertFalse("parent sid cleared from pendingErrorCheck",
            outChat.pendingErrorCheck.contains(parentSid))
        assertFalse("child sid cleared from pendingErrorCheck (subtree)",
            outChat.pendingErrorCheck.contains(childSid))
        assertTrue("non-archived sid2 preserved in pendingErrorCheck",
            outChat.pendingErrorCheck.contains(sid2))
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
}
