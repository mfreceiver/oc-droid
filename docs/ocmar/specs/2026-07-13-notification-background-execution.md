# 通知 / 后台 SSE 保活 — 执行入口与交接提示词 (Phase 0)

> 状态：**执行入口文档（面向接手的 fresh agent）**。
> 用途：本文件是给**新接手、零上下文**的 agent 的唯一入口——阅读顺序、硬约束、本次范围、执行纪律、已核验的代码锚点、以及一段可直接粘贴的**交接提示词**。
> 设计权威不在本文件——见 §1 阅读顺序。本文件只解决「新人从哪里开始、怎么开始、什么时候停」。

---

## 1. 阅读顺序（必读，按序）

1. `AGENTS.md` —— 仓库硬规则（check.sh 必做、模拟器纪律、版本号 git 派生、main 分支、发版只走 release.sh）。
2. [`2026-07-13-notification-background-fgs-design.md`](./2026-07-13-notification-background-fgs-design.md) —— **权威设计**（已过 gpter + groker 双 9.5 门）：L1/L2/L3 分层生命周期状态机、ConnectionIdentity epoch 协议、busy 权威源、abort 策略、channel 矩阵、IslandNotifier decorator pipeline。**不要重述或推翻其论证。**
3. [`2026-07-13-notification-background-dev-design.md`](./2026-07-13-notification-background-dev-design.md) —— **实施任务清单**：Phase 0 `P0.1`–`P0.10`（§1.1）、Phase 1+ 、通知卡片视觉（§3）、decorator 接口草图（§4）、现状→目标职责拆分表（§5）。
4. [`2026-07-13-notification-background-code-research.md`](./2026-07-13-notification-background-code-research.md) —— 现状定位 + §3「改动影响图」（改哪些文件 / 行号 / 依赖 / 验收锚点）。
5. [`2026-07-13-notification-background-requirements.md`](./2026-07-13-notification-background-requirements.md) —— FR / NFR / 验收 `V-1..V-19`。

---

## 2. 硬约束（不可违反）

- **改动校验必做**：每次改 Kotlin/资源后 `./scripts/check.sh` 必须通过（compileDebugKotlin + testDebugUnitTest；`--full` 加 lint + 覆盖率）。LSP 已关，这是唯一的编译器反馈。
- **设备安全**：UI/插桩测试与安装**仅用模拟器**（`./scripts/emulator.sh status` 确认未运行再 `start`，用完 `stop`）；不得在物理机上跑，除非用户明确要求。
- **Git/版本**：单一主线 `main`；版本号 git 派生（`versionName`=`git describe`、`versionCode`=commit count），**禁止手改** `app/build.gradle.kts`；发版只走 `./scripts/release.sh <patch|minor|major>`（只打 tag，外部 push/upload 需用户确认）。
- **质量门**：发版前过 **gpter + groker 双 9.5 门**。
- **不重写设计**：FGS spec 是权威；本文件及 dev-design 与 spec 冲突时，以 spec 为准。

---

## 3. 本次范围 = Phase 0（仅 P0.1–P0.10）

**目标**：让 FGS **正确**——最小闭环：前台服务持有 SSE 连接 + 分层生命周期交接 + 权威 busy 源 + 权限声明 + 门禁测试。

Phase 1（通知展示层 IslandNotifier）、Phase 2（Live Updates）、Phase 3（小米超级岛）**本次不做**，除非用户另指示。

Phase 0 任务**强依赖单向流动**（dev-design §1.1 给出依赖链）：P0.1 服务 ↔ P0.2 identity ↔ P0.3 lifecycle coordinator ↔ P0.4 status aggregator ↔ P0.5 bootstrap coordinator ↔ P0.6 sse bridge ↔ P0.7 FCC 改造 ↔ P0.8 ALM 收敛 ↔ P0.9 权限/manifest ↔ P0.10 门禁测试。

---

## 4. 执行纪律（green-main + 风险分级）

Phase 0 的 10 个任务分两类，必须区分对待：

### 4.1 安全增量（可单独 ship，main 保持绿，不碰活体 SSE 路径）
- `P0.9` AndroidManifest 权限（`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`）+ `<service>` 声明（需 P0.1 类先存在）。
- 通知 channel 增量（`ocdroid.session_status` LOW + `ocdroid.session_complete` DEFAULT）——dev-design §2.1 / P1.4，channel 创建本身安全，可提前落地。
- 状态栏小图标 drawable（`ic_stat_running` 等单色 silhouette）。
- 新建但**尚未接线**的类骨架（StatusAggregator / StreamingLifecycleCoordinator / ConnectionBootstrapCoordinator / SseEventBridge / ConnectionIdentity），编译通过、无副作用即可。

