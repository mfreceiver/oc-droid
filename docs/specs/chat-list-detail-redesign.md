# Chat List-Detail 重构设计方案（方案 B）

> **状态**：设计基线 v3（opus 终审 + bgpt 复核收敛，核心机制**设计层定型**）
> **日期**：2026-07-24
> **定位**：chat tab 问题的设计基线 + 调研快照 + 实施硬门控。依 `docs/specs/decomposition-guidelines.md` §0，一次性执行计划本应归档 `docs/ocmar/plans/`，但该路径被 `.gitignore`（第 108-109 行「生成式审计/规划报告，不应进 git」）排除为本地工件；应项目要求需入库可追溯，故置于被跟踪的 `docs/specs/`（其核心架构决策——不变式、模块化合规、list-detail 模型、实施门控——具长期参考价值）。
> **行号约定**：本文行号均为调研时快照，实施时以最新代码为准；实施前应重新生成一次引用矩阵。

---

## 0. 文档定位与评审状态

### 0.1 评审严格度区分（v3 更新）

经四轮评审（bgpt v1=7.4、v2=7.8；rev-opus 终审有条件通过；rev-bgpt 复核有条件通过），形成分层严格度：

- **设计层（本文）**：方向 / 根因 / 模块化 / 风险 / 流程，**以及核心正确性机制选型**（§7 `LoadedContent` 值对象 + freshness token、§11 checkpoint 归属）—— **v3 已定型，不再延后**。
- **实施层（代码）**：由 §13 流程的每批次 glm + 最终 bgpt 9.5，落实**机制成立的证据**（AST 门控测试、property-based parser 测试、状态机测试、SavedStateHandle 协议测试、A→B→A stale-load 测试）。

> v2 曾把 G1（不可表达机制）整体延后到实施；opus 与 bgpt **一致指出：机制选型是架构决策（决定 blast radius 与批次计划），不可延后**。v3 将其回收到设计层定型（§7），仅保留"证明机制成立的测试"在实施层。

### 0.2 评审历史

| 轮次 | 评审 | 得分/结论 | 要点 |
|---|---|---|---|
| v1 | rev-bgpt | 7.4 block | 不变量未达不可表达；文件矩阵不全；导航入口参数链未解决；fromRouteKey 不充分；delete/archive 链未闭合；checkpoint 迁移未证明 |
| v2 | rev-bgpt | 7.8 block（作基线） | BLOCK-1 contentSessionId 仍是 currentSessionId 别名（需 generation-CAS）；BLOCK-6 checkpoint 消费顺序自相矛盾；BLOCK-2 零残留门控不足（需 AST 级）；BLOCK-5 transition 调用契约未闭合 |
| v3 | rev-opus 终审 | 有条件通过 | `LoadedContent` 值对象优于 generation-CAS 与 session-keyed Map；checkpoint 迁 per-entry `SavedStateHandle`；list-detail 是单一权威唯一终态；B0.5 薄片 + 共享文件串行。**但主张 generation-CAS 可全删（冗余计数器 #5）** |
| v3 | rev-bgpt 复核 | 有条件通过 | 认可 opus 5 条建议中 4 条；**驳回"CAS 全删"**——A→B→A stale-load race 真实存在（同 session 旧 incarnation 覆盖新内容），既有 epoch 不覆盖；需窄化 freshness token。最终：采纳 LoadedContent + 窄化 token + SavedStateHandle 协议可接近/达到 ≥9.5 |

### 0.3 opus+bgpt 收敛与唯一分歧

**共识**：`LoadedContent` 值对象替代 8 个平铺字段；否决 session-keyed Map（错 trade）；checkpoint 迁 per-entry `SavedStateHandle`；list-detail 为推荐终态；插入 B0.5 薄垂直片；共享文件批次串行。

**唯一分歧（CAS 能否全删）→ 采纳 bgpt**：opus 称 generation-CAS 是冗余的第 5 个计数器可全删；bgpt 给出具体 race（见 §7.2）证明同 session 旧 incarnation 可覆盖新内容，既有 `completenessEpoch`/`sseConnectedGeneration`/token-stream epoch 都不覆盖 REST message-load merge 窗口。**v3 结论**：保留**窄化** freshness/incarnation token（既非 opus 的全删，也非 v2 的两个全局 Long），形态范围见 §7.2。

---

## 1. 背景与根因诊断

### 1.1 现象

关闭所有 chat tab 后，仍显示某个非预期 chat 页面，屡修屡犯。

### 1.2 根因（结构层）

逻辑概念"当前应显示哪个会话界面"被**六套独立可写权威**表达，非法状态可表达、且在 `closeSession` 过程中被发射：

```
openSessionIds = []          // tab 全关（SessionListState）
currentSessionId = "x"       // 仍指向某会话（ChatState）
route = Chat                 // 仍停 Chat（NavState.lastRoute + NavController）
```

