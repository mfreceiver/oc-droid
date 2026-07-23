package cn.vectory.ocdroid.util

import android.content.SharedPreferences

/**
 * L4b domain split of [SettingsManager] — MODEL-MANAGEMENT domain.
 *
 * Owns the per-(serverGroupFp) model-availability catalog and disabled-model
 * set used by Settings → Model management and the chat quick-switch picker.
 *
 * §L4b ESP-key ownership: this class owns the per-fp key builders
 * [disabledModelsKey] / [modelAvailabilityKey]; the legacy baseUrl-keyed
 * builders (pre-Phase-5) live in [MigrationHelper] (their sole reader is
 * the one-shot migration). [clearModelDataForGroup] removes BOTH per-fp
 * keys atomically (异组 host switch / profile deletion hygiene).
 *
 * Behavior byte-identical to pre-split [SettingsManager]: same ESP
 * instance, same key strings, same StringSet `"$providerId/$modelId"`
 * entry encoding. NO key renames.
 */
internal class ModelPrefs(
    private val encryptedPrefs: SharedPreferences,
) {
    /**
     * §model-selection / R-20 Phase 5: per-serverGroupFp disabled-model set.
     * Models the user has unchecked in Settings → Model management; those
     * entries are hidden from the chat quick-switch picker. Storage key
     * format: `disabled_models_<serverGroupFp>` (was `disabled_models_<normalizedBaseUrl>`
     * before Phase 5 — the URL dimension could not distinguish two profiles
     * reaching the same URL but treated as separate caches, and leaked
     * across identities sharing a URL). Stored as a StringSet whose entries
     * are `"$providerId/$modelId"`.
     *
     * Plan §3 Phase 5: legacy `disabled_models_<normalizedBaseUrl>` is migrated
     * to `disabled_models_<fp>` once per fp by
     * [MigrationHelper.migrateLegacyKeysToFp] (idempotent).
     */
    fun getDisabledModels(serverGroupFp: String): Set<String> {
        return encryptedPrefs.getStringSet(disabledModelsKey(serverGroupFp), emptySet()) ?: emptySet()
    }

    /**
     * §model-selection: toggle a single model's disabled flag for
     * [serverGroupFp]. [providerId]/[modelId] form the entry key
     * `"$providerId/$modelId"`.
     */
    fun setModelDisabled(serverGroupFp: String, providerId: String, modelId: String, disabled: Boolean) {
        val key = disabledModelsKey(serverGroupFp)
        val current = (encryptedPrefs.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
        val entry = "$providerId/$modelId"
        if (disabled) current.add(entry) else current.remove(entry)
        encryptedPrefs.edit().putStringSet(key, current).apply()
    }

    /**
     * §bug5: bulk replace the disabled set for a serverGroupFp (used by manual
     * refresh inherit so we don't issue N incremental writes). Entries are
     * `"$providerId/$modelId"`.
     */
    fun setDisabledModels(serverGroupFp: String, disabledKeys: Set<String>) {
        encryptedPrefs.edit().putStringSet(disabledModelsKey(serverGroupFp), disabledKeys).apply()
    }

    // §bug5: per-serverGroupFp model availability catalog (server-fetched full
    // set) so that manual refresh can inherit disable status only for models
    // still present.
    fun getModelAvailability(serverGroupFp: String): Set<String> {
        return encryptedPrefs.getStringSet(modelAvailabilityKey(serverGroupFp), emptySet()) ?: emptySet()
    }

    fun setModelAvailability(serverGroupFp: String, availableKeys: Set<String>) {
        encryptedPrefs.edit().putStringSet(modelAvailabilityKey(serverGroupFp), availableKeys).apply()
    }

    /**
     * R-20 Phase 5: clear ALL per-serverGroupFp model data (availability +
     * disabled) — used on异组 host switch / server-profile deletion so stale
     * data does not leak across identities. Replaces the legacy
     * `clearModelDataForUrl(baseUrl)` (URL was the wrong dimension: two
     * profiles with same URL but different group would clobber each other).
     */
    fun clearModelDataForGroup(serverGroupFp: String) {
        encryptedPrefs.edit()
            .remove(modelAvailabilityKey(serverGroupFp))
            .remove(disabledModelsKey(serverGroupFp))
            .apply()
    }

    companion object {
        /** R-20 Phase 5: per-fp disabled-models key (replaces the legacy
         *  baseUrl-keyed slot). */
        internal fun disabledModelsKey(serverGroupFp: String): String =
            "disabled_models_$serverGroupFp"

        /** R-20 Phase 5: per-fp model-availability key. */
        internal fun modelAvailabilityKey(serverGroupFp: String): String =
            "model_availability_$serverGroupFp"
    }
}
