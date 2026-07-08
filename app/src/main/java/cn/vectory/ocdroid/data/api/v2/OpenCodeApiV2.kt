package cn.vectory.ocdroid.data.api.v2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Headers

/**
 * §model-selection / §v2-catalog (P2 2B): minimal v2 Retrofit surface on a
 * SEPARATE interface from the legacy [cn.vectory.ocdroid.data.api.OpenCodeApi]
 * so the message read/send path stays 100% on the legacy `/session/{id}/message`
 * / `/session/{id}/prompt_async` endpoints. Built on its own Retrofit instance
 * rooted at `<baseUrl>/api/` (see
 * [cn.vectory.ocdroid.data.repository.OpenCodeRepository.v2Retrofit]); the same
 * OkHttp `restHttp` client is reused so auth / cache / traffic interceptors
 * apply uniformly — no new OkHttpClient is created.
 *
 * v2 catalog endpoints (verified live on opencode 1.17.15):
 *  - `GET /api/model`    → `{location, data:[{id, providerID, name, enabled,
 *    limit:{context, input?, output}, ...}]}`. `limit` is parsed into
 *    [ModelLimitV2] so the context-limit index can be populated from v2.
 *  - `GET /api/provider` → `{location, data:[{id, name, ...}]}`.
 *
 * Unlike the legacy `GET /config/providers`, these v2 responses do NOT carry
 * provider API keys. [cn.vectory.ocdroid.data.repository.OpenCodeRepository.getProviders]
 * now builds the catalog from these two v2 calls (merging `limit` from
 * /api/model + the provider `name` from /api/provider into the existing
 * `ProvidersResponse` shape), so the key-bearing legacy endpoint is no longer
 * fetched. The `location` echo is dropped silently by the converter factory's
 * `Json { ignoreUnknownKeys = true }`.
 */
interface OpenCodeApiV2 {
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("model")
    suspend fun getModels(): V2Response<List<ModelInfoV2>>

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("provider")
    suspend fun getProviders(): V2Response<List<ProviderInfoV2>>
}

@Serializable
data class V2Response<T>(
    val data: T
)

@Serializable
data class ModelInfoV2(
    val id: String,
    @SerialName("providerID") val providerId: String,
    val name: String,
    val enabled: Boolean = true,
    val limit: ModelLimitV2? = null
)

/**
 * Per-model token limits from `GET /api/model`. `context` + `output` are
 * required by the server schema; `input` is optional. Mapped onto the legacy
 * [cn.vectory.ocdroid.data.model.ProviderModelLimit] by the catalog builder.
 */
@Serializable
data class ModelLimitV2(
    val context: Int,
    @SerialName("input") val input: Int? = null,
    val output: Int
)

/** Provider entry from `GET /api/provider` — only `id` + `name` are consumed
 *  (for picker section headers); other fields (`api`, `request`, ...) are
 *  dropped by `ignoreUnknownKeys`. */
@Serializable
data class ProviderInfoV2(
    val id: String,
    val name: String,
    val disabled: Boolean? = null
)
