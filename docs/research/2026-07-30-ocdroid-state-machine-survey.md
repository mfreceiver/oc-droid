# ocdroid 会话状态机现状调研报告

> **基线性质**：本文档是「ocdroid 状态机改进」的现状基线，仅记录**事实**（含已观测的缺口与竞态），不含改进提议。改进提议留给后续综合方案（main-C）。
> **日期**：2026-07-30
> **范围**：ocdroid 安卓客户端（`app/src/main/java/cn/vectory/ocdroid/`）会话状态机相关代码。
> **方法**：2 个 explorer 并行核对源码，所有论断附 `文件:行号` 证据；关键无护栏论断由 orchestrator 直接读源码二次确认。
> **bundle**：B-ocdroid-sm-20260730（与 main-A 上游调研、main-C 综合并行）。

---

## 0. 速览结论（先读这一段）

- ocdroid 的会话状态在客户端有**两个并行的权威视图**：①UI 渲染用的 `SessionListState.sessionStatuses`（`AppStateSlices.kt:817` 附近）；②驱动 `GlobalBusyState`/服务生命周期的 `StatusAggregatorImpl.entries`（`StatusAggregatorImpl.kt`）。两者**并不总是同步**：legacy SSE 同时喂两路，但 **slim digest 与乐观写只喂 sessionStatuses**。
- 写入 `sessionStatuses` 的路径有 **10 条**，其中 **9 条经过某种护栏**（identity / epoch / merge-timing），**唯一一条完全无护栏的是乐观发送成功写 `SessionStatusPatched`**（`SessionMutationActions.kt:377-379` → `SessionListFieldsReducer.kt:62-71`）。这是本次调研最关键的单点发现，详见 §3 / §7。
- 乐观繁忙只在 **POST 成功之后**写入（非「POST 前写 + 失败回滚」），POST 往返窗口期依赖 `sendingSessionIds` 短桥集合兜底；失败路径既不写乐观也不回滚（`SessionMutationActions.kt:418-432`）。
- abort 是纯服务端 `POST /session/{id}/abort`（`StandardApi.kt:78`），客户端**不写乐观 idle、不清 sendingSessionIds**（`ChatViewModel.kt:409-425`）。

---

## 1. 状态模型与 UI 派生全貌

### 1.1 状态模型 — `data/model/Session.kt`

- `SessionStatus` 是 data class，**四字段**：`type: String`、`attempt: Int? = null`、`message: String? = null`、`next: Long? = null`（`Session.kt:91-95`）。
- **当前无 `action` 字段** —— 规范描述正确（`Session.kt:91-96` 为全部定义）。
- 三个派生布尔均基于 `type` 字符串相等性：
  - `isIdle` = `type == "idle"`（`Session.kt:97`）
  - `isBusy` = `type == "busy"`（`Session.kt:98`）
  - `isRetry` = `type == "retry"`（`Session.kt:99`）
- **类型用裸字符串**（`"idle"` / `"busy"` / `"retry"`），**未**用 Kotlin enum 或 sealed class。因此类型值无编译期约束，存在拼写错误风险。
- 乐观 busy 的构造点：`SessionStatus(type = "busy")`（`SessionMutationActions.kt:366`）。

### 1.2 UI 派生 — `ui/chat/ChatScaffold.kt` 等

- `currentSessionStatus`：对 `sessionStatuses: Map<String, SessionStatus>` 做按 `sessionId` 查表，无派生逻辑（`AppStateDerived.kt:52-55`）。
- `currentSessionIsRunning`（`ChatScaffold.kt:583-584`）：
  - `curSessionStatus?.let { it.isBusy || it.isRetry } == true`
  - **或** `chromeSessionId?.let { it in composer.sendingSessionIds } == true`
  - 即「服务端 SessionStatus(busy/retry) ∪ POST 飞行集合」的并集。
- `isCurrentSessionSending`（`ChatScaffold.kt:597`）：`chromeSessionId in sendingSessionIds` —— 仅短桥集合，是 `currentSessionIsRunning` 的严格子集。
- Composer 的 `isBusy` 由 `isBusy = currentSessionIsRunning || chat.isCompacting` 传入（`ChatScaffold.kt:1200`），Composer 作普通布尔接收（`Composer.kt:114`）。
- Composer 的 `canStop` = `isBusy && !canSend`（`Composer.kt:165`）；`canSend` = `text.isNotBlank() || imageAttachments.isNotEmpty() && !questionPending`（`Composer.kt:164`）。
- `effectiveBusySessionIds`（`SessionAttention.kt:6-14`）：`activeSessionIds` ∪ `{ sessionStatuses 中 isBusy||isRetry 的 key }`，用于未读吸水（running 的 session 不计未读）。

### 1.3 `chat.isCompacting` —— 一个正交标志

