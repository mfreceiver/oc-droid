package cn.vectory.ocdroid.di

import cn.vectory.ocdroid.data.api.TokenStreamClient
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.ui.SkeletonReloadCoordinator
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.controller.ComposerController
import cn.vectory.ocdroid.ui.controller.ConnectionCoordinator
import cn.vectory.ocdroid.ui.controller.ForegroundCatchUpController
import cn.vectory.ocdroid.ui.controller.ForegroundSessionTreeHydrator
import cn.vectory.ocdroid.ui.controller.HostProfileController
import cn.vectory.ocdroid.ui.controller.SessionSwitcher
import cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator
import cn.vectory.ocdroid.ui.controller.UnreadSoakController
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.ui.controller.sse.TokenFrameCommitContext
import cn.vectory.ocdroid.ui.controller.sse.TokenStreamConnection
import cn.vectory.ocdroid.ui.controller.sse.TokenStreamCoordinator
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.TrafficTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Named
import javax.inject.Singleton

/**
 * R-19 Sprint 3 P2-5: Hilt bindings for the 5 application-scoped controllers.
 *
 * **Why @Provides (not `@Inject constructor` on each controller)**: the
 * controllers are declared `internal class` to keep them module-private
 * (they are implementation details of the `ui` package, not part of the
 * app's public API surface). Kotlin `internal` classes compile to
 * `public` bytecode but Dagger/Hilt's `@Inject constructor` is documented
 * to require a genuinely `public` Kotlin class — using `@Provides` here
 * keeps the `internal` visibility while still giving Hilt a binding to
 * hand out.
 *
 * **Why @Singleton (not VM-scoped)**: the 5 controllers own app-lifetime
 * state (SSE feeds, delta buffers, the foreground catch-up state machine,
 * the host profile + tunnel credential cache). They MUST outlive any
 * individual ViewModel (which is cleared on configuration changes /
 * navigation). AppCore itself remains a @Singleton and continues to inject
 * these — it just no longer constructs them internally, so the 5
 * controllers can ALSO be injected directly into the per-domain VMs
 * (R-19 P2-5 precise injection) without creating duplicates.
 *
 * Each provider is a thin pass-through that wires the same deps the
 * previous inline `XxxController(...)` construction inside
 * [cn.vectory.ocdroid.ui.AppCore] used — pure DI relocation, zero behaviour
 * change. The [UiApplicationScope] (Main.immediate) is shared with AppCore
 * so the controllers' launches stay on the same dispatcher they always did.
 */
@Module
@InstallIn(SingletonComponent::class)
object ControllerModule {

    /**
     * R-20 Phase 1: zero-arg lambda returning the CURRENT host profile's
     * serverGroupFp. Injected into every controller/helper that emits
     * [cn.vectory.ocdroid.ui.controller.ControllerEffect.VerifyAndHydrate]
     * / [cn.vectory.ocdroid.ui.controller.ControllerEffect.EvictSession]
     * / [cn.vectory.ocdroid.ui.controller.ControllerEffect.EvictGroup] so
     * they all derive the fp the SAME way — none of them can read the
     * HostProfileStore directly without a constructor dep, and the
     * round-3 consensus (plan §3 freegpt #3 + maxer) was "one authoritative
     * provider, not each controller re-deriving it".
     *
     * `.ifBlank { id }` is the nonblank-invariant fallback
     * (see [HostProfile.serverGroupFp] + [HostProfileStore.decodeProfiles]
     * normalize step — legacy JSON that predates Phase 0 normalizes blank
     * → id on read, so this is belt-and-braces for a corrupt row that
     * skipped normalization).
     */
    @Provides
    @Singleton
    @Named("currentServerGroupFp")
    fun provideCurrentServerGroupFp(
        hostProfileStore: HostProfileStore
    ): () -> String = {
        val profile = hostProfileStore.currentProfile()
        profile.serverGroupFp.ifBlank { profile.id }
    }

