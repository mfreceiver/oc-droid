# Phase ι — 双 API 变体：域端口 + 双实现（legacy / slim 策略化）

> 生成：2026-07-23。**修订 R3（2026-07-23，据 R2 + 并发开发路径重构：§7 DAG 双轨并行 + §9.2 合流纪律）**。R2 = 报告作者 relay P0–P2 + 冻结测试/OCR 实读核对。
> 定位：**θ/ζ-3 已落地（v0.13.1）** 之上的独立**结构相**——引入接口/实现拆分，非纯行为保持，须单独 gate。
> 前置：`docs/refactor-optimization-plan.md`（α→θ 权威序）+ `docs/refactor-handoff.md`（L4a0 不变量 I5–I20）+ `app/src/test/.../T3RepositoryExtractFreezeTest.kt`（冻结公共面，authoritative）。
> 核心结论：**ι 未过时**——Q1 端口层被 freeze §1 转发条款预授权；Q3 是高价值剩余件。**R3 关键优化**：Q3（L4+ 收敛）与 PORT（域端口）写域不相交，经地基波 ι-A 后**双轨全程并行**（G1 并发组 5 lane 同飞），OCR 单写者 + `check.sh` 中心串行为唯一瓶颈。
> 决策：**Q1 = 域端口+双实现（共享态协作者拆分，非独立策略对象）**；**ι-A 地基（能力读模型）→ Q3‖PORT 并行 → Q2 殿后**；**Q2 = 复合接口 `OpenCodeApi : StandardApi, SlimApi`（仅审计性）**。

---

## §0 事实基线（P0 已核对 · 权威）

ι 报告初稿有 4 处前提须纠正（均已对 OCR/冻结测试实读确认）：

**P0-1 — Q2 不能用 typealias（会自破 gate）**
`T3RepositoryExtractFreezeTest.kt:281-285` 断言 `OpenCodeApi::class.java.name == "cn.vectory.ocdroid.data.api.OpenCodeApi"` 字面 FQN。typealias 指向改名类 → 反射报改名后 name → **RED**。
**改法（优先 a）**：复合接口 `interface OpenCodeApi : StandardApi, SlimApi { }`——Java 动态代理承继父接口方法，Retrofit `.create(OpenCodeApi::class.java)` 仍可用，FQN 不变 → freeze 绿。方法物理归属 `StandardApi`/`SlimApi`，`OpenCodeApi` 只作承继聚合。
备选 (b)：显式排期冻结测试修正（需评审签字）。**采 (a)**。

**P0-2 — Q2 拓扑是 3 链非 2 实例**
OCR 跑 **3 条 OkHttp 链** + v2（实读 OCR:190/213/254/268 + rebuild 531-545）：
- `api`（restHttp，OCR:191/532）
- `commandApi`（commandRetrofit，OCR:213/537）
- `mutationApi`（mutationRetrofit，OCR:254/543）— **slim POST 走此链**（createSession@1128 等）
- `apiV2`（v2Retrofit，OCR:268/545）

拆分 = **每条链 create 复合接口**（仍 `.create(OpenCodeApi)`，因复合承继）；若分窄接口实例则 3 链 × 2 = 6 次 create。仍机械，但波次卡/白名单须按 3 链更新。

**P0-3 — 分支清单：仅 5 处，删「散落」措辞**
OCR 现实只有 **4 个 `if(isSlimMode)`**（实读确认）：
- `getSessions` @1083
- `getSessionsForDirectory` @1107
- `getMessagesPagedImpl` @1297（+ `getMessagesPagedUnanchored` @1283 读同标志）
- `getPendingPermissions` @1657
- 外加 **1 health 分支** `checkHealth` @838。

真实剩余工作 = **抽这 5 处 + 折叠既有 delegate**。非"散落十几处"。

