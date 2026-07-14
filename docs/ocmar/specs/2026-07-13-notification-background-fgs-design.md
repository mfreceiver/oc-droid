# 通知 / 应用外通知 / 后台运行 设计 (ocdroid)

> 状态：**spec（经 gpter / groker 评审，目标 9.5 门）**。本 spec 只管「通知 + 后台 SSE 保活」。
> 导航三项改动（Sessions 扁平 / Files 项目页 / Chat 顶部标签）已拆独立 spec，与本 spec 无代码重叠。
> 依据：`docs/dynamic-island-plan.md`、`ConnectionCoordinator`、`SSEClient`、`AppLifecycleMonitor`、`SessionSyncCoordinator`、`ForegroundCatchUpController`、`AppCore`。
> 评审基线：gpter 6.8→8.7、groker 7.6→8.6；本稿吸纳两轮全部 BLOCKING + MAJOR。

---

## 0. 目标与范围

> 让 ocdroid 在切到别的 app / 锁屏时仍能：①**后台有 active 任务期间（L2-active）**继续接收 SSE（流式不断），并在 L2-idle 窗口内可恢复；②从状态栏/锁屏/灵动岛感知「N 个任务进行中 / 等待授权·回答 / 任务完成 / 错误」。（L3 / 前台 idle 进后台**不**保证 SSE，见 §4.1。）

**范围**：ForegroundService 托管 SSE + ongoing 通知 + 完成通知 + Settings 文案修复 + Live Updates（附录）+ 小米岛（附录）。

**不在范围**：导航三项改动、服务端协议改造（仅消费现有 API）。
**跨 spec 依赖注记**：`connectWorkdir`（仅登记项目、不建会话）的拆分属导航 spec；导航落地前 `SessionViewModel.createSessionInWorkdir` 仍为「draft + 登记 + prefetch」（名实不符），Files「添加项目」仍走该 draft 路径——本 spec 不改它。

---

## 1. 架构：模型 A 收敛（连接所有权迁移，reducer 不动）

> 闭合 gpter-M1 / groker-M1：Service 只拥有连接与流，fold 不搬。

新建 `SessionStreamingService`（ForegroundService）。

- **Service 三职责**：① 拥有 `SSEClient` 连接生命周期（connect / cancel / reconfigure）；② 暴露进程级事件 `SharedFlow<Result<SSEEvent>>`（`events`）；③ 发 FGS 通知。
- **fold 不搬**：`SessionSyncCoordinator`（已是 `@Singleton` 纯 reducer）继续 fold。唯一桥接点（闭合 groker-M1 / gpter-MAJOR#2）：
  > 新增 `SseEventBridge`（`@UiApplicationScope` 单例）collect `Service.events` → 校验 `ConnectionIdentity` → `emit(ControllerEffect.OnSseEvent)` → 现有 `AppCore` / `SessionSyncCoordinator` 路径**完全不变**。
  - **identity 不得在 fold 前被剥掉**（gpter-MAJOR#2）：`Service.events` 与 `ControllerEffect.OnSseEvent` 携带 `IdentifiedSseEvent(identity: ConnectionIdentity, event: SSEEvent)`；bridge 做唯一强校验，effect 仍带 identity，`SessionSyncCoordinator.fold` 可二次校验。不能在 bridge 剥掉 identity 后再声称 SSC 会校验。
- `ConnectionCoordinator` 保留 health / `loadInitialData` / TOFU gate（见 §10 抽离），仅丢掉 `sseJob`。
- `ForegroundCatchUpController.CancelSse` 路径保留驱动 `SseSyncState.reconcileGap`；**Service 断连 ≠ 丢弃 `SharedStateStore`**。

### 1.0 协作者拆分（闭合 gpter-MAJOR#3）

`SessionStreamingService` **仅作 Android 组件壳 + SSE 连接 owner + FGS 通知发布**，不堆业务逻辑。其余由应用级协作者承担（避免巨型 Service）：

