# ocdroid 会话状态机：结构性权威重构方案（v2 返修版）

> **状态**：**v2 返修版，已解决 M1-M10**（v1 经 rev-ogpt + rev-opus 双评审否决，返修核心实现模型自洽性；方向保留）。全新体系方案（**未实现**）。
> **证据基线（固定）**：
> - 上游 `opencode-src/v1.18.7/`（**禁用** `current` 软链；上游服务端**不可改**，所有 fence 在 ocdroid 客户端实现）。
> - ocdroid `app/src/main/java/cn/vectory/ocdroid/`。
> - oc-slimapi（`/home/mar/personal_projects/oc-slimapi`，用户自建 Python 中继层）可改——是强 fence 的重要增强路径（跨项目协作，见 §5）。
> **日期**：2026-07-30（v2 返修：2026-07-30）。**bundle**：B-ocdroid-sm-20260730。
> **协议定位**：ocdroid 消费上游 **V1 线协议**（`session.status` / `session.digest` / `session.error` / `message.part.*` / `GET /session/status`）。V2 执行事件仅作背景。
> **审计**：v1 读 3 份输入材料全文 + 4 个 explorer；**v2 返修**读 v1 方案全文 + 双评审综合报告（M1-M10）全文，并派 **2 个 explorer**（exp-1 ses_05001edeaffe：M9 sid 唯一性 + M6 原子提交形状 + M9 purge 语义；exp-2 ses_05001ca64ffe：M8 锁/线程模型 + P0-C identity 接口）核对新增代码点。所有 `文件:行号` 经 explorer 二次确认。
> **方向约束（omni 确认，不可变）**：①纯客户端改进；②oc-slimapi 可辅助强 fence；③结构性收敛（权威重构，非最小改动），目标**清晰易维护**的新体系；④通过后即实施，可执行性是硬要求。

---

## v2 变更说明（相对 v1 的关键修订）

v1 的**诊断层（§1）、架构方向（单一权威源 + 双投影 + 两层 fence）、§6 interrupt/retry 结论、§10 否决项、§11 索引**保留。v2 重写**核心实现模型**以解决双评审 M1-M10。逐条对应：

| 评审项 | v1 问题 | v2 修订（节） |
|---|---|---|
| **M1 generation 空间冲突**（最严重） | client bump + slimapi turn + restart 重置塞同一 Long 空间，严格单调比较 → restart 后永久冻结 | **分离计数空间**：`ServerRound(incarnation, turn)`（lexicographic 比较）+ `OptimisticClaim(clientSeq)` 独立空间；incarnation advance 协议重启基线；cross-channel reorder 由「server-echoed」标志解决。§3.1、§2.2 |
| **M2 bulk API 缺失** | 单一 `submit(StatusUpdate)` 无法表达 REST/tree 整图替换、partial-coverage、原子 replace/remove | **typed `AuthorityOp` sealed 层级**（ApplyEvent / ApplySnapshot / PurgeHost / MarkSourceFailed / ClearOptimistic），替换单一 submit。§2.2 |
| **M3 aggregator 旁路写入** | `refresh`/`markRequestFailed` 绕 authority；coverage 只由 refresh 写；applySseStatus 写 `fresh=false` → 幻影 busy 永久钉死 GlobalBusyState | **aggregator 改为 authority 的纯响应式投影**（entries 派生）；mutation API 移出公共接口，调用方全经 authority；GlobalBusyState 分层（ServerBusy vs OptimisticBusy）。§2.3、§4 P0-A2 |
| **M4 P0-D hoist 读未提交** | hoist 在 archive 提交前，读局部变量 mergedSessions（旧 tree）；每次 reload 触发非一次性 | reconcile 接收**已 merge 的 sessionIds 作参数**（不重读 tree）+ cold-start 一次性门。§4 P0-D |
| **M5 P0-E 队列丢 payload** | `pendingErrorReattach: Map<String,Long>` 不存 error；旧 route instance 不可作回归凭证；无 messageID 关联 | 队列存 `PendingError{messageID, errorPayload, routeInstance, enqueuedAt}`；drain 触发点/上限/LRU；durable GET 恢复链；旧 error 仅附同 messageID。§4 P0-E |
| **M6 原子迁移被撕坏** | BackgroundUnreadPoller/Hydrator/Poller 原子提交 sessionStatuses+tree+epoch，剥离 status 异步投影破坏原子性 | **`commit(op)` 同步返回 projection**；原子调用方把 projection 纳入自己的 `store.mutateState` CAS；epoch guard 共享 requestToken。§2.2、§4 P0-A3、新增 §4a |
| **M7 slimapi turn flush 时读当前** | 250ms debounce 把旧事件标成新 turn；increment commit point 未定义；proxy/hub 无共享 registry | turn 在**事件 ingest 时快照**进 DigestFields；increment commit point = slimapi forward POST/abort 时；共享 registry key=(serverGroupFp, sid)。§5 |
| **M8 持锁发布 / 重入死锁** | `synchronized(commitLock){ publishProjections(); forwardToAggregator() }` 持锁 dispatch + 跨锁 | **commit 纯函数（持锁只做 AtomicReference 更新）**；publication（dispatch + aggregator）**deferred 到锁释放后**，单一 Main.immediate collector；锁序单向 authority→aggregator，无反向。§2.2、新增 §4b |
| **M9 absence/scope/purge 不一致** | 整图替换抹掉 REST normalize 的 explicit-idle 改 unread 判定；authority bySid vs aggregator composite key 模型不一；purge 无条件清 | **projection = bySid.mapValues**（保留所有已知 sid → absence 仍=未知，fail-closed 保留）；I-SID-UNIQUE 不变量；`PurgeHost(preserveServerGroup)` 精确镜像 reduceHostStatePurged。§2.3、新增 §4c |
| **M10 测试门槛不足** | 仅 check.sh | 静态架构 gate（编译期禁业务代码赋值 sessionStatuses / 禁调 aggregator mutation API）+ 状态转换穷举 + 跨通道反序 + slimapi debounce/restart + partial 失败 + host switch + 投影一致性。§4 各 P0 验证 + 新增 §4d |

> **三条核心阻断（M1/M3/M2）的设计枢纽**：v1 把「增量轮次事件」与「整图权威替换」与「aggregator 自有 REST」都往一个 `submit(StatusUpdate, generation: Long?)` 里塞，既无法表达整图语义（M2），又让 client bump 与 server turn 撞同一 Long 空间（M1），还声称根治 R3 而 aggregator 仍可被旁路写入（M3）。v2 的根本动作是 **(a) 按操作类型拆分 authority API（M2），(b) 在增量事件操作内用分离的 (incarnation,turn)+claim 空间（M1），(c) 让 aggregator 成为 authority 的纯响应式投影、mutation API 移出公共接口（M3）**。三者环环相扣，共同构成自洽的单一权威模型。

---

## 0. 阅读约定与术语

| 术语 | 含义 |
|---|---|
| **SessionStatusAuthority**（新） | 本方案引入的**单一权威源**。所有 status 写入的唯一入口；内部执行全部护栏；对外投影 UI 态与生命周期态。见 §2。 |
| **AuthorityOp**（新，v2） | authority 写入入口的 **typed sealed 层级**：`ApplyEvent`（增量单 sid 事件）/ `ApplySnapshot`（整图权威替换）/ `PurgeHost` / `MarkSourceFailed` / `ClearOptimistic`。替换 v1 单一 `submit`。见 §2.2。 |
| **ServerRound(incarnation, turn)**（新，v2） | 服务端权威的**执行轮次身份**，lexicographic 比较。`incarnation`=opencode/slimapi 生命周期 epoch（restart 跳变）；`turn`=per-(session,incarnation) 单调计数。仅服务端源（slim digest 带 turn、REST 不带）设置。**与客户端 claim 空间分离**（M1）。 |
| **OptimisticClaim(clientSeq)**（新，v2） | 客户端乐观轮次的**独立计数空间**。`clientSeq` per-(client session) 单调，**从不与 server turn 比较**。`serverEchoed` 标志服务端是否已确认该轮次。 |
| **确认门（confirmation gate）** | legacy 路径（无 turn）的**启发式**因果 fence：optimistic 未被 server echo 前，屏蔽 incoming idle；**有 liveness 超时自愈**（非无限 DROP）。诚实定位：legacy 无可靠因果 fence，强 fence 需 slimapi turn。 |
| **投影（projection）** | 从权威源派生的只读视图。UI 投影（sid-keyed `sessionStatuses`）与生命周期投影（aggregator → `GlobalBusyState`）均从权威源派生，根治双源不同步。 |
| **dual-source（被根治）** | 现状：UI 源 `sessionStatuses` 与生命周期源 `StatusAggregatorImpl.entries` 独立写入、不同步。新体系收敛为单一权威源；aggregator 改为其纯响应式投影（v2 M3）。 |
| **durable error** | 持久化在 assistant 消息上的 `Message.error`。失败回合的持久真相，跨重启/重载均在。 |
| **I-SID-UNIQUE**（新不变量，v2） | OpenCode 服务端 session id（UUID）在 server group 内全局唯一；codebase 现状已隐含假设（`allSessionsById` 用 `putIfAbsent`）。aggregator 的 composite key 保留为防御性。见 §4c。 |

---

## 1. 现状问题诊断（综合 main-A/B + 4 explorer）

### 1.1 根因：双权威源 + 缺执行代际 fence（结构性缺陷）

ocdroid 现状有**两个并行的独立权威视图**，且**同 identity 内缺执行代际 fence**。这是三个用户反馈的共同结构性根因。

**双权威源拓扑**（exp-3/exp-4 核对）：

```
SSE session.status 帧到达
  ├─→ LegacySseHandler.kt:135-154  applySseStatus() → StatusAggregatorImpl.entries   【权威 A：生命周期】
  ├─→ LegacySseHandler.kt:168-170  mutateSessionList(applySessionStatus) → sessionStatuses  【权威 B：UI】
  │   ↑ 两次独立写入，无协调（THE FORK POINT）
Slim digest status
  └─→ SessionSyncCoordinator.kt:988  mutateSessionList → sessionStatuses  【仅权威 B，不喂 A】
Optimistic busy（POST 成功）
  └─→ SessionListFieldsReducer.kt:62-71  sessionStatuses += ...  【仅权威 B，无任何护栏】
REST 轮询
  ├─→ ProcessStatusPoller.runRefresh:461  statusAggregatorInput.refresh() → entries  【权威 A】
  └─→ StatusPollOrchestrator:183-218  mutateSessionList(mergeStatusSnapshot) → sessionStatuses  【权威 B】
```

**后果**：
- 权威 A（聚合器）在 slim 模式下落后权威 B（UI）数秒（slim digest 不喂 A）；`GlobalBusyState` 可能误判 `AllIdleFresh` → 过早关 SSE。
- 权威 B（UI）有 **10 条写入路径**，其中 **path #5（optimistic busy）完全无护栏**（exp-2 确认 `SessionListFieldsReducer.kt:62-71` 裸 `+`）。

**缺执行代际 fence**：现有护栏（`ConnectionIdentityStore` epoch + `statusLoadEpoch` + `mergeStatusSnapshot`）**只防跨 host/跨 epoch**，挡不住**同一连接内**「旧执行轮次的 idle vs 新轮次的 busy/retry」。这是反馈 #1（retry 期 stop 消失）的**直接致命根因**（rev-ogpt 硬伤 #1）。

### 1.2 三个用户反馈的根因映射

| 反馈 | 主根因 | 类别 | 对症 |
|---|---|---|---|
| #1 retry/退避期 stop 消失 | 同 identity 内 stale idle 覆盖 optimistic/retry（执行代际错位）+ path #5 无护栏 | **执行代际 fence 缺失** | §2 authority 内建 generation fence（§3）；P0-B/P0-C |
| #2 错误显示不稳定 | durable `Message.error` 恢复缺失 + 两阶段时序 + 静默丢弃 + 归档不清 | **错误恢复缺失** | P0-E |
| #3 回页面看不到运行态/错误 | 冷启 reconcile 条件性（archive 早返回 + KeepCurrent-only 门）+ abort 不乐观致 UI 停滞 + 双源不同步 | **冷启缺口 + UI 停滞 + 双源** | P0-D/P0-F + §2 双源根治 |

### 1.3 关键代码事实（exp-1/exp-2/exp-3/exp-4 核对，2026-07-30 快照）

