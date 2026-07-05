# R-18 待解决问题（Pending Issues）

> 本文件是 ocdroid 项目**唯一的待解决问题清单**，取代已归档的 `refactor-R17-followup.md`。
> 来源：R-17 综合重构 5 批次延后项 + 4 路全局终审（kimo 8.9 / gpter 8.0 / dser 8.0 / glmer 7.5）新发现。
> 每项含：来源、证据、**具体修复方案（含代码骨架）**、预估工作量。
>
> **本文档已于 2026-07-06 经 gpter / glmer / maxer 三方 review-5 终审 reconcile 更新**，并完成 6 项不确定项验证：
> - 修正 11 项原方案的编译/运行期硬伤（标 `⚠️ 评审修正`）
> - 新增 13 项遗漏问题（P0-5~P0-9、P1-7~P1-11、P2-8~P2-10）
> - 重写阶段路线图（第六节）、依赖图（第七节）
> - 新增 kover 80% line 收尾规划（第八节）
> - 评审共识与裁决见第九节附录
> - 6 项不确定项已全部确认（第九节"已确认结论"）：header=`X-Opencode-Directory`、kover 不含 androidTest、Reconnecting 两种 pattern、tryEmit 36 处分类（A4/B13/C19）、P1-1 YAGNI、TestAppStateShim 76 处（非 150+）

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

> ⚠️ **评审修正（3/3 共识）**：
> 1. **调用链列举严重不全**。实际 main 代码 `setCurrentDirectory` **8 处**（不止 2 处）：OrchestratorVM ×3（141/144/157）、SessionVM ×1（92-95）、HostProfileController:359、SessionSwitcher:230、MainViewModelConnectionActions:38、AppCoreOrchestration:340。
> 2. **`getCurrentDirectory` 读取点完全漏列**：AppCoreOrchestration:64（materializeDraftSession）、OrchestratorVM（browseSavedDirectory 保存）。
> 3. **测试侧 mock 8+ 处**（ChatViewModelTest/SessionViewModelTest/SessionSwitcherTest/HostProfileControllerTest/OpenCodeRepositoryTest）——删 production 方法前必须先改测试，否则编译失败阻塞 check.sh。
> 4. **步骤 1 saved 还原逻辑错误**：`directorySessions.keys.firstOrNull()` 语义不对，应读 `settingsManager.currentWorkdir`。
> 5. **SSE/question/command 端点缺显式 directory**，删 fallback 后会路由回归。需先做 P0-3（新）directory 显式穿透。
> 6. **header 名称需核实**：文档写 `X-Opencode-Session-Directory`，实际项目用 `X-Opencode-Directory`（见 `HttpHeaders`）。
>
> **裁决**：采用**两步走**（最安全）——Phase 1 步骤 1 做显式化（不删 fallback），Phase 2 步骤 2 删全局状态（compiler 列遗漏）。

**修复方案（修正后两步走）**：

**步骤 1（Phase 1）：所有 HTTP/SSE 路径支持显式 directory，不删 fallback**

`OpenCodeApi` 的 question/command 端点加显式 header：
```kotlin
@GET("question")
suspend fun getPendingQuestions(
    @Header(HttpHeaders.DIRECTORY_HEADER) directory: String
): List<QuestionRequest>

@POST("question/{requestId}/reply")
suspend fun replyQuestion(
    @Header(HttpHeaders.DIRECTORY_HEADER) directory: String,
    @Path("requestId") requestId: String,
    @Body body: QuestionReplyRequest
): Response<Unit>

@POST("session/{id}/command")
suspend fun executeCommand(
    @Header(HttpHeaders.DIRECTORY_HEADER) directory: String,
    @Path("id") sessionId: String,
    @Body body: CommandRequest
): Response<Unit>
```

`SSEClient.connect()` 加 directory 参数（非 Retrofit，手动加 OkHttp request header）：
```kotlin
fun connect(baseUrl: String, username: String?, password: String?, directory: String?): Flow<Result<SSEEvent>> =
    connectOnce(baseUrl, username, password, directory)

// Request.Builder 中
.apply {
    if (!directory.isNullOrBlank()) header(HttpHeaders.DIRECTORY_HEADER, directory)
}
```

`OpenCodeRepository.connectSSE(directory)` 签名扩展。

文件浏览器 saved 还原修正：
```kotlin
// OrchestratorViewModel.kt
fun browseFilesInWorkdir(workdir: String) {
    // 保存当前持久化 workdir（不是 directorySessions 第一个 key）
    val saved = settingsManager.currentWorkdir
    core.store.fileFlow.update { it.copy(fileBrowserOpen = true, fileBrowserWorkdir = workdir) }
    // 不再调 repository.setCurrentDirectory(workdir)
}
fun closeFileBrowser() {
    core.store.fileFlow.update { it.copy(fileBrowserOpen = false, fileBrowserWorkdir = null) }
    // 不再调 repository.setCurrentDirectory(saved)
}
```

调用方（SSE 连接、question、command）从 session 显式取 directory：
```kotlin
val directory = store.sessionListFlow.value.sessions
    .firstOrNull { it.id == sessionId }?.directory
    ?: settingsManager.currentWorkdir
// 三级回退：session.directory → settingsManager.currentWorkdir → repository.getSession(id).directory
```

**步骤 2（Phase 2）：确认所有调用方都传显式 directory 后，删除全局可变状态**

先删 `setCurrentDirectory`/`getCurrentDirectory` 的定义，让 compiler 列出所有遗漏调用方。逐个改完后：
- 删除 `HostConfig._currentDirectory` / `setCurrentDirectory` / `currentDirectory`
- 删除 `OpenCodeRepository.setCurrentDirectory` / `getCurrentDirectory`
- `DirectoryHeaderInterceptor` 改为只从 per-request header 注入（无全局 fallback）
- 清理 8+ 处测试 mock

**步骤 3：createSessionInWorkdir 不设全局目录**
```kotlin
// SessionViewModel.kt — createSessionInWorkdir
// 删除 core.repository.setCurrentDirectory(workdir)
// 后续 API 调用显式传 directory=workdir
```

- **预估工作量**：中-大（~250 行，10+ 文件 + 5 测试文件）

---

### P0-2. currentSessionId 双写残存（SettingsManager + ChatState）

- **来源**：dser 🔴 + kimo 🟠
- **当前状态**：10 个写入口在 7 文件，非原子双写

> ⚠️ **评审修正（3/3 共识）**：**冷启动 seed 与 collector 时序冲突**。AppCore.init 已调用 `applySavedSettings` 种子（MainViewModelConnectionActions:60-72），若再 init 内加 collector，collector 可能在 seed 落地前读到默认 null 并写回 settingsManager，**覆盖冷启动 seed**。需 `drop { it == null }` 或确保 seed 同步完成后再注册 collector。

**修复方案（修正后）：ChatState 为唯一运行时源，SettingsManager 为冷启动种子 + 持久化副作用**

```kotlin
// AppCore.kt init 块中添加：
init {
    // 冷启动：从 SettingsManager 种子 ChatState（applySavedSettings 已做，这里只兜底）
    val persisted = settingsManager.currentSessionId
    if (persisted != null) {
        store.chatFlow.update { it.copy(currentSessionId = persisted) }
    }

    // 持久化副作用：chatFlow.currentSessionId 变化时写 SettingsManager
    // drop 防止 collector 读到初始值覆盖 seed
    appScope.launch(start = CoroutineStart.UNDISPATCHED) {
        store.chatFlow.map { it.currentSessionId }
            .drop { it == null }   // ← 评审修正：跳过初始 null
            .distinctUntilChanged()
            .collect { id ->
                settingsManager.currentSessionId = id
            }
    }
}
```

然后**删除全部 10 个手动 `settingsManager.currentSessionId = xxx` 调用点**（main 代码 10 处已确认：MainViewModelSessionMutationActions:109,185、SessionSyncCoordinator:202、HostProfileController:285、SessionSwitcher:170、AppCoreOrchestration:140,341、SessionViewModel:64,95、MainViewModelConnectionActions:65）。运行时只写 `store.chatFlow.update { it.copy(currentSessionId = ...) }`，持久化由 collector 自动完成。

- **预估工作量**：中（删 10 处手动写 + 加 1 个 collector + drop 修正）

---

### P0-3. VM/controller 层 i18n 漏洞

- **来源**：glmer 🔴 + kimo 🟠
- **当前状态**：`UiEvent.Error("隧道激活失败")` 等硬编码中文，lint 抓不到

