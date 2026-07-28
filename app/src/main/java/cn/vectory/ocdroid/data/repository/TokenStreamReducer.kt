package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.ResyncReason
import cn.vectory.ocdroid.data.model.TokenStreamFrame

/**
 * §Stage-C §3.3 / §3.8 — effect vocabulary emitted by the pure
 * [TokenStreamReducer]. The Stage-D coordinator translates these into
 * concrete app actions (e.g. [cn.vectory.ocdroid.ui.AppAction.ClearTokenStreamState]
 * for [ClearPartState]). Kept in the data/repository layer so the reducer
 * stays decoupled from the UI store.
 */
sealed interface TokenStreamCoordinatorEffect {

    /**
     * Clear the token-stream overlay ([streamOwned] / streamingPartTexts) for
     * the given partIds. Emitted on `truncated` snapshot (single part) and on
     * `resync` (all parts owned for the session).
     */
    data class ClearPartState(val partIds: Set<String>) : TokenStreamCoordinatorEffect

    /**
     * The in-flight overlay is no longer authoritative; re-fetch the session's
     * messages from REST (`/slimapi/messages/{sid}/since/…`) to reconcile.
     * [authoritative] is always `true` here — a resync / truncate means the
     * REST view must replace the streamed overlay (matches
     * [cn.vectory.ocdroid.ui.controller.mergeSlimMessages] authoritative
     * splice, clearing streamOwned for fetched parts).
     */
    data class TriggerSinceFetch(
        val sessionId: String,
        val authoritative: Boolean,
    ) : TokenStreamCoordinatorEffect

    /**
     * Tear down + re-open the SSE transport for [sessionId]. Emitted ONLY for
     * resync reasons where [ResyncReason.triggersReconnect] is true (the
     * server has no replay buffer, so the socket itself is unusable).
     */
    data class Reconnect(val sessionId: String) : TokenStreamCoordinatorEffect
}

/**
 * Per-part lifecycle within the reducer. Mirrors
 * [cn.vectory.ocdroid.ui.StreamOwnedState] but kept local so the reducer has
 * zero UI-layer dependencies (pure data/repository code, unit-testable in
 * isolation without pulling the UI slice).
 */
enum class TokenPartStreamState { STREAMING, DONE }

/**
 * Per-part accumulator. [text] is the joined buffer (snapshots replace it,
 * deltas append). Immutable so [TokenStreamReducerState] copies stay cheap
 * and the reducer is a pure function (state in → state out + effects).
 */
data class TokenPartAcc(
    val sessionId: String,
    val messageId: String,
    val partId: String,
    val text: String,
    val state: TokenPartStreamState,
)

/**
 * Reducer working state. Part-keyed ([parts]) so the reducer can apply
 * snapshot/delta transitions per partId without consulting the UI slice.
 *
 * [droppedDeltaCount] is an observability counter for deltas that could not be
 * applied — both "orphan" (delta arrived before any snapshot for that partId)
 * and "late" (delta arrived after the part transitioned to DONE). Matching
 * the server's C3 silent-drop semantics, these deltas are dropped (not
 * fatal), but counted so the Stage-D watchdog / diagnostics can surface a
 * runaway stream.
 */
data class TokenStreamReducerState(
    val parts: Map<String, TokenPartAcc> = emptyMap(),
    val droppedDeltaCount: Long = 0L,
)

