package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.SliceFlows
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
 */
internal class QuestionReconcileWorker(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
    private val settingsManager: SettingsManager,
    private val currentProfileId: () -> String,
) {
    // These plain vars depend on Main.immediate thread-imprisonment.
    // Do NOT upgrade to AtomicReference/ConcurrentHashMap — see class kdoc.
    private var questionReconcileRunning = false
    private var questionReconcilePending = false
    private var questionReconcileGeneration = 0L
    private var latestQuestionRepository: OpenCodeRepository? = null

    /**
     * §P1-9: refreshes pending questions across EVERY known workdir (the in-memory
     * `directorySessions` keys + `settingsManager.currentWorkdir`), not just
     * `currentWorkdir`.
     *
     * Merge semantics: successful directory responses are authoritative (server is
     * source of truth — questions absent from server response are dropped), failed
     * directories conservatively retain locally-held questions for that directory,
     * race-window arrivals (SSE `question.asked` during the fan-out) are preserved,
     * and the generation gate ensures stale (superseded) responses from a prior
     * reconcile round are not committed.
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
                val currentWd = settingsManager.currentWorkdir
                val recentWds = settingsManager.getRecentWorkdirs(currentProfileId())
                val allDirs = (recentWds + listOfNotNull(currentWd))
                    .filter { it.isNotBlank() }
                    .distinct()
                val allQuestions = mutableListOf<QuestionRequest>()
                for (dir in allDirs) {
                    repository.getPendingQuestions(dir)
                        .onSuccess { allQuestions += it }
                }
                if (generation == questionReconcileGeneration) {
                    slices.mutateSessionList { state ->
                        state.copy(pendingQuestions = allQuestions)
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
