# ocdroid-lite 简化方案（RFC）

> ⚠️ **本 RFC 已被取代（SUPERSEDED）**
>
> 此文档是 V1→V2 迁移的规划记录，旨在描述"计划要删除/变更的内容"。
> **V2 迁移已落地执行**（bundle `bundle-slimv2-20260728`）——ocdroid 现运行于 `lite-v2-dev` 分支，使用 oc-slimapi V2 线协议（`X-Slimapi-Version: 1` → `2` breaking bump）。
>
> **本文档保留为历史归档**，其所有 V1 时代的端点/字段/frame 引用描述的是**迁移前的状态**及**计划中的删除动作**，**非当前线协议面**。
> 当前权威线协议请见 oc-slimapi `docs/specs/v2-contract.md`（及 `CLIENT_CHANGES.md`）。

> **状态**：Draft v0.2 / **（历史归档）已由 V2 迁移落地取代，见上方 superseded banner**
> **范围**：ocdroid 客户端 + oc-slimapi sidecar 协同简化
> **基线**：ocdroid v0.13.x、oc-slimapi v1 contract rev M（2026-07-27）
> **修订日志**：见文末
>
> **v0.2 关键修订**（详见 §11）：
> - SlimApi.kt 实际 **13 个方法**（非 12）；Tier 3 对应 **10 个**（非 9）
> - `respondSlimapiPermission` 在 v0.1 被漏数
> - `/full?ids=` batch **非"完全未触发"**——客户端有调用，sidecar 返 404 → fallback
> - `/sessions/{sid}/children` **客户端 SlimApi 中不存在**——只 sidecar 侧删
> - 3 个 routeToken DTO 在 `OpenCodeApi.kt` 而非 `Slimapi.kt`
> - **`_part_state` / `_session_event_seq` 必须保留**（驱动 `contentRevisions` digest，非 fingerprint 专属）
> - 测试影响实测 **删 ~361 用例 / 改 ~61 / 增 ~13**（v0.1 严重低估）
>
> **引用约定**：
> - 本文件引用 ocdroid 内部代码用相对路径（如 `app/src/.../SlimSseStateMachine.kt:1038`）。
> - 引用 oc-slimapi 用 `../../oc-slimapi/...` 前缀（同主机兄弟仓库）。
> - 与 `docs/specs/architecture.md` 冲突时：本文件是**待执行 plan**，architecture.md 是**已落地规范**；本方案落地后须同步更新 architecture.md。

---

## 0. TL;DR

把当前「双 API 变体长期共存」架构简化为「**透传为主 + slim 增强**」：

- **默认形态**：客户端通过 sidecar catch-all 透传 opencode legacy API（包括 `/global/event` SSE）。Sidecar 表现为「带可选增强的 opencode 代理」。
- **增强形态**：客户端在 host profile 里启用 slim 时，对**三个端点**（`/slimapi/messages?mode=skeleton`、`/slimapi/sessions`、`/slimapi/events`）走 slim 路径；其余全部透传。
- **模式切换**：切换 host profile = 重启应用，不做运行时切换事务。
- **预期收益**：客户端代码量降到 ~50-60%；状态机相关 ~4200 行删除；7 轮 TOCTOU 修复链全部退役。实测流量节省率从 82-87% 降到 ~75-80%（损失 < 10%）。
- **独立收益**：修两个 client 高频轮询 bug（与架构改动无关），单周流量节省 ~15 GB。

---

## 1. 背景与动机

### 1.1 现状：双 API 变体共存的复杂度

`docs/specs/architecture.md:14-20` 定义了 ocdroid 同时兼容两套服务端 API：

- **legacy**：直连 opencode，`/session`、`/global`、`/file`、`/vcs`、`/question`、`/permission`。
- **slim（oc-slimapi sidecar）**：`/slimapi/...`，带 watermark / routeToken / 聚合信封 / 版本协商。

为支撑共存，`docs/specs/architecture.md:73-78` 立了三条铁律（共享形状 / 接口封装差异 / 差异下沉）。这套架构的代价：

- `app/src/main/java/cn/vectory/ocdroid/data/repository/SlimSseStateMachine.kt`（1038 行）维护 incarnation token / marker rotation / watermark / dirty ratchet / commitIfCurrent 等并发原语。
- 注释里能看到 fix-3 / fix-8 / fix-9 P0-4 / P1-1 / P1-2 / rev-ogpt 七轮 TOCTOU 修复迭代。
- `ui/controller/` 32 个 controller 文件，其中 12 个是 slim 专属（`SlimSessionReconciler` / `SlimColdStartSnapshotApplier` / `SlimResyncCadence` / `SlimEffectsPort` / `SlimOnlyStateWrite` / `SlimQuestionLoader` / 等）。
- `T3RepositoryExtractFreezeTest` 反射锁 ~40 个公共方法 + `slimStateLock` 字段 + 多个嵌套异常 FQN，任何小改动都可能 RED。

### 1.2 用户场景与约束（决策清单）

| # | 用户决策 | 解锁的简化 |
|---|---|---|
| C1 | 多 project/workdir 仍需要，但接受 opencode 原版逻辑（`X-Opencode-Directory` header 路由） | 砍掉 slim 多目录聚合（`/slimapi/questions?directory=A&directory=B`）+ routeToken 多目录应答 |
| C2 | 开关 slim = 切换 host profile，可接受重启应用 | **整个 `SlimSseStateMachine.kt` 文件可删**（1038 行 → 0）。重启进程 = 内存状态清零，没有 in-flight 跨 host 污染问题，`SlimCommitToken` / `SlimReconfigureTicket` / `beginSlimReconfigure` 整套不需要 |
| C3 | 砍掉多服务器配置槽位，只保留一套 host profile | `HostProfileController` / `HostProfileStore` 简化为单 profile，切换 = 覆盖 |
| C4 | 可以修改 sidecar（oc-slimapi 自己的代码） | 可以从 sidecar 删除没人用的端点 + 简化 digest 帧 |
| C5 | 连到 slimapi 服务器但不启用 slim 时，行为等价于普通 opencode 服务器 | **已是现状**：`oc-slimapi/src/oc_slimapi/proxy.py:113-120` catch-all 已透传 `/global/event` SSE（`read=None` 长连接 + `StreamingResponse` 流式） |
| C6 | 暂不修改 opencode 源码 | slim 的 skeleton 投影 / digest debounce 必须留在 sidecar |

### 1.3 与 opencode 网页版的差异

opencode 网页版「使用正常」的根源是**功能范围窄**：单 host、单 API、前台-only、可丢弃状态。ocdroid 复杂度集中在两个客观维度：

- **slim 路径**（`SlimSseStateMachine` 一千行全是 slim watermark/token/conflict 逻辑）
- **Android 平台**（后台 / 通知 / 进程回收 / Compose slice 隔离）

本方案只解决前者；后者是平台刚需，砍不掉。

---

## 2. 流量复测（数据证据）

> **数据来源**：`oc-slimapi/logs/access.jsonl*`（5 个轮转文件，跨度 2026-07-27 14:46 → 20:22，5.5 小时） + `/slimapi/metrics` ledger（sidecar 进程内存累计，非持久化）。
> **限制**：ocdroid 客户端无 sqlite 流量库（`TrafficLogger` 仅 200 条内存 ring buffer）。"最近一周"数据不存在，下面外推按"使用强度与 5.5h 窗口一致"线性放大，**不代表真实一周数据**。

### 2.1 总体对比（5.5h 窗口）

| 指标 | 实测值 |
|---|---|
| slim 客户端实收（access.jsonl `downOut` 累计） | 619.44 MB |
| sidecar 从 opencode 收（access.jsonl `upIn` 累计，= 等效 legacy） | 3500.72 MB |
| 节省 | 2881.28 MB（**82.3%**） |
| ledger 累计（自 sidecar 启动） | client 209 MB / upstream 1562 MB / 节省 86.6% |
| 外推 7 天 slim | ~18.5 GB |
| 外推 7 天等效 legacy | ~104 GB |

