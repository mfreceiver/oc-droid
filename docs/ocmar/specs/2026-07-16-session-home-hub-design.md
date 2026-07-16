# Session 主页化枢纽改造 设计 (ocdroid)

> 状态：**spec（实现契约 + 评审依据）**。
> 范围：彻底移除底部切换栏；`Sessions` 成为应用主页（startDestination）；找回 0.7 服务器连接图标弹窗；project 页并入主页（两可折叠 section）；git/files 经 project 行进入且标题显示当前 project；手机/平板响应式 Chat 顶左（返回 / 汉堡抽屉）；Chat 新 tab 改从右侧加入。
> 依据：brainstorming 七决策 + §3 开放点（用户已确认：平板抽屉含 Home 入口、系统返回始终回主页）。
> 与 `2026-07-13-nav-surfaces-redesign-design.md` 衔接：后者已把 project list 落到 `FilesScreen.ProjectListPane`，本 spec 将该 list **迁入主页**，Files 退化为纯文件浏览器。

---

## 决策汇总（brainstorming 锁定）

| # | 决策 |
|---|---|
| D1 | 导航模型 = hub-and-spoke：选中/新建 session 从主页导航进独立 `Chat` 目的地；`startDestination` Chat→Sessions；手机 Chat 返回键回主页 |
| D2 | 宽窄屏冷启动**都先停在 Sessions 主页**（不再 lastRoute 恢复 Chat；通知/deeplink 仍可程序化跳转） |
| D3 | 手机/平板阈值 = **600dp**（`LocalWindowSizeClass`：Compact<600=手机 / Medium≥600=平板） |
| D4 | attached-project 行操作 = `[files][git][新建session]` + 展开看该 project 下 sessions（显示逻辑同原 project） |
| D5 | 两 section 折叠态 = **仅会话内** `rememberSaveable`；重启重置为**两 section 全展开** |
| D6 | 服务器弹窗 = 复用并扩展 `ServerManagementDialog`，新增「打开设置」按钮；**设置仅从此弹窗进入** |
| D7 | 流量 = 沿用 `TrafficTracker` 累计 ↑/↓ 计数（不做时间序列历史） |
| D8 | §3 平板从 Chat 回主页 = 抽屉 header 放 Home 入口 + 系统返回始终 pop 回主页（不破坏"设置仅从弹窗进入"） |

---

## 改动 1：移除底部栏 + 主页化为枢纽（NavHost）

- **删除** `CompactBottomBar` 及配套 `NavIcon`（`AppShell.kt:459`）、`routeLabel`（`AppShell.kt:472`）、Sessions attention-dot（`AppShell.kt:420-441`）、`BottomBarImeGeometry` 的 bottomBar 折叠（Scaffold 不再挂 `bottomBar`；IME padding 改为直接 `Modifier.imePadding()` 收尾）。
- `NavHost.startDestination` = `NavRoute.Sessions.route`（`AppShell.kt:188`）。
- `navigateTopLevel` / 同 tab 重选（`AppShell.kt:128-134, 177`）：底部栏消失后**移除**触发点；`reselectFlow` 基础设施保留（Files/Git 内部 reset 不再被 tab-tap 驱动，见改动 3/4 的 back 语义重写）。
- 冷启动：`OrchestratorViewModel.lastRoute`（`OrchestratorViewModel.kt:87-92`）**不再**用于冷启动恢复进 Chat；`AppShell.kt:137-139` 的 `requestedRoute` `LaunchedEffect` 改为：仅当是 deeplink/通知显式请求时才跳转，否则守在 Sessions。
- 顶层 `BackHandler`（`AppShell.kt:353-355`）重写为 hub 语义：Chat/Files/Git/Settings 非 root 时 pop 回主页；主页(root) 不拦截（交系统）。
- `NavRoute`（`NavRoute.kt:7`）：`topLevel` 概念随底部栏一并删除（保留 enum 本体与 `filesRoute/gitRoute` 工厂）。

## 改动 2：主页（SessionsScreen 重写为「两 section 主页」）

> 新建/重写 `SessionsScreen` 为主体；复用既有数据源与逻辑，重组结构。

- **顶栏**：`TopAppBar`，title 固定 `"<appName> v <versionName>"`（versionName 取值复用 `ChatTopBar.kt:275-281` 的 `PackageManager.getPackageInfo(...).versionName` + `remember(context)`）。右上角 = **服务器图标**（见改动 5）。无返回键（根页）。
- **§2a Recently Session**：
  - 列表逻辑同现 `SessionsScreen`（`sessions + directorySessions` 去重，过滤 `parentId==null && !isArchived`，按 `time.updated` 倒序）。
  - header 用 `AppSectionHeader`（`ui/theme/AppSectionHeader.kt`），`trailing` = **新建 session 按钮**（复用现 `SessionsScreen.kt:269-277,411-430`：1 workdir 直建、≥2 弹 `AppBottomSheet` workdir picker、0 禁用+提示）。
  - 点击 session 行 / 新建成功 → 导航进 `Chat`。
