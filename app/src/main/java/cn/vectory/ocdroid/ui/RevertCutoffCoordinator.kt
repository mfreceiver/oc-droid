package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.RevertCutoff
import cn.vectory.ocdroid.data.model.RevertCutoffState
import cn.vectory.ocdroid.util.DebugLog
import java.util.concurrent.ConcurrentHashMap

/** Resolves a cutoff once; a failed resolution stays fail-closed until explicit retry. */
class RevertCutoffCoordinator(private val core: AppCore) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    suspend fun ensure(sessionId: String, messageId: String, retryFailed: Boolean = false) {
        val existing = core.store.chatFlow.value.revertCutoffs[sessionId]
        if (existing?.messageId == messageId) {
            when (existing.state) {
                is RevertCutoffState.Resolved -> return
                RevertCutoffState.Failed -> if (!retryFailed) return
                RevertCutoffState.PendingFetch,
                RevertCutoffState.NoTimestamp -> Unit
            }
        }
        if (!inFlight.add(sessionId)) return

        val operationProfileId = core.currentProfileId()
        var terminalState: RevertCutoffState? = null
        try {
            // Publish the fail-closed state before any suspend point.
            commitStateIfCurrent(sessionId, messageId, operationProfileId, RevertCutoffState.PendingFetch) ?: return
            val local = core.store.chatFlow.value.messages.firstOrNull { it.id == messageId }
            if (local != null) {
                terminalState = local.time?.created?.let(RevertCutoffState::Resolved)
                    ?: RevertCutoffState.NoTimestamp
            } else {
                var cursor = core.store.chatFlow.value.olderMessagesCursor
                repeat(MAX_PAGES) {
                    if (cursor == null || terminalState != null) return@repeat
                    // §11.1 fix-8 P1-2: in slim mode the anchored `/since`
                    // facade returns SlimSinceStagingOnlyException. The
                    // revert-cutoff walk needs OLDER history via the cursor
                    // endpoint (NOT a /since anchored fetch), so route via
                    // getSlimapiMessagesPage when the active connection is
                    // slim. legacy mode keeps the original getMessagesPaged
                    // call. The typed staging-only exception is mapped to
                    // Failed (the revert-cutoff coordinator cannot resolve
                    // via REST in slim stage-A without the cursor endpoint).
                    val pageResult = if (core.repository.supportsWatermarkResync) {
                        core.repository.getSlimapiMessagesPage(
                            sessionId = sessionId,
                            limit = PAGE_SIZE,
                            before = cursor,
                            mode = "skeleton",
                            token = core.repository.captureSlimCommitToken(),
                        )
                    } else {
                        core.repository.getMessagesPaged(sessionId, PAGE_SIZE, cursor)
                    }
                    val page = pageResult.getOrElse {
                        terminalState = RevertCutoffState.Failed
                        return@repeat
                    }
                    val found = page.items.firstOrNull { it.info.id == messageId }
                    if (found != null) {
                        terminalState = found.info.time?.created?.let(RevertCutoffState::Resolved)
                            ?: RevertCutoffState.NoTimestamp
                    } else {
                        cursor = page.nextCursor
                    }
                }
                if (terminalState == null) terminalState = RevertCutoffState.Failed
            }
        } finally {
            // Remove first: a state publication below can synchronously cause a
            // caller to ask again, and must never be dropped behind this guard.
            inFlight.remove(sessionId)
        }

        val state = terminalState ?: return
        if (commitStateIfCurrent(sessionId, messageId, operationProfileId, state) == null) return
        if (state is RevertCutoffState.Resolved && core.currentProfileId() == operationProfileId) {
            runCatching {
                persistSessionCache(
                    settingsManager = core.settingsManager,
                    sessions = core.store.sessionListFlow.value.sessions,
                    currentId = core.store.chatFlow.value.currentSessionId,
                    currentWorkdir = core.settingsManager.currentWorkdir,
                    revertCutoffs = core.store.chatFlow.value.revertCutoffs
                )
            }.onFailure { DebugLog.w("RevertCutoff", "persist cutoff failed: ${it.message}") }
        }
    }

    /** Returns null when a newer revert or host switch superseded this fetch. */
    private fun commitStateIfCurrent(
        sessionId: String,
        messageId: String,
        operationProfileId: String,
        state: RevertCutoffState
    ): RevertCutoff? {
        if (core.currentProfileId() != operationProfileId) return null
        val currentTarget = core.store.sessionListFlow.value.sessions
            .firstOrNull { it.id == sessionId }
            ?.revert?.messageId
        if (currentTarget != messageId) return null
        val cutoff = RevertCutoff(sessionId, messageId, state)
        core.writeChat { current ->
            // Revalidate inside the write boundary against the live Session list.
            val target = core.store.sessionListFlow.value.sessions
                .firstOrNull { it.id == sessionId }
                ?.revert?.messageId
            if (core.currentProfileId() == operationProfileId && target == messageId) {
                current.copy(revertCutoffs = current.revertCutoffs + (sessionId to cutoff))
            } else current
        }
        return core.store.chatFlow.value.revertCutoffs[sessionId]?.takeIf { it == cutoff }
    }

    companion object { const val MAX_PAGES = 5; const val PAGE_SIZE = 50 }
}
