# Chat UX Batch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use ocmar-subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 8 分支（A–H）实施 chat UX 改进：会话重命名、agent/model 统一每会话粘滞契约、通知可选 + 后台临时通知、子 agent spinner 位置、变宽流式高度回缩、发消息自动滚动。

**Architecture:** 纯客户端（Kotlin/Compose）改动，无服务端/API 契约变更。按文件作用域独立性分 3 波并发：Wave1=E+G+H（3 agent）→ Wave2=A+F（2 agent）→ Wave3=B 拆 3 子任务顺序执行（最侵入）。

**Tech Stack:** Kotlin, Jetpack Compose (Material3), kotlinx.serialization, Hilt, Retrofit, Room/SQLCipher。测试：JUnit 单测（`./gradlew testDebugUnitTest`）+ Compose UI（androidTest / 模拟器手测）。

## Global Constraints

- **minSdk 34**，单主线 `main`；版本号 git 派生，**禁止改 `app/build.gradle.kts` 的 version**。
- **每次改动后必跑 `./scripts/check.sh`**（编译 + 单测）；关键分支跑 `--full`。
- **UI/安装/插桩仅用模拟器**；用前 `./scripts/emulator.sh status` 确认空闲，用完 `stop`。禁用物理机。
- **UI 三层 overlay 规则**（`docs/ui-style-spec.md`，MANDATORY）：A=锚定 `DropdownMenu`（≤6 项）/ B=`AppBottomSheet` / C=`AlertDialog` family（`AppFormDialog`/`AppConfirmDialog`）。优先 `ui/theme/` 共享原语。
- **不 commit**（除非用户显式要求）；每 task 仅 `git diff` 留证。
- 序列化：`Json{ explicitNulls=false; ignoreUnknownKeys=true; coerceInputValues=true; encodeDefaults=true }`（`OpenCodeRepository.kt:103-109`）——显式 model 永远非 null 发出，null 则省略。
- `ocmar-state` 路径：`~/.config/opencode/skills/ocmar-subagent-driven-development/scripts/ocmar-state`；`--owner ocmar-chat-ux-batch`；state_dir `.ocmar/workflows/chat-ux-batch/`。

## File Structure（按分支）

| 分支 | 文件 | 职责 |
|---|---|---|
| E | `ui/chat/ChatSubAgentCard.kt`, `ui/chat/ChatReasoningAndTodo.kt` | spinner 位置；注释修正 |
| G | `ui/chat/StreamingMarkdownRender.kt`, `ui/chat/StreamingMarkdownHelpers.kt` | 宽度感知高度锚 |
| H | `ui/AppCoreOrchestration.kt` | 发送 dispatch 滚动意图 |
| A | `ui/sessions/SessionsScreen.kt`, `ui/SessionViewModel.kt`, `ui/SessionMutationActions.kt`, `res/values/strings.xml`, `res/values-zh-rCN/strings.xml` | 长按菜单 + 重命名 |
| F | `util/SettingsManager.kt`, `ui/settings/SettingsScreen.kt`, `service/SessionStreamingService.kt`, `service/notify/SessionStatusNotifier.kt`, `service/streaming/*`(SSE 桥) | 通知开关 + SSE→临时通知 |
| B | `util/SettingsManager.kt`, `ui/AppStateSlices.kt`, `ui/AppStateDerived.kt`, `ui/ComposerViewModel.kt`, `ui/AppCoreOrchestration.kt`, `ui/SessionListActions.kt`, `ui/MessageActions.kt`, `ui/CatchUpActions.kt`, `ui/ConnectionActions.kt`, `ui/SessionSwitcher.kt`, `ui/chat/Composer.kt`, `ui/chat/ChatTopBar.kt`, `ui/chat/ChatScaffold.kt` | 每会话粘滞契约 |

## Task Execution Order（并发波次）

- **Wave 1（3 agent 并行，文件两两不交）**：T1(E), T2(G), T3(H)
- **Wave 2（2 agent 并行）**：T4(A), T5(F)
- **Wave 3（顺序，整合 Wave1/2 之上）**：T6(B1), T7(B2), T8(B3)

