# 任务 2：slim↔legacy 隔离治理

> **日期**：2026-07-22
> **来源**：3 层隔离评估（2 个 explorer：状态机层 / 渲染+测试层；orchestrator 传输层自查），rev-glm 整合并逐条 `file:line` 核验
> **状态**：草案，待实施
> **目标读者**：实施 agent、评审面板
> **关联**：ocdroid slim（省流）模式引入后的隔离治本方案；核心不变量为 legacy 路径 byte-for-byte 不变（slim 加性叠加，不劫持 legacy）

---

## 一、思路（诊断与治理哲学）

### 1.1 隔离到底坏在哪——根因链

不是"传输层没隔离"，而是**"加性传输 + 点补丁状态机 + 零回归闸"的三段式失配**：

```
传输层干净（路径加性分流 + 拦截器双门闩）
        │ 事件帧汇流
        ▼
SseEventBridge 汇流缝（slim digest 帧 + legacy 事件进同一 dispatchSseEvent）
        │
        ▼
状态机：slim 机制长在 legacy 共享脊柱上
   （SessionListState.sessions / ChatState.messages / partsByMessage）
   靠 7 处 isSlimMode() 点补丁 gate 区分（5 处结构性 early-return + 2 处点补丁守共享写）
   slim 专属写路径（冷启动快照 / reconcile 合并）直接写 legacy 共享 state
        │
        ▼
渲染：双轨做对了（partExpandStates vs expandedParts 物理分离）
        │
        ▼
测试层断档：check.sh 只跑 compile+单测，无 legacy 协调器端到端回归
   → slim 改动破坏 legacy 时「无声」（无测试失败、无 check.sh 红灯）
        ▼
历史已发生 4 次渗漏（均"先污染后补 gate"）→ 模式未断
```

**一句话根因**：slim 是**加性叠加在 legacy 的事件驱动状态脊柱上**，而非独立域。传输与渲染两端做了结构隔离（加性路径、nullable 字段、双轨 map），但**中段（状态机 + 测试）只有点补丁、无回归闸**——slim 的每一次演进都是对 legacy 共享 state 的一次"靠 gate 守住的擦边"，gate 漏一次就渗漏一次。

### 1.2 哪里是好的、必须保留（治理红线）

治理必须**不破坏**以下已验证良好的隔离：

| 隔离资产 | 证据 | 为何必须保留 |
|---|---|---|
| **SSE 路径加性切换** | `SSEClient.kt:137` `if (slimMode) SLIM_EVENTS_PATH else LEGACY_EVENTS_PATH` | legacy 走 `/global/event`、slim 走 `/slimapi/events`，字节级分流干净 |
| **拦截器双门闩（slim flag + 路径前缀）** | `SlimapiVersionInterceptor.kt:39-42`、`SlimapiCapabilitiesInterceptor.kt:43-46` | 双条件 AND（`!hostConfig.slim` AND `/slimapi/` 前缀），legacy 下 no-op，门控比单 flag 强 |
| **byte-for-byte 加性纪律** | `OpenCodeRepository.kt` 共 **6 处**注释：`:1222`/`:1337`/`:1404`/`:1470`/`:1769`/`:2022` | REST 入口语义被反复 pin，纪律比初步评估更强（初评只列 2 处） |
| **Part 字段 nullable + null 默认** | `Message.kt:225`（hasFull）/ `:235`（omitted），`explicitNulls=false` | legacy Part 三项全 null → 线上字节与新增字段前一致 |
| **expand eligibility 三元过滤** | `PartExpandState.kt:207` `hasFull==true && omitted!=null && messageId!=null` | legacy 三项 null → slim 展开 affordance 在 legacy 永不出现 |
| **expand 双轨物理分离** | slim `partExpandStates` 在 `AppStateSlices.kt:410`；legacy tool/reasoning 折叠 `expandedParts` 在 `StoreState.kt:40`（双轨自述 `AppStateSlices.kt:406-408`「SEPARATE」）| 独立字段无耦合（注：初评将 partExpandStates 标为 :394，实际 :394 是 `streamingPartTexts`，partExpandStates 在 :410，同属 ChatState） |
| **streamingPartTexts 共享实时层** | `AppStateSlices.kt:394` | 双模式共享为实时文本层，是有意设计，非泄漏 |
| **结构性 early-return** | `SessionSyncCoordinator.kt:2401`/`:2482`/`:3021`/`:3148` | slim 专属入口在 legacy 直接返回，不进入 slim 写路径 |