| 协作者 | 职责 | 对应章节 |
|---|---|---|
| `StreamingLifecycleCoordinator` | §4 分层状态机、idle debounce、FGS↔poller 交接、源切换 | §4 / §4.4 / §6 |
| `ConnectionBootstrapCoordinator` | 冷启动 bootstrap、TOFU 共享 gate、长期 retry/degraded | §5 / §10 |
| `StatusAggregator` | 全局 busy 归并、status TTL/unknown、merge 时序 | §3 / §3.1 |
| `IslandNotifier` | 统一 dedup store、channel/ID 矩阵、decorator pipeline | §6 / §7 / §12 |
| `SseEventBridge` | events → identity 校验 → `OnSseEvent` | §1 |

### 1.1 断 SSE 的真实所有者（闭合 groker-R2）

> 今日真正在进后台时发 `CancelSse` 的是 `ForegroundCatchUpController.onForegroundChanged(false)`，**不是** `AppLifecycleMonitor`（后者只起 30s 轮询器）。因此改造目标是 FCC，不是 ALM。

- **删除** `ForegroundCatchUpController` 背景分支的无条件 `CancelSse`；背景只设 `backgroundedAtMs` + `clearDraft`。SSE 启停**只**由 §4 生命周期协调器 / Service 策略驱动。
- **`CancelSse` 双职责拆分**：
  - 有意停 SSE（idle/debounce）→ 仍**必须**留下与今日 `CancelSse` 等价的 gap-dirty（`SseSyncState.reconcileGap(Disconnected…)`），保证回前台 catch-up / gap-reload 不静默失效；但**不得**误伤「应保活」状态。
  - `CancelSseForReconfigure` → 不变（stop + bump epoch + `HostReconfigured`）。
- 单测（门禁）：background+busy 时 `sseJob`/Service 连接仍在；background+idle 到期后 dirty 置位且回前台触发 reconcile。

---

## 2. 连接身份与原子 reconfigure 协议（闭合 gpter-B#3 / groker-B2）

进程级 `ConnectionIdentity(epoch, serverGroupFp, normalizedWorkdir, endpointFp)`，**单一真相源**（不留在 Activity 侧）。

reconfigure 严格顺序（不可颠倒）：
1. `increment epoch`；
2. 失效旧 SSE collector；
3. `repository` / OkHttp client 重建（host/profile/workdir）；
4. 隔离/清理旧 host 状态；
5. 新 collector 绑定新 identity；
6. **每个发布事件携带 identity**；`SseEventBridge` / 通知聚合器 / `SessionSyncCoordinator.fold` 在**任何副作用前**校验 identity。

- `CancelSseForReconfigure` → 先 stop Service SSE 并 bump **与 collector 绑定的 epoch** → 再 repository/host 重配 → `loadInitialData` → start SSE。
- **`loadInitialData` 的 directory fan-out 必须读同一 epoch**（实现注记，gpter）：不得让 CC 私有第二个 generation 与 SSE 的 epoch 分裂。统一为：epoch 同时作 SSE collector 守卫 + directory-fetch 守卫。
- 单测：迁移/保留 `ConnectionCoordinatorTest` stale-host 场景（旧帧 `server.connected`/`session.status`/`message.*` 不得污染新 host；通知聚合器也不得用旧 `session.status` 触发错误完成通知）。

---

## 3. 权威 busy 数据源（闭合 gpter-B#1）

> 现状 `OpenCodeApi.getSessionStatus()` **无 directory 参数**。设计里「一次全局调用」与「per-workdir freshness/unknown」自相矛盾。本 spec **不保留为开放待验证项**，直接钉死方案。

> 现状 `OpenCodeApi.getSessionStatus()` / `OpenCodeRepository.getSessionStatus()` **无 directory 参数**，返回 host 级 `Map<sessionId, SessionStatus>`（idle 时服务端 delete，只含 active）。本 spec 不改造服务端，只消费现有 API。

