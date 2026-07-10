package cn.vectory.ocdroid

import android.util.Log
import cn.vectory.ocdroid.data.model.BasicAuthConfig
import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.PermissionResponse
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.ui.AppCore
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.TunnelActivationState
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.currentSession
import cn.vectory.ocdroid.ui.session.buildSessionTree
import cn.vectory.ocdroid.ui.visibleMessages
import cn.vectory.ocdroid.util.ThemeMode
import app.cash.turbine.test as turbineTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §R-17 batch3e: domain tests split out of the former [MainViewModelTest].
 *
 * Each test constructs an [AppCore] (via [MainViewModelTestBase.createCore])
 * and wraps it in ALL 6 domain VMs. Since the VMs share the same AppCore
 * singleton, slice reads return the same flows regardless of which VM is
 * asked. Tests call VM-owned methods via the matching VM variable
 * (`chatVM.sendMessage()`, `sessionVM.selectSession(...)`, etc.) — no legacy
 * `AppCore` shim extensions remain.
 *
 * §R18 Phase 4 (P2-3): state setup writes slices directly through the
 * AppCore `writeXxx { it.copy(...) }` helpers (former `updateState {}`
 * AppState shim removed). UiEvent Error/Success assertions read the
 * test-only [AppCore.recentTestErrors] / [AppCore.recentTestSuccesses]
 * ring buffers populated by [MainViewModelTestBase.createCore]. AppCore
 * internals that have no VM equivalent (`handleSSEEvent`, `store`,
 * `peekSessionWindow`) are reached through `core` directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostViewModelTest : MainViewModelTestBase() {

    @Test
    fun `saveHostProfile writes basic auth password when basicAuthEdited is true`() = runTest {
        val profile = HostProfile.defaultDirect("http://server.test").copy(
            id = "profile-1",
            basicAuth = BasicAuthConfig(username = "alice", passwordId = "profile-1")
        )

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        hostVM.saveHostProfile(
            profile,
            basicAuthPassword = "new-secret",
            basicAuthEdited = true
        )

        verify { settingsManager.setBasicAuthPassword("profile-1", "new-secret") }
    }

    @Test
    fun `saveHostProfile removes basic auth password when edited and blank`() = runTest {
        val profile = HostProfile.defaultDirect("http://server.test").copy(
            id = "profile-1",
            basicAuth = BasicAuthConfig(username = "alice", passwordId = "profile-1")
        )

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        hostVM.saveHostProfile(
            profile,
            basicAuthPassword = "",
            basicAuthEdited = true
        )

        // blank → setBasicAuthPassword with "" which SettingsManager maps to remove.
        verify { settingsManager.setBasicAuthPassword("profile-1", "") }
    }

    @Test
    fun `saveHostProfile skips basic auth write when basicAuthEdited is false`() = runTest {
        val profile = HostProfile.defaultDirect("http://server.test").copy(
            id = "profile-1",
            basicAuth = BasicAuthConfig(username = "alice", passwordId = "profile-1")
        )

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        hostVM.saveHostProfile(
            profile,
            basicAuthPassword = "whatever",
            basicAuthEdited = false
        )

        verify(exactly = 0) { settingsManager.setBasicAuthPassword(any(), any()) }
    }

    @Test
    fun `saveHostProfile skips tunnel write when tunnelEdited is false`() = runTest {
        val profile = HostProfile.defaultDirect("http://server.test").copy(
            id = "profile-1",
            tunnelPasswordId = "profile-1"
        )

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        hostVM.saveHostProfile(
            profile,
            tunnelPassword = "ignored",
            tunnelEdited = false
        )

        verify(exactly = 0) { settingsManager.setTunnelPassword(any(), any()) }
    }

    @Test
    fun `saveHostProfile writes tunnel password when tunnelEdited is true`() = runTest {
        val profile = HostProfile.defaultDirect("http://server.test").copy(
            id = "profile-1",
            tunnelPasswordId = "profile-1"
        )

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        hostVM.saveHostProfile(
            profile,
            tunnelPassword = "tunnel-secret",
            tunnelEdited = true
        )

        verify { settingsManager.setTunnelPassword("profile-1", "tunnel-secret") }
    }

    @Test
    fun `saveHostProfile clears tunnel password when edited and blank`() = runTest {
        val profile = HostProfile.defaultDirect("http://server.test").copy(
            id = "profile-1",
            tunnelPasswordId = "profile-1"
        )

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        hostVM.saveHostProfile(
            profile,
            tunnelPassword = "",
            tunnelEdited = true
        )

        // blank → setTunnelPassword with "" which SettingsManager maps to remove.
        verify { settingsManager.setTunnelPassword("profile-1", "") }
    }

    @Test
    fun `activateTunnelForCurrentHost surfaces error when profile has no tunnelPasswordId`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        advanceUntilIdle()

        hostVM.activateTunnelForCurrentHost()
        advanceUntilIdle()

        // Does not call the repository, but now surfaces a specific error so the
        // user knows why activation did nothing (previously a silent no-op).
        coVerify(exactly = 0) { repository.activateTunnel(any(), any()) }
        assertTrue(connectionVM.connectionFlow.value.tunnelActivationState is TunnelActivationState.Error)
        assertNotNull(core.recentTestErrors.lastOrNull())
    }

    @Test
    fun `activateTunnelForCurrentHost surfaces error when tunnel password is empty`() = runTest {
        val profileWithTunnel = HostProfile.defaultDirect("http://server.test").copy(
            tunnelPasswordId = "profile-1"
        )
        every { hostProfileStore.currentProfile() } returns profileWithTunnel
        every { settingsManager.getTunnelPassword("profile-1") } returns null

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        advanceUntilIdle()

        hostVM.activateTunnelForCurrentHost()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.activateTunnel(any(), any()) }
        assertTrue(connectionVM.connectionFlow.value.tunnelActivationState is TunnelActivationState.Error)
        assertNotNull(core.recentTestErrors.lastOrNull())
    }

    @Test
    fun `activateTunnelForCurrentHost sets Loading then Success on success`() = runTest {
        val profileWithTunnel = HostProfile.defaultDirect("http://server.test").copy(
            tunnelPasswordId = "profile-1"
        )
        every { hostProfileStore.currentProfile() } returns profileWithTunnel
        every { settingsManager.getTunnelPassword("profile-1") } returns "tunnel-secret"
        coEvery { repository.activateTunnel("http://server.test", "tunnel-secret") } returns Result.success(Unit)
        // §tunnel-refresh: mock checkHealth for auto coldStartReconnect after tunnel activation
        coEvery { repository.checkHealth() } returns
            Result.success(HealthResponse(healthy = true, version = "1.0"))

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        advanceUntilIdle()

        hostVM.activateTunnelForCurrentHost()
        advanceUntilIdle()

        coVerify { repository.activateTunnel("http://server.test", "tunnel-secret") }
        assertEquals(
            cn.vectory.ocdroid.ui.TunnelActivationState.Success,
            connectionVM.connectionFlow.value.tunnelActivationState
        )
    }

    @Test
    fun `activateTunnelForCurrentHost sets Error on failure`() = runTest {
        val profileWithTunnel = HostProfile.defaultDirect("http://server.test").copy(
            tunnelPasswordId = "profile-1"
        )
        every { hostProfileStore.currentProfile() } returns profileWithTunnel
        every { settingsManager.getTunnelPassword("profile-1") } returns "bad-password"
        coEvery {
            repository.activateTunnel("http://server.test", "bad-password")
        } returns Result.failure(IllegalStateException("Tunnel activation failed 403: Forbidden"))

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        advanceUntilIdle()

        hostVM.activateTunnelForCurrentHost()
        advanceUntilIdle()

        val activationState = connectionVM.connectionFlow.value.tunnelActivationState
        assertTrue(activationState is cn.vectory.ocdroid.ui.TunnelActivationState.Error)
        assertTrue((activationState as cn.vectory.ocdroid.ui.TunnelActivationState.Error).message.contains("403"))
    }

    @Test
    fun `selectHostProfile clears the per-session message cache on cross-group switch`() = runTest {
        // R-20 Phase 1: selectHostProfile uses a 4-step previous/target fp
        // compare. Cache eviction (EvictGroup → clearMemoryForGroup) only
        // fires on a CROSS-GROUP switch (previousFp != targetFp); a same-
        // group switch keeps the cache (server-identical data). The two
        // profiles here have explicit, distinct serverGroupFp values so the
        // 异组 branch fires. (defaultDirect() generates random UUIDs which
        // would also be distinct, but we mock currentProfile() to return the
        // target BEFORE select — see below — which would collapse previousFp
        // onto targetFp. Explicit fps + an answers-based currentProfile()
        // holder keeps the test deterministic.)
        val defaultProfile = HostProfile(
            id = "default",
            name = "Default",
            serverUrl = "http://server.test",
            serverGroupFp = "g-default"
        )
        val otherProfile = HostProfile(
            id = "other",
            name = "Other",
            serverUrl = "http://other.test",
            serverGroupFp = "g-other"
        )
        // answers-based holder: currentProfile() returns defaultProfile UNTIL
        // select("other") is called, then returns otherProfile. This matches
        // the production HostProfileStore semantics (select has a side effect).
        val currentProfileHolder = mutableListOf(defaultProfile)
        every { hostProfileStore.currentProfile() } answers { currentProfileHolder.first() }
        every { hostProfileStore.profiles() } returns listOf(defaultProfile, otherProfile)
        every { hostProfileStore.select("other") } answers {
            currentProfileHolder[0] = otherProfile
            otherProfile
        }
        // selectHostProfile calls testConnection(force=true) which calls
        // checkHealth. Make it FAIL so the post-health loadInitialData path
        // (which would invoke further relaxed-mock returns and mis-cast) is
        // skipped. The cache is cleared BEFORE testConnection runs, so this
        // does not affect what we are asserting.
        coEvery { repository.checkHealth() } returns Result.failure(IllegalStateException("offline"))

        coEvery { repository.getMessagesPaged("session-A", any(), any()) } returns
            Result.success(
                MessagesPage(
                    listOf(MessageWithParts(info = Message(id = "m_a1", role = "user"))),
                    null
                )
            )

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-A") }
        core.writeSessionList {
            it.copy(sessions = listOf(Session(id = "session-A", directory = "/tmp/a")))
        }
        chatVM.loadMessages("session-A")
        advanceUntilIdle()
        assertEquals(1, core.sessionWindowCacheSize())

        hostVM.selectHostProfile("other")
        advanceUntilIdle()

        assertEquals(
            "Cross-group host switch must clear the per-session message cache",
            0,
            core.sessionWindowCacheSize()
        )
    }

    @Test
    fun `selectHostProfile preserves the per-session message cache on same-group switch`() = runTest {
        // R-20 Phase 1 (same-group counterpart of the test above): when two
        // profiles share a serverGroupFp (sibling entry points to the same
        // server), selectHostProfile keeps the cached message windows — the
        // server data is identical, so dropping the cache would just cause a
        // flicker + re-fetch. purgePerHostState runs with
        // preserveServerGroupData=true; no EvictGroup effect fires.
        val profileA = HostProfile(
            id = "pa",
            name = "Profile A",
            serverUrl = "http://server.test",
            serverGroupFp = "shared-group"
        )
        val profileB = HostProfile(
            id = "pb",
            name = "Profile B",
            serverUrl = "http://server.test",
            serverGroupFp = "shared-group" // same group
        )
        val currentProfileHolder = mutableListOf(profileA)
        every { hostProfileStore.currentProfile() } answers { currentProfileHolder.first() }
        every { hostProfileStore.profiles() } returns listOf(profileA, profileB)
        every { hostProfileStore.select("pb") } answers {
            currentProfileHolder[0] = profileB
            profileB
        }
        coEvery { repository.checkHealth() } returns Result.failure(IllegalStateException("offline"))

        coEvery { repository.getMessagesPaged("session-A", any(), any()) } returns
            Result.success(
                MessagesPage(
                    listOf(MessageWithParts(info = Message(id = "m_a1", role = "user"))),
                    null
                )
            )

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = HostViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-A") }
        core.writeSessionList {
            it.copy(sessions = listOf(Session(id = "session-A", directory = "/tmp/a")))
        }
        chatVM.loadMessages("session-A")
        advanceUntilIdle()
        assertEquals(1, core.sessionWindowCacheSize())

        hostVM.selectHostProfile("pb")
        advanceUntilIdle()

        assertEquals(
            "Same-group host switch must preserve the per-session message cache",
            1,
            core.sessionWindowCacheSize()
        )
    }

    // --------------------------------------------- caSummary / clientCertSummary
    // §mtls-clipboard coverage: the new pure-read summary helpers in
    // HostViewModel have nested `?.let` branches (null-id / no-stored /
    // parse-fail / success). These tests stub settingsManager's ESP reads
    // (getClientCertCa / getClientCertP12 / getClientCertPassword) and feed
    // real programmatically-minted cert/p12 bytes (pure JDK, no BouncyCastle)
    // to cover each branch. Each test only needs `val core = createCore();
    // val viewModel = HostViewModel(core)` — these are pure read methods.

    @Test
    fun `caSummary returns null when clientCertId is null`() = runTest {
        val core = createCore()
        val viewModel = HostViewModel(core)

        assertNull(viewModel.caSummary(null))
        verify(exactly = 0) { settingsManager.getClientCertCa(any()) }
    }

    @Test
    fun `caSummary returns null when no CA is stored for the id`() = runTest {
        every { settingsManager.getClientCertCa("id") } returns null
        val core = createCore()
        val viewModel = HostViewModel(core)

        assertNull(viewModel.caSummary("id"))
    }

    @Test
    fun `caSummary returns null when stored CA bytes fail to parse`() = runTest {
        every { settingsManager.getClientCertCa("id") } returns byteArrayOf(1, 2, 3)
        val core = createCore()
        val viewModel = HostViewModel(core)

        assertNull(viewModel.caSummary("id"))
    }

    @Test
    fun `caSummary returns subject and size for a valid CA cert DER`() = runTest {
        val der = mintSelfSignedCertDer("ca-fixture")
        every { settingsManager.getClientCertCa("id") } returns der
        val core = createCore()
        val viewModel = HostViewModel(core)

        val summary = viewModel.caSummary("id")
        assertNotNull(summary)
        assertEquals("CN=ca-fixture", summary!!.first)
        assertEquals(der.size, summary.second)
    }

    @Test
    fun `clientCertSummary returns null when clientCertId is null`() = runTest {
        val core = createCore()
        val viewModel = HostViewModel(core)

        assertNull(viewModel.clientCertSummary(null))
        verify(exactly = 0) { settingsManager.getClientCertP12(any()) }
    }

    @Test
    fun `clientCertSummary returns null when no p12 is stored for the id`() = runTest {
        every { settingsManager.getClientCertP12("id") } returns null
        val core = createCore()
        val viewModel = HostViewModel(core)

        assertNull(viewModel.clientCertSummary("id"))
    }

    @Test
    fun `clientCertSummary returns null when password is missing and p12 cannot load`() = runTest {
        // Covers the `getClientCertPassword(id) ?: ""` Elvis branch: password
        // is null → "". The garbage p12 bytes then fail to load with the empty
        // password, so the whole summary collapses to null.
        every { settingsManager.getClientCertP12("id") } returns byteArrayOf(1, 2, 3)
        every { settingsManager.getClientCertPassword("id") } returns null
        val core = createCore()
        val viewModel = HostViewModel(core)

        assertNull(viewModel.clientCertSummary("id"))
    }

    @Test
    fun `clientCertSummary returns null for garbage p12 bytes even with a password`() = runTest {
        every { settingsManager.getClientCertP12("id") } returns byteArrayOf(9, 9, 9)
        every { settingsManager.getClientCertPassword("id") } returns "some-pw"
        val core = createCore()
        val viewModel = HostViewModel(core)

        assertNull(viewModel.clientCertSummary("id"))
    }

    @Test
    fun `clientCertSummary returns subject and size for a valid passwordless p12`() = runTest {
        val p12 = mintPasswordlessP12("client-fixture")
        every { settingsManager.getClientCertP12("id") } returns p12
        every { settingsManager.getClientCertPassword("id") } returns ""
        val core = createCore()
        val viewModel = HostViewModel(core)

        val summary = viewModel.clientCertSummary("id")
        assertNotNull(summary)
        assertEquals("CN=client-fixture", summary!!.first)
        assertEquals(p12.size, summary.second)
    }

    // ------------------------------------------------- cert/p12 minting (pure JDK)
    // Mirrors CertBase64Test's programmatic-fixture approach: a hand-rolled
    // minimal DER encoder mints a self-signed X.509 cert, reused both as a CA
    // fixture and as the leaf inside a passwordless PKCS12. No BouncyCastle,
    // no sun.security.x509 (module-restricted on the JDK 21 host).

    private companion object {
        private fun mintSelfSignedCertDer(cn: String): ByteArray = mintSelfSignedCert(cn).first

        private fun mintSelfSignedCert(cn: String): Pair<ByteArray, KeyPair> {
            val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            val keyPair = kpg.generateKeyPair()
            val spki = keyPair.public.encoded

            val name = Der.seq(
                listOf(
                    Der.set(
                        listOf(Der.seq(listOf(Der.oid(2, 5, 4, 3), Der.utf8String(cn))))
                    )
                )
            )
            val sigAlgId = Der.seq(listOf(Der.oid(1, 2, 840, 113549, 1, 1, 11), Der.nullVal()))
            val now = System.currentTimeMillis()
            val validity = Der.seq(
                listOf(
                    Der.utcTime(utcTimeString(now)),
                    Der.utcTime(utcTimeString(now + 365L * 24 * 3600 * 1000)),
                )
            )
            val tbs = Der.seq(
                listOf(
                    Der.tlv(0xA0, Der.integer(2)), // [0] EXPLICIT version = v3
                    Der.integer(BigInteger.valueOf(now)), // serialNumber
                    sigAlgId, // signature algorithm
                    name, // issuer
                    validity,
                    name, // subject
                    spki, // subjectPublicKeyInfo
                )
            )
            val sig = Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(tbs)
            }
            val der = Der.seq(listOf(tbs, sigAlgId, Der.bitString(sig.sign())))
            // Round-trip through the real parser to validate our DER.
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der))
            return der to keyPair
        }

        private fun mintPasswordlessP12(cn: String): ByteArray {
            val (certDer, keyPair) = mintSelfSignedCert(cn)
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
            val ks = KeyStore.getInstance("PKCS12").apply {
                load(null, CharArray(0))
                setKeyEntry("client", keyPair.private, CharArray(0), arrayOf(cert))
            }
            val baos = ByteArrayOutputStream()
            ks.store(baos, CharArray(0))
            return baos.toByteArray()
        }

        private fun utcTimeString(epochMs: Long): String {
            val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = epochMs
            fun two(v: Int) = v.toString().padStart(2, '0')
            val yy = two(cal.get(Calendar.YEAR) % 100)
            val mm = two(cal.get(Calendar.MONTH) + 1)
            val dd = two(cal.get(Calendar.DAY_OF_MONTH))
            val hh = two(cal.get(Calendar.HOUR_OF_DAY))
            val mi = two(cal.get(Calendar.MINUTE))
            val ss = two(cal.get(Calendar.SECOND))
            return "$yy$mm$dd$hh$mi${ss}Z"
        }

        // Minimal DER (ITU-T X.690) TLV encoder — pure java.io.ByteArrayOutputStream.
        private object Der {
            fun tlv(tag: Int, content: ByteArray): ByteArray {
                val out = ByteArrayOutputStream()
                out.write(tag)
                writeLength(out, content.size)
                out.write(content)
                return out.toByteArray()
            }

            private fun writeLength(out: ByteArrayOutputStream, len: Int) {
                when {
                    len < 0x80 -> out.write(len)
                    len <= 0xFF -> { out.write(0x81); out.write(len) }
                    len <= 0xFFFF -> {
                        out.write(0x82)
                        out.write((len ushr 8) and 0xFF)
                        out.write(len and 0xFF)
                    }
                    else -> {
                        out.write(0x83)
                        out.write((len ushr 16) and 0xFF)
                        out.write((len ushr 8) and 0xFF)
                        out.write(len and 0xFF)
                    }
                }
            }

            private fun concat(parts: List<ByteArray>): ByteArray {
                val out = ByteArrayOutputStream()
                for (p in parts) out.write(p)
                return out.toByteArray()
            }

            fun seq(parts: List<ByteArray>) = tlv(0x30, concat(parts))
            fun set(parts: List<ByteArray>) = tlv(0x31, concat(parts))
            fun integer(value: Int) = integer(BigInteger.valueOf(value.toLong()))
            fun integer(value: BigInteger) = tlv(0x02, value.toByteArray())
            fun nullVal() = byteArrayOf(0x05, 0x00)
            fun utf8String(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
            fun utcTime(s: String) = tlv(0x17, s.toByteArray(Charsets.US_ASCII))
            fun bitString(content: ByteArray): ByteArray {
                val c = ByteArray(content.size + 1).also {
                    System.arraycopy(content, 0, it, 1, content.size)
                }
                return tlv(0x03, c) // leading 0x00 = zero unused bits
            }

            fun oid(vararg arcs: Int): ByteArray = tlv(0x06, oidContent(arcs))

            private fun oidContent(arcs: IntArray): ByteArray {
                require(arcs.size >= 2)
                val out = ByteArrayOutputStream()
                out.write(40 * arcs[0] + arcs[1])
                for (i in 2 until arcs.size) out.write(encodeBase128(arcs[i]))
                return out.toByteArray()
            }

            private fun encodeBase128(v: Int): ByteArray {
                if (v == 0) return byteArrayOf(0)
                val tmp = ArrayList<Int>()
                var x = v
                while (x > 0) {
                    tmp.add(x and 0x7F)
                    x = x ushr 7
                }
                tmp.reverse()
                return ByteArray(tmp.size) { i ->
                    val b = tmp[i]
                    (if (i == tmp.lastIndex) b else (b or 0x80)).toByte()
                }
            }
        }
    }

}
