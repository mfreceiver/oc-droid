# ocdroid 设计与代码质量评审 Handoff

> 本文档是 2026-08-07 双线评审的综合交接，供后续接手修复者使用。两份原始评审为只读 rev-restrict 评审（`git_ro`），未跑 `./scripts/check.sh`——任何改动落地后须以 check.sh 为完成判据。

## 元信息

| 项 | 值 |
|---|---|
| 评审日期 | 2026-08-07 |
| review_id | `rv_20260807-102253_e044adb0_593440` |
| 评审 head OID | `aeb6e67150aafdfdcf14016f48bffe4ca924fb5a` |
| 评审范围 | 设计/美学/主题管理 + 代码质量/模块化/可维护性/冗余死代码 |
| 评审专家 | rev-kimi（设计）、rev-gpt（代码）；rev-opus 原派因 Session error 未产出，由 rev-gpt 替补 |
| 评审会话 | rev-kimi `ses_0244065e9ffe8dnZUL2goLCept` / rev-gpt `ses_0240b9102ffeYUrnXJvQpzlIu3` |

## 总览评分

| 维度 | 评审专家 | 评分 | 一句话结论 |
|---|---|---|---|
| 设计 / 美学 / 主题管理 | rev-kimi | **B**（地基 A，执行一致性 B） | 主题原语体系文档化程度顶尖，色彩零泄漏；但 MANDATORY 规范状态表与代码失实、overlay 三层规范有未登记违规 |
| 代码质量 / 模块化 / 可维护性 / 冗余死代码 | rev-gpt | **B**（有条件通过） | 分层/DI/测试体系完整，无高危反模式；但 AppCore 等仍是 God Object，UI 直依赖具体 Repository，存在测试专用生产代码与残留兼容面 |

代码健康度（rev-gpt）：模块化 ★★★☆☆ / 可维护性 ★★★☆☆ / 冗余治理 ★★☆☆☆

---

## 交叉一致点（两位专家独立命中，可信度高，优先处理）

### 1. 规范 / 文档可信度裂缝
- **rev-kimi**：`docs/specs/ui-style-spec.md §1.2` 状态表声称 stop-confirm/revert-confirm 已用 `AppConfirmDialog`，实际 `Composer.kt:346`、`MessageCard.kt:392` 仍是裸 `AlertDialog`。
- **rev-gpt**：`AppShell.kt:86` 的 FIXME 注释自称"closes the FIXME"但仍挂着；`NavState.lastNavPage` 已 `@Deprecated` 但 `OrchestratorViewModel.kt:84-91` 仍有写入。
- **共性教训**：声明与代码不符会系统性削弱"强制规范"的可信度。

### 2. 死代码 / 残留冗余（双方各自挖出一批，互不重叠，合并即完整清单）
- 设计侧死 token：`SemanticColors.addedLine/deletedLine`（浅色 pastel，暗色误用陷阱）、`Color.StopRed`（全库零引用）。
- 代码侧死代码：`ui/session/SessionTree.kt`（`buildSessionTree`/`flattenVisibleTree` 仅测试调用，无生产消费者）、`ProcessStatusPoller`（注释自承 inert 但仍被调用）、`configureServer(): Job?`（恒返回 null）、`ConnectionCoordinator.diagLayer`（恒 null）。

### 3. Overlay 治理是共同痛点
- **rev-kimi**：两个未登记裸 `AlertDialog`（`ChatServerManagementDialog.kt:44`、`ChatOverlayHost.kt:220` error dialog）违反三层规范，且散落 `4.dp/12.dp/400.dp` 字面量。
- **rev-gpt**：`ChatScaffold.kt`（1398 行）承载过多 overlay/picker/dialog 状态，是 God Composable。

---

## Panel A：设计 / 美学 / 主题管理（rev-kimi）

