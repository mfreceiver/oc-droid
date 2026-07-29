# ocdroid 会话状态机：结构性权威重构方案

> **状态**：全新体系方案（**未实现**）。基于上游调研（main-A）、现状调研（main-B）、4 个 explorer 结构核对综合产出。原对比文档 `docs/opencode-state-machine-comparison.md` 已作废（将被删除），本方案是全新体系，不与之兼容。
> **证据基线（固定）**：
> - 上游 `opencode-src/v1.18.7/`（**禁用** `current` 软链；上游服务端**不可改**，所有 fence 在 ocdroid 客户端实现）。
> - ocdroid `app/src/main/java/cn/vectory/ocdroid/`。
> - oc-slimapi（`/home/mar/personal_projects/oc-slimapi`，用户自建 Python 中继层）可改——是强 fence 的重要增强路径（跨项目协作，见 §5）。
> **日期**：2026-07-30。**bundle**：B-ocdroid-sm-20260730。
> **协议定位**：ocdroid 消费上游 **V1 线协议**（`session.status` / `session.digest` / `session.error` / `message.part.*` / `GET /session/status`）。V2 执行事件仅作背景。
> **审计**：读 3 份输入材料全文；派 4 个 explorer 核对（exp-1 interrupt/retry 上游链；exp-2 ocdroid 10 代码点；exp-3 SessionListState+UI 读面+store 机制；exp-4 StatusAggregator 构造/注入/消费者）。所有 `文件:行号` 经 explorer 二次确认。
> **方向校正（omni 确认）**：纯客户端改进；oc-slimapi 可辅助强 fence；结构性权威重构（非最小改动）；通过后实施，可执行性是硬要求。

---

## 0. 阅读约定与术语

| 术语 | 含义 |
|---|---|
| **SessionStatusAuthority**（新） | 本方案引入的**单一权威源**。所有 status 写入的唯一入口；内部执行全部护栏；对外投影 UI 态与生命周期态。见 §2。 |
| **执行代际（generation / turn）** | 单调递增标识，标识「一次执行轮次」。**强 fence**（slimapi 路径）由中继层附加 `turn`；**fallback fence**（legacy 路径）由客户端确认门推断。非 wall-clock。 |
| **确认门（confirmation gate）** | legacy 路径的客户端因果 fence：optimistic busy 未被 server echo 确认前，屏蔽 incoming idle。 |
| **投影（projection）** | 从权威源派生的只读视图。UI 投影（sid-keyed `sessionStatuses`）与生命周期投影（`GlobalBusyState`）均从权威源派生，根治双源不同步。 |
| **dual-source（被根治）** | 现状：UI 源 `sessionStatuses`（sid-keyed）与生命周期源 `StatusAggregatorImpl.entries`（composite-key）独立写入、不同步。新体系收敛为单一权威源。 |
| **durable error** | 持久化在 assistant 消息上的 `Message.error`。失败回合的持久真相，跨重启/重载均在。 |

---

## 1. 现状问题诊断（综合 main-A/B + 4 explorer）

### 1.1 根因：双权威源 + 缺执行代际 fence（结构性缺陷）

ocdroid 现状有**两个并行的独立权威视图**，且**同 identity 内缺执行代际 fence**。这是三个用户反馈的共同结构性根因。

**双权威源拓扑**（exp-3/exp-4 核对）：

```
SSE session.status 帧到达
  │
  ├─→ LegacySseHandler.kt:135-154  applySseStatus() → StatusAggregatorImpl.entries   【权威 A：生命周期】
  ├─→ LegacySseHandler.kt:168-170  mutateSessionList(applySessionStatus) → sessionStatuses  【权威 B：UI】
  │   ↑ 两次独立写入，无协调（THE FORK POINT）
  │
Slim digest status
  └─→ SessionSyncCoordinator.kt:988  mutateSessionList → sessionStatuses  【仅权威 B，不喂 A】
  │
Optimistic busy（POST 成功）
  └─→ SessionListFieldsReducer.kt:62-71  sessionStatuses += ...  【仅权威 B，无任何护栏】
  │
REST 轮询
  ├─→ ProcessStatusPoller.runRefresh:461  statusAggregatorInput.refresh() → entries  【权威 A】
  └─→ StatusPollOrchestrator:215/398  mutateSessionList(mergeStatusSnapshot) → sessionStatuses  【权威 B】
```

**后果**：
- 权威 A（聚合器）在 slim 模式下落后权威 B（UI）数秒（slim digest 不喂 A）；`GlobalBusyState` 可能误判 `AllIdleFresh` → 过早关 SSE。
- 权威 B（UI）有 **10 条写入路径**，其中 **path #5（optimistic busy）完全无护栏**（exp-2 确认 `SessionListFieldsReducer.kt:62-71` 裸 `+`）。

**缺执行代际 fence**：现有护栏（`ConnectionIdentityStore` epoch + `statusLoadEpoch` + `mergeStatusSnapshot`）**只防跨 host/跨 epoch**，挡不住**同一连接内**「旧执行轮次的 idle vs 新轮次的 busy/retry」。这是反馈 #1（retry 期 stop 消失）的**直接致命根因**（rev-ogpt 硬伤 #1：比冷启缺口更致命）。

### 1.2 三个用户反馈的根因映射

| 反馈 | 主根因 | 类别 | 对症 |
|---|---|---|---|
| #1 retry/退避期 stop 消失 | 同 identity 内 stale idle 覆盖 optimistic/retry（执行代际错位）+ path #5 无护栏 | **执行代际 fence 缺失** | §2 权威源内建 generation fence（§3）；P0-B/P0-C |
| #2 错误显示不稳定 | durable `Message.error` 恢复缺失 + 两阶段时序（status=idle 先到，error 后到）+ 静默丢弃 + 归档不清 | **错误恢复缺失** | P0-E |
| #3 回页面看不到运行态/错误 | 冷启 reconcile 条件性（archive 早返回 + KeepCurrent-only 门）+ abort 不乐观致 UI 停滞 + 双源不同步 | **冷启缺口 + UI 停滞 + 双源** | P0-D/P0-F + §2 双源根治 |

### 1.3 关键代码事实（exp-2/exp-3/exp-4 核对，2026-07-30 快照）

- `sessionStatuses` 声明：`AppStateSlices.kt:716`（`SessionListState` 类 :714）；非独立 Flow，经 `.value` 快照或 `sessionListFlow` 收集读取（exp-3）。
- store 机制：`SharedStateStore`（`SharedStateStore.kt:54-56`）持 `MutableStateFlow<StoreState>`；`dispatch(AppAction)` → `state.update { reduce(it, action) }`（:250-252）；`AppAction` 是 sealed interface（`AppAction.kt:49`），`reduce` 为 `when`（:815-883）。
- `SessionStatusPatched`（`AppAction.kt:664-668`）→ `reduceSessionStatusPatched`（`SessionListFieldsReducer.kt:62-71`，裸 `+`，无护栏）。
- `sessionStatuses` 写入 reducer 共 3 个：`reduceSessionStatusPatched`（+）、`reduceSessionTreeHydrated`（= 替换 :108）、`reduceHostStatePurged`（清空 CrossSliceFieldsReducer.kt:128）。
- `sessionStatuses` 原始 `mutateSessionList` 写入点：`StatusPollOrchestrator.kt:215/398`、`SessionSyncCoordinator.kt:988`、`SessionTreeHydrator.kt:149`、`BackgroundUnreadPoller.kt:187`。
- `sessionStatuses` 读点 ~25 处（exp-3 列全），均 `.value.sessionStatuses` 快照或纯函数参数。
- 聚合器：`StatusAggregatorImpl`（`StatusAggregatorImpl.kt:96-102`，`@Singleton`，经 `StatusModule.kt:53-74` @Provides+@Binds 注入）；同时实现 `StatusAggregator` + `StatusAggregatorInput`（单实例双接口）。
- 聚合器 `entries` 在 `AtomicReference<Aggregate>`（:181），key=`SessionStatusKey(serverGroupFp, workdir, sessionId)`（`SessionBusyStatus.kt:52-56`），value=`SessionBusyStatus`（`SessionBusyStatus.kt:28-34`，Fresh/Busy/Retry/Idle/Unknown）。
- 聚合器公共 API：`globalState: StateFlow<GlobalBusyState>`（:207）、`globalBusy: StateFlow<Boolean>`（:208）、`statusByKey: StateFlow<Map<SessionStatusKey, SessionBusyStatus>>`（:209）、`stateAtNow()`（:230）、`refresh()`（:257）、`applySseStatus()`（:386）、`markRequestFailed()`（:404）。
- `GlobalBusyState` 消费者：`StreamingLifecycleCoordinator`（主——5 个 transition handler + 45s idle-debounce + handoff，:1620-1658/:837/:881/:1001/:1185-1208/:1470/:1103）、`SessionStreamingController`（busy chronometer :150-159）、`ProcessStatusPoller.startAndAwaitFirstPoll`（:255）。
- FORK POINT：`LegacySseHandler.handleSessionStatus`（:130-169）—— 两次独立写入（applySseStatus + mutateSessionList），无协调。
- `SlimStatusFanOut`（slim on-demand fan-out）**不喂聚合器**；slim digest 进 `SlimSseState`（两者都不喂）。

