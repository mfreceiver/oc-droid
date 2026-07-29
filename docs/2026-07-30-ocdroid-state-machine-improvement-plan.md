# ocdroid 会话状态机：结构性权威重构方案（v3，已解决 B1-B11）

> **状态**：**v3，已解决 B1-B11**（v2「有条件可进入实施」，B1-B11 均为局部规格修订，修完即可进入实施）。全新体系方案（**未实现**）。
> **证据基线（固定）**：
> - 上游 `opencode-src/v1.18.7/`（**禁用** `current` 软链；上游服务端**不可改**，所有 fence 在 ocdroid 客户端实现）。
> - ocdroid `app/src/main/java/cn/vectory/ocdroid/`。
> - oc-slimapi（`/home/mar/personal_projects/oc-slimapi`，用户自建 Python 中继层）可改——强 fence 增强路径（见 §5）。
> **日期**：2026-07-30（v3：2026-07-30）。**bundle**：B-ocdroid-sm-20260730。
> **协议定位**：ocdroid 消费上游 **V1 线协议**（`session.status` / `session.digest` / `session.error` / `message.part.*` / `GET /session/status`）。V2 执行事件仅作背景。
> **审计**：v1 读 3 份输入 + 4 explorer；v2 返修读 v1+评审 M1-M10 + 2 explorer；**v3 返修读 v2 重评报告（B1-B11）+ 复用 2 个 explorer 会话**（exp-1 ses_05001edeaffe：B7 真实 action 名/B2 messages GET/B5 删除归档；exp-2 ses_05001ca64ffe：B1 StoreState 切片机制/B4 aggregator 调用点全量）。所有 `文件:行号` 经 explorer 二次确认。
> **方向约束（omni 确认，不可变）**：①纯客户端改进；②oc-slimapi 可辅助强 fence；③结构性收敛（权威重构，非最小改动），目标**清晰易维护**的新体系；④通过后即实施，可执行性是硬要求。

---

## v3 变更说明（相对 v2 的修订，逐条解决 B1-B11）

v2 经 rev-ogpt + rev-opus 重评：**Verdict 有条件可进入实施**——M1（v1 最致命阻断）真解决、架构方向自洽（两位共识），剩余 11 项（B1-B11）均为局部规格修订。v3 逐条闭合。**核心动作 B1 反而让实现模型比 v2 更简单**（消除 authority 独立 CAS/锁/collector/conflation）。

| 评审项 | v2 问题 | v3 修订（节） |
|---|---|---|
| **B1 事务模型**（分歧桥接，采用 ogpt option 1） | §4a.1 把 `authority.commit()` 写进 `MutableStateFlow.update{}` CAS retry lambda → CAS 重试重复执行 + authority applied 而 store CAS 拒绝的分裂窗口；v2「回滚或接受短暂不一致」均不安全 | **option 1：AuthorityState 并入 StoreState 单一 CAS**。authority 逻辑改为**纯 reducer** `reduceAuthority`；`sessionStatuses` 保留为存储字段但**唯一写入者**= authority reducer（同一 `state.copy` 内算 projection）；identity guard 移到 dispatch 站点（B11 event-captured），reducer guard 纯（scope/requestToken）。**CAS retry 安全**（纯 reducer 幂等）；**无分裂窗口、无回滚**。§2.2、§4 P0-A、§4a、§4b |
| **B2 M5 messageID 不可获得** | 上游 `session.error` payload `{sessionID,error}` **不带 messageID**（`processor.ts:619-623`、`SlimSseHandler.kt:66-75` 实证）；`LastAssistantErrorAttached`（AppAction.kt:572-576）也无 messageId，靠 `lastOrNull{isAssistant}` 定位 | 重设计为 **session 级 pendingErrorCheck → `getMessages(sid)` GET 定位真实带 error 的 assistant（用服务端 message.id 关联）**，不假定 action.messageId。§4 P0-E |
| **B3 ApplySnapshot 缺 sid→workdir + registeredWorkdirs** | ApplySnapshot 缺 sid→workdir 映射/registeredWorkdirs/lastSuccessTimeMs/scope 合并；`AuthorityState.coverage: Coverage?` 单字段表达力不足 → AllIdleFresh 算不出；示例 `authoritativeNodeIds=completeRootIds` **错误**（仅根 ID）破坏 M9 absence | ApplySnapshot 补 `sidToWorkdir`/`registeredWorkdirs`/`lastSuccessTimeMs`/`scopeKey`；coverage 改 `Map<ScopeKey,Coverage>`；示例修正为**全部 session IDs**（`allSessionsById` flatten）。§2.2、§4 P0-A |
| **B4 aggregator 调用点漏+标错+同步读序+缺 fetch 层** | 漏 :182/:438（实为 refresh 非 markFailed）；ProcessStatusPoller 在 `service/streaming/` 非 `ui/controller/`；`:183` refresh 后同步读 `globalState.value` 被 deferred publish 打破；删 mutation API 后 poller 无 snapshot 可 commit（REST fetch 原在 aggregator.refresh 内） | **列全 6 写点 + 3 读点**（正确路径）；aggregator 的 `globalState/statusByKey/globalBusy` 改 **DerivedStateFlow over store.state**（`.value` 同步，复用 SharedStateStore.kt:88-90 既有模式）；新增 **StatusFetchService** 独立 fetch 层替代 aggregator.refresh 的网络职责。§2.3、§4 P0-A、§4b |
| **B5 bySid 无界增长** | v2「保留所有被通知过的 sid」作不变量是错的；SessionDeletedLocal/archive 不清 sessionStatuses（exp-1 确认）→ 孤儿永久留存 | 增 **PruneSessions op**（SessionDeletedLocal/archive 触发，清 authority.bySid 对应项）；**删除「保留所有 sid」不变量**；同组长期 stale 清理。§2.2、§4c、§4 P0-A |
| **B6 incarnation scope + 低 inc 拒绝缺失** | 缺 `incoming.incarnation < knownIncarnation → DROP`；单一全局 `knownIncarnation` 无法覆盖多 server group/slimapi instance；scope 边界未定义 | **per-scope incarnation high-water** `Map<ScopeKey,Long>`（ScopeKey=serverGroupFp+endpointFp+slimapiInstanceFp）+ **低 incarnation DROP**；定义 PurgeHost/endpoint 切换/slimapi restart/opencode restart 各自行为。§2.2、§3.1 |
| **B7 ClearOptimistic 不清 status + SessionsMerged 不存在** | ClearOptimistic 只清 claim 留 `status=BUSY,origin=OPTIMISTIC,claim=null` 幻影（投影未定义）；v2 P0-D 引用不存在的 `AppAction.SessionsMerged` | ClearOptimistic 重塑为 **ApplyReconcileOutcome(sid, outcome)**：REST 成功 idle→idle / REST busy/retry→server-confirmed busy 清 claim / REST 失败→Unknown 有界；P0-D 改用**真实 action** `SessionsRefreshedLocal`/`BulkSessionsRefreshed`。§2.2、§4 P0-D |
| **B8 optimistic bumps publication 丢失** | `CommitResult.optimisticBumps` 不在 AuthorityState；异步 collector 无法恢复；StateFlow conflation 吞中间 commit | **B1 option 1 自动解决**：bumps 持久化为 `AuthorityState.pendingBumps`，reducer 同一 `state.copy` 内应用到 sessions 并消费——无 publication、无 conflation、无丢失。§2.2、§2.3 |
| **B9 legacy busy 分类 + 等值 serverRound 无序保护** | 正常 legacy SSE busy 无 serverRound 也无 claim → 分层条件（有 serverRound 或 serverEchoed）漏 origin 无法归 ServerBusy；等值 serverRound/REST idle 后同 turn 陈旧 busy 复活 | 分层条件加 **`origin∈{SSE_LEGACY,SSE_SLIM,REST}`**；等值 serverRound 定义**单调钟 tie-break DROP**（旧帧丢）；ApplySnapshot（REST）对 serverRound 的处置（覆盖 scope 内清除/抬升）。§3.1、§2.2 |
| **B10 静态 gate 不可按当前描述实现** | Kotlin data class `val` 无 setter，写入在公开 `copy(sessionStatuses=)` 参数上，无法单参数 internal；`@VisibleForTesting`/`@Deprecated internal` 不编译期 error；AST 规则覆盖不全 | 用**真实可执行封装**：`SessionListState` 改非 data class + private 构造 + 仅 `internal fun withProjection(...)` 给 authority reducer 用；**detekt 自定义规则 + allowlist**（单一允许调用点）；穷举矩阵补 busy→idle→HTTP success / inc advance→旧 inc 迟到 / CAS retry 期间 SSE 插入。§4d |
| **B11 SSE 应 event-captured identity 非 handler currentIdentity** | v2 `host.currentIdentity()` 取**处理时**identity，旧连接事件 host 切换后迟到 → 读新 identity → scope 隔离 TOCTOU | SSE event 携带 **IdentifiedSseEvent.identity**（连接/接收时捕获）一路传入 handler/authority；reducer 用 captured identity 的 scope 作 guard（纯），`currentIdentity` 仅二次 current check。§4 P0-C、§2.2 |

> **B1 是枢纽**：option 1（AuthorityState 并入 StoreState 单一 CAS）不仅消除 v2 最明确的实施阻断（CAS-in-lambda + 分裂窗口），还**同时闭合 B8**（bumps 在 state 内同步应用，无 conflation）并**简化 B4-b**（dispatch 在 Main.immediate 同步完成，`.value` 读立即可见）与**整个 §4b 锁模型**（无 authority 独立锁，authority 逻辑就是纯 reducer）。两位评审严格要求（ogpt「可证明正确」+ opus「局部修订」）一并满足。

---

## 0. 阅读约定与术语

