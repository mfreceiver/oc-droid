package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.MessageWithParts

/** Phase B narrow seam: message load / expand / probe / skeleton. Implemented by [OpenCodeRepository].
 *
 *  Note: the `token` params carry no default on the overriding concrete repo — Kotlin forbids
 *  default values on `override` methods, so this interface is the authoritative surface. For
 *  the paged/skeleton fetches (`getMessagesPaged`, `getMessagesPagedUnanchored`,
 *  `getSlimapiMessagesPage`) the token is REQUIRED and callers supply one via the concrete
 *  repo's `captureSlimCommitToken()`; `expandMessagesFullBatch` keeps an optional nullable
 *  token. This couples the seam to OCR's nested [OpenCodeRepository.SlimCommitToken] type —
 *  a compile-time coupling (the method *surface* is additionally pinned by
 *  T3RepositoryExtractFreezeTest §6b, which checks method names, not param types) that holds
 *  until the slim-token compatibility shim is retired (B3). */
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
