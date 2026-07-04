package cn.vectory.ocdroid.data.api.v2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Headers

/**
 * §model-selection: minimal v2 Retrofit surface, kept on a SEPARATE interface
 * from the legacy [cn.vectory.ocdroid.data.api.OpenCodeApi] so the message
 * read/send path stays 100% on the legacy `/session/{id}/message` /
 * `/session/{id}/prompt_async` endpoints. The ONLY v2 surface added here is
 * the endpoint verified against the live opencode 1.17.12 server:
 *
 *  - `GET  /api/model`                          → list of available models
 *
 * (The previous `POST /api/session/{sessionID}/model` switch endpoint was
 * REMOVED to align with the official packages/app V1-per-prompt model — the
 * model is now attached per-prompt via [cn.vectory.ocdroid.data.api.PromptRequest]
 * .model, not switched server-side per session.)
 *
 * Built on its own Retrofit instance rooted at `<baseUrl>/api/` (see
 * [cn.vectory.ocdroid.data.repository.OpenCodeRepository.v2Retrofit]). The
 * same OkHttp `restHttp` client is reused so auth / cache / traffic
 * interceptors apply uniformly — no new OkHttpClient is created.
 *
 * Server contract notes:
 *  - GET /api/model returns `{ "location": {...}, "data": [ {id, providerID,
 *    name, enabled, ...}, ... ] }`. The `location` echo is dropped silently
 *    by the converter factory's `Json { ignoreUnknownKeys = true }`.
 */
interface OpenCodeApiV2 {
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("model")
    suspend fun getModels(): V2Response<List<ModelInfoV2>>
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
    val enabled: Boolean = true
)
