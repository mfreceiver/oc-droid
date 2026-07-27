package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.MessageWithParts

/**
 * §11.2 (slim message reliability joint plan — stage A): a complete,
 * already-merged candidate produced by an authoritative full / cursor
 * drain. The candidate is the single input to the stage-A
 * **process-local token-guarded commit** ([SlimAuthoritativeCommitter]).
 *
 * # What "candidate" means here (MANDATORY caller contract)
 *
 * Stage A does NOT verify wire-level completeness itself. Constructing a
 * [SlimAuthoritativeCandidate] is the caller's affirmative statement that
 * ALL of the following hold (plan §11.2 step 3):
 *
 *  - HTTP success, body non-null, every [MessageWithParts] deserialised.
 *  - All cursor pages consumed AND the final page carried `nextCursor == null`.
 *  - No item / page cap was hit while the cursor was still non-null.
 *  - No cursor loop, timeout, or truncation occurred.
 *  - [messages] is the merged aggregate of the authoritative window
 *    (caller used [mergeSlimMessageSet] with `complete = true`, which in
 *    turn is only valid when the candidate is a phase-B-frozen complete
 *    full / cursor snapshot — NOT a `X-Since-Complete: true` header).
 *
 * Mapping a stage-A `/since` response (including one carrying
 * `X-Since-Complete: true`) into a [SlimAuthoritativeCandidate] is
 * FORBIDDEN — that header is a transport hint, not a completeness proof.
 *
 * # localApplied* derivation (caller's job, NOT the committer's)
 *
 * [localAppliedUpdatedAt] / [localAppliedMessageId] MUST be filled by the
 * caller from [maxMessageTuple]:
 *
 * ```kotlin
 * val mergeResult = mergeSlimMessageSetWithConflict(authoritative, items, complete = true)
 * val merged = mergeResult.messages
 * val (ts, id) = maxMessageTuple(merged)?.let { it.first to it.second }
 *     ?: (null to null)
 * SlimAuthoritativeCandidate(
 *     sessionId = sid,
 *     token = token,
 *     messages = merged,
 *     localAppliedUpdatedAt = ts,
 *     localAppliedMessageId = id,
 *     hasConflict = mergeResult.hasConflict,
 * )
 * ```
 *
 * The committer trusts the candidate's tuple and writes it verbatim — it
 * does NOT re-derive from [messages]. Re-derivation inside the commit
 * would couple the watermark to the message list's order/contents and
 * mask caller bugs (plan §11.2 / test
 * `successfulCommitUsesCandidateLocalAppliedTupleNotDerivedFromMessages`).
 *
 * # hasConflict — atomic dirty decision (§11.1 fix-10 P1-1 / rev-ogpt P1-1+P1-2)
 *
 * [hasConflict] is `true` when [mergeSlimMessageSetWithConflict] detected
 * at least one same-tuple-different-parts divergence. The committer
 * carries this flag into the critical section and uses it as an INPUT to
 * the atomic dirty decision inside
 * [SlimAuthoritativeCommitHost.replaceLocalAppliedAndClearDirty]:
 *
 *  - `hasConflict = true` ⇒ `dirty = true` UNCONDITIONALLY (regardless of
 *    remote vs localApplied). The authoritative parts diverge from the
 *    incoming parts at the same tuple; a later reconcile is needed.
 *  - `hasConflict = false` ⇒ existing logic (remote > localApplied ⇒
 *    dirty = true; else dirty = false).
 *
 * This closes BOTH the P1-1 hole (reconciler path's `markDirty` was a
 * no-op for same-tuple conflicts because `needsReconcile` returns false
 * when remote <= localApplied) AND the P1-2 atomicity window (commit
 * completed → dirty=false → then a SEPARATE `forceSlimDirty` critical
 * section re-set dirty=true; concurrent observers between those two
 * critical sections saw dirty=false against a divergent authoritative
 * set). The conflict flag is now an input to the SAME critical section
 * that writes localApplied* — atomically decided, no intermediate window.
 *
 * # DTO — no message copy
 *
 * [messages] holds the existing [MessageWithParts] instances; this DTO
 * does not clone them. The [SlimAuthoritativeCommitter] makes a defensive
 * shallow copy of the list before handing it to the host so the caller's
 * list cannot be mutated in place (test
 * `commitDoesNotMutateMessagesListInstance`).
 *
 * @see SlimAuthoritativeCommitter
 * @see SlimAuthoritativeCommitResult
 */
data class SlimAuthoritativeCandidate(
    val sessionId: String,
    val token: OpenCodeRepository.SlimCommitToken,
    val messages: List<MessageWithParts>,
    val localAppliedUpdatedAt: Long?,
    val localAppliedMessageId: String?,
    /**
     * §11.1 fix-10 P1-1 / rev-ogpt P1-1+P1-2: `true` iff the merge that
     * produced [messages] detected at least one same-tuple-different-parts
     * divergence. The committer threads this into the commit's atomic
     * dirty decision — see class KDoc "# hasConflict" above. Defaults to
     * `false` for backward compat with candidates constructed without the
     * merge-contract signal (legacy paths + existing tests).
     */
    val hasConflict: Boolean = false,
)