- **§2b Attached Project**：
  - 列表逻辑 = 现 `FilesScreen.ProjectListPane` 的 `buildWorkdirGroups`（`SessionsScreen.kt:898` / `FilesScreen.kt:266-281`）；空项目/全归档项目保留占位（workdir 第一类实体，仅显式 disconnect 移除）。
  - header `trailing` = **attach 按钮**（`CreateNewFolder` → `DirectoryPickerSheet` → `settingsVM.connectWorkdir(path)`，attach 后留在主页，`FilesScreen.kt:509-515,461-474`）。
  - 每行 trailing = `[files] [git] [新建session]` 三个 `IconButton`（files=`FolderOpen`、git=`AccountTree`、新建=`AddComment`）+ 展开箭头看该 project 下 sessions（沿用现 `WorkdirRow` 展开逻辑，`FilesScreen.kt:618-665`）。长按 workdir header → disconnect 确认（`AppConfirmDialog`）。
- **折叠态**：两个 section 各自 `var expanded by rememberSaveable { mutableStateOf(true) }`（D5）；header 整行可点切换。
- 遵循 `docs/ui-style-spec.md`：`AppSectionHeader`+trailing、`ListItem`、`Dimens`（禁散落 dp）、picker/confirm 用共享原语。

## 改动 3：Chat 页响应式 + 新 tab 从右加入

### 3a 顶左响应式分支（`LocalWindowSizeClass`）
- `ChatTopBar`（`ChatTopBar.kt:245`）的 `navigationIcon` 槽按宽度分支（D3）：
  - **手机（<600dp）**：返回箭头 `IconButton(AutoMirrored.Filled.ArrowBack)` → pop 回主页（等效边缘侧滑）。
  - **平板（≥600dp）**：汉堡键 `IconButton(Filled.Menu)` → 展开**左侧抽屉**。
- **抽屉**（`ModalNavigationDrawer`，`drawerState`）：
  - 内容 = **主页 §2a 的 Recently Session 列表**（同一数据源/渲染）；点行 = `sessionVM.selectSession`（切 session，不离开 Chat）。
  - header 放 **Home 入口**（图标按钮 → `orchestratorVM.setLastRoute(Sessions)` 导航回主页，D8），使平板在 Chat 内可达主页 → 设置弹窗。
  - 再次点汉堡键 → 收起抽屉。
- **系统返回**：两种形态下系统 back/边缘侧滑均 pop 回主页（D8）。`ChatScaffold` 现有 root-session「双击退出」`BackHandler`（`ChatScaffold.kt:365-373`）需调整为：root-session 时返回主页而非「再按退出」。

### 3b 新 tab 从右加入（D 范围外，本次修正）
- `SessionSwitcher.switchTo`（`SessionSwitcher.kt:565-568`）：prepend → **append**：
  - 旧：`(listOf(sessionId) + openSessionIds).take(8)`
  - 新：**先移除已存在项**（防重复）再 append，取最近 8 且新加入在**右侧**：
    `(openSessionIds.filterNot { it == sessionId } + sessionId).takeLast(8)`
- `closeSession`（`SessionViewModel.kt:203-229`）：移除 id 后选中由 `firstOrNull()` → **`lastOrNull()`**（保留"关闭后落在相邻 tab"的直觉，方向与右侧加入一致）。
- 同步修正其它 prepend 点：`AppCoreOrchestration.kt:304-305`、`SessionListActions.kt:179`（deeplink/启动路径），统一改为 append + `takeLast(8)`。
- `SessionTabStrip`（`ChatSessionTabStrip.kt:129`）渲染顺序不变（数据源即 `openSessions`，顺序变了即体现在右侧）。

### 3c 保留
- 标题点击弹近期 session（`SessionPickerSheet`，`ChatScaffold.kt:727`）不变。
- Chat 顶栏 overflow（ContextUsageRing + Context/Todo/Agent/Model/Force-Refresh，`ChatTopBar.kt:421-452,616-629`）保留。

## 改动 4：Git / Files 经 project 行进入 + 标题显示 project

