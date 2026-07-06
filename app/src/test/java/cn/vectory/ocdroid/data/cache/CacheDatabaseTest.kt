package cn.vectory.ocdroid.data.cache

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-20 Phase 0: CacheDatabase schema + DAO CRUD.
 *
 * **Why in-memory Room (NOT the SQLCipher SupportFactory)?**
 * Robolectric runs on a JVM (no Android ARM/x86_64 runtime), so the SQLCipher
 * native lib (`libsqlcipher.so`) cannot be `System.loadLibrary`'d here.
 * `CacheModule`'s SupportFactory path is exercised by
 * `connectedDebugAndroidTest` on a real emulator (per AGENTS.md, Phase 0
 * gate). The schema, entity compound-PK, ordering, and CRUD behavior are all
 * platform-agnostic and validate cleanly against Room's framework
 * SupportSQLiteOpenHelper — that is what these tests cover.
 *
 * `@Config(application = Application::class)` keeps Robolectric from spinning
 * up [cn.vectory.ocdroid.OpenCodeApp] (whose Hilt graph would try to build
 * the real SupportFactory-backed CacheDatabase).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CacheDatabaseTest {

    private lateinit var db: CacheDatabase
    private lateinit var dao: CacheDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // No SupportOpenHelperFactory — Robolectric cannot load libsqlcipher.
        // allowMainThreadQueries so runTest's synchronous-style assertions
        // work without a dispatcher dance; this is a unit test, not a
        // production thread policy.
        db = Room.inMemoryDatabaseBuilder(ctx, CacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.cacheDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ───────────── session upsert / query round-trip ─────────────────────

    @Test
    fun `session upsert then query returns the same row`() = runTest {
        val session = sampleSession(fp = "grp-1", sid = "ses-1")

        dao.upsertSession(session)

        val readBack = dao.session("grp-1", "ses-1")
        assertNotNull(readBack)
        assertEquals(session, readBack)
    }

    @Test
    fun `session upsert twice with same key replaces`() = runTest {
        val original = sampleSession(fp = "grp-1", sid = "ses-1", newestCachedAt = 100L)
        val updated = original.copy(newestCachedAt = 200L, lastVerifiedAt = 250L)

        dao.upsertSession(original)
        dao.upsertSession(updated)

        val readBack = dao.session("grp-1", "ses-1")
        assertEquals(updated, readBack)
    }

    @Test
    fun `session query misses return null`() = runTest {
        assertNull(dao.session("nope", "nope"))
    }

    // ───────────── messages: ASC ordering + round-trip ───────────────────

    @Test
    fun `messages upsert and read back ordered by time ascending`() = runTest {
        val msgs = listOf(
            sampleMessage(fp = "grp-1", sid = "ses-1", mid = "m-3", time = 300L),
            sampleMessage(fp = "grp-1", sid = "ses-1", mid = "m-1", time = 100L),
            sampleMessage(fp = "grp-1", sid = "ses-1", mid = "m-2", time = 200L)
        )

        dao.upsertMessages(msgs)

        val readBack = dao.messages("grp-1", "ses-1")
        assertEquals(3, readBack.size)
        // ASC by time regardless of insertion order.
        assertEquals(listOf("m-1", "m-2", "m-3"), readBack.map { it.messageId })
        assertEquals(listOf(100L, 200L, 300L), readBack.map { it.time })
    }

    @Test
    fun `message upsert twice with same key replaces`() = runTest {
        val original = sampleMessage(fp = "grp-1", sid = "ses-1", mid = "m-1", parts = "[old]")
        val updated = original.copy(parts = "[updated]")

        dao.upsertMessages(listOf(original))
        dao.upsertMessages(listOf(updated))

        val readBack = dao.messages("grp-1", "ses-1")
        assertEquals(1, readBack.size)
        assertEquals("[updated]", readBack.first().parts)
    }

    // ───────────── delete session: cascades session + messages ───────────

    @Test
    fun `deleteSession removes the session row`() = runTest {
        dao.upsertSession(sampleSession(fp = "grp-1", sid = "ses-1"))
        dao.upsertMessages(listOf(sampleMessage(fp = "grp-1", sid = "ses-1", mid = "m-1")))

        dao.deleteSession("grp-1", "ses-1")
        dao.deleteSessionMessages("grp-1", "ses-1")

        assertNull(dao.session("grp-1", "ses-1"))
        assertTrue(dao.messages("grp-1", "ses-1").isEmpty())
    }

    @Test
    fun `deleteSession is scoped to the targeted session`() = runTest {
        dao.upsertSession(sampleSession(fp = "grp-1", sid = "ses-1"))
        dao.upsertSession(sampleSession(fp = "grp-1", sid = "ses-2"))
        dao.upsertMessages(listOf(sampleMessage(fp = "grp-1", sid = "ses-1", mid = "m-1")))
        dao.upsertMessages(listOf(sampleMessage(fp = "grp-1", sid = "ses-2", mid = "m-2")))

        dao.deleteSession("grp-1", "ses-1")
        dao.deleteSessionMessages("grp-1", "ses-1")

        assertNull(dao.session("grp-1", "ses-1"))
        assertNotNull(dao.session("grp-1", "ses-2"))
        assertTrue(dao.messages("grp-1", "ses-1").isEmpty())
        assertEquals(1, dao.messages("grp-1", "ses-2").size)
    }

    // ───────────── delete group: wipes whole group, leaves others ─────────

    @Test
    fun `deleteGroup removes all sessions and messages for the fp`() = runTest {
        dao.upsertSession(sampleSession(fp = "grp-A", sid = "ses-1"))
        dao.upsertSession(sampleSession(fp = "grp-A", sid = "ses-2"))
        dao.upsertSession(sampleSession(fp = "grp-B", sid = "ses-1"))
        dao.upsertMessages(listOf(sampleMessage(fp = "grp-A", sid = "ses-1", mid = "m-1")))
        dao.upsertMessages(listOf(sampleMessage(fp = "grp-A", sid = "ses-2", mid = "m-2")))
        dao.upsertMessages(listOf(sampleMessage(fp = "grp-B", sid = "ses-1", mid = "m-3")))

        dao.deleteGroupSessions("grp-A")
        dao.deleteGroupMessages("grp-A")

        assertNull(dao.session("grp-A", "ses-1"))
        assertNull(dao.session("grp-A", "ses-2"))
        assertTrue(dao.messages("grp-A", "ses-1").isEmpty())
        assertTrue(dao.messages("grp-A", "ses-2").isEmpty())
        // Other group untouched (the cross-server isolation guarantee).
        assertNotNull(dao.session("grp-B", "ses-1"))
        assertEquals(1, dao.messages("grp-B", "ses-1").size)
    }

    // ───────────── clearAll ───────────────────────────────────────────────

    @Test
    fun `clearAll wipes every row`() = runTest {
        dao.upsertSession(sampleSession(fp = "grp-A", sid = "ses-1"))
        dao.upsertSession(sampleSession(fp = "grp-B", sid = "ses-2"))
        dao.upsertMessages(listOf(sampleMessage(fp = "grp-A", sid = "ses-1", mid = "m-1")))
        dao.upsertMessages(listOf(sampleMessage(fp = "grp-B", sid = "ses-2", mid = "m-2")))

        dao.clearAllSessions()
        dao.clearAllMessages()

        assertNull(dao.session("grp-A", "ses-1"))
        assertNull(dao.session("grp-B", "ses-2"))
        assertTrue(dao.messages("grp-A", "ses-1").isEmpty())
        assertTrue(dao.messages("grp-B", "ses-2").isEmpty())
    }

    // ───────────── compound PK collision defense ─────────────────────────
    // (serverGroupFp, sessionId) — different fp + same sessionId MUST coexist.

    @Test
    fun `compound pk keeps same sessionId across different serverGroupFp`() = runTest {
        // Same sessionId "ses-shared" under two different groups — the whole
        // reason for the compound PK (plan §0: sessionId is ses_xxxx, NOT a
        // UUID, and can collide across server clones).
        dao.upsertSession(sampleSession(fp = "grp-A", sid = "ses-shared"))
        dao.upsertSession(sampleSession(fp = "grp-B", sid = "ses-shared"))
        dao.upsertMessages(listOf(sampleMessage(fp = "grp-A", sid = "ses-shared", mid = "m-x")))
        dao.upsertMessages(listOf(sampleMessage(fp = "grp-B", sid = "ses-shared", mid = "m-x")))

        assertNotNull(dao.session("grp-A", "ses-shared"))
        assertNotNull(dao.session("grp-B", "ses-shared"))
        assertEquals(1, dao.messages("grp-A", "ses-shared").size)
        assertEquals(1, dao.messages("grp-B", "ses-shared").size)
        // (serverGroupFp, sessionId, messageId) compound on messages too.
    }

    @Test
    fun `compound pk on messages keeps same messageId across different groups`() = runTest {
        // Same messageId "m-shared" under two different (fp, session) pairs.
        dao.upsertMessages(listOf(sampleMessage(fp = "grp-A", sid = "ses-1", mid = "m-shared")))
        dao.upsertMessages(listOf(sampleMessage(fp = "grp-B", sid = "ses-1", mid = "m-shared")))

        assertEquals(1, dao.messages("grp-A", "ses-1").size)
        assertEquals(1, dao.messages("grp-B", "ses-1").size)
    }

    // ───────────── helpers ───────────────────────────────────────────────

    private fun sampleSession(
        fp: String,
        sid: String,
        createdAt: Long? = 1L,
        newestCachedAt: Long = 100L,
        lastVerifiedAt: Long = 100L,
        workdir: String = "/tmp/proj"
    ): CachedSessionEntity = CachedSessionEntity(
        serverGroupFp = fp,
        sessionId = sid,
        createdAt = createdAt,
        newestCachedAt = newestCachedAt,
        lastVerifiedAt = lastVerifiedAt,
        workdir = workdir
    )

    private fun sampleMessage(
        fp: String,
        sid: String,
        mid: String,
        time: Long = 1L,
        role: String = "user",
        parts: String = "[]"
    ): CachedMessageEntity = CachedMessageEntity(
        serverGroupFp = fp,
        sessionId = sid,
        messageId = mid,
        time = time,
        role = role,
        parts = parts
    )
}
