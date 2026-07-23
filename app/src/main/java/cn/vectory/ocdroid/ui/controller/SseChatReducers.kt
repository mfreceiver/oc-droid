package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.MESSAGE_CHRONO
import cn.vectory.ocdroid.ui.StreamOwnedState
import cn.vectory.ocdroid.ui.chronological
import cn.vectory.ocdroid.ui.reasoningPartOrNull

/**
 * Pure file-level chat / message / stream-buffer / coalesce reducers,
 * relocated verbatim from [SessionSyncCoordinator] (Wave 1'-B lane L1a).
 *
 * PURE RELOCATION — no logic or signature changes. Same package
 * (`cn.vectory.ocdroid.ui.controller`) so callers need no import edits;
 * [SessionSyncCoordinator] keeps the orchestration + token-gate call sites.
 */

/**
 * The chat-side archive clear when the archived session IS the currently-open
 * one: drop currentSessionId + messages + partsByMessage so the chat view
 * falls back to the empty state. Pure; effects empty.
 *
 * FIX-B (review-blocker, groker B3): also clears the unified scroll slot +
 * parent-return backstack (added by WT2 / §Wave5b-Q13). The clearSessionData
 * path already clears them, but this archive-clear path was missed → a
 * pending scroll intent / checkpoint could stick if the session was archived
 * between the intent being set and consumed. Now the slot + backstack are
 * wiped atomically with the rest of the chat clear (one committed state, no
 * torn "session archived but scroll intent still references it").
 */
internal fun ChatState.applyArchivedChatClear(): Pair<ChatState, List<SseSideEffect>> = copy(
    currentSessionId = null,
    messages = emptyList(),
    partsByMessage = emptyMap(),
    // §slimapi-client-v1 §G6 (Task 16 round-2): clear per-part expand states
    // on archived-chat clear. Matches the partsByMessage clear above.
    partExpandStates = emptyMap(),
    // FIX-B / §Wave5b-Q13: clear the unified scroll slot + parent-return
    // backstack — a pending scroll / checkpoint for an archived session is
    // meaningless.
    pendingScrollRequest = null,
    parentReturnCheckpoints = emptyMap(),
) to emptyList()

/**
 * §Wave5b-Q13 blocker-2 fix (gpter 8.7 FAIL / groker 9.6 PASS): UNCONDITIONAL
 * scroll-state cleanup for an archived subtree. Used by the THREE archive
 * paths ([AppAction.SessionArchived] reducer, [AppAction.BulkSessionsRefreshed]
 * reducer, [launchSetSessionArchived] onSuccess) so a non-current archived
 * session/child can no longer leave stale scroll state behind.
 *
 * Cleans (preserving all chat CONTENT — messages/parts/streaming/etc. — which
 * remains current-only-cleared by [applyArchivedChatClear]):
 *  - [ChatState.pendingScrollRequest] → null IFF its targetSessionId is in
 *    [subtree] (a pending Latest/Restore for an archived session will never
 *    fire correctly; the consumer would skip it on the next switch anyway,
 *    but leaving it risks a stale fire if the user re-opens the same id
 *    later via a fresh create).
 *  - [ChatState.parentReturnCheckpoints] → filter out every entry whose key
 *    (childId) is in [subtree]. A returnToParent from an archived child is
 *    unreachable (the user cannot navigate to an archived session), but the
 *    stale entry would otherwise leak indefinitely in the map.
 *
 * Pure; effects empty. Callers MUST already have computed [subtree] via
 * [subtreeIds] (the SAME three-source union used for unread/questions
 * cleanup) — no second subtree walk here.
 *
 * Idempotent: safe to call when the slot is already null / the map already
 * has no entries in [subtree] (the operations are no-ops in that case).
 * Safe to compose with [applyArchivedChatClear] for the current-archived
 * case: applyArchivedChatClear wipes BOTH fields unconditionally, so a
 * subsequent call to this helper is a no-op.
 */
