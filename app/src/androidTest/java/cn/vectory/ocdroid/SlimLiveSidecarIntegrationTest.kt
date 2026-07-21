package cn.vectory.ocdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.SlimAggregationOutcome
import cn.vectory.ocdroid.data.repository.StatusOutcome
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * POST-RELEASE instrumentation (slimapi-client-v1 subsystem): live integration
 * tests against the oc-slimapi sidecar running on the dev host.
 *
 * **Sidecar facts (verified by live probes during this session)**:
 *  - URL: `http://10.0.2.2:4097` from emulator (=`http://127.0.0.1:4097` from host). UP, healthy.
 *  - `X-Slimapi-Version: 1` header REQUIRED on every `/slimapi/` call
 *    (without it → HTTP 400 `{"code":"version_required","accepted":[1,1]}`).
 *  - Injected by `SlimapiVersionInterceptor` on the shared OkHttp base chain
 *    (gated by `HostConfig.slim == true` AND `/slimapi/` path prefix).
 *  - `GET /slimapi/sessions` with null `?directory` → 200 OK (returns all aggregated sessions).
 *  - **`GET /slimapi/questions` + `GET /slimapi/permissions`**: `?directory` is optional
 *    (F1 / oc-slimapi v0.2.2+). Null = aggregate all scope dirs the sidecar fronts for
 *    this client → HTTP 200 envelope `{items, errors, scope:{directories:N}}`
 *    (`Success`/`Partial`; live probe saw N=21). The client
 *    (`OpenCodeApi.getSlimapiQuestions`/`getSlimapiPermissions`) declares
 *    `directory: List<String>? = null` with KDoc "null = all" — now aligned with
 *    the sidecar. See the per-test notes below.
 *
 * **Test design**: robust. Does NOT depend on deep sidecar state, does NOT do
 * destructive POSTs. Each test guards with `assumeTrue` so an unreachable
 * sidecar skips the whole suite rather than failing.
 *
 * **Slim toggle**: `OpenCodeRepository` does not have a "slim toggle" parameter
 * on its public API; slim mode is enabled by `repository.configure(baseUrl=...,
 * slim = true)` which sets `HostConfig.slim = true`. The `SlimapiVersionInterceptor`
 * then injects `X-Slimapi-Version: 1` on every `/slimapi/` request (the path
 * prefix is the second gate; both must hold for header injection). So this
 * suite calls `repository.configure(baseUrl = slimapiServerUrl, slim = true)`
 * in setup; the version header is then transparently injected on every slim
 * API call below.
 *
 * **Run**: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.slimapiServerUrl=http://10.0.2.2:4097`
 *  (or via the `openCodeServerUrl` fallback for parity with [OpenCodeIntegrationTest]).
 *  Does NOT run in `./scripts/check.sh` (needs a device + live sidecar).
 *
 * **Skip list** (intentionally NOT covered here):
 *  - SSE (`/slimapi/events`) — long-lived connection; needs a streaming test harness.
 *  - POST mutations (`/slimapi/questions/{qid}/reply|reject`, `/slimapi/sessions/{sid}/permissions/{pid}`)
 *    — destructive; need pending items + careful cleanup.
 *  - The batched `/slimapi/messages/{sid}/full` endpoint — covered by unit tests
 *    against MockWebServer; not re-tested here.
 */
@RunWith(AndroidJUnit4::class)
class SlimLiveSidecarIntegrationTest {

    private lateinit var repository: OpenCodeRepository
    private var serverUrl: String = ""

