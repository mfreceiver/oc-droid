package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.BuildConfig
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
 * §B (slimapi-v2-adapt-traffic-plan §B): [ClientIdentityInterceptor] 单测。
 *
 * 契约（mirror [SlimapiVersionInterceptorTest]）：
 *  - 双门闩：HostConfig.slim == true **AND** path 以 `/slimapi/` 前缀 → 注入
 *    `X-Client-Name` / `X-Client-Version` / `X-Client-Id` 三头。
 *  - legacy 模式（slim=false）：原样透传，不注入任何 identity 头。
 *  - 非 slimapi 路径（即便 slim=true）：不注入——绝不向 legacy/catch-all 请求
 *    泄露客户端身份（§B3 coverage-gap）。
 *  - 设备 id 缺失（provider 返回 null）：X-Client-Id 省略，另两头发送。
 *
 * 用 MockWebServer 走真实 OkHttp Chain（与 production client 同路径），与
 * [SlimapiVersionInterceptorTest] 同模式。
 */
class ClientIdentityInterceptorTest {

    private val server = MockWebServer()
    private lateinit var hostConfig: HostConfig

    /** Fixed device id for deterministic assertions. */
    private val deviceId = "test-device-id-abc123"

    private val client: OkHttpClient
        get() = OkHttpClient.Builder()
            .addInterceptor(
                ClientIdentityInterceptor(hostConfig.snapshot()) { deviceId }
            )
            .build()

    @Before
    fun setup() {
        server.start()
        hostConfig = HostConfig().apply {
            configure(
                baseUrl = server.url("/").toString().trimEnd('/'),
                username = null,
                password = null,
                hostPort = null,
                slim = false
            )
        }
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun configureSlimHost() {
        hostConfig.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = null, password = null, hostPort = null, slim = true
        )
    }

    // ── slim=true + /slimapi/ 路径：注入 3 头（值正确） ───────────────────

    @Test
    fun `slim mode injects all 3 identity headers on slimapi health`() {
        configureSlimHost()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/slimapi/health")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals(SlimapiContract.CLIENT_NAME, request.getHeader(SlimapiContract.X_CLIENT_NAME))
        assertEquals(BuildConfig.VERSION_NAME, request.getHeader(SlimapiContract.X_CLIENT_VERSION))
        assertEquals(deviceId, request.getHeader(SlimapiContract.X_CLIENT_ID))
    }

    @Test
    fun `slim mode injects all 3 identity headers on slimapi events`() {
        configureSlimHost()
        server.enqueue(MockResponse().setBody("ok"))

        // SSE endpoint — must also receive the headers (SSE coverage).
        client.newCall(Request.Builder().url(server.url("/slimapi/events")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals(SlimapiContract.CLIENT_NAME, request.getHeader(SlimapiContract.X_CLIENT_NAME))
        assertEquals(BuildConfig.VERSION_NAME, request.getHeader(SlimapiContract.X_CLIENT_VERSION))
        assertEquals(deviceId, request.getHeader(SlimapiContract.X_CLIENT_ID))
    }

    @Test
    fun `slim mode injects all 3 identity headers on nested slimapi path`() {
        configureSlimHost()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/slimapi/sessions/sid/messages")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals(SlimapiContract.CLIENT_NAME, request.getHeader(SlimapiContract.X_CLIENT_NAME))
        assertEquals(BuildConfig.VERSION_NAME, request.getHeader(SlimapiContract.X_CLIENT_VERSION))
        assertEquals(deviceId, request.getHeader(SlimapiContract.X_CLIENT_ID))
    }

    @Test
    fun `exactly one value per identity header (no duplication)`() {
        configureSlimHost()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/slimapi/health"))
                .header(SlimapiContract.X_CLIENT_ID, "caller-preset")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        // .header() (replace) — at most one value per name; caller preset overwritten.
        assertEquals(1, request.headers.values(SlimapiContract.X_CLIENT_NAME).size)
        assertEquals(1, request.headers.values(SlimapiContract.X_CLIENT_VERSION).size)
        assertEquals(1, request.headers.values(SlimapiContract.X_CLIENT_ID).size)
        assertEquals(deviceId, request.getHeader(SlimapiContract.X_CLIENT_ID))
    }

    // ── 非 /slimapi/ 路径：完全不注入 identity 头（coverage-gap 防泄漏） ────

    @Test
    fun `slim mode omits identity headers on legacy opencode path`() {
        configureSlimHost()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/global/health")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(
            "slim=true but path is /global/health: must NOT leak identity",
            request.getHeader(SlimapiContract.X_CLIENT_NAME)
        )
        assertNull(request.getHeader(SlimapiContract.X_CLIENT_VERSION))
        assertNull(request.getHeader(SlimapiContract.X_CLIENT_ID))
    }

    @Test
    fun `slim mode omits identity headers on session path`() {
        configureSlimHost()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/session/s1/message")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(request.getHeader(SlimapiContract.X_CLIENT_NAME))
        assertNull(request.getHeader(SlimapiContract.X_CLIENT_VERSION))
        assertNull(request.getHeader(SlimapiContract.X_CLIENT_ID))
    }

    // ── slim=false（legacy）：不注入 identity 头 ──────────────────────────

    @Test
    fun `legacy mode omits identity headers on slimapi path`() {
        // hostConfig defaults to slim=false (setup()).
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/slimapi/health")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(
            "slim=false: even /slimapi/ paths must NOT get identity headers",
            request.getHeader(SlimapiContract.X_CLIENT_NAME)
        )
        assertNull(request.getHeader(SlimapiContract.X_CLIENT_VERSION))
        assertNull(request.getHeader(SlimapiContract.X_CLIENT_ID))
    }

    // ── 设备 id 缺失：X-Client-Id 省略，另两头仍发送 ─────────────────────

    @Test
    fun `absent device id omits X-Client-Id only`() {
        configureSlimHost()
        val noIdClient = OkHttpClient.Builder()
            .addInterceptor(ClientIdentityInterceptor(hostConfig.snapshot()) { null })
            .build()
        server.enqueue(MockResponse().setBody("ok"))

        noIdClient.newCall(Request.Builder().url(server.url("/slimapi/health")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        // Name + version still present.
        assertEquals(SlimapiContract.CLIENT_NAME, request.getHeader(SlimapiContract.X_CLIENT_NAME))
        assertEquals(BuildConfig.VERSION_NAME, request.getHeader(SlimapiContract.X_CLIENT_VERSION))
        // Only X-Client-Id omitted.
        assertNull(
            "device id absent → X-Client-Id omitted; the other two still sent",
            request.getHeader(SlimapiContract.X_CLIENT_ID)
        )
    }

    // ── 路径前缀边界：`/slimapi`（无尾斜杠）不匹配 ────────────────────────

    @Test
    fun `path without trailing slash prefix is not matched`() {
        configureSlimHost()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/slimapi")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(
            "/slimapi (no trailing slash) must NOT match prefix /slimapi/",
            request.getHeader(SlimapiContract.X_CLIENT_NAME)
        )
        assertNull(request.getHeader(SlimapiContract.X_CLIENT_VERSION))
        assertNull(request.getHeader(SlimapiContract.X_CLIENT_ID))
    }
}
