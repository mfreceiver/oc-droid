# 状态机简化方案（v3，基于 rev-gpt v2 评审 @0.97 逐条修订）

> **状态**：讨论稿。基于 v2（被 rev-gpt @0.97 评审为 NO-GO，7 critical）的修订。
> 修订原则：严格按 rev-gpt 的 7 个 suggestion 逐条改，不发明自己的方案。

## 0. 修订总结（v2 → v3）

### v2 的错误（rev-gpt @0.97 证实）

| # | v2 缺陷 | rev-gpt 证据 |
|---|---|---|
| C1 | S3 把 token stream 和 global SSE 当成一个传输，委托给 ServiceSseConnectionOwner | 两个独立 HTTP 通道（`/slimapi/sessions/{sid}/stream` vs `/slimapi/events`），独立 socket/失败模式/心跳/重连 |
| C2 | DefaultSseReconnectSupervisor 管 token 重连 | 它协调 FGS/ownership/gate——token 503 不应触发全局恢复 |
| C3 | AuthorityReducer 吸收 token reducer 状态 | token 状态是 per-part 文本/bundle fence，不是 status——混入制造 god reducer |
| C4 | bundleCommitLock → Mutex | 生产代码传 `repository` 作为锁，`OpenCodeRepository.configure()` 的 `@Synchronized` 依赖同一 monitor |
| C5 | S2 的 3 层 fence 合并为 1 个引用检查 | epoch/job-ref/terminal-ref 保护不同边界；data class 循环依赖（job 闭包捕获 attempt，attempt 需要 job） |
| C6 | S1 字段清单/同步模型不准 | 不是 9 个 @Volatile（部分是 plain var 受 mutex 保护）；Job/Deferred 不能进 CAS record（retry 重复副作用） |
| C7 | 33% 行数目标数学不成立 | 12000-1300=10700≠8000；分解≠删除（提取到子文件不减总量） |

### v3 的核心转变

v2 试图"简化耦合"但具体方案错了。v3 的转变：
- **S3 从"委托给其他组件"改为"token-specific 分解"**（4 个 token 专属组件，保留 token 独立传输身份）
- **S1 从"flat Boolean record + CAS"改为"TransportLease sealed phase + 外部副作用在 CAS loop 外"**
- **S2 收窄**：只合并 Service 侧 epoch/job/abort，terminal 留给 S4
- **S4 升级为完整 gate-owned StartingLease 设计**（含 timer scope + lease 生命周期）
- **保留 bundleCommitLock**（不换 Mutex，直到 bundle publication 端到端重设计）
- **行数目标→架构质量目标**
- **阶段重排**：S1-design→S1-impl→S4→S2→S3a→S3b→S3c

## 1. S1 — ServiceSseConnectionOwner TransportLease 模型

### 1.1 问题（rev-gpt C6）

当前 ServiceSseConnectionOwner 混合了三类不同性质的字段：
- **generation metadata**（`transportGenerationCounter`、`closingGeneration`、`gapEmittedForGen`、`exhaustedReportedForGen`、`resyncHandledForGen`、`resyncInFlightForGen`、`resyncDirtyForGen`）——纯标记，无外部引用
- **协程/句柄**（`sseJob`、`transportTimeoutJob`、`pendingReadiness`）——cancellation/completion handles，有外部副作用
- **运行时状态**（`activeAttempt`、`activeIdentity`）——必须与 `SseTransportRuntimeStore` 的 transition 在 `dropHandler` monitor 下一致

v2 错误地把它们全塞进一个 CAS record。rev-gpt 指出：Job/Deferred 的 cancel/complete 是外部副作用，不能在 retryable CAS loop（`updateAndGet`/`compareAndSet`）内运行——CAS 失败重试会重复执行副作用。

### 1.2 TransportLease 设计（rev-gpt suggestion #1）

**核心**：用一个 immutable lease 对象表示"当前传输代次的身份 + phase"，但**不包含**协程句柄或外部引用。协程句柄留在 `connectMutex` 下管理。

