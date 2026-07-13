# 通知 / 后台 SSE 保活 / 通知卡片视觉 — 开发设计文档 (ocdroid)

> 状态：**开发设计文档（面向实现）**。
> **不重写 spec**：本文件**仅一句话引用** [FGS spec](./2026-07-13-notification-background-fgs-design.md)（`2026-07-13-notification-background-fgs-design.md`，已过 gpter/groker 双 9.5 门）为权威设计源，并给出**实施视角的落地指南**——按 Phase 列任务清单、补足通知卡片视觉设计（FGS spec 中偏略的部分）、明确与现有 channel 的关系、给出 IslandNotifier decorator pipeline 的接口草图。
> 配套：[需求文档](./2026-07-13-notification-background-requirements.md)、[代码调研文档](./2026-07-13-notification-background-code-research.md)。

---

## 0. 一句话引用

**所有架构决策、生命周期状态机、连接身份协议、busy 权威源、abort 策略、channel 矩阵、IslandNotifier decorator pipeline 的权威定义在 [FGS spec](./2026-07-13-notification-background-fgs-design.md)。本文件不重述其论证；任何此处与 FGS spec 描述不一致的，以 FGS spec 为准。**

---

## 1. 实施视角的落地指南（按 Phase 任务清单）

> 每个 Phase 内任务**强依赖单向流动**：上游任务未完，下游无意义。任务编号 `P<phase>.<seq>`，与需求文档 §6 对齐。
> **改哪些文件 / 新增哪些类 / 依赖 / 验收**——具体行号见代码调研文档 §3「改动影响图」。

### 1.1 Phase 0 — 让 FGS 正确的最小闭环

#### P0.1 新增 `SessionStreamingService`（ForegroundService）

- **新增**：`app/src/main/java/cn/vectory/ocdroid/service/SessionStreamingService.kt`
  - 职责（FGS spec §1.0）：**仅作 Android 组件壳 + SSE 连接 owner + FGS 通知发布**，不堆业务逻辑；
  - 暴露进程级 `events: SharedFlow<Result<IdentifiedSseEvent>>`（`IdentifiedSseEvent(identity: ConnectionIdentity, event: SSEEvent)`）；
  - `dataSync` 类型；`START_STICKY`；
  - `onCreate`/`onStartCommand`：null Intent 安全（按 FGS spec §4.3 = 后台，合法 FGS-start 上下文）；
  - 同步读最小持久配置 → 立即 `startForeground(占位通知)` → 异步 bootstrap（tunnel/health/TOFU/status）→ busy 保持 FGS / 全 idle 进 L3。
- **新增**：`app/src/main/java/cn/vectory/ocdroid/service/di/ServiceModule.kt`（Hilt `@ServiceScope` 或 `@Singleton` 提供 service 内部依赖）。
- **依赖**：P0.2（identity）/ P0.3（协调器）/ P0.5（TOFU 抽离）。
- **验收**：FR-1/2/9；V-6（sticky null Intent 占位先贴）/ V-7（SSEConnectionExhausted service 级长期重试）。

#### P0.2 新增 `ConnectionIdentity` + 6 步 reconfigure 协议

- **新增**：`app/src/main/java/cn/vectory/ocdroid/service/identity/ConnectionIdentity.kt`（data class：`epoch: Long, serverGroupFp: String, normalizedWorkdir: String, endpointFp: String`）。
- **修改**：`ui/controller/ConnectionCoordinator.kt` 的 `directoryFetchGeneration`（line 150）概念**上移**到 service 持有的 identity；CC 的 `cancelSseForReconfigure`（line 793-806）改为 emit 一个 service-side epoch bump effect，**不再**自己持 epoch。
- **关键约束**（FGS spec §2）：epoch 同时作 SSE collector 守卫 **+** directory-fetch 守卫——统一为单一 epoch，不允许 CC 私有第二个 generation。
- **验收**：V-3（host 切换不污染）/ V-10（sessionId 跨 host 碰撞拦截）。

#### P0.3 新增 `StreamingLifecycleCoordinator`（应用级 `@Singleton`）

- **新增**：`app/src/main/java/cn/vectory/ocdroid/service/lifecycle/StreamingLifecycleCoordinator.kt`
  - 实现 FGS spec §4 分层状态机（L1/L2/L3）、§4.4 统一交接顺序（持锁、串行：新 source active + notifier 切换成功后才关旧 source）；
  - 输入：`AppLifecycleMonitor.isInForeground`（§4.3 不再默认 true）、`StatusAggregator.globalBusy`、用户显式关后台信号；
  - 输出：驱动 service 的 `startForeground`/`stopForeground`/`stopSelf`、SSE 启停、poller 启停、notifier 数据源切换；
  - 持有 45s idle debounce 计时。
- **依赖**：P0.1 / P0.4（StatusAggregator）/ P0.6（交接参与方）。
- **验收**：V-1（dataSync 平台矩阵）/ V-9（poller 交接双向无空窗）/ V-13（L1→L2 提升时序）。

#### P0.4 新增 `StatusAggregator`（应用级 `@Singleton`）

