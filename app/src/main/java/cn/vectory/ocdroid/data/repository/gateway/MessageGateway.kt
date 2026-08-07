package cn.vectory.ocdroid.data.repository.gateway

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.data.repository.ExpandOutcome
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.repository.ProbeResult
import cn.vectory.ocdroid.data.repository.SLIMAPI_LOCAL_HISTORY_BOUND
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.data.repository.http.SlimapiErrorCodes
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.runSuspendCatching
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Gateway for message operations: load, actions, skeleton reload.
 *
 * Zero mutable state — all reads go through [bundleProvider] every call,
 * preserving the generational-consistency invariant.
 */
internal class MessageGateway(
    private val bundleProvider: () -> ClientBundle,
    private val serverCompatProfile: ServerCompatProfile,
    private val json: Json,
) {
    private val api: OpenCodeApi get() = bundleProvider().restApi

    suspend fun getMessages(sessionId: String, limit: Int? = null): Result<List<MessageWithParts>> {
        if (serverCompatProfile.slimConnection) {
            return runSuspendCatching {
                getSlimapiMessagesSkeleton(
                    sessionId,
                    limit = limit ?: SLIMAPI_LOCAL_HISTORY_BOUND,
                    before = null,
                ).items
            }
        }
        return runSuspendCatching {
            val response = api.getMessages(sessionId, limit, before = null)
            if (!response.isSuccessful) throw IOException("HTTP ${response.code()}")
            response.body() ?: emptyList()
        }
    }

    suspend fun getMessagesPaged(
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
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
            if (DebugLog.verboseDiagEnabled) {
                DebugLog.d("SlimapiProbe", "probe sid=$sessionId FAILED http=${resp.code()}")
            }
            return@runSuspendCatching ProbeResult(ok = false, httpStatus = resp.code())
        }
        val arr = resp.body() ?: return@runSuspendCatching ProbeResult(ok = false, httpStatus = resp.code())
        if (arr.isEmpty()) {
            if (DebugLog.verboseDiagEnabled) {
                DebugLog.d("SlimapiProbe", "probe sid=$sessionId EMPTY")
            }
            ProbeResult(ok = true, empty = true)
        } else {
            val mid = arr.first().info.id
            val ts = arr.first().info.time?.updated ?: arr.first().info.time?.created
            if (DebugLog.verboseDiagEnabled) {
                DebugLog.d("SlimapiProbe", "probe sid=$sessionId OK latest=$mid ts=$ts")
            }
            ProbeResult(ok = true, messageID = mid, updatedAt = ts)
        }
    }.getOrElse { error ->
        if (DebugLog.verboseDiagEnabled) {
            DebugLog.d("SlimapiProbe", "probe sid=$sessionId TRANSPORT_FAIL ${error.javaClass.simpleName}: ${error.message}")
        }
        ProbeResult(ok = false, httpStatus = null)
    }

    /**
     * §slimapi-client-impl-v1 §5 G6 (lite-v2 N×/full loop). Expands each
     * requested message id via an individual `GET /slimapi/messages/{sid}/full/{mid}`
     * call (the V1 batch endpoint `…/full?ids=` and the ExpandBatchEngine
     * retry/halving/backoff machinery were RETIRED in lite-v2 — see
     * OpenCodeRepository.kt retire note above expandMessagesFullBatch).
     *
     * Outcome branching (consumed by T15 PartExpandState):
     *  - at least one id resolves → [ExpandOutcome.Ok] (carries resolved
     *    `items` + per-message `failures` with their parsed envelope codes);
     *  - any id fails with `session_not_found` → [ExpandOutcome.SessionMissing]
     *    (the whole session is gone upstream — every per-id call against a
     *    missing session returns this code, so a single occurrence is
     *    sufficient; mirrors the G2 status handling the UI expects);
     *  - every id fails with anything else → [ExpandOutcome.Failed]
     *    (representative code = first non-null envelope code, or null when
     *    all failures are transport-level IOException).
     *
     * ## Legacy fallback — deliberately absent (design decision)
     *
     * This method has NO legacy `GET /session/{sid}/message` fallback. This
     * does NOT violate the catalog's 404-fallback rule: the catalog's
     * "404 → cache 60s → per-id /full/{mid}" rule described the OLD batch
     * endpoint (`/full?ids=`) → per-id transition (slim-mode-api-routing.md
     * §5.4 G6), which is itself RETIRED. In lite-v2 the client is already on
     * the per-id `/full/{mid}` path, and the oc-slimapi sidecar is the
     * sole transport in slim mode (always present when
     * [ServerCompatProfile.slimConnection] is true). A legacy fallback would
     * re-introduce the very REST passthrough slim mode exists to eliminate,
     * so none is wired. If a future deployment must tolerate sidecar
     * degradation, that is a separate design decision (see audit P1.5).
     */
    suspend fun expandMessagesFullBatch(
        sessionId: String,
        messageIds: Set<String>,
    ): ExpandOutcome {
        val items = mutableListOf<MessageWithParts>()
        val failures = mutableListOf<ExpandOutcome.MessageFailure>()
        for (mid in messageIds) {
            getSlimapiMessageFull(sessionId, mid)
                .onSuccess { items += it }
                .onFailure { throwable ->
                    val code = parseEnvelopeCode(throwable)
                    failures += ExpandOutcome.MessageFailure(mid, code = code)
                }
        }
        // Session gone upstream — any session_not_found is sufficient (a missing
        // session 404s every per-id call with this code).
        if (failures.any { it.code == SlimapiErrorCodes.SESSION_NOT_FOUND }) {
            return ExpandOutcome.SessionMissing(sessionId)
        }
        // Total failure (no id resolved) — collapse to Failed so the UI can
        // distinguish all-failed from partial-success. Representative code is
        // the first non-null envelope code; null when every failure was
        // transport-level (IOException).
        if (items.isEmpty() && failures.isNotEmpty()) {
            return ExpandOutcome.Failed(
                sessionId = sessionId,
                code = failures.firstNotNullOfOrNull { it.code },
            )
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
    ): Result<MessagesPage> = getMessagesPaged(sessionId, limit, before)

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

    /**
     * Best-effort extract the sidecar's machine-readable error code from a
     * per-id `/full/{mid}` failure. Non-2xx responses are thrown by Retrofit
     * as [retrofit2.HttpException] (the suspend API returns the body
     * directly, not `Response<*>`); transport failures surface as
     * [IOException] and yield `null` (no envelope to parse). Mirrors
     * [OpenCodeRepository.parseErrorCode] / SessionGateway's private copy;
     * kept per-gateway to stay self-contained (same convention as the other
     * gateways). One-shot errorBody read is safe inside runCatching.
     */
    private fun parseEnvelopeCode(throwable: Throwable): String? {
        val httpException = throwable as? retrofit2.HttpException ?: return null
        val rawBody = runCatching { httpException.response()?.errorBody()?.string() }.getOrNull()
        return parseErrorCodeFromRaw(rawBody)
    }

    private fun parseErrorCodeFromRaw(rawBody: String?): String? {
        if (rawBody == null) return null
        return try {
            val obj = json.decodeFromString<JsonObject>(rawBody)
            (obj["code"] as? JsonPrimitive)?.content
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "MessageGateway"
    }
}
