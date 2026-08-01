# ocdroid 会话状态机架构规格（authority 模式）

> **状态**：**实施后规格（post-implementation spec）**——本文档反映 `main` 分支真实落地代码（HEAD `0871ca36`，含 P0-A/B/C/D/E/F + final-fix + slim 收官），由 5 路 explorer 核实 `file:line` 后沉淀。**不是**实施前计划（方案 v3 那份在 `docs/2026-07-30-ocdroid-state-machine-improvement-plan.md`，是历史快照）。
>
> **定位**：本文件是 ocdroid **会话状态（session status）权威机**的架构规格。它**互补而非重复** `./architecture.md`：`architecture.md` 覆盖整体分层 / 双 API 变体 / OCR 冻结门面，但**没有**状态机段落；本文件把 `./decomposition-guidelines.md` §4.4「纯 Reducer」先例（`SlimSseStateMachine` / `SseChatReducers`）扩展为 authority 这一更大尺度的实例化，并给出其设计理念、硬约束、fence 模型、扩展指引与实践教训。
>
> **读者**：任何触碰会话状态（status busy/idle、optimistic claim、SSE status 帧、REST status 快照、session lifecycle 归档）的开发任务（人或 agent）。改前必读 §3 硬约束与 §7 扩展指引。
>
> **相关规格**：`./architecture.md`（分层 / 不变量）、`./decomposition-guidelines.md`（§4.4 纯 Reducer 模式 + §5 单一权威）、`../2026-07-31-oc-slimapi-turn-token-contract.md`（Tier-1 fence 的跨项目契约）。规则冲突以 `.opencode/policies/` + 代码现状为准。

> ⚠️ **漂移标注（历史）**：本文档 7 类漂移已于 Batch 4 修订。修订详情见 git log commit 0871ca36+。

---

## 0. TL;DR（一分钟版）

| 项 | 结论 |
|---|---|
| 会话状态唯一权威 | `StoreState.authority: AuthorityState`（`StoreState.kt`，`StoreState` 类 `authority` 域）。所有 status 写入收敛到 `reduceAuthority(state, op)`（`AuthorityReducer.kt`，`reduceAuthority` 函数）单一纯 reducer。 |
| 单一 CAS 原子 | `authority` 切片 + `sessionStatuses` 投影 + `pendingBumps` + `authorityRevision` 在**同一个** `state.copy(...)` 提交（`reduceAuthority` 函数内 `state.copy(...)` 提交块），跑在 `MutableStateFlow.update` 的 CAS retry 内（`SharedStateStore.kt`，`dispatch` 函数）。无分裂窗口、无回滚。 |
| sole-writer gate | `SessionListState` 非 `data class` + `sessionStatuses` 为 `var private set` + 唯一写入者 `internal withProjection()`（仅 `reduceAuthority` 调用，`AppStateSlices.kt`，`withProjection` 函数）→ **编译期**强制。 |
| 两层 fence | **Tier-1（slim，强因果）**：`ServerRound(incarnation,turn)` 字典序严格单调 + per-scope incarnation high-water。**Tier-2（legacy，启发式 + 超时自愈）**：确认门 + 5s watchdog。字段缺失自动降级 Tier-2，逐事件独立裁决。 |
| 写入漏斗 | 16 个写入点全部收敛到 `reduceAuthority`：6 个走 `dispatch(AuthorityEvent)`，10 个在 `mutateState` / 其他 reducer 内**内联**调用 `reduceAuthority`（同一 CAS 内，非绕过）。**零绕过**。 |
| reducer 纯度 | `reduceAuthority` 无注入依赖、无 I/O、无时钟读、无输入变更、无 Dispatchers → 可安全跑在 CAS retry lambda 内（幂等）。 |
| 已知限制 | **BLK-2 残留**：基线清空后的**等 turn（`==`）**帧未镜像 wall-clock tie-break（两路径同源 `System.currentTimeMillis()` 单墙上钟域；BLK-2 守卫不镜像是**故意 fail-closed 选择**而非时钟域问题，见 `applyEvent` 内 BLK-2 guard 的 KNOWN RESIDUAL 注释 + §8.1）；严格低 turn 已闭合（`serverRoundHighWater`，commit `e7549e0`）。**detekt 已配**（`config/detekt/detekt.yml`，2 条自定义 sole-writer 规则：`SessionStatusDirectWriteRule` + `AuthorityDirectWriteRule`；`buildUponDefaultConfig=false` 仅跑自定义规则集；`./scripts/check.sh` default + 2 CI 变体均接入 `:app:detekt`，编译后、测试前执行）。**持久化未做**（authority 全内存，进程死即失）。 |

---

## 1. 设计理念（为什么必须这样设计）

本节讲「为什么」，不是「是什么」。核心理念：**消除会话状态的多源真相，把所有写入收敛到一个纯 reducer 在单一 CAS 内原子应用**。

### 1.1 单一权威源（R3：双源不同步）

重构前，会话 status 散落在两处真相：UI 投影（`sessionStatuses`）与 slim/aggregator 内部 `Aggregate`。两者各自更新，slim 落后 UI 数秒，导致 UI 显示 busy 而 slim 已 idle（或反之）的撕裂。

**根治**：所有 status 写入收敛到 `StoreState.authority` 切片（`StoreState.kt`，`StoreState` 类 `authority` 域）。`sessionStatuses` 不再是独立可写字段，而是 `authority` 的**纯投影**（`projectSessionStatuses`，`AuthorityReducer.kt`）。`StatusAggregatorImpl` 不再持有独立可写 `Aggregate`，而是 `DerivedStateFlow` over `store.state.authority`（`StatusAggregatorImpl.init` collect `authorityRevision`）。两处「真相」从根上变成一处真相 + 两处派生读。

### 1.2 纯 reducer + 单一 CAS（B1：CAS-in-lambda 是反模式）

**踩过的坑（B1）**：早期方案曾把 `authority.commit` 写进 CAS retry lambda 里——即「CAS-in-lambda」。这导致两个独立问题：
1. **重复执行**：CAS retry 会重跑 lambda，若 lambda 内有副作用（如 bump 计数器、写日志），重跑就累加。
2. **分裂窗口**：`authority` applied 了，但 store CAS 拒绝（被并发写者抢先）→ authority 变了、store 没变 → 投影与权威撕裂；回滚 authority 又需要额外机制。

**正确做法（ogpt option 1，已落地）**：`authority` 是 `StoreState` 的一个切片（`StoreState.kt`，`StoreState` 类 `authority` 域），`reduceAuthority` 是一个**纯函数** `(StoreState, AuthorityOp) -> StoreState`（`AuthorityReducer.kt`，`reduceAuthority` 函数）。整个 reduce 跑在 `MutableStateFlow.update { reduce(it, action) }` 的 CAS retry 内（`SharedStateStore.kt`，`dispatch` 函数）。CAS retry 重跑**纯 reducer** 是幂等的（`reduceAuthority` 函数 kdoc，`AuthorityReducer.kt`），无分裂窗口、无回滚、无副作用累积。这是「reducer 必须全纯」这一硬约束（§3）的根本理由。