---

## 2. 新体系架构设计（单一权威源）

### 2.1 设计目标

> 用户明确：权威重构，形成易于管理维护、清晰逻辑的新体系。**不是最小改动。**

- **单一权威源**：所有 status 写入的唯一入口，内部执行全部护栏。
- **双投影派生**：UI 投影（sid-keyed）+ 生命周期投影（`GlobalBusyState`）从同一源派生，**根治双源不同步**。
- **执行代际 fence 内建**：generation（slimapi 强 fence）+ 确认门（legacy fallback），非 wall-clock。
- **写入漏斗**：10 条散落写入路径收敛为 1 个 `submit()` 入口。
- **读面零侵入**：~25 个读点不改（保持 `.value.sessionStatuses` 快照语义），仅写面重构。

### 2.2 核心组件：`SessionStatusAuthority`（新 @Singleton）

```
                 ┌─────────────────────────────────────────────────────┐
                 │         SessionStatusAuthority（单一权威源）          │
                 │  @Singleton；内部 AtomicReference<AuthorityState>     │
                 │                                                     │
写入漏斗（10→1）│  submit(update: StatusUpdate)                         │
                 │    ├─ identity/epoch guard（复用 ConnectionIdentity）│
                 │    ├─ generation fence（slimapi turn 强 / 确认门弱）  │
                 │    ├─ merge-timing（连接时钟 sourceTimeMs）          │
                 │    └─ commit → AuthorityState                        │
                 │                                                     │
双投影（派生）   │  uiStatuses: StateFlow<Map<String, SessionStatus>>    │──→ sessionStatuses（UI）
                 │  lifecycleProjection → 委托 StatusAggregatorImpl      │──→ GlobalBusyState（生命周期）
                 └─────────────────────────────────────────────────────┘
                          ↑                                    ↑
              所有写入路径经 submit()                  聚合器投影由 authority 喂养
```

**`AuthorityState`（内部）**：
```kotlin
data class AuthorityState(
    val bySid: Map<String, SessionEntry>,   // 当前 host scope 内的会话执行态
)
data class SessionEntry(
    val status: SessionStatus,              // idle/busy/retry（三态镜像）
    val generation: Long,                   // 执行代际（slimapi turn 或客户端 bump）
    val optimisticPending: Boolean,         // 确认门：optimistic 是否待 server echo
    val sourceTimeMs: Long,                 // 连接时钟（merge-timing tie-breaker）
    val origin: StatusOrigin,               // OPTIMISTIC/SSE_LEGACY/SSE_SLIM/REST/TREE
    val fresh: Boolean,                     // freshness（TTL 派生）
)
```

**`StatusUpdate`（写入入口参数）**：
```kotlin
data class StatusUpdate(
    val sid: String,
    val status: SessionStatus,
    val origin: StatusOrigin,
    val identity: ConnectionIdentity?,      // 写入时捕获的 identity（identity guard 用）
    val generation: Long? = null,           // slimapi turn（强 fence）；null=走确认门
    val sourceTimeMs: Long,                 // 连接时钟（host.sseClock() / 请求开始时钟）
    val workdir: String? = null,            // 聚合器 composite key 用
)
enum class StatusOrigin { OPTIMISTIC, SSE_LEGACY, SSE_SLIM, REST, TREE }
```

**`submit()` 的 commit 逻辑（全部护栏集中此一处）**：
```kotlin
fun submit(update: StatusUpdate) = synchronized(commitLock) {
    val cur = state.get()
    // ① identity guard（host 切换后过期写丢弃）
    if (update.identity != null && identityStore != null && !identityStore.isCurrent(update.identity)) return
    val prev = cur.bySid[update.sid]
    // ② generation 强 fence（slimapi turn）
    if (update.generation != null && prev != null && update.generation < prev.generation) return  // stale 代际，DROP
    // ③ 确认门 fallback（legacy 路径，无 generation）
    if (update.generation == null && prev?.optimisticPending == true && update.status.isIdle) return  // stale idle，DROP
    // ④ merge-timing（连接时钟 tie-breaker）
    if (prev != null && update.sourceTimeMs < prev.sourceTimeMs && update.generation == null) return  // 乱序，DROP
    // ⑤ 计算 optimisticPending 与 generation
    val newPending = when {
        update.origin == OPTIMISTIC -> true                       // optimistic 写入待确认
        prev?.optimisticPending == true && !update.status.isIdle -> false  // server echo 确认
        update.status.isIdle -> false                             // idle 清 pending（轮次结束）
        else -> prev?.optimisticPending ?: false
    }
    val newGen = when {
        update.generation != null -> update.generation            // slimapi 强
        update.origin == OPTIMISTIC -> (prev?.generation ?: 0L) + 1L  // 客户端 bump
        update.status.isIdle -> (prev?.generation ?: 0L) + 1L     // idle 接受 → bump（轮次边界）
        else -> prev?.generation ?: 0L
    }
    // ⑥ commit
    val entry = SessionEntry(update.status, newGen, newPending, update.sourceTimeMs, update.origin, fresh = true)
    state.set(cur.copy(bySid = cur.bySid + (update.sid to entry)))
    // ⑦ 投影发布 + 喂聚合器
    publishProjections()
    forwardToAggregator(update.sid, update.status, update.sourceTimeMs, update.workdir)
}
```

> **关键**：所有护栏集中此一处。path #5 无护栏、双源 fork、stale-idle 覆盖、host 切换污染、乱序覆盖——**全部在此根治**。

### 2.3 双投影收敛（根治 dual-source）

**UI 投影**（替换 `sessionStatuses`）：
- authority 发布 `AppAction.SessionStatusProjectionUpdated(projection: Map<String, SessionStatus>)`（投影 = `bySid.mapValues { it.value.status }`）。
- **单一** reducer `reduceSessionStatusProjectionUpdated`（替换 `reduceSessionStatusPatched` + `reduceSessionTreeHydrated` 的 status 部分 + 所有原始 mutateSessionList status 写）：
  ```kotlin
  internal fun reduceSessionStatusProjectionUpdated(state, action): StoreState = state.copy(
      sessionList = state.sessionList.copy(sessionStatuses = action.projection),
  )
  ```
- ~25 个读点**零修改**（仍读 `.value.sessionStatuses`）。
- `bumpSessionUpdated`（`ViewModelSupport.kt:188-196`）：optimistic 写入时仍需 bump session.time.updated（防 REST 覆盖乐观）——authority 在 OPTIMISTIC origin 时附带 bump 信号，reducer 一并处理（或在 projection action 里带 updatedTimestamp 集合）。

**生命周期投影**（收敛 `StatusAggregatorImpl.entries`）：
- authority 的 `forwardToAggregator()` 调 `statusAggregatorInput.applySseStatus(key, toSessionBusyStatus(status), sourceTimeMs)`（复用既有方法）。
- **所有**写入源（含 slim digest、optimistic，现状不喂聚合器）经 authority 自动喂聚合器 → **slim 模式聚合器不再落后 UI**（根治 R3）。
- 聚合器保留其 composite-key 作用域、freshness TTL、`GlobalBusyState` 投影逻辑（已成熟，不改）。
- 长期演进（P1）：聚合器的 `entries` 可进一步改为从 authority 直接派生（消除内部并行状态），但**首期保留聚合器作为 authority 的下游消费者**，降低重构风险。

### 2.4 正交状态轴（UI 派生）

