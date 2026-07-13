# 通知 / 后台 SSE 保活 / 通知卡片视觉 — 需求文档 (ocdroid)

> 状态：**需求文档（面向交付）**。
> 权威设计源：[`2026-07-13-notification-background-fgs-design.md`](./2026-07-13-notification-background-fgs-design.md)（已过 gpter/groker 双 9.5 门，下文简称 **FGS spec**）。本文件不重写其设计，只把它的设计语言翻译成可验收的需求项与用户故事。
> 配套：[`2026-07-13-notification-background-dev-design.md`](./2026-07-13-notification-background-dev-design.md)（开发设计）、[`2026-07-13-notification-background-code-research.md`](./2026-07-13-notification-background-code-research.md)（代码调研 / 改动定位）。
> 调研基线：[`docs/dynamic-island-plan.md`](../../dynamic-island-plan.md)（灵动岛 / 持久通知调研）。

---

## 0. 文档定位

本文件回答「**做什么、为什么、为谁、做到什么程度算交付**」四问，不回答「怎么改代码」（那是开发设计文档的事）也不回答「改哪一行」（那是代码调研文档的事）。任何在 FGS spec 中已钉死的方案（模型 A、L1/L2/L3、busy 全局归并、dataSync FGS、IslandNotifier decorator pipeline），本文件一律作为既定输入引用，不重新论证。

---

## 1. 背景与目标

### 1.1 现状（一句话）

ocdroid 当前的 SSE 连接在 app 进入后台时**主动断开**（`ForegroundCatchUpController.onForegroundChanged(false)` 无条件 emit `CancelSse`，详见代码调研文档 §2.3），进程被杀则流式数据全丢且无自恢复；后台仅靠 `AppLifecycleMonitor` 的 30 秒轮询弹「待授权 / 待回答」与错误通知，**任务进度（busy）、任务完成、连接身份切换后的状态污染**均无任何应用外感知通道。

### 1.2 目标（业务价值）

让 ocdroid 在用户**切到别的 app / 锁屏 / 滑动到多任务清理界面**时仍能：

1. **后台有 active 任务期间**（FGS spec §4.1 的 **L2-active**）继续接收 SSE，流式数据不中断、不丢、回前台不需长 catch-up；
2. 从**状态栏 / 锁屏 / 灵动岛**感知：
   - 「N 个任务进行中」+ 运行时长；
   - 「等待你授权 / 回答」；
   - 「任务完成」；
   - 「连接错误 / session 错误」；
3. **在平台允许的窗口内**（Android 12+ dataSync FGS 的 6h/24h 限额）保持上述能力，**窗口外明示不保证后台自动恢复**，避免给用户假承诺。

### 1.3 非目标

- **导航三项改动**（Sessions 扁平 / Files 项目页 / Chat 顶部标签）属另一 spec，与本 spec 无代码重叠。
- **服务端协议改造**——只消费现有 `/global/event` SSE 与 `/session/status` REST。
- **强制保活 / 抗厂商杀进程**——遵守平台与厂商规则，不做对抗性保活。
- **后台发起新任务**——后台仅观察与 abort，不发起 prompt。

---

## 2. 用户故事

> 每条都标出对应的 FGS spec 章节、状态层（L1/L2/L3）、所属 Phase。

### US-1 后台 / 锁屏瞥见任务进度

> **作为**一个跑了长任务（codex review / 大改）的用户，
> **当**我切到别的 app 或锁屏，
> **我希望**状态栏一条常驻通知告诉我「3 个任务进行中 · 已运行 4:12」，
> **以便**我不必守在 ocdroid 屏幕前，回来时也不会忘记还有任务在跑。

- 覆盖：FGS spec §0/§4.1 L2-active / §6 ongoing / §7 channel `ocdroid.session_status`；
- Phase 0（单 session 形态）+ Phase 1（N 任务聚合 + chronometer + abort）。

### US-2 待授权 / 待回答推送

> **作为**一个被 agent 问「是否执行 rm -rf」或「请选择实现路径」的用户，
> **当** ocdroid 在后台、agent 阻塞等回复，
> **我希望**收到一条高优先级通知（带跳转），点按直达该 session，
> **以便**任务不被我漏回而长时间停滞。

