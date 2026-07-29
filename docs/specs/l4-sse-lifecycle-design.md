# L4 Concrete Implementation Design — SSE Lifecycle, Background Grace, Receive-Only Fencing

> **来源**：oracle 设计（`ora-2`），是 L4（§C3' + R2）的**实现契约**。fixer-zlm 按此机械实现，rev-gpt 据此评审（生产正确性为主）。
> **配套**：[`slimapi-v2-adapt-traffic-plan.md`](slimapi-v2-adapt-traffic-plan.md) §C3'/R2/P4/§6；[`l3-reload-scheduler-design.md`](l3-reload-scheduler-design.md)（L4 的 seam）。
> **重要**：§D（reconfigure barrier）**已在 L3 第4轮由 fixer-zlm 实现**（`ReloadGenerationBarrier` + `HostProfileController` 三入口 + `ControllerModule` 接线）——L4 只需复用/兼容，**不重做**。
> **Reconnect override（2026-07-29）**：后台主 SSE 掉线、transport liveness、foreground reconnect、retry、Service destruction 及 ownership 语义由 [`l4-background-sse-reconnect-design.md`](l4-background-sse-reconnect-design.md) 覆盖。尤其不得再按本文件旧条款用 `SseLifecyclePolicy.recoveryNeeded` 驱动 transport reconnect、保留 stale Ready ownership，或让 coordinator 与 launcher 同时决定前台重连。

## 0. Locked decisions
1. 三 gate 独立：主 SSE = 前台+健康 identity；token stream = 前台+可见 chat route 匹配 sid；消息 reload = L3 已有的 前台+currentSessionId。
2. 后台 grace 只保留**已开**的主 SSE socket；主 gate 管**新 connect/reconnect**；grace 内 socket 断不内部重连。transport loss 由专项 reconnect runtime 记为 Dropped，前台由唯一 supervisor 恢复（见 reconnect override）。
3. 确认后台启 15min 时钟，用 `AppLifecycleMonitor.isInForeground`（接受其 700ms 确认延迟）；不引入 raw-activity edge。
4. 后台即 receive-only：SSE 帧不发 REST；无 ALM q/p 轮询；无 ProcessStatusPoller REST；retained SSE 可 best-effort 发布 question/permission 决策通知，但 idle 通知仍抑制；状态投影 + dirty/recovery 记账仍允许。`NoSourceTerminal` 后全部静默。
5. 15min 到期进入新 `NoSourceTerminal` 状态（**非** 现有 `Layer.L3` poller-only）。
6. L4 不实现全局 P4/L5 recovery；记录数据恢复所需的 `recoveryNeeded` + per-sid dirty；暂时把前台/会话打开的消息恢复转发进 L3 forced scheduler；全局 recovery 完成权留给 L5。**Transport reconnect 禁止消费或清除此全局 flag**，必须使用专项 runtime/drop ticket。

---

## 1. File boundaries
**新生产文件**：
- `service/lifecycle/SseLifecyclePolicy.kt` — 进程级同步权威（lifecycle mode、foreground/lifecycle generations、健康 identity、帧/REST fence、后台 dirty/recovery flag、独立 main-SSE/token-stream permit）。**不**拥有 15min timer（归 `StreamingLifecycleCoordinator`）。
- ~~`ui/controller/ReloadGenerationBarrier.kt`~~ — **已在 L3 实现**，L4 复用。

**主要修改生产文件**：`CoordinatorModels.kt`、`StreamingLifecycleCoordinator.kt`、`SessionStreamingController.kt`、`ServiceShell.kt`、`SessionStreamingService.kt`、`ServiceSseConnectionOwner.kt`、`SSEClient.kt`、`OpenCodeRepository.kt`（connectSSE reconnect callback）、`IdentifiedSseEvent.kt`、`SseEventBridge.kt`、`SseNotificationBridge.kt`、`AppLifecycleMonitor.kt`、`ProcessStatusPoller.kt`、`SessionSyncCoordinator.kt`、`sse/SseEventHandler.kt`、`sse/SseEventRouter.kt`、`sse/SseDispatchHost.kt`、`sse/SharedConversationSseHandler.kt`、`sse/LegacySseHandler.kt`、`sse/SlimSseHandler.kt`、`sse/TokenStreamCoordinator.kt`、`ControllerEffect.kt`、`AppCore.kt`、`AppCoreOrchestration.kt`、`MessageActions.kt`、`SessionListActions.kt`、`PermissionRefreshOrchestrator.kt`、`CatchUpActions.kt`、`ControllerModule.kt`、`HostProfileController.kt`。

