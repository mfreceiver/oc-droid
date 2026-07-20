# oc-slimapi v0.2.2 客户端适配 — 交付总结报告

| | |
|---|---|
| **日期** | 2026-07-20 |
| **slug** | `slimapi-v022-client-adapt` |
| **owner** | `ocmar-slimapi-v022-client-adapt` |
| **base** | `401792f`（规划文档已 commit；实现改动 working-tree 未 commit，ocmar 默认） |
| **状态** | ✅ **FINISHED**（revision 20） |
| **wire 影响** | 无（纯客户端；服务端 wire 仍 `1`，v0.2.2 已部署） |
| **双门控** | final whole-branch rev-grok **APPROVED (0 Critical / 0 Important)** + fresh verifier **EXIT=0 / FAILURES=0**（live rerun） |

---

## 1. 需求回顾

**原始一句话**：「同意，按流程开展」——上下文收敛为：按完整 ocmar 流程，在 ocdroid 侧落地 oc-slimapi v0.2.2 handoff（`docs/ocmar/reports/2026-07-20-v0.2.2-ocdroid-handoff.md`）要求的客户端适配。

**spec 要点**（4 项，用户确认全做 P0+P1+P2a+P2b）：
- **P0**：tie-break watermark 升级为 `(updatedAt, messageID)` 二元组字典序 + `/since` 真过滤联调。
- **P1**：q/p envelope `scope.directories` 消费（修 N==0 误清 stale pending 的窄窗口 correctness bug）。
- **P2a**：`/sessions` 列表错误体 code-based（最小深度：带 code 的失败）。
- **P2b**：directory 客户端规范化去重（与 v0.2.2 服务端 fan-out 计数对齐）。

## 2. 方案摘要

**架构**：纯客户端 repo/UI-controller 层。P0 为 correctness-critical 核心，引入单一 `compareWatermark` 纯函数统一 4 个 watermark 比较站点；P1/P2a/P2b 各自独立。

**关键设计决策（grill + 源码核实）**：
1. **messageID 单调性已源码核实**：`packages/opencode/src/id/id.ts`（ascending；`msg_`+12hex(`ts*4096+counter`)+随机尾；同 ms counter 自增）→ 字典序严格单调，含同毫秒 → tuple tie-break 安全可证，无需 monotonicity-agnostic 兜底（YAGNI）。
2. **`bumpUpdatedAt` vestigial**：grep 确认 main src 零 caller（T11 已 reroute）→ `@Deprecated`，不对称化；真实 remote 写路径 `reduceSlimDigest` 的 last-write-wins 合并**无害**（单调 id 保证），不改。
3. **T3 不引入新异常类型**：grill 确认无 caller 做 `as? HttpException` → `SlimapiHttpException` 是 YAGNI；改为 `parseErrorCode`→internal + log + rethrow 原始。
4. **T2 Partial gate 扩展**：N==0 retain-prior 加到 Success **和** Partial（safe superset，堵 Partial+empty+empty-allowlist 残留洞）。

**Task 列表**（4 task，依赖序 T1→T2→T3→T4；T2/T3 共享 OpenCodeRepository.kt 写区故顺序执行）：

| Task | 文件（prod / test） | 关键产物 |
|---|---|---|
| T1 (P0) | SlimapiResync / SlimapiProbe / SlimSseReducer + SlimapiResyncTest / SlimapiProbeTest | `compareWatermark` 纯函数 + 4 站点 tuple 化 + `bumpUpdatedAt` @Deprecated + 测试反转（含 SlimapiProbeTest branch 4/5） |
| T2 (P1) | Slimapi / OpenCodeRepository / SessionSyncCoordinator + SlimapiV1ModelsTest / SessionSyncCoordinatorSlimTest | `SlimapiScope` DTO + `serverScope` + N==0 retain gating（Success+Partial） |
| T3 (P2a) | OpenCodeRepository + OpenCodeRepositorySlimapiEndpointsTest | `parseErrorCode`→internal + `getSlimapiSessions` recoverCatching log+rethrow |
| T4 (P2b) | WorkdirPaths / AppCoreOrchestration / SessionStreamingService + WorkdirPathsTest / AppCoreOrchestrationTest | `normalizeDirectory`（server-facing）+ fan-out normalize-dedup + onResync audit fix |

## 3. 执行过程

全部 4 task **首过 APPROVED**（每 task implementer→rev-grok reviewer，0 Critical / 0 Important，**无 ocmar 内 fix-loop**）。

| Task | status | attempts | 关键 |
|---|---|---|---|
| T1 | verified | 1 | tuple watermark + 测试反转；SlimSseReducerTest audit 无需改（无 same-ts tie fetch-trigger 测试，与 grill 预测一致） |
| T2 | verified | 1 | scope gating；implementer 主动把 gate 扩到 Partial（reviewer 判正确安全超集） |
| T3 | verified | 1 | grill 决策落实（无新异常类型） |
| T4 | verified | 1 | normalizeDirectory；implementer 主动修 all-slashes 边界 + 捕获 onResync 额外位点 |

