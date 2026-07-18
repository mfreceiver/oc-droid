# 接口需求清单评审（2026-07-18）

> 范围：用户提出的接口需求清单 × ocdroid 实际调用 × oc-slimapi 实现 × opencode-src 源码  
> 方法：3 路 explorer-ext 侦察 + **同一份提示词** 独立派发 rev-gpt / rev-grok / rev-opus  
> 状态：只读评审，无代码改动  
> 真源：
>
> - ocdroid：`app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt`、`OpenCodeRepository.kt`、`SSEClient.kt`；`docs/slim-mode-api-routing.md`、`docs/sse-client-spec.md`
> - slimapi：`/home/mar/personal_projects/oc-slimapi/INTERFACE_MAP.md`、`docs/design-v2.md`、`sidecar/src/oc_slimapi/**`
> - opencode：`opencode-src/v1.17.20/`、`opencode-src/v1.18.3/`

---

## 决策摘要（先读这段）

### 一句话结论

**清单方向正确，不能当「完整接口契约」原样落地。**  
三评一致：**有条件通过**；主对话路径覆盖较好；写路径/文件/VCS/command 等靠 catch-all 透传；真正要补的是 **error 可见性、消息省流语义闭环、客户端 digest/resync 接入**，不是把 opencode 全量 thin 一遍。

### 对四个问题的合成立场

| # | 问题 | 合成结论 |
|---|---|---|
| 1 | 清单是否覆盖 ocdroid 全部接口？ | **否。** 对话/会话/pending 主路径基本有；**未列** children、fork、revert、command、diff、todo、vcs、health/ready、active 等客户端真实依赖。功能靠 catch-all 不断，但清单≠完整契约。 |
| 2 | 是否有超出？对 UX 是否有帮助？ | **有超额。** multi-mid 批量展开、骨架/实体分离、curated SSE 有价值；**queue API**（无后端）、**dedicated latest-id**（可用 skeleton&limit=1）、**id±N / start+end 时间窗** 多数可延后；二进制流证据不足。 |
| 3 | slimapi 遗漏 / 等价 / 无用？ | **几乎没有 thin≈legacy 严格等价**（刻意省流）。写类 catch-all **可近似等价**。必要缺口：`session.error` 被 DROP、无 multi-mid full、since 无 end（弱）、status 404→503、messages 未强制 allowlist、digest/resync 客户端闭环。**无用（相对现状）**：queue、±N、过早 binary。 |
| 4 | opencode 源码取舍？ | **thin：读放大 + 跨目录 pending 聚合 + curated SSE**。**透传：写/文件/VCS/agent/provider/command/diff/todo/children**。**不提供或严格受控：shell/PTY/WS**。v2 `after=sequence` 可作后续 resync 借鉴，暂非主协议。 |
| 5 | 下一步 0/1/2？ | **0 先做实**（单全局 SSE + tab 本地或 focus 过滤 + resync/REST 回补）。**1 谨慎**（批式 delta 与「不传 token 流」冲突，须绑 focus + 版本 bump）。**2 暂缓**（先确认 gzip，再 benchmark）。 |

### 决策矩阵（建议）

