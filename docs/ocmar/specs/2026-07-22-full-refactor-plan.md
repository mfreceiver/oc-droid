# ocdroid 全量代码健康修复方案（终稿）

> **日期**：2026-07-22
> **状态**：**双方认知已同步 / 可启动 T0 + T-R1**（ocdroid × oc-slimapi 2026-07-22 对齐；C1–C7 已确认、C3 已裁决、R1–R7 已并入 task 树）
> **优化目标**：① 代码质量优化 ② 架构级 legacy/slim 清晰区分、易于管理
> **依据**：`docs/ocmar/reports/2026-07-22-code-health-audit.md` + `docs/ocmar/specs/2026-07-22-slim-legacy-isolation-task2.md` + 源码逐条核实（rev-gpt 终审）
> **执行约束**：测试与实现并行独立开发；测试用例一律由 fixer 编写并冻结为契约；终验由禁止改测试的 `_priv-verifier` 执行；**每个 Task 须通过 rev-gpt ≥ 9.5 门控方可合并**

---

## 一、范围与执行约束

### 1.1 修复范围（批次 0–6 全做）

| 批次 | 内容 |
|---|---|
| **T0** | 安全网：legacy/slim 双轨特征化 + 回归测试 |
| **T-R1** | status 降频（slimapi R1）：停 4s 轮询 `/session/status`+`/api/session/active` → `/slimapi/sessions/status` cold-start + digest `status` 接力；断连降级 10–30s。纯客户端、slimapi 零改动，与 T0 并行 |
| **T1** | 状态所有权收口（4 slice 单一语义 reducer + AppAction 路由）+ Task 2 P0/P1 合流 |
| **T2** | 拆 `dispatchSseEvent`（910 行 when → 三类 Sse*Handler + Router） |
| **T3** | 拆 `OpenCodeRepository`（→ HttpClient/SseState/ApiFacade/Tofu） |
| **T4** | 拆 UI 大 Composable（ChatScaffold/ChatMessageContent + 去重） |
| **T5** | 清理（死代码/魔法值/recomposition） |
| **T6** | 构造参数与生命周期瘦身（AppCore/Service/Controller，按域 runtime） |

### 1.2 执行顺序（硬约束）

```text
T0 ┐
   ├─→ T1 → {T2 ∥ T3 ∥ T4} → T5 → T6 → Release gate
T-R1┘
```

- **必须串行**：T0→T1（无行为基线不得迁状态）；T1→T2（handler 拆分改写入口，须先定所有权）；T0–T5→T6（构造瘦身放大所有改动影响面）。
- **可并行**：**T0 与 T-R1 立即并行**（T-R1 纯客户端、slimapi 零改动）；T2/T3/T4 在 T1 完成后并行，但**禁同改** `AppAction.kt`/`SharedStateStore.kt`/`AppCore.kt`；T3 不得同改 `HostConfig` slim 传播契约；T4 不得改 state schema。
- **风险排序**：T6（最高）> T1 > T2 > T3 > T4 > T5 > T0/T-R1。

### 1.3 关键架构事实（设计前提）

1. **状态写者**：`SharedStateStore.state` 已是唯一物理 commit 点（`SharedStateStore.kt:53-54`），但 `mutateChat`/`mutateSessionList` 被多模块直接调用（语义写者未收口）。T1 目标 = **按状态字段族定义唯一语义 reducer**，业务代码只 dispatch action。
2. **SSE 事件按 wire 发射归属（C3 修正，slimapi 确认）**：legacy `/global/event` 与 slim `/slimapi/events` **发射事件集不相交**——`message.updated`/`message.part.*`/`text.delta`/`tool.*` 是 **legacy-only wire**（slim SSE 明确丢弃；slimapi 仅用 `message.updated` 内部驱动 digest 聚合，**不下发**）；`session.digest`/`question.*`/`permission.*`/`server.*`/`resync`/`session.error` 是 **slim-only wire**。客户端 `dispatchSseEvent` 的 message.part.* handler（`SSC.kt:1258-1465`）在 slim 模式下**永不触发**（dormant）——slim 内容更新经 digest→REST skeleton/full，**无 token 级流式**（省流设计）。故「共享」仅指**纯转换函数库**（`applyPartDelta`/`applyMessageUpdated` 可复用），**不存在两 SSE 流都发射的事件**；T2 Router 按事件类型→域路由，**不得期望 slim 流含 `message.part.*`**。（此修正推翻前序渲染审计「slim SSE 不丢 text.delta」的误判——slim 模式 streamingPartTexts 不由 SSE 喂充。）
3. **AppCore 瘦身边界**：22 构造参数（`AppCore.kt:80-193`）按域拆 `SessionRuntime`/`ConnectionRuntime`/`HostRuntime`；**禁止**用一个更大「上帝 Context」替换当前上帝对象。

---

## 二、目标架构

### 2.1 依赖方向与模块图

