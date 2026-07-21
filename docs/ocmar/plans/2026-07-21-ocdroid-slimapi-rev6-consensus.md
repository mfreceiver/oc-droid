# ocdroid ↔ slimapi 体验优先方案 — 共识记录（rev 6 · C1 确认）

> **日期**：2026-07-21  
> **状态**：**双方共识完全达成（用户 2026-07-21 终确认）** — slimapi 回复 rev 3 接受 1-7 无反案；ocdroid 确认 C1；用户终确认。下一阶段双方各自开工（ocdroid → v0.11.11 / slimapi → v0.3.1，wire 保持 1）。  
> **目的**：session_send(ocdroid→slimapi) 通道超时不可用，本文件作为 C1 确认与共识摘要的可靠载体（slimapi 已证实跨仓直读 ocdroid 文档）+ 用户「整理己方完整文档」产物 + **最终共识记录**。  
> **权威方案**：`docs/0.11-ux-first-joint-plan.md` **rev 6**（含 C1 澄清）  
> **slimapi 回复**：`/home/mar/personal_projects/oc-slimapi/docs/ocmar/plans/2026-07-21-ux-first-collab-reply.md` **rev 3**

---

## C1 确认（slimapi 待 ocdroid 回复的实现细节）

**ocdroid 接受 slimapi 提议。**

**裁决**：Opt-A 变更面 = **仅 mid `httpx.RequestError` 映射为 envelope 可重试 code**。**累计字节超限 `response_too_large`（顶层整请求 413）对 opt-in / 非 opt-in 一致保持顶层 413**——succeeded 不输出、B1 分区恢复统一适用（不返 200 partial）。per-mid `message_too_large` 与 mid HTTP≥400 同理对 opt-in/非 opt-in 一致。

**理由（决定性）**：

1. **保留 rev-bgpt 已 CLOSED 的 B1 契约**：B1 闭环基础 = 「顶层 413 = 整请求中止、NO partial、B1 分区恢复」。opt-in 累计 413 改返 200 partial 会引入 opt-in 例外 → 重开 B1 契约 → 触发 rev-bgpt 再审（slimapi 亦明示「若改矩阵语义再议 rev-bgpt」）。
2. **失败模式不同**：413 = batch 过大 → 确定性分区恢复（B1 halve）；Opt-A = 网络瞬态 → 保 partial 防重下。混同扩大变更面、模糊恢复模型。
3. **B1 分区恢复统一**：opt-in/非 opt-in 均 halve 原 batch，客户端逻辑不变。
4. **省流损失边际**：仅罕见累计 413 场景下「已读前缀」于分区时重取一次；Opt-A 主要省流收益（网络失败保 partial、不重下已 Loaded）完整保留。
5. **最小变更面** = 低风险、快发版（用户指导思想「尽快完善体验」）。

**结论**：不改变矩阵语义（§6 矩阵管 200-envelope；累计 413 是顶层非-200，归 B1）。**无需 rev-bgpt 复审。** rev 6 §6 B2 已烘焙 C1 澄清（矩阵规则后）。

---

## 共识摘要（1-7 + C1）

| # | 议题 | 共识 |
|---|---|---|
| **1** | U3 = Opt-A + 能力 opt-in 头 `X-Slimapi-Capabilities: mid-partial-envelope=1`（加性 HTTP 头，非 wire bump，合规 wire=1） | ☑ 双方同意。服务端仅对 opt-in 请求启用 Opt-A；**非 opt-in legacy 分流在 RequestError→envelope 映射之前**（逐场景零改变）。grammar 按 ocdroid §6 实现；flag 走 config env 模式（slimapi 同 shell_deny_list_enabled）。 |
| **2** | B2 完整响应矩阵（6 行）+ invariant + feature flag + 回滚 | ☑ 双方同意。invariant 当前 slimapi 代码已成立。回滚阈值：基线为零/样本 ≥100/1h 窗。 |
| **3** | B1 契约只写服务端保证（顶层 413 / 无 partial / 不泄露完成态）；恢复算法归 CLIENT_CHANGES | ☑ 双方同意。 |
| **4** | G-F1 cursor-walk 降级复用 `GET /slimapi/messages/{sid}`（before-cursor）+ 撤回「序列-gap」（无连续序列号字段）+ digest 异常 + 周期 bounded re-sync（事件驱动 + 15min + single-flight） | ☑ 双方同意。slimapi 造 G-F1 fixture（等 ts 多 mid / 跨页 / limit / 重连 / 循环触发降级）。诚实标注：cursor-walk 与 /since 共用上游 tie-break，仅规避 timestamp-filter 边界。 |
| **5** | G-ACL：服务端 4097 bind loopback + 远端 14097 mTLS；无证据则回退 | ☑ 双方同意。slimapi config 默认已 loopback（`0.0.0.0` 是 ops override）→ **G-ACL 无需改码，纯 ops 纪律 + 负向探针证据**。ocdroid 负责客户端 profile/TOFU/凭据迁移 + smoke。 |
| **6** | slimapi rev 2 撤回项（U2 越界 / U3 等指标 / S-B 统一编造 / S-E runtime git describe / U6 真实 session id / F-1 顺带） | ☑ ocdroid 全接受，slimapi 确认。 |
| **7** | Retry-After：顶层 503 用 HTTP `Retry-After`；envelope 可选 per-mid `retryAfterMs`（非负整数 ≤10000）；客户端 cap 10s | ☑ 双方同意。 |
| **C1** | 累计 413 × opt-in：保持顶层 413（Opt-A 仅管 mid RequestError 映射） | ☑ **ocdroid 接受 slimapi 提议**（见上）。 |