- `sessionStatuses` 声明：`AppStateSlices.kt:716`（`SessionListState` 类 :714）；扁平 sid-keyed Map（exp-1 确认无 workdir 限定）。
- store 机制：`SharedStateStore`（`SharedStateStore.kt:54-56`）持 `MutableStateFlow<StoreState>`；`dispatch(AppAction)` → `state.update { reduce(it, action) }`（:250-252）；CAS retry loop（AtomicReference）。**线程契约**（exp-2）：`:182-183` kdoc「callers MUST run on Dispatchers.Main.immediate」覆盖所有 `mutateXxx`；`dispatch` 内部 CAS 线程安全但 reduce 在调用线程同步执行。
- `SessionStatusPatched`（`AppAction.kt:664-668`）→ `reduceSessionStatusPatched`（`SessionListFieldsReducer.kt:62-71`，裸 `+`，无护栏）。
- `sessionStatuses` 写入 reducer 共 3 个：`reduceSessionStatusPatched`（+）、`reduceSessionTreeHydrated`（= 替换 :108）、`reduceHostStatePurged`（清空 `CrossSliceFieldsReducer.kt:128`）。
- 聚合器：`StatusAggregatorImpl`（`StatusAggregatorImpl.kt:96-102`，`@Singleton`）；单 `commitPublishLock`（:201），所有写经 `update(){ synchronized(lock){...} }`（:479-486）。`applySseStatus`（:386-397）**硬编码 `fresh=false`**（:391）；`refresh`（:257-358）写 `coverage`（:347-358）；`applySseStatus` **从不写 coverage**；`markRequestFailedInternal`（:412-443）写 degenerate coverage（`coveredWorkdirs=empty, lastSuccessTimeMs=-1`）。Busy/Retry **永不过期**（:556-561，保守）；`rescheduleFreshnessLocked`（:615-657）仅对 Idle deadline 调度 `freshnessJob`。
- `GlobalBusyState` 消费者：`StreamingLifecycleCoordinator`（主——5 个 transition handler + 45s idle-debounce + handoff）、`SessionStreamingController`（busy chronometer）、`ProcessStatusPoller`。
- FORK POINT：`LegacySseHandler.handleSessionStatus`（:130-169）—— 两次独立写入（applySseStatus + mutateSessionList），无协调。**SSE 路径无 ConnectionIdentity**（exp-2 确认 :130-169 用 `host.serverGroupFp()`，无 identity/epoch 门）；`SseDispatchHost` 接口（:27-126）**不暴露 identityStore**（须扩接口）。
- optimistic POST onSuccess（exp-2）：`SessionMutationActions.kt:376-379` `dispatch(SessionStatusPatched)` **无 identity/epoch guard**；`launchSendMessage`（自由函数 :312-441）**无 identityStore 参数**；3 个调用点 `AppCoreOrchestration.kt:780/825/1013` 均不传 identity；但 `dispatchSendMessage`（AppCore 扩展 :939-960）**能访问** `appCore.identityStore`（AppCore:160）。

---

## 2. 新体系架构设计（单一权威源）

### 2.1 设计目标

> 用户明确：权威重构，形成易于管理维护、清晰逻辑的新体系。**不是最小改动。**

- **单一权威源**：所有 status 写入的唯一入口（typed `AuthorityOp`），内部执行全部护栏。
- **双投影派生**：UI 投影（sid-keyed）+ 生命周期投影（aggregator → `GlobalBusyState`）从同一源派生，**根治双源不同步**（aggregator 改纯响应式投影，M3）。
- **执行代际 fence 内建**：`ServerRound(incarnation, turn)` 强 fence（slimapi）+ 确认门启发式 fallback（legacy，带 liveness 自愈），非 wall-clock；**计数空间分离**（M1）。
- **写入漏斗**：10 条散落写入路径收敛为 typed `AuthorityOp` 入口（M2）。
- **读面零侵入**：~25 个读点不改（保持 `.value.sessionStatuses` 快照语义），仅写面重构（M9 证明 absence 语义保留）。

### 2.2 核心组件：`SessionStatusAuthority`（新 @Singleton）

```
                  ┌─────────────────────────────────────────────────────────────┐
                  │         SessionStatusAuthority（单一权威源）                  │
                  │  @Singleton；内部 AtomicReference<AuthorityState>             │
                  │                                                             │
写入漏斗（10→typed）│  commit(op: AuthorityOp): CommitResult   【纯函数：持锁只做 CAS】│
                  │    ├─ identity/epoch guard（复用 ConnectionIdentity）          │
                  │    ├─ by-op-type 护栏（见下表）                                 │
                  │    └─ 返回 CommitResult{applied, projection, optimisticBumps}  │
                  │                                                             │
                  │  publish()                  【deferred：锁释放后 bump StateFlow】│
                  │                                                             │
双投影（派生）     │  authorityFlow: StateFlow<AuthorityState>                     │
                  │    ├─→ Main.immediate 单一 collector → dispatch projection    │──→ sessionStatuses（UI）
                  │    └─→ aggregator 订阅 → 派生 entries + GlobalBusyState        │──→ 生命周期
                  └─────────────────────────────────────────────────────────────┘
```

**关键 v2 决策（解决 M6/M8）**：`commit(op)` 是**纯同步函数**——`synchronized(lock)` 内只做「读 cur → 按 op 类型计算 next → `state.set(next)` → 返回 `CommitResult`」，**不 dispatch、不调 aggregator、不起协程**。所有发布副作用（dispatch projection、aggregator 派生）由 `publish()` 在**锁释放后**触发，经 `authorityFlow` 的单一 Main.immediate collector 与 aggregator 订阅者消化（§4b 锁序模型）。原子调用方（M6）把 `CommitResult.projection` 直接纳入自己的 `store.mutateState` CAS，再 `publish()`。

**`AuthorityState`（内部，v2 分离计数空间，M1）**：
```kotlin
data class AuthorityState(
    val bySid: Map<String, SessionEntry>,
    val knownIncarnation: Long,           // slimapi/REST 生命周期 epoch；incarnation advance 时重置 serverRound
    val coverage: Coverage?,              // REST 整图覆盖信息（coveredWorkdirs/unmappedActiveIds），从 ApplySnapshot 派生
)
data class SessionEntry(
    val status: SessionStatus,            // idle/busy/retry（三态镜像）
    val serverRound: ServerRound?,        // 服务端权威轮次（null=从未见服务端信号）
    val optimisticClaim: OptimisticClaim?,// 客户端乐观轮次（独立空间；null=无 pending claim）
    val origin: EntryOrigin,              // OPTIMISTIC/SSE_LEGACY/SSE_SLIM/REST/TREE
    val freshness: Freshness,             // Fresh/Stale/Unknown（TTL 派生，authority 拥有）
    val updatedMonotonic: Long,           // TTL 锚（单调钟，仅 TTL/超时，非因果）
    val workdir: String?,                 // aggregator composite-key 投影用
)
data class ServerRound(val incarnation: Long, val turn: Long) : Comparable<ServerRound> {
    override fun compareTo(other) = compareBy(incarnation, turn)  // lexicographic
}
data class OptimisticClaim(
    val clientSeq: Long,                  // per-(client session) 单调；NEVER 与 server turn 比较
    val claimedAtMonotonic: Long,         // 超时自愈用（非因果）
    val serverEchoed: Boolean,            // 服务端是否已 echo 该轮次（解决 cross-channel reorder）
    val guardedIdleDrop: Boolean,         // 确认门 DROP 过 idle（watchdog 据此 reconcile）
)
```

**`AuthorityOp`（typed 写入入口，M2）**：
```kotlin
sealed interface AuthorityOp {
    /** 增量单 sid 事件（SSE legacy / SSE slim / optimistic）。带 ServerRound（slim）或无（legacy/optimistic）。 */
    data class ApplyEvent(
        val sid: String,
        val status: SessionStatus,
        val origin: EventOrigin,          // SSE_LEGACY, SSE_SLIM, OPTIMISTIC
        val serverRound: ServerRound?,    // slim 带/incarnation advance 带；legacy/optimistic = null
        val identity: ConnectionIdentity?,
        val connectionMonotonicMs: Long,  // TTL/tie-break only，非因果
        val workdir: String?,
        val optimisticBumpTimestamp: Long? = null,  // OPTIMISTIC 时附带 session.time.updated bump
    ) : AuthorityOp

    /** 整图权威替换（REST bulk / tree hydrate）。缺项≡idle（在 covered workdir 内）。 */
    data class ApplySnapshot(
        val snapshot: Map<String, SessionStatus>,
        val coveredWorkdirs: Set<String>,
        val authoritativeNodeIds: Set<String>,     // 树内已知 sid（缺失则 normalize 为 idle）
        val unmappedActiveIds: Set<String>,
        val partialFailureWorkdirs: Set<String>,   // 失败 workdir 保留 prior
        val requestToken: RequestToken,            // (host, statusLoadEpoch, requestStartMonotonic) epoch guard
        val identity: ConnectionIdentity?,
    ) : AuthorityOp

    /** host 切换清理（镜像 reduceHostStatePurged）。 */
    data class PurgeHost(val preserveServerGroup: Boolean) : AuthorityOp

    /** 源失败（替换 markRequestFailed）。 */
    data class MarkSourceFailed(val scope: ScopeKey, val requestToken: RequestToken) : AuthorityOp

    /** 清 optimistic pending（REST reconcile / watchdog 超时 / host switch 的 liveness）。 */
    data class ClearOptimistic(val sids: Set<String>, val reason: ClearReason) : AuthorityOp
}
data class CommitResult(
    val applied: Boolean,
    val projection: Map<String, SessionStatus>,    // bySid.mapValues{status}，原子调用方纳入 store CAS
    val optimisticBumps: Map<String, Long>,        // 随 projection action 一起 bump session.time.updated
)
```

**by-op-type 护栏（全部护栏按操作类型分布，非单一 submit）**：

| Op | 护栏（commit 内，纯函数） |
|---|---|
| **ApplyEvent (serverRound != null, slim)** | ① identity guard；② `serverRound < cur.serverRound`（lex）→ DROP（旧轮次）；③ incarnation advance（`serverRound.incarnation > knownIncarnation`）→ 重置该 scope 内所有 serverRound，更新 knownIncarnation；④ 解决 optimisticClaim：若 `serverRound > cur.serverRound` → claim superseded 清除；若 == → claim.serverEchoed=true。 |
| **ApplyEvent (serverRound == null, legacy SSE)** | ① identity guard；② 确认门启发式：`cur.optimisticClaim != null && !serverEchoed && status==IDLE` → DROP + 标 `guardedIdleDrop`（watchdog 据此 reconcile，§3.2）；③ `status==BUSY/RETRY && cur.optimisticClaim != null` → claim.serverEchoed=true（echo 确认）；④ merge-timing tie-break（`connectionMonotonicMs`，非因果）。 |
| **ApplyEvent (OPTIMISTIC)** | ① identity guard；② `alreadyEchoed = cur.status∈{BUSY,RETRY} && cur.origin∈{SSE_*}`（cross-channel：server 已先发 busy）→ claim.serverEchoed=alreadyEchoed；③ clientSeq++（独立空间，不与 server turn 比）；④ 附带 optimisticBumpTimestamp。 |
| **ApplySnapshot (REST/tree)** | ① requestToken guard（host + statusLoadEpoch + requestStart 一致）；② 对 coveredWorkdirs 内 authoritativeNodeIds 缺失 sid → normalize idle（保留 absence=未知语义）；③ partialFailureWorkdirs 保留 prior；④ 清除被 snapshot 覆盖 sid 的 optimisticClaim（REST 权威）；⑤ 更新 coverage。 |
| **PurgeHost** | 镜像 `reduceHostStatePurged`：`preserveServerGroup=false` → 清空 bySid（跨组）；`true` → 保留 bySid（同组，仅清 activeSessionIds 等非 status 字段）。 |
| **MarkSourceFailed** | requestToken guard；标 scope Unknown（freshness 保守）。 |
| **ClearOptimistic** | 清指定 sid 的 optimisticClaim（liveness 自愈）。 |

> **关键**：v1 把所有护栏塞进一个 `submit`，导致 generation 空间冲突（M1）且无法表达整图（M2）。v2 按 op 类型分布护栏，serverRound（incarnation,turn）与 optimisticClaim.clientSeq **空间分离**（M1），整图走 ApplySnapshot + requestToken（M2），aggregator 旁路写入消除（M3）。path #5 无护栏、双源 fork、stale-idle 覆盖、host 切换污染、乱序覆盖、幻影 busy 钉死——**全部在此根治**。

### 2.3 双投影收敛（根治 dual-source，M3/M9）

**UI 投影**（替换 `sessionStatuses`，M9 absence 保留）：
- `CommitResult.projection = bySid.mapValues { it.value.status }`——**保留所有已知 sid**（authority.bySid 只保留被通知过的 sid；REST snapshot 经 normalize 把 authoritativeNodeIds 缺失 sid 填 explicit-idle）。故 `it !in sessionStatuses` 仍 = 「真未知」→ **`UnreadSoakController.kt:75-78` 的 fail-closed 语义保留**（exp-1 确认这是唯一 absence-reader）。
- authority `publish()` → 单一 Main.immediate collector → `dispatch(AppAction.SessionStatusProjectionUpdated(projection, optimisticBumps))`。
- **单一** reducer `reduceSessionStatusProjectionUpdated`（替换 `reduceSessionStatusPatched` + `reduceSessionTreeHydrated` 的 status 部分 + 所有原始 `mutateSessionList` status 写）：
  ```kotlin
  internal fun reduceSessionStatusProjectionUpdated(state, action): StoreState = state.copy(
      sessionList = state.sessionList.copy(
          sessionStatuses = action.projection,
          // 顺带 bump session.time.updated（防 REST 覆盖乐观）
          sessions = applyOptimisticBumps(state.sessionList.sessions, action.optimisticBumps),
      ),
  )
  ```
- ~25 个读点**零修改**（仍读 `.value.sessionStatuses`）。

**生命周期投影（aggregator 改纯响应式投影，M3 真根治）**：
- **aggregator 不再有公共 mutation API**：`StatusAggregatorInput` 接口的 `refresh`/`applySseStatus`/`markRequestFailed` 移除（或降为 `@Deprecated internal`）。所有原调用方改经 authority：
  - `ProcessStatusPoller.runRefresh:461` → `authority.commit(ApplySnapshot(...))`。
  - `SessionStreamingController.kt:177-196,438` `markRequestFailed` → `authority.commit(MarkSourceFailed(...))`。
