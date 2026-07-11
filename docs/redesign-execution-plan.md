# ocdroid 移动端重构 — 执行计划与阶段演进步骤

> 状态：v2（gpter 首轮 FAIL → 已纳入全部必修项 → 待复审）。
> 编制日期：2026-07-11。基于 v0.7.1；行号引用基于当前 main，实施前需 diff 确认。
> 前置文档（本计划依据，新会话必须先读）：
> - `docs/redesign-mobile-ux-architecture.md` —— 评审报告
> - `docs/redesign-mobile-compose-scheme.md` —— Compose (M3) 设计方案（组件 B / 骨架 D / 交互 E / 状态 F / 分阶段 G）
> - 本计划与源文档的有意偏差见 §12。

---

## 0. 执行原则（不可违反）

1. **增量可交付**：每个（子）阶段独立可 ship，app 全程可运行；入口单调迁移、不堆积、不断路。
2. **每（子）阶段必过 `./scripts/check.sh`**（编译+单测）才算完成；LSP 已关，check.sh 是唯一编译反馈。
3. **review gate 与模拟器回归是 Phase 1-3 的强制门**（非可选）。按 `.opencode/policies/review-gate.md`，产物归档 `.opencode/runs/reviews/<date>/`。破坏性操作（revert）单设数据正确性 gate。
4. **不动服务端协议**：文件引用走方案 A（`PartInput(type=text)` + `File: <path>`）。
5. **以 UI 层为主；state/domain/cache 改动为受控例外**：Phase 0-3 主体是 UI + 最小状态新增（方案 F）；仅下列受控例外允许动 state/domain/cache，须在对应阶段显式声明范围：
   - Phase 0：A3-1 revert cutoff 元数据（domain/cache 持久化 + selector 语义）——见 §2.2。
   - Phase 1A：`NavRoute` + 迁移适配（少量 VM/持久化）——见 §3.1。
   - Phase 2：`WorkspaceState`（selected diff file）——见 §4.2。
   - 不重构 controller/repository/state 架构（Phase 4，单独排期）。
6. **parity 契约**：重接 UI 时不得重新实现 SSE/流式/coalesce/gap/paging/scroll-anchoring/draft 行为；ChatScaffold 必须订阅现有同一 slice，行为等价（见 §3.4 checklist）。
7. **硬前置守卫**：见 §7 矩阵。
8. **模拟器纪律**（AGENTS.md）：UI 自检/插桩只用模拟器；用前 `status` 确认未运行再 `start`，用完 `stop`。
9. **版本号**：发版走 `./scripts/release.sh`，禁手改 `app/build.gradle.kts`。
10. **每（子）阶段一个新会话**：完成后 gate 通过再开下一会话。

---

## 1. 阶段总览

| Phase | 目标 | 对应方案 | 前置 | 门 |
|---|---|---|---|---|
| **0 紧急修复 + revert 数据正确性** | 修 A3-1 leak（domain/cache）、Files semantics、48dp、去版本号 | 报告 §7.0 + §8.3 | 无 | check.sh + revert 单测 + 模拟器 |
| **1A 壳迁移（opt-in）** | NavRoute + AppShell + 4 目的地 + 保留 Files overlay + deep-link/back adapter | G.0 + G.5 | Phase 0 | check.sh + 模拟器回归 + review(gpter+oracle) |
| **1B 聊天 chrome + composer** | 新 TopAppBar/上下文 chip、移除标签条+Pager、Add 菜单、Agent/Model chip | G.1 步 1-4,8 | 1A | check.sh + parity checklist + review |
| **1C 状态槽 + revert/fork 动作** | 单一状态槽、权限会话过滤、消息溢出菜单（Copy/Edit/Fork/Revert+确认） | G.1 步 5-7 | 1B + Phase 0(A3-1) | check.sh + revert 破坏性 gate + review |
| **2 搜索 + 上下文 + Workspace** | 搜索、ContextSelectorSheet、Workspace(Files\|Changes)、diff 移出时间线、VCS 迁出、删旧 overlay | G.2 | 1C | check.sh + host/session 隔离验证 + review |
| **3 Settings 清理 + 导航打磨 + 收壳** | Settings 子路由、predictive back（依赖升级）、48dp 终审、删旧 PhoneLayout、新壳默认开 | G.3 + G.5 收尾 | 2 | check.sh + 终审 + `release.sh minor` |
| **4 架构治理（延后，单独排期）** | controller/state/repository 边界与事务化 | 报告 §6 | 3 | — |

Phase 1 拆 1A/1B/1C，每个独立 gate、独立会话、各自保持编译且不破坏现有入口。

---

## 2. Phase 0 — 紧急修复 + revert 数据正确性

### 2.1 任务清单
1. **Files semantics 注释/代码不符**（`FilesScreen.kt:78-85`）：按意图改 `clearAndSetSemantics` 或修正注释；TalkBack 验证。
2. **48dp 触控已知违规点**：`ThinkingCapsule.kt:109-129`(28dp 停止)、`ChatMessageNavFab.kt:58-74`(40dp FAB) → ≥48dp。（`ChatSessionTabStrip` 36dp/24dp 不改——Phase 1B 整条移除。）
3. **顶栏去版本号 + 未连接去红点角标**（observer S0）。
4. **A3-1 revert cutoff 数据正确性**（见 §2.2，独立子计划）。

