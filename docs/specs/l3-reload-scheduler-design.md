# L3 concrete implementation design — unified skeleton reload scheduler

> **来源**：oracle 设计（`ora-1`），是 L3（§C1+C2+R1）的**实现契约**。fixer 按此机械实现，rev-1 据此评审。
> **配套**：[`slimapi-v2-adapt-traffic-plan.md`](slimapi-v2-adapt-traffic-plan.md) §C1/C2/R1/§6 测试矩阵。

## Decision

保留公共类名 `SkeletonReloadCoordinator`（避免 DI/调用点 churn），但**完全替换其调度内部**。它即方案的 `ReloadScheduler`；**不**引入第二个 scheduler，也**不**保留 watchdog 作为并行机制。

核心不变式：
1. 只有 `submit()` 创建/更新 reload 需求。
2. 只有私有 `launchReload()` 可发起 HTTP。
3. 一个 state 恰由一个不可变 transport generation 拥有。
4. completion 仅当该 state 仍占据其 `(generation, sid)` 槽时才能 mutate/commit。
5. `dirty` 在 launch 时消费，失败/空内容/CAS 拒绝时恢复；in-flight 期间的提交再次置位，旧 completion 无法清除。
6. marker 推进需 complete tuple + 非空页 + `dispatchAndVerify() == true`。
7. timer 永不被每个 digest 重启；它瞄准最早允许的 trailing launch，防饿死。

---

## A. 数据结构 + 类形

### 生产类型

```kotlin
internal data class Tuple(
    val updatedAt: Long?,
    val messageId: String?,
) {
    val isComplete: Boolean
        get() = updatedAt != null &&
            updatedAt >= 0L &&
            !messageId.isNullOrBlank()
}

internal enum class Priority(val limit: Int, val rank: Int) {
    FORCE_RECONCILE(limit = 200, rank = 1),
    DIGEST(limit = 50, rank = 0),
}

internal fun maxPriority(a: Priority, b: Priority): Priority =
    if (a.rank >= b.rank) a else b

internal enum class ReloadReason(
    val isExternalSignal: Boolean,
    val contentBearing: Boolean = false,
    val confirmsAuthoritativeEmpty: Boolean = false,
) {
    DIGEST(isExternalSignal = true, contentBearing = true),
    DIGEST_MALFORMED(isExternalSignal = true, contentBearing = true),
    REQUEST_RELOAD(isExternalSignal = true),
    TOKEN_STREAM_DONE(isExternalSignal = true),
    TOKEN_PART_REMOVED(isExternalSignal = true),
    SERVER_RECONNECT(isExternalSignal = true),
    TRANSPORT_RESET(isExternalSignal = true),
    FORCE_RECONCILE_AUTHORITATIVE_EMPTY(isExternalSignal = true, confirmsAuthoritativeEmpty = true),
    NETWORK_RETRY(isExternalSignal = false),
    EMPTY_PAGE_RETRY(isExternalSignal = false, contentBearing = true),
}
```

> **不得**仅凭 `limit=200` 推断 authoritative empty。

### State + keys

```kotlin
private data class ReloadKey(val generation: Long, val sessionId: String)

private data class ReloadState(
    val ownerGeneration: Long,          // immutable generation fence
    var dirty: Boolean = false,
    var target: Tuple? = null,
    var inFlight: Boolean = false,
    var timerJob: Job? = null,
    var nextAllowedAt: Long = 0L,
    var queuedPriority: Priority = Priority.DIGEST,
    var queuedReasons: Set<ReloadReason> = emptySet(),
    var queuedRequiresContent: Boolean = false,   // 关键：防 FORCE 覆盖 digest 内容需求
    var retryAttempt: Int = 0,
    var lastSuccessfullyReloadedTarget: Tuple? = null,
)
```

`queuedRequiresContent` 必需：否则 queued FORCE/非内容请求会覆盖 content digest，空结果会错误清除 digest 的 dirty。

in-flight jobs 单独存以便 `onSessionClosed()` join：

```kotlin
private val states = mutableMapOf<ReloadKey, ReloadState>()
private val reloadJobs = mutableMapOf<ReloadState, Job>()
private val locallyInjected = ConcurrentHashMap<String, MutableSet<String>>()
```

