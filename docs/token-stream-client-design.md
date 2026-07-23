# ocdroid 客户端设计：Token 批式 SSE 集成 (v2 → v3 契约已迁出)

> 状态：**设计 v3 契约（round2 修订）已迁至权威开发计划 [`docs/token-stream-dev-plan.md`](./token-stream-dev-plan.md) §3（含可编译 Kotlin）**；本文 §5 为 v2 散文，已被 dev-plan §3 契约**取代**用于实施与门控。
> 评审史：round1 三评委 FAIL（grok 8.6 / gpt 6.2 / opus 8.6）；round2 两评委 FAIL（grok 9.1 / gpt 8.4，架构认可，残留=片段正确性+action plumbing，均附可采纳代码→已折入 dev-plan §3）。
> 对应服务端：`oc-slimapi/docs/design-token-stream.md`（v3）；handoff：`oc-slimapi/docs/ocdroid-token-stream-handoff.md`。
> 性质：**加性**（新端点 + 新帧 + health 加性字段），opt-in，**省流默认零回归**，不 bump `X-Slimapi-Version`。
> ⚠️ **D-MB-P 翻转（v0.13.2，commit `da47fe3`）**：本文 §5/reason 表所述 `token_memory_limit`→`Reconnect`（rev-bgpt Option A）**已被翻转**——现 `triggersReconnect=false`，改走 same-connection re-anchor（依赖服务端 MB-P-S1）。历史设计记录保留；现状见 [`docs/refactor-handoff.md`](./refactor-handoff.md) §2.1。

---

## v2 修订记录（round1 must-fix → 修订落点）

| 编号 | round1 缺陷（命中评委） | v2 修法 | 落点 |
|---|---|---|---|
| **M1** | `ClearDeltaBuffers` 不清动画 overlay（全部3家） | 新增独立 `ClearTokenStreamState(sid[,partId])`：清 `streamingPartTexts` + `streamOwned`；**不复用** `clearDeltaBuffers`（其 docstring 明示只清 coalesce buffer，不动 overlay） | §5.7 |
| **M2** | 无心跳 watchdog（全部3家） | token 连接复用 `SSEClient` watchdog 模式：reset-on-any-frame，timeout≈3×心跳=45s；超时→cancel→清态→`/since`→退避重连 | §5.8 |
| **M3** | streamOwned 守卫挂错 merge + /since 可丢完成态（grok/gpt/opus） | 守卫落**真实** skeleton merge `ChatState.mergeSlimMessages`(`:3105-3133`)；区分 **权威 fetch**（step-finish/resync/watchdog 触发→无条件覆+清 streamOwned）vs **skeleton fetch**（初始/cursor→`streaming` 态保留本地 part）；幂等收敛 | §5.6 |
| **M4** | `streamingPartTexts` 双写主未决（grok/opus） | **硬决定单一 owner**：slim+`features.tokenStream` 时 `TokenStreamReducer` 为 owned text part 的唯一写者；legacy `SharedConversationSseHandler` 在 slim 模式 no-op `message.part.*`（自然惰性，仅防 host-switch 双写）；filter 源 = `animatedPartTypes` | §5.4 |
| **M5** | session.deleted 未处理（全部3家） | digest session-deleted → coordinator 强关流 + `ClearTokenStreamState(sid)` + UI 移除；`/since` 404 `session_not_found` = 终态删除（不重试） | §5.8 |
| **M6** | 无 stream epoch 守卫（gpt） | 每次 open/reconnect 发 `streamEpoch`；帧打 epoch 标签，`epoch!=current` 一律 drop；OkHttp callback 取消后迟到帧不污染当前态 | §5.8 |
| **M7** | directory 冲突→400（gpt） | token 专用 OkHttpClient **排除** `DirectoryHeaderInterceptor`（它把 `X-Opencode-Directory` 头镜像进 query，与 `?directory=` 冲突→`directory_not_allowed`）；directory 只走 query 或省略；**显式加** `SlimapiVersionInterceptor`（专用 client 不自动继承） | §5.1 |
| **M8** | features 须显式 parse（gpt/opus） | `parseSlimapiHealth`(`:995-1014`，手抽 JsonObject) 显式抽 `server.features.tokenStream`；`SlimapiHealthPayload` 加 `features` 字段，默认 false→零回归 | §5.5 |
| — | 关键 should-fix | 显示层 supersede 删 key（§5.6）；占位 Part 复用既有 part.updated 注入器（§5.9）；503 `sse_token_subscriber_limit` 退避（§5.8）；routing A-list 补一行（§6）；V5/V6/V7 措辞修正（§1） | 各节 |

