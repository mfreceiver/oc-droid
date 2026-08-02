# 状态机大幅精简方案（v5-lean）

> **状态**：讨论稿。基于用户梳理后的精简范围确认。
> 前序：v1-v4.5（8 轮评审的内部并发清理方案）仍在 `state-machine-simplification-v4.md`。
> 本方案是**不同方向**——通过放弃特定保证大幅降低复杂度，而非在保留所有保证的前提下做内部清理。

## 0. 方向选择

v4 方案（8 轮评审）试图在保留所有现有保证（后台 SSE + 通知 + optimistic claim + 双通道 + 多 host）的前提下做内部并发清理。评审揭示：保留这些保证的并发清理极其困难（TransportLease / StartingLease / BootstrapAttempt 的 linearization 设计经 8 轮仍未完全收敛）。

v5-lean 方向：**通过放弃特定保证，从根源消除复杂性**——删除产生复杂性的状态机，而非试图清理它们。

## 1. 用户确认的约束

### 保留（不可精简）

| 特性 | 理由 |
|---|---|
| **token stream**（slimapi 聚合） | 核心流量优化 + 防闪烁；原始 message.part.delta 闪烁 + 流量大 |
| **TOFU 证书钉扎** | mtls 功能依赖 |
| **AuthorityReducer 纯函数** | P0-A 确认的正确架构（单一权威源 + 纯 reducer + CAS） |
| **历史持久化** | 已无（当前就没有） |

### 精简（用户确认）

| 特性 | 精简方式 | 用户原话/理由 |
|---|---|---|
| **后台 SSE + 通知** | app 进后台 = 断 SSE；返回 = 冷启动恢复 | "如果完全放弃本应用的后台和通知功能" |
| **optimistic claim** | POST 后等 SSE session.status（~250ms） | "其他我认为都可以精简" |
| **会话列表实时更新** | 定时刷新（如每 30s） | "可以改为定时刷新" |
| **multi-host/profile 并发** | 单 host 连接 | "其他我认为都可以精简" |
| **后台 token stream** | app 进后台 = 断 token stream | 同后台 SSE |

## 2. replay 评估

### 结论：replay 可用，不影响 slimapi 原有机制

slimapi 的 catch-all proxy（`proxy.py:160-217`）把任何不匹配 `/slimapi/*` 特定路由的请求透传给上游 opencode：

```python
# proxy.py:166 — query params 透传
params=request.query_params.multi_items()

# proxy.py:170 — SSE 路径检测
is_sse = norm_path in {"/event", "/global/event"}

# proxy.py:165 — 路径标准化后透传给上游
norm_path
```

`GET /session/{sid}/event?after=N` 会被透传到 opencode V2 的 `session.events` 端点（exp-3 确认上游支持 `after` 参数 replay）。这个透传路径：

- **不经过** slimapi 的 digest 去抖动（那是 `/slimapi/events` 专属逻辑）
- **不经过** token 聚合（那是 `/slimapi/sessions/{sid}/stream` 专属逻辑）
- **不影响** slimapi 的任何流量优化机制

### 注意事项

- replay 返回的是 V2 细粒度事件（`Step.Started/Ended/Failed`、`Text.Delta` 等），不是 V1 的 `session.status` digest 格式
- ocdroid 需要扩展 `SseEventBridge` 处理 V2 事件格式（或保持 V1 digest 模式 + 仅在断线恢复时用 V2 replay 补缺）
- replay 的流量消耗 = 缺失期间的事件量（通常很少，因为断线到重连通常 < 30s）

## 3. 精简范围

### 删除的状态机（整个移除）

| # | 状态机 | 行数 | 删除理由 |
|---|---|---|---|
| 1 | **StreamingLifecycleCoordinator** L2/L3/L4 分支 | ~1000（1692 行中约 60%） | 后台不保持 SSE → 无需 L2Active/L2Idle/BackgroundGrace/NoSourceTerminal |
| 2 | **StreamingOwnershipGate** | ~664 | 存在的唯一原因是 FGS 需"谁拥有 SSE 连接"仲裁 → 无 FGS 则无仲裁需求 |
| 3 | **StreamingServiceLauncher** | ~282 | Stage-1/Stage-2 超时存在是因为 FGS 启动有延迟 → 无 FGS 则直接连 SSE |
| 4 | **BootstrapJobHolder** + epoch + handle-passing | ~100 | v0.18.13 加的 mutable-capture race 补偿层 → 无 FGS bootstrap 则不存在 |
| 5 | **SessionStreamingService** FGS 壳 | ~800（1466 行中约 55%） | 不需要后台 SSE → 不需要 FGS |
| 6 | **SessionStreamingController** | ~480 | bootstrap 重试循环 + command 分发 → 无 FGS 则不需要 |
| 7 | **OptimisticClaimWatchdogCoordinator** | ~209 | SSE 在线时直接信任 session.status → 不需要 watchdog reconcile |
| 8 | **ProcessStatusPoller**（SSE 降级 fallback） | ~640 | 替换为定时刷新（每 30s REST GET /slimapi/sessions） |