### Launch ticket（挂起后使用的全部信息，不可变）

```kotlin
private data class LaunchTicket(
    val key: ReloadKey,
    val ownerState: ReloadState,
    val target: Tuple?,
    val priority: Priority,
    val reasons: Set<ReloadReason>,
    val requiresContent: Boolean,
    val connectionIdentity: ConnectionIdentity,
    val bundleStamp: BundleStamp,
    val routeInstance: Long,
)
```

### 类签名

```kotlin
class SkeletonReloadCoordinator(
    private val scope: CoroutineScope,
    private val repository: OpenCodeRepository,
    private val slices: SliceFlows,
    private val foreground: StateFlow<Boolean>,                    // AppLifecycleMonitor.isInForeground
    private val currentTransport: () -> TransportSnapshot,         // currentEpoch + currentIdentity(epoch 必须等于 epoch)
    private val currentBundleStamp: () -> BundleStamp?,            // repository.currentClientBundle()
    private val monotonicNowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val busyMinIntervalMs: Long = 2_000L,
    private val retryDelaysMs: LongArray = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L),
)

internal data class TransportSnapshot(val generation: Long, val identity: ConnectionIdentity?)
```

生产接线在 `ControllerModule.kt:447-457`：加 `AppLifecycleMonitor`、`ConnectionIdentityStore`、bundle-stamp provider。

### 方法 suspend/context 契约

| 方法 | suspend? | context |
|---|---:|---|
| `submit(sid, tuple, priority, reason)` | 否 | caller 线程；tiny synchronized 状态转移 |
| `scheduleTrailingLocked(...)` | 否 | 必须持 scheduler lock |
| `launchReload(...)` | 否 | 必须持 scheduler lock；创建 scope 的 lazy child |
| `runReload(ticket)` | 是 | `scope`（`UiApplicationScope`，生产 Main.immediate）；Retrofit suspend I/O |
| `onReloadComplete(...)` | 否 | tiny synchronized 转移 |
| `cancelForBackground()` | 否 | 同步取消 timer 引用 |
| `detachGeneration(newGeneration)` | 否 | 同步 generation barrier |
| `onSessionClosed(sid)` | 是 | lock 下 detach，lock 外 `cancelAndJoin()` |

`UiApplicationScope` 定义上 Main.immediate（`UiApplicationScope.kt:13-21,39-50`）；store commit 留在 Main。**不**在 Retrofit suspend API 外加 `Dispatchers.IO`。

---

## B. 最小重构映射（对照现有 SkeletonReloadCoordinator）

### 保留
- `locallyInjected` 语义 + 同步 `markLocallyInjected()`（`MessageActions.kt:959-966,1352-1361`）。
- `ReloadIdentity` 的 route-freshness 概念，但用更强的 `LaunchTicket` 替换。
- 现有 window merge 算法（`MessageActions.kt:1102-1268`）。
- `messageLoadCoordinator.withSessionLock(sessionId)` 串行化竞争 message load（当前 `MessageActions.kt:1043-1070`）。
- `onSessionClosed()` 的 detach-then-join 行为（`MessageActions.kt:1341-1349`）。

修改 merge helper 返回 commit 判决：

```kotlin
private fun mergeSkeletonIntoChatSlice(...): Boolean {
    if (page.items.isEmpty()) return false
    // 现有 merge 计算...
    val committed = slices.store.dispatchAndVerify(
        AppAction.ChatContentLoaded(/* existing fields */ bundleStamp = ticket.bundleStamp)
    )
    if (committed) { locallyInjected[sessionId]?.removeAll(fetchedIds) }
    return committed
}
```

> 当前 `locallyInjected` 在 dispatch 前清除（`MessageActions.kt:1132-1133`）。**移到 verified commit 之后**——否则被拒的 route/host commit 仍会摧毁 local-injection guard。

### 替换（全部）
- 现有 `ReloadState` 字段 `desiredEpoch/pendingLimit/failed/retryAttempt`（`:907-913`）。
- `reloadStates` 仅以 sessionId 为 key（`:953-957`）。
- `watchdogJobs`（`:968-969`）。
- 现有 `requestReload()`（`:1004-1020`）。
- 现有 `launchNextReload()`（`:1022-1100`）。
- 现有 `onDigestChange()`（`:1274-1292`）。
- 整个无界 watchdog（`:1294-1335`）。