`ChatScaffold` 渲染权威是 `chat.currentSessionId != null`（`ChatScaffold.kt:862-895`），**不校验** currentSession 是否属于某 open root → tab strip（看 openSessionIds）与聊天体（看 currentSessionId）各自为政。

### 1.3 为何屡修屡犯（打地鼠的结构性原因）

1. `SessionViewModel.closeSession`（`SessionViewModel.kt:251-296`）把"关 tab"拆成 6 步**独立** store commit。store 原子性只在单 dispatch 内，用户意图层非原子 → step1 后非法聚合态已落地。
2. **三个导航参与者抢权**：closeSession 改 NavState；AppShell `LaunchedEffect(requestedRoute)`（`AppShell.kt:134-136`）翻译成 `navigateTopLevel`；ChatScaffold 还有**第二套** close 处理器（`LaunchedEffect` `L702-708` 监听 openSessionIds 变空调 onBackToHome），且带 `.drop(1)`——对"已在非法空态 compose 出来的 Chat"无能为力。三路并发编排不确定。
3. 测试历史即证据：`SessionViewModelPassThroughTest` 含大量 close-all 分支回归——每修一组合漏下一组合。典型症状：在每 mutation 点**程序化**事后维护不变式，而非让非法状态不可表达。
4. 次要放大器：`navigateTopLevel` 用 `saveState=true/restoreState=true`（bottom-nav 配方，本 app 无 bottom nav）；持久化三件套独立写盘，进程死亡留混合快照。
5. 隐式产品例外：`draftWorkdir != null` 时关最后一个 tab 故意留 Chat——是"非预期页面"主要嫌疑之一；draft 应为一等状态而非 close-all 规则的豁免。

---

## 2. 业界对照

主流会话类 app（WhatsApp / Telegram / Signal / iMessage / Discord / Slack）**不用浏览器式 tab**，而用 **list-detail（主从）**：会话列表常驻 root，聊天是 push 的 detail，系统返回退列表；"无选中"显式占位页（Signal `NoSelectedConversation`、Material 3 `ListDetailSceneStrategy.detailPlaceholder`）。

本 app 已有三套 quick-switch 入口：`SessionsScreen`（列表）+ `SessionPickerSheet` + `RecentSessionsDrawer`。**删 tab strip 不损失可达性，是做减法**。

> **opus+bgpt 共识**：list-detail 是本项目推荐终态。opus 论证 `NavController` 在 Android 不可删（deep link/通知/系统 back 必须驱动它）= 天然一个导航权威；保留 tab 就需独立于 route 的"当前选中 tab"指针 → 至少 2 套权威，到不了 1；list-detail 把会话身份并入 route → 2 套合并成 1。bgpt 采纳此工程结论（仅驳回"唯一可能"的绝对措辞）。

---

## 3. slimapi 边界

slimapi 是 `/slimapi/` 前缀的服务端 sidecar（`data/api/SlimApi.kt`）。所有 session/message/status/stream 端点**早已按 `sessionId` 寻址**。服务端无"当前 tab / current session"概念。

**本重构是纯客户端改造，L0–L3（data/）零改动**。客户端 session-id threading 的正确性由 §7 LoadedContent owner + freshness token + §14 G2 AST 门控保证。

---

## 4. 模块化合规（对照 architecture.md / decomposition-guidelines）

| 准则 | 本方案遵从 |
|---|---|
| architecture §3 分层 | 导航身份归 L5 ui/shell；数据指针归 L5 state slice；编排归 L4 controller；data L0-L3 零改动 |
| architecture §4 差异下沉 | 导航重构与传输无关，符合"共享形状专有取数" |
| decomposition §3 不加构造器 arity | SessionViewModel/SessionSwitcher 新行为用方法，不加构造器参数 |
| decomposition §5 单一归属 | 导航身份唯一=路由；删 lastRoute 第二套权威、删 ChatScaffold safety-net 第二套 close 处理器；`LoadedContent` 把内容字段收敛为单一 owner 槽 |
| decomposition §6 单向 DAG | feature→OrchestratorVM.navigateToChat→NavController，无回调环 |
| decomposition §7 结构搬动≠语义改动 | §12 批次内部有序 commit（先搬结构后改语义），各自 check.sh-green |
| decomposition §9 UI 域 | derivedStateOf 优于 LaunchedEffect+snapshotFlow；不抽新 scope-owning 控制器；ChatMessageList 入口（冻结 seam）不动 |

---

## 5. 目标不变式

```
(P1) 渲染会话内容 ⟺ 路由=chat/{id} 且 content.sessionId==id   ← LoadedContent 结构 owner
(P2) 路由=sessions ⟹ 占位页，绝不渲染任何 transcript
(P3) 冷启动 ⟹ 路由恒 sessions（无复活对象）
(P4) chat/{id} 中 id 已删/非法 ⟹ Missing 态或 pop 回列表，绝不渲染别的会话
(P5) 导航身份唯一=路由参数；currentSessionId 仅作数据指针与后台 guard，不决定渲染
(P6) 后台异步结果提交给会话 X ⟹ 校验 freshness/incarnation token              ← 时序 owner
     （同 session 旧 incarnation 不可覆盖新内容），否则丢弃
```

