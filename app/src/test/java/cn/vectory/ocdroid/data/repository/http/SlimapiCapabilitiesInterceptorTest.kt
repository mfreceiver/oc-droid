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
 * R8 slim-mode foundation / B2 Opt-A: SlimapiCapabilitiesInterceptor 单测。
 *
 * 契约（见 SlimapiContract / docs/0.11-ux-first-joint-plan.md §6）：
 *  - 双门闩：HostConfig.slim == true **AND** 路径以 `/slimapi/` 前缀 → 注入
 *    `X-Slimapi-Capabilities: mid-partial-envelope=1`。
 *  - legacy 模式（slim=false）：原样透传，不注入头（opencode 不识别该头）。
 *  - 非 slimapi 路径（即便 slim=true）：不注入——legacy opencode 端点不变。
 *  - 头值用 `.header()` 替换语义（不叠加重复）。
 *  - 值格式：单个 `name=value` token（I-R4-CAP-GRAMMAR）。
 *
 * 用 MockWebServer 让拦截器走真实 OkHttp Chain（与 production client 同路径），
 * 不 mock 内部 OkHttp 类型（与 SlimapiVersionInterceptorTest / DirectoryHeaderInterceptorTest 同模式）。
 */
class SlimapiCapabilitiesInterceptorTest {

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
                hostPort = null,
                slim = false
            )
        }
        client = OkHttpClient.Builder()
            .addInterceptor(SlimapiCapabilitiesInterceptor(hostConfig))
            .build()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    // ── slim=false（legacy）：所有路径都不注入头 ──────────────────────────

    @Test
    fun `legacy mode omits capabilities header on slimapi path`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/slimapi/health")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(
            "slim=false: even /slimapi/ paths must NOT get the capabilities header",
            request.getHeader(SlimapiContract.X_SLIMAPI_CAPABILITIES)
        )
    }

    @Test
    fun `legacy mode omits capabilities header on opencode path`() {
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/global/health")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(request.getHeader(SlimapiContract.X_SLIMAPI_CAPABILITIES))
    }

    // ── slim=true + 非 slimapi 路径：不注入 ──────────────────────────────

    @Test
    fun `slim mode omits capabilities header on legacy opencode path`() {
        hostConfig.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = null,
            password = null,
            hostPort = null,
            slim = true
        )
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/global/health")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertNull(
            "slim=true but path is /global/health: must NOT inject (opencode won't recognize)",
            request.getHeader(SlimapiContract.X_SLIMAPI_CAPABILITIES)
        )
    }

    @Test
    fun `slim mode omits capabilities header on session path`() {
        hostConfig.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = null, password = null, hostPort = null, slim = true
        )
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/session/s1/message")).build())
            .execute().use { /* drain */ }

        assertNull(server.takeRequest().getHeader(SlimapiContract.X_SLIMAPI_CAPABILITIES))
    }

    // ── slim=true + /slimapi/ 路径：注入头 ───────────────────────────────

    @Test
    fun `slim mode injects capabilities header on slimapi health`() {
        hostConfig.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = null, password = null, hostPort = null, slim = true
        )
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/slimapi/health")).build())
            .execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals(
            "slim=true + /slimapi/health: header MUST be injected (B2 Opt-A)",
            SlimapiContract.MID_PARTIAL_ENVELOPE_CAPABILITY,
            request.getHeader(SlimapiContract.X_SLIMAPI_CAPABILITIES)
        )
    }

    @Test
    fun `slim mode injects capabilities header on slimapi events`() {
        hostConfig.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = null, password = null, hostPort = null, slim = true
        )
        server.enqueue(MockResponse().setBody("ok"))

        // SSE endpoint — must also receive the header (B2 Opt-A covers SSE).
        client.newCall(Request.Builder().url(server.url("/slimapi/events")).build())
            .execute().use { /* drain */ }

        assertEquals(
            SlimapiContract.MID_PARTIAL_ENVELOPE_CAPABILITY,
            server.takeRequest().getHeader(SlimapiContract.X_SLIMAPI_CAPABILITIES)
        )
    }

    @Test
    fun `slim mode injects capabilities header on nested slimapi path`() {
        hostConfig.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = null, password = null, hostPort = null, slim = true
        )
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/slimapi/sessions/sid/messages")).build())
            .execute().use { /* drain */ }

        assertEquals(
            SlimapiContract.MID_PARTIAL_ENVELOPE_CAPABILITY,
            server.takeRequest().getHeader(SlimapiContract.X_SLIMAPI_CAPABILITIES)
        )
    }

    // ── 边界：路径前缀匹配 ────────────────────────────────────────────────

    @Test
    fun `path without trailing slash prefix is not matched`() {
        hostConfig.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = null, password = null, hostPort = null, slim = true
        )
        server.enqueue(MockResponse().setBody("ok"))

        // /slimapi (no trailing slash) — does NOT match prefix `/slimapi/`.
        // Defense against a future `slimapi` literal endpoint at root that's
        // unrelated to the sidecar surface.
        client.newCall(Request.Builder().url(server.url("/slimapi")).build())
            .execute().use { /* drain */ }

        assertNull(
            "/slimapi (no trailing slash) must NOT match prefix /slimapi/",
            server.takeRequest().getHeader(SlimapiContract.X_SLIMAPI_CAPABILITIES)
        )
    }

    // ── 替换语义：调用方预设的头被覆盖（防重复） ─────────────────────────

    @Test
    fun `predefined capabilities header is replaced not duplicated`() {
        hostConfig.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = null, password = null, hostPort = null, slim = true
        )
        server.enqueue(MockResponse().setBody("ok"))

        // Caller preset a stale value — interceptor must REPLACE (single source
        // of truth), not stack via addHeader.
        client.newCall(
            Request.Builder().url(server.url("/slimapi/health"))
                .header(SlimapiContract.X_SLIMAPI_CAPABILITIES, "old-value")
                .build()
        ).execute().use { /* drain */ }

        val request = server.takeRequest()
        assertEquals(
            "Interceptor must overwrite caller-supplied stale value",
            SlimapiContract.MID_PARTIAL_ENVELOPE_CAPABILITY,
            request.getHeader(SlimapiContract.X_SLIMAPI_CAPABILITIES)
        )
        // .header() (not addHeader) — at most one value per name.
        // RecordedRequest.headers returns okhttp3.Headers; .values(name) gives
        // the multi-valued list (empty if absent, N if N were stacked).
        val headerCount = request.headers.values(SlimapiContract.X_SLIMAPI_CAPABILITIES).size
        assertEquals(1, headerCount)
    }

    // ── 常量校验 ──────────────────────────────────────────────────────────

    @Test
    fun `capabilities header name is the documented value`() {
        assertEquals("X-Slimapi-Capabilities", SlimapiContract.X_SLIMAPI_CAPABILITIES)
    }

    @Test
    fun `capability value is the documented token`() {
        assertEquals("mid-partial-envelope=1", SlimapiContract.MID_PARTIAL_ENVELOPE_CAPABILITY)
    }
}
