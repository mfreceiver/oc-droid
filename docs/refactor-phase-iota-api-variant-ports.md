# Phase ι — 双 API 变体：域端口 + 双实现（legacy / slim 策略化）

> 生成：2026-07-23。定位：**θ 之后**的独立**结构相**（非纯行为保持——引入接口/实现拆分，须单独 gate）。
> 前置：`docs/refactor-optimization-plan.md`（α→θ 权威序）+ `docs/refactor-handoff.md`（L4a0 不变量 I1–I22）。
> 目标：把 REST 侧散落的 `if (isSlimMode) { … } else { … }` 收敛为 **一个域端口接口 + 两个专有实现（Standard / Slim）**，装配期选一次；对齐 SSE 侧已验证的 `SseEventHandler` + `Legacy/SlimSseHandler` + `ModeDomain` 形态。
> 决策（用户拍板 2026-07-23）：**Q1 = 采纳域端口+双实现**；**Q2 = 拆 `OpenCodeApi` → `StandardApi`+`SlimApi`（做，严格）**；**Q3 = `isSlimMode` 严格收敛，L4+（协调/service/UI）零命中，改语义 capability**。

---

## §0 为什么是 ι 而非塞进 ζ-3/η

- 重构主线（α→θ）是**纯行为保持型**（只移动/抽取，不改 wire/签名/逻辑）。域端口+双实现**改变了内部调用结构**（方法体分支 → 多态派发），属于结构性变更，不能混进"纯移动"波次的 gate 口径。
- ζ-3/L4a3 已是**高风险单 lane 串行收尾**（动 OCR 上帝文件 + slim 轴收敛）。叠结构性抽象 = 同时打开"行为保持"与"结构变更"两类风险，命中计划 §3「风险放大组合，避免」。
- 故 ι **必须在 θ 完成后启动**。ι 依赖 ζ-3 的产出（域 Repository 已拆分、slim 分支已收敛去重）作为**干净的接手面**——分支越少越集中，端口化 diff 越小。

---

## §1 终态形态（ι 完成后）

### 1.1 层次
```
L0 传输原语（通用，不动）      OkHttpClientFactory / SSL·TOFU / 拦截器 / Auth / Traffic
        │  （拦截器读 hostConfig.slim 按路径注入头——叶子级、不上浮，保留）
L1 Wire/API 定义（专有，拆分） StandardApi（/session·/global·/file·/vcs·/question·…）
                              SlimApi（/slimapi/…） + slim 专属 DTO 随迁
L2 域端口（通用接口）          SessionSource / MessageSource / StatusSource / InteractionSource / HealthProbe
   域实现（专有，双份）        Standard*Source ‖ Slim*Source（各自内部无 if isSlimMode）
        │  （Slim* 共享注入 slimStateLock / SlimSseStateMachine / serverCompatProfile）
L3 Repository 门面（通用，薄） OpenCodeRepository compat 门面持 source 束，纯委托，FQN 不变（I20）
L4 协调/service（通用，模式盲） isSlimMode 零命中；差异 → capability 只读查询
L5 ViewModel/UI（通用，模式盲） 版本/能力从 ServerCompatProfile / ConnectionCapabilities 只读取
```

### 1.2 域端口示例（以 session 域为准）
```kotlin
// L2 端口：域语言，签名内无 slim/legacy 字眼（watermark/since-ts/routeToken 藏进实现）
interface SessionSource {
    suspend fun getSessions(limit: Int?): Result<List<Session>>
    suspend fun getSessionsForDirectory(directory: String, limit: Int?): Result<List<Session>>
}

// L2 专有实现 A：只有 legacy 逻辑
internal class StandardSessionSource(private val api: OpenCodeApi /* StandardApi 后 */) : SessionSource {
    override suspend fun getSessions(limit: Int?) =
        runSuspendCatching { api.getSessions(limit) }
}

// L2 专有实现 B：只有 slim 逻辑（identity-adapter 收敛后 mapCatching 内联）
internal class SlimSessionSource(private val api: OpenCodeApi /* SlimApi 后 */) : SessionSource {
    override suspend fun getSessions(limit: Int?) =
        getSlimapiSessions(directories = null, roots = null, limit = limit)
            .mapCatching { it.sessions }
}
```

### 1.3 唯一选择点（与 SSE 的 ModeDomain 同构）
```kotlin
// 仍在 configure() 原子换栈内选一次（守 I6/I7）；之后门面纯委托
sessionSource = if (slim) SlimSessionSource(...) else StandardSessionSource(...)
// OpenCodeRepository.getSessions() → 变为 sessionSource.getSessions(limit)（零 if）
```