### 1.3 执行代际 fence（R1：wall-clock 不能做因果）

会话状态的核心竞态：一个 stale 的 IDLE 帧覆盖了一个正在进行（optimistic busy）的会话，导致 UI 显示 idle 而实际 server 还在跑（retry 期 stop 消失）。legacy SSE 协议**不携带任何服务端因果标识**——每帧只有 `{sessionID, status}`，没有「这是哪一次执行轮次」。

用 wall-clock（`connectionTimeMs`，实为 `System.currentTimeMillis()` 经 `sseClock()` 传入；"Monotonic"为历史命名残留）做因果判断是**不可靠的**：墙上钟不能跨进程/跨重启可靠排序（NTP 跳变 / 设备休眠会让时间回退），且相同 wall-clock 的两个帧无法裁决。因此 ocdroid 引入**服务端派生的因果标识** `ServerRound(incarnation, turn)`（`AuthorityState.kt` → `data class ServerRound`）：
- `incarnation`：slimapi 生命周期 epoch，restart bump，跨 restart 单调。
- `turn`：per-`(serverGroupFp, sid)` 单调计数，每个执行轮次 +1。

ocdroid 用 `(incarnation, turn)` 字典序**严格单调比较**（`applyEvent` 内 live lex 守卫）：`<` 即 DROP。这是**数学可证的因果 fence**（Tier-1），不依赖 wall-clock。

但 legacy / optimistic / REST 路径**没有** turn token（`serverRound == null`），只能用**启发式确认门 + 超时自愈**（Tier-2）兜底。两层并存且分层（§5）。

### 1.4 分离计数空间（M1：client optimistic vs server）

客户端 optimistic 计数（`OptimisticClaim.clientSeq`，`AuthorityState.kt` → `data class OptimisticClaim`）与服务端计数（`ServerRound.turn`）是**两个永不比较的独立计数空间**。

**为什么不能混**：optimistic claim 是客户端 POST 成功后立刻占的「乐观 busy」，server 还没回任何 echo。若用 clientSeq 与 server turn 比数值，会因两个空间起点不同、步长不同而误判（M1 根因）。cross-channel reorder（server busy 经 SSE 先于 HTTP 200 到达）由 `optimisticClaim.serverEchoed` **布尔标志**协调（echo 时置位，非数值比较，`applyEvent` 函数内 Tier-2 确认门）。

这是 Tier-1（数值 lex）与 Tier-2（标志位确认）**协作但不混淆**的关键。

### 1.5 写入漏斗（10 条散落写入是结构性缺陷）

重构前，会话 status 有 ~10 条散落写入路径（SSE legacy status、SSE slim digest、REST 批量快照、REST 单点、optimistic-on-send、reconcile、archive、purge……），其中 path #5（optimistic）**无护栏**——POST 成功直接写 UI projection，无 guard。

**结构性根治**：定义 `sealed interface AuthorityOp`（`AuthorityOp.kt` → `sealed interface AuthorityOp`），8 个 typed variant（`ApplyEvent` / `ApplySnapshot` / `PurgeHost` / `MarkSourceFailed` / `ApplyReconcileOutcome` / `PruneSessions` / `RetryQueued` / `RetryFired`；后两个 P1-B/E 接线，详见 §8.5）。每条写入路径**构造一个 op → dispatch**，由 reducer 统一跑 guard 链（§3）后应用。散落写入消失，护栏集中在 reducer。

### 1.6 双投影派生（M3：aggregator 必须派生而非独立可写）

`StatusAggregatorImpl` 重构前**既 fetch 又 mutate 自己的 `Aggregate`**——耦合了网络 I/O 与生命周期投影，且留下第二处可写真相。

**真根治（M3）**：aggregator 的**读侧**改为 `DerivedStateFlow` over `store.state.authority`（`StatusAggregatorImpl.init` collect `authorityRevision` distinct → `publishFromState` → `authorityToAggregate` 纯映射，详见 §4.2）。旧的 public mutation API（`refresh` / `applySseStatus` / `markRequestFailed`）降级为**薄 adapter**：构造 `AuthorityOp` → `store.dispatch` → `publishFromState`（各 adapter 方法，`StatusAggregatorImpl.kt`）。aggregator 不再独立可写。

### 1.7 网络与归约分离（StatusFetchService 抽出）

`StatusFetchService`（`StatusFetchService.kt`，`StatusFetchService` 类）把 REST/slim 批量 status GET 从 aggregator 内抽出，成为**纯网络 seam**：只做 I/O（唯一不纯点），返回 `Result<StatusFetch>`，**不** mutate 任何 aggregator/store 状态。caller（aggregator 的 `refresh` adapter）决定如何处理结果。这让 aggregator 对 `OpenCodeRepository` 零依赖，且网络失败语义干净（`Result` 通道）。

---

## 2. 核心架构构造（数据 / 逻辑层）

### 2.1 `AuthorityState` —— 权威切片

**文件**：`AuthorityState.kt` → `data class AuthorityState`

```kotlin
data class AuthorityState(
    val bySid: Map<String, SessionEntry> = emptyMap(),
    val knownIncarnations: Map<ScopeKey, Long> = emptyMap(),
    val coverage: Map<ScopeKey, Coverage> = emptyMap(),
    val pendingBumps: Map<String, Long> = emptyMap(),
)
```

- **不可变**（全 `val`），**未** `@Serializable`（§8 持久化水位线）。
- **`bySid`**：per-sid 权威条目；**缺席 ≡ unknown**（fail-closed 语义——未知 sid 不假装 idle）。
- **`knownIncarnations`**：per-`ScopeKey` 的 incarnation 高水位（Tier-1 fence 基线）。
- **`coverage`**：per-`ScopeKey` 的全图覆盖簿记（注册/覆盖 workdir、未映射活跃 id、上次成功时间）。
- **`pendingBumps`**：per-sid 的 optimistic bump 时间戳，等在同一 CAS 内应用到 `sessionList.sessions`（未读数等）。

> `AuthorityState` 在 `StoreState`（`StoreState.kt`，`StoreState` 类）中作为 `authority` 切片（`StoreState` 类 `authority` 域），与 `connection` / `chat` / `sessionList` / `unread` / `host` 等切片并列。`StoreState` 另有 `authorityRevision: Long`（`StoreState` 类 `authorityRevision` 域，每次真 authority 转移 +1）作为 aggregator 的派生触发器。

### 2.2 `SessionEntry` —— 单会话权威条目

**文件**：`AuthorityState.kt` → `data class SessionEntry`。不可变。

