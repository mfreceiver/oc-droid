package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.api.NOISY_SSE_LOG_EVENTS
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.ui.AppState
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.lenientJson
import cn.vectory.ocdroid.ui.parseMessagePartDeltaEvent
import cn.vectory.ocdroid.ui.parseQuestionAskedEvent
import cn.vectory.ocdroid.ui.parseSessionCreatedEvent
import cn.vectory.ocdroid.ui.parseSessionStatusEvent
import cn.vectory.ocdroid.ui.parseSessionUpdatedEvent
import cn.vectory.ocdroid.ui.reasoningPartOrNull
import cn.vectory.ocdroid.ui.updateAndSync
import cn.vectory.ocdroid.ui.upsertSession
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * R-16 M4: callbacks the [SessionSyncCoordinator] invokes back into MainViewModel
 * for the side effects an SSE event can trigger.
 *
 * Defined as an interface rather than direct injection so the coordinator never
 * holds a reference to MainViewModel — avoiding the circular dependency flagged
 * in R-16 §7.3 (Coordinator ← MainViewModel that owns it). Each method maps 1:1
 * to the private helper the pre-extraction `handleSSEEvent` invoked inline.
 */
interface SessionSyncCoordinatorCallbacks {
    /**
     * `server.connected` frame — every (re)connect's first frame. Routed to the
     * foreground catch-up state machine (ForegroundCatchUpController.onServerConnected)
     * which decides whether to fire a gap-aware catch-up probe.
     */
    fun onServerConnected()

    /**
     * Authoritative reload of [sessionId]'s message window. [resetLimit] = true
     * wipes the streaming overlay and reloads the latest window (message.created
     * / part.created / session.status busy|idle finalization); false preserves
     * the current paging limit (gap-aware catch-up tail fetch).
     */
    fun onRefreshMessages(sessionId: String, resetLimit: Boolean)

    /** Full session-list refresh (session.created that isn't parseable, etc.). */
    fun onRefreshSessions()

    /** Re-fetch pending permissions from the server (permission.asked). */
    fun onLoadPendingPermissions()

    /**
     * Non-fatal issue (invalid SSE payload that was ignored). Logged only — NOT
     * surfaced to the user. Routed to reportNonFatalIssue.
     */
    fun onNonFatalIssue(message: String)
}

/**
 * R-16 M4: owns the SSE event → AppState fold (the SSE-trust dispatch model).
 *
 * **Moved from MainViewModel** (`handleSSEEvent` + the `handleIncomingSseEvent`
 * / `markSessionUnread` free functions that lived in `MainViewModelSyncActions.kt`):
 * every server-pushed message / session / status / part / permission / question
 * / todo event is folded in-place into the shared `MutableStateFlow<AppState>`
 * (patch-if-found + insert-if-absent for messages; upsert for sessions; in-place
 * map updates for statuses/todos/questions; streaming overlay for parts). The
 * side effects a fold can trigger (authoritative reload, permission refresh,
 * catch-up) flow through [SessionSyncCoordinatorCallbacks] which MainViewModel
 * implements — so the coordinator never touches MainViewModel, the Repository,
 * or any other controller directly (R-16 §7.3 circular-dependency avoidance).
 *
 * The coordinator holds NO state of its own: SSE events are stateless folds over
 * the shared AppState, so a single instance follows the ViewModel lifetime and
 * is driven entirely through [handleEvent]. The `server.connected` catch-up
 * trigger is folded in here (one entry point for every event) and routed to the
 * foreground catch-up controller via [SessionSyncCoordinatorCallbacks.onServerConnected].
 *
 * All state writes go through `state.updateAndSync(slices)` which is
 * functionally equivalent to MainViewModel's `updateState` (writes AppState +
 * syncs all nine slices — MutableStateFlow suppresses equal-value emissions so
 * only changed slices notify subscribers).
 *
 * RFC reference: R-16 §B / §M4. Zero behaviour change — the dispatch body is a
 * verbatim move of the pre-extraction `handleIncomingSseEvent`.
 */
