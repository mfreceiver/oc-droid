package cn.vectory.ocdroid.ui

/**
 * §R-17 batch3d: Domain orchestration free functions. These are NOT the deleted
 * batch-2 AppState mirror helpers (aggregateFromSlices/syncSlicesFromAppState etc.).
 * They are coroutine-launch helpers called by the domain ViewModels and AppCore
 * orchestration extensions to perform async operations (load/refresh/mutate).
 * Future cleanup (batch3e+): may be inlined into individual VM private methods.
 */

import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun launchLoadProviders(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    settingsManager: SettingsManager,
    hostProfileStore: HostProfileStore,
    onNonFatalError: (String, Throwable?) -> Unit,
    /**
     * §需求4 host/fp guard (mirrors launchLoadMessages expectedServerGroupFp):
     * the serverGroupFp captured AT CALL TIME (when the REST request was
     * initiated). Compared against [currentServerGroupFp] at onSuccess — a
     * mismatch means the user switched host during the async REST call, so
     * the stale response from the OLD host must NOT be written into the NEW
     * host's persisted state (lost/cross data). Default "" → guard is a
     * no-op (both sides "" → equal), preserving backward compat for tests /
     * legacy callers.
     */
    expectedServerGroupFp: String = "",
    /**
     * §需求4 host/fp guard: provider for the CURRENT host's serverGroupFp,
     * read at onSuccess time. See [expectedServerGroupFp].
     */
    currentServerGroupFp: () -> String = { "" },
) {
    scope.launch {
        repository.getProviders()
            .onSuccess { providers ->
                // §需求4 host/fp guard: if the user switched host during the
                // REST call, expectedServerGroupFp != currentServerGroupFp() —
                // stale host response dropped (no write to the new host's
                // persisted state).
                if (expectedServerGroupFp != currentServerGroupFp()) return@onSuccess
                // §R-17 M3 / batch2 step d: providers lives on the settings
                // slice — written directly via thread-safe update.
                //
                // §bug5 / R-20 Phase 5 / §需求4: also reconcile the per-
                // serverGroupFp model data against the freshly-fetched catalog
                // so disable status is inherited ONLY for models still present
                // on the server (was per-baseUrl before Phase 5):
                //  1. Build the new availability set (`"$providerId/$modelId"`).
                //  2. Atomically (§需求4: single monitor-held RMW via
                //     [SettingsManager.reconcileModelData] →
                //     [ModelPrefs.reconcileModelData], so a concurrent manual
                //     model toggle cannot lose its update against this RMW):
                //     read the previously-persisted disabled set, intersect
                //     with the new availability (drop disabled entries that no
                //     longer exist server-side), persist availability + the
                //     trimmed disabled set, return the inherited disabled set.
                //  3. Update the in-memory slice's disabledModels atomically
                //     with providers so the UI stays consistent.
                //
                // §需求4: single read of currentProfile() — closes the TOCTOU
                // where two reads (one for .serverGroupFp, one for .id fallback)
                // could observe different profiles if the user switched host
                // between them.
                val profile = hostProfileStore.currentProfile()
                val fp = profile.serverGroupFp.ifBlank { profile.id }
                val newAvailableKeys = buildSet {
                    providers.providers.forEach { provider ->
                        provider.models.keys.forEach { modelId ->
                            add("${provider.id}/$modelId")
                        }
                    }
                }
                val inheritedDisabled = settingsManager.reconcileModelData(fp, newAvailableKeys)
                slices.mutateSettings {
                    it.copy(providers = providers, disabledModels = inheritedDisabled)
                }
            }
            .onFailure { error ->
                onNonFatalError("Failed to load providers", error)
            }
    }
}
