@file:OptIn(ExperimentalCoroutinesApi::class)

package cn.vectory.ocdroid.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §A5-3 Phase B1: the N per-slice private `MutableStateFlow<XxxState>` were
 * collapsed into ONE authoritative composite aggregate
 * (`state` of type [StoreState]). Every public `xxxFloW: StateFlow<XxxState>`
 * is now a [DerivedStateFlow] projection over that single source. Every
 * `mutateXxx(transform)` is now `state.update { it.copy(xxx = transform(it.xxx)) }`.
 *
 * Background / why: HiltViewModels cannot inject each other (ViewModels are
 * not `@Inject`-able dependencies), so the slices that USED to live in
 * MainViewModel's private fields live in this `@Singleton`. Every domain
 * ViewModel injects the same instance and reads/writes its own slice through
 * the per-slice helpers. Pre-B1 each slice had its own authoritative
 * `MutableStateFlow`; B1 unifies them into one composite so B2 (an `AppAction` /
 * reducer / atomic multi-slice writes) has a single committed state per
 * dispatcher tick to transition.
 *
 * Source-compatibility: every public `xxxFloW` projection + every `mutateXxx`
 * keeps its pre-B1 signature, so callers (controllers / VMs / [SliceFlows] /
 * [AppCore] write-helpers / every test) resolve UNCHANGED.
 *
 * Behavior-preservation (the B1 gate — ALL existing tests must stay green): a
 * [DerivedStateFlow] is lag-free. Its [StateFlow.value] reads synchronously
 * from the aggregate via the selector — there is NO async `stateIn` mirror
 * (no `SharingStarted` dispatcher hop) that could lag the aggregate. So
 * `store.chatFlow.value` observes the new chat immediately after
 * `store.mutateChat { ... }` on the same tick, exactly as before. Collectors
 * see distinct selector results only.
 *
 * §R18 Phase 4 (P0-9): the pre-B1 write-permission convergence still holds —
 * the underlying `MutableStateFlow` is `private`, external readers consume the
 * read-only [StateFlow] views, every write funnels through the matching
 * [mutateXxx]. Oracle ruling (YAGNI): in a single-module app a true isolation
 * layer (interface segregation / write-token hand-out) is not worth the
 * indirection; the private + DerivedStateFlow + mutateXxx trio IS the
 * terminal write surface. B1 changes the storage backing, not that surface.
 */
