# ocdroid slimapi v1 客户端实现 — Implementation Plan (v2, flat-numbered)

> **For agentic workers:** REQUIRED SUB-SKILL: Use ocmar-subagent-driven-development. Steps use checkbox (`- [ ]`).
> **v2 由 grilling（oracle）驱动重写**：8 Critical（G5 状态模型/status fan-out/cursor API/G6 重试/Last-Event-ID/banner 归属/§10 矩阵/command 通道）+ 7 Important + 3 Minor。任务扁平编号 T1-T18（task-brief 工具要求数字号）。

**Goal:** 全量实现 ocdroid 客户端 slimapi v1 配套（任务书 B5），单测级绿（`./scripts/check.sh`）。

**Architecture（v2）:**
- **G5 状态模型**：`SlimSessionState` 拆 `remoteObserved*`（digest 立即推进，单调）vs `localApplied*`（仅 REST merge 成功才推进）+ `dirty`。digest/resync 共用单一 `reconcileSession(sid, trigger)`。
- **status 双机制**（用户裁决 A）：host-wide 批量轮询（legacy 不动）+ slim per-session on-demand（新，typed outcome，coordinator 据 404/503 行动）。
- **cursor**（用户裁决 B）：契约（§3 line 160）胜出；新 `getSlimapiMessagesPage()` 暴露 `X-Next-Cursor`。
- **Last-Event-ID**：服务端不发 `id:` 但检 header 存在性 → 客户端首次成功后重连带 marker `slim-no-replay`。见 Contract-Gap Ledger。
- **banner 归属**：canonical `SessionListState.sessionErrorsById`；UI 经既有 `StatusSlot`/`ChatScaffold:1211`。

**Tech Stack:** Kotlin + Retrofit + okhttp3 + kotlinx.serialization + Compose. 测试 JUnit4 + mockk + MockWebServer + coroutines-test.

**Spec:** `docs/ocmar/specs/2026-07-18-slimapi-client-v1-design.md` · **任务书:** `docs/slimapi-client-impl-v1.md` · **服务端:** `/home/mar/personal_projects/oc-slimapi/docs/INTERFACE_MAP.md`

## Global Constraints
- 契约冻结（任务书+INTERFACE_MAP）；slim 分支（`isSlimMode`）下生效，legacy 不动。
- mutation POST 不自动重试（§0 原则 2）；错误码 `{"code":…}`，404 清本地/503 重试。
- slim 模式不假设 token delta；probe 禁裸 `probe[0]`。
- 测试：`OpenCodeRepository(mockk(relaxed=true),...)` + `configure(baseUrl, slim=true)` + MockWebServer；JSON `ignoreUnknownKeys=true, explicitNulls=false, encodeDefaults=true`。
- 每 task 后 `./scripts/check.sh` 必过（compile + 单测；**不跑 kover**）。不 commit（仅 record diff）。
- **并发**：SERIAL（admission gate：L1 共享 OpenCodeApi/Repository、L3 共享 Coordinator，文件重叠压倒性；唯一无重叠候选 T6/T7/T8 在 T1-T5 barrier 后但 worktree 开销 > 收益 → fail-closed SERIAL）。

## Contract-Gap Ledger

| 项 | 目标契约 | 服务端当前 | 客户端策略 | 联调阻塞 |
|---|---|---|---|---|
| Last-Event-ID | 服务端发 `id:` | 只发 event:/data:；检 header 存在性 | 重连带 `slim-no-replay` marker | 服务端补 id 后去 marker |
| per-session status 404 | upstream 404 透传 | 映射为 503 | 503 退避；真删除靠 sessions 交叉验证 | 服务端改透传 404 |
| G6 batch full 路由 | `?ids=` 存在 | 无该路由（`thin_route_not_found`） | fallback N 并行单条 | 服务端部署 batch 后切批量 |
| routeToken 过期刷新 | §3 line 177 | n/a | **WAIVED（D1 OUT）** | 联调阶段 |

## Task → Layer 映射
- L0: T1
- L1: T2(G3) T3(G6) T4(G2) T5(G5 cursor)
- L2: T6(G5 状态模型) T7(probe) T8(merge)
- L3: T9(transport LE-ID) T10(owner reason) T11(reconciler) T12(session.error/banner) T13(status fan-out) T14(mutation client)
- L4: T15(expand state) T16(chat wiring) T17(banner StatusSlot)
- L5: T18(docs)