/**
 * §Stage-C §3.3 state machine + §3.8 effect pattern. PURE function: no
 * dispatch, no IO, no coroutine launches. Given the current working state, a
 * single parsed frame, and the external ownership map, returns the next
 * state and a list of effects for the coordinator to translate.
 *
 * # Per-part state machine
 *
 *  - `snapshot(done=false, truncated=false)` → REPLACE buffer + STREAMING.
 *    A fresh snapshot for a part already in the map overwrites its text +
 *    state (the server is re-establishing the authoritative prefix; the
 *    token stream trusts snapshot over accumulated deltas).
 *  - `snapshot(done=true)` → DONE. If `text` is present it REPLACES the
 *    buffer; if `text` is null/absent the accumulated buffer is KEPT (avoids
 *    blanking the part before the authoritative `/since` reconcile — bilateral
 *    §5 C-1). No further deltas are accepted for this part (late deltas are
 *    dropped, see [reduceDelta]); emits zero effects (authoritative text
 *    arrives via the digest/`/since` path).
 *  - `snapshot(truncated=true)` → clear that part from the reducer state +
 *    [ClearPartState]({partId}) + [TriggerSinceFetch](sid, authoritative=true).
 *    `truncated` takes priority over `done` (a truncated part is by definition
 *    not authoritative regardless of the done flag).
 *  - `delta` → append to the part's buffer IF the part is currently STREAMING;
 *    otherwise drop + increment [droppedDeltaCount] (orphan-before-snapshot OR
 *    late-after-done). This mirrors the server's C3 silent-drop: the wire
 *    delta carries no part type, so the reducer cannot type-filter; orphan
 *    deltas are the unavoidable consequence and are silently absorbed.
 *  - `resync(reason, sid)` → clear ALL reducer parts whose sessionId == sid +
 *    [ClearPartState](union of reducer-owned + externally-owned parts for sid)
 *    + [TriggerSinceFetch](sid, authoritative=true) +, when
 *    [ResyncReason.triggersReconnect], [Reconnect](sid).
 *
 * # `ownedBySession` — external ownership union
 *
 * The reducer's own [parts] map is the streaming accumulator, but the
 * AUTHORITATIVE ownership (what [cn.vectory.ocdroid.ui.ChatState.streamOwned]
 * records) is owned by the UI slice and passed in via [ownedBySession]. On
 * resync, [ClearPartState] is emitted with the UNION of (reducer-known parts
 * for sid) and (externally-owned parts for sid) so neither side is left with
 * stale entries. For `truncated`, the single [partId] is sufficient (we are
 * actively streaming it, so it is owned by definition).
 *
 * Pure modulo immutable copies; no synchronization needed (callers feed
 * single-threaded frames, and the returned state replaces the prior atomically).
 */
object TokenStreamReducer {

    fun reduce(
        state: TokenStreamReducerState,
        frame: TokenStreamFrame,
        ownedBySession: Map<String, Set<String>> = emptyMap(),
    ): Pair<TokenStreamReducerState, List<TokenStreamCoordinatorEffect>> = when (frame) {
        // Heartbeat + server.connected are transport-level signals; they carry
        // no part-state mutation. The watchdog (Stage D) consumes them
        // separately via the raw frame Flow before reduction.
        TokenStreamFrame.ServerHeartbeat -> state to emptyList()
        is TokenStreamFrame.ServerConnected -> state to emptyList()
        is TokenStreamFrame.PartSnapshot -> reduceSnapshot(state, frame)
        is TokenStreamFrame.PartDelta -> reduceDelta(state, frame)
        is TokenStreamFrame.Resync -> reduceResync(state, frame, ownedBySession)
        // B-P0-3 §Stage-B M5: removal events carry no streaming-text
        // mutation of their own (the per-message watermark is updated via
        // a SEPARATE path — [MessageWatermarkState]). But the reducer OWNS
        // the streaming-text
        // overlay, so a removal MUST (a) drop the removed part(s) from
        // the reducer's working map so a late straggler frame cannot
        // re-establish them, AND (b) emit [ClearPartState] so the
        // coordinator translates it into [cn.vectory.ocdroid.ui.AppAction.ClearTokenStreamState]
        // and the chat slice's streamOwned / streamingPartTexts /
        // coalesce buffers are torn down — preventing a late frame from
        // resurrecting ghost text for a removed part / message.
        is TokenStreamFrame.MessagePartRemoved -> reduceMessagePartRemoved(state, frame)
        is TokenStreamFrame.MessageRemoved -> reduceMessageRemoved(state, frame)
    }