---

## 2. Central lifecycle/fence types（`SseLifecyclePolicy.kt`）

```kotlin
enum class SseLifecycleMode { FOREGROUND, BACKGROUND_RECEIVE_ONLY, NO_SOURCE_TERMINAL }

data class SseLifecycleSnapshot(
    val mode: SseLifecycleMode,
    val appInForeground: Boolean,
    val lifecycleGeneration: Long,
    val foregroundGeneration: Long,
    val healthyIdentity: ConnectionIdentity?,
)
data class SseFrameFence(val lifecycleGeneration: Long, val identity: ConnectionIdentity)
data class SseRestFence(val lifecycleGeneration: Long, val identity: ConnectionIdentity)
data class BackgroundDeadlineTicket(val foregroundGeneration: Long, val identity: ConnectionIdentity, val deadlineElapsedMs: Long)
enum class RecoveryCause { SERVER_CONNECTED, RESYNC, ARCHIVE_RESTORED, PERMISSION_EVENT, MESSAGE_CREATED, DELTA_OVERFLOW, TRANSPORT_LOST }
```

Policy state用一个短 `synchronized(lock)` 守护（**不用** coroutine Mutex；handler/REST-boundary 检查必须同步）。API：

```kotlin
@Singleton class SseLifecyclePolicy @Inject constructor() {
    val snapshot: StateFlow<SseLifecycleSnapshot>
    fun onForeground(identity: ConnectionIdentity?)
    fun onBackgroundReceiveOnly(identity: ConnectionIdentity?)
    fun tryEnterNoSourceTerminal(ticket: BackgroundDeadlineTicket): Boolean
    fun onHealthyIdentityChanged(identity: ConnectionIdentity?)
    fun mainSseConnectAllowed(identity: ConnectionIdentity): Boolean
    fun tokenStreamAllowed(sessionId: String, visibleChatSessionId: String?): Boolean
    fun stampFrame(identity: ConnectionIdentity): SseFrameFence?
    fun frameStillCurrent(fence: SseFrameFence): Boolean
    fun restFenceFor(frame: SseFrameFence): SseRestFence?
    fun restEffectAllowed(fence: SseRestFence): Boolean
    fun markDirty(sessionId: String, cause: RecoveryCause, lifecycleGeneration: Long)
    fun markRecoveryNeeded(cause: RecoveryCause, lifecycleGeneration: Long)
    fun foregroundRecoveryFor(sessionId: String): ForegroundRecoveryClaim?
    fun acknowledgeMessageRecoveryForwarded(claim: ForegroundRecoveryClaim)
    fun completeGlobalRecovery(version: Long) // reserved for L5
}
```

**Generation 规则**：`foregroundGeneration` 每次确认 fg/bg edge 自增；`lifecycleGeneration` 在 确认前台/确认后台/健康 identity 替换或移除/terminal 转换 时自增。`stampFrame()` 在 identity 非当前健康 identity 或 mode=terminal 时返回 null。`restFenceFor()` 仅前台非 null。`restEffectAllowed()` 要求三全：mode=FOREGROUND + lifecycle gen 匹配 + identity 匹配（**这就是 R2 执行边界谓词**）。