**Barrier:** T1→T2/T3/T4/T5→[T1-T5 done]→T6/T7/T8→T9→T10→T11→T12→T13→T14→T15→T16→T17→T18。

---

## Task 1: L0 模型 + 契约常量（foundation）

**Files:** Modify `data/model/Message.kt:194-210`、`data/model/Slimapi.kt:124-133`；Create `data/repository/http/SlimapiErrorCodes.kt`；Test `data/model/SlimapiV1ModelsTest.kt`

**Interfaces Produces:** `Part.hasFull/omitted`；`SlimSessionLastError`；`LastErrorField`(sealed Omitted/Cleared/Set, descriptor=`kotlinx.serialization.descriptors.buildClassSerialDescriptor`)；`SlimSessionDigest.lastError`；`SlimapiResyncReason`+`fromRaw`；`SlimapiMessageFullBatch`/`SlimapiMessageBatchError`；`SlimapiErrorCodes.*`。

**Acceptance:**
- `T1-C1`: Part hasFull/omitted round-trip + 默认 null（`explicitNulls=false` 兼容旧响应）。
- `T1-C2`: lastError 三态：缺键→Omitted；`null`→Cleared；object→Set。
- `T1-C3`: G6 envelope parse items+errors。
- `T1-C4`: error codes 常量值与任务书一致；descriptor import 正确。

**Steps:** TDD — 写 `SlimapiV1ModelsTest`（Part round-trip / 默认 null / lastError 三态 / G6 envelope / error codes 共 7 测试）→ 跑红 → 实现：Part 加 `hasFull:Boolean?=null`+`omitted:List<String>?=null`；`LastErrorField` sealed + serializer（**descriptor 用 `kotlinx.serialization.descriptors.buildClassSerialDescriptor`**）；`SlimapiResyncReason` enum；G6 DTO；`SlimapiErrorCodes` object → 跑绿 → record diff。

```kotlin
// SlimapiErrorCodes.kt
object SlimapiErrorCodes {
    const val SESSION_NOT_FOUND = "session_not_found"
    const val DIRECTORY_NOT_ALLOWED = "directory_not_allowed"
    const val UPSTREAM_UNAVAILABLE = "upstream_unavailable"
    const val UPSTREAM_HTTP_PREFIX = "upstream_http_"
    const val UPSTREAM_TIMEOUT = "upstream_timeout"
    const val THIN_ROUTE_NOT_FOUND = "thin_route_not_found"
    const val INVALID_IDS = "invalid_ids"
    const val RESPONSE_TOO_LARGE = "response_too_large"
    const val TRANSFORM_BUSY = "transform_busy"
    const val MESSAGE_NOT_FOUND = "message_not_found"
}
```

`LastErrorField` serializer 同 v1（decode 三态：JsonNull→Cleared；JsonObject→Set；缺键→默认 Omitted），仅修 descriptor import 为 `kotlinx.serialization.descriptors.buildClassSerialDescriptor`。

---

## Task 2: L1 G3 — probe（repo 直接返回 ProbeResult）

**Files:** Modify `data/api/OpenCodeApi.kt:317`、`data/repository/OpenCodeRepository.kt:889`；Test 扩展 `OpenCodeRepositorySlimapiEndpointsTest.kt`

**Interfaces Produces:** `OpenCodeApi.getSlimapiMessages(mode=…)`；`OpenCodeRepository.probeLatestSlim(sessionId): ProbeResult`（边界归一化，不经 Result<Response>；`ProbeResult` 定义在本 task，纯 data class 不依赖 retrofit）。

**Acceptance:**
- `T2-C1`: 命中 `GET /slimapi/messages/{sid}?limit=1&mode=skeleton`。
- `T2-C2`: 200 `[]`→`ProbeResult(ok=true,empty=true)`；200 `[{info…}]`→`ok=true, messageID/updatedAt=info.time.updated?:created`。
- `T2-C3`: 404→`ProbeResult(ok=false,httpStatus=404)`；网络异常→`ok=false,httpStatus=null`。
- `T2-C4`: legacy `probeLatestMessageId` 不变；既有测试不破。

