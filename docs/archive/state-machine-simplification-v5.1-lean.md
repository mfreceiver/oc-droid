# 状态机大幅精简方案（v5.1-lean）

> **状态**：讨论稿（修订版）。基于 v5-lean + 三轮调研（ocdroid 可行性 / slimapi 影响 / opencode 设计调研）整合。
> **前序**：`state-machine-simplification-v5-lean.md`（保留不动作为历史）、`state-machine-simplification-v4.md`（8 轮评审内部并发清理）。
> **本版相对 v5-lean 的核心变化**：修正 13 项文档/代码偏差；修正 replay 路径（上游 `GET ?after=` 证伪）；修正依赖链（L3 独立、L4/L5 前置是 L1）；定性修正 OwnershipGate（不可整体删，精简保留）；新增 opencode 设计借鉴专章与问题域对照。

---

## 0. 方向（沿用 v5-lean）

v4 方案（8 轮评审）试图在保留所有现有保证（后台 SSE + 通知 + optimistic claim + 双通道 + 多 host）的前提下做内部并发清理，评审揭示其并发 linearization 极难收敛。

**v5-lean / v5.1-lean 方向不变**：通过放弃特定保证，从根源消除复杂性——删除产生复杂性的状态机，而非试图清理它们。

v5.1 在此基础上：**用调研证据校准删除范围与依赖关系**，避免"文档说删、代码删不动"的落地风险。

---

## 1. 用户确认的约束

### 1.1 保留（v5-lean 标注，v5.1 复核）

| 特性 | 理由 | v5.1 备注 |
|---|---|---|
| **token stream**（slimapi 聚合） | 核心流量优化 + 防闪烁 | 保留；Phase B（`feature-removal-assessment-webalign.md`）会评估"是否也可精简"——opencode TUI 直接消费原始 delta 不聚合 |
| **TOFU 证书钉扎** | mtls 功能依赖 | 保留；Phase B 评估 web 客户端等价需求 |
| **AuthorityReducer 纯函数** | P0-A 确认的正确架构 | 保留；本版修正字段数（5≠4，见 §3.2） |
| **历史持久化** | 已无（当前就没有） | 复核确认：无 Room/DataStore 存会话消息；token 流无磁盘缓存 |

### 1.2 精简（v5-lean 确认，v5.1 沿用）

| 特性 | 精简方式 |
|---|---|
| 后台 SSE + 通知 | app 进后台 = 断 SSE；返回 = 冷启动恢复 |
| optimistic claim | POST 后等 SSE session.status（~250ms） |
| 会话列表实时更新 | 定时刷新（如每 30s） |
| multi-host/profile 并发 | 单 host 连接 |
| 后台 token stream | app 进后台 = 断 token stream |

> **注**：上述"精简"清单是 v5-lean 的产品决策。Phase B 文档会进一步评估"能否更激进地向 opencode web/TUI 范式靠拢"。

---

## 2. replay 评估【v5.1 重大修正】

### 2.1 v5-lean 原结论（已被证伪部分）

v5-lean §2 称："slimapi catch-all 透传 `GET /session/{sid}/event?after=N` 到 opencode V2，exp-3 确认上游支持 `after` replay"。

### 2.2 slimapi 侧透传隔离：CONFIRMED（无变化）

slimapi 的 catch-all proxy（`proxy.py:160-217`）确实把不匹配 `/slimapi/*` 的请求透传给上游 opencode：
- `proxy.py:166` — query params 透传（`params=request.query_params.multi_items()`）
- `proxy.py:170` — SSE 路径检测（`is_sse = norm_path in {"/event", "/global/event"}`）
- `proxy.py:165` — 路径标准化后透传
- **proxy.py 零 import 任何 hub 模块**——透传隔离属实，不碰 digest 去抖（`/slimapi/events` 专属）也不碰 token 聚合（`/slimapi/sessions/{sid}/stream` 专属）

### 2.3 上游端点：证伪 ❌

**opencode v1.18.9 无 `GET /session/{sid}/event?after=` 端点（返回 404）**。v5-lean §2 引"exp-3 确认上游支持 after replay"的依据失效。真实可用的 replay 入口：

