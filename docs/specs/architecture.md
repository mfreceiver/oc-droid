# ocdroid 代码框架规范

> 本文件是 ocdroid 代码架构的**长期权威规范**：分层、双 API 变体（legacy/slim）共存策略、
> 核心模式与不变量、包与命名约定。新增 / 重构代码须遵守；规则冲突时以代码现状 + `.opencode/policies/` 为准。
>
> 相关规格（操作细节）：`./build-apk.md`（构建发版）、`./emulator-debug.md`（模拟器）、
> `./ui-style-spec.md`（UI overlay 三层规则，MANDATORY）、`./slim-mode-api-routing.md`（slim 路由契约）、
> `./sse-client-spec.md`（SSE 控制面）、`./mtls-setup-guide.md`（mTLS）。

---

## 1. 项目定位

ocdroid 是 [opencode](https://github.com/) 的 Android 客户端（Kotlin / Jetpack Compose / Hilt / Retrofit / OkHttp）。
长期同时兼容两套服务端 API：

- **标准 API（legacy）**：直连 opencode，`/session`、`/global`、`/file`、`/vcs`、`/question`、`/permission` 等。
- **精简 API（slim / oc-slimapi sidecar）**：经 sidecar 代理的 `/slimapi/…`，带 watermark / routeToken / 聚合信封 / 版本协商。

两套 API **形状相同（域模型复用）、取数方式不同（端点 / 分页 / 状态机 / 协商）**。本规范定义二者如何长期共存而不互相腐蚀。

---

## 2. 包布局

```
app/src/main/java/cn/vectory/ocdroid/
├── MainActivity.kt / OpenCodeApp.kt        # 入口（Application + 单 Activity）
├── data/                                    # 数据层
│   ├── api/                                 #   L1 Wire：Retrofit 接口 + SSE/TokenStream 客户端
│   │   ├── OpenCodeApi.kt                   #     复合接口（legacy 法 + slim 法，见 §5）
│   │   ├── SSEClient.kt / SseLogFilter.kt
│   │   ├── TokenStreamClient.kt
│   │   └── v2/OpenCodeApiV2.kt
│   ├── repository/                          #   L0 传输 + L2 端口 + L3 门面（扁平同包，禁子包）
│   │   ├── http/                            #     L0：OkHttpClientFactory / SSL·TOFU / 拦截器 / SlimapiContract
│   │   ├── OpenCodeRepository.kt            #     L3：冻结门面（~40 公共方法 1-line 委托）
│   │   ├── HostConfig.kt                    #     per-host profile（slim 位等）
│   │   ├── ServerCompatProfile.kt           #     能力读模型（slimConnection + 派生查询）
│   │   ├── SessionSource.kt / MessageSource.kt   #  L2：域端口 + Standard/Slim 双实现
│   │   ├── SlimGetRepository.kt / TofuRepository.kt / ExpandBatchEngine.kt  # 已外提 delegate
│   │   └── SlimSseStateMachine.kt / SlimSseReducer.kt / Slimapi*.kt          # slim 状态机 / 聚合
│   └── model/                               #   共享域模型（Session / MessageWithParts / Slimapi* DTO）
├── di/                                      # Hilt 装配（ControllerModule 等）—— 唯一 mode 选择点之一
├── service/                                 # L4 service：streaming / status / notify（模式盲）
├── ui/                                      # L4 controller + L5 UI（Compose）
│   ├── controller/ (sse/)                   #   SessionSyncCoordinator / coordinators / SSE 分派
│   ├── chat/ sessions/ files/ …             #   Compose 屏幕 + ViewModel
│   └── theme/                               #   共享 UI 原语（AppBottomSheet / AppConfirmDialog / Dimens…）
└── util/                                    # 工具（DebugLog / runSuspendCatching / TrafficLogger / SettingsManager）
```

**约定**：`data/repository/` 一律**扁平同包 `internal`**，**禁子包**（避免 import churn；只有 `http/` 子包是历史既存）。新类型落 `data/repository/` 根，`internal class`。

---

## 3. 分层（自底向上）

| 层 | 职责 | 通用 / 专有 |
|---|---|---|
| **L0 传输原语** | `OkHttpClientFactory` / SSL·TOFU（`TofuRepository`）/ 拦截器（`SlimapiVersion/Capabilities/Debug/Traffic`）/ Auth / `SlimapiContract` 常量 | **通用**（拦截器按 `/slimapi/` 前缀注入头，叶子级、不上浮） |
| **L1 Wire/API 定义** | Retrofit 接口（`OpenCodeApi` 复合 = legacy 法 + slim 法；`OpenCodeApiV2`）；`SSEClient` / `TokenStreamClient` | **专有**（按变体分法，但同接口面） |
| **L2 域端口 + 双实现** | `SessionSource` / `MessageSource`（域语言接口）+ `Standard*Source` / `Slim*Source` | **接口通用 / 实现专有** |
| **L3 OCR 冻结门面** | `OpenCodeRepository`（~40 公共方法 = 1-line 委托，冻结测试锁）+ 能力读模型 forwarder | **通用**（对上 mode-agnostic） |
| **L4 协调 / service** | `SessionSyncCoordinator` / `service/streaming` / `service/status` / `notify` | **通用、模式盲**（读能力查询，不读 raw mode） |
| **L5 ViewModel / UI** | Compose 屏幕 + HiltViewModel + `ui/theme/` 原语 | **通用、模式盲** |

**判据**（什么通用、什么专有）：两变体**形状相同 → 通用**（域模型、门面契约、协调编排）；**取数方式不同 → 专有**（端点、分页、状态机、协商）。

---

## 4. 双 API 变体共存策略（核心）

三条铁律：
1. **共享形状，专有取数**——域模型共享；说 wire 的代码专有。
2. **用接口封装差异，不用散落 flag**——装配期（`configure()` / DI）分一次支，不在每个调用点分 N 次。
3. **差异下沉，不上浮**——协调层要区分时，先问"能否塞进 L2 端口"；只有编排本身不同才上抛为 capability 数据。

### 4.1 域端口 + 双实现（L2）
- `SessionSource`（`getSessions` / `getSessionsForDirectory`）、`MessageSource`（`getMessagesPaged`）等：**签名域语言，无 slim/legacy 词汇**。
- `Standard*Source`（调 legacy 法）/ `Slim*Source`（调 `/slimapi/` 法）。各实现内部**无 `if(isSlimMode)`**。
- **apiProvider lambda 防陈旧**：`StandardSessionSource(apiProvider = { api })`——每次调用读最新 `api` 字段（`configure` 重建后即生效），**禁止**构造期缓存 api 快照。
- **唯一选择点**：`OpenCodeRepository.configure()` 内 `completeSlimReconfigure` **之后**选束 `source = if (slim) Slim* else Standard*`（同一 `@Synchronized` monitor）。

### 4.2 共享态协作者（非独立策略对象）—— 最坏例 `MessageSource`
slim 域涉及共享锁 / 状态机 / token。`Slim*Source` **不得自持锁或状态机**，经**注入的纯函数 lambda** 访问共享态：
```kotlin
SlimMessageSource(
    apiProvider = { api },                                              // 防陈旧
    slimSessionUpdatedAt = { sid -> slimStateMachine.getSlimSessionState(sid)?.updatedAt ?: 0L },  // 只读 watermark
    bumpBookmark = { sid, items, tok -> bumpSlimBookmarkFromItems(sid, items, tok) },  // 回调 OCR（锁内）
)
```
`bumpBookmark` lambda 调 OCR 自身 `bumpSlimBookmarkFromItems`（内部 `synchronized(slimStateLock)`）→ **锁与 bookmark 状态留 OCR**，`Slim*Source` 不持锁 / 不持状态机对象。这是"共享态协作者拆分"，**不是**独立策略对象。

### 4.3 能力读模型（L4+ 模式盲）
L4+（协调 / service / UI）**禁读裸 `repository.isSlimMode`**，改读**语义能力查询**：
- 真相源：`ServerCompatProfile.slimConnection`（`@Volatile`，唯一受管写点 = `configure()` 的 `setSlimConnection`，在 `completeSlimReconfigure` 之后发布 = "最近一次成功 live 的 mode"）。
- 派生查询（纯计算）：`supportsWatermarkResync`（= slimConnection）、`supportsTokenStreamResync`（= slimConnection ∧ `slimapiTokenStreamEnabled`）、`usesSlimStatusFanOut`（= slimConnection）。
- OCR forwarder（零涟漪访问面）：`repository.supportsWatermarkResync` 等 → 透传 `serverCompatProfile`。多数 L4+ 已持 repository 句柄，直接读 forwarder，无需新注入。
- **mode vs readiness**：`slimConnection` 反映"最近成功 live 的 mode"，**不是** health/readiness（后者 = probe 字段 + `completeSlimReconfigure` readiness）。勿把 `supportsX` 当 readiness 用。

**§6 验收**：`rg isSlimMode` 在 `ui/`/`service/`/`di/` 业务代码 = 0。合法残留：SSE 机制豁免（`SseDispatchHost.slimMode()` 接口 / `SlimSseHandler.host.slimMode()` / SSC `slimMode()` override，属 SSE 路由分派）；OCR 内部（`isSlimMode` 属性 / `configure` / `checkHealth` 分叉，L3 门面）。

---

## 5. 不变量（重构 / 新增须守护）

源自 `T3RepositoryExtractFreezeTest`（反射锁 ~40 公共方法 + `slimStateLock` 字段 + FQN）与 L4a0：

- **I5 `slimStateLock` 单实例**：domain-delegate 与门面同锁；`Slim*Source` 经 lambda 回调，**禁自建锁 / 状态机**。
- **I6 `configure()` 原子事务**：`ticket → configureClientCert → hostConfig.configure → rebuildClients → completeSlimReconfigure → (source 束 / setSlimConnection 发布)` 不可拆、跨 legacy+slim、同一 `@Synchronized` monitor。
- **I7 `@Synchronized` monitor 序列化**；**rev-4 双监视器锁序**：`TofuRepository` 自带 `@Synchronized`，经 OCR 回调 rebuild 走同一 monitor（反向锁序会死锁）。
- **I8 `serverCompatProfile` 写点**：`update()`/`updateSlimapi()`（probe 尾部）+ `setSlimConnection`（`configure` 受管扩展）。派生查询 / forwarder 只读。
- **I15 token threading**：`SlimCommitToken` 外层 capture / 内层 require，端口化须原样穿透。
- **I20 公共 FQN 向后兼容**：`OpenCodeApi`/`SSEClient`/`SlimapiContract`/`HostConfig` + 嵌套 `SlimCommitToken`/`StaleSlimCommitException`/`SlimReconfigureTicket`/`SupersededSlimReconfigureException` 不动；上游 import 零改（调用经门面 / L2 端口）。
- **并发路由位 `@Volatile`**：`configure()` `@Synchronized` 内写、运行时 lock-free 读的可变 ref（`api`/`commandApi`/`mutationApi`/`apiV2`/`sseClient`/`sessionSource`/`messageSource`）均 `@Volatile`；纯 builder 字段（`retrofit`/`*Http`，仅 `rebuildClients` 内写读）不加。
- **freeze 行为保持**：端口化 / 能力化是纯加法 + 内部委托，公共签名 / 返回类型 / 错误语义（`Result` + `parseErrorCode` + `DebugLog.w` + rethrow）/ `X-Slimapi-Version=1`（不 bump）逐字不变。

---

## 6. 关键模式与约定

- **错误通道**：所有 suspend 取数返回 `Result<T>`，经 `runSuspendCatching`；HTTP 非 2xx `throw IOException("HTTP $code")`（或 `HttpException` + `recoverCatching` 二次解码），slim bookmark stale `throw StaleSlimCommitException()`。错误不跨锁挂起。
- **同包 `internal`**：新 delegate / source / 引擎落 `data/repository/` 根，`internal class/function`，禁子包。
- **freeze 前置**：动 OCR 公共面前先读 `T3RepositoryExtractFreezeTest`——它锁的方法不能搬走 / 改签名（要改须显式、审慎改测试）。
- **不可二分路径**：`checkHealth`/`checkHealthFor`（mTLS 解析分叉：`probeSlimapiHealth` 用 `sslConfigFor` held mTLS vs `checkHealthFor` 用 `resolveProbe` 纯参，防 mTLS 泄漏）**禁合并**；`coldStartSlimSync` + `requireSlimTokenCurrent`（token threading）**留门面**。
- **DI 选择点**：`ControllerModule` 装配 SSC 时 `supportsWatermarkResync = { repository.supportsWatermarkResync }`（thunk 读 forwarder，host/profile 切换可观测）。
- **新 overlay surface（picker/dialog/menu）**：走 `ui-style-spec.md` 三层规则（A=`DropdownMenu` / B=`AppBottomSheet` / C=`AlertDialog` family）+ `ui/theme/` 共享原语 + `Dimens`，禁散落 `dp` 字面量。

---

## 7. 「东西放哪」速查

| 要加的东西 | 放哪 | 注意 |
|---|---|---|
| 新域端口（取数双分支） | `data/repository/<Domain>Source.kt`（接口 + Standard/Slim 双实现） | 共享态协作者（lambda 注入，不自持锁）；OCR 门面加 1-line 委托；freeze |
| 新 slim/legacy 取数差异 | 已有 Source 的 Slim*/Standard* 实现 | 签名无 slim 词汇 |
| 新能力（L4+ 需区分） | `ServerCompatProfile` 派生查询 + OCR forwarder | 只读；mode vs readiness 区分 |
| 新 wire 端点 | `OpenCodeApi`（legacy 法 / slim 法）；slim 专属 DTO 随迁 `data/model` | 保持 `OpenCodeApi` FQN |
| 新 overlay / dialog | `ui/` + `ui/theme/` 原语 | 遵 `ui-style-spec.md` |
| 新拦截器 / 传输细节 | `data/repository/http/` | 叶子级，按路径前缀注入，不上浮 mode |
| 新不变量 / 守护 | 本文件 §5 + 必要时 freeze 测试 | 反射锁新增断言 |

---

## 8. 测试契约

- **`T3RepositoryExtractFreezeTest`**：反射锁 OCR ~40 公共方法 + `slimStateLock` 字段 + 协作者 FQN + 嵌套异常 FQN + `IOException` 继承——任一公共面 / 锁 / FQN 变更即 RED。
- **slim/legacy 双路测试**：`OpenCodeRepositorySlimapiEndpointsTest`（wire 契约）+ 各 `*Test` 覆盖两变体路径。
- **能力真值表测试**：`ServerCompatProfileCapabilitiesTest`（`slimConnection × tokenStream → supportsX` 真值表 + 派生纯读 + `configure` 成功/失败发布时序）。
- **共享态并发**：slim bookmark / token / watermark 路径须有并发不回归测。
- **行为保持型重构**：每步 `./scripts/check.sh`（compile + 单测）GREEN 才算完成；详见 `.opencode/policies/build-signing.md`。
