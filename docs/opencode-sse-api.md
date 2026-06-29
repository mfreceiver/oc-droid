# OpenCode Web 端 SSE 接口与消息刷新策略参考

> **状态**：参考资料。源码部分基于 `opencode-wv/oc-ref`（较新版本）；**实测部分针对本机运行的 server 1.17.11（2026-06-29）**。两者存在版本差异，下文凡涉及"replay/续传"的结论均以实测版本为准并标注版本门控。
>
> **目的**：说明 OpenCode web server 提供的、用于"判断是否需要刷新消息"的接口，重点为 SSE 实时推送，并给出浏览器端使用方法与备选轮询方案。
>
> **关联文档**：
> - `docs/architecture-v3-sse-trust.md` — Android V3 SSE 信任架构方案（消费侧）
> - 源码：`/home/mar/personal_projects/opencode-wv/oc-ref/`（TypeScript monorepo，较 1.17.11 更新）

---

## 0. 实测结果（server 1.17.11，2026-06-29）

> ⚠️ 本节为对 `http://localhost:4096`（`opencode serve`，version 1.17.11）的实际 curl 测试结论。**与源码（oc-ref，更新版本）存在关键差异，以本节为准。**

### 0.1 端点可用性矩阵（实测）

| 端点 | 1.17.11 实测 | 说明 |
|------|:---:|------|
| `GET /api/event`（全局 SSE） | ✅ 可用 | 纯 live 流，**不支持 `?after`**（参数被忽略） |
| `GET /global/event`（V1 全局 SSE） | ✅ 可用 | 同上，`?after` 被忽略（after=0 与 after=999999 返回事件数无差异） |
| `GET /api/session`（列表） | ✅ 可用 | 每条带 `time.{created,updated}` |
| `GET /api/session/:id` | ✅ 可用 | |
| `GET /api/session/:id/message` | ✅ 可用 | cursor 分页（`before=` 入参，响应体 `{data,cursor:{previous,next}}`）；支持 `order=desc|asc`、`limit` |
| `GET /api/session/:id/message/:msgID` | ❌ 不存在 | 返回 SPA 壳（2884B），未注册 |
| **`GET /api/session/:id/event?after=seq`** | ❌ **不存在** | 返回 SPA 壳，**1.17.11 无 per-session replay 端点** |
| **`GET /api/session/:id/history?after=seq`** | ❌ **不存在** | 同上，未注册 |

> 判定方法：未注册路由统一返回 web app 的 SPA HTML 壳（实测稳定 2884 字节）；已注册 API 路由返回 JSON。

### 0.2 事件结构（1.17.11 wire 实测）

全局 SSE 每帧结构（`data:` 行内 JSON）：
```jsonc
{
  "id": "evt_f10ccb454001VORW7noj98iVfa",   // cuid 字符串，非数字 seq
  "type": "message.updated",
  "location": { "directory": "...", "project": {...} },
  "data": { "sessionID": "...", "info": {...} },   // 事件特有 payload
  "durable": { "aggregateID": "ses_...", "seq": 2464, "version": 1 }  // ⭐ 仅 Durable 事件有
}
```

关键点：
- **`durable.seq` 字段存在**（per-session 聚合序列号），数据已在事件流里——但 1.17.11 **没有端点可消费它做 replay**。
- `message.updated` 的 `data.info` = **完整 message 对象**；`message.part.updated` 的 `data.part` = **完整 part 对象**。reducer 就地修补模型在 1.17.11 上成立。
- `message.part.delta`（流式增量）**无 `durable` 字段**（Volatile），且 `data.delta` 为文本片段。
- 事件**无顶层 `timestamp`**（源码 schema 的 `timestamp` 在 1.17.11 wire 上不直接暴露；时间在 `data.time` 里，部分事件有）。

### 0.3 ⚠️ 版本门控（最重要的一条）

- **`?after=seq` 续传在 1.17.11 上完全不可用**：全局流忽略 `after`，per-session replay 端点未注册。
- 该能力存在于 **oc-ref 源码**（`packages/protocol/src/groups/session.ts:327-360` 定义了 `session.events` 与 `session.history`，含 `after` 参数），属**更新版本**的特性。
- **结论**：任何依赖 seq replay 的补差策略（含前述"策略 C"）**仅在升级到含该端点的 server 版本后才可行**。在 1.17.11 上，断线补差的唯一手段是 `/api/session/:id/message` reload（即 Android 当前行为）。
- 升级前验证方法：`curl -s -o /dev/null -w "%{size_download}" http://host/api/session/<id>/event?after=0` → 若返回 2884（SPA 壳）则该版本无 replay；若返回 SSE 流则可用。