---

## §2 Q2（严格）— 拆 `OpenCodeApi` → `StandardApi` + `SlimApi`

现状：`data/api/OpenCodeApi.kt`（689 行）单接口含 legacy(~34) + slim(cluster A ~9) 方法。

**严格方案**：
- 新 `data/api/StandardApi.kt` — 承接 `/session`·`/global`·`/config`·`/agent`·`/command`·`/file`·`/vcs`·`/find`·`/question`·`/permission` 全部 legacy 方法 + 其专属 request DTO（`CreateSessionRequest`/`PromptRequest`/`CommandRequest`/…）。
- 新 `data/api/SlimApi.kt` — 承接全部 `/slimapi/…` 方法 + slim 专属 DTO（`SlimapiQuestionReplyRequest`/`SlimapiQuestionRejectRequest`/`SlimapiPermissionResponseRequest`；聚合信封 `SlimapiMessageFullBatch` 等留 `data/model/`）。
- **两个独立 Retrofit 实例**：OCR 内 `api: StandardApi`（legacy 束用）+ `slimApi: SlimApi`（slim 束用），共用同一 OkHttp 链（拦截器仍按 `/slimapi/` 前缀注入版本/capability 头，L0 不变）。
- **I20 兼容守护**：`OpenCodeApi` 类型若仍被上游/测试 FQN 引用，保留 `typealias`（或空壳保位）指向拆分后主体，直至确认零外部引用再删。**接口拆分不得引发上游 import churn**——调用统一经 L2 端口，上游不直接 import `StandardApi`/`SlimApi`。

**收益**：接口面可审计（一眼分辨标准/精简）、不会误调、两侧独立演进（slim bump 版本不牵动 legacy）。
**成本控制**：置于 ι 内子波 **ι-1**，纯机械搬迁（fixer-zlm 可胜任），单测全绿即门。

---

## §3 Q3（严格）— `isSlimMode` 硬收敛，L4+ 零命中

**验收标准（硬）**：`isSlimMode` / `hostConfig.slim` 的读点，重构后**只允许出现在 L2 域实现选择（configure）+ L0 拦截器叶子**。L4+（协调层 / service / UI）**一处不许有**。

**当前上浮点（须清零，全仓 grep 命中）**：
| 文件 | 层 | 收敛手段 |
|---|---|---|
| `ui/controller/SessionSyncCoordinator.kt`（`isSlimMode` thunk @187、`slimMode()` @1058、reconcile 门 @1091/1583/1664…） | L4 协调 | 差异下沉进 `MessageSource`/reconcile source；真编排差异 → `capabilities.supportsWatermarkResync` |
| `service/status/StatusAggregatorImpl.kt`（@269 分支） | service | 下沉进 `StatusSource`（bulk vs 扇出两实现） |
| `service/status/SlimStatusFanOut.kt` | service | 归 `SlimStatusSource` 内部实现细节 |
| `service/SessionStreamingService.kt`（@358 onResync 门） | service | 换 `capabilities.supportsTokenStreamResync` |
| `service/streaming/StreamingModule.kt`（@125 runner 门） | service | 同上 capability |
| `ui/SessionListActions.kt`（@646/659/1059/1116） | UI 动作 | 下沉进 `SessionSource`/`StatusSource`；SWEEP 差异 → capability |
| `ui/ConnectionViewModel.kt`（@190 版本 banner） | UI | 保留——读 `ServerCompatProfile` 只读模型（本就是数据上浮，合规） |

**capability 只读模型**：扩展现有 `ServerCompatProfile`（守 I8 写点不变）或新增 `ConnectionCapabilities` 值对象，暴露语义布尔（`supportsWatermarkResync` / `supportsTokenStreamResync` / `supportsBulkStatus`），**禁止**暴露裸 `slim: Boolean` 给 L4+。

**豁免清单（非违规，明列以防误判）**：
- L0 `SlimapiVersionInterceptor` / `SlimapiCapabilitiesInterceptor` 读 `hostConfig.slim`（叶子按路径注入头）。
- L2 `configure()` 内的 source 束选择（唯一控制分支）。
- `ConnectionViewModel` 版本区间 banner（读 `ServerCompatProfile`，数据非控制）。

---

## §4 波次表（ι-1 → ι-4，同 α→θ 格式）

