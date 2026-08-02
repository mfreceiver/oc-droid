# 状态机大幅精简方案（v5.2-final）— 实施依据（冻结决策版）

> **状态**：实施依据（冻结决策版）。本版整合 v5.1 全部 13 项修正 + opencode 借鉴专章 + 修正依赖链，并落实用户经多轮评估后的**最终 7 项决策**，产出可直接指导实施的文档。
> **前序（均保留不动作为历史/材料）**：
> - `state-machine-simplification-v5.1-lean.md` — 讨论稿（13 修正 + opencode 借鉴专章）
> - `state-machine-simplification-v5-lean.md` — v5-lean 原稿
> - `state-machine-simplification-v4.md` — v4 方案（8 轮评审内部并发清理）
> - `feature-removal-assessment-webalign.md` — 三档（保守/中度/激进）决策材料
> - `/home/mar/personal_projects/oc-slimapi/.omni-orch/reports/ses_03cdf5aacffeZZJCoAZSGRD6E2.md` — slimapi status 评估（跨项目只读）
> - `.omni-orch/reports/eventfold-migration-assessment.md` — 事件折叠迁移评估
>
> **本版相对 v5.1 的核心变化**：从"讨论稿"升级为"冻结决策版"。用户**逐功能轴独立评估**——不机械套用某一档（保守/中度/激进），而是对每个轴单独拍板。结果是**混合档**：在诊断/token 聚合/OwnershipGate/事件折叠轴上取保守，在 multi-host/TOFU 轴上取激进，在 replay 轴上确认 Plan A。

---

## 0. 定位

| 维度 | 说明 |
|---|---|
| **文档性质** | 实施依据（冻结决策）。实施阶段据此执行，不再回头讨论"是否删"。 |
| **决策方式** | 逐轴独立评估（非整体选档）。7 项决策各自独立，来源可追溯。 |
| **净方向** | 沿用 v5-lean/v5.1：**删除产生复杂性的状态机**（放弃特定保证），而非在保留前提下清理并发。 |
| **行数估算口径** | 沿用 v5.1 实测行数（非 v5-lean 文档行数）。所有估算为"量级估算"，实施时以实际为准。 |

---

## 1. 用户最终 7 项决策清单（逐条核对）

> 每项标注：**决策** + **理由** + **评估依据来源**。