- **新增**：`app/src/main/java/cn/vectory/ocdroid/service/status/StatusAggregator.kt`
  - 复合键 `(serverGroupFp, workdir, sessionId) → Fresh|Busy|Retry|Idle|Unknown`；
  - Phase 0 主路径：消费 `OpenCodeRepository.getSessionStatus()`（host 级，line 500-502）+ `session.directory` 归并；
  - status TTL（30s）；所有 REST 响应校验 epoch；
  - merge 时序规则（FGS spec §3.1）：带「来源时间」，REST 不得覆盖其 request-start 之后到达的更新 SSE 状态。
- **依赖**：P0.2（identity / epoch）。
- **验收**：V-2（directory-status 隔离探针）/ V-5（后台非当前 workdir busy 不误停 SSE）。

#### P0.5 新增 `ConnectionBootstrapCoordinator`（应用级 `@Singleton`）

- **新增**：`app/src/main/java/cn/vectory/ocdroid/service/bootstrap/ConnectionBootstrapCoordinator.kt`
  - FGS spec §10：将 `ConnectionCoordinator` 私有的 `pendingTofuHostPort`（line 121-122）/ `pendingTofuDecision`（line 123-124）/ `resolveTofuTrust`（line 678-681）**抽到应用级共享**；
  - CC 与 Service 共同调用同一 coordinator；
  - 处理 §5「未决 TOFU 且无 Activity」degraded 路径（不无限等 UI deferred、不消耗 SSE 重试预算、占位通知加 action 引导打开 Activity）。
- **修改**：`ConnectionCoordinator.kt` 删除私有 TOFU 字段 + 方法，改为 delegate。
- **依赖**：P0.1 / P0.2。
- **验收**：FR-9/10；V-6（bootstrap 占位先贴）/ V-8（FGS 启动被拒降级）。

#### P0.6 新增 `SseEventBridge`（应用级 `@UiApplicationScope` 单例）

- **新增**：`app/src/main/java/cn/vectory/ocdroid/service/bridge/SseEventBridge.kt`
  - FGS spec §1：collect `Service.events` → 校验 `ConnectionIdentity` → `emit(ControllerEffect.OnSseEvent(IdentifiedSseEvent))`；
  - 现有 `AppCore.dispatchConnectionEffect` 的 `OnSseEvent` 分支（line 618-621）改为接 `IdentifiedSseEvent`，二次校验后传 `event` 给 SSC.fold（**SSC 不感知 identity 包装**这一点违反 FGS spec §1——必须保留 identity 给 SSC 二次校验）；
  - **控制类事件独立通道**（FGS spec §11）：`session.status`/`server.connected`/permission/question 走独立有界 Channel（满载 suspend，不 DROP_OLDEST，FIFO，service 销毁时 drain/cancel）。
- **修改**：`ui/controller/ControllerEffect.kt`（`OnSseEvent` 增加 identity）/ `ui/AppCore.kt`（dispatchConnectionEffect line 595-623）。
- **依赖**：P0.1 / P0.2。
- **验收**：V-3（host 切换不污染）/ V-10 / V-12（delta overflow 控制事件不被挤出）。

#### P0.7 修改 `ForegroundCatchUpController`（去背景无条件 CancelSse）

- **修改**：`ui/controller/ForegroundCatchUpController.kt` 的 `onForegroundChanged(false)` 分支（line 145-153）：
  - **删除** line 150-152 的 `DebugLog.i("SSE", "cancelSse (background)")` + `effects.tryEmitEffect(ControllerEffect.CancelSse)`；
  - 背景分支**只保留** `clearDraft()`（line 148）+ `backgroundedAtMs = clock()`（line 149）；
  - SSE 启停**只**由 `StreamingLifecycleCoordinator`（P0.3）驱动。
- **CancelSse 双职责拆分**（FGS spec §1.1）：有意停 SSE（idle/debounce）→ 必须留下与今日 `CancelSse` 等价的 gap-dirty（`SseSyncState.reconcileGap(Disconnected…)`，已存在于 SSC line 231-242）；`CancelSseForReconfigure` 不变（CC line 793-806）。
- **依赖**：P0.3。
- **验收**：V-9（poller 交接双向无空窗）/ V-13（不误停应保活的 SSE）。

#### P0.8 修改 `AppLifecycleMonitor`（前台信号源 + poller 宿主）

- **修改**：`di/AppLifecycleMonitor.kt`：
  - `_isInForeground = MutableStateFlow(true)`（line 92）**改为 false**——不再默认 true（FGS spec §4.3）；
  - 收敛职责：进程前台信号源 + poller 机制宿主；**不再**被当作 SSE-cancel 所有者（P0.7 已删）；
  - 现有 30s 轮询器（line 180-213）保留——但只走 `currentWorkdir`，与 `loadPendingQuestionsAllWorkdirs` 的多 workdir 轮询**独立**（不混）。
- **依赖**：P0.3 / P0.7。
- **验收**：FR-7 / NFR-14。

#### P0.9 权限声明 + AndroidManifest service

- **修改**：`app/src/main/AndroidManifest.xml`：
  - 新增 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />`；
  - 新增 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />`（API 34+）；
  - 新增 `<service android:name=".service.SessionStreamingService" android:foregroundServiceType="dataSync" android:exported="false" />`。
