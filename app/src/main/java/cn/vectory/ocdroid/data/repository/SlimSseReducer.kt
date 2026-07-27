package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.LastErrorField
import cn.vectory.ocdroid.data.model.SlimSessionDigest
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import cn.vectory.ocdroid.util.DebugLog

/**
 * Cluster A (slim SSE reducer): per-session view of the latest known state
 * derived from `session.digest` frames. Stored under
 * [SlimSseStateMachine] (T3-extracted from OpenCodeRepository) so the reducer is pure (state in →
 * state out + decision) and unit-testable in isolation.
 *
 * # Task 6 (slimapi v1 §G5) — split watermark
 *
 * The session's "what the server told us" ([remoteMessageId] /
 * [remoteUpdatedAt]) is split from "what we've successfully fetched and
 * applied via REST" ([localAppliedMessageId] / [localAppliedUpdatedAt]).
 * The split is what lets the client distinguish "digest observed but not
 * yet fetched" from "fetched and merged" — the previous single-watermark
 * model conflated the two, which made reconcile decisions ambiguous.
 *
 *  - [remoteUpdatedAt] / [remoteMessageId]: advanced MONOTONICALLY by the
 *    digest reducer ([reduceSlimDigest]) — every digest observation
 *    ratchets these forward (or leaves them on a stale / re-emitted
 *    digest). The reducer NEVER rolls them back.
 *  - [localAppliedUpdatedAt] / [localAppliedMessageId]: advanced by
 *    [onReconcileSuccess] (pure function in [SlimapiResync]) AND by
 *    [SlimSseStateMachine.replaceLocalAppliedAndClearDirtyLocked] (the
 *    §11.2 committer's in-lock write). The digest reducer NEVER touches
 *    these directly. This is the core invariant — "applied" means a REST
 *    fetch succeeded and the result was committed via the authoritative
 *    commit protocol.
 *  - [dirty]: ratcheted to `true` by the digest reducer when
 *    [needsReconcile] holds after the merge (the local view is behind the
 *    remote view). Cleared to `false` by the committer
 *    ([SlimSseStateMachine.replaceLocalAppliedAndClearDirtyLocked]),
 *    then RE-EVALUATED ATOMICALLY inside the same critical section from
 *    `hasConflict` + [needsReconcile] (rev-ogpt P1-1/P1-2: if the
 *    candidate's own merge detected a same-tuple-different-parts
 *    conflict, OR if the committer's IN-LOCK per-ID conflict-aware
 *    merge detected a concurrent candidate's same-ID/same-tuple/
 *    different-parts divergence at the equal global watermark —
 *    fix-13; dirty is forced true unconditionally. Otherwise fix-9
 *    P0-4: if remote > localApplied post-commit, dirty is re-set to
 *    true). The conflict HOT PATH (engine + reconciler foldRestFetch)
 *    rides this atomic decision. The cache-retention failure
 *    production path in [SlimSessionReconciler] (post-REST retention
 *    guard rejects the merged set — OUTSIDE the commit protocol)
 *    forces dirty=true via [SlimSseStateMachine.forceSlimDirty];
 *    diagnostic / test paths also use that entry point.
 *  - [lastError]: three-state merge of the digest's `lastError` field
 *    (T6-C4). [LastErrorField.Omitted] preserves the prior value (debounce
 *    tick that doesn't restate the field); [LastErrorField.Cleared] sets
 *    it to null (sidecar signals upstream recovery); [LastErrorField.Set]
 *    takes the new value (sidecar surfaces an upstream error). The
 *    reducer merges via [mergeLastError] — see T1's [LastErrorField] for
 *    the absent-vs-present-null semantics that make this faithful.
 *  - [archived]: monotonic-max like [remoteUpdatedAt] — it's a permanent
 *    timestamp from `info.time.archived` (deployed contract §3); a stale
 *    digest MUST NOT regress it (§3 debounce + archive permanence).
 *
 * Field semantics mirror [SlimSessionDigest] absent-field handling: a null
 * field means "no information from the last digest" (the sidecar only emits
 * changed fields, §3). The reducer merges present fields onto the prior
 * state; absent fields preserve the prior value.
 *
 * ## Backward-compat accessors (T6 transitional, removed by T11 wiring)
 *
 * The pre-T6 single-watermark callers in `OpenCodeRepository` (slim
 * `/since/` anchor reads at `:911` + `:2062`, post-REST bump at
 * `bumpSlimBookmarkFromItems`) read/wrote a single `updatedAt` /
 * `messageId`. T6 rewrites the storage but T11 owns the call-site rewire
 * (routing post-REST through [onReconcileSuccess] instead of
 * [SlimSseState.bumpUpdatedAt]). Until T11 lands, the accessors below
 * preserve the OLD single-watermark view (`localApplied ?: remote` —
 * prefer the field that actually reflects a successful REST fetch, fall
 * back to the digest-observed value when none has been applied yet) so
 * the slim path keeps compiling + behaving byte-for-byte.
 */
