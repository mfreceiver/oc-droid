# ocdroid Backlog 执行方案（W1 / W2 / W3 三批次）

> 编制日期：2026-07-31 ｜ 基线 `main@1149a0e` ｜ bundle `B-ocdroid-sm-20260730`
> **本文件是后续执行 main 的规格依据**：file:line + 改法 + 风险 + 验证 + 写域 + 执行顺序。
> 全部 file:line 经 explorer（ses_0497505a, ses_04974df1, ses_04974bcc）核实 + orchestrator 抽样回读。
> 方案 v3（`docs/2026-07-30-ocdroid-state-machine-improvement-plan.md`）为**意图来源**；落地差异以本文件核实结果为准。

---

## §0 TL;DR

**10 项（去掉 reduce-motion 无障碍的 13 项 backlog 中的 W1/W2/W3）**：

| 批次 | 项数 | 写域 | 串/并行 | 风险 |
|---|---|---|---|---|
| **W1** 防回归补全 | 3 | spec §8（doc）+ AuthorityReducerTest + AuthorityReducer/AuthorityState | W1 内部独立；与 W2 **状态机写域重叠 → 必须串行（W1→W2）** | 低-中 |
| **W2** P1 架构深化 | 4 | StatusAggregatorImpl / AuthorityOp / AuthorityReducer / AuthorityState / StatusPollOrchestrator | W2 四项内部**有 AuthorityReducer/State 共享写域** → 建议**串行内部子序列**（见 §4） | 中（核心） |
| **W3** 体验+债 | 2 | 测试文件（flaky）+ UI/compose 文件（P2 候选） | W3 独立于状态机 → **可与 W1/W2 并行** | 低 |

**执行顺序（omni 已定，本文件细化依据）**：

```
W1（串行子序列）→ W2（串行子序列）     ── 状态机写域重叠
W3（flaky 优先）                          ── 独立写域，可并行
              ↓
        worktree 清理
              ↓
   发版 v0.18.3 (patch) + push
```

**flaky 结论（#10）**：两个 flaky 测试（`SkeletonReloadCoordinatorCoreTest` ABA / `ConnectionCoordinatorConcurrentTest` 全部）**为刻意非确定性并发测试**——用真实线程（`Dispatchers.Default` + `CountDownLatch` + `Thread.sleep` + `CyclicBarrier`）暴露单线程 `TestDispatcher` 无法覆盖的竞态。SUT **不可注入 dispatcher**（`SkeletonReloadCoordinator`/`ConnectionCoordinator` 均无 `CoroutineDispatcher` 参数）。**裁决：STABILIZE-only**（`@Retry` / 增加 sleep），**非本轮真正修复**。详见 §3.

**P2 范围结论（#9）**：方案 v3 §9 阶段12 未列具体项。explorer 侦察出 4 个 MUST 候选（SSE fallback UX / FilesViewModel scope 泄漏 / SessionSyncCoordinator dispatcher 注入 / accessibility contentDescription 缺失）+ 3 个 NICE。**最强候选 = P2-1 SSE fallback UX**（3 个 `§sse-rest-fallback (TODO 2/3/4)` 跨 3 文件）。**本批次 W3 仅做 flaky stabilize**；P2 具体项**待用户明确**（SSE fallback / a11y / scope 泄漏 至少一项），因为 P2 不可盲做。详见 §3.

---

## §1 W1 · 防回归补全（3 项）

### W1-1 · 更新 spec §8.1（BLK-2 已闭合）

| 字段 | 值 |
|---|---|
| **现状 file:line** | `docs/specs/state-machine-architecture-spec.md:376-386`（§8.1 正文）+ `:412`（§8.5 P1 backlog 条目） |
| **写域** | 仅此一个 doc 文件。**零代码写域**。 |
| **依赖** | 无。**独立**。 |
| **风险** | 极低（doc-only）。 |

**现状（过时文本，第 376-386 行）**：

```
376: ### 8.1 BLK-2：`prev.serverRound == null` 时 lex guard 跳过
377:
378: **代码**：`AuthorityReducer.kt:245` —— `if (op.serverRound != null && prev?.serverRound != null)`。`prev.serverRound == null` 时整个 lex 块**跳过**。
...
382: slimapi 1.0.1 已发字段，lex guard 现已激活（原「未发 turn → 此路径休眠」状态已过期）。
...
386: **彻底闭合**（超出 doc-only scope）：需改 ocdroid reducer（... 对基线清空后的首个 Tier-1 帧记 high-turn 水位）。列为 P1 backlog。
```

```
412: - **P1**：BLK-2 彻底闭合（reducer 改）。
```

