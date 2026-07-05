# R-17 重构遗留项与待解决问题

> 基于 4 路全局终审（kimo 8.9 / gpter 8.0 / dser 8.0 / glmer 7.5）汇总。
> 按优先级排序，含来源评审、证据位置、修复方向。
> 供下一期重构规划使用。

---

## 一、必须修复（4 路共识 🔴/🟠）

### P0-1. currentDirectory 全局状态未彻底消除

- **来源**：gpter 🔴 / dser 🔴 / glmer 🟠 / kimo 🔴（4/4 共识，最高优先）
- **当前状态**：批次 4 把文件 API 改为显式 `directory` 参数，但以下路径仍调用 `repository.setCurrentDirectory()`：
  - `OrchestratorViewModel.kt:141` — `browseFilesInWorkdir()` 临时覆盖全局目录
  - `OrchestratorViewModel.kt:157` — `closeFileBrowser()` 恢复旧目录
  - `SessionViewModel.kt:94` — `createSessionInWorkdir()` 设置全局目录
  - `AppCoreOrchestration.kt:64` — `executeCommand("/clear")` 读取全局目录
- **影响**：文件浏览器打开期间，SSE/question/command 等非文件路由可能被导向浏览目录而非当前会话目录
- **残留实体**：`HostConfig._currentDirectory`（@Deprecated 但仍 @Volatile mutable）、`DirectoryHeaderInterceptor` 仍读它
- **修复方向**：
  1. `browseFilesInWorkdir/closeFileBrowser` 改为写 `FileState.fileBrowserWorkdir` slice 字段，不触碰 repository
  2. `createSessionInWorkdir` 不调 `setCurrentDirectory`，改为显式传 directory 给后续 API 调用
  3. SSE/question/command 路由改为从 session/workdir 显式取 directory，不依赖全局
  4. 删除 `HostConfig._currentDirectory` + `DirectoryHeaderInterceptor` 的 fallback 读取
- **预估工作量**：中（~200 行改动，涉及 5+ 文件）

### P0-2. currentSessionId 双写残存（SettingsManager + ChatState）

- **来源**：dser 🔴 / kimo 🟠
- **当前状态**：`currentSessionId` 同时写入 `settingsManager.currentSessionId`（持久化）和 `chatState.currentSessionId`（内存 slice），共 10 个写入口分布在 7 文件
- **影响**：进程在两次写入之间死亡时，冷启动恢复可能落入错误会话（split brain）
- **证据**：`SessionSwitcher.kt:170`（写 SettingsManager）→ `:172-174`（写 slice），两行非原子
- **修复方向**：持久化变为 slice 的副作用（类似 Redux middleware），运行时只经过 `writeChat { ... }`
- **预估工作量**：中

### P0-3. VM/controller 层 i18n 漏洞

- **来源**：glmer 🔴 / kimo 🟠
- **当前状态**：`UiEvent.Error/Success(message: String)` 携带硬编码中文，英文 locale 下原样显示：
  - `ChatViewModel.kt:168` — `"已刷新"`
  - `SessionViewModel.kt:48` — `"子任务会话不可用"`
  - `HostProfileController.kt:352/362/398` — 隧道错误消息（3 处）
  - `AppCore.kt:33` — `TUNNEL_SUCCESS_TOAST = "隧道激活成功"`
- **根因**：VM 无法调用 Compose 的 `stringResource()`；`lint HardcodedText` 只检测 `Text()` 不检测 `UiEvent/const val`
- **修复方向**：`UiEvent.Error(@StringRes Int resId, vararg args)` → Composable 层 `stringResource()` 解析。strings.xml 补对应 key
- **预估工作量**：小（~30 行改动）

### P0-4. kover 门控在主路径失效

- **来源**：glmer 🔴
- **当前状态**：`tasks.named("check").configure { dependsOn("koverVerify") }` 接入 gradle check，但 `check.sh`（AGENTS.md 强制路径）跑 `compileDebugKotlin + testDebugUnitTest`，绕过 check。`--full` 跑 `koverHtmlReport`（报告）而非 `koverVerify`（验证）
- **修复方向**：`check.sh` 默认或 `--full` 路径追加 `$GRADLE koverVerify`
- **预估工作量**：1 行

---

## 二、应修复（3/4 共识 🟠）

### P1-1. SharedStateStore 写权限无编译期隔离