**Steps:** TDD → API 加 `mode` query → `probeLatestSlim` wrapper（边界归一化）→ 跑绿 → record。

```kotlin
suspend fun probeLatestSlim(sessionId: String): ProbeResult = runSuspendCatching {
    val resp = api.getSlimapiMessages(sessionId, limit = 1, before = null, mode = "skeleton")
    if (!resp.isSuccessful) return@runSuspendCatching ProbeResult(ok = false, httpStatus = resp.code())
    val arr = resp.body() ?: return@runSuspendCatching ProbeResult(ok = false, httpStatus = resp.code())
    if (arr.isEmpty()) ProbeResult(ok = true, empty = true)
    else ProbeResult(ok = true, messageID = arr.first().info.id,
        updatedAt = arr.first().info.time?.updated ?: arr.first().info.time?.created)
}.getOrElse { ProbeResult(ok = false, httpStatus = null) }
```

`ProbeResult` data class（ok/empty/messageID/updatedAt/httpStatus）本 task 定义于 `OpenCodeRepository.kt` 顶部或 `SlimapiProbe.kt`（T7 会复用）—— **本 task 定义在 `SlimapiProbe.kt`**，T7 仅加纯函数。

---

## Task 3: L1 G6 — 批量 full + 完整重试策略 in repo

**Files:** Modify `data/api/OpenCodeApi.kt:333`、`data/repository/OpenCodeRepository.kt:1409`；Test 扩展 endpoints test。

**Interfaces Produces:** `getSlimapiMessagesFullBatch(...)`；`expandMessagesFullBatch(sessionId, ids): ExpandOutcome`（sealed: Ok(items,failedIds,usedBatch)/SessionMissing/Failed(code)）。

**Acceptance（重试硬门槛）:**
- `T3-C1`: 200→items 按 ids 去重保序；errors mid 进 failedIds。
- `T3-C2`: 404+`session_not_found`→`SessionMissing`。
- `T3-C3`: 404+`thin_route_not_found`→**fallback N 并行单条**（`coroutineScope{async}+Semaphore(4)`）；其它 404→`Failed`。
- `T3-C4`: 413→**repo 内折半重试**至单 id（不外泄 RetryWithFewerIds）。
- `T3-C5`: 503→**repo 内有界指数退避**（max3, base 200ms, jitter±30%）。
- `T3-C6`: 400→`Failed`（不重试）；422→`Failed`（编程错误）。
- `T3-C7`: ids 规范化：去重保序 + coerce 1..20（空→Failed，>20→截前20+日志，**不 throw**）。
- `T3-C8`: 测试 path 断言不含 "GET " 前缀（修 v1 测试 bug）。

**Steps:** TDD（8 用例对应 C1-C8）→ API（`@GET("slimapi/messages/{sid}/full")` + `@Query("ids")`+`@Query("mode")`+`@Query("directory")` 返回 `Response<SlimapiMessageFullBatch>`）→ repo wrapper（折半+退避+有界并发 fallback + `parseErrorCode` helper）→ 跑绿 → record。

`parseErrorCode(r: retrofit2.Response<*>): String?` = errorBody string → `Json{ignoreUnknownKeys=true}.decodeOrNull<{code:String?}>().code`。

---

## Task 4: L1 G2 — per-session status（Success 携带真实 payload）

**Files:** Modify `data/api/OpenCodeApi.kt`、`data/repository/OpenCodeRepository.kt`；Test 扩展。

**Interfaces Produces:** `getSlimapiSessionStatus(...)`；`getSlimapiSessionStatusOutcome(sessionId): StatusOutcome`。

```kotlin
sealed interface StatusOutcome {
    data class Success(val sessionId: String, val status: SessionStatus) : StatusOutcome
    data class SessionMissing(val sessionId: String) : StatusOutcome
    data class DirectoryError(val sessionId: String) : StatusOutcome
    data class UpstreamWarn(val sessionId: String, val code: String?) : StatusOutcome
    data class Retry(val sessionId: String, val code: String?) : StatusOutcome
}
```

