# 持久化聊天记录 + 已连接项目共享 — 实施 Handoff

> **用途**：本文档供**派给其他 agent 实施**用。含三部分：① 方案概览（设计决策）② 阶段计划（Phase 0-5 依赖/并行/gate）③ **每 Phase 完整提示词**（自包含，可直接粘贴）。
> **权威设计文档**：`docs/features/persistent-chat-cache-plan.md`（v4，实施就绪）。本 handoff 是它的可派发摘要 + 提示词集。
> **评审基线**：7 模型 3 轮评审收敛（round-3 glmer 8.5 PASS / momo 8.7 / freegpt 8.1，设计层无阻塞）。存档 `.opencode/runs/reviews/2026-07-06/persistent-cache-7model-comparison{,-r2,-r3}.json`。
> **项目硬规则**（AGENTS.md）：每次改 Kotlin/资源后必跑 `./scripts/check.sh`；版本号走 `./scripts/release.sh` 禁手改；主线 main；模拟器占用纪律；TDD。

---

## ① 方案概览（设计决策 F1–F8 + N1–N15）

**目标**：ocdroid 首次引入本地加密持久化聊天缓存 + 跨连接（同一 opencode 服务器的内网/tailscale 两接入点）共享已连接项目配置；重连按 gap 算法补齐；存储管理 UI。

**架构**（6 句话）：
1. **内容寻址缓存**（非人工 link）：按 `(serverGroupFp, sessionId)` 复合键控；跨 profile 靠 `(sessionId, createdAt, directory)` 指纹匹配自然关联两个接入点，匹配才归并共享。
2. **SQLCipher 加密 Room DB**：密钥存 EncryptedSharedPreferences（Keystore），`allowBackup=true` + 排除 database 域（纵深防御），DB open 失败 destructive reset 不崩。
3. **非连续消息模型**（slice + gap marker，≥1 gap）：服务端只有 `before` 游标（无 after），"补齐"= backward page from newest + dedup；缓存真实价值=即时显示 + 老历史持久化，不省流量。
4. **三重淘汰**：per-serverGroup LRU-50 / `newestCachedAt>30d` / `lastVerifiedAt>7d`；每日 sweep（alive 集合**完整**才删，不完整 mark-only）。
5. **清除粒度**：归档/删除/指纹不符 → 精确删一条（`EvictSession` effect）；异组切换 → `EvictGroup`；核武器仅手动全清。**不核武器清所有组持久 DB**。
6. **归并三条件 + copy-on-split 逃生口**：归并需 id+createdAt **+ directory 交集 + 非空 session**；误归并可"解除归并"（copy-on-split，旧组保留）。

**关键不变量**：
- `switchTo` **禁改 suspend**（保护 8 步同步 FIFO effect 发射）；verify-before-hydrate 走新 `VerifyAndHydrate` effect（handler 在 appScope.launch 内 `verifyAndLoad` 单事务）。
- `verifyAndLoad` 单 Room `@Transaction`（消除 verify+load 两步 TOCTOU）。
- `HostProfile.serverGroupFp` **nonblank invariant**（decode 后 normalize blank→id）。
- session 级 gap Mutex（`ConcurrentHashMap<String,Mutex>`，非全局单）。
- createdAt==null → `UnknownColdStart`（不 evict 不 hydrate，冷启动）。

---

## ② 阶段计划（Phase 0-5）

```
Phase 0（地基：SQLCipher Room + HostProfile serverGroupFp 前移 + 密钥 + 备份排除）
  ↓ 依赖
Phase 1（group-aware 缓存 + verifyAndLoad 单事务指纹 + EvictSession/EvictGroup/VerifyAndHydrate effects + 6 onCacheWindow hooks + select 4-step + 同组字段分类）
  ↓ 依赖
Phase 2（非连续消息模型 + gap 补齐算法，**替代现有 GapInfo/launchCatchUp/launchCloseGap** + session 级 Mutex）
  ↓ 依赖
Phase 3（三重淘汰 + 每日 sweep，alive 完整枚举 + mark-only 降级 + DailySweepReport）
  ↓ 可并行
Phase 4（存储管理 UI + copy-on-split 解除归并）   ← 与 Phase 3/5 可并行（写作用域不重叠）
Phase 5（workdir 配置三类迁移 + 归并三条件 + applySavedSettings 迁移触发）
```

