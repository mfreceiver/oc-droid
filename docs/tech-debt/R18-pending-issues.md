# R-18 待解决问题（Pending Issues）

> 本文件是 ocdroid 项目**唯一的待解决问题清单**，取代已归档的 `refactor-R17-followup.md`。
> 来源：R-17 综合重构 5 批次延后项 + 4 路全局终审（kimo 8.9 / gpter 8.0 / dser 8.0 / glmer 7.5）新发现。
> 每项含：来源、证据、**具体修复方案（含代码骨架）**、预估工作量。

---

## R-17 成就基线

| 指标 | 重构前 | 重构后 |
|---|---|---|
| 可维护性（4 路平均） | 5.3/10 | 8.1/10 |
| MainViewModel 行数 | 2063 | 0（删除） |
| AppCore 行数 | — | 291 + 329 orchestration |
| AppState mirror 字段 | 50+ | 0（整文件删除） |
| check(Looper) | 17 | 0 |
| callback interface | 6（41 方法） | 0（22 ControllerEffect） |
| SSE 纯函数 | 0 | 22（+49 table 测试） |

---

## 一、P0 必须修复

### P0-1. currentDirectory 全局状态未彻底消除

- **来源**：gpter 🔴 + dser 🔴 + glmer 🟠 + kimo 🔴（4/4 共识，最高优先）
- **当前状态**：文件 API 已改显式 directory，但 OrchestratorVM/SessionVM 仍调 `repository.setCurrentDirectory()`，`HostConfig._currentDirectory` 仍被 `DirectoryHeaderInterceptor` 读取
- **影响**：文件浏览器打开期间，SSE/question/command 路由可能导向错误目录

**修复方案**：

**步骤 1：文件浏览器不再触碰 repository**

`OrchestratorViewModel` 的 `browseFilesInWorkdir` / `closeFileBrowser` 改为只写 slice：
```kotlin
// OrchestratorViewModel.kt
fun browseFilesInWorkdir(workdir: String) {
    val saved = core.store.sessionListFlow.value.directorySessions.keys.firstOrNull()
    core.store.fileFlow.update { it.copy(fileBrowserOpen = true, fileBrowserWorkdir = workdir) }
    // 不再调 repository.setCurrentDirectory(workdir)
}
fun closeFileBrowser() {
    core.store.fileFlow.update { it.copy(fileBrowserOpen = false, fileBrowserWorkdir = null) }
    // 不再调 repository.setCurrentDirectory(saved)
}
```

**步骤 2：非文件路由从 session 显式取 directory**

`DirectoryHeaderInterceptor` 不再读全局 `_currentDirectory`，改为从请求 header `X-Opencode-Session-Directory` 取值（由调用方在 Retrofit `@Header` 传入）。

各 API 方法的调用方在发起请求前，从 `sessionListFlow` 查找 session 的 directory：
```kotlin
// 例如 SSE 连接时
val directory = store.sessionListFlow.value.sessions
    .firstOrNull { it.id == sessionId }?.directory
    ?: settingsManager.currentWorkdir
// 传给 repository.connectSSE(directory)
```

**步骤 3：删除全局可变状态**
- 删除 `HostConfig._currentDirectory` / `setCurrentDirectory` / `currentDirectory`
- 删除 `OpenCodeRepository.setCurrentDirectory` / `getCurrentDirectory`
- `DirectoryHeaderInterceptor` 改为只从 per-request header 注入（无全局 fallback）

**步骤 4：createSessionInWorkdir 不设全局目录**
```kotlin
// SessionViewModel.kt — createSessionInWorkdir
// 删除 core.repository.setCurrentDirectory(workdir)
// 后续 API 调用显式传 directory=workdir
```

- **预估工作量**：中（~200 行，5+ 文件）

---

### P0-2. currentSessionId 双写残存（SettingsManager + ChatState）

- **来源**：dser 🔴 + kimo 🟠
- **当前状态**：10 个写入口在 7 文件，非原子双写

