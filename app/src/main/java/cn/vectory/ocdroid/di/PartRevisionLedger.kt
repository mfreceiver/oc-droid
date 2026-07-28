package cn.vectory.ocdroid.di

import java.util.concurrent.ConcurrentHashMap

/**
 * B-4 HIGH-2: per-session bounded revision ledger with terminal tombstone
 * semantics (rev-gpt fix).
 *
 * Replaces the raw `ConcurrentHashMap<String, Long>` used by the original
 * [tokenStreamProductionHooks]. The key differences:
 *
 * 1. **Terminal tombstone**: `markTerminal` sets an `isTerminal` flag instead
 *    of removing the entry. A terminal entry rejects ALL subsequent revisions
 *    (same, lower, higher, null) — preventing part resurrection after
 *    done/truncated. Only [clearSession] / [remove] / [removeMessage] remove
 *    tombstones.
 *
 * 2. **Bounded capacity**: each session is capped at [maxEntriesPerSession]
 *    entries. When the cap is reached and a genuinely new (mid,pid) pair
 *    tries to enter, the ledger returns `false` (fail-closed). The cap is
 *    **hard** under concurrent inserts for distinct keys because each
 *    session's admission+size+insert is protected by a per-session mutex.
 *    Different sessions can still be parallel.
 *
 * 3. **Null revision**: nullable `partEventRevision` is now incorporated into
 *    the ledger itself (not bypassed by the caller). Null revisions for an
 *    active (non-terminal) key are accepted (fail-open compatible). Null
 *    revisions for a terminal key are rejected. A new null key occupies a
 *    bounded slot. When at capacity, a new null key is rejected (fail-closed).
 *    The first non-null revision after a null one upgrades the watermark.
 *
 * 4. **Overflow safety**: terminal watermarks are NEVER evicted to make room
 *    (doing so would allow replay/re-resurrection). Non-terminal entries are
 *    also NOT evicted (that would break an in-flight streaming part). The only
 *    safe overflow action is fail-closed admission rejection. There is NO
 *    automatic recovery mechanism — the caller must handle rejection through
 *    existing lifecycle/reconcile paths.
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
 * Fail-closed admission (reject new keys when at capacity) is the safest
 * policy for a revision ledger: it guarantees that once a revision is
 * recorded (especially a terminal one), it is never silently forgotten.
 * The downside is that a genuinely new part whose key cannot be admitted
 * will have its frame dropped. The production [dedupPartRevision] hook
 * returns `false` for rejected frames, causing the coordinator to skip
 * reduction entirely. Recovery depends on the existing lifecycle (close/open,
 * resync, reconcile) — there is no automatic recovery path for overflow.
 *
 * If replay attacks from full-session tombstones become a practical concern,
 * an alternative policy could evict the oldest NON-terminal entry, but this
 * risks breaking an in-flight streaming part and is not implemented here.
 */
class PartRevisionLedger(
    private val maxEntriesPerSession: Int = MAX_REVISIONS_PER_SESSION,
) {

    private data class Entry(
        val revision: Long?,
        val isTerminal: Boolean,
    )

    /**
     * Per-session ledger with synchronized admission+size+insert.
     * Different sessions are independent and can be parallel.
     */
    private class SessionLedger(private val maxEntries: Int) {
        private val entries = HashMap<String, Entry>()

        /**
         * Attempt to admit a key with given revision.
         *
         * @return `true` if admitted (fresh), `false` if rejected
         *   (stale/terminal/cap full).
         */
        fun admit(key: String, rev: Long?): Boolean = synchronized(this) {
            val existing = entries[key]

            // Rule 1: Terminal tombstone rejects ALL revisions (same/lower/higher/null).
            if (existing != null && existing.isTerminal) return false

            // Rule 2: Non-null stale/duplicate (<= existing watermark).
            if (existing != null && rev != null && existing.revision != null && rev <= existing.revision) return false

            // Rule 3: New key at capacity — fail-closed.
            if (existing == null && entries.size >= maxEntries) return false

            // Accept: update entry for non-null rev (new key or existing upgrade).
            if (rev != null) {
                entries[key] = Entry(rev, isTerminal = false)
            } else if (existing == null) {
                // New null key occupies a slot.
                entries[key] = Entry(null, isTerminal = false)
            }
            // else: existing non-terminal, repeated null — no entry change, still accepted.

            return true
        }

        /**
         * Mark an EXISTING entry as terminal. NO-OP if the key has never been
         * admitted — absent tombstones would silently bypass the capacity cap.
         * The coordinator's [onPartDone][cn.vectory.ocdroid.di.TokenStreamProductionHooks.onPartDone]
         * is always called after successful [admit], so the entry always exists
         * in the normal flow.
         */
        fun markTerminal(key: String) = synchronized(this) {
            val existing = entries[key] ?: return
            entries[key] = existing.copy(isTerminal = true)
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
     * This is the ONLY mechanism that clears terminal tombstones at the
     * epoch/lifecycle boundary.
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