| 字段 | 类型 | 语义 |
|---|---|---|
| `status` | `SessionStatus` | 投影源 status（`data/model/Session.kt`，`@Serializable`，含 `isIdle/isBusy/isRetry`）。 |
| `serverRound` | `ServerRound?` | slimapi `(incarnation,turn)` 强 fence 基线；legacy/optimistic/REST 为 null。 |
| `optimisticClaim` | `OptimisticClaim?` | Tier-2 optimistic 确认门（POST 成功、SSE busy echo 前）。 |
| `origin` | `EntryOrigin` | 该值如何到达（`OPTIMISTIC/SSE_LEGACY/SSE_SLIM/REST/TREE`，`AuthorityState.kt` → `enum class EntryOrigin`）→ 驱动 `ServerBusy` 分类。 |
| `updatedAtMs` | `Long` | TTL 计算与等-serverRound tie-break 时钟（墙上钟 ms，`System.currentTimeMillis()`；"Monotonic"为历史命名残留，U-MN9 step2 已改名）；**非**因果 fence。 |
| `serverRoundHighWater` | `ServerRound?` | BLK-2 闭合机制：per-sid 持久字典序最大 `(incarnation, turn)`，基线清空时保留（与 live `serverRound` 不同，后者会被清空）；只向前推进不回退；冷启动 null。详见 §8.1。 |
| `workdir` | `String?` | workdir 归属（`ApplySnapshot.sidToWorkdir` 填充/更新）。 |
| `scopeKey` | `ScopeKey?` | 该 entry 写入时的 scope；null 为向后兼容。 |

### 2.3 支撑值类型（均不可变）

| 类型 | 文件（符号锚） | 语义 |
|---|---|---|
| `ServerRound(incarnation: Long, turn: Long)` | `AuthorityState.kt` → `data class ServerRound` | `Comparable`，`compareValuesBy(incarnation, turn)` 字典序。服务端派生执行轮次身份。 |
| `OptimisticClaim` | `AuthorityState.kt` → `data class OptimisticClaim` | `clientSeq` / `claimedAtMs`（墙上钟 ms，U-MN9 step2 已改名；"Monotonic"为历史命名残留）/ `serverEchoed`（实时 SSE 置位）/ `reconcileConfirmed`（延迟 reconcile 置位，default false，**不跨代继承**——§9 #1 fix）/ `guardedIdleDrop`（确认门触发标记，供 watchdog 识别）。 |
| `ScopeKey(serverGroupFp, endpointFp, slimapiInstanceFp?)` | `AuthorityState.kt` → `data class ScopeKey` | 计数空间边界；incarnation high-water 与 turn 计数都 per-scope。 |
| `Coverage(registeredWorkdirs, coveredWorkdirs, unmappedActiveIds, lastSuccessTimeMs)` | `AuthorityState.kt` → `data class Coverage` | per-scope 覆盖簿记。 |
| `ReconcileOutcome` | `AuthorityOp.kt` → `enum class ReconcileOutcome` | enum：`IDLE_CONFIRMED` / `BUSY_CONFIRMED` / `FETCH_FAILED`。 |
| `RetryEntry(attempt: Int, backoffMs: Long, queuedAtMs: Long)` | `AuthorityState.kt` → `data class RetryEntry` | P1-B/E bounded retry queue 条目。`attempt`（1-based 重试计数）/ `backoffMs`（名义指数退避基，非 poller 实际延迟）/ `queuedAtMs`（入队墙上钟 ms，observability）。纯 bookkeeping，实际 retry 触发外部。 |

### 2.4 `AuthorityOp` —— typed sealed 写入层

**文件**：`app/src/main/java/cn/vectory/ocdroid/data/state/AuthorityOp.kt` → `sealed interface AuthorityOp`。8 variant：

| Variant | 载关键字段 | 用途 |
|---|---|---|
| `ApplyEvent` | `sid`, `status`, `origin`, `serverRound?`, `capturedIdentity?`, `identityEpochAtCapture`, `scopeKey`, `connectionTimeMs`, `workdir?`, `optimisticBumpTimestamp?` | **单帧 status 事件**（SSE status / digest / optimistic-on-send）。走完整 guard 链（§3）。 |
| `ApplySnapshot` | `snapshot: Map<String,SessionStatus>`, `sidToWorkdir`, `authoritativeNodeIds`, `registeredWorkdirs`, `coveredWorkdirs`, `unmappedActiveIds`, `partialFailureWorkdirs`, `lastSuccessTimeMs`, `scopeKey`, `requestToken`, `localBefore` | **全图权威快照替换**（REST/TREE，covered scope 内）。REST 在其 covered scope 内清 `serverRound`（epoch-terminal 权威）。 |
| `PurgeHost` | `scopeKey`, `preserveServerGroup: Boolean` | host purge：清该 scope 的 `bySid` / `coverage` / `knownIncarnations`（同 group `preserveServerGroup=true` 时 no-op）。 |
| `MarkSourceFailed` | `scopeKey`, `requestToken`, `monotonic`, `registeredWorkdirs` | REST 源失败：covered entry 变 fail-closed unknown（缺席）。 |
| `ApplyReconcileOutcome` | `sid`, `scopeKey`, `outcome`, `serverRound?`, `monotonic`, `claimClientSeq`, `hostProfileId?`, `identityEpochAtCapture` | REST reconcile 终态（watchdog / 显式 reconcile）。带 generation fence（`claimClientSeq` ABA）。 |
| `PruneSessions` | `sids: Set<String>`, `scopeKey` | 从 `bySid` 丢 sids（archive / delete lifecycle）。 |
| `RetryQueued` | `sid`, `scopeKey`, `attempt`, `backoffMs`, `queuedAtMs`, `identityEpochAtCapture` | **入队 bounded retry**（slim fan-out sweep 返 `retryableCount > 0` 时派发）。reducer 记 `RetryEntry` 到 `retryQueue`，纯 bookkeeping；`identityEpochAtCapture` 为 §U-CQ5 stale-identity guard。详见 §8.5。 |
| `RetryFired` | `sid`, `scopeKey`, `monotonic`, `identityEpochAtCapture` | **标记 retry 已外部派发**（poller re-sweep）。reducer 从 `retryQueue` 移除条目。详见 §8.5。 |

### 2.5 `reduceAuthority` —— 纯 reducer 契约

**签名**：`internal fun reduceAuthority(state: StoreState, op: AuthorityOp): StoreState`（`AuthorityReducer.kt` → `reduceAuthority` 函数）

**契约**：
1. **纯 guard**（在 `state` 上判定，无副作用）→ 决定 accept / drop（return `state` same-ref）。
2. **按 op 应用**（`when` 分派，`reduceAuthority` 函数内 when 分支）→ 产 `nextAuth: AuthorityState`。
3. **投影 + bumps 同 CAS**（`reduceAuthority` 函数内 `state.copy(...)` 提交块）：
   ```kotlin
   return state.copy(
       authority = cleanedAuth,
       authorityRevision = state.authorityRevision + 1L,
       sessionList = state.sessionList.withProjection(projection).copy(
           sessions = newSessions,
           abortPendingSessionIds = nextAbortPending,
       ),
       chat = state.chat.copy(
           pendingErrorCheck = state.chat.pendingErrorCheck + transitionedToIdle,
       ),
   )
   ```
   - `projection = projectSessionStatuses(nextAuth)`（`reduceAuthority` 函数内 `projectSessionStatuses` 调用点）—— `bySid.mapValues { it.value.status }`（`projectSessionStatuses` 函数，`AuthorityReducer.kt`）。
   - `newSessions = applyOptimisticBumps(state.sessionList.sessions, nextAuth.pendingBumps)`（`reduceAuthority` 函数内 `applyOptimisticBumps` 调用点）。
   - **一次 `state.copy` 提交全部** → 单 CAS 原子。

