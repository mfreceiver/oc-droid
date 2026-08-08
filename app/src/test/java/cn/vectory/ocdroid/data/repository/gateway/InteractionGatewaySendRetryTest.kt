package cn.vectory.ocdroid.data.repository.gateway

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.api.PromptRequest
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException

/**
 * §transient-send-retry: pins [InteractionGateway.sendMessage] retry-once
 * behaviour for transient connect-phase failures.
 *
 * Only [UnknownHostException] (DNS) and [ConnectException] (TCP refused) are
 * retried — these are deterministic signals that the request bytes never left
 * the client. All other failures ([SocketException] that is not
 * [ConnectException], HTTP errors, etc.) are NOT retried to avoid duplicate
 * turns from an already-received request.
 */
class InteractionGatewaySendRetryTest {

    private fun gateway(api: OpenCodeApi): InteractionGateway {
        val bundle = mockk<ClientBundle>(relaxed = true)
        every { bundle.mutationApi } returns api
        return InteractionGateway(
            bundleProvider = { bundle },
            serverCompatProfile = mockk(relaxed = true),
        )
    }

    @Test
    fun `first attempt ConnectException then success retries and succeeds`() = runTest {
        val api = mockk<OpenCodeApi>(relaxed = true)
        var callCount = 0
        coEvery { api.promptAsync(any(), any()) } answers {
            callCount++
            if (callCount == 1) throw ConnectException("refused")
            Response.success(Unit)
        }

        val result = gateway(api).sendMessage(sessionId = "s1", text = "hello")

        assertTrue("should succeed after retry", result.isSuccess)
        coVerify(exactly = 2) { api.promptAsync(any(), any()) }
    }

    @Test
    fun `first attempt UnknownHostException then success retries and succeeds`() = runTest {
        val api = mockk<OpenCodeApi>(relaxed = true)
        var callCount = 0
        coEvery { api.promptAsync(any(), any()) } answers {
            callCount++
            if (callCount == 1) throw UnknownHostException("dns")
            Response.success(Unit)
        }

        val result = gateway(api).sendMessage(sessionId = "s1", text = "hello")

        assertTrue("should succeed after retry", result.isSuccess)
        coVerify(exactly = 2) { api.promptAsync(any(), any()) }
    }

    @Test
    fun `SocketException not retried`() = runTest {
        val api = mockk<OpenCodeApi>(relaxed = true)
        coEvery { api.promptAsync(any(), any()) } throws SocketException("reset")

        val result = gateway(api).sendMessage(sessionId = "s1", text = "hello")

        assertTrue("should fail without retry", result.isFailure)
        coVerify(exactly = 1) { api.promptAsync(any(), any()) }
    }

    @Test
    fun `HTTP 500 not retried`() = runTest {
        val api = mockk<OpenCodeApi>(relaxed = true)
        coEvery { api.promptAsync(any(), any()) } returns Response.error(
            500,
            "Internal Server Error".toResponseBody("text/plain".toMediaTypeOrNull()),
        )

        val result = gateway(api).sendMessage(sessionId = "s1", text = "hello")

        assertTrue("should fail without retry on HTTP 5xx", result.isFailure)
        coVerify(exactly = 1) { api.promptAsync(any(), any()) }
    }

    @Test
    fun `first attempt success no retry`() = runTest {
        val api = mockk<OpenCodeApi>(relaxed = true)
        coEvery { api.promptAsync(any(), any()) } returns Response.success(Unit)

        val result = gateway(api).sendMessage(sessionId = "s1", text = "hello")

        assertTrue("should succeed on first attempt", result.isSuccess)
        coVerify(exactly = 1) { api.promptAsync(any(), any()) }
    }
}
