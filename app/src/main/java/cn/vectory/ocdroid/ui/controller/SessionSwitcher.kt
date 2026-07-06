package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.CachedSessionWindow
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.persistSessionCache
import cn.vectory.ocdroid.ui.upsertSession
import cn.vectory.ocdroid.util.SettingsManager

/**
 * R-16 M2b → R-17 batch3b: owns the session-switching state machine — the
 * full 8-step `selectSession` flow extracted from the orchestrator.
 *
 * **Migration (batch 3b)**: the [SessionSwitcherCallbacks] interface was
 * eliminated. Methods that purely touched [SettingsManager] / repository
 * (rule A — saveDraft / getDraft / setCurrentSessionId / setOpenSessionIds /
 * persistSessionCache / syncCurrentDirectory) now run inline against the
 * injected [settingsManager] / [repository]. The cross-domain methods that
 * reach sibling controllers (loadChildSessions / loadMessages /
 * loadSessionStatus / loadPendingQuestions / onClearDeltaBuffers) emit
 * [ControllerEffect]s on [effects] instead (rule B).
 *
 * **Moved from the orchestrator:**
 *  - `sessionWindowCache` (LRU LinkedHashMap) + all cache helpers
 *    (captureCurrentSessionWindow / writeSessionWindow / peekSessionWindow /
 *    clearSessionWindowCache / sessionWindowCacheSize).
 *  - `switchTo(sessionId)` — the complete selectSession flow (8 steps).
 *
 * **Constructor params** follow the [ComposerController] pattern: the slice
 * `MutableStateFlow`s (`composerFlow` / `expandedParts` / `slices`) stay
 * declared in the orchestrator (referenced by the R-17 `SliceFlows` container
 * and the public pass-throughs) but are received here by reference.
 * SessionSwitcher is the single writer during a switch. Side effects that
 * cross into other controllers flow through [effects]; same-domain state
 * (drafts / open-tab list / directory) writes through [settingsManager] /
 * [repository] directly.
 *
 * **Zero behaviour change** vs the pre-extraction
 * `orchestrator.selectSession`: the 8 steps, their ordering, and the
 * `tempClearedUnread` / `previousWasBusyAndCleared` re-marking logic are
 * transcribed line-for-line.
 *
 * RFC reference: R-16 §E / §M2.
 */