| 子波 | 项 | 写域（白名单） | fixer 路由 | 风险 | 依赖 |
|---|---|---|---|---|---|
| **ι-0** | 域端口清单 + 不变量守护表（本 doc §5 落为代码注释锚） | doc / 注释 | — | — | θ done |
| **ι-1** | Q2：拆 `OpenCodeApi`→`StandardApi`+`SlimApi`（+DTO 随迁 + typealias 保 I20） | `data/api/{OpenCodeApi,StandardApi,SlimApi}.kt` + OCR retrofit 装配 | zlm（机械） | 低-中 | ι-0 |
| **ι-2** | L2 域端口 + 双实现（session→message→status→interaction→health，逐域） | `data/repository/source/*.kt`（同包 `data.repository`，禁子包 churn）+ OCR 委托 | fixer（跨文件不变量） | 中-高 | ι-1 |
| **ι-3** | Q3：L4+ `isSlimMode` 清零 + capability 只读模型 | `ServerCompatProfile.kt` + 上表 7 文件 | fixer | 中 | ι-2 |
| **ι-4** | 收尾：删 `OpenCodeApi` typealias（确认零外部引用后）/ 门面瘦身复核 | OCR + api | zlm | 低 | ι-3 |

**逐域顺序（ι-2 内，按分支密度/风险升序）**：session → message（watermark/since-ts 最复杂）→ status（bulk vs 扇出）→ interaction（routeToken）→ health（双路探针，与 cluster 19 checkHealth 委托对齐）。

---

## §5 硬不变量守护（ι 全程不可违反，源自 L4a0 I1–I22）

- **I5**：`Slim*Source` **禁自建** `slimStateLock` / `SlimSseStateMachine`——必须**单实例共享注入**（与 OCR/domain-delegate 同锁），否则并发写域裂开。
- **I6/I7**：source 束的选定/替换必须仍在 `configure()` 原子换栈内、经同一 `@Synchronized` monitor；**禁止**为 source 束开新锁序（呼应 handoff rev-4「TofuRepository 反向锁序死锁」警告）。
- **I8**：`serverCompatProfile` 写点（`probeSlimapiHealth`/`checkHealthFor` 尾部）不变；capability 只读模型只**读**它，不新增写点。
- **I15**：`coldStartSlimSync` token threading（外层 capture / 内层 require）——若归入 `MessageSource`/reconcile source，token 参数须原样穿透，禁在实现内重捕获。
- **I20**：公共类型 FQN 向后兼容（`OpenCodeApi` / `SlimColdStartSnapshot` / `SlimCommitToken` / `SlimAggregationOutcome` 等）——拆分/端口化发 `typealias` 或保位，上游 import 零改动。
- **行为保持子契约**：端口方法的返回类型、错误语义（`Result` 包装 / `parseErrorCode` + `DebugLog.w` + rethrow）、`X-Slimapi-Version` 头值（保持 1，不 bump）均逐字不变。

---

## §6 每波验证

- 每子波 reconcile 后 `source ./scripts/env.sh && ./scripts/check.sh`（compile + 单测全绿）。**中心串行，禁并行 Gradle。**
- ι-1（机械拆接口）单测全绿即门。ι-2/ι-3 现有 slim/legacy 双路测试全绿即门；必要时补**端口多态派发测**（同一端口注入 Standard/Slim 双实现，断言路由正确）+ **capability 映射测**（`slim ↔ supportsX` 真值表）。
- 每 lane reconcile 前 diff 验写文件不越白名单；`isSlimMode` 收敛以 grep「L4+ 零命中」为客观验收。
- 纯 JVM，不用模拟器。

---

## §7 与既有产物的接线

- **上游依赖**：ζ-3/L4a3 的域 Repository 拆分 + cluster 7/9/22/24 slim 分支收敛 = ι 的干净接手面。ι **不重复** ζ-3 的域拆分，只在其产出上加端口+双实现。
- **SSE 参照系**：`ui/controller/sse/`（`SseEventHandler`/`ModeDomain`/`Legacy·SlimSseHandler`/`SseEventRouter`）是 ι 的**同构范本**——REST 侧端口化后，两侧变体处理形态统一。
- **可复用 session**：`exp-1`（L4a0 不变量映射）取 I5/I6/I7/I8/I15/I20 全文；ι-2 复杂域实现走 `fixer`（强模型单飞），ι-1 机械拆走 `fixer-zlm`。