internal fun ChatState.cleanScrollStateForSubtree(
    subtree: Set<String>,
): ChatState {
    if (subtree.isEmpty()) return this
    val cleanSlot = pendingScrollRequest
        ?.takeUnless { it.targetSessionId in subtree }
    val cleanCheckpoints = if (parentReturnCheckpoints.isEmpty()) {
        parentReturnCheckpoints
    } else {
        parentReturnCheckpoints.filterKeys { it !in subtree }
    }
    // Skip the .copy() allocation entirely if neither field would change
    // (hot path: archive of an unrelated subtree on a chat with no scroll
    // state — common during bulk refreshes).
    if (cleanSlot === pendingScrollRequest && cleanCheckpoints === parentReturnCheckpoints) {
        return this
    }
    return copy(
        pendingScrollRequest = cleanSlot,
        parentReturnCheckpoints = cleanCheckpoints,
    )
}

/**
 * message.updated → patch-if-found / insert-if-absent [updated] into
 * [ChatState.messages]. Returns the new state AND a `found` flag (so the
 * caller can log patch vs. insert without a second O(n) scan). Single O(n)
 * atomic pass — no TOCTOU. Pure.
 *
 * When [found] is false, the new message is inserted in chronological order
 * using [MESSAGE_CHRONO] (binarySearch). Exception: null-created messages
 * (time?.created == null) are always appended at the tail (the list is
 * oldest-first and the new message is the newest — mirrors opencode-web).
 */
internal fun ChatState.applyMessageUpdated(updated: Message): Pair<ChatState, Boolean> {
    val idx = messages.indexOfFirst { it.id == updated.id }
    if (idx >= 0) {
        // Patch path: message found
        val old = messages[idx]
        if (old.time?.created == updated.time?.created) {
            // Created timestamp unchanged → keep position, just patch content
            val newMessages = messages.toMutableList().also { it[idx] = updated }
            return copy(messages = newMessages) to true
        }
        // Created timestamp changed → remove old, re-insert chronologically
        val remaining = messages.toMutableList().also { it.removeAt(idx) }
        // For null-created, always append at tail (optimistic insert)
        if (updated.time?.created == null) {
            return copy(messages = remaining + updated) to true
        }
        // Insert using chronological comparator
        val insertAt = remaining.binarySearch { MESSAGE_CHRONO.compare(it, updated) }
        val insertIdx = if (insertAt < 0) -(insertAt + 1) else insertAt
        val newMessages = remaining.toMutableList().also { it.add(insertIdx, updated) }
        return copy(messages = newMessages) to true
    } else {
        // Insert path: message absent
        // For null-created, always append at tail
        if (updated.time?.created == null) {
            return copy(messages = messages + updated) to false
        }
        if (messages.isEmpty() || MESSAGE_CHRONO.compare(messages.last(), updated) <= 0) {
            // Append at end (common case)
            return copy(messages = messages + updated) to false
        }
        // Find insertion point
        val insertAt = messages.binarySearch { MESSAGE_CHRONO.compare(it, updated) }
        val insertIdx = if (insertAt < 0) -(insertAt + 1) else insertAt
        val newMessages = messages.toMutableList().also { it.add(insertIdx, updated) }
        return copy(messages = newMessages) to false
    }
}

/**
 * T1b pure extract of the pre-T1b [SessionSyncCoordinator.mergeSlimMessagesIntoChat]
 * private loop (patch-if-found + insert-if-absent for messages; parts map
 * overwritten per fetched id when parts non-empty). Empty-id items are skipped.
 *
 * §Stage-B §3.4 (splice/merge + single-owner contract):
 *  - [authoritative]=false (default / skeleton): for each fetched part `f`,
 *    if a LOCAL part with the same id is currently token-stream-owned
 *    (`streamOwned[f.id] == STREAMING`), the LOCAL part is substituted in
 *    place of the fetched skeleton (the streamed text in
 *    `streamingPartTexts` is the live source of truth; the server skeleton
 *    carries text=""). Locally-owned parts NOT present in the fetched set
 *    are PRESERVED (preservedLocal). streamOwned / streamingPartTexts are
 *    left untouched (the token stream still owns them).
 *  - [authoritative]=true (resync / watchdog / forced): fetched parts
 *    ALWAYS win — owned parts are NOT substituted. After the loop, any
 *    fetched part id that WAS in streamOwned is cleared from both
 *    streamOwned and streamingPartTexts (the fetched content is now the
 *    authoritative final text).
 *
 * # opus MF-A regression guard (byte-for-byte parity when streamOwned is empty)
 *
 * When `streamOwned` is empty (all non-token-stream slim users), the
 * substitution guard (`newOwned[f.id] == STREAMING`) is never true and
 * `preservedLocal` is always empty. The result is therefore byte-for-byte
 * identical to the legacy `partsByMessage + (id to item.parts)` full-
 * overwrite. This guarantees ZERO behavioral regression for existing slim
 * users until the token-stream path (Stage C/D) actively populates
 * streamOwned.
 */