**Acceptance:**
- `T4-C1`: 命中 `GET /slimapi/sessions/{sid}/status`。
- `T4-C2`: 200 `{"type":"busy"}`→`Success(busy)`（**不**折 idle）；idle/retry 同理。
- `T4-C3`: 404 `session_not_found`→`SessionMissing`；503→`Retry`；502→`UpstreamWarn`；400→`DirectoryError`。

**Steps:** TDD → API（`@GET("slimapi/sessions/{sid}/status")` 返回 `Response<SessionStatus>`）→ repo outcome → 跑绿 → record。

---

## Task 5: L1 G5 — getSlimapiMessagesPage 暴露 cursor

**Files:** Modify `data/api/OpenCodeApi.kt:316-321`、`data/repository/OpenCodeRepository.kt:1396-1404,1576-1589,90`；Create `data/repository/MessagesPage.kt`；Test 扩展。

**Interfaces Produces:** `MessagesPage(items, nextCursor)`；`getSlimapiMessagesPage(sid, limit, before, mode): Result<MessagesPage>`（读 `X-Next-Cursor` header）。

**Acceptance:**
- `T5-C1`: 200+`X-Next-Cursor: m1`→`nextCursor="m1"`；无 header→null。
- `T5-C2`: `coldStartSlimSync` 有 bookmark→`/since/{ts}` 单页（保留）；无 bookmark→`getSlimapiMessagesPage(mode=skeleton, limit=200)` cursor 续拉至 null 或达 bound。
- `T5-C3`: `SLIMAPI_DEFAULT_PAGE_LIMIT==200`；删 `:1578` "no cursor follow" 注释。
- `T5-C4`: 续拉测试：2 页→2 请求+聚合+messageID 去重。
- `T5-C5`: `SLIMAPI_LOCAL_HISTORY_BOUND` 取自既有缓存策略（读 `MessageLoadCoordinator`/分页常量），给出产品依据，不拍 2000。

**Steps:** TDD（多页 MockWebServer）→ `getSlimapiMessagesPage` 仿 legacy `getMessagesPaged:858-881` 读 header → coldStart 分 anchored-since vs no-anchor-cursor → 删旧注释 → 跑绿 → record。

---

## Task 6: L2 G5 状态模型 — split watermark + reconcileSession（核心重设计）

**Files:** Modify `data/repository/SlimSseReducer.kt:17-31,117-165`；Create `data/repository/SlimapiResync.kt`；Test `SlimapiResyncTest.kt` + 扩展 `SlimSseReducerTest.kt`。**依赖 T1-T5 完成。**

**架构（锁定）:** `SlimSessionState` 拆：
```kotlin
data class SlimSessionState(
    val sessionId: String,
    val directory: String? = null,
    val status: String? = null,
    val remoteMessageId: String? = null,      // digest 立即推进，单调
    val remoteUpdatedAt: Long? = null,
    val localAppliedMessageId: String? = null, // 仅 REST 成功推进
    val localAppliedUpdatedAt: Long? = null,
    val lastError: SlimSessionLastError? = null,
    val archived: Long? = null,
    val deleted: Boolean = false,
    val dirty: Boolean = false,
)
```
reducer（纯）: digest 推进 `remote*`（单调）+ lastError 三态 merge + `dirty=true`（当 needsReconcile）；**不动 localApplied***。`onReconcileSuccess(sid, items)`：推进 localApplied* + 清 dirty。`onReconcileFailure(sid)`：保留 dirty。`markDeleted`/`clearLocal` 信号。`needsReconcile(state)`：比较 remote vs localApplied。

**Acceptance:**
- `T6-C1`: digest updatedAt→remoteUpdatedAt 推进，localAppliedUpdatedAt **不动**，dirty=true。
- `T6-C2`: REST 成功→localApplied* 推进+dirty=false；失败→dirty 保持。
- `T6-C3`: 非 focus digest→remote* 推进+dirty=true，**不清 dirty**。
- `T6-C4`: lastError 三态 merge。
- `T6-C5`: 回归：既有 updatedAt 单调+fetch decision（适配 split 字段）。
- `T6-C6`: needsReconcile 各分支（remoteId!=localId / remoteUpdatedAt 更新 / dirty / 对齐→false）。

**Steps:** TDD（reconcile 逐分支）→ 拆字段+reducer+reconcile 纯函数 → 适配既有 reducer 测试 → 跑绿 → record。

