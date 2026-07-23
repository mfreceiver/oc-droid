# ocdroid × oc-slimapi · Token 批式 SSE 集成 — 开发计划与报告

> **单一权威计划+报告文档**。每阶段更新 §8 状态日志。
> 技术散文见 [`docs/token-stream-client-design.md`](./token-stream-client-design.md)（§5 为 v2，**已被本计划 §3 v3 契约取代**用于实施与门控）。
> 门控模型：**每阶段单评委 9.5**（rev-grok → 不可用 rev-bgpt → 仍不可用 rev-gpt）；不满须附可采纳方案+Kotlin 代码。

---

## 0. 概述 / 角色
- **ocdroid 侧（本计划）**：客户端 token-stream 集成（消费 `GET /slimapi/sessions/{sid}/stream`）。
- **oc-slimapi 侧**：服务端 v3（已并入 3 评委）。
- **协调**：双方同形式（计划+阶段批次+每阶段 9.5 门控+报告日志），`session_send` 互通进度；契约分歧双边对齐。

## 1. 评审史与共识
| 轮次 | 评委分数 | 结论 |
|---|---|---|
| round1 | grok 8.6 / gpt 6.2 / opus 8.6 | 全 FAIL；架构认可，集成边界共识硬伤（错 merge 站点、误用 ClearDeltaBuffers、无 watchdog/epoch/deleted）→ v2 |
| round2 | grok 9.1 / gpt 8.4 / opus 8.7 | 全 FAIL；**架构已认可**，残留=**片段正确性 + action plumbing + 2 个 scope/一致性**，三家均附可采纳代码 → 折入 §3 v3 契约 |

**三家一致 must-fix**：(a) §5.7 sid 级清态 no-op bug（我的伪码 bug）；(b) `authoritative` 须贯穿 `SlimMessagesMerged` action 且 authoritative merge 须同时清 `streamingPartTexts`+`streamOwned`；(c) `features.tokenStream` JSON 路径未定→dual-read。
**opus 独有**：(d) `loadMessages`→`MessagesMerged` 路径不经过 `mergeSlimMessages`（MF-2 事实错误）；(e) 纯 reducer 须用 effect-list 模式（SF-2）；(f) cold-start authoritative 须 caller 区分；(g) partId 碰撞边界；(h) placeholder 时序为声明式 degraded path。

## 2. 关键问题裁定（我方自决，不再征询；盼 slimapi 对齐）
| # | 问题 | 裁定 |
|---|---|---|
| Q1 | `features.tokenStream` JSON 路径 | 客户端 **dual-read** `root["features"] ?? server["features"]` 过渡；**slimapi 已确认冻结 root**（top-level `features.tokenStream`，与 sidecar/server/schema 并列），dual-read 仅过渡 |
| Q2 | partId 键与清态 | coordinator 持 `activeTokenPartIds: Map<sid,Set<partId>>`；`ClearTokenStreamState(partIds:Set<String>)` 恒传明确集合（opus MF-1，无猜逻辑）；`streamingPartTexts`/`streamOwned` 保持 partId-keyed（UI 兼容 `MessageCard.kt:469`） |
| Q3 | cold-start authoritative | `applySlimColdStartSnapshot` 默认 `authoritative=false`（skeleton）；resync/watchdog 触发的 cold-start=true（caller 显式区分） |
| Q4 | partId 碰撞 | max-1 前台 stream + 按 sid 注册集合清 mitigate；`ClearTokenStreamState` 文档标注边界（仅清该 sid 注册的 partId）；实测碰撞→升 PartKey |
| Q5 | placeholder 时序 | 不保证控制面 `part.updated` 先于 token 首帧；首帧 snapshot 本地无 part→drop 为孤儿→降级 `/since` reconcile（**声明为 degraded path**，可容忍） |
| Q6 | P2 gzip | SSE 路径 P1 **不 gzip**；P2 门控后须实测 OkHttp 流式解压不碎 SSE |
| Q7 | busy-open UX | B-1 立即显示已生成部分+流式追加；无额外 spinner（既有 loading affordance 足够） |
| Q8 | 纯 reducer 语义 | 采 effect-list 模式 `reduce(): Pair<State, List<TokenStreamCoordinatorEffect>>`（opus SF-2）；coordinator 消费 effect 派发 AppAction |

