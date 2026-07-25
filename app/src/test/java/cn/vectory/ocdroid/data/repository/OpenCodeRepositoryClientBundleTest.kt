package cn.vectory.ocdroid.data.repository

import io.mockk.mockk
import okhttp3.Dispatcher
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * T2A.4 regression coverage for the immutable, generation-scoped client
 * publication boundary.
 *
 * These tests deliberately inspect the bundle identity rather than a set of
 * volatile mirrors. A successful configure must publish one object containing
 * every client/API variant; a failed candidate build must leave that object
 * untouched.
 */
class OpenCodeRepositoryClientBundleTest {

    private val server = MockWebServer()
    private lateinit var repository: OpenCodeRepository

    @Before
    fun setUp() {
        server.start()
        repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `failed candidate build does not publish bundle generation or host`() {
        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "alice",
            password = "a-secret",
        )
        val before = repository.currentClientBundle()!!

        assertThrows(Throwable::class.java) {
            repository.configure(
                baseUrl = "http://host with space/",
                username = "bob",
                password = "b-secret",
            )
        }

        val after = repository.currentClientBundle()!!
        assertSame("failed candidate must not replace current bundle", before, after)
        assertEquals("generation must not advance on candidate failure", before.generation, after.generation)
        assertEquals("host snapshot must remain A", before.hostSnapshot, after.hostSnapshot)
        assertEquals("published host mirror must remain A", before.hostSnapshot.baseUrl, repository.hostSnapshot().baseUrl)
    }

    @Test
    fun `successful configure publishes one generation for all five client families`() {
        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "alice",
            password = "a-secret",
            slim = true,
        )
        val bundle = repository.currentClientBundle()!!

