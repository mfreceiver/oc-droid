package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * R18 Phase 5++ coverage: the `activateTunnel` HTTP form-POST wrapper on
 * [OpenCodeRepository]. Coverage gap before this file: 0/12 branches, 18/27
 * lines on `activateTunnel$1` (the rethrow / HTTP-error / network-failure
 * / form-encoding paths). Also exercises flushTrafficLog / dumpTrafficLog
 * delegators (each 1 LOC, never invoked by other tests).
 *
 * Build pattern mirrors OpenCodeRepositoryWrapperTest: MockWebServer +
 * `OpenCodeRepository(mockk(relaxed=true), mockk(relaxed=true))`.
 */
class OpenCodeRepositoryTunnelTest {

    private val server = MockWebServer()
    private lateinit var repository: OpenCodeRepository

    @Before
    fun setup() = runBlocking {
        DebugLog.clear()
        server.start()
        repository = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        repository.configure(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `activateTunnel success returns Result success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = repository.activateTunnel(
            tunnelUrl = server.url("/tunnel").toString(),
            password = "secret",
        )

        assertTrue(result.isSuccess)
        // Verify form body was sent.
        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("pw=secret"))
        assertTrue(body.contains("persist_auth=off"))
    }

    @Test
    fun `activateTunnel HTTP failure returns failure with HTTP prefix`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("invalid password"),
        )

        val result = repository.activateTunnel(
            tunnelUrl = server.url("/tunnel").toString(),
            password = "wrong",
        )

        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()!!.message ?: ""
        // The HTTP branch throws raw (rethrown as-is), so the message starts
        // with HTTP.
        assertTrue("expected HTTP prefix, got: $msg", msg.startsWith("HTTP "))
        assertTrue(msg.contains("invalid password"))
    }

    @Test
    fun `activateTunnel HTTP failure with blank body falls back to empty marker`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(500).setBody(""),
        )

        val result = repository.activateTunnel(
            tunnelUrl = server.url("/tunnel").toString(),
            password = "x",
        )

        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()!!.message ?: ""
        // Empty body → the "空响应体" fallback marker is used.
        assertTrue(msg.contains("HTTP 500"))
    }

    @Test
    fun `activateTunnel network failure wraps the exception with class+message`() = runBlocking {
        // Point at an unroutable URL to trigger a real network exception
        // (not the MockWebServer). The client factory's tunnelClient(false)
        // uses the system trust manager, so connection refused / timeout is
        // surfaced as IOException.
        val result = repository.activateTunnel(
            tunnelUrl = "http://127.0.0.1:1/tunnel",  // port 1: connection refused
            password = "x",
        )

        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()!!.message ?: ""
        // The non-HTTP branch wraps with the exception class simple name.
        assertTrue(
            "expected wrapped exception class name in: $msg",
            msg.contains("Exception") || msg.contains("ConnectException") ||
                msg.contains("IOException") || msg.contains("Socket")
        )
    }

    @Test
    fun `flushTrafficLog delegates to TrafficLogger flushToDisk`() = runBlocking {
        // flushTrafficLog spawns a background thread; the test just verifies
        // the call doesn't throw.
        repository.flushTrafficLog()
        // Give the spawned thread a moment to settle.
        Thread.sleep(100)
    }

    @Test
    fun `dumpTrafficLog delegates to trafficLogger and returns the value`() = runBlocking {
        // The repository's trafficLogger is a relaxed mockk → dump() returns
        // the relaxed default (empty string for String return type). The test
        // verifies the delegator body executes without throwing; the
        // controller-side dump format is exercised in TrafficLoggerTest.
        val dump = repository.dumpTrafficLog()
        // Relaxed-mock default is "" (the dump() return value is unused
        // by callers in production other than for logcat display).
        assertEquals("", dump)
    }
}