- 覆盖：复用现有 `ocdroid.decisions`（IMPORTANCE_HIGH），由统一 `IslandNotifier`（FGS spec §6）接管 dedup；
- Phase 1（聚合底层接入）。
- **前台不发**（走应用内卡片），见 FGS spec §4.2 / §8。

### US-3 任务完成提醒

> **作为**一个发起任务后切去干别的事的用户，
> **当**任务从 busy → idle，
> **我希望**收到一条「✅ 任务完成」通知（可点击进入），
> **以便**我知道可以回来 review 结果了。

- 覆盖：FGS spec §8 完成通知状态机；
- Phase 1；
- 关键抑制：baseline 初始快照不发、SSE 重连首帧 idle 不发（先 reconcile）、host 切换不发、abort/error/archive 各自终态不发完成、**前台不发**。

### US-4 错误提醒

> **作为**用户，
> **当**连接错误 / SSE 长时间无法连接 / session.error，
> **我希望**收到一条错误通知，
> **以便**我知道消息可能不是最新，可以主动重连。

- 覆盖：复用现有 `ocdroid.errors`（IMPORTANCE_DEFAULT）+ FGS spec §11 SSEConnectionExhausted 长期重试；
- Phase 1（迁移到统一 notifier）。

### US-5 一键 abort 进行中的任务

> **作为**用户，
> **当**通知显示「1 个任务进行中」，
> **我希望**通知上有「取消」按钮直接 abort，
> **以便**我能在不打开 app 的情况下停止跑歪的任务。

- 覆盖：FGS spec §9 abort 动作；
- Phase 1；
- **单 busy → 直接 abort；多 busy → 不显示单一 Abort、不做无确认「全部取消」，点按跳应用任务列表**（MVP）。

### US-6 灵动岛 / 超级岛渐进（Pixel / 三星 / 小米用户）

> **作为** Pixel / 三星 One UI 8+ / 小米 HyperOS 2/3 用户，
> **我希望** ocdroid 的进行中状态能进「Live Updates / Now Bar / 灵动岛」而不仅是状态栏一条，
> **以便**锁屏 / AOD 也能瞥见。

- 覆盖：FGS spec §12 decorator pipeline、§13 Phase 2/3、dynamic-island-plan.md §2.2/§4；
- Phase 2（Android 16 Live Updates，ProgressStyle + status chip）；
- Phase 3（小米超级岛 `miui.focus.param`，需审核白名单）。

### US-7 后台保活不假承诺

> **作为**用户，
> **当**系统 `onTimeout()` 或厂商杀进程后，
> **我希望** ocdroid **不要假装**仍在后台跑（例如停止 ongoing 通知、不再发完成通知），
> **以便**我对「后台能力」有准确预期。

- 覆盖：FGS spec §4.1 L3 teardown、§15 已知限制；
- Phase 0 起即生效（生命周期状态机本身）。

---

## 3. 功能需求

> 编号 `FR-x`。每条标出对应 FGS spec 章节、所属 Phase、验收锚点。

### 3.1 后台 SSE 保活（FGS 托管）