```text
                         ┌──────────────────────┐
                         │      UI / Compose     │
                         └──────────┬───────────┘
                                    │ Read-only StateFlow
                                    ▼
                         ┌──────────────────────┐
                         │   Application Store   │
                         │ StoreState / AppAction │
                         └───────┬───────┬──────┘
                                 │       │
                    ┌────────────┘       └─────────────┐
                    ▼                                  ▼
          ┌──────────────────┐                ┌──────────────────┐
          │  Legacy Domain    │                │    Slim Domain    │
          │ LegacySseHandler  │                │ SlimSseHandler    │
          │ LegacySessionUse  │                │ DigestHandler     │
          │ LegacyPolling     │                │ Recoe Engine/Cold │
          └────────┬─────────┘                └────────┬─────────┘
                   └──────────────┬───────────────────┘
                                  ▼
                         ┌──────────────────────┐
                         │ Shared Event Seam     │
                         │ SseEvent / Msg folding│
                         └──────────┬───────────┘
                                    ▼
                         ┌──────────────────────┐
                         │   Transport Layer     │
                         │ HttpClient/Legacy/    │
                         │ Slim Api/SSEClient/   │
                         │ TofuManager           │
                         └──────────────────────┘
```

**依赖规则**：UI→Store read model；Controller→Domain use case；Domain→Shared contracts + Store dispatcher；Transport→Data models/HTTP 契约。**禁止**：Data/Transport→UI；Slim domain↔Legacy domain 互依。

### 2.2 legacy/slim 双域隔离

**模式身份**：`HostConfig.slim`(Boolean) 在运行时转为不可变 `ModeDomain`：

```kotlin
sealed interface ModeDomain { data object Legacy : ModeDomain; data object Slim : ModeDomain }
data class HostRuntimeConfig(val baseUrl:String, val hostPort:String?, val username:String?, val password:String?, val mode:ModeDomain)
```

- `HostProfile.slim` 只持久化（`HostProfile.kt:69-91`）；`HostConfig` 转 `HostRuntimeConfig`；controller 不再逐业务点读 `isSlimMode()`；host 切换建新 runtime，旧 runtime 经 epoch/token 失效。
- 传输域保留加性分流（SSE path `SSEClient.kt:137`、双门闩 `SlimapiVersionInterceptor.kt:39-42`/`SlimapiCapabilitiesInterceptor.kt:43-46`）；**不强制**双物理 OkHttp client（自门控够用，wire-shape 须有测试锁定）。

**事件按 wire 归属（两流不相交，C3 修正）**：
- **Legacy-only wire**（`/global/event`）：`message.updated`/`message.part.created/updated/delta`/`text.delta`/`tool.*`/legacy `session.created/updated/status`/`permission.asked`/`question.asked`
- **Slim-only wire**（`/slimapi/events`）：`session.digest`/`question.*`/`permission.*`/`server.connected|heartbeat|reconfigured`/`resync`/`session.error`
- 「共享」= 纯转换函数库（`applyPartDelta`/`applyMessageUpdated`）可被复用，**非 wire 共发**。T2 的 `SharedConversationSseHandler` 实为 **legacy-wire 会话 handler**（处理 message.part.*，仅经 `/global/event` 喂入）；其纯函数可被 slim 的 REST/digest 路径复用，但 handler 本身不挂在 slim SSE 路由上。

**状态三类**：SharedConversationState（messages/partsByMessage/streamingPartTexts）/ LegacySessionState（expandedParts/legacy session-status）/ SlimSessionState（digest bookmarks/reconcile dirty/partExpandStates/aggregation）。物理可先共存，经 reducer 约束写入边界。

### 2.3 状态所有权

| 状态 | 唯一语义 writer | 允许的 action |
|---|---|---|
| `streamingPartTexts` | StreamingReducer | PartFullTextReceived/PartDeltaReceived/StreamingCleared |
| `partsByMessage` | ConversationReducer | PartUpserted/MessageReconciled/ChatCleared |
| `expandedParts` | LegacyFoldReducer | PartExpansionToggled |
| `sessions` | SessionListReducer | SessionUpserted/SessionsRefreshed/SessionArchived |

CQRS-lite：Command（handler→AppAction→Store.dispatch）；Query（只读 StateFlow）；Side effect（network/Settings/effectBus 在 dispatch 前后）。`reduce` 纯函数，禁 network/settings/effect/suspend（`AppAction.kt:33-42` 扩大覆盖）。

---

## 三、核心代码骨架

### 3.1 ModeDomain + handler seam + Router

放置：`ui/controller/sse/{ModeDomain,SseEventHandler,SharedConversationSseHandler,LegacySseHandler,SlimSseHandler,SseEventRouter}.kt`

