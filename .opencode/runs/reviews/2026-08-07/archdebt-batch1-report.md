# ocdroid Architecture-Debt Batch 1 — Final Report (Items 14, 16, 17)

- **bundle_id**: archdebt-batch1-20260807
- **Date**: 2026-08-07
- **Head baseline**: dad3b3b2 (= origin/main = tag v0.21.4)
- **Outcome**: ✅ **DONE — released as v0.21.5**

---

## 状态: DONE

## 成果

### Oracle 设计阶段 (先行, SSOT)
- **设计文档**: `/home/mar/personal_projects/ocdroid/.opencode/runs/reviews/2026-08-07/archdebt-batch1-design.md`
- 含 slim/standard 模式边界调研 (§2)、三项细化设计 (§3/§4/§5)、抽象层次论证、slim/standard 影响分析、写域不重叠分析 (§6)。
- 关键设计决策: 项14 收窄到 FileVcsRepository seam; 项16 删字段但保留 KEY_LAST_NAV_PAGE 迁移读源; 项17 经证据链证明 ProcessStatusPoller 在 dad3b3b2 **双重 inert** (死循环 + 闭路 backoff/retry 无入口 + runner 被 slimPerSessionStatusEndpointAvailable=false 门关闭) → shrink-and-rename (删死循环机、保留 backoff+单飞重试 seam、重命名 → SlimFanOutRetryScheduler、抽常量 → SlimFanOutBackoffPolicy)。

### 三项逐项状态
| 项 | 状态 | 摘要 |
|---|---|---|
| **14** Repository 收敛 | ✅ 完成 | 渲染链 ChatViewModel→ChatMessageContent→MessageCard→MessageRow→PartView 全部收窄为 FileVcsRepository (叶 TextPart 本就用此 seam)。无运行时变更。 |
| **16** lastNavPage 迁移+删 | ✅ 完成 | 删 NavState.lastNavPage + setLastNavPage + SettingsManager/NavigationPrefs 访问器 + NavRoute.legacyPage/fromLegacyPage; 保留 KEY_LAST_NAV_PAGE + lastRoute getter 一次性迁移读; 新增 4 个迁移测试 (此前未测)。无运行时变更 (setLastNavPage 零生产调用方)。 |
| **17** ProcessStatusPoller 去留 | ✅ 完成 | shrink-and-rename: 删死循环机械 (~150 行) + statusAggregatorInput/statusAggregator/clock 参数; 保留 backoff+单飞重试 seam (scheduleBackoff/resetBackoff/requestSlimFanOutRetry/runSlimFanOut 三点身份纪律); ProcessStatusPoller → SlimFanOutRetryScheduler; 抽 SlimFanOutBackoffPolicy; 删死 API SseRecoveryPolicy.applyJitter。无运行时变更 (移除代码经证据链证明不可达)。 |

### 测试新增/改动
- 新增: SlimFanOutRetrySchedulerTest (迁移 backoff 测试 + 4 新测试: null-identity no-op、host-switch 丢弃、single-flight、resetBackoff 取消); 含 rev-kimi 要求的 `mid-sweep host switch drops the summary before sink` 回归测试 (钉 runSlimFanOut 第三点 isCurrent 检查)。SlimFanOutRetryWiringTest (迁移 test 5-6)。4 个 lastRoute 迁移测试。
- 删除: ProcessStatusPollerTest (循环测试)、SlimFanOutPollerWiringTest (循环测试)、StatusPollingDowngradeSeamsRegressionTest (前提已失效)。
- 改动: 13 个测试文件跟随符号重命名/参数清理。

### slim/standard 边界验证结论 (两项约束)
- **slim/standard 干净划分保持**: StreamingModule 的 runner lambda slim 门控 (isCurrent / usesSlimStatusFanOut / slimPerSessionStatusEndpointAvailable / sweepStartEpoch) **逐字保留**, 未丢失任何门控逻辑。standard 模式 runner 在 usesSlimStatusFanOut 门返回 null (零 HTTP); slim 模式三条活状态路径 (SSE digest relay / cold-start bulk / SSE-loss REST fallback) 所在文件均不在变更集。
- **抽象质量**: 移除的具体依赖 (OpenCodeRepository in render chain / ProcessStatusPoller 死循环 / applyJitter 死 API) 未以任何形式泄漏回; 新 seam (FileVcsRepository / SlimFanOutBackoffPolicy) 为既有或窄职责接口, 服务可测试性与分层。两 reviewer 独立确认。

## 产出
- **feature 分支**: `fix/archdebt-batch1`
- **refactor commit**: `51e2c5d5` (40 files, +1446/−2043)
- **merge commit**: `45dfe0db` (--no-ff → main, = origin/main)
- **tag**: `v0.21.5` (annotated, `e2492241` → 45dfe0db); patch from v0.21.4

## 验证
- **check.sh**: ✅ 全绿 (compile + detekt lint + testDebugUnitTest + coverage), 含 `--full`。
- **rev-glm**: 1 轮 → **APPROVED** (独立复核 §5.1 活跃度前提 + §5.5 slim 影响证明均成立; 仅 2 非阻塞文档 nit)。
- **rev-kimi**: 2 轮 → **APPROVED**。R1 = CHANGES_REQUESTED (2 个 🟠 major: ① mid-sweep host-switch 第三点 isCurrent 回归测试丢失 + kdoc 谎报覆盖; ② applyJitter 死 API + 抽象边界文档不实陈述)。修复后 R2 = APPROVED (两 major 实质修复验证, 无回归)。
- **串联验收 = APPROVED** (rev-glm PASS + rev-kimi PASS)。

## 下一步: 无 — 已发版 v0.21.5

## 阻塞: 无

## 报告
- 本报告: `/home/mar/personal_projects/ocdroid/.opencode/runs/reviews/2026-08-07/archdebt-batch1-report.md`
- 设计 SSOT: `/home/mar/personal_projects/ocdroid/.opencode/runs/reviews/2026-08-07/archdebt-batch1-design.md`

## 审计
- branch: `fix/archdebt-batch1` (merged, not deleted)
- refactor commit: `51e2c5d5`
- merge→main: `45dfe0db` (= origin/main)
- tag: `v0.21.5` (`e2492241`, annotated) — pushed to origin
- review bindings: rv_20260807-150737_e044adb0_723770 (R1), rv_20260807-153414_e044adb0_737555 (R2)

## 范围边界遵守
- 仅批1 三项 (14/16/17)。未碰批2: 项13 (DI Wave2.2)、项15 (God 类拆分)。未碰 stream 生命周期/重连/watchdog, 未拆 ChatScaffold。
- AppCore.kt 仅做项17 相关 (poller 参数重命名 + dispatch 分支 + kdoc 重写), 未做整体重构 (留给批2 项13)。
- 发现的后续项 (F1-F5, 见设计文档 §7) 均标注为后续建议, 未擅自扩大范围。
