# 移除 SQLite 消息持久化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use ocmar-subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 移除 Room/SQLCipher 消息持久化层，复用现有 `SessionSwitcher.sessionWindowCache` 为唯一缓存层，调整拉取条数，删除 gap 机制与缓存管理 UI。

**Architecture:** 删除 `data/cache` 整包 + DI + 依赖 + schema；`SessionSwitcher.sessionWindowCache`（已有 `LinkedHashMap` LRU，容量 12 会话，main-thread confined）成为唯一缓存层；`VerifyAndHydrate` 改为 `peekSessionWindow`；消息拉取主链路（REST）不变。

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, kotlinx.coroutines。移除 Room 2.8.4 / SQLCipher 4.16.0 / androidx.sqlite。

## Global Constraints

- 验证命令：`./scripts/check.sh`（编译 + 单测）；最终用 `./scripts/check.sh --full`
- 不在物理设备跑；集成测试仅模拟器（AGENTS.md 设备安全纪律）
- 版本号由 git 派生，禁止手改 `app/build.gradle.kts` 版本字段
- 不 commit（除非用户显式要求）；每 task 记录 diff（`git rev-parse HEAD` baseline + `git diff --stat`）
- base = `git rev-parse HEAD`（执行前记录）
- **复用 `SessionSwitcher.sessionWindowCache`，不新建并行内存缓存**
- `CachedSessionWindow` 从 `data/cache/contract/` 迁移到 `ui/controller/`（SessionSwitcher 配套类型），不删除
- **执行顺序严格 Task 1→8**（先移除调用点，后删接口/依赖），每 task 后 `check.sh` 必须通过

## File Structure

**迁移**：
- `data/cache/contract/CachedSessionWindow.kt` → `ui/controller/CachedSessionWindow.kt`

**删除**：
- `data/cache/` 整包（除迁移出的 `CachedSessionWindow`）
- `di/CacheModule.kt`
- `ui/chat/GapFillCoordinator.kt`、`ui/chat/BackfillAlgorithm.kt`、`ui/chat/GapAwareMessageList.kt`
- `ui/settings/CacheManagementSection.kt`
- `app/schemas/` 整目录
- 测试：`test/data/cache/*`、`test/di/CacheModuleTest.kt`、`androidTest/data/cache/*`、`test/ui/chat/GapFillCoordinatorTest.kt`

**修改**：`ui/ViewModelSupport.kt`、`ui/AppCore.kt`、`ui/controller/SessionSwitcher.kt`、`ui/controller/SessionSyncCoordinator.kt`、`ui/controller/HostProfileController.kt`、`ui/SettingsViewModel.kt`、`ui/SessionListActions.kt`、`ui/SessionViewModel.kt`、`ui/AppStateSlices.kt`、`ui/MessageActions.kt`、`ui/CatchUpActions.kt`、`ui/AppAction.kt`、`ui/AppCoreOrchestration.kt`、`ui/chat/ChatMessageContent.kt`、`ui/ChatViewModel.kt`、`ui/settings/SettingsScreen.kt`、`di/ControllerModule.kt`、`app/build.gradle.kts`、`gradle/libs.versions.toml`

---

### Task 1: 拉取条数常量调整

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/ViewModelSupport.kt:67-102`
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/CatchUpActions.kt:138`

**Interfaces:** N/A（纯常量）

**Acceptance Criteria:**
- `T1-C1`: `MainViewModelTimings.initialMessagePageSize` == 40
- `T1-C2`: `MainViewModelTimings.historyMessagePageSize` == 30
- `T1-C3`: `gapProbeMessagePageSize` 不存在；`rg -n "gapProbeMessagePageSize" app/src` 仅剩 KDoc 历史注释或无结果
- `T1-C4`: `catchUpProbePageSize` == 5；`CatchUpActions.kt:138` 引用它
- `T1-C5`: `./scripts/check.sh` 通过

- [ ] **Step 1: 改常量定义**

`ui/ViewModelSupport.kt` `MainViewModelTimings` 内：
```kotlin
const val initialMessagePageSize = 40      // was 20
const val historyMessagePageSize = 30      // was 50
const val catchUpProbePageSize = 5         // renamed from gapProbeMessagePageSize
```
删除原 `gapProbeMessagePageSize` 定义（:102）；更新其 KDoc 去掉"gap"措辞，改为"catch-up probe: 拉最新几条用于断线补齐"。

- [ ] **Step 2: 改 CatchUpActions 引用**

`CatchUpActions.kt:138`：
```kotlin
repository.getMessagesPaged(sessionId, MainViewModelTimings.catchUpProbePageSize, before = null)
```

- [ ] **Step 3: 跑 check.sh**

Run: `./scripts/check.sh`
Expected: PASS（编译 + 单测）

- [ ] **Step 4: 记录 diff**

```bash
git rev-parse HEAD        # baseline
git diff --stat           # 记录改动
```

---

