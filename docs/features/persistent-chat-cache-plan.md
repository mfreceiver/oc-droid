# 持久化聊天记录 + 已连接项目共享 — 实施计划

> **状态**：v4 实施就绪稿。v1 grilling 8 轮 + gpter 3 轮（9.1 偏宽）。v2 按 7 模型 round-1 并集（~25 项）。v3 按 round-2 收敛（switchTo async 边界等）。**v4 按 round-3 收敛折入评委提供的修复代码**：verifyAndLoad 单 @Transaction（消除 TOCTOU，glmer I-2）+ VerifyAndHydrate ordering 修正（Step 7 移除 LoadMessages，handler 独占，3 模型收敛）+ EvictGroup 命名统一 + makeCacheHook + DailySweepReport（alive 内部化）+ currentServerGroupFp DI provider。
> **round-3 终审**：glmer **8.5 PASS** / momo **8.7 COND-PASS** / freegpt **8.1 N-F**（gpter 引擎不可用）。设计层无阻塞，代码骨架齐全，可进入 Phase 0 实施。
> **评审存档**：`.opencode/runs/reviews/2026-07-06/persistent-cache-7model-comparison{,-r2,-r3}.json`。
> **项目约束**：AGENTS.md 硬规则（每次改动 `./scripts/check.sh`；版本号走 `release.sh`；主线 main；recon 基线 = R-19 发版后 `a32dd69`，单测 1848，覆盖率 line 61.4% / branch 57.8%，kover floor 60/56）。

**Goal**：在 ocdroid 首次引入**本地加密持久化**的聊天记录缓存 + 跨连接（同一 opencode 服务器的多个接入点）共享已连接项目配置；重连后按 gap 算法补齐到最新；提供存储管理 UI。

**Architecture**：内容寻址缓存（按 `(serverGroupFp, sessionId)` 复合键控，id+createdAt 双指纹复核）+ SQLCipher 加密 Room DB + 非连续消息模型（slice + gap marker）支持多 gap 补齐 + 每日孤儿扫描与三重淘汰（LRU-50/30d/7d）。**不维护人工服务器等价关系**，靠会话内容匹配自然关联两个接入点。

**Tech Stack**：Kotlin / Room（+ SQLCipher via SupportFactory）/ EncryptedSharedPreferences（密钥，已有）/ Hilt / Coroutines + Flow / Compose（管理 UI）/ 既有 SSE + REST 基础设施。

---

## 0. 全局约束（每个 Phase 隐含继承）

- **不手改** `app/build.gradle.kts` 的 versionCode/versionName（走 `release.sh`）。
- 每次改 Kotlin/资源后必跑 `./scripts/check.sh`（--full 在 Phase gate）。
- **备份策略**（dser/momo 裁决：`allowBackup=false` 与 `fullBackupContent` 互斥，且 false 过度）：**保留 `android:allowBackup="true"`**，在 `res/xml/backup_rules.xml` 与 `res/xml/data_extraction_rules.xml` **同时**加 `<exclude domain="database" path="."/>`（覆盖 cache DB + `-wal`/`-shm`），保留现有 sharedpref 排除。SQLCipher DB 即使被备份也无法在他机解密（密钥在 Keystore 不可导出），排除规则是纵深防御。
- **缓存复合键控**（gpter/freegpt 共识）：主键一律复合 `(serverGroupFp, sessionId)` 与 `(serverGroupFp, sessionId, messageId)`，**不用裸 sessionId/messageId 全局 PK**（session id 是 `ses_xxxx` branded string 非 UUID，克隆/重置服务器可能碰撞）。裸 `(sessionId, createdAt, directory)` 仅作跨 profile 归并候选指纹，归并确认前不跨组读正文。
- **指纹复核** `(sessionId, createdAt=epoch ms)`，不匹配→清该会话+冷启动+`DebugLog.w`。`createdAt` 处理：服务端 `Session.time.created` 实为 `Long?` 可空（momo 实测）；**`createdAt == null` → 跳过复核，视为冷启动**（不触发 evict）。
- 服务端消息 API **只有 `before`**（已证，PR #8535 未合并，游标 = `base64url({id,time_ms})` 响应头返回）；"补齐"= backward page from newest + dedup；**缓存真实价值=即时显示 + 老历史持久化**，不省流量。
- **内存 SessionWindowCache 也改 group 维度**（freegpt）：key 从裸 sessionId 改为 `CacheWindowKey(serverGroupFp, sessionId)`，否则"同组不清/异组清当前组"在现有 `MutableMap<String,...>` 结构不可实现。
- 不破坏现有 1848 单测；每个 Phase 的 gate 含 koverVerify 不低于 60/56。

### 时间戳定义（每个 CachedSessionEntity 两个，语义锁定）

| 字段 | 语义 | 驱动 | 更新时机 |
|---|---|---|---|
| `newestCachedAt` | **该会话已缓存消息里最新一条的时间**（内容新鲜度，**非**"用户上次打开"） | LRU-50 淘汰序 + 30d 年龄规则 | 仅当缓存入一条更新的消息时 = `max(existing, newMsg.time)`；用户单纯打开不刷新 |
| `lastVerifiedAt` | 上次与服务器确认该会话**既未删除也未归档**的时刻 | 7d 服务器废弃规则 + 孤儿扫描 | 每次该会话出现在服务器存活集合里时 = now |

> **命名**：v1 的 `lastUsedAt` 已按多模型共识**改名为 `newestCachedAt`**（消除"用户上次打开"歧义）。内存 LRU(12) 与持久 LRU-50 是两层独立淘汰，数值不同合理。

---

## 1. 文件结构（新建 + 改动，按职责）

