# opencode 与 ocdroid 状态机对比：调研结论与待办

> **状态**：调研结论 + 待办项（**未实现**）。覆盖 send/stop 按钮与错误态子集，并在 §6–§7 给出全面状态机对比与优化评估；后续据此细化实施方案。
> **来源**：lib-1（opencode 上游源码调研）+ exp-1（ocdroid 本地实现测绘）。
> **配套**：[`./specs/l4-sse-lifecycle-design.md`](specs/l4-sse-lifecycle-design.md)（SSE 生命周期 / receive-only fence）、[`./specs/l4-background-sse-reconnect-design.md`](specs/l4-background-sse-reconnect-design.md)（重连）、[`./specs/sse-client-spec.md`](specs/sse-client-spec.md)（SSE 客户端规范：legacy + slim + v2 策展帧）、[`./specs/architecture.md`](specs/architecture.md)（分层 / 不变量）。

---

## 1. 问题现象（用户反馈）

1. **重试/等待期间 stop 按钮消失**：即使会话正卡在 API 重试或退避等待，输入栏旁边的按钮常常显示为「待发送」而非「停止」。
2. **错误信息显示不稳定**：API 限流/报错时，错误提示时有时无。
3. **回到页面看不到错误态**：用户离开后回来，无法像 opencode web 那样看到会话处于错误/运行态并主动停止。

对照 opencode web：错误时持续错误信息常驻会话、stop 按钮全程可用、回到页面仍可见运行态。

---

## 2. opencode 的机制（参考设计）

核心哲学：**会话状态完全由服务器权威持有，客户端只是忠实镜像**。客户端不需要自己猜「现在有没有请求在途」。

### 2.1 三态模型（互斥）

`packages/schema/src/session-status-event.ts`：

```ts
Info = idle | busy | retry(attempt, message, action, next)
```

服务器在 `SessionStatus.Service`（`packages/opencode/src/session/status.ts`）用内存 `Map<SessionID, Info>` 持有状态，变化时经事件总线广播。

### 2.2 唯一的「是否在干活」布尔

`packages/app/src/context/global-sync/child-store.ts`：

```ts
session_working(id) {
  const type = this.session_status[id]?.type
  return (type ?? "idle") !== "idle"   // busy 和 retry 都算 working
}
```

### 2.3 send/stop 按钮切换条件

`packages/app/src/components/prompt-input.tsx`：

```tsx
const working  = sync().data.session_working(session.id ?? "")
const blank    = /* 输入框文本/图片/评论全空 */
const stopping = working() && blank()

<IconButton icon={stopping() ? "stop" : "arrow-up"}
            onClick={() => stopping() ? onStop() : onSubmit()} />
```

**判据：`停止图标 = (会话非idle) && (输入框为空)`。** 输入框有内容时即使会话在跑也显示发送箭头（允许排队下一条）。

### 2.4 为什么重试期间 stop 常驻（关键设计）

`packages/opencode/src/session/processor.ts`：LLM 流和 retry 循环跑在**同一个 Effect fiber/scope** 里。

```ts
yield* status.set(sessionID, { type: "busy" })          // 流开始 → busy
// ...llm.stream...
.pipe(
  Effect.retry(SessionRetry.policy({                     // 可重试错误 → retry
    set: (info) => status.set(sessionID, { type: "retry", attempt, message, action, next }),
  })),
  Effect.catch(halt),                                    // 不可重试/重试耗尽 → idle
)
```

时序：`busy → (429/5xx) → retry（退避期全程 retry，非 idle）→ busy → retry → ... → 重试耗尽 → idle`。
**因为 retry 是独立状态且非 idle，`session_working()` 全程为 true，stop 按钮自然常驻。** 错误信息（retry 携带的 message/attempt/next）随状态持续推送，web 端渲染成会话内错误/重试提示。

重试策略（`packages/opencode/src/session/retry.ts`）：5xx、`isRetryable=true`、rate-limit 文本匹配才重试；退避 2s 起、2 倍指数、上限 30s，尊重 `retry-after` 头。

### 2.5 stop 的取消语义：一次 interrupt 同时杀流和重试

客户端 `packages/app/src/components/prompt-input/submit.ts` → `sdk().api.session.interrupt({ sessionID })`。
服务器 `packages/opencode/src/effect/runner.ts` `Runner.cancel` → `Fiber.interrupt(run.fiber)`。因流与重试同属一个 fiber，中断同时取消二者；`onInterrupt → halt → idle`。

### 2.6 回到页面/重连如何恢复运行态

`packages/app/src/context/global-sync/bootstrap.ts`：每次连接/重连全量拉取所有会话状态 `sdk.session.status()` 并 reconcile；之后靠 `session.status` 事件订阅（`server-session.ts`）持续更新。**刷新后只要服务器仍 busy/retry，stop 按钮立即出现。**

---

## 3. ocdroid 现状

ocdroid **镜像了同一套三态模型**，按钮条件也**包含 isRetry**：

