# 代码优化计划表与方案（refactor plan）— v3（R1+R2 评审后定稿）

> v3 生成：2026-07-23。输入：Phase-1 god-file 排查 + Phase-2 重复/大函数 + rev-grok 两轮评审（R1 6.5/10 → R2 **7.8/10，放行带修改**）。
> **v2→v3 变更（R2 整合评审采纳）**：① cluster 18 移出 L1b（近零不污染）→ L1b 后独立；② §3 改为 **Phase α→θ 整合执行序**（拆分+整合交错）；③ cluster 6 写入 `withHostReconfiguration` 三分支契约；④ cluster 12 L3c 内两步（语义合并→emit 抽取）；⑤ 6a-1 明确**扩展现有 `WorkdirPaths.kt`**；⑥ 补硬序 L1e→6a-4、6a-1→L5c、6a-21/23≤L4a1。
> 目标：**纯行为保持型**模块化重构，分波次并行，每波 `./scripts/check.sh` 门控（compile + ~3967 单测全绿）。

---

## §0 评审结论（rev-grok R1 + R2）
- **R1 6.5/10 → R2 7.8/10，Conditional GO**。两个正确性硬伤（R1：L2 穷举 / L3a 删 PollerRuntime）+ 两处整合错配（R2：cluster 18→L1b / 6a-4 顺序未入图）均已纠正。
- **R2 整合性意见**：「拆分主线（1′→2→3→4）与整合双轨可共存且应**交错**：先抽与上帝文件无关的 6a util 降噪，用干净的 1′-A/B 换导航收益，L1e 后再工厂化 Picker，高风险收敛（6/12/7·9·19）只叠在本就打开不变量的 L5b/L3c/L4a 上——近零 lane 保持无菌，上帝文件动刀前先卸纯函数与 barrier 债。」
- **可立即执行**：Phase α（6a util 速胜）+ Phase β（Wave 1′-A）。

---

## §1 原则与风险控制
1. **行为保持**：只移动/抽取，不改逻辑/签名/wire。每波门控 = ~3967 单测全绿。
2. **同包 `internal` 优先**：纯移动用**同包**（package 不变）+ `internal`，仅物理拆文件。L1a=`ui.controller`、L1f=`ui`，禁默认丢子包（全仓 import churn）。Kotlin sealed 跨文件须同包同模块。
3. **写域不重叠 + 白名单**：每 lane prompt 钉死写文件清单；reconcile 前 diff 验无越界；跨文件语义耦合降为单 lane 或分波。
4. **fixer 路由**：纯移动/简单 → `fixer-zlm`（≤3 并发，单飞/白名单）；reducer/跨文件不变量/重试 → `fixer`（强模型单飞）。
5. **近零 lane 不折叠非平凡收敛**（R2）：L1a/L1b/L1c/L1d 保持无菌；收敛项进高风险 lane（L3c/L4a/L5b）或独立 lane。
6. **高风险前置**：`OpenCodeRepository` 分包前必须 L4a0 不变量映射表。

---

## §2 计划表

### Wave 1′ — 纯抽取（先 3 真零，再 2 修正项）
**1′-A（并行 ≤3 zlm，真近零）：**
| Lane | 项 | 写域（白名单） | 方法 | 风险 | LOC |
|---|---|---|---|---|---|
| L1b | AppLifecycleMonitor 解耦 | `di/AppLifecycleMonitor.kt` + 新 `service/notify/{SessionNotifier,NotificationDedup}.kt` + `di/ApplicationScopeModule.kt` | 3 独立类纯文件移动；⚠️ 新文件勿与既有 `SessionStatusNotifier`/`SseNotificationBridge` 同名；**本波不叠 cluster 18**（见 Wave 6） | 近零 | ~700 |
| L1c | SLC 模型类型 | `service/lifecycle/StreamingLifecycleCoordinator.kt` + 新 `service/lifecycle/{Layer,LifecycleCommand,CoordinatorModels}.kt` | 9 sealed/data(1247-1359) 外提同包；**不删 PollerRuntime 语义** | 近零 | ~180 |
| L1d | Ownership 模型类型 | `service/StreamingOwnershipGate.kt` + 新 `service/{OwnershipResults,OwnershipAckPolicy,OwnershipRequestParser}.kt` | 6 顶级 sealed + 2 尾部类移出 | 近零 | ~185 |

