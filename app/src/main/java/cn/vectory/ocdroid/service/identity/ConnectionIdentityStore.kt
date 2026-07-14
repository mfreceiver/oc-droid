package cn.vectory.ocdroid.service.identity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-level store for the single [ConnectionIdentity] that guards BOTH the
 * SSE collector AND the directory-fetch fan-out (FGS spec §2 «关键约束»: no
 * second private generation).
 *
 * CP1 of the notification Phase-0 switch-over: `ConnectionCoordinator` STILL
 * owns `sseJob`, but identity is threaded through this store so that:
 *  - the SSE collector can label each emitted event with the identity it was
 *    captured under ([currentIdentity] at collection start);
 *  - the directory fan-out in `loadInitialData` can guard against stale-host
 *    responses using the SAME epoch (no private second generation);
 *  - `SessionSyncCoordinator.handleEvent(IdentifiedSseEvent)` can validate
 *    `isCurrent(identity)` before any fold/state mutation.
 *
 * Reconfigure protocol (FGS spec §2, strictly ordered):
 *  1. [beginReconfigure] — synchronously increments [currentEpoch] AND nulls
 *     the old identity. MUST be called BEFORE `repository.configure()` so the
 *     epoch bump invalidates in-flight collectors directory-fetches from this
 *     instant. Returns the new epoch so the caller can emit it downstream.
 *  2. repository / OkHttp client rebuild (host / profile / workdir).
 *  3. [bind] — establishes the new [currentIdentity] at the current epoch.
 *  4. The new SSE collector + directory fan-out capture the bound identity;
 *    [isCurrent] validates every frame against it.
 *
 * Thread-safe via [AtomicReference] + [AtomicLong] (the fields are read/written
 * from the main dispatcher in production, but the atomic discipline is
 * belt-and-suspenders and required by tests that use UnconfinedTestDispatcher
 * where suspension points can interleave).
 *
 * `@Singleton` + `@Inject constructor` — no Hilt `@Binds`/`@Provides` module
 * needed; Hilt auto-provides this concrete class.
 */
@Singleton
class ConnectionIdentityStore @Inject constructor() {

    private val currentEpochAtomic = AtomicLong(0L)
    private val currentIdentityAtomic = AtomicReference<ConnectionIdentity?>(null)
    private val _currentIdentity = MutableStateFlow<ConnectionIdentity?>(null)

    /**
     * The current bound identity, or null when no identity is established
     * (cold start before the first [bind], or after [beginReconfigure] but
     * before the next [bind]).
     *
     * SSE collectors + directory fetches capture this value at their start;
     * a frame is stale iff [isCurrent] returns false for its capture-time
     * identity.
     */
    val currentIdentity: StateFlow<ConnectionIdentity?> = _currentIdentity.asStateFlow()

    /**
     * The current monotonic generation counter. Bumped on every [beginReconfigure].
     * The single guard for both the SSE collector and the directory-fetch fan-out
     * (FGS spec §2 «关键约束»).
     */
    fun currentEpoch(): Long = currentEpochAtomic.get()

    /**
     * SYNCHRONOUSLY increments [currentEpoch] AND invalidates the old identity
     * (sets [currentIdentity] to null). Any in-flight collector / directory
     * fetch whose capture-time identity was the OLD one becomes stale from
     * this instant — [isCurrent] returns false for it.
     *
     * MUST be called BEFORE `repository.configure()` runs (FGS spec §2 step 1)
     * so the epoch bump is guaranteed to precede any repository/client rebuild.
     * The async effect-bus emission path (CancelSseForReconfigure →
     * ConnectionCoordinator.cancelSseForReconfigure) does NOT guarantee this
     * ordering — that is why the true barrier origin is the caller
     * (HostProfileController.configure*), which calls this method FIRST.
     *
     * Returns the new epoch so the caller can pass it downstream (e.g. emit
     * `HostReconfigured(newEpoch)`).
     */
    fun beginReconfigure(): Long {
        val newEpoch = currentEpochAtomic.incrementAndGet()
        currentIdentityAtomic.set(null)
        _currentIdentity.value = null
        return newEpoch
    }

    /**
     * Builds + sets [currentIdentity] at the current epoch. Called after
     * `repository.configure()` has settled the new host/profile/workdir.
     * Returns the newly bound identity so the caller can capture it directly
     * (avoiding a re-read race between bind + capture).
     */
    fun bind(
        serverGroupFp: String,
        normalizedWorkdir: String,
        endpointFp: String,
    ): ConnectionIdentity {
        val identity = ConnectionIdentity(
            epoch = currentEpochAtomic.get(),
            serverGroupFp = serverGroupFp,
            normalizedWorkdir = normalizedWorkdir,
            endpointFp = endpointFp,
        )
        currentIdentityAtomic.set(identity)
        _currentIdentity.value = identity
        return identity
    }

    /**
     * True iff [identity].epoch == [currentEpoch] AND the fingerprint fields
     * match the current bound identity. A frame whose capture-time identity
     * fails this check belongs to a stale (pre-reconfigure) collector or
     * directory fetch and MUST be dropped by every consumer BEFORE any side
     * effect (state mutation, notification, fold).
     *
     * Returns false when no identity is currently bound (cold start / post-
     * beginReconfigure) — nothing is "current" in that window.
     */
    fun isCurrent(identity: ConnectionIdentity): Boolean {
        val current = currentIdentityAtomic.get() ?: return false
        return identity.epoch == currentEpochAtomic.get() &&
            identity.serverGroupFp == current.serverGroupFp &&
            identity.normalizedWorkdir == current.normalizedWorkdir &&
            identity.endpointFp == current.endpointFp
    }
}
