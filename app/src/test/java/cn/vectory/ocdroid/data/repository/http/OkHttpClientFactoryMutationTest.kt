package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.data.repository.HostConfig
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T14 (CLIENT_CHANGES "mutation 只发一次，不因超时向 direct 重发"): the
 * per-method OkHttp client routing table — config-contract layer.
 *
 * Pins the THREE client variants' retry / timeout configs:
 *  - [OkHttpClientFactory.restClient]: 30 s read + `retryOnConnectionFailure = true`
 *    (GET is idempotent; safe to retry on connection failure).
 *  - [OkHttpClientFactory.mutationClient]: 30 s read + `retryOnConnectionFailure = false`
 *    (POST mutations — NEVER auto-retry; double-send hazard).
 *  - [OkHttpClientFactory.commandClient]: 300 s read + `retryOnConnectionFailure = false`
 *    (POST executeCommand — heavy server-side work window + same double-send hazard).
 *
 * The retry flag is the WHOLE POINT of T14 — the
 * `OkHttpClient.retryOnConnectionFailure` boolean is the single OkHttp-level
 * switch that controls whether a transport-level IOException triggers a
 * silent replay of the request. We pin it explicitly per variant so a future
 * "set all clients to retry=false" sweep cannot silently regress GET retry,
 * and a future "restore retry=true for speed" sweep cannot silently
 * re-introduce the POST double-send hazard.
 *
 * Routing-level (which wrapper uses which client) coverage lives in
 * `OpenCodeRepositoryMutationWiringTest`.
 *
 * **Why no MockWebServer-based retry behaviour test here** — OkHttp's
 * `retryOnConnectionFailure` flag fires only for "recoverable" IOExceptions
 * at the connection-establishment boundary. MockWebServer's
 * `SocketPolicy.DISCONNECT_AT_START` (and siblings) produce mid-protocol
 * failures ("unexpected end of stream" while reading response headers)
 * that OkHttp does NOT consider connection failures — so the resulting
 * test is non-discriminating between retry=true / retry=false. We rely on
 * the direct boolean inspection here (the value OkHttp actually reads) and
 * on the routing test for the wrapper-level proof.
 */
class OkHttpClientFactoryMutationTest {

    private val server = MockWebServer()
    private lateinit var factory: OkHttpClientFactory

    @Before
    fun setUp() {
        server.start()
        // Real interceptor instances; the tests only inspect OkHttp client
        // config, so the HostConfig defaults (no auth, no slim, no directory)
        // are fine. TrafficTracker / TrafficLogger are mocked — we don't
        // assert on traffic accounting here (covered by
        // TrafficCountingInterceptorTest), and the real TrafficLogger needs
        // an Android Context.
        val hostConfig = HostConfig()
        val cachePathSanitizer = CachePathSanitizer(hostConfig)
        val sslConfigFactory = SslConfigFactory(InMemoryTofuPinStore())
        factory = OkHttpClientFactory(
            sslConfigFactory,
            DirectoryHeaderInterceptor(),
            SlimapiVersionInterceptor(hostConfig),
            SlimapiDebugInterceptor(),
            AuthInterceptor(hostConfig),
            CacheControlInterceptor(hostConfig, cachePathSanitizer),
            TrafficCountingInterceptor(mockk(relaxed = true), mockk(relaxed = true)),
            ResponseSizeGuardInterceptor(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── T14-C1: restClient keeps retryOnConnectionFailure=true ───────────────

    @Test
    fun `restClient keeps retryOnConnectionFailure true`() {
        // GET is idempotent — safe to retry on connection failure. The flag
        // is the OkHttp default; we pin it explicitly so a future "set all
        // clients to retry=false" sweep cannot silently regress GET retry.
        val client = factory.restClient(hostPort = null)
        assertTrue(
            "restClient MUST keep retryOnConnectionFailure=true (GET idempotent)",
            client.retryOnConnectionFailure,
        )
    }

    @Test
    fun `restClient has 30s read timeout`() {
        val client = factory.restClient(hostPort = null)
        assertEquals(30_000, client.readTimeoutMillis)
    }

    // ── T14-C1: mutationClient has retryOnConnectionFailure=false ────────────

    @Test
    fun `mutationClient has retryOnConnectionFailure false`() {
        // The WHOLE POINT of mutationClient — a POST that broke the connection
        // after the server applied it MUST NOT be auto-replayed.
        val client = factory.mutationClient(hostPort = null)
        assertFalse(
            "mutationClient MUST set retryOnConnectionFailure=false (POST double-send hazard)",
            client.retryOnConnectionFailure,
        )
    }

    @Test
    fun `mutationClient has 30s read timeout`() {
        // Same 30 s window as restClient — mutations don't have command's
        // heavy-server-side work pattern.
        val client = factory.mutationClient(hostPort = null)
        assertEquals(30_000, client.readTimeoutMillis)
    }

    // ── T14-C3: commandClient has retryOnConnectionFailure=false ─────────────

    @Test
    fun `commandClient has retryOnConnectionFailure false`() {
        // executeCommand is a POST — same double-send hazard as the 30 s
        // mutations, only with a longer (300 s) read window that WORSENS the
        // blip-after-apply risk.
        val client = factory.commandClient(hostPort = null)
        assertFalse(
            "commandClient MUST set retryOnConnectionFailure=false (POST /command double-send hazard)",
            client.retryOnConnectionFailure,
        )
    }

    @Test
    fun `commandClient preserves 300s read timeout`() {
        // T14 explicitly forbids touching the 300 s window — only the retry
        // flag changes.
        val client = factory.commandClient(hostPort = null)
        assertEquals(300_000, client.readTimeoutMillis)
    }

    // ── Sanity: each variant produces a distinct client instance ────────────
    //
    // If all three were the same instance, the retry flag would be ambiguous
    // — this is the first smoke test that the variants are truly separate.

    @Test
    fun `restClient mutationClient commandClient are distinct instances`() {
        val rest = factory.restClient(hostPort = null)
        val mut = factory.mutationClient(hostPort = null)
        val cmd = factory.commandClient(hostPort = null)
        assertNotSame("mutationClient distinct from restClient", rest, mut)
        assertNotSame("commandClient distinct from restClient", rest, cmd)
        assertNotSame("mutationClient distinct from commandClient", mut, cmd)
    }

    @Test
    fun `mutationClient shares the base chain (connect timeout = 10s)`() {
        // Sanity that mutationClient isn't accidentally built without
        // baseBuilder — same 10 s connect timeout pin as restClient /
        // commandClient.
        val client: OkHttpClient = factory.mutationClient(hostPort = null)
        assertEquals(10_000, client.connectTimeoutMillis)
    }
}
