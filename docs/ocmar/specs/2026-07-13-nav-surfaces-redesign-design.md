# 导航三项改造 设计 (ocdroid)

> 状态：**spec（实现契约 + 评审依据）**。与 `2026-07-13-notification-background-fgs-design.md` 完全独立、无代码重叠。
> 依据：grilling 四决策 + workdir 独立性修订。两评审均评导航「风险低」。
> 范围：①Sessions 扁平全量 ②Files 项目页 ③Chat 顶部 0.7 标签恢复。

---

## 改动 1：Sessions = 所有根会话扁平全量

- `SessionsScreen.recentSessions`：**去掉 `.take(5)`**；源仍为 `sessions + directorySessions` 去重，过滤 `parentId==null && !isArchived`，按 `time.updated` 倒序。
- `SessionListActions.launchLoadSessions`：初始全局拉取改为**全量**（大 limit，如 `SESSION_FULL_LOAD_LIMIT`，使 `hasMoreSessions=false`）；**不**加 load-more 触发器。`launchLoadMoreSessions` 基础设施保留（不删除，避免动其它消费者），仅 Sessions 页不再驱动。
- **删除** SessionsScreen 的「已连接的项目」分组区块（`workdirGroups` 渲染 + 相关 header/empty/disconnect dialog）——整体迁往 Files（改动 2）。
- **新增** new-session FAB（复用 `SessionPickerSheet.onNewSession` 语义：`sessionVM.createSession()` → `onSwitchToChat()`）。
- 保留：archive 长按确认 dialog（recent 列表项需要）。

## 改动 2：Files = 项目列表 + 文件浏览（workdir 完全独立于会话）

> 原则：项目（workdir）是第一类实体，独立于会话。空项目 / 全归档项目不消失；仅显式 disconnect 移除。`buildWorkdirGroups` 已用 `recentWorkdirs` 闸门且对 0 会话项目输出占位——直接复用。

- `FilesScreen` 改**两级内部状态** `browseWorkdir: String?`：
  - `null`（首屏）= **移植自 SessionsScreen 的「已连接的项目」区块**：`buildWorkdirGroups` 渲染 workdir 行（展开看会话 / 新建会话 `createSessionInWorkdir` / 长按断开 `disconnectWorkdir` / draft 徽标），**每行新增「文件」按钮**（FolderOpen → 设 `browseWorkdir = workdir`）；TopAppBar 右上角「添加新项目」（CreateNewFolder → DirectoryPickerSheet → `settingsVM.connectWorkdir(path)`）。
  - 非 `null` = 该 workdir 的文件浏览器（复用现有 `FileBrowserPane` + 预览链路）；workdir = **用户选定值**（不再跟随 active session）。
- BackHandler 两级：浏览中→回项目列表；项目列表→`onExit`（回 Chat，沿用现有）。
- `reselectFlow`（Files tab 重选）→ 回项目列表根（`browseWorkdir = null` + 关预览）。
- **新增 `SettingsViewModel.connectWorkdir(path)`**：仅 `addRecentWorkdir` + bump tick + 可选 prefetch；**不**建会话、**不**改 currentWorkdir、**不**清 chat/draft、**不**抢 active session。（与现有 `createSessionInWorkdir` 的 draft 劫持行为分离。）
- FilesScreen workdir 来源改为 `browseWorkdir`；移除 `effectiveWorkdir = sessions.first{active}?.directory` 的 active-session 耦合。

## 改动 3：Chat 顶部恢复 0.7 SessionTabStrip（与 SessionPickerSheet 并存）

- **新建 `ChatSessionTabStrip.kt`**（从 v0.7.6 恢复 `SessionTabStrip` + `SessionTab` composable）：PrimaryScrollableTabRow（溢出横滚）/ FitWeighted（铺满），每 tab = 标题 + 未读点 + 待问「?」+ 关闭 X + workdir 底色 + 选中竖条/Bold。**复用现存纯函数** `resolveEffectiveSelectedId` / `resolveSessionTabLayout` / `SessionTabLayout` / `truncateTitle`（`SessionPickerHelpers.kt`）。
- 数据源 = **`openSessions`**（从 `openSessionIds` 解析的根会话，仅 root、非归档；openSessionIds 已只含 root、上限 8）——忠实 0.7「打开的标签」语义、有界。
- `ChatScaffold`：在 `ChatTopBar` 之后插入 `SessionTabStrip` 第二行；`topBarState` 填充 `openSessions`；`topBarActions` 接 `onCloseSession = sessionVM::closeSession`（关 tab，非归档）。
- **并存**：标题点击仍开 `SessionPickerSheet`（全部/搜索/归档），不动。

## 字符串（新增）

- `sessions_new_session_fab`（Sessions new-session FAB）
- `files_tab_projects_title` / `files_project_browse`（每项目文件按钮）/ `files_add_project`（右上添加项目）

## 不在范围

- badge/sub-agent 不一致（维持现状，已知问题，见 FGS spec）。
- Settings「完成提醒」文案修复（属 FGS spec）。
- SessionPickerSheet 内部 take(10)（保持，非本次）。

## 验证

- `./scripts/check.sh`（compile + 单测）必过。
- 评审门：gpter + groker 对**实现**评 9.5。
