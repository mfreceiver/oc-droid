package cn.vectory.ocdroid.data.repository.http

/**
 * R8 slim-mode foundation: oc-slimapi sidecar 客户端契约（M1 版本头 + M2 自检）。
 *
 * **写域边界**：本文件是 slimapi 路由层共享常量的唯一来源。版本头拦截器
 * （SlimapiVersionInterceptor）、health 探针（OpenCodeRepository）与自检逻辑
 * （ServerCompatProfile）都从这里读 SLIMAPI_CLIENT_VERSION 与
 * X_SLIMAPI_VERSION / SLIMAPI_PATH_PREFIX，避免散落字面量漂移（与 HttpHeaders
 * 同模式）。
 *
 * **版本来源**：客户端硬编码 1，与 oc-slimapi 当前 SERVER_API_VERSION=1 +
 * ACCEPTED_CLIENT_VERSIONS=(1,1) 对齐（见 docs/slim-mode-api-routing.md §3）。
 * 未来 slimapi bump major 时同步本常量；旧客户端打新 sidecar 会触发
 * 400 version_incompatible，由 M2 自检的版本区间检查预先标记。
 *
 * 注：避免在 KDoc 中写 `/slimapi/` + `**`（连续），Kotlin lexer 会把 `/**` 解析
 * 为嵌套 KDoc 起始 → 后续 `*/` 提前闭合外层 → "Unclosed comment"。用
 * `/slimapi/<wildcard>` 或单独写 `/slimapi/`。
 */
object SlimapiContract {

    /**
     * HTTP 头名称：每个 slimapi 请求（含 SSE / health）必须携带，值为
     * SLIMAPI_CLIENT_VERSION 的十进制整数（无 v 前缀）。缺头 → slimapi
     * 400 version_required；非整数 → 同；区间外 → 400 version_incompatible。
     */
    const val X_SLIMAPI_VERSION = "X-Slimapi-Version"

    /**
     * 客户端硬编码的 slimapi 协议版本。注入到每个 slimapi 请求的
     * X_SLIMAPI_VERSION 头；同时用于 M2 自检——本值必须落在 sidecar 返回的
     * accepted_client_versions 闭区间内，否则标记为不兼容。
     */
    const val SLIMAPI_CLIENT_VERSION: Int = 1

    /**
     * slimapi 路由前缀。版本头拦截器用 startsWith 匹配——所有以此前缀开头的
     * 请求都注入版本头（含 /slimapi/health 、 /slimapi/ready 、
     * /slimapi/events 等；门闩对所有 /slimapi/ 路径生效，见 design-v2 §9.6）。
     * 注意尾部 /：匹配 /slimapi/health 而非字面路径 /slimapi，避免误命中。
     */
    const val SLIMAPI_PATH_PREFIX = "/slimapi/"

    /**
     * slimapi health 端点相对路径（拼到 baseUrl 后）。M2 自检 / C3 fix 探针：
     * 探 sidecar 自身健康（sidecar.ok）+ 版本契约，不经 catch-all 透传到
     * opencode——所以 sidecar 挂时本探针直接 5xx，不会误报健康（C3 核心）。
     */
    const val SLIMAPI_HEALTH_PATH = "/slimapi/health"

    /**
     * legacy opencode health 端点相对路径。slim=false 时探针走此路径
     * （行为完全不变）。
     */
    const val LEGACY_HEALTH_PATH = "/global/health"

    /**
     * HTTP 头名称：客户端 capability 选入头。服务器端根据此头决定是否启用
     * B2 Opt-A partial-envelope 行为。仅 slimapi 请求携带（门闩同 M1 版本头）。
     */
    const val X_SLIMAPI_CAPABILITIES = "X-Slimapi-Capabilities"

    /**
     * 当前客户端声明的唯一 capability：`mid-partial-envelope=1`（opt-in）。
     * 值遵循逗号分隔的 `name=value` 语法（I-R4-CAP-GRAMMAR），目前仅此一项。
     * 服务器端解析时 name 大小写不敏感、忽略前后空白。
     */
    const val MID_PARTIAL_ENVELOPE_CAPABILITY = "mid-partial-envelope=1"
}

