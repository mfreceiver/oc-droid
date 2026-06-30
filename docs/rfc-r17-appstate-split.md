# RFC R-17 — 拆分 AppState 上帝状态对象

> 版本：v1.0 | 日期：2026-06-30 | 状态：草拟（待评审）
>
> 定位：`remediation-plan-2026-06-30.md` §R-17 的详细设计方案。针对 `ui/MainViewModel.kt:86-227` 的 `data class AppState`（~44 扁平字段 + 6 个派生子 class）以及 `ui/chat/ChatTopBar.kt:81-133` 的 `ChatTopBarState`（20+ 字段）的全局重组问题进行渐进式拆分。

---

## 1. 问题诊断

### 1.1 当前结构

`AppState`（`MainViewModel.kt:86-227`）包含 ~44 个扁平字段，覆盖：

| 域 | 字段 | 消费方 |
|---|---|---|
| 连接 | `isConnected`, `isConnecting`, `serverVersion`, `connectionPhase`, `tunnelActivationState` | ChatTopBar, ChatScreen（空态）, Settings screen |
| Session 列表 | `sessions`, `hasMoreSessions`, `isLoadingMoreSessions`, `isRefreshingSessions`, `expandedSessionIds`, `loadedSessionLimit`, `sessionStatuses`, `pendingPermissions`, `pendingQuestions`, `childSessions`, `directorySessions`, `openSessionIds` | SessionsScreen, ChatTopBar（tab strip）, ChatScreen（permission/question cards） |
| 当前聊天 | `currentSessionId`, `messages`, `partsByMessage`, `streamingPartTexts`, `streamingReasoningPart`, `olderMessagesCursor`, `hasMoreMessages`, `isLoadingMessages` | ChatScreen（ChatMessageList）, ChatTopBar（title, context ring） |
| 文件浏览 | `filePathToShowInFiles`, `filePreviewOriginRoute`, `fileBrowserOpen`, `fileBrowserWorkdir` | FilesScreen, PhoneLayout |
| Composer | `inputText`, `imageAttachments`, `sendingSessionIds`, `availableCommands` | ChatInputBar |
| Agent/Provider | `agents`, `selectedAgentName`, `providers` | ChatTopBar（agent picker） |
| Unread 追踪 | `unreadSessions`, `tempClearedUnread`, `lastViewedTime` | ChatTopBar（tab strip dots） |
| 持久化 UI 偏好 | `themeMode`, `markdownFontSizes`, `lastNavPage` | SettingsScreen, MainActivity |
| Draft | `draftWorkdir` | ChatTopBar（title）, ChatScreen |
| Gap / Stale | `gapInfo`, `staleNotice` | ChatScreen |
| 流量统计 | `trafficSent`, `trafficReceived` | ChatTopBar（server dialog） |
| Error | `error` | AppToast |
| Host | `hostProfiles`, `currentHostProfileId` | ChatTopBar（server dialog） |
| Todos | `sessionTodos` | ChatTopBar（context menu→dialog） |

内部嵌套 6 个 `data class` 子类型（`ConnectionState`, `SessionState`, `ChatState`, `FileUiState`, `SettingsState`）和计算属性 `ContextUsage`，但这些都是**零存储的派生视图**（`get() =` 从扁平字段计算），并未真正解耦。

`ChatTopBarState`（`ChatTopBar.kt:81-133`）有 25 个字段，与 `AppState` 字段一一对应，由 `ChatScreen` 在每次重组时全量映射。

### 1.2 机制问题

```kotlin
// MainViewModel.kt:453-454
private val _state = MutableStateFlow(AppState())
val state: StateFlow<AppState> = _state.asStateFlow()
```

**任一字段变化 → `_state.update { copy(...) }` → 所有 `collectAsState()` 订阅者重组。**

项目中 ~60 处 `_state.update` 调用（`MainViewModel.kt` + `MainViewModelSyncActions.kt` + `MainViewModelSessionActions.kt` 等）。高频路径包括：

- SSE `message.part.delta` → 每秒多次 `_state.update`（`SyncActions.kt:326`）
- 用户打字 → `setInputText()` 每次按键触发重组（已在 `ChatInputBar` 自有 State 部分缓解，但 `ChatTopBarState` 映射仍参与重组）
- `selectSession()` 内部 **5 次** `_state.update`（`selectSessionState` + cache restore + upsert + unread + openSessionIds）

### 1.3 影响面

- **ChatScreen**（`collectAsStateWithLifecycle()` at line 58）：每次 AppState 变化 → ChatTopBar + ChatMessageList + ChatInputBar + 空态判断 + permission/question cards + 文件 overlay 全部重组。
- **SessionsScreen**（`collectAsStateWithLifecycle()` at line 43）：每次 SSE delta → workdirGroups/derived computations 重算 → LazyColumn 重组。
- **SettingsScreen** / **FilesScreen**：同理。

`ChatTopBarState` 20+ 字段未拆分，ChatTopBar 的 `SessionTabStrip` 嵌套在 LazyRow 中，每次重组都重新 `items(state.openSessions, key = { it.id })`。

