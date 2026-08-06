# 状态机简化方案（v4.6，基于 rev-gpt v3 评审 @0.97 逐条修订）

> **状态**：讨论稿。基于 v3（被 rev-gpt @0.97 评审为 NO-GO，7 critical，但架构方向确认为 sound）的修订。
> v3 进展：C1-C4/C7 真正解决；v4.6 聚焦 S1/S4/S2 的 race-safe 实现细节。

## 0. 修订总结（v3 → v4）

### 0.6 v4.5 → v4.6 修订总结

> **§v4.6**：本节是对 rev-gpt v4.5 review 的六项 critical 修复；同时覆盖 review 中与这些修复重合的五项 suggestion，并单独补充第六项测试 suggestion。

1. **C1：取消 exact-state read 的 TOCTOU 协议**：每个会产生外部副作用的 phase variant 都携带 `sideEffectsClaimed`。副作用路径必须用 CAS 抢占该标志；intentional close / shutdown 看到已抢占的标志时不得再夺取同一 lease 的副作用所有权。协议覆盖 gap、frame publication/readiness、drop routing 与 terminal exhaustion。
2. **C2：修正 row 2 的 shutdown 泄漏**：`markLive` 成功后、任何 frame publication 或 readiness completion 前再次检查 `Shutdown` tombstone；发现已关闭则抑制全部副作用。
3. **C3：修正 rows 1b/1c 的 runtime 顺序**：先在 `synchronized(dropHandler)` 内 rollback/stop 旧 runtime，再 `beginAttempt`，随后用带 `Shutdown` wildcard guard 的 `getAndUpdate` 安装新 lease；新 attempt 若失去 shutdown race 则 rollback 并 abort。
4. **C4：让 Stage-2 scheduler 接受 `suspend () -> Unit`**：生产实现使用注入的非 `Main.immediate` scope；gate 持有一个 gate-owned `SupervisorJob` child scope，跨越单次 `markReady`/`failStarting`，并在 gate shutdown 时取消。
5. **C5：修正 60s defensive fallback**：返回 `Refused(BootstrapFailed)` 前必须以 `attempt.terminal` 做 terminal-reference reap，调用 `failStartingIfTerminal`，并在锁外运行 `Starting.runTeardown`，避免 CC 已失败而 gate 仍保留 Starting。
6. **C6：补齐 S2 sticky integration sequence**：明确 `onStartCommand → installedJob → launcher install → onBootstrapIdentity → gate callback` 的完整顺序；sticky 路径复用 retained attempt，不无条件创建 replacement；`installBootstrapAttempt` 改为返回 nullable attempt。

> **§v4.6**：附加修订：加入 final-check 与每个 external side effect 之间的 deterministic barrier 测试要求；记录 `Accepted` 从 `data object` 改为 `data class` 的非 source-compatible migration；row 5b 明确 `leaseStateRef == null`；并统一 §2.5 的“returns Refused”措辞。

### v3 的进展（rev-gpt 确认）

rev-gpt 明确确认"v3 is now architecturally directionally sound"：
- C1-C4（token stream 独立性 / 不委托 global SSE/reducer/supervisor / 保留 repository monitor）✅ RESOLVED
- C7（行数目标→架构质量目标）✅ RESOLVED
- S3 四组件分解 boundaries fundamentally sound ✅
- S4 先于 S2 的阶段排序 ✅
- 30s/45s deadline 区别保留 ✅

### v4 修订的 7 个 critical（全部是 S1/S4/S2 实现细节）

| # | v3 缺陷 | rev-gpt 证据 | v4 修订 |
|---|---|---|---|
| C1 | S1 linearization table 不 race-safe：CAS 后副作用在 connectMutex 外不总是安全（late outage gap 在 close 后 emit） | 详 §1.3 | S1 重写：stable leaseId + replaceable state + 分层 CAS |
| C2 | TransportLease 不能同时是 immutable CAS state + stable reference identity（每次 phase 转移新建实例，callback 捕获的 lease 变 stale） | 详 §1.2 | 拆成 TransportLeaseId（stable）+ TransportLeaseState（replaceable） |
| C3 | S1 不处理同步 shutdown（`cancelForShutdown` 从 onDestroy 调用，不能 acquire suspending Mutex） | 详 §1.4 | 新增同步 lease invalidation 路径 |
| C4 | S4 gate timer 和 launcher 各自超时竞争——gate 延迟时 launcher 返回 BootstrapFailed 但 gate 仍持有 Starting | 详 §2.4 | gate 成为唯一 deadline 权威；launcher 纯 await |
| C5 | S4 timer 实现不完整（StartingLease 无 deadline identity、leaseTimers 以 identity 为主键无法防 ABA、pendingTeardown 共享队列、synchronized 内 schedule 可能 inline 执行） | 详 §2.3 | timer 捕获 exact lease + 本地 teardown + lazy launch + exact-ref cleanup |
| C6 | S2 attach-after-supersession：attempt.job CAS null→job 只证明 local slot 空，不证明 attempt 仍 current | 详 §3.3 | 完整 installBootstrapAttempt 协议（5 点 currentAttempt check） |
| C7 | S2 未定义 sticky-start 合约（onBootstrapIdentity 的 NO_ATTEMPTPT_ID 若每次 install 新 attempt，retained callback 的 ===capturedAttempt 永久失效） | 详 §3.4 | sticky reuse 合约（三种选择，选 c） |

---

## 1. S1 — TransportLease 两层模型 + race-safe transitions

### 1.1 核心修正（rev-gpt C1/C2）

v3 用单个 `TransportLease` data class 既做 CAS state 又做 callback 引用——但每次 phase 转移创建新实例，callback 捕获的引用立即 stale。

v4 拆成两层（rev-gpt suggestion #1）：

```kotlin
// service/streaming/TransportLease.kt

/**
 * §v4 S1 (rev-gpt C2): STABLE identity captured by asynchronous callbacks.
 * Lives as long as one transport generation. Phase transitions do NOT
 * create a new TransportLeaseId — they replace only [TransportLeaseState].
 * Callbacks compare `state.leaseId === capturedLeaseId` to detect stale.
 *
 * §v4.4 rev-ds C1-hole#4: runtimeAttempt is NULLABLE — the Shutdown
 * tombstone installs a sentinel leaseId with null attempt (no runtime
 * state to track after permanent shutdown). All non-Shutdown phases
 * carry a non-null attempt.
 */
data class TransportLeaseId(
    val generation: Long,
    val identity: ConnectionIdentity?,
    val runtimeAttempt: TransportAttemptToken?,
)

/**
 * §v4 S1 (rev-gpt C2): REPLACEABLE phase + outage metadata. CAS target.
 * Every phase transition creates a new TransportLeaseState with the SAME
 * leaseId but a different phase variant.
 */
sealed interface TransportLeaseState {
    val leaseId: TransportLeaseId
    /** §v4.6 C1: default false; side-effect phases override via CAS. */
    val sideEffectsClaimed: Boolean get() = false

    /** Connect accepted, awaiting first valid frame. runtimeAttempt is set. */
    data class Connecting(
        override val leaseId: TransportLeaseId,
        /** §v4.6 C1: CAS ownership for frame publication/readiness effects. */
        override val sideEffectsClaimed: Boolean = false,
    ) : TransportLeaseState

    /** First valid frame received, streaming live. gapEmitted reset. */
    data class Live(
        override val leaseId: TransportLeaseId,
        /** §v4.6 C1: CAS ownership for frame publication/readiness effects. */
        override val sideEffectsClaimed: Boolean = false,
    ) : TransportLeaseState

    /** Transient collection failure, retrying within same generation. */
    data class Retrying(
        override val leaseId: TransportLeaseId,
        val attempt: Int,
        val gapEmitted: Boolean,  // folded into phase (rev-gpt concern #1/#2)
        /** §v4.6 C1: CAS ownership for gap/drop effects. */
        override val sideEffectsClaimed: Boolean = false,
    ) : TransportLeaseState

    /** Intentional disconnect in progress. */
    data class Closing(
        override val leaseId: TransportLeaseId,
        /** §v4.6 C1: close may itself claim a markGap emission. */
        override val sideEffectsClaimed: Boolean = false,
    ) : TransportLeaseState

    /** Retry budget exhausted, generation terminal. */
    data class Exhausted(
        override val leaseId: TransportLeaseId,
        val reported: Boolean,  // folded into phase (rev-gpt concern #1)
        /** §v4.6 C1: CAS ownership for terminal effects. */
        override val sideEffectsClaimed: Boolean = false,
    ) : TransportLeaseState

    /**
     * §v4.3 rev-gpt C2: permanent shutdown tombstone. Once installed, NO
     * subsequent connect can install a lease — connect CAS checks for this
     * phase and rejects. Distinguished from Closing (per-connection
     * intentional disconnect that allows re-connect) by being permanent
     * (owner-level Service shutdown from onDestroy).
     */
    data class Shutdown(
        override val leaseId: TransportLeaseId,
        /** Preserve an already granted claim while installing the tombstone. */
        override val sideEffectsClaimed: Boolean = false,
    ) : TransportLeaseState
}
```