> ⚠️ 最关键 task。reviewer 重点审 split-watermark 不变量。

---

## Task 7: L2 SlimapiProbe — 纯函数（解耦 retrofit）

**Files:** Create `data/repository/SlimapiProbe.kt`（含 `ProbeResult`，T2 已用 → T2 定义此处，T7 加纯函数）；Test `SlimapiProbeTest.kt`。**依赖 T1-T5。**

**Interfaces Produces:** `needsCatchUp(probe, localAppliedId, localAppliedTs): Boolean`；`catchUpSet(focus, localAll, dirty): Set<String>`。

**Acceptance:**
- `T7-C1`: needsCatchUp 严格按 §3（probe.ok=false→false；empty→false；localAppliedId 不存在→true；probeId!=localAppliedId→true；probe.updatedAt>localAppliedTs→true；否则 false）。
- `T7-C2`: catchUpSet = focus ∪ localAll ∪ dirty。

**Steps:** TDD → 纯函数 → 跑绿 → record。

---

## Task 8: L2 G6 merge — null-safe + status 映射

**Files:** Create `data/repository/SlimapiMessageMerge.kt`；Test `SlimapiMessageMergeTest.kt`。**依赖 T1-T5（用 T3 ExpandOutcome/T4 StatusOutcome）。**

**Acceptance:**
- `T8-C1`: merge 按 `(normalizedMessageId, partId)` 替换；**测试双方 messageId=null**；重复 partId；部分返回。
- `T8-C2`: `mapStatusOutcome`: SessionMissing→ClearLocal；Success→ApplyStatus；Retry→Retry；DirectoryError/UpstreamWarn→Warn。
- **不引入 ResyncAction**（永远 Rebuild，无决策价值）。

```kotlin
fun mergeFullBatchIntoLocal(local: List<MessageWithParts>, fullItems: List<MessageWithParts>): List<MessageWithParts> {
    fun Part.normMsg(owner: String) = (messageId ?: owner)
    val fullByMsg: Map<String, Map<String, Part>> = fullItems.associate { it.info.id to it.parts.associateBy { p -> p.id } }
    return local.map { lm ->
        val replacements = fullByMsg[lm.info.id] ?: return@map lm
        lm.copy(parts = lm.parts.map { lp -> replacements[lp.id]?.takeIf { it.normMsg(lm.info.id) == lp.normMsg(lm.info.id) } ?: lp })
    }
}
```

**Steps:** TDD（含 null messageId）→ 跑绿 → record。

---

## Task 9: L3 transport — Last-Event-ID marker

**Files:** Modify `data/api/SSEClient.kt:138-159`；Test 扩展 SSE 测试。

**Acceptance:**
- `T9-C1`: 首次连接请求**不带** Last-Event-ID。
- `T9-C2`: 首次成功 slim 连接后，重连请求**带** `Last-Event-ID: slim-no-replay`。
- `T9-C3`: 测试模拟"无 id 帧→断线→重连"（**不**人为提供 event id）。

**Steps:** TDD → SSEClient 加 `@Volatile var firstSuccessDone` + `buildRequest()` 注入 marker → 跑绿 → record。

---

## Task 10: L3 owner — resync reason（用既有签名）

**Files:** Modify `service/streaming/ServiceSseConnectionOwner.kt:588-598,701`；Test 扩展 `ServiceSseConnectionOwnerResyncTest.kt`。

**Acceptance:**
- `T10-C1`: reason 经 `JsonPrimitive.content` 解析（**不** `as? String`）；`SlimapiResyncReason.fromRaw` 记日志。
- `T10-C2`: 三 reason+未知+null 都复用既有 `scheduleResync(reason:String, generation)`+`onResync`（**签名不变**）。
- `T10-C3`: 测试三 reason+unknown 都触发 onResync。

**Steps:** TDD → reason 解析+调既有 scheduleResync → 跑绿 → record。

---

## Task 11: L3 per-session reconciler（digest + resync 共用）

**Files:** Modify `ui/controller/SessionSyncCoordinator.kt:1521-1612`+resync handler；Test `SessionSyncCoordinatorResyncTest.kt`。

