# OCDroid 综合调研报告（2026-07-26）

> 面向后续 agent 的权威调研底稿。本文为静态代码 + 文档审计产出，**不包含运行期测量数据**；凡涉及性能的判断均标注「需 benchmark 证实」。后续 agent 可直接据本文派发改进任务。

---

## 1. 调研范围、方法、时间与限制

| 项 | 内容 |
|---|---|
| 产出日期 | 2026-07-26 |
| 代码基线 | HEAD `a0c136b`（`git describe` = `v0.14.7`，即 v0.14.7 tag 之后提交） |
| 调研方法 | 静态代码审计 + 文档对照审计。阅读关键源码路径、构建脚本、CI 工作流、docs/specs；统计文件/行数；逐条核对事实声明。**未运行** macrobenchmark / 性能采集 / 集成测试。 |
| 覆盖范围 | 架构、网络/SSE/安全、Compose UI、工程化（Gradle/CI/覆盖率/静态检查）、测试、文档。 |
| 已知限制 | ① 性能与分配热点为代码静态推断，**必须经 profiling/benchmark 证实**后才可定性。② LOC 高 ≠ 必须拆分——本文明确拒绝「按行数机械拆分」。③ 未做真实设备 OEM 兼容性验证。④ 未审计第三方依赖 CVE（建议另开依赖审计任务）。 |
| 不变性 | 本文仅创建本文件，不修改任何源码/配置/脚本。 |

---

## 2. 执行摘要

**成熟度评价：成熟、工程纪律强、安全设计扎实，但复杂度高、可观测性/性能基线缺失。**

- **最强优势**：网络与 SSE 链路的安全/鲁棒设计是该项目的核心竞争力——TOFU SPKI 绑定、mTLS（hostname 严格）、连接 epoch 身份校验、§11 双通道（control 挂起保序 / delta 溢出转 dirty + REST reconcile）、no-store 默认 + Basic Auth 下强制 no-store 的缓存安全门。这套设计在同类 Android 客户端中属高水准。
- **核心问题（非灾难）**：
  1. **可观测性基线缺失**——无 macrobenchmark / baseline profile / allocation 追踪，性能讨论只能靠静态推断。
  2. **文档事实漂移**——SDK 版本（README/build-apk 写 minSdk=26，实际 34）、死链（`docs/current/...` 不存在）、architecture 对 Repository「薄门面」的描述与 3004 行现实脱节。
  3. **工程门禁不一致**——`release.sh` 走 `check.sh --lint`（无 Kover），CI `integration-check.yml` 跑 `koverVerify`，本地发版门禁弱于 CI。
  4. **首次连接用户体验**——URL 无 inline 格式校验、缺最小引导、错误分类（401/403/格式）可更明确。
  5. **关键路径 Compose 测试不系统**——存在 androidTest，但路由/重组边界/恢复路径覆盖零散。
- **明确结论：当前无已确认的 P0 灾难项。** 已发现的网络/安全/缓存设计经逐条核对均为**正向**或**设计意图明确**（详见 §10 误报澄清）。若未来出现安全漏洞或数据完整性故障（如缓存跨用户泄漏、epoch 失效导致串会话），则升级为 P0。

---

## 3. 项目概况与关键事实表

| 维度 | 事实 | 来源 |
|---|---|---|
| 定位 | OpenCode 协议原生 Android 客户端，远程连 AI coding agent | `README.md:5` |
| 技术栈 | Kotlin + Jetpack Compose + Material3 + Hilt + OkHttp/Retrofit + kotlinx.serialization/coroutines + DataStore | `gradle/libs.versions.toml`、`app/build.gradle.kts` |
| 工具链 | AGP 9.1.0 / Gradle 9.3.1 / Kotlin 2.2.10 / KSP 2.3.6 / JDK 21 | `docs/specs/build-apk.md:22-26` |
| compileSdk | **35** | `app/build.gradle.kts:65` |
| minSdk | **34**（Android 14+） | `app/build.gradle.kts:69` |
| targetSdk | **34** | `app/build.gradle.kts:70` |
| 应用 ID | `cn.vectory.ocdroid` | `app/build.gradle.kts:68` |
| ABI | 仅 `arm64-v8a`（release 包体收敛） | `app/build.gradle.kts:80-82` |
| 版本机制 | git 派生：`versionName`=`git describe`、`versionCode`=commit count；无硬编码 | `app/build.gradle.kts:37-61` |
| 生产 Kotlin 文件 | **332 文件 / 85,941 行** | 实测 `find app/src/main -name '*.kt'` |
| 单测文件 | **258 文件 / 105,130 行** | 实测 `app/src/test` |
| 插桩测试 | **13 文件 / 2,676 行** | 实测 `app/src/androidTest` |
| 当前版本 | v0.14.7 + 后续提交 | `git describe` |
| 主要功能 | Chat（token 流/工具调用/Markdown/Patch diff/Todo）、会话导航（根会话聚合/Tab 切换/归档）、Files（项目实体/文件树/git 状态/预览）、Settings（连接/Basic Auth/模型清单/缓存/主题）、平板三栏 | `README.md:7-13` |
| Kover 基线（文档） | Line 57.9% / Branch 55.1% / Instruction 54.9% / Method 56.4% / Class 56.9%（2026-07-06 实测，排除纯 @Composable/主题/Activity 后） | `app/build.gradle.kts:194-197` |

> **文档漂移警示**：`README.md:17` 写「Android 8.0+（API 26）」、`docs/specs/build-apk.md:27` 写「35 / 26 / 34」，**minSdk 实际为 34**。两处均需修正（见 P1 路线图）。

### 顶层文件规模（实测，LOC 降序）

| 文件 | 行数 | 角色 |
|---|---|---|
| `data/repository/OpenCodeRepository.kt` | 3004 | L3 冻结门面（~40 公共委托 + 内联领域逻辑） |
| `ui/controller/SessionSyncCoordinator.kt` | 2029 | SSE → store fold 主协调器 |
| `ui/chat/ChatMessageContent.kt` | 1463 | 聊天消息渲染 |
| `ui/controller/sse/TokenStreamCoordinator.kt` | 1392 | token 流聚合 |
| `ui/controller/SlimSessionReconciler.kt` | 1355 | slim 模式会话调和 |
| `service/lifecycle/StreamingLifecycleCoordinator.kt` | 1221 | FGS 生命周期 |
| `ui/chat/ChatScaffold.kt` | 1210 | 聊天脚手架 |
| `service/streaming/ServiceSseConnectionOwner.kt` | 1098 | SSE 连接持有/重连/resync |
| `ui/sessions/SessionsScreen.kt` | 1027 | 会话页 |
| `ui/AppCoreOrchestration.kt` | 1017 | 编排层 |
| `ui/AppCore.kt` | 954 | dispatch 入口 |

---

## 4. 架构全景

### 4.1 包地图（简化）