| 端点 | 方法 | 用途 | ocdroid 是否可直接用 |
|---|---|---|---|
| `/sync/history` | POST | body 是 `{aggregateID → lastKnownSeq}` map，返回所有 `seq > lastKnownSeq` 的事件（差量补齐） | ✅ 可用 |
| `/sync/replay` | POST | 写侧重放（session 在 workspace 间迁移，校验+提交到新 store） | ❌ 不适用（写侧，非读侧恢复） |
| `/api/session/:id/event?after=N` | GET | per-session 重放（先读 DB 历史再切实时） | ⚠️ opencode 内部有此端点，但**不在 slimapi is_sse 集合**（见 §2.4） |
| `EventV2.durable({aggregateID, after})` | 内部 API | 服务端机制，非 HTTP 暴露 | ❌ 需推动上游暴露 |

### 2.4 is_sse 集合缺漏（次要风险）

`proxy.py:170` 的 `is_sse` 集合仅含 `{"/event", "/global/event"}`。`/session/{sid}/event` 与 `/api/session/:id/event` **不在此集合** → 命中 30s read timeout（非 None），长连 replay SSE 可能被掐断。

**修复**：若启用长连 replay SSE，把对应路径补进 `is_sse` 集（1 行改动）。

### 2.5 v5.1 replay 结论

| 方案 | slimapi 改动 | ocdroid 改动 | 上游依赖 | 推荐 |
|---|---|---|---|---|
| **Plan A（V1 digest 恢复）** | 加回 `GET /slimapi/sessions/status`（v2 契约已删除，需恢复；**加性、不 bump wire 版本**）；扩展 `skeleton_session()` 含 busy/idle+turn+turnIncarnation | 改断线恢复走该端点 | 无 | ⭐ 推荐（最小风险） |
| **Plan B（V2 replay）** | 无需改 | 改走 `POST /sync/history`（按 seq pull） | 依赖上游 `/sync/history` 已存在 ✅ | 备选（事件格式更细但需扩 `SseEventBridge`） |

**v5.1 默认推荐 Plan A**：slimapi 加性改动小、不 bump wire 版本、ocdroid 侧走熟悉的 V1 digest 格式。Plan B 留作"需要细粒度事件恢复"时的备选。

### 2.6 replay 注意事项（沿用 v5-lean）

- V2 细粒度事件（`Step.Started/Ended/Failed`、`Text.Delta` 等）≠ V1 的 `session.status` digest 格式
- 走 Plan B 需扩展 `SseEventBridge` 处理 V2 事件格式
- replay 流量消耗 = 缺失期间事件量（断线到重连通常 < 30s，量很小）

---

## 3. 精简范围【v5.1 修正行数/字段数】

### 3.1 删除的状态机（整个移除）

| # | 状态机 | v5-lean 文档行数 | 实际行数 | 删除理由 | v5.1 判定 |
|---|---|---|---|---|---|
| 1 | **StreamingLifecycleCoordinator** L2/L3/L4 分支 | ~1000 | 1692（约 60% 可删） | 后台不保持 SSE → 无需 L2Active/L2Idle/BackgroundGrace/NoSourceTerminal | ✅ 可行 |
| 2 | **StreamingOwnershipGate** | ~664 | 664 | v5-lean 称"FGS 消失则无仲裁需求" | ⚠️ **不可整体删**（见 §3.3 定性修正） |
| 3 | **StreamingServiceLauncher** | ~282 | 282 | Stage-1/Stage-2 超时因 FGS 启动延迟 → 无 FGS 则直连 | ✅ 可行 |
| 4 | **BootstrapJobHolder** + epoch + handle-passing | ~100 | **34**（文档夸大 3 倍） | v0.18.13 加的 mutable-capture race 补偿层 | ✅ 可行（但收益远小于文档声称） |
| 5 | **SessionStreamingService** FGS 壳 | ~800 | 1466（约 55%） | 不需要后台 SSE → 不需要 FGS | ⚠️ **高风险**：8 个待删类的唯一汇聚点，删它 DI 全断，波及 20+ 非删除文件（Manifest/DI modules/ConnectionCoordinator/HealthProbe/SseEventBridge...） |
| 6 | **SessionStreamingController** | ~480 | 480 | bootstrap 重试循环 + command 分发 | ✅ 可行 |
| 7 | **OptimisticClaimWatchdogCoordinator** | ~209 | 209 | SSE 在线时直接信任 session.status | ✅ 可行 |
| 8 | **ProcessStatusPoller**（SSE 降级 fallback） | ~640 | 640 | 替换为定时刷新 | ⚠️ **有风险**：双职责（bulk polling + slim fan-out），删会丢 slim fan-out（见 §3.4） |

