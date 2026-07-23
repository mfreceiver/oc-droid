# 大文件 / God-File 拆分待办（Phase 1 排查）

> 生成：2026-07-23。方法：oracle（`ora-1`）拆分策略 + 5 个 explorer（`exp-1..5`）分子系统排查，覆盖 30 个 ≥599 LOC 的 main-source Kotlin 文件。
> 配套：Phase 2（内容重复 + 大单体函数）见 `docs/refactor-duplication-and-functions-backlog.md`。
> 目标：纯行为保持的重构待办（god-file 拆分 + 模块化），非新功能。每项均带行号与建议拆分边界。

---

## §1 判定总表

| 文件 | LOC | 判定 | P | 一句话 |
|---|---|---|---|---|
| `data/repository/OpenCodeRepository.kt` | 3953 | **GOD** | P2 | 4 层堆叠：HTTP/SSL/TOFU 客户端栈 / REST 门面 / slim 同步状态机 / TOFU pin。**✅ 拆分进行中**：TofuRepository 抽出 (L4a1, commit 3c9173f) ‖ ExpandBatchEngine 抽出 (L4a2, commit f448318)；⬜ L4a3 薄 GET 域委托+slim 轴+compat 门面 remaining |
| `ui/controller/SessionSyncCoordinator.kt` | 3687 | **GOD** | **P1** | SSE 协调类 + ~900 行纯 reducer 扩展(2794-3687) + 重复 pending-questions 岛 |
| `ui/chat/ChatMessageContent.kt` | 1399 | 长-内聚（god-函数） | P3 | 单个 ~1100 行 composable |
| `ui/chat/ChatScaffold.kt` | 1392 | **GOD** | P3 | 48 参 + 373 行 derivedStateOf + 7 VM 接线，编排枢纽非内聚 composable |
| `ui/AppAction.kt` | 1384 | **GOD（仅 reducer）** | **P1** | 42-action 目录 OK；**715 行 `reduce` when(613-1328)** 是合并冲突热点 |
| `di/AppLifecycleMonitor.kt` | 1375 | **GOD** | **P1** | 3 独立类挤一文件：SessionNotifier / NotificationDedup / Monitor + 模块 |
| `service/lifecycle/StreamingLifecycleCoordinator.kt` | 1359 | GOD-lite | P2 | L1/L2/L3 机 + 9 个可提取类型(1247-1359) |
| `ui/settings/HostProfilesManagerScreen.kt` | 1320 | **GOD** | P3 | `HostProfileEditorDialog` ~730 行怪兽表单（mTLS/CA stage/group picker） |
| `ui/SessionListActions.kt` | 1220 | MIXED | P3 | 9 自由函数大杂烩；`launchLoadSessions` 368 行 |
| `ui/sessions/SessionsScreen.kt` | 1216 | MIXED | P3 | 屏 + SessionCard + HomeWorkdirRow + helpers 三组合一 |
| `ui/controller/HostProfileController.kt` | 1110 | 长-内聚 | P3 | 单域；长度来自 **3 层 barrier/非barrier/非active 重复** |
| `ui/controller/ConnectionCoordinator.kt` | 1075 | **GOD-lite** | P2 | health/testConnection 引擎(292-676, 260行) + 初始加载 + SSE 开关。**✅ done**：ConnectionHealthProbe 抽出 + cluster 11 cancelSseInternal 去重 (L4c, commit 3c9173f) |
| `util/SettingsManager.kt` | 1071 | **GOD-config** | P2 | ~17 域共用一个 EncryptedSharedPreferences。**✅ done**：分域为 8 *Prefs.kt + MigrationHelper，原文件退化为 facade (L4b, commit 3c9173f；cluster 17 requireValidFp 顺带) |
| `service/SessionStreamingService.kt` | 1007 | **GOD-lite** | P2 | Service 壳 + ~250 行通知构建(711-902，与 service/notify/ 重复)。**部分 done**：BootstrapFailure 两步收敛 (L3c Step A rollbackBootstrap + Step B emit, commit 665cf79)；⬜ L3b（通知构建 → ForegroundNotificationPublisher）pending |
| `service/streaming/ServiceSseConnectionOwner.kt` | 979 | 长-内聚 | — | 单一 SSE 传输生命周期；resync 合并(782-879) 可抽 |
| `ui/AppCore.kt` | 934 | 长-内聚 | P3 | DI 根；dispatchEffect 路由(459-903) 可抽 |
| `ui/AppStateSlices.kt` | 867 | 长-内聚 | P3 | 状态字典；ChatState(194行)/SessionListState(155行) 可拆 |
| `ui/AppCoreOrchestration.kt` | 839 | 长-内聚（in-flux） | P3 | 跨域编排扩展；dispatchSendMessage(424-556,132行) 最大 |
| `ui/chat/ChatMessageRow.kt` | 807 | 长-内聚 | — | 单消息行渲染，已良好分解 |
| `service/StreamingOwnershipGate.kt` | 726 | MIXED | P3 | 核心内聚；6 顶级 sealed 类型 + 2 尾部类可抽 |
| `ui/chat/ChatTopBar.kt` | 700 | MIXED | P3 | State(30字段)+Actions(20回调) 可独立成文件 |
| `ui/chat/ChatTextParts.kt` | 691 | 长-内聚 | — | TableGrid 自定义 Layout(175行) 可抽 |
| `data/api/OpenCodeApi.kt` | 689 | 长-内聚 | — | 纯 Retrofit 接口，1:1 端点 |
| `service/status/StatusAggregatorImpl.kt` | 675 | 长-内聚 | — | 单一权威聚合组件 |
| `ui/chat/QuestionCardView.kt` | 659 | 长-内聚 | — | 自洽交互 widget |
| `ui/chat/MessageCard.kt` | 657 | 长-内聚 | — | 已充分提取纯函数 |
| `ui/chat/Composer.kt` | 642 | **MIXED** | P3 | Composer 内聚；**AgentPickerSheet/ModelPickerSheet 是孤儿**（被 ChatScaffold 调用） |
| `service/streaming/ProcessStatusPoller.kt` | 621 | 长-内聚 | — | 批量循环 + slim fan-out 双角色 |
| `ui/MessageActions.kt` | 628 | 长-内聚（god-函数） | P3 | `launchLoadMessages` 426 行（3-路合并/覆盖保留/光标/模型推断） |
| `ui/workspace/ChangesPane.kt` | 599 | 长-内聚 | — | 已良好分解 |

