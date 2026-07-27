package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.ui.BundleStamp
import cn.vectory.ocdroid.util.DebugLog
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import retrofit2.Response

/**
 * B-P0-1 (R1+R2 recovery strategy): the R1 bounded `/full` reconcile
 * coordinator. Consumes the per-message `needsFullRecheck = true` flags
 * (set by B-P0-3's [MessageWatermarkState] via the digest's
 * `contentRevisions` / `message.part.removed` / `clearWatermarksForReconnect`)
 * and drives one `/full?known=` per affected message, applying the R2
 * fingerprint optimization, fetch-storm backoff, and bounded
 * active-message set.
 *
 * # Ports-only design
 *
 * Every dependency is injected as a function-typed port so this class
 * is unit-testable in isolation (no MockWebServer, no SlimSseStateMachine,
 * no OpenCodeRepository). The production adapter (ControllerModule,
 * Lane I) wires it to the live OCR + state machine + store; tests wire
 * it to fakes. This keeps the data/repository layer decoupled from the
 * concrete repository class (matching the existing
 * [SlimAuthoritativeCommitter] / [ExpandBatchEngine] port-injection style).
 *
 * # rev-b-fix — atomic /full commit protocol (C4 + M3/M4/M6)
 *
 * The 200 / 304 commit now runs through the Lane W atomic commit ports
 * ([commitFull200] / [commitFull304]) so the seq write + flag clear + UI
 * dispatch land in ONE token-guarded critical section (C4). The reconnect
 * reset is token-guarded (M3 — [clearWatermarksForReconnect]`(token)`).
 * All three entry points share a per-session [Mutex] (M4 — single-flight,
 * no AlreadyInFlight request drop). [CancellationException] propagates
 * verbatim through the fetch / backoff catch chain (M6), and the injected
 * [requireTokenCurrent] guard runs after every network suspension +
 * every backoff sleep.
 *
 * # FullReconcileContext (route + bundle guard)
 *
 * Every reconcile entry point carries a [FullReconcileContext] captured at
 * the trigger (the caller reads the live route instance + bundle stamp
 * BEFORE issuing the request). The context threads unchanged across the
 * entire fetch; the commitUi lambda dispatches [SlimFullMessageReconciled]
 * with this context so the reducer's freshness CAS can reject stale
 * dispatches. A context with `expectedRouteInstance == 0L` (no active
 * route) advances the watermark but skips the transcript dispatch (the
 * reducer would reject route=0 anyway).
 *
 * # Bounded active set (R1 §活跃消息)
 *
 * [reconcileActiveSession] scans the watermark map for the session,
 * filters to `needsFullRecheck = true`, sorts by `updatedAt` DESC (the
 * sort key is provided by [messageUpdatedAt] — typically the
 * [MessageWithParts.info.time.updated] from the chat slice / cache),
 * takes the top [DEFAULT_MAX_ACTIVE] (N=50, frozen), and reconciles
 * each via R2. Older stale messages stay flagged — they will be
 * picked up by a future sweep (the LRU cap of 500 in
 * [MessageWatermarkState] bounds the work set).
 *
 * # Per-session single-flight (M4 — fetch-storm guard §单次 resync 最多 1 次 batch)
 *
 * A per-session [Mutex] (from [inFlightMutex]) serialises EVERY reconcile
 * entry point for a given session: [reconcileMessage] (single),
 * [reconcileActiveSession] (batch), and [reconcileReconnect]'s per-session
 * worker. Concurrent invocations SUSPEND (they don't short-circuit with
 * AlreadyInFlight — that dropped requests and lost flags). Because callers
 * suspend rather than drop, flags set during an in-flight batch are
 * automatically picked up by the next caller that acquires the mutex
 * (automatic trailing-dirty).
 *
 * # R2 /full fingerprint
 *
 * Each per-message fetch supplies `known.maxPartId` + `known.partCount`
 * + `known.messageEventSeq` when the caller has them (from
 * [messageMaxPartId] / [messagePartCount] / the watermark's
 * [MessageWatermark.messageEventSeq]). A 304 Not Modified clears the
 * flag WITHOUT advancing the seq (the watermark is already
 * authoritative) — but ONLY if the local seq still matches the request
 * seq (no newer activity arrived during the network window). A 200
 * clears the flag AND advances the seq from the `X-Message-Event-Seq`
 * header — atomically, inside [commitFull200].
 *
 * # Backoff (exponential, full jitter)
 *
 * On HTTP 429 / 503 / 413 / transport timeout, the per-message
 * reconcile backs off via [computeBackoffMs] (full-jitter exponential,
 * base=1s, cap=30s) — the sidecar's 429 Retry-After is honoured
 * (capped at 10s, mirroring OCR's `retryAfterHeaderToMs`). On a 429
 * the per-message attempt is retried up to [MAX_ATTEMPTS_PER_MESSAGE]
 * (3) times; on exhaustion the message stays flagged (next sweep
 * retries). Other failures (transport / 5xx) DO NOT retry inside this
 * call — the flag stays set and the next digest debounce / resync
 * sweep will re-attempt.
 *
 * # Reconnect R1 batch
 *
 * [reconcileReconnect] is the server.connected / resync entry: it
 * calls [clearWatermarksForReconnect]`(token)` (M3 — token-guarded,
 * TOCTOU-safe) to get the per-session work set
 * (Map<sessionId, Set<messageId>>), then fans out per-message R2
 * reconciles under a bounded concurrency semaphore
 * ([DEFAULT_RECONNECT_CONCURRENCY] = 8, frozen) so the sidecar is not
 * stormed on reconnect. Each session's work acquires that session's
 * [inFlightMutex] (M4 — serialises with any concurrent active-session
 * batch).
 */
