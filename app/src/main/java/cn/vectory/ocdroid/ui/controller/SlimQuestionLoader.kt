package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.SlimapiQuestionEntry
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.SlimAggregationOutcome
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.computeQuestionFanOutWorkdirs
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// ==========================================================================
// P5 extraction: `SlimQuestionLoader`. Owns the legacy all-workdir fan-out
// + the slim single-shot question aggregation. Pure move from SSC — NO
// behavior change.
//
// # Coroutine ownership (§2.1)
//
// The loader holds NO `CoroutineScope`. `planLoad` is synchronous and
// returns a sealed [SlimQuestionLoadCommand]; SSC owns the `scope.launch`
// and calls `execute` inside it. This preserves the timing invariants:
//  - Mode selection synchronous in `planLoad`.
//  - Legacy workdir computation, logging, empty-set early return synchronous.
//  - Legacy `startIds` capture stays AFTER coroutine launch, inside `execute`.
//  - Slim "single-shot" log synchronous.
//  - Slim token capture = first operation inside the launched worker.
//  - Slim workdir + `startIds` snapshots stay after token capture.
//
// See docs/ocmar/plans/2026-07-24-p5-slim-question-loader-design.md.
// ==========================================================================

// ── §4.1 Question repository port ─────────────────────────────────────────

/**
 * P5 §4.1: the narrow repository + token port for the question loader.
 * Per-invocation adapter wraps the F5 caller-supplied [OpenCodeRepository]
 * (do NOT substitute SSC's constructor repository — tests pass different /
 * mock repos).
 *
 * ONE adapter ([OpenCodeSlimQuestionRepositoryPort]); each method delegates
 * 1:1 to the corresponding method on [delegate].
 */
internal interface SlimQuestionRepositoryPort {
    fun captureCommitToken(): OpenCodeRepository.SlimCommitToken
    fun isCommitTokenCurrent(token: OpenCodeRepository.SlimCommitToken): Boolean
    fun commitIfTokenCurrent(
        token: OpenCodeRepository.SlimCommitToken,
        commit: () -> Unit,
    ): Boolean
    fun isStaleFailure(error: Throwable): Boolean
    suspend fun getPendingQuestions(directory: String?): Result<List<QuestionRequest>>
    suspend fun getSlimQuestions(
        directories: List<String>?,
        token: OpenCodeRepository.SlimCommitToken,
    ): Result<SlimAggregationOutcome<SlimapiQuestionEntry>>
}

/**
 * P5 §4.1: the sole [SlimQuestionRepositoryPort] adapter. Each method
 * delegates 1:1 to the corresponding method on [delegate].
 *
 * Verified against `OpenCodeRepository.kt`:
 *  - `captureSlimCommitToken()` / `isSlimCommitTokenCurrent(token)` /
 *    `commitIfSlimTokenCurrent(token, commit)` /
 *    `getPendingQuestions(directory)` / `getSlimapiQuestions(directories, token)`.
 *  - `StaleSlimCommitException` is the typed stale-marker exception.
 */
internal class OpenCodeSlimQuestionRepositoryPort(
    private val delegate: OpenCodeRepository,
) : SlimQuestionRepositoryPort {
    override fun captureCommitToken(): OpenCodeRepository.SlimCommitToken =
        delegate.captureSlimCommitToken()

    override fun isCommitTokenCurrent(token: OpenCodeRepository.SlimCommitToken): Boolean =
        delegate.isSlimCommitTokenCurrent(token)

    override fun commitIfTokenCurrent(
        token: OpenCodeRepository.SlimCommitToken,
        commit: () -> Unit,
    ): Boolean = delegate.commitIfSlimTokenCurrent(token, commit)

    override fun isStaleFailure(error: Throwable): Boolean =
        error is OpenCodeRepository.StaleSlimCommitException

    override suspend fun getPendingQuestions(directory: String?): Result<List<QuestionRequest>> =
        delegate.getPendingQuestions(directory)

    override suspend fun getSlimQuestions(
        directories: List<String>?,
        token: OpenCodeRepository.SlimCommitToken,
    ): Result<SlimAggregationOutcome<SlimapiQuestionEntry>> =
        delegate.getSlimapiQuestions(directories = directories, token = token)
}

// ── §2.1 Launch command (sealed; SSC owns the launch) ────────────────────

/**
 * P5 §2.1: the synchronous launch decision returned by
 * [SlimQuestionLoader.planLoad]. SSC interprets it and launches
 * [SlimQuestionLoader.execute] on its existing `scope`.
 *
 *  - [None] — no work (empty workdir set for legacy).
 *  - [LoadLegacy] — legacy multi-workdir fan-out (workdirs pre-computed).
 *  - [LoadSlim] — slim single-shot `/slimapi/questions` aggregation.
 */
internal sealed interface SlimQuestionLoadCommand {
    data object None : SlimQuestionLoadCommand
    data class LoadLegacy(
        val repository: SlimQuestionRepositoryPort,
        val workdirs: List<String>,
    ) : SlimQuestionLoadCommand
    data class LoadSlim(
        val repository: SlimQuestionRepositoryPort,
    ) : SlimQuestionLoadCommand
}

