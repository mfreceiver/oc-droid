package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.MessageWithParts

/** Phase B narrow seam: message load / expand / probe / skeleton. Implemented by [OpenCodeRepository].
 *
 *  §B3-retirement: the slim-token shim and the 4 shim methods have been fully removed (Phase 4b).
 *  ConnectionCapture (captureConnection / isConnectionCaptureCurrent / commitIfConnectionCaptureCurrent)
 *  is the replacement for stale-response protection. */
interface MessageRepository {
    suspend fun getMessages(sessionId: String, limit: Int? = null): Result<List<MessageWithParts>>
    suspend fun getMessagesPaged(
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
    ): Result<MessagesPage>
    suspend fun getMessagesPagedUnanchored(
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
    ): Result<MessagesPage>
    suspend fun probeLatestMessageId(sessionId: String): Result<String?>
    suspend fun probeLatestMessageIdForCurrent(sessionId: String): ProbeResult
    suspend fun probeLatestSlim(sessionId: String): ProbeResult
    suspend fun expandMessagesFullBatch(
        sessionId: String,
        messageIds: Set<String>,
    ): ExpandOutcome
    suspend fun getSlimapiMessagesSkeleton(sessionId: String, limit: Int, before: String? = null): MessagesPage
    suspend fun getSlimapiMessageFull(sessionId: String, messageId: String): Result<MessageWithParts>
    suspend fun getSlimapiMessagesPage(
        sessionId: String,
        limit: Int,
        before: String?,
        mode: String = "skeleton",
    ): Result<MessagesPage>
}
