# OpenCode Android Client — V3 架构：SSE 信任 + 分离存储

> **状态**：草稿，待评审。本文件是 Phase 4 大型重构的完整设计方案。
>
> **前提阅读**：
> - `design.md` — Quiet Tech 视觉语言（V2 已落地，V3 UI 层不动）
> - `v2-redesign-plan.md` — V2 改造实施记录（历史参考）
> - `oc-ref/packages/app/src/context/server-session.ts` — Web 版参考实现
>
> **目标**：将 Android 的消息模型从 "API 为主的混合信任" 迁移到 "SSE 100% 信任的就地修补"，消除残余 API reload，将流量从 450MB/分钟 降至 ~2MB/分钟。对齐 opencode-web 架构。

---

## 1. 问题根因

### 1.1 当前架构：混合信任模型（0.1.13）

```
SSE 事件 ──→ streamingPartTexts (实时文本，可信)
                  │
                  ├── message.updated   → 不操作 (0.1.13 ✅)
                  ├── message.part.updated → delta 累加 (✅)
                  ├── message.part.delta   → 未处理 (❌)
                  │
                  └── session.status idle → API reload 30 条 (❌ 不信任)
watchdog 15s 静默  → API reload 30 条 (❌ 不信任)
app 回前台         → API reload 30 条 (❌ 不信任)
message.created    → API reload 30 条 (OK — 结构事件)
```

**核心矛盾**：虽然 V2 (0.1.7) 引入了 cursor 分页，0.1.12 修了 `preserveUnfetched` merge，0.1.13 移除了 `message.updated`→reload 管道——但 Android 仍然在 **3 个残余触发点** 用 API reload 做 catch-up，每次 30 条 × 全量工具输出。

**ADB 确诊数据**（pid 29340, 0.1.12）：
- `/message?limit=30` 循环 ~3s 间隔（12s 间隔的 watchdog + idle 叠加效果）
- 单次响应最大 2991ms, LOS 818 个 / 64MB
- 页面内流量统计 450MB/分钟
- `Clamp target GC heap from 562MB to 512MB`（largeHeap 反作用）
- `Heap oversized, notify system.` → LMK 激进 → 进程被杀

### 1.2 Web 版对比

| 维度 | Web (SolidJS) | Android 0.1.13 |
|---|---|---|
| 初始 load 大小 | 2 条 | 30 条 (15×) |
| `message.updated` | 就地 `reconcile(info)` | 不操作 |
| `message.part.updated` | 就地 `reconcile(part)` + 清 accum delta | delta 累加到 streaming |
| `message.part.delta` | 独立 handler, 字段级累加 | **未处理** ❌ |
| `session.status` | 设 status map, 0 HTTP | **API reload 30 条** ❌ |
| watchdog | **不存在** | 15s → 30 条 reload ❌ |
| 前台恢复 | 缓存 15s 内跳过 | **每次都 reload 30 条** ❌ |
| 存储结构 | `message[]` + `part[msgId][]` 分存 | `MessageWithParts(info + parts)` 合存 |

Web 平均流量：~0.5–2MB/session。Android: ~200–450MB/session。差距 **100–900×**。

---

## 2. 目标架构

### 2.1 总原则

> SSE 是唯一权威数据源。API 仅在结构事件（message.created、首次 load、loadMore）时调用。SSE 事件直接就地修补 AppState，不通过 API 中转。

```
SSE 事件 ──→ AppState 就地修补 ──→ Compose 自动重绘
              │                        │
         0 HTTP 调用             O(changed) recomposition
```

### 2.2 每个 SSE 事件的目标行为

| 事件 | 行为 |
|---|---|
| `message.updated` | 在 `messages` 中按 ID 找到 → 替换该项 |
| `message.part.updated` | 在 `partsByMessage[msgId]` 中找到 → 替换该 part。同时清 `streamingPartTexts[partId]` |
| `message.part.delta` | 在 `streamingPartTexts[partId]` 累加。同时尝试在 `partsByMessage[msgId]` 中找到该 part 并追加 `delta` 到对应 field |
| `session.status` | 设 `sessionStatuses[sid] = status`，**无 reload，不清 streaming** |
| `message.created` | reload (唯一结构事件触发点) |
| loadMore | API → `preserveUnfetched` merge |

