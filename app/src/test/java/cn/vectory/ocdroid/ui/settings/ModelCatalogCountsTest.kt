package cn.vectory.ocdroid.ui.settings

import cn.vectory.ocdroid.data.model.ConfigProvider
import cn.vectory.ocdroid.data.model.ProviderModel
import cn.vectory.ocdroid.data.model.ProvidersResponse
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §model-management: JVM unit test for [modelCatalogCounts].
 *
 * Locks the `"${provider.id}/$modelId"` key-format invariant that the
 * v0.1.2 ModelManagementSection bug broke (the old `"$provider.id/$modelId"`
 * produced a literal `.id` after the variable → never matched a real disabled
 * entry → switches appeared dead). The pure function extracted from the
 * Composable lets this run on the JVM without Compose / device.
 */
class ModelCatalogCountsTest {

    private fun provider(
        id: String,
        vararg models: Pair<String, String> // (modelKey, displayName)
    ): ConfigProvider = ConfigProvider(
        id = id,
        name = id,
        models = models.associate { (key, name) -> key to ProviderModel(id = key, name = name) }
    )

    @Test
    fun `null providers returns zero counts`() {
        val (disabled, total) = modelCatalogCounts(providers = null, disabledModels = emptySet())
        assertEquals(0, disabled)
        assertEquals(0, total)
    }

    @Test
    fun `empty providers returns zero counts`() {
        val (disabled, total) = modelCatalogCounts(
            providers = ProvidersResponse(providers = emptyList()),
            disabledModels = emptySet()
        )
        assertEquals(0, disabled)
        assertEquals(0, total)
    }

    @Test
    fun `providers with models and empty disabled set returns zero disabled and full total`() {
        val providers = ProvidersResponse(
            providers = listOf(
                provider("p1", "m1" to "M1", "m2" to "M2"),
                provider("p2", "m1" to "M1")
            )
        )
        val (disabled, total) = modelCatalogCounts(providers, disabledModels = emptySet())
        assertEquals(0, disabled)
        assertEquals(3, total)
    }

    @Test
    fun `disabled key matching provider-id slash model-id is counted`() {
        // This is the regression guard: the key format MUST be
        // "${provider.id}/$modelId". The v0.1.2 bug used "$provider.id/..."
        // (literal `.id`) so a real disabled entry was never matched.
        val providers = ProvidersResponse(
            providers = listOf(provider("p1", "m1" to "M1", "m2" to "M2"))
        )
        val (disabled, total) = modelCatalogCounts(
            providers,
            disabledModels = setOf("p1/m2")
        )
        assertEquals(1, disabled)
        assertEquals(2, total)
    }

    @Test
    fun `multiple providers with mixed disabled`() {
        val providers = ProvidersResponse(
            providers = listOf(
                provider("p1", "m1" to "M1", "m2" to "M2", "m3" to "M3"),
                provider("p2", "m1" to "M1", "m2" to "M2")
            )
        )
        // Disable p1/m2, p1/m3, p2/m1 — mix across providers.
        val (disabled, total) = modelCatalogCounts(
            providers,
            disabledModels = setOf("p1/m2", "p1/m3", "p2/m1")
        )
        assertEquals(3, disabled)
        assertEquals(5, total)
    }

    @Test
    fun `disabled key that does not match any real model is not counted`() {
        // Defensive: a stale / hand-edited disabled entry that no longer maps
        // to a model in the catalog must not inflate the disabled count.
        val providers = ProvidersResponse(
            providers = listOf(provider("p1", "m1" to "M1"))
        )
        val (disabled, total) = modelCatalogCounts(
            providers,
            // p1/m1 is real; p1/ghost and p-other/m1 don't exist in catalog.
            disabledModels = setOf("p1/m1", "p1/ghost", "p-other/m1")
        )
        assertEquals(1, disabled)
        assertEquals(1, total)
    }

    @Test
    fun `provider with empty models map does not contribute to total`() {
        // A provider with zero models is filtered out of the catalog before
        // counting — the summary must not say "1 model" when there are none.
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(id = "empty", name = "empty", models = emptyMap()),
                provider("p1", "m1" to "M1")
            )
        )
        val (disabled, total) = modelCatalogCounts(providers, disabledModels = emptySet())
        assertEquals(0, disabled)
        assertEquals(1, total)
    }

    @Test
    fun `same model id under different providers is keyed independently`() {
        // Two providers can each expose a model with id "m1"; the disabled
        // key is provider-scoped so disabling p1/m1 must not also disable
        // p2/m1.
        val providers = ProvidersResponse(
            providers = listOf(
                provider("p1", "m1" to "M1"),
                provider("p2", "m1" to "M1")
            )
        )
        val (disabled, total) = modelCatalogCounts(
            providers,
            disabledModels = setOf("p1/m1")
        )
        assertEquals(1, disabled)
        assertEquals(2, total)
    }
}
