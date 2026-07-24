# 模块拆分与门面演化准则

> 本文件是 ocdroid **长期有效**的架构方针：如何在不破坏既有契约的前提下，把大型类（god class）演化成
> 「稳定的门面 + 注入式协作者」结构。新增 / 重构代码须遵守；规则冲突时以代码现状 + `.opencode/policies/` 为准。
>
> 相关规格：`./architecture.md`（分层 / 端口模式 / 能力读模型 / 不变量，**本文件的基础**）、
> `./slim-mode-api-routing.md`（slim 路由契约）、`./sse-client-spec.md`（SSE 控制面）、`./ui-style-spec.md`（UI 三层规则）。
> 一次性执行计划（具体函数 / 行号 / 阶段）归档在 `docs/ocmar/plans/`，**不**进 specs；本文件只沉淀从中提炼的可复用准则。

---

## 0. 本文件讲什么、不讲什么

| 讲（持久方针） | 不讲（一次性计划） |
|---|---|
| 门面为何冻结、如何演化 | 某个具体类现在该拆成哪几个文件 |
| 协作者的注入模式与归属边界 | 某个函数应当放在哪里 |
| 何时该拆、何时不该拆的判据 | 某次重构的 12 阶段表 |
| 冻结测试如何编码公开契约 | 具体行号、具体测试名（会漂移） |

需要具体执行时，去 `docs/ocmar/plans/` 找对应的 dated 计划；本文件提供**为什么这样做**的准则。

---

## 1. 第一原则：门面稳定、行为下沉

ocdroid 的核心大类（`OpenCodeRepository`、`SessionSyncCoordinator`、`HostProfileController` 等）承担**门面（facade）**角色：它们持有大量外部依赖的公开契约，被广泛引用、被测试钉死。

**演化方向**：门面**只增不减地保持公开表面稳定**，新增的内聚行为**下沉**到被注入的协作者（Engine / Source / Orchestrator / Helper），门面对其做 1:1 薄委托。

- 门面 = 稳定契约 + 装配 + 委托；**不**继续堆积业务逻辑。
- 协作者 = 单一内聚能力 + 明确归属；**不**反向持有门面。
- 薄 forwarder（1–6 行委托）是门面的本职，不是「该拆」的对象。

**反模式**：为了行数好看，把薄 forwarder 也搬走，或把门面拆成多个对等大类——前者徒增噪音，后者炸开血缘。

> 已有的成功先例：`OpenCodeRepository` 的 `ExpandBatchEngine` / `TofuRepository` / `SlimSseStateMachine` / `SessionSource` / `MessageSource`；`http/` 拦截器层。本文件把这些先例抽象成可复用准则。

---

## 2. 冻结测试即公开契约

公开表面（构造器 arity、嵌套类型 FQN、公开方法签名、UI seam、free-function 签名）由 **characterization / freeze 测试**编码。这些测试是「能改什么」的**权威**，而非可随手改的测试。

演化必须让它们保持 GREEN。三类常见冻结形态：

| 冻结形态 | 典型编码 | 演化约束 |
|---|---|---|
| 构造器 arity | 反射断言 `N in declaredConstructors.map{parameterCount}` | 不能增删主构造参数（见 §3 的 arity 陷阱） |
| 嵌套类型 FQN | 外部 caller 以 `Outer.Nested` 引用 | 不能把嵌套类移到顶层或换外层类（二进制破坏） |
| 公开 / 内部签名 | 测试直接按签名调用 | 签名不变，行为可下沉为 wrapper 委托 |

**纪律**：动公开表面前，先读对应 freeze 测试，确认约束的**精确形态**；不要凭「默认参数应该没事」的直觉下结论（见 §3）。

---

## 3. 协作者注入：provider-lambda field-init，不用构造器注入

**准则**：向冻结门面引入协作者，用**字段初始化器 + provider lambda**，**不**加构造器参数。

```
// ✅ 正确（ExpandBatchEngine 先例）
private val collaborator = Collaborator(
    apiProvider = { api },          // live provider，configure 换 api 后自动重读
    hostPortProvider = { hostConfig.hostPort ?: "" },
    ...
)

// ❌ 错误：给冻结门面加构造器参数（即便带默认值）
class Facade @Inject constructor(
    a, b,
    collaborator: Collaborator = Collaborator(...)  // 看似安全，实则破坏 arity
)
```

**为什么不用构造器注入**——三个独立问题：

1. **Arity 冻结违例（可证明）**：Kotlin 把 N 参默认参构造编译成 `N + 1(mask) + 1(marker)` 的合成构造。freeze 测试若钉死 `arity in {4, 6}`，加一个默认参会把合成 arity 从 6 推到 7，**两条断言同时红**。历史既有默认参（如 `tofuStore`）是**连同 freeze 测试一起改**才加进去的，不是免费午餐。
2. **Hilt 不可构造**：捕获 `this` / 已有字段（`api` / `hostConfig` / `slimStateMachine`）的协作者无法被 Hilt 干净构造——要么无 binding，要么循环 `this`。
3. **语义搬家而非解耦**：注入「整个门面引用」只是把耦合换个语法位置，没真正分离。