- **aggregator.entries 改为 authority 的派生视图**：aggregator 订阅 `authorityFlow`，在 `Dispatchers.Main.immediate` 上重算 `Aggregate`（composite-key entries 投影 + coverage 投影），运行既有 `publishLocked`/`rescheduleFreshnessLocked`/`GlobalBusyState` 逻辑：
  ```kotlin
  init { authority.authorityFlow.onEach { recompute(it) }.launchIn(scope) }  // Main.immediate
  private fun recompute(authState: AuthorityState) = synchronized(commitPublishLock) {
      val derived = projectAggregate(authState)  // composite-key entries + coverage from authState
      aggregate.set(derived)
      publishLocked(derived, clock())            // 既有 GlobalBusyState + freshness 调度
  }
  ```
- **GlobalBusyState 分层（修 exp-2/opus 的幻影 busy 钉死）**：投影保留 `tentative` 标志（optimisticClaim 未 echoed 的 busy）。aggregator 的 `project`（:556-561）改为：
  - `ServerBusy`（serverRound 确认 或 serverEchoed=true）→ 持 `GlobalBusyState.Busy`（保守不过期，保留现状语义）。
  - `OptimisticBusy`（optimisticClaim 未 echoed）→ **不钉死 GlobalBusyState**：作为 `GlobalBusyState.Busy` 但 freshnessJob 对 OptimisticBusy 条目**调度 TTL**（`OPTIMISTIC_CONFIRM_TIMEOUT`~5s 后由 watchdog 清），而非永不过期。45s idle-debounce 仅对 ServerBusy 计入。
  - 修复 v1 缺陷：v1 把 optimistic 经 applySseStatus 喂聚合器写 `fresh=false` 且 Busy 不过期 → 幻影 busy 永久钉死 → SSE/前台服务不关。v2 让 OptimisticBusy 可被 TTL/watchdog 清。
- **R3 真根治**：slim digest / optimistic / REST / tree 全经 authority → aggregator 派生；不再有旁路；coverage 来自 authority 的 ApplySnapshot；AllIdleFresh 由派生 coverage 产生（不再只由 refresh 产生）。

> **长期演进（P1）**：aggregator 的 `Aggregate` 内部状态可进一步消除（直接订阅 authorityFlow 计算 GlobalBusyState，无中间 AtomicReference）。首期保留 `Aggregate` 作为派生缓存，降低重构风险——但因 entries 现为派生（非独立可写），**已满足「单一权威」真实性**（M3 选 option a，非降级声明）。

### 2.4 正交状态轴（UI 派生）

| 轴 | 取值 | 来源 |
|---|---|---|
| **execution.value** | `Idle \| Busy \| Retry` | authority 投影（`sessionStatuses[id]`），单一 UI 来源 |
| **execution.serverRound** | `ServerRound?` | authority 内部（slim 强 / legacy 无） |
| **execution.optimisticClaim** | `OptimisticClaim?` | authority 内部（确认门 + echo 标志） |
| **freshness** | `Unknown \| Stale \| Fresh` | authority 内部 TTL（`STATUS_TTL_MS≈30s`）派生，UI 保守 |
| **submission** | `Idle \| Posting` | `sendingSessionIds`（POST 在途窗口，**不延长**） |
| **abort.pending** | Boolean（per session） | 新增（P0-F），带 startedAt token |
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

## 3. 护栏与不变量设计（执行代际 fence：强 + 启发式 fallback，M1）

### 3.1 两层 fence 设计（v2 分离计数空间）

> **rev-ogpt #8 / M1**：禁 wall-clock 因果；须为同 identity 内跨执行轮次建立非 wall-clock 因果标识，且**计数空间须分离**（client bump ≠ server turn ≠ restart reset）。

**Tier 1 — 强 fence（slimapi 路径，推荐）**：
- oc-slimapi 中继层为每个 `session.digest` / status 转发附加 `(turnIncarnation: Long, turn: Long)`（per-(serverGroupFp,sid) 单调，见 §5 契约）。
- authority `ApplyEvent(serverRound = ServerRound(turnIncarnation, turn))`：**lexicographic 严格单调**——`(inc, turn) < cur.serverRound`（lex）→ DROP。
- **incarnation advance 协议（修 M1 restart 冻结）**：`turnIncarnation > authority.knownIncarnation` 时，authority **重置该 scope 内所有 entry 的 serverRound = null**（忘记旧轮次），更新 `knownIncarnation = turnIncarnation`。后续新 incarnation 的事件是「当前」，不被旧基线永久拒绝。客户端重连时 REST reconcile（ApplySnapshot）作为 liveness 兜底。
- 可靠性：turn 由中继层**在事件 ingest 时快照**（M7，非 flush 时读当前），观察真实 POST/abort 流量派生，是服务端侧权威因果标识。
- **不动 opencode 服务端**（slimapi 是用户自建中继，可改）。

**Tier 2 — 启发式 fallback（legacy SSE 路径，不经 slimapi）**：
- 无 turn 时走**确认门（带 liveness 自愈）**：
  - POST 成功 → `ApplyEvent(OPTIMISTIC)` → `optimisticClaim.clientSeq++`、`serverEchoed=alreadyEchoed`。
  - incoming `BUSY/RETRY` AND `optimisticClaim != null` → **server echo 确认**，`serverEchoed=true`，应用。
  - incoming `IDLE` AND `optimisticClaim != null && !serverEchoed` → **可能 stale idle（旧轮次）**，DROP + 标 `guardedIdleDrop`；**同时 arm watchdog**：`claimedAtMonotonic + OPTIMISTIC_CONFIRM_TIMEOUT(~5s)` 后触发该 sid REST reconcile（`ClearOptimistic` + `ApplySnapshot`），**非无限 DROP**（修 M5/疑点1 liveness）。
  - incoming `IDLE` AND `serverEchoed==true` → legitimate terminal idle，应用 + 清 claim。
  - incoming 任意 AND `optimisticClaim == null` → 正常应用。
- **Cross-channel reorder（M5/疑点1，server busy 先于 HTTP success）**：OPTIMISTIC commit 时检查 `cur.status∈{BUSY,RETRY} && cur.origin∈{SSE_*}` → server 已先发 busy → `serverEchoed=true`（claim 立即被确认）。后续 server idle → serverEchoed → legitimate 应用。**不误挡合法快速 idle**（修 v1 8s 误挡 + 塌缩）。

**liveness 安全阀（非因果源，仅逃生）**：
- watchdog：`optimisticClaim.serverEchoed==false` 且 age > `OPTIMISTIC_CONFIRM_TIMEOUT(~5s)` → 触发该 sid REST reconcile（authority `ApplySnapshot` / `ClearOptimistic`），权威清除 pending。
- REST reconcile（`ApplySnapshot`）对覆盖 sid **清除 optimisticClaim**（REST 权威）。

**为何非 wall-clock（修硬伤⑦）**：因果排序权威是 `ServerRound(incarnation,turn)`（单调）+ `optimisticClaim`（确认状态）+ slimapi turn（服务端侧）。`connectionMonotonicMs`/`OPTIMISTIC_CONFIRM_TIMEOUT` 仅 TTL/局部 tie-breaker/逃生，**不参与因果 DROP 判定**（v1 误让 sourceTimeMs 参与 DROP，v2 改正：legacy 分支的 merge-timing 仅 tie-breaker，不单独 DROP；DROP 只由确认门 + serverRound 决定）。

### 3.2 不变量（Invariants）

> 现状满足 ✅；本方案落地 🟡（对应 P 项）；**v2 新增 🔵**。

1. **(✅)** 服务端 `SessionStatus` 是 per-instance 内存执行投影；`GET /session/status` 稀疏（缺项 ≡ idle）；进程重启清空；非持久真相。
2. **(✅)** `Message.error` 是失败回合的**持久真相**；跨重启/重载/重连均在。
3. **(✅ 修正)** retry 跑在 Runner fiber 内；**interrupt 杀 fiber 但 `Effect.retry` 不在 interrupt 时触发**（§6）；retry 退避期 status=retry（非 idle）。
4. **(✅)** retry `next` 是服务端计算的下次重试 epoch-ms，非客户端时间戳。
5. **(✅)** `sendingSessionIds` ≡ POST 在途窗口，**不延长**（§9 否决延长）。
6. **(✅)** 乐观 busy 仅 POST onSuccess 后写；失败不写（无回滚对象，§9 否决回滚）。
7. **(✅)** abort 仅服务端操作，不写乐观 idle（§9 否决乐观 idle）；idle 由 server 清除。
8. **(🟡 P0-A)** **所有 status 写入经 `SessionStatusAuthority.commit(AuthorityOp)` typed 入口**，内部 identity/epoch guard。path #5 无护栏直写消除。
9. **(✅)** REST status 受 `statusLoadEpoch` + host-at-request-start 守卫（ApplySnapshot.requestToken）。
10. **(🟡 P0-B)** 同 identity 内跨执行轮次由 **`ServerRound(incarnation,turn)` 强（slim）+ 确认门启发式（legacy，带 liveness 自愈）** 保护；optimistic 未 echo 时 stale idle 不得覆盖；**计数空间分离**；不依赖 wall-clock。
11. **(🟡 P0-A)** UI 投影与生命周期投影从**同一权威源**派生；**aggregator 改纯响应式投影，mutation API 移出公共接口**；slim digest/optimistic/REST/tree 全经 authority 自动投影；双源收敛（R3 真根治，非声称）。
12. **(✅)** `Unknown`/`Stale` 新鲜度不进 SSE idle 宽限期 / `AllIdleFresh`（保守）。
13. **(✅)** route-instance 守卫只保护 route-owned chat 写；全局 status route 无关。
14. **(🟡 P0-D)** 首次连接 status reconcile 不依赖 `KeepCurrent`，**基于已提交的 merged tree**；冷启无论决策（含 archive 早返回）都触发 bulk status；exactly-once（cold-start 门）。
15. **(🟡 P0-E)** durable `Message.error` 经消息加载/重载可见；`status=idle` 不等于成功，须等 `message.updated` 读 `message.error`；**重排队列保存 payload + messageID**（M5）。
16. **(🟡 P0-F)** abort POST 在途有显式 `abort.pending` 标志（带 startedAt token）+ 看门狗；UI 不停滞。
17. **(🔵 I-SID-UNIQUE)** OpenCode session id（UUID）在 server group 内全局唯一；UI `sessionStatuses` 扁平 sid-keyed 安全；aggregator composite key 防御性保留（§4c）。
18. **(🔵 M6 原子性)** BackgroundUnreadPoller/reduceSessionTreeHydrated/StatusPollOrchestrator 的 sessionStatuses 与 tree/active/epoch **保持原子提交**（projection 纳入调用方 CAS）。
19. **(🔵 M8 锁序)** authority.commit 纯函数（持锁只 CAS）；publication deferred；锁序单向 authority→aggregator，无反向；reducer 不回调 authority。

---

## 4. 结构性重构改动计划（P0/P1/P2）

> 每个改动点含：**文件:行号**、**具体改什么**、**为什么**、**风险**、**验证**。
> 验证统一遵循 AGENTS.md：改 Kotlin/资源后跑 `./scripts/check.sh`（编译 + `testDebugUnitTest`）**+ §4d 测试门槛（M10）**。
> 行号基于 explorer 核对快照（2026-07-30）；实施前用 `read` 复核漂移。

### P0（结构性基础 + 用户可见正确性）

---

#### **P0-A 建立 SessionStatusAuthority（typed op 入口 + 双投影 + aggregator 收编 + 原子提交）**

**对症**：dual-source（R3）、path #5 无护栏（R2）、10 条散落写入、aggregator 旁路（M3）、generation 空间冲突（M1）、bulk 语义缺失（M2）、原子迁移（M6）、锁序（M8）。**这是整个重构的基础**，其余 P0 项依赖它。v2 把 v1 的 P0-A + P1-A（aggregator 派生）+ M2/M3/M6/M8 合并为一个自洽的 P0-A。

**文件:行号**：
- **新建** `service/status/SessionStatusAuthority.kt`（权威源本体：AuthorityState + AuthorityOp + commit/publish）。
- **新建** `service/status/AuthorityOp.kt`（typed sealed op 层级 + CommitResult）。
- **改** `service/status/StatusModule.kt:53-74`（Hilt 注入 authority + 改 aggregator 注入依赖 authority）。
- **新建** `ui/SessionStatusProjectionReducer.kt`（单一投影 reducer，或并入 `SessionListFieldsReducer.kt`）。
- **改** `AppAction.kt:664-668`（`SessionStatusPatched` → `SessionStatusProjectionUpdated(projection, optimisticBumps)`）。
- **改 aggregator** `service/status/StatusAggregatorImpl.kt:96-102, 170-181, 257, 386, 404, 479-486, 556-561`（entries 改派生；mutation API 移出公共 `StatusAggregatorInput`）。
- **改写入漏斗**（10 条路径 → typed op）：
  - `ui/SessionListFieldsReducer.kt:62-71`（`reduceSessionStatusPatched` → 移除，由 projection reducer 驱动）。
  - `ui/controller/sse/LegacySseHandler.kt:130-169`（fork point → 单一 `authority.commit(ApplyEvent(SSE_LEGACY))`）。
  - `ui/controller/sse/SseDispatchHost.kt:27-126`（**扩接口**：加 `currentIdentity(): ConnectionIdentity?`，委托 identityStore——exp-2 确认现状无 identity 暴露）。
  - `ui/controller/SessionSyncCoordinator.kt:987-991`（slim digest → `ApplyEvent(SSE_SLIM, serverRound=ServerRound(inc,turn))`）。
  - `ui/controller/StatusPollOrchestrator.kt:183-218, 363-404`（REST merge → `ApplySnapshot`，projection 纳入 `mutateSessionList` CAS，保留 `mergeStatusSnapshot` 语义在 authority 内）。
  - `ui/SessionTreeHydrator.kt:132-150`（tree hydrate → `ApplySnapshot`，projection 随 `AppAction.SessionTreeHydrated` payload 携带以保原子；见 M6）。
  - `ui/controller/BackgroundUnreadPoller.kt:175-222`（→ `ApplySnapshot`，projection 纳入 `mutateState` CAS；见 M6）。
  - `ui/SessionListActions.kt:251`（loadChildSessions → `ApplySnapshot`/`ApplyEvent(TREE)`）。
  - `ui/controller/ProcessStatusPoller.kt:461-475`（refresh → `ApplySnapshot`，不再调 `statusAggregatorInput.refresh`）。
  - `ui/controller/SessionStreamingController.kt:177-196, 438`（markRequestFailed → `MarkSourceFailed`）。
  - `ui/SessionMutationActions.kt:376-379`（optimistic → `ApplyEvent(OPTIMISTIC)`，见 P0-C）。