## 3. v3 权威契约（folded Kotlin — 门控依据）

### 3.1 状态模型
```kotlin
enum class StreamOwnedState { STREAMING, DONE }
data class SlimapiFeatures(val tokenStream: Boolean = false)
// ChatState 新增字段（partId-keyed，与既有 streamingPartTexts 一致）：
//   streamOwned: Map<String, StreamOwnedState>
```

### 3.2 M8 — health features 双读（grok MF-3）
```kotlin
// ServerCompatProfile.kt
data class SlimapiFeatures(val tokenStream: Boolean = false)
data class SlimapiHealthPayload(
    val sidecarOk: Boolean?, val schemaDegraded: Boolean?,
    val serverApiVersion: Int?, val acceptedClientVersions: Pair<Int, Int>?,
    val features: SlimapiFeatures = SlimapiFeatures(),  // 加性，默认 false→零回归
)
// OpenCodeRepository.parseSlimapiHealth (:995) — 优先 root features，回退 server.features；布尔容忍 content=="true"
val featuresObj = root["features"]?.safeObject() ?: server?.get("features")?.safeObject()
val tokenStream = featuresObj?.get("tokenStream")?.safePrimitive()?.let { p ->
    p.booleanOrNull == true || p.content.equals("true", ignoreCase = true)
} == true
```

### 3.3 M1 — 清态原语（coordinator 注册集合，无猜逻辑；opus MF-1）
```kotlin
// AppAction.kt
data class ClearTokenStreamState(val partIds: Set<String>) : AppAction()  // 恒传明确集合
// ChatState pure reducer (SessionSyncCoordinator.kt 新增)
internal fun ChatState.clearTokenStreamState(partIds: Set<String>): ChatState {
    if (partIds.isEmpty()) return this
    return copy(streamingPartTexts = streamingPartTexts - partIds,
                streamOwned = streamOwned - partIds)
}
// AppAction reducer 分支
is AppAction.ClearTokenStreamState -> state.copy(chat = state.chat.clearTokenStreamState(action.partIds))
// TokenStreamCoordinator — 持活跃 partId 注册
private val activePartIds = mutableMapOf<String, MutableSet<String>>()  // sid → partIds
fun onPartOwned(sid: String, partId: String) { activePartIds.getOrPut(sid){ mutableSetOf() }.add(partId) }
fun clearSession(sid: String) { val ids = activePartIds.remove(sid) ?: return
    if (ids.isNotEmpty()) store.dispatch(AppAction.ClearTokenStreamState(ids)) }
fun clearPart(sid: String, partId: String) { activePartIds[sid]?.remove(partId)
    store.dispatch(AppAction.ClearTokenStreamState(setOf(partId))) }
```

