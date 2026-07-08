# opencode v1.17.13 → v1.17.15 升级评估与改进总报告

- **日期**：2026-07-08
- **评估对象**：ocdroid 对接的 opencode 服务端（本地基准 v1.17.13 → 目标 v1.17.15）
- **实测服务器**：本机 `localhost:4096`，`/global/health` → `{"healthy":true,"version":"1.17.15"}`
- **参考源码**：`opencode-src/v1.17.13/`（ocdroid 基线）、`opencode-src/v1.17.15/`（最新，以 worktree 提供）
- **方法**：服务端契约用树级 `git diff` + `openapi.json` 比对（commit 历史被 anomalyco 重写，不可用作 changelog）；v2 接口用 live 实测 + 源码核实 + 上游官方渠道（sst/opencode）调研三路交叉验证；**Phase 0 全量 HTTP 回归已于 2026-07-08 由子代理对 live 1.17.15 实测完成（`PASS WITH WARNINGS`，见 §1.5）**

---

## 0. 执行摘要

- **服务端契约：零破坏（Phase 0 实测确认）。** OpenAPI 仅差 1 行（PTY 端点的 `x-websocket` 注解）；ocdroid 用到的端点路径/方法/schema/SSE 事件/数据模型**全部不变**——30+ 端点 live 回归全 200、形状符合。仅 3 处可观察的微妙行为变化（均兼容/低风险）。
- **客户端参考实现：有新增可学。** v1.17.14+ 新出了 v2 `session-review`、v2 `file-tree`、desktop `onboarding/window-registry` 等，可对照移植。
- **v2 API 是两面体（Phase 0 实测确认）**：目录/元数据类（model/provider/agent/command/integration/skill）**已 GA、可迁**；会话内容/执行类（message/context/history/prompt/compact/wait）**实验性、空或 503 桩**——5 个会话实测 message 空 5/5、`/wait` 503 5/5，**勿碰**。
- **1 个安全发现（Phase 0 实测坐实）**：v1 `/config/providers` 在响应里**明文回传 provider API Key**（4/9 provider 泄漏 live key）→ 建议尽快切 v2 `/api/model` + `/api/provider`。
- **Phase 0 已通过**（`PASS WITH WARNINGS`，2026-07-08）→ 可安全把对接基准升到 1.17.15。
- **推荐 5 阶段推进**（P0 校验 ✅ → P1 安全+快赢 → P2 体验 → P3 深度 → P4 按需）。

---

## 1. 完整升级范围

### 1.1 服务端契约影响（零破坏，3 处行为微变）

| 变化 | 位置 | 对 ocdroid | 风险 |
|---|---|---|---|
| `GET /session` 列表：传 `?directory=` 时改用服务端 `InstanceState.directory` | `handlers/session.ts`、`handlers/experimental.ts` | 单实例=单目录下功能等价；仅"一实例查他目录"边缘情况变 | 低 |
| 工具 part 出错时**保留 `metadata`** | `session/processor.ts` | ocdroid `PartState` 反序列化本就容错读 metadata → 兼容，反而更丰富 | 低（正向） |
| 新工具 `execute`（code-mode），受 `OPENCODE_EXPERIMENTAL_CODE_MODE` 门控，**默认关** | `tool/registry.ts` + `tool/code-mode.ts` | 未知工具走通用渲染，兼容 | 极低 |

> v1.17.13→v1.17.15 的 +6 万行几乎全是**新增独立包**（desktop/slack/stats/web/enterprise/ui-v2），不触及 instance HTTP API。`packages/sdk/openapi.json`（HTTP 契约权威）仅 1 行差异。

### 1.2 客户端参考实现新增（v1.17.14+，可对照学习）

- **session-ui**：全新 v2 `session-review` 套件（`session-review-v2.tsx` +331 / `.css` +432、`session-review-file-preview-v2.tsx` +287 及虚拟化、`line-comment-annotations-v2.tsx` +212、空状态 `empty-changes`/`empty-no-git`）；`message-part.tsx`(+135)、`basic-tool.tsx`(+25)/`.css`(+52)、`file-media.tsx`(+64)。
- **ui**：全新 `file-tree-v2.css`(+120)；`scroll-view.tsx`(+74)、`tabs.css`(+48)、`text-input-v2.tsx`(+36)、`toast-v2.tsx`、`marked.tsx`(+64)。
- **desktop**：全新 `window-registry`（多窗口 +47 +测试）、`onboarding`（main +28 / renderer +79）；`windows.ts` 重制(±81)。
- **tui**：`dialog-debug.tsx`(+90)、session 路由 `routes/session/index.tsx`(+66)。

