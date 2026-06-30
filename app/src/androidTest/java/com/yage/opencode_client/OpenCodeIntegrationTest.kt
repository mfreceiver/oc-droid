package com.yage.opencode_client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.util.SettingsManager
import com.yage.opencode_client.util.TrafficLogger
import com.yage.opencode_client.util.TrafficTracker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests against a real OpenCode server.
 * Credentials are loaded from .env at build time and passed via instrumentation arguments.
 * Copy .env.example to .env and fill in credentials. .env is gitignored.
 *
 * Run with: ./gradlew connectedDebugAndroidTest
 * Requires: OpenCode server at localhost:4096 (use 10.0.2.2:4096 for emulator in .env)
 */
@RunWith(AndroidJUnit4::class)
class OpenCodeIntegrationTest {

    private lateinit var repository: OpenCodeRepository
    private var serverUrl: String = ""
    private var username: String = ""
    private var password: String = ""

    @Before
    fun setup() {
        val args = InstrumentationRegistry.getArguments()
        serverUrl = args.getString("openCodeServerUrl") ?: ""
        username = args.getString("openCodeUsername") ?: ""
        password = args.getString("openCodePassword") ?: ""

        assumeTrue(
            "Skipping: no OpenCode server configured in OPENCODE_SERVER_URL",
            serverUrl.isNotBlank()
        )

        // R-18: OpenCodeRepository now requires TrafficTracker + TrafficLogger.
        // This is a real-server integration test, so construct real instances
        // from the instrumentation target context (they only count/log HTTP
        // traffic, which is exactly what this suite exercises).
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsManager = SettingsManager(ctx)
        repository = OpenCodeRepository(
            trafficTracker = TrafficTracker(settingsManager),
            trafficLogger = TrafficLogger(ctx)
        )
        repository.configure(
            baseUrl = serverUrl,
            username = username.ifEmpty { null },
            password = password.ifEmpty { null }
        )

        assumeTrue(
            "Skipping: OpenCode server is not reachable at $serverUrl",
            runBlocking { repository.checkHealth().isSuccess }
        )
    }

    @Test
    fun checkHealth() = runBlocking {
        val result = repository.checkHealth()
        assertTrue("Health check failed: ${result.exceptionOrNull()}", result.isSuccess)
        val health = result.getOrThrow()
        assertTrue(health.healthy)
        assertNotNull(health.version)
    }

    @Test
    fun getSessions_withCredentials() = runBlocking {
        assumeTrue("Skipping: no credentials in .env - copy .env.example to .env", username.isNotEmpty() && password.isNotEmpty())

        val result = repository.getSessions()
        assertTrue("Get sessions failed: ${result.exceptionOrNull()}", result.isSuccess)
        val sessions = result.getOrThrow()
        assertNotNull(sessions)
    }

    @Test
    fun getAgents() = runBlocking {
        val result = repository.getAgents()
        assertTrue("Get agents failed: ${result.exceptionOrNull()}", result.isSuccess)
        val agents = result.getOrThrow()
        assertNotNull(agents)
    }
}
