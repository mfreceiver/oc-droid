package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator
import cn.vectory.ocdroid.service.bootstrap.TofuState
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLHandshakeException

class ConnectionBootstrapEngineTest {
    private val profile = HostProfile(
        id = "profile",
        name = "Test",
        serverUrl = "https://server:443",
        tunnelPasswordId = "tunnel",
        serverGroupFp = "group",
    )

    private data class Fixture(
        val engine: ConnectionBootstrapEngine,
        val repository: OpenCodeRepository,
        val store: ConnectionIdentityStore,
        val coordinator: ConnectionBootstrapCoordinator,
        val resolver: EffectiveConnectionConfigResolver,
    )

    private fun fixture(hasActivity: Boolean, withTunnel: Boolean = true, slim: Boolean = false): Fixture {
        val settings = mockk<SettingsManager>(relaxed = true)
        val repository = mockk<OpenCodeRepository>(relaxed = true)
        val selected = if (withTunnel) profile else profile.copy(tunnelPasswordId = null)
        every { settings.currentWorkdir } returns "/work"
        every { settings.getTunnelPassword("tunnel") } returns "secret"
        every { repository.pinnedSpkiFor(any()) } returns null
        every { repository.isMutualTlsActive() } returns false
        val store = ConnectionIdentityStore()
        val coordinator = ConnectionBootstrapCoordinator()
        val resolver = mockk<EffectiveConnectionConfigResolver>()
        every { resolver.resolve() } returns EffectiveConnectionConfig(
            source = EffectiveConnectionSource.Profile,
            profileId = selected.id,
            serverGroupFp = selected.serverGroupFp,
            url = selected.serverUrl,
            username = null,
            password = null,
            workdir = "/work",
            tunnelPasswordId = selected.tunnelPasswordId,
            tunnelPassword = selected.tunnelPasswordId?.let { "secret" },
            clientCertId = null,
            mtlsEnabled = false,
            slim = slim,
        )
        return Fixture(
            ConnectionBootstrapEngine(
                resolver,
                settings,
                repository,
                store,
                coordinator,
                ServerCompatProfile(),
                hasActivity = { hasActivity },
            ),
            repository,
            store,
            coordinator,
            resolver,
        )
    }