---

## 2. 状态分组蓝图

按**重组边界**（哪些 UI 子树需要独立响应哪些状态变化）将 AppState 拆为以下独立 `MutableStateFlow`：

### 2.1 connectionFlow

```kotlin
data class ConnectionState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val serverVersion: String? = null,
    val connectionPhase: String? = null,
    val tunnelActivationState: TunnelActivationState = TunnelActivationState.Idle
)
```

**字段归属**：`isConnected`, `isConnecting`, `serverVersion`, `connectionPhase`, `tunnelActivationState`

**分组理由**：连接状态与聊天/文件/会话列表完全正交。变化频率低（仅在 health check、tunnel 激活时），但消费方分散（ChatTopBar 状态点 + ChatScreen 空态）。独立 Flow 后，打字/session 切换不再触发 ChatScreen 空态判断。

**消费方**：ChatTopBar（server icon tint, connectionPhase 文本）、ChatScreen（`ChatEmptyState` + `ChatInputBar.enabled`）

### 2.2 chatFlow

```kotlin
data class ChatState(
    val currentSessionId: String? = null,
    val messages: List<Message> = emptyList(),
    val partsByMessage: Map<String, List<Part>> = emptyMap(),
    val streamingPartTexts: Map<String, String> = emptyMap(),
    val streamingReasoningPart: Part? = null,
    val olderMessagesCursor: String? = null,
    val hasMoreMessages: Boolean = true,
    val isLoadingMessages: Boolean = false,
    val gapInfo: GapInfo? = null,
    val staleNotice: Boolean = false,
    val error: String? = null
)
```

**分组理由**：当前聊天内容（messages/parts/streaming）是最高频变化的域（SSE delta 每秒多次）。独立 Flow 后，session 列表/连接状态/设置等不受 SSE 写入的影响。

**注意**：`error` 也被 SettingsScreen/SessionsScreen 使用（全局错误）。折中：error 同时保留在 `settingsFlow` 的镜像字段或改为独立的 `errorFlow`。见 §3（派生 vs 存储决策）。

**消费方**：ChatMessageList（messages/parts/streaming/gap），ChatTopBar（currentSessionId → title, contextUsage 计算），ChatScreen（staleNotice banner, error toast）

### 2.3 sessionListFlow

```kotlin
data class SessionListState(
    val sessions: List<Session> = emptyList(),
    val sessionStatuses: Map<String, SessionStatus> = emptyMap(),
    val expandedSessionIds: Set<String> = emptySet(),
    val loadedSessionLimit: Int = MainViewModelTimings.sessionPageSize,
    val hasMoreSessions: Boolean = true,
    val isLoadingMoreSessions: Boolean = false,
    val isRefreshingSessions: Boolean = false,
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    val pendingQuestions: List<QuestionRequest> = emptyList(),
    val childSessions: Map<String, List<Session>> = emptyMap(),
    val directorySessions: Map<String, List<Session>> = emptyMap(),
    val openSessionIds: List<String> = emptyList(),
    val sessionTodos: Map<String, List<TodoItem>> = emptyMap()
)
```

**分组理由**：Session 列表是低频变化（`loadSessions` / `loadMoreSessions` / SSE `session.created` / `session.updated`），但与高频 chat 状态混在一起导致 SessionsScreen 在每次 SSE delta 时重新计算 `workdirGroups` + `remember` 的 derived computations，虽然 `remember` key 不变会拦截一部分，但 LazyColumn 仍会扫描 diff。

**消费方**：SessionsScreen, ChatTopBar（session tab strip, session dropdown, context menu todos）

### 2.4 settingsFlow

```kotlin
data class SettingsState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val markdownFontSizes: MarkdownFontSizes = MarkdownFontSizes(),
    val selectedAgentName: String = "build",
    val agents: List<AgentInfo> = emptyList(),
    val providers: ProvidersResponse? = null,
    val availableCommands: List<CommandInfo> = emptyList(),
    val error: String? = null
)
```

**分组理由**：全局式设置/偏好，写频率极低（用户手动切换），但消费方是 MainActivity（theme→OpenCodeTheme）、ChatTopBar（agent picker）、ChatInputBar（commands）、SettingsScreen。与 chat/session 正交。

**注意**：`availableCommands` 仅在 loadCommands（连接后一次）和 host switch 时写入——属于"启动配置"而非实时状态。放在 settings 而非 composer 更合理。

### 2.5 composerFlow

```kotlin
data class ComposerState(
    val inputText: String = "",
    val imageAttachments: List<ComposerImageAttachment> = emptyList(),
    val sendingSessionIds: Set<String> = emptySet(),
    val draftWorkdir: String? = null
)
```

**分组理由**：Input 变化极高频（每次按键），目前虽然 `ChatInputBar` 使用自身的 `TextFieldValue` 避免了最坏情况（在 `setInputText` 处写入 AppState），但每次输入变化仍触发整个 ChatScreen（包括 ChatTopBar 的 20+ 字段映射）重组。独立 Flow 后只有 ChatInputBar 重组。

