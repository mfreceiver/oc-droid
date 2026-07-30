package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.ui.controller.allSessionsById
import cn.vectory.ocdroid.ui.controller.applyArchiveEviction
import cn.vectory.ocdroid.ui.controller.applyArchivedChatClear
import cn.vectory.ocdroid.ui.controller.cleanScrollStateForSubtree
import cn.vectory.ocdroid.ui.controller.removeSessions
import cn.vectory.ocdroid.ui.controller.subtreeIds

/**
 * Wave 2 lane L2: cross-domain [reduce] branch bodies extracted as pure
 * helper functions. Each branch touches MULTIPLE [StoreState] slices and
 * is lifted VERBATIM as ONE helper with a SINGLE `state.copy(...)` commit
 * at the end — NEVER split into per-slice helpers (lost-update / double-
 * write bug, rev-grok R1 catch). Same package as [AppAction] /
 * [StoreState] — zero-import dispatch from [reduce].
 */

internal fun reduceDraftSessionMaterialized(state: StoreState, action: AppAction.DraftSessionMaterialized): StoreState = state.copy(
    sessionList = state.sessionList.copy(
        sessions = upsertSession(state.sessionList.sessions, action.session),
        // §Q4-strict-sync: a freshly-created session is NOT yet in the
        // server's authoritative listing. Track its id as pending-create
        // so the next mergeRefreshedSessionsPreservingLocalActivity keeps
        // it alive until a REST refresh or SSE session.created confirms it
        // (or the 30 s sweep drops it). Added atomically in the SAME
        // dispatch as the session upsert (no torn intermediate).
        // §B4: no open-tabs-list — list-detail has no tab strip.
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

internal fun reduceSessionArchived(state: StoreState, action: AppAction.SessionArchived): StoreState {
    // Apply archive eviction unconditionally (upsert archived session).
    // Apply the chat CONTENT clear IFF the archived session IS the current
    // one — derived from the snapshot, not carried on the action.
    // §B4: no open-tabs-list rewrite; route pop is the caller's job.
    val isCurrent = state.chat.currentSessionId == action.session.id
    val newSessionList = state.sessionList.applyArchiveEviction(action.session).first
    // §Wave5b-Q13 blocker-2 fix: SCROLL-STATE cleanup runs UNCONDITIONALLY
    // for the archived subtree (chat content remains current-only). For
    // the current-archived case applyArchivedChatClear already wipes the
    // slot, so cleanScrollStateForSubtree is a no-op; for non-current
    // archived ids it cleans a stale pendingScrollRequest (target in
    // subtree) without touching messages / parts / currentSessionId.
    // §chat-list-detail §11 / G6 (B5): checkpoint map is gone (per-entry
    // SavedStateHandle); this helper now only sweeps pendingScrollRequest.
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
    // above already wiped the slot; substantive for non-current archived
    // ids that had a stale pendingScrollRequest targeting them). The
    // checkpoint map is gone (B5 §11); this helper only sweeps
    // pendingScrollRequest.
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
    // §P0-A rev-gpt #7 (SSE archive subtree-prune gap): route the authority
    // prune through reduceAuthority — the SOLE writer of sessionStatuses (the
    // projection comes from authority, not a direct write). Pre-fix the SSE
    // archive removed the session from the list via applyArchiveEviction but
    // left its entry in authority.bySid → the archived session stayed Busy in
    // the aggregator's derived view forever. Prune the WHOLE subtree from
    // authority in the SAME state.copy (single CAS), then layer the non-
    // authority sessionList fields on top WITHOUT touching sessionStatuses.
    // Mirrors reduceSessionArchivedLocal's subtree-prune pattern exactly.
    // §P0-A r2: derive the real authority scope from StoreState (host profile
    // serverGroupFp + liveEndpointFp), not empty ScopeKey("","") — the prune
    // boundary must reflect the active connection identity.
    val withAuth = reduceAuthority(
        state,
        cn.vectory.ocdroid.data.state.AuthorityOp.PruneSessions(
            subtree,
            state.resolveScopeKey(),
        ),
    )
    return withAuth.copy(
        sessionList = withAuth.sessionList.copy(
            sessions = newSessionList.sessions,
            directorySessions = newSessionList.directorySessions,
            pendingQuestions = cleanedQuestions,
            activeSessionIds = withAuth.sessionList.activeSessionIds - subtree,
            sessionErrorsById = cleanedSessionErrors,
            abortPendingSessionIds = withAuth.sessionList.abortPendingSessionIds.filterKeys { it !in subtree },
        ),
        chat = newChatCleaned.copy(
            // §P0-E scaffolding hygiene: clear pending-error maps for the archived
            // subtree (mirrors the local/SSE archive + delete cleanup). The
            // reducer already computed subtree for the unread / pendingQuestions
            // cleanup above — reuse it here so the archive does not leave stale
            // pending entries (review gap fix).
            pendingErrorReattach = newChatCleaned.pendingErrorReattach.filterKeys { it !in subtree },
            pendingErrorCheck = newChatCleaned.pendingErrorCheck - subtree,
        ),
        unread = newUnread,
    )
}

internal fun reduceHostStatePurged(state: StoreState, action: AppAction.HostStatePurged): StoreState {
    // §P0-A rev-gpt #8 (sole writer): for cross-group purge, route the authority
    // reset through reduceAuthority (PurgeHost) — it is the SOLE writer of
    // sessionStatuses (the projection comes from authority). For same-group,
    // authority + sessionStatuses are preserved (no change). baseState carries
    // the correct authority + projection for the branch below.
    val baseState = if (!action.preserveServerGroupData) {
        reduceAuthority(state, cn.vectory.ocdroid.data.state.AuthorityOp.PurgeHost(
            scopeKey = cn.vectory.ocdroid.data.state.ScopeKey("", ""),
            preserveServerGroup = false,
        ))
    } else {
        state
    }
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
            baseState.chat.clearSessionData(),
            baseState.sessionList.copy(
                sessions = emptyList(),
                directorySessions = emptyMap(),
                // §P0-A rev-gpt #8: sessionStatuses NOT set here — comes from
                // baseState (reduceAuthority's projection over the purged bySid).
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
                // §P0-F 阻断6: cross-group purge must drop in-flight abort-pending
                // flags — they reference the prior host's sessions; a root-id
                // collision would let a stale "stopping" lock / watchdog survive.
                abortPendingSessionIds = emptyMap(),
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
            // Same-group host changes invalidate route-owned LoadedContent,
            // but the legacy bare-chat window is deliberately retained.  The
            // pre-B2 contract only cleared the streaming overlay here; using
            // clearLoadedChatPayload also erased the valid flat window.
            state.chat.copy(
                content = null,
                streamingPartTexts = emptyMap(),
                streamOwned = emptyMap(),
                streamingReasoningPart = null,
                pendingAgent = null,
                pendingModel = null,
                // §Wave5b-Q13: a host/profile transition invalidates any
                // pending scroll intent even within the same server group.
                // The scroll slot references a session the user is navigating
                // away from (or that may be re-laid-out differently on the
                // new profile). Mirrors the cross-group branch's
                // clearSessionData() reset.
                // §chat-list-detail §11 / G6 (B5): the legacy per-child
                // checkpoint map clear is GONE — checkpoints now live on
                // per-route-entry SavedStateHandle, so a host purge cannot
                // leave a stale checkpoint in ChatState.
                pendingScrollRequest = null,
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
    return baseState.copy(
        chat = newChat,
        sessionList = newSessionList,
        unread = newUnread,
        // §P0-A rev-gpt #8: authority is handled by baseState (reduceAuthority
        // for cross-group, unchanged for same-group) — NOT set here. The
        // sessionStatuses projection comes from baseState's authority (the
        // SOLE writer is reduceAuthority).
        // Per-profile UX — ALWAYS reset regardless of group.
        composer = state.composer.copy(draftWorkdir = null),
        settings = state.settings.copy(availableCommands = emptyList()),
        connection = state.connection.copy(serverVersion = null),
        // §breathing-indicator (purge-clear defensive): a host purge (cross OR
        // same group) invalidates the SSE transport for the prior host — the
        // breath flag MUST NOT survive as stale-true for a host that no longer
        // exists. Clears to false AND advances [StoreState.sseConnectedGeneration]
        // (`state.sseConnectedGeneration + 1L`, computed from THIS dispatch's
        // CAS-loop snapshot — atomic, see [SharedStateStore.dispatch]'s
        // `state.update { reduce(it, action) }`). A stale collector carrying the
        // pre-purge generation then loses the monotonic CAS
        // ([SharedStateStore.mutateSseConnected]: `prePurgeGen < advanced`) and
        // cannot resurrect `true`. The owner's normal teardown
        // ([cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwner.disconnect] /
        // cancelForShutdown) stays the primary clear path; this is the
        // defensive backstop for a purge that does NOT go through owner-disconnect.
        // INDEPENDENT of [cn.vectory.ocdroid.ui.ConnectionState.isConnected]
        // (health-settle) — this only clears the transport-up breath flag.
        isSseConnected = false,
        sseConnectedGeneration = state.sseConnectedGeneration + 1L,
    )
}

internal fun reduceBulkSessionsRefreshed(state: StoreState, action: AppAction.BulkSessionsRefreshed): StoreState {
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
        // §B4: no open-tabs-list field on BulkSessionsRefreshed.
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
        // T1c gap fix: bulk refresh (incl. archive-sync early-return path)
        // is a completed initial load — set the flag atomically so the
        // separate mutateSessionList patch at SessionListActions is gone.
        hasCompletedInitialLoad = true,
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
    // (FIX-B / §Wave5b-Q13). The checkpoint map is gone (B5 §11).
    val newChat = if (isCurrentArchived) {
        state.chat.applyArchivedChatClear().first
    } else {
        state.chat
    }
    // §Wave5b-Q13 blocker-2: UNCONDITIONAL scroll-state cleanup for the
    // archived subtree union. For current-archived the prior
    // applyArchivedChatClear already wiped the slot (no-op here); for
    // NON-current archived ids this drops a stale pendingScrollRequest
    // (target in subtree) without touching chat content. The checkpoint
    // map is gone (B5 §11).
    val newChatCleaned = newChat.cleanScrollStateForSubtree(allArchivedSubtree)
    // §P0-A rev-gpt #7 (bulk-refresh archive subtree-prune gap): route the
    // authority prune through reduceAuthority — the SOLE writer of
    // sessionStatuses (the projection comes from authority, not a direct
    // write). Pre-fix the bulk refresh wrote the merged list + pruned
    // unread/questions for the archived subtree but left the archived ids'
    // entries in authority.bySid → archived sessions stayed Busy in the
    // aggregator's derived view. Prune the WHOLE archived subtree union from
    // authority in the SAME state.copy (single CAS), then layer the non-
    // authority sessionList fields on top WITHOUT touching sessionStatuses.
    // Mirrors reduceSessionArchivedLocal's subtree-prune pattern exactly.
    // §P0-A r2: derive the real authority scope from StoreState (host profile
    // serverGroupFp + liveEndpointFp), not empty ScopeKey("","") — the prune
    // boundary must reflect the active connection identity.
    val withAuth = reduceAuthority(
        state,
        cn.vectory.ocdroid.data.state.AuthorityOp.PruneSessions(
            allArchivedSubtree,
            state.resolveScopeKey(),
        ),
    )
    return withAuth.copy(
        sessionList = withAuth.sessionList.copy(
            sessions = newSessionList.sessions,
            hasMoreSessions = newSessionList.hasMoreSessions,
            isLoadingMoreSessions = newSessionList.isLoadingMoreSessions,
            isRefreshingSessions = newSessionList.isRefreshingSessions,
            pendingCreateIds = newSessionList.pendingCreateIds,
            pendingCreatedAt = newSessionList.pendingCreatedAt,
            completeRootIds = newSessionList.completeRootIds,
            completenessEpoch = newSessionList.completenessEpoch,
            activeSessionIds = newSessionList.activeSessionIds,
            hasCompletedInitialLoad = newSessionList.hasCompletedInitialLoad,
            pendingQuestions = cleanedQuestions,
        ),
        chat = newChatCleaned.copy(
            // §P0-E scaffolding hygiene: clear pending-error maps for the bulk-
            // archived subtree (mirrors the local/SSE archive + delete cleanup).
            // The reducer already computed allArchivedSubtree for the unread /
            // pendingQuestions cleanup above — reuse it here so a REST bulk
            // archive does not leave stale pending entries (review gap fix).
            pendingErrorReattach = newChatCleaned.pendingErrorReattach.filterKeys { it !in allArchivedSubtree },
            pendingErrorCheck = newChatCleaned.pendingErrorCheck - allArchivedSubtree,
        ),
        unread = newUnread,
    )
}

// ── §chat-list-detail §12 B0: atomic SelectConversation / CloseDetail /
//    DetailMissing reducers (scaffolding — inert until B0.5/B1 wire the
//    dispatch sites). Stage the §7.2 route-instance token on
//    [StoreState.chatRouteInstance] (the freshness CAS counter that mirrors
//    [StoreState.sseConnectedGeneration]'s STRICTLY MONOTONIC pattern — never
//    resets, never regresses). The LoadedContent slot + render switch land in
//    B0.5/B2; these reducers only stage the token so the CAS is ready when
//    the content layer arrives. PURE ADDITIVE — no existing flow dispatches
//    these actions.

/**
 * §chat-list-detail §6 D10 / §12 B0: atomic select. Stamps the new route
 * incarnation onto [StoreState.chatRouteInstance] via [maxOf] — the reducer
 * is the single structural seam that enforces monotonicity (§7.2 "不可复用
 * token"): a stale/out-of-order `SelectConversation(routeInstance=N_old)`
 * dispatched when the live counter has already advanced past it is a
 * no-op (`maxOf(current, N_old) == current`); the current action is
 * idempotent (`maxOf(N, N) == N`). Neither regresses the counter.
 *
 * A content load (B0.5+) captures the live instance at request time and
 * stamps [LoadedContent.routeInstance] with it; the render gate (§7.1)
 * accepts the content IFF `content.routeInstance == chatRouteInstance`
 * (and `content.sessionId == routeId`). An older incarnation's late-
 * arriving load (the §7.2 A→B→A race) loses the CAS and is dropped.
 *
 * B0: inert — no dispatch site, no [LoadedContent] slot yet. The sessionId
 * is carried for the B0.5 content-slot wiring (then-current-id derivation
 * + content ownership); here it is accepted and not yet applied so the
 * action shape is frozen for the B0.5/B1 caller swap.
 */
internal fun reduceSelectConversation(state: StoreState, action: AppAction.SelectConversation): StoreState = state.copy(
    chatRouteInstance = maxOf(state.chatRouteInstance, action.routeInstance),
)

/**
 * §chat-list-detail §6 D10 / §12 B0: close the detail pane. ADVANCES the
 * incarnation (`+1L`) rather than resetting to 0 — the counter is STRICTLY
 * MONOTONIC across close→reopen (mirrors [StoreState.sseConnectedGeneration],
 * which never resets). The immediate post-close invalidation is identical
 * to a reset: a late load carrying the prior token N finds
 * `chatRouteInstance == N + 1`, fails the CAS, and is dropped. But unlike
 * a reset, the next open gets N + 2 (never reuses N) — closing the
 * "close→reopen-of-same-session reuses a token" hole a 0L reset would
 * open (§7.2 P6 "同 session 旧 incarnation 不可覆盖新内容").
 *
 * B0: inert.
 */
internal fun reduceCloseDetail(state: StoreState, action: AppAction.CloseDetail): StoreState = state.copy(
    chatRouteInstance = state.chatRouteInstance + 1L,
    // §chat-list-detail §10 B0.5-rework: full clear of the LoadedContent slot
    // AND the flat mirror (the close path must not leave stale data in EITHER
    // authority). The flat clear mirrors clearLoadedChatPayload so both paths
    // (close + navigate) use the same reset shape. The old bare-chat render
    // (currentSessionId != null) also gets a clean slate.
    chat = state.chat.clearLoadedChatPayload(),
)

/**
 * §chat-list-detail §5 P4 / §12 B0: the requested session is gone (deleted
 * / archived / never existed / ill-formed). Stamps the incarnation via
 * [maxOf] — same monotonicity guarantee as [reduceSelectConversation]: a
 * stale `DetailMissing(routeInstance=N_old)` cannot regress the counter.
 * Any in-flight load for the missing session carrying a prior token is
 * dropped by the freshness CAS; B2's render gate then shows the Missing
 * placeholder (routeId has no resolvable session →
 * `ChatDetailState.Missing(routeId)`).
 *
 * B0: inert.
 */
internal fun reduceDetailMissing(state: StoreState, action: AppAction.DetailMissing): StoreState {
    // A missing result is destructive only for the incarnation that requested
    // it.  In particular, a late `chat/A` missing result must not clear the
    // content already selected for B (or an A→B→A newer incarnation).
    val routeMatchesActiveDetail = state.chat.currentSessionId == action.sessionId ||
        state.chat.content?.sessionId == action.sessionId
    // B0's reducer contract also permits a newer token to stamp an otherwise
    // identity-less snapshot (the pure reducer setup has no current session or
    // loaded content yet). Once an active route identity exists, however, a
    // mismatched result must be ignored wholesale so it cannot invalidate the
    // active route's freshness CAS.
    val hasActiveDetailIdentity = state.chat.currentSessionId != null ||
        state.chat.content != null
    val accepted = action.routeInstance >= state.chatRouteInstance &&
        (!hasActiveDetailIdentity || routeMatchesActiveDetail)
    return state.copy(
        chatRouteInstance = if (accepted) {
            maxOf(state.chatRouteInstance, action.routeInstance)
        } else state.chatRouteInstance,
        chat = if (accepted) state.chat.clearLoadedChatPayload() else state.chat,
    )
}

/**
 * §chat-list-detail §7.1/§7.2 B0.5-rework: commit loaded content with the §7.2
 * freshness CAS. Accepts IFF BOTH [AppAction.ChatContentLoaded.expectedRouteInstance]
 * == the live [StoreState.chatRouteInstance] (the token hasn't advanced since
 * the load started) AND [AppAction.ChatContentLoaded.sessionId] ==
 * [ChatState.currentSessionId] (expected-id commit guard). A stale load (older
 * expectedRouteInstance OR session switched away) is SILENTLY DROPPED (P6).
 *
 * On accept, commits BOTH:
 *  - [LoadedContent] (the route-authoritative slot — sessionId welded to
 *    messages, P1 structural render authority)
 *  - the flat mirror (messages/partsByMessage/streaming/cursor/model — the
 *    same writes [reduceMessagesMerged] does, so the legacy bare-chat render
 *    path AND the chat/{id} path see the SAME data)
 * atomically in ONE reducer pass (no torn intermediate). The second condition
 * (sessionId match) is a COMMIT guard — rendering authority stays
 * `content.sessionId == routeId` (P1), NOT currentSessionId.
 */
internal fun reduceChatContentLoaded(state: StoreState, action: AppAction.ChatContentLoaded): StoreState {
    // §7.2 P6 freshness CAS + expected-id commit guard: stale load → drop.
    if (action.expectedRouteInstance != state.chatRouteInstance) return state
    if (action.sessionId != state.chat.currentSessionId) return state
    // §Stage-B §3.10 streamOwned computation (mirrors reduceMessagesMerged).
    val fetchedPartIds = action.partsByMessage.values.flatten().map { it.id }.toSet()
    val newStreamOwned = if (action.authoritative) {
        state.chat.streamOwned.filterKeys { it !in fetchedPartIds }
    } else {
        state.chat.streamOwned
    }
    val newStreamingPartTexts = if (action.authoritative) {
        action.streamingPartTexts.filterKeys { it !in fetchedPartIds }
    } else {
        action.streamingPartTexts
    }
    // Atomic dual commit: LoadedContent + flat mirror in ONE pass.
    return state.copy(
        chat = state.chat.copy(
            content = LoadedContent(
                sessionId = action.sessionId,
                messages = action.messages,
                partsByMessage = action.partsByMessage,
                streamingPartTexts = newStreamingPartTexts,
                streamOwned = newStreamOwned,
                streamingReasoningPart = action.streamingReasoningPart,
                olderMessagesCursor = action.olderMessagesCursor,
                hasMoreMessages = action.hasMoreMessages,
                currentModel = action.currentModel,
                routeInstance = action.expectedRouteInstance,
            ),
            // Flat mirror (same field set as reduceMessagesMerged).
            messages = action.messages.chronological(),
            partsByMessage = action.partsByMessage,
            streamingPartTexts = newStreamingPartTexts,
            streamOwned = newStreamOwned,
            streamingReasoningPart = action.streamingReasoningPart,
            olderMessagesCursor = action.olderMessagesCursor,
            hasMoreMessages = action.hasMoreMessages,
            currentModel = action.currentModel,
            isLoadingMessages = false,
            staleNotice = false,
        ),
    )
}

/**
 * §chat-list-detail §10 B0.5-rework: focused clear of the loaded-chat payload
 * (the fields that represent "a session's loaded content"). Used by
 * [reduceCloseDetail] AND by [reduceDetailMissing] (both route-scoped — the
 * legacy bare-chat path never dispatches these actions). Clears:
 *  - [ChatState.content] (the LoadedContent slot — P1 authority for chat/{id})
 *  - flat mirror: messages, partsByMessage, streamingPartTexts, streamOwned,
 *    streamingReasoningPart, cursor, hasMore, model, isLoadingMessages,
 *    isLoadingMoreMessages
 *  - coalesce buffers: deltaBuffer, fullTextBuffer, pendingFlushPartIds
 *
 * §B2 rev-gpt CRITICAL + MAJOR 2: the load-more flag + coalesce buffers MUST
 * be cleared here. An in-flight route-aware load-more's `finally` backstop is
 * token-guarded, so once CloseDetail/DetailMissing advanced the incarnation
 * the finally no-ops and isLoadingMoreMessages would stay `true` forever. And
 * a late `CoalesceFlushedForPart` (legacy token=0, accepted by
 * acceptsRouteUpdate) could resurrect streaming state for the closed route if
 * a new overlay for the same partId is established before the scheduled flush
 * fires — clearing the buffers leaves the late flush nothing to apply.
 *
 * Preserves: currentSessionId (the route-open sets it separately),
 * isCompacting/compactStartedAt/refreshNonce (chrome), revertCutoffs,
 * partExpandStates.
 */
internal fun ChatState.clearLoadedChatPayload(): ChatState = copy(
    content = null,
    messages = emptyList(),
    partsByMessage = emptyMap(),
    streamingPartTexts = emptyMap(),
    streamOwned = emptyMap(),
    streamingReasoningPart = null,
    olderMessagesCursor = null,
    hasMoreMessages = false,
    isLoadingMessages = false,
    isLoadingMoreMessages = false,
    currentModel = null,
    deltaBuffer = emptyMap(),
    fullTextBuffer = emptyMap(),
    pendingFlushPartIds = emptySet(),
)

/**
 * §P0-A r2: derive the REAL authority [ScopeKey] from [StoreState] alone
 * (pure — no identityStore dependency). Combines the current host profile's
 * [serverGroupFp] (normalized per [HostProfile.ensureServerGroupFp]) with
 * the live [endpointFp] maintained by [BundlePublished]. Mirrors the
 * derivation in [SharedStateStore.authorityScope] and
 * [StatusAggregatorImpl.currentScope] so prune operations
 * ([PruneSessions]) carry a scope that correctly represents the active
 * connection identity.
 *
 * When no host is active (cold start / no profile), both fields default
 * to "" — matching the identity fallback in the authoritative sites.
 */
internal fun StoreState.resolveScopeKey(): cn.vectory.ocdroid.data.state.ScopeKey {
    val currentProfile = host.hostProfiles.firstOrNull { it.id == host.currentHostProfileId }
    val serverGroupFp = if (currentProfile != null) {
        // Normalize blank to the profile id (mirrors HostProfile.ensureServerGroupFp()).
        if (currentProfile.serverGroupFp.isBlank()) currentProfile.id else currentProfile.serverGroupFp
    } else ""
    return cn.vectory.ocdroid.data.state.ScopeKey(
        serverGroupFp = serverGroupFp,
        endpointFp = liveEndpointFp,
    )
}

internal fun ChatState.syncLoadedContentFromFlat(
    routeInstance: Long,
    expectedRouteInstance: Long = 0L,
    expectedSessionId: String? = null,
): ChatState {
    // `0L` is the compatibility value for the bare-chat action surface. It is
    // deliberately NOT a route identity: legacy reducers may update the flat
    // projection, but they must not silently mutate a route-owned slot.
    if (expectedRouteInstance == 0L) return this
    val loaded = content ?: return this
    if (expectedRouteInstance != routeInstance) return this
    if (expectedSessionId != null && expectedSessionId != loaded.sessionId) return this
    if (loaded.routeInstance != routeInstance || loaded.sessionId != currentSessionId) return this
    return copy(
        content = loaded.copy(
            messages = messages,
            partsByMessage = partsByMessage,
            streamingPartTexts = streamingPartTexts,
            streamOwned = streamOwned,
            streamingReasoningPart = streamingReasoningPart,
            olderMessagesCursor = olderMessagesCursor,
            hasMoreMessages = hasMoreMessages,
            currentModel = currentModel,
        ),
    )
}

/**
 * Mirror a route-aware flat-field reducer into the already-owned route slot.
 * A zero token is intentionally a no-op for the route projection.
 */
internal fun StoreState.withRouteContentSynced(
    expectedRouteInstance: Long = 0L,
    expectedSessionId: String? = null,
): StoreState = copy(
    chat = chat.syncLoadedContentFromFlat(
        routeInstance = chatRouteInstance,
        expectedRouteInstance = expectedRouteInstance,
        expectedSessionId = expectedSessionId,
    ),
)

/**
 * Acceptance guard shared by asynchronous route-owned reducer branches.
 * Legacy actions intentionally pass `0L` and are accepted against the flat
 * compatibility surface; a non-zero token must match the live incarnation and
 * any supplied session owner must still be selected.
 */
internal fun StoreState.acceptsRouteUpdate(
    expectedRouteInstance: Long,
    sessionId: String? = null,
): Boolean {
    // Legacy (token=0) actions target the flat compatibility surface ONLY —
    // withRouteContentSynced is a no-op for token=0 (syncLoadedContentFromFlat
    // early-returns), so a legacy write can never mutate a route-owned slot.
    // They MUST be accepted unconditionally to honour the pre-B2 flat-maps
    // contract: the token-stream coordinator (and other legacy callers)
    // dispatch session-id-bearing frames without first selecting the session
    // in the chat slice (the unit-test engine surface AND the production
    // bare-chat path). Cross-session / stale-incarnation protection is the
    // responsibility of the route-owned path below (token≠0).
    if (expectedRouteInstance == 0L) return true
    if (sessionId != null && chat.currentSessionId != sessionId) return false
    return expectedRouteInstance == chatRouteInstance
}