- busy = **全局**，键为复合 `(serverGroupFp, workdir, sessionId) → Fresh|Busy|Retry|Idle|Unknown`。
- **Phase 0 主路径**：直接用现有**全局** `getSessionStatus()` 一次；用 `session.directory`（来自 `sessions` / `directorySessions`）把每个 `sessionId` 归并到 workdir。**请求失败 → 全局 `Unknown`，不得进 idle 宽限期**。
- **D1 gate #1：status TTL 主动过期（passive→active）**。TTL 不再仅由 map mutation 触发的 recompute 隐式生效——`StatusAggregatorImpl` 注入 `@UiApplicationScope CoroutineScope`，在每次 commit 后调度**单一的 earliest-deadline wake-up**（`freshnessJob`），目标时刻为「所有当前 identity 下 Idle 条目的 `sourceTimeMs + STATUS_TTL_MS + 1` + 成功快照 coverage marker 的 TTL 截止」中最早者。到期时 `recompute` 不依赖任何 map mutation。**新鲜度边界明确**：`now - sourceTimeMs <= STATUS_TTL_MS` 为 fresh；过期 `Idle` → 贡献 `Unknown`；过期 `Busy`/`Retry` → 保守地保持 `Busy`（绝不静默丢 keep-alive）。
- **D1 gate #1：debounce 读取时间正确状态**。`StreamingLifecycleCoordinator.startIdleDebounce` 在 45s 到期时调用 `statusAggregator.stateAtNow()`（按当前 `clock()` 即时投影），**不读** `globalState.value`（后者可能滞后于 wall clock）。`stateAtNow()` 与 `recompute` 共用同一个纯函数 `project(state, coverage, now)`。
- **D1 gate #5：returned-but-unmapped active IDs 强制 Busy**。`/session/status` 返回的 `sessionId` 若**不在** `sessionsById` 中，是**正向已知 active** → 贡献 `GlobalBusyState.Busy`（既不跳过，也不发明 workdir 制造复合键）。在 coverage metadata 的 `unmappedActiveIds` 中跟踪。
- **D1 gate #5：registered-workdir coverage 谓词**。snapshot 同时携带 `sessionsById` + `registeredWorkdirs`（= `recentWorkdirs(currentFp) + currentWorkdir + directorySessions.keys + sessionsById.values.map(Session::directory)`，dedup）。`AllIdleFresh` 合法当且仅当全部满足：(a) current-epoch 的成功快照存在且在 TTL 内；(b) `unmappedActiveIds.isEmpty()`；(c) `registeredWorkdirs` 中每个 workdir 都被当前投影里的 fresh 条目覆盖；(d) 每个已知 tracked session 已被映射；(e) 每个 tracked status 为 fresh `Idle`；(f) 无 `Unknown`/`Busy`/`Retry`。一个**无 session 但已登记**的 workdir 由 coverage marker 代表，不会因 session 全部 archive 而从 all-idle 谓词中消失。
- 一次**成功的空全局快照 + 零 registeredWorkdirs** 可以是 `AllIdleFresh`（由 fresh coverage marker 担保，**不是**空洞的未初始化状态）。
- **status TTL** 明确（如 30s）；只有**所有已登记 workdir 都取得新鲜+成功 idle** 才进停流宽限期。
- **Phase 0 门禁探针（必过）**：确认 `/session/status` 返回确为 host 级、覆盖所有 workdir（非仅 current）。**两 workdir 隔离测试**：两个不同 workdir 各有 active session 时，单次全局请求须**同时**反映两者。若实测全局只返回 current workdir → 升级为 **directory fan-out**（每 workdir 显式查询，复合键不变）并跑同一隔离门禁。
- 多 workdir **pending** 轮询独立（`computeQuestionFanOutWorkdirs` / `loadPendingQuestionsAllWorkdirs`），与 status 无关。

### 3.1 status merge 时序规则（闭合 gpter-MAJOR#3）

> 仅写「SSE 优先」不足以处理「请求发出后、响应返回前发生的 SSE transition」。

- 每条状态带「来源时间」：SSE 事件用事件到达的 monotonic 时钟；REST 响应记录 **request-start epoch** 与 receive time。
- REST 响应**不得覆盖**其 request-start 之后到达的更新 SSE 状态（防「旧 REST idle 覆盖新 SSE busy」与反向复活）。
- 所有 REST 响应校验 epoch（跨 reconfigure 的响应丢弃）。
- `mergeStatusSnapshot` 扩展为带时间/epoch 的合并，而非简单的 UI 刷新语义。