### 兼容 wrapper（临时，单行路由进 submit）

```kotlin
fun requestReload(sessionId: String, limit: Int = 50) {
    submit(sessionId, tuple = null,
        priority = if (limit >= 200) Priority.FORCE_RECONCILE else Priority.DIGEST,
        reason = ReloadReason.REQUEST_RELOAD)
}

fun onDigestChange(sessionId: String, tuple: Tuple) {
    submit(sessionId, tuple,
        priority = Priority.DIGEST,
        reason = if (tuple.isComplete) ReloadReason.DIGEST else ReloadReason.DIGEST_MALFORMED)
}
```

> status-only digest 帧**不**调用 `onDigestChange()`。

### 移除 immediate completion re-fire

必须消失的路径（`MessageActions.kt:1088-1091`）：

```kotlin
ownerState.inFlight = false
if (!ownerState.failed && ownerState.pendingLimit > 0) { launchNextReload(sessionId) }
```

替换为：

```kotlin
ownerState.inFlight = false
if (ownerState.dirty && foreground.value) { scheduleTrailingLocked(ownerState) }
```

`scheduleTrailingLocked()` 必须尊重 `nextAllowedAt`，**永不**自己调 HTTP。

### 源路由（全部经 submit）

| 现有源 | 位置 | 替换 |
|---|---|---|
| content digest | `SessionSyncCoordinator.kt:1037-1038` | 解析 tuple；`submit(... DIGEST, DIGEST)` |
| `requestDigestFullSweep` | `SessionSyncCoordinator.kt:1085-1094` | `submit(... DIGEST, REQUEST_RELOAD)` |
| `server.connected` | `SessionSyncCoordinator.kt:661-669` | `submit(... FORCE_RECONCILE, SERVER_RECONNECT)`；删冗余嵌套 `scope.launch` |
| transport reconcile | `SessionSyncCoordinator.kt:1134-1149` | `isStillCurrent` 后 `submit(... FORCE_RECONCILE, TRANSPORT_RESET)` |
| token terminal fetch | `ControllerModule.kt:351-355` | `submit(... DIGEST, TOKEN_STREAM_DONE)` |
| part removal | `ControllerModule.kt:576-585` | 移除独立 `partRemovalDebounceJobs`；立即 submit 为 `TOKEN_PART_REMOVED` |
| retry/watchdog | 旧 `MessageActions.kt:1311-1319` | 统一 timer 恢复 dirty 并经 scheduler 调度；无直接 launch |

`launchReload()` 保持私有，仅 `scheduleTrailingLocked()` 可达。

---

## C. 并发 + generation fence

### 锁选择
单一私有 monitor `private val stateLock = Any()`。所有 `states`/`timerJob`/`reloadJobs`/priority/target/dirty/retry/marker 操作在 `synchronized(stateLock) { ... }` 内。优于现有 coroutine `Mutex`，因为 submit/foreground 取消/generation detach 必须**同步**，且无 protected 操作 suspend。

### 锁序
唯一嵌套序：
```
messageLoadCoordinator session mutex → scheduler stateLock → 非挂起 store dispatchAndVerify
```
**永不**：lock 下 await HTTP / `delay` / `join` / `cancelAndJoin`；持 stateLock 时获取 session mutex；持 stateLock 时调 `ConnectionIdentityStore.capture()`（用其 lock-free reader，`ConnectionIdentityStore.kt:68-73` 允许）。接受 transport snapshot 仅当 `identity.epoch == epoch`。

### submit()

```kotlin
fun submit(sid: String, tuple: Tuple?, priority: Priority, reason: ReloadReason) {
    val transport = currentTransport()
    synchronized(stateLock) {
        detachMismatchedGenerationsLocked(transport.generation)
        val key = ReloadKey(transport.generation, sid)
        val state = states.getOrPut(key) { ReloadState(ownerGeneration = transport.generation) }
        state.dirty = true
        state.queuedPriority = maxPriority(state.queuedPriority, priority)
        state.queuedReasons = state.queuedReasons + reason
        state.queuedRequiresContent = state.queuedRequiresContent || reason.contentBearing
        // 仅 content-bearing 信号拥有 marker target；tuple=null 的 FORCE/token 回调不得覆盖更新的 digest target
        if (reason.contentBearing) state.target = tuple
        if (reason.isExternalSignal) state.retryAttempt = 0
        scheduleTrailingLocked(state)
    }
}
```

