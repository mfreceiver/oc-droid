package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.state.AuthorityOp
import cn.vectory.ocdroid.data.state.AuthorityState
import cn.vectory.ocdroid.data.state.Coverage
import cn.vectory.ocdroid.data.state.EntryOrigin
import cn.vectory.ocdroid.data.state.RetryEntry
import cn.vectory.ocdroid.data.state.SessionEntry
import cn.vectory.ocdroid.data.state.ServerRound
import cn.vectory.ocdroid.data.state.serverRoundOrNull
import cn.vectory.ocdroid.ui.controller.normalizeAuthoritativeStatusSnapshot

/** §P1-B/E: cap on [AuthorityState.retryQueue] size (LRU eviction when exceeded). */
private const val RETRY_QUEUE_MAX_SIZE = 256

/** §U-CQ9: cap on [ChatState.pendingErrorCheck] set size (drops oldest entries when exceeded). */
private const val PENDING_ERROR_CHECK_MAX_SIZE = 128

/**
 * §P0-A (B1 option 1): the PURE authority reducer — the SINGLE writer of
 * [StoreState.sessionList.sessionStatuses]. All status mutations funnel here
 * via `dispatch(AppAction.AuthorityEvent(op))`, landing in the single CAS
 * `state.update { reduce(it, action) }`.
 *
 * # Purity / CAS-idempotency (B1 — the rev-gpt gate)
 *
 * This function is PURE:
 *  - No injected dependencies (no repository / identityStore / lock / logger
 *    that mutates). Inputs are ONLY [state] and [op].
 *  - No I/O, no clock reads. Every timestamp is carried in [op]
 *    ([AuthorityOp.ApplyEvent.connectionTimeMs] /
 *    [AuthorityOp.ApplySnapshot.requestToken.requestStartMs] /
 *    [AuthorityOp.ApplyEvent.optimisticBumpTimestamp]) or already in [state].
 *  - No mutation of inputs — `data class copy(...)` builds fresh objects; the
 *    `bySid` / `pendingBumps` maps are replaced, not mutated in place.
 *
 * Therefore `reduceAuthority(state, op)` is referentially transparent: the
 * same `(state, op)` always yields the same [StoreState], so a CAS retry that
 * re-runs the reducer reproduces the same transition (idempotent). rev-gpt
 * verifies this by construction (no impurity source exists in this file).
 *
 * # Drop-on-no-change
 *
 * When a guard rejects the op or an op leaves authority unchanged, this
 * returns the SAME [state] reference (reference-equal) so the CAS layer treats
 * it as a no-op (no emission) — consistent with the existing `reduce` purity
 * contract (AppAction.kt:804-817).
 */
internal fun reduceAuthority(state: StoreState, op: AuthorityOp): StoreState {
    val cur = state.authority
    if (!opScopeValid(op, state)) return state
    val nextAuth: AuthorityState = when (op) {
        is AuthorityOp.ApplyEvent -> applyEvent(cur, op)
        is AuthorityOp.ApplySnapshot -> applySnapshot(cur, op)
        is AuthorityOp.PurgeHost -> applyPurge(cur, op)
        is AuthorityOp.MarkSourceFailed -> applyMarkFailed(cur, op)
        is AuthorityOp.PruneSessions -> applyPrune(cur, op)
        is AuthorityOp.RetryQueued -> applyRetryQueued(cur, op)
        is AuthorityOp.RetryFired -> applyRetryFired(cur, op)
    }
    // §P0-A rev-gpt r2 #5: compute the abort-pending release INDEPENDENT of the
    // same-ref short-circuit. An equal-value terminal-idle ApplyEvent (re-
    // delivered idle frame) hits nextAuth === cur BUT must STILL release
    // abortPendingSessionIds[sid] — the server confirmed a non-running status,
    // so the in-flight abort flag must clear regardless of whether the authority
    // entry itself changed.
    //
    // §P0-B ITEM 1 (confirmation gate interaction): 检查 RESULTING entry 的状态
    // 而非 op.status。当 confirmation gate DROP 了一个 idle（未确认的 optimistic
    // claim 保护），结果 entry 仍是 busy → abort-pending 不释放。这防止 gate 保护
    // 期间 abortPending 被错误清除。
    //
    // 注意：op.sid 仅在 op is ApplyEvent 时可用。提取到 abortEventSid 变量中
    // （在 abortRelease 为 true 时保证非 null），避免在 if 块外引用未 smart-cast 的 op.sid。
    val abortEventSid = (op as? AuthorityOp.ApplyEvent)?.sid
    val abortRelease = if (abortEventSid != null) {
        val resultingEntry = nextAuth.bySid[abortEventSid]
        val resultingTerminal = resultingEntry == null ||
            (!resultingEntry.status.isBusy && !resultingEntry.status.isRetry)
        resultingTerminal && abortEventSid in state.sessionList.abortPendingSessionIds
    } else {
        false
    }
    if (nextAuth === cur) {
        // Authority unchanged. If this is a terminal ApplyEvent that needs to
        // release abort-pending, do so as a REAL transition (bump revision so
        // the aggregator re-derives). Otherwise true no-op (same ref).
        return if (abortRelease && abortEventSid != null) {
            state.copy(
                authorityRevision = state.authorityRevision + 1L,
                sessionList = state.sessionList.copy(
                    abortPendingSessionIds = state.sessionList.abortPendingSessionIds - abortEventSid,
                ),
            )
        } else {
            state
        }
    }
    val projection = projectSessionStatuses(nextAuth)
    val newSessions = applyOptimisticBumps(state.sessionList.sessions, nextAuth.pendingBumps)
    val cleanedAuth = nextAuth.copy(pendingBumps = emptyMap())

    // §P0-E(c): detect busy/retry → terminal-idle transitions and flag them
    // for the ErrorRecoveryCoordinator GET drain. Only real transitions where
    // the sid persists in nextAuth (not pruned/removed) qualify.
    val transitionedToIdle = cur.bySid.filter { (sid, entry) ->
        val nextEntry = nextAuth.bySid[sid]
        (entry.status.isBusy || entry.status.isRetry) && nextEntry != null &&
            !nextEntry.status.isBusy && !nextEntry.status.isRetry
    }.keys
    // §P0-A rev-gpt rework (abort-pending single-CAS): when an ApplyEvent
    // delivers a TERMINAL status (NOT busy AND NOT retry), release the in-flight
    // abort-pending flag for that sid in the SAME state.copy that writes the
    // status projection — atomically (no torn window between the status write
    // and the abort release, which the old SseDispatchHost two-CAS path had).
    val nextAbortPending = if (abortRelease && abortEventSid != null) {
        state.sessionList.abortPendingSessionIds - abortEventSid
    } else {
        state.sessionList.abortPendingSessionIds
    }
    return state.copy(
        authority = cleanedAuth,
        // §P0-A rev-gpt rework (prep Lane B): bump revision ONLY on a real
        // authority transition (we already returned early on no-change above).
        authorityRevision = state.authorityRevision + 1L,
        // §P0-A rev-gpt #8 B10: sessionStatuses is set via [withProjection] (the
        // SOLE writer gate — the public `copy` excludes sessionStatuses). The
        // other sessionList fields (sessions, abortPending) go through `copy`.
        sessionList = state.sessionList.withProjection(projection).copy(
            sessions = newSessions,
            abortPendingSessionIds = nextAbortPending,
        ),
        // §P0-E(c): mark sessions that transitioned busy/retry → terminal-idle
        // so the ErrorRecoveryCoordinator drain can locate the error-bearing
        // assistant via GET getMessages(sid) (B2: session.error carries no
        // messageId). Only real transitions (not guard-rejected no-ops that
        // returned early above) reach this copy.
        chat = state.chat.copy(
            pendingErrorCheck = capPendingErrorCheck(state.chat.pendingErrorCheck + transitionedToIdle),
        ),
    )
}