---

## 4. FGS 生命周期状态机（闭合 gpter-B#2 / B#6；groker-R1 / B4 / B5）

### 4.1 分层生命周期 L1 / L2 / L3（闭合 groker-R1 + gpter-B#1）

> 既要绕开「全 idle → stopSelf → poller 再发现 busy → startForegroundService」在 Android 12+ 被拒（groker-R1），又不能声称 dataSync FGS 可**永久**常驻（与平台 6h/24h 时限冲突，gpter-B#1）。解法：**分层 + L2 子态**，FGS 限定在平台允许的 active 窗口，窗口外明示不保证后台自动恢复。**全章（§4.1/§4.2/§4.4/§5/附录）统一用此模型，旧「FGS 外壳常驻」表述已全部删除。**

| 层 | 条件 | FGS | SSE | ongoing |
|---|---|---|---|---|
| **L1** 前台 | 有可见 Activity | idle=**普通 Service**（不占 FGS）；busy/retry=**立即提升 dataSync FGS**（预热后台保活，见 §4.2） | 始终保活（决策 4） | idle=无（或可选 LOW「已连接」）；busy=「N 任务进行中」+chronometer+abort |
| **L2-active** 后台 | 无可见 Activity + 全局 busy/retry | dataSync FGS | 保活 | 「N 任务进行中」+chronometer+abort |
| **L2-idle** 后台 | L2 内权威全 idle 且 45s debounce 到期 | **外壳保持**（不 stopSelf） | `cancelSse` | 降级 LOW「空闲监听」+ arm poller |
| **L3** 无 FGS | `onTimeout()` / 用户显式关后台 / disconnect / 进程死亡 | **已撤** | 无 | poller only |

**L2 内部恢复（groker-R1）**：L2-idle 期间 poller/REST 发现 busy → 在**同一已运行 Service 内** `connectSSE` 恢复（无需后台 `startForegroundService`）。此保证**仅持续到平台 dataSync 时限**；`onTimeout()` 后进入 L3，poller **无法**后台重启 FGS。

**L3 不承诺后台自动恢复 SSE**：合法恢复入口仅用户重开 app / 通知 action / 系统拉起。

**边界声明**：
- Android 12+ 的 FGS-start 限制只咬「初始 start」与「sticky 进程死亡重建」——两者都是**合法** FGS-start 上下文（用户动作 / 系统拉起）。
- 「用户显式关后台」入口（L3 触发之一）【2026-07-13 用户决策，post-gate 追加】：**确认采用**——机制分两路：(a) ongoing 通知常驻 **Action「关闭后台」**（FGS ongoing 不可被系统划掉，故以 Action 触发 L3 teardown：`stopForeground`+`stopSelf`+`cancelSse`+arm poller+撤 ongoing）；(b) 非 ongoing 通知（完成/decision）的划掉走 `deleteIntent`。Settings 侧不再设独立 app on/off 开关，整合进系统通知设置（见 dev-design P1.6）。force-stop / task-swipe 进 §14 矩阵。

### 4.2 L1→L2 合法提升时机（闭合 gpter-B#1 续）

> 必须在应用仍具 FGS 启动资格时完成提升，否则触发后台启动限制。

- **前台进入 busy/retry → 立即提升为 dataSync FGS**（此时仍处前台，合法）；用户随后进后台 → 直接保持（无后台启动问题）。
- **前台无 active 任务就进后台 → 不提升，直接 L3**（不尝试后台提升）。
- 远端（其它客户端）发起的任务、本应用已后台且非 FGS → **不保证**自动提升 / 恢复 SSE（明示）。
- **前台不发 completion/decisions 系统通知**（走应用内卡片）。

### 4.3 前台判定源（闭合 gpter-MAJOR#1）

> `AppLifecycleMonitor._isInForeground` 当前默认 `true`；后台进程 sticky 重建时短暂误报前台。

