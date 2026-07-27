package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.runSuspendCatching
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * ι-P2 message 域端口。签名域语言，无 slim/legacy 字眼。
 *
 * # I15 (token threading)
 *
 * [OpenCodeRepository.SlimCommitToken] 透传——外层
 * （[OpenCodeRepository.captureSlimCommitToken]）在公共 wrapper 默认参数处捕获，
 * 经 [getMessagesPaged] 穿到实现。capture 逻辑不改。
 *
 * # §11.1 stage A — anchored `/since` staging path
 *
 * [getMessagesPagedStageA] is the stage-A staging surface for the anchored
 * slim `/since` path. It classifies the response into [SlimSinceStageAOutcome]
 * and performs NO bookmark mutation. The legacy [getMessagesPaged] is retained
 * for non-anchored / legacy-compatible callers; its slim branch no longer
 * bumps the bookmark either (stage A: `/since` is staging-only at every
 * surface). Anchored slim callers SHOULD migrate to [getMessagesPagedStageA].
 *
 * # §阶段B C1 (frozen protocol) — terminal-success split
 *
 *  - `/messages` (no-anchor cursor window, [MessagesPage]): terminal signal
 *    = `nextCursor == null`. `X-Since-Complete` is FORBIDDEN on this
 *    endpoint. The drain ([SlimSyncEngine.drainSlimapiMessagesBoundedOutcome])
 *    does NOT consult that header.
 *  - `/since/{ts}` (anchored incremental window, [SlimSincePage]):
 *    terminal Success = `nextCursor == null && X-Since-Complete == true`.
 *    The header is REQUIRED — missing / unparseable → protocol failure
 *    ([SlimSinceProtocolException]). The typed drain is
 *    [SlimSyncEngine.drainSlimSinceBoundedOutcome] (page primitive:
 *    [SlimSyncEngine.getSlimSincePage]); the Stage-A single-page staging
 *    surface here is separate (diagnostics-only, retains a nullable
 *    [SlimSinceStageAOutcome.Staged.completeHeader] for inspection).
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

    /**
     * §11.1 stage A: anchored slim `/since` staging surface. Classifies the
     * HTTP response into [SlimSinceStageAOutcome] WITHOUT any bookmark /
     * localApplied / dirty mutation. The caller owns token validation and
     * authoritative commit decisions.
     *
     * Anchored slim callers MUST consume `/since` via this method (not via
     * [getMessagesPaged]) so the staging-only invariant is structurally
     * enforced.
     */
    suspend fun getMessagesPagedStageA(
        sessionId: String,
        limit: Int?,
        before: String?,
        token: OpenCodeRepository.SlimCommitToken,
        anchored: Boolean,
    ): SlimSinceStageAOutcome
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

    /**
     * §11.1 stage A: standard (legacy) mode has no `/since` concept — this
     * method is never called in legacy mode (the anchored slim path is only
     * selected when slim mode is on). Throws to surface any accidental
     * cross-mode call loudly.
     */
    override suspend fun getMessagesPagedStageA(
        sessionId: String,
        limit: Int?,
        before: String?,
        token: OpenCodeRepository.SlimCommitToken,
        anchored: Boolean,
    ): SlimSinceStageAOutcome = throw UnsupportedOperationException(
        "StandardMessageSource.getMessagesPagedStageA: stage-A /since staging is slim-only (sid=$sessionId)",
    )
}

/**
 * Slim: 只 slimapi。**共享态协作者**（§4.4 最坏例）——不持锁、不持 slimStateMachine
 * 对象，只持注入的纯函数 lambda：
 *  - [apiProvider]: 读最新 [OpenCodeApi]（防陈旧）。
 *  - [slimSessionUpdatedAt]: 读缓存 watermark（注入；源 = OCR.slimStateMachine
 *    .getSlimSessionState(sid)?.localAppliedUpdatedAt ?: 0L；**只读**，不暴露状态机对象）。
 *  - [requireSlimTokenCurrent]: 注入的 token guard（抛 [OpenCodeRepository.StaleSlimCommitException]）。
 *    P1-1：[getMessagesPagedStageA] 必须在 HTTP 前后执行 token guard，与
 *    [SlimSyncEngine.fetchSinceForStageA] 的异常分类合同对齐。
 *
 * §11.1 stage A: the previously-injected `bumpBookmark` lambda was REMOVED.
 * The slim `/since` path is staging-only at EVERY surface — both anchored
 * (`/since/{watermark}`) and non-anchored (`/since/0`) return a typed
 * staging-only failure from [getMessagesPaged]; only [getMessagesPagedStageA]
 * surfaces the typed [SlimSinceStageAOutcome] classification. Bookmark
 * advancement happens ONLY via the full/cursor drain +
 * [SlimAuthoritativeCommitter.commitAuthoritative] authoritative path.
 *
 * token threading（I15）：[token] 透传到 [SlimMessageSource] 方法签名以保持接口一致。
 */