| 术语 | 含义 |
|---|---|
| **AuthorityState（StoreState 切片，v3）** | 本方案的**单一权威源**，作为 `StoreState.authority` 切片存在（v2 的独立 @Singleton state holder 已并入）。所有 status 写入经纯 reducer；内部执行全部护栏；投影 UI 态与生命周期态。见 §2.2。 |
| **AuthorityOp**（typed sealed） | authority 写入入口的 **typed sealed 层级**：`ApplyEvent`（增量单 sid 事件）/ `ApplySnapshot`（整图权威替换）/ `PurgeHost` / `MarkSourceFailed` / `ApplyReconcileOutcome`（v3 重塑 ClearOptimistic）/ `PruneSessions`（v3 新增，B5）。 |
| **ServerRound(incarnation, turn)** | 服务端权威**执行轮次身份**，lexicographic 比较。`incarnation`=生命周期 epoch（restart 跳变，per-scope，B6）；`turn`=per-(session,incarnation) 单调计数。仅服务端源设置。**与客户端 claim 空间分离**（M1）。 |
| **OptimisticClaim(clientSeq)** | 客户端乐观轮次的**独立计数空间**。`clientSeq` per-(client session) 单调，**从不与 server turn 比较**。`serverEchoed` 标志服务端是否已确认（解 cross-channel reorder）。 |
| **ScopeKey（v3 新增，B6）** | incarnation scope 身份 = `(serverGroupFp, endpointFp, slimapiInstanceFp)`。incarnation high-water 与 coverage 按 ScopeKey 维度（多 server group / 多 slimapi instance 不可比）。 |
| **StatusFetchService（v3 新增，B4）** | 独立的 status 网络 fetch 层（从 `StatusAggregatorImpl.refresh` 内 :269-300 抽出），做 REST/slim GET 产出 `StatusSnapshot`，dispatch `ApplySnapshot`。分离「网络获取」与「状态归约」（authority reducer）与「投影」（aggregator）。 |
| **确认门（confirmation gate）** | legacy 路径（无 turn）的**启发式**因果 fence：optimistic 未被 server echo 前，屏蔽 incoming idle；**有 liveness 超时自愈**（非无限 DROP）。诚实定位：legacy 无可靠因果 fence，强 fence 需 slimapi turn。 |
| **投影（projection）** | 从权威源派生的只读视图。UI 投影（sid-keyed `sessionStatuses`）由 reducer 同 CAS 算出；生命周期投影（aggregator → `GlobalBusyState`）由 DerivedStateFlow 派生。 |
| **I-SID-UNIQUE** | OpenCode session id（UUID）在 server group 内全局唯一；UI `sessionStatuses` 扁平 sid-keyed 安全；aggregator composite key 防御性保留。见 §4c。 |

---

## 1. 现状问题诊断（综合 main-A/B + explorer；v3 不变）

### 1.1 根因：双权威源 + 缺执行代际 fence（结构性缺陷）

ocdroid 现状有**两个并行的独立权威视图**，且**同 identity 内缺执行代际 fence**。

**双权威源拓扑**：
```
SSE session.status 帧到达
  ├─→ LegacySseHandler.kt:149  applySseStatus() → StatusAggregatorImpl.entries   【权威 A：生命周期】
  ├─→ LegacySseHandler.kt:168-170  mutateSessionList(applySessionStatus) → sessionStatuses  【权威 B：UI】
  │   ↑ 两次独立写入，无协调（THE FORK POINT）
Slim digest status
  └─→ SessionSyncCoordinator.kt:988  mutateSessionList → sessionStatuses  【仅权威 B，不喂 A】
Optimistic busy（POST 成功）
  └─→ SessionListFieldsReducer.kt:62-71  sessionStatuses += ...  【仅权威 B，无任何护栏】
REST 轮询
  ├─→ ProcessStatusPoller.kt:463  statusAggregatorInput.refresh() → entries  【权威 A；REST fetch 在 refresh 内 :269-300】
  └─→ StatusPollOrchestrator:183-218  mutateSessionList(mergeStatusSnapshot) → sessionStatuses  【权威 B】
```

**后果**：权威 A 在 slim 模式落后权威 B 数秒；权威 B 有 **10 条写入路径**，path #5（optimistic busy）完全无护栏；同 identity 内缺执行代际 fence → 反馈 #1（retry 期 stop 消失）致命根因。

### 1.2 三个用户反馈的根因映射

| 反馈 | 主根因 | 对症 |
|---|---|---|
| #1 retry/退避期 stop 消失 | 同 identity 内 stale idle 覆盖 optimistic/retry + path #5 无护栏 | §3 fence；P0-B/P0-C |
| #2 错误显示不稳定 | durable `Message.error` 恢复缺失 + 两阶段时序 + 静默丢弃 | P0-E（v3 重设计 GET 链） |
| #3 回页面看不到运行态/错误 | 冷启 reconcile 条件性 + abort 不乐观致 UI 停滞 + 双源不同步 | P0-D/P0-F + §2 双源根治 |

### 1.3 关键代码事实（v3 经 exp-1/exp-2 二次核对）

- `StoreState`（`StoreState.kt:28-44`）data class，16 字段（11 domain 切片 + meta）；**加新切片 = 加 `val authority = AuthorityState()` 字段**。`reduce`（`AppAction.kt:818-883`）**纯函数**（kdoc 明文「No effects, no settings writes, no network, no emit — ONLY returns a new StoreState」；**零** reducer 调 identityStore/repository/lock）。多切片 `state.copy()` 是常态（`reduceHostStatePurged` 写 6+ 切片）。
- `sessionStatuses`（`AppStateSlices.kt:716`）扁平 sid-keyed；写入 reducer 3 个：`reduceSessionStatusPatched`（:62-71 裸 `+`）、`reduceSessionTreeHydrated`（:100-112 epoch-guarded 原子 = 替换）、`reduceHostStatePurged`（:106-259 跨组清/同组留）。**读点 ~25 处 + `UnreadSoakController.kt:75-78` 唯一 absence-reader**。
- `SessionsRefreshedLocal`（`AppAction.kt:670-680`，reducer `SessionListFieldsReducer.kt:73-85`）/ `BulkSessionsRefreshed`（`AppAction.kt:204-211`，reducer `CrossSliceFieldsReducer.kt:261-352`）= 真实会话列表合并 action（**不存在 SessionsMerged**）；两者都**不清 sessionStatuses**；`reduceSessionsRefreshedLocal` 清 completeRootIds + bump completenessEpoch。
- `SessionArchivedLocal`（`AppAction.kt:646-650`）/ `SessionDeletedLocal`（`:656-658`）：archive 不清 sessionStatuses/sessionErrorsById；delete 清 sessionErrorsById（filterKeys）但**不清 sessionStatuses** → 孤儿永久留存（B5）。
- 聚合器 `StatusAggregatorImpl`（`StatusAggregatorImpl.kt`）：单 `commitPublishLock`（:201）；`applySseStatus`（:386-397）**硬编码 fresh=false**；`refresh`（:257-358）写 coverage（:347-358）且**REST fetch 内联在 :269-300**（`withContext(Dispatchers.IO)` + repository.getSlimapiSessionsStatus/getSessionStatus）；Busy 不过期（:556-561）。
- **aggregator 调用点全量**（exp-2）：写 6 处——`SessionStreamingController.kt:182`(refresh,bootstrap) / `:192`(markFailed,TOFU) / `:438`(refresh,handleUserClose **非 markFailed**) / `ProcessStatusPoller.kt:463`(refresh,runRefresh) / `:473`(markFailed,fallback) / `LegacySseHandler.kt:149`(applySseStatus，唯一)；ProcessStatusPoller 在 **`service/streaming/`**；读 3 处——`:151`(globalBusy.collect) / `:183`(globalState.value 同步，紧跟 :182) / `:339`(statusByKey.value, currentBusyCount)。
- `StandardApi.getMessages`（`StandardApi.kt:64-69`，`GET session/{id}/message` → `List<MessageWithParts>`）；每条 Message 有服务端 `id`（`Message.kt:32-33`）；「最后 assistant」=`messages.lastOrNull{it.isAssistant}`（`ChatFieldsReducer.kt:238`）。
- `LastAssistantErrorAttached`（`AppAction.kt:572-576`）**无 messageId**；`session.error` SSE 仅 `{sessionID,error}`（`SlimSseHandler.kt:66-75`）→ messageID 须 GET 定位（B2）。
- SSE 路径无 identity（`LegacySseHandler.kt:130-169` 用 `host.serverGroupFp()`）；`SseDispatchHost`（:27-126）不暴露 identityStore（须扩 + event-captured，B11）。

---

## 2. 新体系架构设计（单一权威源，v3：StoreState 切片 + 纯 reducer）

### 2.1 设计目标（不变）

- **单一权威源**：所有 status 写入的唯一入口（typed `AuthorityOp`），内部执行全部护栏。
- **双投影派生**：UI 投影（sid-keyed）+ 生命周期投影（aggregator → `GlobalBusyState`）从同一源派生，**根治双源不同步**。
- **执行代际 fence 内建**：`ServerRound(incarnation,turn)` 强 fence（slimapi）+ 确认门启发式 fallback（legacy，带 liveness 自愈），非 wall-clock；**计数空间分离**。
- **写入漏斗**：10 条散落写入路径收敛为 typed `AuthorityOp`。
- **读面零侵入**：~25 个读点不改。

### 2.2 核心组件：AuthorityState（StoreState 切片）+ 纯 reducer（v3 B1 option 1）

> **v3 关键决策（B1 option 1）**：AuthorityState 并入 `StoreState` 作切片，authority 逻辑是**纯 reducer**。消除 v2 的独立 authority CAS / commit-promise / collector / conflation。**单一 CAS** 同时提交 status+tree+epoch+projection。

```
┌─────────────────────────────────────────────────────────────────────┐
│  StoreState（单一 CAS，SharedStateStore.state.update{ reduce(it,action) }）│
│                                                                     │
│   sessionList: SessionListState {                                   │
│     sessions, childSessions, sessionStatuses ◄── 唯一写入者=authority│
│     activeSessionIds, completenessEpoch, ...                        │
│   }                                                                 │
│   authority: AuthorityState ◄── 【v3 新切片】                       │
│     bySid: Map<String, SessionEntry>                                │
│     knownIncarnations: Map<ScopeKey, Long>   (B6 per-scope)         │
│     coverage: Map<ScopeKey, Coverage>        (B3 完整 coverage)      │
│     pendingBumps: Map<String, Long>          (B8 持久化 bumps)       │
│   ...其他切片（chat/unread/host/...）                                │
│                                                                     │
│   reduce(state, action): StoreState   【纯；CAS retry 幂等】          │
│     when(action){                                                   │
│       is AuthorityEvent -> reduceAuthority(state, action.op)        │
│       is SessionTreeHydrated -> reduceSessionTreeHydrated(...)      │
│         // 含 epoch guard + tree delta + authority op + projection  │
│         //   全在同一 state.copy（M6 原子，B1 单 CAS）               │
│       ...                                                            │
│     }                                                                │
└─────────────────────────────────────────────────────────────────────┘
        ▲ dispatch(AppAction) on Dispatchers.Main.immediate（同步）
        │ 所有写入路径经 dispatch（typed op 或携带 op 的 action）
        │
   UI 投影：reducer 算 sessionStatuses = projection(authority)
   生命周期投影：aggregator.globalState/statusByKey = DerivedStateFlow(store.state)
                  （.value 同步，B4-b；复用 SharedStateStore.kt:88-90 模式）
```

