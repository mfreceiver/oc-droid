# Phase-2 排查：大单体函数 + 内容重复

> 生成：2026-07-23。输入：5 explorer（exp-1..5）顺带捕获的大函数 + `exp-6` 跨文件重复穷尽扫描（24 cluster）。
> 配套：`docs/refactor-large-files-backlog.md`（Phase-1 god-file）、`docs/refactor-optimization-plan.md`（执行计划，Wave 6 整合本目录）。
> 只读排查，未改代码。所有抽取均为行为保持型。

---

## §1 大单体函数目录（≥~60 LOC，按规模降序）

> **god-函数**（单个 ~1000+ 行 composable/function）与 god-文件不同——它们内聚但过大，用**函数/composable 抽取**而非模块拆。

### 🔴 god-函数（最高拆分单体，UI 批次）
| 函数/composable | 文件:行 | ~LOC | 处置 |
|---|---|---|---|
| `ChatScaffold` | `ui/chat/ChatScaffold.kt:112-1382` | ~1300 | god-文件 L5a（overlay/pager/derivedState 抽出） |
| `ChatMessageList` | `ui/chat/ChatMessageContent.kt:82-1255` | ~1100 | LazyColumn items 块(@900-1172) + 5 scroll LaunchedEffect 各抽 composable |
| `HostProfileEditorDialog` | `ui/settings/HostProfilesManagerScreen.kt:443-1173` | ~730 | god-文件 L5b（表单+helpers+CaStage 抽出） |
| `reduce`（when） | `ui/AppAction.kt:613-1328` | ~715 | god-文件 L2（按域拆 reducer） |
| `SessionsScreen` | `ui/sessions/SessionsScreen.kt:76-683` | ~600 | L5c（SessionCard 抽出后屏体 ~400） |

