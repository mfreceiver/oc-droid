package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.controller.applyArchiveEviction
import cn.vectory.ocdroid.ui.controller.applyArchivedChatClear
import cn.vectory.ocdroid.ui.controller.removeSessions
import cn.vectory.ocdroid.ui.controller.subtreeIds

/**
 * §A5-3 Phase B2: a pure-data sealed hierarchy describing the cross-slice
 * state transitions the app performs atomically. Each variant replaces a
 * pre-B2 site that scattered N `mutateXxx` / `writeXxx` calls across
 * [SharedStateStore]; that scattering produced N intermediate committed
 * aggregate states per logical transition (visible to any concurrent
 * `stateFlow` collector as torn reads — e.g. `sessionList` archived-but-
 * `chat.currentSessionId` still pointing at the archived id).
 *
 * The variants carry ONLY pure data — no transform lambdas, no `clearChat`
 * booleans derivable from the snapshot. The reducer ([reduce]) decides from
 * the current [StoreState] which fields to clear (e.g. the archived session
 * clears chat IFF it is the current one — derived inside [reduce], not
 * carried on the action).
 *
 * Behavior-preservation contract (the B2 gate): the field changes each
 * variant produces in [reduce] MUST match — field-for-field, no more no
 * less — the multi-`mutateXxx` sequence the corresponding pre-B2 site
 * performed. The existing site tests (AppCoreOrchestrationTest /
 * SessionSyncCoordinatorTest / HostProfileControllerTest) are the
 * behavior-equivalence proof; they MUST stay GREEN unchanged.
 *
 * What is NOT here (intentionally, oracle ruling): the network calls,
 * `settingsManager.*` writes, `persistSessionCache` / cache-eviction, and
 * effect-bus emissions (`EvictSession` / `EvictGroup` / `ForceReconnect` /
 * `HostProfileSwitched`) that surround each migrated site are NOT part of
 * the reducer. They stay at the call site (they are side-effects, not
 * state) and run AROUND the single [SharedStateStore.dispatch] that
 * commits the action. Likewise the intentional pre-network
 * `writeComposer { draftWorkdir = null }` in materializeDraftSession + its
 * failure-restoration writeComposer stay separate (oracle: those are
 * intentional, not part of the success-path atomic commit).
 */
internal sealed interface AppAction {
    /**
     * materializeDraftSession success path: a freshly-created [session] is
     * wired into sessionList (upsert + openSessionIds), chat.currentSessionId
     * is set, the new session is dropped from unread + its lastViewedTime is
     * bumped to [viewedAt], and composer.draftWorkdir is cleared.
     *
     * Carries exactly the data the reducer needs:
     *  - [session]: the Session returned by `repository.createSession` (the
     *    reducer upserts it into sessionList.sessions).
     *  - [openSessionIds]: the NEW full open-tabs list (caller computes
     *    `(listOf(session.id) + settingsManager.openSessionIds).distinct()
     *    .take(8)`; the reducer just stores it — persistence is the caller's
     *    job).
     *  - [viewedAt]: `System.currentTimeMillis()` captured at the call site
     *    (the reducer writes `unread.lastViewedTime + (session.id to viewedAt)`).
     */
    data class DraftSessionMaterialized(
        val session: Session,
        val openSessionIds: List<String>,
        val viewedAt: Long,
    ) : AppAction

    /**
     * session.updated archived SSE branch (cross-client archive): upsert the
     * archived [session] + replace openSessionIds (applyArchiveEviction), and
     * IFF the archived [session].id IS the currently-open chat session, clear
     * chat.currentSessionId + messages + partsByMessage (applyArchivedChatClear).
     * The "clear chat" decision is derived inside [reduce] from the snapshot
     * (NOT carried as a boolean) so the action stays pure data.
     *
     * Carries:
     *  - [session]: the archived Session (full record — the reducer upserts it
     *    so the authoritative copy reflects the archived flag).
     *  - [openSessionIds]: the NEW open-tabs list with this id filtered out
     *    (caller computes + persists; reducer just stores it).
     */
    data class SessionArchived(
        val session: Session,
        val openSessionIds: List<String>,
    ) : AppAction