| # | 决策轴 | 用户决策 | 理由 | 评估依据来源 |
|---|---|---|---|---|
| 1 | **调试/诊断功能** | ✅ **保留**（排除中/激进档的诊断移除） | 诊断/调试/流量统计/崩溃日志是开发与运维必需；移除仅省 ~600-1000 行但不影响终端用户，权衡后选择保留开发工具链完整 | `feature-removal-assessment-webalign.md` §1.4 / §5.2 中度档 |
| 2 | **token 聚合**（TokenStreamReducer 416 行纯 reducer + TokenStreamCoordinator 聚合逻辑） | ✅ **保留** | 已是纯 reducer（`(State,Event)->(State,List<Effect>)`），与传输安全层（coordinator 内的 epoch/generation/dedupPartRevision/bundleStamp 4 守卫）**正交、零冲突**。删聚合层=失去实时打字能力，退化为 REST 轮询 | `eventfold-migration-assessment.md` §token 聚合兼容性；`feature-removal-assessment-webalign.md` §2.1 |
| 3 | **multi-host** | ✅ **移除**（单 host） | opencode 单 baseUrl；多 host profile 管理是移动端厚层（HostProfilesManagerScreen ~444 行 + HostProfileStore + 切换 FSM）。单 host 配置足以覆盖绝大多数部署 | `feature-removal-assessment-webalign.md` §1.3 / §5.3 激进档 |
| 4 | **TOFU 证书钉扎** | ✅ **→ trust-all 开关**（非完整移除、非保留 TOFU） | TOFU 是仅 server pinning（单向），非 mTLS 依赖（mTLS 走独立 `HostProfile.clientCertConfig` 路径）。完整 TOFU 6 文件 ~1735 行 + ConnectionBootstrapCoordinator ~240 行是连接 bootstrap 主要复杂度来源。退化为全局"信任所有证书"开关 ~50-100 行，满足自签服务器连接需求。**代价：失去 MITM 防护**（见 §6.2） | `feature-removal-assessment-webalign.md` §1.1 / §2.2 |
| 5 | **OwnershipGate** | ✅ **完整版保留**（~664 行，**不精简**，含 Starting/Ready 两阶段 + attemptId 超时） | 删后台 SSE 后，前台+后台并发持有场景消失，完整版中为该场景设计的部分成为**留存冗余**。用户选择**保守保留**——宁冗余不削弱仲裁。重连窗口（旧连接 dying + 新连接 starting）仍需 max-1 仲裁，故完整版仍有 ~60-70% 功能有效 | `state-machine-simplification-v5.1-lean.md` §3.3（v5.1 原推荐精简，用户推翻为完整保留） |
| 6 | **事件折叠迁移** | ✅ **不大迁移** | ocdroid 数据投影层 `AppAction`(~50 变体 sealed) → 纯 `reduce()` → `MutableStateFlow.update{reduce()}` CAS 单点提交，**已是成熟事件折叠**（~3491 行已折叠，零迁移成本）。3 大命令式 FSM（TokenStreamCoordinator/ServiceSseConnectionOwner/StreamingOwnershipGate）本质是网络 I/O + 协程生命周期 + 并发锁，reducer 是纯函数管不了副作用与并发，**不可折叠**。可折叠增量仅 ~130 行（BannerHysteresisOwner），边际收益不值得迁移 | `eventfold-migration-assessment.md` §核心结论 |
| 7 | **replay 断线恢复** | ✅ **Plan A**（slimapi 加回 `GET /slimapi/sessions/status`） | 方案 Y + 路径 A：透传上游 `/session/status` + merge TurnRegistry。~40 代码行 + ~16 文档行，**加性、不 bump wire 版本**（仍为 2），与 digest **同源**（busy/idle 均源自上游 `Event.Status`，turn 均源自 TurnRegistry），无"两份真相"风险。ocdroid 复用 v1 同名端点的既有消费逻辑，回归成本低 | `ses_03cdf5aacffeZZJCoAZSGRD6E2.md`（slimapi 跨项目评估）；`state-machine-simplification-v5.1-lean.md` §2.5 |

**决策命中核对**：7/7 ✅。每项决策均可在本文档对应章节追溯到评估依据。

---

## 2. 最终状态机清单（基于 7 决策重算）

> 行数沿用 v5.1 实测（非 v5-lean 文档值）。"当前"= 现有代码基线 ~12000 行（核心状态机）。

### 2.1 删除（整个移除）

| # | 状态机/组件 | 当前行数 | 删除理由 | 决策来源 |
|---|---|---|---|---|
| 1 | **StreamingLifecycleCoordinator** L2/L3/L4 分支 | 1692（约 60% 可删 ≈ **1015 行删除**，~677 行骨架重用/精简） | 后台不保持 SSE → 无需 L2Active/L2Idle/BackgroundGrace/NoSourceTerminal | v5.1 §3.1（沿用） |
| 2 | **StreamingServiceLauncher** | 282 | Stage-1/Stage-2 超时因 FGS 启动延迟 → 无 FGS 则直连 | v5.1 §3.1（沿用） |
| 3 | **BootstrapJobHolder** + epoch + handle-passing | 34 | v0.18.13 加的 mutable-capture race 补偿层；无 FGS 则无 epoch/job/abort | v5.1 §3.1（沿用，收益小但无风险） |
| 4 | **SessionStreamingService** FGS 壳 | 1466（约 55% 可删 ≈ **806 行**） | 不需要后台 SSE → 不需要 FGS。**🔴 高风险**：8 个待删类的唯一汇聚点，波及 20+ 非删除文件 | v5.1 §3.1（沿用，高风险） |
| 5 | **SessionStreamingController** | 480 | bootstrap 重试循环 + command 分发 | v5.1 §3.1（沿用） |
| 6 | **OptimisticClaimWatchdogCoordinator** | 209 | SSE 在线时直接信任 session.status（~250ms digest 延迟可接受） | v5.1 §3.1（沿用） |
| 7 | **ConnectionBootstrapCoordinator**（TOFU 共享单例） | 240 | 随 TOFU 移除（Decision 4）；当前共享于 HealthProbe 与 SessionStreamingService，L1 删 Service 后仅余 HealthProbe，再随 TOFU 删 | **Decision 4** |
| 8 | **TOFU 证书钉扎 4 文件**（TofuPinStore / TofuTrust / TofuRepository / SslConfig） | ~595 | 完整 TOFU 移除（Decision 4），退化为 trust-all 开关 | **Decision 4** |
| 9 | **多 host profile 管理 UI + 切换 FSM**（HostProfilesManagerScreen 等） | ~444（管理 UI）+ 切换 FSM | 单 host（Decision 3）；降级为单 host 配置界面 | **Decision 3** |