**P0-4 — HealthProbe 删/重定（cluster 19 NO-GO，勿在 ι-2 重论）**
`refactor-handoff.md §3` 已裁定：`checkHealthFor` 必须用 `resolveProbe`（纯参）防 mTLS 泄漏（OCR:981-987），`probeSlimapiHealth` 用 `sslConfigFor`（held mTLS）——**双路不能全委托**。ι 至多抽共享 parse 尾部（~15 LOC，已判边际，**不做**）。`checkHealth` @838 分支留门面。

---

## §1 硬约束（状态组 §A + 冻结测试，不重新争论）

1. **冻结公共面**：`T3RepositoryExtractFreezeTest` 反射锁 OCR ~40 公共方法 "MUST remain accessible from outside the repo"——含全部 slim token/reducer 面（`markSlim*`/`completeSlimReconfigure` freeze:107-138、`coldStartSlimSync`:141、`expandMessagesFullBatch`:143、`requireSlimTokenCurrent`:121）+ `slimStateLock` 字段本身（freeze:512-524）。策略对象在门面背后，OCR 保同签名 1 行委托。
2. **I5 `slimStateLock` 单实例**（freeze:512-524）→ legacy/slim **禁各自持锁**；共享注入同一锁。
3. **I6 `configure()` 原子事务**（`ticket→configureClientCert→hostConfig.configure→rebuildClients→completeSlimReconfigure` 不可拆，跨 legacy+slim）→ **禁按端口切分 configure**。
4. **I7 `@Synchronized` monitor**；**I8 `serverCompatProfile` 写点**（:116/:139）；**I15 `coldStartSlimSync` token threading**；**I20 公共 FQN**；**rev-4 双监视器锁序**（`TofuRepository` 自带 `@Synchronized`，反向锁序死锁）。

---

## §2 已落地基线（状态组 §B，勿重抽）

- **domain-delegate**：`SlimGetRepository`（**实为 legacy GET 转发**，`apiProvider: () -> OpenCodeApi` lambda 防陈旧，SlimGetRepository.kt:11-13/20-22）、`TofuRepository`、`ExpandBatchEngine`（已 apiProvider 重读模式）、`ConnectionHealthProbe`。
- **类型外提**：`MessagesPage`/`SlimAggregationOutcome`/`SlimColdStartSnapshot`（公共 FQN 保）。
- **cluster 6** barrier fold；**L5a/L5b/L5c** UI 拆；**cluster 18** publishIdleNotification。
- **当前 legacy/slim 形态** = `isSlimMode` 分支内联在 OCR 5 处（P0-3）。ι = 把这 5 处正式化为门面背后双实现 + 折叠既有 delegate。

---

## §3 已定案不做（状态组 §C，勿重开）

| backlog | 结论 | ι 义务 |
|---|---|---|
| **cluster 7**（slim 6 透孔） | resolved-by-design：`markSlim*`/`completeSlimReconfigure` **就是门面**（freeze:107-138） | ι **保留**，不删 |
| **cluster 19**（checkHealth 全委托） | NO-GO：`resolveProbe` vs `sslConfigFor`（防 mTLS 泄漏 OCR:981-987） | ι **禁合并**，双路留门面（P0-4） |
| **coldStartSlimSync 抽取** | deferred：freeze:121/:141 + I15 token threading | ι slim 域拆分**保**门面回调，不下沉 |
| **cluster 20**（emitEffect 基类） | deferred：tryEmit/emit 有意区分（A=suspend/B=single/C=multi-FIFO），correctness-critical | ι 动 controller **须留意**不误合并 |
| cluster 2/8 | 低优先 | 非 ι 范围 |

---

## §4 终态形态（P1 纳入不变量/delegate）

### 4.1 门面目标 = ~40 条 1-line 转发委托门面（非「薄门面」）
冻结锁 ~40 公共方法在 OCR（含全部 slim token/reducer 面 + `slimStateLock` 字段）。终态 OCR = **~40 条 1 行转发的委托门面**，锁经注入**共享**进 `Slim*Source`，**永不搬走**。

