# oc-slimapi 缺口补齐契约 v1-rc1

> **状态**：v1-rc1（吸收三专家复审 R1–R10；待第二轮复审 → 通过后转 v1 实现基线）  
> **日期**：2026-07-18  
> **前序**：`docs/slimapi-gap-contract-review-2026-07-18.md`（复审汇总）、`docs/api-requirements-review-2026-07-18.md`（需求三评）  
> **实现真源**：`/home/mar/personal_projects/oc-slimapi/INTERFACE_MAP.md` + `docs/design-v2.md`  
> **范围**：slimapi **应补充 / 应调整** 的 thin 契约。已存在且无需改的端点不展开。  
> **v1 非目标**：不 thin 化写路径；不做 queue API；不做 WebSocket；不做二进制帧；**不做服务端 focus SSE**；不做 G3-B 独立探针。

---

## 0. 总原则

| # | 原则 |
|---|---|
| 1 | **读热路径 thin，写路径透传**；catch-all 写路径升格为正式支持面（G4） |
| 2 | **形状兼容**：消息仍是 `MessageWithParts` 裸数组/对象；不改 `info`；skeleton 规则沿用 `skeleton.py` |
| 3 | **版本策略**：本契约 v1 全部为加性变更（不 bump `X-Slimapi-Version`）；v2 项明确标「需 bump」 |
| 4 | 无 SQLite / loopback / stunnel mTLS 边界（与现网相同） |
| 5 | **SSE 只走控制面**；内容体靠 REST；v1 单全局 `/slimapi/events` |
| 6 | **错误可区分**：`404 not_found` ≠ `503 upstream`；session 级 error 不得静默丢 |
| 7 | **校验源单一**：v1 只认 query `directory`；header/query 冲突 → 400 |
| 8 | **写路径禁止自动重试**（mutation timeout 不得双发） |

---

## 1. 版本路线（v1 / v2 / 永不做）

### v1（本契约，加性，不 bump）

| ID | 名称 | 类型 | 批次 |
|---|---|---|---|
| **G1** | error 可见性（digest.lastError + session-less 帧） | 代码 | B2 |
| **G2** | status 404/503 分离 | 代码 | B1 |
| **G3** | latest 探针收敛到 `skeleton&limit=1` | 文档/契约 | B3 |
| **G4** | catch-all 透传矩阵 + shell deny-list | 文档 | B3 |
| **G5** | digest/resync 客户端可执行状态机 | 文档 | B3 |
| **G6** | multi-mid 批量 full（`full?ids=`） | 新端点 | B4 |
| **G7-soft** | messages directory allowlist（仅 query，提供才校验） | 代码 | B1 |
| **G8** | `full/{mid}` 流式 cap（边读边 413） | 代码 | B1 |
| — | 统一错误码表 | 文档 | B3 |

### v2（延后，需 RFC + 多数需 bump）

| ID | 名称 | 触发条件 | 是否 bump |
|---|---|---|---|
| **G3-B** | 独立 latest-message 探针（info-only 真有界） | 压测证明 G3-A 仍过重 | 否 |
| **G7-strict** | messages directory 必填 | 客户端迁移完成 | 是（破坏性） |
| **G9** | 服务端 SSE `?focus=` | 实测带宽收益 > 列表盲区成本 | 否（加性 query） |
| **G10** | `since/{ts}?until=` 时间窗上界 | 按日/审计产品需求 | 否 |
| **G11** | `around/{mid}?before&after` id 邻域 | 搜索/跳转产品需求 | 否 |
| **G12** | 批式 coalesced text delta（打字机） | 与省流 KPI 重定义绑定 | **是** |
| **G13** | durable sequence replay（桥接 opencode v2） | opencode v2 API 稳定 | 是 |

### 永不做（拒绝清单）

