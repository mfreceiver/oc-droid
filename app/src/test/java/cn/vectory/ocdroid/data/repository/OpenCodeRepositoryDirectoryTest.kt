package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-18 Phase 5 coverage: success-path behaviour for the [OpenCodeRepository]
 * methods that are NOT exercised by the legacy `OpenCodeRepositoryTest` in
 * the test root — specifically the Phase 2-E directory-parameter transmission
 * surface plus a handful of suspend endpoints whose happy/error paths are
 * cheap to drive through MockWebServer.
 *
 * Coverage focus:
 *  - `getSessionsForDirectory` — `?directory` + `?roots=true` query mirror
 *    (Phase 2-E explicit-directory over the legacy session list).
 *  - `executeCommand` — `@Header(X-Opencode-Directory)` transparent pass-through
 *    (Phase 2-E step 1).
 *  - `getFileTreeForDirectory` — `@Header(X-Opencode-Directory)` on the
 *    browse picker variant of the file route (Phase 2-E step 2).
 *  - `summarizeSession` / `revertSession` / `probeLatestMessageId` /
 *    `getMessagesPaged` / `getChildren` / `getCommands` / `getModels` /
 *    `getSession` — easy success paths that exercise the surrounding
 *    runSuspendCatching plumbing.
 *
 * All tests build the repository the same way the legacy test does:
 * `OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))` and a
 * MockWebServer instance. We assert the resulting Request path / headers /
 * body so the wire-level contract (URL shape, body serialisation, directory
 * header transmission) is pinned — not just the decoded Kotlin value.
 */
class OpenCodeRepositoryDirectoryTest {

    private val server = MockWebServer()
    private lateinit var repository: OpenCodeRepository

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setBody(body)
            .setHeader("Content-Type", "application/json")

