# ocdroid-lite 激进方案 v2.7-final（执行计划）

> ⚠️ **本执行计划已完成（EXECUTED）**
>
> 此文档的 V2 目标已通过 bundle `bundle-slimv2-20260728` 落地执行——ocdroid 现运行于 `lite-v2-dev` 分支，使用 oc-slimapi V2 线协议。
>
> **本文档保留为历史设计/迁移记录**，其规划的各项删除（routeToken、contentRevisions/childrenVersion、`_part_state`、`X-Discovery-*`、`X-Message-Event-Seq`、Opt-A、BatchLedger 等）已在 V2 中完成。
> 当前权威线协议请见 oc-slimapi `docs/specs/v2-contract.md`。

> **状态**：Active v2.7-final / 已通过终审（kimi 有条件通过 + gpt 有条件通过）
> **范围**：ocdroid 客户端 + oc-slimapi sidecar 一次性协同重构
> **基线**：ocdroid v0.13.x、oc-slimapi v1 contract rev M（2026-07-27）
> **调研数据来源**：v0.2 调研 + 三评委四轮评审（v1.0-v2.3）+ 8 explorer 深挖 + rev-glm 两轮分析
> **工作模式**：双方在独立分支并行开发 → 双方测试全绿 → 同时替换主干
>
> **v2.5 核心思路**（相对 v2.3 的范式转变）：
> - **放弃精确单条同步，改为权威窗口 skeleton reload + diff**：digest 任何变化 → reload skeleton（高频 50 条 / 低频 200 条）→ 权威窗口 diff 一次搞定新增/更新/删除/part 变化
> - **SlimFullReconciler 完全删除**（906 行 → 0）：done:true → reload skeleton（不再走单条 /full）；/full/{mid} 降级为纯用户按需展开
> - **SlimCommitToken 完全删除**：stale 保护靠 reloadGeneration CAS + 现有 chatRouteInstance/currentSessionId/serverGroupFp 三重 fence（host 切换 = 重启，无需额外保护）
> - **同步路径从 5 条收敛为 1 条**：digest 变化 → reload skeleton + 权威窗口 diff
> - **sidecar 仍需 bump updatedAt**（~2 行）：使 part.updated 触发 digest 变化
> - **窗口分级**：高频短周期（digest 变化 / done:true / resync）拉 50 条；低频长周期（busy→idle / 冷启动 / 用户打开）拉 200 条
> - **200 条以外手动分页**：用户滚到历史区域 → `?before={cursor}` 按需拉取
>
> **v2.5 相对 v2.4 的修补**（第五轮复评结果）：
> - **§4.3 彻底重写 merge 规格**：权威窗口三分区（olderKept + fetched + newerKept）；新增 `serverSeenIds` 集合区分"本地注入"与"服务器删除"；保留全部 4 个历史守卫（placeholder/overlay/streamOwned/streamingFinalized）；新增展开 part 保护
> - **失败收敛协议**：reload 失败不再静默——3 层兜底（失败标记 + digest 重试 + 15s watchdog）
> - **trailing debounce**：等 in-flight reload 完成再发下一个，替代固定 250ms（消除高 RTT 下的饥饿）
> - **fence 论证**：调研确认 chatRouteInstance CAS + currentSessionId 全链路校验 + sidecar 单进程同步更新已覆盖 gpt 的 A→B→A / session 切换 / read-after-event 担忧
> - **流量估算修正**：worst case 可能 10-30 倍（非 2-5 倍）；典型 1-10 倍
>
> **v2.6 核心修补**（相对 v2.5 的第六轮复评结果——§4.3 承重墙重铸）：
> - **serverSeenIds → locallyInjectedIds 反转**：追踪小闭集（本地注入消息 id），而非大开集（服务器见过的所有 id）。修复 v2.5 的空页清空 transcript、冷启动历史翻到底部等问题
> - **desiredEpoch 替代 generation CAS**：每次触发（digest/done/resync/idle）都递增 epoch（不只是 launch 时），在途响应只有在 epoch 未变时才提交——新事件到达即时失效在途响应，比 generation CAS 更强
> - **ReloadIdentity launch 时一次性捕获**：serverGroupFp + sessionId + routeInstance 在请求发起时一次性捕获，dispatch 原样携带捕获值（防 A₁→B→A₂ 的 token laundering）
> - **expandedPartIds → 复用 partExpandStates**：按位置就地替换（不破坏顺序、不阻止删除），而非 v2.5 的"局部保留再拼接"
> - **补齐 dispatch 3 字段**：authoritative / streamingReasoningPart / currentModel（v2.5 漏）
> - **CancellationException 处理 + stateMutex 线程安全 + 后台 session 前置 gate + 空页零权威 + 孤儿键清理**
> - **read-after-event 调研确认**：skeleton 端点每次 re-GET upstream opencode（不读 sidecar 内存）；一致性依赖 opencode "SSE emit happens-after persistence" 假设；busy→idle 200 条兜底覆盖偶发不一致
> - **工作量修正**：v2.5 估算 ~120 行；v2.6 实际 ~200 行
>
> **v2.7 核心修补**（相对 v2.6 的第七轮复评结果——§4.3 正确性收口）：
> - **补集分区 + 空页早退**（P0）：v2.6 把 `isServerDeleted` 从 `>` 改成 `>=` 却没同步改分区 filter——空页时 `oldestFetched == null` → `olderKept` 为空 → `mergedMessages` 只剩本地注入 → transcript 清空。v2.7 改为：空页在 merge 入口直接 `return`（零权威，不 merge 不 dispatch）；`isServerDeleted` 用严格 `created > oldest`（闭合 tie 重叠）+ `created >= newestFetched → 永不删`；分区改为**补集式**（`survivors` 先算，`newerKept` = `notFetched - olderKept`，兜住 null-created / injected / 一切剩余，绝不丢人）
> - **markLocallyInjected 改同步**：`scope.launch { withLock }` 是异步的，SSE shell 注入与打标之间有时序窗口 → 用 `ConcurrentHashMap` + `newKeySet()`，打标方法同步无锁
> - **失败路径热循环修复**：v2.6 的 `catch` 恢复 `pendingLimit` + `finally` 见 `pendingLimit > 0` 立即 `launchNextReload` = 无延迟死循环。改为 catch 不动 `pendingLimit`（重试全部归 watchdog），`finally` 加 `!failed` 门
> - **watchdog 退避真正生效**：`attempt` 从局部变量提到 `ReloadState.retryAttempt`（跨 arm 持久化）；已 armed 不重复 arm（不重置退避）；恢复即 `break` 退出循环（v2.6 的 `return@withLock` 只退出 lambda）
> - **stateMutex 一致性**：锁序固定 `sessionLock → stateMutex`（单向，无环）；epoch 校验 + merge + 成功簿记全部在 `stateMutex` 内（v2.6 成功路径与 merge 在锁外读 `locallyInjected`）
> - **livePartIds 白名单 → deadMsgIds 黑名单**：白名单会误杀 `streamOwned == STREAMING` 且尚未持久化的 part（不在 `mergedParts` → 被剪掉）。改为只剪"已删消息拥有的 part"
> - **编译修复 + O(n²) 修复**：`lp` 可空导致 `lp.isTruncatedMarker()` 编译不过；`mergedParts = mergedParts + (…)` 在循环内每轮全量复制 → 改 `HashMap` 就地写入 + 显式 null 检查
> - **API 形状修复**：v2.6 §4.3.7 用了不存在的 `SlimapiMessagesPage` / `body.messages` / `apiProvider()` → 复用既有 `MessagesPage`（`data/repository/MessagesPage.kt:26`），`page.items` 元素为 `MessageWithParts`（`.info` / `.parts`）
> - **epoch 饥饿修复**：流式期间 digest 高频到达，v2.6 每个响应都被 epoch 丢弃 → 全程零提交。改为**提交只看身份，epoch 只决定"要不要再拉一轮"**（`created >= newestFetched → 永不删` 使提交稍旧窗口安全）
> - **captureIdentity 接收者修正 + merge 快照一致性**：`slices.routeInstanceFor(sessionId)`（不是 snapshot）；merge 函数开头一次性快照 `slices.chat.value`，全函数只读该快照
> - **onDigestChange 去重 + 删除 `updateSessionListProjection`**：后台 session 的 status 投影由 SlimSseHandler 消费 digest 的 status 字段分支处理（§4.5 已有），不需要在此重复
> - **skeleton 排序契约落字 + onSessionClosed 调用点落字**
> - **工作量修正**：v2.6 估算 ~200 行；v2.7 实际 ~260 行

---

## 0. TL;DR

**核心策略**：skeleton 足够便宜 → 放弃"精确拉变化的 message"，改为"digest 变化 → 权威窗口 skeleton reload + diff"。一条同步路径覆盖所有场景（新增/更新/删除/non-text part 变化/part.removed/终态文本）。