> ⚠️ **关键 nuance**：`dispatchSseEvent`（`SessionSyncCoordinator.kt:894`）的 `message.updated`(`:1270-1274`) 与 `message.part.*`(`:1362-1460`) 分支**只守会话门（`eventSessionId != currentSessionId`，:1259），不守 isSlimMode**。这不是 bug——legacy opencode 同样流式 `message.part.delta`，**这层"共享"是正确设计**。治理**不应**把这层强行拆开；渗漏风险在 slim **专属**写路径（冷启动快照/reconcile 合并）触及 **legacy 共享 state**，而非在 dispatch 分支本身。这一点决定了 P1 的精确落点。

### 1.3 治理哲学——点补丁 vs 结构分离+回归闸

**判定：不做"再补一个 gate"，也不做"一步到位全重写拆 state slice"。采用"回归闸优先 + 最高风险点结构加固"的务实中间路线。**

- **继续点补丁（不可取）**：4 次历史 bug 已证明不可持续——每次都是"先污染后补"，且 4000+ 行协调器的 7 处 gate 中，2 处（`:1701`/`:1909`）正是"守护共享写"的点补丁，**少加一个即渗漏**。补丁数量只增不减。
- **一步到位拆 state slice（不可取）**：`SessionSyncCoordinator` 已是混合机（纯事件驱动 + slim digest/reconcile/cursor-walk/resync/cold-start），拆 slice 牵动 `applyReconcileResult`(`:2675`)、`applySlimColdStartSnapshot`(`:3354`)、`mergeSlimMessagesIntoChat`(`:3307`) 全链 + 所有 slim 测试，**单次回归面过大**，且当前无回归闸兜底——等于在没安全网时走钢丝。
- **务实路线（采用）**：先用**回归闸**把"无声破坏"变成"测试红灯"（最低成本最高收益，check.sh 零改动即可接入），再用**写所有权断言**把"忘加 gate 即渗漏"变成"忘加 gate 即断言崩溃"（debug/test 下 fail-fast）。state slice 物理拆分列为未来工作，**不在任务 2 范围**。

### 1.4 任务 2 范围边界

**做（范围内）**：
- 补 legacy 协调器级回归测试套件（接入现有 `testDebugUnitTest`，**不依赖模拟器**）。
- 在 slim 专属写路径入口加写所有权断言。
- 加传输层 legacy 不被 slim 头/路径污染的断言测试。
- 固化冷启动快照 `byDirectory MERGE`（已修，补 pin 测试）。

**不做（范围外 / YAGNI）**：
- 不拆 ChatState / SessionListState 为 slim/legacy 双 slice。
- 不引入双 OkHttp client 物理分离。
- 不重写 `dispatchSseEvent` 的共享流式分支。
- 不接入 `connectedDebugAndroidTest` 进 check.sh 默认链（需模拟器 + .env，重；留给 `--full` 或手动）。

### 1.5 成功标准（可观测验收）

1. `./scripts/check.sh`（默认）能拦截"slim 写路径在 legacy 触发"类回归——表现为新增 legacy 测试红灯。
2. slim 专属写函数（`applySlimColdStartSnapshot`/`mergeSlimMessagesIntoChat`）在 `isSlimMode()==false` 下被调用时**抛断言而非静默写共享 state**。
3. 冷启动快照 `byDirectory MERGE` 被 pin 测试锁死，FULL REPLACE 回退路径被显式覆盖。
4. 现有全部 slim 测试（`SessionSyncCoordinatorSlimTest`/`ResyncTest`/`CadenceTest` 等）保持绿色（治理是加性的，不改生产行为）。

---

## 二、方案（可执行实施计划）

### 2.1 P0（必须，最高优先）：legacy 协调器级回归闸

