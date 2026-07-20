# ocdroid v0.11.7 后续待办与上下文交接（2026-07-21）

> **本文件为上下文压缩后的恢复锚点。读完本文件即可继续推进。**

> **2026-07-21 更新**：Phase 2 完成（rev-grok plan review，T1 NO-GO / T2+T3 GO，8.5/10）。Phase 3 完成——两 fixer 并行 landed（Track A=T2+测试 fix-1 / Track B=T3(b)(c) 新文件 fix-2），joint `check.sh` 绿（3650 测试 0 失败）。**Phase 4 门控进行中**：rev-grok + rev-glm 并行复审全范围（≥9.5 才 ship）。**评委改 rev-grok（rev-sgpt-sol 已死）；改文件只用 fixer；≤2 并发。** 完整 Phase 2 方案 + reconcile 步骤见 **`docs/post-v0.11.7-phase2-plan.md`**。门控过 → Phase 5（commit + push ocdroid main；是否发版问用户）。

## 0. 当前状态（已完成，勿重做）

- **v0.11.7 已发版 + push + 上传 Gitea**：
  - commits（ocdroid main，已 push 到 `3cdcc07..668e3e2`）：
    - `d5d486a` fix(sync): slim resync 移出 Main 线程 + stale/session-switch gate + workdir union
    - `7dc5134` fix(repo): coldStartSlimSync roots=true + limit=500
    - `668e3e2` feat(ui): 服务器配置编辑器四选项并入「高级」+ 删连接模式 + 首页弹窗去流量统计
  - tag `v0.11.7`（已 push）；APK `APK/oc-droid-0.11.7-668e3e2.apk`；Gitea release id 531：https://git.vectory.cn:18443/mfreceiver/oc-droid/releases/tag/v0.11.7
  - 三方门控（rev-sgpt-sol / rev-grok / rev-glm）复审 **9.7/9.6/9.6 PASS**。
- **Phase 1（slimapi 契约反馈文档）已完成**：`/home/mar/personal_projects/oc-slimapi/docs/ocdroid-v0.11.7-contract-feedback.md`（5 节：§1 sessions 完整性+cursor+roots / §2 partId 稳定+G6 迁移 / §3 reconfigure 事件 / §4 version min/max / §5 优先级；每条含客户端观测+已落地契约+★最理想选项+接口要求）。
  - **尚未 commit/push 到 oc-slimapi**（待用户确认是否提交）。

## 1. 用户指令的多阶段计划（剩余 Phase 2–5）

用户原话：「随后完成本项目可独立完成的任务，并经过 rev-sgpt-sol 后尽可能并行开展（fixer-grok 不超过 3 个），最后交 rev-glm、grok、sgpt-sol 共同门控 9.5 分后 push」。

- **Phase 2**：识别 ocdroid 可独立完成任务（不依赖 slimapi 契约变更）→ 形成方案 → **rev-sgpt-sol 审方案**（plan review）。复用 rev-2 会话 `ses_07f9ace6bffeRRgls2CZKVLdAU`。
- **Phase 3**：方案通过后，**并行 fixer-grok（≤3）**实现。fixer-grok 本会话未用过 → 首次派发用**全新会话**（不传 task_id）。
- **Phase 4**：实现后 **rev-glm + rev-grok + rev-sgpt-sol 三方门控 ≥9.5**（复用 rev-4 `ses_07f9a5618ffeZ5SYjdB9UVc5ve` / rev-3 `ses_07f9a93a1ffe8BOFR5vLgJl15o` / rev-2 `ses_07f9ace6bffeRRgls2CZKVLdAU`，同一 prompt 全范围复审）。
- **Phase 5**：三方通过 → `./scripts/check.sh` → commit → push（ocdroid main）。是否再发版（release.sh）由用户定。

## 2. 可独立任务（不依赖 slimapi 契约）

| ID | 任务 | 写范围 | 依赖 |
|---|---|---|---|
| **T1** | `applySlimColdStartSnapshot` prepare→gate→apply 两段式优化：snapshot fold（session-list 聚合 + message merge + effect 发射）当前在 Main+锁内重计算，移到 Default prepare → Main commit，与 fix-6 resync 模式对齐（oracle 建议；rev-sgpt-sol concern #1） | `SessionSyncCoordinator.kt`（applySlimColdStartSnapshot 区） | 无 |
| **T2** | coarse focus-snapshot 窄化：会话切换时只拒 current-session-sensitive 分支，不拒整个 catch-up set 的非 focus 操作（WriteSessionWindow / MarkDeleted / ClearLocal）；非 focus retention 失败仍 re-ratchet dirty（rev-sgpt-sol concern #2 / rev-grok concern #1） | `SessionSyncCoordinator.kt`（applyReconcileResult / applyCurrentReconcileResult 区） | 无 |
| **T3** | 测试补强：(a) session-switch 后 bookmark 已推进+cache 跳过的**恢复路径**测试（依赖 loadMessagesForEffect 兜底）；(b) per-SID Mutex 持锁跨网络的 **cancellation/retry ordering** 测试；(c) deadlock 回归测试用**真实 slimStateLock**（非 mock commitIf）；(d) SlimGoldenPathIntegrationTest **端到端** pin fix-6/session-switch/catch-up Stale | `SessionSyncCoordinatorResyncTest.kt`、`SlimGoldenPathIntegrationTest.kt` | T3(a) 依赖 T2 落地；T3(b)(c)(d) 独立 |

