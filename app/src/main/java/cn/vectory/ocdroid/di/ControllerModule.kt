package cn.vectory.ocdroid.di

import cn.vectory.ocdroid.data.api.TokenStreamClient
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.data.repository.SlimFullReconciler
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
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.TrafficTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
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
        slimFullReconciler: SlimFullReconciler,
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
        // C2/C3 (Lane I production wiring): pass the @Singleton
        // SlimFullReconciler so SSC's digest debounce + reconnect R1 trigger
        // chain (requestDigestFullSweep / reconcileFullAfterTransportReset)
        // actually drive /full fetches. Without this, both are no-ops and
        // the C2 critical path stays at the fake-test level.
        slimFullReconciler = slimFullReconciler,
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
        sessionSyncCoordinator: SessionSyncCoordinator,
        bundleEndpointResolver: cn.vectory.ocdroid.service.streaming.BundleEndpointResolver,
        settingsManager: cn.vectory.ocdroid.util.SettingsManager,
        // C2/C3 (Lane I production wiring): the @Singleton SlimFullReconciler.
        // The three TokenStreamCoordinator commit hooks (dedupPartRevision /
        // onMessagePartRemoved / onMessageRemoved) MUST be wired to the LIVE
        // repository + reconciler so the slim watermark dedup + R2 reconcile
        // + chat eviction paths actually run in production. Without this the
        // default no-op hooks stay in place and B-P0-1 / B-P0-2 regress to
        // "data layer has tests but production is silent".
        slimFullReconciler: SlimFullReconciler,
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

        // C2/C3: build the three production hooks against the LIVE repository +
        // store + reconciler. Extracted to [tokenStreamProductionHooks] so the
        // M7 closed-loop test can construct the SAME wiring against a real OCR
        // fixture and prove the hooks are non-no-op.
        val hooks = tokenStreamProductionHooks(
            repository = repository,
            store = store,
            slimFullReconciler = slimFullReconciler,
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
            triggerSinceFetch = { sid, auth ->
                appScope.launch {
                    sessionSyncCoordinator.reconcileSession(
                        sid,
                        if (auth) SessionSyncCoordinator.ReconcileMode.RESYNC
                        else SessionSyncCoordinator.ReconcileMode.DIGEST_FOCUS,
                    )
                }
            },
            // §sse-disabled-debug-toggle: gate per-session token stream on the DEBUG
            // flag (REST-only mode). Read live so toggling takes effect on next open.
            sseDisabled = { settingsManager.sseDisabled },
            // C2/C3 production hooks (Lane I): the three commit hooks are now
            // bound to the LIVE repository + reconciler. Each captures a fresh
            // slim commit token inside the TokenStreamCoordinator's
            // `synchronized(bundleCommitLock)` critical section (no rotation
            // possible between capture and apply).
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
     * B-P0-2: provides the [SlimFullReconciler] singleton — the R1+R2
     * `/full` recovery coordinator. Every port delegates to the LIVE
     * repository + chat slice so the data-layer reconciler stays free
     * of UI/repository coupling.
     *
     * # Port wiring
     *
     *  - [tokenProvider] / [isTokenCurrent] / [requireTokenCurrent] /
     *    [fetchFull] / [parseSeqHeader] / [parseRetryAfterMs] /
     *    [snapshotSessionWatermarks] / [clearFullRecheckFlag] /
     *    [clearWatermarksForReconnect]: 1:1 forwarders on
     *    [OpenCodeRepository] (B-P0-2 added these slim watermark
     *    forwarders alongside the B-P0-1 fetch / parse surface).
     *  - [messageUpdatedAt] / [messagePartCount] / [messageMaxPartId]:
     *    read the chat slice's `messages` list + `partsByMessage` map.
     *    Cold-start (no entry) returns null — fingerprint omitted,
     *    sidecar forces a 200 (correct degraded behaviour).
     *  - [partIsStreaming]: reads the chat slice's `streamOwned[partId]`
     *    map. `STREAMING` == active token stream (done=false) → /full
     *    drops the part; the token stream wins. `DONE` / absent →
     *    /full authoritative.
     *  - [onMessageGone] (MAJOR 4): runs the /full 404 cleanup —
     *    `applyMessageRemoved` (watermark), chat-slice eviction,
     *    maxMessageTuple drop. Token-guarded inside the callback.
     *  - [ioDispatcher]: `Dispatchers.IO` for the network-bound body.
     *
     * # /since orthogonality
     *
     * The reconciler advances ONLY the per-message `needsFullRecheck`
     * flag (via [clearFullRecheckFlag]) and the chat slice's parts.
     * `/since` advances `localApplied*` / `remoteUpdatedAt` via the
     * existing reducer — no field overlap, no mutex required.
     */
    @Provides
    @Singleton
    fun provideSlimFullReconciler(
        store: SharedStateStore,
        repository: OpenCodeRepository,
    ): SlimFullReconciler = SlimFullReconciler(
        tokenProvider = { repository.captureSlimCommitToken() },
        isTokenCurrent = { repository.isSlimCommitTokenCurrent(it) },
        requireTokenCurrent = { repository.requireSlimTokenCurrent(it) },
        fetchFull = { sid, mid, maxP, pc, seq ->
            repository.getSlimapiMessageFullWithFingerprint(sid, mid, maxP, pc, seq)
        },
        parseSeqHeader = { repository.parseMessageEventSeqHeader(it) },
        parseRetryAfterMs = { resp ->
            repository.retryAfterHeaderToMs(resp.headers()["Retry-After"])
        },
        snapshotSessionWatermarks = { sid ->
            repository.snapshotSessionWatermarks(sid)
        },
        // rev-b-fix §3/§4 (C4 — Lane W atomic commit ports): the 200 / 304
        // flag-clear + seq-advance + UI dispatch now run inside ONE slim-
        // state-lock critical section via the OCR forwarders. The legacy
        // clearFullRecheckFlag port is REMOVED (superseded by commitFull200/
        // commitFull304 which clear the flag atomically).
        //
        // rev-ogpt #2: commitFull200's commitUi now returns Boolean —
        // the watermark mutation is GATED on the UI verdict. The forwarder
        // passes the lambda through unchanged; the Boolean flows back from
        // dispatchSlimFullReconciled below.
        commitFull200 = { sid, mid, requestSeq, responseSeq, token, commitUi ->
            repository.commitFull200(sid, mid, requestSeq, responseSeq, token, commitUi)
        },
        commitFull304 = { sid, mid, requestSeq, token ->
            repository.commitFull304(sid, mid, requestSeq, token)
        },
        // rev-b-fix M3: token-guarded reconnect reset (TOCTOU-safe).
        clearWatermarksForReconnect = { token ->
            repository.clearWatermarksForReconnect(token)
        },
        // Sort key + R2 fingerprint sources: chat slice (cold-start = null).
        messageUpdatedAt = { sid, mid ->
            store.slices.chat.value.messages
                .firstOrNull { it.id == mid }
                ?.time?.updated
        },
        messagePartCount = { sid, mid ->
            store.slices.chat.value.partsByMessage[mid]?.size
        },
        messageMaxPartId = { sid, mid ->
            store.slices.chat.value.partsByMessage[mid]
                ?.maxByOrNull { it.id }?.id
        },
        // B-P0-2 replacement edge: STREAMING == active token stream
        // (done=false) → /full drops the part. DONE/absent → /full wins.
        partIsStreaming = { _, _, pid ->
            store.slices.chat.value.streamOwned[pid] ==
                cn.vectory.ocdroid.ui.StreamOwnedState.STREAMING
        },
        // rev-b-fix C4 + rev-ogpt #2 (Lane U dispatch): 200 Reconciled
        // transcript merge, route-guarded. Runs inside commitFull200's
        // commitUi lambda. Returns Boolean so commitFull200 can gate the
        // watermark mutation on the reducer's verdict:
        //   true  = dispatch accepted (route+session CAS passed + the
        //           dispatch was issued). Watermark advances + flag clears.
        //   false = dispatch would have been CAS-rejected (route advanced
        //           or session deselected during the network window).
        //           Dispatch is SKIPPED; watermark + flag are PRESERVED.
        //
        // §rev-2 TOCTOU fix: dispatch via [dispatchAndVerify] which runs
        // the reducer INSIDE the CAS retry loop and returns the actual
        // commit verdict. The pre-check (route/bundle/session) is now
        // performed by the reducer within the CAS — no TOCTOU window.
        dispatchSlimFullReconciled = { sid, msg, ctx ->
            store.slices.store.dispatchAndVerify(
                AppAction.SlimFullMessageReconciled(
                    sessionId = sid,
                    message = msg,
                    expectedRouteInstance = ctx.expectedRouteInstance,
                    bundleStamp = ctx.bundleStamp,
                ),
            )
        },
        // §rev-2 TOCTOU fix: dispatch via [dispatchAndVerify]. No pre-check
        // — the reducer's bundle/route/session CAS inside the retry loop
        // is authoritative. The return value (Boolean) is discarded by the
        // SlimFullReconciler's `Unit` port signature; the side-effect (the
        // dispatch) either commits or is CAS-rejected atomically.
        dispatchMessageRemoved = { sid, mid, ctx ->
            store.slices.store.dispatchAndVerify(
                AppAction.MessageRemovedConfirmed(
                    sessionId = sid,
                    messageId = mid,
                    expectedRouteInstance = ctx.expectedRouteInstance,
                    bundleStamp = ctx.bundleStamp,
                ),
            )
        },
        // B-P0-2 MAJOR 4: /full 404 WATERMARK/REPOSITORY cleanup only.
        // The UI-side eviction is now dispatched via dispatchMessageRemoved
        // (MessageRemovedConfirmed — route-guarded). The legacy
        // MessageRemovedFromFull dispatch is stripped from this callback.
        onMessageGone = { sid, mid, token ->
            repository.applyMessageRemoved(sid, mid, token)
        },
        ioDispatcher = Dispatchers.IO,
    )
}