### 新建（data 层 — 持久化）
| 文件 | 职责 |
|---|---|
| `data/cache/CacheDatabase.kt` | Room `@Database`（SQLCipher SupportFactory 注入），entities: CachedSessionEntity / CachedMessageEntity / GapMarkerEntity（**无 CachedPartEntity**——parts 进 CachedMessageEntity JSON blob，dser/glmer 共识简化） |
| `data/cache/Entities.kt` | `@Entity`：**CachedSessionEntity(primaryKeys=[serverGroupFp, sessionId], createdAt:Long?, newestCachedAt, lastVerifiedAt, workdir)** / **CachedMessageEntity(primaryKeys=[serverGroupFp, sessionId, messageId], time, role, parts JSON)** / **GapMarkerEntity(gapId PK, [serverGroupFp,sessionId] FK, lowerAnchorMessageId, upperBoundaryMessageId, nextBeforeCursor, state[idle/filling/exhausted/error], createdAt, updatedAt)** ← 复合 PK + per-gap 游标 |
| `data/cache/CacheDao.kt` | `@Dao`：含淘汰 SQL（`DELETE ... WHERE serverGroupFp=? AND (sessionId,...) NOT IN (SELECT ... ORDER BY newestCachedAt DESC LIMIT 50)`）+ 年龄过滤 + per-gap cursor 操作 |
| `data/cache/CacheRepository.kt` | 对外门面（签名见 §2） |
| `data/cache/CacheKeyStore.kt` | 从 EncryptedSharedPreferences 取/生成 DB 密钥（首次随机，永不轮换）；**`cache_db_key` 加入 `clearAllLocalData` 的 preservedKeys 白名单**（避免重置时丢密钥导致 DB 打不开） |
| `di/CacheModule.kt` | Hilt `@Module`：CacheDatabase（SupportFactory）+ CacheRepository `@Singleton`；**DB open 失败 → destructive reset**（G4：catch → delete DB 文件 + key → 视同空缓存，不崩） |

### 新建（ui 层 — gap 模型 + 算法 + 管理 UI）
| 文件 | 职责 |
|---|---|
| `ui/chat/GapAwareMessageList.kt` | 非连续消息模型：`sealed interface Entry { Message; GapMarker(gapId, fillState) }`；支持 ≥1 gap；**替代现有 `ChatState.gapInfo: GapInfo?`**（见 Phase 2） |
| `ui/chat/GapFillCoordinator.kt` | 补齐状态机：probe(5)→detect→fill(50-step)；**session 级 Mutex**（非仅 gapId 级，因跨 gap overlap，freegpt）；近 gap 10 条预取 |
| `ui/chat/BackfillAlgorithm.kt` | 纯函数：`detectGap` / `stepCoversAnchor` / `shouldProbe`（G6）/ `extractMessages(CachedSessionLayout)` |
| `ui/settings/CacheManagementSection.kt` | 需求 3 UI |

### 改动（既有）
| 文件 | 改动点 |
|---|---|
| `data/model/HostProfile.kt` + `HostProfileStore.kt` | **（前移 Phase 0）新增 `serverGroupFp: String = ""` 字段**；旧 JSON 反序列化默认 `= id`；Store 加 `mergeServerGroup(from, into)` / `splitProfileToOwnGroup(profileId)` / `profilesInGroup(fp)`；import/export 默认不导出内部 group（导入新建独立组） |
| `ui/controller/ControllerEffect.kt` | **（maxer B2 + v4 round-3）sealed class 新增** `VerifyAndHydrate(serverGroupFp, sessionId, createdAt)` + `EvictSession(serverGroupFp, sessionId)` + `EvictGroup(serverGroupFp)`（**禁用 ClearGroup 命名，统一 EvictGroup**，freegpt+maxer）；AppCore 5 域 dispatcher 加分支路由（VerifyAndHydrate→`cacheRepository.verifyAndLoad` 单事务；EvictSession→内存+持久；EvictGroup→`clearMemoryForGroup`+`evictGroup`） |
| `ui/controller/SseSyncState.kt` | **（maxer B4/kimo）新增 `sessionsEverColdSnapshotted: Set<String>` 字段**（G6 SSE-保障判定依据；现有只有全局 `sseHasConnectedOnce`） |
| `ui/controller/SessionSwitcher.kt` | SessionWindowCache key 改 `CacheWindowKey(serverGroupFp, sessionId)`；`clearSessionWindowCache` 拆为 `evictSession(group,id)` / `clearMemoryForGroup(group)` / `clearAllCached()`；`captureCurrentSessionWindow` 是 **private**（在 `switchTo` 内部 line 152 调），镜像持久化在 switchTo capture 步 + `onCacheWindow` 回调（见下） |
| `ui/MessageActions.kt` + `ui/CatchUpActions.kt` | **（maxer B3）4 个 `onCacheWindow` 回调点**（MessageActions.kt:28/305 + CatchUpActions.kt:52/216）hook `scope.launch { cacheRepository.putSessionWindow(...) }`（fire-and-forget，IO 异常仅 `DebugLog.e`，不阻塞 LRU 写）；调用方（AppCoreOrchestration:334/372、ChatViewModel:70/88/99）传入双 hook |
| `ui/SessionMutationActions.kt` | **（freegpt）新增到 Files**：`launchSetSessionArchived`（含子树 ids 循环 :77-80，每个 subtree id emit EvictSession）+ `launchDeleteSession`（REST onSuccess emit EvictSession） |
| `ui/controller/SessionSyncCoordinator.kt` | 归档分支（session.updated archived :389-414）emit EvictSession；**删除分支 `session.deleted` 当前不存在**（7/7 实测）→ 不虚构，删除走 SessionMutationActions REST 路径 + Phase 3 sweepOrphans 兜底；`message.updated` insert-if-absent 成功后异步写 DB（maxer I11，否则杀进程丢消息） |
| `ui/controller/HostProfileController.kt` | `selectHostProfile`：**（kimo）`hostProfileStore.select()` 之前快照 previous serverGroupFp**，再与 target 比；同组→不清，异组→emit EvictGroup(previous)；`purgePerHostState` 改为按 serverGroupFp 隔离（同组保留）；`resetLocalDataAndResync` 调 `cacheRepository.clearAll()` + `context.deleteDatabase()` + 删 `-wal`/`-shm`（先删 DB 再清 prefs，maxer B1） |
| `ui/ConnectionActions.kt`（applySavedSettings）+ `ConnectionCoordinator.kt` | **（dser/maxer）applySavedSettings 是 Phase 5 迁移触发点**（冷启动执行一次，读旧 key 写新 serverGroupFp key + 迁移标记位 `cache_migration_v1_done`）；applySavedSettings 中 `mutateChat{currentSessionId=...}` **之前**先 `verifyFingerprint`（maxer H7），不匹配则 `currentSessionId=null`（走 first-select）；每日 sweep 钩子在 `ConnectionCoordinator.testConnection` healthy 分支 + `lastSweepEpoch_<fp>` 24h 去重 |
| `ui/SessionListActions.kt` `launchLoadSessions` | 拉到会话列表后做 id+createdAt 复核；**（gpter/freegpt）归并靠 loadSessions 非 SSE**（SSE 单 workdir，dser 实测） |
| `AndroidManifest.xml` + `res/xml/backup_rules.xml` + `res/xml/data_extraction_rules.xml` | 保留 `allowBackup="true"`；两份 XML 都加 `<exclude domain="database" path="."/>` |
| `gradle/libs.versions.toml` + `build.gradle.kts` | 加 `androidx.room` + `androidx.sqlite` + `net.zetetic:sqlcipher-android`（Phase 0 前用 librarian 核版本）；`proguard-rules.pro` 加 SQLCipher keep |