- **依赖**：P0.1。
- **验收**：FR-34 / NFR-5。

#### P0.10 强制门禁测试（FGS spec §14.0）

- **新增**：`app/src/test/java/cn/vectory/ocdroid/service/` 下的测试：
  - dataSync 平台矩阵测试（覆盖 targetSdk 当前 + 35+：`onTimeout()`、`ForegroundServiceStartNotAllowedException`、task-swipe、force-stop、通知权限拒绝、OEM 杀进程）；
  - directory-status 隔离探针（两 workdir 各有 active session，全局 status 须同时反映）。
- **新增**：`app/src/androidTest/java/.../SessionStreamingServiceInstrumentedTest.kt`（需模拟器，按 AGENTS.md §「设备安全」纪律）。
- **依赖**：P0.1-P0.9 全部。
- **验收**：V-1 / V-2（**未过不得 ship Phase 0**）。

### 1.2 Phase 1 — 通知展示层

#### P1.1 新增 `IslandNotifier` 接口 + base ongoing 实现

- **新增**：`app/src/main/java/cn/vectory/ocdroid/notify/IslandNotifier.kt`（接口：`suspend fun showOngoing(state: OngoingState)` / `suspend fun showCompletion(state: CompletionState)` / `suspend fun showError(state: ErrorState)` / `suspend fun showDecision(state: DecisionState)` / `suspend fun cancel(id: NotificationId)`）；
- **新增**：`app/src/main/java/cn/vectory/ocdroid/notify/BaseOngoingNotifier.kt`（实现 base ongoing，全机型兜底）；
  - 统一 dedup store：复合业务键 `(serverGroupFp, workdir, sessionId, type)`（FGS spec §6）；
  - 固定 ID：`SESSION_STATUS_NOTIFICATION_ID`（ongoing）/ `4242`（error）/ decisions 复合键 / completion 稳定复合键（FGS spec §7）。
- **依赖**：P0.4（StatusAggregator 提供状态输入）。
- **验收**：V-14 / V-15 / V-16 / V-19。

#### P1.2 全 workdir 聚合 ongoing「N 任务」+ chronometer + abort

- **修改 / 新增**：`BaseOngoingNotifier` 内的 ongoing 渲染：
  - 内容见本文档 §3.1（通知卡片视觉）；
  - chronometer 起点 = 聚合内最早 busy transition（FGS spec §7）——`StatusAggregator` 须暴露该 timestamp；
  - 单/多 busy abort 分流（§9）。
- **依赖**：P1.1。
- **验收**：V-14 / V-11。

#### P1.3 完成通知（§8 状态机）

- **新增**：`CompletionDetector`（应用级 `@Singleton`）实现 FGS spec §8 完成通知状态机：
  - 仅当**同一 identity** 下明确观察过 busy/retry 后观察到**权威 idle** 才发；
  - 抑制：baseline / SSE 重连首帧 idle（先 reconcile）/ host 切换 / abort/error/archive 终态 / **前台不发**（走应用内反馈）；
  - snapshot 去重（类似现有 `notificationSnapshot` ALM line 102）。
- **依赖**：P0.4 / P0.6（事件源）/ P1.1。
- **验收**：V-15。

#### P1.4 新增 channel `ocdroid.session_complete`（DEFAULT, autoCancel）

- **修改**：`di/AppLifecycleMonitor.kt` 的 `createChannels`（line 340-360）：
  - 新增 `ocdroid.session_status`（LOW，ongoing）；
  - 新增 `ocdroid.session_complete`（DEFAULT, autoCancel）。
- **依赖**：P1.1。
- **验收**：FR-17。

#### P1.5 现有 decisions/errors 通知迁移到 IslandNotifier

- **修改**：`di/AppLifecycleMonitor.kt`：
  - 删除 `notifyDecision`（line 264-282）/ `notifyError`（line 286-298）；
  - `pollPendingItems`（line 192-213）/ `onAppError`（line 147-154）改为 emit `DecisionState` / `ErrorState` 给 `IslandNotifier`；
  - `notificationSnapshot`（line 102）**移到 IslandNotifier 的统一 dedup store**。
- **依赖**：P1.1。
- **验收**：V-16 / V-19。

#### P1.6 Settings 文案 / channel 修复

- **修改**：`ui/settings/`（具体文件待定，见代码调研文档）：
  - 通知页展示**每 channel** 状态 + 跳系统 channel 设置；
  - 通知权限被拒降级文案（明确「后台保活受 dataSync 6h/24h 与厂商策略限制，不保证永久」）。
- **依赖**：P1.4。
- **验收**：FR-31/32/33 / V-19。

### 1.3 Phase 2 — Live Updates（附录，非 MVP 门）

#### P2.1 升级 AndroidX Core ≥ 1.17.0

- **修改**：`app/build.gradle.kts` / `gradle/libs.versions.toml`：`androidx.core:core-ktx` → 1.17.0+。
- **依赖**：无。
- **验收**：编译通过。

#### P2.2 `LiveUpdateDecorator`（API36+）