    @Before
    fun setup() {
        val args = InstrumentationRegistry.getArguments()
        // Primary arg: slimapiServerUrl. Fallback: openCodeServerUrl (parity
        // with OpenCodeIntegrationTest) so the suite can run from the same
        // .env-driven invocation if needed.
        serverUrl = args.getString("slimapiServerUrl")
            ?: args.getString("openCodeServerUrl")
            ?: ""

        assumeTrue(
            "Skipping: no slimapi sidecar URL configured (slimapiServerUrl or openCodeServerUrl instrumentation arg)",
            serverUrl.isNotBlank(),
        )

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsManager = SettingsManager(ctx)
        repository = OpenCodeRepository(
            trafficTracker = TrafficTracker(settingsManager),
            trafficLogger = TrafficLogger(ctx),
        )
        // slim=true flips HostConfig.slim → SlimapiVersionInterceptor injects
        // X-Slimapi-Version: 1 on every /slimapi/ call. Without this, the
        // sidecar returns HTTP 400 version_required on every slim request.
        repository.configure(baseUrl = serverUrl, slim = true)

        assumeTrue(
            "Skipping: sidecar not reachable / not healthy at $serverUrl",
            runBlocking { repository.checkHealth().isSuccess },
        )
    }

    /**
     * `GET /slimapi/sessions` (null directory) → 200 + non-null list. Each
     * [Session] has the contract-minimum fields: id, directory, time. The
     * sidecar aggregates sessions across the opencode instances it fronts;
     * null `?directory` is the documented "all" scope at this endpoint
     * (contrast with /questions and /permissions — see those tests for the
     * mismatch).
     */
    @Test
    fun slim_sessions_list_deserializes() = runBlocking {
        val result = repository.getSlimapiSessions()
        assertTrue("getSlimapiSessions failed: ${result.exceptionOrNull()}", result.isSuccess)
        val page = result.getOrThrow()
        assertNotNull(page)
        val sessions = page.sessions
        // Each session carries the contract-minimum fields. We do not assert
        // non-empty (the sidecar might be legitimately empty in some setups);
        // we DO assert that every returned row has the three required fields.
        sessions.forEach { s ->
            assertNotNull("session.id is required", s.id)
            // directory is `val directory: String` (non-null in the model) —
            // if deserialization succeeded, it's present.
            assertTrue(
                "session.directory must be non-blank (got '${s.directory}')",
                s.directory.isNotBlank(),
            )
        }
        println("[SlimLiveSidecar] sessions count=${sessions.size}")
        println("[SlimLiveSidecar] complete=${page.complete} discoveryDirectories=${page.discoveryDirectories} discoveryReady=${page.discoveryReady}")
        sessions.take(3).forEach { s ->
            println("[SlimLiveSidecar]   id=${s.id} dir=${s.directory} title=${s.title ?: "-"}")
        }
    }

    /**
     * Version-gate rejection: a raw OkHttp call WITHOUT `X-Slimapi-Version`
     * → HTTP 400 + sidecar body `{"code":"version_required",...}`.
     *
     * **Why a raw OkHttp call**: the repository's `SlimapiVersionInterceptor`
     * (on the shared OkHttp base chain) ALWAYS injects the version header on
     * `/slimapi/` paths when `HostConfig.slim == true`. There is no public
     * way to suppress it via the repository API. To verify the negative
     * contract (sidecar rejects missing header), we use a SEPARATE bare
     * `OkHttpClient` that bypasses the interceptor chain entirely. This
     * proves the sidecar's gate is independent of the client's interceptor.
     */
    @Test
    fun version_gate_rejects_missing_header() = runBlocking {
        val bareClient = OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("$serverUrl/slimapi/sessions")
            // Deliberately NO X-Slimapi-Version header.
            .get()
            .build()
        // execute() is blocking — wrap in runBlocking (this suite is runBlocking-top-level).
        val response = bareClient.newCall(request).execute()
        response.use {
            assertEquals(
                "sidecar must reject missing X-Slimapi-Version with HTTP 400 (got ${it.code})",
                400,
                it.code,
            )
            val body = it.body?.string() ?: ""
            assertTrue(
                "sidecar 400 body must carry the version_required code (got: $body)",
                body.contains("\"code\":\"version_required\"") || body.contains("version_required"),
            )
            println("[SlimLiveSidecar] version-gate rejection body: $body")
        }
    }

