# ocdroid slimapi v1 客户端实现 — Design Spec

> **日期**：2026-07-18
> **状态**：待用户确认（🔑 spec gate）
> **来源契约**：`docs/slimapi-client-impl-v1.md`（B5 客户端配套，派生自"v1 最终契约，三轮评审通过"）
> **服务端基准**：`/home/mar/personal_projects/oc-slimapi/docs/INTERFACE_MAP.md`（sibling repo，跨 repo 可读）
> **开发模式**：客户端与服务端**并行开发**，分别完成后**联合调试**。本流程只覆盖客户端 B5。

---

## 1. 原始需求

> "注意当前 slimapi 还没开发，准备和你并行开发，随后分别完成后共同联合调试。请按文档开展开发，有条件的环节可并发 2 个 fixer。"

## 2. 明确后的范围

### 2.1 做什么
按 `docs/slimapi-client-impl-v1.md` 任务书 B5 批次**全量实现客户端 slimapi v1 配套**：G1（SSE 新帧）、G2（status 错误路由）、G3（probe 收敛）、G5（resync 完整状态机）、G6（批量 full 展开）、Part 模型扩字段、G4（docs 路径对齐）、mutation no-retry。

### 2.2 不做什么（边界）
- **OUT**：真服务端 `connectedDebugAndroidTest`（推迟到联合调试阶段）。
- **OUT**：routeToken 1h 过期**主动刷新**（§3 边规 line 177，非 §9 验收项；无真服务端难单测 token-expired 路径）。现有 SSE→REST race-fix 保留。
- **OUT**：服务端任何变更（oc-slimapi 侧独立开发）。

### 2.3 契约稳定性假设（决策记录）
**任务书视为冻结契约**（用户决策 Q1=A）。客户端按文档全量实现所有 REST 端点。若服务端实现期间偏离契约，偏离留到联合调试阶段处理。契约本体 `slimapi-gap-contract-v1-draft.md` 不在本仓库，以 impl 任务书 + INTERFACE_MAP.md 为真相源。

### 2.4 成功标准（决策记录 Q2=A）
- **ocmar verifier**：`./scripts/check.sh`（编译 + 单测绿）。
- 新增逻辑全部有单测覆盖（纯逻辑常规单测；REST 用 MockWebServer + fixture JSON 匹配契约响应形状）。
- 任务书 §9 验收清单 8 项全过。

## 3. 总原则（任务书 §0，不可违反）

1. slim 模式下**禁止**假设存在 token 级 `message.part.delta`；渲染气泡一律靠 REST skeleton/full。
2. 写路径（mutation）**禁止** timeout 后自动重试（双发风险）。
3. 错误码按 thin 路由统一形状 `{"code":…}`；404 = 清本地，503 = 重试。
4. 探针/展开默认 skeleton，按需 full；`Part.hasFull` 标记驱动展开 affordance。

## 4. 关键决策（brainstorming 确认）

| # | 决策 | 选择 | 理由 |
|---|---|---|---|
| Q1 | 契约稳定性 | A. 任务书=冻结契约，全量实现 | 用户"按文档开展开发" |
| Q2 | 完成门槛 | A. 单测级绿（`check.sh`） | 无真服务端；YAGNI |
| D1 | routeToken 过期主动刷新 | 推迟到联合调试 | 非 §9 验收；无服务端难单测 |
| D2 | error banner UI 面 | 复用扩展 SessionRetryCard + 列表行 | YAGNI，复用现有 surface |
| D3 | mutation no-retry | POST 专用 OkHttpClient，`retryOnConnectionFailure(false)` | 精准；不影响 GET 容错 |

## 5. 架构（6 层，依赖单向）