### Task 2: VerifyAndHydrate 改为 peek sessionWindowCache

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/AppCore.kt:546-623`
- Test: `app/src/test/java/cn/vectory/ocdroid/ui/controller/SessionSwitcherTest.kt`（补 hydrate 断言）

**Interfaces:**
- Consumes: `SessionSwitcher.peekSessionWindow(sessionId: String): CachedSessionWindow?`（已存在，`SessionSwitcher.kt:101`）
- Produces: N/A

**Acceptance Criteria:**
- `T2-C1`: `VerifyAndHydrate` handler 不再调用 `cacheRepository.verifyAndLoad` 或 `cacheRepository.gapsOf`（`rg "verifyAndLoad|gapsOf" app/src/main` 在 AppCore 无命中）
- `T2-C2`: 内存命中时 `mutateChat` 写入 `cached.messages`/`partsByMessage`/`olderMessagesCursor`/`hasMoreMessages`，并调 `loadMessagesForEffect(sessionId, resetLimit=false)`
- `T2-C3`: 未命中时调 `loadMessagesForEffect(sessionId, resetLimit=true)`，不注入任何消息
- `T2-C4`: 保留 entry guard + 二次 fp/sessionId 重检（peek 后）
- `T2-C5`: `./scripts/check.sh` 通过

- [ ] **Step 1: 补 SessionSwitcherTest hydrate 断言（先写测试）**

在 `SessionSwitcherTest.kt` 新增（或确认已有）测试：写入一个 window → `peekSessionWindow` 返回该 window；未写入的 session 返回 null。若 `ChatViewModelTest.kt:1209-1266` 已覆盖 peek 命中/未命中，则在此 task 仅确认其仍通过。

- [ ] **Step 2: 跑测试确认基线**

Run: `./gradlew :app:testDebugUnitTest --tests "*SessionSwitcherTest*"`
Expected: PASS

- [ ] **Step 3: 改造 VerifyAndHydrate handler**

`AppCore.kt:546-623` 替换为（保留 entry guard，peek 替代 verifyAndLoad，删 gapsOf）：
```kotlin
appScope.launch {
    if (effect.sessionId != store.chatFlow.value.currentSessionId) {
        DebugLog.d(TAG, "VerifyAndHydrate dropped: session switched away (entry)")
        return@launch
    }
    val cached = sessionSwitcher.peekSessionWindow(effect.sessionId)
    // 二次重检：peek 后复合键未变才注入
    if (effect.serverGroupFp != currentServerGroupFp() ||
        effect.sessionId != store.chatFlow.value.currentSessionId
    ) {
        DebugLog.d(TAG, "VerifyAndHydrate dropped: fp or session changed during peek")
        return@launch
    }
    if (cached != null) {
        store.mutateChat {
            it.copy(
                messages = cached.messages,
                partsByMessage = cached.partsByMessage,
                olderMessagesCursor = cached.olderMessagesCursor,
                hasMoreMessages = cached.hasMoreMessages,
            )
        }
        loadMessagesForEffect(effect.sessionId, resetLimit = false)
    } else {
        loadMessagesForEffect(effect.sessionId, resetLimit = true)
    }
}
true
```

**更新 `peekSessionWindow` docstring**（grilling·假设2）：`SessionSwitcher.kt:96-100` 当前标注 "Test-only visibility"，改为生产用途说明——`appScope`=Main.immediate，与 cache main-thread confinement 一致，peek 同步读安全；"不扰动 LRU 顺序"（`entries.firstOrNull` 而非 `get`）对 verify 场景正合适。

- [ ] **Step 4: 跑 check.sh**

Run: `./scripts/check.sh`
Expected: PASS

- [ ] **Step 5: 记录 diff**

```bash
git rev-parse HEAD && git diff --stat
```

---

### Task 3: makeCacheHook 删 Room 写 + SSE 增量改写内存 LRU（append）

**Files:**
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/AppCore.kt:246-262`（makeCacheHook 删 Room 写）+ `dispatchSessionEffect`（新增 `AppendMessageToCache` handler）
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/controller/SessionSwitcher.kt`（新增 `appendMessageIfCached`）
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/controller/ControllerEffect.kt`（新增 `AppendMessageToCache` effect）
- Modify: `app/src/main/java/cn/vectory/ocdroid/ui/controller/SessionSyncCoordinator.kt:822-839`（emit effect 替换 `cacheRepository` 调用）
- Test: `app/src/test/java/cn/vectory/ocdroid/ui/controller/SessionSwitcherTest.kt`（补 `appendMessageIfCached` 断言）

**Interfaces:**
- Produces: `SessionSwitcher.appendMessageIfCached(serverGroupFp: String, sessionId: String, message: Message, parts: List<Part>)`；`ControllerEffect.AppendMessageToCache(serverGroupFp, sessionId, message, parts)`

