# 状态机大幅精简方案（v5.3-final）— 实施依据（冻结决策版 · 修订版）

> **状态**：实施依据（冻结决策版 · 修订版）。本版基于 v5.2-final + rev-gpt 评审意见（3 P0 + 7 P1 + 5 P2）修订而成，落实用户对 **Decision 5（OwnershipGate）的重新决策**，并修复全部 3 个 P0、7 个 P1、5 个 P2。
> **前序（均保留不动作为历史/材料）**：
> - `state-machine-simplification-v5.2-final.md` — 上一冻结决策版（被 rev-gpt 评为 REJECTED 5/10）
> - `state-machine-simplification-v5.1-lean.md` — 讨论稿（13 修正 + opencode 借鉴专章）
> - `state-machine-simplification-v5-lean.md` — v5-lean 原稿
> - `.omni-orch/reports/ses_03cd6ddbaffeLRChdWhnV0DGb0.md` — **rev-gpt 对 v5.2 的评审报告（P0/P1/P2 全文，本版修订依据）**
> - `.omni-orch/reports/eventfold-migration-assessment.md` — 事件折叠迁移评估
> - `/home/mar/personal_projects/oc-slimapi/.omni-orch/reports/ses_03cdf5aacffeZZJCoAZSGRD6E2.md` — slimapi status 评估（跨项目只读）
>
> **本版相对 v5.2 的核心变化**：
> 1. **Decision 5 推翻回退**：OwnershipGate 从"完整版保留 ~664"改为"**精简到 ~200 行**"（回到 v5.1 原推荐）。理由：rev-gpt 证明完整版的 `prepareAttempt()/registerStarting()/markReady()` 驱动链（Launcher → Service Intent(attemptId) → Service.registerStarting → LifecycleCoordinator.markReady）在 L1 删除 Launcher+Service 后**整体消失**——这不是"冗余"而是"不可达代码"，完整版无法 Ready。用户接受降级精简方案：只保留**重连窗口防双发的 max-1 仲裁**职责，放弃 Starting/Ready 两阶段 + attemptId 超时（这些依赖已删驱动链）。
> 2. **行数基线口径统一**：从同一"被触及组件"口径重算，纳入 v5.2 漏计的 `DefaultSseReconnectSupervisor(631)` / `ConnectionBootstrapEngine(201)` / `ConnectionBootstrapRunner(50)`，并修正 TOFU 文件实测行数。最终保留行数与削减率随之更正。
> 3. **Plan A 一致性论证诚实降级**：删除"同源无两份真相"强断言，标注因果快照非原子（普通 REST baseline 语义），并补齐客户端消费侧扩展、JSON schema、CLIENT_CHANGES、测试矩阵、部署兼容矩阵。
>
> **净方向（不变）**：沿用 v5-lean/v5.1/v5.2——**删除产生复杂性的状态机**（放弃特定保证），而非在保留前提下清理并发。

---

## 0. 定位

| 维度 | 说明 |
|---|---|
| **文档性质** | 实施依据（冻结决策 · 修订版）。实施阶段据此执行，不再回头讨论"是否删"。 |
| **决策方式** | 逐轴独立评估（非整体选档）。7 项决策各自独立，来源可追溯。Decision 5 经 rev-gpt 评审后由用户重新拍板（精简回退）。 |
| **净方向** | 删除产生复杂性的状态机（放弃特定保证）。 |
| **行数口径（v5.3 强化）** | **所有行数均 `wc -l` 实测**于 `app/src/main`（2026-08-02 快照，见 §2.7 文件清单可复算）。baseline = 所有被本计划触及组件的"当前行数"之和（删除+替换+精简+保留不变的核心 FSM），**不含调试/诊断组件 ~1000+（Decision 1 保留，不在精简范围）**。 |

---

## 1. 用户最终 7 项决策清单（逐条核对）

> 每项标注：**决策** + **理由** + **评估依据来源**。**🔴 = v5.3 相对 v5.2 有变更**。

| # | 决策轴 | 用户决策 | 理由 | 评估依据来源 |
|---|---|---|---|---|
| 1 | **调试/诊断功能** | ✅ **保留** | 诊断/调试/流量统计/崩溃日志是开发与运维必需；移除仅省 ~600-1000 行但不影响终端用户 | `feature-removal-assessment-webalign.md` §1.4 / §5.2 |
| 2 | **token 聚合**（TokenStreamReducer 415 行纯 reducer + TokenStreamCoordinator 聚合逻辑） | ✅ **保留** | 纯 reducer `(State,Event)->(State,List<Effect>)`，与传输安全层 4 守卫正交。删聚合层=失去实时打字 | `eventfold-migration-assessment.md`；`feature-removal-assessment-webalign.md` §2.1 |
| 3 | **multi-host** | ✅ **移除**（单 host） | opencode 单 baseUrl；多 host profile 管理是移动端厚层（HostProfilesManagerScreen 444 行 + HostProfileStore + 切换 FSM） | `feature-removal-assessment-webalign.md` §1.3 / §5.3 |
| 4 | **TOFU 证书钉扎** | ✅ **→ per-server trust-all 开关** | TOFU 是仅 server pinning（单向），非 mTLS 依赖（mTLS 走独立 `HostProfile.mtlsEnabled + clientCertId` 路径）。退化为 per-server "信任所有证书"开关，满足自签服务器连接需求。**代价：失去 MITM 防护**（见 §6.2） | `feature-removal-assessment-webalign.md` §1.1 / §2.2 |
| 🔴 **5** | **OwnershipGate** | ✅ **精简到 ~200 行**（**回退到 v5.1 原推荐；v5.2 的"完整版保留 ~664"被推翻**） | rev-gpt 证明完整版 `prepareAttempt/registerStarting/markReady` 驱动链在 L1 删 Launcher+Service 后**整体消失**（不可达代码，非冗余）。用户接受降级精简：**只保留重连窗口防双发的 max-1 仲裁**；放弃 Starting/Ready 两阶段 + attemptId 超时（依赖已删驱动链）。**决策变更理由见下** | rev-gpt 评审 P0-1；`state-machine-simplification-v5.1-lean.md` §3.3 |
| 6 | **事件折叠迁移** | ✅ **不大迁移** | ocdroid 数据投影层已是成熟事件折叠（4 reducer 2516 行 + AppAction 988 行 ≈ 3504 行已折叠，零迁移成本）。命令式 FSM 本质是网络 I/O + 协程生命周期 + 并发锁，reducer 管不了副作用与并发，**迁移收益不足**（非"绝对不可折叠"） | `eventfold-migration-assessment.md` §核心结论 |
| 7 | **replay 断线恢复** | ✅ **Plan A**（slimapi 加回 `GET /slimapi/sessions/status`） | 透传上游 `/session/status` + merge TurnRegistry。加性、不 bump wire 版本（仍为 2）。**与 digest 同源但因果快照非原子**（见 §3.3 诚实论证） | `ses_03cdf5aacffeZZJCoAZSGRD6E2.md`；rev-gpt 评审 P0-3 |

### 🔴 Decision 5 变更理由（v5.2 → v5.3）