```kotlin
// 新文件：service/streaming/TransportLease.kt

/**
 * §state-machine-v3 S1: immutable identity of the current transport
 * generation. Captured by asynchronous callbacks for exact-reference lease
 * validation. Does NOT hold coroutine handles (Job/Deferred) or external
 * runtime references — those remain under [connectMutex] / [resyncMutex]
 * and are NEVER mutated inside a CAS retry loop.
 */
data class TransportLease(
    val generation: Long,
    val identity: ConnectionIdentity,
    val runtimeAttempt: TransportAttemptToken?,
    val phase: TransportLeasePhase,
    val outage: OutageState,
)

/**
 * Constrained transport lifecycle phase. Sealed to make invalid
 * combinations (e.g. Closing + resyncInFlight) unrepresentable
 * (rev-gpt concern #3: v2's flat Booleans permitted invalid combos).
 */
sealed interface TransportLeasePhase {
    /** Connect accepted, awaiting first valid frame. */
    data object Connecting : TransportLeasePhase
    /** First valid frame received, streaming live. */
    data object Live : TransportLeasePhase
    /** Transient collection failure, retrying within the same generation. */
    data class Retrying(val attempt: Int) : TransportLeasePhase
    /** Intentional disconnect in progress. */
    data object Closing : TransportLeasePhase
    /** Retry budget exhausted, generation terminal. */
    data object Exhausted : TransportLeasePhase
}

/**
 * Outage tracking within a generation. gapEmitted + exhaustedReported
 * fold into the phase + this field, eliminating the separate @Volatile
 * booleans.
 */
data class OutageState(
    val gapEmitted: Boolean = false,
    val exhaustedReported: Boolean = false,
)
```

**resync 子状态**保留独立的 mutex（`resyncMutex`），**不进 TransportLease**——因为 resync 可以在一个 transport generation 内循环多次（rev-gpt C6："resync in-flight/dirty state is serialized through resyncMutex and can cycle multiple times within one transport generation"）。

### 1.3 Linearization points（rev-gpt suggestion #1 要求）

在实施前定义每个转移的线性化点（原子化的最小单位）：

| # | 转移 | 线性化点 | 锁 |
|---|---|---|---|
| 1 | accepted connect | `leaseRef` CAS `null → Connecting(gen, attempt=null)` | 无锁 CAS |
| 2 | first valid frame | `leaseRef` CAS `Connecting → Live` + `runtimeStore.markLive()` | `connectMutex`（markLive 在锁外） |
| 3 | outage (gap) | `leaseRef` CAS `Live → Retrying(n)` + `outage.gapEmitted=true` + `emitGapOnce()` | `connectMutex`（emit 在锁外） |
| 4 | recovered frame | `leaseRef` CAS `Retrying(n) → Live` | `connectMutex` |
| 5 | intentional close | `leaseRef` CAS `* → Closing` + cancel sseJob + `markStopped()` | `connectMutex`（cancel/join 在锁外） |
| 6 | exhaustion | `leaseRef` CAS `Retrying(n) → Exhausted` + `routeUnexpectedDrop` + `onTerminalExhaustion` | `connectMutex`（副作用在锁外） |
| 7 | resync scheduling | `resyncInFlight` CAS under `resyncMutex`（不改 leaseRef） | `resyncMutex` |
| 8 | shutdown | `leaseRef` CAS `* → Closing` + `markStopped` + cancel job | `connectMutex`（cancel 在锁外） |

**关键规则**：CAS 只更新 `leaseRef`（纯 metadata）。所有外部副作用（`runtimeStore.markLive/markStopped`、`emitGapOnce`、`routeUnexpectedDrop`、job.cancel）在 CAS 成功**后**、锁**外**执行。CAS 失败 = lease 已被其他线程更新 = 当前操作过时 = 放弃（不重试副作用）。

### 1.4 不进 TransportLease 的字段（保留现有保护）

| 字段 | 保护 | 理由 |
|---|---|---|
| `sseJob` / `transportTimeoutJob` | `connectMutex` | Job cancellation 是外部副作用，不能在 CAS loop 内 |
| `pendingReadiness` | `connectMutex` | CompletableDeferred completion 是外部副作用 |
| `resyncInFlight` / `resyncDirty` | `resyncMutex` | 独立子状态机，可在一个 generation 内循环 |
| `activeIdentity` | `connectMutex` | 随 connect/cancel 变化，与 lease.phase 同步但不独立 CAS |

