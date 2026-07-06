# R-19 执行计划（Execution Plan）

> 本文件是 R-19 技术债清理的**可执行实施方案**，基于 `R19-roadmap.md`（权威路线图）+ `R18-pending-issues.md`（R-18 闭环与共识）+ 代码库实测 recon（2026-07-06，post-R-18 `b119517`）。
> 纪律：先评审完善方案 → 分 3 sprint 执行 → 每 sprint gpter+glmer 9.5 门控 → 全部完成加 dser+kimo 4 路共同门控 → 发版（versionName +0.0.1）。
> 权威构建/发版/版本号规则见 `AGENTS.md` + `docs/build-apk.md`；本文件不重复，只标注 R-19 特有的执行约束。

---

## 0. 基线与起点（实测确认）

| 指标 | 实测值（recon 2026-07-06） |
|---|---|
| R-18 闭环 commit | `b119517` |
| Post-R-18 main 状态 | 仅 2 个 R-19 docs commit（`c68d517`/`0c08d57`），**无新代码** |
| versionName / versionCode | `0.4.1` / `40`（`app/build.gradle.kts:32-33`） |
| kover 阈值（floor） | line 55 / branch 52 / instruction 52（`build.gradle.kts:238-240`） |
| kover 实测（unit-testable） | line 57.9% / branch 55.1% |
| 单测总数 | 1697 |
| `TODO/FIXME/HACK/XXX` 残留 | **0**（main 源全清） |
| `uiEvents.tryEmit` 直调点 | **33 处**（7 文件；roadmap 称 37，差异待 Sprint 1 实测核实） |
| 注入整个 AppCore 的 VM 数 | **8 个**（roadmap 称 6；实测 ChatViewModel/SessionViewModel/ConnectionViewModel/ComposerViewModel/OrchestratorViewModel/HostViewModel/SettingsViewModel/FilesViewModel） |
| dispatchEffect 分支 | 22（`AppCore.kt:252`），无 `assertExactlyOneHandled` |
| SSE applyXxx 纯函数 | 21 个（20 返 State，1 已返 `Pair<State,Boolean>`；均无 `Pair<State,List<SseSideEffect>>`） |

---

## 1. 评审要点回答（执行前必读）

### 1.1 依赖关系准确性

| 声称的依赖 | 实测核实 | 裁决 |
|---|---|---|
| P2-5 依赖 P2-2（R-19 roadmap §4） | ✅ 准确。VM 精确注入需先明确域边界；P2-2 把 22 分支拆成 5 域后，P2-5 的 per-VM slice 决策更清晰 | **采纳**：Sprint 2 做 P2-2，Sprint 3 做 P2-5 |
| R-18 §7 图标 P2-5 → P2-2（反向） | ⚠️ **与 R-19 冲突**。R-18 称 P2-5 是 P2-2 前置（理由"mutateXxx 调用方式确定"） | **驳回 R-18 方向**：P0-9 已把 mutateXxx 终态化，该理由失效；P2-2 自包含（AppCore 内拆 when），风险低于 P2-5（改 DI 图）。按 R-19 方向：P2-2 先 |
| P2-4 依赖 P1-3 EffectBus | ✅ 准确。P1-3（SharedFlow+SUSPEND+dropped counter）R-18 已就绪，P2-4 的 side-effect 分发走 emitEffect/tryEmitUiEvent | **就绪**，Sprint 3 可做 |
| #9 与 P2-5 同批 | ✅ 准确。#9 改 ChatViewModel/SessionViewModel 闭包，P2-5 重写这俩 VM 构造器 | **合并**：Sprint 3 内 #9 随 P2-5 同批 |
| #2 与 P1-10 同批 | ✅ 准确。均涉及 SSE 重连语义，文档化 + 测试可并行 | **采纳** |

### 1.2 工程量估算核实