> T3 改 `AppCoreOrchestration.dispatchSendMessage`；T7(B2) 改同文件 `dispatchSend`（不同函数）——Wave3 在 Wave1 之后，无冲突。T5 改 `SettingsManager`；T6-T8 改同文件移除字段——Wave3 在 Wave2 之后，无冲突。

---

### Task 1: 子 agent spinner 移到 @name 后（分支 E）

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/chat/ChatSubAgentCard.kt:132-213`
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/chat/ChatReasoningAndTodo.kt:119`

**Interfaces:** 无（纯 UI 重排，不改签名）

**Acceptance Criteria:**
- `T1-C1`: `ChatSubAgentCard` 的 Row 子项顺序为：AccountTree icon → `Text("@$name")` → (isRunning?spinner / isError?Warning) → 描述 → chevron。验证：读 `ChatSubAgentCard.kt`，`when{...}` 状态块位于 `Text("@$subAgentName")` 之后。
- `T1-C2`: running 时 spinner 紧贴 @name（中间 4dp Spacer）；@name 与 spinner 顺序即 `[name][spacer][spinner]`。
- `T1-C3`: `ChatReasoningAndTodo.kt:119` "mirrors the SubAgent card's running spinner style" 注释已更新（不再描述"spinner 在前"）。

- [ ] **Step 1: 重排 Row（先读现状）**

读 `ChatSubAgentCard.kt:132-213` 确认当前顺序（AccountTree → 状态块 → @name → 描述 → chevron）。把 `:142-155` 的状态块（`when { isRunning -> CircularProgressIndicator(...); isError -> Icon(Warning, ...) }` + 紧随的 `if (isRunning || isError) Spacer(width=4.dp)`）整块剪切，移到 `Text("@$subAgentName")` 块（`:161-170`）之后、描述块之前。在 @name 与状态块之间插入 `Spacer(modifier = Modifier.width(4.dp))`。

- [ ] **Step 2: check**

Run: `./scripts/check.sh`
Expected: PASS（编译 + 单测）

- [ ] **Step 3: 修过时注释**

`ChatReasoningAndTodo.kt:119` 注释改为反映"SubAgent card 把 spinner 放在名字之后"（不再说"mirrors"前置风格）。

- [ ] **Step 4: 模拟器手测**

`./scripts/emulator.sh status`（确认空闲）→ `start` → 装-debug → 触发子 agent 任务，观察 running 顺序为 `[AccountTree] @name ⟳ 描述`。`stop`。

- [ ] **Step 5: 记录 diff**

```bash
git rev-parse HEAD   # baseline
git diff --stat      # 改动仅 2 文件
```

---

