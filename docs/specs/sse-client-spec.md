# ocdroid 客户端 SSE 规约

> 本文件是 **ocdroid 客户端侧**对 opencode SSE 流（与省流模式下的 oc-slimapi
> curated SSE 流）的连接、解析、重连、生命周期规约。**模式无关**——legacy 直连
> opencode 与省流（slimapi）模式都适用，差异在 §8 集中列表。
>
> **与服务端策展 SSE 的边界**：oc-slimapi 服务端的 `/slimapi/events` 策展行为
> （per-directory 共享订阅、delta 合批、`thin.session.dirty` 合并、`event:resync`
> 信号、queue=256 背压）由
> [`oc-slimapi/docs/design-v2.md`](../../oc-slimapi/docs/design-v2.md) §1.10 与
> [`oc-slimapi/INTERFACE_MAP.md`](../../oc-slimapi/INTERFACE_MAP.md) §3 权威定义；
> 本文件**不复述其内容**，只在差异点交叉引用。

---

## 1. 目的与边界

| 项 | 说明 |
|---|---|
| **覆盖** | 客户端如何发起 SSE 连接、解析 `data:` 帧、按 event-type 分派、重连退避、生命周期归属、错误状态。 |
| **不覆盖** | 服务端如何策展/合批/过滤事件（→ slimapi design-v2 §1.10）；服务端事件 schema 本身（→ opencode 上游）；UI 如何渲染（→ `ui/chat/`、`ui/controller/`）。 |
| **适用模式** | legacy 直连 opencode（默认 `http://localhost:4096`）与省流（slimapi sidecar）模式都遵循同一规约；模式差异集中在 §8。 |
| **权威源** | 代码基线为本仓库 `app/src/main/java/`；slimapi 行为以 `oc-slimapi/INTERFACE_MAP.md` 为准（注意 fix-3 在改其字面路径，本文不依赖过渡态字面量）。 |

涉及文件（按职责）：

| 文件 | 职责 |
|---|---|
| [`data/api/SSEClient.kt`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt) | OkHttp `EventSource` 封装、退避、心跳看门狗、frame→`SSEEvent` 解码 |
| [`data/api/SseLogFilter.kt`](../app/src/main/java/cn/vectory/ocdroid/data/api/SseLogFilter.kt) | 噪音 event-type 名单（仅日志） |
| [`data/model/SSE.kt`](../app/src/main/java/cn/vectory/ocdroid/data/model/SSE.kt) | `SSEEvent` / `SSEPayload` wire 形状 |
| [`service/streaming/ServiceSseConnectionOwner.kt`](../app/src/main/java/cn/vectory/ocdroid/service/streaming/ServiceSseConnectionOwner.kt) | Service-lifetime 唯一 collector、transport-readiness、服务级重试预算 |
| [`service/streaming/SseRecoveryPolicy.kt`](../app/src/main/java/cn/vectory/ocdroid/service/streaming/SseRecoveryPolicy.kt) | 服务级重试时间表（30s / 2m / 5m + ±20% 抖动） |
| [`service/bridge/SseEventBridge.kt`](../app/src/main/java/cn/vectory/ocdroid/service/bridge/SseEventBridge.kt) | control vs delta 双通道、溢出标记 dirty |
| [`data/repository/OpenCodeRepository.kt`](../app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt) `connectSSE` | base URL + Basic Auth + directory 注入入口 |
| [`data/repository/http/OkHttpClientFactory.kt`](../app/src/main/java/cn/vectory/ocdroid/data/repository/http/OkHttpClientFactory.kt) `sseClient` | SSE 专用 OkHttp（read timeout = 0） |
| [`data/repository/http/DirectoryHeaderInterceptor.kt`](../app/src/main/java/cn/vectory/ocdroid/data/repository/http/DirectoryHeaderInterceptor.kt) | `X-Opencode-Directory` 注入 + `?directory` query 镜像 |
| [`data/repository/HostConfig.kt`](../app/src/main/java/cn/vectory/ocdroid/data/repository/HostConfig.kt) | 当前所选 server 的 base URL / 凭据 / hostPort |

---

## 2. 连接模型

### 2.1 base URL 派生

`OpenCodeRepository.connectSSE(directory)` 读 `HostConfig.baseUrl` /
`username` / `password`，转发给 `SSEClient.connect(...)`（[`OpenCodeRepository.kt:890`](../app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt)）。

- **legacy 模式**：`baseUrl = http://localhost:4096`（或用户配置的 opencode
  直连/stunnel 14096 入口）。
- **省流模式**：`baseUrl = http://localhost:4097`（或用户配置的 sidecar
  /stunnel 14097 入口）。**省流 = 选 slimapi 为当前 server**，所有 SSE 流量
  随之派生（详见 [`slim-mode-api-routing.md`](./slim-mode-api-routing.md) §1）。

`SSEClient` 自己**不持有**任何 base URL——它每次 `connect()` 都从外部接受
`baseUrl`，连接生命周期内不再读 `HostConfig`。host 切换由上层
`ServiceSseConnectionOwner` 通过 cancel + 重建 collector 实现（见 §6）。