### 1.3 v2 API 现状（实测 + 源码核实）

| 子面 | 状态 | 出处 |
|---|---|---|
| 目录类（model/provider/agent/command/integration/skill） | **GA、可用** | live 实测全 200 |
| 会话列表/元数据（`/api/session`、`/{id}`、`/active`） | 列表壳可用 | live 200 |
| 会话内容（`/message`、`/context`、`/history`） | **实验性、默认空**（需 `OPENCODE_EXPERIMENTAL_EVENT_SYSTEM` + v2 栈） | 实测全空；Phase 0 实测 **5/5 会话全空**；`core/src/session.ts` `SessionMessageTable` 独立存储 |
| 会话执行（`/prompt`、`/compact`、`/wait`） | **未实现（503 桩）** | 源码 `OperationUnavailableError` operation 字面量含 `compact/wait`；Phase 0 实测 `/wait` **503 5/5**（线上 `_tag:"ServiceUnavailableError"`） |
| `/interrupt` | 可用（204） | Phase 0 实测 **204 5/5**（需带 `Content-Type: application/json`，否则掉到 SPA 返 HTML） |
| 官方定性 | **Experimental v0.0.1** | `groups/session.ts:449,457-460`；`protocol/groups/session.ts:377` |
| 迁移计划 | **无公开时间表**；v1 个别端点已标 `deprecated`（`permission.respond`） | 源码 + 上游调研 |

**v2 实测体积/信息对比（同会话/同目录）：**

| 维度 | v1 | v2 | 结论 |
|---|---|---|---|
| Model 目录 | `/config/providers` **42KB（泄漏 key）** | `/api/model` 17KB + `/api/provider` 0.8KB（不泄漏） | v2 更安全且元信息更全（`limit/enabled/status/time.released/variants`） |
| Agent | `/agent` 142KB | `/api/agent` 43KB | v2 精简 3.3× |
| Command | `/command` 107KB | `/api/command` 12KB | v2 精简 8.7× |
| Session 列表 | `/session` 11KB | `/api/session` 26KB（每条带 agent/model） | v2 更全 |
| 消息 | `/session/{id}/message` **748KB ✓** | `/api/session/{id}/message` **空 `data:[]`** | v1 可用；v2 有 location 路由坑 |
| Health | `/global/health`（带 version） | `/api/health`（无 version） | v1 更好 |
| 功能面 | — | integration/skill/credential/session-active/context/history/per-session event/两阶段 revert/interrupt | v2 严格超集 |

### 1.4 安全发现

v1 `GET /config/providers` 响应含 `"key":"sk-..."`（provider API Key 明文）。ocdroid 当前调用它 → 若被日志/缓存/截图会泄漏。**v2 不泄**。

> **Phase 0 实测坐实**（2026-07-08）：9 个 provider 中 **4 个明文泄漏 live key** — `deepseek`（`sk-2749925...`，35 字符）、`xiaomi`、`opencode-go`、`kimi-for-coding`。`/api/provider` 不含 `key`。

### 1.5 Phase 0 实测回归结果（升级决策基础）

**执行**：2026-07-08，对 live 1.17.15（`localhost:4096`）跑全量 HTTP 回归（fixer 子代理，仅 curl/python3；**未动模拟器/Kotlin/`check.sh`**）。
**结论**：**`PASS WITH WARNINGS` → 事后 2 WARN 均闭合，实质 = `PASS`**。无端点回归/消失，v1 全功能，v2 会话面实测确认不可用。2 个 WARN（F2、H2）经源码/历史数据复核**均已闭合且均为非问题**：F2 仅对默认关的实验性 execute 工具生效（休眠）；H2 是测试体畸形（ocdroid 真实 `PromptRequest` 完全合规）。

**回归矩阵（节选，完整 30+ 项见 fixer 报告）：**

