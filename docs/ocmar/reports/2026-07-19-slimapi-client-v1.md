# ocdroid slimapi v1 客户端实现 — Final Report

> **Date:** 2026-07-19 · **Workflow:** `slimapi-client-v1` (ocmar, owner `ocmar-slimapi-client-v1`)
> **Base:** `fb0e1899eeb89eccd5294293c310505caf48f4ba` · **Status:** **RELEASED** (`promote-batch` rev 86→87 + `release --finished`, EXIT=0)
> **Plan:** `docs/ocmar/plans/2026-07-18-slimapi-client-v1.md` · **Spec:** `docs/ocmar/specs/2026-07-18-slimapi-client-v1-design.md`
> **Server contract:** `/home/mar/personal_projects/oc-slimapi/docs/{v1-contract.md,INTERFACE_MAP.md,CLIENT_CHANGES.md}` (deployed sidecar @ `127.0.0.1:4097`, api_version=1)

---

## 1. 概述

全量实现 ocdroid 客户端 slimapi v1 配套（任务书 B5）：18 个 flat-numbered 任务（T1-T18）+ B1 fold-in，覆盖 L0 模型→L1 端点→L2 状态机→L3 wiring→L4 UI→L5 docs。经 **5 轮 final-gate 评审迭代**（round-1→round-8）收敛至 **round-8 APPROVED（0 Critical / 0 Important，7/7 hand-off invariants HOLD）**，独立 no-cache 验证 **3598 tests / 0 failures**。Stage 7 对部署 sidecar curl smoke **无自 bug**。

## 2. 需求回顾

任务书 `docs/slimapi-client-impl-v1.md` + 服务端 `INTERFACE_MAP.md` 定义 slimapi v1 契约。客户端在 slim 分支（`HostProfile.slim=true`）下启用，legacy 路径不动。核心能力：

- **G2** per-session status（typed outcome，404/503 分裂）；**G3** probeLatestSlim；**G5** cursor-paginated messages + split watermark 状态机；**G6** batch/single full expand + retry；**SSE** instance-level `/slimapi/events` digest→resync；**mutation** POST（q/p response，routeToken，不自动重试）；**banner** session.error→`sessionErrorsById`；**cold-start** sessions+questions+permissions 聚合；**Last-Event-ID** marker；**version gate** `X-Slimapi-Version`。

## 3. 方案摘要（架构）

- **G5 状态模型**：`SlimSessionState` 拆 `remoteObserved*`（digest 立即推进，单调）vs `localApplied*`（仅 REST merge 成功才推进）+ `dirty`。digest/resync 共用单一 `reconcileSession(sid, trigger)`。
- **status 双机制**：host-wide 批量轮询（legacy 不动）+ slim per-session on-demand（typed `SlimapiStatusOutcome`，coordinator 据 404/503 行动，fan-out via `SlimStatusFanOut` Semaphore(4)）。
- **cursor**：契约胜出；`getSlimapiMessagesPage()` 暴露 `X-Next-Cursor`。
- **reconciler**：T11 `ReconcileMode` + 64 stripes + `Semaphore(4)` + `performSlimResync` orchestrator；T6 `SlimapiResync.kt` 纯函数（epoch guard 在 repo wrapper）。
- **C-D3 token discipline**：`commitIfSlimTokenCurrent` 三条件原子（`issuedReady && slimIncarnationReady && marker===`，全在 `slimStateLock`）+ `SlimCommitToken` 经全 call-site（cursor/resync/UI-gate/standalone q-p）。
- **Tech stack**：Kotlin + Retrofit + okhttp3 + kotlinx.serialization + Compose；JUnit4 + mockk + MockWebServer + coroutines-test。

## 4. T1-T18 执行总结

| ID | 层 | 标题 | 状态 |
|---|---|---|---|
| T1 | L0 | models + contract constants | ✅ verified |
| T2 | L1 | G3 probeLatestSlim | ✅ verified |
| T3 | L1 | G6 batch full + retry | ✅ verified |
| T4 | L1 | G2 per-session status (StatusOutcome) | ✅ verified |
| T5 | L1 | G5 cursor (`X-Next-Cursor`) + cold-start follow | ✅ verified |
| T6 | L2 | G5 split watermark + reconcileSession (pure primitives) | ✅ verified |
| T7 | L2 | SlimapiProbe pure needsCatchUp/catchUpSet | ✅ verified |
| T8 | L2 | SlimapiMessageMerge null-safe + mapStatusOutcome | ✅ verified |
| T9 | L3 | SSEClient reconnect no-replay (LE-ID marker) | ✅ verified |
| T10 | L3 | ServiceSseConnectionOwner resync reason | ✅ verified |
| T11 | L3 | reconcileSession wiring (digest+resync; 64 stripes) | ✅ verified |
| T12 | L3 | session.error → sessionErrorsById lifecycle | ✅ verified |
| T13 | L3 | slim per-session status fan-out + poller backoff | ✅ verified |
| T14 | L3 | mutation client per-method routing (POST no-retry) | ✅ verified |
| T15 | L4 | expand state + usecase (residual rule) | ✅ verified |
| T16 | L4 | chat 展开 wiring (MessageRow → T15 usecase) | ✅ verified |
| T17 | L4 | banner via StatusSlot + non-focus row | ✅ verified |
| T18 | L5 | docs slim-mode-api-routing aligned | ✅ verified |
| T-B1-foldin | — | B1: +4 constants + 413 split (message_too_large fail-fast) | ✅ verified |

