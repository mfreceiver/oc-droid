package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.DebugLog
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
 * §slimapi-p3 / §rev-ds ISSUE 2: branches on [repository.supportsGlobalQuestionFetch]:
 *  - **Slim** (true): single `getPendingQuestions(directory = null)` returns ALL
 *    pending questions across all workdirs (the `/question` directory parameter
 *    is a no-op for filtering, only instance routing).
 *  - **Legacy** (false): per-dir fan-out over known workdirs, identical to
 *    pre-P3 behavior (no regression).
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
     * §slimapi-p3 / §rev-ds ISSUE 2: bounded pending-question refresh with
     * slim/legacy branching:
     *
     * **Slim path** — single `getPendingQuestions(null)` global call.
     * **Legacy path** — per-dir fan-out over known workdirs.
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
                if (repository.supportsGlobalQuestionFetch) {
                    // §slimapi-p3: slim path — single global call.
                    repository.getPendingQuestions(directory = null)
                        .onSuccess { allQuestions ->
                            if (generation == questionReconcileGeneration) {
                                slices.mutateSessionList { state ->
                                    state.copy(pendingQuestions = allQuestions)
                                }
                            }
                        }
                } else {
                    // §rev-ds: legacy path — per-dir fan-out (restored pre-P3).
                    val allDirs = computeLegacyQuestionWorkdirs()
                    // §rev-ds round-2 FIX 5 (preserve-on-empty): when no workdirs
                    // are known, preserve existing pendingQuestions rather than
                    // committing an empty list (pre-P3 cleared on empty). The
                    // current behavior is safer — discarding pending questions
                    // due to transient workdir unavailability could lose user-
                    // visible question state. Chosen per reviewer guidance.
                    if (allDirs.isEmpty()) return@launch
                    val allQuestions = mutableListOf<cn.vectory.ocdroid.data.model.QuestionRequest>()
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
            } finally {
                finishQuestionReconcile()
            }
        }
    }

    /**
     * §rev-ds: computes the set of workdirs for the legacy per-dir fan-out.
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
