package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.ui.SharedStateStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionTreeHydratorTest {
    private val repository = mockk<OpenCodeRepository>()
    private fun session(id: String, parentId: String? = null) =
        Session(id = id, directory = "/repo", parentId = parentId)

    @Test
    fun `recursive hydration marks root complete only after every descendant succeeds`() = runTest {
        val root = session("A")
        val child = session("C", "A")
        val grandchild = session("G", "C")
        coEvery { repository.getChildren("A") } returns Result.success(listOf(child))
        coEvery { repository.getChildren("C") } returns Result.success(listOf(grandchild))
        coEvery { repository.getChildren("G") } returns Result.success(emptyList())

        val result = loadCompleteSessionTrees(repository, listOf(root), maxConcurrency = 2)

        assertEquals(setOf("A"), result.completeRootIds)
        assertEquals(listOf("C"), result.childrenByParent["A"]?.map { it.id })
        assertEquals(listOf("G"), result.childrenByParent["C"]?.map { it.id })
    }

    @Test
    fun `descendant request failure leaves root incomplete`() = runTest {
        val root = session("A")
        val child = session("C", "A")
        coEvery { repository.getChildren("A") } returns Result.success(listOf(child))
        coEvery { repository.getChildren("C") } returns Result.failure(IllegalStateException("offline"))

        val result = loadCompleteSessionTrees(repository, listOf(root), maxConcurrency = 2)

        assertFalse("A" in result.completeRootIds)
        assertTrue(result.childrenByParent.isEmpty())
    }

    @Test
    fun `root hydration concurrency is bounded`() = runTest {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val roots = (1..8).map { session("R$it") }
        coEvery { repository.getChildren(any()) } coAnswers {
            val count = active.incrementAndGet()
            maxActive.updateAndGet { previous -> maxOf(previous, count) }
            delay(10)
            active.decrementAndGet()
            Result.success(emptyList())
        }

        val result = loadCompleteSessionTrees(repository, roots, maxConcurrency = 3)

        assertEquals(8, result.completeRootIds.size)
        assertTrue("observed ${maxActive.get()} concurrent requests", maxActive.get() <= 3)
    }

    @Test
    fun `status failure caches complete tree while descendants remain unknown`() = runTest {
        val store = SharedStateStore()
        val root = session("A")
        val child = session("C", "A")
        store.mutateSessionList {
            it.copy(sessions = listOf(root), sessionStatuses = mapOf("A" to SessionStatus("idle")))
        }
        coEvery { repository.getChildren("A") } returns Result.success(listOf(child))
        coEvery { repository.getChildren("C") } returns Result.success(emptyList())
        coEvery { repository.getSessionStatus() } returns Result.failure(IllegalStateException("offline"))

        ForegroundSessionTreeHydrator(repository, store, this).request(setOf("A"))
        advanceUntilIdle()

        assertTrue("A" in store.sessionListFlow.value.completeRootIds)
        assertEquals(listOf("C"), store.sessionListFlow.value.childSessions["A"]?.map { it.id })
        assertFalse("C" in store.sessionListFlow.value.sessionStatuses)
    }
}