    @Provides
    @Singleton
    fun provideForegroundCatchUpController(
        appLifecycleMonitor: AppLifecycleMonitor,
        @UiApplicationScope appScope: CoroutineScope,
        store: SharedStateStore,
        settingsManager: SettingsManager,
        effectBus: SharedEffectBus,
    ): ForegroundCatchUpController = ForegroundCatchUpController(
        appLifecycleMonitor = appLifecycleMonitor,
        scope = appScope,
        store = store,
        settingsManager = settingsManager,
        effects = effectBus,
        sseEffectivelyOff = {
            // §defect-A-1A: SSE is effectively OFF when the debug toggle refuses
            // it OR the connection is terminally down (retry exhausted / network
            // lost). Transient phases (Connecting/Reconnecting/Connected) will
            // deliver server.connected soon, so they are NOT "off".
            settingsManager.sseDisabled || run {
                val phase = store.connectionFlow.value.connectionPhase
                phase is ConnectionPhase.SseDisabled || phase is ConnectionPhase.Disconnected
            }
        },
    )

    /**
     * §unread-soak: provides the foreground sweep controller. @Singleton +
     * appScope (Main.immediate) — the sweep loop subscribes to
     * [AppLifecycleMonitor.isInForeground] in its init and self-starts/stops
     * on foreground transitions; no production caller needs to invoke it. The
     * controller is constructed for its side-effecting init (the launchIn),
     * so it MUST be injected somewhere reachable at app start (AppCore) to
     * actually begin sweeping — the binding itself just hands out the
     * singleton.
     */
    @Provides
    @Singleton
    fun provideUnreadSoakController(
        appLifecycleMonitor: AppLifecycleMonitor,
        @UiApplicationScope appScope: CoroutineScope,
        store: SharedStateStore,
        repository: OpenCodeRepository,
        effectBus: SharedEffectBus,
    ): UnreadSoakController = UnreadSoakController(
        appLifecycleMonitor = appLifecycleMonitor,
        scope = appScope,
        store = store,
        requestTreeHydration = ForegroundSessionTreeHydrator(repository, store, appScope)::request,
        requestStatusRefresh = { completion ->
            effectBus.tryEmitEffect(ControllerEffect.LoadSessionStatusWithCompletion(completion))
        },
    )

    @Provides
    @Singleton
    fun provideComposerController(
        store: SharedStateStore,
        settingsManager: SettingsManager,
        hostProfileStore: HostProfileStore,
    ): ComposerController = ComposerController(
        store = store,
        settingsManager = settingsManager,
        hostProfileStore = hostProfileStore,
    )

    @Provides
    @Singleton
    fun provideSessionSwitcher(
        store: SharedStateStore,
        settingsManager: SettingsManager,
        repository: OpenCodeRepository,
        effectBus: SharedEffectBus,
        @Named("currentServerGroupFp") currentServerGroupFp: () -> String,
    ): SessionSwitcher = SessionSwitcher(
        store = store,
        settingsManager = settingsManager,
        repository = repository,
        effects = effectBus,
        currentServerGroupFp = currentServerGroupFp,
    )

    @Provides
    @Singleton
    fun provideHostProfileController(
        @UiApplicationScope appScope: CoroutineScope,
        store: SharedStateStore,
        hostProfileStore: HostProfileStore,
        repository: OpenCodeRepository,
        settingsManager: SettingsManager,
        trafficTracker: TrafficTracker,
        effectBus: SharedEffectBus,
        @Named("currentServerGroupFp") currentServerGroupFp: () -> String,
        identityStore: cn.vectory.ocdroid.service.identity.ConnectionIdentityStore,
        reconfigureBarrier: cn.vectory.ocdroid.service.ConnectionReconfigureBarrier,
        effectiveConnectionConfigResolver: cn.vectory.ocdroid.service.streaming.EffectiveConnectionConfigResolver,
        slimLocalResetCoordinator: cn.vectory.ocdroid.data.repository.SlimLocalResetCoordinator,
    ): HostProfileController = HostProfileController(
        scope = appScope,
        slices = store.slices,
        hostProfileStore = hostProfileStore,
        repository = repository,
        settingsManager = settingsManager,
        trafficTracker = trafficTracker,
        effects = effectBus,
        currentServerGroupFp = currentServerGroupFp,
        identityStore = identityStore,
        reconfigureBarrier = reconfigureBarrier,
        effectiveConnectionConfigResolver = effectiveConnectionConfigResolver,
        slimLocalResetCoordinator = slimLocalResetCoordinator,
    )