### 1.5 改动范围

| 文件 | 改动 |
|---|---|
| `service/streaming/TransportLease.kt` | **新建**：TransportLease + TransportLeasePhase + OutageState |
| `service/streaming/ServiceSseConnectionOwner.kt` | 替换 7 个 generation 字段为 `AtomicReference<TransportLease>`；保留 sseJob/pendingReadiness 在 connectMutex 下；所有 CAS 后副作用提取到锁外 |

**预期**：generation metadata 从 7 个分散 @Volatile/plain var → 1 个 AtomicReference。外部副作用安全地留在锁下/锁外，不进 CAS loop。

---

## 2. S4 — Gate-owned StartingLease（Stage-2 zombie reap 收敛）

### 2.1 问题（rev-gpt C5 + suggestion #5）

v0.18.13 的 Stage-2 zombie reap 跨了 launcher↔gate 两个文件：launcher 的 `ensureStarted` 在 Stage-2 超时后调 `gate.failStartingIfTerminal(identity, expectedTerminal, reason)` + `extracted.runTeardown()`。这是正确的但耦合复杂——launcher 需要 gate 的 terminal 引用，gate 的回调需要 launcher 的 job/sse handle。

rev-gpt 指出：gate 是 Starting→Ready 转移的自然所有者。一个 gate-owned `StartingLease`（携带自己的 deadline）可以消除 launcher 侧的 zombie reap。

### 2.2 StartingLease 设计（rev-gpt suggestion #5）

**前提**：gate 当前是 passive `synchronized` state holder，无协程 scope。引入 timer 需要：
- 注入 application scope 或 timer abstraction
- 每个 Starting lease 对应一个 timer job
- markReady/failStarting/release/replacement/shutdown 时取消 timer
- timer 到期后 reference check + 锁内提取 + 锁外 teardown

```kotlin
// OwnershipModels.kt 新增

/**
 * §state-machine-v3 S4: a Starting owner's lifecycle lease, owned by the
 * gate. Carries the Stage-2 deadline so the gate can self-reap expired
 * zombies without the launcher reaching into gate internals.
 *
 * The gate schedules a timer for the exact lease reference. On timeout:
 * 1. acquire gate lock
 * 2. confirm owner === capturedLease and still Starting
 * 3. transition owner to null
 * 4. complete terminal with BootstrapFailed
 * 5. extract callbacks
 * 6. release lock
 * 7. run teardown outside lock
 *
 * markReady / failStarting / release / replacement cancels the timer.
 */
data class StartingLease(
    val attemptId: Long,
    val identity: ConnectionIdentity,
    val terminal: CompletableDeferred<OwnershipStartResult>,
    val disconnectAndJoin: suspend (Boolean) -> Unit,
    val abortStartup: () -> Unit,
)
```

**关键设计约束**：
- 30s transport timeout（ServiceSseConnectionOwner 的 first-frame 超时）和 45s Stage-2 deadline（gate lease 超时）**职责不同，不能合并**（rev-gpt concern #7）：
  - owner timeout = 传输激活失败
  - gate lease timeout = ownership 工作流未能 settle
- timer 到期后必须做 reference check（`owner === capturedLease`），防止 stale timer 误杀 replacement
- teardown 在锁外执行（`runTeardown` 含 try/finally）

### 2.3 Gate 改动