### 亮点（保留，勿在后续重构中破坏）
1. **色彩集中度近乎满分**：`grep "Color(0x"` 在 `ui/` 下仅命中 `theme/Color.kt` 与 `theme/SemanticColors.kt`；feature 层零硬编码颜色、零命名色泄漏。
2. **Diff 渲染暗色安全**：`UnifiedDiffRenderer.kt:114-119` 用 `addedFg.copy(alpha = DiffLineBgAlpha)` 从主题前景色派生行背景，明暗两态自动正确。
3. **状态色 WCAG 治理有实证**：`SemanticColors.kt:33-50` 明暗双值切换，注释写明实测对比度（中亮度 2.25-2.85:1 故废弃）。
4. **共享原语采用率高**：`AppBottomSheet` 10 调用点、`AppConfirmDialog` 6 处、`AppSectionHeader` 17 处、`Dimens.topBarHeight` 覆盖全部 8 个 `TopAppBar`。
5. **Tier A 锚定全部正确**：`ChatTopBar.kt:704,726` Box-sibling 锚定；`MessageCard.kt:286-295`、`SessionsScreen.kt:466-488` `pressOffset` 钉触点。
6. **a11y 细节到位**：`SessionAttentionBadge.kt:54-60` 全 tier 带 contentDescription；`ChatTopBar.kt:706-709` 触控目标 44dp 引 WCAG 2.5.5。
7. **暗色冷启动白闪已修**：`values-night/colors.xml:11` starting window `#080808`。
8. **WebView markdown 明暗跟随**：`MarkdownWebPreviewPane.kt:155` 从 luminance 推导 theme，preview.css 双套 `[data-theme]` 变量。
9. **动效 token 化**：`AppMotion` + `SseBreathSpec` 共享常量，呼吸节奏两处复用不漂移。

### 问题清单

#### P1
- **[P1] 规范文档 §1.2 状态表失实：stop-confirm / revert-confirm 未用 `AppConfirmDialog`**
  - 位置：`docs/specs/ui-style-spec.md:54-55` vs `ui/chat/Composer.kt:346`、`ui/chat/MessageCard.kt:392`
  - 现象：规范声称已迁移，实际两处仍是裸 `AlertDialog`。`Composer.kt` 的 stop-confirm 是纯文本+两按钮，正是 `AppConfirmDialog` 目标场景；`MessageCard.kt` 因需 `confirmButtonEnabled`（`:389`）而 `AppConfirmDialog` 目前不支持 `enabled` 参数。
  - 建议：① `Composer.kt` 直接迁移；② 给 `AppConfirmDialog` 加 `confirmEnabled: Boolean = true` 后迁移 MessageCard，或就近补 §3 偏离注释；③ 同步修正 spec §1.2 表格。

- **[P1] 两个 overlay surface 未登记且违规裸写**
  - 位置：`ui/chat/ChatServerManagementDialog.kt:44`（活代码，从 `ServerStatusIconButton.kt:222` 调用）、`ui/chat/ChatOverlayHost.kt:220`（error detail dialog）
  - 现象：均为裸 `AlertDialog`，未在 spec §1.2 登记。`ServerManagementDialog` 还散落 `RectangleShape`、`padding(12.dp)`、`Arrangement.spacedBy(4.dp)`、`Spacer(height(4.dp))`、`heightIn(max = 400.dp)`。
  - 建议：error detail（含 `SelectionContainer`+滚动正文）→ `AppFormDialog`；ServerManagementDialog → `AppBottomSheet` 或规范化 `AppFormDialog`，清掉字面量。

- **[P1] WorkdirControl switcher 选中态违反 spec §2 单选约定**
  - 位置：`ui/files/WorkdirControl.kt:233-241`
  - 现象：trailing Check 未用 `PickerTrailingCheck`——无 `primary` 染色、未选中时不渲染 Spacer 导致行尾宽度跳变（`PickerTrailingCheck.kt:20-21` 明确把"恒渲染防跳动"列为设计要点）。
  - 建议：`trailingContent = { PickerTrailingCheck(isCurrent) }` 一行替换。

