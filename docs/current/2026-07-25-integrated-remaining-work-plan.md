# ocdroid 剩余工作整合方案 v2（Wave 2 onward 统一发车）

> **状态**：整合草案 v4，待 rev-bgpt / rev-opus 双轮门控评审收敛到 ≥9.5。
> - v1 → rev-bgpt 8.3/BLOCK（§2 漏 `di/ControllerModule.kt`、C1「正交」过强、P11 D3 epoch gap 遗漏、「清空共享面」措辞错误、串行过保守）。
> - v2 → 全量吸收 bgpt + 据用户硬约束新增 **§1.1 防回归宪章 R1–R10**（slim / 标准 API 不回归 + 项目标准遵循）→ rev-opus 9.3/CONDITIONAL-PASS（5 bgpt blocker 全数确认修复；新 blocker B1 Fix② 范围与 C2 矛盾；改进 I1 generation 术语四义碰撞、I2 R7 漏 I7/I8、I3 措辞、I4 跨线程 invalidate 时序、I5 路径笔误）。
> - v3 → 吸收 opus B1（Fix② 全 4 caller 闭环）/I1（§10 术语消歧表 + R11）/I2（R7 补 I7/I8）/I3（§2.1 措辞）/I4（C1 采纳 option b：coordinator 读已发布 generation-stamped 字段，免跨线程同步调用）/I5（路径）→ **双轮确认：rev-opus 9.5/PASS（无 blocker，2 条 Wave-3 精度 note）；rev-bgpt 9.3/CONDITIONAL-PASS（C1 原子快照 + compare-to-launch 竞态契约 2 blocker，预览修复→9.6–9.7）**。
> - **v4** → 吸收 bgpt v3 两 blocker（**C1 单一 immutable `ClientBundle` 经单一 `@Volatile` 发布 + lifecycle 在 resolve-time 捕获 bundle + 提交前复验 `lifecycle.bundle===currentClientBundle` + 原子 supersede + 4 类竞态 failing-first 测试**）+ opus v3 两 note（bound-gen resolve-time 捕获 / C6 注入机制点名：扩 `resolver.resolve()` 返回 `(url,endpointFp,gen)` 单一读源）+ 非阻塞补强（R8 base-URL 断言、R12 继承 §5 其余不变量、R3 `slimHost` 命名、Wave 2A「不可单独交付」硬约束）。
> **日期**：2026-07-25
> **定位**：把当前三份独立修改方案整合为**一条发车序列**（统一 wave/task 拆分 + 共享写域裁剪 + 用户决策门 + 统一评审门控 + 防回归门）。本文件是**计划层**产物，不含实现；实现仍走每条方案既定的批次/门控。
> **行号约定**：所有 file:line 均为各源文档调研时快照，实施前须重新生成引用矩阵。

---

## 0. 源方案与各自共识状态

| # | 源方案 | 路径 | 共识状态 | 性质 |
|---|---|---|---|---|
| S1 | Wave-1 收尾 / 剩余任务移交 | `docs/ocmar/plans/2026-07-25-wave1-done-remaining-tasks.md`（+ P11 分析 `2026-07-24-p11-network-ownership-analysis.md`、剩余阶段 `2026-07-24-remaining-phases-plan.md`） | god-file 拆分 P1–P10 已 done（`ec2a502`，rev-bgpt ≥9.5）；**P11/P12/P4.1 未做** | 后端/架构收尾 |
| S2 | Chat List-Detail 重构（方案 B，v3） | `docs/current/chat-list-detail-redesign.md` | **设计层定型**（rev-opus + rev-bgpt 收敛，§7 LoadedContent + freshness token、§11 checkpoint）；实施层 B0–B6 + 门控 G1–G6 待跑 | 大型 UI/导航重构 |
| S3 | SSE 自取消循环排查 | `docs/current/sse-self-cancel-investigation.md` | **根因层定型**（rev-bgpt + rev-opus 双轮收敛）；Fix① + Fix② + 诊断待落地 | 小型 bug 修复 |

> 三份方案各自已通过不同严格度的评审；本整合**不重审它们各自的根因/设计**，只解决「合并成一条序列时的冲突、依赖、排序、门控、防回归」。

---

## 1. 为什么整合 + 防回归宪章

### 1.1 防回归宪章（用户硬约束，横跨所有 wave）

> 用户指令：**方案须充分遵循本项目相关标准，避免 slim 与标准 API 相关功能回归。** 以下条款每 wave 的评审与 check.sh **必须逐条核验**，违反即 BLOCK。

