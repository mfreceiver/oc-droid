# Spec: 移除 SQLite 消息持久化，复用进程内内存 LRU

- **日期**：2026-07-15
- **Feature slug**：`remove-message-persistence`
- **状态**：已与用户对齐（brainstorming 产出），plan 编写中据摸底细化
- **审计引用**：本文件 + `docs/ocmar/plans/2026-07-15-remove-message-persistence.md`

---

## 1. 原始需求

> 是否可以全面评估移除本应用的 sqlite，移除消息存储持久化，并相应调整消息拉取的条数范围、查询逻辑等。我发现远期数据查询意义较小，而 sqlite、消息缓存、状态等可能带来了更多的问题。

## 2. 背景与现状（摸底结论）

- **数据库方案**：Room 2.8.4（KSP）+ SQLCipher 4.16.0 加密，DB 名 `chat_cache.db`，`fallbackToDestructiveMigration`（cache 被定位为"可重建的本地镜像，非真相源"）。
- **3 张表**（均在 `data/cache/`）：`cached_session`（会话元数据+指纹）、`cached_message`（消息行，`parts` 列为 JSON blob）、`gap_marker`（历史缺口游标）。
- **关键事实**：消息拉取主链路 `OpenCodeApi.getMessages` → `OpenCodeRepository.getMessagesPaged` → `MessageActions`/`CatchUpActions` **完全不依赖缓存**，纯 REST。移除持久化对"拉取"本身零影响。
- **未读/草稿/会话列表状态本来就不在 SQLite**（走 EncryptedSharedPreferences / 内存），不受影响。
- **进程内内存 LRU 已存在**：`ui/controller/SessionSwitcher.kt:87` `sessionWindowCache`（`LinkedHashMap(accessOrder=true)` + `removeEldestEntry`，key=`CacheWindowKey(serverGroupFp, sessionId)`，value=`CachedSessionWindow`，main-thread confined，容量 `SESSION_WINDOW_CACHE_CAPACITY=12`）。`makeCacheHook`（`AppCore.kt:248`）已先写该内存 LRU，再写 Room（`:260`）。`captureCurrentSessionWindow`（`SessionSwitcher.kt:129`）在切走会话时写回当前 chat 状态。即"切回会话秒显"的内存层**已在运作**，Room 只是额外的"指纹校验 + 重启恢复"层。
- **分页常量**集中在 `ui/ViewModelSupport.kt:53` `object MainViewModelTimings`：`initialMessagePageSize=20`、`historyMessagePageSize=50`、`sessionPageSize=10`、`gapProbeMessagePageSize=5`。

## 3. 目标 / 非目标

### 目标
1. 移除 Room / SQLCipher / SQLite 全部持久化基础设施（数据层 + DI + 依赖 + schema + 测试）。
2. 复用现有 `SessionSwitcher.sessionWindowCache` 作为唯一缓存层（不新建并行缓存），保留"切回近期会话秒显"体验（进程存活期内有效，重启即丢）。
3. 调整消息拉取条数：初始页增大、历史页缩减，契合"远期数据查询意义较小"。
4. 简化查询逻辑：删除 gap-aware / backfill 机制，历史浏览退化为单次会话内 `before` 游标线性上拉。
5. 删除"缓存管理"UI 与日度清理/驱逐协调器。

### 非目标
- 不改变消息拉取的 REST 主链路（API、Repository、网络层不动）。
- 不改变未读/草稿/会话列表的现有存储方式。
- 不引入新的磁盘持久化方案，不新建并行内存缓存。

## 4. 方案设计

### 4.1 途径选择（已与用户对齐）

| 途径 | 说明 | 取舍 |
|---|---|---|
| ① 纯内存全砍（无 LRU） | 进程内不保留消息，切会话必走 REST | 切回会话必闪烁 → **否决** |
| ② 保留极简磁盘缓存 | 文件/SP 存最近窗口 | 与"移除持久化"冲突 → **否决** |
| ③ 删持久化 + 复用内存 LRU + 条数调整 + 删 gap | 见下 | **选定** |

### 4.2 架构

移除 Room/SQLCipher 持久化层，复用 `SessionSwitcher.sessionWindowCache`（已存在）为唯一缓存层。消息拉取主链路（REST）不变。会话切换/历史浏览退化为"内存命中优先 + 网络回填"。

### 4.3 复用组件：`SessionSwitcher.sessionWindowCache`（不新建缓存）

摸底发现 `sessionWindowCache` 已是完整 LRU（见 §2）。因此**不新建 `MessageMemoryCache`**——复用 `sessionWindowCache` 为移除 Room 后的唯一缓存层，避免并行两套内存缓存（bug 温床）。

