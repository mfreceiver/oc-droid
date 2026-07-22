package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1c freeze — **RED until impl**. Step-4 `sessions` + `openSessionIds`
 * ownership migration (full-refactor-plan §2.3 ownership table row 4 +
 * §4-T1 acceptance "目标字段业务直接写入点静态归零").
 *
 * **Scope**: the §2.3 target fields `sessions` + `openSessionIds` (and any
 * fields written in the SAME `copy()` as these two, to avoid splitting a
 * single atomic mutateSessionList into two dispatches). Non-target fields
 * like `sessionTodos` / `sessionStatuses` / `pendingQuestions` /
 * `directorySessions` / `childSessions` are deferred UNLESS co-written with
 * sessions in the same copy block.
 *
 * **Already migrated (NOT touched here)**:
 *  - `SessionArchived` (cross-client archive SSE) ✓
 *  - `BulkSessionsRefreshed` (bulk refresh + archive-sync) ✓ — but has a
 *    `hasCompletedInitialLoad` gap (see Group D below)
 *  - `DraftSessionMaterialized` (create draft session) ✓
 *  - `HostStatePurged` (host switch purge) ✓
 *  - `WorkdirDraftStarted` (new-session-in-workdir draft) ✓
 *
 * **Expected action vocabulary** (T1c impl MUST add):
 *
 * ```kotlin
 * // ── Simple session upsert (sessions only) ──────────────────────────────
 * // Covers: fork, rename, child upsert, switchTo target upsert, revert,
 * // question-dir-resolve, SSE session.created/updated.
 * // Reducer delegates to the existing pure function upsertSession.
 * data class SessionUpserted(val session: Session) : AppAction
 * // reduce: state.copy(sessionList = state.sessionList.copy(
 * //     sessions = upsertSession(state.sessionList.sessions, action.session)
 * // ))
 *
 * // ── Session created with pending-create registration ───────────────────
 * // Covers: launchCreateSession (SessionMutationActions:42-48). Writes
 * // sessions + pendingCreateIds + pendingCreatedAt in ONE dispatch.
 * data class SessionCreatedLocal(
 *     val session: Session,
 *     val registeredAt: Long,
 * ) : AppAction
 * // reduce: state.copy(sessionList = state.sessionList.copy(
 * //     sessions = upsertSession(state.sessionList.sessions, action.session),
 * //     pendingCreateIds = state.sessionList.pendingCreateIds + action.session.id,
 * //     pendingCreatedAt = state.sessionList.pendingCreatedAt + (action.session.id to action.registeredAt),
 * // ))
 *
 * // ── OpenSessionIds changed (openSessionIds only) ───────────────────────
 * // Covers: closeSession (SessionViewModel:247), switchTo open-tab append
 * // (SessionSwitcher:561).
 * data class OpenSessionIdsChanged(val openSessionIds: List<String>) : AppAction
 * // reduce: state.copy(sessionList = state.sessionList.copy(
 * //     openSessionIds = action.openSessionIds
 * // ))
 * ```
 *
 * **BulkSessionsRefreshed hasCompletedInitialLoad gap** (Group D): the
 * existing reducer does NOT set `hasCompletedInitialLoad = true`, so the
 * call site at SessionListActions:296 does a SEPARATE mutateSessionList to
 * patch it after the dispatch. The fix: add `hasCompletedInitialLoad = true`
 * to the BulkSessionsRefreshed reducer. The test below locks the desired
 * behavior (dispatch alone sets the flag — no separate mutateSessionList).
 *
 * **RED kind**: `compile-error` for the 3 new action types (unresolved
 * references) + `assertion-failure` for the BulkSessionsRefreshed gap (the
 * action exists but the reducer doesn't set the flag).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class T1cSessionListOwnershipTest {

    // ═══════════════════════════════════════════════════════════════════════
    // A. SessionUpserted — simple upsert (sessions only, 7+ call sites)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispatch SessionUpserted is byte-for-byte equivalent to mutateSessionList upsertSession`() = runTest {
        val existing = listOf(Session(id = "s1", directory = "/a"), Session(id = "s2", directory = "/b"))
        val newSession = Session(id = "s1", directory = "/a-updated") // same id → replace
        val oldStore = SharedStateStore().apply {
            mutateSessionList { it.copy(sessions = existing, openSessionIds = listOf("s1", "s2")) }
        }
        val newStore = SharedStateStore().apply {
            mutateSessionList { it.copy(sessions = existing, openSessionIds = listOf("s1", "s2")) }
        }

        // Old path: upsertSession (prepends + dedupes by id).
        oldStore.mutateSessionList { sl -> sl.copy(sessions = upsertSession(sl.sessions, newSession)) }
        // New path.
        newStore.dispatch(AppAction.SessionUpserted(newSession))

        assertEquals(
            "SessionUpserted MUST equal legacy upsertSession (prepend + dedupe by id)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        // Sanity: the new session is at the head; the old entry with the same id is gone.
        assertEquals("new session prepended", "s1", newStore.stateFlow.value.sessionList.sessions.first().id)
        assertEquals("directory updated on the replaced entry", "/a-updated", newStore.stateFlow.value.sessionList.sessions.first().directory)
        assertEquals("list size unchanged (dedupe)", 2, newStore.stateFlow.value.sessionList.sessions.size)
    }

    @Test
    fun `dispatch SessionUpserted with a brand-new session appends it at the head`() = runTest {
        val existing = listOf(Session(id = "s1", directory = "/a"))
        val brandNew = Session(id = "s-new", directory = "/new")
        val oldStore = SharedStateStore().apply { mutateSessionList { it.copy(sessions = existing) } }
        val newStore = SharedStateStore().apply { mutateSessionList { it.copy(sessions = existing) } }

        oldStore.mutateSessionList { sl -> sl.copy(sessions = upsertSession(sl.sessions, brandNew)) }
        newStore.dispatch(AppAction.SessionUpserted(brandNew))

        assertEquals(
            "SessionUpserted with a new id prepends (list grows by 1)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        assertEquals(2, newStore.stateFlow.value.sessionList.sessions.size)
        assertEquals("s-new", newStore.stateFlow.value.sessionList.sessions.first().id)
    }

    @Test
    fun `SessionUpserted is scoped to sessions — openSessionIds and all other fields untouched`() {
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(Session(id = "s1", directory = "/a")),
                openSessionIds = listOf("s1"),
                hasMoreSessions = true,
                hasCompletedInitialLoad = true,
            ),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(AppAction.SessionUpserted(Session(id = "s2", directory = "/b")))

        val out = store.stateFlow.value.sessionList
        // sessions changed.
        assertEquals(2, out.sessions.size)
        // Non-target fields MUST survive.
        assertEquals("openSessionIds untouched by SessionUpserted", listOf("s1"), out.openSessionIds)
        assertTrue("hasMoreSessions untouched", out.hasMoreSessions)
        assertTrue("hasCompletedInitialLoad untouched", out.hasCompletedInitialLoad)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // B. SessionCreatedLocal — create + pending-create registration
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispatch SessionCreatedLocal writes sessions plus pendingCreateIds plus pendingCreatedAt in one dispatch`() = runTest {
        val existing = listOf(Session(id = "s1", directory = "/a"))
        val created = Session(id = "s-new", directory = "/new")
        val registeredAt = 12345L
        val oldStore = SharedStateStore().apply {
            mutateSessionList {
                it.copy(
                    sessions = existing,
                    pendingCreateIds = setOf("s1"),
                    pendingCreatedAt = mapOf("s1" to 100L),
                )
            }
        }
        val newStore = SharedStateStore().apply {
            mutateSessionList {
                it.copy(
                    sessions = existing,
                    pendingCreateIds = setOf("s1"),
                    pendingCreatedAt = mapOf("s1" to 100L),
                )
            }
        }

        // Old path: replicate SessionMutationActions:42-48 mutateSessionList verbatim.
        oldStore.mutateSessionList { sl ->
            sl.copy(
                sessions = upsertSession(sl.sessions, created),
                pendingCreateIds = sl.pendingCreateIds + created.id,
                pendingCreatedAt = sl.pendingCreatedAt + (created.id to registeredAt),
            )
        }
        // New path.
        newStore.dispatch(AppAction.SessionCreatedLocal(created, registeredAt))

        assertEquals(
            "SessionCreatedLocal MUST equal legacy create mutateSessionList " +
                "(sessions + pendingCreateIds + pendingCreatedAt in ONE dispatch)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        // Sanity: pendingCreateIds grew.
        val out = newStore.stateFlow.value.sessionList
        assertTrue("pendingCreateIds includes the new id", out.pendingCreateIds.contains("s-new"))
        assertEquals("pendingCreatedAt carries the registeredAt timestamp", registeredAt, out.pendingCreatedAt["s-new"])
    }

    @Test
    fun `SessionCreatedLocal is distinct from DraftSessionMaterialized — does NOT touch chat or unread`() {
        // DraftSessionMaterialized (the draft-create action) writes chat.currentSessionId +
        // unread.lastViewedTime. SessionCreatedLocal (the REST-create action) writes ONLY
        // sessionList fields — it does NOT flip chat or touch unread. The caller handles
        // those via separate calls (onSelectSession etc.).
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(sessions = listOf(Session(id = "s1", directory = "/a"))),
            chat = ChatState(currentSessionId = "s1"),
            unread = UnreadState(unreadSessions = setOf("s1")),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(AppAction.SessionCreatedLocal(Session(id = "s2", directory = "/b"), 999L))

        val out = store.stateFlow.value
        // sessionList changed (sessions + pendingCreateIds + pendingCreatedAt).
        assertEquals(2, out.sessionList.sessions.size)
        // chat + unread MUST be untouched (distinct from DraftSessionMaterialized).
        assertEquals("chat.currentSessionId untouched by SessionCreatedLocal", "s1", out.chat.currentSessionId)
        assertTrue("unread untouched by SessionCreatedLocal", out.unread.unreadSessions.contains("s1"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // C. OpenSessionIdsChanged — openSessionIds-only writes
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispatch OpenSessionIdsChanged is byte-for-byte equivalent to mutateSessionList openSessionIds write`() = runTest {
        val oldIds = listOf("s1", "s2", "s3")
        val newIds = listOf("s1", "s3") // s2 removed (close)
        val oldStore = SharedStateStore().apply {
            mutateSessionList { it.copy(sessions = listOf(Session(id = "s1", directory = "/a"), Session(id = "s2", directory = "/b"), Session(id = "s3", directory = "/c")), openSessionIds = oldIds) }
        }
        val newStore = SharedStateStore().apply {
            mutateSessionList { it.copy(sessions = listOf(Session(id = "s1", directory = "/a"), Session(id = "s2", directory = "/b"), Session(id = "s3", directory = "/c")), openSessionIds = oldIds) }
        }

        oldStore.mutateSessionList { it.copy(openSessionIds = newIds) }
        newStore.dispatch(AppAction.OpenSessionIdsChanged(newIds))

        assertEquals(
            "OpenSessionIdsChanged MUST equal legacy mutateSessionList openSessionIds write",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        assertEquals("openSessionIds updated", newIds, newStore.stateFlow.value.sessionList.openSessionIds)
        // sessions MUST survive (OpenSessionIdsChanged does NOT touch sessions).
        assertEquals("sessions untouched by OpenSessionIdsChanged", 3, newStore.stateFlow.value.sessionList.sessions.size)
    }

    @Test
    fun `dispatch OpenSessionIdsChanged for switchTo append mirrors SessionSwitcher 558-561`() = runTest {
        // SessionSwitcher:558-561: val updated = (openIds + sessionId).takeLast(8)
        // The caller computes `updated` and passes it in. The reducer just stores it.
        val existing = listOf("s1", "s2")
        val appended = (existing + "s3").takeLast(8) // = ["s1", "s2", "s3"]
        val store = SharedStateStore().apply {
            mutateSessionList { it.copy(openSessionIds = existing) }
        }

        store.dispatch(AppAction.OpenSessionIdsChanged(appended))

        assertEquals(
            "OpenSessionIdsChanged for switchTo append mirrors SessionSwitcher:561",
            appended,
            store.stateFlow.value.sessionList.openSessionIds,
        )
    }

    @Test
    fun `OpenSessionIdsChanged is scoped to openSessionIds — no other field touched`() {
        val prior = StoreState.initial().copy(
            sessionList = SessionListState(
                sessions = listOf(Session(id = "s1", directory = "/a")),
                openSessionIds = listOf("s1"),
                hasMoreSessions = true,
                pendingCreateIds = setOf("s1"),
            ),
        )
        val store = SharedStateStore().apply { mutateState { prior } }

        store.dispatch(AppAction.OpenSessionIdsChanged(listOf("s1", "s2")))

        val out = store.stateFlow.value.sessionList
        assertEquals("openSessionIds changed", listOf("s1", "s2"), out.openSessionIds)
        // Non-target fields MUST survive.
        assertEquals("sessions untouched", 1, out.sessions.size)
        assertTrue("hasMoreSessions untouched", out.hasMoreSessions)
        assertTrue("pendingCreateIds untouched", out.pendingCreateIds.contains("s1"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // D. BulkSessionsRefreshed + hasCompletedInitialLoad gap
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `BulkSessionsRefreshed sets hasCompletedInitialLoad to true (gap fix — currently requires a separate mutateSessionList patch)`() = runTest {
        // GAP: SessionListActions:293-296 documents that "The BulkSessionsRefreshed
        // reducer dispatched by the callback writes sessions/openIds but does NOT
        // set this flag, so set it explicitly here." The fix: add
        // hasCompletedInitialLoad = true to the BulkSessionsRefreshed reducer so
        // the separate mutateSessionList patch at :296 is no longer needed.
        //
        // This test asserts the DESIRED post-fix behavior: dispatch alone sets the flag.
        val store = SharedStateStore().apply {
            mutateSessionList { it.copy(hasCompletedInitialLoad = false) }
        }
        assertFalse("baseline: hasCompletedInitialLoad is false", store.stateFlow.value.sessionList.hasCompletedInitialLoad)

        store.dispatch(
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(Session(id = "s1", directory = "/a")),
                openSessionIds = listOf("s1"),
                hasMoreSessions = false,
                confirmedServerIds = setOf("s1"),
                sweepNow = 0L,
            )
        )

        assertTrue(
            "BulkSessionsRefreshed sets hasCompletedInitialLoad = true (the gap fix — " +
                "currently requires a separate mutateSessionList patch at SessionListActions:296)",
            store.stateFlow.value.sessionList.hasCompletedInitialLoad,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // E. Single-emission atomicity
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispatch SessionUpserted produces exactly one aggregate emission`() = runTest {
        val store = SharedStateStore().apply {
            mutateSessionList { it.copy(sessions = listOf(Session(id = "s1", directory = "/a"))) }
        }
        val seen = mutableListOf<StoreState>()
        val job = launch { store.stateFlow.collect { seen += it } }
        advanceUntilIdle()
        assertEquals(1, seen.size)

        store.dispatch(AppAction.SessionUpserted(Session(id = "s2", directory = "/b")))
        advanceUntilIdle()

        assertEquals("exactly one post-dispatch emission", 2, seen.size)
        assertEquals(2, seen.last().sessionList.sessions.size)
        job.cancel()
    }

    @Test
    fun `dispatch SessionCreatedLocal produces exactly one aggregate emission`() = runTest {
        val store = SharedStateStore()
        val seen = mutableListOf<StoreState>()
        val job = launch { store.stateFlow.collect { seen += it } }
        advanceUntilIdle()
        assertEquals(1, seen.size)

        store.dispatch(AppAction.SessionCreatedLocal(Session(id = "s1", directory = "/a"), 100L))
        advanceUntilIdle()

        assertEquals("exactly one post-dispatch emission", 2, seen.size)
        assertTrue(seen.last().sessionList.pendingCreateIds.contains("s1"))
        job.cancel()
    }

    @Test
    fun `dispatch OpenSessionIdsChanged produces exactly one aggregate emission`() = runTest {
        val store = SharedStateStore().apply {
            mutateSessionList { it.copy(openSessionIds = listOf("s1")) }
        }
        val seen = mutableListOf<StoreState>()
        val job = launch { store.stateFlow.collect { seen += it } }
        advanceUntilIdle()
        assertEquals(1, seen.size)

        store.dispatch(AppAction.OpenSessionIdsChanged(listOf("s1", "s2")))
        advanceUntilIdle()

        assertEquals("exactly one post-dispatch emission", 2, seen.size)
        assertEquals(listOf("s1", "s2"), seen.last().sessionList.openSessionIds)
        job.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // F. Documentation — production write-site inventory (target vs deferred)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `DOCUMENTATION — sessions and openSessionIds write-site inventory (target vs deferred)`() {
        // Full inventory of production mutateSessionList/writeSessionList
        // sites that touch `sessions` or `openSessionIds`. Audit with:
        //   rg -n 'mutateSessionList|writeSessionList' app/src/main
        //
        // ┌──────────────────────────────────────────────────────────────────────┐
        // │ TARGET (sessions and/or openSessionIds) — must migrate to dispatch   │
        // ├──────────────────────────────────┬──────────────┬────────────────────┤
        // │ file                             │ line(s)      │ action             │
        // ├──────────────────────────────────┼──────────────┼────────────────────┤
        // │ SessionMutationActions.kt        │ 42-48        │ SessionCreatedLocal│
        // │ SessionMutationActions.kt        │ 70           │ SessionUpserted    │
        // │ SessionMutationActions.kt        │ 102          │ SessionUpserted    │
        // │ SessionViewModel.kt              │ 157          │ SessionUpserted    │
        // │ SessionSwitcher.kt               │ 486          │ SessionUpserted    │
        // │ RevertConversation.kt            │ 44           │ SessionUpserted    │
        // │ AppCoreOrchestration.kt          │ 120-134      │ SessionUpserted    │
        // │ AppCoreOrchestration.kt          │ 195          │ SessionUpserted    │
        // │ SSC.kt                           │ 972          │ SessionUpserted    │
        // │ SSC.kt                           │ 1025         │ SessionUpserted    │
        // │ SessionViewModel.kt              │ 247          │ OpenSessionIdsChanged│
        // │ SessionSwitcher.kt               │ 561          │ OpenSessionIdsChanged│
        // │ SessionListActions.kt            │ 296 (patch)  │ BulkSessionsRefreshed│
        // │                                  │              │ +hasCompletedInitialLoad│
        // └──────────────────────────────────┴──────────────┴────────────────────┘
        //
        // ┌──────────────────────────────────────────────────────────────────────┐
        // │ DEFERRED — complex multi-field writes (sessions + non-target fields  │
        // │ in the same copy block). Impl must create per-site actions that      │
        // │ cover the FULL copy field set, or split carefully with testing.      │
        // ├──────────────────────────────────┬──────────────┬────────────────────┤
        // │ file                             │ line(s)      │ fields in copy()   │
        // ├──────────────────────────────────┼──────────────┼────────────────────┤
        // │ SessionMutationActions.kt        │ 186-202      │ sessions+dirSess+  │
        // │ (archive)                        │              │ childSess+openIds+ │
        // │                                  │              │ pendingQ+activeIds │
        // │ SessionMutationActions.kt        │ 312-331      │ sessions+dirSess+  │
        // │ (delete)                         │              │ pendingQ+activeIds+│
        // │                                  │              │ sessionErrorsById  │
        // │ SessionMutationActions.kt        │ 452          │ sessions+          │
        // │ (status)                         │              │ sessionStatuses    │
        // │ SessionListActions.kt            │ 299-321      │ sessions+hasMore+  │
        // │ (full refresh)                   │              │ loading+pending+   │
        // │                                  │              │ epoch+hasCompleted │
        // │ SessionListActions.kt            │ 548-566      │ sessions+limit+    │
        // │ (loadMore refresh)               │              │ hasMore+pending+   │
        // │                                  │              │ epoch              │
        // └──────────────────────────────────┴──────────────┴────────────────────┘
        //
        // ┌──────────────────────────────────────────────────────────────────────┐
        // │ EXPLICITLY NON-TARGET (no sessions/openSessionIds write)             │
        // ├──────────────────────────────────┬───────────────────────────────────┤
        // │ SessionListActions.kt:151        │ openSessionIds only — but part of │
        // │                                  │ a larger flow that dispatches      │
        // │                                  │ BulkSessionsRefreshed.            │
        // │ SessionListActions.kt:368        │ sessions upsert (directory session)│
        // │                                  │ → SessionUpserted.                 │
        // │ SessionListActions.kt:467        │ sessions + openSessionIds +        │
        // │                                  │ hasMore + loading flags            │
        // │                                  │ → BulkSessionsRefreshed variant.   │
        // │ SessionListActions.kt:601        │ sessions (merged refresh)           │
        // │                                  │ → BulkSessionsRefreshed variant.   │
        // │ SessionListActions.kt:704        │ pendingQuestions only              │
        // │ SessionListActions.kt:853        │ sessionDiffs only                  │
        // │ SessionListActions.kt:926        │ sessionDiffs only                  │
        // │ SessionListActions.kt:966        │ completenessEpoch only              │
        // │ SessionListActions.kt:1011       │ sessionTodos only                  │
        // │ SessionListActions.kt:1101       │ pendingPermissions only            │
        // │ SessionListActions.kt:1188       │ pendingQuestions only              │
        // │ MessageActions.kt:410            │ sessionTodos only                  │
        // │ OrchestratorViewModel.kt:121     │ pendingPermissions only            │
        // │ OrchestratorViewModel.kt:159     │ pendingQuestions only              │
        // │ OrchestratorViewModel.kt:192     │ pendingQuestions only              │
        // │ SSC various                      │ pendingQuestions/permissions/       │
        // │                                  │ sessionDiffs/sessionTodos/sessionErrors│
        // │ SessionViewModel.kt:345          │ directorySessions only             │
        // │ SessionViewModel.kt:465          │ pendingPermissions only            │
        // │ AppCoreOrchestration.kt:410      │ sessions (materializeDraftSession) │
        // │                                  │ → DraftSessionMaterialized (exists)│
        // │ AppCoreOrchestration.kt:538      │ sessions (materializeDraftSession) │
        // │                                  │ → DraftSessionMaterialized (exists)│
        // │ AppCoreOrchestration.kt:821      │ directorySessions only             │
        // │ ConnectionActions.kt:139         │ sessionStatuses only               │
        // │ ConnectionCoordinator.kt:882     │ sessionStatuses only               │
        // │ SessionTreeHydrator.kt:107       │ sessions+childSessions+            │
        // │                                  │ completenessEpoch+completeRootIds  │
        // │                                  │ → DEFERRED (complex tree hydration)│
        // │ ForegroundCatchUpController:308  │ pendingQuestions only              │
        // │ SessionSwitcher.kt:529           │ expandedParts only (T1a)           │
        // └──────────────────────────────────┴───────────────────────────────────┘
        assertTrue(
            "sessions/openSessionIds write-site inventory documented — see comment block above",
            true,
        )
    }
}