### 2.2 替换

| # | 原组件 | 当前行数 | 替换为 | 行数 | 说明 |
|---|---|---|---|---|---|
| 10 | **ProcessStatusPoller**（bulk polling + slim fan-out 双职责） | 640 | **TimedRefreshWithSlimFanOut**（定时刷新 + 保留 slim 扇出） | ~150 | **⚠️ 必须覆盖 slim fan-out**：替换方案要把轮询结果扇出到 slimapi 订阅者，否则 slimapi 订阅者收不到 status 更新（v5.1 §3.4 已记） |

### 2.3 精简但保留

| # | 状态机 | 当前行数 | 精简后 | 删减 | 说明 |
|---|---|---|---|---|---|
| 11 | **ServiceSseConnectionOwner** | 1471 | ~400 | -1071 | 删 **5 个** @Volatile（`transportGenerationCounter`/`closingGeneration`/`resyncDirtyForGen`/`resyncInFlightForGen`/`activeAttempt`）降到 0-2；重连代次简化（v5.1 §3.2 修正：实际 5 非 9） |
| 12 | **TokenStreamCoordinator**（传输安全层/守卫层） | 1900 | ~800 | -1100 | 删 foreground/route 门控逻辑 ~450 行（无后台 token stream）。**Decision 2 保留聚合层**：TokenStreamReducer（416 行纯 reducer）完整保留；4 守卫中部分在单 host 下变冗余，**实施时审计哪些守卫成死代码**（见 §7.6） |
| 13 | **AuthorityReducer** | 1092 | ~600 | -492 | 删 optimistic claim 协调（5 字段：含 `reconcileConfirmed`，v5.1 §3.2 修正：实际 5 非 4）；纯函数架构保留 |
| 14 | **AuthorityState** | 236 | ~212 | -24 | 删空间比 v5-lean 文档小（v5.1 §3.2 修正：精简后 ~212 非 150） |
| 15 | **ConnectionHealthProbe**（含 TOFU 部分） | 900 | ~200 | -700 | **Decision 4**：剥离 TOFU 验证逻辑，仅保留 REST 健康检查（testConnection）。注：ConnectionHealthProbe 是 TOFU 6 文件之一（含 TOFU ~700 + 健康检查 ~200） |

### 2.4 完整保留（不精简）

| # | 状态机 | 行数 | 说明 | 决策来源 |
|---|---|---|---|---|
| 16 | **StreamingOwnershipGate** | 664 | **Decision 5：完整版保留**（推翻 v5.1 §3.3 的"精简到 ~200-300"推荐）。含 Starting/Ready 两阶段 + attemptId 超时。**留存冗余诚实标注见 §6.1** | **Decision 5** |
| 17 | **ConnectionPhase** + isSseDown | 63 | UI 层 sealed class，设计良好（v5.1 §3.5 修正：实际 ~63 非 160） | 沿用 |
| 18 | **BannerHysteresisOwner** | 130 | **Decision 6：不迁移**到 `Flow.scan`（边际收益不值得）。纯计时器 reducer，保留现状 | **Decision 6** |
| 19 | **SseEventBridge** | 300 | 走 Plan B 时需扩展 V2 事件格式；Plan A 下保持现状 | 沿用 |
| — | **TokenStreamReducer** | 416 | 纯 reducer，**Decision 2 完整保留**（独立于 coordinator 精简） | **Decision 2** |
| — | **调试/诊断组件**（DebugLogSection/TrafficTracker/CrashLogger/调试开关等） | ~1000+ | **Decision 1：全部保留**（排除中/激进档移除） | **Decision 1** |

### 2.5 新增

| # | 组件 | 行数 | 说明 |
|---|---|---|---|
| 20 | **Trust-all 证书开关**（全局"信任所有证书"） | ~50-100 | **Decision 4**：替代 TOFU 满足自签服务器连接。OFF 时走系统 CA store（标准 HTTPS）；ON 时信任所有证书（含伪造/拦截）。默认 OFF，per-server 显式启用 |