---

## 0. TL;DR

- **核心模型**：token 流 = **动画层**（transient）；`/since` = **真值层**（authoritative）。动画让位于真值；但 **streaming 期不让位空值**（防生成中 `text=""` 冲掉动画）。
- **四正交子系统**：传输 `TokenStreamClient` / 纯归约 `TokenStreamReducer` / 拼接策略 / 生命周期 `TokenStreamCoordinator`；wire 知识集中 `TokenStreamFrame`。
- **单一 owner 原则**（v2 硬约束）：动画缓冲 `streamingPartTexts` + `streamOwned` 标志由 token 子系统独占写（slim+tokenStream 时），legacy 控制面在 slim 下 no-op part.*；不建并行 buffer。
- **V1-V7**：V1/V2/V4 ✅；V3 ✅（nuance）；V5 ✅ 验证对、复用机制已修（M1/M3）；V6 ⚠️→✅ 已补 watchdog（M2）+ gzip 措辞修正；V7 ⚠️→✅ 已显式 parse（M8）。详见 §1。
- **B-1**：立即显示已生成部分 + 流式追加（用户选定）；**B-2**：reasoning 延后 P2（用户选定）。

---

## 1. V1-V7 核实结果（v2 修正措辞）

### V1 ✅ opencode wire properties（源码确认）
`message.part.delta` = `{sessionID,messageID,partID,field:"text",delta}` 扁平 camelCase（`processor.ts:503-509`→`session.ts:886`）；`message.part.updated` = `{sessionID,part:structuredClone(part),time:Date.now()}` **part 嵌套在 `part` key**（`session.ts:639-643`）。服务端 `on_part_updated` 读 `props.get("part")` 正确。事件名 `SessionV1.Event.Part{Delta,Updated}`（`message-v2.ts:58-59`）。上线前 live 抓包终确认（§9）。

### V2 ✅ P1 仅 text，reasoning/tool-input 延后（B-2 已定）
reasoning-delta `field:"text"` 但 `part.type=="reasoning"`；服务端 C3 静默 drop 非 text；客户端 `animatedPartTypes=setOf("text")`（P2 可加）。

### V3 ✅ abort/halt/overflow 可靠发 text-end（带 nuance）
`processor.ts:675-676` `Effect.catch(halt)`+`Effect.ensuring(cleanup())`（finally 语义）→ cleanup(`:555-560`) 对 `ctx.currentText` 设 `time.end` 并 `updatePart` 发 text-end。**nuance**：ensuring 保证 cleanup *执行尝试*，非*网络送达*（fiber 中断/进程 kill/事件丢失败仍可能）；故服务端 60s TTL 是必要 defense-in-depth（非可选），保留。

### V4 ✅ opencode-src/current = v1.18.3（`git describe` 对齐）

### V5 ✅ 验证本身正确；复用机制 v2 已修
`/since` reconciler 可吸收 resync/truncated（`mergeFullBatchIntoLocal`(`SlimapiMessageMerge.kt:139-162`) 幂等 REPLACE）——**验证结论对**。但 v1 说"复用 `ClearDeltaBuffers` 清 token 态"**错**（M1）：该原语只清 coalesce buffer（`clearAllCoalesceBuffers` docstring 明示不动 `streamingPartTexts`）。v2 新增 `ClearTokenStreamState`（§5.7）。

