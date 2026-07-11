package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.ConfigProvider
import cn.vectory.ocdroid.data.model.ProviderModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §round-B ④ (scheme G.2): pins the ModelPickerSheet search contract.
 *
 * The previous shell filtered on modelId / model.name only — provider name
 * searches ("anthropic") returned nothing even though the provider header
 * visibly read "Anthropic". The pure helper widens the match to provider
 * id + provider name so the search matches what the user sees, and the
 * Composer ModelPickerSheet hides a provider's header when the helper
 * returns zero matches for it (no more dangling section headers).
 */
class ProviderModelFilterTest {

    private fun provider(
        id: String,
        name: String? = null,
        vararg models: Pair<String, String?>,
    ): ConfigProvider = ConfigProvider(
        id = id,
        name = name,
        models = models.associate { (mid, mname) -> mid to ProviderModel(id = mid, name = mname) },
    )

    // ── blank query (no-search shape) ────────────────────────────────────

    @Test
    fun `blank query returns every model under the provider`() {
        val p = provider("anthropic", "Anthropic", "claude-1" to null, "claude-2" to null)
        val result = filterProviderModels(p, query = "")
        assertEquals(listOf("claude-1", "claude-2"), result.map { it.first })
    }

    @Test
    fun `whitespace-only query is treated as blank`() {
        val p = provider("anthropic", "Anthropic", "claude-1" to null)
        val result = filterProviderModels(p, query = "   ")
        assertEquals(listOf("claude-1"), result.map { it.first })
    }

    // ── matching axes ────────────────────────────────────────────────────

    @Test
    fun `query matches provider id`() {
        val p = provider("anthropic", "Anthropic", "claude-1" to null, "claude-2" to null)
        val result = filterProviderModels(p, query = "anthropic")
        assertEquals(setOf("claude-1", "claude-2"), result.map { it.first }.toSet())
    }

    @Test
    fun `query matches provider name`() {
        val p = provider("anth", "Anthropic Labs", "claude-1" to null, "claude-2" to null)
        val result = filterProviderModels(p, query = "labs")
        assertEquals(setOf("claude-1", "claude-2"), result.map { it.first }.toSet())
    }

    @Test
    fun `query matches model id`() {
        val p = provider("anthropic", "Anthropic",
            "claude-1" to null, "claude-2" to null, "other-model" to null)
        val result = filterProviderModels(p, query = "claude")
        assertEquals(setOf("claude-1", "claude-2"), result.map { it.first }.toSet())
    }

    @Test
    fun `query matches model name`() {
        val p = provider("anthropic", "Anthropic",
            "claude-1" to "Claude Sonnet", "claude-2" to "Claude Haiku")
        val result = filterProviderModels(p, query = "sonnet")
        assertEquals(listOf("claude-1"), result.map { it.first })
    }

    // ── case-insensitivity ───────────────────────────────────────────────

    @Test
    fun `match is case-insensitive across every axis`() {
        val p = provider("Anthropic", "Anthropic",
            "Claude-1" to "Sonnet")
        // provider id
        assertEquals(listOf("Claude-1"), filterProviderModels(p, "anthropic").map { it.first })
        // provider name (identical to id here, but also exercises the name axis)
        assertEquals(listOf("Claude-1"), filterProviderModels(p, "ANTHROPIC").map { it.first })
        // model id
        assertEquals(listOf("Claude-1"), filterProviderModels(p, "CLAUDE-1").map { it.first })
        // model name
        assertEquals(listOf("Claude-1"), filterProviderModels(p, "SONNET").map { it.first })
    }

    // ── zero-match (header-hide contract) ────────────────────────────────

    @Test
    fun `non-match yields empty list so the picker hides the provider header`() {
        val p = provider("anthropic", "Anthropic", "claude-1" to null)
        val result = filterProviderModels(p, query = "gpt")
        assertTrue("non-match must yield empty so header is hidden", result.isEmpty())
    }

    @Test
    fun `provider-name match yields every model even when model fields do not match`() {
        // This is the core Round-B ④ fix — the previous shell would return
        // empty here (model fields don't match) but the provider header
        // would still render above an empty list. The new contract: when
        // the PROVIDER matches, every (non-disabled) model is shown so the
        // user can pick the one they want.
        val p = provider("anthropic", "Anthropic",
            "claude-1" to "Sonnet", "claude-2" to "Haiku", "claude-3" to "Opus")
        val result = filterProviderModels(p, query = "Anthropic")
        assertEquals(3, result.size)
    }

    @Test
    fun `provider with zero models yields empty regardless of query`() {
        val p = ConfigProvider(id = "empty", name = "Empty", models = emptyMap())
        assertTrue(filterProviderModels(p, query = "").isEmpty())
        assertTrue(filterProviderModels(p, query = "empty").isEmpty())
    }
}