### 3.2 精简但保留的状态机（修正字段数）

| # | 状态机 | 当前行数 | v5-lean 精简后 | v5.1 修正后 | 修正点 |
|---|---|---|---|---|---|
| 9 | **ServiceSseConnectionOwner** | 1471 | ~400（删 9 个 @Volatile） | ~400（删 **5 个** @Volatile） | v5-lean 称 9 个，实际 **5 个**：`transportGenerationCounter` / `closingGeneration` / `resyncDirtyForGen` / `resyncInFlightForGen` + `activeAttempt` |
| 10 | **TokenStreamCoordinator** | 1900 | ~800 | ~800 | 门控逻辑 ~450 行可切，无字段数偏差 |
| 11 | **AuthorityReducer** | 1092 | ~600 | ~600 | v5-lean 称 OptimisticClaim 4 字段，实际 **5 字段**（多 `reconcileConfirmed`） |
| 12 | **AuthorityState** | ~250（实际 236） | ~150 | **~212** | v5-lean 称精简后 150，实际精简后 ~212（删除空间比文档小） |

### 3.3 OwnershipGate 定性修正【关键】

**v5-lean 原判断**：FGS 消失 → 无 SSE 仲裁需求 → 整体删除 ~664 行。

**v5.1 修正**：**不可整体删除**。理由：
1. opencode 服务端是**纯 fanout 无连接级 ownership**——server 不管谁是 primary，只广播。
2. 但只要有 SSE 连接（即使前台单连接），客户端仍可能遇到并发持有场景：foreground 重连窗口、token stream 与 status SSE 双通道、配置切换时的旧连接未释放。
3. opencode 的设计哲学是"server 不管 ownership，客户端自己仲裁"——这恰恰说明 **ocdroid 需要一个纯客户端的 OwnershipGate**，只是规模可比现在小。

**v5.1 处置**：精简保留为纯客户端实现（仲裁哪个连接是 primary SSE），删除与 FGS bootstrap 绑定的部分。预期从 664 行精简到 ~200-300 行，**而非整体删除**。

> **决策点（待用户确认）**：精简后是否还有"前台 + 后台同时持有 SSE"场景？若 v5-lean 彻底放弃后台 SSE，则并发持有场景大幅减少，OwnershipGate 可极度精简（但非零）。

### 3.4 ProcessStatusPoller 双职责【文档未记】

v5-lean 称 ProcessStatusPoller 仅做"SSE 降级 fallback bulk polling"。实际它有**双职责**：
- (a) **bulk polling**：给 ConnectionCoordinator 提供 status 快照
- (b) **slim fan-out**：把轮询结果扇出到 slimapi 订阅者

**v5.1 处置**：替换方案必须**同时覆盖 slim fan-out**，否则 slimapi 订阅者收不到 status 更新。非简单删除。

### 3.5 不变的状态机（修正行数）

| # | 状态机 | v5-lean 行数 | 实际行数 | 理由 |
|---|---|---|---|---|
| 13 | **ConnectionPhase** + isSseDown | ~160 | **~63**（纯 sealed class） | UI 层，设计良好；v5-lean 夸大 2.5 倍 |
| 14 | **BannerHysteresis** | ~130 | ~130 | UI 层，纯 reducer |
| 15 | **ConnectionHealthProbe**（含 TOFU） | ~900 | ~900 | mtls/TOFU 安全特性 |
| 16 | **SseEventBridge** | ~300 | ~300 | 走 Plan B 时需扩展处理 V2 事件格式 |

### 3.6 行数估算（修正后）