```kotlin
sealed interface ModeDomain { data object Legacy : ModeDomain; data object Slim : ModeDomain }

data class SseDispatchContext(val currentSessionId: String?, val mode: ModeDomain, val serverGroupFp: String)

sealed interface SseDispatchResult {
    data object Ignored : SseDispatchResult
    data class Handled(val actions: List<AppAction> = emptyList(), val effects: List<ControllerEffect> = emptyList()) : SseDispatchResult
}

interface SseEventHandler {
    fun supports(eventType: String): Boolean
    fun handle(event: SSEEvent, context: SseDispatchContext): SseDispatchResult
}

class LegacySseHandler(private val reducer: LegacySseReducer) : SseEventHandler {
    override fun supports(t: String) = t in setOf("session.created","session.updated","session.status","permission.asked","question.asked")
    override fun handle(e: SSEEvent, c: SseDispatchContext): SseDispatchResult {
        check(c.mode is ModeDomain.Legacy)
        return SseDispatchResult.Handled(actions = reducer.reduce(e, c))
    }
}

class SlimSseHandler(private val digest: SlimDigestReducer, private val reconcile: SlimReconcileTrigger) : SseEventHandler {
    override fun supports(t: String) = t in setOf("session.digest","session.error")
    override fun handle(e: SSEEvent, c: SseDispatchContext): SseDispatchResult {
        check(c.mode is ModeDomain.Slim)
        return when (e.payload.type) {
            "session.digest" -> digest.reduce(e, c)
            "session.error" -> reconcile.onError(e, c)
            else -> SseDispatchResult.Ignored
        }
    }
}

class SharedConversationSseHandler(private val reducer: ConversationSseReducer) : SseEventHandler {
    override fun supports(t: String) = t in setOf("message.updated","message.part.created","message.part.updated","message.part.delta")
    override fun handle(e: SSEEvent, c: SseDispatchContext) = SseDispatchResult.Handled(actions = reducer.reduce(e, c))
}

class SseEventRouter(private val shared: SharedConversationSseHandler, private val legacy: LegacySseHandler, private val slim: SlimSseHandler) {
    fun route(e: SSEEvent, c: SseDispatchContext): SseDispatchResult {
        val handlers = buildList {
            add(shared)
            when (c.mode) { ModeDomain.Legacy -> add(legacy); ModeDomain.Slim -> add(slim) }
        }
        val h = handlers.firstOrNull { it.supports(e.payload.type) } ?: return SseDispatchResult.Ignored
        return h.handle(e, c)
    }
}
```

**契约**：shared handler 不读 slim-only state；legacy handler 不依赖 SlimSseStateMachine；slim handler 不调 legacy polling；**Router 是唯一模式选择点**。

### 3.2 状态所有权 reducer

```kotlin
internal sealed interface AppAction {
    data class PartDeltaReceived(val sessionId:String, val messageId:String, val partId:String, val delta:String, val partType:String) : AppAction
    data class PartFullTextReceived(val sessionId:String, val messageId:String, val partId:String, val fullText:String, val partType:String) : AppAction
    data class PartUpserted(val messageId:String, val part:Part) : AppAction
    data class PartExpansionToggled(val key:String, val expanded:Boolean) : AppAction
    data class SessionsRefreshed(val sessions:List<Session>, val openSessionIds:List<String>, val hasMore:Boolean) : AppAction
    data object StreamingCleared : AppAction
}

internal fun reduce(state: StoreState, action: AppAction): StoreState = when (action) {
    is AppAction.PartDeltaReceived -> state.copy(chat = state.chat.applyPartDelta(action.partId, action.messageId, action.sessionId, action.delta, action.partType))
    is AppAction.PartFullTextReceived -> state.copy(chat = state.chat.applyPartFullText(action.partId, action.messageId, action.sessionId, action.fullText, action.partType))
    is AppAction.PartUpserted -> state.copy(chat = state.chat.withPart(action.messageId, action.part))
    is AppAction.PartExpansionToggled -> state.copy(expandedParts = state.expandedParts + (action.key to action.expanded))
    is AppAction.SessionsRefreshed -> state.copy(sessionList = state.sessionList.copy(sessions = action.sessions, openSessionIds = action.openSessionIds, hasMoreSessions = action.hasMore))
    AppAction.StreamingCleared -> state.copy(chat = state.chat.copy(streamingPartTexts = emptyMap(), streamingReasoningPart = null, deltaBuffer = emptyMap(), fullTextBuffer = emptyMap(), pendingFlushPartIds = emptySet()))
}

internal fun SharedStateStore.dispatch(action: AppAction) { state.update { reduce(it, action) } }
```

### 3.3 slimOnlyStateWrite 断言（Task 2 P1）

```kotlin
private inline fun <T> slimOnlyStateWrite(mode: ModeDomain, label: String, block: () -> T): T {
    check(mode is ModeDomain.Slim) { "slim-only state write [$label] invoked outside slim domain" }
    return block()
}

fun applySlimColdStartSnapshot(snapshot: SlimColdStartSnapshot, token: OpenCodeRepository.SlimCommitToken): Boolean {
    val repo = repository ?: return false
    check(modeProvider() is ModeDomain.Slim) { "cold-start snapshot must not apply in legacy mode" }
    return repo.commitIfSlimTokenCurrent(token) {
        slimOnlyStateWrite(modeProvider(), "cold-start-snapshot") { store.dispatch(AppAction.SlimColdStartApplied(snapshot)) }
    }
}
```

