# ocdroid Refactor + Bilateral Handoff

> 状态快照：**v0.13.0 已发版**（2026-07-23）。本文件是下一会话/agent 的入口：记录已落地、剩余任务、关键产物、可复用 session、与硬约束。

---

## 1. 已发版

- **v0.13.0**（tag 在 `f448318`）：token-stream 客户端特性 + 双边 §5 修复。minor bump（新用户可见功能）。APK + notes 已上传 Gitea（release 548）。
- 提交链（main，均 gated rev-grok 9.5–9.8）：
  - `a6521e0` — α→δ：6a utils + L1b AppLifecycleMonitor + L1c SLC + L1d OwnershipGate + L1a SSC + L1e Composer + L2 AppAction
  - `665cf79` — ε：L3a′ PollerRuntime 类型统一 + L3c BootstrapFailure 两步收敛
  - `3c9173f` — ζ-1：L4a1 TofuRepository ‖ L4b SettingsManager 分域 ‖ L4c ConnectionHealthProbe
  - `d4b22da` — 双边 C-1/C-2/C-3（token-stream）
  - `1986567` — 双边 token_memory_limit reconnect（联合终审 Option A）
  - `f448318` — ζ-2：L4a2 ExpandBatchEngine

## 2. 双边 §5（slimapi token-stream）— 已闭环

- 联合终审 re-gate **GO 9.7**（rev-bgpt）。双方已发版（不 bump `X-Slimapi-Version`）。
- 出货（ocdroid 侧）：C-1=A（done:null 保累计 buffer）· C-2（ResyncReason 5 值 + UNKNOWN fallback，不静默丢帧）· C-3（删 part_too_large）· token_memory_limit 触发 reconnect。
- Wire 契约（客户端 authoritative）：5 reason（`reconnect_no_replay`/`subscriber_backpressure`/`token_memory_limit`/`session_idle`/`session_deleted`）+ UNKNOWN fallback；`triggersReconnect=true` 仅前 3；不发 part_too_large（超限→`truncated:true`）；lever1 done=marker；lever2 gzip 客户端默认透明；health `features.tokenStream` dual-read fail-closed。
- **延后（post-release，非阻塞）**：C-4（doc 对齐，本轮做）· V-B（stunnel 运维 idle 断实证）· token_hub 拆包。
- slimapi 主会话：`ses_075953166ffeUULAsWATEcjoS8`（cross-session via `session_send`；用户协调）。

## 3. 剩余 refactor（plan v3 α→θ）

### ζ-3 L4a3 — 最高风险，串行收尾（下一步）
OCR domain-delegate + compat-facade + cluster **7**（slim 6 透孔删）· **9/22/24**（repository 重复）· **19**（checkHealth 双路委托）。原文件留 ~100 行 compat 门面。
**必须保**（L4a0 不变量，详见 §4 exp-1）：
- **I5** `slimStateLock` 实例共享（不能复制；domain-delegate 与 facade 同一锁）
- **I6** `configure()` 原子事务（ticket→sslConfigFactory.configureClientCert→hostConfig.configure→rebuildClients→completeSlimReconfigure 不得拆）
- **I7** `@Synchronized` monitor 序列化（configure ↔ currentSslConfig ↔ applyTofuDecision ↔ rebuildClients）
- **I8** `serverCompatProfile` 写点（probeSlimapiHealth/checkHealthFor 尾部）
- **I15** `coldStartSlimSync` token threading（外层 capture、内层 require）
- **I20** 公共类型 FQN 向后兼容（`SlimColdStartSnapshot`/`SlimAggregationOutcome`/`TofuCaptureResult`/`SlimCommitToken` 等——发 typealias 或保留原位）
- **rev-4 双监视器锁序约束**：TofuRepository 自带 `@Synchronized`；若 L4a3 暴露 TofuRepository 且允许不经 OCR 直调 → 反向锁序死锁。须文档化「必须先持 OCR monitor」或去 Tofu 侧锁。

### 6a UI 工厂（3/5/16）— 低风险穿插（plan line 136，ζ-3 前可做）
### η — cluster 6 → L5b（`withHostReconfiguration` 三分支契约；cluster 6 须 6 绿再拆）
### θ — 收尾波

## 4. 关键产物

- **L4a0 不变量映射**：`exp-1` session 结果（22 不变量 I1–I22 + 分缝 hazard + scope-whitelist）。reuse exp-1 取全文；上节 §3 已摘要 a3 相关项。
- **Plan**：`docs/refactor-optimization-plan.md`（§3 α→θ 序权威；§5 优先级）。
- **Gate 结果**：每波 rev-grok（本会话上下文；9.5–9.8 GO）。
- **双边 handoff（slimapi 侧）**：`docs/ocdroid-token-stream-handoff.md` **不在本仓**（slimapi 仓 §5/§8/§9/§11）。

## 5. 可复用 session（按 alias）

| alias | 类型 | 上下文 |
|---|---|---|
| `exp-1` | explorer | L4a0 OCR 不变量映射（OCR 3334 + SslConfig/HostConfig/ServerCompatProfile/SlimSseStateMachine） |
| `fix-13`（=fix-9 session） | fixer | L4a2 ExpandBatchEngine（OCR-expand 区） |
| `fix-12` | fixer | token-stream（C-1/C-2/C-3 + token_memory_limit） |
| `fix-11` | fixer | L4c ConnectionHealthProbe |
| `rev-5` / `rev-7` | rev-grok | L4b / L4a2 gate |
| `fix-1` / `fix-2` | fixer-zlm | 6a-1 / L1b（机械） |
| `rev-1` / `rev-2` | rev-grok | ε / δ gate |

## 6. 硬约束 / 经验

- **check.sh 必做**（AGENTS.md；LSP 关）。**中心串行**——禁并行 Gradle（毁共享 build dir）。遇 `NoSuchFileException`(in-progress-results.bin) flake → `--rerun-tasks`（up-to-date cache 腐败）。
- **fixer 路由**：结构/复杂 → 强 `fixer`；简单/机械 → `fixer-zlm`（本会话 fixer-zlm 复杂任务失败 3 次：幻觉写/不完整迁移/错结构）。
- **Gate**：diff-based rev-grok（整文件读会 timeout）；阈值 9.5。
- **发版**：`./scripts/release.sh <patch|minor|major>`（打 tag，git 派生版本，无 commit）→ `git push origin main && git push origin <tag>` → `./scripts/upload-release.sh <ver>`。instrumented gate（review-gate.md scoped 文件）用模拟器（`emulator.sh status`→start→跑→stop）。
- **双边**：`session_send` 给 slimapi 主会话；技术结论交付，用户协调；不自动回。
- **重构原则**：行为保持；同包 `internal`（禁子包/import churn）；每 lane 写作用域白名单；近零 lane 保持无菌。
- **设备安全**：不在物理机跑 connectedDebugAndroidTest/debug 安装，除非用户明确要求；模拟器是共享资源，用前 status 确认空闲、用完 stop。
