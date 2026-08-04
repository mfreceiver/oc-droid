package cn.vectory.ocdroid.ui

/**
 * §R-17 batch3d: Domain orchestration free functions. These are NOT the deleted
 * batch-2 AppState mirror helpers (aggregateFromSlices/syncSlicesFromAppState etc.).
 * They are coroutine-launch helpers called by the domain ViewModels and AppCore
 * orchestration extensions to perform async operations (load/refresh/mutate).
 * Future cleanup (batch3e+): may be inlined into individual VM private methods.
 */

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.repository.InteractionRepository
import cn.vectory.ocdroid.data.repository.SessionRepository
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.ui.controller.cleanScrollStateForSubtree
import cn.vectory.ocdroid.ui.controller.removeSessions
import cn.vectory.ocdroid.ui.controller.subtreeIds
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun launchCreateSession(
    scope: CoroutineScope,
    repository: SessionRepository,
    slices: SliceFlows,
    title: String?,
    onSelectSession: (String) -> Unit,
    emit: EventEmitter = EventEmitter { },
    directory: String? = null   // §R18 Final 终审 fix (gpter): route POST /session to the right workdir
) {

    scope.launch {
        repository.createSession(title, directory)
            .onSuccess { session ->
                val registeredAt = System.currentTimeMillis()
                // §Q4-strict-sync: track the freshly-created session's id as
                // pending-create so the next REST refresh does not evict it
                // before the server's listing propagates. Removed atomically
                // by the refresh's sweep or by SSE session.created.
                // T1c: SessionCreatedLocal owns sessions + pendingCreateIds +
                // pendingCreatedAt in ONE dispatch.
                slices.store.dispatch(AppAction.SessionCreatedLocal(session, registeredAt))
                onSelectSession(session.id)
            }
            .onFailure { error ->
                emit.emit(UiEvent.Error(R.string.error_create_session_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
            }
    }
}

internal fun launchForkSession(
    scope: CoroutineScope,
    repository: InteractionRepository,
    slices: SliceFlows,
    sessionId: String,
    messageId: String?,
    onSelectSession: (String) -> Unit,
    emit: EventEmitter = EventEmitter { }
) {

    scope.launch {
        repository.forkSession(sessionId, messageId)
            .onSuccess { session ->
                // T1c: SessionUpserted owns sessions-only upsert.
                slices.store.dispatch(AppAction.SessionUpserted(session))
                onSelectSession(session.id)
            }
            .onFailure { error ->
                emit.emit(UiEvent.Error(R.string.error_fork_session_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
            }
    }
}

/**
 * T4 (chat-ux-batch): rename a single session. Mirrors [launchCreateSession] /
 * [launchForkSession] (simple single-session mutations) — calls
 * [OpenCodeRepository.updateSession] with the new title and upserts the
 * server-returned [Session] so the slice's displayName reflects immediately.
 *
 * Empty [title] is forwarded as-is: the server clears the session's title,
 * which makes [Session.displayName] fall back to the project folder name
 * (see [Session.displayName] getter). No subtree walk — only the renamed
 * leaf is upserted.
 */
internal fun launchRenameSession(
    scope: CoroutineScope,
    repository: SessionRepository,
    slices: SliceFlows,
    sessionId: String,
    title: String,
    emit: EventEmitter = EventEmitter { }
) {

    scope.launch {
        repository.updateSession(sessionId, title)
            .onSuccess { updated ->
                // T1c: SessionUpserted owns sessions-only upsert.
                slices.store.dispatch(AppAction.SessionUpserted(updated))
            }
            .onFailure { error ->
                emit.emit(UiEvent.Error(R.string.error_rename_session_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
            }
    }
}

internal fun launchSetSessionArchived(
    scope: CoroutineScope,
    repository: SessionRepository,
    slices: SliceFlows,
    settingsManager: SettingsManager,
    sessionId: String,
    archived: Boolean,
    emit: EventEmitter = EventEmitter { },
    /**
     * R-20 Phase 1 (C3): provider for the current host's serverGroupFp. Used
     * to key the [ControllerEffect.EvictSession] emission per archived subtree
     * id (plan §3 矩阵 "用户归档" 行). Null = caller has not been migrated yet;
     * no eviction emits (preserves the legacy behavior for unmigrated callers).
     */
    currentProfileId: (() -> String)? = null,
    /**
     * R-20 Phase 1 (C3): sink for the [ControllerEffect.EvictSession] emissions.
     * Typically the VM's `effectBus.tryEmitEffect` closure. Null = caller has
     * not been migrated yet; no eviction emits.
     */
    emitEffect: ((ControllerEffect) -> Unit)? = null,
) {

    scope.launch {
        val archivedValue = if (archived) System.currentTimeMillis() else -1L
        // §R-17 batch2 step e final: slice-only reads (slices are the sole
        // authoritative store).
        // §task5-lifecycle: three-source subtree so descendants that only
        // live in directorySessions / childSessions are still visited.
        val sl = slices.sessionList.value
        val subtree = subtreeIds(sessionId, sl.sessions, sl.directorySessions, sl.childSessions)
        val ids = subtree.toList()
        for (id in ids) {
            repository.updateSessionArchived(id, archivedValue)
                .onSuccess { updated ->
                    // §R-17 batch2 step e final: fresh capture after the suspend;
                    // used for all reads in this synchronous onSuccess block.
                    // T1c: map-replace of sessions/dirSessions/childSessions is
                    // owned by SessionArchivedLocal reduce (by session.id).
                    // §B4: open-tabs-list removed; route id is the sole detail
                    // identity for clear/pop decisions.
                    val currentCurrentId = slices.chat.value.currentSessionId
                    val routeId = routeChatSessionId(slices.store.stateFlow.value.nav.lastRoute)
                    val isArchive = archivedValue > 0
                    // §10 REST archive: clear chat IFF archived id is current
                    // pointer OR active chat/{id} route.
                    val clearCurrent = isArchive && (currentCurrentId == id || routeId == id)

                    // §task5-lifecycle: per-id question filter (presentation domain).
                    val cleanedQuestions = if (isArchive) {
                        slices.sessionList.value.pendingQuestions.filter { q -> q.sessionId !in subtree }
                    } else {
                        slices.sessionList.value.pendingQuestions
                    }
                    val activeIdsToRemove = if (isArchive) subtree else setOf(id)
                    // T1c: SessionArchivedLocal owns the sessionList copy.
                    // Cross-slice mutateUnread / mutateChat / ChatCleared stay below.
                    slices.store.dispatch(
                        AppAction.SessionArchivedLocal(
                            session = updated,
                            pendingQuestions = cleanedQuestions,
                            activeSessionIdsToRemove = activeIdsToRemove,
                        )
                    )
                    if (isArchive) {
                        // §task5-lifecycle: archive clears the full known subtree.
                        slices.mutateUnread { it.removeSessions(subtree) }
                        // §Wave5b-Q13 blocker-2: UNCONDITIONAL scroll-state
                        // cleanup for the archived subtree. Drops a stale
                        // pendingScrollRequest (target in subtree) WITHOUT
                        // touching chat content. The current-archive chat-
                        // content clear below uses mutateChat which only
                        // wipes currentSessionId/messages/partsByMessage —
                        // cleanScrollStateForSubtree is the SOLE path that
                        // catches scroll-state leakage for NON-current
                        // archived ids (which the clearCurrent branch skips).
                        // §chat-list-detail §11 / G6 (B5): the checkpoint map
                        // is gone (per-entry SavedStateHandle); this helper
                        // now only sweeps pendingScrollRequest.
                        slices.mutateChat { it.cleanScrollStateForSubtree(subtree) }
                    }
                    if (clearCurrent) {
                        // §B4 / §10 REST archive current: ChatCleared +
                        // CloseDetail + popToSessions. Route id (or residual
                        // current) matches archived id.
                        slices.store.dispatch(AppAction.ChatCleared)
                        slices.store.dispatch(AppAction.CloseDetail)
                        settingsManager.currentSessionId = null
                        settingsManager.lastRoute = NavRoute.Sessions.route
                        slices.store.mutateNav {
                            it.copy(
                                lastRoute = NavRoute.Sessions.route,
                                navEpoch = it.navEpoch + 1L,
                            )
                        }
                    }
                    // R-20 Phase 1 (C3, plan §3 矩阵 "用户归档" 行): emit
                    // EvictSession for each successfully-archived subtree id
                    // AFTER the REST call confirmed. The eviction is gated on
                    // isArchive — restoring a session (archived=false) must
                    // NOT evict its cache (the user wants to see it again).
                    // Both memory LRU + persistent cache are cleared by
                    // AppCore.dispatchHostEffect's EvictSession handler.
                    // emit happens inside onSuccess to avoid optimistic
                    // eviction on a failed archive.
                    if (isArchive && currentProfileId != null && emitEffect != null) {
                        emitEffect(ControllerEffect.EvictSession(currentProfileId(), id))
                    }
                }
                .onFailure { error ->
                    emit.emit(UiEvent.Error(
                        if (archived) R.string.error_archive_session_failed
                        else R.string.error_restore_session_failed,
                        listOf(errorMessageOrFallback(error, "unknown error")),
                    ))
                    return@launch
                }
        }
    }
}

internal fun launchDeleteSession(
    scope: CoroutineScope,
    repository: SessionRepository,
    slices: SliceFlows,
    settingsManager: SettingsManager,
    sessionId: String,
    onSelectSession: (String) -> Unit,
    emit: EventEmitter = EventEmitter { },
    /**
     * R-20 Phase 1 (C3): provider for the current host's serverGroupFp. Used
     * to key the [ControllerEffect.EvictSession] emission on delete (plan §3
     * 矩阵 "用户删除" 行). Null = caller has not been migrated yet.
     */
    currentProfileId: (() -> String)? = null,
    /**
     * R-20 Phase 1 (C3): sink for the [ControllerEffect.EvictSession] emission.
     * Null = caller has not been migrated yet.
     */
    emitEffect: ((ControllerEffect) -> Unit)? = null,
) {

    scope.launch {
        // §task5-lifecycle §delete-subtree: snapshot the full three-source
        // subtree BEFORE the REST delete (server may cascade-delete descendants).
        val slSnap = slices.sessionList.value
        val removedIds = subtreeIds(sessionId, slSnap.sessions, slSnap.directorySessions, slSnap.childSessions)
        repository.deleteSession(sessionId)
            .onSuccess {
                // Purge the deleted subtree from both the global sessions list AND
                // directorySessions. If the session was originally surfaced via
                // a connected workdir (createSessionInWorkdir's directory fetch),
                // leaving it in directorySessions would let SessionsScreen's
                // union render it — and re-selecting it would upsert a ghost
                // copy of an already-deleted server session (#10).
                // §R-17 batch2 step e final: slice-only reads.
                // T1c: SessionDeletedLocal owns the 5-field sessionList purge
                // (sessions / directorySessions / pendingQuestions /
                // activeSessionIds / sessionErrorsById) derived from removedIds.
                // Cross-slice mutateUnread / ChatCleared stay below.
                slices.store.dispatch(AppAction.SessionDeletedLocal(removedIds))
                // §task5-lifecycle: unread drop for the whole removed subtree.
                slices.mutateUnread { it.removeSessions(removedIds) }
                // §B4 / §10 delete: if deleted id (== route id or residual
                // current) is the active detail → popToSessions + ChatCleared.
                // Non-current: sessionList purge alone (already dispatched).
                // No remainingOpenIds / onSelectSession sibling switch.
                val currentId = slices.chat.value.currentSessionId
                val routeId = routeChatSessionId(slices.store.stateFlow.value.nav.lastRoute)
                val deletingActiveDetail =
                    (currentId != null && currentId in removedIds) ||
                        (routeId != null && routeId in removedIds)
                if (deletingActiveDetail) {
                    slices.store.dispatch(AppAction.ChatCleared)
                    slices.store.dispatch(AppAction.CloseDetail)
                    settingsManager.currentSessionId = null
                    settingsManager.lastRoute = NavRoute.Sessions.route
                    slices.store.mutateNav {
                        it.copy(
                            lastRoute = NavRoute.Sessions.route,
                            navEpoch = it.navEpoch + 1L,
                        )
                    }
                }
                // §B4: onSelectSession retained in signature for call-site
                // stability but is no longer invoked on delete-current (no
                // sibling-tab switch; route pops to Sessions instead).
                // R-20 Phase 1 (C3, plan §3 矩阵 "用户删除" 行): emit EvictSession
                // AFTER the REST delete confirmed. The eviction (memory LRU +
                // persistent cache) is routed through AppCore.dispatchHostEffect.
                // Emits inside onSuccess to avoid optimistic eviction on a failed
                // delete. Emitted for the user-requested id only; descendant
                // caches are evicted by their own delete cascade (or by the
                // server-side delete handlers).
                if (currentProfileId != null && emitEffect != null) {
                    emitEffect(ControllerEffect.EvictSession(currentProfileId(), sessionId))
                }
            }
            .onFailure { error ->
                emit.emit(UiEvent.Error(R.string.error_delete_session_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
            }
    }
}

internal fun launchSendMessage(
    scope: CoroutineScope,
    repository: InteractionRepository,
    slices: SliceFlows,
    sessionId: String,
    text: String,
    attachments: List<ComposerImageAttachment> = emptyList(),
    agent: String?,
    model: Message.ModelInfo?,
    onRefreshMessages: (String, Boolean) -> Unit,
    onSuccess: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null,
    emit: EventEmitter = EventEmitter { }
) {

    // §P0-C (B11): capture identity + epoch at function ENTRY, BEFORE the
    // suspend call (repository.sendMessage). The capture at entry ensures
    // we snapshot the identity under which the send was DECIDED, not after
    // a potential reconfigure during the POST round-trip.
    val identityAtDispatch = slices.store.currentIdentity()
    val identityEpochAtDispatch = slices.store.captureIdentityEpoch()

    scope.launch {
        // §streaming-state-sync-diag: send timing — POST prompt_async start.
        cn.vectory.ocdroid.util.DebugLog.i(
            "SendDiag",
            "POST prompt_async send sid=$sessionId textLen=${text.length}",
        )
        repository.sendMessage(sessionId, text, agent, model, attachments = attachments)
            .onSuccess {
                // §streaming-state-sync-diag: POST succeeded — record before
                // any branch (archived-skip / busy write) runs.
                cn.vectory.ocdroid.util.DebugLog.i("SendDiag", "POST onSuccess sid=$sessionId")
                // gro-2 Blocker 2a: if the session was archived mid-send (e.g.
                // cross-device archive during the prompt_async window), do NOT
                // resurrect it as ghost-busy. Check whether the session is
                // EXPLICITLY archived in the list — bail ONLY if it is present
                // AND archived. If it is absent (not yet loaded / cold-start
                // window), be lenient and proceed (the session was valid at
                // dispatch time; absence ≠ archived). This correctly handles:
                //  (a) archived mid-flight → present + isArchived → skip;
                //  (b) user merely switched away to a non-archived session →
                //      present + !isArchived → proceed (bump the sent-to
                //      session). Do NOT gate purely on
                //      sessionId == currentSessionId (that breaks the legit
                //      switch-away case — the sent-to session deserves its
                //      bump regardless of which session is currently open).
                val sessionInList = slices.sessionList.value.sessions.firstOrNull { it.id == sessionId }
                if (sessionInList != null && sessionInList.isArchived) {
                    DebugLog.i("Sync", "launchSendMessage: session $sessionId archived at success time → skipping bump/refresh (no ghost-busy)")
                    // Do NOT call onComplete here — the outer onComplete?.invoke()
                    // below (after .onFailure) ALWAYS runs (it is the "finally"
                    // equivalent). Just bail out of onSuccess.
                    return@onSuccess
                }
                // §P0-C (B11): isCurrent guard — if the host switched during the
                // POST round-trip, the identity captured at function entry no longer
                // matches. DROP the stale optimistic write: a later new-host POST
                // will stamp its own ApplyEvent under the new identity. Do NOT
                // clobber the new host's state with a stale optimistic BUSY.
                if (identityAtDispatch != null && !slices.store.isCurrentIdentity(identityAtDispatch)) {
                    DebugLog.i(
                        "Sync",
                        "launchSendMessage: host switched during POST sid=$sessionId — " +
                            "dropping stale optimistic (identity epoch no longer current)",
                    )
                    return@onSuccess
                }
                // §append-safe (glmer MAJOR-1): inputText is cleared
                // synchronously at dispatch time, so do NOT touch it here —
                // wiping now would destroy a follow-up the user typed during
                // the in-flight prompt_async window (the core send-while-
                // running workflow).
                val updatedTimestamp = System.currentTimeMillis()
                val busyStatus = cn.vectory.ocdroid.data.model.SessionStatus(type = "busy")
                // §streaming-state-sync-diag (runtime-gated): record the optimistic
                // busy write so we can confirm whether it later gets overwritten
                // by a stale idle from session.status / digest / poller.
                if (cn.vectory.ocdroid.util.DebugLog.verboseDiagEnabled) {
                    cn.vectory.ocdroid.util.DebugLog.d(
                        "StatusDiag",
                        "optimistic-onSuccess busy write sid=$sessionId",
                    )
                }
                // §P0-A (B1): optimistic busy now funnels through the authority
                // reducer — ApplyEvent(OPTIMISTIC) sets the bySid entry and records
                // the bump timestamp in pendingBumps, which the reducer applies to
                // sessions (bumpSessionUpdated) and the sessionStatuses projection
                // in the SAME single CAS. The optimisticBumpTimestamp IS the
                // caller-captured wall-clock (System.currentTimeMillis above) —
                // the reducer stays pure (no clock read; value carried in the op).
                // §P0-C (B11): the ApplyEvent carries the CAPTURED identity +
                // epoch (not the current host's). The scopeKey is derived from
                // the captured identity's profileId + endpointFp, NOT from
                // authorityScope() (which reads the CURRENT host). When the
                // captured identity is null (cold start / no identity store),
                // fall back to current behavior (authorityScope(), null identity,
                // epoch 0L — lenient, backward compat).
                val scopeKey = if (identityAtDispatch != null) {
                    cn.vectory.ocdroid.data.state.scopeKeyOf(
                        identityAtDispatch.profileId, identityAtDispatch.endpointFp,
                    )
                } else {
                    slices.store.authorityScope()
                }
                slices.store.dispatch(
                    AppAction.AuthorityEvent(
                        cn.vectory.ocdroid.data.state.AuthorityOp.ApplyEvent(
                            sid = sessionId,
                            status = busyStatus,
                            origin = cn.vectory.ocdroid.data.state.EntryOrigin.OPTIMISTIC,
                            capturedIdentity = identityAtDispatch,
                            identityEpochAtCapture = identityEpochAtDispatch,
                            scopeKey = scopeKey,
                            connectionTimeMs = updatedTimestamp,
                            optimisticBumpTimestamp = updatedTimestamp,
                        ),
                    ),
                )
                onSuccess?.invoke()
                // §streaming-send-ux-fix: the post-send full-list refresh was
                // REMOVED — it was the root cause of the "no live UI feedback"
                // bug on normal sends (slash commands were unaffected because
                // they never fired it). Two coupled failure modes:
                //
                //  (1) STATUS half: the refresh fanned out to GET /session/status
                //      → launchLoadSessionStatus → mergeStatusSnapshot. That merge
                //      only preserves a local value that CHANGED while REST was
                //      in flight (localBefore[id] != after); it does NOT protect
                //      the pre-existing optimistic busy we just wrote above. So
                //      localBefore=busy, localAfter=busy, REST=idle → idle wins,
                //      clobbering the optimistic busy → thinking/running UI gone.
                //      SSE session.status{busy} is a one-shot transition event
                //      (not repeated) so it never repairs the clobber.
                //
                //  (2) MESSAGE half: the same fan-out started an IMMEDIATE
                //      messages GET that occupied the single-flight
                //      isLoadingMessages slot; the better-timed 400ms post-send
                //      reload (loadMessagesWithRetry) and the 400ms SSE-busy
                //      reload then got DISCARDED by launchLoadMessages'
                //      `if (isLoadingMessages) return` guard with no trailing-
                //      edge retry → stale transcript committed, new user
                //      message hidden until the user switches away and back.
                //
                // The fix matches the working slash path (AppCore.executeCommand),
                // which does no post-send full-list refresh and relies on SSE +
                // the targeted message reload below.
                //
                // KEEP the targeted message reload — it is the sole post-send
                // fallback: legacy first-paint + user-text-part hydration (the
                // user `message.part.updated` is intentionally ignored at
                // SessionSyncCoordinator.kt:1154-1168), plus slim fallback +
                // gap recovery. The 400ms-delayed sibling
                // (loadMessagesWithRetry) is no longer starved by the dropped
                // immediate load, so it now actually runs.
                onRefreshMessages(sessionId, true)
            }
            .onFailure { error ->
                // §streaming-state-sync-diag: POST failed.
                cn.vectory.ocdroid.util.DebugLog.w(
                    "SendDiag",
                    "POST onFailure sid=$sessionId err=${error.message}",
                )
                // §R-17 M3: read composer slice for the restore-decision; error
                // → UiEvent, inputText → composer slice.
                // Restore the failed prompt only if the user has not typed
                // something new since the synchronous dispatch clear.
                val currentInput = slices.composer.value.inputText
                val restored = if (currentInput.isBlank()) text else currentInput
                emit.emit(UiEvent.Error(R.string.error_send_message_failed, listOf(errorMessageOrFallback(error, "Failed to send message"))))
                slices.mutateComposer { it.copy(inputText = restored) }
            }
        // §streaming-state-sync-diag: POST completed (finally-equivalent) —
        // about to clear sendingSessionIds.
        cn.vectory.ocdroid.util.DebugLog.i(
            "SendDiag",
            "POST onComplete (clearing sendingSessionIds) sid=$sessionId",
        )
        onComplete?.invoke()
    }
}