| 项 | 建议 | 理由（三评共识度） |
|---|---|---|
| 会话列表 / sessions skeleton | **保留 thin** | 已实现，主路径 |
| projects + allowlist | **保留 thin** | 跨目录基础 |
| messages skeleton / since / full 单条 | **保留 thin** | 省流核心 |
| multi-mid full（`ids=`） | **P1 做** | 三评均认可减 RTT；opus/gpt 偏优先 |
| pending questions/permissions + routeToken | **保留 thin** | 跨目录刚需 |
| curated `/slimapi/events` | **保留；补 error** | 三评：error 不得静默丢 |
| create/rename/archive/send/abort/summarize | **透传 + 写清契约** | grok/opus：写 thin 收益低；gpt 偏 thin 化契约，短期透传可接受 |
| file / vcs / agent / providers / command / diff / todo / children / fork / revert | **透传 + 文档化进契约** | 清单遗漏但客户端在用 |
| dedicated latest-message-id | **不做 / 收敛** | opus：skeleton&limit=1 等价；grok：二选一；gpt：非 P0 |
| queue message API | **不做（无上游）** | 三评：无后端；发 prompt_async |
| messageId ±N 窗口 | **P2 / 需求驱动** | 可用 before+limit 近似 |
| since 时间窗 end bound | **P2 / 弱必要** | catch-up 只需 ts 地板 |
| shell / WebSocket | **不提供 slim 主路径** | WS→501；shell 需安全边界 |
| 批式 token 流（下一步 1） | **延后，版本 bump** | 与 slim 省流目标冲突 |
| 二进制流（下一步 2） | **暂缓** | 证据不足 / 过早优化 |

### 建议优先级（合成）

**P0（正确性 / 契约）**

1. 客户端：`session.digest` + `resync` → REST 快照/`since` 闭环（事实 A gap）。
2. slim：恢复 **error 可见性**（精简 `session.error` 帧或 `digest.lastError`）。
3. 文档：INTERFACE_MAP 为真源；把 catch-all 透传清单（fork/command/file…）写进契约，避免「清单=完整接口」误导。
4. status 404 vs 503 可区分；messages directory allowlist 与 sessions 对齐（可选但应定）。
5. latest 探针：**要么** thin latest-id+ETag，**要么** 正式采用 `skeleton&limit=1` 并停用 heavy limit=1 full。

**P1（省流效率）**

1. multi-mid full。
2. focus 化或客户端 tab 过滤规范写死（下一步 0 做实）。
3. `full/{mid}` 流式 cap（防大消息缓冲）。
4. 路径/文档与 OpenCodeApi 对齐（去掉过时 A 桶路径）。

**P2（演进）**

1. 批式 coalescing delta（仅 focus tab + API version bump）。
2. end 时间窗 / id±N（产品明确再做）。
3. 二进制 pull / durable sequence replay（benchmark + v2 稳定后再动）。

### 三评对照速查

| 议题 | rev-gpt | rev-grok | rev-opus |
|---|---|---|---|
| 清单完整？ | 否 | 否 | 否（点名 7 类遗漏） |
| 总立场 | 有条件通过 | 蓝图有条件通过；原样全 thin 不通过 | 有条件通过 |
| error | 必要遗漏 | 必要遗漏（弱）/ P1 | **P0 必补** |
| multi-mid | P0/P1 | P1 可选 | **P1 优先** |
| 写路径 thin？ | 倾向正式契约/P0 thin | 默认 catch-all 透传 | 透传合理，须文档化 |
| latest-id | 非 P0 | P0 二选一 | dedicated 冗余 |
| queue | 需求未证实 | 无用 | 无用 |
| 下一步 0/1/2 | 0 高；1 中高；2 低 | 0 做实；1 冲突；2 证据不足 | 0→1(绑 focus)→2 |
| v2 sequence | 暂不主协议 | 证据不足则不动 | **被低估，应借鉴** |

### 侦察关键事实（压缩）

**ocdroid**：混合 legacy REST + `/global/event` + 部分 `/slimapi/*` + catch-all。发送=`prompt_async`；无 queue API；无 WS；probe latest 仍 `message?limit=1`。

**slimapi 已 thin**：health/ready、sessions、projects、messages(+since+full)、questions/permissions(+routeToken)、sessions status、events、metrics；其余 HTTP catch-all；WS 501。

**SSE 发出**：`server.connected` / `heartbeat` / `resync` / `session.digest` / question|permission 即时帧。  
**SSE 丢弃**：`message.part.*`、`tool.*`、`session.error`、`message.removed` 等。

**opencode**：legacy `/session/*`、`/question`、`/permission`、`/file`、`/global/event` 等；无原生 since/skeleton/digest/跨目录聚合；v2 有 durable session event。