### V6 ✅ 要求对（v2 已补 watchdog + 修 gzip 措辞）
stunnel mTLS 对长连 SSE 透明逐字节，15s 心跳远超默认 `TIMEOUTidle=12h`。**v1 错论断**"OkHttp 透明 gzip 必碎 SSE"——流式 gzip 解压通常安全，不应绝对化。**出货 lever2（已对齐 shipped 代码，`OkHttpClientFactory.tokenStreamClient` 无 Accept-Encoding 剥离拦截器）**：token stream **gzip 默认开**——客户端走 OkHttp 默认透明 `Accept-Encoding: gzip`（自动解压），是否真压由**服务端决定**；`/events` 不 gzip = **服务端策略**（对 `text/event-stream` 不返 `Content-Encoding: gzip`），非客户端干预。**v2 补**：`readTimeout=0` **必须配心跳 watchdog**（M2/§5.8），否则半开/NAT 死亡无 `onFailure` → 重现半冻。

### V7 ✅ 结论对（v2 显式 parse）
`parseSlimapiHealth`(`:995-1014`) 是**手抽 JsonObject**（非 v1 暗示的 `@Serializable ignoreUnknownKeys`），天然容错未知键→加性安全。但**不会自动填充**新字段；v2 显式抽 `server.features.tokenStream` + DTO 加 `features`（§5.5）。

---

## 2. B-1 / B-2（用户已定）
- **B-1**：打开 busy 会话 = 立即显示已生成部分（snapshot 首帧）+ 流式追加；不做占位 loading（首帧前亚秒空窗可选极轻 spinner）。
- **B-2**：P1 仅 text 动画，reasoning 延后 P2。

---

## 3. 设计原则：模块化 / 可扩展性 / 可维护性
- **正交子系统**：传输（只管连接/帧解析）/ 纯归约（`(state,frame)→state`，无 I/O，可单测）/ 拼接策略（动画↔真值优先级，独立可测）/ 生命周期（foreground opt-in、max-1、watchdog、epoch）。
- **wire 知识集中**：`TokenStreamFrame` sealed class + parser，契约变更只动一文件。
- **帧分发表 + part-type 过滤器可配置**：P2 加 reasoning 动画 = 加 handler + 放开 `animatedPartTypes`，非改主干。
- **单一 owner（v2 硬约束）**：动画缓冲槽 `streamingPartTexts` + `streamOwned` 由 token 子系统独占（slim+tokenStream 时）；不与 legacy 共写、不建并行 buffer。
- **硬约束内建**：reducer 把每帧当"文本段" append/replace，不计数/不假定帧间隔（对任意 batch 稳健）。
- **复用既有 part.updated 占位注入器**（`SessionSyncCoordinator.kt:3135+`）保证 list key 稳定（B-1）。

---

## 4. 架构总览
```
控制面（既有，不改）：/slimapi/events → SlimSseReducer → digest/q/p/status
                                   └ digest message.updated(step-finish) ─► SlimFetchMessages[authoritative] ─► /since
动画面（新，opt-in）：/slimapi/sessions/{sid}/stream → TokenStreamClient → TokenStreamReducer → streamingPartTexts + streamOwned
                                              ↑ capability(features.tokenStream) + epoch + watchdog
拼接策略：动画(streaming) 显示 buffer；streaming 期忽略 skeleton /since；authoritative /since 或 done 后幂等覆盖→superseded
清态：ClearTokenStreamState（独立，非 clearDeltaBuffers）
```
**两条不变量**：(1) 真值优先（`/since` 幂等覆盖动画）；(2) streaming 期不让位空值（skeleton /since 不冲动画；authoritative /since 才覆盖）。

---

## 5. 组件设计

