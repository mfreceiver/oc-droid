# RFC R-16：拆分 MainViewModel 上帝类

> **状态**：Draft  
> **RFC 编号**：R-16  
> **创建日期**：2026-06-30  
> **关联**：R-09（分层下沉）、R-10（SettingsManager 反向依赖）、R-17（AppState 拆 Flow）  
> **依赖**：无硬前置；建议 R-09/10 先修，R-17 可与 M3/M4 并行

---

## 摘要

`MainViewModel.kt`（2267行）+ 4 个分片（SessionActions 923、SyncActions 389、Support 198、ConnectionActions 86）合计 **~3.9K 行**。当前"拆分"仅切行数未切职责——所有 `internal fun launchXxx(scope, repository, state, settingsManager, …)` 是顶层函数，通过共享 `MutableStateFlow<AppState>` 通信，耦合未降低。`MainViewModelTest` 被迫用反射访问私有 `_state`（可测性恶化信号）。新增功能必然触达此聚合根，合并冲突概率高。

本 RFC 提出 **Coordinator 模式 + 分阶段迁移**，将 MainViewModel 分解为 6 个独立可测的 Coordinator，每个持 **自己的 StateFlow 子切片**，MainViewModel 退化为 **编排层**（合并子 Flow → 对外暴露统一 `StateFlow<AppState>`，保持 Composable 端零改动）。

---

## 1. 现状分析

### 1.1 职责清单（MainViewModel 当前持有）

| 职责域 | 代码行/位置 | 说明 |
|--------|-----------|------|
| **连接管理** | `:992-1066` testConnection/coldStartReconnect/loadInitialData | 健康检查+指数退避重试 |
| **Host Profile CRUD** | `:785-984` selectHostProfile/saveHostProfile/delete/duplicate/import/export | 含 configureRepositoryForProfile |
| **SSE 生命周期** | `:2197-2237` startSSE/handleSSEEvent | 委托 SyncActions |
| **Foreground catch-up 状态机** | `:567-610` 5 个 `@Volatile` 字段 + `:650-717` onForegroundChanged | sseHasConnectedOnce/suppressNextConnectCatchUp/backgroundedAtMs 等 |
| **Session 选择** | `:1208-1339` selectSession（130+ 行/8 职责）| 缓存回写/恢复、目录同步、unread 状态机、tab 管理 |
| **Session CRUD** | `:1179-1288, :1347-1439` loadSessions/loadMoreSessions/loadChildSessions/closeSession | 委托 SessionActions |
| **消息加载/分页** | `:1441-1554` loadMessages/loadMoreMessages/catchUp/closeGap/refreshCurrentSession | 委托 SessionActions |
| **消息发送** | `:1667-1817` sendMessage/dispatchSendMessage | 含 draft 模式+unarchive |
| **Agent/Provider/Command** | `:1549-1177` | loadAgents/loadProviders/loadCommands/executeCommand |
| **权限/问题** | `:1901-1966` respondPermission/loadPendingPermissions/replyQuestion/rejectQuestion | - |
| **文件浏览** | `:2106-2195` showFileInFiles/browseFilesInWorkdir/closeFileBrowser | - |
| **隧道激活** | `:2050-2104` activateTunnelForCurrentHost | - |
| **设置** | `:1874-1899` setThemeMode/setMarkdownFontSizes/selectAgent/toggleSessionExpanded | - |
| **流量统计** | `:1978-1991` refreshTrafficStats/resetTrafficStats | - |
| **重置/恢复** | `:2012-2048` resetLocalDataAndResync | - |
| **消息编辑/中断** | `:1819-1872` abortSession/editFromMessage | - |
| **Session Window Cache** | `:505-553` sessionWindowCache + 相关方法 | LRU 12条 |
| **Expanded Parts** | `:470-485` _expandedParts StateFlow | - |
| **输入/附件** | `:1829-1845` setInputText/addImageAttachments/removeImageAttachment | - |

### 1.2 当前"分片"的实质

4 个分片文件（`MainViewModelSessionActions.kt` 等）全部是 **`internal fun` 顶层函数**，接收 `MutableStateFlow<AppState>` 参数直接写入。这不是职责分离——是跨文件共享可变状态，耦合度与单文件无异。

示例（MainViewModelSessionActions.kt:225-258）：
```kotlin
internal fun selectSessionState(
    state: MutableStateFlow<AppState>,  // ← 共享可变状态
    settingsManager: SettingsManager,
    sessionId: String
) { … state.update { … } }
```

### 1.3 可测性恶化信号

`MainViewModelTest.kt:124-136`：
```kotlin
private fun updateState(viewModel: MainViewModel, transform: (AppState) -> AppState) {
    val field = MainViewModel::class.java.getDeclaredField("_state") // ← 反射
    field.isAccessible = true
    val flow = field.get(viewModel) as MutableStateFlow<AppState>
    flow.value = transform(flow.value)
}

private fun handleSse(viewModel: MainViewModel, event: SSEEvent) {
    val method = MainViewModel::class.java.getDeclaredMethod("handleSSEEvent", …) // ← 反射
    method.isAccessible = true
    method.invoke(viewModel, event)
}
```

