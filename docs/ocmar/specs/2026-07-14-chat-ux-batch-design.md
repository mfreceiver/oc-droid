# Spec — Chat UX Batch（agent/model 契约 + 重命名 + 通知可选 + UI 修复）

- **日期**：2026-07-14
- **slug**：`chat-ux-batch`
- **base**：`aa2aa34`（工作树含既有未提交改动 `app/build.gradle.kts` 的 arm64-v8a abiFilters，保留）
- **状态**：✅ 设计经 grilling 对齐并获用户批准；本文档为固化版

---

## 1. 原始需求

一句话：**按风险序实施既定的 8 分支改动，并在条件允许时并发 2-3 个 agent 处理完全独立的模块，以提升效率。**

8 分支源自前序 grilling（需求拓展 + 逐题对齐），已全部定稿。本文档将其固化成可审计 spec，并补充执行策略（风险序 + 并发波次 + 文件作用域独立性分析）。

## 2. 范围

### In scope（7 个可执行分支，C/D 为无操作研究结论）

| 分支 | 主题 | 风险 |
|---|---|---|
| A | 会话重命名（长按菜单 + 对话框） | 中 |
| B | agent/model 统一每会话粘滞契约 | **高（最侵入）** |
| C/D | opencode-src v1.17.18→v1.17.20 差异 | 无（研究结论：API 契约零破坏，无需改动） |
| E | 子 agent spinner 移到 @name 后 | 低 |
| F | 常驻通知可选 + 后台临时通知（question/完成） | 中 |
| G | 变宽时流式区高度不回缩（宽度感知锚） | 中 |
| H | 发消息后不自动滚动到底 | 低 |

### Out of scope（明确不做）
- `reasoning_options` catalog 字段展示、每消息 `variant`（推理档位）选择——未来增强。
- 桌面端 open-in-app、image-attachments.css 等 web-only 改动。
- 任何服务端改动（本应用是客户端）。

## 3. 逐分支规格

### A · 会话重命名

**目标**：session 列表长按增加"重命名"，并调整相应窗体标题/说明/按钮。

**改动文件**
- `ui/sessions/SessionsScreen.kt`：长按由"直接弹 archive AlertDialog"改为**锚定 `DropdownMenu`**（Tier A，`ui-style-spec`），菜单项 = 重命名 / 归档。重命名项打开 `AppFormDialog`（Tier C，TextField 必须用它，避 AlertDialog 吞触摸）；归档项保留现有确认。
- `ui/SessionViewModel.kt`：新增 `renameSession(sessionId, title)`，调 `repository.updateSession(id, title)` + 本地 `upsertSession`。
- `ui/SessionMutationActions.kt`：（按需）`launchRenameSession` free function，复用 slice 更新模式。
- `res/values/strings.xml` + `res/values-zh-rCN/strings.xml`：新增 `sessions_rename`("Rename"/"重命名")、`sessions_rename_title`("Rename session"/"重命名会话")、`sessions_rename_hint`("Leave blank to use the project folder name"/"留空则使用项目目录名")；复用 `common_cancel`。

**关键设计**
- 对话框预填 `session.title`；为 null 则空、placeholder=`displayName`（**不**把目录名回填成显式 title）。
- **允许留空**：清空 + 确认 → 发空 title → 服务端清除 → `displayName` 回退目录名。确认钮恒可用。
- 确认钮复用 `sessions_rename`（动作名作确认，与归档一致）；取消复用 `common_cancel`。

**成功标准**：长按出菜单（含重命名/归档）；重命名能改 `Session.title` 并即时反映在列表；留空回退目录名；取消不动；`PATCH /session/{id}` 调用正确。

**测试**：单测 `SessionViewModel.renameSession`（mock repository，断言 upsertSession 收到新 title）；`./scripts/check.sh` 通过。

---

### B · agent/model 统一每会话粘滞契约（agent ≡ model）

**目标**：根治"切换模型不生效"，并把 agent/model 统一为"每会话粘滞、不跨会话、不改就延续"。

