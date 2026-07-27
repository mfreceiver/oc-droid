package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SlimapiQuestionEntry
import cn.vectory.ocdroid.data.model.SlimapiPermissionEntry
import cn.vectory.ocdroid.data.repository.SlimColdStartSnapshot
import cn.vectory.ocdroid.data.model.SlimSessionsPage
import cn.vectory.ocdroid.data.repository.http.SlimapiErrorCodes
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.exponentialBackoffMs
import cn.vectory.ocdroid.util.runSuspendCatching
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import cn.vectory.ocdroid.data.repository.MessagesPage

/**
 * §11.1 (slim message reliability joint plan — stage A): repository-layer
 * staging classification of a stage-A `/since` response. The `/since` endpoint
 * is NOT an authoritative completeness signal in stage A — even when the sidecar
 * returns `X-Since-Complete: true`, the response is staging-only (no bookmark
 * advance, no dirty clear, no authoritative cache replacement).
 *
 * # Variants
 *
 *  - [Staged] — HTTP 2xx with a non-null body. [completeHeader] carries the
 *    parsed `X-Since-Complete` header (`true`/`false`/`null` for absent or
 *    unparseable). Stage A does NOT commit on this variant regardless of the
 *    header — the caller may stage the items for display but MUST NOT advance
 *    the watermark / clear dirty / replace authoritative memory.
 *  - [Incomplete] — HTTP 2xx with a null body (reason = `"null_body"`). No
 *    items; the caller MUST NOT advance the watermark.
 *  - [Failed] — transport / serialization / IO error, OR a non-2xx HTTP
 *    status (wrapped as `IOException("HTTP ${code}")`). The caller treats this
 *    as a reconcile failure (preserve dirty). [CancellationException] and
 *    [OpenCodeRepository.StaleSlimCommitException] are NOT folded into this
 *    variant — they propagate as thrown exceptions from
 *    [SlimSyncEngine.fetchSinceForStageA].
 *
 * See [SlimSyncEngine.fetchSinceForStageA] for the exception-classification
 * contract.
 */
internal sealed interface SlimSinceStageAOutcome {
    data class Staged(
        val items: List<MessageWithParts>,
        val completeHeader: Boolean?,
        val statusCode: Int,
        val transportComplete: Boolean,
    ) : SlimSinceStageAOutcome

    data class Incomplete(
        val items: List<MessageWithParts>,
        val reason: String,
        val statusCode: Int?,
    ) : SlimSinceStageAOutcome

    data class Failed(
        val cause: Throwable,
    ) : SlimSinceStageAOutcome
}

/**
 * §11.1: typed exception returned (as `Result.failure`) by the legacy
 * [SlimSyncEngine.getSlimapiMessagesSince] / [OpenCodeRepository.getSlimapiMessagesSince]
 * facades once stage A closes the old authoritative `/since` reliability path.
 *
 * Stage A retains the old signatures for source/test binary compatibility, but
 * their implementations NO LONGER constitute a reliability path: they return
 * `Result.failure(SlimSinceStagingOnlyException)` unconditionally and perform
 * NO bookmark / localApplied / dirty mutation. New reliability callers MUST
 * use [SlimSyncEngine.fetchSinceForStageA] (returning [SlimSinceStageAOutcome])
 * and, for authoritative commits, the full/cursor drain +
 * [SlimAuthoritativeCommitter.commitAuthoritative] path.
 *
 * §11.1 fix-8 P1-2: this is now a thin typealias-equivalent subclass of
 * [OpenCodeRepository.SlimSinceStagingOnlyException] so consumers in the
 * `ui` package can detect the typed staging-only signal via
 * `error is OpenCodeRepository.SlimSinceStagingOnlyException`.
 */
internal class SlimSinceStagingOnlyException(message: String) :
    OpenCodeRepository.SlimSinceStagingOnlyException(message)

/**
 * §P1: Extracted slim message-sync engine from [OpenCodeRepository].
 * Holds the message page/since fetch, cold-start + bounded-drain algorithm,
 * and slim sync result construction.
 *
 * **No lock, no token minting, no reconfigure state.**
 * Delegates token validation + bookmark bumping to [slimStateMachine].
 *
 * Injected via provider-lambda pattern — does NOT reference [OpenCodeRepository].
 *
 * **v0.9.0 503 backoff** — [parseErrorCode] / [retryAfterHeaderToMs] are injected
 * as lambdas that delegate to the OCR `internal fun`s of the same name (single
 * source of truth for coded-envelope parsing + Retry-After decoding), mirroring
 * the [ExpandBatchEngine] injection pattern. No helper is re-defined here.
 */