**唯一例外**：当协作者是 Hilt 可独立 provide 的无状态服务（如 ESP-backed store），且**同步更新 freeze 测试**，才可走构造器默认参。否则一律 field-init。

---

## 4. 协作者的四种成熟模式（按内聚度排序）

拆分时优先套用既有模式，**不发明正交轴**：

### 4.1 Engine（算法内聚）
单一算法簇 + 明确输入输出，持有**自己**的算法状态，但不碰门面的锁/token。
先例：`ExpandBatchEngine`（消息展开批处理预算 / TTL / 缓存失效）。
注入：provider lambda + 所需能力读 + 工具函数 lambda。

### 4.2 Port / Source（模式切换）
按数据模式（slim / standard）切换的取数抽象，在 `configure()` 时选定实现。
先例：`SessionSource` / `MessageSource`（及 `Standard*` 实现）。
门面持有 `@Volatile` 端口引用，`configure` 切换；调用方不感知模式。

### 4.3 能力读模型（Capability Read-Model）
把「连接具备什么能力」从裸布尔标志（`isSlimMode`）提升为**语义能力查询**，供 L4+ 消费者读。
先例：`ServerCompatProfile`（`supportsWatermarkResync` / `usesSlimStatusFanOut` / `supportsTokenStreamResync`）。
**规则**：L4+ 不直接读 raw mode 标志，只读 capability；mode 是 source-of-truth，capability 是访问语义。

### 4.4 纯状态对象 / Reducer（状态机内聚）
纯状态转换逻辑（CAS 状态机、reducer、merge 规则），无 IO、无副作用。
先例：`SlimSseStateMachine`、`SlimSseReducer`、`SseChatReducers`（`applyXxx` 纯扩展）。
**规则**：状态对象不 launch 协程、不访问 repo/store/effects；门面负责把结果落到副作用。

---

## 5. 归属边界：单一权威

拆分绝**不复制归属**。每类共享可变资源有且只有一个 owner：

| 资源 | 单一 owner | 拆分时的规则 |
|---|---|---|
| 并发锁 | 一个状态机 / 一个 domain 类 | 抽出的协作者**调用**持锁者，**不**自造第二把锁 |
| Token / incarnation 权威 | 一个状态机 | 协作者**消费** token，不 mint、不 reinterpret |
| Epoch / generation 计数 | 一个 `AtomicLong`，一个 owner | 注入**原实例**；禁止 wrapper + 协作者各持一份影子副本 |
| Effects 出口 | 一个 port（门面实现） | 子节点通过 sealed result / 单 port 表达副作用，不各自直发 |
| Stripe / 并发限额 | 一套 stripe / 一个 semaphore | 以 port 包裹既有实例，禁造第二套 |

**复制归属 = 重引入原本要消除的竞态。** 例：把 epoch 拆给 wrapper 和 orchestrator 各一份，会在两者之间打开新的 TOCTOU 窗口。

---

## 6. 单向依赖 DAG：禁回调环

把协调者（coordinator）拆成多个子协作者时，依赖必须是**有向无环**的：

- **子节点不持有父协调者引用**，不调用父的入口方法（如 `handleEvent`）。
- **副作用只经单一 effects port**（父实现）或 **sealed result / command**（数据进、数据出）。
- 「窄回调」往往是**伪装的环**——Reconciler→父 banner、父→Orchestrator catch-up，看似解耦，实为动态环，会制造难以归因的调度/取消 bug。

判据：画依赖图，若任一边可回溯到起点，就**没拆干净**——要么把决策下沉为纯函数（数据进/出），要么把副作用收敛到单 port，要么暂不抽该簇。

---

## 7. 结构搬动 ≠ 语义改动

**永不**把大规模代码搬动与并发 / 语义改动混在一步：

- 结构搬动（搬函数到新类、抽 wrapper）：失败应可归因为「搬错了位置」。
- 语义改动（锁获取顺序、token 校验位点、epoch 校验、identity 耦合）：失败应可归因为「逻辑变了」。

两者混在一起，红了无法定位是搬运错还是逻辑错。**分阶段**：先搬（行为不变，freeze 全 GREEN），再改语义（单独的可测改动）。

典型场景：D3 类「锁获取前的 host 切换」缺口——token 系统已存在，缺口在 commit 位点的 host-epoch 校验；这是**语义改动**，与任何结构抽取分开。

---

## 8. 何时拆、何时不拆（判据）

**行数从不是理由。** 「这个文件 3000 行」不构成拆分依据。

### 该拆
- 存在**内聚能力簇** + **既有 seam**（如已抽出的 Engine / Source 模式可延伸）。
- 门面围绕某簇膨胀，簇与门面其余部分耦合清晰、可注入。
- 簇有独立测试价值（想绕过 Retrofit / Robolectric 直接测状态机）。