@Singleton
class SharedStateStore @Inject constructor(
    /**
     * §P0-A rev-gpt #5: the current connection identity, used to derive the
     * REAL authority [ScopeKey] at the non-aggregator snapshot sites
     * (StatusPollOrchestrator / BackgroundUnreadPoller / SessionTreeHydrator /
     * SessionListActions). Previously these sites used an EMPTY ScopeKey
     * ("","") → coverage was written under a key the aggregator never reads →
     * globalState degraded to Unknown. */
    private val identityStore: cn.vectory.ocdroid.service.identity.ConnectionIdentityStore,
    @cn.vectory.ocdroid.di.UiApplicationScope
    private val scope: kotlinx.coroutines.CoroutineScope? = null,
) {
    internal var state: MutableStateFlow<StoreState> = MutableStateFlow(StoreState.initial())
        private set

    /**
     * §需求10 C3 (round-4, oracle-driven cancel-and-replace): the heavyweight
     * session-list refresh flag.
     *
     * ## Concurrency model (the authoritative invariant set)
     *
     * - **Orchestrator ([SessionListRefreshOrchestrator.launchLoadSessions])**:
     *   cancel-and-replace, newer-wins. A new caller bumps the epoch at ENTRY and
     *   cancels the in-flight job so the older coroutine dies BEFORE any write
     *   (cancellation is delivered to the suspend Retrofit call; onSuccess/onFailure
     *   do not run). The per-call epoch check in onSuccess/onFailure is kept as
     *   defense-in-depth against the cancellation-delivery race. This is original
     *   FIX-D + "and cancel the loser so it stops consuming the network."
     * - **Poller ([SessionMetadataPoller.poll])**: ambient, read-only skip. It is
     *   self-serialized by its single pollJob and carries no intent, so it is NOT
     *   part of the single-flight. It READS this flag to skip its own light
     *   title-patch when a heavyweight refresh is running (avoids one duplicate
     *   cheap GET). It never acquires/cancels anything.
     *
     * ≤1 concurrent is guaranteed for the heavyweight refresh path (orchestrator
     * vs orchestrator). Orchestrator-vs-poller overlap is a bounded, benign known
     * limitation: the poller's title-patch commit is independently guarded by
     * commitIfCurrent + fresher-wins merge, cost is one cheap GET, self-heals next
     * tick. Strict ≤1-concurrent across both would require coupling the poller's
     * in-flight call to a shared job handle in the store — not worth one cheap GET.
     *
     * Set/clear is owned by the orchestrator: set INSIDE the coroutine body (so a
     * scope cancellation between acquire and body-launch can't leak the flag),
     * cleared in `finally` ONLY if this job still holds the current epoch (so a
     * superseded job doesn't wipe the newer job's flag).
     */
    @Volatile
    var sessionListLoadInFlight: Boolean = false
        internal set

    /** Track the last identityStore epoch that triggered a store identityEpoch bump.
     *  One bump per unique identityStore epoch value avoids double-bumps when
     *  mutateHost and identityStore bind both fire for the same reconfigure cycle.
     *  Written only from the init collection (serial on [scope]). */
    private var lastObservedIdentityEpoch: Long = -1L

    init {
        // §P0-A r2 #3b: observe identityStore identity changes for endpoint/workdir-
        // only reconfigures (same hostProfileId, different endpoint/workdir). The
        // identity store's epoch bumps on every beginReconfigure; we mirror it in
        // StoreState.identityEpoch so the reducer's opScopeValid catches ALL
        // identity changes (not just host-profile switches via mutateHost).
        // Bump on EVERY transition: non-null→null (beginReconfigure's clearing)
        // AND null→non-null (new bind) AND non-null→non-null different identity.
        // The null-phase bump closes the window where stale in-flight requests
        // from the prior identity could pass the reducer's epoch guard while
        // currentIdentity is null. Cold-start initial null does NOT bump
        // (lastObservedIdentityEpoch starts at -1). Each unique identityStore
        // epoch bumps identityEpoch + authorityRevision exactly once.
        scope?.launch {
            identityStore.currentIdentity.collect { id ->
                if (id != null) {
                    val newEpoch = id.epoch
                    if (newEpoch > lastObservedIdentityEpoch) {
                        lastObservedIdentityEpoch = newEpoch
                        state.update { s ->
                            s.copy(
                                identityEpoch = s.identityEpoch + 1L,
                                authorityRevision = s.authorityRevision + 1L,
                            )
                        }
                    }
                } else {
                    // §P0-A null-phase epoch bump: when identity transitions TO
                    // null (beginReconfigure), bump identityEpoch + authorityRevision
                    // so stale in-flight requests from the prior identity cannot pass
                    // the reducer's epoch guard during the null window. Only bump if
                    // we had a previous non-null identity (lastObservedIdentityEpoch
                    // >= 0). Reset to -1 so repeated null emissions don't re-bump.
                    if (lastObservedIdentityEpoch >= 0L) {
                        lastObservedIdentityEpoch = -1L
                        state.update { s ->
                            s.copy(
                                identityEpoch = s.identityEpoch + 1L,
                                authorityRevision = s.authorityRevision + 1L,
                            )
                        }
                    }
                }
            }
        }
    }

    /** Test-only no-arg constructor (unbound identityStore). Hilt does NOT see
     *  this (no @Inject); it resolves the primary constructor above. */
    internal constructor() : this(cn.vectory.ocdroid.service.identity.ConnectionIdentityStore())

    /** Test-only: inject a custom [MutableStateFlow] for deterministic CAS control
     *  (e.g., to simulate CAS failure / retry sequences). */
    internal constructor(testState: MutableStateFlow<StoreState>) : this() {
        state = testState
    }

    /**
     * §P0-C (B11): the current [ConnectionIdentity] or null (cold start / test
     * without identity store). Delegates to [identityStore.currentIdentity.value].
     */
    internal fun currentIdentity(): cn.vectory.ocdroid.service.identity.ConnectionIdentity? =
        identityStore.currentIdentity.value

    /**
     * §P0-C (B11): snapshot of [StoreState.identityEpoch] captured NOW (before
     * any suspend). The reducer's [opScopeValid] compares this captured epoch
     * against the live [StoreState.identityEpoch] at CAS time to detect stale
     * ops. Read from [state.value.identityEpoch] (the store's mirror) so the
     * reducer's guard matches — NOT from [identityStore.currentEpoch] directly,
     * because the store may legitimately lag or lead due to [SharedStateStore]
     * own epoch management (init collection bumps state.identityEpoch).
     */
    internal fun captureIdentityEpoch(): Long = state.value.identityEpoch

    /**
     * §P0-C (B11): whether [id] is the current identity per the identity store.
     * Used by the optimistic send [onSuccess] guard (deliverable 2) to drop
     * stale optimistic writes after a host switch. Delegates to
     * [ConnectionIdentityStore.isCurrent].
     */
    internal fun isCurrentIdentity(id: cn.vectory.ocdroid.service.identity.ConnectionIdentity): Boolean =
        identityStore.isCurrent(id)

    /**
     * §P0-A rev-gpt #5: the REAL authority [ScopeKey] for the current identity
     * (profileId + endpointFp). Used by the non-aggregator snapshot sites
     * so coverage is written under the SAME key the aggregator reads
     * ([StatusAggregatorImpl.currentScope] derives identically from
     * `identityStore.currentIdentity.value`). MUST match the aggregator's
     * derivation — no second scope source. */
    internal fun authorityScope(): cn.vectory.ocdroid.data.state.ScopeKey {
        val id = identityStore.currentIdentity.value
        return cn.vectory.ocdroid.data.state.scopeKeyOf(
            id?.profileId, id?.endpointFp,
        )
    }

    /**
     * §A5-3 Phase B2: read-only aggregate [StateFlow] over the single
     * authoritative composite [state]. Exposed (internal) so the B2
     * atomicity tests + any future cross-slice consumer can collect the
     * AGGREGATE emission stream and prove a single [dispatch] commits
     * exactly one transition with no torn intermediates (e.g. the
     * SessionArchived action cannot produce an intermediate where
     * sessionList is archived-but-chat.currentSessionId still references
     * it — there is one `state.update` per dispatch, hence one emission).
     *
     * Per-slice consumers SHOULD keep reading the per-slice projections
     * ([chatFlow] / [sessionListFlow] / etc.) — those are distinct-filtered
     * for their slice only, which is the desired UX. This aggregate flow
     * exists for tests + future cross-slice observers that need to reason
     * about the WHOLE committed state at once.
     */
    internal val stateFlow: StateFlow<StoreState> = state.asStateFlow()

    // ── Per-slice read projections (lag-free DerivedStateFlow over [state]). ──
    // Each .value reads selector(state.value) synchronously — no dispatcher hop.
    val connectionFlow: StateFlow<ConnectionState> = DerivedStateFlow(state) { it.connection }
    val trafficFlow: StateFlow<TrafficState> = DerivedStateFlow(state) { it.traffic }
    val composerFlow: StateFlow<ComposerState> = DerivedStateFlow(state) { it.composer }
    val fileFlow: StateFlow<FileState> = DerivedStateFlow(state) { it.file }
    val settingsFlow: StateFlow<SettingsState> = DerivedStateFlow(state) { it.settings }
    val chatFlow: StateFlow<ChatState> = DerivedStateFlow(state) { it.chat }
    val sessionListFlow: StateFlow<SessionListState> = DerivedStateFlow(state) { it.sessionList }
    val unreadFlow: StateFlow<UnreadState> = DerivedStateFlow(state) { it.unread }
    val hostFlow: StateFlow<HostState> = DerivedStateFlow(state) { it.host }

    /** §A5-3 B1: collapsible-card expansion map (was its own MutableStateFlow).
     *  Writes via [mutateExpandedParts] (CAS, atomic); reads via this. */
    val expandedParts: StateFlow<Map<String, Boolean>> = DerivedStateFlow(state) { it.expandedParts }

    /** §A5-3 B1: nav slice (was its own MutableStateFlow). Seeded by OrchestratorVM.
     *  Not part of the [SliceFlows] bundle. */
    val navFlow: StateFlow<NavState> = DerivedStateFlow(state) { it.nav }

    /**
     * §breathing-indicator (item ①): SSE-transport-up projection over the
     * aggregate [state.isSseConnected]. The UI drives the breathing pulse off
     * this flow. Like [navFlow] it is NOT part of the [SliceFlows] bundle
     * (it is not a domain slice — it is a transport-liveness flag owned by
     * [cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwner]).
     *
     * Lag-free [DerivedStateFlow]: `.value` reads synchronously from the
     * aggregate, so the owner's write + the UI's read observe the same tick.
     */
    val sseConnectedFlow: StateFlow<Boolean> = DerivedStateFlow(state) { it.isSseConnected }

    /**
     * §chat-list-detail §7.2 B0.5: the chat-route incarnation counter
     * projection. The chat/{id} render composable collects this to apply the
     * P6 freshness CAS at render time (`content.routeInstance ==
     * chatRouteInstance`). Internal — surfaced to the shell via
     * [OrchestratorViewModel.chatRouteInstanceFlow].
     */
    internal val chatRouteInstanceFlow: StateFlow<Long> = DerivedStateFlow(state) { it.chatRouteInstance }

    /**
     * §P1-B/E retry-queue observability: a lag-free [DerivedStateFlow]
     * projection over `authority.retryQueue` (the bounded retry queue keyed
     * by sid). Pure READ side — mirrors the per-slice projection pattern
     * ([sessionListFlow] / [chatFlow] / …). Introduces NO new writeable
     * truth: the queue is mutated solely by the pure [reduceAuthority]
     * (RetryQueued / RetryFired / terminal-cleanup), and this flow is a
     * plain selector over the single aggregate [state].
     *
     * Consumers: diagnostics / tests / future UI retry indicator. Each
     * `.value` reads `state.value.authority.retryQueue` synchronously.
     */
    internal val retryQueueFlow: StateFlow<Map<String, cn.vectory.ocdroid.data.state.RetryEntry>> =
        DerivedStateFlow(state) { it.authority.retryQueue }

    /**
     * §breathing-indicator (item ①, TOCTOU fix): the last generation that
     * committed [sseConnectedFlow]. Read accessor used by
     * [cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwner] to SEED
     * its transport-generation counter at construction — so a recreated owner
     * (new Service instance, fresh counter field) continues monotonically from
     * where the prior owner's teardown stamped the @Singleton store. Without
     * this seed, the new owner's counter would start at 0 and the monotonic CAS
     * would reject ALL of its writes (its generations < the persisted stamp),
     * breaking service-recreation survival (the owner is instance-scoped +
     * nullable; the store is process-lifetime).
     */
    val sseConnectedGeneration: Long get() = state.value.sseConnectedGeneration

    // ── Per-slice write helpers (each funnels through the single aggregate). ──
    // SAME public signatures as pre-B1 — callers (AppCore.writeXxx /
    // SliceFlows.mutateXxx / every test) resolve UNCHANGED. Each is
    // `state.update { it.copy(xxx = transform(it.xxx)) }` so the per-slice
    // transform sees the CURRENT committed aggregate's slice value (CAS loop),
    // and the write lands as ONE committed aggregate state.
    fun mutateConnection(transform: (ConnectionState) -> ConnectionState) {
        // §CQ-P7 (U-CQ7): wall clock read ONCE outside the CAS retry lambda so the
        // CAS transform stays pure/idempotent across retries (re-running it on a
        // retried snapshot reproduces the same transition). System.currentTimeMillis()
        // inside the lambda would read a different value on each retry.
        val now = System.currentTimeMillis()
        state.update { storeState ->
            val previous = storeState.connection
            val requested = transform(previous)
            // §sse-rest-fallback (TODO 3): auto-stamp disconnectedSince on the
            // Disconnected phase transition (single chokepoint → every writer
            // records it). Pure helper so it is unit-testable in isolation.
            storeState.copy(connection = stampDisconnectedSince(previous, requested, now))
        }
    }
    fun mutateTraffic(transform: (TrafficState) -> TrafficState) =
        state.update { it.copy(traffic = transform(it.traffic)) }
    fun mutateComposer(transform: (ComposerState) -> ComposerState) =
        state.update { it.copy(composer = transform(it.composer)) }
    fun mutateFile(transform: (FileState) -> FileState) =
        state.update { it.copy(file = transform(it.file)) }
    fun mutateSettings(transform: (SettingsState) -> SettingsState) =
        state.update { it.copy(settings = transform(it.settings)) }
    fun mutateChat(transform: (ChatState) -> ChatState) =
        state.update { it.copy(chat = transform(it.chat)) }
    fun mutateSessionList(transform: (SessionListState) -> SessionListState) =
        state.update { it.copy(sessionList = transform(it.sessionList)) }
    fun mutateUnread(transform: (UnreadState) -> UnreadState) =
        state.update { it.copy(unread = transform(it.unread)) }
    /** Derive an unread update from one atomic aggregate snapshot. */
    internal fun mutateUnreadFromState(transform: (StoreState) -> UnreadState) =
        state.update { snapshot -> snapshot.copy(unread = transform(snapshot)) }
    /** Internal aggregate CAS for controllers that must commit multiple slices together. */
    internal fun mutateState(transform: (StoreState) -> StoreState) = state.update(transform)

    /**
     * §unified-nav (A4): aggregate CAS that RETURNS the committed snapshot. Used
     * by call sites that must read a value minted INSIDE the transform (e.g.
     * [cn.vectory.ocdroid.ui.OrchestratorViewModel.navigateToChat]'s route-
     * instance token + [cn.vectory.ocdroid.ui.adoptMaterializedSessionRoute]'s
     * adoption CAS) without a separate `.value` re-read that could observe a
     * concurrent writer's state (the token-capture race). Backed by
     * [MutableStateFlow.updateAndGet] (kotlinx's CAS retry loop), so the
     * returned [StoreState] is the truly committed value.
     *
     * Main-thread contract: like every other mutateXxx, callers MUST run on
     * Dispatchers.Main.immediate so the mint-then-read pair is serial.
     */
    internal fun mutateStateAndGet(transform: (StoreState) -> StoreState): StoreState = state.updateAndGet(transform)
    fun mutateHost(transform: (HostState) -> HostState) =
        state.update {
            val prevHost = it.host
            val nextHost = transform(prevHost)
            // §P0-A rev-gpt r2 #3b/#7: bump identityEpoch + authorityRevision
            // on ANY HostState change (not just currentHostProfileId) — a
            // same-profile reconfigure that updates hostProfiles or other
            // identity-defining fields must still invalidate in-flight REST
            // requests (the reducer's identityEpoch guard drops stale tokens).
            // authorityRevision bumps alongside so the aggregator's
            // distinctUntilChanged{authorityRevision} re-derives on scope change.
            // The adapter's dispatch-side identityStore.currentEpoch() check
            // catches endpoint/workdir-only reconfigures that don't touch HostState.
            if (nextHost != prevHost) {
                it.copy(
                    host = nextHost,
                    identityEpoch = it.identityEpoch + 1L,
                    authorityRevision = it.authorityRevision + 1L,
                )
            } else {
                it.copy(host = nextHost)
            }
        }
    /** §history-load-fix / §A5-3 B1: CAS write of the expansion map. */
    fun mutateExpandedParts(transform: (Map<String, Boolean>) -> Map<String, Boolean>) =
        state.update { it.copy(expandedParts = transform(it.expandedParts)) }
    /** §A5-3 B1: CAS write of the nav slice. */
    fun mutateNav(transform: (NavState) -> NavState) =
        state.update { it.copy(nav = transform(it.nav)) }
    /**
     * §breathing-indicator (item ①, TOCTOU fix): MONOTONIC generation-stamped
     * CAS write of the SSE-transport-up flag. The candidate [generation] wins
     * ONLY IF `generation >= current.sseConnectedGeneration` (newest generation
     * wins); a stale LOWER-generation write is atomically rejected.
     *
     * Atomicity: this is a SINGLE `state.update { }` CAS (kotlinx's compare-
     * and-set retry loop), so the generation validation and the value write
     * commit as ONE atomic transition — there is no window for a concurrent
     * disconnect/reconfigure to bump the transport generation BETWEEN the check
     * and the write (the check-then-write TOCTOU that the pre-CAS
     * `setSseConnected` had). The CAS serializes concurrent writers by
     * generation, no extra lock (cannot deadlock with `connectMutex`).
     *
     * Routed through the single aggregate (one committed state per dispatcher
     * tick) so a write + a concurrent [dispatch] never tear.
     *
     * @param value the candidate `isSseConnected` value.
     * @param generation the transport generation the write belongs to. A
     *   generation-transition teardown passes the BUMPED (new) generation so a
     *   stale prior-gen collector loses the CAS; a same-gen outage/recovery
     *   passes the collector's own generation.
     * @return `true` if the write committed (candidate gen won the CAS).
     */
    fun mutateSseConnected(value: Boolean, generation: Long): Boolean {
        val newState = state.updateAndGet { current ->
            if (generation >= current.sseConnectedGeneration) {
                current.copy(isSseConnected = value, sseConnectedGeneration = generation)
            } else {
                // Stale lower-generation write — atomically rejected. A
                // superseded collector cannot resurrect isSseConnected=true.
                current
            }
        }
        // The write committed iff the resulting stamp matches the candidate
        // generation (a rejected write leaves the higher stored stamp untouched,
        // so newState.sseConnectedGeneration != generation).
        return newState.sseConnectedGeneration == generation && newState.isSseConnected == value
    }

    /**
     * §A5-3 Phase B2: commit an [AppAction] against the aggregate as ONE
     * composite state transition. The pure [reduce] turns `(state, action)`
     * into a new [StoreState]; a single `state.update { … }` then CAS-
     * commits it. Because there is exactly ONE update per dispatch, there
     * is exactly ONE aggregate emission — concurrent [stateFlow] collectors
     * observe a single atomic transition with no torn intermediates (the
     * pre-B2 scattering of N `mutateXxx` calls produced N intermediate
     * committed states per logical transition; this collapses them to one).
     *
     * Purity contract: [reduce] is pure (no effects / network / settings /
     * emit); everything that is NOT pure state stays at the call site and
     * runs AROUND the dispatch (network calls, `settingsManager.*` writes,
     * `persistSessionCache`, effect-bus emissions — see the per-variant
     * kdocs on [AppAction]).
     */
    internal fun dispatch(action: AppAction) {
        state.update { reduce(it, action) }
    }

    /**
     * §rev-2 TOCTOU fix: dispatch an [AppAction] inside the CAS retry loop
     * and return whether the reducer accepted (i.e., actually modified state).
     *
     * The reducer returns the SAME [StoreState] reference when it rejects
     * (due to stale bundle / route / session), and a NEW reference from
     * `copy(...)` when it accepts. By comparing references inside the CAS
     * loop's LAST invocation (the one that actually commits), the verdict
     * reflects the truly committed state — closing the TOCTOU window between
     * a pre-check read and the dispatch write.
     *
     * # CAS retry semantics
     *
     * [MutableStateFlow.update] calls the reducer function with the latest
     * current state in a compare-and-swap loop until the CAS succeeds. If
     * a concurrent writer mutates the state between the reducer call and the
     * CAS, the loop retries with the new state. The `accepted` flag is set
     * on EVERY call where the reducer produces a new reference; the LAST
     * call's verdict (the one that actually committed) is what gets returned.
     *
     * # Callers
     *
     * Used by [ControllerModule]'s `dispatchSlimFullReconciled` and
     * `dispatchMessageRemoved` production hooks (and any future dispatch
     * site that needs to gate side-effects on the reducer's verdict).
     * Does NOT change the 170+ existing [dispatch] call sites (which
     * fire-and-forget and do not need the return value).
     */
    internal fun dispatchAndVerify(action: AppAction): Boolean {
        // CAS loop: run reduce inside the retry, and only the LAST iteration's
        // verdict (the one that actually committed) is returned. This closes the
        // TOCTOU window AND the sticky-variable bug (the old `accepted` flag
        // persisted across CAS retries).
        while (true) {
            val current = state.value
            val next = reduce(current, action)
            if (state.compareAndSet(current, next)) {
                return next !== current
            }
        }
    }

    /** §history-load-fix: per-session message-list mutation lock shared by the
     *  three load paths (launchLoadMessages / launchLoadMoreMessages /
     *  launchCatchUp) via [SliceFlows.messageLoadCoordinator]. Owned here (the
     *  store is @Singleton) so all callers share one lock map without extra DI
     *  plumbing and without changing this class's no-arg constructor (existing
     *  `SharedStateStore()` test constructions keep working).
     *
     *  §A5-3 B1: NOT a state slice — a coordination primitive. Unchanged by the
     *  composite refactor (it was never a `MutableStateFlow`). */
    val messageLoadCoordinator: MessageLoadCoordinator = MessageLoadCoordinator()

    /** Bundle view (preserves the [SliceFlows] data class for controller ctors). */
    val slices: SliceFlows = SliceFlows(this)
}