```
cn.vectory.ocdroid
├── data/
│   ├── api/            OpenCodeApi (Retrofit) / SSEClient (OkHttp EventSource)
│   └── repository/
│       ├── http/       OkHttpClientFactory / SslConfig / HttpHeaders /
│       │               CacheControlInterceptor / AuthInterceptor / 各 *Interceptor
│       ├── OpenCodeRepository.kt   L3 冻结门面（slim/legacy 双源收敛）
│       ├── TofuRepository.kt       TOFU SPKI pin 管理
│       └── ExpandBatchEngine.kt    批量展开
├── service/
│   ├── SessionStreamingService.kt        前台服务（FGS, dataSync）
│   ├── bridge/SseEventBridge.kt          §11 双通道 + epoch 守卫
│   ├── streaming/ServiceSseConnectionOwner.kt   连接持有/重连/resync 触发
│   └── lifecycle/StreamingLifecycleCoordinator.kt
├── ui/
│   ├── SharedStateStore.kt        单一 StateFlow + DerivedStateFlow 切片（Redux-like store）
│   ├── AppCore.kt / AppAction.kt / AppStateSlices.kt / AppCoreOrchestration.kt
│   ├── controller/                SessionSyncCoordinator / TokenStreamCoordinator /
│   │                              SlimSessionReconciler / Connection* / HostProfile*
│   ├── chat/ sessions/ files/ settings/ shell/   Compose 屏幕
│   └── theme/                     共享原语（Dimens / AppBottomSheet / AppConfirmDialog / ...）
└── di/                            Hilt 模块
```

### 4.2 状态管理：Redux-like store + MVVM

- **单一可信源**：`SharedStateStore`（`ui/SharedStateStore.kt:53-89`）持有一个 `MutableStateFlow<StoreState>`，所有写入经 `dispatch`/`mutateXxx` 走单次 `state.update`（原子、无撕裂中间态）。
- **切片投影**：`connectionFlow`/`chatFlow`/`sessionListFlow` 等为 `DerivedStateFlow`（同步 `.value` 读取，无 dispatcher 跳跃），各 slice 独立 distinct-filter，UI 只重订阅关心的切片。
- **MVVM 边界**：`AppCore`（dispatch 入口，954 行）+ `AppCoreOrchestration`（编排，1017 行）+ 各 `Controller`（ViewModel 角色）消费 action，`@Composable` 层只读切片 + 发 action。
- **测试可达**：B2 原子性测试验证「单次 dispatch = 单次 emission」，`SessionArchived` 不会产生「list 已归档但 chat.currentSessionId 仍指向它」的撕裂态（`SharedStateStore.kt:57-73`）。

### 4.3 SSE 数据流图

```
OpenCode Server / oc-slimapi sidecar
        │  text/event-stream  (slim: /slimapi/events ; legacy: /global/event —— 动态选择 SSEClient.kt:138)
        ▼
OkHttp EventSource  ──  onEvent(type, data)  ──►  SSEClient.parseSseEvent  (三路解析, SSEClient.kt:357-395)
        │                                                │
        │                                     IdentifiedSseEvent(identity, event)
        ▼                                                ▼
ServiceSseConnectionOwner  ── 首帧/resync/server.reconfigured 触发 cold-start (Owner.kt:668-706)
        │  sseEventStream.emit(Result<IdentifiedSseEvent>)
        ▼
SessionStreamingService.events  ──►  SseEventBridge.start (bridge.kt:24-67)
        │
        │  routeIfFresh: epoch != currentEpoch → 丢弃（stale host / pre-reconfigure 帧）
        │               (bridge.kt:195-203)
        ├─► controlChannel (SUSPEND, FIFO, 保序不丢)   ← session.status / server.connected / permission.* / question.*
        │        ▼
        │   AppCore.dispatchConnectionEffect → SessionSyncCoordinator.fold (二次 epoch 校验)
        │
        └─► deltaChannel (trySend, 溢出 → markDeltaOverflow → dirtySessions)  ← message.part.delta 等
                 ▼
            TokenStreamCoordinator 聚合 → store mutate
                 ▼
            后台 REST reconcile 回收 dirty 会话（owner/SSC）
```

**关键不变量**：① epoch 双重校验（bridge + fold）保证 reconfigure 期间的帧绝不污染新 host 的状态。② control 永远不被 delta 洪水饿死（独立通道 + 挂起 vs trySend）。③ delta 溢出不丢数据——标记 dirty，由 REST 兜底。

### 4.4 网络层（正向设计，详见 §6.6）

- 单一 SSL 入口：`OkHttpClientFactory` 全部 client 经 `applySsl(cfg)`（`SslConfig.kt:248-260`）。
- 三种 SSL 模式：`SystemDefault`（公网 CA）/ `TofuPinned`（SPKI 绑 host:port，hostname 放行因 pin 即身份）/ `MutualTLS`（客户端证书，**hostname 严格不覆盖**）。
- 缓存安全门：默认 `no-store`；Basic Auth 在场则**一切**强制 `no-store`；白名单仅 `/global/health` `/agent` `/command` 且精确匹配（`CacheControlInterceptor.kt:33-45`、`HttpHeaders.kt:53-57`）。

### 4.5 架构优势

1. **关注点分离清晰**：传输（http 包）/ 协议（api 包）/ 门面（repository）/ 编排（controller）/ 视图（ui）分层稳定。
2. **slim/legacy 双模收敛优雅**：单一 `OpenCodeRepository.configure()` 在 `completeSlimReconfigure` 后选束 source（`architecture.md:84`），业务代码 `rg isSlimMode` 在 ui/service/di = 0（`architecture.md:104`）。
3. **SSE 鲁棒性工程化**：epoch / 双通道 / dirty-reconcile / no-replay 契约 / resync 触发均有单测锁定（`SSEClientTest`、`ServiceSseConnectionOwnerResyncTest`、`SlimSseReducerTest`）。
4. **门面冻结纪律**：`T3RepositoryExtractFreezeTest` 反射锁 ~40 公共方法签名，防止重构破坏上游。

### 4.6 架构债务（LOC 是症状，不是拆分判据）

> **重要声明**：下列高 LOC 文件**不构成「必须拆」的依据**。许多行数来自详尽的设计注释（本项目注释密度高、含决策溯源）和受 freeze 测试约束的不可分割路径。拆分必须基于**职责内聚**判断，并先读 freeze 测试。LOC 仅用于标记「值得审计职责」。

| 文件 | LOC | 债务观察（需职责审计，非必拆） |
|---|---|---|
| `OpenCodeRepository.kt` | 3004 | 公共 API 确是 ~40 个 1-line 委托（freeze 锁定）；但 `SlimGetRepository` v0.13.5 删除后折回 ~17 处 `runSuspendCatching{api.xxx()}` 内联（`architecture.md:42`），且 `checkHealth`/`coldStartSlimSync`/`requireSlimTokenCurrent` 等「不可二分路径」留门面（`architecture.md:136`）。**问题在 doc「薄门面」描述与现实的 gap，未必在代码本身。** |
| `SessionSyncCoordinator.kt` | 2029 | fold 主路径 + slim resync + cold-start snapshot 折叠 + token threading 同处。职责链清晰但长，resync 分支多。**拆分方向：把 slim resync / cold-start snapshot 抽到独立 reconciler（已有 `SlimSessionReconciler` 1355 行，需审计两者边界是否已干净）。** |
| `ChatMessageContent.kt` | 1463 | 消息渲染含工具卡/Patch/Todo/marker 多分支。属于「渲染复杂度」而非「逻辑耦合」。 |
| `ChatScaffold.kt` | 1210 | 脚手架 + 多个 `LaunchedEffect`（staleNotice、UiEvent 等）。effect 集中点是重组风险来源（见 §6.1）。 |
| `AppCore` 954 + `AppCoreOrchestration` 1017 | — | dispatch + 编排。两者边界（dispatch vs effect 编排）需确认是否已干净切分，避免「改一处要动两文件」。 |