---

## §2 真 God File 详档（拆分价值高）

### `OpenCodeRepository.kt` (3953) — P2
**职责**（25+）：4 Retrofit 实例 + SSL/TOFU + 双路 health 探测 + legacy 会话/消息 CRUD + slim 状态机委托 + slim 消息(since/page/full) + **expand-batch 引擎(drivePartition 265行)** + slim 聚合 + cold-start 编排 + cursor drain + tunnel + 4 内部异常/5 data class。
**信号**：god 构造器 @162-192；`drivePartition` @2259-2523(~265) 二分分区+重试+信号量+413/503；`coldStartSlimSync` @2959-3111(~150)；`getSlimapiSessionStatusOutcome` @1521-1601(~80, 11 臂 when)；`captureServerCert` @764-832 自建 OkHttp+SSLContext。
**拆分**：分解为 `data/repository/` 包——9 域薄门面（Session/Message/SessionMutation/QuestionPermission/Config/File/Health/Tunnel/Sse Repository）+ slim 轴（SlimSession/SlimMessage/SlimAggregation/SlimColdStart Repository + SlimBookmarkHelper）+ `ExpandBatchEngine.kt`(2184-2647) + `TofuRepository.kt`(738-873) + data class 移 `data/model/`；原文件留 ~100 行 backward-compat 门面。
**注意**：最大文件但 reconfigure-ticket/hostConfig/slim-state-machine 不变量横跨拟切接缝 → 需先做不变量映射，作 **拆分 #4**。

