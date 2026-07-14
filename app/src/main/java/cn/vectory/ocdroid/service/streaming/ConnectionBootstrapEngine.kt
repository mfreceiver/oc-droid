package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.data.repository.http.TofuDecision
import cn.vectory.ocdroid.data.repository.http.hostPortFromUrl
import cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.util.SettingsManager
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

data class EffectiveConnectionConfig(
    val profileId: String,
    val serverGroupFp: String,
    val serverUrl: String,
    val username: String?,
    val password: String?,
    val workdir: String,
    val tunnelPasswordId: String?,
    val tunnelPassword: String?,
    val clientCertId: String?,
    val mtlsEnabled: Boolean,
)

@Singleton
class ConnectionBootstrapEngine internal constructor(
    private val hostProfileStore: HostProfileStore,
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
    private var tunnelActivatedKey: EffectiveConnectionConfig? = null

    suspend fun bootstrap(): ConnectionBootstrapOutcome {
        while (true) {
            val key = runCatching { effectiveConfig() }
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

    private fun effectiveConfig(): EffectiveConnectionConfig {
        val profile = hostProfileStore.currentProfile()
        val username = profile.basicAuth?.username
        val password = profile.basicAuth?.passwordId?.let(settingsManager::basicAuthPassword)
        val tunnelPassword = profile.tunnelPasswordId?.let(settingsManager::getTunnelPassword)
        return EffectiveConnectionConfig(
            profileId = profile.id,
            serverGroupFp = profile.serverGroupFp.ifBlank { profile.id },
            serverUrl = profile.serverUrl,
            username = username,
            password = password,
            workdir = settingsManager.currentWorkdir.orEmpty(),
            tunnelPasswordId = profile.tunnelPasswordId,
            tunnelPassword = tunnelPassword,
            clientCertId = profile.clientCertId.takeIf { profile.mtlsEnabled },
            mtlsEnabled = profile.mtlsEnabled,
        )
    }

    private suspend fun performAttempt(key: EffectiveConnectionConfig): ConnectionBootstrapOutcome {
        val clientCert = key.clientCertId?.let(settingsManager::loadClientCertMaterial)
        if (key.mtlsEnabled && clientCert == null) {
            return ConnectionBootstrapOutcome.Failed(IllegalStateException("mTLS client certificate unavailable"))
        }
        val expected = identityStore.currentIdentity.value
        val matchingIdentity = expected?.serverGroupFp == key.serverGroupFp &&
            expected.normalizedWorkdir == key.workdir && expected.endpointFp == key.serverUrl
        if (configuredKey != key || !matchingIdentity) {
            repository.configure(
                key.serverUrl,
                key.username,
                key.password,
                hostPort = hostPortFromUrl(key.serverUrl),
                clientCert = clientCert,
            )
            configuredKey = key
        }
        if (key.tunnelPasswordId != null && tunnelActivatedKey != key) {
            val password = key.tunnelPassword
                ?: return ConnectionBootstrapOutcome.Failed(IllegalStateException("Tunnel password unavailable"))
            repository.activateTunnel(
                key.serverUrl,
                password,
                hostPort = hostPortFromUrl(key.serverUrl),
            ).getOrElse { return ConnectionBootstrapOutcome.Failed(it) }
            tunnelActivatedKey = key
        }

        while (true) {
            val healthResult = repository.checkHealth()
            val health = healthResult.getOrNull()
            if (health != null && health.healthy) {
                serverCompatProfile.update(health.version)
                val current = identityStore.currentIdentity.value
                val identity = if (current != null &&
                    current.serverGroupFp == key.serverGroupFp &&
                    current.normalizedWorkdir == key.workdir &&
                    current.endpointFp == key.serverUrl
                ) current else identityStore.bind(key.serverGroupFp, key.workdir, key.serverUrl)
                bootstrapCoordinator.clearPendingTofu()
                return ConnectionBootstrapOutcome.Success(identity, health)
            }
            val error = healthResult.exceptionOrNull()
                ?: IllegalStateException("Server reported unhealthy${health?.version?.let { " ($it)" }.orEmpty()}")
            val tlsFailure = generateSequence(error) { it.cause }
                .any { it is SSLException || it is CertificateException }
            val hostPort = hostPortFromUrl(key.serverUrl)
            if (!tlsFailure || hostPort == null || repository.pinnedSpkiFor(hostPort) != null ||
                repository.isMutualTlsActive()
            ) {
                return ConnectionBootstrapOutcome.Failed(error)
            }
            bootstrapCoordinator.setPendingTofu(hostPort)
            val capture = repository.captureServerCert(key.serverUrl, hostPort, clientCert)
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
            // Retry this same attempt immediately: TOFU does not consume a
            // network-retry slot or apply a retry delay.
        }
    }
}

@Singleton
class BootstrapRetryPolicy @Inject constructor() {
    val delaysMs: List<Long> = listOf(2_000L, 5_000L, 15_000L, 30_000L, 120_000L, 300_000L)
}