internal class SlimMessageSource(
    private val apiProvider: (OpenCodeRepository.SlimCommitToken) -> OpenCodeApi,
    private val slimSessionUpdatedAt: (String) -> Long,
    private val requireSlimTokenCurrent: (OpenCodeRepository.SlimCommitToken) -> Unit =
        { _ -> /* default no-op for legacy construction paths */ },
) : MessageSource {
    override suspend fun getMessagesPaged(
        sessionId: String,
        limit: Int?,
        before: String?,
        token: OpenCodeRepository.SlimCommitToken,
        anchored: Boolean,
    ): Result<MessagesPage> {
        // §11.1 fix-8 P0-1: the ENTIRE slim `/since` path (anchored OR
        // non-anchored, including `/since/0`) is staging-only. It MUST NOT
        // return items to the UI as a success page. Only the full/cursor
        // drain + authoritative commit path may feed items to the UI
        // (wired via [OpenCodeRepository.getMessagesPagedUnanchored] in
        // slim mode → [SlimSyncEngine.drainSlimapiMessagesBoundedOutcome]
        // → [SlimAuthoritativeCommitter.commitAuthoritative]).
        //
        // We delegate to the typed staging method and convert any
        // Staged / Incomplete outcome to a typed staging-only failure so
        // callers structurally cannot consume staged items as a success
        // page. The SlimSinceStagingOnlyException is a "conservative
        // staging" signal (P1-2): consumers MUST distinguish it from a
        // real transport failure (Failed.cause) and skip the UiEvent.Error
        // surface — REST reload is unavailable in slim stage-A, the SSE
        // feed drives updates.
        val outcome = getMessagesPagedStageA(
            sessionId = sessionId,
            limit = limit,
            before = before,
            token = token,
            anchored = anchored,
        )
        val anchorTs = if (anchored) slimSessionUpdatedAt(sessionId) else 0L
        return when (outcome) {
            is SlimSinceStageAOutcome.Staged ->
                Result.failure(
                    SlimSinceStagingOnlyException(
                        "stage A: getMessagesPaged slim /since is staging-only; " +
                            "use getMessagesPagedStageA + commitAuthoritative (sid=$sessionId, " +
                            "anchored=$anchored, since=$anchorTs, items=${outcome.items.size})",
                    ),
                )
            is SlimSinceStageAOutcome.Incomplete ->
                Result.failure(
                    SlimSinceStagingOnlyException(
                        "stage A: getMessagesPaged slim /since incomplete " +
                            "(reason=${outcome.reason}); use getMessagesPagedStageA " +
                            "+ commitAuthoritative (sid=$sessionId, anchored=$anchored)",
                    ),
                )
            is SlimSinceStageAOutcome.Failed ->
                Result.failure(outcome.cause)
        }
    }

    /**
     * §11.1 stage A: slim `/since` staging fetch. Classifies the HTTP
     * response into [SlimSinceStageAOutcome] WITHOUT any bookmark mutation.
     *
     * # Exception classification (mirrors [SlimSyncEngine.fetchSinceForStageA])
     *
     *  - [CancellationException]: thrown (NOT wrapped). Caller MUST propagate.
     *  - [OpenCodeRepository.StaleSlimCommitException]: thrown (NOT wrapped,
     *    NOT downgraded to [SlimSinceStageAOutcome.Failed]). P1-1: the catch
     *    order is CancellationException → StaleSlimCommitException → Throwable
     *    so the stale-incarnation signal propagates as a typed exception, not
     *    as a generic Failed outcome (consumers MUST NOT stage a stale result).
     *  - Other transport / IO: [SlimSinceStageAOutcome.Failed]`(cause)`.
     *  - Non-2xx: [SlimSinceStageAOutcome.Failed]`(IOException("HTTP ${code}"))`.
     *  - 2xx null body: [SlimSinceStageAOutcome.Incomplete]`(reason = "null_body")`.
     *  - 2xx non-null body: [SlimSinceStageAOutcome.Staged] (even when
     *    `X-Since-Complete: true` — stage A does NOT commit on this surface).
     *
     * # Token guard (P1-1)
     *
     * Pre-check before HTTP + post-check after HTTP (mirrors
     * [SlimSyncEngine.fetchSinceForStageA]). The captured-bundle API is
     * resolved via [apiProvider]`(token)` so the HTTP call uses the SAME
     * client bundle captured at operation entry; a host-rotation mid-flight
     * surfaces as [OpenCodeRepository.StaleSlimCommitException] at the
     * post-check (the in-flight HTTP may already have retired).
     */
    override suspend fun getMessagesPagedStageA(
        sessionId: String,
        limit: Int?,
        before: String?,
        token: OpenCodeRepository.SlimCommitToken,
        anchored: Boolean,
    ): SlimSinceStageAOutcome {
        val since = if (anchored) slimSessionUpdatedAt(sessionId) else 0L
        // P1-1: pre-check token BEFORE issuing the HTTP call. A stale
        // incarnation short-circuits without consuming the network.
        try {
            requireSlimTokenCurrent(token)
        } catch (e: OpenCodeRepository.StaleSlimCommitException) {
            throw e
        }
        val response = try {
            apiProvider(token).getSlimapiMessagesSince(sessionId, since, limit, before)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OpenCodeRepository.StaleSlimCommitException) {
            throw e
        } catch (e: Throwable) {
            return SlimSinceStageAOutcome.Failed(e)
        }

        // P1-1: post-check token AFTER the HTTP suspension. A host rotation
        // during the network call invalidates the response — surface as a
        // typed stale exception so the caller does not stage a retired payload.
        try {
            requireSlimTokenCurrent(token)
        } catch (e: OpenCodeRepository.StaleSlimCommitException) {
            throw e
        }

        if (!response.isSuccessful) {
            return SlimSinceStageAOutcome.Failed(
                IOException("HTTP ${response.code()}"),
            )
        }

        val body = response.body()
            ?: return SlimSinceStageAOutcome.Incomplete(
                items = emptyList(),
                reason = "null_body",
                statusCode = response.code(),
            )

        val completeHeader = response.headers()["X-Since-Complete"]?.let { raw ->
            when (raw.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

        // Stage A: even true is staging-only.
        return SlimSinceStageAOutcome.Staged(
            items = body,
            completeHeader = completeHeader,
            statusCode = response.code(),
            transportComplete = true,
        )
    }
}