当测试被迫用反射访问 private 成员，说明类内部状态边界过宽、粒度不适合单测。

---

## 2. 目标拆分蓝图

### 2.1 总体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                      MainViewModel (编排层)                       │
│  - 合并各 Coordinator 的子 Flow → 对外暴露单 StateFlow<AppState> │
│  - lifecycle 感知 (viewModelScope / onCleared)                   │
│  - 持有 Coordinator 实例（@Inject 构造）                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐│
│  │ConnectionCoordinator│  │SessionSyncCoordinator│  │ForegroundCatchUp  ││
│  │                  │  │                  │  │  Controller       ││
│  │ testConnection() │  │ handleSSEEvent() │  │ onForegroundChanged││
│  │ coldStartReconnect│  │  → 写入子 Flow   │  │ 三档状态机         ││
│  │  → 连接态 Flow    │  │  SSE 生命周期     │  │  → 触发 catch-up  ││
│  └──────────────────┘  └──────────────────┘  └──────────────────┘│
│                                                                   │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐│
│  │HostProfileController│  │SessionSwitcher    │  │ComposerController  ││
│  │                  │  │                  │  │                  ││
│  │ selectHost()     │  │ selectSession()  │  │ sendMessage()    ││
│  │ save/delete/import│  │ closeSession()   │  │ dispatchSend()   ││
│  │  → profile 态 Flow│  │ → sessions/unread│  │  → composer Flow ││
│  └──────────────────┘  │   Flow            │  └──────────────────┘│
│                        └──────────────────┘                       │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 各 Coordinator 定义

#### A. `ConnectionCoordinator`（~350 行迁出）

| 属性 | 值 |
|------|-----|
| **单一职责** | 管理服务器连接生命周期：健康检查、SSE启停、initialData加载编排 |
| **持有状态** | `MutableStateFlow<ConnectionState>`（isConnected/isConnecting/serverVersion/connectionPhase） |
| **依赖** | `OpenCodeRepository`, `HostProfileStore`, `SettingsManager` |
| **公开 API** | `fun testConnection(force: Boolean, retries: Int)`、`fun coldStartReconnect()`、`fun startSSE()`、`fun cancelSse()`、`val connectionState: StateFlow<ConnectionState>` |
| **生命周期** | `@HiltViewModel` 的协作者，跟随 ViewModel；startSSE 返回的 Job 由其持有 |

迁出函数：
- `testConnection` → ConnectionCoordinator
- `coldStartReconnect` → ConnectionCoordinator
- `loadInitialData` → ConnectionCoordinator（调用 callbacks 触发 loadSessions/loadAgents/loadProviders/loadCommands/loadPendingQuestions）
- `loadCommands` + `localCommands` + `mergeCommands` → ConnectionCoordinator
- `startSSE` → ConnectionCoordinator
- `cancelSseForReconfigure` → ConnectionCoordinator
- `handleSSEEvent` → 委托给 SessionSyncCoordinator（见 B）
- `lastHealthCheckTime` 字段 → ConnectionCoordinator

#### B. `SessionSyncCoordinator`（~500 行迁出）

| 属性 | 值 |
|------|-----|
| **单一职责** | SSE 事件 → AppState 折叠：将服务器推送的消息/状态/权限/问题事件转换为子 Flow 更新 |
| **持有状态** | `MutableStateFlow<SyncState>`（sessionStatuses/unreadSessions/tempClearedUnread/pendingPermissions/pendingQuestions/childSessions/sessionTodos） |
| **依赖** | `OpenCodeRepository` |
| **公开 API** | `fun handleSSEEvent(event: SSEEvent)`、`fun markSessionUnread(sessionId: String)`、`val syncState: StateFlow<SyncState>` |
| **生命周期** | ConnectionCoordinator 在 server.connected 时调用其 handleSSEEvent；不自己持 SSE Job |

迁出函数：
- `handleSSEEvent` → SessionSyncCoordinator（当前委托至 SyncActions）
- `handleIncomingSseEvent` 顶层函数 → SessionSyncCoordinator 内部
- `launchSseCollection` → ConnectionCoordinator（SSE 生命周期属连接管理）
- 将 `MainViewModelSyncActions.kt` 所有函数内联进 SessionSyncCoordinator，不再作为顶层函数

#### C. `ForegroundCatchUpController`（~250 行迁出）

| 属性 | 值 |
|------|-----|
| **单一职责** | 前台/后台切换的三档 catch-up 状态机：<15s 节流 / 15s-5min 增量 catch-up / >5min 全量重载 |
| **持有状态** | 5 个 `@Volatile` 字段移入：`hasObservedForegroundState`、`lastLoadAtMs`、`sseHasConnectedOnce`、`backgroundedAtMs`、`suppressNextConnectCatchUp` |
| **依赖** | `AppLifecycleMonitor`、回调 `(String) -> Unit`（catchUpAfterDisconnect）、回调 `(String) -> Unit`（performGlobalColdStart） |
| **公开 API** | `val shouldCatchUp: StateFlow<CatchUpDecision>`（枚举：Skip/ProbeColdStart）、`fun onServerConnected()`（消费 suppress 标记） |
| **生命周期** | 跟随 ViewModel，订阅 `AppLifecycleMonitor.isInForeground` |