**全纯保证**（`AuthorityReducer.kt` `reduceAuthority` 函数 kdoc + 全文件核验）：无注入依赖、无 I/O、无时钟读（时间戳全在 op 内）、无输入变更（`data class copy` + map 替换非原地改）、无 Dispatchers。referentially transparent：同 `(state, op)` 必产同 `StoreState`。

---

## 3. 护栏与不变量（硬约束，不可违背）

### 3.1 guard 链顺序（`applyEvent` 内）

顺序**严格如下**，从外到内：

| # | Guard | 守卫在 `applyEvent` 内的位置 | 拒绝的竞态类 | 顺序理由 |
|---|---|---|---|---|
| 1 | **Scope/identity guard**（`opScopeValid`） | `opScopeValid` 函数（reducer 内被 `applyEvent` 调用） | stale 事件（host 切换 / identity reconfigure 后的迟到帧） | 最外层：scope 错的帧无论因果都拒，先省后续判断 |
| 2 | **incarnation high-water**（per-scope） | `applyEvent` 内 incarnation high-water 守卫 | slimapi restart 后旧 incarnation 帧复活（R6） | epoch 级 reject 先于轮次级：旧 incarnation 的任何 turn 都拒 |
| 3 | **`ServerRound` lex**（严格单调） | `applyEvent` 内 live lex 守卫（`cmp < 0` DROP / `cmp == 0` wall-clock tie-break） | 跨轮次 stale 帧复活（R3/R4）+ 同轮重复（R5 tie-break） | 因果精确裁决，仅当 `op.serverRound != null && prev.serverRound != null` |
| 4 | **确认门**（Tier-2） | `applyEvent` 内 Tier-2 确认门 | stale legacy IDLE 覆盖未确认 optimistic claim（R1 根因） | 仅当 `op.serverRound == null`：无因果 token 时的启发式兜底 |
| 5 | **no-change same-ref** | `applyEvent` 内 no-change 早返回 | CAS retry 幂等 / 等值重投 | 优化：data-class 相等且 pendingBumps 引用相等 → return same ref（无 emission） |

> **顺序的因果**：incarnation high-water（epoch 级）必须先于 lex（轮次级）——否则旧 incarnation 的低 turn 帧会先过 lex 才被 inc 拒，留下「apply 又 drop」的中间态。确认门（Tier-2）在 lex 之后，因为只有「无因果 token」时才需要它；有 token 走 Tier-1 因果精确，不需启发式。

### 3.2 sessionStatuses 唯一写入者（B10 sole-writer gate）

`SessionListState.sessionStatuses`（`AppStateSlices.kt`，`SessionListState` 类）的唯一写入路径：

```
reduceAuthority  (reduceAuthority 函数调用点，AuthorityReducer.kt)
  → projectSessionStatuses(nextAuth)  (projectSessionStatuses 函数，AuthorityReducer.kt)
  → state.sessionList.withProjection(projection)  (withProjection 函数，AppStateSlices.kt，internal)
  → result.sessionStatuses = projection  (withProjection 函数内 private set 写入，AppStateSlices.kt)
```

**编译期强制**（三重）：
1. `SessionListState` 是 **`class`**（非 `data class`），`internal constructor`（`AppStateSlices.kt`，`SessionListState` 类定义）→ 无法 `.copy(sessionStatuses=…)`。
2. `sessionStatuses` 是 **`var private set`**（`AppStateSlices.kt`，`SessionListState` 体内）→ 外部只读。
3. 手动 `copy()` 方法（`AppStateSlices.kt`，`SessionListState` 类体）**排除** `sessionStatuses` 参数 → `SessionListState(sessionStatuses=…)` 与 `.copy(sessionStatuses=…)` 均**编译失败**。
4. 唯一写入者 `internal fun withProjection(...)`（`AppStateSlices.kt`），仅 `reduceAuthority` 调用（`reduceAuthority` 函数调用点，`AuthorityReducer.kt`）。

> **AST 后备已就位**：`config/detekt/detekt.yml` 配了 2 条自定义 sole-writer 规则——`SessionStatusDirectWriteRule`（禁 `sessionStatuses =` 非 `withProjection` 调用）+ `AuthorityDirectWriteRule`（Batch 3 U-MN6，禁 `authority =` 直写）。`buildUponDefaultConfig=false` 意味着仅这 2 条自定义规则 active（默认规则集产生 1975 条历史违规，刻意 out-of-scope）。`./scripts/check.sh` 在 default + 2 CI 变体中均跑 `:app:detekt`（编译后、测试前）。B10 gate = 编译期三重机制（§3.2）+ detekt AST 后备双层。若有人新增第 17 个写入点绕过 `reduceAuthority`，编译期 gate 拦 `sessionStatuses` 直写，detekt 拦 `authority =` 与 `sessionStatuses =` 直写；其余靠 §6 文件索引 + §7 扩展指引 + code review 守。

### 3.3 CAS retry 幂等性（reducer 必须全纯）

`SharedStateStore.dispatch`（`SharedStateStore.kt`，`dispatch` 函数）跑 `state.update { reduce(it, action) }`，`update` 是 kotlinx `MutableStateFlow` 的 CAS retry 循环。`dispatchAndVerify`（`SharedStateStore.kt`，`dispatchAndVerify` 函数）显式 `while(true) { state.value → reduce → compareAndSet }`。

**硬约束**：跑在 CAS lambda 内的 reducer **必须全纯**——同输入必产同输出，无副作用累积。`reduceAuthority` 满足（§2.5）。**禁**在 reducer 内：bump 外部计数器、写日志、launch 协程、读 wall-clock、访问 repo/store。违反 = CAS retry 引入重复执行 / 分裂窗口（B1 教训，§9）。

### 3.4 持久化边界

**进 StoreState（权威态）**：`authority` 全部（`bySid` / `knownIncarnations` / `coverage` / `pendingBumps`）、`identityEpoch`、`authorityRevision` 等纯内存切片。

**不进**（进程级 / 瞬态）：UI 瞬态、`bootTimestamp`（实存于 `UnreadState.bootTimestamp`，`AppStateSlices.kt` → `data class UnreadState`，由 `UnreadSoakController` 初始化时写一次；**非 authority 切片字段**，属 unread 派生读侧的进程级水位线）、传输 generation stamp（`sseConnectedGeneration`）、导航 incarnation（`chatRouteInstance`）——这些都落在 StoreState 但**非 authority 切片**，且**全内存**。

**当前现实**（§8）：authority 全内存，无 `@Serializable`、无 DataStore/proto/SharedPreferences hook。进程死即全失，靠 SSE 重连 + REST 快照重建。`bootTimestamp`（`UnreadState.bootTimestamp`）非 authority 切片，不随 authority 持久化——其全内存语义同上。

### 3.5 dispatch 线程契约

`dispatch` **无** `withContext(Dispatchers.Main.immediate)` 包装——契约是**调用方约定**：caller 必须在 `Dispatchers.Main.immediate` 上（`SharedStateStore.kt`，`dispatch` 函数 kdoc）。`state.update` 本身线程安全（CAS），但「先读后写」的 mint-then-read pair 依赖调用方串行化。