- **新增**：`app/src/main/java/cn/vectory/ocdroid/notify/LiveUpdateDecorator.kt`（wraps `BaseOngoingNotifier`，叠加 `NotificationCompat.ProgressStyle` + status chip + chronometer）；
- **修改**：`AndroidManifest.xml` 新增 `<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />`（API36+）。
- 内容见 §3.3。
- **依赖**：P1.1 / P2.1。
- **验收**：V-17。

### 1.4 Phase 3 — 小米超级岛（附录，需审核白名单）

#### P3.1 `XiaomiIslandDecorator`（`focus_protocol∈{2,3}`）

- **新增**：`app/src/main/java/cn/vectory/ocdroid/notify/XiaomiIslandDecorator.kt`（检测 `Settings.System.getInt("notification_focus_protocol", 0)`，extras 附加 `miui.focus.param` JSON）；
- 可选依赖社区库 `HyperIsland-ToolKit`（dynamic-island-plan §2.1）。
- 内容见 §3.4。
- **依赖**：P1.1 + 小米审核白名单通过。
- **验收**：V-18。

---

## 2. 与现有 channel / 通知机制的关系

> 现状：`AppLifecycleMonitor.kt` line 325-326 已有 2 个 channel：
> - `ocdroid.decisions`（IMPORTANCE_HIGH）— permission/question，30s 轮询器弹；
> - `ocdroid.errors`（IMPORTANCE_DEFAULT）— 错误，固定 ID `4242`。

### 2.1 channel 矩阵（FGS spec §7 全量）

| channel | importance | 用途 | 现状 | Phase |
|---|---|---|---|---|
| `ocdroid.session_status` | LOW | ongoing：L1「已连接」/ L2-active「N 任务进行中」+chronometer+abort / L2-idle「空闲监听」 | **新增** | 0/1 |
| `ocdroid.session_complete` | DEFAULT, autoCancel | 完成通知（仅后台） | **新增** | 1 |
| `ocdroid.decisions` | HIGH | permission/question（仅后台） | 现有，Phase 1 迁移到 IslandNotifier | -/1 |
| `ocdroid.errors` | DEFAULT | 错误（含 SSEConnectionExhausted） | 现有，Phase 1 迁移到 IslandNotifier | -/1 |

### 2.2 迁移原则

- **不删现有 channel ID**：保留 `ocdroid.decisions` / `ocdroid.errors` 的 ID 字符串不变（用户已给的权限 / 系统设置不丢失）；
- **写入统一收口**：所有发布点统一经 `IslandNotifier.showXxx(...)` → `BaseOngoingNotifier` → `NotificationManagerCompat.notify`，`AppLifecycleMonitor` 内不再直接 notify；
- **dedup 收口**：`notificationSnapshot`（ALM line 102）从 ALM 移到 `IslandNotifier.dedupStore`，复合键 `(serverGroupFp, workdir, sessionId, type)`；
- **30s poller 不动**：保留作为 L3 / fallback 路径；poller 的发现结果仍走 IslandNotifier（不再 ALM 内 notify）。

---

## 3. 通知卡片视觉设计（补足 FGS spec 偏略部分）

> FGS spec §7 只定义了 channel 矩阵与 chronometer 起点，未给具体卡片文案与布局；本节是补足。
> 所有文案为占位中文（zh）+ 英文（en）——上线前由 i18n pass 提升到 `res/values/strings.xml`。

### 3.1 Ongoing 通知（`ocdroid.session_status`，固定 `SESSION_STATUS_NOTIFICATION_ID`）

#### 3.1.1 L2-active：N 任务进行中（核心场景）

**小图标**：`ic_stat_running`（24dp，白色单色，running 状态）。建议新增矢量drawable（避免复用 `ic_launcher`——状态栏小图标必须是单色 silhouette）。

**ContentTitle**：
- zh：「{N} 个任务进行中」
- en：「{N} task(s) in progress」

**ContentText**（子标题，按状态分流）：
- 单 busy：zh：「{session 标题 or workdir 名}」/ en：「{session title or workdir name}」
- 多 busy（N≤3）：zh：「{workdir-A} · {workdir-B} · {workdir-C}」
- 多 busy（N≥4）：zh：「{workdir-A}、{workdir-B} 等 {N} 个项目」/ en：「{workdir-A}, {workdir-B} and {N-2} more」

**BigTextStyle（展开后）**：
- 列出每个 busy session 一行：「· {session 标题} — {agent 名} — {workdir 名}」
- 最多 5 行，超出显示「… 以及其它 {N-5} 个任务」

**Chronometer**：
- `setUsesChronometer(true)` + `setWhen(earliestBusyTransitionMs)` + `setShowWhen(true)`；
- 起点 = 该通知聚合内**最早 busy transition**（`StatusAggregator` 暴露）；
- **不在通知每次更新时重置 setWhen**——重建通知要保留原 when（持久化在 StatusAggregator 状态机内）。

**Action 按钮**（按单/多分流，FGS spec §9）：
- 单 busy：
  - Action 1：zh「取消任务」/ en「Abort」→ `PendingIntent` BroadcastReceiver → `OpenCodeRepository.abortSession(sessionId)`；
  - （可选）Action 2：zh「打开」/ en「Open」→ deep-link MainActivity。