```
L0 模型/契约常量
    ├─ Part 加 hasFull:Boolean?=null, omitted:List<String>?=null  (Message.kt:195-288)
    ├─ SlimSessionDigest 加 lastError 三态字段                     (Slimapi.kt:124-133)
    ├─ resync.reason 枚举 {reconnect_no_replay, subscriber_backpressure, implicit}
    ├─ G6 batch envelope DTO {items:List<MessageWithParts>, errors:List<MessageBatchError>}
    └─ slimapi error code 常量集（session_not_found / directory_not_allowed /
       upstream_http_N / upstream_unavailable / invalid_ids / response_too_large /
       transform_busy / message_not_found）
        ↓
L1 REST 端点（Retrofit 接口 OpenCodeApi.kt + Repository wrapper）
    ├─ G3: getSlimapiMessages({sid}, mode=skeleton, limit=1) → 返回 MessageWithParts[]
    ├─ G6: getSlimapiMessagesFullBatch({sid}, ids, mode=full) → envelope（+ 单条 full/{mid} 过渡）
    ├─ G2: getSlimapiSessionStatus({sid}) → 新端点
    └─ G5: getSlimapiMessages 加 mode query；coldStartSlimSync cursor 续拉 limit=200
        ↓
L2 纯逻辑 / reducer（新 seam 文件 + 既有 reducer）
    ├─ SlimSseReducer.reduceSlimDigest 加 lastError 三态 merge (SlimSseReducer.kt:117)
    ├─ SlimapiProbe.kt（新）: probeLatest(sid) + needsCatchUp(sid, probe) 纯函数
    ├─ catch-up set 计算（focus ∪ 本地全集 ∪ dirty）
    ├─ G6 items 定序 merge + 按 messageId+partId 去重
    ├─ status 错误路由纯逻辑（404清/503退避/502告警/400提示）
    └─ resync.reason 三分支决策（→ resync 单 sid 流程）
        ↓
L3 wiring
    ├─ SSEClient 重连带 Last-Event-ID header           (SSEClient.kt:138-159)
    ├─ ServiceSseConnectionOwner 解析 resync.reason    (ServiceSseConnectionOwner.kt:588-598)
    ├─ SessionSyncCoordinator:
    │    ├─ digest lastError 三态 → banner             (SessionSyncCoordinator.kt:1521)
    │    ├─ session.error sid→banner / no-sid→toast 路由 (SessionSyncCoordinator.kt:1168)
    │    └─ resync 流 → catch-up set 迭代 (probeLatest/needsCatchUp)
    ├─ StatusAggregatorImpl HTTP code 分流             (StatusAggregatorImpl.kt:239-291)
    ├─ ProcessStatusPoller 503 退避                    (ProcessStatusPoller.kt:80-179)
    └─ mutation 专用 OkHttpClient（retryOnConnectionFailure=false）(OkHttpClientFactory.kt)
        ↓
L4 UI
    ├─ SessionRetryCard 扩展吃 lastError（按 lastError.name 决定样式）(SessionRetryCard.kt)
    ├─ 非 focus session 列表行 status/icon 指示
    └─ 展开 affordance: skeleton part hasFull&&omitted → "展开省略内容" → G6 批量 →
       按 messageId+partId 原地替换 + 内联 loading/failed（走 ui-style-spec 三层规则）

L5 docs（独立，任意时刻可并发） slim-mode-api-routing.md
    ├─ §2/§5/M8/M9 路径对齐到扁平 /slimapi/messages/{sid}/*（删过时嵌套）
    ├─ 新建 G4 透传矩阵（thin / catch-all 透传 / 不支持）
    └─ mutation POST 禁 timeout 自动重试 写成显式 must
```

## 6. 各 G 区详细 spec

### 6.1 G1 — SSE 新帧解析
- `SlimSessionDigest` 加 `lastError: SlimSessionLastError?`（含省略态：null=清除 banner；字段缺失=不变）。`SlimSessionLastError{name, message, at?}`。
- `SlimSseReducer.reduceSlimDigest` 三态 merge：object→设 banner；null→清 banner；省略→保留旧值。
- `session.error` 帧：`{sessionID?, directory?, name, message, at}`。
  - **有 sessionID** → 该 session banner（durable in-session）。
  - **无 sessionID** → 全局 toast（无 durable 恢复，已知限制）。
- 当前 `SessionSyncCoordinator:1168-1203` 是 toast-always + 可选 inline，需改为 sid-banner vs no-sid-toast 分流。
- `lastError.message` 服务端已脱敏（首行 + 路径/stack/secret 裁剪 + ≤512），客户端直接展示。
- `MessageAbortedError` 服务端已过滤，客户端不收 abort 误报。

### 6.2 G3 — probeLatestMessageId 收敛
- `OpenCodeRepository.probeLatestMessageId(sessionId)`（`:889-893`，当前走 legacy `session/{id}/message?limit=1`）：
  - slim 模式分支改走 `GET /slimapi/messages/{sid}?limit=1&mode=skeleton`（带 `X-Slimapi-Version: 1`，已由 `SlimapiVersionInterceptor` 注入）。
  - 取 `info.id` **和** `info.time.updated`（当前只取 id，缺 updated）。
  - 响应 `MessageWithParts[]` 长度 0 或 1。
- `getSlimapiMessages` API 形状补 `mode: String` query 参数。
- 诚实限制：skeleton 保留 `text` part 全文（末条大文本不保证 body≤数 KB）；`schema_degraded=true` 接受降级（读 `health.schema.degraded`，不返 503）；空会话→`[]`（200）；不存在的 sid→透传 upstream（通常 404）；不做 G3-B 独立探针（v2 延后）。

