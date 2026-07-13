package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Singleton

/**
 * Authoritative global busy-source implementation (FGS spec §3 / §3.1, dev-design P0.4).
 *
 * Implements BOTH [StatusAggregator] (read-only outputs, consumed by the
 * lifecycle coordinator + Phase-1 notification display) and [StatusAggregatorInput]
 * (the feed surface consumed by SSC / the bootstrap coordinator / AppCore).
 *
 * Constructed via `StatusModule.provideStatusAggregatorImpl` (NOT `@Inject
 * constructor`) so the [clock] default param (`{ System.currentTimeMillis() }`)
 * is honored at the construction site — matching the other controllers' pattern
 * (ForegroundCatchUpController / SessionSwitcher / ConnectionCoordinator /
 * SessionSyncCoordinator all take a default-param clock and are wired via
 * `@Provides`). Both [StatusAggregator] and [StatusAggregatorInput] are bound
 * to the SAME `@Singleton` instance via the two `@Binds` in `StatusModule`.
 *
 * Holds the single source of truth for per-session busy status, keyed by the
 * composite [SessionStatusKey] = `(serverGroupFp, workdir, sessionId)`.
 *
 * **Authoritativeness rules enforced here** (FGS spec §3 / §3.1 / §2):
 *  - busy is **global**, keyed by [SessionStatusKey] so two sessions with the same id on
 *    different hosts / workdirs cannot collide;
 *  - the Phase-0 main path consumes the host-level `getSessionStatus()` once and bins each
 *    returned `sessionId` to its workdir via `sessionsById[sessionId].directory`;
 *  - a failed status fetch labels every known session [SessionBusyStatus.Unknown], and the
 *    merge-timing rule (below) ensures a prior fresher `Busy`/`Retry` SSE status is **not**
 *    clobbered by the failure snapshot — `Unknown` thus never wrongly clears [globalBusy]
 *    and never enters the idle grace window;
 *  - a REST response **must not** overwrite an SSE status whose source time is later than
 *    the REST request-start (merge timing, FGS spec §3.1);
 *  - **CP4 REST epoch guard** (FGS spec §2 + §3.1): `refresh` captures
 *    `epochAtRequestStart = identityStore.currentEpoch()` BEFORE the REST call. When
 *    committing the response, the entry is DROPPED if the current epoch differs — a stale
 *    old-epoch REST response cannot mutate current state;
 *  - **CP4 status TTL** (FGS spec §3, ≈30s): an entry is "fresh" only within [STATUS_TTL_MS]
 *    of its source time. [globalState] = [GlobalBusyState.AllIdleFresh] requires EVERY
 *    tracked session under the current identity to be `Idle` AND fresh. Stale `Idle`
 *    entries fall back to [GlobalBusyState.Unknown] (do NOT enter idle grace on stale/unknown);
 *    stale `Busy`/`Retry` entries stay [GlobalBusyState.Busy] (conservative — never silently
 *    drop keep-alive on a possibly-still-busy session).
 *
 * **Thread-safety**: the internal [state] map is held in an [AtomicReference] and updated
 * via a CAS loop; the three projected [StateFlow]s ([globalState], [globalBusy],
 * [statusByKey]) are recomputed inside the same CAS step so external observers always see
 * a consistent triple.
 *
 * **One clock domain**: the injected [clock] is the SOLE source of "now" for both REST
 * request-start (in [refresh]) and any internally-derived freshness check. SSE arrival
 * time is supplied by the caller via [StatusAggregatorInput.applySseStatus]'s
 * `sourceTimeMs`; in production both call sites read the same wall clock
 * (`System.currentTimeMillis()`).
 */