```kotlin
// ChatScaffold.kt:583
val currentSessionIsRunning =
    (curSessionStatus?.isBusy || curSessionStatus?.isRetry) == true ||
    chromeSessionId in composer.sendingSessionIds

// Composer.kt:164
val canStop = isBusy && !canSend   // isBusy = currentSessionIsRunning || chat.isCompacting
```

**即：若 ocdroid 能可靠收到服务器的 retry 状态事件，按现有代码 stop 按钮本应常驻。**

`SessionStatus`（`Session.kt:90`）已具备 idle/busy/retry 三态 + attempt/message/next 字段。`sessionStatuses: Map<String, SessionStatus>`（`AppStateSlices.kt:716`）。

三路状态信号汇入 `currentSessionIsRunning`：

| 信号源 | 何时置 true | 何时清除 | 覆盖范围 |
|---|---|---|---|
| `sessionStatuses[id].isBusy` | ① POST 成功乐观写 ② SSE `session.status{busy}` ③ REST 轮询 | SSE `idle` / REST idle | POST 后整个运行期 |
| `sessionStatuses[id].isRetry` | **仅**服务器 SSE `session.status{retry}` | SSE `idle` / REST | 若服务器发 retry 则兜住 |
| `id in sendingSessionIds` | POST 前（`AppCoreOrchestration.kt:722`） | **POST HTTP 返回时**（`AppCoreOrchestration.kt:806`） | **仅** POST HTTP 往返窗口 |

stop 机制：`ChatViewModel.abortSession` → `OpenCodeRepository.abortSession` → `StandardApi.abortSession`（`POST /session/{id}/abort`）。仅服务器端操作，依赖服务器后续发 `session.status{idle}` 清状态。

---

## 4. 差距分析

| 维度 | opencode（正确） | ocdroid（现状） |
|---|---|---|
| 客户端「在途」兜底标志 | 不需要——服务器权威 + 全量重连拉取 | 有 `sendingSessionIds`，但**POST HTTP 返回即清**，窗口只覆盖 HTTP 往返，不覆盖流+重试周期 |
| retry 状态事件接收 | 服务器退避期持续发 retry（retry Schedule 的 set 回调写 status=retry），客户端忠实镜像 | **解析正确**：`parseSessionStatusEvent`（`ignoreUnknownKeys=true`+`coerceInputValues=true`）能正确产出 `isRetry=true`。但：① `session.status` **仅在 legacy 线路**由 `LegacySseHandler` 处理，slim 线路 `SlimSseHandler` 不处理（slim 靠 digest，是否带状态待核）；② 客户端从不本地创建 retry，完全依赖服务器事件到达 |
| 错误→idle 覆盖 | 仅重试耗尽/不可重试才 idle | `LegacySseHandler.handleSessionStatus` 对 SSE 直写覆盖（无时间戳/合并保护）；乐观 busy 可被永久冲掉 |
| 错误信息持久化 | retry 的 message/attemp/next 随状态持续推送并渲染进会话 | 错误走 `UiEvent.Error` snackbar（临时），**不写入会话历史** → 显示不稳定 |
| 重连恢复 running | bootstrap 全量拉取 status | 待 exp 确认是否做等价全量 status 拉取并 reconcile |

**根因（已确认）：**
1. 「重试等待期 stop 消失」→ **非解析问题**。`sendingSessionIds` 仅覆盖 POST HTTP 往返（`onComplete` 即清），窗口太窄；若 SSE busy/retry 未及时到达或处于 slim 线路无 status 事件，三路信号同时为 false。
2. 「错误信息不稳定」→ 临时 snackbar + 脆弱的 `sessionErrorsById` 横幅；`LastAssistantErrorAttached` 有 route-instance 守卫可能静默丢弃，消息列表重置时丢失；`session.error` 仅 slim 线路处理。
3. 「回到页面看不到错误态」→ **已确认缺重连全量 status 拉取**：冷启动/重连只 REST 拉 `sessions`，`sessionStatuses` 初始为空，直到首个 SSE 事件才填充——此窗口内 isBusy/isRetry 皆 false。

---

## 5. 待办项（Backlog）

> 优先级 P0=直接影响用户可见 bug；P1=稳健性；P2=体验对齐。
> 实施前以「后续调研」结论为准。

- **[P0] 重连全量拉取 session status（对齐 opencode bootstrap）**：冷启动/重连时调一次全量 `session.status()` 并 reconcile 进 `sessionStatuses`，消除「重连后到首个 SSE 事件前状态全空」窗口。这是「回到页面仍见运行/错误态」的关键修复，低风险。
- **[P0] 扩展「在途」兜底标志生命周期**：让 `sendingSessionIds`（或新增 `requestInFlight`）跨越 **POST + 整个流 + 重试周期**，仅在服务器发 `session.status{idle}` 终态或用户 abort 时清除，而非 POST HTTP 返回即清。
- **[P0] 错误信息持久化进会话**：将 retry 的 message/attempt/next（及 action）与致命错误（对齐 opencode `assistantMessage.error`）作为消息/状态的一部分持久化渲染，替代/补充临时 snackbar。
- **[P0] 确认 slim 线路 status 来源**：核 `session.digest` 是否携带 busy/retry 状态；若否，slim 模式需补 status 投影路径（否则 slim 模式下按钮状态只能靠乐观标志 + REST 轮询）。
- **[P1] SSE status 直写覆盖防护**：给 `LegacySseHandler.handleSessionStatus` 的直写引入版本/时间戳或合并保护，避免乐观 busy 被瞬态 idle 永久冲掉（参照 `mergeStatusSnapshot` 的 REST 防护思路）。
- **[P1] 重连全量 status 拉取**：确认/补齐重连时等价 opencode bootstrap 的全量 `session.status` 拉取并 reconcile，保证「回到页面仍见运行/错误态」。
- **[P2] action 字段驱动 UI**：retry 携带的 `action`（free_tier_limit / account_rate_limit）映射到对应提示/操作入口。