### 6.3 G5 — resync 完整状态机
- `ServiceSseConnectionOwner:588-598` 当前只比 `type=="resync"`，需解析 `reason ∈ {reconnect_no_replay, subscriber_backpressure, implicit}`，三分支都走列表重建 + catch-up 集。
- `SSEClient` 重连需带 `Last-Event-ID` header（当前不带）。重连不带 Last-Event-ID 时服务端只发 `server.connected` → client 必须按 resync reason=implicit 同级处理。
- 新建 `SlimapiProbe.kt` 纯函数：
  - `probeLatest(sid): {ok, empty?, messageID?, updatedAt?, httpStatus?}` — 严格按任务书 §3 伪码，**禁止裸 `probe[0]`**。
  - `needsCatchUp(sid, probe): Boolean` — 按任务书 §3 伪码（probe.ok 失败保留 dirty；empty 清本地；messageID 不匹配 / updatedAt 推进 → true）。
- catch-up set = **focus ∪ 本地缓存/列表全集 ∪ dirty**（当前 `sessionsDirty` 只用于 scenario-3，`sessionsEverColdSnapshotted` 是 SSE-coverage 基线，都不是 catch-up set，需新建统一概念）。
- resync 单 sid 流程（首次连接 + 重连共用）：probe → 404 移除 sid / 失败保留 dirty / needsCatchUp 则 since 或 skeleton&limit=200 cursor 分页 → 更新 localLatestMessageID + localMaxUpdatedAt → dirty.remove。
- `coldStartSlimSync`（`:1547-1600`）当前 `limit=50` 单页无 cursor，需改 `limit=200` + cursor 续拉至本地历史边界（spec line 160）。
- `since/{ts}` 含边界 `info.time.updated >= ts`，客户端按 messageID 去重边界。
- 性能（非契约）：resync 逐 sid probe 可加并发上限（如 4）+ "仅列表可见行"策略，不改契约语义。

### 6.4 G2 — status 错误路由
- 新增 `GET /slimapi/sessions/{sid}/status` per-session 端点（当前只有 host-wide `session/status`，`OpenCodeApi.kt:57-58`）。
- `StatusAggregatorImpl.refresh`（`:239-291`）当前对所有 HTTP 失败一视同仁 `markRequestFailedInternal` → 全局 Unknown，需按 code 分流：

| HTTP | code | 客户端行为 |
|---|---|---|
| 404 | session_not_found | **清本地缓存**（会话已删），从列表移除 |
| 400 | directory_not_allowed | directory 配置错误，提示用户 |
| 502 | upstream_http_N | 上游 4xx，告警，**不删本地** |
| 503 | upstream_unavailable | **退避重试**（暂时故障） |
| 200 | `{"type":"idle"}` | 正常；注意假 idle 风险（session 已删但 status map 滞后），结合 sessions 列表交叉验证 |

- `ProcessStatusPoller`（`:80-179`）需加 503 退避策略（当前 30s 固定轮询，无错误码退避）。

### 6.5 G6 — 批量 full 展开
- `OpenCodeApi` 加 `GET /slimapi/messages/{sid}/full?ids=m1,m2,…&mode=full`（ids 逗号分隔 1–20，去重保序；`directory` query G7-soft 校验）。
- envelope `{items: List<MessageWithParts>, errors: List<{messageID, code}>}`（参考既有 `SlimapiQuestionAggregation` 模式）。
- HTTP 处理：200（items 按 ids 去重后顺序 merge；errors 内 mid 标记展开失败）/ 400 invalid_ids（修正重试）/ 422（ids 缺失，编程错误告警）/ 404 session_not_found（清本地，与 G2 一致）/ 413 response_too_large（减 ids 重试）/ 503 transform_busy·upstream_unavailable（退避重试）。
- **过渡策略**：检测到新端点 404 时回退到 N 次并行旧单条 `full/{mid}`（`getSlimapiMessageFull:1409`，当前孤儿零调用）。
- `Part` 加 `hasFull:Boolean?=null`、`omitted:List<String>?=null`。`hasFull && omitted` 的 part → 首次展开走 G6 → 按 `messageId+partId` 替换本地缓存；loading/失败内联状态。
- 新建 `PartExpandState`（per `messageId+partId`：idle/loading/loaded/failed），**不复用** `MessageRow` 现有的 `expandedParts/onToggleExpand`（那是折叠 tool run 的同名异义）。state slice lift 到 `ChatViewModel`/`SessionSyncCoordinator`（与 `streamingPartTexts` 同层）。

### 6.6 mutation no-retry（§9.8）
- `OkHttpClientFactory` 当前未显式设置 `retryOnConnectionFailure`（OkHttp 默认 `true`，对不可重做 POST 是双发隐患）。
- **新建 mutation 专用 OkHttpClient**，`retryOnConnectionFailure(false)`，给所有 POST 用（prompt_async/abort/summarize/permission respond/question reply·reject）。GET client 保留默认 true。
- 应用层 `runSuspendCatching` 单次 wrap 无 loop/backoff（已满足），不动。