**具体改什么**：

1. **新建 `SessionStatusAuthority`**（@Singleton，构造注入 `identityStore`、`clock: ()->Long`、`@UiApplicationScope scope`）：
   - 内部 `AtomicReference<AuthorityState>`（结构见 §2.2）。
   - `fun commit(op): CommitResult`（**纯同步**，`synchronized(lock){ 纯计算 + state.set }`，无副作用；按 op 类型执行 §2.2 护栏表）。
   - `fun publish()`（锁释放后 bump `_pub.value = state.get()`）。
   - `val authorityFlow: StateFlow<AuthorityState>`。
   - `val projection: Map<String,SessionStatus>`（= `state.get().bySid.mapValues{status}`，同步读，供原子调用方）。
   - 单一 Main.immediate collector：`authorityFlow.onEach { dispatch(SessionStatusProjectionUpdated(projectionOf(it), bumpsOf(it))) }.launchIn(scope)`。

2. **Hilt 注入**：`@Provides @Singleton fun provideSessionStatusAuthority(...)`；aggregator 改 `@Inject constructor(authority: SessionStatusAuthority, ...)`。

3. **aggregator 改纯响应式投影（M3）**：
   - 移除 `StatusAggregatorInput` 的 `refresh`/`applySseStatus`/`markRequestFailed`（编译期禁用，配合 §4d 静态 gate）。
   - `init { authority.authorityFlow.onEach { recompute(it) }.launchIn(scope) }`；`recompute` 在 `commitPublishLock` 内派生 `Aggregate`（composite-key entries + coverage from authState.coverage）+ `publishLocked`。
   - `project`（:556-561）改分层：ServerBusy（持 Busy 保守不过期）/ OptimisticBusy（TTL 可清，不进 45s idle-debounce 的永久 Busy）。

4. **单一投影 reducer**：`reduceSessionStatusProjectionUpdated`（`sessionStatuses = action.projection` + optimistic bumps）。移除 `reduceSessionStatusPatched`（:62-71）。`reduceSessionTreeHydrated`（:108）的 status 部分改为：payload 仍带 projection（M6 原子），reducer 用 payload 的 projection（与 authority 一致）。

5. **写入漏斗（10→typed）**：每条写入路径改为构造对应 `AuthorityOp` 并调 `authority.commit(op)`：
   - 原子调用方（StatusPollOrchestrator/Hydrator/BackgroundUnreadPoller）：`val r = authority.commit(ApplySnapshot(...)); mutateSessionList/mutateState{ ...sessionStatuses = r.projection... }; authority.publish()`。
   - 非原子调用方（SSE/optimistic）：`authority.commit(op); authority.publish()`（collector 异步 dispatch）。
   - 移除所有直接 `mutateSessionList { sessionStatuses = ... }`。

6. **读面零修改**：~25 个读点保持 `.value.sessionStatuses`（M9 证明 absence 语义保留）。

**为什么**：dual-source、10 条散落写入、aggregator 旁路、generation 冲突、bulk 缺失、原子性、锁序——全是结构债根因。typed op + 双投影 + aggregator 收编 + 纯 commit/deferred publish 一次性自洽根治 M1/M2/M3/M6/M8。用户明确要结构性重构。

**风险**：
- **高**：触及状态层核心。**缓解**：分阶段——先建 authority + aggregator 派生（双写 shadow：authority 投影与 legacy 并行计算，仅日志比对 divergence，不驱动 UI），验证投影一致 N 次后切换（§4b shadow 模型）。
- `bumpSessionUpdated` 语义保留：OPTIMISTIC op 附带 `optimisticBumpTimestamp`，projection action 携带。
- `mergeStatusSnapshot` 的「REST 在途保护」：迁入 ApplySnapshot commit 分支（requestToken guard + localBefore/localAfter diff 在 authority 内重算）。
- aggregator 既有 `GlobalBusyState`/idle-debounce 语义保留：分层 ServerBusy/OptimisticBusy 后需金钟回归 StreamingLifecycleCoordinator。

**验证**（+ §4d M10）：
- 单测：每类 AuthorityOp × 初始 SessionEntry 状态 → 投影正确（状态转换穷举表）。
- 单测：identity 过期 DROP；serverRound lex stale DROP；确认门 stale idle DROP + watchdog arm；incarnation advance 重置；cross-channel（server busy 先到 → OPTIMISTIC serverEchoed=true → idle 应用）。
- 单测：双投影一致（store.sessionStatuses == authority.projection == aggregator 派生 entries）。
- 单测：幻影 optimistic busy → aggregator OptimisticBusy → TTL/watchdog 清 → GlobalBusyState 不永久 Busy → idle-debounce 可触发。
- 回归：`sessionStatuses` 读点行为不变（金钟测试覆盖 ~25 读点 + UnreadSoakController fail-closed）。
- `./scripts/check.sh` 通过 + §4d 静态架构 gate（编译期禁业务代码赋值 sessionStatuses / 禁调 aggregator mutation API）。

---

#### **P0-B 执行代际 fence（authority commit 层 ServerRound 强 + 确认门启发式，M1）**

**对症**：同 identity 内 stale idle 覆盖 optimistic/retry（R1）；rev-ogpt #1/#8；用户反馈 #1；**M1 计数空间冲突**。**依赖 P0-A**（fence 在 authority commit 层）。

**文件:行号**：
- `service/status/SessionStatusAuthority.kt`（commit 层 ApplyEvent 分支：ServerRound lex + 确认门 + incarnation advance）。
- `service/status/AuthorityOp.kt`（`ApplyEvent.serverRound: ServerRound?`）。
- **slimapi turn 消费**：`ui/controller/SessionSyncCoordinator.kt:978-991`（digest 解析 `(turnIncarnation, turn)` → ApplyEvent.serverRound）。
- **watchdog**：`ui/controller/ProcessStatusPoller.kt`（`optimisticClaim.serverEchoed==false && age>5s` → `ClearOptimistic + ApplySnapshot` 单 sid reconcile）。

**具体改什么**：
1. authority commit 层实现 §3.1 两层 fence（已在 P0-A 的 ApplyEvent 分支）。
2. slim digest 解析：`val inc = payload.optLong("turnIncarnation"); val turn = payload.optLong("turn")`（slimapi 附加字段，见 §5）；非空 → `ApplyEvent(serverRound = ServerRound(inc, turn))`；空 → fallback 确认门。
3. optimistic 写入（P0-C）`serverRound = null` → 确认门 + clientSeq++。
4. REST reconcile（ApplySnapshot）自动清覆盖 sid 的 optimisticClaim（liveness）。
5. watchdog（`ProcessStatusPoller`）：扫描 authorityState，`optimisticClaim.serverEchoed==false && now - claimedAtMonotonic > 5s` → 单 sid REST probe → ApplySnapshot + ClearOptimistic。

**为什么**：R1 是反馈 #1 致命根因。slimapi `(incarnation,turn)` 提供最强 fence（空间分离 + restart 不冻结，M1）；legacy 启发式确认门 + watchdog 自愈（不无限 DROP，M5/疑点1）。非 wall-clock（rev-ogpt #8 / 硬伤⑦）。

**风险**：slimapi 未附加 turn 时 fallback 须正确（确认门 + watchdog）。**缓解**：单测覆盖两层 + incarnation advance + cross-channel；slimapi 协作项明确（§5）。

**验证**（+ §4d M10）：
- 单测：optimistic busy + stale idle（无 turn）→ 确认门 DROP + watchdog arm；5s 后 REST reconcile 清。
- 单测：slim (inc=5,turn=3) 后到 (inc=5,turn=2) → lex DROP。
- 单测：incarnation advance（inc=5 → inc=6）→ 旧 serverRound 清空，新 inc 事件「当前」不冻结。
- 单测：cross-channel（server busy → HTTP success optimistic → server idle）→ serverEchoed 流转 → idle 应用。
- `./scripts/check.sh` 通过。

---

#### **P0-C Optimistic busy 走 authority + identity guard（含 SseDispatchHost 接口扩展）**

**对症**：path #5 无护栏（R2）；rev-ogpt #2；**M8 identity 接口缺口**（exp-2 确认 SSE 路径无 identity）。**依赖 P0-A**。

**文件:行号**：
- `ui/controller/sse/SseDispatchHost.kt:27-126`（**扩接口**：加 `fun currentIdentity(): ConnectionIdentity?` 委托 identityStore——exp-2 确认现状缺）。
- `ui/controller/sse/LegacySseHandler.kt:130-169`（用 `host.currentIdentity()` 构造 ApplyEvent）。
- `ui/AppCoreOrchestration.kt:939-960`（`dispatchSendMessage` 捕获 `identityAtDispatch = identityStore.currentIdentity.value`——AppCore:160 已有 identityStore，exp-2 确认）。
- `ui/SessionMutationActions.kt:312-441, 376-379`（`launchSendMessage` 签名增 `identityAtDispatch`；onSuccess `isCurrent` guard + `ApplyEvent(OPTIMISTIC)`）。
- 3 个调用点 `AppCoreOrchestration.kt:780/825/1013`（传 identityAtDispatch）。
- `service/identity/ConnectionIdentityStore.kt:238-244`（复用 `isCurrent`）；`:86`（`currentIdentity` StateFlow）；`:194-203`（`commitIfCurrent`）。

**具体改什么**：
1. `SseDispatchHost` 加 `fun currentIdentity(): ConnectionIdentity?`（委托实现类 `appCore.identityStore.currentIdentity.value`）。
2. `LegacySseHandler`（:130-169）：用 `host.currentIdentity()` 构造 `ApplyEvent(identity=...)`，单一 `authority.commit(ApplyEvent(SSE_LEGACY))`，删 fork。
3. `dispatchSendMessage`（:939-960）POST 派发前捕获 `identityAtDispatch = identityStore.currentIdentity.value`，传入 `launchSendMessage`（3 调用点）。
4. `launchSendMessage`（:312-441）签名增 `identityAtDispatch: ConnectionIdentity?`；`onSuccess`（:376-379）：archive 检查后 `if (identityAtDispatch != null && !identityStore.isCurrent(identityAtDispatch)) return`（host 已切换），否则 `authority.commit(ApplyEvent(sid, BUSY, OPTIMISTIC, identity=identityAtDispatch, optimisticBumpTimestamp=now))`。
   - authority 内 identity guard 是第二道防线；onSuccess 的 isCurrent 是第一道（避免无谓 commit）。

**为什么**：explorer 确认 `launchSendMessage` 现状无 identity/epoch/host guard（exp-2）。host 切换后 POST onSuccess 污染新 host sessionStatuses（R2）。SSE 路径无 identity 是 P0-A 落地的硬依赖（M8）。

**风险**：`launchSendMessage` 是自由函数，签名变更影响 3 调用点；`dispatchSendMessage` 已有 identityStore（AppCore:160）缓解注入。`SseDispatchHost` 接口扩影响所有实现类（实现类委托 appCore 即可）。

**验证**（+ §4d M10）：单测 isCurrent=false → 不 commit；host 切换后 onSuccess → authority 不含该 sid；SSE 帧 identity 过期 → DROP。`./scripts/check.sh`。

---

#### **P0-D 冷启 status reconcile 无条件化（基于已提交 tree + cold-start 门，M4）**

**对症**：archive 早返回跳 status 刷新（R7）；rev-ogpt #4；用户反馈 #3；**M4 hoist 读未提交 + 成本低估**。**独立可上线**。

**文件:行号**：
- `ui/controller/SessionListRefreshOrchestrator.kt:90-139, 121-131, 195-198`（archive 早返回 + KeepCurrent onLoadSessionStatus）。
- `ui/controller/StatusPollOrchestrator.kt:310-316`（slim 空目录 complete(true)）。

**具体改什么**（修 M4）：
1. **reconcile 接收已 merge 的 sessionIds 作参数**（不重读 tree）：
   ```kotlin
   // onSuccess：先 store.dispatch(merged tree) 提交，再基于已提交状态 reconcile
   store.dispatch(AppAction.SessionsMerged(mergedSessions, ...))   // 提交新 tree
   val committedIds = store.state.value.sessionList.allSessionsById().keys  // 读已提交
   if (coldStartReconcileGate.tryConsume()) {                        // cold-start 一次性门
       onLoadSessionStatusForCommitted(committedIds)                 // 基于已提交，exactly-once
   }
   if (anyArchived && onArchivedSessionsDetected != null) { ...; return@onSuccess }
   ```
   - 修 M4：不再在 archive 提交前读局部 `mergedSessions`；改为先提交 merge → 读 `.value` 已提交 → reconcile。