data class SlimSessionState(
    val sessionId: String,
    val directory: String? = null,
    val status: String? = null,
    /**
     * Latest `messageID` the sidecar has told us about for this session
     * (digest-driven, monotonic — absent in digest preserves prior).
     * Advanced by [reduceSlimDigest]; NEVER advanced by REST paths.
     */
    val remoteMessageId: String? = null,
    /**
     * Largest `updatedAt` the sidecar has signalled for this session
     * (digest-driven, monotonic-max). Anchors reconciliation gap
     * detection ([needsReconcile] compares this against
     * [localAppliedUpdatedAt]). NOT a `/since/{ts}` anchor by itself —
     * the anchor is [localAppliedUpdatedAt] (what we've actually applied).
     */
    val remoteUpdatedAt: Long? = null,
    /**
     * Latest `messageID` we've successfully fetched + merged via REST
     * (`/slimapi/messages/{sid}/since/…` or `?mode=skeleton` cold-start).
     * Advanced by [onReconcileSuccess] AND by the stage-A authoritative
     * committer ([SlimSseStateMachine.replaceLocalAppliedAndClearDirtyLocked],
     * driven by [InternalSlimAuthoritativeCommitter] inside the host's
     * token-guarded critical section). NEVER advanced by the digest reducer.
     */
    val localAppliedMessageId: String? = null,
    /**
     * Largest `info.time.updated` we've successfully fetched + merged via
     * REST. Advanced by [onReconcileSuccess] AND by the stage-A authoritative
     * committer ([SlimSseStateMachine.replaceLocalAppliedAndClearDirtyLocked]).
     * This is the `/since/{ts}` anchor (per contract §3: server returns
     * `time.updated >= ts` so the boundary message is included for
     * messageID dedup).
     */
    val localAppliedUpdatedAt: Long? = null,
    /**
     * Three-state upstream-error banner. See [LastErrorField] for the
     * reducer-merge semantics (Omitted / Cleared / Set).
     */
    val lastError: SlimSessionLastError? = null,
    /**
     * Permanent archive timestamp from `info.time.archived` (§3).
     * Monotonic-max like [remoteUpdatedAt]; absent in digest preserves
     * prior.
     */
    val archived: Long? = null,
    /**
     * Sidecar has signalled this session is deleted (§3). The reducer
     * preserves `false` default; the wiring layer (T11) reacts by
     * removing the row from the session list.
     */
    val deleted: Boolean = false,
    /**
     * Reconcile-needed flag. Set `true` by:
     *  - the digest reducer (when [needsReconcile] holds after a merge —
     *    the local view is behind the remote view); the reducer NEVER
     *    clears `dirty`.
     *  - the stage-A authoritative committer
     *    ([SlimSseStateMachine.replaceLocalAppliedAndClearDirtyLocked]),
     *    ATOMICALLY with the localApplied* write — either because the
     *    candidate's `hasConflict = true` (the outer merge detected a
     *    same-tuple-different-parts divergence; rev-ogpt P1-1/P1-2), OR
     *    because the committer's IN-LOCK per-ID conflict-aware merge
     *    detected a concurrent candidate's same-ID/same-tuple/different-
     *    parts divergence at the equal global watermark (rev-ogpt P1-1
     *    fix-13), OR because `remote > localApplied` post-write (P0-4
     *    TOCTOU mitigation for a digest arriving mid-drain).
     *  - [SlimSseStateMachine.forceSlimDirty] (PRODUCTION: the cache-
     *    retention failure path in [SlimSessionReconciler] — when the
     *    post-REST retention guard rejects the merged set, the reconciler
     *    unconditionally forces dirty=true so a later reconcile re-fetches;
     *    this path is OUTSIDE the commit protocol and cannot ride the
     *    atomic hasConflict decision. Also retained for diagnostic / test
     *    use as an unconditional dirty ratchet).
     * Cleared `false` ONLY inside the committer's critical section when
     * `hasConflict = false` AND `needsReconcile` returns false post-write
     * (the normal happy-path commit clears dirty). Sticky across non-focus
     * digests + reconcile failures — clearing is deferred to a successful
     * REST reconcile.
     */
    val dirty: Boolean = false,
) {
    /**
     * §11.1 fix-9 P2 KDoc cleanup: legacy scalar accessor for the most
     * advanced known `updatedAt` across both watermarks. Prefer
     * [localAppliedUpdatedAt] (we actually fetched it); fall back to
     * [remoteUpdatedAt] (digest observed but not yet reconciled). Kept for
     * the few legacy call-sites that pre-date the T6 split; new code
     * MUST read the split fields directly (the scalar merge masks the
     * "remote ahead of local" state that drives dirty).
     */
    val updatedAt: Long?
        get() = localAppliedUpdatedAt ?: remoteUpdatedAt

    /**
     * §11.1 fix-9 P2 KDoc cleanup: legacy scalar accessor for the most
     * advanced known `messageID` across both watermarks (mirrors
     * [updatedAt]). Kept for legacy call-sites; new code MUST read the
     * split fields directly.
     */
    val messageId: String?
        get() = localAppliedMessageId ?: remoteMessageId
}