**Acceptance Criteria:**
- `T3-C1`: `makeCacheHook` 不再调用 `cacheRepository.putSessionWindow`（`rg "putSessionWindow" app/src/main` 无命中）；保留 `sessionSwitcher.writeSessionWindow`
- `T3-C2`: `SessionSwitcher.appendMessageIfCached` 存在：当 `(fp,sid)` 在 `sessionWindowCache` 时 append message 到 `messages` 末尾 + 合并 parts，不在则 no-op
- `T3-C3`: SSE `message.updated` 新消息分支 emit `ControllerEffect.AppendMessageToCache`（替换原 `cacheRepository.appendMessageIfSessionCached`）；`rg "appendMessageIfSessionCached" app/src/main` 无命中
- `T3-C4`: `AppCore.dispatchSessionEffect` 处理 `AppendMessageToCache` → 调 `sessionSwitcher.appendMessageIfCached`
- `T3-C5`: `./scripts/check.sh` 通过

**说明（grilling 修正·假设1）**：原 plan 拟"直接删 SSE 缓存写、依赖切回 REST 补"。grilling 验证发现：若用户在别处期间该 session 到达 >`initialMessagePageSize`(40) 条新消息，切回时 latest-40 fetch + `olderKept` merge 会丢中间消息（`olderKept` 排除比 `oldestFetched` 新但不在 fetch 里的消息）。故保留"SSE → 内存 LRU append"（纯内存，无 IO），把原 `appendMessageIfSessionCached` 目标从 SQLite 换成 `sessionWindowCache`，闭掉丢消息窗口。因 `SessionSwitcher` 是 `AppCore` 成员（非 Hilt），`SessionSyncCoordinator`（Hilt 提供）经 `ControllerEffect` 总线委托 AppCore 执行 append（沿用现有 effect 模式）。

- [ ] **Step 1: SessionSwitcher 新增 appendMessageIfCached**

`SessionSwitcher.kt`（`clearAllCached` 之后）新增：
```kotlin
internal fun appendMessageIfCached(
    serverGroupFp: String,
    sessionId: String,
    message: Message,
    parts: List<Part>,
) {
    val key = CacheWindowKey(serverGroupFp, sessionId)
    val existing = sessionWindowCache[key] ?: return
    sessionWindowCache[key] = existing.copy(
        messages = existing.messages + message,
        partsByMessage = existing.partsByMessage + (message.id to parts),
    )
}
```
（import `cn.vectory.ocdroid.data.model.Message` / `Part`。）

- [ ] **Step 2: 新增 ControllerEffect.AppendMessageToCache**

`ControllerEffect.kt`（`ClearSessionWindowCache` 附近）新增：
```kotlin
data class AppendMessageToCache(
    val serverGroupFp: String,
    val sessionId: String,
    val message: Message,
    val parts: List<Part>,
) : ControllerEffect()
```
（import `Message` / `Part`。）

- [ ] **Step 3: AppCore handler + makeCacheHook 改造**

`AppCore.kt` `dispatchSessionEffect`（`ClearSessionWindowCache` 分支旁）新增：
```kotlin
is ControllerEffect.AppendMessageToCache -> {
    sessionSwitcher.appendMessageIfCached(
        effect.serverGroupFp, effect.sessionId, effect.message, effect.parts
    )
    true
}
```
`makeCacheHook`（`:246-262`）替换为：
```kotlin
internal fun makeCacheHook(fp: String): (String, CachedSessionWindow) -> Unit = { sid, window ->
    sessionSwitcher.writeSessionWindow(fp, sid, window)
    // Room 持久化已移除：进程内 LRU 是唯一缓存层。
}
```
删除原 `:250-262` 的 sessionList 查找 + `appScope.launch { cacheRepository.putSessionWindow(...) }`。

- [ ] **Step 4: SessionSyncCoordinator 改 emit effect**

`SessionSyncCoordinator.kt:822-839`：将 `if (cacheRepository != null) { scope.launch { cacheRepository.appendMessageIfSessionCached(...) } }` 替换为：
```kotlin
effects.tryEmitEffect(
    ControllerEffect.AppendMessageToCache(
        serverGroupFp = currentServerGroupFp(),
        sessionId = sessionId,
        message = updated,
        parts = emptyList(),
    )
)
```
（`updated` 为 SSE 新消息；`parts = emptyList()` 沿用原调用语义。）

- [ ] **Step 5: 补 SessionSwitcherTest 断言**

新增测试：`writeSessionWindow` 写入 → `appendMessageIfCached` 追加一条 → `peekSessionWindow` 返回的 `messages` 含追加项；未缓存的 session → `appendMessageIfCached` no-op（size 不变）。

- [ ] **Step 6: 跑 check.sh**

Run: `./scripts/check.sh`
Expected: PASS

- [ ] **Step 7: 记录 diff**

```bash
git rev-parse HEAD && git diff --stat
```

---

### Task 4: 删除 gap 机制 + 清理 gapMarkers

