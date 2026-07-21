package cn.vectory.ocdroid.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ── §2 / §6.1 lastError three-state model ────────────────────────────────

/**
 * Task 1 (slimapi v1 §2 / §6.1) — the payload carried inside a
 * `session.digest`'s `lastError` field when the sidecar IS surfacing an
 * upstream error for that session. Mirrors the v1 contract §2 schema:
 * `name` is the machine-readable error class (drives UI styling — e.g.
 * `UpstreamTimeout` vs `UpstreamHttp5xx`); `message` is server-scrubbed
 * (first line + path/stack/secret trimmed + ≤512 chars — the client
 * renders it verbatim, no further redaction); `at` is the epoch-ms of
 * the upstream failure.
 *
 * All secondary fields nullable + defaulted so partial objects (the
 * sidecar can legitimately emit only `name`) decode cleanly.
 */
@Serializable
data class SlimSessionLastError(
    val name: String,
    val message: String? = null,
    val at: Long? = null
)

/**
 * Task 1 (slimapi v1 §2 / §6.1) — three-state encoding for the
 * `session.digest.lastError` field. A plain `SlimSessionLastError?`
 * cannot distinguish "JSON key absent" (no information; preserve prior
 * banner state) from "JSON key present-null" (sidecar explicitly clears
 * the banner). The contract distinguishes all three:
 *
 *  - [Omitted] — the `lastError` key was ABSENT from the digest frame.
 *    The reducer MUST NOT touch the existing banner (a debounce tick
 *    that doesn't restate lastError shouldn't clear an active error).
 *  - [Cleared] — the key was present and JSON-null. The reducer MUST
 *    clear the session's banner (the sidecar is signalling recovery).
 *  - [Set] — the key was present and a JSON-object. The reducer MUST
 *    set/replace the session's banner from [error].
 *
 * Mechanic: [SlimSessionDigest] declares
 * `val lastError: LastErrorField = LastErrorField.Omitted` with a custom
 * serializer ([LastErrorFieldSerializer]). kotlinx.serialization invokes
 * the serializer ONLY when the key is present in the source JSON — when
 * absent, it falls back to the declared default (`Omitted`) and never
 * calls `deserialize(...)`. Inside the serializer, `JsonNull → Cleared`
 * / `JsonObject → Set`. This is the ONLY way to faithfully represent
 * all three states given `explicitNulls=false` on the project's shared
 * `Json` config (ViewModelSupport.kt:42-47).
 *
 * **Encode direction** is best-effort only: digests arrive via SSE and
 * are never re-encoded by the client. The serializer's `serialize`
 * emits the inner object for [Set] and `JsonNull` for [Cleared] /
 * [Omitted] — enough for unit-test round-tripping without
 * over-engineering the production path.
 */
sealed interface LastErrorField {
    /**
     * The `lastError` key was absent from the digest. No-op for the
     * reducer (preserve prior banner).
     */
    data object Omitted : LastErrorField

    /**
     * The `lastError` key was present and JSON-null. Reducer clears the
     * session's banner (sidecar signals upstream recovery).
     */
    data object Cleared : LastErrorField

    /**
     * The `lastError` key was present and a JSON-object. Reducer sets /
     * replaces the session's banner from [error].
     */
    data class Set(val error: SlimSessionLastError) : LastErrorField
}

/**
 * Task 1 (slimapi v1 §2 / §6.1) — the custom kotlinx serializer backing
 * [LastErrorField]. See [LastErrorField] for the three-state contract.
 *
 * **Descriptor**: built via `kotlinx.serialization.descriptors
 * .buildClassSerialDescriptor` (NOT `kotlinx.serialization.serializers
 * .buildClassSerialDescriptor` — that package does not exist; an earlier
 * plan draft had this bug). The descriptor is structural metadata for
 * the serializer — it advertises a class shape with the three inner
 * fields so debugging tooling / JSON schema drivers see a faithful
 * surface. It does NOT drive the manual decode (which goes through
 * [JsonDecoder.decodeJsonElement]).
 */
object LastErrorFieldSerializer : KSerializer<LastErrorField> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("LastErrorField") {
            element<String>("name")
            element<String>("message")
            element<Long>("at")
        }

    override fun serialize(encoder: Encoder, value: LastErrorField) {
        // Best-effort encode (digests are decode-only in production;
        // unit tests use this for round-trip). Set → inner object;
        // Cleared / Omitted → JSON null.
        val output: JsonElement = when (value) {
            is LastErrorField.Set -> buildJsonObject {
                put("name", value.error.name)
                value.error.message?.let { put("message", it) }
                value.error.at?.let { put("at", it) }
            }
            LastErrorField.Cleared -> JsonNull
            LastErrorField.Omitted -> JsonNull
        }
        encoder.encodeSerializableValue(JsonElement.serializer(), output)
    }

    override fun deserialize(decoder: Decoder): LastErrorField {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        // The field default (Omitted) is applied by kotlinx when the key
        // is absent — this branch is reached only when the key is
        // present. Distinguish JsonNull (explicit clear) from object
        // (set/replace).
        return when (element) {
            JsonNull -> LastErrorField.Cleared
            is JsonObject -> {
                val name = (element["name"] as? JsonPrimitive)?.content
                    ?: error("lastError object missing required 'name' field")
                val message = (element["message"] as? JsonPrimitive)?.content
                val at = (element["at"] as? JsonPrimitive)?.content?.toLongOrNull()
                LastErrorField.Set(SlimSessionLastError(name, message, at))
            }
            else -> error(
                "unexpected lastError JSON kind: expected null or object, got $element"
            )
        }
    }
}

