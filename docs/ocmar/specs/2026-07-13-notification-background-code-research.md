# 通知 / 后台 SSE 保活 / 通知卡片视觉 — 代码调研文档 (ocdroid)

> 状态：**代码调研文档（面向改动定位）**。
> 目的：为后续 Fixer agent 落实 [FGS spec](./2026-07-13-notification-background-fgs-design.md) 与 [需求文档](./2026-07-13-notification-background-requirements.md) / [开发设计文档](./2026-07-13-notification-background-dev-design.md) 提供**精确的文件路径 + 行号范围 + 改动类型**清单，避免实施时反复摸代码。
> 行号基线：2026-07-13 当前 `main` 分支快照。实施前请用 `git diff` 复核（v0.6.3 后代码可能微调）。

---

## 0. 文档定位

- **现状分析**（§1-§2）：每个关键协作者「现在是什么、当前职责、被谁调用」；
- **改动影响图**（§3）：每个改动点「文件 + 行号范围 + 改动类型（新增/修改/删除）+ 关联 FGS spec § + 关联需求 FR/V」；
- **风险与待验证**（§4）：服务端、平台、厂商、targetSdk 相关。

代码包前缀统一为 `cn.vectory.ocdroid`（**不是** `com.zhipu.ocdroid`）。

---

## 1. 现状分析（按 SSE 数据流方向）

### 1.1 SSE 连接建立：`OpenCodeRepository.connectSSE` → `SSEClient.connect`

- `data/api/SSEClient.kt`：OkHttp `EventSources` + `callbackFlow`；`connect(baseUrl, username, password, directory)` 进入 `retryWhen`（line 73-92，最多 `MAX_RETRY_ATTEMPTS=10`，指数退避 + ±30% jitter），耗尽抛 `SSEConnectionExhausted`（line 30-32）。心跳看门狗（line 222-244，30s/5s 间隔）。URL = `$baseUrl/global/event`（line 102），带 `DIRECTORY_HEADER`（line 114-116）。
- `data/repository/OpenCodeRepository.kt:886-887`：`fun connectSSE(directory: String?): Flow<Result<SSEEvent>> = sseClient.connect(hostConfig.baseUrl, ..., directory)`。SSE 只喂**单个 directory**（currentWorkdir）——**多 workdir 不复用 SSE**。

### 1.2 SSE 收集：`ConnectionCoordinator.sseJob`

- `ui/controller/ConnectionCoordinator.kt:108`：`private var sseJob: Job? = null` — **SSE 连接当前所有者是 ConnectionCoordinator，Activity / VM 域**。
- `startSSE()`（line 655-668）：cancel 旧 sseJob → `launchSseCollection()`；
- `launchSseCollection()`（line 707-770）：read `directoryFetchGeneration.get()`（line 724）→ `repository.connectSSE(settingsManager.currentWorkdir)` → `.collect { ... }`：
  - 每事件 re-check generation（line 742-745，drop stale-host events）；
  - `result.onSuccess { event -> ...; effects.emitEffect(ControllerEffect.OnSseEvent(event)) }`（line 762）；
  - failure → `UiEvent.Error`（line 766-768）。

### 1.3 SSE fold：`SessionSyncCoordinator.handleEvent`

- `ui/controller/SessionSyncCoordinator.kt:269-290`：`fun handleEvent(event: SSEEvent)` — `server.connected` 触发 `ControllerEffect.ServerConnected` + 调 `reconcileGap`；随后 `dispatchSseEvent(event)`；
- `dispatchSseEvent`（line 408+）：11 个 `when` 分支（session.created/updated/status、message.created/updated/part.updated、permission、question、todo 等），通过 `applyXxx` 纯函数 fold 到 slice；
- **纯 reducer**——SSC **不持 SSE Job**（仅持 `flushJobs: MutableMap<String, Job>` line 136 用于 delta coalesce）；
- 自有 `sseSyncState`（line 163）+ `hostGeneration`（line 182）— §P1-10 gap reconciliation overlay，由 `effects.effects` init collector 驱动（line 213-255）。
- 多 workdir pending 轮询：`loadPendingQuestionsAllWorkdirs(repository)`（line 1214+），用 `computeQuestionFanOutWorkdirs`（在 `ui/AppCoreOrchestration.kt:157`）。

### 1.4 SSE 启停驱动：`ForegroundCatchUpController`（**真正的背景断流点**）

> FGS spec §1.1 钉死：今日真正在进后台时发 `CancelSse` 的是 **`ForegroundCatchUpController.onForegroundChanged(false)`**，**不是** `AppLifecycleMonitor`。这是改造目标，不是 ALM。

- `ui/controller/ForegroundCatchUpController.kt:42-52`：构造注入 `appLifecycleMonitor, scope, store, settingsManager, effects, clock`；
- init（line 88-94）：`appLifecycleMonitor.isInForeground.onEach { onForegroundChanged(it) }.launchIn(scope)`；
- `onForegroundChanged(inForeground)`（line 106-154）：
  - 前台分支（line 113-144）：清 suppressNextConnectCatchUp、emit `ForceReconnect`、按 bgGapMs 三档（<15s / 15s-5min / >5min）分流 catch-up；
  - **背景分支（line 145-153）**：`clearDraft()`（line 148）+ `backgroundedAtMs = clock()`（line 149）+ **`DebugLog.i("SSE", "cancelSse (background)")`（line 150）+ `effects.tryEmitEffect(ControllerEffect.CancelSse)`（line 152）** — 这是无条件断 SSE 的根源；
- `onServerConnected()`（line 164-176）：catch-up 触发点（§Phase1E），由 `dispatchSessionSyncEffect` 调用（AppCore.kt:626-630）；
- `onHostReconfigured()`（line 183-186）：reset `sseHasConnectedOnce`/`suppressNextConnectCatchUp`，由 `dispatchConnectionEffect` 调用（AppCore.kt:596-599）。

### 1.5 `CancelSse` effect 路由：`AppCore.dispatchForegroundCatchUpEffect`