#### P2
- **[P2] 单选选中态三种方言并存**：`PickerSheets.kt:56-63`（primary headline + Check）、`SessionPickerSheet.kt:12-15`（仅 headline，有注释刻意去 Check）、`WorkdirControl.kt:233-241`（未染色 Check）。建议明确选定一种为规范。
- **[P2] pre-12 回退 DarkPrimary 对比度不足**：`Color.kt:25`（`#3B5CF6`）在 `DarkSurface #161616` 上约 3.5:1、sheet 容器 `#242424` 上约 3.0:1，作为 16sp 正文低于 WCAG AA 4.5:1（仅影响 pre-12 回退路径）。建议 dark primary 调亮（如 `#8FA3F8`）或选中态文本改染 `stateInfoFg()`，工具实测。
- **[P2] `compactTypography` 最小字号 9-11sp 低于可读下限**：`Type.kt:212-221`（bodyMedium 11sp、bodySmall 10sp、labelMedium 10sp、labelSmall 9sp），注释自称"取 13 保 ≥12sp"却压其余到 9-11sp，与项目 a11y 水位自相矛盾。建议下限抬到 ≥11sp（label）/ 12sp（body）。
- **[P2] 死 token 残留 token 文件**：`SemanticColors.kt:61-62`（`addedLine #E8F5E9` / `deletedLine #FFEBEE`）、`Color.kt:20`（`StopRed`）全库零引用，且 pastel 值是暗色陷阱。建议删除或标注 deprecated。
- **[P2] dp 字面量大面积散落，与 spec §2 张力**：49 个 ui 文件命中 `[0-9].dp`；`ChatUiTuning.kt`（chat 下第二个尺寸 object）与 `Dimens` 双头并行。`Dimens.kt:9-10` 承认"不批量替换"但 spec §2 写"禁止散落"。建议 spec §2 补过渡条款"存量不强制、新增/改动行必须 Dimens"，合并 `ChatUiTuning`。
- **[P2] Tier A 菜单图标尺寸两档**：`MessageCard.kt:299,321`（24dp 默认）vs `ChatTopBar.kt:726`（`Dimens.iconSm` 18dp），spec §2 规定 18dp。建议统一或登记偏离。
- **[P2] 冷启动 window 背景与 dynamic color 一帧 mismatch（边界）**：`values-night/colors.xml:11`（固定 `#080808`）vs `Theme.kt:189-190`（12+ 壁纸派生），注释"eliminates the flash"过于绝对。建议修注释措辞或 MainActivity 预选 window 背景（成本高，仅记录）。

### 三层 Overlay 规范遵守度（逐表核对）

| 规范声称 | 实际 | 结论 |
|---|---|---|
| Agent/Model/Session picker = B | `PickerSheets.kt:42,155`、`SessionPickerSheet.kt:138` | ✅ 符合 |
| ContextUsage/Todo/Workdir/DirectoryPicker = B | `ChatContextUsageDialog.kt:88`、`ChatOverlayHost.kt:173`、`WorkdirControl.kt:149`、`DirectoryPicker.kt:107` | ✅ 符合 |
| stop-confirm = C | `Composer.kt:346` 裸 `AlertDialog` | ❌ 失实 |
| revert confirm = C | `MessageCard.kt:392` 裸 `AlertDialog` | ❌ 失实 |
| archive/disconnect/clear-data/host delete = C | `SessionsScreen.kt:662,680`、`SettingsSections.kt:352`、`HostProfileEditorDialog.kt:676,699` | ✅ 符合 |
| 表外 surface | `ServerManagementDialog`、`ChatOverlayHost` error dialog | ❌ 违规未登记 |
| 破坏性操作均走确认 | force-abort `ChatScaffold.kt:1289` | ✅ 符合 |

---

## Panel B：代码质量 / 模块化 / 可维护性 / 死代码（rev-gpt）

### 架构与模块化

**分层现状**：`data/api`、`data/repository`、`service`、`ui/controller`、`ui/chat` 层次清晰；`SharedStateStore.kt:18` 单一聚合状态利于原子更新与测试。

**主要问题**：UI 仍直接持有具体数据层实现——
- `ChatMessageContent.kt:47` 引入 `OpenCodeRepository`
- `ChatMessageRow.kt:106` 的 `repository` 参数是具体 `OpenCodeRepository`
- 下层实际只需窄能力（`ChatTextParts.kt:150` 已用 `FileVcsRepository`）

**最大文件 / God 类排行 Top 5**：
1. `TokenStreamCoordinator.kt` ~74 KB / 1434 行（stream 生命周期+epoch/generation+watchdog+退避重连+reducer dispatch）
2. `ChatScaffold.kt` ~79 KB / 1398 行（7 个 ViewModel + 大量 dialog/drawer/picker/派生状态）
3. `AppCore.kt` ~68 KB / 1200 行（Store+Repository+Settings+身份+SSE Bridge+多 Controller+多 Orchestrator，应用级 God Object）
4. `ConnectionCoordinator.kt` ~57 KB / 1051 行
5. `ChatViewModel.kt` ~50 KB / 956 行