**根因（已查证）**
- 服务端 `prompt.ts:646`：显式 `model` 恒优先于 agent.model → 客户端只需可靠附上显式 model。
- 客户端旧设计非对称：agent 有全局 `selectedAgentName`（跨会话携带），model 仅每会话覆盖表且不可清除、`chat.currentModel` 在切会话时被清空（picker 失真）。

**新契约（agent 与 model 完全一致）**
1. **无全局、不跨会话**：移除 agent 全局偏好。
2. 每会话"当前值"优先级：`pending(瞬态,未发送的选择)` > `推断(从本会话历史，跳过 compaction/title 等隐藏 agent)` > `null(新会话默认)`。
3. 新会话发 `agent=null`、`model=null` → 服务端默认；两 picker 统一显示"默认"。
4. pending 瞬态：未发送的选择在导航离开后回退（历史为权威）。

**改动文件**
- `util/SettingsManager.kt`：**移除** `selectedAgentName`、`session_agents`/`session_models` 持久化覆盖表及其 get/set/clear。
- `ui/AppStateSlices.kt`：**移除** `SettingsState.selectedAgentName`；`ChatState` 增 `pendingAgent: String?`、`pendingModel: Message.ModelInfo?`（瞬态）。
- `ui/AppStateDerived.kt`：`inferCurrentModel` 增"跳过 agent∉可见集合"守卫；**新增** `inferCurrentAgent(messages)`（读最近一条 user 消息的 agent，同样跳过隐藏 agent）。
- `ui/ComposerViewModel.kt`：`selectAgent`/`switchSessionModel` 改为写 `pendingAgent`/`pendingModel`（不再写全局/覆盖表）。
- `ui/AppCoreOrchestration.kt`：`dispatchSend` 解析改为 `agent = pendingAgent ?: inferCurrentAgent ?: null`、`model = pendingModel ?: inferCurrentModel ?: null`；发送后清 pending；**移除** `materializeDraftSession` 的 agent/model 拷贝（pending 已在 chat slice）。
- `ui/SessionListActions.kt`：`launchLoadAgents` 移除 selectedAgent 校验回填。
- `ui/MessageActions.kt`：移除 global←per-session agent 回填。
- `ui/CatchUpActions.kt`：移除 `syncAgentFromPage`。
- `ui/ConnectionActions.kt`：移除冷启动 `seedAgent`。
- `ui/SessionSwitcher.kt`：`currentModel=null` 清空保留（picker 已不依赖它，无害）。
- `ui/chat/Composer.kt`：`AgentPickerSheet`/`ModelPickerSheet` 读取 `pending ?: infer ?: null`；两 picker 顶部各加"默认"项（清除 pending）。
- `ui/chat/ChatTopBar.kt` + `ui/chat/ChatScaffold.kt`：picker 与 topbar 显示改读新源。

**成功标准**
- 新会话首次发：agent/model=null（服务端默认）。
- 会话内选 agent/model → 后续消息沿用，直到再改。
- A 会话切换不影响 B。
- `/compact` 后不沿用 compaction agent。
- 显式选 model 后服务端确实用它（服务端已保证）。
- 无 NPE/编译错误；旧全局/覆盖表代码无残留引用。

**测试**：单测 `inferCurrentAgent`（含 compaction-skip 用例）、`inferCurrentModel`（compaction-skip）、`dispatchSend` 解析（pending>infer>null 三态）；模拟器手测 4 场景（新会话选 agent、已选 agent 切 model、进行中切 agent/model、compact 后延续）；`./scripts/check.sh --full`。

---

### C/D · opencode-src v1.17.18→v1.17.20 差异（研究结论，无代码改动）

- 服务端 httpapi 路由、core Session/Message schema **零差异** → 客户端无需兼容改动。
- 已借鉴：`prompt-model-selection.ts` 解析顺序、`syncPromptModel`/`restorePromptModel` 模式（驱动 B 设计）。
- 可选未来项（本次不做）：`reasoning_options`、`variant`。

---

### E · 子 agent spinner 移到 @name 后