internal fun ChatState.mergeSlimMessages(
    items: List<MessageWithParts>,
    authoritative: Boolean = false,
): ChatState {
    if (items.isEmpty()) return this
    // Collect ids of patched items for O(1) exclusion
    val patchedIds = mutableSetOf<String>()
    val patched = mutableListOf<Message>()
    val absent = mutableListOf<Message>()
    var partsByMessage = this.partsByMessage
    var newOwned = this.streamOwned
    var newSpt = this.streamingPartTexts
    val cleared = mutableSetOf<String>()
    for (item in items) {
        val updated = item.info
        if (updated.id.isEmpty()) continue
        // Check if this id exists in current messages (O(n) but typically small;
        // could use index if n grows, but mergeSlimMessages is bounded by chunk size)
        if (messages.indexOfFirst { it.id == updated.id } >= 0) {
            patchedIds.add(updated.id)
            patched.add(updated)
        } else {
            absent.add(updated)
        }
        if (item.parts.isNotEmpty()) {
            val local = partsByMessage[updated.id] ?: emptyList()
            val fetchedIds = item.parts.map { it.id }.toSet()
            // §Stage-B: substitute local part only when !authoritative AND the
            // fetched part is currently token-stream-owned (STREAMING) — keep
            // the streamed text, drop the server skeleton (text="").
            val merged = item.parts.map { f ->
                if (!authoritative && newOwned[f.id] == StreamOwnedState.STREAMING) {
                    local.firstOrNull { it.id == f.id } ?: f
                } else {
                    f
                }
            }
            // §opus MF-A: preservedLocal keeps locally-owned parts that are
            // NOT in the fetched set (an in-flight token-stream part the
            // server snapshot hasn't caught up to). When streamOwned is empty
            // this is always empty → byte-for-byte legacy parity.
            val preservedLocal = local.filter { lp ->
                lp.id !in fetchedIds && newOwned[lp.id] == StreamOwnedState.STREAMING
            }
            partsByMessage = partsByMessage + (updated.id to (merged + preservedLocal))
            // §Stage-B: on authoritative, collect fetched ids that were owned
            // so their ownership state can be cleared after the loop.
            if (authoritative) {
                cleared += fetchedIds.filter { it in newOwned }
            }
        }
    }
    if (cleared.isNotEmpty()) {
        newOwned = newOwned - cleared
        newSpt = newSpt - cleared
    }
    // Preserve messages not in patchedIds
    val preserved = messages.filterNot { it.id in patchedIds }
    // Merge preserved + patched + absent, then chronological sort
    val mergedMessages = (preserved + patched + absent).chronological()
    return copy(
        messages = mergedMessages,
        partsByMessage = partsByMessage,
        streamOwned = newOwned,
        streamingPartTexts = newSpt,
    )
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
): Pair<ChatState, List<SseSideEffect>> {
    val base = partsByMessage[msgId]?.filterNot { it.id == partId } ?: emptyList()
    return copy(
        streamingReasoningPart = reasoningPartOrNull(partType, partId, msgId, sessionId)
            ?: streamingReasoningPart,
        partsByMessage = partsByMessage + (msgId to base + Part(
            id = partId, messageId = msgId, sessionId = sessionId, type = partType
        ))
    ) to emptyList()
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
): Pair<ChatState, List<SseSideEffect>> = copy(
    streamingPartTexts = streamingPartTexts + (partId to fullText),
    streamingReasoningPart = reasoningPartOrNull(
        partType = partType,
        partId = pId,
        messageId = msgId,
        sessionId = sessionId
    ) ?: streamingReasoningPart,
    partsByMessage = ensurePlaceholderPart(partsByMessage, msgId, pId, sessionId, partType)
) to emptyList()

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
): Pair<ChatState, List<SseSideEffect>> {
    val previous = streamingPartTexts[partId].orEmpty()
    return copy(
        streamingPartTexts = streamingPartTexts + (partId to (previous + delta)),
        streamingReasoningPart = reasoningPartOrNull(knownType, partId, msgId, sessionId)
            ?: streamingReasoningPart,
        partsByMessage = ensurePlaceholderPart(partsByMessage, msgId, partId, sessionId, knownType)
    ) to emptyList()
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
): Pair<ChatState, List<SseSideEffect>> {
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
    ) to emptyList()
}

