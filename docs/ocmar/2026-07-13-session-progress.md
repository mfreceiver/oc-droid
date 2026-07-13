# 进展与下一步（2026-07-13 会话）

> 本文件为本会话工作的交接快照，供上下文压缩后恢复。权威实现细节见各自 spec。

## 当前状态

- **版本**：`v0.9.3`（已发版上线，tag + APK + Gitea release 487）。
- **分支**：`main`，工作区干净，已 push 到 `origin`。
- **本会话两次发版**：
  - `v0.9.2`（commit `fb2fdff` + `09b0644`）：导航三项改造。
  - `v0.9.3`（commit `ad36e10` + `05cd1e6`）：UX 批次（13 项）+ 后台/通知交接文档。
- **门控**：两次发版实现均经 **gpter + groker 双 9.5 门**通过（v0.9.3 终评 gpter 9.6 / groker 9.5）。
- **硬规则**：所有 Kotlin/资源改动均 `./scripts/check.sh` 通过（compileDebugKotlin + testDebugUnitTest）。

## 本会话已交付

### 代码（已发布）

**v0.9.2 导航改造**（spec：`docs/ocmar/specs/2026-07-13-nav-surfaces-redesign-design.md`）
1. Sessions = 所有根会话扁平全量（去 take(5)、删 workdir 分组块迁往 Files、`sessionFullLoadLimit=500` 无分页、new-session 入口）。
2. Files = 两级项目工作区（`browseWorkdir`、`buildWorkdirGroups` 项目列表、每项目文件按钮、右上添加项目→`connectWorkdir` 仅登记、解耦 active session、deeplink seed、三级 BackHandler）。
3. Chat 恢复 0.7 `SessionTabStrip`（复用纯函数、与 SessionPickerSheet 并存）。

**v0.9.3 UX 批次**（13 项）
- Sessions：去「近期」section、归档名加粗、新建 FAB→顶栏(0灰1直2选)、卡片 点/?/锁 + `rootHasPending`(含 `childSessions`) 聚合子 agent pending 到根、bottom-tab badge 锚图标、可点空态。
- Chat：恢复 0.7 `HorizontalPager` 侧滑（openSessions 集、双向同步、边缘 no-op、close/reorder 竞态加固）、全关 tab 状态（标题 app名+版本号、隐藏 ContextUsageRing、Git「未选取工作目录」、Chat 空态可点切 Sessions）。
- Settings：服务器间距压缩、模型开关转 `BasicAlertDialog`(85% 屏高 + weight，修 AlertDialog.text 触摸吞噬)、`clearLastSweep` try/finally(修 snackbar 重弹)、缓存清除按钮→矩形深色垃圾桶、「服务器」+去所有 `>`、通知去 leading icon。
- 新增 7 个 `rootHasPending` 单测。

### 文档（已入库 `docs/ocmar/specs/`）

1. `2026-07-13-nav-surfaces-redesign-design.md` — 导航改造 spec。
2. `2026-07-13-notification-background-fgs-design.md` — **后台 FGS+通知 spec（已过双 9.5 门，权威）**：模型 A 收敛（连接迁 Service、fold 留 SSC）、L1/L2/L3 分层生命周期、ConnectionIdentity epoch、权威 busy 源、统一 notifier、IslandNotifier decorator pipeline、分阶段、发布门禁。
3. `2026-07-13-notification-background-requirements.md` — 需求（7 用户故事 / 34 FR / 20 NFR / 19 验收）。
4. `2026-07-13-notification-background-dev-design.md` — 开发设计（P0.1–P3 任务清单 + 通知卡片视觉 + decorator 接口草图）。
5. `2026-07-13-notification-background-code-research.md` — 代码调研（现状定位 + 改动影响图 + 风险）。

## 下一步工作

### A. 后台服务 + 通知 + 卡片落地（Track B，**由其它 agent 实施**）

