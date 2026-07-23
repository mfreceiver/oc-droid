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
  - `950d6b3` — N1：6a UI 工厂 3/5/16 ‖ L3b ForegroundNotificationPublisher ‖ θ-14 autoSelect/sweepPendingCreateIds（post-release 遗留子项收口）
  - `4a0a4d1` — ζ-3 首子步：SlimGetRepository 薄 GET 委托 ‖ 类型外提 ‖ cluster 9 合并（domain-delegate + compat-facade 起步）
  - `287f476` — η cluster 6：withHostReconfiguration barrier fold（CD3 三分支 preamble 折叠，~120→~25 LOC）
  - `0673dfa` — η Step 2 L5b ‖ L5c：HostProfileEditorDialog + SessionCard 外提（pure move + kover excludes）
  - `fcab357` — η Step 2 L5a：ChatScaffold 拆 T rememberChatTopBarState ‖ D ChatDrawerHost ‖ P ChatSessionPager
  - `78081c8` — θ cluster 18：publishIdleNotification 抽取（ALM ‖ SseNotificationBridge idle publish dedup）

## 2. 双边 §5（slimapi token-stream）— 已闭环

- 联合终审 re-gate **GO 9.7**（rev-bgpt）。双方已发版（不 bump `X-Slimapi-Version`）。
- 出货（ocdroid 侧）：C-1=A（done:null 保累计 buffer）· C-2（ResyncReason 5 值 + UNKNOWN fallback，不静默丢帧）· C-3（删 part_too_large）· token_memory_limit 触发 reconnect。
- Wire 契约（客户端 authoritative）：5 reason（`reconnect_no_replay`/`subscriber_backpressure`/`token_memory_limit`/`session_idle`/`session_deleted`）+ UNKNOWN fallback；`triggersReconnect=true` 仅前 3；不发 part_too_large（超限→`truncated:true`）；lever1 done=marker；lever2 gzip 客户端默认透明；health `features.tokenStream` dual-read fail-closed。
- **延后（post-release，非阻塞）**：C-4（doc 对齐，本轮做）· V-B（stunnel 运维 idle 断实证）· token_hub 拆包。
- slimapi 主会话：`ses_075953166ffeUULAsWATEcjoS8`（cross-session via `session_send`；用户协调）。

## 3. refactor（plan v3 α→θ）— ✅ 全部完成（α…θ 全绿；7/19/20/coldStartSlimSync/2/8 deferred-by-analysis）

### ζ-3 L4a3 — ✅ 安全 scope 收官（commits `950d6b3` N1 / `4a0a4d1` ζ-3）
**已落地**（domain-delegate 起步 + 类型外提 + cluster 9）：
- 类型外提：`MessagesPage` / `SlimAggregationOutcome` / `SlimColdStartSnapshot`（保公共 FQN，I20）
- `SlimGetRepository`：16 纯 GET wrapper 委托；`OpenCodeRepository.SlimCursorPartialException` FQN 兼容
- cluster 9：`getMessagesPaged/Unanchored → getMessagesPagedImpl(anchored)`、Q/P → `getSlimapiAggregation` 泛型；单次 `isSlimMode` + watermark 在 `runSuspendCatching` 内（rev-grok 9.3 NO-GO→9.7 GO）
- 每子步 rev-grok ≥9.7 GO + check.sh 绿

**scope 修正（backlog 陈旧，勿重新争论）**：
- **cluster 7**（slim 6 透孔删）= **resolved-by-design**：`T3RepositoryExtractFreezeTest@107-138` 锁死 6 个 `markSlim*`/`completeSlimReconfigure` 为 OCR 公共 API（注释："MUST remain accessible from outside the repo"）；生产 SSC + 6 测试 mock 经 `repo.` 调用。**保留为门面即正确**。
- **cluster 19**（checkHealth 双路委托）= **全委托 NO-GO**：`probeSlimapiHealth` 用 `sslConfigFor`（held mTLS），`checkHealthFor` 用 `resolveProbe`（纯参）防 R2#1 mTLS 泄漏；全委托重引入泄漏。仅共享尾部（parseSlimapiHealth + I8 写点 + HealthResponse）可抽，~15 LOC 边际，**未做**。
- **coldStartSlimSync 抽取** = **冻结测试约束/边际**：方法本身（freeze@141）+ `requireSlimTokenCurrent`（freeze@121）均锁 OCR，抽取只能 facade 式回调，净解耦有限且碰 I5/I15；**延后**。
- **原「OCR 留 ~100 行门面」目标被冻结测试取代**（~40 公共方法 `hasMethod()` 锁定；冻结测试本为防 L4a3 过度抽取而设）。