### 2.2 A3-1 子计划（domain/cache/state 受控例外）
**问题**：`AppStateDerived.kt:111-129` `filterBeforeRevert` 当 `revertMessageId` 不在当前分页窗口时**直接返回全部消息** → 泄漏回退后消息。无时间戳消息仍被保守放行。

**根因**：cutoff 仅依赖「当前已加载消息列表里查找 revert 消息的时间戳」，未持久化、冷启动/hydration/分页时不可解析即放行。

**修复设计**（须明确 owner / 持久化 / fetch / 失效 / 无时间戳语义）：
- **owner**：新增 domain 字段 `RevertCutoff(sessionId, messageId, createdAtEpochMs?, state)`。`state ∈ {Resolved(createdAt), PendingFetch, NoTimestamp}`。
- **解析与持久化**：当 `Session.revert.messageId` 变化时，在已加载消息中查其 `time.created`；找到 → `Resolved`。找不到 → 标 `PendingFetch`，触发一次性 `GET /session/{id}/message` 定向取该消息（或含该消息的分页页）解析 `time.created`，落 `Resolved`。无 `time.created` → `NoTimestamp`，回退 index 语义（且仅作用于已加载前缀，**不**放行整窗）。
- **持久化**：随 session cache 持久化 `createdAtEpochMs`（仿 `SessionCacheEntry`），冷启动先以缓存值渲染截断，再用 fetch 校正。键 = sessionId，随 `Session.revert` 变更失效。
- **selector 语义（fail-closed，强制）**：`filterBeforeRevert` 改为读 `RevertCutoff`：
  - **一致性**：selector 仅接受 `cutoff.messageId == Session.revert.messageId` 的 cutoff；`Session.revert.messageId` 变化时，**在同一次状态提交中**使旧 cutoff 失效（原子失效，不跨帧），杜绝新旧 cutoff 竞态。
  - `Resolved(t)`：保留 `created < t`。
  - `PendingFetch`：显示「正在加载回退点…」截断指示 + 允许重试，**绝不放行**整窗。
  - `NoTimestamp`：按已知前缀 index 截断；**无法判定位置的无时间戳消息一律排除**（安全侧倾斜）。
  - **永久 fetch 失败**：保持 unavailable/截断 + 可重试入口，**永不回退为显示全窗**。
- **`RevertConversation` use case**（报告 §8.3 前置）：封装 revert + reload + composer 草稿恢复 + 成功/失败/取消 outcome；流式进行中禁止 revert（明确策略）；重复点击/重连去重。

**改动文件**：`AppStateDerived.kt`、`Session.kt`(`RevertCutoff` 加 domain model 或旁路 cache)、`SessionSyncCoordinator.kt`(revert 事件 → 写 cutoff)、新增 `RevertConversation` use case（挂在 `AppCoreOrchestration`/`ChatViewModel` 之间，最小）、cache 持久化点。

**测试**（破坏性 gate 必备）：单测覆盖 cutoff 在窗口内/不在窗口内/无时间戳/PendingFetch/NoTimestamp/冷启动缓存恢复/分页后/`Session.revert` 变更失效；集成测 revert 成功/失败/取消/重复点击/重连/流式中拦截。

### 2.3 验证门
- `./scripts/check.sh`（含新增 revert 单测/集成测）
- 模拟器回归：revert 三态 + 冷启动 + 分页
- 此阶段不改 UI 入口（revert 暴露在 1C），但数据正确性必须先稳。

### 2.4 风险/回退
- 风险：cutoff 持久化与现有 revert 成功路径（`ChatViewModel.editFromMessage:165-198`）交互；须保证成功路径行为不变。NoTimestamp 的「保守排除」可能隐藏少量本应显示的无时间戳消息——可接受（安全侧倾斜）。
- 回退：单提交 git revert。

---

## 3. Phase 1 — 壳迁移 + 聊天重构（拆 1A/1B/1C）

### 3.1 Phase 1A — 壳迁移（opt-in，G.0 + G.5）

**目标**：底部 NavigationBar 出现；4 目的地路由；旧壳经 flag 并存；Files overlay **不断路**。

**依赖前置（必修，独立提交）**：
- 版本目录 `gradle/libs.versions.toml`：新增 `material3-adaptive-navigation-suite` artifact（`NavigationSuiteScaffold` 在此，非 material3 主 artifact）。验证与 Compose BOM 2025.12.00 兼容。
- 新增 `NavRoute` enum（方案 F.1）+ `setLastRoute`。**持久化用稳定 route key 字符串，禁用 `.ordinal`**（见 §12 偏差）。旧 int → 新 key 显式迁移表（旧 0/1/2 → chat/sessions/settings；旧 2 不映射到 workspace）。
- `useNewShell` 由 Gradle 生成 `BuildConfig.USE_NEW_SHELL`（非 `local.properties` 直接读）。**G.0-G.1 opt-in（默认 false），Phase 2 完成入口迁移后 Phase 3 默认 true**（对齐 scheme G.5，纠正首轮计划「默认开」）。