**1′-B（1′-A 门控后，并行）：**
| Lane | 项 | 写域（白名单） | 方法 | 风险 | LOC |
|---|---|---|---|---|---|
| L1a | SSC 尾部 reducer | `ui/controller/SessionSyncCoordinator.kt` + 新 `ui/controller/{SseChatReducers,SseSessionListReducers}.kt`（**package `ui.controller`，无 `sse/` 子包**） | 2794-3687 ~30 纯 `applyXxx`/`mergeSlimMessages` 搬迁；AppAction import FQN 不变；`isAuthoritativeSlimMerge`@2542 在类内不迁 | 近零 | ~900 |
| L1e | Composer 孤儿 pickers | `ui/chat/Composer.kt` + 新 `ui/chat/PickerSheets.kt` + `ui/chat/ChatScaffold.kt`(import) + **`ui/chat/ChatOverlayHost.kt`(import @103/124)** | AgentPickerSheet/ModelPickerSheet(@462-642) 移出 | 近零 | ~180 |
| L1f（可选） | AppStateSlices 按片拆 | `ui/AppStateSlices.kt` + 新 `ui/{ChatState,SessionListState,SliceFlows}.kt`（同包 `ui`） | ChatState/SessionListState/SliceFlows 移出 | 近零 | ~375 |

### Wave 2 — Reducer 拆分支体（单 lane fixer，穷举纠正）
| Lane | 项 | 写域 | 方法 | 风险 | 依赖 | LOC |
|---|---|---|---|---|---|---|
| L2 | AppAction.reduce 拆分支体 | `ui/AppAction.kt` + 新 `ui/reducer/{ChatFields,SessionListFields,StreamingBufferFields,ScrollFields}Reducer.kt` | **主 `reduce` 保留单一 exhaustive `when(action)`**（编译器强制）；子文件只载**分支体 helper** `(StoreState, AppAction.X) -> StoreState`；**禁止仅靠 `?:` 链宣称穷举**；子文件可置 `ui/reducer/`（拆函数非 sealed 子类） | 低-中 | L1a（仅 FQN 稳定） | ~720 |

### Wave 3 — Streaming 集群（3a′ 并行 / 3b 串行）
| Lane | 项 | 写域 | 方法 | 风险 | 依赖 | LOC |
|---|---|---|---|---|---|---|
| L3a′ | PollerRuntime 类型/文档（**不删 runtime**） | `service/lifecycle/StreamingLifecycleCoordinator.kt` + `service/streaming/ProcessStatusPoller.kt` | 类型随 L1c 外提 + 双平面文档（控制面 PollerRuntime / 数据面 loopJob）；保留 requestId 握手；可选后续统一须配 stale-ack 测 | 低 | L1c | ~60 |
| L3b | SSS 通知剥离 | `service/SessionStreamingService.kt` + 新 `service/notify/ForegroundNotificationPublisher.kt` | buildNotification(711-902) 移出 | 低-中 | — | ~250 |
| L3c | BootstrapFailure 拆除合并（+cluster 12 两步） | `service/SessionStreamingService.kt` + `service/lifecycle/StreamingLifecycleCoordinator.kt` | **Step A** 语义合并：`rollbackBootstrap(kind: Timeout\|Failed, identity?)`（abortExpiredStartup 仅 teardown vs failStarting 先 ownership rollback 再 teardown，分支保留 + `bootstrapAbortIssued` 幂等）；**Step B**（同 PR 后半/+1）抽 `emitTeardownCommands(stopSse,stopPoller,stopFg,stopSelf)` **保序** + `settleTerminal(reason)` | 中 | L3a′, L3b | ~80 |