### Task 2: 流式高度锚宽度感知（分支 G）

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/chat/StreamingMarkdownRender.kt:67-192`
- Test: `app/src/test/java/cn/vectory/ocdroid/ui/chat/StreamingMarkdownHelpersTest.kt`（或现有 HeightAnchor 测试位）

**Interfaces:**
- Produces: `HeightAnchorRegistry.update(key: Pair<Any,Int>, naturalHeight: Int)`、`anchorFor(key)`、`reset(key)`（key 改为 `(stableKey to width)`）

**Acceptance Criteria:**
- `T2-C1`: `HeightAnchorRegistry` 按 `(stableKey, width)` 复合键存 maxHeight；同 stableKey 不同 width 互不污染。单测：`update(("k" to 100), 50)` 后 `anchorFor(("k" to 200)) == 0`。
- `T2-C2`: `HeightAnchor`/`DebugHeightAnchor` 删除 `lastWidth` remember 与 `:154-156`/`:212-214` reset 分支；registry 调用传入 `(effectiveKey to width)`。
- `T2-C3`: 同 width 内仍 0-shrink（`update` 只增不减）；LRU 上限 256 保留。
- `T2-C4`: `./scripts/check.sh` 通过。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `width-aware key isolates anchors across widths`() {
    HeightAnchorRegistry.resetAllForTest() // 若无此 helper 则用 reset 逐个清
    HeightAnchorRegistry.update("k" to 100, 50)
    assertEquals(50, HeightAnchorRegistry.anchorFor("k" to 100))
    assertEquals(0, HeightAnchorRegistry.anchorFor("k" to 200)) // 跨 width 不泄漏
    HeightAnchorRegistry.update("k" to 100, 30) // 同 width 只增不减
    assertEquals(50, HeightAnchorRegistry.anchorFor("k" to 100))
}
```
（若 `HeightAnchorRegistry` 无 test-only resetAll，加一个 `internal` 的 `@TestOnly fun resetAllForTest()`，或测试用唯一 key 隔离。）

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "*StreamingMarkdown*"`
Expected: FAIL（当前 key 是 bare stableKey）

- [ ] **Step 3: 改 registry 为宽度感知**

`HeightAnchorRegistry`：`maxHeightByKey: MutableMap<Pair<Any,Int>, Int>`（LRU LinkedHashMap 不变，cap 256）。`update(key: Pair<Any,Int>, naturalHeight)`、`anchorFor(key)`、`reset(key)` 签名 key 类型改 `Pair<Any,Int>`。LRU 键改为复合 Pair（Pair 自身 hashCode 稳定，可用）。

- [ ] **Step 4: 改 HeightAnchor / DebugHeightAnchor 调用处**

删除 `val lastWidth = remember(effectiveKey){ intArrayOf(-1) }` 与 reset 分支。`SubcomposeLayout` 内 `val width = constraints.maxWidth` 后，调用改为：
```kotlin
val compositeKey = effectiveKey to width
HeightAnchorRegistry.update(compositeKey, naturalHeight)
val anchor = HeightAnchorRegistry.anchorFor(compositeKey)
```
（不再需要 reset——宽度已在键内。）

- [ ] **Step 4a: 更新既有 0-shrink 测试**

既有 `DebugHeightAnchor`/`HeightAnchorRegistry` 相关 androidTest（0-shrink 断言）与单测用旧 bare-key 调用，改键类型会破坏——同步把它们改为 `(stableKey to width)` 复合键调用；确认 0-shrink 用例在新键下仍成立（同 width 内非递减）。`grep -rn "HeightAnchorRegistry\|DebugHeightAnchor" app/src` 找全所有引用点。

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew testDebugUnitTest --tests "*StreamingMarkdown*"`
Expected: PASS

- [ ] **Step 6: check + 模拟器多窗口拖宽手测**

`./scripts/check.sh`；模拟器分屏/多窗口拖宽 + 流式输出，确认变宽即回缩无留白；旋转仍正确。

- [ ] **Step 7: 记录 diff**

---