class SlimFullReconciler(
    /** Captures a fresh slim commit token (operation-entry validation). */
    private val tokenProvider: () -> OpenCodeRepository.SlimCommitToken,
    /** Returns true iff the token is still the current incarnation. */
    private val isTokenCurrent: (OpenCodeRepository.SlimCommitToken) -> Boolean,
    /**
     * Throws [OpenCodeRepository.StaleSlimCommitException] if the token is
     * stale. Called after every network suspension + backoff sleep (M6 —
     * strong guard, not a nullable boolean check).
     */
    private val requireTokenCurrent: (OpenCodeRepository.SlimCommitToken) -> Unit,
    /**
     * Executes the R2 `/full?known=` fetch. Returns the raw Retrofit
     * [Response] so the caller can branch on 304 / read
     * `X-Message-Event-Seq` / `Retry-After`.
     */
    private val fetchFull: suspend (
        sessionId: String, messageId: String,
        knownMaxPartId: String?, knownPartCount: Int?, knownMessageEventSeq: Long?,
    ) -> Response<MessageWithParts>,
    /** Parses the `X-Message-Event-Seq` response header (null if absent / invalid). */
    private val parseSeqHeader: (Response<*>) -> Long?,
    /**
     * Parses the `Retry-After` response header into ms (already capped
     * by the caller — typically 0..10_000). 0 when absent.
     */
    private val parseRetryAfterMs: (Response<*>) -> Long,
    /** Snapshots one session's per-message watermark map. */
    private val snapshotSessionWatermarks: (sessionId: String) -> Map<String, MessageWatermark>,
    /**
     * rev-b-fix §3 (C4 — Lane W port): atomic commit of a `/full` 200 OK.
     *
     * Validates the token, validates [responseSeq] (strictly positive,
     * not older than the current local seq), invokes [commitUi] inside
     * the SAME slim-state-lock critical section, AND — iff [commitUi]
     * returned `true` — advances the watermark's `messageEventSeq` to
     * [responseSeq] + clears the `needsFullRecheck` flag. The UI merge
     * therefore lands in the SAME atomic window as the watermark
     * mutation.
     *
     * # rev-ogpt #2 — [commitUi] returns Boolean
     *
     * `true` = reducer accepted the dispatch (route/bundle CAS passed,
     * content merged); the watermark advances + the flag clears.
     * `false` = reducer rejected (route/bundle stale, OR the caller
     * short-circuited with route=0); the watermark + flag are
     * PRESERVED and the next sweep / route reactivation retries.
     *
     * @return `true` iff the commit landed; `false` on stale token,
     *   non-positive [responseSeq], stale response, OR [commitUi]
     *   rejection. On `false` the watermark map is untouched.
     */
    private val commitFull200: (
        sessionId: String, messageId: String,
        requestSeq: Long, responseSeq: Long,
        token: OpenCodeRepository.SlimCommitToken,
        commitUi: () -> Boolean,
    ) -> Boolean,
    /**
     * rev-b-fix §4 (C4 — Lane W port): conditional commit of a `/full`
     * 304 Not Modified. Clears the `needsFullRecheck` flag IFF the token
     * is current AND the current `messageEventSeq` exactly matches
     * [requestSeq]. Returns `true` iff the flag was cleared.
     */
    private val commitFull304: (
        sessionId: String, messageId: String,
        requestSeq: Long,
        token: OpenCodeRepository.SlimCommitToken,
    ) -> Boolean,
    /**
     * rev-b-fix M3 (Lane W port): token-guarded reconnect reset. Acquires
     * the slim state lock AND validates [token] inside the SAME critical
     * section that runs the reset (TOCTOU-safe). Returns `emptyMap()` on
     * a stale token (no work done); otherwise the per-session work set.
     */
    private val clearWatermarksForReconnect: (
        token: OpenCodeRepository.SlimCommitToken,
    ) -> Map<String, Set<String>>,
    /**
     * Sort-key provider: returns the message's `time.updated` (ms) for
     * the bounded active-set ordering, or null if the message is no
     * longer in the local cache (it will be reconciled LAST — most
     * recent activity first).
     */
    private val messageUpdatedAt: (sessionId: String, messageId: String) -> Long?,
    /**
     * R2 fingerprint provider: the current `partCount` (number of parts
     * in the local view of the message). null = unknown → omit from the
     * fingerprint (forces a 200).
     */
    private val messagePartCount: (sessionId: String, messageId: String) -> Int?,
    /**
     * R2 fingerprint provider: the highest partId currently in the
     * local view (lexicographic max). null = unknown → omit.
     */
    private val messageMaxPartId: (sessionId: String, messageId: String) -> String?,
    /**
     * Dispatches [AppAction.SlimFullMessageReconciled] to the store
     * (Lane U). Called inside [commitFull200]'s commitUi lambda, under
     * the slim state lock. The reducer CAS-rejects stale route / bundle
     * dispatches.
     *
     * # rev-ogpt #2 — returns Boolean
     *
     * `true` = dispatch was (or would be) accepted by the reducer —
     * the caller's [FullReconcileContext.expectedRouteInstance] still
     * matches the live `chatRouteInstance` AND the session is still
     * the current one. The dispatch runs.
     * `false` = dispatch would be rejected (route/bundle CAS fail).
     * The dispatch is SKIPPED (no-op) so the caller's [commitUi]
     * lambda returns `false` to [commitFull200]; the watermark +
     * flag are preserved for retry.
     *
     * NOT called when `context.expectedRouteInstance == 0L` — the
     * upstream [commitUi] lambda short-circuits with `false` in that
     * case (no active route owns the transcript; the reducer would
     * reject the write anyway).
     */
    private val dispatchSlimFullReconciled: (
        sessionId: String, message: MessageWithParts,
        context: FullReconcileContext,
    ) -> Boolean,
    /**
     * Dispatches [AppAction.MessageRemovedConfirmed] to the store (Lane U)
     * on a /full 404. The reducer evicts the message from BOTH the flat
     * + [LoadedContent] projections (the freeze protocol's dual-projection
     * invariant). NOT called when `context.expectedRouteInstance == 0L`
     * (no active route — the reducer is a no-op for route=0; only the
     * watermark/repository cleanup via [onMessageGone] runs).
     */
    private val dispatchMessageRemoved: (
        sessionId: String, messageId: String,
        context: FullReconcileContext,
    ) -> Unit,
    /**
     * B-P0-2 (replacement edge — done:true/false): per-part streaming
     * probe. Returns `true` iff `(sessionId, messageId, partId)` has
     * an ACTIVE token stream (i.e. the token stream is still
     * streaming the part — `done = false` in the sidecar's part
     * lifecycle). The reconciler consults this when processing a 200
     * /full response:
     *
     *  - `partIsStreaming(sid, mid, pid) == true` → the part's live
     *    content is owned by the token stream (provisional). The /full
     *    body's version of this part is DROPPED from the merged
     *    [Outcome.Reconciled.message] (the live token content wins;
     *    /full is reference-only for in-flight parts).
     *  - `partIsStreaming(sid, mid, pid) == false` → the part is NOT
     *    actively streaming (`done = true` OR no local ownership).
     *    The /full body's version of this part IS authoritative and
     *    is kept in the merged message; the client adopts /full.
     *  - Parts in /full that the client does NOT know about at all
     *    (new parts) → always kept (no streaming ownership to
     *    preserve); the client adopts /full.
     *
     * Default `{ _, _, _ -> false }` (no streaming → /full authoritative
     * for every part) preserves the pre-B-P0-2 behaviour.
     *
     * # /since orthogonality
     *
     * /since only advances metadata watermarks (`localApplied*`,
     * `remoteUpdatedAt`); /full only changes parts. The two NEVER
     * race on the same field — no mutex is required between them
     * (per B-P0-2 frozen contract).
     */
    private val partIsStreaming: (
        sessionId: String, messageId: String, partId: String,
    ) -> Boolean = { _, _, _ -> false },
    /**
     * B-P0-2 (MAJOR 4 — /full 404 cleanup): per-message gone callback
     * for WATERMARK/REPOSITORY cleanup only (token-guarded
     * `applyMessageRemoved`). Invoked once per message when /full
     * returns 404 (the message was deleted upstream). The UI-side
     * eviction is dispatched separately via [dispatchMessageRemoved]
     * (Lane U's [AppAction.MessageRemovedConfirmed] — route-guarded).
     *
     * Lane I MUST strip the legacy [AppAction.MessageRemovedFromFull]
     * dispatch from this callback's production wiring (it is superseded
     * by [dispatchMessageRemoved]).
     */
    private val onMessageGone: (
        sessionId: String, messageId: String, token: OpenCodeRepository.SlimCommitToken,
    ) -> Unit = { _, _, _ -> },
    /** IO dispatcher for the network-bound reconcile body. */
    private val ioDispatcher: CoroutineDispatcher,
    /** Clock for backoff timing (overridable for deterministic tests). */
    private val clock: () -> Long = ::defaultClock,
    /** Random source for full-jitter backoff (overridable for tests). */
    private val random: () -> Double = ::defaultRandom,
    /** Suspension-friendly sleep (overridable for tests via TestScheduler). */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /**
     * Per-session single-flight [Mutex] factory (M4). Returns the SAME
     * [Mutex] for a given sessionId across invocations (the default uses
     * a process-wide [ConcurrentHashMap] so concurrent callers for the
     * same session SERIALISE). All three entry points
     * ([reconcileMessage] / [reconcileActiveSession] / [reconcileReconnect]'s
     * per-session worker) share this mutex per session. Tests override
     * to inject a deterministic mutex.
     */
    private val inFlightMutex: (sessionId: String) -> Mutex = { sid ->
        SHARED_INFLIGHT_MUTEXES.getOrPut(sid) { Mutex() }
    },
) {
    /**
     * Captured at the reconcile trigger (BEFORE the first network call)
     * and threaded UNCHANGED across the entire fetch. Carries the route
     * instance + bundle stamp the reducer CAS-checks so a stale dispatch
     * (route advanced / bundle rotated during the network window) is
     * rejected.
     *
     * `expectedRouteInstance == 0L` means "no active route" — the
     * watermark still advances (the data layer is route-independent)
     * but the transcript dispatch is skipped (the reducer would reject
     * a route=0 write anyway).
     */
    data class FullReconcileContext(
        val expectedRouteInstance: Long,
        val bundleStamp: BundleStamp,
    )

    /**
     * The outcome of a single-message R2 reconcile. The flag state is
     * handled atomically inside the commit ports ([commitFull200] /
     * [commitFull304]) — this outcome only REPORTS what happened (the
     * caller does NOT clear the flag itself).
     */
    sealed interface Outcome {
        /**
         * 200 OK + body + valid strictly-positive seq. The commit landed
         * atomically: the watermark's `messageEventSeq` advanced to
         * [messageEventSeq], the flag was cleared, and the UI dispatch
         * ran (inside [commitFull200]'s token-guarded critical section).
         * [message] is the streaming-filtered body ready for the chat
         * merge path.
         */
        data class Reconciled(
            val message: MessageWithParts,
            val messageEventSeq: Long,
        ) : Outcome

        /**
         * 304 Not Modified. The client's fingerprint matched; the
         * watermark is already authoritative. The flag MAY have been
         * cleared (if the local seq still matched the request seq);
         * if the seq advanced during the network window, the flag is
         * preserved (the next sweep re-fetches against the new seq).
         * Either way, no body to merge.
         */
        data object NotModified : Outcome

        /**
         * rev-ogpt #2: 200 OK was fetched with a valid seq, but
         * [commitFull200] returned `false` because [commitUi] rejected
         * the dispatch (route/bundle CAS fail OR route=0 with no
         * active route). The flag is PRESERVED (the watermark's
         * `messageEventSeq` did NOT advance, `needsFullRecheck` stays
         * `true`); the next digest sweep — or route reactivation when
         * the route=0 skip path fires — re-enters with the same seq.
         *
         * Distinct from [Failure]: this is NOT an error. The fetch
         * succeeded; the response was valid; the route just wasn't
         * ready to receive the transcript write. Treat as "stay
         * flagged, non-aborting" in batch callers (same retry
         * semantics as [Failure]).
         */
        data object Skipped : Outcome

        /**
         * B-P0-2 (MAJOR 4): /full returned 404 — the message was
         * deleted upstream. The caller MUST run the [onMessageGone]
         * cleanup path (watermark removal) + [dispatchMessageRemoved]
         * (UI eviction). The flag is NOT cleared (the entry is removed
         * entirely, not flagged-clean). Distinct from [Failure] (which
         * leaves the message flagged for retry): 404 is definitive.
         */
        data object MessageGone : Outcome

        /**
         * 429 Too Many Requests. The sidecar's fetch-storm guard
         * tripped; the message stays flagged. [retryAfterMs] is the
         * honoured Retry-After (already capped). The caller backs off
         * the entire batch (or skips to the next sweep).
         */
        data class TooManyRequests(val retryAfterMs: Long) : Outcome

        /**
         * Token rotated (host switch / reconfigure). The reconcile is
         * aborted; the message stays flagged for the next incarnation's
         * sweep to pick up.
         */
        data object Stale : Outcome

        /**
         * Non-recoverable failure (5xx / 4xx other than 304/429 /
         * transport error / deserialization / protocol failure — a 200
         * missing `X-Message-Event-Seq` is reported here with
         * `httpStatus = 200`). The message stays flagged; the next
         * digest debounce or resync sweep will re-attempt.
         */
        data class Failure(
            val httpStatus: Int? = null,
            val error: Throwable? = null,
        ) : Outcome
    }

    /**
     * Batch outcome for [reconcileActiveSession] / [reconcileReconnect].
     * Aggregates the per-message [Outcome]s so the caller can drive
     * observability for each entry.
     */
    sealed interface BatchOutcome {
        /**
         * The batch ran. [results] maps messageId → per-message outcome.
         * Absent entries were not in the work set.
         */
        data class Completed(val results: Map<String, Outcome>) : BatchOutcome

        /**
         * The token rotated before / during the batch. No (further) work
         * was done; the caller SHOULD re-capture and retry, or defer to
         * the next sweep.
         */
        data object Stale : BatchOutcome
    }

    // ── R2 single-message reconcile ─────────────────────────────────────────

    /**
     * Drives ONE R2 `/full?known=` for `(sessionId, messageId)` under
     * the per-session single-flight mutex. See [reconcileMessageLocked]
     * for the protocol details.
     *
     * @param token captured BEFORE the first suspend point by the caller.
     * @param context route + bundle guard captured at the trigger.
     */
    suspend fun reconcileMessage(
        sessionId: String,
        messageId: String,
        token: OpenCodeRepository.SlimCommitToken,
        context: FullReconcileContext,
    ): Outcome = withContext(ioDispatcher) {
        inFlightMutex(sessionId).withLock {
            reconcileMessageLocked(sessionId, messageId, token, context)
        }
    }

    /**
     * Core R2 logic — NO mutex acquisition (the caller holds the
     * per-session single-flight mutex). Does NOT acquire [ioDispatcher]
     * (the caller provides the dispatcher context).
     *
     * Token-threaded: [requireTokenCurrent] is called after every
     * network suspension + every backoff sleep (M6). A rotated token
     * short-circuits with [Outcome.Stale].
     *
     * Flag state is handled atomically inside the commit ports
     * ([commitFull200] for 200, [commitFull304] for 304) — this method
     * does NOT clear the flag itself.
     *
     * [CancellationException] from [fetchFull] / [sleep] propagates
     * verbatim (M6 — never mapped to [Outcome.Failure]).
     */
    private suspend fun reconcileMessageLocked(
        sessionId: String,
        messageId: String,
        token: OpenCodeRepository.SlimCommitToken,
        context: FullReconcileContext,
    ): Outcome {
        val watermark = snapshotSessionWatermarks(sessionId)[messageId]
        val knownSeq = watermark?.messageEventSeq?.takeIf { it > 0L }
        val knownPartCount = messagePartCount(sessionId, messageId)
        val knownMaxPartId = messageMaxPartId(sessionId, messageId)
        var attempt = 0
        // Bounded retry loop: 429 / 503 / 413 retried up to
        // [MAX_ATTEMPTS_PER_MESSAGE]; exits with Failure / TooManyRequests
        // when exhausted. Bounded (vs `while(true)`) so the final return
        // is reachable.
        while (attempt <= MAX_ATTEMPTS_PER_MESSAGE) {
            if (!isTokenCurrent(token)) return Outcome.Stale
            val response = try {
                fetchFull(
                    sessionId, messageId,
                    knownMaxPartId, knownPartCount, knownSeq,
                )
            } catch (e: OpenCodeRepository.StaleSlimCommitException) {
                return Outcome.Stale
            } catch (e: CancellationException) {
                // M6: structured-concurrency cancellation MUST propagate —
                // never collapse to Failure (would break scope-cancel).
                throw e
            } catch (e: Throwable) {
                DebugLog.w(
                    TAG,
                    "R2 /full transport error sid=$sessionId mid=$messageId attempt=$attempt: ${e.message}",
                )
                return Outcome.Failure(error = e)
            }
            // M6: strong token guard after every network suspension.
            try {
                requireTokenCurrent(token)
            } catch (e: OpenCodeRepository.StaleSlimCommitException) {
                return Outcome.Stale
            }
            when (response.code()) {
                HTTP_200 -> {
                    val body = response.body()
                        ?: return Outcome.Failure(httpStatus = HTTP_200, error = null)
                    val seq = parseSeqHeader(response)
                    // C4: /full 200 MUST carry a strictly-positive
                    // X-Message-Event-Seq. Missing / non-positive = protocol
                    // failure → flag preserved, no merge, no commit.
                    if (seq == null || seq <= 0L) {
                        DebugLog.w(
                            TAG,
                            "R2 /full 200 missing/invalid seq sid=$sessionId mid=$messageId — protocol failure",
                        )
                        return Outcome.Failure(
                            httpStatus = HTTP_200,
                            error = IllegalStateException(
                                "protocol: X-Message-Event-Seq absent or non-positive on /full 200",
                            ),
                        )
                    }
                    val merged = filterStreamingParts(sessionId, messageId, body)
                    val requestSeq = knownSeq ?: 0L
                    // C4 + rev-ogpt #2: atomic commit — seq pre-check +
                    // UI dispatch verdict + (iff accepted) seq advance +
                    // flag clear, all in one token-guarded critical
                    // section (Lane W port).
                    //
                    // route=0: skip the dispatch (reducer would reject
                    // a route=0 transcript write anyway) and return
                    // `false` so the flag + seq are PRESERVED. The next
                    // digest sweep (after route reactivation) retries.
                    val commitUi: () -> Boolean = if (context.expectedRouteInstance == 0L) {
                        { false }
                    } else {
                        { dispatchSlimFullReconciled(sessionId, merged, context) }
                    }
                    val committed = commitFull200(
                        sessionId, messageId, requestSeq, seq, token, commitUi,
                    )
                    if (committed) {
                        return Outcome.Reconciled(message = merged, messageEventSeq = seq)
                    }
                    // Commit rejected. Three possible causes:
                    //  1. token stale (host switch / reconfigure) → Stale.
                    //  2. seq stale (responseSeq < currentSeq — newer
                    //     activity arrived during the network window).
                    //  3. UI rejected (route/bundle CAS fail OR route=0).
                    // Cases 2 and 3 are indistinguishable here without
                    // another snapshot, but they have IDENTICAL
                    // semantics (flag preserved, retry next sweep), so
                    // we report Skipped for both. Skipped is NOT a hard
                    // error — the caller treats it as "stay flagged,
                    // non-aborting" (same as Failure but explicitly
                    // signals a temporal / route mismatch rather than
                    // a transport / protocol fault).
                    if (!isTokenCurrent(token)) return Outcome.Stale
                    return Outcome.Skipped
                }
                HTTP_304_NOT_MODIFIED -> {
                    val requestSeq = knownSeq ?: 0L
                    // C4: conditional flag clear — only if current seq still
                    // matches requestSeq (no newer activity during the window).
                    // Returns true (flag cleared) or false (flag preserved).
                    // Either way the outcome is NotModified.
                    commitFull304(sessionId, messageId, requestSeq, token)
                    return Outcome.NotModified
                }
                HTTP_404 -> {
                    DebugLog.i(
                        TAG,
                        "R2 /full 404 sid=$sessionId mid=$messageId — message gone, cleanup",
                    )
                    return Outcome.MessageGone
                }
                HTTP_429 -> {
                    val retryAfterMs = parseRetryAfterMs(response).coerceAtLeast(0L)
                    if (attempt >= MAX_ATTEMPTS_PER_MESSAGE) {
                        DebugLog.w(
                            TAG,
                            "R2 /full 429 exhausted retries sid=$sessionId mid=$messageId — stays flagged",
                        )
                        return Outcome.TooManyRequests(retryAfterMs)
                    }
                    val backoff = if (attempt == 0 && retryAfterMs > 0L) {
                        retryAfterMs.coerceAtMost(RETRY_AFTER_HARD_CAP_MS)
                    } else {
                        computeBackoffMs(attempt)
                    }
                    DebugLog.w(
                        TAG,
                        "R2 /full 429 sid=$sessionId mid=$messageId attempt=$attempt backoff=${backoff}ms",
                    )
                    sleep(backoff)
                    // M6: strong token guard after backoff sleep.
                    try {
                        requireTokenCurrent(token)
                    } catch (e: OpenCodeRepository.StaleSlimCommitException) {
                        return Outcome.Stale
                    }
                    attempt++
                }
                HTTP_413, HTTP_503 -> {
                    if (attempt >= MAX_ATTEMPTS_PER_MESSAGE) {
                        return Outcome.Failure(httpStatus = response.code())
                    }
                    val backoff = computeBackoffMs(attempt)
                    DebugLog.w(
                        TAG,
                        "R2 /full ${response.code()} sid=$sessionId mid=$messageId attempt=$attempt backoff=${backoff}ms",
                    )
                    sleep(backoff)
                    try {
                        requireTokenCurrent(token)
                    } catch (e: OpenCodeRepository.StaleSlimCommitException) {
                        return Outcome.Stale
                    }
                    attempt++
                }
                else -> {
                    DebugLog.w(
                        TAG,
                        "R2 /full non-recoverable sid=$sessionId mid=$messageId code=${response.code()}",
                    )
                    return Outcome.Failure(httpStatus = response.code())
                }
            }
        }
        // Reachable ONLY when retries exhausted on a 429/503/413 path that
        // did NOT hit the explicit `>= MAX_ATTEMPTS_PER_MESSAGE` branch.
        return Outcome.Failure(error = IllegalStateException("retry exhausted"))
    }

    // ── R1 bounded batch (single session) ───────────────────────────────────

    /**
     * Drives the R1 bounded batch for [sessionId] under the per-session
     * single-flight mutex (M4): scans the watermark map for
     * `needsFullRecheck = true`, orders by `updatedAt` DESC, takes the
     * top [maxActive] (default [DEFAULT_MAX_ACTIVE] = 50), and reconciles
     * each via [reconcileMessageLocked]. Each successful outcome (200 OR
     * 304) clears the per-message flag atomically inside the commit port.
     *
     * # Single-flight
     *
     * The per-session [Mutex] (from [inFlightMutex]) serialises this
     * batch with any concurrent [reconcileMessage] / [reconcileReconnect]
     * worker for the same session. Callers SUSPEND (no AlreadyInFlight
     * drop); flags set during an in-flight batch are picked up by the
     * next caller that acquires the mutex.
     *
     * @param token captured BEFORE the first suspend point by the caller.
     * @param context route + bundle guard captured at the trigger.
     * @param maxActive the N=50 cap (overridable for tests).
     */
    suspend fun reconcileActiveSession(
        sessionId: String,
        token: OpenCodeRepository.SlimCommitToken,
        context: FullReconcileContext,
        maxActive: Int = DEFAULT_MAX_ACTIVE,
    ): BatchOutcome = withContext(ioDispatcher) {
        if (!isTokenCurrent(token)) return@withContext BatchOutcome.Stale
        inFlightMutex(sessionId).withLock {
            if (!isTokenCurrent(token)) return@withLock BatchOutcome.Stale
            val snapshot = snapshotSessionWatermarks(sessionId)
            val flagged = snapshot.filterValues { it.needsFullRecheck }.keys
            if (flagged.isEmpty()) return@withLock BatchOutcome.Completed(emptyMap())
            val ordered = flagged
                .sortedByDescending { messageUpdatedAt(sessionId, it) ?: Long.MIN_VALUE }
                .take(maxActive)
            val results = linkedMapOf<String, Outcome>()
            for (mid in ordered) {
                if (!isTokenCurrent(token)) return@withLock BatchOutcome.Stale
                val outcome = reconcileMessageLocked(sessionId, mid, token, context)
                results[mid] = outcome
                when (outcome) {
                    is Outcome.Reconciled, Outcome.NotModified -> {
                        // Flag state handled atomically inside
                        // commitFull200 / commitFull304. Nothing to do.
                    }
                    Outcome.Skipped -> {
                        // rev-ogpt #2: UI rejected the dispatch (route
                        // mismatch / route=0). Flag preserved by
                        // commitFull200; non-aborting — continue with
                        // the next message. Next sweep retries.
                    }
                    Outcome.MessageGone -> {
                        if (isTokenCurrent(token)) {
                            onMessageGone(sessionId, mid, token)
                            if (context.expectedRouteInstance != 0L) {
                                dispatchMessageRemoved(sessionId, mid, context)
                            }
                        }
                    }
                    is Outcome.TooManyRequests -> {
                        DebugLog.w(
                            TAG,
                            "R1 batch sid=$sessionId aborted on 429 at mid=$mid — back off",
                        )
                        return@withLock BatchOutcome.Completed(results)
                    }
                    is Outcome.Stale -> return@withLock BatchOutcome.Stale
                    is Outcome.Failure -> {
                        // Stay flagged (commit rejected / protocol failure);
                        // continue with the next message.
                    }
                }
            }
            BatchOutcome.Completed(results)
        }
    }

    // ── Reconnect R1 batch (server.connected / resync) ──────────────────────

    /**
     * Reconnect R1 batch: clears seq state for every session (M3 —
     * token-guarded [clearWatermarksForReconnect]`(token)`, TOCTOU-safe),
     * then fans out per-message R2 reconciles under a bounded
     * cross-session concurrency semaphore ([DEFAULT_RECONNECT_CONCURRENCY]
     * = 8). Each session's worker acquires that session's [inFlightMutex]
     * (M4 — serialises with any concurrent active-session batch).
     *
     * @param context route + bundle guard captured at the trigger.
     * @param token captured ONCE at entry (the reconnect is a single
     *   workflow). A token rotation mid-batch aborts ([BatchOutcome.Stale]).
     * @param concurrency cross-session fan-out cap (default 8).
     */
    suspend fun reconcileReconnect(
        context: FullReconcileContext,
        token: OpenCodeRepository.SlimCommitToken = tokenProvider(),
        concurrency: Int = DEFAULT_RECONNECT_CONCURRENCY,
    ): BatchOutcome = withContext(ioDispatcher) {
        if (!isTokenCurrent(token)) return@withContext BatchOutcome.Stale
        // M3: token-guarded reset (atomic token check + reset, TOCTOU-safe).
        val workBySession = clearWatermarksForReconnect(token)
        if (workBySession.isEmpty()) return@withContext BatchOutcome.Completed(emptyMap())
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val aggregate = coroutineScope {
            workBySession.entries
                .map { (sid, messageIds) ->
                    async(ioDispatcher) {
                        semaphore.withPermit {
                            reconcileReconnectSession(sid, messageIds, token, context)
                        }
                    }
                }
                .awaitAll()
        }
        val combined = linkedMapOf<String, Outcome>()
        for (sessionOutcome in aggregate) {
            when (sessionOutcome) {
                is ReconnectSessionOutcome.Done -> combined.putAll(sessionOutcome.results)
                ReconnectSessionOutcome.Stale -> return@withContext BatchOutcome.Stale
            }
        }
        BatchOutcome.Completed(combined)
    }

    /**
     * Per-session worker for [reconcileReconnect]. Acquires the session's
     * [inFlightMutex] (M4), reconciles each message serially via
     * [reconcileMessageLocked] (flag handled atomically inside the commit
     * ports), aborts on token rotation.
     */
    private suspend fun reconcileReconnectSession(
        sessionId: String,
        messageIds: Set<String>,
        token: OpenCodeRepository.SlimCommitToken,
        context: FullReconcileContext,
    ): ReconnectSessionOutcome = inFlightMutex(sessionId).withLock {
        val results = linkedMapOf<String, Outcome>()
        for (mid in messageIds) {
            if (!isTokenCurrent(token)) return@withLock ReconnectSessionOutcome.Stale
            val outcome = reconcileMessageLocked(sessionId, mid, token, context)
            results[mid] = outcome
            when (outcome) {
                is Outcome.Reconciled, Outcome.NotModified -> {
                    // Flag state handled atomically inside commit ports.
                }
                Outcome.Skipped -> {
                    // rev-ogpt #2: UI rejected (route mismatch / route=0).
                    // Flag preserved; non-aborting — next sweep retries.
                }
                Outcome.MessageGone -> {
                    if (isTokenCurrent(token)) {
                        onMessageGone(sessionId, mid, token)
                        if (context.expectedRouteInstance != 0L) {
                            dispatchMessageRemoved(sessionId, mid, context)
                        }
                    }
                }
                is Outcome.Stale -> return@withLock ReconnectSessionOutcome.Stale
                else -> { /* TooManyRequests / Failure: stay flagged, continue */ }
            }
        }
        ReconnectSessionOutcome.Done(results)
    }

    private sealed interface ReconnectSessionOutcome {
        data class Done(val results: Map<String, Outcome>) : ReconnectSessionOutcome
        data object Stale : ReconnectSessionOutcome
    }

    // ── B-P0-2 (replacement edge — done:true/false) ─────────────────────────

    /**
     * B-P0-2: filters the /full 200 body to enforce the
     * done:true/false replacement edge.
     *
     *  - For each part in [body]: if [partIsStreaming] returns `true`
     *    (the token stream still owns the live content — `done = false`),
     *    DROP the part. The client keeps its token stream content.
     *  - Otherwise: KEEP the part — /full is authoritative.
     *
     * If NO parts are dropped the original [body] reference is returned
     * (zero-allocation fast path).
     */
    private fun filterStreamingParts(
        sessionId: String,
        messageId: String,
        body: MessageWithParts,
    ): MessageWithParts {
        val parts = body.parts
        if (parts.isEmpty()) return body
        var anyStreaming = false
        for (part in parts) {
            if (partIsStreaming(sessionId, messageId, part.id)) {
                anyStreaming = true
                break
            }
        }
        if (!anyStreaming) return body
        val kept = parts.filterNot { partIsStreaming(sessionId, messageId, it.id) }
        if (kept.size == parts.size) return body
        return body.copy(parts = kept)
    }

    // ── Backoff (exponential with full jitter) ──────────────────────────────

    /**
     * Full-jitter exponential backoff for [attempt] (0-indexed):
     * `sleep = random(0, min(BASE * 2^attempt, CAP))` with BASE=1s,
     * CAP=30s (frozen R1 §退避).
     */
    private fun computeBackoffMs(attempt: Int): Long {
        val expCap = (BACKOFF_BASE_MS shl attempt).coerceAtMost(BACKOFF_CAP_MS)
        val jitter = random()
        return (expCap * jitter).toLong().coerceIn(0L, BACKOFF_CAP_MS)
    }

    companion object {
        private const val TAG = "SlimFullReconciler"

        /** R1 frozen: N=50 active messages cap per session batch. */
        const val DEFAULT_MAX_ACTIVE = 50

        /** Reconnect frozen: 8-way cross-session concurrency cap. */
        const val DEFAULT_RECONNECT_CONCURRENCY = 8

        /** Per-message 429 / 503 / 413 retry count (then stays flagged). */
        const val MAX_ATTEMPTS_PER_MESSAGE = 3

        /** Backoff base (frozen R1: base=1s). */
        const val BACKOFF_BASE_MS = 1_000L

        /** Backoff cap (frozen R1: max=30s). */
        const val BACKOFF_CAP_MS = 30_000L

        /** Retry-After hard cap (mirrors OCR's `retryAfterHeaderToMs`). */
        const val RETRY_AFTER_HARD_CAP_MS = 10_000L

        private const val HTTP_200 = 200
        private const val HTTP_304_NOT_MODIFIED = 304
        private const val HTTP_404 = 404
        private const val HTTP_413 = 413
        private const val HTTP_429 = 429
        private const val HTTP_503 = 503

        private fun defaultClock(): Long = System.currentTimeMillis()
        private fun defaultRandom(): Double = Math.random()

        private val SHARED_INFLIGHT_MUTEXES =
            java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    }
}