**任务**：
1. `AppShell`（方案 D.1）：`NavigationSuiteScaffold` + `NavHost`，4 目的地（Workspace 暂 stub）。
2. **保留 `fileBrowserOpen` overlay**：`AppShell` 内继续渲染现有 overlay（`MainActivity.kt:392-415` 的渲染逻辑迁移到 AppShell，行为不变）。overlay 删除推迟到 Phase 2（所有 Files 入口迁入 Workspace 后）。
3. **navigation/deep-link adapter**：显式映射 Sessions→Chat、Settings back、Files origin route、通知 deep-link（通知点选 → 导航到 Chat 并选中 session）到新 `NavController`。
4. `BackHandler` 优先级审计：新 `NavHost` 与 `MainActivity`/Chat/Files 现有分层 `BackHandler` 的冲突排查，定义优先级（见 §10 表）。
5. `hiltViewModel()` 作用域确认：screen-scoped VM 在不同 `NavBackStackEntry` 下实例化策略，确保 Activity 范围任务/状态生命周期不被打碎。

**改动文件**：`gradle/libs.versions.toml`、`app/build.gradle.kts`(BuildConfig flag)、新增 `ui/NavRoute.kt`+`ui/shell/AppShell.kt`+`ui/NavState.kt`(route key 持久化)、`MainActivity.kt`(setContent 分支 + overlay 迁移)、`OrchestratorViewModel.kt:57-65`(setLastRoute + 迁移表)、`SettingsManager.kt`(持久化 last route key + 旧 int 迁移)。

**验证门**：check.sh + 模拟器回归矩阵（§8）+ review(gpter+oracle：导航/back/deep-link/overlay 未断)。

### 3.2 Phase 1B — 聊天 chrome + composer（G.1 步 1-4,8）

**任务**：
1. 新 `TopAppBar` + session-history 图标 + 上下文 chip（D.2/D.5），替换 `ChatTopBar` 第二行 `SessionTabStrip`。session-history 开 `ModalBottomSheet`(D.4) 先做 Recent+By-workdir（无 Search）。（P5-3）
2. 移除 `ChatScreen.kt:399-414,615-655` 的 `HorizontalPager`，会话切换走 sheet。（P5-3）
3. `+` 改 `ModalBottomSheet` Add 菜单（D.3），先只做 Photos。（P4-2 部分）
4. 输入行上方加 Agent/Model `AssistChip`（D.3），点开 `ModalBottomSheet`（复用现有对话框内容，无 Search）。（P4-1）
5. 会话归档长按 → sheet 行溢出菜单。（P4-4）

**parity checklist（强制，禁止改 SessionSyncCoordinator 行为）**：流式 coalesce、gap/paging、scroll anchoring、streaming overlay、draft lifecycle、未读清理、metadata marker 注入——逐项在 ChatScaffold 订阅同一 slice 后验证等价。

**改动文件**：新增 `ui/chat/ChatScaffold.kt`、`ui/chat/Composer.kt`(新)、`ui/chat/SessionPickerSheet.kt`、改 `ChatScreen.kt`(内部换 ChatScaffold，保留 VM 接线)、`ChatTopBar.kt`(新 TopAppBar)。状态新增 `ComposerState.fileReferences`(additive)。

**验证门**：check.sh + parity checklist 逐项签字 + 模拟器流式/分页/旋转回归 + review。

### 3.3 Phase 1C — 状态槽 + revert/fork 动作（G.1 步 5-7）

**任务**：
1. 单一状态槽（C.3/D.2.1）：Permission/Question/Running/Connecting 四选一优先级渲染；SessionRetryCard、Compacting 胶囊、connecting 胶囊、ThinkingCapsule、QuestionCardView 全部汇流。（P5-6）
2. 状态槽输入按 `chat.currentSessionId` 过滤 pending permissions。（P5-7）
3. 消息行溢出菜单（D.6）：Copy / Edit & rerun / Fork / Revert + 确认框（影响说明、不可误触）。调用 `RevertConversation` use case（Phase 0）+ `forkSession`。（P5-5）

**改动文件**：新增 `ui/chat/StatusSlot.kt`、`ui/chat/MessageCard.kt`；接 `RevertConversation` use case。

**验证门（revert 破坏性 gate，强制）**：check.sh + revert 数据正确性 + 破坏性场景（成功/失败/取消/重复/重连/流式中拦截/确认框误触）+ review(gpter+oracle)。

### 3.4 Phase 1 风险/回退
- 主要风险：ChatScaffold 重接丢流式/coalesce；deep-link/back 冲突；hiltVM 作用域；Modal sheet 并发/旋转/进程恢复布尔冲突。
- 回退：`BuildConfig.USE_NEW_SHELL=false` 回旧壳（1A/1B/1C 期并存）。

---

## 4. Phase 2 — 搜索 + 上下文 + Workspace（G.2）

**任务**：
1. Session Picker + Agent/Model sheet 加 M3 `SearchBar`。（P3-1）
2. Context Selector Sheet（D.5）替换 DNS 图标 + 埋藏 workdir 流程；切 host/workdir 显式重置/恢复 scope，**跨 host/session diff 隔离**。（P5-2）
3. Workspace 目的地（D.7）：`PrimaryTabRow` Files | Changes。Files 指向现有 `FilesScreen`（迁路由）。
4. Changes 标签（D.7b）：变更文件列表 + `ModalBottomSheet` 统一 diff（紧凑非并排）+ typed deep-link builder（`workspace/changes?session=<id>`，禁手拼）。（P5-4 部分）
5. 消息「N files changed」深链：`ChatMessageContent.kt:596-606` `SessionDiffCard` → 单行 `ListItem` 导航 Changes；详情移出时间线。
6. VCS 段移出 Settings（`SettingsScreen.kt:307-322,379-557`）→ Workspace Changes；确认无 Settings 专属依赖残留。
7. **删 `fileBrowserOpen` overlay**（入口已全迁 Workspace）。

