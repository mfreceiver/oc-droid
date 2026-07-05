# R-17 综合重构计划（代码债全面消除）

> 5 模型交叉评审（glmer/gpter/momo/dser/kimo）综合裁决产物。
> 经 `/grilling` 8 轮质询确认的所有决策。本文件是执行依据。

## 评审基线

| 评审 | 评分 | 侧重 |
|---|---|---|
| glmer | 6.0 | 方案可行性/配置不一致 |
| gpter | 5.5 | 架构演进/跨文件状态 |
| momo | 5.0 | 并发陷阱/性能 |
| dser | 5.0 | 强耦合/多状态 |
| kimo | 5.0 | 语义bug/边界条件 |
| **综合** | **5.3/10** | 处于重构中期，债在复利 |

## 核心共识债（全票）

1. AppState↔Slice 双写地狱（50+ mirror 字段，4 处重复映射）
2. MainViewModel God Orchestrator（2063 行，6 callback 接口，63 public 方法）
3. 主线程单线程隐式契约（17 处 check(Looper)，无编译期保证）
4. MainViewModelTest 3363 行反射白盒，反推架构
5. OpenCodeRepository 字段非 volatile 可见性问题
6. SessionSyncCoordinator delta coalescing 隐藏状态机
7. 静默 catch(_:Exception) 吞异常
8. 硬编码中文 UI 文案绕过 strings.xml

## 质询决策记录

| # | 决策点 | 选择 | 理由 |
|---|---|---|---|
| 1 | "一次编排"定义 | 统一调度分阶段流水线 | mirror 退役单点动 ~100 调用点；原子大爆炸回滚不可承受 |
| 2 | 第一批边界 | 8 项独立语义bug + i18n + lint，排除 connectionPhase | connectionPhase 与 mirror 纠缠，延后 |
| 2b | id 比较 null fallback | 列表索引 | 最坏只是无法精确定位，不错误截断 |
| 2c | lint HardcodedText | abortOnError=true 第一批生效 | 强制可见化 |
| 3 | error/successMessage 归宿 | (C) SharedFlow<UiEvent> 事件流 | 现有 clearError 已是事件语义；生产读取全 collect |
| 4a | lastNavPage 归宿 | NavSlice（MutableStateFlow<Int>）| 非事件，是持久化导航状态 |
| 4b | mirror 退役内部分片 | a→c→b→d→e→f，d 内 ≤3 并发 | d 前删 e 由编译器兜底 |
| 5 | d 子步风险控制 | (i) @oracle 全预审产出转换表 | 19 个条件调用需语义判断 |
| 6 | VM 拆分力度 | (C) 混合：6 领域VM + 薄OrchestratorVM + callback→effect | 跨域编排需归宿；领域VM提供scope |
| 7a | Repository currentDirectory | (i) 全消除，改显式 directory 参数 | 服务端已支持；改造面 bounded |
| 7b | SSE reducer | (ii) 半形式化：delta进slice+纯函数抽取，effect通道留后续 | 全reducer无table测试前回归风险高 |

## 门禁策略

| 节点 | 评审 | 门控 |
|---|---|---|
| 每子步 | check.sh | 必须绿 |
| 每批次末尾 | check.sh --full | 必须绿 |
| 每批次完成后 | @glmer + @maxer | **≥9.5** |
| batch2 d 启动前 | @oracle（转换表） | 阻塞 |
| batch2 e 完成后 | @oracle（退役核查） | 阻塞 |
| batch3 完成后 | @oracle + @council review-3 | 阻塞 |
| batch3 末尾 | emulator connectedDebugAndroidTest | 必须绿 |
| 全编排收尾 | @kimo + @gpter 全局 | **≥9.5** |

## 批次编排总图

### 批次 1 · 止血 + i18n
- 8 项语义 bug 修复（可并行 fixer）
- i18n：Compose Text()/message=/contentDescription= 全部硬编码 → strings.xml（lint HardcodedText error 级强制）。VM/controller 层 error/toast 字符串（如 `error = "连接失败"`）随批次 2 UiEvent 架构迁移（UiEvent 携带 @StringRes Int，UI 层 stringResource 解析），因为 VM 层无法直接用 Compose 的 stringResource()。
- kover 接入 check + lint HardcodedText abortOnError=true
- 门禁：check.sh --full → @glmer+@maxer ≥9.5
  - **批次门禁必须用 `check.sh --full`（含 lint），不能用默认 `check.sh`（不跑 lint）。**

### 批次 2 · AppState mirror 退役（最高风险）
- a. 新增 UiEvent + SharedFlow + NavSlice
- b. error/successMessage → SharedFlow，删 clearError/clearSuccessMessage
- c. lastNavPage → NavSlice
- d. @oracle 转换表 → ≤3 fixer 并行 74 个 updateAndSync→slice.update
- e. 删 AppState mirror + aggregateFromSlices + syncSlicesFromAppState + 8 writeXxx
- f. 重写 14 处 error 测试 → Turbine
- 门禁：@oracle(d前/e后) → check.sh --full → @glmer+@maxer ≥9.5

### 批次 3 · MainViewModel 拆分 + callback→effect
- 拆 6 VM（ChatVM/SessionVM/ConnectionVM/HostVM/ComposerVM/OrchestratorVM）
- controllers callback → SharedFlow<Effect>
- 8 Composable 入口改域 VM 注入
- MainViewModelTest 拆分下沉
- 门禁：emulator 测试 → @oracle → @council review-3 → @glmer+@maxer ≥9.5

### 批次 4 · Repository currentDirectory 全消除
- 目录端点改显式 directory 参数
- 删 HostConfig.currentDirectory 可变态
- FilesViewModel 加 generation guard
- 门禁：check.sh --full → @glmer+@maxer ≥9.5

### 批次 5 · SSE 半形式化
- delta coalescing → ChatState slice
- 11 事件分支状态更新抽纯函数 + table-driven 测试
- 副作用触发暂留 inline（effect 通道列入 followup）
- 门禁：check.sh --full → @glmer+@maxer ≥9.5

### 收尾 · 全局验收
- @kimo + @gpter 全局 review ≥9.5
- 更新 followup 文档

## 并发约束

- 同时运行的 agent 不超过 3 个
- 写作用域（文件/目录）不得重叠