### Wave 4 — God-File 大拆（L4a 分阶 a0–a3；L4b/L4c 可并行）
| Lane | 项 | 写域 | 方法 | 风险 | 依赖 | LOC |
|---|---|---|---|---|---|---|
| L4a0 | OCR 不变量映射 | `data/repository/OpenCodeRepository.kt`(注释/表) | 不变量表：hostConfig@216 / 4×OkHttp-Retrofit@251-316 / slimStateLock+SlimSseStateMachine@387-394 / beginSlimReconfigure/Ticket@451-548 与 `configure()` 原子换栈绑定 | — | — | doc |
| L4a1 | TofuRepository 抽 | `OpenCodeRepository.kt` + 新 `TofuRepository.kt` | captureServerCert/applyTofuDecision/pinnedSpkiFor/clearTofuPin(738-873) | 中 | L4a0 | ~135 |
| L4a2 | ExpandBatchEngine 抽 | `OpenCodeRepository.kt` + 新 `ExpandBatchEngine.kt` | drivePartition(2259-2523)/expandMessagesFullBatch/mergeResults/fallbackSingleFull/backoffMs + cache + single-flight | 中 | L4a0 | ~500 |
| L4a3 | 薄 GET 域委托 + slim 轴 + compat 门面 | `OpenCodeRepository.kt` + 新域 Repository + data class 移 `data/model/` | 原文件留 ~100 行 compat 门面；**顺带 cluster 7（透孔删）/9/22/24**；cluster 19 checkHealth 委托可前至此半或 L4a1 后独立 | 高 | L4a1,a2 | ~剩余 |
| L4b | SettingsManager 分域 | `util/SettingsManager.kt` + 新 `util/*Prefs.kt` + `MigrationHelper.kt` | 先清单 key→Prefs；ESP key 所有权+迁移测试；**顺带 cluster 17** | 中 | — | ~900 |
| L4c | ConnectionHealthProbe 抽 | `ui/controller/ConnectionCoordinator.kt` + 新 `ConnectionHealthProbe.kt` | testConnection*(@292-676)+TOFU 委托+init 钩子；**顺带 cluster 11**（cancelSseInternal/guards） | 中 | — | ~400 |

> L4a1‖L4b‖L4c 可并行（白名单钉死）；L4a2 随 a1；L4a3 串行收尾。**禁单 lane 九域 3800 LOC。**

### Wave 5 — UI 批次（并行；L5b 含 cluster 6 前置）
| Lane | 项 | 写域 | 方法 | 风险 | 依赖 | LOC |
|---|---|---|---|---|---|---|
| L5a | ChatScaffold overlays 抽 | `ui/chat/ChatScaffold.kt` + 新 overlays/pager/derived 文件 | ChatOverlayHost(28参)+overlay 状态+pager+topBarState 派出 | 低-中 | L1e | ~600 |
| L5b | HostProfileEditorDialog 抽（**含 cluster 6 前置**） | `ui/controller/HostProfileController.kt`(cluster 6) → `ui/settings/HostProfilesManagerScreen.kt`+新 Dialog/CertViews | **先 cluster 6**（`withHostReconfiguration`，见 Wave 6 契约）→ check.sh → 再拆 Dialog(730)+helpers+CaStage | 中（CD3） | cluster 6 | ~850+200 |
| L5c | SessionsScreen/SessionCard 抽 | `ui/sessions/SessionsScreen.kt` + 新 `SessionCard.kt`+derivation | SessionCard(180)+helpers 移出（**6a-1 须先于此**，减 diff 噪声） | 低 | 6a-1 | ~250 |

### Wave 6 — 重复整合（双轨，权威：`docs/refactor-duplication-and-functions-backlog.md`）
**Track 6a — 速胜 helper（独立，低风险）：**
| cluster | 抽取 | 落点 | 风险 |
|---|---|---|---|
| 1 | `String.workdirBasename()` | **扩展现有 `util/WorkdirPaths.kt`**（已有 normalize/normalizeDirectory，禁平行新文件） | 低 |
| 21 | `exponentialBackoffMs(attempt,baseMs,maxShift)` | `util/` | 低 |
| 23 | `resolveSslConfig(url,hostPort,clientCert)` | `data/repository/http/` | 低 |
| 3/5/16 | `CardWidthScope`/`MenuItem`/`StatusBanner` | `ui/theme/` 或 `ui/chat/components/` | 低 |
| 4 | `PickerSheet<T>` | `ui/chat/components/`（**L1e 门控绿后**；写域仅 PickerSheets+调用方 import，禁回写 Composer） | 低-中 |