| 组 | 端点 | 结果 | 要点 |
|---|---|---|---|
| A | `GET /global/health` | ✅ | `{"healthy":true,"version":"1.17.15"}`，`version` 在 |
| A | `GET /config/providers` | ⚠️ | **4/9 provider 明文泄漏 `key`**（见 §1.4） |
| A | `/agent` `/command` `/api/model` `/api/provider` `/api/agent` `/api/command` | ✅ | 全 200，形状符合 |
| B | `GET /session/{id}/message` | ✅ | **381KB / 49 条，非空基线成立**（v1 消息流核心依赖） |
| B | `/session` `/api/session` `/{id}` `/{id}/diff` `/todo` `/children` `/session/status` | ✅ | 全 200；`/session/status` 空闲时返 `{}`（对象非数组） |
| C | `/file` `/file/content` `/file/status` `/find/file` | ✅ | `/find/file` 返回**裸字符串数组**（非 `{data:[]}`，需容忍） |
| D | `GET /permission` `/question` | ✅ | `[]` |
| E | `GET /global/event`（SSE） | ✅ | `text/event-stream`，3s 内收到 `server.connected` |
| F1 | `GET /session?directory=` | ✅ | 接受并按目录 scope（18 vs 19 条）—— directory 解析变化未破坏 |
| F3 | `GET /experimental/tool/ids` | ✅ | 20 工具，**无 `execute`/`code-mode`**（flag 关）；`webfetch` 重复 1 次 |
| G | v2 会话面 ×5 会话 | ✅（确认不可用） | message 空 **5/5**；`/wait` **503 5/5**；`/interrupt` **204 5/5**；`/context` 空 5/5；`/history` 空 5/5 |
| H | `POST /session` → `abort` → `DELETE` | ✅ | 创建/中止/删除全 200，清理校验通过（GET→404，无孤儿） |
| H2 | `POST /session/{id}/prompt_async` | ⚠️ | 最小 part 体 `{type,text}` 被 **400** 拒（"Missing key at parts"）；端点可达，仅测试体缺 per-part 必填键 |

**决策含义：**
1. **升级安全**：ocdroid 依赖的 v1 契约在 1.17.15 上完整可用 → 可把对接基准升到 1.17.15。
2. **v2 会话面铁证不可用**（5/5 实测）→ §1.3"会话层留 v1"由"源码推断"升级为"实测确认"。
3. **API Key 泄漏坐实**（4 provider 的 live key）→ P1 切 `/api/model`+`/api/provider` 紧迫性提升；切之前务必对该响应日志/缓存脱敏。
4. **`/wait` 503 线上错误标签是 `_tag:"ServiceUnavailableError"`**（源码类名 `OperationUnavailableError`，序列化标签不同）→ 客户端若按错误标签匹配，以线上 `_tag` 为准。
5. **遗留 follow-up**：
   - (a) ✅ **H2 已闭合**（2026-07-08，源码契约对照）：正确体是**顶层** `{parts:[…], agent?, model:{providerID,modelID}?}`（无 `prompt` 包装；`TextPartInput` 仅需 `{type,text}`）。fixer 的 400 是**测试体畸形**（把 `parts` 套进 `prompt:{…}`、`providerID/modelID` 摊在顶层）。**ocdroid 的 `PromptRequest(parts, agent, model:ModelInput)` + `PartInput(type,text,…)` 与 1.17.15 契约完全一致，无需改动**。四种 part 输入必填键：`Text{type,text}`、`File{type,mime,url}`、`Agent{type,name}`、`Subtask{type,prompt,description,agent}`。
   - (b) ✅ **F2 已闭合**（2026-07-08）：源码 `processor.ts:189-198` 确认 1.17.15 错误态含 `metadata: match.part.state.metadata`；但全量扫描 19 个历史会话（7.8MB，**70 个错误 part**）发现 **0 个带 metadata**——因唯一会在 running 期写 `state.metadata` 的是 `execute`(code-mode) 工具（默认关），普通工具失败时无 metadata 可保留。**结论：F2 仅对 `OPENCODE_EXPERIMENTAL_CODE_MODE` 开启场景生效；默认配置下休眠，对 ocdroid 无影响**（`PartState` 反序列化本就容错 metadata 有无）。