| # | 宪章条款 | 权威出处 | 核验方式 |
|---|---|---|---|
| **R1** | **双 API 变体共存不腐蚀**：共享形状、专有取数；装配期（`configure()`/DI）分一次支，**禁**散落 `if(isSlimMode)`；差异下沉不上浮 | architecture §3/§4 三铁律 | `rg '\bisSlimMode\b'` 在 `ui/`/`service/`/`di/` 业务代码 = 0（合法残留：SSE 路由分派 `SseDispatchHost.slimMode()` / `SlimSseHandler` / SSC override；OCR 内部 L3 门面） |
| **R2** | **L4+ 模式盲**：协调/service/UI 禁读裸 `repository.isSlimMode`，改读能力查询（`supportsWatermarkResync` 等 forwarder） | architecture §4.3 | 同 R1；新增 L4+ 代码不得引入裸 mode 读 |
| **R3** | **「slim = 切换服务器」模型不变**：不新增运行时 `slimMode` 分支标志位；省流 = `HostConfig.baseUrl` 指向 slimapi sidecar；5 个 client...全部随 host 重建。P11 `HostSnapshot`/`ClientBundle` 承载的 slim 位**命名为 `slimHost: Boolean`**（host/profile provenance），**仅** repository/DI/source-束装配与端点构造使用，**禁**作为 L4+/UI 模式读取 API（防重演散落式 `slimMode`，守 R1/R2） | slim-routing §1.1/§1.2 + architecture §4.3 | P11 `ClientBundle`/`HostSnapshot` 承载 `slimHost` 且 5 client 一同原子替换；AST 门控：无第二个钉死 opencode 主机的 Retrofit/OkHttp；`rg '\bslimMode\b'` ui/service/di 业务码 = 0 |
| **R4** | **C 桶 = 0（省流模式禁直连 opencode）**：不得有任何调用绕过 slimapi 直连 opencode | slim-routing §1.3/§7 | `rg` C 桶违规清单（硬编码 `:4096`/`:14096`/第二 opencode Retrofit）= 0；P11 改 graph 不得新增绕行 |
| **R5** | **mutation POST 禁自动重试（双发风险）**：`mutationClient`/`commandClient` 显式 `retryOnConnectionFailure(false)`；GET 类 `restHttp` 可保留 `true` | slim-routing §3.5/§6.4 | AST/反射断言 P11 重构后 mutation/command client 仍 `retryOnConnectionFailure(false)`；P11 client 退休/重建不得回退该位 |
| **R6** | **`X-Slimapi-Version:1` 不 bump、每 `/slimapi/**`（含 SSE）必带**：`SlimapiVersionInterceptor` 在 slimapi base URL 时挂头 | slim-routing §3.1 + architecture §5 freeze 行为保持 | P11 拦截器捕获 `HostSnapshot` 后仍按 base URL 前缀注入版本头；版本号字面量不变 |
| **R7** | **不变量 I5/I6/**I7*/I8/I15/I20** + `@Volatile` 路由位 + freeze 公共面保持**：`slimStateLock` 单实例（I5）；`configure()` 原子事务（I6：ticket→cert→hostConfig→rebuild→completeSlimReconfigure→source 束/setSlimConnection，同一 `@Synchronized`）；**I7 双监视器锁序：`TofuRepository` 自带 `@Synchronized` 经 OCR 回调 rebuild 须走同一 monitor，反向锁序会死锁——P11 重写 `rebuildClients`/`configure()` 不得反转该回调锁序**；**I8 `serverCompatProfile` 写点仅 `update`/`updateSlimapi`/`setSlimConnection`——P11 不得新增写点**；token threading（I15）；公共 FQN（I20）；`@Volatile` 路由 ref | architecture §5 | `T3RepositoryExtractFreezeTest` GREEN（反射锁 ~40 法 + slimStateLock + FQN）；P11 改 graph 须保持 I6 事务边界 + I7 锁序 + I8 写点封闭 |
| **R8** | **slim/legacy 双路测试保持 GREEN + base-URL 断言**：`OpenCodeRepositorySlimapiEndpointsTest`（wire 契约）+ `ServerCompatProfileCapabilitiesTest`（能力真值表）+ slim bookmark/token/watermark 并发不回归测；P11 后追加断言：**slim profile → 5 client baseUrl 全为 slimapi；legacy profile → 5 client baseUrl 全为标准 host；两者各用各自 generation；slim profile 无第二个直连 opencode client**（直接守 R3/R4，比纯行为测试更强）；「双变体同 generation」断言（REST 与 token-stream 用同 published SSL） | architecture §8 + slim-routing §4 | 每 wave check.sh 含上述测试 |
| **R9** | **slim 功能面不回归**：skeleton/full/since 增量、聚合 question/permission（routeToken）、策展 SSE（`/slimapi/events` 帧矩阵）、token-stream（`/slimapi/sessions/{sid}/stream`）、health/ready 自检、G2 status 三态、G6 batch full | slim-routing §4/§5 | S2 freshness token / LoadedContent 对 slim message-merge 与标准 message-merge 一视同仁；S3 Fix① 对 slim token-stream 与标准 SSE 控制面均不误伤 |
| **R10** | **标准 API（legacy）功能面不回归**：直连 opencode 形态（`/session`、`/global`、`/file`、`/vcs`、`/question`、`/permission` 等 B 桶 38 法）在非省流 profile 下行为逐字不变 | architecture §1 + slim-routing §4.2 | 双路测试 + Fix② 后标准 profile 仍 `slim=false`；P11 graph 对标准 profile 构建等价 client 集 |
| **R11** | **既有 generation/epoch CAS 计数器语义不污染**：`sseConnectedGeneration`（SSE 控制面传输存活，`ServiceSseConnectionOwner` 单调 CAS）/ identity `epoch-CAS bind`（`ConnectionIdentityStore.bindIfCurrent`）/ `TokenStreamCoordinator.epochBySid`（token 入帧标记）/ `genBySid`（token 出向 clear 标记）**与 P11 新引入的 `ClientBundle` generation 正交、不复用、不误绑** | architecture §5 v0.13.5 + §10 术语表 | P11 新字段独立命名（见 §10）；freeze 测试保持 `sseConnectedGeneration` 单调 CAS + identity epoch-CAS GREEN；不把 `genBySid`/`epochBySid` 当网络图 generation |
| **R12** | **architecture §5 其余并发不变量不被本 train 回归**（本 train 未触碰项，由既有 freeze/test gate 继承）：draft persist 并发（`SessionPrefs` `debounceLock`/`persistLock` 不嵌套）、流式渲染数据流（per-block streaming slice、value-dependent renderBlocks、`@Stable` 约束）、`FileShareUtils` IO/Main 分派、resolver null fail-closed 语义、`checkHealth`/`checkHealthFor` 不可二分路径、`coldStartSlimSync`/`requireSlimTokenCurrent` 留门面 | architecture §5/§6 | 每 wave check.sh 保持相关 freeze/并发测试 GREEN（`SessionPrefsDebounceTest`/`ServiceSseConnectionOwnerSseConnectedTest`/`ConnectionCoordinatorTest` 等）；本 train 新增代码不得反转这些不变量 |

> **R 系列是本整合相对三份源方案的增量门控**：源方案各自未必显式覆盖「跨方案合并后的双变体不腐蚀」，本宪章补齐。

### 1.2 整合的增量价值（除防回归外）