| 项 | roadmap 估算 | 实测核实 | 调整 |
|---|---|---|---|
| P1-10 SSE gap 状态机 | 中（~1 周） | SessionSyncCoordinator **1196 行**，ForegroundCatchUpController 已有 3-tier 状态机；新增 SseSyncState 是**叠加决策层**不替换现有 | ✅ 合理，偏上限 |
| #3 uiEvents 收敛 | 半天，37 处 | 实测 **33 处/7 文件**（AppCoreOrchestration 9 / ChatVM 7 / SessionVM 7 / ConnectionCoord 3 / HostProfileCtrl 4 / SessionSyncCoord 2 / OrchestratorVM 1） | ✅ 略乐观，改 0.5-1 天 |
| P2-2 dispatcher 拆 | 中 | AppCore.kt 340 行，22 分支映射到 5 域已清晰（见下表） | ✅ 合理 |
| P2-4 SSE reducer Pair | 大（22 reducer） | 实测 **21 个**，1 个已返 Pair，剩 20 改签名 + 所有 caller 解构 | ✅ 合理 |
| **P2-5 VM 注入** | **大（6 VM）** | ⚠️ **低估**：实测 **8 VM**；且 controller 当前在 AppCore.kt 内实例化（app-level singleton），要迁到 Hilt DI 图才能注入 VM | **上调**：8 VM + DI 图迁移，估"大+"（Sprint 3 最大风险项） |
| #7(c) dispatchEffect mock | +2-3pp，纯 unit | R-18 §8 低 ROI 区曾标"需大量 mock，与 P2-2 拆分冲突" | **调整顺序**：Sprint 2 内 **P2-2 先于 #7(c)**，mock 5 域 dispatcher 而非单体 when |
| #7(b) helper 提取 | ~130 行，+1-2pp | 实测 5 文件 ~130-140 LOC，`visiblePickerProviders` 模式已验证 | ✅ 合理 |
| #8 counter UI | 半天 | DebugLogSection.kt 264 行，加 2 panel | ✅ 合理 |
| #10 isConnecting | 1 行 | ConnectionActions.kt:114 | ✅ 准确 |
| #9 closure self-ref | 小 | ChatVM:144 + SessionVM:45，各加 `if (!isActive) return` | ✅ 准确 |

**dispatchEffect 22 分支 → 5 域映射**（P2-2 执行依据）：

| 域 | ControllerEffect 分支 |
|---|---|
| ForegroundCatchUp | ForceReconnect, GlobalColdStartRefresh, CancelSse, CatchUpAfterDisconnect |
| SessionSwitcher | LoadMessages, LoadChildSessions, LoadSessionStatus, LoadPendingQuestions, ClearDeltaBuffers |
| HostProfile | CancelSseForReconfigure, StartSse, HostProfileSwitched, ColdStartReconnect, ResetLocalDataAndResync, ClearSessionWindowCache |
| Connection | HostReconfigured, LoadSessions, LoadAgents, LoadProviders, LoadPendingPermissions, OnSseEvent |
| SessionSync | ServerConnected (+ RefreshSessions) |

### 1.3 R-18 后新出现的技术债

recon 结论：**无新代码技术债**。post-R-18 仅 2 个 docs commit；`TODO/FIXME/HACK/XXX` 全清。R-19 roadmap 的 10 项均为 R-18 gate 期间识别的遗留项，非新积累。**无需补入新项。**

### 1.4 并行/串行划分（写作用域级）

**Sprint 1（4 并行 lane）**：
- Lane A（P1-10 + #10）：SessionSyncCoordinator.kt + 新 SseSyncState/SseSyncDecision 文件 + ConnectionActions.kt
- Lane B（#3 uiEvents）：SharedEffectBus.kt + 7 caller 文件（33 处）
- Lane C（#2 currentWorkdir）：注释/文档 + catch-up 边界测试（test 文件）
- Lane D（#8 counter UI）：DebugLogSection.kt
- ✅ 四 lane 写作用域**零重叠**

**Sprint 2（2 阶段）**：
- 阶段 1 并行：P2-2（AppCore.kt）|| #7(b) helper 提取（ChatScreen/ChatTextParts/ChatInputBar/ChatContextUsageDialog/ChatMessageContent + 新 test）
- 阶段 2 串行：#7(c) dispatchEffect mock（test 文件）— **等 P2-2 完成**，mock 5 域 dispatcher

