# ocdroid Architecture-Debt Batch 2 — Final Report (Items 13, 15)

- **Date**: 2026-08-08
- **Bundle**: `archdebt-batch2-20260807`
- **Base head**: `45dfe0db` (= origin/main = tag v0.21.5)
- **Released**: tag **`v0.21.6`** (patch)
- **Design SSOT**: `.opencode/runs/reviews/2026-08-07/archdebt-batch2-design.md` (oracle, 372 lines)
- **Status**: ✅ **DONE — released v0.21.6**

---

## 状态
**DONE** — 串联验收 APPROVED (rev-gpt 9.7/10) + check.sh 绿 → 已发版 v0.21.6。

> 注：评审门禁中途由 omni 变更为 **rev-gpt 单节点 ≥9.5/10**（原 rev-glm→rev-kimi 串联取消）。本批按新门禁执行。

## 成果

### oracle 设计阶段（先行，SSOT）
产出 `.opencode/runs/reviews/2026-08-07/archdebt-batch2-design.md`（372 行）。关键决策全部基于 head `45dfe0db` 重定位（非 handoff 旧行号），含：项13 DI Wave2.2 迁移方案、项15 God 类拆分方案、**slim/standard 边界影响分析（§6，本批最关键章节）**、抽象层次论证、分步迁移、测试计划、风险点。

### 项13 — DI Wave2.2（DONE @ `c0b6106c`）
- 5 个 Orchestrator（`SessionOpener`/`RefreshOrchestrator`/`SendOrchestrator`/`DraftSessionOrchestrator`/`CommandOrchestrator`）现由 Hilt 经其既有 `@Singleton @Inject constructor` 直接提供；AppCore 的 `by lazy` 手工组装块（原 AppCore.kt:224-292）删除；effect-routing 5 路级联保留（YAGNI，已 per-domain 拆分）。
- 与批1 `SlimFanOutRetryScheduler` 注入兼容（AppCore.kt:222 参数未动）。
- 两测试工厂 `MainViewModelTestBase.createCore` / `ForkSessionTest.createCore` 扩展：内联按依赖序（SessionOpener→Refresh→Send→Draft→Command）构建 5 Orchestrator + 追加构造参数。
- **已知偏差（已接受，留档）**：设计 §3.1 要求保持 `internal` 可见性 + `@Provides` 兜底；实现改为移除 5 类的 `internal`→`public`。原因：Kotlin 公开构造器暴露规则（公开类 `AppCore` 的 public ctor 不能收 `internal` 参数类型，编译期错误——非设计预期的 KSP 错误，且设计的 `@Provides` 兜底同样会撞此规则）。在无外部消费者的 Android app 模块里 cosmetic，所有架构目标（单一职责/不泄漏具体实现/可测试性）保留。rev-gpt 确认不构成阻塞（"app-only 模块里我视为可接受的 cosmetic deviation"）。

### 项15a — TokenStreamCoordinator 拆分（DONE @ `fd1eee52`→`f8273ab9`，6 commits）
- Strangler-fig：`TokenStreamCoordinator.kt` 1435→**435 行** facade，构造函数（19 参数，默认值不变）**字节相同**——零调用方/测试迁移。
- 拆出 4 个 `internal` 协作者（同包 `ui/controller/sse/`）：
  - `ReconnectPolicy.kt`（退避阶梯 + per-sid 尝试计数，纯）
  - `TokenFrameGuard.kt`（epoch/generation/ownership 簿记——stale frame/clear 权威）
  - `TokenStateDispatcher.kt`（reducer→ChatState 桥接 + effect 翻译 + revision-hook）
  - `StreamLifecycleSupervisor.kt`（max-1 生命周期：open/close/debounce/run loop/watchdog/reconnect/§MF-1 sentinel/lifecycle-bundle 绑定）
  - 另：`TokenStreamTypes.kt`（共享类型）
- **单 monitor 可重入**：四组件共享同一 `bundleCommitLock`；JVM `synchronized` 重入保留原单锁原子性；facade `close()` 在一个外层 `synchronized` 包裹四清理。Atomics（CAS/sentinel）保留给锁外读。
- supervisor↔dispatcher 循环用 `lateinit` 构造序打破（首次 `open()` 解引用，main-confined 安全）。
- 4 个新组件单测（`ReconnectPolicyTest`/`TokenFrameGuardTest`/`TokenStateDispatcherTest`/`StreamLifecycleSupervisorTest`）。

### 项15b — ChatScaffold 拆分（DONE @ `bcd315b4`→`5e2b13a0`，4 commits）
- `ChatScaffold.kt` 1398→**950 行** facade。
- 拆出 3 个 `internal` remember-factory（同包 `ui/chat/`，遵循既有 `rememberChatTopBarState` 范式）：
  - `ChatDerivedState.kt`（`rememberChatDerivedState` + 29 个 per-field `State<T>` 派生值）
  - `ChatChromeState.kt`（`rememberChatChromeState` + overlay flag/drawer/snackbar/image picker，4 个 `rememberSaveable` 作为有序整块移入保 slot 位置性）
  - `ChatNavigationEffects.kt`（`ChatNavigationEffects` + `rememberOnOpenSubAgentNavigate`，所有 LaunchedEffect/LifecycleEventEffect/BackHandler，BackHandler LIFO 顺序 parent→drawer 保留）
