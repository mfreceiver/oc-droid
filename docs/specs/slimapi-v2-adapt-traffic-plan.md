# ocdroid slimapi v2 适配 + 流量治理 — 实现规约

> **状态**：经 4 轮 rev-gpt 架构门控（7.2 → 8.8 → 9.2 → **9.3，架构定盘**）。本文件是**实现契约**。
> §A–§C 架构经评审闭合；**R1–R3 为实现级完成条件**，必须以单测/竞态测试验收（单测比 prose 评审更强）。
> 关联：[`slim-mode-api-routing.md`](slim-mode-api-routing.md)、[`sse-client-spec.md`](sse-client-spec.md)、上游报告 `oc-slimapi/docs/manual/traffic-audit-report.md`。
> **Reconnect override（2026-07-29）**：用户已确认 BackgroundGrace 内健康主 SSE 可 best-effort 保留以接收决策通知。后台掉线后的前台恢复、transport runtime、ownership 与 retry 以 [`l4-background-sse-reconnect-design.md`](l4-background-sse-reconnect-design.md) 为权威；该专项规范覆盖本文件旧的主 SSE reconnect/recovery 细节。

---

## 1. 背景与产品决策

**报告根因**（14.4h / 2778MB / 省流 81.5%）：`messages` 桶占 88%，其中 `/slimapi/messages/{sid}` 全量骨架列表 24,871 次（80% 成本）。根因是 **SSE `session.digest` 每帧触发一次全量骨架重拉，而 `DIGEST_FULL_SWEEP_DEBOUNCE_MS=100ms` 是"薛定谔防抖"——常量定义了、kdoc 写了、实现里没接线**（`MessageActions.kt` 的 `SkeletonReloadCoordinator.onDigestChange` 仅 `inFlight` 门控）。活跃流式期间 ≈ 1/网络往返 ≈ 5–10 次/秒；后台 FGS 保活 SSE 时持续烧。

**slimapi 项目组变更**：breaking v2 wire（ocdroid 已合规，见 §A）+ additive 客户端标识头（§B）。

**产品决策（用户确认）**：
- **P1（更新）**：BackgroundGrace 内健康主 SSE 可 best-effort 保留以接收决策通知；后台不发起新 connect/reconnect。15 分钟到期仍硬性关闭 SSE+FGS+轮询。掉线后前台恢复走专项 reconnect runtime + supervisor。
- **P2（更新）**：后台无 q/p REST 轮询；BackgroundGrace retained SSE 可 best-effort 发布 question/permission 决策通知（无 REST side-effect），idle 通知仍抑制；15min terminal 后彻底静默。
- **P3**：token stream（per-session `/sessions/{sid}/stream`）仅当前可见会话保活。
- **P4**：前台恢复 / 会话打开 → 清 marker + 强制 reconcile，不可丢恢复。

---

## 2. §A — v2 契约合规（已完成）

逐项审计（见门控记录）全部 **ALREADY COMPLIANT**：

| 变更 | 状态 | 证据 |
|---|---|---|
| `X-Slimapi-Version: 2` 覆盖所有 `/slimapi/**`（含 SSE/token-stream） | ✅ | `SlimapiVersionInterceptor.kt` 经 `OkHttpClientFactory.baseBuilder` + `tokenStreamClient`；持 configure-time immutable `HostSnapshot`（**非** runtime read，A2 已修正） |
| 删除端点（projects/questions/permissions/since/session children&status/batch expand/POST q-p reply·reject） | ✅ | 全部已 reroute 到 legacy `/session/{sid}/question|permission` 等 |
| `/full/{mid}` 去 304/ETag/X-Message-Event-Seq、去 `?known.*` | ✅ | 客户端从未使用 |
| `/messages` `mode=full` 忽略、list 升序 | ✅ | 仅传 `mode="skeleton"`；`MessageActions.kt:1127` 防御式 ASC 重排 |
| Digest 删 `childrenVersion`/`contentRevisions` | ✅ | 从未引用 |
| 删 `X-Discovery-Directories`/`X-Discovery-Ready` | ✅ | 零引用 |
| `/slimapi/metrics` batch 恒 null | ✅ | 不读 `.batch` |