**纪律**：断言**只挂 slim 专属入口**（cold-start/reconcile merge/ClearLocal）；**不挂** `message.part.*` 共享分支（legacy 正常路径，`SSC.kt:1317-1465`）。

### 3.4 完整 handler 样例：MessagePartDeltaHandler

```kotlin
class MessagePartDeltaHandler(private val store: SharedStateStore, private val currentSessionId: () -> String?) : SseEventHandler {
    override fun supports(t: String) = t == "message.part.delta"
    override fun handle(e: SSEEvent, c: SseDispatchContext): SseDispatchResult {
        val p = parseMessagePartDeltaEvent(e) ?: return SseDispatchResult.Ignored
        if (p.sessionId != currentSessionId()) return SseDispatchResult.Ignored
        val mid = p.messageId ?: return SseDispatchResult.Ignored
        val pid = p.partId ?: return SseDispatchResult.Ignored
        val delta = p.delta?.takeIf { it.isNotBlank() } ?: return SseDispatchResult.Ignored
        return SseDispatchResult.Handled(actions = listOf(AppAction.PartDeltaReceived(p.sessionId, mid, pid, delta, p.partType)))
    }
}
```

该 handler 不知 Repository/slim token/legacy polling/Compose/SettingsManager → 两模式皆可复用，不污染任何模式专属域。

---

## 四、逐批次执行方案

> 每个 Task 通用流程：① fixer 写测试并冻结（契约）→ ② 实现 agent 独立改生产码（禁碰测试）→ ③ reviewer 审 diff + 测试未被动 → ④ `_priv-verifier` 跑 `check.sh`+破坏实验（测试 hash 前后不变）→ ⑤ **rev-gpt ≥ 9.5 门控**方可合并。

### T0 — 安全网与特征化测试
- **目标**：冻结当前行为，建 legacy/slim 双轨回归闸。
- **改**：新增 `LegacyGoldenPathRegressionTest.kt`；扩 `SessionSyncCoordinatorTest`/`SlimTest`/`ResyncTest`/`OpenCodeRepositorySlimapiEndpointsTest`。`check.sh:18-22` 默认链不变（自动收 *Test）。
- **覆盖**：slim=false（session switch 保消息 / part.delta→streamingPartTexts / message.updated patch / legacy reconcile 不触发 slim 写 / legacy cold-start 不改 slim snapshot / legacy tool fold 只用 expandedParts）；slim=true（digest→reconcile / cold-start merge / stale token 不提交 / partExpandStates 与 expandedParts 不互染）；transport（legacy 无 /slimapi/ path 与 headers；slim 必须同时有）。
- **验收**：`check.sh` 绿；移除 cold-start mode guard→红；改 legacy path 为 /slimapi/events→红。

### T1 — 状态所有权 + Task 2 P1 合流
- **目标**：共享物理 store → 单一语义 writer + AppAction 路由。
- **改**：`SharedStateStore.kt:94-147`、`AppAction.kt:44-617`、`SSC.kt:1270-1460`/`:2790-2849`/`:3307-3328`、`MessageActions.kt:169-170`/`:354-356`、`SessionListActions.kt:157-333`、`SessionMutationActions.kt:44-102`；Task 2 P1 `SSC.kt:3354`/`:3307`/`:2794`。
- **做法**：四类状态定义 action；禁业务模块直接 mutateChat/mutateSessionList/mutateExpandedParts 改目标字段（降为内部实现）；handler 只构造 action；slim-only 断言挂模式域入口 + action guard。
- **验收**：目标字段业务直接写入点静态归零；每字段族唯一 reducer；reduce 无副作用；legacy 调 slim-only entry→IllegalStateException；T0+全套 slim 测试绿。
- **风险**：action 顺序致跨 slice 行为变化 → 一次只迁一个 action，保留旧路径仅供测试对比。

### T2 — 拆 dispatchSseEvent
- **目标**：SSC 退化为事件路由器。
- **改**：`SSC.kt:894-1770`；新增 `SharedConversationSseHandler`/`LegacySseHandler`/`SlimSseHandler`/`SseEventRouter`/`DeltaBufferManager`/`ReconcileEngine`。
- **做法**：第一阶段纯搬迁不改 action/副作用顺序；ReconcileEngine 只输出 ReconcileResult；`applyReconcileResult` 迁为 `ReconcileCommitter` 经 action 提交。
- **验收**：SSC 不再含 910 行 when；每 handler 可独立单测；T0 SSE golden 输出完全一致；slim handler 不被 legacy route 调用。
- **风险**：事件顺序/flush/effect 顺序变 → 保留 trace 比对新旧 action/effect 序列；路由器可留旧 dispatcher 隐藏 fallback 验证后删。

