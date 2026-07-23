package cn.vectory.ocdroid.di

import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.data.repository.http.OkHttpClientFactory
import cn.vectory.ocdroid.data.repository.http.hostPortFromUrl
import cn.vectory.ocdroid.data.api.TokenStreamClient
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.controller.ComposerController
import cn.vectory.ocdroid.ui.controller.ConnectionCoordinator
import cn.vectory.ocdroid.ui.controller.ForegroundCatchUpController
import cn.vectory.ocdroid.ui.controller.ForegroundSessionTreeHydrator
import cn.vectory.ocdroid.ui.controller.HostProfileController
import cn.vectory.ocdroid.ui.controller.SessionSwitcher
import cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator
import cn.vectory.ocdroid.ui.controller.UnreadSoakController
import cn.vectory.ocdroid.ui.controller.ControllerEffect
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
import kotlinx.coroutines.launch
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
        // Cluster A / Phase 2: runtime slim provider + repository for
        // session.digest / slim questions / cold-start snapshot fold.
        // isSlimMode is a thunk so host-profile switches that flip
        // repository.isSlimMode are observed without reconstructing SSC.
        isSlimMode = { repository.isSlimMode },
        repository = repository,
        reconcileDispatcher = Dispatchers.Default,
    )

    /**
     * §Stage-D2 §5.8/§5.9: provides the [TokenStreamCoordinator] singleton.
     *
     * # streamProvider wiring
     *
     * Resolves the LIVE host config at call time (when `open(sid, dir)` is
     * invoked) from `settingsManager.serverUrl` — NOT from a captured
     * snapshot. This mirrors how `HostConfig` resolves baseUrl/hostPort at
     * `configure()` time: `settingsManager.serverUrl` IS the live value that
     * `hostConfig.baseUrl` mirrors. `hostPortFromUrl(serverUrl)` gives the
     * `host:port` authority for the TOFU pin lookup (same derivation as
     * `HostConfig.configure`). A new `TokenStreamClient` is constructed per
     * call (lightweight wrapper; the `OkHttpClient` is built fresh — no
     * `cache(tokenStreamClient)` per the Stage-C design note).
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
        settingsManager: SettingsManager,
        clientFactory: OkHttpClientFactory,
        sessionSyncCoordinator: SessionSyncCoordinator,
    ): TokenStreamCoordinator = TokenStreamCoordinator(
        scope = appScope,
        slices = store.slices,
        streamProvider = { sid, directory ->
            val baseUrl = settingsManager.serverUrl
            val hostPort = hostPortFromUrl(baseUrl)
            TokenStreamClient(clientFactory.tokenStreamClient(hostPort), baseUrl)
                .connect(sid, directory)
        },
        triggerSinceFetch = { sid, auth ->
            appScope.launch {
                sessionSyncCoordinator.reconcileSession(
                    sid,
                    if (auth) SessionSyncCoordinator.ReconcileMode.RESYNC
                    else SessionSyncCoordinator.ReconcileMode.DIGEST_FOCUS,
                )
            }
        },
    )

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
    )
}
