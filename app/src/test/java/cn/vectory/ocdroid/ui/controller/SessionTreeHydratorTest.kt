package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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
}