2. **cold-start 一次性门**（修成本低估）：`coldStartReconcileGate: AtomicBoolean`，首次 launchLoadSessions 成功后 consume=true，后续 reload 不再触发 bulk status（避免每次 reload epoch bump）。仅 host 切换（HostStatePurged）重置 gate。
3. **hoist 位置**：reconcile 在 `staleHostAfterSuspend` 检查**之后**、archive 检查之前，确保 host 仍有效。
4. **slim 空目录边界**（:310-316）：`directories.isEmpty()` → `complete(true)` 返回，不产条目（行为正确）。文档化：空目录 → 聚合器该 workdir `Unknown`（保守）。

**为什么**：archive 早返回跳过 onLoadSessionStatus，用户跨设备归档后看到陈旧状态。M4 修正：基于已提交 tree + 一次性门，避免读未提交与重复成本。

**风险**：archive 路径额外一次 REST status 调用（可接受，冷启一次性）；`statusLoadEpoch` 单飞保护防 double。

**验证**（+ §4d M10）：单测 archive 命中 → onLoadSessionStatus 仍调（exactly-once，基于已提交）；ClearChat/NoOp → status 加载触发；二次 reload → gate 已 consume 不再触发；host 切换 → gate 重置。集成（模拟器）：冷启 + 服务端 busy 会话 + 无 current → UI 显示运行态。`./scripts/check.sh`。

---

#### **P0-E Durable `Message.error` 恢复（payload 完整的重排队列 + GET 恢复链，M5）**

**对症**：durable error 恢复缺失（rev-ogpt #5/#7）；R9/R10；用户反馈 #2；**M5 队列丢 payload + 无 messageID 关联 + 无 drain/清理**。**独立可上线**（不依赖 P0-A）。

**文件:行号**：
- `ui/SessionListFieldsReducer.kt:28-44`（`reduceSessionArchivedLocal` 不清 sessionErrorsById）。
- `ui/ChatFieldsReducer.kt:234-250`（`reduceLastAssistantErrorAttached` 静默丢弃）。
- `ui/AppAction.kt:572-576`（原 action 含 error payload——重排队列须保留）。
- 两阶段时序契约（上游 `processor.ts:619` 设内存、`:595-596` ensuring 落盘）。

**具体改什么**（修 M5，三子项 + payload 完整化）：

**(a) 归档清 sessionErrorsById**（R9；**修字段名 bug**：按 `action.session.id` 非 `action.archivedIds`）：
```kotlin
// SessionListFieldsReducer.kt reduceSessionArchivedLocal
sessionErrorsById = state.sessionList.sessionErrorsById.filterKeys { it != action.session.id },
```

**(b) 修复 `reduceLastAssistantErrorAttached` 静默丢弃（R10，payload 完整队列）**：
```kotlin
// ChatFieldsReducer.kt reduceLastAssistantErrorAttached（:234-250）
if (!state.acceptsRouteUpdate(action.expectedRouteInstance, action.sessionId)) {
    // route 已切换：排队待 route 回归重附（保存完整 payload + messageID）
    return state.copy(chat = state.chat.copy(
        pendingErrorReattach = state.chat.pendingErrorReattach +
            (action.sessionId to PendingError(
                messageID = action.messageId,            // ← M5：关联 messageID（防旧 error 附新 assistant）
                errorPayload = action.error,             // ← M5：保存完整 error（非仅 route instance）
                routeInstance = action.expectedRouteInstance,
                enqueuedAt = clock(),
            )),
    ))
}
// ... route 匹配：仅当 last assistant.id == action.messageId 才附（防错位）
```
- `ChatState` 增 `pendingErrorReattach: Map<String, PendingError>`、`pendingErrorRefresh: Set<PendingErrorKey>`（key 含 messageID）。
- **drain 触发点**：route 回归（route instance 匹配 OR 消息重载）、消息加载完成。**上限 + LRU 淘汰**：每 session 最多 1 条（最新），全局上限 32，LRU 淘汰。
- **防旧 error 附新 assistant（M5）**：error 绑定 `messageID`；drain 时仅当该 messageID 仍是（或仍在）最后 assistant 才附；否则放入 session-level error slot（`sessionErrorsById`）而非附错位 assistant。
- **清理（M5）**：`SessionDeletedLocal`、`HostStatePurged`、`archive` 一并清 pendingErrorReattach/Refresh（防泄漏）。

**(c) 两阶段时序感知 + durable GET 恢复链（rev-ogpt #5，M5）**：
- busy/retry → idle（轮次结束）时，若该 session 有过 busy/retry，标 `pendingErrorCheck[sid+round]`，等最后一条 assistant 的 `message.updated` 到达后检查 `message.error`。
- **durable GET 恢复链（M5 新增）**：若 idle 后 last assistant 无 error 字段但客户端预期有（pendingErrorCheck），调 `GET /session/{sid}/message`（或既有消息加载）重新拉取，从 durable Message.error 恢复。**文档化契约**：`status=idle` 不等于成功，须等 `message.updated`；若 message.updated 未带 error 但预期有，GET 兜底。

**为什么**：错误稳定展示是三个用户反馈之一，原 P0 无错误行为项。M5 修正：队列保存完整 payload + messageID 关联 + drain/上限/清理 + GET 恢复链，真正基于 durable error。

**风险**：(b) 排队机制增 ChatState 复杂度（集合有界，drain 后清）；(c) pendingErrorCheck + GET 兜底需在 SessionDeletedLocal/HostStatePurged 一并清防泄漏。

**验证**（+ §4d M10）：单测归档清错误（按 session.id）；route 不匹配→重排队列（含 payload+messageID）→回归重附；last.id != pending.messageID→sessionErrorsById 而非错位；busy→idle→pendingErrorCheck→message.updated 带 error→展示；idle 后无 error 但预期→GET 兜底恢复；清理（delete/archive/hostpurge）。`./scripts/check.sh`。

---

#### **P0-F abortPending 标志（带 startedAt token）+ 看门狗（abort UI 不停滞）**

**对症**：abort 不写乐观致 UI 停滞（R6）；sendingSessionIds 无看门狗（R5）；用户反馈 #1/#3；**M（建议项）abortPending 纯 Set 无法判超时 + watchdog 触发后仍 busy 时是否清 pending**。**独立可上线**。

**文件:行号**：
- `ui/AppStateSlices.kt:716`（SessionListState 增 `abortPendingSessionIds: Map<String, AbortToken>`，**非 Set**）。
- `ui/ChatViewModel.kt:409-425`（`abortSession` 派发 AbortPendingAdded(token)）。
- `ui/SseSessionListReducers.kt:297`（server idle 清 abortPending，或经 authority 投影）。
- `ui/chat/Composer.kt:164-165`（canStop + abortPending stopping 态）。

**具体改什么**：
1. `SessionListState` 增 `val abortPendingSessionIds: Map<String, AbortToken>`（`AbortToken(startedAtMs, requestToken)`）——**非纯 Set**（M 建议项：可判 ">10s"）。
2. `abortSession`（:409-425）：派发前 `dispatch(AppAction.AbortPendingAdded(sid, AbortToken(now, requestToken)))`；`.onFailure { dispatch(AbortPendingCleared(sid)) }`。
3. server idle 到达 sid（authority 投影或独立 reducer）→ 清 abortPending + 兜底清 sendingSessionIds[sid]（R5 兜底）。
4. 看门狗：`abortPendingSessionIds` 滞留 > 10s → 该 sid REST reconcile（ApplySnapshot）。**定义**：watchdog REST 后仍 busy → **保留 pending**（abort 可能已生效但 server 慢回 idle；UI 显「停止中」，禁二次 abort），不强制清（避免允许二次 abort）。
5. UI（Composer :164-165）：`val isAborting = sid in abortPendingSessionIds`；`canStop = currentSessionIsRunning && !canSend && !isAborting`；isAborting → 显「停止中」（disabled/spinner）。
6. idle 跨 slice 清理用单一原子 action（M 建议项）。

**为什么**：abort 纯服务端不写乐观，SSE 未投 idle 时 UI 停留 busy（R6）。abortPending(token) + 看门狗使 UI 不停滞且禁二次 abort。

**风险**：看门狗误清 → 现改为「保留 pending + REST 对齐」避免二次 abort（M 建议项）。

**验证**（+ §4d M10）：单测 abort→abortPending 含 token；server idle→清除；SSE 断流→10s 看门狗→REST；REST 后仍 busy→保留 pending（禁二次 abort）。`./scripts/check.sh`。

---

### P1（稳健性 / 模型补全 / 收敛深化）

> 注：P0-A 落地后，R3/R4/R8 已在 authority 内**真根治**（aggregator 派生）。P1 项是深化与补全。

---

#### **P1-A aggregator Aggregate 内部状态彻底消除**

**对症**：P0-A 已让 aggregator.entries 改派生（M3 真根治）；P1-A 进一步消除 aggregator 内部 `AtomicReference<Aggregate>` 缓存，直接订阅 authorityFlow 计算 GlobalBusyState。

**文件:行号**：`service/status/StatusAggregatorImpl.kt:170-181, 479-486`。

**具体改什么**：移除 `aggregate: AtomicReference<Aggregate>`；`GlobalBusyState`/idle-debounce 直接由 authorityFlow 投影计算（纯响应式）。保留 `commitPublishLock` 仅护 idle-debounce 调度。

**风险**：中（动聚合器核心）；需保留 `GlobalBusyState`/idle-debounce 语义。**缓解**：金钟回归 StreamingLifecycleCoordinator。

**验证**（+ §4d M10）：单测 GlobalBusyState 投影不变；idle-debounce 行为不变。`./scripts/check.sh`。

---

#### **P1-B retry `action` 模型补全**

**文件:行号**：`data/model/Session.kt:91-100`。

**具体改什么**：增 `val action: SessionStatusAction? = null`（`{reason,provider,title,message,label,link?}`）。legacy `session.status{retry}` 与 slim digest 穿透解析（ApplyEvent 携带）。保留 `MessageAbortedError` 静默策略。

**验证**：单测解析 retry action。`./scripts/check.sh`。

---

#### **P1-C freshness 轴 UI 落地（Unknown/Stale/Fresh，术语统一）**

**文件:行号**：`ui/chat/ChatScaffold.kt:583-584` + authority freshness 派生。

**具体改什么**：authority freshness（TTL `STATUS_TTL_MS≈30s`）透传投影；UI **stale-busy→Unknown**（统一术语，修 v1 §P1-C 与验收 #21 矛盾——stale-busy 不确定，归 Unknown 保守，不假装确知 busy）；stale-idle/首窗→Unknown（不进 idle 宽限期）。

**验证**（+ §4d M10）：单测首窗 Unknown；stale-busy→Unknown（术语统一）。`./scripts/check.sh`。

---

#### **P1-D statusLoadEpoch ↔ completenessEpoch 关联**

**对症**：R11（两独立 epoch）。

**文件:行号**：`ui/controller/StatusPollOrchestrator.kt`（statusLoadEpoch）+ `ui/SessionTreeHydrator.kt:117`（completenessEpoch）。

**具体改什么**：statusLoadEpoch advance 时 invalidate pending completenessEpoch 结果（单向失效）。

**风险**：中（动 epoch 交互，谨慎回归）。

**验证**（+ §4d M10）：单测 epoch 交叉。`./scripts/check.sh`。

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
**对症**：R8。`LegacySseHandler.kt:142-154`：target==null 时用 status event 的 directory（若有）或标 Unknown。P0-A 后经 authority 自动部分覆盖（ApplyEvent 带 workdir 兜底），P2-C 补 legacy 兜底。

#### **P2-D SessionStatus 类型硬化（sealed/enum）**
`data/model/Session.kt:91-100`：裸字符串 → sealed/enum，编译期约束。机械重构。

---

## 4a. 原子迁移事务边界（M6 详解）

> exp-1 核对三处原子提交形状。v2 设计：**`commit(op)` 同步返回 projection，原子调用方纳入自己的 `store.mutateState` CAS**，保持 sessionStatuses 与 tree/active/epoch 的原子性。

### 4a.1 BackgroundUnreadPoller（`BackgroundUnreadPoller.kt:175-222`）

**现状**（exp-1）：单 `store.mutateState` CAS 原子提交 `sessions/childSessions/completeRootIds/sessionStatuses/activeSessionIds/completenessEpoch + unread`；守卫 `identityValid() + currentHostProfileId + completenessEpoch`。