    @Provides
    @Singleton
    fun provideSessionSyncCoordinator(
        @UiApplicationScope appScope: CoroutineScope,
        store: SharedStateStore,
        settingsManager: SettingsManager,
        effectBus: SharedEffectBus,
        @Named("currentServerGroupFp") currentServerGroupFp: () -> String,
        identityStore: cn.vectory.ocdroid.service.identity.ConnectionIdentityStore,
        statusAggregatorInput: cn.vectory.ocdroid.service.status.StatusAggregatorInput,
        repository: OpenCodeRepository,
        skeletonReloadCoordinator: SkeletonReloadCoordinator,
    ): SessionSyncCoordinator = SessionSyncCoordinator(
        scope = appScope,
        slices = store.slices,
        settingsManager = settingsManager,
        effects = effectBus,
        currentServerGroupFp = currentServerGroupFp,
        // remove-message-persistence Task 6: the prior `cacheRepository`
        // argument (R-20 Phase 1 C4, wired for the message.updated
        // appendMessageIfSessionCached path) was deleted together with the
        // CacheRepository surface. The new-insert append now reaches
        // SessionSwitcher.appendMessageIfCached via the effect bus
        // (ControllerEffect.AppendMessageToCache → AppCore).
        // CP1 (notify Phase-0): single connection-identity store.
        identityStore = identityStore,
        // CP4 (notify Phase-0): feed the authoritative status aggregator on
        // every `session.status` SSE event (the SSE branch resolves
        // sessionId→workdir via SessionTree.allSessionsById, builds the
        // composite key, and calls applySseStatus with clock()).
        statusAggregatorInput = statusAggregatorInput,
        // Cluster A / Phase 2: runtime watermark-resync capability + repository
        // for session.digest / slim questions / cold-start snapshot fold.
        // supportsWatermarkResync is a thunk so host-profile switches that flip
        // repository.supportsWatermarkResync are observed without reconstructing
        // SSC. ι-Q3b: reads the OCR forwarder (ι-A) in place of the prior raw
        // mode flag — byte-for-byte equal today (both = slimConnection/slim mode).
        supportsWatermarkResync = { repository.supportsWatermarkResync },
        repository = repository,
        reconcileDispatcher = Dispatchers.Default,
        // lite-v2-dev (plan §4.2/§4.5): SlimFullReconciler 全量退役——digest /
        // reconnect 不再走 /full 单条 reconcile，改为 skeleton reload（见
        // [SkeletonReloadCoordinator.onDigestChange] / [requestReload]）。SSC 的
        // slimFullReconciler 形参默认 null（slim 触发路径 no-op）；新的权威同步
        // 由 skeletonReloadCoordinator 承担。
        skeletonReloadCoordinator = skeletonReloadCoordinator,
    )