**Files:**
- Delete: `app/src/main/java/cn/vectory/ocdroid/ui/chat/GapFillCoordinator.kt`
- Delete: `app/src/main/java/cn/vectory/ocdroid/ui/chat/BackfillAlgorithm.kt`
- Delete: `app/src/main/java/cn/vectory/ocdroid/ui/chat/GapAwareMessageList.kt`
- Delete: `app/src/main/java/cn/vectory/ocdroid/data/cache/contract/GapMarker.kt`
- Delete: `app/src/main/java/cn/vectory/ocdroid/data/cache/contract/GapFillState.kt`
- Delete: `app/src/test/java/cn/vectory/ocdroid/ui/chat/GapFillCoordinatorTest.kt`
- Modify: `ui/AppCore.kt:130`（删 `gapFillCoordinator` 字段）
- Modify: `ui/AppCoreOrchestration.kt:581`（删透传）
- Modify: `ui/CatchUpActions.kt:71,164-217`（删 `gapFillCoordinator` 参数 + `detectGap` + `GapExists` 分支）
- Modify: `ui/ChatViewModel.kt:170`（删 `fillSingleGap` 调用）
- Modify: `ui/AppStateSlices.kt:275`（删 `ChatState.gapMarkers` 字段）
- Modify: 清理所有 `gapMarkers` 引用（见 Steps）

**Interfaces:** N/A

**Acceptance Criteria:**
- `T4-C1`: `rg -n "GapFillCoordinator|BackfillAlgorithm|GapAwareMessageList|GapMarker|GapFillState|GapDetection|withGaps|gapMarkers" app/src/main` 无命中
- `T4-C2`: `ChatState` 不再含 `gapMarkers` 字段
- `T4-C3`: `ChatMessageContent` 直接遍历 `messages` 渲染（不再 `withGaps`）
- `T4-C4`: catch-up 路径保留 NoGap merge（`mergeProbeIntoSlice`）补最新；`GapExists` 分支删除
- `T4-C5`: `./scripts/check.sh` 通过

- [ ] **Step 1: 删 gap 类文件**

```bash
rm app/src/main/java/cn/vectory/ocdroid/ui/chat/GapFillCoordinator.kt
rm app/src/main/java/cn/vectory/ocdroid/ui/chat/BackfillAlgorithm.kt
rm app/src/main/java/cn/vectory/ocdroid/ui/chat/GapAwareMessageList.kt
rm app/src/main/java/cn/vectory/ocdroid/data/cache/contract/GapMarker.kt
rm app/src/main/java/cn/vectory/ocdroid/data/cache/contract/GapFillState.kt
rm app/src/test/java/cn/vectory/ocdroid/ui/chat/GapFillCoordinatorTest.kt
```

- [ ] **Step 2: 删 AppCore `gapFillCoordinator` 字段 + 透传**

`AppCore.kt:130`：删除 `internal val gapFillCoordinator: GapFillCoordinator` 字段及其初始化。
`AppCoreOrchestration.kt:581`：删除向 `launchCatchUp` 透传 `gapFillCoordinator` 的实参。

- [ ] **Step 3: 改 CatchUpActions（删 gap 分支）**

`CatchUpActions.kt`：
- `:71`：删除 `gapFillCoordinator: GapFillCoordinator? = null` 参数。
- `:164`：删除 `val detection = BackfillAlgorithm.detectGap(...)` 及 `when (detection)` 分支结构。
- 保留 probe + `mergeProbeIntoSlice` + `onCacheWindow` + `onColdSnapshot`（原 NoGap 分支逻辑）作为 probe 成功后的唯一路径：
```kotlin
val fetched = page.items.map { it.info }
val fetchedParts = page.items.associate { it.info.id to it.parts }
val merged = mergeProbeIntoSlice(slices, fetched, fetchedParts)
slices.mutateChat { c ->
    c.copy(
        messages = merged.first,
        partsByMessage = merged.second,
        isLoadingMessages = false,
        staleNotice = false
    )
}
onCacheWindow(sessionId, snapshotCurrentWindow(slices, sessionId))
onColdSnapshot(sessionId)
```
- `:177, :227`：删除 `gapMarkers = emptyList()` 赋值（字段已删）。

- [ ] **Step 4: 删 ChatViewModel `fillSingleGap` 调用**

`ChatViewModel.kt:170`：删除 `core.gapFillCoordinator.fillSingleGap(...)` 及其 divider-tap 路由分支（divider 不再存在）。

- [ ] **Step 5: 删 `ChatState.gapMarkers` 字段 + 清理引用**

`AppStateSlices.kt:275`：删除 `val gapMarkers: List<GapMarker> = emptyList()` 字段及关联 KDoc（:267-278, :478）。