/**
 * §sse-rest-fallback (TODO 3): pure phase-transition stamper for
 * [ConnectionState.disconnectedSince]. Stamps the wall clock ([now]) on the
 * transition INTO [ConnectionPhase.Disconnected] (only when the caller did NOT
 * already set [ConnectionState.disconnectedSince] — explicit non-null writes
 * win, so tests can simulate an old disconnect), and clears it on the
 * transition OUT. Pure (all inputs are params) so it is unit-testable in
 * isolation without a store or a clock.
 *
 * Called by [SharedStateStore.mutateConnection] — the single connection-write
 * chokepoint — so every writer (CC / healthProbe / SSE connection owner /
 * host-switch) records [ConnectionState.disconnectedSince] consistently without
 * each site needing to remember.
 */
internal fun stampDisconnectedSince(
    previous: ConnectionState,
    requested: ConnectionState,
    now: Long,
): ConnectionState {
    val wasDisconnected = previous.connectionPhase is ConnectionPhase.Disconnected
    val isDisconnected = requested.connectionPhase is ConnectionPhase.Disconnected
    return when {
        // Transition INTO Disconnected: stamp unless the caller already did.
        !wasDisconnected && isDisconnected && requested.disconnectedSince == null ->
            requested.copy(disconnectedSince = now)
        // Transition OUT of Disconnected: clear the stale stamp.
        wasDisconnected && !isDisconnected && requested.disconnectedSince != null ->
            requested.copy(disconnectedSince = null)
        else -> requested
    }
}