### 2.3 删除的机制

- ❌ watchdog (watchdogJob, startStreamWatchdog, stopStreamWatchdog)
- ❌ `lastSseProgressAtMs`
- ❌ `onLastSseProgress` / `onSessionBecameBusy` / `onSessionBecameIdle` callbacks
- ❌ `session.status idle` → reload
- ❌ 前台恢复 → reload（改 15s 缓存）
- ❌ `android:largeHeap="true"`（已回退, 0.1.13）

---

## 3. 数据模型重构

### 3.1 当前模型 (MessageWithParts — 合存)

```kotlin
// Message.kt
data class MessageWithParts(
    val info: Message,
    val parts: List<Part> = emptyList()
)

// AppState
val messages: List<MessageWithParts>         // info + parts 合体
val streamingPartTexts: Map<String, String>  // "msgId:partId" → accumulated text
```

**问题**：
- SSE 事件推送的 part 无法直接写入 `messages`（需要找到对应 MessageWithParts → 重建它 → 重建整个 messages list）
- 初始 load 必须拉足够多的 messages 才能渲染（因为 UI 从 `messages[i].parts` 读 parts）
- limit=2 加载会让 UI 只能渲染 2 条消息的骨架

### 3.2 目标模型 (Message + Part[] 分存 — 对齐 Web)

```kotlin
// 不变：Message 保持现有字段，从 MessageWithParts 剥离出来
// 不变：MessageWithParts 删除

// 新增
typealias PartMap = Map<String, List<Part>>  // messageId → parts

// AppState
val messages: List<Message>                  // 纯 info，不含 parts
val partsByMessage: Map<String, List<Part>>  // messageId → parts（分存）
val streamingPartTexts: Map<String, String>  // partId → accumulated text（key 简化）
```

**关键变更**：

| 项 | 当前 | 目标 |
|---|---|---|
| messages 类型 | `List<MessageWithParts>` | `List<Message>` |
| parts 存储 | 内嵌在 MessageWithParts 中 | 独立 `partsByMessage: Map<String, List<Part>>` |
| streaming key | `"msgId:partId"` | `partId` (simpler, web 只用 partId) |
| SSE 修补 part | 不可行（合体） | 直接 `partsByMessage[msgId]` 就地 upsert |
| 初始 load | 30 (需要 parts 来渲染) | 2 (SSE 填充 parts 后渲染) |

### 3.3 Part 模型增强

当前 `Part` 模型 (`Message.kt`) 需要确认字段是否足够承载 SSE 的完整 part 对象。Web 的 Part 类型 (`@opencode-ai/sdk/v2/client`) 包含：

```ts
interface Part {
  id: string
  sessionID: string
  messageID: string
  type: string           // "text", "tool", "reasoning", "patch", "step-start", "step-finish"
  text?: string
  tool?: string
  toolReason?: string
  toolInput?: unknown
  toolOutput?: string
  status?: string        // "running", "completed", "error"
  // ... 其他字段
}
```

需确认 Android `Part` 序列化类对齐这些字段（`toolReason`, `toolInput`, `toolOutput`, `status`）。

### 3.4 API 适配

当前 `getMessagesPaged` 返回 `MessagesPage(items: List<MessageWithParts>)`。需改为分拆：

```kotlin
data class MessagesPage(
    val messages: List<Message>,           // 拆分自 MessageWithParts.info
    val parts: Map<String, List<Part>>,    // 拆分自 MessageWithParts.parts, 按 messageId 分组
    val nextCursor: String?
)
```

API 层 (`OpenCodeApi.getMessages`) 返回的 `List<MessageWithParts>` 在 `getMessagesPaged` 内拆分。反序列化不需要改。

---

## 4. SSE Handler 重构

### 4.1 当前 handlers 重写清单

#### `message.updated` (已移除 reload，0.1.13)

**0.1.13 行为**：仅 `onLastSseProgress()`（保持 watchdog）。
**V3 行为**：从 event 中解析 `info: Message` → 在 `state.messages` 中按 `id` 找到 → `state.update { it.copy(messages = messages.map { if (it.id == info.id) info else it }) }`。
**注意**：需要确认 server 的 `message.updated` SSE payload 是否包含完整的 message info 对象。如果不含 → 保持当前不做操作的行为。