@Suppress("DEPRECATION")
internal class SessionSwitcher(
    private val store: SharedStateStore,
    private val settingsManager: SettingsManager,
    private val repository: OpenCodeRepository,
    private val effects: SharedEffectBus,
    // Injectable clock so lastViewedTime is deterministically testable.
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    /** §R18 Phase 4 (P0-9): the 9-slice bundle is derived from [store]. */
    private val slices: SliceFlows get() = store.slices
    /**
     * §Per-session message cache (LRU): maps sessionId → the loaded message
     * window ([CachedSessionWindow]) so switching A→B→A restores A's already-
     * loaded history + cursor instantly instead of wiping to empty and re-
     * fetching only the latest 5. Bounded to [SESSION_WINDOW_CACHE_CAPACITY]
     * entries; overflow evicts the least-recently-used.
     *
     * Main-thread confined: all writers run on `viewModelScope`
     * (Dispatchers.Main.immediate). LinkedHashMap is not thread-safe but every
     * access here is on the main dispatcher. `accessOrder = true` makes both
     * `get` and `put` promote the entry to MRU (LRU semantics).
     */
    private val sessionWindowCache: MutableMap<String, CachedSessionWindow> =
        object : LinkedHashMap<String, CachedSessionWindow>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSessionWindow>?): Boolean =
                size > SESSION_WINDOW_CACHE_CAPACITY
        }

    /** Test-only visibility into the cache size (for assertions). */
    internal fun sessionWindowCacheSize(): Int = sessionWindowCache.size

    /**
     * Test-only visibility: returns the cached window for [sessionId] if any.
     * TRUE read-only — iterates entries instead of `get` so it does NOT
     * promote the entry to most-recently-used.
     */
    internal fun peekSessionWindow(sessionId: String): CachedSessionWindow? =
        sessionWindowCache.entries.firstOrNull { it.key == sessionId }?.value

    /**
     * Writes [window] for [sessionId] into the LRU cache. Called from
     * launchLoadMessages / launchLoadMoreMessages after a successful fetch+merge
     * (via the `onCacheWindow` callback threaded through the orchestrator).
     */
    internal fun writeSessionWindow(sessionId: String, window: CachedSessionWindow) {
        sessionWindowCache[sessionId] = window
    }

    /**
     * Captures the CURRENT message state into the cache under [sessionId].
     * Used by [switchTo] to write-back the outgoing session's latest view
     * before switching away. Reads only; never mutates [state].
     */
    private fun captureCurrentSessionWindow(sessionId: String) {
        val current = slices.chat.value
        sessionWindowCache[sessionId] = CachedSessionWindow(
            messages = current.messages,
            partsByMessage = current.partsByMessage,
            olderMessagesCursor = current.olderMessagesCursor,
            hasMoreMessages = current.hasMoreMessages
        )
    }

    /** Drops the entire cache. Called on host switch / host delete / reset. */
    internal fun clearSessionWindowCache() {
        sessionWindowCache.clear()
    }

    /**
     * The full session-switch flow — 8 steps transcribed line-for-line from
     * the original `selectSession`.
     *
     * **Steps:**
     *  1. Capture outgoing session state (unread re-mark decision + LRU write-back).
     *  2. selectSessionState (save old draft, set current, restore new draft,
     *     clear chat fields + streaming + gap + staleNotice).
     *  3. Restore cached window from LRU (if hit → seed messages; else cold load).
     *  4. Look up target session (sessions ∪ directorySessions) + upsert if needed.
     *  5. Reset collapsible-card expansion state.
     *  6. Sync repository's workdir context to the selected session's directory.
     *  6.5. Refresh pending questions (load overwrites the list; no pre-clear)
     *      so staleness comparison uses fresh data and the new session's live
     *      question surfaces.
     *  7. Load messages (resetLimit based on cache hit) + session status + children.
     *  8. Update unread state machine (tempClearedUnread + re-mark busy) +
     *     discard draft + openSessionIds prepend + persistSessionCache.
     */
    fun switchTo(sessionId: String) {
        // ── Step 1: Capture outgoing session state ──────────────────────────
        // Capture the previously-selected session BEFORE overwriting
        // currentSessionId. Used below to decide whether the session the user
        // is leaving should be re-marked unread: if it was temp-cleared (user
        // had viewed it) and is still busy, background activity may still
        // produce output the user cares about — re-mark it.
        val previousSessionId = slices.chat.value.currentSessionId
        val previousWasBusyAndCleared = previousSessionId != null &&
            previousSessionId != sessionId &&
            slices.unread.value.tempClearedUnread.contains(previousSessionId) &&
            slices.sessionList.value.sessionStatuses[previousSessionId]?.isBusy == true

        // §Per-session message cache (write-back): snapshot the OUTGOING
        // session's currently-loaded view into the LRU before clearing it.
        if (previousSessionId != null && previousSessionId != sessionId) {
            captureCurrentSessionWindow(previousSessionId)
            // M5 跟进 (§4.2): drop the outgoing session's pending streaming-
            // delta buffers + cancel their flush jobs, so a late flush can't
            // write into the newly-selected session's state. Done here
            // (alongside the LRU write-back) because this branch only fires on
            // a real session change — a same-session reselect keeps its deltas.
            // §R18 Phase 3 Wave 1 (P1-3 C 类): switchTo 多发顺序敏感 → 同步 tryEmitEffect。
            effects.tryEmitEffect(ControllerEffect.ClearDeltaBuffers)
        }

        // ── Step 2: selectSessionState (inlined) ────────────────────────────
        // Save old draft, set currentSessionId, restore new draft, clear chat.
        val oldSessionId = slices.chat.value.currentSessionId
        val currentInputText = slices.composer.value.inputText
        if (oldSessionId != null) {
            settingsManager.setDraftText(oldSessionId, currentInputText)
        }
        // §R18 Phase 2-F: chatFlow.currentSessionId (set in the chat.update
        // below) is the sole runtime source; the AppCore collector persists
        // the new non-null id back to SettingsManager. No manual write here.
        val restoredDraft = settingsManager.getDraftText(sessionId)
        slices.mutateChat {
            it.copy(
                currentSessionId = sessionId,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null,
                gapInfo = null,
                staleNotice = false,
                // §model-selection (V1-per-prompt): drop the outgoing session's
                // currentModel so dispatchSendMessage (which reads
                // _chatFlow.value.currentModel synchronously) can't leak it
                // across tabs during the window before launchLoadMessages
                // completes for the newly-selected session. The authoritative
                // per-session value is reloaded from SettingsManager by
                // launchLoadMessages (stored wins) / dispatchSendMessage
                // (getModelForSession preferred).
                currentModel = null
            )
        }
        // Restore the selected session's draft into the composer slice.
        slices.mutateComposer { it.copy(inputText = restoredDraft) }

        // ── Step 3: Restore cached window from LRU ──────────────────────────
        // If the new session has a cached window, seed messages/parts/cursor/
        // hasMore from it INSTEAD of leaving the empty list set above. The
        // subsequent loadMessages uses resetLimit=false on cache hit so the
        // §preserveUnfetched merge keeps older pages and merges the tail.
        val cachedWindow = sessionWindowCache[sessionId]
        if (cachedWindow != null) {
            slices.mutateChat {
                it.copy(
                    messages = cachedWindow.messages,
                    partsByMessage = cachedWindow.partsByMessage,
                    olderMessagesCursor = cachedWindow.olderMessagesCursor,
                    hasMoreMessages = cachedWindow.hasMoreMessages
                )
            }
        }

        // ── Step 4: Look up target session + upsert if needed ───────────────
        // Union of cached sessions list and directorySessions (#10: a session
        // surfaced for a connected workdir may not yet be in the global list).
        val targetSession = (slices.sessionList.value.sessions + slices.sessionList.value.directorySessions.values.flatten())
            .firstOrNull { it.id == sessionId }
        // #10: if the session is currently only in directorySessions, upsert
        // it now so currentSession lookup + workdir sync work.
        if (targetSession != null && slices.sessionList.value.sessions.none { it.id == sessionId }) {
            slices.mutateSessionList { s -> s.copy(sessions = upsertSession(s.sessions, targetSession)) }
        }

        // ── Step 5: Reset collapsible-card expansion state ──────────────────
        // Switching sessions collapses all cards (#13). Done here so history
        // pagination (loadMore) preserves the user's in-progress expand state.
        // §R18 Phase 3 Wave 1 (C-2): .value = → .update { } (CAS, atomic).
        // §R18 Phase 4 (P0-9): write via SharedStateStore.mutateExpandedParts.
        store.mutateExpandedParts { emptyMap() }

        // ── Step 6: Workdir tracking ───────────────────────────────────────
        // §R18 Phase 2-E step 2: the repository.setCurrentDirectory call was
        // removed; downstream directory-scoped calls now take an explicit
        // `directory` parameter. settingsManager.currentWorkdir is updated
        // below (Step 8 / persistSessionCache path) when the user opens a
        // new root session — the per-session directory is read directly from
        // the Session object at each callsite.
        // (No global mutation needed; intentionally blank.)

        // ── Step 6.5: Refresh pending questions (§stale-question) ───────────
        // Re-fetch the server's pending-questions list so the new session's
        // live question surfaces immediately and the stale-question calc in
        // ChatMessageList uses fresh data. We deliberately do NOT clear the
        // list first: the effect handler's onSuccess atomically replaces
        // it, and onFailure leaves state unchanged — so a transient network
        // failure cannot wipe a LIVE question that was delivered via SSE
        // `question.asked` earlier (which would make its QuestionCardView
        // disappear and the part mis-render as "Interrupted"). Cross-session
        // leakage is a non-issue: partsByMessage is already cleared in Step 2
        // so the outgoing session's parts don't participate in the stale calc,
        // and stale matching is by messageId+callId, not sessionId.
        effects.tryEmitEffect(ControllerEffect.LoadPendingQuestions)

        // ── Step 7: Load messages + session status + child sessions ─────────
        // resetLimit=false on cache hit (don't wipe restored messages); true
        // on cache miss (cold-load latest 5 + seed cursor).
        // §R18 Phase 3 Wave 1 (P1-3 C 类): switchTo 多发顺序敏感 (LoadPendingQuestions → LoadMessages → LoadSessionStatus → LoadChildSessions) → 同步 tryEmitEffect。
        effects.tryEmitEffect(ControllerEffect.LoadMessages(sessionId, resetLimit = cachedWindow == null))
        effects.tryEmitEffect(ControllerEffect.LoadSessionStatus)
        effects.tryEmitEffect(ControllerEffect.LoadChildSessions(sessionId))

        // ── Step 8: Unread state machine + draft discard + openSessionIds ───
        val now = clock()
        slices.mutateUnread {
            // Re-mark the previous session as unread if it was busy when the
            // user navigated away.
            val withReMark = if (previousWasBusyAndCleared) {
                it.copy(unreadSessions = it.unreadSessions + previousSessionId!!)
            } else {
                it
            }
            withReMark.copy(
                unreadSessions = withReMark.unreadSessions - sessionId,
                lastViewedTime = withReMark.lastViewedTime + (sessionId to now),
                // Track the newly-selected session as "temp-cleared" so a
                // subsequent switch away (while busy) can re-mark it.
                tempClearedUnread = withReMark.tempClearedUnread + sessionId
            )
        }
        // Selecting a real session discards any in-progress draft.
        slices.mutateComposer { it.copy(draftWorkdir = null) }

        // Browser-tab semantics: prepend only when NOT already in the list.
        // Skip sub-agents (parentId != null) — transient navigations.
        if (targetSession?.parentId == null && sessionId !in slices.sessionList.value.openSessionIds) {
            val updated = (listOf(sessionId) + slices.sessionList.value.openSessionIds).take(8)
            settingsManager.openSessionIds = updated
            slices.mutateSessionList { it.copy(openSessionIds = updated) }
            // Fix #5: persist the newly-opened session's metadata into
            // sessionCache so its tab survives a restart.
            val sourceSessions = (slices.sessionList.value.sessions + slices.sessionList.value.directorySessions.values.flatten())
                .distinctBy { it.id }
            persistSessionCache(
                settingsManager = settingsManager,
                sessions = sourceSessions,
                openIds = updated,
                currentId = slices.chat.value.currentSessionId,
                currentWorkdir = settingsManager.currentWorkdir,
            )
        }
    }

    companion object {
        /**
         * §Per-session message cache capacity: max number of session windows
         * kept in memory. ~12 covers the typical "open tabs" working set
         * (openSessionIds is capped at 8) plus a few recently-evicted tabs.
         */
        internal const val SESSION_WINDOW_CACHE_CAPACITY = 12
    }
}