迁出函数：
- `onForegroundChanged` → ForegroundCatchUpController
- `catchUpAfterDisconnectOrForeground` → 作为回调传入（实现在 SessionSwitcher）
- `performGlobalColdStartRefresh` → 作为回调传入
- 5 个 `@Volatile` 字段 → ForegroundCatchUpController
- `FOREGROUND_RELOAD_MIN_INTERVAL_MS` / `LONG_ABSENCE_THRESHOLD_MS` 常量

#### D. `HostProfileController`（~400 行迁出）

| 属性 | 值 |
|------|-----|
| **单一职责** | Host Profile CRUD + repository 重配置 |
| **持有状态** | `MutableStateFlow<HostProfileState>`（hostProfiles/currentHostProfileId） |
| **依赖** | `HostProfileStore`、`OpenCodeRepository`、`SettingsManager` |
| **公开 API** | `fun selectHostProfile(profileId: String)`、`fun saveHostProfile(…)`、`fun deleteHostProfile(profileId: String)`、`fun duplicateHostProfile(…)`、`fun importHostProfile(…)`、`fun exportHostProfile(…)`、`fun configureRepositoryForProfile(profile: HostProfile)`、`fun activateTunnelForCurrentHost()`、`fun resetLocalDataAndResync()`、`val hostProfileState: StateFlow<HostProfileState>` |
| **生命周期** | 跟随 ViewModel |

迁出函数：
- `selectHostProfile` → HostProfileController
- `saveHostProfile` → HostProfileController
- `deleteHostProfile` → HostProfileController
- `duplicateHostProfile` → HostProfileController
- `importHostProfile` / `exportHostProfile` → HostProfileController
- `activateTunnelForCurrentHost` → HostProfileController
- `configureRepositoryForProfile` → HostProfileController
- `resetLocalDataAndResync` → HostProfileController（协调各 Coordinator 的 reset）
- `getSavedConnectionSettings` → HostProfileController
- `getHostProfiles` / `currentHostProfile` / `refreshHostProfileState` → HostProfileController

#### E. `SessionSwitcher`（~500 行迁出）

| 属性 | 值 |
|------|-----|
| **单一职责** | Session 选择/关闭 + tab 管理 + unread 状态机 + window cache |
| **持有状态** | `MutableStateFlow<SessionListState>`（sessions/loadedSessionLimit/hasMoreSessions/expandedSessionIds/openSessionIds）、`sessionWindowCache`（LRU Map）、`_expandedParts` StateFlow |
| **依赖** | `OpenCodeRepository`、`SettingsManager` |
| **公开 API** | `fun selectSession(sessionId: String)`、`fun closeSession(sessionId: String)`、`fun loadSessions()`、`fun loadMoreSessions()`、`fun loadChildSessions(sessionId: String)`、`fun openSubAgent(childSessionId: String)`、`fun createSession(title: String?)`、`fun createSessionInWorkdir(workdir: String)`、`fun forkSession(…)`、`fun archiveSession(…)`、`fun restoreSession(…)`、`fun deleteSession(…)`、`fun clearDraftIfActive()`、`val sessionListState: StateFlow<SessionListState>` |
| **生命周期** | 跟随 ViewModel |

迁出函数：
- `selectSession` → SessionSwitcher（拆为 5 个 private 子步骤）
- `closeSession` → SessionSwitcher
- `loadSessions` / `loadMoreSessions` → SessionSwitcher
- `loadChildSessions` / `openSubAgent` → SessionSwitcher
- `createSession` / `createSessionInWorkdir` / `clearDraftIfActive` → SessionSwitcher
- `forkSession` / `archiveSession` / `restoreSession` / `deleteSession` → SessionSwitcher
- `sessionWindowCache` + 相关方法 → SessionSwitcher
- `_expandedParts` + `togglePartExpand`/`clearExpandedParts` → SessionSwitcher
- `loadSessionStatus` → SessionSwitcher
- `refreshDirectorySessions` → SessionSwitcher

`selectSession` 拆分（子步骤）：
1. `captureOutgoingState(previousSessionId)` — 保存草稿 + 回写 cache
2. `restoreIncomingState(sessionId)` — 恢复 cache window + 同步目录
3. `loadSessionContent(sessionId, hasCache)` — 加载消息+状态+子会话
4. `updateTabAndUnreadMachine(sessionId, previousSessionId, targetSession)` — tab 插入 + unread 重标记
5. `persistSessionCacheForSession(sessionId)` — Fix #5 的缓存持久化

#### F. `ComposerController`（~300 行迁出）

