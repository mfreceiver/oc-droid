package cn.vectory.ocdroid.di

import java.util.concurrent.ConcurrentHashMap

/**
 * B-4 HIGH-2: per-session bounded revision ledger with terminal tombstone
 * semantics and LRU (recency-order) eviction.
 *
 * Replaces the raw `ConcurrentHashMap<String, Long>` used by the original
 * [tokenStreamProductionHooks]. The key differences:
 *
 * 1. **Terminal tombstone**: `markTerminal` sets an `isTerminal` flag instead
 *    of removing the entry. A terminal entry rejects ALL subsequent revisions
 *    (same, lower, higher, null) — preventing part resurrection after
 *    done/truncated. Tombstones can be removed by [clearSession] / [remove] /
 *    [removeMessage], or silently evicted when LRU capacity pressure evicts
 *    the least-recently-used terminal entry.
 *
 * 2. **Bounded capacity**: each session is capped at [maxEntriesPerSession]
 *    entries. When the cap is reached and a genuinely new (mid,pid) pair
 *    tries to enter, the ledger evicts the least-recently-used terminal entry
 *    (LRU by recency order). If all entries are active (non-terminal), the
 *    new key is rejected (fail-closed). The cap is **hard** under concurrent
 *    inserts for distinct keys because each session's admission+size+insert
 *    is protected by a per-session mutex. Different sessions can still be
 *    parallel.
 *
 * 3. **Null revision**: nullable `partEventRevision` is now incorporated into
 *    the ledger itself (not bypassed by the caller). Null revisions for an
 *    active (non-terminal) key are accepted (fail-open compatible). Null
 *    revisions for a terminal key are rejected. A new null key occupies a
 *    bounded slot. When at capacity, a new null key may still be admitted
 *    if an LRU-terminal slot can be evicted; otherwise rejected (fail-closed).
 *    The first non-null revision after a null one upgrades the watermark.
 *
 * 4. **LRU eviction by recency order**: when the session is at capacity and a
 *    genuinely new key needs admission, the terminal entry with the smallest
 *    `lastUsedOrder` (least recently touched) is evicted. `lastUsedOrder` is
 *    updated on every successful admit/upgrade, accepted null, and
 *    `markTerminal` — so frequently-touched terminals survive longer than
 *    stale ones even if they were inserted earlier. Non-terminal (active)
 *    entries are NEVER evicted. If all entries are active, the new key is
 *    rejected (fail-closed). Evicted terminal entries lose their tombstone
 *    protection and can be re-admitted as fresh entries.
 *
 * ## Thread safety
 *
 * Per-session atomicity is provided by a private [SessionLedger] class that
 * uses `synchronized` on a per-session mutex, making the admission check +
 * size check + insert a single atomic step. Different sessions use different
 * mutexes and can be operated on in parallel.
 *
 * ## Trade-off note
 *
 * LRU terminal eviction (evict least-recently-used tombstone when at capacity)
 * balances safety and liveliness: existing terminal entries that were evicted
 * lose their tombstone protection (the part could theoretically be
 * re-rendered), but in practice the sidecar produces a new part with a new
 * (messageId, partId) only after the origin terminal event. The alternative —
 * keeping all 32 tombstones but starving new parts — caused functional
 * starvation in long sessions. Eviction of NON-terminal entries is NOT done:
 * that would break an in-flight streaming part's dedup protection. The
 * production [dedupPartRevision] hook returns `false` only when no terminal
 * can be evicted (all 32 entries are active). Rejected frames cause the
 * coordinator to skip reduction; recovery depends on existing lifecycle
 * events (close/open, resync, reconcile).
 */