**DI 一致性**：Controller 的 Hilt Provider 方案合理（`ControllerModule.kt:44` 解释了 internal 类型不能直接 `@Inject constructor`）。但 Orchestrator 有两套组装机制——类标 `@Inject constructor`，AppCore 又 `by lazy` 手工构造（`AppCore.kt:230,265`）。代码已留 `TODO(Wave2.2)`，属迁移未完成。

### 代码质量问题清单

#### P0
无。未发现必现崩溃、数据损坏或全局协程泄漏。未发现 `GlobalScope`、`runBlocking`、空 `catch` 等高危反模式。

#### P1
- **[P1] 递归中止失败后仍被视为"完成"** — `ChatViewModel.kt:604,671`
  冷启动 fallback 通过 `getChildren` 补全子树；超时/异常只写 DebugLog 后返回部分结果，调用方继续 abort 并在 `:649` 清锁。子树获取失败与"已完整处理"在状态层无区别，可能只中止父/部分子会话。建议返回 `complete/partial/error` 结果，失败时保留状态并向 UI 反馈。

- **[P1] AppCore 仍是跨域依赖汇聚点，DI 迁移未完成** — `AppCore.kt:83,265`
  构造函数接收 20+ 依赖，保留多 slice accessor/effect router/跨域 orchestration/兼容入口。5 个 Orchestrator 虽有 `@Inject constructor` 仍由 AppCore 手工 lazy 组装。建议完成 Wave2.2：Hilt 直接提供，按跨域用例拆分 effect routing。

- **[P1] TokenStreamCoordinator 职责和构造参数过度集中** — `TokenStreamCoordinator.kt:96,199`
  同时管传输连接+重连+watchdog+epoch+generation+revision dedup+owner map+测试 hook+UI dispatch。建议拆出 `StreamLifecycleSupervisor`/`TokenFrameGuard`/`ReconnectPolicy`/`TokenStateDispatcher`。

- **[P1] UI 依赖具体 OpenCodeRepository，削弱可测试性与分层** — `ChatMessageRow.kt:106`、`ChatMessageContent.kt:139`
  聊天 UI 只需文件预览/补全省略等有限能力，却通过 ViewModel 暴露完整 Repository。建议 ViewModel 提供窄接口或 suspend callback。

#### P2
- **[P2] `!!` 多数有不变量保护但演进安全性差** — `SlimapiResync.kt:97`、`ConnectionGateway.kt:230`、`ConnectionCoordinator.kt:342`。建议改局部 `val`/`let`/解构非空对象，把不变量显式化。
- **[P2] `configureServer()` 返回类型与语义不一致** — `HostProfileController.kt:338,347` 声明 `Job?` 但固定返回 `null`，ViewModel 透传（`HostViewModel.kt:300`）。改 `Unit` 或恢复真实异步 Job。
- **[P2] ChatScaffold 大型入口承载过多局部状态/派生逻辑** — `ChatScaffold.kt:345,454`。建议拆 `ChatChromeState`/`ChatNavigationEffects`/`ChatDerivedState`/`ChatOverlayHost`，用 Compose metrics 或重组测试验证。
- **[P2] 导入和兼容代码冗余** — `ChatMessageRow.kt:45`（`isThinPlaceholder` 重复 import）、`ChatMessageContent.kt:20`（显式 import + wildcard import 混用）。建议 lint/formatter 清理。

### 冗余与死代码专项