**关键改动**：
- `gapEmitted` 和 `exhaustedReported` 从独立的 `OutageState` 字段折叠进 phase variant（rev-gpt concern #1："OutageState 独立于 phase 允许 Connecting+gapEmitted=true 等无效组合"）
- 回调捕获 `TransportLeaseId`（stable），phase 转移只替换 `TransportLeaseState`——回调检查 `state.leaseId === capturedLeaseId` 不受 phase 变化影响
- `Connecting` 携带 `runtimeAttempt`（非 null——rev-gpt C1："runtimeAttempt=null in Connecting permits a state that cannot safely launch a collector"）

### 1.2 AtomicReference 持有 TransportLeaseState

```kotlin
private val leaseStateRef = AtomicReference<TransportLeaseState?>(null)
```

CAS 操作：`leaseStateRef.compareAndSet(expectedState, newState)`。成功 = phase 转移生效。失败 = 其他线程已更新 = 放弃（不重试副作用）。**§v4.6 C1**：所有会在锁外 suspend 或调用外部 callback 的路径还必须通过同一 CAS 将 `sideEffectsClaimed` 从 `false` 改为 `true`；该 CAS 才是副作用 ownership 的线性化点。

### 1.3 Linearization points（v4 race-safe 修订）

rev-gpt C1 指出 v3 的"CAS metadata under connectMutex, then side effects outside lock"太宽泛。v4 区分：
- **非 suspending runtime 操作**（`runtimeStore.beginAttempt/markStopped/rollbackAttempt`）——可留在 connect 事务内（rev-gpt C1："Non-suspending runtime operations may need to remain within the serialized connect transaction"）
- **suspending 操作**（`emitGapOnce`、`job.cancelAndJoin`、`routeUnexpectedDrop`）——必须在锁外

§v4.1 rev-ds C2 修订：原 table 的 row 1 `null → Connecting` 漏了 **connect-while-lease-live** 场景（后台重连拒绝后 lease 仍 Live/Retrying，前台返回重入 connect——当前代码 :475-484 的 `rollbackAttempt`-vs-`markStopped` 分支）。row 5 的 `markStopped` 也过于笼统，需区分 **transport-timeout rollback**（I4：保留 recovery ticket）、**intentional disconnect markStopped**（I6）、**identity-dependent supersession**（同 identity rollback / 不同 identity markStopped）。

| # | 转移 | 线性化步骤 | 锁 |
|---|---|---|---|
| 1a | accepted connect (slot empty) | (a) `runtimeStore.beginAttempt()` → (b) 构造 `Connecting(leaseId)` → (c) CAS `null → Connecting` → (d) 若 CAS 失败：`runtimeStore.rollbackAttempt()` + 放弃 | (a)(b) 无锁；(c) CAS；(d) rollback 在 CAS 失败分支 |
| 1b | accepted connect (slot live, same identity — reconnect) | **§v4.6 C3：严格按实际 runtime API 顺序**：(a) `synchronized(dropHandler)`；(b) 对旧 attempt 调 `runtimeStore.rollbackAttempt(oldAttempt)`；(c) 在同一 monitor 内调用 `runtimeStore.beginAttempt(newIdentity)`；(d) 以 `leaseStateRef.getAndUpdate { current -> if (current is Shutdown) current else if (current === expectedOldState) Connecting(newLeaseId) else current }` 安装新 lease；(e) 返回 `Shutdown`、返回值不是 `expectedOldState`、或 post-update re-check 为 `Shutdown` 时，在 monitor 内 `rollbackAttempt(newAttempt)`，锁外 abort。实际 `beginAttempt` (:168) 会拒绝已有 Connecting/Live/Retrying，因此不能先 begin 新 attempt。 | `synchronized(dropHandler)` + `getAndUpdate` + connectMutex |
| 1c | accepted connect (slot live, different identity — supersession) | **§v4.6 C3：与 1b 同一事务顺序**：(a) `synchronized(dropHandler)`；(b) 对旧 attempt 调 `runtimeStore.markStopped(oldAttempt)`；(c) 调 `runtimeStore.beginAttempt(newIdentity)`；(d) 用 `getAndUpdate { current -> if (current is Shutdown) current else if (current === expectedOldState) Connecting(newLeaseId) else current }` 安装；(e) 返回 `Shutdown`、返回值不是 `expectedOldState`、或 post-update re-check 看到 `Shutdown` 时 `rollbackAttempt(newAttempt)` + abort。旧 runtime 必须先 terminalize，否则实际 `beginAttempt` (:168) 会拒绝。 | `synchronized(dropHandler)` + `getAndUpdate` + connectMutex |
| 2 | first valid frame | **§v4.6 C2**：`runtimeStore.markLive(attempt)` 是首要门槛；false 时不 CAS、不 publish、不 complete readiness。成功后 CAS `Connecting → Live(sideEffectsClaimed=false)`；在任何副作用前再检查 `leaseStateRef.get() is Shutdown`，若是则抑制 event publication、readiness completion、resync 与 gap reset。只有成功 CAS `Live(false) → Live(true)` 的路径才能执行这些副作用；`markLive` 成功不等于 lease 仍然存活。 | (a)(b) `connectMutex` + `synchronized(dropHandler)`；claim CAS + 外部副作用在锁外 |
| 3a | outage from Live | **§v4.6 C1**：`markRetrying` 后 CAS `Live → Retrying(attempt=1, gapEmitted=false, sideEffectsClaimed=false)`；再 CAS `Retrying(false) → Retrying(true, sideEffectsClaimed=true)`。后一个 CAS 的 winner 获得 gap/UI side-effect ownership，执行 `emitGapOnce()`；不得用 exact-state read 代替 claim。close/shutdown 看到 `sideEffectsClaimed=true` 时等待该路径完成或变 no-op，不另发 gap。 | (a)(b) `connectMutex`+`synchronized(dropHandler)`；claim CAS 后锁外 suspend |
| 3b | collection failure from Connecting (never-Live) | **§v4.6 C1**：即使 `markRetrying` 返回 false，只要 runtime 仍 canonical Connecting，就 CAS `Connecting → Retrying(attempt=0, gapEmitted=false, sideEffectsClaimed=false)`，随后 CAS `Retrying(false) → Retrying(true, sideEffectsClaimed=true)`；只有 claim winner 执行 gap + UI error。 | (a)(b) `connectMutex`+`synchronized(dropHandler)`；claim CAS 后锁外 suspend |
| 4 | recovered frame | §v4.3 rev-gpt C3：补 `runtimeStore.markLive`。(a) `synchronized(dropHandler)` 内 `runtimeStore.markLive(leaseId.runtimeAttempt)` → (b) 若 markLive 返回 false（stale token）：放弃 → (c) 若成功：CAS `Retrying(*, gapEmitted=*) → Live`（gapEmitted 重置——rev-gpt concern #2） | (a) `synchronized(dropHandler)`；(b) 无副作用；(c) CAS |
| 5a | transport timeout (30s, first-frame) | §v4.1 rev-ds concern：`rollbackAttempt`（I4，不是 markStopped）。(a) CAS `Connecting → Closing` → (b) `runtimeStore.rollbackAttempt(leaseId.runtimeAttempt)` + complete `pendingReadiness` with TransportTimeout → (c) cancel sseJob/transportTimeoutJob | (a) CAS；(b) connectMutex 内（rollbackAttempt 非 suspending）；(c) 锁外 |
| 5b | intentional disconnect (user/system, markGap=true) | **§v4.6 C1/C2**：若 `leaseStateRef.get()` 为 null，不能构造 `Closing(current.leaseId)`，因此 close 直接跳过 lease close/side effects（仍可执行无 lease 的 job cleanup）。否则用 wildcard CAS；`Shutdown` 永不被超越。对非 Shutdown current，只有成功的 `* → Closing(sideEffectsClaimed=false)` 才继续；若 current 的 `sideEffectsClaimed=true`，close 不抢 claim，等待 claim owner 或变 no-op。随后由 gap claim CAS（例如 `Closing(false) → Closing(true)`）获得 `emitGapOnce(markGap=true)` ownership；若 getAndUpdate/CAS 后或 claim 前后看到 `Shutdown`，不 emit spurious gap。`markStopped(runtimeAttempt)` 与 cancel job 仍按 I6/锁序执行。 | lease CAS + claim CAS；`synchronized(dropHandler)`；suspend emit/cancel 在锁外 |
| 6 | exhaustion | **§v4.6 C1**：(a) CAS `Retrying → Exhausted(reported=false, sideEffectsClaimed=false)`；(b) CAS `Exhausted(false) → Exhausted(reported=true, sideEffectsClaimed=true)`，一次性 claim drop routing、readiness terminalization 与 `onTerminalExhaustion` 的全部外部副作用；(c) 只有 claim winner 执行 `routeUnexpectedDrop` + `onTerminalExhaustion`，其他线程不重试。 | (a)(b) CAS；(c) 锁外 |
| 7 | resync scheduling | 不改 leaseStateRef——`resyncInFlight` CAS under `resyncMutex`（独立子状态，可在一个 generation 内循环多次） | `resyncMutex` |
| 8 | shutdown（同步） | 见 §1.4 | 同步 invalidation |