- **容量策略**：保留现有 `SESSION_WINDOW_CACHE_CAPACITY=12`（会话数 LRU）；不设"总条数上限"（每会话窗口受 initial+history 页大小约束，YAGNI）。
- **线程安全**：沿用现有 main-thread confined 模型（无需新增锁）。
- **生命周期**：`SessionSwitcher` 为 `AppCore` 持有对象，进程级，重启即清空。
- **改造**：
  - `VerifyAndHydrate` handler（`AppCore.kt:553`）：`cacheRepository.verifyAndLoad` → 改为 `sessionSwitcher.peekSessionWindow`，命中即 hydrate（`messages`/`partsByMessage`/`olderMessagesCursor`/`hasMoreMessages`），无需指纹校验（内存缓存是进程内刚写的，天然有效）；未命中 cold-start REST。删除 `:583` `gapsOf` 注入。
  - `makeCacheHook`（`AppCore.kt:246`）：删除 `:260` Room 写入（保留 `:248` `writeSessionWindow`）。
  - SSE `message.updated` 增量（`SessionSyncCoordinator.kt:827`）：`appendMessageIfSessionCached` → 改为若 `sessionWindowCache` 命中该 session 则更新内存窗口。

### 4.4 改造点

| 文件 | 改造 |
|---|---|
| `ui/AppCore.kt`（`:130,246,553,583`） | 删 `gapFillCoordinator` 字段（:130）；`makeCacheHook` 删 Room 写（:260）；`VerifyAndHydrate` 改 `peekSessionWindow`（:553）、删 `gapsOf`（:583） |
| `ui/controller/SessionSyncCoordinator.kt`（`:121,827`） | SSE 增量 → 更新 `sessionWindowCache`；删 `cacheRepository` 字段 |
| `ui/chat/GapFillCoordinator.kt`、`BackfillAlgorithm.kt`、`GapAwareMessageList.kt` | **删除** |
| `ui/controller/HostProfileController.kt`（`:871,896,910,974`） | 删 `cacheRepository.clearAll()` ×2 + `deleteDatabase`；内存清理保留 `ClearSessionWindowCache` effect 路径 |
| `ui/SettingsViewModel.kt`（`:67`） | 删除全部缓存管理状态/方法 |
| `ui/settings/CacheManagementSection.kt` | **整块删除**；`SettingsScreen.kt:557` 调用删除 |
| `ui/SessionListActions.kt`、`ui/SessionViewModel.kt` | 移除 `cacheRepository` 依赖 |
| `ui/AppStateSlices.kt:275` | 删 `ChatState.gapMarkers` 字段；清理全部引用（MessageActions/CatchUpActions/AppAction/SessionSwitcher/AppCoreOrchestration/ChatMessageContent） |

### 4.5 常量调整（`ui/ViewModelSupport.kt:53` `MainViewModelTimings`）

| 常量 | 现值 | 新值 |
|---|---|---|
| `initialMessagePageSize` | 20 | **40** |
| `historyMessagePageSize` | 50 | **30** |
| `gapProbeMessagePageSize` | 5 | **重命名为 `catchUpProbePageSize=5`**（catch-up probe 独立于 gap，保留小步长补最新） |
| `sessionPageSize` | 10 | 不变 |
| `sessionFullLoadLimit` | 500 | 不变 |

`RevertCutoffCoordinator`（`PAGE_SIZE=50` / `MAX_PAGES=5`）纯 REST、不依赖 cache/gap，**保留不动**。

### 4.6 数据流

- **切会话 A→B**：`switchTo` 写回 A 的窗口到 `sessionWindowCache`；`VerifyAndHydrate` → `peekSessionWindow` B，命中秒显 + `loadMessagesForEffect(resetLimit=false)` 非破坏合并最新尾；未命中 → `loadMessagesForEffect(resetLimit=true)` REST `getMessages(initial=40)`。
- **切回 A**：内存命中秒显。
- **向上翻历史**：当前最早消息 id 作 `before` 游标 → REST `getMessagesPaged(before, limit=30)` → prepend 到内存 list 头部并更新 `olderMessagesCursor`；空/不足 → 到头，UI 显示"没有更早消息"。
- **SSE `message.updated`**：若 `sessionWindowCache` 命中该 session 则更新内存窗口；否则静默丢弃。
- **catch-up（断线重连）**：`probeLatestMessageId` → 有新消息则 `getMessagesPaged(catchUpProbePageSize=5)` merge 补最新（NoGap 路径，gap 检测/填充已删）。
- **进程重启**：内存清空，冷启动走 REST。

### 4.7 错误处理

- 内存未命中 + 网络失败：沿用现有空态/错误重试 UI（`messageRetryDelayMs` 等不变）。
- LRU 淘汰：透明，用户无感（切回很旧会话需重新拉）。
- 历史到头：上拉返回空，UI 显示"没有更早消息"。
- SSE 增量无对应内存会话：静默丢弃（与当前 `appendMessageIfSessionCached` 仅当 session 已缓存才写一致）。