### 3.4 M3 — merge guard + authoritative 贯穿（正确范围 reconcile/cold-start；opus MF-2）
> **范围澄清（MF-2）**：`mergeSlimMessages` 仅被 reconcile(`SessionSyncCoordinator.kt:1992`) 与 cold-start(`:2665`) 调用；**初始 `loadMessages` skeleton 走 `AppAction.MessagesMerged`(`MessageActions.kt:354`)**，另有独立 `streamingFinalized` guard(`MessageActions.kt:310`)——两层保护须在设计区分，不合并声称。
```kotlin
// AppAction.kt — 加 authoritative 参
data class SlimMessagesMerged(val items: List<MessageWithParts>, val authoritative: Boolean = false) : AppAction()
is AppAction.SlimMessagesMerged -> state.copy(chat = state.chat.mergeSlimMessages(action.items, action.authoritative))
// SessionSyncCoordinator.kt — mergeSlimMessages(authoritative)；authoritative 时清两 map
internal fun ChatState.mergeSlimMessages(items: List<MessageWithParts>, authoritative: Boolean = false): ChatState {
    if (items.isEmpty()) return this
    val patchedIds = mutableSetOf<String>(); val patched = mutableListOf<Message>(); val absent = mutableListOf<Message>()
    var partsByMessage = this.partsByMessage; var newOwned = this.streamOwned
    var newSpt = this.streamingPartTexts; val cleared = mutableSetOf<String>()
    for (item in items) {
        val updated = item.info; if (updated.id.isEmpty()) continue
        if (messages.any { it.id == updated.id }) { patchedIds.add(updated.id); patched.add(updated) } else absent.add(updated)
        if (item.parts.isNotEmpty()) {
            val local = partsByMessage[updated.id] ?: emptyList()
            val fetchedIds = item.parts.map { it.id }.toSet()
            val merged = item.parts.map { f ->
                if (!authoritative && newOwned[f.id] == StreamOwnedState.STREAMING)
                    local.firstOrNull { it.id == f.id } ?: f   // skeleton+streaming：保本地 part
                else f
            }
            val preservedLocal = local.filter { lp -> lp.id !in fetchedIds && newOwned[lp.id] == StreamOwnedState.STREAMING }
            partsByMessage = partsByMessage + (updated.id to (merged + preservedLocal))
            if (authoritative) cleared += fetchedIds.filter { it in newOwned }   // 完成态→superseded
        }
    }
    if (cleared.isNotEmpty()) { newOwned = newOwned - cleared; newSpt = newSpt - cleared }
    return copy(messages = (messages.filterNot { it.id in patchedIds } + patched + absent).chronological(),
                partsByMessage = partsByMessage, streamOwned = newOwned, streamingPartTexts = newSpt)
}
// caller 打标：reconcile step-finish/resync/watchdog → authoritative=true；cold-start skeleton → false（resync 触发的 cold-start=true，Q3）
```
> **注**：`MessagesMerged`(`MessageActions.kt:310`) 的 `newStreamingTexts` 未来须认知 `streamOwned`（现以 `streamingFinalized` 代理），Stage B 一并对齐（grok S3）。

### 3.5 M4 — single-owner guard（opus SF-1，精确于 blanket slimMode）
```kotlin
// SharedConversationSseHandler.kt — 两个 part handler 顶部 guard
private fun handleMessagePartUpdated(e: SSEEvent) { if (host.slices.chat.value.hasActiveTokenStreamOwner()) return; /* existing */ }
private fun handleMessagePartDelta(e: SSEEvent)  { if (host.slices.chat.value.hasActiveTokenStreamOwner()) return; /* existing */ }
// ChatState pure ext
internal fun ChatState.hasActiveTokenStreamOwner(): Boolean = streamOwned.values.any { it == StreamOwnedState.STREAMING }
// token 路径过滤器（不替换 legacy isStreamablePartType，grok S1）
object TokenStreamPolicy { val animatedPartTypes = setOf("text") /* P2 +reasoning */; fun isAnimated(t: String?) = t != null && t in animatedPartTypes }
```

### 3.6 M7/S2 — token 专用 OkHttpClient（grok S2）
```kotlin
// OkHttpClientFactory.kt — SSL+Version+Auth，排除 Directory，无 cache，readTimeout=0
fun tokenStreamClient(hostPort: String?): OkHttpClient = OkHttpClient.Builder()
    .apply { applySsl(sslConfigFactory.sslConfigFor(hostPort)) }
    .addInterceptor(slimapiVersionInterceptor)   // X-Slimapi-Version: 1（专用 client 显式加）
    .addInterceptor(authInterceptor)             // 保留 basic-auth
    // 不加 directoryHeaderInterceptor — directory 只走 ?directory= query（M7 防 400）
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true).build()
```