**依赖**：0→1→2 串行（共享持久层 + 消息模型）；3 依赖 1；4 依赖 1（UI 读缓存）；5 依赖 0（serverGroupFp）+ 1（复核），可与 3/4 部分并行。

**每 Phase gate**：`./scripts/check.sh --full` 全绿 + koverVerify ≥60/56 + 该 Phase 测试覆盖规定场景。

**并行 fixer 纪律**：写作用域（文件级）零冲突才并行。Phase 4（`CacheManagementSection.kt` + `SettingsScreen.kt` + `SettingsViewModel.kt`）与 Phase 5（`SettingsManager.kt` + `SessionListActions.kt` + `ConnectionActions.kt`）可并行（不碰对方文件）。

---

## ③ 完整提示词（每 Phase 一个，自包含，可直接粘贴）

> 每个提示词假设：实施 agent 先 Read `docs/features/persistent-chat-cache-plan.md`（v4）+ AGENTS.md，再执行。提示词内嵌关键代码骨架（来自 round-3 评委），agent 应直接采用。

---

### 提示词 · Phase 0（地基 + HostProfile serverGroupFp 前移）

```
R-20 Phase 0：持久化缓存地基（SQLCipher Room + HostProfile.serverGroupFp + 密钥 + 备份排除）。

项目根：/home/mar/personal_projects/ocdroid
必读：AGENTS.md（改完 ./scripts/check.sh；版本号走 release.sh；主线 main）+ docs/features/persistent-chat-cache-plan.md（v4 §3 Phase 0）。

## 任务（纯地基，无业务逻辑）
1. 依赖：libs.versions.toml + build.gradle.kts 加 androidx.room + androidx.sqlite + net.zetetic:sqlcipher-android（先 librarian/Context7 核版本 + R8 keep 规则）。
2. 新建 data/cache/CacheDatabase.kt（Room @Database，SQLCipher SupportFactory 注入）。
3. 新建 data/cache/Entities.kt：
   - CachedSessionEntity(primaryKeys=[serverGroupFp,sessionId], createdAt:Long?, newestCachedAt:Long, lastVerifiedAt:Long, workdir:String)
   - CachedMessageEntity(primaryKeys=[serverGroupFp,sessionId,messageId], time:Long, role:String, parts:String /*JSON blob*/）
   - 复合 PK（非裸 sessionId/messageId，防克隆服务器碰撞）
4. 新建 data/cache/CacheKeyStore.kt：32 字节随机密钥存 EncryptedSharedPreferences(key=cache_db_key)，首次生成永不轮换；cache_db_key 加入 clearAllLocalData preservedKeys 白名单。
5. 新建 di/CacheModule.kt：@Provides @Singleton CacheDatabase（SupportFactory SQLCipher）+ CacheRepository 占位；DB open 失败 catch→destructive reset（删 DB 文件+key，视同空缓存，不崩）。
6. HostProfile.serverGroupFp 前移：data/model/HostProfile.kt 加 `val serverGroupFp: String = ""`；HostProfileStore.decodeProfiles() 成功后 normalize `if (serverGroupFp.isBlank()) copy(serverGroupFp = id)`；save()/profiles() 保证 nonblank；import 不导出 serverGroupFp（导入新建独立组）。Store 加 mergeServerGroup(from:String,into:String)/splitProfileToOwnGroup(profileId:String)/profilesInGroup(serverGroupFp:String):List<HostProfile>。
7. 备份：保留 AndroidManifest allowBackup="true"；res/xml/backup_rules.xml + res/xml/data_extraction_rules.xml 都加 `<exclude domain="database" path="."/>`。
8. Message.parts JSON：Json { ignoreUnknownKeys = true; encodeDefaults = true }。

## 测试
- CacheDatabaseTest（Robolectric in-memory Room，不挂 SupportFactory——Robolectric 跑不了真 SQLCipher native lib）：schema/密钥往返/CRUD。
- HostProfileStoreTest：旧 JSON 多 profile decode 后每个 serverGroupFp==id 且互不相同（防全落 blank group）。
- 真 SQLCipher 集成走 connectedDebugAndroidTest（仅模拟器）。

## 约束
- 不碰 SessionSwitcher/MessageActions/VM（Phase 1 负责）。
- 不改业务逻辑。
- 自检 source ./scripts/env.sh && ./gradlew compileDebugKotlin && compileDebugUnitTestKotlin 必过（禁跑 check.sh，编排者统一跑）。

## 返回
报告：依赖版本 + SupportFactory 集成方式 + HostProfileStore nonblank 实现 + 测试结果 + compile 结果。
```