**改动文件（file-level allowlist）**：
- 新增：`ui/workspace/WorkspaceScaffold.kt`、`ui/workspace/FilesPane.kt`(迁)、`ui/workspace/ChangesPane.kt`、`ui/chat/ContextSelectorSheet.kt`、`ui/chat/ChatTopBarRedesign.kt`(chip)
- 修改：`ChatScreen.kt`/`ChatMessageContent.kt`(diff 深链)、`SessionsScreen.kt`(sheet 入口)、`MainActivity.kt`(删 overlay 路径)、`SettingsScreen.kt`/`SettingsSections.kt`(删 VCS 段)
- 状态新增：`WorkspaceState`(selected diff file)

**route schema（明确）**：`chat`、`sessions`、`workspace`、`workspace/files`、`workspace/changes?session=<id>`、`settings`；所有 deep-link 用 typed builder。

**host/session 隔离规则**：Workspace 显示的 diff 限定当前 workdir + 选中 session；切 host 清空 Workspace state；stale diff 不跨 host 显示。

**验证门**：check.sh + host/session 隔离验证 + overlay 已无活跃入口确认 + 模拟器(搜索/上下文/Workspace/深链/旋转/进程恢复) + review。

**风险/回退**：A5-1 data→ui 环使 Changes 复用 diff/UI model 时扩大反向依赖——**Phase 2 复用 `FileDiff`(domain model，非 ui 类型)**，不新增 data→ui 耦合；真正的环拆解留 Phase 4。回退=单阶段 git revert。

---

## 5. Phase 3 — Settings 清理 + 导航打磨 + 收壳（G.3 + G.5 收尾）

**任务**：
1. Settings 瘦身（D.8）：`LazyColumn` of `ListItem` 分区推子路由（`settings/hosts` 复用 `HostProfilesManagerScreen`、`settings/appearance`、`settings/models`、新增 Notifications/Storage/About）。
2. Settings 顶栏恒传 back（`SettingsScreen.kt:176-180`）。
3. **predictive back（依赖升级，独立提交）**：升级 `navigation-compose ≥ 2.8.0`（现 2.7.6，`libs.versions.toml:14`）+ 兼容的 `activity-compose`（≥1.9.0）+ 验证 `AndroidManifest.xml` `enableOnBackInvokedCallback` 与 `targetSdk`；测试与现有分层 `BackHandler` 冲突。（纠正首轮「仅需 activity-compose 1.9.0」的错误。）
4. 48dp 终审：扫 `Modifier.size(<=40.dp)` 于 `IconButton`/`clickable`。
5. **主题/字号/缩放控件不动**：经核实 `SettingsSections.kt:28-31,183-260` 已是 M3 `SegmentedButton`/`Slider`（首轮 observer 误判，gpter 纠正）。Phase 3 仅把它们迁入 `settings/appearance` 子路由，不替换。
6. **删旧 PhoneLayout + `Screen` enum + `useNewShell` flag**，新壳默认开（G.5 收尾）。**删除条件（顺序执行，非一步）**：(a) 新壳设为默认启用（flag 默认 true）；(b) 跑一次完整验证门（§8 Phase 3 矩阵 + 依赖升级回归）并通过；(c) 通过后才在同一阶段后续提交删除旧 `PhoneLayout`/`Screen` enum/flag。若 (b) 任一项失败，保留旧壳至修复后。

**改动文件**：`gradle/libs.versions.toml`(nav/activity 升级)、`AndroidManifest.xml`(back callback)、`SettingsScreen.kt`/`SettingsSections.kt`(子路由化，VCS 已在 Phase 2 迁出)、`MainActivity.kt`(删旧壳)。**不动** `SettingsSections.kt:183-260` 的 SegmentedButton/Slider。

**验证门**：check.sh + 依赖升级全量回归（compose 兼容）+ back-stack/predictive gesture 矩阵（§10）+ 终审(gpter+oracle @9.5) → `./scripts/release.sh minor`。

**风险/回退**：nav/activity 升级触发 compose 兼容——独立提交便于 revert；删旧壳失去 flag 回退路径——故 Phase 2 须充分验证后再于 Phase 3 删。

---

## 6. Phase 4 — 架构治理（延后，单独排期；不在本次执行）

对应报告 §6，触发条件：Phase 3 后若新功能被迫往 god-slice 塞字段、或 controller 频繁回归。含 A5-1/A5-2/A5-3、A4-1..A4-5、A3-2/A3-3。

---

## 7. 硬前置与依赖矩阵（纠正后）

| 前置 | 解锁 | 位置 |
|---|---|---|
| Phase 0 §2.2 A3-1（含 `RevertConversation` use case + outcome） | Phase 1C 消息行 revert 动作 | `AppStateDerived.kt:111-129` |
| `material3-adaptive-navigation-suite` artifact | Phase 1A AppShell | `gradle/libs.versions.toml` |
| `navigation-compose ≥2.8.0` + 兼容 activity-compose + manifest/targetSDK | Phase 3 predictive back | `libs.versions.toml:14`、`AndroidManifest.xml` |
| Phase 1A（壳稳定 + overlay 不断路） | Phase 1B | — |
| Phase 1B（ChatScaffold parity） | Phase 1C | — |
| Phase 1C（状态槽 + revert 安全） | Phase 2 | — |
| Phase 2（Workspace 一等地 + VCS 迁出 + overlay 入口迁移） | Phase 3（Settings 可瘦身 + 删 overlay + 删旧壳） | — |

