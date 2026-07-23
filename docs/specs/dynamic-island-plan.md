# 灵动岛 / 持久通知 接入方案

> 状态：方案已定，待实施。前置依赖是 ForegroundService 保活 SSE。
> 调研日期：2026-07。行号引用基于 v0.6.3，实施前需 diff 确认。

---

## 1. 背景与目标

让 ocdroid 在用户切到别的 app 或锁屏时，仍能从状态栏 / 锁屏 / 灵动岛感知 AI 编码任务的状态：哪些 session 在跑（busy）、是否需要授权/回答。核心价值场景是"后台/锁屏也能瞥见任务进度与待办"。

## 2. 调研结果

### 2.1 小米 HyperOS 灵动岛（厂商专享，极致体验）

小米的"灵动岛"按 OS 版本分两阶段：

| HyperOS 版本 | 官方名称 | 状态 |
|---|---|---|
| HyperOS 2（MIUI 14→） | **焦点通知** (Focus Notification) | 成熟稳定 |
| HyperOS 3（2025+） | **小米超级岛** (Xiaomi Super Island / HyperIsland) | 正式上线 |

- **对第三方开放**，但有审核 + 白名单机制。接入方式：标准 `Notification` + 在 `extras` 塞 `miui.focus.param`（JSON，含 `bigIslandArea` / `smallIslandArea`），或通过 MIPush 服务端下发。
- **流程**：方案提报 → 场景审核 → 开通 MIPush → 设备白名单调试 → 上线验证。OS2 焦点通知调试需邮件 `mipush-permission@xiaomi.com` 申请临时权限。
- **准入**：严禁营销/广告；需"用户主动发起 + 有明确生命周期（≤12h）"。ocdroid 的 AI 任务符合准入。
- **机型**：不限机型，以系统版本为准。
- 社区库（简化 JSON 构建，**不能绕过审核**）：`HyperIsland-ToolKit`（Kotlin DSL）。
- 实时更新：`updatable=true` + 更新 `sequence`；岛默认 1h 超时。

### 2.2 通用 Android 方案对比（厂商无关）

| 方案 | 最低版本 | 接近"岛" | 厂商无关 | 适合 ocdroid |
|---|---|---|---|---|
| **❶ Ongoing 通知 + FGS** | Android 8+ | ⭐ | ✅ 全机型 | ✅ 基座必做 |
| **❷ Android 16 Live Updates** | API 36 / XOX 回退 | ⭐⭐⭐⭐ | ⚠️ 需 OEM 集成 | ✅ 推荐主方案 |
| **❸ 小米超级岛** | HyperOS 2/3 | ⭐⭐⭐⭐⭐ | ❌ 仅小米 | ✅ 极致增强 |
| ❹ Bubble API | Android 11+ | ⭐⭐ | ✅ | ❌ 仅聊天场景 |
| ❺ SYSTEM_ALERT_WINDOW 自绘 | 4.4+ | ⭐⭐⭐⭐⭐ | ✅ 但厂商限制 | ❌ 权限复杂不稳 |
| ❻ 第三方悬浮窗库 | — | ⭐⭐⭐ | ⚠️ | ❌ 无成熟 SDK |

**关键结论**：
- **Android 16 Live Updates**（`Notification.ProgressStyle`，API 36；`NotificationCompat.ProgressStyle` 在 AndroidX Core 1.17.0+ 提供 API<36 回退）是 2026 年最接近 iOS Dynamic Island 的通用能力——状态栏芯片 + 锁屏/AOD + 分段进度，且 **Samsung One UI 8+ 直接复用此 API**（白送三星适配）。
- 传统 ongoing 通知**不是**灵动岛等价物（只是通知栏一条），但作为 100% 兜底基座必要。
- Bubble 不适合（设计目标是聊天头像）。
- 所有第三方"通用灵动岛"本质是 `NotificationListenerService` + `SYSTEM_ALERT_WINDOW`，不稳定、无成熟 SDK、厂商管控多，不建议集成。
- AI 任务**无确定性进度**（不像下载有 0-100%），所以 Live Updates 的 `ProgressStyle` 分段进度在 ocdroid 场景用不太上；更该用**状态胶囊 + chronometer 计时 + 动态正文 + Action 按钮**。

### 2.3 ocdroid 可观测状态清单（通知可显示的数据源）

| 类别 | 字段 | 来源 |
|---|---|---|
| 标题 | session 标题/模型名/agent 名 | `Session.title`、`ChatState.currentModel`、`Message.agent` |
| **状态** | busy / retry / idle | `SessionStatus.type`（`Session.kt:57`） |
| 连接 | Connected/Disconnected/Reconnecting | `ConnectionState.connectionPhase` |
| 流式中 | streamingPartTexts 非空 | `ChatState.streamingPartTexts` |
| 思考中 | streamingReasoningPart | `ChatState.streamingReasoningPart` |
| **等待用户** | 待授权 / 待回答 | `pendingPermissions` / `pendingQuestions` |
| 进度 | token 总量 / 上下文% / cost | `Message.tokens`、`ContextUsage.percentage`、`Message.cost` |
| 错误 | session.error / Message.error | SSE `session.error` |
| 取消 | abortSession API | `OpenCodeApi.abortSession`（已存在） |

### 2.4 ⚠️ ocdroid 后台执行能力现状（关键约束）