### 5.1 TokenStreamClient（传输）
- **专用 OkHttpClient**（独立连接池、`readTimeout=0`、无 cache；lever2 gzip 默认开——走 OkHttp 默认透明 `Accept-Encoding: gzip`，不主动加/剥，服务端决定是否压）。
- **拦截器链（v2 精确）**：**显式加** `SlimapiVersionInterceptor`（专用 client 不自动继承 shared chain——v1 "自动注入"论断错，M7）；**显式排除** `DirectoryHeaderInterceptor`（防头↔query 镜像冲突→400，M7）。`X-Accel-Buffering`/`Cache-Control` 服务端已设。
- directory：只走 `?directory=` query（可选），不带 `X-Opencode-Directory` 头。
- 帧解析：复用 `SSEClient` 的 **EventSource + event 解析**（注：`SSEClient` 用 OkHttp `EventSource`（`SSEClient.kt:289-290`），非手搓 splitter；可共享单元=event parser + OkHttp/watchdog 配置）。
- 输出 `(event,dataJson)`→`TokenStreamFrame.parse()`。

### 5.2 TokenStreamFrame（wire 模型，集中）
```kotlin
sealed interface TokenStreamFrame {
    data class ServerConnected(val sessionId: String)
    object ServerHeartbeat
    data class PartSnapshot(val sessionId:String, val messageId:String, val partId:String,
                            val text:String?, val done:Boolean, val truncated:Boolean)
    data class PartDelta(val sessionId:String, val messageId:String, val partId:String, val text:String)
    data class Resync(val reason:Reason, val sessionId:String?)  // reason ∈ reconnect_no_replay/subscriber_backpressure/token_memory_limit/session_idle/session_deleted + UNKNOWN fallback（§5 C-2；part_too_large 已删，超限→truncated:true）
}
// parse(event, data): unknown event → null（向前兼容）
```
事件名严格对齐 SSE `event:` 串：`message.part.snapshot`/`message.part.delta`/`resync`/`server.connected`/`server.heartbeat`。无 `id:`、无 replay、不发 `Last-Event-ID`。

### 5.3 TokenStreamReducer（纯函数状态机）
per-part（key=`partId`，见 §5.4 取舍）状态 `idle/streaming/done/superseded`：

| 当前 | 事件 | 迁移 | 动作 |
|---|---|---|---|
| any | snapshot(done=false,truncated=false) | streaming | buffer=REPLACE(text)；streamOwned=streaming |
| streaming | delta(text=d) | streaming | buffer+=d |
| streaming | snapshot(done=true,truncated=false) | done | buffer=REPLACE(终态全文)；**done 且 text=null 时保累计 buffer（C-1=A，不写 ""）** |
| streaming\|done | snapshot(truncated=true) | idle | **ClearTokenStreamState(partId)**；`TriggerSinceFetch(authoritative)` |
| any(sid) | resync(_,sid) | 该 sid 全 idle | **ClearTokenStreamState(sid)**；`TriggerSinceFetch(authoritative)`；reason∈{reconnect_no_replay,subscriber_backpressure,token_memory_limit}→`Reconnect`（rev-bgpt A：token_memory_limit 也重连——服务端 LRU 逐一 part 但保流，不重连则后续 delta 成孤儿被 drop）；session_idle/session_deleted/UNKNOWN 仅 clear+`/since` 不重连（会话终态/未知→conservative） |
| 非 streaming | delta | 不变 | drop（迟到/stale） |

part-type 过滤：part.type 从 **本地 part 元数据查**（snapshot 到达时该 part 应已由占位注入器存在于 `partsByMessage`）；`type∉animatedPartTypes` 不进状态机（对齐服务端 C3 drop）。**孤儿**（snapshot 前到 delta，本地无 part 元数据）→ drop+计数（对齐服务端 orphan 静默 drop）。