**Fix wave（final review 后单次）**：final rev-grok 把 5 个 Minor 升级为 must-fix-pre-merge（M1-M3 P0 KDoc 批量 / M4 Partial+N==0 锁测试 / M5 spec §4.3 追认 Partial 对称）→ 单个 fixer 一次清完，check.sh EXIT=0。

**gate 记录**：`parallel-admission:pass`（SERIAL，Phase B 文件重叠 T2∩T3）→ `review-task-1..4:pass` → `final-verify:pass`。

## 4. 测试结果

- **fresh verifier**（live rerun，`--rerun-tasks --no-build-cache`）：**EXIT=0 / FAILURES=0**。
- 日志：`.ocmar/workflows/slimapi-v022-client-adapt/verify-1784518839.log`（首行 `OCMAR_VERIFY_START=`）。
- 新增测试 ~30+：compareWatermark 矩阵 / tuple 推进反转 / Probe branch 4-5 / DTO scope 三态 / Partial+N==0 lock / sessions 503 code log+rethrow / normalizeDirectory 矩阵 / fan-out dedup。
- 各 task implementer 报告 check.sh 均 EXIT=0（3m16s–3m21s）。

## 5. 评审结论

| 门 | 结果 |
|---|---|
| per-task rev-grok ×4 | 全 APPROVED，SPEC=PASS，QUALITY=APPROVED（每 task 0C/0I，2–5 Minor） |
| final whole-branch rev-grok | **APPROVED**，SPEC=PASS，QUALITY=APPROVED，**0 Critical / 0 Important**，MINOR_MUST_FIX=5（已清）/ MINOR_DEFER=8 |
| fresh `_priv-verifier` | **EXIT=0 / FAILURES=0**（live rerun） |

**self-check**（fresh read-only explorer）：20 high / 0 low / 1 missing-evidence（F-1 runtime handoff，plan 标 Final-only，非缺陷）。

## 6. 最终状态

**working-tree 改动**（vs base `401792f`，未 commit，ocmar 默认）：**16 文件 +1086 / −151**
- 9 prod：`Slimapi.kt` / `OpenCodeRepository.kt` / `SlimSseReducer.kt` / `SlimapiProbe.kt` / `SlimapiResync.kt` / `SessionStreamingService.kt` / `AppCoreOrchestration.kt` / `SessionSyncCoordinator.kt` / `WorkdirPaths.kt`
- 6 test：`AppCoreOrchestrationTest` / `SlimapiV1ModelsTest` / `OpenCodeRepositorySlimapiEndpointsTest` / `SlimapiProbeTest` / `SlimapiResyncTest` / `SessionSyncCoordinatorSlimTest`（+ `WorkdirPathsTest` 新建）
- 1 spec doc：`design.md`（bumpUpdatedAt vestigial + Partial 对称 gate 追认）

**是否 commit**：否（ocmar 默认 working-tree diff；base `401792f` = 规划文档 commit）。用户决定是否 commit/发版。

**已知遗留**：
- **F-1（runtime handoff，非代码缺口）**：`/since` 真过滤 + tie-break 联调需 loopback/emulator 实测（`127.0.0.1:4097` / mTLS `opencode.vectory.cn:14097` 已部署 v0.2.2）。单测覆盖纯函数；真实 watermark 推进/同 ms tie 行为留用户联调。
- **8 deferred Minor**（D1-D8，非阻塞 backlog）：D2 reducer equal-ts 直接 fetch-trigger 测试（现间接覆盖）、D4 `getSessions`/`getSessionsForDirectory` slim 分支日志覆盖面（既有架构分叉）、D7 onResync vs fan-out blank 处理不对称（边缘）、其余为测试命名/可选覆盖深度。
- spec §4.4 "最小深度" 文案追认（D6）未做（KDoc + grill 已清晰）。

**后续建议**：
1. 联调验证 F-1（真实 watermark + tie-break 边界）。
2. 视情 commit + `./scripts/release.sh` 发版（用户显式触发）。
3. D2/D4/D7 作为 follow-up backlog。

## 7. 可审计引用

- **ocmar-state**：`.ocmar/workflows/slimapi-v022-client-adapt/state.json`（revision 20, FINISHED；4 tasks verified；6 gates pass）
- **spec**：`docs/ocmar/specs/2026-07-20-slimapi-v022-client-adapt-design.md`
- **plan**：`docs/ocmar/plans/2026-07-20-slimapi-v022-client-adapt.md`（含 Criterion Ownership Matrix + grill 修订记录）
- **工作流产物**：`.ocmar/workflows/slimapi-v022-client-adapt/`（task briefs/reports、per-task reviews、final review、self-check、minor-ledger、verify-log、checkpoints/）
- **manifest**：`.ocmar/workflows/slimapi-v022-client-adapt/manifest.md`