---

### 提示词 · Phase 1（group-aware 缓存 + 指纹 + EvictSession/EvictGroup/VerifyAndHydrate）

```
R-20 Phase 1：group-aware 缓存 + verifyAndLoad 单事务指纹 + 三个新 effect + 6 onCacheWindow hooks + select 4-step + 同组字段分类。
依赖 Phase 0 完成（CacheDatabase + HostProfile.serverGroupFp 就位）。

项目根：/home/mar/personal_projects/ocdroid
必读：docs/features/persistent-chat-cache-plan.md（v4 §2 接口 + §3 Phase 1 + 矩阵）。

## 任务

### 1. CacheRepository 完整实现（data/cache/CacheRepository.kt + CacheDao.kt）
接口签名严格按 v4 §2。核心方法：
- `verifyAndLoad(fp, sid, createdAt): HydrateResult` —— **单 Room @Transaction**（消除 TOCTOU）：createdAt==null→UnknownColdStart；SELECT cached createdAt↔入参不等→evictSession+MismatchEvicted；等→touchLastVerifiedAt(now)+loadWindow，window!=null→Verified(window) else UnknownColdStart。
- putSessionWindow/evictSession/evictGroup/clearAll/sweepOrphansWithCompleteAliveSet/markSeenAliveOnly/applyEvictionPolicy。
- HydrateResult sealed（Verified(window)/UnknownColdStart/MismatchEvicted）；FingerprintResult sealed（同三态，verifyFingerprint 独立方法仅供 loadSessions 归并）。

### 2. ControllerEffect sealed 新增（ui/controller/ControllerEffect.kt）
```kotlin
data class VerifyAndHydrate(val serverGroupFp:String, val sessionId:String, val createdAt:Long?) : ControllerEffect()
data class EvictSession(val serverGroupFp:String, val sessionId:String) : ControllerEffect()
data class EvictGroup(val serverGroupFp:String) : ControllerEffect()  // 禁用 ClearGroup 命名
```

### 3. AppCore dispatch 分支（ui/AppCore.kt dispatchSessionEffect/dispatchHostEffect）
VerifyAndHydrate handler（关键，直接采用 round-3 收敛代码）：
```kotlin
is ControllerEffect.VerifyAndHydrate -> {
    appScope.launch {
        if (effect.sessionId != store.chatFlow.value.currentSessionId) return@launch  // 用户可能已切走
        when (val r = cacheRepository.verifyAndLoad(effect.serverGroupFp, effect.sessionId, effect.createdAt)) {
            is HydrateResult.Verified -> {
                store.mutateChat { it.copy(messages=r.window.messages, partsByMessage=r.window.partsByMessage,
                    olderMessagesCursor=r.window.olderMessagesCursor, hasMoreMessages=r.window.hasMoreMessages) }
                loadMessagesForEffect(effect.sessionId, resetLimit=false)
            }
            HydrateResult.UnknownColdStart, HydrateResult.MismatchEvicted ->
                loadMessagesForEffect(effect.sessionId, resetLimit=true)
        }
    }; true
}
is ControllerEffect.EvictSession -> { sessionSwitcher.evictSession(e.serverGroupFp,e.sessionId); appScope.launch{cacheRepository.evictSession(e.serverGroupFp,e.sessionId)}; true }
is ControllerEffect.EvictGroup -> { sessionSwitcher.clearMemoryForGroup(e.serverGroupFp); appScope.launch{cacheRepository.evictGroup(e.serverGroupFp)}; true }
```
AppCore 注入 CacheRepository（Hilt @Singleton）。

### 4. SessionSwitcher（ui/controller/SessionSwitcher.kt）
- SessionWindowCache key 改 CacheWindowKey(serverGroupFp, sessionId)。
- clearSessionWindowCache 拆为 evictSession(fp,sid)/clearMemoryForGroup(fp)/clearAllCached()。
- 构造加 currentServerGroupFp: ()->String。
- **switchTo 改造（禁改 suspend，禁 Step 7 发 LoadMessages）**：Step 3 不再同步读 LRU 注入正文，改同步发 VerifyAndHydrate(fp,sid, targetSession.time.created)；Step 7 只发 LoadSessionStatus+LoadChildSessions（**移除 LoadMessages**，它由 VerifyAndHydrate handler 独占）。

### 5. 6 个 onCacheWindow hooks（ui/MessageActions.kt:28/305 + CatchUpActions.kt:52/216 + SessionViewModel.kt:309）
签名不变 (sessionId,window)。在 AppCore 抽 makeCacheHook(fp)：
```kotlin
internal fun AppCore.makeCacheHook(fp:String):(String,CachedSessionWindow)->Unit = { sid, window ->
    sessionSwitcher.writeSessionWindow(sid, window)  // 内存 LRU 同步
    val session = store.sessionListFlow.value.sessions.firstOrNull { it.id == sid }
    val createdAt = session?.time?.created
    val workdir = session?.directory ?: settingsManager.currentWorkdir ?: ""
    appScope.launch { runCatching { cacheRepository.putSessionWindow(fp, sid, createdAt, workdir, window) }
        .onFailure { DebugLog.e(TAG,"cache write failed for $sid",it) } }
}
```
6 调用方（AppCoreOrchestration:334/372、ChatViewModel:70/88/99、SessionViewModel:309）传入 makeCacheHook(hostProfileStore.currentProfile().serverGroupFp)（**闭包创建时捕获 fp，不在 launch 体内重取**）。

### 6. SessionMutationActions（ui/SessionMutationActions.kt）
launchSetSessionArchived（含子树 ids 循环 :77-80）每个 subtree id emit EvictSession(fp,id)；launchDeleteSession REST onSuccess emit EvictSession(fp,id)。需注入 currentServerGroupFp + emitEffect 回调（从 VM effectBus）。

### 7. SessionSyncCoordinator（ui/controller/SessionSyncCoordinator.kt）
构造加 currentServerGroupFp。archived 分支(:389-414) emit EvictSession(fp,id)；message.updated insert-if-absent 成功后异步写 DB（否则杀进程丢消息）。

### 8. HostProfileController（ui/controller/HostProfileController.kt）
- selectHostProfile 4-step（**select 前 snapshot previousFp**，因 hostProfileStore.select() 有副作用改 currentProfile）：① previousFp=currentHostProfile().serverGroupFp ② select ③ targetFp=profile.serverGroupFp ④ 同组不清/异组 emit EvictGroup(previousFp)。
- purgePerHostState group 隔离 + 字段分类：同服务器数据(sessions/directorySessions/unread/cache/recentWorkdirs/disabled_models)同组保留；per-profile UX(openSessionIds/draft/currentWorkdir)仍 reset。
- resetLocalDataAndResync：cacheRepository.clearAll() + context.deleteDatabase() + 删 wal/shm（先 DB 后 prefs）。

### 9. ControllerModule（di/ControllerModule.kt）
@Provides currentServerGroupFp:()->String = { hostProfileStore.currentProfile().serverGroupFp.ifBlank { it.id } }；注入 SessionSwitcher/SessionSyncCoordinator。

### 10. ConnectionActions（ui/ConnectionActions.kt applySavedSettings）
applySavedSettings 同步部分只做 ES 元数据 seed；verify hoist 到 AppCore.init 的 appScope.launch（非归并触发，仅缓存自洽检查）。

## 测试（表驱动，项目 R-18/R-19 风格）
- verifyAndLoad 5 case（同id同createdAt/同id异createdAt/异id/首次冷启动/createdAt==null）。
- verifyAndLoad 单事务原子性（并发 EvictSession 不产生 TOCTOU）。
- 归档子树→每个 id 删；切同组保留/异组 EvictGroup；switchTo 不展示未验证正文（隐私回归）。
- EvictSession/EvictGroup/VerifyAndHydrate dispatch 路由。

## 约束
- 不碰 GapInfo/CatchUpActions gap 逻辑（Phase 2 负责）。
- 不碰淘汰/sweep（Phase 3）、UI（Phase 4）、workdir 迁移（Phase 5）。
- 自检 compileDebugKotlin + compileDebugUnitTestKotlin + 相关 *Test。

## 返回
报告：CacheRepository 实现、3 effect dispatch、switchTo 改造、6 hooks、select 4-step、字段分类、compile+test 结果。
```