- 由 `ChatViewModel.compactSession()` 置位（`ChatViewModel.kt:201`），由 `clearCompacting()` 清除（`ChatViewModel.kt:326`）。
- `LaunchedEffect`（`ChatScaffold.kt:742-748`）：当 `isCompacting && !currentSessionIsRunning` 且 `compactStartedAt` 年龄 > 3s 时自动清除。
- 会话切换时由 `ChatFieldsReducer.kt:179` 重置。
- **性质**：纯客户端标志，不在服务端任何状态类型中；混入 UI 的「busy」概念但独立于 `SessionStatus` 与 `sendingSessionIds`。

---

## 2. 发送 / 乐观 / 桥接 / abort 机制

### 2.1 发送流程 — `dispatchSendMessage` + `launchSendMessage`

**`dispatchSendMessage`（`AppCoreOrchestration.kt:939-960`）**：
- 去重：若 `sessionId` 已在 `sendingSessionIds` 则提前返回（`AppCoreOrchestration.kt:941`）。
- **POST 前置位短桥**：`writeComposer { state -> state.copy(sendingSessionIds = state.sendingSessionIds + sessionId) }`（`AppCoreOrchestration.kt:960`）。该集合仅覆盖 POST 往返窗口。
- 同时清空 `inputText` / `imageAttachments` / `fileReferences`（`AppCoreOrchestration.kt:973`）、清持久化草稿（`:963`）、dispatch `pendingAgent=null, pendingModel=null`（`:1056`）。
- 随后调用 `launchSendMessage`（`:1013`）。

**`launchSendMessage`（`SessionMutationActions.kt:312-441`）**：
- `onSuccess`（`:334-417`）写乐观 busy：
  - `val busyStatus = SessionStatus(type = "busy")`（`SessionMutationActions.kt:366`）。
  - `store.dispatch(AppAction.SessionStatusPatched(sessionId, updatedTimestamp, busyStatus))`（`SessionMutationActions.kt:377-379`）。
  - **关键**：此写发生在 **POST 成功之后**（非 POST 前）。属「成功才写乐观、失败不回滚」模式，与「POST 前预写 + 失败回滚」相反。
- `onFailure`（`:418-432`）：**不写乐观 busy、无回滚**（无 busy 写入可回滚）；仅在用户未另行输入时恢复 `inputText`。
- `onComplete`（`:439`）：`onComplete?.invoke()` 总会运行（finally 语义）；`dispatchSendMessage` 路径下用于从 `sendingSessionIds` 移除该 id。

> **POST 在途窗口的 UI 间隙**：在 POST 返回前（约 250ms–2s），乐观 busy 尚未写入 `sessionStatuses`，UI 仅靠 `sendingSessionIds` 短桥维持「running」指示。`currentSessionIsRunning` 通过 `|| chromeSessionId in sendingSessionIds`（`ChatScaffold.kt:584`）正确覆盖此窗口。

### 2.2 `sendingSessionIds` 的读取点（控制范围）

- `ChatScaffold.kt:584` —— 喂 `currentSessionIsRunning`。
- `ChatScaffold.kt:597` —— `isCurrentSessionSending`（传给 ChatMessageList）。
- `dispatchSendMessage`（`AppCoreOrchestration.kt:941`）与 `dispatchCapturedSend`（`AppCoreOrchestration.kt:720`）—— 防重发。
- `RevertConversation.kt:25` —— 发送中禁「编辑重跑」。
- `MessageCard.kt:527` —— 发送中禁编辑/重跑菜单项。
- **`abortSession` 不读不清 `sendingSessionIds`**（见 §2.3）。

### 2.3 abort — 纯服务端、无乐观

- `abortSession`（`ChatViewModel.kt:409-425`）：`sessionId` 可空，缺省回退到 `core.store.chatFlow.value.currentSessionId`（`:410`）；在 `core.appScope.launch`（`:419`）上启动（注释 :411-418 说明：故意用 appScope 而非 viewModelScope，以便 ViewModel 被清除时 HTTP 仍完成）。
- 调用链：VM `:420` → `core.repository.abortSession(sid)` → `OpenCodeRepository.kt:1572` → `mutationApi.abortSession(sessionId)` → **`StandardApi.kt:78` `POST "session/{id}/abort"`**。
- **纯服务端**：仅 `repository.abortSession(sid)` 带 `.onFailure{...}`（`ChatViewModel.kt:420-424`），**不写乐观 idle、不改 sessionStatuses、不清 sendingSessionIds**。
- **后果缺口**：若服务端确认 abort 但 SSE 未投递 `session.status{idle}`（SSE 断流），UI 将保持 `isBusy==true`，直到下次状态轮询或 SSE 重连才修正。无客户端看门狗兜底。

---

## 3. 三路 status 信号与合并路径（**重点**：护栏覆盖矩阵）

### 3.1 (a) Legacy SSE — `LegacySseHandler.handleSessionStatus`