    /**
     * §Stage-D2 §5.8/§5.9: provides the [TokenStreamCoordinator] singleton.
     *
     * # streamProvider wiring
     *
     * Resolves the LIVE published endpoint at call time (when `open(sid, dir)`
     * reaches the provider) from the resolver's bundle-aware overload — NOT
     * from a captured snapshot or direct settings read. The returned endpoint
     * carries the exact bundle identity, endpoint fingerprint, and generation;
     * the client is built from that same bundle. A null resolve throws
     * (explicit fail) and never falls back to stale settings.
     *
     * # triggerSinceFetch wiring (S2 — AUTHORITATIVE)
     *
     * `auth=true` → [SessionSyncCoordinator.ReconcileMode.RESYNC] (the
     * resync sweep is authoritative: `isAuthoritativeSlimMerge` returns true
     * for RESYNC mode → `MessagesMerged(authoritative=true)` → the fetched
     * content is the final view, clearing any stale streamOwned overlay).
     * `auth=false` → [SessionSyncCoordinator.ReconcileMode.DIGEST_FOCUS]
     * (skeleton merge — preserves streamOwned so an in-flight token stream
     * keeps its ownership).
     *
     * Launched on [appScope] because `reconcileSession` is a suspend function
     * but the coordinator's callback signature is `(sid, auth) -> Unit`.
     */
    @Provides
    @Singleton
    fun provideTokenStreamCoordinator(
        @UiApplicationScope appScope: CoroutineScope,
        store: SharedStateStore,
        // §tokenstream-mtls-fix: was `clientFactory: OkHttpClientFactory` — the Hilt
        // singleton whose own SslConfigFactory never received configureClientCert, so
        // its sslConfigFor() fell back to SystemDefault → "Trust anchor not found"
        // under mTLS + slim. The repository owns the mTLS-loaded clientFactory (same
        // one REST/SSE use); route the token stream through it via the new
        // [OpenCodeRepository.tokenStreamClient]. (The @Singleton OkHttpClientFactory /
        // SslConfigFactory bindings are now orphaned here — left in place for the
        // Option A follow-up that unifies ownership; do NOT delete.)
        repository: OpenCodeRepository,
        @Suppress("UNUSED_PARAMETER") sessionSyncCoordinator: SessionSyncCoordinator,
        bundleEndpointResolver: cn.vectory.ocdroid.service.streaming.BundleEndpointResolver,
        settingsManager: cn.vectory.ocdroid.util.SettingsManager,
        // lite-v2-dev (plan §4.2): SlimFullReconciler 全量退役。三个 commit hook
        // （dedupPartRevision / onMessagePartRemoved / onMessageRemoved）+ TriggerSinceFetch
        // 全部重接线到 [SkeletonReloadCoordinator]——digest/done/resync/part.removed
        // 统一走权威窗口 skeleton reload（一条同步路径，见 plan §2.2）。
        skeletonReloadCoordinator: SkeletonReloadCoordinator,
    ): TokenStreamCoordinator {
        synchronized(repository) {
            repository.onBundlePublished = { generation, endpointFp ->
                store.dispatch(AppAction.BundlePublished(generation, endpointFp))
            }
            // The repository creates its initial generation-0 bundle before
            // this callback can be installed. Publish that baseline now so
            // bundle-bound reducer actions have a valid StoreState stamp
            // before the first host reconfiguration or token frame.
            repository.currentClientBundle()?.let { bundle ->
                store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))
            }
        }
        val streamConnectionProvider: (String, String?) -> TokenStreamConnection = { sid, directory ->
            val resolved = bundleEndpointResolver.resolveEndpoint(repository)
                ?: error("token-stream open($sid) with no published effective endpoint")
            val bundle = resolved.bundle
            check(bundle.generation == resolved.bundleGeneration) {
                "token-stream endpoint generation drift for $sid"
            }
            check(bundle.endpointFp == resolved.endpointFp) {
                "token-stream endpoint fingerprint drift for $sid"
            }
            TokenStreamConnection(
                flow = TokenStreamClient(
                    repository.tokenStreamClient(bundle),
                    resolved.baseUrl,
                ).connect(sid, directory),
                bundle = bundle,
            )
        }

        // lite-v2-dev: build the three production hooks against the LIVE store +
        // skeletonReloadCoordinator. Token hub per-frame revision 保证让 dedup 成为
        // no-op；part.removed / message.removed 走权威 reload / 直接 dispatch。
        val hooks = tokenStreamProductionHooks(
            store = store,
            skeletonReloadCoordinator = skeletonReloadCoordinator,
            appScope = appScope,
        )

        return TokenStreamCoordinator(
            scope = appScope,
            slices = store.slices,
            streamProvider = { sid, directory ->
                streamConnectionProvider(sid, directory).flow
            },
            streamConnectionProvider = streamConnectionProvider,
            bundleCommitLock = repository,
            currentBundleProvider = { repository.currentClientBundle() },
            // lite-v2-dev (plan §4.2): TriggerSinceFetch → skeleton reload
            // （终态文本 / resync 收敛统一走权威窗口，不再走 reconcileSession）。
            triggerSinceFetch = { sid, _ ->
                skeletonReloadCoordinator.requestReload(sid, 50)
            },
            // §sse-disabled-debug-toggle: gate per-session token stream on the DEBUG
            // flag (REST-only mode). Read live so toggling takes effect on next open.
            sseDisabled = { settingsManager.sseDisabled },
            dedupPartRevision = hooks.dedupPartRevision,
            onMessagePartRemoved = hooks.onMessagePartRemoved,
            onMessageRemoved = hooks.onMessageRemoved,
        )
    }

    @Provides
    @Singleton
    fun provideConnectionCoordinator(
        @UiApplicationScope appScope: CoroutineScope,
        store: SharedStateStore,
        repository: OpenCodeRepository,
        settingsManager: SettingsManager,
        effectBus: SharedEffectBus,
        serverCompatProfile: ServerCompatProfile,
        @Named("currentServerGroupFp") currentServerGroupFp: () -> String,
        identityStore: cn.vectory.ocdroid.service.identity.ConnectionIdentityStore,
        bootstrapCoordinator: cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator,
        streamingServiceLauncher: cn.vectory.ocdroid.service.StreamingServiceLauncher,
        streamingLifecycleCoordinator: cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator,
        connectionBootstrapEngine: cn.vectory.ocdroid.service.streaming.ConnectionBootstrapEngine,
        bootstrapRetryPolicy: cn.vectory.ocdroid.service.streaming.BootstrapRetryPolicy,
        appLifecycleMonitor: AppLifecycleMonitor,
        degradedBootstrapTerminator: cn.vectory.ocdroid.service.DegradedBootstrapTerminator,
        tokenStreamCoordinator: TokenStreamCoordinator,
        effectiveConnectionConfigResolver: cn.vectory.ocdroid.service.streaming.EffectiveConnectionConfigResolver,
    ): ConnectionCoordinator = ConnectionCoordinator(
        scope = appScope,
        slices = store.slices,
        repository = repository,
        settingsManager = settingsManager,
        effects = effectBus,
        serverCompatProfile = serverCompatProfile,
        currentServerGroupFp = currentServerGroupFp,
        identityStore = identityStore,
        // CP2 (notify Phase-0): delegate TOFU state to the shared bootstrap
        // coordinator (FGS spec §10). CC's public TOFU surface is unchanged.
        bootstrapCoordinator = bootstrapCoordinator,
        // CP9 (notify Phase-0 switchover): CC's startSSE now calls the
        // streaming Service launcher (the atomic ownership switch); the
        // Service runs the §5 bootstrap + the SSE collector lives in
        // ServiceSseConnectionOwner. CC NEVER calls repository.connectSSE.
        streamingServiceLauncher = streamingServiceLauncher,
        // CP9 (notify Phase-0 switchover): CC's cancelSse /
        // cancelSseForReconfigure now route through the lifecycle
        // coordinator's onDisconnect (§4.1 disconnect → L3 teardown); the
        // Service observes the commands and disconnects its owner.
        streamingLifecycleCoordinator = streamingLifecycleCoordinator,
        connectionBootstrapEngine = connectionBootstrapEngine,
        bootstrapRetryPolicy = bootstrapRetryPolicy,
        appLifecycleMonitor = appLifecycleMonitor,
        degradedBootstrapTerminator = degradedBootstrapTerminator,
        // §Stage-D2: the token-stream coordinator. CC hooks close(sid) on
        // cancelSse / cancelSseForReconfigure (background / host-switch).
        // Busy-open is hooked in ChatViewModel.loadMessages.
        tokenStreamCoordinator = tokenStreamCoordinator,
        // RESOLVER lane ②: forwarded to ConnectionHealthProbe so its legacy
        // testConnection path (identity endpointFp + TOFU host:port) resolves
        // the URL through the single authority, matching the engine + token-
        // stream factory.
        effectiveConnectionConfigResolver = effectiveConnectionConfigResolver,
    )

    /**
     * lite-v2-dev (plan §4.2/§4.3): provides the [SkeletonReloadCoordinator]
     * singleton — the single authoritative sync path. digest / done:true /
     * resync / busy→idle / part.removed / message.removed 全部收敛到
     * `requestReload(sid, limit)` → sidecar skeleton 单页 → 权威窗口 diff merge
     * 进 chat slice（见 [SkeletonReloadCoordinator]）。
     *
     * # 取代旧的全量 reconcile 路径
     *
     * 旧的 /full reconcile 单条路径已全量退役（plan §4.1）。
     * 该协调器不再依赖 SlimCommitToken / watermark / reconfigure 协议——skeleton
     * 端点每次 re-GET upstream opencode，不读 sidecar 内存（read-after-event 见
     * plan §4.3.2）。
     *
     * # 注入依赖
     *
     *  - [UiApplicationScope] appScope：reload / watchdog / debounce 协程的宿主。
     *  - repository：`getSlimapiMessagesSkeleton` fetch 原语（plan §4.3.7）。
     *  - store.slices：chat slice 读写 + sessionLock。
     *  - currentServerGroupFp：ReloadIdentity launch 时捕获（防 cross-group 误写）。
     */
    @Provides
    @Singleton
    fun provideSkeletonReloadCoordinator(
        @UiApplicationScope appScope: CoroutineScope,
        store: SharedStateStore,
        repository: OpenCodeRepository,
        @Named("currentServerGroupFp") currentServerGroupFp: () -> String,
    ): SkeletonReloadCoordinator = SkeletonReloadCoordinator(
        scope = appScope,
        repository = repository,
        slices = store.slices,
        currentServerGroupFp = currentServerGroupFp,
    )
}