class PartRevisionLedger(
    private val maxEntriesPerSession: Int = MAX_REVISIONS_PER_SESSION,
) {

    private data class Entry(
        val revision: Long?,
        val isTerminal: Boolean,
        /** Monotonically increasing touch order; used for LRU eviction
         * of terminal tombstones when the session ledger is at capacity.
         * Updated on every successful admit/upgrade, accepted null, and
         * markTerminal — so the eviction candidate is the least-recently-used
         * terminal, not the earliest-inserted one. */
        val lastUsedOrder: Long,
    )

    /**
     * Per-session ledger with synchronized admission+size+insert.
     * Different sessions are independent and can be parallel.
     */
    private class SessionLedger(private val maxEntries: Int) {
        private val entries = HashMap<String, Entry>()
        /** Monotonically increasing touch counter for LRU recency ordering. */
        private var touchOrder = 0L

        /**
         * Attempt to admit a key with given revision.
         *
         * @return `true` if admitted (fresh), `false` if rejected
         *   (stale/terminal/cap full with no evictable terminal tombstone).
         */
        fun admit(key: String, rev: Long?): Boolean = synchronized(this) {
            val existing = entries[key]

            // Rule 1: Terminal tombstone rejects ALL revisions (same/lower/higher/null).
            if (existing != null && existing.isTerminal) return false

            // Rule 2: Non-null stale/duplicate (<= existing watermark).
            if (existing != null && rev != null && existing.revision != null && rev <= existing.revision) return false

            // Rule 3: New key at capacity — evict oldest terminal if possible,
            // otherwise fail-closed.
            if (existing == null && entries.size >= maxEntries) {
                if (!evictOldestTerminal()) return false
            }

            // Accept: update entry for non-null rev (new key or existing upgrade).
            if (rev != null) {
                val ord = touchOrder++
                entries[key] = Entry(
                    revision = rev,
                    isTerminal = false,
                    lastUsedOrder = ord,
                )
            } else if (existing == null) {
                // New null key occupies a slot.
                val ord = touchOrder++
                entries[key] = Entry(null, isTerminal = false, lastUsedOrder = ord)
            } else {
                // Existing non-terminal, repeated null — still a hit: update touch.
                val ord = touchOrder++
                entries[key] = existing.copy(lastUsedOrder = ord)
            }

            return true
        }

        /**
         * Evict the least-recently-used (smallest [lastUsedOrder]) terminal entry.
         * Called only when the caller already holds the per-session lock, so
         * this helper does NOT add its own [synchronized].
         * @return `true` if an entry was evicted, `false` if no terminal
         *   entries exist (caller must reject the new key).
         */
        private fun evictOldestTerminal(): Boolean {
            val lruKey = entries.entries
                .filter { it.value.isTerminal }
                .minByOrNull { it.value.lastUsedOrder }
                ?.key ?: return false
            entries.remove(lruKey)
            return true
        }

        /**
         * Mark an EXISTING entry as terminal. NO-OP if the key has never been
         * admitted — absent tombstones would silently bypass the capacity cap.
         * The coordinator's [onPartDone][cn.vectory.ocdroid.di.TokenStreamProductionHooks.onPartDone]
         * is always called after successful [admit], so the entry always exists
         * in the normal flow.
         *
         * Updates [lastUsedOrder] so a recently-touched terminal survives longer
         * under LRU pressure than a stale one.
         */
        fun markTerminal(key: String) = synchronized(this) {
            val existing = entries[key] ?: return
            val ord = touchOrder++
            entries[key] = existing.copy(isTerminal = true, lastUsedOrder = ord)
        }

        fun remove(key: String) = synchronized(this) {
            entries.remove(key)
        }

        fun removeAllWithPrefix(prefix: String) = synchronized(this) {
            entries.entries.removeIf { it.key.startsWith(prefix) }
        }

        fun clear() = synchronized(this) {
            entries.clear()
        }

        fun size(): Int = synchronized(this) { entries.size }

        fun isTerminal(key: String): Boolean? = synchronized(this) {
            entries[key]?.isTerminal
        }
    }

    // sessionId → SessionLedger
    private val sessions = ConcurrentHashMap<String, SessionLedger>()

    /**
     * Attempt to admit a frame with the given [rev] for (sid, mid, pid).
     *
     * @param rev nullable partEventRevision. Null is accepted for active
     *   (non-terminal) keys, rejected for terminal keys. A null key without
     *   an existing entry occupies a bounded slot.
     * @return `true` if the revision is fresh (admitted), `false` if stale
     *   (duplicate/out-of-order/terminal) or if the session ledger is at
     *   capacity and cannot admit a new key.
     */
    fun admit(sid: String, mid: String, pid: String, rev: Long?): Boolean {
        val ledger = sessions.computeIfAbsent(sid) { SessionLedger(maxEntriesPerSession) }
        return ledger.admit(compositeKey(mid, pid), rev)
    }

    /**
     * Mark an EXISTING revision entry as terminal (tombstone). Does NOT remove
     * it. After marking, ALL subsequent revisions (same, lower, higher, null)
     * will be rejected by [admit].
     *
     * If the key has never been admitted, this is a NO-OP — the coordinator
     * always calls [onPartDone][cn.vectory.ocdroid.di.TokenStreamProductionHooks.onPartDone]
     * after a successful [admit], so the entry exists in the normal flow.
     * Making this a no-op for absent keys prevents silent cap-bypass when the
     * ledger is full.
     */
    fun markTerminal(sid: String, mid: String, pid: String) {
        sessions[sid]?.markTerminal(compositeKey(mid, pid))
    }

    /**
     * Remove a single revision entry (used by [onMessagePartRemoved]).
     * Frees capacity and erases all revision/tombstone history for this key.
     */
    fun remove(sid: String, mid: String, pid: String) {
        sessions[sid]?.remove(compositeKey(mid, pid))
    }

    /**
     * Remove ALL revision entries for a message (used by [onMessageRemoved]).
     */
    fun removeMessage(sid: String, mid: String) {
        val prefix = "$mid\u0001"
        sessions[sid]?.removeAllWithPrefix(prefix)
    }

    /**
     * Clear ALL revision entries for a session (used by [clearSessionRevisions]).
     * This is the primary mechanism that clears terminal tombstones at the
     * epoch/lifecycle boundary. Terminal tombstones may also be silently
     * evicted earlier under LRU capacity pressure ([maxEntriesPerSession]).
     */
    fun clearSession(sid: String) {
        sessions.remove(sid)
    }

    // ── Diagnostic / test helpers ──────────────────────────────────────────

    /** Returns the number of revision entries for [sid] (or 0). */
    internal fun sessionEntryCount(sid: String): Int = sessions[sid]?.size() ?: 0

    /** Returns `true` if the entry exists and is terminal, `false` if not
     * terminal, `null` if no entry. */
    internal fun isTerminal(sid: String, mid: String, pid: String): Boolean? =
        sessions[sid]?.isTerminal(compositeKey(mid, pid))

    companion object {
        /** Max revision entries per session. Chosen as a safe bound:
         * a single message has one or a few parts; 32 accommodates multiple
         * concurrent messages while preventing unbounded map growth. */
        const val MAX_REVISIONS_PER_SESSION = 32

        private fun compositeKey(mid: String, pid: String): String =
            "$mid\u0001$pid"
    }
}