/**
 * Pure scope/identity guard. Derives ONLY from [state] (no injected identity
 * store, no TOCTOU — §B11).
 *
 *  - [AuthorityOp.ApplySnapshot] / [AuthorityOp.MarkSourceFailed]: host guard
 *    ([token.hostProfileId] vs `state.host.currentHostProfileId`) AND epoch
 *    guard ([token.identityEpoch] vs [StoreState.identityEpoch]). Both must pass.
 *    The host guard closes the host-switch TOCTOU (the adapter captures
 *    `hostProfileId` at request START, not current). The epoch guard is
 *    defense-in-depth inside the CAS: `identityEpoch` is bumped on
 *    `currentHostProfileId` change (via [SharedStateStore.mutateHost]), so a
 *    stale response whose request-start epoch predates a host switch is
 *    dropped. The adapter's dispatch-side `identityStore.currentEpoch()` check
 *    additionally catches endpoint/workdir-only reconfigures (same hostProfileId).
 *    null current host passes leniently (cold-start).
 *  - [AuthorityOp.ApplyEvent]: §B11 (P0-C) identity-epoch guard. When
 *    [op.capturedIdentity] is non-null, the reducer CHECKS
 *    `op.identityEpochAtCapture == state.identityEpoch` — a stale
 *    event/optimistic frame whose capture-epoch predates an identity transition
 *    is DROPPED (returns false). This is defense-in-depth inside the CAS on top
 *    of the dispatch-site [isCurrent] check. When [op.capturedIdentity] is null
 *    (cold-start / test / non-migrated site), the guard passes leniently
 *    (backward compat). The guard is PURE: it derives only from [op.carried data]
 *    and [state]; no injected identity store, no TOCTOU.
 *  - [AuthorityOp.PurgeHost] / [AuthorityOp.PruneSessions]: structurally valid (true).
 */
private fun opScopeValid(op: AuthorityOp, state: StoreState): Boolean {
    return when (op) {
        is AuthorityOp.ApplySnapshot,
        is AuthorityOp.MarkSourceFailed -> {
            val token = when (op) {
                is AuthorityOp.ApplySnapshot -> op.requestToken
                is AuthorityOp.MarkSourceFailed -> op.requestToken
                else -> return true
            }
            val currentHost = state.host.currentHostProfileId
            // Host guard: null currentHost passes leniently (cold-start); otherwise
            // the request-start host must match the live host.
            if (currentHost != null && currentHost != token.hostProfileId) return false
            // §P0-A rev-gpt #2: epoch guard. identityEpoch is bumped on host-profile
            // switch; a stale response captured before the switch has a lower epoch.
            if (token.identityEpoch != state.identityEpoch) return false
            true
        }
        // §B11 (P0-C): identity-epoch guard for event-captured (non-null identity)
        // ApplyEvents. Pure: the epoch comparison uses ONLY op-carried data + state.
        is AuthorityOp.ApplyEvent -> {
            // When capturedIdentity is null (cold-start / non-migrated site), pass
            // leniently — no B11 guard exists for those paths.
            if (op.capturedIdentity != null) {
                // identityEpochAtCapture must match state.identityEpoch. If the
                // identity advanced between capture and this CAS, the op is stale.
                // This is defense-in-depth: the dispatch site already does an
                // isCurrent check, but the reducer guards against TOCTOU races.
                op.identityEpochAtCapture == state.identityEpoch
            } else {
                true
            }
        }
        is AuthorityOp.PurgeHost,
        is AuthorityOp.PruneSessions -> true
        // §U-CQ5: epoch guard for RetryQueued/Fired — DROP if the identity advanced
        // between capture and this CAS. Default 0L backward-compat: passes when
        // state.identityEpoch is also 0L (initial / empty state).
        is AuthorityOp.RetryQueued -> op.identityEpochAtCapture == state.identityEpoch
        is AuthorityOp.RetryFired -> op.identityEpochAtCapture == state.identityEpoch
    }
}

// ── ApplyEvent ────────────────────────────────────────────────────────────

