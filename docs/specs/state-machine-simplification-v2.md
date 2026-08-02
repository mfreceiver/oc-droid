# 状态机简化方案（v2，基于 rev-gpt 评审修订）

> **状态**：讨论稿。基于 v1 方案（被 rev-gpt @0.94 评审为 NO-GO）的修订。
> 核心转变：从"删 watchdog"（rev-gpt 证明不安全）转向"**简化状态机之间的耦合**"。

## 0. v1 → v2 的转变

### v1 的错误

v1 方案的基石是"slimapi 推送 turn token → Tier-1 fence 完整 → 删 Tier-2 watchdog"。rev-gpt 对照源码证明：
- turn token 是 **ingest-time watermark**（slimapi 收到 status 时 snapshot 当前计数器），**不是执行因果标识**（不绑定 prompt）
- 跨通道竞态（延迟的 turn-1 idle 被盖戳为 turn-2）无法被字典序 fence 拒绝
- 因此 watchdog 不能删——它是唯一能覆盖这个 gap 的 bounded convergence 机制

### v2 的核心思路

**不删 watchdog，简化它的环境。** watchdog 本身只有 209 行（exp-1 🟢），复杂的是它周围的**耦合**：

```
当前复杂度来源（不是 watchdog 本身，是交互）：
  OptimisticClaim 4 字段状态机
    × 3 条 reducer 路径（applyEvent/applySnapshot/applyReconcile）
    × 7s 保护窗口
    × watchdog 1s tick reconcile
    × SSE 断开时 watchdog 被杀（Fix A StopPoller）
    × REST 轮询竞争
    = v0.18.13 的 5 轮迭代痛苦
```

**简化方向**：收窄每条路径的职责，消除让它们交互变复杂的耦合点。

## 1. 简化机会清单（按收益/风险排序）

### 1.1 ⭐ 高收益：TokenStreamCoordinator 剥离状态（1900 行 → ~600 行）

**现状**：TokenStreamCoordinator 是最大的单文件（1900 行），持有：
- per-sid epoch/generation/attempt/503-count（5 个 ConcurrentHashMap）
- currentLifecycle / desired / reconnectRequested（3 个 AtomicReference）
- bundleCommitLock（synchronized）
- watchdog 子状态机
- reducer state per sid

这些状态大部分是**为了补偿"token stream 是独立 HTTP 通道、与 SSE 事件流并行"**而存在的。

**简化**：token stream 的职责应该是**纯数据管道**——接收 token → 累积文本 → 触发 UI 更新。它不需要自己管理 generation/reconnect/reducer 状态——这些应该委托给：
- **连接管理**：ServiceSseConnectionOwner（SSE 事件流已经管理 transport generation）
- **会话状态**：AuthorityReducer（status 归约已经集中在那里）
- **重连策略**：DefaultSseReconnectSupervisor（已存在）

具体剥离：
- `epochBySid`/`genBySid` → 委托给一个轻量 `TokenStreamEpoch` 纯值对象（不可变，CAS 替换）
- `desired` 双消费者 → 改为单消费者（route observer 和 foreground observer 合并为一个 `lifecycleEffect`）
- `bundleCommitLock` 递归 synchronized → 改为 Mutex + 非递归结构
- watchdog 子状态机 → 合并进 ServiceSseConnectionOwner 的 transport generation（已有 10s 心跳检测）

**风险**：中（1900 行核心文件，但有测试覆盖）
**收益**：删除 ~1300 行；消除 exp-1 标记的 🔴 高风险项（LAZY 自取消 + desired 双消费者）

### 1.2 ⭐ 高收益：ServiceSseConnectionOwner generation 收敛（9 个 @Volatile → 1 个 AtomicReference）

**现状**：9 个 `@Volatile` generation 字段（`transportGenerationCounter`、`closingGeneration`、`gapEmittedForGen`、`exhaustedReportedForGen`、`resyncHandledForGen`、`resyncInFlightForGen`、`resyncDirtyForGen`、`activeAttempt`、`pendingReadiness`）的非原子交互是 exp-1 标记的最高单点风险。

**简化**：合并为单个不可变数据类 + `AtomicReference` CAS：
```kotlin
data class TransportGeneration(
    val generation: Long,
    val closing: Boolean,
    val gapEmitted: Boolean,
    val exhaustedReported: Boolean,
    val resyncHandled: Boolean,
    val resyncInFlight: Boolean,
    val resyncDirty: Boolean,
    val activeAttempt: TransportAttemptToken?,
)

private val generationRef = AtomicReference(TransportGeneration())
```

所有读改写通过 `generationRef.compareAndSet(expected, updated)` 原子完成。

**风险**：低-中（单文件重构，但有 1471 行 + 测试薄弱）
**收益**：消除 exp-1 标记的 🔴 最高风险项；可测试性大幅提升；为后续所有 transport 改动提供安全基础

### 1.3 中收益：BootstrapJobHolder + epoch + handle-passing 简化

**现状**：v0.18.13 加的 `BootstrapJobHolder`（AtomicReference CAS）+ `bootstrapEpoch`（AtomicLong）+ handle-passing 签名（job + sse 参数）是为了消除 mutable-capture race。这是正确的，但引入了 3 层 fencing（epoch + job-ref + terminal-ref）。