| 需求 | 原因 | 场景如何满足 |
|---|---|---|
| `POST` 排队消息 thin | 上游无 queue | 客户端本地队列 + 串行 `prompt_async` |
| 全量 token SSE 经 slim | 与省流核心冲突 | since/full 回补；要动画走 v2 G12 |
| sessions 改 cursor 分页 | legacy 用 start，非瓶颈 | `start`+`limit` 继续 |
| 写路径全面 thin 包装 | 双实现漂移 | G4 透传矩阵文档化 |
| WebSocket / PTY | sidecar WS→501 | SSE 继续 |
| 独立 error REST 轮询 | 避免第三通道 | G1 SSE + 必要时 full 消息体 |
| shell 经 catch-all 开放 | 安全 | **默认 deny-list 配置**（见 G4） |

---

## 2. v1 实现批次与顺序

```
B0  前提验证（go/no-go）
    └─ 验证 opencode session.error 是否出现在 /global/event
       （G1 的硬前提；若否 → G1 改走 REST 补 error，整个 B2 重设计）

B1  服务端 P0 修复（稳定性 + 错误语义，相互独立，可并行 PR）
    ├─ G2  status 404/503 分离
    ├─ G8  full/{mid} 流式 cap
    └─ G7-soft  messages allowlist（query 校验）

B2  服务端 error 通道（依赖 B0 验证通过）
    └─ G1  digest.lastError + session-less 帧（分类/脱敏/sticky/clear/立即 flush）

B3  契约文档（依赖 B1/B2 定型）
    ├─ G3  latest 探针契约（诚实表述）
    ├─ G4  透传矩阵 + shell deny-list + 超时表 + 写路径禁止自动重试
    ├─ G5  可执行客户端状态机
    └─ 统一错误码表

B4  服务端 P1 新端点
    └─ G6  multi-mid full（envelope，MUST 定序）

B5  客户端配套（ocdroid，契约联动）
    ├─ SSEClient 解析 lastError；resync 状态机按 G5
    ├─ probeLatestMessageId → skeleton&limit=1
    ├─ 展开 UI 支持 G6 批量 full
    └─ slim-mode-api-routing.md 路径对齐 INTERFACE_MAP
```

**关键依赖**：B2 依赖 B0；B3 依赖 B1+B2 定型；B5 依赖 B3 契约冻结。B1 三项相互独立可并行。

---

## 3. 场景总表（为什么需要）

| ID | 用户/产品场景 | 现网缺口 | v1 补什么 | 不做会怎样 |
|---|---|---|---|---|
| G1 | Agent 跑挂、provider 失败、权限拒绝导致 error | slim SSE **DROP** `session.error`；UI 一直 busy 或静默无反馈 | digest.`lastError` + session-less 帧 | slim 模式错误不可见，用户瞎等或杀会话 |
| G2 | 打开已删/错 sid 看状态 | 404 被包成 503，无法区分「不存在」vs「上游挂」 | 404 vs 503 分流 | 误报「服务不可用」、错误重试、状态机错乱 |
| G3 | 回前台 catch-up：只需「有没有新消息」 | `message?limit=1` 可能拉整包 parts | 契约收敛到 `skeleton&limit=1` | 每次探针浪费带宽/电量 |
| G4 | fork/command/file/vcs 等清单外能力 | 功能靠 catch-all 能用但契约未写明 | 正式透传矩阵 + shell deny-list | 误做 thin 双实现，或产品以为「没接口」 |
| G5 | 断线重连、digest 后如何拉内容 | 服务端有 resync/digest，客户端行为未写死 | 可执行客户端状态机 | gap 后消息/pending 不一致 |
| G6 | 一次展开多条「思考/工具」 | 只能 N 次 `full/{mid}`，RTT×N | `full?ids=` 批量 | 滚动展开卡顿、省流打折 |
| G7 | 跨项目误带 directory 读消息 | messages **不校验** allowlist | 提供 query directory 时校验 | 串目录/越权读（弱边界） |
| G8 | 单条消息极大（大 tool output） | 先整包下载再 413 | 边读边 cap | sidecar OOM / 内存尖刺 |

---

## 4. v1 规范

### G1. error 可见性

#### 场景

| 场景 | 说明 |
|---|---|
| 生成失败 | 模型/provider 报错，会话进入 error；需立刻看到失败原因 |
| 后台会话挂了 | 多会话非当前 tab 失败，列表/通知需标红或 toast |
| 权限/工具链失败 | 部分失败以 `session.error` 而非 question 形式出现 |
| slim 切换回归 | legacy 有 `session.error` 路径；slim 切后被 DROP → 功能回退 |