### 🟠 大函数（≥100 LOC，逻辑函数）
| 函数 | 文件:行 | ~LOC | 建议 |
|---|---|---|---|
| `launchLoadMessages` | `ui/MessageActions.kt:23-449` | ~426 | 抽 `mergeMessagePages()` 纯函数（3-路合并 @177-276）+ `preserveStreamingOverlay()` |
| `launchLoadSessions` | `ui/SessionListActions.kt:88-456` | ~368 | 抽 `autoSelectSession()` + `sweepPendingCreateIds()`（见重复 cluster 14） |
| `drivePartition` | `data/repository/OpenCodeRepository.kt:2259-2523` | ~265 | 随 L4a 进 `ExpandBatchEngine.kt`（二分分区+重试+信号量+413/503 整体） |
| `testConnection` | `ui/controller/ConnectionCoordinator.kt:292-551` | ~260 | L4c 进 `ConnectionHealthProbe.kt`，或抽 sealed `ProbeState` 状态机 |
| `MessageCard` | `ui/chat/MessageCard.kt:156-442` | ~285 | pointerInput+DropdownMenu+AlertDialog，可拆 `MessageOverflowMenu` |
| `ChatTopBar` | `ui/chat/ChatTopBar.kt:244-507` | ~260 | title slot 4 分支(@349-458) 可抽 |
| `ExpandedQuestionContent` | `ui/chat/QuestionCardView.kt:370-642` | ~270 | 表单体，状态机可提 `QuestionCardState` 类 |
| `QuestionCardView` | `ui/chat/QuestionCardView.kt:84-320` | ~235 | 同上 |
| `MessageRow` | `ui/chat/ChatMessageRow.kt:87-315` | ~230 | part 迭代器+tool-run 收集+fold 分组 |
| `OmittedContentCard` | `ui/chat/ChatMessageRow.kt:333-530` | ~200 | 5 `when` 分支近同（见重复 cluster 16） |
| `refreshHostProfileState` | `ui/controller/HostProfileController.kt:116-336` | ~220 | 单用途但长，可分段 |
| `saveHostProfile` | `ui/controller/HostProfileController.kt:162` | ~160 | 3-层 barrier 重复（见重复 cluster 6） |
| `performSlimResync` | `ui/controller/SessionSyncCoordinator.kt:2340` | ~160 | 随 L1a 进 reconcile 轴 |
| `launchLoadMoreMessages` | `ui/MessageActions.kt:472-628` | ~156 | 同 launchLoadMessages |
| `launchLoadSessionStatus` | `ui/SessionListActions.kt:613-752` | ~140 | slim 分支可抽 |
| `onCreate` | `service/SessionStreamingService.kt:298-440` | ~142 | `onResync` 闭包(@348-390) 抽 `SseResyncHandler`（L3b） |
| `reconcileSessionLocked` | `ui/controller/SessionSyncCoordinator.kt:1651` | ~140 | 随 L1a 进 reconcile 轴 |
| `applySlimColdStartSnapshot` | `ui/controller/SessionSyncCoordinator.kt:2603` | ~140 | 随 L1a |
| `applyCurrentReconcileResult` | `ui/controller/SessionSyncCoordinator.kt:1940` | ~140 | 随 L1a |
| `handleSessionDigestImpl` | `ui/controller/SessionSyncCoordinator.kt:1339` | ~134 | 随 L1a |
| `dispatchSendMessage` | `ui/AppCoreOrchestration.kt:424-556` | ~132 | 抽 `AppCoreSendMessage.kt`（需重构参数） |
| `pollPendingItems` | `di/AppLifecycleMonitor.kt:885-1012` | ~128 | 随 L1b 分 `BackgroundNotifier.kt` |
| `ModelPickerSheet` | `ui/chat/Composer.kt:519-642` | ~125 | L1e 进 `PickerSheets.kt`（见重复 cluster 4） |
| `HostStatePurged` 分支 | `ui/AppAction.kt:698` | ~131 | 随 L2 按域拆 |
| `onStartCommand` | `service/SessionStreamingService.kt:474-592` | ~120 | intent 路由抽 `BootstrapStartupHandler`（L3b） |
| `launchSseCollector` | `service/streaming/ServiceSseConnectionOwner.kt:415-528` | ~115 | 重试循环体可抽 `SseRetryLoop` |
| `coldStartSlimSync` | `data/repository/OpenCodeRepository.kt:2959-3111` | ~150 | 随 L4a 进 `SlimColdStartRepository.kt` |
| `refresh`(StatusAggregator) | `service/status/StatusAggregatorImpl.kt:257-365` | ~110 | 内聚，可选抽 `project()` |
| `testConnectionWithEngine` | `ui/controller/ConnectionCoordinator.kt:554-658` | ~100 | L4c |
| `onSuccessfulFrame` | `service/streaming/ServiceSseConnectionOwner.kt:557-664` | ~110 | 抽 `SseResyncCoordinator`（resync 合并 @782-879） |
| `ContextMenuCluster` | `ui/chat/ChatTopBar.kt:538-684` | ~150 | DropdownMenu 5 项（见重复 cluster 5） |

### 🟡 中等函数（60-100 LOC，择优抽）
`TableGrid`@503(175,自定义 Layout)、`dispatchSessionEffect`@529(180)、`init`(AppCore)@268(170)、`launchLoadMoreSessions`@458(122)、`executeCommand`@208(71)、`TextPart`@150(120)、`buildNotification`@764(60)、`performResyncCatchUpOnWorker`@2202(90)、`scheduleResync`@782(100)、`drainSlimapiMessagesBoundedOutcome`@3477(100)、`getSlimapiSessionStatusOutcome`@1521(80,11-臂 when)、`captureServerCert`@764(70)、`checkHealthFor`@1046(70)、`configure`@632(67)、`handleIdleAlert`@1014(67)、`registerStarting`@365(90)、`startAndAwaitFirstPoll`@203(75)、`prepareAttempt`@257(65)、`SessionArchived`分支@639(58)、`commitSseTransport`@1105(50)。

---

## §2 内容重复目录（exp-6，24 cluster，按 dup LOC 降序）

