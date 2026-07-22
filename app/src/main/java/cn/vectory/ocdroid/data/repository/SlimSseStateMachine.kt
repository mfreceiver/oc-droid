package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.SlimSessionDigest
import cn.vectory.ocdroid.data.model.MessageWithParts

/**
 * Cluster A (slim SSE state machine core): extracted from OpenCodeRepository
 * by T3. Holds the slim incarnation token / bookmark state / readiness bit
 * and every compound state transition under [slimStateLock].
 *
 * All public methods here are 1:1 forwarded from OpenCodeRepository's public
 * slim state API surface. Internal helpers ([withSlimStateCommit],
 * [requireCurrentReconfigureTicket]) are package-private for reducer access.
 *
 * @param slimStateLock the per-repository atomic state boundary (injected;
 *       declared on OpenCodeRepository for freeze §4c binary compat).
 */
class SlimSseStateMachine(
    private val slimStateLock: Any,
) {
    // ── Fields ───────────────────────────────────────────────────────────────────
    /**
     * Per-session bookmark accumulator for [session.digest] frames + the
     * `/since/{ts}` anchor (§5 A2=A). Cleared by [beginSlimReconfigure].
     */
    private val slimSseState = SlimSseState()

    /**
     * Rotated under [slimStateLock] by [beginSlimReconfigure] (and at the
     * start of [configure] as defense-in-depth). Same critical section
     * clears slimSseState so in-flight workflows carrying the previous
     * marker are rejected from that instant onward.
     */
    // GuardedBy("slimStateLock") — documentary; rotated in beginSlimReconfigure().
    private var slimCommitMarker: Any = Any()

    /**
     * C-D3 rev-3 readiness bit: false while a reconfigure transaction is
     * in flight (between [beginSlimReconfigure] and a successful [configure]
     * completion). Tokens captured while false carry [SlimCommitToken.issuedReady]
     * = false permanently, closing the mid-transaction capture window where a
     * marker-only check would accept a token captured during host mutation.
     */
    // GuardedBy("slimStateLock") — documentary.
    private var slimIncarnationReady: Boolean = true

    // ── Public state API (forwarders from OpenCodeRepository) ───────────────

    /**
     * Captures the current incarnation marker + readiness into an opaque
     * token for later comparison / commit. See [SlimCommitToken] kdoc.
     */
    fun captureSlimCommitToken(): OpenCodeRepository.SlimCommitToken =
        synchronized(slimStateLock) {
            OpenCodeRepository.SlimCommitToken(
                marker = slimCommitMarker,
                issuedReady = slimIncarnationReady,
            )
        }

    /**
     * Three-condition check: the token must (1) have been captured from
     * a READY incarnation, (2) still match the current marker, and (3)
     * the current incarnation must still be ready. This rejects both
     * superseded markers AND mid-reconfigure captures.
     */
    fun isSlimCommitTokenCurrent(token: OpenCodeRepository.SlimCommitToken): Boolean =
        synchronized(slimStateLock) {
            token.issuedReady &&
                slimIncarnationReady &&
                token.marker === slimCommitMarker
        }

    /**
     * C-D3 v2 §1.2: Runs a short, non-suspending commit atomically against
     * the current incarnation. The [commit] block MUST contain only in-memory
     * state/effect commits: no network, delay, blocking disk I/O, or suspend call.
     *
     * @param onStale invoked when [token] is stale (the caller MUST short-circuit).
     * @param commit the actual mutation block (runs iff token is current).
     * @return the value returned by [onStale] or [commit].
     */
    private inline fun <T> withSlimStateCommit(
        token: OpenCodeRepository.SlimCommitToken,
        onStale: () -> T,
        commit: () -> T,
    ): T = synchronized(slimStateLock) {
        if (!token.issuedReady ||
            !slimIncarnationReady ||
            token.marker !== slimCommitMarker
        ) {
            onStale()
        } else {
            commit()
        }
    }

    /**
     * C-D3 v2 §1.2: Runs [commit] atomically iff [token] is current.
     * Returns `true` when [commit] ran, `false` when the marker rotated first
     * (the caller MUST treat as stale and short-circuit).
     */
    fun commitIfSlimTokenCurrent(
        token: OpenCodeRepository.SlimCommitToken,
        commit: () -> Unit,
    ): Boolean = withSlimStateCommit(
        token = token,
        onStale = { false },
    ) {
        commit()
        true
    }

    /**
     * C-D3 v2 §1.2: Throws [OpenCodeRepository.StaleSlimCommitException] if
     * [token] is no longer the current repository incarnation. Used after every
     * network suspension (the marker may rotate while we were suspended on IO).
     */
    fun requireSlimTokenCurrent(token: OpenCodeRepository.SlimCommitToken) {
        if (!isSlimCommitTokenCurrent(token)) {
            throw OpenCodeRepository.StaleSlimCommitException()
        }
    }

    /**
     * C-D3 rev-3 reconfigure-boundary: SYNCHRONOUSLY invalidates the slim
     * repository incarnation (rotate marker + clear slim SSE state) under
     * [slimStateLock].
     *
     * @return a [OpenCodeRepository.SlimReconfigureTicket] identifying this
     *   transaction's not-yet-ready incarnation.
     */
    fun beginSlimReconfigure(): OpenCodeRepository.SlimReconfigureTicket =
        synchronized(slimStateLock) {
            val marker = Any()
            slimCommitMarker = marker
            slimIncarnationReady = false
            slimSseState.clear()
            OpenCodeRepository.SlimReconfigureTicket(marker)
        }

    /**
     * C-D3 rev-3 round-5 (oracle §1.4): asserts [ticket] still identifies
     * the current slim reconfigure transaction. Called by [configure] BEFORE
     * any host mutation so a stale/superseded ticket can't mutate state under
     * a wrong incarnation.
     *
     * Throws [OpenCodeRepository.SupersededSlimReconfigureException] if
     * [ticket] was superseded by a later [beginSlimReconfigure]; never
     * re-arms a new transaction.
     */
    fun requireCurrentReconfigureTicket(ticket: OpenCodeRepository.SlimReconfigureTicket) {
        synchronized(slimStateLock) {
            if (ticket.marker !== slimCommitMarker) {
                throw OpenCodeRepository.SupersededSlimReconfigureException()
            }
            // The "already completed" branch is a programming error (calling
            // configure twice with the same ticket) — keep it as ISE so it
            // surfaces loudly in dev.
            check(!slimIncarnationReady) {
                "Slim reconfigure transaction already completed"
            }
        }
    }

    /**
     * C-D3 rev-3 readiness bit: re-arm [slimIncarnationReady] after a
     * successful [configure]. Called ONLY at the end of a fully-successful
     * configure transaction.
     *
     * C-D3 rev-3 round-5 (oracle §1.4): ticket-ownership — only the ticket
     * that BEGAN the transaction can complete it. A superseded ticket
     * throws [OpenCodeRepository.SupersededSlimReconfigureException] and NEVER
     * re-arms readiness.
     */
    fun completeSlimReconfigure(ticket: OpenCodeRepository.SlimReconfigureTicket) {
        synchronized(slimStateLock) {
            if (ticket.marker !== slimCommitMarker) {
                throw OpenCodeRepository.SupersededSlimReconfigureException()
            }
            slimIncarnationReady = true
        }
    }

    // ── Slim digest / reconcile state mutations ────────────────────────────────

    /**
     * Applies a [SlimSessionDigest] to the in-memory [slimSseState] under
     * [slimStateLock]. Returns a [SlimFetchMessages] if the digest indicates
     * newer activity that needs fetching, else null.
     *
     * The [token] is checked before mutation — a stale incarnation rejects.
     */
    fun applySlimDigest(
        digest: SlimSessionDigest,
        token: OpenCodeRepository.SlimCommitToken,
    ): SlimFetchMessages? {
        val parsed = digest.takeIf { it.sessionId.isNotBlank() } ?: return null
        return withSlimStateCommit(
            token = token,
            onStale = { null },
        ) {
            reduceSlimDigest(slimSseState, parsed)
        }
    }

    /**
     * Snapshot the per-session slim SSE state (testing + upper layer queries).
     * Returns a defensive copy.
     *
     * T11 round-2 (oracle I3): acquires [slimStateLock] for a consistent
     * read (no concurrent mutator land grab between the snapshot and the
     * caller's branching on the snapshot).
     */
    fun snapshotSlimSseState(): Map<String, SlimSessionState> = synchronized(slimStateLock) {
        slimSseState.all()
    }

    /**
     * Reads the per-session slim SSE state for [sessionId].
     * Returns null when the session has no state (cold path).
     *
     * Pure read — no mutation. T11 round-2 (oracle I3): acquires
     * [slimStateLock] so the returned state is consistent with the latest
     * commit.
     */
    fun getSlimSessionState(sessionId: String): SlimSessionState? =
        synchronized(slimStateLock) {
            slimSseState.get(sessionId)
        }

    /**
     * Cold-start path (coldStartSlimSync): atomically read the per-session
     * `updatedAt` bookmark iff the incarnation marker is still current. A
     * rotated marker throws [OpenCodeRepository.StaleSlimCommitException]
     * (rethrows out of the message fetch — NOT collapses to null, which would
     * mask a host rotation as "server unreachable"; see OpenCodeRepository
     * coldStartSlimSync comment). Mirrors the pre-T3
     * `synchronized(slimStateLock){ if(token.marker !== slimCommitMarker) throw; get }`
     * block 1:1 (marker-only check, single critical section with the read).
     */
    fun readBookmarkOrThrowIfStale(
        sid: String,
        token: OpenCodeRepository.SlimCommitToken,
    ): Long? = synchronized(slimStateLock) {
        if (token.marker !== slimCommitMarker) {
            throw OpenCodeRepository.StaleSlimCommitException()
        }
        slimSseState.get(sid)?.updatedAt
    }

    /**
     * Marks the session as deleted upstream (the reconcile probe returned
     * HTTP 404). Applies T6's pure [markDeleted] primitive.
     *
     * Returns false (and does NOT mark) when the token is stale.
     */
    fun markSlimSessionDeleted(
        sessionId: String,
        token: OpenCodeRepository.SlimCommitToken,
    ): Boolean = withSlimStateCommit(
        token = token,
        onStale = { false },
    ) {
        val prev = slimSseState.get(sessionId) ?: SlimSessionState(sessionId)
        slimSseState.put(sessionId, markDeleted(prev))
        true
    }

    /**
     * Clears the local-applied message cache watermark for [sessionId] AND
     * clears `dirty`. Used when the reconcile probe returned an EMPTY array
     * (the session exists upstream but has no messages) AND the local cache
     * had messages for it.
     *
     * Chains T6's two pure primitives: [clearLocal] then [onReconcileSuccess]
     * with empty items, then re-evaluates [needsReconcile] for dirty ratchet.
     *
     * Returns false when the token is stale.
     */
    fun clearSlimLocalMessages(
        sessionId: String,
        token: OpenCodeRepository.SlimCommitToken,
    ): Boolean = withSlimStateCommit(
        token = token,
        onStale = { false },
    ) {
        val prev = slimSseState.get(sessionId) ?: SlimSessionState(sessionId)
        val cleared = clearLocal(prev)
        val applied = onReconcileSuccess(cleared, emptyList())
        val next = if (needsReconcile(applied)) applied.copy(dirty = true) else applied
        slimSseState.put(sessionId, next)
        true
    }

    /**
     * Records that a reconcile attempt FAILED for [sessionId] (transport error,
     * 5xx, timeout). Applies T6's pure [onReconcileFailure] primitive —
     * preserves `dirty` (the session still needs reconcile) and does NOT
     * advance local-applied.
     *
     * Returns false when the token is stale.
     */
    fun markSlimReconcileFailure(
        sessionId: String,
        token: OpenCodeRepository.SlimCommitToken,
    ): Boolean = withSlimStateCommit(
        token = token,
        onStale = { false },
    ) {
        val prev = slimSseState.get(sessionId) ?: SlimSessionState(sessionId)
        slimSseState.put(sessionId, onReconcileFailure(prev))
        true
    }

    /**
     * Records that a session is ALIGNED — the reconcile probe confirmed
     * there's nothing to fetch. Clears `dirty` without advancing
     * local-applied (no new info to apply, but reconcile did succeed).
     *
     * Applies T6's [onReconcileSuccess]`(state, emptyList)` — the
     * explicit "clear dirty, no localApplied advance" path. Re-evaluates
     * [needsReconcile] for dirty ratchet.
     *
     * Returns false when the token is stale.
     */
    fun markSlimReconcileAligned(
        sessionId: String,
        token: OpenCodeRepository.SlimCommitToken,
    ): Boolean = withSlimStateCommit(
        token = token,
        onStale = { false },
    ) {
        val prev = slimSseState.get(sessionId) ?: SlimSessionState(sessionId)
        val applied = onReconcileSuccess(prev, emptyList())
        val next = if (needsReconcile(applied)) applied.copy(dirty = true) else applied
        slimSseState.put(sessionId, next)
        true
    }

    /**
     * Invalidates the per-session local-applied watermark when the
     * corresponding in-memory [CachedSessionWindow] is evicted.
     *
     * Sets `localAppliedMessageId = null`, `localAppliedUpdatedAt = null`.
     * Does NOT touch `remote*` or `dirty`.
     *
     * Returns false (no-op) when the session has no state or token is stale.
     */
    fun invalidateSlimLocalApplied(
        sessionId: String,
        token: OpenCodeRepository.SlimCommitToken,
    ): Boolean = withSlimStateCommit(
        token = token,
        onStale = { false },
    ) {
        val prev = slimSseState.get(sessionId) ?: return@withSlimStateCommit false
        val cleared = prev.copy(
            localAppliedMessageId = null,
            localAppliedUpdatedAt = null,
        )
        val next = if (needsReconcile(cleared)) cleared.copy(dirty = true) else cleared
        slimSseState.put(sessionId, next)
        true
    }

    /**
     * Explicitly marks the session's `dirty = true` (with [needsReconcile]
     * re-eval to avoid setting dirty on a truly-aligned state).
     *
     * Returns false (no-op) when the session has no state or token is stale.
     */
    fun markSlimDirty(
        sessionId: String,
        token: OpenCodeRepository.SlimCommitToken,
    ): Boolean = withSlimStateCommit(
        token = token,
        onStale = { false },
    ) {
        val prev = slimSseState.get(sessionId) ?: return@withSlimStateCommit false
        val next = if (needsReconcile(prev)) prev.copy(dirty = true) else prev
        slimSseState.put(sessionId, next)
        true
    }

    /**
     * Bumps the slim SSE bookmark for [sessionId] from the max
     * `time.updated` over [items]. Applies [onReconcileSuccess] and then
     * re-evaluates [needsReconcile] for dirty ratchet.
     *
     * Returns false when the token is stale (caller should throw
     * [StaleSlimCommitException]).
     */
    fun bumpSlimBookmarkFromItems(
        sessionId: String,
        items: List<MessageWithParts>,
        token: OpenCodeRepository.SlimCommitToken,
    ): Boolean = withSlimStateCommit(
        token = token,
        onStale = { false },
    ) {
        val prev = slimSseState.get(sessionId) ?: SlimSessionState(sessionId)
        val applied = onReconcileSuccess(prev, items)
        val next =
            if (needsReconcile(applied)) applied.copy(dirty = true)
            else applied
        slimSseState.put(sessionId, next)
        true
    }
}
