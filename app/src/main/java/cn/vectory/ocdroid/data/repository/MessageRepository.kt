package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.MessageWithParts

/** Phase B narrow seam: message load / expand / probe / skeleton. Implemented by [OpenCodeRepository].
 *  Note: token-default methods declare `token` REQUIRED here; OCR's override keeps the
 *  `= captureSlimCommitToken()` default for source-compat with the frozen concrete surface. */
interface MessageRepository {
    suspend fun getMessages(sessionId: String, limit: Int? = null): Result<List<MessageWithParts>>
    suspend fun getMessagesPaged(
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
        token: OpenCodeRepository.SlimCommitToken,
    ): Result<MessagesPage>
    suspend fun getMessagesPagedUnanchored(
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
        token: OpenCodeRepository.SlimCommitToken,
    ): Result<MessagesPage>
    suspend fun probeLatestMessageId(sessionId: String): Result<String?>
    suspend fun probeLatestMessageIdForCurrent(sessionId: String): ProbeResult
    suspend fun probeLatestSlim(sessionId: String): ProbeResult
    suspend fun expandMessagesFullBatch(
        sessionId: String,
        messageIds: Set<String>,
        token: OpenCodeRepository.SlimCommitToken? = null,
    ): ExpandOutcome
    suspend fun getSlimapiMessagesSkeleton(sessionId: String, limit: Int, before: String? = null): MessagesPage
    suspend fun getSlimapiMessageFull(sessionId: String, messageId: String): Result<MessageWithParts>
    suspend fun getSlimapiMessagesPage(
        sessionId: String,
        limit: Int,
        before: String?,
        mode: String = "skeleton",
        token: OpenCodeRepository.SlimCommitToken,
    ): Result<MessagesPage>
}
