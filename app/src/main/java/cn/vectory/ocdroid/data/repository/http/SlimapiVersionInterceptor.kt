package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.data.repository.HostConfig
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R8 slim-mode foundation / M1: oc-slimapi 版本头注入器。
 *
 * **行为**：当 HostConfig.slim == true **且**请求路径以 SlimapiContract.SLIMAPI_PATH_PREFIX
 * 开头时，注入头 `X-Slimapi-Version: <SLIMAPI_CLIENT_VERSION>`。其它情况
 * （legacy 模式或非 slimapi 路径）原样透传——opencode 直连入口不识别该头，注入会
 * 污染请求。
 *
 * **位置**：挂在 OkHttpClientFactory.baseBuilder 的共享拦截器链上（在
 * DirectoryHeaderInterceptor 之后），所以 REST / SSE / command 三条链路都覆盖
 * （slimapi 端点 /slimapi/events 是 SSE，必须经此拦截器）。tunnel / health 一次性
 * 探针不挂本拦截器（health 探针在 OpenCodeRepository.checkHealthFor /
 * captureServerCert 中显式带版本头；tunnel 是前置 form 认证，无 slimapi 语义）。
 *
 * **门闩覆盖**：版本头门闩拦所有 /slimapi/ 下路径（含 /slimapi/health），见
 * design-v2 §9.6——本拦截器对 health 探针同样生效（不像某些网关把 health 排除
 * 在门闩外）。M2 自检流程因此能复用同一契约。
 *
 * **legacy 不变**：slim=false 时本拦截器是 no-op，请求字节序列与新增本拦截器之前
 * 完全一致；不破坏现有用户的 opencode 直连行为。
 */
@Singleton
class SlimapiVersionInterceptor @Inject constructor(
    val hostConfig: HostConfig
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // 双门闩：profile 必须 slim=true AND 路径必须 /slimapi/ 前缀。
        // 任意一者不满足 → 原样透传（legacy opencode 不识别该头）。
        if (!hostConfig.slim) return chain.proceed(original)
        if (!original.url.encodedPath.startsWith(SlimapiContract.SLIMAPI_PATH_PREFIX)) {
            return chain.proceed(original)
        }
        // 用 .header(name, value) 而非 .addHeader —— 替换任何调用方可能已设的值，
        // 保证单一来源（与 AuthInterceptor 同语义）。客户端硬编码版本唯一，调用方
        // 无理由自设；防御性 replace 防意外重复。
        val rewritten = original.newBuilder()
            .header(SlimapiContract.X_SLIMAPI_VERSION, SlimapiContract.SLIMAPI_CLIENT_VERSION.toString())
            .build()
        return chain.proceed(rewritten)
    }
}