#### 硬约束（R1/R2，必须）

1. **B0 前提**：实现前验证 `session.error` 是否到达 slim 订阅的 `GET /global/event`；**若否 → G1 改由 REST 补 error，不做 hub**。
2. **不可仅 A**：opencode `session.error.sessionID` 可为 optional（plugin/skill 实发无 sid），A-only 会静默丢 session-less error。
3. **排除主动中止**：`MessageAbortedError` 及等价 abort **不得**写入 lastError / error 帧（否则每次中止亮 banner）。
4. **脱敏**：`message` **禁止**使用 `Cause.pretty` 全文（多行堆栈/含路径）；默认 `name` + 首行/≤512 截断 + 路径/堆栈样式裁剪。
5. **sticky + clear**：lastError 跨 debounce 窗口 sticky，直到 clear；error 到达时 **立即 flush** digest（不等 250ms 窗口）。
6. **schema 映射**（待核对 Assistant.error schema）：优先 `error.name`、`error.data.message`，非 `error.message`。

#### 方案：G1-A + G1-B 组合

**G1-A（digest 加性，承载有 sid 的 error）**

```json
{
  "sessionID": "ses_…",
  "directory": "/abs/path",
  "status": "idle",
  "messageID": "msg_…",
  "updatedAt": 1710000000000,
  "lastError": {
    "name": "UnknownError",
    "message": "short human-readable",
    "at": 1710000000000
  }
}
```

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `lastError` | object \| 省略 | 否 | 仅当窗口内出现过非-abort `session.error` 时出现；sticky 到 clear |
| `lastError.name` | string | 是 | 来自上游 `error.name` / 类型名，截断 ≤128 |
| `lastError.message` | string | 是 | 人类可读摘要，截断 ≤512；**禁止**堆栈/路径/pretty-cause |
| `lastError.at` | int epoch ms | 否 | 事件到达时间或上游 time |

**clear 规则（实现二选一写死，建议前者）**：
- 同 session 出现新 `status=busy` 且无并发 error → 清除 lastError；**或**
- digest 显式携带 `"lastError": null`（加性，兼容旧客户端忽略）。

**G1-B（session-less / 即时精简帧，承载无 sid 的 error）**

```text
event: session.error
data: {"sessionID"?,"directory"?,"name","message","at"}
```

- 立即推送（不进 250ms debounce）。
- **仅当 upstream error 无 sessionID 时必须走 B**（或全局 lastError 桶），不可丢。
- 有 sid 时：A + 立即 flush 即可，B 可选（避免双通道）。
- 同样排除 abort；同样脱敏规则。

#### 上游映射

| upstream `/global/event` payload | slim 行为 |
|---|---|
| `type == session.error` 且 `name == MessageAbortedError`（abort） | **静默丢弃**（正常中止） |
| `type == session.error` 且有 sessionID | A 立即 flush；B 可选 |
| `type == session.error` 且无 sessionID | **必须** B 帧（或全局桶） |
| 其它已 DROP 类型 | 不变 |

#### 验收

- 触发 session 失败 → slim 订阅者立即（不等 250ms）看到 lastError 或 session.error 帧。
- 主动 abort → 不产生 lastError / session.error 帧。
- frame message 不含堆栈/绝对路径/secrets。
- 晚订阅/重连场景由 G5 resync 状态机覆盖（无 durable replay 承诺）。

---

### G2. `GET /slimapi/sessions/{sid}/status` 错误语义

#### 场景

| 场景 | 说明 |
|---|---|
| 会话已删除 | 从通知/深链打开已删 sid，应明确 404 并清本地缓存 |
| 上游短暂故障 | 真 503 应重试/退避，而非当「不存在」删本地 |
| 未读/status 回补 | status 错码错会导致未读 soak 误判 |

#### 目标

| 条件 | HTTP | body |
|---|---|---|
| upstream 404 / 明确 not found | **404** | `{"code":"session_not_found","sessionID":"…"}` |
| directory ∉ allowlist（query 提供） | **400** | `{"code":"directory_not_allowed"}` |
| upstream 超时 / 5xx / JSON 坏 | **503** | `{"code":"upstream_unavailable"}` |
| 成功但 map 无 sid | **200** | `{"type":"idle"}`（保持） |