| 属性 | 值 |
|------|-----|
| **单一职责** | 消息发送（含 draft 模式）、消息编辑、会话中断 |
| **持有状态** | `MutableStateFlow<ComposerState>`（inputText/imageAttachments/sendingSessionIds/selectedAgentName/draftWorkdir） |
| **依赖** | `OpenCodeRepository`、`SettingsManager` |
| **公开 API** | `fun sendMessage()`、`fun abortSession()`、`fun editFromMessage(messageId: String)`、`fun setInputText(text: String)`、`fun addImageAttachments(…)`、`fun removeImageAttachment(…)`、`fun selectAgent(name: String)`、`fun executeCommand(command: String, arguments: String)`、`val composerState: StateFlow<ComposerState>` |
| **生命周期** | 跟随 ViewModel |

迁出函数：
- `sendMessage` + `dispatchSendMessage` → ComposerController
- `abortSession` → ComposerController
- `editFromMessage` → ComposerController
- `setInputText` / `addImageAttachments` / `removeImageAttachment` → ComposerController
- `selectAgent` → ComposerController
- `executeCommand` → ComposerController
- `loadAgents` / `loadProviders` / `loadPendingPermissions` / `loadPendingQuestions` → ComposerController（初始化逻辑）
- `respondPermission` / `replyQuestion` / `rejectQuestion` → ComposerController

---

## 3. 所有权与状态边界

### 3.1 核心原则：每个 Coordinator 持有自己的 StateFlow 子切片

**反模式（当前）**：
```kotlin
// 所有顶层函数共享同一个 MutableStateFlow<AppState>
internal fun launchLoadSessions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,  // ← 共享可变状态
    …
)
```

**目标模式**：
```kotlin
@Inject
class SessionSwitcher @Inject constructor(
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val scope: CoroutineScope  // 由 ViewModel 传入 viewModelScope
) {
    // 只持有自己职责域的状态
    private val _sessionListState = MutableStateFlow(SessionListState())
    val sessionListState: StateFlow<SessionListState> = _sessionListState.asStateFlow()

    // 操作只影响自己的子 Flow，不接触 AppState
    fun selectSession(sessionId: String) { … }
}
```

### 3.2 MainViewModel 的角色：编排层 + Flow 合并

```kotlin
@HiltViewModel
class MainViewModel @Inject constructor(
    private val connectionCoordinator: ConnectionCoordinator,
    private val sessionSyncCoordinator: SessionSyncCoordinator,
    private val foregroundCatchUpController: ForegroundCatchUpController,
    private val hostProfileController: HostProfileController,
    private val sessionSwitcher: SessionSwitcher,
    private val composerController: ComposerController,
) : ViewModel() {

    // 合并各 Coordinator 的子 Flow → 统一 AppState（保持 Composable 端兼容）
    val state: StateFlow<AppState> = combine(
        connectionCoordinator.connectionState,
        sessionSyncCoordinator.syncState,
        sessionSwitcher.sessionListState,
        sessionSwitcher.expandedParts,
        composerController.composerState,
        hostProfileController.hostProfileState,
        // … 其他固定字段（theme/font/fileBrowser 等仍在 ViewModel）
    ) { connection, sync, sessions, expandedParts, composer, hostProfile ->
        AppState(
            isConnected = connection.isConnected,
            isConnecting = connection.isConnecting,
            // … 组装
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppState())
}
```

关键约束：
- **各 Coordinator 的 StateFlow 是 write-only*（Coordinator 内部写），read-only（外部通过 `asStateFlow()` 暴露）**
- **MainViewModel 不直接操作任何 Coordinator 的内部状态**——只通过公开 API 调用
- **combine 合并是纯计算**，不产生副作用

### 3.3 关于 Domain UseCase 层的判断

**不建议**在 R-16 中引入 UseCase 层。理由：
1. R-16 的目标是切职责、降耦合、提可测性。引入 UseCase 层会增加额外的抽象级别，与开刀范围不成比例。
2. 当前业务逻辑几乎全是"编排 API 调用 + 更新状态"，并非纯领域逻辑，不适合抽取为无状态的 UseCase 对象。
3. 若未来某 Coordinator 内部逻辑过重（如 SSE 事件解析→状态折叠的复杂规则），可在该 Coordinator 内部进一步抽取纯函数 helper，无需一个完整 UseCase 层。

**触发 UseCase 层的信号**：当 Coordinator 的 private 方法超过~300行且包含大量纯计算/策略逻辑时，再考虑在 Coordinator 内部抽取 `internal class XxxUseCase`。

---

## 4. 签名变更清单

### 4.1 迁移映射表

