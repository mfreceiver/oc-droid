package com.yage.opencode_client.ui.controller

import android.util.Log
import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.data.repository.HostProfileStore
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.ComposerState
import com.yage.opencode_client.ui.ConnectionFormSettings
import com.yage.opencode_client.ui.ConnectionState
import com.yage.opencode_client.ui.FileState
import com.yage.opencode_client.ui.SettingsState
import com.yage.opencode_client.ui.SliceFlows
import com.yage.opencode_client.ui.TrafficState
import com.yage.opencode_client.ui.TunnelActivationState
import com.yage.opencode_client.ui.TUNNEL_SUCCESS_TOAST
import com.yage.opencode_client.ui.errorMessageOrFallback
import com.yage.opencode_client.ui.updateAndSync
import com.yage.opencode_client.ui.util.HttpImageHolder
import com.yage.opencode_client.util.DebugLog
import com.yage.opencode_client.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * R-16 M3: callbacks the [HostProfileController] invokes back into MainViewModel
 * for SSE lifecycle, connection testing, and cross-controller coordination.
 *
 * Defined as an interface rather than direct injection so the controller never
 * holds a reference to MainViewModel — avoiding the circular dependency flagged
 * in R-16 §7.3 (Controller ← MainViewModel that owns it).
 *
 * These methods are the orchestration points that belong to ConnectionCoordinator
 * (M4) or SessionSwitcher (M2b) and cannot yet be held directly by this controller.
 * The `testConnection` flow itself stays in MainViewModel until M4 extracts it
 * into ConnectionCoordinator.
 */
interface HostProfileCallbacks {
    /**
     * Cancels the in-flight SSE feed + resets the foreground catch-up state
     * machine. Called BEFORE repository.configure so stale events from the
     * previous host don't pollute AppState during the new probe.
     */
    fun cancelSseForReconfigure()

    /** Starts the SSE event collection feed. */
    fun startSSE()

    /**
     * Forces a health-check reconnect (bypasses the 30s throttle).
     * Already satisfied by [ForegroundCatchUpCallbacks.forceReconnect] —
     * both interfaces declare the same method, so a single override works.
     */
    fun forceReconnect()

    /**
     * Force reconnect with up to 3 retries (cold start). Called from
     * resetLocalDataAndResync.
     */
    fun coldStartReconnect()

    /**
     * Loads initial data (sessions/agents/providers/commands/questions/directory
     * sessions). Called from testConnection's success path (via the callback
     * chain in MainViewModel).
     */
    fun loadInitialData()

    /** Drops the per-session message-window cache (owned by SessionSwitcher). */
    fun clearSessionWindowCache()

    /** Zeros the in-memory traffic tracker. */
    fun resetTrafficTracker()
}

/**
 * R-16 M3: owns Host Profile CRUD + repository reconfiguration + tunnel
 * activation + full local-data reset.
 *
 * **Moved from MainViewModel:**
 *  - `selectHostProfile` / `deleteHostProfile` — profile switching with full
 *    per-host state purge (sessions/messages/unread/draft/cache/commands).
 *  - `saveHostProfile` / `duplicateHostProfile` / `importHostProfile` /
 *    `exportHostProfile` — profile CRUD + three-state password contract.
 *  - `configureServer` / `configureRepositoryForProfile` — repository
 *    reconfiguration with SSL allowInsecure wire (R-01).
 *  - `activateTunnelForCurrentHost` — tunnel activation state machine.
 *  - `resetLocalDataAndResync` — full local-data wipe + reconnect.
 *  - `getHostProfiles` / `currentHostProfile` / `getSavedConnectionSettings` /
 *    `refreshHostProfileState` — accessors.
 *
 * **Constructor params:** holds `HostProfileStore`, `OpenCodeRepository`, and
 * `SettingsManager` directly (per RFC §D — these are plain data/service classes,
 * not MainViewModel). Side effects that need MainViewModel orchestration (SSE
 * lifecycle, testConnection, sessionWindowCache, trafficTracker) flow through
 * [HostProfileCallbacks].
 *
 * All state writes go through `state.updateAndSync(slices)` which is
 * functionally equivalent to MainViewModel's `updateState` (writes AppState +
 * syncs all nine slices — MutableStateFlow suppresses equal-value emissions so
 * only changed slices notify subscribers).
 *
 * RFC reference: R-16 §D / §M3. Zero behaviour change.
 */