**v5.2 决策**：完整版保留 ~664 行（含 Starting/Ready 两阶段 + attemptId 超时 + max-1 仲裁）。理由是"宁冗余不削弱仲裁"，并评估 ~30-40% 冗余、~60-70% 仍有效。

**rev-gpt 证伪（P0-1）**：完整版的协议驱动链是
```
StreamingServiceLauncher.prepareAttempt()
  → Service Intent(attemptId)
    → SessionStreamingService.registerStarting()
      → StreamingLifecycleCoordinator.markReady()
```
- `prepareAttempt()` 仅由将删的 **Launcher** 调用；
- `registerStarting()` 仅由将删的 **Service** 调用。

L1 同时删 Launcher + Service 后，**无任何组件创建 pendingAttempt 或注册 Starting owner** → `markReady()` 永远 no-op → 完整版 Starting/Ready/attemptId 路径变成**不可达代码**，而非"留存冗余"。v5.2 §6.1 "60-70% 仍有效"的评估无代码证据，方向相反。

**用户重新决策**：接受降级精简——
- **保留**：重连窗口防双发的 **max-1 所有权仲裁**（旧前台连接 dying + 新前台连接 starting 期间，确保只有一个连接持有 primary，防双发）。此职责在新驱动链下仍可达：由 L1 后的**前台连接宿主**（AppCore/ConnectionCoordinator，替代已删的 Service/Launcher）驱动。
- **放弃**：Starting/Ready 两阶段状态机 + attemptId 超时（依赖已删的 Service Intent(attemptId) 驱动链，删后不可达）。

**精简后规模**：~200 行（从 664 删 -464）。详见 §2.3 #20 与 §6.1 重写。

**决策命中核对**：7/7 ✅（Decision 5 已更新为精简版）。

---

## 2. 最终状态机清单（v5.3 重算 · 行数可复算）

> **行数口径**：所有数值 `wc -l` 实测于 `app/src/main`（2026-08-02 快照）。文件级清单见 §2.7，可逐项复算。baseline = 被触及组件当前行数之和（不含调试/诊断 ~1000+）。

### 2.1 删除（区分整文件删 / 切片删 / 重写）

> **v5.3 修正（P1-1）**：v5.2 把 TOFU 清理笼统说成"删 6 文件 ~1735 行"，实际须区分三类——**整文件删**（纯 TOFU 文件）/ **切片删**（HealthProbe、SslConfig 含非 TOFU 部分须保留）/ **重写删**（bootstrap 引擎）。各文件实测行数见 §2.7。

| # | 状态机/组件 | 当前行数 | 处置 | 删除行 | 保留行 | 决策来源 |
|---|---|---|---|---|---|---|
| 1 | **StreamingLifecycleCoordinator** L2/L3/L4 分支 | 1692 | 切片删（骨架重用/精简） | **-1015** | 677 | v5.1 §3.1 |
| 2 | **StreamingServiceLauncher** | 282 | **整文件删** | -282 | 0 | v5.1 §3.1 |
| 3 | **BootstrapJobHolder** + epoch + handle-passing | 34 | **整文件删** | -34 | 0 | v5.1 §3.1 |
| 4 | **SessionStreamingService** FGS 壳 | 1466 | 切片删（~55% 可删） | **-806** | 660 | v5.1 §3.1（高风险，波及 20+ 文件） |
| 5 | **SessionStreamingController** | 480 | **整文件删** | -480 | 0 | v5.1 §3.1 |
| 6 | **OptimisticClaimWatchdogCoordinator** | 209 | **整文件删** | -209 | 0 | v5.1 §3.1 |
| 7 | **重连/bootstrap 引擎簇**（`DefaultSseReconnectSupervisor` 631 + `ConnectionBootstrapEngine` 201 + `ConnectionBootstrapRunner` 50） | **882** | **整簇删 + 职责重表达** | **-882** | 0（职责并入精简后的 Owner/Gate，不单列） | 🔴 **v5.3 新增（v5.2 漏计，rev-gpt P0-2）** |
| 8 | **ConnectionBootstrapCoordinator**（TOFU 共享单例） | 240 | **整文件删** | -240 | 0 | Decision 4 |
| 9 | **TOFU 整文件删 5 文件**（TofuPinStore 98 / TofuTrust 203 / TofuRepository 248 / TofuModule 22 / TofuTrustDialog 159） | **730** | **整文件删** | **-730** | 0 | 🔴 Decision 4（v5.3 修正：v5.2 只列 4 文件 ~595，实际 5 文件 730） |
| 10 | **SslConfig**（TOFU 分支 + system-CA 路径） | 260 | **切片删**（剥 TOFU 分支，保留 system-CA + 新增 trust-all 分支） | **-~150** | ~110 | 🔴 Decision 4（v5.3：HealthProbe/SslConfig 不能整删，rev-gpt P1-1） |
| 11 | **ConnectionHealthProbe**（TOFU 验证切片 + REST 健康检查） | 946 | **切片删**（剥 TOFU 验证 ~700，保留 REST testConnection ~246） | **-~700** | ~246 | Decision 4（P1-1：HealthProbe 不能整删） |
| 12 | **多 host 管理 UI + 切换 FSM**（HostProfilesManagerScreen 444） | 444 | **切片删**（删多 profile/切换 FSM ~250；**保留**流量统计+模型管理+清除数据 ~194，迁出/保留） | **-~250** | ~194 | 🔴 Decision 3（v5.3：P1-5 拆解——444 行含与 Decision 1 冲突的诊断块） |

**删除小计**：
- 整文件删/整簇删（#2,3,5,6,7,8,9）：282+34+480+209+882+240+730 = **-2857**
- 切片删（#1,4,10,11,12）：1015+806+150+700+250 = **-2921**
- **删除净减合计：-5778**（保留余 677+660+110+246+194 = 1887 行）

> **关于 #7 重连/bootstrap 引擎簇**：`DefaultSseReconnectSupervisor`(631) 原负责 SSE 多策略退避 + 跨代重试预算；`ConnectionBootstrapEngine`(201)/`ConnectionBootstrapRunner`(50) 负责含 TOFU 捕获的连接 bootstrap。L7 删 TOFU + L1 删 FGS 后，这三者的大部职责消失；其**唯一仍有效的职责（重连触发 + 重连窗口防双发）**在精简后的 `ServiceSseConnectionOwner`(→400) 与 `StreamingOwnershipGate`(→200) 中重表达，故本簇整删、不单列保留文件。切片精确边界为实施时确认项（见 §7.3a）。

### 2.2 替换

| # | 原组件 | 当前行数 | 替换为 | 行数 | 净减 | 说明 |
|---|---|---|---|---|---|---|
| 13 | **ProcessStatusPoller**（bulk polling + slim fan-out 双职责） | 640 | **TimedRefreshWithSlimFanOut**（定时刷新 + 保留 slim 扇出） | ~150 | **-490** | ⚠️ 必须覆盖 slim fan-out，否则 slimapi 订阅者收不到 status 更新（v5.1 §3.4） |

### 2.3 精简但保留