**核心数字**：
- sidecar 净删除：~450 行 + ~2 行新增（part 事件 bump updatedAt）
- 客户端净删除：**~6500 行** production（SlimSseStateMachine 1038 + SlimSseReducer 719 + MessageEventSeqWatermark 502 + **SlimFullReconciler 906**（全删）+ SlimSyncEngine 900 + ExpandBatchEngine 742 + SlimSessionReconciler 600 + SlimColdStartSnapshotApplier 400 + 其他）+ ~450 测试用例
- 客户端新增：reloadSkeletonPage **~260 行**（merge spec + desiredEpoch + ReloadIdentity + failure convergence + watchdog + trailing debounce + fetch 原语）+ locallyInjected 状态追踪
- 流量：worst case 比 v2.3 贵 10-30 倍（长 reasoning turn，每次 reload 重传累积文本）；典型 1-10 倍。250ms debounce + trailing debounce + 窗口分级控制
- wire 协议：**净减少 2 字段**（删 contentRevisions + childrenVersion，不加）
- 同步路径数：**1 条**（v2.3 是 5 条）

---

## 1. 决策依据

### 1.1 范式转变：为什么 skeleton reload 能替代精确同步

| 场景 | v2.3（精确单条） | v2.4（全量 reload + diff） | 等价性 |
|---|---|---|---|
| 新消息到达 | digest messageID 变化 → 单条 /full | reload 50 条 → diff 出新增 | ✅ |
| non-text part 变化 | contentRevisions → /full | reload 50 条 → diff tool output | ✅ |
| message 删除 | token stream message.removed | reload 50 条 → diff 发现消失 | ✅（窗口内） |
| part.removed | reconcileMessage → /full | reload 50 条 → diff part 列表减少 | ✅ |
| done:true 终态文本 | reconcileMessage → /full | reload 50 条 → text part 全文替换 overlay | ✅ |
| 网络窗口 stale | mutex + CAS（gpt 质疑不够） | reloadGeneration CAS（旧 reload 被拒绝） | ✅ 更强 |

**关键洞察**：skeleton 包含 text/reasoning 全文 + tool/patch output 阈值内联。一次 reload 50 条就能覆盖几乎所有同步需求——不需要精确知道"哪条变了"。

### 1.2 窗口分级（用户提议）

| 场景 | limit | 理由 | 估算大小 |
|---|---|---|---|
| digest 变化（高频） | **50** | 短周期、高频、只需覆盖最近活跃消息 | ~12-125KB |
| done:true / resync | **50** | 只需当前活跃 message 的终态 | ~12-125KB |
| busy→idle（权威收敛） | **200** | 一次 agent turn 可能产生多条 message，全量覆盖 | ~50-500KB |
| 冷启动 / 用户打开 session | **200** | 初始全量加载 | ~50-500KB |
| 用户滚到历史区域 | **200/页** | 手动分页 `?before={cursor}` | ~50-500KB/页 |

### 1.3 流量估算

| 场景 | 频率 | 每次 | 小计 |
|---|---|---|---|
| agent turn 期间 digest 变化 | ~4Hz × 10-30s = 40-120 次 | reload 50 条 ~12-125KB | ~0.5-15MB/turn |
| busy→idle 收敛 | 1 次/turn | reload 200 条 ~50-500KB | ~50-500KB |
| 用户打开 session | 按需 | reload 200 条 | ~50-500KB |

> **注意**：skeleton 包含 text/reasoning **全文**——长 reasoning turn 中每次 reload-50 都会重传 streaming message 的累积文本（可达数十至数百 KB）。极端 case（120 次 reload × 125KB）≈ 15MB/turn，是当前 seq 路径（~500KB/turn）的 **~30 倍**。典型 case（短 turn + 短文本）≈ 0.5-5MB/turn，是 seq 路径的 1-10 倍。**这是已接受的 trade-off**——换取同步路径从 5 条→1 条的架构简化。
>
> trailing debounce（§4.3）确保同一 session 最多 1 个 in-flight reload，消除高 RTT 下的并发浪费。

---

## 2. 目标架构

### 2.1 单一形态

```
host 配置：单一 profile（slim: Boolean），指向 sidecar 或 opencode 直连
          切换 = 修改 profile + 重启

sidecar 路由：
  控制面 SSE   → /slimapi/events         （digest debounce，6 字段）
  流式 SSE     → /slimapi/sessions/{sid}/stream  （token stream，必需订阅）
  消息列表     → /slimapi/messages?mode=skeleton&limit={50|200}  （唯一同步路径）
  消息全文     → /slimapi/messages/{sid}/full/{mid}  （仅用户手动展开超阈值字段）
  会话列表     → /slimapi/sessions        （skeleton 投影）
  其他所有     → catch-all 透传

directory：统一 ?directory query（方案 B，sidecar 零改动）
版本：X-Slimapi-Version: 2
```

### 2.2 同步模型（最终版——极简）

```
单一同步路径：
  digest 任何字段变化（messageID / updatedAt / status）
    → requestReload(sid, limit=50)
    → desiredEpoch++（事件即时失效在途响应）
    → 权威窗口 diff：三分区 merge（olderKept + fetched + newerKept）
    → locallyInjected 更新 + 展开part保护（partExpandStates）+ 历史守卫
    → chat slice 状态更新

辅助路径：
  token stream delta → 实时逐 token 渲染（overlay，不写 chat slice）
  token stream done:true → reloadSkeletonPage(sid, limit=50)（终态文本收敛）
  token stream resync → reloadSkeletonPage(sid, limit=50)（断流恢复）+ scheduleReconnect
  token stream message.removed → MessageRemovedConfirmed（即时移除，不等 reload）
  status busy→idle → reloadSkeletonPage(sid, limit=200)（权威全量收敛）

手动路径：
  用户滚到 200 条之前 → GET /slimapi/messages?before={cursor}&limit=200（分页）
  用户展开超阈值 output → GET /slimapi/messages/{sid}/full/{mid}（按需）
```

**关键设计**：
- **唯一自动同步路径** = reloadSkeletonPage（~120 行，新的权威窗口 merge 算法，详见 §4.3）
- **reloadGeneration CAS**：per-session 递增计数器；reload 响应回来时校验 generation 仍是最新 → 旧响应被整体丢弃
- **多重视窗保护**（调研确认已有）：
  - `chatRouteInstance` CAS（`CrossSliceFieldsReducer.kt:606`）→ 防 A→B→A 路由重入
  - `currentSessionId` 全链路校验（10+ 处）→ 防 session 切换后旧 reload 写入
  - `serverGroupFp` 复合键检查 → 防 cross-group 误写
  - host 切换 = 重启 app（C2）→ 无需额外保护
- **read-after-event**：skeleton 端点每次 re-GET upstream opencode（`messages.py:574`），不读 sidecar 内存。一致性依赖 opencode "SSE emit happens-after persistence" 假设。偶发不一致由 busy→idle 200 条兜底覆盖。
- **trailing debounce**：同一 session 最多 1 个 in-flight reload，消除高 RTT 饥饿
- **失败收敛**：3 层兜底（失败标记 + digest 重试 + 15s watchdog），详见 §4.3
- **sessionLock**：复用现有 messageLoadCoordinator.withSessionLock 序列化同 session 写操作
- **无 SlimFullReconciler / SlimCommitToken / inFlightMutex / filterStreamingParts**

### 2.3 digest 帧字段集

```json
{
  "sessionID": "ses_...",
  "directory": "/path",
  "status": "busy|idle|retry",
  "messageID": "msg_...",
  "updatedAt": 1753000000000,
  "archived": 1753000000000,
  "deleted": true,
  "lastError": {"name","message","at"} | null
}
```

**删**：`contentRevisions`、`childrenVersion`
**不加**：不新增任何字段

### 2.4 目录协议（方案 B）

- sidecar：**零改动**
- 客户端：删 `X-Opencode-Skip-Dir`（~40 处）+ 停用 `DirectoryHeaderInterceptor.kt:66-87` 镜像逻辑

### 2.5 版本协商

- `X-Slimapi-Version`：客户端发送 `2`
- sidecar `versioning.py`：`accepted_client_versions = (2, 2)`
- `/slimapi/health` 响应暴露 `slimapi_contract: 2`
- **token stream gate**：v2 协议下 `tokenStreamEnabled = slimConnection`（不再 probe health `features.tokenStream`）

---

## 3. sidecar 改造清单（oc-slimapi）

### 3.1 端点删除（10 个）

| 端点 | 文件 | 处理 |
|---|---|---|
| `GET /slimapi/messages/{sid}/full?ids=` | `routes/messages.py` | 删 handler |
| `GET /slimapi/messages/{sid}/since/{ts}` | 同上 | 删 handler |
| `GET /slimapi/sessions/{sid}/children` | `routes/sessions_children.py` | **整个文件删** |
| `GET /slimapi/sessions/{sid}/status` | `routes/sessions.py` | 删 handler |
| `GET /slimapi/sessions/status` | 同上 | 删 handler |
| `GET /slimapi/questions` + `/permissions` | `routes/questions.py` | **整个文件删** |
| `POST /slimapi/questions/{qid}/reply` `/reject` | 同上 | 同上 |
| `POST /slimapi/sessions/{sid}/permissions/{pid}` | 同上 | 同上 |
| `GET /slimapi/projects` | `routes/sessions.py` | 删 handler + 删 import |