    @Before
    fun setup() = runBlocking {
        server.start()
        repository = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        repository.configure(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    // ---- Phase 2-E directory-parameter transmission ----

    @Test
    fun `getSessionsForDirectory forwards directory and roots=true query`() = runBlocking {
        val sessions = listOf(
            Session(id = "s1", directory = "/workdir/project", title = "T1")
        )
        server.enqueue(jsonResponse(json.encodeToString(sessions)))

        val result = repository.getSessionsForDirectory("/workdir/project", limit = 25)

        assertTrue(result.isSuccess)
        assertEquals("s1", result.getOrThrow().single().id)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        // directory is passed via @Query on the API method (not via @Header),
        // so the interceptor does NOT add a second ?directory — it only
        // mirrors a header-supplied directory. roots=true is also @Query.
        // The Skip-Dir marker on getSessions is stripped by the interceptor.
        // Query order is the declaration order of the @Query params on
        // OpenCodeApi.getSessions (limit, directory, roots) — Kotlin named
        // arguments at the call site do NOT reorder URL params.
        assertEquals(
            "/session?limit=25&directory=%2Fworkdir%2Fproject&roots=true",
            request.path
        )
        assertNull("Skip-Dir marker stripped", request.getHeader("X-Opencode-Skip-Dir"))
        assertNull(
            "directory comes from @Query, no @Header to mirror",
            request.getHeader("X-Opencode-Directory")
        )
    }

    @Test
    fun `getSessionsForDirectory without limit omits limit query`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getSessionsForDirectory("/workdir")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals(
            "/session?directory=%2Fworkdir&roots=true",
            request.path
        )
    }

    @Test
    fun `executeCommand passes directory via X-Opencode-Directory header on POST`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.executeCommand(
            sessionId = "session-1",
            command = "/init",
            arguments = "",
            agent = null,
            directory = "/workdir/project"
        )

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session/session-1/command", request.path)
        // Phase 2-E: directory is carried via @Header — POST does NOT
        // additionally mirror into the query (only GET/HEAD do).
        assertEquals(
            "directory MUST be transmitted via header",
            "/workdir/project",
            request.getHeader("X-Opencode-Directory")
        )
        assertNull(
            "POST must not mirror directory into query",
            request.requestUrl?.queryParameter("directory")
        )
        // Body shape: {command, arguments, agent omitted when null}
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"command\":\"/init\""))
        assertTrue(body.contains("\"arguments\":\"\""))
        assertFalse("null agent must be omitted", body.contains("\"agent\""))
    }

    @Test
    fun `sendMessage omits null agent and null model from prompt body (server default)`() = runBlocking {
        // §agent-default: 钉死核心契约——agent=null 时 PromptRequest 经 repo 的
        // Json{explicitNulls=false} 省略 agent 字段，让服务端用其默认 agent。
        server.enqueue(MockResponse().setResponseCode(202))

        val result = repository.sendMessage(
            sessionId = "session-1", text = "hi", agent = null, model = null
        )

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session/session-1/prompt_async", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"text\":\"hi\""))
        assertFalse("null agent must be omitted (server default)", body.contains("\"agent\""))
        assertFalse("null model must be omitted", body.contains("\"model\""))
    }

    @Test
    fun `executeCommand with null directory omits header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.executeCommand(
            sessionId = "session-1",
            command = "/compact",
            arguments = "",
            agent = "build",
            directory = null
        )

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertNull(
            "null directory must not produce a header",
            request.getHeader("X-Opencode-Directory")
        )
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"agent\":\"build\""))
    }

    @Test
    fun `executeCommand surfaces HTTP failure with error body`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("unknown command")
        )

        val result = repository.executeCommand(
            sessionId = "session-1",
            command = "/bogus",
            arguments = "",
            agent = null,
            directory = "/workdir"
        )

        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue("status code surfaced: $msg", msg.contains("400"))
        assertTrue("error body surfaced: $msg", msg.contains("unknown command"))
    }

    @Test
    fun `getFileTreeForDirectory passes browse target via X-Opencode-Directory header`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getFileTreeForDirectory(directory = "/browse/target", path = "src")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(
            "/browse/target",
            request.getHeader("X-Opencode-Directory")
        )
        // Skip-Dir marker is stripped but the caller-supplied directory is
        // mirrored into ?directory by the interceptor (GET mirror path).
        assertEquals(
            "browse-target directory mirrored into ?directory",
            "/browse/target",
            request.requestUrl?.queryParameter("directory")
        )
        assertEquals("src", request.requestUrl?.queryParameter("path"))
        assertNull("Skip-Dir marker stripped", request.getHeader("X-Opencode-Skip-Dir"))
    }

    @Test
    fun `getFileTreeForDirectory defaults path to empty string`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getFileTreeForDirectory(directory = "/browse")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        // path defaults to "" via `path ?: ""` in the repository method.
        assertEquals("", request.requestUrl?.queryParameter("path"))
    }

    // ---- success-path coverage for untested suspend endpoints ----

    @Test
    fun `getSession returns single session by id`() = runBlocking {
        val session = Session(id = "s1", directory = "/workdir", title = "T")
        server.enqueue(jsonResponse(json.encodeToString(session)))

        val result = repository.getSession("s1")

        assertTrue(result.isSuccess)
        assertEquals("s1", result.getOrThrow().id)
        val request = server.takeRequest()
        assertEquals("/session/s1", request.path)
    }

    @Test
    fun `getChildren returns child sessions list`() = runBlocking {
        val children = listOf(
            Session(id = "child-1", directory = "/workdir", parentId = "parent-1"),
            Session(id = "child-2", directory = "/workdir", parentId = "parent-1")
        )
        server.enqueue(jsonResponse(json.encodeToString(children)))

        val result = repository.getChildren("parent-1")

        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(2, list.size)
        assertEquals("child-1", list[0].id)
        assertEquals("parent-1", list[0].parentId)

        val request = server.takeRequest()
        assertEquals("/session/parent-1/children", request.path)
        assertNull("Skip-Dir marker stripped", request.getHeader("X-Opencode-Skip-Dir"))
    }

    @Test
    fun `getCommands returns command list`() = runBlocking {
        // The CommandInfo hints field is JsonElement-typed; pin both the
        // array form (current server) and the absence of hints. The
        // ${'$'} escape is needed because Kotlin interpolates $ even in
        // raw strings — and the JSON literal literally contains "$ARGUMENTS".
        val body = """[
            {"name":"init","description":"init project","hints":["${'$'}ARGUMENTS"]},
            {"name":"clear","description":"clear screen"}
        ]""".trimIndent()
        server.enqueue(jsonResponse(body))

        val result = repository.getCommands()

        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(2, list.size)
        assertEquals("init", list[0].name)
        assertEquals(listOf("${'$'}ARGUMENTS"), list[0].hintsAsStringList)
        assertNull(list[1].hintsAsStringList)

        val request = server.takeRequest()
        assertEquals("/command", request.path)
    }

    @Test
    fun `getModels returns models from v2 endpoint`() = runBlocking {
        // v2 endpoint lives under /api/model on the same OkHttp chain.
        // The location echo on the response is dropped by ignoreUnknownKeys.
        val body = """{
            "location":{"directory":"/anywhere"},
            "data":[
                {"id":"gpt-4","providerID":"openai","name":"GPT-4","enabled":true},
                {"id":"claude","providerID":"anthropic","name":"Claude","enabled":false}
            ]
        }""".trimIndent()
        server.enqueue(jsonResponse(body))

        val result = repository.getModels()

        assertTrue(result.isSuccess)
        val models = result.getOrThrow()
        assertEquals(2, models.size)
        assertEquals("gpt-4", models[0].id)
        assertEquals("openai", models[0].providerId)
        assertEquals("GPT-4", models[0].name)
        assertTrue(models[0].enabled)
        assertFalse(models[1].enabled)

        val request = server.takeRequest()
        assertEquals(
            "v2 endpoint is rooted at <baseUrl>/api/",
            "/api/model",
            request.path
        )
    }

    @Test
    fun `summarizeSession sends model body and returns true on success`() = runBlocking {
        server.enqueue(jsonResponse("true"))

        val result = repository.summarizeSession(
            sessionId = "session-1",
            model = Message.ModelInfo(providerId = "openai", modelId = "gpt-4")
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session/session-1/summarize", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"providerID\":\"openai\""))
        assertTrue(body.contains("\"modelID\":\"gpt-4\""))
    }

    @Test
    fun `summarizeSession throws when server returns false`() = runBlocking {
        server.enqueue(jsonResponse("false"))

        val result = repository.summarizeSession(
            sessionId = "session-1",
            model = Message.ModelInfo(providerId = "openai", modelId = "gpt-4")
        )

        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue("decline reason surfaced: $msg", msg.contains("false"))
    }

    @Test
    fun `summarizeSession returns true when body is null`() = runBlocking {
        // HTTP 204 no content — body() == null → repository substitutes true.
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.summarizeSession(
            sessionId = "session-1",
            model = Message.ModelInfo(providerId = "openai", modelId = "gpt-4")
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
    }

    @Test
    fun `summarizeSession surfaces HTTP failure with error body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = repository.summarizeSession(
            sessionId = "session-1",
            model = Message.ModelInfo(providerId = "openai", modelId = "gpt-4")
        )

        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue(msg.contains("500"))
        assertTrue(msg.contains("boom"))
    }

    @Test
    fun `revertSession sends messageID and partID body and returns session`() = runBlocking {
        val session = Session(
            id = "session-1",
            directory = "/workdir",
            title = "Reverted",
            revert = Session.RevertInfo(messageId = "msg-1", partId = "part-1")
        )
        server.enqueue(jsonResponse(json.encodeToString(session)))

        val result = repository.revertSession("session-1", messageId = "msg-1", partId = "part-1")

        assertTrue(result.isSuccess)
        assertEquals("session-1", result.getOrThrow().id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session/session-1/revert", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"messageID\":\"msg-1\""))
        assertTrue(body.contains("\"partID\":\"part-1\""))
    }

    @Test
    fun `revertSession with null partId omits partID from body`() = runBlocking {
        val session = Session(id = "session-1", directory = "/workdir")
        server.enqueue(jsonResponse(json.encodeToString(session)))

        val result = repository.revertSession("session-1", messageId = "msg-1", partId = null)

        assertTrue(result.isSuccess)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"messageID\":\"msg-1\""))
        assertFalse("null partId must be omitted", body.contains("\"partID\""))
    }

    @Test
    fun `probeLatestMessageId returns id of newest message`() = runBlocking {
        val messages = listOf(
            MessageWithParts(
                info = Message(id = "msg-newest", sessionId = "session-1", role = "assistant"),
                parts = emptyList()
            )
        )
        server.enqueue(jsonResponse(json.encodeToString(messages)))

        val result = repository.probeLatestMessageId("session-1")

        assertTrue(result.isSuccess)
        assertEquals("msg-newest", result.getOrThrow())
        val request = server.takeRequest()
        assertEquals("/session/session-1/message?limit=1", request.path)
    }

    @Test
    fun `probeLatestMessageId returns null when list is empty`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.probeLatestMessageId("session-1")

        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
    }

    @Test
    fun `getMessagesPaged reads X-Next-Cursor header as nextCursor`() = runBlocking {
        val messages = listOf(
            MessageWithParts(
                info = Message(id = "m1", sessionId = "session-1", role = "user"),
                parts = listOf(Part(id = "p1", type = "text", text = "hi"))
            )
        )
        server.enqueue(
            jsonResponse(json.encodeToString(messages))
                .setHeader("X-Next-Cursor", "cursor-abc")
        )

        val result = repository.getMessagesPaged("session-1", limit = 50, before = null)

        assertTrue(result.isSuccess)
        val page = result.getOrThrow()
        assertEquals(1, page.items.size)
        assertEquals("m1", page.items[0].info.id)
        assertEquals("cursor-abc", page.nextCursor)

        val request = server.takeRequest()
        assertEquals("/session/session-1/message?limit=50", request.path)
    }

    @Test
    fun `getMessagesPaged forwards before cursor as query param`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getMessagesPaged(
            sessionId = "session-1",
            limit = 20,
            before = "older-cursor"
        )

        assertTrue(result.isSuccess)
        // No X-Next-Cursor header → nextCursor is null
        assertNull(result.getOrThrow().nextCursor)

        val request = server.takeRequest()
        assertEquals(
            "/session/session-1/message?limit=20&before=older-cursor",
            request.path
        )
    }

    @Test
    fun `getMessages surfaces HTTP failure as IOException`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.getMessages("session-1")

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            "non-2xx must surface IOException, got ${ex?.javaClass?.name}",
            ex is java.io.IOException
        )
    }

    // ---- checkHealthFor: independent per-profile probe (no configure mutation) ----

    @Test
    fun `checkHealthFor probes target url without mutating repository config`() = runBlocking {
        // The repository was configure()d to point at `server` in setup().
        // checkHealthFor must build a throwaway client against a DIFFERENT
        // server and NOT touch the repository's hostConfig.
        val other = MockWebServer()
        other.start()
        try {
            other.enqueue(
                MockResponse()
                    .setBody("""{"healthy": true, "version": "9.9.9"}""")
                    .setHeader("Content-Type", "application/json")
            )

            val result = repository.checkHealthFor(
                baseUrl = other.url("/").toString().trimEnd('/'),
                username = null,
                password = null,
                hostPort = null
            )

            assertTrue(result.isSuccess)
            assertEquals("9.9.9", result.getOrThrow().version)

            // The probe MUST have gone to `other`, not `server`.
            val probed = other.takeRequest()
            assertEquals("/global/health", probed.path)
            assertEquals(0, server.requestCount)

            // The probe carries Skip-Dir (the repository builder sets it).
            assertNotNull(probed.getHeader("X-Opencode-Skip-Dir"))
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `checkHealthFor sends Basic Auth when credentials are non-blank`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("""{"healthy": true, "version": "1.0.0"}""")
                .setHeader("Content-Type", "application/json")
        )

        val result = repository.checkHealthFor(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "alice",
            password = "secret",
            hostPort = null
        )

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals(
            "Basic YWxpY2U6c2VjcmV0",
            request.getHeader("Authorization")
        )
    }

    @Test
    fun `checkHealthFor omits Basic Auth when either credential is blank`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("""{"healthy": true, "version": "1.0.0"}""")
                .setHeader("Content-Type", "application/json")
        )

        repository.checkHealthFor(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "",   // blank
            password = "secret",
            hostPort = null
        )

        val request = server.takeRequest()
        assertNull(
            "blank username must skip Basic Auth",
            request.getHeader("Authorization")
        )
    }

    @Test
    fun `checkHealthFor surfaces HTTP failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = repository.checkHealthFor(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = null,
            password = null,
            hostPort = null
        )

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("503")
        )
    }

    @Test
    fun `checkHealthFor surfaces empty body as failure`() = runBlocking {
        server.enqueue(MockResponse().setBody("").setResponseCode(200))

        val result = repository.checkHealthFor(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = null,
            password = null,
            hostPort = null
        )

        assertTrue(result.isFailure)
    }

    // ---- config companion mirror ----

    @Test
    fun `repository DEFAULT_SERVER mirrors HostConfig DEFAULT_SERVER`() {
        // Locked by the legacy test too; pin here so this suite is
        // self-contained when refactoring HostConfig.
        assertEquals(HostConfig.DEFAULT_SERVER, OpenCodeRepository.DEFAULT_SERVER)
    }
}

/**
 * Standalone Hilt-free factory for tests that want to construct the holder
 * outside the repository. Kept at the bottom of the file so the test class
 * above stays the focal point. Currently unused by the suite above (which
 * uses mockk(relaxed=true) for TrafficTracker/TrafficLogger), but reserved
 * for future tests that need to inspect traffic counters.
 */
@Suppress("unused")
private object TestRepositoryFactory {
    fun newRepo(
        tracker: TrafficTracker = mockk(relaxed = true),
        logger: TrafficLogger = mockk(relaxed = true)
    ): OpenCodeRepository = OpenCodeRepository(tracker, logger)
}
