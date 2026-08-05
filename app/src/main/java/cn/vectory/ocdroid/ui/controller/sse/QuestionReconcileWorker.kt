package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.SlimAggregationOutcome
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.SlimapiQuestionEntry
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.controller.toQuestionRequest
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * **INVARIANT: all access must occur on Dispatchers.Main.immediate — this is a
 * thread-imprisonment contract, do not introduce concurrent primitives.**
 *
 * Self-contained latest-wins single worker for pending-question reconciliation.
 * Owns the [questionReconcile{Running,Pending,Generation}] state machine and
 * [latestQuestionRepository].
 *
 * Multiple concurrent requests are coalesced: only the latest request's
 * generation survives; earlier in-flight responses are dropped by the
 * generation gate.
 *
 * §slimapi-p3 / §rev-ds ISSUE 2 → §slimapi-questions: branches on
 * `supportsGlobalQuestionFetch && supportsSlimQuestions`:
 *  - **Slim + endpoint on** (both true): single `getSlimapiQuestions()` call
 *    hits the sidecar's cross-directory aggregate (`GET /slimapi/questions`),
 *    which returns ALL pending questions across the configured workdir
 *    allowlist (each entry carries its own `directory`). This fixes the cold-
 *    start bug where the previous `getPendingQuestions(null)` silently
 *    dropped questions whose workdir ≠ upstream `process.cwd()`.
 *  - **Slim + endpoint off** (slim but bit sticky-false) OR **non-slim**:
 *    per-dir fan-out over `recentWorkdirs + currentWorkdir`, calling
 *    `getPendingQuestions(dir)` with an EXPLICIT directory header for EACH dir
 *    — NEVER `getPendingQuestions(null)` again (that resolves to process.cwd()
 *    upstream and hides other-workdir pending questions).
 */
