package cn.vectory.ocdroid.di

import java.util.concurrent.ConcurrentHashMap

/**
 * T5-C4a + T5-review I1: thread-safe atomic-claim dedup set shared by the 30s
 * poller and the SSE bridge so the same logical event id is notified at most
 * once (a double-notify would stack `SOUND|VIBRATE` and confuse the user).
 *
 * Contract: callers MUST use [claim] BEFORE notifying. [claim] returns a
 * unique **owner-aware token** (or `null` if the key is already in flight /
 * posted) — exactly one claimant wins per key. On a successful notify the
 * winner MUST call [complete] with the SAME token to transition the slot to
 * [State.Posted] (which makes it eligible for idle pruning). On a failed /
 * suppressed notify the winner MUST call [release] with the SAME token to
 * roll back the claim so the next attempt can retry (§Stage D / gpter 重要 #4).
 *
 * T5-review I1 fix: the prior implementation used a flat `ConcurrentHashMap`
 * key set + key-only `release`. That had two defects:
 *  1. `retainAll(active)` (idle pruning) removed in-flight CLAIMED entries →
 *     a second claimant could then win `claim(K)` for an item whose first
 *     claimant was still posting → both notified.
 *  2. Key-only `release` had an ABA surface: claimant A released → claimant
 *     B claimed → claimant A's stale `release(K)` from a re-entrant path
 *     would remove B's claim.
 *
 * The new state-aware map fixes both: pruning touches ONLY [State.Posted]
 * entries (in-flight CLAIMED entries survive); [release] / [complete] are
 * token-gated via [MutableMap.compute] so only the owning claimant can
 * transition the slot. All three mutations (`claim`, `complete`/`release`,
 * and `retainAll`-based pruning) are individually atomic via CHM's
 * `putIfAbsent` / `compute` / iterator-remove — no lock needed.
 */
internal class NotificationDedup {

    // T5-round-4 I1-S: widened from `private` to `internal` so the fenced
    // prune API ([snapshotPosted] / [pruneStaleCandidates]) can express its
    // return/param type as `State.Posted` — the exact captured generation
    // instance the atomic `computeIfPresent` compares against. Still
    // module-internal (the owning class [NotificationDedup] is `internal`);
    // no subtype can be added from outside (sealed) and the backing `keys`
    // map stays private, so external code cannot inject state.
    internal sealed interface State {
        /** Reserved by a claimant that has not yet completed (token = owner id). */
        class Claimed(val token: String) : State
        /**
         * Notify succeeded — eligible for idle pruning.
         *
         * T5-re-review M1-R Posted-ABA fix: the prior `object Posted`
         * singleton made every completed generation identity-equal, so a
         * stale pruner that captured A's old `Posted` could later remove
         * B's freshly-completed `Posted` at the same key (they were `===`
         * to the SAME singleton). Mirroring `Claimed(val token: String)`,
         * `Posted` now carries the completing owner's token. The data-class
         * `==` includes the token, so a stale pruner that captured
         * `Posted(tokenA)` cannot match B's `Posted(tokenB)` — a stale
         * pruner removes NEITHER a current `Claimed(tokenB)` NOR a newer
         * `Posted(tokenB)` belonging to a different generation.
         */
        data class Posted(val token: String) : State
    }

    private val keys: MutableMap<String, State> = ConcurrentHashMap()

    /**
     * Bug-1 (notification regeneration): seeds persisted "already-notified"
     * keys directly into the [State.Posted] slot so a freshly-booted process
     * suppresses re-notification for items already posted before process
     * death. Without this seed, the in-memory map starts empty after every
     * restart and the next background poll re-notifies every idle unread root
     * (≈ weekly regeneration). The persisted keys come from
     * [NotificationDedupStore] (snapshotPosted → save*Keys round-trip).
     *
     * Init-only: MUST run before any [claim] (the [AppLifecycleMonitor] init
     * block calls it once before polling starts). All seeded entries are
     * installed as [State.Posted] (not [State.Claimed]) so the existing prune
     * path treats them identically to a runtime-completed notification — they
     * ARE eligible for idle pruning when the root leaves the active set, which
     * is the correct semantics (a root that's no longer idle should be
     * prunable the same way regardless of whether it was notified in this
     * process or a prior one).
     *
     * Each key is installed via `putIfAbsent`, so a key already present (e.g.
     * an in-flight [State.Claimed] from a concurrent path) is NOT overwritten
     * — the live claim wins. This is the only [NotificationDedup] mutation
     * that touches [keys] outside the ABA-correct claim/complete/release/
     * prune family; it is structurally safe because it only ever installs
     * terminal [State.Posted] entries and never transitions existing ones.
     */
    internal fun seedPosted(keys: Set<String>) {
        keys.forEach { k -> this.keys.putIfAbsent(k, State.Posted(SEED_TOKEN)) }
    }