### 4.2 层次
```
L0 传输原语（通用,不动）   OkHttpClientFactory / SSL·TOFU / 拦截器 / Auth / Traffic
L1 Wire/API（专有,复合）   StandardApi(legacy 法) + SlimApi(/slimapi/ 法)
                          interface OpenCodeApi : StandardApi, SlimApi { }  ← FQN 冻结,承继聚合
                          3 链 × .create(OpenCodeApi)：api/commandApi/mutationApi(+apiV2)
L2 域端口(通用接口)+双实现  SessionSource / MessageSource(±status/interaction 按边际价值)
        │  Standard*Source(调 legacy 法) ‖ Slim*Source(调 slimapi 法)
        │  ★ Slim*Source 共享注入同一 SlimSseStateMachine + slimStateLock(I5)——
        │    这是【共享态协作者拆分】,不是独立策略对象
L3 OCR 冻结委托门面(~40 1-line) 持 source 束 + 不可二分路径; configure/checkHealth*/
        │  coldStartSlimSync/markSlim*/token·ticket 留门面
L4 协调/service(模式盲)    isSlimMode 零命中(Q3); 差异 → ServerCompatProfile 语义查询
L5 ViewModel/UI(模式盲)    版本/能力从 ServerCompatProfile 只读
```

### 4.3 端口边界（★ 能二分 / 留门面）
**能进 L2 双实现**（可干净二分的取数路由）：`getSessions`@1083 / `getSessionsForDirectory`@1107 / `getMessagesPagedImpl`@1297（取数层）/ `getPendingPermissions`@1657。

**禁下沉，留门面**：
- `configure()`（I6 单原子事务）
- `checkHealth`@838 / `checkHealthFor`（cluster 19 NO-GO，mTLS 分叉）
- `coldStartSlimSync` + `requireSlimTokenCurrent`（I15 token，deferred）
- 全部 slim state-machine 面（`captureSlimCommitToken`/`begin·completeSlimReconfigure`/`markSlim*`/`applySlimDigest`/`getSlimSessionState`… freeze 锁 + I5 单锁）

### 4.4 最坏例：`getMessagesPagedImpl`（OCR:1280-1315）
bookmark 读写 + I15 token threading + 嵌套 FQN `StaleSlimCommitException`。→ `SlimMessageSource` 须**共享注入** `SlimSseStateMachine`+`slimStateLock`，token 参数原样穿透（外层 capture/内层 require 不变），异常嵌套 FQN 不动。**这是共享态协作者，不是干净独立策略。**

### 4.5 ι-2 须指定 delegate 折叠（P1-7，避免第三种重叠形态）
- **`SlimGetRepository`（实为 legacy GET）→ 并入 `StandardSessionSource`/相关 Standard* 实现**（命名误导，本质 legacy 转发）。
- **`ExpandBatchEngine`（已 apiProvider 重读）→ 作 `SlimMessageSource` 协作者**（不重抽，注入复用）。

### 4.6 端口示例（apiProvider lambda 复用 SlimGetRepository 范本）
```kotlin
interface SessionSource {
    suspend fun getSessions(limit: Int?): Result<List<Session>>
    suspend fun getSessionsForDirectory(directory: String, limit: Int?): Result<List<Session>>
}
internal class StandardSessionSource(private val apiProvider: () -> OpenCodeApi) : SessionSource { … }  // 调 legacy 法
internal class SlimSessionSource(private val apiProvider: () -> OpenCodeApi) : SessionSource { … }      // 调 slimapi 法
// 端口今天即可包【单一复合 OpenCodeApi】——Standard impl 调 legacy 法、Slim impl 调 slimapi 法;
// Q2 的物理 API 拆分是审计性的,不阻塞 L2。
```

---

## §5 能力模型（P1-9：建在既有 ServerCompatProfile 上）