---

## 用户原始需求清单（归档）

- 获取有效会话列表：id、名称、项目、活跃时间
- 浏览文件夹并创建项目
- 根据项目获取会话？
- 会话新建、归档、重命名
- 文件浏览及下载接口
- 模型、agent 可用性探测
- pending 查询、响应问题和权限接口
- 压缩上下文
- 消息中 error 获取
- 中止会话
- 在会话中发送消息、增加排队消息
- 查询指定会话最新消息 id 及时间
- 获取特定时段内消息骨架（id/类型/时间，无实体）；默认开始时间，也可开始+结束
- 获取特定时段内有效消息清单（配合骨架，可分批）
- 按消息 id + 前后 查询骨架/消息（前后可选）
- 获取特定消息内容：sessionId + 1..n messageIds
- SSE：会话状态、新消息、pending 创建/解除、error？
- 下一步：0 全局状态+当前 tab 单 SSE（不含非对话内容）；1 批式流式输入；2 二进制流提升有效占比

---

# 报告 A — rev-gpt（完整）

## 0. 执行摘要

1. 清单覆盖了 ocdroid 的主对话闭环，但未覆盖全部实际依赖，尤其是会话派生操作、文件/VCS、diff/todo/command 等。
2. “按时间获取消息 + 骨架/实体分离”是有价值的移动端优化，但当前 slimapi 仅部分实现。
3. `messages/{sid}/since/{ts}` 不能严格等价于“开始时间+结束时间”的时间窗接口。
4. `full/{mid}` 当前一次只能取一个消息，不能等价于清单要求的批量消息内容接口。
5. 创建、归档、重命名、发送、abort、summarize 等必要写操作目前主要依赖 catch-all，尚未形成 slimapi 专用能力。
6. `latest message id` 不是当前客户端的真实依赖；客户端仍使用 `GET /session/{id}/message?limit=1`。
7. 模型/agent 探测、文件浏览/下载、fork/revert、diff/todo/command 等不能因“能透传”就视为 slimapi 已提供。
8. slimapi 的 curated SSE 已覆盖会话摘要和 pending 即时通知，但丢弃消息 part、tool、error 等事件，无法完全替代 legacy SSE。
9. 下一步 0（全局状态 + 当前 tab 单 SSE）最有价值，但必须明确事件范围、重连和 REST 回补边界。
10. 最终判断：清单方向正确，但不能作为“已完整覆盖”的接口契约；应补齐写操作、消息窗口语义、批量展开和错误/流式协议。

## 1. 清单 vs ocdroid 覆盖度（问题1）

