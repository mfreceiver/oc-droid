# R-19 路线图（Roadmap）

> R-18 已闭环并推送 main（commit `b119517`，Final 5 路终审 maxer 10.0 / glmer 9.7 / kimo 9.6 / dser 9.5 / gpter 9.5）。
> 本文件是 R-18 遗留技术债的 **重要性评估 + 处理思路 + 执行顺序**，作为 R-19 epic 的权威规划。
> 评估维度：用户可感知风险 / 正确性 / 可维护性拖累 / 解锁覆盖率的杠杆 / 工程量。
> 全部项均经 R-18 各阶段 gate 评审确认 **非发版阻塞**。

---

## R-18 交付基线（R-19 起点）

| 指标 | R-18 终态 |
|---|---|
| 单测总数 | 1697（+995 新增） |
| kover line（unit-testable，排除纯 Composable/theme/Activity 后） | 57.9%（4456/7696） |
| kover branch（unit-testable） | 55.1% |
| kover 阈值（防回归 floor） | line 55 / branch 52 / instruction 52 |
| 全量 line（含 Composable） | 34.3% |
| check.sh --full | 全绿（compile + 单测 + lint[HardcodedText+MissingTranslation error] + koverVerify + assembleRelease） |
| SharedStateStore 写权限 | private + asStateFlow + mutateXxx（单 module 最强屏障） |
| 全局 currentDirectory | 彻底消除（含 createSession 显式 directory） |
| EffectBus | SharedFlow(256, SUSPEND) + uiEvents(64, DROP_OLDEST)，36 处 A/B/C 分类 |

---

## 一、第一梯队：真实可靠性/正确性边际风险（R-19 P0-P1，应优先）

### 1. P1-10 SSE gap reconciliation 统一状态机

- **来源**：gpter 🟡 + dser 🟠 + R-18 原计划 P1-10
- **当前状态**：断线重连靠 `gapInfo` slice + `loadMessages(resetLimit=true)` + catch-up 各自兜底，**无统一 invariant**。
- **风险**：极端网络（半开连接、server 重启、host 切换后旧 SSE late event）下可能短暂消息/状态不一致。dser/maxer 标"风险中，自愈但非稳健"。
- **重要性**：🔴 高（可靠性天花板）
- **处理思路**：
  ```kotlin
  data class SseSyncState(
      val connectedOnce: Boolean = false,
      val lastDisconnectAt: Long? = null,
      val sessionsDirty: Set<String> = emptySet()
  )
  sealed class SseSyncDecision {
      data class ReloadSession(val sessionId: String) : SseSyncDecision()
      data object RefreshSessions : SseSyncDecision()
      data object ClearDeltaBuffers : SseSyncDecision()
  }
  ```
  在 SessionSyncCoordinator 集中决策"重连后哪些 session reload / 哪些 delta buffer flush/clear"。
- **测试**：表驱动覆盖 4 场景（delta 中断 reconnect / reconnect 后 session.status idle 乱序 / 断线期间 currentSession 切换 / host reconfigure 后 late event）
- **工程量**：中（~1 周）
- **依赖**：无

### 2. SSE currentWorkdir 语义收敛

- **来源**：gpter Final 🟠（次要）
- **当前状态**：`ConnectionCoordinator.startSSE` 仍用 `settingsManager.currentWorkdir`，多 workdir 下"当前 workdir"语义模糊。P1-9 fan-out 已缓解 pending questions，但 SSE 本身是单 workdir 连接。
- **风险**：多 workdir 用户切到后台 workdir 时，其 SSE 事件路径不闭合（靠 catch-up 补全）。
- **重要性**：🟠 中（多 workdir 用户体验）
- **处理思路**：先**明确产品语义**（单 SSE + 多 workdir 靠 catch-up？还是未来多路 SSE？）→
  - 若单 SSE（推荐 ROI 高）：文档化 + 强化 catch-up 边界测试
  - 若多路：架构改造（大工程）
- **工程量**：小（决策 + 文档 + catch-up 边界测试），前提是单 SSE 决策
- **依赖**：与 P1-10 同批

### 3. uiEvents MutableSharedFlow deviation + 37 处直 tryEmit

- **来源**：glmer ⚠️ + kimo ⚠️
- **当前状态**：`SharedEffectBus.uiEvents` 仍是 `MutableSharedFlow`（应为只读 SharedFlow），37 处 out-of-scope 调用方绕过 `tryEmitUiEvent` wrapper。当前行为一致（wrapper 仅转发），但 API 表面腐化。
- **风险**：未来维护者可能误用（如非 suspend 上下文 `_uiEvents.emit`）
- **重要性**：🟠 中（API 腐化）
- **处理思路**：机械迁移 37 处 `effectBus.uiEvents.tryEmit(...)` → `effectBus.tryEmitUiEvent(...)`，然后 `uiEvents` 降级 `SharedFlow`。
- **工程量**：小（半天），纯收敛无行为变化
- **依赖**：无

---

## 二、第二梯队：架构可维护性债（R-19 P2，"按需触发"已成熟）

