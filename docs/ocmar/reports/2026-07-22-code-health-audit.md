# ocdroid 代码健康审计 — 初步情况报告

> **日期**：2026-07-22
> **来源**：4 个 explorer 审计（slim/legacy 隔离 2 个 + 全项目代码健康 2 个）+ orchestrator 传输层自查；本文为整合
> **状态**：初步，**待用户审阅**（未实施）
> **目的**：给整体代码健康画像，提出修改建议、治理思路、批次计划，供决策

---

## 一、情况报告

### 1.1 大体量画像（>900 行文件，wc -l 实测）

| 文件 | 行数 | 层 | 核心问题 |
|---|---|---|---|
| `ui/controller/SessionSyncCoordinator.kt` | **4310** | UI/控制器 | 事件路由器+状态折叠器+网络协调器三合一；`dispatchSseEvent` 单函数 **910 行**（20+ when 分支） |
| `data/repository/OpenCodeRepository.kt` | **4069** | 数据 | transport+状态+缓存+序列化+业务+安全(TOFU/mTLS)+API 转发全聚；~98 个函数 |
| `ui/chat/ChatScaffold.kt` | **1522** | UI | 单 Composable `ChatScaffold` **1399 行**；6 VM 订阅 + 9 slice flow + chrome/scroll/picker |
| `ui/chat/ChatMessageContent.kt` | **1388** | UI | `ChatMessageList` **1170 行**；渲染+scroll+方向检测+tab+NavFab+load-more |
| `service/.../AppLifecycleMonitor.kt` | **1375** | 服务 | 通知发布+去重+持久化+生命周期+30s 轮询 |
| `service/.../StreamingLifecycleCoordinator.kt` | **1359** | 服务 | FGS L1/L2/L3 状态机+handoff+poller+debounce |
| `ui/settings/HostProfilesManagerScreen.kt` | **1320** | UI | CRUD+证书导入+mTLS+测试连接堆一起 |
| `util/SettingsManager.kt` | **1071** | 工具 | ~50+ 配置项 + 迁移逻辑，单一文件 |
| `ui/SessionListActions.kt` | **1102** | UI | `launchLoadSessions` ~300 行（REST+stale+merge+cache+archive） |
| `ui/controller/ConnectionCoordinator.kt` | **1040** | UI/控制器 | health-check+retry+TOFU+SSE 启停；16 构造参数 |
| `service/SessionStreamingService.kt` | **1007** | 服务 | Android 组件 + SSE 生产者 + **20 个 lateinit** 注入枢纽 |
| `service/.../ServiceSseConnectionOwner.kt` | **979** | 服务 | 收集+超时+重试+resync+TOFU；多线程竞态 |
| `ui/AppCore.kt` | **903** | UI | **22 构造参数**，跨域编排上帝对象 |

**>1000 行文件 7 个，>4000 行 2 个。** 两个核心单体（SSC + Repository）是绝大部分改动风险的来源。

### 1.2 耦合问题

**A. 状态 slice 多写者（所有权模糊）** — 最高风险

| Slice | 写入点 | 风险 |
|---|---|---|
| `streamingPartTexts` | SSC.dispatchSseEvent + ChatViewModel.expandParts | 写者不唯一 |
| `partsByMessage` | SSC + MessageActions + PartExpandState | **三处写**，merge 逻辑分散 |
| `expandedParts` | ChatViewModel + ComposerViewModel | 双 VM 写同一 slice |
| `sessions` | SSC + SessionListActions + SessionMutationActions + HostProfileController | **四处写**，无单一权威 |

**B. 构造函数依赖爆炸**：`AppCore` 22 / `ConnectionCoordinator` 16 / `SSC` 11 / `HostProfileController` 11。

**C. 跨层耦合**：
- UI→data：`ChatMessageContent.kt` 直接持有 `OpenCodeRepository` 引用
- service→ui：`SessionStreamingService` 注入 `SharedStateStore`/`SharedEffectBus`/`SessionSyncCoordinator`
- data→ui：`OpenCodeRepository` 注释引用 ui 层常量（`SLIMAPI_LOCAL_HISTORY_BOUND` ← ui.ViewModelSupport）

**D. Action 与 State 混杂**：`AppAction.reduce` 同时做 state 变换 + 业务决策；`*Actions.kt` 同时 REST+写 slice+持久化。

### 1.3 代码质量

- **重复**：scroll LRU 缓存（ChatScaffold + ChatMessageContent 两份）；TOFU 状态机（ConnectionCoordinator + HostProfileController 两份）；stale/epoch 检查模式多处；SSLContext 构建模式两处；通知构建模式两处。
- **死代码/vestigial**：`ChatMessageContent.kt:364` `pendingRestoreSession` 恒 null（legacy guard）；`SSC:179` `isSlimMode={false}` 默认参数；`@Deprecated` 残留（SlimSseReducer:207、AppCore:41）；SettingsManager 注释掉的键墓碑（:932/:952）。
- **魔法值散落**：`300`(防抖)/`8_000`(reconcile 超时)/`30_000`(health 节流)/`4`(resync 并发)/`64`(stripe 锁数) 等未收敛为配置。
- **remember/key 误用**：`ChatScaffold:281` 多余 key 依赖；`ChatMessageContent:185` `chatState.messages` 每次 streaming 变 → 频繁 recomposition。

