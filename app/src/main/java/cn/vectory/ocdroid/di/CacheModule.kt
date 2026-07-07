package cn.vectory.ocdroid.di

import android.content.Context
import androidx.room.Room
import cn.vectory.ocdroid.data.cache.CacheDatabase
import cn.vectory.ocdroid.data.cache.CacheKeyStore
import cn.vectory.ocdroid.data.cache.CacheRepository
import cn.vectory.ocdroid.data.cache.CacheRepositoryImpl
import cn.vectory.ocdroid.data.cache.CacheDao
import cn.vectory.ocdroid.util.DebugLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Named
import javax.inject.Singleton

/**
 * R-20 Phase 0 → Phase 1: Hilt module wiring the encrypted [CacheDatabase] +
 * [CacheRepository].
 *
 * Three concerns, in order:
 *  1. `System.loadLibrary("sqlcipher")` MUST run before any SQLCipher class
 *     is touched. Done at the top of [buildOrFallback] so it precedes
 *     `SupportOpenHelperFactory` construction. (Loading is idempotent — a
 *     second call is a no-op — so safe even if other paths also load it.)
 *  2. The DB is opened with [SupportOpenHelperFactory] from
 *     [CacheKeyStore.getOrCreateKey]. The passphrase is read as a raw
 *     ByteArray (NEVER converted to a String — String is interned / immutable
 *     in the JVM heap, leaving a copy of the secret around for the GC pause;
 *     ByteArray can at least be zeroed by the caller, though SQLCipher's
 *     internal copy is unavoidable).
 *  3. Destructive-reset fallback (plan §0 G4 "密钥轮换 destructive reset"):
 *     if the DB open fails (corruption, key mismatch, schema rot), we delete
 *     the DB file + reset the key + rebuild. The cache is treated as empty —
 *     cold-start REST rehydration repopulates it. The user is NEVER blocked
 *     by a corrupt cache.
 *
 *     R-20 Phase 0 fix (glmer blocker #1): [buildDatabase] forces an EAGER
 *     open (`db.openHelper.writableDatabase`) so the G4 triggers actually
 *     fire inside the provider's runCatching — Room's `build()` is lazy and
 *     only catches config-class errors. If even the post-reset rebuild
 *     fails, we degrade to an in-memory unencrypted DB (plan §5 "不崩").
 *
 * R-20 Phase 1 (dser I-3): the build + fallback chain is extracted to
 * [buildOrFallback] so instrumented tests can call it directly against a
 * distinct DB filename, eliminating the copy-paste drift between
 * CacheModule and CacheDatabaseInstrumentedTest. The cacheDegraded flag
 * (dser I-1) records whether the in-memory fallback fired so Phase 4's
 * management UI can surface "cache is degraded" to the user.
 */
@Module
@InstallIn(SingletonComponent::class)
object CacheModule {

    /**
     * R-20 Phase 1 (dser I-1): JVM-static flag flipped to `true` iff
     * [buildOrFallback] fell through to the in-memory fallback. Read via
     * [provideCacheDegraded]. Phase 4 surfaces this in the management UI;
     * Phase 1 callers do not branch on it (they just persist / read with
     * whatever DB Hilt handed them — the in-memory fallback is still a
     * CacheDatabase, just non-persistent).
     */
    private val cacheDegraded = AtomicBoolean(false)

    @Provides
    @Singleton
    fun provideCacheDatabase(
        @ApplicationContext ctx: Context,
        keyStore: CacheKeyStore
    ): CacheDatabase = buildOrFallback(ctx, keyStore, DB_NAME)

    @Provides
    @Singleton
    fun provideCacheDao(db: CacheDatabase): CacheDao = db.cacheDao()

    @Provides
    @Singleton
    fun provideGapMarkerDao(db: CacheDatabase): cn.vectory.ocdroid.data.cache.GapMarkerDao =
        db.gapMarkerDao()

    @Provides
    @Singleton
    fun provideCacheRepository(
        dao: CacheDao,
        gapDao: cn.vectory.ocdroid.data.cache.GapMarkerDao,
        db: CacheDatabase
    ): CacheRepository = CacheRepositoryImpl(dao, gapDao, db)

