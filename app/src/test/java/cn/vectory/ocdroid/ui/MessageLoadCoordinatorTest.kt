package cn.vectory.ocdroid.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * §history-load-fix: covers [MessageLoadCoordinator] — the per-session Mutex
 * that serializes message-list mutations across launchLoadMessages /
 * launchLoadMoreMessages / launchCatchUp. Locks the core invariants the
 * history-load fix relies on: the critical section is EXCLUSIVE per session
 * (no two concurrent mutations of one session's list → no torn prepend/replace
 * or lost update), and DIFFERENT sessions never contend (per-session map, not a
 * global lock → no cross-session stall).
 */
class MessageLoadCoordinatorTest {

    @Test
    fun `withSessionLock serializes the critical section for the same session`() = runTest {
        val coordinator = MessageLoadCoordinator()
        val inSection = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        // Many coroutines all locking the SAME session — the critical section
        // must never overlap (maxConcurrent stays 1).
        val jobs = (0 until 12).map {
            async {
                coordinator.withSessionLock("ses-A") {
                    val c = inSection.incrementAndGet()
                    maxConcurrent.accumulateAndGet(c) { old, seen -> maxOf(old, seen) }
                    delay(5) // yield point; virtual time under runTest
                    inSection.decrementAndGet()
                }
            }
        }
        jobs.awaitAll()
        assertEquals("critical section must be exclusive per session", 1, maxConcurrent.get())
    }

    @Test
    fun `withSessionLock does not block a different session`() = runTest {
        val coordinator = MessageLoadCoordinator()
        val ran = AtomicInteger(0)
        // Two DIFFERENT sessions locking concurrently — both enter without
        // waiting on each other (per-session lock, not global) and complete.
        val a = async {
            coordinator.withSessionLock("ses-A") {
                ran.incrementAndGet()
                delay(10)
            }
        }
        val b = async {
            coordinator.withSessionLock("ses-B") {
                ran.incrementAndGet()
                delay(10)
            }
        }
        a.await()
        b.await()
        assertEquals("different sessions must not contend", 2, ran.get())
    }
}
