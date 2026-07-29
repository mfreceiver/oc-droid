# opencode Web 版会话状态机调研报告

> **证据基线**：`opencode-src/v1.18.7/`（绝对路径 `/home/mar/personal_projects/ocdroid/opencode-src/v1.18.7/`）。
> **用途**：为 ocdroid 安卓客户端状态机改进提供上游参考。ocdroid 消费 **V1 线协议**，故 V1 为重点；V2（`session.execution.*` / `session.next.*` / `session.retry.scheduled`）仅作演进背景。
> **调研方式**：3 个 explorer 并行交叉核对源码（服务端 / Web App / 协议+V2+TUI），每条论断附 `文件:行号`。
> **日期**：2026-07-30。**上游版本**：v1.18.7。

---

## 0. 术语校正与版本事实（必读）

调研中发现任务 hint 与 v1.18.7 实际代码有几处偏差，**以源码为准**，此处先澄清，避免后文误导：

1. **无 `runner.ts`**：v1.18.7 中不存在 `packages/opencode/src/session/runner.ts`。"Runner" 状态模型实际位于 `packages/opencode/src/effect/runner.ts:33-37`（通用 effect Runner），而会话层的 run-state 编排位于 `packages/opencode/src/session/run-state.ts`。
2. **`SessionStatus` 恰好 3 值**：`"idle"` / `"retry"` / `"busy"`，**没有 `"error"` 类型**（`packages/schema/src/session-status-event.ts:9-32`）。错误信息走 **assistant message 的 `error` 字段**（via `halt`）和独立的 `session.error` 事件，**不**编码进 SessionStatus。
3. **"Runner 四态 vs 对外三态" 的精确含义**：Runner 内部 4 态（`Idle` / `Running` / `Shell` / `ShellThenRun`，`effect/runner.ts:33-37`）→ 对外只有 **二值 busy 标志**（`runner.ts:208-210` `busy = state()._tag !== "Idle"`）。而 `SessionStatus` 的第三值 `retry` 是**处理器（processor）在 retry policy 回调里叠加的 overlay**，Runner 自身并无 "Retrying" 态。所以更准确的说法是：**Runner 4 内态 → 2 外态（busy/idle）；SessionStatus 在此之上再加 `retry` → 3 外态**。
4. **"interrupt 杀流+重试" 措辞误导**：interrupt 确实杀 fiber（流），但 `Effect.retry` **不会**在中断时触发（只对 failure 触发，`processor.ts:647-677` 的 `catchCauseIf` 过滤了纯中断 cause）。所谓"重试"实为 `awaitDone` 捕获 `RunnerCancelled` 后返回 `onInterrupt` 结果（`runner.ts:64-65`），是**一次性取消**，非 LLM 重试循环。
5. **`packages/app` ≠ `packages/web`**：`packages/app` = `@opencode-ai/app`（**SolidJS Web App，本次调研目标产品**）；`packages/web` = Starlight/Astro **文档网站**，非产品形态，全程未使用。
6. **Web App 在 v1.18.7 默认走 V2 协议**：通过探测 `/global/health`（V1）vs `/api/health`（V2）判定；V1 代码路径是**条件分支回退**，非主路径（explorer-2 确认）。

---

## 1. 服务端状态模型全貌

### 1.1 SessionStatus 类型定义（3 值，权威）

`packages/schema/src/session-status-event.ts:9-32`：

```typescript
export const Info = Schema.Union([
  Schema.Struct({ type: Schema.Literal("idle") }),                  // 10-12
  Schema.Struct({                                                 // 13-28
    type: Schema.Literal("retry"),
    attempt: NonNegativeInt,
    message: Schema.String,
    action: optional(Schema.Struct({ reason, provider, title, message, label, link? })),
    next: NonNegativeInt,                                          // epoch ms，下次重试时刻
  }),
  Schema.Struct({ type: Schema.Literal("busy") }),                 // 29-31
]).annotate({ identifier: "SessionStatus" })
```

- `retry` 携带 `attempt`（第几次）、`message`（错误描述）、可选 `action`（**仅计费/限流错误**才有，含 upsell 链接 `link`）、`next`（下次重试的 epoch 毫秒）。
- `action.reason` 取值：`"free_tier_limit"` / `"account_rate_limit"`（`packages/opencode/src/session/retry.ts:14-24`）。

### 1.2 内存投影：稀疏 Map（idle = 删除）

`packages/opencode/src/session/status.ts:26-48`：

```typescript
const state = yield* InstanceState.make(
  Effect.fn("SessionStatus.state")(() => Effect.succeed(new Map<SessionID, Info>())),
)
// ...
if (status.type === "idle") {
  yield* events.publish(Event.Idle, { sessionID })
  data.delete(sessionID)        // 42-45: idle 不存储，直接从 Map 删
  return
}
data.set(sessionID, status)     // busy/retry 才存
```

- **默认（缺 key）= idle**：`return data.get(sessionID) ?? { type: "idle" as const }`（`status.ts:32`）。
- 设计意图：idle 是常态，不占内存；只有 busy/retry 才驻留。**对 Android 有直接参考价值**（稀疏存储降低内存占用）。
- 作用域：`InstanceState.make` 包裹，按 instance/directory 隔离，dispose 时清理。

### 1.3 状态转换来源

| 触发点 | 设置的状态 | 证据 |
|---|---|---|
| Runner `onIdle` 回调 | `{ type: "idle" }` | `run-state.ts` / `runner.ts` |
| Runner `onBusy` 回调 | `{ type: "busy" }` | `run-state.ts` / `runner.ts` |
| processor `process()` 入口 | `{ type: "busy" }` | `processor.ts:639` |
| processor `halt()`（非 overflow） | `{ type: "idle" }` | `processor.ts:624` |
| processor `halt()`（overflow 且开启自动压缩） | **不设 idle**，保持 busy 以进入 compact | `processor.ts` |
| retry policy `set` 回调 | `{ type: "retry", attempt, message, action?, next }` | `retry.ts:176-199` |