---

## 2. 关键接口契约（跨 Phase 共享，签名锁定）

```kotlin
// CacheRepository — 对外门面（Phase 1 产出）
interface CacheRepository {
    // 写：会话窗口落盘，serverGroupFp 复合键；window 复用既有 CachedSessionWindow 类型
    suspend fun putSessionWindow(serverGroupFp: String, sessionId: String, createdAt: Long?,
                                 workdir: String, window: CachedSessionWindow)
    // 原子 verify+load（v4 glmer I-2，单 Room @Transaction 消除两步 TOCTOU）：switchTo/VerifyAndHydrate handler 唯一调用此方法；
    //   Verified(window)      → 复用，刷 lastVerifiedAt=now，返回正文供 hydrate
    //   UnknownColdStart      → createdAt==null/metadata 缺失/缓存空：不 evict 不 hydrate，冷启动 REST
    //   MismatchEvicted       → evictSession + 冷启动
    suspend fun verifyAndLoad(serverGroupFp: String, sessionId: String,
                              createdAt: Long?): HydrateResult  // Phase 1：无 gap 等价物（CachedSessionWindow）
    // Phase 2 升级：返回带 gap 的非连续布局 + 转换辅助
    suspend fun loadSessionLayout(serverGroupFp: String, sessionId: String): CachedSessionLayout?
    // 独立 verify（仅供 loadSessions 归并路径，不取正文）
    suspend fun verifyFingerprint(serverGroupFp: String, sessionId: String, createdAt: Long?): FingerprintResult
    // 精确删一条（带 group）
    suspend fun evictSession(serverGroupFp: String, sessionId: String)
    // 清整组（异组切换/reset）。命名统一 EvictGroup（effect 与 repo 一致，v4 freegpt+maxer）
    suspend fun evictGroup(serverGroupFp: String)
    // 全清（仅手动全清按钮）
    suspend fun clearAll()
    // 低层原语：仅当 caller 能证明 alive 集合完整时调用（Phase 3 由 CacheMaintenanceCoordinator 调）
    suspend fun sweepOrphansWithCompleteAliveSet(serverGroupFp: String, aliveSessionIds: Set<String>): EvictionReport
    // mark-only 降级（alive 不完整时）：只刷已确认存活 lastVerifiedAt，未见标疑似不删
    suspend fun markSeenAliveOnly(serverGroupFp: String, seenAliveSessionIds: Set<String>)
    // 淘汰：LRU-50(per fp) + newestCachedAt>30d + lastVerifiedAt>7d
    suspend fun applyEvictionPolicy(): EvictionReport  // = data class(evictedCount, keptCount, orphanIds)
    // 高层每日 sweep 入口（v4 freegpt 建议2）：alive 完整性归此方法内部（CacheMaintenanceCoordinator 全量枚举 + 24h 去重 + 不完整降级 mark-only），**caller 不传 aliveSessionIds**
    suspend fun dailySweepIfNeeded(serverGroupFp: String): DailySweepReport
    // per-gap 游标（Phase 2）
    suspend fun openGap(serverGroupFp: String, sessionId: String, lowerAnchorMessageId: String,
                       upperBoundaryMessageId: String, initialNextBeforeCursor: String): String
    suspend fun appendOlderSlice(gapId: String, older: List<Message>, returnedCursor: String?)
    suspend fun setGapState(gapId: String, state: GapState)
    suspend fun resolveGap(gapId: String)
    suspend fun gapsOf(serverGroupFp: String, sessionId: String): List<GapMarker>
}
// v3 三态指纹结果（freegpt #1）
sealed interface FingerprintResult {
    data object Verified : FingerprintResult
    data object UnknownColdStart : FingerprintResult  // createdAt null/metadata 缺失：不 evict 不 hydrate
    data object MismatchEvicted : FingerprintResult   // evict + 冷启动
}
// v4 原子 verify+load 结果（glmer I-2，单事务消除 TOCTOU）
sealed interface HydrateResult {
    data class Verified(val window: CachedSessionWindow) : HydrateResult
    data object UnknownColdStart : HydrateResult
    data object MismatchEvicted : HydrateResult
}
// v4 每日 sweep 报告（freegpt 建议2，alive 完整性归内部）
data class DailySweepReport(
    val serverGroupFp: String,
    val completeness: AliveCompleteness,  // Complete / Incomplete
    val verifiedAliveCount: Int,
    val evictedSessionIds: List<String>,
    val suspiciousSessionIds: List<String>
)
enum class AliveCompleteness { Complete, Incomplete }
// 类型别名：CachedWindow = CachedSessionWindow（复用 AppStateSlices.kt:285 现有类型，kimo B2）
// HostProfileStore 公开 API（v3 maxer 细节-3，完整签名）：
//   fun mergeServerGroup(from: String, into: String)  // from/into = serverGroupFp，单向
//   fun splitProfileToOwnGroup(profileId: String)
//   fun profilesInGroup(serverGroupFp: String): List<HostProfile>
// HostProfile.serverGroupFp nonblank invariant（v3 freegpt #2）：decode 后 normalize blank→id，禁止持久化 blank
// CachedSessionLayout（Phase 2 产出）见下
data class CachedSessionLayout(
    val serverGroupFp: String, val sessionId: String,
    val entries: List<GapAwareMessageList.Entry>,  // Message | GapMarker
    val oldestCursor: String?, val newestMessageId: String?
) {
    fun toCachedSessionWindow(): CachedSessionWindow  // Phase 1 兼容（无 gap 时）
}

object BackfillAlgorithm {
    fun anchorOf(cached: List<Message>): Message? = cached.dropLast(3).lastOrNull()
    fun extractMessages(layout: CachedSessionLayout): List<Message>  // kimo：从 entries 提取供 anchorOf
    fun detectGap(anchor: Message?, fetched5: List<Message>): GapDetection
    sealed class GapDetection {
        data object NoGap : GapDetection()
        // gpter：携带完整 boundary/cursor，非仅 newestFetched
        data class GapExists(val lowerAnchorMessageId: String, val upperBoundaryMessageId: String,
                             val initialNextBeforeCursor: String) : GapDetection()
    }
    fun stepCoversAnchor(step: List<Message>, anchor: Message?): Boolean
    // G6：SSE 保障判定（依赖 SseSyncState.sessionsEverColdSnapshotted）
    fun shouldProbe(sessionId: String, currentWorkdir: String,
                    sessionsEverColdSnapshotted: Set<String>, sseCurrentWorkdir: String?): Boolean
}
```