| 当前位置 | 迁入类 | 新签名 |
|----------|--------|--------|
| `MainViewModel.testConnection` | `ConnectionCoordinator` | `fun testConnection(force: Boolean, retries: Int): Job` |
| `MainViewModel.coldStartReconnect` | `ConnectionCoordinator` | `fun coldStartReconnect(): Job` |
| `MainViewModel.startSSE` | `ConnectionCoordinator` | `private fun startSSE()` |
| `MainViewModel.handleSSEEvent` | `SessionSyncCoordinator` | `fun handleSSEEvent(event: SSEEvent)` |
| `MainViewModel.onForegroundChanged` | `ForegroundCatchUpController` | `fun onForegroundChanged(inForeground: Boolean)` |
| `MainViewModel.selectSession` | `SessionSwitcher` | `fun selectSession(sessionId: String)` |
| `MainViewModel.closeSession` | `SessionSwitcher` | `fun closeSession(sessionId: String)` |
| `MainViewModel.loadSessions` | `SessionSwitcher` | `fun loadSessions()` |
| `MainViewModel.loadChildSessions` | `SessionSwitcher` | `fun loadChildSessions(sessionId: String)` |
| `MainViewModel.createSession` | `SessionSwitcher` | `fun createSession(title: String?)` |
| `MainViewModel.createSessionInWorkdir` | `SessionSwitcher` | `fun createSessionInWorkdir(workdir: String)` |
| `MainViewModel.clearDraftIfActive` | `SessionSwitcher` | `fun clearDraftIfActive()` |
| `MainViewModel.sendMessage` | `ComposerController` | `fun sendMessage()` |
| `MainViewModel.selectHostProfile` | `HostProfileController` | `fun selectHostProfile(profileId: String)` |
| `MainViewModel.saveHostProfile` | `HostProfileController` | `fun saveHostProfile(…)` |
| `MainViewModel.activateTunnelForCurrentHost` | `HostProfileController` | `fun activateTunnelForCurrentHost()` |
| `MainViewModel.resetLocalDataAndResync` | `HostProfileController` | `fun resetLocalDataAndResync()` |
| `MainViewModel.loadMessages` | 保留在 MainViewModel | 委托给 `SessionSwitcher.loadSessionContent` |
| `MainViewModel.showFileInFiles` | 保留在 MainViewModel | 文件浏览暂不拆（逻辑简单） |
| `MainViewModel.setThemeMode` | 保留在 MainViewModel | 设置态暂不拆 |
| `MainViewModel.refreshTrafficStats` | 保留在 MainViewModel | 流量统计暂不拆 |

### 4.2 各 Coordinator 的构造参数

```kotlin
// ConnectionCoordinator
@Inject constructor(
    private val repository: OpenCodeRepository,
    private val hostProfileStore: HostProfileStore,
    private val settingsManager: SettingsManager,
    private val trafficTracker: TrafficTracker,
    private val sessionSyncCoordinator: SessionSyncCoordinator,
    private val onInitialDataLoad: InitialDataCallbacks  // interface
)

// SessionSyncCoordinator
@Inject constructor(
    private val repository: OpenCodeRepository,
    private val sessionSwitcher: SessionSwitcher,  // 用于 session.updated 的 upsert
    private val composerController: ComposerController  // 用于 message 回调
)

// ForegroundCatchUpController
@Inject constructor(
    private val appLifecycleMonitor: AppLifecycleMonitor,
    private val connectionCoordinator: ConnectionCoordinator  // 触发 testConnection(force=true)
)

// HostProfileController
@Inject constructor(
    private val hostProfileStore: HostProfileStore,
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val connectionCoordinator: ConnectionCoordinator,  // cancelSse + testConnection
    private val sessionSwitcher: SessionSwitcher  // 切换时清 session 态
)

// SessionSwitcher
@Inject constructor(
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager
)

// ComposerController
@Inject constructor(
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val sessionSwitcher: SessionSwitcher
)
```

### 4.3 Hilt 注入方案