### 2.2 端点

| 端点 | 来源 | 模式相关性 |
|---|---|---|
| `GET {baseUrl}/global/event` | 硬编码于 [`SSEClient.kt:102`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt) + 216 | **legacy 默认**；opencode 全局事件流（per-process，跨所有 session/workdir）。客户端订阅后**自己**做 session/workdir 过滤。 |
| `GET {baseUrl}/event?directory=<dir>` | opencode 也支持 | 与 `/global/event` 等价的 directory-scoped 入口；当前客户端**不使用**（统一打 `/global/event` + header 过滤）。 |
| `GET {baseUrl}/slimapi/events` | slimapi 策展流（v1-contract §3） | **省流专属**；实例级全实例上游（GlobalBus），每事件自带 `directory`。无 `sessionId` / `?stream` / per-directory hub（v1 简化）。 |

> **路径稳定性说明**：`/global/event` 与 `/event` 是 opencode 上游稳定 legacy
> 形态。`/slimapi/events` 为 v1 契约（v1-contract §3）扁平路径，无 `/v1/` 前缀。

### 2.3 directory 路由头

每个 SSE 请求都带：

- `X-Opencode-Directory: <abs-workdir>`（若 `directory != null`，由
  [`SSEClient.kt:114`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt) 显式注入）。
- `?directory=<abs-workdir>` query 镜像（由
  [`DirectoryHeaderInterceptor.intercept`](../app/src/main/java/cn/vectory/ocdroid/data/repository/http/DirectoryHeaderInterceptor.kt)
  在 GET/HEAD 上自动追加，以便反向代理剥自定义头时仍能正确路由）。

`directory == null` 时**不注入** header——上层 `ServiceSseConnectionOwner` 会
把 `identity.normalizedWorkdir.ifBlank { null }` 传入（[`ServiceSseConnectionOwner.kt:362`](../app/src/main/java/cn/vectory/ocdroid/service/streaming/ServiceSseConnectionOwner.kt)），
故空 workdir 退化为 unscoped（让服务端用 process.cwd()）。

### 2.4 SSE 请求头清单

| 头 | 值 | 注入点 | 模式相关性 |
|---|---|---|---|
| `Accept` | `text/event-stream` | [`SSEClient.kt:103`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt) | 【模式无关】 |
| `Cache-Control` | `no-cache` | [`SSEClient.kt:104`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt) | 【模式无关】 |
| `Authorization` | `Basic <base64(user:pass)>` | [`SSEClient.kt:106-110`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)（仅当 username+password 都非空） | 【模式无关】；省流模式下 slimapi sidecar 不做应用层鉴权（外层 stunnel mTLS 已完成），但携带无害 |
| `X-Opencode-Directory` | abs workdir | [`SSEClient.kt:114-116`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt) | 【模式无关】；省流模式下 slimapi catch-all 会读它转发给 opencode |
| `X-Slimapi-Version` | `1` | [`SlimapiVersionInterceptor`](../app/src/main/java/cn/vectory/ocdroid/data/repository/http/SlimapiVersionInterceptor.kt)（按 `/slimapi/` 前缀注入） | **【省流专属 must】**；slimapi 要求每个 `/slimapi/**` 请求（含 SSE）必带此头，否则 `400 {"code":"version_required"}`（v1-contract §1） |

### 2.5 OkHttp 客户端

`OkHttpClientFactory.sseClient(hostPort)`（[`OkHttpClientFactory.kt:130`](../app/src/main/java/cn/vectory/ocdroid/data/repository/http/OkHttpClientFactory.kt)）：

- base chain（SSL via TOFU/mTLS + 缓存 + 鉴权 + directory + cache-control + 流量计数 + 日志 gate）。
- `readTimeout(0)` —— SSE 长连接无读超时（关键：REST 30s 不能用于 SSE）。
- `connectTimeout(10s)`。
- **不**挂 `ResponseSizeGuardInterceptor`（SSE 是流，不是一次性 body）。

host 切换时 `OpenCodeRepository.rebuildClients()`（[`OpenCodeRepository.kt:202`](../app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt)）
整体重建包括 `sseHttp` + `sseClient`。

---

## 3. 帧解析

### 3.1 OkHttp EventSource 回调

`SSEClient.connectOnce`（[`SSEClient.kt:99`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)）
创建 `EventSourceListener`：

- `onOpen` → 仅记日志（[`SSEClient.kt:135`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)）。
- `onEvent(id, type, data)` → 见 §3.2。
- `onClosed` → `close(Exception("SSE connection closed by server"))`（带 double-close CAS 守卫）。
- `onFailure(t, response)` → `close(t ?: Exception("SSE connection failed"))`。

### 3.2 data 载荷形状与归一化

opencode 在 `/global/event` 上发出的 `data:` 载荷是 **wrapped** 形状：

```json
{
  "directory": "/abs/workdir",
  "payload": {
    "type": "message.part.delta",
    "properties": { "sessionID": "ses_xxx", "...": "..." }
  }
}
```