---

## 3. Phase 分解（每 Phase 独立可测 + gate）

### Phase 0：加密持久化地基 + HostProfile serverGroupFp 前移

**目标**：可用加密 Room DB（复合 PK）；密钥就位；HostProfile 加 serverGroupFp 字段 + 迁移；备份排除 DB。

**Files**：`CacheDatabase.kt` / `Entities.kt`（CachedSessionEntity 复合 PK + CachedMessageEntity 复合 PK + parts JSON blob）/ `CacheKeyStore.kt`（preservedKeys 白名单）/ `CacheModule.kt`（DB open 失败 destructive reset）/ **`HostProfile.kt` + `HostProfileStore.kt`（serverGroupFp 字段 + 旧 JSON 迁移默认=id + merge/split API）** / `AndroidManifest.xml` + 两份 backup XML / `libs.versions.toml` + `proguard-rules.pro`。

**关键点**：
- SQLCipher via SupportFactory（`net.zetetic:sqlcipher-android`，Phase 0 前 librarian 核版本 + R8 keep）。
- **测试策略**（momo/maxer）：**Robolectric 跑不了真 SQLCipher（native lib）**。单元测试用 in-memory Room（不挂 SupportFactory，跑 schema + DAO）；真 SQLCipher 集成走 `connectedDebugAndroidTest`（仅模拟器，per AGENTS.md）。
- Message.parts JSON：`Json { ignoreUnknownKeys = true; encodeDefaults = true }`（maxer H10，防服务端加 part type 反序列化炸）。
- **HostProfile.serverGroupFp nonblank invariant（v3 freegpt #2）**：字段 `val serverGroupFp: String = ""` 仅作反序列化兼容；`HostProfileStore.decodeProfiles()` 成功 decode 后**必须 normalize**：`if (serverGroupFp.isBlank()) copy(serverGroupFp = id)`；`save()/profiles()` 返回前保证 nonblank；新建/导入 profile 默认 `serverGroupFp = id`。测试：旧 JSON 多 profile decode 后每个 `serverGroupFp == id` 且互不相同（防全落 blank group 被误归并）。

**测试**：`CacheDatabaseTest`（Robolectric in-memory Room）schema/密钥往返/CRUD；`HostProfileStoreTest` 旧 JSON 反序列化默认 serverGroupFp=id。

**Gate**：check.sh --full（新依赖 resolved）；emulator androidTest 验真 SQLCipher 打开库。

---

### Phase 1：会话内容缓存 + 指纹复核 + 精确淘汰（group-aware）

**目标**：消息跨重启持久化；**switchTo 先 verifyFingerprint 后 hydrate 正文**（freegpt 隐私）；归档/删除信号精确删该会话（emit EvictSession effect）；切 host 内容同组感知（select 前 snapshot previous）。