| 轴 | 取值 | 来源 |
|---|---|---|
| **execution.value** | `Idle \| Busy \| Retry` | authority 投影（`sessionStatuses[id]`），单一 UI 来源 |
| **execution.generation** | 单调 Long（per session） | authority 内部（slimapi turn 强 / 客户端 bump 弱） |
| **execution.optimisticPending** | Boolean | authority 内部（确认门） |
| **freshness** | `Unknown \| Stale \| Fresh` | authority 内部 TTL（`STATUS_TTL_MS≈30s`）派生，UI 保守 |
| **submission** | `Idle \| Posting` | `sendingSessionIds`（POST 在途窗口，**不延长**） |
| **abort.pending** | Boolean（per session） | 新增（P0-F），abort POST 在途 |
| **connection.delivery** | connected/delivering | 既有 `sseConnected`/`sseDigestRelayEffective` |
| **compaction** | running | 既有 `chat.isCompacting` |
| **error.durable** | `none \| Some(MessageError)` | `Message.error`（持久），P0-E 恢复 |

**UI 派生公式**（收敛 §1.3 的散落派生）：
```
currentSessionIsRunning = execution.value != Idle || submission == Posting || compaction.running
canStop = currentSessionIsRunning && !canSend && !abort.pending
abortButtonState = abort.pending ? "stopping(禁二次)" : canStop ? "stop" : hidden
```

---

## 3. 护栏与不变量设计（执行代际 fence：强 + fallback）

### 3.1 两层 fence 设计

> **rev-ogpt #8**：禁 wall-clock 因果；须为同 identity 内跨执行轮次建立非 wall-clock 因果标识/确认协议。

**Tier 1 — 强 fence（slimapi 路径，推荐）**：
- oc-slimapi 中继层为每个 `session.digest` / status 转发附加 `turn: <int>`（per-session 单调，见 §5 契约）。
- authority `submit(generation=turn)`：**严格单调**——`turn < currentGen[sid]` → DROP（晚到的旧代际事件丢弃）。
- 可靠性：turn 由中继层观察真实 POST/abort 流量派生，是服务端侧权威因果标识，**远胜客户端推断**。
- **不动 opencode 服务端**（slimapi 是用户自建中继，可改）。

**Tier 2 — fallback fence（legacy SSE 路径，不经 slimapi）**：
- 无 turn 时走**确认门**：
  - POST 成功 → authority `submit(origin=OPTIMISTIC, generation=null)` → `optimisticPending[sid]=true`、`generation++`。
  - incoming `idle` AND `optimisticPending==true` → **stale idle（旧轮次）**，DROP，保留 optimistic busy。
  - incoming `busy`/`retry` AND `optimisticPending==true` → **server echo 确认**，`optimisticPending=false`，应用。
  - incoming 任意 AND `optimisticPending==false` → 正常应用。
- 辅以连接时钟 merge-timing（`sourceTimeMs >= prev.sourceTimeMs`，局部 tie-breaker，非因果源）。

**liveness 安全阀**（非因果源，仅逃生）：
- REST reconcile（`refresh()`/`mergeStatusSnapshot`）对覆盖 sid **清除 `optimisticPending`**（REST 权威）。
- 可选看门狗：`optimisticPending[sid]` 滞留 > `OPTIMISTIC_CONFIRM_TIMEOUT`（8s）→ 触发该 sid REST reconcile。

**为何非 wall-clock**：排序权威是 `generation`（单调计数）+ `optimisticPending`（确认状态）+ slimapi turn（服务端侧权威）。`sourceTimeMs`/`OPTIMISTIC_CONFIRM_TIMEOUT` 仅作局部 tie-breaker 与逃生阀，不参与因果判定。

### 3.2 不变量（Invariants）

> 现状满足 ✅；本方案落地 🟡（对应 P 项）。

1. **(✅)** 服务端 `SessionStatus` 是 per-instance 内存执行投影；`GET /session/status` 稀疏（缺项 ≡ idle）；进程重启清空；非持久真相。
2. **(✅)** `Message.error` 是失败回合的**持久真相**；跨重启/重载/重连均在。
3. **(✅ 修正)** retry 跑在 Runner fiber 内；**interrupt 杀 fiber 但 `Effect.retry` 不在 interrupt 时触发**（§6）；retry 退避期 status=retry（非 idle）。
4. **(✅)** retry `next` 是服务端计算的下次重试 epoch-ms，非客户端时间戳。
5. **(✅)** `sendingSessionIds` ≡ POST 在途窗口，**不延长**（§9 否决延长）。
6. **(✅)** 乐观 busy 仅 POST onSuccess 后写；失败不写（无回滚对象，§9 否决回滚）。
7. **(✅)** abort 仅服务端操作，不写乐观 idle（§9 否决乐观 idle）；idle 由 server 清除。
8. **(🟡 P0-A)** **所有 status 写入经 `SessionStatusAuthority.submit()` 单一入口**，内部执行 identity/epoch guard。path #5 无护栏直写消除。
9. **(✅)** REST status 受 `statusLoadEpoch` + host-at-request-start 守卫。
10. **(🟡 P0-B)** 同 identity 内跨执行轮次由 **generation（slimapi turn 强）+ 确认门（legacy 弱）** 保护；optimistic 未确认时 stale idle 不得覆盖；不依赖 wall-clock。
11. **(🟡 P0-A)** UI 投影（`sessionStatuses`）与生命周期投影（`GlobalBusyState`）从**同一权威源**派生；slim digest 与 optimistic 经 authority 自动喂聚合器，双源收敛（R3 根治）。
12. **(✅)** `Unknown`/`Stale` 新鲜度不进 SSE idle 宽限期 / `AllIdleFresh`（保守）。
13. **(✅)** route-instance 守卫只保护 route-owned chat 写；全局 status route 无关。
14. **(🟡 P0-D)** 首次连接 status reconcile 不依赖 `KeepCurrent`；冷启无论决策（含 archive 早返回）都触发 bulk status；exactly-once。
15. **(🟡 P0-E)** durable `Message.error` 经消息加载/重载可见；`status=idle` 不等于成功，须等 `message.updated` 读 `message.error`。
16. **(🟡 P0-F)** abort POST 在途有显式 `abort.pending` 标志 + 看门狗；UI 不停滞。

---

## 4. 结构性重构改动计划（P0/P1/P2）

> 每个改动点含：**文件:行号**、**具体改什么**、**为什么**、**风险**、**验证**。
> 验证统一遵循 AGENTS.md：改 Kotlin/资源后跑 `./scripts/check.sh`（编译 + `testDebugUnitTest`）。
> 行号基于 explorer 核对快照（2026-07-30）；实施前用 `read` 复核漂移。

### P0（结构性基础 + 用户可见正确性）

---

#### **P0-A 建立 SessionStatusAuthority（单一权威源 + 写入漏斗 + 双投影）**

**对症**：dual-source 不同步（R3）、path #5 无护栏（R2 的一部分）、10 条散落写入（结构债）。**这是整个重构的基础**，其余 P0 项依赖它。

**文件:行号**：
- **新建** `service/status/SessionStatusAuthority.kt`（权威源本体）。
- **新建/改** `service/status/StatusModule.kt:53-74`（Hilt 注入）。
- **新建** `ui/SessionStatusProjectionReducer.kt`（单一投影 reducer，或并入 `SessionListFieldsReducer.kt`）。
- **改** `AppAction.kt:664-668`（`SessionStatusPatched` 改为投影更新 action，或新增 `SessionStatusProjectionUpdated`）。
- **改写入漏斗**（10 条路径）：
  - `ui/SessionListFieldsReducer.kt:62-71`（`reduceSessionStatusPatched` → 改为调 authority 或移除）。
  - `ui/controller/sse/LegacySseHandler.kt:130-169`（fork point → 单一 submit）。
  - `ui/controller/SessionSyncCoordinator.kt:987-991`（slim digest → submit）。
  - `ui/controller/StatusPollOrchestrator.kt:215,398`（REST merge → submit）。
  - `ui/SessionTreeHydrator.kt:149`（tree hydrate → submit）。
  - `ui/BackgroundUnreadPoller.kt:187`（→ submit）。
  - `ui/SessionListActions.kt:251`（loadChildSessions → submit）。

**具体改什么**：