    private fun reduceSnapshot(
        state: TokenStreamReducerState,
        frame: TokenStreamFrame.PartSnapshot,
    ): Pair<TokenStreamReducerState, List<TokenStreamCoordinatorEffect>> {
        // truncated takes priority: the part's in-flight state is lost, the
        // consumer must clear + re-fetch authoritatively. Done is irrelevant
        // when truncated (the text is not trustworthy).
        if (frame.truncated) {
            val cleared = state.copy(parts = state.parts - frame.partId)
            val effects = listOf(
                TokenStreamCoordinatorEffect.ClearPartState(setOf(frame.partId)),
                TokenStreamCoordinatorEffect.TriggerSinceFetch(frame.sessionId, authoritative = true),
            )
            return cleared to effects
        }
        if (frame.done) {
            // §5 C-1: a done snapshot may omit text (`text:null`, status-only
            // terminal). Overwriting the buffer with "" here would propagate
            // through bridgePartToChatState → TokenStreamPartUpdated(text="")
            // → StreamingBufferFieldsReducer sets streamingPartTexts[partId]=""
            // and the UI's `streamingTextOverride ?: part.text` would render a
            // BLANK window (the non-null "" override shadows the real part
            // text) until an authoritative /since reconcile arrives. So when
            // text is null, KEEP the accumulated buffer (or "" if the part had
            // no prior snapshot — nothing to preserve). When text is non-null,
            // it is the authoritative final value → use it (existing behavior).
            // The part still transitions to DONE.
            //
            // lite-v2-dev (plan §2.2 / §4.2): done:true → TriggerSinceFetch →
            // skeleton reload（limit=50，终态文本收敛）。旧的「依赖 digest//since
            // 自动收敛」路径在 lite-v2 改为显式 reload——权威窗口 diff 一次拿到
            // 终态全文（覆盖 overlay）。
            val existing = state.parts[frame.partId]
            val terminalText = frame.text ?: existing?.text ?: ""
            val terminal = TokenPartAcc(
                sessionId = frame.sessionId,
                messageId = frame.messageId,
                partId = frame.partId,
                text = terminalText,
                state = TokenPartStreamState.DONE,
            )
            val effects = listOf(
                TokenStreamCoordinatorEffect.TriggerSinceFetch(frame.sessionId, authoritative = true),
            )
            return state.copy(parts = state.parts + (frame.partId to terminal)) to effects
        }
        // snapshot(done=false, truncated=false) → REPLACE buffer + STREAMING.
        // §orphan-delta-guard (rev-gpt concern #1): if a provisional entry
        // from orphan deltas already has LONGER accumulated text than the
        // snapshot, the snapshot is a stale/partial view (a delayed initial
        // empty snapshot arriving after the deltas already streamed). Token
        // streaming is append-only, so the authoritative snapshot should
        // ALWAYS be ≥ the accumulated delta text. If it's shorter, keep the
        // existing text to prevent "vanishing tokens" (the user would see
        // text disappear and never come back — the subsequent deltas were
        // already consumed and won't replay).
        val existingEntry = state.parts[frame.partId]
        val snapshotText = frame.text ?: ""
        val effectiveText = if (existingEntry != null &&
            existingEntry.state == TokenPartStreamState.STREAMING &&
            existingEntry.text.length > snapshotText.length
        ) {
            existingEntry.text
        } else {
            snapshotText
        }
        val acc = TokenPartAcc(
            sessionId = frame.sessionId,
            messageId = frame.messageId,
            partId = frame.partId,
            text = effectiveText,
            state = TokenPartStreamState.STREAMING,
        )
        return state.copy(parts = state.parts + (frame.partId to acc)) to emptyList()
    }