- 多 busy：
  - **不**显示「取消」Action（FGS spec §9 钉死）；
  - Action 1：zh「查看任务列表」/ en「View tasks」→ deep-link MainActivity 的任务列表（MVP，跳应用内）；
  - 点按通知主体（contentIntent）也跳任务列表。
- 执行时**重新查询 identity/status**（不盲用通知创建时的 ID 列表）。

**Ongoing / AutoCancel**：`setOngoing(true)`（不可滑动清除），`setAutoCancel(false)`。

**Priority / Importance**：`setPriority(NotificationCompat.PRIORITY_LOW)`（channel LOW）——避免每帧都弹气泡。

**ContentIntent**：deep-link MainActivity + `EXTRA_SESSION_ID`（单 busy）/ `EXTRA_TARGET_TASKS_LIST`（多 busy），携带 `(serverGroupFp, workdir, sessionId)` identity。

#### 3.1.2 L1-idle（前台已连接）— 可选

**小图标**：`ic_stat_connected`（绿色或灰色单色）。

**ContentTitle**：zh「已连接」/ en「Connected」。
**ContentText**：zh「{hostPort}」/ en「{hostPort}」。

**无 Action**。`setOngoing(true)`。`setPriority(PRIORITY_MIN)`（不弹气泡、最小化干扰）。

> 此项是**可选**的——产品上可决定 L1-idle 完全不发 ongoing（更安静）。建议默认发，以便用户感知连接健康。

#### 3.1.3 L2-idle（空闲监听）

**小图标**：`ic_stat_idle`（灰色单色）。

**ContentTitle**：zh「空闲监听」/ en「Listening (idle)」。
**ContentText**：zh「无活动任务」/ en「No active tasks」。

**无 Action**。`setOngoing(true)`（仍是 FGS 通知）。`setPriority(PRIORITY_MIN)`。

**无 chronometer**（`setUsesChronometer(false)` + `setShowWhen(false)`）。

#### 3.1.4 L3（无 FGS）— 不发 ongoing

L3 期间无 ongoing（poller only）。如曾有 ongoing，`cancel(SESSION_STATUS_NOTIFICATION_ID)`。

#### 3.1.5 占位通知（START_STICKY bootstrap 冷启动）

**小图标**：`ic_stat_connecting`。

**ContentTitle**：zh「正在恢复连接…」/ en「Restoring connection…」。
**ContentText**：zh「」(空)。

**无 Action**（除非 TOFU 待决——见 §3.5）。`setOngoing(true)`（FGS 必备）。`setPriority(PRIORITY_LOW)`。

bootstrap 完成后：busy → 切到 §3.1.1；全 idle → `stopForeground` + 进 L3（占位撤销）。

### 3.2 完成通知（`ocdroid.session_complete`，DEFAULT, autoCancel）

**触发**：FGS spec §8 完成状态机（仅同一 identity 下观察过 busy/retry 后观察到权威 idle；前台不发；其它抑制条件见 FR-22）。

**小图标**：`ic_stat_done`（单色 silhouette）。

**ContentTitle**：
- zh：「✅ 任务完成」
- en：「✅ Task completed」

**ContentText**：
- zh：「{session 标题}」
- en：「{session title}」

**BigTextStyle（展开）**：
- 「{session 标题}（{workdir 名}）已完成」
- 「点按查看结果」

**chronometer**：**不发**完成通知时不显示。

**Action**：
- Action 1（可选）：zh「打开」/ en「Open」→ deep-link session；
- 点按主体也 deep-link 同一 session。

**Ongoing / AutoCancel**：`setOngoing(false)`，`setAutoCancel(true)`（点按后消失）。

**Priority / Importance**：`setPriority(PRIORITY_DEFAULT)`（channel DEFAULT）。

**ID**：稳定复合键（`hashCode` of `(serverGroupFp, workdir, sessionId)`），保证同一 session 完成多次（罕见）替换不堆叠；不同 session 并存。

**dedup**：snapshot 去重（类似 `notificationSnapshot`），同一 transition 不重发。

### 3.3 Live Updates ProgressStyle（API36+，Phase 2）

#### 3.3.1 视觉目标

- 状态栏芯片（status bar chip）：彩色胶囊形，内容是简短状态文字；
- 锁屏 / AOD：同步显示同一 chip + chronometer + Action；
- **不用确定性进度条**（AI 任务无 0-100%，dynamic-island-plan §2.2）。

#### 3.3.2 实现

```kotlin
// 伪代码（开发设计文档不写真实实现，仅示意 API 调用形态）
val progressStyle = NotificationCompat.ProgressStyle()
    .addProgressSegment(
        NotificationCompat.ProgressStyle.ProgressSegment(...)
    )
    // ocdroid 无确定性进度 → 用状态 chip
    .setProgress(0, 0, false)  // 不确定进度（如果 API 支持「无进度条」更好）

val builder = NotificationCompat.Builder(context, CHANNEL_SESSION_STATUS)
    .setSmallIcon(R.drawable.ic_stat_running)
    .setStyle(progressStyle)
    .setRequestPromotedOngoing(true)  // 关键：请求提升为 Live Update
    // ... 其余同 §3.1.1
```