`SSEClient` 用一个 lenient `Json { ignoreUnknownKeys = true; isLenient = true;
coerceInputValues = true }`（[`SSEClient.kt:56-60`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)）
直接 `decodeFromString<SSEEvent>(data)`（[`:159`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)）。

`SSEEvent` 模型（[`data/model/SSE.kt`](../app/src/main/java/cn/vectory/ocdroid/data/model/SSE.kt)）：

```kotlin
data class SSEEvent(val directory: String? = null, val payload: SSEPayload)
data class SSEPayload(
    val type: String,
    val properties: JsonObject? = null
)
```

- `payload.type` 是事件分派键（§4）。
- `payload.properties` 是开放 JsonObject，调用方用 `getString("sessionID")` /
  `getJsonObject("message")` / `getAs<T>(key, parse)` 取字段（避免上游加字段
  打破客户端）。
- `directory` 顶层字段允许客户端识别事件归属的 workdir（用于多 workdir 路由）。

### 3.3 特殊 data 值

- 空白 / `[DONE]` → 直接丢弃，不尝试解码（[`SSEClient.kt:157`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)）。
- 解码抛异常 → 记录 `DebugLog.w("SSEClient", "Skipping malformed SSE event: ...")`
  并**跳过该帧**（不污染流，不重连）。见 [`SSEClient.kt:185-189`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)。

### 3.4 省流模式下的帧类型（v1-contract §3）

slimapi `/slimapi/events` 输出 wrapped `SSEEvent{directory,payload}` 形状——**帧解析
逻辑无需改动**。v1 契约定义以下帧类型：

| 帧类型 | wire 形状 | 说明 |
|---|---|---|
| `session.digest` | wrapped（`payload.type = "session.digest"`），properties 含 `{sessionID, directory, status?, messageID?, updatedAt?, archived?, deleted?}` | debounce 250ms/session，仅发有变化的字段。`archived` ← `session.updated` 的 `info.time.archived`（有值→true/时间戳）。客户端据此 upsert session 列表。 |
| `question.asked` / `question.v2.asked` | wrapped（`payload.type`），properties 含 `{directory, type, properties}` | **立即直推**——不 debounce。 |
| `permission.asked` / `permission.resolved` / `permission.v2.asked` / `permission.v2.resolved` | wrapped（同上） | **立即直推**。 |
| `server.connected` | wrapped | 订阅即吐。 |
| `server.heartbeat` | wrapped | 10s 间隔。 |
| `resync` | **SSE `event:` 字段为 `resync`**，data 为 `{"reason":"reconnect_no_replay"}` | 重连首帧，无 replay。客户端必须主动 catch-up（§5.5）。 |

> **丢弃**（v1 不再使用）：`?stream`、`text.delta`、`message.part.*`、`tool.*`、
> `sessionId` 参数、per-directory hub。legacy opencode 模式仍可能收到这些帧
> （opencode 上游发出），客户端按现有 reducer 处理即可。

### 3.5 event-type 分派

`payload.type` 拿到后：

1. **日志节流**（仅日志）：若 `type ∈ NOISY_SSE_LOG_EVENTS`（[`SseLogFilter.kt`](../app/src/main/java/cn/vectory/ocdroid/data/api/SseLogFilter.kt)）
   则不写 DebugLog.d。当前名单：
   - `message.part.delta`、`message.part.updated`（per-token 流式，每秒数十到数百次）
   - `server.heartbeat`、`server.connected`
   - `plugin.added`、`catalog.updated`、`integration.updated`（server-internal 突发）
2. **正常日志**：`DebugLog.d("SSE", "event type=$type session=${sessionID ?: "-"}")`。
3. **发送下游**：`trySend(Result.success(event))`（带 double-check 关闭守卫，
   [`SSEClient.kt:183-184`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)）。

**事件分派本身不在 `SSEClient` 做**——它只发 `Result<SSEEvent>` 到 Flow；
具体 event-type 的处理在 [`SseEventBridge`](../app/src/main/java/cn/vectory/ocdroid/service/bridge/SseEventBridge.kt)
（control vs delta 双通道，§4）+ [`SessionSyncCoordinator`](../app/src/main/java/cn/vectory/ocdroid/ui/controller/)
（业务 fold）+ [`SseNotificationBridge`](../app/src/main/java/cn/vectory/ocdroid/service/streaming/SseNotificationBridge.kt)
（系统通知）。

---

## 4. 事件类型处理

### 4.1 双通道分流（control vs delta）

[`SseEventBridge.isControlEvent`](../app/src/main/java/cn/vectory/ocdroid/service/bridge/SseEventBridge.kt)
（第 226 行）把帧分到两条 `Channel`：

