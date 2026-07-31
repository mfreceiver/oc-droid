package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.state.AuthorityOp
import cn.vectory.ocdroid.data.state.AuthorityState
import cn.vectory.ocdroid.data.state.Coverage
import cn.vectory.ocdroid.data.state.EntryOrigin
import cn.vectory.ocdroid.data.state.Freshness
import cn.vectory.ocdroid.data.state.OptimisticClaim
import cn.vectory.ocdroid.data.state.ReconcileOutcome
import cn.vectory.ocdroid.data.state.RetryEntry
import cn.vectory.ocdroid.data.state.SessionEntry
import cn.vectory.ocdroid.data.state.ServerRound
import cn.vectory.ocdroid.ui.controller.normalizeAuthoritativeStatusSnapshot

/** §P1-B/E: cap on [AuthorityState.retryQueue] size (LRU eviction when exceeded). */
private const val RETRY_QUEUE_MAX_SIZE = 256

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
 *    ([AuthorityOp.ApplyEvent.connectionMonotonicMs] /
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
        is AuthorityOp.ApplyReconcileOutcome -> applyReconcile(cur, op)
        is AuthorityOp.PruneSessions -> applyPrune(cur, op)
        is AuthorityOp.FreshnessTick -> applyFreshnessTick(cur, op)
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
            pendingErrorCheck = state.chat.pendingErrorCheck + transitionedToIdle,
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
 *  - [AuthorityOp.ApplyReconcileOutcome]: §P0-B scope guard (host) AND
 *    §P0-C identity-epoch guard. The reconcile-dispatch host must match the
 *    live host (P0-A scope guard); the dispatch-epoch must match current
 *    [StoreState.identityEpoch] (stale-identity guard, P0-C).
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
        is AuthorityOp.ApplyReconcileOutcome -> {
            val currentHost = state.host.currentHostProfileId
            // Host guard: null currentHost passes leniently (cold-start); otherwise
            // the reconcile-dispatch host must match the live host.
            if (currentHost != null && currentHost != op.hostProfileId) return false
            // §P0-C identity-epoch guard: a reconcile whose dispatch-epoch predates
            // an identity transition is stale → DROP.
            if (op.identityEpochAtCapture != state.identityEpoch) return false
            true
        }
        is AuthorityOp.PurgeHost,
        is AuthorityOp.PruneSessions,
        is AuthorityOp.FreshnessTick,
        is AuthorityOp.RetryQueued,
        is AuthorityOp.RetryFired -> true
    }
}

// ── ApplyEvent ────────────────────────────────────────────────────────────

