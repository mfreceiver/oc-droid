package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.http.buildMutualTlsConfig
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.settings.CaStage
import cn.vectory.ocdroid.ui.settings.ClientCertEditIntent
import cn.vectory.ocdroid.ui.settings.resolveClientCert
import cn.vectory.ocdroid.ui.settings.toMaterial
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * §P9: Extracted profile CRUD + clientCert mutation engine from
 * [HostProfileController]. Holds the profile save/delete/duplicate/import/
 * export logic + the mTLS client-cert material resolution
 * ([applyClientCertSave]).
 *
 * **Sequence risk: LOWEST.** The save/delete operations have the least
 * critical ordering constraints — the reconfigure boundary preamble
 * ([withHostReconfiguration]) and the repository configure body
 * ([configureRepositoryForProfileRaw]) stay in the controller and are
 * delegated to via lambdas. This engine does NOT own any reconfigure ticket
 * lifecycle or EvictGroup placement — those sequencing decisions are the
 * controller's (the caller's) responsibility.
 *
 * lite-v2: reconfigureBarrier / beginReconfigureBoundary / deleteHostProfileWithBarrier
 * removed. withHostReconfiguration no longer takes a slim ticket.
 * configureRepositoryForProfileRaw no longer takes a ticket param.
 */
class ProfileMutationEngine internal constructor(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
    private val hostProfileStore: HostProfileStore,
    private val settingsManager: SettingsManager,
    private val effects: SharedEffectBus,
    // ── Controller ops (reconfigure boundary + configure + state helpers) ──
    // These stay in HostProfileController; injected as lambdas so the engine
    // does not reference the controller type directly (provider-lambda
    // discipline). All withHostReconfiguration callers use
    // Unit-returning bodies.
    private val withHostReconfiguration: suspend (
        needsReconfigure: Boolean,
        body: suspend () -> Unit,
    ) -> Unit,
    private val configureRepositoryForProfileRaw: (
        profile: HostProfile,
    ) -> Unit,
    private val configureRepositoryForProfile: (HostProfile) -> Unit,
    private val refreshHostProfileState: () -> Unit,
    private val purgePerHostState: () -> Unit,
) {

    /**
     * Persists [profile] and conditionally writes/clears the Basic Auth
     * password according to the explicit three-state contract (Fix #5):
     *
     *  - [basicAuthEdited] = true  → write [basicAuthPassword] (blank removes).
     *  - [basicAuthEdited] = false → skip (preserve stored value).
     *
     * When basicAuth is null, the orphaned password is always cleared.
     *
     * lite-v2: active-host connection-affecting changes persist + emit
     * RestartRequired (no runtime hot-reconfigure). Non-active changes
     * persist only with no restart signal.
     *
     * `suspend` + `Result<Unit>` so the caller (HostViewModel → viewModelScope)
     * observes completion + failure: a failed save (applyClientCertSave
     * throws) returns `Result.failure` and the dialog stays open.
     */
    suspend fun saveHostProfile(
        profile: HostProfile,
        basicAuthPassword: String = "",
        basicAuthEdited: Boolean = false,
        clientCertEdit: ClientCertEditIntent = ClientCertEditIntent.Unchanged,
    ): Result<Unit> = runSuspendCatching {
        var normalized = if (profile.basicAuth != null) {
            profile.copy(basicAuth = profile.basicAuth.copy(passwordId = profile.id))
        } else {
            profile
        }
        val previous = hostProfileStore.profiles().firstOrNull { it.id == normalized.id }
        val isActiveHost = normalized.id == slices.host.value.currentHostProfileId
        val urlChanged = previous?.serverUrl != normalized.serverUrl
        val mtlsMaterialEdited = when (clientCertEdit) {
            is ClientCertEditIntent.Update ->
                clientCertEdit.stagedP12 != null ||
                    clientCertEdit.caStage !is CaStage.Unchanged ||
                    clientCertEdit.p12PasswordEdited
            else -> false
        }
        val projectedMtlsEnabled = when (clientCertEdit) {
            ClientCertEditIntent.Unchanged -> normalized.mtlsEnabled
            ClientCertEditIntent.Disable -> false
            is ClientCertEditIntent.Update -> true
        }
        val projectedClientCertId = when (clientCertEdit) {
            ClientCertEditIntent.Unchanged -> normalized.clientCertId
            ClientCertEditIntent.Disable -> null
            is ClientCertEditIntent.Update ->
                normalized.clientCertId ?: "<new-uuid-placeholder>"
        }
        val mtlsChanged = previous?.mtlsEnabled != projectedMtlsEnabled ||
            previous?.clientCertId != projectedClientCertId ||
            mtlsMaterialEdited
        val slimChanged = previous?.slim != normalized.slim
        val basicAuthUsernameChanged = previous?.basicAuth?.username != normalized.basicAuth?.username
        val basicAuthChanged = basicAuthUsernameChanged || basicAuthEdited
        val needsReconfigure = isActiveHost &&
            (urlChanged || mtlsChanged || slimChanged || basicAuthChanged)

        withHostReconfiguration(needsReconfigure) {
            normalized = applyClientCertSave(normalized, clientCertEdit)
            if (basicAuthEdited) {
                settingsManager.setBasicAuthPassword(normalized.id, basicAuthPassword)
            }
            if (normalized.basicAuth == null) {
                settingsManager.setBasicAuthPassword(normalized.id, "")
            }
            if (urlChanged) {
                settingsManager.clearModelDataForGroup(normalized.id)
            }
            hostProfileStore.save(normalized)
            // lite-v2: NO configureRepositoryForProfileRaw — restart applies new settings.
            // withHostReconfiguration already emitted RestartRequired for needsReconfigure.
            refreshHostProfileState()
        }
        // lite-v2: needsReconfigure path emits RestartRequired via
        // withHostReconfiguration — no ForceReconnect/HostProfileSwitched
        // (restart supersedes runtime reconnect).
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
            runCatching { buildMutualTlsConfig(resolved.toMaterial()) }
                .onFailure {
                    throw IllegalArgumentException("客户端证书无效: ${it.message}", it)
                }
            val newId = oldId ?: UUID.randomUUID().toString()
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
     * lite-v2: barrier path and ticket-based configure removed. Active
     * deletion persists + signals restart-required; non-current deletion
     * just persists.
     *
     * §需求12阶段3 (oracle-assessed): under 需求12 profiles are fully
     * independent (no groups — a group can never have sibling profiles),
     * so the former `remainingInGroup` reference-counting
     * (`profilesInGroup`, conditional `clearModelDataForGroup`/`EvictGroup`)
     * is dead logic. Simplified to unconditional clear + evict on active
     * deletion.
     */
    fun deleteHostProfile(profileId: String) {
        val wasCurrent = profileId == slices.host.value.currentHostProfileId
        val deletedProfile = hostProfileStore.profiles().firstOrNull { it.id == profileId }
        // §需求12: fp == profile.id (the serverGroupFp field is deleted).
        val deletedProfileId = deletedProfile?.id
        hostProfileStore.delete(profileId)
        deletedProfile?.clientCertId?.let { settingsManager.clearClientCert(it) }
        val current = hostProfileStore.currentProfile()
        if (wasCurrent) {
            // Active deletion: persist + signal restart-required.
            // Repository will reconfigure on restart with the new current profile.
            // §需求12阶段3: unconditional clear + evict (no sibling profiles
            // can reference the group under 需求12).
            deletedProfileId?.let { settingsManager.clearModelDataForGroup(it) }
            purgePerHostState()
            deletedProfileId?.let {
                effects.tryEmitEffect(ControllerEffect.EvictGroup(it))
            }
            // lite-v2: RestartRequired supersedes runtime reconfigure.
            // No ForceReconnect/HostProfileSwitched — restart handles everything.
            // Lane A: use suspend emitEffect (not tryEmitEffect) so the effect
            // is never silently dropped — bus-full would suspend the producer
            // instead of logging and losing the restart signal. Non-suspend
            // context → wrap in scope.launch.
            scope.launch {
                effects.emitEffect(ControllerEffect.RestartRequired)
            }
        } else {
            // §需求12阶段3 (rev-3 blocker #2 fix): non-active deletion must
            // ALSO clear the deleted profile's persisted model data — under
            // 需求12 profiles are fully independent (a group can never have
            // sibling profiles), so the per-profile-id availability/disabled
            // ESP keys are orphans the instant their owning profile is gone.
            // Without this, deleting a non-current profile leaks
            // `model_availability_<id>` / `disabled_models_<id>` forever
            // (only `clearOrphanGroupKeys` migration would eventually catch
            // them, and only if the id isn't a UUID — but profile ids ARE
            // UUIDs, so they'd never be cleaned). Mirror the active branch.
            deletedProfileId?.let {
                settingsManager.clearModelDataForGroup(it)
                effects.tryEmitEffect(ControllerEffect.EvictGroup(it))
            }
        }
    }

    fun importHostProfile(payload: String): Result<HostProfile> = runCatching {
        hostProfileStore.importJson(payload).also { refreshHostProfileState() }
    }

    fun exportHostProfile(profile: HostProfile): String = hostProfileStore.exportJson(profile)

    private companion object {
        private const val TAG = "ProfileMutationEngine"
    }
}
