# ocdroid 移动端重构 —— 自动执行入口提示词

> 用法：在新 opencode 会话（`/home/mar/personal_projects/ocdroid`）整段粘贴执行。本会话自动推进 6 个阶段（0 → 1A → 1B → 1C → 2 → 3），自测自审，不得中途停止或询问。全部完成后输出汇总。

---

## 1. 起步

1. **读取三份基础文档**（全文）：
   - `docs/redesign-execution-plan.md` —— **权威执行蓝图**（本会话的工作依据）。读完后用 `todowrite` 创建 6 个阶段追踪项。
   - `docs/redesign-mobile-ux-architecture.md` —— 问题诊断、目标 IA、目标架构。
   - `docs/redesign-mobile-compose-scheme.md` —— 组件清单（B）、代码骨架（D）、交互规范（E）、状态新增（F）、阶段顺序（G）、问题映射（H）。

2. 验证仓库根目录 `/home/mar/personal_projects/ocdroid` 存在且可写。

3. `todowrite` 初始化（`in_progress` 只标当前阶段）：
   - Phase 0 紧急修复 + revert 数据正确性
   - Phase 1A 壳迁移（opt-in）
   - Phase 1B 聊天 chrome + composer
   - Phase 1C 状态槽 + revert/fork 动作
   - Phase 2 搜索 + 上下文 + Workspace
   - Phase 3 Settings 清理 + 导航打磨 + 收壳

---

## 2. 全局约束（任何阶段不得违反）

1. **增量可交付**：每阶段保持 app 可运行，入口单调迁移、不断路。
2. **每阶段必过 `./scripts/check.sh`**（编译+单测）；否则不进入下一阶段。LSP 已关，check.sh 是唯一编译反馈。
3. **不动服务端协议**：文件引用走方案 A（`PartInput(type=text)` + `File: <path>`），零 API/Schema 改动。
4. **以 UI 层为主；state/domain/cache 改动为受控例外**（仅 §13 中各阶段声明允许的范围）。不重构 controller/repository/state 架构（那是 Phase 4，不在本会话）。
5. **不重复实现现有逻辑**：重接 UI 时订阅现有 slice，不改 `SessionSyncCoordinator` 行为。
6. **遇到无法编译或测试失败**：修复至通过再继续；不跳过。
7. **遇到设计选择（命名/风格/布局微调）**：自行做合理决策，不询问。
8. **模拟器纪律**：如需 UI 验证，用 `./scripts/emulator.sh status → start → 验证 → stop`。模拟器是本机共享资源，不在物理机做 UI 调试。

---

## 3. 阶段执行流程（每阶段循环）

对第 N 阶段：

1. **读取 §13.`N` 的提示词**（`docs/redesign-execution-plan.md`）。它包含了阶段范围、改动文件清单、验证门、红线。
2. **读取该阶段涉及的现有文件**（即 §13 中引用的文件），理解当前实现。
3. **执行代码改动**：
   - 新增文件按方案骨架（D.1-D.8）实现，绑定现有 state。
   - 修改文件按 §13 指定范围。
   - **不动**红线列出的文件/逻辑。
4. **自测**：`./scripts/check.sh`。若失败，修复到通过。
5. **验证 §8 验收矩阵**：逐项确认该阶段的 done-definition 场景可用（代码层面的验证，不需要模拟器的不等性检查可以用单元/集成测覆盖）。
6. **模拟器回归**：如果 §13 要求模拟器验证，执行 `emulator.sh status → start → adb install/验证 → stop`。若模拟器已在用（status 报「运行中」），跳过手测，继续。
7. **check.sh 通过 + 验收矩阵满足 = 阶段完成**。标记 todowrite。
8. **如果 check.sh 连续 3 次修复仍不过**：输出当前错误、改动的文件列表上下文，然后**停止**（此时可以询问——说明有真正阻塞问题需要人工判断）。
9. **如果验收矩阵某项不可验证（如缺少测试基础设施）**：在阶段完成报告中注明，不阻塞前进。
10. **进入下一阶段**。

---

## 4. 阶段序列与 §13 索引

| 顺序 | 阶段 | 提示词 | 前置 |
|---|---|---|---|
| 1 | Phase 0 紧急修复 + revert 数据正确性 | §13.0 | 无 |
| 2 | Phase 1A 壳迁移（opt-in） | §13.1A | Phase 0 |
| 3 | Phase 1B 聊天 chrome + composer | §13.1B | Phase 1A |
| 4 | Phase 1C 状态槽 + revert/fork 动作 | §13.1C | Phase 1B + Phase 0 的 A3-1 |
| 5 | Phase 2 搜索 + 上下文 + Workspace | §13.2 | Phase 1C |
| 6 | Phase 3 Settings 清理 + 导航打磨 + 收壳 | §13.3 | Phase 2 |

**说明**：
- 每个 §13 提示词是自包含的：范围、文件、红线、验证门都在里面。
- 不要跳过任何阶段。
- Phase 1A 添加新的 Gradle 依赖后在依赖下载完成前可能第一次 check.sh 会因 artifact 未解析失败——等网络完成后重试即可。
- Phase 3 的 `release.sh minor` 场景：由于本会话是开发调试用途，你可以在完成所有改造后执行 `./scripts/check.sh --full` 来替代发版——版本号的 bump 留给用户手动或后续流程处理。但要输出一个发版摘要（改了什么、版本 BOM 如何调整、兼容性验证结果），以便用户执行 `./scripts/release.sh minor`。

---

## 5. 完成后

- 最终 `todowrite` 全部标记 `completed`。
- 输出总结报告包含：
  - 每个阶段的状态（completed / skipped with reason / blocked）
  - 总的改动文件数（新增+修改）
  - check.sh 最终结果
  - 已知遗留问题（如有）
  - Phase 3 发版摘要（依赖升级、manifest 调整、兼容结论）

---

## 附录：捷径引用

各 §13 提示词位于 `docs/redesign-execution-plan.md` 中，在会话中用 `grep -n '^### 13\.' docs/redesign-execution-plan.md` 定位行号后读取：
- §13.0 Phase 0 — 搜索 `### 13.0 Phase 0`
- §13.1A Phase 1A — 搜索 `### 13.1A Phase 1A`
- §13.1B Phase 1B — 搜索 `### 13.1B Phase 1B`
- §13.1C Phase 1C — 搜索 `### 13.1C Phase 1C`
- §13.2 Phase 2 — 搜索 `### 13.2 Phase 2`
- §13.3 Phase 3 — 搜索 `### 13.3 Phase 3`