#### 实现要点（R9）

- 现状 `sessions.py:121-131` 用 `raise_for_status()` + `except Exception → 503`，**同时吞掉 allowlist 400 和 upstream 404**。
- 修法：**re-raise `HTTPException`**（保留 allowlist 400）；对 upstream 用 `HTTPStatusError` 精确判 404；仅网络/5xx/解析失败 → 503。
- 批量 `GET /slimapi/sessions/status` 语义不变。

---

### G3. latest 消息 id + 时间探针（契约收敛）

#### 场景

| 场景 | 说明 |
|---|---|
| App 回前台 | 判断「自 lastSeen 后有无新消息」决定是否 since |
| SSE gap 后 | resync 后对 dirty session 廉价探测 |
| 列表角标 | 仅需最新 messageId/时间 |

#### v1 契约（G3-A，不新增端点）

```http
GET /slimapi/messages/{sid}?limit=1&mode=skeleton
X-Slimapi-Version: 1
```

响应：`MessageWithParts[]` 长度 0 或 1；取 `info.id`、`info.time.updated`（或 `created`）。

#### 诚实限制（R6）

- skeleton **保留 `text` part 全文**；末条若为大段 assistant 文本，**不保证 body≤数 KB**。省的是 tool/reasoning/file 等大字段。
- 前提：upstream 分页默认「最新优先」——须集成测试确认。
- `schema_degraded=true` 强制 full 时探针变重；客户端读 `health.schema.degraded`，按「接受降级」或「探针 503」二选一写死。
- 空会话 → `[]`（200）；不存在的 sid → 由 G7/G2 决定（404 或 503）。
- **不做**可选响应头（避免双 latest 契约）；以 body 为准。

---

### G4. catch-all 透传矩阵（文档-only）

#### 场景

把透传能力升格为契约的一部分；区分 thin 省流 vs 透传必达；避免后人误做 thin 双实现。

#### 正式支持面（经 catch-all → opencode，**不经版本门闩**）

| 能力 | sidecar path | 方法 | directory | read timeout | 可重试 | 备注 |
|---|---|---|---|---|---|---|
| 新建会话 | `/session` | POST | header | 30s | **否**（mutation） | — |
| 改标题/归档 | `/session/{id}` | PATCH | header | 30s | **否** | body 含 title / time.archived |
| 删除会话 | `/session/{id}` | DELETE | header | 30s | **否** | 破坏性 |
| 子会话 | `/session/{id}/children` | GET | header | 30s | 是 | — |
| 发送消息 | `/session/{id}/prompt_async` | POST | header | 30s | **否**（timeout 禁双发） | 主发送路径 |
| 中止 | `/session/{id}/abort` | POST | header | 30s | **否** | — |
| 压缩 | `/session/{id}/summarize` | POST | header | 30s | **否** | 长耗时 |
| fork / revert | `/session/{id}/fork`、`/revert` | POST | header | 30s | **否** | — |
| slash 命令 | `/command`、`/session/{id}/command` | GET/POST | header | **300s** | 否 | 长 command |
| 模型/agent | `/config/providers`、`/agent` | GET | — | 30s | 是 | 低频 |
| 文件 | `/file`、`/file/content`、`/file/status`、`/find/file` | GET | query | 30s | 是 | 大 body 客户端 guard |
| VCS | `/vcs`、`/vcs/status`、`/vcs/diff` | GET | query | 30s | 是 | — |
| diff / todo | `/session/{id}/diff`、`/todo` | GET | header | 30s | 是 | — |
| active | `/api/session/active` | GET | — | 30s | 是 | 未读 soak |
| health 回退 | `/global/health` | GET | — | 5s | 是 | 非 slim host 用 |

> 路径均指 opencode 原生路径；客户端访问 sidecar 时打同 host 无 `/slimapi` 前缀。所有 mutation **禁止 timeout 后自动重试**。

#### shell deny-list（R10）

