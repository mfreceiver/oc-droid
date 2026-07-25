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
) {
    // ── Constants ──────────────────────────────────────────────────────────────────

    internal companion object {
        internal const val SLIM_COLDSTART_SESSION_LIMIT = 500
    }

    // ── Public API: anchored /since fetch ──────────────────────────────────────

    /**
     * §slim-v1-page (Task 5 / G5 cursor): anchored fetch from a [since]
     * bookmark timestamp. Atomically bumps the local SSE state watermark on
     * success — the digest / degrade / resync protocol invariants depend on
     * this being the sole bookmark advancement path for the anchored branch.
     *
     * Pass [since] = 0L for the cold-start path (no prior bookmark).
     */
    suspend fun getSlimapiMessagesSince(
        sessionId: String,
        since: Long,
        limit: Int? = null,
        before: String? = null,
        token: OpenCodeRepository.SlimCommitToken,
    ): Result<List<MessageWithParts>> = runSlimStaleAwareCatching(token) {
        val response = apiProvider(token).getSlimapiMessagesSince(sessionId, since, limit, before)

        slimStateMachine.requireSlimTokenCurrent(token)

        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code()}")
        val items = response.body() ?: emptyList()
        if (!slimStateMachine.bumpSlimBookmarkFromItems(sessionId, items, token)) {
            throw OpenCodeRepository.StaleSlimCommitException()
        }
        // POST-RELEASE instrumentation: per-resync fetch outcome for the
        // SlimapiResync diagnostic surface. One line per anchored fetch.
        DebugLog.d(
            "SlimapiResync",
            "since sid=$sessionId since=$since drained=${items.size} " +
                "newest=${items.lastOrNull()?.info?.id ?: "-"}",
        )
        items
    }

    /**
     * §slim-v1-paging (Task 5 / G5 cursor): Cluster A cursor-paginated
     * skeleton fetch (`GET /slimapi/messages/{sid}?limit=…&before=…&mode=…`).
     * Used for the no-bookmark cold-start branch in [coldStartSlimSync]
     * (which cursor-follows via [drainSlimapiMessagesBounded]) and any
     * future caller that wants to walk older history without an anchor ts.
     *
     * Surfaces BOTH the items AND the `X-Next-Cursor` response header on
     * the returned [MessagesPage] (was: the pre-G5 [getSlimapiMessagesPaged]
     * returned `Result<List<MessageWithParts>>` with no header access — the
     * cursor was discarded; that method was unused and is replaced here).
     * The legacy non-slim analogue is [getMessagesPaged] (legacy branch).
     *
     * Bookmark invariant (rev-gpt MINOR #2, Option A): when [bumpBookmark]
     * is true (default), bumps the local slim SSE watermark to
     * `max(time.updated)` over the returned items — mirrors
     * [getSlimapiMessagesSince] / [getMessagesPaged] slim branch so single-
     * page callers keep the invariant for free. The cursor-following drain
     * ([drainSlimapiMessagesBounded]) passes `bumpBookmark = false` and
     * bumps ONCE at termination from the aggregated items — otherwise each
     * page would bump individually and the "single bump" claim in the
     * drain's KDoc would be inaccurate (the result is correct either way
     * because [bumpSlimBookmarkFromItems] is monotonic, but the doc/code
     * should agree).
     */
    suspend fun getSlimapiMessagesPage(
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
        mode: String? = "skeleton",
        bumpBookmark: Boolean = true,
        token: OpenCodeRepository.SlimCommitToken,
    ): Result<MessagesPage> = runSlimStaleAwareCatching(token) {
        val response = apiProvider(token).getSlimapiMessages(sessionId, limit, before, mode)

        slimStateMachine.requireSlimTokenCurrent(token)

        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code()}")
        val items = response.body() ?: emptyList()
        if (bumpBookmark) {
            if (!slimStateMachine.bumpSlimBookmarkFromItems(sessionId, items, token)) {
                throw OpenCodeRepository.StaleSlimCommitException()
            }
        }
        val nextCursor = response.headers()["X-Next-Cursor"]
        MessagesPage(items = items, nextCursor = nextCursor)
    }

    // ── Cold-start + bounded drain ──────────────────────────────────────────────

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
                runSlimStaleAwareCatching(token) {
                    val response = apiProvider(token).getSlimapiMessagesSince(
                        sid, bookmark, limit = SLIMAPI_DEFAULT_PAGE_LIMIT,
                    )

                    slimStateMachine.requireSlimTokenCurrent(token)

                    if (!response.isSuccessful) {
                        return@runSlimStaleAwareCatching null
                    }

                    val items = response.body() ?: emptyList()

                    if (!slimStateMachine.bumpSlimBookmarkFromItems(sid, items, token)) {
                        throw OpenCodeRepository.StaleSlimCommitException()
                    }

                    items
                }.getOrElse { error ->
                    if (error is OpenCodeRepository.StaleSlimCommitException) throw error
                    null
                }
            } else {
                runSlimStaleAwareCatching(token) {
                    drainSlimapiMessagesBounded(
                        sessionId = sid,
                        pageLimit = SLIMAPI_DEFAULT_PAGE_LIMIT,
                        itemBound = SLIMAPI_LOCAL_HISTORY_BOUND,
                        token = token,
                    )
                }.getOrElse { error ->
                    if (error is OpenCodeRepository.StaleSlimCommitException) throw error
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
     * in a plain `List` (throws on partial / degraded). Used by the cold-start
     * path when no bookmark exists (the no-bookmark branch of
     * [coldStartSlimSync]).
     *
     * See [drainSlimapiMessagesBoundedOutcome] for the full contract.
     */
    internal suspend fun drainSlimapiMessagesBounded(
        sessionId: String,
        pageLimit: Int,
        itemBound: Int,
        token: OpenCodeRepository.SlimCommitToken,
    ): List<MessageWithParts> = drainSlimapiMessagesBoundedOutcome(
        sessionId = sessionId,
        pageLimit = pageLimit,
        itemBound = itemBound,
        token = token,
    ).items

    /**
     * Bounded skeleton-cursor drain (G5 cursor follow).
     *
     * Walks the skeleton-only message window for [sessionId] via cursor-
     * paginated `GET /slimapi/messages/{sid}?limit=…&before=…&mode=skeleton`
     * up to [itemBound] items OR page-count cap (wall-clock 30 s timeout),
     * whichever hits first.
     *
     * Returns ONE OF:
     *  - [SlimDrainOutcome.Success] — the walk terminated cleanly
     *    (cursor-null, item-bound hit, or page-count cap). The local
     *    watermark is advanced ONCE after the aggregated items (single bump).
     *  - [SlimDrainOutcome.Partial] — a mid-walk transport / page failure
     *    (HTTP timeout / 5xx) that MAY carry partial [items] — the local
     *    watermark is NOT advanced (preservation of dirty). Per the
     *    T11 round-4 contract, [bumpBookmarkOnPartialFailure] controls
     *    whether the bookmark is bumped (default = true, but the T11
     *    reconcile façade [fetchSlimInitialWindowBounded] passes `false`
     *    to NOT bump on partial).
     *  - [SlimDrainOutcome.Degraded] — loop / zero-progress detection.
     *    Same contract as Partial: no watermark advance.
     *
     * BOOKMARK INVARIANT: The watermark is bumped ONCE from the aggregated
     * [items] irrespective of how many pages contributed (single bump).
     * The internal pages all pass `bumpBookmark = false` so each page does
     * NOT individually bump, avoiding the over-bump hazard if a page failure
     * had already bumped before the drain restarted. The final bump occurs
     * in the Success/Partial/Degraded arm.
     *
     * G-F1 safety: wall-clock bound of 30 s. On timeout, surface as Partial
     * (accumulated items preserved, dirty preserved, watermark NOT advanced —
     * even for the T11 façade, so the next reconcile's `needsCatchUp`
     * (which compares probe vs `localApplied*`, NOT dirty) could see
     * "aligned" if the partial window included the server's latest, then
     * `markSlimReconcileAligned` cleared dirty and the cursor walk switched
     * to `/since/{partial-watermark}` → older pages permanently lost.
     */
    internal suspend fun drainSlimapiMessagesBoundedOutcome(
        sessionId: String,
        pageLimit: Int,
        itemBound: Int,
        bumpBookmarkOnPartialFailure: Boolean = true,
        token: OpenCodeRepository.SlimCommitToken,
    ): SlimDrainOutcome {
        val aggregated = mutableListOf<MessageWithParts>()
        val seen = HashSet<String>()
        var before: String? = null
        // +1 slack page for the trailing partial; ceil via Int math.
        val maxPages = (itemBound + pageLimit - 1) / pageLimit + 1

        fun commitBookmarkOrThrow() {
            if (!slimStateMachine.bumpSlimBookmarkFromItems(sessionId, aggregated, token)) {
                throw OpenCodeRepository.StaleSlimCommitException()
            }
        }

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
                        bumpBookmark = false,
                        token = token,
                    ).getOrElse { error ->
                        // Stale incarnation is NOT an ordinary partial transport
                        // result; it invalidates the entire aggregate.
                        if (error is OpenCodeRepository.StaleSlimCommitException) {
                            throw error
                        }

                        if (bumpBookmarkOnPartialFailure) {
                            commitBookmarkOrThrow()
                        }

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

                    for (item in page.items) {
                        if (seen.add(item.info.id)) {
                            aggregated += item

                            if (aggregated.size >= itemBound) {
                                commitBookmarkOrThrow()
                                return@withTimeout SlimDrainOutcome.Success(aggregated.toList())
                            }
                        }
                    }

                    if (page.nextCursor == null) {
                        commitBookmarkOrThrow()
                        return@withTimeout SlimDrainOutcome.Success(aggregated.toList())
                    }

                    before = page.nextCursor
                }
                // Page-count safety cap reached (repeat exhausted maxPages).
                commitBookmarkOrThrow()
                SlimDrainOutcome.Success(aggregated.toList())
            }
        } catch (e: TimeoutCancellationException) {
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
     *  - `Result.success(items)` — bounded skeleton cursor drain completed
     *    cleanly (cursor-null, item-bound, or page-count cap). The local
     *    watermark was advanced inside the drain via
     *    [bumpSlimBookmarkFromItems] (single bump from aggregated items).
     *  - `Result.failure(SlimCursorPartialException)` — mid-walk transport /
     *    page failure detected (including loop/zero-progress), OR the
     *    drain's max-page cap was reached with a non-null cursor still
     *    pending (the partial cross-page aggregate is NOT surfaced — the
     *    reconciler treats any Partial the same: preserve dirty). The local
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
                bumpBookmarkOnPartialFailure = false,
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
