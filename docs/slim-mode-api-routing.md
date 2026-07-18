# 省流模式全 API 清单与四分类

> 本文件是 **ocdroid 客户端**对省流（slimapi）模式的目标路由规约：枚举本应用
> 当前调用的全部 HTTP/SSE 端点，按 A/B/C/D 四桶分类，列出 C 桶违规与迁移检查
> 清单。
>
> **核心模型**：**省流 = 切换服务器**。省流模式不是在现有连接属性上加个省流
> 开关，而是把当前所选 server（`HostConfig.baseUrl`）从 opencode 直连入口切到
> oc-slimapi sidecar 入口。选中后，**所有** opencode 形态的调用 base URL 都派生
> 自 slimapi。
>
> **版本契约基线**：本文按 fix-3 并行落地后的状态书写——路径扁平 `/slimapi/*`
> （**不**用 `/slimapi/v1/*`），每个 `/slimapi/**` 请求（含 SSE）必带
> `X-Slimapi-Version: 1` 头；`/slimapi/health` 暴露 `server.api_version` +
> `accepted_client_versions`。

---

## 1. 目的与"切换服务器"模型

### 1.1 模型定义

`HostConfig`（[`data/repository/HostConfig.kt`](../app/src/main/java/cn/vectory/ocdroid/data/repository/HostConfig.kt)）
持有当前所选 server 的 `_baseUrl` / `_username` / `_password` / `_hostPort`。
`configure(baseUrl, ...)`（[:69](../app/src/main/java/cn/vectory/ocdroid/data/repository/HostConfig.kt)）
是 `@Synchronized` 的原子 host 切换。

`OpenCodeRepository.configure()` → `hostConfig.configure(...)` →
`rebuildClients()`（[`OpenCodeRepository.kt:202`](../app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt)）
整体重建：

- `restHttp` / `restRetrofit` / `api`（REST 主接口）
- `commandHttp` / `commandRetrofit` / `commandApi`（POST `/session/{id}/command` 专用 300s read timeout）
- `v2Retrofit` / `apiV2`（`/api/model` + `/api/provider`，根 `<base>/api/`）
- `sseHttp` / `sseClient`（SSE 长连接，read timeout=0）

**省流模式下用户在 host 列表选中"slimapi server"条目**（base URL 指向 sidecar
入口，例如 `http://localhost:4097` 或 stunnel `https://host:14097`），上述五个
client 全部以 slimapi 为根重建。**所有** opencode 形态的调用随之派生。

### 1.2 与"在现有连接加省流开关"的对比（被否决的设计）

| 维度 | **采用**：切换服务器 | 被否决：连接属性加开关 |
|---|---|---|
| 状态 | 单一 `HostConfig.baseUrl` | `HostConfig.baseUrl` + `slimMode: Boolean` |
| 路由 | base URL 决定一切 | 每个端点要 if/else 选 URL |
| 一致性 | 5 个 client 全部随 host 切换重建（`rebuildClients`） | 每个调用点都要读 `slimMode`，遗漏即漏网 |
| 回退 | 选另一个 server 条目即可（双 stunnel 14096/14097） | 关开关，但客户端要重新 configure |
| 风险 | 用户清楚自己在哪个 server | 开关状态隐式，易遗漏（如新加端点忘读开关） |

**结论**：切换服务器模型胜出——客户端**不**新增 `slimMode` 标志位；省流是
用户在 host 列表里的显式选择。这意味着所有"省流专属 must"的迁移项都通过
**统一拦截器**实现（按 base URL 是 slimapi 时挂版本头、切 SSE 端点等），而
非散落到每个 Retrofit 方法。

### 1.3 与本文相关的硬约束

- **省流模式下严禁直接访问 opencode**：不得有任何调用绕过 slimapi 直连
  opencode（硬编码 opencode 主机 / `:4096` / 第二个指向 opencode 的 Retrofit
  client）。C 桶（§7）即此类违规的清零目标。
- **版本契约**：每个 `/slimapi/**` 请求（含 SSE）须带 `X-Slimapi-Version: 1`
  头；连接时读 `/slimapi/health` 自检 `server.api_version` /
  `accepted_client_versions`。

---

## 2. 分类口径（A/B/C/D 四桶定义）

| 桶 | 定义 | 客户端看到的主机 | 典型路径 |
|---|---|---|---|
| **A — slim-direct**（slimapi 自服务） | oc-slimapi design-v2 §1 / INTERFACE_MAP §1-3 列出的 sidecar 自己提供的端点（骨架消息、聚合 question/permission、策展 SSE、health/ready、routeToken） | slimapi URL | `/slimapi/sessions`、`/slimapi/sessions/{sid}/messages?mode=skeleton`、`/slimapi/sessions/{sid}/messages/since?anchor=`、`/slimapi/sessions/{sid}/messages/{mid}?mode=full`、`/slimapi/sessions/{sid}/latest-message-id`、`/slimapi/questions`、`/slimapi/permissions`、`/slimapi/events`、`/slimapi/health`、`/slimapi/ready`、`/slimapi/sessions/status`、`/slimapi/projects` |
| **B — slim-passthrough**（透传） | opencode 形态但不在 A 列表的端点——slimapi catch-all 反代转发给 opencode（写路径还注入 routeToken/directory） | slimapi URL（客户端看到的是 slimapi，opencode 不可见） | `/config/providers`、`/agent`、`/command`、`/file`、`/vcs`、`/find/file`、`/session/{id}/fork`、`/session/{id}/revert`、`/session/{id}/summarize`、`/session/{id}/abort`、`/session/{id}/diff`、`/session/{id}/todo`、`/session/{id}/children`、`/session/{id}/permissions/{pid}`、`/config/providers`、`/api/model`、`/api/provider`、`/api/session/active`、`/global/health`、`/session`（POST create）、`/session/{id}`（GET/PATCH/DELETE）、`/session/{id}/prompt_async`、`/session/{id}/command` |
| **C — direct-opencode**（省流模式禁止） | base URL 直指 opencode、绕过 slimapi 的调用（硬编码 opencode 主机 / `:4096` / 第二个指向 opencode 的 Retrofit client / 跳过 slimapi 网关的路径） | opencode URL（绕过 slimapi） | **必须全部找出并迁移**——见 §7 |
| **D — external**（非该体系） | 既非 opencode 也非 slimapi 的调用——更新检查/GitHub releases、OAuth/OIDC、遥测/分析、模型列表 CDN、host 不匹配所选 server 的远程加载 | 任意外部 host | 见 §8 |

### 2.1 归类算法

对每个 HTTP/SSE 调用：

1. 看 base URL：派生自 `HostConfig.baseUrl`？是 → 2；否 → D。
2. 看路径：在 A 列表？是 → A；否 → 3。
3. 看路径：是 opencode 形态（`/session`、`/config/providers`、`/global/event` 等）？是 → B（slimapi catch-all 会透传）；否 → D。
4. **C 的判别**：检查"是否有第二个 Retrofit instance / 裸 OkHttp call 把 base URL 钉死在 opencode 主机（`localhost:4096` / `:14096`）"——是 → C。

---

## 3. 版本契约

### 3.1 头注入

每个 `/slimapi/**` 请求（**含 SSE**）必须携带（v1-contract §1）：

```
X-Slimapi-Version: 1
```

- 缺头 → `400 {"code":"version_required","accepted":[min,max]}`
- 非整数 → 同上
- 区间外 → `400 {"code":"version_incompatible","client":v,"accepted":[min,max]}`

