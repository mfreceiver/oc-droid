package com.yage.opencode_client.ui

import com.yage.opencode_client.data.model.toSession
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
    // Restore the last connected workdir so the repository is re-scoped to the
    // same project on cold start (currentDirectory is otherwise in-memory only
    // and the connected project would "vanish" after restart).
    settingsManager.currentWorkdir?.let { repository.setCurrentDirectory(it) }

    // Reuse the profile list once: it backs both AppState.hostProfiles and the
    // cold-start connectionPhase decision below.
    val profiles = hostProfileStore.profiles()
    state.update {
        it.copy(
            currentSessionId = settingsManager.currentSessionId,
            lastNavPage = settingsManager.lastNavPage,
            hostProfiles = profiles,
            currentHostProfileId = currentProfile.id,
            selectedAgentName = settingsManager.selectedAgentName ?: "build",
            themeMode = settingsManager.themeMode,
            languageMode = settingsManager.languageMode,
            markdownFontSizes = settingsManager.markdownFontSizes,
            openSessionIds = settingsManager.openSessionIds,
            // Seed sessions from the persisted metadata cache so tabs/title/
            // workdir groups render instantly on cold start (before the server
            // list loads). loadSessions replaces these with authoritative data.
            sessions = settingsManager.sessionCache.map { entry -> entry.toSession() },
            // Signal "reconnecting" immediately when a profile is configured so
            // the empty-state UX can show a spinner instead of the bare connect
            // button while coldStartReconnect() is in flight.
            connectionPhase = if (profiles.isNotEmpty()) "reconnecting" else null
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
