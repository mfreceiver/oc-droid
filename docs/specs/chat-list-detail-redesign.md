# Chat List-Detail 重构设计方案（方案 B）

> **状态**：设计基线 v2（bgpt 评审 7.8/10）
> **日期**：2026-07-24
> **定位**：chat tab 问题的设计基线 + 调研快照 + 实施硬门控。依 `docs/specs/decomposition-guidelines.md` §0，一次性执行计划本应归档 `docs/ocmar/plans/`，但该路径被 `.gitignore`（第 108-109 行「生成式审计/规划报告，不应进 git」）排除为本地工件；应项目要求需入库可追溯，故置于被跟踪的 `docs/specs/`（其核心架构决策——不变式、模块化合规、list-detail 模型、实施门控——具长期参考价值）。
> **行号约定**：本文行号均为调研时快照，实施时以最新代码为准；实施前应重新生成一次引用矩阵。

---

## 0. 文档定位与评审状态

### 0.1 评审严格度区分（重要）

本问题经过两轮 bgpt 评审（v1=7.4 block、v2=7.8 block）。核心结论：bgpt 要求的"bug 结构不可表达"证明（route-generation CAS、AST 级静态门控、"所有写入路径原子"的形式化证明、状态机表）本质是**实施级证据**，设计文档阶段无法提供（代码尚未写）。

因此采用**分层评审严格度**：
- **设计文档（本文）**：方向正确性、根因诊断、模块化合规、风险识别、实施流程 —— v2 已达"可指导实施"水准。
- **实施代码**：由实施流程（§13）的每批次 glm 评审 + 最终 bgpt 9.5 评审，强制落实"不可表达证明 / AST 门控 / 状态机形式化"（详见 §14 实施硬门控清单）。

### 0.2 评审历史

| 轮次 | 评审 | 得分 | 结论 | 主要 blocking |
|---|---|---|---|---|
| v1 | rev-bgpt | 7.4 | block | 不变量未达不可表达；文件矩阵不全；导航入口参数链未解决；fromRouteKey 不充分；delete/archive 链未闭合；checkpoint 迁移未证明 |
| v2 | rev-bgpt | 7.8 | block（作基线） | BLOCK-1 contentSessionId 仍是 currentSessionId 别名（需 generation-CAS）；BLOCK-6 checkpoint 消费顺序自相矛盾；BLOCK-2 零残留门控不足（需 AST 级）；BLOCK-5 transition 调用契约未闭合 |

两轮评审的完整 blocking 已转化为 §14 的实施硬门控清单。

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

1. `SessionViewModel.closeSession`（`SessionViewModel.kt:251-296`）把"关 tab"拆成 6 步**独立** store commit（OpenSessionIdsChanged → ChatCleared → persist → 清 composer → 改 NavState）。store 原子性只在单 dispatch 内，用户意图层非原子 → step1 后非法聚合态已落地。
2. **三个导航参与者抢权**：closeSession 改 NavState；AppShell `LaunchedEffect(requestedRoute)`（`AppShell.kt:134-136`）翻译成 `navigateTopLevel`；ChatScaffold 还有**第二套** close 处理器（`LaunchedEffect` `L702-708` 监听 openSessionIds 变空调 onBackToHome），且带 `.drop(1)`——对"已在非法空态 compose 出来的 Chat"无能为力。三路并发编排不确定。
3. 测试历史即证据：`SessionViewModelPassThroughTest` 含大量 close-all 分支回归（陈旧磁盘 ids、null 持久化、关非当前 tab、draft、关当前子 agent 的 root…）——每修一组合漏下一组合。典型症状：在每 mutation 点**程序化**事后维护不变式，而非让非法状态不可表达。
4. 次要放大器：`navigateTopLevel` 用 `saveState=true/restoreState=true`（`AppShell.kt:85-91`，bottom-nav 配方，本 app 无 bottom nav）；持久化三件套独立写盘，进程死亡留混合快照。
5. 隐式产品例外：`draftWorkdir != null` 时关最后一个 tab 故意留 Chat——是"非预期页面"主要嫌疑之一；draft 应为一等状态而非 close-all 规则的豁免。

---

## 2. 业界对照