---

## 8. 验收矩阵（Done 定义，可执行场景）

每（子）阶段须过：cold start、rotation、process restore（杀进程重启）、system back、sheet back、streaming、offline/reconnect、跨 host/session 切换。

- **Phase 0 done**：check.sh 过 + revert cutoff 三态/冷启动/分页/失效单测过 + 集成测（成功/失败/取消/重复/重连/流式中）过 + Files semantics + 48dp + 去版本号落地。
- **1A done**：4 目的地切换 + Files overlay 仍可达 + deep-link 到 Chat+session + back 优先级无冲突 + 旧壳经 flag 可回退。
- **1B done**：无标签条/无 Pager + parity checklist 逐项（流式/coalesce/gap/paging/scroll/draft/未读/marker）等价 + model/agent/Add 菜单可用 + 旋转/进程恢复 sheet 状态正确。
- **1C done**：状态槽四态无同位竞争 + 权限按会话过滤 + revert/fork 可达 + revert 破坏性 gate 全过。
- **2 done**：搜索 + 上下文 sheet(切 scope 重置/恢复) + Workspace 双标签 + 变更深链 + VCS 移出 Settings + overlay 已无活跃入口 + 跨 host/session diff 隔离。
- **3 done**：Settings 子路由 + predictive back 矩阵过 + 48dp 终审 + 旧壳删除 + 新壳默认 + 依赖升级无兼容回归 + 终审过 + release 出包。

---

## 9. 风险登记（gpter 风险项 + 补充）

1. adaptive navigation suite artifact 缺失/兼容 → Phase 1A 独立提交验证。
2. 新旧导航持久化冲突 → 稳定 route key + 迁移表（§12）。
3. Files overlay Phase 1 断路 → 1A 保留 overlay 至 Phase 2。
4. navigation-compose 2.7.6 不满足 predictive back → Phase 3 升 2.8+。
5. NavHost 与分层 BackHandler 优先级冲突 → §10 表 + 1A 审计。
6. 通知 deep-link 仅选 session 不导航 Chat → 1A adapter 显式处理。
7. hiltViewModel() screen-scoped 实例化 → 1A 确认作用域。
8. Modal sheet 并发/旋转/进程恢复布尔冲突 → 用 `rememberSaveable`/`SavedStateHandle`，1B/1C/2 验证。
9. A5-1 data→ui 环在 Changes 扩大 → Phase 2 仅复用 domain `FileDiff`，环拆解留 Phase 4。
10. Workspace Changes 的 session/host 过滤与 stale diff 隔离 → Phase 2 隔离规则。
11. `SettingsManager` 存 selected diff file 的 key/清理/跨 host 隔离 → Phase 2 优先 `SavedStateHandle`（无需跨进程恢复），必要时 `SettingsManager` 带过期清理。
12. Phase 3 删旧壳失紧急回退 → Phase 2 充分验证后再删；旧壳设稳定期。

---

## 10. 路由 / 返回行为表（Phase 1A 产出，Phase 3 验证）