---

### 提示词 · Phase 2（非连续消息模型 + gap 算法，替代 GapInfo）

```
R-20 Phase 2：非连续消息模型（slice + gap marker，≥1 gap）+ gap 补齐算法，**替代现有 GapInfo/launchCatchUp/launchCloseGap**。
依赖 Phase 1 完成（CacheRepository gap 方法 + SseSyncState 字段）。

项目根：/home/mar/personal_projects/ocdroid
必读：docs/features/persistent-chat-cache-plan.md（v4 §3 Phase 2 + GapMarker invariant）。

## 现有实现盘点（必须迁移，glmer B1）
- GapInfo(anchorNewestId,tailOldestId,tailOldestCursor,open)（AppStateSlices.kt:307）单 gap 内存 → **废除 ChatState.gapInfo**，迁移到 GapAwareMessageList.Entry 多 gap。
- launchCatchUp（CatchUpActions.kt:46，probe limit=1+fetch 4 sentinel）→ probe 改 limit=5（detectGap）；保留 sentinel off-by-one 回归测试。
- launchCloseGap（step=3/maxSteps=5）→ 被 GapFillCoordinator(step=50) 替代。
- UI gap divider（ChatMessageContent.kt:123/466）→ 渲染 GapAwareMessageList.Entry.GapMarker。

## 任务
1. ui/chat/GapAwareMessageList.kt：sealed interface Entry { Message; GapMarker(gapId, fillState) }。
2. ui/chat/BackfillAlgorithm.kt（纯函数）：anchorOf(cached)=cached.dropLast(3).lastOrNull()；extractMessages(layout)；detectGap(anchor,fetched5):GapDetection{NoGap/GapExists(lowerAnchor,upperBoundary,initialNextBeforeCursor)}；stepCoversAnchor(step,anchor)；shouldProbe(sessionId,currentWorkdir,sessionsEverColdSnapshotted,sseCurrentWorkdir)。
3. ui/chat/GapFillCoordinator.kt：状态机 probe(5)→detect→50-step fill；**session 级 Mutex**（private val sessionLocks=ConcurrentHashMap<String,Mutex>()，fillForSession 用 computeIfAbsent(sessionId){Mutex()}.withLock，同一 session 串行不同 session 并行，禁全局单 Mutex）；近 gap 10 条预取；手动点 gap marker 触发该 gapId 50-step。
4. 改 ChatScreen.kt/ChatTextParts.kt/ChatMessageContent.kt：渲染 GapMarker entry（"存在未加载 gap，点击加载下 50 条"，无条数）+ 点击。
5. 改 MessageActions.kt：probe/stepFill 接 BackfillAlgorithm。
6. CacheRepository gap 方法（openGap/appendOlderSlice/setGapState/resolveGap/gapsOf）实现。
7. SseSyncState.kt：加 sessionsEverColdSnapshotted:Set<String>（launchCatchUp 成功 onSuccess += sessionId；purgePerHostState = emptySet）；G6 SSE 保障判定依据。

## GapMarker invariant（严格）
- cursor 事实：服务端 before=base64url({id,time_ms})，响应头返回（X-Next-Cursor/Link rel=next），客户端不合成。
- openGap：initialNextBeforeCursor=probe 响应头；probe 无游标（gap≤5）→不 openGap 直接合并。
- appendOlderSlice(gapId,older,returnedCursor) 单 Room transaction：①insert ②stepCoversAnchor=true→同事务 resolveGap ③returnedCursor==null 未覆盖→state=exhausted ④否则 upperBoundaryMessageId=older.oldest().id+nextBeforeCursor=returnedCursor+state=idle。
- 多 gap 排序按 upperBoundary message time 升序；跨 gap overlap（append 覆盖另一 gapId lowerAnchor）→同事务 resolve。
- resolveGap 事务原子。

## 测试（表驱动）
- detectGap 4 case；stepCoversAnchor 边界；多 gap 独立 cursor/resolve；session 级并发（同 session 两 gap 并发→串行，Mutex）；现有 sentinel off-by-one 回归（恰好 4/5 条新消息）；GapInfo 迁移（grep gapInfo 全仓无残留引用，约 6 文件 18 处）。

## 约束
- 不碰淘汰/sweep/UI管理/workdir迁移（其它 Phase）。
- 自检 compileDebugKotlin + compileDebugUnitTestKotlin + *GapReconcile*/*Backfill*/*CatchUp*。

## 返回
报告：GapAwareMessageList 模型、BackfillAlgorithm、GapFillCoordinator Mutex 实现、GapInfo 迁移完整性（grep 无残留）、测试结果。
```