清理所有 `gapMarkers =` 赋值与读取（编译器会报错定位，逐处删除）：
- `MessageActions.kt:144`（删 `val srcGapMarkers = ...`）、`:286`（删 `gapMarkers = newGapMarkers` 及 `newGapMarkers` 计算）
- `AppAction.kt:449`
- `HostProfileController.kt:933, :986`
- `SessionSwitcher.kt:274`
- `AppCoreOrchestration.kt:547`
- `AppCore.kt:606`（Task 2 已删，确认无残留）

- [ ] **Step 6: 改 ChatMessageContent 渲染**

`ChatMessageContent.kt:231,649-650`：删除 `val gapMarkers = chatState.gapMarkers` 与 `messages.withGaps(gapMarkers)`。改为直接以 `messages` 构建 reverse-Layout 列表（保留现有 reverse + filter 逻辑，仅移除 `Entry` sealed 与 gap divider 渲染分支）。

- [ ] **Step 7: 跑 check.sh**

Run: `./scripts/check.sh`
Expected: PASS（编译报错则按报错清理残留 gapMarkers/GapMarker 引用）

- [ ] **Step 8: 记录 diff**

```bash
git rev-parse HEAD && git diff --stat
```

---

### Task 5: 删缓存管理 UI + SettingsViewModel 缓存路径 + CacheMaintenanceCoordinator + HostProfileController 清理

**Files:**
- Delete: `app/src/main/java/cn/vectory/ocdroid/ui/settings/CacheManagementSection.kt`
- Delete: `app/src/main/java/cn/vectory/ocdroid/data/cache/CacheMaintenanceCoordinator.kt`
- Delete: `app/src/test/java/cn/vectory/ocdroid/data/cache/CacheMaintenanceCoordinatorTest.kt`
- Modify: `ui/settings/SettingsScreen.kt:557`
- Modify: `ui/SettingsViewModel.kt`
- Modify: `ui/controller/HostProfileController.kt:871,896,910,974`
- Modify: `di/ControllerModule.kt:226`

**Interfaces:** N/A

**Acceptance Criteria:**
- `T5-C1`: `rg -n "CacheManagementSection|CacheMaintenanceCoordinator|cacheListing|lastSweep|cachedDataBytes|activeGroupCachedSessionCount|isCacheDegraded|refreshCacheListing|sweepNow|sweepAllGroups" app/src/main` 无命中
- `T5-C2`: `HostProfileController` 不再调用 `cacheRepository.clearAll()` 或 `deleteDatabase`；内存清理走 `ClearSessionWindowCache` effect（:910,:974 保留）
- `T5-C3`: `SettingsScreen` 不再渲染 `CacheManagementSection`
- `T5-C4`: `./scripts/check.sh` 通过

- [ ] **Step 1: 删文件**

```bash
rm app/src/main/java/cn/vectory/ocdroid/ui/settings/CacheManagementSection.kt
rm app/src/main/java/cn/vectory/ocdroid/data/cache/CacheMaintenanceCoordinator.kt
rm app/src/test/java/cn/vectory/ocdroid/data/cache/CacheMaintenanceCoordinatorTest.kt
```

- [ ] **Step 2: 删 SettingsScreen 调用**

`SettingsScreen.kt:557`：删除 `CacheManagementSection(vm = settingsVM, snackbarHostState = snackbarHostState, hideHeader = true)` 调用。

- [ ] **Step 3: 删 SettingsViewModel 缓存路径**

`SettingsViewModel.kt`：删除 `:67` `cacheRepository` 字段；删除状态 `cacheListing`(:292)、`lastSweep`(:304)、`cachedDataBytes`(:322)、`activeGroupCachedSessionCount`(:225)、`isCacheDegraded`(:272)；删除方法 `refreshCacheListing`(:333)、`clearSession`(:372)、`clearProject`(:387)、`clearAll`(:401)、`sweepNow`(:427)、`sweepAllGroups`(:459)、`clearLastSweep`(:312)；`disconnectWorkdir`(:493) 删除 `evictWorkdirInGroup` 调用（保留其余断开逻辑）。删除构造参数 `cacheRepository`。

- [ ] **Step 4: 改 HostProfileController**

`HostProfileController.kt:871,896`：删除 `cacheRepository.clearAll()` 调用 + `deleteDatabase(...)` 调用（runCatching 块一并清理）。`:910,:974` 的 `ClearSessionWindowCache` effect 发射保留（内存清理路径）。删除 `:99` `cacheRepository` 字段。

- [ ] **Step 5: 改 ControllerModule**

`ControllerModule.kt:226`：删除 `provideConnectionCoordinator` 的 `cacheMaintenanceCoordinator` 参数及转发。`SettingsViewModel` 的 `cacheRepository` 注入（若有）一并删除。

- [ ] **Step 6: 跑 check.sh**

Run: `./scripts/check.sh`
Expected: PASS

- [ ] **Step 7: 记录 diff**

```bash
git rev-parse HEAD && git diff --stat
```

---

### Task 6: 迁移 CachedSessionWindow + 删 CacheRepository/data/cache 包/DI/字段