// ── §3 resync reason ─────────────────────────────────────────────────────

/**
 * Task 1 (slimapi v1 §3) — the three legal `reason` values carried by a
 * `resync` SSE frame. All three trigger the SAME client action (discard
 * event-contiguity assumption, rebuild session list, iterate catch-up
 * set with probeLatest/needsCatchUp); the enum exists so the client can
 * attribute its resync frequency / backoff decisions in telemetry and
 * so unknown future reasons degrade gracefully (caller sees null and
 * falls back to the implicit-reconnect path).
 *
 * Wire values pinned verbatim by the v1 contract §3.
 */
@Serializable
enum class SlimapiResyncReason {
    /** Sidecar lost its replay buffer (restart / eviction) → no events replayable. */
    @SerialName("reconnect_no_replay") RECONNECT_NO_REPLAY,

    /** Sidecar's per-subscriber queue overflowed → events dropped for this client. */
    @SerialName("subscriber_backpressure") SUBSCRIBER_BACKPRESSURE,

    /**
     * Client reconnected without `Last-Event-ID` (or any other reason not
     * covered above) → server treats as resync, sidecar only emits
     * `server.connected`.
     */
    @SerialName("implicit") IMPLICIT;

    companion object {
        /**
         * Tolerant parser used by the SSE wiring layer: returns null for
         * null / blank / unknown wire values so the caller can degrade
         * to the implicit-reconnect code path without throwing. The
         * `@Serializable` annotation above is for direct field decoding
         * (round-trip); this companion is for raw-string lookup from
         * the SSE frame's `reason` property.
         */
        fun fromRaw(raw: String?): SlimapiResyncReason? = when (raw) {
            "reconnect_no_replay" -> RECONNECT_NO_REPLAY
            "subscriber_backpressure" -> SUBSCRIBER_BACKPRESSURE
            "implicit" -> IMPLICIT
            null, "" -> null
            else -> null
        }
    }
}

// ── Sessions page (three headers) ────────────────────────────────────

/**
 * rev-F: page-level metadata returned by `GET /slimapi/sessions` as
 * response headers (case-insensitive). Carries discovery-related information
 * that the client must consume with correct semantics, NOT as regular
 * pagination state.
 */
data class SlimSessionsPage(
    val sessions: List<Session>,
    /**
     * `X-Complete`: `"true"` iff `len(sessions) < limit` (this page not
     * full). NOT authoritative full universe — only a hint that the client
     * might be missing rows if it applies limit + got `complete=false`.
     */
    val complete: Boolean? = null,
    /**
     * `X-Discovery-Directories`: allowlist size (NOT hit count). Int
     * representation (the header string is parseable as an integer).
     */
    val discoveryDirectories: Int? = null,
    /**
     * `X-Discovery-Ready`: `"true"` if the sidecar has a last-known-good
     * snapshot. `"false"` = discovery not ready — the client MUST NOT treat
     * empty/null sessions as authoritative empty wipe. Null = old sidecar
     * (pre-rev-F) — preserve original behavior.
     */
    val discoveryReady: Boolean? = null,
)

// ── §5 G6 batch full envelope ────────────────────────────────────────────

/**
 * Task 1 (slimapi v1 §5 G6) — per-message failure carried inside the
 * `errors[]` array of a [SlimapiMessageFullBatch] response. The HTTP
 * status stays 200 when only SOME of the requested ids failed; the
 * client marks just those messages' expand-state as failed.
 *
 * `messageID` is the wire name (the rest of the codebase uses the same
 * `messageID` SerialName on [Part] / [Message]); `code` is one of the
 * [cn.vectory.ocdroid.data.repository.http.SlimapiErrorCodes] values
 * (typically [MESSAGE_NOT_FOUND]).
 */
@Serializable
data class SlimapiMessageBatchError(
    @SerialName("messageID") val messageId: String,
    val code: String? = null
)

/**
 * Task 1 (slimapi v1 §5 G6) — response envelope for
 * `GET /slimapi/messages/{sid}/full?ids=m1,m2,…&mode=full`. The sidecar
 * returns 200 with `items[]` carrying the resolved [MessageWithParts]
 * (in the requested id order, de-duplicated) and `errors[]` carrying
 * per-message failures (typically [SlimapiMessageBatchError] with
 * [cn.vectory.ocdroid.data.repository.http.SlimapiErrorCodes.MESSAGE_NOT_FOUND]
 * for ids the upstream no longer has).
 *
 * Both lists defaulted empty so a fully-empty 200 (`{"items":[],"errors":[]}`)
 * decodes cleanly. Pattern mirrors the existing
 * [SlimapiQuestionAggregation] / [SlimapiPermissionAggregation]
 * envelopes (Cluster A).
 */
@Serializable
data class SlimapiMessageFullBatch(
    val items: List<MessageWithParts> = emptyList(),
    val errors: List<SlimapiMessageBatchError> = emptyList()
)
