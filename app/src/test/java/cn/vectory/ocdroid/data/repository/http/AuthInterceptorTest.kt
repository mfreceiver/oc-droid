package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.data.repository.HostConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * R-18 unit tests for [AuthInterceptor]. Verifies the Basic Auth injection
 * contract:
 *  - `Authorization: Basic <base64(user:pass)>` added when BOTH creds present,
 *  - omitted when either is null,
 *  - encoding matches `Base64.getEncoder().encodeToString("$u:$p".toByteArray())`.
 */
class AuthInterceptorTest {

    private val server = MockWebServer()
    private lateinit var hostConfig: HostConfig
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        server.start()
        hostConfig = HostConfig().apply {
            configure(
                baseUrl = server.url("/").toString().trimEnd('/'),
                username = null,
                password = null,
                allowInsecure = false
            )
        }
        client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(hostConfig))
            .build()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `injects Basic Auth header when both credentials are set`() {
        hostConfig.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "alice",
            password = "secret",
            allowInsecure = false
        )
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/session")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        // "alice:secret" → Base64 → "YWxpY2U6c2VjcmV0"
        assertEquals("Basic YWxpY2U6c2VjcmV0", request.getHeader("Authorization"))
    }

    @Test
    fun `omits Authorization when no credentials are configured`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/session")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `omits Authorization when only username is set`() {
        hostConfig.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "alice",
            password = null,
            allowInsecure = false
        )
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/session")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(
            "Authorization must require BOTH username and password",
            request.getHeader("Authorization")
        )
    }

    @Test
    fun `omits Authorization when only password is set`() {
        hostConfig.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = null,
            password = "secret",
            allowInsecure = false
        )
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/session")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `encodes credential pair as UTF-8 before Base64`() {
        hostConfig.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "u",
            password = "p",
            allowInsecure = false
        )
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/x")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        // "u:p" → [0x75, 0x3a, 0x70] → "dTpw"
        assertEquals("Basic dTpw", request.getHeader("Authorization"))
    }
}