/**
 * §3.1 fence selection by [AuthorityOp.ApplyEvent.origin]. Pure.
 *
 * P0-A (no full B6/B9 slim serverRound yet — slim digest carries serverRound=null):
 *  - OPTIMISTIC: stamp [OptimisticClaim] (clientSeq monotonic, serverEchoed=false),
 *    keep prior serverRound. Record [AuthorityOp.ApplyEvent.optimisticBumpTimestamp]
 *    into pendingBumps (consumed by [applyOptimisticBumps] in the outer reducer).
 *  - SSE_LEGACY / SSE_SLIM: if serverRound != null → lex strict-monotonic guard
 *    (strictly-less-than → DROP; equal → monotonic tie-break). If null → accept
 *    (no causal fence for P0-A). On incoming BUSY/RETRY with an existing claim →
 *    echo-confirm (serverEchoed=true). On incoming terminal IDLE → clear claim.
 *  - knownIncarnations per-scope high-water (B6): incarnation advance resets that
 *    scope's entries' serverRound; low incarnation → DROP. (Pure, self-contained
 *    even though slim does not yet supply serverRound.)
 *  - §3.1 BLK-2 per-sid serverRound high-water: when the live baseline (prev
 *    serverRound) was cleared by REST/legacy/incarnation-reset but a Tier-1 slim
 *    frame with a turn arrives, the lex guard is skipped — the PERSISTENT
 *    [SessionEntry.serverRoundHighWater] watermark still fences a stale low-turn
 *    frame (strictly-older → DROP). Closes the BLK-2 revival window.
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
        if (cmp == 0 && op.connectionMonotonicMs < prev.updatedMonotonic) {
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
        return cur // §3.1 BLK-2: stale low-turn after baseline clear → DROP
    }

    // ── §3.1 Tier-2 confirmation gate (legacy, no causal serverRound) ──
    // R1 根因修复：一个未确认的 optimistic claim（POST 成功后 server 还没 echo busy）
    // 绝不能被一个 stale legacy IDLE 覆盖。DROP 该 idle，标 guardedIdleDrop，arm watchdog。
    // serverEchoed==true 时，后续 IDLE 是合法 terminal（应用 + 清 claim，走原路径）。
    //
    // §P0-B final-fix #1: a claim is confirmed (gate RELEASED → legacy IDLE treated as
    // legitimate terminal) when confirmed by EITHER a real-time SSE echo (serverEchoed)
    // OR a delayed reconcile GET (reconcileConfirmed). Only a claim unconfirmed by BOTH
    // is protected from stale legacy IDLEs.
    if (op.serverRound == null && op.status.isIdle &&
        prev?.optimisticClaim != null &&
        !prev.optimisticClaim.serverEchoed && !prev.optimisticClaim.reconcileConfirmed
    ) {
        if (prev.optimisticClaim.guardedIdleDrop) {
            return cur  // 已标记过 → 真正 no-op（same ref）
        }
        val guarded = prev.copy(
            optimisticClaim = prev.optimisticClaim.copy(guardedIdleDrop = true),
        )
        return cur.copy(bySid = cur.bySid + (op.sid to guarded))
    }

    // ── next entry fields ──
    // §U-P3: a frame with no serverRound (legacy SSE busy / optimistic) MUST NOT
    // clear the prior slim baseline — the legacy frame is causally weaker (no
    // incarnation/turn) and clearing the baseline would reopen the BLK-2 revival
    // window. Only a fresh slim frame (serverRound != null) or REST whole-graph
    // replace (applySnapshot, separate path) legitimately advances/clears.
    val keepRound: ServerRound? = op.serverRound ?: prev?.serverRound
    val nextOptimisticClaim: OptimisticClaim? = when {
        op.origin == EntryOrigin.OPTIMISTIC -> {
            val priorSeq = prev?.optimisticClaim?.clientSeq ?: 0L
            // §P0-B ITEM 2: cross-channel reorder — when prev is already SSE
            // busy/retry, the server has confirmed the busy state → immediate echo.
            val echoedNow = prev != null &&
                (prev.status.isBusy || prev.status.isRetry) &&
                (prev.origin == EntryOrigin.SSE_LEGACY || prev.origin == EntryOrigin.SSE_SLIM)
            OptimisticClaim(
                clientSeq = priorSeq + 1L,
                claimedAtMonotonic = op.connectionMonotonicMs,
                serverEchoed = echoedNow || (prev?.optimisticClaim?.serverEchoed ?: false),
                // §P0-B final-fix #1: a NEW optimistic generation starts NOT
                // reconcile-confirmed — never inherit reconcileConfirmed (prevents
                // cross-generation pollution).
                reconcileConfirmed = false,
                guardedIdleDrop = false,
            )
        }
        // incoming terminal IDLE clears the claim (legitimate terminal).
        op.status.isIdle -> null
        // incoming BUSY/RETRY with an existing claim → echo-confirm
        // (cross-channel reorder: server busy lands before HTTP success).
        (op.status.isBusy || op.status.isRetry) && prev?.optimisticClaim != null -> {
            prev.optimisticClaim.copy(serverEchoed = true)
        }
        else -> prev?.optimisticClaim
    }

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
        optimisticClaim = nextOptimisticClaim,
        origin = op.origin,
        freshness = Freshness.Fresh,
        updatedMonotonic = op.connectionMonotonicMs,
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
    // the prior entry (data-class equality: same status, serverRound, claim,
    // origin, freshness, updatedMonotonic, workdir) AND pendingBumps is
    // unchanged (addPendingBump returned the same reference) → NO transition →
    // return cur (same ref). This makes an equal-value SSE re-delivery a true
    // CAS no-op (no emission), completing the B1 idempotency contract. (An
    // OPTIMISTIC op always changes the claim via clientSeq++ → never no-change.)
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
 * Faithfully reproduces the legacy REST/hydration merge:
 *  1. `normalizeAuthoritativeStatusSnapshot(snapshot, authoritativeNodeIds)`:
 *     idle-fill proven tree nodes missing from the REST snapshot (absence of a
 *     KNOWN node ≡ idle). IDs outside the tree stay absent (fail-closed).
 *  2. REST in-flight protection (migrated `mergeStatusSnapshot`): for each id
 *     in the CURRENT projection where `localBefore[id] != current[id]`, the
 *     SSE-wins value overrides the stale REST snapshot value.
 *  3. slim failed-directory preservation: entries whose workdir is in
 *     `partialFailureWorkdirs` keep their PRIOR entry (claim/round/workdir),
 *     status updated to the merged value.
 *  4. ids absent from the merged result are dropped from bySid (whole-graph
 *     replace — §3.1 line 320 clears covered sids' serverRound).
 *  5. coverage[scopeKey] updated.
 */