- **来源**：gpter 🟠 / dser 🟠 / kimo 🟡
- **当前状态**：9 个 slice 都是 `public val MutableStateFlow`，任何注入 SharedStateStore 的类都能写任何 slice。AppCore.writeXxx 是纪律约束，非类型约束
- **修复方向**：对外暴露 `StateFlow`（只读），写入集中在 domain store/reducer；或用 `internal` + 同模块访问
- **预估工作量**：大（影响所有 VM/controller 签名）

### P1-2. appScope vs viewModelScope 语义混淆

- **来源**：dser 🟠 / kimo 🟡
- **当前状态**：6 个 VM 通过 `core.appScope.launch { ... }` 启动协程（进程级 scope）。VM 被清除时这些协程不会自动取消。仅 FilesViewModel 用 `viewModelScope`
- **影响**：快速切换 session 时，旧的网络请求继续飞行（结果被 sessionId guard 丢弃，但 HTTP 连接占用）
- **修复方向**：VM 方法体改用 `viewModelScope`，或 AppCore 提供按域取消的 hook
- **预估工作量**：中

### P1-3. tryEmit 关键 effect 可静默丢失

- **来源**：gpter 🟠 / maxer 🟡 / kimo 🟡
- **当前状态**：`SharedEffectBus.effects` 用 `MutableSharedFlow(extraBufferCapacity=64).tryEmit()`。buffer 满时返回 false，事件静默丢弃。对 UI toast 可接受，对 SSE event/session switch/clear buffer 不可接受
- **修复方向**：拆分通道——UI event 用 SharedFlow(可丢)；业务命令用 Channel/suspend emit(不可丢)
- **预估工作量**：中

### P1-4. ConnectionCoordinator.directoryFetchGeneration 非原子

- **来源**：kimo 🔴
- **当前状态**：`@Volatile private var directoryFetchGeneration: Long = 0` + `++` 自增，与 FilesViewModel 已修复的 `@Volatile Int++` 问题同构
- **修复方向**：改为 `AtomicLong`
- **预估工作量**：1 行

### P1-5. FilesViewModel 空文件误判为目录

- **来源**：kimo 🟠 / maxer 🟡
- **当前状态**：`content.content.isNullOrBlank()` 为 true 时 fallback 到 `loadDirectoryPreview`。空文件（`.gitkeep`/空 README）被当目录展示。且测试 `FilesViewModelTest.kt:132-155` 固化了这个 bug
- **修复方向**：依赖 `FileContent.type` 或 path 后缀区分 file/directory；修测试
- **预估工作量**：小

### P1-6. FilesViewModel.navigateUp 路径归一化缺陷

- **来源**：kimo 🟠 / maxer 🟢
- **当前状态**：`substringBeforeLast("/", "")` 对尾部斜杠路径（如 `foo/bar/`）计算 parent 为 `foo/bar`（同级），返回无效果
- **修复方向**：先 trimEnd('/') 再 substringBeforeLast，或用 `java.nio.file.Paths`
- **预估工作量**：2 行

---

## 三、应清理（2/4 共识 🟡）

### P2-1. Actions 自由函数文件命名债

- **来源**：glmer 🟠 / kimo 🟠 / maxer 🟡
- **当前状态**：MainViewModel 类已删除，但 7 个 `MainViewModel*Actions.kt` 文件名仍带前缀，内部全是 `internal fun launchXxx`，命名与内容不符
- **修复方向**：重命名为 `MessageActions.kt` / `CatchUpActions.kt` 等，或内联到各 VM private 方法
- **预估工作量**：小（机械改名 + import 更新）

### P2-2. AppCore 仍是中等耦合点

- **来源**：glmer 🟡 / dser 🟡 / kimo 🟡
- **当前状态**：AppCore 291 行 + AppCoreOrchestration 329 行 = 620 行。22 分支 `when(effect)` router + 6 个跨域编排方法。比原 MainViewModel 好很多，但仍是单一耦合点
- **修复方向**：按域拆分 effect dispatcher；跨域方法提取为 interface；或通过 SharedEffectBus 完全去中心化
- **预估工作量**：大

### P2-3. TestAppStateShim.kt 重引入 AppState

