# R-19 执行任务（给开发 Agent 的提示词）

> **目标**：按 `docs/tech-debt/R19-roadmap.md` 推进 R-19 技术债清理。
> **纪律**：先评审完善方案 → 分阶段执行 → 每阶段 gpter+glmer 9.5 门控 → 全部完成加 dser+kimo 共同门控 → 发版（versionName +0.0.1）。
> **项目根**：`/home/mar/personal_projects/ocdroid`（git 主线 `main`）。
> **必读**：`AGENTS.md`（硬规则：每次改动后必跑 `./scripts/check.sh`；模拟器占用纪律；版本号禁手改，走 `./scripts/release.sh`）。

---

## 第 0 步：评审与完善方案（执行前必做）

读以下文件，形成**可执行、有步骤**的 R-19 实施方案（写入 `docs/tech-debt/R19-execution-plan.md`）：

1. `docs/tech-debt/R19-roadmap.md`（权威路线图：四梯队 + 3 sprint + quarterly）
2. `docs/tech-debt/R18-pending-issues.md`（R-18 已闭环项 + 评审共识，避免重复处理）
3. `AGENTS.md` + `docs/build-apk.md` + `docs/emulator-debug.md`（构建/发版/测试纪律）

**评审要点**（在执行计划中逐一回答）：
- R19-roadmap 的依赖关系是否准确（P2-5 依赖 P2-2、P2-4 依赖 P1-3 已就绪、#9 与 P2-5 同批）？
- 各项的工程量估算是否合理？有无被低估的？
- 是否有 R-18 后新出现的技术债需补入（先 `git log --oneline -20` + 全量 grep 抽查 R-18 改动后状态）？
- 哪些项可并行（写作用域不冲突）？哪些必须串行（同文件/依赖）？
- 每 sprint 的 gate 标准是什么（除了 gpter+glmer 9.5，还有哪些技术 gate，如 check.sh --full 必过、覆盖率不降）？

**输出**：`docs/tech-debt/R19-execution-plan.md`，含：
- 每 sprint 的任务清单（文件级写作用域 + 具体改动点 + 测试要求）
- 并行/串行划分（明确哪些可同时派 fixer，哪些必须等前序完成）
- 每 sprint 的 gate 清单
- 风险与回退策略

**完成后**：先跑 `./scripts/check.sh`（文档改动也应确认构建不破），再进入 Sprint 1。

---

## 执行结构（3 Sprint + 发版）

### Sprint 1：可靠性 + 收敛（~1.5 周）

**任务**（来自 R19-roadmap 第一梯队）：
1. **P1-10 SSE gap reconciliation 统一状态机**：定义 `SseSyncState` + `sealed SseSyncDecision`，在 SessionSyncCoordinator 集中决策重连后 reload/flush。表驱动测试覆盖 4 场景（delta 中断 reconnect / reconnect 后 session.status idle 乱序 / 断线期间 currentSession 切换 / host reconfigure 后 late event）。
2. **SSE currentWorkdir 语义收敛**：明确单 SSE + catch-up 决策，文档化 + 强化 catch-up 边界测试。
3. **uiEvents wrapper 统一**：37 处 `effectBus.uiEvents.tryEmit(...)` → `effectBus.tryEmitUiEvent(...)`，`uiEvents` 降级只读 `SharedFlow`。
4. **applySavedSettings isConnecting 同步**：设 `connectionPhase=Reconnecting` 时同步 `isConnecting=true`（1 行）。
5. **counter UI 暴露**：DebugLogSection 加 Effect Bus dropped + SSE unknown events 两个 panel。

**写作用域分析**（执行前确认不冲突）：
- P1-10 + isConnecting：SessionSyncCoordinator + ConnectionActions（可同 fixer）
- uiEvents 收敛：SharedEffectBus + 37 调用点（宽，独立 fixer）
- currentWorkdir 文档化：注释 + catch-up 测试（独立）
- counter UI：DebugLogSection（独立）

**Gate**（通过后进 Sprint 2）：
- `./scripts/check.sh --full` 全绿（含 koverVerify，覆盖率不低于 R-18 终态 55/52/52）
- 新增测试覆盖 P1-10 的 4 场景
- **gpter + glmer 评审 ≥9.5**（两位并行，目标分 9.5，低于则修复后重审）

### Sprint 2：覆盖率快赢 + 架构启动（~1 周）

**任务**（来自 R19-roadmap 第三梯队 + 第二梯队）：
1. **dispatchEffect 22 分支逐 effect 全 mock**（纯 unit-test，+2-3pp）
2. **private helper 提取为 internal 纯函数**（~130 行：ChatScreen/ChatTextParts/ChatInputBar/ChatContextUsageDialog/ChatMessageContent 的 private helper，按 `visiblePickerProviders` 模式提取到独立文件单测，+1-2pp）
3. **P2-2 AppCore dispatcher 按域拆**：5 域 dispatcher 为 AppCore internal 成员函数（非扩展）+ 返 Boolean + `assertExactlyOneHandled`。