`ServerCompatProfile` **已有** `features.tokenStream` / `sidecarOk` / `schemaDegraded` / 版本范围 + I8 写点（:116/:139）。Q3 收敛所需语义查询**扩展它**（只读），**不新增 `ConnectionCapabilities`**（多半 YAGNI）。禁暴露裸 `slim: Boolean` 给 L4+。

---

## §6 Q3（P2-10：命中表修订 + SSE host 范围裁定）

**修订命中表**（实读核对）：
| 位置 | 层 | 处置 |
|---|---|---|
| `OpenCodeRepository` 5 处（§4.3） | 门面 | 抽 L2 / 留门面（§4.3 二分） |
| `ui/controller/SessionSyncCoordinator.kt`（`isSlimMode` thunk） | L4 | 取数下沉;编排差异 → `ServerCompatProfile` 语义查询 |
| `service/status/StatusAggregatorImpl.kt` / `SlimStatusFanOut.kt` | service | 下沉 `StatusSource`（若纳入 ι-2） |
| `ui/SessionListActions.kt` | UI 动作 | 下沉 / capability |
| **`SseDispatchHost.kt:88`（`fun slimMode()` host 接口）** | SSE host | **见下裁定** |
| **`SlimSseHandler.kt:70`（`host.slimMode()`）** | SSE handler | 见下裁定 |
| **`ControllerModule.kt:226`（`isSlimMode = { repository.isSlimMode }` DI thunk）** | DI 装配 | 属装配选择点,**豁免** |
| `service/SessionStreamingService.kt` / `service/streaming/StreamingModule.kt`（onResync/runner 门） | service | `ServerCompatProfile.features.tokenStream` 语义查询 |
| `ConnectionBootstrapEngine` / `EffectiveConnectionConfigResolver` | — | **KDoc-only,非命中**（标注排除） |
| `ui/ConnectionViewModel.kt`（版本 banner） | UI | 保留,读 `ServerCompatProfile`（合规） |

**SSE host 接口裁定（P2-10）**：`SseDispatchHost.slimMode()` 是 SSE 事件路由的**内部机制**（router 依 mode 选 handler），非业务分支上浮。**判为 L4+ 零命中的豁免项**（与 DI 装配 thunk 同类：机制性 mode 选择，非泄漏）。**并停止引用 SSE 侧为 mode-blind 范本**——SSE 有 3 个 handler 且经 `slimMode()` 自身泄漏 mode，它是"机制性 mode 分派"参照，不是"mode-blind"典范。

**合法豁免明列**：L0 拦截器读 `hostConfig.slim`；OCR 门面 source 束选择 + 不可二分路径分叉；`ControllerModule` DI thunk；`SseDispatchHost/SlimSseHandler` 的 `slimMode()`（SSE 机制）；`ConnectionViewModel` 版本 banner。

---

## §7 开发计划与并发路径（R3：DAG + 双轨并行）

### 7.1 并发架构原理（为什么能并行）

关键洞察 —— **两大主轨在共享地基之后写域不相交，可全程并行**：

- **Track Q3（L4+ 收敛）**：只写 **L4+ 文件**（SSC / StatusAggregator / SessionListActions / SSS / StreamingModule / ControllerModule），对 OCR **只读**（读能力查询）。
- **Track PORT（域端口）**：只写 **OCR 内部 + 新 `*Source.kt`**，**不碰 L4+**。

两轨唯一交点 = **能力读模型**（`ServerCompatProfile` 派生查询）。把它前置为**地基波 ι-A**（唯一需要小改 OCR 暴露能力访问器的点），ι-A 合并后两轨 fork，此后**零写域重叠**。

**串行瓶颈（硬约束）**：
1. **OCR 单写者** —— PORT 轨内各域端口都改 OCR 门面（1-line 委托），故 PORT 轨**内部串行**（session→message→…），但整轨与 Q3 并行。
2. **`check.sh` 中心串行** —— Gradle 共享 build dir，禁并行；即便 lane 在各自 worktree 开发，**reconcile+gate 必须排队过闸**（见 §9.2）。