/**
 * §11.2: outcome of [SlimAuthoritativeCommitter.commitAuthoritative].
 *
 *  - [Committed] — the candidate was applied atomically to the
 *    authoritative memory state (visible content, authoritative cache,
 *    localApplied watermark). `dirty` is cleared UNLESS the candidate's
 *    `hasConflict=true` OR an in-lock same-tuple content conflict was
 *    detected (rev-ogpt P1-1: a concurrent candidate landed the same
 *    watermark with different parts), OR `remote > localApplied`
 *    post-write (P0-4 TOCTOU) — all three force `dirty=true` so a
 *    follow-up reconcile re-fetches to resolve the divergence.
 *  - [StaleToken] — the [SlimAuthoritativeCandidate.token] was no longer
 *    current at the pre-check OR at the in-critical-section recheck
 *    (marker rotation, epoch bump, generation/endpoint mismatch — stage A
 *    collapses all of these into `StaleToken`; there is no separate
 *    stamp-conflict result). No memory state was mutated; `dirty` stays
 *    `true`.
 *  - [CacheWriteFailed] — the diagnostic (non-authoritative) cache write
 *    raised [cause]. Stage A keeps the commit protocol simple: a cache
 *    failure aborts BEFORE the in-memory critical section, so the
 *    authoritative memory state is unchanged (old visible content, old
 *    localApplied*, `dirty = true`). The cache itself is non-authoritative
 *    and may be partially written; that is acceptable.
 *  - [MergeRejected] — the candidate failed structural validation
 *    ([reason]); the caller must not retry the same candidate. No memory
 *    state was mutated.
 */
sealed interface SlimAuthoritativeCommitResult {
    data object Committed : SlimAuthoritativeCommitResult
    data object StaleToken : SlimAuthoritativeCommitResult
    data class CacheWriteFailed(val cause: Throwable) : SlimAuthoritativeCommitResult
    data class MergeRejected(val reason: String) : SlimAuthoritativeCommitResult
}

/**
 * §11.2: process-local token-guarded authoritative commit for stage A.
 *
 * Implementations advance the authoritative memory state — visible
 * content, authoritative cache, localApplied watermark, and `dirty` —
 * atomically inside the host's [SlimAuthoritativeCommitHost.commitIfCurrent]
 * critical section, iff the candidate's token is still current.
 *
 * # What this is NOT
 *
 * Stage A does **not** claim cross-process or cross-restart recoverability.
 * If the process exits after a successful commit, in-memory state is lost;
 * cache state may or may not be persisted (and is non-authoritative). The
 * commit is only token-guarded against in-process races (host rotation,
 * epoch bump, generation/endpoint mismatch). A future crash-recovery
 * protocol must add a separate persistence interface, checksum, and
 * start-up recovery path before this claim can be widened.
 *
 * # Fixed 7-step commit order (plan §11.2 steps 1–7)
 *
 *  1. Capture pre-commit state (`oldVisibleMessages`, `oldLocalApplied*`,
 *     `oldDirty`) for failure-branch assertions. The `remote*` fields are
 *     NOT read for `/since` watermarking (they are advanced only by the
 *     digest reducer).
 *  2. `requireToken(token)` — pre-check; throws `StaleSlimCommitException`
 *     → return [SlimAuthoritativeCommitResult.StaleToken]. No cache write,
 *     no visible replacement, no localApplied advance.
 *  3. Validate candidate structural completeness →
 *     [SlimAuthoritativeCommitResult.MergeRejected] on failure.
 *  4. Cache write (diagnostic, non-authoritative) →
 *     [SlimAuthoritativeCommitResult.CacheWriteFailed] on throw; the
 *     in-memory commit does NOT proceed.
 *  5. `commitIfCurrent(token) { applyAuthoritativeMemoryState(...) }` —
 *     the SOLE token/incarnation check inside the critical section. A
 *     `false` return (token rotated after the cache write) maps to
 *     [SlimAuthoritativeCommitResult.StaleToken]; the cache write is left
 *     in place (non-authoritative, will be overwritten on the next
 *     successful commit) but the memory state is unchanged.
 *  6. On `true`: the host replaces `authoritativeLocal`, `visibleContent`,
 *     `localApplied*` (both fields together), and decides `dirty`
 *     ATOMICALLY inside the same critical section — `hasConflict=true`
 *     (candidate's merge flagged a same-tuple-different-parts divergence)
 *     OR an in-lock same-tuple content conflict (rev-ogpt P1-1: a
 *     concurrent candidate already wrote different parts at this
 *     watermark) forces `dirty=true`; otherwise `needsReconcile` against
 *     the post-write state decides (P0-4: `remote > localApplied` re-sets
 *     `dirty=true`). The normal happy path clears `dirty=false`.
 *  7. Stage A does NOT require cache and memory to be updated
 *     simultaneously; the success assertion only checks authoritative
 *     memory + localApplied + dirty.
 */
interface SlimAuthoritativeCommitter {
    suspend fun commitAuthoritative(
        candidate: SlimAuthoritativeCandidate,
    ): SlimAuthoritativeCommitResult
}