- 所有 `remember(...)`/`derivedStateOf` key 列表逐项保留；`ChatScaffoldSaveableTest` 未改且绿。

### slim/standard 边界验证结论
oracle 设计 §6（rev-gpt 评分 9.8/10 独立确认）：**任务指令对边界的描述因 head 漂移已失效**——TokenStreamCoordinator 内部**零** SSE_SLIM/SSE_LEGACY 分支（传输经 `streamProvider`/`streamConnectionProvider` 注入），ChatScaffold **零**运行时 `isSlimActive` 读取（仅 4 处注释）。真正的 token-stream slim 门控在上游调用点 `shouldOpenTokenStream(tokenStreamEnabled ≡ slimConnection)`（`RefreshOrchestrator.kt:170`、`ChatViewModel.kt:164`），均在本批写域外、未动。SSE_LEGACY/SSE_SLIM 实际路由在 `SseEventRouter`/`SseDispatchHost`/`SessionSyncCoordinator`（独立子系统）。**项13/15 均不触碰边界；拆分仅做 mode-agnostic 代码搬运。**

### 测试
- 新增：4 个 TSC 组件单测 + `ChatDerivedStateTest`（rev-gpt finding1 回归）。
- 改动：`MainViewModelTestBase`、`ForkSessionTest`（工厂扩展，项13）。
- 回归安全网全绿未改：`TokenStreamCoordinatorTest`、`TokenStreamCoordinatorIdempotencyTest`、`TokenStreamCoordinatorBundleIdentityTest`、`SlimV2WireRegressionTest`、`B2RouteWiringSequenceTest`、`AppCoreDispatcherTest`、`ChatScaffoldSaveableTest` 等。

## 产出
- feature 分支：`fix/archdebt-batch2`（14 commits）
- merge：`9d1efb8d` merge: fix/archdebt-batch2 — batch2 archdebt refactor (items 13/15)（--no-ff）
- main：`45dfe0db..9d1efb8d`（已 push origin/main）
- tag：**`v0.21.6`**（annotated，on 9d1efb8d，已 push）→ `git describe` = `v0.21.6`

## 验证
- **check.sh**：✅ 绿（编译 + detekt + `testDebugUnitTest`，**4542 测试**，0 失败）。
- **rev-gpt**：✅ **APPROVED 9.7/10**（3 轮：8.8 CHANGES → 9.2 CHANGES → 9.7 APPROVED；2 轮修复）。维度：功能正确性 9.8 / 证据强度 9.9 / 最小性·生产中性 9.6 / 并发状态一致性 9.5 / 可维护性 9.7；边界保持 9.8。
- 修复轮次：① finding1（ChatDerivedState context-usage memo key 收窄，BLOCKER）→ 还原为每次 composition 重算 + 回归测试 `2a5739ed`；findings2&3（StreamLifecycleSupervisorTest 偏薄 + TokenFrameGuard.removeSid 无显式锁）→ 扩展 4 生命周期路径测试 + synchronized 加固 `72ba985f`；② finding2 余项（sid-recheck guard 未被测试证伪）→ `setCurrentSidForTest` seam + mutation-verified 测试 `6dcdeb84`。

## 下一步
**无 — 已发版 v0.21.6**。

## 阻塞
无。

## 报告
`/home/mar/personal_projects/ocdroid/.opencode/runs/reviews/2026-08-07/archdebt-batch2-report.md`（本文件）。

## 审计
- 分支：`fix/archdebt-batch2`（merged）；主线 `main`
- commits：`c0b6106c`（项13）+ `fd1eee52`/`a6a380b8`/`ceaf366a`/`dce26464`/`4f7fa1f6`/`f8273ab9`（项15a，6）+ `bcd315b4`/`a33ea460`/`39a2fa06`/`5e2b13a0`（项15b，4）+ `2a5739ed`/`72ba985f`/`6dcdeb84`（review 修复，3）
- merge commit：`9d1efb8d`
- tag：`v0.21.6`（on `9d1efb8d`）

---

## 后续（out-of-scope，留档）
- **F1**：`ControllerModule.kt:46-53` 的"internal 类不能用 @Inject constructor"是 folklore；项13 已证 internal+@Inject 在本库可行（实际因 Kotlin 暴露规则改 public，但机制已澄清）——后续 comment-only 修正该 kdoc。
- **F2**：`ChatDerivedState` 的 `cachedContextUsage` 写入-期间-组合 smell（§L5a 原有）按设计 verbatim 保留；后续可重设计（rev-gpt 注意到 plain-recompute 路径在 AppStateDerived.kt:209/408 带 contextUsage 索引 + null-path 日志的每次重组开销——忠实原行为，非缺陷）。
- **F3**：`RefreshOrchestrator`/`SendOrchestrator` 仍收具体 `OpenCodeRepository`（design §11 F5），窄化候选（后续批）。
- **F4**：AppCore 进一步 God-Object 拆分（超出 DI 迁移，design §11 明确排除本批）。
- **F5**：slim fan-out seam 仍无生产入口触发器（批1 F3，本批未动）。
