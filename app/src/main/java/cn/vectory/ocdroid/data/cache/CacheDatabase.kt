package cn.vectory.ocdroid.data.cache

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * R-20 Phase 0: encrypted Room DB (SQLCipher via SupportFactory, wired in
 * [cn.vectory.ocdroid.di.CacheModule]).
 *
 * Entities: [CachedSessionEntity] + [CachedMessageEntity] only. GapMarkerEntity
 * is Phase 2 — it will be added there along with a version bump + Migration.
 *
 * `exportSchema = true` requires KSP `room.schemaLocation` arg (configured in
 * `app/build.gradle.kts` → `ksp { arg(...) }`); the JSON schema is committed
 * under `app/schemas/` for future formal migrations.
 *
 * `fallbackToDestructiveMigration` is acceptable for the first version (plan
 * §6 Risk/回退) — once the cache reaches a stable shape (end of Phase 3), it
 * is replaced with explicit Migration classes.
 *
 * NOTE: Phase 0 DAO surface is intentionally minimal (no eviction); see
 * [CacheDao] for the rationale.
 */
@Database(
    entities = [
        CachedSessionEntity::class,
        CachedMessageEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
}