/**
 * §3.1 fence selection by [AuthorityOp.ApplyEvent.origin]. Pure.
 *
 *  - OPTIMISTIC: keep prior serverRound. Record
 *    [AuthorityOp.ApplyEvent.optimisticBumpTimestamp] into pendingBumps (consumed
 *    by [applyOptimisticBumps] in the outer reducer).
 *  - SSE_LEGACY / SSE_SLIM: if serverRound != null → lex strict-monotonic guard
 *    (strictly-less-than → DROP; equal → monotonic tie-break). If null → accept
 *    (no causal fence for P0-A).
 *  - knownIncarnations per-scope high-water (B6): incarnation advance resets that
 *    scope's entries' serverRound; low incarnation → DROP. (Pure, self-contained.)
 *  - §3.1 BLK-2 per-sid serverRound high-water: when the live baseline (prev
 *    serverRound) was cleared by REST/incarnation-reset but a Tier-1 slim frame
 *    with a turn arrives, the lex guard is skipped — the PERSISTENT
 *    [SessionEntry.serverRoundHighWater] watermark still fences a stale low-turn
 *    frame (strictly-older → DROP). Closes the BLK-2 revival window.
 *
 * # KNOWN RESIDUAL (§review-#9, cross-channel ordering)
 *
 * An OPTIMISTIC ApplyEvent (serverRound=null, from the launchSendMessage
 * onSuccess POST callback) systematically bypasses all serverRound-based
 * guards (B6 incarnation / lex / BLK-2). If an SSE terminal idle for the
 * same turn lands BEFORE the POST callback (slow network / GC / scheduler
 * jitter during the prompt_async round-trip), the OPTIMISTIC busy
 * resurrects a false busy over the terminal idle.
 *
 * **Trigger window** (narrower than arbitrary jitter): requires a
 * send-while-busy follow-up + the follow-up turn completing ENTIRELY
 * within the POST round-trip + unfavorable client event ordering.
 * prompt_async is accept-and-respond, so on the pure idle→send main path
 * SSE busy reliably arrives AFTER onSuccess and corrects; the false-busy
 * is largely unreachable there.
 *
 * **Recovery caveat**: SlimFanOutRetryScheduler (fka ProcessStatusPoller)
 * had its background loop deleted in Batch-1 item 17; the backoff/single-flight
 * retry seam is preserved but currently has no production entry trigger.
 * So a false busy on a foreground first-opened session may stick
 * until the next session activity or backgrounding — NOT a 30s self-heal.
 *
 * **Fix direction** (patch-level, deferred): capture a dispatch-time
 * watermark (sendDecisionTimeMs) at launchSendMessage entry; guard
 * condition becomes `prev.updatedAtMs > sendDecisionTimeMs` (distinguishes
 * "old idle baseline" ≪ watermark from "this turn's terminal idle" >
 * watermark). Single wall-clock domain (already established). Do NOT use a
 * naive `prev.status`-only guard — it cannot distinguish the two idle
 * scenarios and regresses send-while-idle (the main user path).
 *
 * # §review-note-N2 (origin semantics)
 *
 * `origin` reflects the most-recent write source. An OPTIMISTIC ApplyEvent
 * (serverRound=null) passes through keepRound which preserves
 * prev.serverRound, so an entry may show a MIXED semantic of origin=OPTIMISTIC
 * but serverRound != null (carried from a prior slim SSE write; REST
 * snapshots do not produce serverRound). The sole behavioral consumer of
 * origin was the retired StatusAggregatorImpl's fresh derivation (F6); origin
 * is still written/stored (§B9 ServerBusy classification) and the mixed
 * semantic has NO behavioral impact. Future consumers MUST be aware: origin
 * denotes the last writer, NOT the provenance of serverRound.
 *
 * # §review-note-N6 (coverage)
 *
 * applyEvent deliberately does NOT update coverage[scope].lastSuccessTimeMs.
 * coverage denotes "REST confirmed complete workdir coverage"; SSE pushes
 * per-sid deltas and cannot confirm full coverage. So under sustained REST
 * failure (applyMarkFailed sets lastSuccessTimeMs=-1) plus SSE-delivered
 * idle, global state stays Unknown (not AllIdleFresh) — this is fail-safe
 * design (prefer keep-alive over falsely-idle-then-miss-event). Only
 * applySnapshot restores coverage.
 */
