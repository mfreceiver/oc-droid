package com.yage.opencode_client.ui

import com.yage.opencode_client.data.model.toSession
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.data.repository.HostProfileStore
import com.yage.opencode_client.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal fun applySavedSettings(
    repository: OpenCodeRepository,
    settingsManager: SettingsManager,
    hostProfileStore: HostProfileStore,
    state: MutableStateFlow<AppState>,
    connectionFlow: MutableStateFlow<ConnectionState>,
    settingsFlow: MutableStateFlow<SettingsState>
, slices: SliceFlows? = null) {

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
    state.updateAndSync(slices) {
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
    @Suppress("DEPRECATION")
    state.updateAndSync(slices) { it.copy(connectionPhase = connectionPhase) }
}

/**
 * §R-17 Stage 1: launchConnectionTest (dead code, glm-1 N2) was REMOVED here.
 * It had zero callers in main or test and only wrote the AppState mirror
 * without slice sync — reviving it requires routing connection writes through
 * MainViewModel.writeConnection (or accepting a SliceFlows param and using
 * updateAndSync).
 */

