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
 * §C5 (oc-slimapi turn-token contract §6.2 method A): [ServerGroupFpInterceptor] 单测。
 *
 * 契约（mirror [ClientIdentityInterceptorTest]）：
 *  - 双门闩：HostConfig.slim == true **AND** path 以 `/slimapi/` 前缀 → 注入
 *    `X-Ocdroid-Server-Group-Fp` 头。
 *  - legacy 模式（slim=false）：原样透传，不注入 fp 头。
 *  - 非 slimapi 路径（即便 slim=true）：不注入——绝不向 legacy/catch-all 请求
 *    泄露 serverGroupFp（§B3 coverage-gap 同模式）。
 *  - 请求时捕获语义：provider 返回值随请求时变化，host 切换后下一请求立即使用
 *    新 fp（无 TOCTOU）。
 *
 * 用 MockWebServer 走真实 OkHttp Chain（与 production client 同路径），与
 * [ClientIdentityInterceptorTest] 同模式。
 */
class ServerGroupFpInterceptorTest {

    private val server = MockWebServer()
    private lateinit var hostConfig: HostConfig

    /** Fixed fp for deterministic assertions. */
    private val testFp = "profile-group-test-123"

    private val client: OkHttpClient
        get() = OkHttpClient.Builder()
            .addInterceptor(
                ServerGroupFpInterceptor(hostConfig.snapshot()) { testFp }
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
                slim = false,
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
            username = null, password = null, hostPort = null, slim = true,
        )
    }

    // ── slim=true + /slimapi/ 路径：注入 fp 头（值正确） ───────────────────

    @Test
    fun `slim mode injects server group fp header on slimapi health`() {
        configureSlimHost()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/slimapi/health")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals(testFp, request.getHeader(SlimapiContract.X_OCDROID_SERVER_GROUP_FP))
    }

    @Test
    fun `slim mode injects server group fp header on slimapi events`() {
        configureSlimHost()
        server.enqueue(MockResponse().setBody("ok"))

        // SSE endpoint — must also receive the header (SSE coverage).
        client.newCall(Request.Builder().url(server.url("/slimapi/events")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals(testFp, request.getHeader(SlimapiContract.X_OCDROID_SERVER_GROUP_FP))
    }

    @Test
    fun `slim mode injects server group fp header on nested slimapi path`() {
        configureSlimHost()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/slimapi/sessions/sid/messages")).build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals(testFp, request.getHeader(SlimapiContract.X_OCDROID_SERVER_GROUP_FP))
    }

    @Test
    fun `exactly one value per server group fp header (no duplication)`() {
        configureSlimHost()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder().url(server.url("/slimapi/health"))
                .header(SlimapiContract.X_OCDROID_SERVER_GROUP_FP, "caller-preset")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        // .header() (replace) — at most one value; caller preset overwritten.
        assertEquals(
            1,
            request.headers.values(SlimapiContract.X_OCDROID_SERVER_GROUP_FP).size,
        )
        assertEquals(testFp, request.getHeader(SlimapiContract.X_OCDROID_SERVER_GROUP_FP))
    }

    // ── 非 /slimapi/ 路径：完全不注入 fp 头（coverage-gap 防泄漏） ────────

    @Test
    fun `slim mode omits server group fp header on legacy opencode path`() {
        configureSlimHost()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/global/health")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(
            "slim=true but path is /global/health: must NOT leak serverGroupFp",
            request.getHeader(SlimapiContract.X_OCDROID_SERVER_GROUP_FP),
        )
    }

    @Test
    fun `slim mode omits server group fp header on session path`() {
        configureSlimHost()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/session/s1/message")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(
            request.getHeader(SlimapiContract.X_OCDROID_SERVER_GROUP_FP),
        )
    }

    // ── slim=false（legacy）：不注入 fp 头 ──────────────────────────────

    @Test
    fun `legacy mode omits server group fp header on slimapi path`() {
        // hostConfig defaults to slim=false (setup()).
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/slimapi/health")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(
            "slim=false: even /slimapi/ paths must NOT get serverGroupFp header",
            request.getHeader(SlimapiContract.X_OCDROID_SERVER_GROUP_FP),
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
            request.getHeader(SlimapiContract.X_OCDROID_SERVER_GROUP_FP),
        )
    }

    // ── 请求时捕获（request-time capture） ──────────────────────────────

    @Test
    fun `request-time capture reflects host switch`() {
        configureSlimHost()
        var fp = "group-A"
        val mutableClient = OkHttpClient.Builder()
            .addInterceptor(ServerGroupFpInterceptor(hostConfig.snapshot()) { fp })
            .build()

        server.enqueue(MockResponse().setBody("ok"))
        mutableClient.newCall(Request.Builder().url(server.url("/slimapi/health")).build())
            .execute().use { /* drain */ }
        assertEquals("group-A", server.takeRequest().getHeader(SlimapiContract.X_OCDROID_SERVER_GROUP_FP))

        // Mutate the fp — next request must capture the new value.
        fp = "group-B"
        server.enqueue(MockResponse().setBody("ok"))
        mutableClient.newCall(Request.Builder().url(server.url("/slimapi/health")).build())
            .execute().use { /* drain */ }
        assertEquals(
            "host switch → next request carries new fp (request-time capture)",
            "group-B",
            server.takeRequest().getHeader(SlimapiContract.X_OCDROID_SERVER_GROUP_FP),
        )
    }
}
