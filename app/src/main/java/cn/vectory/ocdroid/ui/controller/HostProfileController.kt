package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import cn.vectory.ocdroid.data.repository.http.hostPortFromUrl
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.ui.ConnectionFormSettings
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.FileState
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SettingsState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.TrafficState
import cn.vectory.ocdroid.ui.TunnelActivationState
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.UnreadState
import cn.vectory.ocdroid.ui.errorMessageOrFallback
import cn.vectory.ocdroid.ui.settings.ClientCertEditIntent
import cn.vectory.ocdroid.ui.settings.resolveMtlsDegradationMessage
import cn.vectory.ocdroid.ui.util.HttpImageHolder
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.TrafficTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
     *  [selectHostProfile] (4-step previous/target fp compare). */
    internal val currentServerGroupFp: () -> String,
    /**
     * CP1 (notify Phase-0): the single connection-identity store. The
     * reconfigure barrier origin — [configureServer] /
     * [configureRepositoryForProfile] / [resetLocalDataAndResync] call
     * [ConnectionIdentityStore.beginReconfigure] SYNCHRONOUSLY BEFORE
     * `repository.configure()` so the epoch bump is guaranteed to precede
     * any client rebuild. The async effect-bus emission
     * (CancelSseForReconfigure → CC.cancelSseForReconfigure) does NOT
     * guarantee this ordering — that was the bug being fixed (FGS spec §2).
     */
    internal val identityStore: ConnectionIdentityStore? = null,
    private val effectiveConnectionConfigResolver: cn.vectory.ocdroid.service.streaming.EffectiveConnectionConfigResolver? = null,
) {
    // ── §P9 ProfileMutationEngine (extracted) ──────────────────────────────

    /**
     * §P9: profile save/delete + clientCert mutation engine (extracted from
     * the 5 CRUD methods below). See [ProfileMutationEngine] for the full
     * contract. Field-init uses provider-lambda injection, `by lazy`.
     *
     * lite-v2: reconfigureBarrier / beginReconfigureBoundary / deleteHostProfileWithBarrier
     * removed — no runtime hot-reconfigure. withHostReconfiguration signature
     * changed (no slim ticket). configureRepositoryForProfileRaw no longer
     * takes a ticket param.
     */
    private val profileMutationEngine by lazy {
        ProfileMutationEngine(
            scope = scope,
            slices = slices,
            hostProfileStore = hostProfileStore,
            settingsManager = settingsManager,
            effects = effects,
            withHostReconfiguration = { needs, body -> withHostReconfiguration(needs, body) },
            configureRepositoryForProfileRaw = { profile -> configureRepositoryForProfileRaw(profile) },
            configureRepositoryForProfile = { configureRepositoryForProfile(it) },
            refreshHostProfileState = { refreshHostProfileState() },
            purgePerHostState = { purgePerHostState(it) },
        )
    }

    // ── Public accessors ───────────────────────────────────────────────────

    fun getHostProfiles(): List<HostProfile> = hostProfileStore.profiles()

    fun currentHostProfile(): HostProfile = hostProfileStore.currentProfile()

    /**
     * §resolver-single-source-of-truth (RESOLVER lane ②): pre-fills the
     * connection form (login dialog) from the EffectiveConnectionConfigResolver
     * — the single authority for the effective connection URL/credentials —
     * NOT from a direct settingsManager read.
     *
     * null = EXPLICIT FAIL: a null resolve() (no resolver wired, or no valid
     * active endpoint) returns a BLANK form (serverUrl/username/password = ""),
     * NOT a stale settingsManager.serverUrl fallback. A blank form is the
     * correct UX for "no active endpoint configured" (fresh install / host
     * mid-switch) and avoids surfacing a stale URL the user could accidentally
     * re-submit. This is the form-context equivalent of the token-stream /
     * health-probe explicit-fail (those throw / defer; the form shows blanks).
     */
    fun getSavedConnectionSettings(): ConnectionFormSettings {
        val config = effectiveConnectionConfigResolver?.resolve()
        return ConnectionFormSettings(
            serverUrl = config?.url ?: "",
            username = config?.username ?: "",
            password = config?.password ?: ""
        )
    }

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

    // ── Reconfigure boundary helpers (cluster 6 barrier folding) ───────────

    /**
     * C-8 reconfigure boundary: when [needsReconfigure] is true (active-host
     * connection-param edit OR profile select), runs [body] then EMITS
     * [ControllerEffect.RestartRequired] (suspend = SUSPEND-on-full, cannot
     * lose). Non-active changes run body with no restart signal.
     *
     * C-8 full matrix:
     *  - active profile edit connection params → persist + RestartRequired ✓
     *  - select another active profile → persist + RestartRequired ✓
     *  - delete active profile → persist + RestartRequired ✓
     *  - resetLocalDataAndResync → same-host teardown (no restart) ✓
     *  - non-active CRUD → persist only (no restart)
     */
    internal suspend fun withHostReconfiguration(
        needsReconfigure: Boolean,
        body: suspend () -> Unit,
    ): Unit {
        body()
        if (needsReconfigure) {
            // FIX-7: suspend emitEffect = SUSPEND-on-full, cannot lose.
            // tryEmitEffect was the old non-suspend variant that could drop on full bus.
            effects.emitEffect(ControllerEffect.RestartRequired)
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
     *
     * §P9: body delegated to [ProfileMutationEngine.saveHostProfile].
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
    ): Result<Unit> = profileMutationEngine.saveHostProfile(
        profile = profile,
        basicAuthPassword = basicAuthPassword,
        basicAuthEdited = basicAuthEdited,
        tunnelPassword = tunnelPassword,
        tunnelEdited = tunnelEdited,
        clientCertEdit = clientCertEdit,
    )

    fun duplicateHostProfile(profileId: String) =
        profileMutationEngine.duplicateHostProfile(profileId)

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
     *
     * §P9: body delegated to [ProfileMutationEngine.deleteHostProfile].
     */
    fun deleteHostProfile(profileId: String) =
        profileMutationEngine.deleteHostProfile(profileId)

    fun importHostProfile(payload: String): Result<HostProfile> =
        profileMutationEngine.importHostProfile(payload)

    fun exportHostProfile(profile: HostProfile): String =
        profileMutationEngine.exportHostProfile(profile)

    // ── Profile selection (host switch) ────────────────────────────────────

    /**
     * Switches to the host profile [profileId], fully resetting all per-host
     * state (sessions/messages/unread/draft/cache/commands).
     *
     * **C-8 (lite-v2): active profile select → persist + RestartRequired.**
     * The app must restart to apply the new host; no runtime reconfigure /
     * ForceReconnect / HostProfileSwitched (restart supersedes them).
     *
     * The purge + select sequence (no runtime reconfigure):
     *  1. Snapshot `previousFp = hostProfileStore.currentProfile().serverGroupFp`
     *     BEFORE [HostProfileStore.select] (select has a side effect — it
     *     bumps lastUsedAt + sets currentHostProfileId, so reading
     *     currentProfile() AFTER would return the new profile's fp).
     *  2. Inside the `withHostReconfiguration(needsReconfigure = true)` body:
     *     a. `activateProfile(profileId)` / `select(profileId)` — mutates the store.
     *     b. `purgePerHostState` — clears per-profile UX state; preserves
     *        per-server data iff same group.
     *     c. Emit `EvictGroup(previousFp)` if cross-group.
     *     d. `refreshHostProfileState()`.
     *     e. **NO** `configureRepositoryForProfileRaw` — restart handles it.
     *  3. After the block, `RestartRequired` is emitted by `withHostReconfiguration`.
     *     No `ForceReconnect`/`HostProfileSwitched`.
     *
     * **C-8 full matrix (frozen):**
     *  - active profile edit connection params → persist + RestartRequired ✓
     *  - select another active profile → persist + RestartRequired ✓ (THIS)
     *  - delete active profile → persist + RestartRequired ✓
     *  - resetLocalDataAndResync → same-host teardown (no restart) ✓
     *  - non-active CRUD → persist only (no restart)
     */
    fun selectHostProfile(profileId: String) {
        scope.launch {
            // Step 1: snapshot previousFp BEFORE select (select's side effect
            // makes post-select currentProfile() read the NEW profile).
            val previousFp = hostProfileStore.currentProfile().serverGroupFp
            // C-D3 rev-3 round-5 (oracle §4.1): read-only target lookup BEFORE
            // the boundary (so we know sameGroup without performing any
            // mutation outside it). The ACTIVATE/SELECT mutations move inside
            // the boundary below.
            val targetBeforeMutation =
                hostProfileStore.profiles().firstOrNull { it.id == profileId } ?: return@launch
            val sameGroup = previousFp == targetBeforeMutation.serverGroupFp
            // C-8: host switch = restart. withHostReconfiguration(needsReconfigure=true)
            // persists selection + purges per-host state + emits RestartRequired.
            // No runtime reconfigure (configureRepositoryForProfileRaw removed),
            // no ForceReconnect/HostProfileSwitched (restart supersedes them).
            withHostReconfiguration(needsReconfigure = true) {
                effectiveConnectionConfigResolver?.activateProfile(profileId)
                val selected = if (effectiveConnectionConfigResolver != null) {
                    hostProfileStore.currentProfile()
                } else {
                    hostProfileStore.select(profileId)
                }
                purgePerHostState(preserveServerGroupData = sameGroup)
                if (!sameGroup) {
                    effects.emitEffect(ControllerEffect.EvictGroup(previousFp))
                }
                refreshHostProfileState()
            }
            // RestartRequired is emitted by withHostReconfiguration inside the block.
            // No ForceReconnect / HostProfileSwitched — restart supersedes them.
        }
    }

    /**
     * Shared helper: purges ALL per-host session/message/unread/draft/cache
     * state. Used by both selectHostProfile and deleteHostProfile(wasCurrent).
     *
     * **R-20 Phase 1 group-isolated field classification** (plan §3 v4
     * glmer I2 — same-server vs per-profile UX):
     *
     *  - **per-profile UX (ALWAYS reset)**: draft,
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
        //
        // §A5-3 Phase B2: the pre-B2 scattered mutateChat + mutateSessionList
        // + mutateUnread (cross-group) / mutateChat-streaming-only (same-
        // group) + unconditional mutateComposer / mutateSettings /
        // mutateConnection sequence is collapsed into ONE atomic dispatch.
        // The reducer ([AppAction.HostStatePurged]) derives the cross-vs-same-
        // group field set from [preserveServerGroupData] and PRESERVES the
        // three chat-only fields via .copy() (never a fresh ChatState()). ONE
        // committed aggregate state → no torn intermediates for stateFlow
        // collectors.
        //
        // What stays OUTSIDE the dispatch (oracle: not state): the
        // settingsManager writes (clearRecentWorkdirs / currentWorkdir /
        // sessionCache) + the effect-bus emissions
        // (EvictGroup / ForceReconnect / HostProfileSwitched) below — they
        // are side-effects, run at the call site.
        slices.store.dispatch(
            cn.vectory.ocdroid.ui.AppAction.HostStatePurged(
                preserveServerGroupData = preserveServerGroupData,
            )
        )
        if (!preserveServerGroupData) {
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
            // §R18 Phase 2-F + §B4: currentSessionId cleared by HostStatePurged
            // reducer; wipe persisted current + sessionCache. open-tabs-list
            // no longer exists (list-detail).
            settingsManager.currentSessionId = null
            settingsManager.sessionCache = emptyList()
            // §B4 / §10 host switch 异组: force popToSessions so the detail
            // pane cannot stay on a prior host's chat/{id}.
            settingsManager.lastRoute = NavRoute.Sessions.route
            slices.store.mutateNav {
                it.copy(
                    lastRoute = NavRoute.Sessions.route,
                    navEpoch = it.navEpoch + 1L,
                )
            }
            slices.store.dispatch(AppAction.CloseDetail)
        } else {
            // §review-fix #5 (glm-3 ⚠️ per-profile UX): plan §3 glmer I2
            // classifies currentWorkdir as per-profile UX. Same-group switches
            // MUST reset currentWorkdir so the new profile starts fresh.
            settingsManager.currentWorkdir = null
            // §B4: open-tabs-list removed — no same-group tab-sharing concern.
        }
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
    fun configureServer(url: String, username: String? = null, password: String? = null): Job? {
        // lite-v2: manual URL entry from the login form does runtime configure
        // (the user expects immediate connection). The restart-required model
        // applies only to saved-profile edits (saveHostProfile), not to the
        // initial manual connection attempt.
        configureServerRaw(url, username, password)
        return null
    }

    /**
     * C-D3 rev-3 round-5 (oracle §5): raw body accepts the slim ticket from
     * the caller (barrier context or non-barrier beginSlimReconfigure return)
     * and threads it into [OpenCodeRepository.configure].
     */
    private fun configureServerRaw(
        url: String,
        username: String?,
        password: String?,
    ) {
        val oldUrl = settingsManager.serverUrl
        val urlChanging = oldUrl != url
        // CP1 (notify Phase-0) §2 step 1: identity + slim marker are rotated by
        // the caller (barrier or configureServer non-barrier path) BEFORE this
        // raw body mutates settings / calls repository.configure().
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
        effectiveConnectionConfigResolver?.activateManual(url, username, password) ?: run {
            settingsManager.serverUrl = url
            settingsManager.username = username
            settingsManager.password = password
        }
        val profile = currentHostProfile()
        // §2.5(b): 注入 mTLS 客户端证书材料（profile.mtlsEnabled 时从 ESP 载入）。
        // 手动输新 URL（未存为 profile）会沿用当前 profile 的证书——客户端证书为
        // 带外公开件、风险低（glmer S9）。
        val clientCert = if (profile.mtlsEnabled) profile.clientCertId?.let { settingsManager.loadClientCertMaterial(it) } else null
        repository.configure(
            url, username, password,
            hostPort = hostPortFromUrl(url),
            clientCert = clientCert,
            // §sse-self-cancel T1.2 / Fix②: slim provenance — propagate the
            // active profile's slim flag so HostConfig.slim (→ routing,
            // X-Slimapi-Version header, /slimapi/health) matches the user's
            // server type. Was implicitly defaulting to false (legacy) which
            // left a slim-profile host routed as legacy after a manual URL
            // change. See OpenCodeRepository.configure slim param.
            slim = profile.slim,
            // lite-v2-dev: reconfigureTicket 形参已从 configure() 移除（incarnation
            // 协议退役，见 OpenCodeRepository.configure）。ticket 仍在 barrier 层流转
            // 但不再传入 configure。
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
        configureRepositoryForProfileRaw(profile)
    }

    private fun configureRepositoryForProfileRaw(
        profile: HostProfile,
    ) {
        val password = profile.basicAuth?.passwordId?.let { settingsManager.basicAuthPassword(it) }
        // §2.5(a): 注入 mTLS 客户端证书材料（profile.mtlsEnabled 时从 ESP 载入）。
        // configure(null) 会 clear 已持材料，所以切到非 mTLS profile 时停止出示证书。
        val clientCert = if (profile.mtlsEnabled) profile.clientCertId?.let { settingsManager.loadClientCertMaterial(it) } else null
        // §bugfix-token-stream / RESOLVER lane ②: mirror profile.serverUrl into
        // settingsManager as a harmless WRITE-THROUGH CACHE. The three primary
        // direct readers (TokenStreamClient factory @ ControllerModule,
        // ConnectionHealthProbe identity/TOFU, getSavedConnectionSettings) have
        // been MIGRATED to EffectiveConnectionConfigResolver.resolve() — they no
        // longer read settingsManager.serverUrl. The mirror is KEPT because:
        //  (a) remaining legacy readers still consult settingsManager.serverUrl
        //      (HostProfileStore.migrateLegacySettings seed; configureServerRaw's
        //      `oldUrl` change-detection at :775), and
        //  (b) it is the endorsed safe choice during the staged migration
        //      (constraint #5: "if unsure, LEAVE the mirror") — it can only be
        //      removed once EVERY reader is migrated + tests are green.
        // It is now belt-and-braces: in Profile mode it keeps
        // settingsManager.serverUrl ≡ the resolver's URL, so any not-yet-migrated
        // reader observes the live value. Written before repository.configure so
        // both align at the same point. configureServerRaw (manual path) keeps it
        // in sync via activateManual.
        settingsManager.serverUrl = profile.serverUrl
        repository.configure(
            profile.serverUrl, profile.basicAuth?.username, password,
            hostPort = hostPortFromUrl(profile.serverUrl),
            clientCert = clientCert,
            // §sse-self-cancel T1.2 / Fix②: slim provenance — propagate
            // profile.slim so HostConfig.slim routes correctly (slim sidecar vs
            // legacy opencode). Was defaulting to false, leaving slim profiles
            // mis-routed on selectHostProfile / deleteHostProfile / testConnection.
            slim = profile.slim,
            // lite-v2-dev: reconfigureTicket 形参已从 configure() 移除（见上）。
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
        // CP1 (notify Phase-0) §2 step 1: SYNCHRONOUSLY bump the epoch AND
        // invalidate the old identity BEFORE any reset/reconnect runs. The
        // full-data reset tears down SSE + all in-memory caches; the epoch
        // bump ensures any in-flight collector / fetch from the pre-reset
        // state is dropped.
        identityStore?.beginReconfigure()
        // C-D3 rev-3 same-host reset: rotate the slim-local marker before the
        // local purge / slice reset (no-op compat shim — see OpenCodeRepository).
        repository.resetSlimForLocalWipe()
        // remove-message-persistence Task 5: the cacheRepository.clearAll() +
        // appContext.deleteDatabase(...) that used to wipe the SQLite cache DB
        // here were removed together with the persistence layer. The in-memory
        // session-window cache is still cleared via the
        // [ControllerEffect.ClearSessionWindowCache] emission below (step 3).
        // 1. Wipe persisted local data (preserves connection + tunnel creds).
        settingsManager.clearAllLocalData()
        // 2. Zero the in-memory traffic tracker (direct — same domain).
        trafficTracker.reset()
        // 3. Drop the per-session message-window cache (sibling controller).
        // §R18 Phase 3 Wave 1 (P1-3 C 类): resetLocalDataAndResync 多发顺序敏感 (Clear → Cancel → ColdStart) → 保持同步 tryEmitEffect。
        effects.tryEmitEffect(ControllerEffect.ClearSessionWindowCache)
        // 4. Tear down SSE + reset catch-up flags.
        effects.tryEmitEffect(ControllerEffect.CancelSseForReconfigure)
        // 5. T1b residual: chat + sessionList + unread via HostStatePurged
        //    (superset of the prior mutateChat field set + epoch bump —
        //    deliberate §fix-leak-window / ABA improvement). Connection /
        //    traffic / composer / file / settings still need the full
        //    reconnect defaults (isConnecting / Reconnecting / input wipe)
        //    which HostStatePurged does not cover — those stay as explicit
        //    slice resets below (effects stay at the call site).
        slices.store.dispatch(
            cn.vectory.ocdroid.ui.AppAction.HostStatePurged(
                preserveServerGroupData = false,
            )
        )
        // 6. Reset the connection + traffic slices to "reconnecting / zeroed".
        //    Defaults already cover tunnelActivationState=Idle; we override
        //    isConnecting + connectionPhase to signal the in-flight reconnect.
        slices.mutateConnection {
            ConnectionState(
                isConnecting = true,
                connectionPhase = ConnectionPhase.Reconnecting
            )
        }
        slices.mutateTraffic { TrafficState(resetAt = trafficTracker.resetAt) }
        // 7. Reset the composer/file/settings slices to defaults.
        slices.mutateComposer { ComposerState() }
        slices.mutateFile { FileState() }
        slices.mutateSettings { SettingsState() }
        // 8. Reconnect only after the same-host reset has rotated its marker.
        effects.tryEmitEffect(ControllerEffect.ColdStartReconnect)
    }

    private companion object {
        private const val TAG = "HostProfileController"
    }
}