> ⚠️ **评审修正（gpter 编译失败 + glmer 连带破坏）**：
> 1. **`stringResource()` 在 `collect` lambda 内编译失败**——`stringResource` 是 `@Composable`，collector lambda 不是 composable。必须用 `LocalContext.current.getString()`。
> 2. **硬编码 UiEvent 实际 22+ 处，不是 ~8 个**：HostProfileController 4、ConnectionCoordinator 3、SessionViewModel 1、ChatViewModel 6、AppCoreOrchestration 5、OrchestratorViewModel 1、SessionSyncCoordinator 1。
> 3. **AppLifecycleMonitor 接口会崩**：`AppCore.kt:194-195` `uiEvents.collect { if (event is UiEvent.Error) appLifecycleMonitor.onAppError(event.message) }`——改 `@StringRes` 后 `event.message` 不存在，`onAppError(Int)` 会把 resId 数字当错误上报崩溃监控。**必须同步改接口**（见 P0-6）。
> 4. **TestAppStateShim 会失效**：`core.state.value.error/successMessage`（String）有 **12 处测试断言依赖**。改 UiEvent 后 shim 失去数据源，必须同步改写（与 P2-3 关联）。
> 5. **strings.xml 实际 18-20 个 key**（含动态参数 `"隧道激活失败：$msg"`、`"Failed to create session in $draftWorkdir"`、`"Command /$cmd failed"`），不是 8 个。

**修复方案（修正后）：UiEvent 携带 @StringRes + Composable 层用 Context 解析**

```kotlin
// UiEvent.kt
sealed class UiEvent {
    data class Error(@StringRes val resId: Int, val args: List<Any> = emptyList()) : UiEvent()
    data class Success(@StringRes val resId: Int, val args: List<Any> = emptyList()) : UiEvent()
}
```

```kotlin
// ChatScreen.kt — uiEvents collector（评审修正：用 LocalContext，不用 stringResource）
LaunchedEffect(Unit) {
    val context = LocalContext.current   // ← 在 composable 内取
    orchestratorVM.uiEvents.collect { event ->
        val message = when (event) {
            is UiEvent.Error -> context.getString(event.resId, *event.args.toTypedArray())
            is UiEvent.Success -> context.getString(event.resId, *event.args.toTypedArray())
        }
        // show snackbar with message
    }
}
```

**同步改 AppLifecycleMonitor 接口**（见 P0-6）：要么 `onAppError(@StringRes resId: Int, args: List<Any>)`，要么 AppCore 内预解析为 String 再传。

调用方改写示例：
```kotlin
// 旧：eventEmitter.emit(UiEvent.Error("子任务会话不可用"))
// 新：eventEmitter.emit(UiEvent.Error(R.string.error_child_session_unavailable))

// 旧：UiEvent.Error("隧道激活失败：$msg")
// 新：UiEvent.Error(R.string.error_tunnel_activation_failed, listOf(msg))
```

strings.xml 需新增 ~18-20 个 key（中英双语，含 format args）。调用点 22+ 处全改。TestAppStateShim 12 处断言同步改写。

注意：`EventEmitter` 接口签名不变（仍 `fun emit(event: UiEvent)`），只是构造 UiEvent 的方式变了。

- **预估工作量**：中（~80 行改动 + 18-20 string key + AppLifecycleMonitor 接口 + TestAppStateShim 12 处）——非"小，~30 行"

---

### P0-4. kover 门控在主路径失效

- **来源**：glmer 🔴

> ⚠️ **评审修正（3/3 共识）**：描述不准。`build.gradle.kts:128` **已 wire** `tasks.named("check").configure { dependsOn("koverVerify") }`，问题在于 `scripts/check.sh` **不调 `check` task**（只跑 `compileDebugKotlin + testDebugUnitTest`），CI 走 `./gradlew check` 才生效。应描述为"check.sh 路径未接入"，不是"主路径失效"。

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

阈值提升路径见第八节 kover 80% 收尾规划（阶梯 25% → 80%，不是一次拉到 80）。

- **预估工作量**：1 行（check.sh）+ 持续（阈值阶梯提升，见第八节）

---

### P0-5. SSE/question/command 端点缺显式 directory header（评审新增，gpter + maxer）

- **来源**：gpter 🔴 + maxer 🔴（评审新增）
- **当前状态**：`OpenCodeApi` 的 question/command 端点当前特意不 `Skip-Dir`（OpenCodeApi.kt:110-127、:150-162），靠全局 `_currentDirectory` fallback；SSEClient 不是 Retrofit，靠 OkHttp interceptor 注入全局目录。
- **影响**：P0-1 删除全局 fallback 后，这些路径会路由回 server cwd 或错误 InstanceState。这是 P0-1 步骤 2（删 fallback）的**前置**。

**修复方案**：见 P0-1 步骤 1 的代码骨架（OpenCodeApi 加 `@Header(DIRECTORY_HEADER)`、SSEClient.connect(directory)）。

- **预估工作量**：与 P0-1 步骤 1 合并

---

### P0-6. AppLifecycleMonitor.onAppError 接口在 i18n 后崩（评审新增，glmer）

- **来源**：glmer 🔴（评审新增）
- **当前状态**：`AppCore.kt:194-195` `uiEvents.collect { if (event is UiEvent.Error) appLifecycleMonitor.onAppError(event.message) }`。P0-3 改 `UiEvent.Error(@StringRes resId)` 后，`event.message` 不存在，`onAppError(Int)` 会把 resId 数字当错误消息上报崩溃监控。
- **影响**：P0-3 i18n 改造的连带破坏，不处理则编译失败或崩溃监控污染。

**修复方案**：AppCore 内预解析为 String 再传，或改接口：
```kotlin
// 方案 A：AppCore 预解析（推荐，接口改动小）
uiEvents.collect { event ->
    if (event is UiEvent.Error) {
        val message = context.getString(event.resId, *event.args.toTypedArray())
        appLifecycleMonitor.onAppError(message)
    }
}

// 方案 B：改接口
interface AppLifecycleMonitor {
    fun onAppError(@StringRes resId: Int, args: List<Any>)
}
```

- **预估工作量**：小（与 P0-3 同批做）

---

### P0-7. SSE unknown event type 静默 drop（评审新增，maxer）

- **来源**：maxer 🔴（评审新增）
- **当前状态**：`SessionSyncCoordinator` 主 `when (type)` 分支的 else 静默 drop 未识别事件，无 log、无 metrics、无 UiEvent。
- **影响**：服务端新增 event type（如 v2 heartbeat、session.compacted）时静默失败，生产事故（"对话突然停止更新"）需 1-2 小时定位。

**修复方案**：
```kotlin
// SessionSyncCoordinator.handleEvent — else 分支
} else {
    DebugLog.w("SSE", "unrecognized event type=$type session=${sid ?: "-"} payload-keys=${event.payload.keys.take(5)}")
    if (BuildConfig.DEBUG) effects.uiEvents.tryEmit(UiEvent.Debug("unknown SSE: $type"))
    unknownEventCounters.merge(type, 1, Integer::sum)
}
```
加 `unknownEventCounters: ConcurrentMap<String, AtomicInteger>` 字段，定期上报 CrashLogger。

- **预估工作量**：小（1 处 + 1 字段）

---

### P0-8. release signingConfig 默认值导致 release 构建崩（评审新增，glmer）

- **来源**：glmer 🔴（评审新增）
- **当前状态**：`build.gradle.kts:57` `storeFile = file(props.getProperty("release.storeFile", "release.keystore"))`——默认 `release.keystore` 在仓库 gitignored，新机器/CI 没 local.properties 时 `assembleRelease` 会崩。
- **影响**：CI/新机 release 构建失败。

**修复方案**：
```kotlin
signingConfigs {
    create("release") {
        val props = Properties()
        val propsFile = rootProject.file("local.properties")
        if (propsFile.exists()) props.load(propsFile.inputStream())
        val storeFilePath = props.getProperty("release.storeFile")
        if (storeFilePath != null && file(storeFilePath).exists()) {
            storeFile = file(storeFilePath)
            storePassword = props.getProperty("release.storePassword", "")
            keyAlias = props.getProperty("release.keyAlias", "release")
            keyPassword = props.getProperty("release.keyPassword", "")
        } else {
            logger.warn("release keystore not configured — release APK will not be signed")
        }
    }
}
```

- **预估工作量**：小

---

### P0-9. SharedStateStore 的 MutableStateFlow 仍 public（评审新增，maxer + gpter + glmer）

- **来源**：maxer P0-N1 + gpter + glmer（3/3 共识）
- **当前状态**：`SharedStateStore.kt:28-40` 所有 slice 是 `val xxx: MutableStateFlow<...> = MutableStateFlow(...)` **直接 public 暴露**。任何类都能 `.update{}`。grep 实证 22+ 处直接写 slice。
- **影响**：这是 P1-1（写权限隔离）的根因。不先修这个，P1-1 的任何隔离方案都是空操作。

**修复方案（前置步骤，Phase 2）**：
```kotlin
// SharedStateStore.kt
@Singleton
class SharedStateStore @Inject constructor() {
    private val _connectionFlow = MutableStateFlow(ConnectionState())
    val connectionFlow: StateFlow<ConnectionState> = _connectionFlow.asStateFlow()

    private val _chatFlow = MutableStateFlow(ChatState())
    val chatFlow: StateFlow<ChatState> = _chatFlow.asStateFlow()

    // ... 其余 7 slice 同理

    // public 写入口（不 internal —— 单 module 下 internal 无效）
    fun mutateConnection(transform: (ConnectionState) -> ConnectionState) = _connectionFlow.update(transform)
    fun mutateChat(transform: (ChatState) -> ChatState) = _chatFlow.update(transform)
    // ... 其余 7 个
}
```

