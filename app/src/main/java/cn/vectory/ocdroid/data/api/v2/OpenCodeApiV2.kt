package cn.vectory.ocdroid.data.api.v2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * §model-selection: minimal v2 Retrofit surface, kept on a SEPARATE interface
 * from the legacy [cn.vectory.ocdroid.data.api.OpenCodeApi] so the message
 * read/send path stays 100% on the legacy `/session/{id}/message` /
 * `/session/{id}/prompt_async` endpoints. The ONLY v2 surface added here is
 * the two endpoints verified against the live opencode 1.17.12 server:
 *
 *  - `GET  /api/model`                          → list of available models
 *  - `POST /api/session/{sessionID}/model`      → switch a session's model
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
 *  - POST /api/session/{id}/model takes `{ "model": { "providerID": "<p>",
 *    "id": "<m>" } }` (nested under `model`; the model id field is `id`, the
 *    provider field is `providerID`) → 204 No Content on success.
 */
interface OpenCodeApiV2 {
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("model")
    suspend fun getModels(): V2Response<List<ModelInfoV2>>

    @Headers("X-Opencode-Skip-Dir: 1")
    @POST("session/{sessionID}/model")
    suspend fun switchModel(
        @Path("sessionID") sessionId: String,
        @Body body: SwitchModelRequest
    ): Response<Unit>
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

@Serializable
data class ModelRefV2(
    val id: String,
    @SerialName("providerID") val providerId: String
)

@Serializable
data class SwitchModelRequest(
    val model: ModelRefV2
)
