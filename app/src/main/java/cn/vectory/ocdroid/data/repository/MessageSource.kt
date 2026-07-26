package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.runSuspendCatching
import java.io.IOException

/**
 * ι-P2 message 域端口。签名域语言，无 slim/legacy 字眼。
 *
 * # I15 (token threading)
 *
 * [OpenCodeRepository.SlimCommitToken] 透传——外层
 * （[OpenCodeRepository.captureSlimCommitToken]）在公共 wrapper 默认参数处捕获，
 * 经 [getMessagesPaged] 穿到实现，实现内调注入的 [SlimMessageSource.bumpBookmark]
 * lambda 回 OCR.bumpSlimBookmarkFromItems，require 一致。capture/bump 逻辑不改。
 *
 * # anchored 语义
 *
 * slim 实现里 `anchored = true` 读缓存 watermark
 * （`since = slimSessionUpdatedAt(sid)`），`anchored = false` 强制 `since = 0L`
 * （unanchored initial window，§empty-window-fix）。
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
                return param.substring("before=".length)
            }
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

/**
 * Slim: 只 slimapi。**共享态协作者**（§4.4 最坏例）——不持锁、不持 slimStateMachine
 * 对象，只持三条注入的纯函数 lambda：
 *  - [apiProvider]: 读最新 [OpenCodeApi]（防陈旧）。
 *  - [slimSessionUpdatedAt]: 读缓存 watermark（注入；源 = OCR.slimStateMachine
 *    .getSlimSessionState(sid)?.updatedAt ?: 0L；**只读**，不暴露状态机对象）。
 *  - [bumpBookmark]: 回调 OCR.bumpSlimBookmarkFromItems（注入；源 = OCR 私有方法，
 *    内部 `synchronized(slimStateLock)`）→ **锁与 bookmark 状态留 OCR**（I5 保持）。
 *
 * token threading（I15）：[token] 透传到 [bumpBookmark]——stale 时 bumpBookmark
 * 返回 false → 抛 [OpenCodeRepository.StaleSlimCommitException]（嵌套 FQN 不变）。
 */
internal class SlimMessageSource(
    private val apiProvider: () -> OpenCodeApi,
    private val slimSessionUpdatedAt: (String) -> Long,
    private val bumpBookmark: suspend (
        sessionId: String,
        items: List<MessageWithParts>,
        token: OpenCodeRepository.SlimCommitToken,
    ) -> Boolean,
) : MessageSource {
    override suspend fun getMessagesPaged(
        sessionId: String,
        limit: Int?,
        before: String?,
        token: OpenCodeRepository.SlimCommitToken,
        anchored: Boolean,
    ): Result<MessagesPage> = runSuspendCatching {
        val since = if (anchored) slimSessionUpdatedAt(sessionId) else 0L
        val response = apiProvider().getSlimapiMessagesSince(sessionId, since, limit, before)
        if (!response.isSuccessful) throw IOException("HTTP ${response.code()}")
        val items = response.body() ?: emptyList()
        // bookmark bump 经注入 lambda 回调 OCR.bumpSlimBookmarkFromItems
        // （锁内 mutation；token 透传，stale → throw StaleSlimCommitException）。
        if (!bumpBookmark(sessionId, items, token)) {
            throw OpenCodeRepository.StaleSlimCommitException()
        }
        // §pagination-header-fallback: slimapi normally sets `X-Next-Cursor`,
        // but the `/since/0` initial-load path may suppress it (ts-floor rule).
        // The Link header fallback covers the edge case where the sidecar
        // forwarded opencode's Link header without translating it.
        val nextCursor = extractNextCursor(
            xNextCursor = response.headers()["X-Next-Cursor"],
            linkHeader = response.headers()["Link"],
        )
        if (DebugLog.verboseDiagEnabled) {
            DebugLog.d("MessageSource", "getMessagesPaged slim sid=$sessionId since=$since limit=$limit before=$before items=${items.size} nextCursor=${nextCursor?.take(20)} xNextCursor=${response.headers()["X-Next-Cursor"] != null} linkHeader=${response.headers()["Link"] != null}")
        }
        MessagesPage(items = items, nextCursor = nextCursor)
    }
}