// ── lite-v2-dev production hook factory (plan §4.2) ───────────────────────
//
// Extracted from [ControllerModule.provideTokenStreamCoordinator] so the
// TokenStreamCoordinator commit hooks share ONE production implementation.
// lite-v2-dev: 全量重接线——SlimFullReconciler / slim commit token / watermark
// 全部退役，统一走 [SkeletonReloadCoordinator]（一条权威同步路径，见 plan §2.2）。

/**
 * C2/C3 (Lane I production wiring): the per-(sid|mid) debounce window for
 * the onMessagePartRemoved → skeleton reload. Mirrors the sidecar's 100ms
 * content-burst window so a rapid sequence of `message.part.removed` frames
 * for the same message coalesces into ONE reload.
 */
internal const val PART_REMOVAL_RECONCILE_DEBOUNCE_MS = 100L

/**
 * lite-v2-dev (plan §4.2): the three [TokenStreamCoordinator] commit hooks
 * bound to the LIVE chat slice + [SkeletonReloadCoordinator]. Each hook is
 * constructed by [tokenStreamProductionHooks] so
 * [ControllerModule.provideTokenStreamCoordinator] shares the SAME production
 * implementation.
 */
internal data class TokenStreamProductionHooks(
    val dedupPartRevision: (
        sessionId: String, messageId: String, partId: String,
        partEventRevision: Long?, context: TokenFrameCommitContext,
    ) -> Boolean,
    val onMessagePartRemoved: (
        sessionId: String, messageId: String, partId: String,
        messageEventSeq: Long, context: TokenFrameCommitContext,
    ) -> Unit,
    val onMessageRemoved: (
        sessionId: String, messageId: String,
        context: TokenFrameCommitContext,
    ) -> Unit,
)