    /**
     * `GET /slimapi/sessions/{sid}/status` for the first session from the
     * list → deserializes into [StatusOutcome.Success]. Skipped if no
     * sessions are available upstream.
     *
     * `getSlimapiSessionStatusOutcome` returns the boundary-normalised
     * [StatusOutcome] (Success / SessionMissing / Retry / UpstreamWarn /
     * DirectoryError). The live sidecar with a known session id is expected
     * to land in Success; we accept Retry too (transient sidecar/upstream
     * flap is acceptable for a live integration test).
     */
    @Test
    fun slim_single_session_status_deserializes() = runBlocking {
        val sessionsResult = repository.getSlimapiSessions()
        assumeTrue(
            "Skipping: no sessions upstream (getSlimapiSessions failed: ${sessionsResult.exceptionOrNull()})",
            sessionsResult.isSuccess && sessionsResult.getOrThrow().sessions.isNotEmpty(),
        )
        val sid = sessionsResult.getOrThrow().sessions.first().id

        val outcome = repository.getSlimapiSessionStatusOutcome(sid)
        println("[SlimLiveSidecar] status sid=$sid outcome=$outcome")
        assertTrue(
            "status outcome must be Success or Retry for a known session (got $outcome)",
            outcome is StatusOutcome.Success || outcome is StatusOutcome.Retry,
        )
        if (outcome is StatusOutcome.Success) {
            assertNotNull(outcome.status)
            // SessionStatus.type is the contract field ("idle" | "busy" | "retry").
            assertTrue(
                "status.type must be a known value (got '${outcome.status.type}')",
                outcome.status.type in setOf("idle", "busy", "retry"),
            )
        }
    }

    /**
     * `GET /slimapi/messages/{sid}` (skeleton mode) for the first session
     * from the list → deserializes into a list of [MessageWithParts].
     * Skeleton rows are lightweight (no parts expanded); the count may be 0
     * for a freshly-created session.
     */
    @Test
    fun slim_messages_skeleton_deserializes() = runBlocking {
        val sessionsResult = repository.getSlimapiSessions()
        assumeTrue(
            "Skipping: no sessions upstream",
            sessionsResult.isSuccess && sessionsResult.getOrThrow().sessions.isNotEmpty(),
        )
        val sid = sessionsResult.getOrThrow().sessions.first().id

        val result = repository.getSlimapiMessagesPage(
            sessionId = sid,
            limit = 50,
            before = null,
            mode = "skeleton",
            bumpBookmark = false,  // read-only instrumentation; do not mutate repository watermark
            token = repository.captureSlimCommitToken(),
        )
        assertTrue("getSlimapiMessagesPage failed: ${result.exceptionOrNull()}", result.isSuccess)
        val page = result.getOrThrow()
        assertNotNull(page.items)
        println("[SlimLiveSidecar] messages sid=$sid skeletonCount=${page.items.size} nextCursor=${page.nextCursor ?: "-"}")
        page.items.take(3).forEach { m ->
            assertNotNull("message.info.id required", m.info.id)
            // role is non-null in the model — deserialization success means it's present.
            assertNotNull("message.info.role required", m.info.role)
        }
    }

