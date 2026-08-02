package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.state.AuthorityOp
import cn.vectory.ocdroid.data.state.EntryOrigin
import cn.vectory.ocdroid.di.NotificationDedup
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundUnreadPollerTest {
    private val repository = mockk<OpenCodeRepository>()
    private val settings = mockk<SettingsManager>()
    private val store = SharedStateStore()
    private var now = 1_000L

    // §unread-semantics (F3): `updated` mirrors Session.time.updated — the
    // "new content" signal. Defaults to null (no content); mark-asserting
    // tests opt into a timestamp to exercise the new-message branch.
    private fun root(id: String, updated: Long? = null) =
        Session(id = id, directory = "/repo", title = "Root $id", time = Session.TimeInfo(updated = updated))

    private fun poller(
        isBackground: () -> Boolean = { true },
        lifecycleGeneration: () -> Long = { 0L },
    ): BackgroundUnreadPoller {
        every { repository.usesSlimStatusFanOut } returns false
        coEvery { repository.getActiveSessionIds() } returns Result.success(emptySet())
        return BackgroundUnreadPoller(
        repository = repository,
        settingsManager = settings,
        store = store,
        clock = { now },
        isBackground = isBackground,
        lifecycleGeneration = lifecycleGeneration,
        )
    }

    /**
     * T5-round-5 I1-A: extracts the alert list from an [UnreadPollResult],
     * asserting it is [UnreadPollResult.Authoritative]. Tighter than the old
     * `poll().isEmpty()` which could not distinguish authoritative-empty from
     * abort. Use [assertAborted] for the abort expectation.
     */
    private fun authoritativeAlerts(result: UnreadPollResult): List<IdleUnreadAlert> {
        assertTrue(
            "expected Authoritative (committed snapshot), got $result",
            result is UnreadPollResult.Authoritative,
        )
        return (result as UnreadPollResult.Authoritative).alerts
    }

    private fun assertAborted(result: UnreadPollResult) {
        assertTrue(
            "expected Aborted (no authoritative snapshot), got $result",
            result is UnreadPollResult.Aborted,
        )
    }

    private fun stubSnapshot(
        sessions: List<Session>,
        statuses: Map<String, SessionStatus>,
        children: Map<String, List<Session>> = emptyMap(),
    ) {
        every { settings.currentWorkdir } returns "/repo"
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)
        coEvery { repository.getSessionStatus() } returns Result.success(statuses)
        coEvery { repository.getChildren(any()) } answers {
            Result.success(children[arg<String>(0)].orEmpty())
        }
    }

    @Test
    fun `two background polls mark once after soak and continuous idle does not duplicate`() = runTest {
        // F3: opt-in a new message (arrived before the first poll's rising edge
        // at now=1000) so the soak→mark fires; root never viewed ⇒ baseline 0.
        stubSnapshot(listOf(root("A", updated = 500L)), mapOf("A" to SessionStatus("idle")))
        val poller = poller()

        // T5-round-5 I1-A: each committed poll returns Authoritative (was a
        // bare List), so the assertion goes through authoritativeAlerts.
        assertTrue(authoritativeAlerts(poller.poll()).isEmpty())
        now = 31_000L
        val completed = poller.poll()
        now = 61_000L
        val repeated = poller.poll()

        assertEquals(listOf("A"), authoritativeAlerts(completed).map { it.rootId })
        assertEquals("pending cycle remains retryable; monitor dedupes posted keys", completed, repeated)
        assertTrue("authoritative unread set is updated", "A" in store.unreadFlow.value.unreadSessions)
    }

    @Test
    fun `successful status snapshot absence is normalized to authoritative idle`() = runTest {
        // F3: opt-in a new message so the soak→mark fires (root never viewed).
        stubSnapshot(listOf(root("A", updated = 500L)), emptyMap())
        val poller = poller()

        assertTrue(authoritativeAlerts(poller.poll()).isEmpty())
        now = 31_000L

        assertEquals(listOf("A"), authoritativeAlerts(poller().poll()).map { it.rootId })
    }

    @Test
    fun `successful background poll bumps completeness epoch to drop in-flight foreground hydration`() = runTest {
        // §gpter-residual: the background poll writes an authoritative
        // completeness snapshot. Bumping the epoch makes any foreground
        // hydration captured before this poll fail-closed at its commit
        // instead of re-certifying roots against a stale session map.
        stubSnapshot(listOf(root("A")), mapOf("A" to SessionStatus("idle")))
        assertEquals("default epoch", 0L, store.sessionListFlow.value.completenessEpoch)

        assertTrue(authoritativeAlerts(poller().poll()).isEmpty())

        assertEquals(
            "background poll bumped the completeness epoch",
            1L,
            store.sessionListFlow.value.completenessEpoch,
        )
    }


    @Test
    fun `current root and busy child produce no background alert`() = runTest {
        val a = root("A")
        val child = Session(id = "C", directory = "/repo", parentId = "A")
        stubSnapshot(
            sessions = listOf(a),
            statuses = mapOf("A" to SessionStatus("idle"), "C" to SessionStatus("busy")),
            children = mapOf("A" to listOf(child)),
        )
        store.mutateChat { it.copy(currentSessionId = "A") }
        store.mutateUnread { it.copy(idleSince = mapOf("A" to 1_000L)) }
        now = 31_000L

        val alerts = authoritativeAlerts(poller().poll())

        assertTrue(alerts.isEmpty())
        assertFalse("A" in store.unreadFlow.value.unreadSessions)
    }

    @Test
    fun `active drain suppresses background unread despite idle status snapshot`() = runTest {
        stubSnapshot(
            sessions = listOf(root("A", updated = 2_000L)),
            statuses = mapOf("A" to SessionStatus("idle")),
        )
        store.mutateUnread { it.copy(idleSince = mapOf("A" to 1_000L)) }
        now = 31_000L
        val poller = poller()
        coEvery { repository.getActiveSessionIds() } returns Result.success(setOf("A"))

        val alerts = authoritativeAlerts(poller.poll())

        assertTrue(alerts.isEmpty())
        assertEquals(setOf("A"), store.sessionListFlow.value.activeSessionIds)
        assertFalse("running drain must not become unread", "A" in store.unreadFlow.value.unreadSessions)
    }

    @Test
    fun `viewed during cycle suppresses background alert`() = runTest {
        stubSnapshot(listOf(root("A")), mapOf("A" to SessionStatus("idle")))
        store.mutateUnread {
            it.copy(idleSince = mapOf("A" to 1_000L), lastViewedTime = mapOf("A" to 2_000L))
        }
        now = 31_000L

        assertTrue(authoritativeAlerts(poller().poll()).isEmpty())
        assertFalse("A" in store.unreadFlow.value.unreadSessions)
    }

    @Test
    fun `notification key is stable across idle-cycle re-stamps`() {
        // Bug-1: the key MUST NOT embed the volatile `idleSince` timestamp —
        // the evaluator re-stamps it on every fresh idle transition, and it
        // is not preserved across process death. A stable (serverId, workdir,
        // rootId) triple survives restart so a persisted dedup entry matches.
        // The OLD format appended ":$idleSince" (e.g. "...:A:1000"); the new
        // format drops that suffix entirely so a persisted set seeded by a
        // prior process always matches.
        assertEquals(
            "idle:server-1:/repo:A",
            idleNotificationKey("server-1", "/repo", "A"),
        )
        // null workdir is normalized to empty (matches the persistable key).
        assertEquals(
            "idle:server-1::A",
            idleNotificationKey("server-1", null, "A"),
        )
    }

    @Test
    fun `slim mode routes status to per-workdir slim fan-out and skips legacy endpoints`() = runTest {
        // §T-R1 (slimapi R1): BackgroundUnreadPoller MUST route around the
        // legacy getSessionStatus / getActiveSessionIds when
        // usesSlimStatusFanOut is true. Before the fix the legacy paths are
        // called unconditionally; this test verifies the slim routing patch
        // redirects to getSlimapiSessionsStatus and preserves the store's
        // existing activeSessionIds (null → fail-closed fallback).
        every { repository.usesSlimStatusFanOut } returns true
        every { settings.currentWorkdir } returns "/repo"
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(root("A", updated = 500L)))
        coEvery { repository.getChildren("A") } returns Result.success(emptyList())
        // Stub ONLY the slim endpoint, NOT the legacy getSessionStatus.
        // Before the fix, the unstubbed getSessionStatus throws MockKException → RED.
        coEvery { repository.getSlimapiSessionsStatus("/repo") } returns Result.success(
            mapOf("A" to SessionStatus("idle"))
        )

        val poller = BackgroundUnreadPoller(
            repository = repository,
            settingsManager = settings,
            store = store,
            clock = { now },
            isBackground = { true },
            lifecycleGeneration = { 0L },
        )
        assertTrue(authoritativeAlerts(poller.poll()).isEmpty())

        // GREEN: legacy endpoints NOT called; slim endpoint IS called.
        coVerify(exactly = 0) { repository.getSessionStatus() }
        coVerify(exactly = 1) { repository.getSlimapiSessionsStatus("/repo") }
    }

    @Test
    fun `SSE invalidation bumping epoch mid-poll aborts stale commit and alert`() = runTest {
        // §gpter-residual: a session.created/updated SSE event bumps the
        // completeness epoch without touching host/generation/workdir. The poll
        // must detect the moved epoch at its next identity check (and again in
        // the CAS) and abort, so its older snapshot never regresses the store.
        // T5-round-5 I1-A: abort returns [UnreadPollResult.Aborted] (was
        // `emptyList()`), so ALM does NOT treat this as an authoritative empty.
        val a = root("A")
        every { settings.currentWorkdir } returns "/repo"
        store.mutateHost { it.copy(currentHostProfileId = "host-1") }
        store.mutateSessionList { it.copy(completenessEpoch = 5L) }
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(a))
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getChildren("A") } coAnswers {
            // SSE fires while the tree request is in flight.
            store.mutateSessionList { it.copy(completenessEpoch = it.completenessEpoch + 1L) }
            Result.success(emptyList())
        }

        assertAborted(poller().poll())
        assertTrue(
            "stale snapshot must not regress the store",
            store.sessionListFlow.value.sessions.isEmpty(),
        )
    }

    @Test
    fun `host switch during final tree request prevents stale commit and alert`() = runTest {
        val a = root("A")
        every { settings.currentWorkdir } returns "/repo"
        store.mutateHost { it.copy(currentHostProfileId = "host-1") }
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(a))
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getChildren("A") } coAnswers {
            store.mutateHost { it.copy(currentHostProfileId = "host-2") }
            Result.success(emptyList())
        }

        // T5-round-5 I1-A: host-switch abort is [UnreadPollResult.Aborted].
        assertAborted(poller().poll())
        assertTrue(store.sessionListFlow.value.sessions.isEmpty())
        assertTrue(store.unreadFlow.value.unreadSessions.isEmpty())
    }

    @Test
    fun `foreground transition during final request prevents stale commit and alert`() = runTest {
        val a = root("A")
        var background = true
        var generation = 1L
        every { settings.currentWorkdir } returns "/repo"
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(a))
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getChildren("A") } coAnswers {
            background = false
            generation += 1
            Result.success(emptyList())
        }

        // T5-round-5 I1-A: foreground-transition abort is [UnreadPollResult.Aborted].
        assertAborted(poller({ background }, { generation }).poll())
        assertTrue(store.sessionListFlow.value.sessions.isEmpty())
    }

    // ── T5-round-5 I1-A — aborted poll MUST NOT prune the dedup map ──────────

    /**
     * T5-round-5 I1-A: the residual defect. Under the OLD contract,
     * [BackgroundUnreadPoller.poll] returned `emptyList()` for every non-
     * exception abort (identity invalidation, repository failure, rejected
     * aggregate commit). ALM's `runSuspendCatching ... onSuccess` therefore
     * treated each abort as an authoritative empty snapshot →
     * `active = emptySet()` → the fenced `pruneStaleCandidates` removed the
     * live `Posted(tokenA)` candidate (exact-generation match) → the next
     * genuine poll re-claimed the same logical idle key → duplicate
     * sound/vibration.
     *
     * Fix: `poll()` now returns [UnreadPollResult.Aborted] on abort paths and
     * [UnreadPollResult.Authoritative] (incl. genuinely empty) on a committed
     * snapshot. ALM's caller prunes + publishes ONLY on Authoritative.
     *
     * This test drives an ABORTING poll (mid-poll epoch invalidation — the
     * exact scenario the reviewer proved) end-to-end through the production
     * ALM caller logic (candidate fence → poll → conditional prune) and
     * asserts the live `Posted(tokenA)` survives, so a later `claim(K)`
     * cannot win → no duplicate.
     */
    @Test
    fun `I1-A - aborted poll preserves dedup state and prevents duplicate claim`() = runTest {
        val dedup = NotificationDedup()
        val key = "idle:host-1:/repo:A:1000"

        // 1. Seed Posted(tokenA) for K — A claimed, notified, completed in a
        //    prior cycle. This is the live dedup entry the bug would prune.
        val tokenA = dedup.claim(key)
        assertNotNull("seed: A wins the initial claim", tokenA)
        assertTrue("seed: A completes → Posted(tokenA)", dedup.complete(key, tokenA!!))

        // 2. Capture candidates BEFORE the poll (the I1-S pre-poll fence).
        val candidates = dedup.snapshotPosted()
        assertTrue("K captured as a candidate", candidates.containsKey(key))

        // 3. Drive an ABORTING poll: SSE bumps completenessEpoch mid-poll →
        //    the CAS rejects the commit → poll returns Aborted (was emptyList).
        every { settings.currentWorkdir } returns "/repo"
        store.mutateHost { it.copy(currentHostProfileId = "host-1") }
        store.mutateSessionList { it.copy(completenessEpoch = 5L) }
        val a = root("A")
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(a))
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getChildren("A") } coAnswers {
            store.mutateSessionList { it.copy(completenessEpoch = it.completenessEpoch + 1L) }
            Result.success(emptyList())
        }
        val result = poller().poll()
        assertAborted(result)

        // 4. Replicate the ALM caller contract (AppLifecycleMonitor.pollPendingItems):
        //    prune + publish ONLY on Authoritative; Aborted skips both. The
        //    OLD code unconditionally built `active = alerts.keys` and pruned —
        //    which is the defect. The sealed result branches them.
        if (result is UnreadPollResult.Authoritative) {
            val active = result.alerts.mapTo(mutableSetOf()) { it.key }
            dedup.pruneStaleCandidates(candidates, active)
        }

        // 5. K SURVIVES — Posted(tokenA) is intact because the prune was skipped.
        assertTrue(
            "Aborted poll MUST NOT prune live Posted candidates " +
                "(pruning would let the next authoritative poll re-claim → duplicate)",
            dedup.contains(key),
        )

        // 6. A subsequent claim(K) loses — A's Posted(tokenA) still owns the
        //    slot. No duplicate notification is possible.
        assertNull(
            "claim(K) must lose after an aborted poll — A's Posted(tokenA) survived",
            dedup.claim(key),
        )
    }

    /**
     * I1-A companion control: proves the contract change does NOT disable
     * pruning for genuine authoritative-empty snapshots. K is seeded as
     * `Posted(tokenA)`; the poller produces a real `Authoritative(emptyList())`
     * (a committed snapshot that happens to contain no idle alerts — A's
     * `idleSince` is unset); the ALM caller's `active = emptySet()` excludes
     * K → `pruneStaleCandidates` removes K (exact-generation match: captured
     * `Posted(tokenA) == current Posted(tokenA)`). Without this control the
     * abort test could pass for the wrong reason (e.g. if the prune were a
     * no-op for both branches).
     */
    @Test
    fun `I1-A - genuine authoritative empty DOES prune live Posted (control)`() = runTest {
        val dedup = NotificationDedup()
        val key = "idle:host-1:/repo:A:1000"

        // 1. Seed Posted(tokenA) for K (same seed as the abort test).
        val tokenA = dedup.claim(key)
        assertNotNull("seed: A wins the initial claim", tokenA)
        assertTrue("seed: A completes → Posted(tokenA)", dedup.complete(key, tokenA!!))

        // 2. Capture candidates.
        val candidates = dedup.snapshotPosted()
        assertTrue("K captured as a candidate", candidates.containsKey(key))

        // 3. Drive a GENUINE authoritative-empty poll: session A is present
        //    with no idle entry in the store, so the committed snapshot's
        //    `idleSince` map excludes A → alerts is empty → Authoritative(empty).
        every { settings.currentWorkdir } returns "/repo"
        store.mutateHost { it.copy(currentHostProfileId = "host-1") }
        val a = root("A")
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(a))
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getChildren("A") } returns Result.success(emptyList())
        val result = poller().poll()
        val alerts = authoritativeAlerts(result)
        assertTrue("genuine authoritative empty snapshot (no alerts)", alerts.isEmpty())

        // 4. ALM caller contract: Authoritative → prune + publish.
        val active = alerts.mapTo(mutableSetOf()) { it.key }
        dedup.pruneStaleCandidates(candidates, active)

        // 5. K IS pruned — exact-generation match (captured Posted(tokenA) ==
        //    current Posted(tokenA)). This is the legitimate prune the OLD
        //    bare-retainAll path also performed; the contract change must not
        //    regress it.
        assertFalse(
            "Authoritative empty MUST prune a live Posted candidate absent from active",
            dedup.contains(key),
        )

        // 6. K is re-claimable after the prune (next idle cycle can re-fire).
        assertNotNull("K re-claimable after the prune", dedup.claim(key))
    }

    // ── §review-blocker-#8 (P0-C end-to-end) — requestStartMs timing ──────────

    /**
     * §review-blocker-#8 (P0-C end-to-end): [BackgroundUnreadPoller] MUST
     * capture [RequestToken.requestStartMs] at request START (the same moment
     * as [localBefore]), NOT at response END. The authority reducer's timestamp
     * arm `prior.updatedAtMs > op.requestToken.requestStartMs`
     * (AuthorityReducer.kt:458) only protects a concurrent in-flight update
     * when requestStartMs PRECEDES that update.
     *
     * Torn interleaving (two independent 30s background pollers —
     * BackgroundUnreadPoller + ProcessStatusPoller):
     *   t0: BackgroundUnreadPoller captures localBefore[A]=busy + requestStartMs
     *   t1: REST returns a STALE idle for A with a NULL round
     *       (serverRoundOrNull() == null → the sole path the timestamp arm
     *       guards; non-null-R is resolved by the AuthorityReducer :534 lex-fence)
     *   t2: a concurrent ProcessStatusPoller commits A=busy (value UNCHANGED,
     *       but updatedAtMs=t2) through the same authority reducer
     *   t3: BackgroundUnreadPoller's response-end clock() lands
     *   t0 < t1 < t2 < t3
     *
     * The status-diff arm alone CANNOT catch this: localBefore[A] ==
     * currentProjection[A] == busy (same value re-committed at t2) → no diff →
     * the timestamp arm is the SOLE fence. Pre-fix it compared requestStartMs
     * = t3 (response end), so `t2 > t3` was FALSE → the arm missed → the null-R
     * REST idle clobbered the t2 busy and was stamped with the LATER t3,
     * regressing the timestamp a subsequent earlier SSE/REST would be fenced
     * against. Post-fix requestStartMs = t0 → `t2 > t0` is TRUE → inFlightWin
     * preserves the busy projection + the t2 updatedAtMs; the stale REST idle
     * is rejected.
     *
     * The 4 other REST writers (SessionListActions.kt:248 /
     * SessionTreeHydrator.kt:114 / StatusPollOrchestrator.kt:183,349) all
     * capture requestStartMs BEFORE the fetch; BackgroundUnreadPoller was the
     * sole exception (#8). This test fails pre-fix (status=idle,
     * updatedAtMs=t3) and passes post-fix (status=busy, updatedAtMs=t2).
     */
    @Test
    fun `concurrent same-value status update during background poll is not clobbered by stale REST`() = runTest {
        val t0 = 1_000L
        val t2 = 2_000L
        val t3 = 3_000L

        every { settings.currentWorkdir } returns "/repo"

        // Seed the request-start authority state: A is busy (the value the
        // concurrent poller re-commits UNCHANGED at t2). Seeded via the SAME
        // pure reducer path production uses (ApplyEvent) so bySid + the
        // sessionStatuses projection are consistent — localBefore will capture
        // {A:busy}, matching currentProjection at the CAS. The status-diff arm
        // therefore reads "no diff", isolating the timestamp arm as the SOLE
        // discriminator — exactly the #8 scenario.
        store.dispatch(AppAction.AuthorityEvent(
            AuthorityOp.ApplyEvent(
                sid = "A",
                status = SessionStatus(type = "busy"),
                origin = EntryOrigin.SSE_LEGACY,
                scopeKey = store.authorityScope(),
                connectionTimeMs = 500L,
            )
        ))
        assertEquals(
            "seed: projection reflects busy (the localBefore source)",
            SessionStatus(type = "busy"),
            store.sessionListFlow.value.sessionStatuses["A"],
        )

        // REST returns a STALE idle for A with a NULL round — the sole path the
        // timestamp arm guards (a non-null round is resolved by the :534
        // lex-fence, so the arm is correctly confined to null-R here).
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(root("A")))
        coEvery { repository.getChildren("A") } returns Result.success(emptyList())
        coEvery { repository.getSessionStatus() } coAnswers {
            // t2: a CONCURRENT ProcessStatusPoller commit lands during this REST
            // round-trip — same value (busy), but a fresher updatedAtMs. Two
            // independent 30s background pollers interleave exactly this way.
            // Advance bySid[A].updatedAtMs directly (the projection depends
            // only on status, which is unchanged, so no recompute is needed).
            store.mutateState { s ->
                val entry = s.authority.bySid.getValue("A")
                s.copy(authority = s.authority.copy(
                    bySid = s.authority.bySid + ("A" to entry.copy(updatedAtMs = t2))
                ))
            }
            // t3: the REST response — and the poller's response-end clock —
            // land AFTER the t2 concurrent commit.
            now = t3
            Result.success(mapOf("A" to SessionStatus(type = "idle")))
        }

        // requestStartMs is captured at poll START, while `now` is still t0.
        // (Pre-fix the single clock() read happened only at response end, by
        // which point the coAnswers above had already advanced `now` to t3.)
        now = t0
        val result = poller().poll()

        // An authoritative snapshot WAS committed (the CAS did not abort on an
        // identity / epoch / host move — those guards are not exercised here).
        authoritativeAlerts(result)

        // The stale REST idle MUST NOT clobber the concurrent t2 busy: with
        // requestStartMs = t0 the timestamp arm `t2 > t0` fired → inFlightWin
        // preserved the busy projection. Pre-fix this read `idle`
        // (requestStartMs = t3 → `t2 > t3` false → arm missed → REST idle won).
        assertEquals(
            "concurrent t2 busy survives the stale REST idle (timestamp arm fired)",
            SessionStatus(type = "busy"),
            store.sessionListFlow.value.sessionStatuses["A"],
        )
        val entry = store.stateFlow.value.authority.bySid["A"]
        assertNotNull("authority entry present after the commit", entry)
        assertEquals(
            "updatedAtMs preserved at the concurrent t2 commit, not regressed to response-end t3",
            t2,
            entry?.updatedAtMs,
        )
    }
}
