package cn.vectory.ocdroid.data.api.v2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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
 * provider API keys. NOTE: [cn.vectory.ocdroid.data.repository.OpenCodeRepository.getProviders]
 * no longer uses these endpoints — it fetches `/config/providers` (the same
 * source the opencode web model picker uses) because on opencode ≤1.17.x the
 * V2 pair returns a strict subset of the catalog (fewer providers/models than
 * the web shows). These V2 endpoints are now used only by the debug-only
 * [cn.vectory.ocdroid.data.repository.OpenCodeRepository.getModels]. The
 * `location` echo is dropped silently by the converter factory's
 * `Json { ignoreUnknownKeys = true }`.
 */
interface OpenCodeApiV2 {
    // §v2-tolerant-catalog (0.6.1 round-1 fix): the retrofit return type is
    // V2Response<List<JsonElement>>, NOT V2Response<List<ModelInfoV2>>. The
    // previous typed list decode was ATOMIC: a single entry with a TYPE
    // MISMATCH in a nested field (e.g. `limit.context = "abc"` string→Int,
    // `limit.context = {}` object→Int, or an out-of-Int-range number) made
    // the whole List<ModelInfoV2> decode throw → /api/model surfaced as
    // failure → providers=null → "服务器没有可用模型". By decoding each
    // array element as an opaque JsonElement here, the per-entry
    // decodeFromJsonElement<ModelInfoV2> (in
    // [cn.vectory.ocdroid.data.repository.OpenCodeRepository.getProviders])
    // is wrapped in runCatching so a wrong-type entry is SKIPPED + counted
    // instead of nuking the entire catalog. The DTOs below are retained for
    // that per-entry decode.
    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("model")
    suspend fun getModels(): V2Response<List<JsonElement>>

    @Headers("X-Opencode-Skip-Dir: 1")
    @GET("provider")
    suspend fun getProviders(): V2Response<List<JsonElement>>
}

@Serializable
data class V2Response<T>(
    val data: T
)

/**
 * §v2-tolerant-catalog: ALL identity fields are nullable so a single malformed
 * entry (missing id/providerID/name, or a `limit` with a null value) is
 * absorbed as null instead of throwing — the 0.6.0 "服务器没有可用模型"
 * regression (providers stayed null → silent Log.w). The catalog builder
 * ([OpenCodeRepository.getProviders]) drops entries missing id/providerID
 * after the tolerant decode.
 *
 * **What nullable fields + `coerceInputValues` DO absorb** (defense-in-depth,
 * kept even after the 0.6.1 round-1 fix): a missing/null/blank id or
 * providerID; a null `limit.context`/`output`; a missing/null `enabled` (→
 * default true); an unknown enum value on an enum field.
 *
 * **What they do NOT absorb** — and why the 0.6.1 round-1 fix moved to
 * per-entry runCatching decode in [OpenCodeRepository.getProviders]: a TYPE
 * MISMATCH in a nested field (e.g. `"context":"abc"` string→Int,
 * `"context":{}` object→Int, or a number exceeding Int range) is NOT absorbed
 * by `coerceInputValues` (it only coerces null/unknown-enum, not type
 * mismatches) — such an entry would still throw on decode. The per-entry
 * runCatching decode catches that throw per entry, logs + counts the skip, and
 * leaves the rest of the catalog intact (one bad entry no longer nukes the
 * whole list).
 *
 * `enabled` keeps a non-null Boolean default; `coerceInputValues` handles a
 * missing/null value → true.
 */
@Serializable
data class ModelInfoV2(
    val id: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    val name: String? = null,
    val enabled: Boolean = true,
    val limit: ModelLimitV2? = null
)

/**
 * Per-model token limits from `GET /api/model`. All nullable so a malformed
 * limit (non-Int or out-of-Int-range value) is absorbed as null instead of
 * throwing. Mapped onto the legacy
 * [cn.vectory.ocdroid.data.model.ProviderModelLimit] (also all-nullable) by
 * the catalog builder.
 */
@Serializable
data class ModelLimitV2(
    val context: Int? = null,
    @SerialName("input") val input: Int? = null,
    val output: Int? = null
)

/** Provider entry from `GET /api/provider` — only `id` + `name` are consumed
 *  (for picker section headers); other fields (`api`, `request`, ...) are
 *  dropped by `ignoreUnknownKeys`. Nullable for the same tolerant-decode
 *  reason as [ModelInfoV2]. */
@Serializable
data class ProviderInfoV2(
    val id: String? = null,
    val name: String? = null,
    val disabled: Boolean? = null
)