| 通道 | 容量 | event-type 谓词 | 用途 |
|---|---|---|---|
| **control** | 64（FIFO bounded） | `type == "session.status"`、`type == "server.connected"`、`type.startsWith("permission.")`、`type.startsWith("question.")` | 这些事件**绝不能**被 delta 洪流挤掉（FGS spec §11）。 |
| **delta** | 256（FIFO bounded） | 其它一切（主要是 `message.part.delta` / `message.part.updated` / `message.appended` / `message.updated` 等） | 渲染帧；溢出时 `markDeltaOverflow` 把对应 `sessionID` 标 dirty（后由 REST reconcile 恢复）。 |

溢出策略：delta 通道满 → `trySend` 失败 → 把该 sessionID 加入 `_dirtySessions`
（共享 `MutableStateFlow<Set<String>>`），droppedDeltaCount++，**不**重连、**不**
丢 control 通道。

### 4.2 客户端实际响应的 event 类型

> 完整性来自全仓库 grep + 上述 bridge/notification/reducer 引用。打勾=已有
> reducer；空圈=opencode 上游会发但客户端当前不处理。

| event-type | 客户端行为 | 主要代码 |
|---|---|---|
| ✅ `server.connected` | 服务端连接确认；触发 `ForegroundCatchUpController` 评估 gap catch-up（15s–5min 内不抑制，让 connected 驱动）。`connectedOnce` 翻 true；后续 connected 帧（重连后）触发 reconciliation。 | [`ForegroundCatchUpController.kt:191`](../app/src/main/java/cn/vectory/ocdroid/ui/controller/ForegroundCatchUpController.kt)、[`SseSyncState.kt:97`](../app/src/main/java/cn/vectory/ocdroid/ui/controller/SseSyncState.kt) |
| ✅ `server.heartbeat` | 仅 reset 心跳看门狗（§5.3）；不分派下游（其实会被发送，但无 reducer 消费）。 | [`SSEClient.kt:155`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt) |
| ✅ `session.status` | busy/idle 状态；驱动 L1→L2 FGS 升级、未读 soak、`resetLimit`、`session.busy` poller；通知桥按 idle+root+未读发通知。 | [`SseNotificationBridge.kt:270`](../app/src/main/java/cn/vectory/ocdroid/service/streaming/SseNotificationBridge.kt)、[`MessageActions.kt:122`](../app/src/main/java/cn/vectory/ocdroid/ui/MessageActions.kt) |
| ✅ `session.created` / `session.updated` | session 列表 upsert / invalidateTree（跨客户端归档/创建同步）；触发 SessionListActions 重排。 | [`AppAction.kt:69`](../app/src/main/java/cn/vectory/ocdroid/ui/AppAction.kt)、[`AppStateSlices.kt:522`](../app/src/main/java/cn/vectory/ocdroid/ui/AppStateSlices.kt) |
| ✅ `message.appended` / `message.updated`（insert 分支） | 新消息追加到 in-memory sessionWindowCache（按 messageId 去重 replace，非 append）；驱动未读计数。 | [`SessionSwitcher.kt:264`](../app/src/main/java/cn/vectory/ocdroid/ui/controller/SessionSwitcher.kt) `appendMessageIfCached`、[`ControllerEffect.kt:100`](../app/src/main/java/cn/vectory/ocdroid/ui/controller/ControllerEffect.kt) |
| ✅ `message.updated`（existing 分支） | 同 ID 字段更新（如 cost/tokens）。Server 1.17.11+ 行为；客户端按 messageId 替换。 | [`MessageActions.kt:251`](../app/src/main/java/cn/vectory/ocdroid/ui/MessageActions.kt) |
| ✅ `message.part.delta` / `message.part.updated` | per-token 流式增量；合并到当前 streaming 消息的 part。高频事件，仅日志节流。 | [`SessionSyncCoordinator`](../app/src/main/java/cn/vectory/ocdroid/ui/controller/) fold、[`SseLogFilter.kt`](../app/src/main/java/cn/vectory/ocdroid/data/api/SseLogFilter.kt) |
| ✅ `message.part.removed` | part 删除（revert 后）。 | [`SessionSyncCoordinator`](../app/src/main/java/cn/vectory/ocdroid/ui/controller/) |
| ✅ `question.asked` / `question.v2.asked` | 系统通知（dedup key `q:${question.id}`）；UI 也直接渲染 QuestionCardView。 | [`SseNotificationBridge.kt:217`](../app/src/main/java/cn/vectory/ocdroid/service/streaming/SseNotificationBridge.kt)、[`AppLifecycleMonitor.handlePendingQuestion`](../app/src/main/java/cn/vectory/ocdroid/di/AppLifecycleMonitor.kt) |
| ✅ `permission.*` | 不发系统通知（由 30s poller 兜底，避免双发）；UI 渲染 PermissionCardView。 | [`SseNotificationBridge.kt:219-221`](../app/src/main/java/cn/vectory/ocdroid/service/streaming/SseNotificationBridge.kt) |
| ✅ `tool.*` / `patch.*` / `step-start` / `step-finish` | 折叠到 part 增量，触发渲染。 | [`SessionSyncCoordinator`](../app/src/main/java/cn/vectory/ocdroid/ui/controller/) |
| ✅ `plugin.added` / `catalog.updated` / `integration.updated` | 仅日志节流，无业务 reducer。 | [`SseLogFilter.kt`](../app/src/main/java/cn/vectory/ocdroid/data/api/SseLogFilter.kt) |
| ○ `thin.session.dirty` | **未处理**（迁移目标，§5.5） | — |
| ○ `event: resync`（SSE event 字段，非 payload.type） | **未处理**（迁移目标，§5.5） | — |
| ○ `event:resync` 之外的 server-defined event names | 不依赖 SSE `event:` 字段做分派（OkHttp `onEvent(type, data)` 收到的 `type` 参数**被丢弃**——客户端只按 `data.payload.type` 走）。`resync` 是当前唯一需要按 SSE event 字段分派的例外。 | [`SSEClient.kt:139-144`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt) |