- catch-all 当前 **无路径黑名单**（`proxy.py` 不识别语义）；shell/PTY 经 HTTP 可达 = 安全风险。
- **默认配置 deny-list**：命中 shell/PTY 类路径（具体路径表须对照目标 opencode 版本路由扫表后写死，不臆造）→ `403 {"code":"shell_not_allowed"}`。
- 提供 ops 配置项可关闭（默认开启屏蔽）；WebSocket 继续 501。
- **注意**：catch-all 不识别路径语义是结构性事实；deny-list 是 best-effort 第二道，真实隔离仍靠 stunnel mTLS + 网络边界。

#### 明确不承诺

| 项 | 说明 |
|---|---|
| 全量 `message.part.delta` 经 slim SSE | 设计 DROP；要动画走 v2 G12 |
| WebSocket / PTY | WS→501 |
| shell 默认开放 | 见上 deny-list |
| catch-all 错误统一映射 | upstream 异常可能 500；不额外包装 |

---

### G5. digest / resync 客户端可执行状态机（文档-only，R8）

#### 场景

| 场景 | 说明 |
|---|---|
| 网络闪断 | SSE 重连发 `resync`，若当心跳 → 漏消息 |
| digest 只有 messageID | 不含正文；须约定何时 since |
| 多 session 后台 | 非 focus 会话仅更新列表行，避免全量拉 |
| 与 legacy 双模式 | slim 无 part.delta；按 delta 渲染 → 空白气泡 |

#### 状态机（伪代码 + 决策表）

**术语**：
- `focus session` = 当前打开的聊天 tab 会话
- `dirty session` = 本地已知、且尚未对齐服务端状态的 session 集合（初始 = 本地打开过的 session ∪ 曾收到 digest 的 session）
- `localMaxUpdatedAt[sessionID]` = 本地该 session 已知最大 `info.time.updated`

```
on server.connected:
    if 首次连接 or 冷启动:
        GET /slimapi/sessions          # 重建列表
        GET /slimapi/questions|permissions（当前 workdirs）
        dirty = 所有本地已知 session
    else:
        # 重连但非首次：依赖后续 resync
        pass

on session.digest {sessionID, status?, messageID?, updatedAt?, archived?, deleted?, lastError?}:
    if deleted == true:
        本地移除 session 行
    else if archived?:
        标记归档
    if lastError?:
        UI banner（按 lastError.name 决定展示样式）
    if focus session == sessionID and updatedAt? and updatedAt > localMaxUpdatedAt[sessionID]:
        GET /slimapi/messages/{sessionID}/since/{localMaxUpdatedAt[sessionID]}
        去重（messageID 边界包含）
        更新 localMaxUpdatedAt
    else:
        仅刷新列表行 status/title/updatedAt
    dirty.remove(sessionID)

on question.* / permission.*:
    upsert 单条 pending；或 GET 聚合刷新

on resync {reason}:
    # reason ∈ {reconnect_no_replay, subscriber_backpressure}
    丢弃「事件连续」假设
    GET /slimapi/questions|permissions（全量刷新）
    for sid in dirty ∪ focus:
        probe = GET /slimapi/messages/{sid}?limit=1&mode=skeleton   # G3
        if probe.info.id != 本地最新 or updatedAt 推进:
            GET /slimapi/messages/{sid}/since/{localMaxUpdatedAt[sid]}
    dirty = {}

on server.heartbeat:
    仅刷新 SSE watchdog

# 禁止：slim 模式下假设存在 token 级 message.part.delta
# 渲染气泡一律靠 REST skeleton/full
```

#### 边界规则

- `since/{ts}` 含边界：`info.time.updated >= ts`；客户端按 `messageID` 去重边界。
- digest 无 `updatedAt` 时：用 G3 probe 决定是否拉取。
- routeToken 1h 过期后：pending 重新拉取会换新 token。
- 重连期间新事件与 catch-up REST 结果合并：以 REST 为准（digest 只是触发器）。

---

### G6. 批量消息展开 `GET /slimapi/messages/{sid}/full`

#### 场景

| 场景 | 说明 |
|---|---|
| 点开多条「已折叠思考」 | skeleton 多条 `hasFull`，逐条 `full/{mid}` → 高延迟 |
| 局部历史恢复 | 某段 5–10 条需同时展开 tool/reasoning |
| 弱网 | 单 RTT 批量优于 N 次往返 |