### 精简但保留的状态机

| # | 状态机 | 当前行数 | 精简后行数 | 删除部分 |
|---|---|---|---|---|
| 9 | **ServiceSseConnectionOwner** | 1471 | ~400 | 删除重连/降级/代次 fencing（9 个 @Volatile generation 字段）+ transport timeout + exhausted + gap emission + resync 系列；保留 connect/disconnect/基本帧处理 |
| 10 | **TokenStreamCoordinator** | 1900 | ~800 | 删除 foreground/route 门控 + desired/suspendClose/resume + reconnect policy + 503 降级；保留 token 聚合 + 帧处理 + reducer state |
| 11 | **AuthorityReducer** | 1092 | ~600 | 删除 optimistic claim 4 字段状态机 + 7s 窗口 + applySnapshot/applyReconcile Tier-2 gate；保留纯函数 reducer + ApplyEvent/ApplySnapshot 基本路径 |
| 12 | **AuthorityState** | ~250 | ~150 | 删除 OptimisticClaim data class + ServerRound high-water（如果信任 SSE digest 的 turn token）；保留 SessionEntry 基本字段 |

### 不变的状态机

| # | 状态机 | 行数 | 理由 |
|---|---|---|---|
| 13 | **ConnectionPhase** + isSseDown | ~160 | UI 层，设计良好 |
| 14 | **BannerHysteresis** | ~130 | UI 层，纯 reducer |
| 15 | **ConnectionHealthProbe**（含 TOFU） | ~900 | mtls/TOFU 安全特性 |
| 16 | **SseEventBridge** | ~300 | 可能需扩展处理 V2 事件格式（如果走 replay 路径） |

### 行数估算

| | 当前 | 精简后 | 减少 |
|---|---|---|---|
| 核心状态机总行数 | ~12000 | ~4000-5000 | **-58% 到 -67%** |
| 🔴 高风险项 | 3 个 | 0-1 个 | TokenStreamCoordinator 精简后风险降低 |
| @Volatile 非原子字段 | 9 个 | 0-2 个 | ServiceSseConnectionOwner 精简后大部分字段删除 |
| 状态机数量 | 12 个（+ 子状态机） | 6 个 | -50% |

## 4. 精简后的数据流

### 正常运行（前台）

```
app 启动 / 从后台返回
  → testConnection()（REST 健康检查 + TOFU 证书验证）
  → REST GET /slimapi/sessions（全量会话列表 + 状态快照）
  → SSE 连接 /slimapi/events（前台保持）
  → token stream /slimapi/sessions/{sid}/stream（当前会话的 token 流）

session.status 更新：
  → 收到 session.status digest（busy/idle + turnIncarnation + turn）
  → 直接写 AuthorityReducer.applyEvent(SSE_SLIM)
  → 不经过 optimistic claim 协调
  → 不经过 watchdog reconcile
  → serverRound (incarnation, turn) 字典序 fence 保留（防止 stale 帧）

发消息：
  → POST prompt_async
  → 等 SSE session.status busy 到达（slimapi digest ~250ms）
  → UI 显示 busy（等 ~250ms，不是立即——可接受）
  → 不创建 optimistic claim
  → 不需要 watchdog 确认

收 token：
  → token stream 推送聚合后的 part delta
  → TokenStreamFrameProcessor 处理 → UI 逐字渲染

会话列表更新：
  → 定时刷新（每 30s REST GET /slimapi/sessions）
  → 不再 SSE 驱动
```

### 断线恢复（前台时）

```
SSE 断开（网络抖动）
  → 检测到（心跳超时 10s 或连接错误）
  → 重连 /slimapi/events
  → 重连成功后：
    方案 A（V1 digest 模式）：
      → REST GET /slimapi/sessions/status（全量状态快照）
      → applySnapshot 直接信任（无 optimistic claim 保护窗口）
      → 恢复实时 SSE digest 流
    方案 B（V2 replay 模式，如果实现）：
      → 透传 GET /session/{sid}/event?after=lastSeq
      → replay 缺失的 V2 事件
      → 恢复实时 SSE digest 流
```

### 后台/返回

```
app 进后台：
  → SSE 断开（不保持）
  → token stream 断开
  → 无后台通知（用户需手动打开 app）

app 返回前台：
  → 冷启动恢复（同"app 启动"流程）
  → 全量拉取会话列表 + 重连 SSE
```

## 5. 与 v4 方案的关系

| v4 阶段 | v5-lean 状态 | 理由 |
|---|---|---|
| **S1**（TransportLease） | ❌ 不需要 | ServiceSseConnectionOwner 精简后无 9 个 @Volatile（大部分删除） |
| **S2**（BootstrapAttempt） | ❌ 不需要 | 无 FGS bootstrap → 无 epoch/job/abort 管理 |
| **S3**（TokenStream 分解） | ⚠️ 简化版仍需要 | 删除 foreground/route 门控，但 token 聚合逻辑保留 |
| **S4**（StartingLease） | ❌ 不需要 | 无 ownership gate → 无 Stage-2 超时 |