/**
 * Cluster A: per-host accumulator of [SlimSessionState]. Held by
 * [OpenCodeRepository] as private state; reads/writes are @Synchronized so
 * the reducer (called from the SSE collector thread) and the message-fetch
 * caller (called from the lifecycle coordinator's Main thread) cannot race.
 *
 * Cleared by [OpenCodeRepository.configure] on a host switch — the per-host
 * bookmarks belong to the previous server and MUST NOT leak to the new one
 * (a stale updatedAt would skip real boundary messages on the new server).
 */
class SlimSseState {
    private val sessions = mutableMapOf<String, SlimSessionState>()

    @Synchronized
    fun get(sessionId: String): SlimSessionState? = sessions[sessionId]

    @Synchronized
    fun put(sessionId: String, state: SlimSessionState) {
        sessions[sessionId] = state
    }

    @Synchronized
    fun all(): Map<String, SlimSessionState> = sessions.toMap()

    @Synchronized
    fun clear() = sessions.clear()

    /**
     * Advances the **remote** watermark ([SlimSessionState.remoteUpdatedAt])
     * for [sessionId] to the max of (prior, [updatedAt]).
     *
     * # Task 6 (split watermark) — transitional
     *
     * Pre-T6 this bumped the single `updatedAt`. Post-T6 (per locked
     * design) it advances [SlimSessionState.remoteUpdatedAt] (the
     * digest-path watermark) — `localApplied*` is now advanced by the
     * pure [onReconcileSuccess] in [SlimapiResync], which the T11 wiring
     * task routes the post-REST bumps through. The slim path's existing
     * post-REST caller (`OpenCodeRepository.bumpSlimBookmarkFromItems`)
     * keeps compiling against this method but is semantically off (it
     * advances remote instead of local-applied) until T11 rewires it —
     * the pre-T6 tests still pass because the [SlimSessionState.updatedAt]
     * accessor falls back to `remoteUpdatedAt` when `localAppliedUpdatedAt`
     * is null.
     *
     * Monotonic: a strictly-older [updatedAt] is a no-op (guards against
     * a fetch returning an OLDER tail than what a later digest already
     * advanced us to, or a stale debounce re-emit arriving after a
     * newer observation).
     *
     * # T1-C5 (slimapi v0.2.2) — vestigial
     *
     * grep confirms ZERO production callers (`OpenCodeRepository` no
     * longer routes post-REST bumps through this method — T11 rewired
     * everything to [reduceSlimDigest], which is the SOLE remote-write
     * path under T1). The only callers are the `SlimSseReducerTest`
     * invariant pins (which exist to lock the pre-T6 semantic during
     * the T6→T11 transition). Not symmetric on `remoteMessageId`
     * (writes only `remoteUpdatedAt`) — that asymmetry is a non-issue
     * under T1 tuple semantics (see [reduceSlimDigest]'s T1-C5
     * invariant note) and would be moot anyway once T11 removes the
     * transitional callers. Marked deprecated to flag future cleanup;
     * signature intentionally NOT changed (would be a pseudo-problem
     * fix — grill confirmed YAGNI).
     */
    @Deprecated(
        "T11 后 vestigial；remote watermark 经 reduceSlimDigest 推进。" +
            " T1 tuple 语义下不需要对称化（见 reduceSlimDigest 的 T1-C5 不变量注释）。",
    )
    @Synchronized
    fun bumpUpdatedAt(sessionId: String, updatedAt: Long) {
        val prev = sessions[sessionId]
        val priorMax = prev?.remoteUpdatedAt ?: Long.MIN_VALUE
        if (updatedAt > priorMax) {
            sessions[sessionId] = (prev ?: SlimSessionState(sessionId))
                .copy(remoteUpdatedAt = updatedAt)
        }
    }
}