class SlimSyncEngine internal constructor(
    /** Resolve the API from the operation token, never from a later bundle. */
    private val apiProvider: (OpenCodeRepository.SlimCommitToken) -> OpenCodeApi,
    private val slimStateMachine: SlimSseStateMachine,
    private val parseErrorCode: (retrofit2.Response<*>) -> String?,
    private val retryAfterHeaderToMs: (String?) -> Long,
    /**
     * §11.1 fix-4 wiring: the authoritative committer used by the
     * full/cursor drain `Success` path in [drainAndCommitAuthoritative]
     * (called by [coldStartSlimSync] and
     * [OpenCodeRepository.getMessagesPagedUnanchored]) to advance the
     * in-memory authoritative state (visible content, authoritative cache,
     * localApplied watermark, dirty clear). Null when no committer is
     * wired (tests / legacy construction) — [drainAndCommitAuthoritative]
     * throws [OpenCodeRepository.SlimAuthoritativeCommitFailedException]
     * in that case (the call cannot commit).
     *
     * §11.1 fix-8 P0-2: a non-null committer returning a non-Committed
     * result ([StaleToken] / [CacheWriteFailed] / [MergeRejected]) causes
     * [drainAndCommitAuthoritative] to throw — the drained items are NOT
     * surfaced to the caller (coldStartSlimSync folds the throw to
     * `messages = null`; getMessagesPagedUnanchored surfaces
     * `Result.failure`). The drain's per-page code path NEVER bumps the
     * watermark (P1-3); the watermark advances ONLY inside
     * [SlimAuthoritativeCommitter.commitAuthoritative] on a Committed result.
     */
    private val authoritativeCommitter: SlimAuthoritativeCommitter? = null,
    /**
     * §11.1 fix-9 P1-2: read-only provider of the current authoritative
     * message list for [sessionId], used by [drainAndCommitAuthoritative]
     * as the `authoritative` input to [mergeSlimMessageSet]. Backed by
     * [OpenCodeRepository.captureAuthoritativeMessages] in production.
     * Null in tests / legacy construction — [drainAndCommitAuthoritative]
     * falls back to `emptyList()` (no merge — the candidate carries the
     * raw drain items).
     */
    private val authoritativeMessagesProvider: ((String) -> List<MessageWithParts>)? = null,
) {
    // ── Constants ──────────────────────────────────────────────────────────────────

    internal companion object {
        internal const val SLIM_COLDSTART_SESSION_LIMIT = 500
    }

    // ── Public API: anchored /since fetch ──────────────────────────────────────

    /**
     * §11.1 (stage A): the NEW staging `/since` fetch. Performs the HTTP call,
     * classifies the response into a [SlimSinceStageAOutcome], and performs NO
     * bookmark / localApplied / dirty / authoritative-cache mutation. Stage A
     * treats every `/since` response (including `X-Since-Complete: true`) as
     * staging-only.
     *
     * # Exception classification contract (plan §11.1)
     *
     *  - [CancellationException]: thrown, NOT wrapped, NOT downgraded to
     *    [SlimSinceStageAOutcome.Failed]. A scope cancel propagates cleanly.
     *  - [OpenCodeRepository.StaleSlimCommitException]: thrown, NOT downgraded
     *    to [SlimSinceStageAOutcome.Failed]. A stale incarnation invalidates
     *    the entire attempt — the caller MUST NOT stage the result.
     *  - Other transport / serialization / IO exceptions: [SlimSinceStageAOutcome.Failed]`(cause)`.
     *  - Non-2xx HTTP: [SlimSinceStageAOutcome.Failed]`(IOException("HTTP ${code}"))`.
     *  - 2xx null body: [SlimSinceStageAOutcome.Incomplete]`(items = [], reason = "null_body", statusCode)`.
     *  - 2xx non-null body: [SlimSinceStageAOutcome.Staged], EVEN when
     *    `X-Since-Complete: true` — stage A does NOT authoritative-commit.
     *
     * # `X-Since-Complete` header parsing
     *
     * `"true" → true`, `"false" → false`, anything else / absent → `null`.
     * The header is surfaced on [SlimSinceStageAOutcome.Staged.completeHeader]
     * for diagnostics; stage A does NOT act on it.
     */
    internal suspend fun fetchSinceForStageA(
        sessionId: String,
        since: Long,
        limit: Int?,
        before: String?,
        token: OpenCodeRepository.SlimCommitToken,
    ): SlimSinceStageAOutcome {
        val response = try {
            apiProvider(token).getSlimapiMessagesSince(
                sessionId = sessionId,
                sinceTimestamp = since,
                limit = limit,
                before = before,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: OpenCodeRepository.StaleSlimCommitException) {
            throw e
        } catch (e: Throwable) {
            return SlimSinceStageAOutcome.Failed(e)
        }

        slimStateMachine.requireSlimTokenCurrent(token)

        if (!response.isSuccessful) {
            return SlimSinceStageAOutcome.Failed(
                java.io.IOException("HTTP ${response.code()}"),
            )
        }

        val body = response.body()
            ?: return SlimSinceStageAOutcome.Incomplete(
                items = emptyList(),
                reason = "null_body",
                statusCode = response.code(),
            )

        val completeHeader = response.headers()["X-Since-Complete"]?.let { raw ->
            when (raw.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

        // Stage A: even true is staging-only.
        return SlimSinceStageAOutcome.Staged(
            items = body,
            completeHeader = completeHeader,
            statusCode = response.code(),
            transportComplete = true,
        )
    }

    /**
     * §11.1 (stage A): the LEGACY anchored `/since` facade. **Stage A closes
     * this as a reliability path.** The signature is retained for source /
     * test binary compatibility, but the implementation returns
     * `Result.failure(SlimSinceStagingOnlyException)` unconditionally and
     * performs NO bookmark / localApplied / dirty mutation.
     *
     * New reliability callers MUST use [fetchSinceForStageA] (returning
     * [SlimSinceStageAOutcome]) for staging, and the full/cursor drain +
     * [SlimAuthoritativeCommitter.commitAuthoritative] path for authoritative
     * commits. No production reconcile / cold-start / MessageSource caller may
     * consume this facade's `Result.success` to judge authoritative success.
     *
     * Pass [since] = 0L for the cold-start path (no prior bookmark).
     */
    suspend fun getSlimapiMessagesSince(
        sessionId: String,
        since: Long,
        limit: Int? = null,
        before: String? = null,
        token: OpenCodeRepository.SlimCommitToken,
    ): Result<List<MessageWithParts>> = Result.failure(
        SlimSinceStagingOnlyException(
            "stage A: getSlimapiMessagesSince is staging-only; use fetchSinceForStageA + commitAuthoritative (sid=$sessionId, since=$since)",
        ),
    )

    /**
     * §slim-v1-paging (Task 5 / G5 cursor): Cluster A cursor-paginated
     * skeleton fetch (`GET /slimapi/messages/{sid}?limit=…&before=…&mode=…`).
     * Used for the no-bookmark cold-start branch in [coldStartSlimSync]
     * (which cursor-follows via [drainSlimapiMessagesBoundedOutcome]) and
     * any caller that wants to walk older history without an anchor ts.
     *
     * Surfaces BOTH the items AND the `X-Next-Cursor` response header on
     * the returned [MessagesPage] (was: the pre-G5 [getSlimapiMessagesPaged]
     * returned `Result<List<MessageWithParts>>` with no header access — the
     * cursor was discarded; that method was unused and is replaced here).
     * The legacy non-slim analogue is [getMessagesPaged] (legacy branch).
     *
     * §11.1 fix-8 P1-3: the `bumpBookmark` parameter was REMOVED. The
     * single-page fetch NEVER bumps the slim SSE watermark — completeness
     * proof belongs to the caller (the bounded drain bumps ONCE at
     * terminal page via [SlimAuthoritativeCommitter.commitAuthoritative];
     * a single-page caller has no completeness claim). Watermark advance
     * is the exclusive job of [SlimAuthoritativeCommitter.commitAuthoritative]
     * inside its token-guarded critical section. The `bumpBookmark = true`
     * path (single-page callers advancing the watermark from a non-terminal
     * cursor) was a P1-3 violation: it could advance `localApplied*` past a
     * page whose `X-Next-Cursor` was still non-null, treating an item-bound
     * mid-walk page as authoritative.
     */
    suspend fun getSlimapiMessagesPage(
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
        mode: String? = "skeleton",
        token: OpenCodeRepository.SlimCommitToken,
    ): Result<MessagesPage> = runSlimStaleAwareCatching(token) {
        val response = apiProvider(token).getSlimapiMessages(sessionId, limit, before, mode)

        slimStateMachine.requireSlimTokenCurrent(token)

        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code()}")
        // §11.5: a 2xx with null body is INCOMPLETE — the drain must
        // classify it as Partial (no bookmark advance). Throw a typed
        // signal so diagnostics can distinguish "empty body" from a
        // transport drop (both become Partial, but the cause differs).
        val items = response.body() ?: throw SlimPageIncompleteException("null_body")
        // §11.1 fix-8 P1-3: NO bookmark bump here — single-page fetch is
        // NOT a completeness proof. The drain bumps ONCE at terminal page
        // via commitAuthoritative; an item-bound mid-walk page must NOT
        // advance localApplied*.
        val nextCursor = response.headers()["X-Next-Cursor"]
        MessagesPage(items = items, nextCursor = nextCursor)
    }

    // ── Cold-start + bounded drain ──────────────────────────────────────────────

    /**
     * §11.1 fix-8 P0-1 + fix-9 P0-6 + P1-2: skeleton cursor drain +
     * authoritative commit. The ONLY path that may surface items to the
     * UI as a successful cold-load / reload (replacing both the legacy
     * `getMessagesPagedUnanchored` and `getMessagesPaged` slim `/since`
     * branches, which are now staging-only).
     *
     * # Behavior
     *
     *  1. Runs [drainSlimapiMessagesBoundedOutcome] (page limit
     *     [SLIMAPI_DEFAULT_PAGE_LIMIT], item bound [itemBound] which
     *     defaults to [SLIMAPI_LOCAL_HISTORY_BOUND] for cold-start and
     *     may be tightened for anchored tail reloads).
     *  2. §11.1 fix-9 P1-2: on [SlimDrainOutcome.Success], the drain
     *     items are MERGED onto the current authoritative set via
     *     [mergeSlimMessageSet]`(..., complete = true)` before the
     *     candidate is constructed. Missing-from-incoming ids are
     *     RETAINED (no tombstone), older tuples ignored, equal-tuple-
     *     different-parts kept authoritative. The merge input is read
     *     via [authoritativeMessagesProvider] (the OCR's per-session
     *     authoritative store).
     *  3. Constructs a [SlimAuthoritativeCandidate] from the merged
     *     aggregate + [maxMessageTuple], then drives
     *     [SlimAuthoritativeCommitter.commitAuthoritative].
     *      - [SlimAuthoritativeCommitResult.Committed] → returns the
     *        committed items (caller may surface them as a successful
     *        page).
     *      - Any non-Committed result ([StaleToken] / [CacheWriteFailed] /
     *        [MergeRejected]) → throws [OpenCodeRepository.SlimAuthoritativeCommitFailedException]
     *        carrying the result. P0-2 / coldStartSlimSync maps this to
     *        `messages = null` (keep prior, preserve dirty); the public
     *        [OpenCodeRepository.getMessagesPagedUnanchored] /
     *        [OpenCodeRepository.getMessagesPaged] surfaces map it to
     *        `Result.failure`. NEITHER exposes Partial items to the UI.
     *  4. On [SlimDrainOutcome.Partial] / [SlimDrainOutcome.Degraded]:
     *     throws [OpenCodeRepository.SlimCursorPartialException] (NO
     *     items exposed via the success channel).
     *  5. [CancellationException] / [OpenCodeRepository.StaleSlimCommitException]
     *     propagate verbatim.
     *
     * Stage A: this is a SKELETON-only drain — the items are not expanded
     * (parts lazy-loaded by the UI). The committed `messages` list is the
     * sole authoritative view; subsequent SSE updates advance the remote
     * watermark + set dirty via the digest reducer. When the SSE-driven
     * reconciler ([SlimSessionReconciler]) sees `localAppliedUpdatedAt != null`
     * (the session already has a watermark), it goes staging-only via
     * `/since/{ts}` ([fetchSinceForStageA]) — it does NOT drive a separate
     * drain+commit. Only the cold path (no watermark yet) routes through
     * this method via [coldStartSlimSync]. The SSE-driven reconciler's
     * authoritative commit happens via [foldRestFetch] on the cold-path
     * branch of `reconcileSessionLocked`. Stage-A SSE does NOT directly
     * merge into authoritative — the authoritative store is updated ONLY
     * via the commit protocol (this method or [SlimSessionReconciler.foldRestFetch]).
     */
    internal suspend fun drainAndCommitAuthoritative(
        sessionId: String,
        token: OpenCodeRepository.SlimCommitToken,
        itemBound: Int = SLIMAPI_LOCAL_HISTORY_BOUND,
    ): List<MessageWithParts> {
        val outcome = drainSlimapiMessagesBoundedOutcome(
            sessionId = sessionId,
            pageLimit = SLIMAPI_DEFAULT_PAGE_LIMIT,
            itemBound = itemBound,
            token = token,
        )
        val items = when (outcome) {
            is SlimDrainOutcome.Success -> outcome.items
            is SlimDrainOutcome.Partial ->
                throw OpenCodeRepository.SlimCursorPartialException(outcome.cause)
            is SlimDrainOutcome.Degraded ->
                throw OpenCodeRepository.SlimCursorPartialException(outcome.cause)
        }
        // §11.1 fix-9 P1-2 + fix-10 P1-1: merge drain items onto the
        // current authoritative set BEFORE constructing the candidate. The
        // drain is a complete cursor snapshot (terminal page reached), so
        // the complete-merge contract applies. Missing-from-incoming ids
        // are RETAINED (no tombstone), older tuples ignored, equal-tuple-
        // different-parts kept authoritative + hasConflict flag set.
        val authoritative = authoritativeMessagesProvider?.invoke(sessionId) ?: emptyList()
        val mergeResult = mergeSlimMessageSetWithConflict(
            authoritative = authoritative,
            incoming = items,
            complete = true,
        )
        val merged = mergeResult.messages
        // Construct candidate from the merged aggregate.
        val (ts, id) = maxMessageTuple(merged)?.let { it.first to it.second }
            ?: (null to null)
        val candidate = SlimAuthoritativeCandidate(
            sessionId = sessionId,
            token = token,
            messages = merged,
            localAppliedUpdatedAt = ts,
            localAppliedMessageId = id,
            // §11.1 fix-10 P1-1 / rev-ogpt P1-2: thread the merge's conflict
            // signal into the commit's atomic dirty decision. The commit's
            // replaceLocalAppliedAndClearDirtyLocked will set dirty=true
            // UNCONDITIONALLY when hasConflict=true — inside the SAME critical
            // section that writes localApplied*, no separate forceSlimDirty
            // post-write (which had a non-atomic window between commit and
            // forceDirty).
            hasConflict = mergeResult.hasConflict,
        )
        val committer = authoritativeCommitter
            ?: throw OpenCodeRepository.SlimAuthoritativeCommitFailedException(
                "no authoritativeCommitter wired (sessionId=$sessionId); " +
                    "stage-A commit unavailable",
                SlimAuthoritativeCommitResult.MergeRejected("no committer"),
            )
        return when (val commitResult = committer.commitAuthoritative(candidate)) {
            is SlimAuthoritativeCommitResult.Committed -> {
                // §11.1 fix-10 P1-1 / rev-ogpt P1-2: the conflict's dirty
                // decision is now ATOMIC with the commit — no separate
                // forceSlimDirty call here. The commit's critical section
                // already set dirty=true iff mergeResult.hasConflict (or
                // remote > localApplied via P0-4). Removing the post-commit
                // forceSlimDirty closes the P1-2 atomicity window (commit
                // completed → dirty=false briefly → then forceSlimDirty
                // re-set dirty=true in a SEPARATE critical section).
                merged
            }
            is SlimAuthoritativeCommitResult.StaleToken,
            is SlimAuthoritativeCommitResult.CacheWriteFailed,
            is SlimAuthoritativeCommitResult.MergeRejected -> {
                DebugLog.w(
                    "SlimapiResync",
                    "drainAndCommitAuthoritative non-Committed sid=$sessionId " +
                        "result=${commitResult::class.simpleName}",
                )
                throw OpenCodeRepository.SlimAuthoritativeCommitFailedException(
                    "authoritative commit did not succeed (sessionId=$sessionId, " +
                        "result=${commitResult::class.simpleName})",
                    commitResult,
                )
            }
        }
    }

    /**
     * §slim-reconcile-lane-repo (T11 round-3): cold-start snapshot fetch.
     * Atomically reads the server's current session list + pending questions +
     * permissions + (optionally) the open session's message window.
     *
     * The returned [SlimColdStartSnapshot] is a 4-piece aggregate; each
     * piece is nullable — a null piece means "keep prior". Only a hard
     * transport failure that surfaces as [Result.failure] (vs a per-piece
     * null) invalidates the entire attempt.
     *
     * The message piece is either an anchored fetch from a prior bookmark (if
     * one exists for the open session) OR a cursor-follow bounded drain (no
     * bookmark — the client has never observed this session before, or the
     * bookmark was invalidated by a host rotation).
     */
    suspend fun coldStartSlimSync(
        openSessionId: String? = null,
        directories: List<String>? = null,
        token: OpenCodeRepository.SlimCommitToken,
    ): Result<SlimColdStartSnapshot> = runSuspendCatching {
        // §slim-reconcile-lane-repo (B4 T6): forward [directories] to
        // /slimapi/sessions (was: ignored — call always hit the unfiltered
        // path). The contract's repeated `?directory` is now produced by
        // Retrofit from the list parameter (null = sidecar decides scope).
        //
        // T11 round-2 (oracle D2): null on failure; emptyList on success.
        //
        // T11 round-3 (CE discipline, R-14): the metadata Retrofit calls
        // are suspend — plain `runCatching` swallows CancellationException,
        // violating the explicit must-hold rule. Use [runSuspendCatching]
        // so a scope cancel mid-fetch propagates as CE instead of being
        // collapsed to a null piece (which would mask the cancellation as
        // a per-piece failure).
        val sessionsPage: SlimSessionsPage? = runSuspendCatching {
            // §session-scope-narrow: pin `roots=true` + explicit limit so the
            // cold-start snapshot fetches ONLY root/main sessions of the
            // (caller-narrowed) directory set, NOT the unbounded child fan-out
            // (subagent / task children). The default `limit=100` was silently
            // truncating the list; `roots=true` filters children server-side.
            // curl-verified on the live sidecar: roots=true drops ≈244 child
            // rows; limit=500 captures the full root set (130 roots → 120 once
            // the caller's local-project directory filter is applied).
            // Combined with [SessionSyncCoordinator.performSlimResync]'s
            // `recentWorkdirs` directory narrowing + the merge in
            // [SessionSyncCoordinator.applySlimColdStartSnapshot] (fix-4),
            // this is the second of the two scope-narrowing levers.
            getSlimapiSessionsResult(
                directories = directories,
                roots = true,
                limit = SLIM_COLDSTART_SESSION_LIMIT,
                token = token,
            ).getOrElse { throw it }
        }.getOrElse { error ->
            if (error is OpenCodeRepository.StaleSlimCommitException) throw error
            null
        }

        // §slim-envelope: /questions + /permissions return {items, errors};
        // flatten `.items` for UI. Per-directory `errors` are logged here
        // (the sidecar already degrades — a 200 with partial items is the
        // expected steady-state when one upstream opencode is briefly down).
        //
        // C-D3 v2 §1.6: a stale incarnation is NOT a per-piece Failure;
        // it invalidates the entire snapshot. StaleSlimCommitException
        // rethrows out of this block; coldStartSlimSync returns
        // Result.failure(StaleSlimCommitException) instead.
        val questions = runSuspendCatching {
            val agg = apiProvider(token).getSlimapiQuestions(directories)

            slimStateMachine.requireSlimTokenCurrent(token)

            if (agg.errors.isNotEmpty()) {
                DebugLog.w("OpenCodeRepository", "slimapi/questions partial errors: ${agg.errors}")
            }
            aggregationOutcome(
                items = agg.items,
                errors = agg.errors,
                requestedDirectories = directories,
                directoryOf = SlimapiQuestionEntry::directory,
                serverScope = agg.scope,
            )
        }.getOrElse { error ->
            if (error is OpenCodeRepository.StaleSlimCommitException) throw error
            SlimAggregationOutcome.Failure(error.message)
        }

        val permissions = runSuspendCatching {
            val agg = apiProvider(token).getSlimapiPermissions(directories)

            slimStateMachine.requireSlimTokenCurrent(token)

            if (agg.errors.isNotEmpty()) {
                DebugLog.w("OpenCodeRepository", "slimapi/permissions partial errors: ${agg.errors}")
            }
            aggregationOutcome(
                items = agg.items,
                errors = agg.errors,
                requestedDirectories = directories,
                directoryOf = SlimapiPermissionEntry::directory,
                serverScope = agg.scope,
            )
        }.getOrElse { error ->
            if (error is OpenCodeRepository.StaleSlimCommitException) throw error
            SlimAggregationOutcome.Failure(error.message)
        }

        val messages: List<MessageWithParts>? = openSessionId?.let { sid ->
            // C-D3 v2 §1.5: token-threaded anchored + cursor branches.
            // A stale incarnation rethrows out of the message fetch
            // (NOT collapses to null — that would mask a host rotation as
            // "server unreachable"). Other transport/HTTP failures degrade
            // to null as before (cold-start per-piece degradation).
            val bookmark = slimStateMachine.readBookmarkOrThrowIfStale(sid, token)

            if (bookmark != null) {
                // §11.1 stage A: anchored `/since` is staging-only.
                // Staged / Incomplete / Failed ALL map to `messages = null`
                // (keep prior). NO bookmark bump, NO authoritative commit,
                // NO clearLocal / markSlimReconcileSuccess / onReconcileSuccess.
                // CancellationException + StaleSlimCommitException propagate
                // out of fetchSinceForStageA (per its exception contract) and
                // are handled by the outer runSuspendCatching.
                val outcome = fetchSinceForStageA(
                    sessionId = sid,
                    since = bookmark,
                    limit = SLIMAPI_DEFAULT_PAGE_LIMIT,
                    before = null,
                    token = token,
                )
                DebugLog.d(
                    "SlimapiResync",
                    "coldStartSlimSync anchored /since staging-only sid=$sid " +
                        "since=$bookmark outcome=${outcome::class.simpleName} " +
                        "items=${(outcome as? SlimSinceStageAOutcome.Staged)?.items?.size ?: 0}",
                )
                null
            } else {
                // §11.5 + §11.2 fix-4 + fix-8 P0-2: full/cursor drain is the
                // ONLY path that may advance the watermark authoritatively.
                // The drain + commit happens inside [drainAndCommitAuthoritative];
                // a non-Committed result ([StaleToken] / [CacheWriteFailed] /
                // [MergeRejected]) throws [SlimAuthoritativeCommitFailedException]
                // which we degrade to `messages = null` (keep prior visible
                // content + preserve dirty — §11.2 failure-branch invariant).
                // Partial / Degraded drain likewise throws
                // [SlimCursorPartialException] → degrade to null. The drained
                // items NEVER reach the cold-start merge on a non-Committed /
                // Partial / Degraded outcome (P0-2).
                runSlimStaleAwareCatching(token) {
                    drainAndCommitAuthoritative(
                        sessionId = sid,
                        token = token,
                    )
                }.getOrElse { error ->
                    if (error is OpenCodeRepository.StaleSlimCommitException) throw error
                    // P0-2: log the specific failure for diagnostics, then
                    // degrade to null. The caller folds null as "keep prior";
                    // dirty stays true so the next reconcile retries.
                    DebugLog.w(
                        "SlimapiResync",
                        "coldStartSlimSync drain+commit sid=$sid failed: ${error::class.simpleName} ${error.message}",
                    )
                    null
                }
            }
        }

        // If ALL four pieces are null AND openSessionId was supplied with
        // at least one piece attempted, the overall Result is still
        // success — the caller folds null pieces as "keep prior". Only a
        // hard transport failure that threw out of runCatching surfaces
        // as Result.failure.
        // §#5 belt: chronological sort the drain result so any cold-start
        // merge sees chronological input even if the serving layer reorders.
        // (optional defense, reducer has the canonical sort)
        val chronoMessages = messages?.sortedWith(
            compareBy<MessageWithParts>(
                { it.info.time?.created ?: Long.MAX_VALUE },
                { it.info.id },
            )
        )
        SlimColdStartSnapshot(
            sessions = sessionsPage?.sessions,
            questions = questions,
            permissions = permissions,
            messages = chronoMessages,
            complete = sessionsPage?.complete,
            discoveryDirectories = sessionsPage?.discoveryDirectories,
            discoveryReady = sessionsPage?.discoveryReady,
        )
    }

    // ── Internal drain helpers ────────────────────────────────────────────────────

    /**
     * Bounded skeleton-cursor drain façade that wraps [drainSlimapiMessagesBoundedOutcome]
     * in a plain `List`. Used by the cold-start path when no bookmark exists
     * (the no-bookmark branch of [coldStartSlimSync]).
     *
     * §11.5 unified state contract — ONLY [SlimDrainOutcome.Success] is
     * surfaced as a return value. [SlimDrainOutcome.Partial] /
     * [SlimDrainOutcome.Degraded] throw [OpenCodeRepository.SlimCursorPartialException]
     * so the partial aggregate is NEVER fed into cold-start / reconciler /
     * visible-content merge via this List façade. (The cold-start path's
     * [coldStartSlimSync] no-bookmark branch degrades this throw to a null
     * piece — "keep prior" — so partial items never reach the merge.)
     * Diagnostic consumers that WANT the partial aggregate MUST call
     * [drainSlimapiMessagesBoundedOutcome] directly and write it to
     * temporary staging.
     *
     * §11.1 fix-6 P0-3: this façade does NOT bump the bookmark. The caller
     * MUST drive [SlimAuthoritativeCommitter.commitAuthoritative] to advance
     * the watermark atomically with the visible-content replacement.
     *
     * See [drainSlimapiMessagesBoundedOutcome] for the full contract.
     */
    internal suspend fun drainSlimapiMessagesBounded(
        sessionId: String,
        pageLimit: Int,
        itemBound: Int,
        token: OpenCodeRepository.SlimCommitToken,
    ): List<MessageWithParts> = when (
        val outcome = drainSlimapiMessagesBoundedOutcome(
            sessionId = sessionId,
            pageLimit = pageLimit,
            itemBound = itemBound,
            token = token,
        )
    ) {
        is SlimDrainOutcome.Success -> outcome.items
        is SlimDrainOutcome.Partial ->
            throw OpenCodeRepository.SlimCursorPartialException(outcome.cause)
        is SlimDrainOutcome.Degraded ->
            throw OpenCodeRepository.SlimCursorPartialException(outcome.cause)
    }

    /**
     * Bounded skeleton-cursor drain (G5 cursor follow).
     *
     * Walks the skeleton-only message window for [sessionId] via cursor-
     * paginated `GET /slimapi/messages/{sid}?limit=…&before=…&mode=skeleton`
     * up to [itemBound] items OR page-count cap (wall-clock 30 s timeout),
     * whichever hits first.
     *
     * Returns ONE OF:
     *  - [SlimDrainOutcome.Success] — all pages succeeded and the final
     *    page had `nextCursor == null`. The local watermark is NOT advanced
     *    by the drain — the caller MUST construct a
     *    [SlimAuthoritativeCandidate] and drive
     *    [SlimAuthoritativeCommitter.commitAuthoritative] to advance the
     *    watermark / clear dirty / replace visible content atomically.
     *  - [SlimDrainOutcome.Partial] — HTTP / transport / page failure,
     *    wall-clock timeout, OR a safety bound ([itemBound] / page-count
     *    cap) reached while the server still returned a non-null
     *    `X-Next-Cursor`. [items] is a partial aggregate for staging /
     *    diagnostics only. The local watermark is NOT advanced.
     *  - [SlimDrainOutcome.Degraded] — loop / zero-progress detection.
     *    Same contract as Partial: no watermark advance, staging-only
     *    items.
     *
     * §11.5 unified state contract — [itemBound] and the page-count cap
     * are SAFETY limits, NOT completeness proof. If either bound is hit
     * while the server's `X-Next-Cursor` is non-null, the result MUST be
     * [SlimDrainOutcome.Partial] (cause =
     * [SlimDrainBoundExceededException]); the walk may NOT return Success
     * and may NOT bump the bookmark. Only an explicit terminal page
     * (`nextCursor == null`) is Success.
     *
     * Implementation shape (page-level check, NOT per-item):
     *   1. fetch + aggregate the FULL page (dedup by message id)
     *   2. check `nextCursor`:
     *      - null → Success (NO bookmark bump — caller commits atomically)
     *      - non-null AND `aggregated.size >= itemBound` → Partial
     *        (SlimDrainBoundExceededException, NO bump)
     *   3. advance `before` and loop; if the page-count cap exhausts with
     *      a non-null cursor still pending → Partial
     *      (SlimDrainBoundExceededException, NO bump)
     * The per-item loop MUST NOT return Success based on `aggregated.size`
     * alone — the page's `nextCursor` is the only completeness signal.
     *
     * BOOKMARK INVARIANT (fix-6 P0-3): The drain NEVER bumps the bookmark.
     * Only [SlimAuthoritativeCommitter.commitAuthoritative] advances
     * `localApplied*` / clears `dirty` / replaces `visibleContent` — all
     * inside one token-guarded critical section. This guarantees that a
     * CacheWriteFailed / StaleToken / MergeRejected leaves the old
     * `localApplied*` / `visibleContent` / `dirty` unchanged.
     *
     * CE discipline: [CancellationException] (non-timeout) propagates
     * out of [getSlimapiMessagesPage] via [runSuspendCatching] and is NOT
     * caught here — it surfaces to the caller as a thrown CE (not as a
     * Partial). Only [TimeoutCancellationException] (the 30 s wall-clock
     * bound) is caught and mapped to Partial.
     */
    internal suspend fun drainSlimapiMessagesBoundedOutcome(
        sessionId: String,
        pageLimit: Int,
        itemBound: Int,
        token: OpenCodeRepository.SlimCommitToken,
    ): SlimDrainOutcome {
        val aggregated = mutableListOf<MessageWithParts>()
        val seen = HashSet<String>()
        var before: String? = null
        // +1 slack page for the trailing partial; ceil via Int math.
        val maxPages = (itemBound + pageLimit - 1) / pageLimit + 1

        // G-F1: wall-clock bound for the entire cursor walk (30s). On timeout
        // surface as Partial (preserve dirty, retain aggregated items).
        return try {
            withTimeout(30_000L) {
                repeat(maxPages) {
                    // C-D3 v2 §1.4: SAME entry token on every page. No recapture.
                    val page = getSlimapiMessagesPage(
                        sessionId = sessionId,
                        limit = pageLimit,
                        before = before,
                        mode = "skeleton",
                        token = token,
                    ).getOrElse { error ->
                        // Stale incarnation is NOT an ordinary partial transport
                        // result; it invalidates the entire aggregate.
                        if (error is OpenCodeRepository.StaleSlimCommitException) {
                            throw error
                        }

                        // §11.5: Partial transport / null-body / HTTP failure —
                        // NO bookmark bump (preserve dirty; next reconcile
                        // retries the full cursor window from the prior
                        // watermark).
                        return@withTimeout SlimDrainOutcome.Partial(
                            items = aggregated.toList(),
                            cause = error,
                        )
                    }

                    // Even with bumpBookmark=false, a host switch during the page
                    // request invalidates that page's payload.
                    slimStateMachine.requireSlimTokenCurrent(token)

                    // ── G-F1 loop detection ──────────────────────────────────────────
                    // Loop = (a) same opaque cursor returned again, OR
                    //        (b) non-null cursor with zero new mids (all dupes).
                    val loopDetected =
                        (before != null && page.nextCursor == before) ||
                            (page.nextCursor != null && page.items.all { it.info.id in seen })
                    if (loopDetected) {
                        // §11.5: NO bookmark bump on Degraded/loop-Partial.
                        // If no items aggregated at all, this is a complete failure;
                        // if some items exist, this is a degraded walk.
                        return@withTimeout if (aggregated.isEmpty()) {
                            // No progress at all — surface as a Partial with cause.
                            SlimDrainOutcome.Partial(
                                items = emptyList(),
                                cause = SlimDrainLoopException("loop detected on first page: cursor=$before"),
                            )
                        } else {
                            SlimDrainOutcome.Degraded(
                                items = aggregated.toList(),
                                cause = SlimDrainLoopException("loop detected after page: cursor=$before"),
                            )
                        }
                    }

                    // ── §11.5 page-level aggregate (NOT per-item cap) ───────────────
                    // First dedup + aggregate the FULL page; do NOT return mid-item.
                    for (item in page.items) {
                        if (seen.add(item.info.id)) {
                            aggregated += item
                        }
                    }

                    // ── §11.5 page-level terminal check (nextCursor FIRST) ──────────
                    // nextCursor is the ONLY completeness signal. itemBound / page
                    // cap are SAFETY limits, NOT proof.
                    when {
                        page.nextCursor == null -> {
                            // Explicit terminal page → Success. Even if the
                            // aggregate happens to hit itemBound on this exact
                            // page, the server signalled end-of-history → success.
                            // §11.1 fix-6 P0-3: do NOT bump bookmark here — the
                            // watermark advance happens atomically inside
                            // commitAuthoritative (via replaceLocalAppliedAndClearDirty).
                            // Bumping here would split the "classify → commit"
                            // atomicity: a CacheWriteFailed/StaleToken/MergeRejected
                            // after the bump would leave localApplied advanced but
                            // visibleContent/authoritativeLocal/dirty unchanged.
                            return@withTimeout SlimDrainOutcome.Success(aggregated.toList())
                        }

                        aggregated.size >= itemBound -> {
                            // Item safety bound hit while cursor is STILL non-null
                            // → Partial. The bound is not completeness proof; the
                            // walk has NOT exhausted history. NO bookmark bump.
                            return@withTimeout SlimDrainOutcome.Partial(
                                items = aggregated.toList(),
                                cause = SlimDrainBoundExceededException(
                                    "item bound reached before cursor exhaustion",
                                ),
                            )
                        }
                    }

                    before = page.nextCursor
                }
                // Page-count safety cap exhausted with a non-null cursor still
                // pending → Partial (NO bump; bound is not completeness proof).
                SlimDrainOutcome.Partial(
                    items = aggregated.toList(),
                    cause = SlimDrainBoundExceededException(
                        "page bound reached before cursor exhaustion",
                    ),
                )
            }
        } catch (e: TimeoutCancellationException) {
            // §11.5: wall-clock timeout → Partial, NO bookmark bump.
            SlimDrainOutcome.Partial(aggregated.toList(), e)
        }
    }

    /**
     * T11 round-2 (oracle I2 — watermark-branched fetch façade): fetch the
     * initial message window for [sessionId] when the client has NO
     * `localAppliedUpdatedAt` (cold path / fresh after dirty-clear). Wraps
     * [drainSlimapiMessagesBoundedOutcome] in a **STRICT [Result] type** so
     * the coordinator can distinguish:
     *
     * §11.1 fix-8 final contract (supersedes "cursor-null, item-bound, or
     * page-count cap" semantics from earlier rounds):
     *
     *  - `Result.success(items)` — bounded skeleton cursor drain reached a
     *    TERMINAL page (`nextCursor == null`). The local watermark is NOT
     *    advanced by the drain — the caller MUST drive
     *    [SlimAuthoritativeCommitter.commitAuthoritative] to advance it
     *    atomically. An item-bound / page-count cap hit while the cursor is
     *    STILL non-null is a [SlimDrainOutcome.Partial] (NOT Success) per
     *    §11.5 — the cap is a SAFETY limit, not a completeness proof.
     *  - `Result.failure(SlimCursorPartialException)` — mid-walk transport
     *    / page failure detected (including loop / zero-progress / timeout
     *    / item-bound / page-bound with non-null cursor). The local
     *    watermark is NOT advanced (no-bump-on-partial). The next reconcile
     *    re-enters the cursor walk from the same pre-drain watermark.
     *
     * **CancellationException** propagation: [drainSlimapiMessagesBoundedOutcome]
     * internally (via [getSlimapiMessagesPage]); CE propagates out of
     * this façade as a thrown [CancellationException] (NOT as
     * `Result.failure(CE)`). A scope cancel mid-walk terminates the
     * cursor follow cleanly without landing a partial state mutation.
     */
    suspend fun fetchSlimInitialWindowBounded(
        sessionId: String,
        token: OpenCodeRepository.SlimCommitToken,
    ): Result<List<MessageWithParts>> = runSuspendCatching {
        when (
            val outcome = drainSlimapiMessagesBoundedOutcome(
                sessionId = sessionId,
                pageLimit = SLIMAPI_DEFAULT_PAGE_LIMIT,
                itemBound = SLIMAPI_LOCAL_HISTORY_BOUND,
                token = token,
            )
        ) {
            is SlimDrainOutcome.Success -> {
                slimStateMachine.requireSlimTokenCurrent(token)
                outcome.items
            }

            is SlimDrainOutcome.Partial -> {
                // Mid-walk transport/page failure. localApplied* is
                // unchanged (no-bump-on-partial). Surface as a
                // distinguishable failure so the reconciler preserves
                // dirty AND the next reconcile re-enters the cursor
                // drain from the same pre-drain watermark.
                if (outcome.cause is CancellationException &&
                    outcome.cause !is TimeoutCancellationException
                ) {
                    throw outcome.cause
                }
                if (!slimStateMachine.isSlimCommitTokenCurrent(token)) {
                    throw OpenCodeRepository.StaleSlimCommitException()
                }
                throw OpenCodeRepository.SlimCursorPartialException(outcome.cause)
            }

            is SlimDrainOutcome.Degraded -> {
                // G-F1 loop/zero-progress detection — same contract as Partial:
                // keep dirty, no watermark advance.
                if (outcome.cause is CancellationException &&
                    outcome.cause !is TimeoutCancellationException
                ) {
                    throw outcome.cause
                }
                if (!slimStateMachine.isSlimCommitTokenCurrent(token)) {
                    throw OpenCodeRepository.StaleSlimCommitException()
                }
                throw OpenCodeRepository.SlimCursorPartialException(outcome.cause)
            }
        }
    }

    /**
     * Converts a transport failure from a retired generation into the slim
     * incarnation failure promised by the token contract. A current token
     * keeps the original transport error so transient network failures are
     * not mislabeled as reconfigure races. Cancellation is always propagated.
     */
    private inline fun <T> runSlimStaleAwareCatching(
        token: OpenCodeRepository.SlimCommitToken,
        block: () -> T,
    ): Result<T> {
        val result = runSuspendCatching(block)
        val error = result.exceptionOrNull() ?: return result
        if (error is CancellationException) throw error
        return if (slimStateMachine.isSlimCommitTokenCurrent(token)) {
            result
        } else {
            Result.failure<T>(OpenCodeRepository.StaleSlimCommitException())
        }
    }

    // ── Internal: /slimapi/sessions helper ────────────────────────────────────

    /**
     * Thin wrapper around the Retrofit call to `/slimapi/sessions`.
     * Mirrors [SessionSource.getSlimapiSessionsDelegate] but inlined here
     * to keep [SlimSyncEngine] self-contained (no external delegate dep).
     *
     * **v0.9.0 503 backoff** (mirrors [ExpandBatchEngine] L440-460 + the
     * top-level [getSlimapiSessionsDelegate]): ≤3 attempts, Retry-After
     * header honored with exponential-backoff fall-back, only
     * `503 + transform_busy` retries. The sidecar's coded envelope is read
     * EXACTLY ONCE via the injected OCR [parseErrorCode] (errorBody is
     * one-shot); the parsed code drives BOTH the retry decision AND the
     * WARN observability log. [retryAfterHeaderToMs] is likewise the
     * injected OCR helper — no helper is re-defined here.
     */
    private suspend fun getSlimapiSessionsResult(
        directories: List<String>?,
        roots: Boolean?,
        limit: Int?,
        token: OpenCodeRepository.SlimCommitToken,
    ): Result<SlimSessionsPage> = runSuspendCatching {
        var lastException: HttpException? = null
        for (attempts in 1..3) {
            val resp = apiProvider(token).getSlimapiSessions(directories, roots, limit, null)
            // The sessions route is also a cross-network suspend.  Validate
            // immediately on return, before retry/degrade handling can turn an
            // old-generation response into a seemingly ordinary null piece.
            slimStateMachine.requireSlimTokenCurrent(token)
            if (resp.isSuccessful) {
                val sessions = resp.body() ?: emptyList()
                val headers = resp.headers()
                return@runSuspendCatching SlimSessionsPage(
                    sessions = sessions,
                    complete = headers?.get("X-Complete")?.toBooleanStrictOrNull(),
                    discoveryDirectories = headers?.get("X-Discovery-Directories")?.toIntOrNull(),
                    discoveryReady = headers?.get("X-Discovery-Ready")?.toBooleanStrictOrNull(),
                )
            }
            // Non-2xx: read the sidecar's coded envelope ONCE (errorBody is
            // one-shot). The parsed code drives BOTH the 503+transform_busy
            // retry decision AND the WARN observability log.
            val code = parseErrorCode(resp)
            if (resp.code() == 503 && code == SlimapiErrorCodes.TRANSFORM_BUSY && attempts < 3) {
                val retryAfterMs = retryAfterHeaderToMs(resp.headers()["Retry-After"])
                val delayMs = if (retryAfterMs > 0L) retryAfterMs else backoffMs(attempts)
                delay(delayMs)
                continue
            }
            // Non-503 / non-transform_busy / final attempt → observability + fail.
            if (code != null) {
                DebugLog.w("OpenCodeRepository", "slimapi sessions failed: $code")
            }
            lastException = HttpException(resp)
            break
        }
        throw lastException ?: throw AssertionError("unreachable")
    }

    /** Exponential backoff for sessions 503 retry: 200ms, 400ms with ±30% jitter. */
    private fun backoffMs(attempt: Int): Long {
        val base = exponentialBackoffMs(attempt - 1, 200L, Int.MAX_VALUE)
        val jitterRange = (base * 0.30).toLong()
        val jitter = (Math.random() * (2.0 * jitterRange + 1.0)).toLong() - jitterRange
        return (base + jitter).coerceAtLeast(0L)
    }
}
