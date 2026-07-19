package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.model.PermissionResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * T14-C2 wiring test: pins that each POST wrapper in [OpenCodeRepository]
 * routes through the dedicated `mutationApi` (backed by a
 * `retryOnConnectionFailure = false` OkHttpClient) and `executeCommand`
 * routes through `commandApi` (300 s + retry=false).
 *
 * **Two layers of proof:**
 *
 *  1. **Reflection layer** — verifies the three Retrofit instances (`api`,
 *     `mutationApi`, `commandApi`) are each backed by the expected OkHttp
 *     client with the expected retry / timeout config. This pins the wiring
 *     at the construction layer (the `rebuildClients` call path) and would
 *     catch a regression that, say, attached the same `restHttp` to all
 *     three Retrofit instances.
 *
 *  2. **Routing layer (mock-swap + coVerify)** — for every POST wrapper,
 *     swaps the private `mutationApi` field with a recording mockk and
 *     asserts the wrapper invoked it. The symmetric check swaps `commandApi`
 *     for `executeCommand`. This proves each wrapper ACTUALLY uses the
 *     intended Retrofit instance — catching any "left on `api`" regression
 *     that reflection-level wiring cannot detect.
 *
 * **Why not behavioural DISCONNECT_AT_START** — OkHttp's
 * `retryOnConnectionFailure` flag fires only on connection-establishment
 * IOExceptions; MockWebServer's `DISCONNECT_AT_START` produces mid-protocol
 * failures ("unexpected end of stream") that OkHttp does NOT consider
 * connection failures for retry purposes — so a behavioural "POST fails →
 * no retry" test is non-discriminating (it'd pass with retry=true too).
 * Mock-swap + coVerify is the strongest reliable proof of routing.
 */
class OpenCodeRepositoryMutationWiringTest {

    private val server = MockWebServer()
    private lateinit var repository: OpenCodeRepository