#### 3.3.3 状态 chip 文案

| 状态 | chip zh | chip en |
|---|---|---|
| L2-active (busy) | 「运行中」 | 「Running」 |
| L2-active (retry) | 「重试中」 | 「Retrying」 |
| L2-idle | （不显示 chip） | （不显示 chip） |

#### 3.3.4 回退

- API<36：AndroidX Core 1.17+ 自动回退到 base ongoing（§3.1）；
- API36+ 但 `setRequestPromotedOngoing(true)` 被系统拒绝（如未声明 `POST_PROMOTED_NOTIFICATIONS`）：系统自动回退到普通 ongoing；
- `LiveUpdateDecorator` 内部 try/catch 任何异常 → 回退 `BaseOngoingNotifier.showOngoing()`。

### 3.4 小米超级岛 `miui.focus.param`（HyperOS 2/3，Phase 3）

#### 3.4.1 检测

```kotlin
val focusProtocol = Settings.System.getInt(cr, "notification_focus_protocol", 0)
if (focusProtocol == 2 || focusProtocol == 3) {
    // 启用 XiaomiIslandDecorator
}
```

#### 3.4.2 `miui.focus.param` JSON 结构（dynamic-island-plan §2.1）

```json
{
  "enableFloat": true,
  "bigIslandArea": [
    { "templateId": "ocdroid_running", ... },
    ...
  ],
  "smallIslandArea": [
    { "templateId": "ocdroid_running_compact", ... },
    ...
  ],
  "updatable": true,
  "sequence": <递增整数>
}
```

#### 3.4.3 内容设计

**bigIslandArea**（展开形态，灵动岛下拉时）：
- 标题：「{N} 个任务进行中」
- 子标题：最早 busy session 标题 + agent
- chronometer（运行时长）
- Action：abort 单/多分流（同 §3.1.1）

**smallIslandArea**（紧凑形态，状态栏岛）：
- 图标 + 数字：「▶ {N}」
- 或单 busy：「▶」+ session 标题截断

**sequence 字段**：每次更新递增（`updatable=true`）。

**岛超时**：默认 1h（dynamic-island-plan §2.1）——`StreamingLifecycleCoordinator` 在 L2-active 持续期间定期更新 sequence 防 timeout；进 L3 时主动清除岛内容。

#### 3.4.4 回退

- 检测失败（`Settings.System.getInt` 抛 SecurityException 等）→ 回退 `BaseOngoingNotifier`；
- 小米审核未通过 / 非白名单 → 设备实际不显示岛内容（系统忽略 extras）→ base ongoing 仍生效，用户体验不退化。

### 3.5 TOFU degraded 占位通知（无 Activity，Phase 0）

**ContentTitle**：zh「需要确认服务器信任」/ en「Server trust required」。
**ContentText**：zh「点按打开 ocdroid 完成」/ en「Tap to open ocdroid and confirm」。

**Action 1**：zh「打开」/ en「Open」→ MainActivity（无 sessionId extra，落到 host 设置页）。

**正文不暴露** hostPort / SPKI（NFR-20）。

`setOngoing(true)`（仍是 FGS 占位）。`setPriority(PRIORITY_DEFAULT)`（比正常占位高一级，确保用户看到）。

用户确认后由 `ConnectionBootstrapCoordinator` 继续；占位通知替换为正常 ongoing 或撤销。

---

## 4. IslandNotifier decorator pipeline 接口草图

> FGS spec §12：`base ongoing` → optional `LiveUpdate` decorator → optional `Xiaomi` decorator（**叠加**，非互斥 interface 选择）。任一增强失败 → 回落 base ongoing。
> 本节是接口**草图**（Kotlin 伪签名，仅供讨论；真实代码由 Fixer 落实）。

### 4.1 顶层接口

```kotlin
// 文件：app/src/main/java/cn/vectory/ocdroid/notify/IslandNotifier.kt
interface IslandNotifier {
    /** Ongoing 状态发布（L1/L2 各子态、占位、TOFU degraded）。 */
    suspend fun showOngoing(state: OngoingState)

    /** 完成通知（仅后台，§8 状态机触发）。 */
    suspend fun showCompletion(state: CompletionState)

    /** Decision 通知（permission/question，§7 channel ocdroid.decisions）。 */
    suspend fun showDecision(state: DecisionState)

    /** 错误通知（§7 channel ocdroid.errors）。 */
    suspend fun showError(state: ErrorState)

    /** 取消指定 ID。 */
    suspend fun cancel(id: NotificationId)

    /** 取消全部（用于 host 切换 / reset）。 */
    suspend fun cancelAll()
}

// 数据类（占位，真实字段由 Fixer 按 FGS spec §7 + §3.1 视觉补全）
sealed interface OngoingState {
    data class L1Idle(val hostPort: String) : OngoingState
    data class L2Active(
        val identity: ConnectionIdentity,
        val busySessions: List<BusySessionDescriptor>,  // 含 earliestBusyTransitionMs
    ) : OngoingState
    data class L2Idle(val identity: ConnectionIdentity) : OngoingState
    object L3TornDown : OngoingState  // 触发 cancel
    data class BootstrapPlaceholder(val identity: ConnectionIdentity?) : OngoingState
    data class TofuDegraded(val hostPort: String) : OngoingState
}

data class BusySessionDescriptor(
    val sessionId: String,
    val workdir: String,
    val serverGroupFp: String,
    val title: String?,
    val agentName: String?,
    val busyTransitionMs: Long,
)

data class CompletionState(
    val identity: ConnectionIdentity,
    val sessionId: String,
    val title: String?,
    val workdir: String,
)

data class DecisionState(
    val identity: ConnectionIdentity,
    val sessionId: String,
    val type: DecisionType,  // PERMISSION / QUESTION
    val header: String,
    val body: String,
)

data class ErrorState(
    val identity: ConnectionIdentity?,
    val sessionId: String?,
    val title: String,
    val body: String,
    val stable: Boolean,  // 固定 ID 替换（true）vs 每次新 ID（false）
)

// NotificationId 抽象（hashCode 易碰撞 → 显式复合键）
data class NotificationId(
    val serverGroupFp: String,
    val workdir: String,
    val sessionId: String?,
    val type: NotificationType,
)
```