### Task 3: 发消息后自动滚动（分支 H）

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/AppCoreOrchestration.kt:415-459`（`dispatchSendMessage`）

**Interfaces:**
- Consumes: `AppAction.PendingJumpToLatestSet(sessionId)`（已存在，`AppAction.kt:144`）；消费者 `ChatMessageContent.kt:565-576`。

**Acceptance Criteria:**
- `T3-C1`: `dispatchSendMessage` 在确认 sessionId 后、调用 `launchSendMessage` 前，dispatch `PendingJumpToLatestSet(sessionId)`（走 store dispatch / intent 通道，与 `SessionViewModel.requestJumpToLatest` 同机制）。
- `T3-C2`: 单测/读码确认：发送后 `ChatMessageContent` 的 pendingJump 消费者触发 `scrollToItem(0)` + `followBottom=true`（消费者已存在，无需改）。
- `T3-C3`: `./scripts/check.sh` 通过。

- [ ] **Step 1: 读 dispatchSendMessage 与 requestJumpToLatest 的 dispatch 机制**

`AppCoreOrchestration.kt:415-459`；`SessionViewModel.kt:106-108`（看 `requestJumpToLatest` 如何提交 `PendingJumpToLatestSet`）；并**核实 AppCore 内的 action/intent dispatch 通道**（`store.dispatch(AppAction.PendingJumpToLatestSet(...))` 或等价）——若 AppCore 无直接 dispatch 入口，则在该公共发送路径（经 ViewModel 或 store）上设置。先读 `AppAction.kt` + `AppCore` dispatch 路由确认机制，再写。

- [ ] **Step 2: 在 dispatchSendMessage 插入意图**

在 sessionId 确定后、`launchSendMessage(...)` 前，按 Step 1 核实的机制提交 `PendingJumpToLatestSet(sessionId)`。

- [ ] **Step 3: check**

Run: `./scripts/check.sh`
Expected: PASS

- [ ] **Step 4: 模拟器手测**

在最新位置发消息 → 观察自动滚到该消息并跟随后续流式。

- [ ] **Step 5: 记录 diff**

---

### Task 4: 会话重命名（分支 A）

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/sessions/SessionsScreen.kt:117-118,281-305,366-396`
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/SessionViewModel.kt:224-269`
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/SessionMutationActions.kt`（加 `launchRenameSession`）
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-zh-rCN/strings.xml`
- Test: `app/src/test/java/cn/vectory/ocdroid/ui/SessionViewModelTest.kt`（新增 rename 用例）

**Interfaces:**
- Consumes: `repository.updateSession(sessionId, title): Result<Session>`（`OpenCodeRepository.kt:488`，已存在）
- Produces: `SessionViewModel.renameSession(sessionId, title)`

**Acceptance Criteria:**
- `T4-C1`: 长按 session 行出**锚定 `DropdownMenu`**（Tier A），2 项：重命名 / 归档（替换单一 archive AlertDialog）。
- `T4-C2`: 重命名项打开 `AppFormDialog`：标题"重命名会话"、TextField 预填 `session.title`（null 则空、placeholder=`displayName`）、辅助文"留空则使用项目目录名"、确认钮"重命名"恒可用（允许留空）、取消"取消"。
- `T4-C3`: 确认 → `ViewModel.renameSession` → `repository.updateSession(id, title)` → 本地 `upsertSession`（slice 更新 displayName 即时反映）。留空发空串→服务端清除 title。
- `T4-C4`: 单测 `renameSession`：mock repository 返回带新 title 的 Session，断言 slice 收到该 Session。
- `T4-C5`: `./scripts/check.sh` 通过。

- [ ] **Step 1: 加字符串**

`values/strings.xml`：`sessions_rename`="Rename"、`sessions_rename_title`="Rename session"、`sessions_rename_hint`="Leave blank to use the project folder name"。`values-zh-rCN/strings.xml` 对应"重命名"/"重命名会话"/"留空则使用项目目录名"。

- [ ] **Step 2: 加 ViewModel.renamesession + launchRenameSession**

`SessionViewModel.kt` 仿 `archiveSession`(:231) 加：
```kotlin
fun renameSession(sessionId: String, title: String) {
    launchRenameSession(viewModelScope = viewModelScope, repository = repository,
        slices = store.slices, sessionId = sessionId, title = title)
}
```
`SessionMutationActions.kt` 仿 `launchSetSessionArchived`(:67) 加 `launchRenameSession`：`repository.updateSession(sessionId, title).onSuccess { upsertSession(slices, it) }`。

- [ ] **Step 3: 写单测**

`SessionViewModelTest` 加用例：mock `repository.updateSession` 返回 `Session(title="新名", ...)`，调 `renameSession`，断言 `slices.sessionList` 含该 session 且 title=="新名"。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew testDebugUnitTest --tests "*SessionViewModelTest*rename*"`
Expected: PASS

- [ ] **Step 5: 改长按为 DropdownMenu + AppFormDialog**

`SessionsScreen.kt`：
- `var pendingArchiveSession` 旁加 `var menuSession: Session? by remember { mutableStateOf(null) }` 与 `var renameSession: Session? by remember { mutableStateOf(null) }`。
- `onLongClick = { menuSession = session }`（替换原 `pendingArchiveSession = session`）。
- `DropdownMenu(expanded = menuSession != null, onDismissRequest = { menuSession = null })` 锚定在 row：两 `DropdownMenuItem`（重命名→`renameSession = session; menuSession = null`；归档→`pendingArchiveSession = session; menuSession = null`）。
- 归档确认 AlertDialog 保持（`pendingArchiveSession`）。
- `renameSession?.let { AppFormDialog(onDismiss = { renameSession = null }, onConfirm = { text -> viewModel.renameSession(it.id, text); renameSession = null }, title = { Text(stringResource(R.string.sessions_rename_title)) }, confirmText = stringResource(R.string.sessions_rename), ...) { TextField(value = ..., placeholder = { Text(it.displayName) }, ...) + 辅助文 Text(stringResource(R.string.sessions_rename_hint)) } }`。`AppFormDialog` 签名以 `AppFormDialog.kt` 现网用法（`ModelManagementSection.kt:139`/`HostProfilesManagerScreen.kt:737`）为准。

