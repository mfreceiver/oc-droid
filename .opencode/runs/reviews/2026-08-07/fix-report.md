# ocdroid 2026-08-07 评审修复 — fix-report

> bundle: `rvfix-20260807` · SSOT: `handoff-design-code-review.md` · 原 review_id `rv_20260807-102253_e044adb0_593440` (head aeb6e67)
> **状态: `DONE`** — 12 项 + onPrimary 连带修复全部落地；check.sh 绿；rev-kimi 完整 diff APPROVED；`origin/main`=dad3b3b2；**tag `v0.21.4`@dad3b3b2 已 push（收口）**；v0.21.3@136aa16a 保留为不完整中间态。

---

## 0. 总览

12 项评审修复（立即 7 + 短期 5）全部落地，5 lane 写域互不重叠并行实现。check.sh 多轮绿（含 release.sh 的 `--lint`）。REVIEW 经 rev-kimi 单节点（改链后）两轮 + 完整 diff 显式 APPROVED。`./scripts/release.sh patch` 在 `dad3b3b2` 打 `v0.21.4`（含 APK 归档 + changelog），tag 已 push origin。`v0.21.3`（用户手动提前打、停在 `136aa16a`、缺 onPrimary 修复）按用户决策保留不动。

---

## 1. 12 项逐项状态

| # | 项 | lane | 状态 | 文件 / 说明 |
|---|---|---|---|---|
| 1 | 删死 token `addedLine`/`deletedLine`/`StopRed` | 1 | ✅ | `SemanticColors.kt`(-2)、`Color.kt`；grep 复核零引用 |
| 12 | a11y: DarkPrimary + compactTypography | 1 | ✅（含补修） | `Color.kt` DarkPrimary `#3B5CF6`→`#8FA3F8`；`Type.kt` label≥11sp/body≥12sp。**补修**：rev-kimi 发现 onPrimary 配对回退 → `dad3b3b2` 把 `DarkOnPrimary #FFF`→`#0F1A4E`（M3 dark 惯例，≈6.9:1 AA） |
| 2 | SessionTree 死代码 | 2 | ✅ | `SessionTree.kt`：`buildSessionTree`/`flattenVisibleTree`/`SessionNode` → `internal`（ChatViewModelTest 仍依赖，按评审「至少改 internal」） |
| 3 | `configureServer(): Job?` → Unit | 2 | ✅ | `HostProfileController.kt`（删 `: Job?`+`return null`+`import Job`）；级联 `MainActivity.kt:75` 删 `?.join()`；透传点单表达式自动适配 |
| 4 | 删 `ConnectionCoordinator.diagLayer` | 2 | ✅ | `ConnectionCoordinator.kt`(-7)；`SendOrchestrator.kt` 删日志 `layer=...` + 注释；grep 归零 |
| 5 | 重复 import + 已解 FIXME | 2 | ✅ | `ChatMessageRow.kt` 删重复 `isThinPlaceholder` import；`AppShell.kt` 删已解 `FIXME(P4-features)` |
| 8 | abort 部分失败语义 | 3 | ✅（含补修） | `ChatViewModel.kt`：`RecursiveAbortOutcome`(Complete/Partial) + `SubtreeFetch(hadErrors)`；Partial 发 `UiEvent.Error`。**补修**：rev-gpt 发现 `Result.failure` 漏计 → `136aa16a` 加 `result.isFailure`/`getChildren.isFailure` 检查；`InteractionGateway.abortSession` 补 `response.isSuccessful` 映射（mirrors sendMessage）。4 个测试 |
| 7 | Composer stop-confirm → AppConfirmDialog | 4 | ✅ | `Composer.kt`；行为/回调/destructive 全保留 |
| 9 | `confirmEnabled` 参数 + MessageCard revert 迁移 | 4 | ✅（含补修） | `AppConfirmDialog.kt` 两 overload 加 `confirmEnabled`；`MessageCard.kt` revert-confirm 迁移（`confirmEnabled = confirmState==ConfirmOpen`）。**补修**：rev-gpt 发现禁用态仍显 error 色 → `136aa16a` 改 `!confirmEnabled → LocalContentColor.current` |
| 10 | WorkdirControl 单选选中态 | 5 | ✅ | `WorkdirControl.kt`：`trailingContent = { PickerTrailingCheck(selected = isCurrent) }`（恒渲染防跳动 + primary） |
| 11 | 两 overlay 迁移 + 清 dp | 5 | ✅ | `ChatServerManagementDialog.kt` + `ChatOverlayHost.kt` error dialog → `AppFormDialog`；清 `RectangleShape`/散落 dp（4.dp→spacing1、12.dp→spacing3）；400.dp 内容高约束保留（无 token） |
| 6 | spec 同步 | 编排者 | ✅ | `docs/specs/ui-style-spec.md`：§1.2 登记 2 个迁移 overlay（ServerManagement / error-detail → C form）；§2.2 单选约定（收敛 3 方言，SessionPickerSheet grandfathered） |