| ID | 需求 | spec | Phase |
|---|---|---|---|
| FR-1 | 必须有 `SessionStreamingService`（ForegroundService, `dataSync` 类型）持有 `SSEClient` 连接生命周期，进程级（不绑 Activity） | §1 / §5 | 0 |
| FR-2 | SSE 事件经进程级 `events: SharedFlow<Result<SSEEvent>>` 广播；fold 仍由 `SessionSyncCoordinator` 处理（不搬） | §1 | 0 |
| FR-3 | 必须有 `SseEventBridge` 校验 `ConnectionIdentity` 后 emit `OnSseEvent`；identity 不得在 fold 前被剥掉 | §1 / §2 | 0 |
| FR-4 | 连接身份 `ConnectionIdentity(epoch, serverGroupFp, normalizedWorkdir, endpointFp)` 为单一真相源，reconfigure 走严格 6 步顺序（increment epoch → 失效旧 collector → 重建 repository → 清旧 host 状态 → 新 collector 绑新 identity → 每事件携 identity） | §2 | 0 |
| FR-5 | 分层生命周期 L1（前台）/ L2-active（后台 busy）/ L2-idle（后台全 idle + 45s debounce，外壳保持）/ L3（无 FGS） | §4.1 | 0 |
| FR-6 | L1→L2 提升时序：前台进入 busy/retry **立即** startForeground（合法）；前台无任务进后台**不**尝试后台提升，直入 L3 | §4.2 | 0 |
| FR-7 | `AppLifecycleMonitor._isInForeground` 不再默认 true；Activity started-count 是前台事实源；配置变化用带延迟的 process-lifecycle 语义 | §4.3 | 0 |
| FR-8 | 统一交接顺序（持锁、串行）：新 source active + notifier 切换成功后才关旧 source，**不存在「两边都没数据源」的中间态** | §4.4 | 0 |
| FR-9 | START_STICKY bootstrap：null Intent 安全；冷启动按 §4.3 = 后台（合法 FGS-start 上下文）；同步读最小持久配置 → 立即 startForeground 占位通知 → 异步 bootstrap（tunnel/health/TOFU/status）→ busy 保持 FGS / 全 idle 进 L3 | §5 | 0 |
| FR-10 | TOFU 待决且无 Activity 时不无限等 UI deferred，进入明确 degraded 状态，占位通知加 action 引导用户打开 Activity 完成信任 | §5 / §10 | 0 |

### 3.2 权威 busy 数据源与 status merge

| ID | 需求 | spec | Phase |
|---|---|---|---|
| FR-11 | busy 是**全局**概念，复合键 `(serverGroupFp, workdir, sessionId) → Fresh\|Busy\|Retry\|Idle\|Unknown` | §3 | 0 |
| FR-12 | Phase 0 主路径：用现有**全局** `getSessionStatus()` 一次；用 `session.directory` 把 sessionId 归并到 workdir；**请求失败 → 全局 Unknown，不得进 idle 宽限期** | §3 | 0 |
| FR-13 | status TTL（如 30s）；只有**所有已登记 workdir 都取得新鲜 + 成功 idle** 才进 L2-idle 宽限期 | §3 | 0 |
| FR-14 | 多 workdir pending 轮询独立于 status（`computeQuestionFanOutWorkdirs` / `loadPendingQuestionsAllWorkdirs`） | §3 | 0（保持现状） |
| FR-15 | status merge 时序：每条状态带「来源时间」（SSE 用事件到达 monotonic 时钟，REST 用 request-start epoch + receive time）；REST 响应**不得覆盖**其 request-start 之后到达的更新 SSE 状态；所有 REST 响应校验 epoch | §3.1 | 0 |

### 3.3 ongoing 状态通知

| ID | 需求 | spec | Phase |
|---|---|---|---|
| FR-16 | 单一固定 `SESSION_STATUS_NOTIFICATION_ID`；error 固定 `4242`；decisions 复合键；completion 稳定复合键 | §7 | 0/1 |
| FR-17 | channel 矩阵：`ocdroid.session_status`（LOW，ongoing）/ `ocdroid.session_complete`（DEFAULT，autoCancel，**新增**）/ `ocdroid.decisions`（HIGH，现有）/ `ocdroid.errors`（DEFAULT，现有） | §7 | 0/1 |
| FR-18 | ongoing 内容随层与子态：L1-idle = 无（或可选 LOW「已连接」）；L1-busy / L2-active = 「N 个任务进行中」+ chronometer + abort；L2-idle = 降级 LOW「空闲监听」；L3 = 无 ongoing（poller only） | §4.1 / §7 | 0（单 session）+ 1（聚合） |
| FR-19 | chronometer 起点 = 该通知聚合内**最早 busy transition**（非通知重建时间） | §7 | 1 |
| FR-20 | 所有 `PendingIntent` 携带 `(serverGroupFp, workdir, sessionId)` identity，执行时复核（host 可能已切） | §7 / §9 | 1 |

### 3.4 完成 / 错误 / decision 通知