### 2.2 按桶分解（5.5h）

| bucket | reqs | slim→client | 等效 legacy | 节省率 | 节省机制 |
|---|---|---|---|---|---|
| messages | 11149 | 570.64 MB | 3200.00 MB | 82.2% | skeleton 投影（去掉 part text） |
| passthrough | 209491 | 29.08 MB | 29.08 MB | 0% | catch-all 反代，无剪裁 |
| sessions | 3410 | 18.88 MB | 271.61 MB | 93.0% | skeleton 投影 |
| events_sse | 66 | 0.72 MB | 366 MB（ledger） | 99.7% | session.digest debounce + 字段 diff |
| quiz | 827 | 0.06 MB | 0.04 MB | -66% | 聚合放大（正常） |
| token_stream_sse | 148 | 0.05 MB | n/a | n/a | 新接口 |
| health | 46 | 0.01 MB | 0 | n/a | — |

### 2.3 协议增强特性的实际使用率（关键）

| 特性 | 实测使用 | 实测节省 |
|---|---|---|
| `/full/{mid}?known=` 304 fingerprint | 23 次 / 5.5h（命中率 11%） | < 30 KB |
| `/since/{ts}` 增量锚点 | 32 次 / 5.5h | 微小 |
| `/full?ids=` batch | **0 次** | 0 |
| `/sessions/{sid}/children` slim 端点 | **0 次**（client 已走 legacy `/session/{id}/children`） | 0 |
| `/sessions/{sid}/status` slim 单查 | 单目录使用无价值 | RTT 节省（无字节节省） |
| `/questions` `/permissions` 聚合 | 实测混用（843 slim + 1349 legacy） | RTT 节省 |

**结论**：所有「协议增强」端点合计 < 60 次调用 / 5.5h，占总流量 < 0.01%。slim 真正的流量价值 100% 来自 **skeleton 投影** + **digest debounce SSE**，二者占节省量的 99% 以上。

### 2.4 客户端 bug：两个高频轮询

| 现象 | 频次 | 流量影响 | 根因（详见 §6） |
|---|---|---|---|
| `/session/{id}/children` 轮询 | 220518 次 / 6h = **10.1 次/秒** | 29 MB（messages 桶外的次要流量） | digest bump `completenessEpoch` → `completeRootIds` 失效 → `UnreadSoakController` 每 2s 重拉所有 roots 的 children |
| `/slimapi/messages/{ses_06188b}` 反馈循环 | 2770 次 / 4h（每次 250-400 KB） | 485 MB（占 messages 桶 85%） | digest 触发整个 messages 列表 reload（应用单条 `/full/{mid}` 即可） |

剔除两个 bug 后修正：5.5h slim client = 135 MB（原 619 MB），外推 7 天 ~4 GB，等效 legacy ~12 GB，节省率 67%（仍很可观）。

---

## 3. 目标架构

### 3.1 一句话

> 客户端两种 host 形态（运行时不可切换，重启 = 切换）：
>
> - **host A：opencode 直连**（`:4096`）→ 全部请求走 legacy（含 `/global/event` SSE）
> - **host B：slimapi sidecar**（`:4097`）+ 启用 slim 增强 → 三个端点走 `/slimapi/*`，其余全部 catch-all 透传

### 3.2 host B 下的端点路由

```
SSE 控制面    → /slimapi/events              （session.digest debounce，99.7% 节省）
消息列表      → /slimapi/messages?mode=skeleton  （82% 节省）
              → /slimapi/messages/{sid}/full/{mid}  （按需展开单条，不带 ?known=）
会话列表      → /slimapi/sessions             （93% 节省）
─────────────────────────────────────────────────
其他所有请求  → catch-all 透传：
              /session/{id}/children         （实测已用 legacy）
              /question /permission          （实测混用，统一走 legacy）
              /session/{id}/message POST     （prompt_async）
              /session/{id}/abort
              /file/* /vcs/* /command
              /global/event                  （host A 形态时）
```

### 3.3 与当前架构对比

| 维度 | 当前（双 API 共存） | 本方案（透传 + slim 增强） |
|---|---|---|
| 模式切换 | 运行时双路 + 能力查询 + host 切换事务 | host profile 决定，重启切换 |
| 客户端状态机 | `SlimSseStateMachine` 1038 行 + watermark + incarnation | **无状态机**（skeleton 投影无状态；重启清空） |
| SSE handler 域 | 3 个（SharedConversation / Legacy / Slim） | 启动时确定 1 个 |
| 能力读模型 | `supportsWatermarkResync` / `supportsTokenStreamResync` / `usesSlimStatusFanOut` 等派生查询 | 单一 `slimConnection: Boolean` |
| host 切换 | `beginSlimReconfigure` / `completeSlimReconfigure` / `SlimReconfigureTicket` | 清缓存 + 重启 |
| 客户端代码量 | 100%（基线） | 估算 ~50-60% |

---

## 4. 接口分层

### 4.1 Tier 1：必留端点（slim 不可替代的核心价值）

| 端点 | 价值 | 客户端配套消费 | sidecar 改动 |
|---|---|---|---|
| `GET /slimapi/events` | session.digest debounce SSE，**99.7% 节省**；必须在服务端做 | 消费 `session.digest` + `session.error` 帧；不再消费 `session.updated` 整帧 | digest 帧裁剪：不再发 `contentRevisions` / `childrenVersion`（详见 §5.3） |
| `GET /slimapi/messages?mode=skeleton` | skeleton 投影，**82% 节省**；必须在服务端做 | skeleton 数据模型 + 按需展开 | — |
| `GET /slimapi/sessions`（skeleton） | skeleton 投影，**93% 节省** | skeleton session 字段 | — |

### 4.2 Tier 2：可选端点（体验增强，可关闭）

| 端点 | 价值 | 备注 |
|---|---|---|
| `GET /slimapi/messages/{sid}/full/{mid}` | 单条全文展开（Tier 1 配套） | **去掉 `?known.maxPartId=&known.partCount=&known.messageEventSeq=` 参数**，纯 full |
| `GET /slimapi/sessions/{sid}/stream`（token stream SSE） | 实时逐 token 渲染（体验项） | 默认 gzip；可选关闭，零回归 |

### 4.3 Tier 3：建议砍掉（实测价值 < 0.1%，配套复杂度高）

| 端点 | 砍掉依据 |
|---|---|
| `GET /slimapi/messages/{sid}/full/{mid}?known=` 304 fingerprint | 实测 23 次 / 5.5h，节省 < 30 KB；但客户端配套 `SlimSseStateMachine` watermark 一千行的根源 |
| `GET /slimapi/messages/{sid}/full?ids=` batch | **实测 0 次**；客户端 `ExpandBatchEngine` 完全未触发 |
| `GET /slimapi/messages/{sid}/since/{ts}` watermark 锚点 | 实测 32 次；冷启动用 `/messages?before=` 向后翻即可 |
| `GET /slimapi/sessions/{sid}/children` slim 端点 | **实测 0 次**；client 已走 legacy `/session/{id}/children`（实测 213504 次） |
| `GET /slimapi/sessions/{sid}/status` slim 单查 | 单目录无价值；走 legacy `/session/status` |
| `GET /slimapi/sessions/status` slim 批量聚合 | 同上 |
| `GET /slimapi/questions` `/permissions` 聚合 | 实测混用；走 legacy `/question` `/permission` |
| `POST /slimapi/questions/{qid}/reply` `/reject`（routeToken 路径） | 多目录聚合不需要；走 legacy `?directory=` |
| `POST /slimapi/sessions/{sid}/permissions/{pid}`（routeToken 路径） | 同上 |

---

## 5. 改造清单

### 5.1 客户端（ocdroid）—— 整个删除的文件