- `ui/AppCore.kt:391-395`：`ControllerEffect.CancelSse → { connectionCoordinator.cancelSse(); sessionSyncCoordinator.clearDeltaBuffers(); }`；
- `ConnectionCoordinator.cancelSse()`（line 777-780）：`sseJob?.cancel(); sseJob = null`；
- 注意：`AppCore.dispatchForegroundCatchUpEffect` 还处理 `ForceReconnect`/`GlobalColdStartRefresh`/`CatchUpAfterDisconnect`（line 382-401）。

### 1.6 前台信号源 + 30s 轮询器：`AppLifecycleMonitor`

- `di/AppLifecycleMonitor.kt:82-91`：`@Singleton class AppLifecycleMonitor @Inject constructor(application, appScope, repository, settingsManager)`；
- `_isInForeground = MutableStateFlow(true)`（line 92）— **默认 true**（FGS spec §4.3 已识别为问题：sticky 进程重建时短暂误报前台）；
- `activityStartedCount`（line 112）：started-activity 计数，init 内 `registerActivityLifecycleCallbacks`（line 114-139）翻译 0↔1 → `_isInForeground`；
- `notificationSnapshot: MutableSet<String>`（line 102）：grow-only dedup（key = `"perm:<id>"`/`"q:<id>"`），跨 ON_STOP/ON_START 保留；
- `pollJob: Job?`（line 105）：30s 轮询器（POLL_INTERVAL_MS=30_000 line 329）；
- `onEnterForeground/onEnterBackground`（line 160-178）：onEnterBg 调 `startBackgroundPolling()`；
- `startBackgroundPolling()`（line 180-190）：launch 一个 while(isActive) loop，每 30s 调 `pollPendingItems()`；
- `pollPendingItems()`（line 192-213）：`getPendingPermissions()` + `getPendingQuestions(settingsManager.currentWorkdir)` — **只查 currentWorkdir**（多 workdir 已由 SSC 的 `loadPendingQuestionsAllWorkdirs` 覆盖，二者**独立**）；
- `handlePendingPermission/Question`（line 215-251）：snapshot dedup → `notifyDecision(...)`；
- `notifyDecision`（line 264-282）：`NotificationCompat.Builder(application, CHANNEL_DECISIONS)`，PRIORITY_HIGH，autoCancel，contentIntent → MainActivity + EXTRA_SESSION_ID；返回 Boolean 表示是否真发（permissions denied 时 false）；
- `notifyError`（line 286-298）：`Builder(application, CHANNEL_ERRORS)`，固定 ID `ERROR_NOTIFICATION_ID=4242`（line 332）；
- `createChannels(context)`（line 340-360）：API 26+ 创建 2 channel（`ocdroid.decisions` HIGH + `ocdroid.errors` DEFAULT），由 `OpenCodeApp.onCreate` 调用；
- companion 常量：`CHANNEL_DECISIONS="ocdroid.decisions"`（line 325）、`CHANNEL_ERRORS="ocdroid.errors"`（line 326）、`POLL_INTERVAL_MS=30_000L`（line 329）、`ERROR_NOTIFICATION_ID=4242`（line 332）。

### 1.7 AppCore（装配 + effect 路由）

- `ui/AppCore.kt:79-145`：`@Singleton class AppCore @Inject constructor(...)` — 持 6 controllers + store + repository + settingsManager + effectBus + cacheRepository 等；通过 Hilt 注入；
- init（line 232-321）：`applySavedSettings` + 启动 UiEvent.Error collector（→ `appLifecycleMonitor.onAppError`，line 240-246）+ 启动 effect bus collector（→ `dispatchEffect`，line 250-252）+ currentSessionId 持久化 collector（line 315-320）；
- `dispatchEffect(effect)`（line 342-349）：5-domain cascade（`||` 短路）：`dispatchForegroundCatchUpEffect || dispatchSessionEffect || dispatchHostEffect || dispatchConnectionEffect || dispatchSessionSyncEffect`；
- `dispatchForegroundCatchUpEffect`（line 382-401）：处理 `ForceReconnect`/`GlobalColdStartRefresh`/`CancelSse`/`CatchUpAfterDisconnect`；
- `dispatchSessionEffect`（line 404-537）：`LoadMessages`/`LoadChildSessions`/`LoadSessionStatus`/`LoadPendingQuestions`（line 417-429，调 SSC `loadPendingQuestionsAllWorkdirs`）/`ClearDeltaBuffers`/`VerifyAndHydrate`；
- `dispatchHostEffect`（line 540-592）：`CancelSseForReconfigure`（→ CC.cancelSseForReconfigure）/`StartSse`（→ CC.startSSE）/`HostProfileSwitched`/`ColdStartReconnect`/`ResetLocalDataAndResync`/`ClearSessionWindowCache`/`EvictSession`/`EvictGroup`；
- `dispatchConnectionEffect`（line 595-623）：`HostReconfigured`（→ FCC.onHostReconfigured）/`LoadSessions`/`LoadAgents`/`LoadProviders`/`LoadPendingPermissions`/`OnSseEvent`（→ `sessionSyncCoordinator.handleEvent(effect.event)` line 619）；
- `cleanup()`（line 664-668）：`clearDeltaBuffers + cancelSse + appScope.cancel()`。

### 1.8 ConnectionCoordinator 的 TOFU 私有状态（§10 待抽离）

- `ui/controller/ConnectionCoordinator.kt:121-124`：
  - `@Volatile private var pendingTofuHostPort: String? = null`（line 122）；
  - `@Volatile private var pendingTofuDecision: kotlinx.coroutines.CompletableDeferred<TofuDecision>? = null`（line 124）；
- 三处读取：`testConnection` 入口（line 198-201）/ `testConnection` loop（line 241-244）/ `coldStartReconnect`（line 451-454）/ `startSSE`（line 661-664）；
- 唯一 writer：`resolveTofuTrust(decision)`（line 678-681）：`pendingTofuDecision?.complete(decision)`；
- `testConnection` 内 capture + 占住 pending（line 331-393）：`pendingTofuHostPort = hostPort` → `captureServerCert` → 进 `await()` → 写 pin 或 cancel → finally 清；
- FGS spec §10 要求：**抽到应用级共享 `ConnectionBootstrapCoordinator`**，CC 与 Service 共同调用。