### T3 — 拆 OpenCodeRepository
- **目标**：分离 transport/slim 状态/API facade/TOFU，外部 API 兼容。
- **改**：`OpenCodeRepository.kt:143-191`/`:209-395`/`:648-780`；新增 `HttpClientManager`/`LegacyApiFacade`/`SlimApiFacade`/`SlimSseStateMachine`/`TofuManager`/`RepositoryRuntime`。
- **做法**：先提 HttpClientManager（rest/command/mutation + v2 Retrofit + rebuildClients）→ TofuManager（captureServerCert/applyTofuDecision/pin）→ SlimSseStateMachine（slimSseState/lock/token/ticket）→ API facade（legacy byte-for-byte，slim 只露 slim API）；保留二参数兼容构造（测试锁定 `OpenCodeRepository(mockk(),mockk())` `:148-152`）；`configure` 降至 20–30 行。
- **验收**：legacy REST wire shape 不变；slim endpoints 仍走 /slimapi/；host switch 后旧 token 不能提交；TOFU/mTLS 测试绿。

### T4 — 拆 UI 大 Composable
- **目标**：分离布局/chrome/滚动/渲染，减少无关 recomposition。
- **改**：`ChatScaffold.kt:114`/`:247-281`、`ChatMessageContent.kt:82`/`:185`/`:651-722`。
- **做法**：`ChatScaffold=ChatChrome+ChatDrawerHost+ChatOverlayHost+ChatSessionPager+ChatMessageList`；`ChatMessageList=ChatMessageProjection+ChatScrollState+ChatTabVisibilityDetector+ChatNavFab+ChatRenderBlockBuilder`；预计算 projection；精确化 `messages` remember key；scroll LRU 统一 `ScrollLruCache`；overlay 遵 `docs/ui-style-spec.md` 三层规则。
- **验收**：Composable 单函数 200–400 行；`:185` 高频变更不致 chrome 重组；Compose UI test 覆盖 legacy tool fold / slim omitted expand / streaming；screenshot/semantic 基线通过。

### T5 — 清理
- **目标**：结构稳定后删死代码、收敛配置、修 recomposition。
- **改**：`AppCore.kt:41-42`（TUNNEL_SUCCESS_TOAST）、`ChatMessageContent.kt:364`（pendingRestoreSession）、`SSC.kt:179`（isSlimMode 默认参数，调用方迁移后）、`SettingsManager.kt:932/952`（键墓碑）、`ChatScaffold.kt:281`/`ChatMessageContent.kt:185/:651-722`（remember）；`300/8_000/30_000/4/64`→`Timings`/`Limits`。
- **验收**：死代码零 caller 证明后删；魔法值单一命名源；recomposition trace 无回退；`check.sh --full` 绿。

### T6 — 构造参数与生命周期瘦身
- **目标**：减上帝对象，不引入新上帝 Context。
- **改**：`AppCore.kt:80-193`、`ConnectionCoordinator`/`SSC.kt:131-198` 构造、`SessionStreamingService` lateinit 枢纽、ControllerModule/Hilt。
- **做法**：按域定义 `SessionRuntime`/`ConnectionRuntime`/`HostRuntime`（data class）；先提接口不移实现；controller 只依赖本域接口；service 经 `SessionSyncPort`/`EffectPort` 反转依赖，不直依赖 UI store；保留 `CoroutineStart.UNDISPATCHED` 初始化语义（`AppCore.kt:306-370`）；每次减 2–4 参数。
- **验收**：AppCore 构造 ≤8–10 高层依赖；service 不直依赖 SharedStateStore UI 实现；Hilt 无重复 singleton；host switch/reconnect/cleanup 测试绿。
- **风险最高**：按 controller 单独提交；无功能阻塞可独立 workflow。

---

## 五、Agent 分配与门控

### 5.1 Agent 分配表（用户指定）

| Task | 测试用例 | 生产代码实现 | 终验执行 | 合并门控 |
|---|---|---|---|---|
| **T0** | **fixer** | **fixer** | `_priv-verifier` | **rev-gpt ≥ 9.5** |
| **T-R1** | **fixer** | **fixer** | `_priv-verifier` | **rev-gpt ≥ 9.5** |
| **T1** | **fixer** | **fixer** | `_priv-verifier` | **rev-gpt ≥ 9.5** |
| **T2** | **fixer** | **fixer-zlm** | `_priv-verifier` | **rev-gpt ≥ 9.5** |
| **T3** | **fixer** | **fixer-zlm** | `_priv-verifier` | **rev-gpt ≥ 9.5** |
| **T4** | **fixer** | **fixer-zlm** | `_priv-verifier` | **rev-gpt ≥ 9.5** |
| **T5** | **fixer** | **fixer-zlm** | `_priv-verifier` | **rev-gpt ≥ 9.5** |
| **T6** | **fixer** | **fixer** | `_priv-verifier` | **rev-gpt ≥ 9.5** |