### 0.4 认证

本机 localhost:4096 **无需认证**。远程部署可能需要 Basic Auth（Android `SSEClient` 已带）。认证不影响上述端点可用性结论。

### 0.5 V1 vs V2 wire 格式差异（实测）

Android 订阅 V1 `/global/event`，其事件结构与 V2 `/api/event` **不同**：

| 版本 | 端点 | wire 结构 |
|------|------|-----------|
| V1（Android 用） | `/global/event` | `{"payload":{"id","type","properties":{...}}}` |
| V2 | `/api/event` | `{"id","type","location":{...},"data":{...},"durable":{...}}` |

V1 用 `payload.properties` 承载事件数据；V2 用顶层 `data` + `location` + `durable`。Android 的 `SSEEvent` 解析按 V1 格式。`durable.seq` 在 V2 流里确认存在；V1 流里需另行确认（1.17.11 无 replay，此字段对当前无消费价值）。

### 0.6 心跳机制（实测，关键）

1.17.11 **发送心跳，但作为 `server.heartbeat` 数据事件**，而非 SSE comment 帧（`: heartbeat`）：

```jsonc
data: {"payload":{"id":"evt_f10d4576e001...","type":"server.heartbeat","properties":{}}}
```

**含义**：OkHttp `EventSource.onEvent(...)` 回调**会**在心跳时触发。因此 Android 可直接用"每次 `onEvent` 重置一个超时定时器"实现半开连接检测——无需依赖 OkHttp 不暴露的 comment 帧。这使"心跳/半开检测"在 1.17.11 上完全可行且实现简单。

**实测间隔**：连续 65s 抓包，`server.heartbeat` 每 **10.00s ± 0.01s** 一帧（极稳定）。注意：web 源码层说的是 15s，属版本差异；以 1.17.11 实测的 10s 为准。

**推荐超时阈值 = 30s（3× 心跳）**：允许漏 2 帧心跳（容忍网络抖动），第 3 个心跳仍未到即判定半开连接，主动 `eventSource.cancel()` 触发重连。30s 也早于多数运营商 NAT 超时（30-120s），能在 NAT 杀连接前主动恢复。20s（2×）检测更快但抖动误判风险高；45s 过迟。

---

## 1. 结论速览

OpenCode web server **没有专门"告知最后消息时间"的轻量端点**（无 `lastMessageAt` 字段、无 `/last-message` 端点）。但提供了两套可用于判断刷新的机制：

| 方案 | 端点 | 推荐场景 |
|------|------|----------|
| **A. SSE 实时推送（推荐）** | `GET /api/event`（全局，1.17.11 可用）或 `GET /api/session/:id/event?after=seq`（单 session，**仅升级后可用**，见 §0） | 反代支持 `text/event-stream` |
| B. 轮询单 session 最新消息 | `GET /api/session/:id/message?order=desc&limit=N`（1.17.11 可用，当前 Android 即此） | 不能用 SSE，或 1.17.11 断线补差 |
| C. 轮询 session 列表 | `GET /api/session`，比较每条 `time.updated` | 不能用 SSE，且要监控全部 session |

> **1.17.11 实测提醒**（详见 §0）：断线补差在 1.17.11 上**只能用方案 B**（message reload），无 seq replay。官方 web app 用方案 A 的全局流，但其 catch-up 也依赖升级后的 per-session replay。

---

## 2. SSE 端点清单

OpenCode 不提供 WebSocket，实时推送全走 SSE（Server-Sent Events）。

### 2.1 全局事件流：`GET /api/event`

- **定义**：`packages/protocol/src/groups/event.ts:35`，identifier `event.subscribe`
- **实现**：`packages/server/src/handlers/event.ts`
- **行为**：推送**所有 session** 的全部事件（消息、工具、step、permission 等）
- **响应头**：`Content-Type: text/event-stream`、`Cache-Control: no-cache`、`X-Accel-Buffering: no`（防反代缓冲）
- **保活**：每 15 秒发送一帧 `: heartbeat` 注释（`event.ts:37`）

