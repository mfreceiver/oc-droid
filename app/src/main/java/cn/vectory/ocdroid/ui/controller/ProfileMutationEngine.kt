package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import cn.vectory.ocdroid.data.repository.http.buildMutualTlsConfig
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.service.ConnectionReconfigureBarrier
import cn.vectory.ocdroid.ui.settings.CaStage
import cn.vectory.ocdroid.ui.settings.ClientCertEditIntent
import cn.vectory.ocdroid.ui.settings.resolveClientCert
import cn.vectory.ocdroid.ui.settings.toMaterial
import cn.vectory.ocdroid.util.DebugLog
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
 * critical ordering constraints in the controller — the reconfigure boundary
 * preamble ([withHostReconfiguration] / [beginReconfigureBoundary]) and the
 * repository configure body ([configureRepositoryForProfileRaw]) stay in the
 * controller and are delegated to via lambdas. This engine does NOT own any
 * lock, reconfigure ticket lifecycle, or EvictGroup placement — those
 * sequencing decisions are the controller's (the caller's) responsibility.
 *
 * **Field-init pattern** (mirrors [cn.vectory.ocdroid.data.repository.SlimSyncEngine]):
 * wired via `by lazy` in [HostProfileController] — zero change to the 11-arg
 * public/test-visible constructor (F6 freeze). The injected lambdas capture
 * `this` (the controller) so they re-read live controller state on every call.
 *
 * **What stays in the controller** (per §5.4 #3 — barrier folding, extractor-
 * independent):
 *  - [HostProfileController.deleteHostProfileWithBarrier] — barrier-path delete
 *    body (delegated back via [deleteHostProfileWithBarrier] lambda).
 *  - [HostProfileController.configureRepositoryForProfileAwait] — barrier-path
 *    configure body.
 *  - [HostProfileController.beginReconfigureBoundary] / [withHostReconfiguration]
 *    — the reconfigure boundary preamble (H3 skeleton, optional
 *    ReconfigureBoundaryHelper in a future step).
 *  - [HostProfileController.configureRepositoryForProfileRaw] — repository
 *    configure body.
 *  - [HostProfileController.purgePerHostState] — per-host state purge
 *    (associated with the optional HostSwitcher).
 *
 * **resetLocalDataAndResync special case preserved**: that controller method
 * intentionally bypasses [withHostReconfiguration] (its CancelSse is deferred
 * until after clearAllLocalData + trafficTracker.reset +
 * ClearSessionWindowCache). This engine does NOT touch that path.
 */