---

## 5. 重连与退避

### 5.1 双层重试架构

| 层 | 范围 | 触发 | 实现 |
|---|---|---|---|
| **L1 — SSEClient 内部**（transport-level） | 单次连接的 retryWhen | flow 异常完成 / onFailure | [`SSEClient.kt:73-92`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt) |
| **L2 — Service 级别**（service-level outage） | 跨 L1 预算耗尽后的额外预算 | L1 用完 10 次仍未拿到任何有效帧 | [`ServiceSseConnectionOwner.launchSseCollector`](../app/src/main/java/cn/vectory/ocdroid/service/streaming/ServiceSseConnectionOwner.kt)（第 357 行）+ [`SseRecoveryPolicy`](../app/src/main/java/cn/vectory/ocdroid/service/streaming/SseRecoveryPolicy.kt) |

### 5.2 L1 参数

| 参数 | 值 | 出处 |
|---|---|---|
| 初始退避 | `1000ms` | `INITIAL_RETRY_DELAY_MS`（[`SSEClient.kt:38`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)） |
| 退避上限 | `30000ms` | `MAX_RETRY_DELAY_MS`（[`:39`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)） |
| 倍率 | `2.0`（指数） | `RETRY_MULTIPLIER`（[`:40`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)） |
| 抖动 | `±30%`（`1.0 + (Random.nextDouble() * 0.6 - 0.3)`） | [`SSEClient.kt:87`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt) |
| 最大尝试次数 | `10`（用完后抛 `SSEConnectionExhausted`） | `MAX_RETRY_ATTEMPTS`（[`:43`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)） |

退避序列（无抖动近似）：1s → 2s → 4s → 8s → 16s → 30s → 30s → 30s → 30s → 30s → exhaust。

每次重连**重新跑一次** `connectOnce`——即 OkHttp 起一条新的 `EventSource`，
重新建 TCP/TLS、重新带 directory header、重新协商。**没有**跨连接的事件
续传。

### 5.3 心跳看门狗（half-open 检测）

服务端每 ~10s 发一帧 `server.heartbeat` 作为 data 事件（[`SSEClient.kt:45-52`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)）。
`onEvent` 在**任意**帧（含 heartbeat）上 reset `lastEventAt`（[`:155`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)）。

看门狗协程（[`:229-244`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)）：

- 每 `HEARTBEAT_CHECK_INTERVAL_MS = 5000ms` 检查一次。
- `eventCount == 0`（首帧未到达）→ 跳过（cold-start grace）。
- `now - lastEventAt >= HEARTBEAT_TIMEOUT_MS = 30000ms`（3× heartbeat，容忍丢 2 帧）→ `eventSource.cancel()` → 触发 `onFailure` → `close` → `retryWhen` 退避。
- 看门狗协程异常**隔离**——永不拖垮父 flow（`:241 catch (_)`）。

### 5.4 L2 参数（service-level outage）

`SseRecoveryPolicy`：

- `attempts = 3`（在 L1 用完 10 次后的额外 3 次服务级重试）。
- 延迟表：`30s / 2m / 5m` + `±20%` 抖动。
- 每次 L2 尝试**重新跑一次** L1 全流程（重置 L1 的 10 次计数）。
- L2 也耗尽 → 调用 `onTerminalExhaustion()`（**每 outage 恰好一次**，由
  `exhaustedReportedForGen` 守卫），路由到 `StreamingLifecycleCoordinator.onDisconnect`
  → L3 teardown。

> **outage 边界**：一个 outage = 一个 transport generation。一次成功的
> current-identity 帧 reset 所有计数器（`retriesUsed = 0`、`gapEmittedForGen = -1L`、
> L1 重新跑），下一次失败开**新** outage。

### 5.5 resync 策略（与版本契约交叉）