VM/controller 调用处：~50 处 `xxxFlow.update { }` 改写为 `mutateXxx { }`。

真正的隔离（module 拆分或接口）见 P1-1（Phase 4 深度）。

- **预估工作量**：中（~60 行 + ~50 处 call site 改写）

---

## 二、P1 应修复

### P1-1. SharedStateStore 写权限无编译期隔离

- **来源**：gpter 🟠 + dser 🟠 + kimo 🟡 + glmer 🟡

> ❌ **评审修正（3/3 共识：原方案技术错误）**：原方案用 `internal fun mutateXxx`，但本项目 `settings.gradle.kts` 只有 `:app` 单一 Gradle module，Kotlin `internal` = **module-visible**（不是 package-visible）。`data/`、`di/`、`util/` 与 `ui/` 同 module，**data 层照样能调 `store.mutateXxx(...)`**。"编译器保证其他模块不能写"不成立。
>
> ✅ **oracle 架构决策（已确认：YAGNI）**：oracle 实测 data/di/util 层**零 import ui 包、零写 slice**（依赖方向天然 `ui → data`，非反向）。P0-9（private MutableStateFlow + public mutateXxx）已解决问题——把写操作收敛到命名方法，所有写手（全在 ui/ 内）走 `mutateXxx()` 即可。
>
> 三方案评估：
> - **方案 A（拆 module）**：有结构性陷阱——`internal` 会误伤同 module 的合法写手（controllers/VMs），解决代价等于拆半个 app。**不可行**。
> - **方案 B（接口隔离 ReadOnlyStateStore）**：改动小、Hilt 友好，但隔离的对象（data/di）本来就不写，属于"为不存在的威胁买单"。
> - **方案 C（Detekt 规则）**：可绕过，且无违规时无价值。
>
> **裁决：不做 P1-1 步骤 B。P0-9 即终态。** 仅在触发条件出现时升级：(1) data/di 层实际需要读 slice（改用方案 B 接口）；(2) 项目拆成多 feature module（此时方案 A 可行，写手与 store 同 module 无误伤）。

---

### P1-2. appScope vs viewModelScope 语义混淆

- **来源**：dser 🟠 + kimo 🟡

> ⚠️ **评审修正（maxer 分类细化 + gpter 闭包泄漏）**：
> 1. **原则需按"操作是否需跨 VM 生命周期"分类**：`loadMessages`/`selectSession`/`compactSession`/`abortSession` → viewModelScope；`materializeDraftSession`/`createSession`/`forkSession` → appScope（创建是关键事务，VM 死不能半途）。
> 2. **appScope job 捕获 VM 方法引用（`::selectSession`）存在 VM 延寿风险**（SessionViewModel:86-89,121-124,141-145）——长请求时 appScope job 持有 VM 实例，VM clear 后仍可回调。需一并修（见 P1-7）。
> 3. **不止 Actions 自由函数**：VM 内联 `core.appScope.launch` 也需改（如 ChatViewModel:80-160）。需先枚举所有 `core.appScope.launch` 调用点。

**修复方案（修正后）：区分 ephemeral（viewModelScope）与 persistent（appScope）操作**

原则：
- **用户交互触发的操作**（loadMessages, selectSession, compactSession, sendMessage, setInputText...）→ `viewModelScope`（VM 清除时取消）
- **关键事务**（materializeDraftSession, createSession, forkSession）→ `appScope`（不能 VM 死就半途）
- **后台持续操作**（SSE collection, foreground catch-up polling, delta flush timer）→ `appScope`（进程级，正确）

实现：Actions 自由函数已经接收 `scope: CoroutineScope` 参数。各 VM 调用时按分类传 `viewModelScope` 或 `core.appScope`。

**注意**：SessionSyncCoordinator 和 ConnectionCoordinator 的内部协程保持 `appScope`。appScope 内不闭包 VM 引用（P1-7）。

- **预估工作量**：中（~20 处 `core.appScope` → `viewModelScope` 改写 + 先枚举调用点）

---

### P1-3. tryEmit 关键 effect 可静默丢失

- **来源**：gpter 🟠 + maxer 🟡 + kimo 🟡

> ⚠️ **评审修正（3/3 共识：原 Channel 方案有顺序风险 + 工作量低估）**：
> 1. **tryEmit(ControllerEffect) 实际 36 处，不是 ~22 处**：HostProfileController **14 处**、ConnectionCoordinator 7、SessionSyncCoordinator 6、SessionSwitcher 5、ForegroundCatchUpController 4。
> 2. **大量调用在非 suspend 函数**（如 ConnectionCoordinator.loadInitialData() 是普通 fun，内部 5 个 tryEmit）。改 `suspend emitEffect` 后必须 `scope.launch { emitEffect(...) }`——**不同 launch 之间无序**，SSE 状态机对 effect 顺序敏感（LoadSessions 必须先于 LoadMessages）。
> 3. **主线程背压死锁风险**（maxer）：Channel 满 + dispatcher 慢时，producer 在 Main.immediate 上 `send` 挂起 → UI 冻屏。
> 4. **Channel(capacity=128) 是 BUFFERED**，不是 UNLIMITED，原描述有误。
>
> **裁决**：放弃原 Channel 方案，采用 **SharedFlow + BufferOverflow.SUSPEND** 轻量替代（gpter + glmer 推荐），保持 FIFO 顺序，避免 launch 重排。**exp-1 实测确认 19 处 C 类顺序敏感**（HostProfileController 4 组 + ConnectionCoordinator.loadInitialData 5 连发 + SessionSwitcher.switchTo 8 步），进一步证实 Channel+launch 会破坏顺序。

**修复方案（修正后）：SharedFlow 满时挂起而非丢弃 + 关键路径改 suspend emit**

```kotlin
// SharedEffectBus.kt
@Singleton
class SharedEffectBus @Inject constructor() {
    // UI 事件：可丢（DROP_OLDEST 显式声明，UI feedback 是 ephemeral）
    private val _uiEvents = MutableSharedFlow<UiEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    // 业务命令：满时挂起 producer（不丢，保持 FIFO）
    private val _effects = MutableSharedFlow<ControllerEffect>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    val effects: SharedFlow<ControllerEffect> = _effects.asSharedFlow()

    private val droppedEffects = java.util.concurrent.atomic.AtomicLong(0)

    // suspend emit — 调用方必须在协程中（保持顺序）
    suspend fun emitEffect(effect: ControllerEffect) = _effects.emit(effect)

    // 非 suspend 后备路径（带监控）
    fun tryEmitEffect(effect: ControllerEffect): Boolean {
        val ok = _effects.tryEmit(effect)
        if (!ok) {
            droppedEffects.incrementAndGet()
            DebugLog.w("EffectBus", "dropped effect=$effect")
        }
        return ok
    }

    suspend fun emitUiEvent(event: UiEvent) = _uiEvents.emit(event)
    fun tryEmitUiEvent(event: UiEvent): Boolean = _uiEvents.tryEmit(event)

    fun droppedEffectCount(): Long = droppedEffects.get()
}
```

Controller 侧改写：oracle 先分类"关键路径"（业务命令，改 `emit` suspend）vs"非关键"（保留 `tryEmitEffect` + log）。关键路径在已有 `scope.launch` 内的，直接改 `emit`；非 suspend 上下文的，评估是否值得 `scope.launch { emit(...) }`（注意顺序）。

- **预估工作量**：中（SharedEffectBus 重写 + 36 处分类改写 + dropped counter）。**注意 OnSseEvent 高频路径保持 SharedFlow 不变**。

---

### P1-4. ConnectionCoordinator.directoryFetchGeneration 非原子

- **来源**：kimo 🔴

> ⚠️ **评审修正（3/3 共识：优先级过高，降级为 P2/C）**：`ConnectionCoordinator.kt:98-101` 注释明确"Reads/writes are on the main thread (reconfigure + loadInitialData both run there); @Volatile is belt-and-suspenders." 单线程 main dispatcher 上 `++` 不会乱序。改 AtomicLong 是无害防御性改写，但**不是 P1 严重 bug**。

**修复方案**（保留，但降级优先级）：
```kotlin
// ConnectionCoordinator.kt
// 旧：@Volatile private var directoryFetchGeneration: Long = 0
// 新：private val directoryFetchGeneration = java.util.concurrent.atomic.AtomicLong(0)

// 旧：++directoryFetchGeneration
// 新：directoryFetchGeneration.incrementAndGet()

// 旧：directoryFetchGeneration
// 新：directoryFetchGeneration.get()
```