**v5-lean 比 v4 简单得多**——它删除复杂性的来源（FGS + 后台 SSE + optimistic claim），而不是试图在保留它们的前提下做并发清理。v4 的 8 轮评审痛点（TransportLease linearization / StartingLease timer / BootstrapAttempt supersession）**全部消失**。

## 6. 实施路线图

| 阶段 | 内容 | 风险 | 收益 | 前置 |
|---|---|---|---|---|
| **L1** | 删除 FGS + 后台 SSE | 高（大范围删除，影响 Service 层） | 删除 ~3500 行 + 7 个状态机 | 无 |
| **L2** | 简化 ServiceSseConnectionOwner（删除重连/代次） | 中（核心文件，但删除的是独立功能） | 删除 ~1000 行 + 消除 9 个 @Volatile | L1 |
| **L3** | 简化 AuthorityReducer（删除 optimistic claim） | 中（改动核心 reducer，但逻辑大幅简化） | 删除 ~500 行 + 消除 watchdog | L2 |
| **L4** | 简化 TokenStreamCoordinator（删除 foreground/route） | 中（最大文件，但删除的是门控逻辑） | 删除 ~1100 行 | L2 |
| **L5** | 替换 ProcessStatusPoller 为定时刷新 | 低（独立替换） | 删除 ~640 行 + 简化轮询 | L3 |
| **L6**（可选） | replay 集成（V2 事件格式或 V1 全量快照恢复） | 低-中（增量改进，非阻塞） | 断线恢复更精确 | L3 |

每个阶段独立可发版。推荐顺序 L1→L2→L3→L4→L5→L6。

## 7. 不做什么（明确排除）

- **不改 slimapi 协议**（replay 用透传，不需要 slimapi 改动）
- **不改 token stream 聚合**（核心流量优化，保留）
- **不改 TOFU/mtls**（安全特性，保留）
- **不删 AuthorityReducer**（纯函数架构正确，精简但不删除）
- **不删 ConnectionPhase/BannerHysteresis**（UI 层，设计良好）

## 8. 风险评估

| 风险 | 严重度 | 缓解 |
|---|---|---|
| 用户后台时不收通知 | **产品决策**（用户已确认接受） | 文档标注"不支持后台通知" |
| POST 后 UI 延迟 ~250ms 显示 busy | 低（用户几乎无感） | 可加一个极简 loading 指示（非 optimistic claim，纯 UI） |
| 定时刷新会话列表（30s）不如实时 | 低（大多数用户不需要秒级实时） | 用户可手动下拉刷新 |
| L1 删除 FGS 影响面大 | 中 | 分步删除：先停止后台 SSE 保持 → 再删 FGS 壳 → 再删 ownership gate |
| 单 host 连接 | 低（多 host 是边缘场景） | 切换 host = 冷启动恢复 |

## 9. 相关文档

- `docs/specs/state-machine-overhaul-proposal.md` — v1 方案（被 rev-gpt @0.94 评审为 NO-GO）
- `docs/specs/state-machine-simplification-v4.md` — v4 方案（8 轮评审的内部并发清理，含 v4.5 修订）
- `docs/specs/state-machine-architecture-spec.md` — 现有状态机架构规范（P0-A/B/C 落地后）
- `docs/2026-07-30-ocdroid-state-machine-improvement-plan.md` — v3 历史方案快照
- `docs/specs/sse-client-spec.md` — SSE 客户端设计
- `docs/specs/l4-sse-lifecycle-design.md` — L4 SSE 生命周期设计（v5-lean 中删除）

## 10. 调研产物索引

| 产物 | 位置 | 内容 |
|---|---|---|
| exp-1 ocdroid 现状测绘 | session ses_03d8b21f9ffeymbVbTTFLC6dp5 | 12 个状态机完整映射 + 交互图（reusable） |
| exp-2 opencode 客户端 | session ses_03d8b020bffeEyhmXWyzUNtU5U | sdk-next/session-ui/protocol/web 映射（reusable） |
| exp-3 opencode 服务端 | session ses_03d8ae871ffezQUdtaNnEROxy6 | opencode/core/server/llm session FSM（reusable） |
| slimapi 源码确认 | 本会话 | global_hub.py / tokenstream/hub.py / routes/events.py / proxy.py |
| rev-gpt v1 方案评审 | session ses_03e831872ffeb43xprCv6rpt1u | NO-GO @0.94（turn token 因果归因分析）（reusable） |
| rev-ds v4.3 评审 | session ses_03d3b9c72ffe4eAAfCdMrTX6y3 | APPROVED-WITH-CONDITIONS @8/10（reusable） |
| rev-glm v4.4 评审 | session ses_03d32d354ffeahh2CSnPckzOYf | APPROVED-WITH-CONDITIONS @8/10（reusable） |
| rev-gpt v4.5 终审 | session ses_03d2e35c4ffeVfk7b6rpU2ccUQ | NOT APPROVED @0.97（6 critical）（reusable） |