| # | 状态机 | 当前行数 | 精简后 | 净减 | 说明 |
|---|---|---|---|---|---|
| 14 | **ServiceSseConnectionOwner** | 1471 | ~400 | **-1071** | 删 5 个 @Volatile 降到 0-2；重连代次简化（v5.1 §3.2：实际 5 非 9）。**吸收 #7 簇的重连触发职责** |
| 15 | **TokenStreamCoordinator**（传输安全层/守卫层） | 1900 | ~800 | **-1100** | 删 foreground/route 门控 ~450 行。**Decision 2 保留聚合层**（TokenStreamReducer 415 行完整保留）；4 守卫冗余**实施时逐守卫验证**（见 §7.6，去倾向性） |
| 16 | **AuthorityReducer** | 1092 | ~600 | **-492** | 删 optimistic claim 协调（5 字段含 `reconcileConfirmed`）；纯函数架构保留 |
| 17 | **AuthorityState** | 236 | ~212 | **-24** | 精简后 ~212（v5.1 §3.2 修正） |
| 🔴 **18** | **StreamingOwnershipGate** | 664 | **~200** | **-464** | 🔴 **Decision 5（v5.3 推翻 v5.2 完整保留）**：只保留重连窗口防双发的 max-1 仲裁；放弃 Starting/Ready 两阶段 + attemptId 超时（驱动链已删，不可达）。新驱动由前台连接宿主提供。详见 §6.1 |

**精简净减合计：-3151**（1471→400, 1900→800, 1092→600, 236→212, 664→200）

### 2.4 完整保留（不精简）

| # | 状态机 | 行数 | 说明 | 决策来源 |
|---|---|---|---|---|
| 19 | **ConnectionPhase** + isSseDown（sealed class，嵌于 OwnershipModels.kt） | ~63 | UI 层 sealed class，设计良好（v5.1 §3.5：实际 ~63 非 160）。**保留不动** | 沿用 |
| 20 | **BannerHysteresisOwner** | 130 | **Decision 6：不迁移**到 `Flow.Scan`（边际收益不值得）。纯计时器 reducer，保留现状 | Decision 6 |
| 21 | **SseEventBridge** | 277 | 走 Plan B 时需扩展 V2 事件格式；Plan A 下保持现状（v5.3 实测 277，v5.2 误记 300） | 沿用 |
| — | **TokenStreamReducer** | 415 | 纯 reducer，**Decision 2 完整保留**（独立于 coordinator 精简；v5.3 实测 415，v5.2 误记 416） | Decision 2 |
| — | **调试/诊断组件**（DebugLogSection/TrafficTracker/CrashLogger/调试开关等） | ~1000+ | **Decision 1：全部保留**（不在精简范围，不计入 baseline） | Decision 1 |

### 2.5 新增

| # | 组件 | 行数 | 说明 |
|---|---|---|---|
| 🔴 **22** | **per-server trust-all 证书开关** | **~150-200** | 🔴 Decision 4（v5.3 上修：v5.2 估 50-100 偏低）。**per-server 粒度**（与 host 配置模型一致，冻结见 P1-3），须承载与 `MutualTLS`/`SystemDefault` 的组合策略（P1-4），故 ~150-200 |

### 2.6 行数估算汇总（v5.3 重算 · 可复算）

**baseline 口径**：被本计划触及组件的当前行数之和（删除类 + 替换类 + 精简类 + 保留不变核心 FSM）。不含调试/诊断 ~1000+（Decision 1 保留，不在范围）。

| 类别 | 当前(baseline) | 计划后 | 净减 |
|---|---|---|---|
| 删除（§2.1，整删+切片删） | 2857 + 4808 = **7665** | 1887（切片保留余） | **-5778** |
| 替换（§2.2） | **640** | 150 | **-490** |
| 精简（§2.3） | **5363** | 2212 | **-3151** |
| 保留不变核心 FSM（§2.4） | **885** | 885 | 0 |
| 新增（§2.5） | 0 | ~175 | **+175** |
| **合计** | **~14553** | **~5309** | **-9244** |

| 指标 | v5.2（被证伪） | **v5.3（重算）** | rev-gpt 独立复核 |
|---|---|---|---|
| baseline | ~12000（口径偏窄，漏计 BootstrapCoord/TOFU/multi-host + DefaultSseReconnectSupervisor/Engine/Runner） | **~14553**（同口径全计入） | — |
| 最终保留 | ~3500-4000 | **~5300**（核心 FSM） | ~5372（窄基线复算，与本版收敛） |
| 削减率 | ~67-71% | **~63%**（9244/14553） | 收敛 |

> **v5.2 → v5.3 行数更正要点（P0-2）**：
> 1. v5.2 baseline ~12000 漏计了被计入删除收益的 `BootstrapCoord(240)` + TOFU 切片(595→实测 730) + multi-host UI(444)，以及完全漏列的 `DefaultSseReconnectSupervisor(631)` + `ConnectionBootstrapEngine(201)` + `ConnectionBootstrapRunner(50)`。
> 2. v5.2 把"删除收益"加进了分母但没把这些组件加进 baseline 分子，导致削减率虚高（67-71%）。
> 3. v5.3 用同一"被触及组件"口径，分子分母一致：baseline ~14553，净减 ~9244，保留 ~5300，**削减率 ~63%**。
> 4. 本版结论与 rev-gpt 独立窄基线复算（~5372）收敛，互证稳健。

### 2.7 行数复算文件清单（P2-5 / P0-2 可追溯）

> 以下均为 `wc -l` 实测于 `app/src/main`（2026-08-02 快照）。复算命令示例：
> `wc -l app/src/main/java/cn/vectory/ocdroid/service/lifecycle/StreamingLifecycleCoordinator.kt ...`

**删除/替换/精简类（baseline 贡献）**：

| 组件 | 文件路径 | 实测行 |
|---|---|---|
| StreamingLifecycleCoordinator | service/lifecycle/StreamingLifecycleCoordinator.kt | 1692 |
| StreamingServiceLauncher | service/StreamingServiceLauncher.kt | 282 |
| BootstrapJobHolder | service/streaming/BootstrapJobHolder.kt | 34 |
| SessionStreamingService | service/SessionStreamingService.kt | 1466 |
| SessionStreamingController | service/streaming/SessionStreamingController.kt | 480 |
| OptimisticClaimWatchdogCoordinator | service/streaming/OptimisticClaimWatchdogCoordinator.kt | 209 |
| DefaultSseReconnectSupervisor | service/streaming/DefaultSseReconnectSupervisor.kt | 631 |
| ConnectionBootstrapEngine | service/streaming/ConnectionBootstrapEngine.kt | 201 |
| ConnectionBootstrapRunner | service/streaming/ConnectionBootstrapRunner.kt | 50 |
| ConnectionBootstrapCoordinator | service/bootstrap/ConnectionBootstrapCoordinator.kt | 240 |
| TofuPinStore | data/repository/http/TofuPinStore.kt | 98 |
| TofuTrust | data/repository/http/TofuTrust.kt | 203 |
| TofuRepository | data/repository/TofuRepository.kt | 248 |
| TofuModule | di/TofuModule.kt | 22 |
| TofuTrustDialog | ui/settings/TofuTrustDialog.kt | 159 |
| SslConfig | data/repository/http/SslConfig.kt | 260 |
| ConnectionHealthProbe | ui/controller/ConnectionHealthProbe.kt | 946 |
| HostProfilesManagerScreen | ui/settings/HostProfilesManagerScreen.kt | 444 |
| ProcessStatusPoller | service/streaming/ProcessStatusPoller.kt | 640 |
| ServiceSseConnectionOwner | service/streaming/ServiceSseConnectionOwner.kt | 1471 |
| TokenStreamCoordinator | ui/controller/sse/TokenStreamCoordinator.kt | 1900 |
| AuthorityReducer | ui/AuthorityReducer.kt | 1092 |
| AuthorityState | data/state/AuthorityState.kt | 236 |
| StreamingOwnershipGate | service/StreamingOwnershipGate.kt | 664 |