- **不再默认 true**；Activity started-count 是前台事实源。
- Service 冷启动、无 started Activity → 按后台处理。
- **D1 gate #2：用带延迟的 process-lifecycle 语义（700ms 延迟确认）**。`onActivityStopped` 把 started-count 递减；当递减到 0 时**不立即**翻 `_isInForeground=false`，而是取消上一个 pending 确认 job + 启动 `delay(700)`；到时**再次检查** `activityStartedCount == 0` 才真正翻 false + `onEnterBackground()`。`onActivityStarted`（0→1）**立即取消**该 pending 确认 job。**700ms 是 AndroidX `ProcessLifecycleOwner` 既定值**——配置变化（rotation: 1→0→1）在该窗口内完成，不会误判为后台。前台判定读取发生在 Main，主 dispatcher delay 与之同线程一致。

### 4.4 统一交接顺序（闭合 gpter-B#3 / groker-B4）

**单一串行状态机（持锁），新 source active + notifier 切换成功后才关旧 source：**

> **D1 gate #1 §4.4**：idle debounce 到期时**读取时间正确状态**——`statusAggregator.stateAtNow()`（按当前 `clock()` 即时投影），**不读** `globalState.value`（后者由 `freshnessJob` 保持一致，但 dispatcher 延迟可能令其在 debounce 到期的瞬间仍缓存着 stale `AllIdleFresh`）。`stateAtNow()` 与 `recompute` 共用同一个 `project(state, coverage, now)` 纯函数。

**L1 内部转换**（前台）：
- `L1-idle → L1-busy`：前台合法 `startForeground`（提升 dataSync FGS，§4.2）。
- `L1-busy → L1-idle`：`stopForeground`（回普通 Service + SSE 保活）。

**L2-active → L2-idle**（全 idle、debounce 到期；FGS 外壳保持）：
1. 执行最终全 workdir snapshot poll；
2. 启动 poller 并确认 job 已 active；
3. notifier 数据源切到 polling；
4. `cancelSse`（Service 内，留下 §1.1 的 gap-dirty）；
5. **进入 L2-idle**（外壳保持，不 stopSelf）。

**L2-idle → L2-active**（poller/REST 发现 busy）或 **→ L1**（回前台）：
1. FGS 仍在（L2 窗口内）/ 回前台（L1）；
2. 建立并验证 SSE / status baseline；
3. notifier 数据源切到 service source；
4. 取消 poller。
- 若回前台时仍 idle → `stopForeground` 回 **L1-idle**（普通 Service + SSE），不长期占 FGS（gpter 注记）。

**→ L3 teardown**（`onTimeout()` / disconnect / 用户关后台）：
1. notifier 数据源切到 polling；
2. `cancelSse`；
3. `stopForeground` / `stopSelf`；
4. 之后 poller only，**不**自动提升。

> 不存在「两边都没数据源」的中间态。L3 teardown 后的恢复只能走 §4.1 合法入口。

---

## 5. START_STICKY 冷启动 bootstrap（闭合 gpter-B#5；groker-B3）

> 硬约束：`startForegroundService()` 后系统给短时限内必须 `startForeground()`。网络/TOFU 不能先于占位通知。

`onCreate` / `onStartCommand`（**null Intent 安全**；冷启动/sticky 重建按 §4.3 = 后台，且为合法 FGS-start 上下文）：
1. **同步**读最小持久配置（SettingsManager + HostProfileStore）；无有效 host → `stopSelf`。
2. **立即** `startForeground(「正在恢复连接…」占位通知)`（占位，避免网络 bootstrap 撞 FGS 启动时限）。
3. **异步** bootstrap：激活/校验 tunnel → health probe + TLS/TOFU gate → `getSessionStatus`（**全局，按 §3 归并，非 fan-out**）。
4. **全局 busy/retry** → 保持 FGS（**L2-active**）+ `connectSSE(currentWorkdir)` → 事件进 `events`。
5. **权威全 idle** → **`stopForeground` / `stopSelf` → 进入 L3**（poller only，不占 FGS）；占位通知撤销。
6. `SSEConnectionExhausted` → service 级**长期重试 / degraded**（不等进程重启）。