private fun applyEvent(cur: AuthorityState, op: AuthorityOp.ApplyEvent): AuthorityState {
    val prev = cur.bySid[op.sid]
    val highWater = cur.knownIncarnations[op.scopeKey] ?: 0L

    // ── §3.1 Tier-1 slim fence (B6 per-scope incarnation high-water) ──
    if (op.serverRound != null && op.serverRound.incarnation < highWater) {
        // §B6: old-incarnation frame must not revive → DROP.
        return cur
    }

    // ── serverRound lex guard (strict monotonic) ──
    if (op.serverRound != null && prev?.serverRound != null) {
        val cmp = op.serverRound.compareTo(prev.serverRound)
        if (cmp < 0) return cur // strictly older → DROP (§3.1 line 303)
        if (cmp == 0 && op.connectionTimeMs < prev.updatedAtMs) {
            // §U-P3 / §U-MN9: both timestamps are System.currentTimeMillis()
            // (single wall-clock domain — sseClock() ← clock() ← currentTimeMillis,
            // requestStartMs ← clock() ← currentTimeMillis). The pre-U-MN9 comment
            // claiming "cross-clock-domain" was STALE and is removed. The comparison
            // is valid; fail-closed direction preserved (drop equal+older).
            return cur
        }
    }

    // ── §3.1 BLK-2: baseline-cleared Tier-1 watermark guard ──
    // The lex guard above requires prev.serverRound != null. When the live baseline
    // was cleared (REST ApplySnapshot / incarnation-advance scope reset) but a Tier-1
    // slim frame carrying a turn arrives, the lex guard is skipped — and without this
    // guard a stale LOW-turn frame would apply unconditionally, reviving stale busy
    // (BLK-2 window). Compare the incoming frame against the PERSISTENT per-sid
    // serverRound high-water (which survives the clear) and DROP a strictly-older
    // (incarnation,turn). Reaching here means op.incarnation >= scope highWater (older
    // incarnations were already DROPPED by the B6 guard above), so a `<` result is a
    // same-incarnation stale-low-turn.
    // prev == null / watermark == null (cold start) → no watermark → establish baseline.
    //
    // §U-P3: legacy SSE busy no longer clears the baseline (keepRound preserves it),
    // so prev.serverRound==null only arises from REST applySnapshot or incarnation-
    // advance scope reset (not legacy SSE).
    //
    // KNOWN RESIDUAL: only strictly-older `<` is DROPped, not equal-turn (mirroring
    // §3.1 "strictly DROP low turn"). An EQUAL-turn frame after baseline clear is
    // accepted (re-establishes baseline). The `==0` monotonic tie-break from the live
    // lex guard is NOT mirrored here — both timestamps are System.currentTimeMillis()
    // (single wall-clock domain — see §U-P3 comment at :263-265), but the equal-turn
    // accept is a deliberate fail-closed choice: a stale equal-turn digest arriving
    // during a brief baseline window is the lesser evil versus a fail-open rejection
    // of a legitimate equal-turn re-establishment. The strictly-low window — the
    // dominant, determinism-critical revival vector — is closed.
    //
    // §U-CQ4: the watermark is per-entry (per-sid). On entry deletion (prune / archive
    // / REST-not-present) the watermark is lost. This is safe because incarnation is
    // tied to the server process lifecycle — a deleted sid cannot revive under the
    // same incarnation. See also guard kdoc at :293-298.
    if (op.serverRound != null && prev != null && prev.serverRound == null &&
        prev.serverRoundHighWater != null &&
        op.serverRound < prev.serverRoundHighWater
    ) {
        // §U-CQ4: this guard's watermark is per-entry; on entry deletion (prune,
        // archive, REST-not-present, applyMarkFailed, FETCH_FAILED) the watermark is
        // lost. This is safe because incarnation is tied to the server process lifecycle
        // — a deleted sid cannot revive under the same incarnation. If an entry is
        // deleted and the same sid arrives with the same incarnation, prev is null and
        // this guard is skipped (accepted). That is a known residual covered by the
        // incarnation semantic guarantee. A per-sid map (backlog) would close this
        // window but is not required for correct operation.
        return cur // §3.1 BLK-2: stale low-turn after baseline clear → DROP
    }

    // ── next entry fields ──
    // §U-P3: a frame with no serverRound (legacy SSE busy / optimistic) MUST NOT
    // clear the prior slim baseline — the legacy frame is causally weaker (no
    // incarnation/turn) and clearing the baseline would reopen the BLK-2 revival
    // window. Only a fresh slim frame (serverRound != null) or REST whole-graph
    // replace (applySnapshot, separate path) legitimately advances/clears.
    val keepRound: ServerRound? = op.serverRound ?: prev?.serverRound

    // §3.1 BLK-2: advance the persistent per-sid serverRound high-water. A non-null
    // incoming Tier-1 serverRound folds forward (lex max — never regresses); for
    // OPTIMISTIC/legacy/IDLE (op.serverRound == null) the watermark is preserved
    // unchanged so it survives the baseline clear. This is computed for ALL accepted
    // frames; nextEntry carries it through both the normal and incarnation-advance
    // return paths (a new incarnation's round lex-dominates the prior high-water).
    val prevHw = prev?.serverRoundHighWater
    val nextHighWater: ServerRound? = if (op.serverRound != null) {
        if (prevHw == null || op.serverRound > prevHw) op.serverRound else prevHw
    } else {
        prevHw
    }

    val nextEntry = SessionEntry(
        status = op.status,
        serverRound = keepRound,
        origin = op.origin,
        updatedAtMs = op.connectionTimeMs,
        workdir = op.workdir ?: prev?.workdir,
        scopeKey = op.scopeKey,
        serverRoundHighWater = nextHighWater,
    )

    val nextPending = addPendingBump(cur.pendingBumps, op)

    // ── incarnation high-water advance (B6): bump + reset that scope's rounds ──
    if (op.serverRound != null && op.serverRound.incarnation > highWater) {
        // Slim server restart (incarnation advance) invalidates prior turns:
        // reset only THIS scope's entries' serverRound (scope-filtered, not
        // all scopes — consistent with applyMarkFailed/applyPrune/applySnapshot
        // scope-filtering). Entries with null scopeKey are treated as in-scope.
        val resetById = cur.bySid.mapValues { (_, e) ->
            if (e.serverRound != null && (e.scopeKey == null || e.scopeKey == op.scopeKey)) {
                e.copy(serverRound = null)
            } else {
                e
            }
        }
        return cur.copy(
            bySid = resetById + (op.sid to nextEntry),
            knownIncarnations = cur.knownIncarnations + (op.scopeKey to op.serverRound.incarnation),
            pendingBumps = nextPending,
            // §P1-B/E: clean retry entry on terminal status
            retryQueue = if (!op.status.isBusy && !op.status.isRetry) {
                cur.retryQueue - op.sid
            } else {
                cur.retryQueue
            },
        )
    }

    // §P0-A rev-gpt #1 (no-change same-ref): if the recomputed entry equals
    // the prior entry (data-class equality: same status, serverRound,
    // origin, updatedAtMs, workdir) AND pendingBumps is
    // unchanged (addPendingBump returned the same reference) → NO transition →
    // return cur (same ref). This makes an equal-value SSE re-delivery a true
    // CAS no-op (no emission), completing the B1 idempotency contract.
    //
    // §P1-B/E rev-glm N4: a terminal status re-delivery (nextEntry == prev,
    // both terminal) MUST still clean a stale retry entry — a sid queued by an
    // earlier 503 then confirmed terminal by this equal-value frame would
    // otherwise leak (the normal + incarnation-advance paths both clean, but
    // this no-change early-return was the gap). Only fires when the sid is
    // actually in the queue (real transition); otherwise stays a same-ref no-op.
    if (nextEntry == prev && nextPending === cur.pendingBumps) {
        return if (!op.status.isBusy && !op.status.isRetry && op.sid in cur.retryQueue) {
            cur.copy(retryQueue = cur.retryQueue - op.sid)
        } else {
            cur
        }
    }

    return cur.copy(
        bySid = cur.bySid + (op.sid to nextEntry),
        pendingBumps = nextPending,
        // §P1-B/E: clean retry entry on terminal status
        retryQueue = if (!op.status.isBusy && !op.status.isRetry) {
            cur.retryQueue - op.sid
        } else {
            cur.retryQueue
        },
    )
}

/** Pure: record [AuthorityOp.ApplyEvent.optimisticBumpTimestamp] (B8). */
private fun addPendingBump(
    pending: Map<String, Long>,
    op: AuthorityOp.ApplyEvent,
): Map<String, Long> {
    val ts = op.optimisticBumpTimestamp ?: return pending
    // Monotonic: keep the max so a retried CAS / replay never regresses.
    val prior = pending[op.sid]
    return if (prior == null || ts > prior) pending + (op.sid to ts) else pending
}

// ── ApplySnapshot ──────────────────────────────────────────────────────────

/**
 * Whole-graph authoritative replacement within the covered scope. Pure.
 *
 * §Plan-A (P0-C): consumes per-sid ServerRound from REST via pair-rule,
 * applying a universal lex-max fold (not legacy "clear on REST"). See
 * P0C-DESIGN.md §5 for the full algorithm.
 *
 * Steps 1-4, 8-10 match the legacy structure; steps 5-7 are the new
 * per-sid round rule (claim/decision matrix deleted).
 */
