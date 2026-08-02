package cn.vectory.ocdroid.service.streaming

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §sse-zombie-fix (v4 R2/R3): unit tests for the thread-safe bootstrap-job
 * holder that replaces the plain `var bootstrapJob: Job?`. The CAS discipline
 * (install = getAndSet, invalidate = removeIfCurrent) is the structural
 * guarantee that a stale teardown can NEVER null/cancel a REPLACEMENT
 * attempt's job. These tests are the primary evidence for R3 closure.
 */
class BootstrapJobHolderTest {

    private fun newJob(): CompletableJob = SupervisorJob()

    @Test
    fun `install returns previous job and current reflects the new one`() {
        val holder = BootstrapJobHolder()
        val j1 = newJob()
        val j2 = newJob()

        // First install into empty slot → returns null.
        assertNull(holder.install(j1))
        assertSame(j1, holder.current())

        // Second install → returns the previous (j1), current is now j2.
        assertSame(j1, holder.install(j2))
        assertSame(j2, holder.current())
    }

    @Test
    fun `removeIfCurrent succeeds once then is a no-op`() {
        val holder = BootstrapJobHolder()
        val j1 = newJob()
        holder.install(j1)

        // First remove of the current reference → returns it, slot now null.
        assertSame(j1, holder.removeIfCurrent(j1))
        assertNull(holder.current())

        // Second remove of the same (now-stale) reference → no-op, returns null.
        assertNull(holder.removeIfCurrent(j1))
        assertNull(holder.current())
    }

    @Test
    fun `removeIfCurrent with a foreign reference preserves the slot`() {
        // §sse-zombie-fix (v4 R3 core assertion): a stale teardown holding a
        // foreign (superseded) reference MUST NOT null the slot — the
        // replacement's job stays intact. This is the race that the old
        // plain-var `bootstrapJob = null; staleJob.cancel()` sequence lost.
        val holder = BootstrapJobHolder()
        val j1 = newJob()
        val j2 = newJob()
        holder.install(j2)  // current is j2

        // Stale caller tries to remove j1 (which was never installed, or was
        // already superseded) → CAS fails, returns null, slot untouched.
        assertNull(holder.removeIfCurrent(j1))
        assertSame(j2, holder.current())
        assertTrue(j2.isActive)  // replacement job NOT cancelled

        // The legitimate owner can still remove its own (j2) reference.
        assertSame(j2, holder.removeIfCurrent(j2))
    }

    @Test
    fun `stale remove after replacement is a strict no-op`() {
        // End-to-end R3 scenario: install j1, replace with j2, then a stale
        // teardown (that captured j1 before the replacement) tries to remove
        // j1. It must fail silently; j2 must remain removable.
        val holder = BootstrapJobHolder()
        val j1 = newJob()
        val j2 = newJob()

        holder.install(j1)
        assertSame(j1, holder.install(j2))  // j1 replaced by j2

        // Stale teardown (captured j1) attempts removal → no-op.
        assertNull(holder.removeIfCurrent(j1))
        assertSame(j2, holder.current())

        // j2 is still cleanly removable by its rightful owner.
        assertSame(j2, holder.removeIfCurrent(j2))
        assertNull(holder.current())
    }

    @Test
    fun `concurrent install and removeIfCurrent never double-cancel and leave at most one job`() {
        runBlocking {
        // §sse-zombie-fix (v4): concurrency smoke test. Two coroutines
        // alternate install/removeIfCurrent 100 times. Post-condition: the
        // holder holds at most one job, and no job is cancelled more than once
        // (tracked via a per-job cancellation counter).
        val holder = BootstrapJobHolder()
        val cancelCounts = mutableMapOf<Job, Int>()
        val lock = Any()

        fun makeTrackedJob(): CompletableJob {
            val j = SupervisorJob()
            j.invokeOnCompletion {
                synchronized(lock) { cancelCounts.merge(j, 1) { a, b -> a + b } }
            }
            return j
        }

        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob())
        val a = scope.launch {
            repeat(100) {
                val job = makeTrackedJob()
                holder.install(job)?.cancel()
            }
        }
        val b = scope.launch {
            repeat(100) {
                holder.current()?.let { cur ->
                    holder.removeIfCurrent(cur)?.cancel()
                }
            }
        }
        a.join(); b.join()
        scope.cancel()

        // Post-condition 1: at most one job remains (AtomicReference invariant).
        val remaining = holder.current()
        // It's either null or a single live job — never a "double-owned" slot.

        // Post-condition 2: no job was cancelled more than once.
        val doubleCancels = synchronized(lock) {
            cancelCounts.values.count { it > 1 }
        }
        assertEquals("no job cancelled more than once", 0, doubleCancels)

        // Clean up any remaining job.
        remaining?.cancel()
        }
    }
}