---

## 4. 双投影与 aggregator 模式

authority 有两个派生读侧（都是**派生**，无可写真相）：

### 4.1 UI 投影：`sessionStatuses`

`sessionStatuses = projectSessionStatuses(authority) = authority.bySid.mapValues { it.value.status }`（`AuthorityReducer.kt` → `projectSessionStatuses` 函数）。**absence 语义**：未知 sid 不在 map 内 → 不假装 idle（fail-closed）。由 `reduceAuthority` 在同 CAS 内经 `withProjection` 写入（§3.2）。UI（`UnreadSoakController` 等）读 `sessionList.sessionStatuses` 决定 all-idle。

### 4.2 生命周期投影：aggregator `DerivedStateFlow`

`StatusAggregatorImpl.init` collect `store.stateFlow.map{authorityRevision}.distinctUntilChanged` → `publishFromState`（`StatusAggregatorImpl.kt` → `publishFromState` 函数）。`publishFromState` 在 `synchronized(publishLock)` 内**现算** Aggregate（`authorityToAggregate(state)` 纯映射）并交给 `publishLocked(agg, clock())`——**Aggregate 无独立 field**（是方法内局部变量，现算现传）。`@Volatile maxPublishedRevision: Long` 守卫抑制 stale publish：严格低 `authorityRevision` 的调用 no-op，等 rev 允许重派生（fresh clock for TTL）。

**派生三层**（`publishLocked` 函数 / `project` 函数，均在 `StatusAggregatorImpl.kt`）：
- `_statusByKey: StateFlow<Map<String, SessionStatus>>`（per-sid status）
- `_globalBusy: StateFlow<Boolean>`（any busy/retry/unmappedActive）
- `_globalState: StateFlow<GlobalBusyState>`（`Busy` / `AllIdleFresh` / `Unknown`，7 级裁决：unmappedActive > busy/retry > unknown > stale > 无 fresh coverage > 未全覆盖 > AllIdleFresh）

> `GlobalBusyState` 定义在 `service/status/StatusAggregator.kt`（`GlobalBusyState` enum）。只有 `AllIdleFresh` 允许 idle debounce。

### 4.3 网络层分离：`StatusFetchService`

`StatusFetchService.fetch(snapshot): Result<StatusFetch>`（`StatusFetchService.kt` → `fetch` 函数）—— 纯网络 seam（唯一不纯点 = I/O），返回 `Result<StatusFetch(statuses, failedWorkdirs)>` 数据载体。aggregator 的 `refresh` adapter 调它，把结果转 `ApplySnapshot` → `store.dispatch`（`StatusAggregatorImpl.kt`，`refresh` adapter）。aggregator 对 `OpenCodeRepository` **零依赖**。

---

## 5. 竞态覆盖与两层 fence 模型

### 5.1 guard → 竞态分类（R1–R11 对应）

| 竞态 | guard | 守卫在 `applyEvent` / reducer 内的位置 | 说明 |
|---|---|---|---|---|
| **R1** stale legacy IDLE 覆盖未确认 optimistic | 确认门 | `applyEvent` 内 Tier-2 确认门 | 核心竞态；POST 成功未 echo 时 IDLE 被 DROP + arm watchdog |
| **R2** ABA / superseded claim | generation fence（ApplyReconcileOutcome） | `applyReconcile` 函数内 generation fence | `claim.clientSeq != op.claimClientSeq` → DROP |
| **R3/R4** 跨轮次 stale 帧复活 | lex `cmp < 0` | `applyEvent` 内 live lex 守卫 `cmp < 0` 分支 | `(inc,turn)` 字典序严格旧 → DROP |
| **R5** 同轮重复帧 | lex `cmp == 0` + wall-clock tie-break | `applyEvent` 内 live lex 守卫 `cmp == 0` 分支 | 等 serverRound 但 `connectionTimeMs < prev.updatedAtMs` → DROP |
| **R6** slimapi restart 后旧 incarnation 帧 | incarnation high-water | `applyEvent` 内 incarnation high-water 守卫 | `inc < knownIncarnations[scope]` → DROP |
| **R7** restart 冻结（旧基线拒新帧） | incarnation advance → scope reset | `applyEvent` 内 incarnation advance 分支 | `inc > highWater` → bump + 重置该 scope entries 的 `serverRound=null` |
| **R8** stale REST snapshot after host switch | host guard（`opScopeValid`） | `opScopeValid` 函数（reducer 内） | `currentHost != token.hostProfileId` → DROP |
| **R9** stale 事件 after identity reconfigure | identity-epoch guard | `opScopeValid` 函数（reducer 内） | `identityEpochAtCapture != state.identityEpoch` → DROP |
| **R10** CAS retry 幂等 | no-change same-ref | `applyEvent` 内 no-change 早返回 | 同 entry + 同 pendingBumps ref → return same ref |
| **R11** host purge 后 scope 残留 | `PurgeHost` / `PruneSessions` scope-filter | `applyPurge` / `applyPrune` 函数（`AuthorityReducer.kt`） | 按 scope 清/滤，跨 scope 不误伤 |

### 5.2 Tier-1（slim，强因果）vs Tier-2（legacy，启发式 + 自愈）

**Tier-1**（数学可证，不依赖 wall-clock）：
- **lex `ServerRound` 比较**（`applyEvent` 内 live lex 守卫）+ **per-scope incarnation high-water**（`applyEvent` 内 incarnation high-water 守卫）。
- 触发条件：`op.serverRound != null`（slim digest 的 `turnIncarnation` + `turn` 均非 null，`SessionSyncCoordinator.handleSessionDigest` 解析）。
- `EntryOrigin.SSE_SLIM` 带 turn → Tier-1。

**Tier-2**（启发式 + 超时自愈）：
- **确认门**（`applyEvent` 内 Tier-2 确认门）：`op.serverRound == null && status.isIdle && claim 未确认` → DROP + arm watchdog。
- **watchdog**（`OptimisticClaimWatchdog.kt` → `runWatchdog` 函数）：扫 `authority.bySid` 找 `age > OPTIMISTIC_CONFIRM_TIMEOUT_MS`（5s）且 `!serverEchoed && !reconcileConfirmed` 的 claim → `SessionSyncCoordinator.reconcileStaleOptimisticClaims`（`SessionSyncCoordinator.kt`）dispatch `ApplyReconcileOutcome` → `applyReconcile`（`AuthorityReducer.kt`）置 `reconcileConfirmed` 或清 claim。
- **触发条件**：`op.serverRound == null`。含 `SSE_LEGACY`（无 turn）、`OPTIMISTIC`（构造即 null）、`REST/TREE`（`applySnapshot` 清 `serverRound`）、`SSE_SLIM` 缺字段。

**边界**：Tier-1 是逐帧精确裁决；Tier-2 是超时兜底（非无限 DROP，5s 后 REST reconcile 自愈）。两层**并存且分层**：有 turn 走 Tier-1，无 turn 走 Tier-2，逐事件独立。字段缺失自动降级 Tier-2，系统正常工作（字段缺失时的降级行为；slimapi 1.0.1 已发字段，正常路径走 Tier-1）。