| ID | 需求 | spec | Phase |
|---|---|---|---|
| FR-21 | 完成通知：仅当**同一 connection identity** 下明确观察过 busy/retry 后观察到**权威 idle** 才发 | §8 | 1 |
| FR-22 | 完成通知抑制：baseline 初始快照不发 / SSE replay 或重连首帧 idle 先 reconcile 不发 / host 切换不发 / abort/error/archive 各自独立终态不发完成 | §8 | 1 |
| FR-23 | 前后台抑制：**前台不发系统 completion**（走应用内反馈）；后台当前或非当前 session 都可发（transition 去重）；短暂 background 加前后台稳定 debounce | §8 | 1 |
| FR-24 | abort 策略：单 busy → 直接 abort；多 busy → **不**显示单一 Abort、**不**做无确认「全部取消」，点按跳应用任务列表；action 执行时**重新查询 identity/status**，不盲用通知创建时的 ID 列表；无 Activity 时由 `BroadcastReceiver`/Service 内调 `OpenCodeRepository.abortSession` | §9 | 1 |
| FR-25 | 错误：复用 `ocdroid.errors`；`SSEConnectionExhausted` 走 service 级长期重试 / degraded（不等进程重启）；delta overflow 时 session 标 dirty + 强制 REST reconcile，**控制类事件不被挤出**（独立有界 Channel，满载 suspend 不 DROP_OLDEST） | §11 | 0/1 |

### 3.5 通知卡片视觉（详见开发设计文档 §3）

| ID | 需求 | spec | Phase |
|---|---|---|---|
| FR-26 | ongoing 通知有「状态胶囊」文案（如「3 个任务进行中 · workdir-A / workdir-B」）+ chronometer + 单/多任务 abort 分流 Action | §7 / §9 | 1 |
| FR-27 | 完成通知有「✅ <session 标题> 已完成」标题 + 「点按查看」正文，autoCancel，点击 deep-link 进入对应 session | §8 | 1 |
| FR-28 | Live Updates（API36 + AndroidX Core 1.17+）：ProgressStyle + status chip（运行中 / 等待）+ chronometer，**无确定性进度**（AI 任务无 0-100%）；`setRequestPromotedOngoing(true)` + 声明 `POST_PROMOTED_NOTIFICATIONS`；增强失败回退 base ongoing | §12 / §13 Phase 2 | 2 |
| FR-29 | 小米超级岛（`notification_focus_protocol ∈ {2,3}`）：extras 附加 `miui.focus.param` JSON（`bigIslandArea` / `smallIslandArea`）；任一增强失败回退 base ongoing；走小米审核白名单流程 | §12 / §13 Phase 3 | 3 |
| FR-30 | IslandNotifier 为**可组合 decorator pipeline**（叠加而非互斥）：base ongoing → optional LiveUpdate → optional Xiaomi | §12 | 1（接口）/ 2 / 3 |

### 3.6 Settings 与权限引导

| ID | 需求 | spec | Phase |
|---|---|---|---|
| FR-31 | Settings 通知页展示**每 channel** 状态 + 跳系统 channel 设置（不止总权限） | §7 / groker-M2 | 1 |
| FR-32 | 通知权限被拒时给降级 / 引导（如 ongoing 不展示但仍保 SSE / 完成通知无通道则不发） | §7 / groker-M2 | 1 |
| FR-33 | Settings 文案修复：明确「后台保活受 Android 12+ dataSync 6h/24h 与厂商策略限制，不保证永久」 | §15 | 1 |
| FR-34 | 声明权限：`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`（Phase 0）；`POST_PROMOTED_NOTIFICATIONS`（Phase 2，API36+） | §13 / dynamic-island-plan §5 | 0 / 2 |

---

## 4. 非功能需求

### 4.1 电量与资源

- **NFR-1**：L2-idle（无 active 任务）必须 `cancelSse`，**不**保留长连接（避免无意义耗电）；
- **NFR-2**：L3（onTimeout / 用户关后台 / disconnect）必须 `stopForeground` + `stopSelf`；
- **NFR-3**：foreground catch-up 与 SSE reconcile 不应在 L2-active 窗口内重复触发（单一串行状态机保证）；
- **NFR-4**：delta overflow 恢复应限频（短 debounce 后强制 REST reconcile，不每事件触发）。

### 4.2 平台与厂商限制