1. **新建 `SessionStatusAuthority`**（@Singleton，构造注入 `identityStore: ConnectionIdentityStore`、`statusAggregatorInput: StatusAggregatorInput`、`clock: () -> Long`、`@UiApplicationScope scope`）：
   - 内部 `AtomicReference<AuthorityState>`（结构见 §2.2）。
   - `fun submit(update: StatusUpdate)`（commit 逻辑见 §2.2，含全部护栏）。
   - `val uiStatuses: StateFlow<Map<String, SessionStatus>>`（派生投影，由内部 MutableStateFlow 发布）。
   - `fun purgeForHostSwitch()`（host 切换清空，对应 `HostStatePurged`）。
   - `fun clearOptimisticPending(sids: Set<String>)`（REST reconcile 权威清除，liveness）。

2. **Hilt 注入**（`StatusModule.kt`）：`@Provides @Singleton fun provideSessionStatusAuthority(...)`；绑定接口。

3. **单一投影 reducer**：
   - authority 在 commit 后 `dispatch(AppAction.SessionStatusProjectionUpdated(uiStatuses.value))`（或经 store 直注投影）。
   - `reduceSessionStatusProjectionUpdated`（新 reducer）：`sessionList.copy(sessionStatuses = action.projection)`。
   - 移除/改写 `reduceSessionStatusPatched`（:62-71）——不再裸 `+`，由 authority 投影驱动。
   - `reduceSessionTreeHydrated`（:108）的 status 部分改为经 authority.submit(origin=TREE)，投影由 authority 统一发。

4. **写入漏斗（10→1）**：每条写入路径改为构造 `StatusUpdate` 并调 `authority.submit()`，**移除直接 `mutateSessionList { sessionStatuses = ... }`**：
   - `LegacySseHandler:130-169`：删 fork，单一 `authority.submit(StatusUpdate(sid, status, SSE_LEGACY, identity, generation=turn, sourceTimeMs))`。
   - `SessionSyncCoordinator:987-991`：`authority.submit(..., SSE_SLIM, generation=turn)`。
   - `StatusPollOrchestrator:215/398`：`mergeStatusSnapshot` 逻辑迁入 authority 的 REST 分支（identity/epoch guard + merge），或保留 merge 函数但结果经 authority.submit(origin=REST)。
   - `SessionMutationActions:376-379`（optimistic）：改为 `authority.submit(..., OPTIMISTIC, identity=identityAtDispatch)`（identity guard 见 P0-C）。
   - 其余写入点同理。

5. **读面零修改**：~25 个读点保持 `.value.sessionStatuses`。

**为什么**：dual-source 与 10 条散落写入是结构债根因。单一权威源 + 双投影根治 R3、收敛 path #5、使所有护栏集中可维护。用户明确要结构性重构。

**风险**：
- **高**：触及状态层核心，回归风险大。**缓解**：分阶段——先建 authority 并行使能（双写：旧路径 + authority），验证投影一致后，再切断旧路径（迁移期 `./scripts/check.sh` 高频跑）。
- `bumpSessionUpdated` 语义需保留（optimistic 防覆盖）。**缓解**：authority OPTIMISTIC 分支附带 bump 信号。
- mergeStatusSnapshot 的「REST 在途保护」逻辑需迁入 authority。**缓解**：authority 内 REST 分支保留 localBefore/localAfter diff 语义。

**验证**：
- 单测：authority.submit 各 origin 路径 → 投影正确；identity 过期 DROP；generation stale DROP；确认门 stale idle DROP。
- 单测：双投影一致（uiStatuses 与 forwardToAggregator 同源）。
- 回归：`sessionStatuses` 读点行为不变（金钟测试覆盖 ~25 读点）。
- `./scripts/check.sh` 通过。

---

#### **P0-B 执行代际 fence（authority 内 generation 强 fence + 确认门 fallback）**

**对症**：同 identity 内 stale idle 覆盖 optimistic/retry（R1）；rev-ogpt #1/#8；用户反馈 #1。**依赖 P0-A**（fence 在 authority commit 层）。

**文件:行号**：
- `service/status/SessionStatusAuthority.kt`（commit 层 generation + 确认门，见 §2.2/§3.1）。
- `service/status/StatusUpdate.kt`（`generation: Long?` 字段）。
- **slimapi turn 消费**：`ui/controller/SessionSyncCoordinator.kt:978-991`（digest 解析 turn → submit generation）。
- **legacy turn 缺失**：authority fallback 走确认门。

**具体改什么**：
1. authority commit 层实现 §3.1 两层 fence（已在 P0-A 的 submit 逻辑中）。
2. slim digest 解析：`val turn = event.payload.optString("turn").toLongOrNull()`（slimapi 附加字段，见 §5）；非空 → `submit(generation=turn)`；空 → fallback 确认门。
3. optimistic 写入（P0-C）`generation=null` → 客户端 bump + 确认门。
4. REST reconcile 调 `clearOptimisticPending(reconciledSids)`（liveness）。
5. 可选看门狗（`ProcessStatusPoller` 内）：`optimisticPending` 滞留 > 8s → 单 sid REST probe。

**为什么**：R1 是反馈 #1 致命根因。slimapi turn（服务端侧权威）提供最强 fence；legacy 路径用确认门兜底。非 wall-clock（rev-ogpt #8）。

**风险**：slimapi 未附加 turn 时 fallback 须正确（确认门）。**缓解**：单测覆盖两层；slimapi 协作项明确（§5）。

**验证**：
- 单测：optimistic busy + stale idle（generation 缺失）→ 确认门 DROP。
- 单测：slim digest turn=2 后到达 turn=1 帧 → 强 fence DROP。
- 单测：optimistic → server busy（turn 或确认）→ pending 清 → server idle → 应用。
- `./scripts/check.sh` 通过。

---

#### **P0-C Optimistic busy 走 authority + identity guard**

**对症**：path #5 无护栏（R2）；rev-ogpt #2。**依赖 P0-A**。

**文件:行号**：
- `ui/AppCoreOrchestration.kt:939-960`（`dispatchSendMessage` 捕获 identity）。
- `ui/SessionMutationActions.kt:312-441, 376-379`（onSuccess 走 authority + isCurrent guard）。
- `service/identity/ConnectionIdentityStore.kt:238-244`（复用 `isCurrent`）。

**具体改什么**：
1. `dispatchSendMessage`（:939-960）POST 派发前捕获 `identityAtDispatch = identityStore.currentIdentity()`、`epochAtDispatch = identityStore.currentEpoch()`，传入 `launchSendMessage`。
2. `launchSendMessage` 签名增 `identityAtDispatch`、`epochAtDispatch`。
3. `onSuccess`（:376-379）：archive 检查后，`if (!identityStore.isCurrent(identityAtDispatch)) return@onSuccess`（host 已切换，不污染新 host），否则 `authority.submit(StatusUpdate(sid, busyStatus, OPTIMISTIC, identityAtDispatch))`。
   - authority 内 identity guard 是第二道防线（P0-A），onSuccess 的 isCurrent 是第一道（避免无谓 submit）。

**为什么**：explorer 确认 `launchSendMessage` 现状无 identity/epoch/host guard（仅 archived 检查，快照可能 stale）。host 切换后 POST onSuccess 污染新 host sessionStatuses（R2）。

**风险**：`AppCoreOrchestration` 须能注入 `identityStore`（实施前 read 确认注入路径）。

**验证**：单测 isCurrent=false → 不 submit；host 切换后 onSuccess → authority 不含该 sid。`./scripts/check.sh`。

---

#### **P0-D 冷启 status reconcile 无条件化（覆盖 archive 早返回 + 空目录边界）**

**对症**：archive 早返回跳 status 刷新（R7）；rev-ogpt #4；用户反馈 #3。**独立可上线**。

**文件:行号**：
- `ui/controller/SessionListRefreshOrchestrator.kt:121-131`（archive 早返回）+ `:195-198`（KeepCurrent 内 onLoadSessionStatus）。
- `ui/controller/StatusPollOrchestrator.kt:310-316`（slim 空目录 complete(true) 返回）。