> 此处**不**拒绝非当前/后台 submit——这些 submit 必须使 state dirty；仅 launch 受 gate。

### 调度 + trailing 保证（防饿死核心）

```kotlin
private fun scheduleTrailingLocked(state: ReloadState) {
    check(Thread.holdsLock(stateLock))
    if (!state.dirty || state.inFlight) return
    if (!foreground.value) return
    if (state.retryAttempt >= retryDelaysMs.size && ReloadReason.NETWORK_RETRY in state.queuedReasons) return
    val sid = keyForStateLocked(state)?.sessionId ?: return
    val now = monotonicNowMs(); val dueAt = state.nextAllowedAt
    if (now >= dueAt) { launchReloadLocked(sid, state); return }
    // 不在每个 digest 上 cancel/re-arm timer。保留最早有效 deadline = 防永久 debounce 饿死。
    if (state.timerJob?.isActive == true) return
    val owner = state
    val job = scope.launch(start = CoroutineStart.LAZY) {
        delay((dueAt - monotonicNowMs()).coerceAtLeast(0L))
        synchronized(stateLock) {
            if (!stillOwnsLocked(sid, owner)) return@synchronized
            if (owner.timerJob === coroutineContext[Job]) owner.timerJob = null
            scheduleTrailingLocked(owner)
        }
    }
    state.timerJob = job
    job.start()
}
```

busy 时在实际 launch 处设 deadline：

```kotlin
if (isBusy(sid)) {
    state.nextAllowedAt = maxOf(state.nextAllowedAt, monotonicNowMs() + busyMinIntervalMs)
}
```

busy = `SessionStatus.isBusy || isRetry`（map 在 `MessageActions.kt:1188-1190`；accessor `Session.kt:91-99`）。

**保证**：in-flight 期间 digest 置 `dirty=true`；completion 调 `scheduleTrailingLocked()`（非立即 re-fire）；timer 存在时 digest 更新 target/priority 但**不**后移 timer；busy→idle collector 调 `nudge(sid)`→`scheduleTrailingLocked()`；foreground 恢复同样 nudge 当前 session。

### Launch guard（捕获 permit，仅消费本次 work）

```kotlin
private fun launchReloadLocked(sid: String, state: ReloadState) {
    check(Thread.holdsLock(stateLock))
    if (state.inFlight || !state.dirty) return
    val transport = currentTransport()
    val identity = transport.identity ?: return
    if (transport.generation != state.ownerGeneration) return
    if (!foreground.value) return
    if (slices.chat.value.currentSessionId != sid) return
    val route = slices.routeInstanceFor(sid); if (route == 0L) return
    val bundle = currentBundleStamp() ?: return
    val ticket = LaunchTicket(ReloadKey(state.ownerGeneration, sid), state, state.target,
        state.queuedPriority, state.queuedReasons, state.queuedRequiresContent, identity, bundle, route)
    state.inFlight = true; state.dirty = false
    state.queuedPriority = Priority.DIGEST; state.queuedReasons = emptySet(); state.queuedRequiresContent = false
    if (isBusy(sid)) state.nextAllowedAt = maxOf(state.nextAllowedAt, monotonicNowMs() + busyMinIntervalMs)
    val job = scope.launch(start = CoroutineStart.LAZY) { runReload(ticket) }
    reloadJobs[state] = job
    job.start()
}
```

HTTP 前再查：

```kotlin
private fun preHttpGuard(ticket: LaunchTicket): Boolean {
    val liveTransport = currentTransport()
    return foreground.value &&
        slices.chat.value.currentSessionId == ticket.key.sessionId &&
        liveTransport.generation == ticket.ownerState.ownerGeneration &&
        liveTransport.identity == ticket.connectionIdentity &&
        slices.routeInstanceFor(ticket.key.sessionId) == ticket.routeInstance &&
        currentBundleStamp() == ticket.bundleStamp
}
```

false → 不发 HTTP、不推进 marker、恢复/保留 `dirty`、不消费 retry budget、后台时无 active timer、留给 foreground/route/identity collector 后续 nudge。