---

## 2. v2 目录类 API 采用意见

> 结论：**目录类值得迁，且 model 目录应作为最高优先级（止漏）。** 注意 envelope、location 路由、分页、health 四处差异。

### 2.1 逐端点意见

| v2 端点 | 替代的 v1 | 实测体积差 | 采用意见 | 理由 / 注意 |
|---|---|---|---|---|
| **`/api/model` + `/api/provider`** | `/config/providers`(42KB,泄漏key) | 17KB + 0.8KB | **强烈采用（P1）** | **消除 API Key 明文泄漏**；model 带 `limit{context,output}`（ocdroid context-limit 索引所需）；`enabled/status/time.released/variants`。注意：provider 与 model 拆两端点，需拼接；`/api/model` 已在 `OpenCodeApiV2` 用，扩 `/api/provider` 即可 |
| `/api/agent` | `/agent`(142KB) | 43KB（精简 3.3×） | 采用（P2） | 信封 `{location,data}`；更精简。ocdroid 仅用于 agent-picker，够用 |
| `/api/command` | `/command`(107KB) | 12KB（精简 8.7×） | 采用（P2） | 同信息、体积小很多；信封化 |
| `/api/session`（列表） | `/session`(11KB) | 26KB（更全） | 采用（P2） | 每条带 `agent`/`model`，更全。**但内容仍走 v1** |
| `/api/session/active` | 无 | — | 采用（P2，小） | "恢复上次会话"体验 |
| `/api/integration` | 无 | 20KB（新） | 按需采用（P4） | provider OAuth 登录流；配合"provider 认证"功能（L）一起做 |
| `/api/skill` | 无 | 103KB（新） | 暂不采用 | 对聊天 UX 价值低；可选调试面板 |
| `/api/health` | `/global/health` | 16B（**无 version**） | **不替代** | v2 health 无 `version`；版本显示与 `ServerCompatProfile` 继续用 v1 `/global/health` |

### 2.2 迁移共性注意（4 处差异）

1. **响应信封**：v2 统一 `{location:{directory,project}, data:[...]}`，v1 是裸数组/对象 → 扩 `OpenCodeApiV2` 时用 v2 响应包装（`V2Response<T>` = `{data, location?}`）。
2. **location 路由**：v2 靠 `?location[directory]=<dir>`（深对象形式）。`DirectoryHeaderInterceptor` 已对 `/api/` 自动加该参数（`DirectoryHeaderInterceptor.kt:79-87`）→ 新端点只要走同一 OkHttp 链即覆盖，无需逐个改。
3. **分页模型不同**：v2 用响应体 `{data, cursor:{previous,next}}`；v1 消息用响应头 `X-Next-Cursor`。两套并存，别混用。
4. **health 无 version**：版本相关一律留 v1。

---

## 3. 改进项全貌

**API 类**：① model 目录切 v2（止漏）② VCS/Git 只读 ③ `/experimental/tool` 调试 ④ interrupt（备用）/两阶段 revert ⑤ v2 目录全量迁移
**UX 类**：⑥ 会话重试卡 + 倒计时 ⑦ 工具卡折叠 + 计数摘要 ⑧ 逐文件 diff 审查 ⑨ 流式 Markdown 分块增量

### 3.1 API 类速览

| 项 | 接口 | 意见 |
|---|---|---|
| VCS/Git 只读 | `GET /vcs`、`/vcs/status`、`/vcs/diff?mode=git\|branch`、`/vcs/diff/raw`(text/plain)、`/project/current` | 中优先（M）；ocdroid 现 Git 全无感；`/vcs/diff/raw` 需 `Response<ResponseBody>` |
| 工具列表 | `GET /experimental/tool/ids`、`/experimental/tool?provider&model` | 低优先（S，调试用）；`parameters` 是自由 JSON Schema，用 `JsonElement` |
| 中断/阻塞 | v1 `/abort`（200 true，硬取消，**继续用**）；v2 `/interrupt`（204，备用）；v2 `/wait`（**503 桩，勿用**） | interrupt 低；wait 勿接 |
| 两阶段 revert | v1 `/revert`（已在用）或 v2 `revert/stage`→`commit`/`clear`（返回 `RevertState{diff,files}`） | 中优先（M）；升级为"回滚预览 diff → 确认" |