**具体改什么**：
1. **hoist `onLoadSessionStatus()`**：从 KeepCurrent 分支（:196）上提到 success handler 顶部（session 列表 merge 后、archive 检查**前**），使所有路径（含 archive 早返回、ClearChat、NoOp）触发一次 bulk status：
   ```kotlin
   // onSuccess 顶部（merge 后，anyArchived 检查前）
   onLoadSessionStatus()   // ← hoisted：无条件，exactly-once
   if (anyArchived && onArchivedSessionsDetected != null) { ...; return@onSuccess }
   // when 分支移除 :196 的 onLoadSessionStatus()（避免 double）
   when (decision) {
       is KeepCurrent -> { onLoadMessages(decision.sessionId) }
       // ClearChat/NoOp 不再单独 status 加载
   }
   ```
   - exactly-once：现状仅 :196 调一次；hoist 后仍一次（移除原 :196）。
2. **slim 空目录边界**（:310-316）：`directories.isEmpty()` → `complete(true)` 返回，不产条目。**此行为正确**（无 session=无状态）。文档化：空目录 → 聚合器该 workdir `Unknown`（保守）→ UI freshness=Unknown（P1-C 落地后透传）。部分失败（fix-11a :386-394）、全失败（fix-10 :357-360）保留逻辑不变。

**为什么**：archive 早返回跳过 onLoadSessionStatus，用户跨设备归档后看到陈旧状态。

**风险**：archive 路径额外一次 REST status 调用（可接受，冷启一次性）；`statusLoadEpoch` 单飞保护防 double。

**验证**：单测 archive 命中 → onLoadSessionStatus 仍调（exactly-once）；ClearChat/NoOp → status 加载触发。集成（模拟器）：冷启 + 服务端 busy 会话 + 无 current session → UI 显示运行态。`./scripts/check.sh`。

---

#### **P0-E Durable `Message.error` 恢复（错误稳定展示）**

**对症**：durable error 恢复缺失（rev-ogpt #5/#7，应升 P0）；R9/R10；用户反馈 #2。**独立可上线**（不依赖 P0-A）。

**文件:行号**：
- `ui/SessionListFieldsReducer.kt:28-44`（`reduceSessionArchivedLocal` 不清 sessionErrorsById）。
- `ui/ChatFieldsReducer.kt:234-250`（`reduceLastAssistantErrorAttached` 静默丢弃）。
- 两阶段时序契约（上游 `processor.ts:619` 设内存、`:595-596` ensuring 落盘；status=idle 先到，message.error 后到）。

**具体改什么**（三子项）：

**(a) 归档清 sessionErrorsById**（R9，最低风险，先上）：
```kotlin
// SessionListFieldsReducer.kt reduceSessionArchivedLocal（:28-44）
sessionErrorsById = state.sessionList.sessionErrorsById.filterKeys { it !in action.archivedIds },
```

**(b) 修复 `reduceLastAssistantErrorAttached` 静默丢弃**（R10）：
```kotlin
// ChatFieldsReducer.kt reduceLastAssistantErrorAttached（:234-250）
if (!state.acceptsRouteUpdate(action.expectedRouteInstance, action.sessionId)) {
    // route 已切换：排队待 route 回归重附（非静默丢）
    return state.copy(chat = state.chat.copy(
        pendingErrorReattach = state.chat.pendingErrorReattach + (action.sessionId to action.expectedRouteInstance),
    ))
}
val last = state.chat.messages.lastOrNull { it.role == "assistant" }
if (last == null) {
    // 最后 assistant 未加载：触发消息刷新，错误到达后重附
    return state.copy(chat = state.chat.copy(pendingErrorRefresh = state.chat.pendingErrorRefresh + action.sessionId))
}
if (last.error != null) return state  // 幂等
// ... 现有附加 ...
```
- `ChatState` 增 `pendingErrorReattach: Map<String, Long>`、`pendingErrorRefresh: Set<String>`；route 回归/消息加载后 drain 重试。

**(c) 两阶段时序感知**（rev-ogpt #5）：busy/retry → idle（轮次结束）时，若该 session 有过 busy/retry，标 `pendingErrorCheck[sid]`，等最后一条 assistant 的 `message.updated` 到达后检查 `message.error`。**文档化契约**：`status=idle` 不等于成功，须等 `message.updated`。

**为什么**：错误稳定展示是三个用户反馈之一，原 P0 无错误行为项。

**风险**：(b) 排队机制增 ChatState 复杂度（集合有界，drain 后清）；(c) pendingErrorCheck 需在 SessionDeletedLocal/HostStatePurged 一并清防泄漏。

**验证**：单测归档清错误；route 不匹配→重排队列→回归重附；last==null→刷新→重附；busy→idle→pendingErrorCheck→message.updated 带 error→展示。`./scripts/check.sh`。

---

#### **P0-F abortPending 标志 + 看门狗（abort UI 不停滞）**

**对症**：abort 不写乐观致 UI 停滞（R6）；sendingSessionIds 无看门狗（R5）；用户反馈 #1/#3。**独立可上线**。

**文件:行号**：
- `ui/AppStateSlices.kt:716`（SessionListState 增 `abortPendingSessionIds: Set<String>`）。
- `ui/ChatViewModel.kt:409-425`（`abortSession` 派发 AbortPendingAdded）。
- `ui/SseSessionListReducers.kt:297`（server idle 清 abortPending，或经 authority 投影）。
- `ui/chat/Composer.kt:164-165`（canStop + abortPending stopping 态）。

**具体改什么**：
1. `SessionListState` 增 `val abortPendingSessionIds: Set<String> = emptySet()`。
2. `abortSession`（:409-425）：派发前 `dispatch(AppAction.AbortPendingAdded(sid))`；`.onFailure { dispatch(AbortPendingCleared(sid)) }`。
3. server idle 到达 sid（authority 投影或独立 reducer）→ 清 abortPending + 兜底清 sendingSessionIds[sid]（R5 兜底，Composer reducer 监听 idle）。
4. 看门狗：`ProcessStatusPoller` 或新协程，`abortPendingSessionIds` 滞留 > 10s → 该 sid REST reconcile + 清 abortPending。
5. UI（Composer :164-165）：`val isAborting = sid in abortPendingSessionIds`；`canStop = currentSessionIsRunning && !canSend && !isAborting`；isAborting → 显「停止中」（disabled/spinner）。

**为什么**：abort 纯服务端不写乐观（explorer 确认 :409-425），SSE 未投 idle 时 UI 停留 busy（R6）。abortPending + 看门狗使 UI 不停滞且禁二次 abort。

**风险**：看门狗误清（abort 实生效但 SSE 慢）→ REST reconcile 修正（idle 权威），可接受。

**验证**：单测 abort→abortPending 含 sid；server idle→清除；SSE 断流→10s 看门狗→REST 对齐；abortPending 中 canStop=false。`./scripts/check.sh`。

---

### P1（稳健性 / 模型补全 / 收敛深化）

> 注：P0-A 落地后，R3（双源）/R4（timing）/R8（target==null 聚合器）已在 authority 内**自动部分根治**。P1 项是深化与补全。

---

#### **P1-A 聚合器 entries 改为从 authority 直接派生（消除并行内部状态）**

**对症**：聚合器仍持独立 `entries`（`AtomicReference<Aggregate>` :181），首期作为 authority 下游消费者。P1-A 进一步消除并行状态。

**文件:行号**：`service/status/StatusAggregatorImpl.kt:170-181, 386-398`。

**具体改什么**：聚合器 `entries` 改为从 authority 派生（订阅 authority 状态，按 composite-key 投影）。`applySseStatus`/`refresh` 内部写逻辑迁移到 authority，聚合器只保留 `GlobalBusyState` 投影 + TTL/freshness 计算。

**为什么**：彻底单一权威，消除聚合器内部并行 Map。

**风险**：中-高（动聚合器核心）；需保留 `GlobalBusyState`/idle-debounce 语义。**缓解**：金钟回归 StreamingLifecycleCoordinator。

**验证**：单测 GlobalBusyState 投影不变；idle-debounce 行为不变。`./scripts/check.sh`。

---

#### **P1-B retry `action` 模型补全**

**文件:行号**：`data/model/Session.kt:91-100`。

**具体改什么**：增 `val action: SessionStatusAction? = null`（`{reason,provider,title,message,label,link?}`）。legacy `session.status{retry}` 与 slim digest 穿透解析（authority submit 时携带）。保留 `MessageAbortedError` 静默策略。

**验证**：单测解析 retry action。`./scripts/check.sh`。

---

#### **P1-C freshness 轴 UI 落地（Unknown/Stale/Fresh）**

**文件:行号**：`ui/chat/ChatScaffold.kt:583-584` + authority freshness 派生。