#### 接口（与单条 `full/{mid}` 并存；旧路径保留）

| 参数 | 位置 | 类型 | 默认 | 约束 |
|---|---|---|---|---|
| `sid` | path | string | — | 必填 |
| `ids` | query | string | — | **必填**；逗号分隔 messageId，**1–20**；去重保序 |
| `mode` | query | `skeleton` \| `full` | `full` | 与单条一致 |
| `directory` | query | string? | — | 转 `X-Opencode-Directory`；G7-soft 校验 |

#### 响应（R7：envelope；部分失败始终 200）

```json
{
  "items": [
    { "/* MessageWithParts */": "…" }
  ],
  "errors": [
    { "messageID": "msg_missing", "code": "message_not_found" }
  ]
}
```

| HTTP | 条件 |
|---|---|
| **200** | 任意成功或全部 mid 级失败（not_found/too_large 进 errors） |
| **400** | `ids` 空 / >20 / 非法字符 / 含非法逗号 → `{"code":"invalid_ids"}` |
| **413** | 累计响应超 `max_response_bytes` |
| **503** | transform_busy / 上游整体不可用 |

**MUST**：
- `items` **严格按请求 `ids` 去重后顺序**排列（并发回填后重排，禁止"尽量"）。
- 单 mid 404 → `errors[] code=message_not_found`；单 mid 超 32MiB → `errors[] code=message_too_large`。
- 累计预算 64 MiB 在 **batch 内共享**（非 20×32MiB）。
- `Cache-Control: no-store`；支持 gzip。

#### 实现

```
for mid in ids (concurrency ≤ 4, 累计字节计数):
    GET upstream /session/{sid}/message/{mid}
    apply skeleton if mode=skeleton
    on 404 → errors[]
    on size → errors[]（或累计超限中止整请求 413）
reassemble items strictly in ids order
```

#### 等价性

- 语义 ≈ N 次单 mid full，单 RTT、共享 transform admission。
- **不严格等价**于 list `mode=full`（list 是分页时间序，本接口是 id 集合）。

---

### G7-soft. messages directory allowlist

#### 场景

| 场景 | 说明 |
|---|---|
| 多 worktree | 客户端 bug / 脏缓存 directory 指到别的项目 |
| 与 pending 一致性 | questions/permissions 已 require_directory，messages 不一致是债 |
| 第二道边界 | stunnel 后仍希望 sidecar 做目录校验 |

#### v1 契约（soft）

| 条件 | 行为 |
|---|---|
| 未传 query `directory` | 不拦（依赖上游默认）；**v1 不强制必填** |
| 传了 query `directory` 且 ∈ allowlist | 通过 |
| 传了 query `directory` 且 ∉ allowlist | **400** `{"code":"directory_not_allowed"}`；允许 miss 时刷新 projects 一次 |

**校验源（R5，写死）**：v1 **只认 query `directory`** 走 `require_directory()`。  
若同时存在 `X-Opencode-Directory` header 且与 query 冲突 → **400**。  
**禁止**宣称 soft = 多租户隔离；隔离靠 stunnel/mTLS + 网络边界。

**涉及路径**：
- `GET /slimapi/messages/{sid}`
- `GET /slimapi/messages/{sid}/since/{ts}`
- `GET /slimapi/messages/{sid}/full/{mid}`
- `GET /slimapi/messages/{sid}/full`（G6）

v2 的 strict（必填 directory）见 §1。

---

### G8. `full/{mid}` 流式 cap（**P0**）

#### 场景

| 场景 | 说明 |
|---|---|
| 巨大 tool 输出 / 贴文件 | 单消息可远超 32 MiB |
| sidecar MemoryMax=384M | 先整包下载再拒绝 → 峰值内存打满、连累其它请求 |
| 与 list skeleton 64MiB 防护不对齐 | list 有预算，单条 full 防护滞后 |

#### 目标（R3）