### 3.2 端点简化

| 端点 | 改动 |
|---|---|
| `GET /slimapi/messages/{sid}/full/{mid}` | 删 `?known.*` + 304 短路 + `X-Message-Event-Seq` 头 + `seq_pre`/`seq_post` 双采样；**降级为纯按需展开**（无自动同步路径调用它） |
| `GET /slimapi/messages/{sid}` | **删 `?mode=full` 列表分支**；统一 skeleton；保留 `?limit=` + `?before=` 分页 |

### 3.3 文件整体退役

| 文件 | 行数 | 依据 |
|---|---|---|
| `routes/questions.py` | ~185 | routeToken 全删 |
| `routes/sessions_children.py` | ~20 | 客户端零消费 |
| `tokens.py` | ~150 | routeToken HMAC 零引用 |
| `discovery.py` | ~200 | questions.py + /projects 删后零消费者 |
| `children_cache.py` | ~199 | 客户端走 legacy children |

### 3.4 digest 帧简化（hub.py）+ bump updatedAt

| 字段/代码 | 处理 | 行号 |
|---|---|---|
| `DigestFields.content_revisions` 声明 + to_payload | **删** | `hub.py:189-195, 215-218` |
| `DigestFields.children_version` 声明 + to_payload | **删** | `hub.py:186, 209-210` |
| `publish()` 中 contentRevisions 写入 + 清理 | **删** | `hub.py:982, 1030, 1056-1064, 1118-1128` |
| `publish()` 中 children_version 写入 | **删** | `hub.py:827-836` |
| `session.created` 分支 | **删整个分支** | `hub.py:826-836` |
| `GlobalHub._children_cache` 引用 | **删** | `hub.py:360, 1318-1357` |
| **MESSAGE_EVENTS 分支 updatedAt 统一** | **改为 `_now_ms()`** | `hub.py:845, 851` |
| **`message.part.updated` 分支新增 bump** | **新增 `entry.updated_at = _now_ms()`** | `hub.py:982` 附近 |
| **`message.part.removed` 分支新增 bump** | **新增 `entry.updated_at = _now_ms()`** | `hub.py:1030` 附近 |

> **updatedAt 语义**：所有 bump 统一用 `_now_ms()`（sidecar wall-clock）。digest.updatedAt = 「sidecar 最后观察到该 session 有变化的 wall-clock 时间」。客户端 strict `>` 单调比较触发 reload。250ms debounce 限频。
>
> **时钟回退处理**：sidecar 重启后 `_now_ms()` 可能低于客户端保存的旧 bookmark。客户端检测到 `digest.updatedAt < bookmark` 时，视为 sidecar 重启 → 清除 bookmark + 强制 reload（digest reset 协议）。实现：SSE 连接建立时 sidecar 在首个 digest 帧附加 `reset: true` 标记（或客户端检测 updatedAt 跳变为 0 / 回退）。

### 3.5 hub 状态简化 — **整体删除**

| 状态 | 处理 | 依据 |
|---|---|---|
| `_part_state` | **整体删** | 零消费者 |
| `_session_event_seq` | **整体删** | 驱动 contentRevisions，已删 |
| `_bump_message_seq()` | **整体删** | `hub.py:683-725` |
| `_bump_session_event_seq()` | **整体删** | `hub.py:635-653` |
| `get_part_fingerprint()` | **整体删** | `hub.py:598-633` |
| `_retired_messages` | **保留** | 防止 late `message.part.updated` 复活 token hub 状态（`hub.py:964` 直接守卫） |
| `publish()` 中 part 事件的 token hub 转发 | **保留** | `_token_hub.on_part_updated()` / `on_part_removed()` / `on_message_removed()` |
| `publish()` 中 `message.part.delta` 路由 | **保留**（完全独立） | `hub.py:1091-1094` |

### 3.6 G1 安全机制（保留不动）

- `sticky_last_error` / `deleted_tombstones` / `_sanitize_error_message` / `lastError` 三态

### 3.7 app.py 清理

| 字段 | 处理 |
|---|---|
| `route_secret` / `batch_ledger` | **删** |
| `directory_allowlist` / `allowlist_lock` / `allowlist_ready` | **删** |
| `warm_allowlist(app)` | **删** |
| `children`（ChildrenCache 实例） | **删** |
| `hubs.set_children_cache()` | **删** |
| `X-Discovery-Directories` 头 | **删** |
| `X-Discovery-Ready` 头 | **删** |

### 3.8 catch-all 透传（保留不动）

---

## 4. 客户端改造清单（ocdroid）

### 4.1 整个文件删除（~13 个）

| 文件 | 行数 |
|---|---|
| `data/repository/SlimSseStateMachine.kt` | 1038 |
| `data/repository/SlimSseReducer.kt` | 719 |
| `data/repository/MessageEventSeqWatermark.kt` | 502 |
| **`data/repository/SlimFullReconciler.kt`** | **906**（v2.4 新增全删，v2.3 是简化保留） |
| `data/repository/SlimSyncEngine.kt` | ~900 |
| `data/repository/ExpandBatchEngine.kt` | 742 |
| `ui/controller/SlimColdStartSnapshotApplier.kt` | ~400 |
| `ui/controller/SlimSessionReconciler.kt` | ~600 |
| `ui/controller/SlimResyncCadence.kt` | — |
| `ui/controller/SlimEffectsPort.kt` | — |
| `ui/controller/SlimOnlyStateWrite.kt` | — |
| `ui/controller/SlimQuestionLoader.kt` | — |
| `ui/controller/sse/ModeDomain.kt` | — |

### 4.2 简化保留

| 文件 | 改动 |
|---|---|
| `ui/controller/SessionSyncCoordinator.kt`（2348 行） | **删** SlimEffectsPort 实现 + SlimResyncCadence + SlimSessionReconciler 调用 + C2 active sweep + cold-start snapshot 应用；**保留** delta 合并 + 11 个 SSE 事件分支 + status aggregator + CAS 守卫 |
| `ui/controller/sse/TokenStreamCoordinator.kt` | **重接线**：TriggerSinceFetch → `reloadSkeletonPage(sid, limit=50)`；watchdog 超时 → reloadSkeletonPage + scheduleReconnect |
| `data/repository/TokenStreamReducer.kt` | **修改**：resync → reloadSkeletonPage；**新增** `done:true` 发射 `TriggerSkeletonReload(sid, limit=50)` |
| `di/ControllerModule.kt` | **4 处 hooks 清理**：(1) `dedupPartRevision` → 删（token hub per-frame revision 保证）；(2) `onMessagePartRemoved` → `reloadSkeletonPage(sid, 50)`（不再走 reconcileMessage）；(3) `onMessageRemoved` → 删 `captureSlimCommitToken` + `applyMessageRemoved`（直接 `dispatchMessageRemoved`）；(4) 删 SlimFullReconciler 的 `@Provides` |
| `data/repository/SlimAuthoritativeCommit.kt` | 删 `forceSlimDirty` 引用 |

### 4.3 新增：reloadSkeletonPage 设计（核心，~260 行）

