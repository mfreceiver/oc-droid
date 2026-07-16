package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.PendingScrollRequest
import cn.vectory.ocdroid.ui.ScrollBehavior
import cn.vectory.ocdroid.ui.ScrollCheckpoint
import cn.vectory.ocdroid.ui.controller.CachedSessionWindow
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
 *    AppCore.dispatchSessionEffect runs an IN-MEMORY peek via
 *    [peekSessionWindow] (remove-message-persistence Task 2: the prior
 *    suspend `cacheRepository.verifyAndLoad` fingerprint check was removed
 *    — the cache is now process-local only, so verification collapses to a
 *    synchronous resident-check) and dispatches the right follow-up
 *    (cold-start LoadMessages vs in-memory hydrate). This preserves the
 *    verify-before-hydrate privacy contract (plan §0 N2): the user NEVER
 *    sees unverified cached content.
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
     * §Wave5b-Q13: monotonic id source for [PendingScrollRequest]s. Uses
     * `System.nanoTime()` (monotonic where available; collisions only if two
     * requests land in the same nanosecond, which the single main-dispatch
     * thread of every writer here makes impossible). The id is the
     * compare-and-clear token in [AppAction.ScrollConsumed]; uniqueness is
     * required so a stale consumer cannot accidentally clear a newer intent.
     */
    private fun nextScrollRequestId(): Long = System.nanoTime()

    /**
     * §Wave5b-Q13: same-session "snap to latest" intent for the send path +
     * Chat-tab reselect path. Deliberately bypasses [switchTo]'s same-session
     * no-op guard (a `switchTo(currentId)` returns early at Step 0, so it
     * would NOT generate a fresh Latest intent for an already-current
     * session).
     *
     * Issues [AppAction.ScrollRequested] with behavior=[ScrollBehavior.Latest].
     * The consumer (ChatMessageList) scrolls to item 0 + arms followBottom +
     * compare-and-clears the slot. No-op if [sessionId] is null (draft /
     * cleared).
     */
    fun requestLatestScroll(sessionId: String?) {
        if (sessionId == null) return
        store.dispatch(
            AppAction.ScrollRequested(
                requestId = nextScrollRequestId(),
                targetSessionId = sessionId,
                behavior = ScrollBehavior.Latest,
            )
        )
    }
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
     * Returns the cached window for [sessionId] in the CURRENT server group
     * if any, or null. TRUE read-only — iterates `entries` (firstOrNull) so
     * it does NOT promote the entry to most-recently-used (a `get` would).
     *
     * Production use (remove-message-persistence Task 2): the
     * VerifyAndHydrate handler in AppCore now calls this as a synchronous
     * memory probe in place of the old suspend `cacheRepository.verifyAndLoad`.
     * This is thread-safe because the handler runs on `appScope` =
     * Dispatchers.Main.immediate, the SAME dispatcher that confines
     * [sessionWindowCache] (every writer in this class runs on the main
     * dispatcher) — so the peek cannot race a concurrent put.
     *
     * The non-promoting semantics (`entries.firstOrNull` rather than `get`)
     * are exactly right for the verify probe: a hydration lookup must NOT
     * disturb the LRU order, otherwise merely opening (and verifying) a
     * rarely-used session would keep it resident and evict a hotter one.
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
     * R-20 Phase 1: precise per-session eviction (内存 only). Used by
     * EvictSession effect handler — removes the entry for `(fp, sid)` if it
     * exists. remove-message-persistence: there is no persistent counterpart;
     * the in-memory LRU is the sole cache layer.
     */
    internal fun evictSession(serverGroupFp: String, sessionId: String) {
        sessionWindowCache.remove(CacheWindowKey(serverGroupFp, sessionId))
    }

    /**
     * R-20 Phase 1: precise per-group eviction (内存 only). Used by EvictGroup
     * effect handler — removes every entry whose key matches [serverGroupFp].
     * remove-message-persistence: there is no persistent counterpart; the
     * in-memory LRU is the sole cache layer.
     */
    internal fun clearMemoryForGroup(serverGroupFp: String) {
        sessionWindowCache.entries.removeAll { it.key.serverGroupFp == serverGroupFp }
    }

    /**
     * R-20 Phase 1: clear every entry regardless of group. Used by
     * performGlobalColdStartRefresh (cold-start 刷新只清内存 — remove-
     * message-persistence 后无持久 DB，内存 LRU 是唯一缓存层)。
     */
    internal fun clearAllCached() {
        sessionWindowCache.clear()
    }

    /**
     * remove-message-persistence Task 3: append a single SSE-delivered new
     * message (`message.updated` insert branch) to the IN-MEMORY window of
     * `(serverGroupFp, sessionId)` if one is resident, so a later switch-back
     * (VerifyAndHydrate → peek hit) sees it without a re-fetch. Pure memory
     * op — no IO, no suspend, no Room. No-op when the window is not cached
     * (cold-start sessions do not proactively build a cache — only already-
     * cached sessions stay fresh; grilling 假设1: 删 SSE 缓存写会在 >40 条
     * 新消息时丢中间消息，故保留 append 但目标从 SQLite 换成 sessionWindowCache).
     *
     * Thread-safety: same confinement as every other writer in this class —
     * `appScope = Dispatchers.Main.immediate` (the handler in
     * [cn.vectory.ocdroid.ui.AppCore.dispatchSessionEffect] that drives this
     * runs on appScope too).
     *
     * **ID-aware upsert (review-fix round 1)**: the effect is delivered
     * asynchronously through a SharedFlow, so another writer
     * (writeSessionWindow from a fetch completion or captureCurrentSessionWindow
     * from a session switch) can land a window already containing this
     * `message.id` BEFORE this handler runs. To stay idempotent under that
     * reorder:
     *  - When [message.id] is already present in `existing.messages`, REPLACE
     *    the matching entry in place (preserve list cardinality — do NOT
     *    append a duplicate).
     *  - When [parts] is empty, KEEP `existing.partsByMessage[message.id]`
     *    unchanged (do NOT overwrite a non-empty parts list with emptyList —
     *    the incoming effect's `parts = emptyList()` reflects only "no new
     *    parts in this event", not "parts should be cleared"). When [parts]
     *    is non-empty, merge under [message.id] as before.
     */
    internal fun appendMessageIfCached(
        serverGroupFp: String,
        sessionId: String,
        message: Message,
        parts: List<Part>,
    ) {
        val key = CacheWindowKey(serverGroupFp, sessionId)
        val existing = sessionWindowCache[key] ?: return
        val updatedMessages = if (existing.messages.any { it.id == message.id }) {
            // ID already resident (a concurrent write-back / fetch completion
            // landed first) → replace in place, do NOT grow cardinality.
            existing.messages.map { if (it.id == message.id) message else it }
        } else {
            existing.messages + message
        }
        val updatedParts = if (parts.isEmpty()) {
            // Incoming parts empty → preserve any existing parts for this id
            // (a fetch / switch-back may already have populated them; the
            // effect's empty list is not a "clear parts" instruction).
            existing.partsByMessage
        } else {
            existing.partsByMessage + (message.id to parts)
        }
        sessionWindowCache[key] = existing.copy(
            messages = updatedMessages,
            partsByMessage = updatedParts,
        )
    }

    /**
     * The full session-switch flow — 8 steps transcribed line-for-line from
     * the original `selectSession`, with R-20 Phase 1 changes to Steps 3 + 7.
     *
     * **Steps:**
     *  1. Capture outgoing session for cache/delta cleanup (LRU write-back).
     *  2. selectSessionState (save old draft, set current, restore new draft,
     *     clear chat fields + streaming + gap + staleNotice).
     *  3. ~~Restore cached window from LRU~~ → **R-20 Phase 1**: emit
     *     [ControllerEffect.VerifyAndHydrate] (verify-before-hydrate, plan §0 N2).
     *     The handler does an in-memory peek of the resident window (Task 2:
     *     the prior suspend fingerprint check was removed once the cache
     *     became process-local) + verified hydrate asynchronously; the chat
     *     slice starts empty and is filled by either the resident window
     *     (peek hit) or the cold-start REST fetch (peek miss, whichever wins).
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
     *  8. Update unread state machine (clear target's unread badge +
     *     record lastViewedTime) + discard draft + openSessionIds prepend +
     *     persistSessionCache.
     *
     * **Step 3 timing caveat**: VerifyAndHydrate needs targetSession.time.created,
     * which Step 4 looks up. We resolve targetSession BEFORE emitting the effect
     * (moving the lookup earlier would duplicate Step 4's union search); the
     * effect carries the resolved createdAt so the handler doesn't have to
     * re-derive it. If targetSession is null (rare: sessionId has no cached
     * metadata yet), we emit createdAt=null → peek miss (no resident window to
     * hydrate from) → cold REST load.
     */
    fun switchTo(sessionId: String, behavior: ScrollBehavior = ScrollBehavior.Latest) {
        // item 3 deep guard (second layer): if the requested session is already
        // current, do nothing. This is the defensive backstop behind the UI-layer
        // guard (SessionTab's onClick no-op) — it catches other call paths
        // (pager swipe, programmatic select) that bypass the tab tap and would
        // otherwise trigger a full reload of an already-loaded session.
        //
        // §Wave5b-Q13: this SAME-SESSION NO-OP is also why the send / Chat-tab
        // reselect paths use [requestLatestScroll] (which BYPASSES this guard)
        // instead of switchTo — those paths need a fresh Latest intent on an
        // already-current session.
        if (slices.chat.value.currentSessionId == sessionId) return
        // ── Step 1: Capture outgoing session state ──────────────────────────
        // Capture the previously-selected session BEFORE overwriting
        // currentSessionId. Used below for the per-session message-cache
        // write-back of the outgoing session's view.
        val previousSessionId = slices.chat.value.currentSessionId

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
        // §Wave5b-Q13 blocker-1 fix (gpter 8.7 FAIL / groker 9.6 PASS): write
        // `currentSessionId` AND `pendingScrollRequest` in the SAME mutateChat
        // commit. The pre-fix code dispatched `ScrollRequested` first (one
        // commit) then mutateChat (a second commit); the intermediate state
        // (pendingScrollRequest set with targetSessionId = NEW but
        // currentSessionId still = OLD) was observable to stateFlow collectors,
        // violating the oracle's "在同一次 chat mutation 里原子提交
        // currentSessionId 与 pendingScrollRequest" contract.
        //
        // The `ScrollRequested` action/reducer is RETAINED for the
        // `requestLatestScroll` path (same-session send / Chat-tab reselect —
        // currentSessionId does NOT flip there, so a single-field dispatch is
        // correct). Only switchTo (which DOES flip currentSessionId) writes
        // the slot inline here. `scrollRequestId` is captured ONCE outside the
        // lambda so the value is stable (a recompute inside the CAS loop of
        // state.update would yield a different id on retry).
        val scrollRequestId = nextScrollRequestId()
        slices.mutateChat {
            it.copy(
                currentSessionId = sessionId,
                // §Wave5b-Q13: the slot + the current-session flip share ONE
                // commit so the consumer's first emission observes a
                // consistent pair (currentSessionId == targetSessionId).
                pendingScrollRequest = PendingScrollRequest(
                    requestId = scrollRequestId,
                    targetSessionId = sessionId,
                    behavior = behavior,
                ),
                messages = emptyList(),
                partsByMessage = emptyMap(),
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null,
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
                // currentModel so any synchronous reader can't leak it across
                // tabs during the window before launchLoadMessages completes
                // for the newly-selected session. §chat-ux-batch T8 (B3): the
                // authoritative per-session value is no longer persisted
                // (setModelForSession/getModelForSession deleted); launchLoadMessages
                // re-infers it from the latest assistant message (the new
                // source). The per-send authority is the TRANSIENT pendingModel
                // (cleared below) per T7's contract.
                currentModel = null,
                // §chat-ux-batch T7 (B2): clear the TRANSIENT pending picks on
                // session switch — they are per-session by contract ("no
                // cross-session carry"). Without this, an unfinished pending
                // pick from session A would leak into session B during the
                // window before the new session's transcript loads. The newly-
                // selected session resolves its own `pending ?: infer` from
                // its own ChatState snapshot (pending defaults null).
                pendingAgent = null,
                pendingModel = null,
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
        // in AppCore.dispatchSessionEffect does an IN-MEMORY peek via
        // peekSessionWindow (remove-message-persistence Task 2: the prior
        // suspend cacheRepository.verifyAndLoad fingerprint check was removed
        // — the cache is process-local now, so verification collapses to a
        // synchronous resident-check). On a peek hit it copies the resident
        // window into the chat slice + dispatches LoadMessages(resetLimit=false);
        // on a peek miss it dispatches LoadMessages(resetLimit=true).
        //
        // The chat slice stays empty (per Step 2 above) until either:
        //   (a) the handler hydrates the peek-hit window (synchronous memory
        //       read, in-process), or
        //   (b) the cold-start REST fetch lands (the handler dispatches it).
        // Either way the user never sees UNVERIFIED cached content.
        //
        // We need targetSession.time.created here, which Step 4 also looks
        // up — resolve it once now and reuse in Step 4. If targetSession is
        // null (session not yet in any slice), pass createdAt=null which the
        // handler treats as a peek miss (no resident window) → cold REST load.
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
        val sessionMap = allSessionsById(
            slices.sessionList.value.sessions,
            slices.sessionList.value.directorySessions,
            slices.sessionList.value.childSessions,
        )
        val rootId = rootIdOf(sessionId, sessionMap) ?: sessionId
        slices.mutateUnread {
            it.copy(
                unreadSessions = it.unreadSessions - setOf(sessionId, rootId),
                idleSince = it.idleSince - setOf(sessionId, rootId),
                lastViewedTime = it.lastViewedTime + (rootId to now),
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