```kotlin
// StreamingOwnershipGate.kt

class StreamingOwnershipGate(
    // 新增：timer scope（注入），用于 S4 lease deadline
    private val timerScope: CoroutineScope,
    // ... 现有字段
) {
    // registerStarting 时，为 Starting owner 启动 timer
    private val leaseTimers = mutableMapOf<ConnectionIdentity, Job>()

    fun registerStarting(...): RegisterStartingOutcome = synchronized(lock) {
        // ... 现有逻辑
        if (outcome == Accepted) {
            // §S4: schedule Stage-2 deadline timer for this lease
            scheduleLeaseTimer(identity, starting)
        }
    }

    private fun scheduleLeaseTimer(identity: ConnectionIdentity, owner: Starting) {
        leaseTimers[identity]?.cancel()
        leaseTimers[identity] = timerScope.launch {
            delay(STAGE2_TIMEOUT_MS)
            synchronized(lock) {
                val current = this.owner
                if (current !is Starting || current.identity != identity) return@synchronized
                if (current !== owner) return@synchronized // stale timer
                // Reap: transition to null + complete terminal
                this.owner = null
                current.terminal.complete(OwnershipStartResult.Refused(OwnershipRefusal.BootstrapFailed))
                waiters.remove(identity)?.forEach {
                    it.complete(OwnershipStartResult.Refused(OwnershipRefusal.BootstrapFailed))
                }
                leaseTimers.remove(identity)
                // 提取 callbacks 供锁外 teardown
                pendingTeardown.add(current)
            }
            // 锁外 teardown
            pendingTeardown.poll()?.runTeardown(markGap = false)
        }
    }

    fun markReady(identity: ConnectionIdentity) = synchronized(lock) {
        // ... 现有逻辑
        leaseTimers.remove(identity)?.cancel() // §S4: cancel timer on Ready
    }

    // failStarting / release / disconnectAndRelease 同样 cancel timer
}
```

### 2.4 Launcher 改动

```kotlin
// StreamingServiceLauncher.kt — Stage-2 路径大幅简化

// BEFORE (v0.18.13):
//   val terminal = withTimeoutOrNull(STAGE2_TIMEOUT_MS) { attempt.terminal.await() }
//   if (terminal == null) {
//       val settled = withTimeoutOrNull(STAGE2_SETTLE_GRACE_MS) { attempt.terminal.await() }
//       if (settled != null) return settled
//       val extracted = ownershipGate.failStartingIfTerminal(...)
//       extracted?.runTeardown(false)
//       return Refused(BootstrapFailed)
//   }

// AFTER (v3 S4):
//   Gate 自行管理 Stage-2 deadline。launcher 只需 await terminal。
//   1s settle grace 保留（markReady 锁外完成的边界窗口）。
val terminal = withTimeoutOrNull(STAGE2_TIMEOUT_MS + STAGE2_SETTLE_GRACE_MS) {
    attempt.terminal.await()
}
return terminal ?: OwnershipStartResult.Refused(OwnershipRefusal.BootstrapFailed)
```

Launcher 不再需要 `failStartingIfTerminal`、`STAGE2_SETTLE_GRACE_MS` 的 zombie reap 逻辑、extracted 回调调用。这些都是 gate 的内部 timer 完成。

### 2.5 测试（rev-gpt suggestion #5 要求）

- Ready exactly at deadline
- stale timer vs same-identity replacement
- multiple same-identity joiners
- Stage-1 expiry vs Stage-2 expiry
- Service destruction during expiry
- disconnect throwing or being cancelled
- duplicate timer invocation

---

## 3. S2 — Service-side Bootstrap attempt 收窄合并

### 3.1 问题（rev-gpt C5 + suggestion #2）

v2 试图把 epoch + job-ref + terminal-ref 合并为一个对象。rev-gpt 指出：
1. 它们保护不同边界（epoch 防 SSE 代次 stale、job-ref 防 bootstrap job 替换、terminal-ref 防 gate ABA）
2. terminal 由 gate/launcher 创建，Service 只收到 attemptId（通过 Intent）——没有 gate 的 terminal 对象
3. data class 循环依赖（job 闭包捕获 attempt，attempt 需要 job）

### 3.2 收窄后的 BootstrapAttempt（rev-gpt suggestion #2）

**只合并 Service 侧的 epoch + job + abort**。Terminal 留在 gate（直到 S4 完成）。

```kotlin
// SessionStreamingService.kt 新增内部类

/**
 * §state-machine-v3 S2: per-attempt Service-side bootstrap state. Captured
 * by gate callbacks for exact-reference validation. Replaces the separate
 * bootstrapEpoch (AtomicLong) + BootstrapJobHolder (AtomicReference<Job?>)
 * + bootstrapAbortIssued (AtomicBoolean) triple.
 *
 * §rev-gpt-C5: terminal reference stays in the gate (S4 introduces a
 * gate-owned StartingLease). This object only consolidates the Service-side
 * fence surfaces.
 */
private class BootstrapAttempt(
    val epoch: Long,
    val gateAttemptId: Long,
    val sse: ServiceSseConnectionOwner,
) {
    val abortIssued = AtomicBoolean(false)
    val job = AtomicReference<Job?>(null)
}
```