**A1**：`SlimSessionDigest.updatedAt`（`data/model/Slimapi.kt:128`）现语义为 **sidecar wall-clock**（原 upstream info.time）。C2 不再用它做 skip，仅作 marker 记账的元组分量。动作 = **注释标注 wall-clock 语义**（不删除，留作未来 best-effort 用途）。
**A2**：启动竞态验证 = bundle 原子发布 + 旧 client 失效（generation-captured snapshot 设计可正确），非 "runtime read 无竞态"。

---

## 3. §B — 客户端标识头（RESOLVED，实现；additive，向后兼容，无需 bump 版本）

- **B1 拦截器**：新增 `ClientIdentityInterceptor`，与 `SlimapiVersionInterceptor` 并列挂在 `OkHttpClientFactory.baseBuilder`（rest/sse/mutation/command）+ `tokenStreamClient`。仅 `/slimapi/` 前缀注入（复用 `SlimapiContract.SLIMAPI_PATH_PREFIX`）。
  - `X-Client-Name` = `"ocdroid"`（常量）；`X-Client-Version` = `BuildConfig.VERSION_NAME`（git 派生）；`X-Client-Id` = 设备 id。
- **B2 设备 id**：UUIDv4 首次启动生成，持久化到 **`EncryptedSharedPreferences`**（复用 `SessionPrefs` infra，`SessionPrefs.kt:42`，**非 DataStore**）；**原子 get-or-create**（防并发首请求生成多 id）；设置页可自定义覆盖（留空回退随机值）。
- **B3 覆盖矩阵 + sanitize**：health/ready/cert one-shot probe 绕过 `baseBuilder` → 显式加头或抽共享注入 helper（同版本头现有模式）。发空前 sanitize：空 / UTF-8 字节 >128 / 控制字符 / OkHttp 字符约束非法 → 省略该头（与 sidecar 校验一致）。不向非 `/slimapi/` catch-all 请求泄露 identity。
- **B4（可选，designer 解耦）**：设置页"设备标识"输入 UI。

---

## 4. §C — 流量治理（架构定盘）

> **契约对账**：报告曾建议"恢复 `/since` 增量"，但 v2 已删 `/since` 且 `/full` 去 ETag/304 → **ETag/304 路线作废**，dedup 改走客户端 marker 记账。

### C1 — 统一 reload scheduler（状态机）

- 新 `ReloadScheduler`（per `(transportGeneration, sessionId)`）持有：`dirty, target: Tuple?, inFlight, timerJob, nextAllowedAt`，每个 state 持**不可变 ownerGeneration**。
- **所有 reload 源统一经 `submit(sid, tuple, priority, reason)`**：digest、`requestReload()`、watchdog 重试、token-stream done/removal、server reconnect/reconcile。无源绕过。
- **优先级**：`FORCE_RECONCILE(limit=200)` > `DIGEST(limit=50)`；queued FORCE 不被后续 DIGEST 降级；后续 FORCE 取代 queued DIGEST。
- **launch 前 guard**：发 HTTP 前再查 `appInForeground && currentSessionId==sid && hostGeneration==capturedGen && routeIdentity==captured`；任一不符 → 不发、不推进 marker、保留 dirty。
- **频率上限**：`nextAllowedAt` 强制 busy 期 max 1 reload / 2s（可配）；最后一个 digest 或 busy→idle 保证 trailing reload。**验收：持续 250ms digest、RTT 100/500/2000ms 时 ≤ ~30 次/min（RTT 解耦）**。
- **后台转换**：立即取消 `timerJob` + 抑制新 launch；至多允许 1 个 in-flight 完成，但其 completion **不**安排 trailing reload。
- `onSessionClosed` 取消 + join `timerJob`。
- **generation 隔离**：generation 变化时原子 detach 旧 state、取消旧 timer；旧 in-flight completion 只能改其**自身** owner state，不能动新 generation 的 dirty/priority/target（防 host-A→host-B 同 sid ABA）。

### C2 — marker 记账（**无 suppression**）

- **删除任何基于 tuple equality 的跳过**。每个 content-bearing digest 都进 C1 scheduler；C1 是唯一限流层。marker 仅做 reconcile 正确性记账。
- **marker 仅在** 非空 + 成功提交的 merge 后推进。
- **NO-ADVANCE 清单**：空页、malformed/partial watermark、route/host CAS reject、cancellation、HTTP 成功但 merge 未提交、后台被抑制。
- transport-generation 变化时 reset marker。
- **不再依赖** sidecar tuple 唯一性契约（消除内容丢失风险）。

