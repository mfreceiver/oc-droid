# Follow-up: 0.6.1 覆盖率 floor 临时下调（58/54 → 0.6.2 回升 60/56）

> 0.6.1 引入三块并发层修复（Reactive VCS workdir flow + 模型 v2-tolerant 目录容错 + history-load 并发 Mutex 包装）后，kover unit-testable 集合的实测覆盖率落在 floor 60/56 之下。四方评审（gpter/opuser/glmer/kimo）一致同意 0.6.1 **临时下调 floor 到 58/54**（line/branch）发版，0.6.2 把新增并发分支的测试补齐后再**回升到 60/56**。本文档登记未覆盖分支 + 回升计划，避免静默回退。

## 现状（0.6.1 发版时）

`app/build.gradle.kts` kover `verify` rule：

```
minBound(58, LINE,      COVERED_PERCENTAGE)
minBound(54, BRANCH,    COVERED_PERCENTAGE)
minBound(52, INSTRUCTION, COVERED_PERCENTAGE)
```

注释 §0.6.1-coverage 标注：**一次性调整，非静默回退**；floor 在 0.6.2 回升。

## 0.6.1 新增的未覆盖分支

下面三类是 0.6.1 为修并发缺陷新增的代码路径，单测成本高（需注入并发触发器 / 模拟竞态），本轮未补：

### 1. history-load 并发 Mutex 路径（MessageActions / ChatViewModel）
- **新增内容**：per-session `Mutex` 包装 `loadMessages` / `loadMore` / `catchUp`，防止后台历史加载与用户触发的 loadMore/catchUp 交错丢消息。
- **未覆盖分支**：
  - Mutex 已被持有时的 `tryLock` 回退路径（第二个并发调用被快速跳过而非排队）。
  - 后台 load 完成时若 loadMore 已抢先入队，合并/去重分支。
- **为什么没补**：需要可控的协程调度器（`runUnconfined` + 人工 `step()`）或 `Mutex` 注入口，且这些方法签名与 ViewModel 生命周期耦合，纯 JVM 单测要重 mock 一层 dispatcher。

### 2. catchUp fp-switch 竞态（CatchUpActions）
- **新增内容**：catchUp（重连后追赶最新消息）期间若用户切换了 host profile（serverGroupFp 变化），中途的 SSE 事件必须被丢弃而不是错配到新 host。
- **未覆盖分支**：catchUp 协程启动后、fp 在途中切换、收尾时 fp-guard 命中「fp 已变 → 丢弃本次追赶结果」。
- **为什么没补**：需要模拟 fp 在协程挂起点之间被外部改写（StateFlow 注入 + `runCurrent`/`advanceUntilIdle` 编排），构造成本高。

### 3. clearAllLocalData 竞态（SettingsManager）
- **新增内容**：0.6.1 round-1 Fix B 把 `clearAllLocalData()` 末尾的 `_currentWorkdirFlow.value = ESP.getString(...)` 改为**直接赋 null**，消除「batched `.remove()` 与 ESP re-read 之间的理论竞态窗口」。
- **已覆盖**：`SettingsManagerTest.currentWorkdirFlow goes null after clearAllLocalData` 钉住了「clearAll 后 flow 必为 null」契约。
- **未覆盖分支**：真正的并发竞态（一个线程在 clearAll 的 `.remove()` 与赋值之间调用 `setCurrentWorkdir`）—— 这是理论窗口，单测难以确定性复现（需要跨线程时序），靠「直接赋 null」的语义消除而非测试钉死。

## 0.6.2 回升计划（floor → 60/56）

目标：把上述 1 / 2 的分支补到 unit-testable，实测 line/branch 回到 ≥ 60/56 后，把 `build.gradle.kts` 的 `minBound` 改回 60/56/52。

### 要补的测试
1. **history-load Mutex 并发（MessageActionsTest / ChatViewModelTest）**
   - 注入一个 fake `Mutex`（或把 `tryLock` 抽成 internal 钩子），断言：
     - loadMore 在后台 load 持锁期间被「快速跳过」（不排队、不丢用户意图）。
     - 后台 load 完成后 loadMore 能正常拿锁、合并去重。
   - 估 2-3 个单测，约 +0.4pp branch。

2. **catchUp fp-switch（CatchUpActionsTest）**
   - 用 `TestScope` + 注入一个可控 fp `StateFlow`，编排：
     - catchUp 启动（fp=A）→ 挂起 → fp 改为 B → catchUp 收尾 → 断言结果被 fp-guard 丢弃（不写回 A 的视图）。
     - 对照组：fp 全程不变 → 结果正常应用。
   - 估 2 个单测，约 +0.3pp branch。

3. **可选：getProviders wrong-type + 结构损坏的分支**（本轮已补 3 个测试，剩余的 unparseable-provider 计数路径可加 1 个对称测试补满）。

### 门控
- 回升前先跑 `./scripts/check.sh --full` 确认实测 ≥ 60/56（不只卡线）。
- 改 `build.gradle.kts` 的 `minBound` 后，commit message 标注 `§0.6.2-coverage: floor restored 58/54 → 60/56 (history-load mutex + catchUp fp-switch tests)`，并把本文件的「现状」更新为已关闭。

## 相关
- `app/build.gradle.kts` §0.6.1-coverage 注释（floor 下调的权威记录）。
- `docs/follow-ups/streaming-md-0.6.2.md`（0.6.2 另一项延期工作）。
- 0.6.1 round-1 评审：gpter/opuser/glmer/kimo 四方共识（模型目录 wrong-type 容错为本轮最高优先级，已修；并发分支测试为 0.6.2 范围）。