**修复方案：ChatState 为唯一运行时源，SettingsManager 为冷启动种子 + 持久化副作用**

```kotlin
// AppCore.kt init 块中添加：
init {
    // 冷启动：从 SettingsManager 种子 ChatState
    val persisted = settingsManager.currentSessionId
    if (persisted != null) {
        store.chatFlow.update { it.copy(currentSessionId = persisted) }
    }

    // 持久化副作用：chatFlow.currentSessionId 变化时写 SettingsManager
    appScope.launch {
        store.chatFlow.map { it.currentSessionId }
            .distinctUntilChanged()
            .collect { id ->
                settingsManager.currentSessionId = id
            }
    }
}
```

然后**删除全部 10 个手动 `settingsManager.currentSessionId = xxx` 调用点**。运行时只写 `store.chatFlow.update { it.copy(currentSessionId = ...) }`，持久化由 collector 自动完成。

写入口从 10→仅 `writeChat { ... }`（凡是修改 currentSessionId 的地方）。

- **预估工作量**：中（删 10 处手动写 + 加 1 个 collector）

---

### P0-3. VM/controller 层 i18n 漏洞

- **来源**：glmer 🔴 + kimo 🟠
- **当前状态**：`UiEvent.Error("隧道激活失败")` 等硬编码中文，lint 抓不到

**修复方案：UiEvent 携带 @StringRes + Composable 层解析**

```kotlin
// UiEvent.kt
sealed class UiEvent {
    data class Error(@StringRes val resId: Int, val args: List<Any> = emptyList()) : UiEvent()
    data class Success(@StringRes val resId: Int, val args: List<Any> = emptyList()) : UiEvent()
}
```

```kotlin
// ChatScreen.kt — uiEvents collector
LaunchedEffect(Unit) {
    orchestratorVM.uiEvents.collect { event ->
        val message = when (event) {
            is UiEvent.Error -> stringResource(event.resId, *event.args.toTypedArray())
            is UiEvent.Success -> stringResource(event.resId, *event.args.toTypedArray())
        }
        // show snackbar with message
    }
}
```

调用方改写示例：
```kotlin
// 旧：eventEmitter.emit(UiEvent.Error("子任务会话不可用"))
// 新：eventEmitter.emit(UiEvent.Error(R.string.error_child_session_unavailable))

// 旧：eventEmitter.emit(UiEvent.Success("已刷新"))
// 新：eventEmitter.emit(UiEvent.Success(R.string.success_refreshed))

// 旧：UiEvent.Error("隧道激活失败：$msg")
// 新：UiEvent.Error(R.string.error_tunnel_activation_failed, listOf(msg))
```

strings.xml 需新增 ~8 个 key（中英双语）。

注意：`EventEmitter` 接口签名不变（仍 `fun emit(event: UiEvent)`），只是构造 UiEvent 的方式变了。

- **预估工作量**：小（~30 行改动 + 8 个 string key）

---

### P0-4. kover 门控在主路径失效

- **来源**：glmer 🔴

**修复方案**：

```bash
# scripts/check.sh — 在 --full 分支中替换 koverHtmlReport 为 koverVerify
case "$MODE" in
  --full)
    echo "==> lintDebug"
    $GRADLE lintDebug
    echo "==> koverVerify（覆盖率门控）"
    $GRADLE koverVerify
    echo "==> koverHtmlReport → app/build/reports/kover/html/index.html"
    $GRADLE koverHtmlReport
    ;;
esac
```

后续逐步提升阈值：当前 `app/build.gradle.kts` 的 kover 阈值 ~25% line。建议每季度提升 5%，目标 util/data 模块 70%+，ui 模块 40%+。

- **预估工作量**：1 行（check.sh）+ 持续（阈值提升）

---

## 二、P1 应修复

### P1-1. SharedStateStore 写权限无编译期隔离

- **来源**：gpter 🟠 + dser 🟠 + kimo 🟡 + glmer 🟡

**修复方案：StateFlow 只读 facade + internal 写入口**

