package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.data.repository.HostConfig
import cn.vectory.ocdroid.data.repository.HostSnapshot
import okhttp3.Interceptor
import okhttp3.Response

/**
 * §B (slimapi-v2-adapt-traffic-plan §B): additive client-identity header
 * injector, parallel to [SlimapiVersionInterceptor].
 *
 * **行为**：当 HostConfig.slim == true **且**请求路径以
 * [SlimapiContract.SLIMAPI_PATH_PREFIX] 开头时，注入 3 个 additive 头：
 *  - `X-Client-Name` = "ocdroid"（常量）
 *  - `X-Client-Version` = `BuildConfig.VERSION_NAME`（git 派生）
 *  - `X-Client-Id` = 设备 id（[clientIdProvider]，UUIDv4 持久化，可选用户覆盖）
 *
 * 双门闩（与 [SlimapiVersionInterceptor] 完全对称）：profile 必须 slim=true
 * AND 路径必须 `/slimapi/` 前缀。任一不满足 → 原样透传，绝不向 legacy
 * opencode 端点（catch-all）泄露客户端身份。
 *
 * **位置**：与 [SlimapiVersionInterceptor] 并列挂在
 * [OkHttpClientFactory.baseBuilder] 的共享拦截器链 + [OkHttpClientFactory.tokenStreamClient]
 * 上，覆盖 REST / SSE / mutation / command / token-stream 全链路。
 *
 * **覆盖缺口**（§B3）：health/ready/cert 一次性探针（`probeSlimapiHealth` /
 * `checkHealthFor` / `captureServerCert`）绕过 baseBuilder，不经本拦截器。
 * 它们显式调用共享 helper [applyClientIdentityHeaders] 注入同一套头
 * （与版本头现有手动注入模式一致），见各探针实现。
 *
 * **per-header 容忍**：每个值发前 sanitize（[sanitizeClientIdentityHeaderValue]），
 * 非法值仅省略该头（不省略其它两个）。`X-Client-Id` 在设备 id 缺失/非法时
 * 独立省略——`X-Client-Name` / `X-Client-Version` 仍发送（additive +
 * 向后兼容：sidecar 缺头仍工作）。
 *
 * **设备 id 解析**：[clientIdProvider] 是 lazy `() -> String?` 回调，由
 * [cn.vectory.ocdroid.data.repository.OpenCodeRepository] 注入（经
 * [cn.vectory.ocdroid.data.repository.RepositoryNetworkGraph]）。它读
 * `ClientIdStore`（Hilt 字段注入，ESP 持久化）——拦截器构造时 store 可能
 * 尚未初始化（graph 是 field initializer），故 lazy 到请求时解析（与
 * `OpenCodeRepository.identityStore` 的 fallback 模式对称）。
 *
 * **additive / 向后兼容**：本拦截器是 additive 头注入，不改 wire 协议；
 * sidecar 在头缺失时仍工作（§B "no client version bump; sidecar works if
 * headers absent"）。legacy 模式（slim=false）下是 no-op，请求字节序列与
 * 新增本拦截器之前完全一致。
 */
class ClientIdentityInterceptor internal constructor(
    internal val hostSnapshot: HostSnapshot,
    internal val clientIdProvider: () -> String?,
) : Interceptor {

    /**
     * Compatibility constructor: captures [hostConfig]'s snapshot (never
     * retains the mutable holder) and defaults the device id to absent
     * (X-Client-Id omitted; X-Client-Name / X-Client-Version still injected
     * on `/slimapi/` paths). Used by direct unit tests that don't need a
     * live device id.
     */
    constructor(hostConfig: HostConfig) : this(hostConfig.snapshot(), { null })

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // 双门闩（与 SlimapiVersionInterceptor 对称）：slim=true AND /slimapi/ 前缀。
        // 任意一者不满足 → 原样透传，绝不向 legacy/catch-all 请求泄露 identity。
        if (!hostSnapshot.slimHost) return chain.proceed(original)
        if (!original.url.encodedPath.startsWith(SlimapiContract.SLIMAPI_PATH_PREFIX)) {
            return chain.proceed(original)
        }
        // 共享 helper（与 health/cert 探针同源）：sanitize + 注入 3 头。
        // 用 .header(name, value)（替换语义）——与 SlimapiVersionInterceptor /
        // AuthInterceptor 同语义，保证单一来源、防重复。
        val rewritten = original.newBuilder().apply {
            applyClientIdentityHeaders(this, clientIdProvider())
        }.build()
        return chain.proceed(rewritten)
    }
}