/**
 * Cluster A: fetch decision emitted by [reduceSlimDigest] when a digest
 * indicates newer message activity than both the local-applied watermark
 * and the previously-observed remote watermark. The caller GETs
 * `/slimapi/messages/{sessionId}/since/{since}` and feeds the result back
 * into [onReconcileSuccess] (pure function in [SlimapiResync]) to advance
 * the local-applied watermark + clear `dirty`.
 *
 * [since] is the **local-applied** watermark (`priorLocalAppliedUpdatedAt`)
 * — NOT `max(remote, local)`. The REST `/since/{ts}` boundary MUST reflect
 * what we've actually fetched + merged; using remote here would skip the
 * `(localApplied, remote]` message range when a prior reconcile failed
 * (rev-gpt Critical fix). Per §5, the server returns `time.updated >= ts`
 * so the boundary message is included and the caller can dedup by
 * messageID. `0L` is used when the client has no local-applied bookmark
 * for the session (cold path).
 */
data class SlimFetchMessages(
    val sessionId: String,
    val since: Long,
)

/**
 * Cluster A: pure digest reducer. Merges [digest] onto the prior
 * [SlimSessionState] (absent fields preserved) and decides whether to
 * emit a [SlimFetchMessages] (digest signals newer activity than BOTH
 * the prior remote watermark AND the prior local-applied watermark).
 *
 * # Task 6 (split watermark) — what this reducer does / doesn't touch
 *
 *  **Advances (remote path, monotonic):**
 *   - [SlimSessionState.remoteMessageId] — last-write-wins on present
 *     `messageID` (defensive against sidecars that change id without ts).
 *   - [SlimSessionState.remoteUpdatedAt] — `max(prior, incoming)` via
 *     [mergeUpdatedAtMonotonic]. Stale / re-emitted / out-of-order
 *     digests MUST NOT regress this (invariant #1).
 *   - [SlimSessionState.archived] — monotonic-max (permanent timestamp).
 *   - [SlimSessionState.lastError] — three-state merge via
 *     [mergeLastError] (T6-C4 — Omitted preserves, Cleared nulls, Set
 *     replaces).
 *   - [SlimSessionState.directory] / [.status] / [.deleted] —
 *     last-write-wins on present fields.
 *
 *  **Does NOT touch (local-applied path):**
 *   - [SlimSessionState.localAppliedMessageId] /
 *     [SlimSessionState.localAppliedUpdatedAt] — advanced ONLY by
 *     [onReconcileSuccess]. The reducer touching these would violate
 *     invariant #2 (the core split: "applied" means REST-succeeded).
 *
 *  **Dirty flag:**
 *   - Set `true` when [needsReconcile] holds against the post-merge
 *     candidate state (local is behind remote). Cleared ONLY by
 *     [onReconcileSuccess]. The reducer NEVER clears `dirty` — clearing
 *     is deferred to a successful REST reconcile (T6-C3: non-focus
 *     digests set `dirty` and don't clear it).
 *
 * ## Fetch decision — TRIGGER vs ANCHOR split (T6-C5 / rev-gpt Critical fix)
 *
 *  Emit [SlimFetchMessages] when the digest's `updatedAt` is strictly
 *  newer than `max(priorRemoteUpdatedAt, priorLocalAppliedUpdatedAt)`
 *  (preserves the OLD single-watermark trigger semantic — the OLD single
 *  `updatedAt` was effectively the max of the two split fields, so this
 *  keeps the debounce against re-emitted / equal / older digests AND the
 *  "don't fire when local has caught up" guarantee).
 *
 *  The `since` anchor — the value the caller passes to
 *  `/slimapi/messages/{sid}/since/{ts}` (server returns
 *  `time.updated >= ts` for messageID-dedup'd boundary inclusion) — is
 *  **`priorLocalAppliedUpdatedAt ?: 0L`**, NOT the max. The anchor is the
 *  REST boundary: it MUST reflect what we've actually applied. Using
 *  `max(remote, local)` here was a Critical consistency bug — when a
 *  prior reconcile failed (`localApplied < remote`, `dirty=true`), a
 *  fresh digest advancing remote further would emit `since=remote` and
 *  SKIP the `(localApplied, remote]` message range that was never
 *  fetched/applied. `remote` only means "what the server told us"; it
 *  carries no claim about what we've merged locally.
 *
 *  - `digest.updatedAt != null` AND
 *    `digest.updatedAt > max(priorRemote, priorLocal)` → fetch with
 *    `since = priorLocalAppliedUpdatedAt ?: 0L`.
 *  - `digest.updatedAt == null` BUT `digest.messageId != null` AND the
 *    messageId is fresh on BOTH watermarks (sidecar emitted a fresh id
 *    without a timestamp — defensive branch covering sidecars that omit
 *    `updatedAt` on pure-status digests) → fetch with the same
 *    local-applied anchor.
 *  - Otherwise → null (no fetch).
 *
 *  Reconcile-failure retry is the wiring's responsibility (T11 reconcile
 *  loop) — the reducer only re-emits fetch when remote ADVANCES, not on
 *  every digest against a stale dirty state.
 *
 * The reducer mutates [state] in place (it is the accumulator). Callers
 * that need a snapshot should call [SlimSseState.all] separately.
 *
 * Pure modulo the [state] mutation (synchronized). No IO, no coroutine
 * launches, no slice reads. Unit-testable as a black box.
 */
