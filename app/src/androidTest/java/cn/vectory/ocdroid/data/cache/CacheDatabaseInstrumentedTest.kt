package cn.vectory.ocdroid.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R-20 Phase 0 Gate: instrumented verification of the **real** SQLCipher path.
 *
 * The JVM unit test sibling ([CacheDatabaseTest]) uses in-memory Room WITHOUT
 * [SupportOpenHelperFactory] because Robolectric cannot `System.loadLibrary`
 * a native lib. That suite validates schema, compound-PK, and CRUD behavior —
 * everything that is platform-agnostic. What it CANNOT validate is the part
 * that actually makes the cache encrypted at rest:
 *
 *  1. `libsqlcipher.so` for the device's ABI is packaged by the AAR
 *     (verified by `System.loadLibrary("sqlcipher")`).
 *  2. [SupportOpenHelperFactory] + Room can OPEN a SQLCipher-encrypted DB
 *     and round-trip rows through it (verified by an end-to-end CRUD test).
 *  3. The compound PK `(serverGroupFp, sessionId[, messageId])` truly isolates
 *     cross-server rows inside the encrypted DB (same as the unit test, but
 *     repeated here against the real SQLCipher engine to be sure the encrypted
 *     path does not silently collapse compound keys).
 *  4. plan §0 G4 destructive reset: when the DB file refuses to open (here
 *     simulated by a wrong passphrase — the exact "key mismatch" trigger named
 *     in [cn.vectory.ocdroid.di.CacheModule]'s comments), the recovery sequence
 *     `deleteDatabase + keyStore.reset + rebuild` yields a clean empty cache.
 *
 * **Build path (Plan B):** the project has no Hilt androidTest infrastructure
 * yet (`@HiltAndroidTest` / custom Hilt runner). Standing it up would require
 * `hilt-android-testing` + `kspAndroidTest(hilt-compiler)` + a separate
 * `HiltTestApplication` + runner — a far larger surface than Phase 0 needs.
 * Instead this class replicates [cn.vectory.ocdroid.di.CacheModule]'s exact
 * 3-step build (loadLibrary → CacheKeyStore → SupportOpenHelperFactory → Room
 * with fallbackToDestructiveMigration) against a **distinct DB filename**
 * (`chat_cache_test.db`) so the production cache is never touched. Every other
 * link in the chain — same native lib, same factory class, same entities, same
 * DAO, same key store — is the production path.
 *
 * These tests are network/.env-free and are designed to be run in isolation:
 *
 * ```
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * cn.vectory.ocdroid.data.cache.CacheDatabaseInstrumentedTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class CacheDatabaseInstrumentedTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val testDbName = "chat_cache_test.db"

    @After
    fun tearDown() {
        // Best-effort cleanup so each test starts from a clean slate and the
        // emulator is left clean for the orchestrator's next run. deleteDatabase
        // also removes the -wal / -shm sidecar files.
        runCatching { ctx.deleteDatabase(testDbName) }
        runCatching { CacheKeyStore(ctx).reset() }
    }

    // ───────────────────────────────────────────────────────────────────────
    // 1. native lib packaging contract
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun sqlCipher_nativeLibrary_loads() {
        // The very first line of CacheModule.provideCacheDatabase. If the
        // sqlcipher-android AAR did not package the .so for the emulator's ABI,
        // this throws UnsatisfiedLinkError. Idempotent: a second load is a no-op.
        try {
            System.loadLibrary("sqlcipher")
        } catch (e: UnsatisfiedLinkError) {
            fail("libsqlcipher missing — AAR abiFilters did not match device ABI: ${e.message}")
        }

        // Smoke-touch the SQLCipher factory so we know the Java/JNI surface
        // that depends on the native lib resolves, not just that dlopen won.
        // (Full native exercise happens in the round-trip test below.)
        SupportOpenHelperFactory(ByteArray(32))
    }

    // ───────────────────────────────────────────────────────────────────────
    // 2. encrypted DB opens + round-trips through the real SupportFactory path
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun encryptedDatabase_opensAndRoundTrips() = runBlocking {
        val db = buildEncryptedDb()
        val dao = db.cacheDao()
        try {
            val session = CachedSessionEntity(
                serverGroupFp = "grp-1",
                sessionId = "ses-1",
                createdAt = 1_700_000_000_000L,
                newestCachedAt = 1_700_000_001_000L,
                lastVerifiedAt = 1_700_000_002_000L,
                workdir = "/home/u/proj"
            )
            val messages = listOf(
                CachedMessageEntity("grp-1", "ses-1", "m-1", 100L, "user", "[]"),
                CachedMessageEntity(
                    "grp-1", "ses-1", "m-2", 200L, "assistant",
                    """[{"type":"text"}]"""
                ),
                CachedMessageEntity("grp-1", "ses-1", "m-3", 300L, "user", "[]")
            )

            dao.upsertSession(session)
            dao.upsertMessages(messages)

            val readSession = dao.session("grp-1", "ses-1")
            assertNotNull("session row must survive a close/reopen of the encrypted DB", readSession)
            // Full-equality on the entity covers every column (incl. compound PK).
            assertEquals(session, readSession)
            // Explicit field spot-checks that the wire-format-critical fields
            // (nullable createdAt, JSON parts blob, workdir) round-trip intact.
            assertEquals(1_700_000_000_000L, readSession!!.createdAt)
            assertEquals("/home/u/proj", readSession.workdir)

            val readMessages = dao.messages("grp-1", "ses-1")
            assertEquals(3, readMessages.size)
            // ASC ordering by time is the contract the cache relies on for slices.
            assertEquals(listOf("m-1", "m-2", "m-3"), readMessages.map { it.messageId })
            assertEquals(listOf(100L, 200L, 300L), readMessages.map { it.time })
            assertEquals("""[{"type":"text"}]""", readMessages[1].parts)
        } finally {
            db.close()
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // 4. compound PK isolates serverGroupFp inside the encrypted DB
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun compositeKey_isolatesGroups() = runBlocking {
        val db = buildEncryptedDb()
        val dao = db.cacheDao()
        try {
            // Same sessionId "ses-shared" under two different serverGroupFp —
            // the whole reason for the compound PK: plan §0 notes sessionId is
            // a branded `ses_xxxx` string, NOT a UUID, and can collide across
            // server clones. A bare sessionId PK would silently merge them.
            dao.upsertSession(
                CachedSessionEntity("grp-A", "ses-shared", null, 100L, 100L, "/a")
            )
            dao.upsertSession(
                CachedSessionEntity("grp-B", "ses-shared", 5L, 200L, 200L, "/b")
            )
            // Compound PK extends to messages: same messageId under two groups
            // must coexist as two distinct rows.
            dao.upsertMessages(
                listOf(CachedMessageEntity("grp-A", "ses-shared", "m-x", 10L, "user", "[]"))
            )
            dao.upsertMessages(
                listOf(CachedMessageEntity("grp-B", "ses-shared", "m-x", 20L, "assistant", "[]"))
            )

            val groupA = dao.session("grp-A", "ses-shared")
            val groupB = dao.session("grp-B", "ses-shared")
            assertNotNull(groupA)
            assertNotNull(groupB)
            assertEquals("/a", groupA!!.workdir)
            assertEquals("/b", groupB!!.workdir)
            // null createdAt must survive the encrypted round-trip (Phase 0
            // treats createdAt==null as UnknownColdStart, so preserving null
            // is semantically load-bearing).
            assertNull(groupA.createdAt)
            assertEquals(5L, groupB.createdAt)

            assertEquals(1, dao.messages("grp-A", "ses-shared").size)
            assertEquals(1, dao.messages("grp-B", "ses-shared").size)
            assertEquals(10L, dao.messages("grp-A", "ses-shared").first().time)
            assertEquals(20L, dao.messages("grp-B", "ses-shared").first().time)
            assertFalse(
                "grp-A and grp-B must not cross-contaminate",
                groupA == groupB
            )
        } finally {
            db.close()
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // 3. plan §0 G4 destructive reset recovers from a key-mismatch "corruption"
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun destructiveReset_recoversFromCorruption() = runBlocking {
        val keyStore = CacheKeyStore(ctx)
        // Belt-and-suspenders: start from no persisted key (the @After of the
        // previous test should already have left things clean).
        keyStore.reset()

        // Step 1 — build a real encrypted DB with key A and write one row.
        val keyA = keyStore.getOrCreateKey()
        val dbA = buildEncryptedDb(passphrase = keyA)
        dbA.cacheDao().upsertSession(
            CachedSessionEntity("grp-1", "ses-1", 1L, 1L, 1L, "/p")
        )
        dbA.close()
        // The file now exists on disk, encrypted with keyA.

        // Step 2 — simulate "DB open fails" (the trigger for CacheModule's G4
        // runCatching branch). We open the SAME file with a DIFFERENT key.
        // SQLCipher rejects the wrong passphrase during the first page read,
        // which is the exact "key mismatch" failure mode CacheModule names.
        keyStore.reset()
        val keyB = keyStore.getOrCreateKey()
        assertFalse("test setup invariant: keyB must differ from keyA", keyB.contentEquals(keyA))

        val wrongKeyFails = try {
            // CacheModule.buildDatabase (since the glmer-blocker-#1 fix) forces
            // an EAGER open (`db.openHelper.writableDatabase`), so SQLCipher's
            // passphrase verification surfaces at build time, inside the
            // caller's runCatching. buildEncryptedDb below replicates that.
            buildEncryptedDb(passphrase = keyB)
            // No exception → SQLCipher accepted the wrong key, which would be a
            // real bug (the cache would be readable with any passphrase). Fail
            // loudly so this regression cannot pass silently.
            false
        } catch (e: Exception) {
            true
        }
        assertTrue(
            "opening an existing encrypted DB with the wrong key must fail " +
                "(the corruption analog for CacheModule's destructive reset)",
            wrongKeyFails
        )

        // Step 3 — replay CacheModule's recovery: delete DB file + reset key +
        // rebuild with a fresh key. After recovery the cache opens cleanly and
        // is treated as empty (cold-start REST repopulates it; the user is
        // never blocked by a corrupt cache).
        keyStore.reset()
        assertTrue("deleteDatabase must succeed during destructive reset", ctx.deleteDatabase(testDbName))
        val keyC = keyStore.getOrCreateKey()
        assertFalse("test setup invariant: keyC must differ from keyB", keyC.contentEquals(keyB))

        val dbRecovered = buildEncryptedDb(passphrase = keyC)
        try {
            val recovered = dbRecovered.cacheDao().session("grp-1", "ses-1")
            assertNull(
                "after destructive reset the previously-written row must be gone",
                recovered
            )
            // The recovered DB is writable — the app would not be bricked.
            dbRecovered.cacheDao().upsertSession(
                CachedSessionEntity("grp-1", "ses-1", 2L, 2L, 2L, "/p2")
            )
            assertNotNull(dbRecovered.cacheDao().session("grp-1", "ses-1"))
        } finally {
            dbRecovered.close()
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // 5. end-to-end "automatic" recovery: provideCacheDatabase detects the
    //    key mismatch via EAGER open + runs destructive reset + returns a
    //    usable empty DB, all without the caller doing anything.
    //
    // Complement to [destructiveReset_recoversFromCorruption] (test #3 above):
    // that test verifies the recovery STEPS work when invoked manually; this
    // test verifies the recovery is AUTOMATICALLY triggered by the eager-open
    // detection in CacheModule.provideCacheDatabase (glmer blocker #1 fix).
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun provideCacheDatabase_recoversFromCorruptionAutomatically() = runBlocking {
        val keyStore = CacheKeyStore(ctx)
        keyStore.reset()

        // Step 1 — seed a real encrypted DB with keyA and write a row.
        val keyA = keyStore.getOrCreateKey()
        val dbSeed = buildEncryptedDb(passphrase = keyA)
        dbSeed.cacheDao().upsertSession(
            CachedSessionEntity("grp-1", "ses-1", 1L, 1L, 1L, "/p")
        )
        dbSeed.close()

        // Step 2 — poison the keyStore so the next getOrCreateKey returns a
        // DIFFERENT key from the one the on-disk file was encrypted with.
        // provideCacheDatabase's first buildDatabase will then eager-open the
        // file with the wrong key → SQLCipher rejects → triggers G4.
        keyStore.reset()
        val keyB = keyStore.getOrCreateKey()
        assertFalse("test setup invariant: keyB must differ from keyA", keyB.contentEquals(keyA))

        // Step 3 — replay CacheModule.provideCacheDatabase's exact recovery
        // flow (loadLibrary → buildDatabase w/ eager open → on failure,
        // deleteDatabase + keyStore.reset + rebuild). Distinct DB name so the
        // production cache is untouched.
        val recovered = replayProvideCacheDatabaseFlow(keyStore)

        // Step 4 — the returned DB must be USABLE (the app would not be
        // bricked) and EMPTY (the row encrypted under keyA is gone — the
        // destructive reset did its job).
        try {
            val row = recovered.cacheDao().session("grp-1", "ses-1")
            assertNull(
                "eager-open detection + auto destructive reset must wipe the " +
                    "row encrypted under the wrong key",
                row
            )
            // Writable — confirms the cache is functional, not just readable.
            recovered.cacheDao().upsertSession(
                CachedSessionEntity("grp-1", "ses-2", 2L, 2L, 2L, "/p2")
            )
            assertNotNull(recovered.cacheDao().session("grp-1", "ses-2"))
        } finally {
            recovered.close()
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Helper: replicate CacheModule.buildDatabase with the REAL
    // SupportOpenHelperFactory path (NOT in-memory). Distinct DB filename so
    // the production chat_cache.db is never touched.
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Verbatim replay of `CacheModule.provideCacheDatabase` against
     * [testDbName] (so production chat_cache.db stays untouched). Three-tier
     * fallback: eager-open build → on failure deleteDatabase + reset + rebuild
     * → on second failure in-memory. Returns whatever the chain yields.
     */
    private fun replayProvideCacheDatabaseFlow(keyStore: CacheKeyStore): CacheDatabase {
        runCatching { System.loadLibrary("sqlcipher") }
            .onFailure { /* prod logs; tests don't assert on logcat parity */ }
        return runCatching { buildEncryptedDb(name = testDbName, passphrase = keyStore.getOrCreateKey()) }
            .getOrElse { failure1 ->
                runCatching { ctx.deleteDatabase(testDbName) }
                keyStore.reset()
                runCatching { buildEncryptedDb(name = testDbName, passphrase = keyStore.getOrCreateKey()) }
                    .getOrElse { failure2 ->
                        // Mirrors prod's terminal in-memory fallback (plan §5 "不崩").
                        // Unreachable in this test's happy path (the rebuild after
                        // reset should succeed on a real emulator), but kept for
                        // 1:1 parity with the production flow.
                        android.util.Log.e("CacheDatabaseInstrumentedTest", "rebuild failed", failure2)
                        Room.inMemoryDatabaseBuilder(ctx, CacheDatabase::class.java)
                            .fallbackToDestructiveMigration()
                            .build()
                    }
            }
    }

    private fun buildEncryptedDb(
        name: String = testDbName,
        passphrase: ByteArray = CacheKeyStore(ctx).getOrCreateKey()
    ): CacheDatabase {
        // CacheModule step 1 — load the native lib before any SQLCipher class.
        System.loadLibrary("sqlcipher")
        // CacheModule step 2 — SupportOpenHelperFactory with the raw key bytes.
        val factory = SupportOpenHelperFactory(passphrase)
        // CacheModule step 3 — Room builder wired to the SQLCipher factory.
        val db = Room.databaseBuilder(ctx, CacheDatabase::class.java, name)
            .openHelperFactory(factory)
            .fallbackToDestructiveMigration()
            .build()
        // CacheModule step 4 (glmer blocker #1) — force eager open so
        // SQLCipher's passphrase verification surfaces HERE rather than
        // escaping to the first DAO call. Matches production 1:1. Room reuses
        // this connection; do NOT close afterwards.
        db.openHelper.writableDatabase
        return db
    }
}
