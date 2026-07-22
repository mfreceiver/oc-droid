# ocdroid → oc-slimapi 配合清单（重构窗口期）

> **日期**：2026-07-22
> **发起方**：ocdroid（本仓）
> **接收方**：oc-slimapi（sibling repo `/home/mar/personal_projects/oc-slimapi`）
> **用途**：ocdroid 即将启动一轮**全量代码健康修复**（批次 T0–T6），核心目标之一是**架构级 legacy/slim 清晰双域隔离**。本清单列出该窗口期对 slimapi 的配合/约束要求，供并入 slimapi 计划安排。
> **完整方案**：`docs/ocmar/specs/2026-07-22-full-refactor-plan.md`（本仓）
> **期望**：slimapi 侧确认本清单各项，并回复一份「需要 ocdroid 配合的反向清单」（见末尾 §回复模板）。

---

## 0. 背景一句话

ocdroid 正在把 slim 与 legacy 从「共享状态脊柱 + 散落 `isSlimMode()` 点补丁」重构为「清晰双域（Legacy Domain / Slim Domain）+ 唯一模式选择点」。重构期间客户端会冻结大量特征化测试（legacy/slim 双轨 golden），因此**强依赖 slimapi 契约稳定**。

---

## 1. 配合项总览

| # | 项 | 优先级 | 性质 | 是否阻塞客户端 |
|---|---|---|---|---|
| C1 | wire 契约冻结期（不 bump） | 🔴 硬约束 | 保持 | 是（全程） |
| C2 | messageID 纯透传确认 | 🔴 硬约束 | 书面确认 | 是（T1） |
| C3 | 事件归属澄清（Shared/Slim-only/Legacy） | 🟠 须确认 | 书面确认 | 否（影响测试矩阵准确性） |
| C4 | G-F1 fixtures 提供与维护 | 🟠 须保持 | 提供/维护 | 否（T0 复用） |
| C5 | `/slimapi/metrics` 端点与聚合语义保持 | 🟠 须保持 | 保持 | 否（实测省流消费） |
| C6 | Opt-A 能力头 + B2 响应矩阵保持 | 🟠 须保持 | 保持 | 否（双域依赖） |
| C7 | 闭环 2 条历史待确认项 | 🟢 闭环 | 确认 | 否 |

> **仅 C1、C2 为硬约束**；C3–C7 多为「保持现状 / 提供确认」，**无强制 slimapi 代码改造**。

---

## 2. 详细配合项

### C1 — wire 契约冻结期（🔴 硬约束）
- **要求**：T0–T6 全程（预计多轮、跨数周）保持 `X-Slimapi-Version: 1` **不 bump**；仅允许加性演进（新可选字段/新可选能力头），**不得引入破坏性 wire 变更**。
- **为什么**：客户端双域隔离 + 特征化 golden 测试依赖契约稳定；契约中途变更会使冻结的测试集体失效。
- **客户端依赖**：`SlimapiVersionInterceptor`（双门闩）、legacy/slim 双轨测试矩阵。
- **如必须变更**：先与 ocdroid 协商，同步 golden，不走静默变更。

### C2 — messageID 纯透传确认（🔴 硬约束 / 权威核验点）
- **要求**：确认 slimapi 对 opencode 的 messageID **纯透传**——含聚合 fan-out 不重映射、不重生 ID。
- **为什么**：客户端 `(updatedAt, messageID)` tie-break 直接对 messageID 做字典序 String 比较，**依赖 messageID 字典序严格单调**（= opencode 原始发射顺序）。若 slimapi 在 fan-out 层改写 ID，T1 tie-break 语义破裂。
- **客户端依赖**：`SlimapiResync.compareWatermark`（4 站点）、`SlimapiProbe`。
- **出处**：`docs/ocmar/reports/2026-07-20-slimapi-v022-release-test-report.md` §6 item1（悬而未决）。

### C3 — 事件归属澄清（🟠 须确认）
- **要求**：书面确认 SSE 事件三类归属：
  - **Shared**（legacy 与 slim 都发）：`message.updated` / `message.part.created` / `message.part.updated` / `message.part.delta`
  - **Slim-only**：`session.digest` / slim error / slim aggregation
  - **Legacy**：不经 slimapi（直连 opencode）
- **并确认**：slimapi **不会向 legacy 路由的 SSE 流注入 slim digest 帧**（反之亦然）。
- **为什么**：客户端 `SseEventRouter` 的模式选择假设「事件类型 ↔ 模式一致」；若 slim 流里混入 legacy-only 帧或反之，路由假设破裂。
- **客户端依赖**：`SseEventRouter`（重构后唯一模式选择点）。

