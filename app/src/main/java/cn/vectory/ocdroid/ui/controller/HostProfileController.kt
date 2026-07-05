package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.ui.ConnectionFormSettings
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.FileState
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SettingsState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.TrafficState
import cn.vectory.ocdroid.ui.TunnelActivationState
import cn.vectory.ocdroid.ui.TUNNEL_SUCCESS_TOAST
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.UnreadState
import cn.vectory.ocdroid.ui.errorMessageOrFallback
import cn.vectory.ocdroid.ui.util.HttpImageHolder
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.TrafficTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * R-16 M3 → R-17 batch3b: owns Host Profile CRUD + repository reconfiguration
 * + tunnel activation + full local-data reset.
 *
 * **Migration (batch 3b)**: the [HostProfileCallbacks] interface was
 * eliminated. The 4 cross-domain signals (cancelSseForReconfigure /
 * forceReconnect / onHostProfileSwitched / coldStartReconnect) emit
 * [ControllerEffect]s on [effects] (rule B). The same-domain operations
 * (resetTrafficTracker, clearSessionWindowCache) reach their owners directly:
 * resetTrafficTracker inlines against the injected [trafficTracker];
 * clearSessionWindowCache routes via [ControllerEffect.ClearSessionWindowCache]
 * because SessionSwitcher is a sibling controller. The previously-injected
 * [cn.vectory.ocdroid.ui.EventEmitter] is replaced by [effects] — UiEvents
 * now ride [SharedEffectBus.uiEvents] (`effects.uiEvents.tryEmit(...)`).
 *
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
 * §R-17 batch2 step e final: all state writes go through the per-slice
 * `MutableStateFlow.update` helpers (slices are the sole authoritative store).
 *
 * RFC reference: R-16 §D / §M3. Zero behaviour change.
 */