**Files**：`CacheDao.kt`（完整）+ `CacheRepository.kt`（含 `verifyAndLoad` 单事务 + `makeCacheHook`）+ `ControllerEffect.kt`（+VerifyAndHydrate/EvictSession/EvictGroup）+ AppCore dispatch 分支（VerifyAndHydrate handler）+ `SessionSwitcher.kt`（group key + 拆分 + switchTo 改发 VerifyAndHydrate 不发 LoadMessages）+ `MessageActions.kt`/`CatchUpActions.kt`/`SessionViewModel.kt`（6 onCacheWindow hooks）+ `SessionMutationActions.kt`（archive/delete emit EvictSession）+ `SessionSyncCoordinator.kt`（archived 分支 emit EvictSession + message.updated 写 DB）+ `HostProfileController.kt`（select snapshot + purgePerHostState group 隔离）+ `SessionListActions.kt`（loadSessions 后复核）+ `ConnectionActions.kt`（applySavedSettings verify-before-mutate + Phase 5 迁移触发）+ `ControllerModule.kt`（currentServerGroupFp @Provides）。

**关键点**：
- **switchTo async 边界（v4 头号，3 模型 round-3 收敛 momo V3-B1 + glmer I-1 + freegpt 阻塞1）**：`switchTo` **整体保持非 suspend**（保护 8 步同步 FIFO effect 发射——代码 5 处注释警告 `scope.launch` 包裹会 race/破坏顺序，**禁改 switchTo 签名为 suspend**）。verify-before-hydrate 改为：
  - Step 3 **不再同步读 LRU 注入正文**，改为同步发射新 effect `VerifyAndHydrate(serverGroupFp, sessionId, createdAt)`。
  - **Step 7 移除 `LoadMessages`**（v4 修正——v3 此处"仍同步发射 LoadMessages + resetLimit 移入 handler"自相矛盾，会双重加载）。`LoadSessionStatus` + `LoadChildSessions` 保留同步发射（独立于消息加载顺序）。
  - AppCore dispatch handler 在 `appScope.launch` 内调 `cacheRepository.verifyAndLoad(...)`（**v4 单 @Transaction，消除 TOCTOU**）：`Verified(window)` → `mutateChat` 注入正文 + `loadMessagesForEffect(sessionId, resetLimit=false)`；`UnknownColdStart/MismatchEvicted` → `loadMessagesForEffect(sessionId, resetLimit=true)`。handler 入口先校验 `effect.sessionId == chatFlow.value.currentSessionId`（用户可能已切走），不匹配 `return@launch`。**LoadMessages 由 handler 唯一调度，禁 switchTo 同步发。**
  - 完整 dispatch handler 代码见 handoff 文档 Phase 1 提示词。
- **applySavedSettings async（v3）**：`applySavedSettings` 是非 suspend `internal fun`（ConnectionActions.kt:16），在 `AppCore.init` 同步调。其内的 `verifyFingerprint`（suspend）**不得用 runBlocking**（阻塞主线程）。方案：把 verify hoist 到 `AppCore.init` 的 `appScope.launch`（init 块已是协程上下文），applySavedSettings 同步部分只做 ES 元数据 seed；verify 仅作"缓存自洽检查"（防 DB 损坏），非归并触发（归并靠 loadSessions，服务器数据到位后）。冷启动闪屏防护：verify 完成前不 hydrate 持久正文（走 VerifyAndHydrate effect）。
- **select 前 snapshot 4-step（v3 momo N-B1）**：`selectHostProfile` 时序 = ① 读 `val previousFp = currentHostProfile().serverGroupFp`（**在 select 之前**，因 `hostProfileStore.select()` 有副作用改 currentProfile）→ ② `hostProfileStore.select(profileId)` → ③ 读 `val targetFp = profile.serverGroupFp` → ④ 比较：同组→不清，异组→emit `EvictGroup(previousFp)`。**`select()` 看似纯查询实则改状态，是最易踩坑点。**
- **同组切换字段分类（v3 glmer I2）**：`purgePerHostState` 清 8 类状态，"同组保留"须分两类：
  - **同服务器数据（同组保留）**：sessions / directorySessions / unread / cache / recentWorkdirs / disabled_models（已按复合键隔离）。
  - **per-profile UX（仍 reset）**：openSessionIds / draft / currentWorkdir（两接入点各有打开标签/草稿，"不清"会跨 profile 泄漏）。
- **current group provider（v4，3 模型收敛 glmer I3 + freegpt #3 + maxer；DI 接线 freegpt 建议3）**：所有需发 `EvictSession/EvictGroup` 的 helper/controller（SessionMutationActions / SessionSyncCoordinator / SessionSwitcher）当前构造无 `hostProfileStore`。注入 `currentServerGroupFp: () -> String`，由 **`ControllerModule` 统一 `@Provides`**（`hostProfileStore.currentProfile().serverGroupFp.ifBlank { profile.id }`），禁止各 controller 自行推导/裸 sessionId/空 group。
- **6 个 `onCacheWindow` hook `putSessionWindow`（v4 glmer I-3 + freegpt）**：MessageActions.kt:28/305 + CatchUpActions.kt:52/216 + SessionViewModel.kt:309（v3 maxer 细节-1 补漏）。onCacheWindow 签名**不变**（仍 `(sessionId, window)`，避免波及 6 声明+6 调用方）；hook 闭包**创建时捕获 serverGroupFp**（不在 launch 体内重取），`createdAt`/`workdir` 从 `sessionListFlow` slice 查询（主线程同步安全）。建议在 AppCore 抽 `makeCacheHook(fp): (String, CachedSessionWindow) -> Unit` 复用，避免 6 处重复。fire-and-forget `scope.launch`，IO 异常仅 `DebugLog.e`。

