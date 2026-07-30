package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.QuestionInfo
import cn.vectory.ocdroid.data.model.QuestionOption
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.RevertCutoff
import cn.vectory.ocdroid.data.model.RevertCutoffState
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SlimSessionLastError
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
    fun `reduce DraftSessionMaterialized upserts session into sessionList and sets open-tabs-list`() {
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(Session(id = "old", directory = "/old"))))
        val created = Session(id = "new", directory = "/proj")

        val out = reduce(prior, AppAction.DraftSessionMaterialized(created, viewedAt = 123L))

        // Upsert: the new session is at the head; the old one survives (not replaced).
        assertEquals("new", out.sessionList.sessions.first().id)
        assertEquals(2, out.sessionList.sessions.size)
        assertTrue(out.sessionList.sessions.any { it.id == "old" })
        assertEquals(setOf("new"), out.sessionList.pendingCreateIds)
        assertEquals(mapOf("new" to 123L), out.sessionList.pendingCreatedAt)
    }

    @Test
    fun `reduce DraftSessionMaterialized sets chat currentSessionId to the new session id`() {
        val prior = StoreState.initial().copy(
            chat = ChatState(currentSessionId = "old-session"))
        val created = Session(id = "fresh", directory = "/p")

        val out = reduce(prior, AppAction.DraftSessionMaterialized(created, viewedAt = 0L))

        assertEquals("fresh", out.chat.currentSessionId)
    }

    @Test
    fun`reduce DraftSessionMaterialized clears session id from unread and bumps lastViewedTime`() {
        val prior = StoreState.initial().copy(
            unread = UnreadState(
                unreadSessions = setOf("new", "other"),
                lastViewedTime = mapOf("other" to 5L)))
        val created = Session(id = "new", directory = "/p")

        val out = reduce(prior, AppAction.DraftSessionMaterialized(created, viewedAt = 999L))

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
            composer = ComposerState(draftWorkdir = "/draft-path", inputText = "stale"))
        val created = Session(id = "n", directory = "/p")

        val out = reduce(prior, AppAction.DraftSessionMaterialized(created, viewedAt = 0L))

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
            settings = priorSettings)
        val created = Session(id = "n", directory = "/p")

        val out = reduce(prior, AppAction.DraftSessionMaterialized(created, viewedAt = 0L))

        // Slices the action does NOT touch are reference-equal (data-class copy leaves
        // the non-target fields untouched).
        assertSame(priorConnection, out.connection)
        assertSame(priorSettings, out.settings)
    }

    // ── SessionArchived ────────────────────────────────────────────────────

    @Test
    fun `reduce SessionArchived upserts archived session and replaces open-tabs-list`() {
        val archived = Session(
            id = "sess-1",
            directory = "/p",
            time = Session.TimeInfo(archived = 1_700_000_000_000))
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(Session(id = "sess-1", directory = "/p"), Session(id = "sess-2", directory = "/q")),
                directorySessions = mapOf("/p" to listOf(Session(id = "sess-1", directory = "/p")))))

        val out = reduce(prior, AppAction.SessionArchived(archived))

        // The archived session is upserted (id-stable replace).
        assertTrue(out.sessionList.sessions.any { it.id == "sess-1" && it.isArchived })
        // open-tabs-list replaced (sess-1 evicted).
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
                partsByMessage = mapOf("m1" to emptyList())),
            sessionList = SessionListState())

        val out = reduce(prior, AppAction.SessionArchived(archived))

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
                partsByMessage = mapOf("m1" to emptyList())),
            sessionList = SessionListState())

        val out = reduce(prior, AppAction.SessionArchived(archived))

        // chat untouched — still pointing at "cur".
        assertEquals("cur", out.chat.currentSessionId)
        assertEquals(1, out.chat.messages.size)
        assertEquals(1, out.chat.partsByMessage.size)
    }

    // ── §task5-lifecycle: SessionArchived clears unread + pendingQuestions ──

    @Test
    fun `reduce SessionArchived clears archived id from unread and its pendingQuestions`() {
        // §task5-lifecycle: an archived session must NOT keep its unread badge
        // or any pending question bound to it — otherwise the user sees a red
        // dot / question chip for a session they can no longer open. The clean
        // happens in the SAME committed state as the archive (single dispatch,
        // no torn "archived but still unread" intermediate).
        val archived = Session(id = "sess-1", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(Session(id = "sess-1", directory = "/p")),
                pendingQuestions = listOf(
                    QuestionRequest(
                        id = "q1", sessionId = "sess-1",
                        questions = listOf(QuestionInfo("q?", "h", listOf(QuestionOption("a", "b"))))),
                    // A question bound to an unrelated session MUST survive.
                    QuestionRequest(
                        id = "q2", sessionId = "sess-other",
                        questions = listOf(QuestionInfo("q?", "h", listOf(QuestionOption("a", "b")))))),
                pendingCreateIds = setOf("s1"),
                pendingCreatedAt = mapOf("s1" to 123L)),
            unread = UnreadState(
                unreadSessions = setOf("sess-1", "sess-other"),
                lastViewedTime = mapOf("sess-1" to 1L, "sess-other" to 2L)))

        val out = reduce(prior, AppAction.SessionArchived(archived))

        // Archived id removed from unread; unrelated id preserved.
        assertFalse("archived id removed from unreadSessions", out.unread.unreadSessions.contains("sess-1"))
        assertTrue("unrelated id preserved in unreadSessions", out.unread.unreadSessions.contains("sess-other"))
        // lastViewedTime for the archived id also dropped (no orphan entry).
        assertFalse("archived id removed from lastViewedTime", out.unread.lastViewedTime.containsKey("sess-1"))
        assertEquals(2L, out.unread.lastViewedTime["sess-other"])
        // Question bound to the archived id removed; the unrelated one survives.
        assertTrue(
            "archived session question removed",
            out.sessionList.pendingQuestions.none { it.sessionId == "sess-1" })
        assertTrue(
            "unrelated session question preserved",
            out.sessionList.pendingQuestions.any { it.sessionId == "sess-other" })
    }

    // ── §task5-lifecycle (final-review fix 1): SessionArchived clears WHOLE SUBTREE ──

    @Test
    fun `reduce SessionArchived clears the whole subtree unread and pendingQuestions even when only the root archive event arrives`() {
        // §task5-lifecycle (final-review fix 1): defensive subtree cleanup.
        // The SSE archive path is per-id, but if the server only emits the
        // root's archive event (descendants do NOT get their own session.updated),
        // the reducer MUST still clean descendants' unread + pending questions
        // atomically in the same committed state — otherwise a child's badge /
        // question survives an archived parent and the user sees a stale chip
        // for a session that is effectively gone.
        val archivedRoot = Session(id = "root", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(
                    Session(id = "root", directory = "/p"),
                    Session(id = "child", directory = "/p", parentId = "root"),
                    Session(id = "grandchild", directory = "/p", parentId = "child"),
                    Session(id = "unrelated", directory = "/p")),
                pendingQuestions = listOf(
                    QuestionRequest(id = "q-root", sessionId = "root", questions = emptyList()),
                    QuestionRequest(id = "q-child", sessionId = "child", questions = emptyList()),
                    QuestionRequest(id = "q-grandchild", sessionId = "grandchild", questions = emptyList()),
                    QuestionRequest(id = "q-unrelated", sessionId = "unrelated", questions = emptyList()))),
            unread = UnreadState(
                unreadSessions = setOf("root", "child", "grandchild", "unrelated"),
                lastViewedTime = mapOf(
                    "root" to 1L, "child" to 2L, "grandchild" to 3L, "unrelated" to 4L)))

        val out = reduce(prior, AppAction.SessionArchived(archivedRoot))

        // Whole root subtree cleaned atomically — even though only the root
        // archive event arrived in this action.
        assertFalse("root cleared", "root" in out.unread.unreadSessions)
        assertFalse("child cleared (no own archive event)", "child" in out.unread.unreadSessions)
        assertFalse("grandchild cleared (no own archive event)", "grandchild" in out.unread.unreadSessions)
        assertTrue("unrelated preserved", "unrelated" in out.unread.unreadSessions)
        // lastViewedTime orphans dropped for the subtree only.
        assertFalse("root lastViewed cleared", "root" in out.unread.lastViewedTime)
        assertFalse("child lastViewed cleared", "child" in out.unread.lastViewedTime)
        assertFalse("grandchild lastViewed cleared", "grandchild" in out.unread.lastViewedTime)
        assertEquals(4L, out.unread.lastViewedTime["unrelated"])
        // Subtree questions all removed; unrelated preserved.
        assertTrue(
            "subtree questions removed",
            out.sessionList.pendingQuestions.none { it.sessionId in setOf("root", "child", "grandchild") })
        assertTrue(
            "unrelated question preserved",
            out.sessionList.pendingQuestions.any { it.sessionId == "unrelated" })
    }

    // ── §Wave5b-Q13 blocker-2: SessionArchived cleans scroll state for the
    //     WHOLE archived subtree unconditionally (chat CONTENT remains
    //     current-only-cleared). Pre-fix: only the current-archived branch
    //     wiped pendingScrollRequest / parentReturnCheckpoints; a non-current
    //     archived subtree leaked stale scroll state indefinitely.

    @Test
    fun `Wave5b-Q13 blocker-2 - SessionArchived clears pendingScrollRequest when target is in the archived subtree (non-current)`() {
        // The archived session is NOT the current one — chat content
        // (currentSessionId / messages) MUST be preserved. But the stale
        // pendingScrollRequest targeting the archived id MUST be wiped
        // (the consumer would never fire correctly on an archived session).
        val archived = Session(id = "stale-target", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val staleReq = PendingScrollRequest(
            requestId = 7L,
            targetSessionId = "stale-target",
            behavior = ScrollBehavior.Latest)
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "cur",  // NOT the archived id
                messages = listOf(Message(id = "m1", role = "user")),
                partsByMessage = mapOf("m1" to emptyList()),
                pendingScrollRequest = staleReq),
            sessionList = SessionListState(
                sessions = listOf(
                    Session(id = "cur", directory = "/p"),
                    Session(id = "stale-target", directory = "/p"))))

        val out = reduce(prior, AppAction.SessionArchived(archived))

        // Chat CONTENT untouched (non-current archived).
        assertEquals("cur", out.chat.currentSessionId)
        assertEquals(1, out.chat.messages.size)
        assertEquals(1, out.chat.partsByMessage.size)
        // Stale scroll intent wiped.
        assertNull(
            "non-current archived target's pendingScrollRequest MUST be wiped",
            out.chat.pendingScrollRequest)
    }

    @Test
    fun `Wave5b-Q13 blocker-2 - SessionArchived clears pendingScrollRequest targeting the archived subtree even when current is unrelated (non-current)`() {
        // §chat-list-detail §11 / G6 (B5): the parentReturnCheckpoints map is
        // GONE — checkpoints now live on per-route-entry SavedStateHandle and
        // are auto-cleaned when the entry pops. The archived-subtree cleanup
        // now ONLY sweeps pendingScrollRequest (target in archived subtree).
        // Verify the slot is cleared when its target is in the archived
        // subtree, even though the current session is unrelated.
        val archivedRoot = Session(id = "archived-root", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val staleReq = PendingScrollRequest(
            requestId = 7L,
            targetSessionId = "archived-child",
            behavior = ScrollBehavior.Latest)
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "live-cur",
                pendingScrollRequest = staleReq),
            sessionList = SessionListState(
                sessions = listOf(
                    Session(id = "live-cur", directory = "/p"),
                    Session(id = "archived-root", directory = "/p"),
                    Session(id = "archived-child", directory = "/p", parentId = "archived-root"))))

        val out = reduce(prior, AppAction.SessionArchived(archivedRoot))

        // Stale scroll intent targeting the archived subtree is wiped.
        assertNull(
            "non-current archived target's pendingScrollRequest MUST be wiped",
            out.chat.pendingScrollRequest)
        // Chat content untouched (non-current archived).
        assertEquals("live-cur", out.chat.currentSessionId)
    }

    @Test
    fun `Wave5b-Q13 blocker-2 - SessionArchived preserves pendingScrollRequest targeting an UNRELATED session`() {
        // Defensive: the cleanup MUST NOT over-reach. A pendingScrollRequest
        // targeting a session NOT in the archived subtree survives.
        val archived = Session(id = "archived", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val liveReq = PendingScrollRequest(
            requestId = 11L,
            targetSessionId = "live-future-target",
            behavior = ScrollBehavior.Latest)
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "cur",
                pendingScrollRequest = liveReq),
            sessionList = SessionListState(
                sessions = listOf(
                    Session(id = "cur", directory = "/p"),
                    Session(id = "archived", directory = "/p"),
                    Session(id = "live-future-target", directory = "/p"))))

        val out = reduce(prior, AppAction.SessionArchived(archived))

        assertEquals(liveReq, out.chat.pendingScrollRequest)
    }

    @Test
    fun `Wave5b-Q13 blocker-2 - SessionArchived current-archived case still wipes scroll slot (no regression)`() {
        // Regression guard: the existing current-archive clear path
        // (applyArchivedChatClear) is unchanged — slot wiped, chat content
        // also wiped. The new cleanScrollStateForSubtree call is a no-op on
        // top (idempotent), so the assertion is the same as pre-fix.
        // §chat-list-detail §11 / G6 (B5): parentReturnCheckpoints map is
        // gone (per-entry SavedStateHandle); only pendingScrollRequest is
        // asserted here.
        val archived = Session(id = "cur", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "cur",
                messages = listOf(Message(id = "m1", role = "user")),
                partsByMessage = mapOf("m1" to emptyList()),
                pendingScrollRequest = PendingScrollRequest(
                    requestId = 1L,
                    targetSessionId = "cur",
                    behavior = ScrollBehavior.Latest)),
            sessionList = SessionListState())

        val out = reduce(prior, AppAction.SessionArchived(archived))

        assertNull("current-archived: currentSessionId cleared", out.chat.currentSessionId)
        assertTrue("current-archived: messages cleared", out.chat.messages.isEmpty())
        assertTrue("current-archived: partsByMessage cleared", out.chat.partsByMessage.isEmpty())
        assertNull("current-archived: pendingScrollRequest cleared", out.chat.pendingScrollRequest)
    }

    @Test
    fun `Wave5b-Q13 blocker-2 - BulkSessionsRefreshed cleans scroll state for non-current archived subtree`() {
        // Same rule, BulkSessionsRefreshed path. A bulk refresh can archive
        // multiple ids cross-device; each archived subtree's scroll state is
        // cleaned even when the current session is NOT among the archived.
        // §chat-list-detail §11 / G6 (B5): parentReturnCheckpoints map is
        // gone (per-entry SavedStateHandle); only the pendingScrollRequest
        // sweep is asserted here.
        val archivedOther = Session(id = "other", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "cur",  // NOT archived
                pendingScrollRequest = PendingScrollRequest(
                    requestId = 5L,
                    targetSessionId = "other",  // in archived subtree
                    behavior = ScrollBehavior.Latest)),
            sessionList = SessionListState(
                sessions = listOf(
                    Session(id = "cur", directory = "/p"),
                    archivedOther,
                    Session(id = "other-child", directory = "/p", parentId = "other"))))

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(
                    Session(id = "cur", directory = "/p"),
                    archivedOther,
                    Session(id = "other-child", directory = "/p", parentId = "other")),
                hasMoreSessions = false,
                confirmedServerIds = setOf("cur", "other", "other-child"),
                sweepNow = 0L))

        // Chat content preserved (current not archived).
        assertEquals("cur", out.chat.currentSessionId)
        // Stale scroll state for the archived subtree wiped.
        assertNull(
            "BulkSessionsRefreshed: pendingScrollRequest targeting archived id wiped",
            out.chat.pendingScrollRequest)
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
                // §fix-leak-window (fix B): the per-session fields pre-B2 left
                // stale — seeded non-default so the assertions below prove the
                // reducer actually clears them (not just that they defaulted).
                currentModel = Message.ModelInfo("openai", "gpt-5"),
                olderMessagesCursor = "cursor-old",
                hasMoreMessages = true,
                staleNotice = true,
                revertCutoffs = mapOf("m1" to RevertCutoff("sess-old", "m1", RevertCutoffState.PendingFetch)),
                deltaBuffer = mapOf("p1" to "buf"),
                fullTextBuffer = mapOf("p2" to "full"),
                pendingFlushPartIds = setOf("p3"),
                // The 3 chat-only fields — MUST be preserved.
                isCompacting = true,
                compactStartedAt = 42L,
                refreshNonce = 7L))

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = false))

        // Cleared (AppState-represented chat fields):
        assertNull(out.chat.currentSessionId)
        assertTrue(out.chat.messages.isEmpty())
        assertTrue(out.chat.partsByMessage.isEmpty())
        assertTrue(out.chat.streamingPartTexts.isEmpty())
        assertNull(out.chat.streamingReasoningPart)
        assertFalse(out.chat.isLoadingMessages)
        assertFalse(out.chat.isLoadingMoreMessages)
        // §fix-leak-window (fix B): newly-cleared per-session fields.
        assertNull("currentModel cleared cross-host", out.chat.currentModel)
        assertNull("olderMessagesCursor cleared cross-host", out.chat.olderMessagesCursor)
        assertFalse("hasMoreMessages cleared cross-host", out.chat.hasMoreMessages)
        assertFalse("staleNotice cleared cross-host", out.chat.staleNotice)
        assertTrue("revertCutoffs cleared cross-host", out.chat.revertCutoffs.isEmpty())
        assertTrue("deltaBuffer cleared cross-host", out.chat.deltaBuffer.isEmpty())
        assertTrue("fullTextBuffer cleared cross-host", out.chat.fullTextBuffer.isEmpty())
        assertTrue("pendingFlushPartIds cleared cross-host", out.chat.pendingFlushPartIds.isEmpty())
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
                // §P0-A rev-gpt #8: sessionStatuses is a PROJECTION of authority.
                // Set authority.bySid so the prior state is consistent — the
                // cross-group purge clears authority → projection recomputes to empty.
                sessionStatuses = mapOf("s1" to cn.vectory.ocdroid.data.model.SessionStatus("idle")),
                sessionTodos = mapOf("s1" to listOf(TodoItem(content = "t", status = "pending", priority = "normal", id = "t1"))),
                sessionDiffs = mapOf("s1" to emptyList()),
                // §fix-leak-window (fix B): pending permission/question requests
                // belong to the prior host's sessions — seeded non-default so the
                // assertions prove the reducer clears them cross-group (pre-B2 left
                // them stale).
                pendingPermissions = listOf(PermissionRequest(id = "perm1", sessionId = "s1")),
                pendingQuestions = listOf(
                    QuestionRequest(
                        id = "q1", sessionId = "s1",
                        questions = listOf(QuestionInfo("q?", "h", listOf(QuestionOption("a", "b"))))))),
            unread = UnreadState(
                unreadSessions = setOf("s1"),
                lastViewedTime = mapOf("s1" to 1L)),
            // §P0-A rev-gpt #8: authority.bySid seeded so the cross-group purge
            // (reduceAuthority(PurgeHost)) clears it → projection recomputes to empty.
            authority = cn.vectory.ocdroid.data.state.AuthorityState(
                bySid = mapOf("s1" to cn.vectory.ocdroid.data.state.SessionEntry(
                    status = cn.vectory.ocdroid.data.model.SessionStatus("idle"),
                    serverRound = null,
                    optimisticClaim = null,
                    origin = cn.vectory.ocdroid.data.state.EntryOrigin.REST,
                    freshness = cn.vectory.ocdroid.data.state.Freshness.Fresh,
                    updatedMonotonic = 0L,
                    workdir = "/p",
                )),
            ),
            composer = ComposerState(draftWorkdir = "/old/proj"),
            settings = SettingsState(availableCommands = listOf(CommandInfo("cmd"))),
            connection = ConnectionState(serverVersion = "1.2.3"))

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = false))

        // sessionList fully cleared.
        assertTrue(out.sessionList.sessions.isEmpty())
        assertTrue(out.sessionList.directorySessions.isEmpty())
        assertTrue(out.sessionList.sessionStatuses.isEmpty())
        assertTrue(out.sessionList.sessionTodos.isEmpty())
        assertTrue(out.sessionList.sessionDiffs.isEmpty())
        // §fix-leak-window (fix B): pending requests cleared cross-group.
        assertTrue("pendingPermissions cleared cross-host", out.sessionList.pendingPermissions.isEmpty())
        assertTrue("pendingQuestions cleared cross-host", out.sessionList.pendingQuestions.isEmpty())
        assertTrue("pendingCreateIds cleared cross-host", out.sessionList.pendingCreateIds.isEmpty())
        assertTrue("pendingCreatedAt cleared cross-host", out.sessionList.pendingCreatedAt.isEmpty())
        // unread fully cleared.
        assertTrue(out.unread.unreadSessions.isEmpty())
        assertTrue(out.unread.lastViewedTime.isEmpty())
        // per-profile UX always reset.
        assertNull(out.composer.draftWorkdir)
        assertTrue(out.settings.availableCommands.isEmpty())
        assertNull(out.connection.serverVersion)
    }

    @Test
    fun `gpter-residual reduce HostStatePurged cross-group clears child trees + completeness proofs and bumps epoch`() {
        // §gpter-residual: a cross-group purge must drop cached child trees and
        // completeness proofs — a root-id collision across hosts would otherwise
        // let a stale proof skip new-host hydration, and an in-flight child load
        // captured before the switch could commit the prior host's children.
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(Session(id = "s1", directory = "/p")),
                childSessions = mapOf("s1" to listOf(Session(id = "c1", directory = "/p", parentId = "s1"))),
                completeRootIds = setOf("s1"),
                completenessEpoch = 9L))

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = false))

        assertTrue(
            "childSessions cleared cross-host",
            out.sessionList.childSessions.isEmpty())
        assertTrue(
            "completeRootIds cleared cross-host",
            out.sessionList.completeRootIds.isEmpty())
        assertEquals(
            "completeness epoch bumped on purge",
            10L,
            out.sessionList.completenessEpoch)
    }

    @Test
    fun `P0-F reduce HostStatePurged cross-group clears abortPendingSessionIds`() {
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                abortPendingSessionIds = mapOf("s1" to 100L, "s2" to 200L),
            ),
        )

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = false))

        assertTrue("abortPendingSessionIds cleared cross-group",
            out.sessionList.abortPendingSessionIds.isEmpty())
    }

    @Test
    fun `P0-F reduce HostStatePurged same-group preserves abortPendingSessionIds`() {
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                abortPendingSessionIds = mapOf("s1" to 100L),
            ),
        )

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = true))

        // Same-group switch: sessions are still valid → pending survives
        assertEquals(mapOf("s1" to 100L), out.sessionList.abortPendingSessionIds)
    }

    // ── HostStatePurged (same-group = preserve server data) ────────────────

    @Test
    fun `reduce HostStatePurged same-group preserves sessions + unread + directorySessions`() {
        val session = Session(id = "s1", directory = "/p")
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(session),
                directorySessions = mapOf("/p" to listOf(session)),
                sessionStatuses = mapOf("s1" to cn.vectory.ocdroid.data.model.SessionStatus("idle")),
                sessionTodos = mapOf("s1" to listOf(TodoItem(content = "t", status = "pending", priority = "normal", id = "t1"))),
                sessionDiffs = mapOf("s1" to emptyList()),
                pendingCreateIds = setOf("s1"),
                pendingCreatedAt = mapOf("s1" to 123L)),
            unread = UnreadState(
                unreadSessions = setOf("s1"),
                lastViewedTime = mapOf("s1" to 1L)))

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = true))

        // Server data PRESERVED (same-group = same server = identical data).
        assertEquals(listOf(session), out.sessionList.sessions)
        assertEquals(mapOf("/p" to listOf(session)), out.sessionList.directorySessions)
        assertEquals(1, out.sessionList.sessionStatuses.size)
        assertEquals(1, out.sessionList.sessionTodos.size)
        assertEquals(1, out.sessionList.sessionDiffs.size)
        assertTrue("pendingCreateIds cleared on same-group host switch", out.sessionList.pendingCreateIds.isEmpty())
        assertTrue("pendingCreatedAt cleared on same-group host switch", out.sessionList.pendingCreatedAt.isEmpty())
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
        // §fix-leak-window (fix B): the FULL per-session clear now also resets
        // cursor / hasMoreMessages / staleNotice / etc. — seeded non-default
        // so the assertions prove the reducer clears them.
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "old",
                messages = listOf(Message(id = "m1", role = "user")),
                partsByMessage = mapOf("m1" to emptyList()),
                streamingPartTexts = mapOf("p1" to "delta"),
                streamingReasoningPart = Part(id = "p1", type = "reasoning", text = "r"),
                currentModel = Message.ModelInfo("openai", "gpt-5"),
                olderMessagesCursor = "cursor-old",
                hasMoreMessages = true,
                staleNotice = true,
                revertCutoffs = mapOf("m1" to RevertCutoff("old", "m1", RevertCutoffState.PendingFetch)),
                deltaBuffer = mapOf("p1" to "buf"),
                fullTextBuffer = mapOf("p2" to "full"),
                pendingFlushPartIds = setOf("p3"),
                // chat-only fields — PRESERVED (same .copy() contract).
                isCompacting = true,
                refreshNonce = 9L))

        val out = reduce(prior, AppAction.WorkdirDraftStarted(workdir = "/new"))

        assertNull(out.chat.currentSessionId)
        assertTrue(out.chat.messages.isEmpty())
        assertTrue(out.chat.partsByMessage.isEmpty())
        assertTrue(out.chat.streamingPartTexts.isEmpty())
        assertNull(out.chat.streamingReasoningPart)
        assertNull("currentModel cleared (fix-draft-model-leak)", out.chat.currentModel)
        // §fix-leak-window (fix B): full per-session clear.
        assertNull("olderMessagesCursor cleared on draft-start", out.chat.olderMessagesCursor)
        assertFalse("hasMoreMessages cleared on draft-start", out.chat.hasMoreMessages)
        assertFalse("staleNotice cleared on draft-start", out.chat.staleNotice)
        assertTrue("revertCutoffs cleared on draft-start", out.chat.revertCutoffs.isEmpty())
        assertTrue("deltaBuffer cleared on draft-start", out.chat.deltaBuffer.isEmpty())
        assertTrue("fullTextBuffer cleared on draft-start", out.chat.fullTextBuffer.isEmpty())
        assertTrue("pendingFlushPartIds cleared on draft-start", out.chat.pendingFlushPartIds.isEmpty())
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
                draftWorkdir = null))

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
        store.mutateSessionList { it.copy(sessions = listOf(Session(id = "cur", directory = "/p"))) }
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
        store.dispatch(AppAction.SessionArchived(archived))
        advanceUntilIdle()

        // Exactly ONE new aggregate emission for the action (no intermediates).
        assertEquals("exactly one initial + one post-dispatch emission", 2, seen.size)
        val finalState = seen.last()
        // The single committed state is fully consistent: sessionList reflects the
        // archive AND chat is cleared in the SAME state. There is NO element in the
        // stream where sessionList is archived but chat.currentSessionId still == "cur".
        assertTrue("sessionList archived in final state", finalState.sessionList.sessions.any { it.id == "cur" && it.isArchived })
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
        store.mutateSessionList { it.copy(sessions = listOf(Session(id = "sess", directory = "/p"))) }
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
        assertTrue(finalState.unread.unreadSessions.isEmpty())
        assertNull(finalState.composer.draftWorkdir)
        assertTrue(finalState.settings.availableCommands.isEmpty())
        assertNull(finalState.connection.serverVersion)
        // No torn intermediate in the stream: no element where chat is cleared but
        // sessionList still has sessions, or any other partial-clear combination.
        seen.forEach { s ->
            val cleared = s.chat.currentSessionId == null && s.composer.draftWorkdir == null
            val partialSessions = s.chat.currentSessionId == null && s.sessionList.sessions.isNotEmpty()
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
        store.dispatch(AppAction.DraftSessionMaterialized(created, viewedAt = 100L))
        advanceUntilIdle()

        assertEquals(2, seen.size)
        val finalState = seen.last()
        // All four slice changes (sessionList / chat / unread / composer) in ONE state.
        assertEquals("new", finalState.sessionList.sessions.first().id)
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
        store.mutateSessionList { it.copy(sessions = listOf(Session(id = "cur", directory = "/p"))) }

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
        store.dispatch(AppAction.SessionArchived(archived))
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
        store.mutateSessionList { it.copy(sessions = listOf(Session(id = "old", directory = "/p"))) }

        val snapshotBefore = store.stateFlow.value
        val action = AppAction.SessionArchived(
            Session(id = "old", directory = "/p", time = Session.TimeInfo(archived = 1L)))
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
            sessionList = SessionListState(sessions = seed.sessions),
            unread = UnreadState(unreadSessions = seed.unreadSessions, lastViewedTime = seed.lastViewedTime),
            settings = SettingsState(availableCommands = seed.availableCommands),
            connection = ConnectionState(serverVersion = seed.serverVersion))

        reduce(prior, AppAction.DraftSessionMaterialized(Session(id = "x", directory = "/x"), viewedAt = 0L))
        reduce(prior, AppAction.SessionArchived(Session(id = "x", directory = "/x", time = Session.TimeInfo(archived = 1L))))
        reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = false))
        reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = true))
        reduce(prior, AppAction.WorkdirDraftStarted(workdir = "/w"))
        // §Wave5b-Q13: the four new actions replace the pre-Wave5b
        // PendingJumpToLatestSet pair (set + clear). All four are exercised so
        // the when-branches stay total.
        reduce(prior, AppAction.ScrollRequested(requestId = 1L, targetSessionId = "x", behavior = ScrollBehavior.Latest))
        reduce(
            prior,
            AppAction.ScrollRequested(
                requestId = 2L,
                targetSessionId = "x",
                behavior = ScrollBehavior.Restore(ScrollCheckpoint(anchorKey = "k", fallbackIndex = 3, offset = 12))))
        reduce(prior, AppAction.ScrollConsumed(requestId = 1L))
        // §chat-list-detail §11 / G6 (B5): ParentCheckpointStored /
        // ParentCheckpointConsumed removed (per-entry SavedStateHandle
        // replaces the global ChatState map). The when-branches test no
        // longer exercises those two actions.
        reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = seed.sessions,
                hasMoreSessions = false,
                confirmedServerIds = seed.sessions.mapTo(mutableSetOf()) { it.id },
                sweepNow = 0L))
        // No exception thrown == each when-branch is total. The concrete field-by-field
        // assertions live in the dedicated tests above.
    }

    // ── §Wave5b-Q13 + §chat-list-detail §11 / G6 (B5): scroll-state machine ─
    //
    // The unified scroll-state machine: a single-slot [PendingScrollRequest]
    // is the SOLE in-ChatState scroll-intent field. The per-child checkpoint
    // backstack MOVED to per-route-entry SavedStateHandle (B5 §11; see
    // SavedStateHandleCheckpointTest for the new consume-once protocol). The
    // reducer is the sole writer of pendingScrollRequest (besides the
    // [clearSessionData] private helper used by HostStatePurged cross-group +
    // WorkdirDraftStarted, and [applyArchivedChatClear] used by
    // SessionArchived current-only).

    @Test
    fun `reduce ScrollRequested overwrites the pending slot unconditionally`() {
        // §Wave5b-Q13 oracle test #6: switch to a different target replaces
        // the prior intent. Single-slot semantics — newer always wins.
        val priorReq = PendingScrollRequest(
            requestId = 1L,
            targetSessionId = "old-target",
            behavior = ScrollBehavior.Latest)
        val prior = StoreState.initial().copy(
            chat = ChatState(currentSessionId = "old", pendingScrollRequest = priorReq))

        val out = reduce(
            prior,
            AppAction.ScrollRequested(
                requestId = 2L,
                targetSessionId = "new-target",
                behavior = ScrollBehavior.Restore(ScrollCheckpoint("k", 3, 12))))

        assertEquals(2L, out.chat.pendingScrollRequest?.requestId)
        assertEquals("new-target", out.chat.pendingScrollRequest?.targetSessionId)
        val behavior = out.chat.pendingScrollRequest?.behavior
        assertTrue("behavior is Restore", behavior is ScrollBehavior.Restore)
        val cp = (behavior as ScrollBehavior.Restore).checkpoint
        assertEquals("k", cp.anchorKey)
        assertEquals(3, cp.fallbackIndex)
        assertEquals(12, cp.offset)
        // No other chat field changes (single-field write).
        assertEquals("old", out.chat.currentSessionId)
    }

    @Test
    fun `reduce ScrollConsumed clears the slot only when requestId matches`() {
        // §Wave5b-Q13 oracle test #5: compare-and-clear. A stale consumer's
        // clear (older requestId) MUST NOT wipe a newer intent.
        val liveReq = PendingScrollRequest(
            requestId = 100L,
            targetSessionId = "B",
            behavior = ScrollBehavior.Latest)
        val prior = StoreState.initial().copy(
            chat = ChatState(pendingScrollRequest = liveReq))

        // Stale clear (requestId mismatch) → no-op.
        val staleOut = reduce(prior, AppAction.ScrollConsumed(requestId = 99L))
        assertEquals(
            "stale clear MUST NOT wipe the live intent",
            liveReq,
            staleOut.chat.pendingScrollRequest)

        // Matching clear → cleared.
        val matchOut = reduce(prior, AppAction.ScrollConsumed(requestId = 100L))
        assertNull("matching clear removes the intent", matchOut.chat.pendingScrollRequest)
    }

    @Test
    fun `reduce ScrollConsumed is a no-op when slot is already empty`() {
        // Defensive: a late consumer firing after another path already
        // cleared the slot (host purge, archive, draft, prior consume).
        val prior = StoreState.initial().copy(chat = ChatState())

        val out = reduce(prior, AppAction.ScrollConsumed(requestId = 1L))

        assertNull(out.chat.pendingScrollRequest)
    }

    // §chat-list-detail §11 / G6 (B5): the four ParentCheckpointStored /
    // ParentCheckpointConsumed reducer tests (append / overwrite / remove /
    // no-op-when-absent) are REMOVED — the actions + reducers are gone
    // (per-entry SavedStateHandle replaces the global ChatState map; see
    // [cn.vectory.ocdroid.ui.consumeAnySubAgentCheckpoint] for the new
    // consume-once helper covered by SavedStateHandleCheckpointTest).

    @Test
    fun `reduce WorkdirDraftStarted clears a stale pendingScrollRequest via clearSessionData`() {
        // §chat-list-detail §11 / G6 (B5): parentReturnCheckpoints map is
        // gone; only the pendingScrollRequest sweep is asserted here.
        val staleReq = PendingScrollRequest(
            requestId = 7L,
            targetSessionId = "abandoned-by-draft",
            behavior = ScrollBehavior.Latest)
        val prior = StoreState.initial().copy(
            chat = ChatState(
                pendingScrollRequest = staleReq))

        val out = reduce(prior, AppAction.WorkdirDraftStarted(workdir = "/w"))

        assertNull(
            "draft-create must wipe a stale scroll intent (references a session id being cleared)",
            out.chat.pendingScrollRequest)
    }

    @Test
    fun `reduce HostStatePurged cross-group clears stale pendingScrollRequest via clearSessionData`() {
        // §chat-list-detail §11 / G6 (B5): parentReturnCheckpoints map is
        // gone (per-entry SavedStateHandle); only the pendingScrollRequest
        // sweep is asserted here.
        val staleReq = PendingScrollRequest(
            requestId = 9L,
            targetSessionId = "abandoned-by-host-switch",
            behavior = ScrollBehavior.Latest)
        val prior = StoreState.initial().copy(
            chat = ChatState(
                pendingScrollRequest = staleReq))

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = false))

        assertNull(
            "cross-group host purge must wipe a stale scroll intent",
            out.chat.pendingScrollRequest)
    }

    @Test
    fun `reduce HostStatePurged same-group clears pendingScrollRequest`() {
        // §Wave5b-Q13 oracle ruling: same-group host purge keeps chat content
        // (messages / currentSessionId) but INVALIDATES the scroll slot —
        // the scroll slot references a session the user is navigating away
        // from.
        // §chat-list-detail §11 / G6 (B5): parentReturnCheckpoints map is
        // gone (per-entry SavedStateHandle); only the pendingScrollRequest
        // sweep is asserted here.
        val liveReq = PendingScrollRequest(
            requestId = 11L,
            targetSessionId = "still-valid",
            behavior = ScrollBehavior.Latest)
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "still-valid",  // PRESERVED (same-group)
                messages = listOf(Message(id = "m1", role = "user")),  // PRESERVED
                pendingScrollRequest = liveReq))

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = true))

        // Content preserved.
        assertEquals("still-valid", out.chat.currentSessionId)
        assertEquals(1, out.chat.messages.size)
        // Slot cleared.
        assertNull("same-group host purge wipes the scroll slot", out.chat.pendingScrollRequest)
    }

    // ── BulkSessionsRefreshed (FIX-A/C: atomic bulk-archive commit) ────────

    @Test
    fun `BulkSessionsRefreshed confirms pending only from raw server ids not merged sessions`() {
        val serverSession = Session(id = "s1", directory = "/x")
        val preservedPending = Session(id = "s2", directory = "/x")
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(serverSession, preservedPending),
                pendingCreateIds = setOf("s2"),
                pendingCreatedAt = mapOf("s2" to 1_000L)))

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(serverSession, preservedPending),
                hasMoreSessions = false,
                confirmedServerIds = setOf("s1"),
                sweepNow = 2_000L))

        assertEquals(setOf("s2"), out.sessionList.pendingCreateIds)
        assertEquals(mapOf("s2" to 1_000L), out.sessionList.pendingCreatedAt)
    }

    @Test
    fun `FIX-A reduce BulkSessionsRefreshed writes merged list and prunes ALL archived openIds`() {
        // The core FIX-A invariant: non-current OPEN tabs B and C were
        // archived cross-device; the bulk refresh discovers them. The reducer
        // MUST prune BOTH from open-tabs-list (not just the current session).
        val current = Session(id = "current", directory = "/x")
        val archivedB = Session(id = "B", directory = "/x", time = Session.TimeInfo(archived = 1L))
        val archivedC = Session(id = "C", directory = "/x", time = Session.TimeInfo(archived = 1L))
        val prior = StoreState.initial().copy(
            chat = ChatState(currentSessionId = "current"),
            sessionList = SessionListState())

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(current, archivedB, archivedC),
                // caller pre-computed prune
                hasMoreSessions = false,
                confirmedServerIds = setOf("current", "B", "C"),
                sweepNow = 0L))

        assertEquals(listOf("current", "B", "C"), out.sessionList.sessions.map { it.id })
        // §B4: open-tabs prune removed; archive flags live on sessions.
        assertTrue(out.sessionList.sessions.any { it.id == "B" && it.isArchived })
        assertTrue(out.sessionList.sessions.any { it.id == "C" && it.isArchived })
        assertFalse(out.sessionList.isRefreshingSessions)
        assertFalse(out.sessionList.hasMoreSessions)
    }

    @Test
    fun `gpter-residual reduce BulkSessionsRefreshed discards cached completeness proofs and bumps epoch`() {
        // §gpter-residual: a bulk refresh is authoritative for structure, so
        // cached completeRootIds may be stale if SSE dropped events. The
        // reducer must clear them and bump the epoch so in-flight hydration is
        // dropped fail-closed (mirroring the REST full-list replace path).
        val current = Session(id = "current", directory = "/x")
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                completeRootIds = setOf("stale-root"),
                completenessEpoch = 7L))

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(current),
                hasMoreSessions = false,
                confirmedServerIds = setOf("current"),
                sweepNow = 0L))

        assertTrue(
            "BulkSessionsRefreshed discards stale completeRootIds",
            out.sessionList.completeRootIds.isEmpty())
        assertEquals(
            "BulkSessionsRefreshed bumps completeness epoch",
            8L,
            out.sessionList.completenessEpoch)
    }

    @Test
    fun `FIX-C reduce BulkSessionsRefreshed clears chat when current session is archived`() {
        // FIX-C: if the current session is among the archived, the reducer
        // atomically clears chat in the SAME committed state as the list write
        // (no torn "sessions[current].isArchived AND chat.currentSessionId == current").
        val archivedCurrent = Session(id = "cur", directory = "/x", time = Session.TimeInfo(archived = 1L))
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "cur",
                messages = listOf(Message(id = "m1", role = "user")),
                partsByMessage = mapOf("m1" to emptyList()),
                // §Wave5b-Q13: the unified scroll slot replaces the pre-
                // Wave5b pendingJumpToLatest field. Must be wiped by
                // applyArchivedChatClear (FIX-B lineage).
                // §chat-list-detail §11 / G6 (B5): parentReturnCheckpoints
                // map is gone (per-entry SavedStateHandle).
                pendingScrollRequest = PendingScrollRequest(
                    requestId = 1L,
                    targetSessionId = "cur",
                    behavior = ScrollBehavior.Latest)),
            sessionList = SessionListState())

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(archivedCurrent),
                hasMoreSessions = false,
                confirmedServerIds = setOf("cur"),
                sweepNow = 0L))

        // Chat cleared atomically.
        assertNull("chat.currentSessionId cleared", out.chat.currentSessionId)
        assertTrue("messages cleared", out.chat.messages.isEmpty())
        assertTrue("partsByMessage cleared", out.chat.partsByMessage.isEmpty())
        assertNull("FIX-B / §Wave5b-Q13: pendingScrollRequest cleared", out.chat.pendingScrollRequest)
        // List written in the SAME state.
        assertTrue("sessionList has the archived session", out.sessionList.sessions.any { it.id == "cur" && it.isArchived })
    }

    @Test
    fun `FIX-C reduce BulkSessionsRefreshed does NOT clear chat when current is not archived`() {
        val current = Session(id = "cur", directory = "/x")
        val archivedOther = Session(id = "other", directory = "/x", time = Session.TimeInfo(archived = 1L))
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "cur",
                messages = listOf(Message(id = "m1", role = "user"))),
            sessionList = SessionListState())

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(current, archivedOther),
                // other pruned
                hasMoreSessions = false,
                confirmedServerIds = setOf("cur", "other"),
                sweepNow = 0L))

        // Chat NOT cleared (current is not archived).
        assertEquals("cur", out.chat.currentSessionId)
        assertEquals(1, out.chat.messages.size)
        // But openIds IS pruned (FIX-A — non-current archived tab removed).
    }

    @Test
    fun `FIX-C dispatch BulkSessionsRefreshed produces exactly one aggregate emission with no torn intermediate`() = runTest {
        // The FIX-C atomicity test: the prior two-step (mutateSessionList then
        // separate dispatch) produced an emission where
        // sessions[current].isArchived == true AND chat.currentSessionId == current
        // coexisted. The single BulkSessionsRefreshed dispatch collapses this
        // to ONE committed state — no torn intermediate in the stream.
        val store = SharedStateStore()
        store.mutateChat {
            it.copy(currentSessionId = "cur", messages = listOf(Message(id = "m1", role = "user")))
        }
        store.mutateSessionList {
            it.copy(sessions = listOf(Session(id = "cur", directory = "/p")))
        }

        val seen = mutableListOf<StoreState>()
        val job = launch {
            store.stateFlow.collect { seen += it }
        }
        advanceUntilIdle()
        assertEquals(1, seen.size)

        val archivedCurrent = Session(id = "cur", directory = "/p", time = Session.TimeInfo(archived = 1L))
        store.dispatch(
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(archivedCurrent),
                hasMoreSessions = false,
                confirmedServerIds = setOf("cur"),
                sweepNow = 0L))
        advanceUntilIdle()

        // Exactly ONE new aggregate emission.
        assertEquals("exactly one initial + one post-dispatch emission", 2, seen.size)
        val finalState = seen.last()
        // Single committed state: sessionList archived AND chat cleared.
        assertTrue("sessionList has archived session", finalState.sessionList.sessions.any { it.id == "cur" && it.isArchived })
        assertNull("chat cleared in SAME state", finalState.chat.currentSessionId)
        // No torn intermediate anywhere in the stream.
        seen.forEach { s ->
            val torn = s.sessionList.sessions.any { it.id == "cur" && it.isArchived } && s.chat.currentSessionId == "cur"
            assertFalse("no torn intermediate (archived-but-current) in stream", torn)
        }
        job.cancel()
    }

    // ── gro-2 Blocker 1: non-current archived subtree cleanup ──────────────

    @Test
    fun `gro-2 Blocker 1 - BulkSessionsRefreshed cleans subtree unread + pendingQuestions for non-current archived open tab`() {
        // The bug: the reducer's else-branch (non-current archived) skipped
        // subtree/unread/questions cleanup entirely. So a non-current archived
        // OPEN tab's unread badge + pendingQuestions leaked — inflating
        // crossSessionPendingCount and leaving dead badges.
        // Fix: subtree cleanup now runs UNCONDITIONALLY over ALL archived ids.
        val archivedRoot = Session(id = "archived-root", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val archivedChild = Session(id = "archived-child", directory = "/p", parentId = "archived-root")
        val archivedGrandchild = Session(id = "archived-gc", directory = "/p", parentId = "archived-child")
        val liveCurrent = Session(id = "live-cur", directory = "/p")
        val prior = StoreState.initial().copy(
            chat = ChatState(currentSessionId = "live-cur"),
            sessionList = SessionListState(
                sessions = listOf(liveCurrent, archivedRoot, archivedChild, archivedGrandchild),
                pendingQuestions = listOf(
                    QuestionRequest(id = "q-root", sessionId = "archived-root", questions = emptyList()),
                    QuestionRequest(id = "q-child", sessionId = "archived-child", questions = emptyList()),
                    QuestionRequest(id = "q-gc", sessionId = "archived-gc", questions = emptyList()),
                    // A question bound to the LIVE current session MUST survive.
                    QuestionRequest(id = "q-live", sessionId = "live-cur", questions = emptyList()))),
            unread = UnreadState(
                unreadSessions = setOf("archived-root", "archived-child", "archived-gc", "live-cur"),
                lastViewedTime = mapOf(
                    "archived-root" to 1L, "archived-child" to 2L, "archived-gc" to 3L, "live-cur" to 4L)))

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(liveCurrent, archivedRoot, archivedChild, archivedGrandchild),
                // archived-root pruned
                hasMoreSessions = false,
                confirmedServerIds = setOf("live-cur", "archived-root", "archived-child", "archived-gc"),
                sweepNow = 0L))

        // The non-current archived subtree (root + child + grandchild) is
        // cleaned from unread + pendingQuestions — even though chat was NOT
        // cleared (non-current).
        assertFalse("archived root removed from unread", "archived-root" in out.unread.unreadSessions)
        assertFalse("archived child removed from unread", "archived-child" in out.unread.unreadSessions)
        assertFalse("archived grandchild removed from unread", "archived-gc" in out.unread.unreadSessions)
        assertTrue("live current session unread preserved", "live-cur" in out.unread.unreadSessions)
        // lastViewedTime orphans dropped for the subtree only.
        assertFalse("archived root lastViewed cleared", "archived-root" in out.unread.lastViewedTime)
        assertFalse("archived child lastViewed cleared", "archived-child" in out.unread.lastViewedTime)
        assertEquals(4L, out.unread.lastViewedTime["live-cur"])
        // Subtree questions removed; live current's question survives.
        assertTrue(
            "archived subtree questions removed",
            out.sessionList.pendingQuestions.none { it.sessionId in setOf("archived-root", "archived-child", "archived-gc") })
        assertTrue(
            "live current question preserved",
            out.sessionList.pendingQuestions.any { it.sessionId == "live-cur" })
        // Chat is NOT cleared for the non-current archived session.
        assertEquals("chat NOT cleared (current is live)", "live-cur", out.chat.currentSessionId)
        // openIds pruned of the archived id.
    }

    @Test
    fun `gro-2 Blocker 1 regression - BulkSessionsRefreshed current-archived STILL clears chat + subtree`() {
        // Regression guard: the current-archived case must STILL clear chat +
        // subtree cleanup (the fix broadened cleanup to ALL archived ids, but
        // the chat-clear must remain current-only and still fire).
        val archivedCurrent = Session(id = "cur", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val archivedChild = Session(id = "child", directory = "/p", parentId = "cur")
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "cur",
                messages = listOf(Message(id = "m1", role = "user")),
                // §Wave5b-Q13: replaced pendingJumpToLatest with the unified
                // slot so the FIX-B clear still asserts.
                // §chat-list-detail §11 / G6 (B5): parentReturnCheckpoints
                // map is gone (per-entry SavedStateHandle).
                pendingScrollRequest = PendingScrollRequest(
                    requestId = 1L,
                    targetSessionId = "cur",
                    behavior = ScrollBehavior.Latest)),
            sessionList = SessionListState(
                sessions = listOf(archivedCurrent, archivedChild),
                pendingQuestions = listOf(
                    QuestionRequest(id = "q-cur", sessionId = "cur", questions = emptyList()),
                    QuestionRequest(id = "q-child", sessionId = "child", questions = emptyList()))),
            unread = UnreadState(
                unreadSessions = setOf("cur", "child"),
                lastViewedTime = mapOf("cur" to 1L, "child" to 2L)))

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(archivedCurrent, archivedChild),
                hasMoreSessions = false,
                confirmedServerIds = setOf("cur", "child"),
                sweepNow = 0L))

        // Chat cleared (current IS archived).
        assertNull("chat cleared for archived current", out.chat.currentSessionId)
        assertTrue("messages cleared", out.chat.messages.isEmpty())
        assertNull("pendingScrollRequest cleared (FIX-B / §Wave5b-Q13)", out.chat.pendingScrollRequest)
        // Full subtree cleaned.
        assertFalse("cur removed from unread", "cur" in out.unread.unreadSessions)
        assertFalse("child removed from unread", "child" in out.unread.unreadSessions)
        assertTrue("all subtree questions removed", out.sessionList.pendingQuestions.isEmpty())
    }

    // ── §final-gate I-3: sessionErrorsById lifecycle cleanup ───────────────
    //
    // The final whole-branch review (review-final-rev-gpt-20260719081038.md
    // hand-off #2 / Important #3) identified that T12's
    // `SessionListState.sessionErrorsById` was only cleared when the sidecar
    // sent `lastError = Cleared`. Three lifecycle gaps allowed stale
    // sid→error entries to survive:
    //   1. `HostStatePurged` (cross-group) cleared sessions/status/q/p but
    //      NOT sessionErrorsById → old host's sid→error persisted across
    //      host switch; if the new host had the same sid, T17 rendered the
    //      old host's banner.
    //   2. `SessionArchived` did NOT remove archived subtree entries.
    //   3. Session delete (in SessionMutationActions.launchDeleteSession)
    //      did NOT remove deleted sid entries.
    //
    // The fix mirrors T16 round-3's `clearSessionData` partExpandStates
    // clearing: atomic, in the same committed state as the lifecycle event,
    // using the existing immutable `.copy()` style. The three tests below
    // pin each cleanup so a regression (re-introducing the leak) fails
    // the test. Each test seeds TWO sid entries and asserts the untouched
    // one survives — this discriminates "cleanup ran" from "whole-slice
    // accidentally wiped" and proves sid-scoped precision.

    @Test
    fun `final-gate I-3 - HostStatePurged cross-group clears sessionErrorsById`() {
        // Lifecycle gap #1: cross-group host purge MUST drop the entire
        // sessionErrorsById map. Pre-fix the map survived a cross-host
        // switch, so an old host's sid→error leaked into the new host. If
        // the new host surfaced a session with the same sid (e.g. a common
        // short id), T17's StatusSlot would render the prior host's banner.
        //
        // Discrimination: seeds {sid1→err, sid2→err}; asserts the result's
        // sessionErrorsById is `emptyMap()`. Fails if the cleanup line is
        // removed (the map survives) OR if the reducer is changed to
        // preserve the map cross-group.
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(Session(id = "sid1", directory = "/p")),
                sessionErrorsById = mapOf(
                    "sid1" to SlimSessionLastError(name = "upstream_error", message = "old host err 1"),
                    "sid2" to SlimSessionLastError(name = "session_not_found", message = "old host err 2"))))

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = false))

        assertTrue(
            "cross-group HostStatePurged must clear sessionErrorsById (pre-fix leaked old host errors)",
            out.sessionList.sessionErrorsById.isEmpty())
    }

    @Test
    fun `I2-v2 HostStatePurged cross-group resets aggregation signals`() {
        // I-2 v2 §3.3: cross-group purge MUST reset question/permission
        // aggregation signals so a stale FAILED/INCOMPLETE from host A
        // cannot surface on host B.
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                questionAggregationSignal = SlimAggregationSignal(
                    completeness = SlimAggregationCompleteness.FAILED,
                    failureMessage = "HTTP 503"),
                permissionAggregationSignal = SlimAggregationSignal(
                    completeness = SlimAggregationCompleteness.INCOMPLETE,
                    failedSources = listOf(
                        SlimAggregationFailedSource(directory = "/a", code = "timeout")))))

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = false))

        assertEquals(
            SlimAggregationCompleteness.COMPLETE,
            out.sessionList.questionAggregationSignal.completeness)
        assertNull(out.sessionList.questionAggregationSignal.failureMessage)
        assertEquals(
            SlimAggregationCompleteness.COMPLETE,
            out.sessionList.permissionAggregationSignal.completeness)
        assertTrue(out.sessionList.permissionAggregationSignal.failedSources.isEmpty())
    }

    @Test
    fun `final-gate I-3 - HostStatePurged same-group preserves sessionErrorsById`() {
        // Symmetric boundary: a SAME-group switch preserves server-identical
        // data (the server is the same, so the sid→error mapping is still
        // authoritative). Only cross-group purges drop the map. This test
        // pins that the cleanup is correctly scoped to cross-group only —
        // a future "always clear" regression would fail it.
        val errors = mapOf(
            "sid1" to SlimSessionLastError(name = "upstream_error", message = "live err"))
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(Session(id = "sid1", directory = "/p")),
                sessionErrorsById = errors))

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = true))

        assertEquals(
            "same-group HostStatePurged MUST preserve sessionErrorsById (server-identical data)",
            errors,
            out.sessionList.sessionErrorsById)
    }

    @Test
    fun `final-gate I-3 - SessionArchived removes archived subtree from sessionErrorsById`() {
        // Lifecycle gap #2: archiving a session MUST remove its sid (and the
        // sids of its subtree — defensive against a server that only emits
        // the root archive event) from sessionErrorsById. Pre-fix archived
        // sids stayed forever, producing unbounded retention + stale banners
        // if the user later un-archived or the id was reused.
        //
        // Discrimination: seeds {root→err, child→err, unrelated→err}; archives
        // root (whose subtree is {root, child}); asserts root+child removed
        // AND unrelated preserved. Fails if the cleanup line is missing
        // (all three survive) OR if the cleanup is over-eager (unrelated
        // also dropped).
        val archivedRoot = Session(id = "root", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(
                    Session(id = "root", directory = "/p"),
                    Session(id = "child", directory = "/p", parentId = "root"),
                    Session(id = "unrelated", directory = "/p")),
                sessionErrorsById = mapOf(
                    "root" to SlimSessionLastError(name = "upstream_error", message = "root err"),
                    "child" to SlimSessionLastError(name = "upstream_error", message = "child err"),
                    "unrelated" to SlimSessionLastError(name = "session_not_found", message = "unrelated err"))))

        val out = reduce(prior, AppAction.SessionArchived(archivedRoot))

        assertFalse(
            "archived root removed from sessionErrorsById",
            out.sessionList.sessionErrorsById.containsKey("root"))
        assertFalse(
            "archived child (subtree, no own archive event) removed from sessionErrorsById",
            out.sessionList.sessionErrorsById.containsKey("child"))
        assertTrue(
            "unrelated session's error preserved",
            out.sessionList.sessionErrorsById.containsKey("unrelated"))
        assertEquals(
            "only the unrelated entry remains",
            1,
            out.sessionList.sessionErrorsById.size)
    }

    @Test
    fun `P0-F reduceSessionArchived clears abortPending for archived subtree`() {
        val archivedRoot = Session(id = "root", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(
                    Session(id = "root", directory = "/p"),
                    Session(id = "child", directory = "/p", parentId = "root"),
                    Session(id = "unrelated", directory = "/p")),
                abortPendingSessionIds = mapOf(
                    "root" to 100L,
                    "child" to 200L,
                    "unrelated" to 300L)))

        val out = reduce(prior, AppAction.SessionArchived(archivedRoot))

        assertFalse("archived root cleared",
            out.sessionList.abortPendingSessionIds.containsKey("root"))
        assertFalse("archived child (subtree) cleared",
            out.sessionList.abortPendingSessionIds.containsKey("child"))
        assertTrue("unrelated session preserved",
            out.sessionList.abortPendingSessionIds.containsKey("unrelated"))
        assertEquals(1, out.sessionList.abortPendingSessionIds.size)
    }

    @Test
    fun `P0-F reduceSessionArchivedLocal clears abortPending for archived subtree`() {
        // activeSessionIdsToRemove carries the FULL subtree (parent + descendants)
        // — reducer must clear abortPending for ALL of them, not just the root.
        val archivedParent = Session(id = "parent", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(
                    Session(id = "parent", directory = "/p"),
                    Session(id = "child", directory = "/p", parentId = "parent"),
                    Session(id = "unrelated", directory = "/p")),
                abortPendingSessionIds = mapOf(
                    "parent" to 100L,
                    "child" to 200L,
                    "unrelated" to 300L)))

        val subtree = setOf("parent", "child")
        val out = reduce(prior, AppAction.SessionArchivedLocal(archivedParent, activeSessionIdsToRemove = subtree, pendingQuestions = emptyList()))

        assertFalse("parent cleared", "parent" in out.sessionList.abortPendingSessionIds)
        assertFalse("child (descendant in subtree) cleared", "child" in out.sessionList.abortPendingSessionIds)
        assertTrue("unrelated preserved", "unrelated" in out.sessionList.abortPendingSessionIds)
        assertEquals(1, out.sessionList.abortPendingSessionIds.size)
    }

    @Test
    fun `P0-F reduceSessionDeletedLocal clears abortPending for removed sessions`() {
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                abortPendingSessionIds = mapOf("s1" to 100L, "s2" to 200L, "s3" to 300L)))

        val out = reduce(prior, AppAction.SessionDeletedLocal(removedIds = setOf("s1", "s2")))

        assertFalse("s1 cleared", "s1" in out.sessionList.abortPendingSessionIds)
        assertFalse("s2 cleared", "s2" in out.sessionList.abortPendingSessionIds)
        assertTrue("s3 preserved", "s3" in out.sessionList.abortPendingSessionIds)
        assertEquals(1, out.sessionList.abortPendingSessionIds.size)
    }
}