---

## 5. 按维度评价（10 分制）

> 评分基于静态审计，**性能分含推断成分，需 benchmark 校准**。评级原则：成熟但复杂，不夸大。

| 维度 | 分 | 说明 |
|---|---|---|
| **性能** | 6/10 | 静态未见明显主线程违规（FilePreview decode 在 `Dispatchers.Default`，`FilePreviewPane.kt:119`）；但**无 baseline profile / macrobenchmark / allocation 追踪**，每 token 一次 JSON parse（`SSEClient.kt:358`）是潜在分配热点却无实测。扣分主因是「无数据可证」，不是「已知慢」。 |
| **易用性** | 6/10 | 功能完整、平板适配好；但首次连接缺引导、URL 无 inline 校验（`HostProfileEditorDialog.kt:517-524`）、错误分类粗。mTLS/TOFU 门槛高（有 guide 但非技术用户难上手）。 |
| **UI（视觉/规范）** | 7/10 | 共享原语（Dimens/AppBottomSheet/AppConfirmDialog/AppFormDialog/AppSectionHeader）总体已建立（34 文件用 Dimens）；但 `ChangesPane`/`MetadataMarker`/`ToolCallFoldBar` 仍有 26/12/9 处 dp 字面量；10 处 raw `AlertDialog` 需按三层规则逐例评估。 |
| **UX（体验）** | 7/10 | staleNotice banner 已存在且 identity-gated（`ChatScaffold.kt:659-671`）；但 reconnecting/in-flight 状态可见性不足，长会话恢复时用户缺「正在追回历史」的明确反馈。 |
| **架构** | 8/10 | 分层稳、双模收敛优雅、SSE 鲁棒性强；扣分在高 LOC 集中 + 门面/编排边界随迭代模糊（freeze 约束导致内联回流）。 |
| **可维护性** | 6/10 | 注释/决策溯源极好（这是大加分项）；但无 detekt/ktlint、门面描述与代码 gap、关键路径测试零散，新人上手曲线陡。 |
| **测试 / 工程** | 7/10 | 单测量大（258 文件/10 万行）、Kover 门禁有；但**门禁不一致**（release 弱于 CI）、Compose UI/androidTest 关键路径不系统、无静态检查、configuration-cache 被 ProcessBuilder 阻塞。 |
| **安全 / 鲁棒性** | 8.5/10 | TOFU/mTLS/epoch/双通道/no-store 默认/Basic-Auth 强制 no-store 全部正向；扣分仅在 debug 构建日志含 URL（release 已关，`OkHttpClientFactory.kt:159-164`）、FGS/Doze 未做 OEM 矩阵验证。**无已确认漏洞。** |
| **文档** | 6/10 | docs/specs 体系完整、决策溯源丰富；但事实漂移（SDK）、死链（`docs/current/...`）、architecture「薄门面」描述过时。文档「量」好，「准」需修。 |

**综合**：一个**安全扎实、工程纪律强、但复杂度高且缺性能基线**的成熟项目。当前最适合的改进方向是「补观测 + 修文档 + 统一门禁 + 渐进拆分」，而非大重构。

---

## 6. 详细发现（带仓库相对路径）

### 6.1 性能

| # | 发现 | 证据 | 性质 |
|---|---|---|---|
| P-1 | **每 SSE token 一次 JSON parse 是潜在分配热点**，但无实测。`parseSseEvent` 对每帧 `json.parseToJsonElement` + 多路判断（`SSEClient.kt:357-395`），高频 token 流下 `JsonObject`/`JsonPrimitive` 短命对象密集。 | `data/api/SSEClient.kt:357-395` | **需 profiling 证实**。先测再优化，禁止盲改。 |
| P-2 | **无 Baseline Profile / Macrobenchmark / allocation 追踪**。`gradle/`、`app/build.gradle.kts`、`settings.gradle.kts` 均无 `androidx.benchmark` / `baselineprofile` 引用。 | 实测 grep 全仓无命中 | 阻碍所有性能讨论从「推断」到「数据」。 |
| P-3 | **巨型 Composable 重组边界风险**：`ChatScaffold`（1210 行）集中多个 `LaunchedEffect`（staleNotice `:659-671`、UiEvent 等），`ChatMessageContent`（1463 行）渲染分支多。若 state 切片粒度过粗，单 token delta 可能触发大范围重组。 | `ui/chat/ChatScaffold.kt:659-671` | 需 Compose 重组审计（Layout Inspector / `Modifier.composed` 统计），非 LOC 问题。 |
| P-4 | **启动 `onCreate` 基本干净**：未见主线程同步 IO / 重型初始化的明显违规（静态审计）。 | `MainActivity`（未细列） | 正向。仍建议冷启动基线测量。 |

> **明确不列入性能问题（误报澄清）**：FilePreview bitmap decode **已在 `Dispatchers.Default`**（`FilePreviewPane.kt:107,119`），不是主线程问题。

### 6.2 易用性 / UX

| # | 发现 | 证据 |
|---|---|---|
| U-1 | **首次连接缺最小引导**：Settings 表单字段齐但无「3 步上手」提示，新用户不知先填什么。 | `ui/settings/HostProfileEditorDialog.kt` |
| U-2 | **URL 字段无 inline 格式校验**：`OutlinedTextField` 无 `isError`/`supportingText` 做 `http(s)://host:port` 格式提示，错误只在 Test 连接后回显（`HostProfileEditorDialog.kt:517-524`，结果小字 `:724-730`）。 | 同上 |
| U-3 | **非空聊天 SSE stale/reconnecting 提示不足**：staleNotice banner 已存在且 identity-gated（`ChatScaffold.kt:659-671`，good），但 reconnecting（重连中）、in-flight（追回历史中）等中间态的可见性不足，用户在弱网下可能长时间面对静止界面。 | `ui/chat/ChatScaffold.kt:659-671` |
| U-4 | **错误分类可更明确**：401/403/URL 格式错误/证书错误目前回显较粗，可针对分类给可操作建议（如 401→检查 Basic Auth，格式错→示例 URL）。 | `HostProfileEditorDialog.kt:724-730` |
| U-5 | **mTLS/TOFU 门槛高**：有 `docs/specs/mtls-setup-guide.md`，但非技术用户难独立完成 PKCS12 配置。属合理复杂度，非缺陷。 | docs |
| U-6 | 🔴 **问题弹窗（QuestionCardView）展开态偏下 + 输入法可能遮挡按钮**。**根因**：`StatusSlot.kt:368-374` 的自定义 layout 对所有状态分支统一施加 `gap = placeable.height / 2` 的向下偏移（为让状态胶囊在 TopAppBar 下「呼吸」）。折叠态（CollapsedQuestionPill ~44dp）偏移 22dp，视觉正常贴顶；**展开态（ExpandedQuestionContent 可能 300–500dp）偏移 150–250dp，卡片顶部被推到屏幕中下部**。这解释了用户观察到的「折叠时顶端对齐、展开时偏下」。再叠加键盘弹出（`AndroidManifest.xml:35` 已设 `adjustResize`），展开卡片被下推后底部按钮行可能落入键盘区域被遮挡，用户无法点确定提交答案——属影响核心交互的功能性缺陷。**注**：QuestionCardView 内部已有正确结构（`BoxWithConstraints` + Card `heightIn(max=maxHeight)` + 内容区 `weight(1f,fill=false).verticalScroll` + Header/Action buttons 留外层，`QuestionCardView.kt:257-319,469-479`），滚动与按钮分离设计本身是对的；问题出在容器层的 `height/2` 偏移把整个卡片推离顶部。Permission card（`ChatPermissionCard`）经同一 AnimatedContent 共享同一 layout，同样受影响。 | `ui/chat/StatusSlot.kt:368-374`、`ui/chat/QuestionCardView.kt:257-319,469-479`、`app/src/main/AndroidManifest.xml:35` |