### 2.2 单 session 事件流：`GET /api/session/:sessionID/event`

> ⚠️ **版本门控**：1.17.11 实测**不存在**此端点（见 §0.1）。下列能力来自 oc-ref 源码（更新版本），升级后方可使用。

- **定义**：`packages/protocol/src/groups/session.ts:327`，identifier `session.events`
- **行为**：仅推送该 session 的事件
- **断线续传**：支持 `?after=<seq>`（`after` 为 `NumberFromString→NonNegativeInt`，即**数字** aggregate sequence，不是事件 id 字符串）—— 从指定 sequence 之后**先回放持久事件，再继续推新事件**（replay-then-live 模式，`session.ts:340`）。客户端记住最后消费的 seq（来自事件 `durable.seq` 字段），重连时不丢事件。

### 2.3 事件持久化标识

- **Durable 事件**带 `durable: { aggregateID, seq, version }`（实测见 §0.2）。`aggregateID` = sessionID，`seq` = 该 session 内的单调递增序列号。这是 `?after` 参数的取值来源。
- Volatile 事件（如 `message.part.delta`）**无 `durable` 字段**，不参与 replay。
- 源码 Schema：`packages/schema/src/session-event.ts`（注意：源码层的 `timestamp` 在 1.17.11 wire 上不直接暴露）。

---

## 3. SSE 事件结构

事件经 server 的 `session.events` / `global.event` 推出，每条结构遵循 `SessionEvent` schema。关键字段：

```ts
// packages/schema/src/session-event.ts
Base = {
  type: string,        // 事件类型，如 "message.part.delta" / "message.updated" / "session.updated" ...
  sessionID: string,
  timestamp: number,   // 毫秒时间戳
  // ... 事件特有字段（info / part / message 等）
}
```

事件按是否持久化分两类：
- **Durable（持久）**：可经 `?after=seq` 回放，如 `message.updated`、`session.updated`、tool 完成等结构事件
- **Volatile（瞬时）**：不持久，如 `message.part.delta`（流式文本增量），只在实时流里出现，丢了不影响最终一致性

> 客户端策略：Durable 事件用于补齐状态，Volatile 事件用于实时显示流式文本。Android V3 信任架构见 `architecture-v3-sse-trust.md`。

---

## 3.1 事件是否携带完整消息内容？

**结论：SSE 事件携带的是"实际内容"，不是"仅有新消息"的空通知。** 但它不是单一形式，而是**快照 + 增量**混合模型——客户端按事件类型直接组装完整消息，**正常情况下无需再调其他接口拉内容**。官方 web app 即以 SSE 为唯一数据源（V3 信任模型），不搭配 reload API。

### 两层事件词汇（重要）

OpenCode 存在两套事件类型，理解区别可避免误读源码：

| 层级 | 类型前缀 | 用途 | 文件 |
|------|----------|------|------|
| 内部存储事件 | `session.next.*` | 引擎内部持久事件源（`text.delta`/`tool.called`/`step.started` 等） | `packages/schema/src/session-event.ts` |
| **wire 客户端事件** | `message.*` / `session.*` / `permission.*` / `question.*` | **SSE `/api/event` 实际推送给浏览器的事件** | `packages/app/src/context/global-sync/event-reducer.ts` |

浏览器订阅 SSE 收到的是 **`message.*` 层**（由引擎把 `session.next.*` 投影/翻译而来，映射逻辑见 `packages/opencode/src/cli/cmd/run/stream.transport.ts`）。下文只关注 wire 层。

### wire 层 `message.*` 事件 payload 结构

| 事件类型 | properties 字段 | 携带内容 | 是否持久（可 `?after=seq` 回放） |
|----------|-----------------|----------|:----:|
| `message.updated` | `info: SessionMessage.Message` | **完整消息对象**（含 id/role/time/agent/model 等全部字段，是全量快照） | ✅ Durable |
| `message.part.updated` | `part: SessionMessage.Part` | **完整消息 part**（一个 part 的全量快照：text/tool/reasoning/file 等） | ✅ Durable |
| `message.part.delta` | `{ messageID, partID, field, delta }` | **增量片段**（仅一段文本 delta，需累加到已有 part 的字段上） | ❌ Volatile（仅实时流） |
| `message.removed` | `{ sessionID, messageID }` | 仅删除信号（无内容） | ✅ Durable |
| `message.part.removed` | `{ messageID, partID }` | 仅删除信号 | ✅ Durable |