1. **共享写域冲突**：S2 与 S3 都改 `ChatViewModel.kt`/`AppCoreOrchestration.kt`/`MessageActions.kt`；S3 Fix② 与 P11 都改 configure 路径；P11 真实 token-stream 装配点在 `di/ControllerModule.kt`（v1 漏列）。
2. **隐性交互契约**：S3 Fix① 的 open() 幂等与 P11 generation 绑定**非简单正交**（v1 判定过强，见 §6 C1）；须钉死 generation/endpoint invalidation 契约。
3. **用户决策瓶颈集中**：P11 Option A/B/C、off-by-one 契约、D3 epoch gap 处置、release train —— 收敛为单一 Wave 0。
4. **统一评审/发版节奏**：按 wave 统一门控与 release-train，避免三套各自跑。

---

## 2. 共享写域地图 + token-stream 装配链（v1 修正）

> 规则继承 `decomposition-guidelines.md` + `remaining-phases-plan.md` §「Write-scope map」+ Wave-4a 事故教训：**写域不重叠时可并行；共享文件必须串行**。fixer edit-only、orchestrator 独占 check.sh、并发 gradle 守卫。

### 2.1 真实 token-stream 装配链（v1 漏列 ControllerModule，已补）

```
di/ControllerModule.kt:275-334  provideTokenStreamCoordinator
  → EffectiveConnectionConfigResolver.resolve()          // URL 单一权威（architecture §5 RESOLVER）
  → OpenCodeRepository.tokenStreamClient(hostPort)        // OCR:751-752
  → OkHttpClientFactory / RepositoryNetworkGraph(P11后)   // client 构造（含 mTLS）
  → TokenStreamClient.connect(...)                        // data/api
  → TokenStreamCoordinator.open(sid, dir)                 // ui/controller/sse（幂等 guard 在此）
```

- `AppCoreOrchestration.kt:875` 仅是 `tokenStreamCoordinator.open(sessionId, currentWorkdir)` **调用点**，非 client 构造/注入点（v1 误把它当 P11 写域核心，已纠正）。
- `TokenStreamCoordinator` 的 open()-幂等相关状态 = `currentSid`/`currentDirectory`/`currentStreamJob`（`:165-169`）；另有 `epochBySid`（token **入帧**标记）/`genBySid`（token **出向 clear**标记，`:172/180`，注释明说与 epoch「serve different guards」）。**关键**：这些都不是网络图 generation——coordinator **无任何字段绑定 published `ClientBundle` generation / endpoint fingerprint**，这是 C1 的根因（见 §10 术语消歧）。

### 2.2 写域矩阵（v1 修正版）

| 文件 | S1 P11 | S2 chat-list-detail | S3 SSE | 整合处置 |
|---|---|---|---|---|
| `data/repository/OpenCodeRepository.kt` | **写**（ctor + graph + configure + D3） | 读（L0–L3 零改） | 读（诊断） | P11 独占（Wave 2A） |
| `data/repository/SlimSseStateMachine.kt`/`SlimSseReducer.kt` | **可能写**（D3 epoch capture） | 读 | 读（诊断） | P11 独占（Wave 2A/3） |
| `di/ControllerModule.kt`（`provideTokenStreamCoordinator` :275-334） | **写**（token-stream provider 绑 generation） | — | — | **P11 独占，且属 Wave 3 集成段**（v1 漏列，已补） |
| `di/*`（Hilt graph 其余 leaf） | **写**（删 `@Inject`/`@Singleton` on `SslConfigFactory`/`OkHttpClientFactory`/host-dependent interceptors） | — | — | P11 独占（Wave 2A） |
| `EffectiveConnectionConfigResolver`（token URL 单一权威） | **可能写**（endpoint fingerprint 校验钩子） | — | 读 | P11（Wave 3，C6） |
| `ui/controller/sse/TokenStreamCoordinator.kt` | **契约变更**（generation/endpoint invalidation） | — | **写** Fix①（open 幂等） | **Wave 1（Fix①）→ Wave 3（generation 契约）串行** |
| `ui/controller/HostProfileController.kt`（`configureRepositoryForProfileRaw` `:583`/`:673`） | **写**（configure 路径重写） | — | **写** Fix②（`:673` 补 `slim=profile.slim`） | **S3 Fix② 先落（Wave 1），P11 吸收（Wave 2A/3）** |
| `service/streaming/ConnectionBootstrapEngine.kt`(`:119`)/`ui/ConnectionActions.kt`(`:41`)（其它 configure caller） | **契约约束**（slim provenance） | — | — | `ConnectionBootstrapEngine:119` **已正确** `slim=key.slim`（Wave 1 freeze only）；`ConnectionActions:41` **未传 slim**（Wave 1 Fix② 一并修，见 B1） |
| `ui/ChatViewModel.kt` | — | **写**（B5 editFromMessage/retryRevertCutoff 读 route param） | **写**（`:145-152` open 入门 + source 标签） | 共享 → 串行（S3 → S2） |
| `ui/AppCoreOrchestration.kt` | 读（`:875` open 调用点，P11 通常不改） | **写**（§9.2 guard `:818-820` 保留 + 导航接线 OrchestratorVM） | **写**（`:869-876` source 标签） | 共享 → 串行（S3 → S2；P11 仅在需传 generation 时才动） |
| `ui/MessageActions.kt` | — | **写**（§9.2 guard 叠 freshness token） | 读（`:68-80` single-flight，不改但语义共享） | S2 独占写；Wave 1 **未**清空其语义共享（v1 措辞错误，已纠正） |
| `ui/SessionViewModel.kt`/slices/reducers/`AppShell.kt`/`ChatScaffold.kt` | — | **写**（B1–B6） | — | S2 独占 |

**导出的串行/并行判据**：
- **可并行**（写域不重叠）：P11 的 `OpenCodeRepository.kt`/`SlimSse*`/`di/*` graph leaf / 网络测试 ∥ S2 的纯 UI 文件（SessionViewModel/AppShell/ChatScaffold/slices/reducers）。
- **必须串行**（共享文件，同一时间只一个 writer）：`TokenStreamCoordinator.kt`、`ControllerModule.kt`(provider)、`HostProfileController.kt`(configure)、`ChatViewModel.kt`、`AppCoreOrchestration.kt`、`MessageActions.kt`(语义)。

---

## 3. Wave 0 — 用户决策门（按阻塞范围拆分，v1 修正）