    private companion object {
        /**
         * Bug-1: token carried by seed-installed [State.Posted] entries so a
         * debugger / log can distinguish "seeded from persistence" from
         * "completed by a real claim→notify". The token participates in
         * data-class equality for [State.Posted], so a pruner that captured
         * `Posted(SEED_TOKEN)` matches the current value `Posted(SEED_TOKEN)`
         * exactly (idempotent prune), and a fresh runtime `complete` installs
         * a different token (`Posted(<uuid>)`) so the existing ABA guards are
         * unaffected.
         */
        const val SEED_TOKEN = "seed"
    }

    /**
     * Atomically claims [key]. Returns a fresh owner token iff the key was
     * newly claimed (was absent); returns `null` if the key is already
     * [State.Claimed] or [State.Posted] (another claimant won, or this key
     * was already notified and not yet pruned). The returned token MUST be
     * passed to [complete] / [release] so only this owner can transition
     * the slot.
     */
    fun claim(key: String): String? {
        val token = java.util.UUID.randomUUID().toString()
        // putIfAbsent is atomic on CHM: the first claimant wins.
        val prior = keys.putIfAbsent(key, State.Claimed(token))
        return if (prior == null) token else null
    }

    /**
     * Transitions the owner's [claim] to [State.Posted] (notify succeeded).
     * Returns `true` iff the slot was [State.Claimed] with this exact token.
     * No-op (returns `false`) if the slot was already transitioned by another
     * path or the token does not match — guards against stale completions.
     *
     * T5-re-review M1-R Posted-ABA fix: installs `Posted(token)` — the
     * completer's OWN token — so the resulting [State.Posted] instance is
     * generation-distinct. A later stale pruner that observed a prior
     * `Posted(tokenOther)` cannot remove this entry via `retainAll` (the
     * data-class `==` compares tokens).
     */
    fun complete(key: String, token: String): Boolean {
        var transitioned = false
        keys.compute(key) { _, state ->
            if (state is State.Claimed && state.token == token) {
                transitioned = true
                State.Posted(token)
            } else {
                state
            }
        }
        return transitioned
    }

    /**
     * Rolls back the owner's [claim] (notify failed / suppressed) so a future
     * attempt can retry. Token-gated: a no-op if the slot was already
     * [State.Posted] (a successful [complete]) or the token does not match.
     * This closes the prior ABA on key-only `release` — only the owning
     * claimant can release its own claim.
     */
    fun release(key: String, token: String) {
        keys.compute(key) { _, state ->
            if (state is State.Claimed && state.token == token) null else state
        }
    }

    /** Read-only membership test (no claim). Used by tests. */
    fun contains(key: String): Boolean = keys.containsKey(key)

    /**
     * §18.1 idle pruning — drops entries whose session is no longer active.
     *
     * T5-review I1 fix: removes ONLY [State.Posted] entries — in-flight
     * [State.Claimed] entries survive pruning so a polling race cannot
     * strip a claim out from under a claimant that is mid-notify (which
     * would let a second claimant win and double-notify).
     *
     * T5-re-review I1-R fix: the prior iterator-remove (`it.remove()`)
     * was NOT atomic-conditional on [State.Posted]. The iterator observed
     * the value and removed the KEY — between the check and the remove
     * another path could install [State.Claimed](tokenB) at the same key,
     * and the stale `it.remove()` would strip B's fresh claim. Residual
     * race driver: lifecycle restart cancels `pollJob` without joining
     * (`:490-519`), so two overlapping `retainAll` passes CAN interleave.
     * The per-key [ConcurrentHashMap.computeIfPresent] is atomic: the
     * removal fires ONLY if the value is STILL [State.Posted] at the
     * instant of the compute — a [State.Claimed] entry can NEVER be
     * removed by pruning, even under overlapping pruners.
     *
     * T5-re-review M1-R Posted-ABA fix: `State.Posted` is now a
     * `data class Posted(token)` carrying the completer's token. To close
     * the ABA surface (stale pruner captured A's old `Posted(tokenA)`,
     * pauses; B re-completes K to a FRESH `Posted(tokenB)`; stale pruner
     * resumes and would have stripped B's freshly-completed Posted because
     * `=== State.Posted` matched the SAME singleton), each pruner iteration
     * captures the EXACT observed [State.Posted] value `p` (token included)
     * and the atomic `computeIfPresent` removes the entry ONLY if the
     * current value `== p` (data-class equality compares the token). A
     * stale pruner holding `Posted(tokenA)` therefore cannot match B's
     * `Posted(tokenB)` — the newer generation survives. The captured `p`
     * is a stable snapshot (data class is immutable); CHM's
     * `computeIfPresent` runs the remap under its per-bucket lock, so the
     * observed-vs-current comparison is atomic and free of TOCTOU.
     */
    fun retainAll(active: Set<String>) {
        keys.forEach { (key, state) ->
            if (key !in active && state is State.Posted) {
                // Capture the EXACT observed Posted generation (token included).
                val captured = state
                keys.computeIfPresent(key) { _, v ->
                    // Data-class == compares the token; a stale pruner that
                    // captured Posted(tokenA) does NOT match a newer
                    // Posted(tokenB) installed between the snapshot and this
                    // compute. A Claimed(tokenB) also does not match
                    // (different State subtype) — it survives.
                    if (v == captured) null else v
                }
            }
        }
    }

