package cn.vectory.ocdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §streaming-state-sync-diag: live SSE frame capture during a real send, for
 * BOTH slim (4097) and legacy (4096) opencode transports, run against the dev
 * host's local opencode instance (10.0.2.2 from the emulator).
 *
 * PURPOSE: definitively answer whether the slim sidecar emits
 * `message.part.delta` (per-token streaming) during generation, or only
 * curated `session.digest` frames (debounced REST-reconcile). This is the
 * pivotal unverified hypothesis in the streaming-state-sync diagnosis. The
 * legacy capture is the control: legacy opencode streams raw part deltas, so
 * comparing the two frame-type histograms is conclusive.
 *
 * Also captures, for every `session.digest` frame, the `updatedAt` /
 * `messageId` / `status` fields — to confirm whether `updatedAt` advances
 * DURING streaming (driving the digest reconcile fetch) or only at completion.
 *
 * Run (slim):
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=cn.vectory.ocdroid.SseFrameCaptureTest \
 *     -Pandroid.testInstrumentationRunnerArguments.slimapiServerUrl=http://10.0.2.2:4097 \
 *     -Pandroid.testInstrumentationRunnerArguments.openCodeServerUrl=http://10.0.2.2:4096 \
 *     -Pandroid.testInstrumentationRunnerArguments.openCodeUsername=user \
 *     -Pandroid.testInstrumentationRunnerArguments.openCodePassword=pass
 *
 * The test prints every captured frame + a per-type SUMMARY to stdout
 * (surfaced in the Gradle test report + logcat tag "TestRunner"/System.out).
 *
 * Non-destructive: creates ONE throwaway session per transport under
 * /tmp/sse-capture-<label>; does not touch user data.
 */
@RunWith(AndroidJUnit4::class)
class SseFrameCaptureTest {

    private var slimUrl: String = ""
    private var legacyUrl: String = ""
    private var username: String = ""
    private var password: String = ""

    @Before
    fun setup() {
        val args = InstrumentationRegistry.getArguments()
        slimUrl = args.getString("slimapiServerUrl") ?: ""
        legacyUrl = args.getString("openCodeServerUrl") ?: ""
        username = args.getString("openCodeUsername") ?: ""
        password = args.getString("openCodePassword") ?: ""
    }

    private fun newRepo(): OpenCodeRepository {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsManager = SettingsManager(ctx)
        return OpenCodeRepository(
            trafficTracker = TrafficTracker(settingsManager),
            trafficLogger = TrafficLogger(ctx),
        )
    }

    /** Extracts the diagnostic-relevant fields per frame type. */
    private fun describe(p: SSEPayload): String = when (p.type) {
        "session.digest" ->
            "sid=${p.getString("sessionID")} status=${p.getString("status")} " +
                "updatedAt=${p.properties?.get("updatedAt")} messageId=${p.getString("messageID")}"
        "session.status" ->
            "sid=${p.getString("sessionID")} status=${p.getString("status")}"
        "message.part.delta", "message.part.updated", "message.updated", "message.removed" ->
            "sid=${p.getString("sessionID")} mid=${p.getString("messageID")} pid=${p.getString("partID")}"
        else -> ""
    }

    /**
     * The core capture. Opens SSE, lets it connect, sends a prompt, collects
     * frames for [waitMs], then prints every frame + a per-type SUMMARY.
     */
    private suspend fun capture(
        baseUrl: String,
        slim: Boolean,
        label: String,
        waitMs: Long = 35_000L,
    ) {
        println("[$label] === begin (baseUrl=$baseUrl slim=$slim) ===")
        val repo = newRepo()
        repo.configure(
            baseUrl = baseUrl,
            username = username.ifEmpty { null },
            password = password.ifEmpty { null },
            slim = slim,
        )
        val health = repo.checkHealth()
        if (!health.isSuccess) {
            println("[$label] SKIP: health check failed: ${health.exceptionOrNull()}")
            return
        }
        println("[$label] health OK version=${health.getOrThrow().version}")

        val dir = "/tmp/sse-capture-$label"
        val session = repo.createSession(title = "sse-capture-$label", directory = dir)
        if (!session.isSuccess) {
            println("[$label] SKIP: createSession failed: ${session.exceptionOrNull()}")
            return
        }
        val sid = session.getOrThrow().id
        println("[$label] session created sid=$sid dir=$dir")

        val frames = mutableListOf<String>()
        val lock = Any()
        val t0 = System.currentTimeMillis()

        kotlinx.coroutines.coroutineScope {
            val sseJob = launch {
                repo.connectSSE(directory = null).collect { result ->
                    val ev = result.getOrNull()
                    val t = System.currentTimeMillis() - t0
                    if (ev == null) {
                        synchronized(lock) {
                            frames.add("[t=${t}ms] ERROR ${result.exceptionOrNull()}")
                        }
                        return@collect
                    }
                    val line = "[t=${t}ms] type=${ev.payload.type} ${describe(ev.payload)}"
                    synchronized(lock) { frames.add(line) }
                }
            }
            // Let the SSE feed establish before we send.
            delay(2_500)
            val send = repo.sendMessage(
                sessionId = sid,
                text = "Reply with exactly this sentence then stop: the quick brown fox jumps over the lazy dog.",
                agent = null,
                model = null,
            )
            println("[$label] send @ t=${System.currentTimeMillis() - t0}ms result=${if (send.isSuccess) "OK" else "FAIL ${send.exceptionOrNull()}"}")
            // Wait for streaming to play out.
            delay(waitMs)
            sseJob.cancel()
        }

        synchronized(lock) {
            println("[$label] === ${frames.size} frames captured ===")
            frames.forEach { println("[$label] $it") }
            // Per-type SUMMARY — the histogram is the conclusive signal.
            val byType = frames.groupBy { it.substringAfter("type=").substringBefore(' ') }
                .toSortedMap()
            println("[$label] === SUMMARY by type ===")
            byType.forEach { (t, list) ->
                println("[$label] SUMMARY type=$t count=${list.size}")
            }
            // Truth assertion: at least SOMETHING arrived over SSE (else the
            // feed never connected and the capture is inconclusive).
            assertTrue(
                "[$label] no SSE frames captured in ${waitMs}ms — feed likely never connected (inconclusive)",
                frames.isNotEmpty(),
            )
        }
    }

    @Test
    fun slim_sse_frame_capture_during_send() = runBlocking {
        assumeTrue("Skipping slim capture: no slimapiServerUrl arg", slimUrl.isNotBlank())
        capture(baseUrl = slimUrl, slim = true, label = "SLIM")
    }

    @Test
    fun legacy_sse_frame_capture_during_send() = runBlocking {
        assumeTrue("Skipping legacy capture: no openCodeServerUrl arg", legacyUrl.isNotBlank())
        capture(baseUrl = legacyUrl, slim = false, label = "LEGACY")
    }
}