**v2 改法**：
```kotlin
store.mutateState { snapshot ->
    if (!identityValid() || snapshot.host.currentHostProfileId != startHostId ||
        snapshot.sessionList.completenessEpoch != startEpoch) return@mutateState snapshot
    // 1. 先算 authority projection（同步纯函数）
    val commitResult = authority.commit(ApplySnapshot(
        snapshot = normalizedStatuses, coveredWorkdirs = ..., authoritativeNodeIds = hydration.completeRootIds,
        unmappedActiveIds = ..., partialFailureWorkdirs = ..., requestToken = RequestToken(startHostId, startEpoch, startMonotonic), identity = ...,
    ))
    if (!commitResult.applied) return@mutateState snapshot   // authority epoch guard 拒绝 → 整体 no-op（与 epoch 守卫一致）
    // 2. projection 纳入同一 CAS（原子）
    val nextSessionList = snapshot.sessionList.copy(
        sessions = sessions, childSessions = children, completeRootIds = hydration.completeRootIds,
        sessionStatuses = commitResult.projection,   // ← authority 派生，与 tree 原子
        activeSessionIds = ..., completenessEpoch = snapshot.sessionList.completenessEpoch + 1L,
    )
    // 3. 评估 unread + 提交（同一 CAS）
    ...
}
authority.publish()  // 锁释放后通知 aggregator + 直接订阅者（store 已有 projection，idempotent）
```
- **原子性保留**：sessionStatuses 与 tree/active/epoch 仍在同一 CAS；authority epoch guard 与 store epoch 守卫一致（同 requestToken/epoch）。
- **若 CAS 被 epoch 守卫拒绝**：authority.commit 已 applied 但 store CAS 返回原 snapshot → 此时 authority 已更新但 store 未更新。**缓解**：authority.commit 与 store CAS 在同一同步调用内顺序执行；若 store CAS 失败（极少，因守卫一致），调 `authority.commit(ApplySnapshot(回滚 prior))` 或接受短暂不一致（aggregator 派生会随后续事件修正）。文档化此窗口。

### 4a.2 reduceSessionTreeHydrated（`SessionListFieldsReducer.kt:100-112`）+ AppAction.SessionTreeHydrated（`AppAction.kt:701-706`）

**现状**（exp-1）：epoch guard（`completenessEpoch != epochAtStart` → 全 no-op）；匹配则原子设 `childSessions + completeRootIds + sessionStatuses`。action payload 带 sessionStatuses。

**v2 改法**：payload 仍带 `sessionStatuses`（= authority projection，由 `SessionTreeHydrator.kt:132-150` 调 `authority.commit(ApplySnapshot(...))` 取得），reducer 仍原子设三字段。**保持原子性**（exp-1 警告：单独 dispatch 会撕开窗口，新子节点完成但 sessionStatuses 未到 → fail-closed 错误）。

### 4a.3 StatusPollOrchestrator REST merge（`StatusPollOrchestrator.kt:183-218, 424-434`）

**现状**（exp-1）：`mutateSessionList` CAS 提交 `sessionStatuses + activeSessionIds`；`localBefore`（REST 前 :170 捕获）/`localAfter`（lambda 内最新）；`mergeStatusSnapshot` 保护 REST 在途期间 SSE 更新。

**v2 改法**：
```kotlin
slices.mutateSessionList { sl ->
    if (myEpoch != statusLoadEpoch.get() || host mismatch) return@mutateSessionList sl  // epoch/host 守卫
    val localAfter = sl.sessionStatuses
    val commitResult = authority.commit(ApplySnapshot(
        snapshot = mergeStatusSnapshot(localBefore, localAfter, normalized),  // ← 在途保护逻辑迁入 authority snapshot 分支
        coveredWorkdirs = ..., authoritativeNodeIds = ..., requestToken = RequestToken(host, myEpoch, requestStart), ...
    ))
    sl.copy(sessionStatuses = commitResult.projection, activeSessionIds = ...)
}
authority.publish()
```
- **mergeStatusSnapshot 在途保护**：迁入 ApplySnapshot commit 分支（authority 内重算 localBefore/localAfter diff）；localBefore 由调用方在 REST 前从 authority.projection 捕获。

---

## 4b. 锁序与一致性模型（M8 详解）

> exp-2 核对 SharedStateStore 线程契约 + StatusAggregatorImpl 锁。v2 设计：**commit 纯函数 + deferred publication + 单向锁序**。

### 4b.1 线程与锁序

- **authority.commit(op)**：`synchronized(authority.lock){ 纯计算 + state.set(next) }`——持锁期间**无 dispatch、无 aggregator 调用、无协程启动**。锁仅护 AtomicReference CAS。
- **publish()**：`_pub.value = state.get()`（StateFlow 线程安全），锁释放后调。
- **store dispatch 线程契约**（exp-2 :182-183「MUST Main.immediate」）：authority 的单一 collector 跑在 `Dispatchers.Main.immediate`：
  ```kotlin
  authorityFlow.onEach { st -> store.dispatch(SessionStatusProjectionUpdated(projectionOf(st), bumpsOf(st))) }
      .launchIn(scope)  // scope = @UiApplicationScope (Main.immediate)
  ```
  - 原子调用方（M6）：projection 直接纳入 store CAS（同 Main 线程或同同步调用），publish 后 collector 的 dispatch 是 idempotent（projection 已等）。
- **aggregator 订阅**：`authorityFlow.onEach { recompute(it) }.launchIn(scope)`（Main.immediate）；`recompute` 在 aggregator `commitPublishLock` 内（既有 :479-486 模式）。
- **锁序单向**：authority.lock（commit 内）→ 释放 → 后续 aggregator.commitPublishLock（recompute 内）。**无反向**（aggregator 不调 authority）。**无嵌套**（commit 不持锁调 aggregator）。→ **无死锁**。
- **rescheduleFreshnessLocked（:615-657）**：exp-2 确认它 `scope.launch` 协程后 `delay`，外层 synchronized 早已释放；协程在 :646 重新获取锁是独立获取，非重入。v2 保留此模式（aggregator 派生后仍用既有 freshness 调度）。

### 4b.2 reducer 纯变换

- `reduceSessionStatusProjectionUpdated` 是纯 `state.copy(...)`，**不回调 authority**（修 M8 reentrancy）。
- authority 是写入漏斗；reducer 只消费 projection action。

### 4b.3 host purge 原子顺序

- `HostStatePurged` action 处理：调用方先 `authority.commit(PurgeHost(preserveServerGroup))`（清/留 authority.bySid），再 `store.dispatch(HostStatePurged)`（reducer 清/留 store 字段，镜像 §4c）。两者见一致 `preserveServerGroup` 标志。
- authority.commit 与 store.dispatch 在同一同步序列（collector Main.immediate）；projection 随后 dispatch 与 reducer 清/留一致。

### 4b.4 双写迁移 shadow 模型（P0-A 阶段 5）

- **shadow 期**：authority 与 legacy 并行计算 projection；**仅 legacy 驱动 UI**，authority 仅日志比对 divergence（per-sid diff）。
- **切换门槛**：连续 N 次（如 50）submit 的 divergence = 0（或差异均在已知兼容集，如 freshness 派生差异）→ 切换 authority 驱动 UI。
- **切换后**：legacy 写入移除（typed op 漏斗生效）；authority 唯一驱动。
- **不让新旧同时驱动不同用户可见投影**（修 M 建议项：避免扩大双源分歧）。

---

## 4c. scope/absence/purge 一致性证明（M9 详解）

> exp-1 核对 sid 唯一性、absence-reader、reduceHostStatePurged。

### 4c.1 I-SID-UNIQUE 不变量

- **exp-1 结论**：OpenCode 服务端 session id（UUID）在 server group 内**全局唯一**（实践）；codebase 的 `allSessionsById`（`SessionTree.kt:9-17`）用 `putIfAbsent` 已隐含假设；UI `sessionStatuses` 扁平 sid-keyed（`AppStateSlices.kt:716`）无 workdir 限定。
- **v2 处理**：UI 投影 `bySid.mapValues{status}` 扁平 sid-keyed **安全**（依赖 I-SID-UNIQUE）。aggregator 的 composite key（`SessionBusyStatus.kt:52-56` `(serverGroupFp, workdir, sid)`）**保留为防御性**（防未来 sid 冲突 + 跨 host stale frame 隔离）。文档化 I-SID-UNIQUE 为显式不变量。

### 4c.2 absence 语义保留（fail-closed）

- **唯一 absence-reader**（exp-1）：`UnreadSoakController.kt:75-78` `it !in sl.sessionStatuses` → 「真未知」→ fail-closed（抑制未读浸润）。
- **v2 projection 保留 absence**：`bySid.mapValues{status}` 的 key 集合 = authority 被通知过的所有 sid。REST ApplySnapshot 经 `normalizeAuthoritativeStatusSnapshot`（`SessionTree.kt:57-63`）把 `authoritativeNodeIds` 缺失 sid 填 explicit-idle → 这些 sid 进 bySid → projection 有 key。故「loaded subtree 的节点」必在 sessionStatuses；`it !in` 仍 = 「真未知」（未加载/未见）。
- **v1 缺陷修复**：v1 整图替换可能抹掉 REST normalize 填的 explicit-idle；v2 的 bySid 是 add+update（非全量 replace 抹 key），projection 保留所有已知 sid → absence 语义不破坏。

### 4c.3 purge 语义对齐

- **reduceHostStatePurged**（exp-1，`CrossSliceFieldsReducer.kt:106-259`）：
  - 跨组（`!preserveServerGroupData`，:128）：清 `sessionStatuses = emptyMap()` + 所有 per-server 数据。
  - 同组（`preserveServerGroupData==true`，:181+）：**保留 sessionStatuses** + 所有 per-server 数据；仅清 `pendingCreateIds/pendingCreatedAt/activeSessionIds`。
- **v2 `PurgeHost(preserveServerGroup)`**：精确镜像——跨组清空 `bySid`；同组保留 `bySid`（仅清 activeSessionIds 等非 status 字段，由 reducer 处理）。**修 v1 缺陷**：v1 `purgeForHostSwitch()` 无条件清会抹掉同组应保留态。

---

## 4d. 测试门槛（M10 详解）

> `./scripts/check.sh`（编译 + 单测）是必要非充分。状态机重构增补：

### 4d.1 静态架构 gate（编译期 + AST 规则）

- **编译期**：`sessionStatuses` 的 setter（`SessionListState.copy`）标记 `@VisibleForTesting internal`；唯一公开写入路径是 `reduceSessionStatusProjectionUpdated`（同 module internal）。业务代码无法 `sessionStatuses =`。
- **编译期**：`StatusAggregatorInput` 移除 `refresh/applySseStatus/markRequestFailed`（只剩读 API `globalState/statusByKey/stateAtNow`）；编译器禁用旧调用。
- **AST 规则**（`ast-grep` 或 detekt 自定义规则，CI 跑）：禁 `mutateSessionList { sessionStatuses = ... }`（除 projection reducer）；禁非 authority 调 aggregator mutation。

### 4d.2 状态转换穷举表

- `AuthorityOp × SessionEntry 初始状态 → 期望 CommitResult` 矩阵（ApplyEvent slim/legacy/optimistic × {idle/busy/retry} × {serverRound null/旧/新/incarnation-advance} × {optimisticClaim null/未echo/已echo/guardedDrop}）。穷举覆盖，CI 跑。

### 4d.3 场景测试（除穷举外）

- **混合来源顺序**：optimistic → server echo → idle；REST snapshot 在途期间 SSE；tree hydrate 与 REST 交叉。
- **跨通道反序**（M1/M5）：server busy 先于 HTTP success → OPTIMISTIC serverEchoed=true → server idle 应用（不误挡）。
- **slimapi debounce + restart incarnation**（M7）：ingest 时 turn 快照（非 flush）；incarnation advance 重置 serverRound 不冻结。
- **partial workdir 失败**（M2）：ApplySnapshot partialFailureWorkdirs 保留 prior。
- **host switch + in-flight REST/POST**（M6/M8）：epoch/requestToken guard DROP；同组 vs 跨组 purge。
- **投影一致性序列**：`store.sessionStatuses == authority.projection == aggregator 派生 entries`（每步断言）。
- **原子迁移**（M6）：BackgroundUnreadPoller/Hydrator/Poller projection 与 tree 在同一 CAS（epoch guard 拒绝时整体 no-op）。
- **幻影 optimistic 不钉死**（M3）：optimistic busy → aggregator OptimisticBusy → TTL/watchdog 清 → GlobalBusyState 可转 idle → 45s idle-debounce/StopSse 可触发（SSE 不永久开）。
- **durable error 恢复**（M5）：归档清；route 不匹配排队（payload+messageID）；messageID 错位→sessionErrorsById；idle 后 GET 兜底；清理（delete/archive/hostpurge）。

---

## 5. oc-slimapi 跨项目协作契约（强 fence 增强路径，M7）

> oc-slimapi（用户自建 Python 中继层）可改。本节定义协作契约（M7：turn 在 ingest 时快照 + 共享 registry + incarnation）。

### 5.1 为何需要 slimapi 介入

- 客户端确认门（Tier 2）能挡「optimistic 未 echo 时的 stale idle」，但**无法可靠区分**「同 identity 内两条 server-originated status 帧的代际」（都非 optimistic）。
- slimapi 作为中继**观察真实 POST/abort 流量**，能派生服务端侧权威因果标识 `(turnIncarnation, turn)`，远胜客户端推断。
- 经 slimapi 的 slim 路径获得 Tier 1 强 fence；不经 slimapi 的 legacy 路径用 Tier 2 启发式 + watchdog。两层覆盖。

### 5.2 契约：slimapi 附加字段（v2，M7）

**slimapi 在转发的每个 `session.digest`（及任何 status 中继）payload 附加 `(turnIncarnation, turn)`**：

```json
{
  "type": "session.digest",
  "sessionId": "<sid>",
  "status": "busy",
  "turnIncarnation": 7,    // ← 新增：slimapi 生命周期 epoch（restart 跳变）
  "turn": 3,               // ← 新增：per-(serverGroupFp,sid) 单调，在事件 ingest 时快照
  ...
}
```