### 4.2 原子切换（必须一次性、带测试、有回退路径）
**核心风险 = SSE 连接所有权迁移**，三处必须协同改，不能留半截：
1. **SSE owner 上移**：`ConnectionCoordinator.sseJob`（line 108）的所有权迁到 `SessionStreamingService`（进程级）；`directoryFetchGeneration`（line 150）概念上移为 `ConnectionIdentity.epoch`（单一真相源，同时守 SSE collector + directory fetch，**不允许 CC 私留第二个 generation**）；`cancelSseForReconfigure`（line 793-800）改为 emit service-side epoch bump。
2. **FCC 去背景无条件 CancelSse**：`ForegroundCatchUpController.onForegroundChanged(false)` 分支删除 line 150-152 的 `cancelSse (background)` + `CancelSse` emit（**这是真实断流点**，非 AppLifecycleMonitor）；背景分支只留 `clearDraft()` + `backgroundedAtMs`。SSE 启停**只**由 StreamingLifecycleCoordinator 驱动。
3. **ALM 收敛**：`AppLifecycleMonitor._isInForeground = MutableStateFlow(true)`（line 92）改为 `false`（不再默认 true）；ALM 降级为「进程前台信号源 + poller 宿主」，不再当 SSE-cancel 所有者。

> 这三处构成一次**原子切换**。切换前先把 §4.1 的骨架 + identity 协议 + bridge 备齐；切换当次 commit 必须自带 V-1/V-2 门禁测试（见 §6），且 check.sh 绿。**绝不在 main 上留下「SSE 已半迁移」的中间态。**

### 4.3 每步动作
- 改前读 spec 对应章节 + code-research §3 对应行。
- 改后 `./scripts/check.sh`。
- 原子切换后补门禁测试（P0.10）。

---

## 5. 代码锚点核验（截至 v0.9.4 / commit `655f4b7`）

> Phase 0 触及的文件在 v0.9.3→v0.9.4 badge 修复中**未被改动**，下列锚点经核验仍准确（仍以实际文件为准）。

| 锚点 | 文件 | 当前行 | 说明 |
|---|---|---|---|
| `sseJob` | `ui/controller/ConnectionCoordinator.kt` | 108 | SSE feed Job（要上移到 Service） |
| `pendingTofuHostPort` | 同上 | 122 | TOFU 待决（抽到 ConnectionBootstrapCoordinator） |
| `directoryFetchGeneration` | 同上 | 150 | 上移为 ConnectionIdentity.epoch |
| `cancelSseForReconfigure` | 同上 | 793-800 | 改为 emit service-side epoch bump |
| `cancelSse (background)` / `CancelSse` emit | `ui/controller/ForegroundCatchUpController.kt` | 150 / 152 | **真实断流点**，背景分支删除 |
| `onForegroundChanged` | 同上 | 106 | 入口 |
| `backgroundedAtMs = clock()` | 同上 | 149 | 背景分支保留 |
| `_isInForeground = MutableStateFlow(true)` | `di/AppLifecycleMonitor.kt` | 92 | 默认值翻 false |
| `OnSseEvent` 分支 | `ui/AppCore.kt` `dispatchConnectionEffect` | ~595-623 | 改为接 `IdentifiedSseEvent`（P0.6） |
| `OnSseEvent` 定义 | `ui/controller/ControllerEffect.kt` | — | 增加 identity 字段（P0.6） |
| `getSessionStatus()` | `data/repository/OpenCodeRepository.kt` | ~500-502 | host 级，StatusAggregator 主路径消费 |

---

## 6. 门禁测试（Phase 0 未过不得 ship）

- **V-1 dataSync 平台矩阵**：targetSdk 当前 + 35+——`onTimeout()`、`ForegroundServiceStartNotAllowedException`、task-swipe、force-stop、通知权限拒绝、OEM 杀进程。
- **V-2 directory-status 隔离探针**：两 workdir 各有 active session，全局 status 须同时反映（验证 `/session/status` 是否 host-global；若否，升级为 directory fan-out）。
- 单测落 `app/src/test/java/cn/vectory/ocdroid/service/`；插桩落 `app/src/androidTest/`（需模拟器）。

---

## 7. post-v0.9.4 状态备忘（实现者必读）