fun reduceSlimDigest(
    state: SlimSseState,
    digest: SlimSessionDigest,
): SlimFetchMessages? {
    val sessionId = digest.sessionId
    // POST-RELEASE instrumentation: one-line per-digest log for the SlimSse
    // diagnostic surface. Mirrors what the live SSE handler already sees
    // (SessionSyncCoordinator logs at "Sync" tag with a different shape);
    // this tag ("SlimSse") keeps the reducer-internal decision surface
    // grouped separately for triage. Fields: sessionID + status +
    // messageID + updatedAt + dirty-after-merge + lastError (when present).
    DebugLog.d(
        "SlimSse",
        "digest sid=$sessionId status=${digest.status ?: "-"} " +
            "mid=${digest.messageId ?: "-"} updatedAt=${digest.updatedAt ?: "-"} " +
            "archived=${digest.archived ?: "-"} deleted=${digest.deleted ?: "-"} " +
            "err=${(digest.lastError as? LastErrorField.Set)?.error?.name ?: "-"}",
    )
    val prev = state.get(sessionId) ?: SlimSessionState(sessionId)
    val priorRemoteUpdatedAt = prev.remoteUpdatedAt
    val priorRemoteMessageId = prev.remoteMessageId
    val priorLocalAppliedUpdatedAt = prev.localAppliedUpdatedAt
    val priorLocalAppliedMessageId = prev.localAppliedMessageId

    // Remote-path merge: monotonic on remoteUpdatedAt + archived; last-write-wins
    // on the rest. lastError is the three-state merge (T6-C4). localApplied* is
    // intentionally NOT copied here — invariant #2 (the reducer never advances
    // the local-applied watermark).
    //
    // §11.1 fix-8 P1-5 — remote watermark is now a proper LEXICOGRAPHIC TUPLE
    // MAX. The prior implementation took monotonic-max on `remoteUpdatedAt`
    // and last-write-wins on `remoteMessageId` INDEPENDENTLY, which could
    // produce a half tuple `(new_ts, old_id)` or `(old_ts, new_id)` that did
    // NOT correspond to any real digest observation. Under T1's tuple
    // semantics the asymmetry was argued-safe (opencode messageID is
    // lexicographically strictly monotonic by creation, so a regressing id
    // at a strictly-larger ts would still tuple-compare correctly), but the
    // ARGUMENT depended on the messageID monotonic invariant — an
    // assumption the reducer should NOT bake in. The fix: only accept a
    // digest's `(updatedAt, messageId)` tuple if BOTH fields are present AND
    // the tuple strictly exceeds the current `(remoteUpdatedAt, remoteMessageId)`
    // tuple via [compareWatermark]. A digest missing either field, OR a
    // digest whose tuple does not strictly exceed the current, leaves BOTH
    // `remote*` fields untouched (preserves the legal-pair invariant from
    // §11.3: only (null, null) or (ts, id) is ever stored).
    //
    // When the prior state has no remote tuple yet (both null), a digest
    // carrying both fields unconditionally adopts it (the tuple-max of
    // (null, null) vs (ts, id) is (ts, id)). When the digest is missing
    // either field, we fall back to the field-level merge for the sidecar's
    // pure-status / pure-messageId digests (covered by tests).
    val mergedRemoteTuple = mergeRemoteTuple(
        priorTs = prev.remoteUpdatedAt,
        priorId = prev.remoteMessageId,
        incomingTs = digest.updatedAt,
        incomingId = digest.messageId,
    )
    val candidate = prev.copy(
        directory = digest.directory ?: prev.directory,
        status = digest.status ?: prev.status,
        remoteMessageId = mergedRemoteTuple.second,
        remoteUpdatedAt = mergedRemoteTuple.first,
        lastError = mergeLastError(prev.lastError, digest.lastError),
        archived = mergeArchivedMonotonic(prev.archived, digest.archived),
        deleted = digest.deleted ?: prev.deleted,
        // localApplied* intentionally omitted — invariant #2.
    )

    // Dirty ratchet: needsReconcile against the post-merge candidate.
    // The reducer only EVER sets dirty=true here; clearing is onReconcileSuccess's
    // exclusive job (T6-C2, T6-C3). Sticky across non-focus digests + reconcile
    // failures.
    val merged = if (needsReconcile(candidate)) candidate.copy(dirty = true) else candidate
    state.put(sessionId, merged)

    // Fetch decision — TRIGGER vs ANCHOR split (rev-gpt Critical fix,
    // restated for the T1 tuple regime):
    //
    //  - TRIGGER (whether to emit fetch at all): T1 tuple compare — see
    //    the full predicate + monotonic-id safety argument immediately
    //    below. Pre-T1 this was a scalar `digest.updatedAt >
    //    max(priorRemote, priorLocal)`; T1 collapses it to one tuple
    //    compare so equal-ts + larger-id digests also fire.
    //  - ANCHOR (the `since` value the caller passes to
    //    `/slimapi/messages/{sid}/since/{ts}`, server returns
    //    `time.updated >= ts`): MUST be `localAppliedUpdatedAt` only.
    //    Using `max(remote, local)` here was the Critical bug — when a
    //    prior reconcile failed (localApplied < remote, dirty=true), a
    //    fresh digest advancing remote further would emit `since=remote`
    //    and SKIP the (localApplied, remote] message range that was never
    //    fetched/applied. localApplied is the ONLY reliable "what we've
    //    actually got" boundary; remote just means "what the server told
    //    us about". The boundary message is included (server returns
    //    `>= ts`) so messageID dedup still works.
    //
    // T1 tuple trigger (slimapi v0.2.2 §G5): fire iff the incoming
    // `(digest.updatedAt, digest.messageId)` tuple is STRICTLY greater than
    // BOTH prior tuples — `(priorRemoteUpdatedAt, priorRemoteMessageId)` and
    // `(priorLocalAppliedUpdatedAt, priorLocalAppliedMessageId)` — in
    // lexicographic order via [compareWatermark]. Pre-T1 this was a scalar
    // `incomingUpdatedAt > max(priorRemote, priorLocal)` compare; T1
    // collapses it to a single tuple compare so equal-ts + larger-id
    // digests also fire (the inverted tie-break — see [compareWatermark]
    // kdoc for the monotonic-id safety argument).
    //
    // Defensive: when the digest carries a fresh messageId WITHOUT an
    // updatedAt ts (covering sidecars that omit `updatedAt` on pure-status
    // digests), the tuple compare can't apply (tsA=null is always oldest).
    // We fall back to the pre-T1 "id differs on BOTH watermarks" rule —
    // unchanged from rev-gpt Critical fix. Anchor is still localApplied-
    // only — same Critical rationale (using max(remote, local) here would
    // skip the (localApplied, remote] message range when a prior reconcile
    // failed).
    val fetchAnchor = priorLocalAppliedUpdatedAt ?: 0L
    val incomingUpdatedAt = digest.updatedAt
    val firesOnTupleTrigger = incomingUpdatedAt != null && (
        compareWatermark(
            incomingUpdatedAt, digest.messageId,
            priorRemoteUpdatedAt, priorRemoteMessageId,
        ) > 0 &&
            compareWatermark(
                incomingUpdatedAt, digest.messageId,
                priorLocalAppliedUpdatedAt, priorLocalAppliedMessageId,
            ) > 0
        )
    val firesOnMessageIdOnly = incomingUpdatedAt == null &&
        digest.messageId != null &&
        digest.messageId != priorRemoteMessageId &&
        digest.messageId != priorLocalAppliedMessageId
    val fetchSince: Long? = when {
        firesOnTupleTrigger -> fetchAnchor
        firesOnMessageIdOnly -> fetchAnchor
        else -> null
    }

    return fetchSince?.let {
        DebugLog.d(
            "SlimSseReducer",
            "digest fetch decision sid=$sessionId since=$it " +
                "(priorRemote=$priorRemoteUpdatedAt priorLocal=$priorLocalAppliedUpdatedAt " +
                "incoming=$incomingUpdatedAt dirty=${merged.dirty})"
        )
        SlimFetchMessages(sessionId = sessionId, since = it)
    }
}