### 5.2 三 lane 独立编排

| Lane | 职责 | 禁止 |
|---|---|---|
| **Test lane（fixer）** | 编写 characterization/regression 测试，固定 fixture/输入/期望 state/effect | 改业务代码；测试提交后冻结 |
| **Impl lane（fixer/fixer-zlm）** | 只改生产代码 | 改测试文件；以「让测试变绿」为由改测试 |
| **Verify lane（`_priv-verifier`）** | read-only source/test；跑 `check.sh`+定向+破坏实验；只产日志+artifact | 提交源码、改测试 |

**门控链**：fixer 测试冻结 → 实现 agent 独立实现 → reviewer（查测试未被动 + diff 对 spec）→ `_priv-verifier`（校验**测试文件 hash 执行前后不变**、跑测试）→ **rev-gpt ≥ 9.5** → 合并。未达 9.5 回到实现 lane 修正，**不得通过改测试达标**。

### 5.3 Characterization vs Regression

- **Characterization（T0）**：记录当前行为（固定 SSE 序列/输入/state → 记录输出 state/effect/request 顺序）。
- **Regression（T1–T6）**：锁设计契约（legacy 不受 slim 影响 / action 跨 slice 原子性 / handler 拆分前后 action/effect 序列一致 / UI scroll·key·recomposition 保持）。
- **避免冻结 bug 的判断原则**：① 文档/产品明确要求→保留；② 历史 bug 修复注释明确→转 regression；③ 与安全/数据完整性/host 隔离冲突→不冻结，先写预期失败测试；④ 无法判断→标 `needs-product-decision`，不进 golden。
  - 例：cold-start `byDirectory MERGE` 应保留（`SSC.kt:3411-3429`）；legacy 流式共享路径应保留（`:1258-1465`）；slim 在 legacy 调 snapshot writer 属 bug，不作 golden。

### 5.4 legacy/slim 双轨测试矩阵

| 能力 | legacy | slim |
|---|---|---|
| SSE path | `/global/event` | `/slimapi/events` |
| headers | 无 slim headers | 有 slim version/capability |
| message.updated | patch/insert（SSE） | 不经 slim SSE（digest 驱动 REST） |
| part.delta | 写 streaming overlay（SSE） | slim SSE 丢弃（无 token 流式） |
| session status | legacy fold | slim digest fold |
| reconcile | 不触发 slim reconcile | digest/resync reconcile |
| cold-start | 不调 slim snapshot | merge snapshot |
| session list | REST authoritative | by-directory merge |
| tool fold | `expandedParts` | 不被 slim expand 污染 |
| omitted part | 不出现 slim affordance | `partExpandStates` |
| stale token | 不适用 | no-op 不提交 |
| host switch | legacy runtime reset | slim token invalidation |

### 5.5 check.sh 接入

- **默认** `./scripts/check.sh`：编译 + JVM unit（含 T0–T3 核心 reducer/handler/wire-shape）。
- **`--lint`**：+ lintDebug（T2–T6）。
- **`--full`**：+ koverVerify/koverHtmlReport（`:29-35`）。`connectedDebugAndroidTest` **不进默认链**（依赖模拟器+.env，禁占他人模拟器）。

---

## 六、ocmar 执行编排

### 6.1 Task 序列

```text
T0 (a legacy golden / b slim-transport wire / c verifier+rev-gpt≥9.5)
T1 (a ownership design / b AppAction migration / c Task2 P1 assertion / d gate)
T2 (a shared handler / b legacy handler / c slim handler / d reconcile+delta / e gate) ┐
T3 (a HttpClient / b Tofu / c SlimSseState / d ApiFacade / e gate)                     ├─ 并行（禁同改 AppAction/SharedStateStore/AppCore）
T4 (a projection / b chrome / c scroll / d Compose gate)                                ┘
T5 (a dead code / b constants / c remember / d gate)
T6 (a runtime interfaces / b AppCore / c service inversion / d release gate)
```

### 6.2 workflow 划分

- **独立 workflow**：T0（安全网）/ T1（状态所有权）/ T6（构造+生命周期，最高风险，独立 release gate）/ T4（Compose 独立 UI 验证）。
- **可合并**：T2 + Task 2 P1（同 SSE/隔离 workflow）；T3 的 HTTP/TOFU/slim state（同 repository workflow 分 task）；T5 作 T2–T4 收尾（不与行为重构混 commit）。

---

## 七、需 slimapi 项目配合改造的内容

> **独立交接文档**：`docs/ocmar/reports/2026-07-22-slimapi-cooperation-request.md`（已抽出，含 slimapi 回复模板，供并入 slimapi 计划）。本节为方案内的依赖摘要。

本方案主体为 ocdroid 客户端内部重构，但对 oc-slimapi（sibling repo）有以下**配合/约束要求**：