详细每任务 brief/report/review 见 `.ocmar/workflows/slimapi-client-v1/task-{N}-{brief,report}.md` + `review-task-{N}-*.md`。

## 5. Final-gate 迭代（5 轮，round-1 → round-8）

whole-branch final review 每轮揭示更深并发/lifecycle 层，逐轮收敛：

| 轮 | 发现 | 修复 |
|---|---|---|
| **round-1→3** | C-D3 call-site token discipline 断链（cursor/resync/UI-gate/standalone q-p） | SlimCommitToken 经全 call-site；`commitIfSlimTokenCurrent` 三条件原子 |
| **round-4** | 2C+1I：reconfigure-boundary 缺失 + discriminator relaxed-mock tautology | — |
| **round-5** | ticket-ownership + entry-boundary（oracle + rev-3 council 双源一致） | `SlimReconfigureTicket` + `SupersededSlimReconfigureException` + `beginSlimReconfigure(): ticket` + `completeSlimReconfigure(ticket)` 条件完成 + `configure(reconfigureTicket)`；selectHostProfile/deleteHostProfile/configureServer/configureRepositoryForProfile 入口 mutation 移入 boundary |
| **round-6** | 1C+2I：active-save boundary 不完整（cert/cred/Basic Auth-only 漏）+ async 生命周期脱钩 + test mock 完成缝 | `saveHostProfile` 全连接影响 mutation 入 barrier + `basicAuthChanged` 谓词 + mTLS projection；suspend `Result<Unit>`；real Retrofit failure discriminator |
| **round-7** | 1I：CE swallow + 重入/生命周期（`runCatching` 捕 CancellationException；screen scope；无 isSaving/generation guard） | `runSuspendCatching`；`HostProfileSaveState` (Idle/Saving/Done) + viewModelScope ownership + single-flight + profileId guard + isSaving gate |
| **round-8** | **0C / 0I** — **APPROVED, Release GO** | — |

每轮 fresh `_priv-verifier` no-cache `--rerun-tasks --no-build-cache` 独立验证 GREEN。review 链：`.ocmar/workflows/slimapi-client-v1/review-final-rev-gpt-round{2..8}-*.md`。设计权威：`oracle-reconfigure-boundary-design.md`（520 行）+ `review-final-council-rev-gpt-round5-*.md`。

## 6. 验证证据

- **独立 no-cache `./scripts/check.sh --full`**（`_priv-verifier`，`--rerun-tasks --no-build-cache`，不信 cache）：**EXIT=0 / 3598 tests / 0 failures / 2 skipped / lint 0 errors（246 pre-existing warnings）/ koverVerify PASSED / koverHtmlReport 生成**。
- 测试增量轨迹：3579（pre-final-gate）→ 3589（round-5）→ 3592（round-6 basicAuthChanged ×3）→ 3598（round-7 CE + save-state ×6）。
- verify log：`.ocmar/workflows/slimapi-client-v1/verify-1784462683.log`（round-7 final）。
- **round-8 review VERDICT: APPROVED — Release GO**（`review-final-rev-gpt-round8-20260719201956.md`，HIGH confidence）。
- ocmar state：`promote-batch` 19 tasks reviewed→verified（rev 86→87, fp=bba69292）→ `release --finished` EXIT=0。

### 7/7 hand-off invariants（round-8 确认全 HOLD）
1. T6 digest 用非 coercing `lenientJson`（不迁 ssePayloadJson）。
2. T6 reconcile primitives PURE（`SlimapiResync.kt` 无 suspend/network/lock）。
3. T11 架构（ReconcileMode / 64 stripes / Semaphore(4) / performSlimResync）。
4. T15 owner = `resolveOwner(part)` from local `MessageWithParts.info.id`；orphan→Failed。
5. T16 compound CAS（fp/session identity + partsToLoad filter + Loading claim/recheck）。
6. C-D3 token discipline（含 active-save entry boundary 全闭合）。
7. I-1 streaming fan-out（ProcessStatusPoller/StreamingModule/AppCore effect tail）。