#### `message.part.updated` (已有 delta 累加，0.1.13)

**V3 增强**：
1. 解析完整 part 对象（不只是 delta + text）
2. 如果 part 有 `text` 字段 → 直接用 `text` 更新 `streamingPartTexts[partId]`（跳过 delta 累加，与 web 一致）
3. 在 `partsByMessage[messageId]` 中 upsert 该 part（就地替换）：
   ```kotlin
   val newParts = (partsByMessage[messageId] ?: emptyList()).toMutableList()
   val idx = newParts.indexOfFirst { it.id == part.id }
   if (idx >= 0) newParts[idx] = part else newParts.add(part)
   // sort by created time or id
   state.update { it.copy(partsByMessage = partsByMessage + (messageId to newParts)) }
   ```
4. 清除非 part 的 `streamingPartTexts[partId]`（server 给了权威版本）

#### `message.part.delta` (新 handler)

**Web 版参考** (`server-session.ts:862-904`)：
```ts
case "message.part.delta": {
    const { sessionID, messageID, partID, field, delta } = event.properties
    // 设置 delta base 用于后续 resync 检测
    deltaBases.set(partID, { base: current, sessionID })
    // 累加到 part_text_accum_delta
    setData("part_text_accum_delta", partID, value => (value ?? current) + delta)
    // 同时直接在 part 对象的 field 上追加
    setData("part", messageID, produce(draft => {
        part[field] = (part[field] ?? "") + delta
    }))
}
```

**Android V3 实现**：
```kotlin
"message.part.delta" -> {
    val sessionId = event.payload.getString("sessionID") ?: return
    if (sessionId != state.value.currentSessionId) return
    val messageId = event.payload.getString("messageID") ?: return
    val partId = event.payload.getString("partID") ?: return
    val field = event.payload.getString("field") ?: "text"
    val delta = event.payload.getString("delta") ?: return
    
    // 1. 累加到 streamingPartTexts（UI 直接读）
    val key = partId
    val prev = state.value.streamingPartTexts[key] ?: ""
    state.update { it.copy(streamingPartTexts = it.streamingPartTexts + (key to (prev + delta))) }
    
    // 2. 尝试就地更新 partsByMessage 中的对应 part.field
    // （如果该 part 已经通过 message.part.updated 加载过）
    val parts = state.value.partsByMessage[messageId]
    if (parts != null) {
        val idx = parts.indexOfFirst { it.id == partId }
        if (idx >= 0) {
            // 需要创建 part 的修改副本（Part 是 data class，不可变）
            // field → 映射到 Part 的对应属性（text, toolReason, toolOutput 等）
            // 复杂度较高，Phase 2 先不做，只做 streamingPartTexts 累加
        }
    }
}
```

**决策**：Phase 2 (本 PR) 只做 `streamingPartTexts` 累加。Part 对象的 field 级就地更新延后到 Phase 4（需要 Part 模型的字段→属性映射）。

#### `session.status`

**V3 行为**（web 对齐）：
```kotlin
"session.status" -> {
    val statusEvent = parseSessionStatusEvent(event) ?: return
    // 只设状态，不做其他事
    state.update { it.copy(sessionStatuses = it.sessionStatuses + (statusEvent.sessionId to statusEvent.status)) }
    // 处理 unread 逻辑（保持不变）
    // 不触发 reload
    // 不启动/停止 watchdog（已删除）
    // 不清 streamingPartTexts（streaming 文本保留，直到被 part.updated 覆写或用户切 session）
}
```

### 4.2 简化的 handleIncomingSseEvent 签名

```
移除的参数：
  - onLastSseProgress    (watchdog 已删)
  - onSessionBecameBusy  (watchdog 已删)
  - onSessionBecameIdle  (watchdog 已删)

保留的参数：
  - state
  - event
  - onRefreshMessages    (仅 message.created 使用)
  - onRefreshSessions
  - onLoadPendingPermissions
  - onNonFatalIssue
```

---

## 5. UI 层修改

### 5.1 ChatMessageContent.kt

