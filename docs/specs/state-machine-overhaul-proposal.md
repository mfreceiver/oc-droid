# 状态机架构完善方案

> 基于 ocdroid 12 个状态机现状测绘 + opencode web/server/SDK 对照 + oc-slimapi 源码确认。
> 调研日期：2026-08-02。本方案为讨论稿，未进入实施。

## 0.5 历史演变（思路脉络 + 关键转折）

### 演变时间线

| 阶段 | 版本/批次 | 决策 | 动机 |
|---|---|---|---|
| **早期 watchdog** | v0.1.x | 存在 streaming watchdog（5s 无 SSE 进度 + busy → reload） | 防止 SSE 丢帧导致 UI 停滞 |
| **v0.2.0 (S1)** | `9337db2c` | **移除 watchdog**，"对齐 web SSE 信任模型"，前台 reload 15s 节流 | OOM 修复 + 简化；信任 SSE 事件流 |
| **用户反馈出现** | v0.2.x | retry/退避期 stop 消失（stale idle 覆盖 optimistic busy） | 双权威源 + path #5 无护栏 + 缺执行代际 fence |
| **P0-A** | `2a4aee5d` (2026-07-30) | 单一权威源：`StoreState.authority` 切片 + 纯 reducer | 根治双源不同步 |
| **P0-B** | `0d572d23` (2026-07-30) | **加回 watchdog**（Tier-2 fallback）+ Tier-1 (serverRound) fence | "legacy SSE 协议不携带任何服务端因果标识"（plan §1.3 R1） |
| **P0-C** | `131592e0` (2026-07-30) | identity guard（event-captured） | host 切换后旧 SSE 事件读到新 identity |
| **U-P2** | `562deb1c` (2026-07-31) | watchdog 从 ProcessStatusPoller 抽出成独立 `OptimisticClaimWatchdogCoordinator` | 解耦检测延迟（30s→5s） |
| **v0.18.13** | `1ac6df71` (2026-08-02) | 7s 保护窗口 + applyReconcile gate + SSE zombie fix | SSE 断开时 REST 轮询覆盖 optimistic claim |

### 关键转折：watchdog 加回的前提已不成立

P0-B 加回 watchdog 的核心理由（plan 文档 §1.3 R1 原文）：
> legacy SSE 协议**不携带任何服务端因果标识**——每帧只有 `{sessionID, status}`，没有「这是哪一次执行轮次」。

因此 watchdog 作为 Tier-1 (serverRound) 的 **fallback**——"legacy 路径没有 turn token，只能用启发式确认门 + 5s 超时自愈"。

**但 slimapi 现在已经推送 `turnIncarnation + turn`**（`oc-slimapi/src/oc_slimapi/sse/global_hub.py:415-416`，盖戳于 ingest 时，随 session.status digest 推送）。这正是 P0-B 当初认为"没有"的因果标识。

P0-B 的 "ITEM 3 — Slim serverRound parsing" 说明他们**当时就在加** serverRound 解析——watchdog 是过渡期的兜底。**现在 Tier-1 fence 已完整可用，Tier-2 watchdog 的存在前提消失了。**

### 这不是"重蹈覆辙"

v0.2.0 移除 watchdog 失败（导致 retry 期 stop 消失）是因为：
- 当时**没有** authority reducer（双源不同步）
- 当时**没有** serverRound fence（无法区分 stale idle）
- 当时**没有** typed AuthorityOp（optimistic 无护栏）

现在这三个都已落地（P0-A/B/C）。**阶段 1 的前提条件已经成熟**——在 authority reducer + serverRound fence + typed op 的基础上移除 watchdog，不是回到 v0.2.0 的裸信任，而是"Tier-1 已完整可用，Tier-2 可以退役"。

### 本轮 zombie fix 的教训印证

v0.18.13 的 5 轮迭代痛苦（Fix C 的 optimistic claim 保护窗口 + applyReconcile gate）正是 Tier-2 watchdog 复杂度的直接体现：watchdog 与 SSE 事件流并行，两者竞争清除 claim，需要时间窗口协调。如果 SSE session.status 是权威（Tier-1 可用），watchdog 不存在，Fix C 的整个问题类不会出现。