### 1.4 Runner 内部状态机（4 态）

`packages/opencode/src/effect/runner.ts:33-37`：

```typescript
export type State<A, E> =
  | { readonly _tag: "Idle" }
  | { readonly _tag: "Running"; readonly run: RunHandle<A, E> }
  | { readonly _tag: "Shell"; readonly shell: ShellHandle<A, E> }
  | { readonly _tag: "ShellThenRun"; readonly shell: ShellHandle<A, E>; readonly run: PendingHandle<A, E> }
```

- **4 内态**：`Idle` / `Running`（正常 LLM 流）/ `Shell`（运行 shell 命令，如 worktree）/ `ShellThenRun`（shell 进行中，已排队等待执行的 run）。
- 关键转换（`runner.ts:64-106, 171-202`）：
  - `Idle → Running`：`ensureRunning(work)` 启动 run。
  - `Running → Idle`：run fiber 结束（`finishRun`）。
  - `Idle → Shell`：`startShell(work)`。
  - `non-Idle → Shell`：报 `Busy` 错（`runner.ts` startShell 守卫）。
  - `Shell → Idle`：shell fiber 结束且无排队 run（`finishShell`，`runner.ts:93-97`）。
  - `ShellThenRun → Running`：shell 结束，自动启动排队的 run（`finishShell`，`runner.ts:98-106`）——**这是 worktree 场景：先切 worktree（shell），再跑 prompt（run）**。
  - 任何 non-Idle → Idle：`cancel()`（`runner.ts:171-202`）。
- **对外只有二值 busy**：`get busy() { return state()._tag !== "Idle" }`（`runner.ts:208-210`）。

> **对客户端的意义**：`Shell` / `ShellThenRun` 是服务端内部态，**客户端不可见**——客户端只看到 `busy`。ocdroid 无需建模 shell 排队。

### 1.5 Processor 主循环

`packages/opencode/src/session/processor.ts:627-683`（`process()`）：

```typescript
yield* status.set(ctx.sessionID, { type: "busy" })           // 639: 入口置 busy
const stream = llm.stream(streamInput)
yield* stream.pipe(
  Stream.tap((event) => handleEvent(event)),
  Stream.takeUntil(() => ctx.needsCompaction),
  Stream.runDrain,
).pipe(
  Effect.onInterrupt(() => { aborted = true; if (!ctx.assistantMessage.error) yield* halt(...) }),  // 648-655
  Effect.catchCauseIf((cause) => !Cause.hasInterruptsOnly(cause), ...),                            // 656-659
  Effect.retry(SessionRetry.policy({ ... set: (info) => status.set(..., {type:"retry",...}) })),   // 660: retry policy 挂 status.set
  Effect.catch(halt),                                                                              // 661-674
  Effect.ensuring(cleanup()),                                                                     // 676: 无论成败中断都跑 cleanup
)
```

返回值（`processor.ts:679-682`）：`"compact"`（需压缩）/ `"stop"`（blocked 或 message 有 error）/ `"continue"`（正常，外层 prompt.ts 循环继续）。

### 1.6 Retry（同 fiber，指数退避）

`packages/opencode/src/session/retry.ts`：

- **同一 fiber 内重试**：`Effect.retry()`（`processor.ts:660`）包裹同一 Effect pipeline，失败时在**同 fiber** 内重新执行（含流重连），**不 spawn 新 fiber**。
- **可重试错误判定**（`retry.ts:68-152` `retryable()`）：
  - `ContextOverflowError`：永不重试。
  - `APIError` 且 `isRetryable=false` 且 status < 500：永不重试。
  - `APIError` 且（`isRetryable=true` 或 status ≥ 500）：重试，无 action。
  - body 含 `FreeUsageLimitError`：重试，action = `{reason:"free_tier_limit",...}`。
  - body 含 `GoUsageLimitError`：重试，action = `{reason:"account_rate_limit",...}`。
- **退避计算**（`retry.ts:35-66` `delay()`）：优先级 `retry-after-ms` 头 → `retry-after` 头（秒或 HTTP 日期）→ 兜底 `2s * 2^(attempt-1)`，无响应头时上限 30s（`RETRY_MAX_DELAY_NO_HEADERS`），有头时上限 ~24.8 天（`2^31-1` ms，`RETRY_MAX_DELAY`）。常量 `RETRY_INITIAL_DELAY=2000`、`RETRY_BACKOFF_FACTOR=2`。
- **Schedule 实现**（`retry.ts:176-199` `policy()`）：`Schedule.fromStepWithMetadata`，每步调 `opts.set({attempt, message, action, next})` 把 retry 信息写进 SessionStatus，并返回 `[attempt, Duration.millis(wait)]` 指示"等待 wait 后重试"。

### 1.7 Interrupt / Abort 完整调用链

```
HTTP POST /session/:sessionID/abort
  → handlers/session.ts:232-235  abort() = promptSvc.cancel(sessionID)
  → prompt.ts:152-155            SessionPrompt.cancel → state.cancel(sessionID)
  → run-state.ts:77-86           SessionRunState.cancel: 取消后台任务 + existing.cancel
  → runner.ts:171-202            Runner.cancel:
                                  - Running: Fiber.interrupt(run.fiber) + fail deferred(Cancelled) + → Idle
                                  - Shell: stopShell + → Idle
                                  - ShellThenRun: stopShell + fail pending + → Idle
  → fiber 中断传播到 processor.process()
  → processor.ts:648-655         Effect.onInterrupt: aborted=true; 若 message 无 error 则 halt(AbortError)
  → processor.ts:599-625         halt(): 设 ctx.assistantMessage.error + publish Session.Event.Error + status.set(idle)
  → catchCauseIf 过滤纯中断（不转 failure，故 retry 不触发）
  → processor.ts:676             ensuring(cleanup()) 执行：收尾工具调用(interrupted:true) + updateMessage 持久化
  → runner.ts:64-65              awaitDone 捕获 RunnerCancelled，返回 onInterrupt 结果（prompt.ts:1346 = lastAssistant）
```

