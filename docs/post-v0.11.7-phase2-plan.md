# ocdroid v0.11.7 后续 Phase 2 方案（rev-grok 审过，8.5/10）

> 本文件是 Phase 2 方案 + Phase 3 实施规范 + 当前进行状态。与 `docs/post-v0.11.7-handoff.md` 配合使用（handoff = 全局恢复锚点；本文件 = Phase 2/3 细节）。

## 0. 评委变更（用户中途指令）

- **rev-sgpt-sol 已死** → 后续评委用 **rev-grok**（Phase 2 plan review 已用；Phase 4 门控主力）。rev-glm 仍可用。
- **改文件只用 `fixer`**（非 fixer-grok / fixer-sgpt）。
- **并发上限 ≤2**。

## 1. rev-grok verdict（2026-07-21，复用 rev-3，重读精确行范围后）

| 任务 | 裁决 | 理由（摘） |
|---|---|---|
| **T1** cold-start prepare→commit | **NO-GO** | RMW 已在 `mutateSessionList` CAS 内；`slimStateLock` 不保护 slice；prepare/commit 会**扩大** lost-update 窗口（并发 SSE session.created/updated/archive 被 clobber）。cold-start 稀有且纯内存，无实测 jank。投机微优化，风险 > 收益。可选未来微优化：仅预计算 snapshot-only map（groupBy/fetchedDirs）off-Main，仍 merge-with-live-prior 在 CAS 内——本次不做。 |
| **T2** 窄化 focus gate | **GO** | 真实正确性收益。外层 gate 误杀 session-list-global 工作（MarkDeleted 残留 zombie tab、非 current Reconciled 丢 WriteSessionWindow + re-ratchet）。chat-merge 安全已由内层 `liveSessionId == result.sid` 守护，外层 gate 冗余。 |
| **T3** 测试补强 | **GO** | (a) 切换恢复路径（依赖 T2）；(b) stripe Mutex 取消/重试序；(c) 真实 `slimStateLock` 死锁回归（高价值）；(d) GoldenPath fix-6 / Stale pin。 |

**总体置信度 8.5/10**（条件：T1 NO-GO、T2 落地明确 outcome/return 语义 + 测试更新、并行划分修正）。

## 2. T2 实施规范（Track A fixer 必须严格遵守 rev-grok 三规则）

**目标文件**：`app/src/main/java/cn/vectory/ocdroid/ui/controller/SessionSyncCoordinator.kt`

**当前 bug**（`applyReconcileResult` ~2507-2532）：外层粗 gate 在 `snapshot.currentSessionId != liveSessionId` 时 `return@commitIfSlimTokenCurrent`（~2526）+ 返回 `Stale`（~2531），整结果丢弃——连非 current 敏感的工作（MarkDeleted / EvictSession / 非 current WriteSessionWindow + dirty re-ratchet ~2608-2663）一并丢。

**实施**：
1. 移除外层粗 focus gate（~2526 的 early return），总是执行 `applyCurrentReconcileResult`。
2. **返回值**：`committed == true` 时返回**真实** `result`（Reconciled/MarkDeleted/…），**非 Stale**；Stale 仅留给 stale/superseded token（`isSlimCommitTokenCurrent` 预检 ~2518 + `commitIfSlimTokenCurrent` 返 false）。清理 vestigial `snapshotAccepted` 机制，**但保留 `snapshot: ResyncUiSnapshot?` 参数签名**（调用方仍传；resync entry 的 `ResyncUiSnapshot` 捕获不变）。
3. **rev-grok 规则 #1**：token gate 保持 all-or-nothing（stale token → 全 no-op + Stale）。
4. **rev-grok 规则 #3**：`mergeSlimMessagesIntoChat` 前的内层 `liveSessionId == result.sid` 守护（~2605）**必须保留**——只有 chat-merge 被 focus-gate，其余（MarkDeleted / EvictSession / 非 current WriteSessionWindow / re-ratchet）无视 focus 照常执行。