### 2.6 行数估算汇总

| 类别 | 估算 |
|---|---|
| 当前核心状态机总行数 | **~12000** |
| 删除（§2.1） | ~4261（含 L2/L3/L4 骨架精简 1015 + Service 806 + 其余整删） |
| 替换净减（§2.2） | ~490（640 → 150） |
| 精简净减（§2.3） | ~3387（5 个组件精简合计） |
| 新增（§2.5） | ~+75 |
| **最终保留总行数** | **~3500-4000**（核心状态机 + 保留的传输/仲裁/UI reducer） |
| **削减率** | **~67%-71%** |

**vs v5.1 保守档（~5000-6000）的差异**：
- Decision 3（multi-host UI 移除）：**-~450**
- Decision 4（TOFU → trust-all）：**-~1460**（health probe -700 + bootstrap -240 + 4 TOFU 文件 -595 + trust-all +75）
- Decision 5（OwnershipGate 完整 vs v5.1 精简）：**+~360-460**（664 vs 200-300）
- Decision 1/2/6/7：与 v5.1 一致或确认，**无净行数偏移**
- **净 delta**：约 **-1450~-1550**，故 v5.2 ≈ v5.1 - 1500 ≈ **~3500-4000**

> **注**：以上为量级估算。ConnectionHealthProbe 与 TOFU 6 文件存在包含关系（health probe 是 6 文件之一），已避免双重计数。multi-host UI 精确范围（是否保留单 host 配置界面 ~100-150 行）为实施时确认项（见 §7.5）。

---

## 3. slimapi 联动改动（Plan A）

### 3.1 改动内容（oc-slimapi 项目，跨项目协作）— Decision 7 = Plan A

| 项 | 内容 |
|---|---|
| **端点** | `GET /slimapi/sessions/status?directory=<必填>` |
| **方案** | 方案 Y（独立端点）+ 路径 A（透传上游） |
| **实现** | 透传上游 `GET /session/status`（返回 `Record<SessionID, {type:"busy"\|"idle"\|"retry"}>`）+ merge `TurnRegistry.snapshot()` 的 turn/turnIncarnation。**纯只读投影，不写、不缓存、不引入新状态机** |
| **数据来源** | busy/idle ← 上游 `Event.Status`（经 slimapi global_hub 接收）；turn ← `TurnRegistry`。与 digest 流（`/slimapi/events`）**同源** |

### 3.2 改动量

| 改动 | 行数 |
|---|---|
| `src/oc_slimapi/routes/sessions.py`（新 route handler） | ~20 |
| `tests/test_sessions_routes.py`（新测试） | ~20 |
| `app.py` 路由注册 | 0（已含 sessions.router） |
| **代码小计** | **~40** |
| `docs/specs/v2-contract.md`（§0/§2/§8/§10/§11.1 移除删除标注 + §2 端点表加行） | ~8 |
| `docs/specs/INTERFACE_MAP.md`（必加，`check_routes_doc.py` 强制） | ~3 |
| `CHANGELOG.md`（[Unreleased] Added） | ~5 |
| **文档小计** | **~16** |

### 3.3 安全性论证（为何 Plan A 不破坏 slimapi 极简哲学）

1. **加性变更（additive）**：恢复 v1 曾有端点（commit `c52775a` 删除时归类为"依赖性端点随已删数据流一并清掉"，**非因数据不可靠**）。
2. **不 bump wire 版本**：`X-Slimapi-Version` 仍为 2（按 `versioning.py` 规则，加性不 bump）。
3. **无两份真相风险**：端点与 digest 同源（busy/idle 均源自上游 `Event.Status`；turn 均源自 `TurnRegistry`）。端点是「同源只读投影」，不产生分叉。**digest hub 的 DigestFields 不是真相源**（它是 250ms debounce 窗口内的瞬时副本，`resync_all()` 会清空）。
4. **为何不扩 skeleton 加 status 字段（方案 X）**：上游 `Session.Info`（session list 返回）**不含 status**，故不能仅扩 skeleton key 取得 status；且 status 秒级翻转 vs 列表稳定元数据混入易陈旧。
5. **ocdroid 侧**：复用 v1 同名端点既有消费逻辑（断线恢复走 `GET /slimapi/sessions/status` → `applySnapshot` 直接信任，无 optimistic claim 保护窗口），回归成本低。