### 不该拆
- **Wiring hub**：每个方法都 fan-in 到同一组 N 个内部依赖。拆了要么 10+ 依赖构造器，要么传整个 hub（换形式不换耦合）。→ 保留为门面 / 扩展函数集合。
- **Mutex 统一域**：一把锁保护一个不可分割的状态向量（层 + 运行时 + 握手 + 身份）。拆分破锁序与不变量。→ 除非先设计独立 command-channel 架构（那是另一个项目）。
- **已分解的壳**：主体已委托给一堆兄弟组件，剩余是 wiring + 状态管道。→ 仅在测量到具体缺陷时做私有 factoring。
- **纯声明密度高**：data / enum / 常量密集的大文件，不是 god 逻辑。

### 灰区（可选 / 条件性）
- **UI / Compose 大 composable**：见 §9，measure-first。

---

## 9. UI / Compose 域特殊规则

UI 拆分**不在** slim/standard 轴上；判据是可读性与**重组性能**（大重组 scope）。

- **纯 helper 可自由抽**（无 Compose 状态，JVM 可测）。
- **大 composable 优先抽稳定子 composable**（窄参数 + `@Stable` 类型）来**收窄重组 scope**，而非为行数。
- **`derivedStateOf` 优先于 `LaunchedEffect + snapshotFlow`** 来追踪派生状态，减少重组。
- **状态控制器抽取是高危**：把 `LaunchedEffect` 群搬出 composition，必须有**明确生命周期归属**——
  - `remember(key) { Controller(...) }` + 薄 composable effect 调 `onX()`，随 composition 销毁；**或**
  - 并入既有 ViewModel（项目既定的生命周期 owner）。
- **禁止造第二套生命周期**：独立 scope-owning 对象会导致 job 泄漏、重复订阅、取消错乱。
- **冻结 seam**（如 `ChatMessageList` 入口、helper 名）保持不动。
- **measure-first**：未测量到重组 / 生命周期缺陷前，不为行数拆 UI。

---

## 10. slim / standard 双轴贯穿

（框架级定义见 `./architecture.md` §1–2；本节给拆分视角的补充。）

拆分时延续双轴，**不发明正交分类**：

1. **slim-only 行为抽进 slim 协作者**（digest / watermark reconcile / bounded drain / cold-start / commit-token / reconfigure），保持显式，不消失在通用 legacy 抽象背后。
2. **standard 行为保持可独立执行**（直连 REST + 轮询），不假设 sidecar。
3. **能力决策走 `ServerCompatProfile`**，不散落布尔、不类型检查。
4. **轴不适用的代码保持共享**：SSE dispatch 核心、公共 diff/cache、生命周期协调、Compose 渲染。
5. **UI 不感知传输**：组件消费状态，不内部 branch repository 细节。

---

## 11. 验证纪律

- **每步 `./scripts/check.sh`**：本仓库已关 LSP，`check.sh`（编译 + 单测）是唯一反馈通道。见 `AGENTS.md`。
- **freeze 测试是 CI 门**：动公开表面前先读 freeze 测试；改后必须 GREEN。
- **ordering 不变量用 failing-first 回归测试**：对「A 必须先于 B」类并发顺序雷（如 sweep short-circuit 必须先于 epoch increment），**重构前先写一个会红的测试**，重构后转 GREEN——注释不是控制。
- **结构搬动与语义改动分阶段交付**（见 §7），各自可独立 `check.sh`。
- **设备安全**：UI / 插桩测试与安装**仅用模拟器**（`./scripts/emulator.sh`，用前 `status`，用完 `stop`）。见 `./emulator-debug.md`。

---

## 12. 检查清单（拆分前 / 拆分中）

拆一个簇前，逐条过：

- [ ] 这个簇是**内聚能力**还是只是「一堆代码」？有既有模式（§4）可套吗？
- [ ] 门面是**冻结门面**吗？读了对应 freeze 测试吗（§2）？
- [ ] 协作者用 **field-init + provider lambda** 注入吗（§3）？没加构造器参数吧？
- [ ] 嵌套类型 FQN / 公开签名保持不变（变 wrapper）吗？
- [ ] 锁 / token / epoch / effects / stripe 的**归属单一**吗（§5）？没复制吧？
- [ ] 依赖是**单向 DAG** 吗（§6）？子节点没回调父吧？
- [ ] 这次只**搬结构**，没混语义改动吧（§7）？
- [ ] ordering 雷有 **failing-first 回归测试**吗（§11）？
- [ ] 是 UI 域吗？若是，有**生命周期归属**且 measure-first 吗（§9）？
- [ ] 沿 **slim/standard 轴**吗（§10）？没发明新轴吧？

全过 → 按 `docs/ocmar/plans/` 里的 dated 计划推进，每步 `./scripts/check.sh`。

---

## 13. 何时更新本文件

- 沉淀了**新的可复用拆分模式**（新的注入形态 / 新的归属规则）。
- 发现某条准则被实践证伪（附反例 + 修订）。
- **不**因某次具体重构的进度 / 行号变化而更新——那些进 `docs/ocmar/plans/`。