    private fun reduceDelta(
        state: TokenStreamReducerState,
        frame: TokenStreamFrame.PartDelta,
    ): Pair<TokenStreamReducerState, List<TokenStreamCoordinatorEffect>> {
        val existing = state.parts[frame.partId]
        if (existing == null) {
            // §orphan-delta-fix (2026-07-26): the server sends
            // `message.part.delta` frames BEFORE the initial
            // `message.part.snapshot` that establishes the part's metadata.
            // The old code dropped ALL orphan deltas (existing==null →
            // droppedDeltaCount++), which meant the user saw NO live
            // streaming — just a loading spinner until the REST poll
            // (session.digest → probe → /messages fetch) picked up the
            // completed message seconds later.
            //
            // The PartDelta wire frame carries sessionId / messageId /
            // partId / text — enough to create a provisional STREAMING
            // entry. The bridge (bridgePartToChatState) hardcodes
            // partType="text" for PartPlaceholderEnsured regardless, so no
            // type information is lost. When the snapshot arrives later,
            // reduceSnapshot's REPLACE semantics overwrite this provisional
            // entry with the server-authoritative text (if any).
            //
            // Log evidence: first delta at 11:07:00.570, snapshot at
            // 11:07:01.382 — 812ms gap, ~11 deltas ALL dropped by the old
            // guard. After this fix, each delta creates/appends to the
            // provisional entry and the UI renders live token-by-token text.
            val provisional = TokenPartAcc(
                sessionId = frame.sessionId,
                messageId = frame.messageId,
                partId = frame.partId,
                text = frame.text,
                state = TokenPartStreamState.STREAMING,
            )
            return state.copy(parts = state.parts + (frame.partId to provisional)) to emptyList()
        }
        // Late delta after DONE — a straggler delta after the terminal
        // snapshot is stale, drop it.
        if (existing.state != TokenPartStreamState.STREAMING) {
            return state.copy(droppedDeltaCount = state.droppedDeltaCount + 1) to emptyList()
        }
        val appended = existing.copy(text = existing.text + frame.text)
        return state.copy(parts = state.parts + (frame.partId to appended)) to emptyList()
    }

    private fun reduceResync(
        state: TokenStreamReducerState,
        frame: TokenStreamFrame.Resync,
        ownedBySession: Map<String, Set<String>>,
    ): Pair<TokenStreamReducerState, List<TokenStreamCoordinatorEffect>> {
        val sid = frame.sessionId
        // Clear ALL reducer parts attributed to sid (regardless of messageId).
        // If sid is null (malformed resync), there is nothing attributable —
        // clear nothing and emit no fetch/reconnect (a null sid is not
        // actionable; the coordinator logs + drops upstream).
        val clearedParts = if (sid != null) {
            state.parts.filterValues { it.sessionId != sid }
        } else {
            state.parts
        }

        val effects = mutableListOf<TokenStreamCoordinatorEffect>()
        if (sid != null) {
            // Union of reducer-known parts for sid + externally-owned parts
            // (ChatState.streamOwned) so neither side retains stale entries.
            val reducerOwnedForSid = state.parts.values
                .asSequence()
                .filter { it.sessionId == sid }
                .map { it.partId }
                .toSet()
            val externalOwnedForSid = ownedBySession[sid].orEmpty()
            val clearSet = reducerOwnedForSid + externalOwnedForSid
            effects += TokenStreamCoordinatorEffect.ClearPartState(clearSet)
            effects += TokenStreamCoordinatorEffect.TriggerSinceFetch(sid, authoritative = true)
            if (frame.reason.triggersReconnect) {
                effects += TokenStreamCoordinatorEffect.Reconnect(sid)
            }
        }
        return state.copy(parts = clearedParts) to effects
    }