@Suppress("DEPRECATION")
internal class HostProfileController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<AppState>,
    private val slices: SliceFlows,
    private val hostProfileStore: HostProfileStore,
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val callbacks: HostProfileCallbacks
) {
    // ── Public accessors ───────────────────────────────────────────────────

    fun getHostProfiles(): List<HostProfile> = hostProfileStore.profiles()

    fun currentHostProfile(): HostProfile = hostProfileStore.currentProfile()

    fun getSavedConnectionSettings(): ConnectionFormSettings = ConnectionFormSettings(
        serverUrl = settingsManager.serverUrl,
        username = settingsManager.username ?: "",
        password = settingsManager.password ?: ""
    )

    // ── State sync helper ──────────────────────────────────────────────────

    /** Writes AppState + syncs all slices (equivalent to MainViewModel.updateState). */
    private fun updateState(transform: (AppState) -> AppState) {
        state.updateAndSync(slices, transform)
    }

    /** Updates host-profile list + current id on AppState + syncs slices. */
    internal fun refreshHostProfileState() {
        updateState {
            it.copy(
                hostProfiles = hostProfileStore.profiles(),
                currentHostProfileId = hostProfileStore.currentProfile().id
            )
        }
    }

    // ── Profile CRUD ───────────────────────────────────────────────────────

    /**
     * Persists [profile] and conditionally writes/clears the Basic Auth and
     * tunnel passwords according to the explicit three-state contract (Fix #5):
     *
     *  - [basicAuthEdited] = true  → write [basicAuthPassword] (blank removes).
     *  - [basicAuthEdited] = false → skip (preserve stored value).
     *  - [tunnelEdited] / [tunnelPassword] follow the same rule.
     *
     * When basicAuth is null, the orphaned password is always cleared.
     */
    fun saveHostProfile(
        profile: HostProfile,
        basicAuthPassword: String = "",
        basicAuthEdited: Boolean = false,
        tunnelPassword: String = "",
        tunnelEdited: Boolean = false
    ) {
        val normalized = if (profile.basicAuth != null) {
            profile.copy(basicAuth = profile.basicAuth.copy(passwordId = profile.id))
        } else {
            profile
        }
        // #12: snapshot the previous profile (before save) so we can detect
        // whether the allowInsecure toggle of the ACTIVE host changed — that
        // is the case that needs a live repository reconfigure + reconnect.
        // Without this, editing the current host's "不安全连接" persisted the
        // flag but left the existing REST/SSE OkHttp clients on the old
        // (SystemDefault) SSL config, so the toggle only took effect after a
        // host switch or app restart.
        val previous = hostProfileStore.profiles().firstOrNull { it.id == normalized.id }
        if (basicAuthEdited) {
            settingsManager.setBasicAuthPassword(normalized.id, basicAuthPassword)
        }
        if (tunnelEdited) {
            settingsManager.setTunnelPassword(normalized.id, tunnelPassword)
        }
        // Defense-in-depth (#5): a profile with no basicAuth config should
        // never retain an orphaned password.
        if (normalized.basicAuth == null) {
            settingsManager.setBasicAuthPassword(normalized.id, "")
        }
        hostProfileStore.save(normalized)
        refreshHostProfileState()

        // #12: if the saved profile is the currently active host AND its
        // allowInsecureConnections flag actually changed, reconfigure the
        // live repository clients (REST / SSE / image) and force a reconnect
        // so the new TLS trust policy takes effect immediately. Mirrors the
        // reconfigure+reconnect path used by selectHostProfile /
        // deleteHostProfile(wasCurrent). Non-current hosts and edits that do
        // not touch the toggle are left untouched (zero regression — the
        // toggle-OFF / unchanged case behaves exactly as before).
        val isActiveHost = normalized.id == slices.host.value.currentHostProfileId
        val toggleChanged = previous?.allowInsecureConnections != normalized.allowInsecureConnections
        if (isActiveHost && toggleChanged) {
            configureRepositoryForProfile(normalized)
            callbacks.forceReconnect()
        }
    }

    fun duplicateHostProfile(profileId: String) {
        hostProfileStore.duplicate(profileId)
        refreshHostProfileState()
    }

    /**
     * Detects deletion of the ACTIVE host: the replacement current host is
     * unrelated, so all per-host session/workdir state must be purged
     * (mirrors selectHostProfile). Otherwise just removes the profile entry.
     */
    fun deleteHostProfile(profileId: String) {
        val wasCurrent = profileId == slices.host.value.currentHostProfileId
        hostProfileStore.delete(profileId)
        val current = hostProfileStore.currentProfile()
        configureRepositoryForProfile(current)
        refreshHostProfileState()
        if (wasCurrent) {
            purgePerHostState()
            callbacks.forceReconnect()
        }
    }

    fun importHostProfile(payload: String): Result<HostProfile> = runCatching {
        hostProfileStore.importJson(payload).also { refreshHostProfileState() }
    }

    fun exportHostProfile(profile: HostProfile): String = hostProfileStore.exportJson(profile)

    // ── Profile selection (host switch) ────────────────────────────────────

    /**
     * Switches to the host profile [profileId], fully resetting all per-host
     * state (sessions/messages/unread/draft/cache/commands) and reconnecting
     * to the new host.
     *
     * The purge + reconfigure + testConnection sequence is the same as
     * deleteHostProfile(wasCurrent) — extracted into [purgePerHostState].
     */
    fun selectHostProfile(profileId: String) {
        scope.launch {
            val profile = hostProfileStore.select(profileId)
            purgePerHostState()
            configureRepositoryForProfile(profile)
            refreshHostProfileState()
            callbacks.forceReconnect()
        }
    }

    /**
     * Shared helper: purges ALL per-host session/message/unread/draft/cache
     * state. Used by both selectHostProfile and deleteHostProfile(wasCurrent).
     * The new host's sessions are unrelated to the previous one, so stale
     * open tabs, unread markers, draft workdirs, session cache, cached message
     * windows, and server version must not leak across hosts.
     */
    private fun purgePerHostState() {
        updateState {
            it.copy(
                currentSessionId = null,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                sessionStatuses = emptyMap(),
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null,
                sessionTodos = emptyMap(),
                openSessionIds = emptyList(),
                unreadSessions = emptySet(),
                tempClearedUnread = emptySet(),
                lastViewedTime = emptyMap(),
                sessions = emptyList(),
                directorySessions = emptyMap()
            )
        }
        // Reset the composer + settings slices that belong to the previous host.
        updateState {
            it.copy(
                draftWorkdir = null,
                availableCommands = emptyList(),
                serverVersion = null
            )
        }
        // Drop per-session message cache (belongs to previous host's sessions).
        callbacks.clearSessionWindowCache()
        settingsManager.currentSessionId = null
        settingsManager.openSessionIds = emptyList()
        settingsManager.sessionCache = emptyList()
        // §H3: clear persisted workdir — a path from host A is meaningless on
        // host B. configureRepositoryForProfile re-scopes to the (now-null)
        // workdir, which is correct for a fresh host.
        settingsManager.currentWorkdir = null
    }

    // ── Repository reconfiguration ────────────────────────────────────────

    /**
     * Reconfigures the repository for manual server URL/credential entry (the
     * "direct connection" path from the login form, NOT a profile switch).
     *
     * §Stage D: cancels in-flight SSE BEFORE repository.configure so events
     * from the previous credential/host don't land in AppState during the new
     * probe. R-01: passes `allowInsecureConnections` from the current profile.
     */
    fun configureServer(url: String, username: String? = null, password: String? = null) {
        callbacks.cancelSseForReconfigure()
        settingsManager.serverUrl = url
        settingsManager.username = username
        settingsManager.password = password
        val allowInsecure = currentHostProfile().allowInsecureConnections
        repository.configure(
            url, username, password,
            allowInsecureConnections = allowInsecure
        )
        // #12: mirror the host's TLS trust policy into the markdown image
        // client (same as configureRepositoryForProfile).
        HttpImageHolder.updateSsl(allowInsecure)
    }

    /**
     * Reconfigures the repository for a [profile]: cancels SSE, configures the
     * URL/credentials with the profile's allowInsecureConnections flag (R-01),
     * and restores the persisted workdir.
     *
     * §Stage D (gpter 阻塞 #1): this is the single authoritative SSE
     * cancellation point for all profile-based reconfigure paths
     * (selectHostProfile / deleteHostProfile / testConnection).
     * §H2: repository.configure() resets currentDirectory to null, so the
     * persisted workdir is restored afterwards.
     */
    internal fun configureRepositoryForProfile(profile: HostProfile) {
        callbacks.cancelSseForReconfigure()
        val password = profile.basicAuth?.passwordId?.let { settingsManager.basicAuthPassword(it) }
        repository.configure(
            profile.serverUrl, profile.basicAuth?.username, password,
            allowInsecureConnections = profile.allowInsecureConnections
        )
        // #12: keep the markdown image HTTP client's TLS trust policy in sync
        // with the active host so self-signed HTTPS images load under the
        // trust-all toggle (same entry point as REST / SSE).
        HttpImageHolder.updateSsl(profile.allowInsecureConnections)
        // §H2: restore persisted workdir after repository.configure resets it.
        settingsManager.currentWorkdir?.let { repository.setCurrentDirectory(it) }
    }

    // ── Tunnel activation ──────────────────────────────────────────────────

    /**
     * Activates the tunnel for the current host profile. Surfaces
     * loading/error/success state through `tunnelActivationState` on the
     * connection slice + error on AppState. R-01: passes
     * `allowInsecureConnections` from the profile.
     */
    fun activateTunnelForCurrentHost() {
        val profile = hostProfileStore.currentProfile()
        val passwordId = profile.tunnelPasswordId
        if (passwordId == null) {
            updateState {
                it.copy(error = "隧道激活失败：未设置隧道认证密码。请在「服务器」设置中填写隧道密码并保存后再试。")
            }
            updateState {
                it.copy(tunnelActivationState = TunnelActivationState.Error("未设置隧道密码"))
            }
            return
        }
        val password = settingsManager.getTunnelPassword(passwordId)
        if (password.isNullOrBlank()) {
            updateState {
                it.copy(error = "隧道激活失败：已配置密码标识但存储为空（可能保存时未输入）。请重新输入隧道密码并保存。")
            }
            updateState {
                it.copy(tunnelActivationState = TunnelActivationState.Error("隧道密码为空"))
            }
            return
        }

        updateState { it.copy(tunnelActivationState = TunnelActivationState.Loading) }
        scope.launch {
            repository.activateTunnel(
                profile.serverUrl, password,
                allowInsecure = profile.allowInsecureConnections
            )
                .onSuccess {
                    updateState { it.copy(error = TUNNEL_SUCCESS_TOAST) }
                    updateState { it.copy(tunnelActivationState = TunnelActivationState.Success) }
                    Log.d(TAG, "Tunnel activated successfully for ${profile.serverUrl}")
                }
                .onFailure { error ->
                    val msg = errorMessageOrFallback(error, "未知错误（无异常信息）")
                    updateState { it.copy(error = "隧道激活失败：$msg") }
                    updateState { it.copy(tunnelActivationState = TunnelActivationState.Error(msg)) }
                    Log.e(TAG, "Tunnel activation failed", error)
                }
        }
    }

    // ── Full local-data reset ──────────────────────────────────────────────

    /**
     * Hard reset of ALL local data, then reconnect + re-fetch from the server.
     *
     * Wipes everything persisted by SettingsManager EXCEPT server connection
     * info + tunnel passwords. Resets in-memory AppState to a clean slate
     * (preserving host profile list + current id), tears down SSE, resets all
     * slice flows, then reconnects via coldStartReconnect which re-runs
     * loadInitialData on a healthy connection.
     */
    fun resetLocalDataAndResync() {
        // 1. Wipe persisted local data (preserves connection + tunnel creds).
        settingsManager.clearAllLocalData()
        // 2. Zero the in-memory traffic tracker.
        callbacks.resetTrafficTracker()
        // 3. Drop the per-session message-window cache.
        callbacks.clearSessionWindowCache()
        // 4. Tear down SSE + reset catch-up flags.
        callbacks.cancelSseForReconfigure()
        // 5. Reset AppState to defaults, preserving host profile list + current id.
        val keptHostProfiles = slices.host.value.hostProfiles
        val keptHostProfileId = slices.host.value.currentHostProfileId
        updateState {
            AppState(
                hostProfiles = keptHostProfiles,
                currentHostProfileId = keptHostProfileId
            )
        }
        // 6. Reset the connection + traffic slices to "reconnecting / zeroed".
        updateState {
            it.copy(
                isConnected = false,
                isConnecting = true,
                serverVersion = null,
                connectionPhase = "reconnecting",
                tunnelActivationState = TunnelActivationState.Idle,
                trafficSent = 0L,
                trafficReceived = 0L
            )
        }
        // 7. Reset the composer/file/settings slices to defaults.
        updateState {
            it.copy(
                inputText = "",
                imageAttachments = emptyList(),
                sendingSessionIds = emptySet(),
                draftWorkdir = null,
                filePathToShowInFiles = null,
                filePreviewOriginRoute = null,
                fileBrowserOpen = false,
                fileBrowserWorkdir = null,
                themeMode = com.yage.opencode_client.util.ThemeMode.SYSTEM,
                markdownFontSizes = com.yage.opencode_client.util.MarkdownFontSizes(),
                selectedAgentName = "build",
                agents = emptyList(),
                providers = null,
                availableCommands = emptyList()
            )
        }
        // 8. Reconnect to the (preserved) current host profile and re-fetch.
        callbacks.coldStartReconnect()
    }

    companion object {
        private const val TAG = "HostProfileController"
    }
}