**改法**：将 §8.1（376-386）整段重写为「BLK-2 已闭合」状态，引用 `serverRoundHighWater` 机制 + commit `e7549e0`；删除 §8.5 第 412 行的「P1：BLK-2 彻底闭合」条目。新文本要点（忠实于代码现状，见 `docs/2026-07-31-oc-slimapi-coordination-response.md` §4.2）：

- §8.1 标题改为「BLK-2：基线清空后的低 turn 复活窗口（**已于 `e7549e0` 闭合**）」
- 描述 `SessionEntry.serverRoundHighWater: ServerRound?`（`data/state/AuthorityState.kt:75-83`）—— per-sid 持久字典序最大 `(incarnation, turn)`，**三类基线清空都保留**（REST `ApplySnapshot` `:491-500` / legacy SSE busy keepRound=null / incarnation-advance scope reset `:376-382`）
- 引用 BLK-2 守卫（`AuthorityReducer.kt:279-284`）：`op.serverRound < prev.serverRoundHighWater` → DROP（严格低 turn）
- **诚实记录残留**：equal-turn（`==`）**未 DROP**（守卫仅 `<`），因 equal-turn 的 `connectionMonotonicMs`/`updatedMonotonic` tie-break 跨 REST/SSE 时钟域语义未验证（见 W1-3）。主复活向量（严格低 turn）已闭合；equal-turn 窗口为已知残留，列入 §8.5「待 clock-domain 验证后加固」。
- §8.5 删除「P1：BLK-2 彻底闭合」；保留其余（C5 header / 持久化 / detekt / REST turn）。

**验证**：doc-only，无需 `check.sh`（本批次整体 gate 仍跑一次确认无意外触达代码）。review 门槛：人工核对文本与 `e7549e0` 代码一致。

---

### W1-2 · 补「连接失败=hole」显式单测

| 字段 | 值 |
|---|---|
| **现状 file:line** | `app/src/test/java/cn/vectory/ocdroid/ui/AuthorityReducerTest.kt`（2167 行）；现有 turn 测试在 `:396-453`（lex DROP / inc advance）+ `:464-679`（7 个 BLK-2 测试）+ `:1013-1050`（equal-turn tie-break） |
| **写域** | 仅 `AuthorityReducerTest.kt`（追加新测试方法）。**无 main 代码改动**。 |
| **依赖** | 无。**独立**。 |
| **风险** | 低（纯测试追加；既有夹具充分）。 |

**现状**：现有测试覆盖严格单调（旧 turn DROP）、相等 turn tie-break、BLK-2 基线清空。**无任何测试覆盖 turn 序列中的「hole」（间隙）**——即 turn `1,2,3,5` 应用正确，turn `4` 因连接失败从未到达。这是 S2 hole 放宽（slimapi 契约 §9）的回归保护：确认 hole 不破坏 lex 单调推进。

**改法**：在 `AuthorityReducerTest` 中（建议插入位置：第 453 行之后，BLK-2 测试段之前；或 396 行 lex DROP 段之后）追加新测试：

```kotlin
@Test
fun `turn sequence with a hole - turns 1,2,3,5 applied, turn 4 lost to connection failure never seen`() {
    val store = storeWith(listOf(Session(id = "s1", directory = "/w")))
    // Apply turns 1,2,3 → busy (each strictly newer, lex advances)
    listOf(1L, 2L, 3L).forEachIndexed { _, turn ->
        store.dispatch(event("s1", SessionStatus(type = "busy"), EntryOrigin.SSE_SLIM,
            serverRound = ServerRound(1L, turn), monotonic = turn * 100L))
    }
    // Turn 4 was LOST (connection failure) — never dispatched
    // Apply turn 5 → idle: must be ACCEPTED (5 > 3, lex-strict advance despite the hole)
    store.dispatch(event("s1", SessionStatus(type = "idle"), EntryOrigin.SSE_SLIM,
        serverRound = ServerRound(1L, 5L), monotonic = 500L))
    assertEquals(SessionStatus(type = "idle"),
        store.stateFlow.value.sessionList.sessionStatuses["s1"])
    assertEquals(ServerRound(1L, 5L),
        store.stateFlow.value.authority.bySid["s1"]?.serverRound)
}
```

**夹具复用**（均已存在）：`event()` 辅助（`:42-59`）、`storeWith()`（`:84-99`）、`ServerRound(incarnation,turn)` 数据类、`scope`（`:36`）。

**验证**：`./scripts/check.sh`（testDebugUnitTest 通过）；测试断言 turn 5 idle 被接受、serverRound 推进到 5。回应文档建议：此测试可给 slimapi 团队作为 hole 语义参考。

---

### W1-3 · BLK-2 equal-turn 残留（clock-domain 核实 + 决策）

