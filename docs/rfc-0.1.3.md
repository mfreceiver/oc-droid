# RFC 0.1.3 — 整合改造方案（v2，评审修订版）

> 目标版本：`0.1.3`。本文件是 16 项需求的代码级 spec，供 glmer + dser 评审（门控 9 分）。
> v2 已整合 glmer(7.5) + dser(4) 两轮评审的全部"必须修复"项。标注 🐞=针对 v1 评审的修订。

---

## 0. 需求与文件归属（🐞 已重排，消除写冲突）

| 通道 | 独占文件 | 需求 |
|------|----------|------|
| **designer** | `ui/chat/ChatTopBar.kt` | #2,#3(顶栏本体),#8,#9 |
| **fixer-1** | `ui/chat/ChatScreen.kt`, `ui/chat/ChatMessageContent.kt` | #4,#13(卡片+接线),#15,#16,#3(ChatScreen padding 移除),#14(BackHandler) |
| **fixer-2** | `MainActivity.kt` | #1,#12 |
| **fixer-3** | `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsSections.kt` | #6,#7,#11(纯 UI) |
| **fixer-4** | `ui/MainViewModel.kt`, `MainViewModelSessionActions.kt`, `MainViewModelSupport.kt`, `MainViewModelConnectionActions.kt`, `MainViewModelSyncActions.kt`, `data/api/OpenCodeApi.kt`, `data/repository/OpenCodeRepository.kt`, `util/SettingsManager.kt`, `ui/sessions/SessionsScreen.kt` | #5,#10,#13(VM 态+清除) |

跨通道契约（详见各条）：
- **#5 契约**：fixer-3 编辑器 ⇄ fixer-4 `saveHostProfile`（见 §5 签名）。
- **#13 契约**：fixer-1 卡片接线 ⇄ fixer-4 VM 态（见 §13 签名）。
- **#14 契约**：fixer-1 调 `viewModel::selectSession`（fixer-4 已有，无需改）。

