package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.BundleStamp
import cn.vectory.ocdroid.ui.SliceFlows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * **INVARIANT: all access must occur on Dispatchers.Main.immediate — this is a
 * thread-imprisonment contract, do not introduce concurrent primitives.**
 *
 * 🔴 CRITICAL INVARIANT: [flushJobs] (MutableMap) relies on Main.immediate
 * thread-imprisonment. Do NOT upgrade to concurrent primitives — if the
 * invariant is ever broken, a plain MutableMap crashes loudly
 * (ConcurrentModificationException), while ConcurrentHashMap would silently
 * corrupt. Crash-loud > silent-wrong.
 *
 * The ONLY coalesce state retained on the coordinator. The Job references are
 * bound to [scope] (a Job is neither serializable nor a value type, so it
 * cannot live in [cn.vectory.ocdroid.ui.ChatState]). The observable mirror —
 * which partIds have a pending flush — is [cn.vectory.ocdroid.ui.ChatState.pendingFlushPartIds]
 * in the slice; this map is the imperative side that drives
 * `delay(DELTA_COALESCE_MS) → flushDeltaBuffer(partId)`.
 *
 * The two views are kept in lock-step: a leading-edge write adds the partId to
 * [cn.vectory.ocdroid.ui.ChatState.pendingFlushPartIds] AND schedules a job here;
 * [flushDeltaBuffer] removes the partId from the slice AND removes the job here;
 * [clearDeltaBuffers] cancels every job here AND wipes the slice's three
 * coalesce fields.
 */
internal class DeltaFlushScheduler(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
    private val repository: OpenCodeRepository?,
) {
    companion object {
        /**
         * §M5 trailing-coalesce window. Leading-edge delta writes immediately;
         * subsequent deltas within this window are batched into one flush → one
         * Compose recomposition per window instead of one per token.
         */
        const val DELTA_COALESCE_MS = 100L
    }

    /** Thread-confinement: Main.immediate only — see class kdoc. */
    private val flushJobs = mutableMapOf<String, Job>()

    /**
     * Opens (or reopens) the [DELTA_COALESCE_MS] trailing-coalesce window for
     * [partId]. Scheduled on the leading-edge delta; while the launched job is
     * alive, subsequent deltas append to [cn.vectory.ocdroid.ui.ChatState.deltaBuffer]
     * instead of writing streamingPartTexts.
     */
    fun scheduleDeltaFlush(partId: String) {
        flushJobs[partId]?.cancel()
        flushJobs[partId] = scope.launch {
            delay(DELTA_COALESCE_MS)
            flushDeltaBuffer(partId)
        }
    }

    /**
     * Cancels [partId]'s pending flush and drops its buffers (both delta APPEND
     * and fullText REPLACE) in the slice.
     */
    @Suppress("unused")
    fun cancelDeltaFlush(partId: String) {
        flushJobs.remove(partId)?.cancel()
        slices.store.dispatch(AppAction.CoalesceClearedForPart(partId))
    }

    /**
     * Flushes [partId]'s buffered deltas/fullText into the chat slice's
     * streamingPartTexts in a single atomic write. Self-removes the partId from
     * [cn.vectory.ocdroid.ui.ChatState.pendingFlushPartIds] and from [flushJobs].
     */
    private fun flushDeltaBuffer(partId: String) {
        flushJobs.remove(partId)
        val sid = slices.chat.value.currentSessionId
        val routeInstance = sid?.let { slices.routeInstanceFor(it) } ?: 0L
        dispatchBundleBound { stamp ->
            AppAction.CoalesceFlushedForPart(
                partId = partId,
                expectedRouteInstance = routeInstance,
                sessionId = sid,
                bundleStamp = stamp,
            )
        }
    }

    /**
     * Drops ALL pending delta/fullText buffers, cancels their flush jobs, and
     * clears [cn.vectory.ocdroid.ui.ChatState.pendingFlushPartIds].
     * Safe to call repeatedly.
     */
    fun clearDeltaBuffers() {
        flushJobs.values.forEach { it.cancel() }
        flushJobs.clear()
        slices.store.dispatch(AppAction.CoalesceBuffersCleared)
    }

    /** Returns true if a flush job is active for [partId]. */
    fun isFlushActiveForPart(partId: String): Boolean =
        flushJobs[partId]?.isActive == true

    /**
     * Shared bundle-bound dispatch gate. The bundle read, stamp construction,
     * and StoreState CAS all occur under the same repository monitor used by
     * configure/publish.
     */
    private fun dispatchBundleBound(actionFactory: (BundleStamp) -> AppAction): Boolean {
        val repo = repository ?: return false
        synchronized(repo) {
            val bundle = repo.currentClientBundle() ?: return false
            slices.store.dispatch(actionFactory(BundleStamp(bundle.generation, bundle.endpointFp)))
            return true
        }
    }
}