---

## 1. 背景与动机

### 1.1 本轮 SSE zombie fix 暴露的架构问题

v0.18.13 的 SSE zombie 三修（Fix A/B/C）经历了 **5 轮评审迭代**才收敛：
- v1/v2 设计被双 reviewer 驳回（mutable-capture race、epoch ABA、check-then-suspend）
- v3 经 oracle 架构级重设计才通过门控
- 实现阶段连失 3 轮（同一类 mutable-field shortcut）

根因不是某个具体状态机的缺陷，而是**整个客户端状态权威层的架构错位**：ocdroid 在客户端重新实现了服务端已经承担的 session.status 推断（optimistic claim + watchdog + 7s 窗口 + 3 条 reducer 路径），用 12 个状态机去补偿"缺少权威推送"的缺陷。

### 1.2 调研基础

| 调研对象 | 方法 | 关键发现 |
|---|---|---|
| ocdroid 12 个状态机 | exp-1 深度测绘（源码逐文件） | 9 个 `@Volatile` 非原子字段交互、TokenStreamCoordinator LAZY 自取消、optimistic claim 4 字段状态机 |
| opencode web/server/SDK | exp-2/exp-3 对照（TS 源码） | Effect 纯函数、无 centralized store、SSE 双流 + `after` replay、session.status 服务端推送 |
| oc-slimapi | 本会话直接读源码 | **已推送 session.status digest**（250ms 去抖动）、**不支持 `after` replay**（明确返回 `reconnect_no_replay`）、10s 心跳 |

### 1.3 核心诊断

ocdroid 当前的数据流（**本末倒置**）：
```
SSE session.status digest (slimapi 推送，权威来源)
    ↓ 被降级为"参考"
optimistic claim (客户端自造的"权威")
    ↓ 配以 watchdog + 7s 窗口 + 3 条 reducer 路径协调
UI 显示
```

应该的数据流：
```
SSE session.status digest (权威)
    ↓ 直接信任
UI 显示
    ↓ (SSE 断开时 fallback)
REST 全量轮询 + 短暂保护窗口
```

## 2. slimapi 实际能力（源码确认）

| 能力 | 状态 | 证据 |
|---|---|---|
| 推送 session.status | ✅ 控制平面 (`/slimapi/events`) 推送 busy/idle digest | `sse/global_hub.py:404-422`；`sse/hub_types.py:78` SESSION_EVENTS |
| token stream 感知 status | ✅ 数据平面记录到 `_session_status`，用于 idle 清理 | `sse/tokenstream/hub.py:816-872` |
| 支持 `after` replay | ❌ 明确不支持 | `routes/events.py:41-50` 返回 `reconnect_no_replay`；`routes/token_stream.py:17-18` |
| 心跳 | ✅ 10s 间隔 | `sse/hub_types.py:91` HEARTBEAT_SECONDS = 10.0 |
| digest 去抖动 | 250ms 窗口累积 | `sse/hub_types.py:90` DEBOUNCE_SECONDS = 0.25 |
| status 值域 | 仅 busy/idle（无 retry） | `sse/tokenstream/hub.py:834` 显式过滤 |
| turn incarnation fence | ✅ 服务端盖戳 | `sse/global_hub.py:415-416` + `turn_registry.py` |

## 3. 12 个状态机的脆弱性热力图

按修改风险分级（基于 exp-1 测绘）：