> v1 把 D-TRAIN 也列为「阻塞全部 wave」，与「Wave 1 可并行先发」自相矛盾。v2 按各决策**实际阻塞范围**拆分。

| 决策 | 内容 | 阻塞范围 | 建议 |
|---|---|---|---|
| **D-P11** | P11 选 Option A / **B（repo-owned RepositoryNetworkGraph）**/ C（拒绝） | **Wave 2A/3（P11）** | **B**（P11 分析推荐：blast radius 最小、命中 stale-client 最高风险；F2 不变 4∧6∧2-arg） |
| **D-D3** | P11 是否**闭合** `OpenCodeRepository.kt:313-323` 的 D3 epoch check release-gate gap（capture epoch/ConnectionIdentity at reconcile、validate at commit、host switch 后拒 stale slim commit）？还是显式延期（带风险接受 + follow-up 任务号）？ | **Wave 2A/3（P11 exit gate）** | **闭合**（P11 已引入 generation token，边际成本低；不闭合则 slim 跨 host stale-commit 风险残留，与 R9 冲突） |
| **D-RETRY** | `SlimSyncEngine.kt:566`(sessions) + `SessionSource.kt:99`(status) 的 `attempts<3` 偏移：**3 次总** vs **4 次总（3 retry）**？触发面仅 503+transform_busy 还是 batch/session/single-full 统一？ | **仅 Wave 5** | 待用户给契约 |
| **D-TRAIN** | release 编排：单次 minor（含每 wave commit/回滚点）vs 三段 patch/minor/patch vs S3 单独 patch 先行 | **仅 release 编排，不阻塞实现** | **单次 minor**（S3/P11 共享 token-stream 生命周期契约，三段会引入中间兼容矩阵；除非 190ms 故障需紧急上线，则 S3 例外单独 patch） |

> Wave 1（SSE）不依赖 D-P11/D-D3/D-RETRY；D-TRAIN 不阻塞实现。故 Wave 1 可在 Wave 0 决策期间**并行先发**。

---

## 4. Wave 划分（DAG：核心并行 + 集成串行，v1 修正）

> v1 把 Wave 1→2→3 固定为全局严格串行，过保守（仅 `AppCoreOrchestration` 一个文件交叠不足以证明全局串行）。v2 改为 **DAG**：写域不重叠的核心实现并行，共享集成段串行。

```
Wave 0  用户决策门（D-P11 / D-D3 阻 Wave 2A/3；D-RETRY 阻 Wave 5；D-TRAIN 仅 release）
   │ (Wave 1 不阻塞，可并行先发)
   ▼
Wave 1  SSE 自取消修复（S3）                          [小，先发，冻结 open() 幂等基线]
   │   1a: Fix① + Fix② + 诊断 + 幂等单测 → check.sh
   │   1b: 模拟器 repro exit gate（确认根因闭环 / 冻结 backoff 契约）
   ▼
Wave 2  核心并行（写域不重叠的双子轨）              [须 D-P11 / D-D3 for 2A]
 ┌─ 2A  P11 core：rev-bgpt 设计 → failing-first 网络回归 → RepositoryNetworkGraph
 │       + immutable ClientBundle/HostSnapshot(含 slim) → 删 Hilt leaf @Inject →
 │       slim/legacy 双路保持（R3/R7/R8）→ check.sh --full
 │       写：OpenCodeRepository.kt / SlimSse* / di/* graph leaf / 网络测试（**不碰** ControllerModule provider / TokenStreamCoordinator）
 └─ 2B  S2 chat-list-detail UI：B0 → B0.5 → B1 → B2 → B3 → B4 → B5 → B6
         每批 rev-glm + 每 checkpoint check.sh；最终 rev-bgpt ≥9.5；模拟器 connectedTest
         写：SessionViewModel/AppShell/ChatScaffold/slices/reducers/MessageActions/ChatViewModel（**共享文件在 2B 内串行**）
   ▼ (2A 与 2B 写域不重叠 → 可并行；二者收尾后进集成段)
Wave 3  共享集成（串行 joint）                       [P11 安全属性在此闭合]
   • ControllerModule.provideTokenStreamCoordinator 绑 generation（C1/C4）
   • TokenStreamCoordinator generation/endpoint invalidation 契约（C1）
   • EffectiveConnectionConfigResolver endpoint fingerprint 校验（C6）
   • P11 stale-generation 拒绝 + D3 epoch capture-and-validate（C7，按 D-D3）
   • 老 generation retirement（stream/REST/command/mutation，C9；保持 R5 mutation no-retry）
   • Fix① 幂等跨 generation 仍成立（C1 验证）
   failing-first：mTLS+REST / mTLS+token(同 generation) / host-reconfigure stale-client /
                  Fix① 幂等跨 generation / reconnect-backoff 跨 generation / F2 arity / Hilt compile+injection / D3
   rev-bgpt ≥9.5
   ▼
Wave 4  P12-doc + P4.1 + 可选测试加固（S1 收尾）     [低风险]
   ▼
Wave 5  排队 follow-up（独立预算，须 D-RETRY）
        • REST-overwrite optimistic-busy（UnreadSoakController + StatusPollOrchestrator）
        • off-by-one attempts<3（sessions/status）
        • SlimResyncOrchestrator 抽取（延期，协议成熟后再评估）
```

> **P11 安全属性的闭合点在 Wave 3 exit**（非 2A）：2A 建图 + DI + 双路保持，但 stale-client / generation / token-stream 的端到端保证要到 Wave 3 集成才交付。计划显式声明：**P11 完成 = Wave 3 rev-bgpt ≥9.5**。
>
> **Wave 2A 硬约束（防中间态误判）**：Wave 2A 产物**不可**单独合并为可交付 P11；在 Wave 3 前**不得**宣称 stale-client 防护、token-stream generation 绑定、D3 epoch 闭合、跨 host retirement 已闭合——这些仅在 Wave 3 exit gate 全绿后才成立。2A 可独立 check.sh green + rev-bgpt 评「2A 段」，但不发版、不标 P11 完成。

---

## 5. 各 Wave 详表