private fun applySnapshot(cur: AuthorityState, op: AuthorityOp.ApplySnapshot): AuthorityState {
    val normalized = normalizeAuthoritativeStatusSnapshot(op.snapshot, op.authoritativeNodeIds)
    // §scope-guard: only in-scope entries participate in the in-flight merge.
    val currentProjection = projectSessionStatuses(cur).filterKeys { sid ->
        val entry = cur.bySid[sid]
        entry == null || entry.scopeKey == null || entry.scopeKey == op.scopeKey
    }

    // slim failed-dir preservation.
    val failedSids: Set<String> = op.sidToWorkdir.entries
        .asSequence()
        .filter { it.value in op.partialFailureWorkdirs }
        .map { it.key }
        .toSet()
    val preserved: Map<String, cn.vectory.ocdroid.data.model.SessionStatus> =
        currentProjection.filterKeys { it in failedSids }
    val restSnapshot: Map<String, cn.vectory.ocdroid.data.model.SessionStatus> =
        LinkedHashMap(normalized).apply { putAll(preserved) }

    // §review-blocker-#7 (P0-C status-merge sync): compute the in-flight SSE-win
    // sid set ONCE, shared by mergeStatusSnapshotInFlight (status choice) and the
    // step-6b loop (origin/updatedAtMs/round choice), so status and causal metadata
    // can NEVER tear across REST/SSE sources. This is the :501-503 inFlightWin
    // predicate lifted verbatim — status-diff arm OR the #6 R==null timestamp arm
    // (meta-only in-flight SSE: busy→busy@(1,7)@2000 leaves the round-stripped
    // projection equal to localBefore, so the status arm alone misses it; without
    // this set the loop preserved SSE meta while the merge kept a DIFFERING REST
    // status → torn entry → late equal-round SSE fenced against the SSE timestamp,
    // freezing REST's wrong status). The R==null gate is load-bearing: non-null-R
    // REST is already resolved by the :504-506 lex-fence (T10 Op-2), so the
    // timestamp arm must stay confined to the one path that fence cannot reach.
    // NOTE (oracle edge case #1): ids present in currentProjection with a fresh
    // prior but ABSENT from op.snapshot/restSnapshot are now RETAINED with their
    // SSE status+meta by this set (pre-fix they were silently dropped). This is
    // correct — a fresher in-flight SSE update causally wins over the REST snapshot
    // — and aligns meta-only with the existing status-diff behavior on that path.
    val inFlightWinSids: Set<String> = currentProjection.keys.filterTo(LinkedHashSet()) { id ->
        val prior = cur.bySid[id]
        op.localBefore[id] != currentProjection[id] ||
            (prior != null &&
                op.snapshot[id]?.serverRoundOrNull() == null &&
                prior.updatedAtMs > op.requestToken.requestStartMs)
    }

    // REST in-flight (SSE-wins) protection.
    val merged = mergeStatusSnapshotInFlight(restSnapshot, currentProjection, inFlightWinSids)

    // §5 step 5: snapInc = max incarnation across all paired rounds in snapshot.
    val snapInc = op.snapshot.values
        .mapNotNull { it.serverRoundOrNull()?.incarnation }
        .maxOrNull()

    val nextById = LinkedHashMap<String, SessionEntry>()
    // Step 6a: copy out-of-scope entries verbatim.
    for ((sid, entry) in cur.bySid) {
        if (entry.scopeKey != null && entry.scopeKey != op.scopeKey) {
            nextById[sid] = entry
        }
    }
    // Step 6b: universal per-sid round rule for merged entries.
    for ((id, mergedStatus) in merged) {
        val prior = cur.bySid[id]
        // Strip round fields from status before storing (projection hygiene).
        val cleanStatus = mergedStatus.stripRound()
        // Failed-dir branch (simplified: claim logic deleted).
        if (id in failedSids && prior != null) {
            nextById[id] = prior.copy(status = cleanStatus, scopeKey = op.scopeKey)
            continue
        }
        // — universal per-sid round rule (D1 table) —
        val R = op.snapshot[id]?.serverRoundOrNull()
        val live0 = prior?.serverRound
        val hw0 = prior?.serverRoundHighWater
        val effBase = lexMaxNull(live0, hw0)
        // §review-blocker-#6 (P0-C meta-only): the status-diff arm alone MISSES a
        // meta-only in-flight SSE when REST returns a NULL round (R==null) — one
        // that advanced ONLY round/time without changing the status value
        // (busy→busy at a newer (incarnation,turn) + later connectionTimeMs).
        // currentProjection stores round-stripped status (stripRound at :455), so
        // localBefore[id] == currentProjection[id] → the status arm reads "no
        // tear" → inFlightWin=false → the else branch stamps updatedAtMs =
        // op.requestToken.requestStartMs, REGRESSING the SSE's fresher
        // updatedAtMs. A subsequent same-round late SSE then passes the
        // applyEvent equal-round tie-break (cmp==0 && connectionTimeMs <
        // prev.updatedAtMs) because the regressed prev.updatedAtMs is now LOWER
        // — the original #3 blocker resurrected under a meta-only trigger.
        //
        // The timestamp arm closes this — but ONLY for the R==null path. When
        // R != null, the existing :469 lex-fence already compares
        // `requestStartMs < prior.updatedAtMs` (the SAME wall-clock signal), so
        // the timestamp arm would be REDUNDANT there and would over-protect:
        // it would force inFlightWin=true for a non-null-R REST whose equal-
        // round tie-break the :469 fence already resolves correctly (e.g. T10
        // Op-2: R=(1,5)==effBase, requestStartMs=500 < updatedAtMs=1000 → the
        // :469 fence preserves prior verbatim; an unguarded timestamp arm would
        // short-circuit that fence and leak REST's differing status through).
        // Guarding on R==null confines the new arm to the ONE path the existing
        // fence cannot reach (the legitimate null-round REST fallback — legacy /
        // unwired-registry / bad-shape snapshot per OpenCodeRepository:1125-1144).
        //
        // Single wall-clock domain (§U-P3 / AuthorityOp.connectionTimeMs kdoc):
        // both REST requestStartMs and SSE connectionTimeMs source the SAME
        // System.currentTimeMillis(), so the comparison mirrors the existing
        // equal-round tie-break at applyEvent:247 and the :469 lex-fence. Strict
        // `>` (not >=) mirrors :469's direction. Origin-agnostic by design; the
        // arm's false-negative mode is self-neutralizing (a backwards clock that
        // hides an in-flight SSE also lowers that SSE's own timestamp, so the
        // REST stamp cannot regress below it). Oracle-assessed (ses_03bbaf126ffe,
        // Plan B, refined to R==null-only after T10 regression analysis).
        //
        // §review-blocker-#7 (P0-C status-merge sync, r3): the timestamp arm above
        // correctly preserved SSE METADATA but the parallel status choice in
        // mergeStatusSnapshotInFlight still used status-diff alone → a meta-only
        // SSE + differing-status null-R REST tore status (REST) from meta (SSE).
        // Both arms now share ONE precomputed inFlightWinSids set (see :437 block)
        // so the two choices can never diverge.
        val inFlightWin = id in inFlightWinSids
        val fenced = !inFlightWin && R != null && effBase != null &&
            (R < effBase ||
                (R == effBase && op.requestToken.requestStartMs < (prior?.updatedAtMs ?: 0L)))
        if (fenced) {
            nextById[id] = prior!!  // verbatim — fresher causal knowledge wins
        } else {
            // §review-blocker-#3 (correctness): when SSE won in-flight
            // (inFlightWin=true), the mergedStatus content already came from
            // the SSE projection (mergeStatusSnapshotInFlight restored it).
            // The causal metadata MUST also stay SSE — writing origin=REST +
            // updatedAtMs=requestStartMs would regress the timestamp to the
            // REST request's start (e.g. 1000ms when SSE landed at 2000ms).
            // A subsequent same-round late SSE (1500ms) would then pass the
            // equal-round tie-break (1500 < 1000 is false) and corrupt state.
            // Preserve prior.origin + prior.updatedAtMs on inFlightWin; only
            // the pure REST path (no concurrent SSE update) stamps REST.
            val effectiveOrigin = if (inFlightWin && prior != null) prior.origin else EntryOrigin.REST
            val effectiveUpdatedAt = if (inFlightWin && prior != null) prior.updatedAtMs else op.requestToken.requestStartMs
            nextById[id] = SessionEntry(
                status = cleanStatus,
                serverRound = lexMaxNull(live0, R),  // preserve on null R — NO clear
                origin = effectiveOrigin,
                updatedAtMs = effectiveUpdatedAt,
                workdir = op.sidToWorkdir[id],
                scopeKey = op.scopeKey,
                serverRoundHighWater = lexMaxNull(hw0, R),
            )
        }
    }

    // §5 step 7: incarnation high-water bump (D4). No per-entry reset.
    val nextIncarnations = if (snapInc != null &&
        snapInc > (cur.knownIncarnations[op.scopeKey] ?: 0L)
    ) {
        cur.knownIncarnations + (op.scopeKey to snapInc!!)
    } else {
        cur.knownIncarnations
    }

    val nextCoverage = cur.coverage + (op.scopeKey to Coverage(
        registeredWorkdirs = op.registeredWorkdirs,
        coveredWorkdirs = op.coveredWorkdirs,
        unmappedActiveIds = op.unmappedActiveIds,
        lastSuccessTimeMs = op.lastSuccessTimeMs,
    ))

    // §P0-A rev-gpt #1 (no-change same-ref): if the merged bySid equals the
    // prior bySid (same keys + data-class-equal entries) AND the coverage entry
    // for this scope is unchanged → NO transition → return cur (same ref).
    //
    // §P1-B/E rev-ogpt B4: a snapshot can transition a sid busy/retry → idle
    // (terminal). Those sids must leave the retry queue.
    val nextRetryQueue = if (cur.retryQueue.isEmpty()) {
        cur.retryQueue
    } else {
        val terminalQueued = cur.retryQueue.keys.filter { sid ->
            val e = nextById[sid]
            (e == null || (!e.status.isBusy && !e.status.isRetry)) &&
                (e?.scopeKey == null || e.scopeKey == op.scopeKey)
        }
        if (terminalQueued.isEmpty()) cur.retryQueue else cur.retryQueue - terminalQueued.toSet()
    }
    val priorCov = cur.coverage[op.scopeKey]
    val coverageUnchanged = priorCov != null &&
        priorCov.registeredWorkdirs == op.registeredWorkdirs &&
        priorCov.coveredWorkdirs == op.coveredWorkdirs &&
        priorCov.unmappedActiveIds == op.unmappedActiveIds &&
        priorCov.lastSuccessTimeMs == op.lastSuccessTimeMs
    if (nextById == cur.bySid && coverageUnchanged && nextRetryQueue === cur.retryQueue &&
        nextIncarnations == cur.knownIncarnations
    ) return cur

    return cur.copy(
        bySid = nextById,
        knownIncarnations = nextIncarnations,
        coverage = nextCoverage,
        retryQueue = nextRetryQueue,
    )
}