**安全构造顺序**（rev-gpt suggestion #2）：
1. 创建 attempt 对象（epoch + gateAttemptId + sse 已知）
2. atomically install 为 currentAttempt
3. 创建 LAZY job（闭包捕获 attempt 对象）
4. CAS attach job 到 attempt.job
5. 仅在 gate registration 成功后 start job

**guard 检查**：
```kotlin
// 回调检查
val current = currentAttempt.get()
if (current === capturedAttempt) {
    // 仍是我自己的 attempt
} else {
    // 被替换了——no-op
}
```

### 3.3 bootstrapAbortIssued 收敛

rev-gpt concern #4 指出：`bootstrapAbortIssued` 当前是 Service-global，每个 `onStartCommand` reset。S2 的 per-attempt 对象可以让 abort idempotence attempt-local：

```kotlin
// 回调中
if (capturedAttempt.abortIssued.compareAndSet(false, true)) {
    // 首次 abort——执行 teardown
    capturedAttempt.job.get()?.let { job ->
        if (this.bootstrapJobHolder.removeIfCurrent(job) != null) {
            job.cancel()
        }
    }
    scope.launch { coordinator.teardownAndAwait(BootstrapFailure) }
}
// 重复 abort 是 no-op（CAS 失败）
```

### 3.4 不做什么

- **不合并 terminal**（留给 S4）
- **不改 gate**（S2 纯 Service 侧改动）
- **不改 launcher 的 ensureStarted**（S4 才简化 launcher）

---

## 4. S3 — TokenStreamCoordinator 分解（token-specific，不委托）

### 4.1 核心修正（rev-gpt C1/C2/C3）

v2 的错误：试图把 token stream 的状态委托给 ServiceSseConnectionOwner / AuthorityReducer / DefaultSseReconnectSupervisor。rev-gpt 证明：
- token stream 是**独立 HTTP 通道**（`/slimapi/sessions/{sid}/stream`），与 global SSE（`/slimapi/events`）有独立 socket/失败模式/心跳/重连
- token 503 不应触发全局 SSE/FGS 恢复
- token reducer 状态（per-part 文本/bundle fence）不是 session status——混入 AuthorityReducer 制造 god reducer

**v3 修正**：token stream 分解为**token-specific 组件**，状态保留在 token 域内。

### 4.2 4 组件分解（rev-gpt suggestion #3）

```
TokenStreamCoordinator (facade, ~400-700 行)
  ├── TokenStreamLifecycleArbiter    (route/foreground 仲裁)
  ├── TokenStreamConnectionRunner     (连接执行 + token-local watchdog + 重连)
  ├── TokenStreamFrameProcessor       (帧 epoch + reducer + bundle commit)
  └── TokenBundleCommitGate           (bundle 校验 + repository monitor)
```

#### TokenStreamLifecycleArbiter

**拥有**：
- visible-route 和 foreground 组合状态
- desired stream（单消费者，合并 route observer + foreground observer）
- max-one active stream 约束
- open/close/suspend 语义
- route-instance 捕获

**消除**：v2 的 `desired` 双消费者（route observer + foreground observer 竞争 `getAndSet`）→ 合并为单一 `LifecycleArbiter` 内部的 `combine(route, foreground)` collect。

#### TokenStreamConnectionRunner

**拥有**：
- token endpoint collection 执行
- **token-local watchdog**（独立于 global SSE 的 ServiceSseConnectionOwner——rev-gpt C1）
- reconnect backoff（可共享纯计算函数，但状态机独立——rev-gpt C2）
- 503 streak / degraded state
- reconnect sentinel / unwind ordering

**不委托给**：DefaultSseReconnectSupervisor（它管 FGS/ownership/gate）

#### TokenStreamFrameProcessor

**拥有**：
- frame epoch validation（per-active-stream，非 per-sid map——见 §4.3）
- TokenStreamReducerState
- part ownership generation
- reducer effects
- ChatState projection
- message/part removal hooks

**不委托给**：AuthorityReducer（它管 session status，不是 token 文本）

#### TokenBundleCommitGate

**拥有**：
- exact bundle validation
- **保留现有 repository-monitor 契约**（rev-gpt C4：不换 Mutex）
- stamped dispatch

