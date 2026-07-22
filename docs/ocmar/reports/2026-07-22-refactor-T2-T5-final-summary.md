# ocdroid 全量重构 — T2/T3/T4/T5 最终总结（续作会话 2026-07-22）

> **HEAD**：`09953a6`（T2/T3/T4/T5 已合 main）  
> **状态**：**核心重构完成**。T0 + T-R1 + T1 + T2 + T3 + T4 + T5 全部完成并提交。每 task：impl → `./scripts/check.sh` exit 0 → rev-gpt ≥9.5（新会话）→ task-scoped commit。  
> **真相源**：`docs/ocmar/specs/2026-07-22-full-refactor-plan.md`  
> **前序 handoff**：`docs/ocmar/reports/2026-07-22-refactor-progress-handoff.md`（T0-T1 + recon 快照）

---

## 1. 提交链（本轮续作）

```
09953a6 chore(t5): 清理过时 KDoc + supports() 缓存为 val
a925614 feat(t4): 抽 ChatOverlayHost (尾部 overlay 簇), ChatScaffold 1522→1409 (机械 1:1)   [rev-gpt 9.8]
e638d43 feat(t3): 抽 SlimSseStateMachine (slim 状态机核心), OCR 退化为 forwarder (机械 1:1)  [rev-gpt 9.7]
8b6328a feat(t2): dispatchSseEvent 抽取为 SseEventRouter + handlers (机械 1:1)               [rev-gpt 9.7]
572c0e1 feat(t1d): ... （前序）
```

---

## 2. 各 Task 实现要点

### T2 — 拆 `dispatchSseEvent`（SSE dispatch）
- `SessionSyncCoordinator.dispatchSseEvent` ~933 行 `when` 块 → 新包 `ui/controller/sse/`：`SseEventRouter` + `SharedConversationSseHandler`/`LegacySseHandler`/`SlimSseHandler`（三个 `SseEventHandler`）+ `SseDispatchHost` host 接口 + `ModeDomain`。
- SSC 退化为 verbose 日志 + `sseRouter.route(event)`。
- **Host 接口模式**：SSC 实现 `SseDispatchHost`，3 handler 拿 host 搬分支体（逐字，`host.` 前缀）。`unknownEventCounters`/`unknownEventCountsSnapshot()`/`flushJobs`/`scheduleDeltaFlush`/`flushDeltaBuffer`/verbose-diag 日志块**留 SSC**。
- **修正轮**：消除 fixer 的构造参数 rename 抖动（`currentServerGroupFp`/`isSlimMode`/`clock`→`*Provider` 连带改 13 测试 + ControllerModule）——改在**接口侧**改名（`serverGroupFp()`/`sseClock()`/`slimMode()`），SSC 属性名恢复，**测试/ControllerModule 零改动**。
- C3 保持：legacy `/global/event` 与 slim `/slimapi/events` 事件集不相交；message.part.* 仅 SharedConversationSseHandler；无运行时 isSlimMode 门。

### T3 — 拆 `OpenCodeRepository`（slim 状态机）
- slim 状态机核心（`slimSseState` + `slimCommitMarker` + `slimIncarnationReady` + 15 个 state op + `withSlimStateCommit`/`requireCurrentReconfigureTicket`/`bumpSlimBookmarkFromItems`）→ 新类 `SlimSseStateMachine`（390 行）。
- `slimStateLock` 字段**留 OCR**（freeze §4c 硬要求），**同一实例**注入机器 → T11 round-2 原子性（lost-update）不破。
- 4 嵌套类型（`SlimCommitToken`/`SlimReconfigureTicket`/`StaleSlimCommitException`/`SupersededSlimReconfigureException`）**留嵌套 OCR**（Kotlin typealias 不能是类成员，无法 re-export 嵌套 FQN；freeze §4），机器跨类引用 `OpenCodeRepository.SlimCommitToken`。
- cold-start/messages/expand HTTP 编排**留 OCR**（调机器 `requireSlimTokenCurrent`/`bumpSlimBookmarkFromItems`，无跨类 lambda）。T3-A 保守方案。
- **编排者修了一处 fixer 引入的行为 bug**：`coldStartSlimSync` 消息分支的原版原子块 `synchronized(slimStateLock){ if(token.marker!==slimCommitMarker) throw; bookmark=slimSseState.get(sid)?.updatedAt }` 被 fixer 拆成 `requireSlimTokenCurrent`+`getSlimSessionState`（丢原子性 + marker-only→3-condition 强化）且删了 HTTP 后 `requireSlimTokenCurrent` 重检——会**把 host rotation 掩盖成 "server unreachable"**（违反 OCR:3036-3037 注释的显式设计意图）。修复：加机器方法 `readBookmarkOrThrowIfStale`（原子 marker-only 检查+读，1:1 镜像原版）+ 恢复 HTTP 后重检（在 `response.isSuccessful` 之前）。