### C3' — SSE 生命周期 + 三道独立 gate

**三道 gate（不可合并为一个布尔）**：
- **主 SSE**（`/slimapi/events`，全局控制流）：`appInForeground && healthyIdentityAvailable`。
- **token stream**（`/sessions/{sid}/stream`，per-session）：`appInForeground && visibleChatSessionId == sid`。
- **消息 reload**：`appInForeground && currentSessionId == sid`。

**后台 15min teardown**：
- 进后台 → **消息 reload gate 立即生效**（digest 只记 dirty，不发请求，timer 取消）。transport（主 SSE）teardown 延后 15min（重连摊销）。
- **grace = receive-only/dirty-only**（见 R2）：grace 内 SSE collector 只更新内存控制面 + 置 `dirty[sid]`/`recoveryNeeded`，**禁止任何 REST side-effect**。
- 15min 到期：**新建 no-source terminal teardown**（**非** 现有 generic L3 "poller-only"），一并停 主 SSE + FGS + ALM `pollJob` + `ProcessStatusPoller`。teardown 后零轮询（P2）。
- timer：单一 application-scope owner，`SystemClock.elapsedRealtime()`（单调），fenced by `(foregroundGeneration, connectionIdentity)`；前台取消旧 gen timer；旧 gen timer 不能关新连接。

### P4 — 两级 single-flight reconcile

- 唯一 canonical 入口 `requestForcedReconcile(reason, priority)`。**替换（非叠加）**三个现有直接 side-effect 入口：SSC `server.connected` 直接 reload（`SessionSyncCoordinator.kt:661`）、`reconcileGap()` ReloadSession（`SseSyncState.kt:255`）、Service `onResync`→`reconcileFullAfterTransportReset`（`SessionStreamingService.kt:397`）。
- **两级 key**（不可用同一 key）：
  - `GlobalRecovery(generation)`：session-list + status + tree-completeness + unread，每 generation 至多 1 并发（见 R3 trailing）。
  - `MessageRecovery(generation, sessionId)`：limit=200，逐 session 合并。
- 不绕过现有 `<15s` foreground throttle（`ForegroundCatchUpController.kt:30`）。session-open 仅在 sid 变化 / marker dirty / 本 gen 未对齐时 force。

### C4 — children/tree TTL + 权威恢复

- children/tree：60s 前台 cache TTL；结构事件（created/deleted/archive）失效；前台周期权威 fallback 每 5min（capped 预算）。teardown 后零轮询。
- unknown-session digest → 发 `LoadSessions` + tree-refresh **信号**（digest 不能构造树节点）。
- missed-frame / resync → 权威恢复事务（session-list + status + tree + unread recompute，**非** 仅 limit=200 messages）。

---

## 5. 残留实现级需求 R1–R3（必须实现 + 测试）

### R1 — 空页零丢失（C1/C2 完成条件）
content-bearing digest 触发的 reload 若返回**空页**（read-after-event 时序），不得视为 aligned（当前 `MessageActions.kt:1056` 在空页复位 watchdog——须改）。保留 scheduler dirty，经统一 scheduler 做**有界退避重试**。仅以下情形可清 dirty：session 确认删除、force reconcile 得到权威空 session。
**测试**：唯一 digest → 首次空页 → 无后续 digest → retry 得到内容（断言最终可见）。

### R2 — receive-only 双重 gate（C3' 完成条件）
仅在 `applySseSideEffectsImpl` 加 flag **不够**（多条路径绕过）。必须**双重**：
1. **handler 层**：事件转 state-only projection / dirty / recoveryNeeded。
2. **REST effect 执行边界**：再查 `backgroundReceiveOnly + lifecycleGeneration`，拒绝进后台后才出队的旧 effect。
**必须覆盖枚举的绕过路径**：`server.connected`（`SessionSyncCoordinator.kt:641`）、archive restore `RefreshSessions`（`:1026`）、permission `LoadPendingPermissions`（`LegacySseHandler.kt:187`）、`message.created` ReloadMessages（`SharedConversationSseHandler.kt:82`）、`SseNotificationBridge` 后台通知（`:211`）、`SseEventBridge` 分发（`:204`）。grace 内 gate 通知 bridge（符合 P2 q/p 静默）。terminal teardown 后晚到 frame/queued effect 由 generation fence 丢弃。