// ── §2.1 SlimQuestionLoader ───────────────────────────────────────────────

/**
 * P5 §2.1: owns the legacy all-workdir fan-out + slim single-shot question
 * aggregation. Pure move from SSC — NO behavior change. Owns **no mutable
 * long-lived state** (token / startIds / workdirs / results are
 * invocation-local).
 *
 * # Symbol resolution (preemptive scoping)
 *
 * Every original SSC symbol reference resolves via:
 *  - (a) an injected port ([store] / [effects]) or lambda
 *    ([supportsWatermarkResync] / [currentWorkdir] / [recentWorkdirs]).
 *  - (b) a shared same-package helper (`computeQuestionFanOutWorkdirs`,
 *    `allSessionsById`, `filterArchivedSessionQuestions`,
 *    `applyAggregationOutcome`, `aggregationSignal`,
 *    `SlimapiQuestionEntry::toQuestionRequest`).
 *  - (c) shared types (`DebugLog`, `R`, `UiEvent`).
 * NO SSC reference/callback held. NO `CoroutineScope` held.
 */
internal class SlimQuestionLoader(
    private val store: SlimReconcileStorePort,
    private val effects: SlimEffectsPort,
    private val supportsWatermarkResync: () -> Boolean,
    private val currentWorkdir: () -> String?,
    private val recentWorkdirs: () -> List<String>,
    private val tag: String = "SessionSyncCoordinator",
) {
    /**
     * P5 §2.1: synchronous launch decision. Mode selection, legacy workdir
     * computation, logging, and empty-set early return all happen HERE
     * (before the coroutine launch). SSC calls this, then launches
     * [execute] on its `scope`.
     */
    internal fun planLoad(repository: SlimQuestionRepositoryPort): SlimQuestionLoadCommand {
        // Cluster A / Phase 2 (P2.3): slim mode aggregates cross-directory
        // pending questions in ONE `/slimapi/questions` call (routeToken
        // preserved). Legacy keeps the multi-workdir fan-out.
        if (supportsWatermarkResync()) {
            DebugLog.d("Question", "loadPendingQuestionsAllWorkdirs slim single-shot")
            return SlimQuestionLoadCommand.LoadSlim(repository)
        }
        // §issue-1 Phase 2a Fix B: shared workdir-set computation (with per-fp
        // recent_workdirs) — identical to AppCore's catchUpWorkdirs site, via
        // the [computeQuestionFanOutWorkdirs] helper, so the two sites cannot drift.
        val workdirs = computeQuestionFanOutWorkdirs(
            directorySessionKeys = store.currentSessionList().directorySessions.keys,
            currentWorkdir = currentWorkdir(),
            recentWorkdirs = recentWorkdirs(),
        )
        // §Phase1a instrumentation (Issue 1): the full workdir SET being fanned out.
        DebugLog.d("Question", "loadPendingQuestionsAllWorkdirs fanOut=${workdirs.size} workdirs=$workdirs")
        if (workdirs.isEmpty()) return SlimQuestionLoadCommand.None
        return SlimQuestionLoadCommand.LoadLegacy(repository, workdirs)
    }

    /**
     * P5 §2.1: the launched worker body. SSC calls this inside
     * `scope.launch { slimQuestionLoader.execute(command) }`.
     *
     * Legacy `startIds` capture + slim token capture happen HERE (inside
     * the launched coroutine), preserving the original timing invariants.
     */
    internal suspend fun execute(command: SlimQuestionLoadCommand) {
        when (command) {
            SlimQuestionLoadCommand.None -> Unit
            is SlimQuestionLoadCommand.LoadLegacy -> executeLegacy(command.repository, command.workdirs)
            is SlimQuestionLoadCommand.LoadSlim -> executeSlim(command.repository)
        }
    }

    /**
     * P5 (moved from SSC `loadPendingQuestionsAllWorkdirs` legacy branch):
     * fan out to EVERY known workdir in parallel, then reconcile
     * AUTHORITATIVELY (server is source of truth).
     *
     * Race-safety: a question.asked SSE event that lands DURING the fan-out
     * (after the start snapshot, not yet in any in-flight GET response) is
     * preserved — only questions present at start AND absent from the server
     * response are treated as resolved-and-dropped.
     */
    private suspend fun executeLegacy(
        repository: SlimQuestionRepositoryPort,
        workdirs: List<String>,
    ) {
        // §badge-stale-fix: fan out to EVERY known workdir in parallel, then
        // reconcile AUTHORITATIVELY (server is source of truth). Unlike the
        // single-workdir optimistic path [launchLoadPendingQuestions] — which
        // keeps locally-held questions to avoid flicker — this sweep covers the
        // full known-workdir set at once, so its union is authoritative. A
        // question the server no longer returns (resolved without the client
        // receiving the resolve event, e.g. a missed SSE gap while backgrounded)
        // is dropped here instead of lingering as a ghost that keeps the
        // Sessions nav badge lit forever. Matches launchLoadPendingPermissions
        // (full replace) semantics.
        //
        // Race-safety: a question.asked SSE event that lands DURING the fan-out
        // (after the start snapshot, not yet in any in-flight GET response) is
        // preserved — only questions present at start AND absent from the server
        // response are treated as resolved-and-dropped.
        val startIds = store.currentSessionList().pendingQuestions
            .mapTo(mutableSetOf()) { it.id }
        val fetched = coroutineScope {
            workdirs.map { dir ->
                async {
                    repository.getPendingQuestions(dir)
                        .onSuccess { questions ->
                            DebugLog.d("Question", "loadPendingQuestionsAllWorkdirs dir=$dir count=${questions.size}")
                        }
                        .onFailure { error ->
                            DebugLog.w(tag, "fan-out getPendingQuestions failed for $dir: ${error.message}")
                        }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
        }
        val fetchedIds = mutableSetOf<String>()
        // §task5-ghost (final-review fix 2): snapshot the three-source
        // sessions map BEFORE the merge so the filter can identify
        // archived-session questions. A question whose session is marked
        // archived in the local snapshot is dropped here even if the server
        // still returns it — the archive reducer already cleared it from
        // the presentation domain, and letting it back in would relight
        // the Sessions nav badge for a session the user cannot open.
        val slSnap = store.currentSessionList()
        val sessionsById = allSessionsById(
            slSnap.sessions,
            slSnap.directorySessions,
            slSnap.childSessions,
        )
        val authoritative = buildList {
            fetched.flatten().forEach { if (fetchedIds.add(it.id)) add(it) }
            slSnap.pendingQuestions.forEach { q ->
                if (q.id !in fetchedIds && q.id !in startIds) add(q)
            }
        }.let { filterArchivedSessionQuestions(it, sessionsById) }
        store.mutateSessionList { it.copy(pendingQuestions = authoritative) }
        DebugLog.d("Question", "loadPendingQuestionsAllWorkdirs authoritative reconcile total=${authoritative.size} (had ${startIds.size} before)")
    }

    /**
     * P5 (moved from SSC `loadPendingQuestionsSlim`): slim single-shot
     * pending-questions load via `getSlimapiQuestions`. Maps each entry to
     * legacy [QuestionRequest] **preserving [QuestionRequest.routeToken]**.
     * Same authoritative reconcile + archived-session filter as the legacy
     * fan-out.
     *
     * C-D3 v2 §2.2: standalone workflow entry — captures ONE token before
     * the network suspend, then guards every slice / signal / effect
     * commit inside a single `commitIfSlimTokenCurrent` block. A stale
     * result is a clean no-op (no slice mutation, no UiEvent).
     */
    private suspend fun executeSlim(repository: SlimQuestionRepositoryPort) {
        // Standalone workflow entry: ONE capture before first suspend.
        val token = repository.captureCommitToken()

        val startIds = store.currentSessionList().pendingQuestions
            .mapTo(mutableSetOf()) { it.id }

        val workdirs = computeQuestionFanOutWorkdirs(
            directorySessionKeys = store.currentSessionList().directorySessions.keys,
            currentWorkdir = currentWorkdir(),
            recentWorkdirs = recentWorkdirs(),
        )

        val directories = workdirs.takeIf { it.isNotEmpty() }?.toList()

        val outcome = repository.getSlimQuestions(
            directories = directories,
            token = token,
        ).getOrElse { error ->
            if (repository.isStaleFailure(error)) {
                return
            }
            SlimAggregationOutcome.Failure(error.message)
        }

        // Fast rejection after suspension but before building work.
        if (!repository.isCommitTokenCurrent(token)) return

        // C-D3 v2 §2.2: ALL slice + effect commits land inside ONE
        // commitIfSlimTokenCurrent atomic gate so a host rotation
        // between the network return and the slice commit can NOT
        // write a stale question list / signal under a new host.
        repository.commitIfTokenCurrent(token) {
            val signal = aggregationSignal(outcome)

            store.mutateSessionList { current ->
                val sessionsById = allSessionsById(
                    current.sessions,
                    current.directorySessions,
                    current.childSessions,
                )

                val folded = applyAggregationOutcome(
                    prior = current.pendingQuestions,
                    outcome = outcome,
                    wireToUi = SlimapiQuestionEntry::toQuestionRequest,
                    uiId = QuestionRequest::id,
                    uiDirectory = QuestionRequest::directory,
                )

                // Preserve an SSE arrival that occurred during this poll.
                val foldedIds = folded.items.mapTo(mutableSetOf()) { it.id }
                val raceArrivals = current.pendingQuestions.filter { question ->
                    question.id !in startIds &&
                        question.id !in foldedIds
                }

                val merged = (folded.items + raceArrivals)
                    .distinctBy { it.id }
                    .let {
                        filterArchivedSessionQuestions(it, sessionsById)
                    }

                current.copy(
                    pendingQuestions = merged,
                    questionAggregationSignal = signal,
                )
            }

            if (outcome is SlimAggregationOutcome.Failure) {
                effects.tryEmitUiEvent(
                    UiEvent.Error(R.string.error_slim_questions_fetch_failed)
                )
            }
        }
    }
}