**保留不变核心 FSM**：ConnectionPhase ~63（嵌于 service/OwnershipModels.kt）/ BannerHysteresisOwner 130 / SseEventBridge 277 / TokenStreamReducer 415。

**P2-2 差额溯源**：v5.2 §2.1 表内已量化项合计 ~4105，而汇总写 ~4261，差额 ~156 来自 multi-host 的"切换 FSM"（v5.2 未单列量化）。v5.3 已将切换 FSM 折入 #12 切片删（~250 估算含切换 FSM），不再悬空。

---

## 3. slimapi 联动改动（Plan A）— v5.3 诚实化

### 3.1 改动内容（oc-slimapi 项目，跨项目协作）— Decision 7 = Plan A

| 项 | 内容 |
|---|---|
| **端点** | `GET /slimapi/sessions/status?directory=<必填>` |
| **方案** | 方案 Y（独立端点）+ 路径 A（透传上游） |
| **实现** | 透传上游 `GET /session/status`（返回 `Record<SessionID, {type:"busy"\|"idle"\|"retry"}>`）+ merge `TurnRegistry.snapshot()` 的 turn/turnIncarnation。**纯只读投影，不写、不缓存、不引入新状态机** |
| **数据来源** | busy/idle/retry ← 上游 `Event.Status`；turn ← `TurnRegistry`。与 digest 流同源 |

### 3.2 改动量

| 改动 | 行数 |
|---|---|
| `src/oc_slimapi/routes/sessions.py`（新 route handler） | ~20 |
| `tests/test_sessions_routes.py`（新测试，**v5.3 扩充矩阵见 §3.5**） | ~20→~40 |
| `app.py` 路由注册 | 0（已含 sessions.router） |
| **代码小计** | **~40-60** |
| `docs/specs/v2-contract.md`（§0/§2/§8/§10/§11.1 + §2 端点表 + **JSON schema 定义见 §3.4**） | ~8-12 |
| `docs/specs/INTERFACE_MAP.md`（必加） | ~3 |
| `CHANGELOG.md`（[Unreleased] Added） | ~5 |
| **文档小计** | **~16-20** |

### 3.3 🔴 一致性论证诚实降级（P0-3）

> **v5.2 强断言"同源无两份真相"被 rev-gpt 证伪，v5.3 降级为诚实表述。**

**v5.2 原断言（已删除）**："端点与 digest 同源（busy/idle 均源自上游 Event.Status；turn 均源自 TurnRegistry），端点是同源只读投影，不产生分叉，无两份真相风险。"

**v5.3 诚实表述**：
1. **同源 ≠ 同一因果时刻**。handler 必须 `await GET /session/status`（上游）后再读 `TurnRegistry.snapshot()`，**两次读取非原子**。在这两步之间，若有 prompt/abort 事件触发 turn bump，会拼出"旧 status + 更高 turn"的**因果错位快照**。
2. **这是普通 REST baseline 语义**，与任何按需查询的 status 端点同级（非线性化读）。客户端不应将其当作强一致快照。
3. **digest hub 的 DigestFields 不是真相源**——它是 250ms debounce 窗口内的瞬时副本，`resync_all()` 会清空。真相源是 `TurnRegistry` 与 `TokenStreamHub._session_status`。
4. **为何不引入强一致**：端点是"按需查询的便利投影"，加锁换取原子性会引入新状态机/新锁，违背 slimapi 极简哲学。客户端按 REST baseline 消费即可（见 §3.4 客户端消费约束）。

### 3.4 🔴 客户端消费侧扩展（P0-3）

> **v5.2 遗漏**：现有客户端无法消费 merge 后的 turn。v5.3 补齐。

**现状（实测 `Session.kt:91-99`）**：
```kotlin
data class SessionStatus(
    val type: String,            // "busy" | "idle" | "retry"
    val attempt: Int? = null,
    val message: String? = null,
    val next: Long? = null
) { val isIdle/isBusy/isRetry ... }
```
- **无 per-sid `ServerRound`/turn 字段**。
- `ApplySnapshot`（`AuthorityState.kt` / `StatusFetchService.kt`）接收 `Map<String,SessionStatus>`，**无 per-sid turn 通道**。
- 同名端点 DTO 在 ocdroid 侧已随 v1→v2 迁移删除。

**v5.3 必须的客户端改动（实施时）**：
1. **扩展 `SessionStatus`**：新增 `serverRound: ServerRound?`（含 turn + turnIncarnation），nullable 以兼容旧端点/坏 shape。
2. **扩展 `ApplySnapshot`**：消费 per-sid `ServerRound`，merge 进 AuthorityState 的 turn 投影。
3. **JSON schema 须定义**（写入 `v2-contract.md` §端点表）：
   ```jsonc
   // GET /slimapi/sessions/status?directory=...
   { "<sid>": { "type": "busy"|"idle"|"retry",
                "serverRound": { "turn": <int>, "incarnation": <int> } } }
   ```
   `serverRound` 整体 optional（端点未升级 / 旧 sidecar 404 时缺省）。
4. **CLIENT_CHANGES.md 同步**：记录 ocdroid 侧 SessionStatus/ApplySnapshot 扩展 + 旧端点 fallback 行为。
5. **404 / 坏 shape 兜底**：端点 404（旧 sidecar）或 `serverRound` 缺省/坏 shape → 客户端降级为"仅 busy/idle/retry，无 turn"，不阻断（见 §3.6 部署兼容）。

### 3.5 🔴 测试矩阵（P0-3）

> v5.2 的 ~20 行测试不覆盖关键路径。v5.3 要求覆盖：

| # | 测试场景 | 验证点 |
|---|---|---|
| T1 | sparse idle（部分 sid idle，部分 busy） | map 正确投影 |
| T2 | retry 态（上游返回 `retry`） | 与 busy/idle 统一处理（P2-1） |
| T3 | turn merge（status + turnIncarnation 拼合） | serverRound 正确 |
| T4 | 并发 bump（读取期间 turn 递增） | 因果错位不崩溃，客户端按 baseline 容忍 |
| T5 | 坏 shape（缺字段/类型错） | 降级，不抛 |
| T6 | 旧 sidecar 404 | 客户端 fallback（§3.6） |
| T7 | turnIncarnation 回退/重置 | snapshot 一致 |