### completion 所有权 + ABA

```kotlin
private fun stillOwnsLocked(sid: String, owner: ReloadState): Boolean =
    states[ReloadKey(owner.ownerGeneration, sid)] === owner
```

commit 路径（`runReload`）：session mutex → stateLock → `stillOwnsLocked` / `commitIdentityStillValid` / `mergeSkeletonIntoChatSlice`。`onReloadComplete` 仅 mutate owner；`Detached` outcome 直接 return（不触碰 state）。

**ABA 序列**：
```
A: gen=10, sid=X, state=A; A launch 捕获 ticket(owner=A, gen=10)
host switch: identity epoch→11; detachGeneration(11): lock 下移除所有 gen=10 条目 + cancel A.timerJob
  A 对象仅从 A 的 in-flight coroutine 可达
B: submit sid=X @ gen=11 → 创建 state=B @ key(11,X) → launch B
A 晚返回: sessionLock→stateLock; states[(10,X)] !== A → outcome=Detached → 无 merge/marker/dirty/priority/timer/job 对 B 的改动
B completion: states[(11,X)] === B → 仅 B 可 commit/mutate B
```

> 现有 state-identity 检查（`MessageActions.kt:1048-1049,1075-1079,1085-1093`）概念上可保留，但 generation 必须成为 key 一部分。

### generation detach

```kotlin
fun detachGeneration(newGeneration: Long) {
    val timers = synchronized(stateLock) {
        val old = states.entries.filter { it.key.generation != newGeneration }
        old.forEach { states.remove(it.key) }
        old.mapNotNull { it.value.timerJob.also { t -> t?.let { _ -> } ; it.value.timerJob = null } }
    }
    timers.forEach(Job::cancel)
}
```

> 此处**不** cancel/join 旧 in-flight 请求——其 completion 被 fence。避免 host switch 阻塞在 HTTP 上。
> 为对 reconfigure 原子，在每次 `ConnectionIdentityStore.beginReconfigure()` 之后、repository/client 重建之前**同步**调用。生产可见处 `HostProfileController.kt:623-631`。把两者封装进一个 helper，使未来 caller 无法在不 detach scheduler 的情况下 bump generation。

---

## D. 精确 pre-HTTP 信号

| 信号 | 来源 |
|---|---|
| 前台 | `AppLifecycleMonitor.isInForeground`（`:108-109`；转换 `:224-227,239-255`） |
| 当前 session | `slices.chat.value.currentSessionId`（当前检查 `MessageActions.kt:994`；digest snapshot `SessionSyncCoordinator.kt:651-655`） |
| transport generation | `ConnectionIdentityStore.currentEpoch()`（`:88-100`） |
| host/transport identity | `ConnectionIdentityStore.currentIdentity`（`:77-86`；字段 `ConnectionIdentity.kt:36-40`） |
| route identity | `SliceFlows.routeInstanceFor(sid)`（`AppStateSlices.kt:982-998`） |
| bundle/endpoint CAS | bundle 发布 `ControllerModule.kt:302-312`；stamp reducer `AppAction.kt:815-825` |

guard 放在 `runReload(ticket)` 内、`getSlimapiMessagesSkeleton()` 之前。

### bundle-bound commit
`ChatContentLoaded` 当前仅 route/session guard（`AppAction.kt:775-786`，应用 `CrossSliceFieldsReducer.kt:464-467`）。加 `val bundleStamp: BundleStamp? = null`；scheduler 产出总带它；现有无关调用点可留 null 兼容。扩展 `acceptsBundle()` 使非 null stamp 对照 `liveBundleGeneration/liveEndpointFp`。用 `dispatchAndVerify()`（实现 `SharedStateStore.kt:254-293`）。

---

## E. C2 marker + empty-page bug

### digest 提取（`handleSessionDigest`，`:965-1039`）
```kotlin
val props = event.payload.properties as? JsonObject
val contentBearing = props?.containsKey("updatedAt") == true || props?.containsKey("messageID") == true
if (contentBearing) {
    val tuple = Tuple(
        updatedAt = (props?.get("updatedAt") as? JsonPrimitive)?.longOrNull,
        messageId = (props?.get("messageID") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank))
    skeletonReloadCoordinator?.submit(sid, tuple, Priority.DIGEST,
        if (tuple.isComplete) ReloadReason.DIGEST else ReloadReason.DIGEST_MALFORMED)
}
```
两字段都缺 = status/control-only，无 message reload。任一字段在 = content-bearing。**不**比对 tuple 与 marker（C2 禁止 equality suppression）。