调用点：cancelSseForReconfigure（:487）、loadInitialData（:298）、appendDirectorySessions/reportNonFatalIssue（:316,326）。

- **预估工作量**：1 行声明 + ~4 处调用改写。降级为 Phase 3 cosmetic。

---

### P1-5. FilesViewModel 空文件误判为目录

- **来源**：kimo 🟠 + maxer 🟡

> ⚠️ **评审修正（3/3 共识：不应靠探测）**：
> 1. 原方案空 content 时再 fetch `getFileTree` 探测——**每个空文件预览打两次网络请求**。
> 2. `FileContent(content = "")` 不编译（gpter：构造函数需 `type` 参数）。
> 3. 空目录 `getFileTree` 返回空列表时也会走 else 当文件处理。
> 4. 实际代码 FilesViewModel.kt:166-186 **已经**在 getFileContent onSuccess 空 content 时 fall through 到 loadDirectoryPreview——部分修复已存在。
>
> **裁决**：让调用方传递 `FileNode.isDirectory` 信号（FileNode 已有此字段），loadPreview 接收 flag 直接决定路径，避免探测。

**修复方案（修正后）：用 FileNode.isDirectory 信号，不探测**

```kotlin
// FilesViewModel.kt — selectFile / syncPathToShow 调用方传 isDirectory 信号
fun selectFile(node: FileNode) {
    loadPreview(
        pathToShow = node.path,
        sessionDirectory = _state.value.workdir ?: return,
        isRefresh = false,
        isDirectory = node.isDirectory   // ← 显式信号
    )
}

private fun loadPreview(
    pathToShow: String,
    sessionDirectory: String,
    isRefresh: Boolean,
    isDirectory: Boolean   // ← 接收信号
) {
    if (isDirectory) {
        loadDirectoryPreview(pathToShow, sessionDirectory, "", isRefresh, requestGeneration.incrementAndGet())
        return
    }
    viewModelScope.launch {
        val content = repository.getFileContent(sessionDirectory, pathToShow).getOrNull()
        updateFilePreview(content ?: FileContent(type = "text", content = ""))
    }
}
```

同时修正 `FilesViewModelTest.kt:132-155`。

- **预估工作量**：小（~15 行逻辑 + 1 个测试修正）

---

### P1-6. FilesViewModel.navigateUp 路径归一化缺陷

- **来源**：kimo 🟠

> ⚠️ **评审修正（gpter + glmer：漏状态更新 + 多加副作用）**：
> 1. 原方案**漏了** `_state.update { it.copy(currentPath = parentPath) }`——UI currentPath 不更新。
> 2. 原方案**多加了** `loadFileStatuses(dir, gen)`——当前 navigateUp 不刷新 statuses，这是新增功能，可能引入额外网络请求 + gen 双 bump 竞态。删除。

**修复方案（修正后）**：
```kotlin
// FilesViewModel.kt
fun navigateUp() {
    val dir = _state.value.workdir ?: return
    val currentPath = _state.value.currentPath
    val normalized = currentPath.trimEnd('/')
    if (normalized.isEmpty() || normalized.substringBeforeLast('/', "").isEmpty()) return  // 已在根目录
    val parentPath = normalized.substringBeforeLast("/", "")
    _state.update { it.copy(currentPath = parentPath) }   // ← 评审修正：必须保留
    val gen = requestGeneration.incrementAndGet()
    loadFiles(parentPath, dir, gen)
    // 不调 loadFileStatuses（原方案多加的副作用）
}
```

- **预估工作量**：2-3 行

---

### P1-7. appScope job 捕获 VM 方法引用，VM 延寿风险（评审新增，maxer + gpter）

- **来源**：maxer P1-N1 + gpter P1-7（评审新增）
- **当前状态**：`SessionViewModel.kt:86-89,121-124,141-145` 将 `::selectSession` 传入 `core.appScope` 启动的任务。
- **影响**：长请求或挂起时，appScope job 持有 VM 实例，VM clear 后仍可回调已销毁 VM，造成内存泄漏或 UI 生命周期语义错乱。

**修复方案**：与 P1-2 同批做。用户动作改 viewModelScope；appScope 内不闭包 VM 引用，改用独立 suspend fun 或 lambda 不捕获 VM。

```kotlin
// 优先：用户动作传 viewModelScope
launchCreateSession(
    scope = viewModelScope,
    repository = core.repository,
    slices = core.store.slices,
    title = title,
    onSelectSession = { id -> core.sessionSwitcher.switchTo(id) },  // 不捕获 VM
    emit = EventEmitter { core.effectBus.emitUiEvent(it) }
)
```

- **预估工作量**：~10 处改写（与 P1-2 合并）

---

### P1-8. EffectBus 缺丢弃监控（评审新增，gpter）

- **来源**：gpter P1-8（评审新增）
- **当前状态**：`tryEmit` 丢失时无日志、无计数、无测试。
- **影响**：即使 P1-3 改 SharedFlow + SUSPEND，也需要知道是否发生拥塞。

**修复方案**：见 P1-3 的 `droppedEffects` AtomicLong + DebugLog.w。

- **预估工作量**：与 P1-3 合并

---

### P1-9. 多 workdir pending questions 只轮询当前目录（评审新增，gpter）

- **来源**：gpter P0-6（评审新增）
- **当前状态**：删除 currentDirectory 后，若只按 `currentSessionId.directory` 调 `getPendingQuestions(directory)`，后台 workdir 的 question 会不可见。
- **影响**：多 project/workdir 场景下，后台 workdir 的 permission/question 静默丢失。

**修复方案**：
```kotlin
data class PendingQuestionEnvelope(
    val directory: String,
    val request: QuestionRequest
)

suspend fun loadPendingQuestionsForKnownDirectories() {
    val dirs = store.sessionListFlow.value.directorySessions.keys +
        listOfNotNull(settingsManager.currentWorkdir)
    dirs.distinct().forEach { dir ->
        repository.getPendingQuestions(dir).onSuccess { questions ->
            store.sessionListFlow.update {
                it.copy(
                    pendingQuestions = mergeByIdKeepingDirectory(
                        old = it.pendingQuestions,
                        directory = dir,
                        incoming = questions
                    )
                )
            }
        }
    }
}
```

- **预估工作量**：小-中

---

### P1-10. SSE 重连/补帧无统一 gap reconciliation 状态机（评审新增，gpter）

- **来源**：gpter P1-9（评审新增）
- **当前状态**：SSEClient 有 retry/heartbeat；ForegroundCatchUpController 与 SessionSyncCoordinator 各自处理 catch-up/delta。缺少统一 invariant：断线期间哪些 session 必须 reload、哪些 delta buffer 必须 flush/clear。
- **影响**：SSE 重连后可能同时发生：delta buffer 未清、LoadMessages effect 丢失、currentSession 切换、session.status idle 触发重复 reload。

**修复方案**：
```kotlin
data class SseSyncState(
    val connectedOnce: Boolean = false,
    val lastDisconnectAt: Long? = null,
    val sessionsDirty: Set<String> = emptySet()
)

sealed class SseSyncDecision {
    data class ReloadSession(val sessionId: String) : SseSyncDecision()
    data object RefreshSessions : SseSyncDecision()
    data object ClearDeltaBuffers : SseSyncDecision()
}
```

为以下场景加表驱动测试：delta 中断后 reconnect；reconnect 后先收到 message.updated 再收到 session.status idle；currentSession 在断线期间切换；host reconfigure 后旧 SSE late event 到达。

- **预估工作量**：中-大

---

### P1-11. TrafficLogger 隐私脱敏（从 C-8 升级，gpter）

- **来源**：gpter（原 C-8，应升 P1）
- **当前状态**：`TrafficLogger` URL/path 中可能含 session/file 片段；release 默认落盘 traffic log。
- **影响**：隐私问题，不只是 cosmetic。

**修复方案**：
```kotlin
interface AppLogger {
    fun d(category: LogCategory, message: () -> String)
    fun w(category: LogCategory, throwable: Throwable? = null, message: () -> String)
}

enum class LogCategory { SSE, HTTP, STATE, EFFECT, SECURITY }

fun redactPathLike(input: String): String = ...
fun redactUrl(url: String): String = ...
```

release 默认不落盘 traffic log。

- **预估工作量**：中

---

## 三、P2 应清理

### P2-1. Actions 自由函数文件命名债 + 内联

- **来源**：glmer 🟠 + kimo 🟠 + maxer 🟡

> ⚠️ **评审修正（glmer：漏列 MainViewModelSupport.kt）**：实际 **7 个文件**，原方案列 6 个。且 `MainViewModelSessionActions.kt → ProviderActions.kt` 命名误导（文件内不只 loadProviders，含 switchSessionModel/loadAgents 等）。命名应基于文件实际内容，不依赖 R-17 概念。

