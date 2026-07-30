package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.state.AuthorityOp
import cn.vectory.ocdroid.data.state.AuthorityState
import cn.vectory.ocdroid.data.state.Coverage
import cn.vectory.ocdroid.data.state.EntryOrigin
import cn.vectory.ocdroid.data.state.Freshness
import cn.vectory.ocdroid.data.state.OptimisticClaim
import cn.vectory.ocdroid.data.state.ReconcileOutcome
import cn.vectory.ocdroid.data.state.SessionEntry
import cn.vectory.ocdroid.data.state.ServerRound
import cn.vectory.ocdroid.ui.controller.normalizeAuthoritativeStatusSnapshot

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
    }
    if (nextAuth === cur) return state
    val projection = projectSessionStatuses(nextAuth)
    val newSessions = applyOptimisticBumps(state.sessionList.sessions, nextAuth.pendingBumps)
    val cleanedAuth = nextAuth.copy(pendingBumps = emptyMap())
    return state.copy(
        authority = cleanedAuth,
        sessionList = state.sessionList.copy(
            sessionStatuses = projection,
            sessions = newSessions,
        ),
    )
}

/**
 * Pure scope/identity guard. Derives ONLY from [state] (no injected identity
 * store, no TOCTOU — §B11).
 *
 *  - [AuthorityOp.ApplySnapshot] / [AuthorityOp.MarkSourceFailed]: host guard.
 *    The caller's own single-flight guard (statusLoadEpoch AtomicLong /
 *    completenessEpoch) remains authoritative for stale-request dropping;
 *    this is defense-in-depth inside the CAS. null current host passes
 *    leniently (matches the legacy `!=` null==null semantics).
 *  - [AuthorityOp.ApplyEvent] / [AuthorityOp.PurgeHost] /
 *    [AuthorityOp.ApplyReconcileOutcome] / [AuthorityOp.PruneSessions]:
 *    structurally valid for P0-A (true). The event-captured-identity scope
 *    guard is P0-C's job (§2.2 line 254-256). Lenient-pass does NOT regress
 *    current behavior — no B11 guard exists today.
 */
