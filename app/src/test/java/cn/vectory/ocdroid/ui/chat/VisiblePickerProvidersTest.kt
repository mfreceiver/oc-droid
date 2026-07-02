package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.ConfigProvider
import cn.vectory.ocdroid.data.model.ProviderModel
import cn.vectory.ocdroid.data.model.ProvidersResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [visiblePickerProviders] — the pure filter backing the
 * model quick-switch picker (§problem: hide providers with no enabled model).
 *
 * `disabledModels` keys use the canonical `"$providerId/$modelId"` format.
 * Locks the invariant that a provider appears in the picker iff it has at
 * least one non-disabled model; providers whose every model is disabled are
 * dropped (no empty section header).
 */
class VisiblePickerProvidersTest {

    private fun provider(id: String, vararg modelIds: String): ConfigProvider =
        ConfigProvider(
            id = id,
            models = modelIds.associateWith { ProviderModel(id = it) }
        )

    private fun catalog(vararg providers: ConfigProvider): ProvidersResponse =
        ProvidersResponse(providers = providers.toList())

    // ── filtering behaviour ────────────────────────────────────────────────

    @Test
    fun `null providers response yields empty catalog`() {
        assertTrue(visiblePickerProviders(null, emptySet()).isEmpty())
    }

    @Test
    fun `no disabled models keeps all providers with at least one model`() {
        val cat = catalog(
            provider("anthropic", "claude-1", "claude-2"),
            provider("openai", "gpt-4")
        )
        val result = visiblePickerProviders(cat, emptySet())
        assertEquals(listOf("anthropic", "openai"), result.map { it.id })
    }

    @Test
    fun `provider with zero models is dropped even without disabled set`() {
        val cat = catalog(
            provider("anthropic", "claude-1"),
            ConfigProvider(id = "empty", models = emptyMap())
        )
        val result = visiblePickerProviders(cat, emptySet())
        assertEquals(listOf("anthropic"), result.map { it.id })
    }

    @Test
    fun `provider whose every model is disabled is dropped`() {
        val cat = catalog(
            provider("anthropic", "claude-1", "claude-2"),
            provider("openai", "gpt-4")
        )
        // Disable every anthropic model → anthropic fully hidden.
        val disabled = setOf("anthropic/claude-1", "anthropic/claude-2")
        val result = visiblePickerProviders(cat, disabled)
        assertEquals(listOf("openai"), result.map { it.id })
    }

    @Test
    fun `provider with some disabled models is kept when at least one remains`() {
        val cat = catalog(provider("anthropic", "claude-1", "claude-2", "claude-3"))
        val disabled = setOf("anthropic/claude-1", "anthropic/claude-3")
        val result = visiblePickerProviders(cat, disabled)
        assertEquals(listOf("anthropic"), result.map { it.id })
    }

    @Test
    fun `all providers fully disabled yields empty catalog`() {
        val cat = catalog(
            provider("anthropic", "claude-1"),
            provider("openai", "gpt-4")
        )
        val disabled = setOf("anthropic/claude-1", "openai/gpt-4")
        assertTrue(visiblePickerProviders(cat, disabled).isEmpty())
    }

    // ── key-format invariant (locks $provider/$model format) ───────────────

    @Test
    fun `disabled key must match provider id slash model id format`() {
        val cat = catalog(provider("anthropic", "claude-1"))
        // Wrong-format key (missing provider prefix) must NOT match → provider kept.
        val result = visiblePickerProviders(cat, setOf("claude-1"))
        assertEquals(listOf("anthropic"), result.map { it.id })
    }

    @Test
    fun `disabled key for a different provider does not affect this provider`() {
        val cat = catalog(provider("anthropic", "claude-1"))
        // openai/claude-1 is a different provider's model id coincidence.
        val result = visiblePickerProviders(cat, setOf("openai/claude-1"))
        assertEquals(listOf("anthropic"), result.map { it.id })
    }
}
