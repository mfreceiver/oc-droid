package cn.vectory.ocdroid.data.model

import kotlinx.serialization.json.JsonElement

/**
 * Server-defined slash command metadata returned by GET /command. Used to drive
 * the composer's `/`-command autocomplete. The [hints] bag carries optional
 * argument schema and toolbar affordances; the client only reads a few keys.
 */
@kotlinx.serialization.Serializable
data class CommandInfo(
    val name: String,
    val description: String? = null,
    val agent: String? = null,
    // ③ ServerCompat: `hints` is now captured (previously dropped — see the
    // history note below) as a raw [JsonElement] so the model never throws
    // regardless of the shape the server sends. Empirically (1.17.8–1.17.13)
    // the server emits `hints` as a JSON ARRAY of strings (e.g.
    // ["$ARGUMENTS"]); an earlier schema typed it as a [CommandHints] OBJECT.
    // Both forms (and any future third form) decode into JsonElement without
    // loss, and a typed view can be derived later when a UI consumer needs it
    // (see [hintsAsStringList]). Restoring the field also stops silently
    // discarding server data the client currently renders no opinion on.
    //
    // History: the field was previously NOT declared because the array-vs-
    // object mismatch made kotlinx.serialization throw on every command entry
    // → getCommands() failed → autocomplete fell back to 4 hardcoded local
    // commands. With `hints: JsonElement?` + ignoreUnknownKeys, neither shape
    // can break deserialization.
    val hints: JsonElement? = null
) {
    /**
     * Convenience typed view of [hints] when it is the current server's
     * array-of-strings form. Returns null for any other shape (object, scalar,
     * or absent) rather than throwing — callers should treat null as "no
     * usable hints". Returns the strings unfiltered.
     *
     * Tolerant parsing: only top-level [JsonPrimitive] elements are collected
     * as strings. Nested objects, arrays, and other non-primitive elements are
     * intentionally dropped rather than throwing, so callers should not expect
     * a 1:1 element count with the raw [hints] array.
     */
    val hintsAsStringList: List<String>?
        get() = (hints as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.takeIf { it.isNotEmpty() }
}
