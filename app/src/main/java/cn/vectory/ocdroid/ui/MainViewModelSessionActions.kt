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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun launchLoadProviders(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    settingsFlow: MutableStateFlow<SettingsState>,
    settingsManager: SettingsManager,
    hostProfileStore: HostProfileStore,
    onNonFatalError: (String, Throwable?) -> Unit
) {
    scope.launch {
        repository.getProviders()
            .onSuccess { providers ->
                // §R-17 M3 / batch2 step d: providers lives on the settings
                // slice — written directly via thread-safe update.
                //
                // §bug5: also reconcile the per-URL model data against the
                // freshly-fetched catalog so disable status is inherited ONLY
                // for models still present on the server:
                //  1. Build the new availability set (`"$providerId/$modelId"`).
                //  2. Read the previously-persisted disabled set for this URL.
                //  3. Intersect: drop disabled entries that no longer exist on
                //     the server (so a model removed server-side stops being
                //     silently hidden if it later returns under the same key).
                //  4. Persist availability + (possibly trimmed) disabled set.
                //  5. Update the in-memory slice's disabledModels atomically
                //     with providers so the UI stays consistent.
                val baseUrl = hostProfileStore.currentProfile().serverUrl
                val newAvailableKeys = buildSet {
                    providers.providers.forEach { provider ->
                        provider.models.keys.forEach { modelId ->
                            add("${provider.id}/$modelId")
                        }
                    }
                }
                val oldDisabled = settingsManager.getDisabledModels(baseUrl)
                val inheritedDisabled = oldDisabled.intersect(newAvailableKeys)
                settingsManager.setModelAvailability(baseUrl, newAvailableKeys)
                settingsManager.setDisabledModels(baseUrl, inheritedDisabled)
                settingsFlow.update {
                    it.copy(providers = providers, disabledModels = inheritedDisabled)
                }
            }
            .onFailure { error ->
                onNonFatalError("Failed to load providers", error)
            }
    }
}