### 5.3 为什么需要 watchdog 自愈

legacy / optimistic 路径**无因果 token**——确认门 DROP 一个 stale IDLE 后，claim 处于「未确认且不知真假」状态。若无自愈，claim 会永久卡住（UI 永远 busy）。watchdog 在 5s 后做一次 REST reconcile：GET 实际 status → 若真 idle 则清 claim（IDLE_CONFIRMED），若真 busy 则确认（BUSY_CONFIRMED），若 GET 失败则 FETCH_FAILED（移除 entry，fail-closed）。这是「启发式 DROP + 超时自愈」的完整闭环，而非无限 DROP。

---

## 6. 重点文件索引（导航地图）

### 数据层（`data/state/`）
- `AuthorityState.kt` —— 权威切片 + `SessionEntry` + `ServerRound`/`OptimisticClaim`/`ScopeKey`/`Coverage`/`RetryEntry` + `EntryOrigin`。
- `AuthorityOp.kt` —— `sealed interface AuthorityOp`（8 variant）+ `RequestToken` + `ReconcileOutcome`。

### 逻辑层（`ui/`）
- `AuthorityReducer.kt` —— **核心纯 reducer**（`reduceAuthority` + `applyEvent`/`applySnapshot`/`applyPurge`/`applyPrune`/`applyReconcile`/`applyMarkFailed`/`applyRetryQueued`/`applyRetryFired` 等 `applyXxx` + guard 链 + `projectSessionStatuses`）。
- `StoreState.kt` —— 根状态；`authority` 切片（`StoreState.kt`，`StoreState` 类 `authority` 域）+ `authorityRevision`（`StoreState` 类 `authorityRevision` 域）。
- `AppStateSlices.kt` —— `SessionListState`（非 data class + `withProjection` sole-writer gate，`AppStateSlices.kt`）。
- `AppAction.kt` —— `AppAction.AuthorityEvent(op)` 唯一漏斗（`AppAction.kt`）→ `reduce` 分派。
- `SharedStateStore.kt` —— CAS 机制（`dispatch`/`dispatchAndVerify`，`SharedStateStore.kt`）+ Main.immediate 线程契约。
- `SessionListFieldsReducer.kt` / `CrossSliceFieldsReducer.kt` —— 跨切片 reducer 内**内联**调 `reduceAuthority`（Prune/Purge）。

### 服务层（`service/`）
- `service/status/StatusFetchService.kt` —— 纯网络 seam（`fetch → Result<StatusFetch>`）。
- `service/status/StatusAggregatorImpl.kt` —— `DerivedStateFlow` over authority + 三层 StateFlow + 薄 adapter。
- `service/status/StatusAggregator.kt` —— `GlobalBusyState` enum。

### 接入层（写入漏斗的外部触发点）

> 16 个写入点的完整分布：本节列**外部触发点**（service/controller 直调）；其余为 reducer/adapter 内**内联**调用（见 §6.2 逻辑层的 `SessionListFieldsReducer` / `CrossSliceFieldsReducer` 与 §6.4 服务层的 `StatusAggregatorImpl` adapter）。全量 16 点清单见 §1.5 与 explorer 核实记录。
- `service/streaming/SessionStreamingController.kt`、`service/streaming/ProcessStatusPoller.kt` —— 流式/轮询 SSE status。
- `ui/controller/sse/LegacySseHandler.kt`、`ui/controller/sse/SseDispatchHost.kt` —— SSE 分派（`applyStatusViaAuthority`）。
- `ui/controller/SessionSyncCoordinator.kt` —— digest 解析（turn token）+ watchdog reconcile。
- `ui/controller/BackgroundUnreadPoller.kt`、`ui/controller/StatusPollOrchestrator.kt` —— REST 快照轮询。
- `ui/SessionMutationActions.kt` —— optimistic-on-send。
- `ui/SessionListActions.kt` —— session tree hydrate。

### gate / 自愈
- `ui/controller/OptimisticClaimWatchdog.kt` —— 5s 超时扫 stale claim → reconcile。

### 契约（跨项目）
- `docs/2026-07-31-oc-slimapi-turn-token-contract.md` —— Tier-1 turn token 的 slimapi×ocdroid 契约（已落地，2026-07-31）。

---

## 7. 扩展指引（给后续开发者）

### 7.1 新增一个 status 写入路径

**正确方式**：
1. **定义或复用** `AuthorityOp` variant（`AuthorityOp.kt`）。新语义才加 variant；现有 8 variant 覆盖大部分场景。
2. 在写入点**构造 op**（带齐 `scopeKey`、`capturedIdentity` + `identityEpochAtCapture`、`serverRound` 若有）。
3. **dispatch**：`store.dispatch(AppAction.AuthorityEvent(op))`（首选，跑完整 CAS）。若写入点已在 `mutateState { }` 或其他 reducer 内，可**内联** `reduceAuthority(state, op)`（同 CAS，非绕过）。
4. reducer 跑 guard 链 → 应用 → 同 CAS 投影。
5. **禁**直接 `state.copy(authority = …)` 或写 `sessionStatuses`（编译期 gate 会拦后者；前者靠 review + §6 索引守）。

**反模式**：在 service/controller 里直接改 UI projection、绕过 op 构造、自造第二处 status 真相。

### 7.2 新增一个状态字段

1. **权威态** → 加到 `AuthorityState` 或 `SessionEntry`（`val`，带默认）。
2. **派生态**（可由 authority 算出）→ 加到投影/`Aggregate`，**不**加到 authority。
3. **进程级瞬态**（非会话状态）→ 加到 StoreState 其他切片，**不**进 authority。
4. 改完跑 `./scripts/check.sh`（编译 + 单测）。
5. 若新字段影响 `GlobalBusyState` 裁决，同步改 `project()` 函数（`StatusAggregatorImpl.kt`）。

### 7.3 何时引入新的 incarnation scope / turn

- **新 scope**：仅当出现一个新的「计数空间边界」（不同 server group / 不同端点 / 不同 slimapi 实例）时才加 `ScopeKey` 维度。不要为单会话加 scope。
- **新 turn**：turn 是服务端派生（slimapi 在 forward 送出时 increment）。ocdroid **只消费**，不 mint turn。若需要新因果标识，先评估能否复用 `ServerRound`，再考虑 slimapi 契约扩展（见 turn-token 契约 §10）。

### 7.4 测试门槛

1. **`./scripts/check.sh`**（必做）：编译 + `testDebugUnitTest`。
2. **`AuthorityOp` × `SessionEntry` 穷举矩阵**：新 op 或新 guard 必须有针对 `applyEvent` 的 guard 链穷举测试（参考既有 `AuthorityReducer` 测试）。
3. **场景测试**：新竞态类要 failing-first 回归测试（先写会红的，改后转绿）。
4. **detekt**：**已配置**（`config/detekt/detekt.yml`，2 条自定义 sole-writer 规则）——`./scripts/check.sh` 已接入 `:app:detekt`，与编译期 gate + review 三层共守 sole-writer。

### 7.5 reducer 纯度自检清单