- **NFR-5**：Android 12+ 后台启动 FGS 限制——`startForegroundService()` 在 background 状态会抛 `ForegroundServiceStartNotAllowedException`，**必须**在 L1（前台）就完成提升（FR-6）；
- **NFR-6**：dataSync FGS 在 targetSdk 35+ 受 6h / 24h 限额，`onTimeout()` 必须有回落（poller + 通知用户）；
- **NFR-7**：厂商（小米 / 华为）dataSync FGS 长连接杀进程策略差异——纳入实机测试矩阵（验收门禁 V-9）；
- **NFR-8**：通知权限 `POST_NOTIFICATIONS`（API 33+）运行时授予，被拒时降级（FR-32）；
- **NFR-9**：`START_STICKY` 仅覆盖「进程被杀后系统可能拉起」，**不**保证及时、不保证 force-stop 后恢复——不将其列为确定恢复入口。

### 4.3 兼容性

- **NFR-10**：minSdk 不变；targetSdk 升级时间表与 Phase 0 是否同发由发布计划决定（FGS spec §15）；
- **NFR-11**：Live Updates 仅在 API 36+ 生效，API<36 由 AndroidX Core 1.17+ 自动回退；
- **NFR-12**：小米超级岛仅 HyperOS 2/3 生效，其它机型无声 fallback 到 base ongoing；
- **NFR-13**：三星 One UI 8+ 直接复用 Live Updates API（白送适配，dynamic-island-plan §2.2）。

### 4.4 可观测 / 可诊断

- **NFR-14**：每个生命周期层切换（L1→L2-active→L2-idle→L3 等）有 DebugLog；
- **NFR-15**：每个通知发布 / 取消 / 替换有 DebugLog（含 channel、ID、复合业务键）；
- **NFR-16**：status merge 时序冲突（旧 REST idle 覆盖新 SSE busy 被拦截）有 DebugLog；
- **NFR-17**：identity 校验失败（跨 host 事件、stale collector 帧）有 DebugLog。

### 4.5 安全 / 隐私

- **NFR-18**：通知正文不得泄漏 session 全文消息（仅标题 / 状态 / 「N 任务」聚合）；
- **NFR-19**：PendingIntent 必须用 `FLAG_IMMUTABLE`；
- **NFR-20**：degraded TOFU 占位通知不得在正文暴露 hostPort / SPKI（仅引导用户打开 Activity）。

---

## 5. 验收标准

> 编号 `V-n`。每条标出对应 FGS spec §14 门禁项、关联的 FR。

### 5.1 Phase 0 强制门禁（FGS spec §14.0）

- **V-1（dataSync 平台矩阵）**：覆盖当前 targetSdk **与** 未来 35+；逐项定义行为——`onTimeout()`、后台重启限制（`ForegroundServiceStartNotAllowedException`）、task-swipe、force-stop、通知权限拒绝、OEM（小米/华为）后台杀进程/长连接策略。未通过**不得 ship Phase 0**。（FR-5/6/9, NFR-5/6/7）
- **V-2（directory-status 隔离探针）**：两 workdir 各有 active session 时，确认 FR-12 主路径的 status 查询能**同时**反映两者（全局归并或 fan-out 皆须过此隔离测试）；失败则不得实施「全 idle」判定。（FR-11/12/13）

### 5.2 行为 / 回归门禁（FGS spec §14.1）

- **V-3（host 切换不污染）**：旧帧 `server.connected` / `session.status` / `message.*` 不污染新 host；通知聚合器校验 identity 拦截。（FR-3/4, FR-21）
- **V-4（Activity 重建）**：idle 帧不丢；SSC 由进程级 owner 持续 fold。（FR-2）
- **V-5（后台非当前 workdir busy）**：全局 busy 源识别；**不误停 SSE**。（FR-11/12, FR-5）
- **V-6（sticky null Intent）**：占位通知先贴 → 异步 bootstrap。（FR-9）
- **V-7（SSEConnectionExhausted）**：service 级长期重试，不等进程重启。（FR-25）
- **V-8（通知权限拒绝 / FGS 启动被拒）**：降级路径可用。（FR-32, NFR-5）
- **V-9（poller 交接双向）**：L2-active↔L2-idle 切换无空窗、无双发。（FR-8）
- **V-10（sessionId 跨 host 碰撞）**：identity 校验拦截。（FR-3/4）
- **V-11（聚合 abort 单/多分流）**：单 busy 直接 abort、多 busy 跳列表；执行时复核 identity。（FR-24）
- **V-12（delta overflow）**：session dirty + 强制 reconcile；控制事件不被挤出。（FR-25）
- **V-13（L1→L2 提升）**：前台进入 busy 时成功提升 FGS，随后 Activity 进后台**不**触发 `ForegroundServiceStartNotAllowedException`；前台无任务进后台直入 L3、不尝试后台提升。（FR-6）

