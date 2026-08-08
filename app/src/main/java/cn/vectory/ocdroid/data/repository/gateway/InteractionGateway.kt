package cn.vectory.ocdroid.data.repository.gateway

import cn.vectory.ocdroid.data.api.CommandRequest
import cn.vectory.ocdroid.data.api.ForkSessionRequest
import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.api.PermissionResponseRequest
import cn.vectory.ocdroid.data.api.PromptRequest
import cn.vectory.ocdroid.data.api.QuestionReplyRequest
import cn.vectory.ocdroid.data.api.RevertSessionRequest
import cn.vectory.ocdroid.data.api.SummarizeRequest
import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.PermissionResponse
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SlimapiAggregationError
import cn.vectory.ocdroid.data.model.SlimapiPermissionEntry
import cn.vectory.ocdroid.data.model.SlimapiQuestionEntry
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.data.repository.SlimAggregationOutcome
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.runSuspendCatching
import java.io.IOException
import kotlinx.coroutines.delay

// §transient-send-retry: prompt_async 非幂等，仅对"连接建立期失败"（请求
// 字节确定未发出）重试一次。UnknownHostException(DNS)/ConnectException(TCP
// refused) 是请求未到达服务端的确定性信号；其余（超时/SSL/reset/EOF/HTTP
// 错误）无法证明请求未落盘，盲目重试有双重 turn 风险，一律不重试。
private const val SEND_MAX_ATTEMPTS = 2
private const val SEND_RETRY_DELAY_MS = 100L

/**
 * Gateway for write-style interaction operations: send, abort, summarize,
 * fork, permission, question.
 *
 * Zero mutable state — all reads go through [bundleProvider] every call,
 * preserving the generational-consistency invariant.
 */