**`AuthorityState`（StoreState 切片，v3）**：
```kotlin
data class AuthorityState(
    val bySid: Map<String, SessionEntry>,
    val knownIncarnations: Map<ScopeKey, Long>,    // B6 per-scope high-water
    val coverage: Map<ScopeKey, Coverage>,          // B3 完整 coverage
    val pendingBumps: Map<String, Long>,            // B8 持久化 optimistic bump timestamp
)
data class SessionEntry(
    val status: SessionStatus,
    val serverRound: ServerRound?,
    val optimisticClaim: OptimisticClaim?,
    val origin: EntryOrigin,          // OPTIMISTIC/SSE_LEGACY/SSE_SLIM/REST/TREE
    val freshness: Freshness,
    val updatedMonotonic: Long,
    val workdir: String?,             // B3 由 ApplySnapshot.sidToWorkdir 填/更新
)
data class ServerRound(val incarnation: Long, val turn: Long) : Comparable<ServerRound> {
    override fun compareTo(other) = compareBy(incarnation, turn)  // lex
}
data class OptimisticClaim(
    val clientSeq: Long,              // NEVER 与 server turn 比较
    val claimedAtMonotonic: Long,     // 超时自愈（非因果）
    val serverEchoed: Boolean,        // 解 cross-channel reorder
    val guardedIdleDrop: Boolean,     // watchdog 据此 reconcile
)
data class ScopeKey(val serverGroupFp: String, val endpointFp: String, val slimapiInstanceFp: String?)  // B6
data class Coverage(                                                 // B3
    val registeredWorkdirs: Set<String>,
    val coveredWorkdirs: Set<String>,
    val unmappedActiveIds: Set<String>,
    val lastSuccessTimeMs: Long,
)
```

**`AuthorityOp`（typed sealed，v3：B3 字段补全 / B5 PruneSessions / B7 ApplyReconcileOutcome）**：
```kotlin
sealed interface AuthorityOp {
    data class ApplyEvent(
        val sid: String, val status: SessionStatus, val origin: EventOrigin,
        val serverRound: ServerRound?,            // slim 带；legacy/optimistic=null
        val capturedIdentity: ConnectionIdentity?,// B11 event-captured（非 handler 时）
        val scopeKey: ScopeKey,                   // B6 由 capturedIdentity 派生
        val connectionMonotonicMs: Long,          // TTL/tie-break（非因果）
        val workdir: String?,
        val optimisticBumpTimestamp: Long? = null,
    ) : AuthorityOp

    data class ApplySnapshot(                     // B3 字段补全
        val snapshot: Map<String, SessionStatus>,
        val sidToWorkdir: Map<String, String>,    // ← B3 新增
        val authoritativeNodeIds: Set<String>,    // 全部 session IDs（非 completeRootIds）
        val registeredWorkdirs: Set<String>,      // ← B3 新增
        val coveredWorkdirs: Set<String>,
        val unmappedActiveIds: Set<String>,
        val partialFailureWorkdirs: Set<String>,
        val lastSuccessTimeMs: Long,              // ← B3 显式
        val scopeKey: ScopeKey,
        val requestToken: RequestToken,           // (host, statusLoadEpoch, requestStart)
    ) : AuthorityOp

    data class PurgeHost(val scopeKey: ScopeKey, val preserveServerGroup: Boolean) : AuthorityOp
    data class MarkSourceFailed(val scopeKey: ScopeKey, val requestToken: RequestToken) : AuthorityOp

    // B7 重塑（原 ClearOptimistic）：REST reconcile 的完整结果，非仅清 claim
    data class ApplyReconcileOutcome(
        val sid: String, val scopeKey: ScopeKey,
        val outcome: ReconcileOutcome,            // IDLE_CONFIRMED / BUSY_CONFIRMED / FETCH_FAILED
        val serverRound: ServerRound?,
        val monotonic: Long,
    ) : AuthorityOp

    data class PruneSessions(val sids: Set<String>, val scopeKey: ScopeKey) : AuthorityOp  // B5 新增
}
enum class ReconcileOutcome { IDLE_CONFIRMED, BUSY_CONFIRMED, FETCH_FAILED }
```

**纯 reducer `reduceAuthority(state, op): StoreState`（v3 核心，B1）**：
```kotlin
internal fun reduceAuthority(state: StoreState, op: AuthorityOp): StoreState {
    val cur = state.authority
    // ① 纯 guard（B1/B11：identity/scope/epoch 全在 state 上判定，无 injected 副作用）
    if (!opScopeValid(op, state)) return state                      // scope/epoch guard
    // ② 按 op 类型应用护栏 → next authority（纯计算）
    val nextAuth = when (op) {
        is ApplyEvent -> applyEvent(cur, op)                        // §3.1 fence
        is ApplySnapshot -> applySnapshot(cur, op)                  // B3 整图 + requestToken
        is PurgeHost -> applyPurge(cur, op)                         // §4c 镜像 reduceHostStatePurged
        is MarkSourceFailed -> applyMarkFailed(cur, op)
        is ApplyReconcileOutcome -> applyReconcile(cur, op)         // B7 终端态
        is PruneSessions -> applyPrune(cur, op)                     // B5
    }
    if (nextAuth === cur) return state                              // 无变化（DROP）
    // ③ projection + bumps（同一 copy，B8 同步应用无丢失）
    val projection = projectSessionStatuses(nextAuth)               // bySid.mapValues{status}
    val newSessions = applyOptimisticBumps(state.sessionList.sessions, nextAuth.pendingBumps)
    val cleanedAuth = nextAuth.copy(pendingBumps = emptyMap())      // 消费 bumps
    return state.copy(
        authority = cleanedAuth,
        sessionList = state.sessionList.copy(sessionStatuses = projection, sessions = newSessions),
    )
}
```

> **为何满足两位严格要求（B1 分歧桥接）**：
> - **ogpt「可证明正确」**：单一 CAS；reducer 纯（`reduce` 全函数零 injected 副作用，exp-2 实证）；CAS retry 重新跑纯 reducer 用同 action → 同结果（幂等）；无 authority 独立 CAS → 无「authority applied 而 store CAS 拒绝」分裂窗口；无需回滚。
> - **opus「局部修订」**：仅 P0-A 内结构决策（AuthorityState 位置 + projection 写入者），不推翻 AuthorityOp/aggregator 收编/分离计数空间方向；CAS-in-lambda 错误消除。
> - **附带闭合 B8**：bumps 在 `AuthorityState.pendingBumps`，reducer 同 `state.copy` 内 apply+消费——无 CommitResult/collector/conflation 概念，自然无丢失。
> - **附带简化 B4-b/§4b**：dispatch 在 Main.immediate 同步完成，`.value` 读立即可见（aggregator DerivedStateFlow）；无 authority 锁/forwardToAggregator/重入分析。

**identity/scope guard（B11，纯）**：guard 不在 reducer 内调 `identityStore.isCurrent`（违反纯度）。改为：
- **dispatch 站点**：调用方对 `capturedIdentity` 做快速 `identityStore.isCurrent` 预检（避免无谓 dispatch），并把 captured identity + scope + requestToken 装进 op。
- **reducer guard（纯）**：`opScopeValid(op, state)` 检查 `op.scopeKey == state.connection.currentScopeKey`（SSE/event）或 `op.requestToken.epoch == state.sessionList.statusLoadEpoch && host matches`（REST）。scope 切换后旧 op 的 scope ≠ 当前 → DROP。无 TOCTOU（B11）。

### 2.3 双投影收敛（v3：B4-b 同步读 / B8 bumps / M3 aggregator 派生）

**UI 投影**（M9 absence 保留）：
- reducer 在 authority 变更时同 CAS 算 `sessionStatuses = projection(authority)`（保留所有**在 bySid 内**的 sid；absence = 未在 bySid = 真未知 → `UnreadSoakController.kt:75-78` fail-closed 保留）。
- `sessionStatuses` 仍是存储字段，**唯一写入者 = authority reducer**（§4d 静态 gate 强制）。~25 读点零修改。
- **bumpSessionUpdated（B8）**：optimistic op 携带 `optimisticBumpTimestamp`，进 `pendingBumps`，reducer 同 copy 内 apply 到 sessions 并消费——无 publication/conflation/丢失。

**生命周期投影（aggregator 改纯响应式投影，M3 + B4-b 同步读）**：
- `StatusAggregatorInput` 的 `refresh/applySseStatus/markRequestFailed` **移出公共接口**（编译期禁用，§4d gate）。
- **aggregator 的 `globalState/statusByKey/globalBusy` 改 DerivedStateFlow over `store.state`**（复用 `SharedStateStore.kt:88-90` `DerivedStateFlow` 模式）：
  ```kotlin
  val globalState: StateFlow<GlobalBusyState> = DerivedStateFlow(store.state) { project(it.authority, ...) }
  val statusByKey: StateFlow<Map<SessionStatusKey, SessionBusyStatus>> = DerivedStateFlow(store.state) { projectByKey(it.authority) }
  ```
  - **`.value` 同步**（B4-b）：DerivedStateFlow.value = 同步 selector over AtomicReference → `:183`/`:339` 同步读立即可见（dispatch 在 Main.immediate 同步完成）。
- **aggregator 内部 `Aggregate` 不再有独立可写 entries**：`project` 从 `state.authority` 派生（composite-key + coverage from `state.authority.coverage`）。
- **GlobalBusyState 分层（修幻影 busy 钉死，M3）**：`project` 按 entry：
  - `ServerBusy`（`serverRound != null` 或 `origin∈{SSE_LEGACY,SSE_SLIM,REST}`（B9）或 `optimisticClaim.serverEchoed==true`）→ 持 `Busy`（保守不过期）。
  - `OptimisticBusy`（claim 未 echoed）→ 不钉死：`Busy` 但可由 watchdog/`ApplyReconcileOutcome(FETCH_FAILED→Unknown)` 清；**不进 45s idle-debounce 的永久 Busy**。修 v2 幻影 optimistic 经 applySseStatus(fresh=false) 永久钉死耗电。
- **freshness TTL（建议项，B）**：aggregator 原 freshnessJob 改为 dispatch `AuthorityEvent(FreshnessTick)` 的协程（无新 event 时主动 Fresh→Stale/Unknown），reducer 更新 entry.freshness。

**StatusFetchService（v3 新增，B4-c）**：从 `StatusAggregatorImpl.refresh:269-300` 抽出 REST/slim GET，产出 `StatusSnapshot`，dispatch `AuthorityEvent(ApplySnapshot(...))`。`ProcessStatusPoller.runRefresh:463` / `SessionStreamingController:182/:438` 改调 StatusFetchService（不再调 aggregator.refresh）。分离「网络获取」（StatusFetchService）与「状态归约」（authority reducer）与「投影」（aggregator）。