### 5.3 通知卡片视觉验收

- **V-14（ongoing 单/多任务胶囊）**：1 个 busy 显示「1 个任务进行中」+ 单 abort Action；≥2 个 busy 显示「N 个任务进行中」+ 无单一 abort、点按跳任务列表；chronometer 起点为最早 busy transition 而非通知重建时间。（FR-18/19/24/26）
- **V-15（完成通知 deep-link）**：点按完成通知进入对应 session；前台时不发；baseline / SSE 重连首帧 idle / host 切换 / abort / error / archive 都不发。（FR-21/22/23/27）
- **V-16（错误通知）**：错误通知正文清晰、autoCancel、固定 ID 替换不堆叠。（FR-25）
- **V-17（Live Updates，Phase 2）**：API36+ 设备看到状态栏 chip + 锁屏/AOD 同步；API<36 自动回退到 base ongoing；增强失败回退不崩。（FR-28, NFR-11）
- **V-18（小米超级岛，Phase 3）**：HyperOS 2/3 设备 `focus_protocol∈{2,3}` 时显示岛；其它机型 / 非白名单设备无声回退 base ongoing。（FR-29, NFR-12）
- **V-19（Settings 通知页）**：每 channel 单独状态 + 跳系统 channel 设置；权限拒绝有降级文案。（FR-31/32/33）

---

## 6. 范围与优先级

> 与 FGS spec §13 分阶段一致。Phase 0 是「让 FGS 正确运行的最小闭环」，已包含 busy 权威源与生命周期状态机（FGS 无法在没有它们时正确运行）；Phase 1 只加展示层；Phase 2/3 为渐进增强附录。

### 6.1 Phase 0（让 FGS 正确的最小闭环）— 必发

| 项 | 描述 | 关联 FR / V |
|---|---|---|
| 0.1 | `SessionStreamingService` 骨架 + 进程级 `events` SharedFlow | FR-1/2, V-6 |
| 0.2 | `SseEventBridge`（identity 校验 → OnSseEvent） | FR-3, V-3/10 |
| 0.3 | 连接身份协议（`ConnectionIdentity` + 6 步 reconfigure） | FR-4, V-3 |
| 0.4 | 权威 busy 源（全局 `getSessionStatus` + workdir 归并 + TTL） | FR-11/12/13, V-2/5 |
| 0.5 | 生命周期状态机 L1/L2/L3（`StreamingLifecycleCoordinator`） | FR-5/6/7/8, V-1/13 |
| 0.6 | START_STICKY bootstrap（占位通知 + 异步 health/TOFU/status） | FR-9/10, V-6 |
| 0.7 | 交接（统一串行）+ 进程级 reducer（fold 不搬） | FR-2/8, V-4/9 |
| 0.8 | 固定低干扰 ongoing（单 session 形态） | FR-16/17/18 |
| 0.9 | TOFU 抽离（CC 私有 → 应用级共享 `ConnectionBootstrapCoordinator`） | FR-10 |
| 0.10 | `ForegroundCatchUpController` 背景分支去无条件 `CancelSse`（改由生命周期协调器驱动） | FR-5/8 |
| 0.11 | 权限声明 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | FR-34 |
| 0.12 | dataSync 平台矩阵 + directory-status 隔离探针（**强制门禁**） | V-1/2 |

### 6.2 Phase 1（通知展示层）— 必发