`draftWorkdir` 分到这里而非 chat/session，是因为 draft 的语义是"composer 的临时状态"——它决定了 ChatInputBar 是否可见、ChatTopBar 显示 draft 标题而非 session 标题。

### 2.6 fileFlow

```kotlin
data class FileUiState(
    val filePathToShowInFiles: String? = null,
    val filePreviewOriginRoute: String? = null,
    val fileBrowserOpen: Boolean = false,
    val fileBrowserWorkdir: String? = null
)
```

**分组理由**：文件浏览 UI 状态完全独立，仅 FilesScreen + PhoneLayout overlay 消费。

### 2.7 hostFlow

```kotlin
data class HostState(
    val hostProfiles: List<HostProfile> = emptyList(),
    val currentHostProfileId: String? = null
)
```

**分组理由**：Host profile 是一个全局配置域，写频率极低（保存/切换/导入），但 ChatTopBar（server dialog）和 SettingsScreen 都需要它。独立 Flow 后 host 切换只通知关心它的组件。

### 2.8 unreadFlow

```kotlin
data class UnreadState(
    val unreadSessions: Set<String> = emptySet(),
    val tempClearedUnread: Set<String> = emptySet(),
    val lastViewedTime: Map<String, Long> = emptyMap()
)
```

**分组理由**：Unread 标记依赖 session status（`isBusy`）+ chat（`currentSessionId`）+ foreground 等跨组状态（见 §4）。独立 Flow 使 ChatTopBar 的 tab strip dots 独立订阅，不随 messages 变化重组。

### 2.9 trafficFlow

```kotlin
data class TrafficState(
    val trafficSent: Long = 0L,
    val trafficReceived: Long = 0L
)
```

**分组理由**：只在 ChatTopBar 的 ServerManagementDialog 打开时读取，且是唯一写入路径（`refreshTrafficStats()`）。独立 Flow 避免每 30s 一次的健康检查结果触发它更新。

### 2.10 汇总表

| Flow | 字段数 | 变化频率 | 主要消费方 |
|---|---|---|---|
| `connectionFlow` | 5 | 极低（连接/断开/retry） | ChatTopBar, ChatScreen 空态 |
| `chatFlow` | 11 | 极高（SSE delta） | ChatMessageList, ChatScreen |
| `sessionListFlow` | 13 | 中（session CRUD + SSE session events） | SessionsScreen, ChatTopBar |
| `settingsFlow` | 7 | 极低（用户手动） | SettingsScreen, MainActivity, ChatTopBar |
| `composerFlow` | 4 | 极高（打字+发送） | ChatInputBar, ChatScreen |
| `fileFlow` | 4 | 低（文件点击/关闭） | FilesScreen, PhoneLayout |
| `hostFlow` | 2 | 极低 | ChatTopBar（server dialog） |
| `unreadFlow` | 3 | 中（SSE message.updated） | ChatTopBar（tab strip） |
| `trafficFlow` | 2 | 极低（弹窗打开时刷新） | ChatTopBar（server dialog） |

---

## 3. 派生 vs 存储决策

### 3.1 应升级为真实存储 Flow 的字段

当前所有 44 字段都存储在 AppState 内。拆分后，上述 9 个 Flow 覆盖全部字段——**没有字段降级为纯派生**，每个 Flow 内的字段仍然是独立写入的。

### 3.2 应保持/引入为派生（derivedStateOf / map）的字段

| 派生字段 | 来源 Flow | 公式 |
|---|---|---|
| `AppState.contextUsage` | `chatFlow` (messages, partsByMessage, providers from settingsFlow) | 已是从 messages 计算的 `get()`，保持不变。拆后消费方需 combine `chatFlow` + `settingsFlow` 再计算 |
| `ConnectionState`（原派生 sub-class） | `connectionFlow` | 拆后 connectionFlow 本身就是这个 state，不再需要派生 |
| `SessionState`（原派生 sub-class） | `sessionListFlow` | 同上 |
| `ChatState`（原派生 sub-class） | `chatFlow` | 同上 |
| `FileUiState`（原派生 sub-class） | `fileFlow` | 同上 |
| `SettingsState`（原派生 sub-class） | `settingsFlow` | 同上 |

**关键原则**：原 6 个派生 sub-class 在拆分后自然消亡——各 Flow 本身已经按这些 sub-class 的边界对齐，直接使用 Flow 的 StateFlow 即可。

### 3.3 跨组依赖的表达

某些字段语义上跨组：