- **来源**：maxer 🟡（批次 3 评审）
- **当前状态**：370 行测试兼容层，重新引入被删除的 `AppState` data class（仅 test source set），提供 `core.state.value.X` 兼容 API
- **修复方向**：逐步把 `core.state.value.X` 改为 `core.store.xxxFlow.value.X`，最终删除 shim
- **预估工作量**：中（~100 处测试断言改写）

### P2-4. SSE 全纯 reducer + effect 通道

- **来源**：gpter 🟡 / dser 🟡 / R-17 followup #1
- **当前状态**：22 个纯函数只管状态更新；副作用触发（onRefreshMessages 等）仍 inline。不是完整 `(Event,State)→(State,Effects)` reducer
- **修复方向**：副作用迁 effect 通道；事件序列枚举测试
- **预估工作量**：大

### P2-5. VM 通过 core 访问一切

- **来源**：kimo 🟡
- **当前状态**：所有 VM 都有 `internal val core: AppCore`，通过 `core.store.xxxFlow` / `core.controller` / `core.repository` 访问。比 MainViewModel 好，但仍是一个"万能上下文"
- **修复方向**：VM 只注入自己需要的 slice + controller + effectBus
- **预估工作量**：大

---

## 四、cosmetic / 防御性（可选）

| # | 问题 | 来源 | 修复 |
|---|---|---|---|
| C-1 | applyPartDeltaLeadingEdge 双重载合并 | kimo+maxer | 合并为单函数 |
| C-2 | expandedParts 4 处 .value = 改 .update{} | maxer | 风格统一 |
| C-3 | flushJobs 加 ConcurrentHashMap 或 @Synchronized（防御性） | maxer+kimo | 文档已标注，按需硬化 |
| C-4 | connectionPhase → sealed class | dser+kimo | 在 ConnectionVM 内独立做 |
| C-5 | MissingTranslation lint 重新启用 | glmer | en/zh 已对齐，可重启 |
| C-6 | 中文注释密度过高 | kimo | 迁 RFC 引用到 docs/ |
| C-7 | OrchestratorVM 拆 settings/traffic 子域 | dser | 减少角色过载 |
| C-8 | SettingsManager 接口抽象 + draft 性能 | momo | 原始评审遗留 |
| C-9 | TrafficLogger 隐私脱敏 | gpter | 扩大用户群前 |
| C-10 | PartStateSerializer 独立 + fuzz | glmer | 碰 Message.kt 时顺手 |
| C-11 | UI 大文件深度拆分 | momo+kimo | ChatScreen 按职责分离 |
| C-12 | property-based testing for SSE 纯函数 | glmer | kotest-property |

---

## 五、推荐下一期执行顺序

```
Phase 1（止血，1-2 天）
├── P0-1 currentDirectory 彻底消除 ← 4 路共识最高优先
├── P0-3 VM/controller i18n ← 小改动大收益
├── P0-4 kover 接入 check.sh ← 1 行
└── P1-4 directoryFetchGeneration AtomicLong ← 1 行

Phase 2（加固，3-5 天）
├── P0-2 currentSessionId 双写收敛
├── P1-1 SharedStateStore 写权限隔离
├── P1-2 appScope → viewModelScope
└── P1-5 + P1-6 FilesViewModel 边界修复

Phase 3（可靠性，1 周）
├── P1-3 tryEmit → Channel 分通道
└── P2-4 SSE 全纯 reducer

Phase 4（清理，按需）
├── P2-1 Actions 文件改名/内联
├── P2-2 AppCore 进一步分散
├── P2-3 TestAppStateShim 逐步删除
└── P2-5 VM 精确注入
```

---

## 附：R-17 成就摘要

| 指标 | 重构前 | 重构后 | 变化 |
|---|---|---|---|
| 可维护性（4 路平均） | 5.3/10 | 8.1/10 | +53% |
| MainViewModel 行数 | 2063 | 0（删除） | -100% |
| AppCore 行数 | — | 291 + 329 orchestration | — |
| AppState mirror 字段 | 50+ | 0（整文件删除） | -100% |
| check(Looper) | 17 | 0 | -100% |
| callback interface | 6（41 方法） | 0（22 ControllerEffect） | -100% |
| SSE 纯函数 | 0 | 22（+49 table 测试） | — |
| 领域 ViewModel | 0 | 6 @HiltViewModel | — |
| i18n key | ~210 | ~240 | +14% |
| 测试文件 | 1 巨型（3363行） | 6 领域文件（~4100行） | 按域拆分 |