### 7.2 依赖 DAG

```
ι-0 (诊断/基线, 串行, 只读)
      │  B1–B7 产物齐 + check.sh 绿
      ▼
ι-A (地基波: 能力读模型 = ServerCompatProfile 派生查询 [+ OCR 最小能力访问器])
      │  合并后 fork —— 此下两轨写域不相交, 全程并行
      ├────────────────────────────┬───────────────────────────────┐
      ▼                            ▼                               (worktree 隔离)
 ═══ Track Q3 (L4+, OCR 只读) ═══   ═══ Track PORT (OCR 内部, 串行) ═══
 ι-Q3a  service/streaming          ι-P1  session 域端口
        (SSS onResync + StreamingModule runner              (SessionSource + Standard/Slim*Source
         → features.tokenStream)                             + 折叠 SlimGetRepository→Standard*
 ι-Q3b  coordinator + DI                                     + OCR getSessions/getSessionsForDirectory 委托)
        (SSC isSlimMode thunk + ControllerModule:226)              │  (OCR 单写者 → 串行)
 ι-Q3c  UI actions                                                 ▼
        (SessionListActions SWEEP 门 → capability)          ι-P2  message 域端口 (最坏例)
 ι-Q3d  status                                                    (MessageSource + Standard/Slim*Source
        (StatusAggregatorImpl + SlimStatusFanOut → capability)     + 折叠 ExpandBatchEngine→SlimMessageSource
   (Q3a–d 写域两两不相交 → 4 lane 并行)                            + 共享注入 slimStateLock/StateMachine
      │                                                            + I15 token 穿透 + getMessagesPagedImpl 委托)
      └────────────────────────────┬───────────────────────────────┘
                                    ▼
                             ι-Q2 (复合接口, 殿后, OCR 单写者→串行于 PORT 后)
                             interface OpenCodeApi : StandardApi, SlimApi
                             slim 法/DTO 迁 SlimApi; 3 链×2=6 次 create 更新
                                    │
                                    ▼
                             ι-4 (收尾: 冻结测试全绿复验 + 白名单越界 diff 审 + 门面瘦身)
```

### 7.3 波次表

| 波 | 轨 | 项 | 写域（白名单，扁平同包） | fixer | 风险 | 依赖 | 并发组 |
|---|---|---|---|---|---|---|---|
| **ι-0** | — | 诊断/基线（§ι-0 B1–B7，只读） | doc | — | — | v0.13.1 | 串行 |
| **ι-A** | 地基 | 能力读模型：`ServerCompatProfile` 派生语义查询（`supportsWatermarkResync`/`supportsTokenStreamResync`/`supportsBulkStatus`）[+ OCR 最小能力访问器暴露] | `ServerCompatProfile.kt` + OCR（仅新增访问器，不动 ~40 pinned） | fixer | 中 | ι-0 | 串行（fork 前） |
| **ι-Q3a** | Q3 | service/streaming 收敛 | `service/SessionStreamingService.kt` + `service/streaming/StreamingModule.kt` | fixer | 中 | ι-A | **G1 并行** |
| **ι-Q3b** | Q3 | coordinator + DI thunk 收敛 | `ui/controller/SessionSyncCoordinator.kt` + `di/ControllerModule.kt` | fixer | 中 | ι-A | **G1 并行** |
| **ι-Q3c** | Q3 | UI actions 收敛 | `ui/SessionListActions.kt` | fixer | 中 | ι-A | **G1 并行** |
| **ι-Q3d** | Q3 | status 收敛 | `service/status/StatusAggregatorImpl.kt` + `service/status/SlimStatusFanOut.kt` | fixer | 中 | ι-A | **G1 并行** |
| **ι-P1** | PORT | session 域端口 + 折叠 `SlimGetRepository` | 新 `data/repository/SessionSource.kt`(+`Standard`/`Slim`) + OCR(getSessions:1083/getSessionsForDirectory:1107 委托) | fixer | 中 | ι-A | **G1 并行**（PORT 轨串行头） |
| **ι-P2** | PORT | message 域端口（最坏例）+ 折叠 `ExpandBatchEngine` | 新 `data/repository/MessageSource.kt`(+`Standard`/`Slim`) + OCR(getMessagesPagedImpl:1297 委托) | fixer（共享态协作者） | 高 | ι-P1 | PORT 串行（承 P1） |
| **ι-Q2** | 殿后 | 复合接口 `OpenCodeApi:StandardApi,SlimApi` + slim 法/DTO 迁 | `data/api/{OpenCodeApi,StandardApi,SlimApi}.kt` + OCR 3 链装配 | zlm（机械） | 低-中 | ι-P2 + G1 全绿 | 串行 |
| **ι-4** | 殿后 | 收尾复验 + 白名单审 | 复验 | zlm | 低 | ι-Q2 | 串行 |