### 4.2 Base 实现

```kotlin
// 文件：app/src/main/java/cn/vectory/ocdroid/notify/BaseOngoingNotifier.kt
@Singleton
class BaseOngoingNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dedupStore: NotificationDedupStore,  // 由 ALM.notificationSnapshot 迁移
    private val settingsManager: SettingsManager,    // identity / workdir 解析
) : IslandNotifier {
    override suspend fun showOngoing(state: OngoingState) { /* §3.1 视觉 */ }
    override suspend fun showCompletion(state: CompletionState) { /* §3.2 */ }
    override suspend fun showDecision(state: DecisionState) { /* 现有 notifyDecision 迁移 */ }
    override suspend fun showError(state: ErrorState) { /* 现有 notifyError 迁移 */ }
    override suspend fun cancel(id: NotificationId) { /* notificationManager.cancel */ }
    override suspend fun cancelAll() { /* notificationManager.cancelAll */ }
}
```

### 4.3 Decorator 抽象

```kotlin
// 文件：app/src/main/java/cn/vectory/ocdroid/notify/NotifierDecorator.kt
/**
 * FGS spec §12：装饰器**叠加**在 base 之上，包装 Notification builder
 * 在发布前增强其参数（extras、style、flags）。任一增强失败 → 回退 base。
 */
abstract class NotifierDecorator(
    protected val delegate: IslandNotifier,
) : IslandNotifier by delegate

// 检测能力后的装饰器实现
class LiveUpdateDecorator(
    delegate: IslandNotifier,
    private val context: Context,
) : NotifierDecorator(delegate) {
    override suspend fun showOngoing(state: OngoingState) {
        if (Build.VERSION.SDK_INT >= 36 && state is OngoingState.L2Active) {
            try {
                showLiveUpdateOngoing(state)  // §3.3 ProgressStyle
                return
            } catch (t: Throwable) {
                DebugLog.w(TAG, "LiveUpdate failed → fallback base", t)
            }
        }
        delegate.showOngoing(state)  // 回退
    }
    // completion/decision/error 默认走 delegate（不增强）
}

class XiaomiIslandDecorator(
    delegate: IslandNotifier,
    private val contentResolver: ContentResolver,
) : NotifierDecorator(delegate) {
    private val enabled by lazy {
        runCatching {
            Settings.System.getInt(contentResolver, "notification_focus_protocol", 0) in setOf(2, 3)
        }.getOrDefault(false)
    }
    override suspend fun showOngoing(state: OngoingState) {
        if (enabled && state is OngoingState.L2Active) {
            try {
                showXiaomiIslandOngoing(state)  // §3.4 miui.focus.param
            } catch (t: Throwable) {
                DebugLog.w(TAG, "Xiaomi island failed → fallback base", t)
            }
        }
        // 注意：叠加而非互斥——Live Update + Xiaomi 同时存在时
        // 这里 delegate 可能已经是 LiveUpdateDecorator，所以 Xiaomi 在其之上再加 extras
        delegate.showOngoing(state)
    }
}
```

### 4.4 装配（Hilt）

```kotlin
// 文件：app/src/main/java/cn/vectory/ocdroid/notify/di/NotifyModule.kt
@Module
@InstallIn(SingletonComponent::class)
abstract class NotifyModule {
    @Binds
    @Singleton
    abstract fun bindIslandNotifier(impl: NotifyPipeline): IslandNotifier
}

@Singleton
class NotifyPipeline @Inject constructor(
    base: BaseOngoingNotifier,
    @ApplicationContext context: Context,
    contentResolver: ContentResolver,
) : IslandNotifier by buildPipeline(base, context, contentResolver)

private fun buildPipeline(base: BaseOngoingNotifier, context: Context, cr: ContentResolver): IslandNotifier {
    var pipeline: IslandNotifier = base
    if (Build.VERSION.SDK_INT >= 36) {
        pipeline = LiveUpdateDecorator(pipeline, context)
    }
    pipeline = XiaomiIslandDecorator(pipeline, cr)
    return pipeline
}
```

### 4.5 调用点