        assertEquals(1L, bundle.generation)
        assertEquals(server.url("/").toString().trimEnd('/'), bundle.hostSnapshot.baseUrl)
        assertEquals("alice", bundle.hostSnapshot.username)
        assertTrue(bundle.hostSnapshot.slimHost)
        assertNotNull(bundle.restApi)
        assertNotNull(bundle.sseClient)
        assertNotNull(bundle.commandApi)
        assertNotNull(bundle.mutationApi)
        assertNotNull(bundle.apiV2)
        assertFalse("command is a POST client", bundle.commandHttp.retryOnConnectionFailure)
        assertFalse("mutation is a POST client", bundle.mutationHttp.retryOnConnectionFailure)
    }

    @Test
    fun `reconfigure retires old generation while preserving shared cache`() {
        repository.configure(baseUrl = server.url("/").toString().trimEnd('/'))
        val old = repository.currentClientBundle()!!
        val oldCache = old.restHttp.cache

        repository.configure(baseUrl = server.url("/next/").toString().trimEnd('/'))
        val current = repository.currentClientBundle()!!

        assertEquals(old.generation + 1L, current.generation)
        assertTrue("published replacement must retire old bundle", old.isRetired)
        assertSame("disk Cache belongs to graph, not generation", oldCache, current.restHttp.cache)
        oldCache?.let { cache -> assertFalse("retirement must not close shared Cache", cache.isClosed) }
    }

    /**
     * T3.3-C3/C9: retiring an old generation cancels its in-flight calls and
     * evicts its connection pool, while the graph-owned disk Cache remains
     * open. Mutation/command clients keep their no-retry policy in the bundle;
     * the explicit no-retry assertions remain in the variant test below.
     */
    @Test
    fun `retirement cancels old calls and evicts pools without closing shared cache`() {
        repository.configure(baseUrl = server.url("/a/").toString().trimEnd('/'))
        val old = repository.currentClientBundle()!!
        val sharedCache = old.restHttp.cache
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbackFailure = AtomicReference<Throwable?>(null)
        val callbackDone = CountDownLatch(1)

        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS)) {
                    "held old-generation call was not released"
                }
                return MockResponse().setBody("late")
            }
        }
        old.restHttp.newCall(
            Request.Builder().url(server.url("/held")).build(),
        ).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callbackFailure.set(e)
                callbackDone.countDown()
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
                callbackDone.countDown()
            }
        })

        assertTrue("old-generation request must be in flight", started.await(5, TimeUnit.SECONDS))
        repository.configure(baseUrl = server.url("/b/").toString().trimEnd('/'))
        release.countDown()

        assertTrue("retirement cancelAll must complete the old call", callbackDone.await(5, TimeUnit.SECONDS))
        assertTrue("old call must fail by cancellation", callbackFailure.get() != null)
        assertEquals("retirement evictAll must drain the old pool", 0, old.restHttp.connectionPool.connectionCount())
        assertFalse("shared graph cache must remain open", sharedCache?.isClosed == true)
        val current = repository.currentClientBundle()!!
        assertFalse("retirement must not re-enable command retries", current.commandHttp.retryOnConnectionFailure)
        assertFalse("retirement must not re-enable mutation retries", current.mutationHttp.retryOnConnectionFailure)
    }

    @Test
    fun `old generation interceptors retain old credentials after host switch`() {
        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "alice",
            password = "a-secret",
        )
        val old = repository.currentClientBundle()!!

        repository.configure(
            baseUrl = server.url("/new/").toString().trimEnd('/'),
            username = "bob",
            password = "b-secret",
        )

        server.enqueue(MockResponse().setBody("ok"))
        old.restHttp.newCall(
            Request.Builder().url(server.url("/direct")).build()
        ).execute().use { response ->
            assertEquals(200, response.code)
        }
        val request = server.takeRequest()
        val expected = "Basic " + Base64.getEncoder().encodeToString("alice:a-secret".toByteArray())
        assertEquals("old client must never read B's live credentials", expected, request.getHeader("Authorization"))
    }

    @Test
    fun `in flight A response released after B publish keeps A headers and cannot replace B`() {
        val baseA = server.url("/a/").toString().trimEnd('/')
        repository.configure(
            baseUrl = baseA,
            username = "alice",
            password = "a-secret",
        )
        val old = repository.currentClientBundle()!!

        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val capturedAuthorization = AtomicReference<String?>(null)
        val serverFailure = AtomicReference<Throwable?>(null)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                capturedAuthorization.set(request.getHeader("Authorization"))
                requestStarted.countDown()
                check(releaseResponse.await(5, TimeUnit.SECONDS)) {
                    "old-host response was not released"
                }
                return MockResponse().setBody("old-host-result")
            }
        }

        val responseCode = AtomicReference<Int?>(null)
        // Use the old bundle's immutable interceptor chain, but an independent
        // dispatcher. Production retirement cancels owned dispatchers as a
        // safety net; the independent dispatcher models an already-suspended
        // operation whose response can still arrive after publication.
        val suspendedAClient = old.restHttp.newBuilder()
            .dispatcher(Dispatcher())
            .build()
        val oldRequest = thread {
            try {
                suspendedAClient.newCall(
                    Request.Builder().url("$baseA/held").build(),
                ).execute().use { response -> responseCode.set(response.code) }
            } catch (error: Throwable) {
                serverFailure.set(error)
            }
        }

        assertTrue(
            "A request must reach the server before B is published",
            requestStarted.await(5, TimeUnit.SECONDS),
        )

        val baseB = server.url("/b/").toString().trimEnd('/')
        repository.configure(
            baseUrl = baseB,
            username = "bob",
            password = "b-secret",
            slim = true,
        )
        val current = repository.currentClientBundle()!!

        assertTrue("A bundle must be retired after B publication", old.isRetired)
        assertSame("the published identity must remain B", current, repository.currentClientBundle())
        assertEquals(old.generation + 1L, current.generation)
        assertEquals(baseB, current.hostSnapshot.baseUrl)
        assertEquals("bob", current.hostSnapshot.username)
        assertEquals(
            "A Retrofit endpoint must remain captured at A after B publication",
            "$baseA/",
            old.restRetrofit.baseUrl().toString(),
        )
        assertEquals(
            "B Retrofit endpoint must be captured at B",
            "$baseB/",
            current.restRetrofit.baseUrl().toString(),
        )

        releaseResponse.countDown()
        oldRequest.join(5_000)
        serverFailure.get()?.let { throw it }
        assertEquals("the suspended A response may complete after B", 200, responseCode.get())
        assertEquals(
            "A must retain its captured credentials after B publication",
            "Basic " + Base64.getEncoder().encodeToString("alice:a-secret".toByteArray()),
            capturedAuthorization.get(),
        )
        assertSame("A's late response must not replace B", current, repository.currentClientBundle())
    }

    @Test
    fun `slim and legacy bundles use distinct generations and their own base URLs`() {
        // Slim and legacy use the same authority. The `/slimapi/` prefix is a
        // request route, not part of the configured base URL; putting it in
        // the base URL would make the actual path `/slim/slimapi/...` and
        // correctly bypass the path-gated version interceptor.
        val slimBase = server.url("/").toString().trimEnd('/')
        repository.configure(baseUrl = slimBase, slim = true)
        val slim = repository.currentClientBundle()!!

        assertEquals(slimBase, slim.hostSnapshot.baseUrl)
        assertTrue(slim.hostSnapshot.slimHost)
        assertEquals("$slimBase/", slim.restRetrofit.baseUrl().toString())
        assertEquals("$slimBase/", slim.commandRetrofit.baseUrl().toString())
        assertEquals("$slimBase/", slim.mutationRetrofit.baseUrl().toString())
        assertEquals("$slimBase/api/", slim.v2Retrofit.baseUrl().toString())
        assertTrue("GET REST client keeps retry", slim.restHttp.retryOnConnectionFailure)
        assertFalse("command POST client never retries", slim.commandHttp.retryOnConnectionFailure)
        assertFalse("mutation POST client never retries", slim.mutationHttp.retryOnConnectionFailure)

        server.enqueue(MockResponse().setBody("slim"))
        slim.sseHttp.newCall(
            Request.Builder().url("$slimBase/slimapi/events").build(),
        ).execute().use { response -> assertEquals(200, response.code) }
        val slimSseRequest = server.takeRequest()
        assertEquals("1", slimSseRequest.getHeader("X-Slimapi-Version"))

        val legacyBase = server.url("/").toString().trimEnd('/')
        repository.configure(baseUrl = legacyBase, slim = false)
        val legacy = repository.currentClientBundle()!!

        assertEquals(slim.generation + 1L, legacy.generation)
        assertEquals(legacyBase, legacy.hostSnapshot.baseUrl)
        assertFalse(legacy.hostSnapshot.slimHost)
        assertEquals("$legacyBase/", legacy.restRetrofit.baseUrl().toString())
        assertEquals("$legacyBase/", legacy.commandRetrofit.baseUrl().toString())
        assertEquals("$legacyBase/", legacy.mutationRetrofit.baseUrl().toString())
        assertEquals("$legacyBase/api/", legacy.v2Retrofit.baseUrl().toString())
        assertFalse("legacy command POST client never retries", legacy.commandHttp.retryOnConnectionFailure)
        assertFalse("legacy mutation POST client never retries", legacy.mutationHttp.retryOnConnectionFailure)

        server.enqueue(MockResponse().setBody("legacy"))
        legacy.sseHttp.newCall(
            Request.Builder().url("$legacyBase/slimapi/events").build(),
        ).execute().use { response -> assertEquals(200, response.code) }
        val legacySseRequest = server.takeRequest()
        assertEquals(
            "legacy variant must not emit slim header",
            null,
            legacySseRequest.getHeader("X-Slimapi-Version"),
        )
    }

    @Test
    fun `T3-3-C1-iv concurrent readers observe complete immutable bundles`() {
        val failure = AtomicReference<Throwable?>(null)
        val writer = thread {
            repeat(20) { index ->
                val suffix = if (index % 2 == 0) "a" else "b"
                repository.configure(
                    baseUrl = server.url("/$suffix/").toString().trimEnd('/'),
                    username = suffix,
                    password = "$suffix-password",
                )
            }
        }
        val readers = (0 until 8).map {
            thread {
                repeat(2_000) {
                    val bundle = repository.currentClientBundle() ?: return@repeat
                    val suffix = bundle.hostSnapshot.baseUrl.substringAfterLast('/').ifBlank { "a" }
                    if (suffix == "a" || suffix == "b") {
                        try {
                            assertEquals(suffix, bundle.hostSnapshot.username)
                            assertEquals("$suffix-password", bundle.hostSnapshot.password)
                        } catch (error: Throwable) {
                            failure.compareAndSet(null, error)
                        }
                    }
                }
            }
        }
        writer.join()
        readers.forEach { it.join() }
        failure.get()?.let { throw it }
    }
}
