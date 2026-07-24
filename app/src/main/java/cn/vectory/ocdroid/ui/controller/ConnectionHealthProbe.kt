package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.data.repository.http.TofuDecision
import cn.vectory.ocdroid.data.repository.http.hostPortFromUrl
import cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator
import cn.vectory.ocdroid.service.bootstrap.TofuState
import cn.vectory.ocdroid.service.DegradedBootstrapTerminator
import cn.vectory.ocdroid.service.OwnershipStartResult
import cn.vectory.ocdroid.service.streaming.BootstrapRetryPolicy
import cn.vectory.ocdroid.service.streaming.ConnectionBootstrapEngine
import cn.vectory.ocdroid.service.streaming.ConnectionBootstrapOutcome
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.StreamingServiceLauncher
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.errorMessageOrFallback
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import javax.net.ssl.SSLException
import java.security.cert.CertificateException

/**
 * L4c (Wave ζ): the connection health-probe concern extracted verbatim from
 * [ConnectionCoordinator]. Owns the multi-state connect flow — health-check
 * probe with exponential-backoff retry, the 30s health-check throttle, the
 * TOFU (trust-on-first-use) capture/decision delegation to
 * [ConnectionBootstrapCoordinator], the degraded-TOFU foreground-promotion
 * hook, and the engine-driven bootstrap path ([testConnectionWithEngine]).
 *
 * **Behavior-preserving extraction.** Every state-machine transition,
 * `writeConnection` ordering, TOFU call sequence
 * ([OpenCodeRepository.captureServerCert] /
 * [OpenCodeRepository.applyTofuDecision]), SSE-test connect timing
 * (`startSSE` callback), `onSettled` exactly-once contract, and log string
 * is byte-identical to the pre-extraction coordinator. The `TAG` is
 * intentionally still `"ConnectionCoordinator"` so logcat filters/greps that
 * keyed on the old tag keep resolving.
 *
 * **Extraction boundary:**
 *  - Probe entry points ([testConnection] / [coldStartReconnect]) +
 *    [testConnectionWithEngine] (private) + [promoteDegradedTofuIfNeeded]
 *    + the foreground-monitor `init` hook + the TOFU `tofu` delegate +
 *    `hasPendingTofuDecision` live HERE.
 *  - [ConnectionCoordinator] keeps thin public delegates
 *    ([ConnectionCoordinator.testConnection] /
 *    [ConnectionCoordinator.coldStartReconnect] /
 *    [ConnectionCoordinator.resolveTofuTrust]) so all existing call sites
 *    resolve unchanged, plus the operations the probe calls back into
 *    ([ConnectionCoordinator.loadInitialData] /
 *    [ConnectionCoordinator.startSSE] — both public, both with external
 *    callers, so they could not move).
 *  - [ConnectionCoordinator.startSSE] stays on the coordinator (it is the
 *    CP9 `ensureStarted` adapter); its TOFU-frozen guard now reads
 *    [hasPendingTofuDecision] / [pendingTofuHostPort] from this probe so the
 *    shared TOFU state has a single owner.
 *
 * **No new subpackages, no public-API change.** `internal` visibility; same
 * package `cn.vectory.ocdroid.ui.controller`.
 */
