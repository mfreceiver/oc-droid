package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.SlimapiMessageFullBatch
import cn.vectory.ocdroid.data.repository.http.SlimapiErrorCodes
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.exponentialBackoffMs
import cn.vectory.ocdroid.util.runSuspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.log2

/**
 * §L4a1 / ζ-2 (plan v3, Wave ζ): behavior-preserving extraction of the
 * expand-batch engine out of [OpenCodeRepository] (OCR). Holds the budget-
 * aware binary partition-tree expand (`expandMessagesFullBatch` /
 * `drivePartition` / `mergeResults` / `exhaustedOutcome` / `backoffMs` /
 * `fallbackSingleFull`), its constants, the two stateful dedup/cache maps,
 * and the three test seams — moved verbatim. OCR keeps thin delegates for
 * `expandMessagesFullBatch`, `invalidateThinRouteCache`, and the test seams
 * so every existing caller (PartExpandState, ExpandPartsUseCase, all tests)
 * resolves unchanged.
 *
 * ## Preserved invariants (L4a0 invariant map)
 *
 * **I3 (CRITICAL) — per-call client reference.** The expand flow reads
 * `api.getSlimapiMessagesFullBatch` / `api.getSlimapiMessageFull`. In OCR
 * these were direct reads of the `api` field, which `rebuildClients()`
 * reassigns on `configure()`. To preserve this, the engine receives
 * [apiProvider] (`() -> OpenCodeApi`) and re-reads the current client
 * **on every call** (`apiProvider().getSlimapiMessagesFullBatch(...)`).
 * OCR wires `apiProvider = { api }` — a lambda that captures `this` and
 * reads the `api` field fresh per invocation, so a `configure()` rebuild
 * takes effect on the NEXT expand call. The client is NEVER cached at
 * construction (caching would freeze a stale client past a host switch).
 *
 * **hostPort-live.** [hostPortProvider] (`() -> String`) returns
 * `hostConfig.hostPort ?: ""` live each call; a host switch re-keys both
 * the single-flight map and the thin-route cache. NEVER cached.
 *
 * **I16 — shared helpers STAY in OCR.** [parseErrorCode] /
 * [parseErrorCodeFromRaw] are OCR `internal fun`s also used by the drain /
 * health / status paths — they are NOT moved; the engine receives them as
 * injected lambdas. The drain methods (`drainSlimapiMessagesBounded` etc.)
 * also stay in OCR (shared with `coldStartSlimSync`).
 *
 * **I10/I11 — cache + single-flight lifecycle preserved verbatim.** The
 * engine OWNS [thinRouteNotFoundCache] + [singleFlightMap]. As in the
 * pre-extraction code, OCR's `configure()` does NOT clear these maps on a
 * host switch (a latent leak: stale thin-route "not found" timestamps and
 * single-flight entries for an old host linger). This extraction preserves
 * that behavior EXACTLY — clearing on `configure()` is an L4a3 concern.
 *
 * **Type FQN.** [ExpandOutcome] is a top-level sealed interface in
 * `SlimapiExpandOutcome.kt` (unchanged). [ExpandBudgetCounters] was a
 * nested OCR class but is referenced by NO caller as
 * `OpenCodeRepository.ExpandBudgetCounters` (tests read it via
 * `repository.lastExpandBudgetCounters`), so it is promoted to a top-level
 * data class in this file (FQN `cn.vectory.ocdroid.data.repository.ExpandBudgetCounters`).
 *
 * **Logcat observability.** [TAG] stays `"OpenCodeRepository"` so the
 * expand diagnostics remain byte-identical in `adb logcat`.
 */
