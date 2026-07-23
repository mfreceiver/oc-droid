package cn.vectory.ocdroid.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * §Stage-C §3.2 — wire model for the oc-slimapi per-session token stream
 * (`GET /slimapi/sessions/{sid}/stream`). This is the centralized SSE frame
 * parser for the token-stream transport; it is deliberately decoupled from
 * the legacy instance-level [SSEEvent] parser (which serves `/slimapi/events`
 * digest/q-p/server frames) because the token-stream event vocabulary is
 * distinct and evolves independently.
 *
 * # Forward compatibility
 *
 * [parse] returns `null` for any unrecognized `event:` string so that a newer
 * server emitting a brand-new event type never breaks an older client — the
 * frame is silently skipped. Only the five events below are interpreted:
 *
 *  - `server.connected`    → [ServerConnected]
 *  - `server.heartbeat`    → [ServerHeartbeat] (empty/comment frame)
 *  - `message.part.snapshot` → [PartSnapshot]
 *  - `message.part.delta`  → [PartDelta]
 *  - `resync`              → [Resync]
 *
 * The wire JSON keys use camelCase with capital suffixes (`sessionID` /
 * `messageID` / `partID`) — these are the literal keys emitted by the
 * sidecar, NOT idiomatic Kotlin camelCase. [parse] reads them verbatim.
 *
 * # Pure & allocation-light
 *
 * The parser holds no state; it reuses a single [Json] instance (mirrors
 * [cn.vectory.ocdroid.data.api.SSEClient]'s companion Json config:
 * `ignoreUnknownKeys` + `isLenient` + `coerceInputValues`) so unknown fields
 * a newer server may add are tolerated. Malformed JSON → `null` (skip), never
 * throws — the transport must stay alive across a single bad frame.
 */
sealed interface TokenStreamFrame {

    /** Sidecar signalled the stream socket is live and bound to [sessionId]. */
    data class ServerConnected(val sessionId: String) : TokenStreamFrame

    /**
     * Empty/comment keep-alive frame. Carries no data; its sole purpose is to
     * prove the link is alive (resets the Stage-D watchdog clock). Recognized
     * purely by the `event:` string — the `data:` line is ignored.
     */
    object ServerHeartbeat : TokenStreamFrame

    /**
     * Full or terminal snapshot of a part's text. Per §3.3 state machine:
     *  - `done=false, truncated=false` → REPLACE the part buffer + STREAMING.
     *  - `done=true` → terminal; REPLACE the final text + DONE.
     *  - `truncated=true` → the server dropped in-flight state; the consumer
     *    MUST clear the part and re-fetch authoritatively.
     *
     * [text] is nullable: a snapshot may omit text (status-only) or carry
     * `text:null` (no payload this tick). The reducer treats null as empty
     * for buffer purposes.
     */
    data class PartSnapshot(
        val sessionId: String,
        val messageId: String,
        val partId: String,
        val text: String?,
        val done: Boolean,
        val truncated: Boolean,
    ) : TokenStreamFrame

    /** Incremental token append for a part currently in STREAMING state. */
    data class PartDelta(
        val sessionId: String,
        val messageId: String,
        val partId: String,
        val text: String,
    ) : TokenStreamFrame

    /**
     * Sidecar signalled the stream can no longer guarantee delivery for
     * [sessionId]. The consumer MUST clear ALL owned parts for that session,
     * re-fetch authoritatively, and — when [ResyncReason.triggersReconnect]
     * is true — tear down and re-open the transport (the server has no replay
     * buffer, so the only recovery is a fresh connection).
     */
    data class Resync(val reason: ResyncReason, val sessionId: String?) : TokenStreamFrame