### 6.3 UI（规范一致性）

| # | 发现 | 证据 |
|---|---|---|
| I-1 | **共享原语总体已建立**：`ui/theme/` 提供 `AppBottomSheet`/`AppConfirmDialog`/`AppFormDialog`/`AppSectionHeader`/`PickerTrailingCheck`/`Dimens`，34 文件引用 Dimens。 | grep `Dimens.` |
| I-2 | **残余 dp 字面量**：`ChangesPane.kt`(26)、`MetadataMarker.kt`(12)、`ToolCallFoldBar.kt`(9) 仍有 `N.dp`。需逐例迁移到 `Dimens`（遵循 `ui-style-spec.md`）。 | grep `\.dp` |
| I-3 | **~10 处 raw `AlertDialog`**：需按三层规则（A=DropdownMenu / B=AppBottomSheet / C=AlertDialog family）**逐例评估**，禁止机械全替换为 `AppConfirmDialog`——有些可能本就是合理的 C 层阻塞确认。 | grep `material3.AlertDialog` |
| I-4 | **残余硬编码用户文案**：lint 已把 `HardcodedText`/`MissingTranslation` 升为 error（`app/build.gradle.kts:177`），但历史残留 + 未来回归需持续守护。 | `app/build.gradle.kts:171-178` |
| I-5 | **语义/触控目标**：静态未见明显违规，但需 accessibility 审计（触控目标 ≥48dp、contentDescription 覆盖）。 | — |

### 6.4 架构 / 维护

| # | 发现 | 证据 |
|---|---|---|
| A-1 | **长 SSE 链路职责清晰，不能简单压平**：`SSEClient → ServiceSseConnectionOwner → SessionStreamingService → SseEventBridge → AppCore → SessionSyncCoordinator.fold` 每环都有明确不变量（epoch / 双通道 / token threading）。**压平会破坏不变量隔离，禁止。** | §4.3 流图 |
| A-2 | **God-ish facade/controller**：`OpenCodeRepository`(3004)、`SessionSyncCoordinator`(2029)、`AppCore`(954)+`AppCoreOrchestration`(1017)。**LOC 非拆分理由**，需基于职责内聚判断 + 先读 freeze 测试（`T3RepositoryExtractFreezeTest`）。 | §4.6 |
| A-3 | **职责拆分方向（渐进、可逆）**：① slim resync/cold-start snapshot 从 SSC 抽到 `SlimSessionReconciler`（审计两者边界）。② `AppCore` dispatch vs `AppCoreOrchestration` effect 编排边界确认。**前置：先建回归测试再动。** | — |
| A-4 | **异常吞噬 / TODO 需治理**：项目大量 `runSuspendCatching`（门面设计），但需审计 catch 块是否充分记录/上报，避免静默吞错。 | grep `catch`/`TODO`（未全列） |

### 6.5 工程化

| # | 发现 | 证据 |
|---|---|---|
| E-1 | **Gradle `ProcessBuilder("git",…)` 阻止 configuration-cache**：`app/build.gradle.kts:37-45` 配置期 fork git 进程派生版本号，与 CC 不兼容。AGENTS.md 已记录待迁 `providers.exec`/`ValueSource`。 | `app/build.gradle.kts:37-45`、AGENTS.md |
| E-2 | **release / CI Kover 门禁不一致**：`release.sh:100` 跑 `check.sh --lint`（compile+test+lint，**无 Kover**）；`check.sh` 仅 `--full` 才跑 `koverVerify`（`check.sh:29-36`）；但 CI `integration-check.yml` 跑 `koverVerify`（`:40-43`）。**本地发版门禁弱于 CI**——可能放行覆盖率回归。 | `scripts/release.sh:98-101`、`scripts/check.sh:29-36`、`.gitea/workflows/integration-check.yml:40-43` |
| E-3 | **detekt / ktlint 缺失**：全仓无 detekt/ktlint 配置。code style 仅靠 lint + 人工 review。 | grep 全仓无命中 |
| E-4 | **依赖升级必须单独验证**：AGP 9.1.0 / Kotlin 2.2.10 / KSP 2.3.6 较新，升级有破坏性风险，需独立 PR + 全量 check.sh --full + 模拟器回归。 | `gradle/libs.versions.toml` |
| E-5 | **R8 full mode 仅实验**：release 用 `proguard-android-optimize.txt`，但未见 `android.enableR8.fullMode=true` 显式配置的权威记录（需确认）。full mode 有更强优化但有反射/序列化风险，需测后再开。 | `app/build.gradle.kts:129-136` |

### 6.6 安全 / 网络（多为正向）

| # | 发现 | 证据 | 性质 |
|---|---|---|---|
| S-1 | ✅ **TOFU SPKI 绑 host:port**：`TofuPinned` 的 `hostnameVerifier{_,_->true}` 是**设计意图**——pin 即身份，自签名证书 SAN 常不匹配故放行 hostname，安全由 `PinningTrustManager` 的 SPKI 比对保证（grill Q4）。**不得定性为漏洞。** | `SslConfig.kt:254-259` | 正向 |
| S-2 | ✅ **mTLS hostname 严格**：`MutualTLS` 不覆盖 hostnameVerifier，OkHttp 严格默认，stunnel 证书 SAN 必须匹配。 | `SslConfig.kt:250-253` | 正向 |
| S-3 | ✅ **缓存安全门**：默认 `no-store`；Basic Auth 在场强制一切 `no-store`；白名单仅 3 个全局只读端点 + 精确匹配。**绝不缓存 session/用户敏感数据。** `/config/providers` 故意排除（含 API key）。 | `CacheControlInterceptor.kt:33-45`、`HttpHeaders.kt:53-66` | 正向 |
| S-4 | ✅ **连接 epoch 身份校验 + §11 双通道 + backpressure + bounded retry**：stale host 帧双重校验丢弃；control 挂起保序 / delta 溢出转 dirty。 | `SseEventBridge.kt:195-220` | 正向 |
| S-5 | ⚠️ **debug 构建日志暴露 URL**：DEBUG=BASIC（含 URL/session/文件路径），release=NONE（已关）。**仅 debug 面暴露**，但开发机 logcat 仍有信息面。可评估 debug 也脱敏路径段。 | `OkHttpClientFactory.kt:159-164` | 低优 |
| S-6 | ⚠️ **FGS / Doze 需 OEM 矩阵验证**：manifest 声明 `FOREGROUND_SERVICE_DATA_SYNC` + `foregroundServiceType=dataSync`（`AndroidManifest.xml:11-61`），但**未发现 `requestIgnoreBatteryOptimizations` / Doze 白名单申请代码**（静态）。后台长连在不同 OEM 省电策略下表现需实测。 | `AndroidManifest.xml`、grep 无 Doze 代码 | 需验证 |
| S-7 | ⚠️ **`/command` 缓存契约需审计**：`/command` 在 `CACHEABLE_PATHS` 白名单（`HttpHeaders.kt:56`）。需确认该端点响应确实全局只读、无 per-user/per-workdir 数据、且部署不会在 Basic Auth 下使用（此时已被强制 no-store 覆盖）。 | `HttpHeaders.kt:53-57` | 审计项 |