- SSE 事件类型 `"session.status"`（`:71` 路由，`:131` 处理）。
- 解析 `parseSessionStatusEvent(event)` 得 `statusEvent{.sessionId, .status}`（`:133`）。
- **写 #1（喂聚合器）**（`:144-153`）：构造 `SessionStatusKey(host.serverGroupFp(), target.directory, statusEvent.sessionId)`，调 `aggregatorInput.applySseStatus(key, toSessionBusyStatus(), sourceTimeMs = host.sseClock())`。**仅更新 `StatusAggregatorImpl.entries`（用于 GlobalBusyState/生命周期），不写 sessionStatuses。**
- **写 #2（写 sessionStatuses）**（`:168-170`）：`host.slices.mutateSessionList { it.applySessionStatus(statusEvent.sessionId, statusEvent.status).first }` → `copy(sessionStatuses = sessionStatuses + (sessionId to status))`（`SseSessionListReducers.kt:297`）。**无条件写入，无 timing 检查。**
- identity 守卫：legacy SSE 帧在投递时先经 `SessionSyncCoordinator.handleEvent(Identified)` 的 `isCurrent` 检查（`SessionSyncCoordinator.kt:607-621`）。

### 3.2 (b) Slim SSE digest — `SessionSyncCoordinator.handleSessionDigest`

- 定义 `:971-1069`，经 `SseDispatchHost` 接口暴露（`SseDispatchHost.kt:105`）。
- `val status = event.payload.getString("status")`（`:981`）。
- **单会话**（非批量）：处理单个 `session.digest` 帧（`:978`）。
- **写 sessionStatuses**（`:987-991`）：`if (status != null) { slices.mutateSessionList { it.applySessionStatus(sid, SessionStatus(type = status)).first } }` —— 与 legacy `session.status` 同一写法。
- **不喂 `StatusAggregatorInput`** —— 这是 slim 与 legacy 的关键分歧（见 §3.4）。
- digest 还投影 `lastError` 三态到 `sessionErrorsById`（`:993-1022`）、`deleted`/`archived` 到驱逐 effect（`:1024-1036`）、content 元组到 `SkeletonReloadCoordinator`（`:1055-1068`）。

### 3.3 (c) REST 轮询 — `StatusPollOrchestrator`

- `launchLoadSessionStatus`（`:92-260`）：单飞入口，协商策略：
  - **SWEEP 短路**（`:137-143`）：`usesSlimStatusFanOut && trigger == SWEEP && sseDigestRelayEffective(slices)` → 零 REST，立即完成。`sseDigestRelayEffective`（`:85-90`）检查 `sseConnected == true && phase ∉ {SseDisabled, Disconnected}`。
  - **Slim 模式**（`:155-157`）：`usesSlimStatusFanOut` → 委托 `launchLoadSessionStatusSlim`。
  - **Legacy 模式**（`:159-259`）：`repository.getSessionStatus()`（`GET /session/status`，批量）+ `repository.getActiveSessionIds()`（`GET /api/session/active`）。
- `launchLoadSessionStatusSlim`（`:287-414`）：**每个 workdir 一次** `GET /slimapi/sessions/status?directory=X`（`:342`），`async/awaitAll` 受目录数限流并发。部分失败保留失败 workdir 条目（`:386-394`，`fix-11a`）；全失败保留上一快照（`:357-360`，`fix-10`）。
- **合并**：两路都在 `mutateSessionList` lambda 内写：
  - Legacy（`:201-203`）：`normalized = normalizeAuthoritativeStatusSnapshot(statuses, authoritativeIds)`；`nextStatuses = mergeStatusSnapshot(localBefore, sl.sessionStatuses, normalized)`。
  - Slim（`:391-395`）：`normalized + preservedFromFailure`；`nextStatuses = mergeStatusSnapshot(localBefore, current.sessionStatuses, restSnapshot)`。
  - 回写 `sl.copy(sessionStatuses = nextStatuses)`（legacy `:215`，slim `:398`）。

### 3.4 `mergeStatusSnapshot`（`StatusPollOrchestrator.kt:424-434`）

- 纯函数无副作用。从 `restSnapshot` 起步，**覆盖** `localBefore[id] != localAfter[id]` 的条目（即 REST 在途期间被 SSE/乐观改动过的 → 新值胜出）。
- **不**检查 epoch / host / 路由 / timing —— 由调用方在调用前自行门控。
- 调用方：legacy REST 轮询（`:203`）、slim REST 轮询（`:395`）、`SessionListActions.loadChildSessions`（`:251`）、`SessionTreeHydrator`（`:140`）。

### 3.5 写入路径护栏分类表（**最关键产出**）

下表分类**所有**写 `SessionListState.sessionStatuses`（UI 可见状态表）的路径：