**(P1) 结构性不可表达** + **(P6) 时序性不可表达** —— 二者共同使"tab 全关却显示某 chat"与"切到 B 显示 A 内容"以及"A→B→A 旧 load 覆盖新内容"在结构上不可表达。设计层已定型（§7）。

---

## 6. 核心设计决策

| # | 决策 | 取代 |
|---|---|---|
| D1 | 带参路由 `chat/{sessionId}`；新建 `chat/new?workdir=…` | 参数化 `Chat("chat")`，身份藏全局可变 currentSessionId |
| D2 | `parseRoute` 未知/空 → 回落 `Sessions` | 回落 `Chat` |
| D3 | 渲染权威 = 路由派生 sealed `ChatDetailState{None; Loading(id); Content(id); NewConversation(workdir); Missing(id)}`（derivedStateOf） | currentSessionId!=null |
| D4 | draft/新会话 = 显式路由 `chat/new` | currentSessionId==null && draftWorkdir!=null 隐式例外 |
| D5 | 导航身份唯一在路由；currentSessionId 降级为数据指针，由路由 effect 设置 | 双重职责 |
| D6 | 删 ChatScaffold 空-tab safety-net LaunchedEffect（L702-708） | 双 close 处理器 |
| D7 | Chat 不走 saveState/restoreState | bottom-nav 配方误用 |
| D8 | 移除 NavState.lastRoute 第二套 NavController；改直接 navigate 回调 | 镜像同步 |
| D9 | openSessionIds 整体移除（tab strip/pager 删） | SessionListState.openSessionIds + 持久化 + auto-select 全链 |
| D10 | SelectConversation/CloseDetail 单原子跨切片 action | closeSession 拆 6 步独立 commit |
| **D11** | 子 agent 复用 `chat/{childId}`；**parentReturnCheckpoints 迁 per-entry `SavedStateHandle`**（§11），不再用全局 ChatState map | v2 保留全局 map（理由被 D9 自我推翻：删 pager 后无 swipe-between-roots 场景） |
| **D12** | 内容收敛为 `LoadedContent(sessionId, …)?` 值对象 + 窄化 freshness token（§7） | 8 个平铺内容字段 + v2 的两个全局 Long |

---

## 7. 核心机制（设计层定型）：`LoadedContent` 值对象 + freshness token

全量矩阵（§9）证实 currentSessionId 被 ~15 处后台 guard 使用（`MessageActions.kt:476,129,425,455,544,615,634`、`SessionSyncCoordinator.kt:1333,1399,1823-1884,2338`、`AppCore.kt:600-611`、`AppCoreOrchestration.kt:818-820`），语义都是"这个异步结果是否属于当前活跃会话"——保留作 expected-id guard（D5）。

### 7.1 `LoadedContent` 值对象（结构性 owner —— opus 提，bgpt 认可）

把当前平铺在 `ChatState`（`AppStateSlices.kt:436-593`）的 ~8 个内容字段 collapse 成**一个带 owner id 的 nullable 值对象、单一槽**：

```kotlin
data class LoadedContent(
    val sessionId: String,              // 内容归属，与 messages 焊死
    val messages: List<Message>,
    val partsByMessage: Map<String, List<Part>>,
    val streamingPartTexts: Map<String, String>,
    val streamOwned: Map<String, Owner>,
    val streamingReasoningPart: ReasoningPart?,
    val olderMessagesCursor: String?,
    val hasMoreMessages: Boolean,
    val currentModel: String?,
)
// ChatState: val content: LoadedContent? = null   // 取代 8 个平铺字段
```

- **渲染权威**：`detail(routeId, chat) = chat.content?.takeIf { it.sessionId == routeId }?.let { Content(it) } ?: Loading(routeId)`。删 `currentSessionId!=null` 渲染权威（`ChatScaffold.kt:862-895`）；删 safety-net（`L702-708`）。
- **构造即原子**：`(sessionId, messages)` 焊在一起，**不可能** messages 无归属，**不可能**更新 messages 却不重盖 owner id。焊死了"reducer 忘记同步 current 与 messages"这一 bug 类（今天 8 个独立字段可各自撕裂）。
- **单一 AST 可审计 seam**：跨会话 bleed 需在唯一渲染 call site 读 `content.sessionId` 却比对 `routeId`——满足 §14 G2 AST 门控。
- 无 map、无 eviction、无内存增长（单槽，与今天一致）；迁移机械（reducer `copy(messages=…)` → `copy(content=content.copy(…))`）。
- `currentSessionId` 保留作数据指针（D5），由路由 effect 设置；后台 guard 用之。

### 7.2 freshness / incarnation token（时序性 acceptance —— bgpt 补 opus 漏洞）

