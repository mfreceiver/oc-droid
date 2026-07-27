package cn.vectory.ocdroid.data.repository

import java.util.LinkedHashMap

/**
 * B-P0-3 (R1+R2 recovery strategy) — per-message watermark for the
 * `messageEventSeq` monotonic event counter.
 *
 * # Purpose
 *
 * The sidecar assigns each message a 64-bit event sequence number that
 * increments on every `message.part.updated` (including the initial
 * creation) and every `message.part.removed`. The client tracks the
 * largest seq it has observed per message so it can detect:
 *
 *  - **Loss**: the digest's `contentRevisions[mid]` carries a seq STRICTLY
 *    GREATER than the local one → the client missed at least one event
 *    for that message (SSE delivery gap, dropped frame, restart with
 *    replay). Recovery is a per-message `/full` fetch (B-P0-1).
 *  - **Untrustworthy value**: the digest carries seq == 0 → the sidecar
 *    just restarted and reset its seq; the client MUST treat the value
 *    as unknown and rebuild via R1 (REST `/since` reconcile).
 *
 * The watermark is per-message (NOT per-session). A session has many
 * messages, each with its own seq. The watermark map lives inside the
 * per-session accumulator held by [SlimSseState] — one
 * [MessageWatermarkState] per sessionID.
 *
 * # Fields
 *
 *  - [messageEventSeq]: the largest `messageEventSeq` observed for this
 *    message (digest or token-removal path). 0 means "uninitialised".
 *    Monotonic; the reducer NEVER regresses it.
 *  - [partRevisions]: per-part `partEventRevision` carried on token
 *    snapshot/delta frames. Used ONLY for 250ms-debounce-window dedup
 *    against re-delivered frames for the same `(messageID, partID)`.
 *    NOT a change-detection key — change detection rides the
 *    [messageEventSeq] compare above.
 *  - [needsFullRecheck]: sticky flag set whenever the reducer detects
 *    loss or an untrustworthy value. B-P0-1 consumes the flag (after a
 *    100ms debounce) to drive a per-message `/full`. Cleared ONLY by
 *    B-P0-1's successful `/full` reconcile (NOT by this data layer —
 *    clearing belongs to the network layer).
 *
 * # Constraints (B-P0-3 frozen clause)
 *
 *  - NOT a new `SlimWatermark` / `SlimMessage` / `SlimPart` /
 *    `messageRevision` type — those are explicitly disallowed by the
 *    stage-A interface-freeze. This is a NEW `MessageWatermark`, scoped
 *    to the data layer's R1+R2 support; it does NOT touch
 *    [cn.vectory.ocdroid.data.model.MessageWithParts] nor the stage-A
 *    `maxMessageTuple` / `canAdvanceLocalAppliedTuple` watermark.
 *  - Does NOT affect `SlimSessionState.remoteUpdatedAt` /
 *    `localAppliedUpdatedAt` — those remain message-level (max over the
 *    session) and are advanced by [reduceSlimDigest] /
 *    [onReconcileSuccess] exactly as before.
 */
data class MessageWatermark(
    val messageEventSeq: Long,
    val partRevisions: Map<String, Long>,
    val needsFullRecheck: Boolean = false,
)

/**
 * B-P0-3: per-session accumulator of [MessageWatermark] keyed by
 * `messageID`. Thread-safe (every public method is `@Synchronized`);
 * held by [SlimSseState] under the same lock discipline as the session
 * bookmark map.
 *
 * # LRU cap
 *
 * Capped at [cap] entries (default 500) to bound memory in long-running
 * sessions. Eviction policy is **access-order LRU**: every `get` and
 * `put` re-orders the entry to the MRU end, so an actively-updated
 * message stays alive while a stale one is evicted first. The cap is
 * enforced on `put` via `LinkedHashMap.removeEldestEntry` — reads
 * alone never trigger eviction (only re-order).
 *
 * # Reconnect semantics
 *
 * [clearAndMarkAllForReconnect] is the server.connected / resync
 * handler. It does NOT drop the messageIDs — instead it resets each
 * entry to `(messageEventSeq = 0, partRevisions = {}, needsFullRecheck
 * = true)`. The 0 value is the "untrustworthy" sentinel; the flag
 * tells B-P0-1 to drive an R1 rebuild for each preserved message.
 * Preserving the messageIDs lets B-P0-1 iterate THIS map (rather than
 * a separate message-list source) to know which messages were tracked.
 *
 * `clear()` (called on host switch via [SlimSseState.clear] /
 * `beginSlimReconfigure`) is a TOTAL wipe — different from reconnect,
 * because a host switch invalidates EVERY session, not just seq state.
 */
