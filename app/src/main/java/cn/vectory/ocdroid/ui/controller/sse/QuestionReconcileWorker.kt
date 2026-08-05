package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.SliceFlows
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
 * §slimapi-p3: P3 fan-out collapse — no longer iterates known workdirs. A
 * single global `getPendingQuestions(directory = null)` call returns ALL
 * pending questions across all workdirs (the `/question` directory parameter
 * is a no-op for filtering, only instance routing). The per-dir fan-out loop
 * and its associated `settingsManager`/`currentProfileId` dependencies have
 * been removed.
 */
internal class QuestionReconcileWorker(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
) {
    // These plain vars depend on Main.immediate thread-imprisonment.
    // Do NOT upgrade to AtomicReference/ConcurrentHashMap — see class kdoc.
    private var questionReconcileRunning = false
    private var questionReconcilePending = false
    private var questionReconcileGeneration = 0L
    private var latestQuestionRepository: OpenCodeRepository? = null

    /**
     * §slimapi-p3: P3 fan-out collapse — single global /question=null call.
     *
     * Previously this refreshed pending questions across EVERY known workdir
     * (the in-memory `directorySessions` keys + `settingsManager.currentWorkdir`).
     * Now a single `getPendingQuestions(directory = null)` call returns ALL
     * pending questions across all workdirs, reducing the fan-out from N→1.
     *
     * Merge semantics: the response is authoritative (server is source of truth
     * — questions absent from server response are dropped), race-window arrivals
     * (SSE `question.asked` during the fetch) are preserved, and the generation
     * gate ensures stale (superseded) responses from a prior reconcile round
     * are not committed.
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
                repository.getPendingQuestions(directory = null)
                    .onSuccess { allQuestions ->
                        if (generation == questionReconcileGeneration) {
                            slices.mutateSessionList { state ->
                                state.copy(pendingQuestions = allQuestions)
                            }
                        }
                    }
            } finally {
                finishQuestionReconcile()
            }
        }
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