**关键规则**：

- **§v4.6 C1（唯一完整 race-free 方案）**：废弃 post-CAS exact-state read。`leaseId === capturedLeaseId` 和 `leaseStateRef.get() === exactClaimedState` 都不能覆盖“检查后、suspending emission 前 close/shutdown 安装新 phase”的窗口。每个需要外部副作用的 phase variant 携带 `sideEffectsClaimed`；副作用路径必须通过 CAS 把它从 `false` 改成 `true`，CAS winner 才有权执行后续副作用。intentional close（row 5b）和 shutdown 安装 `Closing`/`Shutdown` 时检查该标志：若已为 true，不得清除或重抢副作用 ownership；应等待 claim owner 的路径完成，或将 close 变成 no-op，由 claim owner 自然 terminalize。这个协议适用于 `emitGapOnce`（rows 3a/3b/5b）、frame publication/readiness（row 2）、`routeUnexpectedDrop`/`onTerminalExhaustion`（row 6）以及任何新增的 suspend/callback side effect。
  ```kotlin
  // §v4.6 C1: the CAS, not a later read, claims the suspending side effect.
  suspend fun claimAndEmitGap(): Boolean {
      val retrying = leaseStateRef.get() as? TransportLeaseState.Retrying
          ?: return false
      val claimed = retrying.copy(
          gapEmitted = true,
          sideEffectsClaimed = true,
      )
      if (retrying.sideEffectsClaimed ||
          !leaseStateRef.compareAndSet(retrying, claimed)
      ) return false
      // No exact-state re-read here. Closing/shutdown must respect this claim.
      emitGapOnce()
      return true
  }
  ```

- §v4.2 rev-glm S2（三重锁分层）：当前代码嵌套 `connectMutex`（suspend Mutex）→ `synchronized(dropHandler)` → `SseTransportRuntimeStore.lock`（:168/:211/:230/:304/:336）。运行时变更必须保持在 `synchronized(dropHandler)` 下（保留 I3 排序：在发布 drop 之前释放所有权）。锁序：`connectMutex` → `dropHandler` → `runtimeStore.lock`（从不反向）。**§v4.3 rev-gpt C3**：转移表每行的锁列统一标注三层（之前部分行只标了"CAS"或"connectMutex"）。
- 非 suspending runtime 操作（`beginAttempt/markStopped/markLive/markRetrying/rollbackAttempt`）在 `synchronized(dropHandler)` 下执行（§v4.3 rev-gpt C3：这些是 runtime 权威变更，不是可选步骤）
- suspending/callback 操作（`emitGapOnce`/`cancelAndJoin`/frame publication/readiness/`routeUnexpectedDrop`/`onTerminalExhaustion`）在 side-effect claim CAS 成功后、锁外执行；**§v4.6 C1**：禁止以 final state read 作为 claim。
- CAS 失败 = 不重试（幂等性保证：只有第一个成功的 CAS 触发副作用）
- §v4.2 rev-glm S4（attempt=0 约定）：row 3b 的 `Retrying(attempt=0)` 用于 never-Live Connecting failure（与 Live-后 outage 的 `attempt=1` 区分）。实现者不应将 attempt=0 与重连计数器混淆——它是"从未达到 Live"的标记值。
- §v4.3 rev-gpt C2（Shutdown tombstone）：`TransportLeaseState.Shutdown` 是**永久终态**——`connect` 的 CAS 检查如果当前 state 是 `Shutdown`，拒绝安装（不 CAS `Shutdown → Connecting`）。只有 Service 重新创建（新实例，新 `leaseStateRef`）才能恢复。`cancelForShutdown()` 安装 Shutdown tombstone（见 §1.4 修订）。

### 1.4 同步 shutdown 路径（rev-gpt C3）

`cancelForShutdown()` 从 `Service.onDestroy()` 调用——同步，不能 acquire suspending `connectMutex`。v4 新增**同步 lease invalidation**。

§v4.3 rev-gpt C2 修订：原 v4.2 用 `Closing` 作为 shutdown invalidation——但 `Closing` 是**可重连的**（connect CAS `Closing → Connecting` 可成功）。这无法阻止 shutdown 后的 connect 安装新 lease。**修正**：使用 `Shutdown` tombstone（永久终态，不可超越）。