**Files:**
- Move: `data/cache/contract/CachedSessionWindow.kt` → `ui/controller/CachedSessionWindow.kt`
- Delete: `data/cache/` 整包（除迁移出的 `CachedSessionWindow`）
- Delete: `app/src/test/data/cache/CacheRepositoryTest.kt`、`CacheRepositoryEvictionTest.kt`、`CacheDatabaseTest.kt`
- Delete: `app/src/test/di/CacheModuleTest.kt`
- Delete: `app/src/androidTest/data/cache/CacheDatabaseInstrumentedTest.kt`
- Delete: `di/CacheModule.kt`
- Modify: `di/ControllerModule.kt:167,182,195,207`
- Modify: `ui/AppCore.kt`、`ui/controller/SessionSyncCoordinator.kt`、`ui/SessionListActions.kt`、`ui/SessionViewModel.kt`（删 `cacheRepository` 字段/参数）

**Interfaces:** N/A

**Acceptance Criteria:**
- `T6-C1`: `rg -n "data.cache" app/src/main` 无命中（包引用清零）
- `T6-C2`: `CachedSessionWindow` 位于 `ui/controller/CachedSessionWindow.kt`，被 `SessionSwitcher`/`AppCore` 正常 import
- `T6-C3`: `rg -n "cacheRepository|CacheRepository|CacheModule|@Named.\"cacheDegraded\"." app/src/main` 无命中
- `T6-C4`: `./scripts/check.sh` 通过

- [ ] **Step 1: 迁移 CachedSessionWindow**

```bash
git mv app/src/main/java/cn/vectory/ocdroid/data/cache/contract/CachedSessionWindow.kt \
       app/src/main/java/cn/vectory/ocdroid/ui/controller/CachedSessionWindow.kt
```
更新文件内 `package` 为 `cn.vectory.ocdroid.ui.controller`。全局替换 import：`import cn.vectory.ocdroid.data.cache.contract.CachedSessionWindow` → `import cn.vectory.ocdroid.ui.controller.CachedSessionWindow`（`SessionSwitcher.kt`、`AppCore.kt`、`MessageActions.kt` 等）。

- [ ] **Step 2: 删 cacheRepository 字段/参数 + verifyFingerprint 第二调用点（调用点已在前序 task 清零）**

- `AppCore.kt:137`：删 `cacheRepository` 字段 + 构造参数。
- `AppCore.kt:362-382`：**删冷启动 seeded currentSessionId 自检块**（grilling·假设3：`verifyFingerprint` 第二调用点，plan 原遗漏——只删 SessionListActions:206 会导致 `FingerprintResult` 被删后此处编译失败）。删除整个 `if (seededSid != null) { appScope.launch { ... verifyFingerprint ... } }` 块。
- `SessionSyncCoordinator.kt:121`：删 `cacheRepository: CacheRepository? = null` 字段 + 构造参数。
- `SessionListActions.kt:92`：删 `cacheRepository: CacheRepository? = null` 参数 + `:198,:206,:336` 的 null 检查/`verifyFingerprint` 调用 + `currentSessionVerifyResult` 局部变量（:197-219, 336-357）。
- `SessionViewModel.kt:61`：删 `cacheRepository` 字段 + secondary 构造转发。

**MismatchEvicted 清理副作用说明**（grilling·假设3）：原 `verifyFingerprint` 的 `MismatchEvicted` 分支会驱逐 stale seeded session（删库行 + 清 chat + 重选会话）。移除后：冷启动时 seeded currentSessionId 直接用，由 `VerifyAndHydrate` peek 处理（内存空 → cold start REST，服务端是真相）。暖切回若服务端用相同 id 但不同 createdAt 重建 session，旧 LRU 窗口会被无校验注入，但被 `resetLimit=false` 最新尾 merge 限制在"较旧历史"范围，最终自愈。**接受此为已知行为**（YAGNI：不为内存缓存引入 createdAt 比对）。

- [ ] **Step 3: 删 DI + SettingsManager 白名单清理**

```bash
rm app/src/main/java/cn/vectory/ocdroid/di/CacheModule.kt
```
`ControllerModule.kt:167,182,195,207`：删除 `cacheRepository: CacheRepository` 参数及转发（HostProfileController、SessionSyncCoordinator 构造）。删除 `@Named("cacheDegraded")` 相关 provides/消费（含 `CacheModule:67` AtomicBoolean、`:101-102` provider、`:132/155` set 调用）。

**SettingsManager 白名单清理**（grilling·假设4：plan 原遗漏）：`SettingsManager.kt:866-871` 白名单 `val preservedKeys = connectionKeys + CACHE_DB_KEY` → 改为 `val preservedKeys = connectionKeys`；删除常量 `CACHE_DB_KEY`(:907) + 注释块(:866-870, :898-906)。同步清理 `SettingsManagerTest` 中白名单相关断言。

- [ ] **Step 4: 删 data/cache 包 + 测试**