### Wave 1 — SSE 自取消修复（S3 落地）
- **1a（立即做，同 commit）**：
  - Fix① `TokenStreamCoordinator.open()` 同 sid+同 dir+活跃 lifecycle → 幂等跳过 supersede（`Main.immediate` 单线程，同步无 TOCTOU）；dispatcher 前提注释/断言（bgpt 残值）。
  - 诊断日志：`open()` entry 含 `source`；`launchStreamLifecycle` 的 `prior?.isActive`；`loadMessagesForEffect:875` 与 `ChatViewModel:151` 传常量 `source`。
  - Fix② **slim provenance 全 caller 闭环**（消除 v2 C2「全 caller」与「只排 :673」的矛盾）：`HostProfileController.kt:583`(`configureServerRaw`，已持 `profile`)+`:673`(`configureRepositoryForProfile`)+`ui/ConnectionActions.kt:41`(冷启动，已持 `currentProfile`) **三处**均补 `slim = <profile>.slim`；`service/streaming/ConnectionBootstrapEngine.kt:119` **已正确**（freeze only，不改）。同一 commit，独立于 Fix①。
- **1b（加诊断后做）—— exit gate**：
  - 模拟器/受控 repro logcat：确认 `priorActive=true` 且 `source=effect-load` → 根因定案（85%→确认）。
  - 若未证实：**不得自动扩大修复**，进诊断 follow-up；冻结 open-during-backoff 契约（当前：同 sid reconnect-backoff 中 open → skip 且复用 backoff，可接受；「是否提升为立即连」=低优 UX，搁置）。
- **写域**：`TokenStreamCoordinator.kt` / `HostProfileController.kt` / `ChatViewModel.kt`(标签) / `AppCoreOrchestration.kt`(标签)。
- **防回归（R）**：Fix① 须对 slim token-stream（`/slimapi/sessions/{sid}/stream`）与标准 SSE 控制面（`/global/event`）**均不误伤**（R9/R10）；Fix② 后**标准 profile 仍 `slim=false`**（R10）——加双路断言。
- **评审**：rev-bgpt ≥9.5（S3 已 bgpt+opus 收敛，本层为实现层评审 + R 系列核验）。
- **check**：1a 收尾 `./scripts/check.sh`（含 slim/legacy 双路测试）。

### Wave 2A — P11 core（Option B，须 D-P11/D-D3）
- **范围**（依 P11 分析 §4 + §5）：
  1. 锁定归属设计（process/repo/generation 三层 lifetime、唯一变更入口、原子发布边界、退休语义、stale-result 策略、token-stream endpoint 匹配、**slim 位承载与双变体不腐蚀**）。
  2. failing-first 网络回归测试（在 Wave 3 集成时全绿）：重复 SSL-factory 守卫 / mTLS+REST / mTLS+token-stream / host-reconfigure stale-client / F2 arity 反射 / Hilt compile+injection / **D3 epoch capture-and-validate**（按 D-D3）/ **双变体同 generation**（R8 增项）。
  3. 改图（有序）：`RepositoryNetworkGraph` 行为保持 wrapper → 搬手动构造 → 删 leaf Hilt 注解 → immutable `HostSnapshot(含 slim)` → 拦截器捕获 snapshot（**保持 R6 版本头注入**）→ 单原子 `ClientBundle(5 client 同替)` → `configure()` 改 candidate-build→atomic-publish→retire（**保持 I6 事务边界 + source 束在 completeSlimReconfigure 后选**）。
  4. 更新 F2（仅措辞，保留 4∧6∧2-arg/Hilt 4 期望）。
- **写域**：`OpenCodeRepository.kt` / `SlimSseStateMachine.kt`/`SlimSseReducer.kt`(D3) / `di/*` graph leaf / 网络测试。**不碰** `ControllerModule.provideTokenStreamCoordinator`（留 Wave 3）、`TokenStreamCoordinator`（留 Wave 3）。
- **防回归（R）**：R3（5 client 同替、无 slimMode flag）、R4（C 桶=0）、R5（mutation/command `retryOnConnectionFailure(false)` 退休/重建不回退）、R6（版本头）、R7（I5/I6/I15/I20 + `@Volatile` + freeze）、R8（双路 GREEN + 增「同 generation」断言）、R10（标准 profile 等价 client 集）。
- **评审**：rev-bgpt 设计前置 → 实现 → `check.sh --full` → rev-bgpt（2A 段，非 P11 最终闭合）。

### Wave 2B — Chat List-Detail 重构（S2 落地，与 2A 并行）
- **范围**：依 S2 §12 批次 B0→B0.5→B1→B2→B3→B4→B5→B6。
- **写域**：UI 域（共享文件在 2B 内串行，S2 §13.2）。
- **依赖**：Wave 1 收尾（清空 S3 对 `ChatViewModel`/`AppCoreOrchestration` 的**并发 writer**；`MessageActions` 语义共享留 2B B4/B5 核验）。
- **防回归（R）**：freshness token / LoadedContent 对 slim message-merge（slim reconcile）与标准 message-merge **一视同仁**（R9）；L5 UI 保持模式盲（R2，不读 isSlimMode）；slimapi L0–L3 零改（S2 §3）—— check 时双路 message-load 断言。
- **评审**：每批 rev-glm；每 checkpoint `check.sh`（B0/B0.5/B1/B2/B4/B6 等源方案规定点，**非仅 wave 末尾**——v1 错误已纠正）；最终 rev-bgpt ≥9.5；模拟器强制 connectedTest（导航/通知-深链冷启动/子 agent 返回/删归档当前会话）；评审产物归档 `review-gate.md`。
- **fixer 分配**：B0/B1/B2/B4/B5 复杂→base fixer；B3/B6 机械→fixer-zlm（**禁用 fixer-zlm 做复杂逻辑**，P8 教训）。
- **版本**：minor（移除 tab，用户可见行为变更）。