    /**
     * **F1 reconciled (oc-slimapi v0.2.2+)**: `GET /slimapi/questions` with
     * null `?directory` → HTTP 200 aggregate envelope
     * `{items, errors, scope:{directories:N}}` as `SlimAggregationOutcome.Success`
     * or `Partial` (null = all dirs the sidecar aggregates for this client).
     * Pre-F1 the sidecar returned 422 missing-directory; this pins the
     * reconciled behavior.
     *
     * Branch 2 (with-directory success path) is intentionally NOT live-run
     * here: a session's `directory` is not necessarily in the sidecar
     * allowlist → 400 `directory_not_allowed` (environment-fragile, not a
     * contract bug). With-dir success is covered by unit tests
     * (`SessionSyncCoordinatorSlimTest` / `SlimapiV1ModelsTest`).
     */
    @Test
    fun slim_questions_null_directory_aggregates() = runBlocking {
        // Branch 1 (F1 reconciled): null directory → 200 aggregate envelope
        // (Success/Partial, NOT 422). Pre-F1 the sidecar returned 422
        // missing-directory; F1 (slimapi v0.2.0+ rev B) made `?directory`
        // optional (null = all dirs the sidecar aggregates for this client).
        // This pins the reconciled behavior.
        val nullDirResult = repository.getSlimapiQuestions(
            directories = null,
            token = repository.captureSlimCommitToken(),
        )
        assertTrue(
            "null directory must succeed (F1 aggregate) — got ${nullDirResult.exceptionOrNull()}",
            nullDirResult.isSuccess,
        )
        val nullOutcome = nullDirResult.getOrThrow()
        assertTrue(
            "null-directory outcome must be Success or Partial (got $nullOutcome)",
            nullOutcome is SlimAggregationOutcome.Success || nullOutcome is SlimAggregationOutcome.Partial,
        )
        println(
            "[SlimLiveSidecar] questions null-dir outcome=$nullOutcome items=" +
                "${(nullOutcome as? SlimAggregationOutcome.Success)?.items?.size ?: (nullOutcome as? SlimAggregationOutcome.Partial)?.items?.size ?: "-"}",
        )

        // Branch 2 (with-directory success path) is covered by unit tests
        // (SessionSyncCoordinatorSlimTest / SlimapiV1ModelsTest). Live-validating
        // it here would require sourcing an allowlisted directory, but a
        // session's `directory` (e.g. /home/mar/opencode_wd) is not necessarily
        // in the sidecar's project allowlist (/home/mar/personal_projects/*) →
        // 400 directory_not_allowed (environment-fragile, not a contract bug).
        // The F1 null-dir aggregate above is the high-value live check.
    }

    /**
     * **F1 reconciled (oc-slimapi v0.2.2+)**: `GET /slimapi/permissions` with
     * null `?directory` → HTTP 200 aggregate envelope (same shape as questions:
     * `Success`/`Partial`). See [slim_questions_null_directory_aggregates] for
     * the full F1 rationale; parity with the questions path. Branch 2
     * (with-directory) is not live-exercised here for the same allowlist
     * reason — covered by unit tests.
     */
    @Test
    fun slim_permissions_null_directory_aggregates() = runBlocking {
        // Branch 1 (F1 reconciled): null directory → 200 aggregate envelope
        // (Success/Partial, NOT 422). See [slim_questions_null_directory_aggregates]
        // for the full F1 rationale; parity with the questions path.
        val nullDirResult = repository.getSlimapiPermissions(
            directories = null,
            token = repository.captureSlimCommitToken(),
        )
        assertTrue(
            "null directory must succeed (F1 aggregate) — got ${nullDirResult.exceptionOrNull()}",
            nullDirResult.isSuccess,
        )
        val nullOutcome = nullDirResult.getOrThrow()
        assertTrue(
            "null-directory outcome must be Success or Partial (got $nullOutcome)",
            nullOutcome is SlimAggregationOutcome.Success || nullOutcome is SlimAggregationOutcome.Partial,
        )
        println(
            "[SlimLiveSidecar] permissions null-dir outcome=$nullOutcome items=" +
                "${(nullOutcome as? SlimAggregationOutcome.Success)?.items?.size ?: (nullOutcome as? SlimAggregationOutcome.Partial)?.items?.size ?: "-"}",
        )

        // Branch 2 (with-directory success path) is covered by unit tests;
        // see [slim_questions_null_directory_aggregates] for why it is not
        // live-exercised here (session.directory may not be allowlisted →
        // 400 directory_not_allowed, environment-fragile, not a contract bug).
    }
}