**清缓存调用点矩阵**（7 模型 round-1 修订，完整枚举）：

| 场景 | 触发点 | 新行为 |
|---|---|---|
| 用户归档 session（含子树） | `SessionMutationActions.launchSetSessionArchived`（subtree ids :77-80） | 对每个 subtree id emit `EvictSession(group,id)` |
| SSE 归档 session | `SessionSyncCoordinator` session.updated archived 分支(:389) | emit `EvictSession(group,id)` |
| 用户删除 session | `SessionMutationActions.launchDeleteSession`（REST onSuccess） | emit `EvictSession(group,id)` |
| ~~SSE 删除 session~~ | ~~session.deleted 分支~~ | **当前代码无此分支**（7/7 实测）；删除走 REST + Phase 3 sweepOrphans 兜底（实现前用 librarian 核服务端是否发此事件） |
| 切到**同组** host | `selectHostProfile`（select 前 snapshot，同 serverGroupFp） | **不清**（仅切内存视图） |
| 切到**异组** host | `selectHostProfile`（异 serverGroupFp） | emit `EvictGroup(previous)`（清当前组内存 LRU；**不核武器清所有组持久 DB**） |
| 删除当前 host profile | `deleteHostProfile(wasCurrent)` | 该 group 无其它 profile 引用→清该 group；有→不清 |
| 删除**非当前** host profile | `deleteHostProfile`（非当前） | group 仍有 profile 引用→不清；无→可清或标 orphan |
| 手动改 server URL | `configureServer` | 视为异组切换 |
| reset local data | `resetLocalDataAndResync` | `cacheRepository.clearAll()` + `context.deleteDatabase()` + 删 wal/shm（先 DB 后 prefs，maxer B1） |
| 全局冷启动刷新 | `AppCoreOrchestration.performGlobalColdStartRefresh`(:310) | **仅清内存 LRU（全部 group），不清持久 DB**（冷启动刷新为拉最新，持久缓存作 fallback） |
| 切走会话（capture） | `SessionSwitcher.switchTo` 内部 `captureCurrentSessionWindow`(:152 private) + `writeSessionWindow`(onCacheWindow 回调) | 镜像写持久缓存（4 callback hook） |
| Phase 4 手动清单个 session | `CacheManagementSection.clearSession` | `evictSession(group,id)` |
| Phase 4 手动清项目/workdir | `CacheManagementSection.clearProject` | 清该 group+workdir；**不得 clearAll** |
| 手动全清（req 3） | Phase 4 全清按钮 | `clearAll()`（唯一核武器） |

**测试**：指纹匹配/不匹配/null createdAt 5 case；归档子树→每个 id 删；切同组保留/异组 EvictGroup；switchTo 不展示未验证正文（隐私回归）；verifyAndLoad 单事务原子性（并发 EvictSession 不产生 TOCTOU）。

**Gate**：check.sh --full + koverVerify ≥60/56 + gpter 评审。

---

### Phase 2：非连续消息模型 + gap 补齐算法（替代现有 GapInfo）

**目标**：消息列表支持 slice + gap marker（≥1 gap）；**替代现有 `GapInfo`+`launchCatchUp`+`launchCloseGap`**（glmer B1——7 模型只有 glmer 看穿这是替代非新建）；补齐状态机（probe 5→detect→50-step，session 级 mutex）；UI 显式标注 + 手动点补。

**现有实现盘点（glmer B1，必须迁移）**：
- `GapInfo(anchorNewestId, tailOldestId, tailOldestCursor, open)`（`AppStateSlices.kt:307`）单 gap 内存模型 → **废除 `ChatState.gapInfo` 字段**，迁移到 `GapAwareMessageList.Entry` 多 gap 模型。
- `launchCatchUp`（`CatchUpActions.kt:46`，probe limit=1 + fetch 4 含 sentinel off-by-one）→ probe 改 limit=5（detectGap）；**保留 sentinel 边界正确性的回归测试**（恰好 4/5 条新消息）。
- `launchCloseGap`（step=3/maxSteps=5 backward 补齐）→ 被 `GapFillCoordinator`（step=50）替代。
- UI gap divider（`ChatMessageContent.kt:123/466`）→ 渲染 `GapAwareMessageList.Entry.GapMarker`。

**Files**：`GapAwareMessageList.kt` + `BackfillAlgorithm.kt` + `GapFillCoordinator.kt` + 改 `ChatScreen.kt`/`ChatTextParts.kt`/`ChatMessageContent.kt`（渲染新 Entry）+ 改 `CatchUpActions.kt`（迁移）+ 改 `AppStateSlices.kt`（废 gapInfo）+ 改 `CacheRepository`（gap 方法）+ `SseSyncState.kt`（sessionsEverColdSnapshotted，G6 依据）。

**关键点**：
- **触发条件**（G6 精化 + maxer B4）：`BackfillAlgorithm.shouldProbe(sessionId, currentWorkdir, sessionsEverColdSnapshotted, sseCurrentWorkdir)`——当前 SSE job workdir **且** 在 `sessionsEverColdSnapshotted` 集合里的 session 算保障（不 probe）；其它 probe。`launchCatchUp` 成功 onSuccess 写入 `sessionsEverColdSnapshotted += sessionId`；`purgePerHostState` 重置该集合（避免跨 host 误判）。
- **session 级 Mutex**（freegpt B6/8 + v3 maxer 坑3）：同一 session 的所有 gap fill 串行（不仅同 gapId），因 append 可能 resolve 另一 gap，须 session 维度保持 marker snapshot 与写入事务一致。**实现位置**：`GapFillCoordinator` 内 `private val sessionLocks = ConcurrentHashMap<String, Mutex>()`，`fillForSession(sessionId)` 用 `sessionLocks.computeIfAbsent(sessionId){Mutex()}.withLock{...}`——同一 sessionId 串行、不同 sessionId 并行；**禁用全局单 Mutex**（会阻塞不同 session 并行 fill，长尾延迟×N）。与现有 `isLoadingMessages`（UI 加载态）正交，不替代。
- **SSE message.updated 写 DB**（maxer I11）：insert-if-absent 成功后异步 `cacheRepository.appendMessage(...)`，否则杀进程丢消息。