**不做什么**：不替换 `bundleCommitLock = repository` 的 JVM monitor 契约——直到 bundle publication 端到端重设计（独立提案）。

### 4.3 per-sid maps → 单 active lifecycle 对象

rev-gpt suggestion #3 关键洞察："Because only one token stream is active, several per-sid maps may be replaceable by one active lifecycle object."

当前 `epochBySid`/`genBySid`/`attemptBySid`/`consecutive503BySid`/`reducerStateBySid` 是 ConcurrentHashMap<String, ...>——但因为**同时只有一个 token stream 活跃**，大部分条目是过时的。可以改为：

```kotlin
// 替代 5 个 ConcurrentHashMap
data class TokenStreamLifecycle(
    val sid: String,
    val epoch: Long,
    val generation: Long,
    val attempt: Int,
    val consecutive503: Int,
    val reducerState: TokenStreamReducerState,
)

private val activeLifecycle = AtomicReference<TokenStreamLifecycle?>(null)
```

切换 sid 时，旧 lifecycle 丢弃（或提取 degraded/backoff 信息到小 bounded registry）。

**这是 v3 唯一可能产生真正行数删除的地方**（rev-gpt suggestion #6）——per-sid maps 有大量 bookkeeping（put/get/remove/clear/merge），单 lifecycle 对象消除了这些。

### 4.4 改动范围

| 文件 | 改动 |
|---|---|
| `ui/controller/sse/TokenStreamLifecycleArbiter.kt` | **新建** |
| `ui/controller/sse/TokenStreamConnectionRunner.kt` | **新建** |
| `ui/controller/sse/TokenStreamFrameProcessor.kt` | **新建** |
| `ui/controller/sse/TokenBundleCommitGate.kt` | **新建**（从现有代码提取，保留 repository monitor） |
| `ui/controller/sse/TokenStreamCoordinator.kt` | **大幅缩减**为 facade（~400-700 行），委托给 4 个组件 |

### 4.5 S3 分步（rev-gpt suggestion #7）

- **S3a**：提取 TokenStreamFrameProcessor（纯行为保持，不移动状态）
- **S3b**：提取 TokenStreamLifecycleArbiter + TokenStreamConnectionRunner
- **S3c**：评估哪些 per-sid maps 真正冗余（max-one lifecycle 替代），产生真正删除

---

## 5. 架构质量目标（替代行数目标）

rev-gpt C7：v2 的"12000→8000（-33%）"数学不成立。v3 改为可测量的架构质量目标：

| # | 目标 | 当前 | v3 目标 | 验证方法 |
|---|---|---|---|---|
| 1 | 无生产文件 >800 行 | TokenStreamCoordinator 1900 行 | facade ≤700 行 | `wc -l` |
| 2 | 无重复 transport 状态 | token stream 和 global SSE 各自管理 generation | token 组件独立但不复制 global SSE 状态 | 代码审查 |
| 3 | 无 CAS loop 内外部副作用 | ServiceSseConnectionOwner 的 @Volatile 读改写 | TransportLease CAS 只更新 metadata；副作用在锁外 | grep + 代码审查 |
| 4 | 所有异步回调携带 immutable lease identity | epoch + job-ref + terminal-ref 三层 | 单一 lease 对象引用相等 | grep `=== captured` |
| 5 | 每个 extracted owner 有转移表 + race 测试 | TokenStreamCoordinator 测试薄弱 | 4 个组件各有转移表 + race 测试 | 测试套件 |
| 6 | 无 flat Boolean 可表示无效组合 | ServiceSseConnectionOwner 的 7 个 @Volatile Boolean | sealed TransportLeasePhase | 编译器穷尽检查 |
| 7 | gate 自管 Stage-2 deadline | launcher 跨文件调 failStartingIfTerminal | gate-owned StartingLease timer | 代码审查 |

---

## 6. 实施路线图（rev-gpt suggestion #7 阶段重排）