| 字段 | 值 |
|---|---|
| **现状 file:line** | `AuthorityReducer.kt:271-278`（KNOWN RESIDUAL 注释）+ `:279-284`（守卫，仅 `<` DROP）；`AuthorityState.kt:75-83`（highWater 字段） |
| **写域** | 若可证 → `AuthorityReducer.kt`（守卫加 `==0` tie-break）+ `AuthorityReducerTest.kt`（补 equal-turn-after-clear 测试）；若不可证 → 仅 `AuthorityReducer.kt`（强化注释）+ spec §8.1（已在 W1-1 记录残留）。**与 W1-1 共享 spec 写域，与 W1-2 共享 test 写域**。 |
| **依赖** | W1-1（spec 文本先定残留基调）。 |
| **风险** | 低（守卫仅可收紧，fail-closed）。 |

**现状**：BLK-2 守卫（`:279-284`）`op.serverRound < prev.serverRoundHighWater` → DROP。**equal-turn（`==`）接受**（重新建立基线）。live lex guard（`:253`）有 `==0` monotonic tie-break，但 BLK-2 守卫**未镜像**——注释（`:271-278`）明确：「其有效性依赖 `connectionMonotonicMs`/`updatedMonotonic` 跨 REST/SSE 时钟域语义（未验证）」。

**explorer clock-domain 调查结论（已核实）**：

| 路径 | `updatedMonotonic` 来源 | 时钟域 |
|---|---|---|
| SSE `applyEvent`（`AuthorityReducer.kt:362`） | `op.connectionMonotonicMs` | 单调钟（`SystemClock.uptimeMillis()` 类） |
| REST `applySnapshot`（`AuthorityReducer.kt:497`） | `op.requestToken.requestStartMs` | **墙上时钟**（`System.currentTimeMillis()` 类） |

- live lex guard（`:253`）两侧均在 SSE 域 → tie-break 有效。
- BLK-2 场景：`updatedMonotonic` 可能由 REST 快照设（墙上钟），incoming `connectionMonotonicMs` 是单调钟 → **跨时钟域比较无意义**（设备休眠/时钟调整会乱序）。

**裁决（rev-glm 待审）**：equal-turn tie-break **不可安全镜像**（跨时钟域）。主复活向量（严格低 turn）已闭合；equal-turn 残留**文档化**，不本轮修。

**改法（文档化路径，推荐）**：
1. `AuthorityReducer.kt:271-278` KNOWN RESIDUAL 注释已充分——保留，可选微调措辞引用本方案 §1.W1-3。
2. spec §8.1（W1-1 重写时）记录 equal-turn 残留 + clock-domain 根因 + 「待统一时钟源（如全路径用 monotonic）后再镜像 tie-break」演进方向。
3. **不改守卫代码**（避免引入跨时钟域比较的潜在乱序 bug）。

**改法（若 rev-glm 认为必须真正修，备选高代价路径，不推荐本轮）**：统一 `requestStartMs` 也用 monotonic 钟（`SystemClock.uptimeMillis()`）→ 两域同源 → 安全镜像 `==0` tie-break。但这触及 `RequestToken` 构造点（多处 REST 调用），**写域扩散 + 回归面大**，超出 W1 防回归补全范围。**建议留 P1/P2**。

**验证**：文档化路径 → `check.sh` 确认无意外触达；真正修路径 → 补 equal-turn-after-clear 测试 + clock-domain 单测。

---

## §2 W2 · P1 架构深化（4 项，方案 v3 §9 阶段11）

> **W2 写域总览**：`StatusAggregatorImpl.kt`（P1-A/C/B-E）/ `AuthorityOp.kt`（P1-C/B-E）/ `AuthorityReducer.kt`（P1-C/B-E）/ `AuthorityState.kt`（P1-C/B-E）/ `StatusPollOrchestrator.kt`（P1-D）。
> **共享写域**：`AuthorityReducer.kt` + `AuthorityState.kt` + `AuthorityOp.kt` 被 P1-C 和 P1-B/E **同时写** → 两者**不可并行，须串行**。P1-A（仅 aggregator）、P1-D（仅 orchestrator）写域较独立。详见 §4.

### W2-1 · P1-A aggregator Aggregate 内部缓存消除

| 字段 | 值 |
|---|---|
| **现状 file:line** | `app/src/main/java/cn/vectory/ocdroid/service/status/StatusAggregatorImpl.kt:164`（`aggregate` AtomicReference）+ `:172`（`commitPublishLock`）+ `:174-176`（`_globalState`/`_globalBusy`/`_statusByKey` MutableStateFlow）+ `:201`（`lastPublishedRevision`）；**唯一写点** `:329`（`aggregate.set(agg)` 在 `publishFromState` 内 under lock）；**唯一直读点** `:343`（`project(aggregate.get(), clock())` 在 `stateAtNow`） |
| **写域** | 仅 `StatusAggregatorImpl.kt`。 |
| **依赖** | 无（P0-A 已完成，authority 切片已就位）。**可与 P1-D 并行**（写域不交）。 |
| **风险** | 中（核心聚合器；但写/读点极简，影响面可控）。 |