| 清单项 | ocdroid 是否需要 | 当前客户端如何满足 | 覆盖结论 |
|---|---:|---|---|
| 获取有效会话列表：id、名称、项目、活跃时间 | 是 | legacy `GET /session?limit&directory&roots`；Slim `/slimapi/sessions`；另有 `/api/session/active` | 覆盖 |
| 浏览文件夹并创建项目 | 是 | 文件相关接口、`GET /project`/directories；Slim `/slimapi/projects`，创建项目路径未明确 | 部分 |
| 根据项目获取会话 | 是 | legacy `directory` 过滤；Slim sessions 目录过滤，项目到会话专用语义未明确 | 部分 |
| 会话新建 | 是 | `POST /session`，主要 legacy/catch-all | 部分 |
| 会话归档 | 是 | `PATCH /session/{id}`，catch-all | 部分 |
| 会话重命名 | 是 | `PATCH /session/{id}`，catch-all | 部分 |
| 文件浏览及下载 | 是 | `GET /file`、`/file/content`、`/file/status`、`/find/file` | 覆盖 |
| 模型可用性探测 | 是 | `GET /config/providers`；辅助 `/api/model`、`/api/provider` | 覆盖 |
| agent 可用性探测 | 是 | `GET /agent`；另有 `GET /command` | 覆盖 |
| pending 查询 | 是 | legacy `/question`、`/permission`；Slim 聚合 `/slimapi/questions`、`/permissions` | 覆盖 |
| 响应问题和权限 | 是 | Slim + routeToken；legacy 也支持 | 覆盖 |
| 压缩上下文 | 是 | `POST /session/{id}/summarize`，catch-all | 部分 |
| 获取消息中的 error | 是 | legacy 事件；Slim 丢弃 `session.error`，无 dedicated error API | 部分 |
| 中止会话 | 是 | abort，catch-all | 部分 |
| 会话中发送消息 | 是 | `POST /session/{id}/prompt_async`；无 thin | 部分 |
| 增加排队消息 | 事实不足以证明需要 | 无独立 queue API；发送即 `prompt_async` | 缺失/需求未证实 |
| 查询指定会话最新消息 id 及时间 | 低至中 | 实际 `GET /session/{id}/message?limit=1` | 部分 |
| 时间段消息骨架：开始，可开始+结束 | 是 | `/since/{ts}` 仅开始地板；`limit&before` | 部分 |
| 时间段有效消息清单，可分批 | 是 | `mode=full`；`full/{mid}` 单条 | 部分 |
| 按消息 id 前后查询骨架/消息 | 是 | 无 thin；legacy 单条读无前后窗口证明 | 缺失 |
| 批量获取特定消息内容：1..n messageIds | 是 | `full/{mid}` 一次一个 | 缺失 |
| SSE 会话状态更新 | 是 | `/global/event`；Slim `session.digest` | 覆盖，语义不同 |
| SSE 会话新消息 | 是 | legacy message 事件；Slim digest 含 messageID 无 part | 部分 |
| SSE pending 创建与解除 | 是 | Slim 即时帧；legacy question/permission | 覆盖 |
| SSE error 信息 | 是 | legacy 可见；Slim 丢弃 | 部分 |
| 单 SSE：全局状态 + 当前 tab | 方向需要 | 有 global SSE；tab 过滤策略未证明 | 部分 |
| 批次流式输入 | 未证明已需要 | legacy 有 part.delta；Slim 不传 token 流 | 部分/待演进 |
| 二进制协议 | 未证明需要 | 无 WS；Slim WS 501 | 未覆盖/优化项 |

**结论：**以“可调用”为标准实际可用；以“slimapi 专用稳定契约”为标准明显不完整。

## 2. 清单超额与 UX 价值（问题2）

| 超额/增强项 | 是否超出当前 | UX/功能价值 | 建议 |
|---|---:|---|---|
| 独立 latest-message-id + 时间探针 | 是 | 减大消息查询 | 降级优化项，非 P0 |
| 时间窗结束边界 | 相对 Slim 增强 | 同步范围确定 | 保留，P0/P1 |
| 消息骨架与实体分离 | 是 | 列表快显、按需展开 | 保留，核心 |
| 按消息 id 前后窗口 | 是 | 跳转/局部恢复 | 保留，P1 |
| 多消息批量展开 | 是 | 减 RTT | 保留，P0/P1 |
| queue message 独立接口 | 当前无 | 连续输入/离线排队可能有价值 | 延后，先明确产品语义 |
| 专用写 thin（create/send/abort…） | 相对 Slim 增强 | 契约清晰 | 保留，P0 |
| dedicated error API | 当前无 | 错误可查询 | 保留，先定义生命周期 |
| curated SSE | 相对 legacy 增强 | 减无关流量 | 保留，勿丢 UI 必需事件 |
| 全局状态 + 当前 tab 单 SSE | 未完整 | 降连接数 | 保留，P0 |
| 批次流式 | 未实现 | 降 token JSON 开销 | P1，先批量 JSON |
| 二进制流 | 明显超出 | 高吞吐场景 | 延后，P2 |