**Recovery flag 契约**：内部维护 `RecoveryState(version, recoveryNeeded, dirtyVersions: Map<sid,version>, l4ForwardedVersionBySid)`。每个后台恢复信号自增 version；`dirtyVersions[sid]=version`；全局/不完整事件置 `recoveryNeeded=true`。L4→L3 转发：仅前台+可见/current sid+健康 identity 时 `submit(FORCE_RECONCILE)`；仅当该 sid version 未推进时移除其 dirty；记录 forwarded version。L4 **不清** `recoveryNeeded`；L5 之后 `completeGlobalRecovery(version)` 在其权威事务 commit 后才清。转发后的新事件自增 version 重新合格。→ 防 L4/L5 双方都声称全局 recovery 完成。

---

## 3. A — Three independent gates

### 3.1 主 SSE gate = `appInForeground && healthyIdentityAvailable`
`healthyIdentityAvailable` = 非空 `ConnectionIdentityStore.currentIdentity` 且仅在成功健康校验后建立（`ConnectionIdentityStore.kt:77-86`；health bind-before-connected `ConnectionHealthProbe.kt:430-464`、`ConnectionBootstrapEngine.kt:137-158`）。**不**加 visibleChatSessionId/currentSessionId。决策权威在 `StreamingLifecycleCoordinator`（观察 `AppLifecycleMonitor.isInForeground` + `ConnectionIdentityStore.currentIdentity`）；发 `LifecycleCommand.StartSse` 前要求 `policy.mainSseConnectAllowed(identity)`；`ServiceSseConnectionOwner.connect()` (`:353-365`) 重复同检查。

**Grace 例外**：已开 socket 在 `BACKGROUND_RECEIVE_ONLY` 可保留（retained lease，非新 gate 成功）。**任何 reconnect 路径**必须要求前台主 gate：`SSEClient.retryWhen` (`:105-124`)、服务级 loop (`ServiceSseConnectionOwner.kt:505-638`)、`SseRecoveryPolicy` 延迟重试 (`:628-635`)。给 `OpenCodeRepository.connectSSE`→`SSEClient.connect` 加 `reconnectAllowed: () -> Boolean = { true }`。grace socket 后台断：无内部 SSEClient/服务级重试；释放 live ownership 并发布 transport `Dropped`。前台恢复必须遵循 reconnect override 的 runtime + supervisor 单路径，禁止 `markRecoveryNeeded(TRANSPORT_LOST)` 或 coordinator 直接决定重连。

### 3.2 Token stream gate = `appInForeground && visibleChatSessionId == sid`
可见性来源是 route（`AppRoute.kt:108-114` 解析 `slices.nav.value.lastRoute` → `routeChatSessionId(lastRoute)`）。所有权：`TokenStreamCoordinator.kt:134-299`（open `:492-620`、close `:632-659`、reconnect `:1412-1448`）；主调用 `AppCoreOrchestration.kt:1419-1426`、次 `ChatViewModel.kt:152-159`。给 `TokenStreamCoordinator` 加 `appInForeground: StateFlow<Boolean>`、`visibleChatSessionId: () -> String?`、`lifecycleGeneration: () -> Long`；拆 desired 与 active（`DesiredTokenStream(sid, directory)` + `AtomicReference`）。open 记 desired→查 gate→仅允许时开。init collector 观察 foreground+nav route：后台或 route 离 sid 时 suspend-close active 并保留 desired；前台同可见 sid/route 返回时重开 retained desired；显式 `close(sid)` 清 desired，禁止自动重开。**每次 reconnect/503 重试**前后都查 gate。`close(sid)` 必须在 cancel 前自增/失效 sid epoch（当前 `:632-659` 不 bump frame epoch，L4 必须堵）。

### 3.3 消息 reload gate
**不重复**。L3 已在 `MessageActions.kt`（launch gate `:1228-1259`、pre-HTTP `:1262-1275`、后台 timer 取消 `:1392-1401`）执行 `appInForeground && currentSessionId == sid`。L4 可调 L3 `submit()` 但**不**替换/加宽此 gate。

### 3.4 独立不变式
不得有跨三 gate 的共享 `canStream`/`networkAllowed` 布尔。三独立 API：`policy.mainSseConnectAllowed(identity)`、`policy.tokenStreamAllowed(sid, visibleSid)`、L3 自读 foreground+currentSessionId。