### Wave 3 — 共享集成（串行 joint，P11 闭合）
- **范围**：见 §4 Wave 3 列表（ControllerModule provider 绑 generation / TokenStreamCoordinator generation 契约 / resolver fingerprint / stale-gen 拒绝 + D3 / retirement）。
- **写域**：`di/ControllerModule.kt` / `TokenStreamCoordinator.kt` / `EffectiveConnectionConfigResolver` / 可能 `AppCoreOrchestration.kt`(若 generation 须显式传) / `HostProfileController.kt`(吸收 Fix② 进 snapshot)。
- **依赖**：2A + 2B 收尾。
- **防回归（R）**：C1 跨 generation 幂等验证（R9 slim token-stream 不误伤）；R5 retirement 不回退 mutation no-retry；R6 版本头跨 generation；R8 双路 + 「REST 与 token 用同 generation SSL」（P11 §5 测试）。
- **评审**：failing-first 全绿 → `check.sh --full` → rev-bgpt ≥9.5 = **P11 完成**。

### Wave 4 — P12-doc + P4.1 + 可选测试加固（S1 收尾）
- **范围**：P12-doc（P4-review P2-1 文案 / P3-1/2/3 KDoc / P5 🟡1/2 可选 / stale line refs 全量核验 `remaining-phases-plan.md:32-35`）；P4.1（P2-2 protocol/port 单测 `SlimSessionReconciler`、P2-3 `applyReconcileResult` default-mode 安全）；可选 P5/P8 测试加固。
- **依赖**：所有结构性 wave 完成。
- **评审**：rev-bgpt ≥9.5；纯 doc/加法测试，风险低。

### Wave 5 — 排队 follow-up（独立预算，须 D-RETRY）
- REST-overwrite optimistic-busy / off-by-one attempts<3 / SlimResyncOrchestrator 抽取（延期）。

---

## 6. 跨方案交互契约（整合特有，须评审重点核查；v1 C1 修正 + 新增 C6–C9）

| 契约 | 内容 | wave |
|---|---|---|
| **C1（v4 单一 immutable ClientBundle + lifecycle 绑定 + 提交前复验）** | **发布**：repository 以**单个 immutable `ClientBundle(generation, endpointFp, slimHost, restApi, sseClient, commandApi, mutationApi, apiV2, owned OkHttp clients)`** 经**单一 `@Volatile currentClientBundle: ClientBundle?`** 原子发布（candidate-build→publish→retire，I6 事务内）；**禁**分别 `@Volatile` 各字段（否则 `open()` 撕裂读：新 gen+旧 endpointFp / 旧 gen+新 bundle）。**读取**：`open()` 在 Main.immediate **一次性** `val bundle = repository.currentClientBundle() ?: fail-closed-return`；generation+endpointFp **派生自该 bundle**，不自拼多 volatile。**lifecycle 绑定（opus note：resolve-time 捕获）**：每个 lifecycle 在 `streamProvider` 经 debounce 后**实际 resolve 处**捕获该 immutable bundle 作为 `lifecycle.bundle`（含其 generation+endpointFp）——**不**在 open() 入口（debounce 前）捕获，避免「debounce 窗口内 configure() publish 新 bundle → 记录的 boundGen ≠ 实际所用 bundle」off-by-one。**幂等 skip 判据**：`open()` skip ⟺ `(currentSid, currentDir) == (sid, dir)` 且 active lifecycle 的 `lifecycle.bundle` 引用 == `repository.currentClientBundle()`（同一 immutable 对象，即同 generation+同 endpointFp）且 job active。**事件语义**：(a) 同 bundle+同 sid+同 dir+active → skip；(b) `currentClientBundle()` 已是新对象（gen/endpoint 变）→ 旧 lifecycle 不满足幂等 → supersede+用新 bundle；(c) 同 sid+异 dir → supersede；(d) reconnect-backoff 中 open 同 sid+同 bundle → skip 复用 backoff。**提交前复验（bgpt blocker #2）**：每个网络结果（token 帧合并 / REST message-merge / SSE dispatch / slim reconcile commit / command-mutation 完成）提交前**必须**验证 `lifecycle.bundle === repository.currentClientBundle()`（或等价地 `lifecycle.bundle.generation == current.generation && lifecycle.bundle.endpointFp == current.endpointFp`）；不一致 → 取消/丢弃，**不**更新 coordinator/SSE 状态/slim state。**supersede 原子替换** active lifecycle 引用（单 CAS）。**Wave 3 failing-first 增测**：(i) `configure()` 恰发生在 `open()` 比较→connect 之间；(ii) 旧连接已收数据、提交前 host 切换；(iii) reconfigure 落在 debounce 窗口内（opus）；(iv) 撕裂读不可达（单 immutable bundle，多 volatile 拼装被 AST/审查禁）；(v) Fix① 幂等与 `sseConnectedGeneration` CAS 互不干扰（R11）。 | 1→3 |
| **C2（v3 全 configure caller，4 入口逐条状态）** | slim provenance 闭环（**Wave 1 全部完成**，消除 v2「C2 全 caller vs Wave 1 只排 :673」矛盾）：(1) `HostProfileController:583`(`configureServerRaw`，持 `profile`) **Wave 1 补 `slim=profile.slim`**；(2) `HostProfileController:673`(`configureRepositoryForProfile`，持 `profile`) **Wave 1 补**；(3) `ui/ConnectionActions:41`(冷启动，持 `currentProfile`) **Wave 1 补 `slim=currentProfile.slim`**；(4) `service/streaming/ConnectionBootstrapEngine:119` **已正确 `slim=key.slim`（freeze only）**。P11 `HostSnapshot` 须承载 slim；OCR.configure `:621` 默认 `false` 仅测试用。AST/测试门控：每生产 configure caller 显式传 slim（Wave 1 后 4/4 命中）。 | 1→2A |
| **C3（v2 措辞修正）** | S2 freshness token（message-merge 层）与 S3 open 幂等（stream 层）**不同域、互补不互斥**。**但 Wave 1 未清空 `MessageActions` 语义共享**——S2 B4/B5 须重新核验：freshness token **不得**把 `isLoadingMessages` single-flight 误当 freshness；`MessagesMerged`/loadMore/send-completion/refresh 所有写点带 expected-session+freshness；S3 open 幂等**不依赖** REST load flag 已设。 | 1→2B |
| **C4（v2 文件集补全）** | 共享文件串行集合 = `{TokenStreamCoordinator.kt, ControllerModule.kt(provider), HostProfileController.kt(configure), ChatViewModel.kt, AppCoreOrchestration.kt, MessageActions.kt(语义)}`。v1 漏 `ControllerModule`/`TokenStreamCoordinator`，已补。 | 全部 |
| **C5** | freeze pin 跨 wave 一致：F2 仅 P11（Wave 2A，4∧6 不变）；F3/F5/F8/F10 在 Wave 1/2 保持不动；P11 另涉 `di` graph 删除 + token-stream provider + configure contract，不只看 F2。 | 全部 |
| **C6（v4 注入机制点名）** | resolver endpoint fingerprint 与 published `ClientBundle.endpointFp` 一致；不匹配则拒 open（P11 §4 token-stream 不变量）。**注入机制（opus note #2）**：Wave 3 扩 `EffectiveConnectionConfigResolver.resolve()` 返回 `(url, endpointFp, bundleGeneration)`（或暴露 `repository.currentClientBundle()` 一次性读）——**单一读源**同时服务 C1（coordinator 在 open()/resolve-time 读 bundle 引用作幂等与 lifecycle 绑定）与 C6（resolver URL 与 bundle endpointFp 比对）；**禁**为 C1/C6 各开一条独立读源（会撕裂）。 | 3 |
| **C7（v2 新增）** | host epoch 与 slim state commit 一致（D3）：每个跨网络 suspend 的 slim reconcile commit 带 captured epoch/ConnectionIdentity，commit 前 validate；host switch 后旧 result 拒写新 host `slimSseState`。 | 2A/3 |
| **C8（v2 新增）** | = C2（configure 全入口 slim provenance）。 | 1→2A |
| **C9（v2 新增）** | 老 generation retirement 语义：stream/REST/command/mutation dispatchers 取消、连接池 evict、**共享 disk cache 不关闭**（新 bundle 仍用）；mutation/command POST 取消 = "outcome unknown"（**禁自动重试**，R5），显式报 stale/indeterminate。 | 3 |

