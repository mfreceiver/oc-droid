package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.ui.SharedStateStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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

    // ── §gpter-blocker (v097 review-fix): stale in-flight hydration must NOT
    //    re-certify a root after the tree was invalidated mid-flight ─────────

    @Test
    fun `gpter-blocker stale hydration is dropped when epoch bumped mid-flight`() = runTest {
        val store = SharedStateStore()
        val root = session("A")
        val child = session("C", "A")
        store.mutateSessionList {
            it.copy(sessions = listOf(root), sessionStatuses = mapOf("A" to SessionStatus("idle")))
        }
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.getChildren("A") } coAnswers {
            gate.await()
            Result.success(listOf(child))
        }
        coEvery { repository.getChildren("C") } returns Result.success(emptyList())
        coEvery { repository.getSessionStatus() } returns Result.failure(IllegalStateException("offline"))

        val hydrator = ForegroundSessionTreeHydrator(repository, store, this)
        hydrator.request(setOf("A"))
        advanceUntilIdle() // hydration suspended inside gate

        // Simulate SSE invalidation mid-flight (session.created/updated →
        // upsertAndInvalidateTree → epoch bump). The root was never certified
        // (hydration still in-flight), but the epoch bump is the critical part.
        val epochBefore = store.sessionListFlow.value.completenessEpoch
        store.mutateSessionList { state ->
            state.applySessionCreated(child).first
        }
        assertTrue(
            "epoch must be bumped by invalidation",
            store.sessionListFlow.value.completenessEpoch > epochBefore,
        )

        // Release the stale hydration — it completes with the OLD tree snapshot.
        gate.complete(Unit)
        advanceUntilIdle()

        // §gpter-blocker: stale result MUST be dropped — root stays incomplete.
        assertFalse(
            "stale hydration must NOT re-certify root after invalidation",
            "A" in store.sessionListFlow.value.completeRootIds,
        )

        // Follow-up tick re-hydrates against the fresh tree and succeeds.
        coEvery { repository.getChildren("A") } returns Result.success(listOf(child))
        hydrator.request(setOf("A"))
        advanceUntilIdle()

        assertTrue(
            "follow-up tick re-hydrates and certifies root",
            "A" in store.sessionListFlow.value.completeRootIds,
        )
    }

    @Test
    fun `gpter-blocker hydration commits normally when epoch is unchanged`() = runTest {
        // Negative control: no invalidation mid-flight → epoch unchanged →
        // commit succeeds. Ensures the guard doesn't false-positive.
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

        assertTrue("A must be certified when no invalidation occurred", "A" in store.sessionListFlow.value.completeRootIds)
    }

    // ── §glmer-minor (v097 review-fix): inFlight must not leak for filtered ids ─

    @Test
    fun `glmer-minor inFlight does not leak when id is filtered out before launch`() = runTest {
        val store = SharedStateStore()
        // "A" is NOT in the store yet → mapNotNull(byId::get) filters it out.
        // Pre-fix: inFlight.add("A") happened BEFORE the filter, so "A" was
        // stuck in inFlight forever (early-return skipped the finally cleanup).
        // Post-fix: inFlight.add happens AFTER the filter, so "A" is never added.
        val hydrator = ForegroundSessionTreeHydrator(repository, store, this)
        hydrator.request(setOf("A"))
        advanceUntilIdle()

        // Now add "A" to the store and request again — it must hydrate
        // (not be blocked by a leaked inFlight entry).
        val root = session("A")
        val child = session("C", "A")
        store.mutateSessionList {
            it.copy(sessions = listOf(root), sessionStatuses = mapOf("A" to SessionStatus("idle")))
        }
        coEvery { repository.getChildren("A") } returns Result.success(listOf(child))
        coEvery { repository.getChildren("C") } returns Result.success(emptyList())
        coEvery { repository.getSessionStatus() } returns Result.failure(IllegalStateException("offline"))

        hydrator.request(setOf("A"))
        advanceUntilIdle()

        assertTrue(
            "A must hydrate on the second request (no inFlight leak from the first)",
            "A" in store.sessionListFlow.value.completeRootIds,
        )
    }
}