### 6.7 文档

| # | 发现 | 证据 |
|---|---|---|
| D-1 | **SDK 事实漂移**：`README.md:17` 写「API 26」、`docs/specs/build-apk.md:27` 写「35 / 26 / 34」，实际 minSdk=34。 | `README.md:15-19`、`docs/specs/build-apk.md:27`、`app/build.gradle.kts:65-70` |
| D-2 | **死链**：`docs/remaining_question.md:134` 引用 `docs/current/2026-07-25-integrated-remaining-work-plan.md`，但 **`docs/current/` 目录不存在**。 | `docs/remaining_question.md:132-137` |
| D-3 | **architecture 对 Repository「薄门面」描述过时**：doc 称 OCR「~40 公共方法 1-line 委托」的冻结门面，但文件 3004 行 + `SlimGetRepository` 折回内联 ~17 处 + 不可二分路径（checkHealth/coldStartSlimSync）留门面。「薄门面」headline 已不反映现实全貌。 | `docs/specs/architecture.md:38,42,136` vs `OpenCodeRepository.kt`(3004) |

---

## 7. 优先级路线图

> 每项固定模板：问题/证据 → 目标 → 实施步骤 → 验收标准 → 依赖与风险 → 建议 lane。

### P0 — 无已确认项

**当前无已确认 P0 灾难项。** 升级条件（任一触发即升 P0）：
- 缓存被证实跨用户/跨 workdir 泄漏（违反 `CacheControlInterceptor` 不变量）。
- epoch 校验被证实失效导致 reconfigure 期串会话。
- TOFU/mTLS 被证实存在可绕过的身份伪造路径。
- 任何 R8/序列化导致的数据损坏或 crash 影响 release 用户。

发现上述任一，立即停手其他工作，开 P0 issue + 复现 + 热修。

---

### P1（用户信任 / CI / 文档 / 可观测基线）

#### P1-1 聊天持久 reconnect / stale / reconnecting banner 强化
- **问题/证据**：staleNotice banner 已 gated（`ChatScaffold.kt:659-671`），但 reconnecting/in-flight 中间态可见性不足（U-3）。
- **目标**：弱网下用户始终知道「连接在干什么」（已连/重连中/追回历史中/stale 数据）。
- **实施步骤**：
  1. 盘点 `ConnectionState` 现有字段（connected/reconnecting/stale/cold-starting），补齐缺失态。
  2. 在 Chat 顶栏（或持久 banner）渲染当前态 + 上次更新时间。
  3. stale 数据加视觉降级（灰显/角标）。
  4. 补 Compose UI 测试覆盖各态切换。
- **验收标准**：断网→重连全流程，UI 始终有明确态提示；无静止无反馈窗口 >2s（可观测）。
- **依赖与风险**：依赖 `SharedStateStore.connectionFlow` 字段完备；勿引入新主线程工作。
- **建议 lane**：designer（视觉/交互）+ fixer（实现）。

#### P1-2 URL inline 格式校验 + 首次连接引导
- **问题/证据**：URL 字段无 inline 校验、缺引导（U-1, U-2）。
- **目标**：用户填错 URL 即时反馈；首次进入有 3 步引导。
- **实施步骤**：
  1. `HostProfileEditorDialog` URL 字段加 `isError` + `supportingText`（格式 `http(s)://host[:port]`，复用现有 URL 解析 util）。
  2. 首次启动（无 host profile）显示轻量引导（AppFormDialog 或内嵌空态卡）。
  3. 错误分类回显（U-4）：401/403/格式/证书各给可操作文案。
- **验收标准**：填入 `ftp://x` 即时标红 + 提示；首次启动有引导；401 提示检查 Basic Auth。
- **依赖与风险**：UI 改动需走 `ui-style-spec.md` 三层规则；i18n 同步 en/zh（lint MissingTranslation 已 error）。
- **建议 lane**：designer + fixer。

#### P1-3 统一 release / integration Kover 门禁
- **问题/证据**：`release.sh` 跑 `--lint`（无 Kover），CI 跑 `koverVerify`（E-2）。
- **目标**：本地发版门禁 ≥ CI，防止覆盖率回归放行。
- **实施步骤**：
  1. `release.sh:100` 改 `check.sh --full`（或显式加 `koverVerify` 步骤）。
  2. 确认 `check.sh --full` 含 lint + kover（已含，`check.sh:29-36`）。
  3. 文档同步 `.opencode/policies/build-signing.md`。
- **验收标准**：`release.sh` 失败当且仅当 CI 会失败的覆盖率条件；本地能复现 CI 门禁。
- **依赖与风险**：`--full` 略慢；可选 `OC_RELEASE_FULL=1` 开关。仅改脚本 + 文档。
- **建议 lane**：fixer。

#### P1-4 修正文档事实源与死链
- **问题/证据**：SDK 漂移（D-1）、死链（D-2）、「薄门面」描述（D-3）。
- **目标**：文档与代码事实一致。
- **实施步骤**：
  1. `README.md:17` API 26 → 34（Android 14+）。
  2. `docs/specs/build-apk.md:27` 表格 minSdk 26 → 34。
  3. `docs/remaining_question.md:134` 删除死链或重建 `docs/current/` 目标文件。
  4. `docs/specs/architecture.md` 补注：OCR 公共 API 是冻结薄门面，但文件含内联领域逻辑（token threading / health 分叉 / 折回的 SlimGet），LOC 反映注释密度 + 不可二分路径。
- **验收标准**：grep 文档无「API 26」；死链消除；architecture 不再误导。
- **依赖与风险**：纯文档，零代码风险。
- **建议 lane**：fixer（或 librarian）。

#### P1-5 收紧 DEBUG 日志暴露面
- **问题/证据**：DEBUG=BASIC 含 URL/路径（S-5）。
- **目标**：开发机 logcat 信息面收敛（保留连通性诊断）。
- **实施步骤**：
  1. 评估 `HttpLoggingInterceptor` 自定义 `HttpLoggingInterceptor.Logger` 脱敏 path/query。
  2. 或降级为 NONE + 在关键失败点显式打 sanitized 摘要。
- **验收标准**：DEBUG 构建日志不含完整 session/file 路径；连通性诊断仍可用。
- **依赖与风险**：勿破坏排障能力；需兼顾。
- **建议 lane**：fixer。

#### P1-6 显式保留 SSE 协议回归 CI（防回归护栏）
- **问题/证据**：slim/legacy 动态选（`SSEClient.kt:138`）、event:resync、no-replay、Last-Event-ID 永不发送等契约已有单测，但需确认在 CI 主路径常跑且门禁。
- **目标**：SSE 契约回归不可静默破坏。
- **实施步骤**：
  1. 盘点 `SSEClientTest` / `ServiceSseConnectionOwnerResyncTest` / `SlimSseReducerTest` 是否在 `testDebugUnitTest`（CI 已跑）。
  2. 补齐缺失契约（如 resync reason 解析、双通道溢出→dirty）。
  3. 在 PR 模板加「改 SSE 需过这些测试」提示。