| # | Cluster | 位置（行） | dup LOC | 风险 | 抽取提案 |
|---|---|---|---|---|---|
| 6 | **barrier vs 非-barrier 体**（HostProfileController） | save@243-268/276-296；delete@392-499/502-540；select@588-624；configureServer@752-768；reset@1024-1105 | **200+** | **高** | `withReconfiguration(action:(SlimReconfigureTicket?)->Unit)` 封装 barrier 分叉+identity+slim preamble+CancelSseForReconfigure+ticket 归属；每操作核心体降为单 lambda ✅ **done**（η 287f476） |
| 2 | DebugCardIdentity wrapper | ChatMessageRow.kt(15×)、ChatMessageContent.kt(3×) | ~270 | 中 | 已集中至 DebugCardIdentity.kt:34；`source="File:line"` 字面量脆弱，可 `StackWalker` 自动派生（次要） |
| 14 | auto-select 逻辑 + pending-create sweep | SessionListActions.kt: launchLoadSessions@365-432/229-240 vs launchLoadMoreSessions@540-569/515-526 | ~140 | 中 | `autoSelectSession(...)` + `sweepPendingCreateIds(...)` ✅ **done**（N1 950d6b3） |
| 4 | Picker sheet skeleton | Composer.kt: AgentPickerSheet@462-515 + ModelPickerSheet@519-642 | ~120 | 中 | `PickerSheet<T>(title,items,key,isSelected,onPick,...)`；ModelPicker 的 provider 分组加 `sectionBy` 参数或 `SectionedPickerSheet` |
| 5 | DropdownMenuItem(text+icon+onClick) | ChatTopBar.kt@595-681(5×)、MessageCard.kt@294-369(3×) | ~120 | 低 | `MenuItem(text,icon,onClick,enabled)` 工厂 |
| 8 | `runSuspendCatching{api.xxx}` 薄包装 | OpenCodeRepository.kt(~50×) | ~100 | 低 | 已有 RunSuspendCatching.kt；可 `ApiDelegate` 模式，低优先 |
| 12 | BootstrapFailure 拆除 + terminal.complete + emit 三连 | SSS failStarting@657/abortExpiredStartup@639 + SLC teardownForBootstrapFailure@744；OwnershipGate terminal.complete@446,501,508,566,632,653(6×)；SLC emit(StopSse/StopForeground/StopSelf)@548,720,748,1072(4×) | ~100 | 中-高 | `emitTeardownCommands(stopSse,stopPoller)` + `settleTerminal(reason)`；**拆除 emit 顺序时序敏感，须保序** |
| 20 | scope.launch+tryEmitEffect scaffold | ConnectionCoordinator(12×)、HostProfileController(6×)、SessionSyncCoordinator(8×) | ~100 | 低 | 控制器基类 `emitEffect(effect)`；tryEmit vs emit 不一致可统一 ⬜ **deferred**（intentional — correctness-critical, <20 LOC savings, invasive） |
| 16 | `Surface(RectangleShape){Row(icon,spacer,text)}` skeleton | ChatMessageRow.kt OmittedContentCard 分支@363-527(4×)、ChangesPane.kt@203-252 | ~75 | 低 | `StatusBanner(icon,text,color,onClick)` |
| 9 | getMessagesPaged vs Unanchored + Q/P 二元组 | OpenCodeRepository.kt: @1294-1317 vs @1364-1387；getSlimapiQuestions@2768 vs getSlimapiPermissions@2796；reply@2878 vs reject@2894 | ~76 | 中 | `getMessagesPaged(...,since:Long?=null)`；`fetchSlimapiAggregate<T>()` 泛型；`mutationCall(block,label)` ✅ **done**（ζ-3 4a0a4d1） |
| 3 | BoxWithConstraints cardMax | ChatMessageRow.kt@133-136、ChatMessageContent.kt@927,1106,1136 | ~24 | 低 | `CardWidthScope(content:(Dp)->Unit)` + `MAX_CARD_WIDTH=480.dp` |
| 10 | applyPartDeltaLeadingEdge 重载 + stale-token guard | SessionSyncCoordinator.kt: @3315 vs @3334；stale-guard 8×@1522,1526,1619,1811,1819,2267,2404,2409；commitIfSlimTokenCurrent 3×@1206,1515,1914 | ~45 | 中 | 合并重载(nullable 参数)；`withTokenGate(token,block)` |
| 13 | `finally` flag-clear + withSessionLock guard | MessageActions.kt: @435-447 vs @614-626 | ~24 | 低 | `clearLoadingFlag(sessionId,slices,flag:KProperty1)` |
| 11 | cancelSse vs cancelSseForReconfigure + guards | ConnectionCoordinator.kt: @1031 vs @1058；TOFU-frozen 4×@304,351,677,982；identityStore stale 3×@768,811,874；settled guard@323 vs 560 | ~30 | 低 | `cancelSseInternal(reason)` + `checkTofuFrozen()`/`checkIdentityStale()` ✅ **done**（L4c，commit 3c9173f） |
| 7 | Slim state 直通透孔（6×） | OpenCodeRepository.kt@3188-3320 | ~18 | **低** | 调用点直接 `slimStateMachine.markXxx(...)`，删透孔 ✅ **resolved-by-design**（I20+T3RepositoryExtractFreezeTest locks 6 as OCR public API） |
| 23 | hostPortFromUrl+sslConfig resolve preamble | HostProfileController.kt@803,882；OpenCodeRepository.kt configure/probeSlimapiHealth | ~24 | 低 | `resolveSslConfig(url,hostPort,clientCert)` |
| 21 | backoff math | OpenCodeRepository.kt@2583 vs ProcessStatusPoller.kt@320 | ~40 | 低 | `exponentialBackoffMs(attempt,baseMs,maxShift)` ✅ **done**（6a-21，commit a6521e0；落 `util/Backoff.kt`） |
| 24 | reply/reject question envelope | OpenCodeRepository.kt legacy@1748-1766 vs slim@2878-2905 | ~16 | 低 | `mutationCall(block,label)`（同 cluster 9） ⬜ **deferred**（legacy/slim request-type diff） |
| 17 | recentWorkdirs CRUD guard + put/apply | SettingsManager.kt@259,281,317,357 | ~4 | 低 | `requireValidFp(fp)` guard ✅ **done**（L4b，commit 3c9173f） |
| 15 | SessionArchived reducer vs dispatchBulkArchivedSessions | AppAction.kt@639-696 vs AppCoreOrchestration.kt@788-833 | ~30 | 中 | **保持 CQRS 拆分**（有意：reducer 纯态 / effect 副作用） |
| 18 | idleMutex→Main.immediate publish | AppLifecycleMonitor.kt@975-1007 vs SseNotificationBridge.kt@293-320 | ~30 | 低-中 | `publishIdleNotification(...,pruneBefore:Boolean=false)` ✅ **done**（θ 78081c8） |
| 19 | checkHealth/checkHealthFor 双路 | OpenCodeRepository.kt@912-920 vs @1046-1109 | ~60 | 中 | checkHealthFor 委托 probeSlimapiHealth（代码已有 TODO） ✅ **NO-GO**（全委托重引入 R2#1 mTLS leak；sslConfigFor vs resolveProbe 不可合并） |
| 1 | workdir basename 惯用法 | SessionsScreen.kt@563,713,888、ChatTopBar.kt@403、Composer.kt@382、ChatOverlayHost.kt@210、SessionPickerSheet.kt@195、Session.kt@31 + ChangesPane substringAfterLast 3× | ~9 | **低** | `fun String.workdirBasename()` 入 WorkdirPaths.kt ✅ **done**（6a-1，commit a6521e0） |
| 22 | getAgents/getCommands 一行包装 | OpenCodeRepository.kt@1868,1873 | ~6 | 低 | 被 cluster 8 覆盖 ✅ **done**（ζ-3, absorbed into cluster 8 scope） |

