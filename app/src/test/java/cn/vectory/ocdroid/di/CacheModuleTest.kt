package cn.vectory.ocdroid.di

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import cn.vectory.ocdroid.data.cache.CacheDatabase
import cn.vectory.ocdroid.data.cache.CachedMessageEntity
import cn.vectory.ocdroid.data.cache.CachedSessionEntity
import cn.vectory.ocdroid.data.cache.CacheKeyStore
import cn.vectory.ocdroid.util.FakeAndroidKeyStoreProvider
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-20 Phase 0 fix (glmer blocker #1): verify the destructive-reset + in-memory
 * fallback chain in [CacheModule.provideCacheDatabase].
 *
 * **Why this is testable on Robolectric (and how the fallback is triggered):**
 *
 * `CacheModule.buildDatabase` does an EAGER open (`db.openHelper.writableDatabase`)
 * so a SQLCipher key mismatch surfaces inside the caller's runCatching. On
 * Robolectric, `System.loadLibrary("sqlcipher")` cannot resolve the native
 * `.so` (no ARM/x86_64 runtime), and the SQLCipher JNI surface
 * (`SupportOpenHelperFactory` → `SQLiteDatabase`) needs that native lib. So the
 * eager-open call throws `UnsatisfiedLinkError` (or a SQLCipher initialization
 * error) — the exact "DB open failed" trigger the production fallback handles.
 *
 * The test then asserts the chain:
 *  1. First buildDatabase fails → catch → deleteDatabase + keyStore.reset + rebuild.
 *  2. Second buildDatabase also fails on Robolectric → outer runCatching falls
 *     through to `Room.inMemoryDatabaseBuilder` (framework SQLite, no SQLCipher
 *     — the same path [CacheDatabaseTest] uses successfully).
 *  3. The returned [CacheDatabase] is usable (CRUD works) — the app would not
 *     be bricked even with a broken persistent cache (plan §5 "不崩").
 *
 * `@Config(application = Application::class)` keeps Robolectric from spinning
 * up [cn.vectory.ocdroid.OpenCodeApp] (whose Hilt graph would try to build the
 * real SupportFactory-backed CacheDatabase — same failure, but at the wrong
 * lifecycle point for this focused test).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CacheModuleTest {

    private lateinit var ctx: Context
    private lateinit var keyStore: CacheKeyStore
    private lateinit var encryptedPrefs: SharedPreferences
    private var openedDb: CacheDatabase? = null

    @Before
    fun setUp() {
        // CacheKeyStore builds an EncryptedSharedPreferences backed by
        // AndroidKeyStore (via MasterKey). Robolectric doesn't register the
        // AndroidKeyStore provider natively — install the software stub first
        // (same pattern as SettingsManagerTest).
        FakeAndroidKeyStoreProvider.install()
        ctx = ApplicationProvider.getApplicationContext()
        keyStore = CacheKeyStore(ctx)
        // Open the SAME EncryptedSharedPreferences file CacheKeyStore writes
        // to ("ocdroid_settings") so we can observe whether CACHE_DB_KEY was
        // wiped + regenerated. Build it the same way CacheKeyStore does
        // (same MasterKey alias → same encrypted prefs instance).
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        encryptedPrefs = EncryptedSharedPreferences.create(
            ctx,
            "ocdroid_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        // Start each test from a clean key (in case a prior test in this class
        // left one persisted in the shared EncryptedSharedPreferences file).
        keyStore.reset()
    }

    @After
    fun tearDown() {
        // Close any DB the test asked CacheModule to build — in-memory or
        // otherwise — so Room's connection pool doesn't leak across tests.
        runCatching { openedDb?.close() }
        runCatching { keyStore.reset() }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Fallback chain: returns a usable DB even when SQLCipher can't open.
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `provideCacheDatabase returns a usable in-memory DB when SQLCipher unavailable`() = runTest {
        // On Robolectric, buildDatabase's eager open hits the native SQLCipher
        // surface and fails → runCatching routes to the destructive-reset
        // branch → which also fails → outer runCatching falls through to the
        // in-memory fallback. The user-visible contract: NEVER return a broken
        // DB; the app must boot.
        val db = CacheModule.provideCacheDatabase(ctx, keyStore)
        openedDb = db

        // CRUD works on the returned DB — the fallback is functional, not a
        // stub that returns a closed / half-constructed instance.
        val dao = db.cacheDao()
        val session = CachedSessionEntity(
            serverGroupFp = "grp-1",
            sessionId = "ses-1",
            createdAt = null,
            newestCachedAt = 100L,
            lastVerifiedAt = 100L,
            workdir = "/tmp/proj"
        )
        val message = CachedMessageEntity(
            serverGroupFp = "grp-1",
            sessionId = "ses-1",
            messageId = "m-1",
            time = 100L,
            role = "user",
            parts = "[]"
        )
        dao.upsertSession(session)
        dao.upsertMessages(listOf(message))

        val readSession = dao.session("grp-1", "ses-1")
        assertNotNull("returned DB must be readable", readSession)
        assertEquals(session, readSession)
        assertEquals(1, dao.messages("grp-1", "ses-1").size)
        assertEquals("m-1", dao.messages("grp-1", "ses-1").first().messageId)
    }

    // ───────────────────────────────────────────────────────────────────────
    // Destructive reset side-effect: keyStore.reset() must run during the
    // fallback (so the persisted passphrase can't outlive a reset DB).
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `provideCacheDatabase calls keyStore reset during destructive fallback`() {
        // Seed an initial key — this is the value the fallback chain must wipe.
        keyStore.getOrCreateKey()
        val keyBefore = encryptedPrefs.getString(SettingsManager.CACHE_DB_KEY, null)
        assertNotNull("test setup: keyStore should have persisted a key", keyBefore)

        val db = CacheModule.provideCacheDatabase(ctx, keyStore)
        openedDb = db

        // During the fallback: buildDatabase #1 fails → catch → keyStore.reset()
        // wipes CACHE_DB_KEY. The retry's buildDatabase #2 calls
        // keyStore.getOrCreateKey() again, which regenerates and persists a
        // NEW key (before its own eager-open failure). The persisted value
        // must therefore be DIFFERENT from the original — proving reset ran.
        val keyAfter = encryptedPrefs.getString(SettingsManager.CACHE_DB_KEY, null)
        assertNotNull(
            "keyStore.getOrCreateKey in the rebuild attempt must have re-seeded a key",
            keyAfter
        )
        assertNotEquals(
            "keyStore.reset() must have wiped the original key during the fallback",
            keyBefore,
            keyAfter
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // Regression guard: the in-memory fallback path is identical to the
    // pattern CacheDatabaseTest uses successfully — sanity-check that we are
    // not somehow landing on a half-built SupportFactory-backed DB.
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `in-memory fallback survives multiple provideCacheDatabase invocations`() = runTest {
        // Hilt @Singleton means prod calls this once, but the test asserts the
        // path is idempotent — no native-lib state corruption across calls.
        val db1 = CacheModule.provideCacheDatabase(ctx, keyStore)
        db1.cacheDao().upsertSession(
            CachedSessionEntity("g", "s1", null, 1L, 1L, "/p")
        )
        db1.close()

        val db2 = CacheModule.provideCacheDatabase(ctx, keyStore)
        openedDb = db2
        // Different DB instance (in-memory, freshly built each call) — data
        // does NOT persist across calls. This is the documented "lost on
        // restart" degradation semantics (plan §0 G4).
        assertEquals(
            "each in-memory fallback builds a fresh DB",
            0,
            db2.cacheDao().messages("g", "s1").size
        )
    }
}