### 1.9 ConnectionCoordinator 的 `directoryFetchGeneration`（§2 待上移）

- `ui/controller/ConnectionCoordinator.kt:150`：`private val directoryFetchGeneration = java.util.concurrent.atomic.AtomicLong(0L)`；
- 双重用途（line 127-149）：
  1. `loadInitialData`（line 479-540）：capture 后用 `if (generation != directoryFetchGeneration.get()) return@launch`（line 524, 534）drop stale-host directory-fetch 结果；
  2. `launchSseCollection`（line 707-770）：capture `sseGenAtStart`（line 724），每事件 re-check（line 742-745）drop stale-host SSE 帧；
- `cancelSseForReconfigure()`（line 793-806）：`directoryFetchGeneration.incrementAndGet()`（line 800）+ emit `HostReconfigured`（line 805）；
- FGS spec §2 钉死：**epoch 必须同时作 SSE collector 守卫 + directory-fetch 守卫，单一真相源**（不允许 CC 私有第二个 generation）。改造时概念上移到 service 持有的 `ConnectionIdentity.epoch`。

### 1.10 SettingsManager 的持久化项（FGS spec §5 / §10 / §3 依赖）

- `util/SettingsManager.kt:22-24`：`@Singleton class SettingsManager @Inject constructor(context)`，全部 EncryptedSharedPreferences；
- `_currentWorkdirFlow`（line 41-44）+ `currentWorkdir`（line 218-226）：observable mirror + setter；
- `currentSessionId`（line 166-168）；
- `getRecentWorkdirs(serverGroupFp)`（line 251-271）+ `setRecentWorkdirs`（line 273-277）+ `addRecentWorkdir`（line 309-320）+ `removeRecentWorkdir`（line 349-359）+ `clearRecentWorkdirs`（line 368-371）：per-serverGroupFp recent workdir 列表（R-20 Phase 5）；
- `openSessionIds: List<String>`（line 474-487）：persisted open tabs；
- `sessionCache: List<SessionCacheEntry>`（line 498-511）：cold-start session 元数据 seed；
- FGS spec §5 START_STICKY bootstrap 需要：同步读最小持久配置 = `serverUrl` / `username` / `password` / `currentHostProfileId` + 从 `HostProfileStore` 拿 profile（hostPort / mtlsEnabled / clientCertId / tunnel 等）。

### 1.11 AndroidManifest 当前状态（§13 待新增）

- `app/src/main/AndroidManifest.xml`（48 行）：
  - line 5-9：`INTERNET` / `ACCESS_NETWORK_STATE` / `POST_NOTIFICATIONS`；
  - **无** `FOREGROUND_SERVICE`；
  - **无** `FOREGROUND_SERVICE_DATA_SYNC`；
  - **无** `POST_PROMOTED_NOTIFICATIONS`；
  - **无** `<service>` 声明；
  - 只有 `MainActivity`（line 23-36）+ `FileProvider`（line 37-45）。

---

## 2. 现状分析：OpenCodeApi 的关键端点

> 服务端 API 形态决定了 FGS spec §3 busy 权威源主路径的可行性。

### 2.1 `getSessionStatus()`（host 级，无 directory 参数）

- `OpenCodeRepository.kt:500-502`：`suspend fun getSessionStatus(): Result<Map<String, SessionStatus>> = runSuspendCatching { api.getSessionStatus() }`；
- **无 directory 参数**，返回 host 级 `Map<sessionId, SessionStatus>`（idle 时服务端 delete，只含 active）；
- FGS spec §3 Phase 0 主路径：用此全局接口 + `session.directory`（来自 `getSessions` / `directorySessions`）把 sessionId 归并到 workdir；
- FGS spec §14.0 强制门禁 V-2：两 workdir 隔离探针确认其覆盖范围。

### 2.2 `getPendingQuestions(directory)`（有 directory）

- `OpenCodeRepository.kt:658-660`：`suspend fun getPendingQuestions(directory: String?): Result<List<QuestionRequest>> = runSuspendCatching { api.getPendingQuestions(directory) }`；
- 多 workdir fan-out 由 `SessionSyncCoordinator.loadPendingQuestionsAllWorkdirs`（SSC line 1214+）+ `computeQuestionFanOutWorkdirs`（`AppCoreOrchestration.kt:157`）覆盖。

### 2.3 `getSessionsForDirectory(directory)`（有 directory）

- `OpenCodeRepository.kt:472-473`：fetches root sessions whose `Session.directory` exactly matches `directory`；
- 由 `ConnectionCoordinator.loadInitialData`（CC line 517-539）的 fan-out 调用，覆盖 `(recentWorkdirs + currentWorkdir).distinct()`。

### 2.4 `abortSession(sessionId)`（无 directory）

- `OpenCodeRepository.kt:577-579`：`suspend fun abortSession(sessionId: String): Result<Unit>` — FGS spec §9 abort Action 的执行端点；
- 无 Activity 时由 `BroadcastReceiver` / Service 内调。

### 2.5 `configure(...)` + `applyTofuDecision(...)`（reconfigure hooks）

- `OpenCodeRepository.kt:237-251`：`fun configure(baseUrl, username, password, hostPort, clientCert)` — 触发 `sslConfigFactory.configureClientCert` + `hostConfig.configure` + `rebuildClients()`（line 202-216）；
- `applyTofuDecision(hostPort, decision)`（line 377-392）：写 pin + 对当前 host `rebuildClients()`（line 390）；
- FGS spec §2 reconfigure 6 步顺序的执行基础——service 化后这两方法仍是底层调用。

---

## 3. 改动影响图

> 列每个改动点。**文件路径 + 行号范围 + 改动类型**。
> 改动类型：**[新增]** / **[修改]** / **[删除]** / **[保留]**。
> 关联：FGS spec § + 需求 FR/V + 开发设计文档 P\<phase\>.\<seq\>。

### 3.1 Phase 0 改动

#### 3.1.1 新增 `SessionStreamingService`（ForegroundService）