**exp-6 额外发现（非 seed）**：cluster 19-24（checkHealth 双路 / effect 发射 scaffold / backoff / sslConfig preamble / reply-reject envelope / 一行包装）。

---

## §3 抽取提案汇总（按落地归属）
> **进度（v0.13.1，α→θ 全线完成）**：✅ done — cluster 1 (6a-1) / 21 (6a-21) [a6521e0] ‖ 11 (L4c) / 17 (L4b) [3c9173f] ‖ 6 (η, 287f476) / 9 (ζ-3, 4a0a4d1) / 14 (N1, 950d6b3) / 18 (θ, 78081c8) / 22 (ζ-3) ‖ 3/5/16 UI 工厂 (N1, 950d6b3)。⬜ deferred-by-analysis — cluster 7 (resolved-by-design, I20+T3FreezeTest) / 19 (NO-GO, mTLS leak) / 20 (intentional emit diff) / 24 (legacy/slim request-type diff)。其余 cluster（2/4/8/10/12/13/15/23）按各自 wave 已落地 ✅，详见 §2 行内标记与 `refactor-handoff.md` §3。

**Wave 6 通用 helper（跨文件，低风险，可独立先行或末波清理）**：
- `workdirBasename()`（cluster 1）→ `util/WorkdirPaths.kt` 【9+ 处，低风险，速胜】
- `exponentialBackoffMs()`（cluster 21）→ `util/`
- `resolveSslConfig()`（cluster 23）→ `data/repository/http/`
- `MenuItem()` / `StatusBanner()` / `CardWidthScope()` / `PickerSheet<T>()`（cluster 3/4/5/16）→ `ui/theme/` 或 `ui/chat/components/`