### 3.7 M2 watchdog + M6 epoch
```kotlin
private const val TOKEN_HEARTBEAT_MS = 15_000L
private const val TOKEN_WATCHDOG_MS = TOKEN_HEARTBEAT_MS * 3   // 45s（gpt：勿抄 SSEClient 30s）
private fun heartbeatExpired(lastFrameAt: Long, now: Long) = now - lastFrameAt >= TOKEN_WATCHDOG_MS
// epoch 在连接创建时捕获（gpt should-fix），非 dispatch 时
data class EpochFrame(val epoch: Long, val frame: TokenStreamFrame)
fun open(sid: String) { val epoch = ++currentEpoch; tokenClient.connect(sid).collect { reducer.accept(EpochFrame(epoch, it)) } }
fun accept(i: EpochFrame) { if (i.epoch != currentEpoch) return; reduce(i.frame) }
```

### 3.8 纯 reducer effect 模式（opus SF-2）
```kotlin
sealed interface TokenStreamCoordinatorEffect {
    data class ClearPartState(val partIds: Set<String>) : TokenStreamCoordinatorEffect
    data class TriggerSinceFetch(val sessionId: String, val authoritative: Boolean) : TokenStreamCoordinatorEffect
    data class Reconnect(val sessionId: String) : TokenStreamCoordinatorEffect
}
object TokenStreamReducer {
    fun reduce(state: Map<String, StreamOwnedState>, frame: TokenStreamFrame, owned: Map<String, Set<String>>)
        : Pair<Map<String, StreamOwnedState>, List<TokenStreamCoordinatorEffect>> { /* 纯状态机 */ }
}
// coordinator 消费 effects → dispatch AppAction / clearSession / triggerSinceFetch / scheduleReconnect
```

### 3.9 显示 key lifecycle + placeholder（grok S3/S4）
- authoritative merge 已清 `streamingPartTexts`（§3.4 cleared）→ 真值不再被 overlay 盖（`MessageCard.kt:469`）。
- 首 `PartSnapshot` 前若本地无 part：dispatch `AppAction.PartPlaceholderEnsured(partType="text", partId, messageId, sessionId)` 造占位（Q5 degraded path 兜底）。

---

### 3.10 Round2-joint 残留 must-fix（folded 为阶段契约）
> 联合 Stage-0 三评委（grok 9.2 / bgpt 8.7 / opus 9.0）共识：**架构已认可**，残留=受限生命周期边界 bug，均附可采纳代码。设计不再做文档级重评循环；以下 fold 为**各 Stage 实施契约**，在该 Stage 门控（9.5）验证。

**客户端（落 ocdroid 各 Stage）**
- **[Stage A]** `AppAction` sealed 写法对齐：`: AppAction` 非 `: AppAction()`（grok S1，trivial）；`activePartIds` 在 authoritative merge 后 prune（grok S2）。
- **[Stage B]** authoritative caller 决策规则（grok MF-1）：`isAuthoritativeSlimMerge(mode,sid,sessionStatuses,forceAuthoritative)=true iff forceAuthoritative||mode==RESYNC||(session idle 非 busy/retry)`；贯穿 `mergeSlimMessagesIntoChat(authoritative)`/`applySlimColdStartSnapshot(authoritative)`（cold-start 默认 false，resync 触发的 true）。
- **[Stage B]** part 保留范围收窄（opus MF-A，消除非-token-stream 回归）：`preservedLocal = local.filter { lp.id !in fetchedIds && newOwned[lp.id]==STREAMING }` → `streamOwned` 空时等价原 `partsByMessage+(id to item.parts)` 整体覆盖。
- **[Stage B]** `MessagesMerged` streamOwned 认知（grok S3/bgpt SF-2）：`MessagesMerged(authoritative)` + `mergeStreamingOverlay`（token-owned streaming part 不被 skeleton 清；authoritative 清对应 overlay）。
- **[Stage C]** `ResyncReason` 枚举（opus SF-2）：4 reason + `triggersReconnect`（reconnect_no_replay/subscriber_backpressure→重连）；`tokenStreamClient` 补 `TrafficCountingInterceptor`（opus SF-1，流量统计）。
- **[Stage D]** watchdog 首帧前也超时（bgpt MF-2）：独立 watchdog 从 `open()/onOpen()` 起计时，任意 frame 更新 `lastFrameAt`，45s 无帧→cancel；**不复用** `SSEClient` 的 `eventCount==0` 例外（`:292-305`，否则首帧前永久挂起）。
- **[Stage D]** clear-action generation 守卫（bgpt MF-3）：coordinator 按 `(sid,generation)` 注册 owner；clear effects 带 sid+generation；dispatch 前校验当前 owner（防旧 session 迟到 clear 清掉新 session 同 partId overlay；epoch 已护 frame，此为补 clear-effect）。