- **[新增]** `app/src/main/java/cn/vectory/ocdroid/service/SessionStreamingService.kt`（全新文件，约 300-500 行）：
  - 类职责：FGS spec §1.0 — Android 组件壳 + SSE 连接 owner + FGS 通知发布，不堆业务逻辑；
  - 暴露 `events: SharedFlow<Result<IdentifiedSseEvent>>`（进程级）；
  - `onCreate`/`onStartCommand`：FGS spec §5 START_STICKY bootstrap 流程；
  - `onTimeout()`（API 34+）：FGS spec §4.1 L3 teardown；
  - `dataSync` 类型；
- **[新增]** `app/src/main/java/cn/vectory/ocdroid/service/di/ServiceModule.kt`（Hilt 提供 service 内部依赖）；
- **[修改]** `app/src/main/AndroidManifest.xml`（line 11 `<application>` 内）：
  - 在 line 5-9 permissions 块新增：
    ```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    ```
  - 在 line 36 `</activity>` 后、line 37 `<provider>` 前新增：
    ```xml
    <service
        android:name=".service.SessionStreamingService"
        android:foregroundServiceType="dataSync"
        android:exported="false" />
    ```
- 关联：FR-1/9/34 / V-1/6/8；开发设计 P0.1 / P0.9；FGS spec §1/§5/§13。

#### 3.1.2 新增 `ConnectionIdentity` + reconfigure 协议上移

- **[新增]** `app/src/main/java/cn/vectory/ocdroid/service/identity/ConnectionIdentity.kt`（data class + 6 步 reconfigure 状态机 helper）；
- **[修改]** `ui/controller/ConnectionCoordinator.kt`：
  - **[删除]** `private val directoryFetchGeneration = java.util.concurrent.atomic.AtomicLong(0L)`（line 150）——概念上移到 service；
  - **[修改]** `loadInitialData`（line 479-540）：不再 capture 本地 generation；改为读 service-side epoch；
  - **[修改]** `launchSseCollection`（line 707-770）→ **[删除整个方法]**：SSE 收集改由 service 内部完成；CC 不再持 sseJob；
  - **[修改]** `startSSE`（line 655-668）→ 改为 emit `ControllerEffect.RequestStartSse(identity)` 由 service 响应；
  - **[修改]** `cancelSse`（line 777-780）→ 改为 emit `ControllerEffect.RequestCancelSse(reason)` 由 service 响应；
  - **[修改]** `cancelSseForReconfigure`（line 793-806）→ 改为 emit `ControllerEffect.RequestCancelSseForReconfigure`，service 内 bump epoch + repository reconfigure + loadInitialData + start SSE；
- 关联：FR-2/3/4 / V-3/10；开发设计 P0.2；FGS spec §1/§2。

#### 3.1.3 新增 `StreamingLifecycleCoordinator`

- **[新增]** `app/src/main/java/cn/vectory/ocdroid/service/lifecycle/StreamingLifecycleCoordinator.kt`（应用级 `@Singleton`）：
  - 实现 L1/L2-active/L2-idle/L3 状态机；
  - 输入：`AppLifecycleMonitor.isInForeground`、`StatusAggregator.globalBusySnapshot`、用户关后台信号；
  - 输出：驱动 service `startForeground`/`stopForeground`/`stopSelf`、SSE 启停（通过 `ControllerEffect.Request*`）、poller 启停（通过 ALM）、notifier 数据源切换；
  - 持 45s idle debounce；
- **[新增]** `app/src/main/java/cn/vectory/ocdroid/service/lifecycle/Layer.kt`（sealed class：L1Idle/L1Busy/L2Active/L2Idle/L3）；
- **[修改]** `di/ControllerModule.kt`（或新建 `di/ServiceLifecycleModule.kt`）：Hilt 提供 `StreamingLifecycleCoordinator` 单例；
- 关联：FR-5/6/7/8 / V-1/9/13；开发设计 P0.3；FGS spec §4/§4.4。

#### 3.1.4 新增 `StatusAggregator`

- **[新增]** `app/src/main/java/cn/vectory/ocdroid/service/status/StatusAggregator.kt`（应用级 `@Singleton`）：
  - 复合键 `(serverGroupFp, workdir, sessionId) → Fresh|Busy|Retry|Idle|Unknown`；
  - Phase 0 主路径：消费 `OpenCodeRepository.getSessionStatus()`（host 级）+ `session.directory` 归并；
  - status TTL（30s）+ merge 时序（FGS spec §3.1）；
  - 暴露 `globalBusySnapshot: StateFlow<BusySnapshot>`（含 earliestBusyTransitionMs per session）；
- **[新增]** `app/src/main/java/cn/vectory/ocdroid/service/status/SessionBusyStatus.kt`（sealed class）；
- **[修改]** `ui/AppCore.kt:413-416`（`LoadSessionStatus` effect 处理）：改为路由到 `StatusAggregator.refresh()` 而非直接 `launchLoadSessionStatus`（保持向后兼容）；
- 关联：FR-11/12/13/15 / V-2/5；开发设计 P0.4；FGS spec §3/§3.1。

#### 3.1.5 新增 `ConnectionBootstrapCoordinator`（TOFU 抽离）

- **[新增]** `app/src/main/java/cn/vectory/ocdroid/service/bootstrap/ConnectionBootstrapCoordinator.kt`（应用级 `@Singleton`）：
  - 持 pending TOFU state（hostPort / CompletableDeferred）；
  - 提供 `suspend fun awaitTofuDecision(hostPort): TofuDecision` / `fun resolveTofuDecision(decision)` / `suspend fun bootstrap(...): BootstrapResult`；
  - 处理 FGS spec §5「未决 TOFU 且无 Activity」degraded 路径；
- **[修改]** `ui/controller/ConnectionCoordinator.kt`：
  - **[删除]** `private var pendingTofuHostPort`（line 121-122）+ `pendingTofuDecision`（line 123-124）；
  - **[修改]** `testConnection`（line 187-436）TOFU 段（line 312-393）：改为 delegate 到 `bootstrapCoordinator`；
  - **[删除]** `resolveTofuTrust(decision)`（line 678-681）：迁移到 `ConnectionBootstrapCoordinator.resolveTofuDecision`，CC 留 thin wrapper 或转发；
  - **[修改]** `startSSE`（line 655-668）/ `coldStartReconnect`（line 450-456）：用 `bootstrapCoordinator.isFrozen` 取代本地 `pendingTofuHostPort != null` 检查；
