# ocdroid Architecture-Debt Follow-up Batch — Final Report

- **Bundle**: `archdebt-followup-20260807`
- **Date**: 2026-08-08
- **Base**: `9d1efb8d` (= origin/main = annotated tag v0.21.6)
- **Feature branch**: `fix/archdebt-followup`
- **Integration commit**: `d22fc9ac805415824f1a145ddf162806abdaf5c4` (47 files, +1170/−2052)
- **状态**: **DONE — released v0.21.7** (rev-glm 9.5/10 APPROVED)

---

## 1. 状态: DONE

8 项后续架构项（清理+收窄+F2 delete）已发版 v0.21.7。oracle 设计先行 → 4 写域不重叠 fixer lane 实现 → check.sh --full 绿 → **rev-glm 单节点 9.5 门禁 APPROVED (9.5/10)** → 按 PRE_AUTH 发版。

---

## 2. 成果

### 2.1 Oracle 设计（SSOT）
- `/home/mar/personal_projects/ocdroid/.opencode/runs/reviews/2026-08-07/followup-design.md`（52KB，13 节 + appendix）
- 纠正 3 处 explorer 误判 + 抓 1 处漏报；诚实 YAGNI 裁定（F5b DEFER、bF3 NO-OP、ControllerModule 结构重构 DEFER）。

### 2.2 8 项逐项状态（ship 7 + defer/no-op 3；F3 按用户决策不动）

| 项 | 决策 | 实现 | 验证 |
|---|---|---|---|
| **F1** | retire 整个 dead `StatusAggregatorInput` 接口（3 方法全 production-caller-less）+ delete `StatusFetchService`/`SlimStatusFetchCache` + DI plumbing；保留 read side + `MarkSourceFailed` op+reducer | Lane A ✅ | read side grep 完整；MarkSourceFailed op + reducer(`ui/AuthorityReducer.kt`)保留；seedSnapshot/seedApplyEvent/seedMarkSourceFailed 直 dispatch AuthorityOp；Lane A 修 3 个 test bug（identityEpoch 读 store/import/ghost 断言）经 rev-glm 验证稳健 |
| **F2** | delete 10 生产 `settingsManager.lastRoute =` 写点；`val`-ify getter；保留 `KEY_LAST_NAV_PAGE` + 迁移分支 | Lane B ✅ | grep 零生产写点；迁移分支字节不变 |
| **F4a** | 折叠 3 个 identity-equivalent slimapi 写方法（3 层）+ 1 micro-fix（legacy `respondPermission` 加 `isSuccessful`） | Lane B ✅ | 3 方法 3 层全删；micro-fix 有专门测试钉牢 |
| **F4b** | `StatusPollOrchestrator` 收窄为 `ConnectionRepository`+`SessionRepository` 双 seam；wrapper 保留具体签名 | Lane C ✅ | slim 门 `usesSlimStatusFanOut` 读 `connectionRepository`（:147/:170）；slim gating net 绿 |
| **F5a** | PartExpandState kdoc 清理 | Lane C ✅ | import 删 + 2 kdoc 修正 |
| **F5b** | **DEFERRED**（footprint 4-5/6 接口，YAGNI） | — | 设计 §7 |
| **bF2** | `cachedContextUsage` 写移入 `SideEffect`；保留 3 契约 | Lane D ✅ | per-composition `val`、sticky `?.let`、`mutableStateOf` handle 全保留；ChatScaffoldSaveableTest 绿 |
| **bF3** | **NO-OP**（TSC kdoc 已是 component map） | — | grep 零 §Stage-D1/D2 残留 |
| **ControllerModule-note** | 重写 stale "orphaned bindings" 注释 | Lane A ✅ | 事实修正 |
| **ControllerModule-结构重构** | **DEFERRED**（interlock 风险） | — | 设计 §10 |
| **F3** | **EXCLUDED**（用户决策） | — | T13 链/seam/SlimFanOutRetryScheduler 全未触碰 |

