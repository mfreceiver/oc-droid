package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.ConfigProvider
import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.FileDiff
import cn.vectory.ocdroid.data.model.FileContent
import cn.vectory.ocdroid.data.model.FileNode
import cn.vectory.ocdroid.data.model.FileStatusEntry
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.PermissionResponse
import cn.vectory.ocdroid.data.model.ProviderModel
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.data.model.QuestionInfo
import cn.vectory.ocdroid.data.model.QuestionOption
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.http.spkiSha256Hex
import cn.vectory.ocdroid.util.TrafficTracker
import cn.vectory.ocdroid.util.TrafficLogger
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OpenCodeRepositoryTest {

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

    @Test
    fun `default server URL is localhost`() {
        assertEquals(
            "http://localhost:4096",
            OpenCodeRepository.DEFAULT_SERVER
        )
    }

    @Test
    fun `checkHealth returns success`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("""{"healthy": true, "version": "1.0.0"}""")
                .setHeader("Content-Type", "application/json")
        )

        val result = repository.checkHealth()

        assertTrue(result.isSuccess)
        val health = result.getOrThrow()
        assertTrue(health.healthy)
        assertEquals("1.0.0", health.version)
    }

    @Test
    fun `checkHealth handles network error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.checkHealth()

        assertTrue(result.isFailure)
    }

    @Test
    fun `getSessions returns list`() = runBlocking {
        val sessions = listOf(
            Session(id = "s1", directory = "/project", title = "Test")
        )
        server.enqueue(
            MockResponse()
                .setBody(json.encodeToString(sessions))
                .setHeader("Content-Type", "application/json")
        )

        val result = repository.getSessions()

        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(1, list.size)
        assertEquals("s1", list[0].id)
        assertEquals("/project", list[0].directory)
    }

    @Test
    fun `getAgents returns list`() = runBlocking {
        val agents = listOf(
            AgentInfo(
                name = "Build",
                mode = "primary",
                hidden = false
            )
        )
        server.enqueue(
            MockResponse()
                .setBody(json.encodeToString(agents))
                .setHeader("Content-Type", "application/json")
        )

        val result = repository.getAgents()

        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(1, list.size)
        assertEquals("Build", list[0].name)
    }

    @Test
    fun `createSession returns session and sends title body`() = runBlocking {
        val session = Session(id = "session-1", directory = "/project", title = "New chat")
        server.enqueue(jsonResponse(json.encodeToString(session)))

        val result = repository.createSession("New chat")

        assertTrue(result.isSuccess)
        assertEquals("session-1", result.getOrThrow().id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session", request.path)
        assertEquals("{\"title\":\"New chat\"}", request.body.readUtf8())
    }

    @Test
    fun `createSession fails on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.createSession("Broken")

        assertTrue(result.isFailure)
    }

    @Test
    fun `updateSession returns updated session and sends patch body`() = runBlocking {
        val session = Session(id = "session-1", directory = "/project", title = "Renamed")
        server.enqueue(jsonResponse(json.encodeToString(session)))

        val result = repository.updateSession("session-1", "Renamed")

        assertTrue(result.isSuccess)
        assertEquals("Renamed", result.getOrThrow().title)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/session/session-1", request.path)
        assertEquals("{\"title\":\"Renamed\"}", request.body.readUtf8())
    }

    @Test
    fun `updateSession fails on bad request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))

        val result = repository.updateSession("session-1", "Renamed")

        assertTrue(result.isFailure)
    }

    @Test
    fun `updateSessionArchived sends archived time body`() = runBlocking {
        val session = Session(
            id = "session-1",
            directory = "/project",
            time = Session.TimeInfo(archived = 1234)
        )
        server.enqueue(jsonResponse(json.encodeToString(session)))

        val result = repository.updateSessionArchived("session-1", 1234)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isArchived)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/session/session-1", request.path)
        assertEquals("{\"time\":{\"archived\":1234}}", request.body.readUtf8())
    }

    @Test
    fun `deleteSession returns success and sends delete request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.deleteSession("session-1")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/session/session-1", request.path)
    }

    @Test
    fun `getSessionStatus returns map`() = runBlocking {
        val statuses = mapOf(
            "session-1" to SessionStatus(type = "busy", attempt = 2, message = "Running", next = 123L)
        )
        server.enqueue(jsonResponse(json.encodeToString(statuses)))

        val result = repository.getSessionStatus()

        assertTrue(result.isSuccess)
        val status = result.getOrThrow().getValue("session-1")
        assertEquals("busy", status.type)
        assertEquals(2, status.attempt)
        assertEquals("Running", status.message)
    }

    @Test
    fun `getSessionStatus fails on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.getSessionStatus()

        assertTrue(result.isFailure)
    }

    @Test
    fun `getMessages returns message list and forwards limit`() = runBlocking {
        val messages = listOf(
            MessageWithParts(
                info = Message(id = "msg-1", sessionId = "session-1", role = "assistant"),
                parts = listOf(Part(id = "part-1", type = "text", text = "Hello"))
            )
        )
        server.enqueue(jsonResponse(json.encodeToString(messages)))

        val result = repository.getMessages("session-1", limit = 30)

        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(1, list.size)
        assertEquals("msg-1", list[0].info.id)
        assertEquals("Hello", list[0].parts.single().text)
        assertEquals("/session/session-1/message?limit=30", server.takeRequest().path)
    }

    @Test
    fun `getMessages fails on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.getMessages("session-1")

        assertTrue(result.isFailure)
    }

    @Test
    fun `sendMessage sends prompt body with auth header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))
        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "alice",
            password = "secret"
        )

        val result = repository.sendMessage(
            sessionId = "session-1",
            text = "hello repo",
            agent = "review",
            model = Message.ModelInfo(providerId = "openai", modelId = "gpt-4")
        )

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session/session-1/prompt_async", request.path)
        assertEquals("Basic YWxpY2U6c2VjcmV0", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"agent\":\"review\""))
        assertTrue(body.contains("\"type\":\"text\""))
        assertTrue(body.contains("\"text\":\"hello repo\""))
        assertTrue(body.contains("\"providerID\":\"openai\""))
        assertTrue(body.contains("\"modelID\":\"gpt-4\""))
    }

    @Test
    fun `sendMessage omits null model from payload`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))

        val result = repository.sendMessage(
            sessionId = "session-1",
            text = "hello without model",
            agent = "build",
            model = null
        )

        assertTrue(result.isSuccess)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"type\":\"text\""))
        assertTrue(body.contains("\"text\":\"hello without model\""))
        assertFalse(body.contains("\"model\":null"))
    }

    @Test
    fun `sendMessage surfaces status code and server error body`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("bad request body")
        )

        val result = repository.sendMessage(sessionId = "session-1", text = "hello")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("400"))
        assertTrue(result.exceptionOrNull()!!.message!!.contains("bad request body"))
    }

    @Test
    fun `abortSession returns success and sends abort request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))

        val result = repository.abortSession("session-1")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session/session-1/abort", request.path)
    }

    @Test
    fun `forkSession returns forked session and sends message id`() = runBlocking {
        val session = Session(id = "fork-1", directory = "/project", parentId = "session-1", title = "Fork")
        server.enqueue(jsonResponse(json.encodeToString(session)))

        val result = repository.forkSession("session-1", "msg-42")

        assertTrue(result.isSuccess)
        val forked = result.getOrThrow()
        assertEquals("fork-1", forked.id)
        assertEquals("session-1", forked.parentId)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session/session-1/fork", request.path)
        assertEquals("{\"messageID\":\"msg-42\"}", request.body.readUtf8())
    }

    @Test
    fun `forkSession fails on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.forkSession("session-1", "msg-42")

        assertTrue(result.isFailure)
    }

    @Test
    fun `getPendingPermissions returns permission list`() = runBlocking {
        val permissions = listOf(
            PermissionRequest(
                id = "perm-1",
                sessionId = "session-1",
                permission = "write",
                patterns = listOf("app/src/**"),
                metadata = PermissionRequest.Metadata(filepath = "app/src/Main.kt", parentDir = "app/src")
            )
        )
        server.enqueue(jsonResponse(json.encodeToString(permissions)))

        val result = repository.getPendingPermissions()

        assertTrue(result.isSuccess)
        val permission = result.getOrThrow().single()
        assertEquals("perm-1", permission.id)
        assertEquals("write", permission.permission)
        assertEquals("/permission", server.takeRequest().path)
    }

    @Test
    fun `getPendingPermissions returns empty list`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getPendingPermissions()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `respondPermission returns success and sends response body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.respondPermission("session-1", "perm-1", PermissionResponse.ALWAYS)

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session/session-1/permissions/perm-1", request.path)
        assertEquals("{\"response\":\"always\"}", request.body.readUtf8())
    }

    @Test
    fun `getPendingQuestions returns question list`() = runBlocking {
        val questions = listOf(
            QuestionRequest(
                id = "question-1",
                sessionId = "session-1",
                questions = listOf(
                    QuestionInfo(
                        question = "Which file?",
                        header = "Choose target",
                        options = listOf(QuestionOption(label = "A", description = "Option A")),
                        multiple = false,
                        custom = true
                    )
                )
            )
        )
        server.enqueue(jsonResponse(json.encodeToString(questions)))

        val result = repository.getPendingQuestions("/workdir/project")

        assertTrue(result.isSuccess)
        val question = result.getOrThrow().single()
        assertEquals("question-1", question.id)
        assertEquals("Which file?", question.questions.single().question)
        // §R18 Phase 2-E step 2: the interceptor mirrors the explicit
        // directory into ?directory for proxy-safe routing, so the path
        // starts with /question but is no longer exactly "/question".
        assertTrue(
            "path must target /question (② now appends a directory query)",
            server.takeRequest().path?.startsWith("/question") == true
        )
    }

    @Test
    fun `getPendingQuestions returns empty list`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getPendingQuestions(null)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `replyQuestion returns success and sends answers body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.replyQuestion("question-1", listOf(listOf("A"), listOf("custom", "value")), "/workdir/project")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/question/question-1/reply", request.path)
        assertEquals("{\"answers\":[[\"A\"],[\"custom\",\"value\"]]}", request.body.readUtf8())
    }

    @Test
    fun `replyQuestion surfaces status code and error body`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("reply failed")
        )

        val result = repository.replyQuestion("question-1", listOf(listOf("A")), "/workdir/project")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("500"))
        assertTrue(result.exceptionOrNull()!!.message!!.contains("reply failed"))
    }

    @Test
    fun `rejectQuestion returns success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.rejectQuestion("question-1", "/workdir/project")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/question/question-1/reject", request.path)
    }

    @Test
    fun `rejectQuestion surfaces status code and error body`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("cannot reject")
        )

        val result = repository.rejectQuestion("question-1", "/workdir/project")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("400"))
        assertTrue(result.exceptionOrNull()!!.message!!.contains("cannot reject"))
    }

    @Test
    fun `getFileContent parses text response and sends path query`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(json.encodeToString(FileContent(type = "text", content = "# Hello")))
                .setHeader("Content-Type", "application/json")
        )

        // §R-17 batch4: file API now takes an explicit `directory` parameter
        // (first positional arg); it is mirrored into the query by the
        // Skip-Dir-aware interceptor.
        val result = repository.getFileContent(directory = "/workdir/project", path = "docs/README.md")

        assertTrue(result.isSuccess)
        assertEquals("# Hello", result.getOrThrow().content)
        val request = server.takeRequest()
        assertEquals("/file/content?path=docs%2FREADME.md&directory=%2Fworkdir%2Fproject", request.path)
        // §R-17 batch4: file routes carry Skip-Dir, so X-Opencode-Directory is
        // NOT injected from the global HostConfig state — it must be absent.
        assertNull(request.getHeader("X-Opencode-Directory"))
    }

    @Test
    fun `getSessionDiff returns file diff list`() = runBlocking {
        val diffs = listOf(
            FileDiff(filePath = "app/src/Main.kt", additions = 10, deletions = 2, status = "modified")
        )
        server.enqueue(jsonResponse(json.encodeToString(diffs)))

        val result = repository.getSessionDiff("session-1")

        assertTrue(result.isSuccess)
        val diff = result.getOrThrow().single()
        assertEquals("app/src/Main.kt", diff.file)
        assertEquals(10, diff.additions)
        assertEquals("/session/session-1/diff", server.takeRequest().path)
    }

    @Test
    fun `getSessionDiff returns empty list`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getSessionDiff("session-1")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `getSessionTodos returns todo list`() = runBlocking {
        val todos = listOf(
            TodoItem(content = "Write tests", status = "pending", priority = "high", id = "todo-1")
        )
        server.enqueue(jsonResponse(json.encodeToString(todos)))

        val result = repository.getSessionTodos("session-1")

        assertTrue(result.isSuccess)
        val todo = result.getOrThrow().single()
        assertEquals("Write tests", todo.content)
        assertEquals("high", todo.priority)
        assertEquals("/session/session-1/todo", server.takeRequest().path)
    }

    @Test
    fun `getSessionTodos returns empty list`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getSessionTodos("session-1")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `getFileTree returns file nodes and sends path query`() = runBlocking {
        val nodes = listOf(
            FileNode(name = "src", path = "app/src", type = "directory"),
            FileNode(name = "Main.kt", path = "app/src/Main.kt", type = "file")
        )
        server.enqueue(jsonResponse(json.encodeToString(nodes)))

        val result = repository.getFileTree(directory = "/workdir/project", path = "app/src")

        assertTrue(result.isSuccess)
        val tree = result.getOrThrow()
        assertEquals(2, tree.size)
        assertEquals("app/src/Main.kt", tree[1].path)
        assertEquals("/file?path=app%2Fsrc&directory=%2Fworkdir%2Fproject", server.takeRequest().path)
    }

    @Test
    fun `getFileTree returns empty list`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        val result = repository.getFileTree(directory = "/workdir/project", path = "missing")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `getFileStatus returns file status list`() = runBlocking {
        val statuses = listOf(
            FileStatusEntry(path = "app/src/Main.kt", status = "M"),
            FileStatusEntry(path = "README.md", status = "??")
        )
        server.enqueue(jsonResponse(json.encodeToString(statuses)))

        val result = repository.getFileStatus(directory = "/workdir/project")

        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(2, list.size)
        assertEquals("M", list[0].status)
        // §R-17 batch4: file routes pass the explicit directory via ?query
        // (Skip-Dir marker prevents the interceptor from injecting the
        // header from the global state).
        assertTrue(
            "path must target /file/status with directory query",
            server.takeRequest().path?.startsWith("/file/status?directory=") == true
        )
    }

    @Test
    fun `getFileStatus fails on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.getFileStatus(directory = "/workdir/project")

        assertTrue(result.isFailure)
    }

    // §R-17 batch4 fix: findFile has zero production callers (API + Repository
    // wrapper exist but no UI/controller/VM uses them). Tests retained to verify
    // the API contract is correct if/when a file-search UI feature is added.
    @Test
    fun `findFile returns matches and forwards query params`() = runBlocking {
        val matches = listOf("app/src/Main.kt", "app/src/test/MainTest.kt")
        server.enqueue(jsonResponse(json.encodeToString(matches)))

        val result = repository.findFile(directory = "/workdir/project", query = "Main", limit = 2)

        assertTrue(result.isSuccess)
        assertEquals(matches, result.getOrThrow())
        assertEquals("/find/file?query=Main&limit=2&directory=%2Fworkdir%2Fproject", server.takeRequest().path)
    }

    @Test
    fun `findFile fails on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.findFile(directory = "/workdir/project", query = "Main")

        assertTrue(result.isFailure)
    }

    @Test
    fun `getProviders builds catalog from the config providers endpoint`() = runBlocking {
        // §catalog-source: getProviders() fetches GET /config/providers (the same
        // endpoint the opencode web model picker uses) and returns the parsed
        // ProvidersResponse. The `default` map is preserved (the former V2 path
        // left it empty). Provider `options.apiKey` / model `key` fields are
        // dropped by ignoreUnknownKeys (DTO has no such fields) — verified in a
        // dedicated test below.
        val body = """
            {"providers":[
              {"id":"openai","name":"OpenAI","options":{"apiKey":"sk-leak"},
               "models":{"gpt-4":{"id":"gpt-4","name":"GPT-4","providerID":"openai","limit":{"context":128000,"output":16000}}}},
              {"id":"mistral","name":"Mistral","models":{"m1":{"id":"m1","name":"M1","providerID":"mistral"}}}
            ],"default":{"openai":"gpt-4"}}
        """.trimIndent()
        server.enqueue(jsonResponse(body))

        val result = repository.getProviders()

        assertTrue(result.isSuccess)
        val catalog = result.getOrThrow()
        assertEquals(2, catalog.providers.size)
        val openai = catalog.providers[0]
        assertEquals("openai", openai.id)
        assertEquals("OpenAI", openai.name)
        assertEquals(1, openai.models.size)
        val model = openai.models["gpt-4"]
        assertEquals("gpt-4", model?.id)
        assertEquals("GPT-4", model?.name)
        assertEquals("openai", model?.resolvedProviderId)
        assertEquals(128000, model?.limit?.context)
        assertEquals(16000, model?.limit?.output)
        // §catalog-source: default restored (the V2 path left it null).
        assertNotNull(catalog.default)
        assertEquals("openai", catalog.default?.providerId)
        assertEquals("gpt-4", catalog.default?.modelId)
        // request hit /config/providers (the web picker's source).
        assertEquals("/config/providers", server.takeRequest().path)
    }

    @Test
    fun `getProviders drops providers with no models`() = runBlocking {
        // parity with the former V2 builder's groupBy: a provider whose models
        // map is empty is absent from the catalog (no empty section header in
        // the picker).
        val body = """
            {"providers":[
              {"id":"empty","name":"Empty","models":{}},
              {"id":"openai","name":"OpenAI","models":{"gpt-4":{"id":"gpt-4","name":"GPT-4","providerID":"openai"}}}
            ]}
        """.trimIndent()
        server.enqueue(jsonResponse(body))

        val result = repository.getProviders()

        assertTrue(result.isSuccess)
        val catalog = result.getOrThrow()
        assertEquals(1, catalog.providers.size)
        assertEquals("openai", catalog.providers[0].id)
    }

    @Test
    fun `getProviders ignores provider apiKey fields without capturing them`() = runBlocking {
        // §key-leak safety: /config/providers' raw body carries provider apiKey
        // values (the original reason the V2 migration happened). ConfigProvider
        // / ProviderModel have NO options/apiKey/key field + ignoreUnknownKeys,
        // so they are dropped at deserialization — the catalog parses fine and
        // holds no key material. This is what makes the revert from the V2 pair
        // safe (together with /config/providers being excluded from the OkHttp
        // disk cache — see CacheControlInterceptorTest).
        val body = """
            {"providers":[
              {"id":"openai","name":"OpenAI","options":{"apiKey":"sk-secret-123"},
               "models":{"gpt-4":{"id":"gpt-4","name":"GPT-4","providerID":"openai","key":"sk-secret-123"}}}
            ]}
        """.trimIndent()
        server.enqueue(jsonResponse(body))

        val result = repository.getProviders()

        assertTrue(result.isSuccess)
        val catalog = result.getOrThrow()
        assertEquals(1, catalog.providers.size)
        val provider = catalog.providers[0]
        assertEquals("openai", provider.id)
        assertEquals("gpt-4", provider.models["gpt-4"]?.id)
        // No field on ConfigProvider/ProviderModel can hold a key — the unknown
        // options/key fields were silently dropped at deserialization, not stored.
        assertEquals("GPT-4", provider.models["gpt-4"]?.name)
    }

    @Test
    fun `getProviders returns empty catalog instead of failure when config providers fails`() = runBlocking {
        // §last-mile defense: an HTTP failure (or non-decodable body) from
        // /config/providers must NOT propagate as Result.failure (which would
        // surface as "服务器没有可用模型" in the picker). It degrades to
        // Result.success(empty catalog) so the picker shows an empty list and
        // the next refresh retries.
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.getProviders()

        assertTrue("HTTP failure must degrade to success(empty), got $result", result.isSuccess)
        assertEquals(0, result.getOrThrow().providers.size)
    }

    @Test
    fun `getVcs parses branch info and changed-files status from v1 vcs endpoints`() = runBlocking {
        // §glm-P3/max-P3: hardcode the snake_case wire payload so the
        // `default_branch` -> defaultBranch @SerialName is genuinely pinned (an
        // encodeToString round-trip would share the annotation and pass even if
        // it were removed), and assert the request paths hit /vcs + /vcs/status.
        server.enqueue(
            MockResponse()
                .setBody("""{"branch":"main","default_branch":"master"}""")
                .setHeader("Content-Type", "application/json")
        )
        val infoResult = repository.getVcs(directory = "/workdir")
        assertTrue(infoResult.isSuccess)
        assertEquals("main", infoResult.getOrThrow().branch)
        assertEquals("master", infoResult.getOrThrow().defaultBranch)
        assertEquals("/vcs?directory=%2Fworkdir", server.takeRequest().path)

        server.enqueue(
            MockResponse()
                .setBody("""[{"file":"src/App.kt","additions":3,"deletions":1,"status":"modified"}]""")
                .setHeader("Content-Type", "application/json")
        )
        val statusResult = repository.getVcsStatus(directory = "/workdir")
        assertTrue(statusResult.isSuccess)
        val rows = statusResult.getOrThrow()
        assertEquals(1, rows.size)
        assertEquals("src/App.kt", rows[0].file)
        assertEquals(3, rows[0].additions)
        assertEquals("modified", rows[0].status)
        assertEquals("/vcs/status?directory=%2Fworkdir", server.takeRequest().path)
    }

    @Test
    fun `configure rebuilds clients for new base url and credentials`() = runBlocking {
        val replacementServer = MockWebServer()
        replacementServer.start()
        try {
            repository.configure(
                baseUrl = server.url("/").toString().trimEnd('/'),
                username = "old",
                password = "creds"
            )
            repository.configure(
                baseUrl = replacementServer.url("/").toString().trimEnd('/'),
                username = "new",
                password = "secret"
            )
            replacementServer.enqueue(
                MockResponse()
                    .setBody("""{"healthy": true, "version": "2.0.0"}""")
                    .setHeader("Content-Type", "application/json")
            )

            val result = repository.checkHealth()

            assertTrue(result.isSuccess)
            assertEquals("2.0.0", result.getOrThrow().version)
            assertEquals(0, server.requestCount)
            assertEquals("Basic bmV3OnNlY3JldA==", replacementServer.takeRequest().getHeader("Authorization"))
        } finally {
            replacementServer.shutdown()
        }
    }

    @Test
    fun `configure is thread-safe under concurrent calls`() = runBlocking {
        val replacementServer = MockWebServer()
        replacementServer.start()
        val mutex = Mutex()
        var maxConcurrent = 0
        var activeCount = 0

        try {
            replacementServer.enqueue(
                MockResponse()
                    .setBody("""{"healthy": true, "version": "3.0.0"}""")
                    .setHeader("Content-Type", "application/json")
            )

            val jobs = List(10) {
                launch {
                    mutex.withLock { activeCount++ }
                    maxConcurrent = maxOf(maxConcurrent, activeCount)
                    repository.configure(
                        baseUrl = replacementServer.url("/").toString().trimEnd('/'),
                        username = "user$it",
                        password = "pass$it"
                    )
                    activeCount--
                }
            }
            jobs.forEach { it.join() }

            val result = repository.checkHealth()
            assertTrue(result.isSuccess)
            assertEquals("3.0.0", result.getOrThrow().version)
        } finally {
            replacementServer.shutdown()
        }
    }

    // --- directory dimension (X-Opencode-Directory) ---
    //
    // §R18 Phase 2-E step 2: the global currentDirectory state was removed;
    // the directory is now passed EXPLICITLY to directory-scoped endpoints
    // (getPendingQuestions / replyQuestion / rejectQuestion / executeCommand /
    // SSE / file routes). The tests below exercise the explicit-parameter
    // path: the caller-supplied X-Opencode-Directory is preserved and mirrored
    // into ?directory by the interceptor.

    @Test
    fun `explicit directory header is injected on scoped requests`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        // §R18 Phase 2-E step 2: /question now takes an explicit `directory`
        // parameter (passed as @Header). The interceptor preserves it AND
        // mirrors it into ?directory for proxy-safe routing.
        repository.getPendingQuestions("/workdir/project")

        val request = server.takeRequest()
        assertTrue(
            "path must target /question (② now appends a directory query)",
            request.path?.startsWith("/question") == true
        )
        assertEquals("/workdir/project", request.getHeader("X-Opencode-Directory"))
        assertEquals(
            "② directory is mirrored into the query for proxy-safe routing",
            "/workdir/project",
            request.requestUrl?.queryParameter("directory")
        )
    }

    @Test
    fun `directory header is omitted when no directory is passed`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        repository.getPendingQuestions(null)

        val request = server.takeRequest()
        assertNull(request.getHeader("X-Opencode-Directory"))
    }

    @Test
    fun `skip-dir endpoints omit directory header and strip marker`() = runBlocking {
        server.enqueue(jsonResponse("[]"))

        repository.getSessions()

        val request = server.takeRequest()
        assertEquals("/session", request.path)
        assertNull("skip-dir endpoint must not carry directory header", request.getHeader("X-Opencode-Directory"))
        assertNull("skip-dir marker must be stripped before sending", request.getHeader("X-Opencode-Skip-Dir"))
    }
    @Test
    fun `activateTunnel success posts form body and returns success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = repository.activateTunnel(
            tunnelUrl = server.url("/").toString().trimEnd('/'),
            password = "tunnel-secret"
        )

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("persist_auth=off"))
        assertTrue(body.contains("pw=tunnel-secret"))
    }

    @Test
    fun `activateTunnel failure on non-200 response`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("Forbidden")
        )

        val result = repository.activateTunnel(
            tunnelUrl = server.url("/").toString().trimEnd('/'),
            password = "wrong-password"
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("403"))
    }

    @Test
    fun `activateTunnel does not carry Basic Auth header`() = runBlocking {
        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "alice",
            password = "secret"
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val result = repository.activateTunnel(
            tunnelUrl = server.url("/").toString().trimEnd('/'),
            password = "tunnel-secret"
        )

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertNull("Tunnel activation must not carry Basic Auth header", request.getHeader("Authorization"))
    }

    @Test
    fun `activateTunnel sends form-encoded content type`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        repository.activateTunnel(
            tunnelUrl = server.url("/").toString().trimEnd('/'),
            password = "tunnel-secret"
        )

        val request = server.takeRequest()
        val contentType = request.getHeader("Content-Type")
        assertNotNull(contentType)
        assertTrue(contentType!!.startsWith("application/x-www-form-urlencoded"))
    }

    @Test
    fun `explicit directory injection coexists with Basic Auth header`() = runBlocking {
        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "alice",
            password = "secret"
        )
        server.enqueue(jsonResponse("[]"))

        // §R18 Phase 2-E step 2: explicit directory parameter (was the global
        // currentDirectory before; the workdir fallback was removed).
        repository.getPendingQuestions("/workdir/project")

        val request = server.takeRequest()
        assertEquals("/workdir/project", request.getHeader("X-Opencode-Directory"))
        assertEquals("Basic YWxpY2U6c2VjcmV0", request.getHeader("Authorization"))
    }

    // --- §tofu R2: SSL / TOFU pinning behavior ---
    //
    // SslConfig / sslConfigFor / applySsl 在生产代码里是 private sealed interface，
    // 直接单元测不到；通过 HTTPS MockWebServer（自签名证书）做行为级验证，是最能
    // 防回归的方式：未来 R-18 拆分 Repository 时，只要这两条用例仍绿，就证明
    // "默认 trustAll" 没有被误重新引入。
    //
    // 双分支契约（§tofu R2：原 allowInsecure 已删，TOFU 取代）：
    //   - 默认（无 pin）→ SystemDefault TLS，仅信任系统证书，对自签名证书必须
    //     失败（SSLHandshakeException / 封装为 Result.failure）。
    //   - 预先 trust 该 endpoint 的 SPKI → TofuPinned（PinningTrustManager 校验
    //     SPKI 匹配），对自签名证书必须成功。

    /**
     * 起一个 HTTPS MockWebServer，使用内存生成的自签名证书（CN=localhost + SAN
     * localhost）。客户端用系统信任库验证时会失败；用 TOFU pin 时会通过。
     * 返回 (server, heldCertificate) — 后者用来取 SPKI 写入 tofuStore。
     * 调用方负责 `shutdown()`。
     */
    private fun startHttpsMockServer(): Pair<MockWebServer, HeldCertificate> {
        val heldCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverHandshakeCertificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCertificate)
            .build()
        val httpsServer = MockWebServer()
        httpsServer.useHttps(serverHandshakeCertificates.sslSocketFactory(), false)
        httpsServer.start()
        return httpsServer to heldCertificate
    }

    @Test
    fun `checkHealth fails over HTTPS with self-signed cert when no TOFU pin exists`() = runBlocking {
        val (httpsServer, _) = startHttpsMockServer()
        try {
            // §tofu R2: 默认 SystemDefault TLS（无 mTLS、无 TOFU pin）：仅信任系统
            // 证书，自签名 cert 必须握手失败。
            repository.configure(
                baseUrl = httpsServer.url("/").toString().trimEnd('/')
            )
            httpsServer.enqueue(
                MockResponse()
                    .setBody("""{"healthy": true, "version": "1.0.0"}""")
                    .setHeader("Content-Type", "application/json")
            )

            val result = repository.checkHealth()

            assertTrue(
                "default TLS (SystemDefault) must reject self-signed cert, got $result",
                result.isFailure
            )
            // SSLHandshakeException extends IOException；OkHttp 在握手层抛出后由
            // runSuspendCatching 包装为 Result.failure(IOException)。断言异常类型
            // 是 IOException（或其子类），不绑死到具体 SSL 实现，避免 JVM/平台差异。
            val ex = result.exceptionOrNull()
            assertNotNull(ex)
            assertTrue(
                "expected SSL/IO handshake failure, got ${ex?.javaClass?.name}: ${ex?.message}",
                ex is java.io.IOException
            )
        } finally {
            httpsServer.shutdown()
        }
    }

    @Test
    fun `checkHealth succeeds over HTTPS with self-signed cert when a TOFU pin matches`() = runBlocking {
        val (httpsServer, heldCertificate) = startHttpsMockServer()
        try {
            // §tofu R2: 预先把 server leaf 的 SPKI 信任进 tofuStore，然后 configure
            // 时 sslConfigFor(hostPort) 解析为 TofuPinned → 握手按 SPKI 匹配通过。
            val baseUrl = httpsServer.url("/").toString().trimEnd('/')
            val hostPort = cn.vectory.ocdroid.data.repository.http.hostPortFromUrl(baseUrl)
                ?: error("test baseUrl must yield a hostPort")
            val spki = heldCertificate.certificate.spkiSha256Hex()
            repository.applyTofuDecision(
                hostPort,
                cn.vectory.ocdroid.data.repository.http.TofuDecision.Trust(spki),
            )
            repository.configure(baseUrl = baseUrl, hostPort = hostPort)
            httpsServer.enqueue(
                MockResponse()
                    .setBody("""{"healthy": true, "version": "1.0.0"}""")
                    .setHeader("Content-Type", "application/json")
            )

            val result = repository.checkHealth()

            assertTrue(
                "TOFU pin match must accept self-signed cert, got $result",
                result.isSuccess
            )
            val health = result.getOrThrow()
            assertTrue(health.healthy)
            assertEquals("1.0.0", health.version)

            // 验证确实走了 HTTPS（而非被降级到 HTTP）。
            val request = httpsServer.takeRequest()
            assertEquals("GET /global/health HTTP/1.1", request.requestLine)
        } finally {
            httpsServer.shutdown()
        }
    }

    // §tofu R2 round-1 B1 regression (cgpt/opuser/groker): 真实冷启顺序是 configure()
    // 在先（无 pin → SystemDefault），trust 在握手失败【之后】。live OkHttp 客户端在
    // configure() 时按 SystemDefault 快照构建，socket factory 构建后不可变——故
    // applyTofuDecision 必须重建，否则重试仍 SystemDefault 持续失败（上面的预种测试靠
    // configure 前写 pin 掩盖了这点）。本测试在未重建的版本上失败、修复后通过。
    @Test
    fun `checkHealth recovers after TOFU trust applied POST-configure rebuild regression`() = runBlocking {
        val (httpsServer, heldCertificate) = startHttpsMockServer()
        try {
            val baseUrl = httpsServer.url("/").toString().trimEnd('/')
            val hostPort = cn.vectory.ocdroid.data.repository.http.hostPortFromUrl(baseUrl)
                ?: error("test baseUrl must yield a hostPort")
            val spki = heldCertificate.certificate.spkiSha256Hex()

            // 1. configure 在先——无 pin → SystemDefault，live 客户端按（不可变）系统信任
            //    socket factory 构建。
            repository.configure(baseUrl = baseUrl, hostPort = hostPort)

            // 2. 无 pin → 自签名握手失败。
            val before = repository.checkHealth()
            assertTrue("pre-trust checkHealth must fail on self-signed (SystemDefault), got $before", before.isFailure)

            // 3. configure 之【后】信任该 endpoint 的 SPKI（镜像真实 capture→accept 流）。
            repository.applyTofuDecision(
                hostPort,
                cn.vectory.ocdroid.data.repository.http.TofuDecision.Trust(spki),
            )

            // 4. 此时 checkHealth 必须成功——applyTofuDecision 已用 TofuPinned 重建 live
            //    客户端。未重建则失败（回归）：客户端仍 SystemDefault，握手失败，而 pin 已
            //    存不再弹窗 → 终态失败。
            httpsServer.enqueue(
                MockResponse()
                    .setBody("""{"healthy": true, "version": "1.0.0"}""")
                    .setHeader("Content-Type", "application/json")
            )
            val after = repository.checkHealth()
            assertTrue("post-trust checkHealth must succeed (TofuPinned rebuilt live client), got $after", after.isSuccess)
            assertTrue(after.getOrThrow().healthy)
        } finally {
            httpsServer.shutdown()
        }
    }
}