internal class QuestionReconcileWorker(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
    private val settingsManager: SettingsManager? = null,
    private val currentProfileId: (() -> String)? = null,
) {
    // These plain vars depend on Main.immediate thread-imprisonment.
    // Do NOT upgrade to AtomicReference/ConcurrentHashMap — see class kdoc.
    private var questionReconcileRunning = false
    private var questionReconcilePending = false
    private var questionReconcileGeneration = 0L
    private var latestQuestionRepository: OpenCodeRepository? = null

    /**
     * §slimapi-p3 / §rev-ds ISSUE 2 → §slimapi-questions: bounded pending-
     * question refresh with endpoint-vs-fan-out branching.
     *
     * Semantics: the fetch response is authoritative for the fetched scope.
     * SSE events (question.asked) arriving AFTER the response snapshot are
     * applied by the reducer on top — this worker does not claim to preserve
     * race-window arrivals that occurred during the in-flight fetch.
     */
    fun loadPendingQuestionsAllWorkdirs(repository: OpenCodeRepository) {
        latestQuestionRepository = repository
        questionReconcileGeneration += 1L
        if (questionReconcileRunning) {
            questionReconcilePending = true
            return
        }
        questionReconcileRunning = true
        launchLatestQuestionReconcile(repository, questionReconcileGeneration)
    }

    private fun launchLatestQuestionReconcile(repository: OpenCodeRepository, generation: Long) {
        scope.launch {
            try {
                if (repository.supportsGlobalQuestionFetch && repository.supportsSlimQuestions) {
                    // §slimapi-questions: slim path — cross-directory aggregate.
                    repository.getSlimapiQuestions(directories = null)
                        .onSuccess { outcome ->
                            if (generation == questionReconcileGeneration) {
                                applySlimapiQuestionsOutcome(slices, outcome) { state, newQuestions ->
                                    state.copy(pendingQuestions = newQuestions)
                                }
                            }
                        }
                        .onFailure { error ->
                            // §rev-gpt #1 liveness: the slim-questions endpoint failed
                            // — most critically a 404 from an older sidecar, which
                            // InteractionGateway marks sticky-false then throws. Fall
                            // back to per-dir fan-out IN THIS cycle so a cold-start
                            // pending question is NOT hidden until an uncertain "next
                            // reconcile" that may never come (e.g. user stays on a non-
                            // Chat screen; the first server.connected catch-up is
                            // skipped while sseHasConnectedOnce is false). Pending-
                            // question reconcile is low-frequency, so the transient-
                            // failure double-fetch cost is negligible vs. the visibility
                            // gap. The work-order's own gateway kdoc states "the call
                            // site owns the fan-out" — this honours that intent directly.
                            DebugLog.w("QuestionReconcile", "getSlimapiQuestions failed: ${error.message}; falling back to per-dir fan-out")
                            runPerDirFanOut(repository, generation)
                        }
                } else {
                    runPerDirFanOut(repository, generation)
                }
            } finally {
                finishQuestionReconcile()
            }
        }
    }

    /**
     * §rev-ds / §slimapi-questions / §rev-gpt #1: per-directory fan-out — used by
     * the non-slim branch, the slim-but-endpoint-disabled branch, AND the slim-path
     * failure fallback. Each dir gets an EXPLICIT header (never null — null
     * resolves to process.cwd() upstream and hides other-workdir pending questions).
     */
    private suspend fun runPerDirFanOut(repository: OpenCodeRepository, generation: Long) {
        val allDirs = computeLegacyQuestionWorkdirs()
        // §rev-ds round-2 FIX 5 (preserve-on-empty): when no workdirs are known,
        // preserve existing pendingQuestions rather than committing an empty list
        // — discarding pending questions due to transient workdir unavailability
        // could lose user-visible question state.
        if (allDirs.isEmpty()) return
        val allQuestions = mutableListOf<QuestionRequest>()
        for (dir in allDirs) {
            repository.getPendingQuestions(dir)
                .onSuccess { questions -> allQuestions.addAll(questions) }
                .onFailure { error ->
                    DebugLog.w("QuestionReconcile", "getPendingQuestions($dir) failed: ${error.message}")
                }
        }
        if (generation == questionReconcileGeneration) {
            slices.mutateSessionList { state ->
                state.copy(pendingQuestions = allQuestions)
            }
        }
    }

    /**
     * §rev-ds: computes the set of workdirs for the per-dir fan-out.
     * Mirrors the pre-P3 logic: recent workdirs + current workdir.
     */
    private fun computeLegacyQuestionWorkdirs(): List<String> {
        val sm = settingsManager ?: return emptyList()
        val pid = currentProfileId?.invoke() ?: return emptyList()
        val recentWds = sm.getRecentWorkdirs(pid)
        val currentWd = sm.currentWorkdir
        return (recentWds + listOfNotNull(currentWd))
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun finishQuestionReconcile() {
        if (questionReconcilePending) {
            questionReconcilePending = false
            launchLatestQuestionReconcile(
                repository = latestQuestionRepository ?: return,
                generation = questionReconcileGeneration,
            )
        } else {
            questionReconcileRunning = false
        }
    }
}

/**
 * §slimapi-questions: maps a typed [SlimAggregationOutcome] of
 * [SlimapiQuestionEntry] onto the `pendingQuestions` slice via [mutate].
 *
 *  - [SlimAggregationOutcome.Success] with `authoritativeDirectories == null`
 *    → full replace (the response is globally authoritative).
 *  - [SlimAggregationOutcome.Success] with an explicit directory set →
 *    defensive: replace only entries whose directory ∈ the set; keep others.
 *  - [SlimAggregationOutcome.Partial] → keep local entries whose directory ∉
 *    `authoritativeDirectories`; add the incoming `items` (replace covered
 *    dirs, keep uncovered/failed).
 *  - [SlimAggregationOutcome.Failure] → keep local entirely (no-op).
 *
 * Top-level so the same mapping logic is shared with
 * [cn.vectory.ocdroid.ui.controller.ForegroundCatchUpController].
 *
 * Each [SlimapiQuestionEntry] is mapped to a [QuestionRequest] via
 * [toQuestionRequest] which PRESERVES the entry's `directory` + `routeToken`
 * so the slim reply/reject path can route the write to the originating
 * upstream instance.
 */
internal fun applySlimapiQuestionsOutcome(
    slices: SliceFlows,
    outcome: SlimAggregationOutcome<SlimapiQuestionEntry>,
    mutate: (cn.vectory.ocdroid.ui.SessionListState, List<QuestionRequest>) -> cn.vectory.ocdroid.ui.SessionListState,
) {
    slices.mutateSessionList { state ->
        when (outcome) {
            is SlimAggregationOutcome.Success -> {
                // §rev-gpt #2 / §slimapi-questions: sidecar allowlist not ready
                // (scope.directories == 0) → the (possibly empty) items are NON-
                // authoritative; retain prior state so an empty startup response
                // does NOT false-clear stale pending questions. Mirrors
                // applyAggregationOutcome (SseSessionListReducers T2 gate).
                if (outcome.serverScope?.directories == 0) return@mutateSessionList state

                val authDirs = outcome.authoritativeDirectories
                // Global (authDirs == null) → keep all fetched items; scoped →
                // filter fetched to in-scope dirs only (reject out-of-scope /
                // null-dir, defensive against a malformed envelope — mirrors
                // applyAggregationOutcome §3.5).
                val incomingMapped = outcome.items
                    .map { it.toQuestionRequest() }
                    .let { mapped ->
                        if (authDirs == null) mapped
                        else mapped.filter { q -> q.directory != null && q.directory in authDirs }
                    }
                if (authDirs == null) {
                    // Globally authoritative — full replace.
                    mutate(state, incomingMapped)
                } else {
                    // Scoped: keep prior entries whose directory is NOT covered
                    // (incl. null-dir prior — we can't prove they belong to a
                    // covered dir), then add the in-scope fetched items.
                    val kept = state.pendingQuestions.filterNot { q ->
                        q.directory != null && q.directory in authDirs
                    }
                    mutate(state, kept + incomingMapped)
                }
            }
            is SlimAggregationOutcome.Partial -> {
                if (outcome.serverScope?.directories == 0) return@mutateSessionList state
                // Keep local entries whose directory ∉ authoritativeDirectories;
                // add only in-scope incoming items (replace covered dirs).
                val authDirs = outcome.authoritativeDirectories
                val kept = state.pendingQuestions.filterNot { q ->
                    q.directory != null && q.directory in authDirs
                }
                val inScope = outcome.items
                    .map { it.toQuestionRequest() }
                    .filter { q -> q.directory != null && q.directory in authDirs }
                mutate(state, kept + inScope)
            }
            is SlimAggregationOutcome.Failure -> {
                // Keep local entirely — no-op.
                state
            }
        }
    }
}
