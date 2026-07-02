package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.AppState
import cn.vectory.ocdroid.ui.CachedSessionWindow
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.applyComposerSlice
import cn.vectory.ocdroid.ui.updateAndSync
import cn.vectory.ocdroid.ui.upsertSession
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * R-16 M2b: callbacks the [SessionSwitcher] invokes back into MainViewModel for
 * SettingsManager / repository side effects during session switching. Defined
 * as an interface rather than direct injection so the controller never holds a
 * reference to MainViewModel, SettingsManager, or OpenCodeRepository — avoiding
 * the circular dependency flagged in R-16 §7.3.
 *
 * Follows the [ForegroundCatchUpCallbacks] / [ComposerCallbacks] pattern.
 *
 * Each method maps 1:1 to an existing MainViewModel private helper or
 * SettingsManager call inside the original `selectSession`; the implementations
 * are wired in MainViewModel's property initialiser.
 */
interface SessionSwitcherCallbacks {
    /** Persists the draft text for [sessionId] via SettingsManager.setDraftText. */
    fun saveDraft(sessionId: String, text: String)

    /** Returns the persisted draft text for [sessionId] from SettingsManager. */
    fun getDraft(sessionId: String): String

    /** Writes [sessionId] to SettingsManager.currentSessionId. */
    fun setCurrentSessionId(sessionId: String?)

    /** Writes [ids] to SettingsManager.openSessionIds. */
    fun setOpenSessionIds(ids: List<String>)

    /**
     * Persists the session-metadata cache via the `persistSessionCache` free
     * helper. The implementation reads `currentWorkdir` from SettingsManager
     * internally so the controller doesn't need a SettingsManager reference.
     */
    fun persistSessionCache(sessions: List<Session>, openIds: List<String>, currentId: String?)

    /** Sets the repository's working directory to [directory] (null = default). */
    fun syncCurrentDirectory(directory: String?)

    /** Loads child (sub-agent) sessions for [sessionId] from the repository. */
    fun loadChildSessions(sessionId: String)

    /** Loads the message window for [sessionId] (cold or incremental merge). */
    fun loadMessages(sessionId: String, resetLimit: Boolean)

    /** Loads the current session-status map from the repository. */
    fun loadSessionStatus()

    /**
     * §stale-question: refreshes the pending-questions list from the server
     * (GET /question). Called by [SessionSwitcher.switchTo] so the staleness
     * comparison for question tool parts uses fresh data when the user lands
     * on a session, and any live question the new session is asking surfaces
     * immediately (instead of waiting for the next SSE event or full reconnect).
     *
     * Note: we REFRESH, not clear-and-reload. Clearing first was rejected
     * (reviewer consensus, glmer most rigorous): if the async load fails
     * (network), an empty list would wipe a LIVE question delivered earlier
     * via SSE `question.asked`, making its QuestionCardView disappear and the
     * part mis-render as "Interrupted" until the next reconnect. The
     * onSuccess path atomically replaces the list; onFailure leaves state
     * unchanged. Cross-session leakage is a non-issue because (a)
     * `partsByMessage` is already cleared in Step 2 of [switchTo] so the
     * outgoing session's parts don't participate in the stale calc, and (b)
     * stale matching is by `messageId+callId`, not sessionId.
     */
    fun loadPendingQuestions()

    /**
     * M5 跟进 (§4.2): drops all pending streaming-delta buffers in
     * [SessionSyncCoordinator]. Called by [SessionSwitcher.switchTo] when
     * LEAVING the outgoing session so its pending delta flush jobs can't
     * write into the newly-selected session's state. Idempotent.
     */
    fun onClearDeltaBuffers()
}

/**
 * R-16 M2b: owns the session-switching state machine — the full 8-step
 * `selectSession` flow extracted from MainViewModel.
 *
 * **Moved from MainViewModel:**
 *  - `sessionWindowCache` (LRU LinkedHashMap) + all cache helpers
 *    (captureCurrentSessionWindow / writeSessionWindow / peekSessionWindow /
 *    clearSessionWindowCache / sessionWindowCacheSize).
 *  - `switchTo(sessionId)` — the complete selectSession flow (8 steps).
 *
 * **Constructor params** follow the [ComposerController] pattern: the slice
 * `MutableStateFlow`s (`state` / `composerFlow` / `expandedParts` / `slices`)
 * stay declared in MainViewModel (referenced by the R-17 `SliceFlows`
 * container and the public pass-throughs) but are received here by reference.
 * SessionSwitcher is the single writer during a switch. All external side
 * effects (SettingsManager, repository, persistence) flow through
 * [SessionSwitcherCallbacks].
 *
 * **Zero behaviour change** vs the pre-extraction `MainViewModel.selectSession`:
 * the 8 steps, their ordering, and the `tempClearedUnread` /
 * `previousWasBusyAndCleared` re-marking logic are transcribed line-for-line.
 *
 * RFC reference: R-16 §E / §M2.
 */