@Suppress("DEPRECATION")
internal class SessionSyncCoordinator(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<AppState>,
    private val slices: SliceFlows,
    private val settingsManager: SettingsManager,
    private val callbacks: SessionSyncCoordinatorCallbacks
) {
    /**
     * §M5 delta coalescing: per-partId trailing buffer
     * and flush jobs. The leading-edge delta writes immediately; subsequent
     * deltas within [DELTA_COALESCE_MS] are appended to [deltaBuffer] and
     * flushed in one batch when the window expires — collapsing per-token
     * Compose recompositions into one-per-window. Keyed by partId (matches
     * streamingPartTexts rekeying, S4).
     */
    private val deltaBuffer = mutableMapOf<String, StringBuilder>()
    private val flushJobs = mutableMapOf<String, Job>()
    /**
     * Entry point for every SSE event. Mirrors the pre-extraction
     * `MainViewModel.handleSSEEvent`: first the `server.connected` catch-up
     * trigger, then the [dispatchSseEvent] fold.
     *
     * §Phase1E: every (re)connect's first frame is `server.connected`. Catch-up
     * runs on every connect EXCEPT the very first process-time connect (cold
     * start has no local history). The three-tier suppress /
     * sseHasConnectedOnce state machine lives in [ForegroundCatchUpController]
     * (R-16 M1); it calls back into the catch-up probe via
     * [ForegroundCatchUpCallbacks] when a probe is actually warranted.
     */
    fun handleEvent(event: SSEEvent) {
        if (event.payload.type == "server.connected") {
            callbacks.onServerConnected()
        }
        dispatchSseEvent(event)
    }

    /**
     * Dispatches a single SSE event. Per the SSE-trust model (mirrors opencode-web):
     *
     * - `message.updated` for the current session does NOT reload — live text comes
     *   via `streamingPartTexts` (populated by `message.part.updated` delta/full
     *   text). Structural sync is handled in-place: an existing message is patched,
     *   and a NEW message (absent from the local list) is INSERTED (server 1.17.11+
     *   emits `message.updated`, not `message.created`, for new messages; the oc-ref
     *   web client does the same patch-if-found + insert-if-absent).
     * - `message.part.updated` with empty delta but non-null ids (a part status
     *   flip) does NOT clear streaming buffers or reload. Only a true `part.created`
     *   (ids null) wipes the streaming state and reloads.
     * - `session.status` transitions only update the `sessionStatuses` map (busy/idle
     *   badge). They do NOT reload or clear streaming buffers — the finalized turn
     *   text is carried by `streamingPartTexts` until a foreground catch-up
     *   reconciles the persisted message list. (A busy transition on the CURRENT
     *   session also triggers a debounced reload as the cross-client-sync fallback.)
     * - There is no watchdog/idle-reload: a silently-stalled SSE feed recovers via
     *   connection-level retry, a foreground transition (SSE restart), or the next
     *   user action — matching opencode-web.
     *
     * Verbatim move of the `handleIncomingSseEvent` free function; the `onXxx`
     * params became [callbacks] calls and `state`/`slices` became instance fields.
     */
    private fun dispatchSseEvent(event: SSEEvent) {
        // Throttle dispatch logging to preserve the 1000-entry ring buffer's signal.
        // Skipped (noise): server.heartbeat (periodic), message.part.delta (per-token
        // during streaming — its render IS the proof), server.connected (fires on
        // every reconnect), and plugin.added / catalog.updated / integration.updated
        // (server-internal bursts that fire in large flurries when a run starts,
        // e.g. when another client sends a message). Logging-only — dispatch is
        // unchanged. All other types (message.created/updated, session.*,
        // permission/question, todo, connection) are logged — they are the actual
        // sync signal.
        val type = event.payload.type
        val evtSession = event.payload.getString("sessionID") ?: "-"
        val noisy = type in NOISY_SSE_LOG_EVENTS
        if (!noisy) {
            DebugLog.d("Sync", "dispatch $type session=$evtSession current=${slices.chat.value.currentSessionId}")
        }
        when (event.payload.type) {
            "session.created" -> {
                val created = parseSessionCreatedEvent(event)
                if (created != null) {
                    state.updateAndSync(slices) { it.copy(sessions = upsertSession(it.sessions, created.session)) }
                } else {
                    callbacks.onNonFatalIssue("Ignoring invalid session.created payload")
                }
            }
            "session.updated" -> {
                val updated = parseSessionUpdatedEvent(event)
                if (updated != null) {
                    state.updateAndSync(slices) {
                        if (updated.isArchived) {
                            // Archived (typically by another client): evict the
                            // id from the open-tabs list and persist, mirroring
                            // the user-triggered archive path. If the archived
                            // session is the currently-open one, also clear
                            // currentSessionId + messages so the chat view falls
                            // back to the empty state instead of lingering on an
                            // archived session whose tab has disappeared.
                            val newOpenIds = it.openSessionIds.filter { id -> id != updated.id }
                            if (newOpenIds != it.openSessionIds) {
                                settingsManager.openSessionIds = newOpenIds
                            }
                            if (it.currentSessionId == updated.id) {
                                settingsManager.currentSessionId = null
                                it.copy(
                                    sessions = upsertSession(it.sessions, updated),
                                    openSessionIds = newOpenIds,
                                    currentSessionId = null,
                                    messages = emptyList(),
                                    partsByMessage = emptyMap()
                                )
                            } else {
                                it.copy(
                                    sessions = upsertSession(it.sessions, updated),
                                    openSessionIds = newOpenIds
                                )
                            }
                        } else {
                            it.copy(sessions = upsertSession(it.sessions, updated))
                        }
                    }
                } else {
                    callbacks.onNonFatalIssue("Ignoring invalid session.updated payload")
                }
            }
            "session.status" -> {
                val statusEvent = parseSessionStatusEvent(event)
                if (statusEvent != null) {
                    state.updateAndSync(slices) {
                        it.copy(
                            sessionStatuses = it.sessionStatuses + (statusEvent.sessionId to statusEvent.status)
                        )
                    }
                    // §cross-client-sync: when the CURRENT session goes busy, a run
                    // just started — i.e. a message was sent, possibly from ANOTHER
                    // client (e.g. the web UI). This is a belt-and-suspenders reload
                    // that catches messages emitted before the message.updated
                    // insert-if-absent path (below) has run / before the local view
                    // is subscribed — the primary surfacing path is now message.updated
                    // insert-if-absent (mirrors the web client). Debounced via
                    // loadMessagesWithRetry's 400ms delay + isLoadingMessages
                    // coalescing; the user message is persisted server-side before
                    // the run starts. The overlay-clear in launchLoadMessages is
                    // gated on !busy, so this does NOT disrupt the streaming overlay.
                    if (statusEvent.status.isBusy &&
                        statusEvent.sessionId == slices.chat.value.currentSessionId
                    ) {
                        DebugLog.i("Sync", "session.status busy (current) → reload (cross-client message sync)")
                        callbacks.onRefreshMessages(statusEvent.sessionId, true)
                    }
                    // When a temp-cleared session finishes its in-flight work
                    // (busy -> idle) and is NOT the currently-open one, drop it
                    // from tempClearedUnread: there is no longer any pending work
                    // that would warrant re-marking it. If the session was already
                    // re-marked unread (because the user navigated away while it
                    // was busy), keep the badge so the user still knows there was
                    // activity — the user opening the session will clear it.
                    if (!statusEvent.status.isBusy &&
                        statusEvent.sessionId != slices.chat.value.currentSessionId &&
                        slices.unread.value.tempClearedUnread.contains(statusEvent.sessionId)
                    ) {
                        state.updateAndSync(slices) {
                            it.copy(tempClearedUnread = it.tempClearedUnread - statusEvent.sessionId)
                        }
                    }
                    // SSE-trust model: session.status (busy/idle) only updates the
                    // status badge. It does NOT reload or clear streaming buffers
                    // (except the busy-current and idle-current finalization paths
                    // above). The finalized turn text is carried by
                    // streamingPartTexts until a foreground catch-up reconciles the
                    // persisted message list — mirroring opencode-web.
                    //
                    // §append-safe finalization (gpter MAJOR): the overlay-clear in
                    // launchLoadMessages is gated on !busy, so if the last reload
                    // happened while busy (overlay preserved), the overlay could
                    // linger after the run settles. When the CURRENT session goes
                    // idle with a non-empty overlay, reconcile against the now-
                    // persisted authoritative window (loadMessagesWithRetry delays
                    // internally so the server has time to persist the finalized
                    // part text; status is now idle so the gated clear will run).
                    if (statusEvent.status.isIdle &&
                        statusEvent.sessionId == slices.chat.value.currentSessionId
                    ) {
                        val s = state.value
                        val shouldReload = s.streamingPartTexts.isNotEmpty() || s.streamingReasoningPart != null
                        DebugLog.d("Sync", "session.status idle → ${if (shouldReload) "reload" else "no-op"}")
                        if (shouldReload) {
                            callbacks.onRefreshMessages(statusEvent.sessionId, true)
                        }
                    }
                } else {
                    callbacks.onNonFatalIssue("Ignoring invalid session.status payload")
                }
            }
            "message.created" -> {
                // NOTE: server 1.17.11+ does NOT emit message.created (only
                // message.updated / message.part.*). This branch is retained for
                // FORWARD COMPATIBILITY if a future server version adds it; today
                // it is effectively dead code. New messages are surfaced by the
                // message.updated insert-if-absent path above and the
                // session.status busy reload.
                val sessionId = event.payload.getString("sessionID")
                val isCurrent = sessionId != null && sessionId == slices.chat.value.currentSessionId
                DebugLog.i("Sync", "message.created: ${if (isCurrent) "reload current" else "mark unread"}")
                if (sessionId != null && sessionId == slices.chat.value.currentSessionId) {
                    callbacks.onRefreshMessages(sessionId, true)
                } else if (sessionId != null) {
                    // Mark unread: an out-of-band message arrived for a session
                    // the user is not currently viewing.
                    markSessionUnread(sessionId)
                }
            }
            "message.updated" -> {
                // SSE-trust: patch the message metadata in-place from the server's
                // authoritative `info` (mirrors opencode-web server-session.ts:706).
                // Server payload confirmed to carry a full { info: Message } object.
                // We replace the matching Message in the split `messages` store
                // (List<Message>); its parts live separately in partsByMessage and
                // are NOT touched.
                //
                // §cross-client-sync (server 1.17.11+): the server emits
                // `message.updated` (NOT `message.created`) for NEW messages. So
                // when the message id is ABSENT from the local list we INSERT it
                // (append — the list is oldest-first and the new message is the
                // newest). This mirrors the oc-ref web client's
                // patch-if-found + insert-if-absent handler. Subsequent updates for
                // the same id find it in the list and patch in place, so this is a
                // once-per-new-id op (no storm — pure state update, no I/O, no
                // reload). The session.status busy → reload path is the parallel
                // belt-and-suspenders for messages emitted before the local view
                // is even subscribed.
                // Defensive session guard: only touch the current session's view.
                val eventSessionId = event.payload.getString("sessionID")
                if (eventSessionId != null && eventSessionId != slices.chat.value.currentSessionId) return
                val infoJson = event.payload.getJsonObject("info")
                if (infoJson != null) {
                    val updated = runCatching {
                        lenientJson.decodeFromJsonElement<Message>(infoJson)
                    }.getOrNull()
                    if (updated != null && updated.id.isNotEmpty()) {
                        // Single O(n) scan inside the atomic update: `found` is set
                        // during the same `map` pass that builds the replacement
                        // list, so the patch-vs-insert decision and the found flag
                        // come from one atomic pass (no TOCTOU, no second `.none{}`
                        // scan). When found, patch in place; when NOT found, append
                        // (insert) the new message at the tail (oldest-first list).
                        var found = false
                        state.updateAndSync(slices) { s ->
                            val newMessages = s.messages.map { if (it.id == updated.id) { found = true; updated } else it }
                            if (found) s.copy(messages = newMessages) else s.copy(messages = newMessages + updated)
                        }
                        if (found) {
                            DebugLog.d("Sync", "message.updated: patched")
                        } else {
                            DebugLog.i("Sync", "message.updated: inserted (new message, absent from local list)")
                        }
                    }
                }
            }
            "message.part.updated" -> {
                val deltaEvent = parseMessagePartDeltaEvent(event) ?: return
                if (deltaEvent.sessionId == slices.chat.value.currentSessionId) {
                    val msgId = deltaEvent.messageId
                    val pId = deltaEvent.partId
                    if (msgId != null && pId != null) {
                        val key = pId
                        val fullText = deltaEvent.text
                        val delta = deltaEvent.delta
                        if (!fullText.isNullOrBlank()) {
                            // Server sent full accumulated text — use it as the
                            // authoritative streaming value (replaces delta
                            // accumulation, acts as a sync point). §M5: cancel any
                            // pending delta flush so a stale buffered append can't
                            // corrupt the authoritative snapshot after this write.
                            cancelDeltaFlush(key)
                            state.updateAndSync(slices) { s ->
                                s.copy(
                                    streamingPartTexts = s.streamingPartTexts + (key to fullText),
                                    streamingReasoningPart = reasoningPartOrNull(
                                        partType = deltaEvent.partType,
                                        partId = pId,
                                        messageId = msgId,
                                        sessionId = deltaEvent.sessionId
                                    ) ?: s.streamingReasoningPart,
                                    partsByMessage = ensurePlaceholderPart(
                                        s.partsByMessage, msgId, pId, deltaEvent.sessionId, deltaEvent.partType
                                    )
                                )
                            }
                        } else if (!delta.isNullOrBlank()) {
                            // §flicker-fix: apply the same leading-edge +
                            // trailing DELTA_COALESCE_MS coalesce used by the
                            // `message.part.delta` handler. The server emits
                            // `message.part.updated` as the per-token streaming
                            // event (dozens–100s/sec); without coalescing each
                            // one wrote AppState and triggered a Compose
                            // re-parse/recompose, causing streaming jitter.
                            // Leading edge writes immediately (zero-latency
                            // first-token feel) + sets streamingReasoningPart
                            // once; subsequent deltas within the window buffer
                            // into deltaBuffer and flush in one batch.
                            if (flushJobs[key]?.isActive != true) {
                                state.updateAndSync(slices) { s ->
                                    val previousValue = s.streamingPartTexts[key].orEmpty()
                                    s.copy(
                                        streamingPartTexts = s.streamingPartTexts + (key to (previousValue + delta)),
                                        streamingReasoningPart = reasoningPartOrNull(
                                            partType = deltaEvent.partType,
                                            partId = pId,
                                            messageId = msgId,
                                            sessionId = deltaEvent.sessionId
                                        ) ?: s.streamingReasoningPart,
                                        partsByMessage = ensurePlaceholderPart(
                                            s.partsByMessage, msgId, pId, deltaEvent.sessionId, deltaEvent.partType
                                        )
                                    )
                                }
                                scheduleDeltaFlush(key)
                            } else {
                                // Trailing coalesce: buffer; the pending
                                // DELTA_COALESCE_MS flush appends the batch in
                                // one state write. streamingReasoningPart was
                                // already set on this part's leading edge.
                                deltaBuffer.getOrPut(key) { StringBuilder() }.append(delta)
                            }
                        }
                        // Else: ids present + both text & delta null/blank =
                        // part status flip. Do NOT clear streaming buffers and
                        // do NOT reload — a foreground catch-up or the next
                        // message.updated / part.created reconciles if needed.
                    } else {
                        // 闪屏修复：part.created（part 对象只有 type 无 messageID/id）
                        // 在同一回合内每个新 part 都会发出（reasoning→text→tool…），
                        // 并非原先注释假设的"全新回合"。原先这里无条件清空
                        // streamingPartTexts + streamingReasoningPart，导致所有流式
                        // 气泡（reasoning 卡片、正文 text）瞬间坍缩为零高度，下一个
                        // 带 ID 的 part.updated 又重新填充 → 反复闪烁（用户观察：思考
                        // 卡片与正文气泡持续闪屏）。
                        //
                        // 修复：不再手动清空 overlay。reload(resetLimit=false) 本身保留
                        // streamingPartTexts/streamingReasoningPart（见 launchLoadMessages
                        // §append-safe MainViewModelMessageActions.kt:108-109），当前流式
                        // 气泡继续显示不坍缩。仅 clearDeltaBuffers() 丢弃旧 part 的 pending
                        // delta 缓冲（新 part 随后用权威 fullText 恢复，不丢数据）。下一
                        // 个带 ID 的 message.part.updated 正确更新流式状态；回合结束 idle
                        // finalization（resetLimit=true + streamingFinalized）正常清空 overlay。
                        clearDeltaBuffers()
                        callbacks.onRefreshMessages(deltaEvent.sessionId, false)
                    }
                }
            }
            "message.part.delta" -> {
                // Web has an independent `message.part.delta` event (distinct from
                // `message.part.updated`): top-level { sessionID, messageID, partID,
                // field, delta }, field-level incremental append. Without this
                // handler, when the server emits this event the client loses all
                // streaming text. Payload shape per opencode-web server-session.ts.
                //
                // Phase 2 scope (docs/architecture-v3-sse-trust.md §246): only
                // accumulate into streamingPartTexts. Field-level in-place updates
                // on the Part object are deferred (needs field→property mapping).
                // Keyed by bare partId (S4: streamingPartTexts is rekeyed from
                // "msgId:partId" to partId, matching the UI consumers).
                val sessionId = event.payload.getString("sessionID") ?: return
                if (sessionId != slices.chat.value.currentSessionId) return
                // messageID required for a well-formed delta event (validation guard).
                val msgId = event.payload.getString("messageID") ?: return
                val partId = event.payload.getString("partID") ?: return
                // `field` defaults to "text"; it is the type hint for this
                // delta (e.g. "text", "reasoning"). Used as the placeholder
                // partType so a reasoning field-delta routes to ReasoningCard,
                // not a generic text bubble.
                val field = event.payload.getString("field") ?: "text"
                val delta = event.payload.getString("delta")
                if (!delta.isNullOrEmpty()) {
                    val key = partId
                    // §M5 delta coalescing: leading-edge
                    // immediate + trailing 100ms coalesce per partId. The FIRST
                    // delta for a partId writes straight to streamingPartTexts
                    // (zero-latency first-token feel); subsequent deltas within
                    // DELTA_COALESCE_MS are buffered and flushed in one batch,
                    // collapsing per-token Compose recompositions into one-per-
                    // window. Keyed by partId (S4: streamingPartTexts is rekeyed
                    // from "msgId:partId" to partId, matching UI consumers).
                    if (flushJobs[key]?.isActive != true) {
                        // Leading edge: write now, then open the coalesce window.
                        state.updateAndSync(slices) { s ->
                            val previous = s.streamingPartTexts[key].orEmpty()
                            s.copy(
                                streamingPartTexts = s.streamingPartTexts + (key to (previous + delta)),
                                partsByMessage = ensurePlaceholderPart(
                                    s.partsByMessage, msgId, partId, sessionId, field
                                )
                            )
                        }
                        scheduleDeltaFlush(key)
                    } else {
                        // Trailing coalesce: buffer; the pending DELTA_COALESCE_MS
                        // flush will append the batch in one state write.
                        deltaBuffer.getOrPut(key) { StringBuilder() }.append(delta)
                    }
                }
            }
            "permission.asked" -> {
                callbacks.onLoadPendingPermissions()
            }
            "question.asked" -> {
                val question = parseQuestionAskedEvent(event)
                if (question != null) {
                    state.updateAndSync(slices) { currentState ->
                        val existing = currentState.pendingQuestions.any { it.id == question.id }
                        if (!existing) {
                            currentState.copy(pendingQuestions = currentState.pendingQuestions + question)
                        } else {
                            currentState
                        }
                    }
                } else {
                    callbacks.onNonFatalIssue("Ignoring invalid question.asked payload")
                }
            }
            "question.replied", "question.rejected" -> {
                val requestId = event.payload.getString("requestID")
                    ?: event.payload.getString("id")
                if (requestId != null) {
                    state.updateAndSync(slices) { currentState ->
                        currentState.copy(
                            pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId }
                        )
                    }
                }
            }
            "todo.updated" -> {
                val sessionId = event.payload.getString("sessionID") ?: return
                val todosArray = event.payload.properties?.get("todos") as? kotlinx.serialization.json.JsonArray ?: return
                val todos = try {
                    Json.decodeFromJsonElement<List<TodoItem>>(todosArray)
                } catch (_: Exception) {
                    return
                }
                state.updateAndSync(slices) { it.copy(sessionTodos = it.sessionTodos + (sessionId to todos)) }
            }
        }
    }

    /**
     * Mark [sessionId] as having unread activity. Skipped when the session is
     * already the currently-open one (caller guards this) and when the user has
     * never opened it before (we still want the badge in that case, so we only
     * short-circuit on identical selected session, which the caller handles).
     *
     * Verbatim move of the private `markSessionUnread` free function.
     */
    private fun markSessionUnread(sessionId: String) {
        state.updateAndSync(slices) { current ->
            if (sessionId == current.currentSessionId) {
                current
            } else {
                current.copy(unreadSessions = current.unreadSessions + sessionId)
            }
        }
    }

    // ── §M5 delta coalescing helpers ────────────────

    /**
     * Opens (or reopens) the [DELTA_COALESCE_MS] trailing-coalesce window for
     * [partId]. Scheduled on the leading-edge delta; while the launched job is
     * alive, subsequent deltas append to [deltaBuffer] instead of writing.
     */
    private fun scheduleDeltaFlush(partId: String) {
        // Defensive: a stale/completed entry should never coexist with a leading
        // edge (the window self-clears on flush), but cancel anyway to avoid
        // ever having two flush jobs racing for one partId.
        flushJobs[partId]?.cancel()
        flushJobs[partId] = scope.launch {
            delay(DELTA_COALESCE_MS)
            flushDeltaBuffer(partId)
        }
    }

    /**
     * Flushes [partId]'s buffered deltas into [AppState.streamingPartTexts] in
     * a single atomic append (TOCTOU-safe). Self-removes from [flushJobs] /
     * [deltaBuffer]. If the overlay was cleared mid-window (session switch /
     * part.created / ViewModel reset wiped streamingPartTexts), the buffer is
     * dropped — re-injecting stale tokens into the new view would render ghost
     * text from the previous session.
     */
    private fun flushDeltaBuffer(partId: String) {
        flushJobs.remove(partId)
        val buffered = deltaBuffer.remove(partId)
        if (buffered == null || buffered.isEmpty()) return
        // Guard: the leading-edge value must still be present. An intervening
        // overlay clear (session switch / ViewModel reset / global cold-start
        // refresh) means the buffered text is stale → drop, don't append.
        // (闪屏修复后 part.created 不再清空 overlay，故本 guard 对 part.created
        // 场景不再触发；它仍保护 session switch / clear 等其它 overlay-wipe 路径。)
        if (state.value.streamingPartTexts[partId] == null) return
        val text = buffered.toString()
        state.updateAndSync(slices) {
            val previous = it.streamingPartTexts[partId].orEmpty()
            it.copy(streamingPartTexts = it.streamingPartTexts + (partId to (previous + text)))
        }
    }

    /**
     * Cancels [partId]'s pending flush and drops its buffer. Called when an
     * authoritative full-text `message.part.updated` supersedes the streaming
     * accumulation for this partId (so a stale buffered append can't corrupt
     * the snapshot after the authoritative write).
     */
    private fun cancelDeltaFlush(partId: String) {
        flushJobs.remove(partId)?.cancel()
        deltaBuffer.remove(partId)
    }

    /**
     * Drops ALL pending delta buffers and cancels their flush jobs. Called when
     * the whole streaming overlay is wiped (part.created now; session switch /
     * SSE stop / ViewModel clear may be wired by the caller — see
     * §4.2). Safe to call repeatedly.
     */
    fun clearDeltaBuffers() {
        flushJobs.values.forEach { it.cancel() }
        flushJobs.clear()
        deltaBuffer.clear()
    }

    // ── §streaming-render placeholder injection ────────────────

    /**
     * Ensures a minimal placeholder [Part] exists in [partsByMessage] for the
     * given [msgId]/[pId] so a `ChatMessageRow.PartView` is composed immediately
     * and consumes the streaming override (`streamingTextOverride ?: part.text`)
     * while SSE deltas accumulate. The placeholder has `text = null`, so the
     * streaming override wins; when the REST reload later fires it replaces
     * `partsByMessage[msgId]` wholesale, overwriting the placeholder with the
     * real Part — no conflict.
     *
     * No-op when [partsByMessage] already has a Part with `id == pId` for [msgId]
     * (the common case once the first placeholder has been inserted, or once the
     * REST reload has brought the real parts in).
     *
     * Type guard: only `text` and `reasoning` parts are streamed through
     * `streamingPartTexts` and rendered by `PartView`'s `TextPart` /
     * `ReasoningCard`. Other part types (`tool`, `patch`, `file`, `step-start`,
     * `step-finish`, …) are NOT streamed this way — injecting a placeholder for
     * them would misroute in `PartView` (e.g. a `type="tool"` placeholder with
     * `tool=null` renders an empty tool card; `step-*` has no render branch and
     * the streaming text would be orphaned). For those types this is a no-op.
     * They get their real `Part` from the REST reload as before.
     */
    private fun ensurePlaceholderPart(
        partsByMessage: Map<String, List<Part>>,
        msgId: String,
        pId: String,
        sessionId: String,
        partType: String
    ): Map<String, List<Part>> {
        // Only text/reasoning parts stream via streamingPartTexts. Skip
        // placeholder injection for any other type — they would misroute in
        // PartView and the streaming text would never be consumed.
        if (partType != "text" && partType != "reasoning") return partsByMessage
        val existing = partsByMessage[msgId]
        return when {
            existing == null -> partsByMessage + (msgId to listOf(Part(id = pId, messageId = msgId, sessionId = sessionId, type = partType)))
            existing.none { it.id == pId } -> partsByMessage + (msgId to (existing + Part(id = pId, messageId = msgId, sessionId = sessionId, type = partType)))
            else -> partsByMessage
        }
    }

    companion object {
        /**
         * §M5 trailing-coalesce window (§7). Leading-edge
         * delta writes immediately; subsequent deltas within this window are
         * batched into one flush → one Compose recomposition per window instead
         * of one per token.
         */
        private const val DELTA_COALESCE_MS = 100L
    }
}