### 3.6 🔴 部署兼容矩阵（P1-7）

> **v5.2 遗漏**：旧 v2 sidecar 对新端点 404 的部署兼容。v5.3 补齐。

| 部署顺序 | slimapi 端点 | ocdroid 客户端 | 行为 |
|---|---|---|---|
| **server-first（推荐）** | 已加 endpoint | 旧版（不消费） | 无影响（加性） |
| **server-first** | 已加 endpoint | 新版（消费 turn） | 全功能 |
| **client-first（风险）** | 未加（旧 sidecar） | 新版（消费 turn） | **404** → 客户端 fallback：降级为仅 busy/idle/retry，无 turn；或能力探测 |
| **混合** | 部分节点加 / 部分未加 | 新版 | 按节点能力探测 |

**能力探测机制（复用现有 `ServerCompatProfile`）**：ocdroid 已有 `ServerCompatProfile` capability-flag 基础设施（`supportsWatermarkResync` 等 → `slimConnection`；404 探测模式 `thin_route_not_found` / per-sid 404→false 已存在）。**v5.3 要求**：新增 capability flag（如 `supportsSlimStatus`），首次 `GET /slimapi/sessions/status` 404 → 置 flag=false → 后续走 fallback（轮询兜底 + 无 turn）。**部署策略**：优先 server-first；若必须 client-first，依赖 capability 探测，禁止硬编码假设端点存在。

---

## 4. 修正后依赖链（L1→L8，v5.3 更新）

### 4.1 依赖图

```
L3(简化Reducer)          [独立，无前置，可最先做]
L8(host-profile重塑)     [独立可起，但 L7 依赖其配置模型 → 见 P1-5 顺序约束]
        │
        ▼
L1(删FGS+后台SSE)        [高风险，波及20+文件；解锁 L2/L4/L5/L7/OwnershipGate重写]
   ├→L2(简化Owner)       [前置 L1；吸收重连/bootstrap簇职责]
   ├→L4(简化TSC)         [前置 L1，非 L2/L3]
   ├→L5(替换Poller)      [前置 L1，非 L3；须覆盖 slim fan-out]
   ├→L7(TOFU→trust-all)  [前置 L1；且前置 L8 配置模型（per-server trust-all 字段）]
   └→OwnershipGate重写   [前置 L1：驱动链消失，须重写为前台宿主驱动的 max-1 仲裁，见 §6.1]
        │
        ▼
L6(replay Plan A)        [前置 L2；slimapi 侧端点可独立先行；客户端消费侧扩展见 §3.4]
```

### 4.2 阶段表

| 阶段 | 内容 | 风险 | 收益 | 前置 | 可并发？ |
|---|---|---|---|---|---|
| **L3** | 简化 AuthorityReducer（删 optimistic claim） | 中 | 删 ~492 行 + 消除 watchdog | 无（独立） | ✅ 最先做 |
| 🔴 **L8**（v5.3 重定性） | **重塑** host-profile 配置模型 → 单 host（**非纯 UI/store 低风险**，见 P1-5） | **中-高** | 删 ~250 行多 profile/切换 FSM；保留流量统计+模型管理+清除数据；**为 L7 提供 per-server trust-all 字段落点** | 无（可起）；**但 L7 须等 L8 配置模型定型** | ✅ 最先做（起） |
| **L1** | 删除 FGS + 后台 SSE + 通知 | **高**（波及 20+ 文件） | 删 ~3500 行 + 6 个状态机 + 重连/bootstrap簇 | 无 | 与 L3/L8 并发 |
| **L2** | 简化 ServiceSseConnectionOwner（删 @Volatile/重连代次）+ 吸收重连簇职责 | 中 | 删 ~1071 行 + 5→0-2 @Volatile | L1 | — |
| **L4** | 简化 TokenStreamCoordinator（删 foreground/route 门控）+ **逐守卫验证冗余** | 中 | 删 ~1100 行 | L1（非 L2/L3） | 与 L2 并发（写域不同） |
| **L5** | 替换 ProcessStatusPoller → 定时刷新 + slim fan-out | 低-中 | 删 ~490 行；须覆盖 slim fan-out | L1（非 L3） | L2 后 |
| 🔴 **L7** | TOFU → per-server trust-all（删 5 文件 + 切片 HealthProbe/SslConfig + 删 bootstrap 簇/Coord，加 trust-all 开关） | 中 | 删 ~5778 行（含切片） | **L1 + L8**（BootstrapCoord 耦合 L1；trust-all 字段落 L8 配置模型） | L2/L8 后 |
| 🔴 **OwnershipGate 重写**（v5.3：非"复核"而是"重写"） | L1 后驱动链消失，**重写**为前台宿主驱动的 max-1 仲裁（~200 行） | 中 | 664→200；消除不可达代码 | L1（前台宿主定型后） | L2 后 |
| **L6** | replay 集成（Plan A） | 低-中 | 断线恢复更精确 | L2（ocdroid 侧）；**客户端消费侧扩展（§3.4）+ 能力探测（§3.6）** | — |

### 4.3 推荐执行批次

**第一批（可立即并行，均无前置）**：
- **L3**（简化 Reducer，独立）
- **L8**（host-profile 重塑，起配置模型；**注意 P1-5：非低风险，须先拆解流量统计/模型管理/清除数据与 Decision 1 的冲突**）
- **slimapi 侧 Plan A 端点 + JSON schema + 测试矩阵（§3.5）**（跨项目，独立加性）
- **L1**（高风险但收益最大）— 启动后解锁下游

**第二批（L1 + L8 完成后）**：
- L2（简化 Owner，吸收重连簇）
- L4（简化 TSC，与 L2 写域不同可并行；逐守卫验证）
- L7（TOFU → trust-all，须 L8 配置模型就位）
- **OwnershipGate 重写**（L1 后立即重写为 ~200 行 max-1 仲裁）

**第三批**：
- L5（替换 Poller，须覆盖 slim fan-out）
- L6（replay，ocdroid 消费侧扩展 §3.4 + 能力探测 §3.6；slimapi 端点已在第一批就绪）

**每阶段独立可发版。**

---

## 5. opencode 借鉴点落地情况

> 来源：v5.1 §9 + `eventfold-migration-assessment.md`。

### 5.1 已落地 / 无需迁移

| 借鉴模式 | ocdroid 现状 | 决策 |
|---|---|---|
| **事件折叠 / reducer-over-events** | ✅ 已全面实现：`AppAction`(~50 变体 sealed, 988 行) → 纯 `reduce()` → `MutableStateFlow.update{reduce()}` CAS 单点提交。**已折叠文件级清单（P2-5 可复算）**：AuthorityReducer 1092 + TokenStreamReducer 415 + SseSessionListReducers 459 + SseChatReducers 550 = **2516（4 reducer）**；+ AppAction 988 = **~3504 ≈ v5.2 所称 ~3491**（v5.2 缺文件级清单不可复算，v5.3 补齐） | Decision 6：不大迁移 |
| **reducer 范式** | ✅ 现状即此范式 | 保留 |
| **token 聚合** | ✅ 现状（slimapi 聚合 + ocdroid 内存 overlay） | Decision 2：保留 |
| **内外状态解耦** | 部分 | 沿用 |