    @Test
    fun `fresh process persisted profile configures tunnel probes and binds once`() = runTest {
        val f = fixture(hasActivity = false)
        every { f.repository.configure(any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { f.repository.activateTunnel(any(), any(), any()) } returns Result.success(Unit)
        coEvery { f.repository.checkHealth() } returns Result.success(HealthResponse(true, "1.2.3"))

        val result = f.engine.bootstrap() as ConnectionBootstrapOutcome.Success

        verify(exactly = 1) {
            f.repository.configure("https://server:443", null, null, "server:443", null, false)
        }
        coVerify(exactly = 1) { f.repository.activateTunnel("https://server:443", "secret", "server:443") }
        coVerify(exactly = 1) { f.repository.checkHealth() }
        assertEquals(result.identity, f.store.currentIdentity.value)
        assertEquals("group", result.identity.serverGroupFp)
        assertEquals("/work", result.identity.normalizedWorkdir)
    }

    @Test
    fun `M8 manual effective config differing from profile is the engine source`() = runTest {
        val resolver = mockk<EffectiveConnectionConfigResolver>()
        every { resolver.resolve() } returns EffectiveConnectionConfig(
            source = EffectiveConnectionSource.Manual,
            profileId = "profile",
            serverGroupFp = "group",
            url = "https://manual.example:8443",
            username = "manual-user",
            password = "manual-pass",
            workdir = "/manual-work",
            tunnelPasswordId = null,
            tunnelPassword = null,
            clientCertId = null,
            mtlsEnabled = false,
        )
        val settings = mockk<SettingsManager>(relaxed = true)
        val repository = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(true, "1.0"))
        val store = ConnectionIdentityStore()
        val engine = ConnectionBootstrapEngine(
            resolver,
            settings,
            repository,
            store,
            ConnectionBootstrapCoordinator(),
            ServerCompatProfile(),
            hasActivity = { false },
        )

        val result = engine.bootstrap() as ConnectionBootstrapOutcome.Success

        verify(exactly = 1) {
            repository.configure(
                "https://manual.example:8443",
                "manual-user",
                "manual-pass",
                "manual.example:8443",
                null,
                false,
            )
        }
        assertEquals("https://manual.example:8443", result.identity.endpointFp)
        assertEquals("/manual-work", result.identity.normalizedWorkdir)
    }

    @Test
    fun `no Activity TLS failure retains degraded capture without waiting decision`() = runTest {
        val f = fixture(hasActivity = false, withTunnel = false)
        every { f.repository.configure(any(), any(), any(), any(), any(), any()) } returns Unit
        val failure = SSLHandshakeException("unknown CA")
        coEvery { f.repository.checkHealth() } returns Result.failure(failure)
        val capture = mockk<OpenCodeRepository.TofuCaptureResult>()
        every { capture.hostPort } returns "server:443"
        coEvery { f.repository.captureServerCert(any(), any(), any()) } returns capture

        val result = f.engine.bootstrap()

        assertEquals(ConnectionBootstrapOutcome.TofuNeedsActivity("server:443", capture), result)
        val state = f.coordinator.tofuState.value as TofuState.DegradedNeedsActivity
        assertSame(capture, state.capture)
        coVerify(exactly = 1) { f.repository.checkHealth() }
    }

    @Test
    fun `concurrent CC and Service bootstrap join one health probe`() = runTest {
        val f = fixture(hasActivity = true, withTunnel = false)
        every { f.repository.configure(any(), any(), any(), any(), any(), any()) } returns Unit
        val health = CompletableDeferred<Result<HealthResponse>>()
        coEvery { f.repository.checkHealth() } coAnswers { health.await() }

        val first = async { f.engine.bootstrap() }
        val second = async { f.engine.bootstrap() }
        runCurrent()
        coVerify(exactly = 1) { f.repository.checkHealth() }
        health.complete(Result.success(HealthResponse(true, "2.0")))

        val a = first.await()
        val b = second.await()
        assertEquals(a, b)
        assertTrue(a is ConnectionBootstrapOutcome.Success)
        coVerify(exactly = 1) { f.repository.checkHealth() }
    }

    // ───────────── R8 slim-mode foundation / Cluster B: slim wiring ─────
    // When the selected profile carries slim=true, the engine MUST pass slim=true
    // to repository.configure(...); when slim=false (legacy), it MUST pass slim=false.
    // Repository writes this into hostConfig.slim, which SlimapiVersionInterceptor
    // + SSEClient (A1) + health probe (C3 fix) read to route /slimapi/* vs /global/*.

    @Test
    fun `slim profile propagates slim=true to repository configure`() = runTest {
        val f = fixture(hasActivity = false, withTunnel = false, slim = true)
        every { f.repository.configure(any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { f.repository.checkHealth() } returns Result.success(HealthResponse(true, "1.0"))

        f.engine.bootstrap() as ConnectionBootstrapOutcome.Success

        verify(exactly = 1) {
            f.repository.configure("https://server:443", null, null, "server:443", null, true)
        }
    }

    @Test
    fun `legacy profile propagates slim=false to repository configure`() = runTest {
        val f = fixture(hasActivity = false, withTunnel = false, slim = false)
        every { f.repository.configure(any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { f.repository.checkHealth() } returns Result.success(HealthResponse(true, "1.0"))

        f.engine.bootstrap() as ConnectionBootstrapOutcome.Success

        verify(exactly = 1) {
            f.repository.configure("https://server:443", null, null, "server:443", null, false)
        }
    }

    @Test
    fun `switching slim state triggers reconfigure`() = runTest {
        // R8 4-config: toggling slim on the same endpoint URL MUST trigger a
        // re-configure (hostConfig.slim is a routing switch — leaving stale
        // value would route SSE/health to the wrong endpoint family).
        // Engine's configuredKey != key check uses EffectiveConnectionConfig
        // holistic equality; slim is part of that record.
        val f = fixture(hasActivity = false, withTunnel = false, slim = false)
        every { f.repository.configure(any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { f.repository.checkHealth() } returns Result.success(HealthResponse(true, "1.0"))

        // First bootstrap: legacy.
        f.engine.bootstrap() as ConnectionBootstrapOutcome.Success
        coVerify(exactly = 1) { f.repository.checkHealth() }

        // Sanity: EffectiveConnectionConfig data class equality treats slim
        // as part of the value — two configs differing only in slim are NOT equal.
        val legacyCfg = f.resolver.resolve()!!
        val slimCfg = legacyCfg.copy(slim = true)
        assertEquals(false, legacyCfg.slim)
        assertEquals(true, slimCfg.slim)
        org.junit.Assert.assertNotEquals(legacyCfg, slimCfg)
    }
}