| 文件 | 行数 | 删除依据 |
|---|---|---|
| `app/src/main/java/cn/vectory/ocdroid/data/repository/SlimSseStateMachine.kt` | 1038 | 重启可接受（C2）→ 无 in-flight 跨 host 问题；watermark/incarnation 全套不需要 |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/SlimSessionReconciler.kt` | ~600 | 服务于 watermark 比对 |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/SlimColdStartSnapshotApplier.kt` | ~400 | 服务于 watermark + dirty 标志 |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/SlimResyncCadence.kt` | — | 服务于 watermark 重连 |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/SlimEffectsPort.kt` | — | 同上 |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/SlimOnlyStateWrite.kt` | — | 同上 |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/SlimQuestionLoader.kt` | — | 多目录聚合不需要（C1） |
| `app/src/main/java/cn/vectory/ocdroid/data/repository/ExpandBatchEngine.kt` | — | batch 端点删除 |
| `app/src/main/java/cn/vectory/ocdroid/data/repository/SlimSyncEngine.kt` | — | watermark 锚点逻辑 |

**估算**：~4200 行客户端代码删除。

### 5.2 客户端（ocdroid）—— 简化的文件

| 文件 | 改动 |
|---|---|
| `app/src/main/java/cn/vectory/ocdroid/data/api/SlimApi.kt` | 删除 Tier 3 端点对应方法（9 个）；保留 4 个：`getSlimapiSessions` / `getSlimapiMessages` / `getSlimapiMessageFull`（无 `?known=`）/ `getSlimapiEvents` 由 `SSEClient` 直接处理；如保留 token stream，保留 stream 路由 |
| `app/src/main/java/cn/vectory/ocdroid/data/model/Slimapi.kt` | 删除 `SlimapiMessageFullBatch` / `SlimapiQuestionAggregation` / `SlimapiPermissionAggregation` / `SlimapiQuestionReplyRequest` 等 DTO |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/sse/SlimSseHandler.kt` | 只保留 `session.digest` + `session.error` 消费；删 `contentRevisions` / `childrenVersion` 处理 |
| `app/src/main/java/cn/vectory/ocdroid/data/repository/ServerCompatProfile.kt` | 删 `supportsWatermarkResync` / `supportsTokenStreamResync` / `usesSlimStatusFanOut` 派生查询；只留 `slimConnection: Boolean` |
| `app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt` | 删 `slimStateLock` 字段；删 `completeSlimReconfigure` / `beginSlimReconfigure` / `bumpSlimBookmarkFromItems` / `markSlimDirty` / `forceSlimDirty` 等；删嵌套异常 `StaleSlimCommitException` / `SupersededSlimReconfigureException` / `SlimSinceStagingOnlyException`；保留 `configure()` 单一事务 |
| `app/src/main/java/cn/vectory/ocdroid/data/repository/HostConfig.kt` | 单 profile 字段（去掉多槽位） |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/HostProfileController.kt` | 单 profile，切换 = 覆盖 + 重启提示 |
| `app/src/main/java/cn/vectory/ocdroid/data/repository/MessageSource.kt` | `Slim*` 实现简化为纯端点调用（无 lambda 注入 watermark）；删 `getMessagesPagedStageA` / `commitAuthoritative` |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/sse/SseEventRouter.kt` | 三个 handler 域简化为 1 个（启动时根据 host 类型确定） |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/sse/ModeDomain.kt` | 删除（启动时确定，无运行时切换） |
| `app/src/main/java/cn/vectory/ocdroid/ui/MessageActions.kt` | digest messageID 变化只触发单条 `/full/{mid}` 拉取（不再 reload 整个列表）；详见 §6.2 |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/SessionTreeHydrator.kt` | `completeRootIds` 加 TTL 或字段区分失效（详见 §6.1） |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/UnreadSoakController.kt` | 配合 §6.1 修复，避免每 2s 全量重拉 |
| `app/src/main/java/cn/vectory/ocdroid/ui/controller/sse/SseSessionListReducers.kt` | digest 处理时不再无差别 bump `completenessEpoch`（详见 §6.1） |

### 5.3 sidecar（oc-slimapi）—— 改造清单

#### 5.3.1 删除的端点（A 桶）

| 端点 | 涉及文件 |
|---|---|
| `GET /slimapi/messages/{sid}/full?ids=` | `src/oc_slimapi/routes/messages.py`（batch 路由段） |
| `GET /slimapi/messages/{sid}/since/{ts}` | 同上（since 路由段） |
| `GET /slimapi/sessions/{sid}/children` | `src/oc_slimapi/routes/sessions_children.py`（整个文件） |
| `GET /slimapi/sessions/{sid}/status` | `src/oc_slimapi/routes/sessions.py`（status 单查路由） |
| `GET /slimapi/sessions/status` | 同上（status 批量路由） |
| `GET /slimapi/questions` `/permissions` 聚合 | `src/oc_slimapi/routes/questions.py`（聚合路由段） |
| `POST /slimapi/questions/{qid}/reply` `/reject` | 同上 |
| `POST /slimapi/sessions/{sid}/permissions/{pid}` | 同上 |

#### 5.3.2 简化的端点

| 端点 | 改动 |
|---|---|
| `GET /slimapi/messages/{sid}/full/{mid}` | 去掉 `?known.maxPartId=&known.partCount=&known.messageEventSeq=` 参数 + 不再发 `X-Message-Event-Seq` 响应头 + 不再返 304（始终 200 + body） |

#### 5.3.3 简化的 SSE digest 帧

| 字段 | 处理 |
|---|---|
| `session.digest.contentRevisions` | **删除**（不再发；客户端无 watermark 比对） |
| `session.digest.childrenVersion` | **删除**（不再发；客户端走 legacy `/session/{id}/children`） |
| `session.digest.lastError` | 保留（轻量，durable banner 体验项） |
| `session.digest.status` / `messageID` / `updatedAt` / `archived` / `deleted` | 保留（核心字段） |
| `session.error`（无 sid 直推帧） | 保留 |
| `message.part.removed` / `message.removed`（hub 内部路由） | 不再维护 `_part_state` / `_session_event_seq`（可大幅简化 `sse/hub.py`） |

#### 5.3.4 不动的部分

- catch-all 反代（`src/oc_slimapi/proxy.py`，包括 `/global/event` SSE 透传）—— 已是基础功能
- `X-Slimapi-Version: 1` 版本协商（不 bump；删除端点是破坏性变更但客户端配合发版）
  > *（此为 v1 基线描述——该计划称"不 bump"；V2 迁移实际已 bump 至 `X-Slimapi-Version: 2`。本文件为历史归档，见页首 banner。）*
- `GET /slimapi/health` / `/ready` / `/metrics`
- `GET /slimapi/sessions/{sid}/stream`（token stream，可选保留）

### 5.4 文档同步

落地后须同步更新：

- `docs/specs/architecture.md` §1（项目定位）、§3（分层）、§4（双 API 变体共存策略 → 改为「单 API + slim 增强」）、§5（不变量）
- `docs/specs/slim-mode-api-routing.md`（路由契约）
- `docs/specs/sse-client-spec.md`（SSE 控制面）
- `../../oc-slimapi/docs/specs/v1-contract.md`（wire 契约，发新版）
- `../../oc-slimapi/docs/specs/CLIENT_CHANGES.md`（客户端影响清单）

---

## 6. 独立修复：两个高频轮询 bug

> 这两个 bug 与本方案**正交**——即便不实施 ocdroid-lite，也建议立即修复。优先级 P0。

### 6.1 `/children` 全量遍历（10.1 次/秒）

**根因链路**：