- **验收标准**：SSE 契约破坏即 CI 红；契约清单可查。
- **依赖与风险**：仅加测试 + 文档。
- **建议 lane**：fixer（或 oracle 契约梳理）。

#### P1-7 FGS / OEM 后台保活矩阵验证
- **问题/证据**：未发现 Doze 白名单申请（S-6），FGS dataSync 在不同 OEM 省电策略下行为未知。
- **目标**：明确「哪些 OEM/省电策略下后台 SSE 会断」，决定是否需引导用户关电池优化。
- **实施步骤**：
  1. 真机/模拟器矩阵：小米/华为/三星/Pixel + Doze/省电/后台限制。
  2. 记录断连行为 + 恢复行为。
  3. 据结果决定是否加 `requestIgnoreBatteryOptimizations` 引导（**先验证再申请**，不盲申请）。
- **验收标准**：产出 OEM 矩阵表；决策记录入库。
- **依赖与风险**：需多设备；遵守「真机测试需用户明确要求」硬规则——只读诊断可用，主动保活测试需用户批准。
- **建议 lane**：explorer（实测）+ oracle（决策）。

#### P1-8 token JSON parse profiling（可放 P1）
- **问题/证据**：每帧 parse 是潜在热点（P-1），无实测。
- **目标**：用数据取代推断，决定是否优化。
- **实施步骤**：
  1. 接入 macrobenchmark（见 P2，但可先用简单 allocation 追踪）。
  2. 高频 token 流下记录 parse 分配量/GC。
  3. 若证实热点，评估流式/增量 parse 或对象池。
- **验收标准**：产出「token 吞吐 vs 分配/GC」数据；有/无优化对比。
- **依赖与风险**：依赖 benchmark 基础设施（P2-3）；可先用 profiling 工具速测。
- **建议 lane**：explorer（profiling）。

#### P1-9 修复问题弹窗（QuestionCardView）展开态位置 + 输入法遮挡按钮
- **问题/证据**：U-6。`StatusSlot.kt:368-374` 的 `gap = placeable.height / 2` 自适应偏移对所有状态分支生效，展开态大卡片被过度下推；叠加键盘弹出（adjustResize）可能把底部按钮行推入键盘区域。**折叠态正常、展开态偏下**是直接观察，根因单一。
- **目标**：① 展开态与折叠态顶部对齐到同一 y（`Alignment.TopCenter`，无比例偏移）；② 内容超长时卡片内部滚动，Header/Action buttons 始终可见，键盘弹出不被遮挡。
- **实施步骤**：
  1. **重构 `StatusSlot.kt:368-374` 的 layout offset**：移除 `gap = placeable.height / 2` 的自适应偏移。改用**固定小 gap**（如 `Dimens.spacing1`/`spacing2`，约 4–8dp）作为所有状态分支统一的顶部留白；或让胶囊类（Thinking/Connecting/Compacting/Retry）自行用内部 padding 实现「呼吸感」，大 overlay（Question/Permission）纯 `TopCenter` 顶部对齐。**禁止继续用 `height/2`——大尺寸内容必偏移过度。**
  2. **QuestionCardView 容器加 `imePadding()`**：在 `QuestionCardView.kt:257` 的 `BoxWithConstraints` modifier 链加 `Modifier.imePadding()`（或在外层 StatusSlot 调用处加），确保键盘弹出时 Card 可用高度自动收缩，`heightIn(max=maxHeight)` 的 maxHeight 跟随 ime insets 变小，内部 `weight(1f,fill=false).verticalScroll` 滚动区接管，按钮行（`:582-630`）恒在键盘上方。Permission card 同理。
  3. **验证 QuestionCardView 现有滚动结构**：确认 Header（`:408-445`）+ Action buttons（`:582-630`）在 Column 外层、内容区（`:475-579`）独占 `weight(1f,fill=false).verticalScroll`——结构已正确，步骤 1+2 完成后即满足「内容长则内部滚动、按钮不遮挡」。
  4. 走 `ui-style-spec.md` 复核（overlay 对齐/间距用 Dimens，无散落 dp）。
  5. 模拟器回归：短问题（不滚）/ 长问题（滚动）/ 多 tab 翻页 / 键盘弹出点确定 全覆盖。
- **验收标准**：
  - 展开态 Card 顶部 y 坐标 == 折叠态 Pill 顶部 y 坐标（同一对齐基线，误差 ≤1dp）。
  - 展开态长内容（问题 + 选项 + 描述 > 屏高）时内容区滚动，Header 收起按钮 + 底部 Submit/Reject/Back 按钮全程可见、可点。
  - 键盘弹出时按钮行不被遮挡，`imePadding` 生效（Card 区域收缩到键盘上方）。
  - 短问题不产生多余空白（`weight(1f, fill=false)` 保持「短内容取自然高」）。
  - Permission card 同样不再被过度下推。
- **依赖与风险**：UI 改动，需走三层规则；胶囊类若改内部 padding 实现呼吸感需 designer 评审视觉；`imePadding` 与 `BoxWithConstraints` 组合需测 max height 收缩行为（Compose 1.7 已稳定）。改 `StatusSlot.kt` 是共享路径，需确保其它状态分支（Retry/Compacting/Connecting/Thinking）视觉不回归。
- **建议 lane**：designer（对齐/视觉评审）+ fixer（layout/imePadding 实现）。

---

### P2（可维护性 / 渐进优化 / 测后优化）

#### P2-1 基于职责渐进拆分 SessionSyncCoordinator / AppCore / Repository
- **问题/证据**：A-2/A-3。**LOC 非理由，职责内聚 + freeze 测试才是。**
- **目标**：降低单文件认知负荷，不改公共契约。
- **实施步骤**：
  1. 先读 `T3RepositoryExtractFreezeTest`（OCR）、SSC 现有测试，画职责地图。
  2. 候选：slim resync/cold-start snapshot 从 SSC 抽到 `SlimSessionReconciler`（审计边界）。
  3. 每抽一块先建回归测试 → 抽 → 测。
- **验收标准**：freeze 测试不变；行为回归测试全绿；公共 FQN 不变。
- **依赖与风险**：高风险，必须小步 + 每步可回滚。
- **建议 lane**：fixer（需 oracle 评审职责边界）。

#### P2-2 仅按 profiling 优化 reducer
- **问题/证据**：P-1。
- **目标**：数据驱动的 reducer/parse 优化。
- **实施步骤**：profiling 定位真热点 → 针对性优化 → benchmark 对比。
- **验收标准**：优化前后有可复现 benchmark 数据；无行为回归。
- **依赖与风险**：禁盲改；依赖 P1-8/P2-3。
- **建议 lane**：explorer + fixer。

#### P2-3 Baseline Profile + Macrobenchmark 基础设施
- **问题/证据**：P-2。
- **目标**：建立性能基线，所有后续优化有数据。
- **实施步骤**：
  1. 加 `:benchmark` module（`androidx.benchmark:macro-junit4` + `baselineprofile`）。
  2. 写冷启动 + 滚动聊天 macrobenchmark。
  3. 生成 baseline profile 接入 release。
  4. 记录基线值入库（§11）。