- **[修改]** `ui/ConnectionViewModel.kt`（或调用 `resolveTofuTrust` 的 UI 入口）：改为调 `bootstrapCoordinator.resolveTofuDecision`；
- 关联：FR-9/10 / V-6/8；开发设计 P0.5；FGS spec §5/§10。

#### 3.1.6 新增 `SseEventBridge` + 控制类事件独立通道

- **[新增]** `app/src/main/java/cn/vectory/ocdroid/service/bridge/SseEventBridge.kt`（`@UiApplicationScope` 单例）：
  - collect `service.events` → 校验 `ConnectionIdentity`（epoch 与当前 service epoch 一致）→ `emitEffect(OnSseEvent(IdentifiedSseEvent(identity, event)))`；
  - 控制类事件独立有界 `Channel`（容量如 64，满载 suspend，service 销毁时 cancel）；
  - delta 类事件走另一通路（有界 buffer + overflow 标 dirty）；
- **[修改]** `ui/controller/ControllerEffect.kt`（sealed class 定义文件）：
  - **[修改]** `data class OnSseEvent(val event: SSEEvent)` → `data class OnSseEvent(val identified: IdentifiedSseEvent)`；
- **[修改]** `ui/AppCore.kt:618-621`（`dispatchConnectionEffect` 的 `OnSseEvent` 分支）：
  - 二次校验 identity；
  - 提取 `identified.event` 传给 `sessionSyncCoordinator.handleEvent`；
  - SSC 内可二次校验 identity（FGS spec §1）；
- **[修改]** `ui/controller/SessionSyncCoordinator.kt:269-290`（`handleEvent`）：
  - 可选重载接 `IdentifiedSseEvent`，做二次校验；
- 关联：FR-3/25 / V-3/10/12；开发设计 P0.6；FGS spec §1/§11。

#### 3.1.7 修改 `ForegroundCatchUpController`（去无条件 CancelSse）

- **[修改]** `ui/controller/ForegroundCatchUpController.kt:145-153`（背景分支）：
  - **[删除]** `DebugLog.i("SSE", "cancelSse (background)")`（line 150）；
  - **[删除]** `effects.tryEmitEffect(ControllerEffect.CancelSse)`（line 152）；
  - **[保留]** `clearDraft()`（line 148）+ `backgroundedAtMs = clock()`（line 149）；
- **[保留]** `onServerConnected`（line 164-176）/ `onHostReconfigured`（line 183-186）/ `onForegroundChanged` 前台分支（line 113-144）：catch-up 逻辑不变；
- **[保留]** `catchUpPendingQuestionsAllWorkdirs`（line 247-264）：多 workdir pending 轮询不变；
- 关联：FR-5/8 / V-9/13；开发设计 P0.7；FGS spec §1.1。

#### 3.1.8 修改 `AppLifecycleMonitor`（前台信号源 + 收敛职责）

- **[修改]** `di/AppLifecycleMonitor.kt:92`：
  - **[修改]** `private val _isInForeground = MutableStateFlow(true)` → `MutableStateFlow(false)`（FGS spec §4.3）；
- **[保留]** `activityStartedCount`（line 112）+ `registerActivityLifecycleCallbacks`（line 114-139）：前台事实源不变；
- **[保留]** `pollJob`（line 105）/ `startBackgroundPolling`（line 180-190）/ `pollPendingItems`（line 192-213）：30s poller 机制保留，作为 L2-idle / L3 fallback；后续 P1.5 才迁 notify 逻辑；
- 关联：FR-7 / NFR-14；开发设计 P0.8；FGS spec §4.3/§6。

#### 3.1.9 Phase 0 强制门禁测试

- **[新增]** `app/src/test/java/cn/vectory/ocdroid/service/lifecycle/StreamingLifecycleCoordinatorTest.kt`：状态机切换、debounce、L1→L2 提升时序；
- **[新增]** `app/src/test/java/cn/vectory/ocdroid/service/status/StatusAggregatorTest.kt`：merge 时序、TTL、directory-status 隔离探针（V-2）；
- **[新增]** `app/src/test/java/cn/vectory/ocdroid/service/SessionStreamingServiceTest.kt`（Robolectric）：START_STICKY bootstrap、占位通知、SSEConnectionExhausted 重试；
- **[新增]** `app/src/androidTest/java/cn/vectory/ocdroid/service/SessionStreamingServiceInstrumentedTest.kt`（需模拟器，按 AGENTS.md §「设备安全」纪律）：dataSync 平台矩阵、onTimeout、task-swipe、force-stop、OEM 杀进程；
- **[修改]** 现有 `app/src/test/java/cn/vectory/ocdroid/ui/controller/ConnectionCoordinatorTest.kt`：迁移 stale-host 场景（V-3）、background+busy 时 SSE 不被误停（V-13）；
- 关联：V-1/2/3/13；开发设计 P0.10；FGS spec §14.0/§14.1。

### 3.2 Phase 1 改动

#### 3.2.1 新增 `IslandNotifier` 接口 + `BaseOngoingNotifier`

- **[新增]** `app/src/main/java/cn/vectory/ocdroid/notify/IslandNotifier.kt`（接口 + 数据类：`OngoingState`/`CompletionState`/`DecisionState`/`ErrorState`/`NotificationId`）；
- **[新增]** `app/src/main/java/cn/vectory/ocdroid/notify/BaseOngoingNotifier.kt`（`@Singleton`，实现 base ongoing，全机型兜底）；
- **[新增]** `app/src/main/java/cn/vectory/ocdroid/notify/NotificationDedupStore.kt`（复合业务键 `(serverGroupFp, workdir, sessionId, type)`，grow-only，由 ALM.notificationSnapshot 迁移扩展）；
- **[新增]** `app/src/main/java/cn/vectory/ocdroid/notify/di/NotifyModule.kt`（Hilt）；
- 关联：FR-16/17/18/20/25/30 / V-14/15/16；开发设计 P1.1；FGS spec §6/§7/§12。