1. `app/src/main/java/cn/vectory/ocdroid/ui/controller/UnreadSoakController.kt:48-73`：每 2 秒 tick 一次，调用 `requestTreeHydration(incompleteIdleRoots)`。
2. `UnreadSoakController.kt:69-72`：传入"不在 `completeRootIds` 里的所有 idle root"。
3. `SessionTreeHydrator.kt:78`：`ForegroundSessionTreeHydrator.request()` 对每个 root **递归拉 children**，6 路并发（`SessionTreeHydrator.kt:34` `Semaphore(6)`）。
4. **关键放大点**：`app/src/main/java/cn/vectory/ocdroid/ui/controller/SseSessionListReducers.kt:229,233` —— 每个 SSE digest 推 session 变化时，把该 root 从 `completeRootIds` 移除 + bump `completenessEpoch`。
5. 下一个 2 秒 tick：被移除的 root 又变成 "incomplete"，**再次触发全量拉取**。

agent 持续输出时 digest 每 ~250ms 推一次 → 每 2 秒至少 8 次 root 失效 → 每 tick 对 ~100 个 root 全量重拉 → 实测单秒峰值 160 次。

**修复方案**（任选一种，推荐 b）：

| 方案 | 实施 | 优点 | 缺点 |
|---|---|---|---|
| a) 给 `completeRootIds` 加 TTL | 拉过一次后 5-10 分钟内 digest 不让失效 | 最简单，改动小 | 治标；TTL 期间结构变化（fork）不反映 |
| b) 区分 digest 字段（推荐） | 只有 `session.created` / `session.deleted` 才让 root 失效；`status` / `updatedAt` / `messageID` 变化不动 `completeRootIds` | 最正确；语义清晰 | 改动 `SseSessionListReducers.kt` 多个 reducer |
| c) 按需拉取（最激进） | 砍掉整个 `UnreadSoakController` 主动遍历；用户展开 root 时才拉 | 彻底解决；删除大量代码 | 影响"未读 soak"功能（背景轮询未读消息） |

### 6.2 `/slimapi/messages/{sid}` 反馈循环（2770 次 / 4h，每次 250-400 KB）

**反馈循环证据**（16:05-16:10 burst 窗口）：

```
16:05:01.379  → 522 bytes        (probe? small)
16:05:01.530  → 348070 bytes     (full page)
16:05:01.877  → 243805 bytes     (再拉一次)
16:05:04.419  → 814 bytes        (2.5s 后下一轮)
16:05:04.573  → 348054 bytes
16:05:04.805  → 243805 bytes
```

每次 burst 3 次连续调用（间隔 150-350ms ≈ digest 250ms debounce 窗口），返回 ~350 KB / ~244 KB / ~500 B 三种稳定尺寸。

**根因**（`MessageActions.kt:255-256` 注释自承）：

> "During streaming, session.status busy/idle triggers resetLimit=true reloads"

agent 输出期间：
1. 每个 message part 完成 → digest 推 `messageID` + `updatedAt` 变化
2. 客户端消费 digest → 触发 `launchLoadMessages(resetLimit=true)`
3. `MessageActions.kt:143` `getMessagesPaged` → 拉**整个 messages 列表**（不是单条 `/full/{mid}`）
4. session 有大量历史 → 返回 ~350 KB skeleton
5. 拉完后 dispatch 新状态 → 又被 digest 视为变化 → 重复

**关键设计错误**：digest 推 messageID 变化时，**应该拉单条 `/full/{mid}`**（几 KB），不应该 reload 整个列表（几百 KB）。

**修复方案**（推荐 a + c 组合）：

| 方案 | 实施 | 优点 | 缺点 |
|---|---|---|---|
| a) digest messageID 变化只触发单条拉取（推荐） | 改 `MessageActions.kt` digest 处理路径：收到新 messageID → 调 `repository.getSlimapiMessageFull(sid, mid)`，不 reload 整列表 | 最正确；流量降两个数量级 | 改动 reducer 逻辑 |
| b) reload 加去抖 | 同一 session 5 秒内不重复 reload | 治标；改动小 | streaming 期间 UI 可能延迟 |
| c) streaming 期间完全禁用 reload | `session.status == busy` 时不触发 `resetLimit=true`，让 SSE token stream 增量推送 | 配合 token stream 体验更好 | 不连 token stream 时无法看到新消息 |

**流量影响**（修复后估算）：

| 项目 | 当前 | 修复后估算 |
|---|---|---|
| `/children` 调用 | 220518 次 / 29 MB | ~5000 次 / ~5 MB（按需）或 ~20000 次 / ~3 MB（TTL） |
| `ses_06188b` messages | 2770 次 / 485 MB | ~200 次 / ~30 MB（单条 full） |
| **5.5h 总节省** | — | **~480 MB**（占 messages 桶 84%） |
| **外推 7 天节省** | — | **~15 GB** |

---

## 7. 实施顺序与里程碑

### M0：先修两个高频轮询 bug（独立于架构改动，立即收益）

**优先级**：P0（与本方案解耦，可单独发版）

1. 修 `/children` 全量遍历（§6.1 方案 b）
2. 修 `/messages` 反馈循环（§6.2 方案 a + c）
3. `./scripts/check.sh` 通过
4. 发 patch 版本，观察 access.jsonl 一周确认流量下降

**验收**：access.jsonl 中 `/children` 频次 < 1 次/秒；`/slimapi/messages/{sid}` 同一 session burst 间隔 > 30s。

### M1：sidecar 砍端点 + digest 简化

**优先级**：P1（与 M2 并行，先发 sidecar 不破坏现有客户端）

1. 删除 §5.3.1 列出的 9 个端点
2. 简化 §5.3.2 单 full 端点（去掉 `?known=`）
3. 简化 §5.3.3 digest 帧（删 `contentRevisions` / `childrenVersion`）
4. 简化 `sse/hub.py`（不再维护 `_part_state` / `_session_event_seq`）
5. 更新 `../../oc-slimapi/docs/specs/v1-contract.md` 与 `CLIENT_CHANGES.md`
6. 跑 oc-slimapi 测试套件
7. 部署 sidecar（旧客户端由于 catch-all 透传 + 端点 404 fallback 仍能工作）

**验收**：sidecar `/slimapi/metrics` 显示 A 桶端点数减少；老客户端连接正常（仅失去 watermark/304 等增强功能，行为退化但不崩溃）。

### M2：客户端单 profile 化 + 删 SlimSseStateMachine

**优先级**：P1（依赖 M0 完成）

1. `HostProfileController` / `HostProfileStore` 重写为单 profile
2. 删除 §5.1 整个文件清单
3. 简化 §5.2 文件清单（按依赖顺序：先 `SlimApi.kt` → DTO → reducer → handler → repository → controller）
4. 解锁 `T3RepositoryExtractFreezeTest`（删除 `slimStateLock` / 嵌套异常 FQN 断言）
5. 每步 `./scripts/check.sh` GREEN
6. 模拟器集成测试：host A（直连） + host B（slim 增强）双形态各跑一遍

**验收**：客户端编译通过；集成测试通过；代码量降低到目标值。

### M3：文档同步

**优先级**：P2（M2 完成后）

1. 更新 §5.4 列出的所有文档
2. 更新 `AGENTS.md` 的流程入口表
3. 更新 `README.md`（如果有 slim 相关说明）

---

## 8. 风险与权衡

### 8.1 流量损失（可接受）

| 损失项 | 量级 | 缓解 |
|---|---|---|
| 304 fingerprint 节省 | < 30 KB / 5.5h | 微不足道 |
| `/since/{ts}` 增量锚点 | 微小 | 冷启动用 `/messages?before=` 替代 |
| 多目录聚合 RTT 节省 | 视使用而定 | 单目录场景无损失；多目录场景按 opencode 原版逻辑多次调用 |
| 整体节省率下降 | 82-87% → 75-80% | 可接受 |

### 8.2 功能损失（需评估）

