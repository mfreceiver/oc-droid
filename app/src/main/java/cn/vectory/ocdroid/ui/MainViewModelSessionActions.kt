package cn.vectory.ocdroid.ui

/**
 * §R-17 batch3d: Domain orchestration free functions. These are NOT the deleted
 * batch-2 AppState mirror helpers (aggregateFromSlices/syncSlicesFromAppState etc.).
 * They are coroutine-launch helpers called by the domain ViewModels and AppCore
 * orchestration extensions to perform async operations (load/refresh/mutate).
 * Future cleanup (batch3e+): may be inlined into individual VM private methods.
 */

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun launchLoadProviders(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    settingsFlow: MutableStateFlow<SettingsState>,
    onNonFatalError: (String, Throwable?) -> Unit
) {
    scope.launch {
        repository.getProviders()
            .onSuccess { providers ->
                // §R-17 M3 / batch2 step d: providers lives on the settings
                // slice — written directly via thread-safe update.
                settingsFlow.update { it.copy(providers = providers) }
            }
            .onFailure { error ->
                onNonFatalError("Failed to load providers", error)
            }
    }
}