### 7.1 契约冻结期（强约束）
- **wire 保持 `X-Slimapi-Version: 1` 不 bump**：T0–T6 全程（预计多轮）不得引入破坏性 wire 变更；仅允许加性演进。客户端双域隔离 + 特征化测试依赖契约稳定。
- **事件契约稳定**：`session.digest`/reconcile/cold-start snapshot 的字段与语义不得中途变更；如需变更须先与客户端协商并同步 golden。

### 7.2 事件归属澄清（须 slimapi 书面确认）
- **确认三类事件归属**：Shared（message.updated/part.created/part.updated/part.delta）/ Slim-only（session.digest/slim error/aggregation）/ Legacy 不经 slimapi。
- **确认 slimapi 不会向 legacy 路由的 SSE 流注入 slim digest 帧**（反之亦然）——客户端 `SseEventRouter` 的模式选择假设事件类型与模式一致。

### 7.3 messageID 透传（权威核验点，悬而未决）
- 客户端 `(updatedAt, messageID)` tie-break 直接对 messageID 做字典序 String 比较，**依赖 messageID 字典序严格单调**（= opencode 原始发射顺序）。
- **须 slimapi 确认对 messageID 纯透传**（含聚合 fan-out 不重映射/不重生 ID）。若 slimapi 改写，T1 tie-break 语义破裂。

### 7.4 特征化测试 fixtures（须 slimapi 提供/维护）
- T0 legacy/slim golden 测试复用 **S-D G-F1 fixtures**（equal-ts/跨页/limit 截断/重连重放/对抗循环）——slimapi 侧 v0.3.1 已建，须持续维护并与客户端共享。
- 如客户端 T0 发现 fixture 缺口（如某事件序列未覆盖），须 slimapi 补充。

### 7.5 实测省流数据（须 slimapi 保持端点）
- 客户端「实测省流」消费 slimapi `/slimapi/metrics` 的 `batch` ledger + 字节比 median/P90（S-C，v0.3.1 已建）。
- **须 slimapi 保持该端点与聚合语义**；如调整 schema 须同步客户端 TrafficLedger 消费。

### 7.6 Opt-A 能力头与响应矩阵（须保持）
- 客户端双域依赖 `X-Slimapi-Capabilities: mid-partial-envelope=1` opt-in + B2 六行响应矩阵（v0.3.1 已部署）。
- **须保持能力头 grammar + 非 opt-in legacy 等价**（R4-B2-OLD-SEMANTICS）；feature flag 调整/回滚须告知客户端。

### 7.7 已记录待确认项（前序 handoff，须闭环）
来自 `docs/ocmar/reports/2026-07-20-slimapi-v022-release-test-report.md` §6，本重构期间一并闭环：
1. `Partial + scope.directories==0` 是否可能（客户端已做安全超集 gate，确认是否多余）。
2. `/sessions` 错误体消费深度（客户端当前最小深度=失败+log code，确认是否需按 code 差异化）。

> **配合节奏**：以上多为「确认/保持/提供 fixture」，无强制 slimapi 代码改造；唯一硬约束是 §7.1 契约冻结 + §7.3 messageID 透传确认。建议双方约定一个 refactor 窗口期，期间 slimapi 仅做加性/修复、不做契约变更。

### 7.8 协同结果（2026-07-22 slimapi 回复后对齐）

**C1–C7 确认状态**：C1/C2/C4/C5/C6 ✅ 同意；C7.1 闭环（Partial+N==0 结构不可能，retain-prior gate 对 Partial 多余但无害、对 Success+N==0 正确）；C7.2 闭环（最小错误深度=契约正确）。**C3 经裁决修正**（见下）。

**C3 裁决（唯一认知差，已解决）**：slimapi 正确——两 SSE 流事件集**不相交**。`message.updated`/`message.part.*` 是 **legacy-only wire**（slim SSE 丢弃，slimapi 仅用 message.updated 内部驱动 digest、不下发）。我方原「Shared=两流都发」分类错误，实为「Shared=纯转换函数库可复用」。**已修正 §1.3/§2.2/§5.4**。T2 Router 不得期望 slim 流含 message.part.*；slim 域内容更新 = digest→REST skeleton/full（无 token 流式，省流设计）。此修正**推翻前序渲染审计「slim SSE 不丢 text.delta」的误判**——slim 模式 streamingPartTexts 不由 SSE 喂充，T1 所有权须按模式区分 writer（legacy=SSE 喂、slim=REST 喂）。

**rev-4 / rev-5 处置（slimapi 侧提前开工的测试）**：
- **rev-4（Batch1 错误边界，315 行 / 8 xfail / EXIT=0）→ 保留为 Batch1 测试 DRAFT**：xfail=对未来未实现行为的 test-first，不断言现状、加性、不破现有，方向与「测试即契约」方法论一致。**但尚未冻结为契约**——Batch1 正式启动时须经 fixer 复核 + rev-gpt ≥9.5 门控后才升为 frozen contract。R7 适配以此为输入。
- **rev-5（Batch2，仍在跑）→ 完成后同标准评估**：不中途杀；产出后按 rev-4 同样处置（DRAFT→门控→冻结）。若产出 partial/噪声则 restore。