| 阶段 | 内容 | 风险 | 前置 |
|---|---|---|---|
| **S1-design** | TransportLease transition table + linearization points 定义 | 无（设计） | 无 |
| **S1-impl** | ServiceSseConnectionOwner generation 收敛（7 @Volatile/plain → TransportLease + sealed phase） | 中（1471 行核心文件） | S1-design |
| **S4** | Gate-owned StartingLease（timer scope + lease 生命周期 + launcher 简化） | 中（gate 是核心组件） | S1-impl（transport lease 稳定后） |
| **S2** | Service-side BootstrapAttempt 合并（epoch + job + abort，terminal 已由 S4 的 StartingLease 管理） | 低-中 | S4 |
| **S3a** | TokenStreamFrameProcessor 提取（行为保持） | 中（1900 行文件） | S1-impl |
| **S3b** | TokenStreamLifecycleArbiter + ConnectionRunner 提取 | 中 | S3a |
| **S3c** | per-sid maps → 单 active lifecycle（真正删除） | 中 | S3b + 遥测确认 |

**每个阶段独立可发版。S1→S4→S2→S3a→S3b→S3c 顺序减少重叠重写。**

---

## 7. 保留不动的（rev-gpt VERIFIED SAFE 确认）

| 项 | 决定 | rev-gpt 评定 |
|---|---|---|
| **OptimisticClaimWatchdog** | 保留 | ✅ v1 C1 resolved（turn token 非 execution-causal） |
| **AuthorityReducer 3 路径 + 7s 窗口** | 保留 | ✅ v1 C5/C6 resolved（no-replay 约束下的必要 fallback） |
| **AuthorityReducer status authority 模型** | 不改 | ✅ P0-A/B/C 是好的基础 |
| **StreamingLifecycleCoordinator L1-L4** | 保留 | ✅ Android FGS 固有约束 |
| **ConnectionPhase + BannerHysteresis** | 保留 | ✅ UI 层设计良好 |
| **ProcessStatusPoller** | 保留 | ✅ SSE 降级 fallback |
| **ConnectionHealthProbe TOFU** | 保留 | ✅ 安全特性 |
| **bundleCommitLock = repository** | 保留 | ✅ rev-gpt C4（不换 Mutex 直到 bundle publication 重设计） |
| **slimapi 协议** | 不改 | ✅ v2 scope discipline sound |

---

## 8. 与 rev-gpt v2 评审的逐条对照

| rev-gpt v2 critical | v3 处理 |
|---|---|
| C1（S3 混淆两个传输） | ✅ S3 重写为 token-specific 4 组件，不委托给 global SSE |
| C2（DefaultSseReconnectSupervisor 不能管 token） | ✅ TokenStreamConnectionRunner 独立拥有 token-local watchdog + 重连 |
| C3（AuthorityReducer 不能吸收 token 状态） | ✅ TokenStreamFrameProcessor 独立拥有 token reducer 状态 |
| C4（bundleCommitLock → Mutex 不安全） | ✅ TokenBundleCommitGate 保留 repository monitor 契约 |
| C5（S2 的 3 层 fence 不可合并） | ✅ S2 收窄为 Service 侧 epoch+job+abort；terminal 留给 S4 |
| C6（S1 字段清单/同步模型不准） | ✅ S1 重写为 TransportLease sealed phase + 副作用在 CAS loop 外 + linearization points |
| C7（33% 行数目标不成立） | ✅ 改为 7 项架构质量目标 |

| rev-gpt v2 concern | v3 处理 |
|---|---|
| #1（v2 回应了 v1 的 7 critical） | ✅ 保持（v3 不改 v2 对 v1 的回应） |
| #2（S1 不应追求"全 CAS"） | ✅ TransportLease CAS 只管 metadata，副作用在锁外 |
| #3（flat Boolean 允许无效组合） | ✅ sealed TransportLeasePhase |
| #4（S2 缺 attempt-local abort） | ✅ BootstrapAttempt.abortIssued = AtomicBoolean per-attempt |
| #5（S2/S4 顺序尴尬） | ✅ 阶段重排：S4 先于 S2 |
| #6（S4 的"owner carries deadline"不完整） | ✅ S4 完整设计 timer scope + lease 生命周期 + 7 项测试 |
| #7（30s/45s 区别必须保留） | ✅ §2.2 明确区分 owner timeout vs gate lease timeout |
| #8（"每阶段低风险"夸大） | ✅ 风险评级改为"中"（除 S2 低-中） |