```kotlin
/**
 * §v4 S1 (rev-gpt C3) + §v4.3 rev-gpt C2: synchronous shutdown path.
 *
 * Steps:
 * 1. Atomically install Shutdown tombstone (CAS * → Shutdown), but never steal
 *    an already-true sideEffectsClaimed (§v4.6 C1).
 *    — Shutdown is PERMANENT: connect sees it and rejects (never CAS Shutdown → Connecting)
 * 2. Stamp newer generation into SharedStateStore (monotonic CAS)
 * 3. Terminalize the captured runtime attempt (markStopped)
 * 4. Cancel captured jobs idempotently (Job.cancel is non-suspending)
 * 5. Any later collector callback is rejected by the sideEffectsClaimed
 *    protocol (§v4.6 C1)
 * 6. Any later connect sees Shutdown and rejects (§v4.3 C2)
 */
fun cancelForShutdown() {
    // §v4.4 rev-ds C1 (suggestion #1) + §v4.6 C1: SINGLE atomic install.
    // getAndUpdate ALWAYS applies the function — unconditionally installs
    // Shutdown regardless of current state (null, Connecting, Live, Retrying,
    // Closing, Exhausted, even already-Shutdown). No two-step logic, no
    // `?: return`, no `currentIdentity` dependency. The sentinel leaseId
    // (generation=0, identity=null, attempt=null) is used when there was no
    // prior lease — connect checks for Shutdown PHASE before reading leaseId.
    val SHUTDOWN_SENTINEL = TransportLeaseId(0, null, null)
    val captured = leaseStateRef.getAndUpdate { current ->
        when {
            current == null -> TransportLeaseState.Shutdown(SHUTDOWN_SENTINEL)
            current.sideEffectsClaimed ->
                TransportLeaseState.Shutdown(current.leaseId, sideEffectsClaimed = true)
            else -> TransportLeaseState.Shutdown(current.leaseId)
        }
    }

    // No early return — even if captured was null, the tombstone is now installed.
    // Cleanup only runs if there WAS an active lease:
    if (captured != null && captured !is TransportLeaseState.Shutdown &&
        !captured.sideEffectsClaimed
    ) {
        // Step 2: stamp sseConnected=false with shutdown generation + 1
        setSseConnected(false, captured.leaseId.generation + 1)

        // Step 3: terminalize runtime attempt (markStopped is synchronized on
        // dropHandler — non-suspending, safe from onDestroy's main thread)
        synchronized(dropHandler) {
            captured.leaseId.runtimeAttempt?.let { runtimeStore.markStopped(it) }
        }
    }

    // §v4.5 rev-glm S3: defensive unconditional cleanup. cancelForShutdown is
    // the terminal cleanup path — activeAttempt must be cleared regardless of
    // whether captured was non-null (a prior failed CAS could have left a
    // stale activeAttempt without an installed lease).
    synchronized(dropHandler) {
        activeAttempt = null
    }

    // Step 4: cancel jobs idempotently (Job.cancel is non-suspending)
    sseJob?.cancel()
    transportTimeoutJob?.cancel()
    pendingReadiness?.cancel()
    sseJob = null
    transportTimeoutJob = null
    pendingReadiness = null

    // Steps 5-6: enforced by the Shutdown phase + sideEffectsClaimed protocol
    // (§v4.6 C1); connect checks for Shutdown phase (§v4.4 below).
}

// §v4.3 rev-gpt C2: connect rejects Shutdown tombstone
fun connect(identity: ConnectionIdentity): ConnectResult {
    // BEFORE beginAttempt, check for Shutdown tombstone:
    val currentState = leaseStateRef.get()
    if (currentState is TransportLeaseState.Shutdown) {
        // Permanent shutdown — reject. Only a new Service instance (new
        // leaseStateRef) can recover.
        return ConnectResult.Rejected.Shutdown
    }
    // ... proceed with normal connect flow (beginAttempt, CAS, etc.)
    // BUT: after CAS succeeds, re-check for Shutdown (race: shutdown installed
    // between the initial check and the CAS):
    if (leaseStateRef.get() is TransportLeaseState.Shutdown) {
        // Lost the race — rollback our beginAttempt and reject
        runtimeStore.rollbackAttempt(newAttempt)
        return ConnectResult.Rejected.Shutdown
    }
}
```

**测试要求**（rev-gpt C3 + §v4.3 C2 补充）：
- shutdown racing first frame / collection failure / resync scheduling / exhaustion
- **shutdown racing accepted connect**（§v4.3 C2 核心）：beginAttempt → shutdown → connect CAS must fail (Shutdown tombstone)
- **shutdown → late connect**：shutdown 先安装 Shutdown → 随后 connect 必须拒绝
- **outage claimed → intentional close → no late gap/UI**（§v4.6 C1 核心）
- **exhaustion claimed → intentional close → no late drop/terminal**（§v4.6 C1 核心）
- **recovered frame × disconnect**
- **never-Live failure × timeout/replacement**
- **row 5b intentional disconnect racing cancelForShutdown**（§v4.5 rev-glm S5/C1）：disconnect 的 `getAndUpdate` 返回 `Live` 后、`emitGapOnce` 前，shutdown 安装 Shutdown → row 5b 的 post-getAndUpdate re-check 必须跳过 `emitGapOnce`（不 emit spurious gap after permanent shutdown）
- **§v4.6 C1 deterministic barriers**：在 final-state validation/claim 后、每一个 external side effect 前设置 barrier（`CountDownLatch` 或等价 test hook）；barrier 被释放前并发 close/shutdown/replacement，验证 claim protocol 不产生 late gap、event、readiness、drop 或 terminal callback。

---

## 2. S4 — Gate 作为唯一 Stage-2 deadline 权威

### 2.1 核心修正（rev-gpt C4）

v3 的 launcher 仍保留 `withTimeoutOrNull(45s + 1s)`——如果 gate timer 因 scheduler 饥饿延迟，launcher 返回 BootstrapFailed 但 gate 仍持有 Starting owner。rev-gpt C4："That recreates the ownership/result divergence S4 is supposed to eliminate."

v4 选择 rev-gpt suggestion #3 的第一个选项：**gate 是唯一 deadline 权威，launcher 纯 await**。

### 2.2 Stage2DeadlineScheduler 抽象（rev-gpt suggestion #3/concern #6）

**§v4.6 C4**：scheduler 接收 suspend callback；gate 额外持有一个 gate-owned `SupervisorJob` child scope。该 scope 跨越单次 `markReady`/`failStarting` 调用，只有 gate shutdown 才取消。为保留现有 `StreamingOwnershipGate()` 测试构造，scheduler 与 scope 都提供 test default。

```kotlin
// service/Stage2DeadlineScheduler.kt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * §v4 S4 (rev-gpt concern #6) + §v4.6 C4: monotonic deadline scheduler abstraction for
 * the ownership gate's Stage-2 lease expiry. NOT a broad application scope —
 * a narrow testable timer facility.
 *
 * Production impl: launches in the gate-owned scope supplied alongside this
 * scheduler, with a monotonic delay (System.nanoTime based, immune to wall-clock
 * jumps). **§v4.6 C4**: the DI binding must supply the SAME gate-owned scope to
 * both objects; an independently owned scheduler scope would violate shutdown
 * cancellation ownership.
 * Test impl: virtual time via TestScope.
 *
 * §v4.2 rev-glm S3 + §v4.6 C4: the scheduler's dispatcher MUST NOT be
 * Dispatchers.Main.immediate — even CoroutineStart.LAZY can inline-execute
 * on it inside synchronized(lock). Use Dispatchers.Default or
 * Dispatchers.Main (non-immediate) to guarantee onExpiry never runs
 * under the gate lock.
 */
interface Stage2DeadlineScheduler {
    /**
     * §v4.6 C4: [onExpiry] is suspend because OwnershipState.Starting.runTeardown
     * is suspend. The callback is invoked on the injected scheduler scope and
     * is never run inline under the gate monitor.
     */
    fun schedule(delayMs: Long, onExpiry: suspend () -> Unit): Cancellable
}

fun interface Cancellable { fun cancel() }

/**
 * §v4.6 C4: production adapter. [scope] is supplied alongside the scheduler;
 * use Dispatchers.Default (or non-immediate Main), never Main.immediate.
 */
class CoroutineStage2DeadlineScheduler(
    // §v4.6 C4: this is the gate-owned scope, not an application-global scope.
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val monotonicNowNanos: () -> Long = { System.nanoTime() },
) : Stage2DeadlineScheduler {
    override fun schedule(delayMs: Long, onExpiry: suspend () -> Unit): Cancellable {
        val job = scope.launch {
            val deadline = monotonicNowNanos() + delayMs * 1_000_000L
            val remainingNanos = deadline - monotonicNowNanos()
            kotlinx.coroutines.delay((remainingNanos / 1_000_000L).coerceAtLeast(0L))
            onExpiry()
        }
        return Cancellable { job.cancel() }
    }
}
```

### 2.3 StartingLease 完整设计（rev-gpt C5 修订 + rev-ds C1 修订）