不能断言某项“完全无用”；queue/latest/binary 属未证实需求或优化项。

## 3. 三方对比（问题3）

| 能力 | 清单 | slimapi thin | catch-all/legacy | 严格等价？ | 判定 |
|---|---|---|---|---:|---|
| 会话列表 | 有 | `/slimapi/sessions` | `GET /session` | 否 | 必要，当前可用 |
| 项目列表/目录 | 有 | `/slimapi/projects` | `/project` | 未证明 | 必要，确认创建语义 |
| 按项目筛会话 | 有 | directory 过滤 | `?directory` | 近似 | 必要 |
| 创建/归档/重命名 | 有 | 无 | POST/PATCH | 否 | 必要遗漏，短期透传 |
| 文件浏览/下载 | 有 | 无 | `/file*` | 否 | 必要，透传 |
| model/agent | 有 | 无 | providers/agent | 否 | 必要 |
| pending 查询/响应 | 有 | 聚合+routeToken | legacy | 不完全 | 基本覆盖 |
| 消息列表 | 有 | skeleton/full | `/message` | 否 | 必要 |
| 时间窗消息 | 有 | since 无 end | limit&before | 否 | 必要遗漏 |
| 单/多消息展开 | 有 | 仅单 mid | 单 mid | 否 | 必要遗漏 |
| id 前后窗口 | 有 | 无 | 未证明 | 否 | 必要遗漏 |
| latest id/time | 有 | 延后 | limit=1 | 否 | 非当前关键 |
| 发送/queue | 有 | 无 | prompt_async / 无 | 否 | 发送必要；queue 未证实 |
| abort/summarize | 有 | 无 | catch-all | 否 | 必要遗漏 |
| error | 有 | DROP | legacy 可见 | 否 | 必要遗漏 |
| SSE 状态/新消息/pending | 有 | digest+即时 | 全量 | 否 | 增强须契约化 |
| 流式/二进制 | 下一步 | 无 | part.delta | 否 | 演进项 |

**严格等价条件：**请求语义、响应字段、分页、实体完整性、错误语义、事件语义、目录边界均一致。  
按此条件 thin 读接口只能判“近似/增强”，不能严格等价。

**最小补齐：**写操作契约；start+end 时间窗；批量 full；id 前后窗口；send/abort/summarize 协议；error 可恢复表达；messages allowlist；status 404/503；统一字段/分页/错误模型。

## 4. opencode 源码取舍（问题4）

| API 族 | 方式 | 理由 |
|---|---|---|
| session 列表/详情/children/status | thin 优先 | 生命周期基础 |
| message 读 | skeleton/full thin | 最大流量 |
| prompt_async | thin/受控透传 | 核心写 |
| abort/summarize | thin 优先 | UI 状态 |
| fork/revert | 透传 | 非最小闭环也可有 |
| question/permission | thin | 跨目录 |
| project/file | thin 或透传 | 安全+allowlist |
| agent/providers | 透传/thin | 选模型 |
| command | 透传并限制 | 长超时 |
| shell | 默认不提供 | 安全 |
| diff/todo/vcs | 透传 | 客户端在用 |
| /global/event | curated 替代直连 | 省流 |
| v2 durable event | 暂不主协议 | 实验性 |

**设计原则：**场景驱动 thin；skeleton 白名单；full 关系明确；跨目录+routeToken；catch-all≠正式契约；文件/命令 allowlist；SSE 过滤/resync 规则；能力矩阵。

## 5. 下一步评估

- **0 单 SSE：**必要性高。明确范围；digest 列表级；tab 按需拉消息；保留 error；resync+REST。风险：跨 tab 污染、竞态。
- **1 批流：**中高非 P0。先批次边界与批量 JSON。风险：延迟与乱序。
- **2 二进制：**低至中。先裁剪/压缩。风险：兼容与调试成本。

## 6. 路线图