```kotlin
// SharedStateStore.kt
@Singleton
class SharedStateStore @Inject constructor() {
    private val _connectionFlow = MutableStateFlow(ConnectionState())
    val connectionFlow: StateFlow<ConnectionState> = _connectionFlow.asStateFlow()

    private val _chatFlow = MutableStateFlow(ChatState())
    val chatFlow: StateFlow<ChatState> = _chatFlow.asStateFlow()

    // ... 其余 7 个 slice 同理

    // internal 写入口 — 仅同模块（ui/）内的 VM/controller 可访问
    internal fun mutateConnection(transform: (ConnectionState) -> ConnectionState) {
        _connectionFlow.update(transform)
    }
    internal fun mutateChat(transform: (ChatState) -> ChatState) {
        _chatFlow.update(transform)
    }
    // ... 其余 7 个
}
```

VM/controller 调用处：
```kotlin
// 旧：core.store.chatFlow.update { it.copy(isLoadingMessages = true) }
// 新：core.store.mutateChat { it.copy(isLoadingMessages = true) }
```

编译器保证：其他模块（data/、di/）注入 SharedStateStore 后只能读 StateFlow，不能写。AppCore.writeXxx 可删除（store.mutateXxx 直接替代）。

- **预估工作量**：大（~50 处调用点改写 + 18 个 facade 声明）

---

### P1-2. appScope vs viewModelScope 语义混淆

- **来源**：dser 🟠 + kimo 🟡

**修复方案：区分 ephemeral（viewModelScope）与 persistent（appScope）操作**

原则：
- **用户交互触发的操作**（loadMessages, selectSession, compactSession, sendMessage, setInputText...）→ `viewModelScope`（VM 清除时取消）
- **后台持续操作**（SSE collection, foreground catch-up polling, delta flush timer）→ `appScope`（进程级，正确）

实现：Actions 自由函数已经接收 `scope: CoroutineScope` 参数。各 VM 调用时传 `viewModelScope`：
```kotlin
// ChatViewModel.kt
fun loadMessages(sessionId: String, resetLimit: Boolean) {
    launchLoadMessages(
        scope = viewModelScope,  // ← 改这里（原来传 core.appScope）
        repository = core.repository,
        slices = core.store,
        ...
    )
}
```

**注意**：SessionSyncCoordinator 和 ConnectionCoordinator 的内部协程（SSE collection、delta flush）保持 `appScope`——它们的生命周期是进程级。只有用户交互触发的操作改为 viewModelScope。

- **预估工作量**：中（~20 处 `core.appScope` → `viewModelScope` 改写）

---

### P1-3. tryEmit 关键 effect 可静默丢失

- **来源**：gpter 🟠 + maxer 🟡 + kimo 🟡

**修复方案：拆分可靠命令通道与可丢 UI 事件通道**

```kotlin
// SharedEffectBus.kt
@Singleton
class SharedEffectBus @Inject constructor() {
    // UI 事件：可丢（toast/snackbar 是 ephemeral feedback）
    val uiEvents: MutableSharedFlow<UiEvent> = MutableSharedFlow(extraBufferCapacity = 16)

    // 业务命令：不可丢（使用 Channel + 容量缓冲）
    // CAPACITY > 22（当前 effect 种类数），预留扩展空间
    private val _effects = Channel<ControllerEffect>(capacity = 128)
    val effects: ReceiveChannel<ControllerEffect> = _effects

    // suspend emit — 调用方必须在协程中
    suspend fun emitEffect(effect: ControllerEffect) {
        _effects.send(effect)  // Channel 满时挂起而非丢弃
    }
}
```

Controller 侧改写：
```kotlin
// 旧：effects.effects.tryEmit(ControllerEffect.LoadMessages(sid, true))
// 新：effects.emitEffect(ControllerEffect.LoadMessages(sid, true))  // suspend
```

AppCore dispatcher 改为 `effects.receive()`（Channel receive）。