### 5.4 streamOwned 缓冲（v2 单一 owner 决定）
- **缓冲**：复用 `ChatState.streamingPartTexts`（既有 UI 读 `streamingPartTexts[part.id]`（`MessageCard.kt:469`、`ChatMessageRow.kt:154`））；**key=partId**（与既有 UI 一致，gpt should-fix #2）。
- **streamOwned 标志**：新 `ChatState.streamOwned: Map<String, StreamOwnedState>`（key=partId）。**理由**：max-1 前台 stream → 仅当前 session 的 part 在作用域；跨 session 由 coordinator 切换时 wholesale 清（§5.8），partId 冲突非顾虑。
- **单一 owner（M4 硬决定）**：`slim && features.tokenStream` 时，`TokenStreamReducer` 是 owned text part 的**唯一写者**；`SharedConversationSseHandler` 在 slim 模式 **no-op** `message.part.*`（slimapi 本就 drop part.*，legacy 路径天然惰性；仅防 host-switch/dual-mode 双写）。filter 源单一：`animatedPartTypes`（P1=`setOf("text")`），替换既有 `isStreamablePartType` 放行 `"reasoning"` 的不一致（`ViewModelSupport.kt:398`）。

### 5.5 capability 门控（M8 显式 parse）
```kotlin
// parseSlimapiHealth 内（手抽 JsonObject），server 子树：
val features = (server["features"] as? JsonObject)
val tokenStream = (features?.get("tokenStream") as? JsonPrimitive)?.booleanOrNull == true
// DTO：
data class SlimapiFeatures(val tokenStream: Boolean = false)
data class SlimapiHealthPayload(..., val features: SlimapiFeatures = SlimapiFeatures())  // 默认 false→零回归
```
门控：仅 `features.tokenStream==true` 连流；缺/404/405/false/版本偏移→零回归（不连）。endpoint 404/405（版本偏移：health 说 true 但端点不存在）→ 降级 + 本会话不再重试（缓存"不支持"）。

### 5.6 拼接协议（v2 守卫落真实 merge + 区分 fetch 类型）
**守卫落点（M3）**：`ChatState.mergeSlimMessages`(`:3105-3133`)，当前 `:3124-3126` 无条件 `partsByMessage + (id to item.parts)`。改为按 **fetch 类型** + per-part streamOwned 守卫：
```kotlin
// 新增参数 authoritative: Boolean（由调用方标：step-finish/resync/watchdog=true；初始/cursor=skeleton）
if (item.parts.isNotEmpty()) {
    val local = partsByMessage[updated.id] ?: emptyList()
    val merged = item.parts.map { fetched ->
        val st = streamOwned[fetched.id]
        if (!authoritative && st == StreamOwnedState.STREAMING) {
            // skeleton 且动画中：保本地 part（防 text="" 冲动画）
            local.firstOrNull { it.id == fetched.id } ?: fetched
        } else fetched  // authoritative 或 idle/done：真值覆盖
    }
    val fetchedIds = item.parts.map { it.id }.toSet()
    val preservedLocal = local.filter { it.id !in fetchedIds }
    partsByMessage = partsByMessage + (updated.id to (merged + preservedLocal))
    if (authoritative) streamOwned = streamOwned.filterKeys { it !in fetchedIds }  // 完成态→superseded（删 flag）
}
```
**fetch 类型区分（解 gpt M3-part2）**：
- **skeleton**（初始 `loadMessages`、cursor drain）：`authoritative=false`→streaming 期保留本地。
- **authoritative**（digest `message.updated` step-finish、resync、truncated、watchdog 超时触发的 catch-up）：`authoritative=true`→无条件覆盖 + 清 streamOwned。
→ 即便 token 连接丢 `done:true`，digest step-finish 必到（控制面独立），其 authoritative /since 终会覆盖，**完成态不会永久丢失**。**C-1=A（lever1）**：done 仅作 marker，done 帧若 `text=null`（status-only 终态）**保累计 buffer**（不写 `""`）——真值终态文本由 authoritative `/since` 提供；这样 `streamingPartTexts[partId]` 不会被空串盖住真值（`streamingTextOverride ?: part.text` 的 override key 非 null 即生效）。