internal class ExpandBatchEngine(
    /**
     * §I3: returns the CURRENT `api` (re-read each call). OCR wires this to
     * `{ api }` so `configure()`'s `rebuildClients()` reassignment is visible
     * to the next expand call. MUST be per-call — never cached.
     */
    private val apiProvider: () -> OpenCodeApi,
    /** hostPort-live: returns `hostConfig.hostPort ?: ""` each call. */
    private val hostPortProvider: () -> String,
    /** §I16: OCR's shared error-code parser (also used by drain/health/status). */
    private val parseErrorCode: (retrofit2.Response<*>) -> String?,
    /** §I16: OCR's shared raw-body error-code parser. */
    private val parseErrorCodeFromRaw: (String?) -> String?,
    /** OCR's pure Retry-After parser (tested on OCR's surface) — stays in OCR. */
    private val retryAfterHeaderToMs: (String?) -> Long,
) {
    /**
     * §O-A: per-host thin_route_not_found timestamp cache.
     * Keyed by hostPort (host:port authority from [hostPortProvider]).
     * Value is the epoch ms when THIN_ROUTE_NOT_FOUND was last seen.
     * TTL = [THIN_ROUTE_NOT_FOUND_TTL_MS].
     *
     * §I10/I11: NOT cleared on configure() — see class KDoc (latent leak).
     */
    @Volatile
    private var thinRouteNotFoundCache: MutableMap<String, Long> = ConcurrentHashMap<String, Long>()

    /**
     * §O-A: single-flight map keyed by `"$hostPort:$sessionId:$messageId"`.
     * Ensures ≤1 in-flight request per mid across concurrent expand calls.
     *
     * §I10/I11: NOT cleared on configure() — see class KDoc (latent leak).
     */
    private val singleFlightMap = ConcurrentHashMap<String, CompletableDeferred<Result<MessageWithParts>>>()

    /** Test seam: last [ExpandBudgetCounters] from [expandMessagesFullBatch]. */
    internal var lastExpandBudgetCounters: ExpandBudgetCounters? = null

    /** Test seam: injectable wall-clock budget (ms) for [expandMessagesFullBatch]. */
    internal var expandWallClockBudgetMsForTest: Long? = null

    /** Test seam: injectable TTL (ms) for thin-route cache in [drivePartition]. */
    internal var thinRouteTtlMsForTest: Long? = null

    /**
     * §5 G6 + §O-A — budget-aware partition tree expand for multiple messages full.
     *
     * Entry point. Dedup + cap ids, then drive a bounded binary partition tree
     * with per-node retry, concurrency ≤2, wall-clock ≤30s, single-flight dedup.
     *
     * Returns [ExpandOutcome] with the usual branches; adds [ExpandOutcome.Failed.exhausted]
     * marker when the operation cannot finish within budget.
     */
    suspend fun expandMessagesFullBatch(
        sessionId: String,
        ids: Collection<String>,
    ): ExpandOutcome {
        // Step 1: dedup preserve order, coerce 1..20.
        val deduped = ids.toCollection(LinkedHashSet()).toList()
        if (deduped.isEmpty()) return ExpandOutcome.Failed(sessionId, code = null, exhausted = false)
        val capped = if (deduped.size > EXPAND_BATCH_MAX_IDS) {
            DebugLog.w(
                TAG,
                "expandMessagesFullBatch: ${deduped.size} ids > $EXPAND_BATCH_MAX_IDS cap; " +
                    "truncating to first $EXPAND_BATCH_MAX_IDS (sid=$sessionId)",
            )
            deduped.take(EXPAND_BATCH_MAX_IDS)
        } else {
            deduped
        }
        lastExpandBudgetCounters = null
        val deadline = java.lang.System.currentTimeMillis() + EXPAND_MAX_WALLCLOCK_MS
        val counters = ExpandBudgetCounters(
            peakConcurrentPartitionRequests = 0,
            totalHttpAttempts = 0,
            partitionNodesCreated = 0,
            wallClockMs = 0L,
        )
        val requestSemaphore = Semaphore(PARTITION_MAX_CONCURRENT)
        val concurrentRequests = AtomicInteger(0)
        val globalMaxDepth = ceil(log2(capped.size.toDouble())).toInt().coerceAtLeast(1)
        val hostPort = hostPortProvider()
        val result = try {
            withTimeout(expandWallClockBudgetMsForTest ?: EXPAND_MAX_WALLCLOCK_MS) {
                coroutineScope {
                    drivePartition(
                        sessionId = sessionId,
                        ids = capped,
                        depth = 0,
                        globalMaxDepth = globalMaxDepth,
                        deadline = deadline,
                        counters = counters,
                        singleFlightMap = singleFlightMap,
                        hostPort = hostPort,
                        requestSemaphore = requestSemaphore,
                        concurrentRequests = concurrentRequests,
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            exhaustedOutcome(sessionId, capped)
        }
        // Log counters
        counters.wallClockMs = java.lang.System.currentTimeMillis() - (deadline - EXPAND_MAX_WALLCLOCK_MS)
        DebugLog.i(
            TAG,
            "expandMessagesFullBatch done: peakConcurrent=${counters.peakConcurrentPartitionRequests} " +
                "httpAttempts=${counters.totalHttpAttempts} nodes=${counters.partitionNodesCreated} " +
                "wallMs=${counters.wallClockMs}",
        )
        lastExpandBudgetCounters = counters
        return result
    }

    /**
     * §O-A — drive one partition node of the binary partition tree.
     *
     * @param ids the message ids assigned to this partition node (non-empty).
     * @param depth current tree depth (root = 0); used for max-depth check.
     * @param deadline wall-clock deadline in epoch ms.
     * @param counters mutable counters for the operation.
     * @param singleFlightMap shared map keyed by host+sid+mid for single-flight.
     * @param hostPort the current host:port authority for single-flight key.
     * @param requestSemaphore semaphore bounding concurrent HTTP requests.
     * @param concurrentRequests atomic counter for peak concurrency measurement.
     */
    private suspend fun drivePartition(
        sessionId: String,
        ids: List<String>,
        depth: Int,
        globalMaxDepth: Int,
        deadline: Long,
        counters: ExpandBudgetCounters,


        singleFlightMap: ConcurrentHashMap<String, CompletableDeferred<Result<MessageWithParts>>>,
        hostPort: String,
        requestSemaphore: Semaphore,
        concurrentRequests: AtomicInteger,
    ): ExpandOutcome {
        // Budget guard
        if (java.lang.System.currentTimeMillis() > deadline) {
            return exhaustedOutcome(sessionId, ids)
        }

        // Max depth guard: ⌈log₂N_total⌉ edges. N_total=ids in entire batch.
        if (depth > globalMaxDepth) {
            return exhaustedOutcome(sessionId, ids)
        }

        // Increment node count
        counters.partitionNodesCreated++

        // m8 thin-route cache: if hostPort known and cache hit within TTL, skip probe
        if (hostPort.isNotEmpty()) {
            val now = java.lang.System.currentTimeMillis()
            val cached = thinRouteNotFoundCache[hostPort]
            if (cached != null) {
                val elapsed = now - cached
                val ttl = thinRouteTtlMsForTest ?: THIN_ROUTE_NOT_FOUND_TTL_MS
                if (elapsed >= 0L && elapsed < ttl) {
                    // Within TTL — skip probe, go direct to fallbackSingleFull
                    return fallbackSingleFull(sessionId, ids, singleFlightMap)
                }
            }
        }

        // ── attempt loop (≤3 per node) ──────────────────────────────────
        var currentIds = ids
        var attempts = 0
        lateinit var resp: retrofit2.Response<SlimapiMessageFullBatch>
        val itemsMap = HashMap<String, MessageWithParts>()
        val terminalFailures = mutableListOf<ExpandOutcome.MessageFailure>()
        val unknownFailures = mutableListOf<ExpandOutcome.MessageFailure>()
        while (attempts < 3) {
            if (java.lang.System.currentTimeMillis() > deadline) {
                return exhaustedOutcome(sessionId, ids)
            }

            // Acquire concurrency semaphore
            requestSemaphore.acquire()
            val cur = concurrentRequests.incrementAndGet()
            // Update peak
            if (cur > counters.peakConcurrentPartitionRequests) {
                counters.peakConcurrentPartitionRequests = cur
            }
            try {
                resp = apiProvider().getSlimapiMessagesFullBatch(
                    sessionId = sessionId,
                    ids = currentIds.joinToString(","),
                    mode = "full",
                )
                counters.totalHttpAttempts++
                attempts++
            } catch (e: CancellationException) {
                requestSemaphore.release()
                concurrentRequests.decrementAndGet()
                if (e is TimeoutCancellationException) {
                    return exhaustedOutcome(sessionId, ids)
                }
                throw e
            } catch (e: IOException) {
                requestSemaphore.release()
                concurrentRequests.decrementAndGet()
                counters.totalHttpAttempts++
                attempts++
                DebugLog.w(TAG, "drivePartition IOException ids=$ids sid=$sessionId " +
                    "cause=${e.javaClass.simpleName}: ${e.message}")
                if (attempts < 3) {
                    val delayMs = backoffMs(attempts)
                    delay(delayMs)
                    continue
                } else {
                    return exhaustedOutcome(sessionId, ids)
                }
            } catch (e: Exception) {
                requestSemaphore.release()
                concurrentRequests.decrementAndGet()
                // Defensive — collapse to exhausted to avoid hard crash
                return exhaustedOutcome(sessionId, ids)
            }
            requestSemaphore.release()
            concurrentRequests.decrementAndGet()

            // HTTP call succeeded — branch on status
            when {
                resp.isSuccessful -> {
                    // ── 200 envelope ──────────────────────────────────────────
                    val env = resp.body() ?: SlimapiMessageFullBatch()
                    if (env.items.isEmpty() && env.errors.isEmpty()) {
                        DebugLog.w(TAG, "drivePartition 200 empty envelope ids=$currentIds sid=$sessionId")
                    }

                    // Merge items (accumulate across iterations)
                    for (item in env.items) {
                        // items win over errors — if already in itemsMap, skip error accumulation later
                        itemsMap[item.info.id] = item
                    }

                    // Classify errors
                    val retryableFailures = mutableListOf<ExpandOutcome.MessageFailure>()
                    for (err in env.errors) {
                        val mid = err.messageId
                        val code = err.code
                        when {
                            code == SlimapiErrorCodes.MESSAGE_NOT_FOUND ||
                                code == SlimapiErrorCodes.MESSAGE_TOO_LARGE ||
                                (code == null && err.code == null) -> {
                                // mid-terminal — no retry, keep skeleton
                                terminalFailures += ExpandOutcome.MessageFailure(mid, code)
                            }
                            code?.startsWith(SlimapiErrorCodes.UPSTREAM_HTTP_PREFIX) == true ||
                                code == SlimapiErrorCodes.UPSTREAM_UNAVAILABLE -> {
                                // mid-retryable — bounded retry only this mid
                                retryableFailures += ExpandOutcome.MessageFailure(mid, code)
                            }
                            else -> {
                                // unknown code → fail-open to auto-recovery exhausted
                                unknownFailures += ExpandOutcome.MessageFailure(mid, code)
                            }
                        }
                    }

                    // Unknown failures always exhaust immediately
                    if (unknownFailures.isNotEmpty()) {
                        return exhaustedOutcome(sessionId, currentIds)
                    }

                    // If there are retryable failures, retry them in-place
                    if (retryableFailures.isNotEmpty()) {
                        // If we still have attempts left, retry only the retryable mids
                        if (attempts < 3) {
                            currentIds = retryableFailures.map { it.messageId }
                            continue
                        } else {
                            // No attempts left — move retryable to terminal with exhaustion marker
                            for (mid in retryableFailures.map { it.messageId }) {
                                terminalFailures += ExpandOutcome.MessageFailure(mid, null)
                            }
                        }
                    }

                    // No retryable remaining (or exhausted) — return accumulated result
                    DebugLog.i(TAG, "drivePartition 200 OK ids=${currentIds.joinToString(",")} depth=$depth items=${itemsMap.size} failures=${terminalFailures.size}")
                    return ExpandOutcome.Ok(
                        items = itemsMap.values.toList(),
                        failures = terminalFailures,
                        usedBatch = true,
                    )
                }

                resp.code() == 404 -> {
                    val code = parseErrorCode(resp)
                    when (code) {
                        SlimapiErrorCodes.SESSION_NOT_FOUND -> {
                            return ExpandOutcome.SessionMissing(sessionId)
                        }
                        SlimapiErrorCodes.THIN_ROUTE_NOT_FOUND -> {
                            // Record timestamp for thin-route cache (if not already set)
                            if (hostPort.isNotEmpty()) {
                                val now = java.lang.System.currentTimeMillis()
                                // Only set if absent, to avoid rewriting with fresh timestamp on each fallback
                                thinRouteNotFoundCache.putIfAbsent(hostPort, now)
                            }
                            return fallbackSingleFull(sessionId, currentIds, singleFlightMap)
                        }
                        else -> {
                            return ExpandOutcome.Failed(sessionId, code, exhausted = false)
                        }
                    }
                }

                resp.code() == 413 -> {
                    val code = parseErrorCode(resp)
                    if (code == SlimapiErrorCodes.MESSAGE_TOO_LARGE) {
                        // mid-terminal — all ids are too large at any batch size
                        return ExpandOutcome.Ok(
                            items = emptyList(),
                            failures = currentIds.map { ExpandOutcome.MessageFailure(it, code) },
                            usedBatch = true,
                        )
                    }
                    // RESPONSE_TOO_LARGE or unknown — split into both halves and recurse
                    if (currentIds.size <= 1) {
                        // singleton 413 → mid-terminal (per §5 'singleton 413→mid 终态')
                        return ExpandOutcome.Ok(
                            items = emptyList(),
                            failures = currentIds.map { ExpandOutcome.MessageFailure(it, code) },
                            usedBatch = true,
                        )
                    }
                    val mid = currentIds.size / 2
                    val result = coroutineScope {
                        val left = async {
                            drivePartition(
                                sessionId = sessionId,
                                ids = currentIds.subList(0, mid),
                                depth = depth + 1,
                                globalMaxDepth = globalMaxDepth,
                                deadline = deadline,
                                counters = counters,
                                singleFlightMap = singleFlightMap,
                                hostPort = hostPort,
                                requestSemaphore = requestSemaphore,
                                concurrentRequests = concurrentRequests,
                            )
                        }
                        val right = async {
                            drivePartition(
                                sessionId = sessionId,
                                ids = currentIds.subList(mid, currentIds.size),
                                depth = depth + 1,
                                globalMaxDepth = globalMaxDepth,
                                deadline = deadline,
                                counters = counters,
                                singleFlightMap = singleFlightMap,
                                hostPort = hostPort,
                                requestSemaphore = requestSemaphore,
                                concurrentRequests = concurrentRequests,
                            )
                        }
                        mergeResults(left.await(), right.await(), currentIds)
                    }
                    return result
                }

                resp.code() == 503 -> {
                    // Read Retry-After header
                    val retryAfterMs = retryAfterHeaderToMs(resp.headers()["Retry-After"])
                    val delayMs = if (retryAfterMs > 0L) retryAfterMs else backoffMs(attempts)
                    if (attempts < 3) {
                        delay(delayMs)
                        continue  // retry the SAME node ids (not a fan-out)
                    } else {
                        // Exhausted retries for this node
                        return exhaustedOutcome(sessionId, ids)
                    }
                }

                else -> {
                    // Other 4xx/5xx
                    val rawBody = runCatching { resp.errorBody()?.string() }.getOrNull()
                    val code = parseErrorCodeFromRaw(rawBody)
                    return ExpandOutcome.Failed(sessionId, code, exhausted = false)
                }
            }
        }

        // If we exit the while loop naturally (should not happen — we always return above)
        return exhaustedOutcome(sessionId, ids)
    }

    /**
     * §O-A — merge two [ExpandOutcome] results idempotently.
     * Items and failures are mutually exclusive by messageId.
     */
    private fun mergeResults(
        left: ExpandOutcome,
        right: ExpandOutcome,
        parentIds: List<String>,
    ): ExpandOutcome {
        if (left !is ExpandOutcome.Ok) return left
        if (right !is ExpandOutcome.Ok) return right

        val itemsMap = HashMap<String, MessageWithParts>()
        for (item in left.items) itemsMap[item.info.id] = item
        for (item in right.items) itemsMap[item.info.id] = item

        // Dedup failures: items win over errors; also dedup by messageId (last-wins)
        val failuresMap = mutableMapOf<String, ExpandOutcome.MessageFailure>()
        for (f in left.failures) {
            if (f.messageId !in itemsMap) {
                failuresMap[f.messageId] = f
            }
        }
        for (f in right.failures) {
            if (f.messageId !in itemsMap) {
                failuresMap[f.messageId] = f
            }
        }

        return ExpandOutcome.Ok(
            items = itemsMap.values.toList(),
            failures = failuresMap.values.toList(),
            usedBatch = true,
        )
    }

    /**
     * §O-A — produce an exhausted [ExpandOutcome.Failed] for the given ids.
     */
    private fun exhaustedOutcome(
        sessionId: String,
        ids: List<String>,
    ): ExpandOutcome.Failed {
        val failures = ids.map { ExpandOutcome.MessageFailure(it, code = null) }
        return ExpandOutcome.Failed(
            sessionId = sessionId,
            code = null,
            exhausted = true,
        )
    }

    /**
     * §O-A — exponential backoff for per-node retry: 200ms, 400ms with ±30% jitter.
     */
    private fun backoffMs(attempt: Int): Long {
        val base = exponentialBackoffMs(attempt - 1, EXPAND_BACKOFF_BASE_MS, Int.MAX_VALUE)
        val jitterRange = (base * EXPAND_BACKOFF_JITTER).toLong()
        val jitter = (Math.random() * (2.0 * jitterRange + 1.0)).toLong() - jitterRange
        return (base + jitter).coerceAtLeast(0L)
    }

    /**
     * §5 G6 transitional compatibility — N parallel single-message full
     * fetches when the sidecar hasn't deployed the batch endpoint
     * (`404 thin_route_not_found`). Bounded with [Semaphore]
     * ([EXPAND_FALLBACK_CONCURRENCY]) so a 20-id expand doesn't fan out
     * into 20 simultaneous HTTP calls (thundering-herd guard on the
     * sidecar's per-client connection cap).
     *
     * Per-id failures (network exception OR HTTP non-2xx from
     * `getSlimapiMessageFull`) land in [ExpandOutcome.Ok.failures] rather than fail the
     * whole call — the UI marks just those messages' expand state as
     * failed; the rest render their full bodies. This mirrors the
     * batch envelope's per-message `errors[]` semantics on the fallback
     * path so the UI's downstream code (T16) does not branch on
     * `usedBatch`.
     */
    private suspend fun fallbackSingleFull(
        sessionId: String,
        ids: List<String>,
        singleFlightMap: ConcurrentHashMap<String, CompletableDeferred<Result<MessageWithParts>>>,
    ): ExpandOutcome.Ok = coroutineScope {
        val sem = Semaphore(EXPAND_FALLBACK_CONCURRENCY)
        val hostPort = hostPortProvider()
        val results = ids.map { id ->
            async {
                sem.withPermit {
                    val key = "$hostPort:$sessionId:$id"
                    // Single-flight: use computeIfAbsent atomically.
                    val newDeferred = CompletableDeferred<Result<MessageWithParts>>()
                    val existing = singleFlightMap.computeIfAbsent(key) { newDeferred }
                    if (existing === newDeferred) {
                        // We won — make the call and complete the deferred
                        try {
                            val result = runSuspendCatching { apiProvider().getSlimapiMessageFull(sessionId, id) }
                            newDeferred.complete(result)
                            id to result
                        } finally {
                            singleFlightMap.remove(key)
                        }
                    } else {
                        // Another call is in-flight — await its result
                        id to existing.await()
                    }
                }
            }
        }.awaitAll()
        val items = mutableListOf<MessageWithParts>()
        val failures = mutableListOf<ExpandOutcome.MessageFailure>()
        for ((id, r) in results) {
            r.onSuccess { items += it }.onFailure { e ->
                DebugLog.w(TAG, "fallbackSingleFull failed for $id: ${e.message}")
                failures += ExpandOutcome.MessageFailure(id, code = null)
            }
        }
        ExpandOutcome.Ok(items = items, failures = failures, usedBatch = false)
    }

    /**
     * Invalidate the thin-route cache for the current host (e.g. on server reconnect).
     * Called by the coordinator when it learns of a new server generation (host reconnect).
     * Documented for external callers — this is the hook for §O-A m8 bypass.
     */
    fun invalidateThinRouteCache() {
        val hp = hostPortProvider()
        if (hp.isNotEmpty()) {
            thinRouteNotFoundCache -= hp
            DebugLog.i(TAG, "Thin-route cache invalidated for host $hp")
        }
    }

    companion object {
        // ── §5 G6 expandMessagesFullBatch retry strategy ──────────────────
        //
        // Pinned as `internal const` so the strategy (1..20 ids, halve on
        // 413, max-3-retry 503 backoff with base 200 ms / ±30% jitter,
        // 4-wide Semaphore-bounded fallback) is readable in one place AND
        // so T3's MockWebServer-driven tests can assert "exactly 4 requests
        // on a 503-exhausted path" without magic numbers leaking into the
        // branches. Changing any of these is a wire-contract change (e.g.
        // the sidecar's `?ids` cap is 20) — they are NOT tunables.

        /** §5 G6: max ids per batch call (server rejects more with 400 invalid_ids). */
        internal const val EXPAND_BATCH_MAX_IDS = 20

        /** §5 G6: max 503 retries before surfacing as Failed (4 total attempts). */
        internal const val EXPAND_MAX_503_RETRIES = 3

        /** §5 G6: base backoff delay in ms (jitter ±30%); doubles per retry. */
        internal const val EXPAND_BACKOFF_BASE_MS = 200L

        /** §5 G6: jitter range as a fraction of base (±30%). */
        internal const val EXPAND_BACKOFF_JITTER = 0.30

        /** §5 G6: parallel single-full fallback concurrency (thundering-herd guard). */
        internal const val EXPAND_FALLBACK_CONCURRENCY = 4

        // ── O-A budget-aware partition tree new constants ────────────────────

        /** §O-A: max concurrent partition HTTP requests in-flight (2). */
        internal const val PARTITION_MAX_CONCURRENT = 2

        /** §O-A: global wall-clock timeout ms for expand operation (30s). */
        internal const val EXPAND_MAX_WALLCLOCK_MS = 30_000L

        /** §O-A: TTL ms for thin_route_not_found cache (60s). */
        internal const val THIN_ROUTE_NOT_FOUND_TTL_MS = 60_000L

        // §L4a1/ζ-2: kept as "OpenCodeRepository" (NOT the class name) so the
        // expand diagnostics stay byte-identical in `adb logcat` after the
        // extraction — observability is part of the behavior contract.
        private const val TAG = "OpenCodeRepository"
    }
}

/**
 * §O-A: counters logged at the end of an expand operation for test verification.
 *
 * §L4a1/ζ-2: promoted to top-level (was nested `OpenCodeRepository.ExpandBudgetCounters`).
 * No caller referenced the nested FQN (tests read it via
 * `repository.lastExpandBudgetCounters`), so the promotion is source-compatible.
 */
data class ExpandBudgetCounters(
    var peakConcurrentPartitionRequests: Int,
    var totalHttpAttempts: Int,
    var partitionNodesCreated: Int,
    var wallClockMs: Long,
)
