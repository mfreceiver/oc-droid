package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import cn.vectory.ocdroid.ui.controller.subtreeIds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1c complex freeze (round 2) — **RED until impl**.
 *
 * Round 1 (`T1cSessionListOwnershipTest`) froze the 3 SIMPLE single-purpose
 * actions (`SessionUpserted` / `SessionCreatedLocal` / `OpenSessionIdsChanged`)
 * + the `BulkSessionsRefreshed.hasCompletedInitialLoad` gap. This round freezes
 * the DEFERRED complex multi-field write sites — the ones whose single
 * `mutateSessionList { it.copy(...) }` block writes `sessions` AND several
 * other fields atomically. Without these, rev-gpt FAILS on
 * "sessions 唯一 writer 未达成" (the T1b lesson): these call sites still write
 * `sessions` via a raw `mutateSessionList`, so they MUST migrate to a
 * dispatched [AppAction] whose reducer owns the full `copy()` field set.
 *
 * **Scope**: each test locks ONE production write site. The OLD path replicates
 * the production `mutateSessionList` block verbatim (field-for-field); the NEW
 * path dispatches the expected action; the two stores MUST converge to a
 * byte-for-byte equal [StoreState]. Scope tests additionally assert that
 * non-target slices (chat / expandedParts) are untouched (the production code
 * touches some of those via SEPARATE `mutateChat` / `mutateUnread` calls
 * OUTSIDE the migrated `copy()` block — those stay separate concerns; this
 * action owns ONLY the `sessionList` copy).
 *
 * **Reuse decisions** (see Group N for the full write-up):
 *  - `SessionArchived` (SSE cross-client): field set = {session, openSessionIds}
 *    — does NOT cover the REST archive path's 6-field copy (directorySessions
 *    + childSessions + pendingQuestions + activeSessionIds). → NEW action
 *    `SessionArchivedLocal`.
 *  - `BulkSessionsRefreshed` (archive-sync bulk): reducer additionally
 *    INTERSECTS activeSessionIds + OVERWRITES openSessionIds + does archived-
 *    subtree chat/unread cleanup. The :299 non-archive path does NONE of those
 *    (byte-for-byte incompatible). → NEW action `SessionsRefreshedLocal`.
 *
 * **RED kind**: `compile-error` — every action type referenced below
 * (`SessionArchivedLocal` / `SessionDeletedLocal` / `SessionStatusPatched` /
 * `SessionsRefreshedLocal` / `SessionsPageAppended` / `SessionTreeHydrated`)
 * is currently UNRESOLVED. Round 1's 3 types are likewise still unresolved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class T1cSessionListComplexOwnershipTest {

    // ═══════════════════════════════════════════════════════════════════════
    // G. SessionArchivedLocal — REST archive/restore (SessionMutationActions:186-202)
    //
    // Production copy() block writes 6 fields atomically:
    //   sessions + directorySessions + childSessions + openSessionIds
    //   + pendingQuestions + activeSessionIds
    // (the cross-slice mutateUnread / mutateChat / ChatCleared happen in
    // SEPARATE calls outside this block — out of scope; this action owns the
    // sessionList copy only).
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Expected action shape (impl MUST add to [AppAction]):
     *
     * ```kotlin
     * // REST archive/restore of a single session id (one loop iteration of
     * // launchSetSessionArchived). Carries the updated [session] (reducer
     * // map-replaces it into sessions / directorySessions / childSessions by
     * // id) plus the caller-computed [openSessionIds] (filtered), the caller-
     * // computed [pendingQuestions] (subtree-filtered), and the caller-
     * // computed [activeSessionIdsToRemove] (subtree for archive, {id} for
     * // restore). The isArchive branch decision stays at the call site.
     * data class SessionArchivedLocal(
     *     val session: Session,
     *     val openSessionIds: List<String>,
     *     val pendingQuestions: List<QuestionRequest>,
     *     val activeSessionIdsToRemove: Set<String>,
     * ) : AppAction
     * // reduce:
     * //   val id = action.session.id
     * //   state.copy(sessionList = state.sessionList.copy(
     * //     sessions = state.sessionList.sessions.map { if (it.id == id) action.session else it },
     * //     directorySessions = state.sessionList.directorySessions.mapValues { (_, l) ->
     * //       l.map { if (it.id == id) action.session else it } },
     * //     childSessions = state.sessionList.childSessions.mapValues { (_, l) ->
     * //       l.map { if (it.id == id) action.session else it } },
     * //     openSessionIds = action.openSessionIds,
     * //     pendingQuestions = action.pendingQuestions,
     * //     activeSessionIds = state.sessionList.activeSessionIds - action.activeSessionIdsToRemove,
     * //   ))
     * ```
     */
    @Test
    fun `dispatch SessionArchivedLocal equals legacy archive mutateSessionList - 6-field copy`() {
        val toArchiveId = "s1"
        val original = Session(id = "s1", directory = "/a")
        val archived = Session(id = "s1", directory = "/a", time = Session.TimeInfo(archived = 1000L))
        val other = Session(id = "s2", directory = "/b")
        val childOfS1 = Session(id = "c1", directory = "/a", parentId = "s1")
        val priorList = SessionListState(
            sessions = listOf(original, other),
            directorySessions = mapOf("/a" to listOf(original), "/b" to listOf(other)),
            childSessions = mapOf("s1" to listOf(childOfS1)),
            openSessionIds = listOf("s1", "s2"),
            pendingQuestions = listOf(
                QuestionRequest(id = "q1", sessionId = "s1", questions = emptyList()),
                QuestionRequest(id = "q2", sessionId = "s2", questions = emptyList()),
                QuestionRequest(id = "q3", sessionId = "c1", questions = emptyList()),
            ),
            activeSessionIds = setOf("s1", "s2", "c1"),
        )
        val oldStore = SharedStateStore().apply { mutateState { it.copy(sessionList = priorList) } }
        val newStore = SharedStateStore().apply { mutateState { it.copy(sessionList = priorList) } }

        // ── OLD path: replicate SessionMutationActions:147-202 verbatim (single id, isArchive=true)
        val id = toArchiveId
        val isArchive = true
        val currentSessions = oldStore.stateFlow.value.sessionList.sessions
        val currentDirSessions = oldStore.stateFlow.value.sessionList.directorySessions
        val currentChildSessions = oldStore.stateFlow.value.sessionList.childSessions
        val currentOpenIds = oldStore.stateFlow.value.sessionList.openSessionIds
        val subtree = subtreeIds(id, currentSessions, currentDirSessions, currentChildSessions)
        val newSessions = currentSessions.map { s -> if (s.id == id) archived else s }
        val newDirSessions = currentDirSessions.mapValues { (_, list) ->
            list.map { s -> if (s.id == id) archived else s }
        }
        val newChildSessions = currentChildSessions.mapValues { (_, list) ->
            list.map { s -> if (s.id == id) archived else s }
        }
        val newOpenIds = if (isArchive) currentOpenIds.filter { it != id } else currentOpenIds
        val cleanedQuestions = oldStore.stateFlow.value.sessionList.pendingQuestions
            .filter { q -> q.sessionId !in subtree }
        val activeIdsToRemove = if (isArchive) subtree else setOf(id)
        oldStore.mutateSessionList {
            it.copy(
                sessions = newSessions,
                directorySessions = newDirSessions,
                childSessions = newChildSessions,
                openSessionIds = newOpenIds,
                pendingQuestions = cleanedQuestions,
                activeSessionIds = it.activeSessionIds - activeIdsToRemove,
            )
        }

        // ── NEW path: the caller computes the SAME payload values; the reducer
        // owns the 6-field copy + derives the three map-replaces from session.id.
        newStore.dispatch(
            AppAction.SessionArchivedLocal(
                session = archived,
                openSessionIds = newOpenIds,
                pendingQuestions = cleanedQuestions,
                activeSessionIdsToRemove = activeIdsToRemove,
            )
        )

        assertEquals(
            "SessionArchivedLocal MUST equal legacy archive mutateSessionList " +
                "(sessions + dirSessions + childSessions + openIds + pendingQ + activeIds)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        // Sanity on the critical archived-flag propagation across all 3 stores.
        val out = newStore.stateFlow.value.sessionList
        assertEquals("sessions: archived copy replaced by id", archived, out.sessions.first { it.id == "s1" })
        assertEquals("directorySessions: archived copy replaced", archived, out.directorySessions["/a"]!!.first())
        // childSessions["s1"] holds CHILDREN of s1 (c1), not s1 itself — map-replace
        // by id leaves c1 untouched (c1.id != s1). Sanity must match that semantics.
        assertEquals(
            "childSessions: child c1 preserved (map-replace only touches matching id)",
            childOfS1,
            out.childSessions["s1"]!!.first(),
        )
        assertEquals("openSessionIds: archived id evicted", listOf("s2"), out.openSessionIds)
        assertEquals("pendingQuestions: subtree pruned (s1+c1 gone, s2 kept)", 1, out.pendingQuestions.size)
        assertEquals("s2 question survived", "q2", out.pendingQuestions.first().id)
        assertEquals("activeSessionIds: subtree removed", setOf("s2"), out.activeSessionIds)
    }

    @Test
    fun `SessionArchivedLocal restore path keeps openSessionIds and pendingQuestions, removes only id from activeSessionIds`() {
        // Restore (archived=false): newOpenIds = current (no filter), cleanedQuestions
        // = current (no filter), activeIdsToRemove = setOf(id) only (NOT subtree).
        val original = Session(id = "s1", directory = "/a", time = Session.TimeInfo(archived = 1000L))
        val restored = Session(id = "s1", directory = "/a") // archived flag cleared
        val child = Session(id = "c1", directory = "/a", parentId = "s1")
        val priorList = SessionListState(
            sessions = listOf(original),
            childSessions = mapOf("s1" to listOf(child)),
            openSessionIds = listOf("s2"), // s1 NOT in openIds (was archived)
            pendingQuestions = listOf(QuestionRequest(id = "q1", sessionId = "c1", questions = emptyList())),
            activeSessionIds = setOf("s1", "c1", "s2"),
        )
        val oldStore = SharedStateStore().apply { mutateState { it.copy(sessionList = priorList) } }
        val newStore = SharedStateStore().apply { mutateState { it.copy(sessionList = priorList) } }

        // OLD path: isArchive=false branch.
        val id = "s1"
        val isArchive = false
        val currentSessions = oldStore.stateFlow.value.sessionList.sessions
        val currentDirSessions = oldStore.stateFlow.value.sessionList.directorySessions
        val currentChildSessions = oldStore.stateFlow.value.sessionList.childSessions
        val currentOpenIds = oldStore.stateFlow.value.sessionList.openSessionIds
        val subtree = subtreeIds(id, currentSessions, currentDirSessions, currentChildSessions)
        val newSessions = currentSessions.map { s -> if (s.id == id) restored else s }
        val newDirSessions = currentDirSessions.mapValues { (_, l) -> l.map { s -> if (s.id == id) restored else s } }
        val newChildSessions = currentChildSessions.mapValues { (_, l) -> l.map { s -> if (s.id == id) restored else s } }
        val newOpenIds = if (isArchive) currentOpenIds.filter { it != id } else currentOpenIds
        val cleanedQuestions = oldStore.stateFlow.value.sessionList.pendingQuestions // unchanged (restore)
        val activeIdsToRemove = if (isArchive) subtree else setOf(id)
        oldStore.mutateSessionList {
            it.copy(
                sessions = newSessions,
                directorySessions = newDirSessions,
                childSessions = newChildSessions,
                openSessionIds = newOpenIds,
                pendingQuestions = cleanedQuestions,
                activeSessionIds = it.activeSessionIds - activeIdsToRemove,
            )
        }

        newStore.dispatch(
            AppAction.SessionArchivedLocal(
                session = restored,
                openSessionIds = newOpenIds,
                pendingQuestions = cleanedQuestions,
                activeSessionIdsToRemove = activeIdsToRemove,
            )
        )

        assertEquals(
            "SessionArchivedLocal restore MUST equal legacy restore branch",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        val out = newStore.stateFlow.value.sessionList
        assertEquals("openSessionIds untouched on restore", listOf("s2"), out.openSessionIds)
        assertEquals("pendingQuestions untouched on restore", 1, out.pendingQuestions.size)
        assertEquals("activeSessionIds: only id removed (subtree c1 KEPT on restore)", setOf("c1", "s2"), out.activeSessionIds)
    }

    @Test
    fun `SessionArchivedLocal is scoped to sessionList - chat and expandedParts untouched`() {
        // The production archive path touches chat/unread via SEPARATE calls
        // (mutateUnread / mutateChat / ChatCleared) OUTSIDE the copy block.
        // SessionArchivedLocal owns ONLY the sessionList copy — chat and
        // expandedParts MUST be untouched by this dispatch.
        val archived = Session(id = "s1", directory = "/a", time = Session.TimeInfo(archived = 1000L))
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(Session(id = "s1", directory = "/a")),
                openSessionIds = listOf("s1"),
                activeSessionIds = setOf("s1"),
            ),
            chat = ChatState(currentSessionId = "s-other"),
            expandedParts = mapOf("k1" to true),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(
            AppAction.SessionArchivedLocal(
                session = archived,
                openSessionIds = emptyList(),
                pendingQuestions = emptyList(),
                activeSessionIdsToRemove = setOf("s1"),
            )
        )

        val out = store.stateFlow.value
        assertEquals("sessionList.activeSessionIds cleared for s1", emptySet<String>(), out.sessionList.activeSessionIds)
        assertEquals("chat.currentSessionId untouched by SessionArchivedLocal", "s-other", out.chat.currentSessionId)
        assertEquals("expandedParts untouched by SessionArchivedLocal", mapOf("k1" to true), out.expandedParts)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // H. SessionDeletedLocal — REST delete subtree (SessionMutationActions:312-331)
    //
    // Production copy() writes 5 fields:
    //   sessions + directorySessions + pendingQuestions + activeSessionIds
    //   + sessionErrorsById
    // All 5 derive from a single removedIds set → the action carries only that.
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Expected action shape:
     *
     * ```kotlin
     * // REST delete success — purge the deleted subtree (caller computes
     * // removedIds via subtreeIds before the REST call). The reducer derives
     * // all 5 filter fields from [removedIds] (sessions / directorySessions /
     * // pendingQuestions / activeSessionIds / sessionErrorsById).
     * data class SessionDeletedLocal(
     *     val removedIds: Set<String>,
     * ) : AppAction
     * // reduce:
     * //   val ids = action.removedIds
     * //   state.copy(sessionList = state.sessionList.copy(
     * //     sessions = state.sessionList.sessions.filter { it.id !in ids },
     * //     directorySessions = state.sessionList.directorySessions
     * //       .mapValues { (_, l) -> l.filter { it.id !in ids } }
     * //       .filterValues { it.isNotEmpty() },
     * //     pendingQuestions = state.sessionList.pendingQuestions.filter { it.sessionId !in ids },
     * //     activeSessionIds = state.sessionList.activeSessionIds - ids,
     * //     sessionErrorsById = state.sessionList.sessionErrorsById.filterKeys { it !in ids },
     * //   ))
     * ```
     */
    @Test
    fun `dispatch SessionDeletedLocal equals legacy delete mutateSessionList - 5-field copy`() {
        val removedIds = setOf("s1", "c1")
        val survivor = Session(id = "s2", directory = "/b")
        val priorList = SessionListState(
            sessions = listOf(Session(id = "s1", directory = "/a"), survivor, Session(id = "c1", directory = "/a", parentId = "s1")),
            directorySessions = mapOf(
                "/a" to listOf(Session(id = "s1", directory = "/a")),
                "/b" to listOf(survivor),
                "/empty-after" to listOf(Session(id = "c1", directory = "/a")),
            ),
            pendingQuestions = listOf(
                QuestionRequest(id = "q1", sessionId = "s1", questions = emptyList()),
                QuestionRequest(id = "q2", sessionId = "s2", questions = emptyList()),
            ),
            activeSessionIds = setOf("s1", "c1", "s2"),
            sessionErrorsById = mapOf(
                "s1" to SlimSessionLastError(name = "err1"),
                "s2" to SlimSessionLastError(name = "err2"),
            ),
        )
        val oldStore = SharedStateStore().apply { mutateState { it.copy(sessionList = priorList) } }
        val newStore = SharedStateStore().apply { mutateState { it.copy(sessionList = priorList) } }

        // OLD path: replicate SessionMutationActions:306-331 verbatim.
        val currentSessions = oldStore.stateFlow.value.sessionList.sessions
        val currentDirSessions = oldStore.stateFlow.value.sessionList.directorySessions
        val newSessions = currentSessions.filter { s -> s.id !in removedIds }
        val newDirSessions = currentDirSessions
            .mapValues { (_, list) -> list.filter { s -> s.id !in removedIds } }
            .filterValues { it.isNotEmpty() }
        oldStore.mutateSessionList { sl ->
            sl.copy(
                sessions = newSessions,
                directorySessions = newDirSessions,
                pendingQuestions = sl.pendingQuestions.filter { it.sessionId !in removedIds },
                activeSessionIds = sl.activeSessionIds - removedIds,
                sessionErrorsById = sl.sessionErrorsById.filterKeys { it !in removedIds },
            )
        }

        // NEW path: reducer derives all 5 fields from removedIds.
        newStore.dispatch(AppAction.SessionDeletedLocal(removedIds))

        assertEquals(
            "SessionDeletedLocal MUST equal legacy delete mutateSessionList " +
                "(sessions + dirSessions + pendingQ + activeIds + sessionErrorsById)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        val out = newStore.stateFlow.value.sessionList
        assertEquals("sessions: removed ids gone", listOf(survivor), out.sessions)
        // /a held only s1 (removed) → filterValues drops empty entry; /b holds survivor.
        assertEquals(
            "directorySessions: empty-entry dir pruned (/a gone, /b kept)",
            setOf("/b"),
            out.directorySessions.keys,
        )
        assertTrue("directorySessions: /a entry is now empty after filter → pruned by filterValues", out.directorySessions["/a"] == null)
        assertEquals("pendingQuestions: removed-subtree questions gone", 1, out.pendingQuestions.size)
        assertEquals("sessionErrorsById: removed id gone", mapOf("s2" to SlimSessionLastError(name = "err2")), out.sessionErrorsById)
        assertEquals("activeSessionIds: removed ids gone", setOf("s2"), out.activeSessionIds)
    }

    @Test
    fun `SessionDeletedLocal is scoped to sessionList - chat and unread untouched`() {
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(Session(id = "s1", directory = "/a")),
                activeSessionIds = setOf("s1"),
            ),
            chat = ChatState(currentSessionId = "s-other"),
            unread = UnreadState(unreadSessions = setOf("s-other")),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(AppAction.SessionDeletedLocal(setOf("s1")))

        val out = store.stateFlow.value
        assertTrue("sessions purged", out.sessionList.sessions.isEmpty())
        assertEquals("chat untouched by SessionDeletedLocal", "s-other", out.chat.currentSessionId)
        assertEquals("unread untouched by SessionDeletedLocal", setOf("s-other"), out.unread.unreadSessions)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // I. SessionStatusPatched — optimistic busy on send (SessionMutationActions:452)
    //
    // Production copy() writes 2 fields: sessions (bumpSessionUpdated) +
    // sessionStatuses (sessionId → busy). Both derive from (sessionId,
    // updatedTimestamp, status).
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Expected action shape:
     *
     * ```kotlin
     * // launchSendMessage onSuccess optimistic busy write. Carries the
     * // caller-captured [updatedTimestamp] (System.currentTimeMillis() at
     * // call site) and the [status] to set (busy). The reducer delegates
     * // sessions to the existing pure fn bumpSessionUpdated.
     * data class SessionStatusPatched(
     *     val sessionId: String,
     *     val updatedTimestamp: Long,
     *     val status: SessionStatus,
     * ) : AppAction
     * // reduce:
     * //   state.copy(sessionList = state.sessionList.copy(
     * //     sessions = bumpSessionUpdated(state.sessionList.sessions, action.sessionId, action.updatedTimestamp),
     * //     sessionStatuses = state.sessionList.sessionStatuses + (action.sessionId to action.status),
     * //   ))
     * ```
     */
    @Test
    fun `dispatch SessionStatusPatched equals legacy optimistic-busy mutateSessionList`() {
        val sid = "s1"
        val ts = 12345L
        val status = SessionStatus(type = "busy")
        val priorList = SessionListState(
            sessions = listOf(Session(id = "s1", directory = "/a"), Session(id = "s2", directory = "/b")),
            sessionStatuses = mapOf("s2" to SessionStatus(type = "idle")),
        )
        val oldStore = SharedStateStore().apply { mutateState { it.copy(sessionList = priorList) } }
        val newStore = SharedStateStore().apply { mutateState { it.copy(sessionList = priorList) } }

        // OLD path: replicate SessionMutationActions:441-452 verbatim (fixed ts for determinism).
        val currentSessions = oldStore.stateFlow.value.sessionList.sessions
        val currentStatuses = oldStore.stateFlow.value.sessionList.sessionStatuses
        val newSessions = bumpSessionUpdated(currentSessions, sid, ts)
        val newStatuses = currentStatuses + (sid to status)
        oldStore.mutateSessionList { sl -> sl.copy(sessions = newSessions, sessionStatuses = newStatuses) }

        // NEW path.
        newStore.dispatch(AppAction.SessionStatusPatched(sid, ts, status))

        assertEquals(
            "SessionStatusPatched MUST equal legacy optimistic-busy mutateSessionList (sessions + sessionStatuses)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        val out = newStore.stateFlow.value.sessionList
        assertEquals("sessionStatuses: busy set for sid", status, out.sessionStatuses[sid])
        assertEquals("sessionStatuses: pre-existing idle survived", SessionStatus(type = "idle"), out.sessionStatuses["s2"])
        assertEquals("sessions: s1 bumped (not prepend-replaced, same position)", 2, out.sessions.size)
        assertEquals("sessions: s1 still at its original index (map-replace, not upsert)", "s1", out.sessions.first().id)
    }

    @Test
    fun `SessionStatusPatched is scoped to sessions and sessionStatuses - openSessionIds and activeSessionIds untouched`() {
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(Session(id = "s1", directory = "/a")),
                openSessionIds = listOf("s1"),
                activeSessionIds = setOf("s1"),
                pendingCreateIds = setOf("s1"),
            ),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(AppAction.SessionStatusPatched("s1", 99L, SessionStatus(type = "busy")))

        val out = store.stateFlow.value.sessionList
        assertEquals("sessionStatuses patched", SessionStatus(type = "busy"), out.sessionStatuses["s1"])
        assertEquals("openSessionIds untouched", listOf("s1"), out.openSessionIds)
        assertEquals("activeSessionIds untouched", setOf("s1"), out.activeSessionIds)
        assertEquals("pendingCreateIds untouched", setOf("s1"), out.pendingCreateIds)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // J. SessionsRefreshedLocal — full refresh NON-archive path (SessionListActions:299-321)
    //
    // Production copy() writes 9 fields:
    //   sessions + hasMoreSessions + isLoadingMoreSessions=false
    //   + isRefreshingSessions=false + pendingCreateIds + pendingCreatedAt
    //   + completeRootIds=emptySet() + completenessEpoch++ + hasCompletedInitialLoad=true
    //
    // Reuse of BulkSessionsRefreshed REJECTED (see Group N): that reducer also
    // overwrites openSessionIds + intersects activeSessionIds + does archived-
    // subtree chat/unread cleanup — none of which the :299 non-archive path does.
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Expected action shape:
     *
     * ```kotlin
     * // launchLoadSessions NON-archive success path. Carries the caller-
     * // computed merged sessions + swept pendingCreate maps + hasMore flag.
     * // The reducer owns the constants (isLoadingMoreSessions=false,
     * // isRefreshingSessions=false, completeRootIds=emptySet(),
     * // completenessEpoch++, hasCompletedInitialLoad=true).
     * data class SessionsRefreshedLocal(
     *     val sessions: List<Session>,
     *     val hasMoreSessions: Boolean,
     *     val pendingCreateIds: Set<String>,
     *     val pendingCreatedAt: Map<String, Long>,
     * ) : AppAction
     * // reduce:
     * //   state.copy(sessionList = state.sessionList.copy(
     * //     sessions = action.sessions,
     * //     hasMoreSessions = action.hasMoreSessions,
     * //     isLoadingMoreSessions = false,
     * //     isRefreshingSessions = false,
     * //     pendingCreateIds = action.pendingCreateIds,
     * //     pendingCreatedAt = action.pendingCreatedAt,
     * //     completeRootIds = emptySet(),
     * //     completenessEpoch = state.sessionList.completenessEpoch + 1L,
     * //     hasCompletedInitialLoad = true,
     * //   ))
     * ```
     */
    @Test
    fun `dispatch SessionsRefreshedLocal equals legacy full-refresh mutateSessionList - 9-field copy`() {
        val merged = listOf(Session(id = "s1", directory = "/a"), Session(id = "s2", directory = "/b"))
        val sweptIds = setOf("s-pending")
        val sweptCreatedAt = mapOf("s-pending" to 100L)
        val priorList = SessionListState(
            sessions = listOf(Session(id = "old", directory = "/old")),
            hasMoreSessions = true,
            isLoadingMoreSessions = true,
            isRefreshingSessions = true,
            pendingCreateIds = setOf("s-pending", "s-timed-out"),
            pendingCreatedAt = mapOf("s-pending" to 100L, "s-timed-out" to 1L),
            completeRootIds = setOf("old-root"),
            completenessEpoch = 7L,
            hasCompletedInitialLoad = false,
            // openSessionIds + activeSessionIds MUST survive untouched.
            openSessionIds = listOf("tab1"),
            activeSessionIds = setOf("active1"),
        )
        val oldStore = SharedStateStore().apply { mutateState { it.copy(sessionList = priorList) } }
        val newStore = SharedStateStore().apply { mutateState { it.copy(sessionList = priorList) } }

        // OLD path: replicate SessionListActions:299-321 verbatim.
        val newHasMore = false
        oldStore.mutateSessionList {
            it.copy(
                sessions = merged,
                hasMoreSessions = newHasMore,
                isLoadingMoreSessions = false,
                isRefreshingSessions = false,
                pendingCreateIds = sweptIds,
                pendingCreatedAt = sweptCreatedAt,
                completeRootIds = emptySet(),
                completenessEpoch = it.completenessEpoch + 1L,
                hasCompletedInitialLoad = true,
            )
        }

        // NEW path.
        newStore.dispatch(
            AppAction.SessionsRefreshedLocal(
                sessions = merged,
                hasMoreSessions = newHasMore,
                pendingCreateIds = sweptIds,
                pendingCreatedAt = sweptCreatedAt,
            )
        )

        assertEquals(
            "SessionsRefreshedLocal MUST equal legacy full-refresh mutateSessionList (9 fields)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        val out = newStore.stateFlow.value.sessionList
        assertEquals("sessions replaced", merged, out.sessions)
        assertFalse("hasMoreSessions updated", out.hasMoreSessions)
        assertFalse("isLoadingMoreSessions cleared", out.isLoadingMoreSessions)
        assertFalse("isRefreshingSessions cleared", out.isRefreshingSessions)
        assertEquals("pendingCreateIds swept", sweptIds, out.pendingCreateIds)
        assertEquals("pendingCreatedAt swept", sweptCreatedAt, out.pendingCreatedAt)
        assertEquals("completeRootIds reset", emptySet<String>(), out.completeRootIds)
        assertEquals("completenessEpoch bumped", 8L, out.completenessEpoch)
        assertTrue("hasCompletedInitialLoad set", out.hasCompletedInitialLoad)
        // CRITICAL: fields NOT in the :299 copy block MUST survive (BulkSessionsRefreshed
        // WOULD overwrite these — that's why reuse was rejected).
        assertEquals("openSessionIds untouched (BulkSessionsRefreshed would overwrite)", listOf("tab1"), out.openSessionIds)
        assertEquals("activeSessionIds untouched (BulkSessionsRefreshed would intersect)", setOf("active1"), out.activeSessionIds)
    }

    @Test
    fun `SessionsRefreshedLocal is scoped to sessionList - chat untouched`() {
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(sessions = listOf(Session(id = "old", directory = "/o"))),
            chat = ChatState(currentSessionId = "s-current"),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(
            AppAction.SessionsRefreshedLocal(
                sessions = listOf(Session(id = "s1", directory = "/a")),
                hasMoreSessions = false,
                pendingCreateIds = emptySet(),
                pendingCreatedAt = emptyMap(),
            )
        )

        assertEquals("chat.currentSessionId untouched by SessionsRefreshedLocal", "s-current", store.stateFlow.value.chat.currentSessionId)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // K. SessionsPageAppended — loadMore refresh (SessionListActions:548-566)
    //
    // Production copy() writes 8 fields:
    //   sessions + loadedSessionLimit + hasMoreSessions
    //   + isLoadingMoreSessions=false + pendingCreateIds + pendingCreatedAt
    //   + completeRootIds=emptySet() + completenessEpoch++
    // (distinct from :299 — no isRefreshingSessions, no hasCompletedInitialLoad;
    //  adds loadedSessionLimit).
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Expected action shape:
     *
     * ```kotlin
     * // launchLoadMoreSessions success path. Carries the merged sessions +
     * // new loadedSessionLimit + hasMore + swept pendingCreate maps. The
     * // reducer owns the constants (isLoadingMoreSessions=false,
     * // completeRootIds=emptySet(), completenessEpoch++). Does NOT touch
     * // isRefreshingSessions or hasCompletedInitialLoad (distinct from
     * // SessionsRefreshedLocal).
     * data class SessionsPageAppended(
     *     val sessions: List<Session>,
     *     val loadedSessionLimit: Int,
     *     val hasMoreSessions: Boolean,
     *     val pendingCreateIds: Set<String>,
     *     val pendingCreatedAt: Map<String, Long>,
     * ) : AppAction
     * // reduce:
     * //   state.copy(sessionList = state.sessionList.copy(
     * //     sessions = action.sessions,
     * //     loadedSessionLimit = action.loadedSessionLimit,
     * //     hasMoreSessions = action.hasMoreSessions,
     * //     isLoadingMoreSessions = false,
     * //     pendingCreateIds = action.pendingCreateIds,
     * //     pendingCreatedAt = action.pendingCreatedAt,
     * //     completeRootIds = emptySet(),
     * //     completenessEpoch = state.sessionList.completenessEpoch + 1L,
     * //   ))
     * ```
     */
    @Test
    fun `dispatch SessionsPageAppended equals legacy loadMore mutateSessionList - 8-field copy`() {
        val merged = listOf(Session(id = "s1", directory = "/a"), Session(id = "s2", directory = "/b"))
        val nextLimit = 50
        val newHasMore = true
        val sweptIds = setOf("s-pending")
        val sweptCreatedAt = mapOf("s-pending" to 100L)
        val priorList = SessionListState(
            sessions = listOf(Session(id = "old", directory = "/old")),
            loadedSessionLimit = 25,
            hasMoreSessions = true,
            isLoadingMoreSessions = true,
            isRefreshingSessions = true, // MUST survive (loadMore does NOT clear this)
            pendingCreateIds = setOf("s-pending", "s-gone"),
            pendingCreatedAt = mapOf("s-pending" to 100L, "s-gone" to 1L),
            completeRootIds = setOf("old-root"),
            completenessEpoch = 3L,
            hasCompletedInitialLoad = true, // MUST survive (loadMore does NOT touch this)
        )
        val oldStore = SharedStateStore().apply { mutateState { it.copy(sessionList = priorList) } }
        val newStore = SharedStateStore().apply { mutateState { it.copy(sessionList = priorList) } }

        // OLD path: replicate SessionListActions:548-566 verbatim.
        oldStore.mutateSessionList {
            it.copy(
                sessions = merged,
                loadedSessionLimit = nextLimit,
                hasMoreSessions = newHasMore,
                isLoadingMoreSessions = false,
                pendingCreateIds = sweptIds,
                pendingCreatedAt = sweptCreatedAt,
                completeRootIds = emptySet(),
                completenessEpoch = it.completenessEpoch + 1L,
            )
        }

        // NEW path.
        newStore.dispatch(
            AppAction.SessionsPageAppended(
                sessions = merged,
                loadedSessionLimit = nextLimit,
                hasMoreSessions = newHasMore,
                pendingCreateIds = sweptIds,
                pendingCreatedAt = sweptCreatedAt,
            )
        )

        assertEquals(
            "SessionsPageAppended MUST equal legacy loadMore mutateSessionList (8 fields)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        val out = newStore.stateFlow.value.sessionList
        assertEquals("sessions replaced", merged, out.sessions)
        assertEquals("loadedSessionLimit updated", 50, out.loadedSessionLimit)
        assertTrue("hasMoreSessions updated", out.hasMoreSessions)
        assertFalse("isLoadingMoreSessions cleared", out.isLoadingMoreSessions)
        // CRITICAL distinction from SessionsRefreshedLocal:
        assertTrue("isRefreshingSessions MUST survive (loadMore does not clear it)", out.isRefreshingSessions)
        assertTrue("hasCompletedInitialLoad MUST survive (loadMore does not touch it)", out.hasCompletedInitialLoad)
        assertEquals("completeRootIds reset", emptySet<String>(), out.completeRootIds)
        assertEquals("completenessEpoch bumped", 4L, out.completenessEpoch)
    }

    @Test
    fun `SessionsPageAppended is scoped to sessionList - chat untouched`() {
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(sessions = listOf(Session(id = "old", directory = "/o"))),
            chat = ChatState(currentSessionId = "s-current"),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(
            AppAction.SessionsPageAppended(
                sessions = listOf(Session(id = "s1", directory = "/a")),
                loadedSessionLimit = 50,
                hasMoreSessions = false,
                pendingCreateIds = emptySet(),
                pendingCreatedAt = emptyMap(),
            )
        )

        assertEquals("chat.currentSessionId untouched by SessionsPageAppended", "s-current", store.stateFlow.value.chat.currentSessionId)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // L. SessionTreeHydrated — tree hydration commit (SessionTreeHydrator:107-135)
    //
    // Production copy() (guarded by completenessEpoch compare) writes 3 fields:
    //   childSessions (+= validParents) + completeRootIds (+= validRoots)
    //   + sessionStatuses (replace with merged nextStatuses)
    // The epoch guard + validRoots/nextStatuses COMPUTATION stays at the call
    // site; the action carries pre-computed deltas + epochAtStart for the
    // reducer's compare-and-bail.
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Expected action shape:
     *
     * ```kotlin
     * // SessionTreeHydrator.request commit. Carries the epoch captured BEFORE
     * // hydration started (call-site前置); the reducer compare-and-bails if the
     * // live epoch differs (fail-closed against a structural invalidation that
     * // straddled the in-flight REST call). The validRoots filtering (directory
     * // match + host-identity check) stays at the call site — the action
     * // carries only the VALIDATED deltas.
     * data class SessionTreeHydrated(
     *     val epochAtStart: Long,
     *     val childSessionsDelta: Map<String, List<Session>>,
     *     val completeRootIdsDelta: Set<String>,
     *     val sessionStatuses: Map<String, SessionStatus>,
     * ) : AppAction
     * // reduce:
     * //   if (state.sessionList.completenessEpoch != action.epochAtStart) state  // stale → no-op
     * //   else state.copy(sessionList = state.sessionList.copy(
     * //     childSessions = state.sessionList.childSessions + action.childSessionsDelta,
     * //     completeRootIds = state.sessionList.completeRootIds + action.completeRootIdsDelta,
     * //     sessionStatuses = action.sessionStatuses,
     * //   ))
     * ```
     */
    @Test
    fun `dispatch SessionTreeHydrated with matching epoch applies childSessions plus completeRootIds plus sessionStatuses`() {
        val epochAtStart = 5L
        val childDelta = mapOf("s1" to listOf(Session(id = "c1", directory = "/a", parentId = "s1")))
        val rootDelta = setOf("s1")
        val nextStatuses = mapOf("s1" to SessionStatus(type = "idle"), "c1" to SessionStatus(type = "busy"))
        val priorList = SessionListState(
            sessions = listOf(Session(id = "s1", directory = "/a")),
            childSessions = mapOf("s1" to listOf(Session(id = "c-old", directory = "/a", parentId = "s1"))),
            completeRootIds = setOf("s0"),
            completenessEpoch = epochAtStart, // MATCHES → apply
            sessionStatuses = mapOf("s0" to SessionStatus(type = "idle")),
        )
        val oldStore = SharedStateStore().apply { mutateState { it.copy(sessionList = priorList) } }
        val newStore = SharedStateStore().apply { mutateState { it.copy(sessionList = priorList) } }

        // OLD path: replicate SessionTreeHydrator:107-135 (epoch match → apply).
        oldStore.mutateSessionList { current ->
            if (current.completenessEpoch != epochAtStart) return@mutateSessionList current
            current.copy(
                childSessions = current.childSessions + childDelta,
                completeRootIds = current.completeRootIds + rootDelta,
                sessionStatuses = nextStatuses,
            )
        }

        // NEW path.
        newStore.dispatch(
            AppAction.SessionTreeHydrated(
                epochAtStart = epochAtStart,
                childSessionsDelta = childDelta,
                completeRootIdsDelta = rootDelta,
                sessionStatuses = nextStatuses,
            )
        )

        assertEquals(
            "SessionTreeHydrated (epoch match) MUST equal legacy hydrate commit",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        val out = newStore.stateFlow.value.sessionList
        // Map `+` replaces the value for key "s1" wholesale (legacy + reduce both
        // use map merge, not list concat). Delta for s1 is [c1] → c-old is gone.
        assertEquals(
            "childSessions: delta replaces key s1 (map +, not list concat)",
            listOf(Session(id = "c1", directory = "/a", parentId = "s1")),
            out.childSessions["s1"],
        )
        assertEquals("completeRootIds: delta added", setOf("s0", "s1"), out.completeRootIds)
        assertEquals("sessionStatuses: replaced with nextStatuses", nextStatuses, out.sessionStatuses)
    }

    @Test
    fun `dispatch SessionTreeHydrated with mismatched epoch is a no-op (fail-closed against stale hydration)`() {
        val epochAtStart = 5L
        val priorList = SessionListState(
            sessions = listOf(Session(id = "s1", directory = "/a")),
            childSessions = emptyMap(),
            completeRootIds = setOf("s0"),
            completenessEpoch = 99L, // MISMATCH → no-op
            sessionStatuses = mapOf("s0" to SessionStatus(type = "idle")),
        )
        val store = SharedStateStore().apply { mutateState { it.copy(sessionList = priorList) } }
        val before = store.stateFlow.value

        store.dispatch(
            AppAction.SessionTreeHydrated(
                epochAtStart = epochAtStart,
                childSessionsDelta = mapOf("s1" to listOf(Session(id = "c1", directory = "/a", parentId = "s1"))),
                completeRootIdsDelta = setOf("s1"),
                sessionStatuses = mapOf("s1" to SessionStatus(type = "idle")),
            )
        )

        assertEquals(
            "SessionTreeHydrated (epoch mismatch) MUST be a full no-op — state byte-for-byte unchanged",
            before,
            store.stateFlow.value,
        )
    }

    @Test
    fun `SessionTreeHydrated is scoped to childSessions completeRootIds sessionStatuses - sessions and openSessionIds untouched`() {
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(Session(id = "s1", directory = "/a")),
                openSessionIds = listOf("s1"),
                completenessEpoch = 5L,
            ),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(
            AppAction.SessionTreeHydrated(
                epochAtStart = 5L,
                childSessionsDelta = mapOf("s1" to listOf(Session(id = "c1", directory = "/a", parentId = "s1"))),
                completeRootIdsDelta = setOf("s1"),
                sessionStatuses = mapOf("s1" to SessionStatus(type = "idle")),
            )
        )

        val out = store.stateFlow.value.sessionList
        assertEquals("sessions untouched by SessionTreeHydrated", listOf(Session(id = "s1", directory = "/a")), out.sessions)
        assertEquals("openSessionIds untouched by SessionTreeHydrated", listOf("s1"), out.openSessionIds)
        assertEquals("completenessEpoch untouched (not bumped by hydration)", 5L, out.completenessEpoch)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // M. Single-emission atomicity (one dispatch = one aggregate emission)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispatch SessionArchivedLocal produces exactly one aggregate emission`() = runTest {
        val store = SharedStateStore().apply {
            mutateSessionList {
                it.copy(
                    sessions = listOf(Session(id = "s1", directory = "/a")),
                    openSessionIds = listOf("s1"),
                    activeSessionIds = setOf("s1"),
                )
            }
        }
        val seen = mutableListOf<StoreState>()
        val job = launch { store.stateFlow.collect { seen += it } }
        advanceUntilIdle()
        assertEquals(1, seen.size)

        store.dispatch(
            AppAction.SessionArchivedLocal(
                session = Session(id = "s1", directory = "/a", time = Session.TimeInfo(archived = 1000L)),
                openSessionIds = emptyList(),
                pendingQuestions = emptyList(),
                activeSessionIdsToRemove = setOf("s1"),
            )
        )
        advanceUntilIdle()

        assertEquals("exactly one post-dispatch emission", 2, seen.size)
        assertTrue("activeSessionIds cleared in the single emission", seen.last().sessionList.activeSessionIds.isEmpty())
        job.cancel()
    }

    @Test
    fun `dispatch SessionDeletedLocal produces exactly one aggregate emission`() = runTest {
        val store = SharedStateStore().apply {
            mutateSessionList {
                it.copy(
                    sessions = listOf(Session(id = "s1", directory = "/a")),
                    activeSessionIds = setOf("s1"),
                )
            }
        }
        val seen = mutableListOf<StoreState>()
        val job = launch { store.stateFlow.collect { seen += it } }
        advanceUntilIdle()
        assertEquals(1, seen.size)

        store.dispatch(AppAction.SessionDeletedLocal(setOf("s1")))
        advanceUntilIdle()

        assertEquals("exactly one post-dispatch emission", 2, seen.size)
        assertTrue("sessions purged in the single emission", seen.last().sessionList.sessions.isEmpty())
        job.cancel()
    }

    @Test
    fun `dispatch SessionsRefreshedLocal produces exactly one aggregate emission`() = runTest {
        val store = SharedStateStore().apply {
            mutateSessionList { it.copy(completenessEpoch = 1L, hasCompletedInitialLoad = false) }
        }
        val seen = mutableListOf<StoreState>()
        val job = launch { store.stateFlow.collect { seen += it } }
        advanceUntilIdle()
        assertEquals(1, seen.size)

        store.dispatch(
            AppAction.SessionsRefreshedLocal(
                sessions = listOf(Session(id = "s1", directory = "/a")),
                hasMoreSessions = false,
                pendingCreateIds = emptySet(),
                pendingCreatedAt = emptyMap(),
            )
        )
        advanceUntilIdle()

        assertEquals("exactly one post-dispatch emission", 2, seen.size)
        assertTrue("hasCompletedInitialLoad set in the single emission", seen.last().sessionList.hasCompletedInitialLoad)
        assertEquals("completenessEpoch bumped in the single emission", 2L, seen.last().sessionList.completenessEpoch)
        job.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // N. Documentation — reuse decisions + complex write-site inventory
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Reuse decisions for the complex deferred write sites.
     *
     * ┌──────────────────────┬──────────────────────────┬─────────────────────────────────────────┐
     * │ site                 │ action (NEW unless noted) │ reuse rationale                         │
     * ├──────────────────────┼──────────────────────────┼─────────────────────────────────────────┤
     * │ SMA:186-202 archive  │ SessionArchivedLocal     │ SessionArchived (SSE) field set =       │
     * │                      │ (NEW)                    │ {session, openSessionIds} — does NOT    │
     * │                      │                          │ cover directorySessions / childSessions │
     * │                      │                          │ / pendingQuestions / activeSessionIds.  │
     * │                      │                          │ REST archive writes 6 fields; SSE       │
     * │                      │                          │ archive writes 2. Distinct semantics.   │
     * ├──────────────────────┼──────────────────────────┼─────────────────────────────────────────┤
     * │ SMA:312-331 delete   │ SessionDeletedLocal      │ No existing action. removedIds derives  │
     * │                      │ (NEW)                    │ all 5 filter fields.                    │
     * ├──────────────────────┼──────────────────────────┼─────────────────────────────────────────┤
     * │ SMA:452 status       │ SessionStatusPatched     │ No existing action. 2-field optimistic  │
     * │                      │ (NEW)                    │ busy write.                             │
     * ├──────────────────────┼──────────────────────────┼─────────────────────────────────────────┤
     * │ SLA:299-321 refresh  │ SessionsRefreshedLocal   │ BulkSessionsRefreshed REJECTED: its     │
     * │                      │ (NEW)                    │ reducer INTERSECTS activeSessionIds +   │
     * │                      │                          │ OVERWRITES openSessionIds + does        │
     * │                      │                          │ archived-subtree chat/unread cleanup.   │
     * │                      │                          │ The :299 non-archive path does NONE of  │
     * │                      │                          │ those — byte-for-byte incompatible.     │
     * │                      │                          │ (Impl MAY unify later if it accepts the │
     * │                      │                          │ activeSessionIds intersect behavior     │
     * │                      │                          │ change; the freeze locks current exact │
     * │                      │                          │ field set.)                             │
     * ├──────────────────────┼──────────────────────────┼─────────────────────────────────────────┤
     * │ SLA:548-566 loadMore │ SessionsPageAppended     │ Distinct from :299 (loadedSessionLimit  │
     * │                      │ (NEW)                    │ instead of isRefreshingSessions /       │
     * │                      │                          │ hasCompletedInitialLoad).               │
     * ├──────────────────────┼──────────────────────────┼─────────────────────────────────────────┤
     * │ STH:107 hydrate      │ SessionTreeHydrated      │ No existing action. Epoch-guarded 3-    │
     * │                      │ (NEW)                    │ field commit.                           │
     * └──────────────────────┴──────────────────────────┴─────────────────────────────────────────┘
     *
     * Full T1c inventory (impl notes for fixer-grok):
     *
     * TARGET — must migrate raw mutateSessionList → dispatch:
     *   Round 1 (T1cSessionListOwnershipTest): SessionUpserted (7+ sites),
     *     SessionCreatedLocal (SMA:42-48), OpenSessionIdsChanged (SVM:247,
     *     SSC:561), BulkSessionsRefreshed +hasCompletedInitialLoad (SLA:296).
     *   Round 2 (THIS file): SessionArchivedLocal (SMA:186-202),
     *     SessionDeletedLocal (SMA:312-331), SessionStatusPatched (SMA:452),
     *     SessionsRefreshedLocal (SLA:299-321), SessionsPageAppended
     *     (SLA:548-566), SessionTreeHydrated (STH:107).
     *
     * DEFERRED — NOT in T1c (no sessions/openSessionIds write, or already
     * migrated). See T1cSessionListOwnershipTest Group F table for the full
     * non-target inventory (SLA:704/853/926/966/1011/1101/1188, MessageActions,
     * OrchestratorViewModel, SSC various, ConnectionActions, etc.).
     */
    @Test
    fun `DOCUMENTATION - complex write-site reuse decisions and T1c inventory`() {
        // Audit anchor:
        //   rg -n 'mutateSessionList' app/src/main | grep -v '.slim/'
        // Every site that writes `sessions` or `openSessionIds` via a raw
        // mutateSessionList MUST be in the TARGET table (round 1 or 2). After
        // impl, rev-gpt's "sessions 唯一 writer" check MUST pass: the only
        // `sessions` writes in app/src/main go through AppAction dispatch
        // (reduce), never a raw mutateSessionList copy.
        assertTrue("complex write-site reuse decisions documented — see comment block above", true)
    }
}