**修复方案（步骤 1：重命名文件，快速）**
```
MainViewModelMessageActions.kt         → MessageActions.kt
MainViewModelCatchUpActions.kt         → CatchUpActions.kt
MainViewModelSessionListActions.kt     → SessionListActions.kt
MainViewModelSessionActions.kt         → 按实际内容定名（含 loadProviders/switchSessionModel/loadAgents）
MainViewModelSessionMutationActions.kt → SessionMutationActions.kt
MainViewModelConnectionActions.kt      → ConnectionActions.kt（applySavedSettings 等）
MainViewModelSupport.kt                → 按实际内容定名（评审补列）
```
更新所有 import（机械替换）。

**步骤 2（可选，Phase 4）：内联到 VM private 方法**——与 P2-5 大量冲突，不应同批做。

- **预估工作量**：小（步骤 1）~ 中（步骤 2）

---

### P2-2. AppCore 仍是中等耦合点

- **来源**：glmer 🟡 + dser 🟡 + kimo 🟡

> ⚠️ **评审修正（gpter：extension 不能访问 private + else no-op 掩盖未处理 effect）**：
> 1. `internal fun AppCore.dispatchChatEffect(...)` 作为扩展函数**不能访问 AppCore 的 private 成员**；当前 `dispatchEffect` 可访问私有 helper 是因为在类内。若拆文件，要么把 dispatcher 做成 AppCore 成员/内部类，要么把被调用方法改 internal。
> 2. 每个 dispatcher `else -> {}`，顶层依次调用 5 个 dispatcher，会让**新增 effect 未被任何域处理时静默丢失**。
>
> **裁决**：dispatcher 做成 AppCore 的 internal 成员函数（不是扩展）；dispatcher 返回 Boolean，顶层 `assertExactlyOneHandled`。

**修复方案（修正后）：按域拆分 effect dispatcher，返回 handled**

```kotlin
// AppCore 内部成员函数（非扩展）
private fun dispatchChatEffect(effect: ControllerEffect): Boolean {
    return when (effect) {
        is ControllerEffect.LoadMessages -> { loadMessagesForEffect(effect.sessionId, effect.resetLimit); true }
        is ControllerEffect.ClearDeltaBuffers -> { sessionSyncCoordinator.clearDeltaBuffers(); true }
        // ... chat 域 effects
        else -> false
    }
}

private fun dispatchEffect(effect: ControllerEffect) {
    val handled = dispatchChatEffect(effect) ||
        dispatchConnectionEffect(effect) ||
        dispatchSessionEffect(effect) ||
        dispatchHostEffect(effect) ||
        dispatchForegroundEffect(effect)
    if (!handled) {
        DebugLog.w("AppCore", "unhandled effect=$effect")
        // 或 assert(false) 在 debug
    }
}
```

跨域编排方法（sendMessage 等）保持在 AppCoreOrchestration.kt。

- **下次触发**：AppCore + Orchestration 超 600 行（当前已达上限）
- **预估工作量**：大

---

### P2-3. TestAppStateShim.kt 重引入 AppState

- **来源**：maxer 🟡

> ⚠️ **评审修正（glmer：工作量低估 + 隐含依赖）→ 已 explorer 实测确认**：
> 1. 实际访问面：**`core.state.value.*` 读点 12 处**（error/successMessage）+ **`core.updateState { copy(...) }` 写点 64 处**（112 字段赋值，跨 18 个 AppState 字段）。**不是"150+ 处"**——glmer 的 150+ 估计把 `FilesViewModelTest.kt` 的 27 次 `viewModel.state.value.*` 误算进来（那是 FilesViewModel **自身**的 StateFlow，与 shim 无关）。
> 2. **必须在 P0-3 之后做**（P0-3 改 UiEvent 会让 shim 失效），原依赖图没标这个隐含依赖。
> 3. **无跨 slice 派生状态断言**——除 `error`/`successMessage`（UiEvent 派生，需改 Turbine 测 `core.uiEvents`），其余 1:1 映射到 slice，可机械迁移。
> 4. 影响文件仅 **5 个**：ChatViewModelTest（62 点）、SessionViewModelTest（31）、OrchestratorViewModelTest（25）、HostViewModelTest（4）、ComposerViewModelTest（2）。

**修复方案：逐步迁移测试断言**

```kotlin
// 旧：assertEquals(listOf(s1), core.state.value.sessions)
// 新：assertEquals(listOf(s1), core.store.sessionListFlow.value.sessions)

// error/successMessage 需改 Turbine：
core.uiEvents.test {
    assertEquals(UiEvent.Error("network error"), awaitItem())
}
```

12 处读点 + 64 处 updateState 改写（error/successMessage 用 Turbine，其余机械迁移）。分批迁移（每次迁一个 test 文件），最终删除 TestAppStateShim.kt（370 行）。迁完做 usage 审计。

- **预估工作量**：中（~76 处改写 + Turbine 改造 error/successMessage 12 处）——比"150+"乐观

---

### P2-4. SSE 全纯 reducer + effect 通道

- **来源**：gpter 🟡 + dser 🟡

> ⚠️ **评审修正（gpter：.value = 非原子，需 actor 串行化）**：原骨架 `slices.chat.value = newChat` 与当前 `MutableStateFlow.update` 风格不一致，且**并发 SSE 会丢更新**。应采用 CAS/updateAndGet 风格，或单线程事件 actor 串行化 SSE reducer。maxer 补充：SideEffect 走 Channel 还是 SharedFlow 需明确——业务命令（ReloadMessages）走 emitEffect，UI 反馈（ReportNonFatal）走 uiEvents.tryEmit。

**修复方案（修正后）：纯函数返回 Pair<State, List<SideEffect>> + CAS 提交**

```kotlin
sealed class SseSideEffect {
    data class ReloadMessages(val sessionId: String, val resetLimit: Boolean) : SseSideEffect()
    data object RefreshSessions : SseSideEffect()
    data object ServerConnected : SseSideEffect()
    data class ReportNonFatal(val message: String) : SseSideEffect()
}

internal fun ChatState.applySessionStatus(
    status: SessionStatus,
    sessionId: String
): Pair<ChatState, List<SseSideEffect>> {
    // ... 计算 newState + effects
    return newState to effects
}
```

dispatcher 应用（CAS 提交）：
```kotlin
"session.status" -> {
    var sideEffects: List<SseSideEffect> = emptyList()
    slices.chat.update { current ->
        val (newChat, effects) = current.applySessionStatus(status, sid)
        sideEffects = effects
        newChat
    }
    sideEffects.forEach { effect ->
        when (effect) {
            is SseSideEffect.ReloadMessages -> effects.emitEffect(ControllerEffect.LoadMessages(effect.sessionId, effect.resetLimit))
            is SseSideEffect.ReportNonFatal -> effects.tryEmitUiEvent(UiEvent.Debug(effect.message))
            // ...
        }
    }
}
```

table-driven 测试扩展：验证 `applyXxx` 返回的 state AND effects。

- **预估工作量**：大

---

### P2-5. VM 通过 core 访问一切

- **来源**：kimo 🟡

> ⚠️ **评审修正（依赖图错标 + 工作量低估）**：
> 1. 原依赖图说"P1-1 是 P2-5 前置"——但 P1-1 的 internal 方案在单 module 不生效。P2-5 真正的前置是 **P0-9（MutableStateFlow private + public mutateXxx）**（P1-1 步骤 B 已 YAGNI 裁决不做）。
> 2. "各 VM 自己创建 controller"会改变 controller 生命周期：当前 controllers 在 AppCore.kt:118-175 是 singleton appScope 级；分散到 VM 后可能出现重复 controller、重复 collector、重复 SSE/catch-up 状态。**必须先定义哪些 controller 是 app-level，哪些是 VM-level**。

**修复方案：VM 精确注入（明确 controller 生命周期层级）**

```kotlin
class ChatViewModel @Inject constructor(
    private val store: SharedStateStore,
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val sessionSyncCoordinator: SessionSyncCoordinator,  // app-level
    private val effects: SharedEffectBus,
) : ViewModel() {
    val chatFlow: StateFlow<ChatState> = store.chatFlow
}
```

需先解决：controller 实例化分层（app-level singleton vs VM-level scoped）。

- **预估工作量**：大（6 个 VM 构造器重写 + controller 创建分层化）

---

### P2-6. OrchestratorVM 角色过载

- **来源**：dser 🟡 + gpter

> ⚠️ **评审修正（maxer：settings 不应合并到 ComposerVM）**：settings 是 global UI preference，composer 是输入/草稿，语义不对。建议 SettingsViewModel 独立；traffic 合并到 ConnectionViewModel（traffic 与 connection 都是 connectivity 状态）。

**修复方案（修正后）：SettingsViewModel 独立 + traffic → ConnectionViewModel**

```kotlin
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
    fun refreshTrafficStats() { ... }
    fun resetTrafficStats() { ... }
}
```

OrchestratorVM 只保留 nav + file + permission/question + 跨域编排（resetLocalDataAndResync/openSessionFromDeepLink/executeCommand）。

- **预估工作量**：中（~60 行方法搬迁 + Composable 注入更新）