### R3 — GlobalRecovery trailing pass（P4 完成条件）
每 generation 至多 1 并发 **≠** 每 generation 只需 1 次。resync/unknown-session/archive 在 in-flight 期间到达时，当前 pass 可能早于该事件 → 丢失到下次前台。必须：
- `GlobalRecovery(generation)` 增 `dirtyWhileInFlight`；in-flight 期间新恢复信号合并为**一次 trailing global pass**。
- 每个全局恢复子步骤（session-list/status/tree/unread）**提交前查 generation/identity**。
- 明确**无嵌套锁**：持 GlobalRecovery mutex 时不等会反向提交 GlobalRecovery 的 MessageRecovery，反之亦然。
**测试**：resync-during-global-recovery、host-switch-during-global-commit。

---

## 6. 测试矩阵

| ID | 覆盖 | 断言 |
|---|---|---|
| T-C1-a | digest-during-inFlight | 第二个 digest 合并 dirty，不发第二请求 |
| T-C1-b | FORCE 取代 queued DIGEST | limit=200 优先，不被降级 |
| T-C1-c | 后台立即取消 timer | 进后台不发新 reload；in-flight completion 无 trailing |
| T-C1-d | 频率上限 | 250ms digest × {RTT 100/500/2000ms} ≤ 30/min |
| T-C1-e | generation 隔离 | host-A→host-B 同 sid：旧 completion 不污染新 gen state |
| T-C1-f | onSessionClosed | timer cancel+join，无泄漏 |
| T-C2-a | NO-ADVANCE 清单 | 空/malformed/CAS/cancel/uncommitted/后台 → marker 不推进 |
| T-C3-a | 三 gate 独立 | session-list 页主 SSE 不断；token stream 随可见会话开关 |
| T-C3-b | teardown 一致性 | SSE+FGS+ALM+ProcessStatusPoller 同停 |
| T-C3-c | timer fence | 旧 gen timer 不关新连接；monotonic clock |
| T-P4-a | 两级 single-flight | 切 session 中途不重跑 GlobalRecovery；s2 message recovery 不被 s1 吞 |
| T-P4-b | flicker | 快速前后台不产生重复 limit=200 |
| **T-R1** | 空页零丢失 | 唯一 digest→空页→retry→可见 |
| **T-R2-a..f** | receive-only 双 gate | 6 条绕过路径后台不发 REST；通知 bridge 静默；晚到 effect 被 fence 丢 |
| **T-R3-a/b** | GlobalRecovery trailing | resync-during-inflight；host-switch-during-commit |

---

## 7. 车道分解 + 排序

| 车道 | 内容 | 风险 | 依赖 | 备注 |
|---|---|---|---|---|
| **L1 §B 客户端标识** | B1–B3 拦截器 + 设备 id + sanitize | 低 | 无 | 独立、安全，可先做 |
| **L2 §A 清理** | A1 注释 updatedAt、A2 验证 | 极低 | 无 | 随 L1 |
| **L3 §C1+C2 scheduler** | 统一 scheduler + generation 隔离 + marker 记账 + R1 空页 | **高**（并发） | 无 | 核心；TDD |
| **L4 §C3' SSE 生命周期** | 三 gate + 15min teardown + receive-only + R2 双 gate | **高**（lifecycle） | L3 概念 | TDD |
| **L5 §P4 reconcile** | 两级 single-flight + R3 trailing | 中高 | L3, L4 | 替换 3 入口 |
| **L6 §C4 children/tree** | TTL + 恢复信号 | 中 | L4, L5 | |

**排序**：L1+L2（并行，安全）→ L3（核心，TDD）→ L4 → L5 → L6。每车道结束 `./scripts/check.sh` 通过。

---

## 8. 验收

- 复跑 `oc-slimapi` 报告 §6.1 聚合脚本（同口径窗口）：`messages` 桶 upIn 与 `/slimapi/messages/ses_*` 请求数显著下降（活跃 reload ≤1/s；C1 上限 ~30/min）；后台 15min 后 `/messages`+`/children` 趋近 0；前台恢复一次 reconcile。
- `./scripts/check.sh` 通过（编译 + `testDebugUnitTest`）。
- §6 测试矩阵全绿（含 R1–R3 竞态测试）。