**目标**：把 check.sh 缺失的 legacy 不退化基线补上。**测什么**：slimMode=false 下的「开 session → SSE 流式 → 切会话 → 列表不消失 → tool 折叠不受 slim 干扰」关键链。

**为什么是 JVM 单测而非插桩测试**：check.sh 默认只跑 `testDebugUnitTest`（`scripts/check.sh:18-22`），插桩测试需模拟器 + .env，重且不进默认链。本仓已有成熟的 MockK 协调器单测范式（`SessionListActionsTest.kt:1769` `every { repository.isSlimMode } returns true`），P0 沿用同一范式设 `returns false`，**零基础设施改动即可接入 check.sh**。

**注**：传输层路径选择其实**已有** legacy pin——`SSEClientSlimModeTest.kt:152-156` 显式断言「legacy mode MUST hit /global/event (regression guard)」（初评称"无任何 legacy 测试"过度）。但**协调器/渲染层的 legacy 端到端链确实缺失**：glob 确认无 `*Legacy*Test.kt`，`slimMode=false` 在协调器测试里仅零散单例（`LastErrorTest:517/544`、`SlimTest:248`、`ResyncTest:1335`），无系统化套件。P0 补的正是这层。

**改动 / 落点**：

| # | 测试用例（slimMode=false） | 守护的渗漏类 | 参考实现锚点 |
|---|---|---|---|
| P0-1 | `legacy session switch preserves message list`：A 有消息→切 B→切回 A，断言 A 消息存活 | FULL-REPLACE 让列表消失（历史 bug 6bf7bf7 类） | 镜像 `SessionSyncCoordinatorSlimTest` 结构 |
| P0-2 | `legacy SSE message.part.delta streams into streamingPartTexts`：喂 delta 帧→断言 `streamingPartTexts` 填充 + 最终 message 落列表 | 共享 dispatch 分支 `:1362-1460` 在 legacy 退化 | `dispatchSseEvent`(:894) 的 fake-sse 喂帧法 |
| P0-3 | `legacy message.updated patches in place`：message.updated 帧 patch 已存在消息 | `:1270-1274` patch/insert 共享分支 | 同上 |
| P0-4 | `legacy reconcileSession returns NoRepository without touching chat`：断言 legacy 下 reconcile 早返回且**不**改 ChatState | 结构性 early-return `:2401` 被 future 改动破坏 | 直接调 `reconcileSession`，断言 `ReconcileResult.NoRepository` |
| P0-5 | `legacy cold-start does not invoke applySlimColdStartSnapshot`：legacy 下喂冷启动信号，断言 `SessionListState.sessions` **不被改写** | 冷启动 FULL-REPLACE 跨模式擦除（最重历史 bug） | pin `applySlimColdStartSnapshot`(:3354) 在 legacy 不触发 |
| P0-6 | `legacy tool-call fold unaffected by partExpandStates`：legacy tool 折叠走 `expandedParts`(`StoreState.kt:40`)，slim `partExpandStates`(:410) 保持空 | 双轨被未来改动耦合 | 断言 legacy 下 `partExpandStates.isEmpty()` 且折叠状态在 `expandedParts` |

**放哪**：新建 `app/src/test/java/cn/vectory/ocdroid/ui/controller/LegacyGoldenPathRegressionTest.kt`（与现有协调器测试同目录）。

**怎么接入 check.sh**：**无需改 check.sh**——`testDebugUnitTest` 自动收集所有 `*Test`，新增类即自动进默认链。可选增强（非必须）：在 check.sh 加 `--legacy-gate` 分支跑 `./gradlew testDebugUnitTest --tests "*LegacyGoldenPath*"`，供快速定向反馈。

**验收**：`./scripts/check.sh` 通过；且人为制造"在 `applySlimColdStartSnapshot` 删掉 isSlimMode 守护"的破坏实验时，P0-5 红灯。

### 2.2 P1（应做）：共享 state 写入的结构加固

**目标**：把 slim 专属写路径触及 legacy 共享 state 的风险，从"靠 caller 记得加 gate"降为"断言 fail-fast"。**只堵 slim 专属写，不动 dispatch 共享分支**（见 1.2 nuance）。