- HTTP 返回：`POST /session/:id/abort` 恒返回 `true`（`handlers/session.ts:232-235`，schema `groups/session.ts:253-264`）。
- **关键**：abort 是**一次性硬杀**，不是"中断后重试"。任务 hint 的"杀流+重试"措辞不准确（见 §0.4）。

### 1.8 Halt 与错误持久化：两阶段链（已验证）

**核心断言**：`halt()` 在**内存**设 `ctx.assistantMessage.error`，但**真正的落盘**发生在 `ensuring(cleanup())` 的 `updateMessage` 调用。两阶段，已逐链核对：

**阶段 1 — halt() 内存设错**（`processor.ts:599-625`）：
```
halt(e)
  → parse(e) → MessageV2.fromError(e, ...)            // message-v2.ts:603-731
  → ctx.assistantMessage.error = error                 // processor.ts:619  ← 内存突变
  → events.publish(Session.Event.Error, {...})         // processor.ts:620-623
  → status.set(ctx.sessionID, { type: "idle" })        // processor.ts:624
```

**阶段 2 — ensuring(cleanup()) 落盘**（`processor.ts:676` → `cleanup()` `processor.ts:539-597`）：
```typescript
// processor.ts:595-596
ctx.assistantMessage.time.completed = Date.now()
yield* session.updateMessage(ctx.assistantMessage)    // ← 持久化触发点
```

`session.updateMessage`（`session.ts:631-635`）：
```typescript
const updateMessage = <T>(msg) => Effect.gen(function* () {
  yield* events.publish(SessionV1.Event.MessageUpdated, { sessionID: msg.sessionID, info: msg })  // session.ts:633
  return msg
})
```

**持久化机制**：`events.publish(MessageUpdated)` 由 `EventV2Bridge` 消费并写库（schema 见 `v1/session.ts:596-603`）。`publish` 是触发，真正 durable write 在 bridge 层。

**完整链**：
```
halt(e)                                                    processor.ts:599
  ├─ MessageV2.fromError(e) → AssistantError               message-v2.ts:603
  ├─ ctx.assistantMessage.error = error  (内存)            processor.ts:619
  ├─ publish(Session.Event.Error)                          processor.ts:620
  └─ status.set(idle)                                      processor.ts:624
... 中断/失败传播 ...
ensuring(cleanup())                                       processor.ts:676
  ├─ assistantMessage.time.completed = Date.now()          processor.ts:595
  └─ session.updateMessage(msg)                            processor.ts:596
       └─ publish(MessageUpdated, {sessionID, info})       session.ts:633
            └─ EventV2Bridge → DB                          (bridge 层)
```

**备选路径**（processor 创建本身被中断时）：`prompt.ts:1203-1211` `finalizeInterruptedAssistant`：
```typescript
msg.error ??= MessageV2.fromError(new DOMException("Aborted","AbortError"), {providerID, aborted:true})
msg.time.completed = Date.now()
yield* sessions.updateMessage(msg)     // 同样经事件持久化
```

**AssistantError 类型**（`v1/session.ts:385-395`）：8 变体 union（`AuthError` / `UnknownError` / `OutputLengthError` / `AbortedError` / `StructuredOutputError` / `ContextOverflowError` / `ContentFilterError` / `APIError`），判别字段 `name`。

> **对 ocdroid 的启示**：错误落盘是**异步两阶段**——status 先 idle，message 的 error 字段稍后随 MessageUpdated 事件到达。客户端若在 status=idle 时立即读 message 可能拿不到 error，必须等 `message.updated` 事件。这是 V1 协议下客户端必须处理的时序。

### 1.9 HTTP API（V1）

- **`GET /session/status`**（`groups/session.ts:48, 80`；handler `handlers/session.ts:77-79`）：返回 `Record<string, SessionStatus.Info>`（sessionID → status 的 JSON 对象），含所有非 idle 会话（idle 因稀疏 Map 不在响应中，或为 `{type:"idle"}`）。
- **`POST /session/:sessionID/abort`**（`groups/session.ts:91, 253-264`；handler `handlers/session.ts:232-235`）：调 `promptSvc.cancel`，恒返回 `true`。
- **`SessionBusyError`**（`session-errors.ts:10-20` `mapBusy`）：shell/revert/unrevert/deleteMessage 等端点在会话 busy 时返回，映射为 `ApiError.SessionBusyError`。
- **无 HTTP `retry-after` 头**：重试时序在 status 对象的 `next` 字段（epoch ms），非 HTTP 头。

---

## 2. Web App 客户端状态管理全貌

### 2.1 核心派生：`session_working`

**两处同定义**（SolidJS store 的派生 getter）：
- `packages/app/src/context/global-sync/child-store.ts:232-235`
- `packages/app/src/context/server-session.ts:207-209`

```typescript
session_working(id: string) {
  const type = this.session_status[id]?.type
  return (type ?? "idle") !== "idle"     // 任何非 idle（busy/retry/...）→ true
}
```

- **公式**：`(session_status[id]?.type ?? "idle") !== "idle"`。
- **二元语义**：`session_working` 是布尔（idle vs 非 idle）。**没有独立的 "errored" 视觉态**从 status 派生——错误信息在 message 的 error 字段，不在 status。

### 2.2 `session_status` 的 6 个写入来源

| # | 来源 | 位置 | 写入值 |
|---|---|---|---|
| 1 | V1 bootstrap 批量拉取 | `bootstrap.ts:383-405` | 全量 status map |
| 2 | V1/V2 `session.status` 事件 | `event-reducer.ts:266-269`；`server-session.ts:1026-1029` | `reconcile(status)` 直写 |
| 3 | V2 执行事件派生 | `server-session.ts:964-970` | started→busy；succeeded/failed/interrupted→idle |
| 4 | V1 乐观写（submit） | `submit.ts:60-68` | `{type:"busy"}`（POST 前） |
| 5 | V2 active 查询种子 | `server-sync.tsx:168-177` | 发现 active 会话先标 busy |
| 6 | V1 activeSessions 批量 | `server-sync.tsx:244-254` | V1 下批量拉 status 种子 |