    /**
     * R-20 Phase 1 (dser I-1): true iff the persistent encrypted DB is
     * unavailable and [provideCacheDatabase] handed out an in-memory
     * substitute. Phase 4's CacheManagementSection surfaces this so the user
     * knows their cache will not survive the next restart.
     */
    @Provides
    @Singleton
    @Named("cacheDegraded")
    fun provideCacheDegraded(): Boolean = cacheDegraded.get()

    /**
     * R-20 Phase 1 (dser I-3): the build + fallback chain extracted so
     * androidTest can call it against a test DB name without copy-pasting
     * the production logic (which previously drifted in
     * CacheDatabaseInstrumentedTest's `replayProvideCacheDatabaseFlow`).
     *
     * Public so test code in the `cn.vectory.ocdroid.di` package (or an
     * `internal` test in the same module) can invoke it; production only
     * reaches it via [provideCacheDatabase].
     */
    internal fun buildOrFallback(
        ctx: Context,
        keyStore: CacheKeyStore,
        dbName: String
    ): CacheDatabase {
        // MUST load before any net.zetetic.database.sqlcipher.* class is loaded.
        // Idempotent — repeated calls across multiple providers are no-ops.
        runCatching { System.loadLibrary("sqlcipher") }
            .onFailure {
                // Not fatal in unit tests (Robolectric uses Room's framework
                // SupportSQLite without SQLCipher). On a real device, a missing
                // native lib means the AAR's abiFilters did not match the
                // device — surface it loudly so the user can file a bug.
                DebugLog.e("CacheModule", "Failed to load libsqlcipher", it)
            }

        // Reset the degraded flag optimistically — only set true if the
        // in-memory fallback actually fires below.
        cacheDegraded.set(false)

        return runCatching { buildDatabase(ctx, keyStore.getOrCreateKey(), dbName) }
            .getOrElse { failure1 ->
                DebugLog.e("CacheModule", "cache DB open failed, destructive reset", failure1)
                runCatching { ctx.deleteDatabase(dbName) }
                keyStore.reset()
                runCatching { buildDatabase(ctx, keyStore.getOrCreateKey(), dbName) }
                    .getOrElse { failure2 ->
                        // Plan §5 "不崩": even the post-reset rebuild failed
                        // (persistent FS corruption, key store broken, etc).
                        // Fall back to an in-memory UNENCRYPTED Room DB so the
                        // Hilt graph still constructs and the app boots. The
                        // cache degrades to "lost on restart" — semantics
                        // match plan §0 G4 ("视同空缓存"): cold-start REST
                        // repopulates what it can; the user is NEVER blocked
                        // by a corrupt cache. The next process restart
                        // retries the encrypted path from scratch.
                        DebugLog.e(
                            "CacheModule",
                            "cache DB rebuild failed after reset, degrading to in-memory",
                            failure2
                        )
                        cacheDegraded.set(true)
                        Room.inMemoryDatabaseBuilder(ctx, CacheDatabase::class.java)
                            .fallbackToDestructiveMigration()
                            .build()
                    }
            }
    }

    /**
     * R-20 Phase 1 (dser I-3): the eager-open build step, extracted next to
     * [buildOrFallback] so test code exercises the exact same factory chain
     * (loadLibrary → SupportOpenHelperFactory → Room.builder → eager open).
     */
    private fun buildDatabase(ctx: Context, passphrase: ByteArray, dbName: String): CacheDatabase {
        val factory = SupportOpenHelperFactory(passphrase)
        val db = Room.databaseBuilder(ctx, CacheDatabase::class.java, dbName)
            .openHelperFactory(factory)
            .fallbackToDestructiveMigration()
            .build()
        // R-20 Phase 0 fix (glmer blocker #1): Room's build() is LAZY — it
        // returns a proxy and does NOT open the file, validate the SQLCipher
        // passphrase, or run onCreate/onUpgrade. Those happen on the first
        // getWritableDatabase() call, which would otherwise escape this
        // function and surface as a crash on the first DAO op. Forcing the
        // open here pulls all of that inside the caller's runCatching so the
        // destructive-reset fallback (plan §0 G4) actually triggers.
        //
        // Do NOT close afterwards: Room reuses this connection for the
        // lifetime of the Database singleton; closing would break Room's
        // internal connection pool / invalidation tracker state.
        db.openHelper.writableDatabase
        return db
    }

    private const val DB_NAME = "chat_cache.db"
}