```kotlin
// OwnershipModels.kt

/**
 * §v4 S4 (rev-gpt C5) + §v4.1 rev-ds C1: a Starting owner's lifecycle lease,
 * owned by the gate. Carries its own terminal deferred so the gate timer can
 * self-reap via TERMINAL REFERENCE MATCHING (not identity+attemptId — that
 * leaves sticky NO_ATTEMPT_ID owners vulnerable to same-identity ABA).
 *
 * The gate schedules ONE timer per lease (validated attempts only — see §2.4).
 * On timeout:
 * 1. acquire gate lock
 * 2. confirm current.owner is Starting AND current.terminal === lease.terminal
 *    (TERMINAL REFERENCE — rev-ds C1: codebase's own failStartingIfTerminal
 *    pattern at :596-611. Identity+attemptId matching is INSUFFICIENT for
 *    sticky leases where attemptId=NO_ATTEMPT_ID for all.)
 * 3. transition owner to null
 * 4. complete terminal with BootstrapFailed
 * 5. extract this lease's callbacks (LOCAL, not shared queue — rev-gpt C5)
 * 6. release lock
 * 7. run THIS lease's teardown outside lock
 *
 * markReady / failStarting / expireAttempt / disconnectAndRelease /
 * releaseNow / replacement cancels the timer (terminal-ref cleanup).
 *
 * §v4.1 rev-ds suggestion #2: timer is scheduled ONLY for validated attempts
 * (attemptId != NO_ATTEMPT_ID). Sticky/bootstrap-internal owners (TOFU pending,
 * controller-internal) have NO deadline — same as today's behavior. This
 * eliminates the sticky ABA surface entirely AND prevents reaping a bootstrap
 * stalled on the TOFU trust prompt (a legitimate multi-minute user decision).
 */
data class StartingLease(
    val attemptId: Long,
    val identity: ConnectionIdentity,
    val terminal: CompletableDeferred<OwnershipStartResult>,
    val disconnectAndJoin: suspend (Boolean) -> Unit,
    val abortStartup: () -> Unit,
)
```

**v3 → v4 关键改动**：
- StartingLease **不需要包含 deadline/timer identity**（rev-gpt C5 说"does not actually contain a deadline"——但 timer 在 gate 侧捕获 exact lease 引用，不需要 lease 自己持有 deadline）
- **`leaseTimers` 以 terminal 引用为主键**（§v4.1 rev-ds C1：不是 identity+attemptId——sticky 的 attemptId=NO_ATTEMPT_ID 使该键退化为 identity-only，ABA 可达。改为 `IdentityHashMap<CompletableDeferred<*>, Cancellable>` 或以 StartingLease 对象引用为主键）
- **timer 保留 local extracted lease**，在锁外调它的 `runTeardown`——不用共享 `pendingTeardown` 队列（rev-gpt C5）
- **lazy launch**：schedule 在 synchronized 内注册，但 timer 的 `onExpiry` lambda 用 lazy launch（rev-gpt C5）
- **§v4.1 rev-ds C1+suggestion #2**：timer 只给 `attemptId != NO_ATTEMPT_ID`（validated launcher attempts）；sticky 无 deadline（与今天一致）

### 2.4 Gate timer 实现

```kotlin
class StreamingOwnershipGate(
    /** §v4.6 C4: gate-owned child scope survives individual callbacks. */
    private val deadlineScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        ),
    private val deadlineScheduler: Stage2DeadlineScheduler = object : Stage2DeadlineScheduler {
        override fun schedule(delayMs: Long, onExpiry: suspend () -> Unit): Cancellable =
            Cancellable {}
    },  // §v4.6 C4 test default; DI supplies production adapter
    // ... 现有字段
) {
    /** §v4.6 C4: cancel this scope from the gate's shutdown path. */
    fun shutdown() {
        val timers = synchronized(lock) {
            val captured = leaseTimers.values.toList()
            leaseTimers.clear()
            captured
        }
        deadlineScope.cancel()
        timers.forEach { it.cancel() }
    }

    companion object {
        /** §v4.6 C4: preserves zero-argument test constructions. */
        fun testDefaultScheduler(): Stage2DeadlineScheduler =
            object : Stage2DeadlineScheduler {
                override fun schedule(delayMs: Long, onExpiry: suspend () -> Unit): Cancellable =
                    Cancellable {}
            }
    }

    /** §v4.6 C6: synchronized retained-owner probe used by sticky integration. */
    fun hasOwner(identity: ConnectionIdentity): Boolean = synchronized(lock) {
        owner?.identity == identity
    }
    // §v4 S4 + §v4.1 rev-ds C1: timer registry keyed by terminal DEFERRED
    // reference (not identity+attemptId — that fails for sticky NO_ATTEMPT_ID).
    // Only validated attempts (attemptId != NO_ATTEMPT_ID) get a timer.
    private val leaseTimers = mutableMapOf<CompletableDeferred<OwnershipStartResult>, Cancellable>()

    fun registerStarting(...): RegisterStartingOutcome = synchronized(lock) {
        // ... 现有逻辑
        // §v4.2 rev-glm C3 + §v4.4 rev-ds concern + §v4.5 rev-glm C2:
        // 区分"新 owner 创建"和"幂等 Accepted"。
        // 当前代码 :293-301：对于已拥有 Starting owner 的 identity，
        // registerStarting 返回 Accepted 但不替换 owner（幂等）。
        // 只有真正创建/替换 owner 时才创建 lease + schedule timer。
        //
        // §v4.5 rev-glm C2 + §v4.6 migration note API 变更声明：
        // 当前 RegisterStartingOutcome.Accepted 是 `data object`（无字段）。
        // v4.5 改为 `data class Accepted(val ownerReplaced: Boolean)`：
        //   - registerStarting :300（幂等路径，owner 已存在）：return Accepted(ownerReplaced=false)
        //   - registerStarting :341-347（新建/替换路径）：return Accepted(ownerReplaced=true)
        // `Accepted` 从 `data object` 改为 `data class` **不是 source-compatible**：
        // 所有 `outcome == RegisterStartingOutcome.Accepted` 必须迁移为
        // `outcome is RegisterStartingOutcome.Accepted`；所有 when 分支也必须
        // 使用 `RegisterStartingOutcome.Accepted(...)` 类型匹配，并读取
        // `ownerReplaced`。这包括 StreamingOwnershipGate.kt :376-379 等旧调用点。
        // §v4.6 migration: target declaration is
        // `data class Accepted(val ownerReplaced: Boolean) : RegisterStartingOutcome`.
        // All old Accepted equality/when-object matches use
        // the data-class type and read ownerReplaced explicitly.
        val ownerWasCreated = (outcome is RegisterStartingOutcome.Accepted && outcome.ownerReplaced)
        if (ownerWasCreated) {
            val lease = StartingLease(
                attemptId = attemptId,
                identity = identity,
                terminal = starting.terminal,
                disconnectAndJoin = starting.disconnectAndJoin,
                abortStartup = starting.abortStartup,
            )
            // §v4.1 rev-ds C1+suggestion #2: schedule timer ONLY for validated
            // launcher attempts. Sticky (NO_ATTEMPT_ID) has no deadline — same
            // as today. This eliminates the sticky-ABA surface AND prevents
            // reaping a bootstrap stalled on the TOFU trust prompt.
            if (attemptId != StreamingOwnershipGate.NO_ATTEMPT_ID) {
                scheduleLeaseExpiry(lease)
            }
        }
        // 幂等 Accepted（owner 已存在）：不创建 lease，不 schedule timer，
        // 原始 timer 继续运行（如果有）。这与 §3.3 sticky reuse 合约一致。
    }

    private fun scheduleLeaseExpiry(lease: StartingLease) {
        // Key by terminal reference — exact match in onLeaseExpiry
        leaseTimers[lease.terminal]?.cancel()
        leaseTimers[lease.terminal] = deadlineScheduler.schedule(STAGE2_TIMEOUT_MS) {
            onLeaseExpiry(lease)  // captures exact lease — LOCAL, not queue
        }
    }

    private suspend fun onLeaseExpiry(lease: StartingLease) {
        // §v4.3 rev-gpt concern: ALL deferred completions OUTSIDE the gate lock
        // (current code :493-509 deliberately completes outside to avoid running
        // launcher's Main.immediate continuation inline under the monitor).
        // Extract handles under lock, complete outside.
        data class ExpiryHandles(
            val terminal: CompletableDeferred<OwnershipStartResult>,
            val waiters: List<CompletableDeferred<OwnershipStartResult>>,
            val teardown: OwnershipState.Starting,
        )
        val handles: ExpiryHandles? = synchronized(lock) {
            val current = owner
            if (current !is Starting) return@synchronized null
            // §v4.1 rev-ds C1: TERMINAL REFERENCE MATCH — not identity+attemptId.
            // This is the codebase's own failStartingIfTerminal pattern (:596-611).
            if (current.terminal !== lease.terminal) return@synchronized null
            // terminal-ref match — reap
            owner = null
            leaseTimers.remove(lease.terminal)
            val capturedWaiters = waiters.remove(current.identity) ?: emptyList()
            // §v4.3 rev-gpt concern: extract handles for OUTSIDE-LOCK completion.
            // Do NOT complete terminal/waiters here — Main.immediate continuation
            // must not run under the gate monitor.
            ExpiryHandles(current.terminal, capturedWaiters, current)
        }
        if (handles == null) return
        // §v4.3 rev-gpt concern: complete OUTSIDE the lock
        handles.terminal.complete(OwnershipStartResult.Refused(OwnershipRefusal.BootstrapFailed))
        handles.waiters.forEach {
            it.complete(OwnershipStartResult.Refused(OwnershipRefusal.BootstrapFailed))
        }
        // §v4 S4 (rev-gpt C5): LOCAL lease teardown, not shared queue
        // §v4.3 rev-gpt concern: also outside the lock
        // Note: handles.teardown is OwnershipState.Starting (gate's canonical owner type);
        // runTeardown is defined on OwnershipState.Starting (OwnershipModels.kt).
        // StartingLease wraps the same callbacks — use the canonical owner for teardown.
        handles.teardown.runTeardown(markGap = false)
    }

    fun markReady(identity: ConnectionIdentity) = synchronized(lock) {
        val current = owner
        if (current is Starting && current.identity == identity) {
            owner = Ready(current)
            // §v4.1 rev-ds concern #4: complete terminal OUTSIDE the lock
            // (current code :493-509 deliberately completes outside to avoid
            // running launcher's Main.immediate continuation inline under the
            // monitor). Collect terminal-ref for outside-lock completion.
            val terminalToComplete = current.terminal
            val timerToCancel = leaseTimers.remove(current.terminal)
            // Release lock scope here — complete + cancel outside
            // (In practice: extract current, return, then complete outside
            //  the synchronized block — same shape as existing markReady.)
        }
        // ... 现有 waiter 通知
    }
    // markReady 的 terminal.complete() + timer.cancel() 在 synchronized 块外执行
    // (§v4.1 rev-ds concern #4: 保持现有 outside-lock completion 纪律)

    // failStarting / expireAttempt / disconnectAndRelease / releaseNow:
    // 同样在 synchronized 块内 leaseTimers.remove(terminal)?.cancel()
    // 覆盖所有 owner-clearing 路径（rev-gpt C5）
}
```