**GapMarker invariant**（cursor 事实 lib-1 已证 + gpter 闭合）：
- `before` 游标 = `base64url({id,time_ms})`，**响应头返回**，客户端不合成。
- `openGap`：`initialNextBeforeCursor` = probe 响应头；probe 无游标（gap≤5）→ 不 openGap 直接合并。
- `appendOlderSlice(gapId, older, returnedCursor)` 单 Room transaction：① insert；② `stepCoversAnchor`=true→同事务 resolveGap；③ `returnedCursor==null` 未覆盖→`state=exhausted`（UI 标"无法补齐"）；④ 否则 `upperBoundaryMessageId=older.oldest().id` + `nextBeforeCursor=returnedCursor` + state=idle。
- 多 gap 排序按 upperBoundary message time 升序；跨 gap overlap（append 覆盖另一 gapId lowerAnchor）→ 同事务 resolve。
- `resolveGap` 事务原子。

**测试**：detectGap 4 case；stepCoversAnchor 边界；多 gap 独立 cursor/resolve；session 级并发（同 session 两 gap 并发→串行）；**现有 sentinel off-by-one 回归**（恰好 4/5 条）；GapInfo 迁移（废除后无残留引用）。

**Gate**：check.sh --full + gpter 评审算法 + GapInfo 迁移完整性。

---

### Phase 3：淘汰策略 + 每日孤儿扫描（alive 集合完整）

**目标**：三重淘汰（per-serverGroup LRU-50 / newestCachedAt>30d / lastVerifiedAt>7d）+ 每日 sweep（alive 集合**完整**，非 limited UI list）。

**Files**：`CacheRepository.applyEvictionPolicy` + `CacheDao`（淘汰 SQL）+ `ConnectionCoordinator.testConnection`（healthy 分支钩子）+ `SettingsManager`（`lastSweepEpoch_<fp>`）。

**关键点**：
- **alive 集合必须完整**（gpter B3/freegpt——7 模型铁共识）：**禁止用 UI 首屏 `getSessions(limit)` 作 alive 全量**（会误删第一页外的缓存）。**v3（freegpt 建议1+dser S2）：不硬编码 `limit=Int.MAX_VALUE`**（可能触发超大响应/服务端限制），改用"完整枚举策略"：优先服务端分页/roots/目录枚举 + 对已缓存 workdir 做 per-workdir roots 拉取 + 全局 getSessions 补充；任一请求失败/截断/超限→本轮标 incomplete→**降级 mark-only**（刷已确认存活 session 的 lastVerifiedAt，未见标疑似孤儿**不删**，且 lastVerifiedAt 不刷新以免破坏"连续 N 次缺失"判定）；连续 N 次完整扫描缺失或有明确 archive/delete 信号才删。
- LRU-50 per serverGroupFp（SQL 见 §1 CacheDao）；30d/7d 年龄过滤。
- archived 排除 alive 集合（G2）；每日 sweep `testConnection` healthy 分支 + `lastSweepEpoch_<fp>` 24h（epoch day）去重。

**测试**：LRU-50（51 条同组删最旧；跨组独立）；30d/7d 边界；sweep 完整 alive（服务器 100 session 首屏 10，本地缓存第 50 不删）；mark-only 降级（alive 不完整不删）。

**Gate**：check.sh --full + gpter 评审淘汰 + alive 完整性。

---

### Phase 4：存储管理 UI（需求 3）

**目标**：Settings 加 CacheManagementSection，列缓存会话（workdir/createdAt/newestCachedAt/lastVerifiedAt + serverGroup）+ 手动清项目/会话 + 扫描孤儿 + 全清 + **copy-on-split 解除归并逃生口**。

**Files**：`CacheManagementSection.kt` + `SettingsScreen.kt` + `SettingsViewModel.kt`。

**关键点**：
- 沿用 DebugLogSection Hilt `@EntryPoint` 模式取 CacheRepository。
- 列表按 serverGroup 分组；lastVerifiedAt 超 7d 标红"疑似废弃"；exhausted gap 也标注。
- **解除归并 = copy-on-split**（gpter B2）：从旧 fp 复制配置（recentWorkdirs/disabled models/agent/model/draft）到新 fp（拆出 profile.id），旧组保留，缓存 session 不强制迁移。

**Gate**：check.sh --full + Compose preview + gpter 评审。

---

### Phase 5：workdir/项目配置跨连接共享（三类迁移）

**目标**：workdir 配置按 serverGroupFp 共享，**滞后于首次会话匹配触发**（F3）。

**Files**：`SettingsManager.kt`（三类迁移）+ `SessionListActions.kt`（归并）+ `ConnectionActions.applySavedSettings`（迁移触发点）。

**关键点**：
- **三类 SettingsManager 迁移**（gpter/glmer/kimo 共识，v1 错把全当 baseUrl-keyed）：