internal class InteractionGateway(
    private val bundleProvider: () -> ClientBundle,
    private val serverCompatProfile: ServerCompatProfile,
) {
    private val api: OpenCodeApi get() = bundleProvider().restApi
    private val mutationApi: OpenCodeApi get() = bundleProvider().mutationApi
    private val commandApi: OpenCodeApi get() = bundleProvider().commandApi

    suspend fun sendMessage(
        sessionId: String,
        text: String,
        agent: String? = null,
        model: Message.ModelInfo? = null,
        attachments: List<ComposerImageAttachment> = emptyList(),
    ): Result<Unit> {
        val parts = buildList {
            if (text.isNotBlank()) add(PromptRequest.PartInput(type = "text", text = text))
            attachments.forEach { attachment ->
                add(
                    PromptRequest.PartInput(
                        type = "file", mime = attachment.mime,
                        filename = attachment.filename, url = attachment.dataUrl,
                    )
                )
            }
        }
        val request = PromptRequest(
            parts = parts,
            agent = agent,
            model = model?.let { PromptRequest.ModelInput(it.providerId, it.modelId) }
        )
        // §transient-send-retry: prompt_async 非幂等，仅对"连接建立期失败"（请求
        // 字节确定未发出）重试一次。UnknownHostException(DNS)/ConnectException(TCP
        // refused) 是请求未到达服务端的确定性信号；其余（超时/SSL/reset/EOF/HTTP
        // 错误）无法证明请求未落盘，盲目重试有双重 turn 风险，一律不重试。
        var lastOutcome: Result<Unit>? = null
        for (attempt in 0 until SEND_MAX_ATTEMPTS) {
            lastOutcome = runSuspendCatching { doPromptAsync(sessionId, request) }
            if (lastOutcome.isSuccess) return lastOutcome
            val err = lastOutcome.exceptionOrNull()
            val connectPhase = err is java.net.UnknownHostException || err is java.net.ConnectException
            if (!connectPhase || attempt == SEND_MAX_ATTEMPTS - 1) return lastOutcome
            delay(SEND_RETRY_DELAY_MS)
        }
        return lastOutcome ?: Result.failure(Exception("send: no attempt ran"))
    }

    private suspend fun doPromptAsync(sessionId: String, request: PromptRequest) {
        val response = mutationApi.promptAsync(sessionId, request)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Send failed ${response.code()}: $errorBody")
        }
    }

    suspend fun abortSession(sessionId: String): Result<Unit> =
        runSuspendCatching {
            val response = mutationApi.abortSession(sessionId)
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string() ?: response.message()
                throw Exception("Abort failed ${response.code()}: $errorBody")
            }
        }

    suspend fun summarizeSession(
        sessionId: String,
        model: Message.ModelInfo,
    ): Result<Boolean> = runSuspendCatching {
        val response = mutationApi.summarizeSession(sessionId, SummarizeRequest(model.providerId, model.modelId))
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Summarize failed ${response.code()}: $errorBody")
        }
        val accepted = response.body() ?: true
        if (!accepted) {
            throw OpenCodeRepository.SummarizeServerRejectedException()
        }
        accepted
    }

    suspend fun forkSession(sessionId: String, messageId: String? = null): Result<Session> =
        runSuspendCatching { mutationApi.forkSession(sessionId, ForkSessionRequest(messageId)) }

    suspend fun revertSession(sessionId: String, messageId: String, partId: String? = null): Result<Session> =
        runSuspendCatching { mutationApi.revertSession(sessionId, RevertSessionRequest(messageId, partId)) }

    suspend fun getPendingPermissions(): Result<List<PermissionRequest>> =
        runSuspendCatching { api.getPendingPermissions() }

    suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
    ): Result<Unit> = runSuspendCatching {
        val resp = mutationApi.respondPermission(sessionId, permissionId, PermissionResponseRequest(response.value))
        // F4a micro-fix: align with the (now-collapsed) slim variant — check
        // isSuccessful so a failed respond no longer silently removes the
        // pending chip (legacy path was silently swallowing HTTP errors).
        if (!resp.isSuccessful) {
            val errorBody = resp.errorBody()?.string() ?: resp.message()
            throw Exception("Permission respond failed ${resp.code()}: $errorBody")
        }
    }

    suspend fun getPendingQuestions(directory: String?): Result<List<QuestionRequest>> =
        runSuspendCatching { api.getPendingQuestions(directory) }

    suspend fun replyQuestion(
        requestId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Result<Unit> = runSuspendCatching {
        val response = mutationApi.replyQuestion(requestId, QuestionReplyRequest(answers), directory)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reply failed ${response.code()}: $errorBody")
        }
    }

    suspend fun rejectQuestion(requestId: String, directory: String?): Result<Unit> = runSuspendCatching {
        val response = mutationApi.rejectQuestion(requestId, directory)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reject failed ${response.code()}: $errorBody")
        }
    }

    suspend fun executeCommand(
        sessionId: String,
        command: String,
        arguments: String = "",
        agent: String? = null,
        directory: String?,
    ): Result<Unit> = runSuspendCatching {
        val response = commandApi.executeCommand(
            sessionId,
            CommandRequest(command = command, arguments = arguments, agent = agent),
            directory,
        )
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Command failed ${response.code()}: $errorBody")
        }
    }

    /**
     * §slimapi-questions: cross-directory pending-questions aggregate via
     * `GET /slimapi/questions` (oc-slimapi thin route).
     *
     * **Bug fix** (cold-start multi-workdir visibility): the previous impl
     * forwarded to legacy `api.getPendingQuestions(dir)` which (with
     * `directory = null`) upstream resolves to `process.cwd()` — silently
     * hiding pending questions belonging to any other workdir. This impl
     * hits the sidecar's cross-directory aggregate so cold-start sees them
     * all (each [SlimapiQuestionEntry] carries its own `directory`).
     *
     * **Capability gate**: only called when [ServerCompatProfile.slimConnection]
     * AND [ServerCompatProfile.supportsSlimQuestions] are both true. Behaviour:
     *  - HTTP 200 → mark supported; map the envelope to a typed
     *    [SlimAggregationOutcome] (Success when `errors.isEmpty()`, Partial
     *    otherwise). `authoritativeDirectories == null` → globally authoritative.
     *  - HTTP 404 (`thin_route_not_found`) → mark unsupported (sticky-false)
     *    AND throw so the call site's `Result.failure` path triggers — the next
     *    reconcile cycle takes the per-dir fan-out branch. No silent fallback
     *    here (the call site owns the fan-out since it knows the workdir set).
     *  - HTTP 5xx / other / transport → throw (transient — do NOT flip the bit,
     *    do NOT fall back; the call site keeps local).
     *
     * The `directories` param is retained for signature compatibility with
     * the existing interface/forwarder but is intentionally unused — the
     * sidecar aggregates across its whole configured allowlist regardless.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun getSlimapiQuestions(
        directories: List<String>? = null,
    ): Result<SlimAggregationOutcome<SlimapiQuestionEntry>> = runSuspendCatching {
        if (!serverCompatProfile.slimConnection || !serverCompatProfile.supportsSlimQuestions) {
            // Defensive: should never be called with the gate closed, but if it
            // is, surface a deterministic failure rather than a network probe.
            throw IOException("slimapi /slimapi/questions gate closed (slim=${serverCompatProfile.slimConnection} bit=${serverCompatProfile.supportsSlimQuestions})")
        }
        val resp = api.getSlimapiQuestions()
        if (resp.isSuccessful) {
            serverCompatProfile.markSlimQuestionsSupported()
            val body = resp.body() ?: throw IOException("slimapi /slimapi/questions 200 with null body")
            if (body.errors.isEmpty()) {
                SlimAggregationOutcome.Success(
                    items = body.items,
                    // §rev-gpt #2 / §rev-opus: passthrough the server's scope
                    // (readiness) and authoritative-directory set verbatim.
                    // Hardcoding null here would (a) re-introduce the N==0
                    // false-clear (sidecar allowlist not ready → empty items
                    // wrongly treated as globally-authoritative → stale
                    // pending state wiped) and (b) drop a server hint that the
                    // response covers only a subset of dirs. Symmetric with the
                    // Partial branch below + the permissions envelope handling.
                    authoritativeDirectories = body.authoritativeDirectories?.toSet(),
                    serverScope = body.scope,
                )
            } else {
                SlimAggregationOutcome.Partial(
                    items = body.items,
                    errors = body.errors.map { wire ->
                        SlimapiAggregationError(directory = wire.directory, code = wire.code)
                    },
                    authoritativeDirectories = body.authoritativeDirectories?.toSet() ?: emptySet(),
                    serverScope = body.scope,
                )
            }
        } else if (resp.code() == 404) {
            // Old sidecar without the route — sticky-flip the bit so subsequent
            // cycles fan out per-dir. Throw so this call's Result.failure path
            // fires (the controller keeps local pending state on failure).
            serverCompatProfile.markSlimQuestionsUnsupported()
            DebugLog.w("InteractionGateway", "slimapi /slimapi/questions 404 (old sidecar) → mark unsupported + throw for per-dir fan-out")
            throw IOException("slimapi /slimapi/questions not supported (404)")
        } else {
            // 5xx / transport / other — transient; do NOT flip the bit.
            throw IOException("slimapi /slimapi/questions HTTP ${resp.code()}")
        }
    }

    suspend fun getSlimapiPermissions(
        directories: List<String>? = null,
    ): Result<SlimAggregationOutcome<SlimapiPermissionEntry>> = runSuspendCatching {
        val items = api.getPendingPermissions().map { p ->
            SlimapiPermissionEntry(
                id = p.id, sessionId = p.sessionId,
                permission = p.permission, patterns = p.patterns,
                metadata = p.metadata, always = p.always,
                tool = p.tool, directory = null,
            )
        }
        SlimAggregationOutcome.Success(
            items = items,
            authoritativeDirectories = directories?.toSet(),
            serverScope = null,
        )
    }

    // F4a: replySlimapiQuestion / rejectSlimapiQuestion / respondSlimapiPermission
    // were identity-equivalent to the legacy methods (routeToken unused on wire
    // per upstream spec §7:231). Collapsed into the legacy variants above.
}