`LoadedContent` 解决**结构性**撕裂，但**不解决时序性**：同 session 的旧 load incarnation 可覆盖新内容。

**race（bgpt 给出，opus 曾误判为良性）**：
```
t0: route=A，启动 load(A, req-1)
t1: A→B，currentSessionId=B
t2: B→A，currentSessionId=A，启动 load(A, req-2)
t3: req-2 返回，提交较新的 A 内容（含用户刚发消息 + SSE 更新）
t4: req-1（旧快照）返回，currentSessionId 仍是 A → 既有 sessionId guard 通过
t5: req-1 覆盖 req-2 的较新内容
```

既有机制**不覆盖**此窗口：`completenessEpoch`（守会话树结构，A→B→A 不变树）、`sseConnectedGeneration`（守 SSE 传输生命周期）、token-stream `epoch`+`gen`（守 stream 事件）——都不约束普通 REST message-load merge。`sessionId==currentSessionId` guard 只答"现在是不是 A"，答不了"是不是当前这次 A incarnation 的结果"。

**窄化 token（三选一，实施时定，但形态范围设计层钉死；既非 opus 全删，也非 v2 两个全局 Long）**：
1. **route-instance token**（推荐）：每次 `chat/{id}` 导航生成不可复用 token；内容提交带 `expectedRouteInstance`，CAS 校验；
2. **per-load request token**：每次 load 携 request id，提交时 CAS；
3. **内容层单调 content-revision**：若能证明严格单调 merge（旧 load 不可能覆盖更新字段），可用 revision 取代 token。

**关键约束**：必须有某种比 sessionId 更细的 freshness 判别式。token 由 reducer 内部维护，不必暴露 UI。可复用项目既有 `sseConnectedGeneration` 单调 CAS 模式（`SharedStateStore.kt:187-201`，architecture §5 先例）。

### 7.3 currentSessionId（D5 不变）

数据指针 + 后台 guard。~15 处既有 guard 保留（§9.2）；新增 freshness token 与之并行（sessionId 判跨会话错写，token 判同会话旧 incarnation）。

---

## 8. 导航 API 与 route parser

### 8.1 参数化 route parser（sealed AppRoute）

```
sealed AppRoute {
  Sessions; Files(workdir,path); Git(session,workdir); Settings; SettingsHosts
  ChatDetail(sessionId)          // chat/{sessionId}
  NewConversation(workdir)       // chat/new?workdir=…
  ChatPreview(workdir,path)      // chat/preview?workdir=&path=  (既有)
}
parseRoute(raw: String?): AppRoute   // 替代 fromRouteKey
  - raw==null/空/未知顶层 → Sessions      ← 改：原回落 Chat (NavRoute.kt:68)
  - "chat/{id}" → ChatDetail(id)
  - "chat/new" → NewConversation
  - "chat/preview" → ChatPreview
  - 旧持久化裸 "chat" → Sessions（fail-safe，见 §10 cold-start）
```

### 8.2 显式导航 API（替代 setLastRoute(Chat)）

```
OrchestratorVM.navigateToChat(sessionId)          // navController.navigate("chat/$id")（不 saveState/restoreState）
OrchestratorVM.navigateToNewConversation(workdir)
OrchestratorVM.popToSessions() / backToHome()
```

删 `LaunchedEffect(requestedRoute)` 镜像同步（`AppShell.kt:134-136`）、删 `NavState.lastRoute` 第二套导航权威。

### 8.3 全入口改造清单

| 入口 | 现状 | 改造 |
|---|---|---|
| `AppShell.kt:214` SessionsScreen onSwitchToChat | `setLastRoute(Chat)` | `navigateToChat(id)`；签名 `(String)->Unit` |
| `AppShell.kt:246` FilesScreen onSwitchToChat | 同上 | 同上 |
| `MainActivity.kt:176` 深链/通知 | `setLastRoute(Chat)` | `navigateToChat(id)`（通知 intent 必带 sessionId；无 id→Sessions） |
| `SessionsScreen.kt:265,277,541,547` | `selectSession(id)+onSwitchToChat()` | `selectSession(id)+onSwitchToChat(id)` |
| `SessionPickerSheet`/`RecentSessionsDrawer` onSelect | `selectSession(id)` | `selectSession(id)+navigateToChat(id)` |
| sub-agent openSubAgent | `selectSession(childId)` | `navigateToChat(childId)`（§11 顺序） |
| returnToParent | `switchTo(parent,Restore)` | `navigateToChat(parentId)` + checkpoint（§11） |

> **实施门控（§14 G3/G4）**：warm-start/cold-start 导航事件暂存/消费契约（VM 未创建、NavHost 未 attached 时）；route parser property-based 测试（URL 编码、`%2F`、`?`、`#`、空白、重复 query、`ses_` grammar、NavController 解码时序、`chat/new` vs `chat/{id}` 歧义）；通知指向不存在/已删会话 → Missing/Sessions。

