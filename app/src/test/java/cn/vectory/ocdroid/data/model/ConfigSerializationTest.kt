package cn.vectory.ocdroid.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + computed-property coverage for [HealthResponse],
 * [ProvidersResponse], [ConfigProvider], [ProviderModel],
 * [ProviderModelLimit], [DefaultProvider]. Pure kotlinx.serialization.
 */
class ConfigSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ── HealthResponse ────────────────────────────────────────────────────

    @Test
    fun `HealthResponse round trip with version`() {
        val health = HealthResponse(healthy = true, version = "1.2.3")
        val encoded = json.encodeToString(health)
        val decoded = json.decodeFromString<HealthResponse>(encoded)
        assertEquals(health, decoded)
    }

    @Test
    fun `HealthResponse minimal defaults version null`() {
        val decoded = json.decodeFromString<HealthResponse>("{" + "\"healthy\":false}")
        assertEquals(false, decoded.healthy)
        assertNull(decoded.version)
    }

    // ── ProvidersResponse ─────────────────────────────────────────────────

    @Test
    fun `ProvidersResponse round trip with defaults map`() {
        val resp = ProvidersResponse(
            providers = listOf(
                ConfigProvider(id = "openai", name = "OpenAI", models = mapOf("gpt" to ProviderModel(id = "gpt", name = "GPT")))
            ),
            defaultByProvider = mapOf("openai" to "gpt")
        )
        val encoded = json.encodeToString(resp)
        // @SerialName("default") on defaultByProvider.
        assertTrue(encoded.contains("\"default\":"))
        val decoded = json.decodeFromString<ProvidersResponse>(encoded)
        assertEquals(1, decoded.providers.size)
        assertEquals("openai", decoded.providers[0].id)
        assertEquals("GPT", decoded.providers[0].models["gpt"]?.name)
        assertEquals("gpt", decoded.default?.modelId)
    }

    @Test
    fun `ProvidersResponse default empty when defaultByProvider empty`() {
        val resp = ProvidersResponse(providers = emptyList(), defaultByProvider = emptyMap())
        assertNull(resp.default)
    }

    @Test
    fun `ProvidersResponse default picks first entry from defaultByProvider`() {
        val resp = ProvidersResponse(
            providers = emptyList(),
            defaultByProvider = linkedMapOf("anthropic" to "claude", "openai" to "gpt")
        )
        val def = resp.default
        assertNotNull(def)
        assertEquals("anthropic", def?.providerId)
        assertEquals("claude", def?.modelId)
    }

    @Test
    fun `ProvidersResponse parses server JSON with providers and defaults`() {
        val decoded = json.decodeFromString<ProvidersResponse>(
            """
            {"providers":[{"id":"mistral","name":"Mistral","models":{"m":{"id":"m","name":"M"}}}],
             "default":{"mistral":"m"}}
            """.trimIndent()
        )
        assertEquals(1, decoded.providers.size)
        assertEquals("mistral", decoded.providers[0].id)
        assertEquals("M", decoded.providers[0].models["m"]?.name)
        assertEquals("m", decoded.default?.modelId)
    }

    // ── ConfigProvider ────────────────────────────────────────────────────

    @Test
    fun `ConfigProvider minimal round trip with defaults`() {
        val provider = ConfigProvider()
        val decoded = json.decodeFromString<ConfigProvider>(json.encodeToString(provider))
        assertEquals("", decoded.id)
        assertNull(decoded.name)
        assertEquals(0, decoded.models.size)
    }

    @Test
    fun `ConfigProvider parses server JSON without name`() {
        val decoded = json.decodeFromString<ConfigProvider>(
            """{"id":"anthropic","models":{}}"""
        )
        assertEquals("anthropic", decoded.id)
        assertNull(decoded.name)
        assertEquals(0, decoded.models.size)
    }

    // ── ProviderModel ─────────────────────────────────────────────────────

    @Test
    fun `ProviderModel round trip with limit`() {
        val model = ProviderModel(
            id = "claude-3",
            name = "Claude 3",
            providerId = "anthropic",
            limit = ProviderModelLimit(context = 200000, input = 100000, output = 100000)
        )
        val encoded = json.encodeToString(model)
        assertTrue(encoded.contains("\"providerID\":\"anthropic\""))
        val decoded = json.decodeFromString<ProviderModel>(encoded)
        assertEquals(model, decoded)
    }

    @Test
    fun `ProviderModel resolvedProviderId prefers providerID over providerId`() {
        val m1 = ProviderModel(providerId = "anthropic", providerIdAlt = "ignore-me")
        assertEquals("anthropic", m1.resolvedProviderId)
        val m2 = ProviderModel(providerId = null, providerIdAlt = "fallback")
        assertEquals("fallback", m2.resolvedProviderId)
        val m3 = ProviderModel()
        assertNull(m3.resolvedProviderId)
    }

    @Test
    fun `ProviderModel parses legacy server JSON using providerId camelCase`() {
        val decoded = json.decodeFromString<ProviderModel>(
            """{"id":"x","name":"X","providerId":"from-camel"}"""
        )
        assertEquals("from-camel", decoded.providerIdAlt)
        assertEquals("from-camel", decoded.resolvedProviderId)
    }

    @Test
    fun `ProviderModelLimit round trip`() {
        val limit = ProviderModelLimit(context = 1, input = 2, output = 3)
        val decoded = json.decodeFromString<ProviderModelLimit>(json.encodeToString(limit))
        assertEquals(limit, decoded)
    }

    @Test
    fun `ProviderModelLimit defaults nulls`() {
        val decoded = json.decodeFromString<ProviderModelLimit>("{}")
        assertNull(decoded.context)
        assertNull(decoded.input)
        assertNull(decoded.output)
    }

    // ── DefaultProvider ───────────────────────────────────────────────────

    @Test
    fun `DefaultProvider round trip`() {
        val dp = DefaultProvider(providerId = "openai", modelId = "gpt-4")
        val decoded = json.decodeFromString<DefaultProvider>(json.encodeToString(dp))
        assertEquals(dp, decoded)
    }
}