---

## 6. 全面状态机对比（已完成）

### 6.1 opencode 完整状态架构
- **多服务分治**：`SessionStatus.Service`（内存 Map，busy/retry/idle）+ `SessionRunState.Service`（Runner）+ `Session.Service`（SQLite 持久化 messages/parts）+ permission/config/auth 等。
- **Runner 状态机**：`Idle/Running/Shell/ShellThenRun`，`busy = state()._tag !== "Idle"`。**重试跑在 Runner fiber 内部**（`Effect.retry` 不退出 fiber），故 busy 全程为 true。
- **V2 事件层**：`session.execution.started/succeeded/failed/interrupted` 与 `session.retry.scheduled` 映射到 V1 `session_status`；retry 帧确切格式 `{type:"session.status", properties:{sessionID, status:{type:"retry", attempt, message, action?, next}}}`。
- **客户端同步**：bootstrap 重连全量 `session.status()` + `reconcile()` 深合并；事件实时更新；客户端 store 是服务器状态的**严格镜像**，仅 `optimistic`（待确认消息/部分）与 `part_text_accum_delta`（delta 缓冲）为客户端专属，且后者被 `message.part.updated` 清除。
- **错误持久化**：致命错误写进 `assistantMessage.error`（消息元数据），非临时事件。
- **单一真相源不变量**：`session_working(id) = (status.type ?? "idle") !== "idle"`，无需客户端推断「请求在途」。乐观 `setBusy()` 在 API 失败时回滚为 idle。

### 6.2 ocdroid 现状与 opencode 的结构性差异
| 维度 | opencode | ocdroid |
|---|---|---|
| 「在途」真相源 | 服务器 status 权威，客户端不维护 | 客户端 `sendingSessionIds`（POST 窗口）+ 乐观 busy + 服务器 status 三者并存，边界模糊 |
| 重连恢复 | bootstrap 全量拉 status | **无**——冷启动只拉 sessions，status 初始空（已确认） |
| 乐观态回滚 | API 失败即回滚 idle | 乐观 busy 无回滚保护，可被 SSE idle 直写覆盖 |
| 错误持久化 | 写进消息 `assistantMessage.error` | snackbar + 脆弱横幅 + route 守卫，易丢失 |
| SSE status 路径 | 单一事件流 | legacy/slim 双线不对称：`session.status` 仅 legacy 处理 |
| 移动端约束 | 无（常驻 web） | 后台 receive-only / no-source terminal / 15min 拆除（见 L4 设计） |

## 7. 状态机优化评估

**结论：可以、且应以 opencode 的「服务器权威 + 重连全量恢复」模型为核心优化本项目状态机，但须保留移动端的客户端投机态作为离线/后台兜底。**

核心原则（采纳）：
1. **单一真相源**：让 `sessionStatuses[id]` 成为 `session_working` 的唯一权威，弱化 `sendingSessionIds` 为「POST→首个服务器 status 事件」的瞬态桥接（而非全生命周期标志）。
2. **重连全量恢复（最高价值、低风险）**：补 bootstrap 全量 status 拉取 + reconcile，解决「回到页面看不到运行态」与重连空窗。
3. **乐观态对齐回滚语义**：乐观 busy 在 API 失败时回滚 idle（对齐 opencode）；SSE 直写加版本/时间戳合并保护，避免瞬态 idle 冲掉乐观态。
4. **错误进消息**：致命错误写进消息（对齐 `assistantMessage.error`），retry 状态作为会话内提示渲染。
5. **打通 slim status 路径**：确保 slim/legacy 两线都能获得 busy/retry/idle。

保留（移动端特有，不可照搬 web）：
- 后台/断网期间无 live SSE，需保留 `streamOwned`、`isCompacting`、pending 标志等客户端投机态。
- 但这些投机态必须**可被重连 bootstrap 校正**——这才是 opencode 模型在移动端的正确落地。

建议落地顺序（低风险高收益 → 结构性）：
1. 重连 bootstrap 全量 status 拉取（P0，独立可上线）。
2. 错误持久化进消息（P0）。
3. 在途标志生命周期修正 + SSE status 合并保护（P0/P1）。
4. slim status 路径打通（P0/P1）。
5. （可选，P2）将 `session_working` 收敛为单一派生，逐步退役冗余投机标志。