| 跨组字段 | 问题 | 解决方案 |
|---|---|---|
| `error` | 同时被 ChatScreen（toast）和 SettingsScreen 使用；写入路径散布在连接、发送、tunnel、命令执行等多个域 | **独立 `errorFlow`**（`MutableStateFlow<String?>`），不归属于任何单一域 Flow。或放在 `chatFlow` 因为 ChatScreen 是主消费方，SettingsScreen 只在 debug 页展示，改用 `LaunchedEffect` + `snapshotFlow` 推送到独立 channel。建议：**暂不拆分 error**，保持在 `chatFlow`（后续再审视） |
| `contextUsage` | 依赖 `chatFlow` (messages) + `settingsFlow` (providers + agents) | 拆后 ChatTopBar 用 `combine(chatFlow, settingsFlow)` 自行计算——由 Composable 层完成，不预存。`remember` 缓存稳定版本避免重复计算 |
| `unreadSessions` | 依赖 `unreadFlow` + `chatFlow` (currentSessionId) + `sessionListFlow` (sessionStatuses) + foreground 事件 | `unreadFlow` 已独立，但写入逻辑（SyncActions 判断 current 是否 tempCleared + busy）需要在 VM 内 `combine` 多 Flow 后 dispatch。见 §4 原子性分析 |
| `currentSessionId` | 属于 `chatFlow`（聊天的"坐标"），但也影响 `sessionListFlow` 的 tab strip 高亮 | ChatTopBar 消费时 `combine(sessionListFlow, chatFlow.map { it.currentSessionId })` 判断 active tab。不改变存储归属 |

---

## 4. 原子性分析

### 4.1 当前依赖多字段原子更新的场景

以下 `_state.update { copy(a=..., b=...) }` 在单次 `update` lambda 内同时修改多个逻辑域：

| 场景 | 位置 | 同时修改的字段 | 原子性要求 |
|---|---|---|---|
| `selectSession()` | `MainViewModel.kt:1289-1308` | `draftWorkdir`, `unreadSessions`, `lastViewedTime`, `tempClearedUnread` | 必须原子——如果 `draftWorkdir=null` 而 `unreadSessions` 未更新，中间态会被 ChatTopBar 读取（标题显示 draft，但 tab 不亮） |
| `selectSession()` 内部 `selectSessionState()` | `MainViewModelSessionActions.kt:238-257` | `currentSessionId`, `messages`, `partsByMessage`, `inputText`, `streamingPartTexts`, `streamingReasoningPart`, `gapInfo`, `staleNotice` | 必须原子——`messages=empty` 但 `currentSessionId` 已指向新 session，ChatScreen 会渲染空列表而非加载中态 |
| `selectHostProfile()` | `MainViewModel.kt:847-872` | **15 个字段**（`currentSessionId`, `messages`, `sessionStatuses`, `streaming*`, `sessionTodos`, `openSessionIds`, `unreadSessions`, `sessions`, `directorySessions`, `draftWorkdir`, `availableCommands`, `serverVersion`） | 必须原子——不清除旧 host 的 sessions，SessionsScreen 会短暂渲染无关数据 |
| `deleteHostProfile()` | `MainViewModel.kt:912-933` | 同 `selectHostProfile()` 模式 | 同上 |
| `createSessionInWorkdir()` | `MainViewModel.kt:1592-1604` | `currentSessionId`, `messages`, `partsByMessage`, `inputText`, `imageAttachments`, `sessionTodos`, `streaming*`, `draftWorkdir` | 必须原子——draft 模式需要所有这些字段同时切换到"空聊天+草稿标记"态 |
| `setInputText()` | `MainViewModel.kt:1830` | `inputText` + 调用 `SettingsManager.setDraftText`（副作用，非 AppState 字段） | 原子性要求低——单一字段变更 |
| `sendMessage()` draft branch | `MainViewModel.kt:1684-1698` | `draftWorkdir`（清除）→ 异步成功后 `sessions`, `currentSessionId`, `openSessionIds`, `unreadSessions`, `lastViewedTime` | 此处分两步 update，中间态合理——`draftWorkdir=null` 只是消除 draft 标记，空 chat 仍正确 |

### 4.2 拆 Flow 后的原子性策略

**核心原则**：拆 Flow 后，跨 Flow 的原子更新天然不存在——每个 Flow 的 `update {}` 是独立的。需要保持"逻辑原子性"的场景通过以下策略处理：

#### 策略 A：顺序赋值 + 每次赋值后的 UI 必须正确（推荐）

绝大多数场景的"原子性"实际上是**语义正确性**而非真正的 ACID 事务。拆 Flow 后，在 `viewModelScope.launch` 内顺序赋值各 Flow，保证**每个中间态都是合法 UI 状态**：

```kotlin
// selectHostProfile() 改造示例
fun selectHostProfile(profileId: String) {
    viewModelScope.launch {
        val profile = hostProfileStore.select(profileId)
        // 1. 先清空所有旧 host 状态（每个中间态都合法：空列表/空消息/空会话）
        _chatFlow.update { ChatState() }          // currentSessionId=null, messages=empty...
        _sessionListFlow.update { SessionListState() }  // sessions=empty, openSessionIds=empty...
        _unreadFlow.update { UnreadState() }
        _composerFlow.update { ComposerState() }  // draftWorkdir=null, inputText="" 
        // 2. 清理缓存
        clearSessionWindowCache()
        settingsManager.currentSessionId = null
        settingsManager.openSessionIds = emptyList()
        settingsManager.sessionCache = emptyList()
        settingsManager.currentWorkdir = null
        // 3. 仅连接信息更新
        _connectionFlow.update { ConnectionState(serverVersion = null) }
        // 4. re-configure & reconnect
        configureRepositoryForProfile(profile)
        refreshHostProfileState()  // 更新 hostFlow
        testConnection(force = true)  // 成功后会写 connectionFlow + loadInitialData
    }
}
```

