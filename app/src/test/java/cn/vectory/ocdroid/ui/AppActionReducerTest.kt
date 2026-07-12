package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.ui.controller.SeedFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §A5-3 Phase B2: validates the transactional [AppAction] / [reduce] /
 * [SharedStateStore.dispatch] layer — the atomicity mechanism that replaces
 * the pre-B2 scattered `mutateXxx` / `writeXxx` sequences at the four
 * multi-write sites (materializeDraftSession / session.updated archived SSE
 * / purgePerHostState / createSessionInWorkdirForEffect).
 *
 * Three test groups:
 *
 *  1. **Pure reducer tests** — `reduce(snapshot, action)` returns a new
 *     [StoreState] with EXACTLY the field changes the corresponding pre-B2
 *     site performed, no more no less. Asserted field-by-field. Each action
 *     has at least one positive + one negative branch where relevant
 *     (archive current vs non-current; host same-group vs cross-group).
 *
 *  2. **Atomicity tests** — collect [SharedStateStore.stateFlow] (the B2
 *     aggregate); dispatch an [AppAction]; assert the aggregate emission
 *     stream shows NO torn intermediate state and EXACTLY ONE emission for
 *     the action (the pre-B2 scattering produced N intermediate committed
 *     states per logical transition; B2 collapses them to one).
 *
 *  3. **Projection-consistency** — a per-slice collector (sessionListFlow)
 *     that fires AFTER a dispatch reads chatFlow.value and finds it ALREADY
 *     updated in the SAME committed state (no lag, no separate hop). This
 *     is the cross-slice consistency guarantee the four migrated sites now
 *     lean on.
 *
 * Purity contract (the B2 gate): the reducer MUST be pure — no effects,
 * no settings writes, no network, no emit. The reducer tests instantiate
 * [StoreState] directly + assert the returned [StoreState]; nothing else
 * is touched (no SharedStateStore, no SettingsManager, no effects).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppActionReducerTest {

    // ── 1. Pure reducer tests ──────────────────────────────────────────────

    // ── DraftSessionMaterialized ───────────────────────────────────────────

    @Test
    fun `reduce DraftSessionMaterialized upserts session into sessionList and sets openSessionIds`() {
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(Session(id = "old", directory = "/old")),
                openSessionIds = listOf("old"),
            ),
        )
        val created = Session(id = "new", directory = "/proj")

        val out = reduce(prior, AppAction.DraftSessionMaterialized(created, openSessionIds = listOf("new", "old"), viewedAt = 123L))

        // Upsert: the new session is at the head; the old one survives (not replaced).
        assertEquals("new", out.sessionList.sessions.first().id)
        assertEquals(2, out.sessionList.sessions.size)
        assertTrue(out.sessionList.sessions.any { it.id == "old" })
        assertEquals(listOf("new", "old"), out.sessionList.openSessionIds)
    }

    @Test
    fun `reduce DraftSessionMaterialized sets chat currentSessionId to the new session id`() {
        val prior = StoreState.initial().copy(
            chat = ChatState(currentSessionId = "old-session"),
        )
        val created = Session(id = "fresh", directory = "/p")

        val out = reduce(prior, AppAction.DraftSessionMaterialized(created, listOf("fresh"), 0L))

        assertEquals("fresh", out.chat.currentSessionId)
    }

    @Test
    fun`reduce DraftSessionMaterialized clears session id from unread and bumps lastViewedTime`() {
        val prior = StoreState.initial().copy(
            unread = UnreadState(
                unreadSessions = setOf("new", "other"),
                lastViewedTime = mapOf("other" to 5L),
            ),
        )
        val created = Session(id = "new", directory = "/p")

        val out = reduce(prior, AppAction.DraftSessionMaterialized(created, listOf("new"), viewedAt = 999L))

        // "new" removed from unread; "other" untouched.
        assertFalse("new" in out.unread.unreadSessions)
        assertTrue("other" in out.unread.unreadSessions)
        // lastViewedTime for "new" set to viewedAt; "other" preserved.
        assertEquals(999L, out.unread.lastViewedTime["new"])
        assertEquals(5L, out.unread.lastViewedTime["other"])
    }

    @Test
    fun `reduce DraftSessionMaterialized clears composer draftWorkdir`() {
        val prior = StoreState.initial().copy(
            composer = ComposerState(draftWorkdir = "/draft-path", inputText = "stale"),
        )
        val created = Session(id = "n", directory = "/p")

        val out = reduce(prior, AppAction.DraftSessionMaterialized(created, listOf("n"), 0L))

        assertNull(out.composer.draftWorkdir)
        // inputText preserved — materializeDraftSession only clears draftWorkdir.
        assertEquals("stale", out.composer.inputText)
    }

    @Test
    fun `reduce DraftSessionMaterialized does not touch unrelated slices`() {
        val priorConnection = ConnectionState(isConnected = true, serverVersion = "1.0")
        val priorSettings = SettingsState(availableCommands = listOf(CommandInfo("cmd")))
        val prior = StoreState.initial().copy(
            connection = priorConnection,
            settings = priorSettings,
        )
        val created = Session(id = "n", directory = "/p")

        val out = reduce(prior, AppAction.DraftSessionMaterialized(created, listOf("n"), 0L))

        // Slices the action does NOT touch are reference-equal (data-class copy leaves
        // the non-target fields untouched).
        assertSame(priorConnection, out.connection)
        assertSame(priorSettings, out.settings)
    }

    // ── SessionArchived ────────────────────────────────────────────────────

    @Test
    fun `reduce SessionArchived upserts archived session and replaces openSessionIds`() {
        val archived = Session(
            id = "sess-1",
            directory = "/p",
            time = Session.TimeInfo(archived = 1_700_000_000_000),
        )
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(Session(id = "sess-1", directory = "/p"), Session(id = "sess-2", directory = "/q")),
                openSessionIds = listOf("sess-1", "sess-2"),
                directorySessions = mapOf("/p" to listOf(Session(id = "sess-1", directory = "/p"))),
            ),
        )

        val out = reduce(prior, AppAction.SessionArchived(archived, openSessionIds = listOf("sess-2")))

        // The archived session is upserted (id-stable replace).
        assertTrue(out.sessionList.sessions.any { it.id == "sess-1" && it.isArchived })
        // openSessionIds replaced (sess-1 evicted).
        assertEquals(listOf("sess-2"), out.sessionList.openSessionIds)
        // directorySessions entries for the archived id are also updated (mirror of applyArchiveEviction).
        val dirEntry = out.sessionList.directorySessions["/p"]?.singleOrNull()
        assertNotNull(dirEntry)
        assertEquals("sess-1", dirEntry!!.id)
        assertTrue(dirEntry.isArchived)
    }

    @Test
    fun `reduce SessionArchived clears chat when archived session IS the current one`() {
        val archived = Session(id = "cur", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "cur",
                messages = listOf(Message(id = "m1", role = "user")),
                partsByMessage = mapOf("m1" to emptyList()),
            ),
            sessionList = SessionListState(openSessionIds = listOf("cur")),
        )

        val out = reduce(prior, AppAction.SessionArchived(archived, openSessionIds = emptyList()))

        assertNull(out.chat.currentSessionId)
        assertTrue(out.chat.messages.isEmpty())
        assertTrue(out.chat.partsByMessage.isEmpty())
    }

    @Test
    fun `reduce SessionArchived does NOT clear chat when archived session is NOT the current one`() {
        val archived = Session(id = "other", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "cur",
                messages = listOf(Message(id = "m1", role = "user")),
                partsByMessage = mapOf("m1" to emptyList()),
            ),
            sessionList = SessionListState(openSessionIds = listOf("cur", "other")),
        )

        val out = reduce(prior, AppAction.SessionArchived(archived, openSessionIds = listOf("cur")))

        // chat untouched — still pointing at "cur".
        assertEquals("cur", out.chat.currentSessionId)
        assertEquals(1, out.chat.messages.size)
        assertEquals(1, out.chat.partsByMessage.size)
    }

    // ── HostStatePurged (cross-group = full purge) ─────────────────────────

    @Test
    fun `reduce HostStatePurged cross-group clears chat fields except the 3 chat-only fields`() {
        // The three ChatState-only fields documented at HostProfileController.kt:475-479:
        // isCompacting, compactStartedAt, refreshNonce. They MUST survive a purge (a
        // fresh ChatState() would clobber them; the reducer uses .copy()).
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "sess-old",
                messages = listOf(Message(id = "m1", role = "user")),
                partsByMessage = mapOf("m1" to emptyList()),
                streamingPartTexts = mapOf("p1" to "delta"),
                streamingReasoningPart = Part(id = "p1", type = "reasoning", text = "r"),
                isLoadingMessages = true,
                isLoadingMoreMessages = true,
                // The 3 chat-only fields — MUST be preserved.
                isCompacting = true,
                compactStartedAt = 42L,
                refreshNonce = 7L,
            ),
        )

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = false))

        // Cleared (AppState-represented chat fields):
        assertNull(out.chat.currentSessionId)
        assertTrue(out.chat.messages.isEmpty())
        assertTrue(out.chat.partsByMessage.isEmpty())
        assertTrue(out.chat.streamingPartTexts.isEmpty())
        assertNull(out.chat.streamingReasoningPart)
        assertFalse(out.chat.isLoadingMessages)
        assertFalse(out.chat.isLoadingMoreMessages)
        // PRESERVED (the 3 chat-only fields):
        assertTrue(out.chat.isCompacting)
        assertEquals(42L, out.chat.compactStartedAt)
        assertEquals(7L, out.chat.refreshNonce)
    }

    @Test
    fun `reduce HostStatePurged cross-group clears sessionList + unread + per-profile UX`() {
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(Session(id = "s1", directory = "/p")),
                directorySessions = mapOf("/p" to listOf(Session(id = "s1", directory = "/p"))),
                openSessionIds = listOf("s1"),
                sessionStatuses = mapOf("s1" to cn.vectory.ocdroid.data.model.SessionStatus("idle")),
                sessionTodos = mapOf("s1" to listOf(TodoItem(content = "t", status = "pending", priority = "normal", id = "t1"))),
                sessionDiffs = mapOf("s1" to emptyList()),
            ),
            unread = UnreadState(
                unreadSessions = setOf("s1"),
                tempClearedUnread = setOf("s1"),
                lastViewedTime = mapOf("s1" to 1L),
            ),
            composer = ComposerState(draftWorkdir = "/old/proj"),
            settings = SettingsState(availableCommands = listOf(CommandInfo("cmd"))),
            connection = ConnectionState(serverVersion = "1.2.3"),
        )

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = false))

        // sessionList fully cleared.
        assertTrue(out.sessionList.sessions.isEmpty())
        assertTrue(out.sessionList.directorySessions.isEmpty())
        assertTrue(out.sessionList.openSessionIds.isEmpty())
        assertTrue(out.sessionList.sessionStatuses.isEmpty())
        assertTrue(out.sessionList.sessionTodos.isEmpty())
        assertTrue(out.sessionList.sessionDiffs.isEmpty())
        // unread fully cleared.
        assertTrue(out.unread.unreadSessions.isEmpty())
        assertTrue(out.unread.tempClearedUnread.isEmpty())
        assertTrue(out.unread.lastViewedTime.isEmpty())
        // per-profile UX always reset.
        assertNull(out.composer.draftWorkdir)
        assertTrue(out.settings.availableCommands.isEmpty())
        assertNull(out.connection.serverVersion)
    }

    // ── HostStatePurged (same-group = preserve server data) ────────────────

    @Test
    fun `reduce HostStatePurged same-group preserves sessions + unread + directorySessions`() {
        val session = Session(id = "s1", directory = "/p")
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(session),
                directorySessions = mapOf("/p" to listOf(session)),
                openSessionIds = listOf("s1"),
                sessionStatuses = mapOf("s1" to cn.vectory.ocdroid.data.model.SessionStatus("idle")),
                sessionTodos = mapOf("s1" to listOf(TodoItem(content = "t", status = "pending", priority = "normal", id = "t1"))),
                sessionDiffs = mapOf("s1" to emptyList()),
            ),
            unread = UnreadState(
                unreadSessions = setOf("s1"),
                lastViewedTime = mapOf("s1" to 1L),
            ),
        )

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = true))

        // Server data PRESERVED (same-group = same server = identical data).
        assertEquals(listOf(session), out.sessionList.sessions)
        assertEquals(mapOf("/p" to listOf(session)), out.sessionList.directorySessions)
        assertEquals(listOf("s1"), out.sessionList.openSessionIds)
        assertEquals(1, out.sessionList.sessionStatuses.size)
        assertEquals(1, out.sessionList.sessionTodos.size)
        assertEquals(1, out.sessionList.sessionDiffs.size)
        assertEquals(setOf("s1"), out.unread.unreadSessions)
        assertEquals(mapOf("s1" to 1L), out.unread.lastViewedTime)
    }

    @Test
    fun `reduce HostStatePurged same-group clears only chat streaming fields and per-profile UX`() {
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "sess-keep",  // PRESERVED (current session window valid same-group)
                messages = listOf(Message(id = "m1", role = "user")),  // PRESERVED
                partsByMessage = mapOf("m1" to emptyList()),  // PRESERVED
                streamingPartTexts = mapOf("p1" to "delta"),  // CLEARED
                streamingReasoningPart = Part(id = "p1", type = "reasoning", text = "r"),  // CLEARED
                isCompacting = true,  // chat-only field — PRESERVED
            ),
            composer = ComposerState(draftWorkdir = "/old/proj"),  // per-profile — CLEARED
            settings = SettingsState(availableCommands = listOf(CommandInfo("cmd"))),  // CLEARED
            connection = ConnectionState(serverVersion = "1.2.3"),  // CLEARED
        )

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = true))

        // Streaming-only fields cleared.
        assertTrue(out.chat.streamingPartTexts.isEmpty())
        assertNull(out.chat.streamingReasoningPart)
        // chat content + current session PRESERVED.
        assertEquals("sess-keep", out.chat.currentSessionId)
        assertEquals(1, out.chat.messages.size)
        assertEquals(1, out.chat.partsByMessage.size)
        // chat-only field preserved.
        assertTrue(out.chat.isCompacting)
        // per-profile UX always reset (regardless of group).
        assertNull(out.composer.draftWorkdir)
        assertTrue(out.settings.availableCommands.isEmpty())
        assertNull(out.connection.serverVersion)
    }

    // ── WorkdirDraftStarted ────────────────────────────────────────────────

    @Test
    fun `reduce WorkdirDraftStarted clears chat fields and currentModel`() {
        // §fix-draft-model-leak: currentModel MUST be cleared so the prior session's
        // model does not leak into the draft picker.
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "old",
                messages = listOf(Message(id = "m1", role = "user")),
                partsByMessage = mapOf("m1" to emptyList()),
                streamingPartTexts = mapOf("p1" to "delta"),
                streamingReasoningPart = Part(id = "p1", type = "reasoning", text = "r"),
                currentModel = Message.ModelInfo("openai", "gpt-5"),
                // chat-only fields — PRESERVED (same .copy() contract).
                isCompacting = true,
                refreshNonce = 9L,
            ),
        )

        val out = reduce(prior, AppAction.WorkdirDraftStarted(workdir = "/new"))

        assertNull(out.chat.currentSessionId)
        assertTrue(out.chat.messages.isEmpty())
        assertTrue(out.chat.partsByMessage.isEmpty())
        assertTrue(out.chat.streamingPartTexts.isEmpty())
        assertNull(out.chat.streamingReasoningPart)
        assertNull("currentModel cleared (fix-draft-model-leak)", out.chat.currentModel)
        // chat-only fields preserved.
        assertTrue(out.chat.isCompacting)
        assertEquals(9L, out.chat.refreshNonce)
    }

    @Test
    fun `reduce WorkdirDraftStarted clears sessionTodos and resets composer for the new workdir`() {
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessionTodos = mapOf("old" to listOf(TodoItem(content = "t", status = "pending", priority = "normal", id = "t1"))),
                sessions = listOf(Session(id = "keep", directory = "/k")),  // sessions NOT cleared
            ),
            composer = ComposerState(
                inputText = "stale text",
                // imageAttachments omitted — the complex ComposerImageAttachment
                // ctor is irrelevant to this assertion; inputText + fileReferences
                // + draftWorkdir are the fields the reducer resets + asserts.
                imageAttachments = emptyList(),
                fileReferences = listOf(ComposerFileReference(path = "/old.kt")),
                draftWorkdir = null,
            ),
        )

        val out = reduce(prior, AppAction.WorkdirDraftStarted(workdir = "/new"))

        // sessionTodos cleared; sessions PRESERVED (only todos reset per the pre-B2 site).
        assertTrue(out.sessionList.sessionTodos.isEmpty())
        assertEquals(1, out.sessionList.sessions.size)
        // composer fully reset + draftWorkdir set.
        assertEquals("", out.composer.inputText)
        assertTrue(out.composer.imageAttachments.isEmpty())
        assertTrue(out.composer.fileReferences.isEmpty())
        assertEquals("/new", out.composer.draftWorkdir)
    }

    // ── Purity: reducer does not touch SettingsManager / emit effects ──────
    //
    // (Implicit — the reducer signature is (StoreState, AppAction) -> StoreState;
    // it has no SettingsManager / effect-bus / network parameter to touch. The
    // compiler enforces purity at the type level. No runtime assertion needed.)

    // ── 2. Atomicity tests (collect store.stateFlow; dispatch; assert single
    //       committed aggregate transition with no torn intermediates) ───────

    @Test
    fun `dispatch SessionArchived produces exactly one aggregate emission with no torn intermediate`() = runTest {
        val store = SharedStateStore()
        // Seed: the archived session IS the current one — the most tear-prone
        // scenario (pre-B2 scattered mutateSessionList + mutateChat could be
        // observed mid-way as "sessionList archived, chat.currentSessionId
        // still pointing at it").
        store.mutateChat { it.copy(currentSessionId = "cur", messages = listOf(Message(id = "m1", role = "user"))) }
        store.mutateSessionList { it.copy(sessions = listOf(Session(id = "cur", directory = "/p")), openSessionIds = listOf("cur")) }
        val archivedSnapshot = store.stateFlow.value

        val seen = mutableListOf<StoreState>()
        val job = launch {
            store.stateFlow.collect { seen += it }
        }
        advanceUntilIdle()
        // Initial state emitted.
        assertEquals(1, seen.size)
        assertEquals(archivedSnapshot, seen.last())

        val archived = Session(id = "cur", directory = "/p", time = Session.TimeInfo(archived = 1L))
        store.dispatch(AppAction.SessionArchived(archived, openSessionIds = emptyList()))
        advanceUntilIdle()

        // Exactly ONE new aggregate emission for the action (no intermediates).
        assertEquals("exactly one initial + one post-dispatch emission", 2, seen.size)
        val finalState = seen.last()
        // The single committed state is fully consistent: sessionList reflects the
        // archive AND chat is cleared in the SAME state. There is NO element in the
        // stream where sessionList is archived but chat.currentSessionId still == "cur".
        assertTrue("sessionList archived in final state", finalState.sessionList.sessions.any { it.id == "cur" && it.isArchived })
        assertTrue("openSessionIds empty in final state", finalState.sessionList.openSessionIds.isEmpty())
        assertNull("chat cleared in the SAME committed state", finalState.chat.currentSessionId)
        assertTrue("chat messages cleared in the SAME committed state", finalState.chat.messages.isEmpty())
        // No torn intermediate exists in the whole stream.
        seen.forEach { s ->
            val torn = s.sessionList.sessions.any { it.id == "cur" && it.isArchived } && s.chat.currentSessionId == "cur"
            assertFalse("no torn intermediate (archived-but-current) in stream", torn)
        }
        job.cancel()
    }

    @Test
    fun `dispatch HostStatePurged produces exactly one aggregate emission with no partial clear`() = runTest {
        val store = SharedStateStore()
        // Seed: rich cross-slice state so a partial clear would be observable.
        store.mutateChat {
            it.copy(currentSessionId = "sess", messages = listOf(Message(id = "m", role = "user")), currentModel = Message.ModelInfo("p", "m"))
        }
        store.mutateSessionList { it.copy(sessions = listOf(Session(id = "sess", directory = "/p")), openSessionIds = listOf("sess")) }
        store.mutateUnread { it.copy(unreadSessions = setOf("sess")) }
        store.mutateComposer { it.copy(draftWorkdir = "/draft") }
        store.mutateSettings { it.copy(availableCommands = listOf(CommandInfo("c"))) }
        store.mutateConnection { it.copy(serverVersion = "1.0") }

        val seen = mutableListOf<StoreState>()
        val job = launch {
            store.stateFlow.collect { seen += it }
        }
        advanceUntilIdle()
        assertEquals(1, seen.size)

        store.dispatch(AppAction.HostStatePurged(preserveServerGroupData = false))
        advanceUntilIdle()

        // Exactly ONE new aggregate emission for the action.
        assertEquals(2, seen.size)
        val finalState = seen.last()
        // Every cleared field is cleared in the SAME committed state.
        assertNull(finalState.chat.currentSessionId)
        assertTrue(finalState.chat.messages.isEmpty())
        assertTrue(finalState.sessionList.sessions.isEmpty())
        assertTrue(finalState.sessionList.openSessionIds.isEmpty())
        assertTrue(finalState.unread.unreadSessions.isEmpty())
        assertNull(finalState.composer.draftWorkdir)
        assertTrue(finalState.settings.availableCommands.isEmpty())
        assertNull(finalState.connection.serverVersion)
        // No torn intermediate in the stream: no element where chat is cleared but
        // sessionList still has sessions, or any other partial-clear combination.
        seen.forEach { s ->
            val cleared = s.chat.currentSessionId == null && s.composer.draftWorkdir == null
            val partialSessions = s.chat.currentSessionId == null && s.sessionList.sessions.isNotEmpty()
            assertFalse("no partial clear (chat cleared but sessions remain) in stream", partialSessions || (cleared && s.sessionList.openSessionIds.isNotEmpty()))
        }
        job.cancel()
    }

    @Test
    fun `dispatch DraftSessionMaterialized produces exactly one aggregate emission`() = runTest {
        val store = SharedStateStore()
        store.mutateComposer { it.copy(draftWorkdir = "/draft") }
        store.mutateUnread { it.copy(unreadSessions = setOf("new")) }

        val seen = mutableListOf<StoreState>()
        val job = launch {
            store.stateFlow.collect { seen += it }
        }
        advanceUntilIdle()
        assertEquals(1, seen.size)

        val created = Session(id = "new", directory = "/p")
        store.dispatch(AppAction.DraftSessionMaterialized(created, listOf("new"), 100L))
        advanceUntilIdle()

        assertEquals(2, seen.size)
        val finalState = seen.last()
        // All four slice changes (sessionList / chat / unread / composer) in ONE state.
        assertEquals("new", finalState.sessionList.sessions.first().id)
        assertEquals(listOf("new"), finalState.sessionList.openSessionIds)
        assertEquals("new", finalState.chat.currentSessionId)
        assertFalse("new" in finalState.unread.unreadSessions)
        assertEquals(100L, finalState.unread.lastViewedTime["new"])
        assertNull(finalState.composer.draftWorkdir)
        job.cancel()
    }

    @Test
    fun `dispatch WorkdirDraftStarted produces exactly one aggregate emission`() = runTest {
        val store = SharedStateStore()
        store.mutateChat { it.copy(currentSessionId = "old", currentModel = Message.ModelInfo("p", "m")) }
        store.mutateSessionList { it.copy(sessionTodos = mapOf("old" to listOf(TodoItem(content = "t", status = "pending", priority = "normal", id = "t1")))) }
        store.mutateComposer { it.copy(inputText = "stale", fileReferences = listOf(ComposerFileReference(path = "/x"))) }

        val seen = mutableListOf<StoreState>()
        val job = launch {
            store.stateFlow.collect { seen += it }
        }
        advanceUntilIdle()
        assertEquals(1, seen.size)

        store.dispatch(AppAction.WorkdirDraftStarted(workdir = "/proj"))
        advanceUntilIdle()

        assertEquals(2, seen.size)
        val finalState = seen.last()
        // All three slice changes (chat / sessionList.sessionTodos / composer) in ONE state.
        assertNull(finalState.chat.currentSessionId)
        assertNull(finalState.chat.currentModel)
        assertTrue(finalState.sessionList.sessionTodos.isEmpty())
        assertEquals("", finalState.composer.inputText)
        assertTrue(finalState.composer.fileReferences.isEmpty())
        assertEquals("/proj", finalState.composer.draftWorkdir)
        job.cancel()
    }

    // ── 3. Projection-consistency: per-slice collector sees cross-slice
    //       consistency in the SAME committed state ─────────────────────────

    @Test
    fun `sessionListFlow collector observes chat already cleared in the same dispatch`() = runTest {
        // The cross-slice consistency guarantee: when a sessionListFlow collector
        // fires AFTER dispatch(SessionArchived) for the current session, chatFlow.value
        // is ALREADY cleared — no lag, no separate hop. This is what makes the B2
        // single-commit dispatch safe for cross-slice observers.
        val store = SharedStateStore()
        store.mutateChat { it.copy(currentSessionId = "cur", messages = listOf(Message(id = "m", role = "user"))) }
        store.mutateSessionList { it.copy(sessions = listOf(Session(id = "cur", directory = "/p")), openSessionIds = listOf("cur")) }

        var observedChatWhileSessionListArchived: ChatState? = null
        val job = launch {
            store.sessionListFlow.collect { sl ->
                // The moment sessionList reflects the archive, read chatFlow.value.
                if (sl.sessions.any { it.id == "cur" && it.isArchived }) {
                    observedChatWhileSessionListArchived = store.chatFlow.value
                }
            }
        }
        advanceUntilIdle()
        assertNull("no archive observed yet", observedChatWhileSessionListArchived)

        val archived = Session(id = "cur", directory = "/p", time = Session.TimeInfo(archived = 1L))
        store.dispatch(AppAction.SessionArchived(archived, openSessionIds = emptyList()))
        advanceUntilIdle()

        assertNotNull("sessionList collector observed the archive", observedChatWhileSessionListArchived)
        // The chat observed IN THE SAME committed state is already cleared.
        assertNull("chat already cleared when sessionList collector fires", observedChatWhileSessionListArchived!!.currentSessionId)
        assertTrue("chat messages already cleared when sessionList collector fires", observedChatWhileSessionListArchived!!.messages.isEmpty())
        job.cancel()
    }

    // ── Sanity: dispatch forwards to the pure reducer ──────────────────────

    @Test
    fun `dispatch and reduce produce identical results for the same action`() = runTest {
        val store = SharedStateStore()
        store.mutateChat { it.copy(currentSessionId = "old") }
        store.mutateSessionList { it.copy(sessions = listOf(Session(id = "old", directory = "/p")), openSessionIds = listOf("old")) }

        val snapshotBefore = store.stateFlow.value
        val action = AppAction.SessionArchived(
            Session(id = "old", directory = "/p", time = Session.TimeInfo(archived = 1L)),
            openSessionIds = emptyList(),
        )
        // Pure call (no store mutation).
        val reduced = reduce(snapshotBefore, action)
        // Store dispatch.
        store.dispatch(action)

        assertEquals(reduced, store.stateFlow.value)
    }

    // ── SeedFixture-based snapshot helper (mirrors the controller test pattern) ─
    //
    // The pure reducer tests above build StoreState directly (no SeedFixture); this
    // helper exists only to assert the round-trip from a SeedFixture-shaped snapshot
    // (the format the controller tests use) through reduce stays well-formed.
    @Test
    fun `reduce over a SeedFixture-shaped snapshot stays total and well-formed`() {
        // A StoreState built from a default SeedFixture should reduce cleanly under
        // every action variant (no exception, total coverage of the when-branches).
        val seed = SeedFixture()
        val prior = StoreState.initial().copy(
            chat = ChatState(currentSessionId = seed.currentSessionId),
            composer = ComposerState(draftWorkdir = seed.draftWorkdir),
            sessionList = SessionListState(sessions = seed.sessions, openSessionIds = seed.openSessionIds),
            unread = UnreadState(unreadSessions = seed.unreadSessions, lastViewedTime = seed.lastViewedTime),
            settings = SettingsState(availableCommands = seed.availableCommands),
            connection = ConnectionState(serverVersion = seed.serverVersion),
        )

        reduce(prior, AppAction.DraftSessionMaterialized(Session(id = "x", directory = "/x"), listOf("x"), 0L))
        reduce(prior, AppAction.SessionArchived(Session(id = "x", directory = "/x", time = Session.TimeInfo(archived = 1L)), emptyList()))
        reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = false))
        reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = true))
        reduce(prior, AppAction.WorkdirDraftStarted(workdir = "/w"))
        // No exception thrown == each when-branch is total. The concrete field-by-field
        // assertions live in the dedicated tests above.
    }
}