- **验收标准**：CI 可跑 benchmark；baseline profile 生效；基线表有数。
- **依赖与风险**：需模拟器/真机；遵守设备安全规则。
- **建议 lane**：explorer + fixer。

#### P2-4 R8 full mode 实验
- **问题/证据**：E-5。
- **目标**：评估 full mode 收益（包体/启动）与风险（反射/序列化）。
- **实施步骤**：实验性开启 → 全量 test + assembleRelease + 模拟器回归 → 对比。
- **验收标准**：无 crash/序列化失败；包体/启动数据对比。
- **依赖与风险**：kotlinx.serialization 对 R8 full 敏感，需测。
- **建议 lane**：explorer + fixer。

#### P2-5 关键 Compose 路由 / 重组测试
- **问题/证据**：测试覆盖零散（§5）。
- **目标**：会话切换/stale/resync/恢复路径有 UI 测试。
- **实施步骤**：补 `createAndroidComposeRule` 测试覆盖关键路由（仅模拟器）。
- **验收标准**：关键路径有回归测试。
- **依赖与风险**：androidTest 需模拟器 + .env。
- **建议 lane**：fixer。

#### P2-6 轻量静态检查（detekt 或 ktlint）
- **问题/证据**：E-3。
- **目标**：code style / 常见坏味自动捕获。
- **实施步骤**：选 detekt（或 ktlint）→ 渐进启用（先 warn 不 fail）→ 逐步收紧。
- **验收标准**：CI 跑静态检查；不破坏现有 build。
- **依赖与风险**：现有代码可能有大量初代告警，需渐进。
- **建议 lane**：fixer。

#### P2-7 `/command` 缓存契约审计
- **问题/证据**：S-7。
- **目标**：确认 `/command` 在白名单的安全性。
- **实施步骤**：核对端点响应是否全局只读、无 per-user 数据；确认 Basic Auth 下被 no-store 覆盖；记录审计结论。
- **验收标准**：审计记录入库；若不安全则移出白名单 + bump cache purge marker。
- **依赖与风险**：需核对服务端契约。
- **建议 lane**：oracle / librarian。

---

### P3（润色 / 卫生）

| # | 项 | lane |
|---|---|---|
| P3-1 | 残余用户文案 i18n 收尾（lint 已 error 守护，清存量） | fixer |
| P3-2 | 随手迁移 dp 字面量到 Dimens（`ChangesPane`/`MetadataMarker`/`ToolCallFoldBar`） | designer + fixer |
| P3-3 | raw AlertDialog 逐例评估迁移（**禁机械替换**） | designer + fixer |
| P3-4 | 异常吞噬审计（catch 块记录/上报充分性） | fixer |
| P3-5 | 依赖升级卫生（独立 PR + 全量验证） | fixer |
| P3-6 | TODO 清理与跟踪 | fixer |
| P3-7 | accessibility 审计（触控目标/contentDescription） | designer |

---

## 8. 实施顺序与波次

| Wave | 内容 | 并行 lane | 写文件冲突边界 |
|---|---|---|---|
| **Wave 0：观测/文档基线** | P1-4（文档修正）、P1-8（profiling 速测）、P1-7（OEM 矩阵启动）、P2-3（benchmark 基建启动） | librarian(文档) ∥ explorer(profiling/benchmark/OEM) | 文档 lane 只碰 `docs/`、`README.md`；explorer 只加 `:benchmark` module + 跑测量，**不碰主源码**。可全并行。 |
| **Wave 1：用户信任与 CI** | P1-1（banner）、P1-2（URL/引导）、P1-3（门禁统一）、P1-5（日志）、P1-6（SSE 回归） | designer+fixer(UI/UX 串行) ∥ fixer(scripts/CI) ∥ fixer(SSE 测试) | UI lane 改 `ui/chat`+`ui/settings`；scripts lane 改 `scripts/`+`.gitea/`+`.opencode/policies/`；SSE lane 改 `app/src/test`。**三者写文件无重叠**，可并行。UI 内部 banner 与 URL 引导同改 settings/chat，需串行或同 lane。 |
| **Wave 2：可维护性** | P2-1（渐进拆分）、P2-5（Compose 测试）、P2-6（静态检查）、P2-7（缓存审计） | fixer(拆分) ∥ designer/test(Compose 测试) ∥ oracle(缓存审计) | 拆分改 `ui/controller`+`data/repository`；Compose 测试改 `app/src/androidTest`；审计只产文档。**拆分与 Compose 测试若同时改 controller 公共 API 会冲突**——先定 API 再开测试。 |
| **Wave 3：实测后优化** | P2-2（reducer 优化）、P2-4（R8 full）、P1-8 深化、P3 系列 | explorer+fixer(优化) ∥ explorer(R8) ∥ fixer(P3) | 优化改 SSE/reducer；R8 改 gradle/proguard；P3 改 ui 散点。R8 与优化需同 build 验证，建议串行收尾。 |

**冲突原则**：同 lane 内串行；跨 lane 只在「公共 API/共享 build 配置」处协调。每个 Wave 完成后跑 `check.sh --full` + 模拟器回归作为 gate。

---

## 9. Agent 执行指南

后续 agent 接到本文派发的任务时，**开始前必读**：

1. `AGENTS.md`（入口索引 + 硬规则）
2. `docs/specs/ui-style-spec.md`（**任何 overlay/dialog/menu 改动 MANDATORY**）
3. `docs/specs/build-apk.md`（构建/签名；**注意 SDK 文档漂移，以 `app/build.gradle.kts` 为准**）
4. `docs/specs/sse-client-spec.md`（SSE 契约）
5. `docs/specs/architecture.md`（分层 + slim/legacy；**注意「薄门面」描述过时**）
6. 本报告对应章节 + §10 误报澄清

**执行硬规则**：
- 任何 Kotlin/资源改动后**必须** `./scripts/check.sh` 通过（编译 + `testDebugUnitTest`）；发版前 `--full`。
- UI 插桩测试 / 安装**仅用模拟器**，且遵守 `./scripts/emulator.sh status` → `start` → 用完 `stop`。
- **不得在物理手机跑 connectedDebugAndroidTest / 装 debug 构建**，除非用户明确要求。
- 发版**只能** `./scripts/release.sh <patch|minor|major>`，禁止手拼 gradle 命令、禁止手改版本号。
- 每个任务**最小改动 + 先测试/先建回归**；性能任务**必须保存 benchmark 基线证据**（截图/数据文件）入库或附 issue。
- 改 SSE/网络层前确认对应契约测试（P1-6）存在且通过。
- 拆分任务（P2-1）**必先读 freeze 测试**（`T3RepositoryExtractFreezeTest`），不得改公共 FQN。

---

## 10. 不建议做的事 / 误报澄清

> 以下为静态扫描常见误报或过度建议，**明确不做**。后续 agent 不得将这些列为缺陷或高优先项。