@Suppress("DEPRECATION")
internal class ConnectionHealthProbe(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val effects: SharedEffectBus,
    private val serverCompatProfile: ServerCompatProfile,
    private val currentServerGroupFp: () -> String,
    private val clock: () -> Long,
    private val identityStore: ConnectionIdentityStore?,
    private val bootstrapCoordinator: ConnectionBootstrapCoordinator?,
    private val connectionBootstrapEngine: ConnectionBootstrapEngine?,
    private val bootstrapRetryPolicy: BootstrapRetryPolicy,
    private val streamingServiceLauncher: StreamingServiceLauncher?,
    private val degradedBootstrapTerminator: DegradedBootstrapTerminator?,
    private val appLifecycleMonitor: AppLifecycleMonitor?,
    // Callbacks back into [ConnectionCoordinator] for operations that remain
    // there (both are public with external callers, so they could not move).
    // Named to match the original call sites so the relocated probe bodies
    // are byte-identical to the pre-extraction coordinator.
    private val loadInitialData: () -> Unit,
    private val startSSE: () -> Unit,
    /**
     * §resolver-single-source-of-truth (RESOLVER lane ②): the authority for the
     * effective connection URL. The legacy probe used to read
     * `settingsManager.serverUrl` directly for (a) the identity `endpointFp`
     * bind on a healthy connect and (b) the TOFU host:port + captureServerCert
     * baseUrl. Both now go through `resolve()?.url` so identity + TOFU move
     * lockstep with the URL the engine/configure path used. `null` for
     * legacy/test construction that doesn't wire the resolver — the probe then
     * treats a null resolve() as an explicit fail (no stale fallback), see the
     * two call sites below.
     */
    private val effectiveConnectionConfigResolver: cn.vectory.ocdroid.service.streaming.EffectiveConnectionConfigResolver? = null,
) {
    private var lastHealthCheckTime = 0L

    /**
     * CP2 (notify Phase-0): TOFU state single source. CC delegates to the
     * injected [bootstrapCoordinator] (production: Hilt @Singleton shared
     * with the future SessionStreamingService). The lazy fallback preserves
     * the pre-CP2 behavior for legacy/test construction that passes
     * `bootstrapCoordinator = null`.
     */
    private val tofu: ConnectionBootstrapCoordinator by lazy {
        bootstrapCoordinator ?: ConnectionBootstrapCoordinator()
    }

    init {
        appLifecycleMonitor?.let { monitor ->
            scope.launch {
                monitor.isInForeground.map { it }.distinctUntilChanged().filter { it }.collect {
                    promoteDegradedTofuIfNeeded()
                }
            }
        }
    }

    /**
     * Whether a TOFU trust decision is currently pending (the single-flight
     * freeze). Exposed (module-internal) so [ConnectionCoordinator.startSSE]'s
     * TOFU-frozen guard can read the shared TOFU state without the coordinator
     * owning the [tofu] delegate.
     */
    internal fun hasPendingTofuDecision(): Boolean = tofu.tofuState.value is TofuState.TrustPending

    /**
     * The pending TOFU hostPort, or null when none is outstanding. Exposed
     * (module-internal) so [ConnectionCoordinator.startSSE]'s freeze-guard log
     * can read the shared TOFU state.
     */
    internal fun pendingTofuHostPort(): String? = tofu.pendingTofuHostPort()

    private fun promoteDegradedTofuIfNeeded() {
        val challenge = tofu.promoteDegradedToPending() ?: return
        writeConnection {
            it.copy(
                pendingTofuCapture = challenge.capture,
                connectionPhase = ConnectionPhase.AwaitingTofuTrust,
                isConnecting = false,
                isConnected = false,
            )
        }
        scope.launch {
            when (val decision = challenge.decision.await()) {
                TofuDecision.Cancel -> {
                    tofu.clearPendingTofu()
                    writeConnection {
                        it.copy(
                            pendingTofuCapture = null,
                            connectionPhase = ConnectionPhase.Disconnected,
                            isConnecting = false,
                            isConnected = false,
                        )
                    }
                    degradedBootstrapTerminator?.terminate()
                }
                else -> {
                    repository.applyTofuDecision(challenge.hostPort, decision)
                    cn.vectory.ocdroid.ui.util.HttpImageHolder.updateSsl(repository.currentSslConfig())
                    tofu.clearPendingTofu()
                    writeConnection { it.copy(pendingTofuCapture = null) }
                    testConnection(force = true, retries = 3)
                }
            }
        }
    }

    // ── State sync helpers (mirror orchestrator.writeConnection) ──

    /**
     * §R-17 M5.1→batch2: writes the connection slice only (slice is the
     * authoritative read path). The deprecated AppState mirror write +
     * `Dispatchers.Main.immediate` Looper check were removed in R-17 batch2
     * sub-step d (Fixer C) — call sites already run on the main dispatcher
     * (viewModelScope default), and `MutableStateFlow.update` is main-thread-
     * safe by VM contract.
     */
    private fun writeConnection(transform: (ConnectionState) -> ConnectionState) {
        slices.mutateConnection(transform)
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Health-check probe with optional exponential-backoff retry.
     *
     * Throttle: skip when a health check ran <30s ago AND [force] is false
     * (preserves the pre-extraction guard verbatim). On a healthy response:
     * mark connected, run [loadInitialData], and [startSSE]. On failure (or
     * healthy=false past the retry budget): surface the error and mark
     * disconnected. [retries] extra attempts follow on failure with exponential
     * backoff (1s, 2s, 4s, ...); default callers pass retries=0 (one-shot),
     * only [coldStartReconnect] opts into retries.
     *
     * [onSettled] is invoked EXACTLY ONCE when the probe reaches a terminal
     * state — `true` on a healthy connect, `false` on failure / retry
     * exhaustion / ViewModel cancellation mid-backoff. Used by callers that
     * need a success/failure follow-up. Default is null (no callback) so
     * existing call sites keep compiling unchanged.
     *
     * `isActive` is checked so ViewModel cancellation aborts cleanly mid-backoff.
     */
    fun testConnection(force: Boolean = false, retries: Int = 0, onSettled: ((Boolean) -> Unit)? = null) {
        val now = clock()
        if (!force && now - lastHealthCheckTime < 30_000) {
            // Throttled: do not probe, do not invoke onSettled (no new info).
            return
        }
        lastHealthCheckTime = now
        // §tofu R2 round-1 fix (cgpt B3/opuser): 待 TOFU 决策期间不并发探测——pending
        // 流程负责 settle；并行 testConnection（弹窗期间的 ForceReconnect）否则可能在
        // 弹窗仍显示时写 Disconnected。入口守卫 + 下面 pendingTofuHostPort()==null 检查
        // + coldStart/startSSE 早退共同保证 single-flight。
        // CP2: TOFU state is delegated to [tofu] (ConnectionBootstrapCoordinator).
        if (hasPendingTofuDecision()) {
            DebugLog.i(TAG, "testConnection: TOFU trust pending for ${tofu.pendingTofuHostPort()} — deferring")
            return
        }
        connectionBootstrapEngine?.let { engine ->
            testConnectionWithEngine(engine, retries, onSettled)
            return
        }
        scope.launch {
            // §onSettled-exactly-once (gpt-1 🔴 / glm-1): the original post-loop
            // `onSettled?.invoke(false)` was UNREACHABLE on cancellation —
            // `delay()` / `checkHealth()` throw CancellationException when the
            // scope is cancelled, propagating out of launch and skipping the
            // post-loop line. Wrap in try/finally with a `settled` guard so
            // every exit path (success return, failure return, OR cancellation
            // mid-backoff/mid-probe) invokes onSettled exactly once. The
            // finally runs during cancellation WITHOUT swallowing the
            // CancellationException (it re-propagates after the lambda call),
            // preserving structured-concurrency teardown.
            var settled = false
            try {
                // §R-17 batch2: error is now a one-shot UiEvent. There's no
                // persistent `error` field to clear at the start of a probe —
                // any prior failure was already consumed app-wide. Connection
                // phase/isConnecting live on connectionFlow.
                writeConnection { it.copy(isConnecting = true, connectionPhase = ConnectionPhase.Connecting) }
                // NOTE: configureRepositoryForCurrentProfile() was intentionally
                // removed here. Every caller already configures the repository
                // before invoking testConnection (cold start via applySavedSettings;
                // host-switch paths call configureRepositoryForProfile directly).
                // Re-calling it here chained cancelSseForReconfigure ->
                // onHostReconfigured, which reset ForegroundCatchUpController.
                // sseHasConnectedOnce and swallowed the 15s-5min foreground gap
                // catch-up (real bug, pre-existing).
                // Retry loop: attempt 1 is always made; up to `retries` extra
                // attempts follow on failure/unhealthy with exponential backoff
                // (1s, 2s, 4s, ...). Default callers pass retries=0 (one-shot),
                // preserving the original single-attempt semantics; only
                // coldStartReconnect() opts into retries. isActive is checked so
                // ViewModel cancellation aborts cleanly mid-backoff.
                val maxAttempts = 1 + retries.coerceAtLeast(0)
                var attempt = 0
                var backoffMs = 1000L
                while (isActive) {
                    // §tofu R2 round-2 fix (cgpt): 并发连接 job 在 TOFU 待决期间 defer——
                    // 不探/不烧重试/不写终态；待决的 job 独占 settle。入口守卫只挡"新调用"，
                    // 此处挡"已 launch 但 pending 尚未置位时入队的并发 job"的迭代。
                    if (hasPendingTofuDecision()) {
                        DebugLog.i(TAG, "testConnection: deferring loop — TOFU pending for ${tofu.pendingTofuHostPort()}")
                        return@launch
                    }
                    attempt++
                    if (attempt > 1) {
                        writeConnection {
                            it.copy(connectionPhase = ConnectionPhase.ReconnectingAttempt(attempt, maxAttempts))
                        }
                    }
                    // §toctou-resolver-snapshot (RESOLVER lane ②, bgpt phase-gate):
                    // capture the connection-identity generation BEFORE the
                    // checkHealth SUSPEND point. Every host/profile reconfigure
                    // calls ConnectionIdentityStore.beginReconfigure() SYNCHRONOUSLY
                    // (before repository.configure), which bumps currentEpoch() and
                    // nulls the old identity. If the generation advances while
                    // checkHealth is suspended (a host switch interleaved), the
                    // resolved snapshot captured below is OBSOLETE and the success
                    // path MUST abort — see the guard before writeConnection{Connected}.
                    // Mirrors the engine-path isCurrent(identity) recheck in
                    // testConnectionWithEngine (~L499). null when identityStore is
                    // absent (legacy/test construction — no generation to guard).
                    val probeEpoch = identityStore?.currentEpoch()
                    val healthResult = repository.checkHealth()
                    if (healthResult.isSuccess) {
                        val health = healthResult.getOrNull()
                        if (health != null && health.healthy) {
                            // ③ populate the compat profile from the freshly-probed
                            // version before any consumer (initial-data loaders, SSE)
                            // runs, so capability flags are settled for this connect.
                            serverCompatProfile.update(health.version)
                            // §resolver-single-source-of-truth (RESOLVER lane ②):
                            // identity endpointFp MUST move WITH the url. REST/
                            // SSE reached this host via the resolver URL (the
                            // engine/configure path resolves through
                            // EffectiveConnectionConfigResolver); if identity
                            // stayed pinned to settingsManager.serverUrl, a
                            // profile/host switch would leave the epoch/identity
                            // guards keyed to the OLD url → stale directory-fetch
                            // + SSE guards misfire.
                            //
                            // null = EXPLICIT FAIL (resolver WIRED): a null
                            // resolve() means "no valid active endpoint" mid-
                            // probe. Binding a stale identity here is exactly what
                            // resurrects the token-stream-storm bug (the mirror
                            // write at HostProfileController.kt:881 exists BECAUSE
                            // direct readers bypassed the resolver), so we do NOT
                            // fall back to settingsManager.serverUrl. The probe is
                            // treated as superseded — settle false WITHOUT writing
                            // Connected — and the in-flight reconfigure re-drives
                            // it under the new identity. Mirrors the engine-path
                            // stale-identity recheck in testConnectionWithEngine
                            // (~L499).
                            //
                            // ORDERING: the check runs BEFORE the writeConnection
                            // {Connected} below so a null resolve never leaves the
                            // slice in a transient Connected-then-defer state.
                            //
                            // Legacy/test (resolver ABSENT): this whole legacy
                            // testConnection branch is only reached when
                            // connectionBootstrapEngine==null — a test/legacy
                            // condition (production ALWAYS wires the engine, which
                            // resolves through the resolver independently of this
                            // probe body). When the resolver is ALSO absent we
                            // preserve the historical settingsManager.serverUrl
                            // read so legacy constructions (incl. the shared
                            // MainViewModelTestBase harness) keep working byte-
                            // for-byte. The `?: settingsManager.serverUrl` below
                            // is therefore DEAD CODE in production (resolver is
                            // always wired → either resolvedEndpoint.url or the
                            // explicit-fail defer above).
                            val resolvedEndpoint = effectiveConnectionConfigResolver?.resolve()
                            if (effectiveConnectionConfigResolver != null && resolvedEndpoint == null) {
                                DebugLog.i(TAG, "testConnection: identity bind skipped — resolver returned no effective connection; probe superseded")
                                settled = true
                                onSettled?.invoke(false)
                                return@launch
                            }
                            // §toctou-resolver-snapshot DEFINITIVE fix (bgpt
                            // phase-gate): the epoch-CAS in
                            // ConnectionIdentityStore.bindIfCurrent is the SINGLE
                            // atomic gate between resolve and the commit. The
                            // probe captured probeEpoch before checkHealth;
                            // bindIfCurrent commits the identity ONLY IF
                            // currentEpoch() still == probeEpoch (no reconfigure
                            // superseded the snapshot) — and that epoch-CHECK +
                            // identity-COMMIT run under ONE synchronized critical
                            // section mutually exclusive with beginReconfigure,
                            // so a host switch can NEVER slip between the check
                            // and the commit. This closes the window the prior
                            // non-atomic probeEpoch-guard left open (a reconfigure
                            // between that guard and the bind could persist a
                            // stale URL).
                            //
                            // BIND-BEFORE-COMMIT: the identity is bound FIRST; the
                            // Connected slice is committed ONLY when the identity
                            // atomically committed at the captured epoch. So the
                            // connection's Connected state + identity can NEVER
                            // persist a URL inconsistent with the current epoch —
                            // no post-check window exists. If the CAS rejects
                            // (superseded), defer: settle false, NO Connected,
                            // identity NOT persisted; the new generation's probe
                            // re-runs under the new URL.
                            //
                            // Legacy/test (identityStore ABSENT): no CAS — skip
                            // the bind (the old identityStore?.bind was a no-op
                            // for a null receiver anyway) and fall through to
                            // writeConnection, preserving byte-identical legacy
                            // behaviour. The `?: settingsManager.serverUrl` arm
                            // stays dead code in production (resolver wired).
                            val identity = identityStore
                            if (identity != null && probeEpoch != null) {
                                val bound = identity.bindIfCurrent(
                                    serverGroupFp = currentServerGroupFp(),
                                    normalizedWorkdir = settingsManager.currentWorkdir ?: "",
                                    endpointFp = resolvedEndpoint?.url ?: settingsManager.serverUrl,
                                    expectedEpoch = probeEpoch,
                                )
                                if (bound == null) {
                                    DebugLog.i(
                                        TAG,
                                        "testConnection: probe superseded — epoch-CAS bind rejected (generation advanced $probeEpoch → ${identity.currentEpoch()}); aborting commit (no Connected, identity not persisted)",
                                    )
                                    settled = true
                                    onSettled?.invoke(false)
                                    return@launch
                                }
                            }
                            // Identity atomically bound at the captured epoch (or
                            // legacy/test path with no identityStore). NOW commit
                            // Connected — loadInitialData's directory fan-out +
                            // launchSseCollection's collector read the just-bound
                            // identity (FGS spec §2 step 5: bind new collector to
                            // new identity).
                            writeConnection {
                                it.copy(
                                    isConnected = true,
                                    serverVersion = health.version,
                                    isConnecting = false,
                                    connectionPhase = ConnectionPhase.Connected,
                                    isSlimActive = serverCompatProfile.slimConnection,
                                )
                            }
                            loadInitialData()
                            startSSE()
                            // remove-message-persistence Task 5: the daily
                            // cache sweep that used to fire-and-forget here
                            // was removed together with the maintenance
                            // coordinator (SQLite persistence layer deletion).
                            settled = true
                            onSettled?.invoke(true)
                            return@launch
                        }
                        // Healthy=false: surface the version if present but keep
                        // retrying (server may still be coming up on cold start).
                        if (health != null) {
                            serverCompatProfile.update(health.version)
                            writeConnection { it.copy(serverVersion = health.version) }
                        }
                    }
                    // §tofu R2: SSL/cert error against an endpoint with NO pin
                    // yet → capture the leaf cert and prompt the user. This
                    // replaces the legacy "allowInsecure=true → trust-all"
                    // downgrade with SSH-style trust-on-first-use: the user
                    // sees the actual leaf (subject / issuer / expiry / SPKI)
                    // and chooses Accept once / Trust / Cancel. Security is
                    // the SPKI pin (the dialog just decides whether to write
                    // it), NOT a blanket trust-all.
                    //
                    // Guards: only prompt when
                    //   (a) the failure is SSL/cert-shaped (NOT a generic
                    //       network/HTTP error — those surface the usual way),
                    //   (b) [hostPort] is resolvable,
                    //   (c) no pin yet exists (already-trusted endpoints
                    //       never re-prompt — the pin mismatch path stays a
                    //       hard failure for security),
                    //   (d) we are not already pending a decision (avoid
                    //       stacking prompts on a re-entrant coldStart).
                    val exc = healthResult.exceptionOrNull()
                    val rootCause = generateSequence(exc) { it.cause }
                        // §tofu R2 round-1 fix (cgpt B2): 扩到 SSLException（覆盖
                        // SSLHandshakeException + SSLPeerUnverifiedException——OkHttp 把
                        // 主机名不匹配报成后者），使任何 TLS 校验失败都进 TOFU（全口径，grill Q2=a）。
                        .firstOrNull { it is SSLException || it is CertificateException }
                    // §resolver-single-source-of-truth (RESOLVER lane ②): TOFU
                    // host:port identity MUST move WITH the url. If REST uses the
                    // resolver URL but the TOFU pin lookup stayed on
                    // settingsManager.serverUrl, the pin would be keyed to the
                    // wrong host:port and the epoch/identity guards misfire.
                    //
                    // Resolver WIRED + null resolve() = EXPLICIT FAIL: no valid
                    // endpoint → resolvedUrl/hostPort null → the `hostPort != null`
                    // guard below skips TOFU capture and falls through to the
                    // normal connection-failure path (the original SSL error
                    // surfaces as Disconnected + UiEvent.Error). NOT a stale
                    // fallback.
                    //
                    // Resolver ABSENT (legacy/test — see the :303 comment): this
                    // branch is unreachable in production; preserve the historical
                    // settingsManager.serverUrl read so legacy harnesses stay
                    // byte-identical. Production always wires the resolver, so the
                    // `else settingsManager.serverUrl` arm is dead code there.
                    val resolver = effectiveConnectionConfigResolver
                    val resolvedUrl: String? = if (resolver != null) {
                        resolver.resolve()?.url
                    } else {
                        settingsManager.serverUrl
                    }
                    val hostPort = resolvedUrl?.let { hostPortFromUrl(it) }
                    if (rootCause != null && hostPort != null && resolvedUrl != null &&
                        repository.pinnedSpkiFor(hostPort) == null &&
                        !hasPendingTofuDecision() &&
                        // §tofu fix: mTLS 主机不进 TOFU——mTLS 优先级会忽略 TOFU pin，
                        // 弹"信任"无效且误导；mTLS 服务器证书失败应直接作连接错误呈现。
                        !repository.isMutualTlsActive()
                    ) {
                        // §tofu R2 round-1 fix (cgpt B3/opuser): 在 capture 探测【之前】
                        // 占住 pending（single-flight）。并发的 ForceReconnect 驱动的
                        // testConnection 会因入口守卫 + 此处的 pendingTofuHostPort()==null
                        // 检查而 defer，不再二次 capture 覆盖本循环的 deferred 致其孤儿。
                        // CP2: delegated to [tofu] (ConnectionBootstrapCoordinator).
                        tofu.setPendingTofu(hostPort)
                        try {
                            val capture = repository.captureServerCert(resolvedUrl, hostPort, clientCert = null)
                            if (capture != null) {
                                // Enter pending-trust: SUSPEND the retry loop on a
                                // CompletableDeferred that [resolveTofuTrust] completes.
                                // While pending, the freeze guards in [coldStartReconnect] /
                                // [startSSE] + the testConnection entry guard early-return.
                                val deferred = kotlinx.coroutines.CompletableDeferred<TofuDecision>()
                                tofu.setTofuDecision(deferred)
                                writeConnection {
                                    it.copy(
                                        connectionPhase = ConnectionPhase.AwaitingTofuTrust,
                                        pendingTofuCapture = capture
                                    )
                                }
                                try {
                                    val decision = deferred.await()
                                    when (decision) {
                                        is TofuDecision.AcceptOnce,
                                        is TofuDecision.Trust -> {
                                            // 写 pin + 重建 live 客户端（applyTofuDecision 对当
                                            // 前 host 重建）→ 下一次 checkHealth 解析为 TofuPinned
                                            // （SPKI 匹配 → 握手成功）。不消耗 retry、不延迟。
                                        repository.applyTofuDecision(hostPort, decision)
                                        // §tofu R2 round-2 fix (cgpt/groker): applyTofuDecision 重建了
                                        // REST/SSE/command，但图片客户端独立——同步刷新，否则新信任的自签
                                        // host 的 markdown 图片仍走 SystemDefault 直到下次 reconfigure。
                                        cn.vectory.ocdroid.ui.util.HttpImageHolder.updateSsl(repository.currentSslConfig())
                                        writeConnection {
                                            it.copy(connectionPhase = ConnectionPhase.Connecting)
                                        }
                                        continue
                                        }
                                        TofuDecision.Cancel -> {
                                            // User declined — terminal failure.
                                            writeConnection {
                                                it.copy(
                                                    isConnected = false,
                                                    isConnecting = false,
                                                    connectionPhase = ConnectionPhase.Disconnected
                                                )
                                            }
                                            settled = true
                                            onSettled?.invoke(false)
                                            return@launch
                                        }
                                    }
                                } finally {
                                    // §tofu R2 round-1 fix (opuser): 每条路径都清弹窗 + decision
                                    // 引用——决策完成 OR 协程在 await 期间被取消（外层 finally 清
                                    // pendingTofuHostPort，防 coldStart/startSSE 永久冻结）。
                                    // CP2: delegated to [tofu].
                                    tofu.setTofuDecision(null)
                                    writeConnection { it.copy(pendingTofuCapture = null) }
                                }
                            }
                            // capture == null (unreachable / no cert presented):
                            // fall through to the normal failure path below — the
                            // original SSLHandshakeException is surfaced verbatim.
                        } finally {
                            // CP2: delegated full reset to [tofu] (clears hostPort + decision).
                            tofu.clearPendingTofu()
                        }
                    }
                    if (attempt >= maxAttempts || !isActive) {
                        // §tofu R2 round-2 fix (cgpt): 另一个 job 正在 await TOFU 决策时，
                        // 不宣布终态 Disconnected（否则弹窗仍显示却已断开，UI 不一致）——defer。
                        if (hasPendingTofuDecision()) {
                            DebugLog.i(TAG, "testConnection: deferring terminal — TOFU pending for ${tofu.pendingTofuHostPort()}")
                            return@launch
                        }
                        // §R-17 batch2: error is now a one-shot UiEvent on
                        // _uiEvents (consumed app-wide). Connection fields stay
                        // on connectionFlow. Intermediate state legal (error
                        // emitted before phase flips to "disconnected" — both
                        // still describe the same failure).
                        healthResult.exceptionOrNull()?.let { e ->
                            effects.tryEmitUiEvent(UiEvent.Error(R.string.error_connection_failed, listOf(errorMessageOrFallback(e, "unknown error"))))
                        }
                        writeConnection {
                            it.copy(
                                isConnected = false,
                                isConnecting = false,
                                connectionPhase = ConnectionPhase.Disconnected
                            )
                        }
                        settled = true
                        onSettled?.invoke(false)
                        return@launch
                    }
                    delay(backoffMs)
                    backoffMs *= 2
                }
                // Loop exited because isActive flipped false — terminal failure.
                settled = true
                onSettled?.invoke(false)
            } finally {
                // Cancellation path (CancellationException propagated out of
                // delay/checkHealth): the body's settled flag stayed false.
                // Invoke the callback so the caller's exactly-once contract
                // holds; the CancellationException re-propagates after this
                // finally (we do NOT catch/swallow it). refresh-path callers
                // treat false as a no-op (their lambda only acts on true).
                if (!settled) onSettled?.invoke(false)
            }
        }
    }

    private fun testConnectionWithEngine(
        engine: ConnectionBootstrapEngine,
        retries: Int,
        onSettled: ((Boolean) -> Unit)?,
    ) {
        scope.launch {
            var settled = false
            try {
                writeConnection { it.copy(isConnecting = true, connectionPhase = ConnectionPhase.Connecting) }
                val delays = bootstrapRetryPolicy.delaysMs.take(retries.coerceAtLeast(0))
                var attempt = 0
                while (true) {
                    when (val outcome = engine.bootstrap()) {
                        is ConnectionBootstrapOutcome.Success -> {
                            loadInitialData()
                            // D5-2 (#4): CC state during the ownership window —
                            // from engine success until the terminal ownership
                            // result, stay Connecting (do NOT enter Connected or
                            // Disconnected mid-window). `ensureStarted` is
                            // suspend; CC is parked here while the launcher
                            // awaits Stage 1 acceptance (5s) + Stage 2 terminal.
                            val ownership = streamingServiceLauncher?.ensureStarted(outcome.identity)
                                ?: OwnershipStartResult.Refused(
                                    cn.vectory.ocdroid.service.OwnershipRefusal.ServiceStopped,
                                )
                            // D5-2 (#4): identity recheck BEFORE writing Connected.
                            // A newer epoch may have started during the (possibly
                            // long) ownership wait — this stale-result branch
                            // settles false WITHOUT writing Disconnected (a
                            // newer epoch may already be connecting).
                            if (identityStore != null && !identityStore.isCurrent(outcome.identity)) {
                                settled = true
                                onSettled?.invoke(false)
                                return@launch
                            }
                            if (ownership is OwnershipStartResult.Ready &&
                                ownership.identity == outcome.identity
                            ) {
                                writeConnection {
                                    it.copy(
                                        isConnected = true,
                                        isConnecting = false,
                                        serverVersion = outcome.health.version,
                                        connectionPhase = ConnectionPhase.Connected,
                                        isSlimActive = serverCompatProfile.slimConnection,
                                    )
                                }
                                // remove-message-persistence Task 5: the
                                // daily cache sweep that used to fire-and-forget
                                // here was removed together with the
                                // maintenance coordinator.
                                settled = true
                                onSettled?.invoke(true)
                                return@launch
                            }
                            writeConnection {
                                it.copy(
                                    isConnected = false,
                                    isConnecting = false,
                                    connectionPhase = ConnectionPhase.Disconnected,
                                )
                            }
                            settled = true
                            onSettled?.invoke(false)
                            return@launch
                        }
                        is ConnectionBootstrapOutcome.TofuNeedsActivity -> {
                            writeConnection {
                                it.copy(
                                    isConnected = false,
                                    isConnecting = false,
                                    pendingTofuCapture = outcome.capture,
                                    connectionPhase = ConnectionPhase.AwaitingTofuTrust,
                                )
                            }
                            settled = true
                            onSettled?.invoke(false)
                            return@launch
                        }
                        is ConnectionBootstrapOutcome.Failed -> {
                            if (attempt >= delays.size) {
                                effects.tryEmitUiEvent(
                                    UiEvent.Error(
                                        R.string.error_connection_failed,
                                        listOf(errorMessageOrFallback(outcome.error, "unknown error")),
                                    ),
                                )
                                writeConnection {
                                    it.copy(
                                        isConnected = false,
                                        isConnecting = false,
                                        connectionPhase = ConnectionPhase.Disconnected,
                                    )
                                }
                                settled = true
                                onSettled?.invoke(false)
                                return@launch
                            }
                            delay(delays[attempt++])
                        }
                    }
                }
            } finally {
                if (!settled) onSettled?.invoke(false)
            }
        }
    }

    /**
     * Cold-start entry point: force a connection check with up to 3 retries
     * (exponential backoff 1s/2s/4s) so a slow-to-wake server (common when
     * the OpenCode server itself is bootstrapping) still comes up instead of
     * stranding the user on the disconnected empty state. Callers:
     * MainActivity's cold-start LaunchedEffect, [resetLocalDataAndResync],
     * and the home server-connection popup's force-refresh (T7 F1:
     * `ConnectionViewModel.coldStartReconnect()` ←
     * `SessionsScreen.onRefresh`).
     *
     * §tofu R2: FROZEN while a TOFU trust dialog is pending — a reconnect
     * race against the in-flight decision would either burn retries or fork
     * two capture probes. The user's [resolveTofuTrust] clears the pending
     * state and the loop re-probes; cold-start then proceeds naturally.
     */
    fun coldStartReconnect() {
        if (hasPendingTofuDecision()) {
            DebugLog.i(TAG, "coldStartReconnect: frozen — TOFU trust pending for ${tofu.pendingTofuHostPort()}")
            return
        }
        testConnection(force = true, retries = 3)
    }

    /**
     * §tofu R2: applies the user's TOFU trust decision for the pending
     * endpoint. Called by the UI (via [cn.vectory.ocdroid.ui.ConnectionViewModel]
     * → [ConnectionCoordinator.resolveTofuTrust]) when the user taps Accept
     * once / Trust / Cancel in [cn.vectory.ocdroid.ui.settings.TofuTrustDialog].
     * Completes the deferred the [testConnection] retry loop is awaiting; the
     * loop then writes the pin (Accept/Trust) and re-probes, or settles false
     * (Cancel). No-op when no TOFU prompt is pending.
     *
     * CP2 (notify Phase-0): delegates to [ConnectionBootstrapCoordinator.
     * resolveTofuTrust] (FGS spec §10). [ConnectionCoordinator.resolveTofuTrust]
     * forwards here — ConnectionViewModel / external callers see the same
     * signature + behavior.
     */
    fun resolveTofuTrust(decision: TofuDecision) {
        tofu.resolveTofuTrust(decision)
    }

    companion object {
        // Intentionally kept as "ConnectionCoordinator" (not the class name) so
        // logcat filters / grep patterns that keyed on the pre-extraction tag
        // keep resolving. Behavior-preserving.
        private const val TAG = "ConnectionCoordinator"
    }
}