/**
 * Cluster A: monotonic merge for a `Long?` watermark.
 *
 *  - If [incoming] is null → preserve [prior] (no information; §3 debounce).
 *  - If [prior] is null → adopt [incoming] (first observation).
 *  - Otherwise → max(prior, incoming). A strictly-older incoming digest
 *    (SSE re-delivery, sidecar debounce re-emit, out-of-order frame) MUST
 *    NOT regress the watermark, or the next reconcile would re-fire /
 *    miss the gap.
 *
 * Hoisted top-level so [SlimSseReducerTest] can pin the watermark invariant
 * without going through the full reducer.
 */
internal fun mergeUpdatedAtMonotonic(prior: Long?, incoming: Long?): Long? =
    when {
        incoming == null -> prior
        prior == null -> incoming
        else -> maxOf(prior, incoming)
    }

/**
 * §11.1 fix-8 P1-5 + fix-9 P1-1: lexicographic tuple-max merge for the
 * remote `(updatedAt, messageId)` watermark. Returns a `(Long?, String?)`
 * pair that is the tuple-max of the prior and incoming tuples, with the
 * §11.3 legal-pair invariant preserved — **STRICTLY**: only `(null, null)`
 * or `(ts, id)` (both non-null) is ever returned. Half tuples
 * `(ts, null)` / `(null, id)` are NEVER produced.
 *
 * # Cases
 *
 *  - Prior `(null, null)` AND incoming legal pair `(ts, id)` (BOTH non-null)
 *    → adopt incoming (first observation of a legal tuple).
 *  - Prior legal pair `(ts, id)` AND incoming legal pair `(ts', id')` →
 *    take the lexicographic tuple-max via [compareWatermark]. A strictly-
 *    greater incoming wins; equal or smaller incoming keeps prior
 *    (stale / re-emit / out-of-order safe harbor). This is the core P1-5
 *    fix — the prior implementation could form `(new_ts, old_id)` via
 *    last-write-wins on the id, which was argued-safe under the
 *    messageID-monotonicity invariant but baked that assumption into the
 *    reducer. Tuple-max removes the assumption.
 *  - Prior legal pair AND incoming missing one or both fields → keep
 *    prior (P1-5: never overwrite one field of a legal pair with a
 *    partial incoming — would form a half tuple).
 *  - Prior `(null, null)` AND incoming carrying only ONE field
 *    (`updatedAt` only OR `messageId` only) → return `(null, null)`
 *    (P1-1 fix-9: do NOT seed a half tuple from a partial digest). The
 *    reducer's debounce / trigger logic uses [compareWatermark] on the
 *    digest's incoming tuple directly (not the stored tuple), so a
 *    partial digest can still fire the fetch via
 *    `firesOnMessageIdOnly` / `firesOnTupleTrigger` without the stored
 *    tuple being half-formed.
 *  - Both sides empty → `(null, null)`.
 *
 * Pure — no IO, no Android deps. The output is suitable for direct
 * assignment to [SlimSessionState.remoteUpdatedAt] /
 * [SlimSessionState.remoteMessageId].
 */