> 服务端当前 `SERVER_API_VERSION=1`；`ACCEPTED_CLIENT_VERSIONS=(1,1)` 闭区间，
> 可由 `OC_SLIMAPI_SERVER_API_VERSION` /
> `OC_SLIMAPI_ACCEPTED_CLIENT_VERSIONS=min,max` 配置（v1-contract §1）。

### 3.2 自检流程

连接时（profile 切到 slimapi server 后），客户端读（v1-contract §2）：

```
GET {slimapi}/slimapi/health
X-Slimapi-Version: 1
```

响应（v1-contract §2）：

```json
{
  "sidecar": { "ok": true, "version": "0.1.0" },
  "schema":   { "degraded": false },
  "server":   { "api_version": 1, "accepted_client_versions": [1, 1] }
}
```

客户端校验（v1-contract §1 — fail-closed）：

- `server.api_version` 与 `accepted_client_versions` 包含客户端硬编码版本（`1`）；
- `schema.degraded == false`（若 true，`/slimapi/sessions/{sid}/messages` 会自动
  降级 `mode=full`，失去省流收益但仍可用——UI 应提示）；
- `sidecar.ok == true`（liveness，不代表 opencode 可达；ready 探针见下）。

`/slimapi/ready` 探 upstream opencode 可达性（v1-contract §2）：

```
GET {slimapi}/slimapi/ready   → 200 {"upstream":{"ok":true,"latencyMs":n}, ...}
                                503 {"upstream":{"ok":false}}
```

> **注意**：`/slimapi/health` 自身也**必须**带 `X-Slimapi-Version` 头——版本
> 门闩对所有 `/slimapi/**` 生效（design-v2 §9.6）。

### 3.3 与 v1-contract 的交叉引用

| 主题 | 出处 |
|---|---|
| 版本契约（must 头 + 自检 + 错误码） | v1-contract §1（本文不复述） |
| 路径扁平 `/slimapi/*`（去除 `/v1/`） | v1-contract §2 |
| 端点职责（自服务 vs 透传 vs catch-all） | v1-contract §2 |
| catch-all 不受版本门闩影响（无头仍透传） | v1-contract §2 catch-all |

---

## 4. 全量 API 清单表

> **统计**（按桶）：A=12（迁移目标）、B=27（已对接 + slimapi catch-all 透传）、
> C=5（违规，须迁移）、D=4（外部，省流不影响）。逐条见 §5–§8。

调用点标注格式：`<file>:<line>`。`@Headers("X-Opencode-Skip-Dir: 1")` 表示
`DirectoryHeaderInterceptor` 不注入 workdir 头（端点是全局/by-id）。

### 4.1 A 桶 — slim-direct（slimapi 自服务）

> **当前对接状态**：客户端**未对接**任何 A 桶端点（迁移目标）。下表是迁移完成后
> 的目标映射；当前实际调用见 B 桶（所有消息/question/status 走 opencode 形态
> 经 slimapi catch-all 透传，**不省流**）。

| # | 客户端调用点（迁移目标） | HTTP 方法+路径 | 用途 | 备注 |
|---|---|---|---|---|
| A1 | `OpenCodeRepository.getSessions`（迁移到 slimapi 形态） | `GET /slimapi/sessions?directory=&roots=&limit=&start=&search=` | 列 session，骨架裁剪（留 summary/revert，剥 metadata/share） | 当前走 B 桶 `GET /session` |
| A2 | 新增 | `GET /slimapi/projects` | 拉 directory allowlist + 项目列表 | fan-out 到每项目 `/project/{id}/directories` |
| A3 | 新增（latest-id probe） | `GET /slimapi/sessions/{sid}/latest-message-id` + `If-None-Match` | 探最新消息 id + ETag 304 | 当前走 B 桶 `GET /session/{sid}/message?limit=1` |
| A4 | `OpenCodeRepository.getMessagesPaged`（迁移到 skeleton） | `GET /slimapi/sessions/{sid}/messages?limit=&before=&mode=skeleton&directory=` | **核心省流**：骨架消息分页（裸数组 + `X-Next-Cursor`） | 当前走 B 桶 `GET /session/{sid}/message`（全量） |
| A5 | 新增（since/ts） | `GET /slimapi/sessions/{sid}/since/{ts}` | 增量同步：`time.updated >= ts` 的骨架；`?limit/before` 分页（v1-contract §5） | **must**：增量 reducer |
| A6 | 新增（per-message full） | `GET /slimapi/sessions/{sid}/messages/{mid}?mode=full` | 按需展开骨架 part；32MiB 上限 | **must**：Part `hasFull`/`omitted` 展开 hook |
| A7 | `OpenCodeRepository.getSessionStatus`（批量） | `GET /slimapi/sessions/status?directory=` | per-directory status map | 当前走 B 桶 `GET /session/status` |
| A8 | 新增（单 sid status） | `GET /slimapi/sessions/{sid}/status` | 单 sid 反查 status | slimapi 内部 fan-out 找 directory |
| A9 | `OpenCodeRepository.getPendingQuestions`（迁移到聚合） | `GET /slimapi/questions?directory=&directory=`（repeated） | 跨 workdir 聚合 question，每 item 带 `directory`+`routeToken` | 当前走 B 桶 `GET /question?directory=` |
| A10 | `OpenCodeRepository.getPendingPermissions`（迁移到聚合） | `GET /slimapi/permissions?directory=&directory=`（repeated） | 跨 workdir 聚合 permission，每 item 带 `directory`+`routeToken` | 当前走 B 桶 `GET /permission` |
| A11 | `OpenCodeRepository.connectSSE`（迁移到策展 SSE） | `GET /slimapi/events`（SSE） | 实例级策展 SSE（session.digest debounce 250ms + q/p 直推 + 生命周期帧）；v1-contract §3 | 当前走 B 桶 `GET /global/event` |
| A12 | 健康检查（自检） | `GET /slimapi/health` + `GET /slimapi/ready` | 版本契约自检 + upstream 可达性 | 当前走 B 桶 `GET /global/health` |

### 4.2 B 桶 — slim-passthrough（透传）

> **当前对接状态**：客户端**已对接**全部 B 桶端点（在 legacy 模式下直连 opencode，
> 省流模式下经 slimapi catch-all 透传给 opencode）。下表逐条列出。

#### 4.2.1 B 桶 — 通过 Retrofit `OpenCodeApi`（接口：[`data/api/OpenCodeApi.kt`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt)）