**服务端（→ 转 slimapi，其 backstop 印证）**
- `_disabled_parts` 有界化（bgpt MF-1）：bounded `OrderedDict`+TTL(4096/300s)+prune（现 `drop_part` 永久加 key→绕过 C5 cap 进程级泄漏）。
- `publish()` 路由补 `session.status`/`session.deleted` → token_hub（opus MF-B）：`_busy_sids`+`_retire_session(sid)`+TTL busy-guard 数据源（§5.3 三个退役触发依赖它，现 §5.2 只路由 part 事件）。
- `session.idle` 清理时向 token subscriber 发 resync（bgpt SF-3）：新 `session_idle` reason 或客户端把控制面 idle 当 authoritative 触发。

---

## 4. 开发阶段批次
| Stage | 范围 | 主要文件 | fixer |
|---|---|---|---|
| **0** | 设计 v3 契约门控（§3） | dev-plan §3 | — (gate only) |
| **A** | 状态模型 + health features(Q1/M8) + 清态原语+partId 注册(M1/Q2) | ServerCompatProfile, OpenCodeRepository, AppAction, ChatState/SessionSyncCoordinator | fixer-zlm ×2 |
| **B** | 拼接：mergeSlimMessages(authoritative)+SlimMessagesMerged 贯穿(M3/Q3) + MessagesMerged streamOwned 认知(S3) + single-owner guard(M4) | SessionSyncCoordinator, AppAction, MessageActions, SharedConversationSseHandler | **fixer**（复杂） |
| **C** | 传输 client(M7/S2) + TokenStreamFrame + reducer effect 模式(Q8) + epoch(M6) | TokenStreamClient(new), OkHttpClientFactory, TokenStreamFrame(new), TokenStreamReducer(new) | **fixer** |
| **D** | 生命周期 coordinator：watchdog(M2)+session.deleted(M5)+503+debounce + busy-open UX(B-1)+placeholder(Q5)+display key | TokenStreamCoordinator(new), ConnectionCoordinator, ChatViewModel, MessageCard | **fixer** |
| **E** | routing A-list 补行 + 集成测试（emulator） | docs/slim-mode-api-routing.md, tests | fixer-zlm |

每 Stage 完成后 `./scripts/check.sh` 必过 → 单评委门控 9.5 → PASS 才进下一 Stage。

## 5. 门控协议
- 每阶段单评委：**rev-grok** 优先 → 不可用 **rev-bgpt** → 仍不可用 **rev-gpt**；阈值 **9.5**。
- 不满须附可采纳方案 + Kotlin 代码。
- FAIL→修订→重评；同 Stage 重试 ≥2 次升级 fixer（见 §6）。
- 评委复用 session（rev-1 等）保留上下文。

## 6. fixer 路由
- 简单/独立改动 → **fixer-zlm**，并发 ≤3。
- 复杂/跨多文件/重试 ≥2 → **fixer**。
- 每批 `./scripts/check.sh`（编译+单测）必过。

## 7. slimapi 协调
- 双方同形式（计划+阶段+每阶段单评委 9.5 门控+报告日志）。
- `session_send` 互通阶段进度；契约分歧双边对齐（首要：Q1 features 路径冻结）。
- 各自阶段通过后，可选联合终审。

