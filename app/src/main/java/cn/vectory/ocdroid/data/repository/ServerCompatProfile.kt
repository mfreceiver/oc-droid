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
 *
 * ## R8 slim-mode foundation / M2 自检
 *
 * [updateSlimapi] 接收 `GET /slimapi/health` 的解析结果（sidecar.ok +
 * server.api_version + accepted_client_versions + schema.degraded）。本字段集
 * 独立于 opencode 的 semver 字段（上方 [version] / [major] / [minor] / [patch]），
 * 因为 slimapi 是 sidecar 协议层（独立版本节奏），与 opencode semver 解耦。
 *
 * [isSlimapiClientAccepted] 是 M2 自检门：当 [SlimapiContract.SLIMAPI_CLIENT_VERSION]
 * 不落在 [slimapiAcceptedMin] / [slimapiAcceptedMax] 闭区间内时返回 false，
 * ConnectionBootstrap 应据此标记连接不可用（不进入省流模式）。`api_version` 也
 * 带回供 UI 提示（用户能看到 sidecar 期望的版本）。
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

    // ── R8 slim-mode foundation / M2 自检字段 ────────────────────────────
    // 独立于 opencode semver 字段：slimapi sidecar 协议层的版本契约。

    /** slimapi sidecar 的 `server.api_version`，或 null = 未探过 slimapi health。 */
    @Volatile var slimapiServerApiVersion: Int? = null
        private set

    /**
     * slimapi sidecar 的 `accepted_client_versions` 闭区间下界（含）。
     * null = 未探过 / sidecar 未提供该字段。
     */
    @Volatile var slimapiAcceptedMin: Int? = null
        private set

    /** slimapi sidecar 的 `accepted_client_versions` 闭区间上界（含）。 */
    @Volatile var slimapiAcceptedMax: Int? = null
        private set

    /** slimapi sidecar `sidecar.ok`（liveness，不代表 upstream opencode 可达）。 */
    @Volatile var slimapiSidecarOk: Boolean? = null
        private set

    /**
     * slimapi sidecar `schema.degraded`——true 表示 `/slimapi/sessions/{sid}/messages`
     * 自动降级 `mode=full`（失去省流收益但仍可用）。UI 应提示；M2 reducer 据此
     * 强制后续 M8 用 `mode=full`。
     */
    @Volatile var slimapiSchemaDegraded: Boolean? = null
        private set

    /**
     * §Stage-D2: whether the sidecar's `/slimapi/health` advertised
     * `features.tokenStream == true`. The token-stream coordinator gates
     * `open(sid)` on this — false (or unprobed) → zero-regression (no
     * token stream, existing behavior). Updated by [updateSlimapi] on every
     * successful health re-check so a capability flip is picked up without
     * restarting the app.
     */
    @Volatile var slimapiTokenStreamEnabled: Boolean = false
        private set

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
     * R8 slim-mode foundation / M2 自检：解析 `GET /slimapi/health` 响应并
     * 落库 slimapi sidecar 协议层版本契约字段。重复调用幂等。
     *
     * 调用者负责先把 body 解析成 [SlimapiHealthPayload]（解析逻辑放
     * [cn.vectory.ocdroid.data.repository.OpenCodeRepository] 以与
     * [HealthResponse] 同源）。
     */
    fun updateSlimapi(payload: SlimapiHealthPayload) {
        slimapiServerApiVersion = payload.serverApiVersion
        val range = payload.acceptedClientVersions
        slimapiAcceptedMin = range?.first
        slimapiAcceptedMax = range?.second
        slimapiSidecarOk = payload.sidecarOk
        slimapiSchemaDegraded = payload.schemaDegraded
        // §Stage-D2: store the tokenStream feature flag so the coordinator's
        // open/close hooks can gate on it without re-parsing the health body.
        slimapiTokenStreamEnabled = payload.features.tokenStream
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

    /**
     * R8 slim-mode foundation / M2 自检门：客户端硬编码版本
     * ([SlimapiContract.SLIMAPI_CLIENT_VERSION]) 是否落在 sidecar 公告的
     * `accepted_client_versions` 闭区间内。
     *
     * **Fail-closed（与 [isAtLeast] 相反）**：当区间任一端为 null（未探过
     * slimapi health / sidecar 未提供该字段）时返回 **false**——slim 模式下
     * 「未做自检」≠「兼容」，否则 sidecar 静默挂掉时客户端会持续打 slimapi
     * 失败。调用方必须先成功探过 `/slimapi/health` 才能放行（C3 核心）。
     */
    fun isSlimapiClientAccepted(): Boolean {
        val min = slimapiAcceptedMin ?: return false
        val max = slimapiAcceptedMax ?: return false
        val v = SlimapiContract.SLIMAPI_CLIENT_VERSION
        return v in min..max
    }

    private companion object {
        // Greedy leading numeric components separated by dots, stopping at the
        // first non-numeric segment (covers "1.17.13", "1.17.13-rc1",
        // "1.17.13+sha", "v1.17.13"). Each group is \d+ so non-numeric builds
        // don't partially parse into garbage.
        val VERSION_RE = Regex("""v?(\d+)\.(\d+)(?:\.(\d+))?(?:[.\-+]\D.*)?""")
    }
}

/**
 * R8 slim-mode foundation / M2: `GET /slimapi/health` 响应的业务字段抽取
 * （剥原始 JsonObject 解析细节）。由 [cn.vectory.ocdroid.data.repository.OpenCodeRepository]
 * 解析后传入 [ServerCompatProfile.updateSlimapi]。
 *
 * 形状参考 INTERFACE_MAP §4 / docs/slim-mode-api-routing.md §3.2：
 * ```json
 * {
 *   "sidecar": { "ok": true, "version": "0.1.0" },
 *   "schema":   { "degraded": false },
 *   "server":   { "api_version": 1, "accepted_client_versions": [1, 1] }
 * }
 * ```
 *
 * - [serverApiVersion] / [acceptedClientVersions] / [sidecarOk] / [schemaDegraded]
 *   都是可空，因为 sidecar 实现可能缺字段；任一 null 时由
 *   [ServerCompatProfile.isSlimapiClientAccepted] fail-closed。
 * - [acceptedClientVersions] 是 [Pair]<min, max>（闭区间）；服务端可配
 *   `OC_SLIMAPI_ACCEPTED_CLIENT_VERSIONS=min,max`。
 */
data class SlimapiFeatures(
    val tokenStream: Boolean = false
)

data class SlimapiHealthPayload(
    val sidecarOk: Boolean?,
    val schemaDegraded: Boolean?,
    val serverApiVersion: Int?,
    val acceptedClientVersions: Pair<Int, Int>?,
    val features: SlimapiFeatures = SlimapiFeatures()
)

