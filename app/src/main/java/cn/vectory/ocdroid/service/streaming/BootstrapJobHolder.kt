package cn.vectory.ocdroid.service.streaming

import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicReference

/**
 * sse-zombie-fix (v4 R2/R3): thread-safe holder for the Service's in-flight
 * bootstrap job. Replaces the plain `var bootstrapJob: Job?` whose
 * read-then-null-then-cancel sequence left a window in which a stale
 * teardown could null/cancel a REPLACEMENT attempt's job (and which was
 * read from Dispatchers.Default without any happens-before guarantee).
 *
 * Discipline: install via [install] (single atomic swap), invalidate via
 * [removeIfCurrent] (CAS — nulls + returns the job ONLY if the reference is
 * still exactly the caller's own). A stale teardown whose expected reference
 * was superseded is a strict no-op.
 */
internal class BootstrapJobHolder {
    private val ref = AtomicReference<Job?>(null)

    /** Atomically swaps in [job]; returns the PREVIOUS job (caller cancels it). */
    fun install(job: Job): Job? = ref.getAndSet(job)

    /** Current reference, or null. Safe from any thread. */
    fun current(): Job? = ref.get()

    /**
     * Nulls the slot and returns [expected] iff the current reference IS
     * [expected] (reference equality, single CAS). Returns null otherwise —
     * the slot then still holds the replacement's job, untouched.
     */
    fun removeIfCurrent(expected: Job): Job? =
        if (ref.compareAndSet(expected, null)) expected else null
}