- **Files**：`files?workdir={encoded}` 路由已存在；`FilesScreen` 退化为**纯文件浏览器**（移除 `ProjectListPane` 第一级，list 已迁入主页 §2b）。进入即定位到该 workdir 的 `FileBrowserPane`。`FilesScreen.kt:152` 的 `browseWorkdir` 两级状态简化为「恒等于路由 workdir」。
- **Git**：新增 `git?workdir={encoded}` 路由变体（现有 `git?session={id}` 保留，用于 Chat "Open Git Changes" affordance）。`GitScreen` 的 `effectiveWorkdir`（`GitScreen.kt:72-78`）解析优先级 = **路由 workdir 参数** > 路由 session 参数 > fallback。
- **标题统一为「原 git 显示模式」**（D4/需求 4）：
  - Files 文件浏览器 title = `Text("Files") + Spacer + WorkdirControl(readOnly=true)`（显示 workdir basename）。
  - Git title 沿用现式（`GitScreen.kt:123-153`）= `Text("Git") + WorkdirControl(readOnly=true)`。
- 两页均无底部栏；返回 → 主页。

## 改动 5：服务器连接图标弹窗（需求 2，复用 0.7）

- 主页右上角 `IconButton(Icons.Default.Dns)` + `BadgedBox` 圆点（恢复 `tag 0.7.6` `ChatTopBar.kt:394-442` 行为）：
  - `state.isConnected` → 绿（`SemanticColors.stateSuccessFg()`）
  - `state.isConnecting` → 蓝（`stateInfoFg`）
  - `connectionPhase is Idle` → 无点
  - else → 红（`colorScheme.error`）
- 点击 → 复用并扩展 `ServerManagementDialog`（`ChatServerManagementDialog.kt:44`）：
  - 保留 host 列表 + 累计流量 ↑/↓（`TrafficTracker`，`ChatServerManagementDialog.kt:144-153`）+ 强制刷新 + Tunnel。
  - **新增「打开设置」按钮**（footer 或 host 区底部）→ `orchestratorVM.setLastRoute(Settings)`（D6：设置仅此入口）。
- 仅主页此一处入口；Chat 顶栏**不**复活服务器图标（其 Force-Refresh 保留在 overflow）。

## 字符串（新增，`strings.xml`）

- `home_section_recent`（Recently Session header）/ `home_section_projects`（Attached Project header）
- `project_action_files` / `project_action_git` / `project_action_new_session`（project 行三按钮 contentDescription）
- `server_dialog_open_settings`（弹窗「打开设置」）
- `chat_back_to_home`（手机返回键 contentDescription）/ `chat_open_sessions_drawer`（平板汉堡键）
- 主页标题 = 代码拼 `"$appName v$versionName"`（复用 `ChatTopBar.kt:275-281` 模式，不新增字符串）

## 不在范围

- SessionPickerSheet 内部 take(10) / 搜索 / 归档逻辑（保持）。
- badge/sub-agent 不一致（已知，维持现状）。
- 流量时间序列历史（D7 明确不做）。
- 常驻双栏 / 平板永久左侧栏（D3 排除，需求 5 要求可收起抽屉）。
- 版本号体系（仍 git 派生，无 bump）。

## 风险 / 注意

- **startDestination 变更影响面大**：多处流程假设 Chat 为 base（deeplink prepend、double-tap-exit、lastRoute 恢复）。需逐个验证 deeplink/通知跳转仍能进 Chat；ChatScaffold「再按退出」语义需重写。
- **`reselectFlow` 触发点消失**：底部栏删除后同 tab 重选不再发生；Files/Git 的 reset-on-reselect 失去触发——改为「进入即 root + 返回回主页」后不再依赖 reselect（保留基础设施以免动其它消费者）。
- **抽屉与 ModalNavigationDrawer 的 IME/返回键冲突**：抽屉打开时返回应先收抽屉（`BackHandler` 优先级）。
- **Git 路由双形态**（session vs workdir）：`GitScreen` workdir 解析需覆盖两种入参，避免回退到 active-session 耦合。

## 验证

- `./scripts/check.sh`（compile + 单测）必过；改动较大建议 `--full`（+lint+覆盖）。
- 单测：`SessionSwitcher.switchTo`/`closeSession` 顺序（右加入/右优先选中）、Git/Files workdir 路由解析、home section 折叠态 saveable。
- 模拟器（**仅模拟器**，遵守 AGENTS.md 设备安全）：手机宽(<600dp)验返回键、平板宽(≥600dp)验汉堡抽屉展开/收起/切 session/回主页；主页两 section 折叠、project 行三按钮、服务器弹窗+设置入口。
- 评审门（阶段 6）：独立 verifier live rerun + final code-reviewer。

## ocmar 参数（预览，阶段 4 正式固定）

- `slug` = `home-hub`；`owner` = `ocmar-home-hub`；`base` = `git rev-parse HEAD`（执行前取）；`verifier` = `./scripts/check.sh`（+ `--full` 视情况）。