| 风险 | 状态机 | 脆弱点 | 根因 |
|---|---|---|---|
| 🔴 高 | **AuthorityReducer** | optimistic claim 4 字段状态机 + 7s 窗口 + 3 条路径 | 客户端不应承担 session.status 权威 |
| 🔴 高 | **ServiceSseConnectionOwner** | 9 个 `@Volatile` generation 字段非原子交互 | 传输代次 fencing 本可由 server session-id 替代 |
| 🔴 高 | **TokenStreamCoordinator** | LAZY start 自取消 + `desired` 双消费者 | 独立 HTTP 通道，与 SSE 事件流并行 |
| 🟡 中 | **StreamingOwnershipGate** | hasLiveAttemptOtherThan TOCTOU + expireAttempt 边界 | Starting/Ready 双阶段因 server 不推送 status 而需要 |
| 🟡 中 | **StreamingLifecycleCoordinator** | L1/L2/L3/L4 + 45s 防抖 + handoff 竞态 | Android FGS 生命周期（固有约束） |
| 🟡 中 | **OptimisticClaimWatchdog** | 仅补偿"server 不推送"的缺陷 | 不应存在——若 server 推送 status 则不需要 |
| 🟢 低 | BootstrapJobHolder | 无已知脆弱点 | 刚用 AtomicReference CAS 重写 |
| 🟢 低 | BannerHysteresis | 纯 reducer，可测试 | 设计良好 |
| 🟢 低 | ConnectionPhase | sealed class + isSseDown 分类器 | 刚扩展过 |
| 🟢 低 | ConnectionHealthProbe | TOFU 状态机 + retry backoff | 职责清晰 |
| 🟢 低 | ProcessStatusPoller | generation fencing 设计良好 | SSE 降级 fallback 合理 |
| 🟢 低 | SessionStreamingController | command 分发清晰 | 职责单一 |

## 4. 改造方案（3 阶段，渐进式）

### 阶段 1：SSE session.status 提升为权威（最大收益，中等改动）

**前提**：slimapi 已在 `/slimapi/events` 推送 `session.status` digest。ocdroid 的 `SseEventBridge` 已接收这些事件——只是 AuthorityReducer 把它们降级了。

**改动**：

1. **新增 `AuthorityOp.ApplySseSessionStatus`** — 专用路径，直接信任 SSE 推送的 status：
   ```kotlin
   data class ApplySseSessionStatus(
       val sid: String,
       val status: String,         // "busy" / "idle"
       val turnIncarnation: Long?, // slimapi 的 turn fence
       val turn: Long?,
       val scopeKey: ScopeKey,
       val eventTimeMs: Long,
   ) : AuthorityOp
   ```

2. **在 `SseEventBridge` 路由 session.status digest 到新 op**（而非走 ApplyEvent 的 SSE_LEGACY/SLIM 路径）。新 op 绕过 serverRound 因果检查 + 绕过 optimistic claim 协调——SSE 是权威，直接覆盖。

3. **降级 optimistic claim 为 SSE-断开-only**：
   - `applyEvent(OPTIMISTIC)` 仍创建 claim（发送消息时立即显示 busy）
   - SSE session.status busy 到达时立即 `serverEchoed=true`
   - SSE session.status idle 到达时立即清除 claim（不需要 7s 保护窗口）

4. **`applySnapshot` / `applyReconcile` 的 7s 保护窗口仅当 SSE 断开时激活**：
   - 新增 `AuthorityState.sseAlive: Boolean`（由 SseEventBridge 维护）
   - `sseAlive=true`：applySnapshot/applyReconcile 直接信任 REST（SSE 会纠正）
   - `sseAlive=false`：保持现有 7s 保护窗口（fallback）

5. **删除 `OptimisticClaimWatchdogCoordinator`**（SSE 在线时不需要；SSE 断开时 7s 窗口 + REST 轮询足够）

**范围**：~6 文件，净删除 ~250 行
**风险**：中（改动核心状态路径，但逻辑大幅简化）
**收益**：消除 Bug C 根因；"已中断闪烁"从架构层面消失

### 阶段 2：SSE 断开恢复策略（低风险，明确化）

slimapi 不支持 replay，所以断线恢复用**全量重取 + 状态对账**：

1. **SSE 重连成功后立即触发一次 REST `/slimapi/sessions/status` 全量拉取**（ProcessStatusPoller 已支持）
2. **拉取结果走 `applySnapshot`（fallback 模式，7s 窗口激活）**——因为 SSE 刚恢复，可能有短暂 status 不一致
3. **SSE 恢复后 1-2s 内，如果 session.status digest 与 REST 不一致，以 SSE 为准**
4. **保留 ProcessStatusPoller 作为 SSE 断开 >10s 时的周期性 fallback**（现有设计合理）

**范围**：~3 文件（恢复路径协调）
**风险**：低
**收益**：断线恢复行为明确化；消除"断线后状态不一致"的模糊性

