package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.cache.contract.CachedSessionWindow
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.persistSessionCache
import cn.vectory.ocdroid.ui.upsertSession
import cn.vectory.ocdroid.util.SettingsManager

/**
 * R-20 Phase 1: composite key for the per-session message-window LRU.
 *
 * Plan §0 (freegpt): the bare-sessionId key cannot express the "clear
 * memory for one group, keep another" operation that selectHostProfile
 * needs — two profiles reaching the same server (same logical session id
 * seen via different接入点) would otherwise share a LRU slot, and the
 * "异组 → evict previous group" rule would have no key to evict by.
 *
 * `data class` so equality + hashCode are structural (serverGroupFp+sessionId
 * together), letting LinkedHashMap promote entries correctly on `get`.
 */
internal data class CacheWindowKey(val serverGroupFp: String, val sessionId: String)

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
 * **R-20 Phase 1 changes** (plan §3 v4 round-3):
 *  - The LRU key is now [CacheWindowKey] (serverGroupFp, sessionId), not a
 *    bare sessionId. This makes "clear memory for one group, keep another"
 *    expressible (selectHostProfile 异组 → [clearMemoryForGroup]).
 *  - [clearSessionWindowCache] is split into [evictSession] /
 *    [clearMemoryForGroup] / [clearAllCached] for the 3 eviction granularities.
 *  - [switchTo] no longer synchronously seeds the chat slice from the LRU
 *    (old Step 3) and no longer synchronously emits LoadMessages (old
 *    Step 7). Instead it emits a single [ControllerEffect.VerifyAndHydrate]
 *    with `(fp, sid, targetSession.time.created)`. The handler in
 *    AppCore.dispatchSessionEffect runs `cacheRepository.verifyAndLoad` and
 *    dispatches the right follow-up (cold-start LoadMessages vs verified
 *    hydrate). This is the verify-before-hydrate privacy contract (plan §0 N2)
 *    — the user NEVER sees unverified cached content.
 */