---

### 提示词 · Phase 3（三重淘汰 + 每日 sweep，alive 完整）

```
R-20 Phase 3：三重淘汰（per-serverGroup LRU-50 / newestCachedAt>30d / lastVerifiedAt>7d）+ 每日 sweep（alive 完整枚举 + mark-only 降级）。
依赖 Phase 1 完成。

项目根：/home/mar/personal_projects/ocdroid
必读：docs/features/persistent-chat-cache-plan.md（v4 §3 Phase 3 + DailySweepReport/AliveCompleteness）。

## 任务
1. CacheDao 淘汰 SQL：
   - LRU-50 per serverGroupFp：DELETE FROM cached_session WHERE serverGroupFp=? AND sessionId NOT IN (SELECT sessionId FROM cached_session WHERE serverGroupFp=? ORDER BY newestCachedAt DESC LIMIT 50)。
   - 30d/7d：DELETE ... WHERE newestCachedAt <? / lastVerifiedAt <?。
2. CacheRepository.applyEvictionPolicy():EvictionReport（evictedCount/keptCount/orphanIds）实现三重。
3. **alive 完整性归内部（v4 freegpt 建议2）**：新建 CacheMaintenanceCoordinator（cache/openCodeRepository/settingsManager）：
   - dailySweepIfNeeded(fp):DailySweepReport —— 内部 enumerateCompleteAliveSet（服务端分页/roots/目录枚举 + 已缓存 workdir per-workdir roots 拉取 + 全局 getSessions 补充；任一失败/截断→complete=false）。
   - complete→sweepOrphansWithCompleteAliveSet（archived 排除 alive）；incomplete→markSeenAliveOnly（刷已确认 lastVerifiedAt，未见标疑似**不删**）。
   - 24h 去重：SettingsManager lastSweepEpoch_<fp>（epoch day）。
4. ConnectionCoordinator.testConnection healthy 分支调 cacheMaintenanceCoordinator.dailySweepIfNeeded(currentServerGroupFp())。

## 测试
- LRU-50（51 条同组删最旧；跨组独立）；30d/7d 边界（29d留/31d删；6d留/8d删）。
- sweep 完整 alive（服务器 100 session 首屏 10，本地缓存第 50 不删）。
- mark-only 降级（alive incomplete 不删，只刷 lastVerifiedAt）。
- archived 排除 alive 集合（G2）。

## 约束
- 不硬编码 limit=Int.MAX_VALUE（用完整枚举策略）。
- 不碰 gap/UI/workdir迁移。
- 自检 compile + *Eviction*/*Sweep* 测试。

## 返回
报告：淘汰 SQL、CacheMaintenanceCoordinator、alive 枚举策略、测试结果。
```