// ── C2/C3 production hook factory (Lane I) ───────────────────────────────
//
// Extracted from [ControllerModule.provideTokenStreamCoordinator] so the M7
// production closed-loop test can construct the SAME three hooks against a
// real OCR + store + SlimFullReconciler fixture and prove the wiring is
// non-no-op (the oracle risk #11 "default no-op hook stays in production"
// cannot regress without breaking that test).

/**
 * C2/C3 (Lane I production wiring): the per-(sid|mid) debounce window for
 * the onMessagePartRemoved → R2 reconcile. Mirrors the sidecar's 100ms
 * content-burst window so a rapid sequence of `message.part.removed` frames
 * for the same message coalesces into ONE /full fetch.
 */
internal const val PART_REMOVAL_RECONCILE_DEBOUNCE_MS = 100L

/**
 * C2/C3 (Lane I production wiring): the three [TokenStreamCoordinator]
 * commit hooks bound to the LIVE repository + chat slice +
 * [SlimFullReconciler]. Each hook is constructed by [tokenStreamProductionHooks]
 * so [ControllerModule.provideTokenStreamCoordinator] and the M7 closed-loop
 * test share the SAME production implementation.
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
 * C2/C3 (Lane I production wiring): constructs the three
 * [TokenStreamCoordinator] commit hooks bound to the LIVE repository +
 * chat slice + [SlimFullReconciler]. Extracted to a top-level internal
 * function so [ControllerModule.provideTokenStreamCoordinator] and the M7
 * production closed-loop test ([ControllerModuleProductionHooksTest]) share
 * the SAME implementation — the test is a regression guard for oracle risk
 * #11 ("default no-op hook stays in production").
 *
 * # Token capture inside the bundleCommitLock critical section
 *
 * Each hook is invoked by [TokenStreamCoordinator.dispatchEpochFrame]
 * INSIDE `synchronized(bundleCommitLock)` (where `bundleCommitLock =
 * repository`). Capturing a fresh slim commit token via
 * [OpenCodeRepository.captureSlimCommitToken] acquires the slimStateMachine's
 * OWN `slimStateLock` (a different monitor), NOT the OCR monitor, so there
 * is no nested-synchronization concern.
 *
 * # Hook semantics
 *
 *  - **dedupPartRevision**: capture token →
 *    [OpenCodeRepository.applyTokenPartRevision] (strict `>` revision
 *    dedup). Pure local dedup; no token guard at the hook boundary (the
 *    apply is itself token-guarded; a stale token fail-opens to `true`).
 *
 *  - **onMessagePartRemoved**:
 *     1. Capture token → [OpenCodeRepository.applyMessagePartRemoved]
 *        (advances messageEventSeq, drops the partId, flags
 *        `needsFullRecheck = true`).
 *     2. Per-(sid|mid) trailing-coalesce 100ms debounce →
 *        [SlimFullReconciler.reconcileMessage] (R2 single-message). The
 *        captured [TokenFrameCommitContext] is converted 1:1 into a
 *        [SlimFullReconciler.FullReconcileContext] and threaded UNCHANGED
 *        across the entire fetch (no recapture). Token currency is
 *        re-checked AFTER the delay; a rotated token short-circuits (the
 *        flag stays set for the next digest sweep — Lane O1's
 *        `requestDigestFullSweep` picks it up).
 *
 *  - **onMessageRemoved**:
 *     1. Capture token → [OpenCodeRepository.applyMessageRemoved]
 *        (removes the per-message watermark entry; no /full — nothing to
 *        fetch).
 *     2. Dispatch [AppAction.MessageRemovedConfirmed] carrying the captured
 *        route + bundle. A `route=0` ctx (no active route) is dispatched
 *        anyway — the reducer's freeze-protocol guard returns state
 *        unchanged, exactly as designed (no transcript write when there is
 *        no active route; only the watermark/repository cleanup above ran).
 *
 * @param appScope the [UiApplicationScope]-qualified [CoroutineScope] the
 *   debounce launches on. Production: Main.immediate singleton. Tests: the
 *   [kotlinx.coroutines.test.TestScope] so virtual time controls the
 *   debounce.
 * @param debounceMs overridable for deterministic tests (default
 *   [PART_REMOVAL_RECONCILE_DEBOUNCE_MS] = 100ms).
 */