---

## 4. 修正后依赖链（L1→L7，v5.1 修正版 + v5.2 新增项）

### 4.1 依赖图

```
L3(简化Reducer)          [独立，无前置，可最先做]
L8(host-profile精简)     [独立，纯 UI/store，可最先做]
        │
        ▼
L1(删FGS+后台SSE)        [高风险，波及20+文件；解锁 L2/L4/L5/L7/OwnershipGate复核]
   ├→L2(简化Owner)       [前置 L1]
   ├→L4(简化TSC)         [前置 L1，非 L2/L3]
   ├→L5(替换Poller)      [前置 L1，非 L3；须覆盖 slim fan-out]
   ├→L7(TOFU→trust-all)  [前置 L1：BootstrapCoord 当前耦合 Service，L1 后仅余 HealthProbe]
   └→OwnershipGate复核   [非删除：L1 后复核完整版冗余边界是否真无副作用，见 §6.1]
        │
        ▼
L6(replay Plan A)        [前置 L2；slimapi 侧端点可独立先行]
```

### 4.2 阶段表

| 阶段 | 内容 | 风险 | 收益 | v5.2 前置 | 可并发？ |
|---|---|---|---|---|---|
| **L3** | 简化 AuthorityReducer（删 optimistic claim） | 中 | 删 ~500 行 + 消除 watchdog | **无**（独立） | ✅ 最先做 |
| **L8**（v5.2 新增） | 精简 host-profile 管理 → 单 host 配置 | 低-中 | 删 ~450 行 + 切换 FSM；波及面待确认 | **无**（独立，纯 UI/store） | ✅ 最先做 |
| **L1** | 删除 FGS + 后台 SSE + 通知 | **高**（波及 20+ 文件） | 删 ~3500 行 + 6 个状态机 | 无 | 与 L3/L8 并发 |
| **L2** | 简化 ServiceSseConnectionOwner（删 @Volatile/重连代次） | 中 | 删 ~1000 行 + 5→0-2 @Volatile | L1 | — |
| **L4** | 简化 TokenStreamCoordinator（删 foreground/route 门控） | 中 | 删 ~1100 行；**审计 4 守卫冗余** | **L1**（非 L2/L3） | 与 L2 并发（写域不同文件） |
| **L5** | 替换 ProcessStatusPoller → 定时刷新 + slim fan-out | 低-中 | 删 ~490 行；**须覆盖 slim fan-out** | **L1**（非 L3） | L2 后 |
| **L7**（v5.2 新增） | TOFU → trust-all（删 6 文件 + BootstrapCoord，加 trust-all 开关） | 中 | 删 ~1460 行 + 连接 bootstrap 大幅瘦身 | **L1**（BootstrapCoord 耦合） | L2 后 |
| **OwnershipGate 复核**（非删除） | L1 后复核完整版（664 行）冗余边界：FGS-vs-foreground 区分代码在纯前台路径是否真无副作用 | 中（复核，非删除） | 确认冗余安全 / 或发现需收窄 | L1 | L2 后 |
| **L6** | replay 集成（**Decision 7 = Plan A**） | 低-中 | 断线恢复更精确 | L2（ocdroid 侧）；**slimapi 侧端点独立先行** | — |

### 4.3 推荐执行批次

**第一批（可立即并行，均无前置）**：
- **L3**（简化 Reducer，独立）
- **L8**（host-profile 精简，独立 UI/store）
- **slimapi 侧 Plan A 端点**（跨项目，独立加性，不依赖 ocdroid 任何改动）— 与 ocdroid 第一批并发
- **L1**（高风险但收益最大）— 启动后解锁下游

**第二批（L1 完成后）**：
- L2（简化 Owner）
- L4（简化 TSC，与 L2 写域不同可并行）
- L7（TOFU → trust-all，与 L2 强相关，串行或紧随）
- OwnershipGate 复核（L1 后立即复核冗余边界）

**第三批**：
- L5（替换 Poller，须覆盖 slim fan-out）
- L6（replay，ocdroid 消费侧；slimapi 端点已在第一批就绪）

**每阶段独立可发版**。

---

## 5. opencode 借鉴点落地情况

> 来源：v5.1 §9（opencode 设计借鉴专章）+ §10（问题域对照）+ `eventfold-migration-assessment.md`。