**现状**：`aggregate` AtomicReference 是 `authorityToAggregate()`（`:267`）的派生缓存——从 `store.state.authority` 投影成 `Aggregate` 形状，再跑 `publishLocked` → `project()` + `rescheduleFreshnessLocked`。缓存存在仅为：(1) `stateAtNow()`（`:343`）同步读最新派生 verdict（B4-b 契约）；(2) `freshnessJob` 在同 authority revision 下重算 TTL 不必从头派生。

**`publishFromState` 调用点**（aggregator 内 5 处）：`:222`（init collect）/ `:435`（refresh）/ `:471`（applySseStatus）/ `:518`（markRequestFailed）/ `:635`（freshnessJob）。三个 `_globalState`/`_globalBusy`/`_statusByKey` 是 `publishLocked` 的**输出投影**，非缓存消费者。

**改法（方案 v3 §2.3 + §9 阶段11）**：
1. 删除 `aggregate`（`:164`）、`commitPublishLock`（`:172`）、`lastPublishedRevision`（`:201`）、`init` collect（`:217-224`）、`publishFromState`（或保留为纯派生函数）。
2. 将 `_globalState`/`_globalBusy`/`_statusByKey` 改为 `DerivedStateFlow(store.state)` —— selector 直接 `project()` over `it.authority`。
3. 保留 `publishLocked`/`project`/`rescheduleFreshnessLocked` 作为派生函数。
4. `stateAtNow()` 改读 `_globalState.value`（DerivedStateFlow.value 同步，等价 AtomicReference.get()）。
5. **freshnessJob 同-revision TTL 重算**（关键陷阱：DerivedStateFlow 仅在 authority 变化时 emit，不因 TTL 过期 emit）→ `freshnessJob` 仍调用 `publishFromState`（重派生）或在 DerivedStateFlow 依赖中纳入 `clock()`。**建议前者**（freshnessJob 保留，见 W2-2 P1-C 一并改造，消除其旁路）。

**验证**：`check.sh` 全过；新增/现有 aggregator 单测覆盖：(a) dispatch 后 `_globalState.value` 立即可见（B4-b 同步）；(b) freshnessJob TTL 翻转仍触发；(c) 无 commitPublishLock 后无 R5（旧快照先发）回归。场景 §8 #20（aggregator 6 写点全 dispatch + 同步读立即可见）。

---

### W2-2 · P1-C freshness → FreshnessTick action

| 字段 | 值 |
|---|---|
| **现状 file:line** | `StatusAggregatorImpl.kt:187`（`freshnessJob` 字段）+ `:621-641`（launch 在 `rescheduleFreshnessLocked`）+ `:604-642`（整体逻辑）；`AuthorityState.kt:158-162`（`Freshness` enum）+ `:65`（`SessionEntry.freshness` 字段）；`AppAction.kt:814-816`（`AuthorityEvent`）+ `:898`（dispatch）；`AuthorityOp.kt:17`（`AuthorityOp` sealed）；**freshness 双写点**：`AuthorityReducer.kt:361`（`applyEvent` 设 `freshness=Fresh`）+ `:496`（`applySnapshot` REST 分支设 `freshness=Fresh`） |
| **写域** | `AuthorityOp.kt`（新增 variant）+ `AuthorityReducer.kt`（新增 branch）+ `StatusAggregatorImpl.kt`（freshnessJob 改 dispatch）+ 可能 `AuthorityState.kt`。**与 P1-B/E 共享 AuthorityOp/Reducer/State 写域 → 须串行**。 |
| **依赖** | P1-A（若 freshnessJob 改造借 P1-A 的派生函数）。建议 **P1-A → P1-C 串行**（条件依赖：仅当 P1-A 删除 `publishFromState` 时为硬依赖；若 P1-A 保留 `publishFromState` 为纯派生函数则 P1-C 可不依赖 P1-A——见 §4 注）。 |
| **风险** | 中（freshness 字段当前 write-once-Fresh，aging 是新行为）。 |

**现状**：`freshnessJob`（`:621-641`）是**旁路**——不 dispatch、不碰 `AuthorityState`，仅在 `rescheduleFreshnessLocked` 内 delay → `publishFromState` 重派生 Aggregate（含 TTL 翻转）。`SessionEntry.freshness`（`AuthorityState.kt:65`）有**两个写点**，均设 `Freshness.Fresh`：`applyEvent:361`（SSE 路径）+ `applySnapshot:496`（REST 路径）；**从不 aged 到 Stale/Unknown**（全代码 `freshness = Freshness.(Stale|Unknown)` 零匹配，write-once）。