**(turnIncarnation, turn) 语义（M7 修正）**：
- **`turnIncarnation`**：slimapi 生命周期 epoch。slimapi 启动时分配（持久化跨 restart 单调递增，或 restart 时 bump）；每次 slimapi restart → incarnation++。客户端见 incarnation advance → 重置 serverRound 基线（M1 restart 不冻结）。
- **`turn`**：per-`(serverGroupFp, sid)` 单调整数（防御性复合 key，对齐 aggregator）。
- **turn 在事件 ingest 时快照（M7 核心）**：slimapi 的 `global_hub` 250ms debounce（`global_hub.py:243-290`）必须在**事件进入 slimapi 时（ingest）**把当时 turn 快照进 `DigestFields`，**不是 flush 时读当前 turn**（否则旧 idle 被标成新轮次）。
- **turn increment commit point（M7）**：slimapi **forward POST `/session/{sid}/prompt` 或 `/abort` 上游时**（请求进入 forward path，await response 之前），`turn[(serverGroupFp,sid)] += 1`。forward 失败（连接错/非 2xx 前）→ **不 increment**（轮次未真正开始）。
- **共享 turn registry（M7）**：proxy 与 global_hub 共享同一 registry（进程内 map + 锁，或共享存储）；key = `(serverGroupFp, sid)`；并发安全；restart incarnation 单独维护。
- **failed-request 处理（M7）**：POST forward 失败 → turn 不增（client 收到错误，不写 optimistic）；已增但上游 5xx → turn 已增代表「轮次尝试过」，client 经由 status 帧知晓结果。

### 5.3 ocdroid 侧消费

- `SessionSyncCoordinator.handleSessionDigest`（:978-991）：解析 `val inc = payload.optLong("turnIncarnation"); val turn = payload.optLong("turn")`。
- 非空 → `authority.commit(ApplyEvent(sid, status, SSE_SLIM, identity, serverRound = ServerRound(inc, turn), ...))`。
- authority commit 层 Tier 1：`(inc, turn) < cur.serverRound`（lex）→ DROP；`inc > knownIncarnation` → 重置 serverRound 基线。
- 空（slimapi 未升级 / 字段缺失）→ 自动降级 Tier 2 确认门 + watchdog（向后兼容）。

### 5.4 落地协作

| 项 | 责任方 | 内容 |
|---|---|---|
| slimapi 附加 `(turnIncarnation, turn)` + ingest 快照 | oc-slimapi | forward POST/abort 时 increment（commit point）；digest ingest 时快照 turn 进 DigestFields；共享 registry；incarnation lifecycle |
| ocdroid 消费 | 本方案 P0-B | 解析 `(inc,turn)` → ApplyEvent.serverRound；incarnation advance 重置；缺失降级确认门 |
| 契约版本协商 | 双方 | 字段可选（缺失=legacy 行为），无硬依赖，渐进部署 |

> **向后兼容**：字段可选。slimapi 未升级时 ocdroid 用 Tier 2 fallback（确认门 + watchdog），不阻断。升级后自动获得强 fence。**不强求 slimapi 同步上线**。

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
- `catchCauseIf(!Cause.hasInterruptsOnly)` 在 retry 之内：纯 interrupt 谓词 false → 穿透 → retry 不 step → 穿透 catch(halt) → 到 ensuring(cleanup)。
- retry schedule 的 `set`（写 status=retry）只在 retry 捕获 typed failure 时调用（retry.ts:189）—— interrupt 永不触达。

### 6.3 awaitDone 的「retry」真相（runner.ts:59-65）

```ts
const awaitDone = (done) = Deferred.await(done).pipe(
  Effect.catchTag("RunnerCancelled", (e) => onInterrupt ?? Effect.die(e))  // 一次性 onInterrupt
)
```

捕获 `RunnerCancelled` 返回 `onInterrupt`（`lastAssistant` → `finalizeInterruptedAssistant`）。**一次性取消，非 LLM 重试循环**。

### 6.4 retry-backoff 中 abort 的状态序列（rev-opus 机制精确化）

> rev-opus 指出 v1 §6.4「onInterrupt 在 backoff 触发」描述有瑕疵：onInterrupt 不必然在 backoff 期触发；真正 fallback 是 `finalizeInterruptedAssistant`。结论不变。

**`retry → idle`（直接，无中间 busy）**。
- retry backoff 期间 `ctx.assistantMessage.error` 未设（retry 在 halt 之前捕获初始 failure）。
- abort → interrupt → onInterrupt（若 backoff 已结束则在 runner 主流程；若在 backoff 则由 `Deferred.await(done)` 的 `RunnerCancelled` 捕获走 finalizeInterruptedAssistant）→ `halt`（因 `!error`）→ 设 error + `status.set(idle)`。
- Runner.cancel 同时 onIdle → status=idle（幂等）。
- ensuring(cleanup) → session.updateMessage 落盘 error（:595-596）。

### 6.5 对 ocdroid 的影响

1. 客户端无需建模「interrupt 后重试」——abort 一次性硬杀，无 retry 续命。
2. **retry 期 stop 应可用**（isRetry ∈ canStop）——反馈 #1「stop 消失」由 P0-B ServerRound fence + 确认门修复（防 stale idle 覆盖 retry）。
3. **abort 后 idle 先到、error 后到**（两阶段）：ocdroid 须 idle 后等 `message.updated` 读 `message.error`（P0-E），勿在 idle 判成功；idle 后无 error 但预期有 → GET 兜底（M5）。
4. interrupt vs abort 分级（TUI 双击）：ocdroid 单一 abort 已满足；P0-F abortPending 提供「停止中」中间态。

---

## 7. 竞态覆盖矩阵（main-B §7 逐条，v2 真根治标注）

| 竞态 # | 描述 | 覆盖改动项 | 根治方式（v2） |
|---|---|---|---|
| **R1** | 同 identity 内 stale idle 覆盖 optimistic busy | **P0-A + P0-B** | authority commit 层 `ServerRound(inc,turn)`（slim 强，lex）+ 确认门（legacy，echo-based）+ watchdog 自愈 DROP stale idle；**空间分离**（M1） |
| **R2** | POST 成功 vs host 切换污染新 host | **P0-A + P0-C** | authority identity guard + onSuccess isCurrent 双防线；SseDispatchHost 扩 identity |
| **R3** | 双源不同步（sessionStatuses vs 聚合器） | **P0-A**（**真根治**） | 单一权威源双投影；**aggregator 改纯响应式投影**（entries 派生，mutation API 移出公共接口）；slim/optimistic/REST/tree 全经 authority → aggregator 派生 |
| **R4** | applySessionStatus 无 timing | **P0-A + P0-B** | authority 内 merge-timing（connectionMonotonicMs，非因果 tie-breaker）+ ServerRound fence |
| **R5** | sendingSessionIds 无看门狗、abort 不清 | **P0-F** | abortPending(token) 看门狗 + idle 兜底清 sendingSessionIds |
| **R6** | abort 不写乐观 idle，UI 停滞 | **P0-F** | abortPending(token) 显式化 + 看门狗 + stopping UI |
| **R7** | archive 早返回跳过状态刷新 | **P0-D** | reconcile 基于已提交 tree + cold-start 门（M4） |
| **R8** | target==null 绕过聚合器 | **P0-A**（部分）+ **P2-C** | authority 统一投影；ApplyEvent 带 workdir 兜底；legacy target==null 由 P2-C 补 |
| **R9** | sessionErrorsById 归档不清 | **P0-E(a)** | 归档清 sessionErrorsById（按 session.id，修字段名 bug） |
| **R10** | reduceLastAssistantErrorAttached 静默丢弃 | **P0-E(b)** + **P1-E** | 排队重附（payload + messageID 完整）+ GET 兜底 |
| **R11** | 两个独立 epoch 无关联 | **P1-D** | statusLoadEpoch ↔ completenessEpoch 关联 |

**覆盖统计**：11/11 逐条覆盖。P0 真根治 9 条（R1/R2/R3/R4/R5/R6/R7/R9/R10），P1 补 1 条（R11），P2 补强 1 条（R8）。**R3 因 aggregator 改纯响应式投影而结构性真根治**（M3 option a，非声称）；R4/R8 因单一权威源结构性改善。

---

## 8. 验收场景（≥23，标注优先级与修复后期望行为）

> ✅=现状已满足（回归守护）；🟡=本方案修复后期望；🔵=v2 新增（针对 M1-M10）。

1. **(🟡 P0-A/B)** 任何写入路径 → 经 authority.commit(typed op) → 投影与 aggregator 派生同源（双投影一致）。
2. **(🟡 P0-B)** optimistic busy 写入（claim 未 echo）→ 旧轮次 `session.status{idle}` 到达 → 确认门 DROP + watchdog arm → 5s REST reconcile 清（非无限 DROP）。（反馈 #1 核心）
3. **(🟡 P0-B)** slim digest (inc=5,turn=3) 后到 (inc=5,turn=2) → lex DROP。
4. **(🔵 M1)** slim restart (inc 5→6) → 旧 serverRound 重置 → 新 inc 事件「当前」不冻结。
5. **(🟡 P0-B)** optimistic → server busy/retry（echo 确认或 turn）→ pending 清 → server idle → 应用。
6. **(🔵 M1/M5)** cross-channel：server busy 先于 HTTP success → OPTIMISTIC serverEchoed=true → server idle 应用（不误挡）。
7. **(🟡 P0-C)** POST 在途 host 切换 → onSuccess isCurrent false → 不 commit → 新 host sessionStatuses 不含该 sid。
8. **(✅ 回归)** POST 在途 host 未切换 → onSuccess → optimistic busy 正常写入（经 authority）。
9. **(🟡 P0-D)** 冷启无可保留 current session → 仍加载 bulk status → 服务端 busy 会话显示运行态。
10. **(🟡 P0-D)** 冷启命中 archive 早返回 → onLoadSessionStatus 仍触发（基于已提交 tree，exactly-once）。
11. **(🔵 M4)** 二次 reload → cold-start gate 已 consume → 不再重复 bulk status。
12. **(✅ P0-D 边界)** slim 空目录 → complete(true) 不产条目 → 聚合器 Unknown → UI 保守（P1-C 后）。
13. **(🟡 P0-Ea)** 归档 session → sessionErrorsById 清除（按 session.id）→ 无陈旧错误横幅。
14. **(🟡 P0-Eb)** LastAssistantErrorAttached route 已切换 → 进 pendingErrorReattach（含 payload+messageID）→ route 回归 → 重附（非静默丢）。
15. **(🔵 M5)** pending.messageID ≠ last assistant.id → 放 sessionErrorsById（非错位附新 assistant）。
16. **(🟡 P0-Eb)** last==null → 进 pendingErrorRefresh → 消息加载 → 重附。
17. **(🟡 P0-Ec)** busy→idle → pendingErrorCheck → message.updated 带 error → 展示；无 error 但预期 → GET 兜底恢复。（反馈 #2，两阶段 + durable GET）
18. **(🟡 P0-F)** abort → abortPending(token) → UI 显「停止中」（禁二次 abort）；server idle → 清 abortPending + 兜底清 sendingSessionIds。
19. **(🟡 P0-F)** abort + SSE 断流 → 10s 看门狗 → REST reconcile；REST 后仍 busy → 保留 pending（禁二次 abort）。（反馈 #3）
20. **(✅)** retry 窗口（429/5xx）→ server status{retry} → stop 保持（isRetry）；retry message 可见。
21. **(✅)** retry 中 abort → server retry→idle（直接，§6.4）→ stop 清除；无乐观 idle 竞态。
22. **(🟡 P0-A)** slim digest status → authority → aggregator 派生 → GlobalBusyState 不落后 UI（R3 真根治）。
23. **(🔵 M3)** 幻影 optimistic busy → aggregator OptimisticBusy → TTL/watchdog 清 → GlobalBusyState 可转 idle → 45s idle-debounce/StopSse 可触发（SSE 不永久开，不耗电）。
24. **(✅)** 轮询途中切 host → epoch bump → 过期 REST status 丢弃；新 host status 加载。
25. **(🔵 M9)** 投影保留 REST normalize 的 explicit-idle → `it !in sessionStatuses` 仍 = 真未知 → UnreadSoakController fail-closed 不破坏。
26. **(🔵 M9)** 同组 host switch → sessionStatuses 保留（purge 镜像 reduceHostStatePurged）；跨组 → 清空。
27. **(🔵 M6)** BackgroundUnreadPoller/Hydrator/Poller → sessionStatuses 与 tree/active/epoch 同一 CAS 原子提交；epoch guard 拒绝 → 整体 no-op。
28. **(🟡 P1-B/P2-A)** free_tier_limit/account_rate_limit retry → action 穿透 → UI 显示 subscribe/open-settings。
29. **(🔵 M1-C)** TTL 后 stale-busy → freshness Unknown（术语统一）→ 不进 idle 宽限期；保守至 REST reconcile。
30. **(✅ I1/I2)** 服务端进程重启 → GET /session/status 空 → 客户端 reconcile 清幻影 busy；durable Message.error 仍显示历史失败。
31. **(✅)** compaction 运行 → isCompacting → stop 可用；结束 → 清除。

---

## 9. 落地顺序（结构性阶段，标注依赖；v2 重排）