### 1.4 复杂度热点 Top 7（改一处最怕出 bug）

| 热点 | 位置 | 行数 | 测试覆盖 |
|---|---|---|---|
| `dispatchSseEvent` | `SSC.kt:894` | 910 | SSC 有测试，但 20+ 分支组合难全覆盖 |
| `ChatScaffold` Composable | `ChatScaffold.kt:114` | 1399 | 无 Composable 测试 |
| `ChatMessageList` | `ChatMessageContent.kt:82` | 1170 | 仅基础渲染测试 |
| `OpenCodeRepository.configure` | `:715` | ~68 | mock 浅 |
| `applyReconcileResult` | `SSC.kt:2675` | 61（10 子类） | 部分，Stale 分支弱 |
| `ServiceSseConnectionOwner.connect` | — | ~60 | **无**（多线程竞态） |
| `StatusAggregatorImpl.refresh` | — | ~50 | **无**（多线程 CAS） |

### 1.5 健康评分汇总

| 层/维度 | 评分 | 根因 |
|---|---|---|
| 传输层（slim 加性隔离） | 7/10 | 路径分流+双门闩，加性扎实 |
| 数据/服务层 | 5/10 | OpenCodeRepository 4069 行单体 + 跨层耦合 + 热点无测试 |
| UI/控制器层 | 4/10 | SSC 4310 行 + 5 个 >1000 行 Composable + 状态多写者 |
| 状态机 slim 隔离 | 4/10 | slim 长在 legacy 共享脊柱上，点补丁 |
| 渲染+测试层 | 4/10 | 双轨做对，但无 legacy 回归闸、核心热点无测试 |
| **整体** | **~4.5/10** | 大单体丛生 + 状态所有权模糊 + 测试网稀疏 |

---

## 二、修改建议（按收益/风险排序）

### 🔴 必做（高收益、可控风险）

1. **收口状态 slice 单一写者**（streamingPartTexts/partsByMessage/expandedParts/sessions）→ 所有写入走 `AppAction` dispatch 路由。这是"治本"——消除多写者后，后续所有拆分都安全。
2. **拆 `dispatchSseEvent`（910 行）** → 按事件类型拆为 `Sse*Handler` 独立文件（每个 ~50-200 行）。消除最大单体热点，每个 handler 可独立测试。
3. **补核心热点的特征化测试（characterization tests）**：dispatchSseEvent 各分支、applyReconcileResult 10 子类、configure 输入输出。**先冻结现状行为，再动手拆**。

### 🟠 应做（中收益）

4. **拆 `OpenCodeRepository`** → `HttpClientManager` / `SlimSseStateMachine` / `ApiFacade` / `TofuManager`。
5. **拆 UI 大 Composable**：ChatScaffold/ChatMessageContent → 提取 `ScrollLruCache`（去重）、`ChatScrollState`、`ChatTabVisibilityDetector`、`ChatChrome`。
6. **提取重复模式**：`TofuDelegate`、`StaleCheckUtil`、`ScrollLruCache`、通知/SSL/prefs 工具函数。

### 🟡 可做（低收益、清理类）

7. **清死代码**：`pendingRestoreSession`、`isSlimMode={false}` 默认、`@Deprecated` 残留、键墓碑。
8. **收敛魔法值** → `companion object` 或 `Timings`/`Limits` 配置类。
9. **修 remember/key 误用** → `derivedStateOf`/`snapshotFlow`。

### ⚪ 殿后（破坏性、需充分测试网）

10. **AppCore / 构造参数瘦身**：引入 `ControllerContext` 聚合，SessionStreamingService 的 20 lateinit 收口。**风险高，必须在前 9 项的测试网建立后做**。

---

## 三、治理思路（原则）

1. **安全网先行**：任何单体拆分前，先用特征化测试冻结当前行为。无测试网时拆 4000 行单体 = 走钢丝。这与前序 Task 2（slim/legacy 回归闸）是同一地基——**批次 0 必须先建测试网**。
2. **先解耦、后拆体**：先收口状态所有权（逻辑解耦），再做物理文件拆分。顺序反了会引入大量半成品状态。
3. **垂直切片、小步走**：每批次独立可交付，`check.sh` 全程绿。禁止大爆炸式重构。
4. **加性优先、破坏性殿后**：新结构先并存（如新 handler 与旧 dispatch 共存、双轨验证），验证后再删旧路径。
5. **YAGNI 守门**：不为"未来可能"提前抽象；只拆有实测痛点的单体。