**ocdroid 当前不具备后台继续执行 AI 任务的能力**：
- ❌ 无任何 `ForegroundService` / `WorkManager` / 保活机制。
- 🔴 **SSE 连接在 app 进入后台时主动断开**（`ConnectionCoordinator.cancelSse`，`AppLifecycleMonitor` onEnterBackground）。进程被杀则流式数据全丢且无法恢复。
- ✅ **已有**一套后台通知：`AppLifecycleMonitor` 每 30 秒轮询，专门弹"等待权限/回答"的高优先级通知（频道 `ocdroid.decisions`，`IMPORTANCE_HIGH`）+ 错误通知（`ocdroid.errors`）。
- 网络层：OkHttp + Retrofit。SSE：0 超时（无限）、最多 10 次退避重试、30s 心跳看门狗。

**含义**：纯做通知 UI，后台时拿不到任何新状态，通知只显示离开前台那一刻的快照，很快过时。**要让灵动岛/持久通知真正有用，必须先补一个 ForegroundService 把 OkHttp SSE 保活**——这是最大的一块前置工作（中偏大规模），远大于通知 UI 本身。

## 3. 已定方案（用户决策）

持久通知 / 灵动岛显示内容（精简，其余不显示）：
- **busy 状态的 session 总数**（有任务在跑时显示"N 个任务进行中"之类）
- **全 idle 时显示另一种样式**（无任务运行，静默/弱化样式）
- **有授权或问题时显示**（复用/强化现有 permission/question 通知）

## 4. 处理思路：三层递进（共用一个 Notification 对象）

```
第3层 小米超级岛  ── HyperOS 2/3，extras 附加 miui.focus.param（需审核）
第2层 Live Updates ── Android 16+，ProgressStyle + setRequestPromotedOngoing(true) + Status Chip
第1层 Ongoing + FGS ── 全机型兜底，NotificationCompat
```

三层**不互斥**：同一个 `notificationId` 的通知对象，根据设备能力层层叠加扩展参数。架构上抽象 `IslandNotifier` 接口，按 `Build.VERSION.SDK_INT`（≥36 用 Live Updates）和 `Settings.System.getInt("notification_focus_protocol")`（2/3 用超级岛）选择实现。

**核心洞察**：灵动岛在 ocdroid 是"锦上添花、前置依赖较重"的功能——真正挡路的是 SSE 后台保活，不是通知 UI。优先级应是 **ForegroundService 保活 > ongoing 通知 > Live Updates > 小米超级岛**。

## 5. 待办事项（分阶段）

### 阶段 0：前置基建（必做，解锁全部价值）
- [ ] 新增 `ForegroundService`（如 `SessionStreamingService`）承载 SSE 连接，app 进后台不断流。
- [ ] 声明权限：`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`（API 34+）；`POST_NOTIFICATIONS`（已有）。
- [ ] `ConnectionCoordinator` / `AppLifecycleMonitor` 改造：onEnterBackground 时由 FGS 托管 SSE，而非 cancelSse；进程恢复时重连。
- [ ] FGS 发一条 ongoing 通知作为前台服务必需通知（同时即第1层基座）。
- [ ] 风险：FGS 保活受系统/厂商后台限制；需测试进程被杀后的 SSE 恢复（可能需结合 `START_STICKY` 或重连逻辑）。

### 阶段 1：第1层 Ongoing 通知基座（全机型）
- [ ] ongoing 通知显示：**busy session 总数 / 全 idle 样式 / 等待授权·问题**（已定方案）。
- [ ] 复用现有 `ocdroid.decisions` / `ocdroid.errors` 通知通道，新增一条 `ocdroid.session_status`（`IMPORTANCE_LOW`，常驻不响）。
- [ ] Action 按钮：取消任务（`abortSession`）/ 批准授权。
- [ ] `setUsesChronometer` 显示运行时长。

### 阶段 2：第2层 Live Updates（Android 16+，可选增强）
- [ ] 升级 AndroidX Core ≥ 1.17.0，用 `NotificationCompat.ProgressStyle`（API<36 自动回退）。
- [ ] 声明 `POST_PROMOTED_NOTIFICATIONS`，调 `setRequestPromotedOngoing(true)`。
- [ ] 状态栏芯片显示"运行中/等待"；锁屏/AOD 同步（Pixel/三星 One UI 8+ 自动获得）。
- [ ] 注意：ocdroid 无确定性进度，ProgressStyle 的分段进度用不上，重点用 Status Chip + chronometer + Action。

### 阶段 3：第3层 小米超级岛（可选，需审核）
- [ ] 检测 `Settings.System.getInt("notification_focus_protocol", 0)` == 2/3。
- [ ] 在标准通知 extras 附加 `miui.focus.param` JSON（`bigIslandArea` / `smallIslandArea`）。
- [ ] 走小米审核流程（方案提报 → 白名单 → 上线）。
- [ ] 可用 `HyperIsland-ToolKit` 简化 JSON 构建。
- [ ] 投入判断：仅当用户群小米占比高且愿走审核时投入。

## 6. 参考资料

- 小米超级岛开发指南：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131
- 小米超级岛业务介绍/准入：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2140
- 小米超级岛接入流程（白名单）：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132
- Android 16 Live Updates：https://developer.android.com/develop/ui/views/notifications/live-update
- `Notification.ProgressStyle`：https://developer.android.com/reference/android/app/Notification.ProgressStyle
- `NotificationCompat.ProgressStyle`：https://developer.android.com/reference/androidx/core/app/NotificationCompat.ProgressStyle
- Samsung Now Bar + Live Updates：https://www.androidauthority.com/samsung-now-bar-double-apps-3580334/
