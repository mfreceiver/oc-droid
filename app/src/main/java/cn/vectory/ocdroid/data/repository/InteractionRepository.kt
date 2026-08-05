package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.PermissionResponse
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SlimapiPermissionEntry
import cn.vectory.ocdroid.data.model.SlimapiQuestionEntry

/** Phase B narrow seam: write interactions (send/abort/summarize/fork/revert/permission/question). */
interface InteractionRepository {
    suspend fun sendMessage(
        sessionId: String,
        text: String,
        agent: String? = null,
        model: Message.ModelInfo? = null,
        attachments: List<ComposerImageAttachment> = emptyList(),
    ): Result<Unit>
    suspend fun abortSession(sessionId: String): Result<Unit>
    suspend fun summarizeSession(sessionId: String, model: Message.ModelInfo): Result<Boolean>
    suspend fun forkSession(sessionId: String, messageId: String? = null): Result<Session>
    suspend fun revertSession(sessionId: String, messageId: String, partId: String? = null): Result<Session>
    suspend fun getPendingPermissions(): Result<List<PermissionRequest>>
    suspend fun respondPermission(sessionId: String, permissionId: String, response: PermissionResponse): Result<Unit>
    suspend fun getPendingQuestions(directory: String?): Result<List<QuestionRequest>>
    suspend fun replyQuestion(requestId: String, answers: List<List<String>>, directory: String?): Result<Unit>
    suspend fun rejectQuestion(requestId: String, directory: String?): Result<Unit>
    suspend fun executeCommand(
        sessionId: String,
        command: String,
        arguments: String = "",
        agent: String? = null,
        directory: String?,
    ): Result<Unit>
    suspend fun getSlimapiQuestions(
        directories: List<String>? = null,
    ): Result<SlimAggregationOutcome<SlimapiQuestionEntry>>
    suspend fun getSlimapiPermissions(
        directories: List<String>? = null,
    ): Result<SlimAggregationOutcome<SlimapiPermissionEntry>>
    suspend fun replySlimapiQuestion(
        questionId: String,
        answers: List<List<String>>,
        routeToken: String?,
        directory: String? = null,
    ): Result<Unit>
    suspend fun rejectSlimapiQuestion(
        questionId: String,
        routeToken: String?,
        directory: String? = null,
    ): Result<Unit>
    suspend fun respondSlimapiPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        routeToken: String?,
    ): Result<Unit>

    /**
     * §rev-ds ISSUE 2: whether this connection supports a single global
     * `/question` fetch that aggregates questions across ALL workdirs (slim
     * sidecar feature). When true, callers issue ONE `getPendingQuestions(null)`
     * call; when false, callers must fan-out per workdir (legacy behavior).
     *
     * Wired to [cn.vectory.ocdroid.data.repository.gateway.ConnectionGateway.slimConnection]
     * via the same capability flag that [usesSlimStatusFanOut] and [supportsBulkSessionTree]
     * use — global question aggregation is a slim sidecar feature, independent
     * of status-endpoint support.
     */
    val supportsGlobalQuestionFetch: Boolean

    /**
     * §slimapi-questions: whether the connected oc-slimapi sidecar serves the
     * cross-directory `GET /slimapi/questions` endpoint. ANDed with
     * [supportsGlobalQuestionFetch] at the reconcile call sites to decide
     * endpoint-vs-per-dir-fan-out. Fail-open default `true`; sticky-false on
     * the first observed 404 from an older sidecar.
     */
    val supportsSlimQuestions: Boolean
}