**具体改什么**：authority freshness（TTL `STATUS_TTL_MS≈30s`）透传投影；UI stale-busy→busy（保守），stale-idle/首窗→Unknown（不进 idle 宽限期）。

**验证**：单测首窗 Unknown；stale-busy 保守。`./scripts/check.sh`。

---

#### **P1-D statusLoadEpoch ↔ completenessEpoch 关联**

**对症**：R11（两独立 epoch）。

**文件:行号**：`ui/controller/StatusPollOrchestrator.kt`（statusLoadEpoch）+ `ui/SessionTreeHydrator.kt:117`（completenessEpoch）。

**具体改什么**：statusLoadEpoch advance 时 invalidate pending completenessEpoch 结果（单向失效）。

**风险**：中（动 epoch 交互，谨慎回归）。

**验证**：单测 epoch 交叉。`./scripts/check.sh`。

---

#### **P1-E reduceLastAssistantErrorAttached 重排队列深化**

P0-E(b) 的产品化（限界、drain 触发点、诊断日志）。可与 P0-E(b) 合并。

---

### P2（体验对齐 / 模型硬化）

---

#### **P2-A retry action UI（subscribe / open-settings）**
依赖 P1-B。遵循 `docs/specs/ui-style-spec.md` 三层 overlay 规则 + `ui/theme/` 共享原语。

#### **P2-B stale/Unknown UI 轻量提示**
依赖 P1-C。freshness≠Fresh 时轻量指示（不强制改按钮态）。

#### **P2-C legacy SSE target==null 聚合器兜底**
**对症**：R8。`LegacySseHandler.kt:142-154`：target==null 时用 status event 的 directory（若有）或标 Unknown 喂聚合器。P0-A 后经 authority 自动部分覆盖，P2-C 补 legacy 兜底。

#### **P2-D SessionStatus 类型硬化（sealed/enum）**
`data/model/Session.kt:91-100`：裸字符串 → sealed/enum，编译期约束。机械重构。

---

## 5. oc-slimapi 跨项目协作契约（强 fence 增强路径）

> oc-slimapi（`/home/mar/personal_projects/oc-slimapi`，用户自建 Python 中继层）是 ocdroid slim 路径的中继：`GET /slimapi/sessions/status`、slim SSE digest 经它转发。**它可改**（用户自建），不动 opencode 服务端。本节定义协作契约（跨项目，需 slimapi 侧配合实施）。

### 5.1 为何需要 slimapi 介入

- 客户端确认门（Tier 2 fallback）能挡「optimistic busy 未确认时的 stale idle」，但**无法可靠区分**「同 identity 内两条 server-originated status 帧的代际」（都非 optimistic）。
- slimapi 作为中继**观察真实 POST/abort 流量**，能派生服务端侧权威因果标识（turn），远胜客户端推断。
- 经 slimapi 的 slim 路径获得 Tier 1 强 fence；不经 slimapi 的 legacy 路径用 Tier 2 fallback。两层覆盖。

### 5.2 契约：slimapi 附加字段

**slimapi 在转发的每个 `session.digest`（及任何 status 中继）payload 附加 `turn` 字段**：

```json
{
  "type": "session.digest",
  "sessionId": "<sid>",
  "status": "busy",            // 既有
  "turn": 3,                   // ← 新增：per-session 单调递增整数
  ...                          // 其余既有字段不变
}
```

**`turn` 语义**：
- per-session 单调递增整数（每个 session 独立计数，从 0 或 1 起）。
- **递增时机**（slimapi 观察到的执行轮次边界）：
  1. **POST `/session/{sid}/prompt` 经 slimapi 转发时**：`turn[sid] += 1`（新一轮执行开始）。
  2. **POST `/session/{sid}/abort` 经 slimapi 转发时**：`turn[sid] += 1`（当前轮次被中止，新轮次边界）。
  3. （可选）slimapi 观察到 opencode 服务端进程重启/会话重置时：`turn[sid]` 重置或跳大数。
- turn 仅随 digest/status 帧透传，**不参与服务端逻辑**（slimapi 只附加，不改 opencode 行为）。
- turn 的持久化：slimapi 进程内维护（重启可重置，因客户端会在重连时 REST reconcile 清 pending 作 liveness 兜底）。

### 5.3 ocdroid 侧消费

- `SessionSyncCoordinator.handleSessionDigest`（:978-991）：解析 `val turn = payload.optString("turn").toLongOrNull()`。
- 非空 → `authority.submit(StatusUpdate(sid, status, SSE_SLIM, identity, generation=turn, sourceTimeMs))`。
- authority commit 层 Tier 1 强 fence：`turn < currentGen[sid]` → DROP（晚到的旧代际事件丢弃）。
- 空（slimapi 未升级 / 字段缺失）→ 自动降级 Tier 2 确认门（向后兼容）。

### 5.4 落地协作

| 项 | 责任方 | 内容 |
|---|---|---|
| slimapi 附加 `turn` | oc-slimapi 项目 | 转发 digest/status 时附加 per-session 单调 turn；观察 POST prompt/abort 递增 |
| ocdroid 消费 turn | 本方案 P0-B | 解析 turn → authority.submit(generation=turn)；缺失降级确认门 |
| 契约版本协商 | 双方 | turn 字段可选（缺失=legacy 行为），无硬依赖，渐进部署 |

> **向后兼容**：turn 字段可选。slimapi 未升级时 ocdroid 用 Tier 2 fallback（确认门），不阻断。升级后自动获得强 fence。**不强求 slimapi 同步上线**。

---

## 6. interrupt 与 retry 的真实关系（独立澄清）

> exp-1（`ses_05129c484ffeRBCczaCY89Q15D`）逐链核对上游源码，解决 main-A 与对比文档/rev-ogpt 的分歧。

### 6.1 权威结论

**interrupt（fiber cancel）DOES NOT trigger retry continuation。** 「一次中断同时杀流与重试循环」措辞**误导**；main-A §0.4 正确。

### 6.2 决定性证据：processor.ts pipeline 顺序（648-676）

```
                 INNERMOST
ensuring(cleanup) {                    ← :676 总跑（落盘 message.error）
  catch(halt) {                        ← :675 只捕 typed failure
    retry(policy) {                    ← :660-674 只捕 typed failure；schedule 只在 fail 时 step
      catchCauseIf(                    ← :656-659
        !Cause.hasInterruptsOnly       ← 纯 interrupt → 谓词 false → 放行穿透
      ) {
        onInterrupt {                  ← :648-654 interrupt 时触发
          aborted = true
          if (!ctx.assistantMessage.error) halt(...)   ← 条件 halt
        }
      }
    }
  }
}
```

- `Effect.retry` 只捕 typed failure（`Effect.fail`）；interrupt cause 非 typed failure。
- `catchCauseIf(!Cause.hasInterruptsOnly)` 在 retry 之内：纯 interrupt 谓词 false → 穿透 catchCauseIf → 到 retry → retry 不 step → 穿透 catch(halt) → 到 ensuring(cleanup)。
- retry schedule 的 `set`（写 status=retry）只在 retry 捕获 typed failure 时调用（retry.ts:189）—— interrupt 永不触达。

### 6.3 awaitDone 的「retry」真相（runner.ts:59-65）

```ts
const awaitDone = (done) = Deferred.await(done).pipe(
  Effect.catchTag("RunnerCancelled", (e) => onInterrupt ?? Effect.die(e))  // 一次性 onInterrupt
)
```

捕获 `RunnerCancelled` 返回 `onInterrupt`（`lastAssistant` → `finalizeInterruptedAssistant`）。**一次性取消，非 LLM 重试循环**。

### 6.4 retry-backoff 中 abort 的状态序列

**`retry → idle`（直接，无中间 busy）**。

- retry backoff 期间 `ctx.assistantMessage.error` 未设（retry 在 halt 之前捕获初始 failure）。
- abort → interrupt → `onInterrupt` → `halt`（因 `!error`）→ 设 error + `status.set(idle)`。
- Runner.cancel 同时 onIdle → status=idle（幂等）。
- ensuring(cleanup) → session.updateMessage 落盘 error（:595-596）。

### 6.5 对 ocdroid 的影响