> 注：**没有独立的 `message.created`**——新建与更新统一走 `message.updated`。reducer 用二分查找：找到 id 就 reconcile 替换，找不到就按位置插入（`event-reducer.ts:205-225`）。

### 一条消息的完整生命周期（浏览器如何靠 SSE 拼出整条消息）

```
[1] message.updated        → info: { id, role:"assistant", time, ... }   ← 消息壳（无正文）
[2] message.part.updated   → part: { type:"text", text:"" }              ← 文本 part 骨架
[3] message.part.delta     → { field:"text", delta:"H" }                 ┐
    message.part.delta     → { field:"text", delta:"ello" }              │ 流式增量（仅实时）
    ... (N 次 delta)                                                    ┘
[N] message.part.updated   → part: { type:"text", text:"Hello world" }   ← 文本 part 最终全量快照
    message.part.updated   → part: { type:"tool", ... }                  ← 工具调用 part（直接全量）
    message.updated        → info: { ..., time.completed }               ← 消息最终态
```

要点：
- **流式文本**：实时阶段靠 `message.part.delta` 逐字累加（浏览器 UX 流畅）；结束时 `message.part.updated` 给出全量快照作为权威值。
- **非流式 part（工具/文件/推理边界）**：直接以 `message.part.updated` 全量送达，不走 delta。
- **断线重连后**：`?after=seq` 只回放 Durable 事件（`message.updated` + `message.part.updated` 全量快照），**不回放 delta**。即重连后直接拿到全量快照，不丢最终内容，也不需要补调 API。

### 对浏览器刷新策略的直接回答

| 你的疑问 | 回答 |
|----------|------|
| 事件是"有新消息"的空通知吗？ | **不是**。事件带实际内容（全量快照或增量 delta）。 |
| 事件是"完整消息"吗？ | **不完全是**。是"消息壳 + 多个 part 快照 + 流式 delta"的组合，需客户端按 reducer 规则组装。 |
| 收到事件后还要调 `/message` 接口拉内容吗？ | **正常不需要**。官方 web app 完全靠 SSE 就地修补，零 reload（`server-session.tsx`）。仅在初始加载、分页加载更多、或诊断异常时才调 API。 |
| 重连会丢内容吗？ | **不会**。Durable 事件（全量快照）可经 `?after=seq` 回放补齐；只有实时 delta 不回放，但全量快照已覆盖最终值。 |

> ⚠️ 注意：`message.part.delta` 是**实时流优化**，断线期间错过的 delta 永远拿不回——但这是设计意图，因为对应 part 会有一次 `message.part.updated` 全量快照兜底。所以浏览器只要消费全量快照即可保证正确性，delta 仅用于流畅 UX。

---

## 4. 浏览器使用方法（方案 A）

### 4.1 最简：原生 EventSource（单 session）

```js
// 记住最后消费的 seq，用于断线续传
let lastSeq = Number(localStorage.getItem("lastSeq") ?? 0);

function connect(sessionID) {
  // 注意：原生 EventSource 不支持自定义 header；
  // 如需鉴权头，改用 fetch + ReadableStream（见 4.2）或第三方库（如 @microsoft/fetch-event-source）
  const url = `/api/session/${sessionID}/event?after=${lastSeq}`;
  const es = new EventSource(url);

  es.onmessage = (e) => {
    const evt = JSON.parse(e.data);
    lastSeq = evt.info?.aggregateSequence ?? lastSeq;
    localStorage.setItem("lastSeq", String(lastSeq));
    handleEvent(evt);        // 收到事件即刷新对应 UI
  };

  es.onerror = () => {
    es.close();
    setTimeout(() => connect(sessionID), 250);  // 自动重连
  };
}
```

### 4.2 带鉴权 / 自定义头：fetch + ReadableStream