**清除时机**：收到 `session.status{type:"idle"}` 事件 / V2 终止执行事件 / submit catch 的乐观 rollback / bootstrap 返回 idle。

### 2.3 乐观 busy 更新（submit 流，V1 核心 UX）

`packages/app/src/components/prompt-input/submit.ts:57-198`（`sendFollowupDraft`）：

```typescript
// 60-68: 乐观 setter/clearer
const setBusy = () => {
  if (!input.optimisticBusy) return
  input.serverSync.session.set("session_status", input.draft.sessionID, { type: "busy" })
}
const setIdle = () => {
  if (!input.optimisticBusy) return
  input.serverSync.session.set("session_status", input.draft.sessionID, { type: "idle" })
}

// 145-198: 批量写 + fetch + rollback
batch(() => { setBusy(); add() })          // ← POST 前乐观写 busy
try {
  if (!(await wait())) { batch(() => { setIdle(); remove() }); return false }  // 前置条件失败 rollback
  await input.api.prompt({ ... })          // ← 真正的 fetch
  return true
} catch (err) {
  batch(() => { setIdle(); remove() })     // ← fetch 失败 rollback（busy→idle + 移除乐观消息）
  throw err
}
```

- **`optimisticBusy` 门控**（`submit.ts:614`）：仅当 `sessionDirectory === projectDirectory`（同目录 prompt）才乐观写。**跨 worktree prompt 不乐观写**（避免误报）。
- 命令发送（`submit.ts:510-511, 525`）和 worktree 等待（`submit.ts:555, 561`）有同类乐观模式。
- **V2 变体** `prompt-input-v2.tsx` 复用同一 `sendFollowupDraft`。

> **对 ocdroid 的直接价值**：POST prompt 前先乐观置 busy，失败回滚——消除网络 RTT 造成的 UI 卡顿。**注意门控条件**：仅同目录场景，跨目录不乐观。

### 2.4 `stopping` / `canStop` UI 公式

`packages/app/src/components/prompt-input/prompt-input.tsx:279-286`（V2 变体 `prompt-input-v2.tsx:191-198` 同）：

```typescript
const blank = createMemo(() => {
  const text = prompt.current().map((part) => ("content" in part ? part.content : "")).join("")
  return text.trim().length === 0 && imageAttachments().length === 0 && commentCount() === 0
})
const stopping = createMemo(() => working() && blank())   // ← busy 且输入框空 → 显示停止按钮
```

- **`stopping = working() && blank()`**：会话 busy 且输入框为空 → 发送按钮变停止按钮。
- **无独立 `canStop` 信号**：停止动作在 `stopping()` 为真时即可用（按钮切图标/行为）。
- **busy 且输入非空**：按钮保持发送态；`handleSubmit`（`submit.ts:325`）逻辑 `input.working() ? abort() : ...` —— **busy 时按发送 = 中断**（而非发送）。
- `abort`（`submit.ts:250-268`）：调 `session.interrupt()` 或 abort 待决 `AbortController`。

> **对 ocdroid 的价值**：单按钮复用（发送/停止二合一），按 `working && inputBlank` 切换——比独立 stop 按钮更简洁，适合移动端屏小。

### 2.5 Bootstrap（V1-gated 批量拉取）

`packages/app/src/context/global-sync/bootstrap.ts:382-405`：

```typescript
retry(() => (async () => {
  if ((await input.protocol) !== "v1") return      // ← V1 门控：非 V1 直接跳过
  const x = await input.sdk.session.status()       // GET /session/status（全量）
  if (!input.session) { input.setStore("session_status", x.data!); return }
  const statuses = x.data ?? {}
  // 清理已消失会话的陈旧条目
  input.session.set("session_status", produce((draft) => {
    for (const sessionID of Object.keys(draft)) {
      if (statuses[sessionID]) continue
      if (input.session?.get(sessionID)?.directory === input.directory) delete draft[sessionID]
    }
  }))
  // reconcile 每个传入 status
  for (const [sessionID, status] of Object.entries(statuses)) {
    input.session.set("session_status", sessionID, reconcile(status))   // ← SolidJS 细粒度 diff-merge
  }
  // 预热会话信息缓存
  await Promise.all(Object.keys(statuses).map((id) => input.session!.resolve(id).catch(() => undefined)))
})())
```

- **V1 才走此路径**；V2 立即 return。
- 三步：①清陈旧 ②reconcile 全量 ③预热缓存。
- SolidJS `reconcile` 做细粒度属性 diff，避免整对象替换触发重渲染。

### 2.6 event-reducer：`session.status` 直写

`packages/app/src/context/global-sync/event-reducer.ts:266-270`：

```typescript
case "session.status": {
  const props = event.properties as { sessionID: string; status: SessionStatus }
  input.setStore("session_status", props.sessionID, reconcile(props.status))   // ← 直写，无派生
  break
}
```

- **直写语义**：无合并逻辑，`reconcile` 做 diff-merge。`server-session.ts:1026-1029` 是 `apply()` 中的同款 handler。
- **reducer 处理的其他会话事件**（`event-reducer.ts`）：
  - `session.created` (130) / `session.updated` (145) / `session.deleted` (173) / `session.renamed` (192) / `session.usage.updated` (203) / `session.moved` (227) / `session.diff` (255)
  - `todo.updated` (260)
  - `message.updated` (271) / `message.removed` (292) / `message.part.updated` (312) / `message.part.removed` (339) / `message.part.delta` (363)
  - `permission.asked` (396) / `permission.replied` (417) / `question.asked` (432) / `question.replied`/`rejected` (453)
  - `lsp.updated` (469) / `reference.updated` (473) / `vcs.branch.updated` (388)

### 2.7 Refresh 触发（无轮询）