/**
 * lite-v2-dev (plan §4.2): constructs the three [TokenStreamCoordinator]
 * commit hooks bound to the LIVE chat slice + [SkeletonReloadCoordinator].
 * Extracted to a top-level internal function so
 * [ControllerModule.provideTokenStreamCoordinator] shares the SAME
 * implementation.
 *
 * # Hook semantics (post lite-v2 rewiring)
 *
 *  - **dedupPartRevision**: no-op `true`. Token hub per-frame revision 保证
 *    已经做了 per-frame 去重（plan §4.2 (1)），本 hook 不再需要 slim
 *    watermark / commit token 去重——fail-open 到 accept。
 *
 *  - **onMessagePartRemoved**: per-(sid|mid) trailing-coalesce 100ms debounce
 *    → [SkeletonReloadCoordinator.requestReload]`(sid, 50)`。权威窗口 diff
 *    自动发现 part 列表变化（plan §4.2 (2)）。
 *
 *  - **onMessageRemoved**: 直接 dispatch [AppAction.MessageRemovedConfirmed]
 *    （即时移除，不等 reload）。删除 slim commit token + applyMessageRemoved
 *    （plan §4.2 (3)）。
 *
 * @param debounceMs overridable for deterministic tests (default
 *   [PART_REMOVAL_RECONCILE_DEBOUNCE_MS] = 100ms).
 */