// ─────────────────────────────────────────────────────────────────────────────
// §11.2 internal collaboration surface
// ─────────────────────────────────────────────────────────────────────────────

/**
 * §11.2: narrow collaboration surface that [InternalSlimAuthoritativeCommitter]
 * needs from the repository owner. Defined as an `internal` interface so
 * the committer is decoupled from the full [OpenCodeRepository] (testable
 * with a fake host), and so fix-4 (§11.1 wiring) has a crisp contract to
 * implement on [OpenCodeRepository] (or to adapt via lambdas).
 *
 * # Threading contract
 *
 *  - [requireToken], [captureCurrentState], [captureCurrentVisibleMessages],
 *    and [writeDiagnosticCache] are called by the committer OUTSIDE the
 *    state lock. They may acquire the lock internally for a consistent
 *    snapshot but must not depend on the committer holding it.
 *  - [commitIfCurrent] acquires the state lock; the [commit] block runs
 *    inline under that lock. Inside [commit], the committer calls
 *    [replaceVisibleAndAuthoritative] and
 *    [replaceLocalAppliedAndClearDirty] in sequence — both MUST be
 *    non-suspending in-memory mutations (no IO, no coroutine).
 *
 * # fix-4 implementation note
 *
 * [OpenCodeRepository] already provides direct equivalents:
 *
 * | host op                              | OpenCodeRepository method               |
 * |--------------------------------------|-----------------------------------------|
 * | [requireToken]                       | [OpenCodeRepository.requireSlimTokenCurrent]   |
 * | [commitIfCurrent]                    | [OpenCodeRepository.commitIfSlimTokenCurrent]  |
 * | [captureCurrentState]                | [OpenCodeRepository.getSlimSessionState] (via `slimStateMachine`) |
 * | [captureCurrentVisibleMessages]      | (new — backed by the authoritative message store fix-4 adds) |
 * | [writeDiagnosticCache]               | (new — non-authoritative cache write path) |
 * | [replaceVisibleAndAuthoritative]     | (new — writes the authoritative + visible store) |
 * | [replaceLocalAppliedAndClearDirty]   | (new — SlimSessionState read-modify-write under the lock) |
 *
 * The four "new" rows are storage that fix-4 wires; the plan's
 * `authoritativeLocal` / `visibleContent` field names refer to those
 * stores. Stage A does not pre-create them — only the committer + host
 * contract is delivered here.
 */
internal interface SlimAuthoritativeCommitHost {
    /**
     * Pre-check; throws [OpenCodeRepository.StaleSlimCommitException]
     * iff [token] is no longer the current repository incarnation.
     * Called by the committer BEFORE the critical section.
     */
    fun requireToken(token: OpenCodeRepository.SlimCommitToken)

    /**
     * Snapshot the current [SlimSessionState] for [sessionId] (or null
     * when the session has no state yet). Used by the committer to
     * capture the pre-commit `localApplied*` / `dirty` for failure-branch
     * assertions.
     */
    fun captureCurrentState(sessionId: String): SlimSessionState?

    /**
     * Snapshot the current authoritative / visible message list for
     * [sessionId] (empty when none). Used by the committer to capture
     * `oldVisibleMessages` for failure-branch assertions.
     */
    fun captureCurrentVisibleMessages(sessionId: String): List<MessageWithParts>

    /**
     * Write the diagnostic (non-authoritative) cache for [sessionId].
     * Called BEFORE the in-memory critical section. A throw aborts the
     * commit and surfaces as
     * [SlimAuthoritativeCommitResult.CacheWriteFailed]; the in-memory
     * state is NOT mutated.
     *
     * Stage A treats the cache as best-effort diagnostic storage. The
     * implementation MAY make this a no-op (e.g. in tests where cache
     * failure is not under test); it MUST surface real failures via a
     * throw so the committer can short-circuit.
     */
    fun writeDiagnosticCache(sessionId: String, messages: List<MessageWithParts>)

    /**
     * Atomic critical section: runs [commit] under the state lock iff
     * [token] is still current. Returns `true` when [commit] ran,
     * `false` when the token rotated first (the caller MUST treat as
     * stale and short-circuit).
     *
     * The [commit] block MUST contain only in-memory state/effect
     * commits — no network, delay, blocking disk IO, or suspend call.
     * Mirrors [OpenCodeRepository.commitIfSlimTokenCurrent].
     */
    fun commitIfCurrent(
        token: OpenCodeRepository.SlimCommitToken,
        commit: () -> Unit,
    ): Boolean

    /**
     * Inside [commitIfCurrent]'s critical section: replace the
     * authoritative + visible message list for [sessionId] with
     * [messages]. MUST be called only from within [commitIfCurrent]'s
     * [commit] block.
     *
     * The implementor MAY make a defensive copy of [messages]; the
     * committer already passes a fresh copy (see
     * [InternalSlimAuthoritativeCommitter]), so an additional copy is
     * optional.
     */
    fun replaceVisibleAndAuthoritative(
        sessionId: String,
        messages: List<MessageWithParts>,
    )