    /**
     * purgePerHostState (host switch / delete-active-host): the cross-slice
     * purge. The [preserveServerGroupData] flag matches the call-site
     * `sameGroup = previousFp == targetFp` decision — when true (same-group
     * switch) sessions / unread / session-window cache are PRESERVED (server-
     * identical data); when false (异组 switch / delete active host) the full
     * reset runs. Per-profile UX (composer.draftWorkdir /
     * settings.availableCommands / connection.serverVersion) is ALWAYS reset.
     *
     * What is NOT here (oracle): the SettingsManager writes
     * (`clearRecentWorkdirs` / `currentWorkdir` / `openSessionIds` /
     * `sessionCache`) and the effect-bus emissions (`EvictGroup` /
     * `ForceReconnect` / `HostProfileSwitched`) stay at the call site — they
     * are side-effects, not state. The reducer only touches SharedStateStore
     * slices.
     *
     * The reducer PRESERVES the three ChatState-only fields documented at
     * HostProfileController.kt:475-479 (isCompacting, compactStartedAt,
     * refreshNonce) — it uses `.copy()` on the existing ChatState, never a
     * fresh `ChatState()`.
     */
    data class HostStatePurged(
        val preserveServerGroupData: Boolean,
    ) : AppAction

    /**
     * createSessionInWorkdirForEffect ("new session in workdir X" draft
     * entry): clears chat (currentSessionId / messages / partsByMessage /
     * streaming overlays / currentModel — the prior session's inferred model
     * must NOT leak into the draft picker), clears sessionList.sessionTodos,
     * and resets composer (inputText / imageAttachments / fileReferences
     * cleared, draftWorkdir set to [workdir]).
     *
     * Carries only the [workdir] string — every other field is a constant
     * reset, so no need to parameterize.
     */
    data class WorkdirDraftStarted(
        val workdir: String,
    ) : AppAction

    /**
     * §WT2-taskB (Q6 locked): one-shot "Sessions page entry → jump to latest"
     * intent on [ChatState.pendingJumpToLatest]. The reducer sets
     * `chat.pendingJumpToLatest = action.sessionId`:
     *  - non-null [sessionId] → SET the intent (the id the user tapped in the
     *    Sessions list). Used by
     *    [cn.vectory.ocdroid.ui.SessionViewModel.requestJumpToLatest], which
     *    SessionsScreen.onSessionClick fires BEFORE selectSession so the
     *    matching switchTo keeps it and ChatMessageList consumes it once the
     *    session's messages have loaded.
     *  - null [sessionId] → CLEAR the intent. Used by
     *    [cn.vectory.ocdroid.ui.ChatViewModel.clearPendingJumpToLatest] after
     *    ChatMessageList has performed the scrollToItem(0) jump, so the intent
     *    fires exactly once per Sessions-page entry.
     *
     * Single-slice / single-field write. Kept as a dispatched [AppAction]
     * (rather than a raw `mutateChat`) per the WT2 plan ("reducer entries to
     * set it and clear it") so the intent transition is observable on the
     * aggregate stateFlow and unit-testable via [AppActionReducerTest].
     */
    data class PendingJumpToLatestSet(
        val sessionId: String?,
    ) : AppAction

    /**
     * FIX-A/C (archive-sync, review-blocker): atomic bulk-refresh commit.
     * Replaces the torn two-step (`mutateSessionList` then
     * `onCurrentSessionArchived` → separate dispatch) with a SINGLE dispatch
     * that writes the merged session list AND prunes [openSessionIds] of EVERY
     * archived id (FIX-A — not just current) AND — if the current session is
     * among the archived — clears chat via [applyArchivedChatClear] + does
     * unread/pending-question subtree cleanup (mirrors [SessionArchived]'s
     * cleanup for the current session). All in one committed aggregate state
     * so no collector ever observes the torn intermediate
     * "sessions[current].isArchived == true AND chat.currentSessionId == current".
     *
     * Non-current archived ids: the [openSessionIds] prune alone is sufficient
     * (SSE parity — the SSE path's observable effect for non-current archived
     * sessions is the openIds prune; the per-session unread/questions cleanup
     * there is defensive and the bulk path's prune closes the ghost-tab hole).
     *
     * Carries:
     *  - [sessions]: the full merged refresh result (server-authoritative;
     *    includes archived sessions with isArchived == true).
     *  - [openSessionIds]: the NEW open-tabs list with ALL archived ids pruned
     *    (caller computes + persists; reducer just stores it).
     *  - [hasMoreSessions]: the pagination flag (mirrors the mutateSessionList
     *    field the non-archive path writes).
     */
    data class BulkSessionsRefreshed(
        val sessions: List<Session>,
        val openSessionIds: List<String>,
        val hasMoreSessions: Boolean,
    ) : AppAction
}

