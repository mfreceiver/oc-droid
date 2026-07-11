package cn.vectory.ocdroid.ui.controller

/**
 * §R-19 Sprint 1 Lane A (P1-10): SSE gap reconciliation overlay state.
 *
 * The coordinator's [SessionSyncCoordinator.handleEvent] `server.connected`
 * branch historically relied on an implicit reconciliation cocktail:
 *  - a `gapInfo` slice + `loadMessages(resetLimit=true)` for the current
 *    session,
 *  - the [ForegroundCatchUpController]'s 3-tier suppress / catch-up /
 *    `sseHasConnectedOnce` state machine,
 *  - ad-hoc `clearDeltaBuffers()` / catch-up fan-outs.
 *
 * Each piece works in isolation but there was NO single invariant describing
 * "given the SSE disconnect / reconnect history, what should we reload?".
 * [SseSyncState] is that invariant: it is the **sole input** to the pure
 * decision function [reconcileGap], which returns the next state PLUS an
 * explicit list of [SseSyncDecision]s. The decisions drive effect emits
 * (`LoadMessages` / `LoadSessions` / `clearDeltaBuffers()`) inside
 * [SessionSyncCoordinator.handleEvent].
 *
 * **Overlay, not replacement**: [ForegroundCatchUpController]'s 3-tier stays
 * the authoritative foreground/background suppress layer (production-verified);
 * this state machine adds an explicit, unit-testable invariant on TOP of it.
 * Both layers may emit overlapping reload effects — the existing
 * `isLoadingMessages` coalescing + the 400ms `loadMessagesWithRetry` debounce
 * collapse duplicate reloads into one network round-trip.
 *
 * §thread-confinement: writes are confined to the coordinator's
 * single-threaded [scope] (Dispatchers.Main.immediate). The
 * `@Volatile`/`AtomicReference` discipline is belt-and-suspenders against a
 * future dispatcher change; correctness today does not depend on it.
 *
 * @param connectedOnce `true` once the first `server.connected` in this host
 *   generation has been processed. Cold-start reconnects (`connectedOnce=false`)
 *   skip gap reconciliation — there is no local history to reconcile against
 *   (the cold-start loader just fetched the authoritative snapshot).
 * @param lastDisconnectAt Epoch-ms of the most recent observed disconnect
 *   ([ControllerEffect.CancelSse] / future onFailure / onClosed hook), or null
 *   when no explicit disconnect has been observed in this generation. Note
 *   (§R-19 fix Blocker 3): the value of `lastDisconnectAt` does NOT gate
 *   reconciliation — a `server.connected` arriving with `connectedOnce==true`
 *   triggers reconciliation regardless (covering both explicit disconnects
 *   AND implicit retryWhen reconnects that fire no signal the overlay can
 *   observe). The field is kept for diagnostic / future use.
 * @param sessionsDirty Sessions that were the user's currentSession at a
 *   disconnect time (observed via [ControllerEffect.CancelSse] →
 *   [SseReconnectTrigger.Disconnected]). Used SOLELY by the ServerConnected
 *   reconcile to detect scenario 3 (user switched sessions mid-disconnect):
 *   if a dirty session is NOT the current session at reconnect, its list-
 *   level state may be stale (badge / last-activity) and a
 *   [SseSyncDecision.RefreshSessions] is emitted. Populated only by the
 *   Disconnected trigger; cleared in full by [HostReconfigured].
 *
 *   §R-19 fix Blocker 2 v2 (gpter round-2): the PRIOR role of sessionsDirty
 *   as a one-shot idle-dedup set is REMOVED. The lifecycle of "added on
 *   reconnect, consumed on first idle" had a residual bug — if no idle
 *   arrived between the reconnect and a later user-initiated run, the dirty
 *   entry lingered and suppressed that later run's idle finalization
 *   reload. Idle-driven reloads now rely entirely on the scheduling layer's
 *   `isLoadingMessages` coalescing + the 400ms `loadMessagesWithRetry`
 *   debounce (same path that absorbs ForegroundCatchUpController's
 *   overlapping catch-up effects), NOT on overlay-level dedup.
 * @param hostGeneration Monotonic counter bumped on every host reconfigure
 *   ([ControllerEffect.HostReconfigured]). Triggers carry the generation they
 *   were issued under; mismatched generations are no-ops — this is the guard
 *   against a late SSE frame from the PREVIOUS host (its collection job was
 *   cancelled by `cancelSseForReconfigure` but a buffered frame still lands)
 *   erroneously triggering a reload of the NEW host's session.
 * @param sessionsEverColdSnapshotted R-20 Phase 2 (G6): sessions for which a
 *   catch-up cold snapshot has established the SSE-coverage baseline. A session
 *   is SSE-covered (no probe needed) iff the live SSE feed is attached to its
 *   workdir AND it appears in this set ([cn.vectory.ocdroid.ui.chat.BackfillAlgorithm.shouldProbe]
 *   is the negation). Populated by [cn.vectory.ocdroid.ui.launchCatchUp]'s
 *   onColdSnapshot callback (fired on every successful catch-up); reset to
 *   empty by [SseReconnectTrigger.HostReconfigured] so a host switch cannot
 *   carry a stale baseline across to an unrelated server (avoiding a false
 *   "covered" verdict that would skip a needed probe).
 */