| # | 写入点 | 当前守护 | 加固做法 | file:line |
|---|---|---|---|---|
| P1-1 | `applySlimColdStartSnapshot` 写 `SessionListState.sessions` | 仅靠 caller（slim 冷启动）纪律 + `commitIfSlimTokenCurrent` | 入口加 `check(isSlimMode()) { "cold-start snapshot must not apply in legacy mode" }` | `SessionSyncCoordinator.kt:3354` / 写 `:3432-3439` |
| P1-2 | `mergeSlimMessagesIntoChat` 合并进 `ChatState.messages` | 经 `reconcileSession:2401` gate 间接守护 | 入口加 slim 断言；并 pin 现有 `liveSessionId==sid`(:2808) 守护 | `:3307` / 调用 `:2809` |
| P1-3 | `ClearLocal` 清 `messages/partsByMessage` | 经 reconcile gate | 同断言（与 P1-2 同函数 `applyReconcileResult`） | `:2794-2799` |
| P1-4 | `byDirectory MERGE` 路径固化 | 已是结构修复 | 补 pin 测试：fetched 仅覆盖其 directory，其余 directory session 存活（锁死历史 bug 6bf7bf7 的修复） | `:3430-3431`，回退 FULL REPLACE `:3438-3439` |

**做法细节**：引入轻量 helper（不重写）包裹 P1-1/P1-2/P1-3 入口：

```kotlin
private inline fun slimOnlyStateWrite(label: String, block: () -> Unit) {
    check(isSlimMode()) { "slim-only state write [$label] invoked in legacy mode — leak risk" }
    block()
}
```

`check` 在 release 也生效（比 `assert` 强），代价仅一次 boolean 读取。若担心 release 开销或 slim mode 翻转瞬态误抛，可用 `BuildConfig.DEBUG` 门控。**不**拆 state slice、**不**改 dispatchSseEvent 共享分支。

**验收**：P0 全绿 + slim 全套测试（`SlimTest`/`ResyncTest`/`CadenceTest`/`LastErrorTest`）全绿；破坏实验：在 legacy 调 `applySlimColdStartSnapshot` 应抛 `IllegalStateException` 而非静默写。

### 2.3 P2（可选）：传输层加固

**判定：当前自门控够用，不需要物理分离双 client。仅加一个断言测试。**

拦截器双门闩（`SlimapiVersionInterceptor.kt:39-42` / `SlimapiCapabilitiesInterceptor.kt:43-46`：`!hostConfig.slim` AND 路径前缀）已较强；`OpenCodeRepositorySlimapiEndpointsTest.kt:1328-1332` 已 pin `isSlimMode` getter 反射。物理拆 client 牵动 `OkHttpClientFactory.baseBuilder` 共享链（`SlimapiVersionInterceptor.kt:17-21` 自述三条链路都挂共享链），改动面大、收益边际。

**改动**：扩展 `OpenCodeRepositorySlimapiEndpointsTest.kt`，加 1 个 wire-shape 断言——用例 `legacy repo never emits slimapi headers or path`：slim=false 下对所有 REST 方法发请求，断言请求**无** `X-Slimapi-Version`/`X-Slimapi-Capabilities` 头、路径**无** `/slimapi/` 前缀。

**验收**：该测试通过；破坏实验（删 `:39` 的 `!hostConfig.slim` 门）时测试红灯。

### 2.4 任务分解与依赖序

```
P0（回归闸） ──┐ 独立，可立即开工，无生产改动
              ├─ 可与 P2 并行（P2 也独立，纯加测试）
P2（传输断言）─┘

P1（写所有权断言）→ 依赖 P0 完成（否则新断言无回归牙齿）
   └─ P1-4 pin 测试 与 P0 同类，可并入 P0 批次
```