/**
 * §A5-3 Phase B2: the PURE reducer that turns a [StoreState] + [AppAction]
 * into a new [StoreState]. No effects, no settings writes, no network, no
 * emit — it ONLY returns a new [StoreState]. The single committed aggregate
 * is then written atomically by [SharedStateStore.dispatch] via ONE
 * `state.update { reduce(it, action) }` (one CAS, one emission), which is
 * the atomicity mechanism — concurrent `stateFlow` collectors observe a
 * single transition with no torn intermediate states.
 *
 * Field-change parity: each branch replicates the EXACT field writes the
 * corresponding pre-B2 site did. See the per-variant kdocs on [AppAction]
 * for the mapping + the call sites (AppCoreOrchestration.kt /
 * SessionSyncCoordinator.kt / HostProfileController.kt).
 */
internal fun reduce(state: StoreState, action: AppAction): StoreState = when (action) {
    is AppAction.DraftSessionMaterialized -> state.copy(
        sessionList = state.sessionList.copy(
            sessions = upsertSession(state.sessionList.sessions, action.session),
            openSessionIds = action.openSessionIds,
        ),
        chat = state.chat.copy(
            currentSessionId = action.session.id,
        ),
        unread = state.unread.copy(
            unreadSessions = state.unread.unreadSessions - action.session.id,
            lastViewedTime = state.unread.lastViewedTime + (action.session.id to action.viewedAt),
        ),
        composer = state.composer.copy(
            draftWorkdir = null,
        ),
    )

    is AppAction.SessionArchived -> {
        // Apply archive eviction unconditionally (upsert archived + new openIds).
        // Apply the chat clear IFF the archived session IS the current one —
        // derived from the snapshot, not carried on the action.
        val isCurrent = state.chat.currentSessionId == action.session.id
        val newSessionList = state.sessionList.applyArchiveEviction(action.session, action.openSessionIds).first
        val newChat = if (isCurrent) state.chat.applyArchivedChatClear().first else state.chat
        // §task5-lifecycle (final-review fix 1): the archived session's unread
        // badge + any pending question bound to it MUST NOT survive the archive.
        // The cleanup is computed over the FULL three-source subtree of the
        // archived id — defensive against a server that only emits the root
        // archive event (descendants that did NOT get their own session.updated
        // event still get cleaned atomically here). Done in the SAME committed
        // state as the archive itself so collectors never observe an "archived
        // but still unread" torn state.
        val archivedId = action.session.id
        val subtree = subtreeIds(
            archivedId,
            state.sessionList.sessions,
            state.sessionList.directorySessions,
            state.sessionList.childSessions,
        )
        val cleanedQuestions = newSessionList.pendingQuestions.filter { it.sessionId !in subtree }
        val newUnread = state.unread.removeSessions(subtree)
        state.copy(
            sessionList = newSessionList.copy(pendingQuestions = cleanedQuestions),
            chat = newChat,
            unread = newUnread,
        )
    }

    is AppAction.HostStatePurged -> {
        // §slice-only-preserve: ChatState carries three fields NOT mirrored
        // to AppState (isCompacting / compactStartedAt / refreshNonce — see
        // HostProfileController.kt:475-479). [clearSessionData] uses .copy()
        // on the existing ChatState so they are preserved; only the per-
        // session chat fields are reset.
        val (newChat, newSessionList, newUnread) = if (!action.preserveServerGroupData) {
            // Cross-group (异组 switch / delete active host): full purge of
            // per-server + per-profile state.
            // §fix-leak-window (release-gate fix B): clearSessionData also
            // resets currentModel / gapMarkers / olderMessagesCursor /
            // hasMoreMessages / staleNotice / revertCutoffs / SSE-coalesce
            // buffers, AND sessionList pendingPermissions/pendingQuestions are
            // cleared — pre-B2 left all of these stale (verified via
            // `git show e190cce^:.../HostProfileController.kt` purgePerHostState
            // + `git show e190cce^:.../AppCoreOrchestration.kt`
            // createSessionInWorkdirForEffect — neither cleared them). This is
            // a deliberate IMPROVEMENT, not a missed regression.
            Triple(
                state.chat.clearSessionData(),
                state.sessionList.copy(
                    sessions = emptyList(),
                    directorySessions = emptyMap(),
                    openSessionIds = emptyList(),
                    sessionStatuses = emptyMap(),
                    sessionTodos = emptyMap(),
                    sessionDiffs = emptyMap(),
                    // §fix-leak-window (fix B): pending permission / question
                    // requests belong to the prior host's sessions — must NOT
                    // survive a cross-group switch.
                    pendingPermissions = emptyList(),
                    pendingQuestions = emptyList(),
                ),
                state.unread.copy(
                    unreadSessions = emptySet(),
                    lastViewedTime = emptyMap(),
                    // §unread-soak: clear the soak map on cross-group purge so
                    // a stale idleSince entry from the prior host cannot later
                    // fire an unread badge for a session that no longer exists.
                    idleSince = emptyMap(),
                ),
            )
        } else {
            // Same-group switch: per-server data (sessions / unread /
            // directorySessions / statuses / todos / diffs) preserved. Only
            // the streaming overlay is cleared (a stale delta from the old
            // profile's in-flight turn must NOT bleed into the new profile's
            // view).
            Triple(
                state.chat.copy(
                    streamingPartTexts = emptyMap(),
                    streamingReasoningPart = null,
                ),
                state.sessionList,
                state.unread,
            )
        }
        state.copy(
            chat = newChat,
            sessionList = newSessionList,
            unread = newUnread,
            // Per-profile UX — ALWAYS reset regardless of group.
            composer = state.composer.copy(draftWorkdir = null),
            settings = state.settings.copy(availableCommands = emptyList()),
            connection = state.connection.copy(serverVersion = null),
        )
    }

    is AppAction.WorkdirDraftStarted -> state.copy(
        // §fix-leak-window (release-gate fix B): full per-session clear via
        // clearSessionData — closes the draft leak window consistently with
        // the cross-host purge (currentModel / gapMarkers / cursor / etc. all
        // reset; pre-B2 left them stale). The 3 chrome fields are preserved
        // via .copy() inside clearSessionData.
        chat = state.chat.clearSessionData(),
        sessionList = state.sessionList.copy(
            sessionTodos = emptyMap(),
        ),
        composer = state.composer.copy(
            inputText = "",
            imageAttachments = emptyList(),
            // §1B-FIX (I4): also clear fileReferences on draft-create so a
            // chip from the previous session's draft does not survive the
            // workdir switch.
            fileReferences = emptyList(),
            draftWorkdir = action.workdir,
        ),
    )

    is AppAction.PendingJumpToLatestSet -> state.copy(
        // §WT2-taskB: single-field write. No cross-slice concerns.
        chat = state.chat.copy(pendingJumpToLatest = action.sessionId),
    )

    is AppAction.BulkSessionsRefreshed -> {
        // FIX-A/C (archive-sync, review-blocker): atomic bulk-refresh commit.
        // Writes the merged list + pruned openIds + load flags in ONE step.
        //
        // gro-2 Blocker 1 (round-2): subtree / unread / pendingQuestions
        // cleanup now runs UNCONDITIONALLY over ALL archived ids (not just
        // the current session) — mirroring SessionArchived's unconditional
        // subtree cleanup. Previously the else-branch (non-current archived)
        // skipped cleanup entirely, leaking stale unread badges +
        // pendingQuestions for non-current archived open tabs (inflating
        // crossSessionPendingCount). The CHAT-CLEAR remains current-only:
        // only the archived CURRENT session's chat is wiped (non-current
        // archived ids have no active chat window to clear).
        val archivedIds = action.sessions
            .filter { it.isArchived }
            .map { it.id }
            .toSet()
        val currentId = state.chat.currentSessionId
        val isCurrentArchived = currentId != null && currentId in archivedIds
        val newSessionList = state.sessionList.copy(
            sessions = action.sessions,
            openSessionIds = action.openSessionIds,
            hasMoreSessions = action.hasMoreSessions,
            isLoadingMoreSessions = false,
            isRefreshingSessions = false,
        )
        // Compute the subtree UNION over ALL archived ids. Each archived root
        // may have descendants that did NOT get their own archive event —
        // defensive subtree cleanup (mirrors SessionArchived's logic).
        val allArchivedSubtree = archivedIds.flatMap { archivedId ->
            subtreeIds(
                archivedId,
                action.sessions,
                newSessionList.directorySessions,
                newSessionList.childSessions,
            )
        }.toSet()
        val cleanedQuestions = newSessionList.pendingQuestions
            .filter { it.sessionId !in allArchivedSubtree }
        val newUnread = state.unread.removeSessions(allArchivedSubtree)
        // Chat-clear is CURRENT-ONLY (non-current archived ids have no active
        // chat window). applyArchivedChatClear also wipes pendingJumpToLatest
        // (FIX-B).
        val newChat = if (isCurrentArchived) {
            state.chat.applyArchivedChatClear().first
        } else {
            state.chat
        }
        state.copy(
            sessionList = newSessionList.copy(pendingQuestions = cleanedQuestions),
            chat = newChat,
            unread = newUnread,
        )
    }
}