主流会话类 app（WhatsApp / Telegram / Signal / iMessage / Discord / Slack）**不用浏览器式 tab**，而用 **list-detail（主从）**：会话列表常驻 root，聊天是 push 的 detail，系统返回退列表；"无选中"显式占位页（Signal `NoSelectedConversation`、Material 3 `ListDetailSceneStrategy.detailPlaceholder`）。

本 app 已有三套 quick-switch 入口：`SessionsScreen`（列表）+ `SessionPickerSheet` + `RecentSessionsDrawer`。**删 tab strip 不损失可达性，是做减法**。

---

## 3. slimapi 边界

slimapi 是 `/slimapi/` 前缀的服务端 sidecar（`data/api/SlimApi.kt`）。所有 session/message/status/stream 端点**早已按 `sessionId` 寻址**（`slimapi/messages/{sid}/since/{ts}`、`slimapi/sessions/{sid}/stream` 等）。服务端无"当前 tab / current session"概念。

**本重构是纯客户端改造，L0–L3（data/）零改动**。`SlimApi.kt` / `StandardApi.kt` / `SSEClient.kt` / `TokenStreamClient.kt` 无需任何改动。客户端 session-id threading 的正确性由 §7 expected-id guard + §14 AST 门控保证（禁止 UI/service 隐式读全局 current 决定提交目标）。

---

## 4. 模块化合规（对照 architecture.md / decomposition-guidelines）

| 准则 | 本方案遵从 |
|---|---|
| architecture §3 分层 | 导航身份归 L5 ui/shell；数据指针归 L5 state slice；编排归 L4 controller（SessionSwitcher）；data L0-L3 零改动 |
| architecture §4 差异下沉 | 导航重构与传输无关，符合"共享形状专有取数" |
| decomposition §3 不加构造器 arity | SessionViewModel/SessionSwitcher 新行为用方法，不加构造器参数 |
| decomposition §5 单一归属 | 导航身份唯一=路由；删 lastRoute 第二套权威、删 ChatScaffold safety-net 第二套 close 处理器 |
| decomposition §6 单向 DAG | feature→OrchestratorVM.navigateToChat→NavController，无回调环 |
| decomposition §7 结构搬动≠语义改动 | §12 批次内部有序 commit（先搬结构后改语义），各自 check.sh-green |
| decomposition §9 UI 域 | derivedStateOf 优于 LaunchedEffect+snapshotFlow；不抽新 scope-owning 控制器，编排并入既有 SessionViewModel；ChatMessageList 入口（冻结 seam）不动 |

---

## 5. 目标不变式

```
(P1) 渲染会话内容 ⟺ 路由=chat/{id} 且内容归属==id
(P2) 路由=sessions ⟹ 占位页，绝不渲染任何 transcript
(P3) 冷启动 ⟹ 路由恒 sessions（无复活对象）
(P4) chat/{id} 中 id 已删/非法 ⟹ Missing 态或 pop 回列表，绝不渲染别的会话
(P5) 导航身份唯一=路由参数；currentSessionId 仅作数据指针与后台 expected-id guard，不决定渲染
(P6) 后台异步结果（SSE/REST/token-stream/refresh）提交给会话 X ⟹ commit 时校验 expected-id，否则丢弃
```

(P1)+(P6) 使"tab 全关却显示某 chat"与"切到 B 却显示 A 内容"不可表达。

> **实施强化（§14 门控 G1）**：(P1)/(P6) 的"不可表达"强度需由 route-generation CAS 落实（见 §7、§14）。设计文档层面此为目标不变式；实施代码层面为强制 CAS。

---

## 6. 核心设计决策

| # | 决策 | 取代 |
|---|---|---|
| D1 | 带参路由 `chat/{sessionId}`；新建 `chat/new?workdir=…`（沿用 `chat/preview?…` 带参先例） | 参数化 `Chat("chat")`，身份藏全局可变 currentSessionId |
| D2 | `parseRoute` 未知/空 → 回落 `Sessions` | 回落 `Chat` |
| D3 | 渲染权威 = 路由派生 sealed `ChatDetailState{None; Loading(id); Content(id); NewConversation(workdir); Missing(id)}`（derivedStateOf） | currentSessionId!=null |
| D4 | draft/新会话 = 显式路由 `chat/new` | currentSessionId==null && draftWorkdir!=null 隐式例外 |
| D5 | 导航身份唯一在路由；currentSessionId 降级为数据指针，由路由 effect 设置 | 双重职责 |
| D6 | 删 ChatScaffold 空-tab safety-net LaunchedEffect（L702-708） | 双 close 处理器 |
| D7 | Chat 不走 saveState/restoreState | bottom-nav 配方误用 |
| D8 | 移除 NavState.lastRoute 第二套 NavController；改直接 navigate 回调 | 镜像同步 |
| D9 | openSessionIds 整体移除（tab strip/pager 删） | SessionListState.openSessionIds + 持久化 + auto-select 全链 |
| D10 | SelectConversation/CloseDetail 单原子跨切片 action | closeSession 拆 6 步独立 commit |
| D11 | 子 agent 复用 `chat/{childId}` 详情模型；parentReturnCheckpoints 保留（迁移见 §11） | 子 agent 本就不进 tab |

