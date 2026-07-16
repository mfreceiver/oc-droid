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
        assertEquals(setOf("new"), out.sessionList.pendingCreateIds)
        assertEquals(mapOf("new" to 123L), out.sessionList.pendingCreatedAt)
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
                openSessionIds = listOf("sess-1"),
                pendingQuestions = listOf(
                    QuestionRequest(
                        id = "q1", sessionId = "sess-1",
                        questions = listOf(QuestionInfo("q?", "h", listOf(QuestionOption("a", "b")))),
                    ),
                    // A question bound to an unrelated session MUST survive.
                    QuestionRequest(
                        id = "q2", sessionId = "sess-other",
                        questions = listOf(QuestionInfo("q?", "h", listOf(QuestionOption("a", "b")))),
                    ),
                ),
                pendingCreateIds = setOf("s1"),
                pendingCreatedAt = mapOf("s1" to 123L),
            ),
            unread = UnreadState(
                unreadSessions = setOf("sess-1", "sess-other"),
                lastViewedTime = mapOf("sess-1" to 1L, "sess-other" to 2L),
            ),
        )

        val out = reduce(prior, AppAction.SessionArchived(archived, openSessionIds = emptyList()))

        // Archived id removed from unread; unrelated id preserved.
        assertFalse("archived id removed from unreadSessions", out.unread.unreadSessions.contains("sess-1"))
        assertTrue("unrelated id preserved in unreadSessions", out.unread.unreadSessions.contains("sess-other"))
        // lastViewedTime for the archived id also dropped (no orphan entry).
        assertFalse("archived id removed from lastViewedTime", out.unread.lastViewedTime.containsKey("sess-1"))
        assertEquals(2L, out.unread.lastViewedTime["sess-other"])
        // Question bound to the archived id removed; the unrelated one survives.
        assertTrue(
            "archived session question removed",
            out.sessionList.pendingQuestions.none { it.sessionId == "sess-1" },
        )
        assertTrue(
            "unrelated session question preserved",
            out.sessionList.pendingQuestions.any { it.sessionId == "sess-other" },
        )
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
                    Session(id = "unrelated", directory = "/p"),
                ),
                openSessionIds = listOf("root"),
                pendingQuestions = listOf(
                    QuestionRequest(id = "q-root", sessionId = "root", questions = emptyList()),
                    QuestionRequest(id = "q-child", sessionId = "child", questions = emptyList()),
                    QuestionRequest(id = "q-grandchild", sessionId = "grandchild", questions = emptyList()),
                    QuestionRequest(id = "q-unrelated", sessionId = "unrelated", questions = emptyList()),
                ),
            ),
            unread = UnreadState(
                unreadSessions = setOf("root", "child", "grandchild", "unrelated"),
                lastViewedTime = mapOf(
                    "root" to 1L, "child" to 2L, "grandchild" to 3L, "unrelated" to 4L,
                ),
            ),
        )

        val out = reduce(prior, AppAction.SessionArchived(archivedRoot, openSessionIds = emptyList()))

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
            out.sessionList.pendingQuestions.none { it.sessionId in setOf("root", "child", "grandchild") },
        )
        assertTrue(
            "unrelated question preserved",
            out.sessionList.pendingQuestions.any { it.sessionId == "unrelated" },
        )
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
            behavior = ScrollBehavior.Latest,
        )
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "cur",  // NOT the archived id
                messages = listOf(Message(id = "m1", role = "user")),
                partsByMessage = mapOf("m1" to emptyList()),
                pendingScrollRequest = staleReq,
            ),
            sessionList = SessionListState(
                sessions = listOf(
                    Session(id = "cur", directory = "/p"),
                    Session(id = "stale-target", directory = "/p"),
                ),
                openSessionIds = listOf("cur", "stale-target"),
            ),
        )

        val out = reduce(prior, AppAction.SessionArchived(archived, openSessionIds = listOf("cur")))

        // Chat CONTENT untouched (non-current archived).
        assertEquals("cur", out.chat.currentSessionId)
        assertEquals(1, out.chat.messages.size)
        assertEquals(1, out.chat.partsByMessage.size)
        // Stale scroll intent wiped.
        assertNull(
            "non-current archived target's pendingScrollRequest MUST be wiped",
            out.chat.pendingScrollRequest,
        )
    }

    @Test
    fun `Wave5b-Q13 blocker-2 - SessionArchived clears parentReturnCheckpoints entries keyed by the archived subtree (non-current)`() {
        // A child in the archived subtree had a checkpoint entry. The entry
        // MUST be wiped (the user can never navigate "back" to a parent from
        // an archived child). The current session is unrelated → its own
        // entries MUST survive.
        val archivedRoot = Session(id = "archived-root", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "live-cur",
                parentReturnCheckpoints = mapOf(
                    "archived-child" to ScrollCheckpoint(anchorKey = "k1", fallbackIndex = 1, offset = 1),
                    "live-cur" to ScrollCheckpoint(anchorKey = "k2", fallbackIndex = 2, offset = 2),
                ),
            ),
            sessionList = SessionListState(
                sessions = listOf(
                    Session(id = "live-cur", directory = "/p"),
                    Session(id = "archived-root", directory = "/p"),
                    Session(id = "archived-child", directory = "/p", parentId = "archived-root"),
                ),
                openSessionIds = listOf("live-cur", "archived-root"),
            ),
        )

        val out = reduce(prior, AppAction.SessionArchived(archivedRoot, openSessionIds = listOf("live-cur")))

        // Subtree-keyed entry removed; live entry preserved.
        assertFalse(
            "archived-child entry MUST be wiped",
            out.chat.parentReturnCheckpoints.containsKey("archived-child"),
        )
        assertEquals(
            "live-cur entry MUST survive (not in archived subtree)",
            ScrollCheckpoint(anchorKey = "k2", fallbackIndex = 2, offset = 2),
            out.chat.parentReturnCheckpoints["live-cur"],
        )
    }

    @Test
    fun `Wave5b-Q13 blocker-2 - SessionArchived preserves pendingScrollRequest targeting an UNRELATED session`() {
        // Defensive: the cleanup MUST NOT over-reach. A pendingScrollRequest
        // targeting a session NOT in the archived subtree survives.
        val archived = Session(id = "archived", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val liveReq = PendingScrollRequest(
            requestId = 11L,
            targetSessionId = "live-future-target",
            behavior = ScrollBehavior.Latest,
        )
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "cur",
                pendingScrollRequest = liveReq,
            ),
            sessionList = SessionListState(
                sessions = listOf(
                    Session(id = "cur", directory = "/p"),
                    Session(id = "archived", directory = "/p"),
                    Session(id = "live-future-target", directory = "/p"),
                ),
                openSessionIds = listOf("cur", "archived", "live-future-target"),
            ),
        )

        val out = reduce(prior, AppAction.SessionArchived(archived, openSessionIds = listOf("cur", "live-future-target")))

        assertEquals(liveReq, out.chat.pendingScrollRequest)
    }

    @Test
    fun `Wave5b-Q13 blocker-2 - SessionArchived current-archived case still wipes both fields (no regression)`() {
        // Regression guard: the existing current-archive clear path
        // (applyArchivedChatClear) is unchanged — both fields wiped, chat
        // content also wiped. The new cleanScrollStateForSubtree call is a
        // no-op on top (idempotent), so the assertion is the same as pre-fix.
        val archived = Session(id = "cur", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "cur",
                messages = listOf(Message(id = "m1", role = "user")),
                partsByMessage = mapOf("m1" to emptyList()),
                pendingScrollRequest = PendingScrollRequest(
                    requestId = 1L,
                    targetSessionId = "cur",
                    behavior = ScrollBehavior.Latest,
                ),
                parentReturnCheckpoints = mapOf("cur" to ScrollCheckpoint(null, 0, 0)),
            ),
            sessionList = SessionListState(openSessionIds = listOf("cur")),
        )

        val out = reduce(prior, AppAction.SessionArchived(archived, openSessionIds = emptyList()))

        assertNull("current-archived: currentSessionId cleared", out.chat.currentSessionId)
        assertTrue("current-archived: messages cleared", out.chat.messages.isEmpty())
        assertTrue("current-archived: partsByMessage cleared", out.chat.partsByMessage.isEmpty())
        assertNull("current-archived: pendingScrollRequest cleared", out.chat.pendingScrollRequest)
        assertTrue("current-archived: parentReturnCheckpoints cleared", out.chat.parentReturnCheckpoints.isEmpty())
    }

    @Test
    fun `Wave5b-Q13 blocker-2 - BulkSessionsRefreshed cleans scroll state for non-current archived subtree`() {
        // Same rule, BulkSessionsRefreshed path. A bulk refresh can archive
        // multiple ids cross-device; each archived subtree's scroll state is
        // cleaned even when the current session is NOT among the archived.
        val archivedOther = Session(id = "other", directory = "/p", time = Session.TimeInfo(archived = 1L))
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "cur",  // NOT archived
                pendingScrollRequest = PendingScrollRequest(
                    requestId = 5L,
                    targetSessionId = "other",  // in archived subtree
                    behavior = ScrollBehavior.Latest,
                ),
                parentReturnCheckpoints = mapOf(
                    "other-child" to ScrollCheckpoint(null, 0, 0),
                    "cur" to ScrollCheckpoint(anchorKey = "k", fallbackIndex = 0, offset = 0),
                ),
            ),
            sessionList = SessionListState(
                sessions = listOf(
                    Session(id = "cur", directory = "/p"),
                    archivedOther,
                    Session(id = "other-child", directory = "/p", parentId = "other"),
                ),
                openSessionIds = listOf("cur", "other"),
            ),
        )

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(
                    Session(id = "cur", directory = "/p"),
                    archivedOther,
                    Session(id = "other-child", directory = "/p", parentId = "other"),
                ),
                openSessionIds = listOf("cur"),
                hasMoreSessions = false,
                confirmedServerIds = setOf("cur", "other", "other-child"),
                sweepNow = 0L,
            ),
        )

        // Chat content preserved (current not archived).
        assertEquals("cur", out.chat.currentSessionId)
        // Stale scroll state for the archived subtree wiped.
        assertNull(
            "BulkSessionsRefreshed: pendingScrollRequest targeting archived id wiped",
            out.chat.pendingScrollRequest,
        )
        assertFalse(
            "BulkSessionsRefreshed: archived subtree checkpoint entry wiped",
            out.chat.parentReturnCheckpoints.containsKey("other-child"),
        )
        // Live entry preserved.
        assertEquals(
            "BulkSessionsRefreshed: live checkpoint entry preserved",
            ScrollCheckpoint(anchorKey = "k", fallbackIndex = 0, offset = 0),
            out.chat.parentReturnCheckpoints["cur"],
        )
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
                openSessionIds = listOf("s1"),
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
                        questions = listOf(QuestionInfo("q?", "h", listOf(QuestionOption("a", "b")))),
                    ),
                ),
            ),
            unread = UnreadState(
                unreadSessions = setOf("s1"),
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
                completenessEpoch = 9L,
            ),
        )

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = false))

        assertTrue(
            "childSessions cleared cross-host",
            out.sessionList.childSessions.isEmpty(),
        )
        assertTrue(
            "completeRootIds cleared cross-host",
            out.sessionList.completeRootIds.isEmpty(),
        )
        assertEquals(
            "completeness epoch bumped on purge",
            10L,
            out.sessionList.completenessEpoch,
        )
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
                pendingCreateIds = setOf("s1"),
                pendingCreatedAt = mapOf("s1" to 123L),
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
        // §Wave5b-Q13: the four new actions replace the pre-Wave5b
        // PendingJumpToLatestSet pair (set + clear). All four are exercised so
        // the when-branches stay total.
        reduce(prior, AppAction.ScrollRequested(requestId = 1L, targetSessionId = "x", behavior = ScrollBehavior.Latest))
        reduce(
            prior,
            AppAction.ScrollRequested(
                requestId = 2L,
                targetSessionId = "x",
                behavior = ScrollBehavior.Restore(ScrollCheckpoint(anchorKey = "k", fallbackIndex = 3, offset = 12)),
            ),
        )
        reduce(prior, AppAction.ScrollConsumed(requestId = 1L))
        reduce(prior, AppAction.ParentCheckpointStored("child", ScrollCheckpoint(anchorKey = null, fallbackIndex = 0, offset = 0)))
        reduce(prior, AppAction.ParentCheckpointConsumed("child"))
        reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = seed.sessions,
                openSessionIds = seed.openSessionIds,
                hasMoreSessions = false,
                confirmedServerIds = seed.sessions.mapTo(mutableSetOf()) { it.id },
                sweepNow = 0L,
            ),
        )
        // No exception thrown == each when-branch is total. The concrete field-by-field
        // assertions live in the dedicated tests above.
    }

    // ── §Wave5b-Q13: PendingScrollRequest + ParentCheckpoint actions ────────
    //
    // The unified scroll-state machine: a single-slot [PendingScrollRequest]
    // + a per-child [parentReturnCheckpoints] backstack. The reducer is the
    // sole writer of these fields (besides the [clearSessionData] private
    // helper used by HostStatePurged cross-group + WorkdirDraftStarted, and
    // [applyArchivedChatClear] used by SessionArchived current-only).

    @Test
    fun `reduce ScrollRequested overwrites the pending slot unconditionally`() {
        // §Wave5b-Q13 oracle test #6: switch to a different target replaces
        // the prior intent. Single-slot semantics — newer always wins.
        val priorReq = PendingScrollRequest(
            requestId = 1L,
            targetSessionId = "old-target",
            behavior = ScrollBehavior.Latest,
        )
        val prior = StoreState.initial().copy(
            chat = ChatState(currentSessionId = "old", pendingScrollRequest = priorReq),
        )

        val out = reduce(
            prior,
            AppAction.ScrollRequested(
                requestId = 2L,
                targetSessionId = "new-target",
                behavior = ScrollBehavior.Restore(ScrollCheckpoint("k", 3, 12)),
            ),
        )

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
            behavior = ScrollBehavior.Latest,
        )
        val prior = StoreState.initial().copy(
            chat = ChatState(pendingScrollRequest = liveReq),
        )

        // Stale clear (requestId mismatch) → no-op.
        val staleOut = reduce(prior, AppAction.ScrollConsumed(requestId = 99L))
        assertEquals(
            "stale clear MUST NOT wipe the live intent",
            liveReq,
            staleOut.chat.pendingScrollRequest,
        )

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

    @Test
    fun `reduce ParentCheckpointStored appends the (childId, checkpoint) entry preserving existing entries`() {
        // §Wave5b-Q13 oracle test #9: nested root→child→grandchild chain.
        // Each openSubAgent stores its own parent's checkpoint; entries are
        // independent and additive.
        val firstCp = ScrollCheckpoint(anchorKey = "msg-1", fallbackIndex = 5, offset = 10)
        val prior = StoreState.initial().copy(
            chat = ChatState(
                parentReturnCheckpoints = mapOf("child-A" to firstCp),
            ),
        )
        val secondCp = ScrollCheckpoint(anchorKey = "msg-2", fallbackIndex = 2, offset = 20)

        val out = reduce(prior, AppAction.ParentCheckpointStored("child-B", secondCp))

        assertEquals(2, out.chat.parentReturnCheckpoints.size)
        assertEquals(firstCp, out.chat.parentReturnCheckpoints["child-A"])
        assertEquals(secondCp, out.chat.parentReturnCheckpoints["child-B"])
    }

    @Test
    fun `reduce ParentCheckpointStored overwrites an existing entry for the same childId`() {
        // Re-opening the same child (rare) replaces the prior checkpoint.
        val old = ScrollCheckpoint(anchorKey = "old-key", fallbackIndex = 1, offset = 1)
        val new = ScrollCheckpoint(anchorKey = "new-key", fallbackIndex = 9, offset = 9)
        val prior = StoreState.initial().copy(
            chat = ChatState(parentReturnCheckpoints = mapOf("child" to old)),
        )

        val out = reduce(prior, AppAction.ParentCheckpointStored("child", new))

        assertEquals(1, out.chat.parentReturnCheckpoints.size)
        assertEquals(new, out.chat.parentReturnCheckpoints["child"])
    }

    @Test
    fun `reduce ParentCheckpointConsumed removes only the matching childId`() {
        val cpA = ScrollCheckpoint(anchorKey = "a", fallbackIndex = 0, offset = 0)
        val cpB = ScrollCheckpoint(anchorKey = "b", fallbackIndex = 0, offset = 0)
        val prior = StoreState.initial().copy(
            chat = ChatState(parentReturnCheckpoints = mapOf("child-A" to cpA, "child-B" to cpB)),
        )

        val out = reduce(prior, AppAction.ParentCheckpointConsumed("child-A"))

        assertNull("child-A removed", out.chat.parentReturnCheckpoints["child-A"])
        assertEquals("child-B preserved", cpB, out.chat.parentReturnCheckpoints["child-B"])
    }

    @Test
    fun `reduce ParentCheckpointConsumed is a no-op when the childId is absent`() {
        // Defensive: a double-consume (rare race) MUST NOT throw.
        val prior = StoreState.initial().copy(chat = ChatState())

        val out = reduce(prior, AppAction.ParentCheckpointConsumed("missing"))

        assertTrue(out.chat.parentReturnCheckpoints.isEmpty())
    }

    @Test
    fun `reduce WorkdirDraftStarted clears a stale pendingScrollRequest + parentReturnCheckpoints via clearSessionData`() {
        val staleReq = PendingScrollRequest(
            requestId = 7L,
            targetSessionId = "abandoned-by-draft",
            behavior = ScrollBehavior.Latest,
        )
        val prior = StoreState.initial().copy(
            chat = ChatState(
                pendingScrollRequest = staleReq,
                parentReturnCheckpoints = mapOf("orphan-child" to ScrollCheckpoint(null, 0, 0)),
            ),
        )

        val out = reduce(prior, AppAction.WorkdirDraftStarted(workdir = "/w"))

        assertNull(
            "draft-create must wipe a stale scroll intent (references a session id being cleared)",
            out.chat.pendingScrollRequest,
        )
        assertTrue(
            "draft-create must wipe the parent-return backstack (navigation context lost)",
            out.chat.parentReturnCheckpoints.isEmpty(),
        )
    }

    @Test
    fun `reduce HostStatePurged cross-group clears stale pendingScrollRequest + parentReturnCheckpoints via clearSessionData`() {
        val staleReq = PendingScrollRequest(
            requestId = 9L,
            targetSessionId = "abandoned-by-host-switch",
            behavior = ScrollBehavior.Latest,
        )
        val prior = StoreState.initial().copy(
            chat = ChatState(
                pendingScrollRequest = staleReq,
                parentReturnCheckpoints = mapOf("orphan" to ScrollCheckpoint(null, 0, 0)),
            ),
        )

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = false))

        assertNull(
            "cross-group host purge must wipe a stale scroll intent",
            out.chat.pendingScrollRequest,
        )
        assertTrue(
            "cross-group host purge must wipe the parent-return backstack",
            out.chat.parentReturnCheckpoints.isEmpty(),
        )
    }

    @Test
    fun `reduce HostStatePurged same-group clears pendingScrollRequest + parentReturnCheckpoints`() {
        // §Wave5b-Q13 oracle ruling: same-group host purge keeps chat content
        // (messages / currentSessionId) but INVALIDATES the scroll slot +
        // backstack — the scroll slot references a session the user is
        // navigating away from; the backstack is per-session navigation
        // context with no carry across profiles.
        val liveReq = PendingScrollRequest(
            requestId = 11L,
            targetSessionId = "still-valid",
            behavior = ScrollBehavior.Latest,
        )
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "still-valid",  // PRESERVED (same-group)
                messages = listOf(Message(id = "m1", role = "user")),  // PRESERVED
                pendingScrollRequest = liveReq,
                parentReturnCheckpoints = mapOf("c" to ScrollCheckpoint(null, 0, 0)),
            ),
        )

        val out = reduce(prior, AppAction.HostStatePurged(preserveServerGroupData = true))

        // Content preserved.
        assertEquals("still-valid", out.chat.currentSessionId)
        assertEquals(1, out.chat.messages.size)
        // Slot + backstack cleared.
        assertNull("same-group host purge wipes the scroll slot", out.chat.pendingScrollRequest)
        assertTrue(
            "same-group host purge wipes the parent-return backstack",
            out.chat.parentReturnCheckpoints.isEmpty(),
        )
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
                pendingCreatedAt = mapOf("s2" to 1_000L),
            ),
        )

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(serverSession, preservedPending),
                openSessionIds = emptyList(),
                hasMoreSessions = false,
                confirmedServerIds = setOf("s1"),
                sweepNow = 2_000L,
            ),
        )

        assertEquals(setOf("s2"), out.sessionList.pendingCreateIds)
        assertEquals(mapOf("s2" to 1_000L), out.sessionList.pendingCreatedAt)
    }

    @Test
    fun `FIX-A reduce BulkSessionsRefreshed writes merged list and prunes ALL archived openIds`() {
        // The core FIX-A invariant: non-current OPEN tabs B and C were
        // archived cross-device; the bulk refresh discovers them. The reducer
        // MUST prune BOTH from openSessionIds (not just the current session).
        val current = Session(id = "current", directory = "/x")
        val archivedB = Session(id = "B", directory = "/x", time = Session.TimeInfo(archived = 1L))
        val archivedC = Session(id = "C", directory = "/x", time = Session.TimeInfo(archived = 1L))
        val prior = StoreState.initial().copy(
            chat = ChatState(currentSessionId = "current"),
            sessionList = SessionListState(
                openSessionIds = listOf("current", "B", "C"),
            ),
        )

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(current, archivedB, archivedC),
                openSessionIds = listOf("current"),  // caller pre-computed prune
                hasMoreSessions = false,
                confirmedServerIds = setOf("current", "B", "C"),
                sweepNow = 0L,
            ),
        )

        assertEquals(listOf("current", "B", "C"), out.sessionList.sessions.map { it.id })
        assertEquals(
            "FIX-A: ALL archived ids pruned from openSessionIds",
            listOf("current"),
            out.sessionList.openSessionIds,
        )
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
                openSessionIds = listOf("current"),
                completeRootIds = setOf("stale-root"),
                completenessEpoch = 7L,
            ),
        )

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(current),
                openSessionIds = listOf("current"),
                hasMoreSessions = false,
                confirmedServerIds = setOf("current"),
                sweepNow = 0L,
            ),
        )

        assertTrue(
            "BulkSessionsRefreshed discards stale completeRootIds",
            out.sessionList.completeRootIds.isEmpty(),
        )
        assertEquals(
            "BulkSessionsRefreshed bumps completeness epoch",
            8L,
            out.sessionList.completenessEpoch,
        )
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
                // §Wave5b-Q13: the unified scroll slot + backstack replace
                // the pre-Wave5b pendingJumpToLatest field. Both must be
                // wiped by applyArchivedChatClear (FIX-B lineage).
                pendingScrollRequest = PendingScrollRequest(
                    requestId = 1L,
                    targetSessionId = "cur",
                    behavior = ScrollBehavior.Latest,
                ),
                parentReturnCheckpoints = mapOf("cur" to ScrollCheckpoint(null, 0, 0)),
            ),
            sessionList = SessionListState(openSessionIds = listOf("cur")),
        )

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(archivedCurrent),
                openSessionIds = emptyList(),
                hasMoreSessions = false,
                confirmedServerIds = setOf("cur"),
                sweepNow = 0L,
            ),
        )

        // Chat cleared atomically.
        assertNull("chat.currentSessionId cleared", out.chat.currentSessionId)
        assertTrue("messages cleared", out.chat.messages.isEmpty())
        assertTrue("partsByMessage cleared", out.chat.partsByMessage.isEmpty())
        assertNull("FIX-B / §Wave5b-Q13: pendingScrollRequest cleared", out.chat.pendingScrollRequest)
        assertTrue("FIX-B / §Wave5b-Q13: parentReturnCheckpoints cleared", out.chat.parentReturnCheckpoints.isEmpty())
        // List written in the SAME state.
        assertTrue("sessionList has the archived session", out.sessionList.sessions.any { it.id == "cur" && it.isArchived })
        assertTrue("openSessionIds pruned", out.sessionList.openSessionIds.isEmpty())
    }

    @Test
    fun `FIX-C reduce BulkSessionsRefreshed does NOT clear chat when current is not archived`() {
        val current = Session(id = "cur", directory = "/x")
        val archivedOther = Session(id = "other", directory = "/x", time = Session.TimeInfo(archived = 1L))
        val prior = StoreState.initial().copy(
            chat = ChatState(
                currentSessionId = "cur",
                messages = listOf(Message(id = "m1", role = "user")),
            ),
            sessionList = SessionListState(openSessionIds = listOf("cur", "other")),
        )

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(current, archivedOther),
                openSessionIds = listOf("cur"),  // other pruned
                hasMoreSessions = false,
                confirmedServerIds = setOf("cur", "other"),
                sweepNow = 0L,
            ),
        )

        // Chat NOT cleared (current is not archived).
        assertEquals("cur", out.chat.currentSessionId)
        assertEquals(1, out.chat.messages.size)
        // But openIds IS pruned (FIX-A — non-current archived tab removed).
        assertEquals(listOf("cur"), out.sessionList.openSessionIds)
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
            it.copy(sessions = listOf(Session(id = "cur", directory = "/p")), openSessionIds = listOf("cur"))
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
                openSessionIds = emptyList(),
                hasMoreSessions = false,
                confirmedServerIds = setOf("cur"),
                sweepNow = 0L,
            ),
        )
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
                openSessionIds = listOf("live-cur", "archived-root"),
                pendingQuestions = listOf(
                    QuestionRequest(id = "q-root", sessionId = "archived-root", questions = emptyList()),
                    QuestionRequest(id = "q-child", sessionId = "archived-child", questions = emptyList()),
                    QuestionRequest(id = "q-gc", sessionId = "archived-gc", questions = emptyList()),
                    // A question bound to the LIVE current session MUST survive.
                    QuestionRequest(id = "q-live", sessionId = "live-cur", questions = emptyList()),
                ),
            ),
            unread = UnreadState(
                unreadSessions = setOf("archived-root", "archived-child", "archived-gc", "live-cur"),
                lastViewedTime = mapOf(
                    "archived-root" to 1L, "archived-child" to 2L, "archived-gc" to 3L, "live-cur" to 4L,
                ),
            ),
        )

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(liveCurrent, archivedRoot, archivedChild, archivedGrandchild),
                openSessionIds = listOf("live-cur"),  // archived-root pruned
                hasMoreSessions = false,
                confirmedServerIds = setOf("live-cur", "archived-root", "archived-child", "archived-gc"),
                sweepNow = 0L,
            ),
        )

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
            out.sessionList.pendingQuestions.none { it.sessionId in setOf("archived-root", "archived-child", "archived-gc") },
        )
        assertTrue(
            "live current question preserved",
            out.sessionList.pendingQuestions.any { it.sessionId == "live-cur" },
        )
        // Chat is NOT cleared for the non-current archived session.
        assertEquals("chat NOT cleared (current is live)", "live-cur", out.chat.currentSessionId)
        // openIds pruned of the archived id.
        assertEquals(listOf("live-cur"), out.sessionList.openSessionIds)
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
                // slot + a checkpoint entry so the FIX-B clear still asserts.
                pendingScrollRequest = PendingScrollRequest(
                    requestId = 1L,
                    targetSessionId = "cur",
                    behavior = ScrollBehavior.Latest,
                ),
                parentReturnCheckpoints = mapOf("cur" to ScrollCheckpoint(null, 0, 0)),
            ),
            sessionList = SessionListState(
                sessions = listOf(archivedCurrent, archivedChild),
                openSessionIds = listOf("cur"),
                pendingQuestions = listOf(
                    QuestionRequest(id = "q-cur", sessionId = "cur", questions = emptyList()),
                    QuestionRequest(id = "q-child", sessionId = "child", questions = emptyList()),
                ),
            ),
            unread = UnreadState(
                unreadSessions = setOf("cur", "child"),
                lastViewedTime = mapOf("cur" to 1L, "child" to 2L),
            ),
        )

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(archivedCurrent, archivedChild),
                openSessionIds = emptyList(),
                hasMoreSessions = false,
                confirmedServerIds = setOf("cur", "child"),
                sweepNow = 0L,
            ),
        )

        // Chat cleared (current IS archived).
        assertNull("chat cleared for archived current", out.chat.currentSessionId)
        assertTrue("messages cleared", out.chat.messages.isEmpty())
        assertNull("pendingScrollRequest cleared (FIX-B / §Wave5b-Q13)", out.chat.pendingScrollRequest)
        assertTrue("parentReturnCheckpoints cleared (FIX-B / §Wave5b-Q13)", out.chat.parentReturnCheckpoints.isEmpty())
        // Full subtree cleaned.
        assertFalse("cur removed from unread", "cur" in out.unread.unreadSessions)
        assertFalse("child removed from unread", "child" in out.unread.unreadSessions)
        assertTrue("all subtree questions removed", out.sessionList.pendingQuestions.isEmpty())
    }
}