**归入拥有该文件的 god-file 波次 lane（顺手做）**：
- cluster 6（barrier 合并）→ 随 HostProfileController（非文件拆，先于 L5b 的 3-层折叠，见 plan §2 优先级注）
- cluster 7（slim 透孔删除）→ 随 L4a OpenCodeRepository
- cluster 9/19/22/24（repository 重复）→ 随 L4a
- cluster 10（stale-token/reducer 重复）→ 随 L1a SessionSyncCoordinator reducers
- cluster 11/20（ConnectionCoordinator）→ 随 L4c
- cluster 12（streaming 拆除）→ 随 L3c BootstrapFailure 合并
- cluster 13（MessageActions flag-clear）→ 独立或随 L2 后
- cluster 14（SessionListActions auto-select）→ 独立低风险 lane
- cluster 17/18（SettingsManager/ALM）→ 随 L4b / L1b
- cluster 2（DebugCardIdentity source 自动化）→ 次要，可延后

---

## §4 优先级 × 风险（速览）

| 优先级 | cluster/函数 | 收益 | 风险 |
|---|---|---|---|
| **P1 速胜** | cluster 1 workdirBasename；cluster 7 slim 透孔；cluster 21 backoff | 小-中 | 低 |
| **P1 高价值** | cluster 6 barrier 合并（200+ LOC，最大单点）；cluster 14 auto-select | 高 | 中-高（cluster 6 需 CD3 评审） |
| **P2** | cluster 4/5/16 UI 工厂；cluster 12 拆除合并；cluster 9/19 repository 合并 | 中 | 中 |
| **P3 次要** | cluster 2 source 自动化；cluster 8/22 包装；cluster 15 保持 | 低 | 低 |

---

## §5 与 refactor plan Wave 6 的整合
- 本目录为 Wave 6 的权威输入；具体排程（哪些 cluster 归入哪个 god-file 波次 lane、哪些独立先行）待 `refactor-optimization-plan.md` 经 rev-grok 评审后，在 plan §2/§3 定稿。
- 关键耦合（与 `docs/refactor-optimization-plan.md` v3 对齐）：cluster 6（barrier）应**先于** L5b（HostProfileController 文件拆）完成——先折叠 3 层重复再拆文件，减负 ~200 LOC；cluster 10 应**后于 L1a 独立 lane**（不污染近零）；cluster 12 应**随 L3c 两步**（语义合并→emit 抽取）；cluster 18 应**后于 L1b 独立**（不污染近零）。