internal fun tokenStreamProductionHooks(
    repository: OpenCodeRepository,
    store: SharedStateStore,
    slimFullReconciler: SlimFullReconciler,
    appScope: CoroutineScope,
    debounceMs: Long = PART_REMOVAL_RECONCILE_DEBOUNCE_MS,
): TokenStreamProductionHooks {
    // Per-(sid|mid) debounce Job map for the onMessagePartRemoved → R2
    // single-message reconcile. Trailing-coalesce: a re-entry within the
    // debounce window cancels the prior pending Job and re-schedules. The
    // map is local to ONE TokenStreamCoordinator instance — production
    // (singleton TSC) gets one; tests get a fresh map per fixture.
    val partRemovalDebounceJobs = ConcurrentHashMap<String, Job>()

    return TokenStreamProductionHooks(
        dedupPartRevision = { sid, mid, pid, rev, _ ->
            // Pure local dedup. captureSlimCommitToken acquires slimStateLock
            // (NOT the OCR monitor held by dispatchEpochFrame's
            // bundleCommitLock), so no nested synchronization. A stale token
            // fail-opens to `true` (without applying dedup we cannot drop,
            // so the caller falls back to accept).
            val token = repository.captureSlimCommitToken()
            repository.applyTokenPartRevision(sid, mid, pid, rev, token)
        },
        onMessagePartRemoved = { sid, mid, pid, seq, ctx ->
            // 1. Token-guarded watermark mutation: advance messageEventSeq
            //    (monotonic), drop the removed partId, flag
            //    needsFullRecheck = true so the R2 reconcile below (and the
            //    next digest sweep) pick it up.
            val token = repository.captureSlimCommitToken()
            repository.applyMessagePartRemoved(sid, mid, pid, seq, token)
            // 2. Per-(sid|mid) trailing-coalesce debounce → R2 reconcile.
            //    The captured ctx (route + bundle snapshot) is the request
            //    guard for the entire fetch — no recapture inside the
            //    debounce (freeze protocol: single entry context).
            val key = "$sid|$mid"
            partRemovalDebounceJobs[key]?.cancel()
            partRemovalDebounceJobs[key] = appScope.launch {
                delay(debounceMs)
                partRemovalDebounceJobs.remove(key)
                // Token currency: the token captured at hook entry may have
                // rotated during the debounce delay (host reconfigure). The
                // reconciler re-validates after every network suspension,
                // but we short-circuit here to avoid a guaranteed-stale
                // fetch (the flag is preserved for the next digest sweep).
                if (!repository.isSlimCommitTokenCurrent(token)) {
                    DebugLog.i(
                        "ControllerModule",
                        "part-removed debounce sid=$sid mid=$mid stale token — flag preserved for next sweep",
                    )
                    return@launch
                }
                val context = SlimFullReconciler.FullReconcileContext(
                    expectedRouteInstance = ctx.expectedRouteInstance,
                    bundleStamp = ctx.bundleStamp,
                )
                try {
                    slimFullReconciler.reconcileMessage(sid, mid, token, context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Stay-flagged semantics: the per-message flag is
                    // preserved on failure; the next digest debounce or
                    // reconnect sweep will re-attempt.
                    DebugLog.w(
                        "ControllerModule",
                        "part-removed reconcile sid=$sid mid=$mid failed: ${e.message}",
                    )
                }
            }
        },
        onMessageRemoved = { sid, mid, ctx ->
            // 1. Token-guarded watermark removal (no /full — nothing to
            //    fetch; the message is gone).
            val token = repository.captureSlimCommitToken()
            repository.applyMessageRemoved(sid, mid, token)
            // 2. §rev-2 TOCTOU fix: dispatch via [dispatchAndVerify]. No
            //    pre-check — the reducer's bundle/route/session CAS inside
            //    the retry loop is authoritative. If the route or bundle has
            //    advanced since ctx was captured, the reducer returns state
            //    unchanged and dispatchAndVerify returns false — the
            //    watermark cleanup in step 1 is independent and has already
            //    run (it does not need a route token). A route=0 ctx with
            //    expectedRouteInstance==0L is rejected by the reducer
            //    (reduceMessageRemovedConfirmed returns state unchanged for
            //    route=0), which is correct — no transcript write when there
            //    is no active route.
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