---

## 四、批次计划

> 依赖关系：**批次 0（测试网）→ 批次 1（所有权）→ 批次 2/3/4（拆体，可并行）→ 批次 5（清理）→ 批次 6（殿后）**

### 批次 0 — 安全网（前置，必须先做）
- **内容**：Task 2 的 P0（legacy 回归闸）+ 3 个核心单体（SSC/Repository/ChatMessageList）的特征化测试（golden snapshot：喂固定 SSE 事件序列/固定输入，断言输出 slice 不变）。
- **产出**：`check.sh` 多一层回归保护；拆分有"行为不变"的客观判据。
- **风险**：低（纯加测试）。**工作量**：中。
- **验收**：破坏实验（故意改 dispatchSseEvent 分支）→ 测试红灯。

### 批次 1 — 状态所有权收口（最高价值）
- **内容**：为 `streamingPartTexts`/`partsByMessage`/`expandedParts`/`sessions` 各确立**单一权威 writer**，其余写入改走 `AppAction` dispatch（CQRS-lite）；`AppAction.reduce` 纯化为只做 state 变换，业务决策上移。
- **产出**：多写者消除，slim/legacy 状态渗漏从根本上变难（与前序 Task 2 P1 互补）。
- **风险**：中（触及多文件写入路径，但有批次 0 兜底）。**工作量**：中大。
- **验收**：每个 slice 仅 1 个 writer；全测试绿。

### 批次 2 — 拆 `dispatchSseEvent`（消除最大热点）
- **内容**：910 行 `when` → 按事件类型拆为 `Sse*Handler`（session.created/updated/status/error、message.updated/part.*/delta、integration.*、server.* 等），SSC 退化为路由器。
- **产出**：SSC 从 4310 行降到 ~2000；每 handler 可独立测试；构造参数瘦身。
- **风险**：中（机械拆分，批次 1 已理清所有权）。**工作量**：中大。
- **验收**：行为与批次 0 特征化测试一致；分支覆盖率提升。

### 批次 3 — 拆 `OpenCodeRepository`（数据层单体）
- **内容**：拆为 `HttpClientManager`/`SlimSseStateMachine`/`ApiFacade`/`TofuManager`；提取重复 SSL/通知/prefs 模式。
- **产出**：4069 行单体分解；data 层职责清晰。
- **风险**：中高（触及 ~30+ 注入点）。**工作量**：大。
- **可与批次 2 并行**（不同文件，写域不重叠）。

### 批次 4 — 拆 UI 大 Composable
- **内容**：ChatScaffold/ChatMessageContent → 提取 `ScrollLruCache`（去重两份重复）、`ChatScrollState`、`ChatTabVisibilityDetector`、`ChatChrome`；HostProfilesManagerScreen 按功能拆。
- **产出**：2 个 >1300 行 Composable 分解；渲染管线可测。
- **风险**：中（纯 UI 重组，有 screenshot 基线更稳）。**工作量**：中大。
- **可与批次 2/3 并行**。

### 批次 5 — 清理（低风险扫尾）
- **内容**：删死代码（pendingRestoreSession/isSlimMode 默认/@Deprecated/键墓碑）；收敛魔法值为配置类；修 remember/key 误用。
- **产出**：代码体积下降、recomposition 减少。
- **风险**：低。**工作量**：小。**可穿插在任何批次间做**。

### 批次 6 — 构造参数/上帝对象瘦身（殿后）
- **内容**：AppCore 22 参数 / ConnectionCoordinator 16 / SessionStreamingService 20 lateinit → 引入 `ControllerContext` 聚合；service→ui 跨层耦合反转。
- **风险**：高（破坏性，牵动 DI 全图）。**必须**批次 0-5 测试网充分后做。
- **工作量**：大。**建议**：除非有明确改动阻塞，可延后/分多次。

---

## 五、决策建议（给用户）

- **立即可启动、收益最大**：批次 0 + 批次 1（安全网 + 所有权收口）。这两步**不改生产行为**，却把后续所有重构的风险降一个数量级，且与前序 Task 2 天然合并执行。
- **拆体（批次 2/3/4）**：建议批次 1 完成后再启动，可并行。优先级 **2（dispatchSseEvent）> 3（Repository）> 4（UI Composable）**——2 的测试收益最大。
- **殿后（批次 6）**：除非 AppCore/Service 已阻塞具体功能开发，否则**不建议优先**——风险高、收益边际。
- **不确定项**：批次 0 的特征化测试需要确认"当前行为是否就是期望行为"（可能有现存 bug 被冻结）——审阅时请重点确认哪些是"要保留的行为"。

> 请审阅：评分/痛点是否符合你的体感？批次优先级与可投入工作量是否对齐？确认后我可把任一批次展开为可执行的 ocmar spec/plan。