## 7. Stage 7 — slim 端点 smoke（对部署 sidecar @ 127.0.0.1:4097）

> 方法：curl 带版本头逐端点验真实 sidecar（emulator + slim androidTest defer 到专门 session；3598 unit tests 已覆盖 slim 客户端逻辑）。**结论：无自 bug；客户端 wire shape + 端点用法全匹配部署 sidecar 契约。**

全 pass：`health`（+无头→400 门闩）/`sessions`/`sessions/status`/`sessions/{sid}/status`（+404 G2 分裂）/`messages/{sid}` skeleton/drain/since/`questions`/`permissions`（`items`+`errors` 信封）/SSE `events`（流 `server.connected`）/routeToken 校验（400 `invalid_route_token`）。Wire-shape 交叉核验：`SlimapiQuestionReplyRequest(answers: List<List<String>>, routeToken)` / `SlimapiQuestionRejectRequest(routeToken)` / `SlimapiPermissionResponseRequest(response, routeToken)` 全匹配 sidecar。

**部署注意（非 bug）— directory allowlist**：sidecar 只为 allowlisted dir 服务 directory-scoped 查询（`/home/mar/personal_projects/ocdroid` allowlisted；`/home/mar/opencode_wd` 否 → 400 `directory_not_allowed`）。用户 project dir 必须在 allowlist 上；setup docs 应记录。

详见 `.ocmar/workflows/slimapi-client-v1/problem-report-wip.md`「Stage 7」节。

## 8. 最终状态

- **Workflow DONE + RELEASED**（`ocmar-state release --finished` EXIT=0）。
- working tree uncommitted（80→84 files changed over rounds；按 ocmar SERIAL 约定不 commit，仅 record diff/checkpoint）。
- **pre-existing release.sh WIP**（stash@{0}）于报告完成后 `git stash pop` 还原。
- `SettingsSectionsInstrumentedTest.kt` Function11→Function12 drift 已修（enabling fix，pre-existing，非本 workflow 回归；androidTest compile GREEN）。

## 9. 给 slimapi team 的问题报告（contract / 协商项）

详见 `.ocmar/workflows/slimapi-client-v1/problem-report-wip.md`（持续追加的工作文件）。摘要：

- **C-D2** cold-start 成功空 vs 失败不可区分（per-piece 失败折叠成空 list）。
- **C-D3** ✅ 本工作已客户端侧闭合（token discipline + ticket-ownership）；服务端无动作需求。
- **C-D5** 等时间戳不同 messageId 的 tie-break 规则缺失（需服务端确认 per-session `updatedAt` 单调性）。
- **C-D7** T11-C6 "session.error 并发" 语义不清。
- **C-D8** session.error wire shape 歧义 + slimapi 是否发射（top-level vs nested；客户端 defensive 双形状解析）。
- **C-§3-vs-§4** `/since/0` 允许性冲突（客户端裁定 focus digest+resync 统一 cursor drain）。
- **Stage7-A1** directory allowlist（部署注意，非契约问题）。

## 10. 非 blocking follow-ups

- **M1** ✅ 已修复并验证（`consumeSaveState` 加 `if (!is Done) return` guard 对齐 KDoc；+1 test `consumeSaveState while Saving is a no-op` 锁定契约；verify-1784467625.log，3599 tests / 0 failures）。
- **M2** ✅ 已修复并验证（`.onFailure` 加对称 `if (editingProfile?.id == s.profileId)` guard，与 `.onSuccess` 路径一致；同 verify GREEN）。
- **slim instrumented test**：本 session 推进中（real Android HTTP 栈 vs `10.0.2.2:4097`，见 emulator 对接报告）。
- **L-D1** non-focus resync 消息保留（cache-coupled 决策，已实现 T11 round-2）。
- **L-D6** `SlimFetchMessages` reducer 返回值忽略（cleanup，非阻塞）。

## 11. Artifacts 索引

- 计划/规格：`docs/ocmar/plans/2026-07-18-slimapi-client-v1.md`、`docs/ocmar/specs/2026-07-18-slimapi-client-v1-design.md`。
- ocmar workflow：`.ocmar/workflows/slimapi-client-v1/`（state.json + 19 task brief/report + review 链 round-1→round-8 + oracle designs + verify logs + problem-report-wip.md + STAGE6-ROUND5-PAUSE-HANDOFF.md + RESUME-HANDOFF.md）。
- 关键源：`data/repository/OpenCodeRepository.kt`（token/ticket/configure）、`ui/controller/{HostProfileController,SessionSyncCoordinator}.kt`、`service/ConnectionReconfigureBarrier.kt`、`data/api/OpenCodeApi.kt`（slim endpoints）、`util/RunSuspendCatching.kt`。
- 验证：verify logs `verify-17844*.log`；round-8 review `review-final-rev-gpt-round8-20260719201956.md`。