    /**
     * Inside [commitIfCurrent]'s critical section: replace the
     * localApplied watermark (BOTH [localAppliedUpdatedAt] AND
     * [localAppliedMessageId] together — they are a legal pair per
     * §11.3) AND decide `dirty` ATOMICALLY. MUST be called only from
     * within [commitIfCurrent]'s [commit] block.
     *
     * [localAppliedUpdatedAt] / [localAppliedMessageId] are the
     * candidate's tuple (verbatim — the committer does NOT re-derive
     * from messages). When both are null (cold-start of an
     * eligible-less session), the implementor clears the watermark;
     * `dirty` is decided by the rules below.
     *
     * MUST NOT touch `remote*` — those are advanced only by the digest
     * reducer (plan invariant #1).
     *
     * # §11.1 fix-9 P0-4 + rev-ogpt P1-1/P1-2 — atomic dirty decision
     *
     * The implementor decides `dirty` from [hasConflict] AND the
     * post-write session state, INSIDE the same critical section that
     * wrote localApplied*:
     *
     *  - `hasConflict = true` ⇒ `dirty = true` UNCONDITIONALLY. The
     *    candidate's merge detected a same-tuple-different-parts
     *    divergence — the watermark is aligned (remote <= localApplied
     *    post-write) but the authoritative parts are stale. Forcing
     *    dirty=true here means the next reconcile re-fetches to resolve
     *    the parts divergence. This closes P1-1 (the prior reconciler
     *    path called `markDirty` after the commit, which was a no-op
     *    because `needsReconcile` returns false on an aligned watermark)
     *    and P1-2 (the prior engine path called `forceSlimDirty` in a
     *    SEPARATE critical section after the commit, leaving a window
     *    where dirty=false against a divergent authoritative set).
     *  - `hasConflict = false` ⇒ run [needsReconcile] against the
     *    post-write state. If `remoteUpdatedAt / remoteMessageId >
     *    candidateUpdatedAt / candidateMessageId` (via [compareWatermark]),
     *    dirty MUST be re-set to `true`: the server has observed activity
     *    beyond what we just applied, so reconcile is still needed
     *    (P0-4: TOCTOU mitigation for a digest arriving mid-drain).
     *    Otherwise dirty clears to `false` (normal happy path).
     *
     * `dirty=true` writers: (a) the committer (this method) when
     * `hasConflict=true`, OR when an in-lock per-ID conflict-aware merge
     * detects a same-tuple-different-parts divergence (rev-ogpt P1-1
     * fix-13: a concurrent candidate landed the same watermark with
     * different parts at some ID), OR when `remote > localApplied`
     * post-write (P0-4); (b) the digest reducer via [needsReconcile];
     * (c) [SlimSseStateMachine.forceSlimDirty] (production: the cache-
     * retention failure path in [SlimSessionReconciler] when the post-REST
     * retention guard rejects the merged set; diagnostic / test paths also
     * retain it as an unconditional dirty ratchet). The commit-time atomic
     * decision is the authoritative bridge between "drain captured a
     * snapshot at time T" and "remote may have advanced past T / parts may
     * have diverged by the time the commit lands". The conflict hot path
     * no longer needs a separate `forceSlimDirty` / `markDirty` post-write
     * — both prior P1-1 holes (reconciler no-op `markDirty` + engine
     * race-window `forceSlimDirty`) are eliminated by the atomic
     * `hasConflict` decision. [forceSlimDirty] is retained because the
     * retention-failure production path is OUTSIDE the commit protocol
     * (the rejection happens after the candidate was committed, so it
     * cannot ride the commit's atomic decision).
     */
    fun replaceLocalAppliedAndClearDirty(
        sessionId: String,
        localAppliedUpdatedAt: Long?,
        localAppliedMessageId: String?,
        hasConflict: Boolean,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// §11.2 internal implementation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * §11.2: internal [SlimAuthoritativeCommitter] that drives a
 * [SlimAuthoritativeCommitHost] through the fixed 7-step commit order.
 *
 * State-less aside from its [host] reference — all mutable state lives
 * in the host (and ultimately in [OpenCodeRepository]'s `slimStateLock`-
 * guarded stores). This keeps the committer trivially testable with a
 * fake host.
 */
internal class InternalSlimAuthoritativeCommitter(
    private val host: SlimAuthoritativeCommitHost,
) : SlimAuthoritativeCommitter {

    override suspend fun commitAuthoritative(
        candidate: SlimAuthoritativeCandidate,
    ): SlimAuthoritativeCommitResult {
        // Step 1: capture pre-commit state for failure-branch assertions.
        // Do NOT read `remote*` for /since watermarking — remote is owned
        // by the digest reducer (plan invariant #1).
        val oldState = host.captureCurrentState(candidate.sessionId)
        val oldVisibleMessages = host.captureCurrentVisibleMessages(candidate.sessionId)
        val oldLocalAppliedUpdatedAt = oldState?.localAppliedUpdatedAt
        val oldLocalAppliedMessageId = oldState?.localAppliedMessageId
        val oldDirty = oldState?.dirty ?: false

        // Step 2: token pre-check. requireToken throws StaleSlimCommitException
        // when the token is stale (marker rotation, epoch bump, generation /
        // endpoint mismatch — stage A collapses all of these into StaleToken;
        // there is no separate stamp-conflict result). No cache write, no
        // visible replacement, no localApplied advance on this branch.
        try {
            host.requireToken(candidate.token)
        } catch (e: OpenCodeRepository.StaleSlimCommitException) {
            assertFailureBranchInvariant(
                candidate.sessionId,
                oldVisibleMessages,
                oldLocalAppliedUpdatedAt,
                oldLocalAppliedMessageId,
                oldDirty,
            )
            return SlimAuthoritativeCommitResult.StaleToken
        }

        // Step 3: structural candidate validation (PURE checks only — the
        // authoritative monotonic + empty-tuple guards run INSIDE the
        // critical section below; the committer cannot make concurrency
        // decisions outside the lock).
        val structuralRejection = validateCandidateStructural(candidate)
        if (structuralRejection != null) {
            assertFailureBranchInvariant(
                candidate.sessionId,
                oldVisibleMessages,
                oldLocalAppliedUpdatedAt,
                oldLocalAppliedMessageId,
                oldDirty,
            )
            return structuralRejection
        }

        // §11.1 fix-9 P0-1 / P0-2: FAST-PATH reject (outside the lock) for
        // obvious monotonic regressions and empty-candidate-on-non-empty-
        // watermark. This is an optimization — the authoritative check
        // runs INSIDE the critical section below; if a concurrent commit
        // raced between this snapshot and the lock, the in-lock check
        // catches it. The fast-path avoids the cache write for the
        // common case where the candidate is obviously stale.
        val fastPathRejection = fastPathMonotonicCheck(candidate, oldState)
        if (fastPathRejection != null) {
            assertFailureBranchInvariant(
                candidate.sessionId,
                oldVisibleMessages,
                oldLocalAppliedUpdatedAt,
                oldLocalAppliedMessageId,
                oldDirty,
            )
            return fastPathRejection
        }

        // Step 4: diagnostic cache write. Non-authoritative; a throw
        // aborts the commit BEFORE the in-memory critical section, so the
        // memory state stays at old* (plan §11.2 step 5).
        try {
            // Defensive copy: the cache path must not mutate the
            // candidate's list reference (test commitDoesNotMutateMessagesListInstance).
            host.writeDiagnosticCache(
                candidate.sessionId,
                ArrayList(candidate.messages),
            )
        } catch (e: Throwable) {
            assertFailureBranchInvariant(
                candidate.sessionId,
                oldVisibleMessages,
                oldLocalAppliedUpdatedAt,
                oldLocalAppliedMessageId,
                oldDirty,
            )
            return SlimAuthoritativeCommitResult.CacheWriteFailed(e)
        }

        // Step 5 + 6: atomic in-memory commit under the state lock. The
        // host's commitIfCurrent rechecks the token inside the lock
        // (TOCTOU mitigation). `false` ⇒ token rotated between the
        // pre-check and the critical section — the cache write is left
        // in place (non-authoritative, will be overwritten on the next
        // successful commit) but the memory state stays at old*.
        //
        // §11.1 fix-9 P0-1 — IN-LOCK MONOTONIC RE-CHECK. The candidate's
        // monotonic guard MUST run inside this critical section: a
        // concurrent committer may have advanced the watermark between
        // the outer fast-path check and the lock acquire. We re-read
        // `host.captureCurrentState` (acquires the same lock reentrantly)
        // and re-run the monotonic + empty-tuple checks. If they fail,
        // we signal back via `inLockRejection` and exit WITHOUT touching
        // `replaceVisibleAndAuthoritative` / `replaceLocalAppliedAndClearDirty`.
        // The host returns `committed = true` (token IS still current),
        // but we translate the in-lock rejection to MergeRejected at the
        // call site.
        var inLockRejection: SlimAuthoritativeCommitResult.MergeRejected? = null
        val committed = host.commitIfCurrent(candidate.token) {
            // §11.1 fix-9 P0-1: AUTHORITATIVE re-read inside the lock.
            val currentState = host.captureCurrentState(candidate.sessionId)
            val inLockRejectionReason = authoritativeInLockCheck(candidate, currentState)
            if (inLockRejectionReason != null) {
                inLockRejection = SlimAuthoritativeCommitResult.MergeRejected(inLockRejectionReason)
                return@commitIfCurrent
            }
            // §11.1 rev-ogpt P1-1 (six rounds → fix-13 → fix-14) — IN-LOCK
            // PER-ID CONFLICT-AWARE MERGE on EVERY content-differing
            // candidate, regardless of global tuple relationship.
            //
            // The outer authoritativeInLockCheck only re-checks watermark
            // monotonicity + the empty-candidate regression; it does NOT
            // detect a concurrent candidate that landed DIFFERENT parts
            // — either at the SAME global watermark tuple OR at an EARLIER
            // tuple whose message IDs the strictly-advancing candidate
            // also carries.
            //
            // History:
            //  - fix-12a: whole-list content compare at EQUAL tuple only,
            //    preserve-all-current on divergence. Over-rejected (legit
            //    per-message updates at equal global max were dropped →
            //    dirty loop).
            //  - fix-13: per-ID conflict-aware merge, but STILL gated on
            //    equal global tuple. Strictly-advancing candidates skipped
            //    the merge and overwrote current wholesale. The hole: A/B
            //    both drain from an OLD snapshot → B commits m_a@250 (global
            //    max 250) → A carries m_a@100 (older) + m_b@300 (new global
            //    max) → A's global tuple (300) > current (250), monotonic
            //    guard admits, NO merge → m_a regresses 250 → 100 silently,
            //    and dirty may clear false.
            //
            // fix-14 closes it: the global tuple decides only whether the
            // candidate is ADMITTED (monotonic guard, already run above);
            // the CONTENT layer ALWAYS runs per-ID merge whenever the
            // candidate's content differs from the lock-latest
            // [currentMessages]. [mergeSlimMessageSetWithConflict] with
            // currentMessages as `authoritative` and candidate.messages as
            // `incoming` gives the correct per-ID semantics:
            //  - strictly newer tuple at an existing ID → take incoming
            //    (legitimate update).
            //  - strictly older tuple at an existing ID → keep current
            //    (lock-latest wins; closes the fix-13 advancing-tuple
            //    regression — m_a@100 cannot overwrite m_a@250).
            //  - same-tuple + different parts at an existing ID → keep
            //    current + set inLockConflict=true (the §11.4 divergence
            //    the dirty-keeping is supposed to flag).
            //  - new ID in incoming not in current → add it.
            //  - ID in current but missing from incoming → PRESERVED
            //    (missing ≠ deleted; the candidate may legitimately be a
            //    partial drain that doesn't enumerate every prior ID).
            //
            // `messagesToCommit` becomes the new authoritative/visible
            // content; `inLockConflict` is OR'd with the candidate's own
            // `hasConflict` to form `effectiveHasConflict`, which is the
            // atomic dirty input for [replaceLocalAppliedAndClearDirty].
            //
            // Identical-content short-circuit: when
            // `currentMessages == candidate.messages`, the merge is a no-op;
            // skip the work and use the candidate's content + hasConflict
            // directly (idempotent re-commit fast path).
            val currentMessages = host.captureCurrentVisibleMessages(candidate.sessionId)
            val (mergedMessages, inLockConflict) = if (currentMessages != candidate.messages) {
                val inLockMerge = mergeSlimMessageSetWithConflict(
                    authoritative = currentMessages,
                    incoming = candidate.messages,
                    complete = true,
                )
                inLockMerge.messages to inLockMerge.hasConflict
            } else {
                // Identical content (idempotent re-commit) — candidate's
                // content + candidate's own hasConflict are authoritative.
                candidate.messages to candidate.hasConflict
            }
            val effectiveHasConflict = candidate.hasConflict || inLockConflict
            // Defensive copy: the host stores the message list; we must not
            // expose the source list reference to in-place mutation (test
            // commitDoesNotMutateMessagesListInstance). Both branches above
            // produce a fresh list (the merge allocates; the idempotent
            // branch is the candidate's own list), but wrap in ArrayList
            // anyway so the host receives a uniformly-typed mutable list
            // regardless of which collection-impl the merge returned.
            val messagesToCommit = ArrayList(mergedMessages)
            host.replaceVisibleAndAuthoritative(
                candidate.sessionId,
                messagesToCommit,
            )
            host.replaceLocalAppliedAndClearDirty(
                candidate.sessionId,
                candidate.localAppliedUpdatedAt,
                candidate.localAppliedMessageId,
                effectiveHasConflict,
            )
        }
        if (inLockRejection != null) {
            // P0-1 / P0-2: in-lock monotonic / empty-tuple guard fired.
            // The host did NOT replace state (we returned early from the
            // commit lambda). Re-evaluate the failure-branch invariant
            // against the post-lock state.
            assertFailureBranchInvariant(
                candidate.sessionId,
                oldVisibleMessages,
                oldLocalAppliedUpdatedAt,
                oldLocalAppliedMessageId,
                oldDirty,
            )
            return inLockRejection!!
        }
        if (!committed) {
            assertFailureBranchInvariant(
                candidate.sessionId,
                oldVisibleMessages,
                oldLocalAppliedUpdatedAt,
                oldLocalAppliedMessageId,
                oldDirty,
            )
            return SlimAuthoritativeCommitResult.StaleToken
        }

        // Step 7: success. Do NOT assert cache coherency with memory —
        // stage A does not require them to update simultaneously.
        return SlimAuthoritativeCommitResult.Committed
    }

    // ── §11.2 step 3: structural validation (PURE, no concurrency) ──────

    /**
     * PURE structural validation of [candidate] — independent of any
     * session state. Returns a [SlimAuthoritativeCommitResult.MergeRejected]
     * for the first violation, or `null` when structurally sound.
     *
     *  - [sessionId] must be non-blank.
     *  - The localApplied pair must be legal per §11.3: either
     *    `(null, null)` or `(non-null, non-null)`. A split pair
     *    `(ts, null)` or `(null, id)` is rejected.
     *  - Empty [messages] ⇒ localApplied pair must be `(null, null)`
     *    (you cannot derive a watermark from no messages).
     *  - Non-empty [messages] with at least one eligible item
     *    (`info.time.updated > 0L && info.id.isNotBlank()`) ⇒ localApplied
     *    pair must be non-null. This catches the "caller forgot to run
     *    `maxMessageTuple` after a complete drain" case (test
     *    `partialCandidateCannotCommit`). NOTE: this is VALIDATION only;
     *    the committed value is still the candidate's tuple, NOT a
     *    re-derivation (test
     *    `successfulCommitUsesCandidateLocalAppliedTupleNotDerivedFromMessages`).
     *
     * Concurrency-sensitive checks (monotonic watermark, empty-tuple
     * regression) live in [fastPathMonotonicCheck] (outside the lock)
     * and [authoritativeInLockCheck] (inside the critical section).
     */
    private fun validateCandidateStructural(
        candidate: SlimAuthoritativeCandidate,
    ): SlimAuthoritativeCommitResult.MergeRejected? {
        if (candidate.sessionId.isBlank()) {
            return SlimAuthoritativeCommitResult.MergeRejected("blank sessionId")
        }

        val ts = candidate.localAppliedUpdatedAt
        val id = candidate.localAppliedMessageId
        // §11.3 pair legality: both null or both non-null.
        if ((ts == null) != (id == null)) {
            return SlimAuthoritativeCommitResult.MergeRejected(
                "split localApplied pair (updated=$ts, id=$id); §11.3 allows only (null,null) or (ts,id)",
            )
        }

        // Empty messages ⇒ localApplied pair must be null. A watermark
        // cannot be derived from an empty aggregate; a non-null pair here
        // means the caller did not run maxMessageTuple (test
        // candidateWithNullMessagesReturnsMergeRejected).
        if (candidate.messages.isEmpty() && ts != null) {
            return SlimAuthoritativeCommitResult.MergeRejected(
                "empty messages but non-null localApplied pair; caller must clear localApplied* via maxMessageTuple(empty) = null",
            )
        }

        // Eligible messages ⇒ localApplied pair must be non-null. If
        // maxMessageTuple finds an eligible item, the caller MUST have
        // captured its tuple; a null pair here means the candidate is
        // incomplete (test partialCandidateCannotCommit).
        //
        // NOTE: this branch does NOT re-derive the committed watermark.
        // It only checks that the caller provided *some* non-null tuple.
        // The committed value is still candidate.localApplied*, verbatim.
        if (ts == null && candidate.messages.isNotEmpty()) {
            val hasEligible = maxMessageTuple(candidate.messages) != null
            if (hasEligible) {
                return SlimAuthoritativeCommitResult.MergeRejected(
                    "messages contain an eligible item (updated>0, non-blank id) but localApplied pair is null; caller must derive via maxMessageTuple",
                )
            }
        }

        return null
    }

    /**
     * §11.1 fix-9 P0-1 / P0-2 FAST-PATH monotonic check. Runs OUTSIDE
     * the critical section against the captured [oldState] snapshot. This
     * is purely an optimization — the AUTHORITATIVE check runs inside
     * [authoritativeInLockCheck] under the lock.
     *
     * Returns `null` when the candidate passes the fast-path check
     * (caller proceeds to the cache write + critical section), or a
     * [SlimAuthoritativeCommitResult.MergeRejected] when the candidate
     * is OBVIOUSLY stale relative to the captured snapshot (saves the
     * cache write work for the common case of a late-arriving candidate
     * whose tuple is strictly less than the pre-commit watermark).
     */
    private fun fastPathMonotonicCheck(
        candidate: SlimAuthoritativeCandidate,
        oldState: SlimSessionState?,
    ): SlimAuthoritativeCommitResult.MergeRejected? {
        val ts = candidate.localAppliedUpdatedAt
        val id = candidate.localAppliedMessageId
        if (oldState != null && ts != null) {
            val oldTs = oldState.localAppliedUpdatedAt
            val oldId = oldState.localAppliedMessageId
            if (oldTs != null && oldId != null) {
                val order = compareWatermark(ts, id, oldTs, oldId)
                if (order < 0) {
                    return SlimAuthoritativeCommitResult.MergeRejected(
                        "non-monotonic localApplied candidate (updated=$ts, id=$id) " +
                            "< oldState (updated=$oldTs, id=$oldId); " +
                            "a later commit already advanced the watermark — refuse the regression",
                    )
                }
            }
        }
        return null
    }

    /**
     * §11.1 fix-9 P0-1 / P0-2 AUTHORITATIVE in-lock check. Runs INSIDE
     * the host's [commitIfCurrent] critical section, against the
     * re-read [currentState]. This is the SOLE authoritative monotonic
     * + empty-tuple guard — the [fastPathMonotonicCheck] is only an
     * optimization. If this returns a non-null reason, the committer
     * exits the [commitIfCurrent] block WITHOUT calling
     * [replaceVisibleAndAuthoritative] or [replaceLocalAppliedAndClearDirty].
     *
     *  - **Monotonic** (P0-1): candidate tuple MUST be `>=` the in-lock
     *    current tuple in lexicographic order via [compareWatermark]. A
     *    concurrent committer may have advanced the watermark between
     *    the outer snapshot and the lock acquire; this check catches
     *    that race and refuses the regression.
     *  - **Empty-candidate on non-empty watermark** (P0-2): a candidate
     *    carrying `(null, null)` against a current state with a non-null
     *    localApplied pair is a regression (the candidate would clear
     *    the watermark). Reject it. Empty-candidate against an empty
     *    watermark is allowed (cold-start of an empty session).
     *
     * Returns the rejection reason, or `null` when the candidate may
     * proceed to commit.
     */
    private fun authoritativeInLockCheck(
        candidate: SlimAuthoritativeCandidate,
        currentState: SlimSessionState?,
    ): String? {
        val ts = candidate.localAppliedUpdatedAt
        val id = candidate.localAppliedMessageId
        val curTs = currentState?.localAppliedUpdatedAt
        val curId = currentState?.localAppliedMessageId
        // P0-2: empty candidate (null,null) against non-empty watermark → reject.
        if (ts == null && id == null) {
            if (curTs != null || curId != null) {
                return "cannot regress to null watermark (currentState updated=$curTs, id=$curId); " +
                    "candidate carries empty localApplied pair against a non-empty watermark"
            }
            // Empty candidate + empty current → legitimate cold-start of
            // an empty session; allow.
            return null
        }
        // P0-1: monotonic guard. Only run when BOTH sides are legal
        // (non-null) pairs. (Structural validation already rejected
        // split pairs.)
        if (curTs != null && curId != null && ts != null && id != null) {
            val order = compareWatermark(ts, id, curTs, curId)
            if (order < 0) {
                return "non-monotonic localApplied candidate (updated=$ts, id=$id) " +
                    "< in-lock current (updated=$curTs, id=$curId); " +
                    "a concurrent commit advanced the watermark — refuse the regression"
            }
        }
        return null
    }

    // ── §11.2 failure-branch invariant (debug-only assertion) ────────────

    /**
     * Debug aid: on every failure branch, re-read the host's current
     * state and assert it equals the captured pre-commit state (plan
     * §11.2 steps 1 + failure assertions). Disabled in release builds
     * via [SlimAuthoritativeCommitDebugAssertions] so production does not
     * pay the extra reads; unit tests enable them to pin the invariant.
     *
     * The dirty invariant is "dirty did NOT change on a failure branch"
     * (cleared ONLY on the success branch). When there was no prior
     * state (cold-start with no seeded session), oldDirty defaults to
     * false and the post-failure state is also absent / false — that
     * counts as unchanged.
     */
    private fun assertFailureBranchInvariant(
        sessionId: String,
        oldVisibleMessages: List<MessageWithParts>,
        oldLocalAppliedUpdatedAt: Long?,
        oldLocalAppliedMessageId: String?,
        oldDirty: Boolean,
    ) {
        if (!SlimAuthoritativeCommitDebugAssertions.enabled) return
        val currentState = host.captureCurrentState(sessionId)
        val currentVisible = host.captureCurrentVisibleMessages(sessionId)
        val currentDirty = currentState?.dirty ?: oldDirty
        check(currentVisible == oldVisibleMessages) {
            "failure-branch invariant violated: visible content mutated on a non-commit branch (sessionId=$sessionId)"
        }
        check(currentState?.localAppliedUpdatedAt == oldLocalAppliedUpdatedAt) {
            "failure-branch invariant violated: localAppliedUpdatedAt advanced on a non-commit branch (sessionId=$sessionId)"
        }
        check(currentState?.localAppliedMessageId == oldLocalAppliedMessageId) {
            "failure-branch invariant violated: localAppliedMessageId advanced on a non-commit branch (sessionId=$sessionId)"
        }
        check(currentDirty == oldDirty) {
            "failure-branch invariant violated: dirty changed on a non-commit branch (old=$oldDirty, current=$currentDirty, sessionId=$sessionId)"
        }
    }
}

/**
 * §11.2 / §11.1 fix-9 P0-3: debug-assertion toggle for the committer's
 * failure-branch invariant checks.
 *
 * **DEFAULT `false` in production.** The invariant still holds (the
 * committer's logic does not mutate on failure branches), but the
 * runtime `check(...)` calls inside [InternalSlimAuthoritativeCommitter.assertFailureBranchInvariant]
 * can RAISE under legitimate concurrency: a concurrent commit / token
 * rotation / digest arrival between the pre-check snapshot and the
 * post-failure re-read is legal state mutation, NOT a contract
 * violation. Leaving assertions on in production would crash the app
 * on a legal concurrent sequence.
 *
 * Tests that pin the failure-branch invariant explicitly flip this to
 * `true` in their `@Before` setup (see `SlimAuthoritativeCommitTest`).
 */
internal object SlimAuthoritativeCommitDebugAssertions {
    @Volatile
    var enabled: Boolean = false
}
