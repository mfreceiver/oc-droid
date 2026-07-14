package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.NotificationDedup
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
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

    private fun root(id: String) = Session(id = id, directory = "/repo", title = "Root $id")

    private fun poller(
        isBackground: () -> Boolean = { true },
        lifecycleGeneration: () -> Long = { 0L },
    ) = BackgroundUnreadPoller(
        repository = repository,
        settingsManager = settings,
        store = store,
        clock = { now },
        isBackground = isBackground,
        lifecycleGeneration = lifecycleGeneration,
    )

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
        stubSnapshot(listOf(root("A")), mapOf("A" to SessionStatus("idle")))
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
        stubSnapshot(listOf(root("A")), emptyMap())
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
    fun `notification key includes server workdir root and idle cycle`() {
        assertEquals(
            "idle:server-1:/repo:A:1000",
            idleNotificationKey("server-1", "/repo", "A", 1_000L),
        )
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
}