internal fun mergeRemoteTuple(
    priorTs: Long?,
    priorId: String?,
    incomingTs: Long?,
    incomingId: String?,
): Pair<Long?, String?> {
    // Both null on both sides → (null, null).
    if (priorTs == null && priorId == null && incomingTs == null && incomingId == null) {
        return null to null
    }

    val priorLegal = priorTs != null && priorId != null
    val incomingLegal = incomingTs != null && incomingId != null

    // Both legal → tuple-max via compareWatermark (core P1-5 fix).
    if (priorLegal && incomingLegal) {
        val order = compareWatermark(incomingTs, incomingId, priorTs, priorId)
        return if (order > 0) incomingTs to incomingId else priorTs to priorId
    }

    // Incoming legal + prior not legal → incoming completes / replaces the
    // partial prior with a full tuple observation.
    if (incomingLegal) {
        return incomingTs to incomingId
    }

    // Incoming partial (or empty) below.
    //
    // Prior legal + incoming missing one or both fields → keep prior
    // (P1-5: never overwrite one field of a legal pair with a partial
    // incoming — would form a half tuple).
    if (priorLegal) {
        return priorTs to priorId
    }

    // §11.1 fix-9 P1-1: Neither side is legal. STRICT rejection of half
    // tuples — return (null, null) regardless of partial fields on either
    // side. The prior fix-8 implementation did field-level monotonic-max
    // here, which produced half tuples like (100, null) from a status-only
    // digest against a cold session. The reducer's trigger logic reads
    // the digest's incoming fields directly (not the stored tuple) for
    // the fetch decision, so refusing to seed the stored tuple from
    // partial data does NOT break the cold-start fetch trigger.
    return null to null
}