### 2.5 Launcher 简化（rev-gpt C4：gate 是唯一 deadline 权威）

```kotlin
// StreamingServiceLauncher.kt — Stage-2 路径

// BEFORE (v3): withTimeoutOrNull(45s + 1s) { attempt.terminal.await() }
// AFTER (v4): gate 是唯一 deadline 权威。launcher 纯 await。
// 不再有独立超时——gate timer 保证 terminal 最终完成（Ready 或 BootstrapFailed）。
// 1s settle grace 不再需要——§v4.3 rev-gpt concern 修订：gate timer 的
// onLeaseExpiry 在锁内 REVALIDATE（terminal-ref match），在锁外 complete。
// markReady 同样在锁外 complete（保持现有 :493-509 纪律）。
// 因此不存在"锁内 complete 导致 Main.immediate continuation 在 monitor 内恢复"
// 的窗口——grace 的原始理由已消除。

// 但保留一个 defensive outer bound（防 gate timer bug 导致永久挂起）：
// 远大于 45s（如 60s）。§v4.5 rev-glm C3：保持现有 graceful degradation
// 合约——CC 的 ensureStarted 调用者（ConnectionCoordinator/HealthProbe）
// 围绕 OwnershipStartResult sealed type 编写，不 catch IllegalStateException。
// §v4.6 C5：不得 throw（会 crash app），必须返回 Refused(BootstrapFailed)：
// CC 写 SseBootstrapFailed phase（横幅"实时更新中断"+ 刷新按钮），REST 仍工作。
// 附带 DebugLog.w 告警（可观测但不 crash）：
val terminal = withTimeoutOrNull(60_000L) { attempt.terminal.await() }
if (terminal == null) {
    DebugLog.w(TAG, "ensureStarted: gate timer failed to settle terminal within 60s (bug) → graceful BootstrapFailed")
    // §v4.6 C5: the fallback must reconcile the gate before returning. The
    // terminal reference prevents same-identity ABA from reaping a replacement.
    val extracted = ownershipGate.failStartingIfTerminal(
        identity = identity,
        expectedTerminal = attempt.terminal,
        reason = OwnershipRefusal.BootstrapFailed,
    )
    extracted?.runTeardown(markGap = false)
    return OwnershipStartResult.Refused(OwnershipRefusal.BootstrapFailed)
}
return terminal
```

**关键**：正常 Stage-2 结果仍由 gate timer 完成；60s 只是 defensive fallback。**§v4.6 C5**：fallback 不抛异常，且在返回 `Refused(BootstrapFailed)` 前必须 terminal-reference reap 自己的 Starting owner，保证 CC 的结果与 gate owner 状态一致。

### 2.6 Timer cancellation matrix（rev-gpt C5 要求）

| 事件 | 取消 timer？ | 理由 |
|---|---|---|
| `markReady(identity)` | ✅ `leaseTimers.remove(current.terminal)?.cancel()`（§v4.1 terminal-ref 主键） | owner 提升到 Ready |
| `expireAttempt(attemptId)` after Stage-1 | ✅ | attempt 已过期 |
| `failStarting(identity)` | ✅ | owner 失败 |
| `failStartingIfTerminal(identity, terminal)` | ✅ | owner 被终端引用匹配提取 |
| `disconnectAndRelease(identity)` | ✅ | owner 释放 |
| `releaseNow()` | ✅ all | gate 关闭 |
| replacement（新 registerStarting 同 identity） | ✅ old, ✨ new | 旧 lease 被替换 |
| gate shutdown | ✅ all | 进程关闭 |

---

## 3. S2 — Service-side BootstrapAttempt 完整协议

### 3.1 核心修正（rev-gpt C6/C7）

v3 的 construction order 解决了循环依赖，但未处理 attach-after-supersession（attempt A install 后、attach job 前，B 替换了 A）。v3 也未定义 sticky-start 合约。

### 3.2 installBootstrapAttempt() 完整协议（rev-gpt suggestion #4）