| 类型 | 符号/文件 | 位置 | 证据 | 建议 |
|---|---|---|---|---|
| 确认死代码 | `buildSessionTree`、`SessionNode` | `ui/session/SessionTree.kt:10` | 全仓 `buildSessionTree(` 仅命中定义、`ChatViewModelTest.kt:322`、`SessionTreeTest.kt`；无其他 `app/src/main` 调用 | 删除或迁测试 fixture |
| 确认死代码 | `flattenVisibleTree` | `ui/session/SessionTree.kt:25` | 仅定义+自身递归+`SessionTreeTest.kt:46/59`，无生产外部调用 | 同上；至少改 `internal` |
| 冗余兼容属性（待确认） | `ConnectionCoordinator.diagLayer` | `ConnectionCoordinator.kt:390` | getter 恒 null；唯一生产使用是 `SendOrchestrator.kt:66` 日志插值 | 删字段属性或改真实诊断值 |
| 冗余返回类型 | `configureServer(): Job?` | `HostProfileController.kt:338-347` | 所有生产调用直接调用；实现恒返回 null | 改 `Unit`，同步更新 ViewModel |
| 重复 import | `isThinPlaceholder` | `ChatMessageRow.kt:45,47` | 同文件两次 import，实际调用仅 `:703` | 删重复 import |
| 过时标记 | `AppShell` FIXME | `AppShell.kt:86` | 注释随后称"closes the FIXME" | 删已解决 FIXME |
| 遗留（待确认） | `NavState.lastNavPage` | `NavState.kt:54` | `@Deprecated` 但 `OrchestratorViewModel.kt:84-91` 仍写入，SettingsManager 参与迁移 | 迁移完成后删字段+写点+测试 |
| 遗留 inert（待确认） | `ProcessStatusPoller` | `AppCore.kt:215-227` | 注释自承生产启动路径已删除、实例 inert；但 `AppCore.kt:1140` 等仍调 backoff 方法，且有测试 | 不恢复则移除整条 effect/DI 链 |

**资源**：字体在 `Fonts.kt:55` 等处有明确引用；本轮无足够证据确认某 drawable/values 资源未使用，**不建议误删**。Repository 接口（`FileVcsRepository`、`SessionRepository`）仍被窄能力路径使用，不判冗余。

### 可测试性与测试现状

测试覆盖较强：`ChatViewModelTest`、`SessionViewModelTest`、`ConnectionViewModelTest`、`AppCoreDispatcherTest`、`SharedStateStoreTest`、`AuthorityReducerTest`、`TokenStreamCoordinatorTest`、`TokenStreamCoordinatorIdempotencyTest`、`ConnectionCoordinatorConcurrentTest`、`OpenCodeRepository*Test`、各类 Interceptor 测试、Compose/instrumentation（`ChatMessageContentRememberTest`、`ChatScaffoldSaveableTest`、`StatusSlot*Test`）。

**可测试性障碍**：
1. AppCore 手工构造多 Orchestrator → 测试 fixture 须理解完整 positional constructor。
2. `ChatViewModel.fetchSubtreeRecursive` 私有协程，仅能通过递归 abort 间接测试，缺独立结果类型。
3. `ChatMessageRow`/`ChatTextParts` 通过具体 Repository 传依赖 → 单测须 mock 大对象。
4. `configureServer()` 的 `Job?` 契约无测试验证（实现恒 null，疑似历史残留）。

---

## 合并改进路线（按 ROI 排序）

### 🔴 立即清理（高收益、低风险）
| # | 项 | 位置 | 类型 |
|---|---|---|---|
| 1 | 删死 token `addedLine`/`deletedLine`/`StopRed` | `SemanticColors.kt:61-62`、`Color.kt:20` | 设计 |
| 2 | 删/降级 `SessionTree.kt`（仅测试用）→ 测试 fixture 或删 | `ui/session/SessionTree.kt` | 代码 |
| 3 | `configureServer(): Job?` → `Unit`，更新透传 ViewModel | `HostProfileController.kt:338`、`HostViewModel.kt:300` | 代码 |
| 4 | 删 `ConnectionCoordinator.diagLayer`（恒 null） | `ConnectionCoordinator.kt:390`、`SendOrchestrator.kt:66` | 代码 |
| 5 | 清重复 import / 已解决 FIXME | `ChatMessageRow.kt:45`、`AppShell.kt:86` | 代码 |
| 6 | 同步 `ui-style-spec.md §1.2` 表格与代码一致 | `docs/specs/ui-style-spec.md:54-55` | 设计 |
| 7 | `Composer.kt` stop-confirm 迁移 `AppConfirmDialog` | `ui/chat/Composer.kt:346` | 设计 |