---

## 7. 并行与串行规则（DAG，v1 修正）

- **可并行**：Wave 0（决策进行）∥ Wave 1（SSE 先发）；**Wave 2A（P11 core，data/di/网络）∥ Wave 2B（S2 UI，ui-shell）**——写域不重叠（§2.2）；Wave 4 文档/测试叶子 ∥ Wave 5 独立预算项。
- **必须串行**：共享文件集（C4）上的提交；`ControllerModule` provider wiring、`TokenStreamCoordinator` generation 契约 = Wave 3 集成段（在 2A+2B 后）。
- **P11 取舍（v2 采纳 bgpt improvement #2）**：**设计先行 + core 隔离 + 集成后置**，而非「全局最后」。P11 设计 + failing-first 网络 test + graph core 在 2A（与 S2 UI 并行）；token-stream 共享 wiring 在 Wave 3（与 S2 收尾后串行）。
- **fixer 选型**：Wave 1 Fix① 若仅 Main.immediate 最小 guard → base fixer；若增 generation-aware owner state → base fixer + 设计评审（**禁在 C1 收敛前派实现**）。Wave 2A/2B-复杂/3 → base fixer；2B B3/B6、Wave 4 doc → fixer-zlm。
- **check.sh 纪律（v1 修正）**：orchestrator 独占，但**非仅 wave 末尾**——S3：1a 收尾；S2：源方案规定 checkpoint（B0/B0.5/B1/B2/B4/B6）各自 check；P11：targeted→`check.sh --full`；并发 gradle 守卫；edit-only fixer 不跑 git/gradle。

---

## 8. 统一评审门控 + release 节奏（v1 修正）

- **每 wave**：rev-bgpt ≥9.5（Wave 2B 另加每批 rev-glm）；**P11 完成 = Wave 3 rev-bgpt ≥9.5**。
- **本整合方案本身**：rev-bgpt + rev-opus 双轮收敛 ≥9.5（进行中）。
- **防回归门**：每 wave check.sh 含 R1/R8 双路测试；P11 后增「双变体同 generation」断言；release 前模拟器 connectedTest（2B 导航/深链/子 agent/删归档；3 mTLS/TOFU/REST/SSE/token-stream/command-mutation）。
- **发版前**：`.opencode/policies/review-gate.md` 评审产物归档。
- **release train（v2 默认单次 minor）**：一个 release candidate → 一次 minor（S2 用户可见行为变更）；每 wave 独立 commit/可回滚点；**除非** S3 190ms 故障需紧急上线，则 S3 例外单独 patch（v1 三段 patch/minor/patch 改为非默认，因 S3/P11 共享 token-stream 生命周期契约，三段引入中间兼容矩阵）。

---

## 9. 风险登记 + 给评审的开放问题（v2 已吸收 bgpt 回答）

### 9.1 整合层风险
| 风险 | 缓解 |
|---|---|
| C1 跨 generation 幂等被 P11 静默破坏 | Wave 3 显式核验 C1 + 增 4 类跨 gen 测试；Fix① 在 C1 收敛前不派实现 |
| C2 slim provenance 回退（默认 false） | AST 门控每生产 configure caller 显式传 slim；R10 标准 profile 等价 client 断言 |
| D3 gap 隐含残留 | Wave 0 D-D3 显式决策；闭合则 Wave 2A/3 exit gate，延期则带风险接受 + follow-up 任务号 |
| 共享文件串行链交付周期长 | DAG 核心并行（2A∥2B）+ 集成段隔离，缩短关键路径 |
| slim/标准 API 回归（用户硬约束） | §1.1 R1–R10 宪章每 wave 核验；双路测试 GREEN |
| 三份源方案共识深度不一 | 本整合不重审根因；P11 走自己的 rev-bgpt 设计前置（2A 内） |