### 3.2 UX 类速览（参考机制 / ocdroid 现状 / 改进方法）

| 项 | 参考源 | ocdroid 现状 | 改进方法 | 工作量 |
|---|---|---|---|---|
| **重试卡 + 倒计时** | `session-ui/session-retry.tsx`（74 行，`status.next` 倒计时） | 数据全有，仅渲染红点+静态文本 | `LaunchedEffect{delay(1000)}` + `CircularProgressIndicator` + `errorContainer` | **S** |
| **工具卡折叠 + 计数摘要** | `basic-tool.tsx`（spring + deferredMount LIFO）、`tool-count-summary.tsx`（`animateIntAsState`） | 平铺卡片墙，无折叠/摘要 | `updateTransition`+`spring()`、deferredMount 逐帧挂载、消息级 `groupingBy{tool}.eachCount()` | **M** |
| **逐文件 diff 审查** | `session-diff.ts`、`session-review*.tsx`、`apply-patch-file.ts` + v2 虚拟化（500 行阈值） | 原始 patch 文本着色，`MAX_LINES=400` 截断 | 新增 `parseUnifiedPatch()`→`DiffHunk`；`LazyColumn` 虚拟化（去截断）；过滤/全展开 | **L**（分阶段） |
| **流式 Markdown** | `markdown-stream.ts`（`stream()`/`project()` 分块）、`markdown-cache.tsx`（checksum LRU 200）、worker stable/unstable token、`heal()` 半截链接 | `mikepenz/markdown`；流式 prose/code 分开；无高亮/无缓存/完成态全量重解析 | 块级 `LazyColumn`；完成态 LRU `LinkedHashMap(accessOrder)`；代码高亮用 Kotlin 库（auburn 等）替代 Shiki | **L**（先做缓存这一步 S） |

---

## 4. 分阶段推进方案

> 每阶段独立可交付、可回滚。工作量 S=天 / M=1–2 周 / L=2–4 周。

### Phase 0 — 升级前校验（~0.5–1 天）【✅ 已执行 2026-07-08】
- **HTTP 契约回归：已完成，`PASS WITH WARNINGS`**（详见 §1.5）。结论：v1 契约在 1.17.15 上完整可用，可安全升级对接基准。
- **剩余客户端侧 follow-up**（HTTP 层无法覆盖，需在 ocdroid 集成时确认）：
  - 真机/模拟器集成跑 `connectedDebugAndroidTest`（**仅模拟器**：`./scripts/emulator.sh status`→`start`→用完 `stop`）。
  - ~~确认 ocdroid 的 `PromptRequest`~~ **H2 已闭合**：ocdroid `PromptRequest(parts,agent,model:ModelInput)` 与 1.17.15 顶层 `{parts,agent,model}` 契约完全一致；fixer 的 400 是测试体畸形（多套 `prompt` 信封），非 ocdroid 问题，无需改动。
  - ~~错误工具 part 的 `state.metadata`~~ **F2 已闭合**（源码 + 70 个历史错误 part 实测：默认配置下错误 part 无 metadata，仅实验性 execute 工具才会保留；对 ocdroid 无影响）。
  - 多目录场景下回归 `GET /session` 的 directory 解析变化（HTTP 层已确认接受 `?directory=`，未破坏）。
- 改动后 `./scripts/check.sh` 必过。
- **产出**：升级无破坏确认 → 可安全把对接基准升到 1.17.15。

### Phase 1 — 安全 + 快赢（S，~1 周）
| 项 | 类型 | 验收 |
|---|---|---|
| **1A model 目录切 v2**（`/api/model`+`/api/provider`，止漏 + 取 `limit`） | API/安全 | 响应/日志无 `sk-` 密钥；context-limit 索引正确 |
| **1B 会话重试卡 + 倒计时** | UX | retry 态显示倒计时/原因/第几次 |

风险：低。回滚：保留 v1 `/config/providers` 作 fallback 开关直到稳定。