```js
async function subscribe(sessionID, signal) {
  const url = `/api/session/${sessionID}/event?after=${lastSeq}`;
  const res = await fetch(url, {
    headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" },
    signal,
  });
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    const frames = buf.split("\n\n");   // SSE 帧以空行分隔
    buf = frames.pop();
    for (const frame of frames) {
      if (frame.startsWith(":")) continue;          // heartbeat 注释
      const dataLine = frame.split("\n").find(l => l.startsWith("data:"));
      if (!dataLine) continue;
      handleEvent(JSON.parse(dataLine.slice(5).trim()));
    }
  }
}
```

### 4.3 官方 web app 的策略（参考实现）

源码：`packages/app/src/context/server-sdk.tsx`。要点：

| 机制 | 实现 | 位置 |
|------|------|------|
| 订阅入口 | `eventSdk.global.event({ signal, onSseError })` → `GET /api/event` | `:177` |
| 事件消费 | `for await (const event of events.stream)` | `:192` |
| 帧批量 + 去抖 | 16ms batch + coalesce，合并高频 `message.part.delta` | `:104-131` |
| 心跳超时 | 15s（`HEARTBEAT_TIMEOUT_MS`），收到任意帧即重置 | `:145` |
| 自动重连 | 250ms 延迟（`RECONNECT_DELAY_MS`），`while` 循环重建 | `:106` |
| 页面可见性 | `pageshow`（bfcache）重启；`visibilitychange` 回前台且超心跳则 abort 重连 | `:241-247` |

> 代码库中 `setInterval` 均不用于消息轮询（仅用于倒计时、列表重排 60s、server 健康检查 10s、debug bar 等）。

---

## 5. 备选方案：轮询（方案 B / C）

仅当反代不支持 `text/event-stream` 时使用。

### 5.1 方案 B：轮询单 session 最新消息（精准）

`GET /api/session/:id/message?order=desc&limit=1` 返回：
```ts
{ data: SessionMessage.Message[], cursor: { previous?, next? } }
```
每条消息都带 `time.created`（assistant 还有 `completed`）。取 `data[0].time.created` 与本地缓存比较，变了即刷新。

- 定义：`packages/protocol/src/groups/message.ts:26`，identifier `session.messages`
- Schema：`packages/schema/src/session-message.ts:27`（`Base.time = { created }`）

### 5.2 方案 C：轮询 session 列表（多 session 粗粒度）

`GET /api/session`（identifier `session.list`，`protocol/src/groups/session.ts:109`）每条返回 `Session.Info`：
```ts
time: {
  created: number,    // ms
  updated: number,    // ms — 事实上的"最后活动时间"
  archived?: number,
}
```
- Schema：`packages/schema/src/session.ts:35-39`
- DB 映射：`packages/core/src/session/info.ts:44`（`time_updated` → `time.updated`）

**注意**：`time.updated` 仅在结构性变更（agent/model 切换、revert、moved、`Session.Updated` 事件）时 bump，**纯流式文本增量（Text.Delta）不保证实时 bump**，是粗粒度信号。命中变更后再走方案 B 拉详情。

---

## 6. 其他相关端点（参考）

| 方法 | 路径 | identifier | 含时间字段 | 用途 |
|------|------|-----------|:----------:|------|
| GET | `/api/session` | session.list | ✅ `time.updated` | session 列表（分页 cursor） |
| POST | `/api/session` | session.create | ✅ | 创建 session |
| GET | `/api/session/active` | session.active | ❌ | 当前活跃 session（仅 `{type:"running"}`） |
| GET | `/api/session/:id` | session.get | ✅ | 取单个 session |
| GET | `/api/session/:id/context` | session.context | ✅ `time.created` | 压缩后上下文消息 |
| GET | `/api/session/:id/history` | session.history | ✅ `timestamp` | 持久事件分页（`after` seq） |
| GET | `/api/session/:id/event` | session.events | ✅ `timestamp` | **SSE**（单 session） |
| GET | `/api/session/:id/message/:msgID` | session.message | ✅ `time.created` | 取单条消息 |
| GET | `/api/session/:id/message` | session.messages | ✅ `time.created` | 消息列表（分页，可 desc） |
| GET | `/api/event` | event.subscribe | ✅ `timestamp` | **SSE**（全局） |

定义位置：`packages/protocol/src/groups/{session,message,event}.ts`。

---