| 损失项 | 影响 | 决策 |
|---|---|---|
| 跨目录聚合待办列表（q/p） | 用户在多 workdir 场景下失去"一站式"待办视图 | C1 接受（按 opencode 原版逻辑：用户切换 workdir 时各自查询） |
| host 内运行时切 slim | 不能在同一 host 内动态开关 slim | C2 接受（重启切换） |
| 多服务器配置槽位 | 不能保存多个 host 配置快速切换 | C3 接受（单一 profile） |
| `childrenVersion` 缓存失效信号 | 客户端不能精确知道何时重拉 children | 用 TTL 或 digest `session.created` 触发刷新替代 |

### 8.3 不变量解锁（须审慎）

`T3RepositoryExtractFreezeTest` 当前反射锁：

- OCR ~40 个公共方法（多数保留）
- `slimStateLock` 字段（**删除**）
- `SlimCommitToken` / `StaleSlimCommitException` / `SlimReconfigureTicket` / `SupersededSlimReconfigureException` / `SlimSinceStagingOnlyException` FQN（**全部删除**）
- `IOException` 继承关系（部分调整）

解锁前须确认无外部调用（用 `ast_grep_search` 全局搜），逐项删除并跑测试。

### 8.4 回滚预案

- M0（轮询修复）：bug fix，无须回滚
- M1（sidecar 砍端点）：保留旧版 sidecar 镜像，发现客户端异常立即切回
- M2（客户端大改）：在 `ocdroid-lite` 分支开发，main 保持可用；合并前模拟器全量回归

---

## 9. 不在本方案范围

- 修改 opencode 源码（C6 明确排除）
- 修改 Android 平台层（后台服务、通知、Compose slice 隔离）
- Token stream SSE（保留现状，可作为 Tier 2 选项）
- mTLS / TOFU / stunnel 配置
- CI/CD 流程变更

---

## 10. 引用

### ocdroid 内部

- `docs/specs/architecture.md` — 代码框架规范（基线）
- `docs/specs/slim-mode-api-routing.md` — slim 路由契约（基线）
- `docs/specs/sse-client-spec.md` — SSE 控制面（基线）
- `docs/specs/decomposition-guidelines.md` — 模块拆分准则
- `app/src/main/java/cn/vectory/ocdroid/data/repository/SlimSseStateMachine.kt` — 待删除
- `app/src/main/java/cn/vectory/ocdroid/ui/controller/UnreadSoakController.kt` — §6.1 触发源
- `app/src/main/java/cn/vectory/ocdroid/ui/controller/SessionTreeHydrator.kt` — §6.1 拉取执行
- `app/src/main/java/cn/vectory/ocdroid/ui/controller/SseSessionListReducers.kt` — §6.1 epoch bump
- `app/src/main/java/cn/vectory/ocdroid/ui/MessageActions.kt` — §6.2 反馈循环入口

### oc-slimapi（兄弟仓库）

- `../../oc-slimapi/docs/specs/v1-contract.md` — wire 契约（待发新版）
- `../../oc-slimapi/docs/specs/CLIENT_CHANGES.md` — 客户端影响清单
- `../../oc-slimapi/src/oc_slimapi/proxy.py` — catch-all 反代 + SSE 透传（不动）
- `../../oc-slimapi/src/oc_slimapi/access_log.py` — 流量日志格式
- `../../oc-slimapi/src/oc_slimapi/middleware/traffic_accounting.py` — 字节计数语义
- `../../oc-slimapi/src/oc_slimapi/routes/messages.py` — 待删 batch/since 段
- `../../oc-slimapi/src/oc_slimapi/routes/sessions_children.py` — 待删整个文件
- `../../oc-slimapi/src/oc_slimapi/routes/questions.py` — 待删聚合段
- `../../oc-slimapi/src/oc_slimapi/sse/hub.py` — 待简化 `_part_state`

---

## 11. 调研发现与文档修订（v0.2）

> 本章节由 5 个 explorer 并行调研 oc-slimapi（`/home/mar/personal_projects/oc-slimapi`）与 ocdroid 客户端后整理。所有发现均有代码引用，目的是把 §4-§5 的改造清单从「估算」升级为「精确」。
>
> **5 个调研维度**：
> 1. sidecar 端点路由分布 + 共享代码识别
> 2. digest 帧简化实际影响面
> 3. 客户端 SlimApi 反向依赖核实
> 4. 测试套件影响评估
> 5. `?known=` 304 fingerprint 机制移除评估

### 11.1 对 v0.1 的关键修正

#### 修正 1：SlimApi.kt 方法数（12 → 13）

`app/src/main/java/cn/vectory/ocdroid/data/api/SlimApi.kt` 实际定义 **13 个方法**，v0.1 §5.2 漏数了 `respondSlimapiPermission`（对应 `POST /slimapi/sessions/{sid}/permissions/{pid}`）。

| Tier | 方法数 | 方法清单 |
|---|---|---|
| Tier 1 必留 | 2 | `getSlimapiSessions`、`getSlimapiMessages` |
| Tier 2 可选 | 1 | `getSlimapiMessageFull`（去 `?known=`） |
| Tier 3 砍掉 | **10**（非 9）| `getSlimapiMessagesSince`、`getSlimapiMessageFullWithFingerprint`、`getSlimapiMessagesFullBatch`、`getSlimapiSessionStatus`、`getSlimapiSessionsStatus`、`getSlimapiQuestions`、`getSlimapiPermissions`、`replySlimapiQuestion`、`rejectSlimapiQuestion`、`respondSlimapiPermission` |

注意：`GET /slimapi/events`、`GET /slimapi/sessions/{sid}/stream`、`GET /slimapi/health` 不走 Retrofit，由 `SSEClient.kt:54`、`TokenStreamClient.kt:70`、`OpenCodeRepository.kt:1498` 裸 OkHttp 拼接 URL——这些端点删除/保留**不涉及 SlimApi.kt 改动**。

#### 修正 2：`/full?ids=` batch 实际行为（非"0 次调用"）

v0.1 §2.3 / §4.3 称「batch `/full?ids=` **实测 0 次**；客户端 `ExpandBatchEngine` 完全未触发」。**实际**：

- 客户端 `ExpandBatchEngine.kt:280`（`drivePartition`）**确实调用** `getSlimapiMessagesFullBatch(...)`
- 触发场景：用户展开折叠的 chat parts（`PartExpandState.kt:258` `repository.expandMessagesFullBatch(...)`）
- access.log 显示 0 次成功——因为 **sidecar 当前部署未启用该端点，返回 404 `thin_route_not_found`**
- 客户端 fallback：缓存 404 结果 60 秒，转为逐条 `/full/{mid}` 调用

**结论**：删除 batch 端点后，客户端 `ExpandBatchEngine.kt`（742 行）整个文件可删，`PartExpandState.kt` 改为直接循环调 `getSlimapiMessageFull`（等价于当前 fallback 逻辑）。

#### 修正 3：`/sessions/{sid}/children` 客户端无对应方法

v0.1 §4.3 把 `GET /slimapi/sessions/{sid}/children` 列为 Tier 3 端点，但客户端 `SlimApi.kt` **从未定义此方法**——客户端一直走 legacy `StandardApi.kt:53` `GET /session/{id}/children`（实测 5.5h 内 213504 次）。

**结论**：此端点的删除是**纯 sidecar 操作**，客户端侧零改动。

#### 修正 4：3 个 routeToken DTO 在 `OpenCodeApi.kt` 而非 `Slimapi.kt`

v0.1 §5.2 称「删除 `Slimapi.kt` 中的 `SlimapiQuestionReplyRequest` 等 DTO」。**实际位置**：

- `SlimapiQuestionReplyRequest` → `OpenCodeApi.kt:183`
- `SlimapiQuestionRejectRequest` → `OpenCodeApi.kt:189`
- `SlimapiPermissionResponseRequest` → `OpenCodeApi.kt:194`

**结论**：删除时需要修改 `OpenCodeApi.kt` 而非 `Slimapi.kt`。

#### 修正 5：测试影响严重低估

v0.1 §8.3 简单提到「解锁 freeze test」。**实测影响**（详见 §11.5）：