| State | 主 SSE | Token stream | 消息 reload |
|---|---:|---:|---:|
| 前台 sessions list | on | off | off |
| 前台 chat s1 | on | s1 only | s1 only |
| 后台 grace | 仅 retained socket、不重连 | off | off |
| No-source terminal | off | off | off |

---

## 4. B — 15-min background teardown

### 4.1 新 coordinator 状态（改 `CoordinatorModels.kt:10-38`）
```kotlin
sealed interface Layer {
    data class L1(val busy: Boolean) : Layer
    data object L2Active : Layer
    data object L2Idle : Layer
    data object L3 : Layer  // 现有 generic terminal: no FGS/SSE, poller 保留
    data class BackgroundGrace(val foregroundGeneration: Long, val identity: ConnectionIdentity, val fgsWasHeld: Boolean, val mainSseRetained: Boolean) : Layer
    data class NoSourceTerminal(val foregroundGeneration: Long, val identity: ConnectionIdentity?, val reason: NoSourceReason) : Layer
}
enum class NoSourceReason { BACKGROUND_GRACE_EXPIRED, BACKGROUND_PLATFORM_TIMEOUT }
```
`NoSourceTerminal` ≠ `L3`。| State | 主SSE | FGS | ALM pollJob | ProcessStatusPoller |；`L3`=off/off/legacy允许/on、`BackgroundGrace`=retained/可能持/REST-gated/REST-gated、`NoSourceTerminal`=off/off/stopped/stopped。

### 4.2 后台转换（确认 `isInForeground=false`）
1. 自增 foreground/lifecycle generation；2. policy mode→`BACKGROUND_RECEIVE_ONLY`；3. L3 foreground collector 调 `cancelForBackground()`；4. 立即关 token stream；5. 进 `Layer.BackgroundGrace`；6. arm 一个 app-scope timer；7. 不启 poller；8. **不**用现有 `teardownLocked()` (`StreamingLifecycleCoordinator.kt:737-781`)。替换 `handleL1` (`:567-577`) 和 `handleL2Active` (`:590-617` 45s poller handoff) 的旧行为。`BackgroundGrace` 期间 status 变化不得触发旧 L2Active/L2Idle source handoff。

### 4.3 Timer（`StreamingLifecycleCoordinator` 唯一 owner）
ctor 加 `lifecyclePolicy`、`identityStore`、`monotonicNowMs = { SystemClock.elapsedRealtime() }`、`backgroundGraceMs = 15*60*1000L`。状态 `backgroundTeardownJob: Job?`、`backgroundDeadlineTicket`。arming：`armBackgroundTeardownLocked(fgGen, identity)` cancel 旧 job、建 ticket、launch while-loop `delay(remaining)`、到期在 coordinator mutex 下校验 ticket 仍当前 + layer 仍 BackgroundGrace + `policy.tryEnterNoSourceTerminal(ticket)` → 置 `Layer.NoSourceTerminal` + emit `EnterNoSourceTerminal`。前台转换须在同 mutex 下：cancel timer、清 ticket、policy→foreground(自增 gen)、再 resume transports。→ 旧 timer 不能关新前台连接。identity 比较须全 `ConnectionIdentity`，非仅 epoch。

### 4.4 复合 terminal command
`data class EnterNoSourceTerminal(val ticket: BackgroundDeadlineTicket) : LifecycleCommand`。`ServiceShell.enterNoSourceTerminal()`；`SessionStreamingService` 实现：`sseNotificationBridge.stop()`、`sseOwner.disconnect(markGap=false)`、`processStatusPoller.stop()`、`appLifecycleMonitor.stopBackgroundPollingForNoSource()`、`stopForeground()`、`serviceStopSelf()`（逐个 try/catch，一个失败不跳过其它；`stopForeground`+`stopSelf` 一起）。`SessionStreamingController.executeCommand()` 作单一命令处理。**在 enqueue 命令前置 policy terminal**（语义原子：物理组件顺序停时帧/effect 已 fence）。

