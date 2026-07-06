package cn.vectory.ocdroid.di

import android.content.Context
import cn.vectory.ocdroid.data.cache.CacheDatabase
import cn.vectory.ocdroid.data.cache.CacheKeyStore
import cn.vectory.ocdroid.util.DebugLog
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

/**
 * R-20 Phase 0: Hilt module wiring the encrypted [CacheDatabase].
 *
 * Three concerns, in order:
 *  1. `System.loadLibrary("sqlcipher")` MUST run before any SQLCipher class
 *     is touched. Done at the top of [provideCacheDatabase] so it precedes
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
 * `fallbackToDestructiveMigration` is the Phase 0 default; once the schema
 * stabilizes (end of Phase 3) it is replaced by explicit Migration classes.
 */
@Module
@InstallIn(SingletonComponent::class)
object CacheModule {

    @Provides
    @Singleton
    fun provideCacheDatabase(
        @ApplicationContext ctx: Context,
        keyStore: CacheKeyStore
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

        // R-20 Phase 0 fix (glmer blocker #1): buildDatabase now does an EAGER
        // open (db.openHelper.writableDatabase) so a SQLCipher key mismatch /
        // corruption / schema rot surfaces INSIDE this runCatching — not on
        // the first DAO call after the provider has returned. The previous
        // build() is lazy and only catches config-class errors (bad class
        // name, missing KSP output); the G4 contract (plan §0 "destructive
        // reset on key mismatch") was effectively dead code.
        return runCatching { buildDatabase(ctx, keyStore.getOrCreateKey()) }
            .getOrElse { failure1 ->
                DebugLog.e("CacheModule", "cache DB open failed, destructive reset", failure1)
                runCatching { ctx.deleteDatabase(DB_NAME) }
                keyStore.reset()
                runCatching { buildDatabase(ctx, keyStore.getOrCreateKey()) }
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
                        Room.inMemoryDatabaseBuilder(ctx, CacheDatabase::class.java)
                            .fallbackToDestructiveMigration()
                            .build()
                    }
            }
    }

    private fun buildDatabase(ctx: Context, passphrase: ByteArray): CacheDatabase {
        val factory = SupportOpenHelperFactory(passphrase)
        val db = Room.databaseBuilder(ctx, CacheDatabase::class.java, DB_NAME)
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