三份交接文档（#3/#4/#5）已就绪，FGS spec 已过双 9.5。实施顺序（dev-design 文档已列）：
- **Phase 0**（最小闭环）：`SessionStreamingService` + `SseEventBridge` + ConnectionIdentity 协议 + 权威 busy 源（全局 `getSessionStatus` + `session.directory` 归并 + 两 workdir 隔离探针门）+ L1/L2/L3 生命周期状态机 + START_STICKY bootstrap + FGS↔poller 交接 + 进程级 reducer + 固定低干扰 ongoing。**关键改造点**：SSE 连接所有权从 `ConnectionCoordinator.sseJob` 上移到 Service；`ForegroundCatchUpController` 背景分支去掉无条件 `CancelSse`（**真实断流点=L152，非 AppLifecycleMonitor**）；`AppLifecycleMonitor` 收敛为前台信号源+poller 宿主；TOFU `pendingTofu*` 抽到共享 `ConnectionBootstrapCoordinator`；新增权限 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`(+`POST_PROMOTED_NOTIFICATIONS` Phase2)。
- **Phase 1**：ongoing「N 任务」聚合 + chronometer + abort + 完成通知 + Settings 文案/channel 修复。
- **Phase 2**（附录）：Live Updates（API36 ProgressStyle + AndroidX Core 1.17+ 回退）。
- **Phase 3**（附录）：小米超级岛（`miui.focus.param`，需审核/白名单）。
- **待验证**（Phase 0 前置）：服务端 `/session/status` 是否 host-global；厂商 dataSync 杀进程策略；dataSync 6h/24h（targetSdk35+）。

### B. v0.9.3 非阻塞遗留（评审 MINOR，可选）

- `ChatScaffold` pager close/reorder 补 JVM 竞态测（守卫已实现，缺自动化测）。
- `rootHasPending` KDoc 仍写 `allSessions`，参数实为 `sessionsById`（注释漂移）。
- 模型管理对话框 `fillMaxHeight` + `heightIn(max)`：短列表时撑到 ~85% 屏高（视觉偏空），可改 wrapContent + 列表 `heightIn`。
- `ModelManagementSection` provider 间距三连 Spacer(12+8+12) 冗余。
- 0-workdir 时空态可点但 no-op（可加 toast 引导或禁用）。

### C. 已知产品边界（非 bug）

- `crossSessionPendingCount` badge 在有**待授权/待问**时持续显示是符合预期的（pending 需用户去**授权/回答**才清，光打开会话不清）。v0.9.3 已通过卡片 点/?/锁 + 聚合让 badge 可溯源到根会话。

## 关键文件索引

| 关注点 | 文件 |
|---|---|
| 会话扁平列表 + 卡片标记 + pending 聚合 | `ui/sessions/SessionsScreen.kt` |
| bottom-tab badge 锚点 | `ui/shell/AppShell.kt` |
| Chat 侧滑 pager + 全关 tab 状态 | `ui/chat/ChatScaffold.kt`、`ChatTopBar.kt`、`ChatEmptyState.kt` |
| Files 两级项目页 | `ui/files/FilesScreen.kt` |
| 顶部 Tab 条（0.7 恢复） | `ui/chat/ChatSessionTabStrip.kt` |
| Settings 各 section | `ui/settings/{HostProfilesManagerScreen,ModelManagementSection,CacheManagementSection,SettingsScreen}.kt` |
| 模型开关 / connectWorkdir / clearLastSweep | `ui/SettingsViewModel.kt`、`ui/ComposerViewModel.kt` |
| SSE 当前所有者（Track B 要改） | `ui/controller/ConnectionCoordinator.kt`、`ForegroundCatchUpController.kt` |
| 现有通知轮询 | `di/AppLifecycleMonitor.kt` |

## 可复用评审/实施会话（本会话沉淀）

- `gpt-1` / `gro-1`：gpter/groker 评审，已读导航+FGS+UX 相关代码，可复用继续评审 Track B 实现。
- `fix-1`：Sessions/AppShell/ChatScaffold 上下文，可复用做导航区后续改动。
- `fix-3`：Settings 全套上下文，可复用做 Settings 后续改动。