- [ ] **Step 6: check + 模拟器手测**

`./scripts/check.sh`；模拟器长按→菜单→重命名→改名/留空回退。

- [ ] **Step 7: 记录 diff**

---

### Task 5: 通知开关 + SSE→临时通知桥（分支 F）

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/util/SettingsManager.kt`（加 `persistentNotificationEnabled`）
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/settings/SettingsScreen.kt:376-447`（`SettingsNotificationsRoute` 加开关）
- Modify: `app/src/main/java/cn/vectory/ocdroid/service/notify/SessionStatusNotifier.kt`（spec 尊重开关）
- Modify: `app/src/main/java/cn/vectory/ocdroid/service/SessionStreamingService.kt:611-652`（min-priority/silent 应用）
- Create: `app/src/main/java/cn/vectory/ocdroid/service/streaming/SseNotificationBridge.kt`（SSE 事件→通知）
- Test: `app/src/test/java/cn/vectory/ocdroid/service/streaming/SseNotificationBridgeTest.kt`

**Interfaces:**
- Consumes: SSE 事件流（`sseEventStream`/`SseEventBridge`）、`AppLifecycleMonitor.isInForeground`、`notifyDecision`/`notifyIdle`（`AppLifecycleMonitor.kt:372-424`）、`buildContentIntent`（`:426`）。
- Produces: `SseNotificationBridge` 订阅 SSE，仅后台时把 `question.asked`/`session.status{idle}` 转通知。

**Acceptance Criteria:**
- `T5-C1`: `SettingsManager` 加 `persistentNotificationEnabled: Boolean`（ESP 持久化，默认 `false`）。
- `T5-C2`: 开关关时（`!persistentNotificationEnabled`），`SessionStatusNotifier`/`SessionStreamingService.buildNotification` 对常驻通知用 `PRIORITY_MIN` + `setSilent(true)`；FGS 仍存活、SSE 保活不变。
- `T5-C3`: `SettingsNotificationsRoute` 新增单 Switch（绑定 `persistentNotificationEnabled`）。
- `T5-C4`: `SseNotificationBridge`：订阅 SSE，`question.asked`（仅新 id、`!isInForeground`）→ 发通知；`session.status{idle}`（根会话、有未读、`!isInForeground`）→ 发通知；通知 `autoCancel` + `setContentIntent(buildContentIntent(sessionId/rootId))`。单测：前台不发；后台新 question 发一次（同 id 去重）。
- `T5-C4a`（去重共享）：SSE 桥与 30s 轮询**共享** `AppLifecycleMonitor.notificationSnapshot` 去重集（或等价共享集），避免同 id 双发导致 `SOUND|VIBRATE` 叠加。两路用**相同 notification id**（`key.hashCode()`）作为兜底（同 id 视觉去重）。
- `T5-C4b`（管道）：`notifyDecision`/`notifyIdle` 现为 `AppLifecycleMonitor` 私有。SSE 桥需经其一：① 把发布逻辑抽成 `internal` 共享 Notifier（ALM 与桥共用），或 ② ALM 暴露 `internal fun notifyDecision/notifyIdle`，桥注入 ALM 调用。**推荐 ①**（与 dev-design 的 IslandNotifier 方向一致），但本次只抽到"可被桥复用"的最小形态，不做完整 IslandNotifier 重构。
- `T5-C5`: `./scripts/check.sh` 通过。

- [ ] **Step 1: 加 SettingsManager 键**

`SettingsManager.kt` 仿现有 ESP 布尔键加 `var persistentNotificationEnabled: Boolean`（get/set，KEY 持久化，默认 `false`）。

- [ ] **Step 2: spec/构建尊重开关**