### 4.5 后台 bootstrap
当前 `transitionFromL3()` (`:493-528`) 后台 busy 会启 SSE——L4 改：后台 sticky restart 无 socket 可留、主 gate false、不创 grace socket、立即进 `NoSourceTerminal` 停占位 FGS/poller。

---

## 5. C — Receive-only grace + R2 双 gate

### 5.1 帧上下文传播
扩展 `IdentifiedSseEvent.kt:29-32` 加 `lifecycleGeneration: Long`。owner 收帧：`val fence = lifecyclePolicy.stampFrame(identity) ?: return` → emit 带 generation 的 `IdentifiedSseEvent`。bridge 与 SSC 入口：`if (!lifecyclePolicy.frameStillCurrent(frameFence)) return`。经 router 传不可变 `SseHandlerContext(frameFence, backgroundReceiveOnly)`。改 `SseEventHandler.handle`/`SseEventRouter.route`/`SseDispatchHost.applySseSideEffects`/`handleSessionDigest` 签名加 context。**不**在 SSC 存可变 frame context 字段。

### 5.2 REST 执行对象（`ControllerEffect.kt`）
```kotlin
data class ExecuteSseRest(val operation: SseRestOperation, val fence: SseRestFence) : ControllerEffect()
sealed interface SseRestOperation {
    data object ServerConnected : SseRestOperation
    data object RefreshSessions : SseRestOperation
    data object LoadPendingPermissions : SseRestOperation
    data object LoadSessionStatus : SseRestOperation
    data class ReloadMessages(val sessionId: String, val resetLimit: Boolean, val expectedRouteInstance: Long) : SseRestOperation
    data class CatchUpAfterDisconnect(val sessionId: String) : SseRestOperation
}
```
手动/用户发起的 `LoadMessages`/`LoadSessions` 等**不变**（避免误压前台用户动作）。

### 5.3 执行边界
`AppCore.dispatchEffect()` 在常规路由前处理 `ExecuteSseRest`：`if (!policy.restEffectAllowed(fence)) { operation.sessionIdOrNull()?.let { policy.markDirty(it, TRANSPORT_LOST, fence.lifecycleGeneration) }; return true }` 否则 `executeSseRestOperation`。**第二次检查**须在 launched coroutine 内、首个 repository 调用前：给相关 helper 加 `requestStillAllowed: () -> Boolean = { true }`（`MessageActions.kt:40 launchLoadMessages`、`SessionListActions.kt:82`/`SessionListRefreshOrchestrator.kt:43`、`SessionListActions.kt:156`/`StatusPollOrchestrator.kt:92`、`SessionListActions.kt:345`/`PermissionRefreshOrchestrator.kt:74,160`、`CatchUpActions.kt:44`）；coroutine 内 `if (!requestStillAllowed()) return@launch`；fan-out loop 每请求前复查；多步事务每 REST 子操作前 + commit 前复查。→ 捕获：前台 enqueue 后台 dequeue / dequeue 刚过后台但 coroutine 后到 HTTP / 被取代的 lifecycle gen / terminal 后到 effect。