**Sprint 3（串行，高重叠）**：
- 先 P2-4（SessionSyncCoordinator.kt，相对独立）
- 后 P2-5 + #9 合并（8 VM + AppCore controller 所有权 + DI 图 + ChatVM/SessionVM 闭包守卫）— 同批因 #9 改的 VM 正是 P2-5 重写的

### 1.5 Sprint Gate 标准

每 sprint 除 gpter+glmer ≥9.5 外的技术 gate：

| Gate | Sprint 1 | Sprint 2 | Sprint 3 |
|---|---|---|---|
| `./scripts/check.sh --full` 全绿 | ✅ 必过 | ✅ 必过 | ✅ 必过 |
| koverVerify 不低于 floor | ✅（55/52/52） | ✅ + 阈值上调至 line 60/branch 56（冲 ~62-65%） | ✅（维持 S2 阈值） |
| 新测试覆盖规定场景 | ✅ P1-10 的 4 场景 | ✅ #7(b) 每提取 helper ≥1 case；#7(c) 每域 dispatcher ≥1 effect mock | ✅ P2-4 每个 applyXxx 返 Pair 验证；P2-5 每个 VM 注入编译通过 |
| 无新增 TODO/FIXME | ✅ | ✅ | ✅ |
| 每个 fixer 自检 compileDebugKotlin | ✅（不跑 check.sh，编排者统一跑） | ✅ | ✅ |
| 并行 fixer 写作用域零冲突 | ✅ 编排者派发前确认 | ✅ | N/A（串行） |

---

## 2. Sprint 1：可靠性 + 收敛（~1.5 周）

### 任务清单

#### S1-T1（Lane A）：P1-10 SSE gap reconciliation 统一状态机 + #10 isConnecting 同步

- **写作用域**：
  - `app/src/main/java/cn/vectory/ocdroid/ui/controller/SessionSyncCoordinator.kt`
  - 新文件 `app/src/main/java/cn/vectory/ocdroid/ui/controller/SseSyncState.kt`（`SseSyncState` + `sealed SseSyncDecision`）
  - `app/src/main/java/cn/vectory/ocdroid/ui/ConnectionActions.kt`（#10，1 行）
- **具体改动点**：
  1. 定义 `SseSyncState(connectedOnce, lastDisconnectAt, sessionsDirty)` + `sealed SseSyncDecision { ReloadSession, RefreshSessions, ClearDeltaBuffers }`（新文件）
  2. 在 SessionSyncCoordinator 增集中决策点：`fun reconcileGap(state: SseSyncState, event: SSEEvent): List<SseSyncDecision>`，在 `handleEvent` 的 `server.connected` 分支调用，**叠加在 ForegroundCatchUpController 现有 3-tier 之上**（不替换，作为显式 invariant）
  3. 决策结果驱动：ReloadSession→LoadMessages effect；RefreshSessions→loadSessions；ClearDeltaBuffers→clearDeltaBuffers()
  4. `ConnectionActions.kt:114` 设 `connectionPhase = ConnectionPhase.Reconnecting` 处同步 `isConnecting = true`
- **测试要求**（表驱动，新 test 文件 `SessionSyncGapReconcileTest.kt`）：
  - 场景 1：delta 中断后 reconnect → ClearDeltaBuffers + ReloadSession(currentSession)
  - 场景 2：reconnect 后先收 message.updated 再收 session.status idle → 不重复 reload（sessionsDirty 去重）
  - 场景 3：断线期间 currentSession 切换 → ReloadSession 指向新 currentSession
  - 场景 4：host reconfigure 后旧 SSE late event 到达 → 决策为 no-op（generation guard）
- **推荐方案**（不确定项授权）：SseSyncState 作为**叠加决策层**，不拆除 ForegroundCatchUpController。理由：现有 3-tier 已生产验证，叠加层风险最低且提供显式 invariant 给测试。

#### S1-T2（Lane B）：#3 uiEvents wrapper 统一 + uiEvents 降级只读