### marker 推进规则
仅当：`outcome == CommittedNonEmpty && ticket.target?.isComplete == true && states[ticket.key] === ticket.ownerState`。state 是 generation-scoped，detach 自动 reset marker。

### NO-ADVANCE 映射
| 条件 | 代码结果 |
|---|---|
| empty page | `ReloadOutcome.Empty`；无 merge/marker |
| malformed/partial watermark | 内容可 commit，但 `target.isComplete == false` → 不推进 |
| route CAS reject | `dispatchAndVerify()==false` 或 dispatch 前 route mismatch |
| host/bundle CAS reject | identity/bundle mismatch 或 bundle-aware reducer 拒绝 |
| cancellation | `ReloadOutcome.Cancelled`；marker 不动 |
| HTTP 200 但 merge 未 commit | `ReloadOutcome.Uncommitted`；marker 不动 + 有界 retry |
| background-suppressed | pre-HTTP guard false；无 HTTP/marker |

### 精确 empty-page bug
位置 `MessageActions.kt:1056-1063`：
```kotlin
if (page.items.isEmpty()) {
    ownerState.failed = false; ownerState.retryAttempt = 0
    watchdogJobs.remove(sessionId)?.cancel(); return@withLock
}
```
**完全删除**，替换为 `ReloadOutcome.Empty`，按 reason 区分：
```kotlin
if (page.items.isEmpty()) {
    when {
        ticket.requiresContent && !ticket.confirmsAuthoritativeEmpty -> keepDirtyAndRetry()
        ticket.confirmsAuthoritativeEmpty -> consumeDirtyWithoutMarkerAdvance()
        else -> consumeOnlyThisTicket()  // 非内容 probe：仅结束自身请求，不得擦除单独排队的 content-bearing dirty
    }
}
```
> `limit=200` **本身不**算 authoritative-empty 确认。

---

## F. R1 有界退避

初始请求后 4 次重试：`2s / 4s / 8s / 16s`，然后停（自动窗口 ≈30s）。`nextAllowedAt` 是单一 timer deadline；**不**重建 `watchdogJobs`。

```kotlin
private fun scheduleBoundedRetryLocked(state: ReloadState, reason: ReloadReason) {
    if (state.retryAttempt >= retryDelaysMs.size) return  // 停止自动重试，保留 dirty
    val delayMs = retryDelaysMs[state.retryAttempt]
    state.retryAttempt += 1; state.dirty = true
    state.queuedReasons = state.queuedReasons + reason
    state.nextAllowedAt = maxOf(state.nextAllowedAt, monotonicNowMs() + delayMs)
    scheduleTrailingLocked(state)
}
```
新 external digest/FORCE 信号 reset `retryAttempt=0`。content-bearing 的 dirty 仅可由：(1) 非空 committed merge；(2) 确认 session 删除（digest 删除分支 `SessionSyncCoordinator.kt:1018-1024`）；(3) caller 独立确认 authoritative-empty 的 force reconcile——消费。耗尽后 dirty 留给下次 external 信号 / foreground 恢复 / 后续 P4 reconcile。**不**静默置 false。

---

## G. 具体测试（对照现有 fixture 风格，扩展 `SkeletonReloadCoordinatorCoreTest.kt` `:54-100`）

### fixture 增加
```kotlin
private class CoordinatorScope {
    val scope = TestScope(StandardTestDispatcher())
    val foreground = MutableStateFlow(true)
    val generation = AtomicLong(1L)
    val identity = AtomicReference(ConnectionIdentity(epoch=1L, serverGroupFp="host-A", normalizedWorkdir="/a", endpointFp="http://a"))
    val bundleStamp = AtomicReference(BundleStamp(1L, "http://a"))
    val nowMs: () -> Long = { scope.testScheduler.currentTime }
}
private fun CoordinatorScope.coordinator(store, repo) = SkeletonReloadCoordinator(
    scope, repo, store.slices, foreground,
    currentTransport = { TransportSnapshot(generation.get(), identity.get()) },
    currentBundleStamp = { bundleStamp.get() }, monotonicNowMs = nowMs)
```
busy 种子：`sessionStatuses = mapOf("s" to SessionStatus(type="busy"))`。加 test-only `schedulerSnapshotForTest(sid, generation): SchedulerSnapshot?`（仅暴露 dirty/inFlight/timerActive/priority/retryAttempt/marker）。