| 里程碑 | 删用例 | 改用例 | 增用例 |
|---|---|---|---|
| M0（修轮询） | 0 | ~8 | ~6 |
| M1（sidecar 砍端点） | ~105 | ~33 | ~5 |
| M2（客户端大改） | ~256 | ~20 | ~2 |
| **总计** | **~361** | **~61** | **~13** |

涉及测试代码改动 **~1500-2000 行**。

### 11.2 sidecar 路由分布精确化

**路由注册中心**：`src/oc_slimapi/app.py:211`

```python
for router in (health.router, sessions.router, sessions_children.router,
               messages.router, questions.router, events.router,
               metrics.router, token_stream.router):
    app.include_router(router)
```

#### 待删端点精确位置

| # | 端点 | 文件:行号 | handler | 依赖的共享 helper |
|---|---|---|---|---|
| ① | `GET /slimapi/messages/{sid}/full?ids=` | `routes/messages.py:616-983` | `message_batch()` | `_resolve_messages_directory` / `_stream_upstream` / `_busy_response` / `_parse_upstream_retry_after_seconds`（**保留**，被保留端点共享） |
| ② | `GET /slimapi/messages/{sid}/since/{ts}` | `routes/messages.py:294-470` | `messages_since()` | 同上 + `_item_updated` / `_passes_ts_filter`（**可删**，仅此端点用） |
| ③ | `GET /slimapi/sessions/{sid}/children` | `routes/sessions_children.py:11-20`（**整个文件**） | `children()` | `ChildrenCache.get_or_fetch`（**可删该方法**；`peek` 保留——被 `/sessions` 用） |
| ④ | `GET /slimapi/sessions/{sid}/status` | `routes/sessions.py:156-204` | `session_status()` | 通用 helper（无私有依赖） |
| ⑤ | `GET /slimapi/sessions/status` | `routes/sessions.py:133-148` | `statuses()` | `fetch_json_mapped`（**保留**——被 `ChildrenCache` + `/projects` 用） |
| ⑥ | `GET /slimapi/questions` | `routes/questions.py:99-101` | `questions()` → `_aggregate` | `_aggregate`（**可删**——与 ⑦ 共享） |
| ⑦ | `GET /slimapi/permissions` | `routes/questions.py:104-106` | `permissions()` → `_aggregate` | 同 ⑥ |
| ⑧ | `POST /slimapi/questions/{qid}/reply` `/reject` | `routes/questions.py:166-175` | `reply()` / `reject()` | `_token` / `_post`（**可删**——与 ⑨ 共享） |
| ⑨ | `POST /slimapi/sessions/{sid}/permissions/{pid}` | `routes/questions.py:178-184` | `permission()` | 同 ⑧ |

#### 共享代码风险（重要）

`routes/messages.py` 内部 **6 个 helper 被保留端点共享，删除时绝对不能动**：

| Helper | 行号 | 服务于 |
|---|---|---|
| `_resolve_messages_directory` | 204-226 | 全部 4 个 messages handler |
| `_stream_upstream` | 229-244 | 3 个（除 batch） |
| `_drain_error` | 247-261 | 3 个（除 batch） |
| `_busy_response` | 185-201 | 全部 4 个 |
| `_parse_link_next_cursor` | 138-182 | `/since`（删） + `/messages`（留） |
| `_parse_upstream_retry_after_seconds` | 49-72 | `batch`（删） + `/full/{mid}`（留） |

#### routeToken 系统完全退役

删除 ⑥⑦⑧⑨ 后，下面这些可一并清除：

- `_aggregate` / `_request_id` / `_token` / `_post` / `ReplyBody` / `TokenBody` / `PermissionBody`（均在 `routes/questions.py`）
- 整个 `src/oc_slimapi/tokens.py` 文件（`issue_route_token` / `verify_route_token` / `RouteTokenError`，仅被聚合 + mutation 端点引用）
- `routes/questions.py` 整个文件可删（仅含聚合 + mutation 端点）

#### 删除顺序建议

1. **第一波（最安全）**：删 ③（`sessions_children.py` 整个文件）
2. **第二波**：删 ④⑤（`sessions.py` 内两个 handler 函数体）
3. **第三波（routeToken 系统整体退役）**：删 ⑥⑦⑧⑨ + `tokens.py` + `questions.py` 整个文件
4. **第四波（messages.py 内部）**：删 ①② + 私有 helper（`_item_updated` / `_passes_ts_filter` / `_opt_a_top_level_503`），**保留** 6 个共享 helper

#### 路由注册顺序无冲突

`/full`（batch）注册于 `messages.py:616`，`/full/{mid}` 注册于 `messages.py:986`。注释已确认「segment count differs so no actual collision」——删除 `/full` **不影响** `/full/{mid}`。

### 11.3 digest 帧简化的实际范围（关键约束）

> **重大发现**：v0.1 §5.3.3 预期能简化 hub 的 `_part_state` / `_session_event_seq`。**实际不能**——这两个状态服务于 `get_part_fingerprint()`（HTTP 304 fingerprint 路径），**也独立服务于 digest `contentRevisions` 字段**（客户端 loss detection 主通道）。

#### digest 字段映射表

| 字段 | 构造位置 | 上游事件 | 是否可删 |
|---|---|---|---|
| `status` | `hub.py:760` | `session.status` | **否** |
| `messageID` | `hub.py:850` | `message.updated` / `message.appended` | **否** |
| `updatedAt` | `hub.py:851` | 同上 | **否** |
| `archived` | `hub.py:809` | `session.updated` | **否** |
| `deleted` | `hub.py:770` | `session.deleted` | **否** |
| `lastError` | `hub.py:887-892` + 脱敏 `hub.py:73-87` | `session.error` | **否**（G1 安全机制） |
| `contentRevisions` | `hub.py:982` / `1030` / 清理于 `1056-1064` / `1118-1128` | `message.part.updated` / `message.part.removed` | **取决于 fingerprint 决策**（见下） |
| `childrenVersion` | `hub.py:833` | `session.created` | **可删** |

#### hub 内部状态保留清单（重要）

| 状态字段 | 文件:行号 | 是否保留 | 理由 |
|---|---|---|---|
| `pending: dict[str, DigestFields]` | `hub.py:359` | **保留** | 所有 digest 帧依赖 |
| `_part_state: dict[str, dict[str, dict]]` | `hub.py:412` | **保留**（可简化结构） | 驱动 `get_part_fingerprint()`（HTTP 304）+ `contentRevisions`（digest loss detection） |
| `_session_event_seq: dict[str, int]` | `hub.py:425` | **保留** | 驱动 `_part_state[..]["seq"]` 的单调性（v0.5 §K CRITICAL 1） |
| `_retired_messages: OrderedDict` | `hub.py:442` | **保留** | rev-ogpt MAJOR 3 防护——防止 late `message.part.updated` 复活已删 message 的 `_part_state` 条目（污染 contentRevisions） |
| `sticky_last_error: dict` | `hub.py:375` | **保留** | G1 lastError 跨 debounce 窗口保持 |
| `deleted_tombstones: set[str]` | `hub.py:385` | **保留** | G1 防止迟到 `session.error` 复活已删会话的 lastError |
| `_children_cache: ChildrenCache \| None` | `hub.py:360` | **hub 引用可删** | 但 `ChildrenCache` 类必须保留——`/sessions` 端点 `peek()` 仍用它 |

#### 两种简化方案对比

**方案 A（保守，v0.2 推荐）**：只删 `contentRevisions` + `childrenVersion` 字段，保留 `_part_state` 全套

- 净删除：**~70 行**（hub.py）
- `_part_state` / `_session_event_seq` / `_bump_message_seq` / `_bump_session_event_seq` / `_retired_messages` / `get_part_fingerprint` 全部保留
- 风险：低；只动 digest 帧 payload

**方案 B（激进，需配合 fingerprint 移除）**：同时删 `?known=` 304 + `X-Message-Event-Seq`，可带走 `_part_state` 的 `parts` 子字典 + `get_part_fingerprint`