**当前渲染链路**：
```kotlin
val visibleMessages: List<MessageWithParts>  // 来自 state.visibleMessages
LazyColumn(items = visibleMessages, key = { it.info.id }) { msgWithParts ->
    val parts = msgWithParts.parts
    val streamingText = streamingPartTexts["${msgWithParts.info.id}:${part.id}"]
    // 渲染每个 part
}
```

**V3 渲染链路**：
```kotlin
val messages: List<Message>                // 来自 state.messages
val partsByMessage: Map<String, List<Part>> // 来自 state.partsByMessage
val streamingTexts: Map<String, String>    // 来自 state.streamingPartTexts (key=partId)

LazyColumn(items = messages, key = { it.id }) { message ->
    val parts = partsByMessage[message.id] ?: emptyList()
    // 渲染每个 part
    val streamingText = streamingTexts[part.id]  // key 简化
}
```

**影响范围**（`ChatMessageContent.kt` ~1812 行）：
- `visibleMessages` 参数类型改 `List<MessageWithParts>` → `List<Message>`
- 新增参数 `partsByMessage: Map<String, List<Part>>`
- `streamingPartTexts` key 格式从 `"msgId:partId"` → `partId`
- 所有 `messageWithParts.parts` / `it.parts` 引用 → `partsByMessage[message.id] ?: emptyList()`
- 所有 `messageWithParts.info.*` 引用 → `message.*`（message 本身就是 info）
- 预估影响 ~50 处引用，分散在约 30 个 Composable 函数

### 5.2 ChatScreen.kt

```kotlin
// 当前
ChatMessageContent(
    messages = state.visibleMessages,
    streamingPartTexts = state.streamingPartTexts,
    ...
)

// V3
ChatMessageContent(
    messages = state.visibleMessages,      // 类型改为 List<Message>
    partsByMessage = state.partsByMessage, // 新增
    streamingPartTexts = state.streamingPartTexts,  // key 格式已改
    ...
)
```

### 5.3 streamingPartTexts key 格式迁移

| 位置 | 当前 key | V3 key | 影响 |
|---|---|---|---|
| `message.part.updated` delta 累加 | `"$msgId:$pId"` | `pId` | handler 内改 |
| `message.part.delta` 累加 | — | `pId` | 新增 handler |
| `ChatMessageContent` 查找 | `"${info.id}:${part.id}"` | `part.id` | ~5 处 |
| `streamingReasoningPart` 查找 | `"${part.messageId}:${part.id}"` | `part.id` | 1 处 |

### 5.4 初始 load 2 条的 UI 适配

初始 2 条 load 后，`partsByMessage` 中只有 0-2 条消息的 parts（可能为空或只有骨架）。SSE 实时推送 `message.part.updated` / `message.part.delta` 填充 parts。

UI 需要能处理 `partsByMessage[messageId]` 为空的情况：
- 如果 message 是 assistant + 无 parts → 显示 loading 指示或空状态
- 如果 message 是 user → 总是有 text part（user 消息在发送时就已有内容）

**实际上**：opencode server 在初始 load 时会对最近的 2 条消息返回完整 parts（包括已有的 text/tool 输出）。SSE 只在流式生成过程中推送增量的 part 更新。所以初始 load 2 条时，最新的 assistant 消息的 parts 可能已经在 API 响应中返回了。

**保守方案**：保持 limit=30（当前），等 Phase 4 全部验证后再调降。改为 2 需要在 dev 充分测试 server 行为。

---

## 6. Reload 触发点精简

### 6.1 删除清单

| 组件 | 文件 | 行数 | 操作 |
|---|---|---|---|
| `watchdogJob` field | MainViewModel.kt | 440 | 删除 |
| `lastSseProgressAtMs` field | MainViewModel.kt | 420 | 删除 |
| `startStreamWatchdog()` | MainViewModel.kt | 514-533 | 删除 |
| `stopStreamWatchdog()` | MainViewModel.kt | 535-538 | 删除 |
| `maybeStartWatchdogForBusyCurrentSession()` | MainViewModel.kt | 549-554 | 删除 |
| `WATCHDOG_INTERVAL_MS` | MainViewModel.kt | 1632 | 删除 |
| `WATCHDOG_STALE_MS` | MainViewModel.kt | 1638 | 删除 |
| `cancelSseAndWatchdogForReconfigure` → rename `cancelSseForReconfigure` | MainViewModel.kt | 566-571 | 改 |
| `onForegroundChanged` watchdog stop | MainViewModel.kt | 502 | 删除该行 |
| `selectSession` watchdog stop | MainViewModel.kt | 968 | 删除该行 |
| `loadSessionStatus` → `onStatusesUpdated` | MainViewModel.kt | 941-947 | 删除 callback |
| SSE callbacks: onLastSseProgress, onSessionBecameBusy, onSessionBecameIdle | MainViewModel.kt | 1609-1613 | 删除 |
| handleIncomingSseEvent params | MainViewModelSyncActions.kt | 72-74 | 删除参数 |