### 5.4 枚举绕过转换
1. **`server.connected`**（`SessionSyncCoordinator.kt:641-670`）：前台→用帧 `SseRestFence`，把 ServerConnected/reconcile 决策/forced reload 转 `ExecuteSseRest`。后台→`markRecoveryNeeded(SERVER_CONNECTED, gen)` + `currentSid?.let { markDirty(it, ...) }` + return（不发 ServerConnected/LoadSessionStatus/LoadSessions/LoadMessages/直接 L3 forced HTTP；调 L3 `submit()` 仅作 dirty 记账因其前台 gate 阻 HTTP，L4 自身 dirty flag 才是前台缝的权威）。
2. **archive restore**（`:1026-1035`）：前台→发 fenced `RefreshSessions`。后台→保留 `:980-1016` status/error 投影 + mark 全局恢复 + sid dirty + 跳 `RefreshSessions`；允许 content-bearing digest 进 L3 `submit()` (`:1043-1055`)（保持 dirty 不 HTTP）。`:1018-1023` 删除/归档驱逐是本地 state/cache teardown 非 REST，仍可执行（**注意**：L3 已在此处接 `onSessionClosed`，L4 保持）。
3. **permission**（`LegacySseHandler.kt:187-210`）：后台→保留 `applyPermissionAsked/Resolved` 投影 + 跳 `LoadPendingPermissions` + `recoveryNeeded(PERMISSION_EVENT)`。前台→发 fenced `LoadPendingPermissions`。
4. **`message.created`**（`SharedConversationSseHandler.kt:50-87`）：后台→保留 timestamp bump + local-injection 记账 + 不发 `ReloadMessages` + `markDirty(sid, MESSAGE_CREATED)`。前台→发 fenced `ReloadMessages`。同 fenced 机制覆盖 `LegacySseHandler.kt:167-181`、`SharedConversationSseHandler.kt:247-251`。
5. **`SseNotificationBridge`**（`:211-225` dedup 前）：`NoSourceTerminal` 一律 return；`BACKGROUND_RECEIVE_ONLY` 仅允许 question/permission 决策通知，禁止 idle 通知；`FOREGROUND` 保持现有前台抑制/投影语义。最终 publish 边界（`:241-248`）也复查 policy。该例外只允许本地通知，不允许任何后台 REST。
6. **`SseEventBridge`**（`:195-220` 入口）：校验 identity + lifecycle gen + drop terminal/stale 帧 + 当前后台帧路由到 state handler + receive-only 模式不发 `_notificationControlEvents`。delta overflow：前台保留 `_dirtySessions`；后台调 `policy.markDirty()` 且**不**更新 `_dirtySessions`（防 `AppCore.kt:350-374` overflow watcher 启 REST）。`AppCore.kt:830-837` `OnSseEvent` 分支 SSC dispatch 前复查帧 fence。

### 5.5 其它必须 REST 绕过
- **owner resync**（`ServiceSseConnectionOwner.onSuccessfulFrame()` `:688-740` 直接 schedule REST，绕 handler）：receive-only 时不调 `scheduleResync`、置 `recoveryNeeded(RESYNC)`；每个 queued `scheduleResync()` (`:880-975`) 须带 `SseRestFence`，`resyncMutex` 后、`onResync` 前复查。
- **ALM poller**（`startBackgroundPolling` `:377-387`、`pollPendingItems` `:398-487`）：启动前/poll 入口/每 repository 调用前/通知发布前查 policy；receive-only + terminal 零 REST。暴露 `internal fun stopBackgroundPollingForNoSource() { pollJob?.cancel(); pollJob = null }`。
- **ProcessStatusPoller**：注入 policy，每 status REST 请求 + 延迟重试前查；grace 期间 job 可存在但零网络；terminal 复合命令物理停之。

---

## 6. D — Reconfigure/detachGeneration barrier ✅ 已在 L3 实现
> **L3 第4轮 fixer-zlm 已实现** `ReloadGenerationBarrier`（`beginReconfigureBarrier` 同步 `identityStore.beginReconfigure()` + `scheduler.detachGeneration(newEpoch)`），`HostProfileController` 三入口（configureServer/configureRepositoryForProfile/resetLocalDataAndResync）已走 barrier，`ControllerModule` 接线已传 scheduler。**L4 只需复用、不重做**；若发现 nullable/test fallback 仍有裸 begin，收敛进 barrier helper（rev-gpt 曾指出 reset fallback 仍裸调——L4 顺手结构性闭合：全仓禁裸 `beginReconfigure()`）。

---