    @Before
    fun setUp() = runBlocking {
        server.start()
        repository = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        repository.configure(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── Reflection helpers ───────────────────────────────────────────────────

    /** Reads a private var on [OpenCodeRepository] by name via Java reflection. */
    private fun readField(name: String): Any? {
        val field = OpenCodeRepository::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(repository)
    }

    /** Writes a private var on [OpenCodeRepository] by name via Java reflection. */
    private fun writeField(name: String, value: Any?) {
        val field = OpenCodeRepository::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(repository, value)
    }

    private fun okHttp(name: String): OkHttpClient = readField(name) as OkHttpClient

    private fun retrofit(name: String): Retrofit = readField(name) as Retrofit

    /**
     * Builds a relaxed mockk [OpenCodeApi] whose POST methods all throw a
     * marker [RuntimeException]. Used for routing verification: swap one of
     * `api` / `mutationApi` / `commandApi` with this mock, call the wrapper,
     * and the marker in the failure cause identifies which Retrofit instance
     * the wrapper actually invoked.
     *
     * Relaxed-mode defaults are left on for GET / non-POST methods so a
     * wrapper that calls BOTH `mutationApi.X` (POST) and `api.getY` (GET)
     * fails only on the POST marker — that's exactly the discriminator we
     * need for the routing test (POST wrappers should hit mutationApi; GET
     * wrappers should NOT hit mutationApi).
     */
    private fun throwingApi(marker: String): OpenCodeApi = mockk<OpenCodeApi>(relaxed = true) {
        coEvery { createSession(any(), any()) } throws RuntimeException(marker)
        coEvery { promptAsync(any(), any()) } throws RuntimeException(marker)
        coEvery { abortSession(any()) } throws RuntimeException(marker)
        coEvery { summarizeSession(any(), any()) } throws RuntimeException(marker)
        coEvery { forkSession(any(), any()) } throws RuntimeException(marker)
        coEvery { revertSession(any(), any()) } throws RuntimeException(marker)
        coEvery { respondPermission(any(), any(), any()) } throws RuntimeException(marker)
        coEvery { replyQuestion(any(), any(), any()) } throws RuntimeException(marker)
        coEvery { rejectQuestion(any(), any()) } throws RuntimeException(marker)
        coEvery { replySlimapiQuestion(any(), any()) } throws RuntimeException(marker)
        coEvery { rejectSlimapiQuestion(any(), any()) } throws RuntimeException(marker)
        coEvery { respondSlimapiPermission(any(), any(), any()) } throws RuntimeException(marker)
        coEvery { executeCommand(any(), any(), any()) } throws RuntimeException(marker)
    }

    /**
     * Asserts that [call]'s Result.isFailure carries [marker] in its
     * exception message — the proof that the wrapper routed through the
     * swapped (throwing) api instance.
     */
    private fun assertRoutedVia(
        marker: String,
        call: () -> kotlin.Result<*>,
    ) {
        val result = call()
        assertTrue(
            "wrapper MUST surface failure (routed via throwing mock): $result",
            result.isFailure,
        )
        val cause = result.exceptionOrNull()
        assertTrue(
            "wrapper failure cause MUST be the marker '$marker'; got $cause",
            cause is RuntimeException && cause.message == marker,
        )
    }

    // ── Layer 1: OkHttp client config per declared field ────────────────────

    @Test
    fun `restHttp keeps retryOnConnectionFailure true`() {
        val restHttp = okHttp("restHttp")
        assertTrue("restHttp.retryOnConnectionFailure", restHttp.retryOnConnectionFailure)
        assertEquals(30_000, restHttp.readTimeoutMillis)
    }

    @Test
    fun `mutationHttp has retryOnConnectionFailure false`() {
        val mutationHttp = okHttp("mutationHttp")
        assertFalse("mutationHttp.retryOnConnectionFailure", mutationHttp.retryOnConnectionFailure)
        assertEquals(30_000, mutationHttp.readTimeoutMillis)
    }

    @Test
    fun `commandHttp has retryOnConnectionFailure false and 300s read`() {
        val commandHttp = okHttp("commandHttp")
        assertFalse("commandHttp.retryOnConnectionFailure", commandHttp.retryOnConnectionFailure)
        // T14 forbids touching the 300 s window.
        assertEquals(300_000, commandHttp.readTimeoutMillis)
    }

    @Test
    fun `mutationHttp commandHttp and restHttp are distinct instances`() {
        val rest = okHttp("restHttp")
        val mut = okHttp("mutationHttp")
        val cmd = okHttp("commandHttp")
        assertNotSame("mutationHttp MUST be a distinct client from restHttp", rest, mut)
        assertNotSame("commandHttp MUST be a distinct client from restHttp", rest, cmd)
        assertNotSame("mutationHttp MUST be a distinct client from commandHttp", mut, cmd)
    }

    @Test
    fun `each Retrofit instance is bound to its expected OkHttp client`() {
        // retrofit.callFactory() returns the OkHttpClient passed via
        // `.client(...)` — pins that buildRetrofit actually attached the
        // right client to each instance.
        assertEquals(
            "retrofit (api) MUST be backed by restHttp",
            okHttp("restHttp"),
            retrofit("retrofit").callFactory(),
        )
        assertEquals(
            "mutationRetrofit (mutationApi) MUST be backed by mutationHttp",
            okHttp("mutationHttp"),
            retrofit("mutationRetrofit").callFactory(),
        )
        assertEquals(
            "commandRetrofit (commandApi) MUST be backed by commandHttp",
            okHttp("commandHttp"),
            retrofit("commandRetrofit").callFactory(),
        )
    }

    // ── Layer 2: per-wrapper routing proof via mock-swap ────────────────────
    //
    // For every POST wrapper: swap mutationApi with a throwing mock, assert
    // the wrapper fails with the marker. The symmetric check swaps api (the
    // GET client) and asserts the wrapper DOES NOT fail with mutationApi's
    // marker — proving the wrapper is NOT accidentally routing through api.
    //
    // The wrapper's failure-only-on-mutationApi-marker behaviour is the
    // single-assertion proof of routing.

    @Test
    fun `POST createSession routes through mutationApi`() {
        val mock = throwingApi("VIA_MUTATION_API")
        writeField("mutationApi", mock)
        assertRoutedVia("VIA_MUTATION_API") {
            runBlocking { repository.createSession(title = "t") }
        }
        coVerify { mock.createSession(any(), any()) }
    }

    @Test
    fun `POST createSession does NOT route through api`() {
        // Swap api (NOT mutationApi) with the throwing mock — the wrapper
        // should still succeed against the live MockWebServer, proving it
        // is NOT using api.
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"id":"s1","directory":"/w"}""")
                    .setHeader("Content-Type", "application/json"),
            )
            // Re-configure so the live wiring hits the new server.
            repository.configure(baseUrl = server.url("/").toString().trimEnd('/'))

            writeField("api", throwingApi("VIA_API"))
            val result = runBlocking { repository.createSession(title = "t") }
            assertTrue(
                "createSession MUST NOT route through api; should have hit MockWebServer: $result",
                result.isSuccess,
            )
            assertEquals("live wire call MUST have hit the server", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `POST sendMessage (promptAsync) routes through mutationApi`() {
        val mock = throwingApi("VIA_MUTATION_API")
        writeField("mutationApi", mock)
        assertRoutedVia("VIA_MUTATION_API") {
            runBlocking { repository.sendMessage(sessionId = "s1", text = "hi") }
        }
        coVerify { mock.promptAsync(any(), any()) }
    }

    @Test
    fun `POST abortSession routes through mutationApi`() {
        val mock = throwingApi("VIA_MUTATION_API")
        writeField("mutationApi", mock)
        assertRoutedVia("VIA_MUTATION_API") {
            runBlocking { repository.abortSession("s1") }
        }
        coVerify { mock.abortSession(any()) }
    }

    @Test
    fun `POST summarizeSession routes through mutationApi`() {
        val mock = throwingApi("VIA_MUTATION_API")
        writeField("mutationApi", mock)
        assertRoutedVia("VIA_MUTATION_API") {
            runBlocking {
                repository.summarizeSession(
                    "s1",
                    cn.vectory.ocdroid.data.model.Message.ModelInfo(
                        providerId = "p",
                        modelId = "m",
                    ),
                )
            }
        }
        coVerify { mock.summarizeSession(any(), any()) }
    }

    @Test
    fun `POST forkSession routes through mutationApi`() {
        val mock = throwingApi("VIA_MUTATION_API")
        writeField("mutationApi", mock)
        assertRoutedVia("VIA_MUTATION_API") {
            runBlocking { repository.forkSession("s1", messageId = null) }
        }
        coVerify { mock.forkSession(any(), any()) }
    }

    @Test
    fun `POST revertSession routes through mutationApi`() {
        val mock = throwingApi("VIA_MUTATION_API")
        writeField("mutationApi", mock)
        assertRoutedVia("VIA_MUTATION_API") {
            runBlocking { repository.revertSession("s1", messageId = "m1", partId = null) }
        }
        coVerify { mock.revertSession(any(), any()) }
    }

    @Test
    fun `POST respondPermission routes through mutationApi`() {
        val mock = throwingApi("VIA_MUTATION_API")
        writeField("mutationApi", mock)
        assertRoutedVia("VIA_MUTATION_API") {
            runBlocking {
                repository.respondPermission("s1", "p1", PermissionResponse.ONCE)
            }
        }
        coVerify { mock.respondPermission(any(), any(), any()) }
    }

    @Test
    fun `POST replyQuestion routes through mutationApi`() {
        val mock = throwingApi("VIA_MUTATION_API")
        writeField("mutationApi", mock)
        assertRoutedVia("VIA_MUTATION_API") {
            runBlocking {
                repository.replyQuestion("r1", answers = listOf(listOf("a")), directory = null)
            }
        }
        coVerify { mock.replyQuestion(any(), any(), any()) }
    }

    @Test
    fun `POST rejectQuestion routes through mutationApi`() {
        val mock = throwingApi("VIA_MUTATION_API")
        writeField("mutationApi", mock)
        assertRoutedVia("VIA_MUTATION_API") {
            runBlocking { repository.rejectQuestion("r1", directory = null) }
        }
        coVerify { mock.rejectQuestion(any(), any()) }
    }

    @Test
    fun `POST replySlimapiQuestion routes through mutationApi`() {
        val mock = throwingApi("VIA_MUTATION_API")
        writeField("mutationApi", mock)
        assertRoutedVia("VIA_MUTATION_API") {
            runBlocking {
                repository.replySlimapiQuestion(
                    questionId = "q1",
                    answers = listOf(listOf("a")),
                    routeToken = null,
                )
            }
        }
        coVerify { mock.replySlimapiQuestion(any(), any()) }
    }

    @Test
    fun `POST rejectSlimapiQuestion routes through mutationApi`() {
        val mock = throwingApi("VIA_MUTATION_API")
        writeField("mutationApi", mock)
        assertRoutedVia("VIA_MUTATION_API") {
            runBlocking {
                repository.rejectSlimapiQuestion(questionId = "q1", routeToken = null)
            }
        }
        coVerify { mock.rejectSlimapiQuestion(any(), any()) }
    }

    @Test
    fun `POST respondSlimapiPermission routes through mutationApi`() {
        val mock = throwingApi("VIA_MUTATION_API")
        writeField("mutationApi", mock)
        assertRoutedVia("VIA_MUTATION_API") {
            runBlocking {
                repository.respondSlimapiPermission(
                    sessionId = "s1",
                    permissionId = "p1",
                    response = PermissionResponse.ALWAYS,
                    routeToken = null,
                )
            }
        }
        coVerify { mock.respondSlimapiPermission(any(), any(), any()) }
    }

    @Test
    fun `POST executeCommand routes through commandApi NOT mutationApi`() {
        // executeCommand stays on commandApi (300 s preserved + retry=false).
        // Symmetric mock-swap: commandApi gets the throwing mock; the wrapper
        // MUST fail with that marker, proving it uses commandApi.
        val cmdMock = throwingApi("VIA_COMMAND_API")
        writeField("commandApi", cmdMock)
        assertRoutedVia("VIA_COMMAND_API") {
            runBlocking {
                repository.executeCommand(
                    sessionId = "s1",
                    command = "/compact",
                    arguments = "",
                    agent = null,
                    directory = null,
                )
            }
        }
        coVerify { cmdMock.executeCommand(any(), any(), any()) }
    }

    @Test
    fun `POST executeCommand does NOT route through mutationApi`() {
        // Symmetric negative: swapping mutationApi (NOT commandApi) with a
        // throwing mock MUST NOT break executeCommand — it should still
        // reach commandApi + MockWebServer. This proves the
        // commandApi/mutationApi split is real.
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(MockResponse().setResponseCode(202))
            repository.configure(baseUrl = server.url("/").toString().trimEnd('/'))

            writeField("mutationApi", throwingApi("VIA_MUTATION_API"))
            val result = runBlocking {
                repository.executeCommand(
                    sessionId = "s1",
                    command = "/compact",
                    arguments = "",
                    agent = null,
                    directory = null,
                )
            }
            assertTrue(
                "executeCommand MUST NOT route through mutationApi: $result",
                result.isSuccess,
            )
            assertEquals("live wire call MUST have hit the server", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    // ── Control: a GET wrapper does NOT route through mutationApi ───────────
    //
    // If GET wrappers were accidentally re-routed to mutationApi, the
    // retry-on-failure semantics for idempotent GETs would silently
    // disappear (mutationApi has retry=false). This test pins that GETs
    // stay on `api` / restHttp.

    @Test
    fun `control GET does NOT route through mutationApi`() {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("[]")
                    .setHeader("Content-Type", "application/json"),
            )
            repository.configure(baseUrl = server.url("/").toString().trimEnd('/'))

            writeField("mutationApi", throwingApi("VIA_MUTATION_API"))
            val result = runBlocking { repository.getCommands() }
            assertTrue(
                "getCommands MUST NOT route through mutationApi: $result",
                result.isSuccess,
            )
            assertEquals("live wire call MUST have hit the server", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