### 阶段 3：ServiceSseConnectionOwner generation 收敛（低风险清理）

exp-1 标记的最高风险单点。9 个 `@Volatile` generation 字段的非原子交互。

1. **用 `AtomicLong` 替换 `@Volatile` + 非原子读改写**：`closingGeneration`、`gapEmittedForGen` 等
2. **合并相关 generation 字段为单个 `AtomicReference<TransportGeneration>` 数据类**
3. **补充单元测试**（currently 1471 行代码测试覆盖薄弱）

**范围**：~3 文件（单文件重构）
**风险**：低
**收益**：消除最危险单点；可测试性大幅提升

## 5. 不建议改动的部分

| 状态机 | 理由 |
|---|---|
| **StreamingLifecycleCoordinator** (L1/L2/L3/L4) | Android FGS 生命周期是平台约束，opencode（Node/Bun 进程）无此问题。复杂度是固有的。 |
| **ConnectionPhase + BannerHysteresis** | UI 层状态机，设计良好，v0.18.13 刚扩展过。 |
| **BootstrapJobHolder** | v0.18.13 刚用 AtomicReference CAS 重写，无已知问题。 |
| **ConnectionHealthProbe (TOFU)** | TOFU 信任决策是 ocdroid 特有的安全特性。 |
| **slimapi** | 不需要改——它已经做了正确的事（推送 session.status）。问题在 ocdroid 客户端没正确消费。 |

## 6. opencode 可借鉴的具体技术点（不依赖架构改造）

即使不做架构改造，以下 opencode 实现也值得借鉴：

1. **session.status 的 retry 三态**（idle/busy/retry with `next` timestamp + `action` hint）——比 ocdroid 的二元 busy/idle 更有表现力。注意：slimapi 当前只推送 busy/idle，retry 需要服务端支持。
2. **纯函数状态机**（prompt-input 的 `transitionPromptInputV2`）——可测试、可推理，适合 ocdroid 的 BannerHysteresis 和 ConnectionPhase
3. **流式文本 pacing**（`createPacedValue`：24ms 间隔 + 512 字符阈值 + 标点断词）——比 ocdroid 的逐 token 渲染更平滑
4. **15s SSE 心跳**——slimapi 已有 10s 心跳，更优；ocdroid 可利用心跳检测连接活性而无需自己实现 watchdog
5. **结构化错误类型**（7 种 tagged error）——比 ocdroid 的字符串错误更有类型安全性

## 7. 实施路线图

| 阶段 | 前置条件 | 风险 | 收益 | 推荐时机 |
|---|---|---|---|---|
| **3** (generation 收敛) | 无 | 低 | 消除最危险单点 | 立即可开始 |
| **1** (SSE 权威化) | slimapi 已确认推送 status | 中 | 消除 Bug C 根因 + 删 watchdog | 阶段 3 后 |
| **2** (断线恢复) | 阶段 1 完成 | 低 | 断线行为明确化 | 阶段 1 后 |

每个阶段独立可发版、风险可控。

## 8. 相关文档

- `docs/specs/state-machine-architecture-spec.md` — 现有状态机架构规范
- `docs/specs/sse-client-spec.md` — SSE 客户端设计
- `docs/specs/slim-mode-api-routing.md` — slimapi 路由
- `docs/specs/l4-sse-lifecycle-design.md` — L4 SSE 生命周期设计
- `/tmp/opencode/sse-fix-a-design-v4.md` — v0.18.13 的 Fix A 设计（oracle v4）

## 9. 调研产物索引

| 产物 | 位置 | 内容 |
|---|---|---|
| exp-1 ocdroid 现状测绘 | session ses_03d8b21f9ffeymbVbTTFLC6dp5 | 12 个状态机完整映射 + 交互图 |
| exp-2 opencode 客户端 | session ses_03d8b020bffeEyhmXWyzUNtU5U | sdk-next/session-ui/protocol/web 映射 |
| exp-3 opencode 服务端 | session ses_03d8ae871ffezQUdtaNnEROxy6 | opencode/core/server/llm session FSM |
| slimapi 源码确认 | 本会话 | global_hub.py / tokenstream/hub.py / routes/events.py |