#### 3.2.2 新增 `CompletionDetector`

- **[新增]** `app/src/main/java/cn/vectory/ocdroid/notify/CompletionDetector.kt`（`@Singleton`）：
  - 实现 FGS spec §8 完成通知状态机；
  - 订阅 `SseEventBridge` 的 control channel + `StatusAggregator.globalBusySnapshot`；
  - 抑制：baseline / SSE 重连首帧 idle（先 reconcile）/ host 切换 / abort/error/archive / 前台不发；
  - snapshot 去重；
- 关联：FR-21/22/23/27 / V-15；开发设计 P1.3；FGS spec §8。

#### 3.2.3 新增 channel `ocdroid.session_status` + `ocdroid.session_complete`

- **[修改]** `di/AppLifecycleMonitor.kt:340-360`（`createChannels`）：
  - 新增 `ocdroid.session_status`（LOW）+ `ocdroid.session_complete`（DEFAULT, autoCancel）；
  - **[保留]** `ocdroid.decisions`（line 344-350）/ `ocdroid.errors`（line 351-357）：ID 不变；
- **[修改]** `di/AppLifecycleMonitor.kt:322-374`（companion）：新增 `CHANNEL_SESSION_STATUS = "ocdroid.session_status"` + `CHANNEL_SESSION_COMPLETE = "ocdroid.session_complete"` 常量；`SESSION_STATUS_NOTIFICATION_ID` 固定值；
- 关联：FR-17 / V-19；开发设计 P1.4；FGS spec §7。

#### 3.2.4 通知发布迁移：ALM → IslandNotifier

- **[修改]** `di/AppLifecycleMonitor.kt`：
  - **[删除]** `notifyDecision`（line 264-282）：迁移到 `BaseOngoingNotifier.showDecision`；
  - **[删除]** `notifyError`（line 286-298）：迁移到 `BaseOngoingNotifier.showError`；
  - **[删除]** `notificationSnapshot`（line 102）：迁移到 `NotificationDedupStore`；
  - **[修改]** `handlePendingPermission`（line 215-234）/ `handlePendingQuestion`（line 236-251）：改为 emit `DecisionState` 给 `IslandNotifier`；
  - **[修改]** `onAppError`（line 147-154）：改为 emit `ErrorState` 给 `IslandNotifier`；
  - **[保留]** `pollJob`（line 105）/ `startBackgroundPolling`（line 180-190）/ `pollPendingItems`（line 192-213）：30s poller 机制不变；
- 关联：FR-25 / V-16/19；开发设计 P1.5；FGS spec §6。

#### 3.2.5 Settings 通知页修复

- **[修改]** `app/src/main/java/cn/vectory/ocdroid/ui/settings/`（具体文件待定，按现有 SettingsScreen 结构）：
  - 新增「每 channel 状态」展示 + 跳系统 channel 设置（`Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, ...)`）；
  - 新增通知权限被拒降级文案（「后台保活受 dataSync 6h/24h 与厂商策略限制，不保证永久」）；
- 关联：FR-31/32/33 / V-19；开发设计 P1.6；FGS spec §7/groker-M2。

### 3.3 Phase 2 改动（Live Updates）

#### 3.3.1 升级 AndroidX Core

- **[修改]** `gradle/libs.versions.toml`：`androidx-core = "..."` → 1.17.0+；
- **[修改]** `app/build.gradle.kts`：`implementation(libs.androidx.core)` 自动跟随；
- 关联：FR-28 / NFR-11 / V-17；开发设计 P2.1。

#### 3.3.2 新增 `LiveUpdateDecorator`

- **[新增]** `app/src/main/java/cn/vectory/ocdroid/notify/LiveUpdateDecorator.kt`（wraps base，API36+ 叠加 `NotificationCompat.ProgressStyle` + status chip）；
- **[修改]** `app/src/main/AndroidManifest.xml`（line 5-9 permissions）：新增 `<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />`（API36+）；
- **[修改]** `app/src/main/java/cn/vectory/ocdroid/notify/di/NotifyModule.kt`：装配 pipeline 时若 SDK≥36 套 `LiveUpdateDecorator`；
- 关联：FR-28/34 / V-17；开发设计 P2.2；FGS spec §12/§13 Phase 2。

### 3.4 Phase 3 改动（小米超级岛）

#### 3.4.1 新增 `XiaomiIslandDecorator`

- **[新增]** `app/src/main/java/cn/vectory/ocdroid/notify/XiaomiIslandDecorator.kt`（检测 `Settings.System.getInt("notification_focus_protocol", 0) ∈ {2,3}`，extras 附加 `miui.focus.param` JSON）；
- **[修改]** `app/src/main/java/cn/vectory/ocdroid/notify/di/NotifyModule.kt`：pipeline 装配；
- 可选：`app/build.gradle.kts` 新增依赖 `HyperIsland-ToolKit`（社区库）；
- 关联：FR-29 / V-18；开发设计 P3.1；FGS spec §12/§13 Phase 3。

### 3.5 改动汇总表（按文件）

