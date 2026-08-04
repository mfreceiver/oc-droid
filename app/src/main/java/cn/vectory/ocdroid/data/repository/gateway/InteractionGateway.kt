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
import cn.vectory.ocdroid.data.repository.SlimAggregationOutcome
import cn.vectory.ocdroid.data.model.SlimapiPermissionEntry
import cn.vectory.ocdroid.data.model.SlimapiQuestionEntry
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.runSuspendCatching

/**
 * Gateway for write-style interaction operations: send, abort, summarize,
 * fork, permission, question.
 *
 * Zero mutable state — all reads go through [bundleProvider] every call,
 * preserving the generational-consistency invariant.
 */
internal class InteractionGateway(
    private val bundleProvider: () -> ClientBundle,
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
    ): Result<Unit> = runSuspendCatching {
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
        val response = mutationApi.promptAsync(sessionId, request)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Send failed ${response.code()}: $errorBody")
        }
    }

    suspend fun abortSession(sessionId: String): Result<Unit> =
        runSuspendCatching { mutationApi.abortSession(sessionId) }

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
        mutationApi.respondPermission(sessionId, permissionId, PermissionResponseRequest(response.value))
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

    suspend fun getSlimapiQuestions(
        directories: List<String>? = null,
        token: OpenCodeRepository.SlimCommitToken,
    ): Result<SlimAggregationOutcome<SlimapiQuestionEntry>> = runSuspendCatching {
        val dir = directories?.firstOrNull()
        val items = api.getPendingQuestions(dir).map { q ->
            SlimapiQuestionEntry(
                id = q.id, sessionId = q.sessionId,
                questions = q.questions, tool = q.tool, directory = dir,
            )
        }
        SlimAggregationOutcome.Success(
            items = items,
            authoritativeDirectories = directories?.toSet(),
            serverScope = null,
        )
    }

    suspend fun getSlimapiPermissions(
        directories: List<String>? = null,
        token: OpenCodeRepository.SlimCommitToken,
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

    suspend fun replySlimapiQuestion(
        questionId: String,
        answers: List<List<String>>,
        routeToken: String?,
    ): Result<Unit> = runSuspendCatching {
        val response = mutationApi.replyQuestion(
            questionId, QuestionReplyRequest(answers = answers), null,
        )
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reply failed ${response.code()}: $errorBody")
        }
    }

    suspend fun rejectSlimapiQuestion(
        questionId: String,
        routeToken: String?,
    ): Result<Unit> = runSuspendCatching {
        val response = mutationApi.rejectQuestion(questionId, null)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: response.message()
            throw Exception("Reject failed ${response.code()}: $errorBody")
        }
    }

    suspend fun respondSlimapiPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        routeToken: String?,
    ): Result<Unit> = runSuspendCatching {
        val resp = mutationApi.respondPermission(
            sessionId, permissionId, PermissionResponseRequest(response.value),
        )
        if (!resp.isSuccessful) {
            val errorBody = resp.errorBody()?.string() ?: resp.message()
            throw Exception("Permission respond failed ${resp.code()}: $errorBody")
        }
    }
}