> **v2.7 修补**（kimi + opus 第七轮复评结果，本节为最终形态）：
> - **P0 空页清空 transcript**：空页在 merge 入口早退；`isServerDeleted` 严格 `created > oldest` + `created >= newestFetched → 永不删`；分区改补集式（`newerKept` 兜住一切剩余）
> - **markLocallyInjected 同步化**（`ConcurrentHashMap` + `newKeySet()`，消除 SSE shell 注入与打标的时序窗口）
> - **失败路径热循环**：catch 不动 `pendingLimit`，重试归 watchdog；`finally` 加 `!failed` 门
> - **watchdog 退避持久化**（`ReloadState.retryAttempt`）+ 已 armed 不重复 arm + 恢复即 `break`
> - **stateMutex 一致性**：锁序 `sessionLock → stateMutex`；epoch 校验 + merge + 成功簿记全在 `stateMutex` 内
> - **原子提交与生命周期身份**：merge + dispatch + 簿记更新（locallyInjected 移除 / failed 清除 / watchdog disarm）
>   → 全部在同一 stateMutex 临界区内
>   → ReloadState 实例身份检查（current === ownerState）：session 关闭后重开创建新实例时，
>     旧 job 的 catch/finally 不污染新 state
>   → locallyInjected 容器操作线程安全（ConcurrentHashMap.newKeySet）；
>     删除判据保持保守（created >= newestFetched → 永不删）
> - **deadMsgIds 黑名单**替代 `livePartIds` 白名单（不误杀 streamOwned STREAMING 未持久化 part）
> - **编译修复**（`lp` 可空显式检查）+ **O(n²) 修复**（`HashMap` 就地写入）
> - **API 形状修复**（复用既有 `MessagesPage` / `MessageWithParts`）
> - **epoch 饥饿修复**：提交只看身份，epoch 只决定要不要再拉一轮
> - **captureIdentity 接收者**（`slices.routeInstanceFor`）+ **merge 快照一致性**（一次性读 `slices.chat.value`）
> - **onDigestChange 去重**，删除 `updateSessionListProjection` 调用
> - **skeleton 排序契约**（`time.created` 升序，tie 按 id）+ **onSessionClosed 调用点**落字
>
> **v2.6 修补**（三人第六轮复评结果，已被上述 v2.7 项覆盖修正的以 v2.7 为准）：
> - **serverSeenIds → locallyInjectedIds 反转**：追踪小闭集（本地注入消息），而非大开集（服务器见过的所有 id）。修复空页清空 transcript、冷启动历史翻到底部等问题。
> - **desiredEpoch 替代 generation CAS**：每次触发都递增 epoch（不只是 launch 时），在途响应只有在 epoch 未变时才提交。
> - **ReloadIdentity launch 时捕获**：serverGroupFp + routeInstance 在请求发起时一次性捕获，dispatch 原样携带。
> - **expandedPartIds → 复用 partExpandStates**：按位置就地替换，不破坏顺序，不阻止删除。
> - **补齐 dispatch 3 字段**：authoritative / streamingReasoningPart / currentModel。
> - **CancellationException 处理**：`catch (CE) { throw e }` 在 `catch (Exception)` 前。
> - **stateMutex 线程安全**：所有共享簿记在一把 Mutex 下 check-and-set。
> - **后台 session 前置 gate**：fetch 前检查 currentSessionId，避免无效请求。
> - **空页零权威**：空响应不清空 transcript。
> - **孤儿键清理**：删除后 mergedParts/streamingTexts 残留清除。
> - **read-after-event**：skeleton 端点每次 re-GET upstream opencode（已调研确认），不读 sidecar 内存。一致性依赖 opencode "SSE emit happens-after persistence" 假设；busy→idle 200 条兜底覆盖偶发不一致。

#### 4.3.1 状态追踪

```kotlin
// 所有共享簿记在一把 Mutex 下（check-and-set 必须在锁内）
// 锁序契约（v2.7）：sessionLock → stateMutex，单向，无环。
//   禁止在 launchNextReload / merge 里把 stateMutex 反向套在 sessionLock 外。
private val stateMutex = Mutex()

// ── per-session 状态（key = sessionId）──

// desiredEpoch：每次触发都递增（不只是 launch 时）。
//   v2.7：epoch 不再作为"提交门"（会饥饿），只决定"要不要再拉一轮"。
private val reloadStates = mutableMapOf<String, ReloadState>()

data class ReloadState(
    var desiredEpoch: Long = 0L,       // 每次触发递增
    var inFlight: Boolean = false,
    var pendingLimit: Int = 0,         // 排队的 reload limit
    var failed: Boolean = false,       // 失败标记（Layer A）
    var retryAttempt: Int = 0,         // v2.7：退避计数，跨 armWatchdog 持久化
)

// 本地注入消息 id（小闭集）。
//   只有两个写入点：launchSendMessage 乐观消息 + SseChatReducers assistant shell。
//   被服务器确认后（出现在 fetched 中）立即移除。
//   用于区分"本地注入"（保留）和"服务器删除"（移除）。
//   v2.7：ConcurrentHashMap —— markLocallyInjected 必须同步生效（见 §4.3.6），
//         否则 SSE assistant shell 注入与打标之间有时序窗口，
//         窗口内到达的 reload 会把尚未打标的 shell 判成"服务器删除"。
private val locallyInjected = ConcurrentHashMap<String, MutableSet<String>>()

// 15s watchdog（仅失败时 arm，成功时 disarm）
private val watchdogJobs = mutableMapOf<String, Job>()

// 在途 reload job（session close 时 cancelAndJoin，防止 detached state 回写）
private val reloadJobs = mutableMapOf<String, Job>()
```

> **删除的 v2.5 数据结构**：`serverSeenIds`（语义错误，改为 `locallyInjected` 反转）、`expandedPartIds`（复用现有 `partExpandStates`）、`reloadGenerations`（改为 `ReloadState.desiredEpoch`）、`inFlightReloads`/`pendingReload`（合并进 `ReloadState`）、`reloadFailedGenerations`（合并进 `ReloadState.failed`）。

#### 4.3.2 Fence 层次

```
Layer 1: desiredEpoch（新鲜度调度，非提交门 —— v2.7 修正）
  每次触发（digest/done/resync/idle）都递增 desiredEpoch
  响应回来时 epoch 已变 → 不丢弃，照常提交，同时把 pendingLimit 排上再拉一轮
  → v2.6 用 epoch 当提交门会饥饿：流式期间 digest ~4Hz，
    每个响应回来时 epoch 必已递增 → 全程零提交 → transcript 整个 turn 不更新。
    v2.7 提交只看 Layer 2/3 身份；"稍旧窗口"安全性由 merge 侧
    `created >= newestFetched → 永不删` 保证（他端新消息不会被旧窗口误删）。

Layer 2: ReloadIdentity（launch 时一次性捕获）
  serverGroupFp + sessionId + routeInstance 在请求发起时捕获
  dispatch 原样携带捕获值，不重新读取
  → 防 A₁→B→A₂ 的 token laundering

Layer 3: currentSessionId（已存在，10+ 处）
  锁内重新检查 sessionId == slices.chat.value.currentSessionId

Layer 4: host 切换 = 重启 app（C2，代码强制）
```

> **read-after-event 说明**：skeleton 端点每次 re-GET upstream opencode（`messages.py:574` `_stream_upstream()`），不读 sidecar `_part_state`。一致性依赖 opencode 的 "SSE emit happens-after persistence" 语义（合理假设：opencode 先写 DB 再 emit 事件）。偶发不一致由 busy→idle 200 条兜底覆盖。

#### 4.3.3 失败收敛协议（3 层兜底）

```
Layer A: 失败标记（catch 不排队重试 —— v2.7）
  reload catch(e) → state.failed = true；arm watchdog（仅失败时）
  catch 明确不动 pendingLimit：否则 finally 立刻看到 pendingLimit > 0
  → launchNextReload → 立刻再失败 → 无延迟热循环（v2.6 缺陷）。
  重试节奏统一交给 Layer B / Layer C。

  finally: inFlight = false；仅当 !failed && pendingLimit > 0 才续下一轮
           （!failed 门是热循环的第二道保险）

Layer B: digest 触发无条件重试
  digest 到来时检查 failed 标记：
    if (failed) { failed = false; retryAttempt = 0; disarm watchdog }
    然后正常递增 epoch + 排 pendingLimit（一条路径，不分叉）

Layer C: watchdog（仅失败时 arm，成功时 disarm）
  指数退避：15s → 30s → 60s → cap 5min
  retryAttempt 存在 ReloadState 里（跨 arm 持久化）——v2.6 用局部变量，
  每次 armWatchdog 都重置为 0 → 退避永远停在 15s。
  已 armed（job.isActive）时不重复 arm，避免重置退避 / 起多条 job。
  首次重试用 limit=50（不是 200）
  恢复（!failed）或 session 已关 → break 退出循环并从 watchdogJobs 摘除
  （v2.6 用 return@withLock 只退出 lambda，while 循环继续空转）
```

#### 4.3.4 权威窗口 Merge 规格