| # | 调用点 | HTTP 方法+路径 | 用途 | Skip-Dir | 备注 |
|---|---|---|---|---|---|
| B1 | [`OpenCodeApi.kt:12`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("global/health")` | `GET /global/health` | 健康探针、版本识别 | ✓ | `/slimapi/ready` 上游等价；省流模式应迁移到 A12（带版本头） |
| B2 | [`OpenCodeApi.kt:16`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("session")` | `GET /session?limit=&directory=&roots=` | 列 session | ✓ | 省流目标迁移到 A1（骨架） |
| B3 | [`OpenCodeApi.kt:29`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@POST("session")` | `POST /session`（body: CreateSessionRequest）+ `@Header(directory)` | 创建 session | ✗（**显式 directory header**） | §6 写路径 routeToken/directory 注入说明 |
| B4 | [`OpenCodeApi.kt:36`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("session/{id}")` | `GET /session/{id}` | 单 session by id | ✓ | — |
| B5 | [`OpenCodeApi.kt:45`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("session/{id}/children")` | `GET /session/{id}/children` | 子（sub-agent）session 列表 | ✓ | — |
| B6 | [`OpenCodeApi.kt:49`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@PATCH("session/{id}")` | `PATCH /session/{id}`（body: UpdateSessionRequest） | 改 title / archived | ✓ | — |
| B7 | [`OpenCodeApi.kt:53`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@DELETE("session/{id}")` | `DELETE /session/{id}` | 删 session | ✓ | — |
| B8 | [`OpenCodeApi.kt:57`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("session/status")` | `GET /session/status` | 批量 status map | ✓ | 省流目标迁移到 A7 |
| B9 | [`OpenCodeApi.kt:61`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("api/session/active")` | `GET /api/session/active` | 活跃 session 集合 | ✓ | 由 `UnreadSoakController` 30s 轮询（无 SSE 等价） |
| B10 | [`OpenCodeApi.kt:65`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("session/{id}/message")` | `GET /session/{id}/message?limit=&before=` | 消息分页（含 `X-Next-Cursor`） | ✓ | 省流目标迁移到 A4 |
| B11 | [`OpenCodeApi.kt:72`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@POST("session/{id}/prompt_async")` | `POST /session/{id}/prompt_async`（body: PromptRequest） | 发消息（异步） | ✗（默认走 directory interceptor） | **mutation 不双发**（design-v2 §3.5） |
| B12 | [`OpenCodeApi.kt:79`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@POST("session/{id}/abort")` | `POST /session/{id}/abort` | 中止 session | ✓ | — |
| B13 | [`OpenCodeApi.kt:92`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@POST("session/{id}/summarize")` | `POST /session/{id}/summarize`（body: SummarizeRequest） | 触发上下文压缩 | ✓ | 压缩结果通过 SSE 投递 |
| B14 | [`OpenCodeApi.kt:99`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@POST("session/{id}/fork")` | `POST /session/{id}/fork`（body: ForkSessionRequest） | fork session | ✓ | — |
| B15 | [`OpenCodeApi.kt:106`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@POST("session/{id}/revert")` | `POST /session/{id}/revert`（body: RevertSessionRequest） | 回滚到 messageId | ✓ | — |
| B16 | [`OpenCodeApi.kt:113`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@POST("session/{id}/permissions/{permissionId}")` | `POST /session/{id}/permissions/{permissionId}`（body: PermissionResponseRequest） | 应答 permission | ✓ | **省流目标迁移到 A 桶写路径**：body 须带 `routeToken`，directory 经 token 还原（不再用 path/header directory） |
| B17 | [`OpenCodeApi.kt:121`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("permission")` | `GET /permission` | pending permission 列表 | ✓ | 省流目标迁移到 A10（聚合） |
| B18 | [`OpenCodeApi.kt:134`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("question")` | `GET /question`（+ `@Header(directory)`） | pending question 列表 | ✗（**显式 directory header**） | 省流目标迁移到 A9（聚合） |
| B19 | [`OpenCodeApi.kt:139`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@POST("question/{requestId}/reply")` | `POST /question/{requestId}/reply`（body: QuestionReplyRequest + `@Header(directory)`） | 回复 question | ✗（**显式 directory header**） | **省流目标迁移到 A 桶写路径**：body 须带 `routeToken`（剥离后透传） |
| B20 | [`OpenCodeApi.kt:146`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@POST("question/{requestId}/reject")` | `POST /question/{requestId}/reject`（+ `@Header(directory)`） | 拒绝 question | ✗（**显式 directory header**） | 同上，body 须带 `routeToken` |
| B21 | [`OpenCodeApi.kt:153`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("config/providers")` | `GET /config/providers` | 模型 catalog（**含 apiKey 字段**，客户端忽略未知键丢弃） | ✓ | 不缓存（`HttpHeaders.CACHEABLE_PATHS` 故意外排） |
| B22 | [`OpenCodeApi.kt:157`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("agent")` | `GET /agent` | agent 列表 | ✓ | 缓存（在 `CACHEABLE_PATHS` 内） |
| B23 | [`OpenCodeApi.kt:170`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("command")` | `GET /command` | slash 命令列表 | ✓ | 缓存 |
| B24 | [`OpenCodeApi.kt:183`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@POST("session/{id}/command")` | `POST /session/{id}/command`（body: CommandRequest + `@Header(directory)`） | 执行 slash 命令 | ✗（**显式 directory header**） | 走 `commandApi`（300s read timeout） |
| B25 | [`OpenCodeApi.kt:191`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("session/{id}/diff")` | `GET /session/{id}/diff` | session 文件 diff | ✓ | — |
| B26 | [`OpenCodeApi.kt:195`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("session/{id}/todo")` | `GET /session/{id}/todo` | session todo 列表 | ✓ | — |
| B27 | [`OpenCodeApi.kt:206`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("file")` | `GET /file?path=&directory=` | 文件树（current workdir） | ✓ | 显式 `?directory` query |
| B28 | [`OpenCodeApi.kt:220`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("file")`（variant） | `GET /file?path=` + `@Header(X-Opencode-Directory)` | 文件树（任意 directory，picker 用） | ✓ | — |
| B29 | [`OpenCodeApi.kt:227`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("file/content")` | `GET /file/content?path=&directory=` | 文件内容 | ✓ | — |
| B30 | [`OpenCodeApi.kt:234`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("file/status")` | `GET /file/status?directory=` | 文件状态 | ✓ | — |
| B31 | [`OpenCodeApi.kt:242`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("vcs")` | `GET /vcs?directory=` | VCS 信息 | ✓ | — |
| B32 | [`OpenCodeApi.kt:246`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("vcs/status")` | `GET /vcs/status?directory=` | VCS 状态 | ✓ | — |
| B33 | [`OpenCodeApi.kt:250`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("vcs/diff")` | `GET /vcs/diff?mode=&directory=` | VCS diff | ✓ | — |
| B34 | [`OpenCodeApi.kt:257`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt) `@GET("find/file")` | `GET /find/file?query=&limit=&directory=` | 文件查找 | ✓ | — |

#### 4.2.2 B 桶 — 通过 Retrofit `OpenCodeApiV2`（接口：[`data/api/v2/OpenCodeApiV2.kt`](../app/src/main/java/cn/vectory/ocdroid/data/api/v2/OpenCodeApiV2.kt)，根 `<base>/api/`）

| # | 调用点 | HTTP 方法+路径 | 用途 | 备注 |
|---|---|---|---|---|
| B35 | [`OpenCodeApiV2.kt:50`](../app/src/main/java/cn/vectory/ocdroid/data/api/v2/OpenCodeApiV2.kt) `@GET("model")` | `GET /api/model` | 模型 catalog（v2 形态，无 apiKey） | **debug-only**（`OpenCodeRepository.getModels`，无生产调用）；与 B21 二选一，当前生产用 B21 |
| B36 | [`OpenCodeApiV2.kt:54`](../app/src/main/java/cn/vectory/ocdroid/data/api/v2/OpenCodeApiV2.kt) `@GET("provider")` | `GET /api/provider` | provider catalog（v2 形态） | 同上，debug-only |

#### 4.2.3 B 桶 — 裸 OkHttp（非 Retrofit）

| # | 调用点 | HTTP 方法+路径 | 用途 | 备注 |
|---|---|---|---|---|
| B37 | [`OpenCodeRepository.kt:890`](../app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt) `connectSSE` → `SSEClient.connect` → [`SSEClient.kt:102`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt) | `GET {base}/global/event`（SSE） | 全局事件流 | 省流目标迁移到 A11 |
| B38 | [`OpenCodeRepository.kt:429`](../app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt) `checkHealthFor` → [`:445-446`](../app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt) | `GET {baseUrl}/global/health`（裸 OkHttp `healthClient`） | host 列表 "Test" 探针（非 mutative） | 省流模式应改为探 `/slimapi/health` + `/slimapi/ready`（带版本头） |

### 4.3 C 桶 — direct-opencode 违规（见 §7 详细列表）

### 4.4 D 桶 — external（见 §8 详细列表）

### 4.5 桶统计

| 桶 | 条数 | 已对接 | 迁移目标 |
|---|---:|---:|---:|
| A — slim-direct | 12 | 0 | 12（全部新增/迁移） |
| B — slim-passthrough | 38 | 38 | 0（保持透传） |
| C — direct-opencode | 5 | 5 | **5（全部需迁移/隔离）** |
| D — external | 4 | 4 | 0（省流不影响） |
| **合计** | **59** | **47**（不含 A） | **17 must + 5 迁移/隔离** |

---

## 5. A 桶明细 + 期望 slimapi 响应形状

> 客户端当前**未对接**任何 A 桶；下表是迁移完成后的契约。权威定义见
> [`oc-slimapi/INTERFACE_MAP.md`](../../oc-slimapi/INTERFACE_MAP.md) §1-3，
> 本文不复述。

### 5.1 响应形状速查

| 端点 | 响应 shape（节选） | 关键约束 |
|---|---|---|
| `GET /slimapi/sessions` | `Session[]` 裸数组（不套 envelope），裁剪字段（剥 metadata/share/version/path/permission） | 支持 gzip + `Vary:Accept-Encoding`；上游错误透传 |
| `GET /slimapi/projects` | project 数组，每项 `{id,name,worktree,directories:[{path,strategy}]}` | 每次调用 fan-out；无 TTL cache |
| `GET /slimapi/sessions/{sid}/latest-message-id` | `{"id":str|null,"createdAt":ms}`；ETag `"<id>"`/`"empty"`；304 命中 | `createdAt` 是 `info.time.created` **非** updatedAt |
| `GET /slimapi/sessions/{sid}/messages?mode=skeleton` | `List<MessageWithParts>` 裸数组 + 原样 `X-Next-Cursor`/`Link`；`Cache-Control:no-store` | skeleton upstream body >64MiB → 413；转换槽满 → 502 |
| `GET /slimapi/sessions/{sid}/since/{ts}` | 返回 `time.updated >= ts` 的骨架消息；`?limit/before` 分页（v1-contract §5） | 锚点 = `updatedAt` 时间戳，非 messageId |
| `GET /slimapi/sessions/{sid}/messages/{mid}?mode=full` | 单 `MessageWithParts` + `Cache-Control:no-store`；>32MiB → 413 | 客户端按 `messageId+partId` 替换（非追加） |
| `GET /slimapi/sessions/status?directory=` | 原 map（不裁剪） | directory 必填 ∈ allowlist |
| `GET /slimapi/sessions/{sid}/status` | 单 Status 对象或 idle 对象 | fan-out 失败 → 503（禁误报 idle） |
| `GET /slimapi/questions?directory=&directory=`（repeated） | `{"items":[{<原 question>,"directory","routeToken"}],"errors":[..]}` | repeated 参数，**禁**逗号串；routeToken HMAC，exp=1h |
| `GET /slimapi/permissions?directory=&directory=`（repeated） | 同上（permission 形态） | 同上 |
| `GET /slimapi/events`（SSE） | `text/event-stream`；data 通常为 `SSEEvent{directory,payload}`；帧类型：`session.digest`（debounce 250ms，含 `archived`）+ `question.asked` / `permission.*`（直推）+ `server.connected` / `server.heartbeat` / `resync`（v1-contract §3） | queue=256 背压；慢消费者被 STOP 断开 |
| `GET /slimapi/health` | `{"sidecar":{"ok","version"},"schema":{"degraded"},"server":{"api_version","accepted_client_versions"}}` | **liveness**，不代表 upstream 可达 |
| `GET /slimapi/ready` | `{"upstream":{"ok","latencyMs"},"schema":{"degraded"},"server":{"api_version"}}` | 探 upstream；<300 → 200，否则 503 |

### 5.2 A 桶写路径（routeToken + directory）

slimapi 写路径（`POST /slimapi/questions/{qid}/reply|reject`、
`POST /slimapi/sessions/{sid}/permissions/{pid}`）：

- body 必须带 `routeToken`（HMAC-SHA256 签名，base64url payload + base64url sig；
  payload `{v,kind,requestID,sessionID,directory,iat,exp}`，exp=1h）。
- slimapi 校验：签名 / 过期 / kind / path-id 一致 / directory ∈ allowlist。
- **routeToken 剥离后**透传给 opencode（upstream body 只剩 `{answers}` 或
  `{response}`；directory 由 token 还原后作为 `?directory=` + header 注入）。
- 客户端**不**直接传 directory 给写路径——token 已封装。
- **mutation 不双发**：timeout → 504，客户端**不得**自动重试（POST 可能
  已被 upstream 接收）。

routeToken 来源：客户端先调 A9/A10（聚合）拿到带 token 的 item；用户应答时
原样回传。

### 5.3 catch-all 反代（非 A 非 B 的边界）

slimapi `/{path:path}` catch-all（INTERFACE_MAP §4）：

- method/query/body/header 流式透传给 `http://127.0.0.1:4096/{path}`。
- `/event` / `/global/event`：read timeout=None，禁缓冲，`aiter_raw()` 保
  `Content-Encoding`。
- 以 `/command` 结尾：read=300s（对齐客户端 `commandApi`）。
- 其它：read=30s，write=300s。
- **剥** hop-by-hop 头（Connection/Keep-Alive/TE/Trailer/Transfer-Encoding/
  Upgrade/Proxy-*/Host）。
- WebSocket → 501。
- **未知 `/slimapi/**` 路径 → 404 `{"code":"thin_route_not_found"}`，不透传**。

> 这就是 B 桶端点的工作机制：客户端打 slimapi URL → catch-all 反代 → opencode。
> 客户端**看不到** opencode。

---

## 6. B 桶明细 + 写路径 directory / routeToken 注入

### 6.1 当前写路径的 directory 注入机制（legacy / B 桶）

[`DirectoryHeaderInterceptor`](../app/src/main/java/cn/vectory/ocdroid/data/repository/http/DirectoryHeaderInterceptor.kt)
对**所有**请求（REST + SSE）执行：

1. 读 caller-supplied `X-Opencode-Directory` header（`@Header` 参数）。
2. 若无 `X-Opencode-Skip-Dir` marker：保留 header；若有 marker：剥 marker 但
   保留 caller 显式 header。
3. GET/HEAD：把 directory 镜像到 `?directory=` query（非 `/api/` 路径）或
   `?directory=` + `?location[directory]=` （`/api/` 路径，v2 deep-object 形态）。
4. POST/PUT/PATCH/DELETE：**不**注入 query（body 不能依赖 query 解析）。

当前写路径（B3 / B11 / B16 / B18 / B19 / B20 / B24）走的就是：

- Retrofit 方法显式声明 `@Header("X-Opencode-Directory") directory: String?`。
- OkHttp 拦截器保留 caller header + 不注入 query（POST 类）。
- 在省流模式下：slimapi catch-all 看到 `X-Opencode-Directory` + body → 透传
  给 opencode（directory 由 header 解析）。

**问题**：B16 / B19 / B20 在 slimapi 下**应**走 A 桶的 routeToken 路径，
而非依赖 header directory。当前 B 桶的 directory header 模式 slimapi catch-all
也能转发，但**无 routeToken 校验**——丢失了 slimapi 的安全/路由保证。这是
迁移项 M6（§9）。

### 6.2 B 桶各写路径的 directory 传递方式

| 调用点 | 当前传递方式 | 省流目标 |
|---|---|---|
| B3 `POST /session`（create） | `@Header(directory)` | 保留（catch-all 透传 directory header OK） |
| B11 `POST /session/{id}/prompt_async` | 默认 interceptor 注入 | 保留 |
| B16 `POST /session/{id}/permissions/{pid}` | body `{response}`（**无 directory**） | **改**：A 桶写路径，body 加 `routeToken` |
| B18 `GET /question` | `@Header(directory)` | **改**：A9 聚合（repeated `?directory=`） |
| B19 `POST /question/{reqId}/reply` | `@Header(directory)` + body `{answers}` | **改**：A 桶写路径，body 加 `routeToken`，剥离 directory header |
| B20 `POST /question/{reqId}/reject` | `@Header(directory)` | 同上 |
| B24 `POST /session/{id}/command` | `@Header(directory)` + body `{command,arguments,agent}` | 保留（catch-all 透传） |

### 6.3 B 桶与 A 桶的双轨

迁移期间，同一逻辑调用可能既有 B 桶实现（legacy 模式）又有 A 桶实现（省流模式）。
推荐实现：在 `OpenCodeRepository` 内按 base URL 是 slimapi 还是 opencode 选 API
实例（同一 Retrofit interface、不同 base URL + 不同拦截器链）。**不**用 if/else
散落到每个方法（§1.2 已论证）。

---

## 7. C 桶违规清单（**最重要的发现**）

> 下列调用在省流模式下**绕过 slimapi**，违反"切换服务器"模型的核心约束。
> 每条标注：调用点、违规性质、为何算违规、迁移要求。

### C1 — `HttpImageHolder.downloadAndCache` 裸 OkHttp 拉图（不挂 directory/version/header）

- **调用点**：[`ui/util/HttpImageHolder.kt:282-316`](../app/src/main/java/cn/vectory/ocdroid/ui/util/HttpImageHolder.kt)
  ```kotlin
  private suspend fun downloadAndCache(url: String) {
      ...
      val request = Request.Builder().url(url).build()
      imageHttpClient.newCall(request).execute().use { response -> ... }
  }
  ```
- **URL 来源**：`DataUriImageTransformer.transform(link)`（[`ui/util/DataUriImageTransformer.kt:84-86`](../app/src/main/java/cn/vectory/ocdroid/ui/util/DataUriImageTransformer.kt)）
  从 markdown 图片链接收到 `https://` / `http://` URL 后转发到此处。
- **违规性质**：`url` 是从消息 markdown 内容里**原样**抽取的绝对 URL。当服务端
  在消息里嵌入 workspace 文件链接（如 opencode file proxy URL `http://<host>:4096/file/...`
  或经 stunnel 的 `https://<host>:14096/file/...`）时，这个 GET **直连 opencode**，
  绕过 slimapi——客户端的 `imageHttpClient` 是独立的 OkHttpClient（[`HttpImageHolder.kt:143-148`](../app/src/main/java/cn/vectory/ocdroid/ui/util/HttpImageHolder.kt)），
  **不**经过 `DirectoryHeaderInterceptor`、**不**经过版本头拦截器、**不**经
  slimapi 的 catch-all。
- **为何算违规**：省流模式下用户选 slimapi 为 server，期待所有 opencode 形态
  流量经 slimapi。但 markdown 图片如果用 opencode absolute URL 加载，会**绕过**
  slimapi——既丢失省流（图片字节大）、又破坏"无直连 opencode"不变量。
- **复杂度**：当前 `imageHttpClient` 用 `OpenCodeRepository.currentSslConfig()`
  同步 SSL 配置（[`HttpImageHolder.kt:166-179`](../app/src/main/java/cn/vectory/ocdroid/ui/util/HttpImageHolder.kt)），
  所以 hostPort/TOFU/mTLS 与 REST 一致——但 **URL rewriting 未做**。
- **迁移要求**：
  - **M-C1a**（短期）：识别 host 等于当前 `HostConfig.hostPort` 的 URL，rewrite
    到 slimapi base（与 selected server 同 host:port）。
  - **M-C1b**（推荐）：让服务端（opencode 或 slimapi）总是输出**相对**路径或
    `data:` URI（`FilePart.url` 走 slimapi skeleton 已支持短 `http(s)` 留 + `data:`
    null + `hasFull`——见 INTERFACE_MAP §5 `_file()`）。
  - **M-C1c**（兜底）：真正的外网图片（imgur 等）继续走 `imageHttpClient`，
    归 D 桶（§8 D3）。

### C2 — `OpenCodeRepository.captureServerCert` 一次性 mTLS/TOFU 探针绕过 slimapi

- **调用点**：[`OpenCodeRepository.kt:317-365`](../app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt)
  ```kotlin
  val normalizedUrl = (if (baseUrl.startsWith("http")) baseUrl else "https://$baseUrl")
      .trimEnd('/') + "/global/health"
  val requestBuilder = Request.Builder().url(normalizedUrl).header(SKIP_DIR_HEADER, "1")
  oneShot.newCall(requestBuilder.build()).execute().use { /* drain */ }
  ```
- **违规性质**：构造独立的 `OkHttpClient`（`CaptureTrustManager` + permissive
  hostnameVerifier），打 `{baseUrl}/global/health`——**绕过 slimapi** 直探后端。
- **为何算违规**：这个探针的目的是捕获 leaf 证书给 TOFU 对话框。在省流模式下：
  - 用户选的是 slimapi server，stunnel mTLS 在 slimapi 前面，TOFU 应该是 slimapi
    sidecar 的证书；
  - 但探针打 `/global/health` 时，**如果 baseUrl 是 slimapi**，slimapi 的
    catch-all 会反代到 opencode（INTERFACE_MAP §4 catch-all 透传 `/global/health`）；
    然而探针期望的是握手到 slimapi 自己的证书——catch-all 是 HTTP 层反代，TCP/TLS
    握手发生在 stunnel→slimapi，所以 leaf 是 slimapi/stunnel 的（OK）。
  - 真正问题：这是**绕过版本契约**——`/global/health` 是 catch-all 路径，不带
    `X-Slimapi-Version` 头也能透传（design-v2 §9.8）；语义上 `/global/health`
    返回的是 **opencode** 的 health，不是 **slimapi** 的 health。在省流模式下，
    用户应该看 slimapi `/slimapi/health` + `/slimapi/ready` 双信号（INTERFACE_MAP §4）。
- **迁移要求**：
  - **M-C2**：`captureServerCert` 改为按 base URL 决定路径——若 base 是 slimapi，
    探 `/slimapi/health`（带版本头），让客户端拿到 slimapi 的 leaf + 透传 ready
    结果；保留对 `/global/health` 的回退仅用于 legacy opencode 模式。

### C3 — `OpenCodeRepository.checkHealthFor` 健康探针绕过 slimapi

- **调用点**：[`OpenCodeRepository.kt:429-462`](../app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt)
  ```kotlin
  val normalizedUrl = (if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl")
      .trimEnd('/') + "/global/health"
  val requestBuilder = Request.Builder().url(normalizedUrl).header(SKIP_DIR_HEADER, "1")
  ...
  client.newCall(requestBuilder.build()).execute().use { res -> ... }
  ```
- **违规性质**：与 C2 同——host 列表 "Test" 按钮探 `{baseUrl}/global/health`。
  在省流模式下 baseUrl 是 slimapi，slimapi catch-all 会透传到 opencode 的
  `/global/health`——**功能上能 work**（catch-all 透传），但语义错位：
  - 返回的 `HealthResponse.version` 是 **opencode** 的版本，不是 slimapi 的；
  - 丢失了 slimapi 的 `schema.degraded` / `server.api_version` /
    `accepted_client_versions` 自检信号；
  - 不带 `X-Slimapi-Version` 头（虽然 catch-all 不强制，但客户端无法区分"探
    opencode"还是"探 slimapi"）。
- **为何算违规**：违反"省流模式 = 选 slimapi 为 server"的语义——用户在 host
  列表 Test 一个 slimapi 入口，期待知道 slimapi 是否健康；客户端却透传探了
  opencode。当 slimapi 进程挂但 opencode 还活着时，Test 会**误报健康**。
- **迁移要求**：
  - **M-C3**：`checkHealthFor` 按 base URL 决定路径——slimapi 入口探
    `/slimapi/ready`（200=upstream 可达；503=不可达），并附 `/slimapi/health`
    读版本契约。legacy opencode 入口仍探 `/global/health`。

### C4 — `HostConfig.DEFAULT_SERVER` / `HostProfile.defaultDirect` / `SettingsManager.DEFAULT_SERVER` 硬编码 `localhost:4096`

- **调用点**：
  - [`HostConfig.kt:88`](../app/src/main/java/cn/vectory/ocdroid/data/repository/HostConfig.kt) `const val DEFAULT_SERVER = "http://localhost:4096"`
  - [`HostProfile.kt:85`](../app/src/main/java/cn/vectory/ocdroid/data/model/HostProfile.kt) `serverUrl: String = "http://localhost:4096"`
  - [`SettingsManager.kt:873`](../app/src/main/java/cn/vectory/ocdroid/util/SettingsManager.kt) `const val DEFAULT_SERVER = "http://localhost:4096"`
- **违规性质**：这是**默认值**，不是 bypass 路径——用户首次安装时无 profile，
  fallback 到 `localhost:4096` 直连 opencode。
- **为何算违规**（边缘）：严格说这不是 C 桶违规——省流模式由用户显式选 slimapi
  server 触发，默认值只是"未配置时的 legacy 行为"。但**风险点**是：用户切到
  slimapi 后，如果任何路径 fallback 到默认（如某次 configure 失败回退），会
  **默默**回到直连 opencode，绕过 slimapi。当前 `OpenCodeRepository.configure`
  没有"回退到 DEFAULT_SERVER"逻辑，所以实际不会触发——但任何未来"重置"功能要
  警惕。
- **迁移要求**：
  - **M-C4**：保持 `DEFAULT_SERVER` 为 legacy opencode（不破坏向后兼容）；但在
    slimapi 模式下加 invariant 检查——若 `HostConfig.baseUrl` 突然变成
    `DEFAULT_SERVER` 而用户之前选过 slimapi，触发告警（防 silent fallback）。

### C5 — `OpenCodeApp.warmUpWebViewAfterLaunch` 已删除（确认无残留）

- **历史调用点**：[`OpenCodeApp.kt:52-57`](../app/src/main/java/cn/vectory/ocdroid/OpenCodeApp.kt)
  R-06 注释指出此函数已**删除**（原预渲染一个 throwaway WebView 加速首屏）。
- **当前状态**：无 HTTP egress；保留此条仅为记录"已检查无残留"。

> **C 桶统计**：5 条（C1-C4 是真违规/风险点，C5 是已清结的 historical note）。
> **必须迁移的**：C1 / C2 / C3（功能性违规）；C4 是 invariant 监控（无功能
> 改动）。

---

## 8. D 桶外部调用清单

> 下列调用 host 不匹配所选 server，省流模式不影响。

### D1 — `OpenCodeRepository.activateTunnel` 隧道表单认证

- **调用点**：[`OpenCodeRepository.kt:902-940`](../app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt)
  ```kotlin
  val client = clientFactory.tunnelClient(hostPort)
  val formBody = FormBody.Builder()
      .add("persist_auth", "off")
      .add("pw", password)
      .build()
  val request = okhttp3.Request.Builder().url(tunnelUrl).post(formBody).build()
  val response = client.newCall(request).execute()
  ```
- **URL 来源**：`tunnelUrl` 是用户在 host profile 里配置的隧道入口（独立于
  `HostConfig.baseUrl`，是一个**前置认证**端点）。
- **归类理由**：隧道是 opencode 前的反向代理认证层（form-encoded POST），**不**
  是 opencode 形态 API、**不**是 slimapi 形态 API。它认证后建立 cookie/state，
  之后所有 API 流量走 `HostConfig.baseUrl`（用户选的 server）。
- **省流影响**：无。用户选 slimapi 为 server 后，隧道认证仍是对隧道的，与
  slimapi 独立。

### D2 — `MarkdownWebPreviewPane` 外链浏览器跳转

- **调用点**：[`ui/files/MarkdownWebPreviewPane.kt:268-273`](../app/src/main/java/cn/vectory/ocdroid/ui/files/MarkdownWebPreviewPane.kt)
  ```kotlin
  "link" -> {
      val href = payload.optString("href")
      if (href.startsWith("http://") || href.startsWith("https://")) {
          mainHandler.post { onExternalLink(href) }
      }
  }
  ```
  + `shouldOverrideUrlLoading`（[:214](../app/src/main/java/cn/vectory/ocdroid/ui/files/MarkdownWebPreviewPane.kt)）
- **归类理由**：这是用户主动点击 markdown 里的外链 → 触发 `ACTION_VIEW` Intent
  交给**系统浏览器**打开。**不**走 in-process HTTP。
- **省流影响**：无。

### D3 — `HttpImageHolder.downloadAndCache` 外网图片（imgur 等）

- **调用点**：同 C1（[`HttpImageHolder.kt:282-316`](../app/src/main/java/cn/vectory/ocdroid/ui/util/HttpImageHolder.kt)），
  但 URL host 不属于当前 `HostConfig.hostPort`。
- **归类理由**：消息 markdown 里嵌的是真正的外网图片（如 `https://i.imgur.com/xxx.png`），
  与 opencode/slimapi 体系无关。
- **省流影响**：无。
- **与 C1 的边界**：**同一调用点**按 URL host 二分——host 匹配 selected server
  → C1（违规，须迁移）；host 不匹配 → D3（外部，保留）。

### D4 — `MarkdownWebPreviewPane` 本地 asset / about:blank

- **调用点**：[`MarkdownWebPreviewPane.kt:224`](../app/src/main/java/cn/vectory/ocdroid/ui/files/MarkdownWebPreviewPane.kt)
  ```kotlin
  loadUrl("file:///android_asset/web_preview/preview.html")
  ```
  + [:242](../app/src/main/java/cn/vectory/ocdroid/ui/files/MarkdownWebPreviewPane.kt)
  `webView.loadUrl("about:blank")`（释放时清理）
- **归类理由**：本地 asset / 占位 URL，**无 HTTP egress**。
- **省流影响**：无。
- **保留此条的理由**：完整枚举所有 `loadUrl` 调用点，证明 WebView 不触网。

> **D 桶统计**：4 条。**注意**：当前代码**没有**更新检查（无 GitHub releases
> 调用）、**没有**遥测/分析、**没有**OAuth/OIDC、**没有**模型列表 CDN——所以
> D 桶仅含隧道认证 + 浏览器外链 + 外网图片 + 本地 asset。

---

## 9. 迁移检查清单

> 客户端要使省流模式成为干净的"切换服务器"必须完成下列条目。**19 条**。

### M1 — 加 `X-Slimapi-Version` 拦截器（**must**）

**做什么**：在 `OkHttpClientFactory.baseBuilder` 链上加一个新拦截器
`SlimapiVersionInterceptor`，按 request URL 路径前缀 `/slimapi/` 注入
`X-Slimapi-Version: 1` 头。SSE 请求也要经过（`SSEClient` 用 `sseHttp`，
`sseHttp` 走同一 base chain）。

**为何**：缺头 → slimapi `400 version_required`；SSE 重连会陷入 400 死循环
（每次重连都缺头）。

**改哪里**：
- 新建 `data/repository/http/SlimapiVersionInterceptor.kt`。
- 在 [`OkHttpClientFactory.baseBuilder`](../app/src/main/java/cn/vectory/ocdroid/data/repository/http/OkHttpClientFactory.kt)
  `.addInterceptor(...)` 链上挂它（在 `directoryHeaderInterceptor` 之后）。

**版本来源**：客户端硬编码 `1`（与 slimapi 当前 `SERVER_API_VERSION=1` 对齐）；
未来 slimapi bump major 时同步客户端常量。

### M2 — health 自检（**must**）

**做什么**：profile 切到 slimapi server 后，连接 bootstrap 时读
`GET /slimapi/health`（带 M1 版本头），校验：

- `server.accepted_client_versions` 包含客户端版本（`1`）。
- `schema.degraded` 提示 UI（若 true，skeleton 自动降级 full，仍可用）。
- `sidecar.ok == true`。

若版本不兼容 → UI 显示"客户端版本与 sidecar 不兼容"错误（不进入省流模式，
fallback 到 legacy）。

**改哪里**：[`service/streaming/ConnectionBootstrapEngine.kt`](../app/src/main/java/cn/vectory/ocdroid/service/streaming/ConnectionBootstrapEngine.kt)
`:134`（当前调 `repository.checkHealth()`）；新增
`OpenCodeRepository.checkSlimapiHealth()` 走 A12 端点。

**与 ServerCompatProfile 的关系**：[`ServerCompatProfile`](../app/src/main/java/cn/vectory/ocdroid/data/repository/ServerCompatProfile.kt)
当前只解析 opencode semver；需扩字段或新增 `SlimapiCompatProfile`（独立）。

### M3 — 消除 C 桶违规（**must**）

逐条见 §7：

- **C1**：`HttpImageHolder` URL rewriting（识别 host == 当前 server 的图片 →
  rewrite 经 slimapi；外网图片走 D3）。短期 M-C1a，长期 M-C1b。
- **C2 / C3**：`captureServerCert` / `checkHealthFor` 按 base URL 选路径
  （slimapi → `/slimapi/health` + `/slimapi/ready`；legacy → `/global/health`）。
- **C4**：slimapi 模式下 invariant：禁止 fallback 到 `DEFAULT_SERVER`。

### M4 — 加 routeToken 字段到 question/permission 模型（**must**）

**做什么**：扩 `QuestionRequest` / `PermissionRequest` data class：

```kotlin
@Serializable
data class QuestionRequest(
    val id: String,
    val sessionID: String? = null,
    // 新增：slimapi 聚合返回时携带；应答时原样回传
    val directory: String? = null,
    val routeToken: String? = null,
    // ... 已有字段
)

@Serializable
data class PermissionRequest(
    val id: String,
    val sessionID: String? = null,
    val directory: String? = null,
    val routeToken: String? = null,
    // ... 已有字段
)
```

**为何**：A9/A10 聚合响应每 item 带这两个字段；客户端应答时 body 须带
`routeToken`，directory 由 token 还原（不再用 header directory）。

**改哪里**：`data/model/Question.kt` / `Permission.kt`；聚合响应改 envelope
`{items, errors}` 形态（design-v2 §1.7）。

### M5 — SSE 端点切换 + 帧类型扩展（**must**）

**做什么**：省流模式下 `SSEClient.connect` 把 URL 从 `/global/event` 改为：

```
GET {base}/slimapi/events
X-Slimapi-Version: 1
Accept: text/event-stream
Cache-Control: no-cache
X-Opencode-Directory: {dir}  # 仍带（双保险）
```

v1 契约（v1-contract §3）去掉 `sessionId` / `?stream` 参数——实例级全实例流。

**EventSource 回调层**加 `type` 参数分派（当前被忽略，§4 表 ○ 行）：

- `type == "resync"` → 触发冷启动级 REST 快照 + SSE 增量（v1-contract §4）。
- 其它 → 走原 `data.payload.type` 路径。

**改哪里**：[`SSEClient.kt:99-118`](../app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt)
`connectOnce`；新增"省流模式标志"由 `HostConfig.hostPort` 或 base URL 派生
（**不**新增 `slimMode` 字段——靠 host:port 识别）。

### M6 — resync reducer（**must**）

**做什么**：`event:resync` 帧到达时：

1. 标记所有 in-memory sessionWindowCache 为 stale。
2. 复用冷启动流程（v1-contract §4：resync = 复用冷启动）：
   - GET `/slimapi/sessions` + `/slimapi/questions` + `/slimapi/permissions` 快照。
   - 对当前打开 session 调 `GET /slimapi/sessions/{sid}/since/{lastSeenUpdatedAt}`
     （v1-contract §5：锚点 = `updatedAt` 时间戳）。
3. SSE 接力增量。

**为何**：slimapi 明确无 replay（v1-contract §3）；客户端必须主动 catch-up。

**改哪里**：新增 `ui/controller/SlimapiResyncReducer.kt`（类似
`ForegroundCatchUpController`）；hook 进 `SseEventBridge` 的 control 通道。

### M7 — ~~`thin.session.dirty` reducer~~ （**v1 去掉**）

**v1 去掉**：v1-contract §3 不再有 `thin.session.dirty` 事件；改为 `session.digest`
debounce 250ms（每 session 独立 debounce，仅发有变化的字段）。客户端无需额外
reducer——`session.digest` 已包含 session 状态变化信息。

### M8 — 消息分页迁移到 skeleton（**must，省流核心**）

**做什么**：`OpenCodeRepository.getMessagesPaged` 在省流模式下打 A4 而非 B10：

```
GET /slimapi/sessions/{sid}/messages?limit=40&before={cursor}&mode=skeleton&directory={dir}
```

返回的 `List<MessageWithParts>` 走原 `MessagesPage` 形态（`X-Next-Cursor` 原样
透传）。骨架字段（`hasFull` / `omitted`）由 M9 处理。

**改哪里**：[`OpenCodeRepository.kt:527`](../app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt)
`getMessagesPaged`；按 base URL 选 endpoint。

### M9 — Part 模型扩字段 + 展开 hook（**must**）

**做什么**：

1. 扩 `Part` data class：
   ```kotlin
   val hasFull: Boolean? = null
   val omitted: List<String>? = null
   ```
   （`ignoreUnknownKeys=true` 当前会丢弃 → 必须显式声明。）

2. UI 在渲染 `hasFull == true && omitted != null` 的 part 时显示"展开"按钮。

3. 展开动作调 A6：`GET /slimapi/sessions/{sid}/messages/{mid}?mode=full` →
   按 `messageId + partId` **替换**该 part（非追加）。

**改哪里**：`data/model/Message.kt` 的 Part；UI 新增展开 affordance；
`OpenCodeRepository.expandMessagePart`。

### M10 — question/permission 迁移到聚合（**must**）

**做什么**：省流模式下：

- 读：`OpenCodeRepository.getPendingQuestions` / `getPendingPermissions` 改调
  A9/A10（`?directory=&directory=` repeated，跨 workdir 聚合）。响应 envelope
  `{items, errors}`。
- 写：`replyQuestion` / `rejectQuestion` / `respondPermission` 改调 A 桶写路径
  （body 带 `routeToken`，header directory 移除）。

**改哪里**：[`OpenCodeApi.kt:134-150`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt)
question/permission 三方法；新增 `OpenCodeApiSlim` interface 或在现有 interface
加方法。

### M11 — sessions status 迁移（**must**）

**做什么**：省流模式下 `getSessionStatus` 改调 A7（`?directory=` 必填）。

**改哪里**：[`OpenCodeApi.kt:57`](../app/src/main/java/cn/vectory/ocdroid/data/api/OpenCodeApi.kt)。

### M12 — sessions list 迁移到 skeleton（**should**）

**做什么**：省流模式下 `getSessions` / `getSessionsForDirectory` 改调 A1
（`/slimapi/sessions?directory=&roots=&limit=`），剥字段减字节。

**优先级**：should（不是 must——B2 经 catch-all 也能 work，只是不省流）。

### M13 — latest-message-id 探针（**should**）

**做什么**：`probeLatestMessageId` 改调 A3，带 `If-None-Match` 复用 ETag。

**优先级**：should（B 桶也能探，但字节大；A3 是省流优化）。

### M14 — GET 侧 circuit breaker（**should**）

**做什么**：连续 3 次 slimapi transport/5xx → 禁用 thin 路径 5 分钟 →
half-open 探测。**mutation 不双发**（已确保：当前客户端 POST 类不重试）。

**优先级**：should（design-v2 §3.5 推荐项，提升 robustness）。

### M15 — ~~`?stream=true` 参数~~ （**v1 去掉**）

**v1 去掉**：v1-contract §3 去掉 `?stream` 参数和 `text.delta` 输出。

### M16 — ~~`sessionId` 列表构造逻辑~~ （**v1 去掉**）

**v1 去掉**：v1-contract §3 去掉 `sessionId` 参数——实例级全实例流，每事件
自带 `directory` 字段，客户端按 `directory` 过滤。

### M17 — `ServerCompatProfile` 扩展（**已落地**）

**已落地**：`ServerCompatProfile` 已扩展 slimapi 字段（`slimapiServerApiVersion` /
`slimapiAcceptedMin` / `slimapiAcceptedMax` / `slimapiSidecarOk` /
`slimapiSchemaDegraded`）+ `isSlimapiClientAccepted()` fail-closed 自检
（v1-contract §1）。UI 版本不兼容阻塞对话框已落地。

### M18 — TOFU 与 mTLS 适配（**must**）

**做什么**：省流模式下的 TLS 握手发生在 stunnel（mTLS）→ slimapi。客户端的
TOFU 捕获（C2 迁移后）应指向 slimapi 的 leaf；mTLS 客户端证书由 stunnel 验证
（与 legacy 一致，无需改 SslConfig 链）。

**注意**：slimapi sidecar 自身监听 `127.0.0.1:4097` 无应用层鉴权
（INTERFACE_MAP §0）——mTLS 终结在 stunnel，客户端看到的是 stunnel 证书。

### M19 — mutation 幂等性审计（**should**）

**做什么**：审计所有 POST 类调用（B3 / B11 / B12 / B13 / B14 / B15 / B16 /
B19 / B20 / B24），确保重试逻辑**绝不**自动重发 POST。当前已满足
（`runSuspendCatching` 不重试），但 M14 circuit breaker 实现时要再确认。

---

## 10. 待定 / 缺口 / 风险

| # | 缺口 / 风险 | 影响 | 建议处理 |
|---|---|---|---|
| R1 | C1（图片 host rewriting）的判定边界：当 opencode 用相对路径或 file proxy URL 时，规则不明 | 省流模式下图片加载可能仍绕过 slimapi | 服务端侧统一输出相对路径或 `data:` URI（`_file()` 已支持，INTERFACE_MAP §5） |
| R2 | `sessionId` repeated query 上限 16——客户端 unread soak 监控的 root session 数可能超 | 超 16 时 SSE 拒收（422） | 客户端限制 unread 监控 ≤ 16；或 slimapi 放宽上限 |
| R3 | M4 envelope `{items, errors}` 改了 QuestionRequest/PermissionRequest 形态——可能与现有 UI reducer 不兼容 | 渲染 pending 卡片可能丢失字段 | 在 OpenCodeRepository 层扁平化（envelope.items + 在 item 上附 directory/routeToken），UI 无感 |
| R4 | M9 Part 扩字段后，`ignoreUnknownKeys=true` 的副作用：未来服务端加新字段客户端不感知 | 字段静默丢弃 | 加 unit test 锁定已知字段；新字段显式声明 |
| R5 | M5 + M15 + M16 是耦合改动（端点切换 + stream + sessionId），单做一项不工作 | 迁移必须原子 | 列为单一 PR |
| R6 | slimapi catch-all 透传 `/global/health`（C2/C3）功能上 work——可能误以为"已经能用" | 用户在 slimapi 模式下 Test 健康，但 slimapi 进程挂时仍报健康（M-C2/M-C3 必做） | M3 必做项 |
| R7 | M14 circuit breaker 状态如何跨 SSE 重连持久化 | 客户端可能反复打挂掉的 sidecar | 单例 StateFlow，5min 冷却 |
| R8 | 双 stunnel 入口（14096 + 14097）的 profile 配置 UI——用户怎么区分？ | 用户可能误选 | host profile 加 `serverType: "opencode" \| "slimapi"` 标志（仅 UI 提示，不影响 HostConfig.baseUrl 派生） |
| R9 | slimapi `/slimapi/events` queue=256 背压——客户端慢消费被 STOP 断开后如何识别？ | 与心跳看门狗混淆 | onClosed 时检查 slimapi-specific 头（待 slimapi 暴露） |
| R10 | `OpenCodeApiV2`（B35/B36）当前 debug-only——若未来切到 v2 主路径，目录路径前缀是 `/api/`，与 catch-all 的 `/command` 后缀检测有交互（catch-all 给 `/api/...command` 也设 300s 读超时？） | 边界情况超时配置异常 | 监控；当前 v2 端点无 `/command` 路径，无影响 |

---

## 11. 与现有文档的关系

| 现有文档 | 关系 |
|---|---|
| [`docs/ui-style-spec.md`](./ui-style-spec.md) | UI 共享原语规范；M9 展开 hook 的 UI 应遵循其三层规则（展开 affordance 走 `AppFormDialog` 或 inline button） |
| [`docs/build-apk.md`](./build-apk.md) | 构建发版；本文迁移不改 build 流程 |
| [`docs/mtls-setup-guide.md`](./mtls-setup-guide.md) | mTLS 配置；M18 沿用其 TOFU/mTLS 信任模型 |
| [`docs/emulator-debug.md`](./emulator-debug.md) | 模拟器调试；本文迁移测试可在模拟器跑（不涉及设备） |
| **未发现** ocdroid 已有的 SSE-spec 或省流 API spec 文档 | 本文件与 [`docs/sse-client-spec.md`](./sse-client-spec.md) 是首版 |

`docs/ocmar/specs/` 下的 notification-background / unread-lifecycle / FGS design
系列与本文 M6 / M7 / M16 有交互（unread soak 是 sessionId 列表的来源），但
主题不同，本文不重复。

---

## 12. 修订记录

| 日期 | 改动 |
|---|---|
| 2026-07-18 | 初版。从源码枚举全部 HTTP/SSE 调用；交叉引用 oc-slimapi INTERFACE_MAP 与 design-v2。 |
| 2026-07-18 | 对齐 v1-contract.md：版本契约引用 v1-contract §1；A11 SSE 端点去掉 `sessionId`/`?stream`；A5 锚点改为 `updatedAt` 时间戳（`/since/{ts}`）；M5/M15/M16 更新为 v1 简化；M6 resync 改为复用冷启动；M7 `thin.session.dirty` v1 去掉；M17 已落地。 |