/**
 * Trailing-coalesce delta APPEND: append [delta] to the slice's
 * [ChatState.deltaBuffer][partId] (the buffer is now a `Map<String, String>`,
 * so the append is a read-modify-write under the slice's CAS `update`).
 * Pure. */
internal fun ChatState.appendDeltaBuffer(partId: String, delta: String): Pair<ChatState, List<SseSideEffect>> {
    val current = deltaBuffer[partId].orEmpty()
    return copy(deltaBuffer = deltaBuffer + (partId to (current + delta))) to emptyList()
}

/**
 * Trailing-coalesce fullText REPLACE: overwrite [ChatState.fullTextBuffer][partId]
 * with [text] (REPLACE — fullText is the server-authoritative accumulated
 * text; only the latest value per partId matters). Pure.
 */
internal fun ChatState.replaceFullTextBuffer(partId: String, text: String): Pair<ChatState, List<SseSideEffect>> =
    copy(fullTextBuffer = fullTextBuffer + (partId to text)) to emptyList()

/**
 * Marks [partId] as having a pending flush (the slice-side mirror of the
 * coordinator's [SessionSyncCoordinator.flushJobs] entry). Set on the
 * leading-edge write so an observer of the slice can detect "deltas still
 * buffered" without needing access to the Job. Pure.
 */
internal fun ChatState.markFlushPending(partId: String): Pair<ChatState, List<SseSideEffect>> =
    if (partId in pendingFlushPartIds) this to emptyList()
    else copy(pendingFlushPartIds = pendingFlushPartIds + partId) to emptyList()

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
internal fun ChatState.flushCoalesceBufferForPart(partId: String): Pair<ChatState, List<SseSideEffect>> {
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
        ) to emptyList()
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
        ) to emptyList()
    }
    // Overlay wiped mid-window → drop stale buffered delta (re-injecting
    // would render ghost text from the previous session).
    if (!overlayPresent) {
        return copy(
            fullTextBuffer = fullTextBufferAfter - partId,
            deltaBuffer = deltaBuffer - partId,
            pendingFlushPartIds = pendingFlushPartIds - partId
        ) to emptyList()
    }
    // APPEND path: append the buffered delta to the overlay.
    val previous = streamingPartTexts[partId].orEmpty()
    return copy(
        streamingPartTexts = streamingPartTexts + (partId to (previous + bufferedDelta)),
        fullTextBuffer = fullTextBufferAfter - partId,
        deltaBuffer = deltaBuffer - partId,
        pendingFlushPartIds = pendingFlushPartIds - partId
    ) to emptyList()
}

/**
 * Drops [partId]'s three coalesce entries without flushing (the buffers are
 * discarded, NOT applied to streamingPartTexts). The slice-side companion of
 * [SessionSyncCoordinator.cancelDeltaFlush]. Pure.
 */
internal fun ChatState.clearCoalesceBufferForPart(partId: String): Pair<ChatState, List<SseSideEffect>> = copy(
    deltaBuffer = deltaBuffer - partId,
    fullTextBuffer = fullTextBuffer - partId,
    pendingFlushPartIds = pendingFlushPartIds - partId
) to emptyList()

/**
 * Drops ALL coalesce entries (deltaBuffer / fullTextBuffer /
 * pendingFlushPartIds). Does NOT touch streamingPartTexts /
 * streamingReasoningPart — the overlay clear is a separate concern (the
 * §闪屏修复 made part.created preserve the overlay; only the buffers are
 * dropped). The slice-side companion of
 * [SessionSyncCoordinator.clearDeltaBuffers]. Pure.
 */
internal fun ChatState.clearAllCoalesceBuffers(): Pair<ChatState, List<SseSideEffect>> = copy(
    deltaBuffer = emptyMap(),
    fullTextBuffer = emptyMap(),
    pendingFlushPartIds = emptySet()
) to emptyList()

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
