package cn.vectory.ocdroid.di

import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.controller.ComposerController
import cn.vectory.ocdroid.ui.controller.ConnectionCoordinator
import cn.vectory.ocdroid.ui.controller.ForegroundCatchUpController
import cn.vectory.ocdroid.ui.controller.HostProfileController
import cn.vectory.ocdroid.ui.controller.SessionSwitcher
import cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.TrafficTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
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
        @ApplicationContext appContext: android.content.Context,
        @UiApplicationScope appScope: CoroutineScope,
        store: SharedStateStore,
        hostProfileStore: HostProfileStore,
        repository: OpenCodeRepository,
        settingsManager: SettingsManager,
        trafficTracker: TrafficTracker,
        effectBus: SharedEffectBus,
        cacheRepository: cn.vectory.ocdroid.data.cache.CacheRepository,
        @Named("currentServerGroupFp") currentServerGroupFp: () -> String,
        identityStore: cn.vectory.ocdroid.service.identity.ConnectionIdentityStore,
    ): HostProfileController = HostProfileController(
        scope = appScope,
        slices = store.slices,
        hostProfileStore = hostProfileStore,
        repository = repository,
        settingsManager = settingsManager,
        trafficTracker = trafficTracker,
        effects = effectBus,
        currentServerGroupFp = currentServerGroupFp,
        appContext = appContext,
        cacheRepository = cacheRepository,
        identityStore = identityStore,
    )

    @Provides
    @Singleton
    fun provideSessionSyncCoordinator(
        @UiApplicationScope appScope: CoroutineScope,
        store: SharedStateStore,
        settingsManager: SettingsManager,
        effectBus: SharedEffectBus,
        cacheRepository: cn.vectory.ocdroid.data.cache.CacheRepository,
        @Named("currentServerGroupFp") currentServerGroupFp: () -> String,
        identityStore: cn.vectory.ocdroid.service.identity.ConnectionIdentityStore,
    ): SessionSyncCoordinator = SessionSyncCoordinator(
        scope = appScope,
        slices = store.slices,
        settingsManager = settingsManager,
        effects = effectBus,
        currentServerGroupFp = currentServerGroupFp,
        // R-20 Phase 1 (C4): persistent cache for the message.updated
        // new-insert append path (maxer I11).
        cacheRepository = cacheRepository,
        // CP1 (notify Phase-0): single connection-identity store.
        identityStore = identityStore,
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
        cacheMaintenanceCoordinator: cn.vectory.ocdroid.data.cache.CacheMaintenanceCoordinator,
        @Named("currentServerGroupFp") currentServerGroupFp: () -> String,
        identityStore: cn.vectory.ocdroid.service.identity.ConnectionIdentityStore,
        bootstrapCoordinator: cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator,
        sseEventStream: cn.vectory.ocdroid.service.events.SseEventStream,
    ): ConnectionCoordinator = ConnectionCoordinator(
        scope = appScope,
        slices = store.slices,
        repository = repository,
        settingsManager = settingsManager,
        effects = effectBus,
        serverCompatProfile = serverCompatProfile,
        cacheMaintenanceCoordinator = cacheMaintenanceCoordinator,
        currentServerGroupFp = currentServerGroupFp,
        identityStore = identityStore,
        // CP2 (notify Phase-0): delegate TOFU state to the shared bootstrap
        // coordinator (FGS spec §10). CC's public TOFU surface is unchanged.
        bootstrapCoordinator = bootstrapCoordinator,
        // CP3 (notify Phase-0): publish SSE events into the process-wide
        // stream so the SseEventBridge routes them.
        sseEventStream = sseEventStream,
    )
}