- **P0：**能力矩阵；写代理；end 窗；allowlist；单 SSE；resync；status 语义  
- **P1：**批量 full；id 窗口；error/part 按需；latest 评估；diff/todo/vcs/command 契约  
- **P2：**批流；压缩/二进制；durable replay  

## 7. 风险与未知

证据不足：单消息完整语义、projects 是否创建、active vs status、digest 字段精确语义、resync payload、session.error 生命周期、permission 同构、catch-all allowlist、二进制收益数据。

## 8. 最终独立意见

**有条件通过。** 方向正确；阻塞在语义缺口（写 catch-all、无 end、无 id 窗、无批量 full、SSE 丢 error/part、allowlist/status 未闭合）。先 P0；二进制暂缓。

---

# 报告 B — rev-grok（完整）

## 0. 执行摘要

1. 清单大体覆盖对话/会话/pending 主路径；file/VCS、agent/provider、fork/revert/command/children/diff/todo、health 覆盖不全。  
2. 超额：时间窗双界、messageId±N、独立 queue/error、多 mid 批量 full。  
3. slimapi thin 已覆盖省流核心；写与文件/模型靠 catch-all。  
4. 非严格等价：latest 仍 limit=1；since 无 end；full 单条缓冲；status 404→503。  
5. 时段/id±前后无法 thin 严格实现；应用用 limit+before 与 since 近似。  
6. SSE 与「下一步 0」部分重叠：单全局 curated，不传 token 流。  
7. opencode：thin 读放大+聚合；其余透传。  
8. 文档/实现路径不一致：以 INTERFACE_MAP 为准。  
9. 必要遗漏：multi-mid、end-bound、resync/digest 闭环、error REST/帧。  
10. **清单作愿景可用，不能原样全 thin 落地。**

## 1. 清单 vs ocdroid 覆盖度

| 清单项 | 需要 | 当前满足 | 结论 |
|---|---|---|---|
| 会话列表 | 是 | `/session` + `/slimapi/sessions` | 覆盖 |
| 浏览/创建项目 | 是 | projects + POST session；创建项目证据不足 | 部分 |
| 按项目取会话 | 是 | `?directory=` | 覆盖 |
| 新建/归档/重命名 | 是 | POST/PATCH/DELETE（catch-all） | 覆盖 |
| 文件浏览下载 | 是 | `/file*` catch-all | 覆盖 |
| model/agent | 是 | config/providers + agent | 覆盖 |
| pending | 是 | slim 聚合+routeToken | 覆盖 |
| summarize | 是 | catch-all | 覆盖 |
| 消息 error | 部分 | 无独立 API；slim 丢 error | 部分/缺失 |
| abort | 是 | catch-all | 覆盖 |
| 发送/排队 | 发送是 | prompt_async；无 queue | 部分 |
| latest id/time | 是 | limit=1；thin 非目标 | 部分 |
| 时段骨架 start/end | 是 | since 仅地板 | 部分 |
| 时段有效消息 | 是 | full 单条 | 部分 |
| id±前后 | 当前不需要 | 无 | 缺失（相对清单） |
| 1..n mids | 1 条需要 | full 单 mid | 部分 |
| SSE | 是 | curated vs full | 部分 |
| 未列：health/active/children/fork/revert/command/diff/todo/vcs | 需要 | legacy | **清单未覆盖** |

## 2. 超额与 UX

| 项 | 超出？ | 价值 | 建议 |
|---|---|---|---|
| end bound | 是 | 审计有用 | 延后 |
| id±N | 是 | 跳转有用 | 延后 |
| multi-mid | 略超 | 减 RTT | P1 可选 |
| queue | 是 | 可视化排队 | 延后/不提供 |
| error REST | 超出主路径 | slim 丢 SSE 后有价值 | P1 |
| latest-id+ETag | 部分 | 省流显著 | P0 二选一 |
| 单 SSE 无非对话 | 方向一致 | 关键 | 保留做实 |
| 批式/二进制 | 远超 | 实时/带宽 | P2 |