**未决 TOFU 且无 Activity**（闭合 gpter-MAJOR#2 / #5）：
- 不无限等 UI deferred；不消耗 SSE 重试预算；
- 进入明确 degraded 状态；占位通知加 action 引导用户打开 Activity 完成信任；
- 用户确认后由 §10 共享 bootstrap coordinator 继续。

> `START_STICKY` 仅覆盖「进程被杀后系统可能拉起」，**不**保证及时、不保证 force-stop 后恢复。spec 不将其列为确定恢复入口。

---

## 6. FGS↔poller 无缝交接 + 统一 notifier（闭合 gpter-B#6；groker-B4）

- 生命周期协调器串行（见 §4.4）。
- **单一 `IslandNotifier`（应用级）** 持唯一 dedup store，复合业务键 `(serverGroupFp, workdir, sessionId, type)`；**不再**让 Service 与 `AppLifecycleMonitor` 各持 snapshot。
- `AppLifecycleMonitor` 职责收敛为：进程前台信号源（§4.3）+ poller 机制宿主；不再被当作 SSE-cancel 所有者。
- completion 通知归统一 notifier。

---

## 7. 通知 ID / channel 注册表 + 矩阵（闭合 gpter-MAJOR#2；groker-B5 / M3）

**固定保留 ID（避免 hashCode 碰撞）：**
- FGS/ongoing = 单一固定 `SESSION_STATUS_NOTIFICATION_ID`；
- error = `4242`；
- decisions = `notify(tag, id, …)` + 复合键；
- completion = 稳定复合键。

**channel 矩阵：**

| channel | 重要性 | 用途 |
|---|---|---|
| `ocdroid.session_status` | LOW | ongoing：前台「已连接」/ 后台 busy「N 个任务进行中」+chronometer+abort / idle「空闲监听」 |
| `ocdroid.session_complete` | DEFAULT, autoCancel | 完成通知（仅后台） |
| `ocdroid.decisions` | HIGH | permission/question（现有，仅后台） |
| `ocdroid.errors` | DEFAULT | 错误（现有） |

- 所有 `PendingIntent` 携带 `(serverGroupFp, workdir, sessionId)` identity，执行时复核（host 可能已切）。
- Settings 通知页展示**每 channel** 状态 + 跳系统 channel 设置（不止总权限）；通知权限被拒时给降级/引导（groker-M2）。
- chronometer 起点 = 该通知聚合内**最早 busy transition**（非通知重建时间）。

---

## 8. 完成通知状态机（闭合 gpter-MAJOR#1）

仅当：**同一 connection identity** 下明确观察过 `busy`/`retry`，随后观察到**权威 idle** 才发。

- baseline 初始快照 → **不发**；
- SSE replay / 重连首次 idle → **先 reconcile，不发**；
- host 切换 → **不发**；
- abort / error / archive → 各自独立终态，**不发完成**；
- snapshot 去重（类似 `notificationSnapshot`）。

**前后台抑制条件（修正 gpter-MAJOR#5）：**
- 应用**前台** → 不发系统 completion（走应用内反馈）；
- 应用**后台** → 当前或非当前 session **都可发**（transition 去重）；短暂 background（系统界面/配置变化）加前后台稳定 debounce。

---

## 9. abort 动作（闭合 gpter-MAJOR#6；groker-M3）

聚合「N 任务」通知 abort 策略（**钉死**）：
- **单 busy**：直接 abort 该 session；
- **多 busy**：**不**显示单一 Abort、**不**做无确认「全部取消」→ 点按跳应用任务列表（MVP）；
- action 执行时**重新查询 identity/status**，不盲用通知创建时的 ID 列表；
- **无 Activity** 时由 `BroadcastReceiver`/Service 内调 `OpenCodeRepository.abortSession`。

---

## 10. TOFU / bootstrap 抽离（闭合 gpter-MAJOR#2）