**简化**：如果 Service 的回调改为**捕获闭包对象**（per-attempt `BootstrapAttempt` data class）而非读字段，可以把 epoch + job-ref + terminal-ref 合并进一个对象：
```kotlin
data class BootstrapAttempt(
    val epoch: Long,
    val job: Job?,
    val sse: ServiceSseConnectionOwner,
    val terminal: CompletableDeferred<OwnershipStartResult>,
)
```
回调捕获整个 `BootstrapAttempt` 引用，guard 检查 `currentAttempt === capturedAttempt`——单个引用相等性检查替代 3 层 fence。

**风险**：低（v0.18.13 刚建立的代码，改动范围小）
**收益**：简化 mental model；消除 3 层 fence 的交互复杂度

### 1.4 中收益：StreamingOwnershipGate Starting/Ready 双阶段简化

**现状**：Starting（Stage-1 ack）→ Ready（Stage-2 SSE 就绪）的双阶段存在是因为 launcher 需要 5s ack + 45s stage-2 超时。Stage-2 的 zombie reap 在 launcher 里调 `failStartingIfTerminal`——这是 v0.18.13 加的，跨了两个文件。

**简化**：把 Stage-2 超时收敛进 gate 本身——gate 的 Starting owner 携带自己的 deadline，到期自动提取。launcher 只需 await terminal，不负责 reap。

**风险**：中（gate 是核心组件，改动影响面广）
**收益**：消除 launcher-gate 的跨文件 zombie reap 耦合；launcher 代码大幅简化

### 1.5 低收益/保守保留

| 项 | 决定 | 理由 |
|---|---|---|
| **OptimisticClaimWatchdog** | **保留**（rev-gpt C1 证明不能删） | 209 行，职责单一，exp-1 🟢。可考虑从 1s 轮询改为 one-shot 定时器（per-claim），但非必要。 |
| **AuthorityReducer 3 路径** | **保留** | 纯函数设计是亮点（exp-1 确认）。7s 窗口是 no-replay 约束下的必要 fallback（rev-gpt C5/C6）。 |
| **StreamingLifecycleCoordinator L1-L4** | **保留** | Android FGS 约束，固有复杂度。 |
| **ConnectionPhase + BannerHysteresis** | **保留** | UI 层，设计良好。 |
| **ProcessStatusPoller** | **保留** | SSE 降级 fallback，设计合理。 |
| **ConnectionHealthProbe TOFU** | **保留** | 安全特性。 |

## 2. 实施路线图（修订）

| 阶段 | 内容 | 风险 | 收益 | 前置 |
|---|---|---|---|---|
| **S1** | ServiceSseConnectionOwner generation 收敛（9 @Volatile → 1 AtomicReference） | 低-中 | 消除最高单点风险；可测试性 | 无 |
| **S2** | BootstrapAttempt 闭包对象（合并 3 层 fence） | 低 | 简化 mental model | S1 |
| **S3** | TokenStreamCoordinator 剥离状态（1900→600） | 中 | 删除最大文件；消除 🔴 风险项 | S1（transport generation 稳定后） |
| **S4**（可选） | Gate Starting/Ready Stage-2 收敛 | 中 | 消除 launcher-gate 耦合 | S2 |

**每个阶段独立可发版、风险可控、不涉及 slimapi 协议改动。**

## 3. 与 rev-gpt 评审的关系

| rev-gpt critical | v2 如何处理 |
|---|---|
| C1（turn token 不是执行因果标识） | ✅ 不再声称 Tier-1 完整；保留 watchdog 作为必要 fallback |
| C2（Phase 1 自相矛盾：绕过 serverRound） | ✅ 不引入新 op 绕过 fence |
| C3（digest 不在控制通道） | ⚠️ 保留为已知限制（不阻塞 S1-S4，因为不改 status authority 路径） |
| C4（sseAlive 混淆下游/上游） | ✅ 不引入 sseAlive 布尔 |
| C5（先删 watchdog 后做恢复） | ✅ 不删 watchdog |
| C6（busy 是 edge-triggered） | ✅ 保留 7s 窗口保护 |
| C7（legacy 回退） | ✅ 不全局删 watchdog |

**v2 不试图解决 status authority 协议问题**（那需要 slimapi 改动，是 rev-gpt 建议的 Phase 0）。v2 聚焦于**简化客户端状态机之间的耦合**——在不改变 status authority 模型的前提下，降低复杂度。

## 4. 简化的量化目标

| 指标 | 当前 | 目标 | 手段 |
|---|---|---|---|
| 核心状态机总行数 | ~12000 行 | ~8000 行（-33%） | S3 剥离 TokenStreamCoordinator |
| 🔴 高风险项 | 3 个 | 1 个（仅 AuthorityReducer 的固有复杂度） | S1 消除 ServiceSseConnectionOwner + S3 消除 TokenStreamCoordinator |
| @Volatile 非原子字段 | 9 个 | 0 个 | S1 收敛为 AtomicReference |
| 回调读可变字段的位置 | 0（v0.18.13 已修） | 0（保持） | S2 闭包对象保持 |
| 跨文件 zombie reap | 1（launcher→gate） | 0 | S4（可选） |

## 5. 不做什么（明确排除）

- **不删 watchdog**（rev-gpt C1/C7 证明不安全）
- **不改 slimapi 协议**（那是 rev-gpt 建议的 Phase 0，是独立的大项目）
- **不改 AuthorityReducer 的 status authority 模型**（P0-A/B/C 是好的基础，rev-gpt 确认）
- **不改 StreamingLifecycleCoordinator 的 L1-L4 FSM**（Android FGS 固有约束）