| # | 写入路径 | 来源 | 经 ConnectionIdentity？ | 经 StatusAggregator/epoch？ | 经路由守卫 `acceptsRouteUpdate`？ | 直写/无护栏？ |
|---|---|---|---|---|---|---|
| 1 | Legacy SSE `session.status` | `LegacySseHandler.kt:169` | 是（`SSC.handleEvent(Identified)` 的 `isCurrent`，`SessionSyncCoordinator.kt:609`） | 喂聚合器（`:149-153`），但 sessionStatuses 直写不经 epoch | 否（sessionStatuses 不过路由守卫） | identity 门控的直写 |
| 2 | Slim digest `status` | `SessionSyncCoordinator.kt:988-990` | 是（同 #1 的 `isCurrent` 门） | 否（不喂聚合器） | 否 | identity 门控的直写 |
| 3 | REST 轮询 legacy | `StatusPollOrchestrator.kt:214-216` | 检 `hostAtRequestStart`（写 `:145`，查 `:191`） | 是（`statusLoadEpoch` 单飞，写 `:144`，查 `:190`）+ 调用方 epoch + `mergeStatusSnapshot` | 否 | epoch + hostId 门控 |
| 4 | REST 轮询 slim | `StatusPollOrchestrator.kt:397-399` | 检 `hostAtRequestStart`（`:366`） | 是（`statusLoadEpoch` `:365`）+ `mergeStatusSnapshot` | 否 | epoch + hostId 门控 |
| **5** | **乐观发送成功 `SessionStatusPatched`** | `SessionMutationActions.kt:377-378` → `SessionListFieldsReducer.kt:62-71` | **否** | **否** | 否 | **完全无护栏**（无 identity / 无 epoch / 无路由 / 无 timing） |
| 6 | `SessionTreeHydrated` | `SessionTreeHydrator.kt:133-151` → `SessionListFieldsReducer.kt:108` | 否 | 是（`completenessEpoch` `:117`）+ `mergeStatusSnapshot` `:140` | 否 | epoch + merge 门控 |
| 7 | `loadChildSessions`（ephemeral） | `SessionListActions.kt:256` | 否 | 是（`completenessEpoch` `:246`）+ `mergeStatusSnapshot` `:251` | 否 | epoch + merge 门控 |
| 8 | `BackgroundUnreadPoller` | `BackgroundUnreadPoller.kt:187` | 是（`identityValid()` `:176`） | 是（`completenessEpoch` `:177`） | 否 | epoch + identity 门控 |
| 9 | `HostStatePurged`（清空） | `CrossSliceFieldsReducer.kt:128` | 否（故意——跨组切换清空） | 否 | 否 | 有意清空（无护栏） |
| 10 | `StatusAggregatorImpl.refresh` | `StatusAggregatorImpl.kt:324-325` | 经 epoch（`:305`） | 聚合器更新**自身** `entries`（非 sessionStatuses） | N/A | 聚合器自有 epoch + merge-timing 守卫（但写的是**另一张表**） |

> **关键洞察（已二次核对）**：路径 #5 `SessionStatusPatched` 是**唯一**在写 `sessionStatuses` 时**完全不经过** ConnectionIdentity / statusLoadEpoch / 路由守卫 / timing 检查的路径。`reduceSessionStatusPatched`（`SessionListFieldsReducer.kt:62-71`）直接 `sessionStatuses = sessionStatuses + (action.sessionId to action.status)`，orchestrator 已直接读源码确认。

> **双源不同步**：`StatusAggregatorImpl` 维护一张**并行**视图（其内部 `entries`，经 `statusByKey` 流投影）。Legacy SSE 同时喂两路；**Slim digest 与乐观写（路径 #2、#5）只写 sessionStatuses，不喂聚合器**。故 slim 模式下 `GlobalBusyState`（驱动服务 keep-alive 生命周期）可能落后 UI 所见状态数秒，需等 `ProcessStatusPoller`（≤30s）REST 刷新补齐聚合器。

---

## 4. 既有护栏清单与覆盖边界

### 4.1 `ConnectionIdentityStore`（`service/identity/ConnectionIdentityStore.kt`）

`ConnectionIdentity` 字段：epoch + serverGroupFp + workdir + endpointFp。

| 方法 | 行号 | 守卫语义 | 防什么 |
|---|---|---|---|
| `beginReconfigure()` | `:119-129` | `synchronized(lock)` 下原子自增 `currentEpoch` 并把 identity 置空 | host 切换时防止旧收集器/旧目录再次写入；须在 `repository.configure()` 前调用 |
| `bind(...)` | `:143-149` | 无条件将 identity 设为当前 epoch | 供自有守卫的调用方（测试、`ConnectionBootstrapEngine`） |
| `bindIfCurrent(..., expectedEpoch)` | `:171-179` | epoch-CAS：仅 `currentEpoch == expectedEpoch` 才提交；在 `synchronized(lock)` 下防与 `beginReconfigure` 的 TOCTOU | host 切换后防止持久化已解析的 URL |
| `commitIfCurrent(identity, epoch, commit)` | `:194-203` | `synchronized(lock)` 下同检 epoch + identity 指针；不匹配则跳过 commit | 旧 epoch 的在途结果在 host 切换后过期 |
| `isCurrent(identity)` | `:238-244` | 检 epoch + serverGroupFp + normalizedWorkdir + endpointFp 全等当前值 | 帧级守卫：SSE 收集器在开始处捕获 identity；`SessionSyncCoordinator.handleEvent(Identified)`（`SessionSyncCoordinator.kt:607-621`）每次折叠前调用 |