- **full 与 skeleton 均**使用累计字节 cap（对齐 `read_with_cap`）。
- **超过 `max_message_bytes` 立即中止 upstream 读取** → `413 {"code":"message_too_large","limitBytes":…}`。
- **禁止**现状的「先完整下载再查 32MiB」。
- cap 计量：解压后逻辑 JSON 字节（与 list/since 口径一致，写死）。
- 超限后须关闭 upstream response（防连接泄漏）。

**参数不变**。**优先级：P0（稳定性）**。

---

## 5. v2 延后规范（占位，仅记录触发条件）

### G3-B. 独立 latest-message 探针

**触发**：压测证明 G3-A 对「末条大文本」仍过重。

```http
GET /slimapi/sessions/{sid}/latest-message
```

只返回 `{sessionID, messageID, updatedAt, role}`（info-only 真有界）；空会话 `messageID:null`；不存在 404。

### G7-strict. messages directory 必填

**触发**：客户端迁移完成。**需 bump**。所有 messages 读接口必填 query directory。

### G9. 服务端 SSE focus

**触发**：实测带宽收益 > 列表盲区成本；产品接受「focus 订阅者不靠 SSE 更新非 focus 列表」。

```http
GET /slimapi/events?focus={sessionID}
```

`session.digest` 仅推该 sessionID；question/permission/heartbeat/resync/connected 仍全局。tab 切换 = 断线重连带新 focus。

### G10. since 时间窗上界

**触发**：按日/审计产品需求。

```http
GET /slimapi/messages/{sid}/since/{ts}?until={tsEnd}
```

`until` 半开区间 `[ts, until)`（默认）；与 `before` 正交，先时间过滤再分页。

### G11. messageId 邻域

**触发**：搜索/跳转产品需求。

```http
GET /slimapi/messages/{sid}/around/{mid}?before=N&after=M&mode=skeleton|full
```

`before`/`after` 条数 0–50；找不到 mid → 404；扫描超限 → 200+`truncated:true` 或拒绝。

### G12. 批式 coalesced text delta

**触发**：与省流 KPI 重定义绑定；**必须 bump**。仅 focus session；合并 50–100ms text delta；固定小 schema；桥接源是 v1 `message.part.delta`（opencode 有 schema，非凭空）。客户端不得把 delta 当最终消息，须 REST 回补。

### G13. durable sequence replay

**触发**：opencode v2 API 稳定 + sequence 跨重启/目录可靠验证通过。借鉴 `GET /api/session/:id/event?after=sequence`。**需 bump**。

---

## 6. 统一错误码表（最小集）

| code | HTTP | 场景 | 是否新 |
|---|---|---|---|
| `version_required` / `version_incompatible` | 400 | 版本门闩 | 现有 |
| `directory_not_allowed` | 400 | allowlist miss（G7/G2） | 现有 |
| `session_not_found` | 404 | session 不存在（G2） | **新** |
| `message_not_found` | 404 或 200+errors | 单 mid（G6 errors） | **新** |
| `message_too_large` | 413 | G8 / G6 单 mid | 现有 |
| `response_too_large` | 413 | list/since/batch 累计 | 现有 |
| `transform_busy` | 503 | 转换槽满 | 现有 |
| `upstream_unavailable` | 503 | 超时/5xx/解析失败（G2） | **新**（统一命名） |
| `upstream_timeout` / `upstream_error` / `upstream_http_N` | 504/502/原 | questions 聚合现有 | 现有 |
| `invalid_ids` | 400 | G6 ids 校验 | **新** |
| `shell_not_allowed` | 403 | G4 deny-list 命中 | **新** |
| `thin_route_not_found` | 404 | 未知 slim path | 现有 |

- FastAPI 参数/body 校验：**422**（保持）。
- 业务校验主动抛：**400**。
- 错误 body 统一形状：`{"code":string, "message"?:string, ...}`。
- 命名一致：`not_found` 系列统一为 `session_not_found` / `message_not_found`，禁止裸 `not_found`。

---

## 7. 版本与兼容

| 变更 | bump X-Slimapi-Version？ |
|---|---|
| digest 加可选 `lastError` | 否（加性） |
| status 404 替代部分 503 | 否（修 bug；客户端应已能处理 404） |
| multi-mid 新路径 | 否（新端点） |
| G7-soft（提供才校验） | 否（仅收紧非法 directory） |
| G8 流式 cap | 否（413 已存在） |
| shell deny-list 默认开 | 否（运维配置；非协议） |
| 统一错误码 | 否（加性 code） |
| **v2 任一项** | 见 §1 表 |