---

### P2-7. connectionPhase → sealed class

- **来源**：dser + kimo

> ⚠️ **评审修正（3/3 共识：隐藏分支漏列 + Reconnecting 设计歧义 + i18n 冲突）**：
> 1. 实际使用点 **9 处**（不止 ~10）：ConnectionCoordinator.kt 5 处（162/184/200/229/446）、HostProfileController.kt:480、MainViewModelConnectionActions.kt:112、ChatEmptyState.kt:48（`connectionPhase?.takeIf { it.isNotBlank() && it != "connecting" }`）、ChatTopBar.kt:384（`state.connectionPhase == null`）。
> 2. **Reconnecting 设计歧义（已确认两种 pattern）**：实测发现两种 Reconnecting 赋值：
>    - `HostProfileController:480` / `MainViewModelConnectionActions:112`：`"reconnecting"`（**无 attempt**，host switch 即时信号，retry loop 还没开始）
>    - `ConnectionCoordinator:184`：`"reconnecting (attempt $attempt/$maxAttempts)"`（**有 attempt 数据**嵌在字符串里）
>    裁决：sealed 需两种——`data object Reconnecting`（无 attempt）+ `data class ReconnectingAttempt(attempt, max)`。
> 3. **i18n 冲突**（gpter）：`Connecting(val message: String = "connecting")` 又引入硬编码文案，与 P0-3 冲突。ConnectionPhase 只表达状态数据，UI 层用 @StringRes 映射。
> 4. **ChatEmptyState:48-53 渲染依赖 phase 字符串插值**（`stringResource(R.string.chat_reconnecting_with_phase, hostName, phase)`）——改 sealed 后 UI 需 sealed→@StringRes 映射（与 P0-3 i18n 对齐）。

**修复方案（修正后，已确认两种 Reconnecting）**：
```kotlin
sealed class ConnectionPhase {
    data object Idle : ConnectionPhase()
    data object Connecting : ConnectionPhase()   // 不带 message，UI 层 i18n
    data object Connected : ConnectionPhase()
    data object Reconnecting : ConnectionPhase()              // 无 attempt（host switch 即时信号）
    data class ReconnectingAttempt(val attempt: Int, val maxAttempts: Int) : ConnectionPhase()  // retry loop
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

全量 grep `connectionPhase = "` 确保所有赋值换 sealed。UI when 改 `when (phase)` 分支（编译器保证完备性）。ChatEmptyState/ChatTopBar 的 phase 字符串插值改 sealed→@StringRes 映射（与 P0-3 i18n 对齐）。

- **预估工作量**：小（类型定义 + ~9 处字符串→sealed + ~5 处 UI when）

---

### P2-8. SSEClient onClosed/onFailure 二次 close 风险（评审新增，maxer）

- **来源**：maxer P2-N3（评审新增）
- **当前状态**：`SSEClient.kt:158-166` onClosed 调 `close(Exception("..."))`，onFailure 也调 `close(t)`。两个 callback 都可能在 watchdog 触发 cancel 之后被回调——channel 被 close 两次。
- **影响**：OkHttp EventSource 内部可能 NoSuchElementException。

**修复方案**：用 `closed.set(true)` 守卫，或用 `channel.close(cause)` 的 idempotent 检查。

- **预估工作量**：3 行

---

### P2-9. SettingsManager.addRecentWorkdir read-modify-write race（评审新增，maxer）

- **来源**：maxer P2-N4（评审新增）
- **当前状态**：`SettingsManager.kt:132-138` getter 解码、过滤、setter 重编码——中间若有另一个线程 set，会丢更新（read-modify-write race）。
- **影响**：并发 workdir 切换时 recentWorkdirs 列表可能丢项。

**修复方案**：包到 `synchronized(this)`。

- **预估工作量**：小

---

### P2-10. EventEmitter 包装让所有 UiEvent 走 tryEmit（评审新增，glmer）

- **来源**：glmer P0-5（评审新增）
- **当前状态**：SessionViewModel 等 6 处 `EventEmitter { event -> core.effectBus.uiEvents.tryEmit(event) }`，所有 UiEvent 走 tryEmit，可丢。
- **影响**：用户看到的 toast/snackbar 可能静默丢失。

**修复方案**：与 P1-3 合并——SharedEffectBus 的 `_uiEvents` 改 DROP_OLDEST 显式声明 + 提供 suspend `emitUiEvent`。

- **预估工作量**：与 P1-3 合并

---

## 四、Cosmetic / 防御性（可选）

| # | 问题 | 修复方案 | 评审修正 |
|---|---|---|---|
| C-1 | applyPartDeltaLeadingEdge 双重载合并 | 合并为单函数 | ⚠️ glmer：原方案描述错误。实际签名 `(partId, delta, knownType, msgId, sessionId)` 5 参 vs `(partId, delta, partType, pId, msgId, sessionId)` 6 参——第一个重载的 knownType 是外部传入，不是查 partsByMessage。合并需保留两组参数集 |
| C-2 | expandedParts .value = 改 .update{} | | ⚠️ maxer+glmer：实际 **3 处**（SessionSwitcher.kt:226, ComposerController.kt:91,96），不是 4 处。应 grep 全部 `.value =` |
| C-3 | flushJobs 改 ConcurrentHashMap | `mutableMapOf` → `ConcurrentHashMap` | ⚠️ 不充分：ConcurrentHashMap 只解决 map 结构并发，不解决 Job 取消与替换的原子语义。需 `compute`/`remove(key, job)` 模式。副作用：迭代顺序变化（LinkedHashMap→CHM），日志顺序变 |
| C-4 | MissingTranslation lint 重新启用 | 删除 `disable += setOf("MissingTranslation")` | ⚠️ 需先跑 `lintDebug` 验证 en/zh 100% 对齐 |
| C-5 | 中文注释密度过高 | RFC 引用迁移到 `docs/tech-debt/` | ✅ 低风险，但不要在行为修复阶段混入 |
| C-6 | property-based testing | 加 kotest-property 依赖（libs.versions.toml 未引入） | ⚠️ 先补 deterministic table tests，再加 property |
| C-7 | SettingsManager 接口 + draft 性能 | 提取 `interface SettingsRepository` | ⚠️ glmer：实际是 **read-modify-write 数据安全**问题（setDraftText:271-284 并发丢数据），归类"性能"误导。应拆为接口抽象 + RMW 加锁两问题 |
| C-8 | ~~TrafficLogger 隐私脱敏~~ | **已升级为 P1-11** | gpter：隐私问题不应 cosmetic |
| C-9 | PartStateSerializer 独立 + fuzz | 迁移到 `data/model/PartStateSerializer.kt`；加 fuzz test | ✅ 高 ROI，配合 kover 收尾 |
| C-10 | UI 大文件深度拆分 | ChatScreen 拆为 ChatRoute + ChatScreenContent + ChatScrollStrategy | ⚠️ 放 kover 覆盖率提升后做（UI 拆分易引入重组回归）|

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

## 六、修正后修复阶段路线图（含 gate）

> 融合 gpter 两步走（directory 显式化优先）+ maxer 簇划分 + glmer 隐含依赖修正。