### 4.2 `StatusAggregatorImpl`（`service/status/StatusAggregatorImpl.kt`）

| 守卫 | 行号 | 详情 |
|---|---|---|
| `SessionStatusKey` | — | 复合键 `(serverGroupFp, workdir, sessionId)` —— 跨 host 防 sessionId 冲突 |
| `applySseStatus` merge-timing | `:386-398` | 仅 `sourceTimeMs >= prev.sourceTimeMs` 才写；SSE 已在上游过 identity |
| `refresh` epoch 守卫 | `:259, :305` | REST 调用前捕获 `epochAtRequestStart`；响应后丢弃（`:305`） |
| `refresh` merge-timing | `:324` | 仅 `requestStartMs >= prev.sourceTimeMs` 才写 |
| `STATUS_TTL_MS` | `:673` | `30_000L`，用于 TTL 检查：stale Idle → Unknown；stale Busy 保持 Busy |
| `project()` stale-idle → Unknown | `:547-589` | stale Idle（`now - sourceTimeMs > STATUS_TTL_MS`）`:568` → Unknown |
| `GlobalBusyState` | `:110-133` | Busy / AllIdleFresh / Unknown；仅 AllIdleFresh 可进 idle 宽限期 |
| Unknown pending-active ID（D1 gate #5） | `:554` | 服务端返回但 `sessionsById` 不存在的 active → 强制 Busy |
| 已注册 workdir 覆盖（D1 gate #5） | `:583-584` | 每个已注册 workdir 必须被覆盖；缺则 Unknown |
| `freshnessJob` 被动 TTL | `:615-657` | 在首个 Idle 过期截止唤醒；`commitPublishLock` 下重算（`:646-649`） |
| slim 部分失败 | `:339-343` | 失败 workdir 标 `Unknown`（fresh=false），投影拒 AllIdleFresh |

### 4.3 `mergeStatusSnapshot`（`StatusPollOrchestrator.kt:424-434`）

- 见 §3.4。纯函数；调用方负责在调用前门控 epoch/host/路由。覆盖边界：**仅**保护「REST 在途期间的本地改动不被覆盖」，不防跨 host/跨 epoch 污染。

### 4.4 路由守卫 `acceptsRouteUpdate`（`CrossSliceFieldsReducer.kt:606-622`）

- 身份：`expectedRouteInstance`（`chatRouteInstance` 的非零值）+ `sessionId == chat.currentSessionId`。
- 策略：`expectedRouteInstance == 0L` 全收（legacy 兼容）；非零须同时匹配 `currentSessionId` 与 `chatRouteInstance`。
- **覆盖边界**：**仅**守卫 `ChatState` 的异步写（messages、streaming）。**不**守卫 `SessionListState` 写（含 `sessionStatuses`）—— 故本表所有 sessionStatuses 写路径在「路由守卫」列均为「否」。

### 4.5 两个独立 epoch

- `statusLoadEpoch`（REST 轮询用）与 `completenessEpoch`（目录树 hydrate 用）独立推进，无机制相互关联。后台轮询可能用一条「在 session 列表层面已 stale、但在自身 epoch 内新鲜」的结果更新 `sessionStatuses`。

---

## 5. 错误模型（durable vs 内存投影，静默丢弃路径）

### 5.1 Durable `Message.error`

- `data class MessageError(val name: String? = null, val data: JsonObject? = null)`，Message 第 41 行的字段（`Message.kt:87-96` 定义 MessageError）。
- 写：legacy SSE 经 `handleMessageCompleted` / `handleMessageUpdated` 的共享 handler；`SlimSseHandler.kt:69-76` dispatch `AppAction.LastAssistantErrorAttached`。
- 读：`ChatFieldsReducer.kt:239`（`reduceLastAssistantErrorAttached`）—— 附加到最后一条 assistant 消息，若已存在则跳过。UI 读 `message.error`。

### 5.2 内存投影 `sessionErrorsById`（`AppStateSlices.kt:817`）

