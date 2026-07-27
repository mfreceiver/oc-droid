package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.SlimSessionDigest
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import java.util.IdentityHashMap

/**
 * Cluster A (slim SSE state machine core): extracted from OpenCodeRepository
 * by T3. Holds the slim incarnation token / bookmark state / readiness bit
 * and every compound state transition under [slimStateLock].
 *
 * All public methods here are 1:1 forwarded from OpenCodeRepository's public
 * slim state API surface. Internal helpers ([withSlimStateCommit],
 * [requireCurrentReconfigureTicket]) are private (state-machine internal).
 *
 * @param slimStateLock the per-repository atomic state boundary (injected;
 *       declared on OpenCodeRepository for freeze §4c binary compat).
 */
class SlimSseStateMachine internal constructor(
    private val slimStateLock: Any,
    private val epochProvider: (() -> Long)? = null,
    private val identityCaptureProvider: (() -> ConnectionIdentityStore.Capture)? = null,
    private val clientBundleProvider: (() -> ClientBundle?)? = null,
) {
    // ── Fields ───────────────────────────────────────────────────────────────────
    /**
     * Per-session bookmark accumulator for [session.digest] frames + the
     * `/since/{ts}` anchor (§5 A2=A). Cleared by [beginSlimReconfigure].
     */
    private val slimSseState = SlimSseState()

    /**
     * Token-to-epoch map: tracks the epoch at which each token was captured.
     * Uses IdentityHashMap so token identity (not equality) is the key.
     * Cleared by [beginSlimReconfigure] so in-flight tokens are rejected.
     */
    private val tokenEpochs = IdentityHashMap<OpenCodeRepository.SlimCommitToken, Long>()

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
            val identityCapture = identityCaptureProvider?.invoke()
            val capturedBundle = clientBundleProvider?.invoke()
            val capturedEpoch = identityCapture?.epoch ?: epochProvider?.invoke()
            val token = OpenCodeRepository.SlimCommitToken(
                marker = slimCommitMarker,
                issuedReady = slimIncarnationReady,
                capturedConnectionIdentity = identityCapture?.identity,
                capturedIdentityEpoch = capturedEpoch,
                capturedClientBundleGeneration = capturedBundle?.generation,
                capturedEndpointFp = capturedBundle?.endpointFp,
                capturedClientBundle = capturedBundle,
            )
            capturedEpoch?.let { tokenEpochs[token] = it }
            token
        }

    /**
     * Operation-entry validation: the token must be from a READY incarnation,
     * still match the current marker, identity epoch, and published client
     * generation, and the current incarnation must still be ready. This
     * rejects superseded markers, host switches, and mid-reconfigure captures.
     */
    fun isSlimCommitTokenCurrent(token: OpenCodeRepository.SlimCommitToken): Boolean =
        synchronized(slimStateLock) {
            isTokenCurrentLocked(token)
        }

    /**
     * The complete operation-entry validation.  The three guards are kept
     * intentionally orthogonal: the slim marker, ConnectionIdentity epoch,
     * and published ClientBundle generation are different lifetimes and must
     * not be substituted for one another.
     */
    private fun isTokenCurrentLocked(token: OpenCodeRepository.SlimCommitToken): Boolean =
        token.issuedReady &&
            slimIncarnationReady &&
            token.marker === slimCommitMarker &&
            isTokenEpochCurrent(token) &&
            isConnectionIdentityCurrent(token) &&
            isClientBundleCurrent(token)

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
        if (!isTokenCurrentLocked(token)) {
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
            tokenEpochs.clear()
            OpenCodeRepository.SlimReconfigureTicket(marker)
        }

    /**
     * Checks that [token] was captured under the current slim epoch (i.e. no
     * host reconfigure has bumped the counter since capture). Returns true
     * when no [epochProvider] is configured (legacy compat path). The
     * independent identity and ClientBundle checks live beside this helper.
     *
     * Provider exists but token unregistered → fail-closed (returns false).
     */
    private fun isTokenEpochCurrent(token: OpenCodeRepository.SlimCommitToken): Boolean {
        val provider = epochProvider ?: return true
        val capturedEpoch = tokenEpochs[token] ?: return false
        return capturedEpoch == provider()
    }

    private fun isConnectionIdentityCurrent(token: OpenCodeRepository.SlimCommitToken): Boolean {
        val provider = identityCaptureProvider ?: return true
        val capturedEpoch = token.capturedIdentityEpoch ?: return false
        val current = provider()
        if (current.epoch != capturedEpoch) return false
        // A null identity is a valid cold-start capture; the epoch still
        // protects it from a host switch. Once an identity exists, require the
        // exact immutable identity captured at operation entry.
        return token.capturedConnectionIdentity == null ||
            current.identity == token.capturedConnectionIdentity
    }

    private fun isClientBundleCurrent(token: OpenCodeRepository.SlimCommitToken): Boolean {
        val provider = clientBundleProvider ?: return true
        val capturedGeneration = token.capturedClientBundleGeneration ?: return true
        val current = provider() ?: return false
        return current.generation == capturedGeneration &&
            current.endpointFp == token.capturedEndpointFp
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

    /**
     * B-P0-3: applies a `message.part.removed` token event to the
     * per-session watermark map. Advances the message's `messageEventSeq`
     * (monotonic; stale re-delivery is a no-op), drops the removed
     * partID from `partRevisions`, and flags `needsFullRecheck = true`
     * so B-P0-1's 100ms-debounced `/full` driver picks it up.
     *
     * Token-checked: a stale incarnation is a no-op (returns null).
     *
     * @return the post-update [MessageWatermark], or null if the token
     *   was stale OR the incoming seq was `<=` the prior local seq
     *   (stale re-delivery — no-op).
     */
    fun applyMessagePartRemoved(
        sessionId: String,
        messageId: String,
        partId: String,
        messageEventSeq: Long,
        token: OpenCodeRepository.SlimCommitToken,
    ): MessageWatermark? = withSlimStateCommit(
        token = token,
        onStale = { null },
    ) {
        slimSseState.watermarksFor(sessionId)
            .applyPartRemoved(messageId, partId, messageEventSeq)
    }

    /**
     * B-P0-3: applies a `message.removed` token event to the per-session
     * watermark map. Removes the entry entirely (no `/full` is triggered
     * — there's nothing to fetch). The UI-layer session-list eviction
     * is B-P0-1's wiring concern.
     *
     * Token-checked: a stale incarnation is a no-op (returns null).
     *
     * @return the removed [MessageWatermark] (or null if none existed /
     *   token stale), so the caller can branch on "we were tracking
     *   this message".
     */
    fun applyMessageRemoved(
        sessionId: String,
        messageId: String,
        token: OpenCodeRepository.SlimCommitToken,
    ): MessageWatermark? = withSlimStateCommit(
        token = token,
        onStale = { null },
    ) {
        slimSseState.watermarksFor(sessionId).removeMessage(messageId)
    }

    /**
     * B-P0-3: applies a per-part `partEventRevision` from a token
     * snapshot / delta frame, for 250ms-debounce-window dedup. Returns
     * `true` iff the revision differs from the previously-applied one
     * (a fresh event); `false` signals a re-delivery the caller SHOULD
     * drop.
     *
     * Token-checked: a stale incarnation returns `true` (fail-open —
     * without applying dedup we cannot drop, so the caller falls back
     * to accept). The watermark state isn't mutated on the stale path.
     */
    fun applyTokenPartRevision(
        sessionId: String,
        messageId: String,
        partId: String,
        partEventRevision: Long?,
        token: OpenCodeRepository.SlimCommitToken,
    ): Boolean = withSlimStateCommit(
        token = token,
        onStale = { true },
    ) {
        slimSseState.watermarksFor(sessionId)
            .applyPartRevision(messageId, partId, partEventRevision)
    }

    /**
     * B-P0-3: snapshot of one session's per-message watermarks. Used
     * by B-P0-1's wiring layer to scan for `needsFullRecheck = true`
     * entries (the `/full` work queue).
     *
     * Acquires [slimStateLock] for a consistent read (no concurrent
     * mutator land grab between the snapshot and the caller's
     * branching).
     */
    fun snapshotSessionWatermarks(sessionId: String): Map<String, MessageWatermark> =
        synchronized(slimStateLock) {
            slimSseState.watermarksFor(sessionId).all()
        }

    /**
     * B-P0-3: server.connected / resync reset. Clears seq state for
     * EVERY session's watermark map, preserves messageIDs, flags every
     * message `needsFullRecheck = true`. The sidecar's seq counter
     * resets to 0 on restart; the prior client-side values are
     * untrustworthy. B-P0-1's R1 reconcile path consumes the returned
     * per-session messageID sets as its work queue.
     *
     * NOT token-checked — this is a system-level reset that MUST apply
     * regardless of in-flight tokens (a reconnect invalidates every
     * outstanding operation). Acquires [slimStateLock] so the reset is
     * atomic w.r.t. any concurrent digest / token-frame mutation.
     *
     * NOTE: distinct from [beginSlimReconfigure] — that's a HOST SWITCH
     * (total wipe via [SlimSseState.clear]); this is a SAME-HOST
     * reconnect (preserve messageIDs for R1).
     *
     * # rev-b-fix M3 — legacy bridge
     *
     * This no-arg overload is the LEGACY path retained until Lane R
     * migrates [SlimFullReconciler]'s `clearWatermarksForReconnect`
     * port signature to take a token. The TOCTOU in the reconciler
     * (`isTokenCurrent(token)` check, then this no-arg reset, with no
     * atomic guard between them) is FIXED only after the migration —
     * the canonical path is the new [clearWatermarksForReconnect]`(
     * token)` overload, which performs the reset INSIDE the
     * `withSlimStateCommit` token guard.
     */
    fun clearWatermarksForReconnect(): Map<String, Set<String>> =
        synchronized(slimStateLock) {
            slimSseState.clearAndMarkAllWatermarksForReconnect()
        }

    /**
     * rev-b-fix M3 (token-guarded reconnect reset): the canonical
     * path. Acquires [slimStateLock] AND validates [token] inside the
     * SAME critical section that runs the reset, closing the
     * TOCTOU window the legacy no-arg overload leaves open.
     *
     * Returns `emptyMap()` on a stale token (the caller MUST treat as
     * stale and short-circuit — no work was done, the watermark maps
     * are untouched). Otherwise returns the per-session work set
     * (Map<sessionId, Set<messageId>>) — the entries that were flagged
     * for R1.
     *
     * # Atomicity
     *
     * The token check + reset are inside one `synchronized(
     * slimStateLock)` block (via [withSlimStateCommit]). A token that
     * was current at capture but went stale (host reconfigure) before
     * this call enters the critical section is rejected at the guard;
     * a token that's current at entry stays current for the duration
     * of the reset (no concurrent rotation can interleave —
     * [beginSlimReconfigure] also acquires [slimStateLock] to rotate
     * the marker, so it serialises against this reset).
     */
    fun clearWatermarksForReconnect(
        token: OpenCodeRepository.SlimCommitToken,
    ): Map<String, Set<String>> = withSlimStateCommit(
        token = token,
        onStale = { emptyMap() },
    ) {
        slimSseState.clearAndMarkAllWatermarksForReconnect()
    }

    /**
     * B-P0-1: clears the per-message `needsFullRecheck` sticky flag for
     * `(sessionId, messageId)` after a successful `/full` reconcile
     * (HTTP 200 with body OR HTTP 304 Not Modified — both signal the
     * client's view of the message is authoritative). Token-checked:
     * a stale incarnation is a no-op (returns false), so a /full result
     * that returns after a host switch cannot mutate the new incarnation's
     * freshly-flagged watermark map.
     *
     * Delegates to [MessageWatermarkState.clearFullRecheckFlag] under
     * [slimStateLock]; the data-layer method is the SOLE writer that
     * clears the flag (B-P0-3 frozen clause).
     *
     * @return `true` iff the flag was actually cleared; `false` on
     *   stale token OR a no-op (absent entry OR flag already clear).
     */
    fun clearFullRecheckFlag(
        sessionId: String,
        messageId: String,
        token: OpenCodeRepository.SlimCommitToken,
    ): Boolean = withSlimStateCommit(
        token = token,
        onStale = { false },
    ) {
        slimSseState.watermarksFor(sessionId).clearFullRecheckFlag(messageId)
    }

    // ── rev-b-fix: atomic /full commit ports (Lane R/O2 contract) ───────────

    /**
     * rev-b-fix §3 — atomic commit of a `/full` 200 OK response.
     *
     * The ENTIRE commit runs inside the slim state machine's token
     * guard (one critical section): validate token → validate seq →
     * invoke [commitUi] → (iff accepted) advance watermark + clear
     * flag. The UI commit (which merges the 200 body into the chat
     * slice /w eagerly-rendered content) is run inside the SAME lock
     * so a concurrent token rotation, digest, or token-frame cannot
     * observe an intermediate state where the UI lambda has run but
     * the watermark hasn't moved (or vice versa).
     *
     * # Rules (frozen, all inside the token guard)
     *
     *  - token stale → `false` (host switch / reconfigure happened
     *    between request and commit). The watermark map is untouched.
     *    [commitUi] is NOT invoked.
     *  - `responseSeq <= 0` → `false` (protocol failure — the sidecar
     *    MUST advertise `X-Message-Event-Seq` as strictly-positive on
     *    a 200; `0` is the uninitialised/untrustworthy sentinel).
     *    The watermark map is untouched. [commitUi] is NOT invoked.
     *  - `responseSeq < currentSeq` → `false` (stale response — a
     *    newer seq was already observed via SSE while this /full was
     *    in flight; the 200 body is for an older state). The
     *    watermark map is untouched; the flag is NOT cleared (the
     *    newer seq will drive a fresh reconcile). [commitUi] is NOT
     *    invoked.
     *  - otherwise → run [commitUi]. The flag clear + seq advance
     *    now DEPEND on [commitUi]'s verdict:
     *    - `commitUi() == true` (reducer accepted the dispatch —
     *      route/bundle CAS passed, content merged into transcript)
     *      → `messageEventSeq = responseSeq`, `needsFullRecheck =
     *      false`, return `true`.
     *    - `commitUi() == false` (reducer rejected — route/bundle
     *      expired, OR route=0 with no active route) → return
     *      `false` WITHOUT mutating the watermark. The flag is
     *      PRESERVED; the seq is NOT advanced. The next digest
     *      sweep (or route reactivation after a route=0 skip)
     *      re-enters with the same seq and retries.
     *
     * The [requestSeq] parameter (the seq observed when the caller
     * issued the /full) is part of the contract for symmetry with
     * [commitFull304] and for caller-side observability, but the
     * 200 commit rule only inspects [responseSeq] vs. the CURRENT seq
     * (the 200 body is authoritative; the responseSeq overwrites
     * whatever the client held).
     *
     * # [commitUi] contract
     *
     * Returns `true` iff the dispatch was accepted — i.e. the
     * reducer's route + bundle CAS passed and the message body was
     * merged into the chat transcript. Returns `false` iff the
     * dispatch was rejected (route/bundle stale, OR the caller
     * short-circuited with route=0 because no active route owns the
     * transcript).
     *
     * MUST be a short, in-memory mutation — no network, no suspension,
     * no blocking I/O. It is invoked under [slimStateLock]; long-running
     * work would block every other slim state operation. The typical
     * body is `slice.mergeAuthoritative(message)` or a dispatch of an
     * AppAction that the reducer applies synchronously.
     *
     * # rev-ogpt #2 — why the flag clear now depends on [commitUi]
     *
     * Previously, [commitUi] returned `Unit` and the flag was cleared
     * BEFORE the dispatch ran. If the reducer's route/bundle CAS
     * rejected the dispatch, the watermark was already marked done
     * and the message was silently dropped (no retry). The Boolean
     * return makes the CAS verdict observable so this method can
     * preserve the flag on rejection. route=0 (no active route) is
     * the canonical rejection: the reducer would skip the transcript
     * write anyway, so the caller's [commitUi] lambda returns `false`
     * WITHOUT dispatching — preserving the flag for the next sweep
     * once a route is activated.
     *
     * @return `true` iff the commit landed (token current + valid
     *   responseSeq + seq not regressed + [commitUi] accepted);
     *   `false` otherwise. The caller (Lane R/O2 — SlimFullReconciler
     *   / SlimSyncEngine) MUST short-circuit on `false` and treat the
     *   message as still needing reconcile.
     */
    fun commitFull200(
        sessionId: String,
        messageId: String,
        @Suppress("UNUSED_PARAMETER") requestSeq: Long,
        responseSeq: Long,
        token: OpenCodeRepository.SlimCommitToken,
        commitUi: () -> Boolean,
    ): Boolean = withSlimStateCommit(
        token = token,
        onStale = { false },
    ) {
        val wms = slimSseState.watermarksFor(sessionId)
        // Step 1: read-only seq validation (fail-fast on protocol
        // failure / stale responseSeq). Does NOT mutate the map;
        // mutation only runs after the UI commit accepts.
        if (!wms.canCommitFull200Seq(messageId, responseSeq)) {
            return@withSlimStateCommit false
        }
        // Step 2: run the UI commit. Its verdict decides whether the
        // watermark moves. The lambda runs under slimStateLock so the
        // reducer's CAS sees a state consistent with our seq check.
        val accepted = commitUi()
        if (!accepted) {
            // UI rejected (route/bundle CAS fail OR route=0 no active
            // route). Preserve flag + do NOT advance seq. Return false
            // so the reconciler reports Skipped (not Failure) and the
            // next digest sweep / route reactivation retries.
            return@withSlimStateCommit false
        }
        // Step 3: UI accepted — advance seq + clear flag atomically.
        // canCommitFull200Seq pre-validated responseSeq; commitFull200Seq
        // re-validates (defensively) and mutates.
        wms.commitFull200Seq(messageId, responseSeq)
        true
    }

    /**
     * rev-b-fix §4 — conditional commit of a `/full` 304 Not Modified.
     *
     * Clears the per-message `needsFullRecheck` flag IFF (a) the
     * token is current AND (b) the message's current `messageEventSeq`
     * EXACTLY matches [requestSeq] (the seq the caller observed at
     * request time). Both checks run inside one [slimStateLock]
     * critical section (via [withSlimStateCommit]).
     *
     * # Why exact-equality on [requestSeq]
     *
     * A 304 means "your fingerprint is authoritative". The fingerprint
     * included `known.messageEventSeq = requestSeq`, so the sidecar
     * confirmed that seq AT REQUEST TIME. If the local seq has since
     * ADVANCED (a `message.part.*` SSE event arrived between request
     * and 304), the client has new information the 304 didn't account
     * for — clearing the flag would discard a real recovery signal.
     * Keep the flag; the next sweep re-fetches against the new seq.
     *
     * # Returns
     *
     *  - `true` iff the flag was cleared (token current + seq matched
     *    + flag was set). The caller treats the message as
     *    authoritative (no further work this sweep).
     *  - `false` otherwise. On the `seq advanced` branch the flag is
     *    INTENTIONALLY preserved (the caller MUST NOT treat the
     *    message as reconciled — the new seq will drive a fresh
     *    /full next sweep).
     */
    fun commitFull304(
        sessionId: String,
        messageId: String,
        requestSeq: Long,
        token: OpenCodeRepository.SlimCommitToken,
    ): Boolean = withSlimStateCommit(
        token = token,
        onStale = { false },
    ) {
        slimSseState.watermarksFor(sessionId)
            .clearFlagIfSeqMatches(messageId, requestSeq)
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
    /**
     * §11.1 fix-6 P0-5: returns ONLY [SlimSessionState.localAppliedUpdatedAt]
     * — the `/since/{ts}` anchor. MUST NOT fall back to `remoteUpdatedAt`
     * (the digest-driven watermark). When localApplied is missing, the
     * caller MUST fall back to the full/cursor drain, NOT skip messages.
     */
    fun readBookmarkOrThrowIfStale(
        sid: String,
        token: OpenCodeRepository.SlimCommitToken,
    ): Long? = synchronized(slimStateLock) {
        if (!isTokenCurrentLocked(token)) {
            throw OpenCodeRepository.StaleSlimCommitException()
        }
        slimSseState.get(sid)?.localAppliedUpdatedAt
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
     * §11.1 fix-10 P1-1: UNCONDITIONALLY set `dirty = true` for [sessionId],
     * bypassing the [needsReconcile] gate that [markSlimDirty] uses.
     *
     * # rev-ogpt P1-1 / P1-2 (seven rounds → fix-13) — production callers
     *
     * Production: called by the cache-retention failure path in
     * [SlimSessionReconciler] (when the post-REST retention guard rejects
     * the merged set, the reconciler must force the session dirty
     * unconditionally so a later reconcile re-fetches — [markSlimDirty]
     * would no-op here because the post-reconcile watermark is aligned).
     * The conflict hot path (engine `drainAndCommitAuthoritative` +
     * reconciler `foldRestFetch` same-tuple-different-parts) NO LONGER
     * calls this method: the conflict's dirty decision is ATOMIC with the
     * commit via [replaceLocalAppliedAndClearDirtyLocked]'s `hasConflict`
     * parameter (carried from [SlimAuthoritativeCandidate.hasConflict], and
     * from the committer's in-lock per-ID conflict-aware merge — fix-13).
     * Diagnostic / test paths also retain this method as an unconditional
     * dirty ratchet.
     *
     * The prior post-commit `forceSlimDirty` had two issues on the conflict
     * hot path that the atomic decision closes:
     *
     *  - P1-1 (reconciler conflict path): the prior reconciler code called
     *    `markDirty` (gated by [needsReconcile]) which was a NO-OP for
     *    same-tuple conflicts (needsReconcile returns false on an aligned
     *    watermark).
     *  - P1-2 (engine conflict path): the prior engine code called
     *    `forceSlimDirty` here in a SEPARATE critical section after the
     *    commit, leaving a window where `dirty = false` against a divergent
     *    authoritative set.
     *
     * Returns false (no-op) when the session has no state or token is stale.
     */
    fun forceSlimDirty(
        sessionId: String,
        token: OpenCodeRepository.SlimCommitToken,
    ): Boolean = withSlimStateCommit(
        token = token,
        onStale = { false },
    ) {
        val prev = slimSseState.get(sessionId) ?: return@withSlimStateCommit false
        slimSseState.put(sessionId, prev.copy(dirty = true))
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

    /**
     * §11.2 fix-4 host contract ([SlimAuthoritativeCommitHost.replaceLocalAppliedAndClearDirty]):
     * write [localAppliedUpdatedAt] / [localAppliedMessageId] VERBATIM onto the
     * [SlimSessionState] for [sessionId] + decide `dirty` ATOMICALLY, inside
     * the caller's existing [commitIfSlimTokenCurrent] critical section.
     *
     * §11.1 fix-8 P1-4 + fix-9 P0-4 + rev-ogpt P1-1 / P1-2 — ATOMIC dirty
     * decision. The commit decides `dirty` from `hasConflict` AND the
     * post-write state INSIDE this same critical section that wrote
     * localApplied*:
     *  - `hasConflict = true` ⇒ `dirty = true` UNCONDITIONALLY (covers
     *    BOTH the candidate-merge-flagged same-tuple-different-parts
     *    divergence AND the in-lock same-tuple content conflict the
     *    committer detected against a concurrent candidate — rev-ogpt
     *    P1-1 six rounds).
     *  - else if `needsReconcile` holds against the post-write state
     *    (`remote > localApplied` via [compareWatermark], P0-4 TOCTOU)
     *    ⇒ `dirty = true`.
     *  - else ⇒ `dirty = false` (normal happy-path clear).
     *
     * The prior fix-8 P1-4 implementation cleared `dirty` UNCONDITIONALLY
     * and relied on the next digest to re-evaluate via [needsReconcile]
     * (the reducer was the SOLE writer of `dirty=true`). That opened the
     * P1-1 hole (the reconciler-path `markDirty` was a no-op for same-
     * tuple conflicts because [needsReconcile] returns false on an
     * aligned watermark) and the P1-2 atomicity window (the engine-path
     * `forceSlimDirty` ran in a SEPARATE critical section after the
     * commit, leaving a window where `dirty=false` against a divergent
     * authoritative set). Carrying `hasConflict` into THIS critical
     * section closes both — no separate `forceSlimDirty` / `markDirty`
     * post-commit is needed on the **conflict hot paths** (engine +
     * reconciler foldRestFetch). The cache-retention failure production
     * path in [SlimSessionReconciler] (post-REST retention guard rejects
     * the merged set, which happens AFTER the commit protocol has
     * finished and therefore cannot ride its atomic `hasConflict`
     * decision) still uses [forceSlimDirty] to unconditionally ratchet
     * `dirty=true`.
     *
     * # Why verbatim (not derived)
     *
     * The committer trusts the candidate's tuple and writes it verbatim — it
     * does NOT re-derive from the message list (plan §11.2, test
     * `successfulCommitUsesCandidateLocalAppliedTupleNotDerivedFromMessages`).
     * [bumpSlimBookmarkFromItems] DERIVES the tuple from items via
     * [onReconcileSuccess]; this method writes the PASSED tuple directly.
     * The two diverge in the empty-items edge case (where derivation keeps the
     * prior tuple but the candidate may legitimately carry `(null, null)`).
     *
     * # NOT token-checked
     *
     * The caller (the host's [SlimAuthoritativeCommitHost.commitIfCurrent] block)
     * already validated the token inside the lock. Reentrant on [slimStateLock]
     * (Java/Kotlin `synchronized` is reentrant, so the inner `synchronized` in
     * the read-modify-write is a no-op re-entry).
     *
     * MUST NOT touch `remote*` — those are advanced only by the digest reducer
     * (plan invariant #1).
     *
     * # §11.1 fix-9 P0-4 + rev-ogpt P1-1 / P1-2 — atomic dirty decision
     *
     * The commit's dirty decision is ATOMIC with the localApplied write.
     * [hasConflict] is an input to this decision (carried from the merge
     * via [SlimAuthoritativeCandidate.hasConflict]):
     *
     *  - `hasConflict = true` ⇒ `dirty = true` UNCONDITIONALLY. The
     *    candidate's merge detected a same-tuple-different-parts divergence;
     *    the watermark is aligned (remote <= localApplied) but the
     *    authoritative parts are stale. Forcing dirty=true here closes the
     *    P1-1 hole (the prior reconciler-path `markDirty` was a no-op
     *    because `needsReconcile` returns false on an aligned watermark)
     *    AND the P1-2 atomicity window (the prior engine-path
     *    `forceSlimDirty` ran in a SEPARATE critical section after the
     *    commit, leaving a window where dirty=false against a divergent
     *    authoritative set).
     *  - `hasConflict = false` ⇒ re-evaluate [needsReconcile] against the
     *    post-write state. If remote is still ahead (strictly greater than
     *    candidate's tuple via [compareWatermark]), dirty is RE-SET to
     *    `true` — the session still needs reconcile (P0-4: TOCTOU
     *    mitigation for a digest arriving mid-drain and advancing remote).
     *    Otherwise dirty clears to `false` (normal happy path).
     *
     * `dirty=true` writers: (a) the committer (this method) — when
     * `hasConflict=true` (candidate's own merge OR the in-lock per-ID
     * conflict-aware merge — fix-13), OR when `remote > localApplied`
     * post-write (P0-4 TOCTOU); (b) the digest reducer via
     * [needsReconcile]; (c) [forceSlimDirty] (production: cache-retention
     * failure path in [SlimSessionReconciler]; diagnostic / test). The
     * commit-time atomic decision is the authoritative bridge between
     * "drain captured a snapshot at time T" and "remote may have advanced
     * past T / parts may have diverged by the time the commit lands".
     */
    internal fun replaceLocalAppliedAndClearDirtyLocked(
        sessionId: String,
        localAppliedUpdatedAt: Long?,
        localAppliedMessageId: String?,
        hasConflict: Boolean,
    ) = synchronized(slimStateLock) {
        val prev = slimSseState.get(sessionId) ?: SlimSessionState(sessionId)
        // §11.1 fix-9 P0-4 + rev-ogpt P1-1/P1-2: write candidate tuple,
        // then decide dirty ATOMICALLY from hasConflict + post-write state.
        // needsReconcile reads remote* vs localApplied*; if remote >
        // localApplied, dirty MUST stay true (the server has observed
        // activity beyond what we just applied). hasConflict=true forces
        // dirty=true unconditionally (same-tuple-different-parts ⇒ the
        // watermark is aligned but the authoritative parts are stale).
        val candidate = prev.copy(
            localAppliedUpdatedAt = localAppliedUpdatedAt,
            localAppliedMessageId = localAppliedMessageId,
            dirty = false,
        )
        val next = when {
            hasConflict -> candidate.copy(dirty = true)
            needsReconcile(candidate) -> candidate.copy(dirty = true)
            else -> candidate
        }
        slimSseState.put(sessionId, next)
    }
}