| 当前 route | 打开的 sheet | 系统 back | predictive back |
|---|---|---|---|
| chat | (无/会话 sheet/上下文 sheet/状态槽内) | 关 sheet → 退 app(或回上 route) | 同 back，带预测动画 |
| sessions | (搜索) | 关搜索 → 回 chat(若由 chat 进入) | 同 |
| workspace/files | (预览) | 关预览 → 回 files | 同 |
| workspace/changes | (diff sheet) | 关 diff → 回 changes | 同 |
| settings/* | (子页) | 回 settings → 回 chat | 同 |

具体优先级在 1A 审计后定稿。

---

## 11. gpter 评审记录

- **v1 评审（2026-07-11）**：**FAIL**。阻塞：Files 断路、ordinal 共享不兼容、predictive back 依赖判断错误、A3-1 数据所有权/失败语义未定义、adaptive nav suite artifact 缺失、gate 标注自相矛盾、Phase 1 过大。已纳入全部 🔴/🟠 修复（本 v2）。
- **gpter 纠正采纳**：#12（Settings SegmentedButton/Slider 已是 M3）经核实 `SettingsSections.kt:28-31,183-260` 属实，已撤回报告 V7/P3-1 错判、移除计划冗余替换任务。
- **v2 评审（2026-07-11）**：**PASS-WITH-MINOR-FIXES**。v1 全部架构阻塞已解，prompt-ready 仅差两处微修：(1) §2.2 revert fail-closed 规则；(2) §3.1 迁移文件 + §5 task 6 旧壳删除顺序条件。**两处微修已落实**（§2.2 selector 一致性/fail-closed、§3.1 NavState+SettingsManager、§5 task 6 默认启用→回归→删除顺序）。状态：✅ prompt-ready。

---

## 12. 相对源文档的有意偏差

| 偏差 | 源文档 | 本计划 | 理由 |
|---|---|---|---|
| 持久化 route | scheme G.5 用 `.ordinal` | 稳定 route key + 迁移表 | 旧/新 ordinal 碰撞（Settings=2 vs Workspace=2），gpter #2 |
| 新壳默认时点 | 首轮计划「默认开」 | opt-in(G.0-G.2)，Phase 3 默认 | 对齐 scheme G.5，保回退路径 |
| Files overlay | 首轮计划未声明 | 1A 保留至 Phase 2 | 避免 Phase 1 断路，gpter #1 |
| predictive back 依赖 | 首轮「activity-compose 1.9.0」 | navigation-compose ≥2.8 + 兼容 activity + manifest | gpter #4 |
| adaptive nav suite | scheme `:9` 假设在主 artifact | 独立 artifact 显式声明 | gpter #5 |
| Settings 控件 | 报告 V7/P3-1（错判） | 已是 M3，仅迁子路由 | 核实 `SettingsSections.kt`，gpter #12 |
| Phase 1 粒度 | scheme G.1 单元 | 拆 1A/1B/1C | 单会话可控，gpter #7 |

---

## 13. 各（子）阶段提示词

> 以下每段为一个**独立新会话**的启动提示词，按顺序执行。每段自包含：声明阶段范围/前置/产出/验证门/约束，并要求先读三份文档（报告 + 方案 + 本计划）。复制时整段粘贴。完成一段、gate 通过后，再开新会话执行下一段。

---

### 13.0 Phase 0 — 紧急修复 + revert 数据正确性

```
你是 ocdroid（Android/Kotlin/Compose Material 3，OpenCode 移动客户端）重构的执行 agent。当前阶段：Phase 0（紧急修复 + revert 数据正确性）。仓库根：/home/mar/personal_projects/ocdroid。

先读（必读，顺序）：
1. docs/redesign-execution-plan.md —— 本阶段权威范围：§0 执行原则、§2 Phase 0（含 §2.2 A3-1 子计划）、§7 硬前置、§8 验收矩阵。
2. docs/redesign-mobile-ux-architecture.md §3 A3-1（filterBeforeRevert 窗口泄漏 bug）。
3. AGENTS.md（check.sh / 模拟器纪律 / 版本号规则）。

本阶段范围（严格不越界到 Phase 1+）：
- A3-1 revert cutoff 数据正确性：按 §2.2 实现 RevertCutoff（Resolved/PendingFetch/NoTimestamp）+ 持久化 + Session.revert 变化原子失效 + 永久失败 fail-closed（绝不放行全窗）+ 无时间戳消息一律排除 + RevertConversation use case（成功/失败/取消 outcome + 流式中拦截 + 重复/重连去重）。改 AppStateDerived.kt:111-129 + 相关 domain/cache/SSE 写入点。
- Files semantics（FilesScreen.kt:78-85）：clearAndSetSemantics 或改正注释 + TalkBack 验证。
- 48dp：ThinkingCapsule.kt:109-129、ChatMessageNavFab.kt:58-74。（ChatSessionTabStrip 不改，Phase 1B 整条移除。）
- 顶栏去版本号 + 未连接去红点角标。

红线：不动服务端协议；不重构 controller/repository 架构（Phase 4）；revert 数据正确性是唯一 domain/cache 受控例外；不暴露 revert UI（那是 Phase 1C）。

验证门（必须全过才算完成）：
- ./scripts/check.sh 通过（含新增 revert 单测：cutoff 在/不在窗口、无时间戳、PendingFetch、NoTimestamp、冷启动缓存恢复、分页后、Session.revert 变化失效；集成测：成功/失败/取消/重复/重连/流式中拦截）。
- 模拟器回归 revert 三态 + 冷启动 + 分页（用前 ./scripts/emulator.sh status 确认未运行再 start，用完 stop）。

完成后：git 状态自查，不提交（除非我明确要求）。报告：改了哪些文件、check.sh 结果、revert fail-closed 如何保证、模拟器验证结论。
```

---

### 13.1A Phase 1A — 壳迁移（opt-in）

```
你是 ocdroid 重构的执行 agent。当前阶段：Phase 1A（壳迁移，opt-in）。前置：Phase 0 已完成并通过 gate。仓库根：/home/mar/personal_projects/ocdroid。

先读（必读）：
1. docs/redesign-execution-plan.md §0、§3.1 Phase 1A、§7（material3-adaptive-navigation-suite + route key 前置）、§9 风险 1-7/12、§10 路由/返回表、§12 偏差（route key/overlay/opt-in 时点）。
2. docs/redesign-mobile-compose-scheme.md §D.1 AppShell、§F.1 NavRoute、§G.0/G.5。
3. MainActivity.kt:239-417（旧 PhoneLayout + overlay）、OrchestratorViewModel.kt:57-65（setLastNavPage）。

本阶段范围：
- 依赖前置（独立提交）：gradle/libs.versions.toml 新增 material3-adaptive-navigation-suite artifact，验证与 Compose BOM 兼容。
- NavRoute enum + NavState（route key 持久化，禁用 .ordinal）+ 旧 int 迁移表（0/1/2 → chat/sessions/settings；旧 2 不映射 workspace）。SettingsManager 持久化 last route key。
- AppShell（D.1）：NavigationSuiteScaffold + NavHost，4 目的地（Workspace stub）。
- 保留 fileBrowserOpen overlay：把 overlay 渲染逻辑迁入 AppShell，行为不变；删除推迟到 Phase 2。
- BuildConfig.USE_NEW_SHELL（Gradle 生成，非 local.properties 直读），默认 false（opt-in）。
- navigation/deep-link adapter：Sessions→Chat、Settings back、Files origin、通知 deep-link（→ Chat + 选中 session）显式映射到 NavController。
- BackHandler 优先级审计 + hiltViewModel() 作用域确认 + §10 返回行为表定稿。

红线：不重构 controller/repository/state 架构；不改 SSE/流式；Chat 内部本阶段不动（留给 1B/1C）；Files overlay 不断路。

验证门：./scripts/check.sh + 模拟器回归矩阵（cold start/旋转/进程恢复/4 目的地切换/Files overlay 仍可达/deep-link 到 Chat+session/system back/sheet back/重连）+ review gate（gpter+oracle：导航/back/deep-link/overlay 未断）。旧壳经 flag 可回退。

完成后：自查 git，不提交除非明确要求。报告：改/增文件、依赖兼容结论、route key 迁移表、overlay 迁移点、deep-link/back 适配、BackHandler 优先级定稿、gate 结果。
```

---

### 13.1B Phase 1B — 聊天 chrome + composer

```
你是 ocdroid 重构的执行 agent。当前阶段：Phase 1B（聊天 chrome + composer）。前置：Phase 1A 已完成并通过 gate。仓库根：/home/mar/personal_projects/ocdroid。

先读（必读）：
1. docs/redesign-execution-plan.md §0、§3.2 Phase 1B（含 parity checklist）、§8（1B done）。
2. docs/redesign-mobile-compose-scheme.md §D.2 ChatScaffold、§D.3 Composer、§D.4 SessionPickerSheet、§D.5 ChatTopBar、§E.1/E.2、§F.4 ComposerState.fileReferences。
3. 现有 ChatScreen.kt / ChatTopBar.kt / ChatSessionTabStrip.kt / ChatInputBar.kt / ChatMessageContent.kt（订阅同一 slice，禁重新实现 selector）。

本阶段范围（G.1 步 1-4,8）：
- 新 TopAppBar + session-history 图标 + 上下文 chip（D.2/D.5），替换 ChatTopBar 第二行 SessionTabStrip。session-history 开 ModalBottomSheet（D.4），先 Recent+By-workdir（无 Search）。
- 移除 ChatScreen.kt:399-414,615-655 的 HorizontalPager；会话切换走 sheet。
- + 改 ModalBottomSheet Add 菜单（D.3），先只做 Photos。
- 输入行上方加 Agent/Model AssistChip（D.3），点开 ModalBottomSheet（复用现有对话框内容，无 Search）。
- 会话归档长按 → sheet 行溢出菜单。
- ComposerState.fileReferences（additive，不动现有 writer）；文件引用走方案 A（PartInput type=text + "File: <path>"）。

红线（parity，强制）：ChatScaffold 必须订阅现有同一 slice，行为等价；禁改 SessionSyncCoordinator。逐项验证：流式 coalesce、gap/paging、scroll anchoring、streaming overlay、draft lifecycle、未读清理、metadata marker 注入。

验证门：./scripts/check.sh + parity checklist 逐项签字 + 模拟器（流式/分页/旋转/进程恢复/sheet 状态）+ review gate（gpter+oracle）。新壳仍 opt-in。

完成后：自查 git，不提交除非明确要求。报告：改/增文件、parity 逐项结论、Add 菜单与 chip 接线、方案 A 文件引用实现点、gate 结果。
```

---

### 13.1C Phase 1C — 状态槽 + revert/fork 动作

```
你是 ocdroid 重构的执行 agent。当前阶段：Phase 1C（状态槽 + revert/fork 动作）。前置：Phase 1B 已完成并通过 gate，且 Phase 0 的 A3-1（含 RevertConversation use case）已落地。仓库根：/home/mar/personal_projects/ocdroid。

先读（必读）：
1. docs/redesign-execution-plan.md §0、§3.3 Phase 1C、§2.2（RevertConversation 契约）、§8（1C done）。
2. docs/redesign-mobile-compose-scheme.md §C.3 单一状态槽优先级规则、§D.2.1、§D.6 MessageCard、§E.4/E.5。
3. ChatViewModel.kt:165-198（editFromMessage/revert）、SessionViewModel.kt:208（forkSession）。

本阶段范围（G.1 步 5-7）：
- 单一状态槽（C.3/D.2.1）：Permission/Question/Running/Connecting 四选一优先级渲染；SessionRetryCard、Compacting 胶囊、connecting 胶囊、ThinkingCapsule、QuestionCardView 全部汇流，禁止同位竞争。
- 状态槽输入按 chat.currentSessionId 过滤 pending permissions。
- 消息行溢出菜单（D.6）：Copy / Edit & rerun / Fork / Revert + 确认框（影响说明、不可误触）。Revert 调 RevertConversation use case（Phase 0）；Fork 调 forkSession。
- 滑动/长按策略（E.5/P4-4）：手势仅作加速器，破坏性操作走溢出菜单 + 确认。

红线：RevertConversation use case 不得绕过 Phase 0 的 fail-closed cutoff；流式中 revert 必须拦截；不重构架构。

验证门（revert 破坏性 gate，强制）：./scripts/check.sh + revert 数据正确性 + 破坏性场景（成功/失败/取消/重复点击/重连/流式中拦截/确认框误触）+ review gate（gpter+oracle）。模拟器回归矩阵。

完成后：自查 git，不提交除非明确要求。报告：改/增文件、状态槽优先级实现、权限会话过滤、revert/fork 接线与确认框、破坏性 gate 结论。
```

---

### 13.2 Phase 2 — 搜索 + 上下文 + Workspace

```
你是 ocdroid 重构的执行 agent。当前阶段：Phase 2（搜索 + 上下文 + Workspace）。前置：Phase 1C 已完成并通过 gate。仓库根：/home/mar/personal_projects/ocdroid。

先读（必读）：
1. docs/redesign-execution-plan.md §0、§4 Phase 2（file allowlist + route schema + host/session 隔离 + WorkspaceState）、§9 风险 9-11、§8（2 done）。
2. docs/redesign-mobile-compose-scheme.md §D.4(Search)/D.5(ContextSelectorSheet)/D.7/D.7b(Workspace)、§F.3 WorkspaceState、§G.2。
3. 现有 SessionsScreen.kt、SettingsScreen.kt:307-322,379-557（VCS 段）、ChatMessageContent.kt:596-606（SessionDiffCard）、data/model/File.kt（FileDiff，domain 类型，复用不扩 data→ui 环）、MainActivity.kt overlay 路径。

本阶段范围（G.2）：
- Session Picker + Agent/Model sheet 加 M3 SearchBar。
- Context Selector Sheet（D.5）替换 DNS 图标 + 埋藏 workdir 流程；切 host/workdir 显式重置/恢复 scope；跨 host/session diff 隔离。
- Workspace 目的地（D.7）：PrimaryTabRow Files | Changes；Files 指向现有 FilesScreen（迁路由）。
- Changes 标签（D.7b）：变更文件列表 + ModalBottomSheet 统一 diff（紧凑非并排）+ typed deep-link builder（workspace/changes?session=<id>，禁手拼）。
- 消息「N files changed」深链：SessionDiffCard → 单行 ListItem 导航 Changes；详情移出时间线。
- VCS 段移出 Settings → Workspace Changes（确认无 Settings 专属依赖残留）。
- 删 fileBrowserOpen overlay（入口已全迁 Workspace）。
- WorkspaceState（selected diff file）优先 SavedStateHandle，必要时 SettingsManager 带过期清理与跨 host 隔离。

红线：route schema 全 typed；复用 domain FileDiff，不新增 data→ui 耦合（环拆解留 Phase 4）；host/session 隔离 stale diff。

验证门：./scripts/check.sh + host/session 隔离验证 + overlay 已无活跃入口确认 + 模拟器（搜索/上下文/Workspace/深链/旋转/进程恢复/重连）+ review gate（gpter+oracle）。

完成后：自查 git，不提交除非明确要求。报告：改/增文件、route schema、隔离规则实现、VCS 迁移点、overlay 删除确认、gate 结果。
```

---

### 13.3 Phase 3 — Settings 清理 + 导航打磨 + 收壳

```
你是 ocdroid 重构的执行 agent。当前阶段：Phase 3（Settings 清理 + 导航打磨 + 收壳）。前置：Phase 2 已完成并通过 gate。仓库根：/home/mar/personal_projects/ocdroid。本阶段以发版收尾（./scripts/release.sh minor）。

先读（必读）：
1. docs/redesign-execution-plan.md §0、§5 Phase 3（含 task 6 旧壳删除顺序条件）、§7（navigation-compose ≥2.8 + activity-compose + manifest）、§9 风险 4/12、§8（3 done）、§10 返回表。
2. docs/redesign-mobile-compose-scheme.md §D.8 SettingsScaffold、§G.3/G.5。
3. 现有 SettingsScreen.kt / SettingsSections.kt:28-31,183-260（主题 SegmentedButton + 字号/缩放 Slider 已是 M3，仅迁子路由，禁替换）、gradle/libs.versions.toml:14（navigation-compose 2.7.6）、AndroidManifest.xml。

本阶段范围（G.3 + G.5 收尾）：
- Settings 瘦身（D.8）：LazyColumn of ListItem 分区推子路由（settings/hosts 复用 HostProfilesManagerScreen、settings/appearance、settings/models、新增 Notifications/Storage/About）。
- Settings 顶栏恒传 back（SettingsScreen.kt:176-180）。
- predictive back（独立提交）：升级 navigation-compose ≥2.8.0 + 兼容 activity-compose（≥1.9.0）+ 验证 AndroidManifest enableOnBackInvokedCallback + targetSdk；测试与现有分层 BackHandler 冲突 + 全量 compose 兼容回归。
- 48dp 终审：扫 Modifier.size(<=40.dp) 于 IconButton/clickable。
- 主题/字号/缩放控件仅迁入 settings/appearance 子路由（已是 M3，禁替换）。
- 收壳（task 6 顺序）：(a) 新壳 BuildConfig.USE_NEW_SHELL 默认 true；(b) 跑完整验证门（§8 Phase 3 矩阵 + 依赖升级回归）通过；(c) 通过后才删旧 PhoneLayout/Screen enum/flag。任一失败保留旧壳。

红线：不替换已是 M3 的 SegmentedButton/Slider；依赖升级独立提交便于 revert；删旧壳前必须有默认启用 + 完整回归通过的稳定窗口。

验证门：./scripts/check.sh + 依赖升级全量回归 + back-stack/predictive gesture 矩阵（§10）+ 终审（gpter+oracle @9.5）→ ./scripts/release.sh minor。

完成后：自查 git。报告：改/增文件、依赖升级与 compose 兼容结论、manifest/SDK 调整、收壳三步执行结论、发版产物。
```

---

> 使用说明：按 13.0 → 13.1A → 13.1B → 13.1C → 13.2 → 13.3 顺序，每段开一个新会话粘贴执行；上一段 gate 通过再开下一段。任一段失败则在同会话内修复至 gate 通过，不跨阶段。Phase 4（架构治理）单独排期，不在本提示词序列内。