### `SessionSyncCoordinator.kt` (3687) — **P1（首拆 #1）**
**职责**（14）：SSE 事件路由 + 11+ 事件 fold + delta 合并 + T11 reconcile 编排 + resync sweep + SSE-gap overlay + G-F1 resync 节拍(525-647) + slim cold-start fold + 多 workdir pending-questions + ~800 行纯 transform(2890-3687)。
**信号**：`reconcileSessionLocked` @1651(~140)；`performSlimResync` @2340(~160)；`applySlimColdStartSnapshot` @2603(~140)；`applyCurrentReconcileResult` @1940(~140)；`loadPendingQuestionsAllWorkdirs` @1087(~70，与 SessionListActions 重复)。
**拆分**（近零风险）：`SessionSyncTransforms.kt`(~800，全部 `applyXxx`/`mergeSlimMessages` 纯函数，同包无可见性改) + `ResyncCadence.kt`(~150) + `ReconcileOrchestrator.kt`(~800，全部 T11 reconcile) + 主类留 ~800（SSE 派发 + delta 合并）。风格同刚落地的 TokenStreamCoordinator/Client/Reducer。

### `AppAction.kt` (1384) — **P1（首拆 #3）**
**信号**：`reduce` when @613-1328(~715)；`SessionArchived` 分支 @639-696(~58)；`HostStatePurged` 分支 @698(~131)。
**拆分**（低风险）：密封目录留原文件；`reduce` 拆为 `ui/reducer/*Reducer.kt`（Chat/SessionList/StreamingBuffer/Scroll）返回 `StoreState?`，`reduce` 退化为短 `?:` 派发器（编译器仍强制穷举）。**注意**：分支跨多 slice，handler 签名须传整 `StoreState`（保持行为一致）。

### `AppLifecycleMonitor.kt` (1375) — **P1（首拆 #2）**
**信号**：`SessionNotifier` @67-187(120) 完整内部类；`NotificationDedup` @237-509(272) 线程安全去重状态机；`pollPendingItems` @885(~128)；`handleIdleAlert` @1014(~67)。
**拆分**（近零风险，纯文件移动，皆 `internal`）：`SessionNotifier`+`NotificationDedup` → `service/notify/`（顺带合并重叠：与 `SessionStatusNotifier`/`SseNotificationBridge` 同域）+ `ApplicationScopeModule` → `di/`。三独立可测单元，构造注入边界干净，无共享可变状态。

### `SettingsManager.kt` (1071) — P2
**职责**（17 域）：连接/hostProfiles/basicAuth/tunnel/mTLS/clientCert/session/nav/workdir/draft/sessionCache/model/theme/locale/uiScale/font/debug/traffic。
**信号**：`migrateLegacyKeysToFp` @732(~50) 混 3 步；`clearAllLocalData` @862(~34) 白名单脆弱；`lastRoute` getter @195-218 在 getter 内做迁移（副作用）。`.edit().putX/apply()` 重复 ~40×；recentWorkdirs 4 函数同首部 guard。
**拆分**：按域抽 `*Prefs.kt`（Connection/Auth/Session/Workdir/Draft/Model/Ui/Traffic/Debug）+ `MigrationHelper.kt`，共享同一 ESP 实例（delegate 模式）。延后到 #1-#3 验证模式后（ESP key 所有权/迁移风险）。

### `ConnectionCoordinator.kt` (1075) — P2
**信号**：15 参构造器（9 可空 optional，迁移缝）；`testConnection` @292(~260) god 函数（嵌套重试+TOFU 捕获+decision await+3 层 try/finally）；`testConnectionWithEngine` @554(~100) 平行探针路径重复 settled 模式；`loadInitialData` @705(~145)。
**拆分**：`ConnectionHealthProbe.kt`(~400，testConnection* + TOFU 委托 + init 生命周期钩子) + 主类留 ~400（loadInitialData/loadCommands/SSE 委托/refresh）。或轻量：把 `testConnection` 体抽成 sealed `ProbeState` 状态机。