| 路径 | 位置 | 说明 |
|---|---|---|
| Bootstrap 批量（V1） | `bootstrap.ts:382-405` | 目录初始化时一次，仅 V1 |
| SSE 事件驱动（主） | `server-session.ts apply()` / `event-reducer.ts` | 实时，**无周期轮询** |
| activeSessions 查询 | `server-sync.tsx:241-264` | TanStack Query 启动种子；options `refetchOnMount:false, refetchOnReconnect:false, refetchOnWindowFocus:false, staleTime:Infinity`（`server-sync.tsx:160-166`）——**非刷新循环** |
| 全局事件触发 | `event-reducer.ts:42-44` | `global.disposed` / `server.connected` → `input.refresh()` → 全量 `bootstrapGlobal()` 重跑 |

> **对 ocdroid 的关键启示**：上游**无状态轮询**，纯事件驱动。Android 因网络不稳/SSE 易断，需额外加重连 + 断线后 bootstrap reconcile 兜底（上游靠 `server.connected` 事件触发 refresh，正是此意）。

### 2.8 applyV2（V2 背景）

`packages/app/src/context/server-session.ts:937-985`：

```typescript
const applyV2 = (event) => {
  // ... V2 事件经 v2.reduce + projectV2 投影回 V1 store ...
  // 状态从执行事件派生：
  if (event.type === "session.execution.started") setData("session_status", sessionID, { type: "busy" })
  if (event.type === "session.execution.succeeded" || "failed" || "interrupted")
    setData("session_status", sessionID, { type: "idle" })
}
```

**V1 vs V2 派生模型对比**：

| 维度 | V1 | V2 |
|---|---|---|
| status 来源 | 服务端显式发 `session.status{type}` 事件 | 客户端从 `session.execution.*` 生命周期**派生** |
| 粒度 | 不透明 status blob | 执行事件带结构化信息（agent/model/snapshot/retry 调度） |
| 消息内容 | `message.part.*` 扁平 part | `session.text.*`/`reasoning.*`/`tool.*` 带 ordinal 的结构化事件 |
| 适配层 | 无，事件直入 store | `createV2SessionReducer`（~509 行）把 V2 详事件折成 V1 兼容 `session_message`，再 `projectV2()` 回放为合成 V1 事件 |

### 2.9 客户端错误落地（修正 explorer-2 表述）

⚠️ **修正**：`SessionStatus` 无 `"error"` 类型（见 §0.2、§1.1）。错误在客户端的实际落点：

| 错误源 | 落点 | 机制 | 证据 |
|---|---|---|---|
| V2 `session.retry.scheduled` | `session_status[id] = {type:"retry",attempt,message,next}` | `server-session.ts:971-977` |
| V2 执行失败 `session.execution.failed` | `session_status[id] = {type:"idle"}`（错误详情在 step/finish 元数据） | `server-session.ts:966-970` |
| `session.error` 事件（V1/V2） | **不入 session_status**；toast + 桌面通知 | `notification.tsx:396-399`（从常规处理过滤） |
| submit catch | **不入 session_status**；`setIdle()` rollback + toast + 还原输入 | `submit.ts:192-198, 524-531, 616-627` |
| V2 reducer 内 `session.step.failed` | 记入 **message 时间线**（assistant msg `finish:"error"`），非 status | `server-session-v2-reducer.ts:177` |

**关键**：V1 下服务端通过 `halt` 把 error 写进 **message 的 error 字段**（经 `message.updated` 事件到达客户端），**不**通过 status。客户端判"会话是否出错"应读 message.error，而非 status.type。`session_working` 是纯二值（idle vs 非 idle）。

---

## 3. V1 线协议事件清单与语义

### 3.1 V1 事件全集（`packages/schema/src/v1/session.ts:571-657`）

| 事件类型 | payload | 行号 |
|---|---|---|
| `session.created` | `{ sessionID, info: SessionInfo }` | 571-579 |
| `session.updated` | `{ sessionID, info: SessionInfo }` | 580-587 |
| `session.deleted` | `{ sessionID, info: SessionInfo }` | 588-595 |
| `message.updated` | `{ sessionID, info: Info }`（User\|Assistant，role 判别） | 596-603 |
| `message.removed` | `{ sessionID, messageID }` | 604-611 |
| `message.part.updated` | `{ sessionID, part: Part, time }` | 612-620 |
| `message.part.removed` | `{ sessionID, messageID, partID }` | 621-629 |
| `message.part.delta` | `{ sessionID, messageID, partID, field, delta }` | 632-641 |
| `session.diff` | `{ sessionID, diff: FileDiff[] }` | 644-649 |
| `session.error` | `{ sessionID?, error }` | 651-657 |

**legacy 事件**（`packages/schema/src/v1/legacy-event.ts:8-16`）：`command.executed { name, sessionID, arguments, messageID }`。

**V1 Part 类型**（`v1/session.ts:357-370`）：11 变体 `text` / `subtask` / `reasoning` / `file` / `tool` / `step-start` / `step-finish` / `snapshot` / `patch` / `agent` / `retry` / `compaction`。

**V1 复合结构**：`WithParts = { info: Info, parts: Part[] }`。

### 3.2 status 事件（当前协议，与 V1 并存）

`packages/schema/src/session-status-event.ts`：
- `session.status`：`{ sessionID, status: Info }`（Info = idle/retry/busy，见 §1.1）。
- `session.idle`：**已废弃**，仅 `{ sessionID }`（行 44-49）。

### 3.3 传输方式：SSE

`packages/opencode/src/server/routes/instance/httpapi/handlers/event.ts:12-86`：

1. **全局事件流** `GET /api/event`（`event.ts:35-44`）：`text/event-stream`，首事件 `server.connected`，每 10s 心跳 `server.heartbeat`，事件 JSON `{ id, type, properties: event.data }`，按 `location.directory`/`workspaceID` 过滤，`server.instance.disposed` 时停。
2. **会话级持久事件回放** `GET /api/session/:sessionID/event`（`protocol/src/groups/session.ts:327-343`）：回放 `SessionEvent.Durable`，支持 `?after=<seq>` 从聚合序列位置续传，回放后接新持久事件。