**Track 6b — 折叠/独立 cluster：**
| cluster | 抽取 | 归属 | 顺序 | 风险 |
|---|---|---|---|---|
| 6 | `withHostReconfiguration`（契约见下） | **L5b 前置** | 先于 L5b Dialog 拆 | 高（CD3） |
| 7 | slim 6 透孔删 | L4a3 | 随 L4a | 低 |
| 9/22/24 | getMessagesPaged 合并/聚合泛型/mutation envelope | L4a3 | 随 L4a | 中 |
| 19 | checkHealth 双路委托 | L4a3 前半 或 L4a1 后独立 | 勿塞 L4a2 Expand | 中 |
| 10 | delta 重载合并 + stale-token guard | **L1a 后独立 lane** | 后于 L1a，勿污染近零 | 中 |
| 11 | cancelSseInternal + guards | L4c | 随 L4c | 低 |
| 12 | emitTeardownCommands/settleTerminal | **L3c Step B** | 随 L3c（两步） | 中-高 |
| 13 | clearLoadingFlag | L2 后 | 后于 L2 | 低 |
| 14 | autoSelect/sweepPendingCreateIds | 独立低风险 lane | 任意 | 中 |
| 17 | requireValidFp guard | L4b | 随 L4b | 低 |
| 18 | publishIdleNotification | **L1b 后独立 lane**（R2：不污染近零，ALM↔bridge 共享 idleMutex） | 后于 L1b | 低-中 |
| 20 | emitEffect 基类 | 末波 | — | 低 |
| 2/8/22 | source 自动化/ApiDelegate | 延后 | — | 低-中 |
| 15 | SessionArchived CQRS | **保持** | — | — |

**cluster 6 契约（R2 可采用 Kotlin，三分支保 CD3 边界）：**
```kotlin
internal suspend fun <T> HostProfileController.withHostReconfiguration(
    needsReconfigure: Boolean,
    body: suspend (ticket: OpenCodeRepository.SlimReconfigureTicket?) -> T,
): T {
    if (!needsReconfigure) return body(null)                 // 冷路径：无 ticket/无 CancelSse
    val barrier = reconfigureBarrier
    return if (barrier != null) {
        barrier.reconfigure { ctx -> body(ctx.slimTicket) }  // barrier active（teardown 在 barrier 内）
    } else {
        identityStore?.beginReconfigure()                    // 非 barrier active
        val ticket = repository.beginSlimReconfigure()
        effects.tryEmitEffect(ControllerEffect.CancelSseForReconfigure)
        body(ticket)
    }
}
// 每 API（save/delete/select/configure/reset）post-body 差异（EvictGroup/openIds/purge）保留在调用点
```
| 场景 | ticket | CancelSse/teardown | configure(ticket) |
|---|---|---|---|
| barrier active | ctx.slimTicket | barrier 内 teardown | 必须 |
| 非 barrier active | beginSlimReconfigure() | 显式 CancelSse | 必须 |
| 冷/非 active | null | **禁止** | **禁止** live ticket |

---