| 设置 | 当前 key 形态 | 新 key | 迁移规则 |
|---|---|---|---|
| recentWorkdirs | **全局单键** `KEY_RECENT_WORKDIRS`（无 baseUrl 维度） | `recent_workdirs_<fp>` | 全局复制到当前 fp；`purgePerHostState` 改为只清当前 fp（不再 `=emptyList()` 全清，glmer B3） |
| disabled_models / model_availability | `disabled_models_<normalizedBaseUrl>` | `disabled_models_<fp>` | 幂等 merge（set union）；`HostProfileController.clearModelDataForUrl` 改 `clearModelDataForGroup` |
| draft / per-session agent/model | per-session（sessionId-keyed） | **改为 `(serverGroupFp, sessionId)` 复合键**（v3 freegpt #4 定稿，删歧义"或"——sessionId 非 UUID，裸用会跨服务器同 id 串扰，draft 含未发送敏感文本尤甚） | 全局 map 首次迁移到当前 fp；copy-on-split 复制该 fp 下 maps；不同 fp 同 sessionId 必须隔离；加同 id 不同 group 隔离测试 |

- 迁移触发：`applySavedSettings`（冷启动执行一次，dser/maxer）+ 标记位 `cache_migration_v1_done`，重复启动跳过。
- **归并三条件**（gpter B2）：(a) id+createdAt 匹配 **且** (b) directory 相同/交集 **且** (c) session 非空；空 session 不归并；单向归并；误归并走 Phase 4 copy-on-split。
- **归并靠 loadSessions 非 SSE**（dser：SSE 单 workdir）。

**测试**：两 profile 三条件匹配后共享；空 session/directory 不交集不归并；解除归并后隔离；旧 key 三类迁移幂等；purgePerHostState 同组不清 recentWorkdirs。

**Gate**：check.sh --full + gpter 评审归并 + 迁移无丢失。

---

## 4. 开放设计点（round-1 已裁决，全 RESOLVED）

- **G1 serverGroupFp 归并**：三条件（id+createdAt + directory 交集 + 非空）+ copy-on-split 解除。✅
- **G2 archived 算孤儿**：alive 集合排除 archived。✅
- **G3 SQLCipher 版本**：Phase 0 前 librarian 核。✅
- **G4 密钥轮换**：destructive reset（DB open 失败删 DB+key，视同空缓存）。✅
- **G5 session 整体淘汰**：不做 per-slice。✅
- **G6 SSE 保障判定**：依赖 `sessionsEverColdSnapshotted`（Phase 2 新增字段）。✅
- **N1（round-1 新增）复合 PK**：(serverGroupFp, sessionId, messageId)。✅
- **N2 switchTo verify-before-hydrate**：隐私。✅
- **N3 session 级 mutex**：跨 gap overlap。✅
- **N4 alive 集合完整**：禁 limited getSessions，降级 mark-only。✅
- **N5 GapInfo 替代**：废除 ChatState.gapInfo，迁移 launchCatchUp/launchCloseGap。✅
- **N6 EvictSession/EvictGroup sealed**：ControllerEffect 新增 + dispatch（命名统一 EvictGroup，禁 ClearGroup）。✅
- **N7 HostProfile serverGroupFp 前移 Phase 0**。✅
- **N8 4 onCacheWindow hooks + message.updated 写 DB**。✅
- **N9 allowBackup=true + 排除 database 域**（非 false）。✅
- **N10 newestCachedAt 命名**（原 lastUsedAt）。✅
- **N11 SettingsManager 三类迁移**（非全 baseUrl-keyed）。✅
- **N12 session.deleted 不存在**：走 REST + sweep 兜底。✅

---

## 5. 风险与回退

| 风险 | 回退 |
|---|---|
| SQLCipher 破坏 release（R8） | debug 验证 + proguard keep；失败则 feature flag 禁缓存回退纯内存 |
| id+createdAt 误归并 | 三条件 + copy-on-split 解除（非全清） |
| GapInfo 迁移残留引用 | Phase 2 grep `gapInfo` 全清；保留 sentinel 回归测试 |
| switchTo 展示未验证正文（隐私） | verify-before-hydrate；UI 占位 |
| 大 gap 补齐耗流量 | 50-step + 手动 + 7d 淘汰兜底 |
| alive 集合不完整误删 | mark-only 降级，不删 |
| 密钥丢失 | destructive reset，不崩 |
| `clearAllLocalData` 不清 Room | reset 路径显式 clearAll + deleteDatabase |
| Room schema 变更 | 首版 fallbackToDestructiveMigration；稳定后正式 Migration |

---

## 6. 执行顺序与 gate

```
Phase 0（地基 + HostProfile serverGroupFp 前移）→ Phase 1（group-aware 缓存 + 指纹 + EvictSession effect）
  → Phase 2（gap 模型替代 GapInfo + 算法）→ Phase 3（淘汰 + 完整 alive 扫描）
  → Phase 4（管理 UI + copy-on-split）→ Phase 5（三类 workdir 共享）
每 Phase gate：check.sh --full + koverVerify ≥60/56 + gpter 评审
全部完成：4 路门控（gpter+glmer+dser+kimo）→ release.sh patch
```

---

## 7. Self-Review

- **Spec 覆盖**：需求 1→Phase 1（EvictSession 精确删）+ Phase 3（sweep+淘汰）；需求 2→Phase 2；需求 3→Phase 4；需求 4→Phase 5。✅
- **占位符**：接口签名锁定；N1–N12 全 RESOLVED。✅
- **类型一致**：复合 PK / CachedWindow=CachedSessionWindow / EvictSession(serverGroupFp,sessionId) 各处一致。✅
- **round-1 阻塞并集覆盖**：5 铁共识 + glmer GapInfo + maxer EvictSession/message.updated + freegpt 内存key/switchTo/mutex + kimo selectHostProfile/类型 + momo createdAt可空/Robolectric + dser backup互斥/applySavedSettings + gpter 复合PK/alive —— 全部落地。✅