**关键点**：上述顺序赋值中，Compose 可能在任何中间态重组——但每个中间态都是合法 UI（空列表、无聊天），不会崩溃或显示错误数据。这是"Eventual consistency + correct-at-each-step"策略。

#### 策略 B：combine 多 Flow 在 VM 层产出派生 Flow（跨组）

对于需要组合多个 Flow 才能决策的场景（如 unread 标记），在 VM 层用 `combine` 产出新的 `StateFlow`：

```kotlin
private val _unreadFlow = MutableStateFlow(UnreadState())
val unreadState: StateFlow<UnreadState> = _unreadFlow.asStateFlow()

// unread 决策依赖 chat + session 状态 → combine 而非单 Flow update
private val _effectiveUnreadFlow = combine(
    _unreadFlow,
    _chatFlow.map { it.currentSessionId },
    _sessionListFlow.map { it.sessionStatuses }
) { unread, currentSessionId, statuses ->
    // 保持原有逻辑：tempCleared 且 busy → 仍应标记
    unread
}.stateIn(viewModelScope, SharingStarted.Eagerly, UnreadState())
```

但这种方式引入了额外的 Flow 链路——**建议在实际遇到"中间态不合法"问题时再引入**，遵循 YAGNI。

#### 策略 C：专用 "事务" 包装器（不推荐，过度工程）

用 `Mutex` 包裹多个 Flow 赋值并不解决问题——Mutex 只保证赋值顺序，不阻止 Compose 在中间态重组。真正的"原子多 Flow 更新"需要……

**不需要**。现有 ~60 处 `_state.update` 中，真正要求"中间态不可见"的只有 `selectHostProfile` / `deleteHostProfile` 的 15 字段批量清除——但这些中间态（空列表）本身就是合法 UI。其他场景（如 `selectSession`）的中间态在拆 Flow 后会被 `launch` 内的连续赋值最小化窗口（几微秒），Compose 的帧同步保证不会渲染中间帧。

**结论**：按策略 A（顺序赋值 + 合法中间态）改造，不需要新的事务语义。如果未来某个场景的中间态确实不可接受，局部引入 `combine` 产出派生 Flow（策略 B）即可。

---

## 5. Compose 消费侧改造

### 5.1 `collectAsState()` → `collectAsStateWithLifecycle()`

当前代码已大部分使用 `collectAsStateWithLifecycle()`（ChatScreen、SettingsScreen、SessionsScreen、FilesScreen 均已升级），仅少数内部组件可能仍用旧版。**Milestone 1 审计并补齐**。

### 5.2 子组件用 `select` / `derivedStateOf` 订阅局部

拆 Flow 后，各 Screen 不再订阅全局 `viewModel.state`，而是**只订阅关心的 Flow**：

```kotlin
// ChatScreen 改造后
@Composable
fun ChatScreen(viewModel: MainViewModel, ...) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val chatState by viewModel.chatState.collectAsStateWithLifecycle()
    val composerState by viewModel.composerState.collectAsStateWithLifecycle()
    val hostState by viewModel.hostState.collectAsStateWithLifecycle()
    val unreadState by viewModel.unreadState.collectAsStateWithLifecycle()
    val settingsState by viewModel.settingsState.collectAsStateWithLifecycle()
    // 不再订阅 sessionListFlow / fileFlow / trafficFlow
}
```

**ChatTopBarState 映射优化**：不再在 ChatScreen 层全量映射 25 字段，而是 ChatTopBar 自己订阅需要的 Flow：

```kotlin
// ChatTopBar 改造后
@Composable
internal fun ChatTopBar(
    connectionState: ConnectionState,
    chatState: ChatState,
    hostState: HostState,
    sessionListState: SessionListState,
    unreadState: UnreadState,
    settingsState: SettingsState,
    trafficState: TrafficState,
    composerState: ComposerState,
    actions: ChatTopBarActions,
    modifier: Modifier = Modifier
) { ... }
```

或者保持 `ChatTopBarState` data class 但**在 ChatTopBar 内部订阅多个 Flow 并 assemble**，这样 ChatScreen 不再需要 `remember` 映射 ChatTopBarState。

### 5.3 ChatTopBarState 20+ 字段拆分

按现有的内部组件边界，ChatTopBarState 拆分映射关系：

| ChatTopBar 子组件 | 需要的 Flow |
|---|---|
| 标题区域（session 名称/draft/parent breadcrumb） | `chatState.currentSessionId` + `sessionListState.sessions` + `composerState.draftWorkdir` |
| `SessionTabStrip` | `sessionListState.openSessionIds`/`sessions` + `chatState.currentSessionId` + `unreadState.unreadSessions` |
| `ContextMenuButton`（context ring + dropdown） | `chatState`（计算 contextUsage）+ `settingsState.selectedAgentName`/`agents` + `sessionListState.sessionTodos` |
| `ServerManagementDialog` | `hostState` + `connectionState` + `trafficState` |
| Server icon（连接状态点） | `connectionState.isConnected`/`isConnecting`/`connectionPhase` |