---

## 7. 核心机制：渲染权威 + 内容归属校验 + expected-id guard

**关键洞察**：全量矩阵（§9）证实 currentSessionId 被 ~15 处后台 guard 使用（`MessageActions.kt:476,129,425,455,544,615,634`、`SessionSyncCoordinator.kt:1333,1399,1823-1884,2338`、`AppCore.kt:600-611`、`AppCoreOrchestration.kt:818-820` token-stream gate），语义都是"这个异步结果是否属于当前活跃会话"——**这正是 expected-session-id guard，保留即正确**。

### 7.1 渲染权威 = 路由派生态

`ChatScaffold` 渲染唯一读 `detail`（删 `currentSessionId!=null` 渲染权威 `ChatScaffold.kt:862-895`；删 safety-net `L702-708`）：

```
detail(routeId, chat) = when {
  routeId == null → None
  chat.currentSessionId == routeId → Content(routeId)   // 归属一致才显示内容
  else → Loading(routeId)                                // 滞后时显示 Loading，绝不显示旧会话内容
}
```

渲染层永不直接信任 `messages`，而是信任归属校验。当路由 A→B 后 currentSessionId 暂仍为 A（异步未跟上）时，`A != B` → 渲染 `Loading(B)`，绝不显示 A 的 messages。

### 7.2 原子 reducer

切换会话时 currentSessionId 更新与清空 messages/parts/streaming 在**同一 `StoreState.copy`**（现有 `reduceSessionSelected` `ChatFieldsReducer.kt:107-127` 正是如此：15 字段一次 copy）。故不存在"currentSessionId=B 但 messages=A"的单次提交。

### 7.3 expected-id guard（后台提交）

所有后台异步结果提交时校验 `sessionId == currentSessionId`（既有 guard 语义），不等则丢弃。

### 7.4 实施强化（§14 门控 G1，对齐 bgpt BLOCK-1）

v2 的 `contentSessionId` 仍是 `currentSessionId` 别名，未达"结构不可表达"。实施阶段**必须**引入 route-generation CAS（参照项目既有 `sseConnectedGeneration` 单调 generation-stamped CAS 先例，architecture §5）：

- `ChatState` 增 `activeRouteToken: Long`（单调递增，每次 SelectConversation/导航 +1）+ `loadedContentRouteToken: Long`（当前 messages 归属）。
- 内容提交（messages merge/SSE/token-stream/refresh）必带 `expectedRouteToken`，提交时 CAS 校验 `expected == activeRouteToken`，不等则丢弃。
- 结构化内容身份：`ChatDetailState` 携 `routeSessionId` + `loadedContentSessionId`。
- 异步提交统一 guard：`expectedSessionId + expectedHostGroup + expectedRouteToken`（防 host 切换后旧结果提交）。

这样 route=B(token=g2) 时，A 的旧 payload（expected=g1）被 CAS 拒绝——**内容层结构不可表达**，不只是渲染层 fail-safe。

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

> **实施强化（§14 门控 G3/G4）**：需补 warm-start/cold-start 导航事件暂存/消费契约（VM 未创建、NavHost 未 attached 时）；route parser property-based 测试（URL 编码、`%2F`、`?`、`#`、空白、重复 query、`ses_` grammar 稳定性、NavController 解码时序、`chat/new` vs `chat/{id}` 歧义消除）；通知指向不存在/已删会话 → Missing/Sessions。

---

## 9. 全量引用矩阵（摘要）与零残留门控

