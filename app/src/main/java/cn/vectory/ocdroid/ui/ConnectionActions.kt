package cn.vectory.ocdroid.ui

/**
 * §R-17 batch3d: Domain orchestration free functions. These are NOT the deleted
 * batch-2 AppState mirror helpers (aggregateFromSlices/syncSlicesFromAppState etc.).
 * They are coroutine-launch helpers called by the domain ViewModels and AppCore
 * orchestration extensions to perform async operations (load/refresh/mutate).
 * Future cleanup (batch3e+): may be inlined into individual VM private methods.
 */

import cn.vectory.ocdroid.data.model.toSession
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.util.SettingsManager

internal fun applySavedSettings(
    repository: OpenCodeRepository,
    settingsManager: SettingsManager,
    hostProfileStore: HostProfileStore,
    slices: SliceFlows
) {

    val currentProfile = hostProfileStore.currentProfile()
    val password = currentProfile.basicAuth?.passwordId?.let { settingsManager.basicAuthPassword(it) }
    repository.configure(
        baseUrl = currentProfile.serverUrl,
        username = currentProfile.basicAuth?.username,
        password = password,
        allowInsecureConnections = currentProfile.allowInsecureConnections
    )
    // Restore the last connected workdir so the app re-scopes to the same
    // project on cold start. §R18 Phase 2-E step 2: the repository's global
    // currentDirectory was removed; the workdir is persisted in
    // settingsManager (below) and consumed explicitly by the directory-scoped
    // API methods (SSE / /question / /command / file routes).

    // Reuse the profile list once: it backs both the host slice and the
    // cold-start connectionPhase decision below.
    val profiles = hostProfileStore.profiles()
    // Seed sessions from the persisted metadata cache so tabs/title/
    // workdir groups render instantly on cold start (before the server
    // list loads). loadSessions replaces these with authoritative data.
    val restoredSessions = settingsManager.sessionCache.map { entry -> entry.toSession() }
    // Archived-session filtering: a cached entry may carry timeArchived if the
    // user archived it last run (or another client did, surfaced via SSE
    // session.updated before the process died). Without this filter the tab
    // strip would render an archived tab and the chat could restore onto an
    // archived session. Drop any openSessionId whose cached session is
    // archived, persist the cleaned list back via the existing setter, and
    // clear currentSessionId if it points at an archived session.
    val archivedIds = restoredSessions.filter { it.isArchived }.map { it.id }.toSet()
    val persistedOpenSessionIds = settingsManager.openSessionIds
    val restoredOpenSessionIds = persistedOpenSessionIds.filterNot { it in archivedIds }
    if (restoredOpenSessionIds != persistedOpenSessionIds) {
        settingsManager.openSessionIds = restoredOpenSessionIds
    }
    val persistedCurrentSessionId = settingsManager.currentSessionId
    val restoredCurrentSessionId = persistedCurrentSessionId?.let { cid ->
        if (cid in archivedIds) null else cid
    }
    // §R18 Phase 2-F: SettingsManager is no longer written here — the AppCore
    // init collector persists non-null chatFlow.currentSessionId changes, and
    // an archived-id filter (restoredCurrentSessionId == null when persisted
    // pointed at an archived session) is re-applied on every cold start via
    // this same read path, so leaving the stale archived id in SettingsManager
    // is self-healing across restarts. The chat.update below is the runtime
    // source of truth.
    // §R-17 batch2 step d → step e final: each domain written directly to its
    // own slice via thread-safe MutableStateFlow.update. The AppState mirror
    // is no longer written from here — slices are the sole authoritative
    // store.
    slices.mutateChat {
        it.copy(currentSessionId = restoredCurrentSessionId)
    }
    slices.mutateHost {
        it.copy(
            hostProfiles = profiles,
            currentHostProfileId = currentProfile.id
        )
    }
    slices.mutateSessionList {
        it.copy(
            openSessionIds = restoredOpenSessionIds,
            sessions = restoredSessions
        )
    }
    // §R-17 M3 (RFC §4 strategy A): seed the settings slice from persisted
    // prefs. Runs synchronously alongside the slice updates above; intermediate
    // state legal.
    val seedAgent = settingsManager.selectedAgentName ?: "build"
    // §model-selection: load per-baseUrl disabled-model set for the active
    // host so the chat quick-switch picker + Settings render the right
    // entries on cold start.
    val seedDisabledModels = settingsManager.getDisabledModels(currentProfile.serverUrl)
    slices.mutateSettings {
        it.copy(
            selectedAgentName = seedAgent,
            themeMode = settingsManager.themeMode,
            markdownFontSizes = settingsManager.markdownFontSizes,
            disabledModels = seedDisabledModels,
            // §ui-scale: seed the persisted UI scale factors so OpenCodeTheme
            // (subscribed via MainActivity → settingsFlow) applies them on
            // cold start. Updates thereafter flow through setUiFontScale /
            // setUiContentScale → writeSettings.
            uiFontScale = settingsManager.uiFontScale,
            uiContentScale = settingsManager.uiContentScale
        )
    }
    // §R-17 M2 (RFC §4 strategy A): write the connection slice directly.
    // Signal "reconnecting" immediately when a profile is configured so the
    // empty-state UX can show a spinner instead of the bare connect button
    // while coldStartReconnect() is in flight. §R18 Phase 2-I: phase is now a
    // sealed ConnectionPhase; Idle replaces the legacy null sentinel.
    val connectionPhase = if (profiles.isNotEmpty()) ConnectionPhase.Reconnecting else ConnectionPhase.Idle
    slices.mutateConnection { it.copy(connectionPhase = connectionPhase) }
}

/**
 * §R-17 batch3d: free-function extraction of the former
 * AppCore.reloadDisabledModelsForCurrentHost body. Both
 * [ComposerViewModel.reloadDisabledModelsForCurrentHost] and AppCore's
 * effect-dispatch handler (ControllerEffect.HostProfileSwitched) call this so
 * the body lives once.
 */
internal fun applyReloadDisabledModelsForCurrentHost(
    settingsManager: SettingsManager,
    hostProfileStore: HostProfileStore,
    slices: SliceFlows,
) {
    val baseUrl = hostProfileStore.currentProfile().serverUrl
    val set = settingsManager.getDisabledModels(baseUrl)
    slices.mutateSettings { it.copy(disabledModels = set) }
}