/**
 * Lag-free [StateFlow] projection over the aggregate [source] via [selector].
 *
 * `.value` reads synchronously (no async collector hop) so it never lags the
 * aggregate after a mutation on the same dispatcher tick; collectors see
 * distinct selector results only. This is what makes per-slice reads observe
 * the SAME committed aggregate (cross-slice consistency for A5-3 atomicity)
 * while keeping the pre-B1 read UX (`store.chatFlow.value` returns the live
 * chat, not a dispatcher-delayed mirror).
 *
 * Implements every abstract [StateFlow] member:
 *  - [value] — `selector(source.value)` (synchronous; no `SharingStarted`).
 *  - [replayCache] — `source.replayCache.map(selector)` (a [StateFlow]'s
 *    replayCache is always single-element, so this is single-element too).
 *  - [collect] — `source.map(selector).distinctUntilChanged().collect(...)`,
 *    so collectors see selector CHANGES only (matches pre-B1 per-slice
 *    `MutableStateFlow` semantics where distinct-value equality is implicit).
 *
 * This is the mechanical replacement for the pre-B1 `MutableStateFlow<XxxState>
 * .asStateFlow()` view — same read contract, backed by one composite source.
 */
private class DerivedStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val selector: (T) -> R,
) : StateFlow<R> {
    override val value: R get() = selector(source.value)
    override val replayCache: List<R> get() = source.replayCache.map(selector)
    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        // The source is a StateFlow (hot, never completes), so this collect
        // never returns normally — satisfying the [StateFlow.collect] `Nothing`
        // contract. The throw below is unreachable; it only exists to make the
        // Kotlin type-checker accept the `Nothing` return (the inner
        // `Flow.collect` returns Unit). Mirrors how kotlinx-coroutines'
        // StateFlowImpl expresses the same infinite-collection contract.
        source.map(selector).distinctUntilChanged().collect(collector)
        throw IllegalStateException("DerivedStateFlow over a StateFlow source never returns")
    }
}