@Singleton
class StatusAggregatorImpl internal constructor(
    private val repository: OpenCodeRepository,
    private val identityStore: ConnectionIdentityStore,
    private val clock: () -> Long,
) : StatusAggregator, StatusAggregatorInput {

    /**
     * Internal per-key entry. Carries the [status] label, the [sourceTimeMs] used for merge
     * timing + the TTL freshness check (FGS spec §3.1 / §3 «status TTL»), and a [fresh]
     * flag distinguishing entries that came from a successful REST snapshot this cycle
     * (fresh = true) from SSE-driven or failure entries (fresh = false). The freshness
     * flag is informational; the authoritative freshness verdict used by [globalState] is
     * the TTL check `now - sourceTimeMs <= [STATUS_TTL_MS]` (a REST entry can age out and
     * flip the global verdict without any new write).
     */
    private data class Entry(
        val status: SessionBusyStatus,
        val sourceTimeMs: Long,
        val fresh: Boolean,
    )

    private val state = AtomicReference<Map<SessionStatusKey, Entry>>(emptyMap())

    /** serverGroupFp of the most recent [refresh]/[applySseStatus]; scopes projections. */
    private val currentGroupFp = AtomicReference("")

    private val _globalState = MutableStateFlow(GlobalBusyState.Unknown)
    private val _globalBusy = MutableStateFlow(false)
    private val _statusByKey = MutableStateFlow<Map<SessionStatusKey, SessionBusyStatus>>(emptyMap())

    override val globalState: StateFlow<GlobalBusyState> = _globalState.asStateFlow()
    override val globalBusy: StateFlow<Boolean> = _globalBusy.asStateFlow()
    override val statusByKey: StateFlow<Map<SessionStatusKey, SessionBusyStatus>> = _statusByKey.asStateFlow()

    // ── StatusAggregatorInput ──────────────────────────────────────────────

    /**
     * REST-driven refresh of the global busy source (FGS spec §3 «Phase 0 主路径»,
     * §3.1 merge timing, §2 epoch guard).
     *
     * **Epoch guard (FGS spec §2 + §3.1, CP4)**: captures
     * `epochAtRequestStart = identityStore.currentEpoch()` BEFORE the REST call.
     * When committing the response (success OR failure), if the current epoch differs
     * from `epochAtRequestStart`, the response is dropped silently — a reconfigure
     * invalidated the in-flight request, and a stale old-epoch response MUST NOT mutate
     * current state.
     *
     * Snapshots `requestStartMs = clock()` **before** the call so the merge-timing rule
     * can reject the snapshot against SSE updates that landed while the request was in
     * flight (and so the [globalState] TTL verdict is consistent with the request's own
     * "fresh as of" time).
     *
     * **On success**: the host-level `getSessionStatus()` response is binned to composite
     * keys via `sessionsById[sessionId].directory`. Returned (active) sessions are labelled
     * [SessionBusyStatus.Busy] / [SessionBusyStatus.Retry] (server prunes idle entries so
     * everything returned is active); sessions present in `sessionsById` but absent from the
     * response are labelled [SessionBusyStatus.Idle]. Each overwrite is gated by
     * `requestStartMs >= entry.sourceTimeMs` so an SSE status newer than the REST
     * request-start is preserved (FGS spec §3.1).
     *
     * **On failure**: every known session in `sessionsById` is labelled
     * [SessionBusyStatus.Unknown] — again gated by merge timing, so a prior fresher
     * `Busy`/`Retry` SSE status survives the failure (Unknown must **not** wrongly clear
     * [globalBusy] nor enter the idle grace window, FGS spec §3).
     */
    override suspend fun refresh(identity: ConnectionIdentity, sessionsById: Map<String, Session>) {
        currentGroupFp.set(identity.serverGroupFp)
        val requestStartMs = clock()
        val epochAtRequestStart = identityStore.currentEpoch()
        val result = withContext(Dispatchers.IO) { repository.getSessionStatus() }
        // CP4 §2 epoch guard: drop the response if a reconfigure invalidated this request
        // mid-flight. The check happens AFTER the suspend (the only place an epoch bump
        // could have happened) and BEFORE any state mutation.
        if (identityStore.currentEpoch() != epochAtRequestStart) return
        result.fold(
            onSuccess = { statuses ->
                update { current ->
                    val next = current.toMutableMap()
                    val activeKeys = HashSet<SessionStatusKey>()
                    for ((sessionId, serverStatus) in statuses) {
                        val session = sessionsById[sessionId] ?: continue
                        val key = SessionStatusKey(identity.serverGroupFp, session.directory, sessionId)
                        activeKeys.add(key)
                        val prev = next[key]
                        if (prev == null || requestStartMs >= prev.sourceTimeMs) {
                            next[key] = Entry(serverStatus.toSessionBusyStatus(), requestStartMs, fresh = true)
                        }
                    }
                    for ((sessionId, session) in sessionsById) {
                        val key = SessionStatusKey(identity.serverGroupFp, session.directory, sessionId)
                        if (key in activeKeys) continue
                        val prev = next[key]
                        if (prev == null || requestStartMs >= prev.sourceTimeMs) {
                            next[key] = Entry(SessionBusyStatus.Idle, requestStartMs, fresh = true)
                        }
                    }
                    next.toMap()
                }
            },
            onFailure = { markRequestFailedInternal(identity, sessionsById, requestStartMs) },
        )
    }

    /**
     * Apply a single SSE-driven status update (FGS spec §3.1 merge timing).
     *
     * Overwrites the entry for [key] iff [sourceTimeMs] `>=` the existing entry's
     * `sourceTimeMs` (equal timestamps also overwrite, so a same-instant SSE frame replaces
     * a prior REST snapshot of the same instant). A strictly older SSE frame is dropped
     * (defensive against out-of-order SSE replay during reconnect).
     *
     * Not a `suspend` function: SSE status frames are control-class events delivered on the
     * bridge's single-consumer channel, and the update is a single CAS step.
     *
     * @param key Composite key — `serverGroupFp` must match the current connection or the
     *  caller's identity check has failed upstream; this class does not second-guess it.
     *  The key's `serverGroupFp` also updates [currentGroupFp] so [globalState] reflects the
     *  most recently active identity (the bridge guarantees only current-identity frames
     *  reach this method).
     * @param status The SSE-observed status (typically `Busy` / `Retry` / `Idle`).
     * @param sourceTimeMs Monotonic arrival time of the SSE event; the merge-timing arbiter.
     */
    override fun applySseStatus(key: SessionStatusKey, status: SessionBusyStatus, sourceTimeMs: Long) {
        currentGroupFp.set(key.serverGroupFp)
        update { current ->
            val prev = current[key]
            if (prev == null || sourceTimeMs >= prev.sourceTimeMs) {
                current + (key to Entry(status, sourceTimeMs, fresh = false))
            } else {
                current
            }
        }
    }

    /**
     * Explicit failure entry (FGS spec §3 «请求失败 → 全局 Unknown»). See
     * [StatusAggregatorInput.markRequestFailed] for the contract.
     */
    override fun markRequestFailed(
        identity: ConnectionIdentity,
        sessionsById: Map<String, Session>,
        sourceTimeMs: Long,
    ) {
        currentGroupFp.set(identity.serverGroupFp)
        markRequestFailedInternal(identity, sessionsById, sourceTimeMs)
    }

    private fun markRequestFailedInternal(
        identity: ConnectionIdentity,
        sessionsById: Map<String, Session>,
        sourceTimeMs: Long,
    ) {
        update { current ->
            val next = current.toMutableMap()
            for ((sessionId, session) in sessionsById) {
                val key = SessionStatusKey(identity.serverGroupFp, session.directory, sessionId)
                val prev = next[key]
                if (prev == null || sourceTimeMs >= prev.sourceTimeMs) {
                    next[key] = Entry(SessionBusyStatus.Unknown, sourceTimeMs, fresh = false)
                }
            }
            next.toMap()
        }
    }

    // ── Internal: CAS step + projection recompute ──────────────────────────

    /**
     * Atomically swap the state map and recompute the three projected [StateFlow]s inside
     * the same CAS step. [transform] MUST be pure — it is re-invoked on every CAS retry.
     */
    private inline fun update(transform: (Map<SessionStatusKey, Entry>) -> Map<SessionStatusKey, Entry>) {
        while (true) {
            val current = state.get()
            val next = transform(current)
            if (next === current) return
            if (state.compareAndSet(current, next)) {
                recompute(next)
                return
            }
        }
    }

    private fun recompute(map: Map<SessionStatusKey, Entry>) {
        val fp = currentGroupFp.get()
        val scoped = map.filterKeys { it.serverGroupFp == fp }
        // globalBusy: any Busy||Retry under the current identity (kept for back-compat
        // with non-lifecycle consumers). Not authoritative for the idle-grace decision.
        val anyBusy = scoped.any { entry ->
            entry.value.status == SessionBusyStatus.Busy ||
                entry.value.status == SessionBusyStatus.Retry
        }
        // globalState: the lifecycle-authoritative tri-state (FGS spec §3 + §3.1, CP4).
        //  - any Busy||Retry → Busy (never silently clear keep-alive on a possibly-still-
        //    busy session, even if the entry is stale).
        //  - else if any Unknown, OR any stale entry, OR no entries → Unknown (do NOT
        //    enter idle grace on stale/unknown; FGS spec §3 «请求失败 → 全局 Unknown»).
        //  - else (every entry Idle AND within TTL) → AllIdleFresh.
        val now = clock()
        val state = when {
            anyBusy -> GlobalBusyState.Busy
            scoped.isEmpty() -> GlobalBusyState.Unknown
            scoped.values.any { it.status == SessionBusyStatus.Unknown } -> GlobalBusyState.Unknown
            scoped.values.any { now - it.sourceTimeMs > STATUS_TTL_MS } -> GlobalBusyState.Unknown
            else -> GlobalBusyState.AllIdleFresh
        }
        _globalBusy.value = anyBusy
        _globalState.value = state
        _statusByKey.value = map.mapValues { it.value.status }
    }

    companion object {
        /**
         * FGS spec §3 «status TTL»: an entry is "fresh" only within ≈30s of its source
         * time. Used ONLY for the [globalState] idle-grace verdict — stale `Busy`/`Retry`
         * entries stay `Busy` (conservative), stale `Idle` entries fall back to `Unknown`
         * (do not enter idle grace on stale data).
         */
        const val STATUS_TTL_MS = 30_000L
    }
}