### 5.2 不照搬

| opencode 特性 | 不可移植原因 |
|---|---|
| Effect TS 框架 | Kotlin 无直接等价 |
| 单进程 Node.js 假设 | Android 多进程组件不同 |
| 无后台/通知/multi-host 设计 | 问题域不同（桌面/TUI vs Android 移动端） |
| server 纯 fanout 无连接级 ownership | ocdroid 需自研纯客户端 OwnershipGate（v5.3：精简 ~200 行 max-1 仲裁） |

### 5.3 根本结论（v5.3 措辞修正 P2-4）

ocdroid 的核心问题多数在 opencode 根本不存在（架构前提不同）。opencode 提供的是**设计哲学参考**，不是现成解法。

**P2-4 措辞修正**：v5.2 说"3 大命令式 FSM **不可折叠**"过绝对。v5.3 改为：**reducer 不能消除 I/O / 锁 / 协程生命周期（副作用脚手架），把它们改写成 reducer 只是换位置不消失，迁移收益不足**。故 TokenStreamCoordinator/ServiceSseConnectionOwner/StreamingOwnershipGate 保留为命令式 FSM 是正确的（其 ~70-80% 是必须保留的副作用脚手架）。

---

## 6. 保留项的诚实标注

### 6.1 🔴 OwnershipGate 精简版说明（Decision 5 · v5.3 重写）

**v5.3 处置**：`StreamingOwnershipGate` 664 行 → **~200 行**（删 -464）。

**保留对象**：重连窗口防双发的 **max-1 所有权仲裁**。当旧前台连接进入 dying、新前台连接 starting 的重连窗口时，确保同一时刻只有一个连接持有 primary，防止双发（重复 token 投递 / 重复请求）。

**删除对象**（v5.2 完整版的"仍有效"部分，经 rev-gpt 证伪为不可达）：
- **Starting/Ready 两阶段状态机**：依赖 `registerStarting()`（仅将删的 Service 调用）→ `markReady()`（仅由将删的 Launcher 经 LifecycleCoordinator 驱动）。L1 后无组件创建 pendingAttempt / 注册 Starting owner → 这些路径**不可达**。
- **attemptId 超时**：attemptId 由 `prepareAttempt()` 分配（仅将删的 Launcher 调用）。L1 后无 attemptId 来源 → 超时逻辑不可达。

**新驱动链**：L1 后，前台连接宿主（AppCore/ConnectionCoordinator，替代已删的 Service/Launcher）作为 OwnershipGate 的驱动方：
- 连接建立 → 宿主向 Gate 申请 primary（max-1 仲裁：若已有 primary 则排队/拒绝旧连接）；
- 连接 dying → 宿主通知 Gate 释放，新连接接管。
- 重连窗口的防双发是**真实职责**（非冗余），在纯前台世界仍必要。

**可行性论证**：
1. **仲裁职责清晰**：max-1 是单一不变量（≤1 个 primary），~200 行足以表达（状态机 + synchronized 仲裁 + 重连窗口检测）。
2. **与 L1 兼容**：不依赖 Service/Launcher，只依赖前台宿主接口（L1 提供该宿主）。
3. **无新协议冲突**：放弃 attemptId/Starting/Ready 后，Gate 不再持有"两阶段"语义，仅做 primary 持有仲裁，与精简后的 ServiceSseConnectionOwner 重连逻辑正交。

**残留风险与验证**（见 §7.3）：重写时须验证 max-1 不变量在并发重连下成立（构造 dying+starting 并发输入，单元测试覆盖）。

### 6.2 🔴 TOFU → per-server trust-all 的安全代价（Decision 4 · v5.3 强化）

**原 TOFU 提供**：SSH 风格首次信任 + SPKI 变更检测 + 仅 server pinning（单向）。

**per-server trust-all 提供**：**每服务器**开关，ON 时信任该服务器所有证书（含伪造/拦截）。无首次信任、无变更检测、无 SPKI 钉扎。

**🔴 P1-3 粒度冻结**：v5.2 在 §2.5 写"全局 trust-all"、§6.2 写"per-server 显式启用"，**互相矛盾**。v5.3 **冻结为 per-server 粒度**（与 host 配置模型一致——`HostProfile` 已是 per-server 配置单元）。全文统一：trust-all 是 `HostProfile` 上的 per-server 布尔字段，**无全局开关**。

**🔴 P1-2 字段名修正**：v5.2 称 mTLS 走 `HostProfile.clientCertConfig`，**该字段不存在**。实测（`HostProfile.kt:48-49,83-84`）：实际为 `mtlsEnabled: Boolean` + `clientCertId: String?` 两个字段。trust-all 字段将作为 per-server 第三字段加入（如 `trustAllCerts: Boolean`），与 `mtlsEnabled`/`clientCertId` 并列。

**🔴 P1-4 mTLS 不自动认证服务器的诚实标注**：
- **mTLS 不缓解 MITM**：v5.2 称"推荐生产用 mTLS 缓解 MITM"——**不普遍有效**。mTLS 客户端证书证明**客户端身份**，**不自动认证服务器**。若服务器无有效 CA 签名，mTLS 仍可能被 MITM（攻击者中继双向证书）。
- **组合策略定义**：实测当前证书模式 `MutualTLS` / `TofuPinned` / `SystemDefault` **互斥**（sealed 选择）。v5.3 明确：**暂不支持组合**（如 trust-all + mTLS 同时开）。trust-all 与 mTLS 互斥（trust-all ON 时 mTLS 客户端证书路径仍走，但服务器认证被绕过——这是危险组合，UI 须告警或禁用）。后续若需组合（如 "trust-all 服务器证书 + 强制 mTLS 客户端认证"），须单独设计，**v5.3 不纳入**。
- **trust-all 行数上修**：考虑 per-server 粒度 + 与 mTLS 互斥/告警的组合策略 UI，v5.2 的 50-100 偏低，v5.3 改为 **~150-200**。

**安全边界**：
- **失去 MITM 防护**：trust-all ON 时，敌对网络下攻击者可冒充服务器，不告警。
- **trust-all OFF**：走系统 CA store（标准 HTTPS），与任何 HTTPS 客户端同级。
- **自签服务器**：trust-all ON 可连接，但无 MITM 防护。
- **生产推荐**：**优先用合法 CA 签名的服务器证书**（非 trust-all、非 mTLS 绕过）。mTLS 仅在需要客户端认证时使用，且不依赖其防 MITM。

---

## 7. 风险与待验证项（v5.3 更新）