- **pendingQuestions 已改权威 reconcile**：`SessionSyncCoordinator.loadPendingQuestionsAllWorkdirs`（v0.9.4）从「保留本地、服务端补缺」改为**全 workdir 并发取并集 = 服务端真相**，丢弃服务端不再返回的 ghost，并保留 fan-out 期间新到的 question.asked。**P0.4 StatusAggregator 接管 pending 权威源时，需迁移/对齐此语义**，避免两套权威源。
- **AppShell 底部 badge 源**已改为 `(跨会话 pending) OR (非当前会话未读)`，渲染为右上角小圆点（去数字）。通知功能完成后，badge 数据源应与 StatusAggregator 对齐。
- **SessionSyncCoordinator.kt** 顶部新增了 2 行 import（`async`/`awaitAll`），故该文件行号整体 +2（Phase 0 若改 SSC，以实际为准）。

---

## 8. 待用户确认的前置问题（开工前问清）

> **已决议（2026-07-13 用户决策，见 FGS spec §16）**：U1 划掉/Action 关后台（ongoing 用「关闭后台」Action，非 ongoing 用 deleteIntent）；U2 Settings 整合系统通知设置（无独立 app on/off 开关）；U3 decision/完成通知可点跳对应会话页（`EXTRA_SESSION_ID`+workdir deep-link）。这三项不阻塞 Phase 0（U1 的 L3 teardown 入口 Phase 0 需预留，U2/U3 属 Phase 1）。

仍待确认：
1. 服务端 `GET /session/status` 是否 **host-global**（覆盖所有 workdir）？—— 决定 StatusAggregator 是单查还是 directory fan-out（V-2 探针前置）。
2. **targetSdk 升级时间表**？—— dataSync 6h/24h 限额（targetSdk 35+）影响保活承诺与 `onTimeout()` 文案。
3. 本次是否**只做 Phase 0**？Phase 1 通知展示层何时启动？
4. 双 9.5 门：本批走 gpter + groker，还是先内部 review？

---

## 9. 交接提示词（复制粘贴给新 agent）

> 下面这段是给 fresh agent 的自包含指令，可直接作为新会话的首条 user message。