| 模式 | 上游行为 | 客户端当前行为 | 缺口 |
|---|---|---|---|
| **legacy opencode V1**（`/global/event`） | 不发 `event: resync`；重连后**不补发**错过的 events（无 event store）。客户端依赖 `server.connected` + REST reconcile（`probeLatestMessageId` + `getMessagesPaged`）。 | `SseSyncState` 跟踪 `connectedOnce`；后续 `server.connected` 触发 catch-up（`ForegroundCatchUpController`）；gap 内的 `session.updated` **不补**——跨设备归档/创建可能丢，由用户主动刷新恢复。 | — |
| **省流 slimapi**（`/slimapi/events`） | 客户端重连时，**首帧**为 `event: resync` + data `{"reason":"reconnect_no_replay"}`；**不承诺 replay**（v1-contract §3）。 | **未处理 resync**——OkHttp `onEvent(type, data)` 的 `type == "resync"` 当前被忽略，data decode 失败被当 malformed 丢（§3.4）。 | **must**：在 EventSource 回调层识别 `type == "resync"` → 触发冷启动级 REST 快照 + SSE 增量（v1-contract §4：冷启动 = REST 快照 + SSE 增量；resync = 复用冷启动）。**不**依赖 Last-Event-ID replay（v1 明确无 replay）。 |

> **冷启动 & resync 流程**（v1-contract §4）：
> 1. 连接（及 resync）→ 客户端 GET `/slimapi/sessions` + `/slimapi/questions` +
>    `/slimapi/permissions`（+ 当前打开 session 的消息 `/since/{ts}`）→ SSE 接力增量。
> 2. **resync = 复用冷启动流程**（同一"加载初始状态"代码路径）。
> 3. 锚点 = `updatedAt` 时间戳；`/since/{ts}` 返回 `time.updated >= ts` 的骨架
>    （v1-contract §5）。

### 5.6 mutation 不双发

退避/重连**仅作用于 SSE 读通道**。POST 类 mutation（`/question/{id}/reply`、
`/session/{id}/permissions/{pid}` 等）**不重试**——上一次可能已被服务端接收，
双发会重复应答（design-v2 §3.5「GET 侧 circuit breaker；mutation 不双发」）。

---

## 6. 生命周期与归属

### 6.1 单一所有者

`ServiceSseConnectionOwner`（[`service/streaming/ServiceSseConnectionOwner.kt`](../app/src/main/java/cn/vectory/ocdroid/service/streaming/ServiceSseConnectionOwner.kt)）
是**进程内唯一**的活动 SSE collector 所有者。它运行在前台服务
（`SessionStreamingService`）的 `scope` 上，把帧 publish 到进程级 `SseEventStream`
（共享 SharedFlow），由 `SseEventBridge` 订阅并分流。

### 6.2 transport-readiness 契约（D4-B M3）

`connect(identity): SourceActivation` 是 `suspend fun`，返回：

| 返回值 | 含义 |
|---|---|
| `Ready` | **首帧有效 current-identity SSE 帧**到达 → transport 已验证。立即完成（不等待 REST status baseline）。 |
| `Rejected.TransportTimeout` | `TRANSPORT_READY_TIMEOUT_MS = 30000ms` 内无有效帧 → 取消 collector，B1 rollback。 |
| `Rejected.StaleIdentity` | identity 不再是当前（reconfigure epoch 已变）→ 不消耗网络重试预算。 |
| `Rejected.TofuPending` | TOFU 信任对话框未决 → 不重试（握手会以同样方式失败）。 |
| `Rejected.Exhausted` | L2 服务级预算耗尽 → `onTerminalExhaustion()` 一次。 |

### 6.3 前台 / 后台

SSE collector 由前台服务持有。后台时（服务被 stopForeground 降级或 stop）：
- `disconnectAndJoin(markGap = true)`（[`ServiceSseConnectionOwner.kt:622`](../app/src/main/java/cn/vectory/ocdroid/service/streaming/ServiceSseConnectionOwner.kt)）
  → cancel collector + 发 gap-dirty 信号一次（per-generation idempotent）。
- 重启服务后由 `ConnectionBootstrapEngine` → `connect(...)` 重新激活。

### 6.4 取消 / supersession

- **更新的 connect 抢占旧 connect**：`setupConnectLocked` 先 `markClosing(priorGen)`
  再 cancel `sseJob`（[`ServiceSseConnectionOwner.kt:283-285`](../app/src/main/java/cn/vectory/ocdroid/service/streaming/ServiceSseConnectionOwner.kt)）。
- **`disconnect()`**：同上，`markClosing` → cancel → bump generation（`:593-620`）。
- **`cancelForShutdown()`**：同步兜底，Service 销毁时（`:625-638`）。
- **closing 标记的作用**：让 collector 的 post-flow-break 出口识别「这是
  intentional closing（supersession / disconnect / transport-timeout /
  shutdown），不是 transport outage」→ 静默退出，**不**触发 gap/retry/exhaustion
  （D5 #1 关键修复，避免 post-Ready outage 漏报反向 bug）。

### 6.5 session 作用域

SSE 流本身**不限定 session**——legacy `/global/event` 跨所有 session。
session-level 过滤由下游 reducer（按 `payload.properties.sessionID`）做。

> **省流模式差异**：`/slimapi/events` 要求 `sessionId` repeated query（1–16，
> 必须 ∈ 该 directory）。这意味着客户端在 slimapi 模式下**必须**显式列出
> 关心的 session（当前 session + 后台监控的 root sessions）——见 §8。

