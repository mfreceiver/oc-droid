package cn.vectory.ocdroid.util

import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * L4b domain split of [SettingsManager] — LEGACY → per-fp MIGRATION helper.
 *
 * Owns the one-shot, idempotent R-20 Phase 5 migration that rewrites the
 * three legacy global / baseUrl-keyed / sessionId-keyed categories to
 * per-profileId storage, plus the per-fp `cache_migration_v1_done_<fp>`
 * idempotency flag and the pre-Phase-5 legacy baseUrl key builders.
 *
 * §L4b migration-preservation contract: this is a byte-identical lift of the
 * pre-split [SettingsManager.migrateLegacyKeysToFp] + its private
 * `rewriteSessionMapLegacyToFp` + the legacy `normalizeBaseUrl` /
 * `modelAvailabilityLegacyKey` / `disabledModelsLegacyKey` helpers + the
 * `migrationFlagKey` builder. Same ESP instance, same version predicate
 * (the per-fp boolean flag), same write-on-upgrade behaviour, same
 * non-destructive (legacy keys NOT removed) policy, same idempotency. A
 * user upgrading across the refactor sees identical migration outcomes.
 *
 * Cross-domain key references: the per-fp key builders live on their owning
 * domain Prefs ([WorkdirPrefs.recentWorkdirsKey], [ModelPrefs.disabledModelsKey]
 * / [ModelPrefs.modelAvailabilityKey], [SessionPrefs.KEY_SESSION_DRAFTS] /
 * [SessionPrefs.COMPOSITE_KEY_SEPARATOR]) — referenced as `internal`
 * companions so there is exactly one source of truth per key string. The
 * legacy global key `recent_workdirs` is read off [WorkdirPrefs.KEY_RECENT_WORKDIRS].
 */