/**
 * Cluster A (Task 6 §3): monotonic-max merge for the permanent archive
 * timestamp. Mirrors [mergeUpdatedAtMonotonic] — `info.time.archived` is
 * a one-way ratchet (a session, once archived, doesn't un-archive; a
 * stale digest MUST NOT regress the marker). Absent in the digest
 * preserves the prior value (§3 debounce).
 */
internal fun mergeArchivedMonotonic(prior: Long?, incoming: Long?): Long? =
    when {
        incoming == null -> prior
        prior == null -> incoming
        else -> maxOf(prior, incoming)
    }

/**
 * Cluster A (Task 1 hand-off / Task 6 T6-C4): three-state merge of the
 * digest's `lastError` field onto the prior session banner value. The
 * three states are kept distinct (the sidecar relies on them — see
 * [LastErrorField]):
 *
 *  - [LastErrorField.Omitted] → preserve [prior] (key was absent from the
 *    digest frame; a debounce tick that doesn't restate lastError must
 *    NOT clear an active banner).
 *  - [LastErrorField.Cleared] → `null` (key was present-null; sidecar
 *    explicitly signals upstream recovery → clear the session banner).
 *  - [LastErrorField.Set] → the new [SlimSessionLastError] (key was
 *    present-object; sidecar surfaces / replaces the banner).
 *
 * IMPORTANT: do NOT collapse [LastErrorField.Cleared] into
 * [LastErrorField.Omitted] — the wire distinction (present-null vs
 * absent) is exactly what the sidecar uses to signal recovery vs debounce,
 * and T1's [LastErrorFieldSerializer] only makes the distinction visible
 * because the project's Json is `explicitNulls = false`. Collapsing the
 * two would lose the recovery signal and strand an active banner.
 *
 * Hoisted top-level so [SlimSseReducerTest] can pin the three-state merge
 * without going through the full reducer.
 */
internal fun mergeLastError(
    prior: SlimSessionLastError?,
    field: LastErrorField,
): SlimSessionLastError? = when (field) {
    LastErrorField.Omitted -> prior
    LastErrorField.Cleared -> null
    is LastErrorField.Set -> field.error
}