### 3.4 SDK 客户端面

- **V1 SDK** `packages/sdk/js/src/client.ts`：基于 OpenAPI 生成的 `OpencodeClient`（`gen/sdk.gen.ts`），fetch 封装。
- **V2 SDK** `packages/sdk/js/src/v2/client.ts:50-93`：另一 OpenAPI spec（`v2/gen/`），加 `x-opencode-directory`/`x-opencode-workspace` 头转发，响应 content-type 守卫防 text/html（版本错配检测）。
- 方法：`client.session.list/get/create/prompt/compact/wait/interrupt/revert.*/context/history/events/message`、`client.event.subscribe`（SSE）等。

---

## 4. V2 执行事件流（演进背景）

### 4.1 V1 `session.status` 三值 enum 的局限

V1 status 把所有"进行中"活动压成一个不透明的 `busy`，客户端只知道"有事在跑"，**不知在跑什么**（模型在思考？工具在执行？step 结束？）。错误也只能塞进 `retry` 或独立 `session.error` 事件，缺少结构化执行生命周期。

### 4.2 V2 `session.next.*` 提供细粒度 per-step 生命周期

`packages/schema/src/session-event.ts`（事件集 `session.next.*`，注册于 `SessionEvent.Definitions:479-512`，durable 子集 `DurableDefinitions:448-477`）。**按内容类型拆分的生命周期**：

| 内容类型 | started | delta（live-only） | ended/failed |
|---|---|---|---|
| step | `step.started` (149) | — | `step.ended` (162) / `step.failed` (185) |
| text | `text.started` (198) | `text.delta` (210) | `text.ended` (221) |
| reasoning | `reasoning.started` (235) | `reasoning.delta` (249) | `reasoning.ended` (260) |
| tool input | `tool.input.started` (281) | `tool.input.delta` (292) | `tool.input.ended` (301) |
| tool 执行 | `tool.called` (312) | `tool.progress` (331) | `tool.success` (342) / `tool.failed` (359) |
| shell | `shell.started` (124) | — | `shell.ended` (136) |
| compaction | `compaction.started` (399) | `compaction.delta` (410) | `compaction.ended` (420) |

每个 step 携带 `agent`/`model`/`snapshot`/`cost`/`tokens{cache,...}`。tool 有可回放的状态检查点（`tool.progress` 的 `structured`/`content`）。

**其他 V2 事件**：`agent.switched`/`model.switched`/`moved`/`prompted`/`prompt.admitted`/`context.updated`/`synthetic`/`retried`（387-396，durable，带完整 `RetryError{message,statusCode?,isRetryable,responseHeaders?}`）/`revert.staged/cleared/committed`。

### 4.3 V2 执行事件桥（应用层，非 schema）

⚠️ `session.execution.*` 与 `session.retry.scheduled` **不在 schema 包**，是服务端 `EventV2` 系统动态构造的应用层事件，仅 Web App 消费：

- `server-session.ts:964-977`：`started`→busy；`succeeded/failed/interrupted`→idle；`retry.scheduled`→`{type:"retry",attempt,message,next}`。
- `server-session-v2-reducer.ts:323-334`：`retry.scheduled` 在 assistant message 设 `retry:{attempt,at,error}`；三个终止事件清 retry。

**推断 payload**：`session.execution.{started,succeeded,failed,interrupted}` = `{sessionID}`；`session.retry.scheduled` = `{sessionID, assistantMessageID, attempt, at, error:{type,message}}`。

### 4.4 `session.retry.scheduled` 相对 V1 的增强

V1 有两套重试信号：
1. `session.next.retried`（`session-event.ts:387-396`，durable，带完整 `RetryError`）。
2. `RetryPart`（`v1/session.ts:220-231`，嵌在 message parts 里的 `{type:"retry",attempt,error}`）。

V2 `session.retry.scheduled` **新增 `next`（`at`）时间戳 + 更丰富 `error`（含 `action` upsell：`reason/provider/title/message/label/link`）**，使客户端能显示倒计时和操作按钮。退避逻辑同服务端 `retry.ts:35-66`（见 §1.6）。

### 4.5 schema 包对 V1→V2 的官方说明

`packages/schema/AGENTS.md`：
> "Keep clearly V1-only events, such as `message.updated` and `message.part.*`, out of the current Protocol/SDK Next event surface unless a current-client requirement is documented."
> "Keep compatibility events available only to the existing App/TUI/CLI compatibility surface while they are still needed."

——确认 V1（`message.part.*`）是有意排除在新协议面之外；`session.next.*` 是规范替代。

---

## 5. TUI 简略对比

### 5.1 Bootstrap（批量快照，非事件）

`packages/tui/src/context/sync.tsx:445-546` `bootstrap()`：
- 阻塞阶段（`Promise.all` 464-472）：providers/capabilities/agents/config/project；session list 仅 `--continue` 时阻塞。
- 非阻塞阶段（514-530）：`status` 转 `"partial"` 后并行触发：
  ```typescript
  sdk.client.session.status({ workspace }).then((x) => {
    setStore("session_status", reconcile(x.data ?? {}))   // 524-526: 批量 REST 快照
  })
  ```
- **批量快照调用**，非事件驱动。

### 5.2 事件直写（V1 原始订阅）

TUI 直接订阅 V1 事件流（`sync.tsx:170` `event.subscribe`），事件已是 V1 格式，直接 `setStore`：
- `session.status` → `setStore("session_status",...)`（311）
- `session.updated` → reconcile 进 session 数组（280）
- `message.updated` → reconcile 进 `store.message[sessionID]`（316-353）
- `message.part.updated` → reconcile 进 `store.part[messageID]`（370-389）
- `message.part.delta` → **字符串字段追加**（392-408，读旧值 + 拼接 delta）
- `message.part.removed` → 从 parts 数组 splice（411-425）

