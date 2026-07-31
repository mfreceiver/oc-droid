package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.data.repository.HostConfig
import cn.vectory.ocdroid.data.repository.HostSnapshot
import okhttp3.Interceptor
import okhttp3.Response

/**
 * §C5 (oc-slimapi turn-token contract §6.2 method A): serverGroupFp passthrough
 * header injector, parallel to [ClientIdentityInterceptor].
 *
 * **行为**：当 HostConfig.slim == true **且**请求路径以
 * [SlimapiContract.SLIMAPI_PATH_PREFIX] 开头时，注入一个 header：
 *  - `X-Ocdroid-Server-Group-Fp` = [serverGroupFpProvider]()（请求时捕获的 fp）
 *
 * 双门闩（与 [ClientIdentityInterceptor] / [SlimapiVersionInterceptor] 完全对称）：
 * profile 必须 slim=true AND 路径必须 `/slimapi/` 前缀。任一不满足 → 原样透传，
 * 绝不向 legacy opencode 端点（catch-all）泄露 serverGroupFp。
 *
 * **位置**：与 [ClientIdentityInterceptor] 并列挂在
 * [OkHttpClientFactory.baseBuilder] 的共享拦截器链 + [OkHttpClientFactory.tokenStreamClient]
 * 上，覆盖 REST / SSE / mutation / command / token-stream 全链路。
 *
 * **设计理由**（契约 §6.2）：slimapi 需要 ocdroid 的 authoritative serverGroupFp
 * 来 stamp 每个 (turnIncarnation, turn) 的 scope key，而不必自行重新派生
 * HostProfile.serverGroupFp / "manual:\<url\>" 算法。本拦截器通过请求时注入，
 * 让 slimapi 在每个 /slimapi/ 请求上直接读到 fp。
 *
 * **请求时捕获语义**：[serverGroupFpProvider] 是 lazy `() -> String` 回调，
 * 由 [cn.vectory.ocdroid.data.repository.OpenCodeRepository] 注入（经
 * [cn.vectory.ocdroid.data.repository.RepositoryNetworkGraph]）。它读
 * `hostProfileStore.currentProfile().serverGroupFp` 在请求时（request time）
 * 而非拦截器构造时——因此 host 切换后下一个请求立即携带新 fp（无 TOCTOU）。
 * 此模式与 [ClientIdentityInterceptor] 的 `clientIdProvider` 完全对称。
 *
 * **覆盖缺口**（契约 §4.5 / §B3）：health/cert/ready 一次性探针
 * （`probeSlimapiHealth` / `checkHealthFor` / `captureServerCert`）绕过
 * baseBuilder，不经本拦截器。它们不是 turn-generating 请求，契约仅要求
 * prompt/abort 覆盖最低限——本共享拦截器已覆盖 REST/SSE/mutation/command/
 * token-stream（含 prompt/abort），足够满足契约要求。
 *
 * **部署约束**（契约 §9）：C5 应通过进程重启/incarnation-bump 发布，以避免
 * 同 incarnation 内的 header 混合窗口（同一进程内旧请求与新请求可能混合发送
 * 不同 fp 的头）。此为部署约束，非代码强制。
 *
 * **替换语义**：使用 `.header(name, value)`（替换语义，与
 * [ClientIdentityInterceptor] / [SlimapiVersionInterceptor] / [AuthInterceptor]
 * 一致），保证单一来源、防重复。
 */
class ServerGroupFpInterceptor internal constructor(
    internal val hostSnapshot: HostSnapshot,
    internal val serverGroupFpProvider: () -> String,
) : Interceptor {

    /**
     * Compatibility constructor: captures [hostConfig]'s snapshot (never
     * retains the mutable holder) and defaults the fp to empty string
     * (header injected with empty value; /slimapi/ paths still carry the
     * header). Used by direct unit tests that don't need a live fp.
     */
    constructor(hostConfig: HostConfig) : this(hostConfig.snapshot(), { "" })

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // 双门闩（与 ClientIdentityInterceptor / SlimapiVersionInterceptor 对称）：
        // slim=true AND /slimapi/ 前缀。任一不满足 → 原样透传。
        if (!hostSnapshot.slimHost) return chain.proceed(original)
        if (!original.url.encodedPath.startsWith(SlimapiContract.SLIMAPI_PATH_PREFIX)) {
            return chain.proceed(original)
        }
        // 请求时捕获 fp（request-time capture，非构造时）。
        val fp = serverGroupFpProvider()
        // .header(name, value) 替换语义——与 ClientIdentityInterceptor /
        // SlimapiVersionInterceptor / AuthInterceptor 同语义。
        val rewritten = original.newBuilder()
            .header(SlimapiContract.X_OCDROID_SERVER_GROUP_FP, fp)
            .build()
        return chain.proceed(rewritten)
    }
}
