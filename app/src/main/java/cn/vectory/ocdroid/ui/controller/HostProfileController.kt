package cn.vectory.ocdroid.ui.controller

import android.content.Context
import android.util.Log
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.cache.CacheRepository
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import cn.vectory.ocdroid.data.repository.http.buildMutualTlsConfig
import cn.vectory.ocdroid.data.repository.http.hostPortFromUrl
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.ui.ConnectionFormSettings
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.FileState
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SettingsState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.TrafficState
import cn.vectory.ocdroid.ui.TunnelActivationState
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.UnreadState
import cn.vectory.ocdroid.ui.errorMessageOrFallback
import cn.vectory.ocdroid.ui.settings.CaStage
import cn.vectory.ocdroid.ui.settings.ClientCertEditIntent
import cn.vectory.ocdroid.ui.settings.resolveClientCert
import cn.vectory.ocdroid.ui.settings.resolveMtlsDegradationMessage
import cn.vectory.ocdroid.ui.settings.toMaterial
import cn.vectory.ocdroid.ui.util.HttpImageHolder
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.TrafficTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

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
 * now ride [SharedEffectBus.uiEvents] (`effects.tryEmitUiEvent(...)`).
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
class HostProfileController(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
    private val hostProfileStore: HostProfileStore,
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val trafficTracker: TrafficTracker,
    private val effects: SharedEffectBus,
    /** R-20 Phase 1: provider for the current host's serverGroupFp. Used by
     *  [selectHostProfile] (4-step previous/target fp compare) and
     *  [resetLocalDataAndResync] (deleteDatabase name scoping). */
    internal val currentServerGroupFp: () -> String,
    /** R-20 Phase 1: app Context for deleteDatabase() in the reset path.
     *  Injected (not derived from repository) so the cache reset does not
     *  reach across layers. */
    private val appContext: Context,
    /** R-20 Phase 1: persistent cache for [resetLocalDataAndResync]'s
     *  clearAll() + deleteDatabase path. The EvictGroup emission from
     *  [selectHostProfile] routes through AppCore.dispatchHostEffect (which
     *  holds its own CacheRepository); resetLocalDataAndResync is a fuller
     *  nuke that bypasses the per-group effect and clears everything, so it
     *  calls cacheRepository directly here. */
    internal val cacheRepository: CacheRepository,
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
        slices.mutateHost {
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
        tunnelEdited: Boolean = false,
        // §2.7 fix-3（gpt-2#3 阻断）: 显式 mTLS 编辑意图，默认 [ClientCertEditIntent.Unchanged]
        // ——「未提供」≠「禁用」。非 Dialog 调用方（含 test pass-through）默认不动 ESP /
        // 不改 profile 的 mTLS 字段，避免误清既有证书。
        clientCertEdit: ClientCertEditIntent = ClientCertEditIntent.Unchanged,
    ) {
        var normalized = if (profile.basicAuth != null) {
            profile.copy(basicAuth = profile.basicAuth.copy(passwordId = profile.id))
        } else {
            profile
        }
        // #12: snapshot the previous profile (before save) so we can detect
        // whether the serverUrl of the ACTIVE host changed — that is the case
        // that needs a live repository reconfigure + reconnect. Without this,
        // editing the current host's URL persisted the value but left the
        // existing REST/SSE OkHttp clients on the old endpoint, so the change
        // only took effect after a host switch or app restart.
        // S-1: detect a serverUrl change on the ACTIVE host — previously
        // editing the current host's URL persisted the new value but left the
        // existing clients pointed at the OLD endpoint, so the change only
        // took effect after a host switch / app restart. Treat it the same as
        // a toggle change: reconfigure + force reconnect to build clients for
        // the new URL.
        // §tofu R2: the allowInsecure toggle no longer exists; serverUrl +
        // mTLS are the only reconfigure-triggering changes.
        val previous = hostProfileStore.profiles().firstOrNull { it.id == normalized.id }
        // §review-r3 (gpter block): validate + persist mTLS material FIRST.
        // applyClientCertSave can throw (e.g. mtlsEnabled with no client cert
        // material → "开启 mTLS 需先导入客户端证书", or an invalid p12/CA failing
        // buildMutualTlsConfig). Running it BEFORE the Basic/Tunnel password
        // writes below guarantees a failed save is side-effect-free — no partial
        // credential commit that would leave ESP (passwords) inconsistent with an
        // unsaved profile. On success it writes p12/ca + returns normalized with
        // the final clientCertId/mtlsEnabled; the password writes use normalized.id
        // / normalized.basicAuth which it does not alter.
        normalized = applyClientCertSave(normalized, clientCertEdit)
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

        // #12 / S-1 / §fix-3 gro-1/gpt-2/glm-2: if the saved profile is the
        // currently active host AND either its serverUrl OR its mTLS
        // configuration actually changed, reconfigure the live repository
        // clients (REST / SSE / image) and force a reconnect so the new TLS
        // trust policy / endpoint / client cert takes effect immediately.
        // Mirrors the reconfigure+reconnect path used by selectHostProfile /
        // deleteHostProfile(wasCurrent).
        //
        // §tofu R2: allowInsecure toggle removed; serverUrl + mTLS are the
        // only reconfigure triggers.
        //
        // §fix-3 gro-1/gpt-2/glm-2 阻断: 旧条件不含 mTLS → 当前 host 开/关/换 mTLS
        // 证书后 live client 不重建。新增 mtlsChanged 维度。关键（glm-2/max-1 S1）：
        // applyClientCertSave 在 oldId!=null 时复用 oldId，故「保持启用换证书」(重导
        // p12 / 换 CA / 改密码) 时 clientCertId 不变——仅比 id 不够，必须用显式材料
        // 编辑信号 [mtlsMaterialEdited]。
        val isActiveHost = normalized.id == slices.host.value.currentHostProfileId
        val urlChanged = previous?.serverUrl != normalized.serverUrl
        val mtlsMaterialEdited = when (clientCertEdit) {
            is ClientCertEditIntent.Update ->
                clientCertEdit.stagedP12 != null ||
                    clientCertEdit.caStage !is CaStage.Unchanged ||
                    clientCertEdit.p12PasswordEdited
            else -> false
        }
        val mtlsChanged = previous?.mtlsEnabled != normalized.mtlsEnabled ||
            previous?.clientCertId != normalized.clientCertId ||
            mtlsMaterialEdited
        if (isActiveHost && urlChanged) {
            // §bug5 / R-20 Phase 5: URL changed → drop model data so stale
            // disable config does not leak / orphan. Was clearModelDataForUrl
            // (URL-keyed); now clearModelDataForGroup(fp-keyed) — the profile
            // keeps its fp across URL edits, so the fp slot is the right one
            // to clear. The HostProfileSwitched emission below reloads the
            // (now-empty) set.
            settingsManager.clearModelDataForGroup(normalized.serverGroupFp.ifBlank { normalized.id })
        }
        if (isActiveHost && (urlChanged || mtlsChanged)) {
            configureRepositoryForProfile(normalized)
            // §R18 Phase 3 Wave 1 (P1-3 C 类): saveHostProfile 多发顺序敏感 → 保持同步 tryEmitEffect。
            // wrapping in scope.launch would race the synchronous purge above; FIFO via the
            // SUSPEND buffer (256) makes a synchronous multi-emit reliable in practice.
            effects.tryEmitEffect(ControllerEffect.ForceReconnect)
            // §disabled-models-consistency: per-url/group 状态只在 urlChanged 时重载
            // （纯 TLS / mTLS 变化无需重载模型数据，只需 reconnect——gro-1/gpt-2/glm-2）。
            if (urlChanged) {
                effects.tryEmitEffect(ControllerEffect.HostProfileSwitched)
            }
        }
    }

    /**
     * §2.7 fix-3: 把 mTLS 编辑意图 [ClientCertEditIntent] 归一为生效材料并原子写 ESP，
     * 返回带最终 clientCertId/mtlsEnabled 的 [normalized] 副本。失败（无 p12 / 试构建
     * 失败）抛 [IllegalArgumentException] 阻止保存（调用方 runCatching 回显错误、保留对话框）。
     *
     * - [ClientCertEditIntent.Unchanged] → 不动 ESP、不改 profile 的 mTLS 字段（默认）。
     * - [ClientCertEditIntent.Update] → 试构建 [buildMutualTlsConfig]；`saveClientCert`
     *   原子写；profile 置 `clientCertId=id, mtlsEnabled=true`。无 p12 → 抛「需先导入证书」。
     * - [ClientCertEditIntent.Disable] → `clearClientCert(oldId)`；profile 置无 mTLS。
     */
    private fun applyClientCertSave(
        normalized: HostProfile,
        edit: ClientCertEditIntent,
    ): HostProfile = when (edit) {
        ClientCertEditIntent.Unchanged -> normalized
        ClientCertEditIntent.Disable -> {
            val oldId = normalized.clientCertId
            oldId?.let { settingsManager.clearClientCert(it) }
            normalized.copy(clientCertId = null, mtlsEnabled = false)
        }
        is ClientCertEditIntent.Update -> {
            val oldId = normalized.clientCertId
            val resolved = resolveClientCert(
                mtlsEnabled = true,
                stagedP12 = edit.stagedP12,
                hasImportedP12 = edit.hasImportedP12,
                caStage = edit.caStage,
                p12Password = edit.p12Password,
                p12PasswordEdited = edit.p12PasswordEdited,
                oldId = oldId,
                loadP12 = { settingsManager.getClientCertP12(it) },
                loadPassword = { settingsManager.getClientCertPassword(it) },
                loadCa = { settingsManager.getClientCertCa(it) },
            ) ?: throw IllegalArgumentException("开启 mTLS 需先导入客户端证书")
            // §2.7: 保存前试构建——防落坏材料（与运行时 configureClientCert 的
            // runCatching 降级 + lastClientCertError 双保险）。
            runCatching { buildMutualTlsConfig(resolved.toMaterial()) }
                .onFailure {
                    throw IllegalArgumentException("客户端证书无效: ${it.message}", it)
                }
            val newId = oldId ?: UUID.randomUUID().toString()
            // fix-3 max-1 S1: 原地覆盖语义。newId = oldId ?: UUID()，故 oldId!=null 时
            // newId==oldId（saveClientCert 已覆盖同一 id 的 p12/pw/ca 三 key，无需再
            // clearClientCert）。旧 `if(newId!=oldId&&oldId!=null)` 分支恒不执行，已删。
            settingsManager.saveClientCert(newId, resolved.p12, resolved.password, resolved.ca)
            normalized.copy(clientCertId = newId, mtlsEnabled = true)
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
     *
     * §review-fix #6 (gpter #5): EvictGroup emission is REFERENCE-COUNTED —
     * only emit when the deleted profile's group has NO remaining profile
     * referencing it. If a sibling profile in the same group still exists,
     * the group's cache is still live (the sibling reaches the same server);
     * evicting would orphan the sibling's hot cache. plan §3 矩阵 "删除当前
     * host profile → 该 group 无其它 profile 引用→清；有→不清".
     */
    fun deleteHostProfile(profileId: String) {
        val wasCurrent = profileId == slices.host.value.currentHostProfileId
        // §bug5 / R-20 Phase 5: capture the deleted profile's fp before the
        // store mutation so we can purge its group's model data if it was the
        // active host. (Was serverUrl-keyed; Phase 5 makes it fp-keyed — see
        // clearModelDataForGroup call below.)
        val deletedProfile = hostProfileStore.profiles().firstOrNull { it.id == profileId }
        val deletedFp = deletedProfile?.serverGroupFp
        // §review-fix #6: count remaining profiles in the SAME group BEFORE
        // the delete (after delete, the count would be off-by-one). The
        // deleted profile itself is excluded. If ≥1 sibling remains, the
        // group's cache is still referenced → skip EvictGroup.
        val remainingInGroup = deletedFp?.let { fp ->
            hostProfileStore.profilesInGroup(fp).filter { it.id != profileId }
        } ?: emptyList()
        hostProfileStore.delete(profileId)
        // §2.7: 清理被删 profile 的 mTLS 客户端证书材料（clientCertId 是 per-profile
        // 私有 UUID，无其它 profile 引用 → 安全 clear，防 ESP 悬空残留）。
        deletedProfile?.clientCertId?.let { settingsManager.clearClientCert(it) }
        val current = hostProfileStore.currentProfile()
        configureRepositoryForProfile(current)
        refreshHostProfileState()
        if (wasCurrent) {
            // §bug5 / R-20 Phase 5: drop the deleted active host's model data
            // so it does not leak into the new active host's identity (same-fp
            // collision on re-add, or a sibling profile in the same group
            // inheriting the disable set). Was clearModelDataForUrl; now
            // clearModelDataForGroup for the deleted profile's fp.
            if (remainingInGroup.isEmpty()) {
                deletedFp?.let { settingsManager.clearModelDataForGroup(it) }
            } else {
                DebugLog.i(
                    TAG,
                    "deleteHostProfile: kept model data for fp=$deletedFp — ${remainingInGroup.size} sibling profile(s) still reference this group"
                )
            }
            // R-20 Phase 1: deleting the active host is "异组切换" — purge
            // per-server-data (preserveServerData=false) and emit EvictGroup
            // for the deleted host's group (its cache is orphaned — no profile
            // references it anymore).
            purgePerHostState(preserveServerGroupData = false)
            // §review-fix #6 (gpter #5): reference-counted EvictGroup. Only
            // emit when NO sibling profile in the same group remains. If a
            // sibling still references the group, its cache stays live.
            if (remainingInGroup.isEmpty()) {
                deletedFp?.let {
                    // §R18 Phase 3 Wave 1 (P1-3 C 类): deleteHostProfile(wasCurrent) 多发顺序敏感 → 同步 tryEmitEffect。
                    effects.tryEmitEffect(ControllerEffect.EvictGroup(it))
                }
            } else {
                DebugLog.i(
                    TAG,
                    "deleteHostProfile: skipped EvictGroup for fp=$deletedFp — ${remainingInGroup.size} sibling profile(s) still reference this group"
                )
            }
            // §R18 Phase 3 Wave 1 (P1-3 C 类): deleteHostProfile(wasCurrent) 多发顺序敏感 → 同步 tryEmitEffect。
            effects.tryEmitEffect(ControllerEffect.ForceReconnect)
            // §disabled-models-consistency: deleting the active host switches to
            // a different baseUrl — reload per-host state (same as selectHostProfile
            // and saveHostProfile urlChanged paths).
            effects.tryEmitEffect(ControllerEffect.HostProfileSwitched)
        } else {
            // §review-fix #6 (non-current delete): even for a non-current
            // profile, if its group becomes orphaned (no remaining profiles)
            // the cache is dead weight. plan §3 矩阵 "删除非当前 host profile
            // → group 仍有 profile 引用→不清；无→可清或标 orphan". We emit
            // EvictGroup here too (same reference-count logic) so the orphaned
            // group's cache is reclaimed. The EvictGroup handler is
            // group-scoped so it won't touch the current group's cache.
            if (remainingInGroup.isEmpty()) {
                deletedFp?.let {
                    effects.tryEmitEffect(ControllerEffect.EvictGroup(it))
                }
            }
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
     *
     * **R-20 Phase 1 (plan §3 v4 momo N-B1 select 4-step):**
     *  1. Snapshot `previousFp = hostProfileStore.currentProfile().serverGroupFp`
     *     BEFORE [HostProfileStore.select] (select has a side effect — it
     *     bumps lastUsedAt + sets currentHostProfileId, so reading
     *     currentProfile() AFTER would return the new profile's fp).
     *  2. `select(profileId)` — mutates the store.
     *  3. Read `targetFp = returned profile.serverGroupFp`.
     *  4. Compare: same group → no cache eviction (just memory view switch);
     *     different group → emit [ControllerEffect.EvictGroup] for previousFp
     *     (group-scoped memory + persistent cache clear). The new group's
     *     cache stays intact (it may have been populated by an earlier session
     *     on a sibling profile in the same group).
     *
     * `purgePerHostState` still runs — but with the group-isolated field
     * classification (see [purgePerHostState] doc): per-profile UX state
     * (openSessionIds / draft / currentWorkdir) is wiped, but per-server-data
     * (sessions / unread / recentWorkdirs) is preserved iff same group.
     */
    fun selectHostProfile(profileId: String) {
        scope.launch {
            // Step 1: snapshot previousFp BEFORE select (select's side effect
            // makes post-select currentProfile() read the NEW profile).
            val previousFp = hostProfileStore.currentProfile().serverGroupFp
            // Step 2: select (mutates the store).
            val profile = hostProfileStore.select(profileId)
            // Step 3: read targetFp from the returned (new) profile.
            val targetFp = profile.serverGroupFp
            // Step 4: same-group → skip cache eviction (memory view only);
            //         different-group → emit EvictGroup(previousFp).
            val sameGroup = previousFp == targetFp
            purgePerHostState(preserveServerGroupData = sameGroup)
            if (!sameGroup) {
                // Group-scoped eviction: clears memory LRU + persistent cache
                // for previousFp only; the new group (targetFp, now current)
                // keeps its cache. Routed through the effect bus so AppCore's
                // dispatchHostEffect handler runs both halves atomically-ish
                // (memory sync, persistent async).
                // §R18 Phase 3 Wave 1 (P1-3 A 类): scope.launch suspend context → suspend emitEffect.
                effects.emitEffect(ControllerEffect.EvictGroup(previousFp))
            }
            configureRepositoryForProfile(profile)
            refreshHostProfileState()
            // §R18 Phase 3 Wave 1 (P1-3 A 类): scope.launch suspend 上下文 → 用 suspend emitEffect
            // 可靠+FIFO，不会丢。
            effects.emitEffect(ControllerEffect.ForceReconnect)
            // §host-switch-order: only AFTER select + reconnect have settled do
            // we hand control back for host-scoped post-processing. Doing this
            // synchronously in the caller raced the launch above and read the
            // PREVIOUS host's baseUrl.
            effects.emitEffect(ControllerEffect.HostProfileSwitched)
        }
    }

    /**
     * Shared helper: purges ALL per-host session/message/unread/draft/cache
     * state. Used by both selectHostProfile and deleteHostProfile(wasCurrent).
     *
     * **R-20 Phase 1 group-isolated field classification** (plan §3 v4
     * glmer I2 — same-server vs per-profile UX):
     *
     *  - **per-profile UX (ALWAYS reset)**: openSessionIds, draft,
     *    currentWorkdir, composer draftWorkdir, availableCommands,
     *    serverVersion. These describe "what the user was doing on this
     *    profile" — they would leak across profiles in the same group.
     *  - **per-server data (preserve iff [preserveServerGroupData])**:
     *    sessions, directorySessions, unread markers, recentWorkdirs,
     *    disabled_models, session-window cache. Two profiles in the same
     *    group reach the same server, so the server's data is identical —
     *    preserving it avoids a flicker + re-fetch on a same-group switch.
     *
     * @param preserveServerGroupData true iff previousFp == targetFp (a
     *   same-group switch). When false (异组切换 / delete active host), the
     *   full reset runs as before.
     */
    private fun purgePerHostState(preserveServerGroupData: Boolean = false) {
        // §slice-only-preserve (glm-1 / gpt-1): ChatState carries three fields
        // that are NOT mirrored to AppState (isCompacting, compactStartedAt,
        // refreshNonce). Use .copy() on the existing slice value so those are
        // preserved (a fresh ChatState() would clobber them); only the AppState-
        // represented chat fields are reset here.
        if (!preserveServerGroupData) {
            slices.mutateChat {
                it.copy(
                    currentSessionId = null,
                    messages = emptyList(),
                    partsByMessage = emptyMap(),
                    streamingPartTexts = emptyMap(),
                    streamingReasoningPart = null,
                    // §history-load-fix: drop the previous host's load flags so
                    // their spinner state cannot leak into the new host's chat
                    // (round-4 gpt-2 🔴: isLoadingMessages too — the session-
                    // guarded finally won't clear it for a non-current session,
                    // so the host switch must, same as SessionSwitcher.switchTo).
                    isLoadingMessages = false,
                    isLoadingMoreMessages = false
                )
            }
            slices.mutateSessionList {
                it.copy(
                    sessions = emptyList(),
                    directorySessions = emptyMap(),
                    openSessionIds = emptyList(),
                    sessionStatuses = emptyMap(),
                    sessionTodos = emptyMap(),
                    // §phase2-isolation: clear cross-host diff leak. sessionDiffs
                    // (per-session FileDiff snapshots) are per-server data; a
                    // stale snapshot from host A must NOT bleed into host B's
                    // Workspace Changes view. Same-group switches preserve it
                    // (server-identical data — same reasoning as sessions /
                    // statuses / todos above). R-20 §Per-server data.
                    sessionDiffs = emptyMap()
                )
            }
            slices.mutateUnread {
                it.copy(
                    unreadSessions = emptySet(),
                    tempClearedUnread = emptySet(),
                    lastViewedTime = emptyMap()
                )
            }
            // §recent-workdirs / R-20 Phase 5: clear per-host workdir memory
            // on 异组 switch (paths from server A are meaningless on server B).
            // Was a single global slot (`recentWorkdirs = emptyList()`); now
            // scoped to the current fp — same-group switches preserve the
            // list (correct: same server, same workdirs). NOTE: at the call
            // site (selectHostProfile) currentServerGroupFp() reads the NEW
            // (target) fp because select() already ran. The NEW profile's
            // recentWorkdirs is cleared so it starts fresh; the OLD profile's
            // data is preserved (the user can switch back).
            settingsManager.clearRecentWorkdirs(currentServerGroupFp())
            // §H3: clear persisted workdir — a path from host A is meaningless
            // on host B. configureRepositoryForProfile re-scopes to the (now-
            // null) workdir, which is correct for a fresh host.
            settingsManager.currentWorkdir = null
            // §review-fix #5 (gpter #4): the prior code emitted
            // ClearSessionWindowCache (NUKES the entire memory LRU across ALL
            // groups) here. But selectHostProfile's 异组 branch already emits
            // EvictGroup(previousFp) — the EvictGroup handler in AppCore calls
            // clearMemoryForGroup(previousFp) which is GROUP-SCOPED. The
            // nuke-all here was redundant (EvictGroup already handles it) AND
            // over-broad (it would evict OTHER groups' hot caches too — e.g.
            // a third group the user switches between frequently). Removed;
            // rely on the EvictGroup effect for the group-scoped clear.
            // (deleteHostProfile(wasCurrent) below also emits EvictGroup, so
            // its purgePerHostState(preserveServerGroupData=false) call no
            // longer nukes-all either — correct: the deleted host's group is
            // evicted group-scoped, other groups keep their caches.)
            //
            // §R18 Phase 2-F: currentSessionId clear above (chat slice) is the
            // runtime source; the AppCore collector persists non-null changes
            // only, so no manual null write here. openSessionIds/sessionCache
            // are still persisted directly (no collector for them).
            settingsManager.openSessionIds = emptyList()
            settingsManager.sessionCache = emptyList()
        } else {
            // Same-group switch: keep sessions / directorySessions / unread /
            // session-window cache / recentWorkdirs (server-identical data).
            // The current session window stays valid; just clear the
            // streaming overlay (a stale delta from the old profile's
            // in-flight turn should not bleed into the new profile's view).
            slices.mutateChat {
                it.copy(
                    streamingPartTexts = emptyMap(),
                    streamingReasoningPart = null
                )
            }
            // §review-fix #5 (glm-3 ⚠️ per-profile UX): plan §3 glmer I2
            // classifies currentWorkdir as per-profile UX ("两接入点各有
            // currentWorkdir，不清会跨 profile 泄漏"). Same-group switches
            // MUST reset currentWorkdir so the new profile starts fresh
            // (configureRepositoryForProfile re-scopes to null). The prior
            // code only reset currentWorkdir in the 异组 branch.
            settingsManager.currentWorkdir = null
            // §review-fix #5 openSessionIds same-group sharing: plan §3 glmer
            // I2 lists openSessionIds as per-profile UX (reset). HOWEVER
            // openSessionIds is currently a GLOBAL single key (no fp
            // dimension) — Phase 5 migrates it to fp-keyed. For same-group
            // (same server), sharing tabs is the correct UX (the user sees
            // the same open conversations on both entry points). Resetting
            // here would clear the tabs the user JUST had open on the sibling
            // profile. DECISION:校正 plan — same-group preserves
            // openSessionIds until Phase 5 migrates it to fp-keyed (at which
            // point each profile gets its own). No code change needed; the
            // comment documents the intentional divergence from plan §3.
        }
        // per-profile UX state — always reset regardless of group.
        // §review-fix #5: composer.draftWorkdir is the "new session in
        // workdir X" draft pointer — per-profile (different profiles = different
        // project contexts). Reset always.
        slices.mutateComposer { it.copy(draftWorkdir = null) }
        slices.mutateSettings { it.copy(availableCommands = emptyList()) }
        slices.mutateConnection { it.copy(serverVersion = null) }
    }

    // ── Repository reconfiguration ────────────────────────────────────────

    /**
     * Reconfigures the repository for manual server URL/credential entry (the
     * "direct connection" path from the login form, NOT a profile switch).
     *
     * §Stage D: cancels in-flight SSE BEFORE repository.configure so events
     * from the previous credential/host don't land in AppState during the new
     * probe. §tofu R2: passes the host:port authority (derived from the URL
     * via [hostPortFromUrl]) so the TOFU pin lookup resolves for previously-
     * trusted endpoints — replaces the legacy `allowInsecureConnections` flag.
     */
    fun configureServer(url: String, username: String? = null, password: String? = null) {
        val oldUrl = settingsManager.serverUrl
        val urlChanging = oldUrl != url
        if (urlChanging) {
            // §bug5 / R-20 Phase 5: manual URL change also clears model data
            // so the disable set does not orphan against an identity the user
            // abandoned. Was clearModelDataForUrl(oldUrl); now
            // clearModelDataForGroup for the current host's fp (the manual
            // form operates on the current profile — its fp is unchanged
            // across URL edits, so we drop the fp slot to give the new server
            // a fresh start). HostProfileSwitched below reloads the (now-
            // empty) set.
            settingsManager.clearModelDataForGroup(currentServerGroupFp())
        }
        effects.tryEmitEffect(ControllerEffect.CancelSseForReconfigure)
        settingsManager.serverUrl = url
        settingsManager.username = username
        settingsManager.password = password
        val profile = currentHostProfile()
        // §2.5(b): 注入 mTLS 客户端证书材料（profile.mtlsEnabled 时从 ESP 载入）。
        // 手动输新 URL（未存为 profile）会沿用当前 profile 的证书——客户端证书为
        // 带外公开件、风险低（glmer S9）。
        val clientCert = if (profile.mtlsEnabled) profile.clientCertId?.let { settingsManager.loadClientCertMaterial(it) } else null
        repository.configure(
            url, username, password,
            hostPort = hostPortFromUrl(url),
            clientCert = clientCert
        )
        // #12 / §2.5(b): mirror the host's TLS trust policy (incl. mTLS) into
        // the markdown image client (same as configureRepositoryForProfile).
        HttpImageHolder.updateSsl(repository.currentSslConfig())
        // §fix-3 (gro-1#2/gpt-2#2): mTLS 期望但材料缺失/损坏 → fail-loud，不静默降级。
        reportMtlsDegradationIfAny(profile, clientCert)
        if (urlChanging) {
            // §R18 Phase 3 Wave 1 (P1-3 C 类): configureServer 多发顺序敏感 (CancelSse 在前，HostProfileSwitched 在后) → 保持同步 tryEmitEffect。
            effects.tryEmitEffect(ControllerEffect.HostProfileSwitched)
        }
    }

    /**
     * Reconfigures the repository for a [profile]: cancels SSE, configures the
     * URL/credentials with the profile's host:port authority for TOFU pin
     * lookup (§tofu R2 — was the legacy `allowInsecureConnections` flag), and
     * (Phase 1) restored the persisted workdir.
     *
     * §Stage D (gpter 阻塞 #1): this is the single authoritative SSE
     * cancellation point for all profile-based reconfigure paths
     * (selectHostProfile / deleteHostProfile / testConnection).
     * §R18 Phase 2-E step 2: the repository.setCurrentDirectory call that
     * used to restore the workdir here is removed — directory-scoped calls
     * now take an explicit `directory` parameter sourced from
     * settingsManager.currentWorkdir. The workdir itself is still cleared on
     * host switch by the caller (see resetLocalDataAndResync).
     */
    internal fun configureRepositoryForProfile(profile: HostProfile) {
        // §R18 Phase 3 Wave 1 (P1-3 B 类): 单发非 suspend (configureRepositoryForProfile 可能被 C 类路径调用，但本处只是一次 emit) → tryEmitEffect。
        effects.tryEmitEffect(ControllerEffect.CancelSseForReconfigure)
        val password = profile.basicAuth?.passwordId?.let { settingsManager.basicAuthPassword(it) }
        // §2.5(a): 注入 mTLS 客户端证书材料（profile.mtlsEnabled 时从 ESP 载入）。
        // configure(null) 会 clear 已持材料，所以切到非 mTLS profile 时停止出示证书。
        val clientCert = if (profile.mtlsEnabled) profile.clientCertId?.let { settingsManager.loadClientCertMaterial(it) } else null
        repository.configure(
            profile.serverUrl, profile.basicAuth?.username, password,
            hostPort = hostPortFromUrl(profile.serverUrl),
            clientCert = clientCert
        )
        // #12 / §2.5(a): keep the markdown image HTTP client's TLS trust policy
        // in sync with the active host (now incl. mTLS) so self-signed HTTPS
        // images load AND present the client cert where required (same entry
        // point as REST / SSE).
        HttpImageHolder.updateSsl(repository.currentSslConfig())
        // §fix-3 (gro-1#2/gpt-2#2): mTLS 期望但材料缺失/损坏 → fail-loud，不静默降级。
        reportMtlsDegradationIfAny(profile, clientCert)
    }

    /**
     * §fix-3 (gro-1#2/gpt-2#2/max-1 M1): 检测当前 host 的 mTLS 是否处于「期望但材料
     * 缺失/损坏」的降级态，若是则：① 写 [ConnectionState.mtlsDegradedError]（UI 红色
     * banner 观测）；② emit [UiEvent.Error]（toast）。两者均由本 host controller 的
     * configure 路径调用——configRepositoryForProfile / configureServer。无降级时清空
     * 字段（修复后 banner 消失）。
     *
     * - missing: [profile.mtlsEnabled] 但 [clientCert]==null（loadClientCertMaterial 返回
     *   null：ESP 缺 p12/pw key）。
     * - damaged: [OpenCodeRepository.lastClientCertError] 非空（configureClientCert 试构建
     *   失败，已降级 mutualTlsConfig=null）。
     *
     * §mtls-followup (glm-2 DRY): 消息映射改用共享
     * [resolveMtlsDegradationMessage]，与 [cn.vectory.ocdroid.ui.applySavedSettings]
     * 冷启动路径同源，消除文案/触发条件漂移。
     *
     * §mtls-followup (max-1 S2): toast 去重——[lastEmittedMtlsDegradation] 缓存上次 emit
     * 的降级消息文本。同一降级态重复 configure（select/save/configure 循环、URL 未变的
     * configureServer 重入）不再重复弹 toast；slice 仍每次刷新（banner 永远反映最新态）。
     * 健康态（error==null）复位缓存，确保下次降级再现时重新提示。指纹直接用 error 文本
     * 本身（比 mtlsEnabled+lastClientCertError hash 更精确：还覆盖 clientCert 是否为 null
     * 维度），且仍是单字段单比较的 trivial 改动。
     */
    private var lastEmittedMtlsDegradation: String? = null

    private fun reportMtlsDegradationIfAny(profile: HostProfile, clientCert: ClientCertMaterial?) {
        val error: String? = resolveMtlsDegradationMessage(
            mtlsEnabled = profile.mtlsEnabled,
            clientCert = clientCert,
            lastClientCertError = repository.lastClientCertError,
        )
        slices.mutateConnection { it.copy(mtlsDegradedError = error) }
        if (error != null) {
            if (error != lastEmittedMtlsDegradation) {
                lastEmittedMtlsDegradation = error
                effects.tryEmitUiEvent(UiEvent.Error(R.string.host_mtls_missing_cert, listOf(error)))
            }
        } else {
            // 健康态：复位指纹，下次降级再现时重新 emit。
            lastEmittedMtlsDegradation = null
        }
    }

    // ── Tunnel activation ──────────────────────────────────────────────────

    /**
     * Activates the tunnel for the current host profile. Surfaces
     * loading/error/success state through `tunnelActivationState` on the
     * connection slice + UiEvent.Error/Success via [effects.uiEvents].
     * §tofu R2: passes the host:port authority (derived from the profile URL)
     * so the tunnel client honors any TOFU pin for this endpoint.
     */
    fun activateTunnelForCurrentHost() {
        val profile = hostProfileStore.currentProfile()
        val passwordId = profile.tunnelPasswordId
        if (passwordId == null) {
            slices.mutateConnection {
                it.copy(
                    tunnelActivationState = TunnelActivationState.Error("未设置隧道密码")
                )
            }
            effects.tryEmitUiEvent(UiEvent.Error(R.string.error_tunnel_password_unset))
            return
        }
        val password = settingsManager.getTunnelPassword(passwordId)
        if (password.isNullOrBlank()) {
            slices.mutateConnection {
                it.copy(
                    tunnelActivationState = TunnelActivationState.Error("隧道密码为空")
                )
            }
            effects.tryEmitUiEvent(UiEvent.Error(R.string.error_tunnel_password_empty))
            return
        }

        slices.mutateConnection { it.copy(tunnelActivationState = TunnelActivationState.Loading) }
        scope.launch {
            repository.activateTunnel(
                profile.serverUrl, password,
                hostPort = hostPortFromUrl(profile.serverUrl)
            )
                .onSuccess {
                    slices.mutateConnection {
                        it.copy(
                            // §success-channel / §R-17 batch2: success now rides a
                            // UiEvent.Success (NOT error) so ChatScreen renders a
                            // success snackbar instead of "发生错误" + "查看". The
                            // sticky tunnelActivationState=Success still drives the
                            // ServerManagementDialog's success indicator.
                            tunnelActivationState = TunnelActivationState.Success
                        )
                    }
                    effects.tryEmitUiEvent(UiEvent.Success(R.string.success_tunnel_activated))
                    Log.d(TAG, "Tunnel activated successfully for ${profile.serverUrl}")
                    // §user-req: tunnel 激活后自动冷启动级刷新。1.5s 经验值——cloudflared
                    // 类守护进程在 activate API 返回后需要短暂时间建立路由。coldStartReconnect
                    // 自带 3 次退避重试（1/2/4s）兜底，即使首次探测失败也会在 ~7s 内成功。
                    delay(1500)
                    // §R18 Phase 3 Wave 1 (P1-3 A 类): activateTunnel scope.launch onSuccess suspend 上下文 → suspend emitEffect。
                    effects.emitEffect(ControllerEffect.ColdStartReconnect)
                }
                .onFailure { error ->
                    val msg = errorMessageOrFallback(error, "未知错误（无异常信息）")
                    slices.mutateConnection {
                        it.copy(
                            tunnelActivationState = TunnelActivationState.Error(msg)
                        )
                    }
                    effects.tryEmitUiEvent(UiEvent.Error(R.string.error_tunnel_activation_failed, listOf(msg)))
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
        // R-20 Phase 1 (dser I-2, maxer B1): wipe the cache DB FIRST (before
        // clearAllLocalData) so the cache_db_key preserved-key has nothing to
        // unlock. deleteDatabase removes the .db + -wal + -shm sidecars;
        // cacheRepository.clearAll() wipes any in-memory rows that the
        // (possibly still-open) Room handle holds. Order: clearAll →
        // deleteDatabase → clearAllLocalData (prefs). cacheRepository.clearAll
        // is suspend → wrapped in scope.launch (fire-and-forget; the rest of
        // reset runs synchronously and is the user-visible path).
        scope.launch {
            runCatching { cacheRepository.clearAll() }
                .onFailure { DebugLog.e(TAG, "cacheRepository.clearAll failed during reset", it) }
            runCatching { appContext.deleteDatabase(CACHE_DB_NAME) }
                .onFailure { DebugLog.e(TAG, "deleteDatabase($CACHE_DB_NAME) failed during reset", it) }
        }
        // 1. Wipe persisted local data (preserves connection + tunnel creds +
        //    cache_db_key — the key survives so the next app start can re-open
        //    the freshly-created empty cache DB without triggering CacheModule's
        //    destructive-reset fallback).
        settingsManager.clearAllLocalData()
        // 2. Zero the in-memory traffic tracker (direct — same domain).
        trafficTracker.reset()
        // 3. Drop the per-session message-window cache (sibling controller).
        // §R18 Phase 3 Wave 1 (P1-3 C 类): resetLocalDataAndResync 多发顺序敏感 (Clear → Cancel → ColdStart) → 保持同步 tryEmitEffect。
        effects.tryEmitEffect(ControllerEffect.ClearSessionWindowCache)
        // 4. Tear down SSE + reset catch-up flags.
        effects.tryEmitEffect(ControllerEffect.CancelSseForReconfigure)
        // 5. Reset slices to defaults, preserving the host slice (kept above)
        //    and the chat slice's slice-only fields (isCompacting /
        //    compactStartedAt / refreshNonce — use .copy() so they survive).
        //    Equivalent to the pre-migration `AppState(hostProfiles,
        //    currentHostProfileId)` full-reset: every AppState-represented
        //    field returns to its default.
        slices.mutateChat { c ->
            c.copy(
                currentSessionId = null,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null,
                olderMessagesCursor = null,
                // §F3-load-more: reset 时 hasMore 与 cursor 保持一致（均"无更多"）。
                hasMoreMessages = false,
                isLoadingMessages = false,
                // §history-load-fix: reset the loadMore flag alongside the
                // background-reload flag (parallel reset point).
                isLoadingMoreMessages = false,
                gapMarkers = emptyList(),
                staleNotice = false,
                currentModel = null
            )
        }
        slices.mutateSessionList { SessionListState() }
        slices.mutateUnread { UnreadState() }
        // 6. Reset the connection + traffic slices to "reconnecting / zeroed".
        //    Defaults already cover tunnelActivationState=Idle; we override
        //    isConnecting + connectionPhase to signal the in-flight reconnect.
        slices.mutateConnection {
            ConnectionState(
                isConnecting = true,
                connectionPhase = ConnectionPhase.Reconnecting
            )
        }
        slices.mutateTraffic { TrafficState() }
        // 7. Reset the composer/file/settings slices to defaults.
        slices.mutateComposer { ComposerState() }
        slices.mutateFile { FileState() }
        slices.mutateSettings { SettingsState() }
        // 8. Reconnect to the (preserved) current host profile and re-fetch.
        effects.tryEmitEffect(ControllerEffect.ColdStartReconnect)
    }

    private companion object {
        private const val TAG = "HostProfileController"
        /** R-20 Phase 1: matches [cn.vectory.ocdroid.di.CacheModule]'s DB name
         *  so resetLocalDataAndResync's deleteDatabase targets the cache DB.
         *  Kept here (not imported from CacheModule) to avoid coupling the
         *  controller to the DI layer — drift risk is bounded (a rename in
         *  CacheModule would be caught by androidTest's
         *  CacheDatabaseInstrumentedTest which references the same name). */
        private const val CACHE_DB_NAME = "chat_cache.db"
    }
}