所有 Coordinator 使用 `@Inject constructor` + `@Singleton`（协作者本身无状态时），或 `@ViewModelScoped`（持有协程状态时）。

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object CoordinatorModule {
    // Coordinator 之间可能有循环依赖？检查：
    // ConnectionCoordinator → SessionSyncCoordinator
    // SessionSyncCoordinator → SessionSwitcher → (无循环)
    // 如需解循环，用 @Inject lateinit var + setter 注入，或引入接口

    // 若出现循环：ConnectionCoordinator 注入 Provider<SessionSyncCoordinator>
}
```

**Composable 端改动：零**。`MainActivity.kt` 仍调用 `hiltViewModel<MainViewModel>()`，Composable 仍消费 `viewModel.state`。MainViewModel 内部委托给各 Coordinator。

### 4.4 Coordinator 间的通信

| 场景 | 通信方式 |
|------|----------|
| ConnectionCoordinator 通知 SessionSyncCoordinator 处理 SSE 事件 | 直接调用 `sessionSyncCoordinator.handleSSEEvent(event)` |
| ForegroundCatchUpController 触发 catch-up | 回调 lambda：`onCatchUp: (String) -> Unit`（MainViewModel 注入 `sessionSwitcher::catchUpAfterDisconnect`） |
| HostProfileController 切换时清 session 态 | 回调 `onHostSwitch: () -> Unit`（MainViewModel 注入清理动作） |
| ComposerController 发送成功后刷新 session 列表 | 回调 `onRefreshSessions: () -> Unit`（MainViewModel 注入 `sessionSwitcher::loadSessions`） |
| SessionSwitcher 需要检查连接状态 | 读 `ConnectionCoordinator.connectionState.value`（被动查询，不订阅） |

---

## 5. 分阶段迁移路径

### Milestone 1：抽 `ForegroundCatchUpController` + 补单测（~2天）

**范围**：
1. 新建 `ForegroundCatchUpController` 类
2. 移入 5 个 `@Volatile` 字段 + `onForegroundChanged`
3. 在 MainViewModel 中持有实例，通过回调注入 `catchUpAfterDisconnectOrForeground` 和 `performGlobalColdStartRefresh`
4. 写独立单测：`ForegroundCatchUpControllerTest`

**收益**：MainViewModel 瘦 ~250 行；三档阈值逻辑首次可独立测试（不再依赖整个 ViewModel 的 mock 链）；为后续里程碑建立 Coordinator 模式范例。

**风险**：低。`onForegroundChanged` 逻辑自洽，拆出不影响其他功能。前景→后台的 SSE 取消需走回调通知 ConnectionCoordinator（M2 才拆出，M1 中回调回到 MainViewModel 的 `sseJob?.cancel()` 即可）。

**回归点**：三个前景档位（<15s/15s-5min/>5min）的行为不变；`staleNotice` banner 不变。

### Milestone 2：抽 `SessionSwitcher` + `ComposerController`（~3天）

**范围**：
1. 新建 `SessionSwitcher`，保有 `_sessionListState` + `_expandedParts` + `sessionWindowCache`
2. 移入 `selectSession`（及其 5 个子步骤）、`closeSession`、session CRUD
3. 新建 `ComposerController`，保有 `_composerState`
4. 移入 `sendMessage`/`dispatchSendMessage`/`abortSession`/`editFromMessage`/input/attachment/agent
5. MainViewModel 通过 `combine` 合并子 Flow
6. 拆分 `MainViewModelTest` → `SessionSwitcherTest` + `ComposerControllerTest`
7. **删除反射访问 `_state` 的测试 helper**

**收益**：MainViewModel 再瘦 ~800 行；取消反射测试；selectSession 可独立测试 unread 状态机、tab 管理、cache 回写/恢复。

**风险**：中。`selectSession` 是核心流程，拆分子步骤需精确保持语义。`sendMessage` 的 draft 模式 + 同步还原逻辑复杂。建议先写 Characterization Test（验证当前行为），再迁移。

**回归点**：session 切换体验（缓存恢复不闪烁、目录同步、unread badge）不变；消息发送（draft 模式、unarchive 前置、重复发送防护）不变。

### Milestone 3：抽 `HostProfileController`（~2天）

**范围**：
1. 新建 `HostProfileController`
2. 移入全部 Host Profile CRUD + `resetLocalDataAndResync`
3. `hostProfileController` 通过回调通知 ConnectionCoordinator cancelSse + testConnection

**收益**：MainViewModel 再瘦 ~400 行；Host Profile 管理首次可独立测试。

**风险**：中。切换 Host Profile 时需要跨 Coordinator 清理（session 列表、cache、SSE），当前这些逻辑内联在 `selectHostProfile` 中。需通过回调或事件协调——设计上需谨慎，建议采用 `HostProfileController` 暴露 `onBeforeSwitch: () -> Unit` 回调，由 MainViewModel 组装协调逻辑。

**回归点**：切换 Host Profile 后，旧 host 的 session/tabs/unread/draft 全清；切换 profile 后 SSE 正确重连到新 host。

### Milestone 4：抽 `ConnectionCoordinator` + `SessionSyncCoordinator`（~3天）

**范围**：
1. 新建 `ConnectionCoordinator`，移入 `testConnection`/`coldStartReconnect`/`loadInitialData`/SSE 生命周期
2. 新建 `SessionSyncCoordinator`，移入 `handleSSEEvent`，整合 SyncActions 逻辑
3. **删除 `MainViewModelSyncActions.kt` 等 4 个分片文件**（逻辑并入 Coordinator）
4. 删除 `MainViewModelSessionActions.kt`、`MainViewModelConnectionActions.kt`、`MainViewModelSupport.kt`（slim/worktree 下的副本同理）

**收益**：彻底消除 ~3.9K 行的上帝类；ViewModel 退化为 < 200 行的编排层；所有 Coordinator 独立可测。

**风险**：高。涉及 SSE 事件分发和连接生命周期，是全栈型改动。建议在 M3 完成后才动 M4，确保前三个 Coordinator 已验证稳定。

**回归点**：SSE 重连、server.connected catch-up、增量 probe、gap 检测、权限/问题推送均不变。

### 里程碑汇总

| 里程碑 | 迁出总行数 | 新增测试类 | MainViewModel 剩余行数 | 风险 |
|--------|-----------|-----------|----------------------|------|
| M1: ForegroundCatchUpController | ~250 | `ForegroundCatchUpControllerTest` | ~2000 | 低 |
| M2: SessionSwitcher + ComposerController | ~800 | `SessionSwitcherTest` + `ComposerControllerTest` | ~1200 | 中 |
| M3: HostProfileController | ~400 | `HostProfileControllerTest` | ~800 | 中 |
| M4: ConnectionCoordinator + SessionSyncCoordinator | ~800 | `ConnectionCoordinatorTest` + `SessionSyncCoordinatorTest` | ~200 | 高 |
| **合计** | **~2250** | **6 个独立测试类** | **~200** | - |

---

## 6. 测试策略

### 6.1 新 Coordinator 的单测模式

每个 Coordinator 单测遵循统一模式，**零反射**：

```kotlin
class SessionSwitcherTest {
    private lateinit var repository: OpenCodeRepository  // mockk
    private lateinit var settingsManager: SettingsManager  // mockk
    private lateinit var switcher: SessionSwitcher

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        switcher = SessionSwitcher(repository, settingsManager)
    }

    @Test
    fun `selectSession captures outgoing window into cache`() = runTest {
        // Given: 当前 session-1 有消息
        switcher._sessionListState.value = switcher.sessionListState.value.copy(
            currentSessionId = "session-1",
            messages = listOf(userMessage("m1"))
        )

        // When: 切换到 session-2
        switcher.selectSession("session-2")

        // Then: session-1 的窗口被缓存
        val cached = switcher.peekSessionWindow("session-1")
        assertNotNull(cached)
        assertEquals(1, cached!!.messages.size)
    }
}
```

关键原则：
- 注入真实 StateFlow（可写 `_sessionListState.value = …`），不 mock MutableStateFlow
- 用 `runTest` + `advanceUntilIdle()` 控制协程
- 所有 mock 通过 `relaxed = true` 减少 setup 代码
- Coordinator 间依赖通过 mock + callback 注入，不构造完整依赖图

### 6.2 MainViewModelTest 的拆分

| 当前测试 | 迁移到 |
|----------|--------|
| `sendMessage success clears input` | `ComposerControllerTest` |
| `sendMessage ignores duplicate sends` | `ComposerControllerTest` |
| `sendMessage success refreshes sessions` | `ComposerControllerTest` |
| SSE 相关测试（handleSse…） | `SessionSyncCoordinatorTest` |
| Session 切换测试 | `SessionSwitcherTest` |
| `resetLocalDataAndResync` 测试 | `HostProfileControllerTest` (M3) 或保留 Integration 测试 |
| `testConnection` 测试 | `ConnectionCoordinatorTest` (M4) |

**MainViewModelTest 保留项**：整体集成测试——验证 `combine` 合并正确、事件编排正确。不过度 mock Coordinator（用真实 Coordinator + mock Repository）。

### 6.3 删除反射代码

**每个 Milestone 完成后检查**：
```bash
rg "getDeclaredField|getDeclaredMethod|\\.isAccessible" app/src/test/
```
目标：M4 完成后 **零反射**。

---

## 7. 风险与权衡

### 7.1 过渡期双重状态持有的风险

在 M2-M3 期间，部分状态同时存在于 MainViewModel 的 `_state` 和 Coordinator 的独立 Flow 中。例如 M2 引入 `SessionSwitcher._sessionListState` 后，MainViewModel 中可能遗留直接读写 `_state.value.sessions` 的旧代码。

**缓解措施**：
- 每个 Milestone 迁移完成后，**立即删除 MainViewModel 中的对应字段和私有方法**，不留死代码。
- 用 `rg "state\\.update.*sessions|_state\\.value\\.sessions"` 扫描残留直接访问。
- M2 完成时 MainViewModel 不再直接持有 `_state.value.sessions`——转而从 `sessionSwitcher.sessionListState` 派生。

### 7.2 与 R-09/10/17 的依赖关系

| RFC | 与本 RFC 的关系 | 顺序建议 |
|-----|---------------|----------|
| **R-09** SSEClient 反向依赖 UI 层 | 低耦合：`NOISY_SSE_LOG_EVENTS` 下沉到 `data/api/` 即可，不影响 Coordinator 拆分 | **先修 R-09**（改动小，1文件），再开始 M1 |
| **R-10** SettingsManager 反向依赖 UI theme | 中耦合：`MarkdownFontSizes` 下沉到 `util/` 后，Coordinator 不再需要 import ui.theme | **先修 R-10**（改动小），避免 Coordinator 带走反向依赖 |
| **R-17** AppState 拆 Flow | **高耦合**：R-17 的目标（拆 `StateFlow<AppState>` 为多个子 Flow）与本 RFC 的 Coordinator 模式天然互补——每个 Coordinator 持有自己的子 Flow 正是 R-17 要的结果。 | **先完成 R-16 M1-M3**（Coordinator 子 Flow 就位），再执行 R-17（MainViewModel 退化为纯 combine + AppState 快照） |

**推荐执行顺序**：
```
R-09 → R-10 → R-16 M1 → R-16 M2 → R-16 M3 → R-17 → R-16 M4
```

**理由**：R-09/10 是小的分层修正（总改动 < 30 行），先清掉避免带债入新代码。R-16 M1-M3 建立 Coordinator 框架后，R-17 的拆 Flow 就有现成的消费方（每个子 Flow 恰好由对应的 Coordinator 持有）。最后执行风险最高的 R-16 M4（连接+SSE 核心拆解）。

### 7.3 循环依赖风险

Coordinator 间存在循环引用风险：
- `ConnectionCoordinator` ↔ `SessionSyncCoordinator`（连接触发 SSE，SSE 需要知道连接态）
- `HostProfileController` → `ConnectionCoordinator`（切换 Host 需取消 SSE + 重建连接）
- `SessionSwitcher` → `HostProfileController`?（切换 Host 需清 session 态）

**缓解**：
- 使用 **callback/lambda 注入** 而非直接持有引用
- 若必须直接引用，使用 Dagger 的 `Provider<Lazy<T>>`
- 拆分回调接口：`interface OnHostSwitchListener { fun onHostSwitch() }` 由 MainViewModel 实现，注入各 Coordinator

### 7.4 YAGNI 警告

当前 `MainViewModelSessionActions.kt` 中如 `launchLoadSessions`、`launchLoadMoreSessions` 等顶层函数，在 M4 后将全部内联到对应的 Coordinator 内部。不要提前创建"通用工具函数层"——等 Coordinator 稳定后再看是否值得抽取。

---

## 8. 附录：子状态数据类定义

为支持 Coordinator 独立持有子 Flow，需定义以下 data class：

```kotlin
// ConnectionCoordinator
data class ConnectionState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val serverVersion: String? = null,
    val connectionPhase: String? = null
)

