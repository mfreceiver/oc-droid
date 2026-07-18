# ocdroid slimapi v1 客户端实现任务书

> **来源**：拆自 `docs/slimapi-gap-contract-v1-draft.md`（v1 最终契约，三轮评审通过）。  
> **范围**：ocdroid 客户端须实现的 slimapi v1 配套。服务端变更见 oc-slimapi 侧 `docs/v1-impl-spec.md`。  
> **依赖**：B5 须等服务端 B3 契约文档冻结后开（见服务端任务书 §1 批次）。

---

## 0. 总原则

| # | 原则 |
|---|---|
| 1 | slim 模式下 **禁止**假设存在 token 级 `message.part.delta`；渲染气泡一律靠 REST skeleton/full |
| 2 | 写路径（mutation）**禁止 timeout 后自动重试**（双发风险） |
| 3 | 错误码按 thin 路由统一形状 `{"code":…}` 处理；404 = 清本地，503 = 重试 |
| 4 | 探针/展开默认 skeleton，按需 full；`Part.hasFull` 标记驱动展开 affordance |

---

## 1. 实现批次（B5，依赖服务端 B3 冻结）

```
B5  客户端配套
    ├─ SSEClient / bridge：解析 digest.lastError + event:session.error
    ├─ resync 状态机：按 §3 probeLatest/needsCatchUp 完整实现
    ├─ probeLatestMessageId → GET /slimapi/messages/{sid}?limit=1&mode=skeleton
    ├─ status 错误处理：区分 404（清本地）vs 503（重试）
    ├─ 展开 UI：支持 G6 批量 full（GET /slimapi/messages/{sid}/full?ids=）
    └─ slim-mode-api-routing.md 路径对齐 INTERFACE_MAP
```

---

## 2. SSEClient 改造（G1 配套）

### 解析新增帧

slim 模式 `/slimapi/events` 会发：

| 帧 | 字段 | 客户端行为 |
|---|---|---|
| `event: session.digest`（已有，加 `lastError`） | `lastError` 可为 object / null / 省略 | object → 该 session banner（按 `name` 决定样式）；`null` → 清除该 session banner；省略 → 不变 |
| `event: session.error`（G1-B 新增，立即推） | `sessionID?`,`directory?`,`name`,`message`,`at` | **无 sessionID** → 全局 toast；**有 sessionID** → 该 session 行/banner |

### 脱敏已服务端化

`lastError.message` 服务端已脱敏（首行 + 路径/stack/secret 裁剪 + ≤512）；客户端直接展示，无需再处理堆栈。`MessageAbortedError` 服务端已过滤，客户端不会收到 abort 误报。

### 注意

- `session.digest` 与 `event:session.error` 可能并发到达（有 sid 时 A 立即 flush + B 可选）；客户端按 `sessionID` 幂等合并。
- session-less error（无 sid）**无 durable 恢复**：断线期间发生的无 sid error 无法重放（已知限制）。

---

## 3. digest / resync 客户端状态机（G5，完整实现）

### 术语

- `focus session` = 当前打开的聊天 tab 会话
- `dirty session` = 本地已知、尚未对齐服务端状态的 session 集合
- `localMaxUpdatedAt[sessionID]` = 本地该 session 已知最大 `info.time.updated`（可不存在）
- `localLatestMessageID[sessionID]` = 本地该 session 已知最新 messageID（可不存在）
- `catch-up set` = focus ∪ 本地缓存/列表全集 ∪ dirty（resync 时使用，**不得只取 focus ∪ dirty**）

### probe 约定（客户端 MUST）

```
function probeLatest(sid):
    # 返回 {ok, empty?, messageID?, updatedAt?, httpStatus?}
    arr, status = GET /slimapi/messages/{sid}?limit=1&mode=skeleton
    if HTTP 失败 or status not in 2xx:
        return {ok:false, httpStatus:status}
    if arr 不是数组:
        return {ok:false, httpStatus:status}   # 记录 resync_probe_invalid
    if arr.length == 0:
        return {ok:true, empty:true, messageID:null, updatedAt:null}
    info = arr[0].info
    return {
        ok:true, empty:false,
        messageID: info.id,
        updatedAt: info.time.updated ?? info.time.created ?? null
    }

function needsCatchUp(sid, probe):
    if not probe.ok: return false          # 失败保留 dirty，下次再试
    if probe.empty:
        # 服务端无消息：若本地有该 sid 消息缓存则清空；已对齐则不 since
        if 本地有 sid 消息: 清空本地该 sid 消息
        return false
    localId = localLatestMessageID[sid]
    localTs = localMaxUpdatedAt[sid]
    if localId 不存在: return true
    if probe.messageID != localId: return true
    if probe.updatedAt != null and (localTs 不存在 or probe.updatedAt > localTs):
        return true
    return false
```

### 状态机主体