- 将 `ConnectionCoordinator` 私有的 `pendingTofuHostPort` / `pendingTofuDecision` 抽到**应用级共享 connection bootstrap coordinator**，CC 与 Service 共同调用。
- 避免出现「CC health probe 等 TOFU」与「Service 同时发起 TLS/SSE」两套 deferred / 状态机；reconfigure epoch 与 TLS client 重建顺序一致。

---

## 11. delta 背压 / overflow 恢复（闭合 gpter-MAJOR#4）

> `message.part.delta` 是增量拼接，不能当可随意丢弃的噪声。

- `events` SharedFlow：高频 `message.part.delta` 用**有界队列**；overflow 时：
  - 标记该 session `dirty`；
  - 清理该 session 的 delta / coalescing buffer；
  - idle 或短 debounce 后**强制 REST reconcile**。
- **控制类事件独立通道**（gpter-MAJOR#4）：`session.status` / `server.connected` / permission / question 走独立、不被 delta 挤出的通路。该通路为**单消费者、有界 `Channel`**，并规定：
  - 容量有界，**满载时发送者 suspend**（不得 `DROP_OLDEST` 静默丢）；
  - Service 销毁时显式 drain/cancel；
  - FIFO 顺序；`status` 可按复合键合并，但 `server.connected` / host epoch / 终态**不得被静默丢弃或乱序**。

---

## 12. IslandNotifier = 可组合 decorator pipeline（闭合 gpter-MAJOR#8）

`Base ongoing` → optional `LiveUpdate` decorator → optional `Xiaomi` decorator（**叠加**，非互斥 interface 选择）。任一增强失败 → 回落 base ongoing。读 `Settings.System.getInt("notification_focus_protocol", 0)` 安全降级（失败不影响基础 FGS 通知）。

---

## 13. 分阶段

> gpter-MAJOR#9：Phase 0 已含 busy 权威源与生命周期状态机（FGS 无法在没有它们时正确运行）——接受 Phase 0 含通知聚合底层，Phase 1 只加展示层。

- **Phase 0（使 FGS 正确的最小闭环）**：`SessionStreamingService` 骨架 + `SseEventBridge` + 连接身份协议(§2) + 权威 busy 源(§3) + 生命周期状态机(§4) + START_STICKY bootstrap(§5) + 交接(§4.4/§6) + 进程级 reducer(§1) + 固定低干扰 ongoing（单 session 形态）+ TOFU 抽离(§10)。
- **Phase 1**：全 workdir 聚合 ongoing「N 任务」+chronometer+abort、完成通知(§8)、Settings 文案/channel 修复(§7)。
- **Phase 2（附录，非 MVP 门）**：Live Updates（AndroidX Core 1.17+、API36、`POST_PROMOTED_NOTIFICATIONS`、`setRequestPromotedOngoing(true)`；状态芯片 + chronometer，无确定性进度）。
- **Phase 3（附录，需审核/白名单）**：小米岛（`focus_protocol∈{2,3}` 时 `miui.focus.param`）。

---

## 14. 发布门禁测试（闭合 gpter-#11）

### 14.0 Phase 0 发布前强制门禁（闭合 gpter-B#1/#3）

- **dataSync 平台矩阵（必过）**：覆盖当前 targetSdk **与** 未来 35+；逐项定义行为——`onTimeout()`、后台重启限制（`ForegroundServiceStartNotAllowedException`）、task-swipe、force-stop、通知权限拒绝、OEM（小米/华为）后台杀进程/长连接策略。未通过不得 ship Phase 0。（此矩阵从 §15「已知限制」**提升**为发布门禁。）
- **directory-status 隔离探针（必过）**：两 workdir 各有 active session 时，确认 §3 主路径的 status 查询能同时反映两者（全局归并或 fan-out 皆须过此隔离测试）；失败则不得实施「全 idle」判定。

### 14.1 行为/回归门禁