**显示层 key 生命周期（grok #4）**：UI 在 key 存在时优先 `streamingPartTexts[part.id]`；故 `superseded`/`idle` 后（authoritative merge 成功）**必须删** `streamingPartTexts[partId]`（否则空串/陈旧 key 盖真值）。`ClearTokenStreamState` 与 authoritative merge 都负责删。

### 5.7 清态（M1 独立原语，不复用 clearDeltaBuffers）
```kotlin
// AppAction
data class ClearTokenStreamState(val sessionId: String, val partId: String? = null) : AppAction()
// ChatState pure reducer
internal fun ChatState.clearTokenStreamState(sid: String, partId: String?): Pair<ChatState, List<SseSideEffect>> {
    val (spt, so) = if (partId != null) streamingPartTexts - partId to streamOwned - partId
                   else streamingPartTexts.filterKeys { it != partId } to streamOwned.filterKeys { it != partId }
    // 注：partId-keyed，sid 级清靠 coordinator 先枚举该 sid 的 partId（coordinator 持活跃 sid→partId 集）
    return copy(streamingPartTexts = spt, streamOwned = so) to emptyList()
}
```
路由：truncated / resync / session.deleted / CancelSse(token) / 换 session / watchdog 超时 → `ClearTokenStreamState`。**绝不**过载 `clearDeltaBuffers`（其契约 buffer-only，`clearAllCoalesceBuffers` docstring 明示不动 overlay）。

### 5.8 连接生命周期（v2 补 watchdog/epoch/deleted/503/debounce）
- **开/关**：foreground + 查看某 session + `features.tokenStream`→开；切后台/换 session/capability 失效→关。max-1 前台 stream。换 session=关旧开新 + 短 debounce（防快速切换 storm cap-8 准入）。
- **心跳 watchdog（M2）**：复用 `SSEClient` watchdog 模式（`SSEClient.kt:58-66,299-314`），reset-on-any-frame（含 heartbeat），timeout≈45s（3×15s）；超时→cancel→`ClearTokenStreamState(sid)`→authoritative `/since`→退避重连。
- **stream epoch（M6）**：每次 open/reconnect 发新 `streamEpoch`；coordinator 给每帧打 `epoch`；`epoch != currentEpoch[sid]` 的帧（含 OkHttp 取消后迟到的排队帧）一律 drop。
- **session.deleted（M5）**：digest session-deleted（该 sid）→ coordinator 强关流 + `ClearTokenStreamState(sid)` + UI 移除会话行；`/since` 404 `session_not_found`（routing §3.4）= 终态删除，不重试。
- **503 `sse_token_subscriber_limit`（v3 §6）**：按 `Retry-After` 退避；连续 N 次失败→本会话停试 token stream（降级为现状），等下次 health 重检。
- 与 `/events` 控制面并存独立；`ControllerEffect.CancelSse` 扩展同时关 token 流。

### 5.9 busy-open UX（B-1）
`ChatViewModel.loadMessages`(`:106`)：①`/messages` skeleton 出骨架（进行中 text part `text=""`）；②`features.tokenStream`→`TokenStreamCoordinator.open(sid)`；③首帧 `snapshot(done=false)` REPLACE 显示（**立即显示已生成部分**）；④delta 追加；⑤step-finish authoritative `/since` 覆盖。占位 Part 由既有 `message.part.updated` 注入器（`:3135+`）保证首帧前 Part 行已存在→list key 稳定（grok #4）。无 capability→零回归。

---

