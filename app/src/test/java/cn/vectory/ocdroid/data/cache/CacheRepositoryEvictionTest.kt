package cn.vectory.ocdroid.data.cache

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.cache.contract.CachedSessionWindow
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
 * R-20 Phase 3 (plan §3): unit tests for the eviction + sweep pipeline on
 * [CacheRepositoryImpl]:
 *  - [CacheRepository.applyEvictionPolicy] — triple policy (LRU-50 / 30d /
 *    7d), cascade integrity, per-fp isolation.
 *  - [CacheRepository.sweepOrphansWithCompleteAliveSet] — orphan eviction +
 *    survivor lastVerifiedAt refresh.
 *  - [CacheRepository.markSeenAliveOnly] — mark-only degraded sweep (no
 *    deletion).
 *
 * Same Robolectric in-memory Room pattern as [CacheRepositoryTest] — no
 * SupportOpenHelperFactory (Robolectric cannot load libsqlcipher). The
 * eviction SQL is platform-agnostic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CacheRepositoryEvictionTest {

    private lateinit var db: CacheDatabase
    private lateinit var repo: CacheRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = CacheRepositoryImpl(db.cacheDao(), db.gapMarkerDao(), db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ─────────── applyEvictionPolicy: LRU-50 (per fp) ──────────────────────

    @Test
    fun `LRU-50 evicts the oldest session beyond the 50-row cap`() = runTest {
        // Seed 51 sessions with strictly increasing newestCachedAt, all
        // RECENT (within the last hour) so the 30d / 7d rules do NOT fire —
        // this isolates the LRU-50 policy. s0 (oldest) is the LRU-evict target.
        val now = System.currentTimeMillis()
        repeat(51) { i ->
            seedSession(
                fp = "g1",
                sid = "s$i",
                createdAt = 1L,
                newestMsgTime = now - (51 - i) * 60_000L // s0 = now-51min, s50 = now-1min
            )
        }

        val report = repo.applyEvictionPolicy("g1")

        assertEquals("evictedCount = 1 (51 - 50)", 1, report.evictedCount)
        assertEquals("keptCount = 50", 50, report.keptCount)
        assertNull("s0 (oldest) was evicted", db.cacheDao().session("g1", "s0"))
        assertNotNull("s1 survived", db.cacheDao().session("g1", "s1"))
        assertNotNull("s50 (newest) survived", db.cacheDao().session("g1", "s50"))
    }

    @Test
    fun `LRU-50 keeps the top 50 by newestCachedAt DESC`() = runTest {
        // Out-of-order insertion times — the LRU ranking is by newestCachedAt,
        // NOT insertion order. The 50 sessions with the highest newestCachedAt
        // must survive. All times are recent (within last 2h) so 30d/7d rules
        // do not fire.
        val now = System.currentTimeMillis()
        val times = (0..55).shuffled().map { now - it * 60_000L } // 0=now, 55=55min ago
        times.forEachIndexed { idx, t ->
            seedSession("g1", "s$idx", createdAt = 1L, newestMsgTime = t)
        }
        // After eviction: the 50 sessions whose newestCachedAt is in the
        // top-50 of `times` survive. The 6 sessions with the lowest (oldest)
        // times are evicted.
        val sortedTimes = times.sortedDescending()
        val top50Cutoff = sortedTimes[49] // 50th-highest time

        repo.applyEvictionPolicy("g1")

        val survivors = (0..55).filter { i -> times[i] >= top50Cutoff }
        assertEquals(50, survivors.size)
        survivors.forEach { i ->
            assertNotNull("session with top-50 time survived: s$i", db.cacheDao().session("g1", "s$i"))
        }
    }

    @Test
    fun `LRU-50 is per-serverGroupFp (cross-group isolation)`() = runTest {
        // 51 sessions in g1 (LRU evicts 1), 5 sessions in g2 (no LRU eviction).
        // All times recent so 30d/7d do not fire.
        val now = System.currentTimeMillis()
        repeat(51) { i ->
            seedSession("g1", "s$i", createdAt = 1L, newestMsgTime = now - (51 - i) * 60_000L)
        }
        repeat(5) { i ->
            seedSession("g2", "s$i", createdAt = 1L, newestMsgTime = now - (5 - i) * 60_000L)
        }

        val reportG1 = repo.applyEvictionPolicy("g1")
        val reportG2 = repo.applyEvictionPolicy("g2")

        assertEquals("g1 evicted 1 (51 → 50)", 1, reportG1.evictedCount)
        assertEquals("g2 evicted 0 (5 < 50)", 0, reportG2.evictedCount)
        // g2 is untouched by g1's eviction.
        repeat(5) { i ->
            assertNotNull("g2.s$i survived", db.cacheDao().session("g2", "s$i"))
        }
    }

    @Test
    fun `LRU-50 under-cap group is a no-op`() = runTest {
        val now = System.currentTimeMillis()
        repeat(50) { i ->
            seedSession("g1", "s$i", createdAt = 1L, newestMsgTime = now - (50 - i) * 60_000L)
        }
        val report = repo.applyEvictionPolicy("g1")
        assertEquals(0, report.evictedCount)
        assertEquals(50, report.keptCount)
    }

    // ─────────── applyEvictionPolicy: 30d age (newestCachedAt) ─────────────

    @Test
    fun `30d age rule evicts sessions whose newestCachedAt is older than 30d`() = runTest {
        // Use a group with <= 50 sessions so LRU doesn't fire — isolate the
        // 30d rule. Seed: 1 session 29d old (KEEP), 1 session 31d old (EVICT).
        // Both times are relative to System.currentTimeMillis() because
        // applyEvictionPolicy computes its cutoff the same way; the 1-day
        // margin on each side keeps the test deterministic across the few-ms
        // gap between this capture and the policy's cutoff computation.
        val now = System.currentTimeMillis()
        seedSession("g1", "keep-29d", createdAt = 1L, newestMsgTime = now - 29 * DAY_MS)
        seedSession("g1", "evict-31d", createdAt = 1L, newestMsgTime = now - 31 * DAY_MS)

        repo.applyEvictionPolicy("g1")

        assertNotNull("29d-old session kept", db.cacheDao().session("g1", "keep-29d"))
        assertNull("31d-old session evicted", db.cacheDao().session("g1", "evict-31d"))
    }

    // ─────────── applyEvictionPolicy: 7d abandon (lastVerifiedAt) ──────────

    @Test
    fun `7d abandon rule evicts sessions whose lastVerifiedAt is older than 7d`() = runTest {
        // putSessionWindow seeds lastVerifiedAt = now at write time. We bypass
        // that by directly upserting the entity with a stale lastVerifiedAt.
        val now = System.currentTimeMillis()
        db.cacheDao().upsertSession(
            CachedSessionEntity(
                serverGroupFp = "g1", sessionId = "keep-6d",
                createdAt = 1L, newestCachedAt = now - 1 * DAY_MS,
                lastVerifiedAt = now - 6 * DAY_MS, workdir = "/p"
            )
        )
        db.cacheDao().upsertSession(
            CachedSessionEntity(
                serverGroupFp = "g1", sessionId = "evict-8d",
                createdAt = 1L, newestCachedAt = now - 1 * DAY_MS,
                lastVerifiedAt = now - 8 * DAY_MS, workdir = "/p"
            )
        )

        repo.applyEvictionPolicy("g1")

        assertNotNull("6d-old lastVerified kept", db.cacheDao().session("g1", "keep-6d"))
        assertNull("8d-old lastVerified evicted", db.cacheDao().session("g1", "evict-8d"))
    }

    // ─────────── applyEvictionPolicy: cascade integrity ────────────────────

    @Test
    fun `applyEvictionPolicy cascades messages and gap markers with sessions`() = runTest {
        // 51 sessions (LRU evicts s0 — the oldest). Each has 2 messages + 1
        // gap marker. All newestCachedAt recent so 30d/7d do not fire.
        val now = System.currentTimeMillis()
        repeat(51) { i ->
            seedSession("g1", "s$i", createdAt = 1L, newestMsgTime = now - (51 - i) * 60_000L)
            repo.openGap(
                "g1", "s$i",
                lowerAnchorMessageId = "m1",
                upperBoundaryMessageId = "m2",
                initialNextBeforeCursor = "c$i"
            )
        }
        // Sanity: every session has messages + a gap.
        assertEquals(2, db.cacheDao().messages("g1", "s0").size)
        assertEquals(1, db.gapMarkerDao().gaps("g1", "s0").size)

        repo.applyEvictionPolicy("g1")

        // The evicted session's messages + gap marker are cascade-deleted.
        assertTrue("s0 messages cascade-deleted", db.cacheDao().messages("g1", "s0").isEmpty())
        assertTrue("s0 gap markers cascade-deleted", db.gapMarkerDao().gaps("g1", "s0").isEmpty())
        // A surviving session is untouched.
        assertEquals(2, db.cacheDao().messages("g1", "s50").size)
        assertEquals(1, db.gapMarkerDao().gaps("g1", "s50").size)
    }

    @Test
    fun `applyEvictionPolicy runs all three policies in one transaction`() = runTest {
        // 48 stable sessions (well under the 50 LRU cap so adding the 2
        // age-rule targets lands at exactly 50 — no LRU eviction); plus one
        // session that's 31d old (30d fires); plus one session with a fresh
        // newestCachedAt but 8d-stale lastVerifiedAt (7d fires). All three
        // policies contribute to the eviction count.
        val now = System.currentTimeMillis()
        repeat(48) { i ->
            seedSession("g1", "s$i", createdAt = 1L, newestMsgTime = now - (48 - i) * 60_000L)
        }
        // 31d-old session — 30d rule fires.
        seedSession("g1", "evict-30d", createdAt = 1L, newestMsgTime = now - 31 * DAY_MS)
        // Fresh newestCachedAt but stale lastVerifiedAt — 7d rule fires.
        db.cacheDao().upsertSession(
            CachedSessionEntity(
                serverGroupFp = "g1", sessionId = "evict-7d",
                createdAt = 1L, newestCachedAt = now,
                lastVerifiedAt = now - 8 * DAY_MS, workdir = "/p"
            )
        )

        val report = repo.applyEvictionPolicy("g1")

        // 30d evicts evict-30d; 7d evicts evict-7d. LRU does not fire (48 + 2
        // extras = 50 = the cap; nothing goes over).
        assertEquals("combined 30d + 7d eviction count", 2, report.evictedCount)
        assertNull(db.cacheDao().session("g1", "evict-30d"))
        assertNull(db.cacheDao().session("g1", "evict-7d"))
        // The 48 LRU-stable sessions survived.
        repeat(48) { i ->
            assertNotNull("s$i survived", db.cacheDao().session("g1", "s$i"))
        }
    }

    // ─────────── sweepOrphansWithCompleteAliveSet ──────────────────────────

    @Test
    fun `sweep deletes cached sessions not in the alive set`() = runTest {
        seedSession("g1", "alive-1", createdAt = 1L)
        seedSession("g1", "alive-2", createdAt = 1L)
        seedSession("g1", "orphan-1", createdAt = 1L)

        val report = repo.sweepOrphansWithCompleteAliveSet(
            "g1",
            aliveSessionIds = setOf("alive-1", "alive-2")
        )

        assertEquals(1, report.evictedCount)
        assertEquals(2, report.keptCount)
        assertEquals(listOf("orphan-1"), report.orphanIds)
        assertNull("orphan evicted", db.cacheDao().session("g1", "orphan-1"))
        assertNotNull("alive-1 kept", db.cacheDao().session("g1", "alive-1"))
    }

    @Test
    fun `sweep cascades messages and gaps for evicted orphans`() = runTest {
        seedSession("g1", "orphan", createdAt = 1L)
        repo.openGap("g1", "orphan", "m1", "m2", "c1")
        assertEquals(2, db.cacheDao().messages("g1", "orphan").size)

        repo.sweepOrphansWithCompleteAliveSet("g1", aliveSessionIds = emptySet())

        assertTrue(db.cacheDao().messages("g1", "orphan").isEmpty())
        assertTrue(db.gapMarkerDao().gaps("g1", "orphan").isEmpty())
    }

    @Test
    fun `sweep refreshes lastVerifiedAt for the alive survivors`() = runTest {
        // Seed an alive session with a STALE lastVerifiedAt (10d ago). The
        // sweep must refresh it to ~now so the next 7d abandon rule does not
        // evict it between daily sweeps.
        val now = System.currentTimeMillis()
        db.cacheDao().upsertSession(
            CachedSessionEntity(
                serverGroupFp = "g1", sessionId = "alive",
                createdAt = 1L, newestCachedAt = now - 1 * DAY_MS,
                lastVerifiedAt = now - 10 * DAY_MS, workdir = "/p"
            )
        )
        db.cacheDao().upsertMessages(
            listOf(
                CachedMessageEntity("g1", "alive", "m1", now - 1 * DAY_MS, "user", "[]")
            )
        )

        repo.sweepOrphansWithCompleteAliveSet("g1", aliveSessionIds = setOf("alive"))

        val after = db.cacheDao().session("g1", "alive")!!
        assertTrue(
            "lastVerifiedAt refreshed to within the last second (was 10d stale)",
            after.lastVerifiedAt > now - 1_000L
        )
    }

    @Test
    fun `sweep is per-serverGroupFp - other groups untouched`() = runTest {
        seedSession("g1", "orphan", createdAt = 1L)
        seedSession("g2", "alive-in-other-group", createdAt = 1L)

        repo.sweepOrphansWithCompleteAliveSet("g1", aliveSessionIds = emptySet())

        assertNull("g1 orphan evicted", db.cacheDao().session("g1", "orphan"))
        assertNotNull(
            "g2 session untouched by g1's sweep (cross-group isolation)",
            db.cacheDao().session("g2", "alive-in-other-group")
        )
    }

    @Test
    fun `sweep with an empty cache returns zero`() = runTest {
        val report = repo.sweepOrphansWithCompleteAliveSet("g1", aliveSessionIds = setOf("a", "b"))
        assertEquals(0, report.evictedCount)
        assertEquals(0, report.keptCount)
        assertTrue(report.orphanIds.isEmpty())
    }

    // ─────────── markSeenAliveOnly ─────────────────────────────────────────

    @Test
    fun `markSeenAliveOnly refreshes lastVerifiedAt for seen sessions only`() = runTest {
        // Two cached sessions: one in the seen set, one not. mark-only
        // refreshes the seen one; the unseen one's lastVerifiedAt stays put.
        val now = System.currentTimeMillis()
        db.cacheDao().upsertSession(
            CachedSessionEntity(
                serverGroupFp = "g1", sessionId = "seen",
                createdAt = 1L, newestCachedAt = now,
                lastVerifiedAt = now - 10 * DAY_MS, workdir = "/p"
            )
        )
        db.cacheDao().upsertSession(
            CachedSessionEntity(
                serverGroupFp = "g1", sessionId = "unseen",
                createdAt = 1L, newestCachedAt = now,
                lastVerifiedAt = now - 10 * DAY_MS, workdir = "/p"
            )
        )

        repo.markSeenAliveOnly("g1", seenAliveSessionIds = setOf("seen"))

        val seen = db.cacheDao().session("g1", "seen")!!
        val unseen = db.cacheDao().session("g1", "unseen")!!
        assertTrue(
            "seen session's lastVerifiedAt refreshed",
            seen.lastVerifiedAt > now - 1_000L
        )
        assertEquals(
            "unseen session's lastVerifiedAt NOT refreshed (mark-only)",
            now - 10 * DAY_MS,
            unseen.lastVerifiedAt
        )
    }

    @Test
    fun `markSeenAliveOnly does NOT delete anything`() = runTest {
        // Even sessions absent from the seen set must survive mark-only —
        // that is the entire point of the degraded path (a partial alive set
        // cannot prove a missing session is dead).
        seedSession("g1", "missing-from-seen-set", createdAt = 1L)
        seedSession("g1", "also-missing", createdAt = 1L)

        repo.markSeenAliveOnly("g1", seenAliveSessionIds = setOf("nonexistent"))

        assertNotNull(db.cacheDao().session("g1", "missing-from-seen-set"))
        assertNotNull(db.cacheDao().session("g1", "also-missing"))
    }

    // ─────────── cachedWorkdirsInGroup ─────────────────────────────────────

    @Test
    fun `cachedWorkdirsInGroup returns distinct workdirs for the fp`() = runTest {
        seedSession("g1", "s1", createdAt = 1L, workdir = "/proj-a")
        seedSession("g1", "s2", createdAt = 1L, workdir = "/proj-a")
        seedSession("g1", "s3", createdAt = 1L, workdir = "/proj-b")
        seedSession("g2", "sX", createdAt = 1L, workdir = "/other")

        val workdirs = repo.cachedWorkdirsInGroup("g1").toSet()

        assertEquals(setOf("/proj-a", "/proj-b"), workdirs)
        assertFalse("/other" in workdirs)
    }

    @Test
    fun `cachedWorkdirsInGroup excludes blank workdir entries`() = runTest {
        // Direct entity insert with a blank workdir — the DAO query filters
        // `workdir != ''` so blank entries do not pollute the enumeration.
        db.cacheDao().upsertSession(
            CachedSessionEntity("g1", "blank", createdAt = 1L, newestCachedAt = 1L, lastVerifiedAt = 1L, workdir = "")
        )
        seedSession("g1", "real", createdAt = 1L, workdir = "/real")

        val workdirs = repo.cachedWorkdirsInGroup("g1")
        assertEquals(listOf("/real"), workdirs)
    }

    // ─────────── §grouping-rewrite Round-5 C5: evictWorkdirInGroup normalize-match ──
    // The displayed/trimmed workdir passed in comes from buildWorkdirGroups's
    // output (often the absolute leading-slash form like "/proj-a"); cache
    // rows may persist slash variants the server originally returned
    // ("proj-a/"). Pre-C5 the exact-string SQL WHERE workdir = :workdir
    // silently missed variants → cached messages survived disconnect →
    // reappeared on reconnect (violated Q14). These tests pin the in-Kotlin
    // normalize-match via WorkdirPaths.normalize and the per-session cascade
    // (sessions + messages + gap markers all gone for matching workdirs).

    @Test
    fun `evictWorkdirInGroup evicts slash-variant cached rows by normalized match`() = runTest {
        // THE C5 BLOCKER TEST. Cache row stores workdir as "proj-a/" (the
        // trailing-slash variant the server returned); disconnect is invoked
        // with "/proj-a" (the absolute leading-slash form buildWorkdirGroups
        // emits as the display dir). Pre-C5 the exact-match SQL would NOT
        // delete this row → cached messages + gap survived → reappeared on
        // reconnect. Both forms normalize to "proj-a" via WorkdirPaths.normalize
        // → match → evicted (session + message + gap all gone).
        seedSession("g1", "s-variant", createdAt = 1L, workdir = "proj-a/")
        // Seed a gap marker on the variant session so the gap cascade is
        // exercised too (seedSession only seeds messages).
        repo.openGap(
            "g1", "s-variant",
            lowerAnchorMessageId = "m1",
            upperBoundaryMessageId = "m2",
            initialNextBeforeCursor = "c1"
        )
        // Sanity: session + 2 messages + 1 gap are seeded.
        assertNotNull(db.cacheDao().session("g1", "s-variant"))
        assertEquals(2, db.cacheDao().messages("g1", "s-variant").size)
        assertEquals(1, db.gapMarkerDao().gaps("g1", "s-variant").size)

        // Disconnect the absolute-form display dir.
        repo.evictWorkdirInGroup("g1", "/proj-a")

        // All three caches for the variant session are gone.
        assertNull(
            "session row evicted despite the workdir slash-variant mismatch",
            db.cacheDao().session("g1", "s-variant"),
        )
        assertTrue(
            "messages cascade-evicted for the variant session",
            db.cacheDao().messages("g1", "s-variant").isEmpty(),
        )
        assertTrue(
            "gap marker cascade-evicted for the variant session",
            db.gapMarkerDao().gaps("g1", "s-variant").isEmpty(),
        )
    }

    @Test
    fun `evictWorkdirInGroup still evicts exact match`() = runTest {
        // Sanity: the pre-C5 exact-match path is preserved (the normalize
        // match is a strict superset — it matches everything exact-match
        // matched, plus the variants).
        seedSession("g1", "s-exact", createdAt = 1L, workdir = "/proj-a")

        repo.evictWorkdirInGroup("g1", "/proj-a")

        assertNull(db.cacheDao().session("g1", "s-exact"))
        assertTrue(db.cacheDao().messages("g1", "s-exact").isEmpty())
    }

    @Test
    fun `evictWorkdirInGroup does NOT evict sibling workdirs`() = runTest {
        // Over-eviction guard: the normalize match must not accidentally nuke
        // a sibling project whose name is a substring or shares a prefix.
        // proj-a and proj-b are distinct under any normalization rule.
        seedSession("g1", "s-a", createdAt = 1L, workdir = "/proj-a")
        seedSession("g1", "s-b", createdAt = 1L, workdir = "/proj-b")

        repo.evictWorkdirInGroup("g1", "/proj-a")

        assertNull("target session evicted", db.cacheDao().session("g1", "s-a"))
        assertNotNull(
            "sibling project's session MUST survive",
            db.cacheDao().session("g1", "s-b"),
        )
        assertEquals(
            "sibling project's messages MUST survive",
            2,
            db.cacheDao().messages("g1", "s-b").size,
        )
    }

    @Test
    fun `evictWorkdirInGroup evicts ALL variant rows for the target but leaves siblings`() = runTest {
        // The cluster case: TWO cached sessions for the same logical workdir
        // under different slash variants (one "proj-a/", one "/proj-a"), PLUS
        // a sibling project. Evicting "/proj-a" must drop BOTH variants
        // (normalize-match catches each) and leave the sibling intact.
        // This is the case that exercised the bug most visibly pre-C5: even
        // if the user (or an earlier fix attempt) happened to match one
        // variant, the other would survive and resurface.
        seedSession("g1", "s-trailing-slash", createdAt = 1L, workdir = "proj-a/")
        seedSession("g1", "s-leading-slash", createdAt = 1L, workdir = "/proj-a")
        seedSession("g1", "s-sibling", createdAt = 1L, workdir = "/proj-b")

        repo.evictWorkdirInGroup("g1", "/proj-a")

        assertNull(
            "trailing-slash variant evicted",
            db.cacheDao().session("g1", "s-trailing-slash"),
        )
        assertNull(
            "leading-slash variant evicted",
            db.cacheDao().session("g1", "s-leading-slash"),
        )
        assertNotNull(
            "sibling project MUST survive both variant evictions",
            db.cacheDao().session("g1", "s-sibling"),
        )
        assertEquals(
            "sibling's messages MUST survive",
            2,
            db.cacheDao().messages("g1", "s-sibling").size,
        )
    }

    // ─────────── helpers ───────────────────────────────────────────────────

    /**
     * Seed one cached session with a single message at [newestMsgTime]. The
     * session row's `newestCachedAt` is derived from the message time
     * (mirrors [CacheRepositoryImpl.putSessionWindow]'s rule).
     */
    private suspend fun seedSession(
        fp: String,
        sid: String,
        createdAt: Long?,
        newestMsgTime: Long = 100L,
        workdir: String = "/proj"
    ) {
        repo.putSessionWindow(
            fp, sid, createdAt = createdAt, workdir = workdir,
            CachedSessionWindow(
                messages = listOf(
                    Message(id = "m1", role = "user", time = Message.TimeInfo(created = newestMsgTime)),
                    Message(id = "m2", role = "assistant", time = Message.TimeInfo(created = newestMsgTime + 1))
                ),
                partsByMessage = mapOf(
                    "m1" to listOf(Part(id = "p1", type = "text", text = "hi")),
                    "m2" to listOf(Part(id = "p2", type = "text", text = "there"))
                ),
                olderMessagesCursor = null,
                hasMoreMessages = true
            )
        )
    }

    private companion object {
        // Age-rule tests use System.currentTimeMillis() captured at test start
        // (their cutoffs are computed the same way inside applyEvictionPolicy,
        // so the few-ms gap between capture and cutoff computation stays inside
        // the 1-day margin the tests use on each side).
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