---

### 提示词 · Phase 4（存储管理 UI + copy-on-split）—— 可与 Phase 5 并行

```
R-20 Phase 4：存储管理 UI（需求 3）+ copy-on-split 解除归并逃生口。
依赖 Phase 1（读 CacheRepository）。

项目根：/home/mar/personal_projects/ocdroid
必读：docs/features/persistent-chat-cache-plan.md（v4 §3 Phase 4）。

## 任务
1. ui/settings/CacheManagementSection.kt：沿用 DebugLogSection Hilt @EntryPoint 模式取 CacheRepository。
2. 列表按 serverGroup 分组，每行：workdir/sessionId缩写/createdAt/newestCachedAt/lastVerifiedAt（超7d标红"疑似废弃"）；exhausted gap 标注。
3. 手动清单个 session（evictSession(group,id)）/ 手动清项目 workdir（清该 group+workdir，禁 clearAll）/ 扫描孤儿按钮（触发 dailySweepIfNeeded，离线禁用+提示）/ 全清按钮（clearAll 唯一核武器）。
4. **copy-on-split 解除归并**（gpter B2）：每 serverGroup 行"拆分归并"动作 → hostProfileStore.splitProfileToOwnGroup(profileId)；从旧 fp 复制配置（recentWorkdirs/disabled_models/agent/model/draft）到新 fp（拆出 profile.id），旧组保留，缓存 session 不强制迁移。

## 测试
- Compose preview；列表数据完整；clearSession/clearProject/clearAll/sweepNow/copy-on-split 动作可达。

## 约束
- 写作用域：CacheManagementSection.kt + SettingsScreen.kt + SettingsViewModel.kt。**不碰** SettingsManager.kt/SessionListActions.kt/ConnectionActions.kt（Phase 5）。
- 自检 compileDebugKotlin。

## 返回
报告：UI 实现、@EntryPoint 接入、copy-on-split 动作、preview。
```