**改法（方案 v3 §2.3 建议项）**：
1. `AuthorityOp.kt:17` 新增 `data class FreshnessTick(val scopeKey: ScopeKey, val nowMonotonic: Long): AuthorityOp`。
2. `AuthorityReducer.kt:45`（`reduceAuthority`）新增 `is AuthorityOp.FreshnessTick -> applyFreshnessTick(cur, op)` branch；`applyFreshnessTick` 遍历 `bySid`，对 `nowMonotonic - updatedMonotonic > TTL` 的 entry 设 `freshness = Stale`/`Unknown`。
3. `StatusAggregatorImpl.kt:621` freshnessJob：`delay(delayMs)` 后改 `store.dispatch(AppAction.AuthorityEvent(AuthorityOp.FreshnessTick(scope, clock())))` 替代 `publishFromState`。
4. aggregator `init` collect（`:217`）自动捕获 freshness 变化（authorityRevision bump → 重派生）。

**关键注意**：
- `project()`（`:556`）TTL verdict 用 `sourceTimeMs`，**非** `freshness` 字段 → `freshness` aging 纯粹是 authority 状态簿记，**不改变 aggregator TTL 输出**（plan 明确：「reducer updates entry.freshness 作为簿记，非 TTL 源」）。需 rev-glm 确认无 reader 依赖 `freshness` 恒 Fresh。
- 若 P1-A 已移除 `publishFromState`，freshnessJob 必须改 dispatch（否则无派生触发）→ **强化 P1-A→P1-C 串行依赖**。

**验证**：`check.sh`；单测：(a) TTL 到期 dispatch FreshnessTick → entry.freshness 翻 Stale；(b) aggregator `_globalState` 跟随 freshness 变化重派生；(c) 无新 event 时 freshnessJob 持续 arm/触发。场景 §8 相关。

---

### W2-3 · P1-D statusLoadEpoch ↔ completenessEpoch 关联（R11）

| 字段 | 值 |
|---|---|
| **现状 file:line** | `StatusPollOrchestrator.kt:38`（`statusLoadEpoch` 定义 AtomicLong）+ `:144`（bump `incrementAndGet`）+ `:201`（legacy guard `myEpoch != statusLoadEpoch.get()`）+ `:411`（slim guard）；`AppStateSlices.kt:792`（`completenessEpoch` 定义）；completeness bump 点：`BackgroundUnreadPoller.kt:226`/`SessionListFieldsReducer.kt:152,166`/`CrossSliceFieldsReducer.kt:184,357`/`SseSessionListReducers.kt:232,237` |
| **写域** | 仅 `StatusPollOrchestrator.kt`。**最独立的一项**。 |
| **依赖** | 无。**可与 P1-A 并行**（写域不交）。 |
| **风险** | 低（守卫仅可收紧，fail-closed；模式成熟——BackgroundUnreadPoller 已这么做）。 |

**R11 缺口**（survey :194/:319）：两 epoch 独立推进。REST 轮询用 `statusLoadEpoch`（进程内 single-flight），目录树 hydrate 用 `completenessEpoch`（store 切片）。场景：BackgroundUnreadPoller 起 poll 捕获 `startEpoch`（`BackgroundUnreadPoller.kt:185` mutateState 内 guard 检测 `completenessEpoch != startEpoch` → 正确 abort）；**但 `StatusPollOrchestrator` 用独立的 `statusLoadEpoch`，无关联**——一个 session 列表变化前发起、变化后返回的 REST poll 会过 `statusLoadEpoch` guard（没人 bump 它）并把 stale status 应用到已变化的 session 列表。

**改法（方案 v3 §9 阶段11 + R11 + §4a.1 已示范）**：
1. `StatusPollOrchestrator.kt:144`（bump 后）捕获 `completenessEpochAtStart = slices.sessionList.value.completenessEpoch`。
2. `:201`（legacy guard）+ `:411`（slim guard）各加一行：`|| snapshot.sessionList.completenessEpoch != completenessEpochAtStart`。

这是**每 guard 一行**的加固。`completenessEpoch` 已被其他 caller（BackgroundUnreadPoller/SessionTreeHydrator）捕获——模式成熟。

**验证**：`check.sh`；单测：构造「poll 中途 completenessEpoch bump」→ guard 拒绝 apply（返回 aborted/丢弃）。场景 R11。

---

### W2-4 · P1-B/E retry action 模型 + 重排队列深化