### `SessionStreamingService.kt` (1007) — P2
**信号**：15+ `@Inject lateinit`（最广依赖集）；匿名 `ServiceShell` @233-286 混 FGS+poller+SSE+stopSelf；`onCreate` @298(~142)；`onStartCommand` @474(~120)；`buildNotification` @764(~60，与 service/notify/ 重复）。
**拆分**：FGS 通知构建 → `notify/ForegroundNotificationPublisher.kt`；`ServiceShell` → `service/ServiceShellDelegate.kt`；`onResync` 闭包 @348-390 → `SseResyncHandler`；引导路由 → `BootstrapStartupHandler.kt`。

### `StreamingLifecycleCoordinator.kt` (1359) — P2
**信号**：9 可提取类型（`Layer`+`LifecycleCommand` 112 行 @1247-1359 定义在使用之后）；`commitSseTransport` @1105(~50,3 分支带副作用)。
**拆分**：`Layer`/`LifecycleCommand`/`ActivationCommitResult`/`PollerRuntime`/`Handoff*` → `lifecycle/` 子包各文件；`commitSseTransport`+`runDecisionCommit` → `HandoffCommitHandler.kt`。**集群层**：删 `PollerRuntime` 镜像，让 poller 自有运行态、coordinator 改发命令消费 ack（消除双真相）。

### `ChatScaffold.kt` (1392) — P3-god
**信号**：48 参函数 @114-161；8 slice 订阅；`topBarState` derivedStateOf @280-653(~373)；`ChatTopBarState(...)` 30+ 命名参 @570-652；ModalNavigationDrawer @919-1317(3 BackHandler)；`ChatOverlayHost` 28 参 @1319-1382。
**拆分**：`ChatScaffoldOverlays.kt`（ChatOverlayHost + 全部 overlay 状态，高收益）+ `ChatPagerState.kt`（pager 双向同步 + scroll 缓存）+ `ChatDerivedState.kt`（topBarState 派生）。

### `HostProfilesManagerScreen.kt` (1320) — P3-god
**信号**：`HostProfileEditorDialog` @443-1173(~730) 怪兽表单；`triggerClientPaste`/`triggerCaPaste` 剪贴板骨架重复；`CompactCertIndicator` 3 近同分支。
**拆分**：`HostProfileEditorDialog.kt`(~730 自洽表单 + helpers + CaStage/CertSlotStatus) + `HostProfileCertViews.kt` + `HostProfileListComponents.kt`。

### `Composer.kt` (642) — MIXED（孤儿）
`AgentPickerSheet` @462-515 + `ModelPickerSheet` @519-642 被 **ChatScaffold** 调用，不属于 Composer（P2.5 移除 chip 后遗留）。**拆分**：移至 `PickerSheets.kt`（高收益，去 180 行 + 明确归属）。

---

## §3 streaming 集群职责重映射（跨文件，最高价值）

**现状重叠**（oracle + exp-3 验证）：
1. **poller 生命周期双真相** — `StreamingLifecycleCoordinator` 镜像 `PollerRuntime` vs `ProcessStatusPoller` 自有 `loopJob`/`ensureRunning`。
2. **bootstrap 重试三处** — `ConnectionCoordinator.testConnectionWithEngine` + `ConnectionBootstrapEngine` + `BootstrapRetryPolicy`（app 侧）vs `SessionStreamingController` + `BootstrapRunner`（service 侧）+ `DegradedBootstrapTerminator`。
3. **通知构建三处** — `SessionStreamingService.buildNotification` vs `service/notify/SessionStatusNotifier`+`SseNotificationBridge` vs `AppLifecycleMonitor.SessionNotifier`。
4. **pending-questions/permissions 加载重复** — `SessionSyncCoordinator.loadPendingQuestionsAllWorkdirs` vs `SessionListActions.launchLoadPendingQuestions`（@1063 注释承认有意重复）。
5. **BootstrapFailure 拆除路径** — `SessionStreamingService.failStarting` @657 + `abortExpiredStartup` @639 + `StreamingLifecycleCoordinator.teardownForBootstrapFailure` @744 三处重叠。
6. **两条 SSE 传输**（app `ConnectionCoordinator.startSSE` / service `ServiceSseConnectionOwner`）— **设计如此，`StreamingOwnershipGate` 仲裁，保留**。

**目标划分**：
| 关注点 | 单一拥有者 | 动作 |
|---|---|---|
| SSE 传输（service） | `ServiceSseConnectionOwner` | 无 |
| SSE 传输（app） | `ConnectionCoordinator` | 剥离数据加载/命令；仅 open/close/reconfigure/health |
| 所有权仲裁 | `StreamingOwnershipGate` | 无（6 顶级类型 + 2 尾部类 → 各文件） |
| 分层策略/交接 | `StreamingLifecycleCoordinator` | 抽 `HandoffTracker`；**删 PollerRuntime 镜像** |
| 轮询+backoff | `ProcessStatusPoller` | 自有运行态；暴露 `StateFlow<PollerState>` 供 coordinator 读 |
| 状态投影/新鲜度 | `StatusAggregatorImpl` | 无 |
| Service 壳 | `SessionStreamingService` | 仅 Android 生命周期+intent 路由；**通知构建移 service/notify/** |
| bootstrap | 新 `BootstrapOrchestrator`（或并 `ConnectionBootstrapEngine`） | 两路调同一策略拥有者 |
| SSE→状态 reconcile | `SessionSyncCoordinator` | 拆 reducer 后仅事件路由；pending 加载移 SessionListActions |
| fg/bg+idle/未读通知 | `AppLifecycleMonitor` | 拆 3 类后仅生命周期+轮询 |

---

## §4 Top-3 首拆（价值/风险比最高）

1. **`SessionSyncCoordinator` 尾部 reducer 抽取** — P1，近零风险。2794-3687 ~30 个纯函数 → `ui/controller/sse/SseChatReducers.kt` + `SseSessionListReducers.kt`（同包，无可见性改）。LOC/风险比最高，且让刚落地的 token-stream 特性所在文件可导航。后续 PR：迁 `loadPendingQuestionsAllWorkdirs` → SessionListActions，删镜像。
2. **`AppLifecycleMonitor` 解耦** — P1，近零风险。3 类构造注入、无共享可变状态；`SessionNotifier`+`NotificationDedup` → `service/notify/`（顺带收敛重叠 #3）。1375 → ~250/300/700。
3. **`AppAction.reduce` 按域拆 reducer** — P1/P2，低风险。715 行 when 是每特性必碰的合并冲突/评审疲劳热点。拆 `ui/reducer/*Reducer.kt` 返回 `StoreState?`，`reduce` 退化短派发器，穷举仍编译器强制。

**延后**：`OpenCodeRepository`（需先做不变量映射，作 #4）、`SettingsManager`（等模式验证）、UI composables（god-函数，用 composable 抽取非模块拆，独立 UI 批次）。

---

## §5 优先级矩阵（速览）

| 优先级 | 项 | 风险 |
|---|---|---|
| **P1 近零风险** | SessionSyncCoordinator 尾部 reducer；AppLifecycleMonitor 解耦 | 纯移动/同包 |
| **P1/P2 低风险** | AppAction.reduce 按域拆；streaming 集群 PollerRuntime 镜像删除 | 行为保持，需测试 |
| **P2 中风险** | OpenCodeRepository 分包（#4，需不变量映射）；SettingsManager 分域；ConnectionCoordinator 拆 health probe；SessionStreamingService 通知剥离 | 跨接缝不变量 |
| **P3 低收益** | UI composables（ChatScaffold/HostProfileEditor/Composer 孤儿 pickers）；类型目录（AppAction 目录本身、AppStateSlices） | UI 拆分/纯移动 |

---

## §6 备注
- exp-1..5 + ora-1 原始逐文件发现（含完整行号信号、耦合、重复）已并入上表；如需某文件的完整原始清单可回放对应 session。
- 本次为**只读排查**，未改任何代码。所有拆分建议均为行为保持型重构，落地须各自跑 `./scripts/check.sh`。
