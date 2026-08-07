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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import cn.vectory.ocdroid.data.repository.http.SlimapiContract
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
        assertTrue("GET REST client keeps retry", slim.restHttp.retryOnConnectionFailure)
        assertFalse("command POST client never retries", slim.commandHttp.retryOnConnectionFailure)
        assertFalse("mutation POST client never retries", slim.mutationHttp.retryOnConnectionFailure)

        server.enqueue(MockResponse().setBody("slim"))
        slim.sseHttp.newCall(
            Request.Builder().url("$slimBase/slimapi/events").build(),
        ).execute().use { response -> assertEquals(200, response.code) }
        val slimSseRequest = server.takeRequest()
        assertEquals(
            SlimapiContract.SLIMAPI_CLIENT_VERSION.toString(),
            slimSseRequest.getHeader(SlimapiContract.X_SLIMAPI_VERSION),
        )

        val legacyBase = server.url("/").toString().trimEnd('/')
        repository.configure(baseUrl = legacyBase, slim = false)
        val legacy = repository.currentClientBundle()!!

        assertEquals(slim.generation + 1L, legacy.generation)
        assertEquals(legacyBase, legacy.hostSnapshot.baseUrl)
        assertFalse(legacy.hostSnapshot.slimHost)
        assertEquals("$legacyBase/", legacy.restRetrofit.baseUrl().toString())
        assertEquals("$legacyBase/", legacy.commandRetrofit.baseUrl().toString())
        assertEquals("$legacyBase/", legacy.mutationRetrofit.baseUrl().toString())
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

    // ── §concurrency-refactor: new coverage for the two-lock narrowing ──────

    /**
     * §6.1 §concurrency-refactor: concurrent configure storm.
     *
     * N threads each call [configure] with a distinct host. Because [configure]
     * serializes its bodies on `configureLock`, the publishes are observed
     * exactly N times with strictly-increasing consecutive generations, and
     * each published endpointFp matches one of the configured hosts. Concurrent
     * readers (mirroring T3-3-C1-iv) continue to observe complete, consistent
     * immutable bundles. This is the load-bearing test for the lock narrowing:
     * it would fail (duplicate/missing/non-monotonic generations, or a reader
     * seeing a partial bundle) had the [configureLock] + [ClientBundle.withGeneration]
     * stamping been wired incorrectly.
     */
    @Test
    fun `concurrent configure storm produces exactly N monotonic publishes with consistent readers`() {
        val stormSize = 24
        // Capture every publish under the monitor (onBundlePublished is invoked
        // inside synchronized(this)). A synchronized list mirrors the
        // single-writer-per-monitor contract; the test only reads it after join.
        val publishes = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, String>>())
        repository.onBundlePublished = { generation, endpointFp ->
            publishes += generation to endpointFp
        }

        val readerFailure = AtomicReference<Throwable?>(null)
        val stopReaders = java.util.concurrent.atomic.AtomicBoolean(false)
        val readers = (0 until 6).map {
            thread {
                while (!stopReaders.get()) {
                    val bundle = repository.currentClientBundle() ?: continue
                    try {
                        // Every field of an immutable bundle is self-consistent:
                        // the endpointFp (baseUrl) must match the Retrofit baseUrl
                        // captured on the same object. A torn/partial read (which
                        // the pre-refactor wide monitor could not produce, and the
                        // narrowed monitor must ALSO not produce) breaks this.
                        val trailing = bundle.hostSnapshot.baseUrl.substringAfterLast('/').ifBlank { "init" }
                        if (trailing.startsWith("storm-")) {
                            assertEquals(
                                "Retrofit baseUrl must match the bundle's endpointFp",
                                "${bundle.hostSnapshot.baseUrl}/",
                                bundle.restRetrofit.baseUrl().toString(),
                            )
                        }
                    } catch (error: Throwable) {
                        readerFailure.compareAndSet(null, error)
                    }
                }
            }
        }

        val writers = (0 until stormSize).map { index ->
            thread {
                repository.configure(
                    baseUrl = server.url("/storm-$index/").toString().trimEnd('/'),
                    username = "u-$index",
                    password = "p-$index",
                )
            }
        }
        writers.forEach { it.join() }
        stopReaders.set(true)
        readers.forEach { it.join() }
        readerFailure.get()?.let { throw it }

        // Exactly N publishes (one per successful configure; configureLock
        // serializes the bodies so no publish is lost or duplicated).
        assertEquals("exactly $stormSize publishes captured", stormSize, publishes.size)
        // Generations strictly increasing AND consecutive (1..N), proving the
        // prev+1 stamping under synchronized(this) is race-free.
        val generations = publishes.map { it.first }
        assertEquals(
            "generations must be the consecutive sequence 1..$stormSize",
            (1L..stormSize.toLong()).toList(),
            generations,
        )
        // Each published endpointFp matches a configured host (the storm used
        // distinct /storm-i/ hosts); the set must be exactly the N hosts.
        val expectedHosts = (0 until stormSize).map {
            server.url("/storm-$it/").toString().trimEnd('/')
        }.toSet()
        assertEquals(
            "every published endpointFp must match a configured host (no torn stamps)",
            expectedHosts,
            publishes.map { it.second }.toSet(),
        )
        // The final published bundle carries the highest generation.
        val finalBundle = repository.currentClientBundle()!!
        assertEquals(stormSize.toLong(), finalBundle.generation)
    }

    /**
     * §6.2 §concurrency-refactor: cert-error parity.
     *
     * A corrupted p12 configured via [configure] must surface the SAME error
     * message on BOTH the published bundle's [ClientBundle.clientCertError]
     * (read via [OpenCodeRepository.lastClientCertError]) AND the factory's
     * [SslConfigFactory.lastClientCertError] mirror. This proves the pre-built
     * [CandidateSsl] resolution (parsed ONCE in Phase 1) feeds both the bundle
     * field and the factory mirror via [publishClientCertResolution] — i.e. no
     * second p12 parse produces a divergent message.
     *
     * For a corrupted p12, the throwing exception carries a non-null message,
     * so both surfaces are equal AND non-null. The null-message →
     * "client cert load failed" fallback asymmetry (factory non-null, bundle
     * nullable) is covered directly in [SslConfigFactoryTest] on the new
     * [SslConfigFactory.publishClientCertResolution] setter.
     */
    @Test
    fun `corrupted p12 configure surfaces the same error on the bundle and the factory mirror`() {
        val corrupted = ClientCertMaterial(ByteArray(64) { it.toByte() }, "bad-pw".toCharArray(), null)

        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            clientCert = corrupted,
        )

        val bundle = repository.currentClientBundle()!!
        assertNotNull("bundle must carry the client-cert error", bundle.clientCertError)

        // Bundle-level surface (immutable published field).
        val bundleError = repository.lastClientCertError
        assertNotNull("repository.lastClientCertError (bundle mirror) must be non-null", bundleError)
        assertEquals(
            "bundle.clientCertError == repository.lastClientCertError (same published field)",
            bundle.clientCertError,
            bundleError,
        )

        // Factory-level mirror (the volatile lastClientCertError on the
        // repository's SslConfigFactory, published via
        // publishClientCertResolution — the new pre-built path).
        val factoryError = repositoryFactoryLastClientCertError()
        assertNotNull("factory.lastClientCertError must be non-null (corrupted p12)", factoryError)
        assertEquals(
            "bundle error and factory mirror must carry the IDENTICAL message " +
                "(one parse, two byte-identical surfaces)",
            bundle.clientCertError,
            factoryError,
        )
        // mTLS degraded to non-MutualTLS on a failed parse.
        assertTrue(
            "effective SSL must NOT be MutualTLS after a failed cert parse",
            bundle.effectiveSslConfig !is cn.vectory.ocdroid.data.repository.http.SslConfig.MutualTLS,
        )
    }

    /**
     * §6.4 §concurrency-refactor: [ClientBundle.withGeneration] structural test.
     *
     * Mirrors the [replaceClientBundleForTest] checks: a stamped copy shares
     * every OkHttp client / Retrofit / API and the [ownedGenerationClients]
     * retire coverage, while ONLY the generation changes. This is the contract
     * [configure]'s Phase 2 relies on — a stamped copy must retire the SAME
     * clients the pre-built bundle owned, or a subsequent configure would leak
     * / wrong-retire clients.
     */
    @Test
    fun `withGeneration stamps only the generation and shares retire-owned clients`() {
        repository.configure(baseUrl = server.url("/gen0/").toString().trimEnd('/'))
        val original = repository.currentClientBundle()!!

        val stamped = original.withGeneration(original.generation + 100L)

        // Only the generation changes.
        assertNotSame("generation must change", original.generation, stamped.generation)
        assertEquals(original.generation + 100L, stamped.generation)
        // Host / SSL / error identity preserved.
        assertSame(original.hostSnapshot, stamped.hostSnapshot)
        assertSame(original.effectiveSslConfig, stamped.effectiveSslConfig)
        assertEquals(original.clientCertError, stamped.clientCertError)
        // Every OkHttp client / Retrofit / API is the SAME instance (one
        // allocation; withGeneration is a structural copy, not a rebuild).
        assertSame(original.restHttp, stamped.restHttp)
        assertSame(original.restRetrofit, stamped.restRetrofit)
        assertSame(original.restApi, stamped.restApi)
        assertSame(original.sseHttp, stamped.sseHttp)
        assertSame(original.sseClient, stamped.sseClient)
        assertSame(original.commandHttp, stamped.commandHttp)
        assertSame(original.commandRetrofit, stamped.commandRetrofit)
        assertSame(original.commandApi, stamped.commandApi)
        assertSame(original.mutationHttp, stamped.mutationHttp)
        assertSame(original.mutationRetrofit, stamped.mutationRetrofit)
        assertSame(original.mutationApi, stamped.mutationApi)

        // Retire coverage is shared: the stamped copy carries the SAME
        // ownedGenerationClients as the pre-built bundle (proven by the
        // assertSame checks above — restHttp etc. are identical instances), so
        // retiring the stamped copy — what a later configure does to the
        // published bundle — tears down the SAME clients the pre-built bundle
        // owned. isRetired proves the retire CAS fired on the stamped copy;
        // the shared-instance asserts above prove a NON-sharing withGeneration
        // (the regression this test guards) would FAIL them. (connectionCount
        // is intentionally NOT asserted here — on a never-used client the pool
        // is already empty, so such a check would be a vacuous tautology, and
        // firing a real request to populate it would add network brittleness
        // for no extra coverage given the assertSame checks already pin shared
        // ownership.)
        stamped.retire()
        assertTrue("stamped copy is retired", stamped.isRetired)
    }

    /**
     * Helper: read the repository's [SslConfigFactory.lastClientCertError]
     * mirror via reflection (networkGraph is private; this mirrors
     * RepositoryNetworkGraphTest's reflection access). The factory mirror is
     * the volatile written by [publishClientCertResolution] under the publish
     * critical section.
     */
    private fun repositoryFactoryLastClientCertError(): String? {
        val graphField = OpenCodeRepository::class.java.getDeclaredField("networkGraph")
        graphField.isAccessible = true
        val graph = graphField.get(repository) as RepositoryNetworkGraph
        return graph.sslConfigFactory.lastClientCertError
    }
}