| 步骤 | 内容 | check.sh 验证点 | 依赖 |
|---|---|---|---|
| ① | P0-1~P0-6 + P1-4 pin 测试（新建 LegacyGoldenPathRegressionTest） | `check.sh` 绿，且新测试实际跑（看报告行数） | 无 |
| ② | P2 传输断言（扩 OpenCodeRepositorySlimapiEndpointsTest） | `check.sh` 绿 | 无（与①并行） |
| ③ | P1-1~P1-3 写所有权断言（slimOnlyStateWrite 包裹） | `check.sh` 绿，含 slim 全套；破坏实验红灯 | ①完成 |

每步结束跑 `./scripts/check.sh`（默认即可，P0/P1/P2 全是 JVM 单测）。③额外手动跑破坏实验确认断言生效。

### 2.5 风险与回退

| 风险 | 影响 | 缓解 |
|---|---|---|
| P1 断言 `check(isSlimMode())` 在某条合法 slim 路径上**误抛**（如 slim token 切换瞬间 isSlimMode 翻转） | slim 测试红、生产崩溃 | ③完成后跑全套 slim 测试（CadenceTest/ResyncTest/SlimTest）；若误抛，把 `check` 收窄为 `BuildConfig.DEBUG`；最坏回退 = 移除该行断言（P1 加性，回退零代价） |
| P0 测试与现有 slim 测试共享 fake/fixture 漂移 | 测试误红 | 复用 `SeedFixture.kt`、`SessionSyncCoordinatorTest` 已有 fixture，避免新造轮子 |
| 误把 dispatch 共享分支当渗漏点去"加固" | 破坏 legacy 流式渲染（共享是正确设计） | **明确禁止**改 `dispatchSseEvent:1270/:1362-1460`；P1 只动 slim 专属写路径 |
| check.sh 默认链不跑插桩 → P0 只覆盖 JVM 层 | 残余盲区 | 显式承认（见 3.6）；P0 是"最低成本拦截"非"完全覆盖"；渲染层模拟器烟雾测试列为未来工作 |

**回退总原则**：P0/P1/P2 全部**加性**（新测试 + 入口断言 + 1 个传输断言），**不改生产行为**。任一步出问题，删除对应新增代码即可回退，无半成品状态。

---

## 三、报告（评估结论）

### 3.1 三层隔离评分汇总

| 层 | 评分 | 一句话根因 |
|---|---|---|
| **传输层** | **7/10** | 路径分流(`SSEClient.kt:137`)+拦截器双门闩(`SlimapiVersionInterceptor.kt:39-42` / `SlimapiCapabilitiesInterceptor.kt:43-46`)+6 处 byte-for-byte 注释，加性纪律扎实；扣分项：OkHttp client 全共享 + SseEventBridge 汇流缝（自门控非物理隔离） |
| **状态机层** | **4/10** | slim 机制长在 legacy 共享 state 脊柱上，靠 7 处 `isSlimMode()` 点补丁区分；slim 专属写路径（冷启动快照/reconcile 合并）直接写 `SessionListState.sessions`/`ChatState.messages`，忘 gate 即渗漏 |
| **渲染+测试层** | **4/10** | 渲染双轨做对（`AppStateSlices.kt:410` partExpandStates vs `StoreState.kt:40` expandedParts 物理分离）；但测试层断档——check.sh 无 legacy 协调器端到端回归，slim 改动破坏 legacy 时无声 |

### 3.2 已确认渗漏点清单（按严重度）

🔴 **严重（阻塞"无声破坏"）**
- **check.sh 无 legacy 回归闸**（`scripts/check.sh:18-22` 仅 compile+单测）：slim PR 破 legacy 无红灯。**P0 直击**。
- **slim 冷启动快照写共享 `SessionListState.sessions`**（`SessionSyncCoordinator.kt:3432-3439`，FULL REPLACE 回退 `:3438`）：历史最重 bug 现场，仅靠 `byDirectory MERGE`(`:3430`) + caller 纪律守护。**P1-1/P1-4 直击**。

🟠 **重要（应修）**
- **reconcile 合并/清理写共享 `ChatState.messages`**（`mergeSlimMessagesIntoChat:3309` / `ClearLocal:2794-2799`）：经 `:2401` gate 间接守护，无入口断言。**P1-2/P1-3**。
- **2 处点补丁 gate 守共享写**（`:1701` session.error banner、`:1909` questions 分流）：少加即渗漏，无断言兜底。**P1 范式覆盖**。