```kotlin
// SessionStreamingService.kt

private class BootstrapAttempt(
    val epoch: Long,
    val gateAttemptId: Long,
    val sse: ServiceSseConnectionOwner,
) {
    val abortIssued = AtomicBoolean(false)
    val job = AtomicReference<Job?>(null)
}

private val currentAttempt = AtomicReference<BootstrapAttempt?>(null)

// §v4.6 C6: handoff slot for the job created by onStartCommand before an
// attempt exists. After installation, BootstrapAttempt.job is the sole owner.
private val pendingInstalledJob = AtomicReference<Job?>(null)

/**
 * §v4 S2 (rev-gpt C6/C7) + §v4.6 C6: complete bootstrap attempt installation protocol.
 * Handles attach-after-supersession, sticky reuse, and exact removal.
 *
 * Returns the installed attempt (for gate callback capture), or null if
 * this invocation was superseded before installation completed.
 */
private fun installBootstrapAttempt(
    epoch: Long,
    gateAttemptId: Long,
    sse: ServiceSseConnectionOwner,
    installedJob: Job,
): BootstrapAttempt? {
    val attempt = BootstrapAttempt(epoch, gateAttemptId, sse)

    // §v4 S2 rev-gpt C6 step 1: atomically install + capture/cancel previous
    val previous = currentAttempt.getAndSet(attempt)
    if (previous != null) {
        // Previous attempt was still current — cancel its job (if attached)
        previous.job.get()?.cancel()
        previous.abortIssued.set(true)  // prevent late abort
    }

    // §v4.6 C6 step 2: attach the job created by onStartCommand. This is the
    // same installedJob Android requires for the sticky contract.
    if (!attempt.job.compareAndSet(null, installedJob)) {
        // Already attached — shouldn't happen (we just created attempt)
        installedJob.cancel()
    }

    // §v4 S2 rev-gpt C6 step 4: verify attempt is still current AFTER attach
    if (currentAttempt.get() !== attempt) {
        // Superseded between install and attach — cancel our job, abort
        installedJob.cancel()
        attempt.abortIssued.set(true)
        // Don't remove from currentAttempt — the replacement owns it now
        return null
    }

    return attempt  // caller captures this for gate callbacks
}
```

**关键 check 点**（rev-gpt C6）：
1. `getAndSet` atomically install + capture previous
2. CAS `null → job` on attempt's local slot
3. `currentAttempt.get() === attempt` verify still current after attach
4. Gate registration 前再次 `currentAttempt.get() === attempt`
5. `job.start()` 前再次 check
6. 移除时 `currentAttempt.compareAndSet(attempt, null)`（exact-ref removal）

### 3.3 Sticky-start 合约（rev-gpt C7）

rev-gpt C7："`onBootstrapIdentity` 的 `NO_ATTEMPT_ID` 若每次 install 新 attempt，retained callback 的 `===capturedAttempt` 永久失效。"

v4 选择 rev-gpt 列出的第三个选项：**sticky bootstrap 不创建 replacement attempt 当 gate 已有 retained owner**。

```kotlin
// onCreate controller callback
onBootstrapIdentity = { identity ->
    // §v4 S2 rev-gpt C7: sticky path — do NOT create a replacement attempt
    // when the gate already retains an owner for this identity. The gate's
    // idempotent-Accept (registerStarting :300) RETAINS the original
    // launcher-path callbacks, which captured the original BootstrapAttempt.
    // Creating a new attempt here would make those callbacks' ===capturedAttempt
    // check permanently fail.
    //
    // Contract: sticky registration reuses the existing Service attempt.
    // Only register a new attempt if there is NO current one.
    if (currentAttempt.get() == null) {
        installBootstrapAttempt(
            epoch = bootstrapEpoch.get(),  // do NOT increment (R4 preserved)
            gateAttemptId = StreamingOwnershipGate.NO_ATTEMPT_ID,
            sse = requireNotNull(sseOwner),
            installedJob = requireNotNull(pendingInstalledJob.get()),
        )
    }
    // If currentAttempt != null, the existing attempt's callbacks remain valid.
    // Proceed to registerStarting with the existing attempt's handles.
    registerStartingOwnership(
        identity,
        StreamingOwnershipGate.NO_ATTEMPT_ID,
        currentAttempt.get()?.job?.get(),  // existing job
        requireNotNull(sseOwner),
    )
},
```

### 3.4 删除 BootstrapJobHolder（rev-gpt suggestion #4）

S2 的 `BootstrapAttempt.job: AtomicReference<Job?>` 替代 `BootstrapJobHolder`。roll back 路径直接用 attempt 的 job CAS：

```kotlin
// rollbackBootstrap(Timeout) 改为
val attempt = currentAttempt.get()
if (attempt != null) {
    val job = attempt.job.get()
    if (job != null && attempt.job.compareAndSet(job, null)) {
        job.cancel()
    }
}
// 不再调用 bootstrapJobHolder.removeIfCurrent
```

### 3.5 Sticky Integration Sequence

> **§v4.6 C6**：这是完整的 `onStartCommand → job installation → onBootstrapIdentity → gate callback` 顺序。host/config/foreground 检查保持现有流程；片段只展示 attempt/ownership 相关顺序，并使用当前代码已有的 `requestedOwnership`、`requestedAttemptId`、`installedJob`、`registerStartingOwnership` 和 `BootstrapAttempt` 类型。

```kotlin
// §v4.6 C6: inside onStartCommand, after existing foreground promotion.
import kotlinx.coroutines.Dispatchers

// 1. Always create/install the job first; Android requires a job even for a
// null-Intent sticky rebuild.
val installedJob = scope.launch(start = CoroutineStart.LAZY) {
    controller?.bootstrapAsync()
}
pendingInstalledJob.getAndSet(installedJob)?.cancel()

if (requestedAttemptId != StreamingOwnershipGate.NO_ATTEMPT_ID) {
    // §v4.6 C6: launcher path installs the exact attempt before registration.
    val attempt = installBootstrapAttempt(
        epoch = bootstrapEpoch.incrementAndGet(),
        gateAttemptId = requestedAttemptId,
        sse = requireNotNull(sseOwner),
        installedJob = installedJob,
    )
    // §v4.6 C6: exact-ref cleanup on both success and attach supersession.
    pendingInstalledJob.compareAndSet(installedJob, null)
    if (attempt != null && requestedOwnership != null) {
        // §v4.6 C6: registerStartingOwnership is suspend; onStartCommand is
        // not, so use the same Default-dispatcher hop as the real Service.
        scope.launch(Dispatchers.Default) {
            if (currentAttempt.get() !== attempt) return@launch
            val outcome = registerStartingOwnership(
                requestedOwnership,
                requestedAttemptId,
                attempt.job.get(),
                attempt.sse,
            )
            // §v4.6 C6: registration precedes starting the captured job.
            if (outcome is RegisterStartingOutcome.Accepted && currentAttempt.get() === attempt) {
                attempt.job.get()?.start()
            }
        }
    }
} else {
    // §v4.6 C6: sticky rebuild starts the job created in step 1.
    installedJob.start()
}

// §v4.6 C6: pass this suspend callback to SessionStreamingController's
// `onBootstrapIdentity: suspend (ConnectionIdentity) -> Unit` constructor arg.
val onBootstrapIdentity: suspend (ConnectionIdentity) -> Unit = { identity ->
    // 3. Reuse current attempt when the gate retains this identity. Never
    // install a NO_ATTEMPT_ID replacement in this case.
    val current = currentAttempt.get()
    val attempt = when {
        current != null && ownershipGate.hasOwner(identity) -> current
        // 4. Null is rare; usually launcher installation already happened.
        current == null -> installBootstrapAttempt(
            epoch = bootstrapEpoch.get(),
            gateAttemptId = StreamingOwnershipGate.NO_ATTEMPT_ID,
            sse = requireNotNull(sseOwner),
            installedJob = requireNotNull(pendingInstalledJob.getAndSet(null)),
        )
        else -> null // another non-sticky attempt owns the Service
    }
    if (attempt != null && currentAttempt.get() === attempt) {
        // 5. Sticky callback uses NO_ATTEMPT_ID but exact retained handles.
        registerStartingOwnership(
            identity,
            StreamingOwnershipGate.NO_ATTEMPT_ID,
            attempt.job.get(),
            attempt.sse,
        )
    }
}
SessionStreamingController(
    // ... existing constructor arguments
    onBootstrapIdentity = onBootstrapIdentity,
)
```