**注意**：Controller 的 effect emit 当前在同步代码中（非 suspend）。改 Channel 后需要 `scope.launch { effects.emitEffect(...) }`。大多数 controller 方法已经在 `scope.launch` 内，所以 emit 调用已天然在协程中。

- **预估工作量**：中（SharedEffectBus 重写 + ~22 处 tryEmit→emitEffect + dispatcher 改 receive）

---

### P1-4. ConnectionCoordinator.directoryFetchGeneration 非原子

- **来源**：kimo 🔴

**修复方案**：
```kotlin
// ConnectionCoordinator.kt
// 旧：@Volatile private var directoryFetchGeneration: Long = 0
// 新：private val directoryFetchGeneration = java.util.concurrent.atomic.AtomicLong(0)

// 旧：++directoryFetchGeneration
// 新：directoryFetchGeneration.incrementAndGet()

// 旧：directoryFetchGeneration
// 新：directoryFetchGeneration.get()
```

- **预估工作量**：1 行声明 + ~3 处调用改写

---

### P1-5. FilesViewModel 空文件误判为目录

- **来源**：kimo 🟠 + maxer 🟡

**修复方案：用 path 特征区分，不靠 content 是否为空**

```kotlin
// FilesViewModel.kt — loadPreview
// 判断当前路径是否是目录：
// 1. 如果 path 以 "/" 结尾 → 目录
// 2. 如果 getFileTree(directory, path) 返回非空列表 → 目录
// 3. 否则 → 文件（即使 content 为空）
private fun loadPreview(pathToShow: String, sessionDirectory: String, isRefresh: Boolean) {
    // ...
    viewModelScope.launch {
        val content = repository.getFileContent(sessionDirectory, pathToShow).getOrNull()
        if (content?.content?.isNotBlank() == true) {
            // 有内容 → 文件，正常展示
            updateFilePreview(content)
        } else {
            // 无内容：区分空文件 vs 目录
            val treeResult = repository.getFileTree(sessionDirectory, pathToShow).getOrNull()
            if (treeResult != null && treeResult.isNotEmpty()) {
                // 目录 → 展示目录
                loadDirectoryPreview(pathToShow, sessionDirectory, isRefresh)
            } else {
                // 空文件 → 展示空内容（不误判为目录）
                updateFilePreview(content ?: FileContent(content = ""))
            }
        }
    }
}
```

同时修正 `FilesViewModelTest.kt:132-155` 的测试——空文件 fallback 不应断言为 directory preview。

- **预估工作量**：小（~15 行逻辑 + 1 个测试修正）

---

### P1-6. FilesViewModel.navigateUp 路径归一化缺陷

- **来源**：kimo 🟠

**修复方案**：
```kotlin
// FilesViewModel.kt
fun navigateUp() {
    val dir = _state.value.workdir ?: return
    val gen = requestGeneration.incrementAndGet()
    val currentPath = _state.value.currentPath
    // 先归一化（去尾部斜杠），再取 parent
    val normalized = currentPath.trimEnd('/')
    val parentPath = normalized.substringBeforeLast("/", "")
    if (parentPath == normalized) return  // 已在根目录
    loadFiles(parentPath, dir, gen)
    loadFileStatuses(dir, gen)
}
```

- **预估工作量**：2 行

---

## 三、P2 应清理

### P2-1. Actions 自由函数文件命名债 + 内联

- **来源**：glmer 🟠 + kimo 🟠 + maxer 🟡

**修复方案（分两步）**：

**步骤 1：重命名文件**（快速，降低认知负担）
```
MainViewModelMessageActions.kt    → MessageActions.kt
MainViewModelCatchUpActions.kt    → CatchUpActions.kt
MainViewModelSessionListActions.kt → SessionListActions.kt
MainViewModelSessionActions.kt    → ProviderActions.kt（内容是 loadProviders 等）
MainViewModelSessionMutationActions.kt → SessionMutationActions.kt
MainViewModelConnectionActions.kt → ConnectionActions.kt（applySavedSettings 等）
```
更新所有 import（机械替换）。

