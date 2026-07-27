package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.api.SSEClient
import cn.vectory.ocdroid.data.api.v2.OpenCodeApiV2
import cn.vectory.ocdroid.data.repository.http.SslConfig
import io.mockk.mockk
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the local-wipe primitive and independent guards. */
class SlimSseStateMachineResetTest {
    @Test
    fun `local wipe keeps published endpoint and bundle while rotating slim marker`() {
        val bundle = bundle(generation = 7L, endpoint = "same.example")
        val machine = SlimSseStateMachine(Any(), clientBundleProvider = { bundle })
        val before = machine.captureSlimCommitToken()

        machine.resetSlimForLocalWipe()

        val after = machine.captureSlimCommitToken()
        assertEquals("https://same.example", bundle.endpointFp)
        assertEquals(7L, bundle.generation)
        assertFalse("old token is rejected after local wipe", machine.isSlimCommitTokenCurrent(before))
        assertTrue("new token is current after local wipe", machine.isSlimCommitTokenCurrent(after))
    }

    @Test
    fun `old generation token stays rejected after readiness is restored`() {
        var bundle = bundle(generation = 1L, endpoint = "same.example")
        val machine = SlimSseStateMachine(Any(), clientBundleProvider = { bundle })
        val oldToken = machine.captureSlimCommitToken()
        val ticket = machine.beginSlimReconfigure()
        bundle = bundle(generation = 2L, endpoint = "same.example")
        machine.completeSlimReconfigure(ticket)

        assertFalse(
            "bundle generation guard remains independent after readiness recovery",
            machine.isSlimCommitTokenCurrent(oldToken),
        )
    }

    private fun bundle(generation: Long, endpoint: String): ClientBundle = ClientBundle(
        generation = generation,
        hostSnapshot = HostSnapshot(
            baseUrl = "https://$endpoint",
            hostPort = endpoint,
            username = null,
            password = null,
            slimHost = true,
        ),
        effectiveSslConfig = SslConfig.SystemDefault,
        clientCertError = null,
        restHttp = mockk(),
        restRetrofit = mockk(),
        restApi = mockk<OpenCodeApi>(),
        sseHttp = mockk(),
        sseClient = mockk<SSEClient>(),
        commandHttp = mockk<OkHttpClient>(),
        commandRetrofit = mockk<Retrofit>(),
        commandApi = mockk<OpenCodeApi>(),
        mutationHttp = mockk<OkHttpClient>(),
        mutationRetrofit = mockk<Retrofit>(),
        mutationApi = mockk<OpenCodeApi>(),
        v2Retrofit = mockk<Retrofit>(),
        apiV2 = mockk<OpenCodeApiV2>(),
        ownedClients = emptyList(),
    )
}
