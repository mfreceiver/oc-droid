package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.di.NotificationDedup
import cn.vectory.ocdroid.di.SessionNotifier
import cn.vectory.ocdroid.service.events.IdentifiedSseEvent
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.ui.controller.IdleUnreadAlert
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T5-C4 — pure-JVM unit tests for [SseNotificationBridge].
 *
 * Covers the three required invariants from the task brief:
 *  1. **Foreground**: no notification fires for `question.asked` OR
 *     `session.status{idle}` when `isInForeground == true`.
 *  2. **Background + new `question.asked`**: fires exactly once; the same
 *     id arriving a second time is deduped (no second fire).
 *  3. **Shared dedup**: the SAME id arriving via BOTH the bridge AND an
 *     external claim (the 30s poller's path) results in only one
 *     notification. The shared dedup set is the primary guard; the
 *     notification id (`key.hashCode()`) is the visual fallback.
 *
 * The bridge is constructed with mock collaborators: the publisher is a
 * mockk that records `notifyDecision` / `notifyIdle` calls; the dedup sets
 * are real [NotificationDedup] instances so the atomic-claim semantics are
 * exercised; `rootIdleResolver` is a lambda backed by an inline closure.
 *
 * All tests run with [runTest] on the test's [kotlinx.coroutines.test.TestScope]
 * so the collector coroutine is virtualized. The bridge collects on
 * `backgroundScope` (provided by `runTest`); emissions + `runCurrent()`
 * drive the collector synchronously.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SseNotificationBridgeTest {

    @Test
    fun `foreground - question_asked does not fire a notification`() = runTest {
        val publisher = RecordingPublisher(returns = true)
        val bridge = newBridge(
            publisher = publisher.notifier,
            events = source,
            isInForeground = { true },
            scope = backgroundScope,
        )
        bridge.start()
        runCurrent()

        source.emitQuestion(questionId = "q-1", sessionId = "ses-1", header = "Pick a color")
        runCurrent()

        assertEquals(
            "foreground must suppress question.asked notification",
            0,
            publisher.decisionCalls.size,
        )
        bridge.stop()
    }

    @Test
    fun `foreground - session_status idle does not fire a notification`() = runTest {
        val publisher = RecordingPublisher(returns = true)
        val bridge = newBridge(
            publisher = publisher.notifier,
            events = source,
            isInForeground = { true },
            rootIdleResolver = { IdleUnreadAlert("ses-root", "title", 100L, "idle:fp:/wd:ses-root:100") },
            scope = backgroundScope,
        )
        bridge.start()
        runCurrent()

        source.emitIdle(sessionId = "ses-root")
        runCurrent()

        assertEquals(
            "foreground must suppress session.status{idle} notification",
            0,
            publisher.idleCalls.size,
        )
        bridge.stop()
    }

    @Test
    fun `background - new question_asked fires exactly once and same id is deduped`() = runTest {
        val publisher = RecordingPublisher(returns = true)
        val bridge = newBridge(
            publisher = publisher.notifier,
            events = source,
            isInForeground = { false },
            scope = backgroundScope,
        )
        bridge.start()
        runCurrent()

        source.emitQuestion(questionId = "q-1", sessionId = "ses-1", header = "Pick a color")
        runCurrent()
        source.emitQuestion(questionId = "q-1", sessionId = "ses-1", header = "Pick a color")
        runCurrent()

        assertEquals(
            "first arrival fires one notification",
            1,
            publisher.decisionCalls.size,
        )
        assertEquals("q-1's sessionId passed through", "ses-1", publisher.decisionCalls[0].sessionId)
        assertEquals("q-1's header used as body", "Pick a color", publisher.decisionCalls[0].body)
        assertEquals("q-1's key used for the notification id", "q:q-1", publisher.decisionCalls[0].key)
        bridge.stop()
    }

    @Test
    fun `background - second distinct question id fires a second notification`() = runTest {
        val publisher = RecordingPublisher(returns = true)
        val bridge = newBridge(
            publisher = publisher.notifier,
            events = source,
            isInForeground = { false },
            scope = backgroundScope,
        )
        bridge.start()
        runCurrent()

        source.emitQuestion(questionId = "q-1", sessionId = "ses-1", header = "first")
        runCurrent()
        source.emitQuestion(questionId = "q-2", sessionId = "ses-1", header = "second")
        runCurrent()

        assertEquals("two distinct ids → two notifications", 2, publisher.decisionCalls.size)
        bridge.stop()
    }

    @Test
    fun `background - failed notify releases the claim so a later attempt retries`() = runTest {
        // §Stage D deferred-delivery property: a notify that returns false
        // (e.g. permission denied) MUST release the dedup claim so the
        // next attempt can retry. Without the release, a denied
        // permission would silently swallow the event forever (the
        // subsequent grant would never trigger a re-notify).
        val publisher = RecordingPublisher(returns = false)
        val decisionDedup = NotificationDedup()
        val bridge = newBridge(
            publisher = publisher.notifier,
            events = source,
            isInForeground = { false },
            decisionDedup = decisionDedup,
            scope = backgroundScope,
        )
        bridge.start()
        runCurrent()

        source.emitQuestion(questionId = "q-1", sessionId = "ses-1", header = "first")
        runCurrent()
        assertEquals("notify was attempted", 1, publisher.decisionCalls.size)
        assertFalse("claim released after failure", decisionDedup.contains("q:q-1"))

        // Swap to a successful publisher and re-emit — must fire again
        // (the released claim re-opens the slot).
        val okPublisher = RecordingPublisher(returns = true)
        val okBridge = newBridge(
            publisher = okPublisher.notifier,
            events = source,
            isInForeground = { false },
            decisionDedup = decisionDedup, // SAME dedup set
            scope = backgroundScope,
        )
        okBridge.start()
        runCurrent()
        source.emitQuestion(questionId = "q-1", sessionId = "ses-1", header = "first")
        runCurrent()

        assertEquals(
            "released claim allows the next attempt to retry",
            1,
            okPublisher.decisionCalls.size,
        )
        bridge.stop()
        okBridge.stop()
    }

    @Test
    fun `background - session_status idle for an unread root fires once and dedupes`() = runTest {
        val publisher = RecordingPublisher(returns = true)
        val alert = IdleUnreadAlert("ses-root", "Project A", 100L, "idle:fp:/wd:ses-root:100")
        val bridge = newBridge(
            publisher = publisher.notifier,
            events = source,
            isInForeground = { false },
            rootIdleResolver = { id -> if (id == "ses-root") alert else null },
            scope = backgroundScope,
        )
        bridge.start()
        runCurrent()

        source.emitIdle(sessionId = "ses-root")
        runCurrent()
        source.emitIdle(sessionId = "ses-root")
        runCurrent()

        assertEquals(
            "first idle fires one notification; same key deduped",
            1,
            publisher.idleCalls.size,
        )
        assertEquals("ses-root", publisher.idleCalls[0].rootId)
        assertEquals("Project A", publisher.idleCalls[0].title)
        assertEquals(alert.key, publisher.idleCalls[0].key)
        bridge.stop()
    }

    @Test
    fun `background - session_status idle for a non-root or not-unread session fires nothing`() = runTest {
        val publisher = RecordingPublisher(returns = true)
        val bridge = newBridge(
            publisher = publisher.notifier,
            events = source,
            isInForeground = { false },
            // Resolver returns null → not a root / not unread / unknown.
            rootIdleResolver = { null },
            scope = backgroundScope,
        )
        bridge.start()
        runCurrent()

        source.emitIdle(sessionId = "ses-child")
        runCurrent()

        assertEquals(
            "non-root / not-unread idle does not fire (the 30s poller will catch it if warranted)",
            0,
            publisher.idleCalls.size,
        )
        bridge.stop()
    }

    @Test
    fun `background - session_status busy does not fire an idle notification`() = runTest {
        val publisher = RecordingPublisher(returns = true)
        val bridge = newBridge(
            publisher = publisher.notifier,
            events = source,
            isInForeground = { false },
            rootIdleResolver = { IdleUnreadAlert("ses-root", "title", 100L, "idle:fp:/wd:ses-root:100") },
            scope = backgroundScope,
        )
        bridge.start()
        runCurrent()

        source.emitStatus(sessionId = "ses-root", type = "busy")
        runCurrent()

        assertEquals(
            "busy/retry session.status does not fire an idle notification",
            0,
            publisher.idleCalls.size,
        )
        bridge.stop()
    }

    @Test
    fun `shared dedup - same id arriving via bridge and external poll claim fires only once`() = runTest {
        // T5-C4a core invariant: the 30s poller's path and the bridge's
        // path share the SAME dedup set. Simulate the poller having
        // already claimed the key (the poller discovered the pending
        // question first via REST), THEN the SSE event arrives. The
        // bridge MUST skip — the second `claim` returns false.
        val publisher = RecordingPublisher(returns = true)
        val sharedDecisionDedup = NotificationDedup()
        assertNotNull("poller pre-claim", sharedDecisionDedup.claim("q:q-preempted"))

        val bridge = newBridge(
            publisher = publisher.notifier,
            events = source,
            isInForeground = { false },
            decisionDedup = sharedDecisionDedup,
            scope = backgroundScope,
        )
        bridge.start()
        runCurrent()

        source.emitQuestion(questionId = "q-preempted", sessionId = "ses-1", header = "polled-first")
        runCurrent()

        assertEquals(
            "the bridge must NOT fire when the shared dedup set has already claimed the key",
            0,
            publisher.decisionCalls.size,
        )
        bridge.stop()
    }

    @Test
    fun `shared dedup - same idle key via bridge and external poll claim fires only once`() = runTest {
        val publisher = RecordingPublisher(returns = true)
        val sharedIdleDedup = NotificationDedup()
        val key = "idle:fp:/wd:ses-root:100"
        assertNotNull("poller pre-claim", sharedIdleDedup.claim(key))
        val alert = IdleUnreadAlert("ses-root", "title", 100L, key)

        val bridge = newBridge(
            publisher = publisher.notifier,
            events = source,
            isInForeground = { false },
            idleDedup = sharedIdleDedup,
            rootIdleResolver = { id -> if (id == "ses-root") alert else null },
            scope = backgroundScope,
        )
        bridge.start()
        runCurrent()

        source.emitIdle(sessionId = "ses-root")
        runCurrent()

        assertEquals(
            "shared idle dedup prevents the bridge from double-firing on a poller-claimed key",
            0,
            publisher.idleCalls.size,
        )
        bridge.stop()
    }

    // ── T5-review I1 — owner-aware dedup: pruning preserves in-flight claims ─

    /**
     * T5-review I1: a deterministic reproduction of the prior bug. Claimant A
     * wins `claim(K)` and PAUSES before posting; the idle pruner runs (it
     * used to strip in-flight CLAIMED entries); claimant B then attempts
     * `claim(K)` — under the OLD flat-set dedup B would WIN (A's claim was
     * pruned), and BOTH A and B would notify. Under the owner-aware dedup
     * the pruner skips Claimed entries; B's claim returns null; only A
     * completes → exactly one notify.
     */
    @Test
    fun `I1 - pruning preserves in-flight claim, second claimant loses, exactly one notify`() {
        val dedup = NotificationDedup()
        val key = "q:i1"

        // Claimant A wins the claim and gets a token.
        val tokenA = dedup.claim(key)
        assertNotNull("claimant A wins the initial claim", tokenA)

        // Idle pruner runs while A is paused. OLD behavior: removed K (an
        // in-flight claim) → B could re-win. NEW behavior: pruner touches
        // only Posted entries → K survives as Claimed(tokenA).
        dedup.retainAll(active = emptySet())
        assertTrue(
            "pruner must NOT strip an in-flight Claimed entry",
            dedup.contains(key),
        )

        // Claimant B attempts the same key — must NOT win.
        val tokenB = dedup.claim(key)
        assertNull("claimant B must lose (A's claim survived pruning)", tokenB)

        // A completes (transition to Posted); B never notified.
        assertTrue("owner A completes its own claim", dedup.complete(key, tokenA!!))

        // Stale release by B (had it somehow won) is a no-op via token gate.
        dedup.release(key, "not-the-owner-token")
        assertTrue("token-gated release is a no-op for non-owner", dedup.contains(key))
    }

    /**
     * T5-review I1 (ABA on key-only release): claimant A releases, claimant B
     * claims the same key, then a stale `release(K, tokenA)` arrives — under
     * the OLD key-only release this would strip B's claim. The new
     * token-gated release is a no-op for a non-matching token.
     */
    @Test
    fun `I1 - stale release with wrong token does not strip a fresh claim`() {
        val dedup = NotificationDedup()
        val key = "q:aba"

        val tokenA = dedup.claim(key)!!
        dedup.release(key, tokenA) // A legitimately releases.

        val tokenB = dedup.claim(key)!!
        assertNotNull("B re-claims after A's release", tokenB)

        // Stale release arriving from A's path — must NOT remove B's claim.
        dedup.release(key, tokenA)
        assertTrue(
            "stale key-only-equivalent release with wrong token must not strip B's claim",
            dedup.contains(key),
        )

        // B can still complete.
        assertTrue("B completes its own claim", dedup.complete(key, tokenB))
    }

    // ── T5-re-review I1-R — retainAll atomic-conditional on Posted ──────────

    /**
     * T5-re-review I1-R: the prior `retainAll` used iterator-remove, which
     * removed by KEY after observing `State.Posted`. The check-then-remove
     * was NOT atomic-conditional on Posted — under overlapping pruners a
     * stale `it.remove()` could strip a fresh `Claimed(token)` installed
     * between the observation and the remove. The fix uses per-key
     * `computeIfPresent` (atomic-conditional on CHM).
     *
     * This test proves the post-condition deterministically: B installs
     * `Claimed(tokenB)` on K; a separate key completes to `Posted` (the
     * prunable case); `retainAll(active = empty)` runs — the Posted entry
     * IS removed, but B's Claimed SURVIVES. C then `claim(K)` must return
     * null (B still owns it). A concurrent pruner that observed K before
     * B's claim can NEVER strip it under the new atomic primitive.
     */
    @Test
    fun `I1-R - retainAll is atomic-conditional on Posted, Claimed survives overlapping pruners`() {
        val dedup = NotificationDedup()
        val keyClaimed = "q:i1r-claimed"
        val keyPosted = "q:i1r-posted"

        // B installs Claimed(tokenB) on keyClaimed.
        val tokenB = dedup.claim(keyClaimed)
        assertNotNull("B wins the initial claim", tokenB)

        // A separate key completes to Posted (the prunable case).
        val tokenPosted = dedup.claim(keyPosted)!!
        assertTrue("Posted slot transitions to Posted", dedup.complete(keyPosted, tokenPosted))

        // Run retainAll with an active set that excludes BOTH keys.
        // Under the OLD iterator-remove this was a check-then-remove TOCTOU
        // (observe Posted → pause → another path installs Claimed → stale
        // remove strips the Claim). Under the NEW computeIfPresent the
        // removal fires ONLY if the value is STILL Posted at the compute
        // instant — Claimed(tokenB) can NEVER be removed by pruning.
        dedup.retainAll(active = emptySet())

        // The Posted entry WAS pruned (not in active AND state == Posted).
        assertFalse("Posted entry pruned (not in active)", dedup.contains(keyPosted))

        // B's Claimed entry survived the prune — the atomic primitive
        // verified the state was still Claimed at removal time.
        assertTrue(
            "Claimed(tokenB) survived retainAll — computeIfPresent is atomic-conditional",
            dedup.contains(keyClaimed),
        )

        // C attempts claim(K) — must return null (B's Claimed survived).
        val tokenC = dedup.claim(keyClaimed)
        assertNull(
            "C must NOT win claim(K) — B's Claimed(tokenB) survived pruning",
            tokenC,
        )

        // B can still complete its own claim (proves the slot is Claimed,
        // not Posted — Posted would make complete return false for tokenB).
        assertTrue(
            "B completes after prune survived — slot was Claimed(tokenB)",
            dedup.complete(keyClaimed, tokenB!!),
        )
    }

    // ── T5-re-review M1-R — Posted-ABA: stale pruner can't remove a newer Posted generation ─

    /**
     * T5-re-review M1-R: deterministic ABA regression for the stale-pruner
     * interleaving the prior `object State.Posted` singleton could NOT
     * distinguish. Under the OLD code every completed generation became
     * the SAME singleton, so a stale pruner's `v === State.Posted` check
     * matched B's freshly-completed Posted just as well as A's old one —
     * the stale `computeIfPresent` removed B's entry, C re-claimed the
     * now-eligible key, and BOTH B and C invoked platform `notify` on the
     * same notification id (duplicate sound/vibration). Under the NEW
     * `data class Posted(val token: String)` the pruner captures the EXACT
     * observed generation (token included) and the atomic
     * `computeIfPresent { _, v -> if (v == captured) null else v }` removes
     * the entry ONLY if the current value is STILL that same generation
     * (data-class `==` compares the token).
     *
     * 6-step deterministic sequence (driven via the test-only
     * `testOnlyPruneWithInterleave` hook, which mirrors production
     * `retainAll`'s capture-then-atomic-resume shape with an interleaving
     * seam):
     *  1. Seed `K -> Posted(tokenA)` (A completed).
     *  2. P1 captures that `Posted(tokenA)` (the value it intends to prune).
     *  3. P2 prunes A's entry (direct `retainAll(empty)` — K is Posted, not
     *     in active → removed).
     *  4. B claims K → `Claimed(tokenB)`; B completes → `Posted(tokenB)`.
     *  5. P1 resumes its atomic remove against its captured `Posted(tokenA)`
     *     → K must STILL be `Posted(tokenB)` (NOT removed).
     *  6. C `claim(K)` must return null (B's generation still owns it).
     */
    @Test
    fun `M1-R - stale pruner cannot remove a newer Posted generation (Posted-ABA closure)`() {
        val dedup = NotificationDedup()
        val key = "q:posted-aba"

        // 1. Seed K -> Posted(tokenA) (A completed).
        val tokenA = dedup.claim(key)
        assertNotNull("A wins the initial claim", tokenA)
        assertTrue("A completes its own claim → Posted(tokenA)", dedup.complete(key, tokenA!!))

        // 2. P1 captures that Posted(tokenA) (inside the hook) and pauses
        //    before the atomic computeIfPresent. During the pause:
        //    3. P2 prunes A's entry (it IS Posted and not in active).
        //    4. B reclaims K and re-completes to Posted(tokenB).
        // 5. P1 resumes its atomic remove against its captured Posted(tokenA).
        dedup.testOnlyPruneWithInterleave(key) {
            // 3. P2 prunes A's entry.
            dedup.retainAll(active = emptySet())
            assertFalse("P2 removed A's Posted(tokenA)", dedup.contains(key))

            // 4. B reclaims + re-completes (a FRESH generation, Posted(tokenB)).
            val tokenB = dedup.claim(key)
            assertNotNull("B re-claims K after P2 pruned A's entry", tokenB)
            assertTrue("B completes → Posted(tokenB)", dedup.complete(key, tokenB!!))
        }

        // 5. K must STILL be present — P1's stale prune captured
        //    Posted(tokenA); the current value is Posted(tokenB); data-class
        //    == compares the token → no match → entry survives.
        assertTrue(
            "P1's stale prune MUST NOT remove B's newer Posted(tokenB)",
            dedup.contains(key),
        )

        // 6. C must lose claim(K) — B's Posted(tokenB) still owns the slot.
        val tokenC = dedup.claim(key)
        assertNull(
            "C must NOT win claim(K) — B's Posted(tokenB) survived the stale prune",
            tokenC,
        )
    }

    /**
     * T5-re-review M1-R companion: proves the prune DOES fire when the
     * captured generation matches the current value (no ABA). Without this
     * control, the test above could pass for the wrong reason (e.g. if the
     * prune hook were itself a no-op). Here P1 captures Posted(tokenA) and
     * resumes immediately; the current value is STILL Posted(tokenA); the
     * prune fires and the entry is removed.
     */
    @Test
    fun `M1-R - prune fires when generation matches (no ABA, normal prune path)`() {
        val dedup = NotificationDedup()
        val key = "q:posted-aba-match"

        val tokenA = dedup.claim(key)!!
        assertTrue("A completes → Posted(tokenA)", dedup.complete(key, tokenA))

        // No interleave: capture Posted(tokenA) and resume immediately. The
        // current value is STILL Posted(tokenA) → data-class == matches →
        // entry removed.
        dedup.testOnlyPruneWithInterleave(key) {
            // No-op between capture and resume.
        }

        assertFalse(
            "matching-generation prune MUST remove the entry (proves the hook isn't a no-op)",
            dedup.contains(key),
        )

        // After the prune, K is eligible again — a fresh claim succeeds.
        val tokenB = dedup.claim(key)
        assertNotNull("after the prune, K is re-claimable", tokenB)
    }

    // ── T5-round-4 I1-S — fenced snapshot+prune: a posting created AFTER the snapshot survives ──

    /**
     * T5-round-4 I1-S: deterministic reproduction of the snapshot-acquire-vs-
     * prune-execute TOCTOU the OLD bare `retainAll(active)` could NOT close.
     *
     * Under the OLD protocol the poller P computed the active set S, THEN
     * scanned the map for candidates. A `Posted(tokenB)` created between
     * S-compute and `retainAll(S)` (the SSE bridge on the Service's MainScope
     * completing B's claim) WAS scanned by P, which captured B's OWN current
     * `tokenB` at scan time — the generation-conditional `computeIfPresent`
     * then SUCCEEDED (captured == current, both tokenB) and removed B's
     * posting. C re-claimed the now-absent K → duplicate sound/vibration.
     * Token-awareness cannot help: P captured B's CURRENT token, not an old
     * one. The defect is the snapshot-acquire-vs-prune-execute gap.
     *
     * Fix: the fenced `snapshotPosted()` + `pruneStaleCandidates(candidates, S)`
     * API. Candidates are captured BEFORE the poll; a posting created after
     * the snapshot is NOT in `candidates` and survives this cycle.
     *
     * 6-step deterministic sequence:
     *  1. Seed: K is empty (no prior posting).
     *  2. `candidates = snapshotPosted()` captured BEFORE B posts → K ∉ candidates.
     *  3. Compute S without K (K inactive per the stale active snapshot).
     *  4. B claims K, notifies, completes → `Posted(tokenB)` (AFTER candidate capture).
     *  5. `pruneStaleCandidates(candidates, S)` → K must SURVIVE (not in candidates).
     *  6. C `claim(K)` → returns null (B owns). No duplicate.
     */
    @Test
    fun `I1-S - posting created after the candidate snapshot survives the prune (TOCTOU fence)`() {
        val dedup = NotificationDedup()
        val key = "idle:i1-s:/wd:ses-root:100"

        // 1. K is empty (no prior posting).
        assertFalse("K starts empty", dedup.contains(key))

        // 2. candidates captured BEFORE B posts — K is NOT in candidates.
        val candidates = dedup.snapshotPosted()
        assertFalse(
            "candidate snapshot taken before B posted — K must NOT be a candidate",
            candidates.containsKey(key),
        )

        // 3. S computed without K (K inactive per the stale active snapshot).
        val active = emptySet<String>()

        // 4. B claims K, notifies (out of band), completes → Posted(tokenB)
        //    AFTER the candidate capture. This is the heart of the TOCTOU:
        //    B's posting now exists in the map but was NOT in the pre-poll
        //    snapshot. Under the OLD bare retainAll(active) the subsequent
        //    scan would find K as Posted(tokenB) ∉ S, capture tokenB, and
        //    remove it (captured == current).
        val tokenB = dedup.claim(key)
        assertNotNull("B wins the claim (K was empty)", tokenB)
        assertTrue("B completes → Posted(tokenB)", dedup.complete(key, tokenB!!))

        // 5. pruneStaleCandidates(candidates, S) → K must SURVIVE. The fence
        //    restricts the scan to `candidates`, which never contained K →
        //    K is untouched even though K ∉ active AND K is currently Posted.
        dedup.pruneStaleCandidates(candidates, active)
        assertTrue(
            "B's Posted(tokenB) created after the snapshot MUST survive the fenced prune",
            dedup.contains(key),
        )

        // 6. C claim(K) must return null — B still owns the slot. No duplicate.
        val tokenC = dedup.claim(key)
        assertNull(
            "C must NOT win claim(K) — B's Posted(tokenB) survived the fenced prune",
            tokenC,
        )
    }

    /**
     * I1-S companion control: proves the fence DOES prune a captured
     * candidate that is absent from active when the current generation still
     * matches the captured one. Without this control the test above could
     * pass for the wrong reason (e.g. if `pruneStaleCandidates` were a
     * no-op). Here the candidate snapshot captures A's `Posted(tokenA)`;
     * S excludes K; the current value is STILL `Posted(tokenA)`; the fence
     * removes it (a legitimate stale prune — no regression vs the old
     * `retainAll` behavior for the non-racy case).
     */
    @Test
    fun `I1-S - fenced prune fires when a captured candidate is absent from active (control)`() {
        val dedup = NotificationDedup()
        val key = "idle:i1-s-ctrl:/wd:ses-root:100"

        // A claims + completes → Posted(tokenA), captured as a candidate.
        val tokenA = dedup.claim(key)!!
        assertTrue("A completes → Posted(tokenA)", dedup.complete(key, tokenA))
        val candidates = dedup.snapshotPosted()
        assertTrue(
            "candidate snapshot captured A's Posted(tokenA)",
            candidates.containsKey(key),
        )

        // S excludes K (K went stale/inactive between snapshot and prune).
        dedup.pruneStaleCandidates(candidates, active = emptySet())

        assertFalse(
            "captured candidate absent from active MUST be pruned (proves the fence isn't a no-op)",
            dedup.contains(key),
        )

        // After the prune K is re-claimable (a fresh idle generation can fire).
        val tokenB = dedup.claim(key)
        assertNotNull("after the fenced prune, K is re-claimable", tokenB)
    }

    // ── T5-review I2 — final-boundary foreground recheck releases the claim ──

    /**
     * T5-review I2: a lifecycle ON_START flips foreground to true AFTER the
     * bridge's top-of-handle eligibility check but BEFORE the final publish
     * boundary. The bridge MUST recheck at the final gate, suppress the
     * notification, AND release the claim so a later background attempt can
     * retry. Models the real race (lifecycle transitions run on Main; the
     * Service's bridge also runs on Main but the top-of-handle read could
     * have happened an instant before ON_START).
     *
     * The [isInForeground] lambda returns false on its FIRST call (the
     * top-of-handle check) and true on subsequent calls (the final gate) —
     * deterministic simulation of the flip.
     */
    @Test
    fun `I2 - foreground flip during publish suppresses notification and releases claim`() = runTest {
        val publisher = RecordingPublisher(returns = true)
        val decisionDedup = NotificationDedup()
        var checkCount = 0
        val bridge = newBridge(
            publisher = publisher.notifier,
            events = source,
            isInForeground = {
                // First call (top of handle): background. Second call (final
                // boundary): foreground → suppress.
                val firstCall = checkCount == 0
                checkCount++
                !firstCall
            },
            decisionDedup = decisionDedup,
            scope = backgroundScope,
        )
        bridge.start()
        runCurrent()

        source.emitQuestion(questionId = "q-flip", sessionId = "ses-1", header = "Pick a color")
        runCurrent()

        assertEquals(
            "foreground flip during publish must suppress the notification",
            0,
            publisher.decisionCalls.size,
        )
        assertFalse(
            "suppressed publish must release the claim so a later background attempt can retry",
            decisionDedup.contains("q:q-flip"),
        )

        // A later background attempt on the same id re-claims and notifies —
        // proves the release actually happened (otherwise the second claim
        // would lose to the stranded first claim).
        val okPublisher = RecordingPublisher(returns = true)
        val okBridge = newBridge(
            publisher = okPublisher.notifier,
            events = source,
            isInForeground = { false },
            decisionDedup = decisionDedup, // SAME dedup set
            scope = backgroundScope,
        )
        okBridge.start()
        runCurrent()
        source.emitQuestion(questionId = "q-flip", sessionId = "ses-1", header = "Pick a color")
        runCurrent()

        assertEquals(
            "released claim lets a later background attempt notify",
            1,
            okPublisher.decisionCalls.size,
        )
        bridge.stop()
        okBridge.stop()
    }

    // ── T5-review I3 — per-event isolation + claim release on throw ──────────

    /**
     * T5-review I3: a publisher that throws (simulating a platform
     * SecurityException / builder failure / PendingIntent throw) must NOT
     * (a) strand the dedup claim permanently, nor (b) terminate the bridge
     * collector for the Service's lifetime. A subsequent valid event on the
     * SAME bridge instance must still notify.
     */
    @Test
    fun `I3 - throwing publisher releases its claim and collector survives for the next event`() = runTest {
        val throwing = ThrowingPublisher()
        val decisionDedup = NotificationDedup()
        val bridge = newBridge(
            publisher = throwing.notifier,
            events = source,
            isInForeground = { false },
            decisionDedup = decisionDedup,
            scope = backgroundScope,
        )
        bridge.start()
        runCurrent()

        // First event — publisher throws mid-notify.
        source.emitQuestion(questionId = "q-throw", sessionId = "ses-1", header = "first")
        runCurrent()

        assertEquals(
            "the throwing publisher WAS invoked once",
            1,
            throwing.decisionCalls.size,
        )
        assertFalse(
            "the failed publish must release its claim (no permanent strand)",
            decisionDedup.contains("q:q-throw"),
        )

        // The collector survived — a second, valid event on the SAME bridge
        // re-claims and notifies successfully.
        val ok = RecordingPublisher(returns = true)
        // Swap the publisher by rebuilding a bridge on the SAME scope with
        // the SAME dedup set; the previous bridge's collector is cancelled
        // by stop() so the swap is clean. (The collector-survival guarantee
        // is exercised below by the fact that the throwing bridge's
        // collector swallowed the first throw — verified by emitting a
        // second event into the SAME throwing bridge before the swap.)
        source.emitQuestion(questionId = "q-throw-2", sessionId = "ses-1", header = "second")
        runCurrent()
        assertEquals(
            "collector survived the first throw — a second event reached the publisher",
            2,
            throwing.decisionCalls.size,
        )

        bridge.stop()

        // Final proof: a fresh successful publisher can re-claim the
        // released key and notify (the I3 release cleared the slot).
        val okBridge = newBridge(
            publisher = ok.notifier,
            events = source,
            isInForeground = { false },
            decisionDedup = decisionDedup,
            scope = backgroundScope,
        )
        okBridge.start()
        runCurrent()
        source.emitQuestion(questionId = "q-throw", sessionId = "ses-1", header = "retry")
        runCurrent()
        assertEquals(
            "released claim (from the failed first publish) lets a later attempt notify",
            1,
            ok.decisionCalls.size,
        )
        okBridge.stop()
    }

    // ── T5-review I4 — canonical ssePayloadJson tolerates wire drift ─────────

    /**
     * T5-review I4: the server may add fields not in our QuestionRequest
     * schema. The canonical `ssePayloadJson` (ignoreUnknownKeys=true) MUST
     * tolerate them without dropping the event. The prior `lenientJson` had
     * `ignoreUnknownKeys=true` but only for the question branch — the
     * session.status branch used the strict default Json. The bridge
     * exercises BOTH parsers via the same canonical config now.
     */
    @Test
    fun `I4 - question_asked with unknown fields still notifies`() = runTest {
        val publisher = RecordingPublisher(returns = true)
        val bridge = newBridge(
            publisher = publisher.notifier,
            events = source,
            isInForeground = { false },
            scope = backgroundScope,
        )
        bridge.start()
        runCurrent()

        source.emitRaw(
            type = "question.asked",
            json = """
                {
                    "id": "q-unknown",
                    "sessionID": "ses-1",
                    "questions": [
                        { "question": "q?", "header": "h", "options": [], "futureField": 7 }
                    ],
                    "futureTopLevel": { "nested": "object" }
                }
            """.trimIndent(),
        )
        runCurrent()

        assertEquals(
            "unknown fields in question.asked payload must not drop the event",
            1,
            publisher.decisionCalls.size,
        )
        assertEquals("q:q-unknown", publisher.decisionCalls[0].key)
        bridge.stop()
    }

    /**
     * T5-review I4: same property for `session.status{idle}` — the prior
     * `Json.decodeFromString<SessionStatus>` (default, strict) would have
     * thrown on unknown keys and dropped the event. The canonical
     * `ssePayloadJson` tolerates the drift.
     */
    @Test
    fun `I4 - session_status idle with unknown fields still notifies`() = runTest {
        val publisher = RecordingPublisher(returns = true)
        val alert = IdleUnreadAlert("ses-root", "Project A", 100L, "idle:fp:/wd:ses-root:100")
        val bridge = newBridge(
            publisher = publisher.notifier,
            events = source,
            isInForeground = { false },
            rootIdleResolver = { id -> if (id == "ses-root") alert else null },
            scope = backgroundScope,
        )
        bridge.start()
        runCurrent()

        source.emitRaw(
            type = "session.status",
            json = """
                {
                    "sessionID": "ses-root",
                    "status": { "type": "idle", "futureStatusField": "x" },
                    "futureTopLevel": 42
                }
            """.trimIndent(),
        )
        runCurrent()

        assertEquals(
            "unknown fields in session.status payload must not drop the event",
            1,
            publisher.idleCalls.size,
        )
        bridge.stop()
    }

    /**
     * T5-review I4 (coercion): a `null` for a nullable field with a default
     * decodes via `explicitNulls=false` + `coerceInputValues=true` to the
     * default rather than throwing. The default-Json path used by the prior
     * `parseSessionStatusEvent` could throw on this; the canonical path
     * tolerates it.
     */
    @Test
    fun `I4 - session_status with explicit null on optional field decodes to default`() = runTest {
        val publisher = RecordingPublisher(returns = true)
        val alert = IdleUnreadAlert("ses-root", "title", 100L, "idle:fp:/wd:ses-root:100")
        val bridge = newBridge(
            publisher = publisher.notifier,
            events = source,
            isInForeground = { false },
            rootIdleResolver = { id -> if (id == "ses-root") alert else null },
            scope = backgroundScope,
        )
        bridge.start()
        runCurrent()

        // `attempt` is `Int? = null` in SessionStatus; the server explicitly
        // sending null must decode cleanly (the canonical Json's
        // explicitNulls=false + coerceInputValues=true path).
        source.emitRaw(
            type = "session.status",
            json = """
                {
                    "sessionID": "ses-root",
                    "status": { "type": "idle", "attempt": null, "message": null }
                }
            """.trimIndent(),
        )
        runCurrent()

        assertEquals(
            "explicit nulls on nullable fields must not drop the event",
            1,
            publisher.idleCalls.size,
        )
        bridge.stop()
    }

    // ── Fixtures ───────────────────────────────────────────────────────

    /**
     * Per-test event source. JUnit creates a fresh test instance per test
     * method, so each test gets a clean flow without cross-test bleed.
     */
    private val source: EventSource = EventSource()

    /** Builds a bridge with sensible defaults; tests override only what they exercise. */
    private fun newBridge(
        publisher: SessionNotifier,
        events: Flow<IdentifiedSseEvent>,
        isInForeground: () -> Boolean,
        decisionDedup: NotificationDedup = NotificationDedup(),
        idleDedup: NotificationDedup = NotificationDedup(),
        rootIdleResolver: (String) -> IdleUnreadAlert? = { null },
        scope: CoroutineScope,
    ): SseNotificationBridge = SseNotificationBridge(
        events = events,
        notifier = publisher,
        decisionDedup = decisionDedup,
        idleDedup = idleDedup,
        isInForeground = isInForeground,
        rootIdleResolver = rootIdleResolver,
        scope = scope,
    )

    /**
     * A mockk-based recording publisher. Captures each call's args so tests
     * can assert exact arg shape (sessionId / title / body / key) without
     * reaching into Android's Notification facade. [notifier] is the mock
     * to pass into [SseNotificationBridge]; the recorded call lists are on
     * this holder.
     */
    private class RecordingPublisher(returns: Boolean) {
        val decisionCalls = mutableListOf<DecisionCall>()
        val idleCalls = mutableListOf<IdleCall>()
        val notifier: SessionNotifier = mockk(relaxed = true) {
            io.mockk.every { notifyDecision(any(), any(), any(), any()) } answers {
                decisionCalls += DecisionCall(
                    sessionId = arg(0), title = arg(1), body = arg(2), key = arg(3),
                )
                returns
            }
            io.mockk.every { notifyIdle(any(), any(), any()) } answers {
                idleCalls += IdleCall(rootId = arg(0), title = arg(1), key = arg(2))
                returns
            }
        }
        data class DecisionCall(val sessionId: String, val title: String, val body: String, val key: String)
        data class IdleCall(val rootId: String, val title: String, val key: String)
    }

    /**
     * T5-review I3 fixture: a publisher whose `notifyDecision` THROWS after
     * recording the call (simulating a platform SecurityException / builder
     * failure / PendingIntent throw at the Android boundary). The bridge's
     * per-event try/catch in the collector must swallow this and keep the
     * collector alive; the publish-path try/finally must release the claim.
     */
    private class ThrowingPublisher {
        val decisionCalls = mutableListOf<RecordingPublisher.DecisionCall>()
        val notifier: SessionNotifier = mockk(relaxed = true) {
            io.mockk.every { notifyDecision(any(), any(), any(), any()) } answers {
                decisionCalls += RecordingPublisher.DecisionCall(
                    sessionId = arg(0), title = arg(1), body = arg(2), key = arg(3),
                )
                throw RuntimeException("simulated platform notify failure")
            }
            // Idle path stays relaxed-default (the I3 test exercises the
            // decision branch).
        }
    }

    /** Per-test bridge source — a small wrapper over [MutableSharedFlow]. */
    private class EventSource(
        private val flow: MutableSharedFlow<IdentifiedSseEvent> = MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = 32,
        ),
    ) : Flow<IdentifiedSseEvent> by flow {

        suspend fun emitQuestion(questionId: String, sessionId: String, header: String) {
            // Wire format mirrors QuestionTest's makeEvent() so
            // parseQuestionAskedEvent decodes it cleanly. id / sessionID /
            // questions[] are the required QuestionRequest fields; `options`
            // is required (no default) so it MUST be present (can be empty).
            val properties: JsonObject = Json.parseToJsonElement(
                """
                {
                    "id": "$questionId",
                    "sessionID": "$sessionId",
                    "questions": [
                        { "question": "q?", "header": "$header", "options": [] }
                    ]
                }
                """.trimIndent(),
            ).jsonObject
            flow.emit(identified(SSEEvent(payload = SSEPayload(type = QUESTION_TYPE, properties = properties))))
        }

        suspend fun emitIdle(sessionId: String) = emitStatus(sessionId, type = "idle")

        suspend fun emitStatus(sessionId: String, type: String) {
            val properties: JsonObject = Json.parseToJsonElement(
                """
                {
                    "sessionID": "$sessionId",
                    "status": { "type": "$type" }
                }
                """.trimIndent(),
            ).jsonObject
            flow.emit(identified(SSEEvent(payload = SSEPayload(type = STATUS_TYPE, properties = properties))))
        }

        /**
         * T5-review I4 helper: emits a control event with an arbitrary raw
         * JSON properties blob. Used by the canonical-Json tolerance tests
         * to inject unknown fields / explicit nulls / nested future objects
         * that the minimal [emitQuestion] / [emitStatus] helpers don't
         * produce. The blob is parsed via the same [Json.parseToJsonElement]
         * the production wire format would arrive as, then handed to the
         * bridge as the payload's `properties`.
         */
        suspend fun emitRaw(type: String, json: String) {
            val properties: JsonObject = Json.parseToJsonElement(json).jsonObject
            flow.emit(identified(SSEEvent(payload = SSEPayload(type = type, properties = properties))))
        }

        private fun identified(event: SSEEvent): IdentifiedSseEvent = IdentifiedSseEvent(
            identity = ConnectionIdentity(
                epoch = EPOCH,
                serverGroupFp = "group-fp",
                normalizedWorkdir = "/work/dir",
                endpointFp = "endpoint-fp",
            ),
            event = event,
        )
    }

    companion object {
        private const val EPOCH = 7L
        private const val QUESTION_TYPE = "question.asked"
        private const val STATUS_TYPE = "session.status"
    }
}