### T-C1-a digest-during-in-flight
busy；repo 首次 `entered` await `release`，二次返回非空。submit A→runCurrent；await 首次进入（call=1）；submit B→runCurrent；assert call 仍 1、snapshot dirty/inFlight、target B；release 首；`nextAllowedAt` 前无二次；advance 2000ms→恰二次开始；max 并发=1；终 marker=B。

### T-C1-b FORCE 取代 queued DIGEST
busy；repo 记录每次 `limit`，非空。首 digest launch @ t=0（nextAllowedAt=2000）；完成；t=250 submit DIGEST（timer queued）；submit FORCE；再 submit DIGEST；t=2000 前 assert 仅首 call；t=2000 run scheduler。`assertEquals(listOf(50, 200), limits)`；末 DIGEST 不另造 50 call（并入 FORCE target）。

### T-C1-c 后台立即取消 timer
busy；首请求阻塞；submit 首 digest→二次 digest（in-flight）。`foreground=false`→runCurrent（coordinator foreground collector 调 `cancelForBackground()`）；assert snapshot 无 timer、dirty 仍 true；release 首；advance 60s。assert call 恒 1、无 trailing、state 仍 dirty。

### T-C1-d 密集 250ms 限速
RTT 循环 `100/500/2000`，每 RTT 新 fixture。repo `delay(rtt)` 返回非空。busy；`[0,60000)` 每 250ms submit 一 digest（distinct tuple id 防 equality-suppression 假阳性）。每 RTT：`launchTimes.count{it<60000} <= 30`、`zipWithNext.all{b-a>=2000}`、`maxConcurrent==1`。证明限速基于时钟非 RTT。

### T-C1-e host-A→host-B 同 sid ABA
用现有 `Dispatchers.Default` + `CountDownLatch`（`:618-743`）。gen1/host-A 请求 `s` 阻塞返回 `a-stale`；原子改 transport→gen2/host-B；同步 `coordinator.detachGeneration(2)`；改 bundle→host-B；submit tuple B（同 sid）；B 返回 `b-fresh`→await commit；release A。assert store 仅 `b-fresh`、`a-stale` 永不现、gen2 marker=B、gen1 snapshot 缺、A completion 不造 gen2 timer/retry、不改 B priority/dirty。

### T-C1-f onSessionClosed
busy；t=0 完成首请求；t=250 submit digest→验证 t=2000 timer 存在。`cs.scope.launch { c.onSessionClosed("s") }`→runCurrent。assert close.isCompleted、snapshot(gen,s)=null、timer/reload-job 计数 0、advance 60s call 仍 1。保留 `:618-743` 的确定性 late-return 测试，改为 generation-aware。

### T-C2-a 全 NO-ADVANCE（拆为聚焦测试）
- **empty**：complete tuple→空页→仅首 attempt。marker null、dirty true。
- **malformed/partial**：submit `Tuple(123, null)`→非空。messages committed 但 marker null。
- **route CAS reject**：阻塞 HTTP，release 前 `chatRouteInstance++` 或切 route。无 commit、marker null。
- **host/bundle CAS reject**：阻塞 HTTP，release 前转 bundle（不接受旧 bundle-bound action）。`dispatchAndVerify==false`、旧 marker null、dirty 保留。
- **cancellation**：阻塞 HTTP；`onSessionClosed("s")`；release。state 移除、无 marker、无 stale commit。
- **HTTP 成功但 uncommitted**：注入 `commitAction = { false }`；返回非空。marker null、dirty/retry 保留。
- **background suppression**：submit 前 foreground=false→runCurrent→advance。repo 0 调用、marker null、dirty true、无 timer。