- **写作用域**：`SharedEffectBus.kt` + 7 caller 文件（AppCoreOrchestration.kt / ChatViewModel.kt / SessionViewModel.kt / ConnectionCoordinator.kt / HostProfileController.kt / SessionSyncCoordinator.kt / OrchestratorViewModel.kt）
- **具体改动点**：
  1. 33 处 `effectBus.uiEvents.tryEmit(...)` → `effectBus.tryEmitUiEvent(...)`（机械迁移）
  2. `SharedEffectBus.uiEvents: MutableSharedFlow` → `private val _uiEvents` + `val uiEvents: SharedFlow`（只读暴露）
  3. 保留 `uiEventsConsumed: SharedFlow`（已存在，确认是否冗余，若冗余删除）
- **测试要求**：现有 uiEvents 相关测试全绿；新增 1 case 验证 `tryEmitUiEvent` 经 wrapper 路径
- **注意**：实测 33 处（roadmap 称 37）。执行时 grep 确认精确数，以实测为准。

#### S1-T3（Lane C）：#2 SSE currentWorkdir 语义文档化 + catch-up 边界测试

- **写作用域**：`ConnectionCoordinator.kt`（注释）+ 新/现有 catch-up 测试文件
- **具体改动点**：
  1. 在 `ConnectionCoordinator.startSSE()`（line 431-432）加文档注释：明确**单 SSE + catch-up** 产品决策（多 workdir 靠 `loadPendingQuestionsAllWorkdirs` fan-out + ForegroundCatchUpController 补帧，非多路 SSE）
  2. 强化 catch-up 边界测试：多 workdir 切换、后台 workdir question 可见性、reconnect 后 currentWorkdir 一致性
- **测试要求**：catch-up 边界 case ≥3 个
- **推荐方案**：单 SSE（roadmap 推荐 ROI 高）。理由：多路 SSE 是大工程，单 SSE + catch-up 已生产可用，边际收益不抵改造成本。

#### S1-T4（Lane D）：#8 counter UI 暴露

- **写作用域**：`app/src/main/java/cn/vectory/ocdroid/ui/settings/DebugLogSection.kt`
- **具体改动点**：
  1. 新增 "Effect Bus dropped" panel：读 `SharedEffectBus.droppedEffectCount()`
  2. 新增 "SSE unknown events" panel：读 `SessionSyncCoordinator.unknownEventCountsSnapshot()`
  3. 沿用现有 Card section 模式（level filter chips 同级）
- **测试要求**：Composable preview 验证（不强制 unit test，UI test 属季度工程）

### Sprint 1 Gate

- [ ] `./scripts/check.sh --full` 全绿
- [ ] koverVerify ≥ floor（55/52/52），覆盖率不低于 R-18 终态 57.9%/55.1%
- [ ] P1-10 的 4 场景测试全过
- [ ] grep 确认 `effectBus.uiEvents.tryEmit` 残留 0 处
- [ ] gpter + glmer 并行评审 ≥9.5（复用 session 提速；低于则 fixer 修复阻塞项后重审）
- [ ] review 产物归档 `.opencode/runs/reviews/<date>/sprint1-*.json`

### Sprint 1 风险与回退

| 风险 | 回退 |
|---|---|
| SseSyncState 叠加层与 ForegroundCatchUpController 3-tier 行为冲突 | 保留 reconcileGap 为 no-op（返回 emptyList），不破坏现有；逐步启用 |
| uiEvents 降级只读后某处遗漏编译失败 | grep 全覆盖后降级；编译失败即补迁移 |
| catch-up 边界测试发现现有 bug | 记入 R-19 新 issue，不阻塞 Sprint（除非正确性阻塞） |

---

## 3. Sprint 2：覆盖率快赢 + 架构启动（~1 周）

### 任务清单

#### S2-T1（阶段 1，Lane A）：P2-2 AppCore dispatcher 按域拆

- **写作用域**：`app/src/main/java/cn/vectory/ocdroid/ui/AppCore.kt`
- **具体改动点**：
  1. 把 `dispatchEffect`（AppCore.kt:252）22 分支拆为 5 个 internal 成员函数（非扩展）：`dispatchForegroundCatchUpEffect` / `dispatchSessionEffect` / `dispatchHostEffect` / `dispatchConnectionEffect` / `dispatchSessionSyncEffect`（域映射见 §1.2 表）
  2. 每个 dispatcher 返 `Boolean`（handled）
  3. 顶层 `dispatchEffect` 串联 5 个 + `assertExactlyOneHandled`（debug 下未处理 effect 报 `DebugLog.w`）
  4. 跨域编排（sendMessage 等）保持在 AppCoreOrchestration.kt 不动