## 7. 决策建议

1. **优先方案 A（SSE）** —— 与官方 web app 同款，延迟最低、最省资源。浏览器用原生 `EventSource` 或 `fetch + ReadableStream`。
2. **单 session 监控** → 用 `GET /api/session/:id/event?after=seq`，记住 seq 实现"不丢事件 + 断线续传"。
3. **全 session 监控** → 用全局 `GET /api/event`，按事件 `sessionID` 分流。
4. **SSE 不可用时** → 方案 B（单 session 精准）或方案 C（多 session 粗粒度筛选 + B 补详情）。
5. **重连与可见性** → 参考 `server-sdk.tsx`：心跳超时 15s 重连、回前台触发重连、250ms 退避。

---

## 8. Android 场景行为规约（Phase 1 目标态，2026-06-29 定稿）

> 本节是 Android 客户端在各场景下"如何取新数据 / 如何补旧数据 / 数据如何存储"的最终规约。约束：历史消息不重要 + 省流量。`[现]`=代码已有、`[P1]`=Phase 1 新增、`[P2]`=升级 server 后。

### 8.1 存储基底

| 数据 | 存哪 | 寿命/容量 | 落盘 |
|---|---|---|:--:|
| 消息 + parts | `sessionWindowCache`（内存 LinkedHashMap） | 12 session LRU | ❌ |
| streaming 文本叠加 | `streamingPartTexts[partId]`（内存） | 流式期间 | ❌ |
| 历史分页 cursor | `olderMessagesCursor`/`hasMoreMessages`（随 session cache） | 同消息 | ❌ |
| 会话元数据（id/title/directory/time/summary） | `SettingsManager.sessionCache`（EncryptedPrefs） | 永久 | ✅ |
| lastSeq（仅 P2 启用） | 内存变量 | 进程内 | ❌ |

**总原则**：消息永不落盘；重启 = 重拉所选会话 latest-5。

### 8.2 场景表

| 场景 | 新数据获取 | 旧数据补差 | 存储 |
|---|---|---|---|
| **1 冷启动** | 选会话→`GET /message?limit=5`；SSE 建立后实时 reducer | 不适用 | cache 空→1条；元数据从 EncryptedPrefs 秒读 |
| **2 稳态+心跳健康** | SSE 零 API（`message.updated`/`part.updated`/`part.delta`/`session.status` 就地修补；`message.created`→reload） | 不适用 | 稳态；`[P1]` 30s watchdog 持续重置 |
| **3 开着但心跳断开** `[P1]` | 30s watchdog（3×10s 心跳）无 `onEvent`→`cancel()`→`retryWhen` 重连 | 重连后 `limit=1` probe→变则 latest-5 + **断层检测**；断层可手动闭合（见 8.3） | 内存刷新 |
| **4 后台→前台** | ON_STOP 主动断 SSE；ON_START 重连 | **<15s**：节流跳过；**15s–5min**：同场景3（probe+latest-5+断层）；**>5min/退出**：冷启动+轻提示（见 8.4） | 进程存活则留；被杀则全空 |
| **5a 手动刷新** `[P1]` | = **全局冷启动+懒加载**：`clearSessionWindowCache()` + 清 overlay + 立即重载当前会话 latest-5；其他会话切过去才拉 | 不追踪断层（用户主动要新鲜快照） | 全部消息 cache 清空，按需重建 |
| **5b loadMore 上滑历史** `[现]` | — | `before=<loadMore cursor>` 翻老，前插去重 | cursor 随 session cache |
| **5c 升级后** `[P2]` | 同上 | 短断线 `?after=seq` replay（零冗余），长间隔尾刷新；断层概念弱化（seq 不丢事件） | lastSeq 内存 |

### 8.3 断层（Gap）特性

**触发**（仅场景3 / 场景4 的 15s–5min 分支，且缓存原本有内容）：
```
probe limit=1 → 最新id 变化 → reload latest-5
  ├─ 记录 reload 前本地最新 message id = A
  ├─ A 在 latest-5 返回集 → 无断层，正常合并
  └─ A 不在 → 疑似断层（乐观判定，可能误报，由用户一次点击验证）：
       gapInfo.open=true，存 A + latest-5 响应的 cursor.previous
       UI 在"本地历史"与"新尾"间插入断层 divider："可能存在未加载消息，点击加载"
```