| | 当前 | v5-lean 精简后 | v5.1 修正后 | 说明 |
|---|---|---|---|---|
| 核心状态机总行数 | ~12000 | ~4000-5000 | **~5000-6000** | OwnershipGate 保留 ~200-300 行；AuthorityState 多 ~60 行；BootstrapJobHolder 删得少 |
| 🔴 高风险项 | 3 个 | 0-1 个 | 1-2 个 | SessionStreamingService 删除仍是高风险；OwnershipGate 精简需谨慎 |
| @Volatile 非原子字段 | 9 个 | 0-2 个 | **2 个**（ServiceSseConnectionOwner 精简后） | 字段数本就 5 非 9，精简后剩 0-2 |
| 状态机数量 | 12（+子状态机） | 6 | **7** | OwnershipGate 不删，归入"精简保留" |

**净收益仍显著**（~50-58% 行数削减），但比 v5-lean 声称的 -58%~-67% 更保守、更可落地。

---

## 4. 精简后的数据流【v5.1 修正 Plan A 端点】

### 4.1 正常运行（前台）（沿用，无变化）

```
app 启动 / 从后台返回
  → testConnection()（REST 健康检查 + TOFU 证书验证）
  → REST GET /slimapi/sessions（全量会话列表）  ← v5.1 注：不含 status 字段
  → SSE 连接 /slimapi/events（前台保持）
  → token stream /slimapi/sessions/{sid}/stream（当前会话的 token 流）

session.status 更新：
  → 收到 session.status digest（busy/idle + turnIncarnation + turn）
  → 直接写 AuthorityReducer.applyEvent(SSE_SLIM)
  → 不经过 optimistic claim 协调
  → serverRound (incarnation, turn) 字典序 fence 保留

发消息：
  → POST prompt_async
  → 等 SSE session.status busy 到达（slimapi digest ~250ms）
  → UI 显示 busy（等 ~250ms，可接受）

收 token：
  → token stream 推送聚合后的 part delta
  → TokenStreamFrameProcessor 处理 → UI 逐字渲染

会话列表更新：
  → 定时刷新（每 30s REST GET /slimapi/sessions）+ ProcessStatusPoller slim fan-out
```

### 4.2 断线恢复（前台时）【v5.1 修正】

```
SSE 断开（网络抖动）
  → 检测到（心跳超时 10s 或连接错误）
  → 重连 /slimapi/events
  → 重连成功后：
    方案 A（V1 digest 模式，v5.1 推荐）：
      → REST GET /slimapi/sessions/status  ← v5.1：此端点当前不存在，需 slimapi 加回
      → applySnapshot 直接信任（无 optimistic claim 保护窗口）
      → 恢复实时 SSE digest 流
    方案 B（V2 replay 模式，备选）：
      → POST /sync/history  ← v5.1：不是 GET ?after=，是 POST + body map
      → body: {sessionId: lastSeenSeq, ...}
      → replay 缺失的 V2 事件（需扩 SseEventBridge）
      → 恢复实时 SSE digest 流
```

### 4.3 后台/返回（沿用，无变化）

```
app 进后台：
  → SSE 断开（不保持）
  → token stream 断开
  → 无后台通知（用户需手动打开 app）

app 返回前台：
  → 冷启动恢复（同"app 启动"流程）
  → 全量拉取会话列表 + 重连 SSE
```

---

## 5. 与 v4 方案的关系（沿用 v5-lean）

| v4 阶段 | v5.1 状态 | 理由 |
|---|---|---|
| **S1**（TransportLease） | ❌ 不需要 | ServiceSseConnectionOwner 精简后 @Volatile 从 5 降到 0-2 |
| **S2**（BootstrapAttempt） | ❌ 不需要 | 无 FGS bootstrap → 无 epoch/job/abort 管理（且 BootstrapJobHolder 实际仅 34 行，本就小） |
| **S3**（TokenStream 分解） | ⚠️ 简化版仍需要 | 删除 foreground/route 门控，token 聚合逻辑保留 |
| **S4**（StartingLease） | ⚠️ 简化版仍需要 | OwnershipGate 精简保留（非整体删），Stage-2 超时逻辑可大幅简化 |

**v5.1 比 v4 简单得多**——删除复杂性来源（FGS + 后台 SSE + optimistic claim），而非在保留前提下做并发清理。v4 的 8 轮评审痛点（TransportLease linearization / StartingLease timer / BootstrapAttempt supersession）**基本消失**，唯 OwnershipGate 精简仍需小心。