### 4. P2-5 VM 精确注入（去 core 万能访问）

- **来源**：kimo 🟡 + R-18 原计划 P2-5
- **当前状态**：6 VM 仍注入整个 `AppCore`（@Singleton）。无内存泄漏（VM clear 释放），但违背单一职责——任何 VM 能触达所有 slice/controller。
- **重要性**：🟡 中（长期架构清晰度拖累，非正确性）
- **处理思路**：先明确 controller 生命周期分层（app-level singleton vs VM-level scoped），再各 VM 注入所需 slice/repository/controller。
- **前置依赖**：P2-2 dispatcher 拆（否则写 slice 还得过 AppCore.writeXxx）
- **工程量**：大（6 VM 构造器重写 + controller 实例化分散化）
- **触发条件**：VM 数量增长 / 测试隔离需求增强

### 5. P2-2 AppCore dispatcher 按域拆

- **来源**：R-18 原计划 P2-2
- **当前状态**：`dispatchEffect` 是 22 分支大 `when`（已达上限）。sealed `ControllerEffect` 保证完备性，但单函数认知负担重。AppCore+Orchestration 620 行已达原触发线。
- **重要性**：🟡 中（认知负担）
- **处理思路**：拆 5 个域 dispatcher 为 AppCore **internal 成员函数**（非扩展，避免访问 private；gpter R-18 修正）+ 返 Boolean + `assertExactlyOneHandled`（防新增 effect 静默 no-op）。
- **工程量**：中
- **依赖**：无（P2-5 的前置）

### 6. P2-4 SSE reducer Pair<State, SideEffect>

- **来源**：gpter 🟡 + dser 🟡 + R-18 原计划 P2-4
- **当前状态**：22 个 applyXxx 已是纯函数（R-18 Phase 5 覆盖），但 side-effect sequencing 是半形式化（dispatchSseEvent 内散布 emit）。
- **重要性**：🟡 中（可测性 + 清晰度）
- **处理思路**：applyXxx 返回 `Pair<State, List<SseSideEffect>>`，dispatcher 用 **CAS updateAndGet** 提交 state + 分发 effects（业务命令走 emitEffect，UI 反馈走 tryEmitUiEvent）。
- **前置依赖**：P1-3 EffectBus（R-18 已就绪）
- **工程量**：大（22 reducer 改签名 + 所有 caller 解构 + 表测试扩展）

---

## 三、第三梯队：覆盖率杠杆 + 小重构（opportunistic）

### 7. 80% line（用户明确目标）—— R-19 独立 epic

- **来源**：用户明确要求 + R-18 Phase 5 文档化
- **当前状态**：unit-testable 57.9%，天花板 ~62-68%。真正 80% 需 androidTest。
- **处理思路（分 3 子项）**：
  - **(a) Compose UI test 套件**（大工程，季度）：ComposeTestRule + HiltAndroidRule，先覆盖关键路径（启动→会话列表→发消息→pending question→permission→文件预览→session tab 切换），再扩 Composable 全覆盖。kover 启用 androidTest instrumentation。
  - **(b) private helper 提取为 internal 纯函数**（~130 行，低风险）：ChatScreen 4（currentSessionActivity/bestSessionActivityText/formatStatusFromPart/formatThinkingFromReasoningText）+ ChatTextParts 4（fenceMarkerOf/splitCodeAndProse/codeText/codeFenceLanguage）+ ChatInputBar 1（handleComposerSend）+ ChatContextUsageDialog 2（formatCount/formatOptionalCount）+ ChatMessageContent 1（markerLabelFor），按 `visiblePickerProviders` 模式（R-18 Phase 5 已验证）提取到独立文件单测。+1-2pp。
  - **(c) dispatchEffect 22 分支逐 ControllerEffect 全 mock**（纯 unit-test 可达，+2-3pp）。
- **优先级**：(c) 最便宜先做 → (b) 中 → (a) 季度工程
- **目标**：R-19 先 (b)+(c) 拿到 ~62-65%，(a) 作为季度工程冲 80%

### 8. dropped/unknown counter UI 暴露

- **来源**：maxer 🟡 + glmer 建议
- **当前状态**：`SharedEffectBus.droppedEffectCount()` / `SessionSyncCoordinator.unknownEventCountsSnapshot()` 是 public 但 0 个 UI 消费者。生产 dropped 发生时只有 log。
- **重要性**：🟡 中（可观测性）
- **处理思路**：DebugLogSection 加两个 panel（Effect Bus dropped / SSE unknown events）。
- **工程量**：小（半天）

### 9. editFromMessage / openSubAgent 闭包 self-ref 理论风险

- **来源**：maxer 🟡（Phase 3）
- **当前状态**：闭包调 `this@VM.method`（loadMessages/selectSession），VM clear 后 onSuccess 触发理论可 NPE。概率极低（revertSession 30s 飞 + VM clear + 仍 onSuccess）。
- **重要性**：🟡 低（理论可触发）
- **处理思路**：闭包内改调 free function（`core.loadMessagesForEffect(...)` 而非 `this@ChatViewModel.loadMessages`），或 onSuccess 入口加 `if (!isActive) return`。
- **工程量**：小，与 P2-5 同批做更自然