```
Phase 0：评估准备（0.5 天）
├── explorer 全量 grep 所有调用点（setCurrentDirectory/getCurrentDirectory/tryEmit/
│   settingsManager.currentSessionId =/connectionPhase =/core.appScope.launch/xxxFlow.update）
├── 生成 baseline kover 报告
└── 确认 Robolectric + KeyStore 本机可用
Gate: grep 结果归档 + baseline kover

Phase 1：止血（2-3 天，4 fixer 并行）
├── 簇 A（fixer）：P0-3 i18n 编译修正 + P0-6 AppLifecycleMonitor 接口
│   ├── 写作用域：UiEvent.kt, ChatScreen.kt, AppLifecycleMonitor, AppCore.kt:194
│   └── 注：先保证编译通过 + AppLifecycleMonitor 不崩；22+ 调用点 + 18 string key 拆 Phase 2
├── 簇 B（fixer）：P0-1 步骤 1 directory 显式化 + P0-5 endpoint header（不删 fallback）
│   ├── 写作用域：OpenCodeApi.kt, SSEClient.kt, OpenCodeRepository.kt, DirectoryHeaderInterceptor.kt,
│   │   OrchestratorViewModel.kt（saved 还原修正）
│   └── 给 question/command/SSE 加显式 directory
├── 簇 C（fixer）：P1-5 + P1-6 FilesViewModel 双 bug（FileNode.isDirectory 信号，不探测）
│   └── 写作用域：FilesViewModel.kt + FilesViewModelTest.kt（同文件串行）
└── 簇 D（fixer）：P0-4 kover check.sh + P0-8 release 签名兜底
    └── 写作用域：scripts/check.sh, build.gradle.kts
Gate: ./scripts/check.sh --full 全过 + 手动验证文件浏览器不串目录 + i18n 编译通过

Phase 2：架构加固（4-5 天，3-5 fixer 并行）
├── 簇 E（explorer + fixer）：P0-1 步骤 2 删除全局 currentDirectory
│   ├── 前置：Phase 1 簇 B 完成且所有调用方传显式 directory
│   ├── 先删 setter/getter 让 compiler 列遗漏 → 改测试 mock（8+ 处）→ 删 production
│   └── 写作用域：HostConfig.kt + 8 调用方 + 5 测试文件
├── 簇 F（fixer）：P0-2 currentSessionId collector（drop{null} + 删 10 处手动写）
│   └── 写作用域：AppCore.kt init + 10 处调用点
├── 簇 G（designer + librarian + fixer）：P0-3 i18n 完整迁移 + P0-7 SSE unknown event 告警
│   ├── 前置：Phase 1 簇 A 编译修正完成
│   ├── designer 设计 res key，librarian 双语翻译，fixer 改 22+ 调用点 + TestAppStateShim 12 处
│   └── 写作用域：8 controller/VM 文件 + strings.xml(zh/en) + TestAppStateShim + SessionSyncCoordinator
├── 簇 H（fixer）：P0-9 SharedStateStore MutableStateFlow → private + public mutateXxx
│   ├── 写作用域：SharedStateStore.kt + ~50 处 call site（xxxFlow.update → mutateXxx）
│   └── 这是 P1-1 步骤 A，立即堵漏（不做 internal，单 module 无效）
└── 簇 I（fixer）：P2-7 connectionPhase sealed（含 4 处隐藏分支 + Reconnecting 设计）
    └── 写作用域：AppStateSlices, ConnectionCoordinator, HostProfileController,
        MainViewModelConnectionActions, ChatEmptyState, ChatTopBar
Gate: ./scripts/check.sh --full + grep 无 setCurrentDirectory/getCurrentDirectory 残留 +
      i18n 全迁移 + TestAppStateShim error/successMessage 12 处改完 + koverVerify 不低于 baseline

Phase 3：可靠性（1 周，5 fixer 并行）
├── 簇 J（oracle + fixer）：P1-3 EffectBus 改 SharedFlow + BufferOverflow.SUSPEND + P1-8 dropped counter +
│   │   P2-10 UiEvent 通道
│   ├── oracle 先分类"关键路径"（改 emit suspend）vs"非关键"（保留 tryEmit + log）
│   ├── 保持 FIFO 顺序，避免 launch 重排
│   └── 写作用域：SharedEffectBus.kt + 5 controller（36 处分类）+ 测试
├── 簇 K（fixer）：P1-2 viewModelScope 分类改写 + P1-7 appScope lambda 闭包泄漏
│   ├── 先枚举 core.appScope.launch ~20 处
│   └── 写作用域：6 VM + Actions 文件
├── 簇 L（fixer）：P1-9 多 workdir pending questions
│   └── 写作用域：SessionSyncCoordinator.kt, ForegroundCatchUpController.kt
├── 簇 M（fixer）：P2-1 Actions 改名（7 文件含 MainViewModelSupport.kt）+ C-2 expandedParts（3 处）+ P1-4 AtomicLong（降级）
│   └── 写作用域：7 Actions + import + ConnectionCoordinator + 3 处 .value=
└── 簇 N（fixer）：P2-6 OrchestratorVM 拆分（SettingsViewModel 独立 + traffic→Connection）
    └── 写作用域：OrchestratorViewModel + 新 SettingsViewModel + ConnectionViewModel + Composable
Gate: 压测（host switch + SSE burst 不丢关键 effect）+ ./scripts/check.sh --full

Phase 4：深度清理（按需触发，1-2 周）
├── 簇 O（fixer）：P2-5 VM 精确注入（去 core 万能访问）
│   ├── 前置：Phase 2 簇 H（P0-9 private mutateXxx）完成
│   ├── 注：P1-1 步骤B（真隔离）已 YAGNI 裁决不做，P0-9 即终态
│   └── 写作用域：6 VM + AppCore（controller 实例化分层）
├── 簇 P（fixer）：P2-2 AppCore dispatcher 拆分（成员函数非扩展 + 返 Boolean + assertExactlyOneHandled）
│   ├── 前置：依赖 O（mutateXxx 调用方式确定）
│   └── 写作用域：AppCore + 5 新 dispatcher 文件
├── 簇 Q（fixer）：P2-3 TestAppStateShim 逐步删除（76 处改写，5 文件分批）
│   ├── 前置：Phase 2 簇 G 完成（i18n 已改 shim）
│   └── 写作用域：~10 测试文件
├── 簇 R（fixer）：P2-4 SSE reducer Pair<State, SideEffect>（CAS 提交 + actor 串行化）
│   └── 写作用域：SessionSyncCoordinator + 测试
├── 簇 S（fixer）：P1-10 SSE gap reconciliation 状态机 + P1-11 TrafficLogger 隐私
│   └── 写作用域：SessionSyncCoordinator + TrafficLogger
└── 簇 T（fixer×N）：P2-8/P2-9 + C-1/C-3/C-4/C-5/C-6/C-7/C-9/C-10
    └── 写作用域：散布，多项独立
Gate: AppCore + Orchestration 行数核查 + 架构测试（data/di 不依赖 mutable store 写接口）

Phase 5：kover 80% 收尾（2-3 周，见第八节）
└── 阶梯提升 25% → 80%

Phase 6：发版 + review 归档
├── ./scripts/release.sh minor
└── .opencode/runs/reviews/<date>/R18-overall.json
```

**总耗时**：Phase 0-3 约 2.5 周（4-5 fixer 并行）；Phase 4 约 2 周；Phase 5 约 2-3 周。**合计 6-7 周**。

---

## 七、修正后依赖关系图

```
P0-5 (endpoint header) ──→ P0-1 (currentDirectory 消除)
P0-6 (AppLifecycleMonitor) ──→ P0-3 (i18n)
P0-3 (i18n) ──→ P2-3 (TestAppStateShim 删除)  ← 评审修正：隐含依赖

P0-9 (SharedStateStore private) ──→ P2-5 (VM 精确注入) ──→ P2-2 (AppCore dispatcher)
                                      ↑
（P1-1 步骤B 真 isolation 已 YAGNI 裁决不做；P0-9 即终态）

P1-2 (appScope) ──→ P1-7 (lambda 闭包泄漏)  ← 评审新增依赖
P1-3 (EffectBus) ──→ P2-4 (SSE reducer Pair)
P1-3 ──→ P2-10 (UiEvent 通道)  ← 同根

P2-1 (改名) ──→ P2-3 (TestShim 删除)
P2-6 (OrchestratorVM) ──→ P2-7 (connectionPhase sealed)

P0-1 / P0-2 / P0-3 / P0-4 / P0-7 / P0-8 / P0-9 独立可并行（Phase 1-2）
P1-4 降级为 cosmetic（Phase 3）
```

**关键依赖（评审修正后）**：
- P0-5（endpoint header）是 P0-1（删 fallback）的前置
- P0-6（AppLifecycleMonitor）是 P0-3（i18n）的前置
- P0-3（i18n）是 P2-3（TestShim 删除）的前置（隐含，原图漏）
- P0-9（SharedStateStore private）是 P2-5（VM 精确注入）与 P2-2（dispatcher 拆分）的前置（P1-1 步骤B 已 YAGNI 不做）
- P1-3（EffectBus）是 P2-4（SSE reducer）的前置
- P1-7 与 P1-2 同根（appScope），同批做

---

## 八、kover 80% line 收尾规划

### 当前基线（核实）
`app/build.gradle.kts:115-117`：line 25% / branch 23% / instruction 22%。`check` task 已 wire `koverVerify`（line 128），但 `check.sh` 不调 `check`，P0-4 修复后才会生效。kover 0.9.7。**kover 块无 `includeAndroidTest`——只统计 unit test，不含插桩测试**（已确认），所以 80% 只能靠 unit test 拉覆盖率，Composable 必须 exclude。

### 阶梯提升路径（融合三方）

| Stage | line 目标 | 增量 | 高 ROI 区 | 工作量 |
|---|---|---|---|---|
| **5.1** | 25% → 40% | +15pp | 22 个 SSE applyXxx 纯函数边界 case 扩展（已有 table 测试基础）+ FormatUtils + FilePreviewUtils + FileNavigationUtils + ToolCardClassifier | 3 天 |
| **5.2** | 40% → 55% | +15pp | SettingsManager 私有函数（normalizeBaseUrl/draft RMW/disabled models）+ HostConfig + DirectoryHeaderInterceptor 全分支 | 4 天 |
| **5.3** | 55% → 65% | +10pp | OpenCodeRepository 各 suspend function success path（mock server）+ SseLogFilter + CachePathSanitizer | 4 天 |
| **5.4** | 65% → 73% | +8pp | HostProfileController（14 tryEmit 每个至少 1 case）+ SessionSwitcher + ConnectionCoordinator 重试/backoff/SSE | 4 天 |
| **5.5** | 73% → 80% | +7pp | ChatViewModel/SessionViewModel 方法覆盖 + AppCoreOrchestration + 边界 case（generation guard/空文件/sealed phase/navigateUp） | 5 天 |