---

## 6. 实施路线图【v5.1 修正依赖链】

### 6.1 v5-lean 原依赖链（多数不成立）

v5-lean 推荐 `L1→L2→L3→L4→L5→L6` 严格串行。调研证实多数依赖关系**不成立**。

### 6.2 v5.1 修正后依赖链

```
L1(删FGS)→L2(简化Owner)→L6(replay)
   ├→L4(简化TSC)        [前置实际是 L1，非 L2/L3]
   ├→L5(替换Poller)     [前置实际是 L1，非 L3；须覆盖 slim fan-out]
   └ L3(简化Reducer)    [独立，无前置，可先行]
```

### 6.3 修正后的阶段表

| 阶段 | 内容 | 风险 | 收益 | v5.1 前置 | 可并发？ |
|---|---|---|---|---|---|
| **L3** | 简化 AuthorityReducer（删 optimistic claim） | 中 | 删 ~500 行 + 消除 watchdog | **无**（独立） | ✅ 可最先做 |
| **L1** | 删除 FGS + 后台 SSE | **高**（波及 20+ 文件） | 删 ~3500 行 + 7 个状态机 | 无 | 与 L3 可并发 |
| **L2** | 简化 ServiceSseConnectionOwner（删 @Volatile/重连代次） | 中 | 删 ~1000 行 + 5 个 @Volatile 降到 0-2 | L1 | — |
| **L4** | 简化 TokenStreamCoordinator（删 foreground/route） | 中 | 删 ~1100 行 | **L1**（非 L2/L3） | L2 后或与 L2 并发（写域不同文件） |
| **L5** | 替换 ProcessStatusPoller 为定时刷新 | 低-中 | 删 ~640 行 + 简化轮询；**须覆盖 slim fan-out** | **L1**（非 L3） | L2 后 |
| **L6**（可选） | replay 集成 | 低-中 | 断线恢复更精确 | L2 | — |
| ** OwnershipGate 精简**（v5.1 新增独立项） | 删 FGS 绑定部分，保留纯客户端仲裁 | 中 | 664 → ~200-300 行 | L1 | L2 后 |

### 6.4 推荐执行顺序

**第一批（可立即并行）**：
- L3（独立，无前置）— 风险中、收益清晰、不阻塞其它
- L1（高风险但收益最大）— 启动后解锁 L2/L4/L5/OwnershipGate

**第二批（L1 完成后）**：
- L2（简化 Owner）
- L4（简化 TSC，与 L2 写域不同可并行）
- OwnershipGate 精简（与 L2 强相关，串行）

**第三批**：
- L5（替换 Poller，须覆盖 slim fan-out）
- L6（replay，Plan A 优先）

每个阶段独立可发版。

---

## 7. 不做什么（明确排除）

- **不改 slimapi wire 版本**（Plan A 是加性端点，不 bump）
- **不改 token stream 聚合**（核心流量优化，保留；Phase B 会重新评估）
- **不改 TOFU/mtls**（安全特性，保留）
- **不删 AuthorityReducer**（纯函数架构正确，精简但不删除）
- **不删 ConnectionPhase/BannerHysteresis**（UI 层，设计良好）
- **不整体删 OwnershipGate**（v5.1 修正：精简保留为纯客户端实现）

---

## 8. 风险评估【v5.1 增 OwnershipGate 项】

| 风险 | 严重度 | 缓解 |
|---|---|---|
| 用户后台时不收通知 | **产品决策**（用户已确认接受） | 文档标注"不支持后台通知" |
| POST 后 UI 延迟 ~250ms 显示 busy | 低 | 可加极简 loading 指示（非 optimistic claim，纯 UI） |
| 定时刷新会话列表（30s）不如实时 | 低 | 用户可手动下拉刷新 |
| **L1 删除 FGS 影响面大**（波及 20+ 文件） | **高** | 分步：(1) 停后台 SSE 保持 → (2) 删 FGS 壳 → (3) ServiceSseConnectionOwner 迁宿主到 AppCore/CC → (4) 精简 OwnershipGate |
| 单 host 连接 | 低 | 切换 host = 冷启动恢复 |
| **OwnershipGate 精简误删仲裁逻辑** | 中 | 先确认精简后并发持有场景；保留纯客户端 primary 仲裁 |
| **ProcessStatusPoller 替换丢 slim fan-out** | 中 | 替换方案必须覆盖 slim fan-out（非简单删除） |
| **Plan A 依赖 slimapi 加端点**（跨项目协作） | 低 | 加性改动、不 bump wire 版本；与 slimapi 维护者协调 |