### 并行约束（写范围不重叠硬规则）
- **T1 + T2 都写 `SessionSyncCoordinator.kt` → 不能并行**（同一文件会 race，本会话曾因此丢改动）。
- 建议并行划分（交 rev-sgpt-sol plan review 裁定）：
  - **Track A**（1 个 fixer-grok）：T1 + T2 顺序做（同文件，单写者）。
  - **Track B**（1 个 fixer-grok）：T3(b)(c)(d)（测试文件，与 T1/T2 不重叠）。
  - T3(a) 待 T2 落地后补，或并入 Track A。
  - → 最多 **2 个并行 fixer-grok**（在 ≤3 限制内）。

## 3. 依赖 slimapi 契约的任务（本次不做，等对方）

- expand placeholder→real 可靠替换（依赖契约 **§2** partId 稳定性裁定）—— ocdroid 当前最大用户可感知 bug「展开失败」。
- sessions cursor 分页客户端支持（依赖 **§1**）。
- reconfigure 事件驱动失效（依赖 **§3**）。

## 4. 可复用会话（resume 用，completed/reconciled 可复用）

| 别名 | session_id | specialist | 适合复用于 |
|---|---|---|---|
| fix-1 | `ses_07fc83c61ffeOEKwWSJQRx91nt` | fixer-sgpt | T1/T2（SessionSyncCoordinator.kt + resync 测试 context）；但用户指定 Phase 3 用 **fixer-grok**，故 T1/T2 应派**全新 fixer-grok**，不复用 fix-1 |
| fix-2 | `ses_07fc7af90ffexcAziIfBLgLAA7` | fixer-sgpt | UI context（本次 UI 已完成，暂用不到） |
| rev-2 | `ses_07f9ace6bffeRRgls2CZKVLdAU` | **rev-sgpt-sol** | Phase 2 plan review + Phase 4 门控 |
| rev-3 | `ses_07f9a93a1ffe8BOFR5vLgJl15o` | rev-grok | Phase 4 门控 |
| rev-4 | `ses_07f9a5618ffeZ5SYjdB9UVc5ve` | rev-glm | Phase 4 门控 |

> 注：用户指定 Phase 3 用 **fixer-grok**（本会话未用过）→ 派发时**不传 task_id**（全新会话）。rev-* 复用时传上表 task_id。

## 5. 关键文件

- ocdroid：
  - `app/src/main/java/cn/vectory/ocdroid/ui/controller/SessionSyncCoordinator.kt`（T1/T2；含 performSlimResync / performResyncCatchUp / reconcileSession / foldRestFetch / applyReconcileResult / applyCurrentReconcileResult / applySlimColdStartSnapshot）
  - `app/src/test/java/cn/vectory/ocdroid/ui/controller/SessionSyncCoordinatorResyncTest.kt`（T3）
  - `app/src/test/java/cn/vectory/ocdroid/integration/SlimGoldenPathIntegrationTest.kt`（T3d）
  - `app/src/main/java/cn/vectory/ocdroid/data/repository/OpenCodeRepository.kt`（coldStartSlimSync，T1 可能触及）
  - `app/src/main/java/cn/vectory/ocdroid/di/ControllerModule.kt`（reconcileDispatcher 注入，T1 模式参考）
- oc-slimapi：`docs/ocdroid-v0.11.7-contract-feedback.md`（Phase 1 产出，待 commit/push）

## 6. 校验/发版入口（AGENTS.md 硬规则，必须走脚本）

- 改动校验（替代 LSP，必做）：`./scripts/check.sh`（编译 + 单测；`--full` 加 lint+覆盖率）。
- 发版唯一入口：`./scripts/release.sh <patch|minor|major>`（只打 tag，不 commit；要求干净树 + main）。
- push + gitea：`git push origin main && git push origin <tag>` → `./scripts/upload-release.sh <version>`。
- 模拟器：`./scripts/emulator.sh status/start/stop`（用前确认未运行，用完必 stop）。

## 7. resume 后第一步

1. 读本文件 + `/home/mar/personal_projects/oc-slimapi/docs/ocdroid-v0.11.7-contract-feedback.md`。
2. 确认 oc-slimapi 契约文档是否要 commit/push（问用户）。
3. 启动 Phase 2：形成 T1/T2/T3 方案（含上文并行划分）→ 派 rev-sgpt-sol（复用 rev-2）plan review。
