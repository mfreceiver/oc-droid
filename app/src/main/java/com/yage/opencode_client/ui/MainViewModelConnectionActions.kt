package com.yage.opencode_client.ui

import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.data.repository.HostProfileStore
import com.yage.opencode_client.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun applySavedSettings(
    repository: OpenCodeRepository,
    settingsManager: SettingsManager,
    hostProfileStore: HostProfileStore,
    state: MutableStateFlow<AppState>
) {
    val currentProfile = hostProfileStore.currentProfile()
    val password = currentProfile.basicAuth?.passwordId?.let { settingsManager.basicAuthPassword(it) }
    repository.configure(
        baseUrl = currentProfile.serverUrl,
        username = currentProfile.basicAuth?.username,
        password = password
    )

    state.update {
        it.copy(
            currentSessionId = settingsManager.currentSessionId,
            hostProfiles = hostProfileStore.profiles(),
            currentHostProfileId = currentProfile.id,
            selectedAgentName = settingsManager.selectedAgentName ?: "build",
            themeMode = settingsManager.themeMode,
            languageMode = settingsManager.languageMode,
            markdownFontSizes = settingsManager.markdownFontSizes,
            openSessionIds = settingsManager.openSessionIds
        )
    }
}

internal fun launchConnectionTest(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    onHealthyConnection: () -> Unit
) {
    scope.launch {
        state.update { it.copy(isConnecting = true, error = null) }
        repository.checkHealth()
            .onSuccess { health ->
                state.update {
                    it.copy(
                        isConnected = health.healthy,
                        serverVersion = health.version,
                        isConnecting = false
                    )
                }
                if (health.healthy) {
                    onHealthyConnection()
                }
            }
            .onFailure { error ->
                state.update {
                    it.copy(
                        isConnected = false,
                        isConnecting = false,
                        error = errorMessageOrFallback(error, "Connection failed")
                    )
                }
            }
    }
}