rg 穷尽（src/main+test）：openSessionIds ~45 命中、currentSessionId ~80+、lastRoute/nav ~40、delete/archive/refresh ~30。

### 9.1 openSessionIds 写点（整体移除）

`SessionSwitcher.switchTo Step8`（`SessionSwitcher.kt:564-568`）、`SessionViewModel.closeSession`（`:251-255`）、`AppCoreOrchestration.handleBulkArchiveRefresh`（`:951-973`）、`materializeDraftSession`（`:316-331`）、`SessionSyncCoordinator.handleSessionDigest`（`:1357-1374`）+ resync（`:1920-1938`）、`HostProfileController.purgePerHostState`（`:491-493`）、`launchDeleteSession`（`SessionMutationActions.kt:303-305`）、reducer `reduceOpenSessionIdsChanged`（`SessionListFieldsReducer.kt:30-34`）/`reduceDraftSessionMaterialized`/`reduceSessionArchived`/`reduceBulkSessionsRefreshed`（CrossSliceFieldsReducer）、持久化 `SessionPrefs.openSessionIds`。

### 9.2 currentSessionId guard 点（保留作 expected-id guard）

`MessageActions.kt:476,129,425,455,544,615,634`、`SessionSyncCoordinator.kt:1333,1399,1823-1884,2338`、`AppCore.kt:600-611`、`AppCoreOrchestration.kt:818-820`（token-stream）、`AppCoreOrchestration.kt:284`（深链 no-op）。

### 9.3 危险遗漏清单（一旦漏改复活 ghost/跨会话提交/错误归档）

1. `MessageActions.kt:476` 等 guard——**保留**
2. `SessionSyncCoordinator` SSE dispatch——**保留**
3. `AppCoreOrchestration.kt:818-820` token-stream——**保留**
4. `ChatScaffold.kt:552-553,703` empty-tabs→home——**改**路由级判断
5. `ConnectionActions.kt:104` orphan guard——**改**为校验 persisted currentSessionId 在恢复 sessions 中
6. `SessionMutationActions.kt:303-305` delete fallback——**改**为 session list 选最近或 popToSessions
7. `decideAutoSelectSession` SelectRestored——**简化**为 NavigationPrefs last-active
8. `persistSessionCache` openIds 参数——**删**
9. `AppCore.kt:391-401` collector orphan 检查——**改**为 persisted id 有效性直查
10. `ChatScaffold` snapshotFlow L703——**删**

### 9.4 零残留门控（实施完成判据，§14 门控 G2 强化）

```
rg 'openSessionIds|OpenSessionIdsChanged|reduceOpenSessionIdsChanged' src/main   # 必须 0 命中
rg 'setLastRoute\(NavRoute\.Chat\)|navigateTopLevel.*Chat' src/main               # 必须 0 命中
```

> **实施强化（§14 门控 G2）**：rg 不足以区分合法 guard vs 非法渲染读取，也不覆盖 tests/fixtures/持久化迁移/别名（`openSessions`/`SessionTabStrip`/`ChatSessionPager`/`topBarState.openSessions`）。实施阶段**必须**加 AST 级静态检查（freeze 测试风格）：
> - UI 渲染代码不得用 `currentSessionId` 选择 transcript；
> - `openSessionIds` 不得出现在 main/test 源集；
> - Chat 导航必须调用带 id 的 API；
> - 所有异步提交 action 必须携带并校验 expected-id。

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
| host switch（异组） | `HostProfileController.purgePerHostState:491` 清 open+current | 清 currentSessionId+sessionCache+draft；**route 强制 popToSessions** |
| cold start | `applySavedSettings` 恢复 open+current | **route 恒 Sessions**；恢复 sessionCache+workdir；currentSessionId=null；删 decideAutoSelectSession |
| sub-agent/archive 清理 subtree | `cleanScrollStateForSubtree` | 保留（checkpoint 清理，§11） |

> **实施强化（§14 门控 G5）**：每条 transition 须形式化为状态机表（输入事件 / 当前 route / store 前态 / store 后态 / NavController 操作 / 允许中间帧 / stale callback 行为）；`onSelectSession`/`selectSession`/`navigateToChat` 统一为强制带 route transition 的接口；host-switch 清 state 与 pop route 顺序定义；`Missing(id)` vs `Cleared` 选择标准定义；archive/refresh 的 stale-result guard 形式化。