### 10. applySavedSettings isConnecting/phase 冷启动短暂不一致

- **来源**：dser 🟡 + kimo 🟠
- **当前状态**：设 `connectionPhase=Reconnecting` 时未同步 `isConnecting=true`，冷启动空状态只显文案无 spinner（窗口极短，coldStartReconnect 立即覆盖）。
- **重要性**：🟡 低（UX 细节）
- **处理思路**：applySavedSettings 内同步设 isConnecting。
- **工程量**：极小（1 行），与 P1-10 同批

---

## 四、第四梯队：cosmetic（触碰时顺手，低优先）

| # | 项 | 处理 | 触发时机 |
|---|---|---|---|
| 11 | AppStateSlices stale 注释（引用已删 MainViewModel._xxxFlow） | 改为 SharedStateStore | 触碰该文件时 |
| 12 | ConnectionCoordinator settled helper 提取（maxer 早期 P2-N1） | try/finally 提 `invokeSettled(value)` | 触碰该文件时 |
| 13 | C-1 applyPartDeltaLeadingEdge 双重载合并 | glmer 指出原方案描述错（knownType 外部传入非查 partsByMessage），需保留两组参数 | 低优 |
| 14 | C-3 flushJobs ConcurrentHashMap + compute/remove 原子 | 当前 main 线程安全，防御性 | dispatcher 变化时 |
| 15 | C-6 property-based testing | kotest-property，22 reducer 不变量（如 applySessionUpsert 后 id 集合不变） | 覆盖率提升时 |
| 16 | C-7 SettingsRepository 接口 + draft 增量 patch | 性能优化，先量化是否瓶颈 | draft 写入成瓶颈时 |
| 17 | C-9 PartStateSerializer fuzz | 序列化边界，配合 7(a) | Compose test 时 |
| 18 | C-10 ChatScreen 大文件拆分 | ChatRoute（状态收集）+ ChatScreenContent（纯参数）+ ChatScrollStrategy，配合 7(a) | Compose test 时 |
| 19 | C-5 中文注释密度 | RFC 引用迁 docs，代码留简洁英文 | 触碰时 |

---

## 五、建议的 R-19 执行顺序（按 ROI）

```
R-19 Sprint 1（可靠性 + 收敛，~1.5 周）
├── #1 P1-10 SSE gap 状态机（可靠性天花板）+ #10 applySavedSettings isConnecting
├── #3 uiEvents wrapper 统一（37处收敛）
├── #2 SSE currentWorkdir 语义文档化 + catch-up 边界测试
└── #8 counter UI 暴露
Gate: glmer+momo+gpter 9.5

R-19 Sprint 2（覆盖率快赢 + 架构启动，~1 周）
├── #7(c) dispatchEffect 22 分支逐 effect mock（+2-3pp，纯 unit-test）
├── #7(b) private helper 提取（+1-2pp）
└── #5 P2-2 AppCore dispatcher 拆（成员函数+返 Boolean+assertExactlyOneHandled）
Gate: glmer+momo+gpter 9.5  → unit-testable ~62-65%

R-19 Sprint 3（深度架构，~2 周）
├── #4 P2-5 VM 精确注入（依赖 P2-2 完成）
├── #6 P2-4 SSE reducer Pair（依赖 P1-3 已就绪）
└── #9 闭包 self-ref（与 P2-5 同批）
Gate: 5 路终审

R-19 Quarterly（Compose UI test 大工程）
└── #7(a) Compose UI test 套件 + kover androidTest → 冲 80% line
```

---

## 六、总体判断

- **发版阻塞**：0（R-18 已闭环，所有 P0/P1 核心改造 grep 验证 0 残留）
- **最值得马上做**：#1 P1-10 SSE gap 状态机（可靠性边际风险）+ #3 uiEvents 收敛（API 腐化）+ #2 SSE currentWorkdir 语义（多 workdir 用户）
- **最大杠杆但最贵**：#7(a) 80% line 的 Compose UI test 基础设施（季度工程）
- **最便宜快赢**：#7(c) dispatchEffect 全 mock（+2-3pp）+ #7(b) private helper 提取（+1-2pp）+ #8 counter UI 暴露
- **依赖关系**：P2-5 依赖 P2-2；P2-4 依赖 P1-3（已就绪）；#9 与 P2-5 同批；#7(b)/(c) 独立可并行

---

## 七、与 R-18 的衔接

R-18 已完成的项（R-19 不再处理）见 `docs/tech-debt/R18-pending-issues.md` 第五节"已闭环"。R-19 接续 R-18 的：
- 写权限收敛（P0-9）→ R-19 P2-5 VM 精确注入（架构层面收口）
- EffectBus（P1-3）→ R-19 P2-4 SSE reducer（reducer 层面形式化）
- directory 显式化（P0-1/P0-5）→ R-19 #2 SSE currentWorkdir 语义收敛
- 覆盖率基线（57.9%）→ R-19 #7 冲 80%