```
on server.connected (首次连接):
    GET /slimapi/sessions                          # 重建列表
    GET /slimapi/questions|permissions（当前 workdirs）
    catch-up set = 本地缓存全集
    for sid in catch-up set: 调用 resync 单 sid 流程（见下）

on server.connected (重连，非首次):
    # hub 无条件先发 server.connected；client 重连若不带 Last-Event-ID 只会收到此帧
    # → 必须按 resync 同级处理
    走 on resync 流程（reason=implicit）

on session.digest {sessionID, status?, messageID?, updatedAt?, archived?, deleted?, lastError?}:
    if deleted == true:
        本地移除 session 行；return
    if archived?: 标记归档
    if lastError == null: 清除该 session 的 UI banner
    elif lastError is object: UI banner（按 lastError.name 决定展示样式）
    # 决定是否拉消息（focus 才拉正文；非 focus 只刷列表行）
    if focus session == sessionID:
        if updatedAt 存在且 updatedAt > localMaxUpdatedAt[sessionID]:
            GET /slimapi/messages/{sessionID}/since/{localMaxUpdatedAt[sessionID]}
            去重（messageID 边界包含）；更新 localMaxUpdatedAt + localLatestMessageID
        elif updatedAt 不存在 or messageID != localLatestMessageID[sessionID]:
            probe = probeLatest(sessionID)
            if needsCatchUp(sessionID, probe):
                ts = localMaxUpdatedAt[sessionID] ?? 0
                GET /slimapi/messages/{sessionID}/since/{ts}
                去重；更新 localMaxUpdatedAt + localLatestMessageID
        # focus 完成 REST 对齐后才清 dirty
        dirty.remove(sessionID)
    else:
        # 非 focus：只刷列表行 status/title/updatedAt；消息懒加载 on open
        刷新列表行
        # 不在此处清 dirty；resync 时统一处理

on question.* / permission.*:
    upsert 单条 pending；或 GET 聚合刷新

on resync {reason}:   # reason ∈ {reconnect_no_replay, subscriber_backpressure, implicit}
    丢弃「事件连续」假设
    GET /slimapi/sessions                           # 列表重建（必含）
    GET /slimapi/questions|permissions（全量刷新）
    catch-up set = focus ∪ 本地缓存/列表全集 ∪ dirty
    for sid in catch-up set:
        # resync 单 sid 流程（也用于首次连接）
        probe = probeLatest(sid)
        if not probe.ok:
            if probe.httpStatus == 404:
                从本地 session 列表和 dirty 中移除 sid
            else:
                保留 sid 于 dirty   # 网络抖动等，下次重试
            continue
        if needsCatchUp(sid, probe):
            ts = localMaxUpdatedAt[sid] ?? 0
            if localMaxUpdatedAt[sid] 存在:
                GET /slimapi/messages/{sid}/since/{ts}；按 messageID 去重
            else:
                GET /slimapi/messages/{sid}?mode=skeleton&limit=200；按 cursor 分页拉至本地历史边界
            更新 localLatestMessageID[sid] + localMaxUpdatedAt[sid]
        # 无论是否 since：列表行 status 以 GET /slimapi/sessions 结果为准
        dirty.remove(sid)

on server.heartbeat:
    仅刷新 SSE watchdog

# 禁止：slim 模式下假设存在 token 级 message.part.delta
# 渲染气泡一律靠 REST skeleton/full
# 禁止：裸 probe[0] 访问（必须经 probeLatest 约定）
```

### 边界规则

- `since/{ts}` 含边界：`info.time.updated >= ts`；客户端按 `messageID` 去重边界。
- digest 无 `updatedAt` 时：focus 用 `probeLatest` 决定是否拉取。
- routeToken 1h 过期后：pending 重新拉取会换新 token。
- 重连期间新事件与 catch-up REST 结果合并：以 REST 为准（digest 只是触发器）。
- **无 durable replay 承诺**：晚订阅者看到的 lastError 依赖 resync 后状态；session-less error 断线期间发生则无法恢复。

### 性能提示（非契约）

- resync 对本地全集逐 sid probe 的 QPS/电量：可加客户端并发上限（如 4）与「仅列表可见行」策略，不改契约语义。

---

## 4. probeLatestMessageId 收敛（G3）

### 现状

客户端 `probeLatestMessageId` 走 `GET /session/{id}/message?limit=1`（可能 full 重量级）。

### v1 契约

slim 模式 **必须** 改为：

```http
GET /slimapi/messages/{sid}?limit=1&mode=skeleton
X-Slimapi-Version: 1
```

响应 `MessageWithParts[]` 长度 0 或 1；取 `info.id`、`info.time.updated`（或 `created`）。

### 诚实限制（须产品/客户端接受）

- skeleton **保留 `text` part 全文**；末条大文本时**不保证 body≤数 KB**。
- `schema_degraded=true` 时探针可能返回 full body（服务端冻结为「接受降级」，不返 503）；客户端读 `health.schema.degraded` 自行决定。
- 空会话 → `[]`（200）；不存在的 sid → 透传 upstream 状态（通常 404）。
- **不做**可选响应头；以 body 为准。
- **不做** G3-B 独立探针（v2 延后）。

---

## 5. G6 批量 full 调用（展开 UI）