@Suppress("DEPRECATION")
internal class HostProfileController(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
    private val hostProfileStore: HostProfileStore,
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val trafficTracker: TrafficTracker,
    private val effects: SharedEffectBus,
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

    /** Updates host-profile list + current id on the host slice. */
    internal fun refreshHostProfileState() {
        slices.host.update {
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
        // S-1: also detect a serverUrl change on the ACTIVE host — previously
        // editing the current host's URL persisted the new value but left the
        // existing clients pointed at the OLD endpoint, so the change only
        // took effect after a host switch / app restart. Treat it the same as
        // a toggle change: reconfigure + force reconnect to build clients for
        // the new URL.
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

        // #12 / S-1: if the saved profile is the currently active host AND
        // either its allowInsecureConnections flag OR its serverUrl actually
        // changed, reconfigure the live repository clients (REST / SSE /
        // image) and force a reconnect so the new TLS trust policy / endpoint
        // takes effect immediately. Mirrors the reconfigure+reconnect path
        // used by selectHostProfile / deleteHostProfile(wasCurrent). Non-
        // current hosts and edits that touch neither field are left untouched
        // (zero regression — the toggle-OFF / unchanged-URL case behaves
        // exactly as before).
        val isActiveHost = normalized.id == slices.host.value.currentHostProfileId
        val toggleChanged = previous?.allowInsecureConnections != normalized.allowInsecureConnections
        val urlChanged = previous?.serverUrl != normalized.serverUrl
        if (isActiveHost && urlChanged) {
            // §bug5: URL changed → deliberately drop old-URL model data so stale
            // disable config does not leak / orphan. The HostProfileSwitched
            // emission below reloads the (now-empty) set for the new URL.
            previous?.serverUrl?.let { settingsManager.clearModelDataForUrl(it) }
        }
        if (isActiveHost && (toggleChanged || urlChanged)) {
            configureRepositoryForProfile(normalized)
            effects.effects.tryEmit(ControllerEffect.ForceReconnect)
            // §disabled-models-consistency: disabled-models 等按 baseUrl 存储的 per-host
            // 状态在新 URL 生效后必须重新装载（与 selectHostProfile 路径对齐）。否则
            // 改 URL 后旧 baseUrl 的禁用集仍然显示，状态不一致。
            effects.effects.tryEmit(ControllerEffect.HostProfileSwitched)
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
        // §bug5: capture the deleted profile's URL before the store mutation so
        // we can purge its per-URL model data if it was the active host.
        val deletedServerUrl = hostProfileStore.profiles()
            .firstOrNull { it.id == profileId }?.serverUrl
        hostProfileStore.delete(profileId)
        val current = hostProfileStore.currentProfile()
        configureRepositoryForProfile(current)
        refreshHostProfileState()
        if (wasCurrent) {
            // §bug5: drop the deleted active host's model data so it does not
            // leak into the new active host's identity (same-URL collision or
            // later re-add of an identical URL).
            deletedServerUrl?.let { settingsManager.clearModelDataForUrl(it) }
            purgePerHostState()
            effects.effects.tryEmit(ControllerEffect.ForceReconnect)
            // §disabled-models-consistency: deleting the active host switches to
            // a different baseUrl — reload per-host state (same as selectHostProfile
            // and saveHostProfile urlChanged paths).
            effects.effects.tryEmit(ControllerEffect.HostProfileSwitched)
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
            effects.effects.tryEmit(ControllerEffect.ForceReconnect)
            // §host-switch-order: only AFTER select + reconnect have settled do
            // we hand control back for host-scoped post-processing. Doing this
            // synchronously in the caller raced the launch above and read the
            // PREVIOUS host's baseUrl.
            effects.effects.tryEmit(ControllerEffect.HostProfileSwitched)
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
        // §slice-only-preserve (glm-1 / gpt-1): ChatState carries three fields
        // that are NOT mirrored to AppState (isCompacting, compactStartedAt,
        // refreshNonce). Use .copy() on the existing slice value so those are
        // preserved (a fresh ChatState() would clobber them); only the AppState-
        // represented chat fields are reset here.
        slices.chat.update {
            it.copy(
                currentSessionId = null,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null
            )
        }
        slices.sessionList.update {
            it.copy(
                sessions = emptyList(),
                directorySessions = emptyMap(),
                openSessionIds = emptyList(),
                sessionStatuses = emptyMap(),
                sessionTodos = emptyMap()
            )
        }
        slices.unread.update {
            it.copy(
                unreadSessions = emptySet(),
                tempClearedUnread = emptySet(),
                lastViewedTime = emptyMap()
            )
        }
        // Reset the composer + settings slices that belong to the previous host.
        slices.composer.update { it.copy(draftWorkdir = null) }
        slices.settings.update { it.copy(availableCommands = emptyList()) }
        slices.connection.update { it.copy(serverVersion = null) }
        // Drop per-session message cache (belongs to previous host's sessions).
        effects.effects.tryEmit(ControllerEffect.ClearSessionWindowCache)
        settingsManager.currentSessionId = null
        settingsManager.openSessionIds = emptyList()
        settingsManager.sessionCache = emptyList()
        // §H3: clear persisted workdir — a path from host A is meaningless on
        // host B. configureRepositoryForProfile re-scopes to the (now-null)
        // workdir, which is correct for a fresh host.
        settingsManager.currentWorkdir = null
        // §recent-workdirs: clear per-host workdir memory too — a path from
        // host A is meaningless on host B (same rationale as currentWorkdir
        // above). loadInitialData on the new host re-seeds from scratch.
        settingsManager.recentWorkdirs = emptyList()
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
        val oldUrl = settingsManager.serverUrl
        val urlChanging = oldUrl != url
        if (urlChanging) {
            // §bug5: manual URL change also clears old model data so the disable
            // set does not orphan against an identity the user abandoned.
            // HostProfileSwitched below reloads the (now-empty) set for the new
            // URL — previously this path skipped the reload entirely, leaving
            // the in-memory slice stale.
            settingsManager.clearModelDataForUrl(oldUrl)
        }
        effects.effects.tryEmit(ControllerEffect.CancelSseForReconfigure)
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
        if (urlChanging) {
            effects.effects.tryEmit(ControllerEffect.HostProfileSwitched)
        }
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
        effects.effects.tryEmit(ControllerEffect.CancelSseForReconfigure)
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
     * connection slice + UiEvent.Error/Success via [effects.uiEvents].
     * R-01: passes `allowInsecureConnections` from the profile.
     */
    fun activateTunnelForCurrentHost() {
        val profile = hostProfileStore.currentProfile()
        val passwordId = profile.tunnelPasswordId
        if (passwordId == null) {
            slices.connection.update {
                it.copy(
                    tunnelActivationState = TunnelActivationState.Error("未设置隧道密码")
                )
            }
            effects.uiEvents.tryEmit(UiEvent.Error("隧道激活失败：未设置隧道认证密码。请在「服务器」设置中填写隧道密码并保存后再试。"))
            return
        }
        val password = settingsManager.getTunnelPassword(passwordId)
        if (password.isNullOrBlank()) {
            slices.connection.update {
                it.copy(
                    tunnelActivationState = TunnelActivationState.Error("隧道密码为空")
                )
            }
            effects.uiEvents.tryEmit(UiEvent.Error("隧道激活失败：已配置密码标识但存储为空（可能保存时未输入）。请重新输入隧道密码并保存。"))
            return
        }

        slices.connection.update { it.copy(tunnelActivationState = TunnelActivationState.Loading) }
        scope.launch {
            repository.activateTunnel(
                profile.serverUrl, password,
                allowInsecure = profile.allowInsecureConnections
            )
                .onSuccess {
                    slices.connection.update {
                        it.copy(
                            // §success-channel / §R-17 batch2: success now rides a
                            // UiEvent.Success (NOT error) so ChatScreen renders a
                            // success snackbar instead of "发生错误" + "查看". The
                            // sticky tunnelActivationState=Success still drives the
                            // ServerManagementDialog's success indicator.
                            tunnelActivationState = TunnelActivationState.Success
                        )
                    }
                    effects.uiEvents.tryEmit(UiEvent.Success(TUNNEL_SUCCESS_TOAST))
                    Log.d(TAG, "Tunnel activated successfully for ${profile.serverUrl}")
                    // §user-req: tunnel 激活后自动冷启动级刷新。1.5s 经验值——cloudflared
                    // 类守护进程在 activate API 返回后需要短暂时间建立路由。coldStartReconnect
                    // 自带 3 次退避重试（1/2/4s）兜底，即使首次探测失败也会在 ~7s 内成功。
                    delay(1500)
                    effects.effects.tryEmit(ControllerEffect.ColdStartReconnect)
                }
                .onFailure { error ->
                    val msg = errorMessageOrFallback(error, "未知错误（无异常信息）")
                    slices.connection.update {
                        it.copy(
                            tunnelActivationState = TunnelActivationState.Error(msg)
                        )
                    }
                    effects.uiEvents.tryEmit(UiEvent.Error("隧道激活失败：$msg"))
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
        // 2. Zero the in-memory traffic tracker (direct — same domain).
        trafficTracker.reset()
        // 3. Drop the per-session message-window cache (sibling controller).
        effects.effects.tryEmit(ControllerEffect.ClearSessionWindowCache)
        // 4. Tear down SSE + reset catch-up flags.
        effects.effects.tryEmit(ControllerEffect.CancelSseForReconfigure)
        // 5. Reset slices to defaults, preserving the host slice (kept above)
        //    and the chat slice's slice-only fields (isCompacting /
        //    compactStartedAt / refreshNonce — use .copy() so they survive).
        //    Equivalent to the pre-migration `AppState(hostProfiles,
        //    currentHostProfileId)` full-reset: every AppState-represented
        //    field returns to its default.
        slices.chat.update { c ->
            c.copy(
                currentSessionId = null,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null,
                olderMessagesCursor = null,
                hasMoreMessages = true,
                isLoadingMessages = false,
                gapInfo = null,
                staleNotice = false,
                currentModel = null
            )
        }
        slices.sessionList.update { SessionListState() }
        slices.unread.update { UnreadState() }
        // 6. Reset the connection + traffic slices to "reconnecting / zeroed".
        //    Defaults already cover tunnelActivationState=Idle; we override
        //    isConnecting + connectionPhase to signal the in-flight reconnect.
        slices.connection.update {
            ConnectionState(
                isConnecting = true,
                connectionPhase = "reconnecting"
            )
        }
        slices.traffic.update { TrafficState() }
        // 7. Reset the composer/file/settings slices to defaults.
        slices.composer.update { ComposerState() }
        slices.file.update { FileState() }
        slices.settings.update { SettingsState() }
        // 8. Reconnect to the (preserved) current host profile and re-fetch.
        effects.effects.tryEmit(ControllerEffect.ColdStartReconnect)
    }

    companion object {
        private const val TAG = "HostProfileController"
    }
}