internal class MigrationHelper(
    private val encryptedPrefs: SharedPreferences,
) {
    /**
     * R-20 Phase 5: one-shot migration of the three legacy global / baseUrl-
     * keyed / sessionId-keyed categories to per-profileId storage.
     *
     * Plan §3 Phase 5 (dser/maxer): [cn.vectory.ocdroid.ui.ConnectionActions.applySavedSettings]
     * is the cold-start trigger — it runs early (AppCore.init) and is
     * idempotent per fp via the `cache_migration_v1_done_<fp>` flag. Once an
     * fp has been migrated, subsequent cold starts skip the rewrite.
     *
     * Categories migrated:
     *  1. `recent_workdirs` (global single key) → `recent_workdirs_<fp>`.
     *  2. `disabled_models_<normalizedBaseUrl>` + `model_availability_<normalizedBaseUrl>`
     *     (where baseUrl normalizes to the current profile's URL) →
     *     `disabled_models_<fp>` + `model_availability_<fp>`.
     *  3. `session_drafts` JSON map (bare-sessionId keys) → composite keys
     *     `"<fp>\u0000<sessionId>"` inside the same JSON map.
     *
     * §chat-ux-batch T8 (B3): the legacy `session_agents` / `session_models`
     * JSON maps were deleted alongside their getters/setters; the migration
     * rewrites for those two categories were dropped here (no live reader).
     *
     * The migration is non-destructive: legacy keys are NOT removed (they'd
     * be reclaimed by [SettingsManager.clearAllLocalData] eventually). This keeps the
     * migration reversible in case of a rollback — the new code reads only
     * the fp-keyed slot; old code reading the legacy slot sees its original
     * value. Idempotency comes from the per-fp flag.
     *
     * @param profileId the current host's fp (never blank — caller
     *   normalizes via `profileId.ifBlank { id }`).
     * @param legacyBaseUrl the current host's normalized baseUrl (used to
     *   locate the legacy `disabled_models_*` / `model_availability_*` slot
     *   for THIS server only — other URLs' data is left in place as orphan).
     */
    fun migrateLegacyKeysToFp(profileId: String, legacyBaseUrl: String) {
        if (profileId.isBlank()) return
        val flagKey = migrationFlagKey(profileId)
        if (encryptedPrefs.getBoolean(flagKey, false)) return

        val e = encryptedPrefs.edit()

        // ── 1) recent_workdirs (global) → recent_workdirs_<fp> ───────────────
        // Only copy if the fp slot is empty (defensive: never overwrite a
        // value that a prior partial migration wrote).
        val legacyWorkdirsJson = encryptedPrefs.getString(WorkdirPrefs.KEY_RECENT_WORKDIRS, null)
        if (legacyWorkdirsJson != null &&
            !encryptedPrefs.contains(WorkdirPrefs.recentWorkdirsKey(profileId))
        ) {
            e.putString(WorkdirPrefs.recentWorkdirsKey(profileId), legacyWorkdirsJson)
        }

        // ── 2) disabled_models / model_availability (per-baseUrl) → per-fp ──
        // Locate the legacy slots for THIS server's baseUrl. Other URLs' data
        // stays orphaned (multi-host users would need to migrate each host's
        // data on first cold-start of that host).
        if (!legacyBaseUrl.isBlank()) {
            val legacyDisabledKey = disabledModelsLegacyKey(legacyBaseUrl)
            val legacyAvailabilityKey = modelAvailabilityLegacyKey(legacyBaseUrl)
            if (!encryptedPrefs.contains(ModelPrefs.disabledModelsKey(profileId))) {
                encryptedPrefs.getStringSet(legacyDisabledKey, null)?.let {
                    e.putStringSet(ModelPrefs.disabledModelsKey(profileId), it)
                }
            }
            if (!encryptedPrefs.contains(ModelPrefs.modelAvailabilityKey(profileId))) {
                encryptedPrefs.getStringSet(legacyAvailabilityKey, null)?.let {
                    e.putStringSet(ModelPrefs.modelAvailabilityKey(profileId), it)
                }
            }
        }

        // ── 3) session_drafts (bare sessionId) → composite ───────────────
        // Rewrite the JSON map in place: each entry's key is prefixed with
        // `<fp>\u0000`. Entries that already carry the composite prefix (a
        // prior partial migration wrote some entries before the flag landed)
        // are left alone.
        //
        // §chat-ux-batch T8 (B3): the session_agents / session_models rewrites
        // were removed (the maps + their getters/setters were deleted; no live
        // reader remains). session_drafts keeps its migration (drafts still
        // active).
        rewriteSessionMapLegacyToFp(SessionPrefs.KEY_SESSION_DRAFTS, profileId, e)

        e.putBoolean(flagKey, true)
        e.apply()
    }

    /**
     * §需求12 rev-6 blocker D: removes the per-profile R-20 Phase 5 migration
     * idempotency flag (`cache_migration_v1_done_<profileId>`) for [profileId].
     * Called by [SettingsManager.clearAllForProfile] on profile deletion so the
     * flag does not leak as an orphan (the deleted profile's id is a canonical
     * UUID, so [cleanupOrphanGroupKeys] intentionally PRESERVES it — only a
     * direct clear on deletion closes the lifecycle). No-op on blank
     * [profileId] (defensive) and when the flag was never set ([remove] on a
     * missing key is a no-op).
     */
    fun clearMigrationFlag(profileId: String) {
        if (profileId.isBlank()) return
        encryptedPrefs.edit().remove(migrationFlagKey(profileId)).apply()
    }

    /**
     * §需求12阶段4: one-shot, idempotent purge of per-group orphan keys whose
     * suffix is NOT a valid UUID.
     *
     * Background: pre-需求12 profiles could carry named-group fingerprints
     * ("A"/"B"/"C"/"D") and pre-R-20-Phase-5 slots were keyed by normalized
     * baseUrl. Post-需求12 every profile's fp == its own UUID `id`, so any
     * persisted per-fp key whose suffix is not a UUID format is an orphan
     * that no live profile can ever reference again. User decision (plan
     * §阶段4): do NOT migrate that data — just purge it.
     *
     * Scans every key in the shared [encryptedPrefs] matching the per-fp key
     * prefixes owned by [ModelPrefs] / [WorkdirPrefs] + the migration-flag
     * prefix owned here, extracts the suffix (the part after the known
     * prefix), and DELETES the key iff the suffix is not a canonical UUID
     * (`8-4-4-4-12` hex). UUID-suffixed keys (current profile.id-keyed data)
     * are always preserved.
     *
     * Idempotent via the [ORPHAN_CLEANUP_FLAG] boolean: a second invocation
     * is a no-op. Safe to call from any thread (single batched edit + apply).
     *
     * NOTE on the dev-debug seed profile: its id (`"dev-debug-4096"`) is not a
     * UUID, so its per-fp keys are purged the ONE time this runs. That is a
     * one-time loss of the DEBUG-only fixture's recent-workdir/model memory —
     * acceptable, and afterwards the flag is set so it never recurs.
     */
    fun cleanupOrphanGroupKeys() {
        if (encryptedPrefs.getBoolean(ORPHAN_CLEANUP_FLAG, false)) return
        val prefixes = listOf(
            "disabled_models_",
            "model_availability_",
            "recent_workdirs_",
            "cache_migration_v1_done_",
        )
        val e = encryptedPrefs.edit()
        var changed = false
        for (key in encryptedPrefs.all.keys) {
            val prefix = prefixes.firstOrNull { key.startsWith(it) } ?: continue
            val suffix = key.substringAfter(prefix)
            if (!isCanonicalUuid(suffix)) {
                e.remove(key)
                changed = true
            }
        }
        // ── §需求12阶段4 rev-4 blocker A: session_drafts nested composite-key sweep ──
        // SessionPrefs stores all drafts in a SINGLE JSON map keyed by
        // "<profileId>\u0000<sessionId>". Old group-keyed drafts (profileId =
        // "A"/"B"/"C"/"D") live INSIDE this map and are invisible to the
        // top-level prefix sweep above. Parse the map and drop every entry
        // whose profileId portion is NOT a canonical UUID (mirrors the
        // top-level rule). purgeOrphanDraftEntries stages its own edits into
        // `e` and returns true iff it dropped anything; OR-ing into the
        // top-level sweep's `changed` GUARANTEES the batched apply() below
        // fires iff EITHER sweep changed anything (the draft edits MUST
        // always commit even when the top-level sweep found nothing).
        changed = purgeOrphanDraftEntries(e) || changed
        if (changed) e.apply()
        // Always set the flag (even when nothing was deleted) so the scan
        // never repeats — the orphan set is bounded by this one pass.
        encryptedPrefs.edit().putBoolean(ORPHAN_CLEANUP_FLAG, true).apply()
    }

    /**
     * §需求12阶段4: canonical-UUID predicate (`8-4-4-4-12` hex, any case).
     * Matches the format `UUID.randomUUID().toString()` produces (profile.id).
     */
    private fun isCanonicalUuid(value: String): Boolean =
        UUID_REGEX.matches(value)

    /**
     * §需求12阶段4 rev-4 blocker A: purges draft entries whose composite key's
     * profileId portion is not a canonical UUID. Reads the `session_drafts`
     * JSON map, filters entries, writes back iff something was removed.
     * Entries whose composite key's profileId IS a UUID are preserved (current
     * profile.id-keyed drafts). Stages the write into [editor] so it shares
     * the single batched apply() with the top-level sweep + the flag.
     *
     * Returns `true` iff any entry was dropped, so the caller can OR the
     * result into its own `changed` flag and guarantee the batched
     * `editor.apply()` fires iff EITHER sweep changed anything (the draft
     * edits must always commit, even when the top-level prefix sweep found
     * nothing).
     */
    private fun purgeOrphanDraftEntries(editor: SharedPreferences.Editor): Boolean {
        val json = encryptedPrefs.getString(SessionPrefs.KEY_SESSION_DRAFTS, null) ?: return false
        val map: MutableMap<String, String> = try {
            Json.decodeFromString<Map<String, String>>(json).toMutableMap()
        } catch (e: Exception) {
            return false  // Corrupt JSON — leave untouched (don't risk dropping user data on a parse error).
        }
        var changed = false
        val itr = map.entries.iterator()
        while (itr.hasNext()) {
            val (compositeKey, _) = itr.next()
            // Composite key format: "<profileId>\u0000<sessionId>". Extract the
            // profileId portion (before the NUL separator). If the entry has NO
            // separator (a pre-Phase-5 bare-sessionId legacy entry that the
            // R-20 migration never reached), substringBefore returns the whole
            // key — definitely not a UUID → purge (orphan legacy draft).
            val profileIdPart = compositeKey.substringBefore(SessionPrefs.COMPOSITE_KEY_SEPARATOR)
            if (!isCanonicalUuid(profileIdPart)) {
                itr.remove()
                changed = true
            }
        }
        if (changed) {
            editor.putString(SessionPrefs.KEY_SESSION_DRAFTS, Json.encodeToString(map))
        }
        return changed
    }

    /**
     * Helper: rewrites a JSON Map<String, String> ESP entry from bare-sessionId
     * keys to composite `"<fp>\u0000<sessionId>"` keys, in place. Entries
     * already carrying the NUL prefix are preserved as-is (idempotent across
     * partial migrations). The edit is staged into [editor] so the whole
     * migration is a single batched apply().
     */
    private fun rewriteSessionMapLegacyToFp(
        key: String,
        profileId: String,
        editor: SharedPreferences.Editor,
    ) {
        val json = encryptedPrefs.getString(key, null) ?: return
        val map: MutableMap<String, String> = try {
            Json.decodeFromString<Map<String, String>>(json).toMutableMap()
        } catch (e: Exception) {
            return
        }
        val prefix = profileId + SessionPrefs.COMPOSITE_KEY_SEPARATOR
        var changed = false
        val updated = map.mapKeys { (k, _) ->
            if (k.contains(SessionPrefs.COMPOSITE_KEY_SEPARATOR)) {
                // Already composite (prior partial migration) — leave alone.
                k
            } else {
                changed = true
                prefix + k
            }
        }
        if (changed) {
            editor.putString(key, Json.encodeToString(updated))
        }
    }

    companion object {
        /** §需求12阶段4: one-shot idempotency flag for [cleanupOrphanGroupKeys]. */
        internal const val ORPHAN_CLEANUP_FLAG = "orphan_group_cleanup_v1_done"

        /** §需求12阶段4: canonical UUID format (`8-4-4-4-12` hex, any case). */
        internal val UUID_REGEX =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** R-20 Phase 5: per-fp migration flag (idempotency). */
        private fun migrationFlagKey(profileId: String): String =
            "cache_migration_v1_done_$profileId"

        // ── Legacy (pre-Phase-5) key helpers — kept ONLY for
        // [migrateLegacyKeysToFp] to read the old slots. New code MUST use
        // the per-fp versions on [ModelPrefs]. ────────────────────────────

        /**
         * §bug5 / pre-Phase-5: shared URL normalizer for the legacy per-URL
         * model keys. Strips scheme + trailing slash, lowercases the host
         * (collision defense — `http://Host:4096` vs `http://host:4096`),
         * and keeps any path so the identity matches the URL the user
         * actually configured.
         */
        private fun normalizeBaseUrl(baseUrl: String): String {
            val withoutScheme = baseUrl.substringAfter("://").trimEnd('/')
            val host = withoutScheme.substringBefore('/').lowercase()
            val path = withoutScheme.substringAfter('/', "")
            return if (path.isEmpty()) host else "$host/$path"
        }

        /** Pre-Phase-5 legacy key — see [migrateLegacyKeysToFp]. */
        private fun modelAvailabilityLegacyKey(baseUrl: String): String {
            return "model_availability_${normalizeBaseUrl(baseUrl)}"
        }

        /** Pre-Phase-5 legacy key — see [migrateLegacyKeysToFp]. */
        private fun disabledModelsLegacyKey(baseUrl: String): String {
            return "disabled_models_${normalizeBaseUrl(baseUrl)}"
        }
    }
}