data class SseSyncState(
    val connectedOnce: Boolean = false,
    val lastDisconnectAt: Long? = null,
    val sessionsDirty: Set<String> = emptySet(),
    val sessionsEverColdSnapshotted: Set<String> = emptySet(),
    val hostGeneration: Long = 0L
)

/**
 * §R-19 Sprint 1 Lane A (P1-10): the events the SSE gap reconciler reacts to.
 *
 * Sealed so the compiler forces [reconcileGap]'s `when` to be exhaustive when
 * a new trigger is added. Each trigger carries the [hostGeneration] it was
 * issued under so the generation guard (late-event detection) can fire.
 */
sealed class SseReconnectTrigger {
    /**
     * A `server.connected` frame arrived. [currentSessionId] is the
     * coordinator's chatFlow currentSessionId AT THE MOMENT of arrival —
     * scenario 3 (currentSession switched mid-disconnect) supplies the NEW id,
     * so [SseSyncDecision.ReloadSession] correctly targets the session the
     * user is now looking at, not the one that was active when the disconnect
     * began.
     */
    data class ServerConnected(
        val currentSessionId: String?,
        override val hostGeneration: Long
    ) : SseReconnectTrigger()

    /**
     * The SSE feed ended ([ControllerEffect.CancelSse], foreground backgrounding,
     * or future onFailure / onClosed hooks). [dirtySessionIds] is the set of
     * sessions whose slice state may now be stale — typically just the current
     * session (the one the user is watching); the next [ServerConnected] uses
     * this to decide whether a reload is warranted.
     */
    data class Disconnected(
        val atMs: Long,
        val dirtySessionIds: Set<String>,
        override val hostGeneration: Long
    ) : SseReconnectTrigger()

    /**
     * Host / profile switch — the server identity changed. Resets all per-host
     * state and bumps the generation so any in-flight [ServerConnected] /
     * [Disconnected] trigger carrying the OLD generation becomes a no-op
     * (scenario 4: late SSE frame from the previous host). [hostGeneration]
     * IS the new generation (the bump is the hostGeneration value itself).
     */
    data class HostReconfigured(
        override val hostGeneration: Long
    ) : SseReconnectTrigger()

    /** The generation this trigger was issued under; mismatched = stale. */
    abstract val hostGeneration: Long
}

/**
 * §R-19 Sprint 1 Lane A (P1-10): the reconciler's verdict. Translated by
 * [SessionSyncCoordinator.handleEvent] into concrete effects / actions:
 *  - [ReloadSession]            → `ControllerEffect.LoadMessages(sessionId, resetLimit)`
 *  - [LoadSessionStatus]        → `ControllerEffect.LoadSessionStatus`
 *  - [RefreshSessions]          → `ControllerEffect.LoadSessions`
 *  - [ClearDeltaBuffers]        → `clearDeltaBuffers()`
 */
sealed class SseSyncDecision {
    /** Reload [sessionId]'s message window from the server. */
    data class ReloadSession(
        val sessionId: String,
        val resetLimit: Boolean = true
    ) : SseSyncDecision()

    /** Refresh all session statuses after an SSE reconnect. */
    data object LoadSessionStatus : SseSyncDecision()

    /** Refresh the entire session list (a non-current session was dirty). */
    data object RefreshSessions : SseSyncDecision()