> 🐞 v1 把 ChatScreen.kt 同时分给 designer(#3) 与 fixer-4(#14)，v2 将 ChatScreen.kt 全部归 fixer-1，designer 只动 ChatTopBar.kt。#13+#16 同在 ChatMessageContent.kt 的 toggle 节点，统一归 fixer-1，避免中间态崩溃。

---

## 1. 横屏两栏分屏（#1）

`MainActivity.kt:111-122` 仅按 `WindowWidthSizeClass.Expanded`(≥840dp) 切 `TabletLayout`。多数手机横屏 600–840dp 落 Medium → 无分屏。

**方案**：新增 `LandscapeSplitLayout`，按 orientation 触发：
```kotlin
val config = LocalConfiguration.current
val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
when {
    isTablet -> TabletLayout(...)
    isLandscape -> LandscapeSplitLayout(...)
    else -> PhoneLayout(...)
}
```
`LandscapeSplitLayout` = 两栏 `Row`：`SessionsScreen(weight 0.25f, onSwitchToChat={})` + `VerticalDivider()` + `ChatScreen(weight 0.75f, onNavigateToFiles={}, onNavigateToSettings={}, showSettingsButton=false)`。旋转保态靠 Hilt VM。

🐞 **inset 归属（响应评审）**：见 §3 统一规则——本布局的 `Row` **不**加 `windowInsetsPadding(statusBars)`（v1 抄自 TabletLayout 的 :210 会与各 pane 的 TopAppBar 三重 padding）。状态栏 inset 统一由各 pane 内的 M3 TopAppBar 处理。

🐞 **极窄屏兜底**：横屏宽度 `<400dp` 时退化为 `PhoneLayout`（避免 90dp 会话栏不可用）：
```kotlin
val tooNarrow = config.screenWidthDp < 400
when { isTablet -> ...; isLandscape && !tooNarrow -> LandscapeSplitLayout(...); else -> PhoneLayout(...) }
```

🐞 **横屏文件点击兜底**：`onNavigateToFiles` 不置纯空，改为 `viewModel.showFileInFiles(path)`（已有，缓存待回竖屏展示），避免无反馈。

**风险**：低。

---

## 2-3. 聊天顶部栏 M3 TopAppBar 重构 + 标题 18sp（#2,#3）

现状（ChatTopBar.kt:139-160）：自定义 `Surface(tonalElevation=2.dp)`+`Row`；标题 `titleMedium`(16sp)+Bold；底部 HorizontalDivider(:336)。其它页全用 M3 `TopAppBar`，`titleLarge`(18sp)+Medium。

**方案**：用 M3 `TopAppBar` 替换：
```kotlin
TopAppBar(
    windowInsets = TopAppBarDefaults.windowInsets,   // ← 顶栏自身处理 status bar inset
    title = { SessionDropdownRow(...) },            // 会话下拉
    actions = { ContextMenuButton(...); if(showSettingsButton) IconButton(onSettings){...}; ServerStatusDot(...) }
)
```
标题走 TopAppBar 默认 `titleLarge`(18sp Medium) → 满足 #2。移除 tonalElevation、移除 :336 HorizontalDivider（顶栏与内容靠 `surface` 色对比分隔）。

🐞 **inset 统一规则（响应评审"三重 padding"）**：**全应用唯一原则——状态栏 inset 仅由各页 TopAppBar 处理**。据此：
- ChatTopBar 的 TopAppBar 用默认 `TopAppBarDefaults.windowInsets`（处理 inset）。
- **移除** ChatScreen.kt:85 的 `.statusBarsPadding()`（fixer-1 通道）。
- **移除** MainActivity.kt:210 (TabletLayout Row) 与 LandscapeSplitLayout Row 的 `windowInsetsPadding(statusBars)`（fixer-2 通道）。
- 需实测：SessionsScreen/FilesScreen/SettingsScreen 各自 Scaffold+TopAppBar 已自处理 inset（现状如此），不受影响。
- 实测项：TopAppBar 在非 Scaffold 容器（PhoneLayout 的 pager 内）中 inset 消费是否正确（M3 1.2+ 已知变化）。若 pager 内不生效，回退方案：TopAppBar 设 `windowInsets=WindowInsets(0)`，由 ChatScreen.kt:85 保留单一 `statusBarsPadding()`。**实施时先实测再定**，spec 锁定"仅一处应用 inset"。

🐞 **title 槽宽度（响应评审"title 不支持 weight"）**：`SessionDropdownRow` 现状 `.weight(1f)`(:215) 在 title 槽失效。改为：title 内用 `Modifier.widthIn(max = 240.dp)` 包裹锚点 Box；DropdownMenu 宽度另用固定/`IntrinsicSize.Max`（见 #9）。锚点文本 `maxLines=1, ellipsis`。

**风险**：中。需实测 inset 与 title 槽宽度。保留 `ChatTopBarState/Actions` 对外签名不变。

---

## 4. 历史消息加载触发（#4）🐞 重写方案

**根因**（已核读 ChatMessageContent.kt:103-143）：`listState.layoutInfo` 在 :104（composable scope）是 State 读取（触发重组），但 `derivedStateOf` 闭包捕获的是**局部快照 `layoutInfo`**（非闭包内 State 读取）→ 闭包内无被追踪读取 → 永不因滚动重算。

🐞 **修复（采纳 dser 方案，避免闭包捕获）**：把"是否靠近顶部"的纯布局判定放进只依赖 State 的 `derivedStateOf`；参数相关判定放进按参数 re-key 的 `LaunchedEffect`：
```kotlin
val nearTop = remember {
    derivedStateOf {
        val info = listState.layoutInfo              // 闭包内 State 读取 → 滚动时重算
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) false
        else (visible.maxOfOrNull { it.index } ?: 0) >= info.totalItemsCount - 3
    }
}
LaunchedEffect(nearTop.value, isLoading, messages.size, messageLimit) {
    if (!isLoading && messages.isNotEmpty() && messages.size >= messageLimit && nearTop.value) {
        onLoadMore()
    }
}
```
- 删除 :104 `val layoutInfo = listState.layoutInfo` 与原 :130-143 `shouldLoadMore`。
- reverseLayout=true 下最高 index = 视觉顶部 = 最旧，方向正确。
- 🐞 **去重/连续加载**：`loadMoreMessages`（MainViewModelSessionActions.kt:266）已有 `if(isLoadingMessages) return` 守卫；加载后 messageLimit 增长、messages 增长 → LaunchedEffect re-key；若仍 nearTop 且 `messages.size >= messageLimit` 则继续加载下一页（正确分页），直到服务端返回不足（`messages.size < messageLimit` 自动停止）。无无限循环。

**测试**：Compose UI 测试（`createComposeRule()`）模拟滚动到顶，断言 `onLoadMore` 被调用。

**风险**：低。

---

## 5. 认证密码保存修复（#5）🐞 三态契约补全

**根因**（核读确认）：编辑器密码字段恒 ""（SettingsScreen.kt:350）→ 保存 `ifBlank{null}` → saveHostProfile(:397-399) `setBasicAuthPassword(id,null)` → remove。任何编辑保存擦除密钥。

🐞 **三态契约（响应评审"null/空串歧义"）**：onSave 增加显式 edited 标志，避免靠 null/空串区分：
```kotlin
// 编辑器（fixer-3, SettingsScreen.kt HostProfileEditorDialog）
var authPassword by remember(initial.id){ mutableStateOf("") }
var passwordEdited by remember(initial.id){ mutableStateOf(false) }
// onValueChange: { passwordEdited = true; authPassword = it }
var tunnelPassword by remember(initial.id){ mutableStateOf("") }
var tunnelEdited by remember(initial.id){ mutableStateOf(false) }

// 保存回调签名（fixer-3 ⇄ fixer-4 契约）
onSave(
    profile,
    basicAuthPassword = authPassword,
    basicAuthEdited = passwordEdited,
    tunnelPassword = tunnelPassword,
    tunnelEdited = tunnelEdited
)
```
`MainViewModel.saveHostProfile`（fixer-4, :391-408）新签名：
```kotlin
fun saveHostProfile(
    profile: HostProfile,
    basicAuthPassword: String = "",
    basicAuthEdited: Boolean = false,
    tunnelPassword: String = "",
    tunnelEdited: Boolean = false
) {
    val normalized = ...  // passwordId 规整同现状
    // Basic auth
    if (basicAuthEdited) {
        // 用户动过：写入或清除
        settingsManager.setBasicAuthPassword(normalized.id, basicAuthPassword)  // blank→remove(现状语义)
    }
    // basicAuth 被整体移除（username 清空）时主动清残留
    if (normalized.basicAuth == null && /* 原本有 */) {
        settingsManager.setBasicAuthPassword(normalized.id, "")
    }
    // Tunnel：edited=false 时完全跳过（既不 set 也不 clear）
    if (tunnelEdited) {
        settingsManager.setTunnelPassword(normalized.id, tunnelPassword)
    }
    hostProfileStore.save(normalized)
}
```
🐞 **关键（响应评审"tunnel 残缺"）**：`tunnelEdited=false` 时**完全跳过 tunnel 分支**（不 set 不 clear），保持原值。`basicAuthEdited=false` 同理跳过 basic 写入（保持）。仅当用户主动编辑才写入/清除。

legacy 兼容（SettingsManager.kt:52 LEGACY id）保持。

**测试**：仅改 URL 保存 → 密码不被清除；显式清空密码 → 正确删除；不动 tunnel → tunnel 不变。

**风险**：低-中。

---

## 6. URL 展示调整（#6）

- 设置页 `ConnectionProfileSection`（SettingsSections.kt:69-73）移除 serverUrl 文本。
- 管理连接页 `HostProfileRow`（SettingsScreen.kt:269-275）在 displayName 下方加副标题 `profile.serverUrl`（bodySmall/onSurfaceVariant）。

**风险**：低。

---

## 7. 移除测试连接（#7）

删除 `HostProfileRow` Wifi IconButton（SettingsScreen.kt:286-298）+ `testingProfileId`/`onTest` 状态/分支（:138-143,182-198）。
🐞 **清理 dead code（响应评审）**：移除 `MainViewModel.testHostConnection`(:486-505)、未用字符串资源 `R.string.host_profile_test_success/failed`、未用 import（`Icons.Default.Wifi`）。`testConnection`（主连接测试，:507-541）保留（连接时仍用）。

**风险**：低。

---

## 8. 整合 Agent/待办/上下文为单一图标下拉（#8）🐞 明确二级菜单形态

合并 ChatTopBar 右侧三控件（:236-301）为单一 `ContextMenuButton`，下拉三项：上下文 `62% 113k/256k` / 待办 `3/3` / Agent `Plan`。触发图标沿用 `ContextUsageRing`。

🐞 **二级菜单形态（响应评审"嵌套 DropdownMenu 焦点问题"）**：**Agent 项不嵌套二级 DropdownMenu**，改为点击 Agent 项 → 关闭外层 dropdown → 打开**独立 AlertDialog 选择器**（同 TodoListPanel 的 Dialog 模式），列 `state.visibleAgents`，选中调 `actions.onSelectAgent(name)`。避免 M3 嵌套 DropdownMenu 的焦点/关闭冲突。
- 上下文/待办项：点击 → 关闭外层 → 打开现有 `ContextUsageDialog`(:511)/TodoListPanel AlertDialog(:338)。
- 🐞 `ContextUsageRing` 现状是 `Surface(onClick)`(:295-301)，改为 DropdownMenu anchor 时**移除原 onClick**。
- 待办标签始终显示（含 0/0），不再条件渲染。
- `formatCount` "k" 变体规则：≥1000 → `value/1000` 取整 + "k"（113000→113k），<1000 原值。

**风险**：低。

---

## 9. 会话下拉框限高（#9）🐞 宽度策略明确

`SessionDropdownRow` 的 DropdownMenu（:428-507）无高度/宽度限制 → 最多 8 行 ~450dp 覆盖动作区。

- 内容包 `Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()))`。
- 🐞 **宽度（响应评审"widthIn 可能被忽略"）**：用固定 `Modifier.width(280.dp)`（非 widthIn），确保生效；锚点文本 `maxLines=1, ellipsis` 防止锚点撑宽。

**风险**：低。

---

## 10. 项目连接会话流程（#10）🐞 多处补全

### 10a — 识别新 workdir 已有会话

服务端 `GET /session?directory=<path>` 精确过滤（lib-1 确认，优先级 `?directory` > header > cwd）。

- OpenCodeApi.getSessions 增参：`@Query("directory") directory: String? = null`、`@Query("roots") roots: Boolean? = null`。
- repository 增 `getSessionsForDirectory(dir, limit)`。
- `createSessionInWorkdir`（:868-886）进入 draft 后立即调用，结果存入**独立存储**（见下）。

🐞 **目录会话独立存储（响应评审"全局刷新丢弃目录会话"）**：`AppState` 增 `directorySessions: Map<String, List<Session>>`（key=workdir）。`getSessionsForDirectory` 写入此 map，**不**写入 `state.sessions`。`SessionsScreen` 的 workdir 分组改为：全局 `state.sessions` ∪ `state.directorySessions[workdir]` 合并去重显示。周期性全局 `getSessions`（:32）**不触碰** `directorySessions` → 目录会话不再被刷新丢弃。

### 10b — 发消息后弹回旧会话

🐞 **两处同修（响应评审"漏改 loadMore"）**：
1. `mergeRefreshedSessionsPreservingLocalActivity`（MainViewModelSupport.kt:74）**改签名**，增 `currentSessionId: String?` 与 `openSessionIds: Set<String>`（或改收 `AppState`）：
   ```kotlin
   internal fun mergeRefreshedSessionsPreservingLocalActivity(
       refreshed: List<Session>, local: List<Session>,
       currentSessionId: String?, openSessionIds: Set<String>
   ): List<Session> {
       val localById = local.associateBy { it.id }
       val refreshedIds = refreshed.map { it.id }.toSet()
       val base = refreshed.map { remote -> /* 现状 time 合并逻辑 */ }
       val preserve = local.filter { it.id !in refreshedIds &&
           (it.id == currentSessionId || it.id in openSessionIds) }
       return base + preserve
   }
   ```
   调用方（launchLoadSessions :42、launchLoadMoreSessions :109）传入 `state.value.currentSessionId` 与 `state.value.openSessionIds.toSet()`。
2. 🐞 **auto-select guard 两处**（launchLoadSessions :50-69 与 **launchLoadMoreSessions :120-125**）：第三分支条件统一收紧为 `currentId == null && refreshedSessions.isNotEmpty()`，与第一分支合并；**非空 currentSessionId 永不被 `refreshedSessions.first()` 静默替换**。`hasCurrentSession=false` 但 currentId 非空时仅 `onLoadMessages(currentId)`（容忍临时不在列表）。

🐞 **子会话保护范围（响应评审）**：`preserve` 用 `openSessionIds`（已排除子会话，:717）。正在查看的子会话若不在 refreshed，由 `childSessions` map 解析显示，不靠 sessions 列表——此为预期，spec 明示。

**测试**（补 exp-7 指出的盲区）：(a) merge 保留 currentSessionId 即使不在 refreshed；(b) loadMore 后 currentSessionId 不被改写为 first()；(c) SSE session.created 并发 upsert 不被随后 loadSessions 覆盖（preserve 兜底）。

**风险**：中-高。会话状态机核心，实施时交 oracle 复审。

---

## 11. 编辑器字段样式统一（#11）

Username/Password/Tunnel auth 统一为「标题左上 + 占位"(可选)"」：
```kotlin
Column {
    Text("Username", style = labelMedium)
    OutlinedTextField(value=..., onValueChange={...}, placeholder={Text("（可选）")}, singleLine=true)
}
```
🐞 **serverUrl 字段（响应评审）**：一并统一为同模式（标题"服务器地址"+ 输入框），四字段视觉一致。
🐞 **密码遮掩**：Password/Tunnel auth 用 `visualTransformation = PasswordVisualTransformation`（与现状 tunnel 一致；basic auth password 也加遮掩）。
与 #5 的 edited 标志联动（onValueChange 置 true）。

**风险**：低。

---

## 12. 返回回到聊天（#12）

PhoneLayout（MainActivity.kt:146 后）加：
```kotlin
BackHandler(enabled = pagerState.currentPage != screens.indexOf(Screen.Chat)) {
    switchToPage(screens.indexOf(Screen.Chat))
}
```
Chat 页 disabled → 系统退出（预期）。🐞 **横屏/平板**（响应评审）：无 pager，返回直接退出 App——明示为预期（横屏布局无 tab 概念）。

**风险**：低。与 #14 优先级见 §14。

---

## 13. 展开状态重置（#13）🐞 收紧清除时机 + 性能隔离

四类可展开卡（ToolCallsRow:629、ReasoningCard:770、ToolCard:1178、PatchCard:1379）现状 `remember`。切会话已重置（messages=emptyList），但同会话切 tab 再回不重置。

**方案**：展开状态上提到 VM，但用**独立 StateFlow**（不并入 AppState）隔离重组。

🐞 **性能（响应评审"toggle 触发全局重组"）**：MainViewModel 持有 `private val _expandedParts = MutableStateFlow<Map<String,Boolean>>(emptyMap())`，暴露 `val expandedParts: StateFlow<Map<String,Boolean>>` 与 `fun togglePartExpand(key: String)`。**不放入 AppState** → toggle 只通知订阅该 flow 的卡片，不触发整个 ChatScreen 重组。卡片用 `val expanded by expandedParts.collectAsState()` 局部订阅。

🐞 **清除时机收紧（响应评审"loadMessages 清除擦掉用户展开"）**：**仅在 `selectSessionState`（MainViewModelSessionActions.kt:167）清除** `_expandedParts.value = emptyMap()`。`loadMessages`/`loadMoreMessages` 完成时**不清除**（避免擦掉用户并发展开/看历史时的展开）。

🐞 **key 规则（响应评审"key 冲突/ToolCallsRow group"）**：key = `"${messageId}|${partKey}"`（用 `|` 非 `/`）。partKey：单 part 卡（ToolCard/PatchCard/ReasoningCard）= `part.id`；ToolCallsRow（一组 parts）= 首个 part.id。

🐞 **契约（fixer-1 ⇄ fixer-4）**：
- fixer-4：提供 `state.expandedParts`（经 AppState 暴露只读 map，或单独 collectAsState）、`viewModel::togglePartExpand`、selectSessionState 清除。
- fixer-1：`ChatMessageList` 增参 `expandedParts: Map<String,Boolean>`、`onToggleExpand: (String)->Unit`；四类卡 `expanded = expandedParts[key] ?: default`，onClick 调 `onToggleExpand(key)`。
- ChatScreen.kt（fixer-1）接线：从 viewModel 取 expandedParts/togglePartExpand 传入 ChatMessageList。
- default 保留现状（ReasoningCard=isStreaming、ToolCard=isRunning、其余=false）。

**测试**：切会话→全折叠；同会话展开→切 tab→回→保持（因未切会话）；loadMore→已展开保持。

**风险**：中。参数穿透经 MessageRow→PartView→卡（fixer-1 内统一完成，无跨通道）。

---

## 14. 子 session 进入 + 侧滑返回（#14）

现状（exp-9）：SessionsScreen 过滤 child；入口是聊天流 SubAgentCard（:902-948），仅 `part.taskSubSessionId != null`（服务端 metadata.sessionID）可点 → openSubAgent(:760-776) → selectSession(childId)。无导航栈；ChatTopBar:165-189 已有屏幕内返回父箭头。

**方案**：
- 进入：保留 SubAgentCard 路径；增强 affordance（明确按钮态/ripple）。
- 🐞 **openSubAgent child==null 兜底（响应评审）**：现状 :774 child==null 仍调 selectSession → 选不存在会话。改为 child 解析失败时**显示 Toast 提示**（"子任务会话不可用"）而非静默 selectSession。
- 侧滑返回：ChatScreen（fixer-1，比 PhoneLayout 的 #12 BackHandler 更深）加：
  ```kotlin
  val parent = state.currentSession?.parentId
  BackHandler(enabled = parent != null) { parent?.let { viewModel.selectSession(it) } }
  ```

🐞 **优先级协调（响应评审"多 BackHandler 共存"）**：
- HorizontalPager 保持各页 composed。ChatScreen 的 #14 BackHandler 始终注册，但 `enabled = parent != null`。
- 场景：Chat+child → #14 启用（#12 disabled 因 currentPage==Chat）→ 侧滑回父。Chat+root → #14 disabled、#12 disabled → 系统退出。非 Chat → #12 启用 → 回 Chat。
- 边界：用户在某 child 会话时切到 Files 页（罕见），#14 仍 enabled(parent!=null) 会先于 #12 触发→回父会话而非回 Chat。可接受（子会话通常仅 Chat 页内访问）；如需精确，可加 `enabled = parent != null && pagerOnChat`，但 pagerState 不在 ChatScreen 作用域——保持现方案，文档明示此边界。

🐞 **parent 短暂为 null**：用 `remember(parent)` 缓存最近非空 parentId，防侧滑过程中 race。

**风险**：低-中。SubAgentCard 受服务端 metadata 限制需文档说明。

---

## 15. 用户消息右对齐（#15）

`MessageRow`（ChatMessageContent.kt:213）按 isUser 设对齐：
```kotlin
Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
) { ... }
```
用户气泡 `fillMaxWidth(0.8f)`（:683）保持。🐞 **footer padding（响应评审）**：用户消息 footer（:329-336）`padding(start=4.dp)` 改 `padding(end=4.dp)` 以适配右对齐。助手全宽 markdown 不受影响（验证 `ResolvedMarkdownText` 在 End 对齐 Column 内仍全宽）。

**风险**：低。

---

## 16. 折叠钮原位（#16）🐞 触摸目标修正

四类卡 toggle 统一为 IconButton + KeyboardArrowDown(展开)/ChevronRight(折叠)。

🐞 **触摸目标（响应评审"size(24dp) 裁剪 48dp"）**：用 `IconButton`（**不加** `Modifier.size`，保留默认 48dp 最小触摸区），内层 `Icon(Modifier.size(20.dp))`：
```kotlin
IconButton(onClick = { onToggle(key) }) {   // 无 size 约束 → 48dp 触摸区
    Icon(if(expanded) KeyboardArrowDown else ChevronRight, modifier = Modifier.size(20.dp))
}
```
🐞 **Row.clickable 处理（响应评审）**：ToolCallsRow(:637)/PatchCard(:1393) 现状整行 clickable。改为**仅 IconButton 可点**（移除 Row.clickable），单一明确钮位。此为行为变更（整行不再可点），但 IconButton 在 header 右端固定位，满足"展开钮原位、立刻折叠"。文档明示。
- 保证展开前后钮 x 坐标恒定（header Row 结构不变）。

🐞 **与 #13 同通道**：toggle 的状态来源（#13 读 expandedParts）与形态（#16 IconButton）同在 fixer-1 的 ChatMessageContent.kt，同一 commit 完成，避免中间态。

**风险**：低。

---

## 实施顺序

1. fixer-4 先行（提供 #5 saveHostProfile 新签名、#13 VM 态/selectSession 清除、#10 状态机）——其契约为其它通道依赖。
2. fixer-1（依赖 fixer-4 的 expandedParts/togglePartExpand 契约）、designer、fixer-2、fixer-3 并行。
3. 合并后构建 + 单测 + 手测（模拟器，禁真机）。
4. #10 交 oracle 复审。

---

## 测试计划

- 单测：#4(Compose UI 滚动触发)、#5(密码保持/清除/tunnel 跳过)、#10b(merge 保留/不弹回/loadMore 不弹回/SSE 并发)、#13(切会话清除/loadMore 保持)。
- 构建：`./gradlew assembleDebug` + `./gradlew testDebugUnitTest` 全绿。
- 手测（模拟器）：横屏分屏、返回键、侧滑返回、历史滚动连续加载、密码保存往返、连接新项目见已有会话、发消息不弹回、用户消息右对齐、折叠钮原位、切会话折叠重置。

---

## 评审重点（v2 请 glmer/dser 复核）

1. 🐞 #4 新方案（nearTop derivedStateOf + 参数 re-key LaunchedEffect）是否彻底消除闭包捕获；连续加载/去重是否安全。
2. 🐞 #10 两处 auto-select guard + merge 新签名 + directorySessions 独立存储，是否覆盖 SSE/loadMore/all currentSessionId 写入路径。
3. 🐞 #5 三态 edited 契约（含 tunnel 完全跳过）无歧义。
4. 🐞 #3 inset 统一规则（仅 TopAppBar 一处）与"实测回退"是否可接受；title 槽 widthIn/固定 width 方案。
5. 🐞 #13 独立 StateFlow + 仅 selectSessionState 清除，性能与竞态是否解决；key 规则。
6. 🐞 #16 IconButton 不加 size 保留 48dp 触摸区；移除 Row.clickable 的行为变更是否可接受。
7. 通道文件归属是否仍有写冲突；#5/#13/#14 跨通道契约是否完备。