### 9.2 给 rev-opus 的开放问题
1. DAG（核心并行 2A∥2B + 集成串行 Wave 3）是否优于 v1 全局串行？P11「设计先行 + core 隔离 + 集成后置」是否比「全局最后」更稳？
2. C1 条件正交（generation+endpoint fingerprint 入幂等 key）是否充分？P11 保证「reconfigure 先 invalidate coordinator」是否可证？
3. 防回归宪章 R1–R10 是否覆盖了用户「slim/标准 API 不回归 + 遵循项目标准」的全部关切？有无遗漏的不变量（如 SSE 控制面 `sseConnectedGeneration`、identity epoch-CAS、draft persist 并发，architecture §5 v0.13.5 项）须显式入宪？
4. D-D3「闭合」建议是否过载 P11？还是恰好（P11 已引入 generation，边际成本低）？
5. release 默认单次 minor 是否妥当？
6. C2/C8 覆盖 `ConnectionBootstrapEngine:119`/`ConnectionActions:41` 是否准确？这两处 slim 来源是否已正确（无需 P11 额外改）？
7. 是否仍有应并入却遗漏的工作项？

---

## 10. Generation / Epoch 术语消歧表（v3 新增，防误绑 — opus I1 / R11）

代码库现有 **4 个独立 generation/epoch 语义实体**，P11 新增第 5 个。**严禁互相复用/误绑**（误绑会静默破坏 SSE 控制面 CAS 或 Fix① 幂等或 identity 绑定）：

| 字段 | 所属 | 语义 | 守护 | 谁写 | 与本整合关系 |
|---|---|---|---|---|---|
| `sseConnectedGeneration` / `transportGenerationCounter` | `SharedStateStore` + `ServiceSseConnectionOwner` | SSE **控制面传输存活**，单调 CAS | 陈旧 SSE collector 写被拒；host purge 清零+推进 | `ServiceSseConnectionOwner`（唯一写者，generation-checked helper） | R11 保持；**不可**作 Fix① 幂等 key |
| identity `epoch`（`ConnectionIdentityStore.bindIfCurrent(..., expectedEpoch)`） | `ConnectionIdentityStore` | **连接身份** epoch-CAS bind | probe bind-before-commit；reconfigure 互斥 | `beginReconfigure`/`bindIfCurrent`（同 monitor） | R11 保持；与 P11 bundle-gen 不同实体 |
| `TokenStreamCoordinator.epochBySid` | coordinator（per-sid） | token **入帧**标记 | 拒陈旧入帧 | `open(sid)` bump | 内部计数；**非**网络图 generation |
| `TokenStreamCoordinator.genBySid` | coordinator（per-sid） | token **出向 clear**标记（bgpt MF-3） | 拒陈旧 partId clear | `beginSession` bump | 内部计数；注释明说与 epoch「serve different guards」；**非**网络图 generation |
| **`ClientBundle`**（P11 新增，**单一 immutable 快照载体**） | `RepositoryNetworkGraph` / coordinator | **网络图 client 代**：每 `configure()` atomic-publish **一个** immutable `ClientBundle(generation, endpointFp, slimHost, 5 clients)`，经**单一 `@Volatile currentClientBundle`** 发布；`open()` 一次性读该对象；每个 lifecycle 在 resolve-time **捕获该 immutable bundle 引用**为 `lifecycle.bundle`；generation/endpointFp **派生自该 bundle**（无独立 `boundBundleGeneration` volatile 字段——避免撕裂读）。守：老 client 不读新 host 凭证、stale result 不提交、Fix① 幂等比对 lifecycle.bundle 引用相等 | `configure()` candidate-build→publish（OCR monitor，单一 `@Volatile`）；coordinator `open()` 一次性读 | **C1 核心**；R11 守其与上 4 者正交；C6 用 `bundle.endpointFp` 与 resolver 校验 |

> **实施铁律**：P11 新字段独立命名（`clientBundleGeneration` / `boundBundleGeneration` / `boundEndpointFp`），**禁止**复用 `genBySid`/`epochBySid`/`sseConnectedGeneration`/identity epoch。freeze 测试须断言「Fix① 幂等比对 `boundBundleGeneration`，不是 `genBySid`」。

---

## 附录 A：源方案 freeze pin / 关键约束速查
- **F2**：OCR ctor arity 4∧6（Kotlin 源 4 / JVM 普通 4 / default-arg synthetic 6 / 2-arg 测试保留 / Hilt 4）—— 仅 P11（Wave 2A）措辞更新。
- **F3**（SlimCommitToken 不加字段）/ **F5**（SSC 公开 API）/ **F8**（SessionListActions 自由函数签名）/ **F10**（UI seam ChatMessageList 入口）。
- architecture §5 不变量 I5/I6/I7/I8/I15/I20 + 并发路由 `@Volatile` + v0.13.5 项（RESOLVER 单权威 / identity epoch-CAS / SSE transport generation / draft persist 并发 / 流式渲染数据流）。
- S2 不变量 P1–P6、门控 G1–G6；S3 Fix① 前提：`open()` 永远在 `@UiApplicationScope = Main.immediate`。
- slim-routing：5 client 同替、mutation no-retry、X-Slimapi-Version=1、A/B/C/D 桶（C=0）。

## 附录 B：源方案可追溯会话
- S1：ora-3（P11 所有权）/ rev-2（P11/P12 设计）/ fix-5（实现）。
- S2：explorer（引用矩阵）/ librarian（业界对照）/ oracle（Option B）/ rev-bgpt v1/v2 + rev-opus + rev-bgpt 复核。
- S3：编排者代码核实 + rev-bgpt Stage-1 + rev-opus Stage-2 终审。
- 本整合评审链：v1 → rev-bgpt `ses_06b0cdc1…`（8.3/BLOCK）→ v2 全量吸收 + 防回归宪章 → rev-opus `ses_06b04f8d…`（9.3/CONDITIONAL-PASS，B1+I1–I5）→ v3 吸收 → **v3 双轮确认：rev-opus `ses_06af9ab5…`（9.5/PASS，无 blocker，2 Wave-3 note）；rev-bgpt `ses_06af9d5b…`（9.3/CONDITIONAL-PASS，C1 原子快照 + compare-to-launch 2 blocker）** → **v4** 吸收 bgpt 两 blocker + opus 两 note + 非阻塞补强 → 待 v4 双轮共识确认（≥9.5）。