**方案**：ChatTopBar 内部订阅 6 个 Flow（`connectionFlow`, `chatFlow.map{currentSessionId}`, `sessionListFlow`, `unreadFlow`, `settingsFlow`, `trafficFlow`），各组件的参数直接从 `collectAsStateWithLifecycle()` 读取。`ChatTopBarState` data class 可以作为内部 intermediate 保留（方便传参），但**不再由 ChatScreen 预先 mapping**。

### 5.4 关键性能收益预估

| 场景 | 当前重组范围 | 改造后重组范围 |
|---|---|---|
| SSE `message.part.delta` 到达 | ChatScreen + ChatTopBar + ChatMessageList + ChatInputBar + SessionsScreen | **仅 ChatMessageList**（订阅 `chatFlow.streamingPartTexts`） |
| 用户打字 `setInputText()` | ChatScreen + ChatTopBar（因为 `state` 变了） | **仅 ChatInputBar**（订阅 `composerFlow`） |
| `selectSession()` 切换 | ChatScreen + SessionsScreen（因为 `openSessionIds`/`unreadSessions` 变了） | ChatScreen 重组（chatFlow 变了），SessionsScreen **不重组**（sessionListFlow.openSessionIds 只改了 ids 列表，但 SessionsScreen 不直接订阅 openSessionIds——它计算 workdirGroups 从 sessions 出发） |
| `loadSessions()` 完成 | ChatScreen + SessionsScreen + ChatTopBar | **仅 SessionsScreen**（订阅 sessionListFlow）+ ChatTopBar（如果仍映射 sessions） |

---

## 6. 渐进迁移路径

### Milestone 1（零行为变更，纯 Compose 层优化）

**目标**：不改 AppState 结构，不改 VM 的 `_state`，仅在消费侧引入 `derivedStateOf` + `select` 局部订阅。

**改造内容**：
1. ChatScreen / SessionsScreen / SettingsScreen / FilesScreen 各自用 `derivedStateOf` 从全局 `state` 提取关心的子集，再传给子组件（如 ChatTopBar 接收 `ChatTopBarState` 但仅在 ChatScreen 层用 `remember(derivedStateOf { ... })` 稳定引用）
2. ChatTopBar 内部各子组件（SessionTabStrip, ContextMenuButton, ServerManagementDialog）用 `derivedStateOf` 订阅局部，避免整个 ChatTopBar 重组时重建 LazyRow items
3. 审计所有 `collectAsState()` 调用的 lifecycle 感知

**验收标准**：
- 编译通过 + 单元测试通过（MainViewModelTest 不需改动——AppState 结构未变）
- APK 构建通过，功能与 Milestone 0 行为完全一致
- Android Studio Layout Inspector 确认 SSE delta 时 ChatTopBar/SessionsScreen 不再重组

**不做的**：不创建新 StateFlow，不拆字段。

### Milestone 2（connectionFlow + trafficFlow 率先拆分）

**目标**：验证"拆 Flow"方案的可行性，从小 Flow（2-5 字段）入手。

**改造内容**：
1. 新增 `connectionFlow` (`MutableStateFlow<ConnectionState>`) 和 `trafficFlow` (`MutableStateFlow<TrafficState>`)
2. 改造所有 `_state.update { copy(isConnected=..., isConnecting=...) }` → `_connectionFlow.update { copy(...) }`
3. 改造消费端（ChatTopBar server icon, ChatScreen 空态）从 `connectionFlow` 读取
4. `AppState` 中对应字段**保留但不写入**（仅从 connectionFlow mirror，用于兼容旧消费方和测试），标记 `@Deprecated` 
5. 改造测试：`updateState()` 反射 helper 改为同时可写 `_connectionFlow`

**验收标准**：
- 编译 + 测试通过
- 连接/断开/tunnel 激活功能正常

### Milestone 3（composerFlow + fileFlow + settingsFlow）

**目标**：继续拆分中等频率 Flow。

**改造内容**：
1. 拆分 `composerFlow`（输入文本、附件、发送锁、draft）——**这是最大的重组收益**（打字不再触发 ChatTopBar 重组）
2. 拆分 `fileFlow`（文件浏览 UI 状态）
3. 拆分 `settingsFlow`（全局偏好、agent、providers）
4. `AppState` 中对应字段 deprecated
5. 测试改造：反射 helper 支持多 Flow 写入

**验收标准**：打字时 ChatTopBar 不重组（Layout Inspector 验证）

### Milestone 4（chatFlow + sessionListFlow + unreadFlow + hostFlow）

**目标**：拆分剩余高频/中频 Flow，AppState 退化为派生快照或删除。

