package cn.vectory.ocdroid.ui.controller.sse

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Epoch + generation + ownership bookkeeping for the token stream.
 *
 * The "is this frame/clear stale?" authority. Owns three CHMs:
 *  - [epochBySid]: per-sid monotonic epoch counter; bumped at every
 *    [beginStreamIncarnation]; frames whose epoch no longer match the
 *    current value are dropped (stale — incl. late OkHttp callbacks).
 *  - [genBySid]: per-sid generation counter (bgpt MF-3); bumped by
 *    [beginSession] and [beginStreamIncarnation]; used to reject stale
 *    clears that target a partId a NEWER session/generation now owns.
 *  - [ownerByPartId]: partId → ([sid], [gen]) ownership tag.
 *
 * All mutating methods are [synchronized] on the shared [lock] so
 * callers (the facade / supervisor) can nest calls inside a single
 * monitor acquisition without deadlock.
 *
 * @param lock The shared [bundleCommitLock] monitor (same instance used
 *   by the facade and [OpenCodeRepository.configure]).
 */
internal class TokenFrameGuard(private val lock: Any) {

    /**
     * Per-sid monotonic epoch counter. Bumped at every open(sid) that reaches
     * runStream.
     */
    private val epochBySid = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Per-sid generation counter (bgpt MF-3). Bumped by [beginSession]; used
     * to reject stale clears that target a partId a NEWER session/generation
     * now owns. Distinct from [epochBySid] (epoch tags inbound frames;
     * generation tags outbound clears) — they bump together at every open/
     * reconnect but serve different guards.
     */
    private val genBySid = ConcurrentHashMap<String, AtomicLong>()

    /** partId → the (sid, generation) that owns it. */
    private val ownerByPartId = ConcurrentHashMap<String, OwnerTag>()

    // ── Epoch / generation accessors ─────────────────────────────────────────

    /** Test/diagnostic read: the current epoch for [sid] (or 0 if none). */
    fun epochOf(sid: String): Long = epochBySid[sid]?.get() ?: 0L

    /** Test/diagnostic read: the current generation for [sid] (or 0 if none). */
    fun genOf(sid: String): Long = genBySid[sid]?.get() ?: 0L

    /**
     * Test-only: bumps the epoch for [sid] WITHOUT going through open() (so
     * tests can simulate a re-open's epoch bump in isolation while driving
     * frames via [dispatchEpochFrame]).
     */
    fun bumpEpochForTest(sid: String): Long =
        synchronized(lock) {
            epochBySid.computeIfAbsent(sid) { AtomicLong(0L) }.incrementAndGet()
        }

    /**
     * Returns `true` iff [epoch] is the current epoch for [sid]. Used as the
     * entry guard in [dispatchEpochFrame] to drop stale frames.
     */
    fun isEpochCurrent(sid: String, epoch: Long): Boolean {
        val currentEpoch = epochBySid[sid]?.get() ?: return false
        return currentEpoch == epoch
    }

    // ── Session lifecycle ────────────────────────────────────────────────────

    /**
     * Bumps the generation for [sid] and returns the new value. Called at
     * every open/reconnect so ownership claims + clears emitted by the prior
     * generation become stale.
     */
    fun beginSession(sid: String): Long =
        synchronized(lock) {
            genBySid.computeIfAbsent(sid) { AtomicLong(0L) }.incrementAndGet()
        }

    /**
     * Bumps both epoch and generation for [sid] and returns the pair.
     * Called inside the open/reconnect stream lifecycle, AFTER debounce
     * and the supersede guard, so a cancelled collector cannot register a
     * stale epoch.
     */
    fun beginStreamIncarnation(sid: String): Pair<Long, Long> =
        synchronized(lock) {
            val epoch = epochBySid.computeIfAbsent(sid) { AtomicLong(0L) }.incrementAndGet()
            val generation = genBySid.computeIfAbsent(sid) { AtomicLong(0L) }.incrementAndGet()
            epoch to generation
        }

    // ── Generation guard (bgpt MF-3) ─────────────────────────────────────────

    /**
     * Records that [partId] is owned by stream ([sid], [gen]). Only records
     * when [gen] is the current generation for [sid] — a stale claim (from
     * a cancelled collector whose gen lags behind beginSession) is dropped.
     */
    fun onPartOwned(sid: String, gen: Long, partId: String) {
        synchronized(lock) {
            val currentGen = genBySid[sid]?.get() ?: return
            if (currentGen != gen) return
            ownerByPartId[partId] = OwnerTag(sid, gen)
        }
    }

    /**
     * Filters [partIds] through the generation guard. Returns the subset that
     * the ([sid], [gen]) stream is allowed to clear:
     *  - partIds whose owner tag matches ([sid], [gen]) → ALLOW (+ remove tag);
     *  - partIds with NO owner tag → ALLOW (no current owner; the clear is a
     *    safe no-op — e.g. a truncated first-snapshot before any onPartOwned);
     *  - partIds whose owner tag is a DIFFERENT (sid', gen') → DROP (a newer
     *    stream owns them; the stale clear MUST NOT wipe the new overlay).
     *
     * Side-effect: removes allowed-and-owned entries from [ownerByPartId].
     */
    fun filterClearByGeneration(sid: String, gen: Long, partIds: Set<String>): Set<String> {
        synchronized(lock) {
            if (partIds.isEmpty()) return emptySet()
            val allowed = mutableSetOf<String>()
            for (partId in partIds) {
                val tag = ownerByPartId[partId]
                if (tag == null) {
                    allowed += partId
                } else if (tag.sid == sid && tag.gen == gen) {
                    allowed += partId
                    ownerByPartId.remove(partId)
                }
                // else: stale — a newer stream owns this partId. Drop.
            }
            return allowed
        }
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    /** Test/diagnostic read: the partIds currently owned by the active stream for [sid]. */
    fun ownedPartsForSid(sid: String): Set<String> =
        ownerByPartId.entries
            .asSequence()
            .filter { it.value.sid == sid }
            .map { it.key }
            .toSet()

    // ── Cleanup ──────────────────────────────────────────────────────────────

    /**
     * Removes all ownership entries for [sid] (the `ownerByPartId.entries.removeIf`
     * cleanup from [close]). Does NOT remove [epochBySid] or [genBySid] —
     * epoch/gen persist across closes so stale frames from dead connections
     * are still detected and stale-clear drops work correctly.
     */
    fun removeSid(sid: String) {
        ownerByPartId.entries.removeIf { it.value.sid == sid }
    }
}