## 3. 三方对比

- thin 读与 legacy **刻意不等价（省流）**；写透传可近似严格等价。  
- **无用：** queue、±N、end 窗、file/model 再 thin、WS、delta 全量进 slim SSE。  
- **最小补齐：** latest 二选一；multi-mid 或并行；resync/digest；error 可见；status 404；messages allowlist；fork/command/file 保持 catch-all。

**严格等价条件：**语义、分页 cursor、字段集、错误码（404≠503）、写副作用（timeout 不双发）。

## 4. opencode 取舍

| 族 | 方式 |
|---|---|
| session 列表 | thin |
| message 读 | thin skeleton/since/full |
| abort/summarize/fork/revert | 透传 |
| status/active | thin 可选 + 透传 |
| question/permission | thin 聚合 |
| project | thin projects |
| file/find/vcs | 透传 |
| agent/providers/command | 透传 |
| diff/todo/children | 透传 |
| 全量 SSE | 不直连 → curated |
| v2 event replay | 暂不 / 实验 |
| shell/PTY/WS | 不提供 |

**原则：**thin 只做读放大+聚合；骨架默认；SSE 控制面；updatedAt 锚点；错误码可区分；INTERFACE_MAP 真源；resync=冷启动 REST。

## 5. 下一步

- **0：**必要性高。单全局 SSE；tab 过滤客户端；补 resync/digest/pending。  
- **1：**中高但与“不传 token 流”冲突；若做需 batch frame + version bump。  
- **2：**证据不足前不上；可考虑 pull 侧二进制，SSE 仍文本控制面。

## 6. 路线图

- **P0：** resync+digest；路径对齐；status；latest 二选一；slim 禁止依赖 part.delta  
- **P1：** multi-mid 或并行；allowlist；error；full 流式；超额时间/±N 不做  
- **P2：** batch delta；二进制 pull；v2 replay；sessions cursor  

## 7. 风险

文档路径漂移；丢 error/tool/delta；since 多页；routeToken 1h；schema degraded；projects fan-out；创建项目/v2 稳定性证据不足。

## 8. 最终独立意见

**有条件通过（蓝图）/ 不通过（原样全 thin）。**  
收敛为「省流读 + 透传写」；火力在 digest/resync/catch-up 与 limit=1 流量，而非批式二进制想象。

---

# 报告 C — rev-opus（完整）

## 0. 执行摘要

1. 清单未覆盖全部：`children/fork/revert/command/diff/todo/vcs` 7 类在用未列。  
2. catch-all 兜底功能不断，清单作完整契约不完整。  
3. 「排队消息」无后端——不是可交付接口。  
4. **error 真实必要遗漏**：清单要、客户端处理、slim DROP。  
5. start+end 时间窗必要遗漏（弱）。  
6. multi-mid、±N 缺失；N 次调用低效。  
7. dedicated latest-id 冗余：`skeleton&limit=1` 已够。  
8. v2 `after=sequence` 被低估，可服务下一步 0。  
9. 下一步：0 高差距小；1 中高绑 focus；2 低过早优化。  
10. shell 经 catch-all 暴露边界需确认。

## 1. 清单 vs ocdroid 覆盖度

主路径覆盖；error 在 slim **缺失**；排队 **缺失**；时段/批量 **部分**；±N **缺失**；另列 7 类清单遗漏。

**问题1：不覆盖。**

## 2. 超额与 UX

| 项 | 建议 |
|---|---|
| 排队消息 | 延后/降级：本地队列 vs 真后端，勿伪装接口 |
| id±N | P2 |
| start+end | 降级；before 多数够 |
| multi-mid | **P1 优先** |
| SSE error | **保留并明确** |
| 创建项目 | 先澄清 |

## 3. 三方对比