@Suppress("DEPRECATION")
internal class SessionSwitcher(
    private val state: MutableStateFlow<AppState>,
    private val composerFlow: MutableStateFlow<ComposerState>,
    private val expandedParts: MutableStateFlow<Map<String, Boolean>>,
    private val slices: SliceFlows,
    private val callbacks: SessionSwitcherCallbacks,
    // Injectable clock so lastViewedTime is deterministically testable.
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
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
     * (via the `onCacheWindow` callback threaded through MainViewModel).
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
        val current = state.value
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
     * the original `MainViewModel.selectSession`.
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
            callbacks.onClearDeltaBuffers()
        }

        // ── Step 2: selectSessionState (inlined) ────────────────────────────
        // Save old draft, set currentSessionId, restore new draft, clear chat.
        val oldSessionId = slices.chat.value.currentSessionId
        val currentInputText = composerFlow.value.inputText
        if (oldSessionId != null) {
            callbacks.saveDraft(oldSessionId, currentInputText)
        }
        callbacks.setCurrentSessionId(sessionId)
        val restoredDraft = callbacks.getDraft(sessionId)
        state.updateAndSync(slices) {
            it.copy(
                currentSessionId = sessionId,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null,
                gapInfo = null,
                staleNotice = false
            )
        }
        // Restore the selected session's draft into the composer slice.
        applyComposerSlice(state, composerFlow) { it.copy(inputText = restoredDraft) }

        // ── Step 3: Restore cached window from LRU ──────────────────────────
        // If the new session has a cached window, seed messages/parts/cursor/
        // hasMore from it INSTEAD of leaving the empty list set above. The
        // subsequent loadMessages uses resetLimit=false on cache hit so the
        // §preserveUnfetched merge keeps older pages and merges the tail.
        val cachedWindow = sessionWindowCache[sessionId]
        if (cachedWindow != null) {
            state.updateAndSync(slices) {
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
            state.updateAndSync(slices) { it.copy(sessions = upsertSession(it.sessions, targetSession)) }
        }

        // ── Step 5: Reset collapsible-card expansion state ──────────────────
        // Switching sessions collapses all cards (#13). Done here so history
        // pagination (loadMore) preserves the user's in-progress expand state.
        expandedParts.value = emptyMap()

        // ── Step 6: Sync repository's workdir context ───────────────────────
        val directory = slices.sessionList.value.sessions.firstOrNull { it.id == sessionId }?.directory
        callbacks.syncCurrentDirectory(directory)

        // ── Step 6.5: Refresh pending questions (§stale-question) ───────────
        // Re-fetch the server's pending-questions list so the new session's
        // live question surfaces immediately and the stale-question calc in
        // ChatMessageList uses fresh data. We deliberately do NOT clear the
        // list first: loadPendingQuestions()'s onSuccess atomically replaces
        // it, and onFailure leaves state unchanged — so a transient network
        // failure cannot wipe a LIVE question that was delivered via SSE
        // `question.asked` earlier (which would make its QuestionCardView
        // disappear and the part mis-render as "Interrupted"). Cross-session
        // leakage is a non-issue: partsByMessage is already cleared in Step 2
        // so the outgoing session's parts don't participate in the stale calc,
        // and stale matching is by messageId+callId, not sessionId.
        callbacks.loadPendingQuestions()

        // ── Step 7: Load messages + session status + child sessions ─────────
        // resetLimit=false on cache hit (don't wipe restored messages); true
        // on cache miss (cold-load latest 5 + seed cursor).
        callbacks.loadMessages(sessionId, resetLimit = cachedWindow == null)
        callbacks.loadSessionStatus()
        callbacks.loadChildSessions(sessionId)

        // ── Step 8: Unread state machine + draft discard + openSessionIds ───
        val now = clock()
        state.updateAndSync(slices) {
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
        applyComposerSlice(state, composerFlow) { it.copy(draftWorkdir = null) }

        // Browser-tab semantics: prepend only when NOT already in the list.
        // Skip sub-agents (parentId != null) — transient navigations.
        if (targetSession?.parentId == null && sessionId !in slices.sessionList.value.openSessionIds) {
            val updated = (listOf(sessionId) + slices.sessionList.value.openSessionIds).take(8)
            callbacks.setOpenSessionIds(updated)
            state.updateAndSync(slices) { it.copy(openSessionIds = updated) }
            // Fix #5: persist the newly-opened session's metadata into
            // sessionCache so its tab survives a restart.
            val postState = state.value
            val sourceSessions = (postState.sessions + postState.directorySessions.values.flatten())
                .distinctBy { it.id }
            callbacks.persistSessionCache(sourceSessions, updated, postState.currentSessionId)
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