| # | 风险/待验证项 | 严重度 | 缓解/验证方式 |
|---|---|---|---|
| 7.1 | **L1 删除 FGS 影响面大**（波及 20+ 文件） | **高** | 分步：(1) 停后台 SSE 保持 → (2) 删 FGS 壳 → (3) Owner 迁宿主到 AppCore/ConnectionCoordinator → (4) OwnershipGate 重写为前台宿主驱动。每步独立可发版、可回退 |
| 7.2 | **per-server trust-all 无 MITM 防护 + 组合策略** | 中-高（安全） | 硬性 UX：(a) 默认 OFF；(b) per-server 显式启用；(c) trust-all 与 mTLS 互斥/告警；(d) UI 明确风险文案；(e) 生产优先合法 CA。文档标注"不支持 MITM 防护、不支持组合" |
| 🔴 **7.3** | **OwnershipGate 重写正确性**（v5.3：从"复核冗余"改为"重写验证"）—— max-1 仲裁在并发重连下是否成立 | 中 | 重写后单元测试：构造 dying+starting 并发输入，验证 ≤1 primary 不变量；验证新前台宿主驱动链可达（markReady 有真实触发方） |
| 🔴 **7.3a** | **重连/bootstrap 簇切片边界**（#7）—— DefaultSseReconnectSupervisor/Engine/Runner 的职责重表达是否完整 | 中 | 实施时确认：重连触发 + 重连窗口防双发已落入精简后的 Owner(400)/Gate(200)，无职责遗漏 |
| 7.4 | **Plan A 依赖 slimapi 加端点 + 客户端扩展**（跨项目） | 低-中 | 加性改动、不 bump；slimapi 侧独立先行；**客户端须扩展 SessionStatus/ApplySnapshot（§3.4）+ 能力探测（§3.6）**；因果快照非原子按 REST baseline 消费 |
| 🔴 **7.5** | **L8 非"纯 UI/store 低风险"**（P1-5）—— HostProfileStore 被 resolver/多 ViewModel/Authority scope/会话缓存/Basic Auth/mTLS 广泛依赖 | **中-高** | L8 实施前 grep HostProfileStore 全部消费者；HostProfilesManagerScreen 444 行须拆解：流量统计+模型管理+清除数据（~194，与 Decision 1 保留诊断一致，须迁出/保留）与多 profile/切换 FSM（~250，删）；**L7 的 per-server trust-all 字段须落入 L8 重塑后的配置模型**（L7↔L8 顺序依赖） |
| 🔴 **7.6** | **TokenStreamCoordinator 4 守卫冗余——去倾向性**（P1-6） | 中 | v5.3 措辞修正：**未做逐守卫不变量证明前，不预设 generation/bundleStamp 可删**。重连窗口本身产生跨代 stale frame（旧连接 dying 时发出的帧可能带旧 generation），故 generation/bundleStamp 在重连 fencing 中**可能仍有职责**。L4 实施时**逐守卫验证**：构造跨代 stale frame 输入，证明某守卫确为死代码后方可删；epoch 保留（防 stale） |
| 7.7 | **ProcessStatusPoller 替换丢 slim fan-out** | 中 | L5 替换方案必须显式覆盖 slim fan-out |
| 7.8 | **POST 后 UI 延迟 ~250ms 显示 busy** | 低 | 可加极简 loading 指示；或接受 ~250ms digest 延迟 |
| 7.9 | **会话列表 30s 定时刷新不如实时** | 低 | 手动下拉刷新；Plan A replay 兜底（按 REST baseline） |
| 7.10 | **事件折叠反转结论稳健性** | 低 | 已折叠 ~3504 行（4 reducer 2516 + AppAction 988，文件级可复算）；命令式 FSM 副作用脚手架不可消除（P2-4 措辞修正）。结论稳健 |
| 🔴 **7.11** | **Plan A 部署兼容**（P1-7）—— client-first 部署下旧 sidecar 404 | 中 | server-first 优先；client-first 依赖 `ServerCompatProfile` capability 探测（新增 `supportsSlimStatus` flag），404→fallback 轮询兜底（§3.6） |

---

## 8. 不做什么（明确排除）

- ❌ **不改 slimapi wire 版本**（Plan A 加性端点，不 bump；仍为 2）
- ❌ **不改 token stream 聚合层**（Decision 2：TokenStreamReducer 完整保留）
- ❌ **不删 AuthorityReducer**（纯函数架构正确，精简不删）
- ❌ **不删 ConnectionPhase / BannerHysteresis**（UI 层设计良好；BannerHysteresis Decision 6 不迁移）
- ❌ **不整体删 OwnershipGate，也不"完整版保留"**（Decision 5 v5.3：**精简到 ~200 行** max-1 仲裁）
- ❌ **不移除调试/诊断功能**（Decision 1：全部保留；HostProfilesManagerScreen 中的流量统计/模型管理/清除数据须保留/迁出）
- ❌ **不做事件折叠大迁移**（Decision 6：数据层已折叠，命令式 FSM 副作用不可消除）
- ❌ **不保留完整 TOFU**（Decision 4：退化为 per-server trust-all 开关）
- ❌ **不支持 trust-all + mTLS 组合**（P1-4：当前互斥，组合策略 v5.3 不纳入）
- ❌ **不预设 generation/bundleStamp 守卫可删**（P1-6：须逐守卫验证）
- ❌ **不改原 v5-lean / v5.1 / v5.2 文档**（保留为历史/材料）

---

## 9. v5.1/v5.2 修正项继承核对

- v5.2 完整继承 v5.1 的 13 项修正（行数/字段数/依赖链/opencode 借鉴），不重复罗列。
- v5.3 相对 v5.2：**推翻 1 项**（Decision 5：完整保留 → 精简 ~200）；**修正行数基线口径**（§2.6/§2.7）；**降级 Plan A 一致性论证**（§3.3）；其余继承。

---

## 10. 相关文档

- `docs/specs/state-machine-simplification-v5.2-final.md` — 上一冻结决策版（被 rev-gpt REJECTED）
- `docs/specs/state-machine-simplification-v5.1-lean.md` — 讨论稿（13 修正 + opencode 借鉴）
- `docs/specs/state-machine-simplification-v5-lean.md` — v5-lean 原稿（历史）
- `docs/specs/feature-removal-assessment-webalign.md` — 三档决策材料
- `.omni-orch/reports/ses_03cd6ddbaffeLRChdWhnV0DGb0.md` — **rev-gpt 对 v5.2 评审报告（本版修订依据）**
- `.omni-orch/reports/eventfold-migration-assessment.md` — 事件折叠迁移评估
- `/home/mar/personal_projects/oc-slimapi/.omni-orch/reports/ses_03cdf5aacffeZZJCoAZSGRD6E2.md` — slimapi status 评估（跨项目只读）

---

## 11. 实施就绪判定

