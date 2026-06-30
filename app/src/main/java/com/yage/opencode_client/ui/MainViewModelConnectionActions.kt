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
    state: MutableStateFlow<AppState>,
    connectionFlow: MutableStateFlow<ConnectionState>,
    settingsFlow: MutableStateFlow<SettingsState>
) {
    val currentProfile = hostProfileStore.currentProfile()
    val password = currentProfile.basicAuth?.passwordId?.let { settingsManager.basicAuthPassword(it) }
    repository.configure(
        baseUrl = currentProfile.serverUrl,
        username = currentProfile.basicAuth?.username,
        password = password,
        allowInsecureConnections = currentProfile.allowInsecureConnections
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
            openSessionIds = settingsManager.openSessionIds,
            // Seed sessions from the persisted metadata cache so tabs/title/
            // workdir groups render instantly on cold start (before the server
            // list loads). loadSessions replaces these with authoritative data.
            sessions = settingsManager.sessionCache.map { entry -> entry.toSession() }
            // §R-17 M2: connectionPhase moved to connectionFlow below.
            // §R-17 M3: selectedAgentName/themeMode/markdownFontSizes moved to
            // settingsFlow below.
        )
    }
    // §R-17 M3 (RFC §4 strategy A): seed the settings slice + mirror from
    // persisted prefs. Runs synchronously alongside the _state.update above;
    // intermediate state legal.
    val seedAgent = settingsManager.selectedAgentName ?: "build"
    applySettingsSlice(state, settingsFlow) {
        it.copy(
            selectedAgentName = seedAgent,
            themeMode = settingsManager.themeMode,
            markdownFontSizes = settingsManager.markdownFontSizes
        )
    }
    // §R-17 M2 (RFC §4 strategy A): write the connection slice AND mirror it
    // onto the deprecated AppState field synchronously (MainViewModel.state is
    // a direct view over `state`; without this mirror the legacy
    // `state.value.connectionPhase` readers — incl. reflection-based tests —
    // would not observe the change until a coroutine dispatch). The two
    // writes are back-to-back on the same (Main.immediate) thread; the
    // intermediate state is legal.
    // Signal "reconnecting" immediately when a profile is configured so the
    // empty-state UX can show a spinner instead of the bare connect button
    // while coldStartReconnect() is in flight.
    val connectionPhase = if (profiles.isNotEmpty()) "reconnecting" else null
    connectionFlow.update { it.copy(connectionPhase = connectionPhase) }
    @Suppress("DEPRECATION")
    state.update { it.copy(connectionPhase = connectionPhase) }
}

/**
 * §R-17 M2: this helper is currently DEAD CODE (no callers in main or test).
 *
 * M2 does NOT use combine() for `MainViewModel.state` — `state` is
 * `_state.asStateFlow()` and the deprecated AppState connection fields are
 * kept in sync with the slice via `writeConnection` (which writes BOTH the
 * slice and `_state` mirror in one synchronous call). Because this helper
 * only writes the AppState mirror via the raw `state` parameter (it has no
 * access to `writeConnection` / the slice flow), reviving it as-is would
 * leave `MainViewModel._connectionFlow` stale while the mirror flickers —
 * the slice/mirror would drift apart.
 *
 * Do NOT revive without routing the connection-field writes through
 * `MainViewModel.writeConnection` (or equivalently accepting the slice flow
 * and mirroring back to `_state`). @Suppress("DEPRECATION") keeps the legacy
 * mirror writes warning-clean until the helper is deleted or rewritten.
 */
@Suppress("DEPRECATION")
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