**不变量参考（L4a0，未触动，留备查）**：I5 slimStateLock 实例共享 / I6 configure() 原子事务 / I7 @Synchronized 序列化 / I8 serverCompatProfile 写点 / I15 coldStartSlimSync token threading / I20 公共 FQN / rev-4 双监视器锁序。详见 §4 exp-1。

### 6a UI 工厂（3/5/16）— 低风险穿插（plan line 136，ζ-3 前可做）
### η — cluster 6 → L5b ‖ L5a ‖ L5c — ✅ 全绿收官
- **Step 1 ✅** cluster 6 barrier fold（`287f476`，rev-grok 9.7 GO）：`beginReconfigureBoundary()` + `withHostReconfiguration(needsReconfigure,body)` suspend 三分支 fold；7 站点折叠，`resetLocalDataAndResync` 排除（deferred CancelSse）。设计 ora-1 / 实现 fix-10 / 侦察 exp-2。
- **Step 2 ✅** L5b ‖ L5a ‖ L5c（UI god-file 拆，均同包 internal）：
  - **L5b**（`0673dfa`）：HostProfileEditorDialog + CompactCert* + CaStage 外提；rev-grok 9.4 条件GO（pure-move 全 PASS，kover exclude 补→`--full`绿）。侦察 exp-4 / 实现 fix-12。
  - **L5c**（`0673dfa`）：SessionCard + SessionStatusDot + formatTime 外提；rev-grok 10.0 GO。侦察 exp-5 / 实现 fix-11。
  - **L5a**（`fcab357`）：ChatScaffold 1392→~853 行；T `rememberChatTopBarState`（**State<T> 句柄非值，避 derivedStateOf 静默 stale 陷阱**）‖ D `ChatDrawerHost` ‖ P `ChatSessionPager`；rev-grok 9.8 GO（4 invariant 全 PASS，invariant 1 逐读核验全 `.value`）。设计 ora-2 / 实现 fix-13 / 侦察 exp-3。
- 每子步 `check.sh --full` 绿（compile + 单测 + lint + koverVerify）。η 全 wave gate ≥9.7。
### θ — ✅ 收官（cluster 18 done；20/2/8 deferred-by-analysis）
- **cluster 18 ✅**（`78081c8`，rev-grok 9.6 GO）：`publishIdleNotification(...)` 抽取 → `di/IdleNotificationPublisher.kt`，统一 ALM ‖ SseNotificationBridge idle publish。helper `suspend` 无硬编码 dispatcher（ALM 包 `withContext(Main.immediate)`、bridge 直调，保 T5-review I2-R TOCTOU）；claim→fg-gate→notify→complete→persist→release；ALM 全日志经回调；idleMutex 单例共享；prune 未并入。recon exp-6 / 实现 fix-14 / `tag` 死参移除。
- **cluster 20 ⬜ deferred**（exp-7 分析）：`tryEmit`/`emit` 不一致**有意且 correctness-critical**（A=suspend emit / B=single tryEmit / C=multi-FIFO tryEmit），统一破坏其一；无共享基类（3 独立类，仅 SSC 实现 SseDispatchHost）；净节省 <20 LOC（"重复"是完整一行调用，无共享控制流）；invasive（3 controller + Hilt + SseDispatchHost）。**不值得做**。
- **cluster 14 ✅**（N1 `950d6b3`）；**cluster 2/8 ⬜ 延后**（低优先：DebugCardIdentity source 自动化 / ApiDelegate）。

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