**步骤 2（可选）：内联到 VM private 方法**
将每个 free function 的方法体移到对应 VM 的 `private fun`。删除 Actions 文件。降低参数传递开销（VM 内直接访问自己的字段）。

- **预估工作量**：小（步骤 1）~ 中（步骤 2）

---

### P2-2. AppCore 仍是中等耦合点

- **来源**：glmer 🟡 + dser 🟡 + kimo 🟡

**修复方案：按域拆分 effect dispatcher**

当前 AppCore.dispatchEffect 是 22 分支的大 `when`。按域拆为 5 个 dispatcher：

```kotlin
// ChatEffectDispatcher.kt
internal fun AppCore.dispatchChatEffect(effect: ControllerEffect) {
    when (effect) {
        is ControllerEffect.LoadMessages -> loadMessagesForEffect(effect.sessionId, effect.resetLimit)
        is ControllerEffect.ClearDeltaBuffers -> sessionSyncCoordinator.clearDeltaBuffers()
        // ... chat 域 effects
        else -> {} // 不属于本域
    }
}

// ConnectionEffectDispatcher.kt
internal fun AppCore.dispatchConnectionEffect(effect: ControllerEffect) { ... }

// 等等
```

AppCore.dispatchEffect 变为：
```kotlin
private fun dispatchEffect(effect: ControllerEffect) {
    dispatchChatEffect(effect)
    dispatchConnectionEffect(effect)
    dispatchSessionEffect(effect)
    dispatchHostEffect(effect)
    dispatchForegroundEffect(effect)
}
```

每个 dispatcher 只处理自己域的 effect（else no-op），不互相依赖。这降低了 AppCore 的认知复杂度，也使域内 effect 路由可独立测试。

跨域编排方法（sendMessage 等）保持在 AppCoreOrchestration.kt。

- **下次触发**：AppCore + Orchestration 超 600 行（当前已达上限）
- **预估工作量**：大

---

### P2-3. TestAppStateShim.kt 重引入 AppState

- **来源**：maxer 🟡

**修复方案：逐步迁移测试断言**

当前测试用 `core.state.value.sessions` 读聚合 AppState。迁移为：
```kotlin
// 旧：assertEquals(listOf(s1), core.state.value.sessions)
// 新：assertEquals(listOf(s1), core.store.sessionListFlow.value.sessions)
```

约 100 处断言需改写。可分批迁移（每次迁一个 test 文件），最终删除 TestAppStateShim.kt（370 行）。

- **预估工作量**：中（~100 处机械改写）

---

### P2-4. SSE 全纯 reducer + effect 通道

- **来源**：gpter 🟡 + dser 🟡

**修复方案：纯函数返回 `Pair<State, List<SideEffect>>`**

```kotlin
// 新增 SideEffect sealed class
sealed class SseSideEffect {
    data class ReloadMessages(val sessionId: String, val resetLimit: Boolean) : SseSideEffect()
    data object RefreshSessions : SseSideEffect()
    data object ServerConnected : SseSideEffect()
    data class ReportNonFatal(val message: String) : SseSideEffect()
}

// 纯函数改为返回 state + effects
internal fun ChatState.applySessionStatus(
    status: SessionStatus,
    sessionId: String
): Pair<ChatState, List<SseSideEffect>> {
    // ... 计算 newState
    val effects = if (status.type == "idle" && streamingPartTexts.isNotEmpty()) {
        listOf(SseSideEffect.ReloadMessages(sessionId, true))
    } else emptyList()
    return newState to effects
}
```

dispatcher 应用：
```kotlin
"session.status" -> {
    val (newChat, sideEffects) = slices.chat.value.applySessionStatus(status, sid)
    slices.chat.value = newChat
    sideEffects.forEach { effect -> /* emit or execute */ }
}
```

table-driven 测试扩展：验证 `applyXxx` 返回的 state AND effects。

- **预估工作量**：大

---

### P2-5. VM 通过 core 访问一切

- **来源**：kimo 🟡

**修复方案：VM 精确注入**