// ── PurgeHost ──────────────────────────────────────────────────────────────

/** §4c.3: clears the scope. Pure.
 *  (P0-A host-purge path resets authority directly in the reducer copy; this
 *  op is implemented for typed completeness.)
 *
 *  §sm-hardening note (rev-glm ses_04ccdaa78 nit#1 — non-blocking): purge
 *  resets `bySid = emptyMap()` unconditionally, which is correct under the
 *  P0-A single-active-scope invariant (the data model's `Map<ScopeKey,...>`
 *  notwithstanding). A per-entry `scopeKey` filter (matching [applyPrune]) would
 *  be the strictly safer future-proofing, but that is a behavior change deferred
 *  to a dedicated multi-scope epic — NOT done here, per "仅加固，不改核心状态机逻辑".
 *
 *  §P1-B/E rev-ogpt B2: `retryQueue` IS cleared on purge. The queue has no
 *  per-entry scope (keyed by sid), so a host switch leaves host A's queued sids
 *  pointing at stale data. Clearing unconditionally matches the `bySid` reset
 *  and closes the cross-host leak (host A's queued sid fire-then-requeue under
 *  host B with inherited attempt counter).
 *
 *  §需求12阶段3 (oracle-assessed): the former same-group early-return
 *  (`preserveServerGroup=true`) is dead under 需求12 (profiles independent;
 *  C-8 restart kills preserved slices anyway), so the flag + branch were
 *  removed — PurgeHost now ALWAYS clears. */
private fun applyPurge(cur: AuthorityState, op: AuthorityOp.PurgeHost): AuthorityState {
    // rev-ogpt B2: include retryQueue in the emptiness check so a purge when
    // ONLY retryQueue has content still clears it (was previously skipped).
    if (cur.bySid.isEmpty() && op.scopeKey !in cur.knownIncarnations && op.scopeKey !in cur.coverage && cur.retryQueue.isEmpty()) {
        return cur
    }
    return cur.copy(
        bySid = emptyMap(),
        knownIncarnations = cur.knownIncarnations - op.scopeKey,
        coverage = cur.coverage - op.scopeKey,
        // rev-ogpt B2: clear the retry queue — sids belong to the purged host.
        retryQueue = emptyMap(),
    )
}

// ── MarkSourceFailed ───────────────────────────────────────────────────────

/**
 * REST source failure: covered entries become fail-closed UNKNOWN. The UI
 * projection only carries SessionStatus (idle/busy/retry) — there is no
 * "Unknown" status, so markFailed maps to ABSENCE (remove from bySid) so the
 * single absence-reader (`sid !in sessionStatuses`) reads unknown.
 *
 * §P0-A Lane 2 (aggregator derivation): MERGE TIMING is applied — entries
 * FRESHER than the failure ([op.monotonic]) SURVIVE (a prior SSE `Busy` /
 * `Retry` whose `updatedAtMs > monotonic` is NOT clobbered by a stale
 * failure, matching the legacy `markRequestFailed` merge-timing rule). Entries
 * with `updatedAtMs <= monotonic` are removed (absence ≡ unknown). This
 * preserves the FGS-lifecycle guarantee that a failure never wrongly clears a
 * fresher busy observation.
 *
 * Coverage: [Coverage.registeredWorkdirs] is preserved from [op] (the failure
 * caller carries the snapshot's registered set so the coverage predicate keeps
 * gating `AllIdleFresh`); `coveredWorkdirs` is emptied and
 * `lastSuccessTimeMs = -1` marks the coverage as failed/stale (cold-start
 * guard) — preserving the FGS-lifecycle guarantee that a failure never reads
 * as idle (the former aggregator projection's `Unknown` verdict, retired in
 * F6).
 *
 * Purity: this branch is pure (no injected deps, no clock read — [op.monotonic]
 * is carried data). Same `(state, op)` always yields the same output (CAS retry
 * idempotent).
 */