```kotlin
// StreamingLifecycleCoordinator 在层切换时：
class StreamingLifecycleCoordinator @Inject constructor(
    private val notifier: IslandNotifier,  // 注入的是 pipeline 顶层（装饰器栈）
    ...
) {
    fun onLayerChange(newLayer: Layer, state: OngoingState) {
        scope.launch { notifier.showOngoing(state) }
    }
}

// CompletionDetector 在 §8 状态机触发时：
class CompletionDetector @Inject constructor(
    private val notifier: IslandNotifier,
) {
    fun onBusyToIdle(state: CompletionState) {
        if (appLifecycleMonitor.isInForeground.value) return  // 前台不发
        scope.launch { notifier.showCompletion(state) }
    }
}
```

---

## 5. 与现有 controller / monitor 的职责拆分

> FGS spec §1.0 协作者拆分（重述为「现状 → 目标」映射，便于实施者定位改动）。

| 角色 | 现状 | 目标（Phase 0 后） | FGS spec § |
|---|---|---|---|
| SSE 连接 owner | `ConnectionCoordinator.sseJob`（line 108） | `SessionStreamingService`（进程级） | §1 |
| SSE 事件 fold | `SessionSyncCoordinator.handleEvent` | **不变**（纯 reducer），二次校验 identity | §1 |
| SSE → fold 桥 | `launchSseCollection` → `OnSseEvent`（CC line 707-770） | `SseEventBridge`（identity 校验） → `OnSseEvent(IdentifiedSseEvent)` | §1 |
| 进程前台信号源 | `AppLifecycleMonitor._isInForeground`（默认 true） | **同源**，但默认 false + Activity started-count 为事实源 | §4.3 |
| SSE 启停驱动 | `ForegroundCatchUpController.onForegroundChanged(false)` → `CancelSse`（FCC line 152） | `StreamingLifecycleCoordinator`（§4.4 串行状态机） | §1.1 / §4.4 |
| 30s poller 宿主 | `AppLifecycleMonitor.startBackgroundPolling`（line 180） | **同源**（保留），由协调器在 L2-idle / L3 启用 | §6 |
| TOFU gate | `ConnectionCoordinator.pendingTofuHostPort/Decision`（line 121-124，私有） | `ConnectionBootstrapCoordinator`（应用级共享） | §10 |
| busy 数据源 | UI slice `sessionStatuses`（局部） | `StatusAggregator`（全局，复合键，TTL，merge 时序） | §3 / §3.1 |
| 通知发布 | `AppLifecycleMonitor.notifyDecision/notifyError`（line 264-298） | `IslandNotifier` → `BaseOngoingNotifier`（+ decorators） | §6 / §12 |
| dedup store | `AppLifecycleMonitor.notificationSnapshot`（line 102） | `NotificationDedupStore`（IslandNotifier 持有，复合业务键） | §6 |
| directory-fetch generation 守卫 | `ConnectionCoordinator.directoryFetchGeneration`（line 150） | 上移到 `ConnectionIdentity.epoch`（单一真相源） | §2 |

---

## 6. 风险与待验证（重述自 FGS spec §15，按实施视角补充）

| # | 风险 / 待验证 | 实施对策 | 验收锚点 |
|---|---|---|---|
| R1 | 服务端 `/session/status` 是否真为 host-global（覆盖所有 workdir） | Phase 0 强制门禁 V-2 探针；失败升级为 directory fan-out | V-2 |
| R2 | 厂商（小米/华为）dataSync FGS 长连接杀进程策略 | 实机测试矩阵 | V-1 |
| R3 | dataSync 6h/24h 限额（targetSdk 35+） | `onTimeout()` 回落 poller + 通知用户；列 targetSdk 升级清单 | V-1 / NFR-6 |
| R4 | `START_STICKY` 重启矩阵（主动停服 / 系统杀进程 / force-stop / task-swipe / dataSync-timeout） | §5 流程每路径单测 + 实机验证 | V-6 / V-8 |
| R5 | 前台进入 busy 提升 FGS 时 `startForeground` 资格判定 race | L1-busy 检测在 Activity started-count > 0 时立即提升；用带延迟的 process-lifecycle 语义 | V-13 |
| R6 | decorator pipeline 中 LiveUpdate + Xiaomi 叠加顺序（Xiaomi extras 是否影响 Live Update chip） | 实机验证 API36 小米设备；必要时 Xiaomi 在 LiveUpdate 之上做 extras 合并而非替换 | V-17 / V-18 |
| R7 | 通知 ID 复合键 `hashCode` 碰撞 | 用显式 `NotificationId` 数据类 + 稳定哈希；高冲突场景测试 | FR-16 |
| R8 | `miui.focus.param` JSON 格式版本演进（HyperOS 2 vs 3） | 留版本字段 + 容错解析；非白名单设备静默回退 | V-18 |
| R9 | targetSdk 升级时间表与本功能是否同发 | 由发布计划决定（不阻塞 Phase 0 设计） | §15 |

---

## 7. 变更记录

| 日期 | 变更 | 作者 |
|---|---|---|
| 2026-07-13 | 初版（基于 FGS spec + dynamic-island-plan + 代码调研） | Fixer agent |