### 7.4 并发点与关键路径

- **G1 并发组（fork 后主并行窗口）**：`{ι-Q3a, ι-Q3b, ι-Q3c, ι-Q3d}`（Q3 四 lane）**‖** `{ι-P1→ι-P2}`（PORT 串行链）。共 **5 条 lane 同时在飞**（4 Q3 + 1 PORT 头）。
- **关键路径**：`ι-0 → ι-A → ι-P1 → ι-P2 → ι-Q2 → ι-4`（PORT 轨最长，含最坏例 ι-P2）。Q3 四 lane 均在此路径**并行阴影内**完成，不加长总工期。
- **写域不重叠证明**：Q3 轨文件 ∩ PORT 轨文件 = ∅（Q3 = L4+ service/ui/di；PORT = data/repository + OCR 内部方法）；`ServerCompatProfile` 由 ι-A 独占写、fork 后 Q3 只读、PORT 不碰 → 无竞争。
- **status/interaction 归属澄清**：Q3d 用 capability **改 StatusAggregator 的分支读取**（status 域**不端口化**，边际价值低）；`getPendingPermissions:1657` + `checkHealth:838` 是 **OCR 内部分支**（非 L4+ 命中），留门面，**不入 Q3 亦不入 ι-P1/P2**（interaction/health 端口化 = 未来 backlog）。

### 7.5 排序理由

Q3（L4+ 收敛）与 PORT（端口化）**解耦并行**是本 R3 的核心优化：二者剩余价值独立、写域不相交，无需 P2-11 的"Q3 严格打头再串 ι-2"。地基波 ι-A 抽出后，Q3 拿能力查询、PORT 动 OCR 内部，互不阻塞。Q2 物理 API 拆分仅审计性，且 OCR 单写者 → 殿后串行于 PORT 之后。

---

## §8 硬不变量守护清单（逐条对映冻结测试/L4a0）

- **冻结面**：每子波末 `T3RepositoryExtractFreezeTest` 必绿——~40 公共方法可达性零变；`OpenCodeApi`（复合后仍）/`SSEClient`/`SlimapiContract`/`HostConfig` FQN 零变；`SlimCommitToken`/`SlimReconfigureTicket` 嵌套 FQN 零变；`slimStateLock` 字段留 OCR。
- **I5**：`Slim*Source` 禁自建锁/状态机——单实例共享注入（§4.4 最坏例）。
- **I6/I7**：source 束选定仍在 `configure()` 原子换栈 + 同 monitor；禁开新锁序（rev-4）。
- **I8**：`serverCompatProfile` 写点（:116/:139）不变；Q3 语义查询只读。
- **I15**：`coldStartSlimSync` token threading 留门面。
- **I20**：公共 FQN 兼容，上游 import 零改（调用经门面/L2 端口，不直 import `SlimApi`）。
- **§3 保留项**：cluster 7 六透孔保留；cluster 19 双路不合并；coldStartSlimSync 门面回调保。
- **行为保持**：返回类型、错误语义（`Result`/`parseErrorCode`+`DebugLog.w`+rethrow）、`X-Slimapi-Version=1`（不 bump）逐字不变。