// SessionSyncCoordinator
data class SyncState(
    val sessionStatuses: Map<String, SessionStatus> = emptyMap(),
    val unreadSessions: Set<String> = emptySet(),
    val tempClearedUnread: Set<String> = emptySet(),
    val lastViewedTime: Map<String, Long> = emptyMap(),
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    val pendingQuestions: List<QuestionRequest> = emptyList(),
    val childSessions: Map<String, List<Session>> = emptyMap(),
    val sessionTodos: Map<String, List<TodoItem>> = emptyMap()
)

// SessionSwitcher (SessionListState)
data class SessionListState(
    val sessions: List<Session> = emptyList(),
    val loadedSessionLimit: Int = MainViewModelTimings.sessionPageSize,
    val hasMoreSessions: Boolean = true,
    val isLoadingMoreSessions: Boolean = false,
    val isRefreshingSessions: Boolean = false,
    val expandedSessionIds: Set<String> = emptySet(),
    val openSessionIds: List<String> = emptyList(),
    val currentSessionId: String? = null,
    val directorySessions: Map<String, List<Session>> = emptyMap()
)

// SessionSwitcher (ChatState)
data class ChatState(
    val messages: List<Message> = emptyList(),
    val partsByMessage: Map<String, List<Part>> = emptyMap(),
    val streamingPartTexts: Map<String, String> = emptyMap(),
    val streamingReasoningPart: Part? = null,
    val isLoadingMessages: Boolean = false,
    val olderMessagesCursor: String? = null,
    val hasMoreMessages: Boolean = true,
    val gapInfo: GapInfo? = null,
    val staleNotice: Boolean = false
)