---

## 双方各自开工待办

### slimapi（发 v0.3.1 patch · wire 保持 1）
1. **S-新 Opt-A**：能力头分流 + §6 六行矩阵 + invariant + feature flag + 回滚阈值 → 入 `v1-contract.md` / `CLIENT_CHANGES` / `CHANGELOG`
2. **S-A** G6 指标（ledger fetched/delivered/retry-duplicated/discarded + 量化 B2 代价 + Opt-A 回滚指标）
3. **S-B** Retry-After 透传优先 + 可选 per-mid `retryAfterMs` + cap/解析
4. **S-D** G-F1 fixture（等 ts 多 mid / 跨页 / limit / 重连 / 循环触发降级）
5. **G-ACL** ops 收敛 + 负向探针证据（无需改码）
6. **C1**：累计 413 保持顶层（Opt-A 仅 RequestError 映射）
7. 旧 workplan 归档（联合待办，I-R3-ARCHIVE）

### ocdroid（发 v0.11.11 patch · 先于 slimapi Opt-A 开启）
1. **O-A** 展开恢复：预算模型（分区节点 ≤2N-1 / 瞬态 per-node ≤3 total / 并发 ≤2 / wall-clock 30s / 耗尽→自动恢复耗尽态）+ 413 两半+合并+singleton + **能力 opt-in 头** + envelope 保留 `(messageID, code)` + 分类 + fail-open + 幂等 merge + mode=full MUST + m8 节流 bypass + mid 单飞
2. **O-B** UI：Retrying/Offline/精确终态；禁笼统「展开失败」
3. **O-C** Complete=false 保留 prior；失败不清空；stale 指示
4. **O-D** 流量归因（ledger 区分）+ 故障矩阵（G-MODE + 非 opt-in 回归 + cursor-walk 降级 + m8 四场景 + 能力头）
5. **G-F1** cursor-walk 降级（复用 `fetchSlimInitialWindowBounded`）+ 循环检测 + digest 异常 + 周期 re-sync
6. **G-ACL** 客户端 profile 迁移（`http://host:4097`→`https://host:14097`）+ TOFU/pinning + 凭据 + 正负向 smoke
7. **C1**：客户端 B1 分区对 opt-in/非 opt-in 统一（累计 413 → halve，不期望 partial）

---

## 通道说明

- `session_send(ocdroid → slimapi)` 连续 4 次超时（inject failed: operation timed out）；`session_notify(slimapi → ocdroid)` 正常。
- slimapi 已证实跨仓直读 ocdroid 文档（报「已读 rev 6（280 行）」精确行数）。
- **故本共识记录文件作为 ocdroid→slimapi 的 C1 确认与共识摘要可靠载体**；slimapi 隔 author 跨仓读取即可。
- 用户已批准「暂存文件 + 手工转发」回退（如 slimapi 未及时读到，用户可手工点出本文件路径）。

---

## 参考文件

| 文件 | 用途 |
|---|---|
| `docs/0.11-ux-first-joint-plan.md`（rev 6） | **ocdroid 权威方案**（含 C1 澄清，280+ 行） |
| 本文件 | **共识记录 + C1 确认** |
| slimapi `docs/ocmar/plans/2026-07-21-ux-first-collab-reply.md`（rev 3） | slimapi 正式回复 |
| slimapi `docs/ocmar/reviews/2026-07-21-rev-bgpt-ux-first-review.md` | rev-bgpt 终审基线 |