| 字段 | 值 |
|---|---|
| **现状 file:line** | `SessionBusyStatus.kt:19-20`（`Retry` enum 值）/ `Session.kt:99`（`isRetry`）/ `SessionBusyStatusMapping.kt:42`（Retry 映射）/ `SlimStatusFanOut.kt:170-184,226,240`（`retryableCount` 传输失败计数）/ `StatusAggregatorImpl.kt:540-541,566-568`（project 把 Retry 当 Busy）；**无任何 retryQueue/requeue/pendingRetry 结构**（全代码搜空） |
| **写域** | `AuthorityOp.kt`（新 ops）+ `AuthorityState.kt`（新 queue 字段）+ `AuthorityReducer.kt`（新 branches）+ 可能 `StatusAggregatorImpl.kt`（project + retryQueueFlow）。**与 P1-C 共享 AuthorityOp/Reducer/State → 须串行**。 |
| **依赖** | P1-C（共享写域，建议 P1-C 先）。 |
| **风险** | 中（新增结构；须保证 queue 有界 + terminal 清理）。 |

**现状**：Retry 仅作为 `SessionBusyStatus` 的一个 enum 值，project 当 Busy 处理（`anyBusy=true`）。`SlimStatusFanOut` 计 `retryableCount`（传输失败 sid 数）用于 poller backoff。**无结构化 retry 队列、无 attempt 计数、无 backoff 元数据、无 Flow 暴露**。可观测性近乎为零。

**改法（方案 v3 §9 阶段11）**：
1. `AuthorityOp.kt:17` 新增：`data class RetryQueued(val sid: String, val scopeKey: ScopeKey, val attempt: Int, val monotonic: Long): AuthorityOp` + `data class RetryFired(val sid: String, val scopeKey: ScopeKey, val monotonic: Long): AuthorityOp`。
2. `AuthorityState.kt` 新增 `retryQueue: Map<String, RetryEntry>` 字段（`RetryEntry` 含 attempt/backoffMs/queuedMonotonic）。
3. `AuthorityReducer.kt:45` 新增 `applyRetryQueued`（入队 + status=Retry）+ `applyRetryFired`（queued → in-flight）branches。
4. `StatusAggregatorImpl.kt` project 不变（Retry 已当 Busy）；可选新增 `retryQueueFlow: StateFlow<Map<String, RetryEntry>>` 暴露队列深度供 UI/可观测性。

**关键注意**：queue 必须**有界**（LRU/size cap，防 unbounded 增长）；terminal status（idle/failed）时清理对应 retry entry。

**验证**：`check.sh`；单测：(a) RetryQueued 入队 + status=Retry；(b) RetryFired 出队；(c) terminal 清理；(d) queue 有界。场景 §8 #29（retry action UI）。

---

## §3 W3 · 体验 + 债（2 项）

### W3-1 · flaky 测试修复（#10）

| 字段 | 值 |
|---|---|
| **现状 file:line** | `SkeletonReloadCoordinatorCoreTest.kt`（ABA 测试 `:687-817`；`Dispatchers.Default` `:688`；`CountDownLatch` `:691-693,735,789`；`Thread.sleep` `:753,775`）；`ConnectionCoordinatorConcurrentTest.kt`（全部测试 `:61+`；`Dispatchers.Default` ×13 处 `:114,192,251,...`；`CountDownLatch` ×14+；`Thread.sleep` ×14+ `:158,216,226,...`；`CyclicBarrier` `:129`；real `Thread` `:132-141,801-806`） |
| **SUT file:line** | `MessageActions.kt:1060`（`SkeletonReloadCoordinator`，无 dispatcher 参数，取 `scope: CoroutineScope`）；`ConnectionCoordinator.kt:111`（`ConnectionCoordinator`，无 dispatcher 参数，取 `scope: CoroutineScope`） |
| **写域** | 仅这两个测试文件（+ 可能 `@Retry` 注解引入）。**无 main 代码改动**（本轮）。 |
| **依赖** | 无。**完全独立，可与 W1/W2 并行**。 |
| **风险** | 低。 |

**flaky 根因（explorer 已定位，刻意非确定性）**：两个测试**设计上**用真实线程暴露单线程 `TestDispatcher` 无法覆盖的竞态——`ConnectionCoordinatorConcurrentTest` docstring（`:42-43`）明言「Uses real threads + CountDownLatch to expose races that single-threaded dispatchers cannot reproduce」。`SkeletonReloadCoordinatorCoreTest` 的 ABA 测试（`:687`）同理。

**SUT 可注入性裁决**：两个 SUT **均无 `CoroutineDispatcher` 参数**（`SkeletonReloadCoordinator(scope)` / `ConnectionCoordinator(scope)`，取 scope 的 dispatcher）。真正确定性改造需 **main 代码重构**（暴露 dispatcher 注入点）——**超出本轮 W3「flaky 修复」范围**，且会改变 SUT 语义（注入 TestDispatcher 后将无法覆盖这些竞态）。

**裁决：STABILIZE-only（rev-glm APPROVED-after-fixup，路径已核实）**：

> **JUnit 版本已核实**（`app/build.gradle.kts:456` `testImplementation(libs.junit)` + `:473` `androidx.compose.ui.test.junit4`）——项目用 **JUnit4**，无 jupiter/JUnit5/retry 扩展依赖。故 `@Retry` 原生**不可用**。