> 这批写域互不重叠，可拆 3 条并行 fixer lane：① theme token 清理 + spec 表格；② SessionTree + configureServer + diagLayer + import 清理；③ stop-confirm 迁移。

### 🟡 短期（语义/规范正确性）
| # | 项 | 位置 |
|---|---|---|
| 8 | 修递归 abort 部分失败语义（返回 partial/error） | `ChatViewModel.kt:604,671,649` |
| 9 | 给 `AppConfirmDialog` 加 `confirmEnabled` 后迁移 MessageCard revert-confirm | `AppConfirmDialog.kt`、`MessageCard.kt:392` |
| 10 | 统一单选选中态：`WorkdirControl` 改 `PickerTrailingCheck`，规范明确 headline primary 是否必选 | `WorkdirControl.kt:233-241` |
| 11 | 登记并迁移两个表外 overlay（ServerManagement / error dialog）+ 清 `dp` 字面量 | `ChatServerManagementDialog.kt:44`、`ChatOverlayHost.kt:220` |
| 12 | a11y 收口：pre-12 DarkPrimary 调到 AA；`compactTypography` 下限抬到 ≥11/12sp | `Color.kt:25`、`Type.kt:212-221` |

### 🟢 中期（架构债，需配套测试）
| # | 项 | 位置 |
|---|---|---|
| 13 | 完成 Wave2.2 DI 统一：删 AppCore `by lazy` 手工组装 Orchestrator，Hilt 直接提供 | `AppCore.kt:230,265` |
| 14 | 收敛 Repository 依赖：UI 用窄接口，移除 `OpenCodeRepository` 直传 Composable | `ChatMessageRow.kt:106`、`ChatMessageContent.kt:47` |
| 15 | 拆分 God 类：`TokenStreamCoordinator`（生命周期/FrameGuard/Reconnect）、`ChatScaffold`（overlay/navigation/derivedState） | `TokenStreamCoordinator.kt:96`、`ChatScaffold.kt:114` |
| 16 | 迁移完成后删 `lastNavPage` deprecated 字段及写点 | `NavState.kt:54`、`OrchestratorViewModel.kt:84-91` |
| 17 | 决定 `ProcessStatusPoller` 去留：不恢复则移除整条 effect/DI 链 | `AppCore.kt:215-227,1140` |

### 🔵 长期 / 评估项
- spec §2 补 dp 字面量过渡条款；合并 `ChatUiTuning` 入 `Dimens`。
- 统一 Tier A 菜单图标为 18dp（`MessageCard.kt:299,321`）。
- 字体子集化（`Fonts.kt:41` 已记为待办，评估 pyftsubset）。
- EdgeToEdge 主题落地时按 `SheetRecipe.kt §inset-note` 预留 `windowInsetsPadding`。

---

## 待验证 / 不确定项

- 两份评审均为只读静态评审，**未跑 `./scripts/check.sh`**——任何改动落地后须 check.sh 通过（编译 + `testDebugUnitTest`）才算完成。
- pre-12 `DarkPrimary` 对比度（~3.0-3.5:1）为手算 sRGB→linear，建议工具实测。
- `compactTypography` 9sp 文本真实曝光量需截图验证（哪些屏幕在平板分栏下启用）。
- `values-night` window 背景与 dynamic color 一帧 mismatch 未实机验证，基于静态推断。
- rev-opus 原始评审 Session error 未产出；其触及的文件（`SessionTree.kt`、`HostProfileController.kt` 等）已被 rev-gpt 覆盖，无遗漏。

---

## 附录：评审会话引用

| 别名 | session_id | 专家 | 范围 | 状态 |
|---|---|---|---|---|
| rev-1 | `ses_0244065e9ffe8dnZUL2goLCept` | rev-kimi | 设计/美学/主题 | completed, reconciled |
| rev-3 | `ses_0240b9102ffeYUrnXJvQpzlIu3` | rev-gpt | 代码质量/可维护性/死代码 | completed, reconciled |
| rev-2 | `ses_024401e16ffeQ5MMVIk79YM3mV` | rev-opus | （原派，Session error 未产出） | errored, 不可复用 |

绑定：`rv_20260807-102253_e044adb0_593440`（expires 2026-08-07T16:22:53Z），head `aeb6e67`。