---

## 9. 全量引用矩阵（摘要）与零残留门控

rg 穷尽（src/main+test）：openSessionIds ~45 命中、currentSessionId ~80+、lastRoute/nav ~40、delete/archive/refresh ~30。

### 9.1 openSessionIds 写点（整体移除）

`SessionSwitcher.switchTo Step8`（`SessionSwitcher.kt:564-568`）、`SessionViewModel.closeSession`（`:251-255`）、`AppCoreOrchestration.handleBulkArchiveRefresh`（`:951-973`）、`materializeDraftSession`（`:316-331`）、`SessionSyncCoordinator.handleSessionDigest`（`:1357-1374`）+ resync（`:1920-1938`）、`HostProfileController.purgePerHostState`（`:491-493`）、`launchDeleteSession`（`SessionMutationActions.kt:303-305`）、reducer `reduceOpenSessionIdsChanged`（`SessionListFieldsReducer.kt:30-34`）/`reduceDraftSessionMaterialized`/`reduceSessionArchived`/`reduceBulkSessionsRefreshed`（CrossSliceFieldsReducer）、持久化 `SessionPrefs.openSessionIds`。

### 9.2 currentSessionId guard 点（保留作 expected-id guard）

`MessageActions.kt:476,129,425,455,544,615,634`、`SessionSyncCoordinator.kt:1333,1399,1823-1884,2338`、`AppCore.kt:600-611`、`AppCoreOrchestration.kt:818-820`（token-stream）、`AppCoreOrchestration.kt:284`（深链 no-op）。

### 9.3 危险遗漏清单

1. `MessageActions.kt:476` 等 guard——**保留**（并叠加 freshness token，§7.2）
2. `SessionSyncCoordinator` SSE dispatch——**保留**
3. `AppCoreOrchestration.kt:818-820` token-stream——**保留**
4. `ChatScaffold.kt:552-553,703` empty-tabs→home——**改**路由级判断
5. `ConnectionActions.kt:104` orphan guard——**改**为校验 persisted currentSessionId 在恢复 sessions 中
6. `SessionMutationActions.kt:303-305` delete fallback——**改**为 session list 选最近或 popToSessions
7. `decideAutoSelectSession` SelectRestored——**简化**为 NavigationPrefs last-active
8. `persistSessionCache` openIds 参数——**删**
9. `AppCore.kt:391-401` collector orphan 检查——**改**为 persisted id 有效性直查
10. `ChatScaffold` snapshotFlow L703——**删**

### 9.4 零残留门控（§14 G2）

```
rg 'openSessionIds|OpenSessionIdsChanged|reduceOpenSessionIdsChanged' src/main   # 必须 0 命中
rg 'setLastRoute\(NavRoute\.Chat\)|navigateTopLevel.*Chat' src/main               # 必须 0 命中
```

> **实施门控（§14 G2，AST 级）**：rg 不足，加 freeze 测试风格静态断言——UI 渲染不用 currentSessionId 选 transcript；openSessionIds 不在 main/test 源集；Chat 导航必带 id；异步提交必带 expected-id+freshness-token；覆盖 tests/fixtures/持久化迁移/别名。

---

## 10. delete/archive/refresh/host/cold-start transition

每个动作定义 **route transition + detail-state transition**，以 route id 为判据：

| 动作 | 当前（依赖 open/current） | route-driven 后 |
|---|---|---|
| close（返回列表） | closeSession filter openSessionIds；空→home | `popToSessions()` + `ChatCleared` |
| delete 当前 | `launchDeleteSession` `remainingOpenIds.lastOrNull()` | 若删的==route id → `popToSessions()`+`ChatCleared`；否则仅 sessionList 移除 |
| delete 非当前 | prune sessionList | 同 |
| SSE archive 当前 | `SessionSyncCoordinator:1357` filter open + `SessionArchived` | 若 archived==route id → `popToSessions()`+Missing/Cleared；否则仅 sessionList |
| REST archive 当前 | `launchSetSessionArchived:218` null current | 同 SSE |
| REST refresh | `launchLoadSessions` auto-select from openIds | 删 auto-select；refresh 不改 route（仅 sessionList）；若 route id 被 archived→popToSessions |
| host switch（异组） | `HostProfileController.purgePerHostState:491` 清 open+current | 清 currentSessionId+sessionCache+draft+`content=null`；**route 强制 popToSessions** |
| cold start | `applySavedSettings` 恢复 open+current | **route 恒 Sessions**；恢复 sessionCache+workdir；currentSessionId=null、content=null；删 decideAutoSelectSession |
| sub-agent/archive 清理 subtree | `cleanScrollStateForSubtree` | checkpoint 改由 entry 生命周期处理（§11） |

> **实施门控（§14 G5）**：每条 transition 形式化为状态机表（输入事件/当前 route/前态/后态/NavController 操作/允许中间帧/stale 行为）；`onSelectSession`/`selectSession`/`navigateToChat` 统一为强制带 route transition 接口；host-switch 清 state 与 pop route 顺序定义；Missing vs Cleared 标准；archive/refresh stale-result guard（叠 freshness token）。