**架构:** 单一 `reconcileSession(sid, isFocus): ReconcileResult`：probe→404 markDeleted/失败 keep dirty；empty+本地有→clearLocalMessages+清 dirty；needsCatchUp→focus: since/cursor drain→成功 onReconcileSuccess+清 dirty / 失败 keep dirty；非 focus: 刷行+不清 dirty；aligned→清 dirty。resync catch-up 集=`catchUpSet(focus, slimSseState.all().keys, sessionsDirty)`，`supervisorScope+Semaphore(4)`+per-sid deadline。

**Acceptance:**
- `T11-C1`: digest focus：updatedAt→since；无 updatedAt→probe；messageID 不匹配→probe（§3 三分支）。
- `T11-C2`: focus REST 成功才清 dirty；失败保留。
- `T11-C3`: 非 focus 不清 dirty。
- `T11-C4`: probe 404→markDeleted；empty+本地有→clearLocalMessages。
- `T11-C5`: resync catch-up集=focus∪localAll∪dirty，Semaphore(4)+per-sid deadline。
- `T11-C6`: digest+session.error 并发按 sid 幂等。

**Steps:** TDD（逐分支+并发幂等）→ reconcileSession+cursorDrain → 跑绿 → record。

---

## Task 12: L3 session.error + lastError → canonical sessionErrorsById

**Files:** Modify `ui/controller/SessionSyncCoordinator.kt:1168-1203`；`SessionListState`（或等价 slice）加 `sessionErrorsById: Map<String, SlimSessionLastError>`；Test `SessionSyncCoordinatorLastErrorTest.kt`。

**Acceptance:**
- `T12-C1`: session.error 有 sid→`sessionErrorsById[sid]=...`（durable）；无 sid→全局 toast，**不**写 map。
- `T12-C2`: digest lastError Set→写 map；Cleared→移除 key；Omitted→不变。
- `T12-C3`: 并发按 sid 幂等。
- `T12-C4`: **不引入** `repository.applySessionErrorBanner`/`sessionBanners`（不存在）——直接写 sessionErrorsById。

**Steps:** TDD → coordinator 路由+slice 字段 → 跑绿 → record。

---

## Task 13: L3 slim per-session status on-demand + coordinator 行动 + poller 退避（双机制）

**Files:** Create `service/status/SlimStatusFanOut.kt`（use-case）；Modify `service/status/ProcessStatusPoller.kt:80-179,250-263`；coordinator 行动 hook；Test `SlimStatusFanOutTest.kt`。

**架构:** **不动 StatusAggregatorImpl.refresh（host-wide bulk）**。新增 slim on-demand `checkSlimSessionsStatuses(sids): StatusFanOutSummary`（`coroutineScope+Semaphore(4)` 逐 sid `getSlimapiSessionStatusOutcome`）。`StatusFanOutSummary{perSid, retryableCount, missingSids}`。coordinator 据 missingSids→emit 删 session effect；retryableCount>0→请求 poller 退避。poller `scheduleBackoff()`（有界指数+jitter，max 30s），成功重置。假 idle 交叉验证：Success(idle) 但不在 sessions 快照→进 missingSids。

**Acceptance:**
- `T13-C1`: `checkSlimSessionsStatuses(["s1","s2"])` 发 2 次 per-session GET（并发）。
- `T13-C2`: 200 busy→`Success(busy)`（不误判 idle）；idle→`Success(idle)`；404→missingSids；503→retryableCount++。
- `T13-C3`: coordinator 收 missingSids→emit 删 session effect。
- `T13-C4`: poller 503→`nextDelay>base`；成功→重置。
- `T13-C5`: 假 idle 交叉验证。
- `T13-C6`: legacy（!isSlimMode）走原 bulk，行为不变（回归）。

**Steps:** TDD → fan-out use-case+summary+poller backoff hook+coordinator effect → 跑绿 → record。

---

## Task 14: L3 mutation client — 逐方法路由表

**Files:** Modify `data/repository/http/OkHttpClientFactory.kt:133-154`、`data/repository/OpenCodeRepository.kt:244-283,1168-1195`；Test `OkHttpClientFactoryMutationTest.kt`+接线测试。