---

## 7. 错误状态

### 7.1 HTTP 错误码

| 错误 | 客户端行为 | 出处 |
|---|---|---|
| `200 + text/event-stream` | 正常打开 EventSource，开始 `onEvent`。 | OkHttp EventSources factory |
| `401 / 403`（鉴权） | `onFailure` → 走 L1 退避（重复 10 次）；上层 controller 会触发 TOFU 或重新提示凭据。 | — |
| `404`（routeToken 透传 / 不存在） | 当前直接走 `onFailure` 退避。**省流模式迁移点**：slimapi 写路径 404 表示 routeToken 失效或 resource 不存在，应触发 pending 刷新而非重试（design-v2 §1.8）。 | 待加 reducer |
| `400 {"code":"version_required"}` / `version_incompatible` | **当前未特化** → onFailure 退避。**省流模式 must**：拦截器要确保 `X-Slimapi-Version` 头存在，否则会陷入 400 重连死循环（每次重连都缺头）。 | 待加拦截器（迁移 §9） |
| `5xx` | `onFailure` 退避；slimapi design-v2 §3.5 建议加 GET 侧 circuit breaker（连续 3 次 transport/5xx → 禁 thin 5min → half-open 探测）。当前客户端**未实现** circuit breaker——是迁移可选项。 | — |
| `503` slimapi `upstream_error` / `upstream_timeout` | slimapi 聚合接口（`/slimapi/questions`、`/slimapi/permissions`）全失败时返回；SSE 本身不会发 503。 | INTERFACE_MAP §2 |

### 7.2 连接中断

- TCP RST / TLS handshake 失败 → `onFailure(t, response)` → CAS 关闭 → L1 退避。
- 半开连接（NAT 静默超时） → 心跳看门狗（§5.3）30s 内无帧 → 强制 cancel →
  L1 退避。
- 双重 close 防护：`closed: AtomicBoolean` CAS（[`SSEClient.kt:132`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)），
  防止 `onClosed` 与 `onFailure` 都触发时 `close()` 抛
  `IllegalStateException`/`NoSuchElementException`。

### 7.3 解析错误 / 半截帧

- JSON decode 抛 → 跳该帧（§3.3），**不**重连、**不**记入错误。
- 残余 in-flight 帧（cancel 后 OkHttp pipeline 仍在投递）：`onEvent` 入口
  先查 `closed.get()` → 早退（[`SSEClient.kt:152`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)），
  decode 后再 double-check（[`:183`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)）。
  避免 `trySend` 到已关闭 channel 抛异常，烧 retryWhen 预算。

### 7.4 terminal exhaustion 上报

L2 也耗尽 → `sharedStateStore.mutateConnection { Disconnected }` +
`onTerminalExhaustion()` → L3 teardown。UI 看到 `ConnectionPhase.Disconnected`
banner（`error_sse_failed` 字符串 + `R.string.error_sse_failed` UiEvent.Error）。

---

## 8. 模式差异表（legacy-opencode vs 省流 slimapi）

| 项 | legacy 模式（`/global/event`） | 省流模式（`/slimapi/events`） | 标记 |
|---|---|---|---|
| base URL 来源 | `HostConfig.baseUrl`（默认 `http://localhost:4096`） | `HostConfig.baseUrl`（用户选 slimapi 入口，典型 `http://localhost:4097` 或 stunnel `14097`） | 【模式无关】（base URL 派生机制相同） |
| 端点路径 | `GET {base}/global/event` | `GET {base}/slimapi/events` | **【省流专属】** |
| directory 必填 | 否（global 流，header 可选） | 否（实例级全实例流，每事件自带 `directory` 字段） | 【模式无关】 |
| sessionId 参数 | 无 | 无（v1 简化，去掉 per-directory hub 和 sessionId repeated） | 【模式无关】 |
| `X-Opencode-Directory` header | 可选（建议带） | slimapi 透传给 opencode，仍有效 | 【模式无关】 |
| `X-Slimapi-Version: 1` header | 不发（opencode 不识别） | **必须**（缺 → `400 version_required`；区间外 → `400 version_incompatible`） | **【省流专属 must】** |
| 流内容来源 | opencode 原始事件流（per-process 全量） | slimapi 策展后事件流（实例级 GlobalBus，跨目录，每事件自带 `directory`） | **【省流专属】** |
| 帧类型 | `session.status` / `message.*` / `question.*` / `permission.*` / `tool.*` 等 | `session.digest`（debounce 250ms，含 `archived`）+ `question.asked` / `permission.*`（直推）+ `server.connected` / `server.heartbeat` / `resync` | **【省流专属】** |
| `text.delta` 输出 | 无（每 part.updated 直发） | 无（v1 去掉 `?stream` 和 `text.delta`） | 【模式无关】 |
| 重连退避参数（L1） | 1s→30s 指数 ±30%，max 10 次 | 同（`SSEClient` 不区分模式） | 【模式无关】 |
| L2 服务级重试 | 30s/2m/5m ±20%，3 次 | 同 | 【模式无关】 |
| 心跳看门狗 | 30s 超时（依赖 opencode ~10s heartbeat） | 同（slimapi 透传 heartbeat；但 slimapi 自身也产 `server.connected`） | 【模式无关】 |
| resync 信号 | 无（依赖 server.connected + REST reconcile） | 重连首帧 `event: resync` + `{"reason":"reconnect_no_replay"}`；无 replay | **【省流专属】** |
| 跨连接 replay | 无（无 event store） | 无（无 event store，明示 `reconnect_no_replay`） | 【模式无关】 |
| 冷启动流程 | REST 快照 + SSE 增量（`server.connected` 触发） | REST 快照（`/slimapi/sessions` + `/slimapi/questions` + `/slimapi/permissions` + `/since/{ts}`）+ SSE 增量；resync = 复用冷启动（v1-contract §4） | **【省流专属】** |
| 消息拉取锚点 | `messageID`（`probeLatestMessageId`） | `updatedAt` 时间戳（`/since/{ts}` 返回 `time.updated >= ts`，v1-contract §5） | **【省流专属】** |
| mutation 重试 | 不重试 | 不重试（routeToken 一次失效即止） | 【模式无关】 |
| 背压策略 | OkHttp 内部缓冲 | 慢消费者 queue=256 满即 STOP 断开（强制 catch-up） | **【省流专属】** |
| gzip | 不 gzip（SSE wire 透明） | 不 gzip | 【模式无关】 |

