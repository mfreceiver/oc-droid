package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.api.NOISY_SSE_LOG_EVENTS
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.UnreadState
import cn.vectory.ocdroid.ui.lenientJson
import cn.vectory.ocdroid.ui.parseMessagePartDeltaEvent
import cn.vectory.ocdroid.ui.parseQuestionAskedEvent
import cn.vectory.ocdroid.ui.parseSessionCreatedEvent
import cn.vectory.ocdroid.ui.parseSessionStatusEvent
import cn.vectory.ocdroid.ui.parseSessionUpdatedEvent
import cn.vectory.ocdroid.ui.reasoningPartOrNull
import cn.vectory.ocdroid.ui.reportNonFatalIssue
import cn.vectory.ocdroid.ui.isStreamablePartType
import cn.vectory.ocdroid.ui.upsertSession
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * R-16 M4 → R-17 batch3b → R-17 batch5: owns the SSE event → slice fold (the
 * SSE-trust dispatch model).
 *
 * **Migration (batch 3b)**: the [SessionSyncCoordinatorCallbacks] interface
 * was eliminated. The cross-domain signals (onServerConnected /
 * onRefreshMessages / onLoadPendingPermissions) emit [ControllerEffect]s on
 * [effects] (rule B). The non-fatal-issue logger was same-domain
 * ([cn.vectory.ocdroid.ui.reportNonFatalIssue] top-level helper) — inlined.
 *
 * **Moved from the orchestrator** (`handleSSEEvent` + the
 * `handleIncomingSseEvent` / `markSessionUnread` free functions): every
 * server-pushed message / session / status / part / permission / question /
 * todo event is folded in-place into the slice flows via
 * `slices.chat.update { ... }` (patch-if-found + insert-if-absent for messages;
 * upsert for sessions; in-place map updates for statuses/todos/questions;
 * streaming overlay for parts). The side effects a fold can trigger
 * (authoritative reload, permission refresh, catch-up) flow through
 * [effects] — so the coordinator never touches the orchestrator, the
 * Repository, or any other controller directly (R-16 §7.3
 * circular-dependency avoidance).
 *
 * §R-17 batch5 (SSE semi-formalization): the per-partId delta coalescing
 * hidden state machine (`deltaBuffer` / `fullTextBuffer` / `pendingFlushPartIds`)
 * has been migrated INTO [ChatState] (immutable Map/Set, CAS updates). Only
 * the coroutine `Job` references ([flushJobs]) remain on the coordinator
 * (a Job is neither serializable nor a value type — it is bound to the
 * coordinator's [scope]). Each of the 11 event branches now calls a pure
 * `applyXxx(...)` extension function that takes the prior slice + event
 * payload and returns the new slice value; side effects (effect emits,
 * settingsManager writes, scope.launch) stay inline in the `when` branches
 * (effect-channel migration is a tracked followup — not in this batch).
 *
 * The coordinator holds NO streaming state of its own other than the
 * per-partId flush [flushJobs]: SSE events are stateless folds over the
 * shared slices, so a single instance follows the orchestrator lifetime and
 * is driven entirely through [handleEvent]. The `server.connected` catch-up
 * trigger is folded in here (one entry point for every event) and routed to
 * the foreground catch-up controller via [ControllerEffect.ServerConnected].
 *
 * §R-17 batch2 step e final: all state writes go through the per-slice
 * `MutableStateFlow.update` helpers (slices are the sole authoritative store).
 *
 * RFC reference: R-16 §B / §M4. Zero behaviour change — the dispatch body is a
 * verbatim move of the pre-extraction `handleIncomingSseEvent`, with the
 * buffer storage migrated to the slice and the per-branch state transforms
 * extracted as pure functions (R-17 batch5).
 */
@Suppress("DEPRECATION")
internal class SessionSyncCoordinator(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
    private val settingsManager: SettingsManager,
    private val effects: SharedEffectBus,
) {
    /** Tag for [reportNonFatalIssue]; mirrors the original MainViewModel TAG. */
    private val tag: String = "SessionSyncCoordinator"
    /**
     * §R-17 batch5: the ONLY coalesce state retained on the coordinator. The
     * Job references are bound to [scope] (a Job is neither serializable nor
     * a value type, so it cannot live in [ChatState]). The observable mirror
     * — which partIds have a pending flush — is [ChatState.pendingFlushPartIds]
     * in the slice; this map is the imperative side that drives
     * `delay(DELTA_COALESCE_MS) → flushDeltaBuffer(partId)`.
     *
     * The two views are kept in lock-step: a leading-edge write adds the
     * partId to [ChatState.pendingFlushPartIds] AND schedules a job here;
     * [flushDeltaBuffer] removes the partId from the slice AND removes the
     * job here; [clearDeltaBuffers] cancels every job here AND wipes the
     * slice's three coalesce fields.
     *
     * **Thread confinement**: Main-thread confined — all access runs on
     * appScope (Dispatchers.Main.immediate). If appScope ever changes to a
     * non-single-threaded dispatcher, this MUST become a ConcurrentHashMap
     * or use @Synchronized.
     */
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
            effects.effects.tryEmit(ControllerEffect.ServerConnected)
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
     * §R-17 batch5: each `when` branch now calls a pure `applyXxx` extension
     * function for the state transform. Side effects (effect emits, settings
     * writes, scheduling) stay inline.
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
                    slices.sessionList.update { s -> s.applySessionCreated(created.session) }
                } else {
                    reportNonFatalIssue(tag, "Ignoring invalid session.created payload")
                }
            }
            "session.updated" -> {
                val updated = parseSessionUpdatedEvent(event)
                if (updated != null) {
                    if (updated.isArchived) {
                        // Archived (typically by another client): evict the
                        // id from the open-tabs list and persist, mirroring
                        // the user-triggered archive path. If the archived
                        // session is the currently-open one, also clear
                        // currentSessionId + messages so the chat view falls
                        // back to the empty state instead of lingering on an
                        // archived session whose tab has disappeared.
                        val chatBefore = slices.chat.value
                        val newOpenIds = slices.sessionList.value.openSessionIds.filter { id -> id != updated.id }
                        if (newOpenIds != slices.sessionList.value.openSessionIds) {
                            settingsManager.openSessionIds = newOpenIds
                        }
                        if (chatBefore.currentSessionId == updated.id) {
                            settingsManager.currentSessionId = null
                            slices.sessionList.update { s -> s.applyArchiveEviction(updated, newOpenIds) }
                            slices.chat.update { it.applyArchivedChatClear() }
                        } else {
                            slices.sessionList.update { s -> s.applyArchiveEviction(updated, newOpenIds) }
                        }
                    } else {
                        slices.sessionList.update { s -> s.applySessionUpsert(updated) }
                    }
                } else {
                    reportNonFatalIssue(tag, "Ignoring invalid session.updated payload")
                }
            }
            "session.status" -> {
                val statusEvent = parseSessionStatusEvent(event)
                if (statusEvent != null) {
                    slices.sessionList.update {
                        it.applySessionStatus(statusEvent.sessionId, statusEvent.status)
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
                        effects.effects.tryEmit(ControllerEffect.LoadMessages(statusEvent.sessionId, true))
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
                        slices.unread.update { u -> u.dropTempCleared(statusEvent.sessionId) }
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
                        val s = slices.chat.value
                        val shouldReload = s.streamingPartTexts.isNotEmpty() || s.streamingReasoningPart != null
                        DebugLog.d("Sync", "session.status idle → ${if (shouldReload) "reload" else "no-op"}")
                        if (shouldReload) {
                            effects.effects.tryEmit(ControllerEffect.LoadMessages(statusEvent.sessionId, true))
                        }
                    }
                } else {
                    reportNonFatalIssue(tag, "Ignoring invalid session.status payload")
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
                    effects.effects.tryEmit(ControllerEffect.LoadMessages(sessionId, true))
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
                        // §R-17 batch5: pure transform returns (newState, found).
                        // Single O(n) scan inside the atomic update — `found` is
                        // set during the same `map` pass that builds the
                        // replacement list, so the patch-vs-insert decision and
                        // the found flag come from one atomic pass (no TOCTOU, no
                        // second `.none{}` scan). When found, patch in place;
                        // when NOT found, append (insert) the new message at the
                        // tail (oldest-first list).
                        var found = false
                        slices.chat.update { c ->
                            val (next, wasFound) = c.applyMessageUpdated(updated)
                            found = wasFound
                            next
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
                        // §user-part-guard (flicker root cause, secondary): the
                        // server emits message.part.updated for the USER message
                        // too — the user input text is reflected as a part event
                        // (type=text). Treating it as streaming assistant output
                        // pollutes streamingPartTexts with a partId that belongs
                        // to the user message and injects a placeholder into the
                        // user bubble, which (a) misleads the assistant's
                        // isStreaming guard and (b) can render echoed text in the
                        // user's own bubble. Only ASSISTANT output streams — skip
                        // parts whose owning message is a user message. The user
                        // message is always inserted before its part event, so
                        // the lookup is reliable; an unknown msgId falls through
                        // (it is the assistant message being born).
                        val ownerIsUser = slices.chat.value.messages.any { it.id == msgId && it.isUser }
                        if (ownerIsUser) return
                        val fullText = deltaEvent.text
                        val delta = deltaEvent.delta
                        // §reasoning-routing-fix (symptom: reasoning rendered as
                        // 正文 / main body text). The server creates a reasoning
                        // part via part.updated with type=reasoning but BLANK text
                        // (full=0) BEFORE streaming its content via part.delta.
                        // The blank creation event used to fall through the
                        // fullText/delta gates below (both require non-blank
                        // content), so the type=reasoning info was LOST. The
                        // subsequent part.delta events then injected a placeholder
                        // using their `field` — which the server sets to "text"
                        // even for reasoning content — so the reasoning part became
                        // type=text and rendered as 正文, and streamingReasoningPart
                        // was never set (no thinking card). Record the part's TRUE
                        // type at creation here (inject correctly-typed placeholder
                        // + set streamingReasoningPart) so content routes to the
                        // standalone thinking card. Idempotent: once the part
                        // exists with the correct type this is a no-op.
                        val pType = deltaEvent.partType
                        if (isStreamablePartType(pType)) {
                            val existingParts = slices.chat.value.partsByMessage[msgId]
                            val hasCorrectType = existingParts?.any { it.id == pId && it.type == pType } == true
                            if (!hasCorrectType) {
                                slices.chat.update { c ->
                                    c.applyPartCreatedPlaceholder(pType, pId, msgId, deltaEvent.sessionId)
                                }
                            }
                        }
                        if (!fullText.isNullOrBlank()) {
                            // Server sent full accumulated text — use it as the
                            // authoritative streaming value (REPLACE, not append;
                            // acts as a sync point). §Site1-coalesce: mirror the
                            // Site2 (delta) leading-edge + trailing
                            // DELTA_COALESCE_MS coalesce pattern. Some Site1
                            // servers emit fullText per-token; without coalescing
                            // each event recomposed. Leading edge writes
                            // immediately (zero-latency first-token feel) + sets
                            // streamingReasoningPart once + ensures the placeholder
                            // part; subsequent fullText events within the window
                            // buffer into fullTextBuffer (REPLACE — fullText is
                            // authoritative accumulated text, only the latest value
                            // matters) and flush in one state write.
                            if (flushJobs[key]?.isActive != true) {
                                slices.chat.update { c ->
                                    c.applyPartFullTextLeadingEdge(
                                        partId = key,
                                        fullText = fullText,
                                        partType = deltaEvent.partType,
                                        pId = pId,
                                        msgId = msgId,
                                        sessionId = deltaEvent.sessionId
                                    ).markFlushPending(key)
                                }
                                scheduleDeltaFlush(key)
                            } else {
                                // Trailing coalesce: buffer the latest fullText
                                // (REPLACE). The pending DELTA_COALESCE_MS flush
                                // writes the buffered fullText in one state write.
                                // streamingReasoningPart was already set on this
                                // part's leading edge.
                                slices.chat.update { c -> c.replaceFullTextBuffer(key, fullText) }
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
                                slices.chat.update { c ->
                                    c.applyPartDeltaLeadingEdge(
                                        partId = key,
                                        delta = delta,
                                        partType = deltaEvent.partType,
                                        pId = pId,
                                        msgId = msgId,
                                        sessionId = deltaEvent.sessionId
                                    ).markFlushPending(key)
                                }
                                scheduleDeltaFlush(key)
                            } else {
                                // Trailing coalesce: buffer; the pending
                                // DELTA_COALESCE_MS flush appends the batch in
                                // one state write. streamingReasoningPart was
                                // already set on this part's leading edge.
                                slices.chat.update { c -> c.appendDeltaBuffer(key, delta) }
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
                        effects.effects.tryEmit(ControllerEffect.LoadMessages(deltaEvent.sessionId, false))
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
                // §user-part-guard (see message.part.updated): only assistant
                // output streams — skip deltas whose owning message is a user
                // message (the server reflects the user input as a part event).
                if (slices.chat.value.messages.any { it.id == msgId && it.isUser }) return
                // `field` defaults to "text"; it is the type hint for this
                // delta (e.g. "text", "reasoning"). Used as the placeholder
                // partType so a reasoning field-delta routes to ReasoningCard,
                // not a generic text bubble.
                val field = event.payload.getString("field") ?: "text"
                val delta = event.payload.getString("delta")
                if (!delta.isNullOrEmpty()) {
                    val key = partId
                    // §reasoning-routing-fix: prefer the part's KNOWN type (set by
                    // the part.updated creation event) over the delta's `field` —
                    // the server sends field="text" even for reasoning content.
                    val knownType = slices.chat.value.partsByMessage[msgId]
                        ?.firstOrNull { it.id == partId }?.type ?: field
                    // Only streamable types (text/reasoning) are rendered via
                    // streamingPartTexts; a non-streamable type's delta would
                    // accumulate dead overlay text never consumed by PartView.
                    if (!isStreamablePartType(knownType)) return
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
                        slices.chat.update { c ->
                            c.applyPartDeltaLeadingEdge(
                                partId = key,
                                delta = delta,
                                knownType = knownType,
                                msgId = msgId,
                                sessionId = sessionId
                            ).markFlushPending(key)
                        }
                        scheduleDeltaFlush(key)
                    } else {
                        // Trailing coalesce: buffer; the pending DELTA_COALESCE_MS
                        // flush will append the batch in one state write.
                        slices.chat.update { c -> c.appendDeltaBuffer(key, delta) }
                    }
                }
            }
            "permission.asked" -> {
                effects.effects.tryEmit(ControllerEffect.LoadPendingPermissions)
            }
            "question.asked" -> {
                val question = parseQuestionAskedEvent(event)
                if (question != null) {
                    slices.sessionList.update { currentState -> currentState.applyQuestionAsked(question) }
                } else {
                    reportNonFatalIssue(tag, "Ignoring invalid question.asked payload")
                }
            }
            "question.replied", "question.rejected" -> {
                val requestId = event.payload.getString("requestID")
                    ?: event.payload.getString("id")
                if (requestId != null) {
                    slices.sessionList.update { currentState -> currentState.applyQuestionResolved(requestId) }
                }
            }
            "todo.updated" -> {
                val sessionId = event.payload.getString("sessionID") ?: return
                val todosArray = event.payload.properties?.get("todos") as? kotlinx.serialization.json.JsonArray ?: return
                val todos = try {
                    Json.decodeFromJsonElement<List<TodoItem>>(todosArray)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportNonFatalIssue(tag, "Ignoring invalid todo.updated payload: ${e.message}")
                    return
                }
                slices.sessionList.update { s -> s.applyTodoUpdated(sessionId, todos) }
            }
            "session.error" -> {
                // §error-feedback (Issue 4): the server emits session.error with
                // payload { sessionID, error: { name, data: { message, statusCode } } }
                // for rate-limit / quota / provider failures. Two surfaces:
                //   (1) a UiEvent.Error toast (always — the user must know)
                //   (2) attach the error to the current session's last assistant
                //       message (if any non-error one exists) so ErrorCard can
                //       render inline. Error-only turns (error set, no renderable
                //       parts) are otherwise filtered out before render.
                val errObj = event.payload.getJsonObject("error")
                val name = (errObj?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content
                val data = errObj?.get("data") as? JsonObject
                val rawMsg = (data?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: (data?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: "Server session error"
                val composed = if (!name.isNullOrBlank()) "$name: $rawMsg" else rawMsg
                effects.uiEvents.tryEmit(UiEvent.Error(composed))
                val sid = event.payload.getString("sessionID")
                if (sid != null && sid == slices.chat.value.currentSessionId) {
                    slices.chat.update { c ->
                        val lastAssistant = c.messages.lastOrNull { it.isAssistant }
                        if (lastAssistant == null || lastAssistant.error != null) c
                        else c.copy(messages = c.messages.map { m ->
                            if (m.id == lastAssistant.id) m.copy(error = Message.MessageError(name = name, data = data)) else m
                        })
                    }
                }
            }
            else -> {
                // §error-feedback (Issue 4): only warn about genuinely unknown event
                // types. Types in NOISY_SSE_LOG_EVENTS (server.connected, plugin.added,
                // catalog.updated, integration.updated, server.heartbeat) are known-
                // intentional non-dispatched events, not surprises — skip the warning
                // to avoid log noise on every reconnect.
                if (!noisy) {
                    DebugLog.w(tag, "Unhandled SSE event type: ${event.payload.type}")
                }
            }
        }
    }

    /**
     * Mark [sessionId] as having unread activity. Skipped when the session is
     * already the currently-open one (caller guards this) and when the user has
     * never opened it before (we still want the badge in that case, so we only
     * short-circuit on identical selected session, which the caller handles).
     *
     * §R-17 batch5: the state transform is the pure [UnreadState.applyMarkSessionUnread]
     * extension; this wrapper reads [SliceFlows.chat] for the current-session
     * guard and writes [SliceFlows.unread].
     */
    private fun markSessionUnread(sessionId: String) {
        val currentSessionId = slices.chat.value.currentSessionId
        slices.unread.update { u -> u.applyMarkSessionUnread(sessionId, currentSessionId) }
    }

    // ── §M5 delta coalescing helpers ────────────────

    /**
     * Opens (or reopens) the [DELTA_COALESCE_MS] trailing-coalesce window for
     * [partId]. Scheduled on the leading-edge delta; while the launched job is
     * alive, subsequent deltas append to [ChatState.deltaBuffer] instead of
     * writing streamingPartTexts. The Job reference is held in [flushJobs]
     * (NOT in the slice — a Job is not a value type); the observable mirror is
     * [ChatState.pendingFlushPartIds], set by [ChatState.markFlushPending] on
     * the leading edge and cleared by [flushDeltaBuffer] / [clearDeltaBuffers].
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
     * Flushes [partId]'s buffered deltas/fullText into the chat slice's
     * streamingPartTexts in a single atomic write (TOCTOU-safe). Self-removes
     * the partId from [ChatState.pendingFlushPartIds] and from [flushJobs].
     * If the overlay was cleared mid-window (session switch / part.created /
     * ViewModel reset wiped streamingPartTexts), the buffer is dropped —
     * re-injecting stale tokens into the new view would render ghost text from
     * the previous session.
     *
     * §Site1-coalesce: a buffered fullText wins (REPLACE) — it is the server's
     * authoritative accumulated text and supersedes any concurrent delta
     * accumulation for this partId. It is checked BEFORE the delta; if
     * present, streamingPartTexts[partId] is overwritten with the buffered
     * value (REPLACE, not append) and the entry cleared. Only when no fullText
     * is buffered does the delta APPEND path run.
     *
     * §R-17 batch5: the state transform is the pure
     * [ChatState.flushCoalesceBufferForPart] extension; this wrapper only
     * owns the [flushJobs] entry (Job lifecycle, scope-bound — not in the
     * slice).
     */
    private fun flushDeltaBuffer(partId: String) {
        flushJobs.remove(partId)
        slices.chat.update { c -> c.flushCoalesceBufferForPart(partId) }
    }

    /**
     * Cancels [partId]'s pending flush and drops its buffers (both delta APPEND
     * and fullText REPLACE) in the slice. Kept for callers that need to
     * supersede the streaming accumulation for a partId with an authoritative
     * snapshot outside the coalesce path; the §Site1-coalesce fullText branch
     * no longer calls this (it lets the leading-edge + trailing pattern handle
     * it), but the helper is retained for correctness/future use.
     */
    @Suppress("unused")
    private fun cancelDeltaFlush(partId: String) {
        flushJobs.remove(partId)?.cancel()
        slices.chat.update { c -> c.clearCoalesceBufferForPart(partId) }
    }

    /**
     * Drops ALL pending delta/fullText buffers, cancels their flush jobs, and
     * clears [ChatState.pendingFlushPartIds]. Called when the whole streaming
     * overlay is wiped (part.created now; session switch / SSE stop /
     * ViewModel clear may be wired by the caller — see §4.2). Safe to call
     * repeatedly.
     */
    fun clearDeltaBuffers() {
        flushJobs.values.forEach { it.cancel() }
        flushJobs.clear()
        slices.chat.update { c -> c.clearAllCoalesceBuffers() }
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

// ── §R-17 batch5: pure state transforms for each SSE event branch ────────
//
// Each function takes the prior slice value + the event payload and returns
// the new slice value with NO side effects (no effect emits, no coroutine
// launches, no settings writes, no DebugLog). Side effects stay inline in
// SessionSyncCoordinator.dispatchSseEvent's `when` branches. This is the
// semi-formalization boundary: the state math is now unit-testable in
// isolation, while the effect-channel migration (full reducer + effect
// bus) is tracked as a followup.

/**
 * session.created → upsert the parsed [Session] into [SessionListState.sessions].
 * Pure.
 */
internal fun SessionListState.applySessionCreated(session: Session): SessionListState =
    copy(sessions = upsertSession(sessions, session))

/**
 * session.updated (non-archived) → upsert the parsed [Session] into
 * [SessionListState.sessions]. Pure.
 */
internal fun SessionListState.applySessionUpsert(updated: Session): SessionListState =
    copy(sessions = upsertSession(sessions, updated))

/**
 * session.updated (archived) → upsert the session AND rewrite [openSessionIds]
 * to the caller-supplied [newOpenIds] (with the archived id evicted). The
 * caller computes [newOpenIds] + persists it via SettingsManager (a side
 * effect that stays inline). Pure.
 */
internal fun SessionListState.applyArchiveEviction(
    updated: Session,
    newOpenIds: List<String>
): SessionListState = copy(
    sessions = upsertSession(sessions, updated),
    openSessionIds = newOpenIds
)

/**
 * The chat-side archive clear when the archived session IS the currently-open
 * one: drop currentSessionId + messages + partsByMessage so the chat view
 * falls back to the empty state. Pure.
 */
internal fun ChatState.applyArchivedChatClear(): ChatState = copy(
    currentSessionId = null,
    messages = emptyList(),
    partsByMessage = emptyMap()
)

/**
 * session.status → upsert the [sessionId] → [status] pair into
 * [SessionListState.sessionStatuses]. Pure; the busy-current / idle-current
 * / temp-cleared finalization side effects stay inline in the dispatcher.
 */
internal fun SessionListState.applySessionStatus(
    sessionId: String,
    status: SessionStatus
): SessionListState = copy(sessionStatuses = sessionStatuses + (sessionId to status))

/**
 * message.updated → patch-if-found / insert-if-absent [updated] into
 * [ChatState.messages]. Returns the new state AND a `found` flag (so the
 * caller can log patch vs. insert without a second O(n) scan). Single O(n)
 * atomic pass — no TOCTOU. Pure.
 *
 * When [found] is false, the new message is appended at the tail (the list
 * is oldest-first and the new message is the newest — mirrors opencode-web).
 */
internal fun ChatState.applyMessageUpdated(updated: Message): Pair<ChatState, Boolean> {
    var found = false
    val newMessages = messages.map { if (it.id == updated.id) { found = true; updated } else it }
    val finalMessages = if (found) newMessages else newMessages + updated
    return copy(messages = finalMessages) to found
}

/**
 * message.part.updated (blank reasoning creation / type-correct placeholder) →
 * inject a [Part] of [partType] into [ChatState.partsByMessage][msgId] AND
 * set [ChatState.streamingReasoningPart] when [partType] == "reasoning".
 * Idempotent — once a Part of the correct type exists this is a no-op (caller
 * guards `hasCorrectType`, but this is defensive: it filters out any stale
 * wrong-typed placeholder first). Pure.
 */
internal fun ChatState.applyPartCreatedPlaceholder(
    partType: String,
    partId: String,
    msgId: String,
    sessionId: String
): ChatState {
    val base = partsByMessage[msgId]?.filterNot { it.id == partId } ?: emptyList()
    return copy(
        streamingReasoningPart = reasoningPartOrNull(partType, partId, msgId, sessionId)
            ?: streamingReasoningPart,
        partsByMessage = partsByMessage + (msgId to base + Part(
            id = partId, messageId = msgId, sessionId = sessionId, type = partType
        ))
    )
}

/**
 * Leading-edge fullText write: REPLACE [ChatState.streamingPartTexts][partId]
 * with [fullText] (authoritative accumulated text), set
 * [ChatState.streamingReasoningPart] if [partType] == "reasoning", and ensure
 * the placeholder [Part] exists. Pure.
 *
 * Caller follows up with [markFlushPending] + [scheduleDeltaFlush] (the Job
 * scheduling is a side effect that stays on the coordinator).
 */
internal fun ChatState.applyPartFullTextLeadingEdge(
    partId: String,
    fullText: String,
    partType: String,
    pId: String,
    msgId: String,
    sessionId: String
): ChatState = copy(
    streamingPartTexts = streamingPartTexts + (partId to fullText),
    streamingReasoningPart = reasoningPartOrNull(
        partType = partType,
        partId = pId,
        messageId = msgId,
        sessionId = sessionId
    ) ?: streamingReasoningPart,
    partsByMessage = ensurePlaceholderPart(partsByMessage, msgId, pId, sessionId, partType)
)

/**
 * Leading-edge delta write: APPEND [delta] to the prior
 * [ChatState.streamingPartTexts][partId] value, set
 * [ChatState.streamingReasoningPart] if the resolved type is "reasoning", and
 * ensure the placeholder [Part] exists. Pure.
 *
 * Caller follows up with [markFlushPending] + [scheduleDeltaFlush] (the Job
 * scheduling is a side effect that stays on the coordinator).
 */
internal fun ChatState.applyPartDeltaLeadingEdge(
    partId: String,
    delta: String,
    knownType: String,
    msgId: String,
    sessionId: String
): ChatState {
    val previous = streamingPartTexts[partId].orEmpty()
    return copy(
        streamingPartTexts = streamingPartTexts + (partId to (previous + delta)),
        streamingReasoningPart = reasoningPartOrNull(knownType, partId, msgId, sessionId)
            ?: streamingReasoningPart,
        partsByMessage = ensurePlaceholderPart(partsByMessage, msgId, partId, sessionId, knownType)
    )
}

/** Variant of [applyPartDeltaLeadingEdge] taking the part.updated partType +
 *  explicit pId (used by the message.part.updated delta branch where the
 *  known-type lookup hasn't run yet). Pure. */
internal fun ChatState.applyPartDeltaLeadingEdge(
    partId: String,
    delta: String,
    partType: String,
    pId: String,
    msgId: String,
    sessionId: String
): ChatState {
    val previous = streamingPartTexts[partId].orEmpty()
    return copy(
        streamingPartTexts = streamingPartTexts + (partId to (previous + delta)),
        streamingReasoningPart = reasoningPartOrNull(
            partType = partType,
            partId = pId,
            messageId = msgId,
            sessionId = sessionId
        ) ?: streamingReasoningPart,
        partsByMessage = ensurePlaceholderPart(partsByMessage, msgId, pId, sessionId, partType)
    )
}

/**
 * Trailing-coalesce delta APPEND: append [delta] to the slice's
 * [ChatState.deltaBuffer][partId] (the buffer is now a `Map<String, String>`,
 * so the append is a read-modify-write under the slice's CAS `update`).
 * Pure. */
internal fun ChatState.appendDeltaBuffer(partId: String, delta: String): ChatState {
    val current = deltaBuffer[partId].orEmpty()
    return copy(deltaBuffer = deltaBuffer + (partId to (current + delta)))
}

/**
 * Trailing-coalesce fullText REPLACE: overwrite [ChatState.fullTextBuffer][partId]
 * with [text] (REPLACE — fullText is the server-authoritative accumulated
 * text; only the latest value per partId matters). Pure.
 */
internal fun ChatState.replaceFullTextBuffer(partId: String, text: String): ChatState =
    copy(fullTextBuffer = fullTextBuffer + (partId to text))

/**
 * Marks [partId] as having a pending flush (the slice-side mirror of the
 * coordinator's [SessionSyncCoordinator.flushJobs] entry). Set on the
 * leading-edge write so an observer of the slice can detect "deltas still
 * buffered" without needing access to the Job. Pure.
 */
internal fun ChatState.markFlushPending(partId: String): ChatState =
    if (partId in pendingFlushPartIds) this
    else copy(pendingFlushPartIds = pendingFlushPartIds + partId)

/**
 * Flushes [partId]'s buffered delta/fullText into [ChatState.streamingPartTexts]
 * in a single atomic pure transform, then clears that partId's three coalesce
 * entries ([deltaBuffer] / [fullTextBuffer] / [pendingFlushPartIds]).
 *
 * §Site1-coalesce: a buffered fullText wins (REPLACE) over a buffered delta
 * (APPEND), and is checked FIRST. If the overlay was wiped mid-window
 * (`streamingPartTexts[partId] == null`), both buffers are dropped (stale;
 * re-injecting would render ghost text from the previous session).
 *
 * Mirrors the verbatim semantics of the pre-batch5 imperative
 * `flushDeltaBuffer` (the only change is storage location). Pure.
 */
internal fun ChatState.flushCoalesceBufferForPart(partId: String): ChatState {
    val overlayPresent = streamingPartTexts[partId] != null
    // REPLACE path: fullText wins outright when the overlay still exists.
    val bufferedFullText = fullTextBuffer[partId]
    if (overlayPresent && bufferedFullText != null) {
        // The authoritative fullText supersedes any concurrent delta
        // accumulation for this partId; drop the stale buffered delta so
        // it can't be appended in a later coalesce window.
        return copy(
            streamingPartTexts = streamingPartTexts + (partId to bufferedFullText),
            deltaBuffer = deltaBuffer - partId,
            fullTextBuffer = fullTextBuffer - partId,
            pendingFlushPartIds = pendingFlushPartIds - partId
        )
    }
    // If the overlay was wiped mid-window, drop the buffered fullText (stale).
    // Otherwise keep fullTextBuffer intact (no fullText buffered for this
    // partId; the entry was already absent).
    val fullTextBufferAfter = if (overlayPresent) fullTextBuffer else fullTextBuffer - partId
    val bufferedDelta = deltaBuffer[partId]
    if (bufferedDelta.isNullOrEmpty()) {
        return copy(
            fullTextBuffer = fullTextBufferAfter - partId,
            deltaBuffer = deltaBuffer - partId,
            pendingFlushPartIds = pendingFlushPartIds - partId
        )
    }
    // Overlay wiped mid-window → drop stale buffered delta (re-injecting
    // would render ghost text from the previous session).
    if (!overlayPresent) {
        return copy(
            fullTextBuffer = fullTextBufferAfter - partId,
            deltaBuffer = deltaBuffer - partId,
            pendingFlushPartIds = pendingFlushPartIds - partId
        )
    }
    // APPEND path: append the buffered delta to the overlay.
    val previous = streamingPartTexts[partId].orEmpty()
    return copy(
        streamingPartTexts = streamingPartTexts + (partId to (previous + bufferedDelta)),
        fullTextBuffer = fullTextBufferAfter - partId,
        deltaBuffer = deltaBuffer - partId,
        pendingFlushPartIds = pendingFlushPartIds - partId
    )
}

/**
 * Drops [partId]'s three coalesce entries without flushing (the buffers are
 * discarded, NOT applied to streamingPartTexts). The slice-side companion of
 * [SessionSyncCoordinator.cancelDeltaFlush]. Pure.
 */
internal fun ChatState.clearCoalesceBufferForPart(partId: String): ChatState = copy(
    deltaBuffer = deltaBuffer - partId,
    fullTextBuffer = fullTextBuffer - partId,
    pendingFlushPartIds = pendingFlushPartIds - partId
)

/**
 * Drops ALL coalesce entries (deltaBuffer / fullTextBuffer /
 * pendingFlushPartIds). Does NOT touch streamingPartTexts /
 * streamingReasoningPart — the overlay clear is a separate concern (the
 * §闪屏修复 made part.created preserve the overlay; only the buffers are
 * dropped). The slice-side companion of
 * [SessionSyncCoordinator.clearDeltaBuffers]. Pure.
 */
internal fun ChatState.clearAllCoalesceBuffers(): ChatState = copy(
    deltaBuffer = emptyMap(),
    fullTextBuffer = emptyMap(),
    pendingFlushPartIds = emptySet()
)

/**
 * question.asked → append [question] to [SessionListState.pendingQuestions]
 * iff its id is not already present (idempotent). Pure.
 */
internal fun SessionListState.applyQuestionAsked(question: QuestionRequest): SessionListState {
    val existing = pendingQuestions.any { it.id == question.id }
    return if (existing) this else copy(pendingQuestions = pendingQuestions + question)
}

/**
 * question.replied / question.rejected → drop the pending question whose id
 * matches [requestId]. Pure.
 */
internal fun SessionListState.applyQuestionResolved(requestId: String): SessionListState =
    copy(pendingQuestions = pendingQuestions.filter { it.id != requestId })

/**
 * todo.updated → upsert [todos] under [sessionId] in
 * [SessionListState.sessionTodos]. Pure.
 */
internal fun SessionListState.applyTodoUpdated(
    sessionId: String,
    todos: List<TodoItem>
): SessionListState = copy(sessionTodos = sessionTodos + (sessionId to todos))

/**
 * message.created (out-of-band) → mark [sessionId] unread IF it is not the
 * currently-open session. Caller passes [currentSessionId] so this stays
 * pure (no slice read). Pure.
 */
internal fun UnreadState.applyMarkSessionUnread(
    sessionId: String,
    currentSessionId: String?
): UnreadState = if (sessionId == currentSessionId) this
else copy(unreadSessions = unreadSessions + sessionId)

/**
 * session.status idle finalization (non-current temp-cleared session) → drop
 * [sessionId] from [UnreadState.tempClearedUnread]. Pure.
 */
internal fun UnreadState.dropTempCleared(sessionId: String): UnreadState =
    copy(tempClearedUnread = tempClearedUnread - sessionId)

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
internal fun ensurePlaceholderPart(
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

/**
 * §R-17 batch5: helper for tests / future state inspectors — is the SSE
 * coalesce window currently open for [partId]? Pure read on [ChatState].
 */
internal fun ChatState.isFlushPending(partId: String): Boolean =
    partId in pendingFlushPartIds