---

## 11. 子 agent checkpoint / revert 迁移

### 11.1 事实

- `parentReturnCheckpoints: Map<childId, ScrollCheckpoint>` in ChatState（`AppStateSlices.kt:593`）
- `openSubAgent`：dispatch `ParentCheckpointStored(childId)` 同步 → launch `selectSession(childId)` 异步
- `returnToParent`：读 `parentReturnCheckpoints[currentId]` → `ParentCheckpointConsumed` → `switchTo(parent, Restore)`
- `switchTo(parent, Restore)` 唯一区别：`SessionSelected` 携 `pendingScrollRequest.behavior=Restore(checkpoint)`；消费端 `ChatMessageContent.kt:766-821` compare-and-clear

### 11.2 route-driven 原子顺序（消除危险窗口）

**openSubAgent**（capture→persist→navigate）：
```
1. capture checkpoint（Compose 层同步）
2. dispatch ParentCheckpointStored(childId, checkpoint)   // 先持久化
3. navigateToChat(childId)                                  // 后导航
4. switchTo(childId, Latest) hydrate/load（由 route effect 触发）
```

**returnToParent**（以 route param 为 key）：
```
1. routeId = current route param（childId）
2. checkpoint = parentReturnCheckpoints[routeId]
3. navigateToChat(parentId)                          // 导航父
4. switchTo(parentId, Restore(checkpoint))           // 写 pendingScrollRequest(Restore)
   → 消费端 ChatMessageContent LaunchedEffect(routeParam=parentId) compare-and-clear
```

### 11.3 revert 链

- `editFromMessage`（`ChatViewModel.kt:323`）/`retryRevertCutoff`（`:350`）读 currentSessionId——**改读 route param**
- `RevertConversation.execute`/`RevertCutoffCoordinator.ensure` 已显式传 sessionId——不动
- `filterBeforeRevert` 双源 guard（`AppStateDerived.kt:115`）——**保留**
- revert 后 `loadMessages(resetLimit=true)` 不改 route（同 session）

> **实施强化（§14 门控 G6，对齐 bgpt BLOCK-6）**：v2 的"到达后消费"与流程"导航前预消费"冲突，且 pending-Restore 在导航后才写存在 route effect 先初始化 Latest 的竞态；导航失败丢 checkpoint。实施阶段**必须**：
> - checkpoint transaction 语义：导航前 persist（**不预消费**）；消费移到目标 route entry 的 effect（compare-and-clear）；pending-Restore 必须在 route effect 启动前就位（或 route effect 等待 Restore）；
> - 导航失败/进程中断时 checkpoint 保留（由 routeToken 判废弃）；
> - route-level 测试：root→child→parent→grandchild、快速 A→B→C、host purge、删 parent/child。

---

## 12. 单次任务批次划分（decomposition §7：内部有序 commit，非双轨）

> 单次任务 = 一批 commit 一次性交付完整重构，最终态干净无并行旧实现。内部遵循 §7「结构搬动≠语义改动」拆成可独立 check.sh-green 的有序批次。

- **B0 基础设施（串行前置）**：AppRoute sealed + parseRoute；ChatDetailState sealed；原子 action/reducer（SelectConversation/CloseDetail/DetailMissing）落 CrossSliceFieldsReducer；navigateToChat 脚手架。纯加法，check.sh green。
- **B1 导航接线**：AppShell composable("chat/{id}")；navigateToChat 实现；删 saveState/restoreState；back 处理；删 LaunchedEffect(requestedRoute) 镜像。
- **B2 渲染切换（语义核心）**：ChatScaffold 改 derivedStateOf detail；删渲染权威 + safety-net；ChatEmptyState=None。此刻 (P1)(P2) 生效。
- **B3 入口切流**：SessionsScreen/FilesScreen/MainActivity/picker/drawer → navigateToChat(id)。
- **B4 状态清理**：删 openSessionIds（state/prefs/reducer）；delete/archive/refresh/host/cold-start transition 重写（§10）；持久化收敛。
- **B5 subagent/revert**：checkpoint 原子顺序（§11）；editFromMessage/retryRevertCutoff 读 route param。
- **B6 删旧物**：删 ChatSessionPager/ChatSessionTabStrip；删 lastRoute 镜像；清理过时测试；补状态机性质测试。