- **测试要求**：新增 `AppCoreDispatcherTest.kt`，每域 ≥1 effect 验证 handled=true；新增未知 effect（测试用 fake subtype）验证 handled=false + log

#### S2-T2（阶段 1，Lane C）：#7(b) private helper 提取为 internal 纯函数

- **写作用域**：
  - `ChatScreen.kt`（提取 currentSessionActivity/bestSessionActivityText/formatStatusFromPart/formatThinkingFromReasoningText）
  - `ChatTextParts.kt`（fenceMarkerOf/splitCodeAndProse/codeText/codeFenceLanguage）
  - `ChatInputBar.kt`（handleComposerSend）
  - `ChatContextUsageDialog.kt`（formatCount/formatOptionalCount）
  - `ChatMessageContent.kt`（markerLabelFor）
  - 新文件：按 `PickerProviderFilter.kt` 模式，提取到同包独立文件（如 `ChatScreenHelpers.kt`/`ChatTextPartHelpers.kt` 等）
- **具体改动点**：每个 private helper → `internal fun`（接收必要参数，无 Composable 依赖），原调用点改调提取函数
- **测试要求**：每提取函数 ≥1 case（边界 + 正常），目标 +1-2pp

#### S2-T3（阶段 2，Lane B，**等 S2-T1 完成**）：#7(c) dispatchEffect 22 分支逐 effect 全 mock

- **写作用域**：新/现有 `AppCoreDispatcherTest.kt`（test 文件）
- **具体改动点**：mock 5 域 dispatcher 的每个 ControllerEffect，验证 handled=true + 副作用（controller 方法被调）
- **测试要求**：22 个 effect 各 ≥1 case，目标 +2-3pp
- **依赖**：必须在 S2-T1（P2-2 拆分）后，否则 mock 单体 when 与拆分冲突（R-18 §8 已警告）

### Sprint 2 Gate

- [ ] `./scripts/check.sh --full` 全绿
- [ ] kover 阈值上调：line floor 55 → **60**，branch 52 → **56**（冲 ~62-65% unit-testable）；instruction 维持 52 或微调
- [ ] koverVerify 在新阈值下全过
- [ ] #7(b) 每提取 helper ≥1 case；#7(c) 每域 dispatcher 每 effect ≥1 mock
- [ ] grep 确认 `assertExactlyOneHandled` 存在 + 无单体 dispatchEffect when 残留
- [ ] gpter + glmer 并行评审 ≥9.5
- [ ] review 产物归档

### Sprint 2 风险与回退

| 风险 | 回退 |
|---|---|
| 阈值上调至 60/56 后 koverVerify 红 | 先跑 koverHtmlReport 看实际值；若未达 62-65%，临时阈值设 58/54，#7(b)/(c) 补测试后再升 |
| helper 提取破坏 Composable 重组（状态捕获变化） | 提取函数保持纯（无 lambda 捕获 Composable 状态）；编译 + 现有 UI 行为回归验证 |
| P2-2 拆分后某 effect 漏到 else | assertExactlyOneHandled 在 debug 抓；测试覆盖每域 |

---

## 4. Sprint 3：深度架构（~2 周）

### 任务清单（串行）

#### S3-T1（先）：P2-4 SSE reducer Pair<State, SideEffect>

- **写作用域**：`SessionSyncCoordinator.kt`
- **具体改动点**：
  1. 定义 `sealed SseSideEffect { ReloadMessages(sessionId, resetLimit); RefreshSessions; ServerConnected; ReportNonFatal(message) }`
  2. 20 个返 State 的 applyXxx 改签名 → `Pair<State, List<SseSideEffect>>`（applyMessageUpdated 已返 Pair<State,Boolean>，统一为 Pair<State,List>）
  3. dispatcher（dispatchSseEvent 的 when 分支）用 `slices.chat.update { current -> val (new, effects) = current.applyXxx(...); sideEffects = effects; new }`（CAS 提交），然后 `sideEffects.forEach { 分发 }`（业务命令走 emitEffect，UI 反馈走 tryEmitUiEvent）
  4. 表测试扩展：验证 applyXxx 返回的 state AND effects