### 2.3 测试
- 新增 1（F4a micro-fix 测试）；重写 StatusAggregatorImplTest（~1271 行 churn）、SessionSyncCoordinatorStatusFeedTest、OrchestratorViewModelPassThroughTest、SettingsManagerTest；删除 SlimStatusFetchCacheTest + dead Fake fixtures。
- 净 +1170/−2052。

### 2.4 slim/standard 边界验证结论
- F1: 删除的 slim/legacy fork 不可达；live slim/legacy 路径全未触碰。
- F4a: 折叠后 write path mode-agnostic；directory provenance 两模式保留；fetch-side boundary 未动。
- F4b: 两处 `usesSlimStatusFanOut` 门同单例同布尔（纯 param retype）。
- rev-glm 独立复核确认：无门被扰动（除文档化 param retype）。

---

## 3. 产出

- **设计**: `.opencode/runs/reviews/2026-08-07/followup-design.md`
- **Feature 分支**: `fix/archdebt-followup`
- **Integration commit**: `d22fc9ac`（rev-glm 审的代码 = 发版代码，字节一致）
- **Merge → main**: --no-ff merge commit
- **Tag**: `v0.21.7`（annotated，由 `./scripts/release.sh patch` 创建，changelog = 自 v0.21.6 conventional commits）
- **APK**: `APK/oc-droid-0.21.7-<short>.apk`（本机归档；CI/CD 自动签名上传，勿手动上传）

---

## 4. 验证

- **check.sh --full**: ✅ GREEN（4423 unit tests、lint 0 errors、kover 覆盖率；lint 强制 --rerun-tasks 重跑确认 0 errors，247 warnings 全 pre-existing）。
- **8 设计不变量 grep**: 全通过。
- **rev-glm 9.5 门禁**: ✅ **APPROVED (9.5/10)**（rev-gpt 节点缺 git_ro 工具→omni 授权换 rev-glm 节点；rev-glm 有 git_ro，1 轮通过）。逐文件 diff 验证 7 交付项 + 3 延后；slim 边界无门扰动；测试忠实度（seed 辅助/micro-fix 钉牢/ghost 断言）稳健。

---

## 5. Follow-up（log，未在本批执行）
- **NIT（rev-glm 标注，非阻塞）**: `OrchestratorViewModel.kt:164` 过期 kdoc 仍写 "writes `settingsManager.lastRoute = Sessions`"（F2 已删该写）。注释级，零行为影响。建议改为 "delegates to requestNavigate(Sessions) which mutates navState.lastRoute + bumps navEpoch (no persistence write since F2)"。可在下个小修或并入下批。
- **F6（设计 §3.1/§12）**: aggregator 读取侧也 production-consumer-less，完整退役是产品/seam 决策（同 batch1 F3 类别）。
- **F8（设计 §12）**: F5b 延后触发条件。
- **F9（设计 §12）**: ControllerModule internal-class @Inject folklore 未修正。

---

## 6. 阻塞
无（已发版）。过程中一度 BLOCKED：rev-gpt 节点缺 `git_ro` 工具致 2 次 NO_SCORE（fuse 熔断）→ omni 授权换 rev-glm 节点（batch1 确认有 git_ro）→ 1 轮通过。

---

## 7. 报告（绝对路径）
- 设计: `/home/mar/personal_projects/ocdroid/.opencode/runs/reviews/2026-08-07/followup-design.md`
- 本报告: `/home/mar/personal_projects/ocdroid/.opencode/runs/reviews/2026-08-07/followup-report.md`

---

## 8. 审计
- **Base**: `9d1efb8d` (v0.21.6) → **Tag**: `v0.21.7`
- **Integration commit**: `d22fc9ac` @ `fix/archdebt-followup`（rev-glm 审 = 发版代码）
- **Merge**: --no-ff to main
- **review_prep binding**: `rv_20260808-003254_e044adb0_1017384`（head=d22fc9ac）
- **rev-glm session**: rev-3 / ses_0212be5c8ffeDa22EHMe2Yx5Ld → APPROVED 9.5/10
- **Lane sessions**: fix-1(A)/fix-2(B)/fix-3(C)/fix-4(D)，全 0 设计偏差
