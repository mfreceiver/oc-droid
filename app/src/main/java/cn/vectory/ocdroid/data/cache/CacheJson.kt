package cn.vectory.ocdroid.data.cache

import kotlinx.serialization.json.Json

/**
 * R-20 Phase 0: JSON configuration for cache-layer serialization.
 *
 * Used by Phase 1 `putSessionWindow` to (de)serialize
 * [cn.vectory.ocdroid.data.model.Part] lists into the
 * [CachedMessageEntity.parts] JSON blob column.
 *
 * - `ignoreUnknownKeys = true`: forward-compatible — if the server (or a
 *   future Part variant) adds a new field, older cache rows still deserialize
 *   instead of crashing the cache reader (maxer H10).
 * - `encodeDefaults = true`: stable on-disk representation — fields with
 *   default values are written explicitly so a later change to a default does
 *   not silently change the meaning of already-cached rows.
 *
 * Defined in Phase 0 as the single source of truth; Phase 1 callers should
 * reuse this instance rather than constructing ad-hoc `Json { ... }` blocks.
 */
val CacheJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