---

## §9 验证与并发合流纪律

### 9.1 每波验证
- 每子波 reconcile 后 `source ./scripts/env.sh && ./scripts/check.sh`（compile + 单测全绿）。`NoSuchFileException`(in-progress-results.bin) flake → `--rerun-tasks`。
- **ι-A**：`ServerCompatProfile` 现有测全绿；补 `slim ↔ features.*` 派生查询真值映射测；能力访问器单测。
- **Q3 各 lane**：对应 L4 单测全绿；补 capability 分支替换后的行为等价测；lane 合并后 grep「L4+ 零命中」（除 §6 豁免）为客观验收。
- **ι-P1/P2**：slim/legacy 双路测全绿；补端口多态派发测（同端口注入 Standard/Slim，断言路由）；**ι-P2 补 `getMessagesPagedImpl` 共享态并发测**（bookmark 读写 + I15 token threading + `StaleSlimCommitException` 嵌套 FQN 不回归）。
- **ι-Q2**：`OpenCodeRepositorySlimapiEndpointsTest` + 冻结测试（FQN）全绿即门。
- **每波末必跑 `T3RepositoryExtractFreezeTest`**（~40 pinned + FQN + `slimStateLock` 字段）——任一子波转 RED 即回退该 lane。纯 JVM，不用模拟器。

### 9.2 并发合流纪律（worktree + 串行闸门）
- **lane 隔离**：G1 并发组各 lane 在**独立 git worktree** 开发（参考仓库 `.slim/worktrees/` 惯例），互不干扰源树。
- **`check.sh` 中心串行闸门**：Gradle 共享 build dir **禁并行**。各 lane 并行编码，但 **reconcile→check.sh→merge 必须排队**逐个过闸；过闸期间其它 lane 只编码不 gate。
- **reconcile 顺序（G1 内）**：先合 **Q3 四 lane**（写域彼此不相交，OCR 只读，冲突面最小）→ 再合 **PORT 链**（ι-P1→ι-P2，动 OCR）。PORT 后合可让其 rebase 到已收敛的 L4+ 之上，OCR 门面委托与 L4+ 调用点一次对齐。
- **每 lane reconcile 前 diff 验白名单**：`git diff --stat` 确认写文件不越 §7.3 白名单；越界即打回。
- **合流后总 gate**：G1 全部合并 → 跑一次全量 `check.sh` + 冻结测试 → 再进 ι-Q2。
- **回退粒度**：lane = 最小回退单元；某 lane gate RED 且短期不可修，则单独还原该 lane 的 worktree 分支，不阻塞其它已绿 lane 合并。

---

## §ι-0 基线 checklist（ora-3 → 启动闸门，地基波 ι-A 及后续 Q3/PORT 前须逐项落地）

改造方已逐项验证 ora-3 诊断为真（P0-1 复合接口 / P0-2 三链 / P0-3 五分支 / P0-4 cluster19 / P2-10 SSE 命中）。§6 命中表行号 + §10 ora-3 待办在此合并为**单一启动闸门**：ι-0 完成前**禁**动任何代码波（含 ι-A）。每项须落地为**可复验产物**（grep 输出 / diff / 断言），非口头确认。B2 命中表刷新须区分 **G1 并发组归属**（Q3a/b/c/d 各自文件集），为并行 lane 白名单打底。