---

## 9. opencode 设计借鉴专章【v5.1 新增】

> 来源：`opencode-fsm-study-full.md` / `opencode-fsm-study-summary.md`（4 explorer 并行调研 opencode v1.18.9 源码）

### 9.1 核心洞察

opencode **几乎不用显式 FSM**：
- server 侧仅 1 个 4 态 FSM（`Runner`: Idle/Running/Shell/ShellThenRun，`effect/runner.ts:33-37`）
- 客户端 TUI 用 SolidJS store + SSE while-loop，**零显式 FSM**
- 用「事件流 → reactive store 折叠」取代「状态枚举 + 迁移表」
- 对比：ocdroid 12 FSM / ~12000 行 vs opencode TUI 核心 ~1500 行

### 9.2 可借鉴模式（按价值排序）

#### 🟢 高价值，可直接采纳

**模式 1：事件折叠 / reducer-over-events 替代多 FSM**
- opencode：TUI 单个 SolidJS store + 一个 `handleEvent()` switch 处理所有事件，`setStore()` 更新。范例 `packages/tui/src/context/sync.tsx:170-440`
- ocdroid 应用：Kotlin `StateFlow<AppState> + Flow<Event>.scan(initialState, reducer)`，把会话列表/状态/消息 FSM 合并为 1 个 reducer
- 预期收益：砍 2-3 个 FSM

**模式 2：durable event + after 游标做断线恢复**
- opencode：服务端 `EventV2.durable({aggregateID, after})`（`core/event.ts:585-604`）返回 `seq > after` 的历史事件 Stream，再切实时
- ocdroid 应用：客户端持久化 `lastSeenSeq` per session，断线重连带 `after=lastSeenSeq`；对应 v5.1 Plan B
- 预期收益：替代自建复杂 replay FSM

**模式 3：SynchronizedRef 原子转换替代显式锁**
- opencode：`Runner` 用 Effect `SynchronizedRef.modifyEffect` 做原子 read-compute-write（`runner.ts:115-202`）
- ocdroid 应用：Kotlin 等价 `Mutex.withLock { ... }` 或 `AtomicReference.compareAndSet`，把 turn/state 转换收敛到一个原子原语

**模式 4：single-flight Runner 取代 turn/incarnation**
- opencode：无 turn 计数器/incarnation ID。一个 `Runner` per session，`ensureRunning` join-or-launch，`cancel` 中断 Fiber。4 态
- ocdroid 应用：把复杂的 turn/incarnation FSM 简化为 4 态 Runner + 取消

**模式 5：内部执行 FSM 与对外状态投影解耦**
- opencode：Runner（4 态）是内部执行细节；SessionStatus（3 态：idle/busy/retry）是对外投影。通过 `onIdle`/`onBusy` 回调桥接（`session/run-state.ts:35-69`）
- ocdroid 应用：把"执行细节状态"和"UI 可见状态"分两层。UI 只需 idle/busy/retry

#### 🟡 中价值，视场景采纳

**模式 6**：HTTP 端点职责分离（live SSE vs replay POST）— ocdroid 连接 opencode server，端点已固定，更多是理解而非采纳
**模式 7**：指数退避重连（简单 while-loop，非 FSM）— `sdk.tsx:82-117` 范例，可简化 SSE 连接 FSM

#### 🔴 不可移植

- Effect TS 框架（SynchronizedRef/Deferred/Fiber/Layer/Queue/PubSub）— Kotlin 无直接等价，需 Coroutines+Flow+Mutex 重组
- 单进程 Node.js 假设 — Android 多进程组件不同
- 无后台/通知设计 — 问题域不同
- 无 multi-host 设计 — opencode 没解决

### 9.3 关键源码引用速查