    /** Drop all per-partId delta/fullText coalesce buffers (overlay is stale). */
    data object ClearDeltaBuffers : SseSyncDecision()
}

// ── §R-19 P1-10: pure decision function ─────────────────────────────────

/**
 * The pure SSE gap reconciliation decision function. Takes the prior
 * [SseSyncState] + a [SseReconnectTrigger] and returns the next state PLUS the
 * ordered list of [SseSyncDecision]s the caller should apply.
 *
 * Pure — no slice reads, no effect emits, no coroutine launches. The caller
 * ([SessionSyncCoordinator.handleEvent]) is responsible for translating the
 * decisions into effects and writing the new state back. This is the
 * unit-testable invariant surface for the 4 P1-10 scenarios.
 *
 * **Decision logic by trigger**:
 *
 * - [SseReconnectTrigger.HostReconfigured]:
 *   Reset to a fresh cold-start state under the new generation. No decisions
 *   (the reconfigure path runs its own loadSessions / coldStartReconnect).
 *   This is the generation-bump that makes any later stale-generation trigger
 *   a no-op (scenario 4).
 *
 * - [SseReconnectTrigger.Disconnected]:
 *   Generation guard: stale → no-op. Cold-start skip: never-connected hosts
 *   have nothing to mark dirty. Otherwise stamp [SseSyncState.lastDisconnectAt]
 *   and merge [SseReconnectTrigger.Disconnected.dirtySessionIds] into
 *   [SseSyncState.sessionsDirty] (additive — multiple disconnects accumulate
 *   within a generation). No decisions (disconnect alone triggers nothing;
 *   the next `ServerConnected` reconciles).
 *
 * - [SseReconnectTrigger.ServerConnected]:
 *   1. Generation guard: stale → no-op (scenario 4; the production-grade
 *      guard is the per-event drop in `ConnectionCoordinator.launchSseCollection`,
 *      this is the belt-and-suspenders second line).
 *   2. Cold-start (`connectedOnce=false`): mark `connectedOnce=true`, no
 *      decisions (ForegroundCatchUpController's cold-start path runs its own
 *      load).
 *   3. Otherwise (connectedOnce==true): ALWAYS reconcile. This covers BOTH
 *      the explicit-disconnect case (lastDisconnectAt != null, a prior
 *      `Disconnected` trigger fired) AND the implicit-retry case
 *      (lastDisconnectAt == null — the SSE feed's internal retryWhen
 *      reconnected without emitting any signal the overlay could observe;
 *      §R-19 fix Blocker 3). Emit `ClearDeltaBuffers` + `ReloadSession(currentSessionId)`
 *      (unless null) + `RefreshSessions` (iff a non-current session was dirty
 *      → scenario 3). Clear `lastDisconnectAt`. `sessionsDirty` is NOT
 *      modified (§R-19 fix Blocker 2 v2: the prior "add currentSessionId to
 *      dirty for idle dedup" is removed — see [SseSyncState.sessionsDirty]
 *      KDoc for the rationale).
 *
 *   §R-19 fix Blocker 3 note: the prior implementation had an idempotency
 *   branch (`lastDisconnectAt == null → no-op`) that silently swallowed
 *   implicit-retry reconnects, defeating the entire P1-10 overlay for the
 *   most common production gap-recovery path. Idempotency for true
 *   duplicate `server.connected` frames within a single healthy connection
 *   (a rare server quirk) now relies on the existing `isLoadingMessages`
 *   coalescing + the 400ms `loadMessagesWithRetry` debounce at the
 *   dispatch layer — same coalescing that handles ForegroundCatchUpController's
 *   overlapping catch-up effects.
 *
 *   §R-19 fix Blocker 2 v2 note: the prior implementation added
 *   `currentSessionId` to `sessionsDirty` here so the `dispatchSseEvent`
 *   idle branch could dedup the first parallel idle reload. That dedup had
 *   a residual lifecycle bug — if no idle arrived between the reconnect and
 *   a later user-initiated run, the dirty entry lingered and permanently
 *   suppressed that later run's idle finalization reload. The dedup is now
 *   removed entirely (idle reloads always fire based on overlay state;
 *   overlapping reloads coalesce at the scheduling layer). `sessionsDirty`
 *   is now populated ONLY by the Disconnected trigger.
 */
