package cn.vectory.ocdroid.data.cache

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * R-20 Phase 0: encrypted Room DB (SQLCipher via SupportFactory, wired in
 * [cn.vectory.ocdroid.di.CacheModule]).
 *
 * Entities: [CachedSessionEntity] + [CachedMessageEntity] + [GapMarkerEntity]
 * (Phase 2 added). The schema is now version 4; `fallbackToDestructiveMigration`
 * (set in [cn.vectory.ocdroid.di.CacheModule]) handles cache schema changes
 * destructively (acceptable for the dev period
 * per plan §6 Risk/回退 — the cache is a rebuildable local mirror, not a source
 * of truth). A fresh `2.json` schema is generated under `app/schemas/` by KSP.
 *
 * `exportSchema = true` requires KSP `room.schemaLocation` arg (configured in
 * `app/build.gradle.kts` → `ksp { arg(...) }`); the JSON schema is committed
 * under `app/schemas/` for future formal migrations.
 *
 * NOTE: Phase 0 DAO surface is intentionally minimal (no eviction); see
 * [CacheDao] for the rationale.
 */
@Database(
    entities = [
        CachedSessionEntity::class,
        CachedMessageEntity::class,
        GapMarkerEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
    abstract fun gapMarkerDao(): GapMarkerDao
}