---

### 提示词 · Phase 5（workdir 三类迁移 + 归并三条件）—— 可与 Phase 4 并行

```
R-20 Phase 5：workdir 配置跨连接共享（三类迁移 + 归并三条件 + applySavedSettings 迁移触发）。
依赖 Phase 0（serverGroupFp）+ Phase 1（复核）。

项目根：/home/mar/personal_projects/ocdroid
必读：docs/features/persistent-chat-cache-plan.md（v4 §3 Phase 5 三类迁移表）。

## 任务
1. SettingsManager 三类迁移（**v4 修正 v1 错把全当 baseUrl-keyed**）：
   - recentWorkdirs（当前**全局单键** KEY_RECENT_WORKDIRS，无 baseUrl 维度）→ 改 per-serverGroupFp（recent_workdirs_<fp>）。addRecentWorkdir 签名改 (serverGroupFp, workdir)（调用方 ConnectionCoordinator.loadInitialData:329 + AppCoreOrchestration:423 传当前 fp）。purgePerHostState 改为只清当前 fp（不再 =emptyList() 全清）。
   - disabled_models/model_availability（当前 disabled_models_<normalizedBaseUrl>）→ 改 <serverGroupFp>；clearModelDataForUrl→clearModelDataForGroup。
   - draft/per-session agent/model（当前 sessionId-keyed 全局 map）→ 改 (serverGroupFp, sessionId) 复合键（v4 freegpt #4，sessionId 非 UUID 必须复合）。key 格式 "${serverGroupFp}:${sessionId}"（分隔符防碰撞用 \0 或禁止 fp 含分隔符）。
2. applySavedSettings 是迁移触发点（冷启动执行一次）：读旧 key 写新 serverGroupFp key + 迁移标记位 cache_migration_v1_done（ES，非 Room，因迁移在 DB 可能未初始化时），重复启动跳过。
3. SessionListActions.loadSessions 归并：首次 id+createdAt 匹配成功 → **三条件**（id+createdAt **+ directory 相同/交集 + session 非空**）→ 把 B 的组归并到 A（mergeServerGroup(from=B_fp, into=A_fp) 单向）。空 session/directory 不交集不归并。归并靠 loadSessions 非 SSE（SSE 单 workdir）。

## 测试
- 两 profile 三条件匹配后 workdir 配置共享；空 session/directory 不交集不归并；解除归并（copy-on-split）后隔离；旧 key 三类迁移幂等（二次启动不重复）；purgePerHostState 同组不清 recentWorkdirs；同 id 不同 group 隔离。

## 约束
- 写作用域：SettingsManager.kt + SessionListActions.kt + ConnectionActions.kt + HostProfileController.kt(purgePerHostState/clearModelDataForGroup)。**不碰** CacheManagementSection/SettingsScreen/SettingsViewModel（Phase 4）。
- 自检 compileDebugKotlin + compileDebugUnitTestKotlin + *Settings*/*Migration*/*Merge*。

## 返回
报告：三类迁移实现、addRecentWorkdir 签名改造、applySavedSettings 迁移触发、归并三条件、测试结果。
```

---

## 派发建议

- **串行**：Phase 0 → 1 → 2（共享持久层 + 消息模型，依赖链）。
- **Phase 3** 依赖 1 完成后启动。
- **Phase 4 与 Phase 5 可并行**（写作用域零冲突：UI 三件 vs SettingsManager/Actions 三件）。
- 每 Phase 完成后编排者跑 `./scripts/check.sh --full` + koverVerify ≥60/56 + 该 Phase 评审（建议 gpter+glmer 并行 ≥9.0）。
- 全部完成：4 路门控（gpter+glmer+dser+kimo 或按可用性）→ release.sh patch。

## 评审产物归档
每 Phase gate 评审写 `.opencode/runs/reviews/<date>/R20-phase<N>-<reviewer>.json`。