fun reconcileGap(
    prev: SseSyncState,
    trigger: SseReconnectTrigger
): Pair<SseSyncState, List<SseSyncDecision>> {
    return when (trigger) {
        is SseReconnectTrigger.HostReconfigured -> {
            // Fresh cold-start under the new generation. All per-host state resets.
            SseSyncState(
                connectedOnce = false,
                lastDisconnectAt = null,
                sessionsDirty = emptySet(),
                hostGeneration = trigger.hostGeneration
            ) to emptyList()
        }
        is SseReconnectTrigger.Disconnected -> {
            // Generation guard: stale trigger (host reconfigured since this
            // disconnect was observed) → no-op.
            if (trigger.hostGeneration != prev.hostGeneration) {
                prev to emptyList()
            } else if (!prev.connectedOnce) {
                // Cold-start skip: never-connected hosts have no slice history to mark.
                prev to emptyList()
            } else {
                prev.copy(
                    lastDisconnectAt = trigger.atMs,
                    sessionsDirty = prev.sessionsDirty + trigger.dirtySessionIds
                ) to emptyList()
            }
        }
        is SseReconnectTrigger.ServerConnected -> {
            // Generation guard: stale trigger (the host was reconfigured away
            // AFTER this server.connected was emitted, e.g. a buffered frame from
            // the cancelled previous-host SSE job arrived late). Scenario 4.
            // (Production-grade drop is at CC.launchSseCollection; this is the
            // defensive second line that makes the pure-function test pin the
            // contract.)
            if (trigger.hostGeneration != prev.hostGeneration) {
                prev to emptyList()
            } else if (!prev.connectedOnce) {
                // Cold-start: first connect in this generation. Mark connectedOnce;
                // ForegroundCatchUpController + the cold-start loader handle the
                // initial snapshot.
                prev.copy(connectedOnce = true, lastDisconnectAt = null) to emptyList()
            } else {
                // §R-19 fix Blocker 3: ALWAYS reconcile after cold-start. Covers
                // both explicit-disconnect (lastDisconnectAt != null) AND implicit
                // retryWhen reconnects (lastDisconnectAt == null). The prior
                // idempotency branch silently swallowed the implicit-retry case,
                // defeating the overlay for the most common production gap path.
                val currentSessionId = trigger.currentSessionId
                val decisions = mutableListOf<SseSyncDecision>()
                // The streaming overlay is potentially stale after any gap
                // (deltas may have been for an interrupted stream). Always drop.
                decisions.add(SseSyncDecision.ClearDeltaBuffers)
                // Always reload the current session (idempotency for true
                // duplicate server.connected frames within a single connection
                // is handled by isLoadingMessages coalescing at the dispatch
                // layer — same path that absorbs ForegroundCatchUpController's
                // overlapping catch-up effects).
                if (currentSessionId != null) {
                    decisions.add(SseSyncDecision.ReloadSession(currentSessionId, resetLimit = true))
                }
                // session.status frames are only emitted on changes, so an already-busy
                // session needs an authoritative REST refresh after reconnect.
                decisions.add(SseSyncDecision.LoadSessionStatus)
                // If a non-current session was dirty (scenario 3: user switched
                // mid-disconnect), RefreshSessions reconciles its list-level state
                // (badge / last-activity). The current session is handled by
                // ReloadSession above.
                val otherDirty = prev.sessionsDirty.any { it != currentSessionId }
                if (otherDirty) {
                    decisions.add(SseSyncDecision.RefreshSessions)
                }

                // §R-19 fix Blocker 2 v2: sessionsDirty is NOT modified here.
                // The prior "add currentSessionId to dirty for idle dedup"
                // lifecycle had a residual bug — if no idle arrived between
                // the reconnect and a later user-initiated run, the dirty
                // entry lingered and permanently suppressed that later run's
                // idle finalization reload. Idle-driven reloads now rely
                // entirely on the scheduling layer's isLoadingMessages
                // coalescing + the 400ms loadMessagesWithRetry debounce.
                // sessionsDirty is populated ONLY by the Disconnected trigger.
                val newState = prev.copy(
                    lastDisconnectAt = null,
                    sessionsDirty = prev.sessionsDirty
                )
                newState to decisions
            }
        }
    }
}