改 `AuthorityReducer` 前，逐条确认 reducer 仍可放进 CAS retry lambda 而无副作用：
- [ ] 无注入依赖（repo / store / lock / logger）？
- [ ] 无 I/O（网络 / 文件 / DB）？
- [ ] 无时钟读（`System.*` / `elapsedRealtime`）？时间戳全在 op 内？
- [ ] 无输入变更（`copy` + map 替换，非原地改）？
- [ ] 无 Dispatchers（`withContext` / `launch`）？
- [ ] 同 `(state, op)` 必产同 `StoreState`（referentially transparent）？

任一不过 = CAS retry 会引入重复执行 / 分裂窗口（B1，§9）。

---

## 8. 已知限制与演进方向（诚实记录）

### 8.1 BLK-2：基线清空后的低 turn 复活窗口（**已于 `e7549e0` 闭合**）

**原始缺口**：`applyEvent` 内的 live lex 守卫要求 `prev?.serverRound != null`。当 Tier-2 事件（无 turn）把某 entry 的 `serverRound` 基线清成 null（REST `ApplySnapshot` / incarnation-advance scope reset；§U-P3 起 legacy SSE busy 经 `keepRound` **不再**清基线），随后到达一个**有 turn 但 turn 较低**的 Tier-1 帧 → lex 守卫跳过、确认门也跳过（确认门需 `op.serverRound == null`）→ 该低 turn 帧**直接 apply**，stale busy 可能复活。

**闭合机制**（commit `e7549e0`，`SessionEntry.serverRoundHighWater` 字段）：per-sid 持久字典序最大 `(incarnation, turn)`，**基线清空时保留**（与 live `serverRound` 基线不同，后者会被清空；high-water 只向前推进，不随基线清空回退）。冷启动时为 null（首个 slim 帧建立基线）。

**守卫**（`applyEvent` 内 BLK-2 watermark guard，`op.serverRound < prev.serverRoundHighWater` → **DROP**，严格低 turn，fail-closed）：此为**主复活向量**（严格低 turn 跨通道重排）的确定性闭合。reach 此守卫前，`applyEvent` 内的 per-scope incarnation high-water 守卫已 DROP 掉旧 incarnation 帧，故 `<` 命中必为同 incarnation 的 stale 低 turn。

**已知残留（诚实记录）**：equal-turn（`==`）**未 DROP**（守卫仅 `<`）。一个 baseline clear 后到达的等 turn 帧会被接受以重建基线。live lex 守卫有 `cmp == 0` 的 wall-clock tie-break（`op.connectionTimeMs < prev.updatedAtMs` → DROP），但 BLK-2 守卫**未镜像**此 tie-break。

> ⚠️ **clock 域结论（U-P3 / U-MN9 修正）**：两时间戳**同源 `System.currentTimeMillis()`（单墙上钟域）**——`updatedAtMs` 在 REST `ApplySnapshot` 路径由 `requestToken.requestStartMs`（`clock()` ← `currentTimeMillis`）设，在 SSE `applyEvent` 路径由 `op.connectionTimeMs`（`sseClock()` ← `clock()` ← `currentTimeMillis`）设。**比较本身有效（单域），非跨域比较。** BLK-2 守卫不镜像 `==0` tie-break 的**真实原因**是**故意的 fail-closed 选择**（`applyEvent` 内 BLK-2 guard 的 KNOWN RESIDUAL 注释明示）："a stale equal-turn digest arriving during a brief baseline window is the lesser evil versus a fail-open rejection of a legitimate equal-turn re-establishment"——即宁可接受一个短暂基线窗口内的 stale equal-turn（fail-closed 方向：stale busy 短暂复活是较轻危害），也不冒 fail-open 误拒合法 equal-turn 重建的风险。

**主复活向量（严格低 turn）已闭合；equal-turn 窗口为已知残留。** 演进方向：若未来需闭合 equal-turn 窗口，可在单墙上钟域内安全镜像 `==0` tie-break（墙上钟在设备休眠/NTP 跳变下仍有乱序风险，但远低于已废弃的双时钟域前提；真正的根治需统一到单调钟如 `SystemClock.uptimeMillis()`，见 §8.5 backlog MN-P9）。

### 8.2 持久化水位线搁置

authority 全内存（§3.4）。为何不做：
1. **reducer 纯度**：持久化需异步 I/O（DataStore），与「reducer 全纯、跑 CAS retry」冲突——持久化放进 reducer 就破坏幂等性。
2. **竞态**：DataStore 异步写与 CAS retry 之间有竞态（写途中状态又变）。
3. **可推导性**：authority 是 server 会话状态的派生视图，进程死后靠 SSE 重连 + REST 快照可重建，非必须的客户端缓存。

演进：若要持久化，需把持久化移出 reducer（如 reducer 只产 op，副作用层异步持久化），或用 `providers.exec`/`ValueSource` 在 Gradle 配置期派生（与版本号同模式）。列为 P2。

### 8.3 slimapi turn token 已落地（待实机联调验证）

Tier-1 fence 的**消费侧已就绪**（`0d572d2`，解析 + 降级 merged）+ **生产侧（slimapi）已发字段（1.0.1）**，serverGroupFp header 已注入（ocdroid 0.18.3）。turn-token 契约 §10.1 S1–S9 已实现：
- 发 `turnIncarnation` + `turn` 在 `session.digest` 的 `data` **flat 顶层**（与 `sessionID`/`status` 同层；ocdroid 的 `properties` = 整个 `data`，flat 顶层可读，解析无需改）；
- turn commit point = forward 送出上游时（**send 前 bump**：`await send()` 之前 `turn += 1`，httpx-stream 单 await 栈下唯一合规路径）；
- incarnation 单独持久化（`persisted_last + 1`）+ restart bump；
- 事件 ingest 时快照 turn（非 flush 时读当前）；
- serverGroupFp 对齐：**方案 A 落地**（`X-Ocdroid-Server-Group-Fp` header 透传，ocdroid 0.18.3 已注入）。

> 剩余：实机联调验证契约 §11 验收场景（restart 不冻结 / 旧帧 DROP / abort fencing / 跨通道反序等）。**wire 权威**在 oc-slimapi `docs/specs/v2-contract.md` §3「Turn token fence」；因果语义 / 不变量 SSOT 在 `docs/2026-07-31-oc-slimapi-turn-token-contract.md`。

### 8.4 detekt 已配置（sole-writer AST 后备）

detekt 自定义 sole-writer 规则**已实现**（`config/detekt/detekt.yml`）：`SessionStatusDirectWriteRule`（禁 `sessionStatuses =` 非 `withProjection` 调用）+ `AuthorityDirectWriteRule`（Batch 3 U-MN6，禁 `authority =` 直写）。`buildUponDefaultConfig=false`——仅自定义规则 active，默认规则集（1975 条历史违规）刻意 out-of-scope。`./scripts/check.sh` default + 2 CI 变体 + `scripts/ci/remote-check.sh` + `scripts/ci/release-check.sh` 均接入 `:app:detekt`。B10 gate = 编译期（§3.2）+ detekt AST 双层。

### 8.5 P1/P2 backlog 方向