| 文件路径 | 改动类型 | Phase | 关键行号 |
|---|---|---|---|
| `app/src/main/AndroidManifest.xml` | 修改 | 0/2 | line 5-9（perm）、line 36-37（service） |
| `app/src/main/java/cn/vectory/ocdroid/service/SessionStreamingService.kt` | 新增 | 0 | 全新 |
| `app/src/main/java/cn/vectory/ocdroid/service/di/ServiceModule.kt` | 新增 | 0 | 全新 |
| `app/src/main/java/cn/vectory/ocdroid/service/identity/ConnectionIdentity.kt` | 新增 | 0 | 全新 |
| `app/src/main/java/cn/vectory/ocdroid/service/lifecycle/StreamingLifecycleCoordinator.kt` | 新增 | 0 | 全新 |
| `app/src/main/java/cn/vectory/ocdroid/service/lifecycle/Layer.kt` | 新增 | 0 | 全新 |
| `app/src/main/java/cn/vectory/ocdroid/service/status/StatusAggregator.kt` | 新增 | 0 | 全新 |
| `app/src/main/java/cn/vectory/ocdroid/service/status/SessionBusyStatus.kt` | 新增 | 0 | 全新 |
| `app/src/main/java/cn/vectory/ocdroid/service/bootstrap/ConnectionBootstrapCoordinator.kt` | 新增 | 0 | 全新 |
| `app/src/main/java/cn/vectory/ocdroid/service/bridge/SseEventBridge.kt` | 新增 | 0 | 全新 |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/ConnectionCoordinator.kt` | 修改 | 0 | line 108、121-124、150、479-540、655-681、707-806 |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/ForegroundCatchUpController.kt` | 修改 | 0 | line 145-153（删除 CancelSse） |
| `app/src/main/java/cn/vectory/ocdroid/di/AppLifecycleMonitor.kt` | 修改 | 0/1 | line 92（默认 false）、line 102、147-154、215-298、340-360 |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/ControllerEffect.kt` | 修改 | 0 | `OnSseEvent` 增加 identity |
| `app/src/main/java/cn/vectory/ocdroid/ui/AppCore.kt` | 修改 | 0/1 | line 391-395、413-416、540-592、595-623、664-668 |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/SessionSyncCoordinator.kt` | 修改（可选） | 0 | line 269-290（二次校验 identity） |
| `app/src/main/java/cn/vectory/ocdroid/notify/IslandNotifier.kt` | 新增 | 1 | 全新 |
| `app/src/main/java/cn/vectory/ocdroid/notify/BaseOngoingNotifier.kt` | 新增 | 1 | 全新 |
| `app/src/main/java/cn/vectory/ocdroid/notify/NotificationDedupStore.kt` | 新增 | 1 | 全新 |
| `app/src/main/java/cn/vectory/ocdroid/notify/CompletionDetector.kt` | 新增 | 1 | 全新 |
| `app/src/main/java/cn/vectory/ocdroid/notify/di/NotifyModule.kt` | 新增 | 1/2/3 | 全新 |
| `app/src/main/java/cn/vectory/ocdroid/notify/LiveUpdateDecorator.kt` | 新增 | 2 | 全新 |
| `app/src/main/java/cn/vectory/ocdroid/notify/XiaomiIslandDecorator.kt` | 新增 | 3 | 全新 |
| `gradle/libs.versions.toml` | 修改 | 2 | androidx-core |
| `app/build.gradle.kts` | 修改 | 2/3 | core 跟随 + 可选 HyperIsland-ToolKit |
| `app/src/main/java/cn/vectory/ocdroid/ui/settings/` | 修改 | 1 | 通知页（具体文件待定） |

---

## 4. 风险与待验证项

> 与 FGS spec §15 对齐；本节给出**实施视角**的具体测试方法。

### 4.1 服务端：`/session/status` 是否真为 host-global

- **风险**：FGS spec §3 Phase 0 主路径假设 `getSessionStatus()` 返回 host 级、覆盖所有 workdir；若实测只返回 current workdir，主路径失效。
- **验证方法**（V-2 强制门禁）：
  1. 启动 ocdroid，连接 host，开 workdir-A 的 session 并发 prompt（使其 busy）；
  2. 切到 workdir-B，开 session 并发 prompt（使其 busy）；
  3. 此时用 `repository.getSessionStatus()` 单次调用；
  4. 期望：返回 Map 同时含 A 与 B 的 sessionId，且两者 status.type 都为 `busy`；
  5. 若只返回 current workdir（B）→ 升级为 directory fan-out（每 workdir 显式查询），复合键不变。
- **影响代码**：`StatusAggregator` 主路径分支；若升级 fan-out，新增 `OpenCodeApi.getSessionStatusForDirectory(directory)` 方法（待服务端确认是否支持）。

### 4.2 厂商 dataSync FGS 杀进程策略

- **风险**：小米 / 华为等厂商对 dataSync FGS 长连接有激进杀进程策略（FGS spec §15 / NFR-7），可能导致 L2-active 期间连接意外断开且 `START_STICKY` 不被 honoring。
- **验证方法**（V-1 平台矩阵子项）：
  1. 小米 / 华为实机各一台；
  2. 启 ocdroid，发起 long-running session，按 Home 进后台（L2-active）；
  3. 等 5 / 30 / 60 / 180 分钟，观察 ongoing 通知是否还在、SSE 是否还流；
  4. 用 `adb shell dumpsys activity services cn.vectory.ocdroid` 看 service 状态；
  5. 用 `adb shell ps | grep ocdroid` 看进程是否被杀。
- **影响代码**：可能需要在 `SessionStreamingService.onTimeout` / `onDestroy` 加更激进的 poller fallback；或在 `StreamingLifecycleCoordinator` 加「检测到 service 被杀」自恢复（受限）。

### 4.3 dataSync 6h/24h 限额（targetSdk 35+）

- **风险**：targetSdk 升到 35+ 后，dataSync FGS 受 6h（前台累计）/24h（后台累计）限额，`onTimeout()` 强制回调（FGS spec §15 / NFR-6）。
- **验证方法**（V-1 平台矩阵子项）：
  1. 当前 targetSdk（build.gradle.kts 中）+ 模拟 targetSdk 35 行为（adb 模拟或临时改 target）；
  2. 启 service 并持续 6h+，观察 `onTimeout()` 是否被调；
  3. 期望：`onTimeout()` 后 service 进 L3、cancel ongoing、停 SSE、起 poller、发用户通知「后台保活到期」。
- **影响代码**：`SessionStreamingService.onTimeout()`（API 34+）+ `StreamingLifecycleCoordinator` 的 L3 transition + Settings 文案（FR-33）。
- **决策点**：targetSdk 升级时间表与本功能是否同发（FGS spec §15）。

### 4.4 `START_STICKY` 重启矩阵