当前：
```kotlin
class ChatViewModel @Inject constructor(val core: AppCore) { ... }
```

改为：
```kotlin
class ChatViewModel @Inject constructor(
    private val store: SharedStateStore,
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val sessionSyncCoordinator: SessionSyncCoordinator,
    private val effects: SharedEffectBus,
) : ViewModel() {
    val chatFlow: StateFlow<ChatState> = store.chatFlow
    // 不再有 core.store.xxxFlow / core.repository / core.controller
}
```

需要解决：controller 实例化——当前 controllers 在 AppCore 构造时创建。改为各 VM 自己创建自己的 controller（注入所需 slice + deps）。跨域协调通过 SharedEffectBus。

- **预估工作量**：大（6 个 VM 构造器重写 + controller 创建分散化）

---

### P2-6. OrchestratorVM 角色过载

- **来源**：dser 🟡 + gpter

**修复方案：拆分 settings + traffic**

```kotlin
// 新增 SettingsViewModel（或合并到 ComposerVM）
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SharedStateStore,
    private val settingsManager: SettingsManager,
) : ViewModel() {
    fun setThemeMode(mode: ThemeMode) { ... }
    fun setMarkdownFontSizes(sizes: MarkdownFontSizes) { ... }
    fun setUiFontScale(scale: Float) { ... }
    fun setUiContentScale(scale: Float) { ... }
}

// traffic 合并到 ConnectionViewModel
class ConnectionViewModel ... {
    fun refreshTrafficStats() { ... }  // 从 OrchestratorVM 迁入
    fun resetTrafficStats() { ... }
}
```

OrchestratorVM 只保留 nav + file + permission/question + 跨域编排（resetLocalDataAndResync/openSessionFromDeepLink/executeCommand）。

- **预估工作量**：中（~60 行方法搬迁 + Composable 注入更新）

---

### P2-7. connectionPhase → sealed class

- **来源**：dser + kimo

**修复方案**：
```kotlin
// AppStateSlices.kt — ConnectionState
sealed class ConnectionPhase {
    data object Idle : ConnectionPhase()
    data class Connecting(val message: String = "connecting") : ConnectionPhase()
    data object Connected : ConnectionPhase()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : ConnectionPhase()
    data object Disconnected : ConnectionPhase()
}

data class ConnectionState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val serverVersion: String? = null,
    val connectionPhase: ConnectionPhase = ConnectionPhase.Idle,
    val tunnelActivationState: TunnelActivationState = TunnelActivationState.Idle,
)
```

ConnectionCoordinator 内部所有 `"connecting"` / `"connected"` 字符串赋值改为 sealed 实例。ChatTopBar/ChatScreen/ChatEmptyState 中的 `connectionPhase` 显示逻辑改为 `when (phase)` 分支（编译器保证完备性）。

- **预估工作量**：小（类型定义 + ~10 处字符串→sealed 改写 + ~5 处 UI when 分支）

---

## 四、Cosmetic / 防御性（可选）

| # | 问题 | 修复方案 |
|---|---|---|
| C-1 | applyPartDeltaLeadingEdge 双重载合并 | 合并为单函数 `applyPartDeltaLeadingEdge(partId, delta, partType: String?, msgId: String?, sessionId: String?)` — partType 为 null 时从 partsByMessage 查 |
| C-2 | expandedParts .value = 改 .update{} | 4 处 `expandedParts.value = ...` → `expandedParts.update { ... }` |
| C-3 | flushJobs 改 ConcurrentHashMap | `mutableMapOf` → `java.util.concurrent.ConcurrentHashMap`；保留 main-thread confined 文档 |
| C-4 | MissingTranslation lint 重新启用 | 删除 `disable += setOf("MissingTranslation")`；en/zh 已 100% 对齐 |
| C-5 | 中文注释密度过高 | 将 RFC 引用（§R-17 M5.1 等）迁移到 `docs/tech-debt/`；代码保留简洁英文注释 |
| C-6 | property-based testing | 加 kotest-property 依赖；为 22 个 applyXxx 生成 property test（如"任意 SessionListState + 任意 Session → applySessionUpsert 后 id 集合不变"） |
| C-7 | SettingsManager 接口 + draft 性能 | 提取 `interface SettingsRepository`；draft 改为 per-session 增量 JSON patch 而非全量 |
| C-8 | TrafficLogger 隐私脱敏 | URL 脱敏 query/path 中 session/file 片段；release 默认不落盘 traffic log |
| C-9 | PartStateSerializer 独立 + fuzz | 迁移到 `data/model/PartStateSerializer.kt`；加 fuzz test 覆盖 JsonPrimitive/JsonObject/JsonArray/null 分支 |
| C-10 | UI 大文件深度拆分 | ChatScreen 拆为 ChatRoute（状态收集）+ ChatScreenContent（纯参数）+ ChatScrollStrategy（独立可测） |

