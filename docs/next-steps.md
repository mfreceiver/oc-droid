# 下一步开发任务

> 2026-06-28 · 基于 architecture-v3-sse-trust.md 和 backlog-p1-p5.md 整理
> 上游（grapeot/opencode_android_client）基线：2s 忙碌轮询 + 无 cursor 分页 + 无 OOM cap
> 我们 fork 已领先上游 9 个版本（0.1.6→0.1.14）

---

## S0 🔴 OOM 直接修复（~15 行 diff，立即出包）

| # | 改动 | 位置 | 语言 |
|---|---|---|---|
| S0a | 初始 load limit 30→2 | `launchLoadMessages` 第 247 行 | kotlin |
| S0b | loadMore limit 30→200 | `launchLoadMoreMessages` 约第 350 行 | kotlin |
| S0c | loadMore 触发改用 `hasMoreMessages` | `ChatMessageContent.kt` 第 96/146/189 行 + `ChatScreen.kt` 第 193 行 | kotlin |

**理由**：web 抓包证实 `limit=200` 历史页 ≤3.6 MB，16MB cap 完全安全。初始 load 30→2 对齐 web `initialMessagePageSize=2`。

**旁效**：loadMore trigger 从 `messages.size >= 30` 改 `hasMoreMessages`——初始 2 条后也能正常触发滚动加载。

---

## S1 🔴 Phase 1: 删除残余 reload（~120 行删除，纯减法）

| # | 改动 | 文件 |
|---|---|---|
| S1a | 删除 `watchdogJob` / `startStreamWatchdog` / `stopStreamWatchdog` / `lastSseProgressAtMs` / `WATCHDOG_*` 常量 | `MainViewModel.kt` |
| S1b | `session.status` handler：去 reload（`onRefreshMessages`）、去 streaming clear、去 watchdog start/stop。只保留 `sessionStatuses` set | `MainViewModelSyncActions.kt` |
| S1c | 删除 `onLastSseProgress` / `onSessionBecameBusy` / `onSessionBecameIdle` 三个 callback 及其 wiring | `MainViewModelSyncActions.kt` + `MainViewModel.kt` |
| S1d | `onForegroundChanged` 前台 reload 加 15s 缓存（新增 `lastLoadAtMs` 字段） | `MainViewModel.kt` |
| S1e | `cancelSseAndWatchdogForReconfigure` → 改名为 `cancelSseForReconfigure`（去 watchdog stop） | `MainViewModel.kt` |
| S1f | `selectSession` 中的 `stopStreamWatchdog()` 调用删除 | `MainViewModel.kt` |

**理由**：对齐 web——web 无 watchdog、无 idle reload、无前台 reload。唯一保留的 reload 触发：`message.created`（结构事件）和 loadMore（用户主动）。

---

## S2 🟡 Phase 2: 添加 `message.part.delta` handler（~40 行新增）

| # | 改动 | 文件 |
|---|---|---|
| S2a | 新增 `"message.part.delta"` case（解析 `{sessionID, messageID, partID, field, delta}`） | `MainViewModelSyncActions.kt` |
| S2b | 累加 delta 到 `streamingPartTexts[partId]` | 同 |

**理由**：web 有独立的 `message.part.delta` 事件（与 `message.part.updated` 不同）。如果 server 发这个事件，当前 Android 完全丢失流式文本。

---

## S3 🟠 Phase 3: `message.updated` 就地修补（~30 行）

| # | 改动 | 文件 |
|---|---|---|
| S3a | 解析 SSE event 中的完整 `info` 对象 | `MainViewModelSyncActions.kt` |
| S3b | 在 `AppState.messages` 中按 ID 就地替换 | 同 |

**前提**：需确认 server 的 `message.updated` payload 含完整 `info`。不含则保持当前 0.1.14 行为（不操作）。

---

## S4 🔴 Phase 4: 拆分 message/part 存储（~400 行，最大重构）

| # | 改动 | 文件 |
|---|---|---|
| S4a | `AppState.messages: List<MessageWithParts>` → `messages: List<Message>` + `partsByMessage: Map<String, List<Part>>` | `MainViewModel.kt` |
| S4b | `streamingPartTexts` key 从 `"msgId:partId"` → `partId` | 同 |
| S4c | `MessagesPage` 拆分 info + parts | `OpenCodeRepository.kt` |
| S4d | `launchLoadMessages` / `launchLoadMoreMessages` 分存 messages + partsByMessage | `MainViewModelSessionActions.kt` |
| S4e | `message.part.updated` handler → 就地 upsert `partsByMessage[mid]` | `MainViewModelSyncActions.kt` |
| S4f | ChatMessageContent 约 50 处引用改写（`it.parts` → `partsByMessage[it.id]`） | `ChatMessageContent.kt` |
| S4g | ChatScreen 参数适配 | `ChatScreen.kt` |
| S4h | ChatMessageList 参数适配 | `ChatMessageContent.kt`（同文件） |

**收益**：初始 load limit=2 后 UI 可以正常渲染（因为 parts 已经从 API 响应 + SSE 填充到 `partsByMessage` 中）。

---

## P1–P5 待办（已有方案）

| 优先级 | 项 | 方案 | 规模 |
|---|---|---|---|
| P1 🔴 | 卡片视觉修正 | `docs/ui-card-optimization-plan.md` | ~200 行 |
| P2 🔴 | 协议类型代码生成 | openapi.json→Kotlin `@Serializable` | 新工具 |
| P3 🟡 | 模块化拆分 | ChatMessageContent 1812→12 文件 | 结构重构 |
| P4 🟡 | SVG→VectorDrawable | 106 个脚本批量转换 | 脚本 |
| P5 🟢 | i18n + tone + 错误卡 | 小块补齐 | 分散 |

---

## 建议执行顺序

```
S0 (OOM fix) ─── 0.1.15，立即
    │
S1 (删 watchdog) ─── 0.1.15，与 S0 同包
    │
S2 (delta handler) ─── 0.1.16
    │
S3 (message in-place) ─── 0.1.16，与 S2 同包
    │
S4 (split store) ─── 0.1.17，大型重构
    │
P2 (协议生成) → P1 (卡片) → P3 (拆分) → P4 (icon) → P5 (完善)
```

## S0+S1 合并后，session 生命周期内的流量变化

```
当前 (0.1.14):
  打开 session → GET /message?limit=30 (~5-30MB)
  streaming 中 → watchdog GET /message?limit=30 × N
  session idle → GET /message?limit=30 × 1
  回前台 → GET /message?limit=30 × 1

S0+S1 后:
  打开 session → GET /message?limit=2 (~500KB max)
  streaming 中 → 0 次 API 调用
  session idle → 0 次 API 调用
  回前台 (<15s) → 0 次 API 调用
  用户上滚历史 → GET /message?limit=200 (~0.6-3.6MB, 用户触发)
  message.created → GET /message?limit=2

会话总流量: 当前 ~50-450MB → 目标 ~2-6MB（~97% 削减）
```