@Suppress("DEPRECATION")
class SessionSwitcher(
    private val store: SharedStateStore,
    private val settingsManager: SettingsManager,
    private val repository: OpenCodeRepository,
    private val effects: SharedEffectBus,
    /** R-20 Phase 1: provider for the current host's serverGroupFp. Used to
     *  key the LRU and to emit VerifyAndHydrate. */
    internal val currentServerGroupFp: () -> String,
    // Injectable clock so lastViewedTime is deterministically testable.
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    /** §R18 Phase 4 (P0-9): the 9-slice bundle is derived from [store]. */
    private val slices: SliceFlows get() = store.slices
    /**
     * §Per-session message cache (LRU): maps (serverGroupFp, sessionId) →
     * the loaded message window ([CachedSessionWindow]) so switching A→B→A
     * restores A's already-loaded history + cursor instantly instead of
     * wiping to empty and re-fetching only the latest 5. Bounded to
     * [SESSION_WINDOW_CACHE_CAPACITY] entries; overflow evicts the
     * least-recently-used.
     *
     * R-20 Phase 1: the key is now [CacheWindowKey] (was a bare sessionId).
     * Two profiles in the same group continue to share slots (correct —
     * they reach the same server); two profiles in DIFFERENT groups get
     * independent slots (correct — they reach unrelated servers).
     *
     * Main-thread confined: all writers run on `viewModelScope`
     * (Dispatchers.Main.immediate). LinkedHashMap is not thread-safe but every
     * access here is on the main dispatcher. `accessOrder = true` makes both
     * `get` and `put` promote the entry to MRU (LRU semantics).
     */
    private val sessionWindowCache: MutableMap<CacheWindowKey, CachedSessionWindow> =
        object : LinkedHashMap<CacheWindowKey, CachedSessionWindow>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheWindowKey, CachedSessionWindow>?): Boolean =
                size > SESSION_WINDOW_CACHE_CAPACITY
        }

    /** Test-only visibility into the cache size (for assertions). */
    internal fun sessionWindowCacheSize(): Int = sessionWindowCache.size

    /**
     * Test-only visibility: returns the cached window for [sessionId] in the
     * CURRENT server group if any. TRUE read-only — iterates entries instead
     * of `get` so it does NOT promote the entry to most-recently-used.
     */
    internal fun peekSessionWindow(sessionId: String): CachedSessionWindow? {
        val fp = currentServerGroupFp()
        return sessionWindowCache.entries.firstOrNull { it.key == CacheWindowKey(fp, sessionId) }?.value
    }

    /**
     * Writes [window] for `(serverGroupFp, sessionId)` into the LRU cache.
     * Called from launchLoadMessages / launchLoadMoreMessages after a
     * successful fetch+merge (via the `onCacheWindow` callback threaded
     * through the orchestrator + [cn.vectory.ocdroid.ui.AppCore.makeCacheHook]).
     *
     * §review-fix #2 (gpter #2 + glm-3 🟠#3): the [serverGroupFp] parameter
     * is the CAPTURED fp from hook-factory time (makeCacheHook(fp)). The
     * PRIOR signature read `currentServerGroupFp()` at write time — a host
     * switch mid-flight would write the old request's data into the NEW
     * group's LRU slot, polluting it. The captured fp guarantees the write
     * lands in the group that owned the fetch.
     */
    internal fun writeSessionWindow(serverGroupFp: String, sessionId: String, window: CachedSessionWindow) {
        sessionWindowCache[CacheWindowKey(serverGroupFp, sessionId)] = window
    }

    /**
     * Captures the CURRENT message state into the cache under
     * `(currentServerGroupFp(), sessionId)`. Used by [switchTo] to write-back
     * the outgoing session's latest view before switching away. Reads only;
     * never mutates [state].
     */
    private fun captureCurrentSessionWindow(sessionId: String) {
        val current = slices.chat.value
        sessionWindowCache[CacheWindowKey(currentServerGroupFp(), sessionId)] = CachedSessionWindow(
            messages = current.messages,
            partsByMessage = current.partsByMessage,
            olderMessagesCursor = current.olderMessagesCursor,
            hasMoreMessages = current.hasMoreMessages
        )
    }

    /**
     * R-20 Phase 1: backward-compat "clear all" — used by ClearSessionWindowCache
     * effect (host switch 走 EvictGroup 路径不走这里；走这里的是 reset /
     * performGlobalColdStartRefresh)。保留以维持现有 ClearSessionWindowCache
     * effect 兼容；selectHostProfile 的精确清理用 [clearMemoryForGroup]。
     */
    internal fun clearSessionWindowCache() {
        sessionWindowCache.clear()
    }

    /**
     * R-20 Phase 1: precise per-session eviction (内存). Used by EvictSession
     * effect handler — removes the entry for `(fp, sid)` if it exists. The
     * persistent counterpart runs in AppCore.dispatchHostEffect via
     * cacheRepository.evictSession.
     */
    internal fun evictSession(serverGroupFp: String, sessionId: String) {
        sessionWindowCache.remove(CacheWindowKey(serverGroupFp, sessionId))
    }

    /**
     * R-20 Phase 1: precise per-group eviction (内存). Used by EvictGroup
     * effect handler — removes every entry whose key matches [serverGroupFp].
     * The persistent counterpart runs in AppCore.dispatchHostEffect via
     * cacheRepository.evictGroup.
     */
    internal fun clearMemoryForGroup(serverGroupFp: String) {
        sessionWindowCache.entries.removeAll { it.key.serverGroupFp == serverGroupFp }
    }

    /**
     * R-20 Phase 1: clear every entry regardless of group. Used by
     * performGlobalColdStartRefresh (cold-start刷新只清内存，不清持久 DB —
     * plan §3 矩阵 "全局冷启动刷新" 行)。
     */
    internal fun clearAllCached() {
        sessionWindowCache.clear()
    }

    /**
     * The full session-switch flow — 8 steps transcribed line-for-line from
     * the original `selectSession`, with R-20 Phase 1 changes to Steps 3 + 7.
     *
     * **Steps:**
     *  1. Capture outgoing session state (unread re-mark decision + LRU write-back).
     *  2. selectSessionState (save old draft, set current, restore new draft,
     *     clear chat fields + streaming + gap + staleNotice).
     *  3. ~~Restore cached window from LRU~~ → **R-20 Phase 1**: emit
     *     [ControllerEffect.VerifyAndHydrate] (verify-before-hydrate, plan §0 N2).
     *     The handler does fingerprint check + verified hydrate asynchronously;
     *     the chat slice starts empty and is filled by either the verified
     *     window or the cold-start REST fetch (whichever wins).
     *  4. Look up target session (sessions ∪ directorySessions) + upsert if needed.
     *  5. Reset collapsible-card expansion state.
     *  6. Sync repository's workdir context to the selected session's directory.
     *  6.5. Refresh pending questions (load overwrites the list; no pre-clear)
     *      so staleness comparison uses fresh data and the new session's live
     *      question surfaces.
     *  7. ~~Load messages + session status + children~~ → **R-20 Phase 1**:
     *     emit ONLY LoadSessionStatus + LoadChildSessions. LoadMessages is
     *     dispatched exclusively by the VerifyAndHydrate handler (plan §3 v4
     *     round-3: "Step 7 移除 LoadMessages — LoadMessages 由 handler 唯一
     *     调度，禁 switchTo 同步发"). Removing the synchronous LoadMessages
     *     avoids a double-load race with the handler's verify-and-then-load.
     *  8. Update unread state machine (tempClearedUnread + re-mark busy) +
     *     discard draft + openSessionIds prepend + persistSessionCache.
     *
     * **Step 3 timing caveat**: VerifyAndHydrate needs targetSession.time.created,
     * which Step 4 looks up. We resolve targetSession BEFORE emitting the effect
     * (moving the lookup earlier would duplicate Step 4's union search); the
     * effect carries the resolved createdAt so the handler doesn't have to
     * re-derive it. If targetSession is null (rare: sessionId has no cached
     * metadata yet), we emit createdAt=null → UnknownColdStart → cold load.
     */
    fun switchTo(sessionId: String) {
        // item 3 deep guard (second layer): if the requested session is already
        // current, do nothing. This is the defensive backstop behind the UI-layer
        // guard (SessionTab's onClick no-op) — it catches other call paths
        // (pager swipe, programmatic select) that bypass the tab tap and would
        // otherwise trigger a full reload of an already-loaded session.
        if (slices.chat.value.currentSessionId == sessionId) return
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
        // R-20 Phase 5: capture fp ONCE — both draft writes (save outgoing +
        // restore incoming) MUST use the same fp so they land in / read from
        // the same per-(fp, sessionId) slot. A re-read between them could race
        // a host switch.
        val fp = currentServerGroupFp()
        if (oldSessionId != null) {
            settingsManager.setDraftText(fp, oldSessionId, currentInputText)
        }
        // §R18 Phase 2-F: chatFlow.currentSessionId (set in the chat.update
        // below) is the sole runtime source; the AppCore collector persists
        // the new non-null id back to SettingsManager. No manual write here.
        val restoredDraft = settingsManager.getDraftText(fp, sessionId)
        slices.mutateChat {
            it.copy(
                currentSessionId = sessionId,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null,
                gapMarkers = emptyList(),
                staleNotice = false,
                // §F3-load-more: 切换会话时显式重置 cursor/hasMore，保证 chat slice
                // 始终内部一致（cursor=null ∧ hasMore=false），由随后的
                // launchLoadMessages(resetLimit=true) 用服务端 X-Next-Cursor 重建。
                olderMessagesCursor = null,
                hasMoreMessages = false,
                // §history-load-fix: drop the outgoing session's load flags so a
                // stale in-flight loadMessages/catchUp/loadMore (if any) does not
                // leak its spinner state into the newly-selected session.
                // (round-4 gpt-2 🔴: isLoadingMessages MUST reset here too — the
                // session-guarded finally in launchLoadMessages/catchUp declines
                // to clear on a session mismatch, so without this reset a
                // switch-during-load would leave isLoadingMessages stuck true and
                // coalesce away the new session's loads — a permanent stuck.)
                isLoadingMessages = false,
                isLoadingMoreMessages = false,
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
        // §1B-FIX (I4): fileReferences are NOT persisted (the F.4
        // writer only persists the textual `File: <path>` line via
        // setInputText), so they must be cleared on every session switch
        // — otherwise a chip from session A would leak into session B
        // even though the text content is restored from disk. We also
        // strip any `File: <path>` lines from the restored draft text
        // that match the leaked references (defensive — the persisted
        // text SHOULD already be the user's intent for this session, so
        // the strip is a no-op in practice; it guards against a future
        // cross-session text leak).
        slices.mutateComposer {
            it.copy(
                inputText = restoredDraft,
                imageAttachments = emptyList(),
                fileReferences = emptyList(),
            )
        }

        // ── Step 3 (R-20 Phase 1): verify-before-hydrate effect ─────────────
        // The OLD code synchronously seeded the chat slice from the LRU here.
        // Phase 1 replaces that with a VerifyAndHydrate effect: the handler
        // in AppCore.dispatchSessionEffect runs cacheRepository.verifyAndLoad
        // (single-transaction fingerprint check) and ONLY on Verified copies
        // the cached window into the chat slice + dispatches a follow-up
        // LoadMessages(resetLimit=false). On UnknownColdStart / MismatchEvicted
        // the handler dispatches LoadMessages(resetLimit=true).
        //
        // The chat slice stays empty (per Step 2 above) until either:
        //   (a) the handler hydrates a Verified window (typically <10ms —
        //       in-process Room read on a small table), or
        //   (b) the cold-start REST fetch lands (the handler dispatches it).
        // Either way the user never sees UNVERIFIED cached content.
        //
        // We need targetSession.time.created here, which Step 4 also looks
        // up — resolve it once now and reuse in Step 4. If targetSession is
        // null (session not yet in any slice), pass createdAt=null which the
        // handler treats as UnknownColdStart → cold REST load.
        val targetSession = (slices.sessionList.value.sessions + slices.sessionList.value.directorySessions.values.flatten())
            .firstOrNull { it.id == sessionId }
        effects.tryEmitEffect(
            ControllerEffect.VerifyAndHydrate(
                serverGroupFp = currentServerGroupFp(),
                sessionId = sessionId,
                createdAt = targetSession?.time?.created
            )
        )

        // ── Step 4: Look up target session + upsert if needed ───────────────
        // #10: if the session is currently only in directorySessions, upsert
        // it now so currentSession lookup + workdir sync work. (targetSession
        // was resolved in Step 3 above.)
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

        // ── Step 7 (R-20 Phase 1): status + children only ───────────────────
        // LoadMessages was removed here — the VerifyAndHydrate handler in
        // AppCore.dispatchSessionEffect now owns the LoadMessages dispatch
        // (it picks resetLimit based on whether the cache Verified). Emitting
        // LoadMessages here would race the handler and double-load.
        // §R18 Phase 3 Wave 1 (P1-3 C 类): switchTo 多发顺序敏感 (VerifyAndHydrate@Step3 → LoadPendingQuestions@Step6.5 → LoadSessionStatus → LoadChildSessions@Step7) → 同步 tryEmitEffect。
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
                revertCutoffs = slices.chat.value.revertCutoffs,
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