```
前置：空页早退（v2.7 P0）
  page.items.isEmpty() → merge 函数入口直接 return：不 merge、不 dispatch。
  空页承载零权威信息，任何"部分应用"都会退化成清空 transcript。
  （v2.6 只在 isServerDeleted 里 short-circuit 空页，但分区 filter 仍依赖
    oldestFetched != null → 空页时 olderKept 恒空 → mergedMessages 只剩
    newerKept（本地注入）→ transcript 被清光。这是 v2.6 的 P0。）

分区定义（v2.7 改为补集式 —— survivors 非删即留，绝不丢人）：

  survivors  — srcMessages 中未被判定为服务器删除的全部消息
  notFetched — survivors 中 id 不在 fetched 的部分

  olderKept  — 历史：notFetched 中 created 非空且 created <= oldestFetchedCreated
               （tie 归历史侧，与 isServerDeleted 的严格 `>` 闭合，无重叠无空隙）

  fetched    — 服务端权威窗口：skeleton 返回的最新 N 条
               （覆盖更新：同 id 的 message + parts 整体替换）

  newerKept  — notFetched 中一切剩余（= notFetched − olderKept）：
               本地注入（服务器还没确认）+ created == null（无法定位）
               + 任何未被上面两类吃掉的消息
               → 补集写法保证 survivors 全部落在某个分区，不会凭空消失

  mergedMessages = (olderKept + fetched + newerKept).distinctBy { it.id }
  （拼接顺序依赖 fetched 已按 time.created 升序 —— 见 §4.3.7 排序契约）

删除检测（包含法，非排除法）：
  满足全部条件才判定为服务器删除：
    1. id 不在 fetched 中
    2. id 不在 locallyInjected 中（非本地注入）
    3. created 非空（created == null → 永不删，无法定位权威区）
    4. oldestFetched 非空（无法定位窗口 → 永不删）
    5. created < newestFetched（v2.7 新增：created >= newestFetched → 永不删）
       → 覆盖"他端刚产生的新消息"和"与窗口最新条同毫秒的 tie"，
         使"提交稍旧窗口"安全（见 §4.3.2 Layer 1 饥饿修正）
    6. created > oldestFetched（v2.7：严格大于，与 olderKept 的 <= 闭合）
  → olderKept 侧也受同一判据约束（修复 v2.5 olderKept 不受约束的问题）

展开 part 保护（按位置就地替换，复用现有 partExpandStates）：
  从 chat.partExpandStates 派生 expandedKeys（.filterValues { it is Loaded }.keys）
  对 fetched 中的每个 part：
    如果 PartKey(msgId, part.id) 在 expandedKeys 中
    且本地存在同 id part（localById[fp.id] != null —— v2.7 显式 null 检查）
    且 skeleton 返回的是截断标记（hasFull=true && omitted != null）
    且本地版本是完整内容（hasFull != true || omitted == null）
    → 保留本地完整版本（位置不变）
    否则 → 使用 fetched 版本
  → 服务器删除的 part（不在 fetched 中）自然消失，不会被复活

孤儿 overlay 清理（v2.7 改黑名单）：
  剪 streamingPartTexts / streamingReasoningPart 时用 deadMsgIds 黑名单，
  不用 livePartIds 白名单：白名单会误杀 streamOwned == STREAMING、
  尚未持久化（不在 mergedParts）的 part —— 正在流的文本会被剪掉。
  黑名单只剪"已删消息拥有的 part"，其余一律保留。
```

#### 4.3.5 历史守卫清单（不可回归）

| 守卫 | 位置 | 作用 |
|---|---|---|
| §newerKept-force-window-fix | `MessageActions.kt:315-328` | 本地注入消息不被丢弃 |
| §flicker-fix (placeholder survival) | `MessageActions.kt:343-396` | 流式 placeholder 不被 skeleton 空快照覆盖 |
| §append-safe + §Q10 overlay guard | `MessageActions.kt:397-442` | 流式 overlay 在 turn 期间不被清除 |
| §streamingFinalized gate | `MessageActions.kt:370-395` | idle 后不注入 zombie placeholder |

#### 4.3.6 完整伪代码