internal fun tokenStreamProductionHooks(
    store: SharedStateStore,
    skeletonReloadCoordinator: SkeletonReloadCoordinator,
    appScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    debounceMs: Long = PART_REMOVAL_RECONCILE_DEBOUNCE_MS,
): TokenStreamProductionHooks {
    // Per-(sid|mid) debounce Job map for the onMessagePartRemoved → reload.
    // Trailing-coalesce: a re-entry within the debounce window cancels the
    // prior pending Job and re-schedules.
    val partRemovalDebounceJobs = ConcurrentHashMap<String, Job>()

    return TokenStreamProductionHooks(
        dedupPartRevision = { _, _, _, _, _ ->
            // lite-v2-dev (plan §4.2 (1)): token hub per-frame revision 保证
            // 已去重；本 hook 不再需要 slim watermark 去重，fail-open accept。
            true
        },
        onMessagePartRemoved = { sid, mid, _, _, _ ->
            // lite-v2-dev (plan §4.2 (2)): per-(sid|mid) trailing-coalesce
            // debounce → 权威窗口 reload（limit=50）。reload 的 diff 自动发现
            // part 列表变化，不再走单条 /full reconcile。
            val key = "$sid|$mid"
            partRemovalDebounceJobs[key]?.cancel()
            partRemovalDebounceJobs[key] = appScope.launch {
                delay(debounceMs)
                partRemovalDebounceJobs.remove(key)
                skeletonReloadCoordinator.requestReload(sid, 50)
            }
        },
        onMessageRemoved = { sid, mid, ctx ->
            // lite-v2-dev (plan §4.2 (3)): 直接 dispatch
            // MessageRemovedConfirmed（即时移除，不等 reload）。删除 slim commit
            // token + applyMessageRemoved——权威窗口 reload 会最终收敛，但 token
            // stream 的 message.removed 应即时反映。
            store.slices.store.dispatchAndVerify(
                AppAction.MessageRemovedConfirmed(
                    sessionId = sid,
                    messageId = mid,
                    expectedRouteInstance = ctx.expectedRouteInstance,
                    bundleStamp = ctx.bundleStamp,
                ),
            )
        },
    )
}