`SessionStatusNotifier` 产出的 spec 增 `silent: Boolean`（由 `!persistentNotificationEnabled` 决定）；`SessionStreamingService.buildNotification` 在 `silent` 时 `.setPriority(PRIORITY_MIN).setSilent(true)`（仍 `setOngoing` 维持 FGS）。

- [ ] **Step 3: SettingsNotificationsRoute 加 Switch**

`SettingsScreen.kt:376-447` 加一个 `Switch`，checked = `settingsManager.persistentNotificationEnabled`，onChange 写回。

- [ ] **Step 4: 写 SseNotificationBridge + 单测**

先抽共享 Notifier：把 `AppLifecycleMonitor.notifyDecision/notifyIdle/buildContentIntent` 的发布逻辑抽成 `internal`（ALM 持有，或独立 `internal object SessionNotifier`），使 ALM 的 30s 轮询与新的 SSE 桥共用同一发布点 + 同一 `notificationSnapshot` 去重集（T5-C4a）。
`SseNotificationBridge.kt`：构造注入 SSE 流（`SseEventBridge`/`sseEventStream`）、`AppLifecycleMonitor`（取 `isInForeground` 与共享 Notifier/去重集）、生命周期（仅 Service 拥有 SSE 的 L2 期收集）。`question.asked` 解析复用 `parseQuestionAskedEvent`；`session.status{idle}` 复用 `StatusAggregator`/未读判定。单测：mock 前台/后台 + 事件，断言通知调用次数与去重（含与轮询同 id 不重复）。

- [ ] **Step 5: 运行测试 + check**

Run: `./gradlew testDebugUnitTest --tests "*SseNotificationBridge*"`; `./scripts/check.sh`

- [ ] **Step 6: 模拟器手测**

后台（按 home）触发服务端 question 与跑完任务 → 临时通知弹出、点击直达会话；前台不弹。开关关时常驻隐藏。

- [ ] **Step 7: 记录 diff**

---

### Task 6: B1 — 推断函数（agent/model，跳过隐藏 agent）（分支 B）

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/AppStateDerived.kt:250-258`
- Test: `app/src/test/java/cn/vectory/ocdroid/ui/AppStateDerivedTest.kt`

**Interfaces:**
- Produces: `inferCurrentAgent(messages: List<Message>, visibleAgents: Set<String>): String?`；`inferCurrentModel(messages: List<Message>, visibleAgents: Set<String>): Message.ModelInfo?`

**Acceptance Criteria:**
- `T6-C1`: `inferCurrentAgent` 从最新消息向前扫，跳过 `agent∉visibleAgents`（compaction/title 等），取首条合格 user 消息的 `agent`；全空返回 null。单测：末条 user agent="compaction"（∉visible）→ 跳过取前一条 user 的 "build"。
- `T6-C2`: `inferCurrentModel` 加 `visibleAgents` 参数，同样跳过隐藏 agent 的消息；末条 assistant 来自 compaction → 跳过取前一条合格 assistant 的 resolvedModel。
- `T6-C3`: `./scripts/check.sh` 通过（纯加法，旧调用点暂用默认参数兼容）。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `inferCurrentAgent skips hidden agents`() {
    val msgs = listOf(userMsg(agent="build"), assistantMsg(), userMsg(agent="compaction"), assistantMsg(agent="compaction"))
    assertEquals("build", inferCurrentAgent(msgs, setOf("build")))
}
@Test fun `inferCurrentModel skips compaction assistant`() {
    val msgs = listOf(assistantMsg(model=anthropic), assistantMsg(agent="compaction", model=glm))
    assertEquals(anthropic, inferCurrentModel(msgs, setOf("build")))
}
```

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: 实现**

`AppStateDerived.kt`：`inferCurrentAgent`：`messages.reversed().firstOrNull { it.isUser && it.agent != null && it.agent in visibleAgents }?.agent`。`inferCurrentModel` 增 `visibleAgents` 参数：`messages.lastOrNull { it.isAssistant && (it.agent == null || it.agent in visibleAgents) }?.resolvedModel`（旧无参重载委托，visibleAgents=emptySet 视为不跳过——过渡兼容，T8 清理）。

- [ ] **Step 4: 运行确认通过；check**

---