**闭合**（loadMore 式，用户主动）：
- 点断层 divider → `before=<tailOldestCursor>` 拉一页老的 → 前插去重
- 该页包含 A → 闭合，移除 divider；不含 A → divider 仍在，可继续点，直至闭合
- cursor 透传 server 签发的 `cursor.previous`，**不手工构造**（已实测 `before=` 含锚点id，靠现有去重处理）

**清除时机**：会话切换 / 手动刷新 / 冷启动 / 进程重建。

**cursor 分离**：断层 cursor（锚点=尾窗口oldest）与 loadMore cursor（锚点=已加载oldest）独立存储，互不污染。

### 8.4 长时间未查看轻提示（场景4 >5min / 退出）

回前台判定 >5min 或进程重建后：
- 当前会话冷启动 latest-5
- 顶部显示轻提示：「长时间未查看，仅显示最新内容。[点击重新加载全部会话]」
- 点击 → 触发 §8.2 场景5a 的全局冷启动+懒加载（Q2 与 Q3 共用入口）

### 8.5 关键决策记录

| 项 | 决策 | 理由 |
|---|---|---|
| 断层误报 | 乐观 banner，用户点一次验证 | 省流量优先；误报代价仅一次主动点击 |
| >5min 处理 | 冷启动 + 轻提示（含可点击全局刷新） | 与"断层要告知"精神一致；缺口太大手动闭合不现实 |
| 手动刷新作用域 | 全局冷启动 + 懒加载 | 复用 `clearSessionWindowCache()` + `selectSession` cache-miss；其他会话切过去才拉，不主动多拉 |
| 心跳超时 | 30s（3× 实测 10s 心跳） | 容忍漏 2 帧；早于 NAT 超时（30-120s） |
| lastSeq 持久化 | 不持久化（内存） | 冷启动 gap 必 >15s 走尾刷新，持久化无收益；P2 同 |
| `message.created` | 仍 reload，不加 probe | 本身是可靠新消息信号，probe 反而多一跳 |
| 后台 SSE | 保持现状（ON_STOP 断开） | 后台保活 SSE 弊大于利（耗电+限网），reload 补差已足够 |

### 8.6 Phase 1 改动清单

1. **30s 心跳 watchdog**（`SSEClient`：每次 `onEvent` 含 `server.heartbeat` 重置定时器，超时 `cancel()`）
2. **limit=1 tail probe**（刷新/回前台/重连后：先探 1 条，变则 latest-5）
3. **断层检测 + 断层 divider UI + 断层闭合**（`before=<cursor.previous>` 翻页至本地最新id）
4. **手动刷新 = 全局冷启动+懒加载**（`clearSessionWindowCache()` + 当前会话立即 latest-5）
5. **场景4 后台三档阈值**：<15s 节流 / 15s-5min 同场景3 / >5min 冷启动+轻提示（可点击全局刷新）
6. 纠正 V3 文档（已完成，见 §0.3）

### 8.7 不做清单

- 1.17.11 上实现 `?after=seq`（端点不存在，伪功能）
- 持久化消息内容到 Room/DataStore
- 扩大默认窗口（>5）
- 手工构造 cursor 做增量（透传 server cursor 即可）
- 切掉全局 SSE（unread badge 依赖它）
- delta coalesce（非流量瓶颈，无 Profiler 证据前不做）

---

## 9. 源码索引

| 主题 | 文件 |
|------|------|
| SSE 端点定义 | `packages/protocol/src/groups/{event.ts:35, session.ts:327}` |
| SSE handler 实现 | `packages/server/src/handlers/event.ts`（heartbeat `:37`） |
| 事件 Schema | `packages/schema/src/session-event.ts:28` |
| Session.Info.time | `packages/schema/src/session.ts:35-39` |
| Message.time | `packages/schema/src/session-message.ts:27` |
| time.updated 写入点 | `packages/core/src/session/projector.ts:235,251,334,343,401` |
| time.updated DB 映射 | `packages/core/src/session/info.ts:44` |
| 官方 web 订阅实现 | `packages/app/src/context/server-sdk.tsx:177,192,145,241-247` |
| SDK client 方法 | `packages/client/src/generated/client.ts:342,377` |