---

## 13. 实施编排与评审流程（本项目强制约定）

### 13.1 并行原则

在写作用域**不重叠**的前提下尽可能多线并行。**简单机械改动用 `fixer-zlm`，复杂跨文件/深层逻辑用 `fixer`**。同文件多 fixer 禁止（若某文件被多批次触碰，需先内部分区或串行）。

### 13.2 批次依赖与并行图

```
B0（串行前置，必须先完成）
 ├─ B1（导航：AppShell/NavRoute/OrchestratorVM）         ┐
 ├─ B3（入口：SessionsScreen/FilesScreen/MainActivity/   ├── 三者写作用域不重叠 → 可并行
 │      picker/drawer）                                  │
 └─ B5（subagent/revert：SessionViewModel/RevertConversation） ┘
B2（渲染核心：ChatScaffold）依赖 B0+B1，串行
B4（状态清理：store/reducer/actions/prefs）依赖 B0，与 B1/B2/B3 UI 文件不重叠，可与 B2 后期并行
B6（删旧物：ChatSessionPager/ChatSessionTabStrip + 测试）最后，依赖 B1-B5
```

fixer 分配建议：B1 复杂→fixer；B3 机械入口改造→fixer-zlm；B5 复杂→fixer；B2 渲染核心→fixer；B4 状态层→fixer；B6 机械删除+测试→fixer-zlm。

> **注意（bgpt 指出）**：`SessionViewModel`/`AppShell`/`ChatScaffold`/`AppCore`/`AppCoreOrchestration` 是多批次共同边界，并行图按文件隔离可能产生"局部绿、整体不一致"。实施时对这些共享文件须显式排他（同一时间只一个 fixer 改），或拆成更细的函数级分区。

### 13.3 评审流程（强制）

1. 每批次实施完成后，派 **rev-glm 评审该批次**（代码 + 对该批次的 invariant + 本文档 §14 对应门控）；glm pass 后该批次才算完成。
2. 所有批次 glm 通过后，跑 **`./scripts/check.sh`（编译+单测）** 全绿。
3. check 通过后，**整体派 rev-bgpt 评审 9.5**（评实施后整体：§14 全部门控落实、不变式成立、零残留门控、状态机测试、UI 合规）。bgpt ≥9.5 才算重构完成。
4. 发版前按 `.opencode/policies/review-gate.md`：评审产物归档 `.opencode/runs/reviews/<date>/<reviewer>_feature-chat-listdetail.json`；模拟器**强制**跑导航/通知-深链冷启动/子 agent 返回/删归档当前会话 connectedTest（`./scripts/emulator.sh status`→确认空闲→`start`→跑→`stop`）。

---

## 14. bgpt 评审 blocking → 实施硬门控清单

以下为两轮 bgpt 评审 blocking 转化的实施阶段强制门控。每批次 glm 评审与最终 bgpt 9.5 评审**必须**逐条核验。

| 门控 | 对应 blocking | 实施要求 |
|---|---|---|
| **G1 内容归属 route-generation CAS** | BLOCK-1 | 引入 `activeRouteToken`+`loadedContentRouteToken`（参照 `sseConnectedGeneration` 先例）；内容提交带 expected-token，CAS 校验；`ChatDetailState` 携 `routeSessionId`+`loadedContentSessionId`；异步提交统一 `expectedSessionId+expectedHostGroup+expectedRouteToken`。证明 `route=B ∧ 显示 A 内容` 在内容层（非仅渲染层）不可表达 |
| **G2 AST 级零残留门控** | BLOCK-2 | freeze 测试风格静态断言：UI 渲染不用 currentSessionId 选 transcript；openSessionIds 不在 main/test 源集；Chat 导航必带 id；异步提交必带 expected-id；覆盖 tests/fixtures/持久化迁移/别名（openSessions/SessionTabStrip/ChatSessionPager） |
| **G3 导航入口 warm/cold-start 契约** | BLOCK-3 | warm-start/cold-start 导航事件暂存/消费契约（VM 未创建、NavHost 未 attached 时）；通知 intent 非法/已删 session → Missing/Sessions；`onSelectSession`/`selectSession`/`navigateToChat` 统一为强制带 route transition 接口 |
| **G4 route parser property-based 测试** | BLOCK-4 | URL 编码、`%2F`、`?`、`#`、空白、重复 query、`ses_` grammar 稳定性、NavController 解码时序、`chat/new` vs `chat/{id}` 歧义消除、旧裸 `chat`/通知 intent/持久化恢复三路径统一进 Sessions |
| **G5 transition 状态机形式化** | BLOCK-5 | 每条 transition 状态机表（输入事件/当前 route/前态/后态/NavController 操作/允许中间帧/stale 行为）；host-switch 清 state 与 pop route 顺序；Missing vs Cleared 标准；archive/refresh stale-result guard |
| **G6 checkpoint transaction 语义** | BLOCK-6 | 导航前 persist（不预消费）；消费移到目标 route entry effect（compare-and-clear）；pending-Restore 在 route effect 启动前就位；导航失败 checkpoint 保留（routeToken 判废弃）；route-level 测试（root↔child↔parent↔grandchild、快速 A→B→C、host purge、删 parent/child） |