**12/12 完成，0 跳过。** 额外补修（评审熔断产物）：gateway HTTP→Result.failure、abort Result 检查、onPrimary a11y 配对、dialog 禁用色、3 个 nit。

---

## 2. 改动统计

5 commits（`6586868a..dad3b3b2`），24 files，约 `+340 / -230`。分布：theme(3) + 死代码(7) + abort(1+测试2+strings2) + dialog(3) + overlay(3) + gateway(1) + spec(1) + MainActivity(1)。

---

## 3. check.sh 门禁

`./scripts/check.sh`（编译 + `testDebugUnitTest`）**多轮全绿**；release.sh 的 `--lint` 变体亦绿（release 时跑过）。a11y 对比度按 bundle 决策取建议值并视为完成（#0F1A4E on #8FA3F8 手算 ≈6.9:1，rev-kimi 复核 AA 通过）。

---

## 4. 全量 REVIEW 结果

**改链前（rev-gpt）**：rev-gpt 首轮 `git_ro` 出 **CHANGES_REQUESTED**（2×P1 abort `Result.failure` 漏计 + 1×P2 dialog 禁用色）→ 已修（`136aa16a` + gateway）。改链后 rev-gpt fresh/reuse 会话 `git_ro` 工具不可用（rev-gpt 子代理 toolset 问题），omni 政策变更为 **rev-kimi 单节点（无 fallback）**。

**改链后（rev-kimi 单节点）**：
| pass | 结论 |
|---|---|
| rev-5 | **CHANGES_REQUESTED** — P2 a11y（`DarkOnPrimary` 配对回退 ~2.4:1）+ 3 nit → 已修 `dad3b3b2` |
| rev-6 | **PASS** — 4 findings 全 resolved，#0F1A4E on #8FA3F8 ≈6.9:1，无新问题 |
| rev-7 | **APPROVED** — 完整 diff `6586868a..dad3b3b2` 逐 commit（含用户手动 merge/docs 两 commit 重点审）：cdac6054=纯 markdown 228 行、75447945=干净 merge 零 stray，全清 release-ready |

REVIEW **通过**（rev-kimi APPROVED，满足 omni push 门禁①）。reviewer 反馈均按 fixer 熔断修复后重新过审。

---

## 5. 产出 / 审计

| 项 | 值 |
|---|---|
| feature 分支 | `fix/review-20260807`（已 merge 入 main） |
| merge commit（用户手动） | `75447945` |
| main HEAD / `origin/main` | `dad3b3b2`（review-passed 完整态，已 push） |
| **收口 tag（新）** | **`v0.21.4` @ `dad3b3b2`**（release.sh patch 创建，已 push origin；CI/CD 将自动签名构建+上传） |
| 保留 tag（不完整中间态） | `v0.21.3` @ `136aa16a`（用户手动提前打，缺 onPrimary 修复；按用户决策保留不动） |
| 本机 APK 归档 | `APK/oc-droid-0.21.4-dad3b3b2.apk`（仅模拟器冒烟用，勿手动上传） |

**commit 清单（`6586868a..dad3b3b2`，来源标注）**：
| commit | 来源 | 说明 |
|---|---|---|
| `6cd933af` | main-12项 | 12 项评审修复 |
| `cdac6054` | 用户-docs | archive `handoff-design-code-review.md`（228 行纯 markdown，in-scope 不可疑） |
| `75447945` | 用户-merge | merge `fix/review-20260807`（干净，仅带入 `6cd933af`+`cdac6054`） |
| `136aa16a` | main-第一轮修复 | rev-gpt findings（abort Result + gateway HTTP 映射 + dialog 禁用色 + 测试） |
| `dad3b3b2` | main-onPrimary | rev-kimi findings（`DarkOnPrimary` a11y 配对 + 3 nit） |

> `136aa16a` message 提"rev-gpt findings"为改链前措辞残留（omni 已确认不影响代码，不重写已 push commit）。

---

## 6. 流程备注

- **并发 actor**：本 bundle 执行期间检测到用户手动 git 干预（merge `fix/review-20260807` 入 main、commit docs、提前打 `v0.21.3` 并 push）。omni 确认为用户手动操作（已请用户停止），发版完全交回 main 自动。代码完整无丢失；dad3b3b2 完整态 + fast-forward 非破坏。
- **顺序越界记录**：main push 发生在 omni 追加 push 门禁消息到达前（async 竞态），omni 记为既成事实；tag/release 严格在 rev-kimi APPROVED 后执行。
- **下一步**：无——已发版收口。CI/CD（`.gitea/workflows/release.yml`）将据 `v0.21.4` tag 自动签名构建并上传。