### 5.1 已落地 / 无需迁移（现状即最佳）

| 借鉴模式 | ocdroid 现状 | v5.2 决策 |
|---|---|---|
| **事件折叠 / reducer-over-events** | ✅ **已全面实现**：`AppAction`(~50 变体) → 纯 `reduce()` → `MutableStateFlow.update{reduce()}` CAS 单点提交。这是 opencode `handleEvent()`+`setStore()` 的 Kotlin 等价。~3491 行已折叠，零迁移成本 | **Decision 6：不大迁移**（数据层已是事件折叠，命令式 FSM 是正确的副作用层，不可折叠） |
| **reducer 范式**（AuthorityReducer/TokenStreamReducer 纯函数 + detekt 强制单一写入点） | ✅ 现状即此范式 | 保留（Decision 2 确认 token reducer 保留；AuthorityReducer 精简但不删） |
| **token 聚合**（流量优化 + 防闪烁） | ✅ 现状（slimapi 聚合 + ocdroid 内存 overlay） | **Decision 2：保留**（纯 reducer，与传输安全层正交） |
| **内外状态解耦**（执行细节 FSM vs UI 可见状态投影） | 部分（AuthorityReducer 投影 idle/busy/retry） | 沿用，可借鉴 opencode 3 态投影进一步简化 UI 层耦合（非本版强制） |

### 5.2 不照搬（不可移植 / 问题域不同）

| opencode 特性 | 不可移植原因 |
|---|---|
| Effect TS 框架（SynchronizedRef/Deferred/Fiber/Layer/Queue/PubSub） | Kotlin 无直接等价，需 Coroutines+Flow+Mutex 重组 |
| 单进程 Node.js 假设 | Android 多进程组件（Application/Service/Activity）不同 |
| 无后台/通知设计 | 问题域不同（桌面/TUI vs Android 移动端） |
| 无 multi-host 设计 | opencode 没解决（单 baseUrl） |
| **server 纯 fanout 无连接级 ownership** | ocdroid 需自研纯客户端 OwnershipGate（Decision 5 保留完整版） |

### 5.3 根本结论（沿用 v5.1 §10.2）

ocdroid 的 6 个核心问题中 **4 个在 opencode 根本不存在**（架构前提不同）。opencode 提供的是**设计哲学参考**（事件折叠、reducer 范式），**不是现成解法**。ocdroid 的问题是 Android 移动客户端 + 多连接场景特有的。v5.2 的精简来自**删除移动端特有保证**（FGS/通知/TOFU/multi-host/optimistic claim），而非照搬 opencode 架构。

---

## 6. 保留项的诚实标注

### 6.1 OwnershipGate 完整版的冗余说明（Decision 5）

**保留对象**：`StreamingOwnershipGate` ~664 行，完整版，含 Starting/Ready 两阶段 + attemptId 超时 + max-1 所有权仲裁。

**冗余来源**：完整版设计时考虑"**前台 + 后台（FGS）同时持有 SSE 连接**"的并发持有场景——两个来自不同生命周期上下文的 LIVE 连接争用 primary。L1 删除后台 SSE 后，该场景**消失**：所有连接均为前台持有。

**冗余边界（诚实评估）**：
- **~30-40% 冗余**：FGS-vs-foreground 连接区分逻辑、为双上下文仲裁设计的部分分支。
- **~60-70% 仍有效**：重连窗口（旧前台连接 dying + 新前台连接 starting）仍需 max-1 仲裁 + Starting/Ready 两阶段 + attemptId 超时（防 Starting 卡死）。这些在纯前台世界仍有真实职责。

**用户决策理由**：保守保留——**宁冗余不削弱仲裁**。冗余代码是死重（维护负担 + 认知成本），但不是正确性风险（未被触发的路径不会产生运行时 bug）。

**唯一需警惕的隐患**：若完整版中 FGS-vs-foreground 区分逻辑对纯前台路径有**副作用**（如区分逻辑误判前台连接为"后台类"导致降级处理），则冗余会变成行为异常。**实施时（L1 后的 OwnershipGate 复核任务）必须验证**：完整版在纯前台输入下，FGS 相关分支不被触发或被无害跳过。见 §7.3。

### 6.2 TOFU → trust-all 的安全代价（Decision 4）