### 6.7 G4 — docs 路径对齐
- `docs/slim-mode-api-routing.md`（823 行）当前 §2/§5/M8/M9 用过时嵌套路径 `/slimapi/sessions/{sid}/messages*`，与 code + INTERFACE_MAP 已迁到的扁平 `/slimapi/messages/{sid}/*` 漂移。对齐到 INTERFACE_MAP。
- 删过时 A 桶路径：`/slimapi/sessions/{sid}/messages`、`/slimapi/sessions/{sid}/messages/since`、`/slimapi/sessions/{sid}/messages/{mid}`、`/slimapi/sessions/{sid}/latest-message-id`。
- **新建 G4 透传矩阵**（thin / catch-all 透传 / 不支持），把散落在 §2/§5.3/§6.2 的信息统一成一张表。
- mutation POST "禁止 timeout 自动重试" 在 §3 或 §6 显眼处写成 must（当前仅 M19 顺带软注）。

## 7. 测试策略（对齐 Q2=A）

- **L0 模型**：序列化/反序列化单测（三态 lastError、hasFull/omitted nullable + `explicitNulls=false` 兼容、G6 envelope）。
- **L1 REST**：MockWebServer + fixture JSON，覆盖每端点的成功 + 各错误码（404/503/502/400/413/422 + envelope `{items,errors}` + 三态 lastError）。
- **L2 纯逻辑**：常规单测驱动状态机（probeLatest 空会话/HTTP 失败/404；needsCatchUp 各分支；catch-up set 并集；G6 items 定序 merge + 去重；status 路由；resync.reason 三分支）。
- **L3 wiring**：单测验证 reason 解析、Last-Event-ID 注入、HTTP 分流、mutation client 配置（`retryOnConnectionFailure=false`）。
- **L4 UI**：先单测 state slice（PartExpandState per messageId+partId）；Compose UI 测试可选。
- **回归**：slim/legacy 双模式；既有 routeToken/since/skeleton 调用不破。
- **verifier**：`./scripts/check.sh`。

## 8. 任务分解（粗，plan 阶段细化）

依赖序：L0 → L1 → L2 → L3 → L4；L5 docs 独立可并发。

**可并发点**（用户"有条件的环节可并发 2 个 fixer"）：
- L2 纯逻辑层内可拆双 fixer（SSE-reducer 族 vs REST-merge/路由族），新 seam 文件不冲突。
- L2/L3 跑时并行 L5 docs（独立文件）。
- L1 共享 `OpenCodeApi.kt`/`OpenCodeRepository.kt` → **不宜并发**，串行。

## 9. 验收标准（任务书 §9，逐项）

- [ ] SSEClient 解析 `digest.lastError`（object/null/省略三态）+ `event:session.error`（有/无 sid）
- [ ] resync 状态机：三 reason（reconnect_no_replay / subscriber_backpressure / implicit 重连不带 Last-Event-ID）都走列表重建 + catch-up 集
- [ ] probeLatest：空会话 / HTTP 失败 / 404 移除 sid / 无 localMaxUpdatedAt 全覆盖；禁止裸 `probe[0]`
- [ ] probeLatestMessageId 走 `skeleton&limit=1`；schema_degraded 接受降级
- [ ] status 404 清本地 vs 503 重试分流
- [ ] 展开 UI 支持批量 full；过渡兼容旧单条
- [ ] slim-mode-api-routing.md 路径与 INTERFACE_MAP 对齐
- [ ] mutation 不自动重试（prompt_async/abort/summarize/permission/question response）
- [ ] `./scripts/check.sh` 绿

## 10. 风险 + 已知限制

- **契约偏差风险**：服务端并行开发可能偏离任务书；联合调试阶段才发现。缓解：Q1=A 全量实现，契约收口在 Retrofit 接口边界，偏差时改动局部。
- **routeToken 过期主动刷新未实现**（D1 推迟）；联合调试阶段补。
- **session-less error 无 durable 恢复**：断线期间发生的无 sid error 无法重放（任务书已知限制）。
- **slim 模式假设**：所有新逻辑在 `isSlimMode`（`hostConfig.slim`）分支下生效；legacy 路径不动。
- **doc-code-INTERFACE_MAP 三方漂移**：当前 doc 落后；本流程对齐 doc 到 code+INTERFACE_MAP。

## 11. 可审计引用

- 任务书：`docs/slimapi-client-impl-v1.md`
- 服务端基准：`/home/mar/personal_projects/oc-slimapi/docs/INTERFACE_MAP.md`
- 路由 doc：`docs/slim-mode-api-routing.md`
- SSE spec：`docs/sse-client-spec.md`
- UI 规范：`docs/ui-style-spec.md`
- 现状侦察：explorer exp-1（SSE/digest/resync）、exp-2（REST）、exp-3（Part/UI/docs）
