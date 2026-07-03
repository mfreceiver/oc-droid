package cn.vectory.ocdroid.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ③ ServerCompat — [CommandInfo.hints] tolerant decode. Pins the contract that
 * neither the current server's array-of-strings form nor the legacy object form
 * can break command deserialization (the regression that originally forced the
 * field to be dropped entirely).
 */
class CommandInfoHintsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test
    fun `decodes array-of-strings hints (1_17_8 through 1_17_13 server shape)`() {
        val payload = """
            {"name":"init","description":"d","hints":["ARGUMENTS","FILES"]}
        """.trimIndent()
        val cmd = json.decodeFromString(CommandInfo.serializer(), payload)
        assertEquals(listOf("ARGUMENTS", "FILES"), cmd.hintsAsStringList)
    }

    @Test
    fun `decodes legacy object hints without throwing`() {
        val payload = """
            {"name":"review","description":"d","hints":{"actions":{"primary":"Run"},"locations":["session"]}}
        """.trimIndent()
        val cmd = json.decodeFromString(CommandInfo.serializer(), payload)
        // Object form has no typed string-list view → null, but decode must not throw.
        assertNull("object hints must not crash and must not pretend to be a string list", cmd.hintsAsStringList)
        assertEquals("review", cmd.name)
    }

    @Test
    fun `decodes command with no hints at all`() {
        val payload = """{"name":"clear"}"""
        val cmd = json.decodeFromString(CommandInfo.serializer(), payload)
        assertNull(cmd.hints)
        assertNull(cmd.hintsAsStringList)
    }

    @Test
    fun `decodes full server command payload with extra unknown fields`() {
        // Real /command entries carry template/source/etc; the rest must be
        // ignored. hints must survive as a raw element.
        val payload = """
            {"name":"init","description":"guided setup","source":"command",
             "template":"...","hints":["ARGUMENTS"],"extraFutureField":42}
        """.trimIndent()
        val cmd = json.decodeFromString(CommandInfo.serializer(), payload)
        assertEquals("init", cmd.name)
        assertEquals(listOf("ARGUMENTS"), cmd.hintsAsStringList)
    }

    @Test
    fun `empty hints array yields null typed view`() {
        val payload = """{"name":"x","hints":[]}"""
        val cmd = json.decodeFromString(CommandInfo.serializer(), payload)
        assertNull(cmd.hintsAsStringList)
    }
}