- **已接线（P1-B/E）retry queue**：`AuthorityState.retryQueue`（`Map<sid, RetryEntry>`，LRU 严格上限 `RETRY_QUEUE_MAX_SIZE=256`，reducer 纯 bookkeeping）现已接入真实 dispatch：
  - **入队** `RetryQueued`：`SessionSyncCoordinator.applySlimStatusFanOutSummary`（`SessionSyncCoordinator.kt`，`applySlimStatusFanOutSummary` 函数）——当 slim fan-out sweep 返回 `retryableCount > 0` 时，对每个 `StatusOutcome.Retry` sid 派发 `RetryQueued`（attempt 从既有条目递增，backoffMs 用纯函数 `exponentialBackoffMs` 算 nominal base 并 clamp 到 `BACKOFF_MAX_MS`——jitter 由 poller 外部施加，队列记录确定性策略）。每个 Retry sid 无条件入队（语义 = "pending confirmed status"，非 "busy needing retry"；一个 idle session 的 fetch 返 503 是合法重试）。
  - **出队** `RetryFired`：同方法开头——sweep 覆盖到的既有 queued sid 派发 `RetryFired`（re-sweep = retry attempt dispatched）。
  - **terminal 清理**（全路径，rev-ogpt B4）：`applyEvent`（idle/failed，含 incarnation-advance + no-change 早返回路径）/ `applySnapshot`（busy→idle transition）/ `applyReconcile`（IDLE_CONFIRMED + FETCH_FAILED）/ `applyPrune`（delete/archive）/ `applyPurge`（cross-group host switch 清空整个队列，rev-ogpt B2）均清理对应 sid 的 retry 条目。
  - **严格 LRU 上限**（rev-ogpt B1 / rev-gpt）：`applyRetryQueued` 用 while-loop 淘汰直到 ≤256；new entry 自淘汰时返回 same-ref no-op（无伪 transition）。
  - **可观测性** `retryQueueFlow`：`SharedStateStore.retryQueueFlow`（`SharedStateStore.kt`，`DerivedStateFlow(state) { it.authority.retryQueue }`）——lag-free 派生读侧，不新增可写真相。单测在 `AuthorityReducerTest`（入队 / 严格 LRU / 幂等 / 自淘汰 no-op / 出队 / 全路径 terminal 清理 / B2 purge / Flow emission）+ `RetryQueueWireTest`（coordinator 接线级 10 cases）。
  - **reducer 纯度**：接线只加外部 dispatch，**未**向 reducer 注入 clock/scheduler/repository（§7.5 纯度红线保持）。
  - **已知残留（rev-gpt 终审，诚实记录）**：stale fan-out summary 在 terminal event 之后 dispatch RetryQueued 会重入（B3 terminal-status fence 被 rev-gpt 正确否决——它误杀了 idle session 的正常 503 重试）。跨 host purge 后旧 host 的 stale dispatch 也会重入。两者自愈（下次 sweep RetryFired / LRU 淘汰 / 后续 terminal 清理），根治需 sweep-generation / request-token 因果栅（op 当前无因果信息），列为 backlog（见下）。
- **P1/P2 backlog**：retry queue stale-dispatch 重入根治 —— 需向 `RetryQueued`/`RetryFired` 加 sweep-generation / request-token 因果栅，使 reducer 能区分「terminal 后的 stale summary」与「terminal 后真实发生的查询失败」。当前无因果信息可用。
- **P1/P2**：BLK-2 equal-turn 残留 —— baseline clear 后到达的等 turn 帧未镜像 `==0` wall-clock tie-break（两路径已同源 `System.currentTimeMillis()` 单墙上钟域；不镜像是故意 fail-closed 选择，详见 §8.1）。演进：若需闭合 equal-turn 窗口，可在单墙上钟域内安全镜像（墙上钟在 NTP/休眠下仍有乱序风险，真正根治需统一单调钟，见 MN-P9）。
- **P1**：C5 header 透传 serverGroupFp（消除 fp 漂移风险）。
- **P2**：authority 持久化（需 reducer 副作用外移架构）。
- ~~**P2**：detekt sole-writer 规则。~~（**已落地**，见 §8.4）
- **P2**：REST `/session/status` 携带 turn（需改 `ApplySnapshot`，契约 §8.4 O5）。

---

## 9. 实践教训（从重构过程提炼，防后人重蹈）

### 9.1 B1 分歧桥接：CAS-in-lambda 是错的

**坑**：早期把 `authority.commit` 写进 CAS retry lambda。后果：CAS retry 重跑 lambda → 副作用累积 + authority applied 而 store CAS 拒绝 → 分裂窗口 + 需回滚。

**正确（ogpt option 1，已落地）**：authority 是 StoreState 切片，`reduceAuthority` 是纯函数，整个 reduce 跑在 `state.update { reduce(it, action) }` 的 CAS 内。CAS retry 重跑**纯 reducer** 幂等，无分裂、无回滚。

**教训**：任何「在 CAS lambda 内 commit 独立状态机」的方案都是反模式——要么把状态并进 CAS 保护的 state（切片化），要么用纯函数 + 外部副作用层分离。reducer 全纯是不可妥协的硬约束（§3.3）。

### 9.2 跨代污染（#1）：`reconcileConfirmed` 必须与 `serverEchoed` 分离

**坑**：早期 `OptimisticClaim` 只有一个确认标志。若 gen-1 被 watchdog `reconcileConfirmed`，gen-2（新 POST）继承该标志 → watchdog 跳过 gen-2 → stale IDLE 覆盖 gen-2。

**修复（已落地，`AuthorityState.kt` → `data class OptimisticClaim`）**：拆成 `serverEchoed`（实时 SSE echo 置位，**可跨代继承**——同 session 的 echo 对新 POST 仍有效）+ `reconcileConfirmed`（延迟 reconcile 置位，**硬编码 false 不跨代继承**，`applyEvent` 内新 claim 构造）。确认门读「任一为 true」（`applyEvent` 内 Tier-2 确认门）；新 claim 永远从 `reconcileConfirmed=false` 开始。

**教训**：不同确认通道（实时 echo vs 延迟 reconcile）的语义不同，不能用一个布尔位合并——尤其是「延迟到达的确认」绝不能跨代继承，否则污染下一代。每个 generation 必须有干净的 watchdog 起点。

### 9.3 评审熔断：连续失败切节点而非 self 兜底

**教训**：重构中连续 N 次评审失败时，正确做法是**切评审节点**（换模型/换 reviewer），而非 self 兜底（自己改自己评）。self 兜底会固化盲区。REVIEW_CHAIN 设计（rev-glm → rev-ogpt → rev-gpt → BLOCKED）正是此教训的制度化：连续失败升级，不循环。

---

## 10. 何时更新本文件

- **更新**：authority 数据模型 / reducer 契约 / guard 链顺序 / fence 模型发生**语义变化**（新 op variant、新 guard、新 fence tier、持久化落地、detekt 配上）。
- **不更新**：某次重构的行号漂移、阶段性计划进度（那些进 `docs/ocmar/plans/`）。
- **核实纪律**：更新前必须经 explorer 核实 `file:line`（本文档定位为「实施后规格」，不照抄任何「实施前计划」）。