## 7. E — Foreground recovery seam（L4 临时消费者，在 `SessionSyncCoordinator`）
观察 `combine(lifecyclePolicy.snapshot, slices.chat.map{it.currentSessionId}.distinctUntilChanged())`。前台+健康 identity+current sid 时：`val claim = lifecyclePolicy.foregroundRecoveryFor(sid) ?: return` → `skeletonReloadCoordinator?.submit(sid, tuple=null, FORCE_RECONCILE, TRANSPORT_RESET)` → `acknowledgeMessageRecoveryForwarded(claim)`。前台 sid 变化时也跑（打开 dirty session 即 reconcile）。
**清除规则**：per-sid dirty 仅同步 L3 `submit()` 成功后清 + version 比较防擦新事件；全局 `recoveryNeeded` L4 不清、仅记 forwarded version、L5 commit 后清；无健康 identity/current sid 保留所有 flag；L3 pre-HTTP reject 时 L3 自留 dirty（L4 已转消息所有权给 L3）。
**L3 API 依赖**：依赖 L3 保留 `submit(...)`、`Priority.FORCE_RECONCILE`、`ReloadReason.TRANSPORT_RESET`；若 L5 改名用等价 forced reason。

---

## 8. F — 700ms lifecycle delay
信号：foreground flow `AppLifecycleMonitor.kt:108-109`；raw `startedCount==0` gen bump `:239-246`；700ms 后确认 false `:248-255`；常量 `:797-807`。**建议接受 700ms**：相对 15min cap 无关紧要；保持主 SSE/token/reload/policy/timer 同一 lifecycle 真相；raw edge 会在 rotation 期间关 token/拒 queued REST 再 700ms 后重开；L3 已用确认流，混用 raw/confirmed 会使三 gate 瞬时不一致。若产品后要 raw-edge 抑制，另暴露 `val rawActivityVisibility: StateFlow<ActivityVisibility>` 仅用于即时 token close/REST 抑制，不替 `isInForeground`、不从它起 15min timer。

---

## 9. G — 测试映射（**协调器/组件级单测即可；驱动生产路径的集成测试作为独立跟进**）
> **L3 教训**：fixer 在生产代码上达标、在生产路径集成测试上稳定不达标。L4 评审以**生产正确性**为主；驱动真实生产回调（ConnectionBootstrapEngine/ALM/poller/owner resync）的集成测试若需新 harness，作为独立跟进项，不阻塞 L4 ship。

- 9.1 Policy/gate：主 SSE 独立于 visible session、token gate 各状态、reload gate 留 L3。
- 9.2 Token lifecycle：session leave/background 取消、queued frame epoch-drop、reconnect delay 复查 route+foreground（`TokenStreamCoordinatorTest`）。
- 9.3 主 SSE/reconnect gate：`ServiceSseConnectionOwnerTest` + SSE client retryWhen。
- 9.4 15min teardown：`StreamingLifecycleCoordinatorTest` 用 testScheduler currentTime、`backgroundGraceMs=900_000`；arm 单调 15min、前台取消、旧 fg gen 不能关新连接、identity mismatch fence、terminal 是 NoSourceTerminal 非 L3、后台 sticky bootstrap 不发 StartSse。
- 9.5 Stop-all 原子性：`SessionStreamingControllerDispatchTest` 录制 6 stop、一失败不跳其它、不发 StartPoller、policy 已 terminal 时首停执行。
- 9.6 Receive-only handler：`SessionSyncCoordinatorReceiveOnlyTest`，real bus/slices/fake policy/fake repo 计 REST；6 case 各断言零 REST + 零 repo 调用。
- 9.7 REST 执行边界：`SseRestEffectExecutionTest`，各 op 前台 enqueue→后台 dequeue→零调用；及 dequeue 后 suspend→切后台→pre-HTTP 拒。
- 9.8 Bridge generation、9.9 notification（改期望：BackgroundGrace 仅 q/p 决策通知，idle/REST-derived 通知为零；terminal 全部为零）、9.10 ALM/poller、9.11 barrier（**§D 已在 L3，仅回归确认 HostProfileController 无裸 begin**）。

---