class MessageWatermarkState(
    private val cap: Int = DEFAULT_CAP,
) {
    // accessOrder = true → re-order on get/put (LRU, not FIFO).
    private val watermarks: MutableMap<String, MessageWatermark> =
        object : LinkedHashMap<String, MessageWatermark>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, MessageWatermark>): Boolean =
                size > cap
        }

    @Synchronized
    fun get(messageId: String): MessageWatermark? = watermarks[messageId]

    @Synchronized
    fun all(): Map<String, MessageWatermark> = LinkedHashMap(watermarks)

    @Synchronized
    fun size(): Int = watermarks.size

    @Synchronized
    fun clear() {
        watermarks.clear()
    }

    /**
     * Apply one entry from the digest's `contentRevisions` map.
     *
     * # rev-b-fix M2 (frozen protocol): rules
     *
     *  - `incomingSeq > localSeq` → advance + flag `needsFullRecheck`
     *    (the sidecar observed activity the client hasn't seen —
     *    B-P0-1 will drive a `/full` for this message).
     *  - `incomingSeq == 0L` → untrustworthy (sidecar just restarted
     *    and reset its seq counter). The client MUST treat the value
     *    as unknown and rebuild via R1.
     *    - If a prior entry exists: preserve its seq (do NOT regress),
     *      force `needsFullRecheck = true`.
     *    - **If NO prior entry exists: SEED a flagged placeholder**
     *      `(messageEventSeq = 0, partRevisions = {}, needsFullRecheck
     *      = true)`. This is the M2 fix — the previous behaviour
     *      skipped seeding entirely, so B-P0-1's R1 sweep (which scans
     *      `needsFullRecheck = true`) never discovered the message and
     *      the recovery signal was silently lost. Seeding the flagged
     *      entry guarantees R1 picks it up.
     *  - `incomingSeq == localSeq` → no-op (debounce re-emit; the
     *    flag is preserved if it was already set, never cleared here).
     *  - `incomingSeq < localSeq` → stale digest re-emit; no-op
     *    (monotonic — do NOT regress seq, do NOT touch the flag).
     *
     * @return the resulting watermark (post-update). Never null on
     *   the `incomingSeq == 0L` branch since the M2 fix (a flagged
     *   placeholder is always seeded). Null is returned ONLY on the
     *   stale/equal branch against an absent entry (a strict
     *   stale-or-equal would otherwise seed a `(0, {}, false)`
     *   placeholder with no flag — that case is genuinely a no-op).
     */
    @Synchronized
    fun applyDigestRevision(messageId: String, incomingSeq: Long): MessageWatermark? {
        val prior = watermarks[messageId]
        val priorSeq = prior?.messageEventSeq ?: 0L
        val updated = when {
            // Untrustworthy 0 (sidecar restart): preserve prior seq if
            // any (do NOT regress), force flag. M2 fix: when no prior
            // entry exists, SEED a flagged placeholder so B-P0-1's R1
            // sweep discovers this message and drives a `/full`. The
            // previous behaviour returned null here, dropping the
            // recovery signal entirely.
            incomingSeq == 0L -> (prior ?: MessageWatermark(
                messageEventSeq = 0L,
                partRevisions = emptyMap(),
            )).copy(needsFullRecheck = true)
            // Strictly newer: advance seq + flag. Seed a fresh entry if
            // none existed. Preserves any accumulated per-part revisions
            // (the seq advance doesn't invalidate dedup state).
            incomingSeq > priorSeq -> MessageWatermark(
                messageEventSeq = incomingSeq,
                partRevisions = prior?.partRevisions ?: emptyMap(),
                needsFullRecheck = true,
            )
            // Equal or older: no-op. Preserve prior entry as-is.
            else -> prior
        }
        if (updated != null && updated !== prior) {
            watermarks[messageId] = updated
        }
        return updated
    }

    /**
     * Apply a per-part revision carried on a token snapshot / delta
     * frame. Returns `true` iff this is a FRESH event for
     * `(messageId, partId)` — i.e. the revision is strictly greater
     * than the previously-applied one (or there was no prior entry).
     *
     * # rev-b-fix M1 (frozen protocol): strict `>`
     *
     * The `partEventRevision` is a per-`(messageId, partId)` monotonic
     * counter owned by the sidecar. The client's filter rule is strict
     * `>` — a frame whose revision is lower than OR equal to the
     * previously-applied one is a re-delivery (within the caller's
     * 250ms debounce window OR a stale snapshot replay) and MUST be
     * rejected. The previous implementation only rejected equality,
     * accepting lower revisions (6 → 5) and silently regressing the
     * dedup cursor — which then accepted a subsequent 6 as "fresh",
     * re-firing the debounce for content the UI had already merged.
     *
     * Rules (frozen):
     *  - `partEventRevision == null` → accept (`true`); older sidecar
     *    / status-only frame, no counter to dedup against. Stored
     *    state is untouched.
     *  - `prior == null` (no entry for `(messageId, partId)`) and
     *    `revision >= 0` → accept (`true`) + store. The first frame
     *    for a partID is always fresh even at revision 0.
     *  - `revision > prior` → accept (`true`) + store.
     *  - `revision <= prior` (strictly less OR equal) → reject
     *    (`false`); re-delivery, drop caller-side. Stored state is
     *    untouched (no regression).
     *
     * The caller uses the boolean to dedup within its 250ms debounce
     * window. The watermark map only stores the latest revision per
     * `(messageId, partId)`; the data layer does NOT enforce a time
     * window (that's the caller's concern).
     *
     * Does NOT touch [MessageWatermark.messageEventSeq] — the per-part
     * revision is dedup-only and is NOT a change-detection key.
     */
    @Synchronized
    fun applyPartRevision(
        messageId: String,
        partId: String,
        partEventRevision: Long?,
    ): Boolean {
        // No revision counter on the frame → cannot dedup → accept.
        if (partEventRevision == null) return true
        val prior = watermarks[messageId]
        val priorPartRev = prior?.partRevisions?.get(partId)
        // M1 fix: strict `>` — reject lower OR equal revisions. Equal
        // is a same-frame re-delivery (debounce); lower is a stale
        // snapshot replay (the sidecar's monotonic counter does NOT
        // regress, so a lower value is by construction a replay).
        if (priorPartRev != null && partEventRevision <= priorPartRev) {
            return false
        }
        val updatedPartRevisions =
            (prior?.partRevisions ?: emptyMap()) + (partId to partEventRevision)
        val updated = (prior ?: MessageWatermark(
            messageEventSeq = 0L,
            partRevisions = emptyMap(),
        )).copy(partRevisions = updatedPartRevisions)
        watermarks[messageId] = updated
        return true
    }

    /**
     * Apply a `message.part.removed` event. The sidecar sends the
     * POST-INCREMENT `messageEventSeq` (i.e. the value AFTER bumping
     * it for this removal); the client advances monotonically (never
     * client-side increments).
     *
     *  - Drops the removed partID from [MessageWatermark.partRevisions]
     *    (partIDs are NOT reused upstream; the entry is dead weight).
     *  - Advances [MessageWatermark.messageEventSeq] to the sidecar's
     *    value (monotonic — stale `incomingSeq <= priorSeq` is a no-op
     *    so a delayed re-delivery can't regress).
     *  - Flags `needsFullRecheck = true` so B-P0-1 drives a per-message
     *    `/full` (100ms-debounced upstream).
     *
     * @return the post-update watermark, or the prior entry if the
     *   incoming seq was stale (no-op).
     */
    @Synchronized
    fun applyPartRemoved(
        messageId: String,
        partId: String,
        incomingSeq: Long,
    ): MessageWatermark? {
        val prior = watermarks[messageId]
        val priorSeq = prior?.messageEventSeq ?: 0L
        // Monotonic: a stale re-delivery (incomingSeq <= priorSeq) is
        // a no-op — do NOT regress, do NOT re-flag.
        if (incomingSeq <= priorSeq) return prior
        val updatedPartRevisions =
            (prior?.partRevisions ?: emptyMap()) - partId
        val updated = MessageWatermark(
            messageEventSeq = incomingSeq,
            partRevisions = updatedPartRevisions,
            needsFullRecheck = true,
        )
        watermarks[messageId] = updated
        return updated
    }

    /**
     * Apply a `message.removed` event — remove the watermark entry
     * entirely. Does NOT flag `needsFullRecheck` (no `/full` is
     * triggered for a removed message — there's nothing to fetch).
     *
     * Returns the removed watermark (or null if none existed), so the
     * caller can branch on "we were tracking this message" if needed.
     */
    @Synchronized
    fun removeMessage(messageId: String): MessageWatermark? =
        watermarks.remove(messageId)

    /**
     * B-P0-1: clears ONLY the `needsFullRecheck` sticky flag for
     * [messageId], preserving `messageEventSeq` and `partRevisions`.
     *
     * Called by the R1 reconcile path ([SlimFullReconciler]) after a
     * successful `/full` (200 with body OR 304 Not Modified — both mean
     * the client's view of this message is now authoritative). The data
     * layer NEVER clears this flag on its own — clearing belongs to the
     * network layer (B-P0-1's exclusive job per the B-P0-3 frozen
     * contract).
     *
     * Monotonic + access-order LRU preserved: the entry is re-put with
     * `needsFullRecheck = false`, which both promotes it to MRU (an
     * actively-reconciled message stays alive) and clears the flag
     * atomically under this monitor.
     *
     * @return `true` iff the flag was actually cleared (the entry
     *   existed AND had `needsFullRecheck = true`); `false` on a no-op
     *   (absent entry OR flag already clear). The caller MAY use the
     *   boolean to detect a redundant reconcile (e.g. a 100ms-debounced
     *   sweep re-entering for a message that was already cleared by an
     *   earlier sweep).
     */
    @Synchronized
    fun clearFullRecheckFlag(messageId: String): Boolean {
        val prior = watermarks[messageId] ?: return false
        if (!prior.needsFullRecheck) return false
        watermarks[messageId] = prior.copy(needsFullRecheck = false)
        return true
    }

    /**
     * Reconnect / resync reset: clear all seq state AND flag every
     * preserved messageID `needsFullRecheck = true`. The sidecar's seq
     * counter resets to 0 on restart, so any prior seq we hold is
     * untrustworthy — R1 (REST `/since`) will rebuild.
     *
     * The messageID set is PRESERVED (not wiped) so B-P0-1 can iterate
     * THIS map's entries to drive R1 for each previously-tracked
     * message. After this call returns, every entry is
     * `(messageEventSeq = 0, partRevisions = {}, needsFullRecheck = true)`.
     *
     * @return the set of messageIDs that were known before the reset
     *   (i.e. the R1 work set for B-P0-1). Empty if the map was empty.
     */
    @Synchronized
    fun clearAndMarkAllForReconnect(): Set<String> {
        val known = watermarks.keys.toSet()
        if (known.isEmpty()) return emptySet()
        val reset = LinkedHashMap<String, MessageWatermark>(known.size)
        for (id in known) {
            reset[id] = MessageWatermark(
                messageEventSeq = 0L,
                partRevisions = emptyMap(),
                needsFullRecheck = true,
            )
        }
        watermarks.clear()
        watermarks.putAll(reset)
        return known
    }

    /**
     * rev-ogpt #2 (read-only seq pre-check): pure validation of the
     * same rules that [commitFull200Seq] enforces, WITHOUT mutating
     * the map. Used by [SlimSseStateMachine.commitFull200] to validate
     * [responseSeq] BEFORE invoking the (now-Boolean) UI commit
     * lambda, so that:
     *
     *  - seq validation runs first (fail-fast on stale / protocol
     *    failure without ever calling the UI lambda);
     *  - the UI lambda runs only on the would-accept path;
     *  - the watermark is mutated (seq advance + flag clear) ONLY
     *    when the UI lambda reports acceptance (route/bundle CAS
     *    passed). On UI rejection, the flag + seq are preserved
     *    (the next digest sweep / route reactivation retries).
     *
     * # Rules (frozen, mirror [commitFull200Seq])
     *
     *  - `responseSeq <= 0` → `false` (protocol failure — the sidecar
     *    MUST advertise a strictly-positive seq on a 200; `0` is the
     *    uninitialised / untrustworthy sentinel and is rejected).
     *  - `responseSeq < currentSeq` → `false` (stale response that
     *    lag behind the watermark).
     *  - otherwise → `true`.
     *
     * Read-only: does NOT touch the watermark map. The caller MUST
     * follow up with [commitFull200Seq] to perform the mutation.
     */
    @Synchronized
    fun canCommitFull200Seq(messageId: String, responseSeq: Long): Boolean {
        // Protocol failure: 0 / negative seq is the sentinel; reject.
        if (responseSeq <= 0L) return false
        val prior = watermarks[messageId]
        val currentSeq = prior?.messageEventSeq ?: 0L
        // Stale response: do NOT merge, do NOT clear flag.
        if (responseSeq < currentSeq) return false
        return true
    }

    /**
     * rev-b-fix §3 (commit port for Lane R/O2): atomic data-layer
     * half of [SlimSseStateMachine.commitFull200]. Advances
     * `messageEventSeq` to [responseSeq] AND clears the
     * `needsFullRecheck` flag, in ONE `@Synchronized` critical
     * section so the seq advance + flag clear are observable
     * together (no window where the flag is clear but the seq hasn't
     * moved, or vice versa).
     *
     * # Rules (frozen)
     *
     *  - `responseSeq <= 0` → `false` (protocol failure — the sidecar
     *    MUST advertise a strictly-positive seq on a 200; `0` is the
     *    uninitialised/untrustworthy sentinel and is rejected).
     *  - `responseSeq < currentSeq` → `false` (a stale response that
     *    lag behind the watermark; do NOT merge, do NOT clear flag).
     *  - otherwise → advance `messageEventSeq` to [responseSeq]
     *    (monotonic), clear `needsFullRecheck`, return `true`.
     *
     * Part revisions are preserved (the `/full` body's parts are
     * merged at the UI layer; the watermark's dedup cursor is not
     * invalidated by the seq advance). A fresh entry is seeded if
     * none existed (the 200 reconciled a message the digest path
     * hadn't seen yet).
     *
     * The caller (state machine) runs this inside its own
     * `withSlimStateCommit` token-guard critical section AND invokes
     * the UI commit lambda in the SAME outer critical section when
     * this method returns `true`. This method does NOT call the UI
     * lambda — it is purely the data-layer half.
     *
     * # rev-ogpt #2 — call AFTER the UI commit verdict
     *
     * [SlimSseStateMachine.commitFull200] now validates [responseSeq]
     * via [canCommitFull200Seq] FIRST, then runs the (Boolean) UI
     * commit lambda, and ONLY calls this method to mutate the
     * watermark when the UI lambda returned `true`. Direct callers
     * (tests, downstream ports) MAY still call this method directly —
     * the rules are unchanged; the new pre-check is purely an
     * additional call-site discipline for the state machine.
     */
    @Synchronized
    fun commitFull200Seq(messageId: String, responseSeq: Long): Boolean {
        // Delegate validation to the shared read-only helper so the
        // rules can't drift between the pre-check and the mutation.
        if (!canCommitFull200Seq(messageId, responseSeq)) return false
        val prior = watermarks[messageId]
        val updated = (prior ?: MessageWatermark(
            messageEventSeq = 0L,
            partRevisions = emptyMap(),
        )).copy(
            messageEventSeq = responseSeq,
            needsFullRecheck = false,
        )
        watermarks[messageId] = updated
        return true
    }

    /**
     * rev-b-fix §4 (commit port for Lane R/O2): atomic data-layer
     * half of [SlimSseStateMachine.commitFull304]. Clears the
     * `needsFullRecheck` flag IFF the current `messageEventSeq`
     * EXACTLY matches [requestSeq] (the seq the caller observed when
     * it issued the `/full?known=` request).
     *
     * # Why exact-equality (frozen)
     *
     * A 304 Not Modified means "your fingerprint is authoritative".
     * The fingerprint included `known.messageEventSeq`, so the
     * sidecar confirmed that seq matched its current state AT REQUEST
     * TIME. If the local seq has since ADVANCED (a `message.part.*`
     * event arrived over SSE between request and 304 — the network
     * window), the client has NEW information the 304 didn't account
     * for, and clearing the flag would discard a real recovery
     * signal. The 304's "your view is authoritative" assertion no
     * longer holds; keep the flag set so the next sweep re-fetches.
     *
     * If the local seq has REGRESSED (shouldn't happen — monotonic),
     * the request was issued against a state we no longer recognise;
     * keep the flag.
     *
     *  - absent entry → `false` (no watermark to clear; nothing to do).
     *  - `currentSeq != requestSeq` → `false` (seq moved during the
     *    network window; do NOT clear the flag — the next sweep will
     *    re-fetch against the new seq).
     *  - flag already clear → `false` (no-op).
     *  - otherwise → clear flag, return `true`.
     */
    @Synchronized
    fun clearFlagIfSeqMatches(messageId: String, requestSeq: Long): Boolean {
        val prior = watermarks[messageId] ?: return false
        if (prior.messageEventSeq != requestSeq) return false
        if (!prior.needsFullRecheck) return false
        watermarks[messageId] = prior.copy(needsFullRecheck = false)
        return true
    }

    companion object {
        /**
         * Default LRU cap: 500 messages per session. Bounds memory in
         * long sessions; an actively-streaming message is never evicted
         * (LRU access-order re-uses it on every token frame).
         */
        const val DEFAULT_CAP = 500
    }
}