### 6.2 保留的 reload 触发点

| 触发 | 频次 | 理由 |
|---|---|---|
| `message.created` | 每个新消息 1 次 | 结构事件，需要全量 refresh |
| `loadMore` (用户滚动) | 按需 | cursor 分页，preserveUnfetched |
| `selectSession` (首次打开) | 1 次/session | 初始加载 |
| 前台恢复 (>15s since lastLoad) | 偶尔 | 覆盖后台 SSE 断连期间的 missed events |
| `part.created` (message.part.updated, ids null) | 流式开始 1 次 | 正在排查是否可以移除 |

### 6.3 前台恢复 throttle

```kotlin
// 新增字段
@Volatile private var lastLoadAtMs: Long = 0L

private fun onForegroundChanged(inForeground: Boolean) {
    if (!hasObservedForegroundState) { hasObservedForegroundState = true; return }
    if (inForeground) {
        // 清理 streaming state
        _state.update { it.copy(streamingPartTexts = emptyMap(), streamingReasoningPart = null) }
        testConnection(force = true)
        val now = System.currentTimeMillis()
        if (now - lastLoadAtMs > FOREGROUND_RELOAD_INTERVAL_MS) {
            lastLoadAtMs = now
            _state.value.currentSessionId?.let { loadMessages(it, resetLimit = true) }
        }
    } else {
        sseJob?.cancel()
        sseJob = null
        // 不再调用 stopStreamWatchdog()
    }
}

companion object {
    private const val FOREGROUND_RELOAD_INTERVAL_MS = 15_000L
}
```

---

## 7. 迁移阶段

### Phase 1 (低代价, 0.1.14): 删除残余 API reload + watchdog

**范围**：第 6 节的删除清单 + `session.status` handler 简化 + 前台恢复 throttle。
**代价**：~-120 行（纯删除），零风险。
**流量收益**：消除 session 生命周期内 **所有** API reload（idle + watchdog × N + 前台 × N）。剩余唯一 API 调用：`message.created` + loadMore + 初始 load。
**测试**：删除 watchdog tests + 新增 status 无 reload test + 前台 throttle test。

### Phase 2 (低-中代价, 0.1.14): 添加 `message.part.delta` handler

**范围**：新 SSE 事件类型 handler + streamingPartTexts 累加。
**代价**：~+40 行。
**风险**：低。如果 server 不发这个事件，code path 永不触发。
**流量收益**：修复流式文本丢失 bug（如果 server 发独立 delta 事件）。

### Phase 3 (中代价, 0.1.15): `message.updated` → 就地修补

**范围**：解析 message info 对象 → `messages` 就地替换。
**代价**：~+30 行。
**前提**：需确认 server `message.updated` payload 包含完整 info。
**流量收益**：体验优化（消息标题/状态实时更新）。

### Phase 4 (高代价, 0.1.16+): 拆分存储 + 分离 message/part

**范围**：第 3 节（数据模型）+ 第 4 节（SSE handler 增强）+ 第 5 节（UI 重构）。
**代价**：~300-500 行, 6-8 文件。

**细粒度实施步骤**：