```
你将实现 ocdroid（Kotlin / Jetpack Compose / Hilt 的 opencode Android 客户端）的「通知 / 后台 SSE 保活」功能（Track B）。

仓库：/home/mar/personal_projects/ocdroid。先读 AGENTS.md——其硬规则不可违反（每次改动后 ./scripts/check.sh 必过；设备测试仅用模拟器，用前 ./scripts/emulator.sh status 确认未运行再 start、用完 stop；单一 main 分支；版本号 git 派生，禁手改 build.gradle.kts；发版只走 ./scripts/release.sh，外部 push/upload 需用户确认）。

设计已通过 gpter + groker 双 9.5 门，是权威——不要重述或推翻，去【执行】它。按序读：
1. docs/ocmar/specs/2026-07-13-notification-background-fgs-design.md（权威：L1/L2/L3 生命周期状态机、ConnectionIdentity epoch、busy 权威源、abort、channel 矩阵、decorator pipeline）
2. docs/ocmar/specs/2026-07-13-notification-background-dev-design.md（任务清单 P0.1–P3 + §3 通知卡片视觉 + §4 decorator 接口草图 + §5 现状→目标职责拆分）
3. docs/ocmar/specs/2026-07-13-notification-background-code-research.md（§3 改动影响图 + 现状定位）
4. docs/ocmar/specs/2026-07-13-notification-background-requirements.md（FR/NFR/验收 V-1..V-19）
5. docs/ocmar/specs/2026-07-13-notification-background-execution.md（执行入口：阅读顺序/硬约束/本次范围/风险分级/已核验代码锚点/post-v0.9.4 备忘/前置问题）

本次范围 = Phase 0 only（P0.1–P0.10）：让 FGS 正确——前台服务持有 SSE 连接 + 分层生命周期交接 + 权威 busy 源 + 权限声明 + 门禁测试。Phase 1/2/3 除非用户另指示，不做。

执行纪律（最关键）：
- Phase 0 的 10 个任务【强依赖单向流动】（dev-design §1.1）。把它们分成两类：
  (A) 安全增量——可单独 ship、main 保持绿、不碰活体 SSE 路径：AndroidManifest 权限 + <service> 声明（P0.9，需 P0.1 类先存在）、新增通知 channel（ocdroid.session_status / ocdroid.session_complete）、状态栏 drawable、尚未接线的新类骨架（StatusAggregator / StreamingLifecycleCoordinator / ConnectionBootstrapCoordinator / SseEventBridge / ConnectionIdentity，编译通过、无副作用即可）。
  (B) 原子切换——必须一次性、带测试、有回退路径，【绝不在 main 留下半迁移中间态】。核心风险是 SSE 连接所有权迁移，三处协同改：
      1. SSE owner 从 ConnectionCoordinator.sseJob（line 108）上移到 SessionStreamingService；directoryFetchGeneration（line 150）上移为 ConnectionIdentity.epoch（单一真相源，同时守 SSE collector + directory fetch，不允许 CC 私留第二个 generation）；cancelSseForReconfigure（line 793-800）改为 emit service-side epoch bump。
      2. ForegroundCatchUpController.onForegroundChanged(false) 删除 line 150-152 的 cancelSse (background) + CancelSse emit（这是真实断流点，非 AppLifecycleMonitor）；背景分支只留 clearDraft() + backgroundedAtMs（line 149）。SSE 启停只由 StreamingLifecycleCoordinator 驱动。
      3. AppLifecycleMonitor._isInForeground（line 92）默认值从 true 翻 false；ALM 降级为「进程前台信号源 + poller 宿主」，不再当 SSE-cancel 所有者。
  先把 (A) 备齐（骨架 + identity 协议 + bridge），再做 (B) 这一次原子切换。
- 每次 Kotlin/资源改动后 ./scripts/check.sh 必过。原子切换当次必须自带 V-1（dataSync 平台矩阵）+ V-2（directory-status 隔离探针）门禁测试（落 app/src/test 与 app/src/androidTest），不过不得宣布 Phase 0 完成。
- 代码锚点见 execution doc §5（截至 v0.9.4 / 655f4b7 已核验，仍以实际文件为准）。post-v0.9.4 备忘见 §7：loadPendingQuestionsAllWorkdirs 已是权威 reconcile，StatusAggregator 接管时需对齐语义，避免两套权威源。

交付定义：Phase 0 完成 = FGS 正确启动/保活/交接，check.sh 绿，V-1/V-2 门禁测试过。然后【停下汇报】，不要擅自进 Phase 1。发版前过双 9.5 门。

开工第一步：读完上述 5 份文档 + AGENTS.md，然后先产出一份 Phase 0 staging 计划（哪些任务作为安全增量 checkpoint、哪一次是原子切换、每次的 check.sh + 测试节点），经我确认后再写代码。同时先回答 execution doc §8 的前置问题（尤其服务端 /session/status 是否 host-global）。
```

---

## 10. 变更记录

| 日期 | 变更 | 作者 |
|---|---|---|
| 2026-07-13 | 初版（执行入口 + 交接提示词 + 代码锚点核验 + post-v0.9.4 备忘） | orchestrator |
| 2026-07-14 | D4-B：transport readiness + Starting→Ready ownership + reconfigure no-source + teardown/runBlocking/KDoc + tests + rollback docs（见 §11） | fixer |

---

## 11. 回滚纪律（D4-B，2026-07-14）

> CP1-9 + D1/D2/D3/D4-A/D4-B 是**一个不可分割的切换**。D1-D4 改变了 CP9 的契约（`SourceActivation.Ready` 去 state、`OwnershipStartResult.Accepted`→`Ready`、`OwnershipState` 两段式、`TeardownReason.BootstrapFailure`、`StreamingOwnershipGate` synchronized 重构 + `releaseNow`、`teardownAndAwait` 统一、reconfigure 不 emit StartPoller）。**CP9 单独 revert 被禁止**——会复活 B1 unowned window / M3 Unknown hang / M4 stale replacement poll，且因符号删除/改名导致编译失败。

**回滚操作**：

- **merge 前**：discard / close `omos/notify-switchover` 分支。
- **merge 后（squash）**：`git revert <squash-sha>`。
- **merge 后（merge-commit）**：`git revert -m 1 <merge-sha>`。
- **线性连续区间**：CP1…D4-B 在同一连续 commit range → 执行**一次** reviewed revert 覆盖整个区间（不逐 commit revert——中间态不可编译）。
- **禁止**：不得创建并行「legacy SSE owner」runtime flag（双轨复活 gpter-M1 双 SSE 状态机，且不可测）。
- **merge SHA**：合并后记录于此 — `<待合并后填写>`。
- **验证**：revert 后 `./scripts/check.sh` 必须全绿（若失败说明 revert 不完整——扩大到整个连续区间）。
