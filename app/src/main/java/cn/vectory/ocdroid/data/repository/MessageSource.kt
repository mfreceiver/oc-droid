package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.runSuspendCatching
import java.io.IOException

/**
 * ι-P2 message 域端口。签名域语言，无 slim/legacy 字眼。
 *
 * lite-v2-dev: SlimMessageSource + the /since staging path have been retired
 * (plan §4.1). Only StandardMessageSource remains; the slim message paging
 * path now routes through getSlimapiMessagesSkeleton directly.
 *
 * # I15 (token threading)
 *
 * [OpenCodeRepository.SlimCommitToken] 透传——外层
 * （[OpenCodeRepository.captureSlimCommitToken]）在公共 wrapper 默认参数处捕获，
 * 经 [getMessagesPaged] 穿到实现。capture 逻辑不改。
 */
internal interface MessageSource {
    suspend fun getMessagesPaged(
        sessionId: String,
        limit: Int?,
        before: String?,
        token: OpenCodeRepository.SlimCommitToken,
        anchored: Boolean,
    ): Result<MessagesPage>
}

/**
 * §pagination-header-fallback (2026-07-26): extracts the next-page cursor from
 * EITHER the slimapi's `X-Next-Cursor` response header OR opencode's RFC 5988
 * `Link: <...?before=<opaque>; rel="next"` header.
 *
 * The slimapi translates opencode's `Link` into `X-Next-Cursor` for its own
 * routes (`/slimapi/messages/{sid}`). But when the standard path calls opencode
 * directly (`GET /session/{sid}/message`), only the `Link` header is present —
 * `X-Next-Cursor` is absent, so the cursor was null and pagination was dead.
 *
 * This helper closes that gap: try `X-Next-Cursor` first (slimapi), fall back to
 * parsing the `Link` header (direct opencode). The `before` query-param value is
 * extracted VERBATIM (no percent-decoding) — opencode's cursor is an opaque
 * base64url JSON envelope that must round-trip byte-for-byte.
 */
internal fun extractNextCursor(
    xNextCursor: String?,
    linkHeader: String?,
): String? {
    if (xNextCursor != null) return xNextCursor
    if (linkHeader == null) return null
    // RFC 5988 Link header: comma-separated entries, each `<url>; rel="..."`.
    // opencode advertises: `Link: <...?before=<opaque>&limit=N>; rel="next"`
    for (raw in linkHeader.split(",")) {
        val segment = raw.trim()
        val urlStart = segment.indexOf('<')
        val urlEnd = segment.indexOf('>')
        if (urlStart < 0 || urlEnd < 0 || urlEnd <= urlStart) continue
        val url = segment.substring(urlStart + 1, urlEnd)
        val attrs = segment.substring(urlEnd + 1)
        // Check rel="next" (case-insensitive, may be multi-token).
        if (!attrs.contains("rel=", ignoreCase = true)) continue
        val relValue = attrs.substringAfter("rel=", "")
            .trim()
            .removePrefix("\"")
            .substringBefore("\"")
            .lowercase()
        if ("next" !in relValue.split(" ")) continue
        // Extract the `before` query param from the URL — VERBATIM (no decode).
        // The cursor is opaque base64url; parse_qs/unquote would corrupt it.
        val query = url.substringAfter("?", "")
        for (param in query.split("&")) {
            if (param.startsWith("before=")) {
                val value = param.substring("before=".length)
                // §rev-gpt: an empty `before=` (no value) is NOT a valid
                // cursor — return null so hasMore stays false instead of
                // triggering an invalid/repeated load-more request.
                if (value.isNotEmpty()) return value
            }
            // Also handle bare `?before` (no `=`) → not a valid cursor.
        }
    }
    return null
}

/**
 * Standard: 只 legacy。`apiProvider` lambda 防陈旧（复用 SlimGetRepository /
 * SessionSource 模式）——每次调用读最新 [OpenCodeApi]（host 重建后即生效）。
 *
 * `anchored` 在 legacy 分支无意义（无 watermark 概念）——参数接收但忽略，
 * 与原 [OpenCodeRepository.getMessagesPagedImpl] legacy 分支 byte-for-byte 一致。
 *
 * lite-v2-dev: this is now the ONLY MessageSource implementation. The slim
 * message paging path (getMessagesPaged slim branch in OpenCodeRepository)
 * routes through getSlimapiMessagesSkeleton directly, NOT through this source.
 */
internal class StandardMessageSource(
    private val apiProvider: () -> OpenCodeApi,
) : MessageSource {
    override suspend fun getMessagesPaged(
        sessionId: String,
        limit: Int?,
        before: String?,
        token: OpenCodeRepository.SlimCommitToken,
        anchored: Boolean,
    ): Result<MessagesPage> = runSuspendCatching {
        val response = apiProvider().getMessages(sessionId, limit, before)
        if (!response.isSuccessful) throw IOException("HTTP ${response.code()}")
        val items = response.body() ?: emptyList()
        // §pagination-header-fallback: standard path hits opencode directly,
        // which returns `Link` (RFC 5988), NOT `X-Next-Cursor`. The fallback
        // parser extracts the `before` cursor from the Link header.
        val nextCursor = extractNextCursor(
            xNextCursor = response.headers()["X-Next-Cursor"],
            linkHeader = response.headers()["Link"],
        )
        if (DebugLog.verboseDiagEnabled) {
            DebugLog.d("MessageSource", "getMessagesPaged standard sid=$sessionId limit=$limit before=$before items=${items.size} nextCursor=${nextCursor?.take(20)} xNextCursor=${response.headers()["X-Next-Cursor"] != null} linkHeader=${response.headers()["Link"] != null}")
        }
        MessagesPage(items = items, nextCursor = nextCursor)
    }
}