**最高风险——勿破坏 catch-up 记账**：`performResyncCatchUp` / `performResyncCatchUpOnWorker`（~2778-2935）与 `performSlimResync`（~2936-3110）记录每 sid outcome，部分路径把 `Stale` 当"下轮重试/Stale-corrected"。T2 后更少结果返 Stale（retention 工作在切换时也 apply）→ 须读这些 caller 确认记账仍正确（"if Stale → re-enqueue/skip" 逻辑仍终止、dirty 按预期 clear/ratchet）。仅当真出 bug 才改 caller 逻辑，否则留并加注释说明。

## 3. T3 实施规范

- **(a)** session-switch 恢复路径测试（ResyncTest.kt）：切换后旧 session bookmark 推进 + session-window cache 填充 → 后续 switchTo 命中 cache 免重取（`loadMessagesForEffect` 兜底）。**依赖 T2，Track A 内做**。
- **(b)** stripe Mutex 持锁跨网络 cancel/retry 序（新文件）：同 sid 序列化、cancel-during-hold 释放 stripe、跨 sid 并发。
- **(c)** 真实 `slimStateLock` 死锁回归（新文件）：**必须用真实 repo + 真实 `commitIfSlimTokenCurrent`**（非跳过 lambda 的 mock）。pin fix-6 两段式 dispatcher（token 检查在 reconcileDispatcher / Default，commit 在 Main.immediate，锁不跨 suspend 持有）。
- **(d)** GoldenPath T3(d)：端到端 pin fix-6 dispatcher + catch-up Stale（不变）；session-switch 断言更新为 T2 新语义（retention apply、chat-merge 跳过）。

## 4. 并行划分（Track A / Track B，≤2 并发，写范围零重叠）

- **Track A**（fix-1 / `ses_07e387e99ffeW5FKUtR9EP5mmE` / fixer / running）：T2（SessionSyncCoordinator.kt）+ ResyncTest.kt session-switch 测试更新 + T3(a) + GoldenPath T3(d)。**拥有 T2 语义变更及其全部测试余波**。
- **Track B**（fix-2 / `ses_07e382edaffemF2AMPBCF36Bk7` / fixer / running）：**新文件** `SessionSyncDeadlockRegressionTest.kt`（T3(b) + T3(c)）。pin 不变的 fix-6/stripe 行为，与 T2 零耦合、零文件冲突。

> 偏离 rev-grok 原议（其把 T3(d) 放 Track B）：T3(d) 的 session-switch pin 与 T2 语义耦合，故随 Track A。理由已记。

## 5. 当两 fixer 返回后的 reconcile 步骤

1. 读 fix-1 / fix-2 报告（各自 check.sh 结果行 + diff 摘要）。
2. 全量 `./scripts/check.sh`（两 track 合并后重跑，确认无交叉回归）。
3. 若绿 → Phase 4 门控（rev-grok + rev-glm，同 prompt 全范围复审，≥9.5）。
4. 门控过 → Phase 5（commit + push ocdroid main；是否发版问用户）。

## 6. 关键文件

- 主源：`app/src/main/java/cn/vectory/ocdroid/ui/controller/SessionSyncCoordinator.kt`（T2：applyReconcileResult ~2507-2532 / applyCurrentReconcileResult ~2551-2677 / performResyncCatchUp ~2778-2935 / performSlimResync ~2936-3110）
- 仓库锁：`app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt`（slimStateLock ~380 / withSlimStateCommit ~468-509）
- 测试：`app/src/test/java/cn/vectory/ocdroid/ui/controller/SessionSyncCoordinatorResyncTest.kt`、`…/SessionSyncDeadlockRegressionTest.kt`（新）、`app/src/test/java/cn/vectory/ocdroid/integration/SlimGoldenPathIntegrationTest.kt`
- 评委会话：rev-grok = rev-3 `ses_07f9a93a1ffe8BOFR5vLgJl15o`；rev-glm = rev-4 `ses_07f9a5618ffeZ5SYjdB9UVc5ve`