- 形状：`Map<String, SlimSessionLastError>`，挂 `SessionListState`。
- 写：
  - `SlimSseHandler.handleSessionError`（`SlimSseHandler.kt:78-91`）：`sid != null && supportsDurableSessionErrorBanner()` 时在逐 id 条纹锁下写。
  - `SessionSyncCoordinator.handleSessionDigest`（`:1011-1013` 为 SET，`:1017-1019` 为 JsonNull CLEAR）。
- 清：
  - `HostStatePurged` 跨组（`CrossSliceFieldsReducer.kt:159`）：`sessionErrorsById = emptyMap()`。
  - `SessionDeletedLocal`（`SessionListFieldsReducer.kt:57`）：`filterKeys { it !in ids }`。
  - **`SessionArchivedLocal`（`SessionListFieldsReducer.kt:28-44`）不清 `sessionErrorsById`** —— 已归档 session 的错误会滞留，直到该 session 在列表刷新中被移除。

### 5.3 `reduceLastAssistantErrorAttached`（`ChatFieldsReducer.kt:234-250`）

- 路由守卫：`if (!state.acceptsRouteUpdate(action.expectedRouteInstance, action.sessionId)) return state`（`:235`）。
- 逻辑：取最后一条 assistant 消息；若 null 或已有 error → 静默丢弃（返回 `state`，`:240-241`）；否则附加 error 并同步内容。
- **静默丢弃路径（`:239-240`）**：最后一条 assistant 消息已附加 error，或根本无最后 assistant 消息时丢弃。带外场景：`/full` 的 `MessageRemovedConfirmed` 可能在 SSE 帧到达前已清除最后一条消息 → `last == null` → 丢弃。UI 全局 toast（`SseSideEffect.SessionError`，`:66`）仍会弹，但元素级（重试按钮、错误 chip）可能缺失。

---

## 6. 恢复入口矩阵（覆盖 / 缺口）

### 6.1 冷启动 — `SessionListRefreshOrchestrator.launchLoadSessions`

- 位置 `:43-216`。
- **KeepCurrent-only 分支**（`:187-200`）：调 `decideRefreshCurrentSession`；session 仍活跃（或暂缺）时返回 `KeepCurrent`（`:384-386`），调 `onLoadSessionStatus()`（`:196`）与 `onLoadMessages(decision.sessionId)`（`:197`）→ 触发 REST 状态轮询 + 消息加载。
- **归档早返回路径**（`:122-131`）：`anyArchived && onArchivedSessionsDetected != null` → 调 `onArchivedSessionsDetected`（如切到下一个 session）、持久化缓存、`return@onSuccess`。此路径**跳过** `onLoadSessionStatus()` 与 `onLoadMessages()` —— **归档被检测到时状态不刷新**。用户会看到陈旧状态直到下次前台切换或显式刷新。
- `decideRefreshCurrentSession`（`:365-388`）：`null` currentSession → `refreshedSessions` 空则 `ClearChat`，否则 `NoOp`。非归档清除不导出「加载状态」。

### 6.2 非首次 SSE 重连 — `SseSyncState.reconcileGap`

- `ServerConnected` 转换（`:235-309`）：`connectedOnce == true` → 总是 reconcile。
- 决策（`:256-291`）：`ClearDeltaBuffers` +（`currentSessionId != null` 时）`ReloadSession(currentSessionId, resetLimit = true)` + `LoadSessionStatus`（REST 轮询）+ `RefreshSessions`（列表重取）。
- 经 `applySseSyncDecisions`（`SessionSyncCoordinator.kt:689-718`）转为 `ControllerEffect.LoadMessages` / `.LoadSessionStatus` / `.LoadSessions`。
- **冷启动**（`connectedOnce == false`，`:244-248`）：仅置 `connectedOnce = true`，无决策 —— 交由 `ForegroundCatchUpController`。

### 6.3 前台 fallback — `ForegroundCatchUpController`

- 位置 `:62-298`。触发 `onForegroundChanged(inForeground = true)`（`:140-188`），按 `backgroundedAtMs` 分层：
  - `<15s` → 抑制（依赖实时 SSE 馈送）（`:158-160`）。
  - `15s–5min` → `CatchUpOnSseConnect`（`:162-165`）：让 `server.connected` 驱动追赶；`sseEffectivelyOff()` → `CatchUpNow`（`:167-176`）后台 REST 追赶。
  - `>5min` → `ColdStart`（`:177-186`）：`ControllerEffect.GlobalColdStartRefresh` + stale 标记。
- `sseHasConnectedOnce` 门（`:222-223`）：`onServerConnected()`（`:219-231`）仅 `sseHasConnectedOnce && !suppress` 才跑追赶。

### 6.4 Host / Profile 切换

