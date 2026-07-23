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
 *  - `snapshot(done=true)` → DONE; REPLACE final text. No further deltas are
 *    accepted for this part (late deltas are dropped, see [reduceDelta]).
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
            val terminal = TokenPartAcc(
                sessionId = frame.sessionId,
                messageId = frame.messageId,
                partId = frame.partId,
                text = frame.text ?: "",
                state = TokenPartStreamState.DONE,
            )
            return state.copy(parts = state.parts + (frame.partId to terminal)) to emptyList()
        }
        // snapshot(done=false, truncated=false) → REPLACE buffer + STREAMING.
        val acc = TokenPartAcc(
            sessionId = frame.sessionId,
            messageId = frame.messageId,
            partId = frame.partId,
            text = frame.text ?: "",
            state = TokenPartStreamState.STREAMING,
        )
        return state.copy(parts = state.parts + (frame.partId to acc)) to emptyList()
    }

    private fun reduceDelta(
        state: TokenStreamReducerState,
        frame: TokenStreamFrame.PartDelta,
    ): Pair<TokenStreamReducerState, List<TokenStreamCoordinatorEffect>> {
        val existing = state.parts[frame.partId]
        // Append ONLY when the part is currently STREAMING. Two drop cases:
        //  (a) orphan — no snapshot has established the part yet (delta
        //      arrived first). The wire delta has no type, so we cannot
        //      type-filter; silently absorb (server C3 semantics).
        //  (b) late — the part already transitioned to DONE; a straggler
        //      delta after the terminal snapshot is stale, drop it.
        if (existing == null || existing.state != TokenPartStreamState.STREAMING) {
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
}