## §3 整合执行序（Phase α→θ，R2 最优路径）
> **进度（v0.13.1 post-release，α→θ 全线完成）**：α/β/γ/δ/ε/ζ-1/ζ-2/ζ-3/η/θ ALL ✅ done。
```
✅ α  6a 纯 util 速胜（‖ β，zlm≤2）：6a-1 workdirBasename(扩展 WorkdirPaths) / 6a-21 backoff / 6a-23 resolveSslConfig   // done a6521e0（6a-1/6a-21；6a-23 ssl 随 ζ-1 L4c）
✅ β  Wave 1′-A：L1b ‖ L1c ‖ L1d                       // 禁折叠 18  // done a6521e0
✅ γ  Wave 1′-B：L1a ‖ L1e → check.sh → 6a-4 PickerSheet(仅 PickerSheets) → cluster 10 独立   // done a6521e0
✅ δ  Wave 2：L2(fixer) → cluster 13(可选紧随)          // done a6521e0
✅ ε  Wave 3：L3a′ ‖ L3b → L3c(Step A rollbackBootstrap) → cluster 12(Step B emit 保序)   // done 665cf79（L3a′+L3c+cluster12）；⚠ L3b（SSS 通知剥离 → ForegroundNotificationPublisher）未做，pending
✅ ζ-1 Wave 4a：L4a0 → 6a 剩余 UI 工厂 3/5/16 穿插 → L4a1 ‖ L4b(含17) ‖ L4c(含11)   // done 3c9173f（L4a1 TofuRepository ‖ L4b SettingsManager ‖ L4c ConnectionHealthProbe；cluster 11/17 顺带）；⚠ 6a-3/5/16 UI 工厂未穿插，pending
✅ ζ-2 Wave 4b：L4a2（ExpandBatchEngine 抽）            // done f448318
✅ ζ-3 Wave 4c：L4a3(domain-delegate + type extraction + cluster 9)        // done 4a0a4d1；cluster 7 resolved-by-design / 19 NO-GO / 24 deferred
✅ η  Wave 5：cluster 6(withHostReconfiguration) → L5b ‖ L5a ‖ L5c        // done 287f476(cluster6) + 0673dfa(L5b‖L5c) + fcab357(L5a)
✅ θ  收尾：cluster 18 done / 14 done(N1) / 20/2/8 deferred-by-analysis   // done 78081c8(cluster18) + 950d6b3(N1-cluster14)
```
- **硬序**：L1e→6a-4；L1e→L5a；L1a→cluster10；L1b→cluster18；6a-1→L5c；6a-21/23 宜 ≤L4a1；cluster6→L5b；L3b→L3c+12；L4a0→a1→a2→a3。
- **并行点**：α‖β；1′-A（3）；1′-B（2）；3a′（2）；L4a1‖L4b‖L4c（3）；W5（3）。
- **风险放大组合（避免）**：L1b+18 同 lane；L1e‖6a-4；L4a*‖6a 改 OCR；L3c+12 一次做完无子步；cluster6+L5b 同 PR（须 6 绿再拆）。
- **关键路径瓶颈**：L1a→L2；L3b→L3c(+12)；L4a0→a1→a2→a3；cluster6→L5b。
- **「先还债再拆」**：α util + cluster 6 前置正确；cluster 10 紧接 L1a（不先于）；**勿**用「全 6a 做完再 W1」阻塞 Top-3 导航收益。

---

## §4 每波验证
- 每波 reconcile 后 `source ./scripts/env.sh && ./scripts/check.sh`（compile + ~3967 单测全绿）。
- 纯移动波不加测；L2/L3c/L4a*/L5b/cluster6 现有测试全绿即门，必要时补（L3a′ ack 统一则补 stale-ack/Stop-beats-Ensure；cluster 6 补三分支事务测）。
- 每 lane reconcile 前 diff 验**写文件不越白名单**。
- 不用模拟器（纯 JVM）；UI 波视觉等价由 Compose 预览/既有 UI 测保证。

---

## §5 优先级与放行（v3）
- **v0.13.1 进度**：α→θ **全线完成**（commits a6521e0→78081c8，all gated GO）。**全部完成** α→θ。所有遗留子项已闭环：L3b ForegroundNotificationPublisher done（N1 950d6b3）；6a-3/5/16 UI 工厂 done（N1 950d6b3）；cluster 7 resolved-by-design / 19 NO-GO / 20/24 deferred-by-analysis。
- **按 α→θ 推进**：✅α→✅β→✅γ→✅δ→✅ε→✅ζ-1→✅ζ-2→✅ζ-3→✅η→✅θ → **全部完成**。
- **NO-GO（已从计划移除）**：L3a 删 PollerRuntime、L4a 单 lane 九域、cluster 15 合并、cluster 18 进 L1b。
- **主线价值序**：✅L1a(同包)→✅L2(修穷举)；✅streaming L3b→L3c(+12)；✅L4a0+Tofu/Expand 动上帝文件；✅cluster 6→L5b；✅θ 收尾。**α→θ 全线完成**。
