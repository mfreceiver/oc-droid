package cn.vectory.ocdroid.data.cache

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.cache.contract.CachedSessionWindow
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-20 Phase 3 (plan §3 + v4 freegpt 建议2): unit tests for the daily-sweep
 * pipeline owned by [CacheMaintenanceCoordinator].
 *
 * Coverage:
 *  - **24h dedup** — same-epoch-day sweep short-circuits.
 *  - **Complete alive set → sweep** — orphans are evicted, archived excluded.
 *  - **Incomplete alive set → mark-only** — no deletion, only lastVerifiedAt.
 *  - **Per-workdir enumeration** — fan-out across cached workdirs + global
 *    `getSessions` supplement.
 *  - **Truncation detection** — response == enumeration limit → incomplete.
 *  - **applyEvictionPolicy invoked first** — LRU/age always run before alive
 *    sweep.
 *
 * Robolectric in-memory Room for the [CacheRepositoryImpl] (real transaction
 * semantics for the sweep methods) + mockk [OpenCodeRepository] for the REST
 * responses + mockk [SettingsManager] for the epoch-day dedup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CacheMaintenanceCoordinatorTest {

    private lateinit var db: CacheDatabase
    private lateinit var cache: CacheRepository
    private lateinit var repo: OpenCodeRepository
    private lateinit var settings: SettingsManager
    private lateinit var coordinator: CacheMaintenanceCoordinator

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cache = CacheRepositoryImpl(db.cacheDao(), db.gapMarkerDao(), db)
        repo = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        // Default: never swept → first sweep runs the full pipeline. Use
        // `every` (not coEvery) — getLastSweepEpochDay is a regular fun on
        // SettingsManager (no suspend).
        every { settings.getLastSweepEpochDay(any()) } returns null
        // Default: every alive-source returns empty (no extra sessions beyond
        // what the test seeds into the cache).
        coEvery { repo.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repo.getSessionsForDirectory(any(), any()) } returns Result.success(emptyList())
        coordinator = CacheMaintenanceCoordinator(cache, repo, settings) { "g1" }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ─────────── 24h dedup ──────────────────────────────────────────────────

    @Test
    fun `dailySweepIfNeeded skips when already swept today`() = runTest {
        val today = System.currentTimeMillis() / (24L * 60L * 60L * 1000L)
        every { settings.getLastSweepEpochDay("g1") } returns today
        // Seed a cached session that WOULD be evicted by the orphan sweep if
        // the pipeline ran (alive set is empty by default). If the dedup
        // short-circuits, the session survives.
        seedCachedSession("g1", "would-be-orphan")

        val report = coordinator.dailySweepIfNeeded("g1")

        // Skipped: zero counts, Incomplete (we did not enumerate).
        assertEquals(AliveCompleteness.Incomplete, report.completeness)
        assertEquals(0, report.verifiedAliveCount)
        assertTrue(report.evictedSessionIds.isEmpty())
        // Critical: the dedup short-circuited — the orphan sweep did NOT run,
        // so the would-be-orphan session survives.
        assertNotNull(
            "dedup short-circuit preserved the would-be-orphan (sweep did not run)",
            db.cacheDao().session("g1", "would-be-orphan"),
        )
        // Last-sweep-day NOT re-stamped (already at today).
        verify(exactly = 0) { settings.setLastSweepEpochDay(any(), any()) }
    }

    @Test
    fun `dailySweepIfNeeded force=true bypasses 24h dedup and runs the full pipeline`() = runTest {
        // §grouping-rewrite Round-2 C2: the manual sweep buttons pass force=true
        // to bypass the same-day dedup. Verify that even when the epoch is
        // already stamped today (which would otherwise short-circuit), the
        // forced call (a) runs the orphan sweep and (b) re-stamps the epoch.
        val today = System.currentTimeMillis() / (24L * 60L * 60L * 1000L)
        every { settings.getLastSweepEpochDay("g1") } returns today
        // Seed an orphan: cached but not in any alive source (defaults return
        // empty) → evicted iff the pipeline actually runs.
        seedCachedSession("g1", "would-be-orphan")

        val report = coordinator.dailySweepIfNeeded("g1", force = true)

        // force bypassed the dedup → full pipeline ran → orphan evicted.
        assertNull(
            "force=true bypassed the dedup and the orphan sweep ran",
            db.cacheDao().session("g1", "would-be-orphan"),
        )
        // Epoch re-stamped (so the next non-forced call within the same day
        // still dedups correctly).
        verify(exactly = 1) { settings.setLastSweepEpochDay("g1", today) }
        // Sanity: AliveCompleteness reflects the actual run (not the
        // dedup-skipped Incomplete placeholder).
        assertEquals(AliveCompleteness.Complete, report.completeness)
    }

    @Test
    fun `dailySweepIfNeeded proceeds when last sweep was a previous day`() = runTest {
        val today = System.currentTimeMillis() / (24L * 60L * 60L * 1000L)
        every { settings.getLastSweepEpochDay("g1") } returns today - 1

        coordinator.dailySweepIfNeeded("g1")

        // New day → full pipeline ran + stamped.
        verify(exactly = 1) { settings.setLastSweepEpochDay("g1", today) }
    }

    @Test
    fun `dailySweepIfNeeded proceeds when no prior sweep is recorded`() = runTest {
        // First connect (settings returns null) — full pipeline. The seed
        // session has no alive source → orphan → evicted by the sweep.
        seedCachedSession("g1", "orphan")
        coordinator.dailySweepIfNeeded("g1")
        assertNull(
            "first sweep ran the full pipeline (orphan evicted)",
            db.cacheDao().session("g1", "orphan"),
        )
        verify(exactly = 1) { settings.setLastSweepEpochDay(eq("g1"), any()) }
    }

    @Test
    fun `dailySweepIfNeeded no-ops on a blank serverGroupFp`() = runTest {
        // Defensive: a blank fp would corrupt the SettingsManager key namespace.
        // Seed a cached session under "" so we can verify the pipeline did not
        // run (the session survives because the sweep short-circuits).
        seedCachedSession("", "would-be-orphan")

        val report = coordinator.dailySweepIfNeeded("")

        assertEquals(AliveCompleteness.Incomplete, report.completeness)
        assertEquals(0, report.verifiedAliveCount)
        assertNotNull(
            "blank-fp guard short-circuited (would-be-orphan survives)",
            db.cacheDao().session("", "would-be-orphan"),
        )
        verify(exactly = 0) { settings.setLastSweepEpochDay(any(), any()) }
    }

    // ─────────── Complete alive set → sweep ─────────────────────────────────

    @Test
    fun `complete alive set - orphans are evicted`() = runTest {
        // 2 cached sessions; only 1 is alive on the server.
        seedCachedSession("g1", "alive")
        seedCachedSession("g1", "orphan")
        coEvery { repo.getSessions(any()) } returns Result.success(
            listOf(Session(id = "alive", directory = "/p"))
        )

        val report = coordinator.dailySweepIfNeeded("g1")

        assertEquals(AliveCompleteness.Complete, report.completeness)
        assertEquals(1, report.verifiedAliveCount)
        assertEquals(listOf("orphan"), report.evictedSessionIds)
        assertNotNull("alive session kept", db.cacheDao().session("g1", "alive"))
        assertNull("orphan evicted", db.cacheDao().session("g1", "orphan"))
    }

    @Test
    fun `complete alive set - archived sessions excluded from alive set`() = runTest {
        // G2: archived sessions are NOT alive. The cache should treat them as
        // orphans (even though they exist server-side with archived=non-zero).
        seedCachedSession("g1", "active")
        seedCachedSession("g1", "cached-but-archived-server-side")
        coEvery { repo.getSessions(any()) } returns Result.success(
            listOf(
                Session(id = "active", directory = "/p"),
                Session(
                    id = "cached-but-archived-server-side",
                    directory = "/p",
                    time = Session.TimeInfo(archived = 1_700_000_000_000L)
                )
            )
        )

        val report = coordinator.dailySweepIfNeeded("g1")

        assertEquals(AliveCompleteness.Complete, report.completeness)
        // The archived server-side session is NOT in the alive set (only
        // "active" is) → the cached "cached-but-archived-server-side" row is
        // an orphan and gets evicted.
        assertTrue(
            "archived-on-server session is excluded from alive set → orphan evicted",
            report.evictedSessionIds.contains("cached-but-archived-server-side"),
        )
        assertFalse(
            "active session is in alive set → kept",
            report.evictedSessionIds.contains("active"),
        )
    }

    @Test
    fun `complete alive set - per-workdir roots enumeration covers cached workdirs`() = runTest {
        // 2 cached workdirs, each with one cached session. Both workdirs'
        // sessions are alive → no eviction.
        seedCachedSession("g1", "s-a", workdir = "/proj-a")
        seedCachedSession("g1", "s-b", workdir = "/proj-b")
        // Global getSessions returns empty (catch-all path finds nothing).
        coEvery { repo.getSessions(any()) } returns Result.success(emptyList())
        // Per-workdir roots return each workdir's session.
        coEvery { repo.getSessionsForDirectory(eq("/proj-a"), any()) } returns Result.success(
            listOf(Session(id = "s-a", directory = "/proj-a"))
        )
        coEvery { repo.getSessionsForDirectory(eq("/proj-b"), any()) } returns Result.success(
            listOf(Session(id = "s-b", directory = "/proj-b"))
        )

        val report = coordinator.dailySweepIfNeeded("g1")

        assertEquals(AliveCompleteness.Complete, report.completeness)
        assertEquals(2, report.verifiedAliveCount)
        assertTrue("no orphans (all alive)", report.evictedSessionIds.isEmpty())
        // Fan-out covered BOTH cached workdirs.
        coVerify(exactly = 1) { repo.getSessionsForDirectory("/proj-a", any()) }
        coVerify(exactly = 1) { repo.getSessionsForDirectory("/proj-b", any()) }
    }

    @Test
    fun `complete alive set - global getSessions supplements per-workdir`() = runTest {
        // A cached session whose workdir is NOT in the cache (defensive case)
        // is still found via the global getSessions catch-all.
        seedCachedSession("g1", "s-x", workdir = "/unknown")
        // workdirsInGroup returns "/unknown"; per-workdir fetch for it fails.
        coEvery { repo.getSessionsForDirectory(eq("/unknown"), any()) } returns Result.success(emptyList())
        // Global supplement finds the session.
        coEvery { repo.getSessions(any()) } returns Result.success(
            listOf(Session(id = "s-x", directory = "/elsewhere"))
        )

        val report = coordinator.dailySweepIfNeeded("g1")

        // Global catch-all kept the session alive; no orphan eviction.
        assertEquals(AliveCompleteness.Complete, report.completeness)
        assertTrue("s-x found via global supplement, not evicted", report.evictedSessionIds.isEmpty())
    }

    // ─────────── Incomplete alive set → mark-only ───────────────────────────

    @Test
    fun `incomplete alive set - per-workdir fetch failure degrades to mark-only`() = runTest {
        // One fetch fails → alive set is incomplete → mark-only path runs.
        seedCachedSession("g1", "alive")
        seedCachedSession("g1", "missing-from-partial-alive")
        coEvery { repo.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repo.getSessionsForDirectory(any(), any()) } returns Result.failure(
            java.io.IOException("server offline")
        )

        val report = coordinator.dailySweepIfNeeded("g1")

        assertEquals(AliveCompleteness.Incomplete, report.completeness)
        // Critical: NO deletion in the mark-only path — the partial alive set
        // may have missed legitimately-alive sessions.
        assertTrue("mark-only does not evict", report.evictedSessionIds.isEmpty())
        assertNotNull(
            "missing-from-partial-alive NOT evicted (incomplete → mark-only)",
            db.cacheDao().session("g1", "missing-from-partial-alive"),
        )
        assertNotNull("alive NOT evicted either", db.cacheDao().session("g1", "alive"))
    }

    @Test
    fun `incomplete alive set - global getSessions failure degrades to mark-only`() = runTest {
        seedCachedSession("g1", "alive")
        coEvery { repo.getSessions(any()) } returns Result.failure(java.io.IOException("network"))
        coEvery { repo.getSessionsForDirectory(any(), any()) } returns Result.success(emptyList())

        val report = coordinator.dailySweepIfNeeded("g1")

        assertEquals(AliveCompleteness.Incomplete, report.completeness)
        assertTrue(report.evictedSessionIds.isEmpty())
        assertNotNull("alive NOT evicted (mark-only)", db.cacheDao().session("g1", "alive"))
    }

    @Test
    fun `incomplete alive set - truncation degrades to mark-only`() = runTest {
        // Response size == COMPLETE_ENUMERATION_LIMIT (10_000) → likely
        // truncated → mark-only. We can't easily seed 10k cached sessions,
        // but the truncation signal is purely on the response side.
        seedCachedSession("g1", "alive")
        val bigResponse = (0 until 10_000).map { Session(id = "s$it", directory = "/p") }
        coEvery { repo.getSessions(any()) } returns Result.success(bigResponse)

        val report = coordinator.dailySweepIfNeeded("g1")

        assertEquals(
            "truncated response (size == limit) → mark-only",
            AliveCompleteness.Incomplete,
            report.completeness,
        )
        assertTrue("no eviction on incomplete", report.evictedSessionIds.isEmpty())
    }

    @Test
    fun `mark-only refreshes lastVerifiedAt for the seen-alive sessions`() = runTest {
        // The incomplete path still benefits the cache: sessions confirmed
        // alive get their lastVerifiedAt refreshed (so the 7d rule does not
        // evict them between daily sweeps). To exercise this:
        //  - newestCachedAt = now (so 30d rule does not fire)
        //  - lastVerifiedAt = 6d (just under 7d — survives THIS sweep's 7d
        //    cutoff, but is drifting toward the cliff)
        //  - per-workdir fetch FAILS (alive set incomplete → mark-only path)
        //  - global getSessions returns the alive session (it IS in the
        //    seen set, so markSeenAliveOnly touches it)
        val now = System.currentTimeMillis()
        val staleBefore = now - 6 * 24L * 60L * 60L * 1000L
        db.cacheDao().upsertSession(
            CachedSessionEntity(
                serverGroupFp = "g1", sessionId = "alive",
                createdAt = 1L, newestCachedAt = now,
                lastVerifiedAt = staleBefore, workdir = "/p"
            )
        )
        coEvery { repo.getSessionsForDirectory(any(), any()) } returns Result.failure(java.io.IOException("net"))
        coEvery { repo.getSessions(any()) } returns Result.success(
            listOf(Session(id = "alive", directory = "/p"))
        )

        coordinator.dailySweepIfNeeded("g1")

        val after = db.cacheDao().session("g1", "alive")!!
        assertTrue(
            "lastVerifiedAt refreshed by mark-only path (was 6d, now fresh)",
            after.lastVerifiedAt > now - 1_000L,
        )
    }

    // ─────────── LRU / age eviction always runs ─────────────────────────────

    @Test
    fun `dailySweepIfNeeded invokes applyEvictionPolicy before alive sweep`() = runTest {
        // The LRU / age policy runs unconditionally on every sweep — even
        // when the alive enumeration is incomplete (the policies are passive;
        // they do not depend on the server-side alive set). We verify this
        // via observable side effects: a session whose lastVerifiedAt is 8d
        // stale is evicted by the 7d policy even when the alive set fails
        // to enumerate (mark-only path).
        val now = System.currentTimeMillis()
        db.cacheDao().upsertSession(
            CachedSessionEntity(
                serverGroupFp = "g1", sessionId = "stale-verified",
                createdAt = 1L, newestCachedAt = now,
                lastVerifiedAt = now - 8 * 24L * 60L * 60L * 1000L, workdir = "/p"
            )
        )
        coEvery { repo.getSessions(any()) } returns Result.failure(java.io.IOException("net"))

        coordinator.dailySweepIfNeeded("g1")

        // The 7d rule (inside applyEvictionPolicy) evicted the stale session
        // — even though the alive sweep degraded to mark-only.
        assertNull(
            "applyEvictionPolicy ran (7d evicted stale-verified before the mark-only alive sweep)",
            db.cacheDao().session("g1", "stale-verified"),
        )
    }

    @Test
    fun `dailySweepIfNeeded continues to alive sweep when applyEvictionPolicy fails`() = runTest {
        // Defensive: a failure in applyEvictionPolicy should not block the
        // alive sweep (the two are independent concerns; one failing should
        // not skip the other).
        seedCachedSession("g1", "alive")
        // Spy on the cache to make applyEvictionPolicy throw.
        val failingCache = mockk<CacheRepository>(relaxed = true)
        coEvery { failingCache.applyEvictionPolicy(any()) } throws java.io.IOException("db locked")
        coEvery { failingCache.cachedWorkdirsInGroup(any()) } returns emptyList()
        coEvery {
            failingCache.sweepOrphansWithCompleteAliveSet(any(), any())
        } returns EvictionReport(0, 0, emptyList())
        val failingCoordinator = CacheMaintenanceCoordinator(failingCache, repo, settings) { "g1" }

        val report = failingCoordinator.dailySweepIfNeeded("g1")

        // applyEvictionPolicy threw, but the coordinator caught + continued.
        coVerify(exactly = 1) { failingCache.applyEvictionPolicy("g1") }
        // Alive sweep still ran.
        coVerify(exactly = 1) { failingCache.sweepOrphansWithCompleteAliveSet(eq("g1"), any()) }
        // Stamp happened (the work completed).
        coVerify(exactly = 1) { settings.setLastSweepEpochDay(eq("g1"), any()) }
        // Report reflects the alive sweep outcome.
        assertEquals(AliveCompleteness.Complete, report.completeness)
    }

    @Test
    fun `dailySweepIfNeeded for a non-connected group only applies local eviction`() = runTest {
        val isolatedCache = mockk<CacheRepository>(relaxed = true)
        val isolatedRepo = mockk<OpenCodeRepository>(relaxed = true)
        val isolatedSettings = mockk<SettingsManager>(relaxed = true)
        every { isolatedSettings.getLastSweepEpochDay("other-group") } returns null
        coEvery { isolatedCache.applyEvictionPolicy("other-group") } returns EvictionReport(0, 0, emptyList())
        coEvery { isolatedRepo.getSessions(any()) } returns Result.success(emptyList())
        coEvery { isolatedRepo.getSessionsForDirectory(any(), any()) } returns Result.success(emptyList())
        coEvery {
            isolatedCache.sweepOrphansWithCompleteAliveSet(any(), any())
        } returns EvictionReport(0, 0, emptyList())
        val isolatedCoordinator = CacheMaintenanceCoordinator(
            isolatedCache,
            isolatedRepo,
            isolatedSettings,
        ) { "connected-group" }

        val report = isolatedCoordinator.dailySweepIfNeeded("other-group", force = true)

        coVerify(exactly = 1) { isolatedCache.applyEvictionPolicy("other-group") }
        coVerify(exactly = 0) {
            isolatedCache.sweepOrphansWithCompleteAliveSet(any(), any())
        }
        coVerify(exactly = 0) { isolatedCache.markSeenAliveOnly(any(), any()) }
        coVerify(exactly = 0) { isolatedRepo.getSessions(any()) }
        coVerify(exactly = 0) { isolatedRepo.getSessionsForDirectory(any(), any()) }
        coVerify(exactly = 0) { isolatedSettings.setLastSweepEpochDay(any(), any()) }
        assertEquals(AliveCompleteness.Incomplete, report.completeness)
        assertEquals(0, report.verifiedAliveCount)
        assertTrue(report.evictedSessionIds.isEmpty())
        assertTrue(report.suspiciousSessionIds.isEmpty())
    }

    // ─────────── helpers ────────────────────────────────────────────────────

    /**
     * Seed one cached session row + a single message. The message time is the
     * current wall clock so [CacheRepositoryImpl.applyEvictionPolicy]'s 30d /
     * 7d cutoffs (computed from the same clock) do NOT fire on the seeded
     * row — isolating the test to the behaviour under assertion.
     */
    private suspend fun seedCachedSession(fp: String, sid: String, workdir: String = "/p") {
        val now = System.currentTimeMillis()
        cache.putSessionWindow(
            fp, sid, createdAt = 1L, workdir = workdir,
            CachedSessionWindow(
                messages = listOf(Message(id = "m1", role = "user", time = Message.TimeInfo(created = now))),
                partsByMessage = emptyMap(),
                olderMessagesCursor = null,
                hasMoreMessages = true
            )
        )
    }
}