### T-R1 唯一 digest→空→retry→可见
```kotlin
var calls = 0
coEvery { repo.getSlimapiMessagesSkeleton("s", 50, null) } coAnswers {
    calls++; if (calls==1) MessagesPage(emptyList(), null) else MessagesPage(listOf(mwp(msg("eventual", created=100L))), null)
}
```
submit 一 complete content tuple；runCurrent→calls=1、transcript 不变、marker null、dirty true；advance 1999ms 仍 1 call；advance 1ms→二次；runCurrent。`assertEquals(2, calls)`、`messages.map{it.id}==["eventual"]`、marker==tuple、`!dirty`。无二次 digest。

### 现有测试需更新
两处 watchdog 假设冲突：empty-success watchdog-reset（`:293-336`）、无限/300s-cap watchdog 阶梯（`:791-864`）→替换为 `2/4/8/16s` 有界 retry 边界 + 耗尽后"dirty 保留、无第五次 retry"。`:556-587`（digest-after-failure 立即 reload）需计 `nextAllowedAt`：新 digest reset retry 预算但不绕过 2s busy cap。

---

## H. 风险 / gotchas

1. **"authoritative empty" 当前无证明**——`getSlimapiMessagesSkeleton()` 只返 `MessagesPage`（`OpenCodeRepository.kt:1925-1934`），空页不能建立"存在但权威 0 消息"。**缓解**：永不凭 HTTP 200 或 `limit=200` 推断；`FORCE_RECONCILE_AUTHORITATIVE_EMPTY` 留给后续有 session-list/status 证据的 P4 路径。
2. **foreground "immediate" 延迟 700ms**——`AppLifecycleMonitor` 为防 rotation 延迟 false 转换（`:193-204,239-255`）。**缓解**：L3 在 `isInForeground=false` 即取消；若产品要原始 Activity `startedCount==0` 边，L4 须暴露独立 lifecycle 边，L3 无法从当前 public flow 推导。
3. **generation detach 必须共享 reconfigure barrier**——guard 防 stale HTTP/commit，但不满足"立即取消旧 timer"。**缓解**：把 `identityStore.beginReconfigure()` + 同步 `scheduler.detachGeneration(newGen)` 封装进一个 helper（生产处 `HostProfileController.kt:623-631`）；加回归测试确保未来 caller 不裸调 `beginReconfigure()`。
4. **`ChatContentLoaded` 当前无 host CAS**——仅 route/session（`CrossSliceFieldsReducer.kt:464-467`）。**缓解**：加可选 `bundleStamp`，scheduler 总填，用 `dispatchAndVerify()`；不凭 pre-dispatch host 检查推进 marker。
5. **不要把 trailing 实现为可重启 debounce**——每 250ms digest cancel-and-rearm 会永久推迟（同类失败 = 方案中那个未接线的 100ms debounce）。**缓解**：timer deadline = 最早 `nextAllowedAt`；后续提交 mutate queued work 但不后移 timer。
6. **优先级提升时保留 content-bearing 需求**——FORCE 取代 DIGEST 只换 limit/priority，不得擦除 digest 的内容需求/target。**缓解**：`queuedRequiresContent` 用 OR 聚合，`target` 仅由 content-bearing 信号更新。
7. **route switch ≠ session 删除**——当前 init collector 每次 session 变化调 `onSessionClosed(previousSid)`（`MessageActions.kt:974-988`），摧毁仅被隐藏 session 的 dirty。**缓解**：替换为"为不再当前的 route 取消 timer，保留 dirty"；`onSessionClosed()` 仅用于确认删除/真实 lifecycle dispose；打开/前台一个 session 必须 nudge 其保留的 state。

---

## 实现验收清单
- 无外部调用点能调 `launchReload()`。
- 无 `watchdogJobs`/`pendingLimit`/`desiredEpoch`/immediate completion re-fire 残留。
- 所有 timer generation-owned，wake 时 owner-identity 校验。
- scheduler lock 下无 network/delay/join。
- 旧 generation completion 无法 dispatch 或触碰新同 sid state。
- 空 content-bearing 结果保留 dirty，`2/4/8/16s` retry。
- marker 推进用 `dispatchAndVerify()` + complete request tuple。
- 密集 busy digest 在半开 60s 窗口内至多 30 次 launch。
- `./scripts/check.sh` 为最终实现 gate。