### 接口

```http
GET /slimapi/messages/{sid}/full?ids=msg1,msg2,msg3&mode=full
X-Slimapi-Version: 1
```

- `ids`：逗号分隔 messageId，**1–20**；去重保序。
- `mode`：`skeleton` | `full`（默认 full）。
- `directory`：query，G7-soft 校验。

### 响应（envelope）

```json
{
  "items": [ { "/* MessageWithParts */": "…" } ],
  "errors": [
    { "messageID": "msg_missing", "code": "message_not_found" }
  ]
}
```

### 客户端处理

| HTTP | 含义 | 客户端行为 |
|---|---|---|
| **200** | session 存在；mid 部分失败进 `errors[]` | 按 `messageId+partId` 替换本地缓存；errors 内 mid 标记展开失败 |
| **400** `invalid_ids` | ids 空/超限/解析错 | 修正请求重试 |
| **422** | ids 缺失 | 编程错误，告警 |
| **404** `session_not_found` | 整 session 不存在 | 清本地 session（与 G2 一致） |
| **413** `response_too_large` | 累计超限 | 减少 ids 数量重试 |
| **503** `transform_busy`/`upstream_unavailable` | 暂时不可用 | 退避重试 |

**MUST**：
- `items` 严格按请求 `ids` 去重后顺序（服务端保证）；客户端按序 merge。
- 用于「展开多条思考/工具」：skeleton 多条 `hasFull` 时一次批量展开，降低 RTT。
- **过渡策略**：可先用 N 次并行旧单条 `full/{mid}` 兼容未升级服务端，检测到新端点 404 时回退。

---

## 6. status 错误处理（G2 配套）

`GET /slimapi/sessions/{sid}/status` 错误码分流：

| HTTP | 客户端行为 |
|---|---|
| **404** `session_not_found` | **清本地缓存**（会话已删）；从列表移除 |
| **400** `directory_not_allowed` | directory 配置错误，提示用户 |
| **502** `upstream_http_N` | 上游 4xx，告警，不删本地 |
| **503** `upstream_unavailable` | **退避重试**（暂时故障） |
| **200** `{"type":"idle"}` | 正常；注意假 idle 风险（session 已删但 status map 滞后），结合 sessions 列表交叉验证 |

---

## 7. slim-mode-api-routing.md 路径对齐

更新 `docs/slim-mode-api-routing.md`：

- 路径与 oc-slimapi `INTERFACE_MAP.md` 完全对齐（去掉过时 A 桶路径如 `/slimapi/sessions/{sid}/messages`、`/slimapi/sessions/{sid}/latest-message-id`）。
- 写入 G4 透传矩阵（见服务端任务书 §10）：明确哪些走 thin、哪些走 catch-all 透传、哪些不支持。
- 标注 mutation（POST）**禁止 timeout 自动重试**。

---

## 8. Part.hasFull / omitted 模型（沿用 CLIENT_CHANGES 既有要求）

`Part` 扩字段：
- `hasFull: Boolean? = null`
- `omitted: List<String>? = null`

`hasFull && omitted` 的 part → 首次展开走 G6（或旧单条 `full/{mid}` 过渡）→ 按 `messageId+partId` 替换；loading/失败内联状态。

---

## 9. 验收清单

- [ ] SSEClient 解析 `digest.lastError`（object/null/省略三态）+ `event:session.error`（有/无 sid）
- [ ] resync 状态机：三 reason（reconnect_no_replay / subscriber_backpressure / implicit 重连不带 Last-Event-ID）都走列表重建 + catch-up 集
- [ ] probeLatest：空会话 / HTTP 失败 / 404 移除 sid / 无 localMaxUpdatedAt 全覆盖；禁止裸 `probe[0]`
- [ ] probeLatestMessageId 走 `skeleton&limit=1`；schema_degraded 接受降级
- [ ] status 404 清本地 vs 503 重试分流
- [ ] 展开 UI 支持批量 full；过渡兼容旧单条
- [ ] slim-mode-api-routing.md 路径与 INTERFACE_MAP 对齐
- [ ] mutation 不自动重试（prompt_async/abort/summarize/permission/question response）

---

## 10. 测试矩阵

| 区域 | 用例 |
|---|---|
| SSE 解析 | digest lastError object/null/省略；session.error 有/无 sid；并发到达幂等 |
| resync | 三 reason；catch-up 集 = focus ∪ 本地全集 ∪ dirty；非 focus 列表刷新；routeToken 过期 |
| probeLatest | 空会话；HTTP 失败保留 dirty；404 移除 sid；无 localMaxUpdatedAt 用 limit=200 分页 |
| digest focus | updatedAt 推进 since；无 updatedAt 走 probe；messageID 不匹配走 probe |
| G6 调用 | items 定序 merge；部分失败 errors；整 session 404 清本地；413 减 ids 重试；503 退避 |
| G2 status | 404 清本地；503 重试；假 idle 交叉验证 |
| 回归 | slim/legacy 双模式；既有 routeToken/since/skeleton 调用 |