```bash
rm -rf app/src/main/java/cn/vectory/ocdroid/data/cache
rm app/src/test/java/cn/vectory/ocdroid/data/cache/CacheRepositoryTest.kt
rm app/src/test/java/cn/vectory/ocdroid/data/cache/CacheRepositoryEvictionTest.kt
rm app/src/test/java/cn/vectory/ocdroid/data/cache/CacheDatabaseTest.kt
rm app/src/test/java/cn/vectory/ocdroid/di/CacheModuleTest.kt
rm -rf app/src/test/java/cn/vectory/ocdroid/data/cache
rm -rf app/src/androidTest/java/cn/vectory/ocdroid/data/cache
```

- [ ] **Step 5: 跑 check.sh**

Run: `./scripts/check.sh`
Expected: PASS（编译报错则按报错清理残留 `data.cache` / `cacheRepository` 引用）

- [ ] **Step 6: 记录 diff**

```bash
git rev-parse HEAD && git diff --stat
```

---

### Task 7: 依赖清理 + schemas 删除

**Files:**
- Modify: `app/build.gradle.kts:194,455-459,476`
- Modify: `gradle/libs.versions.toml`
- Delete: `app/schemas/` 整目录

**Interfaces:** N/A

**Acceptance Criteria:**
- `T7-C1`: `rg -n "room|sqlcipher|androidx.sqlite" app/build.gradle.kts gradle/libs.versions.toml` 无命中
- `T7-C2`: `app/schemas/` 目录不存在
- `T7-C3`: `./scripts/check.sh` 通过

- [ ] **Step 1: 删 build.gradle.kts 依赖**

`app/build.gradle.kts`：
- `:455-459`：删除 `androidx-room-runtime` / `-ktx` / `ksp(libs.androidx.room.compiler)` / `androidx-sqlite-framework` / `sqlcipher` 的 implementation 行。
- `:194`：删除 `ksp { arg("room.schemaLocation", ...) }` 配置。
- `:476`：删除 `testImplementation(libs.androidx.room.testing)`。

- [ ] **Step 2: 删 libs.versions.toml 条目**

`gradle/libs.versions.toml`：删除 `room`(:37)、`sqlite`(:38)、`sqlcipher`(:39) 版本定义；删除 `androidx-room-runtime`/`-compiler`/`-ktx`/`-testing`(:99-102)、`androidx-sqlite-framework`(:103)、`sqlcipher-android`(:107) lib 条目。

- [ ] **Step 3: 删 schemas**

```bash
rm -rf app/schemas
```

- [ ] **Step 4: 跑 check.sh**

Run: `./scripts/check.sh`
Expected: PASS

- [ ] **Step 5: 记录 diff**

```bash
git rev-parse HEAD && git diff --stat
```

---

### Task 8: 测试清理 + 最终验证

**Files:**
- Modify: 涉及 `cacheRepository` mockk 的残留测试（如 `SessionSwitcherTest`、`ChatViewModelTest`、`AppCoreTest` 等，按编译报错定位）
- Verify: 全仓库

**Interfaces:** N/A

**Acceptance Criteria:**
- `T8-C1`: `./scripts/check.sh --full` 通过（编译 + 单测 + lint + 覆盖率）
- `T8-C2`: `rg -n "Room|SQLCipher|androidx.sqlite|CacheRepository|data.cache" app/src` 无命中（除历史注释）
- `T8-C3`: `rg -n "gapMarkers|GapFillCoordinator|putSessionWindow|verifyAndLoad|appendMessageIfSessionCached" app/src/main` 无命中
- `T8-C4`: 现有 `SessionSwitcherTest`/`ChatViewModelTest` 的 peek/LRU 断言通过

- [ ] **Step 1: 清理测试残留**

按 `./scripts/check.sh` 编译报错，逐个清理测试中残留的 `cacheRepository` mockk / `CacheRepositoryImpl` 构造 / `data.cache` import。删除已无被测对象的测试文件（若 Task 6 未覆盖的）。

- [ ] **Step 2: 补 VerifyAndHydrate 回归测试**

在 `ChatViewModelTest` 或 `AppCoreTest` 中确认/新增：内存命中（先 `writeSessionWindow`）→ 切回触发 `VerifyAndHydrate` → slice 被填充为缓存内容；未命中 → 触发 `loadMessagesForEffect(resetLimit=true)`。用 fake repository 验证。

- [ ] **Step 3: 跑 full check**

Run: `./scripts/check.sh --full`
Expected: PASS（EXIT=0）

- [ ] **Step 4: 残留扫描**

```bash
rg -n "Room|SQLCipher|androidx.sqlite|CacheRepository|data\.cache|gapMarkers|GapFillCoordinator|putSessionWindow|verifyAndLoad|appendMessageIfSessionCached" app/src
```
Expected: 仅历史 KDoc 注释或无结果。

- [ ] **Step 5: 记录最终 diff**