- 净删除：sidecar ~107 行 + 客户端 ~107 行
- 必须配合：客户端 `SlimFullReconciler` 304 分支删除 + `commitFull304` 链路删除
- 风险：中；需要同步改 8 个风险点（详见 §11.5）

**v0.2 推荐路径**：M1 阶段先做方案 A（保守），M2 阶段评估是否升级到方案 B。

### 11.4 fingerprint 移除的精确影响

> 假设采用方案 B（同时删 `?known=` 304 + `X-Message-Event-Seq`），影响如下。

#### sidecar 净删除清单（~107 行）

| 代码段 | 文件:行号 | 行数 |
|---|---|---|
| HTTP 304 短路 | `messages.py:1025-1038` | 14 |
| `known.*` 参数声明 | `messages.py:991-995` | 5 |
| `seq_pre` + `seq_post` 双采样 | `messages.py:1053-1131, 1166-1178` | 25 |
| `X-Message-Event-Seq` 响应头 | `messages.py:1136, 1178` | 2 |
| `get_part_fingerprint()` | `hub.py:598-633` | 36 |
| `_bump_message_seq` 中 `parts` 创建简化 | `hub.py:683-725` | 10 |
| `publish()` 中 `parts[ppid]` 增减精简 | `hub.py:941-992` | 15 |
| **合计** | — | **~107** |

#### 客户端净删除清单（~107 行）

| 代码段 | 文件:行号 | 行数 |
|---|---|---|
| `SlimApi.getSlimapiMessageFullWithFingerprint()` | `SlimApi.kt:133-141` | 10 |
| `OpenCodeRepository.getSlimapiMessageFullWithFingerprint()` | `OpenCodeRepository.kt:2785-2798` | 15 |
| `OpenCodeRepository.parseMessageEventSeqHeader()` | `OpenCodeRepository.kt:2816-2821` | 10 |
| `SlimSseStateMachine.commitFull304()` | `SlimSseStateMachine.kt:657-668` | 12 |
| `MessageWatermarkState.clearFlagIfSeqMatches()` | `MessageEventSeqWatermark.kt:486-492` | 7 |
| `SlimFullReconciler` 304 分支处理 | `SlimFullReconciler.kt:581-587` | 7 |
| `SlimFullReconciler` known.* 参数构造 | `SlimFullReconciler.kt:489` | 5 |
| `SlimFullReconciler.parseSeqHeader` port | `SlimFullReconciler.kt:139-140` | 2 |
| `SlimFullReconciler.commitFull304` port | `SlimFullReconciler.kt:183-187` | 5 |
| `SlimFullReconciler.reconcileMessage` 简化 | `SlimFullReconciler.kt:476-563` | 20 |
| 调用点去 304 分支 | 多处 | ~14 |
| **合计** | — | **~107** |

#### 必须保留的部分（关键）

| 客户端组件 | 文件:行号 | 保留理由 |
|---|---|---|
| `MessageWatermark.messageEventSeq` 字段 | `MessageEventSeqWatermark.kt:59` | 仍由 digest `contentRevisions` 推进 |
| `MessageWatermark.needsFullRecheck` 字段 | `MessageEventSeqWatermark.kt:61` | 同上 |
| `MessageWatermarkState.applyDigestRevision()` | `MessageEventSeqWatermark.kt:150-179` | digest 路径独立 |
| `MessageWatermarkState.commitFull200Seq()` | `MessageEventSeqWatermark.kt:438-453` | 200 路径仍需要 |
| `MessageWatermarkState.canCommitFull200Seq()` | `MessageEventSeqWatermark.kt:386-395` | 同上 |

#### TokenStreamHub 完全独立

`tokenstream/hub.py` 中的 `_part_revisions`（v0.6 §Q 引入）**完全独立于 fingerprint 路径**——0 行受影响。可放心移除 fingerprint，不影响 token stream 的 per-frame revision 分发。

#### 8 个风险点（实施方案 B 时须逐一处理）

1. `_part_state` 简化后，`message.part.removed` 的 `msg_entry["parts"].pop(ppid, None)` 会 KeyError——必须同步删除或改条件检查
2. `X-Message-Event-Seq` 删除后，客户端 `SlimFullReconciler.commitFull200` 的 `responseSeq` 参数失去来源——需调整签名或改为无条件接受
3. `commitFull200` 的 seq 验证（`responseSeq <= 0` 或 `< currentSeq` 拒绝）失去依据——需移除或改逻辑
4. `contentRevisions` 保留完整性——确认 `_part_state` 简化后 `content_revisions[mid]` 赋值路径（`hub.py:982`）能正确读取 seq
5. `_retired_messages` gate **必须保留**——防止 late 事件污染 contentRevisions
6. skeleton mode 路径（`messages.py:1142-1183`）也发 `X-Message-Event-Seq`——必须同步删除
7. 客户端 `parseMessageEventSeqHeader` 删除后，所有调用者（强类型）编译会失败——必须同步改
8. `MessageWatermark.seq` 字段命名歧义——删除 fingerprint 后含义变为「纯 digest 推进」，需更新 kdoc

### 11.5 测试套件影响（精确估算）

#### oc-slimapi 测试规模

- **40 个** test_*.py 文件
- **1098 个** pytest 测试用例
- 测试命令：`.venv/bin/python -m pytest tests/`（pyproject.toml `[tool.pytest.ini_options]` 已配置 `asyncio_mode=auto`）
- **无 CI**（无 `.circleci/` / `.github/`）

#### oc-slimapi 受影响测试清单

| 测试文件 | 用例数 | 覆盖 | 处理 |
|---|---|---|---|
| `test_messages_routes.py` | 96 | `/since/{ts}` × 17、`/full/{mid}` 简化 × 5 | 删 17，改 5 |
| `test_sessions_routes.py` | 43 | status 单查 × 9、status 批量 × 1 | 删 10 |
| `test_questions_routes.py` | 13 | 全部针对待删端点 | **整文件删** |
| `test_sessions_children_route.py` | 17 | TDD DRAFT（已有 ImportError） | **整文件删** |
| `test_sessions_children_hint.py` | 5 | hint 字段（add-on） | 审查保留 |
| `test_hub_children_invalidation.py` | 12 | `childrenVersion` TDD DRAFT（未实现） | **整文件删** |
| `test_children_cache.py` | 29 | ChildrenCache TDD DRAFT | **整文件删** |
| `test_stage_b_part_revision.py` | 105 | `_part_state` / `_session_event_seq` | 删 ~40，改 ~10 |
| `test_traffic_integration.py` | 6 | status 端到端流量 | 删或改 |
| `test_traffic_ledger.py` | 53 | batch/since 流量报告 | 改对应条目 |
| **M1 小计** | — | — | **删 ~105，改 ~33，增 ~5** |

#### ocdroid 测试规模

- **~270 个** .kt 单元测试文件
- **~4580 个** `fun \`...\`` 测试用例
- **14 个** androidTest 文件
- 测试命令：`./scripts/check.sh`（compile + testDebugUnitTest）

#### ocdroid 受影响测试清单（M2 客户端大改）