### 高 ROI 覆盖区（测试行/代码行 比，三方共识）

| 优先级 | 文件 | 理由 | 预估 line 增量 |
|---|---|---|---|
| 🔥 1 | SessionSyncPureFunctions（22 applyXxx）| 纯函数 + 已有 table 测试基础 | +5pp |
| 🔥 2 | SettingsManager 私有函数 | 纯逻辑多 | +3pp |
| 🔥 3 | FormatUtils / FilePreviewUtils / FileNavigationUtils / ToolCardClassifier | 纯函数 + 异常处理 | +3pp |
| ⚡ 4 | HostProfileController 14 tryEmit 路径 | 每个 effect 一个 case | +3pp |
| ⚡ 5 | DirectoryHeaderInterceptor + HostConfig | 已有测试基础 | +2pp |
| ⚡ 6 | ConnectionCoordinator 重试/backoff/SSE | 配合 injectable clock | +2pp |
| ⚡ 7 | OpenCodeRepository success path | mock server | +2pp |
| ⚡ 8 | SessionSwitcher 5 tryEmit | 同 HostProfile | +1pp |

### 低 ROI 区（不建议优先，三方共识）

- **Composable**（ChatScreen/ChatTopBar/Composer）：UI 测试慢且不稳定——**必须 exclude 或单独子阈值**，否则永远到不了 80%
- **Android framework 桥接**：AppLifecycleMonitor、DebugLog 实际写 Logcat 分支
- **AppCore.dispatchEffect 22 分支**：需大量 mock，与 Phase 4 P2-2 拆分冲突
- **反射/Class.forName**：ServerCompatProfile version 解析
- **错误堆栈 4xx/5xx 路径**：OpenCodeRepository Result.failure 分支
- **TrafficLogger IO**：mock 复杂、ROI 低

### 80% gate 最终验证清单

- [ ] `app/build.gradle.kts:115` `minBound(80, CoverageUnit.LINE, COVERED_PERCENTAGE)`
- [ ] branch ≥ 70% / instruction ≥ 75%（不与 line 同高，branch 天然更难）
- [ ] `scripts/check.sh --full` 含 `koverVerify`（Phase 1-D 修复后）
- [ ] CI 跑 `./gradlew check` 强制 koverVerify
- [ ] Composable exclude 或单独子阈值（显式审查，禁止盲排）
- [ ] `app/build/reports/kover/html/index.html` 无红色类（除 Composable）
- [ ] 每个 Stage 提升后单独 PR + `koverVerify` 通过再提下一 Stage
- [ ] 新增 production 代码必须配套测试（防回归）
- [ ] 无 flaky test（连续 3 次全绿）
- [ ] P0-1/P0-2/P0-3/P0-9 全闭环
- [ ] release smoke test

### Composable exclude 配置示例
```kotlin
kover {
    reports {
        verify {
            rule("min coverage floor") {
                minBound(80, CoverageUnit.LINE, AggregationType.COVERED_PERCENTAGE)
            }
            // Composable 单独子阈值或 exclude（显式审查，禁止盲排）
        }
    }
}
```

### 反模式警告
1. 不为达标写无意义测试（`assertEquals(1,1)`）——review 拒绝
2. 不拆小函数刷 line——破坏封装
3. branch 阈值 ≠ line 阈值（branch 70% / line 80%）
4. Composable 必须 exclude 或单独阈值

---

## 九、附录：三方评审共识与裁决

### 评审员
| Agent | Session ID | 视角 |
|---|---|---|
| gpter (review-5) | ses_0ccfa147... | 最深层推理、跨文件状态分析、协程/并发语义终审 |
| glmer (review-2) | ses_0ccf9c944... | 方案可行性、配置一致性、语义级 bug、性能/安全陷阱 |
| maxer (review-5) | ses_0ccf97dc7... | 深度推理、被掩盖的风险、跨文件状态一致性、隐藏内存泄漏 |

### 关键共识（3/3 或 2/3）

| # | 共识点 | 裁决 |
|---|---|---|
| 1 | P1-1 `internal` 在单 module 失效 | 拆两步：P0-9 private + public mutateXxx（Phase 2）→ P1-1 真隔离（Phase 4） |
| 2 | P0-1 调用链列举不全 + saved 逻辑错 | gpter 两步走：显式化（Phase 1）→ 删 fallback（Phase 2） |
| 3 | P0-3 `stringResource()` 在 collect 内编译失败 + 连带破坏 | 改 `LocalContext.getString()`；同步改 AppLifecycleMonitor + TestAppStateShim |
| 4 | P1-3 Channel 方案顺序风险 + 36 处非 22 | 放弃 Channel，改 SharedFlow + BufferOverflow.SUSPEND |
| 5 | P0-2 冷启动 seed 时序冲突 | collector 加 `drop { it == null }` |
| 6 | P1-6 漏 `_state.update` + 多加副作用 | 补状态更新，删 loadFileStatuses |
| 7 | P2-7 隐藏分支 + Reconnecting 歧义 + i18n 冲突 | 用 `data object`；ConnectionPhase 不带文案 |
| 8 | P1-4 AtomicLong 优先级过高 | 降级为 Phase 3 cosmetic |
| 9 | P1-5 不应靠探测 | 用 FileNode.isDirectory 信号 |
| 10 | C-2 是 3 处不是 4 处 | 修正 |
| 11 | P2-1 漏 MainViewModelSupport.kt | 补列 7 文件 |

### 分歧裁决

| 分歧 | gpter | glmer | maxer | 裁决 |
|---|---|---|---|---|
| P2-2 extension 能否访问 private | ❌ 不能 | ✅ | ✅ | 采 gpter：做成成员函数 + 返 Boolean + assertExactlyOneHandled |
| SharedStateStore 何时做 | Phase 5 | Phase 4 | Phase 2 | P0-9 Phase 2 即终态（oracle YAGNI 裁决，P1-1 步骤B 不做） |
| directory 显式化一次/两步 | 两步 | Phase 1 一次 | Phase 1 一次 | 采 gpter 两步走（最安全） |

### 已确认结论（不确定项验证完成 2026-07-06）

1. **P2-7 Reconnecting 设计**：✅ **已确认两种 pattern**。`HostProfileController:480` / `MainViewModelConnectionActions:112` 无 attempt（host switch 即时信号）；`ConnectionCoordinator:184` 有 `"reconnecting (attempt $attempt/$maxAttempts)"`。sealed 用 `data object Reconnecting` + `data class ReconnectingAttempt(attempt, max)`。ChatEmptyState:48-53 把 phase 当字符串插值，改 sealed 后 UI 需 sealed→@StringRes 映射（与 P0-3 i18n 对齐）。
2. **P1-3 关键路径集合大小**：✅ **已确认 36 处 = A(suspend) 4 + B(非 suspend 单发) 13 + C(非 suspend 顺序敏感) 19**。C 类 19 处顺序敏感（HostProfileController.saveHostProfile/deleteHostProfile/configureServer/resetLocalDataAndResync、ConnectionCoordinator.loadInitialData 5 连发、SessionSwitcher.switchTo 8 步流程），进一步确认 SharedFlow + BufferOverflow.SUSPEND 裁决正确（保持 FIFO，避免 launch 重排）。
3. **P1-1 步骤B 真隔离方案**：✅ **oracle 裁决 YAGNI**。data/di/util 层零 import ui 包、零写 slice（依赖方向天然 `ui → data`）。P0-9（private + public mutateXxx）即终态。方案 A（拆 module）有 internal 误伤同 module 写手陷阱；方案 B（接口）为不存在威胁买单。仅在触发条件出现时升级（data/di 需读 slice → 方案 B；项目拆 feature module → 方案 A）。
4. **TestAppStateShim 实际访问面**：✅ **已确认**：`core.state.value.*` 读点 12 处（error/successMessage）+ `core.updateState{copy()}` 写点 64 处（112 字段，18 个 AppState 字段）。**不是 150+**（glmer 的估计误把 FilesViewModelTest 的 27 次自有 StateFlow 访问算进来）。影响 5 个测试文件。无跨 slice 派生状态断言。工作量中（76 处 + Turbine 改造 12 处）。
5. **kover 是否包含 androidTest**：✅ **已确认不含**。`build.gradle.kts:111-121` kover 块无 `includeAndroidTest` 配置。覆盖率只统计 unit test（`testDebugUnitTest`）。这影响 80% 目标——只能靠 unit test 拉覆盖率，Composable 必须 exclude。
6. **header 名称核实**：✅ **已确认 `X-Opencode-Directory`**（`HttpHeaders.kt:17 const val DIRECTORY_HEADER = "X-Opencode-Directory"`）。R18 原文 `X-Opencode-Session-Directory` 错误，已在 P0-1 修正。