## 10. H — 风险/gotchas
1. 现有 lifecycle state machine 与 L4 直接冲突（L2Active 后台+SSE+FGS 无限、L2Idle SSE off+poller、L3 poller-only、L1 idle 后台用 generic poller handoff）。**不要**把 background grace 表为这些状态之上的布尔；在**每个**穷尽 `when` 加显式 `BackgroundGrace`/`NoSourceTerminal` 分支。现有 coordinator 测试需期望变更（后台 busy 仍 L2Active、L1 idle backgrounds 直接到 L3、45s L2Active→L2Idle handoff、L3=所有 terminal）。
2. FGS 6h budget：15min cap 须先触发；Android `dataSync` timeout 若在 BackgroundGrace 期间，路由到同 no-source terminal 路径，非 generic `teardownAndAwait(Timeout)` 启/留 poller。
3. SSE reconnect backoff 两层都要 gate：`SSEClient.retryWhen` (`:105-124`) + 服务级 loop (`:505-638`)；只 gate 外层不够（内层 retryWhen 会在 15min grace 内多次重连）。
4. First-frame/explicit resync 绕 handler：`onSuccessfulFrame()` (`:688-740`) 直接 schedule REST，须带同 REST fence，否则 R2 仍漏。
5. 后台轮询比通知广：仅静 `SseNotificationBridge` 不够——ALM 仍调 permission/question/latest-message REST、ProcessStatusPoller 调 status REST、bridge overflow 触 AppCore reload；三者都要 pre-HTTP gate。
6. 现有通知行为需按最新产品决策收敛：`SseNotificationBridge.kt:20-67`、`SessionStreamingService.kt:426-454`、`SseNotificationBridgeTest` 在 BackgroundGrace 仅保留 question/permission 决策通知，抑制 idle 与任何依赖后台 REST 的通知；`NoSourceTerminal` 全部静默。`AppLifecycleMonitor.onAppError()` 的错误通知不属于 SSE 决策通知，可留。
7. grace 期间 status 显示：state-only `session.status`/digest 投影可继续（内存 badge、status aggregator、进行中 FGS 通知）——不发 REST 即可。合法 FGS UI 与 retained-SSE 决策通知不等于放宽后台 REST。
8. 15min 前服务死：前台 idle 服务可能是无 FGS slot 的普通 started service，Android 可能先杀——安全（进程/服务死比 15min cap 更强 teardown；timer 是 app 进程存活期间的上界，非复活保证）。
9. L3 API 仍可能变：L4 依赖 L3 保留 `detachGeneration`/`cancelForBackground`/generation-keyed state/dirty 保留/forced submit/marker 所有权 seam。另注意 `MessageActions.kt:1044` 生产默认用 `System.currentTimeMillis()`，L3 design 要 monotonic——L4 timer 须独立用 `SystemClock.elapsedRealtime()`，不复用 L3 当前默认 clock。

---

## 11. 机械验收清单
主 SSE connect/reconnect 仅查 foreground+当前健康 identity；主 SSE 在前台 sessions-list 页保持 alive；token stream 绑前台+route-visible sid、leave/background 关；L3 reload gate 不动；确认后台立即 receive-only + 关 token；现存主 SSE socket 至多留 15min、禁止内部 reconnect，掉线后按 reconnect override 在前台恢复；timer 唯一 owner=`StreamingLifecycleCoordinator`；deadline 用 `SystemClock.elapsedRealtime()`；timer 由 foreground gen + 全 connection identity fence；到期进 `NoSourceTerminal` 非 `L3`；一复合 terminal 命令停 SSE/FGS/ALM poll/ProcessStatusPoller；policy terminal 在物理 teardown 前提交；所有 SSE-origin REST effect 带 lifecycle/identity fence；handler 层后台转 state/dirty；执行层 repo 调用前复查；6 命名绕过路径后台零 REST；owner resync/ALM/ProcessStatusPoller/bridge overflow 也 gate；SSE notification bridge 在 BackgroundGrace 仅允许 q/p 决策通知、禁止 idle/REST-derived 通知，terminal 全静默；晚到帧/effect generation-drop；**host reconfigure gen bump + L3 detach 已是 L3 同步 helper（复用）**；L4 转发 current-sid recovery 进 L3 FORCE 不清 L5 全局 flag；`./scripts/check.sh` 为最终 gate。