**原 TOFU 提供**：SSH 风格首次信任 + SPKI 变更检测（首次连接记录证书 SPKI，后续变更告警）+ 仅 server pinning（单向）。

**trust-all 开关提供**：全局开关，ON 时信任**所有**证书（含伪造/拦截的）。无首次信任、无变更检测、无 SPKI 钉扎。

**安全边界**：
- **失去 MITM 防护**：在敌对网络（公共 WiFi / 企业代理 / 被攻陷路由器）下，攻击者可冒充服务器，trust-all 开关 ON 时不会告警。
- **trust-all OFF 时**：走系统 CA store（标准 HTTPS 信任链），与任何 HTTPS 客户端同等安全（CA 信任假设：CA 被攻陷则风险，但这是行业基线，非 ocdroid 特有）。
- **自签服务器**：trust-all ON 可连接（满足需求），但无 MITM 防护；**推荐生产环境改用 mTLS 客户端证书**（独立路径 `HostProfile.clientCertConfig`，不受 TOFU 移除影响）。

**风险评估充分性**：本评估已明确：trust-all 是"方便性 vs 安全性"的权衡，适用于受控/自签部署的便利连接，**不应作为生产默认**。默认 OFF、per-server 显式启用、UI 明确风险提示——这三点是实施时的硬性 UX 要求（见 §7.2）。

---

## 7. 风险与待验证项

| # | 风险/待验证项 | 严重度 | 缓解/验证方式 |
|---|---|---|---|
| 7.1 | **L1 删除 FGS 影响面大**（波及 20+ 文件：Manifest/DI modules/ConnectionCoordinator/HealthProbe/SseEventBridge...） | **高** | 分步：(1) 停后台 SSE 保持 → (2) 删 FGS 壳 → (3) ServiceSseConnectionOwner 迁宿主到 AppCore/ConnectionCoordinator → (4) OwnershipGate 复核。每步独立可发版、可回退 |
| 7.2 | **trust-all 无 MITM 防护** | 中-高（安全） | 实施硬性 UX：(a) 默认 OFF；(b) per-server 显式启用（非全局一刀切）；(c) UI 明确风险提示文案；(d) 推荐生产用 mTLS。文档标注"不支持 MITM 防护" |
| 7.3 | **OwnershipGate 完整版冗余边界**（Decision 5）—— FGS-vs-foreground 区分代码在纯前台路径是否真无副作用 | 中 | L1 后的复核任务：构造纯前台输入，验证完整版中 FGS 相关分支不被触发或无害跳过；若发现副作用，收窄该分支（而非整体精简，保留 max-1 仲裁核心） |
| 7.4 | **Plan A 依赖 slimapi 加端点**（跨项目协作） | 低 | 加性改动、不 bump wire 版本；slimapi 侧可独立先行（第一批），与 ocdroid 改动解耦。需 slimapi 维护者协调合并 |
| 7.5 | **multi-host UI 移除波及面**（Decision 3）—— HostProfileStore 是否被其它非 UI 代码依赖（如连接层读取 host 配置） | 低-中 | L8 实施前先 grep HostProfileStore 的所有消费者；保留最小单 host 配置读写（~100-150 行），仅删多 profile 管理与切换 FSM |
| 7.6 | **TokenStreamCoordinator 4 守卫冗余审计**（Decision 2 保留聚合后）—— 单 host + 简化 ownership 后，epoch/generation/dedupPartRevision/bundleStamp 哪些成死代码 | 低-中 | L4 实施时审计：单连接下 generation/bundleStamp 可能冗余（原为多连接/重连 fencing）；epoch 保留（防 stale）；删死代码守卫以进一步精简，但**不删聚合层本身** |
| 7.7 | **ProcessStatusPoller 替换丢 slim fan-out** | 中 | L5 替换方案必须显式覆盖 slim fan-out（非简单删除）；TimedRefreshWithSlimFanOut 须把轮询结果扇出到 slimapi 订阅者 |
| 7.8 | **POST 后 UI 延迟 ~250ms 显示 busy**（删 optimistic claim 后） | 低 | 可加极简 loading 指示（纯 UI，非 optimistic claim）；或接受 ~250ms（slimapi digest 延迟） |
| 7.9 | **会话列表 30s 定时刷新不如实时** | 低 | 用户可手动下拉刷新；Plan A replay 兜底断线恢复 |
| 7.10 | **事件折叠反转结论的稳健性**（Decision 6）—— "ocdroid 已用事件折叠"是否站得住 | 低 | eventfold 评估（4 explorer 并行）已证实：~3491 行已折叠（AuthorityReducer/TokenStreamReducer/SseSessionListReducers/SseChatReducers），dispatch 单点（SharedStateStore.dispatch ~71 调用站），CAS 保证原子性。结论稳健，无需迁移 |