**改动文件**：`ui/chat/ChatSubAgentCard.kt`（把 `:142-155` 状态块 `when{isRunning→CircularProgressIndicator; isError→Warning}` + 条件 Spacer 从 `Text("@$name")` 之前移到之后，@name 与 spinner 间保留 4dp Spacer；起始 AccountTree 不动）；`ui/chat/ChatReasoningAndTodo.kt:119`（修正"mirrors SubAgent"过时注释）。

**成功标准**：running 时顺序为 `[AccountTree] @name ⟳ 描述 [>]`；error 时 `@name ⚠`。

**测试**：模拟器触发子 agent 任务观察；`./scripts/check.sh`。

---

### F · 常驻通知可选 + 后台临时通知

**目标**：(1) 常驻通知可选；(2) 后台出现 question 或任务完成 → 临时通知，点击进对应会话。

**约束（已确认）**：FGS 常驻通知**无法完全消除**（平台要求 foreground service 运行期必有一条）；"可选"=关时降为 `PRIORITY_MIN`+`setSilent`（事实隐藏），FGS 照跑以保后台 SSE。

**改动文件**
- `util/SettingsManager.kt`：新增 `persistentNotificationEnabled`（默认 **false**）。
- `service/notify/SessionStatusNotifier.kt` + `service/SessionStreamingService.kt`：当 `!persistentNotificationEnabled` 时，常驻通知 spec 用 `PRIORITY_MIN`+`setSilent(true)`（不改变 FGS 存活/SSE 保活）。
- `ui/settings/SettingsScreen.kt`（`SettingsNotificationsRoute`）：新增单开关 UI。
- SSE→通知桥（新增，位置待 plan 定，候选 `service/streaming/` 或 `di/AppLifecycleMonitor.kt`）：L2 后台 SSE 存活时，`question.asked`/`session.status{idle}` 事件 → 仅后台时（`!isInForeground`）触发 `notifyDecision`/`notifyIdle`（即时）；30s 轮询保留为兜底。

**触发语义**
- question：每个新 question（按 id 去重），走 `CHANNEL_DECISIONS`(HIGH)，`autoCancel`。
- 完成：根会话 busy→idle **且**有未读 assistant 内容（复用 `IdleUnreadAlert`/`unreadSessions`），走 `CHANNEL_IDLE`(HIGH)，`autoCancel`。
- 点击：复用 `EXTRA_SESSION_ID` + `MainActivity.handleSessionExtra`（冷/热启动）。

**成功标准**：开关默认关，关时常驻事实隐藏但后台仍工作；后台 question/完成即弹临时通知，点击直达会话；前台不弹。

**测试**：模拟器后台触发（连服务端发 question、跑完任务）；`./scripts/check.sh`。

---

### G · 变宽时流式区高度不回缩

**根因类别**：`StreamingMarkdownRender.kt` 的 `HeightAnchor`/`HeightAnchorRegistry` 0-shrink 锚把可见高度钉成"只增不减"；变宽时内容自然高下降，但陈旧（旧宽度、更高）的锚未失效 → 下方留白。旋转（config change）触发全量重建而恢复。

**改动文件**：`ui/chat/StreamingMarkdownRender.kt`（`HeightAnchorRegistry` 键由 `stableKey` 改为 `(stableKey, width)`——每宽度独立 0-shrink 锚，跨宽度陈旧高永不泄漏；删除 `:154-156` 的 `lastWidth` reset 分支与 `lastWidth` remember；LRU 上限 256 保留）；`ui/chat/StreamingMarkdownHelpers.kt`（相关单测更新）。

**成功标准**：流式中扩展窗口宽度 → 内容即时按新宽度回缩，下方无留白；旋转/拖拽均正确；每宽度内 0-shrink 仍成立。

**测试**：单测 HeightAnchorRegistry 宽度感知（同 key 不同 width 独立、不互染）；模拟器多窗口/分屏拖宽 + 流式；`./scripts/check.sh`。

---

### H · 发消息后不自动滚动

**根因（已确诊）**：`reverseLayout=true`，发送=在 index 0 插入新 item，旧 item 顶到 index 1 → `atBottom` 守卫失效 + `followBottom` 被锁 false。