### Task 7: B2 — pending + dispatch/picker 改读 pending?:infer（分支 B 核心）

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/AppStateSlices.kt`（ChatState 加 `pendingAgent`/`pendingModel`）
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/ComposerViewModel.kt:70-127`（selectAgent/switchSessionModel 改写 pending）
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/AppCoreOrchestration.kt:415-459`（dispatchSend 解析 + 发后清 pending）
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/chat/Composer.kt:461-579`（两 picker 读 pending?:infer + "默认"项）
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/chat/ChatTopBar.kt` + `ChatScaffold.kt`（显示源）
- Test: `app/src/test/java/cn/vectory/ocdroid/ui/AppCoreOrchestrationTest.kt`（dispatchSend 解析三态）

**Interfaces:**
- Consumes: `inferCurrentAgent`/`inferCurrentModel`（T6）、`SettingsState.agents`（**visible 集合 = `agents.filter{it.isVisible}.map{it.name}.toSet()`**——`/agent` 返回含 hidden 内部 agent 如 compaction/title，必须按 `isVisible` 过滤，否则 compaction 跳过失效）。
- Produces: `ChatState.pendingAgent`/`pendingModel`；picker 选中逻辑。

**Acceptance Criteria:**
- `T7-C1`: `ChatState` 增 `pendingAgent: String?=null`、`pendingModel: Message.ModelInfo?=null`。
- `T7-C2`: `selectAgent(name)` 改为 `mutateChat{ it.copy(pendingAgent = name) }`；`switchSessionModel(p,m)` 改为 `mutateChat{ it.copy(pendingModel = ModelInfo(p,m)) }`（**不再**写 `selectedAgentName`/`setAgentForSession`/`setModelForSession`）。
- `T7-C3**: `dispatchSend`：`agent = pendingAgent ?: inferCurrentAgent(msgs, visible) ?: null`、`model = pendingModel ?: inferCurrentModel(msgs, visible) ?: null`；发送后 `mutateChat{ copy(pendingAgent=null, pendingModel=null) }`。
- `T7-C4`: ModelPickerSheet 顶部加"默认"项（`onSwitch→clear pending`）；**AgentPickerSheet 已有"默认"项**（`__default__`→`onPick(null)`，`Composer.kt:477`），其语义自然变为"清 pending→回退 infer/null"，无需新增。两 picker 选中态 = `pending ?: infer ?: null`（null 时高亮"默认"）。
- `T7-C5**: 单测 dispatchSend 三态（pending 命中 / 无 pending 走 infer / 全空 null）。
- `T7-C6**: `./scripts/check.sh` 通过（此 task 后旧全局/覆盖表代码仍存在但已无消费者，T8 删除）。

- [ ] **Step 1: ChatState 加字段**（additive）
- [ ] **Step 2: 改 selectAgent/switchSessionModel 写 pending**（不再写旧路径）
- [ ] **Step 3: 改 dispatchSend 解析 + 发后清 pending**
- [ ] **Step 4: 写 dispatchSend 单测（三态）→ 运行确认通过**
- [ ] **Step 5: 改两 picker 读源 + 加"默认"项**
- [ ] **Step 6: 改 ChatTopBar/ChatScaffold 显示源**
- [ ] **Step 7: check**

---

### Task 8: B3 — 移除死代码（全局 agent / 覆盖表 / reseed / seed）（分支 B 清理）

**Files:**
- Modify: `util/SettingsManager.kt`（删 `selectedAgentName`、`session_agents`/`session_models` map 及 get/set/clear）
- Modify: `ui/AppStateSlices.kt`（删 `SettingsState.selectedAgentName`）
- Modify: `ui/SessionListActions.kt:702-721`（删 selectedAgent 校验回填）
- Modify: `ui/MessageActions.kt:289-296`（删 global←per-session agent 回填）
- Modify: `ui/CatchUpActions.kt:300-308`（删 `syncAgentFromPage`）
- Modify: `ui/ConnectionActions.kt:124-131`（删 seedAgent）
- Modify: `ui/AppCoreOrchestration.kt` `materializeDraftSession`（删 agent/model 拷贝）
- Modify: `ui/SessionSwitcher.kt:299`（`currentModel=null` 现无害；若 `currentModel` 已无消费者则一并删字段——见下）