| 主题 | 文件:行号 |
|---|---|
| Runner FSM 定义 | `opencode/src/effect/runner.ts:33-37` |
| Runner ensureRunning / cancel | `opencode/src/effect/runner.ts:115-138 / 171-202` |
| SessionStatus 3 态 | `packages/schema/src/session-status-event.ts:9-32` |
| SessionRunState 桥接 | `opencode/src/session/run-state.ts:35-69` |
| POST prompt 同步路径 | `opencode/src/session/prompt.ts:1052-1071`（busy 在 line 1089 同步发射） |
| promptAsync fork（有空窗） | `opencode/src/server/routes/instance/httpapi/handlers/session.ts:311-329` |
| EventV2 durable 流 | `packages/core/src/event.ts:585-604` |
| sync history handler | `opencode/src/server/routes/instance/httpapi/handlers/sync.ts:72-85` |
| TUI SSE 重连（while-loop） | `packages/tui/src/context/sdk.tsx:82-117` |
| TUI 事件折叠 switch | `packages/tui/src/context/sync.tsx:170-440` |
| 服务端纯 fanout（listeners 数组） | `packages/core/src/event.ts:181`（notify 406-417） |

---

## 10. ocdroid vs opencode 问题域对照【v5.1 新增】

### 10.1 6 个核心问题的存在性对照

| # | ocdroid 问题 | opencode 是否存在 | 原因 |
|---|---|---|---|
| 1 | 后台 SSE + 通知 | **N/A** | opencode 是桌面/TUI，服务器零感知前后台/FCM |
| 2 | optimistic claim（POST→SSE ~250ms 空窗） | 仅 promptAsync fork 有 | 同步 prompt 路径无空窗（busy 在 LLM 流前同步发射） |
| 3 | 会话列表实时更新 | 存在（但解法不同） | SSE 全局流 + SolidJS store switch 折叠，无 FSM |
| 4 | multi-host 并发 | **N/A** | 单 baseUrl，单服务器连接 |
| 5 | replay 断线恢复 | 服务端有机制，TUI 不用 | TUI 靠重连+bootstrap，不 replay |
| 6 | OwnershipGate 仲裁 | **N/A** | server 纯 fanout 无连接级 ownership |

### 10.2 根本结论

**ocdroid 的 6 个问题中 4 个在 opencode 根本不存在**——因为架构前提不同：
- 单进程 Node.js（无多连接争用）
- 桌面/TUI 客户端（无后台/通知）
- 单服务器连接（无 multi-host）
- 同步 POST prompt（无 optimistic 空窗，主路径）

**ocdroid 的问题是 Android 移动客户端 + 多连接场景特有的**。opencode 没有解决这些问题——而是没有遇到它们。

**因此**：opencode 提供的是**设计哲学参考**（事件折叠、durable+after、内外状态解耦），**不是现成解法**。ocdroid 不能照搬 opencode 架构，但可以借鉴其"用 reducer 替代 FSM"的范式简化自己的状态机层。

### 10.3 对照表对 v5.1 决策的影响

| 问题 | v5.1 处置 |
|---|---|
| 后台/通知 | 沿用 v5-lean 产品决策（放弃）；可用 durable+after 兜底替代通知驱动恢复 |
| optimistic claim | 沿用 v5-lean 删除；借鉴 SessionStatus 3 态投影简化 UI 层 |
| 会话列表更新 | **最高价值借鉴**：评估 `Flow.scan` 替代专门 FSM（Phase B 评估项） |
| multi-host | 沿用 v5-lean 单 host（Phase B 评估是否更激进） |
| replay | Plan A/B（见 §2.5） |
| OwnershipGate | 精简保留为纯客户端实现（见 §3.3） |

---

## 11. v5.1 修正项 Checklist（13 条逐条核对）