- **测试要求**：每个 applyXxx 的 Pair 解构验证；side-effect 分发路径验证
- **依赖**：P1-3 EffectBus（R-18 已就绪）✅

#### S3-T2（后，合并 #9）：P2-5 VM 精确注入 + #9 闭包 self-ref 加固

- **写作用域**（高重叠，串行）：
  - `AppCore.kt`（controller 所有权迁移）
  - DI 模块（`app/src/main/java/cn/vectory/ocdroid/di/`，controller 改 @Singleton @Provides）
  - 8 VM 构造器：ChatViewModel / SessionViewModel / ConnectionViewModel / ComposerViewModel / OrchestratorViewModel / HostViewModel / SettingsViewModel / FilesViewModel
  - `ChatViewModel.kt:144`（#9 editFromMessage 闭包）
  - `SessionViewModel.kt:45`（#9 openSubAgent 闭包）
- **具体改动点**：
  1. **controller 生命周期分层**：app-level singleton（SessionSyncCoordinator/ConnectionCoordinator/HostProfileController/SessionSwitcher/ForegroundCatchUpController）→ 迁到 Hilt `@Singleton`；AppCore 保留 appScope + effectBus + dispatchEffect + applySavedSettings + init collectors
  2. 各 VM 构造器只注入所需 slice/controller/repository（如 ChatViewModel 注入 `store.chatFlow` 访问 + `sessionSyncCoordinator` + `repository` + `effectBus`，不再注入整个 AppCore）
  3. AppCore 仍持有 effectBus collector → dispatchEffect（P2-2 已拆域），VM 只 emit effect 不 dispatch
  4. #9：ChatViewModel.editFromMessage/openSubAgent 的 onSuccess 入口加 `if (!isActive) return`；P2-5 重写后若闭包已不捕获 VM（改调 controller free function）则免
- **测试要求**：每个 VM 注入编译通过 + 现有 VM 测试全绿；#9 加 isActive 守卫的边界 case（VM clear 后回调 no-op）
- **推荐方案**（不确定项授权）：
  - controller 迁 Hilt @Singleton（非 VM-scoped），AppCore 退化为"effectBus collector + appScope 持有者"
  - VM 注入最小 slice 集（精确到所需 StateFlow + controller）
  - #9 优先加 `isActive` 守卫（比 free-function 改造简单；若 P2-5 已解耦则免）

### Sprint 3 Gate

- [ ] `./scripts/check.sh --full` 全绿
- [ ] koverVerify 在 Sprint 2 阈值（60/56）下全过
- [ ] P2-4 每个 applyXxx 返 Pair 验证；side-effect 分发路径验证
- [ ] P2-5 每个 VM 注入编译通过 + grep 确认无 VM 注入整个 AppCore（除 OrchestratorVM 若保留跨域编排）
- [ ] #9 isActive 守卫存在（或闭包已不捕获 VM）
- [ ] gpter + glmer 并行评审 ≥9.5
- [ ] review 产物归档

### Sprint 3 风险与回退

| 风险 | 回退 |
|---|---|
| **P2-5 DI 图迁移破坏 Hilt 编译**（最大风险） | 分 VM 迁移（一次一个 + 编译验证）；某 VM 迁移失败则保留该 VM 注入 AppCore，其余继续 |
| controller 迁 Hilt 后生命周期变化（重复 collector） | AppCore.init 的 collector 改绑到 Hilt 注入的 controller；迁移后 grep 确认无重复实例化 |
| P2-4 Pair 改签名后 caller 解构遗漏 | compiler 列遗漏逐一改；表测试覆盖每 applyXxx |
| #9 isActive 守卫误吞正常回调 | 守卫只加在 onSuccess 入口（revertSession/selectSession 之后），不影响正常路径 |

---

## 5. 发版门控（全部 Sprint 完成后）

### 4 路共同门控（全部 ≥9.5 才发版）

