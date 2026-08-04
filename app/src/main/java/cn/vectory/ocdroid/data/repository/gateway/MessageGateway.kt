package cn.vectory.ocdroid.data.repository.gateway

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.data.repository.ExpandOutcome
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ProbeResult
import cn.vectory.ocdroid.data.repository.SLIMAPI_LOCAL_HISTORY_BOUND
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.runSuspendCatching
import java.io.IOException

/**
 * Gateway for message operations: load, actions, skeleton reload.
 *
 * Zero mutable state — all reads go through [bundleProvider] every call,
 * preserving the generational-consistency invariant.
 */
internal class MessageGateway(
    private val bundleProvider: () -> ClientBundle,
    private val serverCompatProfile: ServerCompatProfile,
) {
    private val api: OpenCodeApi get() = bundleProvider().restApi

    suspend fun getMessages(sessionId: String, limit: Int? = null): Result<List<MessageWithParts>> =
        runSuspendCatching {
            val response = api.getMessages(sessionId, limit, before = null)
            if (!response.isSuccessful) throw IOException("HTTP ${response.code()}")
            response.body() ?: emptyList()
        }

    suspend fun getMessagesPaged(
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
        token: OpenCodeRepository.SlimCommitToken,
    ): Result<MessagesPage> {
        if (serverCompatProfile.slimConnection) {
            return runSuspendCatching {
                val page = getSlimapiMessagesSkeleton(
                    sessionId,
                    limit = limit ?: SLIMAPI_LOCAL_HISTORY_BOUND,
                    before = before,
                )
                MessagesPage(items = page.items, nextCursor = page.nextCursor)
            }
        }
        return getMessagesPagedImpl(sessionId, limit, before)
    }

    suspend fun getMessagesPagedUnanchored(
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
        token: OpenCodeRepository.SlimCommitToken,
    ): Result<MessagesPage> {
        if (serverCompatProfile.slimConnection) {
            return runSuspendCatching {
                val page = getSlimapiMessagesSkeleton(
                    sessionId,
                    limit = limit ?: SLIMAPI_LOCAL_HISTORY_BOUND,
                    before = before,
                )
                MessagesPage(items = page.items, nextCursor = page.nextCursor)
            }
        }
        return getMessagesPagedImpl(sessionId, limit, before)
    }

    private suspend fun getMessagesPagedImpl(
        sessionId: String,
        limit: Int?,
        before: String?,
    ): Result<MessagesPage> = runSuspendCatching {
        val response = api.getMessages(sessionId, limit, before)
        if (!response.isSuccessful) throw IOException("HTTP ${response.code()}")
        val items = response.body() ?: emptyList()
        val nextCursor = extractNextCursor(
            xNextCursor = response.headers()["X-Next-Cursor"],
            linkHeader = response.headers()["Link"],
        )
        if (DebugLog.verboseDiagEnabled) {
            DebugLog.d("MessageGateway", "getMessagesPagedImpl sid=$sessionId limit=$limit before=$before items=${items.size} nextCursor=${nextCursor?.take(20)}")
        }
        MessagesPage(items = items, nextCursor = nextCursor)
    }

    suspend fun probeLatestMessageId(sessionId: String): Result<String?> = runSuspendCatching {
        val response = api.getMessages(sessionId, limit = 1, before = null)
        if (!response.isSuccessful) throw IOException("HTTP ${response.code()}")
        response.body()?.firstOrNull()?.info?.id
    }

    suspend fun probeLatestMessageIdForCurrent(sessionId: String): ProbeResult =
        if (bundleProvider().hostSnapshot.slimHost) {
            probeLatestSlim(sessionId)
        } else {
            probeLatestMessageId(sessionId).toProbeResult()
        }

    suspend fun probeLatestSlim(sessionId: String): ProbeResult = runSuspendCatching {
        val resp = api.getSlimapiMessages(sessionId, limit = 1, before = null, mode = "skeleton")
        if (!resp.isSuccessful) {
            DebugLog.d("SlimapiProbe", "probe sid=$sessionId FAILED http=${resp.code()}")
            return@runSuspendCatching ProbeResult(ok = false, httpStatus = resp.code())
        }
        val arr = resp.body() ?: return@runSuspendCatching ProbeResult(ok = false, httpStatus = resp.code())
        if (arr.isEmpty()) {
            DebugLog.d("SlimapiProbe", "probe sid=$sessionId EMPTY")
            ProbeResult(ok = true, empty = true)
        } else {
            val mid = arr.first().info.id
            val ts = arr.first().info.time?.updated ?: arr.first().info.time?.created
            DebugLog.d("SlimapiProbe", "probe sid=$sessionId OK latest=$mid ts=$ts")
            ProbeResult(ok = true, messageID = mid, updatedAt = ts)
        }
    }.getOrElse { error ->
        DebugLog.d("SlimapiProbe", "probe sid=$sessionId TRANSPORT_FAIL ${error.javaClass.simpleName}: ${error.message}")
        ProbeResult(ok = false, httpStatus = null)
    }

    suspend fun expandMessagesFullBatch(
        sessionId: String,
        messageIds: Set<String>,
        token: OpenCodeRepository.SlimCommitToken? = null,
    ): ExpandOutcome {
        val items = mutableListOf<MessageWithParts>()
        val failures = mutableListOf<ExpandOutcome.MessageFailure>()
        for (mid in messageIds) {
            getSlimapiMessageFull(sessionId, mid)
                .onSuccess { items += it }
                .onFailure { failures += ExpandOutcome.MessageFailure(mid, code = null) }
        }
        return ExpandOutcome.Ok(items = items, failures = failures, usedBatch = false)
    }

    suspend fun getSlimapiMessagesSkeleton(
        sessionId: String,
        limit: Int,
        before: String? = null,
    ): MessagesPage {
        val response = api.getSlimapiMessages(sessionId, limit, before, mode = "skeleton")
        if (!response.isSuccessful) throw IOException("HTTP ${response.code()}")
        val items = response.body() ?: throw IOException("null_body")
        return MessagesPage(items = items, nextCursor = response.headers()["X-Next-Cursor"])
    }

    suspend fun getSlimapiMessageFull(
        sessionId: String,
        messageId: String,
    ): Result<MessageWithParts> = runSuspendCatching {
        api.getSlimapiMessageFull(sessionId, messageId)
    }

    suspend fun getSlimapiMessagesPage(
        sessionId: String,
        limit: Int,
        before: String?,
        mode: String = "skeleton",
        token: OpenCodeRepository.SlimCommitToken? = null,
    ): Result<MessagesPage> = getMessagesPaged(sessionId, limit, before, token ?: OpenCodeRepository.SlimCommitToken(marker = Any(), issuedReady = false))

    // ── Private helpers ─────────────────────────────────────────────────

    private fun Result<String?>.toProbeResult(): ProbeResult = fold(
        onSuccess = { messageId ->
            ProbeResult(ok = true, empty = messageId == null, messageID = messageId)
        },
        onFailure = { error ->
            ProbeResult(ok = false, httpStatus = error.message?.removePrefix("HTTP ")?.toIntOrNull())
        },
    )

    /**
     * Extracts the next-page cursor from EITHER the slimapi's
     * `X-Next-Cursor` response header OR opencode's RFC 5988
     * `Link: <...?before=<opaque>; rel="next"` header.
     */
    private fun extractNextCursor(
        xNextCursor: String?,
        linkHeader: String?,
    ): String? {
        if (xNextCursor != null) return xNextCursor
        if (linkHeader == null) return null
        for (raw in linkHeader.split(",")) {
            val segment = raw.trim()
            val urlStart = segment.indexOf('<')
            val urlEnd = segment.indexOf('>')
            if (urlStart < 0 || urlEnd < 0 || urlEnd <= urlStart) continue
            val url = segment.substring(urlStart + 1, urlEnd)
            val attrs = segment.substring(urlEnd + 1)
            if (!attrs.contains("rel=", ignoreCase = true)) continue
            val relValue = attrs.substringAfter("rel=", "")
                .trim()
                .removePrefix("\"")
                .substringBefore("\"")
                .lowercase()
            if ("next" !in relValue.split(" ")) continue
            val query = url.substringAfter("?", "")
            for (param in query.split("&")) {
                if (param.startsWith("before=")) {
                    val value = param.substring("before=".length)
                    if (value.isNotEmpty()) return value
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "MessageGateway"
    }
}