private fun applySnapshot(cur: AuthorityState, op: AuthorityOp.ApplySnapshot): AuthorityState {
    val normalized = normalizeAuthoritativeStatusSnapshot(op.snapshot, op.authoritativeNodeIds)
    // §scope-guard: only in-scope entries participate in the in-flight merge.
    // Out-of-scope entries that changed during the request must NOT be pulled
    // into merged — the overlay would re-stamp them with op.scopeKey, migrating
    // them to the current scope (cross-scope corruption).
    val currentProjection = projectSessionStatuses(cur).filterKeys { sid ->
        val entry = cur.bySid[sid]
        entry == null || entry.scopeKey == null || entry.scopeKey == op.scopeKey
    }

    // slim failed-dir preservation: prior status for failed-dir sids overrides
    // the (idle-filled) normalized value, exactly mirroring the legacy
    // `restSnapshot = normalized + preservedFromFailure`.
    val failedSids: Set<String> = op.sidToWorkdir.entries
        .asSequence()
        .filter { it.value in op.partialFailureWorkdirs }
        .map { it.key }
        .toSet()
    val preserved: Map<String, cn.vectory.ocdroid.data.model.SessionStatus> =
        currentProjection.filterKeys { it in failedSids }
    val restSnapshot: Map<String, cn.vectory.ocdroid.data.model.SessionStatus> =
        LinkedHashMap(normalized).apply { putAll(preserved) }

    // REST in-flight (SSE-wins) protection — migrated mergeStatusSnapshot
    // (StatusPollOrchestrator.kt:432). Pure, inlined here so the reducer stays
    // self-contained (no dependency on the poller's class member). For each id
    // in the CURRENT projection whose value changed since [op.localBefore], the
    // SSE-wins value overrides the stale REST snapshot value.
    val merged = mergeStatusSnapshotInFlight(op.localBefore, currentProjection, restSnapshot)

    val nextById = LinkedHashMap<String, SessionEntry>()
    // §P0-A FIX: preserve out-of-scope entries (scopeKey != op.scopeKey).
    // The snapshot is authoritative only for its own scope. Entries with null
    // scopeKey (pre-migration) are treated as in-scope (dropped if not in snapshot).
    for ((sid, entry) in cur.bySid) {
        if (entry.scopeKey != null && entry.scopeKey != op.scopeKey) {
            nextById[sid] = entry
        }
    }
    // Then overlay the merged snapshot (current-scope entries). The current
    // scope is authoritative for its SIDs — on a cross-scope SID collision the
    // current-scope entry WINS (bySid is keyed by SID; one entry per SID).
    // Out-of-scope entries with no collision were already preserved above.
    for ((id, status) in merged) {
        val priorEntry = cur.bySid[id]
        nextById[id] = if (id in failedSids && priorEntry != null) {
            // Failed-dir: keep prior entry (claim/round/workdir), update status only.
            // Stamp the real scopeKey from the op for applyMarkFailed filtering.
            priorEntry.copy(status = status, scopeKey = op.scopeKey)
        } else {
            // REST authoritative for this scope: clear claim + serverRound (§3.1:320).
            // §3.1 BLK-2: PRESERVE the per-sid serverRound high-water across the REST
            // baseline clear so a stale low-turn Tier-1 slim digest arriving after
            // this snapshot is still fenced (the live baseline is gone, but the
            // persistent watermark remembers the max turn seen for this incarnation).
            //
            // §U-P1: preserve an SSE-active UNCONFIRMED optimistic claim across the
            // REST snapshot. A claim is "active" when it exists AND is unconfirmed
            // by BOTH signals (!serverEchoed && !reconcileConfirmed) — the watchdog
            // will reconcile it. A confirmed claim (serverEchoed || reconcileConfirmed)
            // means the server acknowledged busy → REST snapshot legitimately resolves
            // it (clear). Mirrors the :309-319 guardedIdleDrop protection at the
            // applySnapshot level.
            val preservedClaim = priorEntry?.optimisticClaim?.let { claim ->
                if (!claim.serverEchoed && !claim.reconcileConfirmed) claim else null
            }
            SessionEntry(
                status = status,
                serverRound = null,
                optimisticClaim = preservedClaim,  // §U-P1: was `null`
                origin = EntryOrigin.REST,
                freshness = Freshness.Fresh,
                updatedMonotonic = op.requestToken.requestStartMs,
                workdir = op.sidToWorkdir[id],
                scopeKey = op.scopeKey,
                serverRoundHighWater = priorEntry?.serverRoundHighWater,
            )
        }
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
    // This makes a re-fetch that produces the identical snapshot a true CAS
    // no-op (no emission), completing the B1 idempotency contract.
    //
    // §P1-B/E rev-ogpt B4: a snapshot can transition a sid busy/retry → idle
    // (terminal). Those sids must leave the retry queue — the REST snapshot
    // authoritatively confirmed a non-running status. Clean any queued sid
    // that is now terminal in nextById (absent ≡ gone, or present + idle).
    val nextRetryQueue = if (cur.retryQueue.isEmpty()) {
        cur.retryQueue
    } else {
        val terminalQueued = cur.retryQueue.keys.filter { sid ->
            val e = nextById[sid]
            // Only clean sids that belong to THIS snapshot's scope (nextById
            // already contains only in-scope + retained out-of-scope entries;
            // a retained out-of-scope entry is NOT this snapshot's verdict).
            // An absent in-scope sid (dropped from snapshot) is gone → terminal.
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
    if (nextById == cur.bySid && coverageUnchanged && nextRetryQueue === cur.retryQueue) return cur

    return cur.copy(bySid = nextById, coverage = nextCoverage, retryQueue = nextRetryQueue)
}

// ── PurgeHost ──────────────────────────────────────────────────────────────

/** §4c.3: cross-group clears the scope; same-group keeps. Pure.
 *  (P0-A host-purge path resets authority directly in the reducer copy; this
 *  op is implemented for typed completeness.)
 *
 *  §sm-hardening note (rev-glm ses_04ccdaa78 nit#1 — non-blocking): cross-group
 *  purge resets `bySid = emptyMap()` unconditionally, which is correct under the
 *  P0-A single-active-scope invariant (the data model's `Map<ScopeKey,...>`
 *  notwithstanding). A per-entry `scopeKey` filter (matching [applyPrune]) would
 *  be the strictly safer future-proofing, but that is a behavior change deferred
 *  to a dedicated multi-scope epic — NOT done here, per "仅加固，不改核心状态机逻辑".
 *
 *  §P1-B/E rev-ogpt B2: `retryQueue` IS cleared on cross-group purge. The
 *  queue has no per-entry scope (keyed by sid), so a host switch leaves host
 *  A's queued sids pointing at stale data. Clearing unconditionally on
 *  cross-group purge matches the `bySid` reset and closes the cross-host
 *  leak (host A's queued sid fire-then-requeue under host B with inherited
 *  attempt counter). The same-group path (`preserveServerGroup=true`) returns
 *  `cur` unchanged (queue preserved — same host). */
private fun applyPurge(cur: AuthorityState, op: AuthorityOp.PurgeHost): AuthorityState {
    if (op.preserveServerGroup) return cur
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
 * `Retry` whose `updatedMonotonic > monotonic` is NOT clobbered by a stale
 * failure, matching the legacy `markRequestFailed` merge-timing rule). Entries
 * with `updatedMonotonic <= monotonic` are removed (absence ≡ unknown). This
 * preserves the FGS-lifecycle guarantee that a failure never wrongly clears a
 * fresher busy observation.
 *
 * Coverage: [Coverage.registeredWorkdirs] is preserved from [op] (the failure
 * caller carries the snapshot's registered set so the coverage predicate keeps
 * gating `AllIdleFresh`); `coveredWorkdirs` is emptied and
 * `lastSuccessTimeMs = -1` so the derived aggregator `project()` returns
 * [GlobalBusyState.Unknown] (cold-start / stale-success guard) — matching the
 * old markFailed → Unknown semantics via the coverage gate rather than Unknown
 * status entries.
 *
 * Purity: this branch is pure (no injected deps, no clock read — [op.monotonic]
 * is carried data). Same `(state, op)` always yields the same output (CAS retry
 * idempotent).
 */
private fun applyMarkFailed(cur: AuthorityState, op: AuthorityOp.MarkSourceFailed): AuthorityState {
    // §P0-A r2 #4: scope-filtering by [scopeKey] (not workdir approximation).
    // Keep an entry if EITHER (a) it is FRESHER than the failure
    // (updatedMonotonic > monotonic — a prior SSE Busy/Retry survives), OR
    // (b) it is OUT OF SCOPE (entry.scopeKey != null AND entry.scopeKey !=
    // op.scopeKey — a different scope's entry must NOT be swept by this
    // scope's failure, even if it happens to share a workdir). Remove only
    // in-scope + at-or-older-than-failure entries (absence ≡ unknown,
    // fail-closed). Entries with null scopeKey (pre-field migration) are
    // conservatively treated as in-scope.
    val survivors = cur.bySid.filterValues { entry ->
        entry.updatedMonotonic > op.monotonic ||
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

// ── ApplyReconcileOutcome ──────────────────────────────────────────────────

/** §B7 REST reconcile terminal outcome. Pure.
 *  §P0-A rev-gpt #1: returns cur (same ref) when the outcome produces no change.
 *  §P0-B (FIX #2): generation fence at top drops stale-generation outcomes (ABA).
 *  §P1-B/E rev-ogpt B4: IDLE_CONFIRMED / FETCH_FAILED are terminal outcomes —
 *  they clean the sid's retryQueue entry (matching applyEvent terminal cleanup). */
private fun applyReconcile(cur: AuthorityState, op: AuthorityOp.ApplyReconcileOutcome): AuthorityState {
    val prev = cur.bySid[op.sid]
    // §P0-B generation fence (ABA): the reconcile was triggered for a specific stale
    // claim generation. If the current claim is gone (cleared by a terminal SSE) or
    // advanced (a newer optimistic POST superseded it), DROP — never let a stale
    // outcome revive/overwrite a newer claim.
    val claim = prev?.optimisticClaim
    if (claim == null || claim.clientSeq != op.claimClientSeq) return cur
    // After the fence, prev is guaranteed non-null with a matching claim.
    return when (op.outcome) {
        ReconcileOutcome.IDLE_CONFIRMED -> {
            val entry = prev.copy(
                status = cn.vectory.ocdroid.data.model.SessionStatus(type = "idle"),
                optimisticClaim = null,
                updatedMonotonic = op.monotonic,
            )
            // §P0-A rev-gpt #1: no-change if the resulting entry equals prev.
            if (entry == prev && op.sid !in cur.retryQueue) {
                cur
            } else {
                cur.copy(
                    bySid = cur.bySid + (op.sid to entry),
                    // rev-ogpt B4: idle is terminal → clean retry entry.
                    retryQueue = cur.retryQueue - op.sid,
                )
            }
        }
        ReconcileOutcome.BUSY_CONFIRMED -> {
            // §P0-B final-fix #1: mark the claim confirmed by the DELAYED reconcile
            // GET. Use the dedicated reconcileConfirmed flag (NOT serverEchoed) so
            // the OPTIMISTIC branch's serverEchoed-inheritance does NOT leak this
            // across generations — a new optimistic POST starts with
            // reconcileConfirmed=false, keeping the watchdog armed for the new claim.
            val entry = prev.copy(
                status = cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"),
                serverRound = op.serverRound ?: prev.serverRound,
                optimisticClaim = prev.optimisticClaim.copy(reconcileConfirmed = true),
                updatedMonotonic = op.monotonic,
            )
            if (entry == prev) cur else cur.copy(bySid = cur.bySid + (op.sid to entry))
        }
        ReconcileOutcome.FETCH_FAILED -> {
            // rev-ogpt B4: entry removal is terminal → clean retry entry too.
            // §CQ-P1 (U-CQ1): write fail-closed coverage (matching applyMarkFailed
            // :650-682) so the AllIdleFresh gate reads this scope as unknown, not a
            // stale success. ApplyReconcileOutcome carries scopeKey but not
            // registeredWorkdirs, so preserve them from the prior coverage entry.
            val priorCov = cur.coverage[op.scopeKey]
            val registered = priorCov?.registeredWorkdirs ?: emptySet()
            val nextCoverage = cur.coverage + (op.scopeKey to Coverage(
                registeredWorkdirs = registered,
                coveredWorkdirs = emptySet(),
                unmappedActiveIds = emptySet(),
                lastSuccessTimeMs = -1L,
            ))
            val bySidChanged = prev != null || op.sid in cur.retryQueue
            val covChanged = priorCov == null ||
                priorCov.coveredWorkdirs.isNotEmpty() ||
                priorCov.unmappedActiveIds.isNotEmpty() ||
                priorCov.lastSuccessTimeMs != -1L
            if (!bySidChanged && !covChanged) {
                cur
            } else {
                cur.copy(
                    bySid = if (prev != null) cur.bySid - op.sid else cur.bySid,
                    retryQueue = cur.retryQueue - op.sid,
                    coverage = nextCoverage,
                )
            }
        }
    }
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
        sid in op.sids && (cur.bySid[sid]?.scopeKey?.let { it == null || it == op.scopeKey } ?: true)
    }
    val nextRetryQueue = if (sidsToClean.isEmpty()) cur.retryQueue else cur.retryQueue - sidsToClean.toSet()
    return if (nextById.size == cur.bySid.size && nextRetryQueue === cur.retryQueue) {
        cur
    } else {
        cur.copy(bySid = nextById, retryQueue = nextRetryQueue)
    }
}

// ── FreshnessTick (P1-C) ────────────────────────────────────────────────────

/**
 * §P1-C: passive-TTL aging. Ages each in-scope [SessionEntry]'s `freshness`
 * Fresh→Stale (>TTL) / Stale→Unknown (>2*TTL) as pure bookkeeping. Does NOT
 * touch `project()`'s TTL verdict (that uses sourceTimeMs, not freshness).
 *
 * Bumps authorityRevision only if at least one entry's freshness actually
 * changes — otherwise returns [cur] (same ref, no transition). The aggregator's
 * init-collect re-derives projections on the revision bump.
 *
 * Out-of-scope entries (non-null scopeKey != op.scopeKey) are skipped (matches
 * applyEvent/applySnapshot scope filtering).
 */
private fun applyFreshnessTick(cur: AuthorityState, op: AuthorityOp.FreshnessTick): AuthorityState {
    val ttlMs = 30_000L // §P1-C STATUS_TTL_MS (StatusAggregatorImpl.STATUS_TTL_MS)
    val unknownMs = 60_000L // §P1-C 2 * STATUS_TTL_MS
    var changed = false
    val nextById = cur.bySid.mapValues { (_, e) ->
        // Out-of-scope entries are never aged by this tick.
        if (e.scopeKey != null && e.scopeKey != op.scopeKey) {
            return@mapValues e
        }
        val age = op.nowMonotonic - e.updatedMonotonic
        val target = when {
            age > unknownMs -> Freshness.Unknown
            age > ttlMs -> Freshness.Stale
            else -> Freshness.Fresh
        }
        if (e.freshness == target) {
            e
        } else {
            changed = true
            e.copy(freshness = target)
        }
    }
    return if (changed) cur.copy(bySid = nextById) else cur
}

// ── RetryQueued (P1-B/E) ──────────────────────────────────────────────────

/**
 * §P1-B/E: enqueue a retry entry. BOUNDED: the queue is STRICTLY capped at
 * RETRY_QUEUE_MAX_SIZE — when full, entries with the smallest queuedMonotonic
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
    val entry = RetryEntry(attempt = op.attempt, backoffMs = op.backoffMs, queuedMonotonic = op.queuedMonotonic)
    val existing = cur.retryQueue[op.sid]
    return if (existing == entry) {
        cur  // same ref, no transition
    } else {
        val withInserted = cur.retryQueue + (op.sid to entry)
        // rev-ogpt B1 / rev-gpt: STRICT cap — evict smallest-queuedMonotonic
        // entries until at or under RETRY_QUEUE_MAX_SIZE.
        var bounded = withInserted
        while (bounded.size > RETRY_QUEUE_MAX_SIZE) {
            val oldestKey = bounded.minByOrNull { it.value.queuedMonotonic }?.key ?: break
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
 * §sse-rest-race pure in-flight protection — migrated verbatim from the
 * `StatusPollOrchestrator.mergeStatusSnapshot` member (kdoc there): for each
 * id in [localAfter], if `localBefore[id] != localAfter[id]`, the SSE-wins
 * value overrides the REST snapshot (`localAfter[id]` replaces `rest[id]`).
 * Inlined (not imported) so the reducer stays dependency-free and the purity
 * is local/auditable; the original member is preserved for its existing tests.
 */
private fun mergeStatusSnapshotInFlight(
    localBefore: Map<String, cn.vectory.ocdroid.data.model.SessionStatus>,
    localAfter: Map<String, cn.vectory.ocdroid.data.model.SessionStatus>,
    restSnapshot: Map<String, cn.vectory.ocdroid.data.model.SessionStatus>,
): Map<String, cn.vectory.ocdroid.data.model.SessionStatus> {
    if (localAfter.isEmpty()) return restSnapshot
    val result = LinkedHashMap(restSnapshot)
    for ((id, after) in localAfter) {
        if (localBefore[id] != after) result[id] = after
    }
    return result
}