## 6. 触点 / 落点（v2 修正）
| 子系统 | 落点 | v2 注 |
|---|---|---|
| 传输 | 新 `data/api/TokenStreamClient.kt` | 专用 OkHttpClient；显式加 VersionInterceptor、排除 DirectoryHeaderInterceptor（M7）；复用 EventSource+watchdog 配置 |
| wire 模型 | 新 `data/model/TokenStreamFrame.kt` | 事件名严格对齐 |
| reducer | 新 `data/repository/TokenStreamReducer.kt`（纯） | 单测 |
| 守卫（M3） | `SessionSyncCoordinator.kt:3105-3133` `ChatState.mergeSlimMessages` 加 `authoritative` 参 + per-part 守卫 | **真实 merge 站点**（非 mergeFullBatchIntoLocal） |
| 清态（M1） | 新 `AppAction.ClearTokenStreamState` + `ChatState.clearTokenStreamState` | 独立原语 |
| capability（M8） | `OpenCodeRepository.parseSlimapiHealth:995` + `ServerCompatProfile.kt:199 SlimapiHealthPayload` | 显式抽 features |
| 单一 owner（M4） | `SharedConversationSseHandler` slim no-op part.*；`animatedPartTypes` 替换 `isStreamablePartType`(`ViewModelSupport.kt:398`) | 防双写 |
| 显示 key（grok#4） | `MessageCard.kt:469`/`ChatMessageRow.kt:154` supersede 后删 key | 防空串盖真值 |
| 生命周期 | `ui/controller/ConnectionCoordinator.kt` + 新 `TokenStreamCoordinator` | watchdog+epoch+deleted+503+debounce |
| routing 文档 | `docs/slim-mode-api-routing.md` A 桶补 `/slimapi/sessions/{sid}/stream` 一行 | 可维护性 |

---

## 7. 与服务端 v3 契约对齐
帧集/终态顺序不变式/`/since` 真值凌驾/无 id 无 replay/truncated→/since/`features.tokenStream` 加性/批式参数服务端调/`不 bump X-Slimapi-Version`——全部 ✅。v2 新增对齐：503 `sse_token_subscriber_limit` 退避（v3 §6）、session.deleted 退役（v3 §5.3）、heartbeat 15s→watchdog（v3 §5.5）。

---

## 8. 测试策略
**纯 reducer 单测**：snapshot/delta/done/truncated/resync 迁移；状态守卫（done 后 delta drop）；**authoritative vs skeleton /since**（streaming 期 skeleton 保留、authoritative 覆盖+清 flag）；**token 丢 done:true 但 digest step-finish authoritative /since 仍覆盖**（gpt M3-part2）；part-type 过滤；孤儿 drop；任意 batch（CJK/大段/多帧）无缺口无重复；epoch 拒 stale 帧；capability 缺/404/405/false 零回归。
**集成（模拟器，`./scripts/emulator.sh` 先 status）**：busy-open 实时流入；watchdog 半开恢复；session.deleted 清理；rapid switch 不 storm。门禁 `./scripts/check.sh`。

---

## 9. 待确认 / 迭代点
1. **live 抓包终确认 V1**（v3 §13）：上线前实抓 `/global/event` 复核键大小写（源码已确认，live 双保险）。
2. ~~P2 gzip 联调~~（V6）→ **已 ship（lever2）**：token stream gzip 默认开（OkHttp 透明 `Accept-Encoding: gzip` 自动解压，服务端决定），不再"建议 SSE 路径不开"；`/events` 不 gzip 属服务端策略。
3. **partId 全局唯一性**：v2 取 partId-keyed（max-1 前台 stream + 切换 wholesale 清）。若实测跨 message partId 冲突→升级为 PartKey=(sid,msgId,partId) + UI 适配（设计预留，非阻塞）。
4. 首帧前空窗 spinner（B-1 微调）。

---

## 10. 结论
v2 修订 round1 全部 M1-M8 + 关键 should-fix，集成边界契约落到真实 file:line + 签名/伪码（impl-ready）。待 round2 联合门控 9.5（3 评委，新规则：不满须附可采纳方案 + Kotlin 代码）。