- **风险**：`START_STICKY` 在不同场景下行为差异大（FGS spec §5 / NFR-9）：
  - 主动 `stopSelf()` → 不重启；
  - 系统内存压力杀进程 → 可能重启（null Intent）；
  - force-stop（用户在 Settings 强行停止）→ **不**重启；
  - task-swipe（最近任务清卡片）→ 行为依 OEM 而定；
  - dataSync `onTimeout()` 后 → 不重启（FGS 已撤）。
- **验证方法**（V-1 / V-6）：
  1. 每种场景实测 service 是否被系统拉起、null Intent 是否到达；
  2. null Intent 路径按 FGS spec §4.3 = 后台处理，且为合法 FGS-start 上下文（`startForeground` 不会被拒）。
- **影响代码**：`SessionStreamingService.onStartCommand` 的 null Intent 分支 + bootstrap 流程。

### 4.5 L1→L2 提升 race

- **风险**：前台进入 busy 时立即 `startForeground`（合法），但「进入 busy」的判定与「Activity ON_STOP」之间有 race——若 Activity 先 ON_STOP 再判 busy，会触发 `ForegroundServiceStartNotAllowedException`（FGS spec §4.2 / V-13）。
- **验证方法**（V-13）：
  1. 启 ocdroid 前台，发起 prompt；
  2. **立即**按 Home（在 status busy 到达前）；
  3. 期望：要么 status 已 busy 在按 Home 前到达（提升成功）、要么按 Home 后才 busy（不尝试后台提升，直入 L3）。
- **影响代码**：`StreamingLifecycleCoordinator` 的 L1-busy 检测必须用「Activity started-count > 0」作为前台事实源（§4.3），不能依赖 `_isInForeground` 的滞后 emission。

### 4.6 decorator pipeline 叠加顺序

- **风险**：`LiveUpdateDecorator` 与 `XiaomiIslandDecorator` 叠加时（API36+ 小米设备），Xiaomi 的 extras 是否会覆盖 LiveUpdate 的 ProgressStyle（开发设计 §6 R6）。
- **验证方法**：实机 API36+ 小米设备（HyperOS 3）测试同一通知在 status bar / lock screen / 岛的显示；若冲突，调整 pipeline 顺序或 extras 合并策略。
- **影响代码**：`NotifyPipeline` 装配顺序（开发设计 §4.4）。

### 4.7 通知 ID 碰撞

- **风险**：复合业务键 `(serverGroupFp, workdir, sessionId, type).hashCode()` 在大量 session 时可能碰撞（FR-16）。
- **验证方法**：构造 ≥1000 个复合键测 `hashCode` 分布；高冲突场景用 `NotificationId` 显式数据类 + 稳定哈希（如 MurmurHash）或直接用稳定字符串 tag + int id。
- **影响代码**：`NotificationId` 实现与 `NotificationManagerCompat.notify(tag, id, ...)` 调用形态。

### 4.8 `miui.focus.param` 格式版本

- **风险**：HyperOS 2（焦点通知）与 HyperOS 3（超级岛）的 `miui.focus.param` JSON 格式可能有差异（开发设计 §6 R8）。
- **验证方法**：参考小米官方文档（dynamic-island-plan §6）+ 两版本各一台设备测试；JSON 解析容错（忽略未知字段）。
- **影响代码**：`XiaomiIslandDecorator` 的 JSON 构建逻辑。

---

## 5. 关键现有测试文件（迁移 / 不破坏）

> 实施时必须保持以下测试 green（或主动迁移）。

| 测试文件 | 测什么 | Phase 0 影响 |
|---|---|---|
| `app/src/test/java/cn/vectory/ocdroid/ui/controller/ConnectionCoordinatorTest.kt` | stale-host 场景、SSE generation guard、TOFU freeze、background directory fan-out | **必须迁移**：stale-host 改为 service-side epoch、TOFU 改为 delegate 到 bootstrap coordinator |
| `app/src/test/java/cn/vectory/ocdroid/ui/controller/ForegroundCatchUpControllerTest.kt` | 三档 catch-up、suppressNextConnectCatchUp、background draft clear | **修改**：背景分支不再 emit CancelSse；测试断言相应更新 |
| `app/src/test/java/cn/vectory/ocdroid/ui/controller/SessionSyncCoordinatorTest.kt` | SSE event fold、reconcileGap、hostGeneration | **基本不变**（fold 不搬）；可选新增二次校验 identity 测试 |
| `app/src/test/java/cn/vectory/ocdroid/ui/controller/SessionSyncGapReconcileTest.kt` | §P1-10 gap reconciliation overlay | **不变** |
| `app/src/test/java/cn/vectory/ocdroid/data/repository/OpenCodeRepositoryTest.kt` | repository facade（2-arg 构造 locked） | **不变**（OpenCodeRepository 不改公共签名） |
| `app/src/test/java/cn/vectory/ocdroid/di/AppLifecycleMonitorTest.kt`（如有） | foreground 信号、poller、notify | **修改**：默认 false 断言；notify 迁移后改 mock IslandNotifier |

---

## 6. 与现有 ocdroid 治理流程的关系

> 引用 AGENTS.md。

- **改动校验必做**（AGENTS.md §硬规则）：每次 Kotlin/资源改后必须 `./scripts/check.sh` 通过（本服务/通知改动量较大，预计 check 时长偏长，预留时间）；
- **设备安全**（AGENTS.md §硬规则）：`SessionStreamingServiceInstrumentedTest` 等插桩测试**仅用模拟器**（`./scripts/emulator.sh status` → `start` → 测试 → `stop`）；不得在物理 Android 手机上跑；
- **版本号**（AGENTS.md §硬规则）：`versionName`=git describe、`versionCode`=commit count，禁止手改 `app/build.gradle.kts`；
- **发版**：走 `./scripts/release.sh patch|minor|major`；Phase 0 完成后建议出 minor 版本；
- **不重写 FGS spec**：本文件任何与 FGS spec 不一致的，以 FGS spec 为准。

---

## 7. 变更记录

| 日期 | 变更 | 作者 |
|---|---|---|
| 2026-07-13 | 初版（基于 main 分支当前快照 + FGS spec + dynamic-island-plan） | Fixer agent |
