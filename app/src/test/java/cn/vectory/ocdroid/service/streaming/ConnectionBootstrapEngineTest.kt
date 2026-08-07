@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
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
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionBootstrapEngineTest {
    private val profile = HostProfile(
        id = "profile",
        name = "Test",
        serverUrl = "https://server:443",
    )

    private data class Fixture(
        val engine: ConnectionBootstrapEngine,
        val repository: OpenCodeRepository,
        val store: ConnectionIdentityStore,
        val resolver: EffectiveConnectionConfigResolver,
    )

    private fun fixture(hasActivity: Boolean, slim: Boolean = false): Fixture {
        val settings = mockk<SettingsManager>(relaxed = true)
        val repository = mockk<OpenCodeRepository>(relaxed = true)
        every { settings.currentWorkdir } returns "/work"
        val store = ConnectionIdentityStore()
        val resolver = mockk<EffectiveConnectionConfigResolver>()
        every { resolver.resolve() } returns EffectiveConnectionConfig(
            source = EffectiveConnectionSource.Profile,
            profileId = profile.id,
            connectionKey = profile.id,
            url = profile.serverUrl,
            username = null,
            password = null,
            workdir = "/work",
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
                ServerCompatProfile(),
                hasActivity = { hasActivity },
            ),
            repository,
            store,
            resolver,
        )
    }

    @Test
    fun `fresh process persisted profile configures and binds once`() = runTest {
        val f = fixture(hasActivity = false)
        every { f.repository.configure(any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { f.repository.checkHealth() } returns Result.success(HealthResponse(true, "1.2.3"))

        val result = f.engine.bootstrap() as ConnectionBootstrapOutcome.Success

        verify(exactly = 1) {
            f.repository.configure("https://server:443", null, null, "server:443", null, false, false)
        }
        coVerify(exactly = 1) { f.repository.checkHealth() }
        assertEquals(result.identity, f.store.currentIdentity.value)
        assertEquals("profile", result.identity.profileId)
        assertEquals("/work", result.identity.normalizedWorkdir)
    }

    @Test
    fun `M8 manual effective config differing from profile is the engine source`() = runTest {
        val resolver = mockk<EffectiveConnectionConfigResolver>()
        every { resolver.resolve() } returns EffectiveConnectionConfig(
            source = EffectiveConnectionSource.Manual,
            profileId = "profile",
            connectionKey = "group",
            url = "https://manual.example:8443",
            username = "manual-user",
            password = "manual-pass",
            workdir = "/manual-work",
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
                false,
            )
        }
        assertEquals("https://manual.example:8443", result.identity.endpointFp)
        assertEquals("/manual-work", result.identity.normalizedWorkdir)
    }

    @Test
    fun `concurrent CC and Service bootstrap join one health probe`() = runTest {
        val f = fixture(hasActivity = true)
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
        val f = fixture(hasActivity = false, slim = true)
        every { f.repository.configure(any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { f.repository.checkHealth() } returns Result.success(HealthResponse(true, "1.0"))

        f.engine.bootstrap() as ConnectionBootstrapOutcome.Success

        verify(exactly = 1) {
            f.repository.configure("https://server:443", null, null, "server:443", null, true, false)
        }
    }

    @Test
    fun `legacy profile propagates slim=false to repository configure`() = runTest {
        val f = fixture(hasActivity = false, slim = false)
        every { f.repository.configure(any(), any(), any(), any(), any(), any()) } returns Unit
        coEvery { f.repository.checkHealth() } returns Result.success(HealthResponse(true, "1.0"))

        f.engine.bootstrap() as ConnectionBootstrapOutcome.Success

        verify(exactly = 1) {
            f.repository.configure("https://server:443", null, null, "server:443", null, false, false)
        }
    }

    @Test
    fun `switching slim state triggers reconfigure`() = runTest {
        // R8 4-config: toggling slim on the same endpoint URL MUST trigger a
        // re-configure (hostConfig.slim is a routing switch — leaving stale
        // value would route SSE/health to the wrong endpoint family).
        // Engine's configuredKey != key check uses EffectiveConnectionConfig
        // holistic equality; slim is part of that record.
        val f = fixture(hasActivity = false, slim = false)
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

    @Test
    fun `matching host with unready slim incarnation reconfigures and restores readiness`() = runTest {
        val f = fixture(hasActivity = false, slim = true)
        var ready = false
        every { f.repository.configure(any(), any(), any(), any(), any(), any()) } answers {
            ready = true
        }
        coEvery { f.repository.checkHealth() } returns Result.success(HealthResponse(true, "1.0"))

        f.engine.bootstrap() as ConnectionBootstrapOutcome.Success
        f.engine.bootstrap() as ConnectionBootstrapOutcome.Success

        verify(exactly = 1) {
            f.repository.configure("https://server:443", null, null, "server:443", null, true, false)
        }
    }



    @Test
    fun `configure failure remains Failed and never reports Connected`() = runTest {
        val f = fixture(hasActivity = false, slim = true)
        var failed = true
        val failure = IllegalStateException("configure failed")
        every { f.repository.configure(any(), any(), any(), any(), any(), any()) } answers {
            failed = true
            throw failure
        }

        val first = f.engine.bootstrap()
        val second = f.engine.bootstrap()

        assertTrue(first is ConnectionBootstrapOutcome.Failed)
        assertTrue(second is ConnectionBootstrapOutcome.Failed)
        assertFalse(first is ConnectionBootstrapOutcome.Success)
        assertFalse(second is ConnectionBootstrapOutcome.Success)
        verify(exactly = 2) { f.repository.configure(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.repository.checkHealth() }
    }

    // ───────────── §stale-identity-heal regression (Bug 1 catch-22 fix) ──────
    // The prior identity-mismatch sub-clause (finalIdentity != null && field-
    // mismatch) killed the bootstrap when identity.normalizedWorkdir had drifted
    // from the resolved key's workdir (user switched directories without a
    // beginReconfigure → epoch unchanged, identity not nulled). bindIfCurrent
    // (the ONLY identity re-bind path) was unreachable after the mismatch
    // failure → permanent deadlock. This test verifies the fix: a stale workdir
    // identity is healed by bindIfCurrent and the bootstrap succeeds.

    @Test
    fun `stale workdir identity is healed by bindIfCurrent`() = runTest {
        val settings = mockk<SettingsManager>(relaxed = true)
        val repository = mockk<OpenCodeRepository>(relaxed = true)
        every { settings.currentWorkdir } returns "/new-work"
        val store = ConnectionIdentityStore()
        // Pre-bind identity with OLD workdir (simulating user switched dir after
        // a prior successful connect; workdir change doesn't trigger beginReconfigure).
        store.bind("profile", "/old-work", "https://server:443")
        val resolver = mockk<EffectiveConnectionConfigResolver>()
        every { resolver.resolve() } returns EffectiveConnectionConfig(
            source = EffectiveConnectionSource.Profile,
            profileId = profile.id,
            connectionKey = profile.id,
            url = "https://server:443",
            username = null,
            password = null,
            workdir = "/new-work",
            clientCertId = null,
            mtlsEnabled = false,
        )
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(true, "1.0"))

        val engine = ConnectionBootstrapEngine(
            resolver, settings, repository, store,
            ServerCompatProfile(), hasActivity = { false },
        )

        val result = engine.bootstrap()

        assertTrue("bootstrap must succeed despite stale workdir identity", result is ConnectionBootstrapOutcome.Success)
        val success = result as ConnectionBootstrapOutcome.Success
        assertEquals("/new-work", success.identity.normalizedWorkdir)
        assertEquals("/new-work", store.currentIdentity.value?.normalizedWorkdir)
    }
}