1. 客户端无需建模「interrupt 后重试」——abort 一次性硬杀，无 retry 续命。
2. **retry 期 stop 应可用**（isRetry ∈ canStop）——反馈 #1「stop 消失」由 P0-B generation fence 修复（防 stale idle 覆盖 retry）。
3. **abort 后 idle 先到、error 后到**（两阶段）：ocdroid 须 idle 后等 `message.updated` 读 `message.error`（P0-E），勿在 idle 判成功。
4. interrupt vs abort 分级（TUI 双击）：ocdroid 单一 abort 已满足；P0-F abortPending 提供「停止中」中间态。

---

## 7. 竞态覆盖矩阵（main-B §7 逐条）

| 竞态 # | 描述 | 覆盖改动项 | 根治方式 |
|---|---|---|---|
| **R1** | 同 identity 内 stale idle 覆盖 optimistic busy | **P0-A + P0-B** | authority commit 层 generation（slimapi turn 强）+ 确认门（legacy 弱）DROP stale idle |
| **R2** | POST 成功 vs host 切换污染新 host | **P0-A + P0-C** | authority identity guard + onSuccess isCurrent 双防线 |
| **R3** | 双源不同步（sessionStatuses vs 聚合器） | **P0-A**（根治） | 单一权威源双投影；slim digest/optimistic 经 authority 自动喂聚合器 |
| **R4** | applySessionStatus 无 timing | **P0-A + P0-B** | authority 内 merge-timing（连接时钟 tie-breaker）+ generation fence |
| **R5** | sendingSessionIds 无看门狗、abort 不清 | **P0-F** | abortPending 看门狗 + idle 兜底清 sendingSessionIds |
| **R6** | abort 不写乐观 idle，UI 停滞 | **P0-F** | abortPending 显式化 + 看门狗 + stopping UI |
| **R7** | archive 早返回跳过状态刷新 | **P0-D** | hoist onLoadSessionStatus 无条件化 |
| **R8** | target==null 绕过聚合器 | **P0-A**（部分）+ **P2-C** | authority 统一喂聚合器；legacy target==null 兜底 |
| **R9** | sessionErrorsById 归档不清 | **P0-E(a)** | 归档清 sessionErrorsById |
| **R10** | reduceLastAssistantErrorAttached 静默丢弃 | **P0-E(b)** + **P1-E** | 排队重附 + 刷新 |
| **R11** | 两个独立 epoch 无关联 | **P1-D** | statusLoadEpoch ↔ completenessEpoch 关联 |

**覆盖统计**：11/11 逐条覆盖。P0 根治 9 条（R1/R2/R3/R4/R5/R6/R7/R9/R10），P1 补 1 条（R11），P2 补强 1 条（R8）。**R3/R4/R8 因 P0-A 单一权威源而结构性根治**（非补丁）。

---

## 8. 验收场景（≥12，标注优先级与修复后期望行为）

> ✅=现状已满足（回归守护）；🟡=本方案修复后期望。

1. **(🟡 P0-A/B)** 任何写入路径 → 经 authority.submit → 投影与聚合器同源（双投影一致）。
2. **(🟡 P0-B)** optimistic busy 写入（optimisticPending=true）→ 旧轮次 `session.status{idle}` 到达 → 确认门 DROP → UI 保持 busy、stop 在位。（反馈 #1 核心）
3. **(🟡 P0-B)** slim digest turn=3 后到达 turn=2 帧 → 强 fence DROP。
4. **(🟡 P0-B)** optimistic → server busy/retry（确认或 turn）→ pending 清 → server idle → 应用。
5. **(🟡 P0-C)** POST 在途 host 切换 → onSuccess isCurrent false → 不 submit → 新 host sessionStatuses 不含该 sid。
6. **(✅ 回归)** POST 在途 host 未切换 → onSuccess → optimistic busy 正常写入（经 authority）。
7. **(🟡 P0-D)** 冷启无可保留 current session（ClearChat/NoOp）→ 仍加载 bulk status → 服务端 busy 会话不等待首事件即显示运行态。
8. **(🟡 P0-D)** 冷启命中 archive 早返回 → onLoadSessionStatus 仍触发（exactly-once）→ 不滞留陈旧。
9. **(✅ P0-D 边界)** slim 空目录 → complete(true) 不产条目 → 聚合器 Unknown → UI 保守（P1-C 后）。
10. **(🟡 P0-Ea)** 归档 session → sessionErrorsById 清除 → 无陈旧错误横幅。
11. **(🟡 P0-Eb)** LastAssistantErrorAttached route 已切换 → 进 pendingErrorReattach → route 回归 → 重附（非静默丢）。
12. **(🟡 P0-Eb)** last==null → 进 pendingErrorRefresh → 消息加载 → 重附。
13. **(🟡 P0-Ec)** busy→idle（轮次结束）→ pendingErrorCheck → message.updated 带 error → 错误稳定展示；无 error → 清 check。（反馈 #2，两阶段时序）
14. **(🟡 P0-F)** abort → abortPending 标记 → UI 显「停止中」（禁二次 abort）；server idle → 清 abortPending + 兜底清 sendingSessionIds。
15. **(🟡 P0-F)** abort + SSE 断流 → 10s 看门狗 → REST reconcile → 状态对齐，UI 不停滞。（反馈 #3）
16. **(✅)** retry 窗口（429/5xx）→ server status{retry} → stop 保持（isRetry）；retry message 可见。
17. **(✅)** retry 中 abort → server retry→idle（直接，§6.4）→ stop 清除；无乐观 idle 竞态。
18. **(🟡 P0-A)** slim digest status 到达 → authority 喂聚合器 → GlobalBusyState 不落后 UI（R3 根治）。
19. **(✅)** 轮询途中切 host → epoch bump → 过期 REST status 丢弃；新 host status 加载。
20. **(🟡 P1-B/P2-A)** free_tier_limit/account_rate_limit retry → action 穿透 → UI 显示 subscribe/open-settings。
21. **(🟡 P1-C)** TTL 后 stale-busy → freshness Unknown → 不进 idle 宽限期；保守 busy 至 REST reconcile。
22. **(✅ I1/I2)** 服务端进程重启 → GET /session/status 空 → 客户端 reconcile 清幻影 busy；durable Message.error 仍显示历史失败。
23. **(✅)** compaction 运行 → isCompacting → stop 可用；结束 → 清除。

---

## 9. 落地顺序（结构性阶段，标注依赖）

| 阶段 | 改动项 | 依赖 | 风险 | 收益 |
|---|---|---|---|---|
| **1** | **P0-E(a)** 归档清 sessionErrorsById | 无 | 极低（机械） | 错误横幅不滞留 |
| **2** | **P0-D** 冷启 reconcile 无条件化 | 无 | 低（hoist + exactly-once） | 反馈 #3 运行态 |
| **3** | **P0-F** abortPending + 看门狗 | 无 | 低（additive） | 反馈 #1/#3 stop 态 |
| **4** | **P0-E(b)(c)** 错误重排队列 + 两阶段时序 | P0-E(a) | 中 | 反馈 #2 错误稳定 |
| **5** | **P0-A** SessionStatusAuthority（双写期）| 无 | 高（核心） | 单一权威源基础 |
| **6** | **P0-C** optimistic 走 authority + identity guard | P0-A | 低-中 | host 切换不污染 |
| **7** | **P0-B** generation fence（authority commit 层） | P0-A | 中 | **反馈 #1 主根因** |
| **8** | **P0-A 切断旧路径**（迁移完成） | 5/6/7 验证 | 中 | 双源根治（R3/R4） |
| **9** | **§5 slimapi 协作**（跨项目，渐进） | P0-B | 低（可选） | 强 fence |
| **10** | **P1-A** 聚合器 entries 派生 | P0-A 稳定 | 中-高 | 彻底单一权威 |
| **11** | **P1-C/P1-B** freshness + action 模型 | P0-A | 低 | 保守 UI + upsell |
| **12** | **P1-D** epoch 关联 | 无 | 中 | R11 |
| **13** | **P2-A/B/C/D** 体验/硬化 | 对应 P1 | 低 | 体验对齐 |

> **策略**：阶段 1-4 低风险高收益先头（不依赖 authority，可独立上线）；阶段 5-8 是核心结构重构（双写迁移降风险）；阶段 9 slimapi 协作渐进（向后兼容）。每阶段 `./scripts/check.sh`。UI 改动（P2-A 等）遵循 `docs/specs/ui-style-spec.md`。

---

## 10. 被否决的方案