| # | 基线项 | 落地动作（可复验） | 验收 |
|---|---|---|---|
| **B1** | 重扫 `isSlimMode` 提取目标集 | `rg -n 'if \(isSlimMode' OpenCodeRepository.kt` 重定位 4 分支（ora-3 读到 getSessions:1083 / getSessionsForDirectory:1107 / getMessagesPagedImpl:1297 / getPendingPermissions:1657）+ health checkHealth:838 | 行号漂移已重定位；确认恰 5 处，无新增遗漏 |
| **B2** | 重扫 L4+ 命中表 | `rg -n 'isSlimMode\|slimMode\(\)'` 全仓；补 ora-3 SSE 三处 `SseDispatchHost.kt:88` / `SlimSseHandler.kt:70` / `ControllerModule.kt:226`；标 `ConnectionBootstrapEngine`/`EffectiveConnectionConfigResolver` = **KDoc-only（非命中）** | 以 ι-0 当时代码重基线；命中表行号刷新（现表基于 ora-3 时点） |
| **B3** | 操作化冻结约束 | 记：§3a `OpenCodeApi::class.java.name` 字面 FQN → **必用复合接口** `interface OpenCodeApi : StandardApi, SlimApi`，`.create(OpenCodeApi)` 走承继；§4c `slimStateLock` 字段**留 OCR 声明**、注入共享、永不搬走；§1 forwarder 条款预授权门面；~40 pinned 方法清单（含全部 `markSlim*`/`completeSlimReconfigure`/`coldStartSlimSync`/`expandMessagesFullBatch`/`requireSlimTokenCurrent`） | 冻结测试基线跑绿一次留存；约束逐条对映 §8 守护清单 |
| **B4** | delegate 折叠清单 | `SlimGetRepository`（16 legacy GET）→ 并入 `Standard*` 实现；`ExpandBatchEngine`（apiProvider 重读）→ 作 `SlimMessageSource` 协作者 | 折叠映射表落定；确认**不产生第三种重叠形态** |
| **B5** | 确认 3 链 Retrofit 拓扑 | `api`(rest) / `commandApi` / `mutationApi`(**slim POST 走此**) + `apiV2`；复合接口 create 计划 = 每链 2 次 `retrofit.create`（共 **6 次**） | 拓扑图 + create 计划落定（对映 OCR:190/213/254/268 + rebuild 531-545） |
| **B6** | 文档化共享态协作者模型 | `Slim*Source` 共享**同一** `SlimSseStateMachine` + `slimStateLock`（注入，非独立策略对象）；最坏例 `getMessagesPagedImpl`（bookmark 读写 + I15 token threading + 嵌套 FQN `StaleSlimCommitException`） | 协作者注入图落定；最坏例的 token/锁/异常穿透路径逐一标注「不动」 |
| **B7** | 能力模型基线 | 核 `ServerCompatProfile` 已有 `features.tokenStream`/`sidecarOk`/`schemaDegraded`/版本范围 + I8 写点（:116/:139） | 确认 Q3 语义查询**扩展既有**即可，**不新增** `ConnectionCapabilities`（YAGNI） |

**闸门**：B1–B7 全部产出可复验产物 + `./scripts/check.sh` 基线绿后，地基波 **ι-A** 方可启动（ι-A 合并后 Q3‖PORT 双轨 fork，见 §7.2）。B1/B2 行号以 ι-0 执行时点为准（本 doc 现有行号均为 ora-3 读取时点快照）。

---

## §10 与既有产物接线 / 待办

- **上游依赖**：ι 假设 α→θ 已落地（**v0.13.1**），基于 `isSlimMode` **现状 5 分支**定义端口边界。ι-0 基线闸门（**§ι-0 B1–B7**）已将 `ora-3` 诊断结论 + §6 行号 rescan 合并为可复验启动条件——端口工作前须逐项落地。
- **勿重做**：`SlimGetRepository`/`TofuRepository`/`ExpandBatchEngine`/`ConnectionHealthProbe` 已外提——ι 形式化端口、**折叠**（§4.5），不重抽。
- **SSE 参照系定性**：`ui/controller/sse/` 是"机制性 mode 分派"参照（3 handler + `slimMode()`），**非 mode-blind 典范**——ι 的 mode-blind 目标只针对 L4+ 业务分支。
- **relay 附带**：本修订 + 状态组「完整后续待办 §A–F」一并转改造方整合。
