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
 *  - [localAppliedUpdatedAt] / [localAppliedMessageId]: advanced ONLY by
 *    [onReconcileSuccess] (pure function in [SlimapiResync]). The digest
 *    reducer NEVER touches these. This is the core invariant — "applied"
 *    means a REST fetch succeeded and the result was merged into the
 *    local message cache.
 *  - [dirty]: ratcheted to `true` by the digest reducer when
 *    [needsReconcile] holds after the merge (the local view is behind the
 *    remote view). Cleared to `false` ONLY by [onReconcileSuccess].
 *    [onReconcileFailure] preserves it. Non-focus digests set `dirty`
 *    but never clear it (T6-C3) — clearing is deferred to the next
 *    reconcile pass.
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
     * Advanced ONLY by [onReconcileSuccess] — never by the digest reducer.
     */
    val localAppliedMessageId: String? = null,
    /**
     * Largest `info.time.updated` we've successfully fetched + merged via
     * REST. Advanced ONLY by [onReconcileSuccess]. This is the
     * `/since/{ts}` anchor (per contract §3: server returns
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
     * Reconcile-needed flag. Set `true` by the digest reducer when
     * [needsReconcile] holds after a merge; cleared `false` ONLY by
     * [onReconcileSuccess]. Sticky across non-focus digests + reconcile
     * failures — clearing is deferred to a successful REST reconcile.
     */
    val dirty: Boolean = false,
) {
    /**
     * T6 transitional accessor: the most advanced known `updatedAt`
     * across both watermarks. Prefer [localAppliedUpdatedAt] (we
     * actually fetched it); fall back to [remoteUpdatedAt] (digest
     * observed but not yet reconciled). Removed by T11 once call-sites
     * are rewired to read the split field directly.
     */
    val updatedAt: Long?
        get() = localAppliedUpdatedAt ?: remoteUpdatedAt

    /**
     * T6 transitional accessor: the most advanced known `messageID`
     * across both watermarks (mirrors [updatedAt]). Removed by T11.
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
    // T1-C5 invariant note (slimapi v0.2.2): `remoteMessageId` is last-write-
    // wins while `remoteUpdatedAt` is monotonic-max — an ASYMMETRY that is
    // harmless under T1's tuple watermark semantics. Correctness depends on
    // opencode messageID being lexicographically strictly monotonic by
    // creation (see [compareWatermark] kdoc — id.ts ascending). A stale /
    // out-of-order digest necessarily carries a strictly-smaller id (the
    // counter-based prefix is monotonic), so even though last-write-wins may
    // regress remoteMessageId relative to remoteUpdatedAt, the resulting
    // (remoteUpdatedAt, remoteMessageId) tuple is still <= any genuinely-newer
    // observation — [needsReconcile]'s tuple compare correctly returns
    // negative for stale digests and never spuriously fires a reconcile.
    // Symmetrizing this merge (e.g. dropping a digest whose id regresses) is
    // YAGNI — grill confirmed no real-world sidecar triggers this case.
    val candidate = prev.copy(
        directory = digest.directory ?: prev.directory,
        status = digest.status ?: prev.status,
        remoteMessageId = digest.messageId ?: prev.remoteMessageId,
        remoteUpdatedAt = mergeUpdatedAtMonotonic(prev.remoteUpdatedAt, digest.updatedAt),
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