**路由表:** GET→`restClient`(retryOnConnectionFailure=true)；`executeCommand`→`commandClient`(300s, **新设 retryOnConnectionFailure=false**)；其它 POST→`mutationClient`(新, 30s, **retryOnConnectionFailure=false**)。

**Acceptance:**
- `T14-C1`: mutationClient+commandClient 都 `retryOnConnectionFailure==false`；restClient 保持 true。
- `T14-C2`: **接线测试**：每个 POST wrapper 实际走 mutationApi；executeCommand 走 commandApi（300s 保留）。
- `T14-C3`: commandClient 也设 `retryOnConnectionFailure(false)`（command 是 POST，双发风险）。

**Steps:** TDD → mutationClient（仿 commandClient:146）+ commandClient 加 `.retryOnConnectionFailure(false)` + rebuildClients 接第 3 个 Retrofit mutationApi + 逐 POST 切换 → 跑绿 → record。

---

## Task 15: L4 expand state + usecase

**Files:** Create `ui/chat/PartExpandState.kt`；expand usecase（调 T3 expandMessagesFullBatch+T8 merge）；Test `PartExpandStateTest.kt`。

**Acceptance:**
- `T15-C1`: `PartExpandState` per `PartKey(messageId,partId)`: Idle/Loading/Loaded/Failed 转移单测。
- `T15-C2`: usecase `expandParts(sessionId, parts)`——仅 `hasFull==true&&omitted!=null` 收集 ids→expandMessagesFullBatch→mergeFullBatchIntoLocal→setLoaded/setFailed；**不复用** expandedParts/onToggleExpand。

**Steps:** TDD → 跑绿 → record。

---

## Task 16: L4 chat 展开 wiring

**Files:** Modify `ui/chat/ChatMessageRow.kt`、ViewModel（lift PartExpandState 与 streamingPartTexts 同层）；Test 扩展。

**Acceptance:**
- `T16-C1`: MessageRow 扫描 hasFull&&omitted parts→"展开省略内容" affordance；点击→触发 T15 usecase（多条合并批量）；loading/failed 内联（失败用既有 ErrorCard）。
- `T16-C2`: 按 ui-style-spec 三层规则（inline button，不新 overlay）。

**Steps:** TDD → 跑绿 → record。

---

## Task 17: L4 banner via StatusSlot + 非 focus 行

**Files:** Modify `ui/chat/StatusSlot.kt:168-215,302-307`、`StatusSlotPriority`、`ui/chat/ChatScaffold.kt:1211`、session 列表行；Test 扩展。

**Acceptance:**
- `T17-C1`: `StatusSlot` 读 `SessionListState.sessionErrorsById[sid]`（T12 产出）渲染 banner（按 lastError.name 决定样式）；null 不展示。
- `T17-C2`: `StatusSlotPriority` 加 lastError 优先级层。
- `T17-C3`: 非 focus session 列表行 status/icon 指示 lastError。

> T16/T17 视觉 polish 派 `@designer`（不阻塞验收；state 接线+数据正确性是硬门槛）。

**Steps:** TDD → 跑绿 → record。

---

## Task 18: L5 docs（独立）

**Files:** `docs/slim-mode-api-routing.md`；基准 INTERFACE_MAP。

**Acceptance:**
- `T18-C1`: §2/§5/M8/M9 嵌套→扁平 `/slimapi/messages/{sid}/*`；删 latest-message-id。
- `T18-C2`: 新增 G4 透传矩阵（thin/catch-all/不支持）。
- `T18-C3`: §3/§6 显式 must：mutation POST 禁 timeout 自动重试。
- `T18-C4`: §5 加 G6 batch full 端点条目。
- `T18-C5`: 加 Contract-Gap Ledger 引用。

**Steps:** 读 INTERFACE_MAP → 改 §2/§5/M8/M9 → 新增 G4 矩阵 → mutation must → G6 端点 → Contract-Gap 引用 → `check.sh`（docs 不影响编译，跑确认无副作用）→ record。

---

## Criterion Ownership Matrix（§9 + §10 逐用例）

