package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.data.repository.http.TofuDecision
import cn.vectory.ocdroid.data.repository.http.hostPortFromUrl
import cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.inject.Singleton
import javax.inject.Inject

sealed interface ConnectionBootstrapOutcome {
    data class Success(
        val identity: ConnectionIdentity,
        val health: HealthResponse,
    ) : ConnectionBootstrapOutcome

    data class TofuNeedsActivity(
        val hostPort: String,
        val capture: OpenCodeRepository.TofuCaptureResult,
    ) : ConnectionBootstrapOutcome



    data class Failed(val error: Throwable) : ConnectionBootstrapOutcome
}

@Singleton
class ConnectionBootstrapEngine internal constructor(
    private val configResolver: EffectiveConnectionConfigResolver,
    private val settingsManager: SettingsManager,
    private val repository: OpenCodeRepository,
    private val identityStore: ConnectionIdentityStore,
    private val bootstrapCoordinator: ConnectionBootstrapCoordinator,
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
        val matchingIdentity = expected?.serverGroupFp == key.serverGroupFp &&
            expected.normalizedWorkdir == key.workdir && expected.endpointFp == key.url
        if (configuredKey != key || !matchingIdentity) {
            repository.configure(
                key.url,
                key.username,
                key.password,
                hostPort = hostPortFromUrl(key.url),
                clientCert = clientCert,
                slim = key.slim,
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
                val finalIdentity = identityStore.currentIdentity.value
                if (finalKey != key ||
                    identityStore.currentEpoch() != expectedEpoch ||
                    (finalIdentity != null && (finalIdentity.serverGroupFp != key.serverGroupFp ||
                        finalIdentity.normalizedWorkdir != key.workdir || finalIdentity.endpointFp != key.url))
                ) {
                    return ConnectionBootstrapOutcome.Failed(IllegalStateException("Config or identity changed"))
                }
                serverCompatProfile.update(health.version)
                val identity = identityStore.bindIfCurrent(
                    key.serverGroupFp,
                    key.workdir,
                    key.url,
                    expectedEpoch,
                ) ?: return ConnectionBootstrapOutcome.Failed(IllegalStateException("Identity bind failed"))
                bootstrapCoordinator.clearPendingTofu()
                return ConnectionBootstrapOutcome.Success(identity, health)
            }
            val error = healthResult.exceptionOrNull()
                ?: IllegalStateException("Server reported unhealthy${health?.version?.let { " ($it)" }.orEmpty()}")
            val tlsFailure = generateSequence(error) { it.cause }
                .any { it is SSLException || it is CertificateException }
            val hostPort = hostPortFromUrl(key.url)
            if (!tlsFailure || hostPort == null || repository.pinnedSpkiFor(hostPort) != null ||
                repository.isMutualTlsActive()
            ) {
                return ConnectionBootstrapOutcome.Failed(error)
            }
            bootstrapCoordinator.setPendingTofu(hostPort)
            val capture = repository.captureServerCert(key.url, hostPort, clientCert)
                ?: run {
                    bootstrapCoordinator.clearPendingTofu()
                    return ConnectionBootstrapOutcome.Failed(error)
                }
            bootstrapCoordinator.setPendingCapture(capture)
            if (!hasActivity()) {
                bootstrapCoordinator.markDegradedNeedsActivity()
                return ConnectionBootstrapOutcome.TofuNeedsActivity(hostPort, capture)
            }
            val decision = CompletableDeferred<TofuDecision>()
            bootstrapCoordinator.setTofuDecision(decision)
            val selected = try {
                decision.await()
            } finally {
                bootstrapCoordinator.setTofuDecision(null)
            }
            if (selected is TofuDecision.Cancel) {
                bootstrapCoordinator.clearPendingTofu()
                return ConnectionBootstrapOutcome.Failed(IllegalStateException("Server trust declined"))
            }
            repository.applyTofuDecision(hostPort, selected)
            bootstrapCoordinator.clearPendingTofu()
        }
    }
}

@Singleton
class BootstrapRetryPolicy @Inject constructor() {
    val delaysMs: List<Long> = listOf(2_000L, 5_000L, 15_000L, 30_000L, 120_000L, 300_000L)
}