private fun opScopeValid(op: AuthorityOp, state: StoreState): Boolean = when (op) {
    is AuthorityOp.ApplySnapshot,
    is AuthorityOp.MarkSourceFailed -> {
        val token = when (op) {
            is AuthorityOp.ApplySnapshot -> op.requestToken
            is AuthorityOp.MarkSourceFailed -> op.requestToken
            else -> return true
        }
        val currentHost = state.host.currentHostProfileId
        currentHost == null || currentHost == token.hostProfileId
    }
    // §B11 TODO(P0-C): scope guard via event-captured identity (op.capturedIdentity /
    // op.scopeKey vs a current scope derivable from state). P0-A lenient-pass preserves
    // current behavior (no B11 guard exists today).
    is AuthorityOp.ApplyEvent,
    is AuthorityOp.PurgeHost,
    is AuthorityOp.ApplyReconcileOutcome,
    is AuthorityOp.PruneSessions -> true
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
            // §B9 equal-serverRound tie-break: strictly-older monotonic → DROP
            return cur
        }
    }

    // ── next entry fields ──
    val keepRound: ServerRound? = if (op.origin == EntryOrigin.OPTIMISTIC) {
        prev?.serverRound
    } else {
        op.serverRound
    }
    val nextOptimisticClaim: OptimisticClaim? = when {
        op.origin == EntryOrigin.OPTIMISTIC -> {
            val priorSeq = prev?.optimisticClaim?.clientSeq ?: 0L
            OptimisticClaim(
                clientSeq = priorSeq + 1L,
                claimedAtMonotonic = op.connectionMonotonicMs,
                serverEchoed = prev?.optimisticClaim?.serverEchoed ?: false,
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

    val nextEntry = SessionEntry(
        status = op.status,
        serverRound = keepRound,
        optimisticClaim = nextOptimisticClaim,
        origin = op.origin,
        freshness = Freshness.Fresh,
        updatedMonotonic = op.connectionMonotonicMs,
        workdir = op.workdir ?: prev?.workdir,
    )

    val nextPending = addPendingBump(cur.pendingBumps, op)

    // ── incarnation high-water advance (B6): bump + reset that scope's rounds ──
    if (op.serverRound != null && op.serverRound.incarnation > highWater) {
        // Slim server restart (incarnation advance) invalidates prior turns:
        // reset every entry's serverRound (single active scope in P0-A) and
        // bump the scope's high-water before placing the new entry.
        val resetById = cur.bySid.mapValues { (_, e) ->
            if (e.serverRound != null) e.copy(serverRound = null) else e
        }
        return cur.copy(
            bySid = resetById + (op.sid to nextEntry),
            knownIncarnations = cur.knownIncarnations + (op.scopeKey to op.serverRound.incarnation),
            pendingBumps = nextPending,
        )
    }

    return cur.copy(
        bySid = cur.bySid + (op.sid to nextEntry),
        pendingBumps = nextPending,
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
    val currentProjection = projectSessionStatuses(cur)

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
    for ((id, status) in merged) {
        val priorEntry = cur.bySid[id]
        nextById[id] = if (id in failedSids && priorEntry != null) {
            // Failed-dir: keep prior entry (claim/round/workdir), update status only.
            priorEntry.copy(status = status)
        } else {
            // REST authoritative for this scope: clear claim + serverRound (§3.1:320).
            SessionEntry(
                status = status,
                serverRound = null,
                optimisticClaim = null,
                origin = EntryOrigin.REST,
                freshness = Freshness.Fresh,
                updatedMonotonic = op.requestToken.requestStartMs,
                workdir = op.sidToWorkdir[id],
            )
        }
    }

    val nextCoverage = cur.coverage + (op.scopeKey to Coverage(
        registeredWorkdirs = op.registeredWorkdirs,
        coveredWorkdirs = op.coveredWorkdirs,
        unmappedActiveIds = op.unmappedActiveIds,
        lastSuccessTimeMs = op.lastSuccessTimeMs,
    ))

    return cur.copy(bySid = nextById, coverage = nextCoverage)
}

// ── PurgeHost ──────────────────────────────────────────────────────────────

/** §4c.3: cross-group clears the scope; same-group keeps. Pure.
 *  (P0-A host-purge path resets authority directly in the reducer copy; this
 *  op is implemented for typed completeness. Single active scope ⇒ full
 *  bySid reset on cross-group; per-entry scope filtering is P0-C.) */
private fun applyPurge(cur: AuthorityState, op: AuthorityOp.PurgeHost): AuthorityState {
    if (op.preserveServerGroup) return cur
    if (cur.bySid.isEmpty() && op.scopeKey !in cur.knownIncarnations && op.scopeKey !in cur.coverage) {
        return cur
    }
    return cur.copy(
        bySid = emptyMap(),
        knownIncarnations = cur.knownIncarnations - op.scopeKey,
        coverage = cur.coverage - op.scopeKey,
    )
}

// ── MarkSourceFailed ───────────────────────────────────────────────────────

/**
 * REST source failure: covered entries become fail-closed UNKNOWN. The UI
 * projection only carries SessionStatus (idle/busy/retry) — there is no
 * "Unknown" status, so markFailed maps to ABSENCE (remove from bySid) so the
 * single absence-reader (`sid !in sessionStatuses`) reads unknown.
 *
 * NOTE: the aggregator's separate "Unknown does not clear globalBusy" lifecycle
 * classification is fixer #2's concern; P0-A keeps the UI-projection semantics
 * consistent with absence ≡ unknown. (This op is currently unused by P0-A call
 * sites — the markFailed paths stay on the aggregator until #2.)
 */
private fun applyMarkFailed(cur: AuthorityState, op: AuthorityOp.MarkSourceFailed): AuthorityState {
    // P0-A single active scope: clearing the scope's covered entries. (This op
    // is currently unused by P0-A call sites — the markFailed paths stay on the
    // aggregator until #2; implemented for typed completeness.)
    if (cur.bySid.isEmpty()) return cur
    return cur.copy(bySid = emptyMap())
}

// ── ApplyReconcileOutcome ──────────────────────────────────────────────────

/** §B7 REST reconcile terminal outcome. Pure. (Currently unused in P0-A.) */
private fun applyReconcile(cur: AuthorityState, op: AuthorityOp.ApplyReconcileOutcome): AuthorityState {
    val prev = cur.bySid[op.sid]
    return when (op.outcome) {
        ReconcileOutcome.IDLE_CONFIRMED -> {
            val entry = (prev ?: SessionEntry(
                status = cn.vectory.ocdroid.data.model.SessionStatus(type = "idle"),
                serverRound = null,
                optimisticClaim = null,
                origin = EntryOrigin.REST,
                freshness = Freshness.Fresh,
                updatedMonotonic = op.monotonic,
                workdir = null,
            )).copy(
                status = cn.vectory.ocdroid.data.model.SessionStatus(type = "idle"),
                optimisticClaim = null,
                updatedMonotonic = op.monotonic,
            )
            cur.copy(bySid = cur.bySid + (op.sid to entry))
        }
        ReconcileOutcome.BUSY_CONFIRMED -> {
            val entry = (prev ?: SessionEntry(
                status = cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"),
                serverRound = op.serverRound,
                optimisticClaim = null,
                origin = EntryOrigin.REST,
                freshness = Freshness.Fresh,
                updatedMonotonic = op.monotonic,
                workdir = null,
            )).copy(
                status = cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"),
                serverRound = op.serverRound ?: prev?.serverRound,
                updatedMonotonic = op.monotonic,
            )
            cur.copy(bySid = cur.bySid + (op.sid to entry))
        }
        ReconcileOutcome.FETCH_FAILED -> {
            if (prev == null) cur else cur.copy(bySid = cur.bySid - op.sid)
        }
    }
}

// ── PruneSessions ──────────────────────────────────────────────────────────

/** §B5: drop [AuthorityOp.PruneSessions.sids] from bySid. Pure. */
private fun applyPrune(cur: AuthorityState, op: AuthorityOp.PruneSessions): AuthorityState {
    if (op.sids.isEmpty()) return cur
    val nextById = cur.bySid.filterKeys { it !in op.sids }
    return if (nextById.size == cur.bySid.size) cur else cur.copy(bySid = nextById)
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
