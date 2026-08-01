package cn.vectory.ocdroid.data.model

import androidx.compose.runtime.Stable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement

@Serializable
data class HealthResponse(
    val healthy: Boolean,
    val version: String? = null
)

// §req1-stability: @Stable 显式断言「调用方不会 mutate 这些集合」。这两个 data
// class 经 settingsFlow distinctUntilChanged 读取后不 mutate，Compose 默认视
// List/Map 字段为不稳定会导致 ModelPickerSheet 不可跳过；加 @Stable 配合 PickerSheets
// 内的 remember 后，Compose 编译器可把 ModelPickerSheet 标记为可跳过，减少无关重组。
// 注意：ProviderModel（两个 String? 字段）编译器已推断为稳定，不加。
@Stable
@Serializable
data class ProvidersResponse(
    val providers: List<ConfigProvider> = emptyList(),
    @SerialName("default") val defaultByProvider: Map<String, String> = emptyMap()
) {
    /** First default provider/model when API returns Map<providerId, modelId>. */
    val default: DefaultProvider?
        get() = defaultByProvider.entries.firstOrNull()?.let {
            DefaultProvider(providerId = it.key, modelId = it.value)
        }
}

@Stable
@Serializable
data class ConfigProvider(
    val id: String = "",
    val name: String? = null,
    val models: Map<String, ProviderModel> = emptyMap()
)

@Serializable
data class ProviderModel(
    val id: String = "",
    val name: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    @SerialName("providerId") val providerIdAlt: String? = null,
    val limit: ProviderModelLimit? = null
) {
    val resolvedProviderId: String? get() = providerId ?: providerIdAlt
}

@Serializable
data class ProviderModelLimit(
    val context: Int? = null,
    val input: Int? = null,
    val output: Int? = null
)

@Serializable
data class DefaultProvider(
    val providerId: String,
    val modelId: String
)