### 5.3 TUI vs Web App 关键差异

1. **store 形状**：TUI 把原始 V1 `message[]` + `part[]` 分 map 存（`sync.tsx:93-98`）；Web App 用 V2 reducer 把 V2 事件投影成扁平 `SessionMessageInfo[]`（`server-session-v2-reducer.ts:7-12`）。
2. **事件处理策略**：TUI 每个事件直接 `setStore`/`reconcile`；Web App 经确定性 reducer `createV2SessionReducer().reduce(messages, event)`（`server-session-v2-reducer.ts:17`）。
3. **消息上限**：TUI 每会话限 100 条（`sync.tsx:335-352`，移除最旧 + 删其 parts）；Web App reducer 保留全部。

### 5.4 双击 interrupt → abort 模式

`packages/tui/src/component/prompt/index.tsx:391-420`：

```typescript
{
  title: "Interrupt session", name: "session.interrupt",
  enabled: status().type !== "idle",
  run: () => {
    // ... 守卫 ...
    setStore("interrupt", store.interrupt + 1)       // 407: 计数器 +1
    setTimeout(() => { setStore("interrupt", 0) }, 5000)  // 410: 5s 后重置
    if (store.interrupt >= 2) {                       // 413: 第二次点击
      void sdk.client.session.abort({ sessionID: props.sessionID })  // 414: 硬 abort
      setStore("interrupt", 0)
    }
    dialog.clear()                                   // 419: 第一次仅关对话框
  }
}
```

- **第一次 interrupt**：关命令面板 + 软中断（`POST /api/session/:id/interrupt`）。
- **5s 内第二次 interrupt**：硬 abort（`session.abort`，杀执行进程）。
- 5s 超时重置，防误触。

> **对 ocdroid 的价值**：软中断 vs 硬 abort 分级。Android 可映射为：单击/短按 = interrupt，长按/双击 = abort（或菜单明确区分）。

---

## 6. 对 ocdroid 改进有参考价值的设计点提炼

> 本章是本调研的核心交付。按"值得借鉴"与"因差异不适用"分类，每条说明理由与上游证据。

### 6.1 强烈建议借鉴（V1 协议下直接适用）

| # | 设计点 | 上游证据 | 对 ocdroid 的价值 |
|---|---|---|---|
| **B1** | **稀疏 status 存储（idle=delete）** | `status.ts:42-45` | Android 内存敏感。idle 不存（默认缺 key=idle），只 busy/retry 驻留。客户端可同理：idle 时清条目，省内存。 |
| **B2** | **乐观 busy + 失败 rollback（submit）** | `submit.ts:60-68, 145-198` | POST prompt 前先写 busy，失败回滚 idle。消除移动网络 RTT 卡顿。**注意门控**：仅主目录 prompt 乐观写，跨目录不写（避免误报）。 |
| **B3** | **`stopping = working && inputBlank` 单按钮复用** | `prompt-input.tsx:286` | 发送/停止二合一按钮，按 busy+空输入切换。移动端屏小，比双按钮更优。busy 且有输入时按发送 = 中断（`submit.ts:325`）。 |
| **B4** | **纯事件驱动 + bootstrap reconcile 兜底（无轮询）** | `event-reducer.ts:42-44, 266-269`；`bootstrap.ts:382-405` | 上游无轮询；断线/重连靠 `server.connected` 触发全量 refresh。Android SSE 易断，**必须**实现：SSE 断 → 重连 → bootstrap 批量 reconcile 兜底。 |
| **B5** | **错误落盘两阶段时序感知** | `processor.ts:619, 595-596, 676`；`session.ts:633` | status=idle 先到，message.error 随 `message.updated` 稍后到。客户端判"出错"应等 message.updated 读 message.error，**勿**在 status=idle 时立即判定成功。 |
| **B6** | **retry 状态带 `next` 时间戳 + `action` upsell** | `session-status-event.ts:13-28`；`retry.ts:14-24, 35-66` | status.retry 携带 `next`（倒计时）和 `action`（限流/计费时的升级链接）。Android 可显示倒计时与"升级/查看"按钮，而非干等。 |
| **B7** | **interrupt/abort 分级** | TUI `prompt/index.tsx:391-420`；`handlers/session.ts:232-235` | 软 interrupt（首次）vs 硬 abort（二次/长按）。Android 可：短按=interrupt，长按=abort，或菜单明示。避免单一 abort 误杀。 |
| **B8** | **session.error 事件 ≠ status，独立 toast 通道** | `notification.tsx:396-399`；§2.9 | `session.error` 不进 status store，走通知通道。客户端应区分：status 表"会话忙不忙"，message.error/session.error 表"出了什么错"。勿把错误塞进 status 枚举。 |

### 6.2 值得参考但需 Android 化调整

| # | 设计点 | 调整点 |
|---|---|---|
| **A1** | **bootstrap 清陈旧 + reconcile 全量 + 预热缓存**（`bootstrap.ts:382-405`） | Android 进程可能被杀重建，冷启动需完整 bootstrap；reconcile 思路（diff-merge 避免整对象替换）对 Compose/MutableState 同样适用（减少重组）。 |
| **A2** | **同 fiber retry + 指数退避 + retry-after 头优先**（`retry.ts:35-66, 176-199`） | 这是**服务端**逻辑，客户端不重试 LLM。但客户端**自身网络请求**（SSE 重连、API 调用）可借鉴同款退避（2s×2^n，上限 30s，尊重 retry-after 头）。 |
| **A3** | **message.part.delta 字符串追加**（TUI `sync.tsx:392-408`） | Android 流式文本同样需增量拼接；注意 V1 delta 是 `{field, delta}`，按 field 累积。 |
| **A4** | **消息上限 100 条**（TUI `sync.tsx:335-352`） | TUI 限 100；Android 内存更紧，可设更激进上限或分页加载历史，但需保留滚动上下文。 |