    /**
     * §Stage-B M5: `message.part.removed` — drop the single reducer-owned
     * part (no straggler frame can resurrect it after the watermark has
     * advanced) and emit [TokenStreamCoordinatorEffect.ClearPartState] so
     * the coordinator translates it into a chat-slice overlay clear
     * (streamOwned / streamingPartTexts / coalesce buffers).
     *
     * §rev-ogpt severe #3: the ClearPartState effect is now emitted for
     * EVERY `message.part.removed` frame, including parts the reducer does
     * NOT own (UI-owned overlay). Pre-fix the unowned branch was a no-op,
     * so the UI-side overlay survived the removal event; a subsequent
     * `/full` (`authoritative=false`) then preserved the part via
     * `preservedLocal` (SseChatReducers.kt — it was still STREAMING-owned
     * in `streamOwned`), keeping ghost text alive despite the explicit
     * upstream deletion. The partId carried on the wire frame is sufficient
     * signal — it does not depend on whether the reducer happens to own a
     * [TokenPartAcc] for it (the UI slice can own a part through paths the
     * reducer never sees, e.g. `applyPartCreatedPlaceholder` for a reasoning
     * part, or a DONE part pruned from the working map while the chat slice
     * retained it).
     */
    private fun reduceMessagePartRemoved(
        state: TokenStreamReducerState,
        frame: TokenStreamFrame.MessagePartRemoved,
    ): Pair<TokenStreamReducerState, List<TokenStreamCoordinatorEffect>> {
        val existing = state.parts[frame.partId]
        // Reducer-owned path: drop the part from the working map. The
        // defensive session/message guard below protects against a
        // theoretically-impossible partId collision across contexts
        // (cheap to guard; if it ever fires the frame is treated as buggy
        // and no ClearPartState is emitted either — see the mismatch
        // branch).
        if (existing != null) {
            // Defensive: only clear for the matching session/message — a
            // partId collision across sessions (theoretically impossible
            // given the wire's per-session scope, but cheap to guard) would
            // otherwise let a removal in session A clear session B's overlay.
            if (existing.sessionId != frame.sessionId ||
                existing.messageId != frame.messageId
            ) return state to emptyList()
            val cleared = state.copy(parts = state.parts - frame.partId)
            val effects = listOf(
                TokenStreamCoordinatorEffect.ClearPartState(setOf(frame.partId)),
            )
            return cleared to effects
        }
        // §rev-ogpt severe #3: reducer does NOT own this part. Pre-fix this
        // was a no-op; now emit ClearPartState(frame.partId) unconditionally
        // so the coordinator translates it into ClearTokenStreamState and
        // the chat-slice overlay (streamOwned / streamingPartTexts /
        // coalesce buffers) is torn down. Without this, the only signal the
        // UI layer gets about the removal is the debounced /full fetch —
        // but the /full authoritative=false merge keeps STREAMING-owned
        // locals via preservedLocal, so the ghost survives. The partId
        // comes from the wire frame itself (independent of reducer
        // ownership); the generation guard in
        // [TokenStreamCoordinator.filterClearByGeneration] still drops the
        // clear if a NEWER generation now owns the partId.
        val effects = listOf(
            TokenStreamCoordinatorEffect.ClearPartState(setOf(frame.partId)),
        )
        return state to effects
    }

    /**
     * §Stage-B M5: `message.removed` — drop EVERY reducer-owned part
     * attributed to the message and emit [TokenStreamCoordinatorEffect.ClearPartState]
     * with the union of those part IDs (the coordinator translates it
     * into the chat-slice overlay clear).
     *
     * §rev-ogpt severe #3 analog: unlike [reduceMessagePartRemoved], the
     * `message.removed` wire frame carries NO partId — only session/message
     * scope ids. The reducer therefore cannot enumerate UI-owned parts
     * attributed to the removed message (its only external ownership view,
     * `ownedBySession`, is keyed by sessionId and over-clearing would wipe
     * unrelated messages' active streams in the same session). The
     * reducer-owned partIds emitted here cover the common case; the UI-side
     * cleanup for any UI-owned part that lives in `partsByMessage[msgId]`
     * is driven SEPARATELY by the production `onMessageRemoved` hook,
     * which dispatches [cn.vectory.ocdroid.ui.AppAction.MessageRemovedConfirmed]
     * (Lane U) → [cn.vectory.ocdroid.ui.evictMessageAndPartOverlay] — that
     * reducer collects partIds from BOTH the flat projection AND
     * LoadedContent and clears streamOwned / streamingPartTexts /
     * deltaBuffer / fullTextBuffer / pendingFlushPartIds /
     * streamingReasoningPart for them. The two paths together (immediate
     * reducer-owned clear + UI-side eviction) cover the whole-message
     * removal contract.
     */
    private fun reduceMessageRemoved(
        state: TokenStreamReducerState,
        frame: TokenStreamFrame.MessageRemoved,
    ): Pair<TokenStreamReducerState, List<TokenStreamCoordinatorEffect>> {
        val ownedPartIds = state.parts.values
            .asSequence()
            .filter { it.messageId == frame.messageId && it.sessionId == frame.sessionId }
            .map { it.partId }
            .toSet()
        if (ownedPartIds.isEmpty()) return state to emptyList()
        val cleared = state.copy(parts = state.parts.filterKeys { it !in ownedPartIds })
        val effects = listOf(
            TokenStreamCoordinatorEffect.ClearPartState(ownedPartIds),
        )
        return cleared to effects
    }
}