**改造内容**：
1. 拆分 `chatFlow`（11 字段，最高频）
2. 拆分 `sessionListFlow`（13 字段）
3. 拆分 `unreadFlow`（3 字段）
4. 拆分 `hostFlow`（2 字段）
5. 处理跨 Flow 原子性（按 §4 策略 A）
6. `AppState` 退化为可选快照：`val appStateSnapshot: StateFlow<AppStateSnapshot>`，由 `combine(connectionFlow, chatFlow, ...) { ... }` 派生，供调试 logger 使用（不用于 UI 重组）

**验收标准**：
- 所有现有功能正常
- APK 构建通过
- Layout Inspector 确认仅目标 UI 子树重组
- AppState 不再被任何 `collectAsState` 消费（仅用于调试快照）

### Milestone 5（清理 + 测试 + ChatTopBarState 完全拆分）

**目标**：移除 AppState 的 `@Deprecated` 字段引用，清理冗余路径，彻底完成 ChatTopBarState 拆分。

**改造内容**：
1. 删除 AppState 及 6 个派生 sub-class（或将其退化为文档注释的 snapshot helper）
2. ChatTopBarState 删除，ChatTopBar 直接订阅各 Flow（不再通过 intermediate data class 映射）
3. 更新所有测试（见 §7）
4. 性能回归测试

---

## 7. 测试改造方案

### 7.1 现状

```kotlin
// MainViewModelTest.kt:124-130
private fun updateState(viewModel: MainViewModel, transform: (AppState) -> AppState) {
    val field = MainViewModel::class.java.getDeclaredField("_state")
    field.isAccessible = true
    val flow = field.get(viewModel) as MutableStateFlow<AppState>
    flow.value = transform(flow.value)
}
```

另有 `ForkSessionTest.kt:118` 直接访问 `_state` 字段写值。

**问题**：拆 Flow 后，`_state` 不再存在（或不再是权威存储），测试需要改为直接写入各域名 Flow。

### 7.2 改造策略

#### 方案：在 MainViewModel 内暴露 test-visible 的写入方法

```kotlin
@VisibleForTesting
internal fun setTestConnectionState(state: ConnectionState) {
    _connectionFlow.value = state
}

@VisibleForTesting
internal fun setTestChatState(state: ChatState) {
    _chatFlow.value = state
}
// ... 按需暴露
```

**替代方案**：反射访问仍可用，但需反射每个 Flow（`getDeclaredField("_chatFlow")` 等）。不如暴露方法清晰。

**推荐方案**：每个 Flow 的 `MutableStateFlow` 声明为 `@VisibleForTesting internal`，测试直接用 `viewModel._chatFlow.value = ...`（Kotlin 的 `internal` 在 test 模块可见）。

#### 7.3 改造范围

| 测试文件 | 受影响断言 | 改造方式 |
|---|---|---|
| `MainViewModelTest.kt` | ~60 处 `viewModel.state.value.xxx` | 改为 `viewModel.chatState.value.inputText` 等各 Flow 字段 |
| `ForkSessionTest.kt` | 反射写 `_state` + 读 `state.value.sessions` | 改为写 `_chatFlow` + 读 `chatState.value` |
| `CatchUpGapTest.kt` | 读 `state.value.gapInfo` / `state.value.messages` | 改为读 `chatState.value.messages` |
| `AppStateTest.kt` | 测试 AppState 派生属性（contextUsage, visibleMessages 等） | 如果 AppState 退化为派生快照，测试改为直接测试计算逻辑；如果是纯函数测试，可能保持不变 |

**最小改动原则**：Milestone 1-2 期间 AppState 仍存在（仅 deprecated），`updateState()` 反射 helper 保持工作，新测试逐步迁移到暴露的 setter 方法。Milestone 5 一次性迁移所有剩余测试。

---

## 8. 与 R-16 的依赖关系

### 8.1 分析

| 维度 | R-16（MainViewModel 拆分） | R-17（AppState 拆分） |
|---|---|---|
| 改动目标 | ViewModel 的**方法**（职责）拆分 | ViewModel 的**状态**（数据）拆分 |
| 物理耦合 | 拆分出的新类（SessionSyncCoordinator 等）需要访问 `_state` | 拆分出的新 Flow 需要被新类读写 |
| 测试耦合 | 反射访问 `_state` → 改为反射/暴露测试方法 | 反射访问 `_state` → 改为访问各 Flow |

### 8.2 顺序建议

**R-17 优先于 R-16**。理由：

1. **R-17 的 Milestone 1 是零行为变更的纯消费侧优化**，不涉及 R-16 的任何改动
2. R-16 拆分出新类（`SessionSyncCoordinator` 等）后，这些类仍需写状态——如果先拆 VM 再拆 State，新类的接口需要二次改造（先接受 `MutableStateFlow<AppState>` → 再改为多 Flow）
3. R-17 的拆 Flow 先落地后，R-16 拆分出的类直接接受/返回各自需要的 Flow（如 `SessionSyncCoordinator` 接收 `_chatFlow` + `_sessionListFlow`），职责更清晰
4. 测试改造：R-17 先拆后，测试访问模式从 `viewModel.state.value.sessions` 变为 `viewModel.chatState.value.messages`，R-16 拆分出新类后测试按新接口访问即可——**避免"先改 VM 接口再改 State 再改测试"的三次折腾**

