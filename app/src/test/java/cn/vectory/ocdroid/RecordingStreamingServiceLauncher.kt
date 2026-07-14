package cn.vectory.ocdroid

import cn.vectory.ocdroid.service.StreamingServiceLauncher
import cn.vectory.ocdroid.service.OwnershipStartResult
import cn.vectory.ocdroid.service.OwnershipRefusal
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import java.util.concurrent.atomic.AtomicInteger

/**
 * CP9 (notify Phase-0 switchover): test-only [StreamingServiceLauncher] that
 * records each [ensureStarted] call. Used by [MainViewModelTestBase] so
 * [cn.vectory.ocdroid.ui.controller.ConnectionCoordinatorTest] + the
 * AppCore/VM test suites can assert "launcher invoked" instead of
 * "repository.connectSSE invoked" after the switchover.
 *
 * Default behavior: reports `true` (Service start issued / not needed) and
 * increments the counter. Tests that need to simulate a refused start
 * (background / platform rejection) can flip [nextResult] before invoking
 * the coordinator.
 *
 * Visibility: `public` (not `internal`) because [MainViewModelTestBase]
 * exposes it via a `protected` field and Kotlin's visibility rule forbids
 * a `protected` field from exposing an `internal` type.
 */
class RecordingStreamingServiceLauncher : StreamingServiceLauncher {
    private val callCountAtomic = AtomicInteger(0)
    val callCount: Int get() = callCountAtomic.get()

    /**
     * The result the next [ensureStarted] call returns. Default `true`
     * (Service start issued or not needed). Set to `false` to simulate a
     * refused start (background or platform rejection).
     */
    @Volatile
    var nextResult: Boolean = true

    val requestedIdentities = mutableListOf<ConnectionIdentity>()

    override suspend fun ensureStarted(identity: ConnectionIdentity): OwnershipStartResult {
        callCountAtomic.incrementAndGet()
        requestedIdentities += identity
        return if (nextResult) OwnershipStartResult.Ready(identity)
        else OwnershipStartResult.Refused(OwnershipRefusal.ServiceStopped)
    }
}