🟡 **建议（可选）**
- **OkHttp 共享 client + 自门控拦截器**：非物理隔离，一个 gate 写错即 legacy 被污染。**P2 加断言测试**。
- **SseEventBridge 汇流**（slim digest + legacy 事件同进 `dispatchSseEvent:894`）：设计如此，加文档+测试覆盖即可，不需结构改。

### 3.3 历史 bug 模式总结

| commit | 模式 | 证据核验状态 |
|---|---|---|
| `6bf7bf7` cold-start FULL REPLACE → byDirectory MERGE（结构改善） | 先污染后补 | ✅ 代码注释自述根因 `SessionSyncCoordinator.kt:3411-3429`（"374 sessions 被 100 替换→列表消失"） |
| `109eb1a` resync 外层粗 focus gate → 移除（结构改善） | 先污染后补 | ✅ `docs/post-v0.11.7-handoff.md:13`「T2 窄化 focus gate」 |
| `c161ca6` stale watermark+空窗 → forceInitialWindow 绕过（点补丁） | 先污染后补 | ⚠️ 未在 docs 命中，模式描述与代码 forceInitialWindow 机制吻合，**需 git log 复核** |
| `d0842f4` catch-up set ~150 → focus+dirty（结构改善） | 先污染后补 | ⚠️ 未在 docs 命中，与 `performResyncCatchUp:2981` 语义吻合，**需 git log 复核** |

> **模式定性**：4 次中 3 次"结构改善"、1 次"点补丁"。结构改善值得肯定，但**触发机制一致——都是"先渗漏、再被发现、才修"**，说明缺前置回归闸。`c161ca6` 的点补丁（forceInitialWindow 绕过）尤其危险，是典型"绕过而非分离"。

### 3.4 任务 2 预期收益

**定性**：
- 把 slim→legacy 渗漏从"靠人记 gate"转为"靠测试+断言机器拦截"。
- slim 后续演进（v0.22 adapt、新 digest 帧等）有 legacy 不退化基线兜底。

**可量化指标**：
- ✅ 回归闸后，slim PR 若在 legacy 触发冷启动快照/reconcile 写，`./scripts/check.sh` **必红灯**（P0-4/P0-5 + P1-1/P1-2 断言）。
- ✅ 新增 legacy 协调器级测试 ≥6 条（P0-1~P0-6），legacy 测试从"零散单例"升为"系统化套件"。
- ✅ slim 专属写函数入口断言 100% 覆盖（`applySlimColdStartSnapshot`/`mergeSlimMessagesIntoChat`）。
- ✅ check.sh 默认链零改动接入（P0 纯加测试类）。
- 📉 残余盲区：渲染层模拟器端到端回归仍缺（见 3.6）。

### 3.5 非目标 / 不做的事（YAGNI）

- ❌ 拆 ChatState/SessionListState 为 slim/legacy 双 slice（改动面过大，无回归闸兜底下风险不可接受；列为未来工作）。
- ❌ 双 OkHttp client 物理分离（自门控够用，P2 断言即可）。
- ❌ 重写 `dispatchSseEvent` 共享流式分支（共享是正确设计，见 1.2 nuance）。
- ❌ 把 `connectedDebugAndroidTest` 接入 check.sh 默认链（需模拟器+.env，违反"改动校验必做"的轻量原则；留给 `--full` 或手动）。
- ❌ 追求 100% legacy 渲染覆盖（P0 是协调器级 JVM 回归，非渲染像素级；够用即止）。

### 3.6 不确定项（需进一步验证）

