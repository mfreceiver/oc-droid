package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authoritative global busy-source implementation (FGS spec §3 / §3.1, dev-design P0.4).
 *
 * Holds the single source of truth for per-session busy status, keyed by the composite
 * [SessionStatusKey] = `(serverGroupFp, workdir, sessionId)`. Consumed by the lifecycle
 * coordinator (Lane C) and the notification display layer (Phase 1) via the two projected
 * [StateFlow]s on this class.
 *
 * **Authoritativeness rules enforced here** (FGS spec §3 / §3.1):
 *  - busy is **global**, keyed by [SessionStatusKey] so two sessions with the same id on
 *    different hosts / workdirs cannot collide;
 *  - the Phase-0 main path consumes the host-level `getSessionStatus()` once and bins each
 *    returned `sessionId` to its workdir via `sessionsById[sessionId].directory`;
 *  - a failed status fetch labels every known session [SessionBusyStatus.Unknown], and the
 *    merge-timing rule (below) ensures a prior fresher `Busy`/`Retry` SSE status is **not**
 *    clobbered by the failure snapshot — `Unknown` thus never wrongly clears [globalBusy]
 *    and never enters the idle grace window;
 *  - a REST response **must not** overwrite an SSE status whose source time is later than
 *    the REST request-start (merge timing, FGS spec §3.1).
 *
 * **Thread-safety**: the internal [state] map is held in an [AtomicReference] and updated
 * via a CAS loop; the two projected [StateFlow]s are recomputed inside the same CAS step
 * so external observers always see a consistent (`globalBusy`, `statusByKey`) pair.
 *
 * This class is **not yet wired** into the live SSE path / `ConnectionCoordinator` — that
 * switch-over is Lane C's job. The two input methods ([refresh] / [applySseStatus]) are
 * defined now so Lane C can compile against them.
 */
@Singleton
class StatusAggregatorImpl @Inject constructor(
    private val repository: OpenCodeRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : StatusAggregator {

    /**
     * Internal per-key entry. Carries the [status] label, the [sourceTimeMs] used for merge
     * timing (FGS spec §3.1), and a [fresh] flag distinguishing entries that came from a
     * successful REST snapshot this cycle (fresh = true) from SSE-driven or failure entries
     * (fresh = false). The freshness flag is consumed by the idle-grace decision in the
     * lifecycle coordinator (Lane C).
     */
    private data class Entry(
        val status: SessionBusyStatus,
        val sourceTimeMs: Long,
        val fresh: Boolean,
    )

    private val state = AtomicReference<Map<SessionStatusKey, Entry>>(emptyMap())

    /** serverGroupFp of the most recent [refresh]; scopes [globalBusy] to the live identity. */
    private val currentGroupFp = AtomicReference("")

    private val _globalBusy = MutableStateFlow(false)
    private val _statusByKey = MutableStateFlow<Map<SessionStatusKey, SessionBusyStatus>>(emptyMap())

    override val globalBusy: StateFlow<Boolean> = _globalBusy.asStateFlow()
    override val statusByKey: StateFlow<Map<SessionStatusKey, SessionBusyStatus>> = _statusByKey.asStateFlow()

    /**
     * REST-driven refresh of the global busy source (FGS spec §3 «Phase 0 主路径», §3.1 merge timing).
     *
     * Snapshots `requestStartMs = clock()` **before** the call so the merge-timing rule can
     * reject the snapshot against SSE updates that landed while the request was in flight.
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
     *
     * @param identity The current connection identity — its `serverGroupFp` scopes the
     *  composite keys and the [globalBusy] projection.
     * @param sessionsById The merged 3-source `id → Session` map (produced upstream by
     *  `SessionTree.allSessionsById`). Used to resolve `sessionId → directory`.
     */
    suspend fun refresh(identity: ConnectionIdentity, sessionsById: Map<String, Session>) {
        currentGroupFp.set(identity.serverGroupFp)
        val requestStartMs = clock()
        val result = withContext(Dispatchers.IO) { repository.getSessionStatus() }
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
                            next[key] = Entry(serverStatus.toBusyStatus(), requestStartMs, fresh = true)
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
            onFailure = {
                update { current ->
                    val next = current.toMutableMap()
                    for ((sessionId, session) in sessionsById) {
                        val key = SessionStatusKey(identity.serverGroupFp, session.directory, sessionId)
                        val prev = next[key]
                        if (prev == null || requestStartMs >= prev.sourceTimeMs) {
                            next[key] = Entry(SessionBusyStatus.Unknown, requestStartMs, fresh = false)
                        }
                    }
                    next.toMap()
                }
            },
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
     *  The key's `serverGroupFp` also updates [currentGroupFp] so [globalBusy] reflects the
     *  most recently active identity (the bridge guarantees only current-identity frames
     *  reach this method).
     * @param status The SSE-observed status (typically `Busy` / `Retry` / `Idle`).
     * @param sourceTimeMs Monotonic arrival time of the SSE event; the merge-timing arbiter.
     */
    fun applySseStatus(key: SessionStatusKey, status: SessionBusyStatus, sourceTimeMs: Long) {
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
     * Atomically swap the state map and recompute the two projected [StateFlow]s inside the
     * same CAS step. [transform] MUST be pure — it is re-invoked on every CAS retry.
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
        _globalBusy.value = map.any { (key, entry) ->
            key.serverGroupFp == fp &&
                (entry.status == SessionBusyStatus.Busy || entry.status == SessionBusyStatus.Retry)
        }
        _statusByKey.value = map.mapValues { it.value.status }
    }

    private fun SessionStatus.toBusyStatus(): SessionBusyStatus = when {
        isRetry -> SessionBusyStatus.Retry
        isBusy -> SessionBusyStatus.Busy
        isIdle -> SessionBusyStatus.Idle
        else -> SessionBusyStatus.Busy
    }
}