### T4 — 拆 Chat UI（保守首轮）
- ChatScaffold 尾部 8 个 overlay（Agent/Model/SessionPickerSheet + TodoListPanel + ContextUsageDialog + workdir picker + error-detail AlertDialog + TofuTrustDialog）→ 新文件 `ChatOverlayHost.kt`（255 行）。
- **state hoisting 不变**：show* state 留 ChatScaffold 拥有，ChatOverlayHost 只接收 value + callback。
- overlay 仍三层 A/B/C + 共享原语（AppBottomSheet / AlertDialog family）。
- chatVM 透传不变（T1 桥 streamingPartTexts/expandedParts/partExpandStates 未碰）。
- ChatScaffold 1522→1409。
- **ChatScaffold 无渲染测试**——验证靠参数透传语义核对（`git show` 基线逐项比对）+ rev-gpt 代码审；渲染/recomposition/layout 不被 check.sh 覆盖。

### T5 — 清理
- `SlimSseReducer.kt:11` 过时 KDoc（引用已搬走的 `OpenCodeRepository.slimSseState`）→ 改指 `SlimSseStateMachine`。
- `SlimSseStateMachine.kt:13` "package-private" 措辞 → "private"（helpers 实际 private）。
- 3 个 SseHandler 的 `supports()` 每次 `setOf(...)` 分配 → 缓存为 `private val supportedTypes`。

---

## 3. 验证状态

| Task | check.sh | rev-gpt | commit |
|---|---|---|---|
| T2 | exit 0 | **9.7** | `8b6328a` |
| T3 | exit 0（+coldStart 行为修复后重测） | **9.7** | `e638d43` |
| T4 | exit 0 | **9.8** | `a925614` |
| T5 | exit 0 | —（清理项） | `09953a6` |

- 方案 A：T2 先串行消红（真实 impl 变绿，无 @Ignore），T3/T4 之后。写范围不重叠（T2 SSC+sse / T3 data.repository / T4 ui.chat）。
- T2/T3/T4 freeze 测试随 impl 一起提交（RED 锚由 impl 变绿）。

---

## 4. imperfect / deferred（最终总结必收录）

### T2
- **`session.digest` 浅抽取**（SlimSseHandler 委托 `host.handleSessionDigest`）——合理：digest 耦合 SSC reconcile 状态机（sseSyncState/performSlimResync），深搬需连状态机一起搬，超 T2 范围。
- **`ModeDomain` 当前仅存在**（Router 不显式用 mode）——Contract 1a 要求其存在；C3 依赖 wire feed 隔离非运行时 mode 门。保留。
- **Contract 1b 仅反射查 handler 类存在**，未调 `supports()`——路由集合验证靠代码审（T5 已把 supports 缓存为 val）。

### T3
- **coldStartSlimSync marker-only 原子检查**（非 3-condition）——1:1 保留原版语义；若原版是 latent bug，单独处理（非重构范畴）。
- **cold-start/messages/expand HTTP 方法留 OCR**（T3-A 保守）——可后续 T3-B 搬全簇进机器（需 api 注入 + expand 专用状态 `thinRouteNotFoundCache`/`singleFlightMap`/`lastExpandBudgetCounters` 迁移）。

### T4
- **ChatDrawerHost / ChatChrome / ChatSessionPager / ChatMessageList body 未抽**（保守首轮）——freeze 容忍多步抽取。ChatDrawerHost 是结构性包裹（ModalNavigationDrawer→content slot），无渲染测试时风险较高，故暂缓。
- **ChatScaffold 无渲染测试**——T4 验证间隙：靠参数透传语义核对 + rev-gpt。建议后续加 ChatScaffold 渲染 smoke 测试再深抽。

### T6 / release gate
- **`check.sh --full`（lint）失败：2 个预存 MissingTranslation**（`settings_debug_card_identity_title`/`summary`，commit `fb9c4ac`「卡片身份叠层」debug 功能引入，在重构起点 `572c0e1` 已存在）。**与重构无关**（4 个重构 commit 全没碰 strings.xml）。重构本身 check.sh 全绿 + lint 零引入错误。**建议下次发版前补译这 2 个 zh 字符串**（`values-zh/strings.xml`）。
- **构造瘦身 deferred**：重构未引入 ctor 膨胀（SSC 参数名已恢复、OCR ctor 不变、ChatOverlayHost 是 leaf composable 参数固有）。无明确瘦身目标，作可选后续。

### T1 deferred（前序会话遗留，仍 open）
partExpandStates/refreshNonce solo write；HostProfile non-target；isLoading/pending\* solo；fp TOCTOU；Exhausted→Failed 覆盖；SSC multi-field SSE helpers；title-refresh sessions+dir；ConnectionActions cold-start seed；slim cold-start snapshot；SessionUpserted KDoc 例外；P1-2/P1-3 无直接 legacy 集成测；cold-start double-guard；mutate\* internal（→T5 未做）；R3 无 wire（等 slimapi Batch4）。

---

## 5. 下一步建议

1. **发版前必做**：补译 `settings_debug_card_identity_title`/`summary` 两个 zh 字符串（解 `check.sh --full` lint；属 `fb9c4ac` 遗留，非本次重构）。
2. **可选后续**：
   - T4 二轮：抽 ChatDrawerHost/ChatChrome/ChatSessionPager（先加 ChatScaffold 渲染 smoke 测试更稳）。
   - T3-B：搬 slim HTTP 全簇进 SlimSseStateMachine。
   - T1 deferred 项逐个收口。
3. WeCom 已发本轮完成通知。