| 评委 | 视角 | 重点审查 |
|---|---|---|
| gpter（终审） | 最深层推理、跨文件状态、协程/并发语义 | R-19 全程（3 sprint 累计）；P1-10 状态机正确性、P2-4 CAS 提交、P2-5 生命周期 |
| glmer（终审） | 方案可行性、配置一致性、语义级 bug、性能/安全 | kover 阈值一致性、DI 图完整性、uiEvents 收敛彻底性 |
| dser（新增） | DeepSeek 高推理：架构决策、跨文件状态、安全/数据完整性 | controller 所有权迁移的数据完整性、SSE reducer side-effect 分发无丢失 |
| kimo（新增） | Kimi 代码视角：实现批判、边界条件、配置一致性、性能 | 边界条件（reconnect 时序、VM clear 竞态）、性能（Pair 分配开销） |

- 4 位并行评审 R-19 全程（3 sprint 累计 git diff）
- 任一低于 9.5 → fixer 修复阻塞项后重审该评委
- 评委 prompt 要点：完整 sprint 改动摘要 + `git diff b119517..HEAD` + 重点审查方向 + 目标分 9.5 + 列阻塞项要求
- 评委允许 Read/Grep/git diff 核实，禁止改代码

### 发版（4 路全过后）

- [ ] `./scripts/check.sh --full` 全绿（最终确认）
- [ ] `./gradlew assembleRelease` 产出签名 APK
- [ ] review 产物归档 `.opencode/runs/reviews/<date>/R19-overall-{gpter,glmer,dser,kimo}.json`
- [ ] `./scripts/release.sh patch`（versionName 0.4.1 → **0.4.2**，versionCode 40 → 41；**禁手改** `app/build.gradle.kts`，见 AGENTS.md 硬规则）
- [ ] release.sh 内部：bump + 构建 + tag + commit + changelog
- [ ] `git push` 与 `tea releases create` 人工确认后执行（脚本不自动 push）

---

## 6. 季度工程（R-19 范围外，独立 epic）

**#7(a) 80% line via Compose UI test**：建 ComposeTestRule + HiltAndroidRule 套件 + kover androidTest instrumentation。这是冲 80% 的唯一路径，工程量大，列为季度工程，**不在本次 3 sprint 内**。本次 Sprint 2 的 #7(b)+(c) 已能把 unit-testable 推到 ~62-65%。

---

## 7. 并行 fixer 纪律（编排者执行）

- 每个 fixer 派发前，编排者确认写作用域（文件级）零冲突
- 每个 fixer 自检 `./gradlew compileDebugKotlin`（**不跑 check.sh**，编排者统一跑）
- 同文件改动串行（Sprint 3 全串行）
- Sprint 1：4 lane 可同时派 fixer
- Sprint 2：阶段 1（P2-2 || #7(b)）并行，阶段 2（#7(c)）等 P2-2
- Sprint 3：P2-4 → P2-5+#9 串行

---

## 8. 不确定项裁决（已授权推荐方案）

| 不确定项 | 推荐方案 | 理由 |
|---|---|---|
| 单 SSE vs 多路 SSE（#2） | 单 SSE + catch-up | ROI 高；多路是大工程；现有 fan-out 已缓解 |
| P2-5 controller 分层 | app-level singleton 迁 Hilt @Singleton；AppCore 退化为 collector+appScope 持有者 | 保持 controller 单例语义；AppCore 减负；VM 精确注入 |
| #9 闭包修法 | 加 `isActive` 守卫 | 比 free-function 改造简单；若 P2-5 已解耦则免 |
| P1-10 SseSyncState 定位 | 叠加决策层（不拆 ForegroundCatchUpController 3-tier） | 现有 3-tier 生产验证；叠加层风险最低 |
| P2-2 vs P2-5 顺序 | P2-2 先（Sprint 2），P2-5 后（Sprint 3） | 驳回 R-18 §7 反向图；P2-2 自包含低风险，明确域边界后 P2-5 决策更清晰 |
| #7(c) vs P2-2 顺序 | P2-2 先，#7(c) mock 5 域 dispatcher | R-18 §8 警告 #7(c) 与单体 when 冲突；拆分后 mock 更清晰 |