---

## 8. 验收清单

- [ ] **B0**：确认 `session.error` 是否上 `/global/event`（写入验证报告）
- [ ] G2：upstream 404 → 404；allowlist miss → 400；网络/5xx → 503（四分支测试）
- [ ] G8：mock 超大 body 边读边 413；峰值 RSS 不爆；upstream response 关闭
- [ ] G7-soft：无 directory 不 400；非法 directory 400；query/header 冲突 400
- [ ] G1：生成失败立即见 lastError；**abort 不亮 banner**；session-less error 走 B；message 脱敏
- [ ] G3：skeleton&limit=1 探针；schema_degraded 行为写死
- [ ] G4：透传矩阵含超时/重试/shell deny-list；shell 命中 403
- [ ] G5：状态机伪代码可测；resync 两种 reason 都覆盖
- [ ] G6：items 严格按 ids 序；部分失败 200+errors；ids 非法 400；累计超限 413
- [ ] 错误码：统一形状；`session_not_found`/`message_not_found` 命名一致
- [ ] 回归：现有 routeToken/since/skeleton/events backpressure 全绿

---

## 9. 测试矩阵

| 区域 | 用例 |
|---|---|
| G1 hub | session.error 有/无 sid；MessageAbortedError 不产 lastError；跨多窗口 sticky；clear 规则；脱敏 golden |
| G1 e2e | `/global/event` 实发 session.error → 订阅者可见（B0 验证） |
| G2 | upstream 404 / 500 / 超时 / allowlist miss / 无 directory 四分支 |
| G8 | gzip/identity 下大响应；client disconnect 后 upstream 关闭；并发大响应 |
| G6 | items 定序；重复 id；部分 404；部分过大；全失败；ids 校验 |
| G7 | query directory 合法/非法/缺省；header 冲突 |
| G5 | resync reconnect_no_replay / subscriber_backpressure；digest 无 updatedAt；routeToken 过期 |
| 回归 | skeleton 规则、since ts 地板、X-Next-Cursor、events backpressure resync |

---

## 10. 实现落点（oc-slimapi）

| 变更 | 文件 |
|---|---|
| G1 lastError + session-less 帧 | `sidecar/src/oc_slimapi/sse/hub.py` |
| G2 status 404 | `sidecar/src/oc_slimapi/routes/sessions.py` |
| G6 multi full | `sidecar/src/oc_slimapi/routes/messages.py`（路由注册先于 `{mid}`） |
| G7-soft allowlist | `routes/messages.py` + 复用 `require_directory` |
| G8 流式 cap | `routes/messages.py`（对齐 `read_with_cap`） |
| G4 shell deny-list | `proxy.py` + `config.py` |
| 错误码统一 | 各 route + helper |
| 契约文档 | `INTERFACE_MAP.md` / `CLIENT_CHANGES.md` / `docs/design-v2.md` |
| 客户端 | ocdroid `SSEClient.kt` / `OpenCodeRepository.kt` / `docs/sse-client-spec.md` / `docs/slim-mode-api-routing.md` |

---

## 11. 客户端配套（ocdroid，契约联动）

| 项 | 动作 |
|---|---|
| `SSEClient` / bridge | 解析 digest.`lastError` 与 `event:session.error`；resync 状态机按 G5 |
| `probeLatestMessageId` | 改 `GET /slimapi/messages/{sid}?limit=1&mode=skeleton` |
| 展开 UI | 支持 G6 批量 full（可并行旧单条接口过渡） |
| status 错误处理 | 区分 404（清本地）vs 503（重试） |
| `slim-mode-api-routing.md` | 路径与 INTERFACE_MAP 对齐；写入 G4 透传矩阵 |
| `Part.hasFull` | 保持既有 CLIENT_CHANGES 要求 |

---

*本文件为 ocdroid 侧契约；落地实现以 oc-slimapi PR 为准，合并后同步 INTERFACE_MAP / CLIENT_CHANGES。*
