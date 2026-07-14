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
class SharedStateStore @Inject constructor() {
    private val state: MutableStateFlow<StoreState> = MutableStateFlow(StoreState.initial())

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

    // ── Per-slice write helpers (each funnels through the single aggregate). ──
    // SAME public signatures as pre-B1 — callers (AppCore.writeXxx /
    // SliceFlows.mutateXxx / every test) resolve UNCHANGED. Each is
    // `state.update { it.copy(xxx = transform(it.xxx)) }` so the per-slice
    // transform sees the CURRENT committed aggregate's slice value (CAS loop),
    // and the write lands as ONE committed aggregate state.
    fun mutateConnection(transform: (ConnectionState) -> ConnectionState) =
        state.update { it.copy(connection = transform(it.connection)) }
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
    fun mutateHost(transform: (HostState) -> HostState) =
        state.update { it.copy(host = transform(it.host)) }
    /** §history-load-fix / §A5-3 B1: CAS write of the expansion map. */
    fun mutateExpandedParts(transform: (Map<String, Boolean>) -> Map<String, Boolean>) =
        state.update { it.copy(expandedParts = transform(it.expandedParts)) }
    /** §A5-3 B1: CAS write of the nav slice. */
    fun mutateNav(transform: (NavState) -> NavState) =
        state.update { it.copy(nav = transform(it.nav)) }

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
