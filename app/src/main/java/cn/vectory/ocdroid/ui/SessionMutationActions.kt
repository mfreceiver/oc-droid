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
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
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
    repository: OpenCodeRepository,
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
                slices.mutateSessionList { sl ->
                    sl.copy(
                        sessions = upsertSession(sl.sessions, session),
                        pendingCreateIds = sl.pendingCreateIds + session.id,
                        pendingCreatedAt = sl.pendingCreatedAt + (session.id to registeredAt),
                    )
                }
                onSelectSession(session.id)
            }
            .onFailure { error ->
                emit.emit(UiEvent.Error(R.string.error_create_session_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
            }
    }
}

internal fun launchForkSession(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    sessionId: String,
    messageId: String?,
    onSelectSession: (String) -> Unit,
    emit: EventEmitter = EventEmitter { }
) {

    scope.launch {
        repository.forkSession(sessionId, messageId)
            .onSuccess { session ->
                slices.mutateSessionList { sl -> sl.copy(sessions = upsertSession(sl.sessions, session)) }
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
    repository: OpenCodeRepository,
    slices: SliceFlows,
    sessionId: String,
    title: String,
    emit: EventEmitter = EventEmitter { }
) {

    scope.launch {
        repository.updateSession(sessionId, title)
            .onSuccess { updated ->
                slices.mutateSessionList { sl -> sl.copy(sessions = upsertSession(sl.sessions, updated)) }
            }
            .onFailure { error ->
                emit.emit(UiEvent.Error(R.string.error_rename_session_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
            }
    }
}

internal fun launchSetSessionArchived(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
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
    currentServerGroupFp: (() -> String)? = null,
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
                    val currentSessions = slices.sessionList.value.sessions
                    val currentDirSessions = slices.sessionList.value.directorySessions
                    val currentOpenIds = slices.sessionList.value.openSessionIds
                    val currentCurrentId = slices.chat.value.currentSessionId

                    val newSessions = currentSessions.map { session -> if (session.id == id) updated else session }
                    // Keep directorySessions in sync so an archived session disappears
                    // from the connected-projects list immediately (refreshDirectorySessions
                    // repopulates this map on expand, but the local copy must not hold a
                    // stale unarchived version).
                    val newDirSessions = currentDirSessions.mapValues { (_, list) ->
                        list.map { session -> if (session.id == id) updated else session }
                    }
                    // §task5-ghost-r2 (final-fix round 2): same sync for childSessions.
                    // A descendant that lives only in childSessions (sub-agent surfaced
                    // via loadChildSessions) must also be replaced with the archived
                    // copy — otherwise the next reconcile's sessionsById snapshot still
                    // sees it as unarchived, defeating the filterArchivedSessionQuestions
                    // ancestor walk and letting its question ghost back.
                    val currentChildSessions = slices.sessionList.value.childSessions
                    val newChildSessions = currentChildSessions.mapValues { (_, list) ->
                        list.map { session -> if (session.id == id) updated else session }
                    }
                    val isArchive = archivedValue > 0
                    // Archived: evict the id from the open-tabs list (browser-tab close
                    // equivalent for archive) and persist via the existing SettingsManager
                    // setter. Mirrors closeSession's currentSessionId fallback: if the
                    // archived session was current, clear it (and the loaded message window)
                    // so the chat view falls back to the empty state instead of pointing
                    // at a now-archived session.
                    val newOpenIds = if (isArchive) currentOpenIds.filter { it != id } else currentOpenIds
                    if (isArchive && newOpenIds != currentOpenIds) {
                        settingsManager.openSessionIds = newOpenIds
                    }
                    val clearCurrent = isArchive && currentCurrentId == id
                    // §R18 Phase 2-F: chatFlow is the sole runtime source; the
                    // chat.update below clears currentSessionId, and the AppCore
                    // collector drops null (no manual SettingsManager write).

                    slices.mutateSessionList {
                        // §task5-lifecycle: per-id question filter (presentation domain).
                        val cleanedQuestions = if (isArchive) {
                            it.pendingQuestions.filter { q -> q.sessionId !in subtree }
                        } else {
                            it.pendingQuestions
                        }
                        val activeIdsToRemove = if (isArchive) subtree else setOf(id)
                        it.copy(
                            sessions = newSessions,
                            directorySessions = newDirSessions,
                            childSessions = newChildSessions,
                            openSessionIds = newOpenIds,
                            pendingQuestions = cleanedQuestions,
                            activeSessionIds = it.activeSessionIds - activeIdsToRemove,
                        )
                    }
                    if (isArchive) {
                        // §task5-lifecycle: archive clears the full known subtree.
                        slices.mutateUnread { it.removeSessions(subtree) }
                        // §Wave5b-Q13 blocker-2: UNCONDITIONAL scroll-state
                        // cleanup for the archived subtree. Drops a stale
                        // pendingScrollRequest (target in subtree) +
                        // parentReturnCheckpoints entries (key in subtree)
                        // WITHOUT touching chat content. The current-archive
                        // chat-content clear below uses mutateChat which only
                        // wipes currentSessionId/messages/partsByMessage —
                        // cleanScrollStateForSubtree is the SOLE path that
                        // catches scroll-state leakage for NON-current
                        // archived ids (which the clearCurrent branch skips).
                        slices.mutateChat { it.cleanScrollStateForSubtree(subtree) }
                    }
                    if (clearCurrent) {
                        // Cross-slice: currentSessionId/messages/partsByMessage are
                        // chat-slice fields; the rest above are sessionList.
                        //
                        // §Wave5b-Q13: applyArchivedChatClear-equivalent content
                        // clear for the CURRENT archived id. The
                        // cleanScrollStateForSubtree call above already wiped
                        // the slot + the currentId's checkpoint entry; this
                        // branch additionally wipes messages / partsByMessage /
                        // currentSessionId so the chat view falls back to the
                        // empty state. (Does NOT call applyArchivedChatClear
                        // directly to preserve the pre-existing field set —
                        // streaming overlays / cursor / model are reset by the
                        // next switchTo's clearSessionData, not here.)
                        slices.mutateChat {
                            it.copy(
                                currentSessionId = null,
                                messages = emptyList(),
                                partsByMessage = emptyMap()
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
                    if (isArchive && currentServerGroupFp != null && emitEffect != null) {
                        emitEffect(ControllerEffect.EvictSession(currentServerGroupFp(), id))
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
    repository: OpenCodeRepository,
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
    currentServerGroupFp: (() -> String)? = null,
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
                val currentSessions = slices.sessionList.value.sessions
                val currentDirSessions = slices.sessionList.value.directorySessions
                val newSessions = currentSessions.filter { s -> s.id !in removedIds }
                val newDirSessions = currentDirSessions
                    .mapValues { (_, list) -> list.filter { s -> s.id !in removedIds } }
                    .filterValues { it.isNotEmpty() }
                slices.mutateSessionList { sl ->
                    sl.copy(
                        sessions = newSessions,
                        directorySessions = newDirSessions,
                        // §task5-lifecycle: question filter for removed subtree.
                        pendingQuestions = sl.pendingQuestions.filter { it.sessionId !in removedIds },
                        activeSessionIds = sl.activeSessionIds - removedIds,
                    )
                }
                // §task5-lifecycle: unread drop for the whole removed subtree.
                slices.mutateUnread { it.removeSessions(removedIds) }
                val currentId = slices.chat.value.currentSessionId
                if (currentId != null && currentId in removedIds) {
                    val newCurrent = newSessions.firstOrNull()?.id
                    if (newCurrent != null) {
                        onSelectSession(newCurrent)
                    } else {
                        // #10: no remaining session — clear currentSessionId on
                        // the chat slice too, otherwise a stale id survives in
                        // the runtime state pointing at a deleted session.
                        // §R18 Phase 2-F: chatFlow is the sole runtime source;
                        // the AppCore collector drops null so no manual write.
                        slices.mutateChat { c -> c.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap()) }
                    }
                }
                // R-20 Phase 1 (C3, plan §3 矩阵 "用户删除" 行): emit EvictSession
                // AFTER the REST delete confirmed. The eviction (memory LRU +
                // persistent cache) is routed through AppCore.dispatchHostEffect.
                // Emits inside onSuccess to avoid optimistic eviction on a failed
                // delete. Emitted for the user-requested id only; descendant
                // caches are evicted by their own delete cascade (or by the
                // server-side delete handlers).
                if (currentServerGroupFp != null && emitEffect != null) {
                    emitEffect(ControllerEffect.EvictSession(currentServerGroupFp(), sessionId))
                }
            }
            .onFailure { error ->
                emit.emit(UiEvent.Error(R.string.error_delete_session_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
            }
    }
}

internal fun launchSendMessage(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    sessionId: String,
    text: String,
    attachments: List<ComposerImageAttachment> = emptyList(),
    agent: String?,
    model: Message.ModelInfo?,
    onRefreshMessages: (String, Boolean) -> Unit,
    onRefreshSessions: () -> Unit,
    onSuccess: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null,
    emit: EventEmitter = EventEmitter { }
) {

    scope.launch {
        repository.sendMessage(sessionId, text, agent, model, attachments = attachments)
            .onSuccess {
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
                // §R-17 batch2 step e final: slice-only reads.
                val currentSessions = slices.sessionList.value.sessions
                val currentStatuses = slices.sessionList.value.sessionStatuses
                // §append-safe (glmer MAJOR-1): inputText is cleared
                // synchronously at dispatch time, so do NOT touch it here —
                // wiping now would destroy a follow-up the user typed during
                // the in-flight prompt_async window (the core send-while-
                // running workflow).
                val newSessions = bumpSessionUpdated(currentSessions, sessionId, System.currentTimeMillis())
                val newStatuses = currentStatuses + (sessionId to cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"))
                slices.mutateSessionList { sl -> sl.copy(sessions = newSessions, sessionStatuses = newStatuses) }
                onSuccess?.invoke()
                onRefreshSessions()
                // §15.1 (review N6): the post-send 1200ms double-refresh is
                // gone — SSE will deliver `message.updated` (server 1.17.11+
                // emits message.updated, not message.created, for new messages;
                // see the insert-if-absent handler in MainViewModelSyncActions)
                // and a foreground catch-up covers any dropped event. The single
                // immediate reload here is the legacy first-paint path that
                // selectSession/sendMessage use to bypass the debounce.
                onRefreshMessages(sessionId, true)
            }
            .onFailure { error ->
                // §R-17 M3: read composer slice for the restore-decision; error
                // → UiEvent, inputText → composer slice.
                // Restore the failed prompt only if the user has not typed
                // something new since the synchronous dispatch clear.
                val currentInput = slices.composer.value.inputText
                val restored = if (currentInput.isBlank()) text else currentInput
                emit.emit(UiEvent.Error(R.string.error_send_message_failed, listOf(errorMessageOrFallback(error, "Failed to send message"))))
                slices.mutateComposer { it.copy(inputText = restored) }
            }
        onComplete?.invoke()
    }
}