**slimapi 反向清单 R1–R7（ocdroid 须做的客户端改造）→ 并入本方案 task 树**：

| R# | 项 | 优先级 | ocdroid 落点（task） | 时点 |
|---|---|---|---|---|
| R1 | status 降频：停 4s 轮询 `/session/status`+`/api/session/active`；改 `/slimapi/sessions/status` cold-start + digest `status` 接力；断连降级 10–30s | 🔴 | **新增 T-R1（独立，立即）**——slimapi 零改动，纯客户端 | 立即（与 T0 并行） |
| R2 | 消费 `childrenIDs`/`childrenComplete`；缺失调 `/slimapi/sessions/{sid}/children`；停透传 `/session/{sid}/children` | 🟠 | T3（Repository 拆分时接 children facade） | slimapi Batch3 上线 |
| R3 | childrenVersion 同 server generation 内比较；`server.connected` 后 cold-start 清基线；禁跨进程比大小；Y 兜底 | 🟠 | T1（状态所有权：version 比较归一 reducer） | slimapi Batch4 上线 |
| R4 | 双轨迁移：`/question`→`/slimapi/questions`；reply 走 routeToken；`/global/health`→`/slimapi/health`；`/session`→`/slimapi/sessions` | 🟡 | T3（ApiFacade 拆分时切端点） | 随时 |
| R5 | C3 澄清（Router 不期望 slim 流含 message.part.*） | 🟠 | T2（已纳入 C3 修正） | T0/T2 前（已解决） |
| R6 | messageID fresh 联调核验（slimapi 供测试用例锚定透传） | 🟢 | T1 启动前 gate | T1 前 |
| R7 | Batch1 错误映射适配（slimapi 修 batch status 错误体后，若 ocdroid 依赖原始 body 须按 §7 coded 适配） | 🟡 | T3（error code 处理） | slimapi Batch1 上线 |

> **新增 T-R1**（status 降频）为 🔴 独立 task，与 T0 并行启动（slimapi 零改动、纯客户端、收益直接）。其余 R2–R7 按 slimapi batch 节奏并入对应 ocdroid task。

---

## 八、最终验收标准

**架构**：legacy/slim 业务域不再靠散落 `isSlimMode()` 分流；shared handler 只处理真正共享事件；slim handler 不被 legacy route 调用；legacy runtime 不依赖 slim runtime；Repository facade 不暴露另一模式内部状态。

**状态**：四 slice 各有唯一语义 reducer；跨 slice 变化全经 AppAction；reduce 无副作用；单 action 一次 aggregate commit。

**安全与并发**：slim token 在 host reconfigure 后失效；stale reconcile 不写 slice/cache/effect；cold-start snapshot 在 legacy 调用 fail-fast；`byDirectory MERGE` 固定；legacy wire path/header 不被 slim 污染。

**测试与门控**：`check.sh` + `--full` 通过；legacy/slim 双轨矩阵全覆盖；破坏实验稳定红灯；`_priv-verifier` 校验测试文件 hash 不变；**每 Task rev-gpt ≥ 9.5**。

---

## 九、实施中须闭环的不确定项

1. `check(isSlimMode())` 是否在 slim reconfigure 瞬态误抛 → 完整 slim resync/cadence 测试验证（token/ready 时序 `OpenCodeRepository.kt:464-532`/`:574-630`）。
2. `applySlimColdStartSnapshot` 无 token overload 可被外部调用（`SSC.kt:3354-3361`）→ 确认所有调用点在 slim domain 内。
3. `partsByMessage`/`streamingPartTexts` 两阶段写入的 Compose 中间态（`SSC.kt:1362-1378`）→ T1 避免两 action 拆成可观察错误顺序。
4. `sessions` 完整语义 writer 仍分散（SessionListActions/SessionMutationActions/SSC/host-profile）→ T1 静态搜索 + 测试矩阵确认无遗漏。
5. `OpenCodeRepository` 6 处 byte-for-byte 注释 ≠ 6 处 wire-shape 测试 → 逐一核对 `:1222`/`:1337`/`:1404`/`:1470`/`:1769`/`:2022`。
6. `pendingRestoreSession`/`TUNNEL_SUCCESS_TOAST`/deprecated 参数隐藏 caller → T5 先全仓 caller 搜索。
7. AppCore effect collector 与 SSE bridge 初始化时序契约 → 保留 `CoroutineStart.UNDISPATCHED`（`AppCore.kt:306-370`）。
8. `connectedDebugAndroidTest` 的稳定 .env/模拟器/可复现 fixture → 独立 smoke workflow。
9. 历史 commit `c161ca6`/`d0842f4` bug 语义 → `git log` 复核（不影响隔离结论）。