> **R3 真根治**：slim/optimistic/REST/tree 全经 dispatch(ApplyEvent/ApplySnapshot) → authority reducer → projection；aggregator DerivedStateFlow 派生；coverage 来自 authority.coverage；AllIdleFresh 由派生 coverage + registeredWorkdirs 产生（B3 补全后）。无旁路。

### 2.4 正交状态轴（UI 派生，不变）

| 轴 | 取值 | 来源 |
|---|---|---|
| execution.value | `Idle\|Busy\|Retry` | authority 投影（单一 UI 来源） |
| execution.serverRound | `ServerRound?` | authority（slim 强 / legacy 无） |
| execution.optimisticClaim | `OptimisticClaim?` | authority（确认门 + echo） |
| freshness | `Unknown\|Stale\|Fresh` | authority TTL 派生（建议项 freshnessJob→FreshnessTick） |
| submission | `Idle\|Posting` | `sendingSessionIds`（不延长） |
| abort.pending | Boolean（带 startedAt token） | P0-F |
| error.durable | `none\|Some(MessageError)` | `Message.error`（P0-E v3 GET 链） |

---

## 3. 护栏与不变量设计（执行代际 fence，v3：B6 scope / B9 分类补全）

### 3.1 两层 fence（v3 分离计数空间 + B6 per-scope incarnation + B9 origin/tie-break）

**Tier 1 — 强 fence（slimapi 路径）**：
- slimapi 附加 `(turnIncarnation, turn)`，per-`(serverGroupFp,sid)` 单调，**ingest 时快照**（§5）。
- `ApplyEvent(serverRound=ServerRound(inc,turn))`：**lex 严格单调**——`(inc,turn) < cur.serverRound`（lex）→ DROP。
- **incarnation advance 协议（修 M1 restart 冻结 + B6 scope）**：
  - **per-scope high-water** `knownIncarnations: Map<ScopeKey, Long>`（B6）：`inc > knownIncarnations[scope]` → 该 scope 内所有 entry 的 serverRound 重置为 null，更新 high-water。多 server group/slimapi instance 独立计数（不可比）。
  - **低 incarnation 拒绝（B6）**：`inc < knownIncarnations[scope]` → **DROP**（旧 incarnation 帧不复活）。
  - `inc == knownIncarnations[scope]` → 比 turn（lex 内）。
- cross-channel reorder（server busy 先于 HTTP success）：OPTIMISTIC commit 时检查 `cur.status∈{BUSY,RETRY} && cur.origin∈{SSE_*}` → `serverEchoed=true`（claim 立即确认），后续 idle 应用（修 v1 8s 误挡）。

**Tier 2 — 启发式 fallback（legacy SSE，无 turn）**：
- POST 成功 → `ApplyEvent(OPTIMISTIC)` → claim.clientSeq++、`serverEchoed=alreadyEchoed`。
- incoming `BUSY/RETRY` 且有 claim → echo 确认 `serverEchoed=true`，应用。
- incoming `IDLE` 且 `claim!=null && !serverEchoed` → DROP + 标 `guardedIdleDrop`；**同时 arm watchdog**：`claimedAtMonotonic + OPTIMISTIC_CONFIRM_TIMEOUT(~5s)` → `ApplyReconcileOutcome`（REST reconcile，**非无限 DROP**，B7/M5 liveness）。
- incoming `IDLE` 且 `serverEchoed==true` → legitimate terminal idle，应用 + 清 claim。
- incoming 任意 且 `claim==null` → 正常应用。

**B9 legacy busy 分类 + 等值 serverRound tie-break**：
- **legacy SSE busy 无 serverRound 也无 claim** → 分层条件加 **`origin∈{SSE_LEGACY,SSE_SLIM,REST}`** → ServerBusy（服务端源即权威）。修 v2 漏 origin 无法归 ServerBusy。
- **等值 serverRound tie-break（B9）**：同 `(inc,turn)` 内，`connectionMonotonicMs < prev.connectionMonotonicMs`（严格旧）→ DROP；`>=` → 接受（覆盖）。aggregator 改派生后原 `sourceTimeMs>=prev` 裁决由 reducer 内此 tie-break 承担。
- **ApplySnapshot（REST）对 serverRound 的处置**：REST 权威 snapshot 在其 covered scope 内**清除被覆盖 sid 的 serverRound**（REST idle 在该 epoch 是终端权威，不保留旧 slim turn），并在 `authoritativeNodeIds` 缺失 sid normalize idle；partialFailureWorkdirs 保留 prior。

**liveness 安全阀（非因果）**：watchdog + `ApplyReconcileOutcome` + REST reconcile（`ApplySnapshot`）。

**为何非 wall-clock**：因果权威是 `ServerRound(inc,turn)` + `optimisticClaim` + slimapi turn。`connectionMonotonicMs` 仅 TTL/等值 tie-break/超时，**不单独 DROP 因果**（v1 误用，v2/v3 改正）。

### 3.2 不变量（v3）

1-7. （✅ 不变，§见 v2）
8. **(🟡 P0-A v3)** 所有 status 写入经 `dispatch(AppAction{AuthorityOp})` → 纯 reducer；path #5 无护栏消除。
9. **(✅)** REST status 受 `statusLoadEpoch` + host-at-request-start 守卫（ApplySnapshot.requestToken）。
10. **(🟡 P0-B)** 同 identity 内跨轮次由 `ServerRound(inc,turn)` 强 + 确认门弱保护；**per-scope incarnation + 低 inc 拒绝 + 等值 tie-break**（B6/B9）；非 wall-clock。
11. **(🟡 P0-A v3)** UI 投影与生命周期投影从**同一权威切片**派生；**aggregator DerivedStateFlow 派生 + mutation API 移出公共接口**；R3 真根治。
12-13. （✅ 不变）
14. **(🟡 P0-D v3)** 首次连接 reconcile 不依赖 KeepCurrent，**基于已提交 tree**（SessionsRefreshedLocal/BulkSessionsRefreshed 后）；cold-start 门（成功后消费，失败受控重试）。
15. **(🟡 P0-E v3)** durable `Message.error` 经 **`getMessages` GET 定位**（B2，无 messageID 假设）；重排队列 session 级 + 服务端 message.id 关联。
16. **(🟡 P0-F)** abortPending(token) + 看门狗；REST 仍 busy 有限重试/用户恢复入口（建议项）。
17. **(🔵 I-SID-UNIQUE)** sid server group 内全局唯一；UI 扁平 sid-keyed 安全；aggregator composite key 防御性。
18. **(🔵 M6/B1 v3)** sessionStatuses+tree+epoch **单一 CAS 原子**（AuthorityState 切片 + 纯 reducer 同 `state.copy`）。
19. **(🔵 B5 v3)** bySid **有 prune**（SessionDeletedLocal/archive/同组长期 stale 清理）；**不再「保留所有 sid」**。
20. **(🔵 B6 v3)** incarnation **per-scope high-water + 低 inc 拒绝**。
21. **(🔵 B8 v3)** optimistic bumps 在 `AuthorityState.pendingBumps` 同 CAS 应用，无丢失。

---

## 4. 结构性重构改动计划（P0/P1/P2，v3）

> 验证：`./scripts/check.sh` + §4d 测试门槛（B10）。行号 explorer 快照（2026-07-30），实施前 read 复核。

### P0（结构性基础 + 用户可见正确性）

---

#### **P0-A 建立 AuthorityState 切片 + 纯 reducer + typed op + aggregator 收编 + StatusFetchService（B1/B3/B4/B5/B6/B9）**

**对症**：dual-source（R3）、path #5 无护栏（R2）、10 条散落写入、aggregator 旁路（M3）、generation 空间冲突（M1）、bulk 语义缺失（M2）、原子迁移（M6/B1）、锁序（M8/v3 简化）、**B3 coverage 字段**、**B4 调用点/fetch 层**、**B5 prune**、**B6 incarnation scope**、**B9 分类/tie-break**。整个重构基础。