### 8.3 并行可能性

R-17 Milestone 1-2 与 R-16 的 **设计阶段**（确定拆分哪些类/接口定义）可并行。但 R-16 的**实现阶段**应等 R-17 Milestone 3 完成（composerFlow + chatFlow 已拆分，R-16 拆分出的新类可直接按新 Flow 接口设计）。

**推荐排期**：

```
Week 1-2: R-17 M1 (纯 Compose 消费侧优化) + R-16 设计方案评审
Week 3-4: R-17 M2-M3 (connection/composer/file/settings 拆分) 
Week 5-6: R-16 实现 (基于 R-17 M3 的新 Flow 接口)
Week 7-8: R-17 M4-M5 (chat/sessionList/unread/host 拆分) + R-16 测试补齐
```

---

## 9. 风险与缓解

### 9.1 可观测性下降

**风险**：拆 Flow 后不再有单一 `AppState` 快照，调试时需同时查看 7+ 个 StateFlow 的值，排查"状态不一致"类 Bug 更困难。

**缓解**：
1. **调试 logger**：新增 `DebugAppStateSnapshot` helper，在 `MainViewModel` 内用 `combine(connectionFlow, chatFlow, sessionListFlow, composerFlow, settingsFlow, fileFlow, hostFlow, unreadFlow, trafficFlow) { ... }` 生成 `AppStateSnapshot`，`DebugLog.d("AppState", snapshot)` 输出到 ring buffer（`DebugLog.entries`），SettingsScreen 的 Debug 页可查看最新快照
2. **仅在 debug build 启用**：`@RequiresOptIn` + `BuildConfig.DEBUG` guard
3. **Layout Inspector**：Compose 的 Layout Inspector 可以查看每行的 State 值——各 Flow 的分散可视化比一个大 object 更易读
4. **保留 AppState 快照 API**（Milestone 5 不删除，退化为 `combine` 派生的 `StateFlow<AppStateSnapshot>`，仅供调试）

### 9.2 跨 Flow 竞态

**风险**：`selectHostProfile()` 等场景的顺序赋值中，如果 Compose 在中间态重组读到了不完整的局部状态（如 `chatState.currentSessionId=null` 但 `sessionListState.openSessionIds` 仍指向旧 session），UI 可能短暂异常。

**缓解**：
1. 每个中间态都是合法状态（空列表/无聊天——见 §4.2）
2. 如果某些场景确实出现 UI 闪烁，用 `viewModelScope.launch { ... }` 保证顺序赋值在同一帧内完成（Dispatchers.Main.immediate 的 batch 行为）
3. 必要时引入 "transition token"：在开始批量更新前设置 `_transitioning.value = true`，消费方在 `transitioning==true` 时不渲染（不推荐，过度工程；仅作为后备方案记录）

### 9.3 对外接口膨胀

**风险**：MainViewModel 从暴露 1 个 `state: StateFlow<AppState>` 变为暴露 9 个 `xxxState: StateFlow<XxxState>`，接口表面积增大。

**缓解**：
- 各 Screen 只订阅需要的 Flow（非全量），实际耦合度反而降低
- 如果接口数量确实造成代码噪音，可考虑将多个 Flow 分组为 `ViewModelState` 包装对象（含 `connection`, `chat`, `sessionList` 等属性），但不改变底层 `MutableStateFlow` 的独立性——纯 syntactic sugar，按需引入

### 9.4 性能回归

**风险**：`combine` 生成派生 Flow（如调试快照）可能引入额外的协程/状态管理开销。

**缓解**：
- 调试快照仅在 debug build 启用
- 每个 `StateFlow` 仍是 `MutableStateFlow`（非 `combine` 产生），写入路径零额外开销
- Milestone 1 完成后做 Layout Inspector + systrace 基准测量

---

## 10. 附录：当前 `_state.update` 调用分布

（从 `MainViewModel.kt` + `MainViewModelSyncActions.kt` + `MainViewModelSessionActions.kt` grep 统计，供迁移规划参考）

| 文件 | `_state.update` 调用数 | 主要作用 |
|---|---|---|
| `MainViewModel.kt` | ~55 | 连接管理、session 选择、发送消息、UI 操作 |
| `MainViewModelSessionActions.kt` | ~15 | `selectSessionState`, `loadSessions`, `loadMessages`, `loadMoreMessages` |
| `MainViewModelSyncActions.kt` | ~15 | SSE 事件处理（session.created, message.updated, streaming delta, todos, unread） |
| `MainViewModelConnectionActions.kt` | ~3 | `checkHealth`, `loadProviders` |
| **合计** | **~90** | |

---

## 11. 修订记录

| 版本 | 日期 | 修订内容 |
|---|---|---|
| v1.0 | 2026-06-30 | 初稿：完整分组蓝图、派生决策、原子性分析、迁移路径、测试改造方案 |
