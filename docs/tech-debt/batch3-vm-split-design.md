# R-17 批次 3：MainViewModel → 6 领域 ViewModel 架构设计

> **纯分析文档**：本文件描述拆分设计，不做任何 code change。
> 依赖批次 2（AppState mirror 退役）完成后执行。
> 决策来源：用户 grilling 确认方案 (C) 混合：4-5 领域 VM + 1 薄 OrchestratorVM。

---

## 目录

1. [方法域映射](#1-方法域映射)
2. [Controller → VM 归属](#2-controller--vm-归属)
3. [Callback → Effect 迁移](#3-callback--effect-迁移)
4. [Composable 入口注入变更](#4-composable-入口注入变更)
5. [跨域编排方法的归宿](#5-跨域编排方法的归宿)
6. [Hilt DI 变更](#6-hilt-di-变更)
7. [测试拆分策略](#7-测试拆分策略)

---

## 1. 方法域映射

当前 `MainViewModel.kt`（2001 行）约 63 个 public/internal 方法。按 6 个 ViewModel 归域如下。

### 1.1 ChatVM（`ChatViewModel`）

> 职责：消息窗口加载/分页/间隙闭合、发送/编辑/压缩/中止、流式渲染状态（`streamingPartTexts`/`streamingReasoningPart`、`expandedParts`）。
> 拥有的 Slice：`_chatFlow`、`_expandedParts`（移到 ChatVM 内部）。

| # | 方法 | 当前行号 | 跨域？ | 依赖 Controller | 依赖 Slice |
|---|------|----------|--------|-----------------|-----------|
| 1 | `sendMessage()` | 1332–1418 | **是** → Orchestrator | composerFlow 读（inputText/attachments）、引用 `selectSession`/`createSession` | chat, composer, sessionList, unread |
| 2 | `abortSession()` | 1549–1557 | 否 | — | chat (currentSessionId) |
| 3 | `compactSession()` | 875–894 | 否 | — | chat (currentSessionId, isCompacting, currentModel) |
| 4 | `clearCompacting()` | 898–902 | 否 | — | chat (isCompacting, compactStartedAt) |
| 5 | `editFromMessage(messageId)` | 1574–1605 | 否（仅调 `loadSessions`-like 刷新） | — | chat, composer, sessionList |
| 6 | `togglePartExpand(key, currentValue)` | 393–395 | 否 | ComposerController | expandedParts |
| 7 | `loadMessages(sessionId, resetLimit)` | 1052–1065 | 否 | — | state, settingsFlow, sliceFlows |
| 8 | `loadMessages(sessionId)` | 1072 | 否（重载） | — | — |
| 9 | `loadMoreMessages()` | 1079–1089 | 否 | — | chat (currentSessionId), state, sliceFlows |
| 10 | `closeGap()` | 1185–1195 | 否 | — | chat (currentSessionId), state, sliceFlows |
| 11 | `refreshCurrentSession()` | 1110–1125 | 否 | — | chat (currentSessionId), connectionFlow |

**跨域说明**：`sendMessage()` 横跨 composer→chat→sessionList 三个域。批次 3 暂保留在 ChatVM，通过 OrchestratorVM 协调（见 §5.1）。未来批次 4 可进一步提取 `SendMessageCoordinator`。

### 1.2 SessionVM（`SessionViewModel`）

> 职责：会话列表 CRUD + 切换导航 + open-tab 管理 + 草稿模式（`createSessionInWorkdir`）。
> 拥有的 Slice：`_sessionListFlow`、`_unreadFlow`。

| # | 方法 | 当前行号 | 跨域？ | 依赖 Controller | 依赖 Slice |
|---|------|----------|--------|-----------------|-----------|
| 1 | `selectSession(sessionId)` | 945–947 | **是**（调用 chat 域的 loadMessages/loadSessionStatus/loadChildSessions） | SessionSwitcher | chat, composer, sessionList, unread, expandedParts |
| 2 | `openSubAgent(childSessionId)` | 987–1008 | 否 | — | chat, sessionList |
| 3 | `closeSession(sessionId)` | 1018–1050 | 否 | — | chat, composer, sessionList, unread |
| 4 | `loadMoreSessions()` | 919–928 | 否 | — | state, sessionList |
| 5 | `createSession(title?)` | 1226–1228 | 否（调 selectSession） | — | state, sessionList |
| 6 | `createSessionInWorkdir(workdir)` | 1237–1297 | 否 | — | chat, composer, sessionList, state |
| 7 | `forkSession(sessionId, messageId?)` | 1316–1318 | 否 | — | state, sessionList |
| 8 | `archiveSession(sessionId)` | 1320–1322 | 否 | — | state, sessionList |
| 9 | `restoreSession(sessionId)` | 1324–1326 | 否 | — | state, sessionList |
| 10 | `deleteSession(sessionId)` | 1328–1330 | 否 | — | state, sessionList |
| 11 | `refreshDirectorySessions(workdir)` | 1933–1942 | 否 | — | state, sessionList |
| 12 | `toggleSessionExpanded(sessionId)` | 1665–1674 | 否 | — | sessionList |
| 13 | `openSessionFromDeepLink(sessionId)` | 670–684 | **是**（跨 sessionList+chat）→ Orchestrator | — | sessionList, chat |

### 1.3 ConnectionVM（`ConnectionViewModel`）

> 职责：服务器连接生命周期（health probe + SSE 起停 + coldStartReconnect + 初始数据加载编排）。
> 拥有的 Slice：`_connectionFlow`。

| # | 方法 | 当前行号 | 跨域？ | 依赖 Controller | 依赖 Slice |
|---|------|----------|--------|-----------------|-----------|
| 1 | `testConnection(force, retries, onSettled)` | 749–751 | **是**（成功后 loadInitialData + startSSE → 触发跨域加载） | ConnectionCoordinator | connectionFlow |
| 2 | `testConnectionForm(...)` | 776–801 | 否 | — | — |
| 3 | `executeCommand(command, arguments)` | 830–862 | **是**（/clear → createSession/createSessionInWorkdir）→ Orchestrator | — | chat, composer, sessionList |
| 4 | `startSSE()` | 1947 | 否 | ConnectionCoordinator | — |
| 5 | `cancelSse()` | 488–492 | 否 | ConnectionCoordinator | — |
| 6 | `coldStartReconnect()` | 810 | 否 | ConnectionCoordinator | — |
| 7 | `loadInitialData()` | 815 | **是**（触发 loadSessions/loadAgents/loadProviders） | ConnectionCoordinator | — |

**注意**：`executeCommand` 的 `/clear` 分支需要访问 `createSessionInWorkdir`（SessionVM）和 `createSession`（SessionVM）。标记为跨域，走 OrchestratorVM。

**注意**：`loadInitialData` → `callbacks.loadSessions()` 等回调触发 SessionVM/ConnectionVM 的方法。批次 3 中 ConnectionCoordinator 回调仍在 VM 层实现（见 §3.2）。

### 1.4 HostVM（`HostViewModel`）

> 职责：Host Profile CRUD + 服务端配置 + 隧道激活。
> 拥有的 Slice：`_hostFlow`。

| # | 方法 | 当前行号 | 跨域？ | 依赖 Controller | 依赖 Slice |
|---|------|----------|--------|-----------------|-----------|
| 1 | `saveHostProfile(profile, ...)` | 707–715 | **是**（toggleChanged/urlChanged → forceReconnect + onHostProfileSwitched） | HostProfileController | host |
| 2 | `selectHostProfile(profileId)` | 717–724 | **是**（SSE 重连、purge）（注：onHostProfileSwitched 在 Controller 内 callback 回） | HostProfileController | host |
| 3 | `duplicateHostProfile(profileId)` | 726–728 | 否 | HostProfileController | host |
| 4 | `deleteHostProfile(profileId)` | 730–732 | **是**（wasCurrent → purge + forceReconnect） | HostProfileController | host |
| 5 | `importHostProfile(payload)` | 734–735 | 否 | HostProfileController | host |
| 6 | `exportHostProfile(profile)` | 737–738 | 否 | HostProfileController | — |
| 7 | `getHostProfiles()` | 703 | 否 | HostProfileController | — |
| 8 | `currentHostProfile()` | 705 | 否 | HostProfileController | — |
| 9 | `configureServer(url, username?, password?)` | 698–700 | **是**（cancelSseForReconfigure → Connection） | HostProfileController | host |
| 10 | `activateTunnelForCurrentHost()` | 1848–1850 | **是**（成功后 coldStartReconnect → Connection） | HostProfileController | connection, host |
| 11 | `getSavedConnectionSettings()` | 740–741 | 否 | HostProfileController | — |
| 12 | `resetLocalDataAndResync()` | 1843–1845 | **是**（全栈重置）→ Orchestrator | HostProfileController | 全部 |

### 1.5 ComposerVM（`ComposerViewModel`）

> 职责：输入文本/图片附件/草稿/模型选择/Agent 选择。
> 拥有的 Slice：`_composerFlow`。

| # | 方法 | 当前行号 | 跨域？ | 依赖 Controller | 依赖 Slice |
|---|------|----------|--------|-----------------|-----------|
| 1 | `setInputText(text)` | 1560–1562 | 否 | ComposerController | composer, chat (for sessionId→draft save) |
| 2 | `addImageAttachments(attachments)` | 1565–1567 | 否 | ComposerController | composer |
| 3 | `removeImageAttachment(id)` | 1570–1572 | 否 | ComposerController | composer |
| 4 | `clearDraftIfActive()` | 1312–1313 | 否 | ComposerController | composer, chat |
| 5 | `selectAgent(agentName)` | 1607–1611 | 否 | — | settings, chat (for per-session persistence) |
| 6 | `toggleModelDisabled(providerId, modelId)` | 1619–1629 | 否 | — | settings, hostProfileStore |
| 7 | `switchSessionModel(providerId, modelId)` | 1653–1663 | 否 | — | chat, settings |
| 8 | `reloadDisabledModelsForCurrentHost()` | 1637–1641 | 否 | — | settings, hostProfileStore |

### 1.6 OrchestratorVM（`OrchestratorViewModel`）

> 职责：跨域编排 + 导航状态 + 设置 UI 偏好 + permission/question 响应 + 文件浏览器 + 流量统计 + deep link。
> 拥有的 Slice：`_navFlow`、`_fileFlow`、`_settingsFlow`、`_trafficFlow`、`_uiEvents`（SharedFlow）。

| # | 方法 | 当前行号 | 跨域？ | 依赖 Controller | 依赖 Slice |
|---|------|----------|--------|-----------------|-----------|
| 1 | `resetLocalDataAndResync()` | 1843–1845 | **是**（全栈）→ 委托给各 VM | HostProfileController | 全部 |
| 2 | `setLastNavPage(page)` | 691–696 | 否 | — | nav |
| 3 | `setThemeMode(mode)` | 1676–1679 | 否 | — | settings |
| 4 | `setMarkdownFontSizes(sizes)` | 1681–1684 | 否 | — | settings |
| 5 | `setUiFontScale(scale)` | 1693–1700 | 否 | — | settings |
| 6 | `setUiContentScale(scale)` | 1708–1712 | 否 | — | settings |
| 7 | `respondPermission(sessionId, permissionId, response)` | 1714–1726 | 否 | — | sessionList |
| 8 | `replyQuestion(requestId, answers, onError)` | 1770–1783 | 否 | — | sessionList |
| 9 | `rejectQuestion(requestId)` | 1785–1797 | 否 | — | sessionList |
| 10 | `showFileInFiles(path, originRoute?)` | 1852–1854 | 否 | — | file |
| 11 | `clearFileToShow()` | 1856–1858 | 否 | — | file |
| 12 | `browseFilesInWorkdir(workdir)` | 1878–1898 | 否 | — | file |
| 13 | `closeFileBrowser()` | 1903–1917 | 否 | — | file |
| 14 | `refreshTrafficStats()` | 1808–1815 | 否 | — | traffic |
| 15 | `resetTrafficStats()` | 1818–1821 | 否 | — | traffic |
| 16 | `openSessionFromDeepLink(sessionId)` | 670–684 | **是**（sessionList→chat）→ 委托给 SessionVM | — | sessionList, chat |
| 17 | `executeCommand(command, arguments)` | 830–862 | **是**（composer→sessionList→chat→Connection）→ 委托给 ConnectionVM+ | — | 全部 |

**OrchestratorVM 的特殊定位**：
- 不作为 Controller 所有者（ForegroundCatchUpController 的 `owner`）
- 持有 `_uiEvents` 发布
- 实现 `ForegroundCatchUpCallbacks`（因为这些回调横跨 Connection/Session/Chat/Composer 四个域）
- 持有各领域 VM 的引用（通过 Hilt DI），协调跨域操作

---

## 2. Controller → VM 归属

### 2.1 归属表

| Controller | 归哪个 VM | 理由 |
|-----------|-----------|------|
| `ComposerController` | **ComposerVM** | 纯 composer 域（inputText/imageAttachments/draft/togglePartExpand） |
| `SessionSwitcher` | **SessionVM** | 纯 session 切换域（8-step switch flow + LRU message cache） |
| `ConnectionCoordinator` | **ConnectionVM** | 纯连接域（health probe/SSE/initial-data orchestration） |
| `HostProfileController` | **HostVM** | 纯 host 域（CRUD/reconfigure/tunnel/reset） |
| `SessionSyncCoordinator` | **ChatVM** | SSE 事件 fold 到 chat/sessionList 状态（消息/部分/状态/权限/问题/todo） |
| `ForegroundCatchUpController** | **OrchestratorVM** | 跨域生命周期回调（forceReconnect/catchUp/clearDraft/cancelSse/globalColdStartRefresh） |

### 2.2 归属理由详解

**SessionSyncCoordinator → ChatVM**：
- SSE 事件（`message.part.updated/delta`、`session.status`、`message.updated`）的主要产出是 `chatFlow`（`streamingPartTexts`、`streamingReasoningPart`、`messages`、`partsByMessage`）和 `sessionListFlow`（`sessions`、`sessionStatuses`、`pendingPermissions`、`pendingQuestions`）
- Chat 是 SSE 事件流的主要消费域；SessionSyncCoordinator 本质上是"SSE→chat/sessionList 的 fold 引擎"
- 切到 ChatVM 后，SessionSyncCoordinator 的回调（`onRefreshMessages`、`onRefreshSessions`、`onLoadPendingPermissions`、`onServerConnected`）天然落在 ChatVM 上

**ForegroundCatchUpController → OrchestratorVM**：
- 它的 7 个回调 (`ForegroundCatchUpCallbacks`) 横跨 4 个域：
  - `forceReconnect()` → ConnectionVM
  - `globalColdStartRefresh(currentId)` → ChatVM + SessionVM
  - `setStaleNotice()` → ChatVM
  - `clearDraft()` → ComposerVM
  - `cancelSse()` → ConnectionVM
  - `catchUpAfterDisconnect(sessionId)` → ChatVM
  - `currentSessionId()` → ChatVM
- 只有 OrchestratorVM 持有所有领域 VM 的引用，才能调度这些跨域回调

### 2.3 Controller 构造器变更

当前所有 Controller 的构造器都注入 `state: MutableStateFlow<AppState>` + `slices: SliceFlows`（用于写已弃用的 AppState mirror）。批次 2 退役 mirror 后，Controller 可以直接持有领域 Slice：

| Controller | 批次 3 后构造器 |
|-----------|----------------|
| `ComposerController` | `composerFlow: MutableStateFlow<ComposerState>`, `chatFlow: StateFlow<ChatState>`, `expandedParts: MutableStateFlow<...>`, `callbacks: ComposerCallbacks` |
| `SessionSwitcher` | `chatFlow`, `composerFlow`, `sessionListFlow`, `unreadFlow`, `expandedParts`, `callbacks: SessionSwitcherCallbacks` |
| `ConnectionCoordinator` | `connectionFlow`, `settingsFlow`, `sessionListFlow`, `scope`, `repository`, `settingsManager`, `serverCompatProfile`, `callbacks`, `eventEmitter` |
| `HostProfileController` | `hostFlow`, `connectionFlow` (读), `scope`, `hostProfileStore`, `repository`, `settingsManager`, `callbacks`, `eventEmitter` |
| `SessionSyncCoordinator** | `chatFlow`, `sessionListFlow`, `settingsManager`, `callbacks` |
| `ForegroundCatchUpController** | `appLifecycleMonitor`, `scope`, `callbacks` |

---

## 3. Callback → Effect 迁移

### 3.1 现有 Callback 接口总览

#### ForegroundCatchUpCallbacks（→ OrchestratorVM）

```kotlin
interface ForegroundCatchUpCallbacks {
    fun forceReconnect()                          // → Effect.ForceReconnect
    fun globalColdStartRefresh(currentId: String) // → Effect.GlobalColdStartRefresh(sessionId)
    fun setStaleNotice()                          // 可消除：VM 直接写 chatFlow
    fun clearDraft()                              // 可消除：VM 直接调 ComposerVM
    fun cancelSse()                               // → Effect.CancelSse
    fun catchUpAfterDisconnect(sessionId: String) // → Effect.CatchUpAfterDisconnect(sessionId)
    fun currentSessionId(): String?               // 可消除：Controller 直接读 chatFlow
}
```

#### ComposerCallbacks（→ ComposerVM）

```kotlin
interface ComposerCallbacks {
    fun saveDraft(sessionId: String, text: String)  // 可消除：VM 直接写 SettingsManager
    fun clearPersistedWorkdir()                      // 可消除：VM 直接写 SettingsManager
}
```

#### SessionSwitcherCallbacks（→ SessionVM）

```kotlin
interface SessionSwitcherCallbacks {
    fun saveDraft(sessionId: String, text: String)      // 可消除
    fun getDraft(sessionId: String): String              // 可消除
    fun setCurrentSessionId(sessionId: String?)          // 可消除
    fun setOpenSessionIds(ids: List<String>)             // 可消除
    fun persistSessionCache(...)                         // 可消除
    fun syncCurrentDirectory(directory: String?)         // 可消除
    fun loadChildSessions(sessionId: String)             // → Effect.LoadChildSessions(sessionId)
    fun loadMessages(sessionId: String, resetLimit: Boolean) // → Effect.LoadMessages(sessionId, resetLimit)
    fun loadSessionStatus()                              // → Effect.LoadSessionStatus
    fun loadPendingQuestions()                           // → Effect.LoadPendingQuestions
    fun onClearDeltaBuffers()                            // 可消除：VM 直接调 SessionSyncCoordinator
}
```

#### HostProfileCallbacks（→ HostVM）

```kotlin
interface HostProfileCallbacks {
    fun cancelSseForReconfigure()  // → Effect.CancelSseForReconfigure
    fun startSSE()                 // → Effect.StartSse
    fun forceReconnect()           // → Effect.ForceReconnect
    fun coldStartReconnect()       // 可消除：VM 直接调 ConnectionCoordinator
    fun loadInitialData()          // 可消除：VM 直接调 ConnectionCoordinator
    fun clearSessionWindowCache()  // 可消除：VM 直接调 SessionSwitcher
    fun resetTrafficTracker()      // 可消除：VM 直接写 trafficFlow
    fun onHostProfileSwitched()    // → Effect.HostProfileSwitched
}
```

#### ConnectionCoordinatorCallbacks（→ ConnectionVM）

```kotlin
interface ConnectionCoordinatorCallbacks {
    fun configureRepositoryForCurrentProfile() // 可消除：VM 直接调 HostProfileController
    fun loadSessions()                         // 可消除：VM 直接调 SessionVM
    fun loadAgents()                           // 可消除：VM 直接调 SettingsManager
    fun loadProviders()                        // 可消除：VM 直接调 SettingsManager
    fun loadPendingQuestions()                 // 可消除：VM 直接调 repository
    fun loadPendingPermissions()               // 可消除：VM 直接调 repository
    fun onSseEvent(event: SSEEvent)            // 可消除：VM 直接调 SessionSyncCoordinator
    fun onHostReconfigured()                   // → Effect.HostReconfigured
}
```

#### SessionSyncCoordinatorCallbacks（→ ChatVM）

```kotlin
interface SessionSyncCoordinatorCallbacks {
    fun onServerConnected()                                    // → Effect.ServerConnected
    fun onRefreshMessages(sessionId: String, resetLimit: Boolean)  // 可消除：ChatVM 直接调 loadMessages
    fun onRefreshSessions()                                     // → Effect.RefreshSessions (去 SessionVM)
    fun onLoadPendingPermissions()                              // 可消除：ChatVM 直接调 loadPendingPermissions
    fun onNonFatalIssue(message: String)                        // 可消除：ChatVM 直接调 reportNonFatalIssue
}
```

### 3.2 迁移决策

#### 规则 A：VM 直接拥有 Controller 后可消除的回调

当 Controller 的生命周期与 VM 绑定（`viewModelScope`），且 VM 拥有相关资源（SettingsManager、repository）时，原先通过 callback interface 间接调用的方法改为 VM 直接操作：

```kotlin
// Before (callback pattern):
callbacks.saveDraft(sessionId, text)

// After (VM owns controller + dependencies directly):
settingsManager.setDraftText(sessionId, text)
```

**可消除的 callback 方法**（约 20 个）：
- `ComposerCallbacks`：`saveDraft`, `clearPersistedWorkdir` （2/2）
- `SessionSwitcherCallbacks`：`saveDraft`, `getDraft`, `setCurrentSessionId`, `setOpenSessionIds`, `persistSessionCache`, `syncCurrentDirectory` （6/11）
- `HostProfileCallbacks`：`coldStartReconnect`, `loadInitialData`, `clearSessionWindowCache`, `resetTrafficTracker` （4/8）
- `ConnectionCoordinatorCallbacks`：`configureRepositoryForCurrentProfile`, `loadSessions`, `loadAgents`, `loadProviders`, `loadPendingQuestions`, `loadPendingPermissions`, `onSseEvent` （7/8）
- `SessionSyncCoordinatorCallbacks`：`onRefreshMessages`, `onLoadPendingPermissions`, `onNonFatalIssue` （3/5）
- `ForegroundCatchUpCallbacks`：`setStaleNotice`, `clearDraft`, `currentSessionId` （3/7）

**总计可消除**：约 25/41 callback 方法。

#### 规则 B：跨域回调变为 `SharedFlow<Effect>`

跨域回调（callback 的发送方和响应方不在同一 VM）改为 sealed class `ControllerEffect`，通过 `SharedFlow` 广播：

```kotlin
// 由 OrchestratorVM 的 _uiEvents 风格扩展为独立 channel
sealed class ControllerEffect {
    // ForegroundCatchUpController → OrchestratorVM → 各领域 VM
    data class ForceReconnect(/* empty */) : ControllerEffect()
    data class GlobalColdStartRefresh(val sessionId: String) : ControllerEffect()
    data object CancelSse : ControllerEffect()
    data class CatchUpAfterDisconnect(val sessionId: String) : ControllerEffect()

    // SessionSwitcher → ChatVM
    data class LoadMessages(val sessionId: String, val resetLimit: Boolean) : ControllerEffect()
    data class LoadChildSessions(val sessionId: String) : ControllerEffect()
    data object LoadSessionStatus : ControllerEffect()
    data object LoadPendingQuestions : ControllerEffect()
    data object ClearDeltaBuffers : ControllerEffect()

    // HostProfileController → ConnectionVM / OrchestratorVM
    data object CancelSseForReconfigure : ControllerEffect()
    data object StartSse : ControllerEffect()
    data object HostProfileSwitched : ControllerEffect()

    // ConnectionCoordinator → ForegroundCatchUpController
    data object HostReconfigured : ControllerEffect()

    // SessionSyncCoordinator → ForegroundCatchUpController / SessionVM
    data object ServerConnected : ControllerEffect()
    data object RefreshSessions : ControllerEffect()
}
```

**迁移为 Effect 的方法**（约 16 个）：
- `ForegroundCatchUpCallbacks`：`forceReconnect`, `globalColdStartRefresh`, `cancelSse`, `catchUpAfterDisconnect` （4/7）
- `SessionSwitcherCallbacks`：`loadChildSessions`, `loadMessages`, `loadSessionStatus`, `loadPendingQuestions`, `onClearDeltaBuffers` （5/11）
- `HostProfileCallbacks`：`cancelSseForReconfigure`, `startSse`, `forceReconnect`, `onHostProfileSwitched` （4/8）
- `ConnectionCoordinatorCallbacks`：`onHostReconfigured` （1/8）
- `SessionSyncCoordinatorCallbacks`：`onServerConnected`, `onRefreshSessions` （2/5）

### 3.3 Controller 内的 Effect 发送

每个 Controller 新增一个 `MutableSharedFlow<ControllerEffect>` 参数（`extraBufferCapacity = 8`），替换原 `callbacks: XxxCallbacks`：

```kotlin
// Before
class ForegroundCatchUpController(
    ...,
    private val callbacks: ForegroundCatchUpCallbacks
)

// After
class ForegroundCatchUpController(
    ...,
    private val effects: MutableSharedFlow<ControllerEffect>
)
```

Controller 内部发送 effect：
```kotlin
// Before
callbacks.forceReconnect()

// After
effects.tryEmit(ControllerEffect.ForceReconnect)
```

### 3.4 接收端收集

**OrchestratorVM** 持有 `SharedFlow<ControllerEffect>` 并分发：

```kotlin
@HiltViewModel
class OrchestratorViewModel @Inject constructor(
    private val chatVM: ChatViewModel,
    private val sessionVM: SessionViewModel,
    private val connectionVM: ConnectionViewModel,
    ...
) : ViewModel() {
    val controllerEffects = MutableSharedFlow<ControllerEffect>(extraBufferCapacity = 8)
    
    init {
        viewModelScope.launch {
            controllerEffects.collect { effect ->
                when (effect) {
                    is ControllerEffect.ForceReconnect -> connectionVM.testConnection(force = true)
                    is ControllerEffect.GlobalColdStartRefresh -> chatVM.performGlobalColdStartRefresh(effect.sessionId)
                    is ControllerEffect.CancelSse -> connectionVM.cancelSse()
                    is ControllerEffect.CancelSseForReconfigure -> connectionVM.cancelSseForReconfigure()
                    is ControllerEffect.StartSse -> connectionVM.startSSE()
                    is ControllerEffect.ServerConnected -> foregroundCatchUpController.onServerConnected()
                    is ControllerEffect.HostReconfigured -> foregroundCatchUpController.onHostReconfigured()
                    is ControllerEffect.LoadMessages -> chatVM.loadMessages(effect.sessionId, effect.resetLimit)
                    is ControllerEffect.RefreshSessions -> sessionVM.loadSessions()
                    // ... etc
                }
            }
        }
    }
}
```

### 3.5 不消除的回调（保留 interface）

仅 `SessionSwitcherCallbacks.loadMessages` 等少数跨域回调保留为 Effect。大部分可消除的直接被 VM 持有。

**最终 Controller 持有的依赖**：`MutableSharedFlow<ControllerEffect>` 替代 `callbacks`，Slice MutableStateFlow 直接注入（批次 2 后不再需要 `AppState`）。

---

## 4. Composable 入口注入变更

### 4.1 当前状态

所有 8 个 Composable 入口接收单一 `viewModel: MainViewModel`：

| 入口 | 当前参数 | 消费的 Slice |
|------|---------|-------------|
| `MainActivity` | `mainViewModel: MainViewModel?` | coldStartReconnect, openSessionFromDeepLink |
| `PhoneLayout` | `viewModel: MainViewModel` | fileFlow, nav（setLastNavPage/clearDraftIfActive/closeFileBrowser） |
| `ChatScreen` | `viewModel: MainViewModel` | connection, traffic, composer, file, settings, chat, sessionList, unread, host, uiEvents, expandedParts |
| `ChatInputBar` | `viewModel: MainViewModel` | composer, settings |
| `ChatMessageList` | `viewModel: MainViewModel` | chat, sessionList, expandedParts |
| `SessionsScreen` | `viewModel: MainViewModel` | sessionList, chat, composer (via clearDraftIfActive), file (browseFilesInWorkdir) |
| `SettingsScreen` | `viewModel: MainViewModel` | connection, traffic, settings, host |
| `HostProfilesManagerScreen` | `viewModel: MainViewModel` | host (profiles, currentProfileId) |

### 4.2 批次 3 后设计

每个 Composable 注入所需的领域 VM（零个到多个），不再持有 `MainViewModel`：

#### ChatScreen / ChatInputBar / ChatMessageList

```kotlin
@Composable
fun ChatScreen(
    chatVM: ChatViewModel = hiltViewModel(),
    composerVM: ComposerViewModel = hiltViewModel(),
    connectionVM: ConnectionViewModel = hiltViewModel(),
    orchestratorVM: OrchestratorViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSessions: () -> Unit = {}
) {
    val chat by chatVM.chatFlow.collectAsStateWithLifecycle()
    val composer by composerVM.composerFlow.collectAsStateWithLifecycle()
    // ... etc
}
```

```kotlin
@Composable
internal fun ChatInputBar(
    composerVM: ComposerViewModel = hiltViewModel(),
    chatVM: ChatViewModel = hiltViewModel(),
    // ...
) {
    val composerState by composerVM.composerFlow.collectAsStateWithLifecycle()
    // ...
}
```

```kotlin
@Composable
internal fun ChatMessageList(
    chatVM: ChatViewModel = hiltViewModel(),
    sessionVM: SessionViewModel = hiltViewModel(),
    // ...
) {
    val chatState by chatVM.chatFlow.collectAsStateWithLifecycle()
    val sessionListState by sessionVM.sessionListFlow.collectAsStateWithLifecycle()
    val expandedParts by chatVM.expandedParts.collectAsStateWithLifecycle()
    // ...
}
```

#### SessionsScreen

```kotlin
@Composable
fun SessionsScreen(
    sessionVM: SessionViewModel = hiltViewModel(),
    chatVM: ChatViewModel = hiltViewModel(),
    composerVM: ComposerViewModel = hiltViewModel(),
    orchestratorVM: OrchestratorViewModel = hiltViewModel(), // for browseFilesInWorkdir/file
    onSwitchToChat: () -> Unit = {},
) {
    val sessionList by sessionVM.sessionListFlow.collectAsStateWithLifecycle()
    val chat by chatVM.chatFlow.collectAsStateWithLifecycle()
    // ...
}
```

#### SettingsScreen / HostProfilesManagerScreen

```kotlin
@Composable
fun SettingsScreen(
    hostVM: HostViewModel = hiltViewModel(),
    connectionVM: ConnectionViewModel = hiltViewModel(),
    orchestratorVM: OrchestratorViewModel = hiltViewModel(), // for traffic/reset/settings UI prefs
    onBack: (() -> Unit)? = null
) {
    val host by hostVM.hostFlow.collectAsStateWithLifecycle()
    val connection by connectionVM.connectionFlow.collectAsStateWithLifecycle()
    val traffic by orchestratorVM.trafficFlow.collectAsStateWithLifecycle()
    val settings by orchestratorVM.settingsFlow.collectAsStateWithLifecycle()
    // ...
}
```

#### PhoneLayout / MainActivity

```kotlin
@Composable
private fun PhoneLayout(
    sessionVM: SessionViewModel = hiltViewModel(),
    chatVM: ChatViewModel = hiltViewModel(),
    composerVM: ComposerViewModel = hiltViewModel(),
    connectionVM: ConnectionViewModel = hiltViewModel(),
    hostVM: HostViewModel = hiltViewModel(),
    orchestratorVM: OrchestratorViewModel = hiltViewModel(),
    initialPage: Int = 0
) {
    val file by orchestratorVM.fileFlow.collectAsStateWithLifecycle()
    // navigation + clearDraftIfActive → call composerVM / orchestratorVM
    // closeFileBrowser → call orchestratorVM
}
```

**MainActivity** 本身保留一个 `OrchestratorViewModel` 引用用于 `openSessionFromDeepLink` 和 `coldStartReconnect`：

```kotlin
class MainActivity : AppCompatActivity() {
    private var orchestratorVM: OrchestratorViewModel? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        setContent {
            val ovm: OrchestratorViewModel = hiltViewModel()
            orchestratorVM = ovm
            ovm.coldStartReconnect()
            // ...
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        // ...
        orchestratorVM?.openSessionFromDeepLink(sessionId)
    }
}
```

### 4.3 重要规则

- **每个 VM 在 Composable 树中都是 `hiltViewModel()`** — 生命周期 scoped 到 Navigation 目的地（NavBackStackEntry）。由于 PhoneLayout 自身不在 NavHost 中（当前架构无 NavHost），所有 6 个 VM 的 scope 落在同一个 Activity/Fragment，生命周期等价。
- **Composable 按需注入**：只 import 需要的 VM，无需全量。
- **StateFlow collect 继续使用 `collectAsStateWithLifecycle()`**。

---

## 5. 跨域编排方法的归宿

有约 8 个方法涉及跨域协调。下面按方法设计编排路径。

### 5.1 `sendMessage()` — ChatVM（调 OrchestratorVM → SessionVM）

`sendMessage` 的核心逻辑属于 chat 域（`dispatchSendMessage`），但有两个跨域分支：
- 草稿物化路径（`draftWorkdir != null && currentSessionId == null`）需要 `createSession()` + `upsertSession()` → 属于 SessionVM 的职责
- 未存档路径需要 `unarchiveSession` → 属于 SessionVM

**设计**：ChatVM 拥有 `sendMessage` 主体，跨域调用通过 OrchestratorVM：

```kotlin
// ChatVM.sendMessage()
fun sendMessage() {
    val draftWorkdir = composerFlow.value.draftWorkdir
    val existingSessionId = chatFlow.value.currentSessionId
    
    if (draftWorkdir != null && existingSessionId == null) {
        // 委托 OrchestratorVM 协调 session 创建
        orchestratorVM.materializeDraftSession(draftWorkdir) { sessionId ->
            chatVM.dispatchSendMessage(sessionId)
        }
        return
    }
    val sessionId = existingSessionId ?: return
    // ... 
}
```

### 5.2 `selectHostProfile(profileId)` — HostVM（调 OrchestratorVM → ConnectionVM + ConnectionVM）

HostProfileController 内联 `purgePerHostState()` + `configureRepositoryForProfile(profile)` + `forceReconnect()` + `onHostProfileSwitched()`。

批次 3 中 HostProfileController 直接写入 `hostFlow`，并通过 `Effect.HostProfileSwitched` 告知 OrchestratorVM：

```kotlin
// HostProfileController 内
fun selectHostProfile(profileId: String) {
    scope.launch {
        val profile = hostProfileStore.select(profileId)
        purgePerHostState()
        configureRepositoryForProfile(profile)
        refreshHostProfileState()
        effects.tryEmit(ControllerEffect.CancelSseForReconfigure)
        effects.tryEmit(ControllerEffect.ForceReconnect)
        effects.tryEmit(ControllerEffect.HostProfileSwitched)
    }
}
```

OrchestratorVM 的 `controllerEffects` 收集器分发到 ConnectionVM（cancelSseForReconfigure/forceReconnect）和 ComposerVM（reloadDisabledModelsForCurrentHost）。

### 5.3 `configureServer(url, username?, password?)` — HostVM

HostProfileController.configureServer 内联 cancelSseForReconfigure，然后通过 `Effect.CancelSseForReconfigure` → OrchestratorVM → ConnectionVM。

### 5.4 `resetLocalDataAndResync()` — OrchestratorVM（调 HostVM + ConnectionVM + 各 VM reset）

```kotlin
// OrchestratorVM
fun resetLocalDataAndResync() {
    hostVM.resetLocalData()           // HostProfileController.resetLocalDataAndResync
    connectionVM.cancelSseForReconfigure()
    connectionVM.coldStartReconnect() // 重置后重连
    chatVM.resetState()
    sessionVM.resetState()
    composerVM.resetState()
    // trafficTracker.reset() → 已在 HostVM 内部
}
```

### 5.5 `openSessionFromDeepLink(sessionId)` — OrchestratorVM → SessionVM

```kotlin
// OrchestratorVM
fun openSessionFromDeepLink(sessionId: String) {
    viewModelScope.launch {
        sessionVM.ensureSessionInList(sessionId)
        sessionVM.selectSession(sessionId)
    }
}
```

### 5.6 `executeCommand(command, arguments)` — ConnectionVM（调 ComposerVM + SessionVM）

`/clear` 分支调用 `setInputText("")` + `createSessionInWorkdir(workdir)` / `createSession()` — 两个方法在 SessionVM。

```kotlin
// ConnectionVM
fun executeCommand(command: String, arguments: String) {
    val cmd = command.removePrefix("/").trim().lowercase()
    when (cmd) {
        "clear" -> {
            composerVM.setInputText("")
            orchestratorVM.clearAndCreateSession(workdir)
        }
        else -> {
            // ... executeCommand on repository
        }
    }
}
```

### 5.7 `refreshCurrentSession()` — ChatVM（调 ConnectionVM）

```kotlin
// ChatVM
fun refreshCurrentSession() {
    val sessionId = chatFlow.value.currentSessionId ?: return
    performGlobalColdStartRefresh(sessionId)
    connectionVM.testConnection(force = true, onSettled = { ok ->
        if (ok && !chatFlow.value.isLoadingMessages) {
            uiEvents.tryEmit(UiEvent.Success("已刷新"))
        }
    })
}
```

### 5.8 编排模式总结

```
OrchestratorVM
├── 持有引用: ChatVM, SessionVM, ConnectionVM, HostVM, ComposerVM
├── 持有 shared: controllerEffects (MutableSharedFlow<ControllerEffect>)
├── 收集 controllerEffects → dispatch 到各 VM
├── 实现 ForegroundCatchUpCallbacks (→ 转调各 VM)
└── 暴露跨域方法: openSessionFromDeepLink, resetLocalDataAndResync, executeCommand/clear
```

**OrchestratorVM 不拥有 Controller**，Controller 归各领域 VM。跨域回调通过 `SharedFlow<ControllerEffect>` 流转，单域操作由各 VM 直接响应。

---

## 6. Hilt DI 变更

### 6.1 当前 DI 结构

- `MainViewModel`：`@HiltViewModel` + `@Inject constructor`
- 无 `@Provides`（`AppModule.kt` 为空模块）
- `OpenCodeRepository`、`SettingsManager`、`HostProfileStore`、`TrafficTracker`、`AppLifecycleMonitor`、`ServerCompatProfile` 均通过 `@Inject constructor` + `@Singleton` 提供

### 6.2 批次 3 后 DI

#### 6.2.1 6 个新 VM 声明

```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    val repository: OpenCodeRepository,
    val settingsManager: SettingsManager,
    val orchestratorVM: OrchestratorViewModel,  // 跨域引用
) : ViewModel() {
    // _chatFlow, _expandedParts 声明在此
    val chatFlow: StateFlow<ChatState>
    val expandedParts: StateFlow<Map<String, Boolean>>
    
    // 持有的 Controller
    private val sessionSyncCoordinator: SessionSyncCoordinator
}
```

```kotlin
@HiltViewModel
class SessionViewModel @Inject constructor(
    val repository: OpenCodeRepository,
    val settingsManager: SettingsManager,
    val orchestratorVM: OrchestratorViewModel,
) : ViewModel() {
    // _sessionListFlow, _unreadFlow 声明在此
    // 持有的 Controller: SessionSwitcher
    private val sessionSwitcher: SessionSwitcher
}
```

```kotlin
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    val repository: OpenCodeRepository,
    val settingsManager: SettingsManager,
    val hostProfileStore: HostProfileStore,
    val serverCompatProfile: ServerCompatProfile,
    val orchestratorVM: OrchestratorViewModel,
) : ViewModel() {
    // _connectionFlow 声明在此
    private val connectionCoordinator: ConnectionCoordinator
}
```

```kotlin
@HiltViewModel
class HostViewModel @Inject constructor(
    val hostProfileStore: HostProfileStore,
    val repository: OpenCodeRepository,
    val settingsManager: SettingsManager,
    val trafficTracker: TrafficTracker,
    val orchestratorVM: OrchestratorViewModel,
) : ViewModel() {
    // _hostFlow 声明在此
    private val hostProfileController: HostProfileController
}
```

```kotlin
@HiltViewModel
class ComposerViewModel @Inject constructor(
    val settingsManager: SettingsManager,
    val hostProfileStore: HostProfileStore,
    val orchestratorVM: OrchestratorViewModel,
) : ViewModel() {
    // _composerFlow 声明在此
    private val composerController: ComposerController
}
```

```kotlin
@HiltViewModel
class OrchestratorViewModel @Inject constructor(
    val settingsManager: SettingsManager,
    val trafficTracker: TrafficTracker,
    val appLifecycleMonitor: AppLifecycleMonitor,
    // 不持有 repository — 委托给领域 VM
) : ViewModel() {
    // _navFlow, _fileFlow, _settingsFlow, _trafficFlow, _uiEvents 声明在此
    val navFlow: StateFlow<NavState>
    val fileFlow: StateFlow<FileState>
    val settingsFlow: StateFlow<SettingsState>
    val trafficFlow: StateFlow<TrafficState>
    val uiEvents: SharedFlow<UiEvent>
    
    val controllerEffects = MutableSharedFlow<ControllerEffect>(extraBufferCapacity = 8)
    
    // 持有的 Controller
    private val foregroundCatchUpController: ForegroundCatchUpController
}
```

#### 6.2.2 循环依赖处理

跨域 VM 引用使用 Hilt 的 `dagger.Lazy<>` 延迟注入：

```kotlin
// 需要引用彼此的 VM（如 ChatVM ↔ SessionVM 互引）
@HiltViewModel
class ChatViewModel @Inject constructor(
    ...
    private val sessionVM: dagger.Lazy<SessionViewModel>, // 延迟注入避免循环
    ...
)
```

或者统一走 OrchestratorVM 中转——批次 3 推荐**只通过 OrchestratorVM 跨域访问**（领域 VM 不互相引用），彻底消除循环依赖。

#### 6.2.3 Slice MutableStateFlow 归属

| Slice | 所有者 VM | 可见性 |
|-------|----------|--------|
| `connectionFlow` | ConnectionVM | public StateFlow |
| `trafficFlow` | OrchestratorVM | public StateFlow |
| `composerFlow` | ComposerVM | public StateFlow |
| `fileFlow` | OrchestratorVM | public StateFlow |
| `settingsFlow` | OrchestratorVM | public StateFlow |
| `chatFlow` | ChatVM | public StateFlow |
| `sessionListFlow` | SessionVM | public StateFlow |
| `unreadFlow` | SessionVM | public StateFlow |
| `hostFlow` | HostVM | public StateFlow |
| `navFlow` | OrchestratorVM | public StateFlow |
| `expandedParts` | ChatVM | public StateFlow |
| `uiEvents` | OrchestratorVM | public SharedFlow |

#### 6.2.4 不再需要的组件

批次 3 完成后：
- `SliceFlows` data class — **移除**（不再需要跨 Controller 共享同一套 slice）
- `MainViewModel` — **移除**（替换为 6 领域 VM）
- `AppState` — **移除**（批次 2 已完成）
- `updateAndSync` / `syncSlicesFromAppState` / `aggregateFromSlices` 顶层函数 — **移除**（依赖 AppState mirror）
- `applyComposerSlice` / `applySettingsSlice` — **移除**（Controller 直接写领域 Slice）

---

## 7. 测试拆分策略

### 7.1 当前测试结构

| 测试文件 | 行数 | 内容 |
|---------|------|------|
| `MainViewModelTest.kt` | 3400 | 全部 63 个 public 方法的单元测试 |
| `ForegroundCatchUpControllerTest.kt` | — | 覆盖 M1 三层次状态机 |
| `ComposerControllerTest.kt` | — | 覆盖 M2 composer 写路径 |
| `SessionSwitcherTest.kt` | — | 覆盖 M2b 会话切换 8 步流程 |
| `HostProfileControllerTest.kt` | — | 覆盖 M3 host CRUD + 选择 + reconfigure + tunnel |
| `ConnectionCoordinatorTest.kt` | — | 覆盖 M4 连接生命周期 + SSE 起停 |
| `SessionSyncCoordinatorTest.kt` | — | 覆盖 M4 SSE 事件 fold |
| `FilesViewModelTest.kt` | — | 独立的 Files 域 |

### 7.2 拆分方案

#### 阶段 1：按领域 VM 拆分 MainViewModelTest

将 `MainViewModelTest.kt` 的 3400 行拆分为 6 个子文件：

| 测试文件 | 覆盖内容 | 预估行数 |
|---------|---------|---------|
| `ChatViewModelTest.kt` | sendMessage, loadMessages, loadMoreMessages, closeGap, compactSession, clearCompacting, editFromMessage, abortSession, refreshCurrentSession, SSE 回调（onRefreshMessages 等） | ~1200 |
| `SessionViewModelTest.kt` | selectSession (8-step flow), openSubAgent, closeSession, createSession, createSessionInWorkdir, forkSession, archiveSession, restoreSession, deleteSession, loadMoreSessions, toggleSessionExpanded, refreshDirectorySessions | ~900 |
| `ConnectionViewModelTest.kt` | testConnection (retry/exponential-backoff/onSettled), testConnectionForm, coldStartReconnect, loadInitialData（编排测试）, executeCommand | ~500 |
| `HostViewModelTest.kt` | selectHostProfile, deleteHostProfile, saveHostProfile, duplicateHostProfile, importHostProfile, exportHostProfile, configureServer, activateTunnel, resetLocalDataAndResync | ~400 |
| `ComposerViewModelTest.kt` | setInputText, addImageAttachments, removeImageAttachment, clearDraftIfActive, selectAgent, toggleModelDisabled, switchSessionModel, reloadDisabledModelsForCurrentHost | ~250 |
| `OrchestratorViewModelTest.kt` | DeepLink 流程、跨域编排（resetLocalDataAndResync / openSessionFromDeepLink）、导航 (setLastNavPage)、设置 UI 偏好、permission/question 响应、文件浏览器、流量统计 | ~150 |

**策略**：
1. **在同一个文件中创建新的 test class**（每个 VM 一个 class，6 个文件）
2. **逐方法迁移**：先从 `MainViewModelTest.kt` 复制对应 `@Test` 到新文件，调整 setup（实例化对应 VM 而非 MainViewModel）
3. **跨域测试集中到 OrchestratorViewModelTest**：涉及多 VM 交互的端到端场景（如 `sendMessage` → `createSession` → `selectSession`）放在 OrchestratorVM 测试中
4. **保留 `MainViewModelTest.kt` 直到所有迁移验证通过**，最后删除

#### 阶段 2：Controller 测试适配

现有 6 个 Controller 测试文件需要适配新参数：

- 不需要再 mock `MutableStateFlow<AppState>` 和 `SliceFlows`
- 改为直接 mock 对应 Slice 的 `MutableStateFlow`（如 `MutableStateFlow<ChatState>`）
- 简单设置 `MutableSharedFlow<ControllerEffect>` 的 turbine test channel

```kotlin
// ComposerControllerTest.kt 适配示例
class ComposerControllerTest {
    private val composerFlow = MutableStateFlow(ComposerState())
    private val chatFlow = MutableStateFlow(ChatState())
    private val expandedParts = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    // 不需要 callbacks mock — Controller 直接写 slice
    private val controller = ComposerController(
        composerFlow = composerFlow,
        chatFlow = chatFlow.asStateFlow(),
        expandedParts = expandedParts,
        // no callbacks needed
    )
}
```

#### 阶段 3：集成测试保留

在 `OrchestratorViewModelTest.kt` 中保留约 10 个端到端集成测试：

- 冷启动流程：`coldStartReconnect → testConnection → loadInitialData → loadSessions → selectSession`
- Deep link 导航：`openSessionFromDeepLink → selectSession → 渲染消息`
- 发送消息完整链路：`sendMessage → createSession → dispatchSendMessage → 查看 streamingPartTexts`
- Host 切换完整链路：`selectHostProfile → purgePerHostState → forceReconnect → 查看新 host 的 disabledModels`
- 本地数据重置：`resetLocalDataAndResync → 确认全部 slice 回默认`
- 跨域命令：`executeCommand("/clear") → 确认新 session 创建 + 输入清空`

### 7.3 测试运行顺序

1. 批次 2 完成 → 所有现有测试通过
2. 创建 6 个新 VM test class → 迁移测试
3. 6 个 VM class 编码完成 → 新测试通过
4. 删除 `MainViewModelTest.kt` 和 `MainViewModel.kt`
5. 运行 `./scripts/check.sh --full` 确认全部通过

---

## 附录 A：文件变更清单

### 新增文件

| 文件 | 内容 |
|------|------|
| `ui/ChatViewModel.kt` | ChatVM + SessionSyncCoordinator 持有 |
| `ui/SessionViewModel.kt` | SessionVM + SessionSwitcher 持有 |
| `ui/ConnectionViewModel.kt` | ConnectionVM + ConnectionCoordinator 持有 |
| `ui/HostViewModel.kt` | HostVM + HostProfileController 持有 |
| `ui/ComposerViewModel.kt` | ComposerVM + ComposerController 持有 |
| `ui/OrchestratorViewModel.kt` | OrchestratorVM + ForegroundCatchUpController 持有 |

### 修改文件

| 文件 | 变更 |
|------|------|
| `ui/MainActivity.kt` | `mainViewModel` → `orchestratorVM`；PhoneLayout 注入 6 个 VM |
| `ui/chat/ChatScreen.kt` | 参数拆分：`ChatViewModel + ComposerViewModel + ConnectionViewModel + OrchestratorViewModel` |
| `ui/chat/ChatInputBar.kt` | 参数拆分：`ComposerViewModel + ChatViewModel` |
| `ui/chat/ChatMessageContent.kt` | 参数拆分：`ChatViewModel + SessionViewModel` |
| `ui/sessions/SessionsScreen.kt` | 参数拆分：`SessionViewModel + ChatViewModel + ComposerViewModel + OrchestratorViewModel` |
| `ui/settings/SettingsScreen.kt` | 参数拆分：`HostViewModel + ConnectionViewModel + OrchestratorViewModel` |
| `ui/settings/HostProfilesManagerScreen.kt` | 参数拆分：`HostViewModel + ConnectionViewModel + OrchestratorViewModel` |
| `ui/controller/ComposerController.kt` | 移除 `state: MutableStateFlow<AppState>`；移除 callback interface；改为直接写 `composerFlow` |
| `ui/controller/SessionSwitcher.kt` | 移除 `state` 和 `slices`；简化为直接写 `chatFlow/sessionListFlow/unreadFlow/composerFlow` |
| `ui/controller/ConnectionCoordinator.kt` | 移除 `state` 和 `slices`；改为持有 `ConnectionCoordinatorCallbacks`（简化为 Effect） |
| `ui/controller/HostProfileController.kt` | 移除 `state` 和 `slices` 和 `eventEmitter`（Effect 替代） |
| `ui/controller/SessionSyncCoordinator.kt` | 移除 `state` 和 `slices`；改为直接写 `chatFlow/sessionListFlow` |
| `ui/controller/ForegroundCatchUpController.kt` | callback → `MutableSharedFlow<ControllerEffect>` |

### 删除文件

| 文件 | 原因 |
|------|------|
| `ui/MainViewModel.kt` | 6 领域 VM 替代 |
| `ui/MainViewModel*.kt`（5 个 action 文件） | 自由函数迁移到各 VM 的 companion/private |
| `ui/MainViewModelSliceSync.kt` | `SliceFlows` / `updateAndSync` / `applyXxxSlice` 不再需要 |
| `ui/MainViewModelSupport.kt`（部分） | `MainViewModelTimings` 迁移到 `ChatViewModel.Companion`；自由效用函数保留为顶层 |

---

## 附录 B：ControllerEffect 完整类型定义

```kotlin
sealed class ControllerEffect {
    // ── ForegroundCatchUpController ──
    /** Forces a health-check reconnect bypassing the 30s throttle. */
    data object ForceReconnect : ControllerEffect()
    /** Global cold-start reload: clear cache + message state for [sessionId]. */
    data class GlobalColdStartRefresh(val sessionId: String) : ControllerEffect()
    /** Cancels the in-flight SSE feed. */
    data object CancelSse : ControllerEffect()
    /** Gap-aware catch-up probe + tail reload. */
    data class CatchUpAfterDisconnect(val sessionId: String) : ControllerEffect()

    // ── SessionSwitcher ──
    data class LoadMessages(val sessionId: String, val resetLimit: Boolean) : ControllerEffect()
    data class LoadChildSessions(val sessionId: String) : ControllerEffect()
    data object LoadSessionStatus : ControllerEffect()
    data object LoadPendingQuestions : ControllerEffect()
    /** Drop all pending delta buffers in SessionSyncCoordinator. */
    data object ClearDeltaBuffers : ControllerEffect()

    // ── HostProfileController ──
    /** Cancel SSE feed BEFORE repository reconfigure. */
    data object CancelSseForReconfigure : ControllerEffect()
    /** Start SSE feed after successful connect. */
    data object StartSse : ControllerEffect()
    /** Host profile switched + settled → reload per-host state. */
    data object HostProfileSwitched : ControllerEffect()

    // ── ConnectionCoordinator ──
    /** A host/profile switch → reset foreground catch-up state machine. */
    data object HostReconfigured : ControllerEffect()

    // ── SessionSyncCoordinator ──
    /** server.connected frame → route to catch-up controller. */
    data object ServerConnected : ControllerEffect()
    /** Full session-list refresh. */
    data object RefreshSessions : ControllerEffect()
}
```

---

> **文档版本**：1.0  
> **分析日期**：2026-07-05  
> **分析范围**：`MainViewModel.kt`（2001 L） + 6 个 Controller + 8 个 Composable 入口 + 测试代码（3400 L）  
> **不修改任何文件** — 纯设计文档，供批次 3 fixer 派发。