```kotlin
// ── ReloadIdentity：launch 时一次性捕获 ──
data class ReloadIdentity(
    val serverGroupFp: String,
    val sessionId: String,
    val routeInstance: Long,
)

// v2.7: routeInstanceFor 的接收者是 slices（不是 store snapshot）
private fun captureIdentity(sessionId: String): ReloadIdentity? {
    if (slices.chat.value.currentSessionId != sessionId) return null
    val routeInstance = slices.routeInstanceFor(sessionId)
    if (routeInstance == 0L) return null
    return ReloadIdentity(
        serverGroupFp = currentServerGroupFp(),
        sessionId = sessionId,
        routeInstance = routeInstance,
    )
}

// ── 入口：requestReload（fire-and-forget）──
fun requestReload(sessionId: String, limit: Int = 50) {
    // 后台 session gate：fetch 前检查，避免无效请求
    if (sessionId != slices.chat.value.currentSessionId) return

    scope.launch {
        stateMutex.withLock {
            val state = reloadStates.getOrPut(sessionId) { ReloadState() }
            // 每次触发都递增 desiredEpoch（Layer 1）
            state.desiredEpoch += 1
            state.pendingLimit = maxOf(state.pendingLimit, limit)
            if (!state.inFlight) {
                launchNextReload(sessionId)
            }
        }
    }
}

// 必须在 stateMutex 内调用
private fun launchNextReload(sessionId: String) {
    val ownerState = reloadStates[sessionId] ?: return
    if (ownerState.inFlight || ownerState.pendingLimit == 0) return

    val identity = captureIdentity(sessionId) ?: run {
        ownerState.pendingLimit = 0
        return
    }

    val requestEpoch = ownerState.desiredEpoch
    val limit = ownerState.pendingLimit
    ownerState.pendingLimit = 0
    ownerState.inFlight = true

    // v2.7-final: lazy start —— 先登记 job 再启动，避免极快完成时 map 写入顺序反转
    val job = scope.launch(start = CoroutineStart.LAZY) {
        try {
            val page = repository.getSlimapiMessagesSkeleton(
                sessionId, limit = limit, before = null
            )
            // 锁序：sessionLock → stateMutex（单向，无环）。
            // 禁止反向（把 stateMutex 套在 sessionLock 外）—— 会与本路径成环死锁。
            messageLoadCoordinator.withSessionLock(sessionId) {
                // v2.7-final: 原子提交事务 —— epoch + 身份 + merge + dispatch + 簿记在同一 stateMutex 内
                stateMutex.withLock {
                    val current = reloadStates[sessionId]
                    if (current !== ownerState) return@withLock
                    if (ownerState.desiredEpoch != requestEpoch) {
                        ownerState.pendingLimit = maxOf(ownerState.pendingLimit, limit)
                    }
                    val chat = slices.chat.value
                    if (chat.currentSessionId != identity.sessionId) return@withLock
                    if (currentServerGroupFp() != identity.serverGroupFp) return@withLock
                    if (page.items.isEmpty()) return@withLock
                    mergeSkeletonIntoChatSlice(chat, sessionId, page, identity)
                    ownerState.failed = false
                    ownerState.retryAttempt = 0
                    watchdogJobs.remove(sessionId)?.cancel()
                }
            }
        } catch (ce: CancellationException) {
            throw ce  // R-14: CE 必须原样传播
        } catch (e: Exception) {
            // Layer A: 失败标记 + arm watchdog（不动 pendingLimit）
            stateMutex.withLock {
                val current = reloadStates[sessionId]
                if (current === ownerState) {
                    ownerState.failed = true
                    armWatchdog(sessionId, ownerState)
                }
            }
        } finally {
            withContext(NonCancellable) {
                stateMutex.withLock {
                    val current = reloadStates[sessionId]
                    val self = coroutineContext[Job]
                    if (current === ownerState) {
                        ownerState.inFlight = false
                        if (!ownerState.failed && ownerState.pendingLimit > 0) {
                            launchNextReload(sessionId)
                        }
                    }
                    if (reloadJobs[sessionId] === self) reloadJobs.remove(sessionId)
                }
            }
        }
    }
    reloadJobs[sessionId] = job
    job.start()
}

// ── 权威窗口 merge ──
// 调用方已持 sessionLock + stateMutex（见 launchNextReload）
private fun mergeSkeletonIntoChatSlice(
    sessionId: String,
    page: MessagesPage,
    identity: ReloadIdentity,
) {
    // ── v2.7 P0: 空页零权威 —— 入口早退，不 merge 不 dispatch ──
    if (page.items.isEmpty()) return

    // ── v2.7: 一次性快照，全函数只读 chat（避免中途 slice 变更导致自相矛盾）──
    val chat = slices.chat.value
    val srcMessages = chat.messages
    val srcParts = chat.partsByMessage
    val srcStreamingTexts = chat.streamingPartTexts
    val srcStreamingReasoning = chat.streamingReasoningPart
    val srcStreamOwned = chat.streamOwned
    val srcCursor = chat.olderMessagesCursor
    val srcHasMore = chat.hasMoreMessages

    // 防御性排序（N ≤ 200，成本可忽略）—— 拼接顺序依赖升序，见 §4.3.7 契约
    val fetched = page.items.map { it.info }
        .sortedWith(compareBy({ it.time?.created ?: Long.MAX_VALUE }, { it.id }))
    val fetchedParts = page.items.associate { it.info.id to it.parts }
    val fetchedIds = fetched.mapTo(HashSet()) { it.id }

    // 更新 locallyInjected：被服务器确认的 id 移除
    locallyInjected[sessionId]?.removeAll(fetchedIds)

    val fetchedCreated = fetched.mapNotNull { it.time?.created }
    val oldestFetched = fetchedCreated.minOrNull()
    val newestFetched = fetchedCreated.maxOrNull()
    val injected: Set<String> = locallyInjected[sessionId] ?: emptySet()

    // ── 删除检测（包含法）──
    fun isServerDeleted(m: Message): Boolean {
        if (m.id in fetchedIds) return false             // 在 fetched 中
        if (m.id in injected) return false               // 本地注入
        val created = m.time?.created ?: return false    // 无时间戳 → 永不删
        val oldest = oldestFetched ?: return false       // 无法定位窗口 → 永不删
        // 比窗口最新还新 → 永不删（他端新消息 / 同毫秒 tie）：
        //   这是"提交稍旧窗口"安全的前提，见 §4.3.2 Layer 1
        if (newestFetched != null && created >= newestFetched) return false
        return created > oldest                           // 严格在窗口内缺席 → 删除
    }

    // ── 补集分区 merge（v2.7：survivors 非删即留，绝不丢人）──
    val survivors = srcMessages.filterNot(::isServerDeleted)
    val notFetched = survivors.filter { it.id !in fetchedIds }
    val olderKept = notFetched.filter { m ->
        val c = m.time?.created
        c != null && oldestFetched != null && c <= oldestFetched   // tie 归历史
    }
    val olderKeptIds = olderKept.mapTo(HashSet()) { it.id }
    // newerKept = notFetched − olderKept：兜住 null-created + injected + 一切剩余
    val newerKept = notFetched.filter { it.id !in olderKeptIds }
    val keptIds = HashSet(olderKeptIds).apply { addAll(newerKept.map { it.id }) }

    val mergedMessages = (olderKept + fetched + newerKept).distinctBy { it.id }

    // ── Parts merge：按位置就地替换，保护展开内容 ──
    // v2.7: HashMap 就地写入（v2.6 在循环里 `mergedParts + (…)` = 每轮全量复制，O(n²)）
    val expandedKeys = chat.partExpandStates
        .filterValues { it is PartExpandState.Loaded }.keys

    val mergedPartsMut = HashMap<String, List<Part>>(srcParts.filterKeys { it in keptIds })
    for ((msgId, fetchedPartList) in fetchedParts) {
        val localById = srcParts[msgId]?.associateBy { it.id }
        mergedPartsMut[msgId] = if (localById == null) fetchedPartList else fetchedPartList.map { fp ->
            val lp = localById[fp.id]          // v2.7: 显式 null 检查（v2.6 `lp.isTruncatedMarker()` 编译不过）
            if (lp != null && PartKey(msgId, fp.id) in expandedKeys &&
                fp.isTruncatedMarker() && !lp.isTruncatedMarker()
            ) lp else fp
        }
    }

    // ── 孤儿键清理：删除后 mergedParts 不残留已删消息的 parts ──
    val liveIds = mergedMessages.mapTo(HashSet()) { it.id }
    mergedPartsMut.keys.retainAll(liveIds)
    var mergedParts: Map<String, List<Part>> = mergedPartsMut

    // ── Historical Guard 1: §flicker-fix (placeholder survival) ──
    val srcSessionStatuses = slices.sessionList.value.sessionStatuses
    val streamingFinalized = srcSessionStatuses[sessionId]
        ?.let { st -> !st.isBusy && !st.isRetry } ?: true
    val streamingPartIds = srcStreamingTexts.keys
    if (!streamingFinalized && streamingPartIds.isNotEmpty()) {
        val withPlaceholders = mergedParts.toMutableMap()
        for ((oldMsgId, oldParts) in srcParts) {
            if (oldMsgId !in liveIds) continue  // 跳过已删消息
            for (p in oldParts) {
                if (p.id in streamingPartIds && (p.isText || p.isReasoning)) {
                    val merged = withPlaceholders[oldMsgId]
                    if (merged == null || merged.none { it.id == p.id }) {
                        withPlaceholders[oldMsgId] = (merged ?: emptyList()) + p
                    }
                }
            }
        }
        mergedParts = withPlaceholders
    }

    // ── Historical Guard 2: §append-safe + §Q10 overlay guard ──
    // Part owner 索引（避免 O(n²) 全表扫描）
    val partOwnerIndex = srcParts.entries
        .flatMap { (mid, ps) -> ps.map { it.id to mid } }.toMap()

    val overlayOwnerMsgIds = srcStreamingTexts.keys.mapNotNull { pid ->
        partOwnerIndex[pid]
    }.toSet()
    val overlayFinalized = overlayOwnerMsgIds.isEmpty() ||
        overlayOwnerMsgIds.all { it in fetchedIds }

    val reasoningOwnerMsgId = srcStreamingReasoning?.let { r -> partOwnerIndex[r.id] }
    val reasoningFinalized = reasoningOwnerMsgId == null || reasoningOwnerMsgId in fetchedIds

    val ownedStreamingKeys = srcStreamOwned
        .filterValues { it == StreamOwnedState.STREAMING }.keys
    val legacyWouldClear = streamingFinalized && overlayFinalized
    val authoritative = legacyWouldClear && ownedStreamingKeys.isEmpty()
    val newStreamingTexts = when {
        authoritative -> emptyMap()
        legacyWouldClear -> srcStreamingTexts.filterKeys { it in ownedStreamingKeys }
        else -> srcStreamingTexts
    }
    val newStreamingReasoning =
        if (streamingFinalized && reasoningFinalized && ownedStreamingKeys.isEmpty()) null
        else srcStreamingReasoning

    // 孤儿 overlay 清理（v2.7：deadMsgIds 黑名单，不用 livePartIds 白名单）
    //   白名单会误杀 streamOwned == STREAMING 且尚未持久化的 part
    //   （还没进 mergedParts → 不在 livePartIds → 正在流的文本被剪掉）。
    val srcIds = srcMessages.mapTo(HashSet()) { it.id }
    val deadMsgIds = srcIds - liveIds
    val deadPartIds = deadMsgIds.flatMapTo(HashSet()) { mid ->
        srcParts[mid].orEmpty().map { it.id }
    }
    val prunedStreamingTexts = newStreamingTexts.filterKeys { it !in deadPartIds }
    val prunedReasoning = newStreamingReasoning?.takeUnless { r ->
        partOwnerIndex[r.id]?.let { it in deadMsgIds } == true
    }

    // ── Cursor/hasMore ──
    val cursorUnseeded = srcCursor == null
    val historyAlreadyPaged = !cursorUnseeded && olderKept.isNotEmpty()
    val newCursor = if (cursorUnseeded && !historyAlreadyPaged) page.nextCursor else srcCursor
    val newHasMore = if (cursorUnseeded && !historyAlreadyPaged) (page.nextCursor != null) else srcHasMore

    // ── Dispatch（补齐全部字段）──
    store.dispatch(ChatContentLoaded(
        sessionId = identity.sessionId,
        expectedRouteInstance = identity.routeInstance,  // 捕获值，不重读
        messages = mergedMessages,
        partsByMessage = mergedParts,
        streamingPartTexts = prunedStreamingTexts,
        streamingReasoningPart = prunedReasoning,          // ← v2.5 漏；v2.7 补黑名单剪枝
        olderMessagesCursor = newCursor,
        hasMoreMessages = newHasMore,
        currentModel = inferCurrentModel(mergedMessages), // ← v2.5 漏
        authoritative = authoritative,                    // ← v2.5 漏
    ))
}

// ── Part 截断标记判定（与 ExpandedPartsReconcile.kt:65-67 同判据）──
private fun Part.isTruncatedMarker(): Boolean = hasFull == true && omitted != null

// ── digest 触发入口 ──
fun onDigestChange(sessionId: String) {
    // 后台 session：不拉 skeleton（status 投影由 SlimSseHandler digest 消费处理）
    if (sessionId != slices.chat.value.currentSessionId) return
    scope.launch {
        stateMutex.withLock {
            val state = reloadStates.getOrPut(sessionId) { ReloadState() }
            // Layer B: 如果上次失败，清除失败状态 + 重置退避
            if (state.failed) {
                state.failed = false
                state.retryAttempt = 0
                watchdogJobs.remove(sessionId)?.cancel()
            }
            state.desiredEpoch += 1
            state.pendingLimit = maxOf(state.pendingLimit, 50)
            if (!state.inFlight) launchNextReload(sessionId)
        }
    }
}

// ── Watchdog（仅失败时 arm，指数退避，retryAttempt 持久化在 ReloadState）──
//   必须在 stateMutex 内调用
//   ownerState: 首次失败所属的 session 生命周期实例
private fun armWatchdog(sessionId: String, ownerState: ReloadState) {
    if (watchdogJobs[sessionId]?.isActive == true) return  // 已 armed，不重置退避
    val job = scope.launch(start = CoroutineStart.LAZY) {
        try {
            while (true) {
                val delayMs = stateMutex.withLock {
                    val current = reloadStates[sessionId]
                    if (current !== ownerState || !ownerState.failed) 0L  // 已恢复或已重开
                    else when (ownerState.retryAttempt) {
                        0 -> 15_000L; 1 -> 30_000L; 2 -> 60_000L; else -> 300_000L
                    }
                }
                if (delayMs == 0L) break
                delay(delayMs)
                val retryScheduled = stateMutex.withLock {
                    val current = reloadStates[sessionId]
                    if (current !== ownerState || !ownerState.failed) false  // 已恢复或已重开
                    else {
                        ownerState.retryAttempt++
                        ownerState.desiredEpoch += 1
                        ownerState.pendingLimit = maxOf(ownerState.pendingLimit, 50)
                        if (!ownerState.inFlight) launchNextReload(sessionId)
                        true
                    }
                }
                if (!retryScheduled) break
            }
        } finally {
            withContext(NonCancellable) {
                stateMutex.withLock {
                    val self = coroutineContext[Job]
                    if (watchdogJobs[sessionId] === self) watchdogJobs.remove(sessionId)
                }
            }
        }
    }
    watchdogJobs[sessionId] = job
    job.start()
}

// ── Session 关闭清理（取消在途 job，防止 detached state 回写）──
//   调用点：(1) session 删除 reducer（CloseDetail / DetailMissing）；
//          (2) host 切换（C2 = 重启，状态自然清空）；
//          (3) ViewModel.onCleared()。
suspend fun onSessionClosed(sessionId: String) {
    val jobsToCancel = stateMutex.withLock {
        val rj = reloadJobs.remove(sessionId)
        val wj = watchdogJobs.remove(sessionId)
        reloadStates.remove(sessionId)
        locallyInjected.remove(sessionId)
        listOfNotNull(rj, wj)
    }
    jobsToCancel.forEach { it.cancelAndJoin() }
}

// ── 本地注入标记（同步，无 launch —— 消除注册时序窗口）──
// 顺序契约（MANDATORY）：调用点必须「先 markLocallyInjected，后发布 slice 更新」，
//   即 dispatch(reducer 写入 shell) 之前完成打标。
//   依据：StateFlow 发布建立 happens-before —— merge 若在快照中看见 shell，
//   则必已看见 CHM 中的标记；反之（先发布后打标）窗口仍在，shell 会被误判删除。
//   两个写入点（launchSendMessage 乐观消息 / SseChatReducers assistant shell）
//   实现时逐一核对此顺序。
fun markLocallyInjected(sessionId: String, messageId: String) {
    locallyInjected.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }.add(messageId)
}
```