> v2 重排：M 建议项指出 P0-E(c)/P0-F 依赖 authority 感知 busy→idle，却排在 P0-A 之前会造成二次重写。v2 调整：P0-E(a)/(b)/(c) 与 P0-F 中「依赖 busy→idle 感知」的部分移到 P0-A 之后；仅机械/独立部分先头。

| 阶段 | 改动项 | 依赖 | 风险 | 收益 |
|---|---|---|---|---|
| **1** | **P0-E(a)** 归档清 sessionErrorsById（修字段名 bug） | 无 | 极低（机械） | 错误横幅不滞留 |
| **2** | **P0-D** 冷启 reconcile（已提交 tree + cold-start 门，M4） | 无 | 低 | 反馈 #3 运行态 |
| **3** | **P0-F（独立部分）** abortPending(token) 显式化 + UI stopping | 无 | 低（additive） | 反馈 #1/#3 stop 态 |
| **4** | **P0-A** SessionStatusAuthority（typed op + aggregator 收编 M3 + 原子 M6 + 锁序 M8；**双写 shadow 期**） | 无 | 高（核心） | 单一权威源基础（M1/M2/M3/M6/M8 地基） |
| **5** | **P0-C** optimistic 走 authority + identity guard（含 SseDispatchHost 扩接口） | P0-A | 低-中 | host 切换不污染（M8 identity） |
| **6** | **P0-B** generation fence（ServerRound 强 + 确认门 + watchdog；**M1 空间分离**） | P0-A | 中 | **反馈 #1 主根因 + M1** |
| **7** | **P0-E(b)(c)** 错误重排队列（payload+messageID）+ 两阶段 + GET 兜底（M5） | P0-A（busy→idle 感知）/ P0-E(a) | 中 | 反馈 #2 错误稳定（M5） |
| **8** | **P0-F（依赖部分）** abortPending 看门狗（idle 感知 + REST 后仍 busy 保留 pending） | P0-A | 低-中 | 反馈 #1/#3 完整 |
| **9** | **P0-A 切断旧路径**（shadow 验证 N 次后切换 authority 驱动 UI） | 4/5/6/7/8 验证 | 中 | 双源真根治（R3/R4） |
| **10** | **§5 slimapi 协作**（跨项目，渐进；M7 ingest 快照 + incarnation） | P0-B | 低（可选） | 强 fence |
| **11** | **P1-A** aggregator Aggregate 内部状态消除 | P0-A 稳定 | 中-高 | 彻底单一权威（深化 M3） |
| **12** | **P1-C/P1-B** freshness（术语统一）+ action 模型 | P0-A | 低 | 保守 UI + upsell |
| **13** | **P1-D** epoch 关联 | 无 | 中 | R11 |
| **14** | **P2-A/B/C/D** 体验/硬化 | 对应 P1 | 低 | 体验对齐 |

> **策略**：阶段 1-3 低风险高收益先头（不依赖 authority，可独立上线）；阶段 4-9 是核心结构重构（双写 shadow 迁移降风险，§4b.4）；阶段 10 slimapi 协作渐进（向后兼容）。每阶段 `./scripts/check.sh` **+ §4d 测试门槛（M10）**。UI 改动（P2-A 等）遵循 `docs/specs/ui-style-spec.md`。

---

## 10. 被否决的方案

| 否决项 | 否决理由 | 证据 |
|---|---|---|
| **保留双源（sessionStatuses + 聚合器各自维护），仅加补丁同步** | 用户明确要**结构性权威重构**。补丁同步留竞态窗口（R3/R4/R8），难维护。 | omni 方向校正 #3 |
| **延长 sendingSessionIds 贯穿 POST+流+retry** | 设计上就是 POST 在途短桥接。延长引入客户端标志与服务端脱节的新竞态。 | main-B §2.2 |
| **wall-clock / 客户端接收时间作因果版本覆盖** | 客户端时钟不可作因果序。本方案用 ServerRound(inc,turn)（slim 强）+ 确认门（弱 + watchdog），非 wall-clock。 | rev-ogpt #8 / 硬伤⑦ |
| **POST 失败回滚乐观 busy** | ocdroid 乐观 busy 仅 POST onSuccess 后写；失败未写 busy，无回滚对象。 | main-B §2.1；exp-2 确认 :418-432 无 optimistic/rollback |
| **abort 写乐观 idle** | abort 与 server idle race。正确做法是 abortPending + 看门狗（P0-F）。 | P0-F；main-B §2.3 |
| **照搬 V2 执行事件 / V2 reducer / Runner Shell 态** | ocdroid 消费 V1，服务端不发 V2 执行事件；Runner Shell 是服务端内部态，客户端不可见。 | main-A §6.3 N1-N4 |
| **照搬 SolidJS reconcile/produce 机制** | 框架特定。ocdroid 用 Kotlin/Compose，借鉴思想（细粒度 diff）非 API。 | main-A §6.3 N5 |
| **session.idle 废弃事件** | 上游已废弃，勿实现。 | main-A §6.3 N6 |
| **依赖 opencode 服务端改动** | 上游服务端不可改。所有 fence 在客户端 + slimapi 中继层实现。 | omni 方向校正 #1/#2 |
| **强求 slimapi 同步上线** | (turnIncarnation,turn) 字段可选，缺失降级 Tier 2 确认门 + watchdog。渐进部署，不强耦合。 | §5.4 |
| **v1 单一 submit(StatusUpdate, generation: Long?) 混用空间（v2 否决）** | client bump + server turn + restart 重置塞同一 Long 空间 → 冲突/冻结（M1）；无法表达整图（M2）；声称根治 R3 而 aggregator 可旁路（M3）。v2 改 typed AuthorityOp + 分离 ServerRound/OptimisticClaim 空间 + aggregator 纯响应式投影。 | 双评审 M1/M2/M3 |

---

## 11. 引用索引

### 11.1 输入材料
- 上游调研：`docs/research/2026-07-30-opencode-web-state-machine-survey.md`（main-A）
- 现状调研：`docs/research/2026-07-30-ocdroid-state-machine-survey.md`（main-B）
- 双评审综合报告（M1-M10）：`.omni-orch/reports/ses_050154d13ffeChVTyCWthc2ll8.md`
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

### 11.3 ocdroid 结构性代码点（v2 经 exp-1/exp-2 二次核对，2026-07-30）
| 主题 | 文件 | 行 | 现状 / v2 核对结论 |
|---|---|---|---|
| SessionListState 声明 | `ui/AppStateSlices.kt` | 714, 716 | sessionStatuses 扁平 sid-keyed（exp-1：无 workdir 限定，依赖 I-SID-UNIQUE） |
| StoreState 聚合 | `ui/StoreState.kt` | 28-35 | 单一复合聚合 |
| SharedStateStore（CAS + 线程契约） | `ui/SharedStateStore.kt` | 54-56, 88-90, 162-163, 182-183, 250-252 | exp-2：dispatch CAS 线程安全；:182 kdoc「MUST Main.immediate」覆盖所有 mutateXxx；reduce 同步在调用线程 |
| AppAction sealed | `ui/AppAction.kt` | 49, 572-576, 664-668, 701-706, 815-883 | SessionStatusPatched / LastAssistantErrorAttached(含 error) / SessionTreeHydrated(含 sessionStatuses) |
| reduceSessionStatusPatched | `ui/SessionListFieldsReducer.kt` | 62-71 | 裸 `+`，无护栏（path #5） |
| reduceSessionTreeHydrated | `ui/SessionListFieldsReducer.kt` | 100-112 | exp-1：epoch guard 全 no-op；匹配则原子设 childSessions+completeRootIds+sessionStatuses |
| reduceHostStatePurged | `ui/CrossSliceFieldsReducer.kt` | 106-259, 128, 181+ | exp-1：跨组清 sessionStatuses；同组保留（仅清 pending/active） |
| bumpSessionUpdated | `ui/ViewModelSupport.kt` | 188-196 | optimistic 防覆盖 |
| 原始 mutateSessionList status 写 | `ui/controller/StatusPollOrchestrator.kt` 等 | 183-218, 363-404, 424-434 / 988 / 149 / 175-222 / 251 | exp-1：均 epoch-guarded 原子 CAS |
| sessionStatuses 读点 | ~25 处 + UnreadSoakController | UnreadSoakController.kt:75-78 | exp-1：唯一 absence-reader（fail-closed） |
| StatusAggregatorImpl | `service/status/StatusAggregatorImpl.kt` | 96-102, 170-181, 201, 207-209, 230-231, 257-358, 386-397, 404-443, 479-486, 556-561, 615-657 | exp-2：单 commitPublishLock；applySseStatus 硬编码 fresh=false(:391)；coverage 只 refresh 写(:347-358)；Busy 不过期(:556-561)；rescheduleFreshnessLocked 起协程非重入 |
| StatusModule 注入 | `service/status/StatusModule.kt` | 53-74 | @Provides+@Binds |
| StatusAggregatorInput 接口 | `service/status/StatusAggregator.kt` | 162-219 | refresh/applySseStatus/markRequestFailed（v2 移出公共接口） |
| SessionStatusKey | `service/status/SessionBusyStatus.kt` | 28-34, 40-42, 52-56 | composite key（防御性，I-SID-UNIQUE） |
| FORK POINT + identity 缺口 | `ui/controller/sse/LegacySseHandler.kt` | 130-169 | exp-2：双写无协调；无 ConnectionIdentity（用 host.serverGroupFp()） |
| SseDispatchHost 接口 | `ui/controller/sse/SseDispatchHost.kt` | 27-126 | exp-2：不暴露 identityStore（v2 须扩 currentIdentity()） |
| GlobalBusyState 消费者 | `ui/controller/StreamingLifecycleCoordinator.kt` | 837,881,1001,1185-1208,1470 | idle-debounce/handoff |
| SessionStreamingController | `ui/controller/SessionStreamingController.kt` | 150-159, 177-196, 438 | busy chronometer/bootstrap；markRequestFailed 调用 |
| ProcessStatusPoller | `ui/controller/ProcessStatusPoller.kt` | 255, 461-475 | refresh 调用（v2 改 ApplySnapshot） |
| SlimStatusFanOut | `service/status/SlimStatusFanOut.kt` | — | 不喂聚合器（v2 经 authority 投影） |
| launchSendMessage | `ui/SessionMutationActions.kt` | 312-441, 376-379, 418-432 | exp-2：自由函数无 identity 参数；onSuccess 无 guard（:376-379 裸 dispatch） |
| dispatchSendMessage | `ui/AppCoreOrchestration.kt` | 939-960, 780, 825, 1013 | exp-2：AppCore 扩展，有 identityStore(AppCore:160) 但未用；3 调用点不传 identity |
| abortSession | `ui/ChatViewModel.kt` | 409-425 | 无乐观/不清 |
| Composer canStop | `ui/chat/Composer.kt` | 164-165 | isBusy && !canSend |
| currentSessionIsRunning | `ui/chat/ChatScaffold.kt` | 583-597 | busy/retry ∪ sending |
| SessionStatus 模型 | `data/model/Session.kt` | 8-10, 91-100 | id 来自服务端（exp-1）；4 字符串字段，无 action |
| reduceSessionArchivedLocal | `ui/SessionListFieldsReducer.kt` | 28-44 | 不清 sessionErrorsById（v2 按 session.id 清，修字段 bug） |
| reduceLastAssistantErrorAttached | `ui/ChatFieldsReducer.kt` | 234-250 | 静默丢弃（v2 排队 payload+messageID） |
| archive 早返回 | `ui/controller/SessionListRefreshOrchestrator.kt` | 90-139, 121-131, 195-198 | 跳 onLoadSessionStatus（v2 基于已提交 tree + cold-start 门） |
| slim 空目录 | `ui/controller/StatusPollOrchestrator.kt` | 310-316 | complete(true) |
| ConnectionIdentityStore | `service/identity/ConnectionIdentityStore.kt` | 86, 93, 119-129, 194-203, 211-222, 238-244 | exp-2：currentIdentity(StateFlow)/currentEpoch()/commitIfCurrent()/isCurrent()；ConnectionIdentity{epoch,serverGroupFp,normalizedWorkdir,endpointFp} |
| allSessionsById（I-SID-UNIQUE） | `ui/controller/SessionTree.kt` | 9-17, 53-63 | exp-1：putIfAbsent 隐含假设 sid 唯一；normalizeAuthoritativeStatusSnapshot 填 explicit-idle |

### 11.4 相关规范
- `AGENTS.md` — 改动校验（`./scripts/check.sh`）、模拟器纪律、UI 样式
- `docs/specs/ui-style-spec.md` — UI overlay 三层规则（P2-A 须遵循）
- `.opencode/policies/build-signing.md` — 构建/校验规则

---

*v2 返修版结束。本方案为结构性权威重构（typed AuthorityOp 入口 + 分离 ServerRound/OptimisticClaim 计数空间 + aggregator 纯响应式投影 + 纯 commit/deferred publish + 原子迁移事务边界），已逐条解决双评审 M1-M10（M1 generation 协议 / M2 bulk API / M3 aggregator 收编 / M4 已提交 tree reconcile / M5 payload 完整重排队列 / M6 原子迁移 / M7 slimapi ingest 快照 / M8 锁序 / M9 scope-absence-purge / M10 测试门槛）；11 条竞态逐条覆盖（§7，R3 因 aggregator 改纯响应式投影而真根治）；interrupt/retry 分歧已澄清（§6）；每个改动点附 `文件:行号 + 改法 + 风险 + 验证`（exp-1/exp-2 二次核对）。下一步由编排者安排 rev-ogpt + rev-opus 独立重评。*