| 否决项 | 否决理由 | 证据 |
|---|---|---|
| **保留双源（sessionStatuses + 聚合器各自维护），仅加补丁同步** | 用户明确要**结构性权威重构**，非最小改动。补丁同步会留竞态窗口（R3/R4/R8），难维护。 | omni 方向校正 #3 |
| **延长 sendingSessionIds 贯穿 POST+流+retry** | 设计上就是 POST 在途短桥接（dispatch 置位、onComplete 清除）。运行期覆盖来自 server busy/retry + reconcile，延长会引入客户端标志与服务端脱节的新竞态。 | main-B §2.2 |
| **wall-clock / 客户端接收时间作因果版本覆盖** | 客户端时钟不可作因果序。本方案用 generation（slimapi turn 强）+ 确认门（弱），非 wall-clock。 | rev-ogpt #8 |
| **POST 失败回滚乐观 busy** | ocdroid 乐观 busy 仅 POST onSuccess 后写；失败未写 busy，无回滚对象。 | main-B §2.1；exp-2 确认 :418-432 无 optimistic/rollback |
| **abort 写乐观 idle** | abort 与 server idle race。正确做法是 abortPending + 看门狗（P0-F）。 | P0-F；main-B §2.3 |
| **照搬 V2 执行事件 / V2 reducer / Runner Shell 态** | ocdroid 消费 V1，服务端不发 V2 执行事件；Runner Shell 是服务端内部态，客户端不可见。 | main-A §6.3 N1-N4 |
| **照搬 SolidJS reconcile/produce 机制** | 框架特定。ocdroid 用 Kotlin/Compose，借鉴思想（细粒度 diff）非 API。 | main-A §6.3 N5 |
| **session.idle 废弃事件** | 上游已废弃，勿实现。 | main-A §6.3 N6 |
| **依赖 opencode 服务端改动** | 上游服务端不可改。所有 fence 在客户端 + slimapi 中继层实现。 | omni 方向校正 #1/#2 |
| **强求 slimapi 同步上线** | turn 字段可选，缺失降级 Tier 2 确认门。渐进部署，不强耦合。 | §5.4 |

---

## 11. 引用索引

### 11.1 输入材料
- 上游调研：`docs/research/2026-07-30-opencode-web-state-machine-survey.md`（main-A）
- 现状调研：`docs/research/2026-07-30-ocdroid-state-machine-survey.md`（main-B）
- 原对比文档（已作废）：`docs/opencode-state-machine-comparison.md`

### 11.2 上游 v1.18.7 关键证据
| 主题 | 文件 | 行 |
|---|---|---|
| SessionStatus 三态 | `packages/schema/src/session-status-event.ts` | 9-32 |
| 稀疏 Map（idle=delete） | `packages/opencode/src/session/status.ts` | 26-48 |
| Runner 内态 / awaitDone | `packages/opencode/src/effect/runner.ts` | 33-37, 59-65, 171-202 |
| processor pipeline 顺序 | `packages/opencode/src/session/processor.ts` | 539-597, 599-625, 627-683 |
| retry policy/退避/action | `packages/opencode/src/session/retry.ts` | 14-24, 35-66, 176-199 |
| halt 两阶段持久化 | `processor.ts` + `session.ts` | 619 / 595-596 / session.ts:631-635 |
| HTTP status/abort | `.../handlers/session.ts` | 77-79, 232-235 |
| Web 乐观 busy（POST 前+回滚） | `packages/app/src/components/prompt-input/submit.ts` | 57-198 |

### 11.3 ocdroid 结构性代码点（4 explorer 核对，2026-07-30）
| 主题 | 文件 | 行 | 现状 |
|---|---|---|---|
| SessionListState 声明 | `ui/AppStateSlices.kt` | 714, 716 | sessionStatuses 在此 |
| StoreState 聚合 | `ui/StoreState.kt` | 28-35 | 单一复合聚合 |
| SharedStateStore（CAS） | `ui/SharedStateStore.kt` | 54-56, 90, 162-163, 250-252 | MutableStateFlow + dispatch→reduce |
| AppAction sealed | `ui/AppAction.kt` | 49, 664-668, 815-883 | SessionStatusPatched |
| reduceSessionStatusPatched | `ui/SessionListFieldsReducer.kt` | 62-71 | 裸 `+`，无护栏（path #5） |
| reduceSessionTreeHydrated | `ui/SessionListFieldsReducer.kt` | 108 | status 替换 |
| reduceHostStatePurged | `ui/CrossSliceFieldsReducer.kt` | 128 | host 切换清 sessionStatuses |
| bumpSessionUpdated | `ui/ViewModelSupport.kt` | 188-196 | optimistic 防覆盖 |
| 原始 mutateSessionList status 写 | `ui/controller/StatusPollOrchestrator.kt` 等 | 215,398/988/149/187/251 | 散落写入 |
| sessionStatuses 读点 | ~25 处 | 见 exp-3 | `.value` 快照 |
| StatusAggregatorImpl | `service/status/StatusAggregatorImpl.kt` | 96-102, 170-181, 386, 257, 207-209 | @Singleton 双接口 |
| StatusModule 注入 | `service/status/StatusModule.kt` | 53-74 | @Provides+@Binds |
| StatusAggregatorInput 接口 | `service/status/StatusAggregator.kt` | 162-219 | refresh/applySseStatus/markRequestFailed |
| SessionStatusKey | `service/status/SessionBusyStatus.kt` | 52-56 | composite key |
| SessionBusyStatus | `service/status/SessionBusyStatus.kt` | 28-34 | Fresh/Busy/Retry/Idle/Unknown |
| FORK POINT | `ui/controller/sse/LegacySseHandler.kt` | 130-169 | 双写无协调 |
| GlobalBusyState 消费者 | `ui/controller/StreamingLifecycleCoordinator.kt` | 837,881,1001,1185-1208,1470 | idle-debounce/handoff |
| SessionStreamingController | `ui/controller/SessionStreamingController.kt` | 150-159, 182, 438 | busy chronometer/bootstrap |
| ProcessStatusPoller | `ui/controller/ProcessStatusPoller.kt` | 255, 461-463, 472-473 | refresh/markRequestFailed |
| SlimStatusFanOut | `service/status/SlimStatusFanOut.kt` | — | 不喂聚合器 |
| launchSendMessage | `ui/SessionMutationActions.kt` | 312-441, 376-379, 418-432 | onSuccess 无 guard |
| dispatchSendMessage | `ui/AppCoreOrchestration.kt` | 939-960 | sendingSessionIds 置位 |
| abortSession | `ui/ChatViewModel.kt` | 409-425 | 无乐观/不清 |
| Composer canStop | `ui/chat/Composer.kt` | 164-165 | isBusy && !canSend |
| currentSessionIsRunning | `ui/chat/ChatScaffold.kt` | 583-597 | busy/retry ∪ sending |
| SessionStatus 模型 | `data/model/Session.kt` | 91-100 | 4 字符串字段，无 action |
| reduceSessionArchivedLocal | `ui/SessionListFieldsReducer.kt` | 28-44 | 不清 sessionErrorsById |
| reduceLastAssistantErrorAttached | `ui/ChatFieldsReducer.kt` | 234-250 | 静默丢弃 |
| archive 早返回 | `ui/controller/SessionListRefreshOrchestrator.kt` | 121-131, 195-198 | 跳 onLoadSessionStatus |
| slim 空目录 | `ui/controller/StatusPollOrchestrator.kt` | 310-316 | complete(true) |
| ConnectionIdentityStore | `service/identity/ConnectionIdentityStore.kt` | 119-129, 194-203, 238-244 | isCurrent/beginReconfigure |

### 11.4 相关规范
- `AGENTS.md` — 改动校验（`./scripts/check.sh`）、模拟器纪律、UI 样式
- `docs/specs/ui-style-spec.md` — UI overlay 三层规则（P2-A 须遵循）
- `.opencode/policies/build-signing.md` — 构建/校验规则

---

*方案结束。本方案为结构性权威重构（单一 SessionStatusAuthority + 双投影 + 两层 generation fence），9 章节 + slimapi 协作契约齐全；每个改动点附 `文件:行号 + 改法 + 风险 + 验证`；11 条竞态逐条覆盖（§7，R3/R4/R8 因单一权威源结构性根治）；interrupt/retry 分歧已澄清（§6）。下一步由编排者安排 rev-ogpt + rev-opus 独立评审。*