---

## 11. 子 agent checkpoint / revert 迁移（SavedStateHandle 归属）

### 11.1 事实

- `parentReturnCheckpoints: Map<childId, ScrollCheckpoint>` in ChatState（`AppStateSlices.kt:593`）
- `openSubAgent`：dispatch `ParentCheckpointStored(childId)` 同步 → launch `selectSession(childId)` 异步
- `returnToParent`：读 `parentReturnCheckpoints[currentId]` → `ParentCheckpointConsumed` → `switchTo(parent, Restore)`（同步预消费 + 异步 Restore）
- `switchTo(parent, Restore)` 唯一区别：`SessionSelected` 携 `pendingScrollRequest.behavior=Restore`；消费端 `ChatMessageContent.kt:766-821` compare-and-clear

### 11.2 迁 per-entry `SavedStateHandle`（opus 提，bgpt 认可方向 + 补协议）

**D11 保留全局 map 的理由被 D9 自我推翻**：保留独立 map 的唯一辩护是"多 root 同时打开可 swipe"，但 D9 删了 pager/tab strip，list-detail 只有一个 detail pane，swipe 场景消失。导航退化为单一栈，per-entry `SavedStateHandle` 是自然归宿。

迁移收益：
- checkpoint 与具体 route entry 生命周期绑定，entry 出栈自然清理；
- **消除三处手工 sweep**（host-purge / `cleanScrollStateForSubtree` / archive subtree）；
- 跨进程死亡存活（优于 in-memory map）；
- **顺带修 BLOCK-6**（checkpoint 消费作为目标 entry effect 的唯一滚动意图来源）。

**转移协议（bgpt 指出 opus "pop 后读"有生命周期陷阱——child entry pop 即销毁，读不到 handle）**，四选一（实施时定）：
1. child effect 在 pop **前**读取并转交 parent，再 pop；
2. 存 **parent entry** 的 handle，以 child route id 为 key；
3. NavController saved-state 回传协议，pop 前写 parent handle；
4. checkpoint 作为 navigation result，parent 恢复时一次性消费。

**唯一滚动意图来源**：Restore 与 Latest 不得由两个独立 effect 竞争——checkpoint present→Restore、absent→Latest、consume exactly once。

**openSubAgent 顺序**（capture→persist→navigate）：
```
1. capture checkpoint（Compose 层同步）
2. 写入 SavedStateHandle（按所选协议：child entry / parent entry keyed by child）
3. navigateToChat(childId)
4. switchTo(childId, Latest) hydrate/load（route effect 触发）
```

**需验证**：`ScrollCheckpoint` Parcelable/Bundle-able；进程死亡恢复语义；deep-link 直入 child（无 checkpoint→Latest）；多层 child→grandchild 按 entry 一一对应；config change/重复导航/快速 pop 不重复消费。

### 11.3 revert 链

- `editFromMessage`（`ChatViewModel.kt:323`）/`retryRevertCutoff`（`:350`）读 currentSessionId——**改读 route param**
- `RevertConversation.execute`/`RevertCutoffCoordinator.ensure` 已显式传 sessionId——不动
- `filterBeforeRevert` 双源 guard（`AppStateDerived.kt:115`）——**保留**
- revert 后 `loadMessages(resetLimit=true)` 不改 route（同 session）；叠加 freshness token 防 stale 覆盖

---

## 12. 单次任务批次划分（decomposition §7：内部有序 commit，非双轨）

> 单次任务 = 一批 commit 一次性交付完整重构，最终态干净无并行旧实现。内部遵循 §7「结构搬动≠语义改动」拆成可独立 check.sh-green 的有序批次。

- **B0 基础设施（串行前置）**：AppRoute sealed + parseRoute；`LoadedContent` 值对象 + freshness token 字段；原子 action/reducer（SelectConversation/CloseDetail/DetailMissing）落 CrossSliceFieldsReducer；navigateToChat 脚手架。纯加法，check.sh green。
- **B0.5 薄垂直片（opus+bgpt 共识，spike-first）**：route `chat/{id}` + `LoadedContent` + 渲染 `content.sessionId==routeId` + freshness token，**只打通一条入口**（Sessions→tap→chat）+ failing-first 测试（close-all→无 transcript、切 B→无 A 内容、A→B→A→req-1 不覆盖 req-2）。先在窄路径端到端证明核心不变式 (P1)(P6)，再铺开。
- **B1 导航接线**：AppShell composable("chat/{id}")；navigateToChat 实现；删 saveState/restoreState；back 处理；删 LaunchedEffect(requestedRoute) 镜像。
- **B2 渲染切换（语义核心）**：ChatScaffold 改 derivedStateOf detail；删渲染权威 + safety-net；ChatEmptyState=None。此刻 (P1)(P2) 全面生效。
- **B3 入口切流**：SessionsScreen/FilesScreen/MainActivity/picker/drawer → navigateToChat(id)。
- **B4 状态清理**：删 openSessionIds（state/prefs/reducer）；delete/archive/refresh/host/cold-start transition 重写（§10）；持久化收敛。
- **B5 subagent/revert**：checkpoint 迁 SavedStateHandle + 转移协议（§11）；editFromMessage/retryRevertCutoff 读 route param。
- **B6 删旧物**：删 ChatSessionPager/ChatSessionTabStrip；删 lastRoute 镜像；清理过时测试；补状态机性质测试。