private fun applyMarkFailed(cur: AuthorityState, op: AuthorityOp.MarkSourceFailed): AuthorityState {
    // §P0-A r2 #4: scope-filtering by [scopeKey] (not workdir approximation).
    // Keep an entry if EITHER (a) it is FRESHER than the failure
    // (updatedAtMs > monotonic — a prior SSE Busy/Retry survives), OR
    // (b) it is OUT OF SCOPE (entry.scopeKey != null AND entry.scopeKey !=
    // op.scopeKey — a different scope's entry must NOT be swept by this
    // scope's failure, even if it happens to share a workdir). Remove only
    // in-scope + at-or-older-than-failure entries (absence ≡ unknown,
    // fail-closed). Entries with null scopeKey (pre-field migration) are
    // conservatively treated as in-scope.
    val survivors = cur.bySid.filterValues { entry ->
        entry.updatedAtMs > op.monotonic ||
            (entry.scopeKey != null && entry.scopeKey != op.scopeKey)
    }
    val nextCoverage = cur.coverage + (op.scopeKey to Coverage(
        registeredWorkdirs = op.registeredWorkdirs,
        coveredWorkdirs = emptySet(),
        unmappedActiveIds = emptySet(),
        lastSuccessTimeMs = -1L,
    ))
    // Drop-on-no-change: if nothing actually transitioned, return the same
    // reference so the CAS layer treats this as a no-op (no emission).
    val bySidChanged = survivors.size != cur.bySid.size ||
        survivors.any { (k, v) -> cur.bySid[k] !== v }
    val priorCov = cur.coverage[op.scopeKey]
    val covChanged = priorCov == null ||
        priorCov.registeredWorkdirs != op.registeredWorkdirs ||
        priorCov.coveredWorkdirs.isNotEmpty() ||
        priorCov.unmappedActiveIds.isNotEmpty() ||
        priorCov.lastSuccessTimeMs != -1L
    if (!bySidChanged && !covChanged) return cur
    return cur.copy(bySid = survivors, coverage = nextCoverage)
}

// ── PruneSessions ──────────────────────────────────────────────────────────

/**
 * §B5: drop [AuthorityOp.PruneSessions.sids] from bySid for matching scope only. Pure.
 *
 * §P0-A FIX: only removes entries whose [SessionEntry.scopeKey] matches the op's
 * scope ([op.scopeKey]) or is null (pre-field migration, backward-compat). Out-of-
 * scope entries (different [scopeKey]) are retained — consistent with
 * [applyMarkFailed]'s scope filtering.
 *
 * §P1-B/E rev-ogpt B4: pruned sids (deleted/archived lifecycle) also leave the
 * retry queue — a gone session has no retry to track. In-scope prune only
 * (matches the bySid filter); out-of-scope queue entries survive.
 */
private fun applyPrune(cur: AuthorityState, op: AuthorityOp.PruneSessions): AuthorityState {
    if (op.sids.isEmpty()) return cur
    val nextById = cur.bySid.filterNot { (sid, entry) ->
        sid in op.sids && (entry.scopeKey == null || entry.scopeKey == op.scopeKey)
    }
    // rev-ogpt B4: drop retry entries for in-scope pruned sids. The queue has
    // no per-entry scope, so we conservatively drop any sid in op.sids whose
    // bySid entry was pruned (scopeKey matched) OR has no bySid entry (was
    // already absent — queued retry for a sid that no longer exists).
    val sidsToClean = cur.retryQueue.keys.filter { sid ->
        sid in op.sids && (cur.bySid[sid]?.scopeKey?.let { it == op.scopeKey } ?: true)
    }
    val nextRetryQueue = if (sidsToClean.isEmpty()) cur.retryQueue else cur.retryQueue - sidsToClean.toSet()
    return if (nextById.size == cur.bySid.size && nextRetryQueue === cur.retryQueue) {
        cur
    } else {
        cur.copy(bySid = nextById, retryQueue = nextRetryQueue)
    }
}

// ── RetryQueued (P1-B/E) ──────────────────────────────────────────────────

/**
 * §P1-B/E: enqueue a retry entry. BOUNDED: the queue is STRICTLY capped at
 * RETRY_QUEUE_MAX_SIZE — when full, entries with the smallest queuedAtMs
 * are evicted until at or under the cap (LRU-style). If [sid] is already
 * queued, it is OVERWRITTEN (refreshed); the overwrite counts as one removal
 * + one insert so capacity is preserved without extra eviction. Bumps
 * authorityRevision on a real change (new insert OR overwrite with different
 * RetryEntry).
 *
 * §P1-B/E rev-gpt (终审): the terminal-status fence proposed by rev-ogpt B3
 * was REMOVED — it misfired on the NORMAL retry case (an idle session whose
 * status fetch returns 503 is a legitimate retry; the fence would have
 * wrongly dropped it). Stale-summary re-entry after a terminal event
 * (rev-ogpt B3 / rev-gpt 严重1-3) is a known residual: it is self-healing
 * (the next sweep's RetryFired, LRU eviction, or a subsequent terminal
 * cleanup corrects it), and a proper fix requires sweep-generation / request-
 * token causal fencing — out of scope for this wire ticket (which adds
 * external dispatch without changing the reducer purity contract). Documented
 * in spec §8.5.
 */
private fun applyRetryQueued(cur: AuthorityState, op: AuthorityOp.RetryQueued): AuthorityState {
    val entry = RetryEntry(attempt = op.attempt, backoffMs = op.backoffMs, queuedAtMs = op.queuedAtMs)
    val existing = cur.retryQueue[op.sid]
    return if (existing == entry) {
        cur  // same ref, no transition
    } else {
        val withInserted = cur.retryQueue + (op.sid to entry)
        // rev-ogpt B1 / rev-gpt: STRICT cap — evict smallest-queuedAtMs
        // entries until at or under RETRY_QUEUE_MAX_SIZE.
        var bounded = withInserted
        while (bounded.size > RETRY_QUEUE_MAX_SIZE) {
            val oldestKey = bounded.minByOrNull { it.value.queuedAtMs }?.key ?: break
            bounded = bounded - oldestKey
        }
        // rev-gpt 4: when the new entry self-evicts (was the oldest), the
        // resulting map equals cur.retryQueue → return cur (same-ref no-op),
        // NOT a spurious copy + revision bump. Keeps the drop-on-no-change
        // / replay-stability contract.
        if (bounded === cur.retryQueue || bounded == cur.retryQueue) {
            cur
        } else {
            cur.copy(retryQueue = bounded)
        }
    }
}