class ProfileMutationEngine internal constructor(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
    private val hostProfileStore: HostProfileStore,
    private val settingsManager: SettingsManager,
    private val effects: SharedEffectBus,
    private val reconfigureBarrier: ConnectionReconfigureBarrier?,
    // ── Controller ops (reconfigure boundary + configure + state helpers) ──
    // These stay in HostProfileController; injected as lambdas so the engine
    // does not reference the controller type directly (mirrors SlimSyncEngine's
    // provider-lambda discipline). All withHostReconfiguration callers use
    // Unit-returning bodies, so the generic <T> collapses to Unit here.
    private val withHostReconfiguration: suspend (
        needsReconfigure: Boolean,
        body: suspend (OpenCodeRepository.SlimReconfigureTicket?) -> Unit,
    ) -> Unit,
    private val beginReconfigureBoundary: () -> OpenCodeRepository.SlimReconfigureTicket,
    private val configureRepositoryForProfileRaw: (
        profile: HostProfile,
        ticket: OpenCodeRepository.SlimReconfigureTicket?,
    ) -> Unit,
    private val configureRepositoryForProfile: (HostProfile) -> Unit,
    private val refreshHostProfileState: () -> Unit,
    private val purgePerHostState: (preserveServerGroupData: Boolean) -> Unit,
    private val deleteHostProfileWithBarrier: suspend (profileId: String) -> Unit,
) {

    /**
     * Persists [profile] and conditionally writes/clears the Basic Auth and
     * tunnel passwords according to the explicit three-state contract (Fix #5):
     *
     *  - [basicAuthEdited] = true  → write [basicAuthPassword] (blank removes).
     *  - [basicAuthEdited] = false → skip (preserve stored value).
     *  - [tunnelEdited] / [tunnelPassword] follow the same rule.
     *
     * When basicAuth is null, the orphaned password is always cleared.
     *
     * **C-D3 rev-3 round-6 (oracle §6.5 + review §C1/I5):** for active-host
     * connection-affecting edits (URL / mTLS / slim / Basic Auth), ALL
     * persistence (cert ESP writes, password writes, profile save, model
     * data clear) runs INSIDE the reconfigure boundary so a mid-flight
     * old-host workflow cannot observe partial new state. Non-active or
     * no-change edits run synchronously (no live host mutation → no boundary).
     *
     * `suspend` + `Result<Unit>` so the caller (HostViewModel → viewModelScope)
     * observes completion + failure: a failed save (applyClientCertSave
     * throws / configure throws / superseded ticket) returns `Result.failure`
     * and the dialog stays open with the error. Effects (`ForceReconnect` /
     * `HostProfileSwitched`) emit only on success.
     *
     * **C-D3 rev-3 round-7 (review I5-R7 CE discipline):** the body is wrapped
     * in `runSuspendCatching` (NOT plain `runCatching`) so a coroutine
     * [kotlinx.coroutines.CancellationException] thrown inside the boundary
     * (e.g. viewModelScope cancelled on VM clear) PROPAGATES instead of being
     * collapsed to `Result.failure`. Swallowing CE breaks structured
     * concurrency; this matches the project's established discipline
     * (`cn.vectory.ocdroid.util.runSuspendCatching` used 100+ times across
     * OpenCodeRepository / AppLifecycleMonitor / PartExpandState). Business
     * exceptions (applyClientCertSave IllegalArgumentException, configure
     * IOException, SupersededSlimReconfigureException) are still returned as
     * `Result.failure` — surface identical to plain runCatching for them.
     */
    suspend fun saveHostProfile(
        profile: HostProfile,
        basicAuthPassword: String = "",
        basicAuthEdited: Boolean = false,
        tunnelPassword: String = "",
        tunnelEdited: Boolean = false,
        // §2.7 fix-3（gpt-2#3 阻断）: 显式 mTLS 编辑意图，默认 [ClientCertEditIntent.Unchanged]
        // ——「未提供」≠「禁用」。非 Dialog 调用方（含 test pass-through）默认不动 ESP /
        // 不改 profile 的 mTLS 字段，避免误清既有证书。
        clientCertEdit: ClientCertEditIntent = ClientCertEditIntent.Unchanged,
    ): Result<Unit> = runSuspendCatching {
        var normalized = if (profile.basicAuth != null) {
            profile.copy(basicAuth = profile.basicAuth.copy(passwordId = profile.id))
        } else {
            profile
        }
        // #12 / S-1 / §tofu R2: snapshot the previous profile to detect which
        // connection-affecting fields changed on the ACTIVE host. serverUrl +
        // mTLS + slim + Basic Auth username/password all feed the live
        // auth-bearing client (AuthInterceptor reads hostConfig.username /
        // password at request time; hostConfig is updated by configure), so
        // ANY of them changing on the active host requires a reconfigure.
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
        // C-D3 rev-3 round-6: applyClientCertSave runs INSIDE the boundary now
        // (per review §C1). For change detection, PROJECT the post-save
        // mTLS fields from the edit intent (mirrors applyClientCertSave's
        // transformation without writing ESP). This keeps mtlsChanged correct
        // without leaking the boundary's mutating phase outside it.
        val projectedMtlsEnabled = when (clientCertEdit) {
            ClientCertEditIntent.Unchanged -> normalized.mtlsEnabled
            ClientCertEditIntent.Disable -> false
            is ClientCertEditIntent.Update -> true
        }
        val projectedClientCertId = when (clientCertEdit) {
            ClientCertEditIntent.Unchanged -> normalized.clientCertId
            ClientCertEditIntent.Disable -> null
            is ClientCertEditIntent.Update ->
                // First-time enable generates a new UUID inside applyClientCertSave;
                // for change detection, treat null→"<new>" as a definite change.
                // Re-imports keep the same id (no change unless material edited).
                normalized.clientCertId ?: "<new-uuid-placeholder>"
        }
        val mtlsChanged = previous?.mtlsEnabled != projectedMtlsEnabled ||
            previous?.clientCertId != projectedClientCertId ||
            mtlsMaterialEdited
        // §R8 slim-mode UI: 省流模式切换也是重连触发器——slim 字段影响路由
        // （/slimapi/ vs legacy）和版本头注入，必须重建 client。
        val slimChanged = previous?.slim != normalized.slim
        // C-D3 rev-3 round-6 (review C1): Basic Auth username/password edits
        // also feed the live auth-bearing client. AuthInterceptor reads
        // hostConfig.username/password at request time; those fields are
        // updated ONLY by repository.configure(...). So an active-host
        // basicAuth-only edit MUST reconfigure to push the new credentials
        // into HostConfig (previously excluded → live clients kept OLD
        // credentials, stale configuration).
        val basicAuthUsernameChanged = previous?.basicAuth?.username != normalized.basicAuth?.username
        val basicAuthChanged = basicAuthUsernameChanged || basicAuthEdited
        val needsReconfigure = isActiveHost &&
            (urlChanged || mtlsChanged || slimChanged || basicAuthChanged)

        withHostReconfiguration(needsReconfigure) { ticket ->
            // C-D3 rev-3 round-6: ALL connection-affecting mutations live
            // INSIDE the boundary (barrier or non-barrier). applyClientCertSave
            // (can throw — the throw escapes the boundary as Result.failure so
            // the dialog stays open with the error), password writes, orphan
            // clear, clearModelDataForGroup (when urlChanged), hostProfileStore
            // .save, configure (with the boundary-owned ticket — ticket-
            // ownership), and refreshHostProfileState — all sequential inside
            // the block. The boundary guarantees identity.beginReconfigure +
            // beginSlimReconfigure run BEFORE any of them, so a mid-flight
            // old-host workflow cannot observe partial new state.
            //
            // Cold path (ticket == null ⟺ !needsReconfigure) skips
            // clearModelDataForGroup + configure: no live host is mutated, so
            // there is nothing to invalidate / re-activate. Gating on
            // ticket != null documents ticket ownership (equivalent to the old
            // needsReconfigure branch split).
            normalized = applyClientCertSave(normalized, clientCertEdit)
            if (basicAuthEdited) {
                settingsManager.setBasicAuthPassword(normalized.id, basicAuthPassword)
            }
            if (tunnelEdited) {
                settingsManager.setTunnelPassword(normalized.id, tunnelPassword)
            }
            if (normalized.basicAuth == null) {
                settingsManager.setBasicAuthPassword(normalized.id, "")
            }
            if (ticket != null && urlChanged) {
                // §bug5 / R-20 Phase 5: URL changed → drop model data so stale
                // disable config does not leak / orphan. clearModelDataForGroup
                // is fp-keyed; the profile keeps its fp across URL edits.
                settingsManager.clearModelDataForGroup(
                    normalized.serverGroupFp.ifBlank { normalized.id }
                )
            }
            hostProfileStore.save(normalized)
            if (ticket != null) {
                configureRepositoryForProfileRaw(normalized, ticket)
            }
            refreshHostProfileState()
        }
        // Success path only — emit AFTER the boundary completes. If the body
        // threw, runSuspendCatching returns Result.failure and these emissions
        // are skipped (the dialog stays open with the error).
        if (needsReconfigure) {
            effects.tryEmitEffect(ControllerEffect.ForceReconnect)
            if (urlChanged) effects.tryEmitEffect(ControllerEffect.HostProfileSwitched)
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
        if (reconfigureBarrier != null) {
            scope.launch { deleteHostProfileWithBarrier(profileId) }
            return
        }
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
        // C-D3 rev-3 round-5 (oracle §6.3): active-profile deletion is a
        // reconfigure — invalidate identity + slim incarnation BEFORE
        // hostProfileStore.delete / clearClientCert / configure run, so a
        // mid-flight old-host workflow cannot pass commitIfSlimTokenCurrent
        // after the delete. The returned ticket threads into configure so the
        // SAME transaction activates (ticket-ownership).
        //
        // Non-current profile delete skips the early boundary here — it does
        // NOT mutate the live host, so [configureRepositoryForProfile]'s own
        // boundary (called below) suffices. (A non-current profile delete only
        // refreshes the unchanged current host's clients to drop the deleted
        // profile's cert material.)
        //
        // Cluster 6: preamble delegated to [beginReconfigureBoundary].
        val ticket = if (wasCurrent) beginReconfigureBoundary() else null
        hostProfileStore.delete(profileId)
        // §2.7: 清理被删 profile 的 mTLS 客户端证书材料（clientCertId 是 per-profile
        // 私有 UUID，无其它 profile 引用 → 安全 clear，防 ESP 悬空残留）。
        deletedProfile?.clientCertId?.let { settingsManager.clearClientCert(it) }
        val current = hostProfileStore.currentProfile()
        if (wasCurrent) {
            // Active deletion: thread the pre-begun ticket so the SAME
            // transaction that invalidated the incarnation activates it.
            configureRepositoryForProfileRaw(current, ticket)
        } else {
            // Non-current: live host unchanged; let the public helper do its
            // own boundary + cancel-SSE + configure cycle (no early
            // invalidation needed because no host mutation happens before it).
            configureRepositoryForProfile(current)
        }
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
            purgePerHostState(false)
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

    private companion object {
        private const val TAG = "ProfileMutationEngine"
    }
}