`installBootstrapAttempt` 的返回值必须是 `BootstrapAttempt?`：`null` 表示 attach 后发现已经 superseded，调用方不得注册 gate callback、启动该 job 或移除 replacement 的 current slot。

---

## 4. S3 — TokenStream 分解（保留 + TokenBundleCommitGate 完善）

### 4.1 v3 保留不变的部分

S3 的四组件分解（TokenStreamLifecycleArbiter / ConnectionRunner / FrameProcessor / BundleCommitGate）被 rev-gpt 确认为"fundamentally sound"。per-sid maps → 单 active lifecycle 也确认有效（但 S3c 只"evaluate"，不预承诺）。

### 4.2 TokenBundleCommitGate 完善（rev-gpt concern #4/suggestion #5）

rev-gpt concern #4：TokenBundleCommitGate 必须持有**完整**的 repository monitor 事务（不只是 final stamped dispatch）。

```kotlin
// TokenBundleCommitGate.kt

class TokenBundleCommitGate(
    private val repository: OpenCodeRepository,  // = bundleCommitLock (rev-gpt C4 preserved)
) {
    /**
     * §v4 S3 (rev-gpt concern #4/suggestion #5): execute the ENTIRE synchronous
     * frame mutation under the repository monitor. This spans:
     *   bundle validation → epoch/generation validation → revision dedup
     *   → token reducer update → removal hooks → ownership update
     *   → stamped ChatState dispatch
     *
     * The closure must NOT call lifecycle or connection methods that acquire
     * locks in the opposite order (rev-gpt concern #5: lock-order rule).
     *
     * §v4.2 rev-glm S1: action() 涵盖同步帧变更（reducer 更新/dedup 钩子/
     * ownership 更新）；效果分发（triggerSinceFetch/scheduleReconnect）
     * 在 synchronized(repository) 块结束后延迟——与当前代码
     * dispatchEpochFrame:1036-1237 的 deferredEffects 模式一致
     * （:1237 在 synchronized 块结束后执行延迟效果）。
     */
    fun commitIfCurrent(boundBundle: Bundle, action: () -> FrameCommitResult) {
        synchronized(repository) {
            // Validate bundle is still current (read repository.currentClientBundle()
            // under the monitor — §v4.2 rev-glm C-concern: not a replicated field)
            if (repository.currentClientBundle() !== boundBundle) return
            // Execute the full frame mutation under monitor
            action()
        }
    }
}

sealed interface FrameCommitResult {
    data object Committed : FrameCommitResult
    data object BundleSuperseded : FrameCommitResult
}
```

### 4.3 组件依赖方向（rev-gpt concern #5）

```
Facade
  → LifecycleArbiter
    → ConnectionRunner
      → FrameProcessor
        → TokenBundleCommitGate
```

**锁序规则**：FrameProcessor 在 TokenBundleCommitGate 的 `synchronized(repository)` 闭包内运行——**不得**回调 LifecycleArbiter 或 ConnectionRunner（会获取锁，可能反向锁序）。

---

## 5. 架构质量目标（scoped，rev-gpt suggestion #6）

rev-gpt C7/concern #6：v3 的"无生产文件 >800 行"不可行（ServiceSseConnectionOwner 1471、SessionStreamingService 1435、AuthorityReducer 1092 等都超标）。

v4 改为 **scoped targets**：

| # | 目标 | 范围 | 验证 |
|---|---|---|---|
| 1 | extracted token facade ≤700 行 | TokenStreamCoordinator | `wc -l` |
| 2 | 无新建组件 >800 行 | 4 个 token 组件 + TransportLease + StartingLease | `wc -l` |
| 3 | 每个异步域一个 stable lease identity | transport = TransportLeaseId; bootstrap = BootstrapAttempt; gate = StartingLease | grep `=== captured` |
| 4 | CAS retry 函数内无外部副作用 | 所有 AtomicReference CAS 点 | grep + 审查 |
| 5 | lease 替换后无 stale callback 被接受 | 所有回调 post-CAS validation | grep `leaseId ===` / `=== captured` |
| 6 | 所有 timer-owning 状态有显式 cancellation matrix | gate leaseTimers | §2.6 table |
| 7 | 所有 bounded registry 暴露 + 测试 capacity | token degraded/backoff registry | 测试 |
| 8 | 每个转移表有 replacement/shutdown/deadline race 测试 | TransportLease / StartingLease / BootstrapAttempt | 测试套件 |

---

## 6. 实施路线图（不变，S1→S4→S2→S3a→S3b→S3c）

| 阶段 | 内容 | 风险 | 前置 |
|---|---|---|---|
| **S1-design** | TransportLease transition table + linearization points + 同步 shutdown 路径 | 无（设计） | 无 |
| **S1-impl** | ServiceSseConnectionOwner TransportLease 两层模型 + sealed phase + 8 linearization points + cancelForShutdown | 中（1471 行核心文件） | S1-design |
| **S4** | Stage2DeadlineScheduler + gate StartingLease timer + launcher 简化 + 7 测试 + cancellation matrix | 中（gate 核心组件） | S1-impl |
| **S2** | installBootstrapAttempt 协议 + sticky reuse 合约 + 删除 BootstrapJobHolder | 低-中 | S4 |
| **S3a** | TokenStreamFrameProcessor 提取（行为保持） | 中 | S1-impl |
| **S3b** | LifecycleArbiter + ConnectionRunner 提取 | 中 | S3a |
| **S3c** | per-sid maps → 单 active lifecycle（evaluate + 谨慎删除） | 中 | S3b + 遥测 |

---

## 7. 与 rev-gpt v3 评审的逐条对照

| rev-gpt v3 critical | v4 处理 |
|---|---|
| C1（S1 linearization 不 race-safe） | ✅ §1.3 分层 CAS + **§v4.6 sideEffectsClaimed ownership CAS** + 非 suspending 操作留 connect 事务内 |
| C2（TransportLease 不能同时是 CAS state + reference identity） | ✅ §1.1 拆成 TransportLeaseId（stable）+ TransportLeaseState（replaceable） |
| C3（S1 不处理同步 shutdown） | ✅ §1.4 cancelForShutdown 同步 invalidation 路径 |
| C4（S4 gate timer 和 launcher 竞争） | ✅ §2.5 gate 成为唯一 deadline 权威，launcher 纯 await |
| C5（S4 timer 不完整） | ✅ §2.3-2.4 exact lease 捕获 + local teardown + lazy launch + **terminal-reference 主键**（§v4.1 rev-ds C1）+ suspend callback + gate-owned scope + cancellation matrix（§v4.6 C4） |
| C6（S2 attach-after-supersession） | ✅ §3.2 installBootstrapAttempt 6 点 check 协议 |
| C7（S2 sticky-start 未定义） | ✅ §3.3 + **§3.5 Sticky Integration Sequence（§v4.6 C6）**：不创建 replacement attempt 当 gate 已 retained |

| rev-gpt v3 concern | v4 处理 |
|---|---|
| #1（OutageState 独立允许无效组合） | ✅ gapEmitted/exhaustedReported 折叠进 phase variant |
| #2（recovered frame 不重置 outage epoch） | ✅ §1.3 #4 `Retrying(*, gapEmitted=*) → Live`（gapEmitted 重置） |
| #3（per-sid maps 非全可弃） | ✅ S3c 只 evaluate，persistent degradation 保留 bounded registry |
| #4（TokenBundleCommitGate 须持完整事务） | ✅ §4.2 commitIfCurrent 持有完整 repository monitor |
| #5（组件依赖方向） | ✅ §4.3 Facade→Arbiter→Runner→Processor→CommitGate + 锁序规则 |
| #6（S4 gate 从 passive→active，需 timer scope） | ✅ §2.2 Stage2DeadlineScheduler 抽象（不直接注入 app scope） |