```bash
git rev-parse HEAD && git diff --stat
```

---

## Criterion Ownership Matrix

| Criterion ID | Spec requirement | Owner task | Cross-task deps | Verification (command/test + expected) | Final-only? |
|---|---|---|---|---|---|
| T1-C1/C2 | §4.5 初始页40/历史页30 | Task 1 | — | `rg "initialMessagePageSize" ViewModelSupport.kt` → 40 | N |
| T1-C3/C4 | §4.5 删 gapProbe/重命名 catchUpProbe | Task 1 | — | `rg gapProbeMessagePageSize app/src` 无代码引用 | N |
| T2-C1/C2/C3 | §4.3 VerifyAndHydrate 改 peek | Task 2 | — | `rg "verifyAndLoad\|gapsOf" app/src/main` AppCore 无命中 + SessionSwitcherTest PASS | N |
| T3-C1/C2/C3/C4 | §4.3 makeCacheHook 删 Room + SSE→内存 LRU append | Task 3 | — | `rg "putSessionWindow\|appendMessageIfSessionCached" app/src/main` 无命中 + SessionSwitcherTest append PASS | N |
| T4-C1/C2/C3/C4 | §4.4 删 gap 机制 + gapMarkers | Task 4 | — | `rg "GapFillCoordinator\|withGaps\|gapMarkers" app/src/main` 无命中 | N |
| T5-C1/C2/C3 | §4.4 删缓存管理 UI + Coordinator | Task 5 | — | `rg "CacheManagementSection\|CacheMaintenanceCoordinator" app/src/main` 无命中 | N |
| T6-C1/C2/C3 | §5 迁移 + 删 CacheRepository/data/cache/DI/verifyFingerprint/白名单 | Task 6 | T2,T3,T5 | `rg "data\.cache\|CacheRepository\|verifyFingerprint\|CACHE_DB_KEY" app/src/main` 无命中 | N |
| T7-C1/C2 | §5 依赖清理 + schemas | Task 7 | T6 | `rg "room\|sqlcipher" app/build.gradle.kts` 无命中 | N |
| T8-C1 | §6.1 check.sh --full 通过 | Task 8 | T1-T7 | `./scripts/check.sh --full` EXIT=0 | Y |
| T8-C2/C3 | §6.2/6.3 无残留依赖/引用 | Task 8 | T1-T7 | `rg` 残留扫描无命中 | Y |
| T8-C4 | §6.4/6.5 LRU + hydrate 行为 | Task 8 | T2 | SessionSwitcherTest/ChatViewModelTest PASS | Y |
| — | §6.2 构建产物无 Room/SQLCipher 依赖 | Task 7+8 | — | `rg` build.gradle/libs 无命中 | Y |

## Self-Review

1. **Spec coverage**：§3 目标 1-5 → Task 1/2/3/4/5/6/7 全覆盖；§4.3 复用 SessionSwitcher → Task 2/3；§4.5 常量 → Task 1；§4.4 删 gap/UI → Task 4/5；§5 删除清单 → Task 4/5/6/7；§6 成功标准 → Task 8 + 各 task criteria。无遗漏。
2. **Placeholder scan**：无 TBD/TODO；删除步骤给清单 + 命令；改造步骤给代码。
3. **Type consistency**：`CachedSessionWindow` 迁移后包名 `cn.vectory.ocdroid.ui.controller`，Task 6 Step 1 明确 import 替换；`peekSessionWindow`/`writeSessionWindow`/`clearAllCached` 签名沿用现有（Task 2/3/5 一致）；`catchUpProbePageSize` 在 Task 1 定义、CatchUpActions 引用一致。
4. **Acceptance observability**：每条 criterion 为 `rg` 命令预期 / `check.sh` EXIT / 测试 PASS，可独立验证。
5. **顺序依赖**：Task 1 独立；Task 2/3 移除 cacheRepository 部分调用；Task 4 删 gap（含 gapMarkers，AppCore:606 已在 Task 2 删）；Task 5 移除缓存 UI/Coordinator 调用；Task 6 在调用点清零后删接口/包/字段；Task 7 依赖清零后删依赖；Task 8 最终验证。每 task 后编译通过。
6. **Grilling 修正**（阶段3，4 个核心假设验证）：假设1（数据完整性）→ Task 3 改为"SSE→内存 LRU append via ControllerEffect"，防 >40 条新消息丢中间（原"直接删缓存写"方案有缺陷）；假设2（线程安全）→ 成立，appScope=Main.immediate，Task 2 仅补 `peekSessionWindow` docstring；假设3（verifyFingerprint）→ Task 6 补删 `AppCore:362-382` 第二调用点 + 接受 `MismatchEvicted` 自愈（YAGNI 不加 createdAt 比对）；假设4（CacheKeyStore/cacheDegraded）→ Task 6 补 `SettingsManager` CACHE_DB_KEY 白名单清理 + cacheDegraded 完整删除链。