### C4 — G-F1 fixtures 提供与维护（🟠 须保持）
- **要求**：T0 legacy/slim golden 测试复用 **S-D G-F1 fixtures**（equal-ts / 跨页 / limit 截断 / 重连重放 / 对抗循环），v0.3.1 已建；须持续维护并与 ocdroid 共享路径。
- **为什么**：特征化测试需要确定性输入；fixture 漂移会让 golden 失效。
- **如 T0 发现 fixture 缺口**（某事件序列未覆盖）：须 slimapi 补充。

### C5 — `/slimapi/metrics` 端点与聚合语义保持（🟠 须保持）
- **要求**：保持 `/slimapi/metrics` 的 `batch` ledger + 字节比 median/P90（S-C，v0.3.1 已建）。
- **为什么**：客户端「实测省流」消费该端点（`TrafficLedger` + slimapi metrics 双路度量）。
- **如调整 schema**：同步客户端消费侧。

### C6 — Opt-A 能力头 + B2 响应矩阵保持（🟠 须保持）
- **要求**：保持 `X-Slimapi-Capabilities: mid-partial-envelope=1` opt-in + B2 六行响应矩阵（v0.3.1 已部署）；保持非 opt-in legacy 等价（R4-B2-OLD-SEMANTICS）。
- **为什么**：客户端双域 + envelope 分类依赖此契约。
- **feature flag 调整/回滚**：须告知 ocdroid。

### C7 — 闭环 2 条历史待确认（🟢）
来自 `docs/ocmar/reports/2026-07-20-slimapi-v022-release-test-report.md` §6，本重构期一并闭环：
1. **`Partial + scope.directories==0` 是否可能**：ocdroid 已做安全超集 gate（Success + Partial 都 retain-prior），确认该 gate 是否多余。
2. **`/sessions` 错误体消费深度**：ocdroid 当前最小深度（失败 + log code + rethrow 原始），确认是否需按 code（`upstream_http_404` vs `upstream_unavailable`）差异化。

---

## 3. 客户端重构节奏（供 slimapi 评估配合窗口）

```text
T0 安全网（测试冻结）→ T1 状态所有权（依赖 C2 messageID）→ T2/T3/T4 并行拆体 → T5 清理 → T6 构造瘦身
```
- 全程 wire 保持 v1（C1）。
- C2 须在 **T1 启动前**确认；其余 C3–C7 可在窗口期内任意时点回复。

---

## 4. slimapi 回复模板（请填）

> 请 slimapi 侧逐项确认，并列出**需要 ocdroid 反向配合**的内容。

### 4.1 对 ocdroid 配合项的确认

| # | 项 | 确认状态（✅同意 / ⚠️有条件 / ❌不同意） | 备注 / 替代方案 |
|---|---|---|---|
| C1 | wire 契约冻结期 | | 冻结起止窗口？ |
| C2 | messageID 纯透传 | | 含 fan-out？ |
| C3 | 事件归属澄清 | | 三类划分是否准确？ |
| C4 | G-F1 fixtures 维护 | | 共享路径？ |
| C5 | metrics 端点保持 | | schema 是否会变？ |
| C6 | Opt-A 保持 | | flag 状态？ |
| C7.1 | Partial+N==0 | | 是否可能？ |
| C7.2 | sessions 错误深度 | | 是否需差异化？ |

### 4.2 slimapi 需要 ocdroid 反向配合的清单

> 请列出 slimapi 侧（含 v0.3.x → 后续）计划中需要 ocdroid 配合的事项，格式建议：

| # | 项 | 优先级 | 期望 ocdroid 做什么 | 期望时点 |
|---|---|---|---|---|
| R1 | （示例）能力头 bump 联调 | 🟠 | 客户端先行适配 `X-Slimapi-Capabilities` 新 token | … |

---

## 5. 联系与引用

- **ocdroid 完整方案**：`docs/ocmar/specs/2026-07-22-full-refactor-plan.md`
- **ocdroid 隔离前置（Task 2）**：`docs/ocmar/specs/2026-07-22-slim-legacy-isolation-task2.md`
- **历史 handoff**：`<oc-slimapi>/docs/ocmar/reports/2026-07-20-v0.2.2-ocdroid-handoff.md`
- **slimapi 契约（rev G）**：`<oc-slimapi>/docs/v1-contract.md`