| 测试文件 | 用例数 | 覆盖 | 处理 |
|---|---|---|---|
| `SlimSseStateMachineRaceTest.kt` | 22 | 直接构造 `SlimSseStateMachine` | **整文件删** |
| `SlimSseStateMachineResetTest.kt` | 2 | 同上 | **整文件删** |
| `SlimIncarnationConcurrencyTest.kt` | 2 | `SlimReconfigureTicket` | **整文件删** |
| `SlimIncarnationStateTest.kt` | 7 | 同上 | **整文件删** |
| `SlimAuthoritativeCommitTest.kt` | 35 | `SlimSseStateMachine` 内部方法 | **整文件删** |
| `SlimSyncEngineStageATest.kt` | 17 | `SlimSyncEngine` + 反射 `slimStateMachine` | **整文件删** |
| `SlimSyncEngineSessionsBackoffTest.kt` | 4 | 同上 | **整文件删** |
| `OpenCodeRepositoryEpochWiringTest.kt` | 3 | `commitIfSlimTokenCurrent` | **整文件删** |
| `SlimSessionReconcilerTest.kt` | 8 | `SlimSessionReconciler` | **整文件删** |
| `SlimQuestionLoaderReconcileTest.kt` | 6 | `SlimQuestionLoader` | **整文件删** |
| `T1dSlimOnlyStateWriteOwnershipTest.kt` | 12 | `SlimOnlyStateWrite` | **整文件删** |
| `OpenCodeRepositorySlimapiEndpointsTest.kt` | 147 | 大量被删端点 | 删 ~57，保留 ~90 |
| `SessionSyncCoordinatorSlimTest.kt` | 31 | slim 协调路径 | 审查，删 slim 专用部分 |
| `SessionSyncCoordinatorResyncTest.kt` | 58 | slim 重连 | 审查 |
| `SessionSyncCoordinatorC2TriggerChainTest.kt` | 10 | `SlimSessionReconciler` 交互 | 审查 |
| `SessionSyncDeadlockRegressionTest.kt` | 7 | `slimStateLock` 核心 | **整文件删** |
| `MessageActionsTest.kt` | 43 | `StaleSlimCommitException` 重试 | 改 ~5 |
| `UnreadSoakControllerTest.kt` | ~20 | M0 修轮询 | 改 ~5 |
| `SessionTreeHydratorTest.kt` | — | M0 修轮询 | 改少量 |
| **M2 小计** | — | — | **删 ~256，改 ~20，增 ~2** |

#### T3RepositoryExtractFreezeTest 详细解锁顺序

| 锁定项 | 类型 | 位置 | M2 解锁顺序 |
|---|---|---|---|
| `slimStateLock` 字段 | 字段 | §4c L548-573 | **第 1 步**（删字段后立即） |
| `OpenCodeRepository.SlimCommitToken` | 嵌套类 FQN | §4 L460-464 | **第 1 步**（同步删） |
| `OpenCodeRepository.StaleSlimCommitException` | 嵌套异常 FQN | §4 L471-474 + IOException 继承 L486-490 | **第 1 步** |
| `OpenCodeRepository.SlimReconfigureTicket` | 嵌套类 FQN | §4 L465-468 | **第 1 步** |
| `OpenCodeRepository.SupersededSlimReconfigureException` | 嵌套异常 FQN | §4 L475-478 + IOException 继承 L492-496 | **第 1 步** |
| §1 公共方法（`beginSlimReconfigure` 等 5 个 + `expandMessagesFullBatch`） | 方法名反射 | §1 L80-169 | **第 2 步**（删实现后再删断言） |
| §1 `SlimSseState` 类型 | 类型 FQN | §4b L527-545 | **第 3 步** |
| §3b `SlimapiContract` 常量 | 常量 | §3b | **不动**（保留） |

#### CI 风险与建议

| 阶段 | 风险 | 缓解 |
|---|---|---|
| M0 | 低（增量行为变化） | 直接改 |
| M1 | **oc-slimapi 无 CI**；测试 collection error 风险 | **分两步 commit**：P1 删实现 + 更新 `_build_app`（COLLECTION ERROR）；P2 删测试 |
| M2 | **高**——43 个测试文件引用 `SlimSseStateMachine`，56 处引用被删异常 | 分支开发；每删一文件紧跟 `./scripts/check.sh` GREEN；先删测试后删实现 |

### 11.6 综合发现与建议

#### 关键洞察

1. **`_part_state` 是隐藏耦合点**：v0.1 以为它是 fingerprint 专属，可随 fingerprint 一起删。实际它同时服务 digest `contentRevisions`——客户端 loss detection 的主通道。**方案 A（保守）必须保留它**；方案 B（激进）可简化但不可全删。
2. **`ChildrenCache` 类必须保留**：即使删 `childrenVersion` digest 字段 + `/sessions/{sid}/children` 端点，`ChildrenCache` 仍被 `/sessions` 端点的 `peek()` 用（提供 `childrenIDs[]` hint）。
3. **`tokens.py` 可整体退役**：routeToken 系统仅服务于多目录聚合 + mutation，全部端点删除后该文件零引用。
4. **测试影响是 v0.1 严重低估的维度**：实际 ~361 个用例删除 + ~61 个修改 + 1500-2000 行测试代码改动。M2 工作量需重新评估。
5. **客户端 batch 不是死代码**：`ExpandBatchEngine.kt` 实际有调用，但因 sidecar 未部署而走 fallback。删除 batch 端点后该文件可整体删，但 `PartExpandState.kt` 需要改为循环调单条 `/full`。

#### 修订后的删除优先级

按"删除安全性"重新排序（先易后难）：

1. **第一波（零风险）**：`sessions_children.py` 整个文件 + `test_sessions_children_route.py` + `test_hub_children_invalidation.py` + `test_children_cache.py`（后三个是 TDD DRAFT，未实现）
2. **第二波（零风险）**：`sessions.py` 内 status 单查 + 批量 handler + 对应测试
3. **第三波（routeToken 退役）**：`questions.py` 整个文件 + `tokens.py` 整个文件 + 客户端 routeToken DTO + 对应测试
4. **第四波（messages.py 内部）**：删 `message_batch()` + `messages_since()` 函数体 + 私有 helper（`_item_updated` / `_passes_ts_filter` / `_opt_a_top_level_503`）+ 客户端 `ExpandBatchEngine.kt` + `SlimapiMessageFullBatch` DTO
5. **第五波（digest 简化，方案 A）**：删 `DigestFields.content_revisions` + `DigestFields.children_version` + hub.py 中两字段的写入/清理代码 + 客户端 watermark 中 fingerprint 专属方法
6. **第六波（fingerprint 移除，方案 B，可选）**：删 `?known=` 参数 + 304 短路 + `X-Message-Event-Seq` + `get_part_fingerprint()` + 客户端 `SlimFullReconciler` 304 分支
7. **第七波（SlimSseStateMachine 整体退役，M2）**：删 ~10 个客户端文件 + `T3RepositoryExtractFreezeTest` §4 解锁

#### 调整后的工作量估算

| 维度 | v0.1 估算 | v0.2 修正 |
|---|---|---|
| 客户端代码删除 | ~4200 行 | ~4200 行（不变） |
| sidecar 代码删除 | ~200 行 | **方案 A: ~70 行；方案 B: ~107 行**（v0.1 高估） |
| 客户端测试改动 | 「须审慎」 | **删 ~256 / 改 ~20 / 增 ~2** |
| sidecar 测试改动 | 未估 | **删 ~105 / 改 ~33 / 增 ~5** |
| 总测试代码改动 | — | **~1500-2000 行** |
| `_part_state` 是否可删 | 暗示可删 | **不可全删**（驱动 contentRevisions） |

---

## 修订日志

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-07-27 | v0.1 Draft | 初版。整合状态机复杂度分析、流量复测、用户决策清单（C1-C6）、接口分层、客户端 + sidecar 改造清单、两个高频轮询 bug 修复方案、实施顺序。 |
| 2026-07-27 | v0.2 Draft | 5 个 explorer 并行调研 oc-slimapi + ocdroid 客户端后的精确化版本。新增 §11 调研发现章节：修正 SlimApi 方法数（12→13）、batch 端点实际行为（非"0 次"）、DTO 位置（OpenCodeApi.kt 非 Slimapi.kt）、`_part_state` 保留约束（驱动 contentRevisions 非 fingerprint 专属）、测试影响精确估算（删 ~361/改 ~61/增 ~13）、fingerprint 移除的精确代码位置（sidecar + 客户端各 ~107 行）、修订后的删除优先级（7 波）、关键约束与 8 个风险点。 |