1. **Message.kt**：新增 `typealias PartMap = Map<String, List<Part>>`。
2. **AppState**：删除 `messages: List<MessageWithParts>` → 拆分 `messages: List<Message>` + `partsByMessage: PartMap`。`streamingPartTexts` key 改 `partId`。
3. **OpenCodeApi.kt / OpenCodeRepository.kt**：`MessagesPage` 拆分 info 和 parts。
4. **MainViewModelSessionActions.kt**：`launchLoadMessages` 在存储时分拆 messages + partsByMessage。`preserveUnfetched` merge 逻辑适配分存。
5. **MainViewModelSyncActions.kt**：`message.part.updated` 增加 `partsByMessage` 就地 upsert。
6. **ChatScreen.kt**：参数适配。
7. **ChatMessageContent.kt**：约 50 处引用修改（最大工作量）。
8. **streamingPartTexts key 全局替换**：`"$msgId:$pId"` → `pId`。
9. **测试**：`AppStateTest`（新字段）+ `MainViewModelTest`（SSE handler 行为）+ `ChatMessageContentTest`（渲染参数）。

**最大风险**：ChatMessageContent.kt 变更覆盖面大（1800+ 行, 30+ composable functions）。缓解措施：
- 先用 IDE 自动重构批量替换 `it.info` → `it`（message 本身就是 info）
- 新增 `partsByMessage` 参数沿 Compose 树传递
- 每步 commit 前跑全量单元测试（260 tests）+ Lint

---

## 8. 与现有设计文档的关系

### 8.1 design.md — 视觉层不变

V3 是**数据/存储/同步层重构**，不涉及视觉设计。`design.md` 中定义的：
- Quiet Tech 色板（BgDark #0B0C0E 等）
- 卡片三态语言（信息/操作/状态）
- Composer 语音轨结构
- Toolbar 描边 chip 风格
- Session 列表分区

**全部保持不变**。UI 层只改数据来源，不改视觉呈现。

### 8.2 v2-redesign-plan.md — 历史参考

V2 plan 记录了 0.1.4 的视觉改造实施过程。V3 不重复那些内容。V2 的架构决策（D1-D7）在 V3 中继续生效。

### 8.3 opencode-web-style-reference.md — 视觉 token 参考

本文件非 V3 相关。V3 的数据模型对齐的是 `oc-ref/packages/app/src/context/server-session.ts`（web 的 store/Sync 实现），不是视觉 token。

---

## 9. 风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| `message.part.delta` 事件 server 不发 | 低 | 添加 handler 无副作用；如果发，修 bug；如果不发，code path 不触发 |
| `message.updated` payload 不含完整 info | 中 | 先解析确认字段存在，无 info → 保持 0.1.13 行为（不做操作） |
| 初始 limit=2 导致 UI 渲染不完整 | 中 | 先保持 limit=30，Phase 4 后期验证 server 行为后再降 |
| ChatMessageContent.kt 大面积改动引入 bug | 高 | 渐进式 commit，每步跑全量测试；先用 30 不变验证 store 分离正确性，再调降 limit |
| 删除 watchdog 后 SSE 断连无 fallback | 低 | SSE reconnect 机制在 `connectSSE()` flow 内自动重连；前台恢复 throttle 覆盖后台断连场景 |
| Part 模型缺失字段 | 中 | 对比 web Part interface 和 Android Part 序列化类，补缺字段 |

---

## 10. 不做清单

- 不引入 Room/SQLite 本地缓存（D2 已决策）
- 不改变 SSE 连接机制（保持现有 `connectSSE()` flow）
- 不改变 cursor 分页逻辑（现有 `X-Next-Cursor` 方案不变）
- 不在 Phase 1-3 阶段改初始 limit（保持 30）
- 不做 SSE `Last-Event-ID` 续传（协议层不支持）
- 不引入字段级 Part 就地更新（Phase 2 只做 streamingPartTexts 累加，不做 `part[field] += delta`）

---

## 11. 交付标准

| 检查项 | 标准 |
|---|---|
| 编译 | `./gradlew assembleRelease` 成功 |
| 单元测试 | 260 tests 全过 |
| ADB 流量 | session 生命周期内 `/message?limit=30` 仅在 `message.created` 和 loadMore 出现 |
| ADB 内存 | 无 `Heap oversized, notify system.`（已回退 largeHeap） |
| ADB 进程 | 同 session 持续 5 分钟不消失 |
| CrashLogger | `crashes/` 目录无新文件 |
| UI 视觉回归 | Chat/ChatTopBar/ChatInputBar/ChatMessageContent 与 0.1.13 视觉一致 |