**写作用域**：
- dispatchEffect mock：test 文件（独立）
- helper 提取：main 源（ChatScreen 等）+ 新 test（独立，但触碰 Composable 文件需小心）
- P2-2 dispatcher：AppCore + 新 dispatcher 文件（独立）

**Gate**：
- `./scripts/check.sh --full` 全绿
- kover unit-testable line 提升（目标 ~62-65%，阈值可相应上调）
- **gpter + glmer 评审 ≥9.5**

### Sprint 3：深度架构（~2 周）

**任务**（来自 R19-roadmap 第二梯队）：
1. **P2-5 VM 精确注入**（依赖 P2-2 完成）：明确 controller 生命周期分层，各 VM 注入所需 slice/repository/controller。
2. **P2-4 SSE reducer Pair<State, SideEffect>**：applyXxx 返回 Pair，dispatcher CAS 提交 + 分发 effects。
3. **闭包 self-ref 加固**（与 P2-5 同批）：editFromMessage/openSubAgent 闭包改调 free function 或加 `isActive` 守卫。

**写作用域**：高度重叠（VM + AppCore + SessionSyncCoordinator），**必须串行**（P2-5 → P2-4 或反之，视依赖）。

**Gate**：
- `./scripts/check.sh --full` 全绿
- **gpter + glmer 评审 ≥9.5**

### 发版门控（全部 Sprint 完成后）

**4 路共同门控**（全部 ≥9.5 才发版）：
- gpter（终审，已参与各 sprint gate）
- glmer（终审，已参与各 sprint gate）
- **dser**（新增，DeepSeek 高推理视角：架构决策、跨文件状态、安全/数据完整性）
- **kimo**（新增，Kimi 代码视角：实现批判、边界条件、配置一致性、性能）

4 位评委审 R-19 全程（3 sprint 累计），目标各 ≥9.5。任一低于则修复后重审该评委。

**发版**（4 路全过后）：
- **版本号 +0.0.1**：当前 `versionName=0.4.1` → 发版后 `0.4.2`。**必须走 `./scripts/release.sh patch`**（禁手改 `app/build.gradle.kts` 的 versionCode/versionName，见 AGENTS.md 硬规则）。release.sh 内部 bump + 构建 + tag + commit。
- 发版前确认：check.sh --full 全绿、assembleRelease 产出 APK、review 产物归档 `.opencode/runs/reviews/<date>/`。

---

## 季度工程（R-19 范围外，独立 epic）

**80% line via Compose UI test**（R19-roadmap #7(a)）：建 ComposeTestRule + HiltAndroidRule 套件 + kover androidTest instrumentation。这是冲 80% 的唯一路径，但工程量大，列为季度工程，不在本次 3 sprint 内。本次 Sprint 2 的 (b)+(c) 已能把 unit-testable 推到 ~62-65%。

---

## 硬规则提醒（AGENTS.md）

- **改动校验必做**：每次改 Kotlin/资源后必跑 `./scripts/check.sh`（等价手动 LSP 自检）。
- **设备安全**：UI/插桩测试仅用模拟器；不得在物理机跑 debug 构建/测试，除非用户明确要求。
- **模拟器占用纪律**：用前 `./scripts/emulator.sh status` 确认未运行，用后 `stop`。
- **版本号**：禁手改 versionCode/versionName，走 `./scripts/release.sh`。
- **Git**：主线 `main` 工作；commit message 用 conventional 风格（`refactor(R19):` / `feat(R19):` / `fix(R19):`）。
- **并行 fixer 纪律**：写作用域（文件级）必须不冲突才并行；同文件改动串行。每个 fixer 自检 `compileDebugKotlin` 但不跑 check.sh（编排者统一跑）。

---

## 评委调用约定

- **每 sprint gate**：gpter + glmer 并行（复用 session 提速），目标各 ≥9.5。低于则派 fixer 修复阻塞项后重审。
- **发版 gate**：gpter + glmer + dser + kimo 4 路并行，目标各 ≥9.5。任一低于则修复后重审该评委。
- 评委 prompt 要点：给完整 sprint 改动摘要 + git diff + 重点审查方向 + 明确目标分 9.5 + 列阻塞项要求。
- 评委允许 Read/Grep/git diff 核实，禁止改代码。

---

## 不确定项处理

执行中遇到不确定的决策（如"单 SSE vs 多路 SSE"、"P2-5 controller 分层方案"），**选你推荐的方案并体现在执行结果报告中**（用户已授权）。推荐方案需有理由（ROI/风险/一致性）。