#### 4.3.7 Repository 原语（~30 行新增）

SlimSyncEngine 被整体删除后，需要一个新的 fetch 方法。放在 `OpenCodeRepository.kt`：

```kotlin
/** 无 token 版 skeleton 单页拉取。复用现有 MessagesPage 类型（data/repository/MessagesPage.kt:26）。 */
suspend fun getSlimapiMessagesSkeleton(
    sessionId: String,
    limit: Int,
    before: String? = null,
): MessagesPage {
    val response = api.getSlimapiMessages(sessionId, limit, before, mode = "skeleton")
    if (!response.isSuccessful) throw IOException("HTTP ${response.code()}")
    val items = response.body() ?: throw IOException("null_body")
    return MessagesPage(items = items, nextCursor = response.headers()["X-Next-Cursor"])
}
```

**skeleton 端点排序契约**：sidecar GET /slimapi/messages?mode=skeleton 必须按 `time.created` 升序返回（tie 按 id 升序）。客户端防御性排序（N≤200，成本可忽略）：已在 merge 伪代码中 `.sortedWith(compareBy(...))` 。

#### 4.3.8 工作量修正

~260 行（merge 规格 ~100 + CAS/epoch/fence/原子事务 ~60 + 失败收敛/watchdog ~40 + fetch 原语 ~20 + 生命周期清理 ~20 + 状态追踪定义 ~20）。

### 4.4 API + DTO + 门面

| 文件 | 改动 |
|---|---|
| `data/api/SlimApi.kt` | 删 10 个 Tier 3 方法；保留 3 个（`getSlimapiSessions` / `getSlimapiMessages` / `getSlimapiMessageFull` 无 `?known=`）；删 Skip-Dir 标记 |
| `data/api/StandardApi.kt` | 删 Skip-Dir 标记（~21 处） |
| `data/api/v2/OpenCodeApiV2.kt` | 删 Skip-Dir 标记（2 处） |
| `data/api/OpenCodeApi.kt` | 删 routeToken DTO（`:183-197`） |
| `data/repository/http/DirectoryHeaderInterceptor.kt` | 删镜像逻辑 + 常量 |
| `data/repository/OpenCodeRepository.kt` | 删 `slimStateLock` + 5 嵌套异常 + slim reconfigure 方法群 + `getSlimapiMessageFullWithFingerprint` + `parseMessageEventSeqHeader` + `expandMessagesFullBatch` |
| `data/repository/MessageSource.kt` | 简化为纯端点调用 |
| `data/repository/ServerCompatProfile.kt` | 删 `supportsWatermarkResync` 等；只留 `slimConnection: Boolean`；`tokenStreamEnabled = slimConnection`（不再 probe） |
| `ui/controller/HostProfileController.kt` | 单 profile |
| `data/model/TokenStreamFrame.kt` | `MessagePartRemoved.messageEventSeq` 改可选 `Long? = null` |
| DTO 清理 | 删 batch/aggregation/since/status DTO |

### 4.5 SSE 处理简化

| 文件 | 改动 |
|---|---|
| `ui/controller/sse/SlimSseHandler.kt` | 消费 `session.digest`（6 字段）+ `session.error`；**任何 digest 字段变化 → reloadSkeletonPage** |
| `ui/controller/sse/SseEventRouter.kt` | 3 域简化为 1 个 |

### 4.6 轮询 bug 修复

| 文件 | 改动 |
|---|---|
| `ui/controller/SseSessionListReducers.kt:229,233` | 只有 `session.created` / `session.deleted` 触发 root 失效 |
| `ui/controller/UnreadSoakController.kt` | 配合 root 失效语义修正 |

### 4.7 保留不动

- `SSEClient.kt` / `TokenStreamClient.kt`（URL 构造不变）
- token stream 完整消费路径（delta / snapshot / resync / handshake）
- `CatchUpActions.kt`（catch-up probe 逻辑保留给 legacy 路径；mergeProbeIntoSlice 不再被任何路径复用）
- catch-all headers / mTLS / TOFU

---

## 5. 测试要求

### 5.1 总账

| 项目 | 删 | 改 | 增 |
|---|---|---|---|
| oc-slimapi | ~200 | ~20 | ~10 |
| ocdroid | ~450 | ~40 | ~15 |
| **总计** | **~650** | **~60** | **~25** |

### 5.2 必须新增的测试

**CAS + Fence**：
- desiredEpoch：新 digest 到来立即失效在途 reload（旧响应被丢弃）
- ReloadIdentity：routeInstance/serverGroupFp launch 时捕获，A→B→A 路由重入测试
- session 切换后旧 reload 不写入新 session（currentSessionId guard）
- 后台 session digest 不触发 skeleton 请求（前置 gate）

**失败收敛**：
- reload 失败 → failed 标记 → 下次 digest 无条件重试
- 所有 reload 失败 → watchdog 指数退避恢复（15s→30s→60s→5min）
- CancellationException 不被吞（session 关闭时 CE 传播且不打失败标记）
- trailing debounce：in-flight 期间新 digest 到来 → 排队不并发

**Merge 规格**：
- 三分区 merge：本地注入消息存活（locallyInjected 区分本地 vs 服务器删除）
- 服务器删除检测：id 不在 locallyInjected + 在权威窗口内 + 不在 fetched → 移除
- 空页不清空 transcript（零权威）
- 冷启动/loadMore 载入的历史在首次 reload 后顺序不变
- 展开的大 output 在 reload 后不回缩（partExpandStates + 按位置就地替换）
- 展开的 part 被服务器删除后 reload 不复活
- reload 期间本地注入消息存活（回归 §newerKept-force-window-fix）
- 流式 placeholder 在 reload 后不闪烁（回归 §flicker-fix）
- 流式 overlay 在 turn 期间不被清除（回归 §append-safe + §Q10）
- dispatch 携带 authoritative/streamingReasoningPart/currentModel（zombie overlay 不残留）
- 删除后 mergedParts/streamingTexts 无孤儿键

**触发路径**：
- digest 变化 → reload 50 条触发
- done:true → reload 50 条终态文本收敛
- busy→idle → reload 200 条全量收敛
- token stream message.removed → 即时移除（不等 reload）
- skeleton diff 检测 part 列表变化（part.removed 收敛）

**降级验证**：
- /full/{mid} 降级为纯按需展开（无自动同步调用）
- digest updatedAt wall-clock 不影响 UI 排序

### 5.3 T3RepositoryExtractFreezeTest 解锁

同 v2.3（删 slimStateLock + 5 嵌套异常 + SlimSseState + slim 方法反射 + `expandMessagesFullBatch`）。

---

## 6. 双分支 + 一次性替换流程

### 6.1 分支策略

```
oc-slimapi: main（不动）→ lite-v2（新建）
ocdroid:    main（不动）→ lite-v2（新建）
```

### 6.2 并行开发（W1-W7）