### Phase 2 — 体验提升（M，~2–3 周）
| 项 | 类型 | 验收 |
|---|---|---|
| **2A 工具卡折叠 + 计数摘要** | UX | 长会话可导航；开多卡不卡 |
| **2B v2 目录全量迁移**（agent/command/session 列表/active；统一 `V2Response<T>` 信封） | API | 体积下降；信息不丢；location 路由正确 |
| **2C 两阶段 revert**（v1 path 预览→确认，或 v2 stage/commit/clear） | API | 回滚前显示影响 diff |

风险：中（信封改造、revert 状态机）。

### Phase 3 — 深度功能（L，~3–6 周，可分项并行）
| 项 | 类型 | 验收 |
|---|---|---|
| **3A diff 审查：hunk 解析 + LazyColumn 虚拟化 + 过滤/全展开**（去 `MAX_LINES=400` 截断） | UX | 大 diff 不截断 |
| **3B Markdown 完成态 LRU 缓存 + 块级 LazyColumn** | UX | catch-up 重载不卡 |
| **3C VCS 只读面板**（branch/status/diff；`/vcs/diff/raw` 用 `ResponseBody`） | API | 显示分支与改动 |

风险：中高（需 Kotlin diff 解析器；markdown 改造）。

### Phase 4 — 后置 / 按需（L，明确需求再做）
流式代码高亮、diff 分屏 + 行评论、PTY/WebSocket 终端（手机端价值有限）、provider OAuth（`/api/integration`，需 WebView）、`/experimental/tool` 调试面板、流式 Markdown 全面重写。

### 持续「勿做」清单
v2 会话内容/执行接口（`/api/session/{id}/message`、`/context`、`/history`、`/prompt`、`/compact`、`/wait`）——实验性、空、桩；**会话与消息流必须留 v1**。

---

## 5. 注意事项（Caveats）

**A. v2 相关**
1. v2 会话面 = **Experimental v0.0.1**，**不要**进生产路径；官方无默认启用/退役时间表。
2. `/api/session/{id}/wait`、`/compact` 是 **503 桩**（Phase 0 实测 `/wait` 503 5/5；线上 `_tag:"ServiceUnavailableError"`，源码类名 `OperationUnavailableError`），别接；`/interrupt` 可用（204 5/5），但**必须带 `Content-Type: application/json`**（否则掉到 SPA 返 HTML），语义=v1 abort，无必要换。
3. v2 location 路由用 `?location[directory]=`（`DirectoryHeaderInterceptor` 已自动加），但**新端点要确认走同一 OkHttp 链**。
4. **`/api/health` 无 `version`** → 版本显示与 `ServerCompatProfile` 继续用 v1 `/global/health`。
5. v2 分页是响应体 `cursor`，v1 消息是 `X-Next-Cursor` 头——**两套别混**。
6. `OPENCODE_EXPERIMENTAL_EVENT_SYSTEM` 默认关；不开它，v2 消息永远空（这是"空"的根因，非 bug）。

**B. 数据/安全**
7. **`/config/providers` 泄漏 API Key**——在 Phase 1 切 v2 前，注意对该响应的日志/缓存做脱敏，避免截图/上报。
8. anomalyco 镜像**滞后于 sst 上游**，且 v2 文件结构上下游不同（上游已有 `v2/session.ts`/`groups/v2.ts`，anomalyco v1.17.15 还没有）——参考实现以**本地 `opencode-src/v1.17.15/`** 为准；web 引用（changelog 日期、上游 issue #33605 "session wait endpoint always returns unavailable"，指派 jlongster，关联 PR #33583/#33643）仅作方向参考。

**C. 工程纪律（仓库硬规则）**
9. 每次改 Kotlin/资源后**必跑 `./scripts/check.sh`**（本工作区 LSP 已关，这是等价自检）。
10. UI/插桩测试与安装**仅用模拟器**；用前 `./scripts/emulator.sh status` 确认未占用，用完 `stop`。禁止真机跑 debug/测试，除非用户明确要求。
11. 版本号**禁手改** `app/build.gradle.kts`，走 `./scripts/release.sh`。
12. 多目录场景回归 `GET /session` 的 directory 解析行为变化。

**D. 回滚策略**
- 各 Phase 独立可回滚；v2 目录迁移期保留 v1 fallback 开关。
- 出现 v2 端点不稳（如 anomalyco 某次同步动了 v2）→ 一键切回 v1 目录端点，会话流不受影响（本就 v1）。