---

## 五、已闭环（不需再处理）

| # | 问题 | 闭环状态 |
|---|---|---|
| ✅ | 单 profile delete store 层防御 | UI 禁用 + runCatch + onHostProfileSwitched 三路径全覆盖 |
| ✅ | AppState 双写体系 | AppState.kt 删除，50+ mirror 字段清零 |
| ✅ | MainViewModel God Object | 删除，6 VM 拆分 |
| ✅ | 17 处 check(Looper) | 全部移除，改 CAS |
| ✅ | SSE delta 隐藏状态机 | 移入 ChatState slice + 22 纯函数 |

---

## 六、推荐执行顺序

```
Phase 1（止血，1-2 天）
├── P0-1 currentDirectory 彻底消除 ← 4 路共识最高优先
├── P0-3 VM/controller i18n ← 小改动大收益
├── P0-4 kover 接入 check.sh ← 1 行
├── P1-4 directoryFetchGeneration AtomicLong ← 1 行
├── P1-5 空文件误判修复 ← 小改动
└── P1-6 navigateUp 路径归一化 ← 2 行

Phase 2（加固，3-5 天）
├── P0-2 currentSessionId 双写收敛 ← collector 自动持久化
├── P1-1 SharedStateStore 写权限隔离 ← internal mutateXxx
├── P1-2 appScope → viewModelScope ← ephemeral 操作改 VM scope
└── P2-7 connectionPhase sealed class ← 独立小改

Phase 3（可靠性，1 周）
├── P1-3 tryEmit → Channel 分通道 ← 业务命令不可丢
├── P2-1 Actions 文件改名 ← 降低认知负担
└── P2-6 OrchestratorVM 拆子域 ← settings/traffic 分离

Phase 4（深度清理，按需）
├── P2-2 AppCore 按域拆 dispatcher
├── P2-3 TestAppStateShim 逐步删除
├── P2-4 SSE 全纯 reducer + SideEffect
├── P2-5 VM 精确注入（去 core 万能访问）
└── C-1~C-10 cosmetic 项
```

---

## 七、依赖关系图

```
P0-1 (currentDirectory) ──┐
                          ├──→ 无依赖（独立可做）
P0-3 (i18n) ──────────────┤
P0-4 (kover) ─────────────┤
P1-4 (AtomicLong) ────────┘

P0-2 (sessionId) ─────→ P1-1 (Store 写隔离) ──→ P2-5 (VM 精确注入)
                                              ↗
P1-2 (appScope) ──────────────────────────────

P1-3 (Channel) ──→ P2-4 (SSE reducer) ──→ P2-2 (AppCore 拆分)

P2-1 (改名) ──→ P2-3 (TestShim 删除)
P2-6 (OrchestratorVM) ──→ P2-7 (connectionPhase sealed)
```

**关键依赖**：
- P1-1（Store 写隔离）是 P2-5（VM 精确注入）的前置——写入口收敛后才能精确注入
- P1-3（Channel）是 P2-4（SSE reducer）的前置——reducer 的 SideEffect 需要 Channel 传递
- P0-1 和 P0-2 独立可并行