**Acceptance Criteria:**
- `T8-C1`: `selectedAgentName`、`session_agents`/`session_models` map 及其 get/set/clear、`syncAgentFromPage`、`seedAgent`、`launchLoadAgents` 校验、`MessageActions` agent 回填、`materializeDraftSession` 拷贝——全部删除，编译无残留引用（`grep -rn "selectedAgentName\|setAgentForSession\|getModelForSession\|syncAgentFromPage\|seedAgent"` 仅剩注释或无）。
- `T8-C2**: `ChatState.currentModel` 若经 T7 后已无消费者，则连同其 reseed（`MessageActions:272`）/clear（`SessionSwitcher:299`）一并删除；若仍有用途则保留并注明。决策依据：T7 完成后 grep `currentModel` 引用。
- `T8-C3**: `./scripts/check.sh --full` 通过（编译 + 单测 + lint + 覆盖率）。
- `T8-C4**: 模拟器 4 场景全通过（新会话选 agent、已选 agent 切 model、进行中切 agent/model、compact 后不沿用 compaction）。

- [ ] **Step 1: grep 确认无消费者**：`grep -rn "selectedAgentName\|setAgentForSession\|getAgentForSession\|setModelForSession\|getModelForSession\|syncAgentFromPage\|seedAgent" app/src/main`
- [ ] **Step 2: 逐文件删除死代码**
- [ ] **Step 3: check --full**
- [ ] **Step 4: 模拟器 4 场景手测**
- [ ] **Step 5: 记录 diff**

---

## Criterion Ownership Matrix

| Criterion ID | Spec 要求 | Owner | 依赖 | 验证 | Final-only? |
|---|---|---|---|---|---|
| T1-C1..C3 | E: spinner 位置 + 注释 | T1 | — | 读码 file:line + 手测 | N |
| T2-C1..C4 | G: 宽度感知锚 | T2 | — | 单测 + 手测 | N |
| T3-C1..C3 | H: 发送滚动 | T3 | — | 读码 + 手测 | N |
| T4-C1..C5 | A: 重命名 | T4 | — | 单测 + 手测 | N |
| T5-C1..C5 | F: 通知开关 + SSE 桥 | T5 | — | 单测 + 手测 | N |
| T6-C1..C3 | B: 推断(跳隐藏) | T6 | — | 单测 | N |
| T7-C1..C6 | B: pending+dispatch+picker | T7 | T6 | 单测 + check | N |
| T8-C1..C4 | B: 死代码清理 + 4 场景 | T8 | T7 | grep + check --full + 手测 | N |
| 跨支线 | C/D 无操作（已结论） | — | — | spec §3.C/D | Y |
| 跨支线 | 8 分支整体集成不回归 | — | 全部 | 最终 verifier `check.sh --full` + 评审 | Y |

## Self-Review（已执行）

- **Spec 覆盖**：A=T4、B=T6/T7/T8、C/D=无操作（spec 已结论）、E=T1、F=T5、G=T2、H=T3。全覆盖，无遗漏。
- **占位扫描**：无 TBD/TODO；T5 的 SSE 桥位置已定为 `service/streaming/SseNotificationBridge.kt`。
- **类型一致**：`inferCurrentAgent`/`inferCurrentModel(messages, visibleAgents)` 在 T6 定义、T7 消费，签名一致；`pendingAgent: String?`/`pendingModel: Message.ModelInfo?` 在 T7 定义并消费。
- **可观测性**：每条 criterion 均为可验证信号（单测名 / 命令 / file:line + 预期）。
- **已知权衡**：B 拆 T6(add)→T7(switch)→T8(remove) 保证每中间态可编译；T8 的 `currentModel` 去留依据 T7 后 grep 结果现场定（非占位，是显式决策点）。

## Execution Handoff

Plan complete and saved to `docs/ocmar/plans/2026-07-14-chat-ux-batch.md`。采用 **Subagent-Driven**（pipeline 阶段 4 已定）。Wave1=T1,T2,T3 并行；Wave2=T4,T5 并行；Wave3=T6,T7,T8 顺序。