**文件:行号**：
- **新建** `data/state/AuthorityState.kt`（AuthorityState + SessionEntry + ServerRound + OptimisticClaim + ScopeKey + Coverage）。
- **新建** `data/state/AuthorityOp.kt`（typed sealed + RequestToken + ReconcileOutcome）。
- **改** `ui/StoreState.kt:28-44`（增 `val authority: AuthorityState = AuthorityState()` 切片）。
- **新建** `ui/AuthorityReducer.kt`（纯 `reduceAuthority(state, op)` + 各 op 应用函数 + `projectSessionStatuses` + `applyOptimisticBumps`）。
- **改** `ui/AppAction.kt:49,818-883`（增 `AuthorityEvent(op: AuthorityOp): AppAction`；reduce `when` 加分支）。
- **改** `ui/SessionListFieldsReducer.kt:62-71, 100-112`（移除 `reduceSessionStatusPatched` 裸 `+`；`reduceSessionTreeHydrated` 改含 authority op + projection 同 copy）。
- **改** `service/status/StatusAggregatorImpl.kt`（entries/coverage 改派生自 store.state；`globalState/statusByKey/globalBusy` 改 DerivedStateFlow；移除 `refresh:269-300` REST fetch → 抽到 StatusFetchService；移除公共 mutation API）。
- **新建** `service/status/StatusFetchService.kt`（从 refresh:269-300 抽出的 REST/slim GET → StatusSnapshot → dispatch ApplySnapshot）。
- **改** `service/status/StatusModule.kt:53-74`（aggregator 注入 store.state 依赖；StatusFetchService 注入）。
- **改 aggregator 调用点**（B4 全量 6 写点）：
  - `service/streaming/SessionStreamingController.kt:182`(refresh) / `:192`(markFailed) / `:438`(refresh) → StatusFetchService.fetch + dispatch。
  - `service/streaming/ProcessStatusPoller.kt:463`(refresh) / `:473`(markFailed) → StatusFetchService + dispatch（**路径 service/streaming/ 非 ui/controller/**）。
  - `ui/controller/sse/LegacySseHandler.kt:149`(applySseStatus) → `dispatch(AuthorityEvent(ApplyEvent(SSE_LEGACY)))`，删 fork（:168-170）。
- **改 SSE/Hydrator/Poller 等写入漏斗**：SessionSyncCoordinator:988（slim → ApplyEvent(SSE_SLIM)）/ StatusPollOrchestrator:183-218（REST merge → ApplySnapshot）/ BackgroundUnreadPoller:175-222（→ ApplySnapshot，op 进 mutateState CAS）/ SessionTreeHydrator:132-150（→ ApplySnapshot，projection 进 SessionTreeHydrated payload）/ SessionListActions:251 / SessionMutationActions:376-379（optimistic → ApplyEvent(OPTIMISTIC)，P0-C）。
- **改** `ui/AppStateSlices.kt:714-716`（SessionListState 改非 data class + private 构造 + `internal fun withProjection`，B10 真实封装）。
- **改** `ui/SessionListFieldsReducer.kt:28-60`（SessionDeletedLocal/ArchivedLocal reducer 增 `applyPrune` 清 authority.bySid，B5）。
- **改** `ui/controller/sse/SseDispatchHost.kt:27-126`（B11 扩接口 + event-captured identity，见 P0-C）。

**具体改什么（B1 option 1 核心）**：
1. `StoreState` 增 `authority` 切片（data class 加字段，trivial）。
2. `AuthorityReducer.kt` 纯函数 `reduceAuthority(state, op)`（§2.2 草案）：纯 guard（scope/epoch 在 state 上）→ 按 op 应用 → projection + bumps 同 copy。**无 injected 依赖**（exp-2 实证 reducer 全纯）。
3. `AppAction.AuthorityEvent(op)` + reduce `when` 加 `is AuthorityEvent -> reduceAuthority(state, action.op)`。
4. **aggregator 改派生**：`globalState/statusByKey/globalBusy` = `DerivedStateFlow(store.state){ project(it.authority,...) }`；移除公共 mutation API（refresh/applySseStatus/markRequestFailed）；内部 Aggregate 派生自 state.authority；GlobalBusyState 分层（B9 origin + ServerBusy/OptimisticBusy）。
5. **StatusFetchService**：抽 `StatusAggregatorImpl.refresh:269-300` 的 REST/slim GET，产 StatusSnapshot，dispatch `AuthorityEvent(ApplySnapshot(...))`。
6. **6 写点改 dispatch**（B4）：每点用 StatusFetchService（fetch 类）或直接 dispatch ApplyEvent（SSE/optimistic）。
7. **ApplySnapshot 字段补全（B3）**：sidToWorkdir/registeredWorkdirs/lastSuccessTimeMs/scopeKey + authoritativeNodeIds=全部 session IDs；coverage 进 `Map<ScopeKey,Coverage>`。
8. **PruneSessions（B5）**：SessionDeletedLocal/ArchivedLocal reducer 调 `applyPrune` 清 bySid 对应项；同组长期 stale（建议项 LRU）。
9. **incarnation per-scope（B6）**：`knownIncarnations: Map<ScopeKey,Long>` + 低 inc DROP。
10. **legacy busy 分类 + tie-break（B9）**：分层加 origin；等值 serverRound 单调钟 tie-break DROP。

**为什么（含 B1）**：B1 option 1 让 authority 成为 StoreState 切片 + 纯 reducer，单一 CAS 原子（M6 自动解决）+ CAS retry 幂等（ogpt 可证明正确）+ 无分裂窗口/回滚。aggregator DerivedStateFlow 派生 + mutation API 移除（M3 真根治 + B4-b 同步读）。StatusFetchService 分离网络（B4-c）。

**风险**：
- 高（状态层核心）；缓解：双写 shadow（§4b.4，operation trace + 场景覆盖门槛，建议项）。
- aggregator 改派生需保留 GlobalBusyState/idle-debounce 语义；金钟回归 StreamingLifecycleCoordinator。
- SessionListState 改非 data class 影响所有 `.copy(` 调用点；缓解：`withProjection`/builder，机械迁移。

**验证（+ §4d）**：单测 AuthorityOp × SessionEntry 穷举；identity/scope 过期 DROP；serverRound lex stale DROP；incarnation advance 重置 + 低 inc DROP（B6）；等值 tie-break（B9）；cross-channel；双投影一致（store.sessionStatuses == authority projection == aggregator 派生 entries）；幻影 optimistic 不钉死（B7 ApplyReconcileOutcome）；原子迁移（M6 单 CAS）；prune（B5）。`./scripts/check.sh` + §4d 静态 gate。

---

#### **P0-B 执行代际 fence（ServerRound 强 + 确认门 + watchdog，M1/B6/B9）**

**对症**：R1；M1（已 v2 解决，v3 强化 B6/B9）；用户反馈 #1。**依赖 P0-A**。

**文件:行号**：`AuthorityReducer.kt`（ApplyEvent 分支：ServerRound lex + per-scope incarnation + 等值 tie-break + 确认门）；`AuthorityOp.kt`；`ui/controller/SessionSyncCoordinator.kt:978-991`（解析 `(turnIncarnation,turn)`）；`service/streaming/ProcessStatusPoller.kt`（watchdog：claim 未 echoed 且 age>5s → StatusFetchService 单 sid → ApplyReconcileOutcome）。

**具体改什么**：authority reducer ApplyEvent 分支实现 §3.1（含 B6 per-scope high-water + 低 inc DROP + B9 origin 分类 + 等值 tie-break）；slim 解析 `(inc,turn)`；legacy fallback 确认门 + watchdog→`ApplyReconcileOutcome`；REST reconcile（ApplySnapshot）自动清覆盖 sid 的 claim。

**为什么**：R1 致命根因。slim `(inc,turn)` per-scope + 低 inc 拒绝 = 最强 fence（空间分离 + restart 不冻结 + 旧帧不复活）；legacy 启发式 + watchdog 自愈。非 wall-clock。

**验证（+ §4d）**：单测 optimistic+stale idle（无 turn）→ DROP+watchdog；5s→ApplyReconcileOutcome 清；slim (inc=5,turn=3) 后到 (inc=5,turn=2)→lex DROP；inc advance（5→6）→ 旧 serverRound 清空，**旧 inc=5 帧到达→低 inc DROP（B6）**；cross-channel（server busy→HTTP success optimistic→server idle）→ serverEchoed 流转→idle 应用；等值 serverRound tie-break（B9）。`./scripts/check.sh`。

---

#### **P0-C Optimistic 走 authority + event-captured identity（B11）+ SseDispatchHost 扩接口**

**对症**：path #5 无护栏（R2）；**B11 SSE 用 handler 时 currentIdentity 有 TOCTOU**。**依赖 P0-A**。

**文件:行号**：
- `ui/controller/sse/SseDispatchHost.kt:27-126`（扩接口：暴露**连接绑定** identity；SSE event 携带 `IdentifiedSseEvent.identity`）。
- `ui/controller/sse/LegacySseHandler.kt:130-169`（用 event-captured identity 构造 ApplyEvent；`currentIdentity` 仅二次 check）。
- `ui/AppCoreOrchestration.kt:939-960`（dispatchSendMessage 捕获 identityAtDispatch）。
- `ui/SessionMutationActions.kt:312-441, 376-379`（launchSendMessage 增 identityAtDispatch；onSuccess isCurrent guard + ApplyEvent(OPTIMISTIC)）。
- 3 调用点 `AppCoreOrchestration.kt:780/825/1013`。

**具体改什么**：
1. `SseDispatchHost` 暴露**连接绑定** identity（SSE 连接建立时捕获，非处理时）；SSE event 经 `IdentifiedSseEvent(identity, payload)` 包装，identity 一路传入 handler。
2. `LegacySseHandler`：用 event.identity 构造 `ApplyEvent(capturedIdentity=event.identity, scopeKey=scopeOf(event.identity))`；`currentIdentity` 仅 `if (!identityStore.isCurrent(event.identity)) skip`（二次 check，非 guard 主源）。
3. dispatchSendMessage 捕获 identityAtDispatch，传 launchSendMessage（3 调用点）。
4. launchSendMessage onSuccess：`if (!identityStore.isCurrent(identityAtDispatch)) return`；否则 `dispatch(AuthorityEvent(ApplyEvent(sid,BUSY,OPTIMISTIC,capturedIdentity=identityAtDispatch,optimisticBumpTimestamp=now)))`。
- reducer guard 用 captured identity 的 scope（纯，B11）；identityStore.isCurrent 是 dispatch 站点快速预检 + onSuccess 第一道。

**为什么**：旧连接事件 host 切换后迟到，handler 读新 identity → scope 隔离 TOCTOU（B11）。event-captured identity 让 reducer 在 state 上纯判定 scope 匹配。

**验证（+ §4d）**：单测 host 切换后旧 SSE 帧（captured scope≠current）→ reducer DROP；onSuccess isCurrent=false→不 dispatch；cross-host 污染测试。`./scripts/check.sh`。

---

#### **P0-D 冷启 status reconcile（基于已提交 tree + cold-start 门 + 真实 action，B7/M4）**

**对症**：archive 早返回跳 status（R7）；**B7 引用不存在的 SessionsMerged**；M4 hoist 读未提交 + 成本低估。**独立可上线**。

**文件:行号**：
- `ui/controller/SessionListRefreshOrchestrator.kt:90-139, 121-131, 133-139`（archive 早返回在 :121-131，SessionsRefreshedLocal dispatch 在 :133）。
- `ui/AppCoreOrchestration.kt:1495`（archive 回调 dispatch BulkSessionsRefreshed）。
- `ui/controller/StatusPollOrchestrator.kt:310-316`（slim 空目录）。

**具体改什么（B7 真实 action + M4 已提交 tree）**：
1. **基于已提交 tree**：先 `dispatch(SessionsRefreshedLocal(...))` 提交 merge（reducer :73-85 更新 sessions + bump epoch），**再**读 `store.state.value.sessionList.allSessionsById().keys`（已提交）作 reconcile 输入：
   ```kotlin
   // onSuccess：先提交 merge，archive 早返回路径用 BulkSessionsRefreshed
   if (anyArchived && onArchivedSessionsDetected != null) {
       onArchivedSessionsDetected?.invoke(...)  // 内部 dispatch BulkSessionsRefreshed（AppCoreOrchestration:1495）
       // archive 路径的 reconcile 在 BulkSessionsRefreshed 提交后触发（见下）
   } else {
       slices.store.dispatch(AppAction.SessionsRefreshedLocal(mergedSessions, ...))
   }
   if (coldStartReconcileGate.tryConsume()) {                    // cold-start 一次性门
       val committedIds = slices.store.value.sessionList.allSessionsById().keys  // 已提交
       onLoadSessionStatusForCommitted(committedIds)             // StatusFetchService → ApplySnapshot
   }
   ```
2. **cold-start 一次性门（M4 成本）**：`coldStartReconcileGate`（AtomicBoolean），**成功后** consume（建议项：失败受控重试）；host 切换（HostStatePurged）重置。
3. archive 路径：`BulkSessionsRefreshed` 提交后同样触发 reconcile（读已提交）。
4. slim 空目录（:310-316）：`complete(true)` 不产条目（行为正确，文档化 Unknown）。

**为什么**：archive 早返回跳过 status（R7）。B7 修：用真实 action；M4 修：基于已提交 tree + 一次性门。

**验证（+ §4d）**：单测 archive 命中→BulkSessionsRefreshed 提交→reconcile 触发（基于已提交）；ClearChat/NoOp→status 触发；二次 reload→gate 已 consume 不触发；host 切换→gate 重置；gate 失败→受控重试。集成（模拟器）：冷启+服务端 busy+无 current→UI 显示运行态。`./scripts/check.sh`。

---

#### **P0-E Durable `Message.error` 恢复（session 级 pending + GET 定位，B2/M5）**

**对症**：durable error 恢复缺失（R9/R10）；**B2 上游 session.error 无 messageID** → v2 队列 messageID 不可获得。**独立可上线**（不依赖 P0-A）。

**文件:行号**：
- `ui/SessionListFieldsReducer.kt:28-45`（archive 不清 sessionErrorsById；delete :47-60 已清 sessionErrorsById）。
- `ui/ChatFieldsReducer.kt:234-250`（reduceLastAssistantErrorAttached 静默丢弃；`lastOrNull{isAssistant}` :238）。
- `data/api/StandardApi.kt:64-69`（getMessages）；`data/model/Message.kt:32-33`（message.id）。
- `ui/controller/sse/SlimSseHandler.kt:39-92, 66-75`（session.error dispatch LastAssistantErrorAttached）。
- 两阶段时序（`processor.ts:619` 设内存、`:595-596` 落盘）。

**具体改什么（B2 重设计 GET 链）**：

**(a) 归档清 sessionErrorsById（R9；修字段名 bug：按 session.id 非 archivedIds）**：
```kotlin
// SessionListFieldsReducer.kt reduceSessionArchivedLocal
sessionErrorsById = state.sessionList.sessionErrorsById.filterKeys { it != action.session.id },
```

**(b) session 级 pendingErrorCheck + GET 定位（B2，不假定 messageID）**：
- `ChatState` 增 `pendingErrorCheck: Set<String>`（**session 级，无 messageID**——B2 核心）、`pendingErrorReattach: Map<String, PendingError>`（route 回归排队，**payload 完整但 messageID 可空**）。
- **session.error 到达（SlimSseHandler:66-75）**：dispatch `LastAssistantErrorAttached(error, sessionId)` 不变；reducer 若 route 不匹配/last==null → 进 `pendingErrorCheck[sessionId]` + `pendingErrorReattach[sessionId]=PendingError(error, routeInstance, messageID=null)`。
- **drain（route 回归 / 消息加载完成）触发 GET 定位（B2）**：
  ```kotlin
  // pendingErrorCheck 的 session：调 repository.getMessages(sid) → 找 lastOrNull{isAssistant && it.error!=null}
  //   → 用服务端返回的 message.id 关联；若该 message.id == 当前 last assistant.id → 附；
  //   否则放入 sessionErrorsById[sid]（session 级错误，不附错位 assistant）。
  ```
- **防旧 error 附新 assistant（B2/M5）**：GET 返回的 error-bearing assistant 用其 message.id；仅当它仍是当前 last assistant（或仍在消息列表）才附；否则 session 级展示。
- **上限/LRU/清理（M5）**：pendingErrorCheck 每 session 1 个、pendingErrorReattach 全局上限 32 LRU；SessionDeletedLocal（:47-60 已清 sessionErrorsById，**增清 pendingErrorCheck/Reattach**）/ archive / HostStatePurged 清理。

**(c) 两阶段时序 + GET 兜底（B2）**：busy/retry→idle（轮次结束）+ 有过 busy/retry → 标 `pendingErrorCheck[sid]`；等 `message.updated`；若 last assistant 无 error 但预期有 → `getMessages(sid)` GET 兜底恢复 durable error。

**为什么**：B2 实证 session.error 无 messageID、LastAssistantErrorAttached 无 messageId、靠 lastOrNull 定位。v3 用 session 级 pending + GET 定位真实 message.id，不假定 action.messageId。

**验证（+ §4d）**：单测归档清错误（session.id）；session.error route 不匹配→pendingErrorCheck+pendingErrorReattach→route 回归→GET 定位→附（payload 完整）；GET 返回 error-bearing assistant.id≠last→sessionErrorsById；busy→idle→pendingErrorCheck→无 error→GET 兜底；清理（delete/archive/hostpurge）。`./scripts/check.sh`。

---

#### **P0-F abortPending(token) + 看门狗（不变 + 建议项）**

**对症**：abort 不乐观致 UI 停滞（R6）；sendingSessionIds 无看门狗（R5）。**独立可上线**。

（设计同 v2；v3 增建议项：watchdog REST 后仍 busy → 有限重试或用户恢复入口，不永久禁 stop。）

**文件:行号**：`ui/AppStateSlices.kt:716`、`ui/ChatViewModel.kt:409-425`、`ui/SseSessionListReducers.kt:297`、`ui/chat/Composer.kt:164-165`。

**验证（+ §4d）**：单测 abort→abortPending(token)；server idle→清除；SSE 断流→10s 看门狗→REST；REST 后仍 busy→保留 pending + 有限重试入口（不二次 abort）。`./scripts/check.sh`。

---

### P1 / P2（不变 + v3 微调）

- **P1-A**：aggregator `Aggregate` 内部缓存彻底消除（直接 DerivedStateFlow project，无中间 AtomicReference）。v3 已部分（globalState/statusByKey 派生），P1-A 收尾。
- **P1-B** retry action 模型；**P1-C** freshness（TTL→FreshnessTick action，建议项）；**P1-D** epoch 关联；**P1-E** 重排队列深化。
- **P2-A/B/C/D**：体验/硬化（P2-A 遵循 `docs/specs/ui-style-spec.md`）。

---

## 4a. 原子迁移（M6，v3：B1 单 CAS 自动解决）

> v3 因 AuthorityState 是 StoreState 切片，原子性**自动**：原子调用方把 op 携带在自己的 action 里，reducer 在同一 `state.copy` 内同时算 authority + projection + tree delta + epoch。

### 4a.1 BackgroundUnreadPoller（`BackgroundUnreadPoller.kt:175-222`）
```kotlin
store.mutateState { snapshot ->
    if (!identityValid() || snapshot.host.currentHostProfileId != startHostId ||
        snapshot.sessionList.completenessEpoch != startEpoch) return@mutateState snapshot
    // reducer 内：tree delta + authority op + projection + epoch 全在同一 state.copy（单 CAS）
    //   通过 dispatch 一个携带 op 的 action，或 mutateState lambda 内直接调纯 reduceAuthority 逻辑
    val op = AuthorityOp.ApplySnapshot(snapshot = normalizedStatuses, sidToWorkdir = ...,
        authoritativeNodeIds = allSessionsById(...).keys /* B3 修正：全部 IDs 非 completeRootIds */,
        registeredWorkdirs = ..., scopeKey = ..., requestToken = RequestToken(startHostId, startEpoch, startMonotonic), ...)
    val next = reduceAuthority(snapshot, op)   // 纯：guard 在 state 上（epoch guard 一致）
    if (next === snapshot) return@mutateState snapshot  // guard 拒绝 → 整体 no-op
    next.copy(sessionList = next.sessionList.copy(
        sessions = sessions, childSessions = children, completeRootIds = hydration.completeRootIds,
        activeSessionIds = ..., completenessEpoch = snapshot.sessionList.completenessEpoch + 1L,
    ), unread = ...)
}
```
- **无 B1 问题**：reduceAuthority 是纯函数（不在 CAS retry lambda 内做外部 mutation）；CAS retry 重跑纯 reducer 幂等；epoch guard 在 state 上，拒绝→整体 no-op（authority 与 store 一致，无分裂窗口）。

### 4a.2 reduceSessionTreeHydrated（`SessionListFieldsReducer.kt:100-112`）+ AppAction.SessionTreeHydrated（`AppAction.kt:701-706`）
- payload 增 `op: AuthorityOp`（由 hydrator 调 StatusFetchService/算 ApplySnapshot 填）；reducer epoch guard 通过 → 同 copy 应用 tree delta + op + projection（单 CAS）。原子保留。

### 4a.3 StatusPollOrchestrator REST merge（`StatusPollOrchestrator.kt:183-218, 424-434`）
- `mergeStatusSnapshot` 在途保护逻辑迁入 `reduceAuthority` 的 ApplySnapshot 分支（localBefore/localAfter diff 在 reducer 内重算，纯）；调用方在 REST 前从 `store.state.value.authority` 捕获 localBefore。

---

## 4b. 锁序与一致性模型（M8，v3 大幅简化）

> v3 因 AuthorityState 是 StoreState 切片 + 纯 reducer，**无 authority 独立锁、无 collector、无 forwardToAggregator、无重入分析**。

### 4b.1 线程与锁（v3）
- **唯一 CAS**：`SharedStateStore.state.update{ reduce(it, action) }`（既有 :250-252）。authority 逻辑是其内一段纯 reducer。
- **线程契约**：`dispatch` 在 `Dispatchers.Main.immediate`（既有 :182-183 契约）；reduce 同步在调用线程。SSE/REST 等非 Main 调用方经 `scope.launch(Dispatchers.Main.immediate){ dispatch(...) }` 或既有 Main 切换。
- **aggregator 无独立锁**：DerivedStateFlow over store.state（无 commitPublishLock 写路径；原 freshnessJob 改 dispatch FreshnessTick）。
- **无锁序问题**：单一 store CAS；aggregator 是其派生读者。

### 4b.2 reducer 纯变换（B1）
- `reduceAuthority` 纯（exp-2 实证 reduce 全函数零 injected 副作用）；不回调 authority（authority 就是 reducer 本身）；CAS retry 幂等。

### 4b.3 host purge 原子顺序（v3）
- `HostStatePurged` reducer（CrossSliceFieldsReducer:106-259）增 `applyPurge(state.authority, PurgeHost(scopeKey, preserveServerGroup))`，同 `state.copy` 内清/留 authority + sessionList（单 CAS，§4c 对齐）。

### 4b.4 双写迁移 shadow（P0-A 阶段）
- shadow 期：authority 切片与 legacy 并行计算；**仅 legacy 驱动 UI**，authority 仅日志比对 divergence（**operation trace + 场景覆盖**门槛，建议项修正：非「连续 N 次零差异」统计）。
- 切换后：legacy 写入移除；authority 唯一驱动。

---

## 4c. scope/absence/purge 一致性（M9 + B5，v3）

### 4c.1 I-SID-UNIQUE（不变）
exp-1 确认：OpenCode UUID server group 内全局唯一；UI 扁平 sid-keyed 安全；aggregator composite key 防御性。

### 4c.2 absence 语义 + B5 prune（v3 修正）
- **唯一 absence-reader** `UnreadSoakController.kt:75-78`：`it !in sessionStatuses` = 真未知 → fail-closed。
- **v3 projection 保留 absence**：projection = `bySid.mapValues`；bySid 含 REST ApplySnapshot 经 normalize（`authoritativeNodeIds` 全部 IDs 缺失→idle）填的 sid → loaded subtree 节点必在 sessionStatuses；absence = 真未知。
- **B5 修正**：bySid **有 prune**——SessionDeletedLocal（:47-60）/ SessionArchivedLocal（:28-45）reducer 调 `applyPrune(state.authority, PruneSessions(ids, scope))` 清对应 entry；删/归档的 sid 也从 sessions 列表移除 → 不在 subtree → 不被 absence-reader 检查（一致）。**删除「保留所有 sid」错误不变量**。同组长期 stale（建议项 LRU/size cap）。

### 4c.3 purge 语义对齐（不变 + scope）
- `reduceHostStatePurged`（:106-259）：跨组清/同组留；v3 增 `applyPurge(authority, PurgeHost(scopeKey, preserveServerGroup))` 同语义（跨组清 bySid + knownIncarnations[scope] + coverage[scope]；同组留）。

---

## 4d. 测试门槛（M10，v3：B10 真实可执行 gate）

### 4d.1 静态架构 gate（B10 真实封装，非 data class @VisibleForTesting）
- **SessionListState 改非 data class**（`AppStateSlices.kt:714-716`）：private 构造 + `internal fun withProjection(projection, bumps): SessionListState`（唯一可改 sessionStatuses 的入口，仅 authority reducer 调）。其他字段经 builder/copy-like 内部方法。**编译期**强制（非 data class 无公开 copy(sessionStatuses=)）。
- **StatusAggregatorInput 移除 mutation API**：`refresh/applySseStatus/markRequestFailed` 删除（编译期禁用旧调用）。
- **detekt 自定义规则 + allowlist**：禁 `mutateSessionList { sessionStatuses = }`、禁 `store.sessionList.copy(sessionStatuses =)`（除 withProjection allowlist 单点）；AST 规则覆盖 copy/reducer/helper/payload 变体。
- **穷举矩阵补（B10）**：busy→idle→HTTP success（cross-channel）；inc advance→无新事件→旧 inc 帧迟到（低 inc DROP）；CAS retry 期间 SSE 插入（纯 reducer 幂等）。

### 4d.2 状态转换穷举表
`AuthorityOp × SessionEntry 初始状态 → 期望 StoreState` 矩阵（含 B6 低 inc / B9 等值 tie-break / B7 ApplyReconcileOutcome 三 outcome）。

### 4d.3 场景测试
混合来源顺序 / 跨通道反序 / slimapi debounce+restart incarnation / partial workdir 失败 / host switch+in-flight REST/POST / 投影一致性序列（store.sessionStatuses==authority projection==aggregator 派生 entries）/ 原子迁移（单 CAS，epoch guard 拒绝整体 no-op）/ 幻影 optimistic 不钉死（ApplyReconcileOutcome）/ durable error GET 恢复（B2）/ prune（B5）。

---

## 5. oc-slimapi 跨项目协作契约（M7，v3 微调）

> slimapi 可改。契约（v3 修正 increment commit point 矛盾 + serverGroupFp 来源 + incarnation 持久化）。

### 5.1 为何需要
客户端确认门挡不住同 identity 内两条 server-originated status 帧代际；slimapi 观察 POST/abort 流量派生 `(turnIncarnation, turn)` 服务端侧权威因果标识。

### 5.2 契约（v3）
slimapi 转发的 `session.digest`/status 附加 `(turnIncarnation, turn)`：
```json
{ "type":"session.digest", "sessionId":"<sid>", "status":"busy",
  "turnIncarnation": 7, "turn": 3, ... }