---

## 13. 实施编排与评审流程（本项目强制约定）

### 13.1 并行原则

在写作用域**不重叠**的前提下尽可能多线并行。**简单机械改动用 `fixer-zlm`，复杂跨文件/深层逻辑用 `fixer`**。

### 13.2 批次依赖与并行图（opus+bgpt：共享文件**串行**）

route-authority 是**跨文件不变式**，共享文件并行改极易"局部绿、整体不一致"。故：

- **串行（共享文件，同一时间只一个 fixer）**：`SessionViewModel` / `AppShell` / `ChatScaffold` / `AppCore` / `AppCoreOrchestration` / 状态 reducer / navigation effect。
- **可并行（不共享状态协议的叶子）**：独立测试编写、文档、不共享协议的资源机械迁移、B0.5 后 API 冻结的独立模块工作。

```
B0（串行前置）→ B0.5（薄垂直片，串行）→
 B1（导航接线，串行：AppShell/NavRoute/OrchestratorVM）
 B2（渲染核心，串行：ChatScaffold）依赖 B1
 B3（入口，可与 B2 末段并行若不碰 ChatScaffold：SessionsScreen/FilesScreen/MainActivity/picker/drawer）
 B4（状态清理，串行：store/reducer/actions/prefs）
 B5（subagent/revert，串行：SessionViewModel/RevertConversation）
 B6（删旧物+测试，可并行：ChatSessionPager/ChatSessionTabStrip 删除、测试编写）
```

fixer 分配：B0/B1/B2/B4/B5 复杂→fixer；B3/B6 机械→fixer-zlm。

### 13.3 评审流程（强制）

1. 每批次实施完成后，派 **rev-glm 评审该批次**（代码 + 该批次 invariant + 本文档 §14 对应门控）；glm pass 后该批次才算完成。
2. 所有批次 glm 通过后，跑 **`./scripts/check.sh`（编译+单测）** 全绿。
3. check 通过后，**整体派 rev-bgpt 评审 9.5**（评实施后整体：§14 全部门控落实、不变式成立、零残留门控、状态机测试、UI 合规）。bgpt ≥9.5 才算重构完成。
4. 发版前按 `.opencode/policies/review-gate.md`：评审产物归档 `.opencode/runs/reviews/<date>/<reviewer>_feature-chat-listdetail.json`；模拟器**强制**跑导航/通知-深链冷启动/子 agent 返回/删归档当前会话 connectedTest（`./scripts/emulator.sh status`→确认空闲→`start`→跑→`stop`）。

---

## 14. 设计层定型机制 + 实施硬门控清单

> v3 把 G1/G6 机制**回收到设计层定型**（§7/§11），仅保留"证明机制成立的测试"在实施层。G2-G5 为实施层测试门控。每批次 glm + 最终 bgpt 9.5 必须逐条核验。

| 门控 | 层 | 要求 |
|---|---|---|
| **G1 内容不可表达（设计层定型）** | 设计+实施 | **`LoadedContent` 值对象**（结构性 owner：`content.sessionId==routeId` 才渲染，构造即原子）**+ 窄化 freshness/incarnation token**（时序性 acceptance，覆盖 bgpt 的 A→B→A stale-load race；route-instance token / per-load request token / content-revision 三选一）。证明 `route=B ∧ 显示 A 内容` 与 `A→B→A 旧 load 覆盖新内容` 在内容层不可表达 |
| **G6 checkpoint（设计层定型）** | 设计+实施 | parentReturnCheckpoints 迁 **per-entry `SavedStateHandle`**；明确转移协议（四选一：pop 前转交 / parent-keyed-by-child / nav saved-state 回传 / nav-result 一次性消费）；Restore/Latest 单一滚动意图来源、consume once；`ScrollCheckpoint` Parcelable 验证；进程死亡/deep-link child/多层 child/config change/快速 pop 测试 |
| **G2 AST 级零残留门控** | 实施 | freeze 测试风格静态断言：UI 渲染不用 currentSessionId 选 transcript；openSessionIds 不在 main/test 源集；Chat 导航必带 id；异步提交必带 expected-id+freshness-token；覆盖 tests/fixtures/持久化迁移/别名 |
| **G3 导航入口 warm/cold-start 契约** | 实施 | warm/cold-start 导航事件暂存/消费契约（VM 未创建、NavHost 未 attached）；通知 intent 非法/已删 session → Missing/Sessions；`onSelectSession`/`selectSession`/`navigateToChat` 统一为强制带 route transition 接口 |
| **G4 route parser property-based 测试** | 实施 | URL 编码、`%2F`、`?`、`#`、空白、重复 query、`ses_` grammar、NavController 解码时序、`chat/new` vs `chat/{id}` 歧义、旧裸 `chat`/通知 intent/持久化恢复三路径统一进 Sessions |
| **G5 transition 状态机形式化** | 实施 | 每条 transition 状态机表（输入事件/当前 route/前态/后态/NavController 操作/允许中间帧/stale 行为）；host-switch 清 state 与 pop route 顺序；Missing vs Cleared 标准；archive/refresh stale-result guard |