---

## 6. 一页式路线图

```
P0 校验 ✅ ──▶ P1 安全+快赢(~1w) ──▶ P2 体验(~2-3w) ──▶ P3 深度(~3-6w) ──▶ P4 按需
  PASS w/ WARN     • model切v2止漏       • 工具卡折叠摘要      • diff虚拟化         • OAuth/PTY
  (实测见§1.5)     • 重试倒计时卡         • v2目录全量          • Markdown缓存       • 流式高亮
                   (S)                    • 两阶段revert        • VCS只读面板         (L,按需)
                                          (M)                   (L)
   ✅ 勿碰：v2 会话内容/执行(message/context/history/prompt/compact/wait)——Phase 0 实测 5/5 不可用
```

**底线**：本次升级**服务端零破坏**，重点不是"适配变更"，而是"借机改进 + 止漏"。**最高优先两件事**：Phase 1 的 **model 目录切 v2 消除 API Key 泄漏**（安全）与**重试倒计时卡**（体验快赢）；中期把工具卡摘要与 diff 虚拟化做上去；**v2 只在目录层择优迁移，会话层一律留 v1**，直到官方把 v2 会话面从 experimental 转正（目前无时间表）。

---

## 附录：方法与可信度

- **契约差异**：因 anomalyco 在 v1.17.13↔v1.17.14 间整体重写了 git 历史（`merge-base` 为空；PR 号从百级跳到 #35xxx 量级），commit-log changelog 不可用；改用两棵树的 `git diff` + `openapi.json` 比对。GitHub Releases 两 tag 均无 body。
- **v2 实测**：对 51 个 `/api/*` 路径抽样 GET 验证（全 200）；`POST /api/session/{id}/interrupt` 需带 `Content-Type: application/json` 才返 204（否则掉到 SPA 返 HTML）；v2 消息跨 8 个会话/2 项目全空，v1 同会话 139KB–1.6MB。
- **Phase 0 全量回归**（2026-07-08，fixer 子代理，仅 curl/python3）：30+ 端点 live 探测，`PASS WITH WARNINGS`（2 WARN 事后均闭合 → 实质 `PASS`）。v1 契约全功能；v2 会话面 5/5 不可用（message 空、`/wait` 503、`/interrupt` 204）；`/config/providers` 4/9 provider 泄漏 live key；`execute` 工具不在 `/experimental/tool/ids`。完整矩阵见 §1.5。
- **可信度分级**：
  - ✅ 高（本地 v1.17.15 源码核实 + Phase 0 live 实测）：Experimental 定性、`v2.session.*` op id、`experimentalEventSystem` 默认关、`SessionMessageTable` 独立存储、`/wait`/`/compact` 为桩（源码类 `OperationUnavailableError`；线上 `_tag:"ServiceUnavailableError"`，Phase 0 实测 503 5/5）、v1 个别端点标 deprecated、v1 契约零破坏。
  - ⚠️ 中（上游 web 调研，本地镜像未逐一印证）：v2 首次进 changelog 约 2026-06-12；issue #33605 与关联 PR；上游已有 `v2/session.ts`/`groups/v2.ts`（anomalyco 镜像尚无）。
  - ✅ 已闭合：F2 错误 part `metadata` 保留——源码确认 + 19 会话/70 错误 part 实测（0 带 metadata，因仅 execute 工具写 running-metadata，默认关）。opencode 数据库：`~/.local/share/opencode/opencode.db`（sqlite，1.2GB）。
  - ✅ 已闭合：H2 `prompt_async` part schema——源码契约对照，正确体为顶层 `{parts,agent,model}`（无 `prompt` 信封）；ocdroid `PromptRequest` 完全一致，fixer 的 400 系测试体畸形，非回归。
  - 至此 Phase 0 的 2 个 WARN（F2、H2）**均已闭合**（均为非问题，非回归）。
  - 源码出处：`opencode-src/v1.17.15/` 的 `groups/session.ts`、`protocol/groups/session.ts`、`core/src/session.ts`、`runtime-flags.ts`、`AGENTS.md`（"V2 Session Core"）、`packages/sdk/openapi.json`。