| 误报项 | 真相 | 证据 |
|---|---|---|
| ❌ 「minSdk=26 / API 26」 | 实际 **compileSdk=35, minSdk=34, targetSdk=34**。文档写 26 是漂移（修文档，不改 build）。 | `app/build.gradle.kts:65-70` |
| ❌ 「`event:resync` 未支持是缺陷」 | **已支持且有测试**。resync 帧触发 cold-start，reason 解析为遥测。 | `ServiceSseConnectionOwner.kt:689-706`、`ServiceSseConnectionOwnerResyncTest`、`SlimSseReducerTest` |
| ❌ 「slim/legacy SSE 端点需统一」 | **已动态选择**：slim→`/slimapi/events`，legacy→`/global/event`，按 `slimMode` 分派。不是缺陷。 | `SSEClient.kt:138` |
| ❌ 「FilePreview 在主线程 decode bitmap」 | **已在 `Dispatchers.Default`**（`R-02a` 修复）。不是主线程问题。 | `FilePreviewPane.kt:107,119` |
| ❌ 「TOFU `hostnameVerifier=true` 是漏洞」 | **是设计**：SPKI pin 绑 host:port，pin 即身份；自签名 SAN 常不匹配故放行 hostname，安全由 pin 保证（grill Q4）。**不得定性为漏洞。** | `SslConfig.kt:254-259` |
| ❌ 「mTLS hostname 不严」 | **mTLS 严格**：不覆盖 hostnameVerifier，OkHttp 默认 CN/SAN 校验。 | `SslConfig.kt:250-253` |
| ❌ 「缓存会泄漏 session 敏感数据」 | **默认 no-store**；Basic Auth 下一切强制 no-store；白名单仅 3 个全局只读端点 + 精确匹配；`/config/providers` 故意排除（含 key）。**不缓存 session 数据。** | `CacheControlInterceptor.kt:33-45`、`HttpHeaders.kt:53-66` |
| ❌ 「项目没有 Compose UI 测试」 | **有**（7 文件 androidTest 引用 Compose rule），只是关键路径覆盖不系统。 | grep androidTest |
| ❌ 「Basic Auth String 擦除」列为高优先 | 不列高优先（JVM String 不可变，真正的凭据生命周期管理已在 OkHttp 拦截器层）。 | — |
| ❌ 「统一所有锁」列为高优先 | 不列高优先。双监视器锁序（OCR + TofuRepository `@Synchronized`）是**刻意设计**（`architecture.md:114`，rev-4 双监视器锁序防死锁），盲统一会引入死锁。 | `architecture.md:114` |
| ❌ 「立刻上 circuit breaker」列为高优先 | 不列高优先。现有 bounded retry + backpressure + dirty-reconcile 已覆盖主要场景，circuit breaker 需先定义「熔断后做什么」否则无意义。 | `SseEventBridge.kt` §11 |
| ❌ 「直接申请 Doze 白名单」列为高优先 | 不列高优先。**先做 OEM 矩阵验证（P1-7）再决定**，Google Play 对该权限有审核风险，盲申请是反模式。 | — |
| ❌ 「按 LOC 机械拆分大文件」 | **LOC 非拆分理由**。注释密度 + freeze 约束 + 不可二分路径解释了 LOC。拆分基于职责内聚 + 先读 freeze 测试。 | §4.6、`architecture.md:135-136` |

---

## 11. 建议的指标基线表

> **所有「当前值」待测**——本文不编造数据。后续 P1-8/P2-3 产出后回填。

| 指标 | 测量方法 | 目标方向 | 当前值 |
|---|---|---|---|
| 冷启动（到首帧可交互） | macrobenchmark `StartupTiming` / `adb shell am start -W` + perfetto | ↓ | 待测 |
| 首屏（Settings/Chat 渲染完成） | macrobenchmark + Layout Inspector | ↓ | 待测 |
| SSE token 吞吐（tokens/s） | 压测脚本 + 服务端高频流 | ↑ | 待测 |
| token 流分配/GC | Android Studio Profiler allocation tracking / macrobenchmark | ↓ | 待测 |
| 滚动 jank 率（Chat 列表） | macrobenchmark `FrameTiming`（frame deadline miss %） | <5% | 待测 |
| 断网→重连恢复时间（到首帧恢复） | 真机/模拟器脚本 + logcat 时间戳 | ↓ | 待测 |
| release APK 大小 | `assembleRelease` 产物 | ≤ 历史 | 待测（历史：SQLCipher 移除后下降） |
| Kover 覆盖率（Line/Branch） | `check.sh --full` → koverHtmlReport | ≥ 基线 57.9%/55.1% | 57.9%/55.1%（2026-07-06） |
| 首次连接成功率（集成） | `connectedDebugAndroidTest`（模拟器） | >95% | 待测 |
| 关键 Compose 路由测试通过率 | androidTest（模拟器） | 100% | 待测 |

**基线保存约定**：每次 P2-3 benchmark 跑完，数据文件 + 截图存 `.opencode/runs/benchmarks/<YYYY-MM-DD>/`，并在本表回填。

---

## 12. 结论与首批建议开票清单

**结论**：OCDroid 是一个**安全设计扎实、工程纪律强、文档决策溯源优秀**的成熟项目，当前**无 P0 灾难**。主要改进空间在：① 补性能/可观测基线（数据取代推断）；② 修文档事实漂移；③ 统一门禁；④ 强化首次连接 UX 与弱网可见性；⑤ 渐进、职责驱动的可维护性提升。**绝不做大重构或盲优化。**

### 首批建议开票清单（8-12 个可成为 issue）

| # | Issue 标题 | 范围 | 验收标准（简） |
|---|---|---|---|
| 1 | 修文档 SDK 漂移与死链 | `README.md`、`build-apk.md`、`remaining_question.md` | grep 无 API 26；死链消除；architecture 不误导 |
| 2 | 统一 release/CI Kover 门禁 | `scripts/release.sh`、`check.sh`、policies | 本地 release 门禁 ≥ CI |
| 3 | URL inline 校验 + 首次连接引导 | `HostProfileEditorDialog`、空态 | 格式错即时反馈；首次有引导；i18n 同步 |
| 4 | 强化 reconnecting/stale banner | `ConnectionState`、`ChatScaffold`、顶栏 | 弱网全程有态提示；UI 测试覆盖 |
| 5 | 错误分类回显（401/403/格式/证书） | `HostProfileEditorDialog`、VM | 各类错误有可操作文案 |
| 6 | 接入 Macrobenchmark + Baseline Profile | 新 `:benchmark` module | CI 可跑；基线表有数 |
| 7 | token JSON parse profiling | `SSEClient`、benchmark | 分配/GC 数据；优化决策有据 |
| 8 | FGS/OEM 后台保活矩阵 | 真机/模拟器测试 | OEM 矩阵表入库；Doze 决策有据 |
| 9 | SSE 契约回归护栏 | `app/src/test`、PR 模板 | 契约破坏即 CI 红 |
| 10 | `/command` 缓存契约审计 | `HttpHeaders`、审计文档 | 审计结论入库；不安全则移出白名单 |
| 11 | 渐进拆分 SessionSyncCoordinator（slim resync 外提） | `ui/controller` | freeze 测试不变；回归全绿 |
| 12 | detekt/ktlint 渐进启用 | `gradle/`、CI | 静态检查跑通；不破坏 build |
| 13 | 修复问题弹窗展开态位置 + 输入法遮挡按钮 | `ui/chat/StatusSlot.kt`、`QuestionCardView.kt` | 展开态顶部对齐折叠态；长内容内部滚动；键盘不遮挡按钮；Permission 同步修复 |

---

*报告结束。本文件为后续 agent 的权威调研底稿；执行任何任务前请重读对应章节与 §10 误报澄清。*
