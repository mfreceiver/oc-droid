package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.repository.http.SlimapiContract
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ③ ServerCompat — central, version-aware profile of the connected opencode
 * server, populated from `GET /global/health`'s `version` field.
 *
 * ## Why this exists
 *
 * ocdroid carries several compatibility shims that were originally written as
 * hardcoded assumptions about a specific server version's *observed* behaviour
 * (e.g. "1.17.11 emits `message.updated` for new messages", "1.17.12 loses
 * in-memory pending questions on restart"). Those are fragile: if the server
 * changes back, the shim breaks the other way; if it changes differently, a new
 * shim must be hand-added. [ServerCompatProfile] is the single entry point that
 * future shim migrations hang capability flags off, so each shim reads a flag
 * instead of guessing a version, and "which server versions are supported"
 * becomes an auditable property rather than scattered folklore.
 *
 * ## Current scope (layer A — scaffolding)
 *
 * This first increment only establishes the entry point: it parses the version
 * string into semver components and exposes an [isAtLeast] helper. No shim
 * consumes a flag yet (the existing shims are either already version-agnostic
 * or restored via tolerant parsing elsewhere). Capability flags will be added
 * here as individual shims are migrated in follow-up increments, each paired
 * with the [cn.vectory.ocdroid.data.api.OpenCodeApi] / controller site that
 * reads them and a version-fixture unit test.
 *
 * ## Population
 *
 * [update] is called from [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator]
 * whenever a health probe succeeds (or surfaces a version while still warming
 * up). Re-parsing the same version is idempotent and cheap, so callers need not
 * guard against redundant updates. Until the first successful health probe,
 * every field stays `null` and [isAtLeast] returns `true` (fail-open: treat an
 * unknown server as the newest, so feature gates default on rather than
 * silently disabling functionality).
 */
@Singleton
class ServerCompatProfile @Inject constructor() {

    /** The raw version string reported by the server (e.g. `"1.17.13"`), or null before first health. */
    @Volatile
    var version: String? = null
        private set

    @Volatile var major: Int? = null
        private set
    @Volatile var minor: Int? = null
        private set
    @Volatile var patch: Int? = null
        private set

    // ── Connection-mode bit ─────────────────────────────────────────────
    // 连接模式位（slim vs legacy），authoritatively = HostConfig.slim。
    // 在 OpenCodeRepository.configure() 原子事务内由 setSlimConnection 写入。

    /**
     * ι-A: 连接模式位。true = 当前 [cn.vectory.ocdroid.data.repository.HostConfig]
     * 指向 oc-slimapi sidecar 入口（省流模式）；false = legacy 直连 opencode。
     *
     * **Authoritative source** = `HostConfig.slim`，由
     * [cn.vectory.ocdroid.data.repository.OpenCodeRepository.configure] 在整条
     * ssl/host/client/readiness 事务**全成功后**写入本字段（受管写点，见
     * [setSlimConnection] 的 I8 扩展注释）。
     *
     * 默认 false（=legacy）。
     *
     * **mode vs readiness 区分**：`slimConnection` 反映"最近一次成功 configure 后
     * live 的连接 mode"，**不是** health/readiness 信号。
     */
    @Volatile var slimConnection: Boolean = false
        private set

    /**
     * ι-A: `slimConnection` 的唯一受管写点（I8 扩展）。
     *
     * **I8 扩展契约**：`ServerCompatProfile` 的既有写点是 `update()`
     * （由 probeSlimapiHealth / checkHealthFor 尾部调用）。本 setter 是
     * **新增的第二类写点**——由 [cn.vectory.ocdroid.data.repository.OpenCodeRepository.configure]
     * 在其 `@Synchronized` monitor 内、**整条 ssl/host/client/readiness 事务全成功后的
     * 成功路径末尾**调用（紧跟 [cn.vectory.ocdroid.data.repository.OpenCodeRepository.completeSlimReconfigure]），
     * 确立连接模式位。写操作与 configure 的 ssl/host/client 原子事务在 **同一 monitor**
     * 下（I5/I6/I7 不变量保持），线程安全。
     *
     * **为何在成功路径末尾**：若 `rebuildClients()` / `completeSlimReconfigure()`
     * 抛异常，`slimConnection` 必须保持先前值（= 仍 live 的旧连接 mode；新 mode 从未
     * live，故不应发布），否则能力模型会误报一个未真正 live 的 mode。这与
     * [cn.vectory.ocdroid.data.repository.OpenCodeRepository.completeSlimReconfigure]
     * 的「readiness 仅每步成功后才发布」纪律同语义。reconfigure 中途（新栈未确认前）
     * L4+ 无锁读 `slimConnection` 看到的始终是"当前仍 live 的 mode"。
     *
     * **可见性语义**：Kotlin `internal` = **同 Gradle module 可见**（非 package-private、
     * 非编译器强制单写点）。OCR.configure() 是唯一**受管**调用方（架构约定，非编译器
     * 强制的 package-private）。L4+（ui/service/coordinator，同 module）理论上能调，
     * 但本 setter 是受管写点，违规调用应被 review/架构测试拦截。
     */
    internal fun setSlimConnection(value: Boolean) {
        slimConnection = value
        // §3.6/§7.11 (P1-7): re-probe the Plan-A endpoint on every reconfigure —
        // a newly-deployed sidecar may now serve it even if a prior 404 cached false.
        supportsSlimStatus = true
    }

    /**
     * ι-A: token-stream 是否可用（slim 连接下始终可用）。
     * 由 [slimConnection] 直接派生，无需侧边探活。
     */
    val tokenStreamEnabled: Boolean get() = slimConnection

    /**
     * 解析 opencode `version` 字符串（e.g. `"1.17.13"` 或 `"1.17.13+abc"`）为
     * semver 分量。容错：缺/短/非数字版本将分量重置为 null，永不抛——
     * malformed 响应不能破坏 profile。
     */
    fun update(value: String?) {
        version = value
        if (value.isNullOrBlank()) {
            major = null; minor = null; patch = null
            return
        }
        // Strip any build metadata / pre-release suffix after the first non-
        // numeric run, then take up to the first three numeric components.
        val parts = VERSION_RE.find(value)?.groupValues
            ?.drop(1)?.filter { it.isNotBlank() } ?: emptyList()
        major = parts.getOrNull(0)?.toIntOrNull()
        minor = parts.getOrNull(1)?.toIntOrNull()
        patch = parts.getOrNull(2)?.toIntOrNull()
    }

    /**
     * True when the connected server is at least [major].[minor].[patch].
     * Fail-open: returns `true` when the version is unknown (see class doc) so
     * feature gates default ON for an unrecognized server rather than silently
     * regressing. Callers that must distinguish "unknown" from "known old"
     * should check [version] == null explicitly.
     */
    fun isAtLeast(major: Int, minor: Int = 0, patch: Int = 0): Boolean {
        val ma = this.major ?: return true
        val mi = this.minor ?: return true
        val pa = this.patch ?: 0
        if (ma != major) return ma > major
        if (mi != minor) return mi > minor
        return pa >= patch
    }

    private companion object {
        // Greedy leading numeric components separated by dots, stopping at the
        // first non-numeric segment (covers "1.17.13", "1.17.13-rc1",
        // "1.17.13+sha", "v1.17.13"). Each group is \d+ so non-numeric builds
        // don't partially parse into garbage.
        val VERSION_RE = Regex("""v?(\d+)\.(\d+)(?:\.(\d+))?(?:[.\-+]\D.*)?""")
    }

    // ── lite-v2-dev retained slimapi probe surface ───────────────────────────
    //
    // 1C 简化了 capability 派生（supportsWatermarkResync 等 → slimConnection），
    // 但 OCR.parseSlimapiHealth / probeSlimapiHealth 仍解析 /slimapi/health 并
    // 构造 [SlimapiHealthPayload]。这部分探活解析在 lite-v2 保留（version 协商
    // observable），故 [SlimapiHealthPayload] / [SlimapiFeatures] / [updateSlimapi]
    // / [isSlimapiClientAccepted] 保留在此。
    @Volatile var slimapiServerApiVersion: Int? = null
        internal set
    @Volatile var slimapiAcceptedMin: Int? = null
        internal set
    @Volatile var slimapiAcceptedMax: Int? = null
        internal set

    /**
     * P0 (B-slim-storm-fix): 是否独立提供 per-session `/slimapi/sessions/{sid}/status`
     * 端点。
     *
     * lite-v2-dev 让 `OpenCodeRepository.getSlimapiSessionStatusOutcome` per-session
     * delegate 到批量端点（`api.getSessionStatus()`），per-session 端点不再独立存在。
     * 此时 `ProcessStatusPoller` 的 slim per-session fan-out 产生 **N 倍重复全量查询**
     * （每个 sid 一次），且 404 检测在 delegate 下失效（缺项被误判为 SessionMissing →
     * `EvictSession` 风暴，而 EvictSession 不收缩 sessionList → snapshot 不收缩 → ∞ 循环）。
     *
     * 默认 `false`（= per-session 不可用 / delegated to bulk）。
     * [StreamingModule] 的 slimFanOutRunner 据此短路 fan-out：status 数据流由 bulk
     * runRefresh（`StatusAggregatorImpl.refresh` → `getSlimapiSessionsStatus`）完整覆盖，
     * stale session 清理由 `session.deleted` digest SSE 事件独立驱动
     * （→ [SessionSyncCoordinator.handleSessionDigest] → `EvictSession`），
     * 不依赖 per-session fan-out（注：`/since` 404 的 `MarkDeleted` reconcile 路径在
     * lite-v2-dev 已 retired，非活路径，故不计入）。**fail-safe**：false = 不 fan-out = 风暴停止。
     *
     * 将来 per-session 端点恢复独立实现时：在 `/slimapi/health` 探测期对此标志置 `true`
     * （对已知 sid 探 `/slimapi/sessions/{sid}/status`，404/`thin_route_not_found`→false，
     * 正常 status→true），并恢复 [StreamingModule] 的真实 fan-out。
     */
    @Volatile var slimPerSessionStatusEndpointAvailable: Boolean = false
        internal set

    /**
     * §3.6 / §7.11 (P1-7): whether the connected oc-slimapi sidecar serves the
     * Plan-A `GET /slimapi/sessions/status` endpoint (with TurnRegistry turn merge).
     *
     * **Fail-open model**: default `true` — attempt the new endpoint first. On the
     * first observed 404 (old v2 sidecar that predates Plan-A), flip to `false`
     * (cached, sticky) and subsequent calls short-circuit to the legacy standard
     * status API (no turn). Transport errors do NOT flip the flag (transient).
     * [setSlimConnection] resets it to `true` on every reconfigure so a newly-
     * deployed sidecar is re-probed.
     *
     * Irrelevant in legacy (non-slim) mode — [getSlimapiSessionsStatus] gates on
     * [slimConnection] first.
     */
    @Volatile var supportsSlimStatus: Boolean = true
        internal set

    /** P1-7: mark the Plan-A slim status endpoint supported (first 200). Sticky. */
    internal fun markSlimStatusSupported() { supportsSlimStatus = true }

    /** P1-7: mark the Plan-A slim status endpoint unsupported (first 404 from old
     *  v2 sidecar). Sticky until [setSlimConnection] resets on reconfigure. */
    internal fun markSlimStatusUnsupported() { supportsSlimStatus = false }

    /** 落库 [SlimapiHealthPayload] 的版本契约业务字段。 */
    fun updateSlimapi(payload: SlimapiHealthPayload) {
        slimapiServerApiVersion = payload.serverApiVersion
        val range = payload.acceptedClientVersions
        slimapiAcceptedMin = range?.first
        slimapiAcceptedMax = range?.second
    }

    /** 客户端版本是否落在 sidecar 公告的接受区间内（fail-closed on null）。 */
    fun isSlimapiClientAccepted(): Boolean {
        val min = slimapiAcceptedMin ?: return false
        val max = slimapiAcceptedMax ?: return false
        return SlimapiContract.SLIMAPI_CLIENT_VERSION in min..max
    }
}

/** /slimapi/health 的 features 子对象（diagnostic-only dual-read）。 */
data class SlimapiFeatures(
    val tokenStream: Boolean = false,
    val thresholdedSkeleton: Boolean = false,
    val skeletonInlineOutputMaxBytes: Int? = null,
)

/** /slimapi/health body 解析结果（[OpenCodeRepository.parseSlimapiHealth] 产出）。 */
data class SlimapiHealthPayload(
    val sidecarOk: Boolean?,
    val schemaDegraded: Boolean?,
    val serverApiVersion: Int?,
    val acceptedClientVersions: Pair<Int, Int>?,
    val features: SlimapiFeatures = SlimapiFeatures(),
)