    companion object {
        // SSE `event:` field strings — exact literals emitted by the sidecar.
        internal const val EVENT_SERVER_CONNECTED = "server.connected"
        internal const val EVENT_SERVER_HEARTBEAT = "server.heartbeat"
        internal const val EVENT_PART_SNAPSHOT = "message.part.snapshot"
        internal const val EVENT_PART_DELTA = "message.part.delta"
        internal const val EVENT_RESYNC = "resync"

        // Mirrors SSEClient.companion Json so both transports tolerate the same
        // shape variance (unknown keys, lenient booleans, coerced nulls).
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        /**
         * Parses one SSE frame. Returns `null` for:
         *  - unknown `event:` string (forward compatibility);
         *  - malformed JSON / non-object root (skip, never throw);
         *  - a recognized event missing a required field (drop, the transport
         *    cannot act on a partial frame);
         *  - an unrecognized `resync.reason` wire value (unknown reason).
         *
         * @param event the SSE `event:` field value (OkHttp `onEvent(type=…)`),
         *   null when the frame had no `event:` line.
         * @param data the raw `data:` payload (already concatenated by OkHttp
         *   if multi-line). For [ServerHeartbeat] this is ignored.
         */
        fun parse(event: String?, data: String): TokenStreamFrame? {
            // Heartbeat is recognized purely by event — data may be empty / a
            // comment. Handle before any JSON parsing.
            if (event == EVENT_SERVER_HEARTBEAT) return ServerHeartbeat

            val root: JsonObject = runCatching {
                json.parseToJsonElement(data) as? JsonObject
            }.getOrNull() ?: return null

            return when (event) {
                EVENT_SERVER_CONNECTED -> {
                    val sid = root.str("sessionID") ?: return null
                    ServerConnected(sid)
                }

                EVENT_PART_SNAPSHOT -> {
                    val sid = root.str("sessionID") ?: return null
                    val mid = root.str("messageID") ?: return null
                    val pid = root.str("partID") ?: return null
                    PartSnapshot(
                        sessionId = sid,
                        messageId = mid,
                        partId = pid,
                        text = root.str("text"),
                        done = root.boolOrFalse("done"),
                        truncated = root.boolOrFalse("truncated"),
                    )
                }

                EVENT_PART_DELTA -> {
                    val sid = root.str("sessionID") ?: return null
                    val mid = root.str("messageID") ?: return null
                    val pid = root.str("partID") ?: return null
                    val text = root.str("text") ?: return null
                    PartDelta(sid, mid, pid, text)
                }

                EVENT_RESYNC -> {
                    val reason = ResyncReason.fromWire(root.str("reason")) ?: return null
                    Resync(reason = reason, sessionId = root.str("sessionID"))
                }

                // Forward compatibility: unrecognized event → null (skip).
                else -> null
            }
        }

        // ── JSON helpers (project style: JsonElement access, never throws) ──────

        /**
         * Reads a string field. Returns null when:
         *  - the key is absent;
         *  - the value is JSON `null` ([JsonNull] — which in kotlinx-serialization
         *    1.6.x is itself a [JsonPrimitive] whose `.content` is the literal
         *    string "null", so the `=== JsonNull` guard is REQUIRED before the
         *    cast; the SlimapiV1 [LastErrorField] serializer uses the same
         *    explicit-JsonNull distinction for present-null vs absent);
         *  - the value is a non-primitive (object/array).
         */
        private fun JsonObject.str(key: String): String? {
            val el = this[key] ?: return null
            if (el === JsonNull) return null
            return (el as? JsonPrimitive)?.content
        }

        /** Reads a boolean field; absent / non-boolean / null → false. */
        private fun JsonObject.boolOrFalse(key: String): Boolean =
            (this[key] as? JsonPrimitive)?.takeIf { it !== JsonNull }?.booleanOrNull ?: false
    }
}

/**
 * §Stage-C §3.2 (opus SF-2) — resync reasons emitted by the sidecar on the
 * token-stream transport. Mirrors the server-side taxonomy; the wire strings
 * are the literal values carried in the `reason` JSON field of a `resync`
 * frame.
 *
 * [triggersReconnect] partitions the reasons into two recovery classes:
 *  - `true`  → the transport itself is unusable (no replay buffer); the
 *    consumer MUST close + re-open the SSE connection.
 *  - `false` → only the in-flight part state is lost; clearing + re-fetching
 *    suffices, the socket can stay open.
 *
 * This is distinct from [SlimapiResyncReason] (legacy `/slimapi/events`
 * digest resync taxonomy); the token-stream reasons are a separate vocabulary.
 */
enum class ResyncReason(val wire: String) {
    RECONNECT_NO_REPLAY("reconnect_no_replay"),
    SUBSCRIBER_BACKPRESSURE("subscriber_backpressure"),
    PART_TOO_LARGE("part_too_large"),
    TOKEN_MEMORY_LIMIT("token_memory_limit");

    val triggersReconnect: Boolean
        get() = this == RECONNECT_NO_REPLAY || this == SUBSCRIBER_BACKPRESSURE

    companion object {
        /** Null-safe reverse lookup; unknown wire value → null (forward-compat). */
        fun fromWire(s: String?): ResyncReason? = s?.let { wire ->
            entries.firstOrNull { it.wire == wire }
        }
    }
}

/**
 * §Stage-C §3.7 — epoch-tagged frame envelope. The Stage-D coordinator stamps
 * each parsed frame with the epoch of the connection that delivered it; an
 * effect emitted against a stale epoch (the connection has since been torn
 * down + re-opened) MUST be discarded to avoid a late frame from a dead
 * socket mutating the new stream's state.
 *
 * This file holds ONLY the data type — the tagging + epoch-check logic lives
 * in Stage D ([TokenStreamCoordinator]).
 *
 * @param epoch monotonic counter captured at connection open; 0 for frames
 *   assembled outside a coordinator (tests / synthetic).
 */
data class EpochFrame(
    val epoch: Long,
    val frame: TokenStreamFrame,
)