---

## 9. 待定 / 缺口

| # | 缺口 | 影响 | 关联迁移项 |
|---|---|---|---|
| ~~G1~~ | ~~`X-Slimapi-Version` 头未注入~~ | ~~省流模式 SSE 全部 400~~ | **已落地**（`SlimapiVersionInterceptor`） |
| G2 | SSE 端点仍硬编码 `/global/event` | 省流模式打不到 `/slimapi/events` | 端点切换（`slim-mode-api-routing.md` §9 M5） |
| G3 | `event:resync` 帧未识别 | 省流模式重连后 catch-up 不触发，消息可能 stale | EventSource 回调层加 type 分派（§5.5） |
| ~~G4~~ | ~~`thin.session.dirty` 事件无 reducer~~ | ~~省流模式非目标 session 的更新信号丢失~~ | **v1 去掉**：v1-contract §3 不再有 `thin.session.dirty`；改为 `session.digest` debounce 250ms |
| ~~G5~~ | ~~`sessionId` 列表构造逻辑缺失~~ | ~~省流模式 `/slimapi/events` 要求 repeated query~~ | **v1 去掉**：v1-contract §3 去掉 `sessionId` 参数，实例级全实例流 |
| ~~G6~~ | ~~`?stream=true` 参数未发~~ | ~~省流模式不下发 `text.delta`~~ | **v1 去掉**：v1-contract §3 去掉 `?stream` 和 `text.delta` |
| ~~G7~~ | ~~`Last-Event-ID` 头未发~~ | ~~行为上等价~~ | **v1 去掉**：v1-contract §3 明确无 replay，`Last-Event-ID` 不再需要 |
| G8 | GET 侧 circuit breaker 未实现 | 连续 sidecar 5xx 不会熔断 thin 路径 | v1-contract §6 推荐项 |
| ~~G9~~ | ~~health 自检未读取~~ | ~~客户端无版本兼容性提示~~ | **已落地**（`ServerCompatProfile.isSlimapiClientAccepted()` + M2 自检） |
| G10 | `/global/event` 在 opencode V1 上的 `?directory` 入口从未使用 | 当前 OK；未来若 opencode 改 `/global/event` 行为需重审 | 监控项 |
| G11 | 双 close 守卫依赖 `AtomicBoolean`；`onClosed` 与 watchdog cancel 路径都覆盖 | 当前已修复（§7.2），但若新增关闭路径须保证 `markClosing` 调用 | 维护性约束 |
| G12 | legacy 模式下 `server.connected` 双重帧去重 | `SseSyncState` 已实现（`duplicateServerConnectedWithinConnection`）；省流模式 slimapi 也产 `server.connected`，行为应等价，但 slimapi 自身重连会多产一帧 | 监控项 |

---

## 10. 修订记录

| 日期 | 改动 |
|---|---|
| 2026-07-18 | 初版。从源码提取；交叉引用 oc-slimapi design-v2 §1.10、§9 与 INTERFACE_MAP §3。 |
| 2026-07-18 | 对齐 v1-contract.md：去掉 `sessionId` / `?stream` / `text.delta` / `thin.session.dirty` / per-directory hub；更新帧类型为 `session.digest`（含 `archived`）+ q/p 直推 + 生命周期帧；锚点改为 `updatedAt` 时间戳（`/since/{ts}`）；冷启动 = REST 快照 + SSE 增量；resync = 复用冷启动；路径扁平 `/slimapi/*` + `X-Slimapi-Version` 头已落地。 |