### 6.3 不适用（V1/形态差异，勿照搬）

| # | 设计点 | 不适用原因 |
|---|---|---|
| **N1** | V2 `session.execution.*` / `session.next.*` 执行事件派生 | ocdroid 消费 **V1**，服务端不发这些事件。status 来源是显式 `session.status` 事件，非派生。 |
| **N2** | V2 reducer 把 V2 事件投影回 V1（`server-session-v2-reducer.ts` ~509 行） | ocdroid 直接吃 V1，无需适配层。 |
| **N3** | Runner 内部 Shell/ShellThenRun 状态 | 服务端内部态，客户端不可见（只看到 busy）。ocdroid 无需建模。 |
| **N4** | `optimisticBusy` 的 worktree 跨目录门控（`submit.ts:614`） | ocdroid 单目录形态，无 worktree 概念，门控条件可简化为恒乐观（或按自身多项目逻辑重设）。 |
| **N5** | SolidJS store / `reconcile` / `produce` 具体机制 | 框架特定。ocdroid 用 Kotlin/Compose，借鉴**思想**（细粒度 diff、批量更新）而非 API。 |
| **N6** | `session.idle` 废弃事件 | 已废弃，勿实现。 |

### 6.4 给 ocdroid 状态机的具体建议（综合）

基于上游证据，ocdroid 客户端状态机建议：

1. **三态 status 模型**（对齐 V1）：`idle` / `busy` / `retry`。**不引入 error 态**——错误走 message.error + session.error 事件通道（B8）。
2. **稀疏存储**：idle 时从内存 Map 移除条目（B1）。
3. **乐观更新**：发送 prompt 前置 busy，失败/中断回滚 idle（B2）。
4. **单按钮发送/停止**：`stopping = isBusy && inputIsEmpty`（B3）。
5. **错误判定的时序契约**：status=idle 不等于成功；必须等 `message.updated` 读 message.error 判定（B5）。
6. **retry UI**：用 `next` 显示倒计时，`action` 存在时显示升级入口（B6）。
7. **中断分级**：interrupt（软）与 abort（硬）分离，UI 明示（B7）。
8. **SSE 断线兜底**：重连后跑 bootstrap 批量 reconcile（B4）。
9. **网络层退避**：自身请求重用上游退避策略（2s×2^n, cap 30s, 尊重 retry-after）（A2）。

---

## 7. 调研准确性说明与证据索引

### 7.1 交叉核对发现的不一致（已修正）

1. **任务 hint 称 SessionStatus 含 error** —— 错。源码确认仅 idle/retry/busy（`session-status-event.ts:9-32`），error 在 message 字段。本报告 §0.2、§2.9 已修正。
2. **任务 hint 称 `runner.ts`** —— v1.18.7 无此文件；Runner 在 `effect/runner.ts`，run-state 在 `session/run-state.ts`（§0.1）。
3. **任务 hint 称 "interrupt 杀流+重试"** —— 措辞误导；interrupt 杀 fiber 但 `Effect.retry` 不在中断时触发（§0.4、§1.7）。
4. **explorer-2 初稿 H 节列出 `session.status{type:"error"}`** —— 该类型不存在，本报告 §2.9 已删除并改为正确落点表。

### 7.2 关键证据文件索引

| 主题 | 文件 | 关键行 |
|---|---|---|
| SessionStatus 类型 | `packages/schema/src/session-status-event.ts` | 9-32 |
| status 内存投影（稀疏 Map） | `packages/opencode/src/session/status.ts` | 26-48 |
| Runner 4 内态 | `packages/opencode/src/effect/runner.ts` | 33-37, 208-210 |
| run-state 编排 | `packages/opencode/src/session/run-state.ts` | 77-86 |
| processor 主循环 | `packages/opencode/src/session/processor.ts` | 539-597, 599-625, 627-683 |
| retry policy/退避 | `packages/opencode/src/session/retry.ts` | 14-24, 35-66, 68-152, 176-199 |
| halt 两阶段持久化 | `processor.ts` + `session.ts` | 599-625 / 595-596 / session.ts:631-635 |
| HTTP status/abort | `packages/opencode/src/server/routes/instance/httpapi/handlers/session.ts` | 77-79, 232-235 |
| V1 事件全集 | `packages/schema/src/v1/session.ts` | 357-370, 385-395, 571-657 |
| V2 session.next.* | `packages/schema/src/session-event.ts` | 47-512 |
| SSE 传输 | `packages/opencode/src/server/routes/instance/httpapi/handlers/event.ts` | 12-86 |
| Web session_working | `packages/app/src/context/global-sync/child-store.ts` / `server-session.ts` | 232-235 / 207-209 |
| Web 乐观 busy | `packages/app/src/components/prompt-input/submit.ts` | 57-198, 614 |
| Web stopping 公式 | `packages/app/src/components/prompt-input/prompt-input.tsx` | 279-286 |
| Web bootstrap V1 gate | `packages/app/src/context/global-sync/bootstrap.ts` | 382-405 |
| Web event-reducer | `packages/app/src/context/global-sync/event-reducer.ts` | 266-269 |
| Web applyV2 | `packages/app/src/context/server-session.ts` | 937-985 |
| TUI bootstrap/事件 | `packages/tui/src/context/sync.tsx` | 170-440, 445-546 |
| TUI 双击 abort | `packages/tui/src/component/prompt/index.tsx` | 391-420 |

### 7.3 调研边界

- **证据版本**：仅 v1.18.7。`opencode-src/current` 软链 v1.18.4，未使用。
- **V2 深度**：仅作背景，未穷尽 `session.next.*` 全部字段语义（ocdroid 不消费 V2）。
- **未覆盖**：服务端 EventV2Bridge 持久化细节、opencode CLI/codemode/console 等其他产品形态（不在调研范围）。

---

*报告结束。本报告为 ocdroid 状态机改进的上游参考输入；最终设计方案由综合 main-C 基于 本报告 + ocdroid 自身调研（main-B）综合产出。*
