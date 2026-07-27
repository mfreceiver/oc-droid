package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.ui.controller.QuestionDirectoryFetch
import cn.vectory.ocdroid.ui.controller.reconcileLegacyPendingQuestions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlimQuestionLoaderReconcileTest {
    private fun question(id: String, directory: String) = QuestionRequest(
        id = id,
        sessionId = id,
        directory = directory,
        questions = emptyList(),
    )

    @Test
    fun `successful empty A deletes A while failed B retains existing question`() {
        val existing = listOf(question("a", "A"), question("b", "B"))
        val result = reconcileLegacyPendingQuestions(
            startIds = existing.mapTo(mutableSetOf()) { it.id },
            current = existing,
            fetches = listOf(
                QuestionDirectoryFetch("A", Result.success(emptyList())),
                QuestionDirectoryFetch("B", Result.failure(IllegalStateException("offline"))),
            ),
        )
        assertEquals(listOf("b"), result.map { it.id })
    }

    @Test
    fun `new question arriving during fetch is retained`() {
        val result = reconcileLegacyPendingQuestions(
            startIds = setOf("old"),
            current = listOf(question("new", "A")),
            fetches = listOf(QuestionDirectoryFetch("A", Result.success(emptyList()))),
        )
        assertEquals(listOf("new"), result.map { it.id })
    }

    @Test
    fun `directory null question attributable to successful dir is cleaned`() {
        // sessionId="q-session-a" belongs to directory "A" (via sessionIdToDirectory).
        // A succeeded (returned empty → no more questions on A).
        // The question with directory==null should be attributed to A and cleaned.
        val sessionIdToDirectory = mapOf("q-session-a" to "A", "q-session-b" to "B")
        val existing = listOf(
            QuestionRequest(id = "q-a", sessionId = "q-session-a", directory = null, questions = emptyList()),
            QuestionRequest(id = "q-b", sessionId = "q-session-b", directory = "B", questions = emptyList()),
        )
        val result = reconcileLegacyPendingQuestions(
            startIds = existing.mapTo(mutableSetOf()) { it.id },
            current = existing,
            fetches = listOf(
                QuestionDirectoryFetch("A", Result.success(emptyList())),
                QuestionDirectoryFetch("B", Result.failure(IllegalStateException("offline"))),
            ),
            sessionIdToDirectory = sessionIdToDirectory,
        )
        // q-a belongs to A (succeeded) → cleaned.
        assertEquals(listOf("q-b"), result.map { it.id })
    }

    @Test
    fun `directory null question attributable to failed dir is kept`() {
        val sessionIdToDirectory = mapOf("q-session-a" to "A", "q-session-b" to "B")
        val existing = listOf(
            QuestionRequest(id = "q-a", sessionId = "q-session-a", directory = null, questions = emptyList()),
        )
        val result = reconcileLegacyPendingQuestions(
            startIds = existing.mapTo(mutableSetOf()) { it.id },
            current = existing,
            fetches = listOf(
                QuestionDirectoryFetch("A", Result.failure(IllegalStateException("offline"))),
                QuestionDirectoryFetch("B", Result.success(emptyList())),
            ),
            sessionIdToDirectory = sessionIdToDirectory,
        )
        // q-a belongs to A (failed) → kept.
        assertEquals(listOf("q-a"), result.map { it.id })
    }

    @Test
    fun `directory null question with unresolvable sessionId is kept conservatively`() {
        val sessionIdToDirectory = mapOf("known-session" to "A")
        val existing = listOf(
            QuestionRequest(id = "q-unknown", sessionId = "ghost-session", directory = null, questions = emptyList()),
        )
        val result = reconcileLegacyPendingQuestions(
            startIds = existing.mapTo(mutableSetOf()) { it.id },
            current = existing,
            fetches = listOf(
                QuestionDirectoryFetch("A", Result.success(emptyList())),
            ),
            sessionIdToDirectory = sessionIdToDirectory,
        )
        // ghost-session is not in the map → can't determine → keep conservatively.
        assertEquals(listOf("q-unknown"), result.map { it.id })
    }

    @Test
    fun `directory null question with unresolvable sessionId is kept on partial failure - conservative cost documented`() {
        // Conservative strategy cost: a NULL-directory question whose sessionId
        // cannot be resolved (unknown/ghost session) is PRESERVED even when ALL
        // directories succeeded. The question may belong to a successful directory
        // but we cannot prove it → keep it (fail closed).
        val sessionIdToDirectory = mapOf("known" to "A")
        val existing = listOf(
            QuestionRequest(id = "q-ghost", sessionId = "no-such-session", directory = null, questions = emptyList()),
        )
        val result = reconcileLegacyPendingQuestions(
            startIds = existing.mapTo(mutableSetOf()) { it.id },
            current = existing,
            fetches = listOf(
                QuestionDirectoryFetch("A", Result.success(emptyList())),
                QuestionDirectoryFetch("B", Result.success(emptyList())),
            ),
            sessionIdToDirectory = sessionIdToDirectory,
        )
        // Conservative: cannot determine ownership → keep.
        assertEquals(listOf("q-ghost"), result.map { it.id })
    }
}