/**
 * §fix-leak-window (release-gate fix B): reset ALL per-session [ChatState]
 * fields, preserving only the 3 chrome fields (isCompacting /
 * compactStartedAt / refreshNonce — NOT per-session; they survive a host
 * purge / draft reset and are documented at HostProfileController.kt:475-479).
 *
 * Closes the cross-host / draft leak window: pre-B2
 * `purgePerHostState` (cross-group) + `createSessionInWorkdir*` left
 * `currentModel` / `gapMarkers` / `olderMessagesCursor` / `hasMoreMessages`
 * / `staleNotice` / `revertCutoffs` / `deltaBuffer` / `fullTextBuffer` /
 * `pendingFlushPartIds` stale — verified via
 * `git show e190cce^:app/src/main/java/cn/vectory/ocdroid/ui/controller/HostProfileController.kt`
 * (purgePerHostState) and
 * `git show e190cce^:app/src/main/java/cn/vectory/ocdroid/ui/AppCoreOrchestration.kt`
 * (createSessionInWorkdirForEffect): NEITHER cleared these fields. Fix B is
 * therefore a deliberate IMPROVEMENT, not a missed regression — a stale
 * model / cursor / gap-set from the prior host or session no longer bleeds
 * into the new view. Uses `.copy()` so the 3 chrome fields are preserved.
 */
private fun ChatState.clearSessionData(): ChatState = copy(
    currentSessionId = null,
    messages = emptyList(),
    revertCutoffs = emptyMap(),
    partsByMessage = emptyMap(),
    streamingPartTexts = emptyMap(),
    streamingReasoningPart = null,
    olderMessagesCursor = null,
    hasMoreMessages = false,
    isLoadingMessages = false,
    isLoadingMoreMessages = false,
    gapMarkers = emptyList(),
    staleNotice = false,
    currentModel = null,
    deltaBuffer = emptyMap(),
    fullTextBuffer = emptyMap(),
    pendingFlushPartIds = emptySet(),
    // §WT2-taskB: clear the one-shot jump-to-latest intent too — it
    // references a session id that is being cleared (draft / host purge).
    pendingJumpToLatest = null,
    // PRESERVED (chrome, NOT per-session — kept via .copy() above):
    // isCompacting, compactStartedAt, refreshNonce.
)
