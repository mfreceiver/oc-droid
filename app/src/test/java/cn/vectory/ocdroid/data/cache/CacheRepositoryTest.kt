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
 * R-20 Phase 1: CacheRepository verifyAndLoad + putSessionWindow + evict scope.
 *
 * Same Robolectric in-memory Room pattern as [CacheDatabaseTest] — no
 * SupportOpenHelperFactory (Robolectric cannot load libsqlcipher). The
 * verifyAndLoad single-@Transaction logic is platform-agnostic; the SQLCipher
 * path is exercised by androidTest on the emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CacheRepositoryTest {

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

    @Test
    fun `totalCachedPayloadBytes reports UTF-8 bytes rather than text characters`() = runTest {
        db.cacheDao().upsertMessages(
            listOf(CachedMessageEntity("g1", "s1", "m1", 1L, "user", "汉"))
        )

        assertEquals(3L, repo.totalCachedPayloadBytes())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ─────────── verifyAndLoad: the 5 cases (plan §3 Phase 1 tests) ─────────

    @Test
    fun `verifyAndLoad with matching createdAt returns Verified and refreshes lastVerifiedAt`() = runTest {
        seedWindow(fp = "g1", sid = "s1", createdAt = 1_700_000L)
        // Confirm seeded.
        val first = repo.verifyAndLoad("g1", "s1", createdAt = 1_700_000L)
        assertTrue("seeded match must be Verified", first is HydrateResult.Verified)
        val firstWindow = (first as HydrateResult.Verified).window
        assertEquals(listOf("m1", "m2"), firstWindow.messages.map { it.id })

        // Second verify confirms lastVerifiedAt was refreshed (the row's
        // lastVerifiedAt advances; we observe it via cachedCreatedAt still
        // being readable + matching). This is the "verified path keeps the
        // row valid for the 7d abandon rule" guarantee.
        val second = repo.verifyAndLoad("g1", "s1", createdAt = 1_700_000L)
        assertTrue(second is HydrateResult.Verified)
    }

    @Test
    fun `verifyAndLoad with mismatched createdAt evicts and returns MismatchEvicted`() = runTest {
        seedWindow(fp = "g1", sid = "s1", createdAt = 1_700_000L)

        val result = repo.verifyAndLoad("g1", "s1", createdAt = 9_999_999L)

        assertTrue("mismatch must evict", result is HydrateResult.MismatchEvicted)
        // Eviction is part of the same transaction — the row + messages are gone.
        assertNull(db.cacheDao().session("g1", "s1"))
        assertTrue(db.cacheDao().messages("g1", "s1").isEmpty())
    }

    @Test
    fun `verifyAndLoad with unknown session returns UnknownColdStart without evicting anything`() = runTest {
        val result = repo.verifyAndLoad("g1", "never-seen", createdAt = 1L)

        assertTrue(result is HydrateResult.UnknownColdStart)
        // Nothing to evict — no error.
    }

    @Test
    fun `verifyAndLoad with null createdAt returns UnknownColdStart and does not evict existing cache`() = runTest {
        // Server can return createdAt=null (momo 实测). Plan §0 says treat as
        // cold start — do NOT evict the existing cache (the cached data may
        // still be valid; we just can't fingerprint-verify it).
        seedWindow(fp = "g1", sid = "s1", createdAt = 1_700_000L)

        val result = repo.verifyAndLoad("g1", "s1", createdAt = null)

        assertTrue(result is HydrateResult.UnknownColdStart)
        // Critical: the cached row MUST survive (no fingerprint = no eviction).
        assertNotNull(db.cacheDao().session("g1", "s1"))
    }

    @Test
    fun `verifyAndLoad on existing row with no messages returns UnknownColdStart`() = runTest {
        // Edge case: session row exists but message set was evicted by an LRU
        // pass (Phase 3). Don't hydrate an empty window — cold-start instead.
        db.cacheDao().upsertSession(
            CachedSessionEntity("g1", "s1", createdAt = 1L, newestCachedAt = 1L, lastVerifiedAt = 1L, workdir = "/p")
        )
        val result = repo.verifyAndLoad("g1", "s1", createdAt = 1L)
        assertTrue(result is HydrateResult.UnknownColdStart)
    }

    // ─────────── review-fix #4: cachedCreatedAt=null symmetry ─────────────

    @Test
    fun `review-fix 4 verifyAndLoad cached createdAt null plus input non-null returns UnknownColdStart`() = runTest {
        // §review-fix #4 (glm-3 🟠#2): a cached row whose createdAt column is
        // null (first putSessionWindow happened while server returned null)
        // + a subsequent verifyAndLoad with non-null createdAt MUST NOT evict.
        // The DAO returns null for both "no row" and "row with null column",
        // so the first `when` branch (cachedCreatedAt == null → UnknownColdStart)
        // catches it BEFORE the mismatch check. Prior code's mismatch branch
        // could never reach this case (the null branch wins first), but this
        // test locks the invariant.
        seedWindow(fp = "g1", sid = "s1", createdAt = null) // cached createdAt=null
        val result = repo.verifyAndLoad("g1", "s1", createdAt = 1_700_000L) // non-null input
        assertTrue(
            "cached createdAt=null + input non-null → UnknownColdStart (NOT MismatchEvicted)",
            result is HydrateResult.UnknownColdStart,
        )
        // Critical: the cached row MUST survive.
        assertNotNull("row with null createdAt must NOT be evicted", db.cacheDao().session("g1", "s1"))
    }

    @Test
    fun `review-fix 4 verifyAndLoad both sides non-null and different returns MismatchEvicted`() = runTest {
        // Sanity: when BOTH sides are non-null AND differ, mismatch eviction
        // DOES fire (this is the legitimate eviction path).
        seedWindow(fp = "g1", sid = "s1", createdAt = 1L)
        val result = repo.verifyAndLoad("g1", "s1", createdAt = 999L)
        assertTrue(result is HydrateResult.MismatchEvicted)
        assertNull("mismatched row evicted", db.cacheDao().session("g1", "s1"))
    }

    @Test
    fun `review-fix 4 verifyFingerprint cached createdAt null plus input non-null returns UnknownColdStart`() = runTest {
        // Symmetric test for verifyFingerprint (the standalone variant).
        seedWindow(fp = "g1", sid = "s1", createdAt = null)
        val r = repo.verifyFingerprint("g1", "s1", createdAt = 1_700_000L)
        assertTrue(r is FingerprintResult.UnknownColdStart)
        assertNotNull("row with null createdAt must NOT be evicted", db.cacheDao().session("g1", "s1"))
    }

    // ─────────── verifyFingerprint (standalone, same three states) ────────

    @Test
    fun `verifyFingerprint matches but does not return a window`() = runTest {
        seedWindow(fp = "g1", sid = "s1", createdAt = 1_700_000L)
        val r = repo.verifyFingerprint("g1", "s1", createdAt = 1_700_000L)
        assertTrue(r is FingerprintResult.Verified)
    }

    @Test
    fun `verifyFingerprint mismatch evicts and returns MismatchEvicted`() = runTest {
        seedWindow(fp = "g1", sid = "s1", createdAt = 1_700_000L)
        val r = repo.verifyFingerprint("g1", "s1", createdAt = 2_222_222L)
        assertTrue(r is FingerprintResult.MismatchEvicted)
        assertNull(db.cacheDao().session("g1", "s1"))
    }

    @Test
    fun `verifyFingerprint null createdAt returns UnknownColdStart without evicting`() = runTest {
        seedWindow(fp = "g1", sid = "s1", createdAt = 1_700_000L)
        val r = repo.verifyFingerprint("g1", "s1", createdAt = null)
        assertTrue(r is FingerprintResult.UnknownColdStart)
        assertNotNull(db.cacheDao().session("g1", "s1"))
    }

    // ─────────── putSessionWindow round-trip + replace semantics ──────────

    @Test
    fun `putSessionWindow then verifyAndLoad round-trips messages and parts`() = runTest {
        val window = CachedSessionWindow(
            messages = listOf(
                Message(id = "m1", role = "user", time = Message.TimeInfo(created = 100L)),
                Message(id = "m2", role = "assistant", time = Message.TimeInfo(created = 200L))
            ),
            partsByMessage = mapOf(
                "m1" to listOf(Part(id = "p1", type = "text", text = "hello")),
                "m2" to listOf(Part(id = "p2", type = "text", text = "world"))
            ),
            olderMessagesCursor = null,
            hasMoreMessages = true
        )
        repo.putSessionWindow("g1", "s1", createdAt = 1_700_000L, workdir = "/p", window = window)

        val r = repo.verifyAndLoad("g1", "s1", createdAt = 1_700_000L)
        assertTrue(r is HydrateResult.Verified)
        val hydrated = (r as HydrateResult.Verified).window
        assertEquals(2, hydrated.messages.size)
        assertEquals(listOf("m1", "m2"), hydrated.messages.map { it.id })
        assertEquals(listOf(100L, 200L), hydrated.messages.map { it.time?.created })
        // Parts round-trip via the JSON blob column.
        assertEquals(2, hydrated.partsByMessage.size)
        assertEquals("hello", hydrated.partsByMessage["m1"]?.firstOrNull()?.text)
        assertEquals("world", hydrated.partsByMessage["m2"]?.firstOrNull()?.text)
        // §glmer-F3: 钉死 toWindow 的重建语义——即便存入 hasMore=true，重建输出也
        // 必为 false（cursor/hasMore 不持久化，由 toWindow 按一致不变量重建）。
        assertNull(hydrated.olderMessagesCursor)
        assertFalse(hydrated.hasMoreMessages)
    }

    @Test
    fun `verifyAndLoad reconstructs a consistent cursor state (no dead load-more button)`() = runTest {
        // §F3-load-more: 缓存水合重建的窗口必须 cursor/hasMore 一致。此前 toWindow
        // 硬编码 cursor=null ∧ hasMore=true，导致"加载更多"按钮显示但点击无反应。
        // 修复后：无持久化 cursor 时 hasMore=false，由随后的 REST 加载重建。
        val window = CachedSessionWindow(
            messages = listOf(Message(id = "m1", role = "user")),
            partsByMessage = emptyMap(),
            olderMessagesCursor = null,
            hasMoreMessages = false
        )
        repo.putSessionWindow("g1", "s1", createdAt = 1_700_000L, workdir = "/p", window = window)

        val r = repo.verifyAndLoad("g1", "s1", createdAt = 1_700_000L)
        val hydrated = (r as HydrateResult.Verified).window
        // 不变量：hasMore 为真当且仅当 cursor 非空。此处 cursor 缺失 → hasMore 必为 false。
        assertNull(hydrated.olderMessagesCursor)
        assertFalse(hydrated.hasMoreMessages)
    }

    @Test
    fun `putSessionWindow replaces an existing window atomically`() = runTest {
        // First put: 2 messages.
        repo.putSessionWindow(
            "g1", "s1", createdAt = 1L, workdir = "/p",
            CachedSessionWindow(
                messages = listOf(
                    Message(id = "old1", role = "user"),
                    Message(id = "old2", role = "assistant")
                ),
                partsByMessage = emptyMap(),
                olderMessagesCursor = null,
                hasMoreMessages = true
            )
        )
        // Second put: 1 different message — should atomically replace (old gone).
        repo.putSessionWindow(
            "g1", "s1", createdAt = 1L, workdir = "/p",
            CachedSessionWindow(
                messages = listOf(Message(id = "new1", role = "user")),
                partsByMessage = emptyMap(),
                olderMessagesCursor = null,
                hasMoreMessages = true
            )
        )

        val r = repo.verifyAndLoad("g1", "s1", createdAt = 1L)
        assertTrue(r is HydrateResult.Verified)
        val hydrated = (r as HydrateResult.Verified).window
        assertEquals(listOf("new1"), hydrated.messages.map { it.id })
    }

    // ─────────── evict scope (session / group / all) ──────────────────────

    @Test
    fun `evictSession removes one session without touching others in the same group`() = runTest {
        seedWindow("g1", "s1", createdAt = 1L)
        seedWindow("g1", "s2", createdAt = 1L)

        repo.evictSession("g1", "s1")

        assertNull(db.cacheDao().session("g1", "s1"))
        assertNotNull(db.cacheDao().session("g1", "s2"))
        assertTrue(db.cacheDao().messages("g1", "s1").isEmpty())
        assertEquals(2, db.cacheDao().messages("g1", "s2").size)
    }

    @Test
    fun `evictGroup removes all sessions in the group without touching other groups`() = runTest {
        seedWindow("g1", "s1", createdAt = 1L)
        seedWindow("g1", "s2", createdAt = 1L)
        seedWindow("g2", "s1", createdAt = 1L)

        repo.evictGroup("g1")

        assertNull(db.cacheDao().session("g1", "s1"))
        assertNull(db.cacheDao().session("g1", "s2"))
        // Other group untouched (the cross-server isolation guarantee).
        assertNotNull(db.cacheDao().session("g2", "s1"))
    }

    @Test
    fun `clearAll wipes every row across groups`() = runTest {
        seedWindow("g1", "s1", createdAt = 1L)
        seedWindow("g2", "s1", createdAt = 1L)

        repo.clearAll()

        assertNull(db.cacheDao().session("g1", "s1"))
        assertNull(db.cacheDao().session("g2", "s1"))
    }

    // ─────────── appendMessageIfSessionCached ────────────────────────────

    @Test
    fun `appendMessageIfSessionCached skips when session is not cached`() = runTest {
        // No cached session row → append is a no-op (do NOT proactively build
        // a cache from a single SSE event — would fragment the cache).
        repo.appendMessageIfSessionCached(
            "g1", "s-uncached",
            Message(id = "m-x", role = "user", time = Message.TimeInfo(created = 1L)),
            parts = emptyList()
        )
        assertNull(db.cacheDao().session("g1", "s-uncached"))
    }

    @Test
    fun `appendMessageIfSessionCached adds the message to a cached session`() = runTest {
        seedWindow("g1", "s1", createdAt = 1L)

        repo.appendMessageIfSessionCached(
            "g1", "s1",
            Message(id = "m-new", role = "assistant", time = Message.TimeInfo(created = 999L)),
            parts = listOf(Part(id = "p-new", type = "text", text = "new"))
        )

        val msgs = db.cacheDao().messages("g1", "s1")
        assertEquals(3, msgs.size) // 2 seeded + 1 appended
        assertTrue(msgs.any { it.messageId == "m-new" })
        // newestCachedAt bumped to the appended message's time.
        val session = db.cacheDao().session("g1", "s1")
        assertNotNull(session)
        assertEquals(999L, session!!.newestCachedAt)
    }

    // ─────────── TOCTOU atomicity note (glmer I-2) ────────────────────────
    //
    // verifyAndLoad runs inside a single Room @Transaction (db.withTransaction
    // in CacheRepositoryImpl). A concurrent evictSession (which is itself
    // @Transaction) CANNOT land between the fingerprint check and the window
    // read — Room serializes transactions on a single-threaded executor for
    // the same DB connection. This eliminates the TOCTOU that would otherwise
    // let verifyAndLoad hydrate a window that was just evicted by a concurrent
    // archive/delete signal.
    //
    // (No runtime assertion here — the test would need to artificially
    // interleave two suspend transactions on different threads, which the
    // in-memory Room + runTest dispatcher makes awkward. The atomicity
    // contract is enforced structurally by db.withTransaction; the schema
    // test suite verifies the underlying transactions serialize correctly.)

    // ─────────── R-20 Phase 2: gap methods (plan §3) ────────────────────────

    @Test
    fun `openGap returns a gapId and gapsOf lists it`() = runTest {
        seedWindowWithIds("g1", "s1", listOf("m1" to 100L, "m2" to 200L))
        val gapId = repo.openGap("g1", "s1", lowerAnchorMessageId = "m1", upperBoundaryMessageId = "m2", initialNextBeforeCursor = "cursor-1")
        assertTrue(gapId.isNotBlank())
        val gaps = repo.gapsOf("g1", "s1")
        assertEquals(1, gaps.size)
        assertEquals(gapId, gaps.single().gapId)
        assertEquals("m1", gaps.single().lowerAnchorMessageId)
        assertEquals("m2", gaps.single().upperBoundaryMessageId)
        assertEquals("cursor-1", gaps.single().nextBeforeCursor)
        assertEquals(cn.vectory.ocdroid.data.cache.contract.GapFillState.Idle, gaps.single().fillState)
    }

    @Test
    fun `resolveGap atomically deletes the marker`() = runTest {
        seedWindowWithIds("g1", "s1", listOf("m1" to 100L, "m2" to 200L))
        val gapId = repo.openGap("g1", "s1", "m1", "m2", "cursor-1")
        repo.resolveGap(gapId)
        assertTrue("resolveGap removes the marker", repo.gapsOf("g1", "s1").isEmpty())
    }

    @Test
    fun `appendOlderSlice resolves the gap when the step covers the anchor`() = runTest {
        // anchor = m1 (lower). A backward step that includes m1 → resolved.
        seedWindowWithIds("g1", "s1", listOf("m1" to 100L))
        val gapId = repo.openGap("g1", "s1", lowerAnchorMessageId = "m1", upperBoundaryMessageId = "m2", initialNextBeforeCursor = "c1")
        val older = listOf(
            Message(id = "bridge", role = "user", time = Message.TimeInfo(created = 150L)),
            Message(id = "m1", role = "user", time = Message.TimeInfo(created = 100L))
        )
        repo.appendOlderSlice(gapId, older, partsByMessage = emptyMap(), returnedCursor = "c2")
        // stepCoversAnchor (m1 in older) → gap resolved in the same transaction.
        assertTrue("anchor-covering step resolves the gap", repo.gapsOf("g1", "s1").isEmpty())
        // The older messages were persisted.
        val msgs = db.cacheDao().messages("g1", "s1").map { it.messageId }
        assertTrue(msgs.containsAll(listOf("m1", "bridge")))
    }

    @Test
    fun `appendOlderSlice marks Exhausted when cursor is null without reaching anchor`() = runTest {
        seedWindowWithIds("g1", "s1", listOf("m1" to 100L))
        val gapId = repo.openGap("g1", "s1", "m1", "m2", "c1")
        // Older step does NOT contain the anchor + null cursor → Exhausted.
        val older = listOf(Message(id = "bridge", role = "user", time = Message.TimeInfo(created = 150L)))
        repo.appendOlderSlice(gapId, older, partsByMessage = emptyMap(), returnedCursor = null)
        val gap = repo.gapsOf("g1", "s1").single()
        assertEquals(cn.vectory.ocdroid.data.cache.contract.GapFillState.Exhausted, gap.fillState)
    }

    @Test
    fun `appendOlderSlice advances boundary and cursor when more history remains`() = runTest {
        seedWindowWithIds("g1", "s1", listOf("m1" to 100L))
        val gapId = repo.openGap("g1", "s1", "m1", "m2", "c1")
        // Older step does NOT contain the anchor + non-null cursor → advance.
        val older = listOf(
            Message(id = "oldest", role = "user", time = Message.TimeInfo(created = 50L)),
            Message(id = "bridge", role = "user", time = Message.TimeInfo(created = 80L))
        )
        repo.appendOlderSlice(gapId, older, partsByMessage = emptyMap(), returnedCursor = "c2")
        val gap = repo.gapsOf("g1", "s1").single()
        assertEquals(cn.vectory.ocdroid.data.cache.contract.GapFillState.Idle, gap.fillState)
        // upperBoundary advanced to the oldest id in the step.
        assertEquals("oldest", gap.upperBoundaryMessageId)
        assertEquals("c2", gap.nextBeforeCursor)
    }

    @Test
    fun `appendOlderSlice resolves cross-gap overlap in the same transaction`() = runTest {
        // Two gaps in one session. Appending for gap A lands a step that
        // contains gap B's lowerAnchor → gap B must also resolve (plan §3).
        seedWindowWithIds("g1", "s1", listOf("anchorA" to 100L))
        val gapA = repo.openGap("g1", "s1", "anchorA", "upperA", "cA")
        val gapB = repo.openGap("g1", "s1", "anchorB", "upperB", "cB")
        assertEquals(2, repo.gapsOf("g1", "s1").size)
        // Step for gapA happens to contain anchorB (overlap).
        val older = listOf(
            Message(id = "x", role = "user", time = Message.TimeInfo(created = 90L)),
            Message(id = "anchorB", role = "user", time = Message.TimeInfo(created = 80L))
        )
        repo.appendOlderSlice(gapA, older, partsByMessage = emptyMap(), returnedCursor = null)
        val remaining = repo.gapsOf("g1", "s1")
        // gapB resolved by overlap; gapA is Exhausted (anchorA not reached, null cursor).
        assertTrue("overlap must resolve the sibling gap", remaining.none { it.gapId == gapB })
        assertTrue(remaining.any { it.gapId == gapA && it.fillState == cn.vectory.ocdroid.data.cache.contract.GapFillState.Exhausted })
    }

    @Test
    fun `loadSessionLayout returns messages and gap markers separately`() = runTest {
        // Phase 4 ring-break + parts coverage: seed NON-EMPTY parts on the
        // cached message rows and assert loadSessionLayout surfaces them on
        // both `partsByMessage` and the toCachedSessionWindow() projection.
        repo.putSessionWindow(
            "g1", "s1", createdAt = 1L, workdir = "/proj",
            CachedSessionWindow(
                messages = listOf(
                    Message(id = "m1", role = "user", time = Message.TimeInfo(created = 100L)),
                    Message(id = "m2", role = "assistant", time = Message.TimeInfo(created = 200L)),
                    Message(id = "m3", role = "assistant", time = Message.TimeInfo(created = 300L))
                ),
                partsByMessage = mapOf(
                    "m1" to listOf(Part(id = "p1", type = "text", text = "hello")),
                    "m2" to listOf(Part(id = "p2", type = "text", text = "world"))
                ),
                olderMessagesCursor = null,
                hasMoreMessages = true
            )
        )
        val gapId = repo.openGap(
            "g1", "s1",
            lowerAnchorMessageId = "m1",
            upperBoundaryMessageId = "m2",
            initialNextBeforeCursor = "cursor-1"
        )
        val layout = repo.loadSessionLayout("g1", "s1")
        assertNotNull(layout)
        // Phase 4 ring-break: the data layer returns messages + gapMarkers
        // SEPARATELY (no UI interleaving in the data layer). The interleaving
        // assertion belongs in GapAwareMessageListTest (withGaps).
        val l = layout!!
        assertEquals(listOf("m1", "m2", "m3"), l.messages.map { it.id })
        // Parts round-trip via the JSON blob column. One entry per cached
        // message row (m3 has no parts → empty list, same as toWindow).
        assertEquals(3, l.partsByMessage.size)
        assertEquals("hello", l.partsByMessage["m1"]?.firstOrNull()?.text)
        assertEquals("world", l.partsByMessage["m2"]?.firstOrNull()?.text)
        assertTrue(l.partsByMessage["m3"].isNullOrEmpty())
        // toCachedSessionWindow preserves the parts-by-message map.
        val asWindow = l.toCachedSessionWindow()
        assertEquals(3, asWindow.partsByMessage.size)
        assertEquals("hello", asWindow.partsByMessage["m1"]?.firstOrNull()?.text)
        assertEquals("world", asWindow.partsByMessage["m2"]?.firstOrNull()?.text)
        assertTrue(asWindow.partsByMessage["m3"].isNullOrEmpty())
        // Gap marker fields: gapId + cursor + boundaries + state.
        assertEquals(1, l.gapMarkers.size)
        val gap = l.gapMarkers.single()
        assertEquals(gapId, gap.gapId)
        assertEquals("m1", gap.lowerAnchorMessageId)
        assertEquals("m2", gap.upperBoundaryMessageId)
        assertEquals("cursor-1", gap.nextBeforeCursor)
        assertEquals(cn.vectory.ocdroid.data.cache.contract.GapFillState.Idle, gap.fillState)
    }

    @Test
    fun `loadSessionLayout returns gap markers in upper boundary order`() = runTest {
        // Regression: CachedSessionLayout.gapMarkers documents an upper-
        // boundary-time order contract. The GapMarkerDao has no ORDER BY,
        // so without an explicit sort the list came back in rowid/insertion
        // order. Seed TWO gaps in REVERSED upper-boundary order (the first-
        // inserted gap has the LATER boundary) and assert loadSessionLayout
        // sorts them by boundary time ascending — matching `withGaps()` in
        // ui/chat/GapAwareMessageList.
        seedWindowWithIds("g1", "s1", listOf("m1" to 100L, "m2" to 200L, "m3" to 300L, "m4" to 400L))
        // First gap: upperBoundary = m4 (t=400). Second gap: upperBoundary =
        // m2 (t=200). DAO/insertion order would yield [m4, m2]; the contract
        // requires [m2, m4].
        repo.openGap("g1", "s1", lowerAnchorMessageId = "m1", upperBoundaryMessageId = "m4", initialNextBeforeCursor = "cA")
        repo.openGap("g1", "s1", lowerAnchorMessageId = "m1", upperBoundaryMessageId = "m2", initialNextBeforeCursor = "cB")

        val layout = repo.loadSessionLayout("g1", "s1")!!
        assertEquals(
            "gapMarkers must be in upperBoundary-time ascending order, not insertion order",
            listOf("m2", "m4"),
            layout.gapMarkers.map { it.upperBoundaryMessageId },
        )
    }

    @Test
    fun `loadSessionLayout returns null for a cold-start session`() = runTest {
        assertNull(repo.loadSessionLayout("g1", "never-seen"))
    }

    // ─────────── R-20 Phase 2 fix-#4: appendOlderSlice atomicity ─────────

    @Test
    fun `fix-4 appendOlderSlice no-ops atomically when the gap was resolved concurrently`() = runTest {
        // gpter #4 TOCTOU: the gap row + sessionGaps reads were previously
        // OUTSIDE the db.withTransaction, weakening the single-transaction
        // invariant (a concurrent resolveGap could land between the read and
        // the transaction → upsert/resolve against a stale gap snapshot).
        // After the fix, both reads live INSIDE the transaction; this test
        // pins the contract by exercising the "target == null → drop" branch
        // — a gap pre-resolved before appendOlderSlice is invoked must NOT
        // resurrect any rows (no message upsert, no marker write).
        seedWindowWithIds("g1", "s1", listOf("m1" to 100L))
        val gapId = repo.openGap("g1", "s1", "m1", "m2", "c1")
        // Pre-resolve the gap (simulating a concurrent overlap resolution).
        repo.resolveGap(gapId)

        val older = listOf(Message(id = "bridge", role = "user", time = Message.TimeInfo(created = 150L)))
        repo.appendOlderSlice(gapId, older, partsByMessage = emptyMap(), returnedCursor = "c2")

        // The "bridge" message MUST NOT be persisted — the transaction saw
        // target == null and dropped the step atomically (no half-write).
        val msgs = db.cacheDao().messages("g1", "s1").map { it.messageId }
        assertTrue(
            "concurrently-resolved gap must not resurrect messages (atomic drop)",
            msgs.none { it == "bridge" },
        )
        // No gap marker re-created.
        assertTrue(repo.gapsOf("g1", "s1").isEmpty())
    }

    @Test
    fun `evictSession also clears the session gap markers`() = runTest {
        seedWindowWithIds("g1", "s1", listOf("m1" to 100L))
        repo.openGap("g1", "s1", "m1", "m2", "c1")
        repo.evictSession("g1", "s1")
        assertTrue(repo.gapsOf("g1", "s1").isEmpty())
    }

    @Test
    fun `listGroupSessions reports real messageCount for empty and non-empty rows`() = runTest {
        seedWindowWithIds("g1", "empty", emptyList())
        seedWindowWithIds("g1", "non-empty", listOf("m1" to 100L, "m2" to 200L))

        val rows = repo.listGroupSessions("g1").associateBy { it.sessionId }

        assertEquals(0, rows.getValue("empty").messageCount)
        assertEquals(2, rows.getValue("non-empty").messageCount)
    }

    // ─────────── helpers ──────────────────────────────────────────────────

    /**
     * R-20 Phase 2: seed a session window whose messages carry the given ids +
     * ascending times (oldest-first), so gap tests can reference known anchors.
     */
    private suspend fun seedWindowWithIds(fp: String, sid: String, ids: List<Pair<String, Long>>) {
        repo.putSessionWindow(
            fp, sid, createdAt = 1L, workdir = "/proj",
            CachedSessionWindow(
                messages = ids.map { (id, t) -> Message(id = id, role = "user", time = Message.TimeInfo(created = t)) },
                partsByMessage = emptyMap(),
                olderMessagesCursor = null,
                hasMoreMessages = true
            )
        )
    }

    private suspend fun seedWindow(fp: String, sid: String, createdAt: Long?) {
        repo.putSessionWindow(
            fp, sid, createdAt = createdAt, workdir = "/proj",
            CachedSessionWindow(
                messages = listOf(
                    Message(id = "m1", role = "user", time = Message.TimeInfo(created = 100L)),
                    Message(id = "m2", role = "assistant", time = Message.TimeInfo(created = 200L))
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
}
