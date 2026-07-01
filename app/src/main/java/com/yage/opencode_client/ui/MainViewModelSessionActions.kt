package com.yage.opencode_client.ui

import com.yage.opencode_client.data.repository.OpenCodeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal fun launchLoadProviders(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    settingsFlow: MutableStateFlow<SettingsState>,
    onNonFatalError: (String, Throwable?) -> Unit
) {
    scope.launch {
        repository.getProviders()
            .onSuccess { providers ->
                // §R-17 M3: providers lives on the settings slice.
                applySettingsSlice(state, settingsFlow) { it.copy(providers = providers) }
            }
            .onFailure { error ->
                onNonFatalError("Failed to load providers", error)
            }
    }
}