| 周 | oc-slimapi | ocdroid | 协调点 |
|---|---|---|---|
| W1 | 删 10 端点 + 简化 /full/{mid} + 删 tokens.py/questions.py/sessions_children.py/discovery.py/children_cache.py | 删 SlimSseStateMachine + SlimSseReducer + MessageEventSeqWatermark + SlimSyncEngine + ExpandBatchEngine | 独立 |
| W2 | 简化 digest（删 childrenVersion + contentRevisions）+ 简化 hub（删 _part_state 整套）+ bump updatedAt for part events + 清理 app.py | 删 **SlimFullReconciler 整个文件** + 简化 SlimApi.kt + 删 DTO + 删 Skip-Dir + 删 DirectoryHeaderInterceptor 镜像 | 独立 |
| W3 | bump version 2 + 修测试 | 精确修改 SessionSyncCoordinator + 重接线 TokenStreamCoordinator/Reducer + 清理 ControllerModule hooks + 解锁 T3FreezeTest + 修测试 | 独立 |
| W4 | 联调准备 | 实现 reloadSkeletonPage + digest 变化 → reload 触发 + done:true → reload + busy→idle → reload 200 + 修轮询 bug | **客户端切到 sidecar W3 提交点联调** |
| W5 | 联调修复 | 联调修复 | 双方对齐 |
| W6 | 全测试 GREEN + staging | `./scripts/check.sh --full` GREEN + 模拟器双形态集成测试 | 联签 |
| W7 | PR `lite-v2 → main` | PR `lite-v2 → main` | **同时合并** |

### 6.3 联调验证清单

| 验证项 | 方法 |
|---|---|
| /full 200 在无 seq 头下正常渲染 | 模拟器展开 message 超阈值 output |
| sidecar part.updated bump updatedAt → digest 携带 | curl 验证 digest 帧 |
| digest 变化 → reload 50 条触发 | 模拟器 agent 输出，access.jsonl 验证 skeleton 调用 |
| done:true → reload 50 条终态文本 | 模拟器流式完成，验证终态文本显示 |
| busy→idle → reload 200 条全量收敛 | 模拟器 agent 完成，验证所有 message 到位 |
| reloadGeneration CAS：旧响应被丢弃 | 模拟器弱网，验证无错乱 |
| token stream message.removed → 即时移除 | 模拟器 revert，验证 message 消失 |
| skeleton diff 检测 part 列表变化 | 模拟器 part 删除场景 |
| 200 条以外手动分页 | 模拟器滚到历史，验证分页加载 |
| digest updatedAt wall-clock 不影响 UI 排序 | 模拟器验证 message 列表排序正常 |
| `/children` 轮询已修（< 1 次/秒） | access.jsonl 24h 监控 |
| host profile 切换 = 重启 | 模拟器验证 |
| opencode 直连形态可用 | 模拟器 :4096 全 legacy 路径 |

### 6.4 同步合并强制条件

- [ ] oc-slimapi `lite-v2` 全测试 GREEN
- [ ] ocdroid `lite-v2` `./scripts/check.sh --full` GREEN
- [ ] 模拟器双形态集成测试通过
- [ ] access.jsonl 24h 监控达标
- [ ] 双方 PR 同时 approve
- [ ] 合并前双仓库打 tag `pre-lite-v2-<date>`
- [ ] 同日同小时合并

---

## 7. 风险与 trade-off

### 7.1 明确接受的 trade-off

| Trade-off | 影响 | 缓解 |
|---|---|---|
| skeleton reload 流量比单条 /full 贵 1-30 倍 | agent turn 期间 0.5-15MB（典型 1-10 倍，极端 30 倍） | trailing debounce + 窗口分级（高频 50 / 低频 200） + sidecar 250ms debounce |
| 200 条以外的 message 删除/更新不自动追踪 | 用户滚到历史区域看到旧数据 | 手动分页刷新；接受（agent 不回改历史） |
| 后台 session 无 token stream 订阅 | message.removed 不实时 | 用户打开时 reload 200 条收敛 |
| digest updatedAt 改用 wall-clock | 时钟回退可能漏触发（sidecar 重启 / NTP） | sidecar 重启时广播 digest reset；客户端检测 reset 强制 reload |
| stale 保护靠 reloadGeneration CAS + 现有 chatRouteInstance | 偶发旧 reload 被丢弃（最终一致） | 失败收敛 3 层兜底（§4.3.3） |

### 7.2 风险

| 风险 | 严重度 | 缓解 |
|---|---|---|
| SessionSyncCoordinator 精确修改引入 bug | **高** | 分步 + 每步 check.sh |
| ControllerModule hooks 4 处重写编译阻塞 | **高** | §4.2 已列入；逐个清理 |
| merge 规格实现引入历史 bug 回归 | **高** | §4.3.5 守卫清单 + §5.2 回归测试（4 个历史 fix 每个一条） |
| digest 高频变化导致 reload 风暴 | 中 | trailing debounce（§4.3）：等 in-flight 完成再发下一个，不并发 |
| 双方未同步合并 | **高** | 同步合并强制 + tag |

### 7.3 回滚

合并后第一周高风险期。P0 bug 时双方从 `pre-lite-v2-<date>` tag 重新部署。

---

## 8. 不在本次范围

- 修改 opencode 源码（C6）
- mTLS / TOFU / stunnel
- Android 平台层
- 双 host 运行时切换（C2）
- 多服务器槽位（C3）

---

## 9. 修订日志

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-07-27 | v1.0 | 初版。 |
| 2026-07-27 | v2.0 | 三评委评审 + 代码检索。digest 只删不加；token stream 必需；bump v2。 |
| 2026-07-27 | v2.1 | 5 explorer 深挖。contentRevisions 保留；_part_state 简化 seq map；SlimFullReconciler 部分保留。 |
| 2026-07-27 | v2.2 | rev-glm seq 决策 + 三评委 v2.1 复评。删除 seq 协议；SlimFullReconciler 简化无 seq；timestamp guard。 |
| 2026-07-27 | v2.3 | 三评委 v2.2 复评 + rev-glm Path D。sidecar bump updatedAt；删除 timestamp guard；统一 digest updatedAt wall-clock。 |
| 2026-07-27 | v2.4 | **范式转变：skeleton reload 替代精确单条同步**。SlimFullReconciler 完全删除（906 行→0）；SlimCommitToken 完全删除；同步路径从 5 条收敛为 1 条（digest 变化 → reloadSkeletonPage + diff）；窗口分级（高频 50 / 低频 200）；200 条以外手动分页；stale 保护靠 reloadGeneration CAS。客户端净删除 ~6500 行（比 v2.3 多删 ~1000 行）。 |
| 2026-07-27 | v2.5 | **第五轮复评修补**：§4.3 彻底重写 merge 规格（权威窗口三分区 + serverSeenIds 区分本地注入 vs 服务器删除 + expandedPartIds 保护 + 4 个历史守卫清单）；失败收敛 3 层协议（失败标记 + digest 重试 + 15s watchdog）；trailing debounce 替代固定 250ms；fence 调研确认 chatRouteInstance/currentSessionId/serverGroupFp 已覆盖 gpt 的 A→B→A / session 切换 / read-after-event 担忧；流量估算修正（典型 1-10 倍，极端 30 倍）。 |
| 2026-07-28 | v2.6 | **第六轮复评修补（§4.3 承重墙重铸）**：serverSeenIds → locallyInjectedIds 反转（修复空页清空 transcript + 冷启动历史翻到底部）；desiredEpoch 替代 generation CAS（事件即时失效在途响应）；ReloadIdentity launch 时一次性捕获（防 token laundering）；expandedPartIds → 复用 partExpandStates 按位置就地替换（不破坏顺序 + 不阻止删除）；补齐 dispatch 3 字段（authoritative/streamingReasoningPart/currentModel）；CancellationException 处理；stateMutex 线程安全；后台 session 前置 gate；空页零权威；孤儿键清理；read-after-event 调研确认 skeleton re-GET upstream。工作量修正 ~120→~200 行。 |
| 2026-07-28 | v2.7 | **第七轮终修（§4.3 承重墙铸成）**：补集分区（空页早退 + null-created 不丢 + tie 归保留）；删除安全边界 created>=newestFetched→永不删；epoch 饥饿修复（提交只看身份不看 epoch）；原子提交事务（stateMutex 内 epoch+merge+dispatch+簿记）；同步 locallyInjected（ConcurrentHashMap+newKeySet）；失败退避修正（catch 不恢复 pendingLimit + finally !failed 门 + retryAttempt 持久化）；session close 取消在途 job（reloadJobs+cancelAndJoin）；孤儿清理黑名单（deadMsgIds 不误杀 streamOwned）；编译修复（lp null check + mutableMap）；API 形状修正（MessagesPage + api.getSlimapiMessages）。~260 行。 |
| 2026-07-28 | v2.7-final | **终审通过**：采纳 gpt 的 ReloadState 实例身份检查（ownerState 捕获 + `current !== ownerState` 在 catch/finally/watchdog）+ 合并两次 stateMutex 为单一原子提交事务 + NonCancellable finally + lazy start；采纳 kimi 的 markLocallyInjected 顺序契约注释。kimi 有条件通过（9/9 闭合 + 1 doc fix）、gpt 有条件通过（首次通过，放弃 route lease + snapshot revision 异议）。 |
