package cn.vectory.ocdroid.ui

/**
 * §R-17 batch3d: Domain orchestration free functions. These are NOT the deleted
 * batch-2 AppState mirror helpers (aggregateFromSlices/syncSlicesFromAppState etc.).
 * They are coroutine-launch helpers called by the domain ViewModels and AppCore
 * orchestration extensions to perform async operations (load/refresh/mutate).
 * Future cleanup (batch3e+): may be inlined into individual VM private methods.
 */

import cn.vectory.ocdroid.data.model.toSession
import cn.vectory.ocdroid.data.model.RevertCutoff
import cn.vectory.ocdroid.data.model.RevertCutoffState
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.http.hostPortFromUrl
import cn.vectory.ocdroid.ui.settings.resolveMtlsDegradationMessage
import cn.vectory.ocdroid.ui.util.HttpImageHolder
import cn.vectory.ocdroid.util.SettingsManager

internal fun applySavedSettings(
    repository: OpenCodeRepository,
    settingsManager: SettingsManager,
    hostProfileStore: HostProfileStore,
    slices: SliceFlows
) {

    val currentProfile = hostProfileStore.currentProfile()
    // R-20 Phase 5 (plan §3, cold-start migration trigger): migrate the
    // legacy global / baseUrl-keyed / sessionId-keyed storage slots to the
    // current host's fp-keyed slots. Idempotent per fp via the
    // `cache_migration_v1_done_<fp>` flag in EncryptedSharedPreferences.
    // Pure ESP synchronous read+write, no network. Repeated cold starts
    // skip the rewrite. See SettingsManager.migrateLegacyKeysToFp.
    val currentFp = currentProfile.serverGroupFp.ifBlank { currentProfile.id }
    settingsManager.migrateLegacyKeysToFp(currentFp, currentProfile.serverUrl)

    val password = currentProfile.basicAuth?.passwordId?.let { settingsManager.basicAuthPassword(it) }
    // §2.5(c): 注入 mTLS 客户端证书材料（冷启动从 ESP 载入）。
    val clientCert = if (currentProfile.mtlsEnabled) currentProfile.clientCertId?.let { settingsManager.loadClientCertMaterial(it) } else null
    repository.configure(
        baseUrl = currentProfile.serverUrl,
        username = currentProfile.basicAuth?.username,
        password = password,
        hostPort = hostPortFromUrl(currentProfile.serverUrl),
        clientCert = clientCert
    )
    // #12 / §2.5(c) (gpter#4): 冷启动也要把 mTLS 信任策略同步给 image client，
    // 否则冷启图片无客户端证书 / 不信私有 CA（与 REST/SSE 对称）。
    HttpImageHolder.updateSsl(repository.currentSslConfig())
    // §fix-3 (gro-1#2/gpt-2#2): 冷启动 mTLS fail-open 检测——profile 宣告 mtlsEnabled
    // 但 ESP 材料缺失 / 损坏 → 写 ConnectionState.mtlsDegradedError 供 UI 红色 banner
    // （本 free-function 无 effects 总线，toast 由 controller 路径覆盖；冷启至少 slice
    // 可观测，避免用户只看泛化「连接失败」）。
    // §mtls-followup (glm-2 DRY): 消息映射抽到共享 resolveMtlsDegradationMessage，
    // 与 HostProfileController.reportMtlsDegradationIfAny 同源，消除漂移。
    val mtlsDegradedError: String? = resolveMtlsDegradationMessage(
        mtlsEnabled = currentProfile.mtlsEnabled,
        clientCert = clientCert,
        lastClientCertError = repository.lastClientCertError,
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
    val cachedEntries = settingsManager.sessionCache
    val restoredSessions = cachedEntries.map { entry -> entry.toSession() }
    val restoredRevertCutoffs = restoreRevertCutoffs(cachedEntries)
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
        it.copy(currentSessionId = restoredCurrentSessionId, revertCutoffs = restoredRevertCutoffs)
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
    //
    // §chat-ux-batch T8 (B3): the legacy `seedAgent = settingsManager
    // .selectedAgentName` seed was deleted here (T7 rewired agent selection
    // to the TRANSIENT pendingAgent chat-slice field; the global selectedAgentName
    // property is gone).
    // §model-selection / R-20 Phase 5: load per-serverGroupFp disabled-model
    // set for the active host (was per-baseUrl before Phase 5) so the chat
    // quick-switch picker + Settings render the right entries on cold start.
    val seedDisabledModels = settingsManager.getDisabledModels(currentFp)
    slices.mutateSettings {
        it.copy(
            themeMode = settingsManager.themeMode,
            // §P5a (Q5): seed the persisted language mode so the Appearance
            // SegmentedButton reflects the user's choice on cold start. The
            // locale itself is applied at process startup (OpenCodeApp.onCreate).
            localeMode = settingsManager.localeMode,
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
    // §R-19 Sprint 1 Lane A (#10): keep isConnecting in sync with the phase —
    // a cold-start Reconnecting signal means a connect probe is in flight, so
    // isConnecting MUST be true (it was being left at the default false,
    // causing the empty-state spinner / "connecting" badge to flip off for the
    // duration of coldStartReconnect). The Idle branch (no profiles configured)
    // leaves isConnecting at the ConnectionState default of false.
    val connectionPhase = if (profiles.isNotEmpty()) ConnectionPhase.Reconnecting else ConnectionPhase.Idle
    val isConnecting = connectionPhase is ConnectionPhase.Reconnecting
    slices.mutateConnection {
        it.copy(connectionPhase = connectionPhase, isConnecting = isConnecting, mtlsDegradedError = mtlsDegradedError)
    }
}

/** Builds the fail-closed cutoff map before any server session hydration. */
internal fun restoreRevertCutoffs(
    entries: List<cn.vectory.ocdroid.data.model.SessionCacheEntry>
): Map<String, RevertCutoff> = entries.mapNotNull { entry ->
    entry.revertMessageId?.let { messageId ->
        entry.id to RevertCutoff(
            sessionId = entry.id,
            messageId = messageId,
            state = entry.revertCreatedAtEpochMs?.let(RevertCutoffState::Resolved)
                ?: RevertCutoffState.PendingFetch
        )
    }
}.toMap()

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
    // R-20 Phase 5: per-serverGroupFp (was per-baseUrl). The current profile's
    // fp isolates the disable set so two profiles reaching the same URL but in
    // different groups don't clobber each other.
    val fp = hostProfileStore.currentProfile().serverGroupFp.ifBlank { hostProfileStore.currentProfile().id }
    val set = settingsManager.getDisabledModels(fp)
    slices.mutateSettings { it.copy(disabledModels = set) }
}