---

## 15. 验证策略（decomposition §11 + architecture §8）

- **failing-first**：B0.5/B2 前先写红测试（close-all 后无 transcript、冷启动恒 sessions、route=B 不显示 A 内容、A→B→A req-1 不覆盖 req-2），实施后转 green。
- **全过程 emission 收集**：收集 `store.stateFlow` 每次 emission，断言无"`content.sessionId=X ∧ route=Y(X≠Y) ∧ content 非空`"的**可渲染**中间帧（允许过渡 emission，但必须不可渲染——由 §7.1 渲染归属校验保证）。
- **A→B→A stale-load 测试（bgpt）**：req-1（旧快照）不得覆盖 req-2（新）；分别验证 SSE/REST message-merge/send-completion/refresh/auto-expand/token-stream 路径均被 freshness token 拦截。
- **LoadedContent 结构测试**：reducers 不可撕裂 owner 与 messages（AST 级，G2）；切换会话时 content 整体替换。
- **SavedStateHandle checkpoint 测试**：进程死亡恢复、deep-link 直入 child（无 checkpoint→Latest）、多层 child→grandchild 按 entry 对应、config change/重复导航/快速 pop 不重复消费。
- **状态机性质测试**：SelectConversation/CloseDetail/delete/archive/SSE-archive/host-switch/draft/revert 序列 model-based，每步断言 P1-P6；store 边界 debug assert 非法聚合态 fail。
- **freeze 守护**：动 SessionViewModel/SessionSwitcher/ChatState 公开面前先读 freeze 测试（SessionSwitcherTest/AppActionReducerTest/T1cSessionListOwnershipTest/T1cSessionListComplexOwnershipTest），改后 GREEN；不加构造器 arity。
- **门控**：每批 check.sh；B0.5/B2/B4/B6 后模拟器 chat/navigation connectedTest。

---

## 16. 风险/回滚 + 标准合规

| 风险 | 缓解 |
|---|---|
| currentSessionId 双重职责解耦 | `LoadedContent` 结构 owner + freshness token（§7）；既有 guard 零改（仅叠加 token） |
| 单次任务大改难验证 | §12 有序批次各自 check.sh；**B0.5 薄垂直片**先验证核心不变式；failing-first + 全过程 emission + 零残留门控 + G1-G6 强证据 |
| 持久化移除 openSessionIds 单向 | 仅丢 tab 列表，会话不丢（sessionCache 保留） |
| fromRouteKey 变更影响深链/通知 | parseRoute 统一兜底 Sessions（fail-safe）；通知 intent 必带 id；G4 property-based 测试 |
| checkpoint SavedStateHandle 迁移 | §11 转移协议（四选一）+ Parcelable 验证 + 全套恢复/快速导航测试 |
| 共享文件并行冲突 | §13.2 共享文件**串行**（route-authority 跨文件不变式） |
| A→B→A stale-load 覆盖 | freshness/incarnation token（§7.2）拦截旧 incarnation |

- **ui-style-spec**：不新增 overlay；删 tab strip；quick-switch 复用 SessionPickerSheet（Tier B 已合规）。
- **versioning**：建议 **minor**（移除 tab、改 list-detail 为用户可见行为变更）；版本 git 派生无硬编码。
- **slimapi**：L0-L3 零改动；客户端 session-id threading 由 §7 LoadedContent owner + freshness token + §14 G2 AST 门控保证。
- **review-gate**：发版前评审 agent 出 `.opencode/runs/reviews/<date>/<reviewer>_feature-chat-listdetail.json`；模拟器强制 connectedTest。

---

## 附录 A：相关调研会话（可追溯）

- 根因+实现映射：explorer（chat/tab 实现 + slimapi 边界 + 全量引用矩阵 + checkpoint/revert 链）
- 业界对照：librarian（移动端会话切换范式）
- 架构判定：oracle（Option B list-detail 推荐）
- 评审：rev-bgpt v1（7.4）、v2（7.8）；rev-opus 终审（有条件通过，提 LoadedContent 值对象）；rev-bgpt 复核（有条件通过，补 A→B→A race → freshness token）