| 项 | 描述 | 关联 FR / V |
|---|---|---|
| 1.1 | 全 workdir 聚合 ongoing「N 任务」+ chronometer + abort 分流 | FR-18/19/24/26, V-14 |
| 1.2 | 完成通知（§8 状态机） | FR-21/22/23/27, V-15 |
| 1.3 | Settings 文案 / channel 修复（每 channel 状态 + 跳系统） | FR-31/32/33, V-19 |
| 1.4 | 错误通知迁移到统一 `IslandNotifier` + dedup store | FR-25, V-16 |
| 1.5 | decisions 通知迁移到统一 `IslandNotifier`（保留 HIGH importance） | FR-20 |
| 1.6 | 新增 channel `ocdroid.session_complete`（DEFAULT, autoCancel） | FR-17 |
| 1.7 | IslandNotifier decorator 接口骨架（base ongoing only） | FR-30 |

### 6.3 Phase 2（Live Updates 附录）— 选做，非 MVP 门

| 项 | 描述 | 关联 FR / V |
|---|---|---|
| 2.1 | 升级 AndroidX Core ≥ 1.17.0 | FR-28, NFR-11 |
| 2.2 | `NotificationCompat.ProgressStyle` + status chip + chronometer | FR-28, V-17 |
| 2.3 | 声明 `POST_PROMOTED_NOTIFICATIONS`（API36+） + `setRequestPromotedOngoing(true)` | FR-28/34 |
| 2.4 | LiveUpdateDecorator 实现 + 回退 base ongoing | FR-28/30, V-17 |

### 6.4 Phase 3（小米超级岛附录）— 选做，需审核白名单

| 项 | 描述 | 关联 FR / V |
|---|---|---|
| 3.1 | 检测 `Settings.System.getInt("notification_focus_protocol", 0) ∈ {2,3}` | FR-29 |
| 3.2 | extras 附加 `miui.focus.param` JSON（`bigIslandArea` / `smallIslandArea`） | FR-29, V-18 |
| 3.3 | 走小米审核流程（方案提报 → 白名单 → 上线） | FR-29 |
| 3.4 | XiaomiIslandDecorator 实现 + 回退 base ongoing | FR-29/30, V-18 |

### 6.5 优先级矩阵

```
必发 → Phase 0 (FGS 正确闭环)  ──┐
                                 ├─→ MVP（后台保活 + 基础 ongoing + 完成/错误/decision）
必发 → Phase 1 (通知展示层)    ──┘

选做 → Phase 2 (Live Updates)  ── API36+ 设备占比 + AndroidX Core 1.17 稳定后
选做 → Phase 3 (小米超级岛)    ── 小米用户占比高 + 愿走审核流程时
```

---

## 7. 术语表

| 术语 | 含义 | 出处 |
|---|---|---|
| FGS | Foreground Service | Android 平台 |
| dataSync | FGS 类型之一，用于数据同步；Android 12+ 启动受限、targetSdk 35+ 受 6h/24h 限额 | 平台 |
| L1/L2/L3 | FGS spec §4.1 三层生命周期（前台 / 后台 active / 无 FGS） | FGS spec |
| L2-active / L2-idle | L2 内子态：有 active 任务保 SSE / 全 idle 45s 后撤 SSE 但外壳保持 | FGS spec §4.1 |
| ongoing 通知 | 状态栏常驻、不可滑动清除的通知，FGS 必备 | 平台 |
| Live Updates | Android 16（API 36）`Notification.ProgressStyle` + `setRequestPromotedOngoing(true)`；状态栏 chip + 锁屏/AOD | dynamic-island-plan §2.2 |
| 小米超级岛 / 焦点通知 | HyperOS 2/3 厂商专享，extras `miui.focus.param` | dynamic-island-plan §2.1 |
| IslandNotifier | FGS spec §6/§12 的统一通知器，decorator pipeline | FGS spec |
| ConnectionIdentity | `(epoch, serverGroupFp, normalizedWorkdir, endpointFp)` 进程级单一真相源 | FGS spec §2 |
| TOFU | Trust On First Use，自签证书首次信任 | ocdroid §tofu R2 |
| decorator pipeline | base → LiveUpdate → Xiaomi 叠加增强，任一失败回退 | FGS spec §12 |
| serverGroupFp | 主机分组指纹，跨 profile 共享 workdir 记忆的键 | ocdroid R-20 Phase 5 |

---

## 8. 变更记录

| 日期 | 变更 | 作者 |
|---|---|---|
| 2026-07-13 | 初版（基于 FGS spec 与 dynamic-island-plan 调研） | Fixer agent |