| 用例 | Owner | 测试（入口+测试名+断言） | Final? |
|---|---|---|---|
| SSE digest lastError 三态 | T1+T6+T12 | `SlimapiV1ModelsTest::lastError*`+`SlimSseReducerTest::lastError*`+`SessionSyncCoordinatorLastErrorTest::set/cleared/omitted` | N |
| session.error 有/无 sid | T12 | `...LastErrorTest::with sid sets banner / without sid toast only` | N |
| digest+error 并发按 sid 幂等 | T11+T12 | `...ResyncTest::concurrent digest+error idempotent` | N |
| resync 三 reason 都重建+catch-up | T10+T11 | `ServiceSseConnectionOwnerResyncTest::reason=RECONNECT/SUBSCRIBER/IMPLICIT/unknown→onResync`+`...ResyncTest::catch-up set union+Semaphore(4)` | N |
| 重连 Last-Event-ID marker | T9 | `SSEClientTest::first connect no header / reconnect has slim-no-replay`（无 id 帧） | N |
| probeLatest 各分支 | T2+T6+T7+T11 | `SlimapiProbeTest::needsCatchUp*`+`SlimapiResyncTest::needsReconcile*`+`...ResyncTest::probe 404 markDeleted/empty clearLocal` | N |
| 禁裸 probe[0] | T2 | code review+probeLatestSlim 经 ProbeResult | Y |
| probeLatestMessageId skeleton&limit=1 | T2 | `EndpointsTest::probeLatestSlim hits skeleton limit=1` | N |
| schema_degraded 接受降级 | T2 | `EndpointsTest::health.schema.degraded=true→200 不 throw` | N |
| status 404 清本地 vs 503 重试 | T4+T13 | `SlimStatusFanOutTest::404 missing/503 retryable/200 busy not idle` | N |
| 假 idle 交叉验证 | T13 | `...FanOutTest::idle not in sessions→missing` | N |
| 展开 UI 批量 full+过渡单条 | T3+T15+T16 | `EndpointsTest::expand 200/fallback/413-halve/503-backoff`+`PartExpandStateTest`+`MessageRowTest::affordance` | N |
| cursor 续拉 limit=200 | T5 | `EndpointsTest::getSlimapiMessagesPage cursor drain 2 pages+dedup` | N |
| slim-mode-api-routing 对齐 | T18 | 人工 diff vs INTERFACE_MAP | Y |
| mutation 不自动重试 | T14 | `MutationTest::mutation+command retryOnConnectionFailure=false`+**接线测试 each POST** | N |
| Part.hasFull/omitted | T1 | `SlimapiV1ModelsTest::Part round-trip` | N |
| G6 envelope | T1+T3 | `ModelsTest::G6 parse`+`EndpointsTest::expand 200` | N |
| §10 回归 slim/legacy 双模式 | 全 | `EndpointsTest` legacy 分支不破+`StatusAggregatorImpl` legacy bulk 不变 | Y |
| routeToken 过期主动刷新 | — | **WAIVED（D1 OUT，Contract-Gap Ledger）** | Y |
| `./scripts/check.sh` 绿 | 全 | verifier live rerun `EXIT=0 FAILURES=0` | Y |

## Self-Review（v2）
1. **Spec coverage**: §9 八项+§10 逐行有 owner+测试名（routeToken 明确 WAIVED）。✅
2. **Placeholder**: 每 task 有完整代码/精确签名+file:line+测试名；无 TBD。✅
3. **Type consistency**: ProbeResult(T2 定义于 SlimapiProbe.kt,T7 复用)/StatusOutcome.Success(payload)/StatusFanOutSummary/ExpandOutcome/PartKey/SlimSessionState split 字段 跨 task 锁定。✅
4. **Acceptance observability**: 每条=测试名→PASS/file:line/verifier EXIT=0。✅
5. **Critical 映射**: #1→T6+T11；#2→T4+T13；#3→T13；#4→T5；#5→T3；#6→T9；#7→T12+T17；#8→§10 矩阵。Important 全覆盖。✅

## Execution Handoff
Plan v2 flat-numbered complete。ocmar 参数：`slug=slimapi-client-v1`、`base=<commit>`、`owner=ocmar-slimapi-client-v1`、`verifier=./scripts/check.sh`、SERIAL。依赖序执行 T1→T2/T3/T4/T5→[barrier]→T6/T7/T8→T9→T10→T11→T12→T13→T14→T15→T16→T17→T18。