1. **`c161ca6` / `d084f4` 两个 commit 未在 docs 核中**——bug 模式描述与代码机制吻合，但具体 hash 待 git log 复核。**不影响结论**（另 2 个已确证 + 代码注释自述根因）。
2. **P1 `check(isSlimMode())` 是否会在 slim token 切换瞬态误抛**——slim mode 翻转时机（`EffectiveConnectionConfigResolver.kt:114/137`）与冷启动快照调用的时序关系需在 ③ 步用全套 slim 测试验证；若误抛，收窄为 `BuildConfig.DEBUG`。
3. **渲染层模拟器端到端回归盲区**——P0 只到协调器 JVM 层，Compose 渲染（streamingPartTexts→UI、tool 折叠视觉）在 slimMode=false 下仍无自动回归。建议作为任务 2 之后的独立烟雾测试项（用 `./scripts/emulator.sh` 手动跑，不进 check.sh 默认链）。
4. **`OpenCodeRepository.kt` 6 处 byte-for-byte 注释是否都有对应 wire-shape 测试 pin**——`OpenCodeRepositorySlimapiEndpointsTest.kt:1308-1312` 注释暗示有 T2/T3/T4 覆盖，但 6 处逐一核对未做；P2 传输断言可顺带补齐。

### 3.7 可审计引用（证据 file:line 清单）

**传输层（已核验）**
- `SSEClient.kt:137` 路径加性分流
- `SlimapiVersionInterceptor.kt:39-42`、`SlimapiCapabilitiesInterceptor.kt:43-46` 双门闩
- `OpenCodeRepository.kt:1222/1337/1404/1470/1769/2022` byte-for-byte（6 处）
- `EffectiveConnectionConfigResolver.kt:114/137`、`ConnectionBootstrapEngine.kt:125` slim 传播链

**状态机层（已核验）**
- `isSlimMode()` 7 处：`SessionSyncCoordinator.kt:1701/1909/2401/2482/3021/3148`（+注释 :1695）
- `applySlimColdStartSnapshot` 写 sessions：`:3354`（函数）/ `:3432-3439`（写）/ FULL REPLACE 回退 `:3438` / byDirectory MERGE `:3430` / 根因注释 `:3411-3429`
- `applyReconcileResult`→ClearLocal：`:2794-2799`（注：初评列 :2794-2798，实为 :2794-2799）
- `mergeSlimMessagesIntoChat`：`:3307`（函数）/ 调用 `:2809`
- `dispatchSseEvent`：`:894`（函数）/ message.updated `:1270-1274` / message.part.* `:1362-1460` / 会话门 `:1259`（不守 isSlimMode，正确共享）

**渲染层（已核验）**
- `Message.kt:225/235` hasFull/omitted nullable
- `PartExpandState.kt:207` eligibility 三元过滤
- `AppStateSlices.kt:410` partExpandStates（注：初评列 :394，:394 实为 streamingPartTexts，partExpandStates 在 :410）/ `:394` streamingPartTexts / 双轨自述 `:406-408`
- `StoreState.kt:40` expandedParts

**测试层（已核验）**
- `scripts/check.sh:18-22` 仅 compile+单测，无 connectedAndroidTest/无 legacy 基线
- `SSEClientSlimModeTest.kt:152-156` legacy `/global/event` 路径**有** pin（注：初评称"无任何 legacy 测试"过度，传输层路径选择有保护；缺的是协调器级端到端）
- 协调器测试目录 `app/src/test/.../ui/controller/`：无 `*Legacy*Test.kt`（glob 确认）；`slimMode=false` 仅零散单例（`LastErrorTest:517/544`、`SlimTest:248`、`ResyncTest:1335`）

**历史 bug（部分核验）**
- `109eb1a` ✅ `docs/post-v0.11.7-handoff.md:13`
- `6bf7bf7` ✅ `SessionSyncCoordinator.kt:3411-3429` 代码注释自述
- `c161ca6` / `d0842f4` ⚠️ 待 git log 复核

---

> **rev-glm 结论**：本方案**有条件通过**——隔离治理方向正确（回归闸优先 + 写所有权断言），P0 立刻可做、零基础设施改动、立即降低"无声破坏"风险。条件是：① 严格守住"不改 dispatch 共享分支 / 不拆 state slice"的红线；② P1 断言必须先过全套 slim 测试确认无误抛；③ `c161ca6`/`d0842f4` 与 P1 瞬态误抛两个不确定项在实施中闭环验证。若这三条落实，任务 2 能把 slim↔legacy 从"靠 gate 擦边"推进到"靠机器兜底"。