    /**
     * T5-round-4 I1-S fence step 1: captures the currently-[State.Posted]
     * entries (key → the EXACT captured `Posted(token)` generation instance)
     * as an immutable snapshot. Each entry is observed atomically per-key
     * via the [ConcurrentHashMap] iterator; the captured [State.Posted] is a
     * stable data-class instance (immutable), so the snapshot is a stable
     * read of "which keys were Posted, and at which generation, at the
     * instant [snapshotPosted] ran".
     *
     * The caller MUST capture this BEFORE the prune-time active set is
     * computed (before the poll runs). The returned map is then handed to
     * [pruneStaleCandidates] as the candidate fence: a `Posted` created
     * AFTER this snapshot is NOT in the returned map and is therefore
     * NEVER touched by that prune cycle (see [pruneStaleCandidates]).
     */
    fun snapshotPosted(): Map<String, State.Posted> {
        // Explicit local (not buildMap): a buildMap lambda's MutableMap
        // receiver has its own `keys: Set<K>` property that would shadow
        // the outer `keys` field (Map.keys vs this.keys), breaking the
        // entry-destructuring forEach below.
        val snapshot = LinkedHashMap<String, State.Posted>()
        keys.forEach { (k, v) -> if (v is State.Posted) snapshot[k] = v }
        return snapshot
    }

    /**
     * T5-round-4 I1-S fence step 2: prunes ONLY entries present in
     * [candidates] (captured BEFORE the poll by [snapshotPosted]) that are
     * absent from [active]. For each such `(k, capturedPosted)`, atomically
     * removes the entry ONLY if the current value is STILL that exact
     * captured generation:
     *
     * ```
     * keys.computeIfPresent(k) { _, v -> if (v == capturedPosted) null else v }
     * ```
     *
     * Data-class `==` compares the token, so a stale pruner holding
     * `Posted(tokenA)` cannot match a newer `Posted(tokenB)` (the existing
     * M1-R guard), and a `Claimed(tokenB)` (different subtype) never matches
     * (the existing I1-R guard).
     *
     * **The I1-S closure**: the scan is restricted to [candidates], NOT to
     * the live map. A `Posted(tokenB)` created AFTER [snapshotPosted] ran
     * (e.g. the SSE bridge completing a claim during the poll) is NOT in
     * [candidates] → `computeIfPresent` is never invoked on its key by this
     * cycle → it survives. Under the OLD bare `retainAll(active)` the poller
     * scanned the map AFTER the active set was computed, so it could capture
     * B's freshly-completed `Posted(tokenB)` and (since captured == current,
     * both tokenB) remove it — C re-claimed → duplicate sound/vibration.
     * Token-awareness could not help: P captured B's CURRENT token, not an
     * old one. The fence is the only fix: the candidate set is frozen before
     * the poll.
     *
     * A legitimate stale entry kept one cycle longer (became Posted after
     * the snapshot) is harmless — the NEXT cycle's snapshot captures it and
     * prunes it if still stale. The dedup intent (don't re-notify) is
     * preserved either way.
     */
    fun pruneStaleCandidates(candidates: Map<String, State.Posted>, active: Set<String>) {
        candidates.forEach { (k, captured) ->
            if (k !in active) {
                keys.computeIfPresent(k) { _, v -> if (v == captured) null else v }
            }
        }
    }

    /**
     * T5-re-review M1-R Posted-ABA test hook: deterministically drives the
     * stale-pruner interleaving that production [retainAll] must survive.
     *
     * Captures the current [State.Posted] generation at [key] (mirroring
     * what a real pruner observes when it first scans the entry), runs
     * [between] (simulating P1's pause — during which P2 may prune the
     * entry and B may re-claim + re-complete it), then resumes the atomic
     * conditional remove against the CAPTURED generation. The capture +
     * atomic-resume shape is identical to production [retainAll] per-key;
     * the only difference is the interleaving seam (production does not
     * pause between capture and compute, but it COULD be preempted by
     * another pruner — this hook forces that preemption deterministically).
     *
     * Test-only; production callers use [retainAll].
     */
    internal fun testOnlyPruneWithInterleave(key: String, between: () -> Unit) {
        val state = keys[key]
        if (state is State.Posted) {
            val captured = state
            between()
            keys.computeIfPresent(key) { _, v -> if (v == captured) null else v }
        }
    }
}
