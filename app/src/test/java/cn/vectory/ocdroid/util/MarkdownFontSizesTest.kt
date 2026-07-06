package cn.vectory.ocdroid.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R18 Phase 5 — [MarkdownFontSizes] pure-JVM coverage.
 *
 * The class itself is a `@Serializable` value type with v2-canonical defaults;
 * it has zero Android dependencies, so a plain JUnit test exercises both the
 * default-value contract (the docstring's v2 §3 numbers) and the kotlinx
 * serialization round-trip used by [SettingsManager.markdownFontSizes].
 *
 * ROI: the file was previously 0% covered; this fully covers the data class
 * declaration + property defaults (every `= NNf` initializer is read).
 */
class MarkdownFontSizesTest {

    @Test
    fun `defaults match v2 spec`() {
        val d = MarkdownFontSizes()
        // v2 §3: h1=17 / h2=15 / h3=13 / h4-h6=13 / body=14 / code=13 /
        // inlineCode=14 / quote=14 / reasoning=13.
        assertEquals(17f, d.h1, 0f)
        assertEquals(15f, d.h2, 0f)
        assertEquals(13f, d.h3, 0f)
        assertEquals(13f, d.h4, 0f)
        assertEquals(13f, d.h5, 0f)
        assertEquals(13f, d.h6, 0f)
        assertEquals(14f, d.body, 0f)
        assertEquals(13f, d.code, 0f)
        assertEquals(14f, d.inlineCode, 0f)
        assertEquals(14f, d.quote, 0f)
        assertEquals(13f, d.reasoning, 0f)
    }

    @Test
    fun `custom values are preserved`() {
        val sizes = MarkdownFontSizes(
            h1 = 20f,
            h2 = 18f,
            h3 = 16f,
            h4 = 14f,
            h5 = 12f,
            h6 = 10f,
            body = 15f,
            code = 12f,
            inlineCode = 13f,
            quote = 13.5f,
            reasoning = 11f,
        )
        assertEquals(20f, sizes.h1, 0f)
        assertEquals(10f, sizes.h6, 0f)
        assertEquals(13.5f, sizes.quote, 0f)
        assertEquals(11f, sizes.reasoning, 0f)
    }

    @Test
    fun `data class equality holds for same values`() {
        val a = MarkdownFontSizes(body = 16f, code = 14f)
        val b = MarkdownFontSizes(body = 16f, code = 14f)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality when any field differs`() {
        val a = MarkdownFontSizes()
        val b = MarkdownFontSizes(body = 99f)
        assertNotEquals(a, b)
    }

    @Test
    fun `kotlinx serialization round trip preserves all fields`() {
        val original = MarkdownFontSizes(
            h1 = 22f,
            h2 = 19f,
            h3 = 17f,
            h4 = 15f,
            h5 = 13f,
            h6 = 11f,
            body = 16f,
            code = 13f,
            inlineCode = 15f,
            quote = 14f,
            reasoning = 12f,
        )

        val json = Json.encodeToString(MarkdownFontSizes.serializer(), original)
        val decoded = Json.decodeFromString(MarkdownFontSizes.serializer(), json)

        assertEquals(original, decoded)
    }

    @Test
    fun `non-default values serialise with stable field names that round-trip`() {
        // Locks the serialisation contract: field names + their wire presence.
        // SettingsManager persists this verbatim using the default Json config
        // (encodeDefaults=false → defaults are dropped), so we exercise the
        // "all fields set to non-default" path which DOES emit every field.
        // A rename or dropped field would change the wire shape and fail here.
        val nonDefault = MarkdownFontSizes(
            h1 = 100f, h2 = 101f, h3 = 102f, h4 = 103f, h5 = 104f, h6 = 105f,
            body = 106f, code = 107f, inlineCode = 108f, quote = 109f, reasoning = 110f,
        )
        val json = Json.encodeToString(MarkdownFontSizes.serializer(), nonDefault)
        val obj = Json.decodeFromString(JsonObject.serializer(), json)

        // Every documented field name must appear on the wire.
        for (field in listOf(
            "h1", "h2", "h3", "h4", "h5", "h6",
            "body", "code", "inlineCode", "quote", "reasoning",
        )) {
            assertTrue(
                "serialised JSON must contain field '$field' (got keys: ${obj.keys})",
                obj.containsKey(field),
            )
        }
        // No extra fields.
        assertEquals(11, obj.size)

        // Round-trip preserves every value.
        val decoded = Json.decodeFromString(MarkdownFontSizes.serializer(), json)
        assertEquals(nonDefault, decoded)
    }

    @Test
    fun `production default instance encodes compactly because kotlinx drops defaults`() {
        // SettingsManager uses the default Json config (encodeDefaults=false),
        // so a fully-default MarkdownFontSizes() persists as "{}". This locks
        // that contract: a future migration to encodeDefaults=true would
        // change the on-disk shape, and this test turns that into a visible
        // diff rather than a silent prefs format change.
        val json = Json.encodeToString(MarkdownFontSizes.serializer(), MarkdownFontSizes())
        assertEquals("{}", json)
        // And it round-trips back to defaults via the missing-key fallback.
        val decoded = Json.decodeFromString(MarkdownFontSizes.serializer(), json)
        assertEquals(MarkdownFontSizes(), decoded)
    }

    @Test
    fun `decoded JSON missing keys falls back to defaults`() {
        // Forward-compat: an older prefs blob without newly-added fields must
        // decode without throwing, with defaults filling the gaps.
        val decoded = Json.decodeFromString(MarkdownFontSizes.serializer(), "{}")
        assertEquals(MarkdownFontSizes(), decoded)
    }

    @Test
    fun `decoded JSON with partial overrides keeps defaults for the rest`() {
        val decoded = Json.decodeFromString(
            MarkdownFontSizes.serializer(),
            """{"body":99.0,"h1":1.0}""",
        )
        assertEquals(1f, decoded.h1, 0f)
        assertEquals(99f, decoded.body, 0f)
        // Untouched fields fall back to defaults.
        assertEquals(15f, decoded.h2, 0f)
        assertEquals(14f, decoded.inlineCode, 0f)
    }
}
