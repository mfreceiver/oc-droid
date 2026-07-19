package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.controller.applyArchiveEviction
import cn.vectory.ocdroid.ui.controller.applyArchivedChatClear
import cn.vectory.ocdroid.ui.controller.cleanScrollStateForSubtree
import cn.vectory.ocdroid.ui.controller.allSessionsById
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
     *    `(settingsManager.openSessionIds.filterNot { it == session.id }
     *    + session.id).takeLast(8)` — the new tab joins from the RIGHT;
     *    the reducer just stores it — persistence is the caller's job).
     *  - [viewedAt]: `System.currentTimeMillis()` captured at the call site
     *    (the reducer writes both `unread.lastViewedTime` and the independent
     *    pending-create registration timestamp from this same clock value).
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
     * §Wave5b-Q13: the unified replacement for the pre-Wave5b
     * `PendingJumpToLatestSet`. Writes a fresh [PendingScrollRequest] to
     * `chat.pendingScrollRequest` UNCONDITIONALLY (a newer request always
     * supersedes any prior one — single-slot semantics, see
     * [PendingScrollRequest] kdoc).
     *
     * Issued by [cn.vectory.ocdroid.ui.controller.SessionSwitcher.switchTo]
     * inside the SAME mutateChat that flips currentSessionId (Latest for the
     * default arg, Restore(checkpoint) for returnToParent), AND by
     * [cn.vectory.ocdroid.ui.controller.SessionSwitcher.requestLatestScroll]
     * for the same-session "snap to latest on send / Chat-tab reselect" path
     * (which deliberately bypasses switchTo's same-session no-op guard).
     *
     * Single-slice / single-field write. Kept as a dispatched [AppAction]
     * (rather than a raw `mutateChat`) per the WT2 plan lineage so the intent
     * transition is observable on the aggregate stateFlow and unit-testable
     * via [AppActionReducerTest].
     */
    data class ScrollRequested(
        val requestId: Long,
        val targetSessionId: String,
        val behavior: ScrollBehavior,
    ) : AppAction

    /**
     * §Wave5b-Q13: COMPARE-AND-CLEAR of [PendingScrollRequest]. The reducer
     * clears `chat.pendingScrollRequest` IFF the live slot's requestId matches
     * [requestId]; otherwise it is a no-op.
     *
     * Why compare-and-clear (oracle ruling): in a fast A→B→C cascade, A's
     * consumer might finish LAST (effect relaunch ordering, page-slot
     * disposal timing). If A's `ScrollConsumed(A.requestId)` unconditionally
     * cleared the slot, it would wipe C's newer intent and leave C at the
     * default position. The id compare guarantees only the CURRENT intent is
     * clearable — a stale consumer's clear is silently dropped.
     */
    data class ScrollConsumed(
        val requestId: Long,
    ) : AppAction

    /**
     * §Wave5b-Q13: store the parent's [ScrollCheckpoint] under the child id
     * key. Issued by [cn.vectory.ocdroid.ui.SessionViewModel.openSubAgent]
     * BEFORE the inner selectSession(childId) so the entry is on file by the
     * time currentSessionId flips. Single-field write
     * (`chat.parentReturnCheckpoints + (childId to checkpoint)`).
     */
    data class ParentCheckpointStored(
        val childId: String,
        val checkpoint: ScrollCheckpoint,
    ) : AppAction

    /**
     * §Wave5b-Q13: remove the [childId] entry from
     * `chat.parentReturnCheckpoints`. Issued by
     * [cn.vectory.ocdroid.ui.SessionViewModel.returnToParent] in the SAME
     * sequence as the inner `switchTo(parentId, Restore(checkpoint))` so the
     * entry is cleaned even if the user re-opens the child later (no stale
     * checkpoint leak). The reducer removes only the matching key (no-op if
     * absent — defensive against a double-consume).
     */
    data class ParentCheckpointConsumed(
        val childId: String,
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
     *  - [sessions]: the full merged refresh result (authoritative server
     *    records plus any still-pending local-only records preserved by Q4).
     *  - [openSessionIds]: the NEW open-tabs list with ALL archived ids pruned
     *    (caller computes + persists; reducer just stores it).
     *  - [hasMoreSessions]: the pagination flag (mirrors the mutateSessionList
     *    field the non-archive path writes).
     *  - [confirmedServerIds]: ids from the raw REST response before merge;
     *    this is the only source allowed to confirm pending creates.
     *  - [sweepNow]: caller-captured wall-clock time used with
     *    [SessionListState.pendingCreatedAt] for the 30 s timeout.
     */
    data class BulkSessionsRefreshed(
        val sessions: List<Session>,
        val openSessionIds: List<String>,
        val hasMoreSessions: Boolean,
        /** Ids from the raw REST response, before pending-local preservation. */
        val confirmedServerIds: Set<String>,
        /** Wall-clock time captured by the REST success path for timeout sweep. */
        val sweepNow: Long,
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
            // §Q4-strict-sync: a freshly-created session is NOT yet in the
            // server's authoritative listing. Track its id as pending-create
            // so the next mergeRefreshedSessionsPreservingLocalActivity keeps
            // it alive until a REST refresh or SSE session.created confirms it
            // (or the 30 s sweep drops it). Added atomically in the SAME
            // dispatch as the session upsert (no torn intermediate).
            pendingCreateIds = state.sessionList.pendingCreateIds + action.session.id,
            pendingCreatedAt = state.sessionList.pendingCreatedAt + (action.session.id to action.viewedAt),
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
        // Apply the chat CONTENT clear IFF the archived session IS the current
        // one — derived from the snapshot, not carried on the action.
        val isCurrent = state.chat.currentSessionId == action.session.id
        val newSessionList = state.sessionList.applyArchiveEviction(action.session, action.openSessionIds).first
        // §Wave5b-Q13 blocker-2 fix: SCROLL-STATE cleanup runs UNCONDITIONALLY
        // for the archived subtree (chat content remains current-only). For
        // the current-archived case applyArchivedChatClear already wipes both
        // fields, so cleanScrollStateForSubtree is a no-op; for non-current
        // archived ids it cleans a stale pendingScrollRequest (target in
        // subtree) + parentReturnCheckpoints (key in subtree) without
        // touching messages / parts / currentSessionId.
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
        // §Wave5b-Q13 blocker-2: apply UNCONDITIONAL scroll-state cleanup for
        // the archived subtree (no-op for current-archived — applyArchivedChatClear
        // above already wiped both fields; substantive for non-current archived
        // ids that had a stale pendingScrollRequest targeting them or a
        // parentReturnCheckpoints entry keyed by them).
        val newChatCleaned = newChat.cleanScrollStateForSubtree(subtree)
        // §final-gate I-3 (review-final-rev-gpt-20260719081038 §2): prune the
        // archived subtree's entries from sessionErrorsById atomically in the
        // same committed state as the archive. Pre-fix the archived sid's
        // lastError survived forever (T12 only removes entries on explicit
        // `lastError = Cleared`), producing unbounded retention + a stale
        // banner if the user later un-archives or the server reuses the id.
        // The subtree scope mirrors the unread / pendingQuestions cleanup
        // (defensive against a server that only emits the root archive event
        // — descendants that did NOT get their own session.updated still get
        // pruned here). T12's set/remove producer logic is unchanged.
        val cleanedSessionErrors = state.sessionList.sessionErrorsById.filterKeys { it !in subtree }
        state.copy(
            sessionList = newSessionList.copy(
                pendingQuestions = cleanedQuestions,
                activeSessionIds = newSessionList.activeSessionIds - subtree,
                sessionErrorsById = cleanedSessionErrors,
            ),
            chat = newChatCleaned,
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
            // resets currentModel / olderMessagesCursor / hasMoreMessages /
            // staleNotice / revertCutoffs / SSE-coalesce buffers, AND sessionList pendingPermissions/pendingQuestions are
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
                    activeSessionIds = emptySet(),
                    sessionTodos = emptyMap(),
                    sessionDiffs = emptyMap(),
                    // §gpter-residual: cross-group purge must also drop cached
                    // child trees and completeness proofs — a root-id collision
                    // across hosts would otherwise let a stale proof skip new-host
                    // hydration. Bump the epoch so any in-flight child load
                    // captured before the switch is dropped fail-closed instead
                    // of committing the prior host's children here.
                    childSessions = emptyMap(),
                    completeRootIds = emptySet(),
                    completenessEpoch = state.sessionList.completenessEpoch + 1L,
                    // §fix-leak-window (fix B): pending permission / question
                    // requests belong to the prior host's sessions — must NOT
                    // survive a cross-group switch.
                    pendingPermissions = emptyList(),
                    pendingQuestions = emptyList(),
                    // §Q4-strict-sync: cross-group purge must drop pending-
                    // create ids — they reference the prior host's sessions
                    // and would ghost into the new host's list.
                    pendingCreateIds = emptySet(),
                    pendingCreatedAt = emptyMap(),
                    // §final-gate I-3 (review-final-rev-gpt-20260719081038 §2):
                    // cross-group purge must drop the entire sessionErrorsById
                    // map — entries reference the prior host's sessions and a
                    // root-id collision on the new host would let T17 render
                    // the prior host's banner. Mirrors the cross-group reset
                    // of sessionStatuses / pendingPermissions / pendingQuestions
                    // above (T12's set/remove logic is unchanged — this is a
                    // lifecycle cleanup, not a producer-path change).
                    sessionErrorsById = emptyMap(),
                    // I-2 v2 §3.3: cross-group purge MUST reset the
                    // aggregation signals — they reference the prior host's
                    // aggregation state and a stale "FAILED" would otherwise
                    // surface on the new host. Defaults to COMPLETE (no signal).
                    // Tied to I-3's sessionErrorsById cleanup (same lifecycle).
                    questionAggregationSignal = SlimAggregationSignal(),
                    permissionAggregationSignal = SlimAggregationSignal(),
                    // §fix-close-all-residual: re-arm the cold-start
                    // auto-select for the new host — its first load should
                    // land the user on a session just like a fresh launch.
                    hasCompletedInitialLoad = false,
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
            //
            // §chat-ux-batch T7 review-fix (I2): a pending agent/model pick
            // belongs to the PRIOR profile's next send — it must NOT survive
            // a host/profile transition even within the same server group.
            // Pre-fix this branch preserved pendingAgent/pendingModel
            // untouched, so a pick from profile A leaked into profile B's
            // first send (violates T7's no-cross-transition-carry contract).
            // Mirrors the cross-group branch's clearSessionData() reset of
            // these two transient fields.
            Triple(
                state.chat.copy(
                    streamingPartTexts = emptyMap(),
                    streamingReasoningPart = null,
                    pendingAgent = null,
                    pendingModel = null,
                    // §Wave5b-Q13: a host/profile transition invalidates any
                    // pending scroll intent + the parent-return backstack
                    // even within the same server group. The scroll slot
                    // references a session the user is navigating away from
                    // (or that may be re-laid-out differently on the new
                    // profile); the backstack is per-session navigation
                    // context that has no carry across profiles. Mirrors the
                    // cross-group branch's clearSessionData() reset.
                    pendingScrollRequest = null,
                    parentReturnCheckpoints = emptyMap(),
                ),
                // §Q4-strict-sync: clear pendingCreateIds even on same-group
                // switch (the spec mandates "host switch → clear pending"). A
                // pending id from profile A is meaningless on profile B even
                // within the same server group; clearing is safer (no ghost)
                // and the ids re-populate naturally from the next REST refresh.
                state.sessionList.copy(
                    pendingCreateIds = emptySet(),
                    pendingCreatedAt = emptyMap(),
                    activeSessionIds = emptySet(),
                ),
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
        // the cross-host purge (currentModel / cursor / etc. all reset;
        // pre-B2 left them stale). The 3 chrome fields are preserved
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

    is AppAction.ScrollRequested -> state.copy(
        // §Wave5b-Q13: single-slot overwrite. A newer request ALWAYS supersedes
        // any prior one — there is no priority / merge logic. The consumer
        // observes the latest committed slot only (single dispatch = single
        // emission).
        chat = state.chat.copy(
            pendingScrollRequest = PendingScrollRequest(
                requestId = action.requestId,
                targetSessionId = action.targetSessionId,
                behavior = action.behavior,
            ),
        ),
    )

    is AppAction.ScrollConsumed -> {
        // §Wave5b-Q13: COMPARE-AND-CLEAR. Only the CURRENT intent (matching
        // requestId) is clearable. A stale consumer's clear (e.g. A's consumer
        // finishing after B's intent is already live) is a silent no-op so it
        // cannot wipe the newer intent.
        val live = state.chat.pendingScrollRequest
        if (live != null && live.requestId == action.requestId) {
            state.copy(chat = state.chat.copy(pendingScrollRequest = null))
        } else {
            state
        }
    }

    is AppAction.ParentCheckpointStored -> state.copy(
        // §Wave5b-Q13: append the (childId → checkpoint) entry. Preserves any
        // other entries (a user can be deep in child-of-child-of-child
        // navigation; each openSubAgent stores its own parent's checkpoint).
        chat = state.chat.copy(
            parentReturnCheckpoints = state.chat.parentReturnCheckpoints + (action.childId to action.checkpoint),
        ),
    )

    is AppAction.ParentCheckpointConsumed -> state.copy(
        // §Wave5b-Q13: remove only the matching childId key. No-op if absent
        // (double-consume / cleared by a host purge between store + consume).
        chat = state.chat.copy(
            parentReturnCheckpoints = state.chat.parentReturnCheckpoints - action.childId,
        ),
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
        // §Q4-strict-sync: confirmation is based only on ids from the raw REST
        // response, never action.sessions (which is the merged list and may
        // contain preserved pending-local sessions). The caller also captures
        // sweepNow so this pure reducer can apply the independent registration-
        // timestamp timeout atomically with the merged sessions write.
        val remainingPendingIds = state.sessionList.pendingCreateIds
            .minus(action.confirmedServerIds)
            .filterTo(mutableSetOf()) { pendingId ->
                val registeredAt = state.sessionList.pendingCreatedAt[pendingId]
                registeredAt != null &&
                    action.sweepNow - registeredAt <= MainViewModelTimings.pendingCreateTimeoutMs
            }
        val newSessionList = state.sessionList.copy(
            sessions = action.sessions,
            openSessionIds = action.openSessionIds,
            hasMoreSessions = action.hasMoreSessions,
            isLoadingMoreSessions = false,
            isRefreshingSessions = false,
            pendingCreateIds = remainingPendingIds,
            pendingCreatedAt = state.sessionList.pendingCreatedAt.filterKeys { it in remainingPendingIds },
            // A bulk refresh is authoritative for structure (archive-sync),
            // so any cached completeness proof may be stale if SSE dropped
            // events. Discard proofs and bump the epoch so in-flight hydration
            // is dropped fail-closed; the next tick re-hydrates fresh trees.
            completeRootIds = emptySet(),
            completenessEpoch = state.sessionList.completenessEpoch + 1L,
            activeSessionIds = state.sessionList.activeSessionIds.intersect(
                allSessionsById(
                    action.sessions,
                    state.sessionList.directorySessions,
                    state.sessionList.childSessions,
                ).keys
            ),
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
        // chat window). applyArchivedChatClear also wipes pendingScrollRequest
        // + parentReturnCheckpoints (FIX-B / §Wave5b-Q13).
        val newChat = if (isCurrentArchived) {
            state.chat.applyArchivedChatClear().first
        } else {
            state.chat
        }
        // §Wave5b-Q13 blocker-2: UNCONDITIONAL scroll-state cleanup for the
        // archived subtree union. For current-archived the prior
        // applyArchivedChatClear already wiped both fields (no-op here); for
        // NON-current archived ids this drops a stale pendingScrollRequest
        // (target in subtree) + parentReturnCheckpoints entries (key in
        // subtree) without touching chat content.
        val newChatCleaned = newChat.cleanScrollStateForSubtree(allArchivedSubtree)
        state.copy(
            sessionList = newSessionList.copy(pendingQuestions = cleanedQuestions),
            chat = newChatCleaned,
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
 * `currentModel` / `olderMessagesCursor` / `hasMoreMessages`
 * / `staleNotice` / `revertCutoffs` / `deltaBuffer` / `fullTextBuffer` /
 * `pendingFlushPartIds` stale — verified via
 * `git show e190cce^:app/src/main/java/cn/vectory/ocdroid/ui/controller/HostProfileController.kt`
 * (purgePerHostState) and
 * `git show e190cce^:app/src/main/java/cn/vectory/ocdroid/ui/AppCoreOrchestration.kt`
 * (createSessionInWorkdirForEffect): NEITHER cleared these fields. Fix B is
 * therefore a deliberate IMPROVEMENT, not a missed regression — a stale
 * model / cursor from the prior host or session no longer bleeds
 * into the new view. Uses `.copy()` so the 3 chrome fields are preserved.
 */
private fun ChatState.clearSessionData(): ChatState = copy(
    currentSessionId = null,
    messages = emptyList(),
    revertCutoffs = emptyMap(),
    partsByMessage = emptyMap(),
    streamingPartTexts = emptyMap(),
    streamingReasoningPart = null,
    // §slimapi-client-v1 §G6 (Task 16 round-2): clear per-part expand states
    // on transcript clear (host purge, archive, draft materialize).
    partExpandStates = emptyMap(),
    olderMessagesCursor = null,
    hasMoreMessages = false,
    isLoadingMessages = false,
    isLoadingMoreMessages = false,
    staleNotice = false,
    currentModel = null,
    deltaBuffer = emptyMap(),
    fullTextBuffer = emptyMap(),
    pendingFlushPartIds = emptySet(),
    // §chat-ux-batch T7 (B2): clear the TRANSIENT pending picks too — they
    // are per-session by contract ("no cross-session carry"). Without this, a
    // pending agent/model picked in session A would leak into the new draft
    // (or into a freshly-purged host view), defeating the pending contract.
    pendingAgent = null,
    pendingModel = null,
    // §Wave5b-Q13: clear the unified scroll slot + the parent-return
    // backstack (replaces the prior `pendingJumpToLatest = null` clear).
    // The slot references a target session id that is being cleared; the
    // backstack is per-session navigation context that has no meaning after
    // a draft-create or cross-host purge.
    pendingScrollRequest = null,
    parentReturnCheckpoints = emptyMap(),
    // PRESERVED (chrome, NOT per-session — kept via .copy() above):
    // isCompacting, compactStartedAt, refreshNonce.
)