- `HostStatePurged`（`CrossSliceFieldsReducer.kt:106`）：清 `sessionStatuses = emptyMap()`、`sessions = emptyList()`、`activeSessionIds = emptySet()`、`sessionErrorsById = emptyMap()`。
- 切换后 `ConnectionBootstrapEngine` 重建栈，触发 `launchLoadSessions`（冷启动重载）→ 经 `onLoadSessionStatus()` 触发状态重载。
- `beginReconfigure()`（`ConnectionIdentityStore.kt:119-129`）：自增 epoch、清空 identity —— 立即令所有在途 SSE 帧与目录抓取结果失效（**但乐观写路径 #5 不经此门**，见 §7.2）。
- `SseSyncState.sessionsEverColdSnapshotted` 重置（host 切换 = 进程级内存清零）。

### 6.5 Slim 空目录边界

- `launchLoadSessionStatusSlim`（`StatusPollOrchestrator.kt:287-414`）：全失败保留上一快照（`:357-360`，`fix-10`）；部分失败保留失败 workdir 条目（`:386-394`，`fix-11a`）。空目录（无 session）不产生条目，`coveredAuthoritativeIds` 不含该目录 id —— 若该目录是唯一已注册 workdir，则聚合器覆盖检查（`StatusAggregatorImpl.kt:583-584`）会判其为「未覆盖」→ Unknown。

---

## 7. 已知缺口、竞态与风险点清单（供改进方案对症）

> 本节仅记录**已观测、有源码证据**的缺口与竞态，不含提议。

### 7.1 同一 identity 内，stale idle 覆盖 optimistic busy（竞态窗口）

- **场景**：
  - A：POST 返回 → `SessionStatusPatched(busy)` 写入（无护栏，路径 #5，`SessionMutationActions.kt:377-378`）。
  - B：信道中遗留的旧 `session.status{idle}` SSE 帧到达 → `isCurrent()` 为真（同 identity）→ `applySessionStatus(idle)` 覆盖 busy（`SseSessionListReducers.kt:297`，**无 timing 检查**）。
- **结果**：乐观 busy 被 stale idle 覆盖；用户看到「未运行」尽管 session 刚开始处理。
- **为何聚合器侧无此问题**：legacy SSE 喂聚合器有 `applySseStatus` 的 `sourceTimeMs >= prev.sourceTimeMs` merge-timing（`StatusAggregatorImpl.kt:386-398`），而 **sessionStatuses 写（`:169`）无此保护**，`applySessionStatus` 仅 `+ (sid to status)`（`SseSessionListReducers.kt:297`）。Slim digest 同样无 timing（`:988-990`）。
- **影响**：UI 视图（sessionStatuses）与聚合器视图在 stale 帧下可短暂不一致；UI 偏悲观/被回退。

### 7.2 POST 成功与 host 切换竞态（无护栏写污染）

- **场景**：POST 在途期间发生 host 切换 → `launchSendMessage.onSuccess`（`SessionMutationActions.kt:334-379`）仍执行：
  - **不**检 `ConnectionIdentityStore.isCurrent`。
  - **不**检 `statusLoadEpoch`。
  - **不**检 `hostAtRequestStart`。
  - 仅在 list 中检 `session.isArchived`（`:352-358`）—— 该 list 快照可能本身在 host 切换后已 stale。
- **结果**：`SessionStatusPatched(busy)` 污染**新 host** 的 `sessionStatuses` 表；下次 REST 轮询（经 epoch 门）可能修正，但存在窗口期渲染伪 busy。
- **对照**：聚合器路径**不受影响**（legacy SSE 喂在 `SSC.handleEvent(Identified)` 内、identity stale 则丢，`SessionSyncCoordinator.kt:609`；REST 刷新 `StatusAggregatorImpl.refresh` 有 epoch 门 `:305`）。host 切换后**仅**这个无护栏的 `SessionStatusPatched` 会污染。

### 7.3 双源不同步（sessionStatuses vs StatusAggregator.entries）

- Slim digest（路径 #2）与乐观写（路径 #5）**只写 sessionStatuses，不喂聚合器**。故 slim 模式下 `GlobalBusyState` 落后 UI 所见状态数秒，依赖 `ProcessStatusPoller`（≤30s）REST 刷新补齐。在此窗口内，服务 keep-alive 生命周期决策可能基于陈旧 `GlobalBusyState`（如过早判 AllIdleFresh → 关闭 SSE）。

### 7.4 `applySessionStatus` 无条件、幂等但无 timing

- `SseSessionListReducers.kt:297`：`copy(sessionStatuses = sessionStatuses + (sid to status))`，**不**检 timing。一个重放的 legacy `session.status` 帧可覆盖较新值。唯一前置守卫是 `SSC.handleEvent(Identified)` 的 `isCurrent`（同 identity 即放行）。

### 7.5 `sendingSessionIds` 无看门狗、不被 abort 清

- abort（`ChatViewModel.kt:409-425`）不读不清 `sendingSessionIds`。若服务端未完成 POST（如 abort 请求本身失败），该 id 会无限滞留 `sendingSessionIds`。无超时/看门狗。实际靠 `onComplete`（发送 POST 本身）总会运行来兜底；**异常 abort + SSE 断流**下无硬保证。