/**
 * §P1-B/E: dequeue a retry entry (the retry was fired externally). Removes
 * [op.sid] from retryQueue. Bumps authorityRevision only if the sid was
 * actually present (real change); same-ref no-op otherwise.
 */
private fun applyRetryFired(cur: AuthorityState, op: AuthorityOp.RetryFired): AuthorityState {
    return if (op.sid in cur.retryQueue) {
        cur.copy(retryQueue = cur.retryQueue - op.sid)
    } else {
        cur
    }
}

// ── projection + bumps ─────────────────────────────────────────────────────

/**
 * §2.3 line 261 / §4c.2: `bySid.mapValues { it.value.status }`. Preserves every
 * id present in bySid; ABSENCE from bySid ≡ ABSENCE from the projection ≡
 * fail-closed unknown (UnreadSoakController).
 */
internal fun projectSessionStatuses(auth: AuthorityState): Map<String, cn.vectory.ocdroid.data.model.SessionStatus> =
    auth.bySid.mapValues { it.value.status }

/**
 * §B8: apply pending optimistic bumps to `sessions` via the existing monotonic
 * [bumpSessionUpdated] (never decreases `time.updated`). Pure; returns the
 * unchanged list when [pendingBumps] is empty. Re-applying the same bump is a
 * no-op (monotonic max) → CAS-retry idempotent.
 */
internal fun applyOptimisticBumps(
    sessions: List<Session>,
    pendingBumps: Map<String, Long>,
): List<Session> {
    if (pendingBumps.isEmpty()) return sessions
    var current = sessions
    for ((sid, ts) in pendingBumps) {
        current = bumpSessionUpdated(current, sid, ts)
    }
    return current
}

/**
 * §U-CQ9: cap the [pendingErrorCheck] set at [PENDING_ERROR_CHECK_MAX_SIZE] to
 * prevent unbounded growth under rapid busy↔idle oscillations. Drops the OLDEST
 * entries (first in insertion order) when the set exceeds the cap. Pure.
 */
private fun capPendingErrorCheck(set: Set<String>): Set<String> {
    if (set.size <= PENDING_ERROR_CHECK_MAX_SIZE) return set
    return set.drop(set.size - PENDING_ERROR_CHECK_MAX_SIZE).toSet()
}

/**
 * §P0-A: builds an [AuthorityOp.ApplySnapshot] from the common REST/hydration
 * inputs, deriving the §B3 coverage bookkeeping (sidToWorkdir /
 * registeredWorkdirs) from the caller's authoritative-sessions map. Pure.
 * Reduces boilerplate across the 5 REST/hydration writer paths.
 *
 * [authoritativeSessions] is the caller's proven tree map (id → Session) — the
 * reducer uses [authoritativeNodeIds] for idle-normalization and [localBefore]
 * for the REST in-flight protection; [coveredWorkdirs] /
 * [partialFailureWorkdirs] / [unmappedActiveIds] / [lastSuccessTimeMs] flow
 * into [AuthorityState.coverage] (consumed by P0-B AllIdleFresh, stored now).
 */
internal fun buildAuthorityApplySnapshot(
    snapshot: Map<String, cn.vectory.ocdroid.data.model.SessionStatus>,
    authoritativeSessions: Map<String, Session>,
    authoritativeNodeIds: Set<String>,
    coveredWorkdirs: Set<String>,
    partialFailureWorkdirs: Set<String>,
    unmappedActiveIds: Set<String>,
    lastSuccessTimeMs: Long,
    scopeKey: cn.vectory.ocdroid.data.state.ScopeKey,
    requestToken: cn.vectory.ocdroid.data.state.RequestToken,
    localBefore: Map<String, cn.vectory.ocdroid.data.model.SessionStatus>,
): cn.vectory.ocdroid.data.state.AuthorityOp.ApplySnapshot {
    val sidToWorkdir = authoritativeSessions.mapValues { it.value.directory }
    val registeredWorkdirs = authoritativeSessions.values.asSequence()
        .map { it.directory }
        .filter { it.isNotBlank() }
        .toSet()
    return cn.vectory.ocdroid.data.state.AuthorityOp.ApplySnapshot(
        snapshot = snapshot,
        sidToWorkdir = sidToWorkdir,
        authoritativeNodeIds = authoritativeNodeIds,
        registeredWorkdirs = registeredWorkdirs,
        coveredWorkdirs = coveredWorkdirs,
        unmappedActiveIds = unmappedActiveIds,
        partialFailureWorkdirs = partialFailureWorkdirs,
        lastSuccessTimeMs = lastSuccessTimeMs,
        scopeKey = scopeKey,
        requestToken = requestToken,
        localBefore = localBefore,
    )
}

/**
 * §sse-rest-race pure in-flight protection: for each id in [inFlightWinSids]
 * (the precomputed §review-blocker-#7 set — status-diff OR R==null timestamp
 * arm, ⊆ [localAfter].keys), the SSE-wins value [localAfter] overrides the
 * REST snapshot. The win judgment is computed ONCE at the call site and
 * shared with the applySnapshot loop so status + origin/updatedAtMs/round
 * can never tear across sources.
 */
private fun mergeStatusSnapshotInFlight(
    restSnapshot: Map<String, cn.vectory.ocdroid.data.model.SessionStatus>,
    localAfter: Map<String, cn.vectory.ocdroid.data.model.SessionStatus>,
    inFlightWinSids: Set<String>,
): Map<String, cn.vectory.ocdroid.data.model.SessionStatus> {
    if (inFlightWinSids.isEmpty()) return restSnapshot
    val result = LinkedHashMap(restSnapshot)
    for (id in inFlightWinSids) result[id] = localAfter.getValue(id)
    return result
}

/** §Plan-A (P0-C): null-aware lex max. Returns the greater of two [ServerRound]s,
 *  or the non-null one if only one is set, or null if both are null. */
private fun lexMaxNull(a: ServerRound?, b: ServerRound?): ServerRound? = when {
    a == null -> b
    b == null -> a
    a >= b -> a
    else -> b
}

/** §Plan-A (P0-C): strip round fields from a [SessionStatus] before storing
 *  in [SessionEntry.status] (projection hygiene — turns live only in
 *  [SessionEntry.serverRound]/[SessionEntry.serverRoundHighWater]). */
private fun cn.vectory.ocdroid.data.model.SessionStatus.stripRound(): cn.vectory.ocdroid.data.model.SessionStatus {
    if (turnIncarnation == null && turn == null) return this
    return copy(turnIncarnation = null, turn = null)
}