```
- **turnIncarnation**：slimapi 生命周期 epoch（持久化跨 restart 单调，或 restart bump）；restart→inc++；客户端见 inc advance→重置该 scope serverRound（M1 不冻结）。
- **turn**：per-`(serverGroupFp, sid)` 单调；**事件 ingest 时快照**进 DigestFields（非 flush 时读当前，M7）。
- **increment commit point（v3 明确）**：slimapi **forward POST `/session/{sid}/prompt` 或 `/abort` 上游时**（进入 forward path，await 前）`turn[(serverGroupFp,sid)] += 1`。**forward 失败（连接错/非 2xx）→ 不 increment**（轮次未真正开始；v3 明确，解 v2 矛盾）。**允许 turn 空洞**（失败不回退，避免 unsafe decrement）。
- **serverGroupFp 来源（v3 明确）**：slimapi 侧按请求的 host/profile 配置计算 serverGroupFp（与 ocdroid client 一致算法），或由 ocdroid 在请求 header 透传。
- **共享 registry（M7）**：proxy 与 global_hub 共享 `(serverGroupFp, sid)→turn`；并发安全；incarnation 单独持久化（多 worker/容器重建下从持久层恢复，v3 明确）。
- **failed-request**：POST forward 失败→turn 不增（client 收错，不写 optimistic）。

### 5.3 ocdroid 消费
`SessionSyncCoordinator.handleSessionDigest`（:978-991）解析 `(inc, turn)` → `ApplyEvent(serverRound=ServerRound(inc,turn), scopeKey=...)`；authority reducer：lex stale DROP / inc advance 重置 / 低 inc DROP（B6）；缺失降级确认门 + watchdog。

### 5.4 落地协作（向后兼容，字段可选）

---

## 6. interrupt 与 retry 的真实关系（v3 不变）

**interrupt（fiber cancel）DOES NOT trigger retry continuation。**（§6.2 processor.ts pipeline 证据不变；§6.4 rev-opus 机制精确化：onInterrupt 不必然在 backoff 触发，真正 fallback 是 finalizeInterruptedAssistant。）

对 ocdroid 影响：①无需建模 interrupt 后重试；②retry 期 stop 可用（P0-B fence 修）；③abort 后 idle 先到 error 后到（两阶段，P0-E GET 链）；④单一 abort + P0-F abortPending。

---

## 7. 竞态覆盖矩阵（v3，R3 真根治标注）

| # | 描述 | 覆盖 | 根治（v3） |
|---|---|---|---|
| R1 | stale idle 覆盖 optimistic | P0-A/B | ServerRound(inc,turn) per-scope + 低 inc DROP + 确认门+watchdog；空间分离（M1/B6/B9） |
| R2 | POST vs host 切换污染 | P0-A/C | reducer scope guard（event-captured identity，B11）+ onSuccess isCurrent |
| R3 | 双源不同步 | P0-A | **真根治**：AuthorityState 切片 + 纯 reducer + aggregator DerivedStateFlow + mutation API 移除 |
| R4 | applySessionStatus 无 timing | P0-A/B | reducer 内 tie-break（B9 等值单调钟）+ ServerRound |
| R5 | sendingSessionIds 无看门狗 | P0-F | abortPending(token) 看门狗 + idle 兜底清 |
| R6 | abort 不乐观 idle，UI 停滞 | P0-F | abortPending(token) + stopping UI |
| R7 | archive 早返回跳 status | P0-D | 基于已提交 tree + cold-start 门（真实 action，B7） |
| R8 | target==null 绕聚合器 | P0-A/P2-C | authority 统一投影；ApplyEvent 带 workdir；P2-C legacy 兜底 |
| R9 | sessionErrorsById 归档不清 | P0-E(a) | 归档清（session.id） |
| R10 | reduceLastAssistantErrorAttached 静默丢 | P0-E(b) | session 级 pending + GET 定位（B2） |
| R11 | 两 epoch 无关联 | P1-D | statusLoadEpoch ↔ completenessEpoch |

**11/11 覆盖。R3 因 aggregator DerivedStateFlow 派生 + mutation API 移除而结构性真根治。**

---

## 8. 验收场景（v3，含 B1-B11 新增）

1-12. （v2 场景保留：双投影一致 / 确认门 DROP+watchdog / slim lex DROP / **inc advance 不冻结 + 旧 inc DROP（B6）** / optimistic→echo→idle / **cross-channel serverEchoed（B9/M1）** / host 切换不污染 / 冷启运行态 / archive reconcile / cold-start gate / slim 空目录）
13. **(🔵 B3)** ApplySnapshot 含 registeredWorkdirs + 全部 session IDs → AllIdleFresh 可算 + absence 正确。
14. **(🔵 B5)** archive/delete → bySid prune → 无孤儿；projection 不泄漏。
15. **(🔵 B7)** watchdog 触发 → ApplyReconcileOutcome(IDLE/BUSY/FAILED) → 终端态正确（非仅清 claim 留幻影）。
16. **(🔵 B8)** optimistic bump 经 pendingBumps 同 CAS 应用 → session.time.updated 不丢。
17. **(🔵 B9)** legacy SSE busy（无 round 无 claim）→ ServerBusy（origin 分类）；等值 serverRound 旧帧 → tie-break DROP。
18. **(🔵 B10)** 静态 gate：业务代码无法 `sessionStatuses=`（非 data class + detekt allowlist）。
19. **(🔵 B11)** host 切换后旧 SSE 帧（event-captured scope≠current）→ reducer DROP（无 TOCTOU）。
20. **(🔵 B4)** aggregator 6 写点全 dispatch；`:183` 同步读 globalState.value 在 dispatch 后立即可见（DerivedStateFlow）。
21. **(🔵 B2)** session.error（无 messageID）→ pendingErrorCheck → getMessages GET → 定位 error-bearing assistant.id → 附；错位→sessionErrorsById。
22-31. （v2 保留：retry 窗口 stop / retry abort retry→idle / slim digest 投影不落后 / **幻影 optimistic 不钉死 SSE（B7/M3）** / host switch epoch DROP / projection 保留 absence（B5 后仍 fail-closed）/ 同组 purge 保留 / **原子迁移单 CAS（B1）** / retry action UI / stale-busy→Unknown（术语统一）/ 服务端重启 reconcile / compaction）

---

## 9. 落地顺序（v3，B1 后简化）

| 阶段 | 改动项 | 依赖 | 风险 | 收益 |
|---|---|---|---|---|
| 1 | P0-E(a) 归档清 sessionErrorsById（修字段 bug） | 无 | 极低 | 错误横幅不滞留 |
| 2 | P0-D 冷启 reconcile（已提交 tree + cold-start 门 + 真实 action，B7/M4） | 无 | 低 | 反馈 #3 |
| 3 | P0-F（独立部分）abortPending(token) | 无 | 低 | 反馈 #1/#3 stop |
| 4 | **P0-A（B1 option 1）AuthorityState 切片 + 纯 reducer + typed op + aggregator DerivedStateFlow + StatusFetchService（B3/B4/B5/B6/B9）；双写 shadow** | 无 | 高（核心，但 v3 比 v2 简单：单 CAS 无独立锁） | 单一权威源基础 |
| 5 | P0-C optimistic + event-captured identity（B11） | P0-A | 低-中 | host 切换不污染 |
| 6 | P0-B generation fence（per-scope incarnation + 低 inc DROP + tie-break + watchdog→ApplyReconcileOutcome） | P0-A | 中 | 反馈 #1 + B6/B9 |
| 7 | P0-E(b)(c) session 级 pending + GET 定位（B2） | P0-A/P0-E(a) | 中 | 反馈 #2（B2） |
| 8 | P0-F（依赖部分）abortPending 看门狗 | P0-A | 低-中 | 反馈 #1/#3 |
| 9 | P0-A 切断旧路径（shadow operation-trace 验证后切换） | 4-8 | 中 | R3/R4 真根治 |
| 10 | §5 slimapi 协作（M7 ingest 快照 + incarnation） | P0-B | 低（可选） | 强 fence |
| 11 | P1 aggregator Aggregate 消除 / freshness FreshnessTick / retry action / epoch 关联 | P0-A | 低-中 | 深化 |
| 12 | P2 体验/硬化 | P1 | 低 | 体验 |

> 每阶段 `./scripts/check.sh` + §4d 测试门槛。UI（P2-A）遵循 `docs/specs/ui-style-spec.md`。

---

## 10. 被否决的方案（v3 + B1 否决）

| 否决项 | 理由 | 证据 |
|---|---|---|
| 保留双源 + 补丁同步 | 用户要结构性重构；补丁留竞态窗口 | omni #3 |
| 延长 sendingSessionIds | POST 在途短桥接；延长引入新竞态 | main-B §2.2 |
| wall-clock 因果 | 客户端时钟不可因果序；用 ServerRound+确认门 | rev-ogpt #8 |
| POST 失败回滚乐观 | 失败未写 busy，无回滚对象 | main-B §2.1 |
| abort 写乐观 idle | 与 server idle race | P0-F |
| 照搬 V2/SolidJS/session.idle | 框架/版本不匹配 | main-A §6.3 |
| 依赖 opencode 服务端改动 | 上游不可改 | omni #1/#2 |
| 强求 slimapi 同步上线 | 字段可选，缺失降级 | §5.4 |
| v1 单一 submit 混用空间 | M1/M2/M3 | 双评审 |
| **v2 authority 独立 CAS + commit-promise + collector（B1 否决）** | CAS-in-lambda 重复执行 + 分裂窗口 + conflation 吞 bumps；v3 改 AuthorityState 切片 + 纯 reducer 单 CAS | 重评 B1（ogpt option 1） |
| **v2 假定 action.messageId（B2 否决）** | 上游 session.error 无 messageID；v3 改 session 级 pending + GET 定位 | 重评 B2 |
| **v2「保留所有 sid」不变量（B5 否决）** | 孤儿永久留存；v3 增 PruneSessions | 重评 B5 |

---

## 11. 引用索引

### 11.1 输入材料
- 上游调研 `docs/research/2026-07-30-opencode-web-state-machine-survey.md`（main-A）
- 现状调研 `docs/research/2026-07-30-ocdroid-state-machine-survey.md`（main-B）
- v1 评审报告（M1-M10）+ **v2 重评报告（B1-B11）**：`.omni-orch/reports/ses_050154d13ffeChVTyCWthc2ll8.md`

### 11.2 上游 v1.18.7 关键证据
| 主题 | 文件 | 行 |
|---|---|---|
| SessionStatus 三态 | `packages/schema/src/session-status-event.ts` | 9-32 |
| 稀疏 Map（idle=delete） | `packages/opencode/src/session/status.ts` | 26-48 |
| processor pipeline | `packages/opencode/src/session/processor.ts` | 539-597, 599-625, 627-683 |
| retry policy/action | `packages/opencode/src/session/retry.ts` | 14-24, 35-66, 176-199 |
| halt 两阶段持久化 | `processor.ts`+`session.ts` | 619 / 595-596 / session.ts:631-635 |
| **session.error 无 messageID（B2 实证）** | `processor.ts` | 619-623 |
| HTTP status/abort | `.../handlers/session.ts` | 77-79, 232-235 |

### 11.3 ocdroid 结构性代码点（v3 经 exp-1/exp-2 二次核对）
| 主题 | 文件 | 行 | v3 核对结论 |
|---|---|---|---|
| StoreState 切片 | `ui/StoreState.kt` | 28-44 | data class 16 字段；加 authority 切片=加字段 |
| reduce 纯函数 | `ui/AppAction.kt` | 818-883, 804-817 | exp-2：kdoc 明文纯；零 reducer 调 injected 依赖 |
| 多切片原子 copy | `ui/CrossSliceFieldsReducer.kt` | 19-42, 44-104, 106-258, 261-351 | exp-2：reduceDraftSessionMaterialized 4 切片 / reduceHostStatePurged 6+ 切片 |
| AppAction sealed | `ui/AppAction.kt` | 49, 63-66, 83-84, 204-211, 572-576, 600, 646-650, 656-658, 670-680, 701-706 | exp-1：真实 action SessionsRefreshedLocal/BulkSessionsRefreshed（无 SessionsMerged）；LastAssistantErrorAttached 无 messageId |
| sessionStatuses 写入 reducer | `ui/SessionListFieldsReducer.kt` | 28-45, 47-60, 62-71, 73-85, 100-112 | exp-1：archive/delete 不清 sessionStatuses（孤儿）；reduceSessionsRefreshedLocal 清 completeRootIds+bump epoch |
| SessionListState | `ui/AppStateSlices.kt` | 714-716 | B10：改非 data class + private + withProjection |
| SharedStateStore CAS/线程 | `ui/SharedStateStore.kt` | 54-56, 88-90, 162-163, 172-185, 182-183, 250-252 | exp-2：dispatch CAS；DerivedStateFlow 模式 :88-90（aggregator 复用） |
| StatusAggregatorImpl | `service/status/StatusAggregatorImpl.kt` | 96-102, 170-181, 201, 207-209, 230-231, 257-358, 269-300, 386-397, 404-443, 479-486, 556-561, 615-657 | exp-2：单 commitPublishLock；REST fetch 内联 :269-300（抽 StatusFetchService）；applySseStatus fresh=false；coverage 只 refresh 写；Busy 不过期 |
| aggregator 调用点（B4 全量） | `service/streaming/SessionStreamingController.kt` `service/streaming/ProcessStatusPoller.kt` `ui/controller/sse/LegacySseHandler.kt` | :151,:182,:183,:192,:339,:438 / :463,:473 / :149 | exp-2：6 写点（:438 refresh 非 markFailed）+3 读点；ProcessStatusPoller 在 service/streaming/ |
| messages GET + message.id（B2） | `data/api/StandardApi.kt` `data/model/Message.kt` | 64-69 / 32-33, 99 | exp-1：getMessages→List<MessageWithParts>；message.id 服务端分配；isAssistant :99 |
| 最后 assistant 定位 | `ui/ChatFieldsReducer.kt` | 238 | lastOrNull{isAssistant} |
| SlimSseHandler session.error | `ui/controller/sse/SlimSseHandler.kt` | 34, 39-92, 66-75 | exp-2：dispatch LastAssistantErrorAttached（无 messageId），不触 aggregator |
| SSE 无 identity（B11） | `ui/controller/sse/LegacySseHandler.kt` `ui/controller/sse/SseDispatchHost.kt` | 130-169 / 27-126 | exp-2：用 host.serverGroupFp()；SseDispatchHost 不暴露 identity（须 event-captured 扩） |
| SessionMutationActions optimistic | `ui/SessionMutationActions.kt` `ui/AppCoreOrchestration.kt` | 312-441, 376-379, 418-432 / 939-960, 780, 825, 1013, 1495 | exp-2：onSuccess 无 guard；dispatchSendMessage 有 identityStore(AppCore:160)；archive 回调 :1495 dispatch BulkSessionsRefreshed |
| allSessionsById（B3 修正） | `ui/controller/SessionTree.kt` | 9-17, 53-63 | exp-1：flatten root+directory+child；normalizeAuthoritativeStatusSnapshot 填 explicit-idle |
| 原子提交点（M6） | `ui/controller/BackgroundUnreadPoller.kt` `ui/controller/StatusPollOrchestrator.kt` `ui/controller/SessionListRefreshOrchestrator.kt` | 175-222 / 183-218, 310-316 / 90-139, 121-131 | exp-1：epoch-guarded 单 CAS；archive 早返回 :121-131 在 dispatch :133 前 |
| ConnectionIdentityStore | `service/identity/ConnectionIdentityStore.kt` | 86, 93, 119-129, 194-203, 211-222, 238-244 | exp-2：currentIdentity(StateFlow)/isCurrent/commitIfCurrent |

### 11.4 相关规范
- `AGENTS.md`（校验/模拟器/UI）/ `docs/specs/ui-style-spec.md`（P2-A）/ `.opencode/policies/build-signing.md`

---

*v3 结束。本方案为结构性权威重构（**AuthorityState 作 StoreState 切片 + 纯 reducer 单一 CAS + typed AuthorityOp + 分离 ServerRound/OptimisticClaim 计数空间 + aggregator DerivedStateFlow 派生 + StatusFetchService 分离网络**），已逐条解决 v2 重评 B1-B11（B1 option 1 事务模型 / B2 GET 链 / B3 coverage 字段 / B4 调用点+fetch 层 / B5 prune / B6 per-scope incarnation / B7 ApplyReconcileOutcome+真实 action / B8 bumps 在 state / B9 origin 分类+等值 tie-break / B10 真实封装 gate / B11 event-captured identity）；11 竞态逐条覆盖（§7，R3 真根治）；interrupt/retry 澄清（§6）；每个改动点附 `文件:行号 + 改法 + 风险 + 验证`（exp-1/exp-2 二次核对）。B1 option 1 让实现模型比 v2 更简单（无独立锁/collector/conflation）。下一步由编排者启动实施（评审建议无需全量重评，做轻量确认）。*
