package cn.vectory.ocdroid.util

import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * L4b domain split of [SettingsManager] — LEGACY → per-fp MIGRATION helper.
 *
 * Owns the one-shot, idempotent R-20 Phase 5 migration that rewrites the
 * three legacy global / baseUrl-keyed / sessionId-keyed categories to
 * per-serverGroupFp storage, plus the per-fp `cache_migration_v1_done_<fp>`
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
     * keyed / sessionId-keyed categories to per-serverGroupFp storage.
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
     * @param serverGroupFp the current host's fp (never blank — caller
     *   normalizes via `serverGroupFp.ifBlank { id }`).
     * @param legacyBaseUrl the current host's normalized baseUrl (used to
     *   locate the legacy `disabled_models_*` / `model_availability_*` slot
     *   for THIS server only — other URLs' data is left in place as orphan).
     */
    fun migrateLegacyKeysToFp(serverGroupFp: String, legacyBaseUrl: String) {
        if (serverGroupFp.isBlank()) return
        val flagKey = migrationFlagKey(serverGroupFp)
        if (encryptedPrefs.getBoolean(flagKey, false)) return

        val e = encryptedPrefs.edit()

        // ── 1) recent_workdirs (global) → recent_workdirs_<fp> ───────────────
        // Only copy if the fp slot is empty (defensive: never overwrite a
        // value that a prior partial migration wrote).
        val legacyWorkdirsJson = encryptedPrefs.getString(WorkdirPrefs.KEY_RECENT_WORKDIRS, null)
        if (legacyWorkdirsJson != null &&
            !encryptedPrefs.contains(WorkdirPrefs.recentWorkdirsKey(serverGroupFp))
        ) {
            e.putString(WorkdirPrefs.recentWorkdirsKey(serverGroupFp), legacyWorkdirsJson)
        }

        // ── 2) disabled_models / model_availability (per-baseUrl) → per-fp ──
        // Locate the legacy slots for THIS server's baseUrl. Other URLs' data
        // stays orphaned (multi-host users would need to migrate each host's
        // data on first cold-start of that host).
        if (!legacyBaseUrl.isBlank()) {
            val legacyDisabledKey = disabledModelsLegacyKey(legacyBaseUrl)
            val legacyAvailabilityKey = modelAvailabilityLegacyKey(legacyBaseUrl)
            if (!encryptedPrefs.contains(ModelPrefs.disabledModelsKey(serverGroupFp))) {
                encryptedPrefs.getStringSet(legacyDisabledKey, null)?.let {
                    e.putStringSet(ModelPrefs.disabledModelsKey(serverGroupFp), it)
                }
            }
            if (!encryptedPrefs.contains(ModelPrefs.modelAvailabilityKey(serverGroupFp))) {
                encryptedPrefs.getStringSet(legacyAvailabilityKey, null)?.let {
                    e.putStringSet(ModelPrefs.modelAvailabilityKey(serverGroupFp), it)
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
        rewriteSessionMapLegacyToFp(SessionPrefs.KEY_SESSION_DRAFTS, serverGroupFp, e)

        e.putBoolean(flagKey, true)
        e.apply()
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
        serverGroupFp: String,
        editor: SharedPreferences.Editor,
    ) {
        val json = encryptedPrefs.getString(key, null) ?: return
        val map: MutableMap<String, String> = try {
            Json.decodeFromString<Map<String, String>>(json).toMutableMap()
        } catch (e: Exception) {
            return
        }
        val prefix = serverGroupFp + SessionPrefs.COMPOSITE_KEY_SEPARATOR
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
        /** R-20 Phase 5: per-fp migration flag (idempotency). */
        private fun migrationFlagKey(serverGroupFp: String): String =
            "cache_migration_v1_done_$serverGroupFp"

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