### 7.6 abort 不写乐观 idle（UI 停滞风险）

- 见 §2.3。abort 成功但 SSE 未投 idle → UI 保持 `isBusy==true` 直到下次轮询/重连。无客户端看门狗。

### 7.7 归档早返回跳过状态刷新（冷启动缺口）

- `SessionListRefreshOrchestrator.kt:122-131`：归档被检测到时 `return@onSuccess`，**不**调 `onLoadSessionStatus()`。跨设备归档场景下用户会看到陈旧状态直到下次前台切换/显式刷新。

### 7.8 `target == null` 绕过聚合器但仍写 sessionStatuses（legacy SSE）

- `LegacySseHandler.kt:142-154`：`target = sessionsByIdNow[statusEvent.sessionId]`；若 null（列表未加载 / session 新建）→ **聚合器未收到更新**，但 `:168-170` 的 `applySessionStatus` 仍运行。故聚合器 `GlobalBusyState` 对该 session 可能误判 AllIdleFresh，导致生命周期在该 busy session 上过早关 SSE。

### 7.9 `sessionErrorsById` 归档不清（陈旧错误横幅）

- `SessionListFieldsReducer.kt:28-44`：`SessionArchivedLocal` 不清 `sessionErrorsById`。已归档 session 的陈旧错误横幅会滞留。

### 7.10 `reduceLastAssistantErrorAttached` 静默丢弃带外 error

- `ChatFieldsReducer.kt:239-240`：`last == null || last.error != null` → 静默丢弃。带外场景（如 `/full` 的 `MessageRemovedConfirmed` 已先清最后消息）下元素级 UI（重试按钮、错误 chip）可能缺失（全局 toast 仍弹）。

### 7.11 两个独立 epoch 无关联

- `statusLoadEpoch`（REST 轮询）与 `completenessEpoch`（目录树 hydrate）独立推进。后台轮询可能用「在 session 列表层面 stale、但在自身 epoch 内新鲜」的结果更新 `sessionStatuses`。

---

## 附录 A：关键文件索引

| 主题 | 文件 |
|---|---|
| 状态模型 | `data/model/Session.kt` |
| 消息错误模型 | `data/model/Message.kt` |
| abort API | `data/api/StandardApi.kt` |
| 发送编排 | `ui/AppCoreOrchestration.kt`、`ui/SessionMutationActions.kt` |
| Chat VM / abort | `ui/ChatViewModel.kt` |
| UI 派生 / Composer | `ui/chat/ChatScaffold.kt`、`ui/chat/Composer.kt`、`ui/AppStateDerived.kt` |
| 状态写入 reducer | `ui/SessionListFieldsReducer.kt`、`ui/SessionListActions.kt`、`ui/CrossSliceFieldsReducer.kt` |
| 错误 reducer | `ui/ChatFieldsReducer.kt` |
| 状态切片 | `ui/AppStateSlices.kt` |
| 三路信号 | `ui/controller/sse/LegacySseHandler.kt`、`ui/controller/SessionSyncCoordinator.kt`、`ui/controller/StatusPollOrchestrator.kt` |
| SSE 同步状态机 | `ui/controller/SseSyncState.kt` |
| 前台 fallback | `ui/controller/ForegroundCatchUpController.kt` |
| 冷启动刷新 | `ui/controller/SessionListRefreshOrchestrator.kt` |
| 聚合器 | `service/status/StatusAggregatorImpl.kt` |
| identity 护栏 | `service/identity/ConnectionIdentityStore.kt` |
| Slim SSE | `ui/controller/sse/SlimSseHandler.kt`（路径以 `SlimSseHandler.kt` 引用） |
| 目录树 hydrate | `ui/SessionTreeHydrator.kt` |
| 后台未读轮询 | `ui/BackgroundUnreadPoller.kt` |

## 附录 B：调研方法与审计

- **并行 explorer × 2**：
  - exp-1（`ses_05131afadffeg6yBMrReoh4Cp0`）：范围 §1-4（状态模型 / UI 派生 / 发送-乐观-桥接 / abort）。读 9 个文件（AppCoreOrchestration / ChatScaffold / ChatViewModel / Composer / SessionMutationActions / AppStateDerived / StandardApi / Session / +1）。
  - exp-2（`ses_051317a1fffevaRUvtmyOvswpw`）：范围 §5-9（三路信号 / 护栏 / 错误模型 / 恢复入口 / 竞态）。
- **orchestrator 二次核对**：直接读 `SessionListFieldsReducer.kt:55-84` 确认 `reduceSessionStatusPatched` 无护栏直写（路径 #5 关键论断）。
- **门禁**：报告落盘 + 7 章节齐全 + 每条论断附 `文件:行号`。本报告不走评审链（评审留给最终综合方案 main-C）。