## 5. 影响面汇总

### 迁移
- `data/cache/contract/CachedSessionWindow.kt` → `ui/controller/CachedSessionWindow.kt`（SessionSwitcher 配套类型，被 `sessionWindowCache` 使用；import 同步更新）。

### 删除（数据层 + 基础设施）
- `data/cache/` 整包（除迁移出的 `CachedSessionWindow`）：`CacheDatabase` / `Entities` / `CacheDao` / `CacheRepository` + `Impl` / `CacheKeyStore` / `CacheMaintenanceCoordinator` / `CacheJson` / `contract/{GapMarker,GapFillState,CachedSessionLayout}`。
- `di/CacheModule.kt`；`di/ControllerModule.kt` 中 `cacheRepository` / `cacheMaintenanceCoordinator` 注入。
- `app/schemas/` 整目录。
- gap 机制：`GapFillCoordinator` / `BackfillAlgorithm` / `GapAwareMessageList` / `GapMarker` / `GapFillState`。
- 缓存管理 UI：`CacheManagementSection` + `SettingsViewModel` 缓存路径 + `CacheMaintenanceCoordinator`。
- `@Named("cacheDegraded")`（内存 LRU 下永远 false）。

### 依赖清理
- `app/build.gradle.kts`：删 room runtime/ktx/compiler、sqlite-framework、sqlcipher、`room.schemaLocation` KSP arg、`room-testing`。
- `gradle/libs.versions.toml`：删 `room` / `sqlite` / `sqlcipher` 版本与 lib 条目。

### 测试
- 删：`app/src/test/data/cache/*`（5 文件）、`app/src/test/di/CacheModuleTest.kt`、`app/src/androidTest/data/cache/*`（1 文件）、`app/src/test/ui/chat/GapFillCoordinatorTest.kt`。
- 改：涉及 `cacheRepository` mockk 的测试改为不依赖 cache（或随字段删除一并清理）。
- 补：`SessionSwitcher` peek-hydrate 路径 + `VerifyAndHydrate` 内存命中/未命中回归（现有 `SessionSwitcherTest`/`ChatViewModelTest` 已覆盖 LRU 淘汰，按需补 hydrate 断言）。

### 不受影响
- 消息拉取 REST 主链路、未读/草稿/会话列表状态、`data/model/Message.kt` 等 domain model、`RevertCutoffCoordinator`。

## 6. 成功标准

1. `./scripts/check.sh --full` 通过（编译 + 单测 + lint + 现有测试不回归）。
2. 构建产物不再含 Room/SQLCipher/SQLite 依赖（`app/build.gradle.kts` 与 `libs.versions.toml` 干净）。
3. `data/cache/` 包、`app/schemas/`、gap 机制、缓存管理 UI 全部移除，无残留引用（编译通过即证明无残留符号引用）。
4. `SessionSwitcher.sessionWindowCache` 作为唯一缓存层：切会话内存命中秒显、未命中走 REST；LRU 淘汰由现有 `SessionSwitcherTest` 覆盖。
5. 行为符合 §4.6 数据流：切会话 peek 命中、向上翻历史 `before` 游标、SSE 增量更新内存、catch-up 补最新。

## 7. 异常路径

- 网络失败 + 内存未命中 → 空态/重试（沿用现有）。
- LRU 容量超限 → 淘汰最旧会话（透明）。
- 历史游标到头 → UI 提示无更早消息。
- SSE 增量无对应内存会话 → 静默丢弃。

## 8. 已知风险 / 遗留

- **会话切换体验**：进程重启后首次切回任一会话均为冷启动 REST（有加载/闪烁），是"移除持久化"的必然代价，用户已接受。
- **历史浏览**：向上翻历史的游标存于 `sessionWindowCache` 内，会话被 LRU 淘汰或进程重启后丢失，需重新从首页上拉。
- **gap 机制删除是功能性退化**：用户已确认"远期数据查询意义较小"，接受该退化。
- **`SESSION_WINDOW_CACHE_CAPACITY=12`** 为现有值，保留；如需调参后续进行，不阻塞交付。

## 9. 测试策略

- 单元测试为主：`SessionSwitcher` peek-hydrate + `VerifyAndHydrate` 命中/未命中回归 + 改造后的 catch-up（NoGap merge）路径。
- 删除全部 `data/cache/*` / `CacheModule` / `GapFillCoordinator` 测试（被测对象已删）。
- 验证命令：`./scripts/check.sh`（每 task 后）；最终 `./scripts/check.sh --full`。
- 不在物理设备跑；如需集成测试仅用模拟器（AGENTS.md 设备安全纪律）。