- host 切换：旧帧 `server.connected`/`session.status`/`message.*` 不污染新 host；通知聚合器校验 identity。
- Activity 重建：idle 帧不丢；SSC 由进程级 owner 持续 fold。
- 后台非当前 workdir busy：全局 busy 源识别；不误停 SSE。
- `sticky null Intent`：占位通知先贴 → 异步 bootstrap。
- `SSEConnectionExhausted`：service 级长期重试，不等进程重启。
- 通知权限拒绝 / FGS 启动被拒：降级路径。
- poller 交接（§4.4 双向）：无空窗、无双发。
- `sessionId` 跨 host 碰撞：identity 校验拦截。
- 聚合 abort：单/多 busy 分流；执行时复核 identity。
- delta overflow：session dirty + 强制 reconcile；控制事件不被挤出。
- **L1→L2 提升**（gpter）：前台进入 busy 时成功提升 FGS，随后 Activity 进后台**不**触发 `ForegroundServiceStartNotAllowedException`；前台无任务进后台直入 L3、不尝试后台提升。

---

## 15. 已知限制 / 待验证

- 服务端 `/global/event` + directory header 过滤严格度（§3：主路径为全局 `getSessionStatus` + `session.directory` 归并；§14.0 隔离探针未过才升级 fan-out）。
- 厂商 `dataSync` FGS 长连接杀进程策略（小米/华为，隧道场景）——纳入实机测试矩阵。
- `dataSync` 6h/24h 限额（targetSdk 35+）：`onTimeout()` 回落 poller + 通知用户；列「targetSdk 升级清单」。
- targetSdk 升级时间表与本功能是否同发。
- `START_STICKY` 重启矩阵：主动停服 / 系统杀进程 / force-stop / task-swipe / dataSync-timeout 各自行为（§5）。

---

## 附：决策溯源（grilling 结论）

| # | 决策 | 选择 |
|---|---|---|
| 1 | spec 范围 | 全量含 FGS（导航已拆独立 spec） |
| 2 | SSE 所有权 | 模型 A 收敛：连接迁入 Service，fold 不搬 |
| 3 | FGS 生命周期 | **分层 L1/L2/L3**：前台 idle=普通 Service、前台 busy/远程无任务=不保证后台提升/恢复（详见 §4.1/§4.2） |
| 4 | 完成通知 | busy→idle 在 SSE 存活窗口捕获，修「从不发完成」语义缺口 |

---

## 16. 用户决策追加（2026-07-13，post-gate）

> 双 9.5 门后用户补充的三项产品决策（不改变架构，仅闭合 §4.1/§15 的「待产品定」与展示层细节）。实现者按此执行；如与正文冲突，以本节为准。

| # | 决策 | 内容 | 落点 |
|---|---|---|---|
| U1 | 「划掉卡片就关」后台 | FGS ongoing 不可被系统划掉（Android 硬性）→ ongoing 通知常驻 **Action「关闭后台」** 触发 L3 teardown；非 ongoing（完成/decision）划掉走 `deleteIntent`。此即 §4.1「用户显式关后台」L3 触发。 | §4.1（已就地标注）/ dev-design §3.1.1 Action |
| U2 | Settings 整合系统通知设置 | **不设独立 app on/off 开关**。Settings→通知页 = ①`POST_NOTIFICATIONS` 权限状态+申请 ②每 channel 状态 ③「打开系统通知设置」deep-link（`ACTION_APP_NOTIFICATION_SETTINGS`）。 | dev-design P1.6 |
| U3 | decision 通知可点跳对应会话页 | permission/question 通知点按 → deep-link MainActivity 带 `EXTRA_SESSION_ID`+workdir → `selectSession` + 切 Chat tab，落到该会话页直接授权/回答。ongoing/完成同样支持单 session deep-link。 | dev-design P1.5 / §3.1.1 contentIntent |

**实现注记**：
- U1 的「关闭后台」Action 须在所有 ongoing 子态（L2-active 单/多 busy、L2-idle、占位、TOFU-degraded）常驻；点击后 **重新查询 identity/status**（不盲用通知创建时的快照）再 teardown。
- U3 需 app 侧具备「session deep-link」intent 处理（`EXTRA_SESSION_ID`+workdir → selectSession + 导航 Chat）；现有 `files?workdir=` deep-link 基础设施可扩展，Phase 1 落实时补齐。