## 8. 报告 / 状态日志（每阶段更新）
| 时间 | 阶段 | 动作 | 结果 |
|---|---|---|---|
| r1 | 设计 v1 评审 | 3 评委 | FAIL 8.6/6.2/8.6 → v2 |
| r2 | 设计 v2 评审 | 3 评委 | FAIL 9.1/8.4/8.7 → v3 契约（§3） |
| now | 计划落盘 + 关键裁定 | dev-plan v1 | 完成；关键问题 Q1-Q8 裁定（§2） |
| now | slimapi 双边对齐（session_send 往返） | — | **Q1 冻结 root**（slimapi 确认）+ Q2-Q8 全对齐（兼容 server v3，无服务端改动）；服务端同形式阶段计划（design-token-stream.md §14） |
| now | Stage-0 门控模型裁定 | — | 采纳 slimapi 提议：**Stage-0 设计联合 3 评委门控**（merged server v3 + client v3 §3，池 rev-grok+rev-opus+rev-bgpt）；impl Stage A-E 仍各侧单评委 |
| r3 | Stage-0 联合门控（merged 设计） | rev-grok+rev-opus+rev-bgpt | FAIL 9.2/9.0/8.7；**架构认可**，残留=受限生命周期边界（均附码）；三家共识 Stage A 可 GO、残留落 Stage B/D |
| now | Stage-0 裁定 | 设计 4 轮评审充分 | **架构级 PASS**；残留 fold 为 §3.10 阶段契约；转入每阶段 9.5 门控（不再文档级重评）；服务端 must-fix 转 slimapi |
| now | slimapi 服务端 backstop | rev-bgpt(带 v1/v2 史) | 7.4 FAIL，7 个 server bug；**跨平面 wire 兼容**（features root/无 part_state_missing/truncated 保 done/backpressure 带 sessionID）→ 我方 §3 无需改。server-7 与我方 client-评委 server 发现高度重合（#3=bgpt MF-1 / #5=opus MF-B / #7=SF-3）= 独立印证；#1/#4/#6/#2 为 slimapi 独有 server-internal |
| now | 双边进程对齐 | session_send | 双方转**每阶段 9.5 单评委门控**（用户模型 + slimapi §14）；不做 merged 设计重门控（无跨依赖）；client 残留=§3.10 stage B/D 契约，server-7=slimapi server-stage 契约；双方并行 Stage A |
| — | **Stage A** impl（状态/health/清态） | fixer-zlm ×2 (fix-1+fix-2) | **完成**：SlimapiFeatures+features dual-read(M8) / StreamOwnedState+streamOwned(§3.1) / ClearTokenStreamState(partIds)+reducer(§3.3)；fix-2 check.sh=BUILD SUCCESSFUL（集成态绿）|
| — | **Stage A 门控** | rev-grok | **PASS 9.7**（无 must-fix；§3.1/3.2/3.3 ✅、范围纪律 ✅、零回归 ✅）；2 should-fix（boolean parse 形式 / wipe 路径清 streamOwned）并入 Stage B |
| — | **Stage B** impl（splice + single-owner） | fixer（复杂, fix-3） | **完成**：B1 卫生 / B2 mergeSlimMessages(authoritative)+isAuthoritativeSlimMerge+ReconcileMode 贯穿 / B3 MessagesMerged(authoritative)+streamOwned 认知 / B4 hasActiveTokenStreamOwner+TokenStreamPolicy+SharedConversation guard；+6 回归测试；fix-3 check.sh GREEN（含 T1b 字节级等价）|
| — | **Stage B 门控 r1** | rev-grok | **FAIL 9.1**：B1/B2/B4/回归/范围/零回归 ✅；B3 ❌ must-fix（MessagesMerged authoritative 不读 streamOwned→idle+STREAMING-owned 误清 overlay）；2 should-fix（§3.4 文档 snippet 同步 / reducer 防御性清 SPT）|
| — | **Stage B 门控 r1 修补** | fixer-zlm (fix-4) | **完成**：MF-1(authoritative 读 streamOwned)+S2(reducer 清 SPT)+回归测试；check.sh GREEN，T1b/SlimMessagesMerge 仍过 |
| — | **Stage B 门控 r2** | rev-grok | **PASS 9.7**（MF-1 修对：authoritative 读 streamOwned，idle+STREAMING-owned→不清 overlay；S2 防御；零回归+范围 ✅）→ GO Stage C |
| — | **Stage C** impl（传输 client+TokenStreamFrame+Reducer(effect)+ResyncReason+EpochFrame） | fixer (fix-5) | **完成**：4 建材 + 36 单测(19+17)；check.sh GREEN；5 适配（EventSource 镜像 SSEClient / tokenStreamClient 从零建排除 Directory+cache / JsonNull 守卫 / ResyncReason 独立 / ownedBySession 语义）|
| — | **Stage C 门控** | rev-grok | **PASS 9.7**（frame/reducer/client/拦截器全 ✅；5 适配 sound；JsonNull 守卫；零回归）→ GO Stage D |
| — | **Stage D1** impl（coordinator 引擎） | fixer (fix-6) | **完成**：TokenStreamCoordinator ~730 行 + 20 单测 + AppAction.TokenStreamPartUpdated(写侧桥) + TokenStreamClient S1；check.sh GREEN(3978 测试)；4 适配（triggerSinceFetch callback 隔离 / 专用 TokenStreamPartUpdated 不复用 legacy 两阶段 / TestScope+runCurrent / **structured-concurrency 修复防 watchdog 挂起**）；7 个 D2 接线 TODO 已列 |
| — | **Stage D1 门控 r1** | rev-grok | **FAIL 9.0**：epoch/watchdog 首帧前/generation-guard/write-bridge/structured-concurrency/S1+S2/范围/零回归 ✅；2 must-fix（MF-1 max-1 并发重连破：reconnect/503 job 未入 currentStreamJob→双 runStream+mid-collect 不拆 collect；MF-2 consecutive503 成功帧不重置）；S2(triggerSinceFetch authoritative)记 D2 |
| — | **Stage D1 门控 r1 修补** | fixer (fix-6 复用) | **完成**：MF-1(单一 lifecycle job+Reconnect 哨兵拆 collect+cancelCurrentStream+**LAZY-start 修复**)+MF-2(成功帧重置 streak)+3 新测试(dual-open/503-supersede/streak-reset)；check.sh GREEN(3981 测试)；23/23 coordinator 测试 |
| — | **Stage D1 门控 r2** | rev-grok | **FAIL 9.2**：MF-1 max-1 ✅、LAZY-start ✅(显式验证 sound)、MF-2 ✅、3 测试 ✅、零回归/范围 ✅；新 must-fix（sentinel CAS 卡死：切换-中-resync 后新 sid Reconnect 永不触发）|
| — | **Stage D1 门控 r2 修补** | fixer (fix-6 复用, retry#2) | **完成**：sentinel 5 处 CAS→无条件 set(sid)/set(null)+stale-sentinel 回归测试(test seam)+2 seam；check.sh GREEN(3982 测试)；24/24 coordinator 测试 |
| — | **Stage D1 门控 r3** | rev-grok | **PASS 9.7**（sentinel 闭合+回归+先前修复完好[max-1/LAZY/MF-2/generation/watchdog/structured-concurrency]+零回归）→ GO D2。Stage D1 完成（3 并发洞修补）|
| — | **Stage D2** impl（UX/生命周期接线） | fixer (fix-6 复用) | **完成**：Hilt DI+streamProvider/triggerSinceFetch(RESYNC=auth)/directory + ConnectionCoordinator close(cancel/reconfigure)+resetDegraded(health) + AppCore EvictSession 漏斗→close(覆盖 deleted+404, 防 DI 环) + ChatViewModel busy-open(B-1)+capability 门控；check.sh GREEN(3962 测试)；1 D2-TODO(PartPlaceholderEnsured from bridge)转 Stage E |
| — | **Stage D2 门控 r1** | rev-grok | **FAIL 8.8**：DI/EvictSession-funnel/triggerSinceFetch-authoritative/resetDegraded/能力门控/测试/范围 ✅；1 must-fix（busy-open 接错入口：open 仅在 ChatViewModel.loadMessages，主路径 loadMessagesForEffect 不开→B-1 对正常 UX 失效）；S1(EvictSession 清 overlay)/S3(单一 open 站点)随修；S2(PartPlaceholder)留 Stage E |
| — | **Stage D2 门控 r1 修补** | fixer (fix-6 复用) | **完成**：MF-1(open 移到 loadMessagesForEffect 共享入口+foreground 门控)+S3(移除 ChatViewModel 重复 open)+S1(EvictSession 清当前 session overlay)；check.sh GREEN(3962 测试)|
| — | **Stage D2 门控 r2** | rev-grok | **PASS 9.7**（open 在主路径 loadMessagesForEffect+foreground 门控；EvictSession 清当前 overlay；triggerSinceFetch authoritative；DI 无环；能力门控零回归）→ GO Stage E。Stage D2 完成（1 轮修补）|
| — | **Stage E** impl（routing A-list + PartPlaceholder from bridge + side-door open + wiring 测试） | fixer-zlm (fix-7) | **完成**：E1 routing A13 + E2 bridgePartToChatState→PartPlaceholderEnsured + E3 ChatViewModel side-door open + E4 TokenStreamWiringTest(5 测试)；check.sh GREEN(3967 测试)；5 手工 smoke-test 场景（live-emulator E2E deferred）|
| — | **Stage E 门控**（最终） | rev-grok | **PASS 9.6**（E1-E4 ✅、零回归、scope-complete）→ **feature COMPLETE** |

---

## 9. 完成总结（ocdroid client token-stream 集成）

**门控链全通过**：A 9.7 / B 9.7(r2) / C 9.7 / D1 9.7(r3) / D2 9.7(r2) / E 9.6 — 每 Stage ≥9.5 单评委 rev-grok。
**测试**：3967 单测全绿（TokenStreamFrame 19 + Reducer 17 + Coordinator 24 + SlimMessagesMerge 6+ + Wiring 5 + 既有 3900 无回归）。
**零回归**：`features.tokenStream` 默认 false（fail-closed）；opt-in 加性，不 bump `X-Slimapi-Version`。
**实现要点**：动画层(streamOwned/streamingPartTexts) vs 真值层(/since authoritative)；四正交子系统(传输/纯归约 effect-list/拼接/生命周期)；single-owner；watchdog 首帧前(45s)；epoch+generation 双守卫(frame+clear-action)；EvictSession 漏斗(覆盖 deleted+404，防 DI 环)；busy-open 主路径(loadMessagesForEffect)。

**已知 should-fix（post-merge polish，非阻塞）**：
- S1 提取 `shouldOpenTokenStream(enabled,currentSessionId,sessionId)` 纯 helper（生产+测试共用，提升 E4 测试保真）。
- S2 手工 smoke-test 清单统一落一处（dev-plan 本节 / design §8）。
- S3 `ChatViewModel.kt:122-129` kdoc 陈旧（D2 移除 open 后 E3 又加回 side-door，kdoc 未同步）——2 行注释修正。

**手工 smoke-test（live-emulator E2E，deferred——需 live 生成会话+模拟器共享资源）**：
详细的 5 个衰退场景定义、前置条件、执行步骤与期望结果已迁移至独立文档 [`docs/token-stream-smoke-test.md`](./token-stream-smoke-test.md)。该文档同时包含模拟器环境准备与清理指令，是执行手动验收测试的单一权威来源。

**双边状态**：ocdroid client **完成**；ocdroid 侧已备联合终审。待 slimapi 服务端阶段完成后做联合 final review。
| — | Stage E（routing A-list + PartPlaceholderEnsured from bridge + 集成测试） | — | pending |
| — | Stage D2（UX 接线）+ Stage E（routing+集成测试） | — | pending |
| — | Stage C-E | — | pending（C：传输/帧/reducer(effect)/epoch；D：coordinator+watchdog+deleted+UX；E：routing+集成测试）|
