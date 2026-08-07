package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.data.repository.http.hostPortFromUrl
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Singleton
import javax.inject.Inject

sealed interface ConnectionBootstrapOutcome {
    data class Success(
        val identity: ConnectionIdentity,
        val health: HealthResponse,
    ) : ConnectionBootstrapOutcome

    data class Failed(val error: Throwable) : ConnectionBootstrapOutcome
}

@Singleton
class ConnectionBootstrapEngine internal constructor(
    private val configResolver: EffectiveConnectionConfigResolver,
    private val settingsManager: SettingsManager,
    private val repository: OpenCodeRepository,
    private val identityStore: ConnectionIdentityStore,
    private val serverCompatProfile: ServerCompatProfile,
    private val hasActivity: () -> Boolean,
) {
    private data class InFlight(
        val key: EffectiveConnectionConfig,
        val result: CompletableDeferred<ConnectionBootstrapOutcome>,
    )

    private val mutex = Mutex()
    private var inFlight: InFlight? = null
    private var configuredKey: EffectiveConnectionConfig? = null

    suspend fun bootstrap(): ConnectionBootstrapOutcome {
        while (true) {
            val key = runCatching { configResolver.resolve() ?: error("No effective connection config") }
                .getOrElse { return ConnectionBootstrapOutcome.Failed(it) }
            var owner = false
            val flight = mutex.withLock {
                inFlight ?: InFlight(
                    key,
                    CompletableDeferred<ConnectionBootstrapOutcome>(),
                ).also {
                    inFlight = it
                    owner = true
                }
            }
            if (!owner) {
                val joined = flight.result.await()
                if (flight.key == key) return joined
                // A different persisted configuration was bootstrapping. It
                // has now settled; loop and bootstrap the latest key rather
                // than racing two repository.configure calls.
                continue
            }

            try {
                val outcome = try {
                    performAttempt(key)
                } catch (e: CancellationException) {
                    // The owning caller may be a superseded Service start.
                    // Settle joiners explicitly instead of cancel-propagating
                    // into an unrelated CC caller sharing this flight.
                    flight.result.complete(ConnectionBootstrapOutcome.Failed(e))
                    throw e
                } catch (t: Throwable) {
                    ConnectionBootstrapOutcome.Failed(t)
                }
                flight.result.complete(outcome)
                return outcome
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (inFlight?.result === flight.result) inFlight = null
                    }
                }
            }
        }
    }

    private suspend fun performAttempt(key: EffectiveConnectionConfig): ConnectionBootstrapOutcome {
        val clientCert = key.clientCertId?.let(settingsManager::loadClientCertMaterial)
        if (key.mtlsEnabled && clientCert == null) {
            return ConnectionBootstrapOutcome.Failed(IllegalStateException("mTLS client certificate unavailable"))
        }
        val expected = identityStore.currentIdentity.value
        val expectedEpoch = identityStore.currentEpoch()
        val matchingIdentity = expected?.profileId == key.connectionKey &&
            expected.normalizedWorkdir == key.workdir && expected.endpointFp == key.url
        if (configuredKey != key || !matchingIdentity) {
            repository.configure(
                key.url,
                key.username,
                key.password,
                hostPort = hostPortFromUrl(key.url),
                clientCert = clientCert,
                slim = key.slim,
                trustAll = key.trustAll,
            )
            val currentKey = configResolver.resolve()
            if (currentKey == key) configuredKey = key else return ConnectionBootstrapOutcome.Failed(
                IllegalStateException("Config changed during bootstrap")
            )
        } else {
            DebugLog.d("Bootstrap", "skipping configure: matching key/identity")
        }
        if (configResolver.resolve() != key ||
            identityStore.currentEpoch() != expectedEpoch
        ) {
            return ConnectionBootstrapOutcome.Failed(IllegalStateException("Config or epoch changed"))
        }

        while (true) {
            val healthResult = repository.checkHealth()
            val health = healthResult.getOrNull()
            if (health != null && health.healthy) {
                val finalKey = configResolver.resolve()
                if (finalKey != key ||
                    identityStore.currentEpoch() != expectedEpoch
                ) {
                    return ConnectionBootstrapOutcome.Failed(IllegalStateException("Config or epoch changed"))
                }
                // §stale-identity-heal: identity workdir can drift from the
                // resolved key's workdir when the user switches directories
                // WITHOUT triggering beginReconfigure() (workdir writes don't
                // touch identityStore). The prior identity-mismatch sub-clause
                // killed the bootstrap here, but bindIfCurrent (below) is the
                // ONLY path that re-binds identity — placing it after the
                // mismatch check created a catch-22 where stale identity could
                // never be healed. The true supersession guard is the epoch
                // check above + bindIfCurrent's epoch-CAS (single synchronized
                // critical section, ConnectionIdentityStore.kt:171-179).
                // Removing the identity-mismatch sub-clause lets bindIfCurrent
                // atomically re-bind at the captured epoch, healing the stale
                // workdir field.
                serverCompatProfile.update(health.version)
                val identity = identityStore.bindIfCurrent(
                    key.connectionKey,
                    key.workdir,
                    key.url,
                    expectedEpoch,
                ) ?: return ConnectionBootstrapOutcome.Failed(IllegalStateException("Identity bind failed"))
                return ConnectionBootstrapOutcome.Success(identity, health)
            }
            val error = healthResult.exceptionOrNull()
                ?: IllegalStateException("Server reported unhealthy${health?.version?.let { " ($it)" }.orEmpty()}")
            return ConnectionBootstrapOutcome.Failed(error)
        }
    }
}

@Singleton
class BootstrapRetryPolicy @Inject constructor() {
    val delaysMs: List<Long> = listOf(2_000L, 5_000L, 15_000L, 30_000L, 120_000L, 300_000L)
}
