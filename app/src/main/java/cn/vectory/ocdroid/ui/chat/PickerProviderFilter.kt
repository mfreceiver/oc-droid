package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.ConfigProvider
import cn.vectory.ocdroid.data.model.ProvidersResponse

/**
 * Pure filter for the model quick-switch picker's provider catalog (§problem:
 * hide providers with no enabled model). Keeps a provider only when it has at
 * least one model whose `"$providerId/$modelId"` key is NOT in [disabledModels]
 * — so a provider whose every model the user disabled no longer renders an
 * empty section header in the picker.
 *
 * The Settings `ModelManagementSection` intentionally does NOT use this: the
 * management surface must list every provider (even fully-disabled ones) so a
 * disabled model can be re-enabled. This filter is picker-only.
 *
 * `disabledModels` keys use the canonical `"$providerId/$modelId"` format also
 * produced/consumed by SettingsManager (see ModelManagementSection /
 * modelCatalogCounts).
 *
 * §R18 Phase 5+ Gate-5 fix (gpter/glmer/momo): this pure top-level fn was
 * previously declared at the bottom of `ChatTopBar.kt`. Because ChatTopBarKt
 * is excluded from kover coverage (the file is ~660 lines of @Composable UI
 * that need ComposeTestRule/androidTest), co-locating this JVM-testable helper
 * there hid its already-written unit test (VisiblePickerProvidersTest) from the
 * coverage metric. Extracting it into this standalone file keeps the helper in
 * the unit-testable coverage set while ChatTopBarKt remains excluded.
 */
internal fun visiblePickerProviders(
    providers: ProvidersResponse?,
    disabledModels: Set<String>
): List<ConfigProvider> =
    providers?.providers.orEmpty()
        .filter { it.models.isNotEmpty() }
        .filter { provider ->
            provider.models.keys.any { modelId -> "${provider.id}/$modelId" !in disabledModels }
        }