1. **首选：增加 `Thread.sleep` 时长**（如 500→1000ms，2000→3000ms）——降低时序敏感，JUnit4 原生可用，零依赖。
2. **次选：引入 retry 扩展**（如 `@Retry` 需 JUnit5 迁移或第三方 JUnit4 retry rule）——成本较高，本轮**不引入**。
3. **保底：`@Ignore` + 注释标注「已知非确定性，手动验证」**——最弱，仅当 sleep 增加仍频繁 flaky 时降级。

**不推荐**：改 SUT 注入 TestDispatcher——破坏测试目的（覆盖竞态），且写域扩散到 main 代码。

**验证**：`check.sh --lint` 多跑几次确认 stabilize 生效；不引入新失败。

---

### W3-2 · P2 体验/硬化（#9）— 范围厘清

| 字段 | 值 |
|---|---|
| **现状** | 方案 v3 §9 阶段12 未列具体项。explorer 侦察候选（见下）。 |
| **写域** | 取决于用户选定项。 |
| **依赖** | 无（独立于状态机）。 |
| **风险** | 低-中（视项）。 |

**explorer 候选清单（file:line 已核实）**：

**MUST（明确证据）**：
| # | 候选 | 证据 | file:line |
|---|---|---|---|
| P2-1 | **SSE fallback UX**（stale-notice snackbar + auto cold-start + terminal 检测） | `§sse-rest-fallback (TODO 2)` + `(TODO 3)`（**无 TODO 4**——原 explorer 报告混淆了 `§force-refresh-guard` 系列） | `ChatViewModel.kt:536`(TODO 2), `AppCoreOrchestration.kt:1098,1111`(TODO 3), `SharedStateStore.kt:258,444`(TODO 3), `AppStateSlices.kt:189`(TODO 3)；含 TODO 标记的 **3 个 main 文件**（ChatViewModel/AppCoreOrchestration/SharedStateStore，AppStateSlices 为字段注释） |
| P2-2 | FilesViewModel scope 泄漏（跨图状态共享） | `FIXME(P4-features)` | `AppShell.kt:80` |
| P2-3 | SessionSyncCoordinator dispatcher 注入（硬编码 `Dispatchers.Default` 阻碍确定性测试） | `internal val reconcileDispatcher = Dispatchers.Default` | `SessionSyncCoordinator.kt:213` |
| P2-4 | accessibility：交互元素 `contentDescription` 缺失 | 10+ 处 `contentDescription = null` on interactive controls | `WorkdirControl.kt:100,132,201,233,242`, `SessionsScreen.kt:758,944`, `SessionCard.kt:141`, `ChangesPane.kt:358`, `DirectoryPicker.kt:143,249` |

**NICE-TO-HAVE（投机/次要）**：
| # | 候选 | 证据 | file:line |
|---|---|---|---|
| P2-5 | OrchestratorViewModel 迁 `lastRoute` | `TODO: migrate to lastRoute` | `OrchestratorViewModel.kt:89` |
| P2-6 | Settings 缓存大小指示器 | remove-message-persistence Task 5 `TODO` | `SettingsSections.kt:319` |
| P2-7 | 系统性 error/empty/loading 状态审计 | 无 `TODO.*(error\|empty\|loading)` 匹配（缺证据即缺口） | 整个 `app/src/main/java` |

**裁决**：P2 具体项**待用户明确**。最强候选 = **P2-1 SSE fallback UX**（用户面影响最大：SSE 断流时缺反馈；3 个 TODO 跨 3 文件）。若用户只选一项 → P2-1。若用户要 accessibility → P2-4。**本轮 W3 仅做 flaky（W3-1）**；P2 等 omni/用户拍板具体项后另起批次。

**P2 实施（待选定项后）遵循** `docs/specs/ui-style-spec.md` 三层 overlay 规则 + `ui/theme/` 共享原语。

---

## §4 写域冲突矩阵 + 执行顺序

### 写域矩阵

| 项 | spec §8 | AuthorityReducerTest | AuthorityReducer | AuthorityState | AuthorityOp | StatusAggregatorImpl | StatusPollOrchestrator | 测试文件(flaky) | UI/compose |
|---|---|---|---|---|---|---|---|---|---|
| W1-1 | ✍ | | | | | | | | |
| W1-2 | | ✍ | | | | | | | |
| W1-3 | (✍ 注释) | (✍ 若修) | (✍ 若修) | | | | | | |
| W2-1 (P1-A) | | | | | | ✍ | | | |
| W2-2 (P1-C) | | | ✍ | ✍ | ✍ | ✍ | | | |
| W2-3 (P1-D) | | | | | | | ✍ | | |
| W2-4 (P1-B/E) | | | ✍ | ✍ | ✍ | (✍) | | | |
| W3-1 (flaky) | | | | | | | | ✍ | |
| W3-2 (P2) | | | | | | | | | (✍ 视项) |