// ComposerController
data class ComposerState(
    val inputText: String = "",
    val imageAttachments: List<ComposerImageAttachment> = emptyList(),
    val sendingSessionIds: Set<String> = emptySet(),
    val selectedAgentName: String = "build",
    val agents: List<AgentInfo> = emptyList(),
    val providers: ProvidersResponse? = null,
    val draftWorkdir: String? = null,
    val availableCommands: List<CommandInfo> = emptyList()
)

// HostProfileController
data class HostProfileState(
    val hostProfiles: List<HostProfile> = emptyList(),
    val currentHostProfileId: String? = null,
    val tunnelActivationState: TunnelActivationState = TunnelActivationState.Idle
)

// ForegroundCatchUpController
enum class CatchUpDecision { Skip, Probe, ColdStart }
```

> 这些 data class 替换当前 `AppState` 内部已定义的 `ConnectionState` / `SessionState` / `ChatState` 等派生属性（当前它们是 computed properties，新方案中它们是有源 StateFlow）。

---

## 9. 决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| 模式 | Coordinator（非 UseCase） | 有状态 + 长生命周期；不引入额外抽象层 |
| Hilt | `@Inject constructor` + `@Singleton` | Dagger 原生支持，无需额外 Module |
| Composable 端 | 保持 `hiltViewModel<MainViewModel>()` 单入口 | 零 UI 改动，降低迁移风险 |
| 子 Flow | Coordinator 持有 `MutableStateFlow<SubState>` | 避免回到共享 `MutableStateFlow<AppState>` 的反模式 |
| Flow 合并 | `combine { … }` → `stateIn(…)` | 标准 Kotlin Flow 模式，无需引入第三方 |
| SSE 分片文件 | M4 删除全部 4 个分片文件 | 逻辑并入 Coordinator 后保留顶层函数无意义 |
| selectSession 拆分 | 5 个 private 子步骤（非公开 API） | SessionSwitcher 对外仅暴露 `selectSession(sessionId)` |
| 测试策略 | 每个 Coordinator 独立单测 + MainViewModelTest 保留集成测试 | 独立可测 + 集成回归 |