| 判据 | 状态 |
|---|---|
| 7 项决策全部体现且可追溯（Decision 5 已更新为精简） | ✅（§1） |
| 最终状态机清单（删/精简/保留/新增 + **行数可复算**） | ✅（§2，§2.7 文件清单） |
| slimapi Plan A（端点 + 行数 + 不 bump + **一致性诚实降级 + 客户端扩展 + 测试矩阵 + 部署兼容**） | ✅（§3） |
| 依赖链（L3 独立 / L4·L5·L7 前置 L1 / **L7↔L8 配置模型顺序**） | ✅（§4） |
| opencode 借鉴（**FSM 措辞修正 + 折叠清单可复算**） | ✅（§5） |
| 诚实标注（**OwnershipGate 重写语义 + trust-all per-server + mTLS 组合**） | ✅（§6） |
| 风险与待验证项（**L8 重定性 + 守卫去倾向性 + 部署兼容**） | ✅（§7，11 项） |
| 不做什么（排除清单） | ✅（§8） |
| **rev-gpt 评审 3 P0 + 7 P1 + 5 P2 全部响应** | ✅（见 §12 修订记录逐条对照） |

**本文档可作为实施依据冻结**。实施按 §4.3 批次执行，每个 L 阶段独立可发版。高风险项（L1）分步走，每步可回退。

---

## 12. 修订记录（v5.2 → v5.3）

> 逐条列出本次修订改了什么，每条引用 rev-gpt 评审报告（`.omni-orch/reports/ses_03cd6ddbaffeLRChdWhnV0DGb0.md`）对应项。

### P0 严重（3/3 命中）

| # | 修订内容 | 对应评审项 | 落点 |
|---|---|---|---|
| **P0-1** | **OwnershipGate 完整保留 → 精简 ~200 行**。回退到 v5.1 原推荐。重写 §6.1（从"冗余边界复核"改为"重写为前台宿主驱动的 max-1 仲裁"）；§1 Decision 5 标注变更理由（驱动链 Launcher→Service→registerStarting→markReady 在 L1 后整体消失，不可达代码非冗余）；§2.3 #18 行数 664→200；§4 依赖链 OwnershipGate 从"复核"改为"重写" | rev-gpt P0-1 + 用户重新决策 | §1, §2.3, §4, §6.1, §7.3 |
| **P0-2** | **行数基线口径统一 + 可复算**。重算 baseline ~14553（纳入 BootstrapCoord 240 + TOFU 实测 730 + multi-host 444 + 漏列的 DefaultSseReconnectSupervisor 631 / ConnectionBootstrapEngine 201 / ConnectionBootstrapRunner 50）；最终保留 ~5300，削减率 ~63%（v5.2 的 3500-4000 / 67-71% 被证伪）；新增 §2.7 文件级 wc -l 清单 | rev-gpt P0-2 | §2.1, §2.6, §2.7 |
| **P0-3** | **Plan A 一致性论证诚实降级**。删除"同源无两份真相"强断言（§3.3），改为"因果快照非原子，普通 REST baseline 语义"；补齐客户端 SessionStatus/ApplySnapshot 扩展（§3.4）、JSON schema、CLIENT_CHANGES 同步、7 项测试矩阵（§3.5） | rev-gpt P0-3 | §3.3, §3.4, §3.5 |

### P1 重要（7/7 命中）

| # | 修订内容 | 对应评审项 | 落点 |
|---|---|---|---|
| **P1-1** | **TOFU 删文件表述修正**。区分整文件删（5 文件 730）/ 切片删（HealthProbe 946 剥 TOFU ~700 保 REST ~246；SslConfig 260 剥 TOFU ~150 保 ~110）/ 重写删（bootstrap 簇）；单列 TofuTrustDialog 159 / TofuModule 22 / ConnectionState.pendingTofuCapture 字段 | rev-gpt P1-1 | §2.1 #9-12, §2.7 |
| **P1-2** | **字段名修正**：`HostProfile.clientCertConfig`（不存在）→ 实测 `mtlsEnabled: Boolean` + `clientCertId: String?`（HostProfile.kt:48-49,83-84）。trust-all 作为 per-server 第三字段 | rev-gpt P1-2 | §1 Decision 4, §6.2 |
| **P1-3** | **trust-all 粒度冻结**：消除"全局 vs per-server"矛盾，全文统一为 **per-server**（与 host 配置模型一致），无全局开关 | rev-gpt P1-3 | §2.5, §6.2, §7.2 |
| **P1-4** | **mTLS 不自动认证服务器诚实标注**：mTLS 客户端证书证明客户端身份不缓解 MITM；`MutualTLS/TofuPinned/SystemDefault` 当前互斥，v5.3 明确"暂不支持组合"，trust-all 与 mTLS 互斥/告警；trust-all 行数 50-100 → **150-200** | rev-gpt P1-4 | §2.5 #22, §6.2, §7.2 |
| **P1-5** | **L8 重定性**：非"纯 UI/store 低风险独立"——HostProfileStore 被 resolver/多 ViewModel/Authority scope/会话缓存/Basic Auth/mTLS 广泛依赖；HostProfilesManagerScreen 444 须拆解（流量统计+模型管理+清除数据 ~194 保留/迁出，与 Decision 1 一致；多 profile/切换 FSM ~250 删）；标注 L7↔L8 顺序依赖（per-server trust-all 字段落 L8 配置模型） | rev-gpt P1-5 | §4.1/§4.2 L8, §7.5 |
| **P1-6** | **token 守卫去倾向性**：§7.6 改为"未做逐守卫不变量证明前，不预设 generation/bundleStamp 可删"；重连窗口产生跨代 stale frame，须 L4 实施时逐守卫验证 | rev-gpt P1-6 | §2.3 #15, §7.6 |
| **P1-7** | **Plan A 部署兼容矩阵**：旧 v2 sidecar 对新端点 404 → server-first 优先 / capability 探测（复用 ServerCompatProfile，新增 supportsSlimStatus flag）/ 404 fallback | rev-gpt P1-7 | §3.6, §7.11 |

### P2 建议（5/5 命中）

| # | 修订内容 | 对应评审项 | 落点 |
|---|---|---|---|
| **P2-1** | **统一含 retry**：§3 端点返回 type 全部写 `busy\|idle\|retry`；SessionStatus DTO 实测已含 isRetry（Session.kt:99） | rev-gpt P2-1 | §3.1, §3.4, §3.5 T2 |
| **P2-2** | **§2.1 差额 ~156 溯源**：来自 multi-host 切换 FSM（v5.2 未单列量化），v5.3 折入 #12 切片删 ~250 估算 | rev-gpt P2-2 | §2.7 P2-2 溯源 |
| **P2-3** | **行数小偏差修正**：TokenStreamReducer 416→**415**；ConnectionHealthProbe 900→**946**；SseEventBridge 300→**277**（均 wc -l 实测） | rev-gpt P2-3 | §2.4, §2.7 |
| **P2-4** | **FSM 措辞修正**："3 大 FSM 不可折叠"→"reducer 不能消除 I/O/锁/协程生命周期，迁移收益不足" | rev-gpt P2-4 | §5.3 |
| **P2-5** | **折叠清单可复算**：补文件级清单——4 reducer 2516（AuthorityReducer 1092 + TokenStreamReducer 415 + SseSessionListReducers 459 + SseChatReducers 550）+ AppAction 988 = ~3504 ≈ 所称 3491 | rev-gpt P2-5 | §5.1, §2.7 |

---

*（v5.3 修订完成。所有行数 wc -l 实测于 2026-08-02 快照，可在 `app/src/main` 复算。）*