---

## 8. 不做什么（明确排除）

- ❌ **不改 slimapi wire 版本**（Plan A 是加性端点，不 bump；仍为 2）
- ❌ **不改 token stream 聚合层**（Decision 2：TokenStreamReducer 完整保留；coordinator 仅精简门控，不删聚合）
- ❌ **不删 AuthorityReducer**（纯函数架构正确，精简但不删除）
- ❌ **不删 ConnectionPhase / BannerHysteresis**（UI 层，设计良好；BannerHysteresis Decision 6 不迁移）
- ❌ **不整体删 OwnershipGate**（Decision 5：完整版保留，非精简）
- ❌ **不移除调试/诊断功能**（Decision 1：排除中/激进档的诊断移除）
- ❌ **不做事件折叠大迁移**（Decision 6：数据层已折叠，命令式 FSM 不可折叠）
- ❌ **不保留完整 TOFU**（Decision 4：退化为 trust-all 开关，非精简保留、非完整移除到系统 CA）
- ❌ **不改原 v5-lean / v5.1 / feature-removal-assessment 文档**（保留为历史/材料）

---

## 9. v5.1 修正项继承核对（13 条，v5.2 全部继承）

v5.2 完整继承 v5.1 的 13 项修正（行数/字段数/依赖链/OwnershipGate 定性/opencode 借鉴），不重复罗列。详见 `state-machine-simplification-v5.1-lean.md` §11。**唯一推翻**：§3.3 OwnershipGate 处置——v5.1 推荐"精简到 ~200-300"，v5.2 按 Decision 5 改为"完整版保留 ~664"。

---

## 10. 相关文档

- `docs/specs/state-machine-simplification-v5.1-lean.md` — 讨论稿（13 修正 + opencode 借鉴专章）
- `docs/specs/state-machine-simplification-v5-lean.md` — v5-lean 原稿（历史）
- `docs/specs/state-machine-simplification-v4.md` — v4 方案（8 轮评审）
- `docs/specs/feature-removal-assessment-webalign.md` — 三档决策材料
- `docs/specs/state-machine-architecture-spec.md` — 现有状态机架构规范
- `docs/specs/sse-client-spec.md` — SSE 客户端设计
- `.omni-orch/reports/eventfold-migration-assessment.md` — 事件折叠迁移评估
- `.omni-orch/reports/v5lean-feasibility-ocdroid.md` — ocdroid 可行性调研
- `/home/mar/personal_projects/oc-slimapi/.omni-orch/reports/ses_03cdf5aacffeZZJCoAZSGRD6E2.md` — slimapi status 评估（跨项目只读）
- `/home/mar/personal_projects/oc-slimapi/.omni-orch/reports/v5lean-feasibility-slimapi.md` — slimapi 影响评估（跨项目只读）
- `.omni-orch/reports/opencode-fsm-study-summary.md` / `opencode-fsm-study-full.md` — opencode 设计调研

---

## 11. 实施就绪判定

| 判据 | 状态 |
|---|---|
| 7 项决策全部体现且可追溯 | ✅（§1 逐条核对，每项标注来源） |
| 最终状态机清单（删/精简/保留/新增 + 行数） | ✅（§2） |
| slimapi Plan A 改动明确（端点 + 行数 + 不 bump + 同源） | ✅（§3） |
| 修正后依赖链（L3 独立 / L4·L5·L7 前置 L1） | ✅（§4） |
| opencode 借鉴点落地情况 | ✅（§5） |
| 保留项诚实标注（OwnershipGate 冗余 / TOFU 安全代价） | ✅（§6） |
| 风险与待验证项 | ✅（§7，10 项） |
| 不做什么（排除清单） | ✅（§8） |

**本文档可作为实施依据冻结**。实施阶段按 §4.3 批次执行，每个 L 阶段独立可发版。高风险项（L1）分步走，每步可回退。