### 冲突分析 + 执行顺序

**W1 内部**：W1-1（spec）/ W1-2（test）/ W1-3（reducer+test+spec）—— W1-3 与 W1-1/W1-2 轻微交叠（spec 注释 / test 若真正修）。**建议顺序**：W1-1 → W1-2 → W1-3（文档化路径则 W1-3 仅注释微调，无冲突）。

**W2 内部**：
- P1-A（仅 aggregator）✅ 独立。
- P1-D（仅 orchestrator）✅ 独立。
- P1-C（AuthorityOp + Reducer + State + aggregator）与 P1-B/E（AuthorityOp + State + Reducer + aggregator）**共享 AuthorityOp/Reducer/State 写域 → 必须串行**。
- **建议顺序**：P1-A → P1-C → P1-B/E（P1-C 先建 FreshnessTick op 模式，P1-B/E 复用）；P1-D 可在任意点并行。

**W1 ↔ W2**：W1-3（若真正修）与 W2 全部共享 `AuthorityReducer` 写域 → **W1 → W2 串行**（omni 已定）。

**W3**：flaky（测试文件）+ P2（UI）**完全独立于状态机写域** → **可与 W1/W2 并行**。

**最终执行图**：
```
[W1-1 → W1-2 → W1-3] ──→ [W2: P1-A → P1-C → P1-B/E; P1-D 可在任意点并行]   (状态机串行链)
[W3-1 flaky]                                                          (并行, 独立写域)
[W3-2 P2]  (待用户定项, 可后置或与上述并行)
```

> **依赖说明**（rev-glm 建议明确）：
> - **W1 → W2 串行**：硬依赖（W1-3 若真正修触 AuthorityReducer，与 W2 全部共享写域；文档化路径下保守串行无害）。
> - **P1-A → P1-C 串行**：**条件依赖**，非硬依赖。仅当 P1-A 选择「**删除** `publishFromState`」时为硬依赖（freshnessJob 失去派生触发点，必须改 dispatch → P1-C 先就绪）；若 P1-A 选择「**保留** `publishFromState` 为纯派生函数」（W2-1 改法第 1 点给的选项），则 P1-C 可不依赖 P1-A 并行。**建议**：P1-A 采取保留 `publishFromState` 方案以解除依赖，或在执行 main 规格里显式标注所选路径。
> - **P1-C → P1-B/E 串行**：硬依赖（共享 AuthorityOp/Reducer/State 写域，P1-C 先建 FreshnessTick op 模式供 P1-B/E 复用）。
> - **P1-D 独立**：零交集，任意点并行（含与 P1-A 同时）。

---

## §5 验证策略

| 批次 | gate | 门槛 |
|---|---|---|
| 每项 | `./scripts/check.sh` | 编译 + `testDebugUnitTest` 通过 |
| W1/W2 末 | `./scripts/check.sh --full` | + lint + 覆盖率 |
| W3 末 | `./scripts/check.sh` + flaky 多跑 | stabilize 生效 |
| 整体 | `./scripts/check.sh --lint` EXIT=0 | 发版前 gate |

**场景覆盖**（对应 plan v3 §8）：W1-2 → hole 语义；W2-1 → #20（aggregator 派生同步读）；W2-2 → freshness TTL；W2-3 → R11；W2-4 → #29（retry UI）。

---

## §6 发版规划

| 字段 | 值 |
|---|---|
| 版本 | **v0.18.3 (patch)** |
| versionCode | git 派生（commit count），`release.sh patch` 自动 bump（打 tag） |
| versionName | git describe |
| 入口 | `./scripts/release.sh patch`（唯一入口；打 tag；无手 bump） |
| 时机 | W1→W2 串行 + W3 并行 全部闭合 → worktree 清理 → release |

**不自动 push**（PRE_AUTH_SCOPE：merge 到 main 后报 omni 统一窗口）。

---

## 附录 · 核实来源

- exp-1 (ses_0497505a)：W1（spec §8.1 现状 + hole 测试位置 + BLK-2 equal-turn 时钟域）—— orchestrator 回读 spec 376-386 + reducer 259-284 确认。
- exp-2 (ses_04974df1)：W2（aggregator/freshness/epoch/retry 现状）—— orchestrator 回读 aggregator 160-171 + orchestrator 35-38,140-151 确认。
- exp-3 (ses_04974bcc)：W3（flaky 根因 + P2 缺口）。
- 意图来源：`docs/2026-07-30-ocdroid-state-machine-improvement-plan.md`（方案 v3 §2.3/§9/R11）+ `docs/2026-07-31-oc-slimapi-coordination-response.md` §4（BLK-2 闭合）。
