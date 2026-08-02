package cn.vectory.ocdroid.util

import android.content.SharedPreferences

/**
 * L4b domain split of [SettingsManager] — MODEL-MANAGEMENT domain.
 *
 * Owns the per-(profileId) model-availability catalog and disabled-model
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
     * §model-selection / R-20 Phase 5: per-profileId disabled-model set.
     * Models the user has unchecked in Settings → Model management; those
     * entries are hidden from the chat quick-switch picker. Storage key
     * format: `disabled_models_<profileId>` (was `disabled_models_<normalizedBaseUrl>`
     * before Phase 5 — the URL dimension could not distinguish two profiles
     * reaching the same URL but treated as separate caches, and leaked
     * across identities sharing a URL). Stored as a StringSet whose entries
     * are `"$providerId/$modelId"`.
     *
     * Plan §3 Phase 5: legacy `disabled_models_<normalizedBaseUrl>` is migrated
     * to `disabled_models_<fp>` once per fp by
     * [MigrationHelper.migrateLegacyKeysToFp] (idempotent).
     */
    @Synchronized
    fun getDisabledModels(profileId: String): Set<String> {
        return encryptedPrefs.getStringSet(disabledModelsKey(profileId), emptySet()) ?: emptySet()
    }

    /**
     * §model-selection: toggle a single model's disabled flag for
     * [profileId]. [providerId]/[modelId] form the entry key
     * `"$providerId/$modelId"`.
     */
    @Synchronized
    fun setModelDisabled(profileId: String, providerId: String, modelId: String, disabled: Boolean) {
        val key = disabledModelsKey(profileId)
        val current = (encryptedPrefs.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
        val entry = "$providerId/$modelId"
        if (disabled) current.add(entry) else current.remove(entry)
        encryptedPrefs.edit().putStringSet(key, current).apply()
    }

    /**
     * §bug5: bulk replace the disabled set for a profileId (used by manual
     * refresh inherit so we don't issue N incremental writes). Entries are
     * `"$providerId/$modelId"`.
     */
    @Synchronized
    fun setDisabledModels(profileId: String, disabledKeys: Set<String>) {
        encryptedPrefs.edit().putStringSet(disabledModelsKey(profileId), disabledKeys).apply()
    }

    // §bug5: per-profileId model availability catalog (server-fetched full
    // set) so that manual refresh can inherit disable status only for models
    // still present.
    @Synchronized
    fun getModelAvailability(profileId: String): Set<String> {
        return encryptedPrefs.getStringSet(modelAvailabilityKey(profileId), emptySet()) ?: emptySet()
    }

    @Synchronized
    fun setModelAvailability(profileId: String, availableKeys: Set<String>) {
        encryptedPrefs.edit().putStringSet(modelAvailabilityKey(profileId), availableKeys).apply()
    }

    /**
     * R-20 Phase 5: clear ALL per-profileId model data (availability +
     * disabled) — used on异组 host switch / server-profile deletion so stale
     * data does not leak across identities. Replaces the legacy
     * `clearModelDataForUrl(baseUrl)` (URL was the wrong dimension: two
     * profiles with same URL but different group would clobber each other).
     */
    @Synchronized
    fun clearModelDataForGroup(profileId: String) {
        encryptedPrefs.edit()
            .remove(modelAvailabilityKey(profileId))
            .remove(disabledModelsKey(profileId))
            .apply()
    }

    /**
     * §需求4: atomic read-compute-write of per-fp model data. Holds the ModelPrefs
     * monitor across getDisabledModels → intersect → setModelAvailability +
     * setDisabledModels so a concurrent [setModelDisabled] manual toggle cannot
     * interleave and lose its update. Returns the inherited (intersected) disabled
     * set so the caller can mirror it into the in-memory settings slice.
     *
     * Mirrors the old inline logic in launchLoadProviders but serialized.
     */
    @Synchronized
    fun reconcileModelData(profileId: String, availableKeys: Set<String>): Set<String> {
        val oldDisabled = getDisabledModels(profileId)
        val inheritedDisabled = oldDisabled.intersect(availableKeys)
        setModelAvailability(profileId, availableKeys)
        setDisabledModels(profileId, inheritedDisabled)
        return inheritedDisabled
    }

    companion object {
        /** R-20 Phase 5: per-fp disabled-models key (replaces the legacy
         *  baseUrl-keyed slot). */
        internal fun disabledModelsKey(profileId: String): String =
            "disabled_models_$profileId"

        /** R-20 Phase 5: per-fp model-availability key. */
        internal fun modelAvailabilityKey(profileId: String): String =
            "model_availability_$profileId"
    }
}