- 可等价：会话列表、骨架 start 窗、pending（slim 更强）、写透传  
- 可等价但低效：多 mid、时段 full  
- 必要遗漏：error；end 窗（弱）；±N（增强）  
- 无用：排队；dedicated latest-id  

**最小补齐：**  
1. events 精简 `session.error` 或 `digest.lastError`  
2. multi-mid full  
3. end/±N 按需求再定  

**严格等价注意：**时间锚点≠id 锚点；`before`≠时间上界；limit=1 full 字段过多；session 级 error 无消息挂载时无等价路径。

## 4. opencode 取舍

| 族 | 方式 |
|---|---|
| session 读热路径 | thin |
| message 读 | thin + 批量 |
| 写控制（prompt/abort/summarize/CRUD/fork/revert） | 透传 |
| command | 透传；shell 审慎 |
| diff/todo | 透传，热点再 thin |
| question/permission | thin 聚合 |
| project/file | 透传；projects thin 已有 |
| agent/providers | 透传 |
| 全量 SSE | 不直供，用 curated |
| v2 after=sequence | **借鉴** |

**原则：**默认 skeleton；保 legacy 形状；读 thin 写透传；跨目录+routeToken；错误一等公民；批量与 sequence。

## 5. 下一步

- **0：** `/slimapi/events?focus={sessionId}` 或等效；与 resync/dirty 一并治理。  
- **1：** coalescing delta 仅 focus tab。  
- **2：** 先确认 gzip/br；二进制 P2。

## 6. 路线图

- **P0：** error 可见；排队语义澄清；压缩事实；shell 边界  
- **P1：** multi-mid；focus SSE；latest→skeleton limit=1；遗漏端点透传文档化  
- **P2：** coalescing+sequence；±N/end；二进制  

## 7. 风险与未知

证据不足：sessions directory 参数细节、创建项目端点、压缩状态、shell 限制。  
已知坑：status 404→503、full 缓冲、allowlist、dirty/resync 在 focus 化时放大。

## 8. 最终独立意见

**有条件通过。**  
1. 必须补：error + multi-mid  
2. 必须澄清/降级：queue、dedicated latest-id、end/±N  
3. 必须文档化：children/fork/revert/command/diff/todo/vcs 走 catch-all 有意为之  
下一步：**0 → 1（绑 focus）→ 2**；优先利用 v2 `after=sequence`。

---

## 附录：slimapi 已实现端点速查（INTERFACE_MAP）

```text
GET  /slimapi/health
GET  /slimapi/ready
GET  /slimapi/sessions
GET  /slimapi/projects
GET  /slimapi/messages/{sid}
GET  /slimapi/messages/{sid}/since/{ts}
GET  /slimapi/messages/{sid}/full/{mid}
GET  /slimapi/questions
GET  /slimapi/permissions
POST /slimapi/questions/{qid}/reply
POST /slimapi/questions/{qid}/reject
POST /slimapi/sessions/{sid}/permissions/{pid}
GET  /slimapi/sessions/status
GET  /slimapi/sessions/{sid}/status
GET  /slimapi/events
GET  /slimapi/metrics
HTTP catch-all → opencode
WS catch-all → 501
```

所有 `/slimapi/**` 须 `X-Slimapi-Version: 1`。

---

## 附录：评审会话 ID（可追溯）

| 角色 | session |
|---|---|
| explorer：ocdroid 客户端 | `ses_08b78c6f0ffe3aeOIWsE7lYqZO` |
| explorer：opencode 源码 | `ses_08b78ba21ffeJa7bAMGZPN5dsJ` / `ses_08b768b15ffeZqjykke0wZgOnS` |
| explorer：slimapi | `ses_08b7595ebffeXSs5n9cY64TkJu` |
| rev-gpt | `ses_08b71997cffehmu53mOMhdxJ08` |
| rev-grok | `ses_08b715434ffe0s95xIoP9MfU2M` |
| rev-opus | `ses_08b7153b5ffeAW8A1SbGMe7Ami` |