| # | 修正项 | 来源 | v5.1 处理位置 | 状态 |
|---|---|---|---|---|
| 1 | BootstrapJobHolder 实际 34 行≠文档 100，且无 epoch/handle-passing（在 Service 侧） | ocdroid 可行性报告 | §3.1 #4 | ✅ |
| 2 | ServiceSseConnectionOwner @Volatile 实际 5 个≠文档 9（transportGenerationCounter/closingGeneration/resyncDirtyForGen/resyncInFlightForGen + activeAttempt） | ocdroid 可行性报告 | §3.2 #9 | ✅ |
| 3 | OptimisticClaim 实际 5 字段≠文档 4（多 reconcileConfirmed） | ocdroid 可行性报告 | §3.2 #11 | ✅ |
| 4 | AuthorityState 精简后 ~212≠文档 150 | ocdroid 可行性报告 | §3.2 #12 | ✅ |
| 5 | ConnectionPhase ~63≠文档 160（纯 sealed class） | ocdroid 可行性报告 | §3.5 #13 | ✅ |
| 6 | §2 "上游支持 after replay" 证伪：opencode v1.18.9 无 `GET /session/{sid}/event?after=`（404）；真实 replay 走 `POST /sync/history` 或 `/sync/replay` | slimapi 影响报告 | §2.3 | ✅ |
| 7 | §4 Plan A 的 `GET /slimapi/sessions/status` v2 契约已删除（当前不存在）；`GET /slimapi/sessions` 不含 status 字段 | slimapi 影响报告 | §2.5 / §4.1 | ✅ |
| 8 | ProcessStatusPoller 双职责（bulk polling + slim fan-out），文档未记 | ocdroid 可行性报告 | §3.4 / §6.3 L5 | ✅ |
| 9 | OwnershipGate 定性修正：不可整体删除，精简保留为纯客户端实现 | ocdroid 可行性报告 + opencode 调研 | §3.3 / §6.3 / §7 / §8 | ✅ |
| 10 | replay 路径修正：catch-all 透传隔离属实，但上游端点路径与文档不同；Plan A 需 slimapi 加回端点，Plan B 需 ocdroid 改走 POST /sync/history | slimapi 影响报告 | §2.3-2.5 | ✅ |
| 11 | 依赖链修正（原 L1→L2→L3→L4→L5→L6 多数不成立）：L3 独立、L4/L5 前置是 L1 | ocdroid 可行性报告 | §6.2-6.3 | ✅ |
| 12 | opencode 可借鉴模式（reducer-over-events / durable+after / single-flight Runner / 内外状态解耦） | opencode 调研 | §9 | ✅ |
| 13 | opencode 对照结论：6 问题中 4 个在 opencode 不存在——ocdroid 问题是 Android 移动端+多连接特有，opencode 提供设计哲学参考而非现成解法 | opencode 调研 | §10 | ✅ |

**命中数：13/13 ✅**

---

## 12. 相关文档

- `docs/specs/state-machine-simplification-v5-lean.md` — v5-lean 原稿（保留不动作为历史）
- `docs/specs/state-machine-simplification-v4.md` — v4 方案（8 轮评审内部并发清理）
- `docs/specs/state-machine-architecture-spec.md` — 现有状态机架构规范（P0-A/B/C 落地后）
- `docs/specs/sse-client-spec.md` — SSE 客户端设计
- `docs/specs/l4-sse-lifecycle-design.md` — L4 SSE 生命周期设计（v5.1 中删除）
- `docs/specs/feature-removal-assessment-webalign.md` — **Phase B 评估文档**（向 opencode web 版靠拢的功能移除评估）
- `.omni-orch/reports/v5lean-feasibility-ocdroid.md` — ocdroid 可行性调研报告
- `/home/mar/personal_projects/oc-slimapi/.omni-orch/reports/v5lean-feasibility-slimapi.md` — slimapi 影响评估（跨项目只读）
- `.omni-orch/reports/opencode-fsm-study-summary.md` / `opencode-fsm-study-full.md` — opencode 设计调研

---

## 13. 待用户决策点（汇总）

1. **Plan A vs Plan B（replay 恢复路径）**：v5.1 默认推荐 Plan A（slimapi 加性端点），需用户拍板
2. **OwnershipGate 精简程度**：精简后是否还有并发持有 SSE 场景？决定精简到 ~200 行还是 ~300 行
3. **L1 高风险删除分步策略**：是否按"停后台 SSE → 删 FGS 壳 → 迁宿主 → 精简 Gate"四步走
4. **Phase B 激进程度**：是否进一步向 opencode web 范式靠拢（见 `feature-removal-assessment-webalign.md`）
