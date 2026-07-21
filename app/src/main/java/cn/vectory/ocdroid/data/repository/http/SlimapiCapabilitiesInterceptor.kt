package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.data.repository.HostConfig
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R8 slim-mode foundation / B2 Opt-A: oc-slimapi capability 选入头注入器。
 *
 * **行为**：当 HostConfig.slim == true **且**请求路径以 SlimapiContract.SLIMAPI_PATH_PREFIX
 * 开头时，注入头 `X-Slimapi-Capabilities: mid-partial-envelope=1`。其它情况
 * （legacy 模式或非 slimapi 路径）原样透传——opencode 直连入口不识别该头，注入会
 * 污染请求。
 *
 * **位置**：挂在 OkHttpClientFactory.baseBuilder 的共享拦截器链上（紧接
 * SlimapiVersionInterceptor 之后），所以 REST / SSE / command 三条链路都覆盖
 * （slimapi 端点需能力头以实现 B2 Opt-A partial-envelope 行为）。tunnel / health
 * 不挂本拦截器（无 slimapi 语义）。
 *
 * **门闩覆盖**：同版本头——对所有 /slimapi/ 下路径生效（含 /slimapi/health），
 * 见 design-v2 §9.6。
 *
 * **legacy 不变**：slim=false 时本拦截器是 no-op，请求字节序列与新增本拦截器之前
 * 完全一致；不破坏现有用户的 opencode 直连行为。
 *
 * **Grammar（I-R4-CAP-GRAMMAR）**：值 `mid-partial-envelope=1` 是单个
 * `name=value` token，name 大小写不敏感、无空白。客户端仅此一项；服务器端负责
 * 解析、去重、冲突检测与关闭行为（本拦截器只负责 emit）。
 *
 * @see docs/0.11-ux-first-joint-plan.md §6 R3-B2-NEGOTIATION + I-R5-CAP-DUPLICATES
 */
@Singleton
class SlimapiCapabilitiesInterceptor @Inject constructor(
    private val hostConfig: HostConfig
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
        // 保证单一来源（与 SlimapiVersionInterceptor / AuthInterceptor 同语义）。
        // 客户端 capability 头唯一，调用方无理由自设；防御性 replace 防意外重复。
        val rewritten = original.newBuilder()
            .header(SlimapiContract.X_SLIMAPI_CAPABILITIES, SlimapiContract.MID_PARTIAL_ENVELOPE_CAPABILITY)
            .build()
        return chain.proceed(rewritten)
    }
}
