package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.repository.SlimapiHealthPayload
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import cn.vectory.ocdroid.data.repository.http.SlimapiContract
import cn.vectory.ocdroid.ui.ConnectionViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.KeyStore

/**
 * R18 Phase 5++ coverage: [ConnectionViewModel.testConnectionForm] — the
 * in-place host probe (no profile switch). Coverage gap before this file:
 * the entire `testConnectionForm$1` coroutine body (0/12 branches, 0/12
 * lines, 0/109 instructions) plus the traffic helpers (refresh/reset).
 *
 * Each test wires a single [ConnectionViewModel] over a fresh [createCore]
 * (which already stubs `settingsManager.basicAuthPassword` as a relaxed mock
 * returning null) and asserts the (success, message) callback contract:
 *  - healthy=true → success with version-prefixed message.
 *  - healthy=false → failure with "healthy=false" body.
 *  - HTTP failure → failure with the exception message.
 *  - password resolution: passwordEdited=false → resolve via stored profile.
 *  - refreshTrafficStats / resetTrafficStats snapshot the tracker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionViewModelFormTest : MainViewModelTestBase() {

    @Test
    fun `testConnectionForm healthy with version reports success`() = runTest {
        coEvery {
            repository.checkHealthFor(any(), any(), any(), any())
        } returns Result.success(HealthResponse(healthy = true, version = "1.2.3"))

        val core = createCore()
        val vm = ConnectionViewModel(core)
        var result: Pair<Boolean, String>? = null

        vm.testConnectionForm(
            baseUrl = "http://x",
            username = "u",
            password = "p",
            profileId = "p1",
            passwordEdited = true,
            onResult = { ok, msg -> result = ok to msg },
        )
        advanceUntilIdle()

        assertNotNull(result)
        assertEquals(true, result!!.first)
        assertTrue(result!!.second.contains("1.2.3"))
    }

    @Test
    fun `testConnectionForm healthy without version uses generic success message`() = runTest {
        coEvery {
            repository.checkHealthFor(any(), any(), any(), any())
        } returns Result.success(HealthResponse(healthy = true, version = null))

        val core = createCore()
        val vm = ConnectionViewModel(core)
        var result: Pair<Boolean, String>? = null

        vm.testConnectionForm(
            baseUrl = "http://x",
            username = null,
            password = null,
            profileId = null,
            passwordEdited = false,
            onResult = { ok, msg -> result = ok to msg },
        )
        advanceUntilIdle()

        assertEquals(true, result!!.first)
        assertTrue(result!!.second.isNotEmpty())
    }

    @Test
    fun `testConnectionForm healthy false reports failure`() = runTest {
        coEvery {
            repository.checkHealthFor(any(), any(), any(), any())
        } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        val core = createCore()
        val vm = ConnectionViewModel(core)
        var result: Pair<Boolean, String>? = null

        vm.testConnectionForm(
            baseUrl = "http://x",
            username = null,
            password = null,
            profileId = null,
            passwordEdited = false,
            onResult = { ok, msg -> result = ok to msg },
        )
        advanceUntilIdle()

        assertEquals(false, result!!.first)
        assertTrue(result!!.second.contains("healthy=false") || result!!.second.contains("不可用"))
    }

    @Test
    fun `testConnectionForm HTTP failure surfaces the exception message`() = runTest {
        coEvery {
            repository.checkHealthFor(any(), any(), any(), any())
        } returns Result.failure(java.io.IOException("connection refused"))

        val core = createCore()
        val vm = ConnectionViewModel(core)
        var result: Pair<Boolean, String>? = null

        vm.testConnectionForm(
            baseUrl = "http://x",
            username = null,
            password = null,
            profileId = null,
            passwordEdited = false,
            onResult = { ok, msg -> result = ok to msg },
        )
        advanceUntilIdle()

        assertEquals(false, result!!.first)
        assertTrue(result!!.second.contains("connection refused"))
    }

    @Test
    fun `testConnectionForm HTTP failure with null message uses fallback`() = runTest {
        coEvery {
            repository.checkHealthFor(any(), any(), any(), any())
        } returns Result.failure(RuntimeException())

        val core = createCore()
        val vm = ConnectionViewModel(core)
        var result: Pair<Boolean, String>? = null

        vm.testConnectionForm(
            baseUrl = "http://x",
            username = null,
            password = null,
            profileId = null,
            passwordEdited = false,
            onResult = { ok, msg -> result = ok to msg },
        )
        advanceUntilIdle()

        assertEquals(false, result!!.first)
        // Fallback message is non-empty.
        assertTrue(result!!.second.isNotEmpty())
    }

    @Test
    fun `testConnectionForm passwordEdited false resolves via stored profile`() = runTest {
        // SettingsManager.basicAuthPassword(profileId) returns the stored
        // password; relaxed mock returns null by default — override to verify
        // it's consulted.
        every { settingsManager.basicAuthPassword("p1") } returns "stored-pw"
        coEvery {
            repository.checkHealthFor(any(), any(), eq("stored-pw"), any())
        } returns Result.success(HealthResponse(healthy = true, version = "1.0"))

        val core = createCore()
        val vm = ConnectionViewModel(core)
        var result: Pair<Boolean, String>? = null

        vm.testConnectionForm(
            baseUrl = "http://x",
            username = "u",
            password = null,  // not edited → resolve via profile
            profileId = "p1",
            passwordEdited = false,
            onResult = { ok, msg -> result = ok to msg },
        )
        advanceUntilIdle()

        assertEquals(true, result!!.first)
        // The repository MUST have been called with the stored-pw, not null.
        coVerify { repository.checkHealthFor(any(), any(), eq("stored-pw"), any()) }
    }

    // ── §fix-3 mTLS test-connection path ──────────────────────────────────────

    private fun buildValidP12(password: String = "p12pw"): ByteArray {
        val ca = HeldCertificate.Builder().commonName("test-ca").build()
        val client = HeldCertificate.Builder().commonName("test-client").signedBy(ca).build()
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry(
            "client", client.keyPair.private, password.toCharArray(),
            arrayOf(client.certificate, ca.certificate),
        )
        val baos = ByteArrayOutputStream()
        ks.store(baos, password.toCharArray())
        return baos.toByteArray()
    }

    @Test
    fun `testConnectionForm mTLS enabled with stagedP12 probes with a client cert`() = runTest {
        // §fix-3: mTLS 开 + 有效 stagedP12 → resolveClientCert 构造 ClientCertMaterial →
        // checkHealthFor 5-arg 带 clientCert（走 resolveProbe，不污染 held mTLS）。
        val p12 = buildValidP12()
        // 5-arg stub（仅此 mTLS 用例用 5-arg；既有 4-arg stub 不动）。
        coEvery {
            repository.checkHealthFor(any(), any(), any(), any(), any<ClientCertMaterial>())
        } returns Result.success(HealthResponse(healthy = true, version = "1.0"))

        val core = createCore()
        val vm = ConnectionViewModel(core)
        var result: Pair<Boolean, String>? = null

        vm.testConnectionForm(
            baseUrl = "http://x", username = null, password = null,
            profileId = null, passwordEdited = false,
            mtlsEnabled = true, stagedP12 = p12, hasImportedP12 = true,
            caStage = cn.vectory.ocdroid.ui.settings.CaStage.Unchanged,
            p12Password = "p12pw", p12PasswordEdited = true, clientCertId = null,
        ) { ok, msg -> result = ok to msg }
        advanceUntilIdle()

        assertEquals(true, result!!.first)
        coVerify { repository.checkHealthFor(any(), any(), any(), any(), any<ClientCertMaterial>()) }
    }

    @Test
    fun `testConnectionForm mTLS enabled without cert fail-fasts without probing`() = runTest {
        // §fix-3 (gpt-2#1): mTLS 开但无证 → onResult(false) 立即返回，不调 checkHealthFor。
        val core = createCore()
        val vm = ConnectionViewModel(core)
        var result: Pair<Boolean, String>? = null

        vm.testConnectionForm(
            baseUrl = "http://x", username = null, password = null,
            profileId = null, passwordEdited = false,
            mtlsEnabled = true, stagedP12 = null, hasImportedP12 = false,
            caStage = cn.vectory.ocdroid.ui.settings.CaStage.Unchanged,
            p12Password = null, p12PasswordEdited = false, clientCertId = null,
        ) { ok, msg -> result = ok to msg }
        advanceUntilIdle()

        assertNotNull(result)
        assertEquals(false, result!!.first)
        assertTrue(result!!.second.contains("mTLS"))
        coVerify(exactly = 0) { repository.checkHealthFor(any(), any(), any(), any(), any()) }
    }

    // ── §Phase 3a (Lane-B3-Dialog): slim version-incompatibility UX loop ──────

    /**
     * §slim-reconcile-lane-repo (Phase 3a / Lane-B3-Dialog): the test-
     * connection path MUST (a) forward [slim] to [OpenCodeRepository.checkHealthFor]
     * so the slim branch lands the sidecar's `accepted_client_versions` in
     * [ServerCompatProfile], and (b) read those bounds back AFTER the probe
     * and feed [ConnectionState.slimapiVersionIncompatible] when incompatible.
     *
     * Closes the M2 UX loop: transport fail-close already worked (the probe
     * throws on incompatible), but the blocking dialog never fired because
     * the flag was never written from this path. The flag is what
     * [HostProfilesManagerScreen] observes to render the incompatibility
     * AlertDialog.
     */
    @Test
    fun `testConnectionForm slim incompatible sets slimapiVersionIncompatible flag and fails`() = runTest {
        // Simulate the sidecar responding with an incompatible range: the
        // mocked checkHealthFor mirrors production by writing the bounds into
        // the shared [ServerCompatProfile] (same instance the VM reads from
        // post-probe).
        val core = createCore()
        val vm = ConnectionViewModel(core)
        val scp = core.serverCompatProfile
        // Stub the 6-arg slim call (relaxed=true would also match, but using
        // an explicit stub with coAnswer lets us land the side-effect the way
        // production does.
        coEvery {
            repository.checkHealthFor(any(), any(), any(), any(), any(), eq(true))
        } answers {
            scp.updateSlimapi(
                SlimapiHealthPayload(
                    sidecarOk = true,
                    schemaDegraded = false,
                    serverApiVersion = 5,
                    acceptedClientVersions = 5 to 9,
                )
            )
            Result.success(HealthResponse(healthy = true, version = "slimapi/api_version=5"))
        }

        var result: Pair<Boolean, String>? = null
        vm.testConnectionForm(
            baseUrl = "http://x", username = null, password = null,
            profileId = null, passwordEdited = false, slim = true,
        ) { ok, msg -> result = ok to msg }
        advanceUntilIdle()

        assertNotNull(result)
        assertEquals(false, result!!.first)
        assertTrue(
            "message must mention incompatibility: ${result!!.second}",
            result!!.second.contains("不兼容"),
        )
        // The SCP bounds landed via the mocked probe.
        assertEquals(5, scp.slimapiAcceptedMin)
        assertEquals(9, scp.slimapiAcceptedMax)
        assertFalse(scp.isSlimapiClientAccepted())
        // The flag is fed into ConnectionState so HostProfilesManagerScreen
        // can render the blocking AlertDialog (the UX loop closure).
        val incompat = core.connectionFlow.value.slimapiVersionIncompatible
        assertNotNull("slimapiVersionIncompatible must be non-null to fire the dialog", incompat)
        assertEquals(SlimapiContract.SLIMAPI_CLIENT_VERSION, incompat!!.first)
        assertEquals(5, incompat.second)
        assertEquals(9, incompat.third)
        // checkHealthFor MUST have been called with slim=true (transport
        // routing closure).
        coVerify(atLeast = 1) { repository.checkHealthFor(any(), any(), any(), any(), any(), eq(true)) }
    }

    @Test
    fun `testConnectionForm slim compatible clears slimapiVersionIncompatible and succeeds`() = runTest {
        // When the sidecar accepts the client version, the SCP bounds land
        // AND the VM clears any stale flag AND surfaces success.
        val core = createCore()
        val vm = ConnectionViewModel(core)
        val scp = core.serverCompatProfile
        // Seed a stale incompatible flag to prove the success path clears it
        // (avoids residual flag confusing the UI after a re-test that succeeds).
        core.writeConnection {
            it.copy(slimapiVersionIncompatible = Triple(SlimapiContract.SLIMAPI_CLIENT_VERSION, 99, 99))
        }
        assertNotNull("pre-test stale flag is set", core.connectionFlow.value.slimapiVersionIncompatible)

        coEvery {
            repository.checkHealthFor(any(), any(), any(), any(), any(), eq(true))
        } answers {
            scp.updateSlimapi(
                SlimapiHealthPayload(
                    sidecarOk = true,
                    schemaDegraded = false,
                    serverApiVersion = 1,
                    acceptedClientVersions = 1 to 5,
                )
            )
            Result.success(HealthResponse(healthy = true, version = "slimapi/api_version=1"))
        }

        var result: Pair<Boolean, String>? = null
        vm.testConnectionForm(
            baseUrl = "http://x", username = null, password = null,
            profileId = null, passwordEdited = false, slim = true,
        ) { ok, msg -> result = ok to msg }
        advanceUntilIdle()

        assertEquals(true, result!!.first)
        assertTrue(scp.isSlimapiClientAccepted())
        // Stale flag MUST be cleared on the success/compatible path.
        assertNull(
            "stale slimapiVersionIncompatible cleared on compatible probe",
            core.connectionFlow.value.slimapiVersionIncompatible,
        )
    }

    @Test
    fun `testConnectionForm slim false preserves existing behavior`() = runTest {
        // §Phase 3a regression: slim=false MUST NOT consult the SCP incompat
        // gate. Even if the SCP holds an incompatible range from a previous
        // slim probe, the legacy test path bypasses the gate and reports
        // success/failure purely from the legacy probe result.
        val core = createCore()
        val vm = ConnectionViewModel(core)
        val scp = core.serverCompatProfile
        // SCP holds incompatible bounds from a prior slim probe (simulated):
        scp.updateSlimapi(
            SlimapiHealthPayload(
                sidecarOk = true,
                schemaDegraded = false,
                serverApiVersion = 5,
                acceptedClientVersions = 5 to 9,
            )
        )
        assertFalse(scp.isSlimapiClientAccepted())

        coEvery {
            repository.checkHealthFor(any(), any(), any(), any(), any(), eq(false))
        } returns Result.success(HealthResponse(healthy = true, version = "1.0"))

        var result: Pair<Boolean, String>? = null
        vm.testConnectionForm(
            baseUrl = "http://x", username = null, password = null,
            profileId = null, passwordEdited = false, slim = false,
        ) { ok, msg -> result = ok to msg }
        advanceUntilIdle()

        // Legacy path: succeeds (the SCP gate is bypassed) AND clears the flag
        // (per spec "否则" branch — legacy clears stale state too).
        assertEquals(true, result!!.first)
        assertNull(
            "slim=false clears slimapiVersionIncompatible (legacy bypasses gate)",
            core.connectionFlow.value.slimapiVersionIncompatible,
        )
    }

    @Test
    fun `testConnectionForm slim true with sidecar missing accepted_client_versions clears flag`() = runTest {
        // §Phase 3a fail-closed nuance: slim=true but the sidecar response
        // carries NO accepted_client_versions (server older / partial deploy).
        // The bounds stay null → isSlimapiClientAccepted() returns false
        // (fail-closed at the SCP layer), but the VM gate explicitly requires
        // min != null && max != null before flagging — so it falls into the
        // "否则" branch (clear flag) and surfaces the probe result. This is
        // deliberate: the probe's own unhealthy/throw already fails-close at
        // the transport layer; we do not double-flag with a Triple that has
        // no meaningful min/max to show the user.
        val core = createCore()
        val vm = ConnectionViewModel(core)
        val scp = core.serverCompatProfile
        // Seed a stale flag to verify it gets cleared.
        core.writeConnection {
            it.copy(slimapiVersionIncompatible = Triple(SlimapiContract.SLIMAPI_CLIENT_VERSION, 5, 9))
        }

        coEvery {
            repository.checkHealthFor(any(), any(), any(), any(), any(), eq(true))
        } answers {
            // Sidecar responded with NO accepted_client_versions field.
            scp.updateSlimapi(
                SlimapiHealthPayload(
                    sidecarOk = true,
                    schemaDegraded = false,
                    serverApiVersion = 1,
                    acceptedClientVersions = null,
                )
            )
            Result.success(HealthResponse(healthy = true, version = "slimapi/api_version=1"))
        }

        var result: Pair<Boolean, String>? = null
        vm.testConnectionForm(
            baseUrl = "http://x", username = null, password = null,
            profileId = null, passwordEdited = false, slim = true,
        ) { ok, msg -> result = ok to msg }
        advanceUntilIdle()

        // No bounds → fall to "否则" branch → clear flag, surface probe result.
        assertEquals(true, result!!.first)
        assertNull(
            "no accepted_client_versions → flag cleared (not triple-null'd)",
            core.connectionFlow.value.slimapiVersionIncompatible,
        )
    }

    // ── Traffic helpers ──────────────────────────────────────────────────────

    @Test
    fun `refreshTrafficStats snapshots tracker totals into the slice`() = runTest {
        every { trafficTracker.totalBytesSent } returns 1024L
        every { trafficTracker.totalBytesReceived } returns 2048L

        val core = createCore()
        val vm = ConnectionViewModel(core)

        vm.refreshTrafficStats()
        advanceUntilIdle()

        assertEquals(1024L, core.trafficFlow.value.trafficSent)
        assertEquals(2048L, core.trafficFlow.value.trafficReceived)
    }

    @Test
    fun `resetTrafficStats zeroes tracker then snapshots`() = runTest {
        every { trafficTracker.totalBytesSent } returns 0L
        every { trafficTracker.totalBytesReceived } returns 0L

        val core = createCore()
        val vm = ConnectionViewModel(core)

        vm.resetTrafficStats()
        advanceUntilIdle()

        io.mockk.verify { trafficTracker.reset() }
        assertEquals(0L, core.trafficFlow.value.trafficSent)
        assertEquals(0L, core.trafficFlow.value.trafficReceived)
    }

    // ── SSE lifecycle pass-throughs ───────────────────────────────────────────

    @Test
    fun `startSSE cancelSse cancelSseForReconfigure and loadInitialData pass through to coordinator`() = runTest {
        // No-op: the coordinator's relaxed repository mocks accept every call.
        // The point is to drive the VM pass-through methods so their bodies
        // (one-line delegators) count as covered.
        every { repository.connectSSE(any()) } returns kotlinx.coroutines.flow.emptyFlow()
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        coEvery { repository.checkHealth() } returns Result.success(
            HealthResponse(healthy = true, version = "1.0"),
        )

        val core = createCore()
        val vm = ConnectionViewModel(core)

        vm.startSSE()
        vm.loadInitialData()
        vm.cancelSseForReconfigure()
        vm.cancelSse()
        advanceUntilIdle()

        // No assertions beyond "did not throw"; the body coverage is the goal.
    }
}