**改动文件**：`ui/AppCoreOrchestration.kt`（`dispatchSendMessage` 发送前 dispatch `AppAction.PendingJumpToLatestSet(sessionId)`，复用 `ChatMessageContent.kt:565-576` 一次性消费者 `scrollToItem(0)`+`followBottom=true`）。`AppAction.PendingJumpToLatestSet` 已存在，无需新增。

**成功标准**：在最新位置发消息 → 自动滚到该消息并跟随后续流式内容。

**测试**：模拟器在最新位置发消息观察滚动；`./scripts/check.sh`。

---

## 4. 执行策略（风险序 + 并发波次）

用户要求：风险序，且条件允许时并发 2-3 个 agent 处理**完全独立**的模块。

### 文件作用域独立性矩阵

| 分支 | 主要文件 | 与谁冲突 |
|---|---|---|
| E | `ChatSubAgentCard.kt`, `ChatReasoningAndTodo.kt` | 无 |
| G | `StreamingMarkdownRender.kt`, `StreamingMarkdownHelpers.kt` | 无 |
| H | `AppCoreOrchestration.kt`, `AppAction.kt` | **B**（同改 AppCoreOrchestration） |
| A | `SessionsScreen.kt`, `SessionViewModel.kt`, `SessionMutationActions.kt`, strings | 无 |
| F | `SettingsManager.kt`, `SettingsScreen.kt`, `SessionStreamingService.kt`, `SessionStatusNotifier.kt`, SSE 桥 | **B**（同改 SettingsManager） |
| B | `SettingsManager.kt`+`AppCoreOrchestration.kt`+10 余文件 | H、F |

### 并发波次（每波内文件作用域两两不交，≤3 并发）

- **Wave 1（3 agent 并行）**：E + G + H
  - E=(ChatSubAgentCard, ChatReasoningAndTodo)，G=(StreamingMarkdownRender, Helpers)，H=(AppCoreOrchestration, AppAction) —— 两两不交。
- **Wave 2（2 agent 并行）**：A + F
  - A=(SessionsScreen, SessionViewModel, SessionMutationActions, strings)，F=(SettingsManager, SettingsScreen, SessionStreamingService, SessionStatusNotifier, SSE 桥) —— 两两不交。
- **Wave 3（1 agent，最后）**：B
  - 最侵入，且要整合 Wave1(H 改的 AppCoreOrchestration) 与 Wave2(F 改的 SettingsManager) 之上的改动；单独顺序执行，避免写冲突。

> 顺序符合风险序（低风险隔离项在前，B 最后），并发度 ≤3，满足用户约束。

## 5. 验证策略

- 每 task：implementer 自带单测 + `./scripts/check.sh`（编译 + 单测）。
- 关键分支（B/F/G）额外：`./scripts/check.sh --full`（+ lint + 覆盖率）。
- 行为验证（需模拟器，用前 `./scripts/emulator.sh status` 确认空闲）：B 的 4 场景、E 的子 agent、F 的后台通知、G 的多窗口拖宽、H 的发送滚动。
- 设备安全：UI/插桩/安装**仅用模拟器**（`AGENTS.md` 硬规则）。

## 6. 风险与回滚

- **B 风险最高**：移除全局 agent 基建牵涉发送/冷启动/catch-up/switch 多路径。缓解：Wave 3 单独做 + 最全单测 + 模拟器 4 场景；若反复失败 → 回方案层（阶段 7）。
- **G 已知不确定性**：静态分析与症状方向曾相悖，width-aware 重设计为稳健解；若仍不奏效 → 回流做实测钉机理。
- 全程**不 commit**（除非用户显式要求）；每 task 独立可回滚（git checkout 单文件）。

## 7. 可审计引用（待 plan/执行阶段填充）

- spec：本文档
- plan：`docs/ocmar/plans/2026-07-14-chat-ux-batch.md`（阶段 2 产出）
- report：`docs/ocmar/reports/2026-07-14-chat-ux-batch.md`（阶段 8 产出）
- ledger：阶段 4 确定（`ocmar-state` 缺失时的替代记账方式）