---

## 15. 验证策略（decomposition §11 + architecture §8）

- **failing-first**：B2 前先写红测试（close-all 后无 transcript、冷启动恒 sessions、route=B 不显示 A 内容），B2 后转 green。
- **全过程 emission 收集**：收集 `store.stateFlow` 每次 emission，断言无"currentSessionId=X ∧ route=Y(X≠Y) ∧ messages 非空"的**可渲染**中间帧（注：允许 route/state 短暂不一致的过渡 emission，但必须不可渲染——由 §7 渲染归属校验保证；测试须明确区分"合法过渡态"与"非法可渲染态"）。
- **状态机性质测试**：SelectConversation/CloseDetail/delete/archive/SSE-archive/host-switch/draft/revert 序列 model-based，每步断言 P1-P6；store 边界 debug assert 非法聚合态 fail。
- **freeze 守护**：动 SessionViewModel/SessionSwitcher/ChatState 公开面前先读 freeze 测试（SessionSwitcherTest/AppActionReducerTest/T1cSessionListOwnershipTest/T1cSessionListComplexOwnershipTest），改后 GREEN；不加构造器 arity。
- **expected-id guard 测试（G1）**：A→B 切换中，A 的延迟 SSE/REST/send-completion/refresh/auto-expand/token-stream 提交被 CAS 丢弃（不污染 B）。
- **门控**：每批 check.sh；B2/B4/B6 后模拟器 chat/navigation connectedTest。

---

## 16. 风险/回滚 + 标准合规

| 风险 | 缓解 |
|---|---|
| currentSessionId 双重职责解耦 | 保留作数据指针；§7 渲染归属校验使滞后无害；现有 guard 零改；G1 用 generation-CAS 强化至不可表达 |
| 单次任务大改难验证 | §12 有序批次各自 check.sh；failing-first+全过程 emission+零残留门控+G1-G6 强证据 |
| 持久化移除 openSessionIds 单向 | 仅丢 tab 列表，会话不丢（sessionCache 保留） |
| fromRouteKey 变更影响深链/通知 | parseRoute 统一兜底 Sessions（fail-safe）；通知 intent 必带 id；G4 property-based 测试 |
| checkpoint 顺序 | §11 + G6 强制 capture→persist→navigate + route-scoped 消费 |
| 共享文件并行冲突 | §13.2 共享文件（SessionViewModel/AppShell/ChatScaffold/AppCore）显式排他 |

- **ui-style-spec**：不新增 overlay；删 tab strip；quick-switch 复用 SessionPickerSheet（Tier B 已合规）。
- **versioning**：建议 **minor**（移除 tab、改 list-detail 为用户可见行为变更）；版本 git 派生无硬编码。
- **slimapi**：L0-L3 零改动；客户端 session-id threading 由 §7 expected-id guard + §14 G2 AST 门控保证。
- **review-gate**：发版前评审 agent 出 `.opencode/runs/reviews/<date>/<reviewer>_feature-chat-listdetail.json`；模拟器强制 connectedTest。

---

## 附录 A：相关调研会话（可追溯）

- 根因+实现映射：explorer（chat/tab 实现 + slimapi 边界 + 全量引用矩阵 + checkpoint/revert 链）
- 业界对照：librarian（移动端会话切换范式）
- 架构判定：oracle（Option B list-detail 推荐）
- 评审：rev-bgpt v1（7.4）、v2（7.8）
